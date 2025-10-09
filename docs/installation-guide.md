---
layout: default
title: Installation Guide
---

# Installation Guide

Comprehensive installation instructions for MCP Task Orchestrator across all platforms and deployment scenarios. This guide covers production deployments, development setups, building from source, and advanced configuration.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Production Installation](#production-installation)
- [Development Installation](#development-installation)
- [Building from Source](#building-from-source)
- [Platform-Specific Instructions](#platform-specific-instructions)
- [Configuration Reference](#configuration-reference)
- [Advanced Configuration](#advanced-configuration)
- [Troubleshooting Installation](#troubleshooting-installation)

---

## Prerequisites

### Required
- **Docker Desktop** 20.10 or later
- **Docker Compose** 1.29+ (included with Docker Desktop)
- **Minimum 4GB RAM** allocated to Docker
- **500MB free disk space** (for image and database)

### Platform Requirements

**Windows**:
- Windows 10/11 Pro, Enterprise, or Education (64-bit)
- WSL 2 enabled
- Hyper-V enabled (for Windows containers)

**macOS**:
- macOS 11 (Big Sur) or later
- Apple Silicon or Intel processor

**Linux**:
- 64-bit distribution (Ubuntu 20.04+, Debian 11+, or equivalent)
- Kernel 5.0 or later

> **New to Docker?** [Download Docker Desktop](https://www.docker.com/products/docker-desktop/) and follow the official installation guide for your platform.

---

## Production Installation

### Step 1: Pull the Production Image

```bash
# Pull the latest stable release
docker pull ghcr.io/jpicklyk/task-orchestrator:latest

# Or pull a specific version for guaranteed stability
docker pull ghcr.io/jpicklyk/task-orchestrator:1.0.0

# Or pull latest from main branch (pre-release)
docker pull ghcr.io/jpicklyk/task-orchestrator:main
```

### Step 2: Create Data Volume

Create a persistent volume for your task database:

```bash
# Create named volume
docker volume create mcp-task-data

# Verify volume was created
docker volume ls | grep mcp-task-data
```

### Step 3: Verify Installation

Test the image:

```bash
# Quick test run
docker run --rm -i -v mcp-task-data:/app/data ghcr.io/jpicklyk/task-orchestrator:latest

# You should see MCP server startup messages
# Press Ctrl+C to stop
```

### Step 4: Configure Claude Desktop

Follow the [Quick Start Guide](quick-start#step-3-configure-claude-desktop) for Claude Desktop configuration.

---

## Development Installation

### Step 1: Clone Repository

```bash
# Clone the repository
git clone https://github.com/jpicklyk/task-orchestrator.git
cd task-orchestrator
```

### Step 2: Build Development Image

**Windows**:
```bash
# Use the provided build script
./scripts/docker-clean-and-build.bat
```

**macOS / Linux**:
```bash
# Manual build
docker build -t mcp-task-orchestrator:dev .
```

### Step 3: Create Development Volume

```bash
# Create separate dev volume
docker volume create mcp-task-dev-data
```

### Step 4: Test Development Build

```bash
# Run development image
docker run --rm -i -v mcp-task-dev-data:/app/data mcp-task-orchestrator:dev

# Or with debug logging
docker run --rm -i -v mcp-task-dev-data:/app/data \
  --env MCP_DEBUG=true \
  mcp-task-orchestrator:dev
```

### Step 5: Configure for Development

Update `claude_desktop_config.json` to use dev image:

```json
{
  "mcpServers": {
    "task-orchestrator-dev": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "--volume",
        "mcp-task-dev-data:/app/data",
        "--env",
        "MCP_DEBUG=true",
        "mcp-task-orchestrator:dev"
      ]
    }
  }
}
```

---

## Building from Source

### Prerequisites for Building

- **Git** 2.30+
- **Docker** 20.10+
- **JDK 17+** (optional, for local Gradle builds)
- **Gradle 8.5+** (wrapper included)

### Build Process

#### 1. Clone and Prepare

```bash
# Clone repository
git clone https://github.com/jpicklyk/task-orchestrator.git
cd task-orchestrator

# Check out specific version (optional)
git checkout v1.0.0
```

#### 2. Build Docker Image

**Using Build Script (Windows)**:
```batch
# Clean and build
scripts\docker-clean-and-build.bat

# This script:
# 1. Removes old containers/volumes
# 2. Builds fresh image
# 3. Creates new data volume
# 4. Runs verification test
```

**Manual Docker Build**:
```bash
# Standard build
docker build -t mcp-task-orchestrator:dev .

# Build with specific tag
docker build -t mcp-task-orchestrator:1.0.0 .

# Build with build args
docker build \
  --build-arg BUILD_VERSION=1.0.0 \
  --build-arg BUILD_TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ") \
  -t mcp-task-orchestrator:custom \
  .
```

#### 3. Build JAR Locally (Optional)

```bash
# Build JAR with Gradle
./gradlew clean build

# JAR will be in: build/libs/task-orchestrator-*.jar

# Run locally without Docker
java -jar build/libs/task-orchestrator-*.jar
```

#### 4. Run Tests

```bash
# Run all tests
./gradlew test

# Run specific test suite
./gradlew test --tests "io.github.jpicklyk.mcptask.integration.*"

# Run with coverage
./gradlew test jacocoTestReport
```

---

## Platform-Specific Instructions

### Windows

#### Docker Desktop Configuration

1. **Enable WSL 2**:
   ```powershell
   # In PowerShell (Administrator)
   wsl --install
   wsl --set-default-version 2
   ```

2. **Allocate Resources**:
   - Open Docker Desktop
   - Settings → Resources → Advanced
   - Set Memory: 4GB minimum
   - Set CPUs: 2 minimum

3. **File Sharing**:
   - Settings → Resources → File Sharing
   - Ensure drive with project is shared

#### Claude Desktop Config Location

```powershell
# Config file location
%APPDATA%\Claude\claude_desktop_config.json

# Open in notepad
notepad %APPDATA%\Claude\claude_desktop_config.json
```

#### Build Script

```batch
# Use Windows batch script
cd task-orchestrator
scripts\docker-clean-and-build.bat
```

### macOS

#### Docker Desktop Configuration

1. **Install Docker Desktop**:
   - Download from [Docker website](https://www.docker.com/products/docker-desktop/)
   - Install DMG
   - Start Docker Desktop

2. **Allocate Resources**:
   - Docker Desktop → Preferences → Resources
   - Set Memory: 4GB minimum
   - Set CPUs: 2 minimum

#### Claude Desktop Config Location

```bash
# Config file location
~/Library/Application Support/Claude/claude_desktop_config.json

# Open in default editor
open ~/Library/Application\ Support/Claude/claude_desktop_config.json
```

#### Build Commands

```bash
# Clone and build
git clone https://github.com/jpicklyk/task-orchestrator.git
cd task-orchestrator
docker build -t mcp-task-orchestrator:dev .
```

### Linux

#### Docker Installation

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install docker.io docker-compose

# Start Docker service
sudo systemctl start docker
sudo systemctl enable docker

# Add user to docker group (avoid sudo)
sudo usermod -aG docker $USER
newgrp docker
```

#### Claude Desktop Config Location

```bash
# Config file location
~/.config/Claude/claude_desktop_config.json

# Create directory if needed
mkdir -p ~/.config/Claude

# Edit config
nano ~/.config/Claude/claude_desktop_config.json
```

#### Build Commands

```bash
# Clone and build
git clone https://github.com/jpicklyk/task-orchestrator.git
cd task-orchestrator
docker build -t mcp-task-orchestrator:dev .
```

---

## Configuration Reference

### Environment Variables

| Variable | Description | Default | Example |
|----------|-------------|---------|---------|
| `MCP_DEBUG` | Enable debug logging | `false` | `true` |
| `DATABASE_PATH` | SQLite database file path | `/app/data/tasks.db` | `/app/data/my-tasks.db` |
| `MCP_SERVER_NAME` | Server identifier in MCP | `mcp-task-orchestrator` | `task-orchestrator-dev` |
| `LOG_LEVEL` | Logging verbosity | `INFO` | `DEBUG`, `WARN`, `ERROR` |

### Claude Desktop Configuration

#### Basic Configuration

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "--volume",
        "mcp-task-data:/app/data",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

#### Configuration with Environment Variables

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "--volume",
        "mcp-task-data:/app/data",
        "--env",
        "MCP_DEBUG=true",
        "--env",
        "DATABASE_PATH=/app/data/my-tasks.db",
        "--env",
        "LOG_LEVEL=DEBUG",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

#### Development Configuration

```json
{
  "mcpServers": {
    "task-orchestrator-dev": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "--volume",
        "mcp-task-dev-data:/app/data",
        "--env",
        "MCP_DEBUG=true",
        "mcp-task-orchestrator:dev"
      ]
    }
  }
}
```

---

## Advanced Configuration

### Multiple Database Instances

Run separate instances for different projects:

```json
{
  "mcpServers": {
    "task-orchestrator-project-a": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "mcp-task-project-a:/app/data",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    },
    "task-orchestrator-project-b": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "mcp-task-project-b:/app/data",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

### Custom Database Location

Mount host directory instead of named volume:

**Windows**:
```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "C:/Users/YourName/task-data:/app/data",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

**macOS/Linux**:
```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "/Users/yourname/task-data:/app/data",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

### Network Configuration

For integration with other Docker containers:

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "mcp-task-data:/app/data",
        "--network", "my-custom-network",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

---

## Troubleshooting Installation

### Docker Issues

#### Docker Daemon Not Running

```bash
# Check Docker status
docker version

# Start Docker Desktop (GUI)
# Or on Linux:
sudo systemctl start docker
```

#### Image Pull Failures

```bash
# Check network connectivity
ping ghcr.io

# Try pulling with explicit tag
docker pull ghcr.io/jpicklyk/task-orchestrator:latest

# Check Docker Hub rate limits
docker info | grep -i rate
```

#### Volume Permission Issues

```bash
# List volumes
docker volume ls

# Inspect volume
docker volume inspect mcp-task-data

# Remove and recreate if corrupted
docker volume rm mcp-task-data
docker volume create mcp-task-data
```

### Build Issues

#### Gradle Build Failures

```bash
# Clean and rebuild
./gradlew clean build --refresh-dependencies

# Skip tests if needed
./gradlew build -x test

# Check Gradle version
./gradlew --version
```

#### Docker Build Failures

```bash
# Build with no cache
docker build --no-cache -t mcp-task-orchestrator:dev .

# Check Dockerfile syntax
docker build --check -t mcp-task-orchestrator:dev .

# View build logs
docker build -t mcp-task-orchestrator:dev . 2>&1 | tee build.log
```

### Configuration Issues

#### Invalid JSON

```bash
# Validate JSON syntax
# Use online tool: https://jsonlint.com/

# Or with Python
python -m json.tool < claude_desktop_config.json

# Or with jq
jq . < claude_desktop_config.json
```

#### Path Issues

**Windows**: Use forward slashes or escaped backslashes:
```json
"--volume", "C:/Users/Name/data:/app/data"
// OR
"--volume", "C:\\Users\\Name\\data:/app/data"
```

**macOS/Linux**: Use absolute paths:
```json
"--volume", "/home/username/data:/app/data"
```

### Connection Testing

#### Direct Container Test

```bash
# Test production image
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | \
  docker run --rm -i -v mcp-task-data:/app/data \
  ghcr.io/jpicklyk/task-orchestrator:latest

# Test development image
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | \
  docker run --rm -i -v mcp-task-dev-data:/app/data \
  mcp-task-orchestrator:dev

# Should output JSON list of available MCP tools
```

#### Debug Mode

```bash
# Run with debug logging
docker run --rm -i \
  -v mcp-task-data:/app/data \
  --env MCP_DEBUG=true \
  --env LOG_LEVEL=DEBUG \
  ghcr.io/jpicklyk/task-orchestrator:latest
```

---

## Next Steps

After successful installation:

1. **Return to [Quick Start](quick-start)** to configure Claude Desktop
2. **Review [AI Guidelines](ai-guidelines)** for initialization and usage patterns
3. **Explore [Templates](templates)** for structured documentation
4. **Check [Troubleshooting](troubleshooting)** for common issues

---

## Additional Resources

- **[Quick Start Guide](quick-start)** - Fast setup for production use
- **[Troubleshooting Guide](troubleshooting)** - Comprehensive problem resolution
- **[Developer Guides](developer-guides)** - Contributing and development setup
- **[GitHub Repository](https://github.com/jpicklyk/task-orchestrator)** - Source code and issues
