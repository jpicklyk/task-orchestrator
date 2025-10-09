---
layout: default
title: Quick Start
---

# Quick Start Guide

Get MCP Task Orchestrator running with Claude Desktop in under 2 minutes. This guide focuses on the fastest path to success.

## Prerequisites

- **Docker Desktop** installed and running
- **Claude Desktop** application
- **Basic CLI access** (Terminal/Command Prompt)

> **New to Docker?** [Download Docker Desktop](https://www.docker.com/products/docker-desktop/) - free for personal use.

---

## Step 1: Get the Docker Image

Pull the latest production image:

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

> **Want to build from source or use a development version?** See the [Installation Guide](installation-guide) for detailed instructions.

---

## Step 2: Test the Installation

Verify the image works:

```bash
docker run --rm -i -v mcp-task-data:/app/data ghcr.io/jpicklyk/task-orchestrator:latest
```

You should see MCP server startup messages. Press `Ctrl+C` to stop.

---

## Step 3: Configure Claude Desktop

### Find Your Configuration File

**Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
**Linux**: `~/.config/Claude/claude_desktop_config.json`

### Add Task Orchestrator

Open the file and add this configuration:

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

> **Already have MCP servers configured?** Add the `task-orchestrator` entry to your existing `mcpServers` object.

### Restart Claude Desktop

Close and reopen Claude Desktop to load the configuration.

---

## Step 4: Verify the Connection

Open Claude Desktop and ask:

```
Show me an overview of my current tasks
```

Claude should respond confirming the task orchestrator is working (showing no tasks initially).

---

## Step 5: Create Your First Project

### Start Simple

Try these commands in Claude Desktop:

**Create a project**:
```
Create a new project called "My Web App" for building a web application
with user authentication and a dashboard
```

**Add a feature with templates**:
```
Create a feature called "User Authentication" with appropriate templates
```

**Create tasks**:
```
Create tasks for implementing user login, registration, and password reset
```

**View your work**:
```
Show me an overview of my project structure
```

---

## AI Guidelines Initialization

Claude will automatically initialize AI guidelines on first connection to Task Orchestrator. This enables:

- **Autonomous pattern recognition** - Claude understands your intent from natural language
- **Smart template discovery** - Automatically finds and applies appropriate templates
- **Workflow integration** - Seamless use of built-in workflow patterns

> **Learn more**: See [AI Guidelines](ai-guidelines) for details on how Claude uses Task Orchestrator autonomously.

---

## What You Can Do

### Project Organization
- Create hierarchical projects with features and tasks
- Apply templates for consistent documentation
- Track status, priority, and complexity

### Template-Driven Documentation
- 9 built-in templates for common scenarios
- Automatic template discovery by Claude
- Compose multiple templates for comprehensive coverage

### Workflow Automation
- 6 built-in workflow prompts for complex scenarios
- Autonomous pattern application
- Dependency management

### Natural Language Control
Simply ask Claude:
- "Create a task to implement the login API"
- "Show me all high-priority pending tasks"
- "Apply the technical approach template to this task"
- "What should I work on next?"

---

## Next Steps

### Learn the System

1. **[AI Guidelines](ai-guidelines)** - How Claude uses Task Orchestrator autonomously
2. **[Templates Guide](templates)** - 9 built-in templates for structured documentation
3. **[Workflow Prompts](workflow-prompts)** - 6 workflow automations for complex scenarios

### Explore Advanced Features

- **Dependencies**: Link related tasks with BLOCKS, IS_BLOCKED_BY relationships
- **Bulk Operations**: Create and manage multiple tasks efficiently
- **Search and Filter**: Find tasks by status, priority, tags, or text
- **Custom Templates**: Create team-specific documentation patterns

### Get Help

- **[Troubleshooting Guide](troubleshooting)** - Solutions to common issues
- **[Installation Guide](installation-guide)** - Detailed setup instructions
- **[API Reference](api-reference)** - Complete tool documentation
- **[GitHub Issues](https://github.com/jpicklyk/task-orchestrator/issues)** - Report bugs or request features

---

## Troubleshooting Quick Tips

**Claude can't find the tools?**
- Restart Claude Desktop after configuration changes
- Verify Docker Desktop is running
- Check JSON syntax with [jsonlint.com](https://jsonlint.com/)

**Docker issues?**
- Ensure Docker Desktop is running: `docker version`
- Verify the image exists: `docker images | grep task-orchestrator`

**Need detailed help?** See the [Troubleshooting Guide](troubleshooting) for comprehensive solutions.

---

## Pre-Release Notice

**⚠️ Current Version: Pre-1.0.0 (Development)**

This is a pre-release version. The database schema may change between updates. For production use, wait for the stable 1.0.0 release.

- 📋 [Track progress to 1.0.0](https://github.com/jpicklyk/task-orchestrator/milestone/1)
- 🔔 [Get release notifications](https://github.com/jpicklyk/task-orchestrator/releases)

---

**Ready to dive deeper?** Start with [AI Guidelines](ai-guidelines) to understand how Claude works with Task Orchestrator, or explore [Templates](templates) for structured documentation patterns.
