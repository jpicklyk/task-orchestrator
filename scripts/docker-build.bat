@echo off
setlocal enabledelayedexpansion

REM Docker build script for MCP Task Orchestrator
REM Usage: scripts\docker-build.bat [tag] [--clean] [--no-cache] [--current] [--all]

set IMAGE_TAG=task-orchestrator:dev
set CLEAN=false
set NO_CACHE=
set BUILD_CURRENT=false
set BUILD_ALL=false

:parse_args
if "%~1"=="" goto :build
if "%~1"=="--clean" (
    set CLEAN=true
    shift
    goto :parse_args
)
if "%~1"=="--no-cache" (
    set NO_CACHE=--no-cache
    shift
    goto :parse_args
)
if "%~1"=="--current" (
    set BUILD_CURRENT=true
    shift
    goto :parse_args
)
if "%~1"=="--all" (
    set BUILD_ALL=true
    shift
    goto :parse_args
)
if "%~1"=="--help" goto :help
if "%~1"=="-h" goto :help
set IMAGE_TAG=%~1
shift
goto :parse_args

:help
echo Usage: scripts\docker-build.bat [tag] [--clean] [--no-cache] [--current] [--all]
echo.
echo Options:
echo   tag          Docker image tag for v2 build (default: task-orchestrator:dev)
echo   --clean      Remove old image(s) before building
echo   --no-cache   Build without Docker layer cache
echo   --current    Build v3 image with tag task-orchestrator:current
echo   --all        Build both v2 (task-orchestrator:dev) and v3 (task-orchestrator:current)
echo   --help       Show this help message
exit /b 0

:build
if "%CLEAN%"=="true" (
    echo Removing old image(s)...
    if "%BUILD_CURRENT%"=="true" (
        docker rmi task-orchestrator:current 2>nul
    )
    if "%BUILD_ALL%"=="true" (
        docker rmi task-orchestrator:current 2>nul
        docker rmi %IMAGE_TAG% 2>nul
    )
    if not "%BUILD_CURRENT%"=="true" if not "%BUILD_ALL%"=="true" (
        docker rmi %IMAGE_TAG% 2>nul
    )
)

REM Build current (v3) if --current or --all
if "%BUILD_CURRENT%"=="true" goto :build_current
if "%BUILD_ALL%"=="true" goto :build_current
goto :build_v2

:build_current
echo Building Docker image: task-orchestrator:current (v3)
docker build --target runtime-current %NO_CACHE% -t task-orchestrator:current .
if errorlevel 1 (
    echo ERROR: Docker build failed for task-orchestrator:current
    exit /b 1
)
echo Built: task-orchestrator:current

REM If --current only (not --all), skip v2 build
if "%BUILD_CURRENT%"=="true" if not "%BUILD_ALL%"=="true" goto :done

:build_v2
echo Building Docker image: %IMAGE_TAG% (v2)
docker build --target runtime-v2 %NO_CACHE% -t %IMAGE_TAG% .
if errorlevel 1 (
    echo ERROR: Docker build failed for %IMAGE_TAG%
    exit /b 1
)
echo Built: %IMAGE_TAG%

:done
echo.
echo Build complete.
echo Run with: docker run --rm -i -v mcp-task-data:/app/data ^<image-tag^>
