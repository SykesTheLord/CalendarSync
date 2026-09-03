# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Self-hosted calendar sync/filtering app: Spring Boot 4.0.4 + Vaadin Flow 25 + SQLite, Java 25. Single Maven module, no wrapper — use the system `mvn`.

## Commands

```bash
mvn spring-boot:run                        # dev on :8080 (Maven dev profile + Spring dev profile, both default)
mvn test                                   # whole suite, ~31s, fully offline
mvn test -Dtest=RuleEngineImplTest         # single class; add #method for one test
mvn -o -q compile                          # ~1.5s incremental, fastest breakage check
mvn -Pprod package                         # production jar (needs Node.js on PATH)
```

## Profile pairing (the trap)

There are **two independent `dev`/`prod` profile mechanisms with the same names**. The Maven profile (`-P`) picks the SQLite driver jar and Vaadin's frontend build; the Spring profile (`SPRING_PROFILES_ACTIVE`) picks the runtime config. They must be aligned by hand — either half alone is a broken build:

```bash
mvn -Pprod package
SPRING_PROFILES_ACTIVE=prod CALCLEANER_DB_KEY=... java -jar target/calendarsync-0.1.0-SNAPSHOT.jar
```

The Maven `dev` profile exists to supply `vaadin-dev`. Never drop it when running anything that boots the app outside production mode — including tests, which fail with `'vaadin-dev-server' not found` the moment a view is requested. The two SQLite drivers (`org.xerial` dev, `io.github.willena` prod) both register `org.sqlite.JDBC` and must never be on the classpath together.

## Environment

`CALCLEANER_DB_KEY` (note: `CALCLEANER`, not `CALENDARSYNC`) is required under the prod Spring profile and read via `System.getenv`, not Spring properties. It keys both whole-DB encryption and the AES-GCM credential column. `CALENDARSYNC_BASE_URL` is also required in prod and deliberately has no default — it is embedded in every published feed URL. OAuth vars (`GOOGLE_OAUTH_*`, `MS_OAUTH_*`) are optional; missing ones just remove that provider from the connection dropdown. See `.env.example`.

## Database

SQLite, migrated by **Flyway only** — `ddl-auto: none`, so Hibernate never creates or alters anything.

- **Never edit a migration that has already run.** Add a new `V<n>__` file. There are four so far.
- SQLite can't `ALTER TABLE` most things; schema changes are rebuild-and-copy (see `V2__rule_scope_surrogate_id.sql`).
- Timestamps are TEXT and set in `@PrePersist` via `SqliteTimestamps.now()`. Column `DEFAULT (datetime('now'))` does not work here — Hibernate always emits the mapped column in the INSERT.
- `DataSourceConfig` sets `PRAGMA foreign_keys = ON` per connection; SQLite defaults it off, which would silently disable every `ON DELETE CASCADE`.

## Security invariants

These encode bugs that actually shipped. Breaking one fails open, usually silently:

- **User isolation lives in the repository layer**, not in encryption or the service layer. Every query touching user-owned data takes a `userId` (`findByIdAndUserId`, `deleteByIdAndUserId`). Services get the caller from the `CurrentUser` component. A new finder without user scoping is a data leak.
- **Every Vaadin view *and every parent layout* needs an access annotation** (`@PermitAll`, `@RolesAllowed`, `@AnonymousAllowed`). An unannotated layout is treated as more restrictive than its annotated children and silently denies everyone — this is why `MainLayout` carries `@PermitAll`.
- **`TrashService.deleteWithSnapshot` is the only path allowed to call a provider's `deleteEvent`.** A committed `deletion_audit` row must exist before the remote delete. Feed-side exclusions go through `TrashService.recordFeedExclusion`.
- `GET /feed/{token}.ics` is `permitAll` — **the token in the URL is the credential.** `IcsFeedController` checks the owning user by hand and returns **404, not 403**, for a missing feed or disabled owner.
- Every Microsoft Graph call site must wrap the SDK's unchecked `ApiException` into `ProviderException`, or `ConnectionSyncJob`'s error bookkeeping is skipped.
- All server-supplied CalDAV URIs must go through `CalDavUris` (same-site check, no https→http downgrade) — credentials are attached to every request.

## Testing

JUnit 5 + AssertJ + Mockito. No Testcontainers, no integration/unit split — `mvn test` runs everything.

- Integration tests extend `AbstractIntegrationTest`, which points the DB at one JVM-lifetime temp dir via `@DynamicPropertySource`. Deliberately not `@TempDir`: Spring's context cache key ignores the dynamic property's value, so a per-class temp dir leaves later classes on a deleted file.
- `AccountDeactivationTest` is intentionally **not** `@Transactional` — its feed request arrives over real HTTP on another thread.
- `LoggingDestinationTest` mutates the JVM-global logger context and restores it in `@AfterAll`. If that restore breaks, every later test logs through whatever it last configured.

## Conventions

- **Do not commit unless asked.** Leave work in the working tree.
- Definition of done: `mvn test` green, and **NOTES.md updated** with the reasoning behind any new deviation, workaround, or design decision.
- Commits are Conventional Commits, lowercase imperative subject, with a substantive multi-paragraph body explaining *why* and stating what was verified for real.
- No Lombok, no formatter, no linter. 4-space indent, ~110–120 col wrap, explicit imports (no wildcards).
- Constructor injection with `private final` fields. `@Autowired` field injection appears only in the two Quartz `Job` classes, which Quartz instantiates by no-arg constructor.
- Records for value/DTO types.
- **House comment style is explanatory**: class and method javadoc states *why* a non-obvious choice was made, usually naming the failure mode it prevents. Match it — the existing comments are the project's design record.

