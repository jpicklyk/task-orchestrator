#!/usr/bin/env bash
set -euo pipefail

# Docker build script for MCP Task Orchestrator
# Usage: ./scripts/docker-build.sh [tag] [--clean] [--no-cache]
#
# Examples:
#   ./scripts/docker-build.sh                          # Build with default tag (task-orchestrator:dev)
#   ./scripts/docker-build.sh task-orchestrator:v3.0   # Build with custom tag
#   ./scripts/docker-build.sh --clean                  # Remove old image first
#   ./scripts/docker-build.sh --no-cache               # Build without Docker cache

TAG="task-orchestrator:dev"
CLEAN=false
NO_CACHE_FLAG=""

for arg in "$@"; do
    case "$arg" in
        --clean)    CLEAN=true ;;
        --no-cache) NO_CACHE_FLAG="--no-cache" ;;
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
        *)  TAG="$arg" ;;
    esac
done

if [ "$CLEAN" = true ]; then
    echo "Removing old image..."
    docker rmi "${TAG}" 2>/dev/null || true
fi

echo "Building Docker image: ${TAG}"
docker build --target runtime-current $NO_CACHE_FLAG -t "${TAG}" .
echo "Built: ${TAG}"

echo ""
echo "Build complete."
echo "Run with: docker run --rm -i -v mcp-task-data:/app/data ${TAG}"
