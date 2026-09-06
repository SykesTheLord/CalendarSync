# Deviations from the original spec

Every stage's deviations are listed below in build order. The short version:
nothing changed in scope or intent - every deviation here is either (a) a
library/API detail that shifted between when the spec was written and when
this was built (Vaadin's security API, Spring Boot 4's Flyway module split,
exact dependency patch versions), (b) a design choice needed to make two
parts of the spec's own architecture consistent with each other (the
`CalendarProvider.type()` → `supports()` change, so one CalDAV
implementation could legitimately answer for both ICLOUD and CALDAV), or
(c) a scope simplification explicitly flagged as such at the point it was
made (MS Graph full-poll instead of delta-query). Nothing destructive was
ever built without the snapshot-first safety mechanism the spec requires,
and every deviation below was verified working, not just written and
assumed correct - see the "verified" notes throughout.

## Stage 0 - core skeleton + auth

- **`VaadinWebSecurity` doesn't exist in Vaadin 25.1.4.** The spec named it
  explicitly, but it's been replaced by `VaadinSecurityConfigurer`, applied
  via `http.with(VaadinSecurityConfigurer.vaadin(), configurer ->
  configurer.loginView(LoginView.class))` inside a plain
  `SecurityFilterChain @Bean`. Functionally equivalent; see
  `config/SecurityConfig.java`.
- **`@PermitAll`/`@RolesAllowed` are the standard `jakarta.annotation.security`
  annotations**, not Vaadin-specific ones (`com.vaadin.flow.server.auth`
  only has `@AnonymousAllowed`). Vaadin's `AccessAnnotationChecker` reads
  the standard JSR-250 annotations directly.
- **Spring Boot 4 requires `spring-boot-starter-flyway` in addition to
  `flyway-core`.** Boot 4 split `FlywayAutoConfiguration` out of the
  monolithic autoconfigure jar into its own module/starter; `flyway-core`
  alone silently does nothing (no error, no log line - migrations just
  never run).
- **`spring-boot-starter-parent` pinned to 4.0.4, not 4.0.1.** Vaadin 25.1.4
  requires Jackson 3.1+; Spring Boot 4.0.1 ships Jackson 3.0.3, which fails
  at servlet init with an explicit "Jackson version ... not compatible"
  error.
- **`com.vaadin:vaadin-dev` must be added explicitly** (declared inside the
  Maven `dev` profile) - without it, booting outside production mode fails
  with `'vaadin-dev-server' not found`.
- **Test infrastructure**: a single JVM-lifetime temp directory
  (`Files.createTempDirectory` + `deleteOnExit`) instead of a JUnit
  `@TempDir` static field for the SQLite test database. A per-test-class
  `@TempDir` gets deleted between test classes, but Spring's context cache
  reuses the same `ApplicationContext` (and its already-open `DataSource`)
  across test classes whose `@DynamicPropertySource` method is inherited
  from a shared base class - the cache key doesn't account for the dynamic
  property's runtime value changing, so the second test class silently got
  a fresh, unmigrated file. One stable path for the whole test JVM avoids
  the mismatch.

## Stage 1 - writable providers, delete and restore together

- **`CalendarProvider.type()` became `supports(ProviderType)`.** The
  spec's own design intent (iCloud and generic CalDAV share one
  implementation) is incompatible with a single-type-per-provider
  contract: `TrashService` needs to look up a provider by the connection's
  `ProviderType`, but `CalDavProvider` legitimately answers for two types.
  Changed before any other provider existed, so no wider impact.
- **CalDAV delete resolves the resource by re-querying and matching on
  UID**, rather than assuming the resource URL follows any particular
  naming convention - the ICS `UID:` property is not guaranteed to be the
  server's resource filename. Costs one extra REPORT round trip per
  delete, but is correct regardless of server naming.
- **MS Graph sync is full-poll, not delta-query, in this build stage.**
  The spec's polling-not-webhooks scope cut is followed, but within that,
  Google's incremental sync uses a single `syncToken` field while MS
  Graph's equivalent (`calendarView/delta`, `@odata.deltaLink`) is a
  materially larger surface. `MicrosoftGraphProvider.listEvents` calls the
  plain `events().get()` every cycle instead - correct, just less
  efficient. A reasonable later improvement, not required for the app to
  function.
- **MS Graph credential storage**: unlike Google's bare refresh-token
  string, MSAL4J only exposes a serialized token cache (a JSON blob it
  manages internally) - `calendar_connection.encrypted_credentials` for
  MS_GRAPH connections holds that serialized cache, not a plain refresh
  token. `MicrosoftGraphProvider` persists the cache back to the
  connection row whenever MSAL rotates it during silent token acquisition.
- **MS Graph event snapshots use a plain Jackson `ObjectMapper` against a
  small hand-written mirror class**, not the kiota-generated `Event` model
  directly - kiota models serialize via their own
  `SerializationWriter`/`ParseNode` machinery, not reflection.
- **Bug caught and fixed: `MainLayout` had no access annotation.** Vaadin's
  `AnnotatedViewAccessChecker` evaluates every class in a view's
  parent-layout chain independently; an unannotated layout is treated as
  MORE restrictive than a `@PermitAll` child view, so it silently denied
  access to *every* view using that layout for authenticated users, not
  just anonymous ones. This shipped in Stage 0 and wasn't caught until
  Stage 1, because Stage 0's own verification only checked that anonymous
  requests redirect to login - which looks identical whether the real
  cause is "correctly requires auth" or "misconfigured and denies
  everyone." Caught by checking the boot log for
  `AnnotatedViewAccessChecker` warnings; fixed with `@PermitAll` on
  `MainLayout`; **verified** by actually logging in via curl (CSRF token +
  bootstrap admin password both pulled from the boot log) and confirming
  every view returns 200 authenticated, not just that anonymous ones
  redirect. This became the standard verification method for every UI
  change from this point on.

## Stage 2 - ICS import/export & publishing

- **ICS_SOURCE connections reuse `calendar_connection.caldav_base_url`**
  to hold the feed URL. The DDL has no dedicated `ics_url` column, and the
  spec's own UI description ("a URL field for ICS sources") implies the
  same base-URL concept generic CalDAV already uses, so the existing
  column does double duty rather than adding a new one.
- **IcsSourceProvider keeps its own in-memory "last known full event
  list" cache**, separate from `sync_state`'s conditional-GET bookkeeping.
  A 304 response correctly means "nothing changed" for the sync job's
  purposes, but `IcsExportService` still needs the *full* current event
  set on every feed regeneration, not a diff - so a 304 returns the cached
  list rather than an empty one.
- **Feed regeneration is cached in memory, not the database** -
  `published_feed` has `last_generated_at` (a DB column, for UI display)
  but no `body`/`content` column, so there's nowhere to persist bytes.
  `IcsExportService` keeps a `Map<feedId, bytes>` cache, invalidated by
  explicit triggers (a source calendar syncing, or the feed's rules
  changing) with `cache_ttl_seconds` as a secondary staleness bound in
  case a trigger is ever missed - never a background timer of its own, per
  spec.
- **Feed-side audit writing lives in `TrashService.recordFeedExclusion`,
  not `IcsExportService`, to avoid a circular dependency**:
  `IcsExportService` needs to write `deletion_audit` rows before
  finalizing an exclusion (same snapshot-first discipline as a real
  delete), but `TrashService.restore()` also needs to trigger feed
  regeneration on a feed-side restore. Having both directions go through
  each other would be circular; the caller of `restore()`
  (`DeletionAuditService`) is responsible for invalidating the feed's
  cache afterward instead.

## Stage 4 - polish

- **Vaadin's production-mode frontend build lives inside the same `prod`
  Maven profile** that switches the SQLite driver, rather than a second
  `production` profile matching Vaadin's own naming convention - the two
  concerns must always be activated together for a working deployment, so
  keeping them as one profile is the only way to build instead of
  something that could be run out of sync. **Verified** with a real `mvn
  -Pprod package`: no custom frontend code means Vaadin used its prebuilt
  `vaadin-prod-bundle` dependency rather than invoking npm/Vite at all, so
  the build stays fast (~8s) and doesn't actually need Node.js for *this*
  app - the Dockerfile still installs it since a real deployment will
  likely add custom frontend eventually.
