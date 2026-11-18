#!/bin/bash
# Sourced only by this lesson's executable scripts; never source into a login shell.
set -euo pipefail
course_scripts=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
source "$course_scripts/toolchain.env"
course_root=${ORYXOS_COURSE_HOME:-"$HOME/jikeshijian/.oryxos-course"}
experiment_repo=${ORYXOS_EXPERIMENT_REPO:-"$HOME/jikeshijian/oryxos"}

fail() { printf '%s\n' "$*" >&2; exit 1; }
[[ $(uname -sm) == 'Darwin arm64' ]] || fail 'This toolchain supports macOS arm64 only.'
[[ $course_root == /* && $course_root != *[[:space:]]* ]] || fail 'ORYXOS_COURSE_HOME must be an absolute path without whitespace.'
[[ -d $experiment_repo ]] || fail "Experiment repository not found: $experiment_repo"
experiment_repo=$(cd -- "$experiment_repo" && pwd -P)
origin=$(git -C "$experiment_repo" remote get-url origin)
case "$origin" in
  git@github.com:oryx-labs/oryxos.git|https://github.com/oryx-labs/oryxos.git|https://github.com/oryx-labs/oryxos|ssh://git@github.com/oryx-labs/oryxos.git) ;;
  *) fail "Expected the official oryx-labs/oryxos checkout; found origin: $origin" ;;
esac
[[ -f $experiment_repo/pom.xml ]] || fail 'Official checkout has no root pom.xml.'

# Refuse installation inside any Git worktree, including the homework checkout.
existing_parent=$course_root
while [[ ! -e $existing_parent ]]; do existing_parent=$(dirname -- "$existing_parent"); done
if git -C "$existing_parent" rev-parse --show-toplevel >/dev/null 2>&1; then
  fail 'Keep ORYXOS_COURSE_HOME outside all Git worktrees.'
fi
umask 077
mkdir -p "$course_root"
course_root=$(cd -- "$course_root" && pwd -P)
jdk_home="$course_root/tools/jdk-$JDK_VERSION/Contents/Home"
maven_home="$course_root/tools/apache-maven-$MAVEN_VERSION"
