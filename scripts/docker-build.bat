@echo off
setlocal enabledelayedexpansion

REM Docker build script for MCP Task Orchestrator
REM Usage: scripts\docker-build.bat [tag] [--clean] [--no-cache]

set IMAGE_TAG=task-orchestrator:dev
set CLEAN=false
set NO_CACHE=

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
if "%~1"=="--help" goto :help
if "%~1"=="-h" goto :help
set IMAGE_TAG=%~1
shift
goto :parse_args

:help
echo Usage: scripts\docker-build.bat [tag] [--clean] [--no-cache]
echo.
echo Options:
echo   tag          Docker image tag (default: task-orchestrator:dev)
echo   --clean      Remove old image before building
echo   --no-cache   Build without Docker layer cache
echo   --help       Show this help message
exit /b 0

:build
echo Building Docker image: %IMAGE_TAG%

if "%CLEAN%"=="true" (
    echo Removing old image...
    docker rmi %IMAGE_TAG% 2>nul
)

docker build %NO_CACHE% -t %IMAGE_TAG% .
if errorlevel 1 (
    echo ERROR: Docker build failed
    exit /b 1
)

echo.
echo Build complete: %IMAGE_TAG%
echo Run with: docker run --rm -i -v mcp-task-data:/app/data %IMAGE_TAG%
