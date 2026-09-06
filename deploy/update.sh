#!/usr/bin/env bash
#
# Build and install an update to a running CalendarSync, native or Docker.
#
#   ./deploy/update.sh                  # build, then update whatever is installed
#   ./deploy/update.sh --skip-tests     # build without running the test suite
#   ./deploy/update.sh --no-build       # use the newest jar already in target/
#   ./deploy/update.sh --jar /tmp/x.jar # use a jar built elsewhere
#   ./deploy/update.sh --mode docker
#   ./deploy/update.sh --dry-run
#
# Run it as YOURSELF, not with sudo. It builds as you - so target/ and ~/.m2
# stay yours - and asks for your password once, after the build, for the parts
# that genuinely need root. Running the Maven build as root leaves root-owned
# artifacts that break your next ordinary build.
#
# The installer (install-ubuntu.sh) also replaces a jar and restarts, but it
# does so under a running service, keeps no copy of what it replaced, and never
# checks that the app came back. That was survivable while an upgrade was only
# new code. It stopped being survivable once upgrades started carrying Flyway
# migrations: migrations are forward-only and are validated at startup, so once
# a new schema version has been applied there is no way back to the previous jar
# without the database that matches it.
#
# So this script's real job is the three things the installer does not do: build
# from a known state, copy the database while the service is STOPPED, and put
# both the jar and that copy back if the new version does not come up.
#
set -euo pipefail

APP_USER=calendarsync
APP_DIR=/opt/calendarsync
DATA_DIR=/var/lib/calendarsync
CONF_DIR=/etc/calendarsync
ENV_FILE="$CONF_DIR/calendarsync.env"
UNIT_FILE=/etc/systemd/system/calendarsync.service
BACKUP_ROOT="$DATA_DIR/backups"
KEEP_BACKUPS=5
HEALTH_TIMEOUT=120
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die()  { echo "error: $*" >&2; exit 1; }
note() { echo "==> $*"; }
warn() { echo "warning: $*" >&2; }

MODE=""
JAR=""
FORCE=0
DRY_RUN=0
BUILD=1
SKIP_TESTS=0
COMPOSE_DIR=""

while [ $# -gt 0 ]; do
    case "$1" in
        --mode)       MODE="${2:-}"; shift 2 ;;
        --jar)        JAR="${2:-}"; BUILD=0; shift 2 ;;
        --dir)        COMPOSE_DIR="${2:-}"; shift 2 ;;
        --no-build)   BUILD=0; shift ;;
        --skip-tests) SKIP_TESTS=1; shift ;;
        --force)      FORCE=1; shift ;;
        --dry-run)    DRY_RUN=1; shift ;;
        -h|--help)    awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"; exit 0 ;;
        *)            die "unknown option: $1 (try --help)" ;;
    esac
done

# --- Privileges, acquired only when they are actually needed --------------

SUDO=""
ROOT_READY=0

# Everything that needs root goes through this. Before the first call,
# require_root() has already prompted, so a password prompt can never appear
# half way through a rollback.
as_root() {
    if [ "$ROOT_READY" -eq 0 ]; then
        require_root
    fi
    if [ -z "$SUDO" ]; then
        "$@"
    else
        sudo "$@"
    fi
}

require_root() {
    [ "$ROOT_READY" -eq 1 ] && return 0
    if [ "$(id -u)" -eq 0 ]; then
        SUDO=""
    else
        command -v sudo >/dev/null 2>&1 \
            || die "this needs root and sudo is not installed - re-run as root"
        SUDO="sudo"
        if ! sudo -n true 2>/dev/null; then
            note "the rest of this needs root - you may be asked for your password"
        fi
        sudo -v || die "could not get root privileges"
    fi
    ROOT_READY=1
}

# --- Which kind of install is this? --------------------------------------
# Both probes read world-readable paths, so detection never needs root.

