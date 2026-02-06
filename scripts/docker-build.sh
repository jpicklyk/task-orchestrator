#!/usr/bin/env bash
set -euo pipefail

# Docker build script for MCP Task Orchestrator
# Usage: ./scripts/docker-build.sh [tag] [--clean] [--no-cache]
#
# Examples:
#   ./scripts/docker-build.sh                          # Build with default tag
#   ./scripts/docker-build.sh task-orchestrator:v2.0   # Build with custom tag
#   ./scripts/docker-build.sh --clean                  # Remove old image first
#   ./scripts/docker-build.sh --no-cache               # Build without Docker cache

IMAGE_TAG="task-orchestrator:dev"
CLEAN=false
NO_CACHE=""

for arg in "$@"; do
    case "$arg" in
        --clean)    CLEAN=true ;;
        --no-cache) NO_CACHE="--no-cache" ;;
        --help|-h)
            echo "Usage: ./scripts/docker-build.sh [tag] [--clean] [--no-cache]"
            echo ""
            echo "Options:"
            echo "  tag          Docker image tag (default: task-orchestrator:dev)"
            echo "  --clean      Remove old image before building"
            echo "  --no-cache   Build without Docker layer cache"
            echo "  --help       Show this help message"
            exit 0
            ;;
        -*)
            echo "Unknown option: $arg"
            exit 1
            ;;
        *)  IMAGE_TAG="$arg" ;;
    esac
done

echo "Building Docker image: ${IMAGE_TAG}"

if [ "$CLEAN" = true ]; then
    echo "Removing old image..."
    docker rmi "${IMAGE_TAG}" 2>/dev/null || true
fi

docker build ${NO_CACHE} -t "${IMAGE_TAG}" .

echo ""
echo "Build complete: ${IMAGE_TAG}"
echo "Run with: docker run --rm -i -v mcp-task-data:/app/data ${IMAGE_TAG}"
