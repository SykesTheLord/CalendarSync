# CalendarSync

A self-hosted, multi-user web app that connects to Google Calendar, Microsoft
365/Outlook, iCloud, generic CalDAV servers, and read-only ICS URL
subscriptions, lets you define rules for which events should be removed, and
applies them - either by deleting the event directly (where the provider
supports writes) or by publishing a filtered ICS feed (for read-only sources
like a Proton Calendar subscription). Every deletion is restorable from a
Trash view, not just logged.

Built with Java 25, Spring Boot 4, Vaadin 25 (Flow), Quartz, and SQLite.

See [NOTES.md](NOTES.md) for a running log of where the implementation
deviates from the original spec and why.

## Contents

- [Requirements](#requirements)
- [Running locally](#running-locally)
- [First login](#first-login)
- [Connecting a calendar provider](#connecting-a-calendar-provider)
  - [Google Calendar (OAuth app registration)](#google-calendar-oauth-app-registration)
  - [Microsoft 365 / Outlook (Azure AD app registration)](#microsoft-365--outlook-azure-ad-app-registration)
  - [iCloud](#icloud)
  - [Generic CalDAV](#generic-caldav)
  - [ICS URL source (e.g. Proton Calendar)](#ics-url-source-eg-proton-calendar)
- [Rules](#rules)
  - [Condition operators](#condition-operators)
- [Publishing a feed (including pointing Proton at one)](#publishing-a-feed-including-pointing-proton-at-one)
  - [Export settings](#export-settings)
- [Trash and restore](#trash-and-restore)
- [Two-factor authentication](#two-factor-authentication)
- [Admin: creating additional users](#admin-creating-additional-users)
- [Deploying with Docker](#deploying-with-docker)
  - [Autostart on reboot](#autostart-on-reboot)
  - [Exposing port 8080 safely](#exposing-port-8080-safely)
- [Native install (Ubuntu)](#native-install-ubuntu)
- [Updating an install](#updating-an-install)
- [Environment variables](#environment-variables)
- [Logging](#logging)
- [Encryption](#encryption)
- [Running tests](#running-tests)
- [Architecture notes](#architecture-notes)

## Requirements

- Java 25 (JDK), on `PATH` or via `JAVA_HOME`
- Maven (or use `./mvnw` if you generate the wrapper)
- Node.js, for Vaadin to (re)build its frontend bundle - needed the first
  time you run the app after a change under `src/main/frontend/themes/`
  (the custom theme), and always for a `-Pprod` build
- Docker, if deploying via the provided `Dockerfile`/`docker-compose.yml`

## Running locally

```bash
export JAVA_HOME=/path/to/jdk-25   # if it isn't already your default JDK
mvn spring-boot:run
```

This uses the `dev` Maven profile (default) and `dev` Spring profile
(default): an unencrypted SQLite database at `./data/calendarsync-dev.db`,
created automatically on first run, with Vaadin's live-reload dev server.

The app listens on `http://localhost:8080`.

## First login

On first startup, if no users exist yet, the app creates a single `ADMIN`
account with a randomly generated password and prints it to the application
log **exactly once**:

```
============================================================
CalendarSync: no users existed, created a first-run admin account.
This password is shown ONLY this one time - it is not stored
anywhere except as a bcrypt hash. Log in and change it, or
create a personal admin account and disable this one.

  username: admin
  password: <random>
============================================================
```

Log in with those credentials, then either keep using that account or
create a personal one via the Admin view and disable the bootstrap account.
There is currently no "change password" UI - to rotate the bootstrap
account's password, disable it and create a new admin user instead.

## Connecting a calendar provider

All connections are managed from the **Connections** view.

### Google Calendar (OAuth app registration)

1. Go to the [Google Cloud Console](https://console.cloud.google.com/),
   create (or pick) a project.
2. **APIs & Services → Library**: enable the **Google Calendar API**.
3. **APIs & Services → OAuth consent screen**: configure it (External is
   fine for personal use; add your own account as a test user while the app
   is in "Testing" publishing status).
4. **APIs & Services → Credentials → Create Credentials → OAuth client ID**:
   - Application type: **Web application**
   - Authorized redirect URI: `<your CALENDARSYNC_BASE_URL>/oauth2/google/callback`
     (e.g. `http://localhost:8080/oauth2/google/callback` for local dev)
5. Copy the generated **Client ID** and **Client secret** into the
   `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` environment
   variables and restart the app.
6. In the Connections view, click **Add connection**, pick provider
   **Google Calendar**, continue to Google and approve access.

### Microsoft 365 / Outlook (Azure AD app registration)

1. Go to the [Azure Portal](https://portal.azure.com/) → **Microsoft Entra
   ID → App registrations → New registration**.
2. Name it, choose the supported account type that matches your tenant
   (single tenant, or "any org + personal Microsoft accounts" for
   `MS_OAUTH_TENANT_ID=common`), and set the redirect URI:
   - Platform: **Web**
   - Redirect URI: `<your CALENDARSYNC_BASE_URL>/oauth2/microsoft/callback`
3. **Certificates & secrets → New client secret**: create one and copy its
   *value* (not the secret ID) immediately - it's only shown once.
4. **API permissions → Add a permission → Microsoft Graph → Delegated
   permissions**: add `Calendars.ReadWrite` and `offline_access`, then grant
   admin consent if your tenant requires it.
5. Copy the **Application (client) ID**, the client secret value, and (if
   not using `common`) your **Directory (tenant) ID** into
   `MS_OAUTH_CLIENT_ID` / `MS_OAUTH_CLIENT_SECRET` / `MS_OAUTH_TENANT_ID`
   and restart the app.
6. In the Connections view, click **Add connection**, pick provider
   **Microsoft 365**, continue to Microsoft and approve access.

### iCloud

1. Generate an [app-specific password](https://support.apple.com/en-us/102654)
   for your Apple ID (Settings → Sign-In & Security → App-Specific
   Passwords).
2. In the Connections view, add a connection with provider **ICLOUD**,
   credentials `your-apple-id@example.com:the-app-specific-password`.

### Generic CalDAV

Works with Fastmail, Nextcloud, and any RFC 4791-compliant server.

1. Find your server's CalDAV base URL (e.g. Fastmail:
   `https://caldav.fastmail.com/`, Nextcloud:
   `https://your-nextcloud/remote.php/dav/`).
2. Add a connection with provider **CALDAV**, that base URL, and
   credentials `username:password` (an app password if your server supports
   one).

### ICS URL source (e.g. Proton Calendar)

Proton Calendar doesn't support CalDAV, only "subscribe by URL" - so this
app treats it (and anything similar) as a **read-only ICS source** that
gets filtered through a **published feed** rather than deleted from
directly.

1. In Proton, get your calendar's private ICS subscription URL.
2. In the Connections view, add a connection with provider **ICS_SOURCE**
   and that URL in the base-URL field.
3. See [Publishing a feed](#publishing-a-feed-including-pointing-proton-at-one)
   below for how to build a filtered feed from it and point Proton back at
   the result.

## Rules

The **Rules** view is where you define what gets removed. A new rule
defaults to **DRY_RUN** (logs what it would do; deletes nothing) - switch it
to **DELETE** explicitly once you trust it.

A rule with no scope never matches anything. Open a rule and use its
**Scope** section to attach it to either:

- **a calendar** - for real deletion. Refused for read-only calendars
  (subscribed/holiday calendars, or anything from an `ICS_SOURCE`
  connection) at save time, with a clear error rather than a silent no-op.
- **a published feed** - for filtering. This is the *only* way to exclude
  events from a read-only source; it never touches the source itself, only
  that feed's output.

### Condition operators

Each field offers only the operators that make sense for it. Text fields
(title, description, location, calendar name, attendee) support `contains`,
`does not contain`, `starts with`, `does not start with`, `is exactly` and
`matches regex`, each with an optional case-sensitive flag. Duration and start
time take comparisons; recurrence takes `is exactly true`/`false`.

The two negated operators are worth reading carefully before you point one at
a DELETE rule:

- **An event whose field is empty or missing matches.** An event with no
  description genuinely does not contain anything, so "description does not
  contain *boring*" matches it. That is what the words mean - but it means
  "title does not contain *[Work]*" also matches every untitled event. The
  editor says so when you pick the operator.
- **On attendees it means *no* attendee matches.** "Attendee does not contain
  bob@" is false for any event Bob is on, however many other attendees there
  are, and true for an event with no attendees at all.

Both are reasons to leave a new rule on DRY_RUN and check what it would have
matched before switching it to DELETE.

## Publishing a feed (including pointing Proton at one)

Any synced calendar - regardless of provider - can be published as its own
ICS feed:

- **Quick publish**: on the **Calendars** view, click **Publish as ICS
  Feed** next to any calendar. This creates (or reuses, if one already
  exists) an unfiltered feed mirroring that one calendar.
- **Filtered feed**: on the **Published feeds** view, click **New feed**,
  name it, and pick one or more source calendars (including an
  `ICS_SOURCE` connection's calendar). Then go to **Rules**, create a rule,
  and scope it to that feed to filter its output.

Either way, the feed's subscribe URL looks like
`https://your-domain/feed/<token>.ics`. Copy it from the Published feeds
view and paste it into Proton's (or any other client's) "subscribe to a
calendar by URL" field. This endpoint is deliberately unauthenticated -
the token in the URL *is* the credential, since these clients can't do an
interactive login - so keep the URL private and use **Rotate token** if it
ever leaks.

Feeds regenerate when a source calendar syncs or the feed's rules change,
not on a fixed timer - the first request after that may take a moment
while it rebuilds.

### Export settings

Rules decide *which* events a feed carries. **Export settings**, in the same
New feed / Edit dialog, decide how each of them looks to whoever subscribes:

| Setting | What it writes | Notes |
|---|---|---|
| Calendar app | - | Which client the feed is aimed at. **Every calendar app** is the default and the recommended one: it adds Microsoft's busy-status extension, which other clients are required to ignore. |
| Privacy | `CLASS:PRIVATE` / `PUBLIC` / `CONFIDENTIAL` | Outlook and Exchange map `PRIVATE` to their private sensitivity setting, and treat an event with no `CLASS` as public. |
| Show time as | `TRANSP` + `X-MICROSOFT-CDO-BUSYSTATUS` | Free, Busy, Tentative or Out of office. |
| Reminders | `VALARM` | No reminders (the default), keep the source's, or one fixed reminder N minutes before. |

The dialog shows the exact property lines your choices will add to every
event, and warns when a setting can't reach the app you picked.

**Out of office** is the case worth understanding. `TRANSP` has only two
values - blocks time, or doesn't - so plain iCalendar cannot distinguish "out
of office" from "busy". Outlook reads that from
`X-MICROSOFT-CDO-BUSYSTATUS:OOF`, which is a Microsoft extension. Picking
Google Calendar or Proton Calendar as the target suppresses that extension, so
Out of office degrades to plain busy; the grid and the dialog both say so
rather than letting you assume it arrived.

Two limits are worth stating plainly:

- **Private is a display flag, not encryption.** The ICS body still contains
  the summary, description and attendees in clear text, and the feed token is
  the only thing protecting them. Use a rule to exclude events that must not
  leave this server at all.
- **Removing reminders only stops the *feed* asking for one.** It cannot
  override reminders the receiving side imposes itself - Outlook's per-calendar
  defaults, an Exchange policy, or Google's notification settings for a
  subscribed calendar, which belong to the subscriber and ignore the feed.

Keeping the source's reminders only works for events that arrived as ICS
text - ICS URL sources and CalDAV, including iCloud. Google and Microsoft 365
events are stored as JSON snapshots with no `VALARM` to copy, so they export
without a reminder whichever way this is set.

Existing feeds are unaffected by the arrival of this feature: the defaults
reproduce exactly what CalendarSync emitted before it, so a subscriber with a
working calendar sees no change until you change something.

## Trash and restore

Every deletion - real or feed-side - shows up in the **Trash** view,
searchable by event title and filterable by status. **Restore** is
available whenever the entry is still `DELETED` and has a snapshot:

- For a real provider deletion, restore recreates the event on the
  provider (a new event, since providers generally don't let you reuse an
  old ID) and marks the record `RESTORED`.
- For a feed-side exclusion, restore forces the event back into that
  feed's output (even though the rule that excluded it may still be
  enabled) rather than recreating anything.

Trash history is kept indefinitely by default. An optional retention
window can be enabled via `calendarsync.retention.enabled` /
`calendarsync.retention.days` - a purge only ever clears the restorable
snapshot and marks the row `PURGED`, it never deletes the audit row, so
the historical fact "this was deleted on this date" survives.

## Two-factor authentication

A time-based one-time code (TOTP) in addition to your password. It works with
any authenticator app and with password managers that store codes - Bitwarden,
1Password, Aegis, Google Authenticator and the rest.

### Turning it on

**Account -> Two-factor authentication -> Set up.** Scan the QR code, or copy
the setup key if you are enrolling on the same device you are browsing on, then
enter the six-digit code your authenticator shows to confirm.

The code is what proves the enrolment worked. Nothing is saved until you enter
one, so a mis-scanned QR or a closed tab leaves your account exactly as it was.

Immediately afterwards you are shown **ten recovery codes**. Save them
somewhere separate from your phone. They are stored hashed, so this is the only
time they can be displayed - if you lose both your authenticator and these
codes, an administrator has to clear the second factor for you.

### Signing in

Password first, then a second page asking for the code. This two-page shape is
deliberate: it is what Bitwarden and 1Password are built around - they fill and
submit your username and password, then offer the stored code on the next page.

Each recovery code works once. Enter one in place of the six-digit code, under
"Use a recovery code instead".

A code that has been used cannot be used again, even within the thirty seconds
it stays valid for. If a code is rejected and you are sure it is current, the
usual cause is the clock on the device generating it having drifted - codes
from the previous and next thirty-second window are accepted, but no further.

### If you get locked out

An administrator can clear the second factor from **Admin -> Clear 2FA**, which
also ends that user's signed-in sessions. This is the only route back for
somebody who has lost their authenticator and their recovery codes.

If the locked-out account is the *only* administrator, there is no in-app route
back and you will need to edit the database:

```bash
# stop the app first
sqlite3 /var/lib/calendarsync/calendarsync.db \
  "UPDATE app_user SET totp_enabled=0, totp_secret=NULL, totp_required=0 WHERE username='admin';"
```

Note that under the prod profile the database is encrypted, so `sqlite3` cannot
open it directly - this works on a dev database. Keeping your recovery codes is
much the easier path.

### Requiring it

An administrator can mark an account with **Require 2FA**. That account is
routed to the enrolment page the next time it signs in and cannot use the rest
of the app until it has enrolled, and cannot switch the second factor off
afterwards.

This is a prompt rather than a lock: the user has already given the correct
password and is signed in, so any published feed URLs they already hold keep
working. It exists to get people enrolled, not to hold off somebody who already
has the password.

### What it does and does not protect

It protects the login form. A published feed URL is a bearer token in a path
and is deliberately not behind authentication at all, so subscribed calendar
apps keep working - two-factor authentication does not change that, and a feed
URL that has leaked still needs the feed deleting or regenerating.

## Admin: creating additional users

The **Admin** view (visible only to `ADMIN` users) lets you create and
disable accounts. Administrators do **not** get implicit access to other
users' calendars, rules, feeds, or trash - account management and data
access are deliberately separate concerns in this app.

## Deploying with Docker

```bash
cp .env.example .env
# edit .env: set CALCLEANER_DB_KEY, CALENDARSYNC_BASE_URL,
# CALENDARSYNC_TRUSTED_PROXIES, and OAuth credentials for whichever
# providers you want to use

docker compose up -d --build
```

The app serves plain HTTP on port **8080** and does not terminate TLS. It
expects a reverse proxy in front of it - on another host, a load balancer, or
an ingress controller - to do that. Proton (and other subscribe-by-URL clients)
need a stable HTTPS URL to poll `/feed/{token}.ics`, so a TLS terminator is
still required for that feature to work at all; it simply isn't part of this
repo's deployment any more. `nginx/nginx.conf` is kept as a worked example to
adapt on whichever machine does terminate TLS.

The container runs as a non-root user and expects a persistent volume at
`/data` for the SQLite database file (already wired up in
`docker-compose.yml`).

### Autostart on reboot

`docker-compose.yml` sets `restart: unless-stopped`, so Docker brings the
container back whenever the daemon starts - after a crash, after a
`docker restart`, and after a reboot. That is one of the two halves.

**The other half is that the Docker daemon must itself start at boot, and on a
socket-activated install it does not.** Where `docker.socket` is enabled but
`docker.service` is not - the default on Arch, and on any install that leans on
socket activation - `dockerd` starts lazily, the first time something connects
to `/run/docker.sock`. Nothing connects to it on an idle boot, so the daemon
never starts, so no restart policy ever runs. The app is simply down until
someone types a `docker` command, and neither the journal nor `docker logs`
says why.

Check which one you have, and fix it if needed:

```bash
systemctl is-enabled docker.service   # want: enabled
sudo systemctl enable --now docker.service
```

`enabled` here is what makes the restart policy mean anything. `disabled` with
an enabled `docker.socket` is the failure above.

Then verify it end to end rather than trusting the config - the whole point is
that this failure is silent:

```bash
sudo reboot
# once it's back, without running any other docker command first:
docker compose ps          # want: calendarsync, Up
curl -sI http://127.0.0.1:8080/login | head -1   # want: HTTP/1.1 200
```

Two things the restart policy deliberately will not do:

- **Restart a container you stopped yourself.** `unless-stopped` remembers a
  deliberate `docker compose stop` across the reboot; `always` would override
  it. Use `docker compose start` (or `up -d`) to hand it back.
- **Recreate a container that no longer exists.** `docker compose down` removes
  the container and its restart policy with it, so nothing survives to be
  restarted. Prefer `stop` when the machine is going to reboot.

#### Managing it from systemd instead

`deploy/calendarsync-compose.service` is a reference unit for hosts that would rather
own the stack from systemd - copy it to `/etc/systemd/system/`, point its
`WorkingDirectory` at the directory holding your `docker-compose.yml` and
`.env`, then `systemctl enable --now calendarsync.service`. It covers the two
cases the restart policy cannot: its `Requires=docker.service` pulls the daemon
up at boot even on a socket-activated install, and it re-runs `compose up -d`,
so a stack that was taken `down` still comes back and `.env` edits are picked
up. `systemctl status calendarsync` then answers "why is it not running".

It is an alternative, not an addition - once the unit is installed it is what
brings the stack back, because its `ExecStop` marks the containers as
user-stopped and `unless-stopped` declines to restart those. Leaving the
restart policy in place is still correct; it keeps covering daemon crashes and
container crashes between reboots.

### Exposing port 8080 safely

The app has no TLS and no network-level authentication of its own, so where
8080 is reachable from is a real decision, not a detail.

`docker-compose.yml` publishes it on `CALENDARSYNC_BIND_ADDRESS`, which defaults
to `127.0.0.1` - loopback only. That default is safe rather than convenient: a
proxy on another machine cannot reach loopback, so an unconfigured deployment
fails visibly instead of quietly listening on a public interface.

For a proxy on another machine, set the private address it connects to:

```bash
# .env
CALENDARSYNC_BIND_ADDRESS=10.0.0.7
```

Avoid `0.0.0.0`: it listens on **every** interface, including any public one,
which puts an unencrypted login form on the internet.

Then set `CALENDARSYNC_TRUSTED_PROXIES` to the proxy's address. The app takes
the client IP and scheme from `X-Forwarded-For` / `X-Forwarded-Proto`, but only
from source addresses in that list. This is load-bearing: the login brute-force
throttle keys on that client IP, so if an untrusted caller could set the header
they could rotate it per request and never trip the lockout. Conversely, if the
proxy is *not* in the list, every request appears to come from the proxy and one
attacker can lock out every user at once.

Two things the proxy must do, both shown in `nginx/nginx.conf`:

- **Forward `X-Forwarded-Proto`**, or the app builds `http://` redirects and the
  browser rejects the `Secure` session cookie.
- **Mask feed tokens in its access log.** `/feed/{token}.ics` carries a bearer
  credential in the URL path, so a default access log records every subscriber's
  token in cleartext on every poll. Anyone who can read those logs can subscribe
  to those calendars.

## Native install (Ubuntu)

Running the jar directly under systemd, with no Docker on the host. The
`deploy/` directory carries everything this needs: an installer, the unit it
installs, and the environment file it seeds.

The server needs **only a JRE 25** - not Maven, not Node, not a JDK. Building
happens wherever you already build.

### 1. Build the jar

On your development machine, or any host with JDK 25 and Node:

```bash
mvn -Pprod package        # produces target/calendarsync-0.1.0-SNAPSHOT.jar
```

`-Pprod` is not optional and not interchangeable with the Spring profile of the
same name - it is what packages the Willena SQLite driver that implements
encryption. A jar built without it is described under
[What the installer refuses](#what-the-installer-refuses) below.

Ubuntu's archive Node (22.x in 26.04) is below the version
`vaadin-maven-plugin` wants, and Vaadin's response is to download its own from
nodejs.org mid-build rather than to fail - a `Downloading
https://nodejs.org/dist/...` line in the output is that happening. To build
against the Node this repo already pins by digest, use the Dockerfile's build
stage and take the jar out of it:

```bash
docker build --target build -t calendarsync-build .
docker create --name cs-extract calendarsync-build
docker cp cs-extract:/build/target/app.jar ./calendarsync.jar
docker rm cs-extract
```

### 2. Install

Copy the jar and this repository's `deploy/` directory to the server, then:

```bash
sudo ./deploy/install-ubuntu.sh                     # finds target/*.jar
sudo ./deploy/install-ubuntu.sh /path/to/app.jar    # or name it
```

It creates a `calendarsync` system account, installs the jar to
`/opt/calendarsync` (root-owned, so a compromised app cannot rewrite the code
that runs as it next boot), the data directory `/var/lib/calendarsync` (0750),
and `/etc/calendarsync/calendarsync.env` (0600 root) with a freshly generated
`CALCLEANER_DB_KEY`. It enables the service at boot but does **not** start it,
because the shipped `CALENDARSYNC_BASE_URL` is still `calendar.example.com` and
that value would be baked into every feed link generated before you corrected
it.

The installer does not install packages or add apt repositories. If Java is
missing or too old it prints the command for your release and stops: Ubuntu
26.04 LTS has `openjdk-25-jre-headless` in the archive, earlier releases need
the Eclipse Temurin apt repository, and which one you get should not be a side
effect of running an install script.

### 3. Configure and start

```bash
sudo nano /etc/calendarsync/calendarsync.env
sudo systemctl start calendarsync
sudo journalctl -u calendarsync -f      # first-run admin password prints here
```

Three settings in that file decide whether the install is sound:

- **`CALCLEANER_DB_KEY`** - generated for you. Back it up somewhere other than
  this machine before it is the only copy; there is no recovery and no re-key
  migration.
- **`SERVER_ADDRESS`** - which interface the app binds, `127.0.0.1` by default.
  A native install has no port mapping, so this is the *only* thing standing
  between an unencrypted login form and every address the host has. Spring's
  own default is all interfaces; leaving it unset on a VPS publishes the app to
  the internet. Set it to the private address your proxy connects to when that
  proxy is on another machine.
- **`CALENDARSYNC_DB_PATH`** - already set to `/var/lib/calendarsync/`. The
  prod profile's built-in default is `/data/calendarsync.db`, which is a path
  inside the Docker image; the unit's `ProtectSystem=strict` would make it
  unwritable even on a host that had one.

Everything else is the same set of variables the Docker deployment uses - see
[Environment variables](#environment-variables) - including
`CALENDARSYNC_TRUSTED_PROXIES`, which matters here for exactly the reasons
described under [Exposing port 8080 safely](#exposing-port-8080-safely).

### What the installer refuses

**A jar built with the dev Maven profile.** That jar carries Xerial's SQLite
driver, which does not understand the `?key=` parameter the prod profile
appends to the JDBC URL - and does not reject it either. Observed on a real
run: the app starts normally, logs nothing unusual, and writes a file literally
named `calendarsync.db?key=<your database key>` which begins `SQLite format 3`
- an entirely unencrypted database, with the encryption key now sitting in a
filename that `ls` and every backup will pick up. The installer looks inside
the jar for the Willena driver and stops rather than let that happen.

**A host already running the Compose unit.** One machine runs one of the two.

### Getting rid of it

```bash
sudo systemctl disable --now calendarsync          # stop and un-enable
sudo rm /etc/systemd/system/calendarsync.service && sudo systemctl daemon-reload
sudo rm -rf /opt/calendarsync
# /var/lib/calendarsync (the database) and /etc/calendarsync (the key) are
# left deliberately - removing them is unrecoverable, so it stays a decision.
```

## Updating an install

```bash
sudo ./deploy/update.sh                 # auto-detects native or Docker
sudo ./deploy/update.sh --dry-run       # show what it would do
sudo ./deploy/update.sh --jar /tmp/calendarsync.jar
sudo ./deploy/update.sh --mode docker --dir /opt/calendarsync
```

Re-running `install-ubuntu.sh` with a newer jar also replaces it and restarts,
but it does so under a running service, keeps no copy of what it replaced, and
does not check that the app came back. Use `update.sh` instead once you have
data worth keeping.

What it does, in order:

1. Works out whether this host runs the native or the Docker install, and
   refuses to guess if both or neither are present (`--mode` overrides).
2. Checks the new jar was built with `-Pprod`. A `-Pdev` jar carries the wrong
   SQLite driver: it cannot open an encrypted database, and what it does
   instead is write a **plaintext** one to a filename containing your database
   key. This is the single most important check in the script.
3. Refuses a jar byte-identical to the installed one, so you do not take an
   outage for nothing (`--force` overrides).
4. Stops the service and **waits until it has really stopped** - a SQLite file
   copied out from under a running writer can be torn.
5. Backs up the database and the current jar to
   `/var/lib/calendarsync/backups/<timestamp>/` (the Docker path tars the
   `/data` volume instead). The five most recent backups are kept.
6. Installs the new jar and starts the service.
7. Polls `http://<address>:8080/login` for up to two minutes. A 200 there means
   Flyway migrated, the Spring context started and Vaadin is serving -
   `systemctl is-active` alone does not, because it reports success as soon as
   the JVM starts, which is before any of that.
8. **If it does not come up, restores both the jar and the database** and
   starts the previous version again, printing the last 50 log lines first.

That last point is why the script exists. Database migrations only run forwards
and are validated at startup, so an older jar against a database a newer one
has already migrated will not start at all. Putting the jar back without the
database produces a service that looks rolled back and then fails on its next
restart.

**The backups contain your calendar database.** Under the prod profile it is
encrypted with `CALCLEANER_DB_KEY`, so it is exactly as sensitive as the live
file and no more - but it is still a copy of everything, sitting in a directory
that grows over time.

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `CALCLEANER_DB_KEY` | prod profile only | Encryption key for the whole database file (Willena driver) and for `calendar_connection.encrypted_credentials` (derived separately, see [Encryption](#encryption)). Generate with `openssl rand -base64 32`. Losing it means losing access to everything encrypted with it. |
| `CALENDARSYNC_BASE_URL` | prod profile only | The externally-visible HTTPS URL this instance is reachable at. Embedded in every generated feed URL - never leave it as `localhost` in production. |
| `CALENDARSYNC_DB_PATH` | no | Path to the SQLite file. Defaults to `/data/calendarsync.db` in the Docker image. |
| `CALENDARSYNC_TRUSTED_PROXIES` | no | Comma-separated CIDRs allowed to set `X-Forwarded-For` / `X-Forwarded-Proto`. Defaults to all private + loopback ranges. Narrow it to your reverse proxy - the login throttle trusts the client IP this produces. See [Exposing port 8080 safely](#exposing-port-8080-safely). |
| `CALENDARSYNC_SECURE_COOKIE` | no | Whether the session cookie is marked `Secure`. Defaults to `true`. Set `false` only to test directly against `http://host:8080` with no TLS proxy in front. |
| `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` | no | Required only to offer **Google Calendar** in the Add connection dropdown. |
| `MS_OAUTH_CLIENT_ID` / `MS_OAUTH_CLIENT_SECRET` / `MS_OAUTH_TENANT_ID` | no | Required only to offer **Microsoft 365** in the Add connection dropdown. `MS_OAUTH_TENANT_ID` defaults to `common`. |
| `CALENDARSYNC_LOG_DESTINATION` | no | `console` (default), `file`, or `elastic` - see [Logging](#logging). |
| `CALENDARSYNC_LOG_ELASTIC_HOST` / `CALENDARSYNC_LOG_ELASTIC_PORT` / `CALENDARSYNC_LOG_ELASTIC_TLS` | no | Only used when the destination is `elastic`. Default to `localhost`, `5044`, `false`. |
| `CALENDARSYNC_LOG_PATH` | no | Log file path when the destination is `file`. Defaults to `/data/logs/calendarsync.log` in the Docker image. |

## Logging

The app logs to exactly **one** destination, chosen by
`calendarsync.logging.destination` (or `CALENDARSYNC_LOG_DESTINATION`):

| Destination | What it does |
|---|---|
| `console` *(default)* | Boot's usual human-readable stdout. In Docker this is what `docker logs` shows. |
| `file` | Rolling plain-text file at `logging.file.name`, defaulting to `/data/logs/calendarsync.log` in the container (on the same persistent volume as the database) and `./data/logs/calendarsync-dev.log` in dev. Rolls at 10MB, keeps 7 days, capped at 1GB total. |
| `elastic` | ECS-formatted JSON, one document per line, over TCP to Logstash or an Elastic Agent. |

These are alternatives, not layers - picking `file` or `elastic` means stdout
goes quiet. Levels are independent of all this and stay on the usual
`logging.level.*` properties.

> **On first run**, the generated admin password (see [First login](#first-login))
> is printed to whichever destination is configured. If you set up `elastic` or
> `file` before the very first boot, that one-time password goes there and *not*
> to your terminal - so read it out of Kibana or the log file, or leave the
> destination on `console` until you've logged in once.

### Shipping to Elastic

The app talks to a **collector**, not to Elasticsearch directly. Point it at a
Logstash (or Elastic Agent) TCP input using the `json_lines` codec, and let
that do the indexing:

```yaml
# docker-compose.yml
environment:
  CALENDARSYNC_LOG_DESTINATION: elastic
  CALENDARSYNC_LOG_ELASTIC_HOST: logstash.internal
  CALENDARSYNC_LOG_ELASTIC_PORT: "5044"
  CALENDARSYNC_LOG_ELASTIC_TLS: "true"
```

```ruby
# the matching Logstash input
input {
  tcp {
    port  => 5044
    codec => json_lines
  }
}
```

Because the events are already ECS, they need no `grok` or `mutate` on the way
through - `log.level`, `service.name`, `@timestamp` and the rest land as the
fields Kibana already expects. `service.name` defaults to `spring.application.name`
(`calendarsync`) and can be overridden, along with the other ECS service fields,
via Spring Boot's own `logging.structured.ecs.service.*` properties.

Two things worth knowing before turning this on:

- **Delivery is best-effort.** Events are queued and written by a background
  thread, so an unreachable collector never blocks a request - but it does
  mean events are dropped rather than buffered indefinitely once the queue
  fills.
- **No application logs go to stdout in this mode**, but it isn't silent when
  something is wrong: connection failures are reported through logback's status
  manager, which Spring Boot prints to stderr at WARN and above. A collector
  that is down or misconfigured still shows up in `docker logs` as
  `Log destination …: connection failed`, followed by retry notices.

TLS uses the JVM's trust store, so a collector with a private CA needs that CA
imported into the image's `cacerts` (or a `-Djavax.net.ssl.trustStore` pointing
elsewhere).

## Encryption

Two separate protections, deliberately not conflated:

- **Whole-database encryption** (`calendarsync.db.encrypted=true`, the
  `prod` Spring profile, active together with the `-Pprod` *Maven* build
  profile that packages the Willena SQLite3MultipleCiphers driver instead
  of the plain Xerial one): protects the `.db` file at rest, e.g. against a
  stolen disk or an unencrypted backup. One instance-wide key from
  `CALCLEANER_DB_KEY`.
- **Per-column credential encryption** (`CredentialCipher`, AES-256-GCM):
  encrypts `calendar_connection.encrypted_credentials` specifically,
  keyed from the *same* `CALCLEANER_DB_KEY` but with a distinct derived
  key, so a decrypted-database scenario (e.g. a misconfigured dev copy of
  a backup) still doesn't expose OAuth refresh tokens/CalDAV passwords in
  the clear.

Neither of these is what actually keeps User A from seeing User B's data -
that's consistent `user_id` scoping enforced at the repository layer.
Encryption protects the file; scoping protects users from each other.

## Running tests

```bash
mvn test
```

Runs offline against an embedded, Flyway-migrated SQLite file - no
external services or Testcontainers needed. Notably includes:

- `RuleEngineImplTest` - every valid field/operator combination, rejected
  invalid ones, and ANY/ALL match logic.
- `TrashServiceIntegrationTest` - the full snapshot-then-delete/restore
  round trip, using a stub provider that itself refuses to delete unless a
  committed snapshot already exists (directly encoding the safety
  requirement, not just observing call order).
- `UserScopingIsolationTest` - confirms a query for one user's data can
  never return another user's rows.
- `LoggingDestinationTest` - configures logback through each of the three
  destinations and asserts what the root logger actually ended up with,
  including that the `elastic` one really emits ECS JSON. Worth testing
  because a broken destination doesn't throw, it just produces a logger
  with no appenders.
- Offline unit tests for every provider's event-mapping/snapshot-cleaning
  logic (Google, Microsoft Graph, CalDAV, ICS), `CryptoUtilTest`, and
  `RetryHelperTest`.

## Architecture notes

- `provider/` - one `CalendarProvider` implementation per provider
  (Google, Microsoft Graph, CalDAV covering both iCloud and generic, ICS).
- `rules/` - the shared `RuleEngine`, used by every provider's sync job and
  by feed regeneration - one evaluation path, not duplicated.
- `trash/` - `TrashService`, the snapshot-before-delete safety mechanism
  and restore handling for both real deletions and feed exclusions.
- `ics/` - `IcsExportService` (feed regeneration) and the unauthenticated
  `GET /feed/{token}.ics` controller.
- `scheduling/` - one Quartz job per connection (`ConnectionSyncJob`), plus
  the optional daily retention purge job.
- `ui/` - Vaadin views, one package per feature area.

See [NOTES.md](NOTES.md) for the full list of places this implementation
deviates from the original spec (library version substitutions, API
differences discovered during development, and deliberate simplifications)
along with the reasoning for each.
