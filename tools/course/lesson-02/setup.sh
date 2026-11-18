#!/bin/bash
if [[ ${BASH_SOURCE[0]} != "$0" ]]; then
  printf 'Execute this script; do not source it.\n' >&2
  return 1
fi
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
mkdir -p "$course_root/downloads" "$course_root/tools" "$course_root/logs"

fetch_verified() {
  local url=$1 filename=$2 algorithm=$3 expected=$4 actual
  local destination="$course_root/downloads/$filename"
  # A previous download may have completed before checksum verification failed.
  if [[ ! -f $destination && -f $destination.partial ]]; then
    actual=$(LC_ALL=C /usr/bin/shasum -a "$algorithm" "$destination.partial")
    if [[ ${actual%% *} == "$expected" ]]; then
      mv -- "$destination.partial" "$destination"
    fi
  fi
  if [[ ! -f $destination ]]; then
    /usr/bin/curl -q --fail --location --silent --show-error \
      --proto '=https' --proto-redir '=https' --connect-timeout 20 \
      --max-time 600 --retry 2 --output "$destination.partial" "$url"
    actual=$(LC_ALL=C /usr/bin/shasum -a "$algorithm" "$destination.partial")
    [[ ${actual%% *} == "$expected" ]] || fail "Checksum mismatch: $filename.partial"
    mv -- "$destination.partial" "$destination"
  fi
  actual=$(LC_ALL=C /usr/bin/shasum -a "$algorithm" "$destination")
  [[ ${actual%% *} == "$expected" ]] || fail "Checksum mismatch: $filename"
  printf 'Verified SHA-%s: %s\n' "$algorithm" "$filename"
}

fetch_verified "$JDK_URL" "$JDK_ARCHIVE" 256 "$JDK_SHA256"
fetch_verified "$MAVEN_URL" "$MAVEN_ARCHIVE" 512 "$MAVEN_SHA512"

install_archive() {
  local archive=$1 directory=$2 stage
  if [[ -d $course_root/tools/$directory ]]; then
    printf 'Already installed: %s\n' "$directory"
    return
  fi
  stage=$(mktemp -d "$course_root/tools/.extract.XXXXXX")
  LC_ALL=C /usr/bin/tar -xzf "$course_root/downloads/$archive" -C "$stage"
  [[ -d $stage/$directory ]] || fail "Unexpected archive layout: $archive (inspect $stage)"
  mv -- "$stage/$directory" "$course_root/tools/$directory"
  rmdir -- "$stage"
}
install_archive "$JDK_ARCHIVE" "jdk-$JDK_VERSION"
install_archive "$MAVEN_ARCHIVE" "apache-maven-$MAVEN_VERSION"
"$course_scripts/run.sh" check