- **Encrypted-database path (`DataSourceConfig`'s Willena `?key=...` JDBC
  URL parameter) verified working, not just written**: built the jar with
  `-Pprod`, ran it with `SPRING_PROFILES_ACTIVE=prod` and a real
  `CALCLEANER_DB_KEY`, confirmed the bootstrap-admin flow worked
  end-to-end (Flyway migration, JPA writes, authenticated login all
  succeeded against the encrypted file), and confirmed via `file`/`xxd`
  that the resulting `.db` file has no SQLite magic header - genuinely
  encrypted at rest.
- **Dockerfile verified with a real `docker build` + `docker run`**: this
  caught a real non-root-user permission bug - a `VOLUME` declared with no
  prior directory gets created root-owned by Docker at container start,
  which the non-root `calendarsync` user then can't write into (SQLite
  failed with `SQLITE_CANTOPEN` on first boot). Fixed by creating and
  chowning `/data` in the image *before* the `VOLUME` line. Confirmed the
  running container serves HTTP, produces bootstrap-admin credentials, and
  leaves a writable, correctly-owned database file.
- **Retry/backoff is deliberately narrow**: only `listEvents` (sync
  polling reads) is retried, never delete/restore calls, since a "failed"
  write might have actually succeeded server-side before a timeout. Only
  retried when the failure's cause chain contains an `IOException` - a
  permanent failure (bad credentials, a 404, an unconfigured OAuth app)
  fails fast instead of wasting three rounds of backoff that can't help.
- **Bug caught and fixed while adding retry**: `MicrosoftGraphProvider`
  never caught exceptions from the Kiota-generated Graph SDK, which throws
  unchecked `ApiException` rather than a checked `IOException`. An
  uncaught one would have propagated straight past
  `ConnectionSyncJob`'s `catch (ProviderException)`, skipping its
  `sync_state.last_error` bookkeeping entirely.
- **Configurable log destination (console / file / Elastic)**: the app logs to
  exactly one of the three, chosen by `calendarsync.logging.destination`. Three
  points are worth recording:
  - **The ECS JSON comes from Spring Boot, not from a logging library.** Boot
    4 ships `StructuredLogEncoder` and an ECS formatter (the same thing
    `logging.structured.format.file=ecs` uses), so the only added dependency is
    `logstash-logback-encoder`, used purely as a transport - none of its own
    encoders are involved. Version 9.0 specifically, since 8.x is built against
    Jackson 2 and would have put a second Jackson alongside the Jackson 3 that
    Boot 4 already provides.
  - **The app ships to a collector, never to Elasticsearch directly.** The
    community appenders that POST to the `_bulk` endpoint have been unmaintained
    since ~2019 and predate ES 8 security; a Logstash/Elastic Agent TCP input
    with the `json_lines` codec is the supported path, and it is also what makes
    delivery asynchronous and non-blocking.
  - **The destination switch substitutes a property into an `<include>` path**
    rather than using logback's `<if>`, which needs Janino on the classpath just
    to compare two strings. Two things fell out of that during implementation
    and are now defended against: an unrecognised destination leaves the root
    logger with *no* appenders and only a status-manager line to say so, so the
    property carries a `@Pattern` matching the file names exactly (an enum would
    not do - relaxed binding accepts `ELASTIC`, which then resolves to a file
    that does not exist); and logback silently **ignores** an `<include>` nested
    inside an `<appender>` ("Ignoring unknown property [include]"), which was the
    first attempt at making `<ssl>` conditional and would have failed open into
    plaintext. `ElasticTcpAppender` - a subclass adding a `tls` boolean - exists
    for that reason alone.
  - **Shutdown grace period reduced from the library's 1 minute to 5 seconds.**
    An unreachable collector spends that period in full on every stop, which is
    longer than the 10s Docker allows before `SIGKILL`; the default made the app
    look hung on shutdown whenever Logstash was down. Caught because a test took
    60s rather than by reasoning about it.
  - **Verified against a real run, not just unit-tested**: booted the packaged
    jar with `destination=elastic` pointed at a TCP listener and captured 98
    startup events, all valid single-line JSON with `@timestamp`, `log.level`,
    `service.name`/`service.version` and `ecs.version` populated; then booted it
    again with `destination=file` and confirmed the rolling file (and its parent
    directory) were created with Boot's usual plain-text pattern. In both runs
    stdout carried only the Spring banner, which is what "alternatives, not
    layers" is supposed to mean.
- **Credential encryption key derivation**: `CredentialCipher` derives its
  AES-256-GCM key from the *same* `CALCLEANER_DB_KEY` env var as the
  Willena whole-database encryption, via SHA-256 with a distinct
  `"credentials"` context string - a different key from the same secret,
  not the same protection reused. The spec is explicit these are two
  separate protections and warns against conflating them; this keeps that
  distinction while only requiring the operator to manage one secret.

## Stage 5 - OWASP Top 10 review

A pass over the whole application against the OWASP Top 10 (2021). Access
control, injection and cryptographic storage came through clean - the
`findByIdAndUserId` discipline, JPQL-only queries and AES-256-GCM credential
column were already right. What follows is what the review actually changed,
and what it deliberately left alone.

**Fixed**

- **CalDAV credentials followed the server's redirects (A07/A10)**:
  `CalDavClient` attaches the user's CalDAV password to every request, and
  `CalDavDiscoveryService` followed `Location` headers - and resolved
  `href`s out of multistatus bodies - to wherever the response pointed. A
  hostile or compromised CalDAV server could therefore redirect the
  credential to a host of its choosing. Now every server-supplied URI goes
  through `CalDavUris`, which keeps the request inside the site the user
  configured and refuses an https-to-http downgrade. The check is
  *same-site*, not same-origin, precisely because iCloud discovery is
  designed to hop from `caldav.icloud.com` to a numbered
  `pNN-caldav.icloud.com` partition - a same-origin rule would break the
  provider this app was written for.
- **No brute-force protection on the login form (A07)**: Spring Security
  provides none by default. `LoginAttemptService` now blocks an address for
  15 minutes after 10 failures. Keyed on client IP rather than username on
  purpose - a username-keyed lockout turns a guessing attack into a
  denial-of-service against a real account.
- **Authentication was completely unlogged (A09)**: the application logged
  provider errors and rule decisions in detail and said nothing about
  logins, so a successful break-in left no trace. `AuthenticationEventLogger`
  records both outcomes with the client address.
- **Disabling an account didn't disable anything in flight (A01)**: Spring
  Security checks `isEnabled()` when a session is created and never again,
  so a disabled user kept working in the session they already had - the
  opposite of what disabling is for. A `SessionRegistry` plus
  `UserSessionTerminator` now expires their live sessions, on disable and on
  an admin password reset (not on `changeOwnPassword`, where the user *is*
  the session holder - and `AccountView` says so).
- **Published feeds outlived their owner's account (A01)**: `/feed/{token}.ics`
  is the one path that never touches Spring Security, so it kept serving a
  disabled user's calendar data to anyone holding the token. It now checks
  the owning account, and returns 404 rather than 403 so an unauthenticated
  caller learns nothing about whether the token was real.
- **nginx logged every feed token in cleartext (A09/A02)**: the token in
  `/feed/{token}.ics` *is* the credential, and the default access log wrote
  it on every subscriber poll, forever. `nginx.conf` now masks the path.
  Declared per-server, not in `http{}` - the stock config already has an
  `access_log` there and a second one appends a line rather than replacing
  it, so the token would still have been written. Verified by running the
  config in a real nginx container and grepping the log for the token.
