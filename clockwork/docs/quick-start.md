---
layout: default
title: Quick Start
---

# Quick Start Guide

**Stop losing context. Start building faster.**

Get the task management framework running in 5 minutes.

## The Problem You're Solving

By **task 5** of a complex feature, traditional AI workflows break:
- ❌ Context pollution (79k tokens by task 4)
- ❌ Token exhaustion forces session restarts
- ❌ AI forgets completed work

**Breaking point**: Traditional approaches fail at **12-15 tasks**. Task Orchestrator scales to **100+ tasks** with 97-99% token reduction.

> **📊 See quantitative examples**: [Token Reduction Examples](token-reduction-examples.md)

---

## Setup (5 Minutes)

### Prerequisites

- **Docker Desktop** installed and running
- **MCP-compatible AI agent** (Claude Desktop, Claude Code, Cursor, Windsurf)
- **Basic CLI access** (Terminal/Command Prompt)

> **New to Docker?** [Download Docker Desktop](https://www.docker.com/products/docker-desktop/) - free for personal use.

### Step 1: Get the Docker Image

Pull the latest production image:

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

> **Want to build from source or use a development version?** See the [Installation Guide](installation-guide) for detailed instructions.

### Step 2: Test the Installation

Verify the image works:

```bash
docker run --rm -i -v mcp-task-data:/app/data ghcr.io/jpicklyk/task-orchestrator:latest
```

You should see MCP server startup messages. Press `Ctrl+C` to stop.

### Step 3: Configure Your AI Agent

Choose your AI platform and configure accordingly:

#### Option A: Claude Code (Primary Supported Platform)

Use the universal MCP configuration command from your project directory (works on macOS, Linux, Windows):

```bash
claude mcp add-json task-orchestrator '{"type":"stdio","command":"docker","args":["run","--rm","-i","-v","mcp-task-data:/app/data","-v","./.taskorchestrator:/project/.taskorchestrator","-e","AGENT_CONFIG_DIR=/project","ghcr.io/jpicklyk/task-orchestrator:latest"]}'
```

This single command works across all platforms. Claude Code will automatically configure and connect to the MCP server.

#### Option B: Other MCP Clients (Cursor, Windsurf, Claude Desktop) - Untested

> **⚠️ Important**: These platforms are NOT actively tested. The core MCP protocol (persistence, templates, task management) should work via MCP protocol, but we cannot verify functionality. Advanced features (skills, subagents, hooks, coordinate_feature_development workflow) require Claude Code and will not work on other platforms.

**Configuration Format** (for Claude Desktop, Cursor, Windsurf):

All these platforms use similar JSON configuration. Find your configuration file:

**Claude Desktop**:
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

**Cursor**:
- **Windows**: `%APPDATA%\Cursor\User\globalStorage\rooveterinaryinc.roo-cline\settings\cline_mcp_settings.json`
- **macOS**: `~/Library/Application Support/Cursor/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`
- **Linux**: `~/.config/Cursor/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`

**Windsurf**:
- **Windows**: `%APPDATA%\Windsurf\User\globalStorage\rooveterinaryinc.roo-cline\settings\cline_mcp_settings.json`
- **macOS**: `~/Library/Application Support/Windsurf/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`
- **Linux**: `~/.config/Windsurf/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`

**Add Task Orchestrator to your configuration file**:

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
        "--volume",
        "/absolute/path/to/your/project/.taskorchestrator:/project/.taskorchestrator",
        "--env",
        "AGENT_CONFIG_DIR=/project",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

> **Note**: Replace `/absolute/path/to/your/project` with your project's actual path. For Windows: `D:/Users/username/project`, for macOS/Linux: `/Users/username/project` or `/home/username/project`.

> **Already have MCP servers configured?** Add the `task-orchestrator` entry to your existing `mcpServers` object.

**Restart your application** (Claude Desktop, Cursor, or Windsurf): Close and reopen to load the configuration.

> **Note**: Cursor and Windsurf use Cline (formerly Roo Cline) for MCP support, which has a different configuration location than Claude Desktop.

### Step 4: Verify It Works

Ask your AI:

```
Show me an overview of my current tasks
```

Your AI should respond confirming the connection (showing no tasks initially).

## Your First Task

You're now ready for template-driven development. Try this:

```
Create a task for implementing user login API with appropriate templates
```

Your AI will:
1. **Discover templates** with `list_templates()`
2. **Create task** with technical-approach + testing-strategy templates
3. **Show you** the structured sections created

To implement:
```
Start working on the login API task
```

Your AI will:
1. **Read template sections** for guidance
2. **Implement** the code directly
3. **Mark complete** when finished

**Congratulations!** You're using template-driven development.

---

## Querying Container Details

Task Orchestrator provides multiple ways to query container data, optimized for different use cases:

### Show Details (Scoped Overview)

Get a hierarchical view of a specific container without section content:

```bash
# Get feature with tasks
query_container operation="overview" containerType="feature" id="<feature-uuid>"
```

**Returns:**
- Feature metadata (name, status, priority, summary)
- List of tasks (minimal: id, title, status, priority, complexity)
- Task counts by status
- NO section content (token efficient: ~1.2k tokens)

**Use cases:**
- "Show me what's in Feature X"
- "What's the status of Project Y?"
- "What tasks does Feature Z have?"

### List All (Global Overview)

Get an overview of all entities:

```bash
# List all features
query_container operation="overview" containerType="feature"
```

**Returns:**
- Array of all features
- Minimal fields for each
- NO child entities

### Full Documentation (Get with Sections)

Get complete entity with all section content:

```bash
# Get feature with full documentation
query_container operation="get" containerType="feature" id="<uuid>" includeSections=true
```

**Returns:**
- Complete feature metadata
- All sections with full content
- High token cost (~18k+ tokens)

**Use this ONLY when:**
- User explicitly asks for documentation
- User needs to read section content
- Editing sections

### Comparison

| Method | Token Cost | Use When |
|--------|------------|----------|
| Scoped overview | ~1.2k | "Show me Feature X" (hierarchical view) |
| Global overview | ~500-2k | "List all features" |
| Get with sections | ~18k+ | "Show me Feature X documentation" |

**Recommendation:** Default to scoped overview for "show details" queries - you get more context for fewer tokens.

### Example Workflow: Feature Status Check

```bash
# 1. List all features to find the one you want
query_container operation="overview" containerType="feature"

# 2. Get details on specific feature (scoped overview)
query_container operation="overview" containerType="feature" id="<uuid>"
# Returns: feature + task list + task counts (~1.2k tokens)

# 3. If you need full documentation (rare):
query_container operation="get" containerType="feature" id="<uuid>" includeSections=true
# Returns: feature + all sections (~18k tokens)
```

**Token efficiency:** Steps 1-2 use ~1.7k tokens vs ~18k if you jumped to step 3.

---

## Common Workflows

### Creating Your First Project

**For new software development**:
```
Create a new project called "E-Commerce Platform" for building an online store
```

Your AI creates the project and suggests next steps.

**For existing codebases**:
```
Create a project called "Legacy System Refactoring" to track modernization work
```

### Creating Features and Tasks

**Simple feature**:
```
Create a feature called "User Profile Management" with appropriate templates
```

**Feature with tasks**:
```
Create a feature for payment processing with these tasks:
1. Set up Stripe integration
2. Implement checkout flow
3. Add order confirmation emails
4. Write integration tests
```

### Working with Templates

**Discover available templates**:
```
Show me available templates for tasks
```

**Apply templates to existing task**:
```
Add the technical-approach and testing-strategy templates to the login API task
```

### Managing Dependencies

**Create blocking dependency**:
```
Task "Login API" blocks task "Frontend Login Form"
```

**Check what you can work on**:
```
Show me tasks that are ready to start (not blocked)
```

---

## PRD-Driven Development (Recommended Pattern)

**Product Requirements Documents (PRDs) provide the most effective workflow** for AI-assisted development. Your AI analyzes the entire requirement at once, creating optimal task breakdown and dependencies.

### Why PRD-Driven Works Best

- **Complete Context**: AI analyzes entire requirements at once for intelligent breakdown
- **Automatic Decomposition**: Features, tasks, dependencies created systematically
- **Template Application**: AI applies appropriate templates based on content
- **Proper Sequencing**: Dependencies based on technical requirements, not guesswork
- **Better Results**: AI makes informed decisions with full context

### How It Works

**Step 1: Share your PRD**:
```
I have a PRD for a payment processing system. [Paste or attach PRD]

Please analyze and create a feature with tasks and dependencies.
```

**Step 2: AI creates structure**:
AI creates feature and tasks using MCP tools with appropriate templates

**Step 3: Implement**:
Work through tasks with template guidance, using `get_next_task` to find unblocked work

### Example PRD

```markdown
# E-commerce Checkout PRD

## Overview
Modern checkout with payment processing, inventory validation, order confirmation.

## Requirements
- Real-time inventory checking
- Multiple payment methods (Stripe, PayPal, Apple Pay)
- Email confirmations
- Order tracking
- Admin order management

## Technical Constraints
- Integrate with existing inventory API
- Stripe for payments
- SendGrid for email
- Support 1000 concurrent users
```

**AI creates**:
- Project: E-commerce Checkout System
- 5 features with 17 tasks
- Templates applied based on content
- 8 blocking dependencies
- Recommended implementation sequence

### PRD Best Practices

**Good PRD includes**:
- Clear functional requirements
- Technical constraints (APIs, libraries, performance targets)
- User flows and integration points
- Success criteria and acceptance tests

**PRD format tips**:
- Use structured markdown (headers, lists, sections)
- Include technical details (not just user stories)
- Specify non-functional requirements (performance, security)
- Define clear acceptance criteria

> **Tip**: For large PRDs, your AI can process sections incrementally while maintaining full context.

---

## What's Next

### Learn More

- **[📝 Templates](templates.md)** - 9 built-in templates explained
- **[🤖 AI Guidelines](ai-guidelines.md)** - How AI uses Task Orchestrator autonomously
- **[🔧 API Reference](api-reference.md)** - Complete tool documentation (14 MCP tools)
- **[📄 Status Progression](status-progression.md)** - Status workflow guide with examples

### Advanced Features

- **Dependencies** - BLOCKS, RELATES_TO, IS_BLOCKED_BY relationships
- **Bulk Operations** - Create multiple tasks/features efficiently
- **Search and Filter** - Find tasks by status, priority, tags, text
- **Custom Templates** - Create team-specific workflow templates and quality gates

### Get Help

- **[🆘 Troubleshooting](troubleshooting.md)** - Common issues and solutions
- **[🔧 Installation Guide](installation-guide.md)** - Detailed setup for all platforms
- **[💬 GitHub Discussions](https://github.com/jpicklyk/task-orchestrator/discussions)** - Ask questions
- **[🐛 Report Issues](https://github.com/jpicklyk/task-orchestrator/issues)** - Bug reports and feature requests

---

## Quick Troubleshooting

**AI can't find tools?**
- Restart your AI after configuration changes
- Verify Docker is running: `docker version`
- Check JSON syntax: [jsonlint.com](https://jsonlint.com/)

**Docker issues?**
- Start Docker Desktop
- Pull image: `docker pull ghcr.io/jpicklyk/task-orchestrator:latest`
- Test: `docker run --rm -i -v mcp-task-data:/app/data ghcr.io/jpicklyk/task-orchestrator:latest`

**Still stuck?** See [Troubleshooting Guide](troubleshooting.md) for comprehensive solutions.