detect_mode() {
    local native=0 docker=0
    [ -f "$APP_DIR/calendarsync.jar" ] && native=1
    # The same probe install-ubuntu.sh uses, in reverse: the two shapes install
    # to the SAME unit name, so the unit's contents are what tells them apart.
    if [ -f "$UNIT_FILE" ] && grep -q 'docker compose' "$UNIT_FILE"; then
        docker=1
        native=0
    fi
    if [ "$docker" -eq 0 ] && [ -f "$REPO_DIR/docker-compose.yml" ] \
       && command -v docker >/dev/null 2>&1 \
       && $DOCKER compose -f "$REPO_DIR/docker-compose.yml" ps --quiet 2>/dev/null | grep -q .; then
        docker=1
    fi

    if [ "$native" -eq 1 ] && [ "$docker" -eq 1 ]; then
        die "found both a native install and a running Compose stack.
       Updating one while the other holds port 8080 - or the same database -
       is not something this script will guess at. Pick one: --mode native or
       --mode docker"
    fi
    [ "$native" -eq 1 ] && { echo native; return; }
    [ "$docker" -eq 1 ] && { echo docker; return; }
    die "no CalendarSync install found ($APP_DIR/calendarsync.jar is missing and no
       Compose stack is running). If it is installed somewhere unusual, pass
       --mode native or --mode docker (with --dir for a Compose project)"
}

# Docker may or may not need root depending on whether this user is in the
# docker group. Asking is better than assuming either way.
DOCKER="docker"
init_docker_command() {
    command -v docker >/dev/null 2>&1 || die "docker is not installed"
    if docker info >/dev/null 2>&1; then
        DOCKER="docker"
    else
        require_root
        DOCKER="${SUDO:+sudo }docker"
        $DOCKER info >/dev/null 2>&1 \
            || die "cannot talk to the Docker daemon, even as root. Is it running?"
        note "using sudo for docker (this user is not in the docker group)"
    fi
}

if command -v docker >/dev/null 2>&1 && ! docker info >/dev/null 2>&1; then
    # Detection must not trip over a permission error and conclude "no stack".
    DOCKER="true"
fi

if [ -z "$MODE" ]; then
    MODE="$(detect_mode)"
    note "detected a $MODE install"
else
    case "$MODE" in
        native|docker) note "using --mode $MODE" ;;
        *) die "--mode must be 'native' or 'docker'" ;;
    esac
fi

# --- Build ----------------------------------------------------------------

# Deliberately NOT run through sudo. A root-owned target/ and ~/.m2 break the
# next ordinary build, and the resulting permission errors are a long way from
# their cause. If the script was started with sudo we hand the build back to
# the invoking user; a genuine root login gets a warning instead, because there
# is no unprivileged user to hand it to.
build_jar() {
    command -v mvn >/dev/null 2>&1 \
        || die "mvn is not on PATH. Install Maven, or build elsewhere and pass --jar"

    local -a maven=(mvn -B -Pprod package)
    [ "$SKIP_TESTS" -eq 1 ] && maven+=(-DskipTests)

    if [ "$(id -u)" -eq 0 ] && [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != "root" ]; then
        note "building as $SUDO_USER (not root, so target/ and ~/.m2 stay theirs)"
        maven=(sudo -u "$SUDO_USER" -H "${maven[@]}")
    elif [ "$(id -u)" -eq 0 ]; then
        warn "building as root - target/ and ~/.m2 artifacts will be root-owned"
    fi

    if [ "$SKIP_TESTS" -eq 1 ]; then
        note "building (tests skipped)"
    else
        note "building and running the tests"
    fi
    local log
    log="$(mktemp)"
    if ! (cd "$REPO_DIR" && "${maven[@]}") 2>&1 | tee "$log"; then
        rm -f "$log"
        die "the build failed - nothing has been changed"
    fi

    # NOTES.md records that vaadin-maven-plugin silently substitutes its own
    # Node when the one on PATH is outside the range it supports - which is a
    # floor AND a ceiling, so a too-NEW Node triggers it as readily as an old
    # one. Worth surfacing: the first time it happens it fetches a Node
    # distribution over the network in the middle of a build.
    if grep -q 'nodejs.org/dist' "$log"; then
        warn "Vaadin downloaded its own Node during this build - the Node on PATH is
         outside the range it supports, so the pinned version was ignored."
    elif grep -qi 'Using Node.js from' "$log"; then
        warn "Vaadin used its own cached Node rather than the one on PATH
         ($(node --version 2>/dev/null || echo 'unknown') is outside the range it supports)."
    fi
    rm -f "$log"

    JAR="$(newest_jar)"
    [ -n "$JAR" ] || die "the build reported success but produced no jar in $REPO_DIR/target"
    note "built $(basename "$JAR")"
}

