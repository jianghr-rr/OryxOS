#!/usr/bin/env bash
set -euo pipefail

# Manual one-click rollback to /opt/oryxos/previous.

APP_ROOT="/opt/oryxos"
CURRENT_LINK="${APP_ROOT}/current"
PREVIOUS_LINK="${APP_ROOT}/previous"
WORKSPACE_DIR="/var/lib/oryxos/workspace"
SYNC_SCRIPT="${APP_ROOT}/bin/sync-workspace-config.sh"
HEALTH_URL="http://127.0.0.1:8080/api/v1/health"

if [[ "$(id -u)" -ne 0 ]]; then
  printf '[rollback] ERROR: Please run as root.\n' >&2
  exit 1
fi

if [[ ! -L "${PREVIOUS_LINK}" ]]; then
  printf '[rollback] ERROR: %s symlink does not exist.\n' "${PREVIOUS_LINK}" >&2
  exit 1
fi

TARGET="$(readlink -f "${PREVIOUS_LINK}")"
if [[ ! -f "${TARGET}/oryxos-boot.jar" ]]; then
  printf '[rollback] ERROR: Invalid target %s\n' "${TARGET}" >&2
  exit 1
fi

printf '[rollback] Stopping oryxos.service...\n'
systemctl stop oryxos || true

ln -sfn "${TARGET}" "${CURRENT_LINK}"
printf '[rollback] Switched current -> %s\n' "${TARGET}"

if [[ -f "${TARGET}/config/runtime.yaml.template" && -x "${SYNC_SCRIPT}" ]]; then
  printf '[rollback] Synchronizing workspace config from %s/config...\n' "${TARGET}"
  "${SYNC_SCRIPT}" "${TARGET}/config" "${WORKSPACE_DIR}"
fi

# The OryxOS drop-in follows the target release; monitoring config is left untouched.
SERVER_SYNC_SCRIPT="${APP_ROOT}/bin/sync-server-config.sh"
if [[ -d "${TARGET}/server-config" && -x "${SERVER_SYNC_SCRIPT}" ]]; then
  printf '[rollback] Synchronizing OryxOS drop-in from %s/server-config...\n' "${TARGET}"
  "${SERVER_SYNC_SCRIPT}" oryxos-dropin "${TARGET}/server-config"
fi

systemctl start oryxos
for i in $(seq 1 30); do
  if curl -fsS -o /dev/null "${HEALTH_URL}"; then
    printf '[rollback] Healthy after rollback (attempt #%d).\n' "${i}"
    exit 0
  fi
  sleep 2
done

printf '[rollback] ERROR: Health probe failed after rollback.\n' >&2
journalctl -u oryxos -n 100 --no-pager || true
exit 1
