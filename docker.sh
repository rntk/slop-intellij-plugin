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
a Docker volume per project and user, separate from host Gradle caches.
Docker's project cache uses .gradle/docker-project/ so it does not lock
IntelliJ's project cache. Concurrent helper builds wait for one another.
Requires Bash, sudo, flock, cksum, and Docker with BuildKit. The development image is
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

for required_command in sudo docker flock cksum; do
  if ! command -v "$required_command" >/dev/null 2>&1; then
    printf 'Required command is missing: %s\n' "$required_command" >&2
    exit 127
  fi
done

sudo docker build --target development -t "$image" "$project_dir"
if [[ "$action" == image ]]; then
  exit 0
fi

# Host daemons previously opened .gradle/docker and held global cache locks
# after their builds finished. Use a Docker-managed volume instead of a host
# Gradle directory. A stable name per project and UID preserves downloads
# across runs without sharing ownership with another user.
caller_uid="$(id -u)"
caller_gid="$(id -g)"
project_checksum="$(printf '%s' "$project_dir" | cksum)"
cache_volume="insight-workbench-gradle-$caller_uid-${project_checksum%% *}"
cache_mount="type=volume,source=$cache_volume,target=/docker-cache"
container_home=/docker-cache/home
container_gradle_home=/docker-cache/gradle
container_project_cache=/workspace/.gradle/docker-project

# Separate containers cannot coordinate Gradle cache locks. Serialize helper
# invocations on the host, including export, which also accesses build output.
mkdir -p "$project_dir/.gradle"
exec 9>"$project_dir/.gradle/build.lock"
if ! flock -n 9; then
  printf 'Another Docker build is running for this project; waiting...\n'
  flock 9
fi
printf 'Docker Gradle cache volume: %s\n' "$cache_volume"

# Named volumes start root-owned. Initialize only the two writable directories
# as root, then run Gradle with the caller's UID/GID. The numeric user has no
# passwd entry, so JAVA_TOOL_OPTIONS supplies user.home to forked JVMs too.
sudo docker run --rm --user 0:0 --entrypoint sh \
  --mount "$cache_mount" \
  "$image" -eu -c \
  'mkdir -p /docker-cache/home /docker-cache/gradle
   chown "$1:$2" /docker-cache/home /docker-cache/gradle' \
  sh "$caller_uid" "$caller_gid"

sudo docker run --rm \
  --user "$caller_uid:$caller_gid" \
  --env GRADLE_USER_HOME="$container_gradle_home" \
  --env HOME="$container_home" \
  --env XDG_DATA_HOME="$container_home/.local/share" \
  --env XDG_CONFIG_HOME="$container_home/.config" \
  --env XDG_CACHE_HOME="$container_home/.cache" \
  --env JAVA_TOOL_OPTIONS="-Duser.home=$container_home" \
  --mount "$cache_mount" \
  --mount "type=bind,source=$project_dir,target=/workspace" \
  "$image" --project-cache-dir "$container_project_cache" "$@"

if [[ "$action" == export ]]; then
  artifacts="$project_dir/docker-artifacts"
  rm -rf "$artifacts"
  mkdir -p "$artifacts"
  cp -R "$project_dir/build/distributions" "$project_dir/build/reports" "$project_dir/build/test-results" "$artifacts/"
  printf 'Artifacts copied to %s\n' "$artifacts"
fi
