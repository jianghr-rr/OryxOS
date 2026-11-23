#!/usr/bin/env bash
# Synchronize repository-managed server configuration during a release (FR-020a).
#
# Usage:
#   sync-server-config.sh oryxos-dropin <release_dir>/server-config   # before the new version starts
#   sync-server-config.sh oryxos-dropin --restore                       # undo the last drop-in sync
#   sync-server-config.sh monitoring    <release_dir>/server-config   # after OryxOS is healthy
#
# Exit codes: 0 success/skip, 1 sync failed (previous state restored), 2 usage error.
# ORYXOS_SYNC_ROOT prefixes every absolute path (tests only). Compatible with bash 4.2 and systemd 219.
set -euo pipefail

ROOT="${ORYXOS_SYNC_ROOT:-}"
SYSTEMD_DIR="${ROOT}/etc/systemd/system"
DROPIN_DIR="${SYSTEMD_DIR}/oryxos.service.d"
DROPIN="${DROPIN_DIR}/10-observability.conf"
STATE_DIR="${ROOT}/opt/oryxos/monitoring-config"
MANIFEST="${STATE_DIR}/server-config.sha256"
PROMTOOL="${ROOT}/opt/monitoring/prometheus/current/promtool"
KEEP=3

log() { printf '[sync-server-config] %s\n' "$*"; }
err() { printf '[sync-server-config] ERROR: %s\n' "$*" >&2; }
usage() { err "usage: $0 oryxos-dropin <server-config-dir> | oryxos-dropin --restore | monitoring <server-config-dir>"; exit 2; }

sha() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

tree_hash() {
  # tree_hash <base> <path>... : stable hash of relative paths and contents (missing paths contribute nothing)
  local base="$1" f
  shift
  (cd "$base" && for p in "$@"; do if [[ -e $p ]]; then find "$p" -type f; fi; done | LC_ALL=C sort | while read -r f; do
    printf '%s  %s\n' "$(sha "$f")" "$f"
  done) > "${STATE_DIR}/.tree.$$"
  sha "${STATE_DIR}/.tree.$$"
  rm -f "${STATE_DIR}/.tree.$$"
}

installed() { [[ -x "${ROOT}/opt/monitoring/$1/current/$( [[ $1 == grafana ]] && echo bin/grafana || echo prometheus )" ]]; }

replace_file() {
  # replace_file <src> <dst>: atomic replace keeping <dst>.prev, or <dst>.created when <dst> was absent
  local src="$1" dst="$2" tmp
  rm -f "${dst}.prev" "${dst}.created"
  if [[ -f $dst ]]; then cp -p "$dst" "${dst}.prev"; else : > "${dst}.created"; fi
  tmp="$(mktemp "$(dirname "$dst")/.sync.XXXXXX")"
  cp "$src" "$tmp"
  chmod 0644 "$tmp"
  mv -f "$tmp" "$dst"
}

restore_file() {
  local dst="$1"
  if [[ -f "${dst}.prev" ]]; then
    mv -f "${dst}.prev" "$dst"
  elif [[ -f "${dst}.created" ]]; then
    rm -f "$dst"
  else
    return 1
  fi
  rm -f "${dst}.created"
}