newest_jar() {
    ls -t "$REPO_DIR"/target/calendarsync-*.jar 2>/dev/null | grep -v -- '-sources' | head -1 || true
}

# The jar must be a -Pprod build. Identical check to install-ubuntu.sh, and the
# most important gate in either script: a -Pdev jar carries Xerial's driver,
# which does not understand the ?key= parameter the prod profile appends and
# does not reject it either. It cannot open an existing encrypted database, and
# what it does instead is create a PLAINTEXT one in a file whose name contains
# the database key.
verify_prod_jar() {
    local jar="$1" rc
    set +e
    python3 - "$jar" <<'PY'
import io, sys, zipfile
outer = zipfile.ZipFile(sys.argv[1])
nested = [n for n in outer.namelist() if "/sqlite-jdbc-" in n and n.endswith(".jar")]
if not nested:
    sys.exit(2)
inner = zipfile.ZipFile(io.BytesIO(outer.read(nested[0])))
sys.exit(0 if any(n.startswith("org/sqlite/mc/") for n in inner.namelist()) else 1)
PY
    rc=$?
    set -e
    case "$rc" in
        0) note "jar is a -Pprod build" ;;
        1) die "$jar was built with the dev Maven profile (Xerial SQLite driver).
       Under SPRING_PROFILES_ACTIVE=prod that driver does not encrypt anything:
       it writes a PLAINTEXT database to a filename containing your database
       key. Rebuild it: mvn -Pprod package" ;;
        2) warn "could not find a SQLite driver inside $jar - continuing, but verify it is a -Pprod build" ;;
        *) warn "jar profile check did not run (python3 missing?) - verify manually that this is a -Pprod build" ;;
    esac
}

# Reads a KEY=value out of the systemd environment file. Not sourced: systemd
# parses that file as plain KEY=value, NOT as shell, so sourcing it would
# execute anything a value happened to look like. Needs root - the file is 0600
# because it holds the database key.
env_value() {
    local key="$1" default="${2:-}" value
    as_root test -f "$ENV_FILE" || { echo "$default"; return; }
    value="$(as_root grep -E "^${key}=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true)"
    value="${value%\"}"; value="${value#\"}"
    [ -n "$value" ] && echo "$value" || echo "$default"
}

# systemctl reports the unit active the moment the JVM execs (Type=exec), which
# is long before Flyway has migrated and Tomcat is listening - and a migration
# failure exits AFTER that point. So health is measured by asking the app for a
# page. /login is the only route that answers 200 with no credentials, so a 200
# proves the context started, Flyway succeeded and Vaadin is serving.
wait_for_health() {
    local url="$1" deadline=$((SECONDS + HEALTH_TIMEOUT)) code
    note "waiting for $url to answer (up to ${HEALTH_TIMEOUT}s)"
    while [ $SECONDS -lt $deadline ]; do
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$url" 2>/dev/null || true)"
        if [ "$code" = "200" ]; then
            note "healthy"
            return 0
        fi
        sleep 2
    done
    warn "no healthy response from $url within ${HEALTH_TIMEOUT}s (last status: ${code:-none})"
    return 1
}

prune_backups() {
    local root="$1"
    as_root test -d "$root" || return 0
    as_root sh -c "ls -1dt '$root'/* 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r rm -rf"
}

# =========================================================================
# Native
# =========================================================================

