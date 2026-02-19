#!/usr/bin/env bash
set -euo pipefail

# Docker build script for MCP Task Orchestrator
# Usage: ./scripts/docker-build.sh [tag] [--clean] [--no-cache] [--current] [--all]
#
# Examples:
#   ./scripts/docker-build.sh                          # Build v2 with default tag (task-orchestrator:dev)
#   ./scripts/docker-build.sh task-orchestrator:v2.0   # Build v2 with custom tag
#   ./scripts/docker-build.sh --clean                  # Remove old image first
#   ./scripts/docker-build.sh --no-cache               # Build without Docker cache
#   ./scripts/docker-build.sh --current                # Build v3 image (task-orchestrator:current)
#   ./scripts/docker-build.sh --all                    # Build both v2 and v3 images

TAG="task-orchestrator:dev"
CLEAN=false
NO_CACHE_FLAG=""
BUILD_CURRENT=false
BUILD_ALL=false

for arg in "$@"; do
    case "$arg" in
        --clean)    CLEAN=true ;;
        --no-cache) NO_CACHE_FLAG="--no-cache" ;;
        --current)  BUILD_CURRENT=true ;;
        --all)      BUILD_ALL=true ;;
        --help|-h)
            echo "Usage: ./scripts/docker-build.sh [tag] [--clean] [--no-cache] [--current] [--all]"
            echo ""
            echo "Options:"
            echo "  tag          Docker image tag for v2 build (default: task-orchestrator:dev)"
            echo "  --clean      Remove old image(s) before building"
            echo "  --no-cache   Build without Docker layer cache"
            echo "  --current    Build v3 image with tag task-orchestrator:current"
            echo "  --all        Build both v2 (task-orchestrator:dev) and v3 (task-orchestrator:current)"
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
    echo "Removing old image(s)..."
    if [ "$BUILD_CURRENT" = "true" ] || [ "$BUILD_ALL" = "true" ]; then
        docker rmi "task-orchestrator:current" 2>/dev/null || true
    fi
    if [ "$BUILD_CURRENT" != "true" ] || [ "$BUILD_ALL" = "true" ]; then
        docker rmi "${TAG}" 2>/dev/null || true
    fi
fi

# Build current (v3) if --current or --all
if [ "$BUILD_CURRENT" = "true" ] || [ "$BUILD_ALL" = "true" ]; then
    echo "Building Docker image: task-orchestrator:current (v3)"
    docker build --target runtime-current $NO_CACHE_FLAG -t "task-orchestrator:current" .
    echo "Built: task-orchestrator:current"
fi

# Build v2 if NOT --current-only, or if --all
if [ "$BUILD_CURRENT" != "true" ] || [ "$BUILD_ALL" = "true" ]; then
    echo "Building Docker image: ${TAG} (v2)"
    docker build --target runtime-v2 $NO_CACHE_FLAG -t "${TAG}" .
    echo "Built: ${TAG}"
fi

echo ""
echo "Build complete."
echo "Run with: docker run --rm -i -v mcp-task-data:/app/data <image-tag>"