- **Missing response headers (A05)**: added a CSP (`frame-ancestors`,
  `object-src`, `base-uri`) and a `same-origin` referrer policy, so a feed
  URL is never handed to another origin in a `Referer`. Spring Security's
  defaults already covered nosniff, X-Frame-Options and HSTS-over-TLS.

**Deliberately not changed**

- **No `script-src` in the CSP**: Vaadin's client bootstrap needs inline
  script, so a `script-src` worth having needs Vaadin's nonce integration
  and a pass over every view to verify. Shipping `'unsafe-inline'` would
  have been a policy that looks like protection and isn't.
- **Server-side request forgery to private addresses (A10)**: connection
  URLs are still allowed to point at loopback and LAN addresses. A
  self-hosted CalDAV server on the LAN is a normal setup for this
  application, so egress filtering is a deployment decision. The scheme
  allow-list in `CalendarConnectionService.requireFetchableUrl` stays as the
  backstop against `file:`/`gopher:` and friends.
- **SHA-256 as the key derivation for `CALCLEANER_DB_KEY` (A02)**: fine for
  the high-entropy value `.env.example` tells operators to generate
  (`openssl rand -base64 32`), weak if someone types a passphrase instead.
  Changing it to a real KDF now would make every existing database
  unreadable, so it stays until there's a migration path for it.
- **The bootstrap admin password is printed to the log** on first run. It's
  the only way to hand over a first credential without shipping a default
  one, and it's one line in one log at one moment - but it does mean the log
  from first boot is sensitive until that password is changed. Since the
  Stage 4 log-destination switch, "the log" can also mean a file on the data
  volume or an Elasticsearch index rather than the console - confirmed by
  running the app against a real TCP collector, where the password arrived as
  an ordinary ECS document. Anywhere the destination points therefore inherits
  that sensitivity, and it lands in a store with its own retention rather than
  scrolling out of a terminal.

**Still open**

- No automated dependency vulnerability scanning in the build (A06).
- No CI: nothing runs the test suite or the linters on a push, so both are
  advisory until someone runs them locally.

**Closed since**

- The Dockerfile no longer installs Node via `curl … | bash` from NodeSource
  (A08) - see "Build toolchain" below.

## Deployment model change - external TLS termination

The bundled nginx is gone. The app now serves plain HTTP on a published 8080 and
expects TLS to be terminated by a reverse proxy on a separate machine.
`nginx/nginx.conf` is retained as an example config to apply there, because two
things in it are easy to lose in the move: the feed-token access-log masking,
and the header forwarding the app depends on.

**This change silently defeated the login brute-force throttle, and fixing that
was the substantive part of the work.** `LoginAttemptService` rate-limits
password guessing keyed on client IP, which it reads from `getRemoteAddr()`.
With `server.forward-headers-strategy: framework`, Spring's
`ForwardedHeaderFilter` populates that from `X-Forwarded-For` supplied by *any*
caller - which was safe only for as long as 8080 was `expose`d to a private
compose network where nginx was the sole possible client. Publishing 8080 makes
the header attacker-controlled: rotate it per request and `MAX_FAILURES` is
never reached, so the lockout becomes decoration.

The fix is `forward-headers-strategy: native`, which routes the same job through
Tomcat's `RemoteIpValve` and honours the headers only from source addresses in
`server.tomcat.remoteip.internal-proxies` (exposed as
`CALENDARSYNC_TRUSTED_PROXIES`, defaulting to the private and loopback ranges).
Confirmed by observing the scheme of the `302` to `/login`, since it reflects
whether `X-Forwarded-Proto` was believed:

| Strategy | Request source | `X-Forwarded-Proto: https` | Resulting `Location` |
|---|---|---|---|
| `native` | in trusted list | sent | `https://localhost/login` - honoured |
| `native` | **not** in trusted list | sent | `http://localhost:8080/login` - **rejected** |
| `framework` | **not** in trusted list | sent | `https://localhost/login` - **accepted anyway** |

The third row is the old configuration ignoring the trusted-proxy list
altogether, which is what made the throttle bypassable.

Note the failure mode in the other direction: if the proxy's address is *not*
in the trusted list, every request appears to originate from the proxy, so one
attacker's failures block every user at once. The list has to be right, not
merely present.

Two smaller consequences:

- `server.servlet.session.cookie.secure` is now
  `${CALENDARSYNC_SECURE_COOKIE:true}` rather than a hard `true`. The default is
  unchanged and correct behind TLS; the override exists because pointing a
  browser directly at `http://host:8080` with a hard-coded `Secure` cookie
  presents as "login succeeds, then every page bounces back to `/login`", which
  is an expensive thing to debug from first principles.
- `docker-compose.yml` publishes `127.0.0.1:8080:8080`, not `8080:8080`. A bare
  mapping listens on every interface including public ones, which would put an
  unencrypted login form on the internet. Deployments with the proxy on another
  host need to bind the interface that host reaches, not widen it to all.

## Build toolchain

Added after the Stage 5 review, while wiring up formatting and linting.

**Spotless and Checkstyle are deliberately non-invasive.** Spotless is *not* a
whole-file reformatter: google-java-format or palantir would have rewritten all
115 source files and destroyed the hand-wrapped explanatory comment blocks that
carry this project's design rationale. It runs only unused-import removal,
import ordering, and whitespace normalisation, so `spotless:check` passes on an
untouched tree. Checkstyle's ruleset was tuned against reality rather than
imposed: the first pass produced 40 warnings, of which ~38 were the default
rules disagreeing with deliberate conventions here - the SLF4J `log` field name
(`ConstantName`), the `if (this == o) return true;` guard in entity `equals()`
methods (`NeedBraces`), and a hand-wrap that tops out at 132 columns rather than
120 (`LineLength`, now set to 140 as a runaway-line backstop, not a style
target). Those three were configured out; the 2 genuine findings that remained
were unused imports, now removed. Checkstyle is bound to `validate` but runs
with `failOnViolation=false` - it is a signal channel, not a gate.

**`.dockerignore` excluded `src/main/frontend/`, which broke the production
image build.** The Dockerfile installs Node specifically so Vaadin can compile
the custom theme, but the theme directory was being stripped out of the build
context, so `vaadin:build-frontend` failed with "Discovered @Theme annotation
with theme name 'calendarsync', but could not find the theme directory". This
also explains the Stage 4 observation that the Docker build "fell back to
`vaadin-prod-bundle` and took ~8s" - there was no theme in the context to build
from. Now only the generated parts (`generated/`, `index.html`) and the dev-mode
bundle are excluded, and the build reports "A production mode bundle build is
needed" and compiles the real thing.

**Node is pinned to 24, not 22, and that version floor matters.**
`vaadin-maven-plugin` has a minimum Node version and silently downloads its own
distribution from nodejs.org into `~/.vaadin` when the one on `PATH` is older.

**The Node range has a ceiling as well as a floor, which the note above did not
say.** Observed while adding `deploy/update.sh`, on a dev host carrying Node
26.2.0: `mvn -Pprod package` logged

    The globally installed Node.js version 26.x is newer than the maximum
    supported version 24.x and may not be compatible. Using Node.js from
    /home/jacob/.vaadin.

and built against its own cached `node-v24.15.0` instead. So the substitution
this project already guards against is triggered by a *too new* Node exactly as
readily as an old one, and on a machine with no cache the ceiling case
downloads over the network mid-build just as the floor case does. The
`Downloading https://nodejs.org/dist/...` line named above is therefore only
half the symptom; `Using Node.js from ...` is the other half, and it is the one
that appears once the download has already happened. `update.sh` greps the
build output for both.


Pinning the system Node to 22 therefore achieved nothing: Vaadin ignored it and
fetched v24.15.0 over the network mid-build, which is the same unpinned-download
problem the pin was meant to remove, just relocated. The image now copies Node
from the official `node:24.19.0-bookworm-slim` image, pinned by digest. When
bumping Vaadin, check whether its Node floor moved - if it climbs above the
pinned version this silently regresses to a network download rather than
failing, so the symptom to watch for in build logs is a
`Downloading https://nodejs.org/dist/...` line.

