#!/usr/bin/env bash
set -euo pipefail

# Safely restore OryxOS workspace from a verified backup archive.
# Usage: sudo bash restore-workspace.sh /var/backups/oryxos/oryxos-backup-YYYYMMDD-HHMMSS.tar.gz

ARCHIVE_PATH="${1:-}"
WORKSPACE_DIR="${ORYXOS_WORKSPACE:-/var/lib/oryxos/workspace}"
ORYX_USER="${ORYXOS_USER:-oryxos}"
ORYX_GROUP="${ORYXOS_GROUP:-oryxos}"

fail() {
  printf '[restore] ERROR: %s\n' "$*" >&2
  exit 1
}

if [[ "$(id -u)" -ne 0 ]]; then
  fail "Please run as root."
fi

if [[ -z "${ARCHIVE_PATH}" || ! -f "${ARCHIVE_PATH}" ]]; then
  fail "Usage: $0 <path-to-oryxos-backup.tar.gz>"
fi

if [[ -f "${ARCHIVE_PATH}.sha256" ]]; then
  (cd -- "$(dirname -- "${ARCHIVE_PATH}")" && sha256sum -c "$(basename -- "${ARCHIVE_PATH}.sha256")")
else
  fail "Checksum file ${ARCHIVE_PATH}.sha256 missing; refusing unverified restore."
fi

if systemctl is-active --quiet oryxos; then
  fail "oryxos.service is running. Stop it first: systemctl stop oryxos"
fi

SAFETY_SNAPSHOT="/tmp/oryxos-pre-restore-$(date +%Y%m%d-%H%M%S)"
mkdir -p "${SAFETY_SNAPSHOT}"
for item in oryxos.db oryxos.db-wal oryxos.db-shm runtime.yaml agents memory; do
  if [[ -e "${WORKSPACE_DIR}/${item}" ]]; then
    mv "${WORKSPACE_DIR}/${item}" "${SAFETY_SNAPSHOT}/"
  fi
done

mkdir -p "${WORKSPACE_DIR}"
tar -xzf "${ARCHIVE_PATH}" -C "${WORKSPACE_DIR}"
chown -R "${ORYX_USER}:${ORYX_GROUP}" "${WORKSPACE_DIR}"
chmod 0750 "${WORKSPACE_DIR}"
if [[ -f "${WORKSPACE_DIR}/secrets.local.yaml" ]]; then
  chmod 0600 "${WORKSPACE_DIR}/secrets.local.yaml"
fi

printf '[restore] Restored %s into %s (previous files moved to %s)\n' "${ARCHIVE_PATH}" "${WORKSPACE_DIR}" "${SAFETY_SNAPSHOT}"
printf '[restore] Start service with: systemctl start oryxos\n'
