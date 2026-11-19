#!/usr/bin/env bash
set -euo pipefail

# Remote release switcher for OryxOS on Tencent Cloud.
# Usage: sudo bash deploy-release.sh <release_dir>

RELEASE_DIR="${1:-}"
APP_ROOT="/opt/oryxos"
RELEASES_ROOT="${APP_ROOT}/releases"
CURRENT_LINK="${APP_ROOT}/current"
PREVIOUS_LINK="${APP_ROOT}/previous"
WORKSPACE_DIR="/var/lib/oryxos/workspace"
SYNC_SCRIPT="${APP_ROOT}/bin/sync-workspace-config.sh"
HEALTH_URL="http://127.0.0.1:8080/api/v1/health"
STATE_URL="http://127.0.0.1:8080/api/v1/runtime/state"
PROFILES_URL="http://127.0.0.1:8080/api/v1/profiles"
KEEP_RELEASES=5

log() {
  printf '[deploy] %s\n' "$*"
}

fail() {
  printf '[deploy] ERROR: %s\n' "$*" >&2
  exit 1
}

sync_config_if_present() {
  local target_release="$1"
  if [[ -f "${target_release}/config/runtime.yaml.template" && -x "${SYNC_SCRIPT}" ]]; then
    log "Syncing managed workspace config from ${target_release}/config..."
    "${SYNC_SCRIPT}" "${target_release}/config" "${WORKSPACE_DIR}"
  else
    log "Skipping config sync (no ${target_release}/config/runtime.yaml.template or ${SYNC_SCRIPT})."
  fi
}

print_profiles_summary() {
  local payload
  payload="$(curl -fsS "${PROFILES_URL}" 2>/dev/null || true)"
  if [[ -z "${payload}" ]]; then
    log "Unable to query ${PROFILES_URL} for Agent availability summary."
    return 0
  fi
  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "${payload}" | python3 -c '
import json, sys
try:
    data = json.load(sys.stdin).get("data", {})
    items = data.get("items", [])
    if not items:
        print("[deploy] Loaded Agents: (none)")
    for item in items:
        name = item.get("name", "?")
        avail = item.get("availability", "?")
        err = item.get("safeError") or ""
        suffix = f" ({err})" if err else ""
        print(f"[deploy] Agent {name}: {avail}{suffix}")
except Exception as exc:
    print(f"[deploy] Could not parse profiles summary: {exc}")
' || true
  else
    log "Profiles endpoint reachable; python3 unavailable for formatted summary."
  fi
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

# 0. Install bundled sync-workspace-config.sh and link shared-lib as root
#    (avoids non-root fs.protected_hardlinks failures and keeps shared-lib owned by deploy user)
if [[ -f "${RELEASE_DIR}/sync-workspace-config.sh" ]]; then
  install -m 0750 -o root -g oryxos "${RELEASE_DIR}/sync-workspace-config.sh" "${SYNC_SCRIPT}"
fi

if [[ ! -d "${RELEASE_DIR}/lib" ]]; then
  if [[ ! -d "${APP_ROOT}/shared-lib" ]]; then
    fail "Missing both ${RELEASE_DIR}/lib and ${APP_ROOT}/shared-lib"
  fi
  SHARED_OWNER="$(stat -c '%u:%g' "${APP_ROOT}/shared-lib" 2>/dev/null || true)"
  if [[ -n "${SHARED_OWNER}" ]]; then
    chown -R "${SHARED_OWNER}" "${APP_ROOT}/shared-lib" || true
  fi
  chmod 0755 "${APP_ROOT}/shared-lib"
  find "${APP_ROOT}/shared-lib" -type f -exec chmod 0644 {} +
  cp -al "${APP_ROOT}/shared-lib" "${RELEASE_DIR}/lib"
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

# 4.5. Ensure workspace is initialized on first deploy (idempotent; never overwrites existing files)
if [[ ! -f "${WORKSPACE_DIR}/workspace.json" ]]; then
  log "Initializing ${WORKSPACE_DIR} via init command..."
  su -s /bin/bash oryxos -c "/opt/java/jdk-21/bin/java -jar '${RELEASE_DIR}/oryxos-boot.jar' --root '${WORKSPACE_DIR}' init"
fi

# 4.6. Synchronize managed runtime.yaml & managed agents (preserves DB, memory, secrets, custom agents; removes default)
sync_config_if_present "${RELEASE_DIR}"

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
    sync_config_if_present "${ROLLBACK_TARGET}" || true
    systemctl start oryxos || true
    if check_health; then
      log "Auto-rollback succeeded; previous version restored."
    else
      log "CRITICAL: Auto-rollback also failed health check!"
    fi
  fi
  exit 1
fi

print_profiles_summary

# 6. Prune old releases (keep current, previous, and latest KEEP_RELEASES)
CURRENT_REAL="$(readlink -f "${CURRENT_LINK}" || true)"
PREVIOUS_REAL="$(readlink -f "${PREVIOUS_LINK}" || true)"
OLD_RELEASES=()
mapfile -t OLD_RELEASES < <(ls -1dt "${RELEASES_ROOT}"/* 2>/dev/null | tail -n +"$((KEEP_RELEASES + 1))" || true)
if [[ ${#OLD_RELEASES[@]} -gt 0 ]]; then
  for rel in "${OLD_RELEASES[@]}"; do
    rel_real="$(readlink -f "${rel}" || true)"
    if [[ "${rel_real}" != "${CURRENT_REAL}" && "${rel_real}" != "${PREVIOUS_REAL}" ]]; then
      rm -rf "${rel}"
    fi
  done
fi

log "Release ${RELEASE_DIR} active and healthy."