update_native() {
    [ -f "$APP_DIR/calendarsync.jar" ] || die "no jar at $APP_DIR/calendarsync.jar - is this a native install?"
    [ -f "$UNIT_FILE" ] || die "no $UNIT_FILE - install it first with deploy/install-ubuntu.sh"
    command -v curl >/dev/null 2>&1 || die "curl is required for the post-update health check"

    if [ "$DRY_RUN" -eq 1 ]; then
        cat <<EOF

Dry run - nothing will be changed.

  build     $([ "$BUILD" -eq 1 ] && echo "mvn -B -Pprod package$([ "$SKIP_TESTS" -eq 1 ] && echo ' -DskipTests')" || echo "skipped")
  install   ${JAR:-$(newest_jar)}
            -> $APP_DIR/calendarsync.jar
  backup    $BACKUP_ROOT/<timestamp>/  (database + current jar)
  health    http://<SERVER_ADDRESS>:8080/login
  root      needed for: reading $ENV_FILE, systemctl, the backup and the install
EOF
        exit 0
    fi

    # Build BEFORE asking for root: the build is the slow part, and a sudo
    # timestamp acquired first could expire in the middle of it.
    if [ "$BUILD" -eq 1 ]; then
        build_jar
    else
        [ -n "$JAR" ] || JAR="$(newest_jar)"
    fi
    [ -n "$JAR" ] && [ -f "$JAR" ] \
        || die "no jar found. Build one (drop --no-build), or pass --jar <path>"

    verify_prod_jar "$JAR"

    local new_sum old_sum
    new_sum="$(sha256sum "$JAR" | cut -d' ' -f1)"
    old_sum="$(sha256sum "$APP_DIR/calendarsync.jar" | cut -d' ' -f1)"
    if [ "$new_sum" = "$old_sum" ] && [ "$FORCE" -eq 0 ]; then
        note "the installed jar is already byte-identical to $(basename "$JAR") - nothing to do (use --force to reinstall anyway)"
        exit 0
    fi

    require_root

    local db_path server_address health_url
    db_path="$(env_value CALENDARSYNC_DB_PATH "$DATA_DIR/calendarsync.db")"
    server_address="$(env_value SERVER_ADDRESS 127.0.0.1)"
    # SERVER_ADDRESS may legitimately be a wildcard or unset, in which case the
    # app is listening on loopback too.
    case "$server_address" in ""|"0.0.0.0"|"*"|"::") server_address=127.0.0.1 ;; esac
    health_url="http://${server_address}:8080/login"

    local stamp backup_dir
    stamp="$(date -u +%Y%m%dT%H%M%SZ)"
    backup_dir="$BACKUP_ROOT/$stamp"

    note "stopping calendarsync"
    as_root systemctl stop calendarsync.service || true
    # Genuinely stopped before copying: a SQLite file copied out from under a
    # running writer can be torn, and a torn backup is worse than none because
    # it is only discovered when it is needed.
    local deadline=$((SECONDS + 60))
    while as_root systemctl is-active --quiet calendarsync.service; do
        [ $SECONDS -lt $deadline ] || die "calendarsync did not stop within 60s - not touching anything"
        sleep 1
    done

    note "backing up to $backup_dir"
    as_root install -d -o root -g root -m 0700 "$BACKUP_ROOT"
    as_root install -d -o root -g root -m 0700 "$backup_dir"

    if as_root test -f "$db_path"; then
        # Free space first: a backup that runs out of disk half way through is
        # the worst of both worlds.
        local need avail
        need="$(as_root du -k "$db_path" | cut -f1)"
        avail="$(as_root df -Pk "$backup_dir" | awk 'NR==2 {print $4}')"
        [ "$avail" -gt $((need * 2)) ] \
            || die "not enough free space for a database backup (need ~$((need * 2))K, have ${avail}K)"

        # A plain copy, NOT sqlite3 .backup: under the prod profile the file is
        # encrypted by the Willena driver, so sqlite3 cannot open it without the
        # key - and the key must not be put on a command line. The -wal and -shm
        # siblings are copied when present; a clean shutdown checkpoints them,
        # but copying them costs nothing and an unclean one would need them.
        as_root cp -p "$db_path" "$backup_dir/"
        for sidecar in "$db_path-wal" "$db_path-shm"; do
            as_root test -f "$sidecar" && as_root cp -p "$sidecar" "$backup_dir/"
        done
        note "database backed up ($(as_root du -h "$db_path" | cut -f1))"
    else
        warn "no database at $db_path - continuing (first start after an install?)"
    fi

    as_root cp -p "$APP_DIR/calendarsync.jar" "$backup_dir/calendarsync.jar"
    as_root sh -c "printf '%s  calendarsync.jar\n' '$old_sum' > '$backup_dir/SHA256SUMS'"
    as_root sh -c "printf '%s\n' '$db_path' > '$backup_dir/db-path'"

    note "installing $(basename "$JAR")"
    as_root install -o root -g root -m 0644 "$JAR" "$APP_DIR/calendarsync.jar"

    note "starting calendarsync"
    as_root systemctl start calendarsync.service || true

    if wait_for_health "$health_url"; then
        prune_backups "$BACKUP_ROOT"
        note "update complete. Backup kept at $backup_dir"
        note "that backup contains your database - it is as sensitive as the live one"
        as_root systemctl --no-pager --lines=0 status calendarsync.service || true
        exit 0
    fi

    echo >&2
    warn "the new version did not come up - rolling back"
    echo "--- last 50 log lines ---" >&2
    as_root journalctl -u calendarsync.service -n 50 --no-pager >&2 || true
    echo "-------------------------" >&2

    rollback_native "$backup_dir" "$db_path" "$health_url"
}

