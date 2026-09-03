---
name: verify
description: Verify a change to CalendarSync for real - runs the full test suite, then boots the app on the dev profile and confirms the context comes up clean and serves a page. Use after making non-trivial changes, before reporting work as done.
---

Verify the current working tree. Report what you actually checked, not what you assume passed.

## 1. Tests

```bash
mvn test
```

Whole suite, ~31s. If anything fails, stop and report the failure with the surefire output — do not continue to the boot check.

## 2. Real boot

The suite passing is not proof the app starts: Flyway migrations, Quartz job registration, `AppProperties` validation, and Vaadin's frontend all run only at boot.

```bash
mvn spring-boot:run > /tmp/calsync-verify.log 2>&1 &
```

Poll the log (do not fire-and-forget) until one of:

- `Started CalendarSyncApplication` — boot succeeded, note the startup time
- a stack trace or `APPLICATION FAILED TO START` — boot failed, report the root cause
- ~90s with neither — report as a hang, capture the tail of the log

Then confirm it actually serves:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/login
```

Expect `200`. A `302` to `/login` from `/login` itself means the security config is looping.

Always shut down afterwards:

```bash
pkill -f 'spring-boot:run' || true
```

## 3. What to check in the log

Even on a successful boot, scan for:

- Flyway lines — did a new migration apply, and to the version you expected?
- `Scheduled sync job` / Quartz registration for each connection
- Any `WARN` or `ERROR` that is new relative to a baseline boot

## 4. Report

State plainly: tests passed/failed with counts, whether the app booted, the HTTP status returned, and anything new in the log. If you skipped a step, say so. Do not report "verified" if only step 1 ran.
