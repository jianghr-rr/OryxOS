#!/usr/bin/env bash
set -euo pipefail

# Remote release switcher for OryxOS on Tencent Cloud.
# Usage: sudo bash deploy-release.sh <release_dir>

RELEASE_DIR="${1:-}"
APP_ROOT="/opt/oryxos"
RELEASES_ROOT="${APP_ROOT}/releases"
CURRENT_LINK="${APP_ROOT}/current"
PREVIOUS_LINK="${APP_ROOT}/previous"
HEALTH_URL="http://127.0.0.1:8080/api/v1/health"
STATE_URL="http://127.0.0.1:8080/api/v1/runtime/state"
KEEP_RELEASES=5

log() {
  printf '[deploy] %s\n' "$*"
}

fail() {
  printf '[deploy] ERROR: %s\n' "$*" >&2
  exit 1
}

check_health() {
  local i
  for i in $(seq 1 30); do
    if curl -fsS -o /dev/null "${HEALTH_URL}"; then
      local state_body
      state_body="$(curl -fsS "${STATE_URL}" 2>/dev/null || true)"
      if [[ "${state_body}" != *"PAUSED_RECORDING"* ]]; then
        log "Health probe passed on attempt #${i}."
        return 0
      fi
      log "Service responded but state is PAUSED_RECORDING!"
      return 1
    fi
    sleep 2
  done
  return 1
}

if [[ "$(id -u)" -ne 0 ]]; then
  fail "Please run as root."
fi

if [[ -z "${RELEASE_DIR}" || ! -d "${RELEASE_DIR}" ]]; then
  fail "Release directory missing: ${RELEASE_DIR}"
fi

if [[ ! -f "${RELEASE_DIR}/oryxos-boot.jar" || ! -f "${RELEASE_DIR}/oryxos-boot.jar.sha256" ]]; then
  fail "Missing oryxos-boot.jar or .sha256 in ${RELEASE_DIR}"
fi

# 1. Verify artifact checksum before touching running service
(cd -- "${RELEASE_DIR}" && sha256sum -c oryxos-boot.jar.sha256)

# 2. Record current target for rollback
PREV_TARGET=""
if [[ -L "${CURRENT_LINK}" ]]; then
  PREV_TARGET="$(readlink -f "${CURRENT_LINK}")"
fi

# 3. Stop old service gracefully to release workspace file lock
if systemctl is-active --quiet oryxos; then
  log "Stopping existing oryxos.service..."
  systemctl stop oryxos
fi

# 4. Update symlinks
if [[ -n "${PREV_TARGET}" && -d "${PREV_TARGET}" && "${PREV_TARGET}" != "${RELEASE_DIR}" ]]; then
  ln -sfn "${PREV_TARGET}" "${PREVIOUS_LINK}"
fi
ln -sfn "${RELEASE_DIR}" "${CURRENT_LINK}"

# 5. Start new version and verify health; rollback automatically on failure
log "Starting oryxos.service with ${RELEASE_DIR}..."
if ! systemctl start oryxos || ! check_health; then
  log "New release failed health check! Printing recent logs:"
  journalctl -u oryxos -n 100 --no-pager || true
  if [[ -L "${PREVIOUS_LINK}" ]]; then
    ROLLBACK_TARGET="$(readlink -f "${PREVIOUS_LINK}")"
    log "Auto-rolling back to ${ROLLBACK_TARGET}..."
    systemctl stop oryxos || true
    ln -sfn "${ROLLBACK_TARGET}" "${CURRENT_LINK}"
    systemctl start oryxos || true
    if check_health; then
      log "Auto-rollback succeeded; previous version restored."
    else
      log "CRITICAL: Auto-rollback also failed health check!"
    fi
  fi
  exit 1
fi

# 6. Prune old releases (keep current, previous, and latest KEEP_RELEASES)
CURRENT_REAL="$(readlink -f "${CURRENT_LINK}" || true)"
PREVIOUS_REAL="$(readlink -f "${PREVIOUS_LINK}" || true)"
mapfile -t OLD_RELEASES < <(ls -1dt "${RELEASES_ROOT}"/* 2>/dev/null | tail -n +"$((KEEP_RELEASES + 1))" || true)
for rel in "${OLD_RELEASES[@]}"; do
  rel_real="$(readlink -f "${rel}" || true)"
  if [[ "${rel_real}" != "${CURRENT_REAL}" && "${rel_real}" != "${PREVIOUS_REAL}" ]]; then
    rm -rf "${rel}"
  fi
done

log "Release ${RELEASE_DIR} active and healthy."
