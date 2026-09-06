#!/usr/bin/env bash
#
# Update an existing CalendarSync install, native or Docker.
#
# The installer (install-ubuntu.sh) doubles as an upgrade in that it replaces
# the jar and restarts - but it does so under a running service, keeps no copy
# of what it replaced, and never checks that the app came back. That was
# survivable while an upgrade was only new code. It stopped being survivable
# once upgrades started carrying Flyway migrations: migrations are forward-only
# and are validated at startup, so once a new schema version has been applied
# there is no way back to the previous jar without the database that matches
# it.
#
# So this script's real job is the two things the installer does not do: take a
# copy of the database while the service is STOPPED, and put both the jar and
# that copy back if the new version does not come up.
#
#   sudo ./deploy/update.sh                    # auto-detect, newest jar in target/
#   sudo ./deploy/update.sh --jar /tmp/new.jar
#   sudo ./deploy/update.sh --mode docker
#   sudo ./deploy/update.sh --dry-run
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
COMPOSE_DIR=""

while [ $# -gt 0 ]; do
    case "$1" in
        --mode)    MODE="${2:-}"; shift 2 ;;
        --jar)     JAR="${2:-}"; shift 2 ;;
        --dir)     COMPOSE_DIR="${2:-}"; shift 2 ;;
        --force)   FORCE=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print; next} NR>1 {exit}' "$0"; exit 0 ;;
        *)         die "unknown option: $1 (try --help)" ;;
    esac
done

[ "$(id -u)" -eq 0 ] || die "must run as root: sudo $0 $*"

# --- Which kind of install is this? --------------------------------------

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
       && docker compose -f "$REPO_DIR/docker-compose.yml" ps --quiet 2>/dev/null | grep -q .; then
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

if [ -z "$MODE" ]; then
    MODE="$(detect_mode)"
    note "detected a $MODE install"
else
    case "$MODE" in
        native|docker) note "using --mode $MODE" ;;
        *) die "--mode must be 'native' or 'docker'" ;;
    esac
fi

# --- Shared helpers -------------------------------------------------------

