---
layout: default
title: Quick Start
---

# Quick Start Guide

Get MCP Task Orchestrator running with Claude Desktop in under 2 minutes. This guide focuses on the fastest path to success using Docker.

## Prerequisites

- **Docker Desktop** installed and running
- **Claude Desktop** application
- **Basic CLI access** (Terminal/Command Prompt)

> **New to Docker?** [Download Docker Desktop](https://www.docker.com/products/docker-desktop/) and follow the installation guide for your platform.

---

## Step 1: Get the Docker Image

### Option A: Pull Pre-built Image (Fastest)
```bash
# Pull the latest image
docker pull mcp-task-orchestrator:latest
```

### Option B: Build Locally (Recommended for Development)
```bash
# Clone the repository
git clone https://github.com/jpicklyk/task-orchestrator.git
cd task-orchestrator

# Build using the provided script
# Windows
./scripts/docker-clean-and-build.bat

# Linux/Mac
./scripts/docker-clean-and-build.sh
```

---

## Step 2: Test the Installation

Verify the container works:

```bash
# Quick test run
docker run --rm -i -v mcp-task-data:/app/data mcp-task-orchestrator
```

You should see MCP server startup messages. Press `Ctrl+C` to stop.

---

## Step 3: Configure Claude Desktop

### Locate Your Configuration File

**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`  
**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`  
**Linux**: `~/.config/Claude/claude_desktop_config.json`

### Add the MCP Server Configuration

Open `claude_desktop_config.json` and add:

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
        "mcp-task-orchestrator"
      ]
    }
  }
}
```

> **Existing Configuration?** Add the `task-orchestrator` entry to your existing `mcpServers` object.

### Restart Claude Desktop

Close and reopen Claude Desktop to load the new configuration.

---

## Step 4: Verify Integration

Test the connection by asking Claude:

```
Can you show me an overview of my current tasks?
```

You should see a response indicating the task orchestrator is working (likely showing no tasks initially).

---

## Step 5: Create Your First Project

### Start with a Project

```
Create a new project called "My Web Application" for building a modern web app with user authentication and a dashboard
```

### Add a Feature

```
Create a feature called "User Authentication" under my web application project with proper templates
```

### Create Some Tasks

```
Create tasks for the user authentication feature:
1. Design authentication API endpoints
2. Implement user registration
3. Implement user login
4. Add password reset functionality
```

### View Your Work

```
Show me an overview of my current project structure
```

---

## Common First Commands

Once you're connected, try these commands with Claude:

### Project Management
- `"Create a new project for [your project description]"`
- `"Show me all my projects"`
- `"Get an overview of project status"`

### Task Organization
- `"Create a task to [specific work description]"`
- `"Show me all pending tasks"`
- `"Update task [description] to in-progress status"`

### Template Usage
- `"List available templates"`
- `"Apply the technical approach template to this task"`
- `"Create a task with bug investigation workflow"`

### Workflow Automation
- `"Use the create_feature_workflow to set up user management"`
- `"Apply the sprint_planning_workflow to organize my backlog"`

---

## Next Steps

### Explore Documentation
- **[Templates Guide](templates)** - Learn about 9 built-in templates
- **[Workflow Prompts](workflow-prompts)** - 5 comprehensive workflow automations
- **[API Reference](api-reference)** - Complete tool documentation

### Try Advanced Features
1. **Dependencies**: Link related tasks with blocking relationships
2. **Templates**: Apply multiple templates for comprehensive documentation
3. **Workflow Prompts**: Use built-in workflows for complex scenarios
4. **Bulk Operations**: Manage multiple tasks efficiently

### Community Resources
- **[GitHub Repository](https://github.com/jpicklyk/task-orchestrator)** - Source code and issues
- **[Community Wiki](https://github.com/jpicklyk/task-orchestrator/wiki)** - Examples and guides
- **[Discussions](https://github.com/jpicklyk/task-orchestrator/discussions)** - Questions and ideas

---

## Troubleshooting

### Claude Can't Find the Tools

**Problem**: Claude responds with "I don't have access to task management tools"

**Solutions**:
1. **Restart Claude Desktop** after adding the configuration
2. **Check Docker**: Ensure Docker Desktop is running
3. **Verify Config**: Confirm the JSON configuration is valid
4. **Check Logs**: Look for error messages in Claude Desktop

### Docker Issues

**Problem**: Docker commands fail or container won't start

**Solutions**:
```bash
# Check Docker is running
docker version

# Verify the image exists
docker images | grep mcp-task-orchestrator

# Check for port conflicts
docker ps -a

# Clean up if needed
docker system prune
```

### Configuration Problems

**Problem**: Invalid JSON in claude_desktop_config.json

**Solutions**:
1. **Validate JSON**: Use [jsonlint.com](https://jsonlint.com/) to check syntax
2. **Check Commas**: Ensure proper comma placement between entries
3. **Verify Quotes**: All strings must be in double quotes
4. **Test Minimal Config**: Start with just the task-orchestrator entry

### Connection Test

Verify your setup with this simple test:

```bash
# Test the container directly
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | \
docker run --rm -i -v mcp-task-data:/app/data mcp-task-orchestrator
```

You should see a list of available tools.

---

## Environment Variables (Optional)

Customize behavior with environment variables:

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "mcp-task-data:/app/data",
        "--env", "MCP_DEBUG=true",
        "--env", "DATABASE_PATH=/app/data/my-tasks.db",
        "mcp-task-orchestrator"
      ]
    }
  }
}
```

### Available Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `MCP_DEBUG` | Enable debug logging | `false` |
| `DATABASE_PATH` | SQLite database path | `/app/data/tasks.db` |
| `MCP_SERVER_NAME` | Server identifier | `mcp-task-orchestrator` |

---

## Success Indicators

You'll know everything is working when:

- ✅ Claude responds to task-related queries
- ✅ You can create projects, features, and tasks
- ✅ Templates are available and can be applied
- ✅ The overview command shows your project structure
- ✅ Workflow prompts provide step-by-step guidance

**Ready to dive deeper?** Check out the [Templates Guide](templates) to learn about structured documentation or explore [Workflow Prompts](workflow-prompts) for automated project management scenarios.

---

## Pre-Release Notice

**⚠️ Current Version: Pre-1.0.0 (Development)**

This is a pre-release version. The database schema may change between updates. For production use, wait for the stable 1.0.0 release.

- 📋 [Track progress to 1.0.0](https://github.com/jpicklyk/task-orchestrator/milestone/1)
- 🔔 [Get release notifications](https://github.com/jpicklyk/task-orchestrator/releases)