# The jar and the database go back TOGETHER, always.
#
# Flyway is forward-only and validates at startup. If the new version applied a
# migration and then failed for any other reason, restoring only the jar leaves
# a database at a schema version the old jar has no migration for, and it
# refuses to start with "detected applied migration not resolved locally". The
# rollback would look like it had worked and the service would fail on its next
# restart instead - the worst possible time to discover it.
rollback_native() {
    local backup_dir="$1" db_path="$2" health_url="$3"

    as_root systemctl stop calendarsync.service || true
    sleep 2

    as_root install -o root -g root -m 0644 "$backup_dir/calendarsync.jar" "$APP_DIR/calendarsync.jar"
    note "restored the previous jar"

    local db_name
    db_name="$(basename "$db_path")"
    if as_root test -f "$backup_dir/$db_name"; then
        as_root cp -p "$backup_dir/$db_name" "$db_path"
        for suffix in -wal -shm; do
            if as_root test -f "$backup_dir/${db_name}${suffix}"; then
                as_root cp -p "$backup_dir/${db_name}${suffix}" "${db_path}${suffix}"
            else
                as_root rm -f "${db_path}${suffix}"
            fi
        done
        as_root chown "$APP_USER:$APP_USER" "$db_path" || true
        note "restored the database as it was before the update"
    fi

    as_root systemctl start calendarsync.service || true
    if wait_for_health "$health_url"; then
        die "the update failed and was rolled back. The previous version is running again.
       The jar you built is untouched; the logs above say why it would not start."
    fi
    die "the update failed AND the rollback did not come up. Backup is at $backup_dir
       (jar, database, and the path it came from). Investigate before starting again:
         sudo journalctl -u calendarsync -n 200"
}

# =========================================================================
# Docker
# =========================================================================

update_docker() {
    init_docker_command
    command -v curl >/dev/null 2>&1 || die "curl is required for the post-update health check"

    local dir="${COMPOSE_DIR:-$REPO_DIR}"
    [ -f "$dir/docker-compose.yml" ] || die "no docker-compose.yml in $dir (pass --dir)"
    note "using the Compose project in $dir"

    local service=calendarsync container image volume
    container="$($DOCKER compose -f "$dir/docker-compose.yml" ps -q "$service" 2>/dev/null | head -1 || true)"
    [ -n "$container" ] || die "the $service container is not running in $dir - start it first with: docker compose up -d"

    image="$($DOCKER inspect --format '{{.Image}}' "$container")"
    volume="$($DOCKER inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$container")"
    [ -n "$volume" ] || die "could not find the /data volume for $service - refusing to update without a backup"

    local port_spec health_url
    port_spec="$($DOCKER compose -f "$dir/docker-compose.yml" port "$service" 8080 2>/dev/null || true)"
    if [ -n "$port_spec" ]; then
        local host="${port_spec%:*}" hostport="${port_spec##*:}"
        case "$host" in ""|"0.0.0.0"|"::"|"[::]") host=127.0.0.1 ;; esac
        health_url="http://${host}:${hostport}/login"
    else
        health_url="http://127.0.0.1:8080/login"
    fi

    local stamp backup_dir
    stamp="$(date -u +%Y%m%dT%H%M%SZ)"
    backup_dir="$dir/backups/$stamp"

    if [ "$DRY_RUN" -eq 1 ]; then
        cat <<EOF