# Reads a KEY=value out of the systemd environment file. Not sourced: systemd
# parses that file as plain KEY=value, NOT as shell, so sourcing it would
# execute anything a value happened to look like.
env_value() {
    local key="$1" default="${2:-}" value
    [ -f "$ENV_FILE" ] || { echo "$default"; return; }
    value="$(grep -E "^${key}=" "$ENV_FILE" | tail -1 | cut -d= -f2- || true)"
    value="${value%\"}"; value="${value#\"}"
    [ -n "$value" ] && echo "$value" || echo "$default"
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
    [ -d "$root" ] || return 0
    # shellcheck disable=SC2012
    ls -1dt "$root"/* 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | while read -r old; do
        note "removing old backup $(basename "$old")"
        rm -rf "$old"
    done
}

# =========================================================================
# Native
# =========================================================================

update_native() {
    [ -f "$APP_DIR/calendarsync.jar" ] || die "no jar at $APP_DIR/calendarsync.jar - is this a native install?"
    [ -f "$UNIT_FILE" ] || die "no $UNIT_FILE - install it first with deploy/install-ubuntu.sh"
    command -v curl >/dev/null 2>&1 || die "curl is required for the post-update health check"

    if [ -z "$JAR" ]; then
        JAR="$(ls -t "$REPO_DIR"/target/calendarsync-*.jar 2>/dev/null | grep -v -- '-sources' | head -1 || true)"
    fi
    [ -n "$JAR" ] && [ -f "$JAR" ] \
        || die "no jar found. Build one first: mvn -Pprod package, or pass --jar <path>"

    verify_prod_jar "$JAR"

    local new_sum old_sum
    new_sum="$(sha256sum "$JAR" | cut -d' ' -f1)"
    old_sum="$(sha256sum "$APP_DIR/calendarsync.jar" | cut -d' ' -f1)"
    if [ "$new_sum" = "$old_sum" ] && [ "$FORCE" -eq 0 ]; then
        note "the installed jar is already byte-identical to $JAR - nothing to do (use --force to reinstall anyway)"
        exit 0
    fi

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

    if [ "$DRY_RUN" -eq 1 ]; then
        cat <<EOF

Dry run - nothing has been changed.

  install   $JAR
            -> $APP_DIR/calendarsync.jar
  database  $db_path
  backup    $backup_dir
  health    $health_url
EOF
        exit 0
    fi

    note "stopping calendarsync"
    systemctl stop calendarsync.service || true
    # Genuinely stopped before copying: a SQLite file copied out from under a
    # running writer can be torn, and a torn backup is worse than none because
    # it is only discovered when it is needed.
    local deadline=$((SECONDS + 60))
    while systemctl is-active --quiet calendarsync.service; do
        [ $SECONDS -lt $deadline ] || die "calendarsync did not stop within 60s - not touching anything"
        sleep 1
    done

    note "backing up to $backup_dir"
    install -d -o root -g root -m 0700 "$BACKUP_ROOT" "$backup_dir"

    if [ -f "$db_path" ]; then
        # Free space first: a backup that runs out of disk half way through is
        # the worst of both worlds.
        local need avail
        need="$(du -k "$db_path" | cut -f1)"
        avail="$(df -Pk "$backup_dir" | awk 'NR==2 {print $4}')"
        [ "$avail" -gt $((need * 2)) ] \
            || die "not enough free space for a database backup (need ~$((need * 2))K, have ${avail}K)"

        # A plain copy, NOT sqlite3 .backup: under the prod profile the file is
        # encrypted by the Willena driver, so sqlite3 cannot open it without the
        # key - and the key must not be put on a command line. The -wal and -shm
        # siblings are copied when present; a clean shutdown checkpoints them,
        # but copying them costs nothing and an unclean one would need them.
        cp -p "$db_path" "$backup_dir/"
        for sidecar in "$db_path-wal" "$db_path-shm"; do
            [ -f "$sidecar" ] && cp -p "$sidecar" "$backup_dir/"
        done
        note "database backed up ($(du -h "$db_path" | cut -f1))"
    else
        warn "no database at $db_path - continuing (first start after an install?)"
    fi

    cp -p "$APP_DIR/calendarsync.jar" "$backup_dir/calendarsync.jar"
    echo "$old_sum  calendarsync.jar" > "$backup_dir/SHA256SUMS"
    echo "$db_path" > "$backup_dir/db-path"

    note "installing $(basename "$JAR")"
    install -o root -g root -m 0644 "$JAR" "$APP_DIR/calendarsync.jar"

    note "starting calendarsync"
    systemctl start calendarsync.service || true

    if wait_for_health "$health_url"; then
        prune_backups "$BACKUP_ROOT"
        note "update complete. Backup kept at $backup_dir"
        note "that backup contains your database - it is as sensitive as the live one"
        systemctl --no-pager --lines=0 status calendarsync.service || true
        exit 0
    fi

    echo >&2
    warn "the new version did not come up - rolling back"
    echo "--- last 50 log lines ---" >&2
    journalctl -u calendarsync.service -n 50 --no-pager >&2 || true
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

    systemctl stop calendarsync.service || true
    sleep 2

    install -o root -g root -m 0644 "$backup_dir/calendarsync.jar" "$APP_DIR/calendarsync.jar"
    note "restored the previous jar"

    local db_name
    db_name="$(basename "$db_path")"
    if [ -f "$backup_dir/$db_name" ]; then
        cp -p "$backup_dir/$db_name" "$db_path"
        for suffix in -wal -shm; do
            if [ -f "$backup_dir/${db_name}${suffix}" ]; then
                cp -p "$backup_dir/${db_name}${suffix}" "${db_path}${suffix}"
            else
                rm -f "${db_path}${suffix}"
            fi
        done
        chown "$APP_USER:$APP_USER" "$db_path" "${db_path}-wal" "${db_path}-shm" 2>/dev/null || true
        note "restored the database as it was before the update"
    fi

    systemctl start calendarsync.service || true
    if wait_for_health "$health_url"; then
        die "the update failed and was rolled back. The previous version is running again.
       The jar you tried is untouched; the logs above say why it would not start."
    fi
    die "the update failed AND the rollback did not come up. Backup is at $backup_dir
       (jar, database, and the path it came from). Investigate before starting again:
         journalctl -u calendarsync -n 200"
}

# =========================================================================
# Docker
# =========================================================================

update_docker() {
    command -v docker >/dev/null 2>&1 || die "docker is not installed"
    command -v curl >/dev/null 2>&1 || die "curl is required for the post-update health check"

    local dir="${COMPOSE_DIR:-$REPO_DIR}"
    [ -f "$dir/docker-compose.yml" ] || die "no docker-compose.yml in $dir (pass --dir)"
    note "using the Compose project in $dir"

    local service=calendarsync container image volume
    container="$(docker compose -f "$dir/docker-compose.yml" ps -q "$service" 2>/dev/null | head -1 || true)"
    [ -n "$container" ] || die "the $service container is not running in $dir - start it first with: docker compose up -d"

    image="$(docker inspect --format '{{.Image}}' "$container")"
    volume="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$container")"
    [ -n "$volume" ] || die "could not find the /data volume for $service - refusing to update without a backup"

    local port_spec health_url
    port_spec="$(docker compose -f "$dir/docker-compose.yml" port "$service" 8080 2>/dev/null || true)"
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

Dry run - nothing has been changed.

  project   $dir
  image     $image  (tagged as the rollback point)
  volume    $volume
  backup    $backup_dir/data.tgz
  health    $health_url
EOF
        exit 0
    fi

    note "tagging the current image as the rollback point"
    docker tag "$image" "calendarsync:rollback-$stamp"

    note "stopping the stack"
    docker compose -f "$dir/docker-compose.yml" stop

    note "backing up the $volume volume to $backup_dir"
    mkdir -p "$backup_dir"
    chmod 0700 "$backup_dir"
    # tar runs inside the app's OWN image rather than pulling alpine: it is
    # known to be present (it is what is being replaced), which keeps this
    # working on a host with no network, and avoids trusting a second image.
    docker run --rm \
        -v "$volume":/data:ro \
        -v "$backup_dir":/backup \
        --entrypoint tar "$image" -czf /backup/data.tgz -C /data . \
        || die "volume backup failed - not updating"
    note "volume backed up ($(du -h "$backup_dir/data.tgz" | cut -f1))"

    note "building the new image"
    if ! docker compose -f "$dir/docker-compose.yml" build --pull; then
        warn "build failed - bringing the previous version back up"
        docker compose -f "$dir/docker-compose.yml" up -d
        die "the image would not build. Nothing was replaced; the previous version is running."
    fi

    note "starting the new version"
    docker compose -f "$dir/docker-compose.yml" up -d --remove-orphans

    if wait_for_health "$health_url"; then
        prune_backups "$dir/backups"
        note "update complete. Backup kept at $backup_dir/data.tgz"
        note "rollback image kept as calendarsync:rollback-$stamp"
        exit 0
    fi

    echo >&2
    warn "the new version did not come up - rolling back"
    docker compose -f "$dir/docker-compose.yml" logs --tail 50 "$service" >&2 || true

    docker compose -f "$dir/docker-compose.yml" stop
    note "restoring the volume"
    # Emptied first: untarring over a newer layout leaves both, and a database
    # migrated forward would still be sitting there.
    docker run --rm -v "$volume":/data --entrypoint sh "calendarsync:rollback-$stamp" \
        -c 'rm -rf /data/* /data/.[!.]* 2>/dev/null; true'
    docker run --rm -v "$volume":/data -v "$backup_dir":/backup \
        --entrypoint tar "calendarsync:rollback-$stamp" -xzf /backup/data.tgz -C /data \
        || die "could not restore the volume. The backup is at $backup_dir/data.tgz"

    note "re-tagging the previous image"
    docker tag "calendarsync:rollback-$stamp" "$image"
    docker compose -f "$dir/docker-compose.yml" up -d

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