## Per-feed export settings (CLASS / TRANSP / busy status / VALARM)

A published feed can now decide how each event it carries is *written*, not
just which events survive the rules: privacy (`CLASS`), availability (`TRANSP`
plus `X-MICROSOFT-CDO-BUSYSTATUS`) and reminders (`VALARM`), with the target
calendar app - Google Calendar, Outlook/Microsoft 365, Proton Calendar, or all
of them - as a fourth setting that decides which of those a given feed is
allowed to emit. Stored as five columns on `published_feed` (V3), applied in
`IcsCalendarMapper.buildFeed` via the `ExportProfile` record, edited in
`ExportSettingsForm`.

**One setting writes two properties, on purpose.** `TRANSP` has exactly two
values, so it cannot tell "out of office" apart from "busy" - both are OPAQUE.
Outlook reads the finer state from `X-MICROSOFT-CDO-BUSYSTATUS`, a Microsoft
extension (MS-OXCICAL), so OUT_OF_OFFICE emits both: the extension for Outlook,
and `TRANSP:OPAQUE` as the fallback every other client - and Outlook itself, if
the extension fails to import - reads as "this time is taken". The pairing is
in `EventBusyStatus` rather than in the mapper so there is one place where the
two spellings of a state are kept consistent.

**`ExportTarget` exists to make a degradation visible rather than to change
many bytes.** GOOGLE and PROTON emit identical output today - standard
properties only - because the only vendor extension involved is Microsoft's,
and RFC 5545 requires clients to ignore x-properties they don't recognise. What
the setting buys is that picking Google or Proton *and* Out of office is
reported, in the dialog and in the feeds grid, as "exported as plain busy"
instead of silently arriving as something the user didn't choose. UNIVERSAL is
the default because it is the only value that can express OOF at all, and the
extension it adds is inert everywhere else.

**Every default reproduces the previous behaviour exactly.** Before this,
`buildFeed` built each VEVENT from normalized fields and never emitted `CLASS`,
`TRANSP`, the Microsoft extension or `VALARM`. UNCHANGED/UNCHANGED/STRIP is
therefore not a neutral-looking placeholder - it is the old output, byte for
byte, which is what an already-subscribed calendar client deserves from an
upgrade. **Verified** on the real dev database: it was at V2 with an existing
feed row, V3 migrated it in place, and a feed left alone still served the
identical document.

**`replace()` rather than `add()`, on a VEVENT that was built empty.** The
three properties cannot already be present, so this is defensive by
construction - but MS-OXCICAL allows exactly one
`X-MICROSOFT-CDO-BUSYSTATUS` per event and Outlook's behaviour on a second is
undefined, so the single-instance guarantee is better as a property of the
method than of the order the builder happens to run in. Tested by counting
occurrences, not just asserting presence.

**Reminders have three options, and only one of them is free.** STRIP needs no
work (a freshly built VEVENT has no alarms, so "no reminders" is structural).
FIXED synthesizes a DISPLAY alarm and works for every provider. PASSTHROUGH
lifts the source's own `VALARM`s out of `ProviderEvent.rawPayload` - which
means it only works where that payload is ICS text, i.e. ICS URL sources and
CalDAV/iCloud; Google and Microsoft Graph snapshots are JSON with no VALARM to
copy. That limit is documented in the enum, the UI helper text and the README
rather than papered over, because the alternative - carrying alarms in
`ProviderEvent` for all five providers - is a much larger change than the
setting is worth.

**A bad alarm must cost a reminder, not the feed.** `CalendarOutputter`
validates on the way out, so one unparseable snapshot or one `VALARM` that
ical4j rejects would otherwise throw out of `buildFeed`, become a
`ProviderException`, and turn `/feed/{token}.ics` into a 502 for every event in
every source calendar of that feed. Each alarm is validated individually and
skipped with a log line if it fails; an unreadable snapshot is logged and
yields no alarms. Same reasoning as the existing rule-evaluation catch in
`IcsExportService` - a feed that is missing one detail beats a feed that is
gone.

**SQLite took the columns without a table rebuild.** Unlike V2, `ADD COLUMN`
is enough here: SQLite permits a `CHECK` constraint and a `NOT NULL` column on
`ADD COLUMN` as long as a constant default is supplied. Checked against the
`sqlite3` CLI before the migration was written, then again by running V1, V2
and V3 in sequence against a scratch database and confirming both that an
existing row picks up the defaults and that the CHECK constraints reject a bad
enum value and an out-of-range offset.

**Verified against a running app, not just unit tests.** An ICS source served
over local HTTP (one event carrying its own `VALARM`), four feeds over the same
source calendar, fetched over real HTTP from `/feed/{token}.ics`:

| Feed settings | What came back |
|---|---|
| Outlook / Private / Out of office / no reminders | `CLASS:PRIVATE`, `TRANSP:OPAQUE`, `X-MICROSOFT-CDO-BUSYSTATUS:OOF`, source VALARM gone |
| defaults | no `CLASS`, no `TRANSP`, no extension, no VALARM - identical to pre-V3 output |
| Google / Private / Out of office / keep source reminders | `CLASS:PRIVATE`, `TRANSP:OPAQUE`, **no** extension, source `TRIGGER:-PT10M` carried through |
| Universal / Busy / fixed 45 min | `TRANSP:OPAQUE`, `X-MICROSOFT-CDO-BUSYSTATUS:BUSY`, one `VALARM` at `TRIGGER:-PT45M` |

The first row is the Microsoft/Outlook requirement this work started from,
observed on the wire rather than asserted in a test. UI verified by the usual
method - logged in over curl against a fresh database using the bootstrap
admin password from the boot log, confirmed `/feeds` and every other view
returns 200 authenticated with no `AnnotatedViewAccessChecker` warning and no
exception in the log.

**What this deliberately does not claim to do.** `CLASS:PRIVATE` is a
sensitivity flag the receiving client is trusted to honour, not encryption -
the ICS body still carries summary, description and attendees in clear text
behind nothing but the feed token. And removing `VALARM` only stops the feed
asking for a notification; it cannot override reminders imposed by Outlook's
per-calendar defaults, an Exchange policy, or Google's own notification
settings for a subscribed calendar, which belong to the subscriber. Both are
stated in the enums' javadoc and in the README, since a privacy feature that
is quietly weaker than its name is worse than not having it.

## Negated text operators (`does not contain`, `does not start with`)

`RuleOperator` gains `NOT_CONTAINS`, `NOT_STARTS_WITH` and - because a
"does not start with" with no "starts with" beside it is a strange thing to
offer - `STARTS_WITH`. Text fields only; `V4` rebuilds `rule_condition` to
widen its operator CHECK. Two things about negation needed deciding rather
than implementing.

**Negation applies to the whole match, not to each candidate.** ATTENDEE is
the one multi-valued text field: `candidateValues` returns the attendee list,
and CONTAINS is `anyMatch`. The obvious implementation - invert the comparison
inside the lambda - produces "SOME attendee fails to match", which is true of
practically every event with two or more attendees, *including every meeting
the person you are filtering for is actually in*. The correct reading of
"attendee does not contain bob@" is "NO attendee contains it", so the
evaluator computes the positive match and inverts the result:
`operator.isNegated() != matched`. `RuleOperator.positiveForm()` carries the
pairing so the inversion is decided in one place instead of by a `switch` in
the evaluator. There is a test for exactly the wrong version - an event with
alice@ and bob@, asserting `NOT_CONTAINS "bob@"` is false.

