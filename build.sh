#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'EOF'
Usage: ./build.sh [command] [extra Gradle arguments]

Commands:
  compile  Compile the Kotlin sources (main and test)
  test     Run unit tests
  build    Package the plugin and verify its structure
  check    Run tests, package the plugin, and verify its structure (default)
  verify   Run binary API compatibility checks (downloads additional IDEs)
  clean    Remove Gradle build output
  tasks    List all Gradle tasks
  gradle   Run arbitrary Gradle tasks supplied as arguments
  help     Show this help

Examples:
  ./build.sh test --tests 'dev.insight.chat.*'
  ./build.sh gradle compileKotlin compileTestKotlin --offline

The Docker-free counterpart of ./docker.sh, intended for coding agents that
already run inside a container (.code_agents/). Gradle, dependencies, and the
IntelliJ IDEA SDK (about 1.4 GB download, several GB unpacked) are cached in
.gradle/agent-home/ inside the bind-mounted project, so they survive container
restarts and are shared by every agent image. Override the location with
INSIGHT_GRADLE_HOME. The project cache uses .gradle/agent-project/ so it does
not lock IntelliJ's project cache. Builds by this script and ./docker.sh wait
for one another because both write build/.
EOF
}

action="${1:-check}"
if (( $# > 0 )); then
  shift
fi

case "$action" in
  help|-h|--help) usage; exit 0 ;;
  compile) set -- compileKotlin compileTestKotlin "$@" ;;
  test) set -- test "$@" ;;
  build) set -- buildPlugin verifyPluginStructure "$@" ;;
  check) set -- test buildPlugin verifyPluginStructure "$@" ;;
  verify) set -- verifyPlugin "$@" ;;
  clean) set -- clean "$@" ;;
  tasks) set -- tasks --all "$@" ;;
  gradle)
    if (( $# == 0 )); then
      printf 'Supply at least one Gradle task, for example: ./build.sh gradle test\n' >&2
      exit 2
    fi
    ;;
  *) printf 'Unknown command: %s\n' "$action" >&2; usage >&2; exit 2 ;;
esac

# Ignore GRADLE_USER_HOME: older agent images point it at a directory inside
# the container, which would download the SDK again on every start.
gradle_home="${INSIGHT_GRADLE_HOME:-$project_dir/.gradle/agent-home}"
project_cache="$project_dir/.gradle/agent-project"
mkdir -p "$gradle_home" "$project_cache"

# Agents in separate containers cannot signal one another to release Gradle
# cache locks, and every build writes build/. Serialize this script and
# ./docker.sh on a lock file in the shared project directory.
exec 9>"$project_dir/.gradle/build.lock"
if ! flock -n 9; then
  printf 'Another build is running for this project; waiting...\n'
  flock 9
fi

# Without a daemon no process keeps cache locks once the build finishes.
cd "$project_dir"
GRADLE_USER_HOME="$gradle_home" sh ./gradlew --no-daemon --console=plain \
  --project-cache-dir "$project_cache" "$@"
