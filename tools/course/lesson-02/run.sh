#!/bin/bash
if [[ ${BASH_SOURCE[0]} != "$0" ]]; then
  printf 'Execute this script; do not source it.\n' >&2
  return 1
fi
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/common.sh"
[[ -x $jdk_home/bin/java && -x $maven_home/bin/mvn ]] || fail 'Run tools/course/lesson-02/setup.sh first.'
[[ $# -gt 0 ]] || fail 'Usage: run.sh {check|java|javac|mvn} [arguments...]'

case "$1" in
  check)
    printf 'Repository: %s\nCommit: %s\nCourse root: %s\n' \
      "$experiment_repo" "$(git -C "$experiment_repo" rev-parse HEAD)" "$course_root"
    "$course_scripts/run.sh" java -version
    "$course_scripts/run.sh" javac -version
    exec "$course_scripts/run.sh" mvn --version
    ;;
  java|javac) executable="$jdk_home/bin/$1"; shift; set -- "$executable" "$@" ;;
  mvn)
    shift
    set -- "$maven_home/bin/mvn" \
      --settings "$course_scripts/settings.xml" \
      --global-settings "$course_scripts/settings.xml" \
      "-Dmaven.repo.local=$course_root/cache/maven" "$@"
    ;;
  *) fail 'Supported commands: check, java, javac, mvn.' ;;
esac

mkdir -p "$course_root"/{home,tmp,cache/maven,cache/npm,cache/xdg,config,config/xdg,data,logs,runtime}
touch "$course_root/config/npmrc"
cd -- "$experiment_repo"

# HOME retains its real value. Java's user.home and all relevant caches are isolated.
# env -i drops inherited Java/Maven options, NVM settings, tokens and API keys.
# Maven reads the official checkout's .mvn/jvm.config; it skips personal mavenrc files.
exec /usr/bin/env -i \
  HOME="$HOME" USER="$(id -un)" LOGNAME="$(id -un)" \
  LANG=en_US.UTF-8 TZ=Asia/Shanghai \
  PATH="$jdk_home/bin:$maven_home/bin:/usr/bin:/bin:/usr/sbin:/sbin" \
  JAVA_HOME="$jdk_home" MAVEN_HOME="$maven_home" MAVEN_SKIP_RC=1 \
  JAVA_TOOL_OPTIONS="-Duser.home=$course_root/home -Djava.io.tmpdir=$course_root/tmp" \
  TMPDIR="$course_root/tmp/" \
  XDG_CACHE_HOME="$course_root/cache/xdg" XDG_CONFIG_HOME="$course_root/config/xdg" XDG_DATA_HOME="$course_root/data" \
  npm_config_cache="$course_root/cache/npm" npm_config_userconfig="$course_root/config/npmrc" \
  ORYXOS_COURSE_HOME="$course_root" ORYXOS_ROOT="$course_root/runtime/.oryxos" \
  "$@"