**An absent field satisfies a negated operator, and that is deliberate.**
`candidateValues` already maps a null title to `""`, which contains nothing, so
"title does not contain X" is true for an untitled event. That is what the
words mean, and the alternative - treating a missing field as "no opinion" -
would make a negated operator quietly fail to match the events that most
obviously satisfy it. It is still the single most surprising thing here,
because a DELETE rule written as "title does not contain [Work]" then matches
every untitled event too. So it is called out where the choice is made, not
just in this file: `UiLabels.negationCaveat` puts it in the operator picker's
helper text the moment a negated operator is selected, with different wording
for ATTENDEE ("matches when NO attendee matches - including events with no
attendees at all").

**Operator ordering became load-bearing, so the supported sets are now
`EnumSet`.** The picker lists operators in the iteration order of the set the
evaluator returns. With three operators, `Set.of`'s unspecified order was
merely untidy; with six, and with each negation needing to sit beside the
operator it negates, it was not. `EnumSet` iterates in declaration order, so
`RuleOperator`'s declaration order is now the UI order and the enum says so.
Safe to reorder because every operator is persisted with `EnumType.STRING` -
nothing writes an ordinal. The other three evaluators were switched too, so
the rule holds everywhere rather than in the one place it currently matters.

**`V4` copies `id` explicitly.** SQLite cannot alter a CHECK constraint, so
`rule_condition` is rebuilt and copied, as `rule_scope` was in V2. Unlike V2
the rows already have ids worth keeping: `ConditionEditorComponent`'s Remove
button passes a condition id to `deleteCondition`, so renumbering live rows
under an open rule dialog would delete the wrong condition. **Verified** by
running V1-V4 in sequence against a scratch database with existing conditions
at non-contiguous ids (7 and 9): ids and `case_sensitive` survived, the new
operators are accepted, an unknown one is still rejected by the CHECK, the
index was recreated, and `ON DELETE CASCADE` still fires - deleting the parent
rule removed its conditions, which a rebuilt table can easily lose.

**A UI listener-ordering bug was caught while adding the caveat text.**
Clearing the field ComboBox calls `operator.clear()`, which fires the
operator's own value-change listener, so whichever listener wrote the helper
text first lost. Both now call one `syncOperatorHelp` that derives the text
from the current state of both fields rather than from the event that happened
to arrive.

**Verified against a running app.** An ICS source with three events (`Weekly
Standup` with a description containing "boring", `Retro` and `Standup Extra`
with no description at all), four feeds over the same calendar, fetched from
`/feed/{token}.ics`:

| Feed's rule | Events served |
|---|---|
| none | Weekly Standup, Retro, Standup Extra |
| title **does not contain** "Standup" -> DELETE | Weekly Standup, Standup Extra |
| title **does not start with** "Weekly" -> DELETE | Weekly Standup |
| description **does not contain** "boring" -> DELETE | Weekly Standup |

The third row confirms STARTS_WITH is anchored (`Standup Extra` does not start
with "Weekly" and was excluded). The fourth is the missing-field behaviour on
real data: the two events with no description were excluded, because they do
not contain "boring".

## Autostart on reboot

`docker-compose.yml` already carried `restart: unless-stopped`, which is the
correct policy and was never the problem. The gap was one layer down: a restart
policy is executed by the Docker daemon, so it does nothing at all on a host
where the daemon itself does not start at boot - and on a socket-activated
install (`docker.socket` enabled, `docker.service` not, which is the Arch
default) it does not. `dockerd` is started lazily by the first connection to
`/run/docker.sock`, an idle boot never makes one, and so the container stays
down with nothing logged anywhere to say why: `docker ps` starts the daemon as
a side effect of asking, which is exactly the command an operator reaches for,
so the act of investigating hides the symptom.

**Observed on the development host rather than reasoned about**: 44 minutes
after boot, `systemctl is-enabled docker.socket` was `enabled` and active,
`docker.service` was `disabled` with an empty `ActiveEnterTimestamp` (never
started this boot), and there was no `dockerd` process - so any container with
a restart policy would still have been down. That is the failure this section
is about, on the machine the app is developed on.

The fix is `systemctl enable docker.service`, documented in the README with a
verification step that deliberately reboots and then checks `docker compose ps`
*before* running any other docker command - checking it any other way starts
the daemon and reports success either way.

**`unless-stopped` kept rather than `always`.** The two differ only in whether
a container the operator stopped by hand comes back when the daemon next
starts. `always` would override a deliberate `docker compose stop`, which is a
worse default for a self-hosted app than the one thing it buys. The cost is
worth stating because it is the surprising half: `docker compose down` removes
the container along with its restart policy, so a stack taken down before a
reboot does not come back under either value.

**A systemd unit is offered as an alternative, not as the recommendation.**
`deploy/calendarsync-compose.service` follows the same "reference config this repo does
not run" convention as `nginx/nginx.conf`. It exists because two real cases sit
outside a restart policy's reach - `Requires=docker.service` pulls the daemon up
at boot regardless of how Docker was installed, and re-running `compose up -d`
recreates a stack that was taken `down` and picks up `.env` edits - but for a
host with `docker.service` enabled it is redundant, and redundant process
managers are their own failure mode. Its `ExecStop` is `compose stop`, not
`down`, so a shutdown does not discard the containers; that same `stop` marks
them user-stopped, which `unless-stopped` then declines to restart, so once the
unit is installed the unit is what brings the stack back. Both mechanisms can
coexist and the unit's comments say which one is in charge, since "I have a
restart policy *and* a unit, so why did neither fire" is the confusing state
this replaces.

## Native install on Ubuntu

A second deployment shape alongside Docker: the jar under systemd, on a host
with no container runtime. `deploy/` now holds `install-ubuntu.sh`,
`calendarsync-native.service`, `calendarsync.env.example`, and the Compose unit
renamed to `calendarsync-compose.service` - both install to the same unit name,
`calendarsync.service`, so that a host physically cannot end up running two
copies of the app under two names, contending for port 8080 and potentially for
one SQLite file. The installer refuses to overwrite the other one.

**The build host and the run host are deliberately separated.** The server
needs a JRE 25 and nothing else - no Maven, no JDK, no Node. That is partly
ordinary hygiene and partly this project's specific problem: `-Pprod` needs a
Node at or above `vaadin-maven-plugin`'s floor, Ubuntu 26.04's archive ships
22.x, and Vaadin's response to an old Node is to silently download its own from
nodejs.org rather than fail (recorded under "Build toolchain" above). Rather
than repeat that on every server, the README points at the Dockerfile's `build`
stage - which already pins Node by digest - as a way to produce the jar with no
Node on the build host either.

**The installer does not install packages or add apt repositories.** It checks
for Java 25, and on failure prints the right commands for the release it finds
itself on - `openjdk-25-jre-headless` from the archive on Ubuntu 26.04 LTS,
which defaults to OpenJDK 25, and the Eclipse Temurin apt repository on
anything older. Adding a third-party repository is a decision an operator
should make in the open, and it is the same reasoning that took the piped
`curl … | bash` NodeSource install out of the Dockerfile in Stage 5.

**The profile-mismatch failure was measured, and it is worse than the CLAUDE.md
warning implies.** The installer inspects the jar and refuses a `-Pdev` build,
because running one under `SPRING_PROFILES_ACTIVE=prod` does not fail. Verified
by doing it: the app started normally in 15s, logged nothing unusual, and
Xerial's driver - which has no idea what `?key=` means and does not reject it -
treated the entire JDBC URL as a filename. The result on disk was a file called

    calendarsync.db?key=<the database key, in cleartext>

beginning with the bytes `SQLite format 3`, holding all thirteen tables and the
bootstrap admin row, readable by any `sqlite3`. So the mismatch costs both
protections at once: the database is not encrypted, and the key that was
supposed to encrypt it is now in a filename that `ls`, tab-completion and every
backup will carry. The check is a nested-jar lookup for `org/sqlite/mc/`, the
package that only Willena's driver has (16 entries there, 0 in Xerial's).

**`SERVER_ADDRESS=127.0.0.1` is in the environment file for a reason that is
easy to lose in the move from Docker.** `docker-compose.yml` binds the
published port to a specific interface, and that mapping is the thing keeping
the app off every address the host has. A native install has no port mapping,
and Spring's own default is all interfaces - so the protection silently
disappears unless something replaces it. **Verified on the wire** rather than
assumed: booted the jar with `SERVER_ADDRESS=127.0.0.1` and `ss -ltnp` showed
`[::ffff:127.0.0.1]:8080`; booted it again without and the same check showed
`*:8080`. Spring's relaxed binding maps the variable to `server.address`, so
this needs no code change - only a default that is written down.