Dry run - nothing will be changed.

  project   $dir
  image     $image  (would be tagged as the rollback point)
  volume    $volume
  backup    $backup_dir/data.tgz
  health    $health_url

  The image is rebuilt from this working tree by Compose, so --skip-tests and
  --no-build do not apply here - the Dockerfile's own build stage runs.
EOF
        exit 0
    fi

    note "tagging the current image as the rollback point"
    $DOCKER tag "$image" "calendarsync:rollback-$stamp"

    note "stopping the stack"
    $DOCKER compose -f "$dir/docker-compose.yml" stop

    note "backing up the $volume volume to $backup_dir"
    mkdir -p "$backup_dir"
    chmod 0700 "$backup_dir"
    # tar runs inside the app's OWN image rather than pulling alpine: it is
    # known to be present (it is what is being replaced), which keeps this
    # working on a host with no network, and avoids trusting a second image.
    $DOCKER run --rm \
        -v "$volume":/data:ro \
        -v "$backup_dir":/backup \
        --entrypoint tar "$image" -czf /backup/data.tgz -C /data . \
        || die "volume backup failed - not updating"
    note "volume backed up ($(du -h "$backup_dir/data.tgz" | cut -f1))"

    note "building the new image"
    if ! $DOCKER compose -f "$dir/docker-compose.yml" build --pull; then
        warn "build failed - bringing the previous version back up"
        $DOCKER compose -f "$dir/docker-compose.yml" up -d
        die "the image would not build. Nothing was replaced; the previous version is running."
    fi

    note "starting the new version"
    $DOCKER compose -f "$dir/docker-compose.yml" up -d --remove-orphans

    if wait_for_health "$health_url"; then
        note "update complete. Backup kept at $backup_dir/data.tgz"
        note "rollback image kept as calendarsync:rollback-$stamp"
        exit 0
    fi

    echo >&2
    warn "the new version did not come up - rolling back"
    $DOCKER compose -f "$dir/docker-compose.yml" logs --tail 50 "$service" >&2 || true

    $DOCKER compose -f "$dir/docker-compose.yml" stop
    note "restoring the volume"
    # Emptied first: untarring over a newer layout leaves both, and a database
    # migrated forward would still be sitting there.
    $DOCKER run --rm -v "$volume":/data --entrypoint sh "calendarsync:rollback-$stamp" \
        -c 'rm -rf /data/* /data/.[!.]* 2>/dev/null; true'
    $DOCKER run --rm -v "$volume":/data -v "$backup_dir":/backup \
        --entrypoint tar "calendarsync:rollback-$stamp" -xzf /backup/data.tgz -C /data \
        || die "could not restore the volume. The backup is at $backup_dir/data.tgz"

    note "re-tagging the previous image"
    $DOCKER tag "calendarsync:rollback-$stamp" "$image"
    $DOCKER compose -f "$dir/docker-compose.yml" up -d

    if wait_for_health "$health_url"; then
        die "the update failed and was rolled back. The previous version is running again."
    fi
    die "the update failed AND the rollback did not come up.
       Volume backup: $backup_dir/data.tgz
       Previous image: calendarsync:rollback-$stamp
         docker compose -f $dir/docker-compose.yml logs"
}

case "$MODE" in
    native) update_native ;;
    docker) update_docker ;;
esac
