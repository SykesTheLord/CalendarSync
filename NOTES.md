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

## Version substitutions

Checked live against Maven Central during planning and again as each stage
actually added the dependency (Aug 2026):

| Spec version | Used | Why |
|---|---|---|
| `spring-boot-starter-parent:4.0.1` (implied by "4.x") | `4.0.4` | Jackson version incompatibility with Vaadin 25.1.4 - see Stage 0 above |
| `com.microsoft.graph:microsoft-graph:6.67.0` | `6.66.1` | 6.67.0 was not found on Maven Central at implementation time; 6.66.1 was the latest available |
| Everything else (`ical4j:4.3.0`, `org.xerial:sqlite-jdbc:3.53.2.1`, `io.github.willena:sqlite-jdbc:3.53.2.0`, `quartz:2.5.2`, `vaadin:25.1.4`, `google-api-client:2.9.0`, `azure-identity:1.16.2`) | as specified | confirmed present on Maven Central, no substitution needed |
| n/a - not in the spec | `net.logstash.logback:logstash-logback-encoder:9.0` | Added for the `elastic` log destination. 9.0 rather than the more widely-used 8.1 because 9.x is the first line built against Jackson 3 (`tools.jackson.*`), which is what Boot 4 puts on the classpath |