**Two paths that are container-shaped and must be overridden**, both defaulting
to `/data/…` in `application-prod.yml`: `CALENDARSYNC_DB_PATH` and, when the
log destination is `file`, `CALENDARSYNC_LOG_PATH`. On a normal filesystem
`/data` does not exist, and the unit's `ProtectSystem=strict` makes it
unwritable even where it does. Both are set in `calendarsync.env.example` with
the reason attached, rather than left to be discovered as `SQLITE_CANTOPEN` on
first start.

**The environment file is 0600 root:root, and the service account cannot read
it.** systemd reads `EnvironmentFile=` as PID 1, before dropping to `User=`, so
the process gets `CALCLEANER_DB_KEY` in its environment without the account it
runs as ever having read access to the file holding it. Docker's `.env` cannot
do that - the client reads it as the invoking user - which makes this the one
place the native install is straightforwardly better than the container.

**Unit hardening notes worth keeping.** `MemoryDenyWriteExecute=true` is absent
on purpose: the JIT writes to pages it then executes, so it prevents the JVM
from starting at all, and an unexplained absence invites someone to "fix" it
later. `RestrictAddressFamilies` includes `AF_NETLINK` because Java's
`NetworkInterface` enumeration uses a netlink socket during Tomcat startup.
`SuccessExitStatus=143` because the JVM exits 128+SIGTERM on a clean
`systemctl stop` and every ordinary stop would otherwise be recorded as a
failed unit - confirmed incidentally while testing, when a `kill` of the test
JVM was reported as exit 143. `Type=exec` rather than `simple` so a bad java
path or missing jar is a start failure rather than a success followed by a
puzzle in the journal.

**`SPRING_PROFILES_ACTIVE=prod` is set in the unit, not the environment file.**
It is not a deployment preference the operator should be editing - it has to
match the Maven profile the jar was built with, and the consequence of getting
it wrong is the plaintext database above. Keeping it in the unit puts it next
to the `ExecStart` it constrains.

## .gitignore audit

Reviewed against the working tree rather than against a template, and checked
in both directions afterwards - every rule asserted to fire on the files it
should hide, and asserted *not* to fire on the ones that must stay tracked
(`.env.example`, `deploy/calendarsync.env.example`, `nginx/certs/.gitkeep`, the
hand-authored theme, the logback destination includes). What changed:

**Vaadin's root-level npm artifacts were missing entirely.** `vaadin-maven-plugin`
writes `package.json`, `vite.config.ts`, `vite.generated.ts`, `tsconfig.json`,
`types.d.ts` and `node_modules/` into the *project root* when `build-frontend`
has real work to do. That never happened locally while the prod build was still
falling back to the prebuilt `vaadin-prod-bundle` (Stage 4), which is why the
gap went unnoticed - and it stopped being theoretical twice over since: the
theme now makes `build-frontend` compile for real, and the native install
documents `mvn -Pprod package` as something to run on your own machine.
Verified that `prepare-frontend` alone writes nothing at the root - all 34
files it generated landed under `src/main/frontend/generated/`, already covered
- so the root list follows Vaadin's documented set rather than an observed full
build. If a hand-authored `package.json` is ever added, that one file needs
un-ignoring; Vaadin merges into a customised one rather than overwriting it.

**`.env` covered the file but not its variants.** `.env.prod`, `.env.local` and
anything else in that family were committable. Now `.env.*` with
`!.env.example` after it, plus the native install's
`deploy/calendarsync.env` by name. These files hold `CALCLEANER_DB_KEY`, so
committing one publishes every encrypted row it protects and there is no re-key
path - the same reasoning that keeps the example files deliberately separate
from the real ones.

**`nginx/certs/*.pem` only caught one spelling of a private key.** A key
arrives as `.pem`, `.key`, `.p12` or `.jks` depending on who issued it, so the
directory's contents are now ignored outright with `!nginx/certs/.gitkeep`
keeping the directory itself.

**`data/` is now `/data/`.** Unanchored, it would also swallow any future
`src/test/resources/data/` fixture directory silently. Only the repository root
has one today, so anchoring costs nothing and removes a trap.

**`*.hprof` added.** A heap dump is ordinary build noise in most projects; in
this one it contains `CALCLEANER_DB_KEY`, the decrypted calendar contents and
whatever OAuth refresh tokens were live at the time.

**`.claude/settings.local.json` added**, that being the machine-specific half
of the Claude Code config. The hooks, skills and shared settings beside it are
project tooling and stay tracked.

## Two-factor authentication (TOTP)

RFC 6238 codes as a second factor, opt-in per user with an admin "required"
flag, ten single-use recovery codes, and an admin reset for somebody locked
out. Login becomes two steps: password, then code. `V5` adds five columns to
`app_user` and one `totp_recovery_code` table.

**The parameters are an interoperability requirement, not a default nobody
revisited.** SHA-1, six digits, thirty seconds, 160-bit secret. The
`otpauth://` Key Uri Format has `algorithm`, `digits` and `period` parameters
and it is tempting to read `algorithm=SHA256` as a free upgrade, but a number
of authenticators and password managers ignore that parameter and compute
SHA-1 regardless. A server that emits SHA256 and validates SHA256 produces an
enrolment that scans perfectly, displays a plausible code, and never
validates - which presents to the user as a clock problem and is close to
undebuggable over a support channel. This is not a claim that SHA-1 is
preferable; HMAC-SHA-1 is unaffected by the collision attacks that retired
bare SHA-1, and the interoperable set is the one every implementation agrees
on.

**Hand-written TOTP and base32 rather than a library, and rather than the
commons-codec already on the classpath.** The whole algorithm is one HMAC and
a truncation. commons-codec *is* present, but only transitively via the Google
HTTP client - an undeclared dependency that an unrelated upgrade can drop, and
the failure would land on the path that decides whether people can log in.
`TotpTest` pins the implementation to RFC 6238's own published vectors, and
each vector exercises a different truncation offset, so a subtle error in the
counter packing or the dynamic truncation fails all six rather than none.

**The success handler is installed as a Vaadin *shared object*, and that is
not a workaround for the sake of it.** `VaadinSecurityConfigurer` configures
form login inside its own `init()`, which runs at `http.build()` - i.e. after
the body of `SecurityConfig`'s `filterChain` bean has returned. So
`http.formLogin(f -> f.successHandler(...))` there is silently overwritten
moments later. Reading the configurer's bytecode shows it resolves its handler
as `getSharedObject(VaadinSavedRequestAwareAuthenticationSuccessHandler.class)
.orElseGet(this::createAuthenticationSuccessHandler)`, so registering an
instance of that type as a shared object is the supported way in.

The consequence is worth remembering: supplying the shared object means Vaadin
never calls `createAuthenticationSuccessHandler()`, which is what would
otherwise have applied `defaultSuccessUrl(...)` and the request cache. Both are
now set on the handler instance, and `VaadinSecurityConfigurer.defaultSuccessUrl()`
has been removed from the chain rather than left there looking effective. Left
in, it would be dead configuration that the next person changes and then spends
an afternoon wondering about.

**The session is destroyed between the two steps, not merely cleared.**
`AbstractAuthenticationProcessingFilter` has already written the
`SecurityContext` through the `SecurityContextRepository` by the time a success
handler runs. Clearing `SecurityContextHolder` therefore leaves a fully
authenticated session persisted on the server while the user is looking at a
"enter your code" page - the second factor would be skippable by requesting any
other URL. Invalidating the session takes the stored context with it and
rotates the session id into the bargain. `TwoFactorLoginTest` asserts that
`/connections` still bounces to `/login` after the password step, which is the
test that would catch a regression to the clearing version.

