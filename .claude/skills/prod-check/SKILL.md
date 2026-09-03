---
name: prod-check
description: Build and boot CalendarSync exactly as production does - mvn -Pprod package plus a prod Spring-profile run with an encrypted database - to catch Maven/Spring profile drift, missing env vars, and Vaadin production-bundle failures before Docker does.
disable-model-invocation: true
---

Prove the production path works. This is the check that catches the repo's main trap: the Maven `prod` profile and the Spring `prod` profile are separate mechanisms that must be aligned by hand.

Use a throwaway database — never point this at `./data/calendarsync-dev.db`, whose driver differs.

## 1. Production build

```bash
mvn -Pprod package
```

Requires Node.js on PATH for Vaadin's `build-frontend`. Watch for:

- **`build-frontend` skipped or falling back to `vaadin-prod-bundle`** — means the custom theme in `src/main/frontend/themes/calendarsync/` was not compiled in. Report it; it is not necessarily fatal but it means the build did not exercise what you think it did.
- Node missing entirely — the build fails here, not at runtime.

## 2. Production boot

The `prod` profile needs both env vars, and the jar must be the `-Pprod` one just built (it carries the Willena encrypted driver, not Xerial):

```bash
TMPDB=$(mktemp -d)
SPRING_PROFILES_ACTIVE=prod \
  CALCLEANER_DB_KEY=prodcheck-throwaway-key \
  CALENDARSYNC_BASE_URL=http://localhost:8080 \
  CALENDARSYNC_DB_PATH="$TMPDB/calendarsync.db" \
  CALENDARSYNC_LOG_PATH="$TMPDB/calendarsync.log" \
  java -jar target/calendarsync-0.1.0-SNAPSHOT.jar > /tmp/calsync-prod.log 2>&1 &
```

Poll `/tmp/calsync-prod.log` until `Started CalendarSyncApplication`, a failure, or ~90s.

Then:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/login
```

## 3. Confirm it is really the production path

Do not accept a green boot at face value — check that prod-specific behavior actually engaged:

- **Encrypted DB**: `head -c 16 "$TMPDB/calendarsync.db" | xxd` should *not* show the plaintext `SQLite format 3` header. If it does, the Xerial driver was packaged and the `-Pprod` build did not take.
- **Vaadin production mode**: the log should not mention the dev server or Vite. Page source should reference a built bundle, not a dev-mode entry point.
- **Flyway** ran the full migration chain against a fresh file, `V1` onward.
- The bootstrap admin password is printed once — note that it appeared, since that path only runs on an empty database.

## 4. Negative check (the drift trap)

Confirm the fail-fast still fails. Boot the same jar with `SPRING_PROFILES_ACTIVE=prod` and **no** `CALCLEANER_DB_KEY`; it must die with `IllegalStateException: calendarsync.db.encrypted=true but CALCLEANER_DB_KEY is not set`. A successful boot here is a bug worth reporting loudly.

## 5. Clean up

```bash
pkill -f 'calendarsync-0.1.0-SNAPSHOT.jar' || true
rm -rf "$TMPDB"
```

## 6. Report

Say whether the build produced a real production frontend bundle, whether the database was actually encrypted, whether the boot succeeded, and whether the negative check failed as it should. Name anything you could not verify.
