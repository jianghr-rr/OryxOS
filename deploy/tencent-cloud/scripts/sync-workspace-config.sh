#!/usr/bin/env bash
set -euo pipefail

# Atomically synchronize managed runtime.yaml and managed Agent definitions
# from a release config directory into the persistent OryxOS workspace.
#
# Usage:
#   sync-workspace-config.sh <config_source_dir> [workspace_dir]
#
# Preserves:
#   - oryxos.db, oryxos.db-wal, oryxos.db-shm
#   - memory/, output/, runtime/, workspace.json
#   - secrets.local.yaml (0600)
#   - Any unmanaged custom Agent directories under <workspace_dir>/agents/
#
# Removes:
#   - <workspace_dir>/agents/default (init placeholder)

CONFIG_DIR="${1:-}"
WORKSPACE_DIR="${2:-${ORYXOS_WORKSPACE:-/var/lib/oryxos/workspace}}"
ORYX_USER="${ORYXOS_USER:-oryxos}"
ORYX_GROUP="${ORYXOS_GROUP:-oryxos}"
SKIP_CHOWN="${ORYXOS_SKIP_CHOWN:-false}"

RUNTIME_TMP=""
STAGE_DIR=""

log() {
  printf '[sync-config] %s\n' "$*"
}

fail() {
  printf '[sync-config] ERROR: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  if [[ -n "${RUNTIME_TMP}" && -e "${RUNTIME_TMP}" ]]; then
    rm -f "${RUNTIME_TMP}"
  fi
  if [[ -n "${STAGE_DIR}" && -e "${STAGE_DIR}" ]]; then
    rm -rf "${STAGE_DIR}"
  fi
}
trap cleanup EXIT

apply_owner() {
  local target="$1"
  if [[ "${SKIP_CHOWN}" != "true" ]]; then
    chown "${ORYX_USER}:${ORYX_GROUP}" "${target}"
  fi
}

apply_owner_recursive() {
  local target="$1"
  if [[ "${SKIP_CHOWN}" != "true" ]]; then
    chown -R "${ORYX_USER}:${ORYX_GROUP}" "${target}"
  fi
}

if [[ "${SKIP_CHOWN}" != "true" && "$(id -u)" -ne 0 ]]; then
  fail "Please run as root (or set ORYXOS_SKIP_CHOWN=true for local non-root test)."
fi

if [[ -z "${CONFIG_DIR}" || ! -d "${CONFIG_DIR}" ]]; then
  fail "Config source directory missing: ${CONFIG_DIR}"
fi

RUNTIME_TEMPLATE="${CONFIG_DIR}/runtime.yaml.template"
AGENTS_SOURCE_DIR="${CONFIG_DIR}/agents"

if [[ ! -f "${RUNTIME_TEMPLATE}" ]]; then
  fail "Missing runtime template: ${RUNTIME_TEMPLATE}"
fi

if [[ ! -d "${AGENTS_SOURCE_DIR}" ]]; then
  fail "Missing managed agents directory: ${AGENTS_SOURCE_DIR}"
fi

mkdir -p "${WORKSPACE_DIR}" "${WORKSPACE_DIR}/agents"
chmod 0750 "${WORKSPACE_DIR}" "${WORKSPACE_DIR}/agents"
apply_owner "${WORKSPACE_DIR}"
apply_owner "${WORKSPACE_DIR}/agents"

# 1. Atomically replace managed runtime.yaml
RUNTIME_TMP="$(mktemp "${WORKSPACE_DIR}/.runtime.yaml.XXXXXX")"
cp "${RUNTIME_TEMPLATE}" "${RUNTIME_TMP}"
chmod 0640 "${RUNTIME_TMP}"
apply_owner "${RUNTIME_TMP}"
mv -f "${RUNTIME_TMP}" "${WORKSPACE_DIR}/runtime.yaml"
RUNTIME_TMP=""
log "Synchronized ${WORKSPACE_DIR}/runtime.yaml"

# 2. Atomically synchronize only managed Agent directories (preserving unmanaged custom Agents)
shopt -s nullglob
for src_agent in "${AGENTS_SOURCE_DIR}"/*; do
  [[ -d "${src_agent}" && ! -L "${src_agent}" ]] || continue
  agent_name="$(basename -- "${src_agent}")"
  if [[ ! -f "${src_agent}/AGENT.md" || -L "${src_agent}/AGENT.md" ]]; then
    fail "Invalid managed agent definition (missing regular AGENT.md): ${src_agent}"
  fi

  STAGE_DIR="$(mktemp -d "${WORKSPACE_DIR}/agents/.sync-${agent_name}.XXXXXX")"
  cp -R "${src_agent}/." "${STAGE_DIR}/"
  find "${STAGE_DIR}" -type d -exec chmod 0750 {} +
  find "${STAGE_DIR}" -type f -exec chmod 0640 {} +
  apply_owner_recursive "${STAGE_DIR}"

  dest_agent="${WORKSPACE_DIR}/agents/${agent_name}"
  old_backup=""
  if [[ -e "${dest_agent}" || -L "${dest_agent}" ]]; then
    old_backup="${WORKSPACE_DIR}/agents/.old-${agent_name}.$$"
    rm -rf "${old_backup}"
    mv "${dest_agent}" "${old_backup}"
  fi

  if ! mv "${STAGE_DIR}" "${dest_agent}"; then
    if [[ -n "${old_backup}" && -e "${old_backup}" ]]; then
      mv "${old_backup}" "${dest_agent}" || true
    fi
    fail "Failed to activate managed agent ${agent_name}"
  fi
  STAGE_DIR=""

  if [[ -n "${old_backup}" && -e "${old_backup}" ]]; then
    rm -rf "${old_backup}"
  fi
  log "Synchronized managed agent: ${agent_name}"
done
shopt -u nullglob

# 3. Remove unavailable placeholder default agent directory if present
DEFAULT_AGENT_DIR="${WORKSPACE_DIR}/agents/default"
if [[ -e "${DEFAULT_AGENT_DIR}" || -L "${DEFAULT_AGENT_DIR}" ]]; then
  rm -rf "${DEFAULT_AGENT_DIR}"
  log "Removed placeholder agent: default"
fi

log "Managed workspace configuration sync completed."