**The second factor is a real `AuthenticationProvider` behind a
`ProviderManager`, not a few lines in a Vaadin click listener.** That is what
makes `ProviderManager` publish `AuthenticationSuccessEvent` and
`AuthenticationFailureBadCredentialsEvent` - the only thing
`AuthenticationEventLogger` listens to, and therefore the only thing feeding
`LoginAttemptService`. Verified anywhere else, a six-digit code is unthrottled,
and 10^6 is a couple of hours of unattended guessing. Two traps came out of
this, both of which fail silently:

- A `ProviderManager` constructed by hand gets a `NullEventPublisher`. It has
  to be given `setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(...))`
  or it logs and throttles nothing while looking entirely correct.
  `TwoFactorLoginTest.failedCodesFeedTheLoginThrottle` exists for that one line.
- **`SecondFactorAuthenticationProvider` must not be a `@Component`.** Spring
  Boot's `InitializeUserDetailsManagerConfigurer` stops auto-configuring a
  `DaoAuthenticationProvider` from the `UserDetailsService` the moment *any*
  `AuthenticationProvider` bean exists. Publishing it as a bean left the global
  `AuthenticationManager` holding only a provider that does not support
  `UsernamePasswordAuthenticationToken`, so **every password login in the
  application failed** with `ProviderNotFoundException` - which surfaces as an
  ordinary "bad credentials" redirect and so reads as a wrong password rather
  than a broken configuration. Caught by an integration test on the *no-2FA*
  path, not by anything to do with 2FA. `SecurityConfig` constructs it by hand.

**A successful password no longer clears the brute-force counter on its own,
and this was a real hole rather than tidying.** `AuthenticationEventLogger`
called `recordSuccess` on every `AuthenticationSuccessEvent`. With a second
factor configured the password step also publishes one, so each attempt reset
the counter: an attacker who already had the password could guess codes one per
login, indefinitely, and never reach `MAX_FAILURES`. The throttle stayed green
while protecting nothing - the precise failure the class was written to
prevent. The counter is now cleared only for a *completed* login, which
`SecondFactorCompletedAuthentication` marks.

**Replay protection: `totp_last_step`.** A code is valid across the current
step and one either side, i.e. up to ninety seconds. Without recording the
accepted step, a code observed once - over a shoulder, in a screen share, in a
proxy log that captured a POST body - is replayable for the rest of that window.
Verification requires a strictly greater step than the last accepted one.
Confirmed on the wire as well as in tests: the same code, verified as still
current by recomputing it afterwards, was accepted once and refused on reuse.

A side effect worth knowing when reading the tests: confirming an enrolment
consumes the step it used, so a test that enrols with the current code cannot
then sign in with it. The helpers enrol with the *previous* step's code, which
is also what a real user typing the last code before it rolls over produces.

**Recovery codes are SHA-256, not bcrypt.** Checking a submitted code means
testing it against every unused row for that user, on an endpoint reachable
before authentication completes; ten bcrypt comparisons per guess is about a
second of CPU an unauthenticated caller can spend at will. bcrypt's cost exists
to protect secrets a human chose badly - these are ten characters from a
32-character alphabet generated by `SecureRandom`, i.e. 50 bits, so there is no
dictionary to slow down and nothing to gain in exchange for the availability.
They are consumed by setting `used_at` rather than by deletion, so "how many
are left" is answerable and "never had any" is distinguishable from "burned
them all".

**The verify page uses native HTML inputs, and that is the point.** Vaadin's
`TextField` does put its inner `<input>` in light DOM where a password manager
can see it, but it carries no `name` attribute and is not reliably
form-associated, so a real browser form submit posts nothing - and the submit
has to be real, because a Spring Security filter is what processes it. So the
form is a `@Tag("form")` container (`flow-html-components` has `Input` and
`NativeButton` but no form element) holding
`com.vaadin.flow.component.html.Input` fields, with
`autocomplete="one-time-code"`, `inputmode="numeric"` and `autofocus`. The
two-page shape is deliberate for the same reason: it is the flow Bitwarden and
1Password are built around - they fill and submit username and password on the
first page, then offer the stored code on the second.

**`/login/verify` carries a real CSRF token.** Vaadin's configurer exempts
exactly two things from CSRF - its own internal requests, and the form-login
page path - which is why `LoginForm.setAction("login")` works without one. A
new path is not exempt, so `TwoFactorVerifyView` renders the token as a hidden
field. Confirmed on the wire: a POST without it is refused and the session
stays unauthenticated.

One consequence surfaced while testing. A Vaadin view is built client-side, so
the hidden field does not exist in the bootstrap HTML an HTTP client receives -
only in the DOM after the browser has run the client engine. `TwoFactorLoginTest`
therefore reads the same session token out of the `<meta name="_csrf">` tag
Vaadin writes into every bootstrap page. That is the token the form field would
carry, so the test exercises the real check rather than routing around it.

**One guess per password step.** The pending record is consumed in
`attemptAuthentication` before verification is attempted, so a wrong code costs
a fresh password login to try again. Without that, a pending session is a
standing permit to keep guessing and the rate limiter is arguing with a loop.
Observed directly: a wrong code redirects to `?error`, and retrying with the
correct code against the same pending record gets `?expired`.

**The forced-enrolment gate is a prompt, not a containment boundary, and the
comments say so.** A user marked required but not yet enrolled has given the
correct password and *is* fully authenticated; `ForcedEnrolmentInitializer`
reroutes their Vaadin navigation to the setup view, but feed tokens they
already hold keep working and a non-Vaadin endpoint is still reachable by
typing its URL. Making it a real boundary would mean withholding authentication
until enrolment completes, which locks people out of the very page that would
fix it. The security boundary in this feature is the second factor demanded of
users who *have* enrolled, and that one is enforced in the authentication layer
where it cannot be walked around.

Its wiring is two lines of stock Vaadin API; the decision it makes is a static
method with its own test. That split is deliberate - constructing a real
`BeforeEnterEvent` needs a router and a UI, and testing the framework's own
listener dispatch would not be testing this feature. **This is the one part of
the work not verified end-to-end without a browser**, and it is recorded as
such rather than implied to be covered.

**QR codes: zxing core only, rendered to SVG.** The companion `zxing-javase`
artifact writes through `BufferedImage`/`ImageIO`, i.e. the `java.desktop`
module - a lot of desktop graphics stack to carry in a headless server image in
order to draw black squares. A `BitMatrix` is already a grid of booleans, so
one `<path>` segment per horizontal run of dark modules is a dozen lines and
scales without going blurry on a phone. `core` has no runtime dependencies of
its own. `TotpQrCodeTest` reconstructs the module grid from the generated SVG
and decodes it with zxing's own reader: asserting that the SVG "contains a
path" would pass for a QR code no phone can scan. The white background is
painted explicitly because an inverted QR code does not scan and this app has a
dark theme.

Delivered as a `data:` URI rather than from an endpoint. A new MVC endpoint
would need its own `authorizeHttpRequests` rule or `VaadinSecurityConfigurer`
answers it with a bare 403 (the trap the `/oauth2/**` rule already documents),
would need its own authorization so one user cannot fetch another's QR, and
would put a TOTP secret in a URL that can reach an access log.

**The candidate secret lives in the Vaadin session until a code confirms it.**
Writing it to `app_user` first and flipping a flag afterwards is simpler, but a
mis-scanned QR or a closed tab then leaves a row that looks half-enrolled - and
the failure mode for getting second-factor state wrong is somebody locked out of
their own account. It is held rather than regenerated per render so that a page
refresh does not swap the secret under a QR the user has already scanned.

**Switching 2FA off requires the account password.** Everything else in
`AccountView` is reachable by whoever holds the session, but a stolen session
that can silently strip the second factor makes the second factor decorative -
the attacker removes it and keeps the password they already have.

**`TotpSecretCipher` mirrors `CredentialCipher` with a `"totp"` context**, so
the secret is keyed differently from connection credentials while still coming
from the one `CALCLEANER_DB_KEY` the operator manages. It inherits that class's
honest limitation: with `calendarsync.db.encrypted=false` (the dev profile) it
is a passthrough and the secret is stored in cleartext, exactly as connection
credentials already are. Fine for a dev database, and a reason not to enrol an
account you care about against one.

