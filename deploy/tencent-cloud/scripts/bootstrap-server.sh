#!/usr/bin/env bash
set -euo pipefail

# Idempotent one-time bootstrap for Tencent Cloud Linux host (CentOS/RHEL/Ubuntu).
# Run as root or with sudo from the directory containing deploy/tencent-cloud/.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

ORYX_USER="oryxos"
ORYX_GROUP="oryxos"
APP_ROOT="/opt/oryxos"
WORKSPACE_DIR="/var/lib/oryxos/workspace"
BACKUP_DIR="/var/backups/oryxos"
LOG_DIR="/var/log/oryxos"
JAVA_HOME_TARGET="/opt/java/jdk-21"

log() {
  printf '[bootstrap] %s\n' "$*"
}

fail() {
  printf '[bootstrap] ERROR: %s\n' "$*" >&2
  exit 1
}

if [[ "$(id -u)" -ne 0 ]]; then
  fail "Please run as root (e.g. sudo bash $0)."
fi

# 1. Verify or locate Java 21
if [[ -x "${JAVA_HOME_TARGET}/bin/java" ]]; then
  log "Found Java at ${JAVA_HOME_TARGET}/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_VER="$(java -version 2>&1 | head -n 1)"
  log "System java found: ${JAVA_VER}"
  if ! java -version 2>&1 | grep -qE '"21\.|openjdk version "21'; then
    fail "Java 21 is required. Install Temurin/OpenJDK 21 and link it to ${JAVA_HOME_TARGET}."
  fi
  JAVA_BIN_REAL="$(readlink -f "$(command -v java)")"
  mkdir -p /opt/java
  ln -sfn "$(dirname -- "$(dirname -- "${JAVA_BIN_REAL}")")" "${JAVA_HOME_TARGET}"
else
  fail "Java 21 not found. Install OpenJDK/Temurin 21 first, then re-run bootstrap."
fi

# 2. Verify sqlite3 CLI (required for online .backup snapshots)
if ! command -v sqlite3 >/dev/null 2>&1; then
  if command -v yum >/dev/null 2>&1; then
    log "Installing sqlite via yum..."
    yum install -y sqlite
  elif command -v dnf >/dev/null 2>&1; then
    log "Installing sqlite via dnf..."
    dnf install -y sqlite
  elif command -v apt-get >/dev/null 2>&1; then
    log "Installing sqlite3 via apt-get..."
    apt-get update && apt-get install -y sqlite3
  else
    fail "sqlite3 CLI not found and package manager unknown."
  fi
fi

# 3. Create dedicated non-login system user
if ! id -u "${ORYX_USER}" >/dev/null 2>&1; then
  log "Creating system user ${ORYX_USER}..."
  useradd --system --no-create-home --shell /sbin/nologin "${ORYX_USER}"
fi

# 4. Create isolated directories with least-privilege permissions
log "Creating directory hierarchy..."
mkdir -p "${APP_ROOT}/releases" "${APP_ROOT}/bin" \
         "${WORKSPACE_DIR}/agents" "${WORKSPACE_DIR}/memory" "${WORKSPACE_DIR}/output" \
         "${BACKUP_DIR}" "${LOG_DIR}"

chown -R "${ORYX_USER}:${ORYX_GROUP}" "${APP_ROOT}" "${WORKSPACE_DIR}" "${LOG_DIR}"
chmod 0755 "${APP_ROOT}" "${APP_ROOT}/releases"
chmod 0750 "${APP_ROOT}/bin" "${WORKSPACE_DIR}" "${LOG_DIR}"

chown root:root "${BACKUP_DIR}"
chmod 0700 "${BACKUP_DIR}"

# 5. Install operational scripts
log "Installing operational scripts to ${APP_ROOT}/bin..."
cp "${SCRIPT_DIR}/deploy-release.sh" "${APP_ROOT}/bin/deploy-release.sh"
cp "${SCRIPT_DIR}/rollback.sh" "${APP_ROOT}/bin/rollback.sh"
cp "${SCRIPT_DIR}/backup-workspace.sh" "${APP_ROOT}/bin/backup-workspace.sh"
cp "${SCRIPT_DIR}/restore-workspace.sh" "${APP_ROOT}/bin/restore-workspace.sh"
cp "${SCRIPT_DIR}/sync-workspace-config.sh" "${APP_ROOT}/bin/sync-workspace-config.sh"
chown root:"${ORYX_GROUP}" "${APP_ROOT}/bin/"*.sh
chmod 0750 "${APP_ROOT}/bin/"*.sh

# 6. Seed runtime.yaml and managed agents only if runtime.yaml is missing (never overwrite existing production config)
if [[ ! -f "${WORKSPACE_DIR}/runtime.yaml" ]]; then
  "${APP_ROOT}/bin/sync-workspace-config.sh" "${DEPLOY_ROOT}/config" "${WORKSPACE_DIR}"
  log "Seeded ${WORKSPACE_DIR}/runtime.yaml and managed agents from template."
fi

# 7. Install systemd units & backup timer
log "Installing systemd units..."
cp "${DEPLOY_ROOT}/systemd/oryxos.service" /etc/systemd/system/oryxos.service
cp "${DEPLOY_ROOT}/systemd/oryxos-backup.service" /etc/systemd/system/oryxos-backup.service
cp "${DEPLOY_ROOT}/systemd/oryxos-backup.timer" /etc/systemd/system/oryxos-backup.timer
chmod 0644 /etc/systemd/system/oryxos.service /etc/systemd/system/oryxos-backup.service /etc/systemd/system/oryxos-backup.timer

systemctl daemon-reload
systemctl enable oryxos.service
systemctl enable --now oryxos-backup.timer

log "Bootstrap complete."
log "Next: configure Baota Nginx vhost + Basic Auth + SSL, set GitHub Secrets, and trigger deploy-tencent-cloud.yml."