write_manifest() {
  # Deployed content keyed by the repository path below server-config/ (SC-007).
  local src="$1" rel deployed
  mkdir -p "$STATE_DIR"
  : > "${MANIFEST}.tmp"
  (cd "$src" && find . -type f | sed 's|^\./||' | LC_ALL=C sort) | while read -r rel; do
    case "$rel" in
      systemd/oryxos.service.d/*) deployed="${DROPIN_DIR}/${rel#systemd/oryxos.service.d/}" ;;
      systemd/*) deployed="${SYSTEMD_DIR}/${rel#systemd/}" ;;
      monitoring/*) deployed="${STATE_DIR}/current/${rel#monitoring/}" ;;
      *) continue ;;
    esac
    if [[ -f $deployed ]]; then
      printf '%s  %s\n' "$(sha "$deployed")" "$rel" >> "${MANIFEST}.tmp"
    else
      printf 'MISSING  %s\n' "$rel" >> "${MANIFEST}.tmp"
    fi
  done
  mv -f "${MANIFEST}.tmp" "$MANIFEST"
}

switch_link() {
  # switch_link <target-name>: repoint STATE_DIR/current atomically where GNU mv is available
  ln -sfn "$1" "${STATE_DIR}/.current.new"
  if mv --version >/dev/null 2>&1; then
    mv -Tf "${STATE_DIR}/.current.new" "${STATE_DIR}/current"
  else
    rm -f "${STATE_DIR}/.current.new"
    ln -sfn "$1" "${STATE_DIR}/current"
  fi
}

sync_dropin() {
  local src_dir="$1" src
  src="${src_dir}/systemd/oryxos.service.d/10-observability.conf"
  if [[ ! -f $src ]]; then
    log "oryxos-dropin: release carries no drop-in; skipped"
    return 0
  fi
  mkdir -p "$DROPIN_DIR"
  if [[ -f $DROPIN ]] && cmp -s "$src" "$DROPIN"; then
    rm -f "${DROPIN}.prev" "${DROPIN}.created"
    log "oryxos-dropin: unchanged"
  else
    replace_file "$src" "$DROPIN"
    if ! systemctl daemon-reload; then
      err "oryxos-dropin: daemon-reload failed; restoring previous drop-in"
      restore_file "$DROPIN" || true
      systemctl daemon-reload || true
      return 1
    fi
    log "oryxos-dropin: updated, daemon reloaded"
  fi
  write_manifest "$src_dir"
}

restore_dropin() {
  if restore_file "$DROPIN"; then
    systemctl daemon-reload
    log "oryxos-dropin: restored state before last sync"
  else
    log "oryxos-dropin: nothing to restore"
  fi
}

sync_monitoring() {
  local src_dir="$1" hash old target changed="" units_changed=0 svc unit
  if [[ ! -d "${src_dir}/monitoring" ]]; then
    log "monitoring: release carries no monitoring config; skipped"
    return 0
  fi
  mkdir -p "$STATE_DIR"
  hash="$(tree_hash "$src_dir" monitoring systemd/prometheus.service systemd/grafana.service | cut -c1-16)"
  old="$(readlink "${STATE_DIR}/current" 2>/dev/null || true)"
  target="${STATE_DIR}/${hash}"
  if [[ ! -d $target ]]; then
    rm -rf "${target}.tmp"
    mkdir -p "${target}.tmp"
    cp -R "${src_dir}/monitoring/." "${target}.tmp/"
    mkdir -p "${target}.tmp/.units"
    for unit in prometheus grafana; do
      if [[ -f "${src_dir}/systemd/${unit}.service" ]]; then cp "${src_dir}/systemd/${unit}.service" "${target}.tmp/.units/"; fi
    done
    mv "${target}.tmp" "$target"
  fi

  if [[ -x $PROMTOOL ]]; then
    if ! "$PROMTOOL" check config "${target}/prometheus/prometheus.yml"; then
      err "monitoring: promtool rejected prometheus.yml; keeping ${old:-no previous config}"
      [[ ${old} == "${hash}" ]] || rm -rf "$target"
      return 1
    fi
  else
    log "monitoring: promtool not installed; prometheus.yml not validated"
  fi
  if command -v python3 >/dev/null 2>&1; then
    for f in "${target}"/grafana/dashboards/*.json; do
      [[ -f $f ]] || continue
      # Explicit UTF-8: python3.6's json.tool decodes with the locale, which is ASCII under sudo.
      python3 -c 'import json, sys; json.load(open(sys.argv[1], encoding="utf-8"))' "$f" || { err "monitoring: invalid dashboard JSON $f"; [[ ${old} == "${hash}" ]] || rm -rf "$target"; return 1; }
    done
  fi

  for svc in prometheus grafana; do
    if [[ -z $old || ! -d "${STATE_DIR}/${old}" ]] \
      || [[ "$(tree_hash "$target" "$svc" ".units/${svc}.service")" != "$(tree_hash "${STATE_DIR}/${old}" "$svc" ".units/${svc}.service")" ]]; then
      changed="${changed} ${svc}"
    fi
  done

  if [[ $old == "$hash" ]]; then
    log "monitoring: unchanged"
    write_manifest "$src_dir"
    return 0
  fi
  switch_link "$hash"

  for svc in prometheus grafana; do
    if installed "$svc" && [[ -f "${target}/.units/${svc}.service" ]] \
      && ! cmp -s "${target}/.units/${svc}.service" "${SYSTEMD_DIR}/${svc}.service" 2>/dev/null; then
      replace_file "${target}/.units/${svc}.service" "${SYSTEMD_DIR}/${svc}.service"
      units_changed=1
    fi
  done

  local failed=""
  if [[ $units_changed == 1 ]] && ! systemctl daemon-reload; then failed="daemon-reload"; fi
  for svc in $changed; do
    [[ -n $failed ]] && break
    if ! installed "$svc"; then
      log "monitoring not installed; skipped restart (${svc})"
      continue
    fi
    if systemctl restart "$svc" && sleep "${ORYXOS_SYNC_SETTLE_SECONDS:-3}" && systemctl is-active --quiet "$svc"; then
      log "monitoring: ${svc} changed, restarted"
    else
      failed="$svc"
    fi
  done

  if [[ -n $failed ]]; then
    err "monitoring: ${failed} failed; restoring previous monitoring config"
    if [[ -n $old && -d "${STATE_DIR}/${old}" ]]; then switch_link "$old"; else rm -f "${STATE_DIR}/current"; fi
    for svc in prometheus grafana; do restore_file "${SYSTEMD_DIR}/${svc}.service" 2>/dev/null || true; done
    systemctl daemon-reload || true
    for svc in $changed; do
      if installed "$svc"; then systemctl restart "$svc" || true; fi
    done
    write_manifest "$src_dir"
    return 1
  fi
  for svc in prometheus grafana; do rm -f "${SYSTEMD_DIR}/${svc}.service.prev" "${SYSTEMD_DIR}/${svc}.service.created"; done

  # Keep the active config plus the newest KEEP-1 others.
  local count=0 d
  for d in $(ls -1t "$STATE_DIR"); do
    [[ -d "${STATE_DIR}/${d}" && ! -L "${STATE_DIR}/${d}" && $d != "$hash" ]] || continue
    count=$((count + 1))
    [[ $count -lt $KEEP ]] || rm -rf "${STATE_DIR:?}/${d}"
  done
  write_manifest "$src_dir"
}

case "${1:-}" in
  oryxos-dropin)
    [[ $# -eq 2 ]] || usage
    if [[ $2 == --restore ]]; then restore_dropin; else sync_dropin "$2"; fi
    ;;
  monitoring)
    [[ $# -eq 2 ]] || usage
    sync_monitoring "$2"
    ;;
  *) usage ;;
esac