**Verified against a running app, not only unit-tested.** Booted on the dev
profile, logged in over curl with the bootstrap admin password from the boot
log, and confirmed every view - including the new `/account/two-factor` -
returns 200 authenticated with no `AnnotatedViewAccessChecker` warning. Then
enabled 2FA on the account and drove the two-step login on the wire:

| Step | Result |
|---|---|
| `POST /login` with the correct password | `302` to `/login/verify` |
| `GET /connections` holding only that session | `302` to `/login` - the password alone grants nothing |
| `POST /login/verify` with a valid code | `302` to `/connections`, and `/connections` then returns `200` |
| the same code again, still inside its 30s step | `302` to `/login/verify?error` |
| `POST /login/verify` with no CSRF token | refused; session stays unauthenticated |
| a wrong code, then the right one on the same pending record | `?error`, then `?expired` |

The code in row three was computed by an **independent Python implementation**
of RFC 6238 rather than by this application's own, so the row is a
cross-check that a third-party authenticator will interoperate, not a tautology
about the code agreeing with itself.

## Updating an install

`deploy/update.sh` covers both deployment shapes, auto-detecting which is
present and refusing to guess when both or neither are.

**It builds the jar itself, and it is run without `sudo`.** Those two are the
same decision. The privileged work is a handful of quick operations - read the
0600 environment file, stop the unit, copy, install, start - while the build is
the long, entirely unprivileged part. Running the whole script as root would
run Maven as root too, leaving a root-owned `target/` and `~/.m2` that break the
operator's next ordinary build, with permission errors a long way from their
cause. So the script runs as the invoking user and calls `require_root` only
when it first needs it.

The ordering matters more than it looks: **the build happens before root is
requested at all.** Asking first would mean the sudo timestamp is acquired and
then left to idle through a multi-minute Maven run, and a password prompt
appearing part-way through - potentially in the middle of a rollback - is
exactly what the up-front prompt is meant to avoid. Every privileged call goes
through one `as_root` wrapper, which primes the credential on first use, so
after that point nothing can block on a prompt.

Two smaller consequences. `sudo ./deploy/update.sh` still works rather than
being refused, because people will type it out of habit and the old
documentation told them to - if `SUDO_USER` is set the build is handed back to
that account, and only a genuine root login (no `SUDO_USER`) gets a warning
that its build artifacts will be root-owned. And the Docker path asks the
daemon whether it needs `sudo` at all (`docker info` as the current user)
rather than assuming either way, since a user in the `docker` group needs none.

**Tests run as part of the build by default.** `mvn -Pprod package` under the
prod Maven profile was verified to pass all 150 tests, which is not obvious -
CLAUDE.md warns that dropping the Maven `dev` profile removes `vaadin-dev` and
breaks any test that requests a view. It does not here, because `-Pprod` builds
a production frontend bundle at `compile`, so the views render from that
instead. `--skip-tests` exists for an urgent deploy, but a green suite before
replacing a running jar is worth the extra half minute.

**It exists because upgrades now carry migrations.** `install-ubuntu.sh` has
always doubled as an upgrade, but it replaces the jar under a running service,
keeps no copy of what it replaced, and never checks that the app came back.
That was survivable while an upgrade was only new code.

**The jar and the database roll back together, always.** This is the reason the
script exists at all. Flyway is forward-only and validates at startup, so if a
new version applies a migration and then fails for some unrelated reason,
restoring only the jar leaves a database at a schema version the old jar has no
migration for - it refuses to start with "detected applied migration not
resolved locally". The rollback would appear to have worked and the service
would fail on its *next* restart instead, which is the worst possible moment to
discover it.

**The database is copied while the service is genuinely stopped**, polled for
rather than assumed after `systemctl stop` returns: a SQLite file copied out
from under a running writer can be torn, and a torn backup is only discovered
when it is needed. A plain `cp` (plus any `-wal`/`-shm` siblings), **not**
`sqlite3 .backup` - under the prod profile the file is encrypted by the Willena
driver, so `sqlite3` cannot open it without the key, and the key must not be put
on a command line. Free space is checked first, because a backup that runs out
of disk half way through is the worst of both worlds.

**Health is measured by asking the app for a page, not by `systemctl
is-active`.** With `Type=exec` systemd reports the unit active the moment the
JVM execs - long before Flyway has migrated and Tomcat is listening, and a
migration failure exits *after* that point. There is no actuator, so the check
is `GET /login`: the one route that answers 200 with no credentials, which
means a 200 proves the context started, Flyway succeeded and Vaadin is serving.
Confirmed against the running app.

**The `-Pprod` jar check is reused verbatim from the installer**, and is the
most important gate in either script - a `-Pdev` jar cannot open an existing
encrypted database and instead writes a plaintext one to a filename containing
the key. Confirmed working against this repository's own dev-profile jar, which
it correctly refuses (Xerial driver, zero `org/sqlite/mc/` entries). The script
also refuses a jar byte-identical to the installed one, so nobody takes an
outage for a no-op.

**The Docker path runs `tar` inside the application's own image** rather than
pulling `alpine`, so a volume backup does not depend on the host having network
access or on trusting a second image - the app image is by definition already
present. The volume is emptied before restoring, or a database migrated forward
would still be sitting there underneath the restored files. It uses `compose
stop`, never `down`, so it does not fight the Compose unit's `ExecStop`.

The environment file is parsed with `grep`, not sourced: systemd reads it as
plain `KEY=value`, so sourcing it as shell would execute whatever a value
happened to look like. Verified against base64 keys containing `/`, `+` and
`=`, quoted values, and missing keys falling back to defaults.

**Verified end to end against a sandboxed install, not only by reading it.** A
harness with a fake `systemctl` driving a real HTTP server, fake `-Pprod` and
`-Pdev` jars built to satisfy the `org/sqlite/mc/` probe, and a fake app that
appends a "migration" line to the database on every start:

| Case | Result |
|---|---|
| a `-Pdev` jar | refused, with the plaintext-database explanation |
| `--dry-run` | reports the plan, changes nothing |
| a good jar | backup written (database + old jar), jar swapped, health check passes |
| a jar that migrates and then fails to start | both jar **and** database restored, previous version healthy again |
| the same run with the database restore deleted | the forward migration survives - so the test above genuinely discriminates |
| a jar byte-identical to the installed one | exits early rather than taking an outage |
| a deliberately broken Maven invocation | "the build failed - nothing has been changed"; installed jar untouched, app still serving |

The fifth row is the one worth keeping. A rollback test that passes whether or
not the rollback happened proves nothing, so the control run removes the
database restore and confirms the assertion flips.

## Version substitutions

Checked live against Maven Central during planning and again as each stage
actually added the dependency (Aug 2026):

| Spec version | Used | Why |
|---|---|---|
| `spring-boot-starter-parent:4.0.1` (implied by "4.x") | `4.0.4` | Jackson version incompatibility with Vaadin 25.1.4 - see Stage 0 above |
| `com.microsoft.graph:microsoft-graph:6.67.0` | `6.66.1` | 6.67.0 was not found on Maven Central at implementation time; 6.66.1 was the latest available |
| Everything else (`ical4j:4.3.0`, `org.xerial:sqlite-jdbc:3.53.2.1`, `io.github.willena:sqlite-jdbc:3.53.2.0`, `quartz:2.5.2`, `vaadin:25.1.4`, `google-api-client:2.9.0`, `azure-identity:1.16.2`) | as specified | confirmed present on Maven Central, no substitution needed |
| n/a - not in the spec | `net.logstash.logback:logstash-logback-encoder:9.0` | Added for the `elastic` log destination. 9.0 rather than the more widely-used 8.1 because 9.x is the first line built against Jackson 3 (`tools.jackson.*`), which is what Boot 4 puts on the classpath |
