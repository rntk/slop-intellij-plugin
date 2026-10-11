#!/bin/bash

# Run docker directly when the daemon is accessible, otherwise fall back to sudo
docker() {
    if [ -z "${DOCKER_SUDO+x}" ]; then
        if ! command -v docker >/dev/null 2>&1; then
            echo "docker is not installed or not in PATH" >&2
            exit 1
        fi
        if command docker info >/dev/null 2>&1; then
            DOCKER_SUDO=""
        else
            echo "Docker daemon not accessible as $(id -un); docker commands will run with sudo." >&2
            DOCKER_SUDO="sudo"
        fi
    fi
    if [ -n "$DOCKER_SUDO" ]; then
        sudo docker "$@"
    else
        command docker "$@"
    fi
}

run_agent() {
    echo "Available AI Agents:"
    echo "1. claude-code"
    echo "2. codex-cli"
    echo "3. antigravity-cli"
    echo "4. muse-code-cli"
    read -rp "Select agent (1-4): " choice
    local project_name
    project_name="$(basename "$(pwd)")"
    case "$choice" in
        1) docker run --rm -it -v "$(pwd):/app" -v "$HOME/.claude:/home/appuser/.claude" -v "$HOME/.claude/.claude.json:/home/appuser/.claude.json" -e ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY}" "${project_name}-claude-code:latest" --verbose --dangerously-skip-permissions ;;
        2) docker run --rm -it -v "$(pwd):/app" -v "$HOME/.codex:/home/appuser/.codex" -e OPENAI_API_KEY="${OPENAI_API_KEY}" "${project_name}-codex-cli:latest" -a never --sandbox danger-full-access --no-daemon;;
        3) docker run --rm -it -v "$(pwd):/app" -v "$HOME/.gemini:/home/appuser/.gemini" -v "$HOME/.config/Antigravity:/home/appuser/.config/Antigravity" -v "$HOME/.config/Antigravity IDE:/home/appuser/.config/Antigravity IDE" "${project_name}-antigravity-cli:latest" --dangerously-skip-permissions ;;
        4) docker run --rm -it -v "$(pwd):/app" -v "$HOME/.config/muse:/home/appuser/.config/muse" "${project_name}-muse-code-cli:latest" --yolo ;;
        *) echo "Invalid selection" ; exit 1 ;;
    esac
}

build_agent() {
    echo "Available AI Agents:"
    echo "1. claude-code"
    echo "2. codex-cli"
    echo "3. antigravity-cli"
    echo "4. muse-code-cli"
    read -rp "Select agent to build (1-4): " choice
    local project_name
    project_name="$(basename "$(pwd)")"
    local base_image="${project_name}-code-agent:latest"

    if [ -f .code_agents/Dockerfile.code_agent ]; then
        echo "Building base image ${base_image}..."
        docker build --no-cache -f .code_agents/Dockerfile.code_agent -t "${base_image}" .
    fi

    case "$choice" in
        1) docker build --no-cache --build-arg BASE_IMAGE="${base_image}" -f .code_agents/Dockerfile.claude-code -t "${project_name}-claude-code:latest" . ;;
        2) docker build --no-cache --build-arg BASE_IMAGE="${base_image}" -f .code_agents/Dockerfile.codex-cli -t "${project_name}-codex-cli:latest" . ;;
        3) docker build --no-cache --build-arg BASE_IMAGE="${base_image}" -f .code_agents/Dockerfile.antigravity-cli -t "${project_name}-antigravity-cli:latest" . ;;
        4) docker build --no-cache --build-arg BASE_IMAGE="${base_image}" -f .code_agents/Dockerfile.muse-code-cli -t "${project_name}-muse-code-cli:latest" . ;;
        *) echo "Unknown agent" ; exit 1 ;;
    esac
}

rebuild_all() {
    echo "Rebuilding all agent images..."
    local failed=()
    local succeeded=()
    local project_name
    project_name="$(basename "$(pwd)")"
    local base_image="${project_name}-code-agent:latest"

    if [ -f .code_agents/Dockerfile.code_agent ]; then
        echo "Building base image ${base_image}..."
        docker build --no-cache -f .code_agents/Dockerfile.code_agent -t "${base_image}" .
    fi

    echo "[1/4] claude-code"
    docker build --no-cache --build-arg BASE_IMAGE="${base_image}" -f .code_agents/Dockerfile.claude-code -t "${project_name}-claude-code:latest" . && succeeded+=("claude-code") || failed+=("claude-code")
    echo "[2/4] codex-cli"
    docker build --no-cache --build-arg BASE_IMAGE="${base_image}" -f .code_agents/Dockerfile.codex-cli -t "${project_name}-codex-cli:latest" . && succeeded+=("codex-cli") || failed+=("codex-cli")
    echo "[3/4] antigravity-cli"
    docker build --no-cache --build-arg BASE_IMAGE="${base_image}" -f .code_agents/Dockerfile.antigravity-cli -t "${project_name}-antigravity-cli:latest" . && succeeded+=("antigravity-cli") || failed+=("antigravity-cli")
    echo "[4/4] muse-code-cli"
    docker build --no-cache --build-arg BASE_IMAGE="${base_image}" -f .code_agents/Dockerfile.muse-code-cli -t "${project_name}-muse-code-cli:latest" . && succeeded+=("muse-code-cli") || failed+=("muse-code-cli")

    echo ""
    echo -e "\033[1;32m[DONE]\033[0m Rebuild complete."
    echo "  Succeeded (${#succeeded[@]}): ${succeeded[*]:-none}"
    if [ ${#failed[@]} -gt 0 ]; then
        echo -e "  \033[1;31mFailed    (${#failed[@]}): ${failed[*]}\033[0m"
        exit 1
    fi
}

usage() {
    echo "Usage: ./.code_agents/agent.sh <run|build|rebuild-all> [args...]"
    echo ""
    echo "Commands:"
    echo "  run         Select and run an agent container (default)"
    echo "  build       Build a single agent Docker image"
    echo "  rebuild-all Rebuild ALL agent Docker images sequentially"
    exit 1
}

case "$1" in
    run|"")       run_agent ;;
    build)        build_agent ;;
    rebuild-all)  rebuild_all ;;
    *)            usage ;;
esac
