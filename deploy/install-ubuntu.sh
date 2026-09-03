#!/usr/bin/env bash
#
# Native (no Docker) install of CalendarSync on Ubuntu.
#
# Installs a jar you have already built, as a systemd service that starts at
# boot. Deliberately does NOT install packages or add apt repositories: it
# checks for a suitable Java and prints the command for this release rather
# than adding a repository behind the operator's back, which is the same
# reasoning that removed the piped-curl NodeSource install from the Dockerfile.
#
#   sudo ./deploy/install-ubuntu.sh [path/to/calendarsync.jar]
#
# Re-running it is the upgrade path: the jar is replaced and the service
# restarted, while the environment file - which holds the only copy of the
# database key - is never touched once it exists.
#
# Layout:
#   /opt/calendarsync/calendarsync.jar    root-owned, read-only to the service
#   /var/lib/calendarsync/                database and logs, mode 0750
#   /etc/calendarsync/calendarsync.env    config incl. the DB key, mode 0600 root
#   /etc/systemd/system/calendarsync.service
set -euo pipefail

APP_USER=calendarsync
APP_DIR=/opt/calendarsync
DATA_DIR=/var/lib/calendarsync
CONF_DIR=/etc/calendarsync
ENV_FILE="$CONF_DIR/calendarsync.env"
UNIT_FILE=/etc/systemd/system/calendarsync.service
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die()  { echo "error: $*" >&2; exit 1; }
note() { echo "==> $*"; }
warn() { echo "warning: $*" >&2; }

[ "$(id -u)" -eq 0 ] || die "must run as root: sudo $0 $*"

# --- The jar -------------------------------------------------------------

JAR="${1:-}"
if [ -z "$JAR" ]; then
    JAR="$(ls -t "$REPO_DIR"/target/calendarsync-*.jar 2>/dev/null | grep -v -- '-sources' | head -1 || true)"
fi
[ -n "$JAR" ] && [ -f "$JAR" ] \
    || die "no jar found. Build one first: mvn -Pprod package, or pass the path as an argument"

# Refuse a jar built with the WRONG Maven profile, because the failure is
# silent and does double damage. A -Pdev jar carries Xerial's SQLite driver,
# which does not understand the ?key= parameter the prod profile appends to the
# JDBC URL - and does not reject it either. Measured, not guessed: the app
# starts normally, logs nothing unusual, and Xerial takes the whole URL as a
# path, leaving a plaintext "calendarsync.db?key=<the key>" on disk. No
# encryption, and the key in a filename. Willena's driver is the one that
# implements encryption, and its jar is the only one carrying org/sqlite/mc/.
note "checking the jar was built with -Pprod"
set +e
python3 - "$JAR" <<'PY'
import io, sys, zipfile
outer = zipfile.ZipFile(sys.argv[1])
nested = [n for n in outer.namelist() if "/sqlite-jdbc-" in n and n.endswith(".jar")]
if not nested:
    sys.exit(2)
inner = zipfile.ZipFile(io.BytesIO(outer.read(nested[0])))
sys.exit(0 if any(n.startswith("org/sqlite/mc/") for n in inner.namelist()) else 1)
PY
jar_check=$?
set -e
case "$jar_check" in
    0) : ;;
    1) die "$JAR was built with the dev Maven profile (Xerial SQLite driver).
       Under SPRING_PROFILES_ACTIVE=prod that driver does not encrypt anything:
       it writes a PLAINTEXT database to a filename containing your database
       key. Rebuild it: mvn -Pprod package" ;;
    2) warn "could not find a SQLite driver inside $JAR - continuing, but verify it is a -Pprod build" ;;
    *) warn "jar profile check did not run (python3 missing?) - verify manually that this is a -Pprod build" ;;
esac

# --- Java ----------------------------------------------------------------

if ! command -v java >/dev/null 2>&1; then
    java_major=0
    java_found=none
else
    # "java -version" writes to stderr, and the version line differs between
    # vendors; the first dotted number on it is the one that matters.
    java_major="$(java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
    [[ "$java_major" =~ ^[0-9]+$ ]] || java_major=0
    java_found="$java_major"
fi

