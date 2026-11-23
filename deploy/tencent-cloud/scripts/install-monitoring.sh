#!/usr/bin/env bash
# One-time, idempotent installation of the loopback-only Prometheus + Grafana stack (FR-001a, SC-009).
#
# Usage: sudo bash install-monitoring.sh --dist-dir <dir with both release tarballs> [--server-config <dir>]
#
# The tarballs are downloaded and verified by the maintainer; this script never downloads anything.
# It does not touch nginx, the firewall, Docker or oryxos.service. Compatible with bash 4.2 and systemd 219.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSIONS_FILE="${SCRIPT_DIR}/../monitoring/versions.env"
SYNC_SCRIPT="${SCRIPT_DIR}/sync-server-config.sh"
SERVER_CONFIG="${SCRIPT_DIR}/../server-config"
DIST_DIR=""

MONITORING_HOME=/opt/monitoring
CONF_DIR=/etc/oryxos-monitoring
ENV_FILE="${CONF_DIR}/monitoring.env"
SECRETS_DIR="${CONF_DIR}/secrets"
PASSWORD_FILE="${SECRETS_DIR}/grafana-admin-password"
MIN_FREE_KB=$((3 * 1024 * 1024))

log() { printf '[install-monitoring] %s\n' "$*"; }
fail() { printf '[install-monitoring] ERROR: %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dist-dir) DIST_DIR="${2:-}"; shift 2 ;;
    --server-config) SERVER_CONFIG="${2:-}"; shift 2 ;;
    *) fail "unknown argument: $1 (usage: --dist-dir <dir> [--server-config <dir>])" ;;
  esac
done
[[ -n $DIST_DIR && -d $DIST_DIR ]] || fail "--dist-dir must point to the directory holding both tarballs"
[[ -d $SERVER_CONFIG/monitoring ]] || fail "server-config directory not found: $SERVER_CONFIG"
[[ -x $SYNC_SCRIPT || -f $SYNC_SCRIPT ]] || fail "sync-server-config.sh not found next to this script"
[[ -f $VERSIONS_FILE ]] || fail "versions file not found: $VERSIONS_FILE"
# shellcheck disable=SC1090
. "$VERSIONS_FILE"

PROM_TAR="${DIST_DIR}/prometheus-${PROMETHEUS_VERSION}.linux-amd64.tar.gz"
GRAFANA_TAR="${DIST_DIR}/grafana-${GRAFANA_VERSION}.linux-amd64.tar.gz"

# 1. Preflight
log "1/8 preflight"
[[ $(id -u) -eq 0 ]] || fail "must run as root"
systemd_version="$(systemctl --version | head -n 1 | awk '{print $2}')"
[[ $systemd_version =~ ^[0-9]+$ && $systemd_version -ge 219 ]] || fail "systemd >= 219 required (found: ${systemd_version:-unknown})"
port_owner_ok() {
  # port_owner_ok <port> <service>: free, or already held by our own service
  local port="$1" svc="$2"
  if ss -ltn 2>/dev/null | awk '{print $4}' | grep -qx "127.0.0.1:${port}"; then
    systemctl is-active --quiet "$svc" || fail "127.0.0.1:${port} is in use by another process"
    log "127.0.0.1:${port} held by ${svc} (already installed)"
  fi
}
port_owner_ok 9090 prometheus
port_owner_ok 3300 grafana
free_kb="$(df -Pk /opt | awk 'NR==2 {print $4}')"
[[ $free_kb -ge $MIN_FREE_KB ]] || fail "need >= 3GB free under /opt (found $((free_kb / 1024))MB)"

# 2. Verify tarballs offline
log "2/8 verify tarball checksums"
verify() {
  local file="$1" expected="$2" actual
  [[ -f $file ]] || fail "missing tarball: $file"
  actual="$(sha256sum "$file" | cut -d' ' -f1)"
  [[ $actual == "$expected" ]] || fail "sha256 mismatch for $(basename "$file")"
}
verify "$PROM_TAR" "$PROMETHEUS_SHA256"
verify "$GRAFANA_TAR" "$GRAFANA_SHA256"

# 3. Users and directories
log "3/8 users and directories"
for user in prometheus grafana; do
  id -u "$user" >/dev/null 2>&1 || useradd --system --no-create-home --shell /sbin/nologin "$user"
done
install -d -m 0755 -o root -g root "${MONITORING_HOME}/prometheus" "${MONITORING_HOME}/grafana" /opt/oryxos/monitoring-config
install -d -m 0750 -o prometheus -g prometheus /var/lib/prometheus
install -d -m 0750 -o grafana -g grafana /var/lib/grafana
install -d -m 0755 -o root -g root "$CONF_DIR"
install -d -m 0750 -o root -g grafana "$SECRETS_DIR"

# 4. Unpack and switch current links, then check the binaries run on this glibc
log "4/8 unpack"
unpack() {
  local tar="$1" dest="$2"
  if [[ ! -d $dest ]]; then
    install -d -m 0755 "${dest}.tmp"
    tar -xzf "$tar" -C "${dest}.tmp" --strip-components=1 --no-same-owner
    mv "${dest}.tmp" "$dest"
  fi
  ln -sfn "$(basename "$dest")" "$(dirname "$dest")/current"
}
unpack "$PROM_TAR" "${MONITORING_HOME}/prometheus/${PROMETHEUS_VERSION}"
unpack "$GRAFANA_TAR" "${MONITORING_HOME}/grafana/${GRAFANA_VERSION}"
"${MONITORING_HOME}/prometheus/current/prometheus" --version >/dev/null || fail "prometheus --version failed (glibc incompatible?)"
"${MONITORING_HOME}/grafana/current/bin/grafana" --version >/dev/null || fail "grafana --version failed (glibc incompatible?)"

# 5. Grafana admin password (generated once, never printed)
log "5/8 grafana admin password"
if [[ ! -f $PASSWORD_FILE ]]; then
  (umask 027 && openssl rand -base64 24 | tr -d '\n' > "$PASSWORD_FILE")
  log "generated ${PASSWORD_FILE}"
else
  log "kept existing ${PASSWORD_FILE}"
fi
chown root:grafana "$PASSWORD_FILE"
chmod 0640 "$PASSWORD_FILE"

# 6. monitoring.env placeholder (holds the public URLs; never committed)
log "6/8 monitoring.env"
if [[ ! -f $ENV_FILE ]]; then
  (umask 027 && cat > "$ENV_FILE" <<'EOF'
# Public URLs behind nginx; replace example.invalid with the real site, then restart prometheus and grafana.
GF_SERVER_ROOT_URL=https://example.invalid/grafana/
PROMETHEUS_EXTERNAL_URL=https://example.invalid/prometheus/
EOF
  )
  chmod 0640 "$ENV_FILE"
  log "wrote placeholder ${ENV_FILE}; fill in the real site URLs"
fi
if grep -q 'example.invalid' "$ENV_FILE"; then
  log "WARN ${ENV_FILE} still uses the example.invalid placeholder"
fi

# 7. Sync repository config, install units, start
log "7/8 sync config and start"
bash "$SYNC_SCRIPT" monitoring "$SERVER_CONFIG"
systemctl enable --now prometheus grafana

# 8. Acceptance commands
log "8/8 done; verify with:"
cat <<'EOF'
  ss -ltn | grep -E '127.0.0.1:(9090|3300)'
  systemctl show -p MemoryCurrent prometheus grafana
  curl -fsS http://127.0.0.1:9090/prometheus/-/ready
  curl -fsS http://127.0.0.1:3300/api/health
EOF
