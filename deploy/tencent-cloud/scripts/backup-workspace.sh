#!/usr/bin/env bash
set -euo pipefail

# Online consistent backup for OryxOS SQLite workspace.
# Uses `sqlite3 .backup` so WAL transactions are safely captured without stopping oryxos.service.

WORKSPACE_DIR="${ORYXOS_WORKSPACE:-/var/lib/oryxos/workspace}"
BACKUP_DIR="${ORYXOS_BACKUP_DIR:-/var/backups/oryxos}"
RETENTION_COUNT="${ORYXOS_BACKUP_RETENTION:-14}"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
STAGING_DIR="$(mktemp -d "/tmp/oryxos-backup-${TIMESTAMP}.XXXXXX")"
ARCHIVE_PATH="${BACKUP_DIR}/oryxos-backup-${TIMESTAMP}.tar.gz"

cleanup() {
  rm -rf "${STAGING_DIR}"
}
trap cleanup EXIT

mkdir -p "${BACKUP_DIR}"
chmod 0700 "${BACKUP_DIR}"

if [[ ! -d "${WORKSPACE_DIR}" ]]; then
  printf '[backup] ERROR: workspace not found at %s\n' "${WORKSPACE_DIR}" >&2
  exit 1
fi

# 1. Consistent online SQLite snapshot
if [[ -f "${WORKSPACE_DIR}/oryxos.db" ]]; then
  sqlite3 "${WORKSPACE_DIR}/oryxos.db" ".backup '${STAGING_DIR}/oryxos.db'"
fi

# 2. Copy non-secret workspace state (runtime.yaml, agents, memory)
for item in runtime.yaml agents memory; do
  if [[ -e "${WORKSPACE_DIR}/${item}" ]]; then
    cp -a "${WORKSPACE_DIR}/${item}" "${STAGING_DIR}/"
  fi
done

# 3. Pack archive + sha256
tar -czf "${ARCHIVE_PATH}" -C "${STAGING_DIR}" .
chmod 0600 "${ARCHIVE_PATH}"
sha256sum "${ARCHIVE_PATH}" > "${ARCHIVE_PATH}.sha256"
chmod 0600 "${ARCHIVE_PATH}.sha256"

# 4. Prune backups older than RETENTION_COUNT
mapfile -t OLD_BACKUPS < <(ls -1t "${BACKUP_DIR}"/oryxos-backup-*.tar.gz 2>/dev/null | tail -n +"$((RETENTION_COUNT + 1))" || true)
if [[ ${#OLD_BACKUPS[@]} -gt 0 ]]; then
  for old in "${OLD_BACKUPS[@]}"; do
    rm -f "${old}" "${old}.sha256"
  done
fi

printf '[backup] Created %s\n' "${ARCHIVE_PATH}"
