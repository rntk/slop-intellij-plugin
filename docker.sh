#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
image="insight-workbench-dev"

usage() {
  cat <<'EOF'
Usage: ./docker.sh [command] [extra Gradle arguments]

Commands:
  image    Build the development Docker image
  compile  Compile the Kotlin source
  test     Run unit tests
  build    Package the plugin and verify its structure
  check    Run tests, package the plugin, and verify its structure (default)
  verify   Run binary API compatibility checks (downloads additional IDEs)
  clean    Remove Gradle build output
  tasks    List all Gradle tasks
  gradle   Run arbitrary Gradle tasks supplied as arguments
  export   Test, package, and copy ZIPs/reports to docker-artifacts/
  help     Show this help

Examples:
  ./docker.sh test --rerun-tasks
  ./docker.sh gradle clean test buildPlugin verifyPluginStructure

Docker commands use sudo. Run this script as your regular user; containers
write bind-mounted output using your UID/GID. Gradle, dependencies, and the
IntelliJ IDEA SDK (about 1.4 GB download, several GB unpacked) are cached in
.gradle/ in the project directory, so they are downloaded only once.
Requires Bash, sudo, and Docker with BuildKit. The development image is
rebuilt using Docker's layer cache before each Gradle command so Dockerfile
changes are picked up automatically.
EOF
}

action="${1:-check}"
if (( $# > 0 )); then
  shift
fi

case "$action" in
  help|-h|--help) usage; exit 0 ;;
  image)
    if (( $# > 0 )); then
      printf 'The %s command does not accept extra arguments.\n' "$action" >&2
      exit 2
    fi
    ;;
  export)
    if (( $# > 0 )); then
      printf 'The export command does not accept extra arguments.\n' >&2
      exit 2
    fi
    set -- test buildPlugin verifyPluginStructure
    ;;
  compile) set -- compileKotlin "$@" ;;
  test) set -- test "$@" ;;
  build) set -- buildPlugin verifyPluginStructure "$@" ;;
  check) set -- test buildPlugin verifyPluginStructure "$@" ;;
  verify) set -- verifyPlugin "$@" ;;
  clean) set -- clean "$@" ;;
  tasks) set -- tasks --all "$@" ;;
  gradle)
    if (( $# == 0 )); then
      printf 'Supply at least one Gradle task, for example: ./docker.sh gradle test\n' >&2
      exit 2
    fi
    ;;
  *) printf 'Unknown command: %s\n' "$action" >&2; usage >&2; exit 2 ;;
esac

for required_command in sudo docker; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    printf 'Required command is missing: %s\n' "$required_command" >&2
    exit 127
  fi
done

sudo docker build --target development -t "$image" "$project_dir"
if [[ "$action" == image ]]; then
  exit 0
fi

# GRADLE_USER_HOME on the bind mount keeps the Gradle distribution, dependencies,
# and the IntelliJ IDEA SDK in .gradle/ so later runs reuse them.
#
# The container runs with a numeric --user that has no /etc/passwd entry, so a
# forked JVM falls back to user.home=/ and headless IDE tasks (e.g.
# buildSearchableOptions via PathManager.getCommonDataPath) try to create
# /.local and fail with AccessDeniedException. Point HOME, the XDG directories,
# and user.home (via JAVA_TOOL_OPTIONS so every forked JVM picks it up) at a
# writable directory on the bind mount, owned by the invoking UID and reused
# across runs.
container_home=/workspace/.gradle/docker-home
mkdir -p "$project_dir/.gradle/docker-home"
sudo docker run --rm \
  --user "$(id -u):$(id -g)" \
  --env GRADLE_USER_HOME=/workspace/.gradle \
  --env HOME="$container_home" \
  --env XDG_DATA_HOME="$container_home/.local/share" \
  --env XDG_CONFIG_HOME="$container_home/.config" \
  --env XDG_CACHE_HOME="$container_home/.cache" \
  --env JAVA_TOOL_OPTIONS="-Duser.home=$container_home" \
  --mount "type=bind,source=$project_dir,target=/workspace" \
  "$image" "$@"

if [[ "$action" == export ]]; then
  artifacts="$project_dir/docker-artifacts"
  rm -rf "$artifacts"
  mkdir -p "$artifacts"
  cp -R "$project_dir/build/distributions" "$project_dir/build/reports" "$project_dir/build/test-results" "$artifacts/"
  printf 'Artifacts copied to %s\n' "$artifacts"
fi