## Docker build

`docker compose up -d --build`, or `docker build .` for the app alone. Three things bite here:

- **`.dockerignore` must not exclude `src/main/frontend/themes/`.** `@Theme("calendarsync")` makes `vaadin:build-frontend` fail outright if the theme directory is missing from the build context. Only the generated parts belong in `.dockerignore`.
- **Node's version floor is Vaadin's, not ours.** If the Node on `PATH` is older than `vaadin-maven-plugin` wants, it silently downloads its own from nodejs.org instead of failing. A `Downloading https://nodejs.org/dist/...` line in the build log means the pinned Node is being ignored.
- **Anything bound to the Maven lifecycle needs its config copied into the image.** Checkstyle runs at `validate`, so `config/` is copied alongside `pom.xml`; a build that only copies `pom.xml` and `src/` fails before compiling.

## Ports and the proxy trust boundary

The app binds exactly one port: **8080**, plain HTTP. It does not terminate TLS — a reverse proxy on another machine does. There is no actuator and no management port. `CALENDARSYNC_LOG_ELASTIC_PORT` (default 5044) is outbound only. OAuth callbacks arrive inbound at `/oauth2/{google,microsoft}/callback` on the proxy's HTTPS port, so those providers need the instance reachable from the internet; CalDAV/ICS-only setups do not. `nginx/nginx.conf` is a reference config for the proxy machine, not something this repo runs.

**`forward-headers-strategy: native` is a security control, not a preference.** `native` (Tomcat's `RemoteIpValve`) honours `X-Forwarded-For`/`X-Forwarded-Proto` only from source addresses in `server.tomcat.remoteip.internal-proxies`; `framework` (Spring's `ForwardedHeaderFilter`) trusts them from *any* caller and ignores that list entirely. Since `LoginAttemptService` rate-limits password guessing by the client IP from `getRemoteAddr()`, switching back to `framework` on a published 8080 lets an attacker rotate `X-Forwarded-For` per request and never trip the lockout. Verified by observing the redirect scheme with and without a trusted source.

Corollaries worth keeping straight:
- The proxy's address **must** be in `CALENDARSYNC_TRUSTED_PROXIES`, or every request looks like it came from the proxy and one attacker locks out all users at once.
- Publish 8080 bound to a specific interface (`127.0.0.1:8080:8080`, or the proxy's private address), never a bare `8080:8080`.
- The proxy must mask feed tokens in its access log — `/feed/{token}.ics` puts a credential in the URL path.

## Scheduling and caching

Quartz with the default RAMJobStore (no Quartz tables); jobs are re-registered at startup by `SyncJobBootstrap` / `RetentionJobBootstrap`. Not `@Scheduled`. Sync intervals are per-provider and not user-configurable. Feed bodies are an in-memory cache invalidated by explicit triggers — there is no `body` column and no background refresh timer. `RetryHelper` wraps `listEvents` call sites only, never delete or restore.

## Rules

Conditions are `field operator value`; each `RuleField` has one evaluator declaring which operators it accepts, and the UI's operator picker is driven by that set.

- **Supported-operator sets are `EnumSet`, not `Set.of`.** The picker lists them in iteration order, and `EnumSet` iterates in `RuleOperator` declaration order — which is what keeps each negated operator beside the one it negates. Declaration order is free to change (`EnumType.STRING` everywhere, no ordinals persisted).
- **A negated operator inverts the whole match, never the per-candidate test.** ATTENDEE is multi-valued, so `NOT_CONTAINS` must mean "no attendee matches"; inverting inside the lambda silently means "some attendee doesn't match", which is true of almost every multi-attendee event. Use `RuleOperator.positiveForm()` + `isNegated()`.
- **An absent field satisfies a negated operator** (a null title becomes `""`, which contains nothing). Correct, and the surprising part of a DELETE rule — `UiLabels.negationCaveat` surfaces it in the operator picker.

## Feed output

Rules decide *which* events a feed carries; the per-feed `ExportProfile` (`CLASS`, `TRANSP`, `X-MICROSOFT-CDO-BUSYSTATUS`, `VALARM` — five columns on `published_feed`, V3) decides how each survivor is written. Applied in `IcsCalendarMapper.buildFeed`, which builds every VEVENT from normalized fields, so the profile's properties are the only ones that can appear.

- **Every default reproduces the pre-V3 output byte for byte.** Keep it that way — an upgraded install must not change what an already-subscribed client receives.
- **Anything that changes a profile must invalidate that feed's cache**, or the setting looks applied while the old bytes keep being served for up to `cache_ttl_seconds`.
- Alarm `PASSTHROUGH` reads `ProviderEvent.rawPayload` and so only works for ICS-shaped snapshots (ICS sources, CalDAV/iCloud) — Google and MS Graph snapshots are JSON.
- A malformed source alarm is skipped and logged, never propagated: `CalendarOutputter` validates on output, and an escaped `ValidationException` turns `/feed/{token}.ics` into a 502 for the whole feed.

## Reference

@NOTES.md carries the full design-decision and deviation log, including known accepted weaknesses.