if [ "$java_major" -lt 25 ]; then
    echo >&2
    echo "error: Java 25 or newer is required (found: $java_found)." >&2
    if apt-cache policy openjdk-25-jre-headless 2>/dev/null | grep -q 'Candidate: [0-9]'; then
        # Ubuntu 26.04 LTS ships OpenJDK 25 as its default JDK, so no external
        # repository is needed there.
        echo "  sudo apt install openjdk-25-jre-headless" >&2
    else
        echo "  This Ubuntu release has no openjdk-25 package. Use Eclipse Temurin:" >&2
        echo "    sudo apt install -y wget apt-transport-https" >&2
        echo "    sudo mkdir -p /etc/apt/keyrings" >&2
        echo "    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \\" >&2
        echo "      | sudo tee /etc/apt/keyrings/adoptium.asc > /dev/null" >&2
        echo "    echo \"deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb \\" >&2
        echo "      \$(awk -F= '/^VERSION_CODENAME/{print\$2}' /etc/os-release) main\" \\" >&2
        echo "      | sudo tee /etc/apt/sources.list.d/adoptium.list" >&2
        echo "    sudo apt update && sudo apt install temurin-25-jre" >&2
    fi
    exit 1
fi
note "java $java_major found at $(command -v java)"

# --- Refuse to collide with the Compose unit -----------------------------

if [ -f "$UNIT_FILE" ] && grep -q 'docker compose' "$UNIT_FILE"; then
    die "$UNIT_FILE is the Docker Compose unit (deploy/calendarsync-compose.service).
       One host runs one of the two, never both - two copies of the app would
       contend for port 8080 and, if their paths ever met, for one SQLite file.
       Remove the Compose install first:
         systemctl disable --now calendarsync.service && docker compose down"
fi

# --- User, directories, jar ---------------------------------------------

if ! id -u "$APP_USER" >/dev/null 2>&1; then
    note "creating system user $APP_USER"
    useradd --system --home-dir "$DATA_DIR" --no-create-home \
            --shell /usr/sbin/nologin "$APP_USER"
fi

install -d -o root      -g root      -m 0755 "$APP_DIR"
install -d -o "$APP_USER" -g "$APP_USER" -m 0750 "$DATA_DIR"
install -d -o root      -g root      -m 0750 "$CONF_DIR"

# root-owned and not writable by the service account: a compromise of the app
# should not be able to rewrite the code that runs as it on next boot.
note "installing $(basename "$JAR") to $APP_DIR/calendarsync.jar"
install -o root -g root -m 0644 "$JAR" "$APP_DIR/calendarsync.jar"

# --- Environment file ----------------------------------------------------

fresh_env=0
if [ -e "$ENV_FILE" ]; then
    note "keeping existing $ENV_FILE (it holds the only copy of the database key)"
else
    fresh_env=1
    if command -v openssl >/dev/null 2>&1; then
        db_key="$(openssl rand -base64 32)"
    else
        db_key="$(head -c 32 /dev/urandom | base64 -w0)"
    fi
    # base64 output cannot contain '|' or '&', so neither the delimiter nor
    # sed's replacement metacharacter can be hit by the generated key.
    umask 077
    sed "s|^CALCLEANER_DB_KEY=.*|CALCLEANER_DB_KEY=${db_key}|" \
        "$REPO_DIR/deploy/calendarsync.env.example" > "$ENV_FILE"
    chown root:root "$ENV_FILE"
    chmod 0600 "$ENV_FILE"
    note "wrote $ENV_FILE with a freshly generated CALCLEANER_DB_KEY"
fi

# --- Unit ----------------------------------------------------------------

note "installing $UNIT_FILE"
install -o root -g root -m 0644 "$REPO_DIR/deploy/calendarsync-native.service" "$UNIT_FILE"
systemctl daemon-reload
systemctl enable calendarsync.service >/dev/null
note "enabled at boot"

# Starting with the shipped placeholder base URL would embed
# https://calendar.example.com in every feed link generated before it is
# corrected, so a fresh install stops here and asks.
if [ "$fresh_env" -eq 1 ] || grep -q 'calendar\.example\.com' "$ENV_FILE"; then
    cat <<EOF

Installed, but NOT started - configuration is still the example one.

  1. sudo nano $ENV_FILE
       CALENDARSYNC_BASE_URL   the HTTPS URL your reverse proxy serves
       SERVER_ADDRESS          127.0.0.1 unless the proxy is on another host
       CALENDARSYNC_TRUSTED_PROXIES   narrow it to the proxy's address
  2. Back up CALCLEANER_DB_KEY from that file, off this machine. Losing it
     means losing every encrypted row; there is no recovery.
  3. sudo systemctl start calendarsync
  4. sudo journalctl -u calendarsync -f    # the first-run admin password is
                                           # printed here, once
EOF
else
    note "restarting calendarsync"
    systemctl restart calendarsync.service
    systemctl --no-pager --lines=0 status calendarsync.service || true
fi
