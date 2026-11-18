#!/usr/bin/env bash
# Internal helper; executable entry points never change the caller's environment.
set -euo pipefail
dev_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
oryx_repo=$(cd -- "$dev_dir/../.." && pwd -P)
fail() { printf '%s\n' "$*" >&2; exit 2; }
[[ -n ${ORYXOS_JAVA_HOME:-} ]] || fail 'Set ORYXOS_JAVA_HOME to an existing JDK 21 directory (no global JAVA_HOME change required).'
oryx_java=$(cd -- "$ORYXOS_JAVA_HOME" 2>/dev/null && pwd -P) || fail 'ORYXOS_JAVA_HOME is not a directory.'
[[ -x "$oryx_java/bin/java" && -x "$oryx_java/bin/javac" ]] || fail 'ORYXOS_JAVA_HOME must contain java and javac.'
oryx_java_version=$(/usr/bin/env -i PATH=/usr/bin:/bin "$oryx_java/bin/java" -version 2>&1)
[[ $oryx_java_version == *'version "21.'* ]] || fail 'ORYXOS_JAVA_HOME must select JDK 21.'
oryx_dev="$oryx_repo/.oryxos-dev"
umask 077
mkdir -p "$oryx_dev"/{home,tmp,maven,repository}
printf 'Repository: %s\nJDK: %s\n%s\n' "$oryx_repo" "$oryx_java" "${oryx_java_version%%$'\n'*}" >&2
