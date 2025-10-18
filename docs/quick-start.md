---
layout: default
title: Quick Start
---

# Quick Start Guide

**Stop losing context. Start building faster.**

Get the AI orchestration framework running in 5 minutes. This guide covers both **basic setup** (templates, works on ALL MCP clients) and **advanced setup** (sub-agent orchestration, Claude Code only).

## The Problem You're Solving

By **task 5** of a complex feature, traditional AI workflows break:
- ❌ Context pollution (79k tokens by task 4)
- ❌ Token exhaustion forces session restarts
- ❌ AI forgets completed work

**Breaking point**: Traditional approaches fail at **12-15 tasks**. Task Orchestrator scales to **100+ tasks** with 97-99% token reduction.

> **📊 See quantitative examples**: [Token Reduction Examples](token-reduction-examples.md)

---

## Two Setup Paths

| Setup Type | Works With | When to Use |
|------------|-----------|-------------|
| **Basic (Templates)** | Claude Desktop, Claude Code, Cursor, Windsurf, ALL MCP clients | Simple features (1-5 tasks), any platform, learning |
| **Advanced (Sub-Agents)** | Claude Code ONLY | Complex features (6+ tasks), cross-domain coordination, 97% token reduction |

**Start with Basic Setup** - it works everywhere. Add Advanced Setup later if you need specialist coordination.

---

## Basic Setup (5 Minutes)

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

#### Option A: Claude Desktop

**Find Your Configuration File**:
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

**Add Task Orchestrator**:

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
        "${workspaceFolder}:/workspace",
        "--env",
        "AGENT_CONFIG_DIR=/workspace",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

> **Note**: Replace `${workspaceFolder}` with your project's absolute path. For Windows PowerShell: `${PWD}`, for macOS/Linux: `$(pwd)`, for Windows CMD: `%cd%`.

> **Already have MCP servers configured?** Add the `task-orchestrator` entry to your existing `mcpServers` object.

**Restart Claude Desktop**: Close and reopen to load the configuration.

#### Option B: Claude Code

Use the MCP configuration command from your project directory:

**macOS/Linux**:
```bash
claude mcp add-json task-orchestrator "{\"type\":\"stdio\",\"command\":\"docker\",\"args\":[\"run\",\"--rm\",\"-i\",\"-v\",\"mcp-task-data:/app/data\",\"-v\",\"$(pwd):/workspace\",\"-e\",\"AGENT_CONFIG_DIR=/workspace\",\"ghcr.io/jpicklyk/task-orchestrator:latest\"]}"
```

**Windows PowerShell**:
```powershell
claude mcp add-json task-orchestrator "{`"type`":`"stdio`",`"command`":`"docker`",`"args`":[`"run`",`"--rm`",`"-i`",`"-v`",`"mcp-task-data:/app/data`",`"-v`",`"${PWD}:/workspace`",`"-e`",`"AGENT_CONFIG_DIR=/workspace`",`"ghcr.io/jpicklyk/task-orchestrator:latest`"]}"
```

Claude Code will automatically configure and connect to the MCP server.

#### Option C: Cursor IDE

**Find Your Configuration File**:
- **Windows**: `%APPDATA%\Cursor\User\globalStorage\rooveterinaryinc.roo-cline\settings\cline_mcp_settings.json`
- **macOS**: `~/Library/Application Support/Cursor/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`
- **Linux**: `~/.config/Cursor/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`

**Add Task Orchestrator**:

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
        "${workspaceFolder}:/workspace",
        "--env",
        "AGENT_CONFIG_DIR=/workspace",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

> **Note**: Replace `${workspaceFolder}` with your project's absolute path. For Windows PowerShell: `${PWD}`, for macOS/Linux: `$(pwd)`, for Windows CMD: `%cd%`.

**Restart Cursor**: Close and reopen to load the configuration.

#### Option D: Windsurf

**Find Your Configuration File**:
- **Windows**: `%APPDATA%\Windsurf\User\globalStorage\rooveterinaryinc.roo-cline\settings\cline_mcp_settings.json`
- **macOS**: `~/Library/Application Support/Windsurf/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`
- **Linux**: `~/.config/Windsurf/User/globalStorage/rooveterinaryinc.roo-cline/settings/cline_mcp_settings.json`

**Add Task Orchestrator**:

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
        "${workspaceFolder}:/workspace",
        "--env",
        "AGENT_CONFIG_DIR=/workspace",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

> **Note**: Replace `${workspaceFolder}` with your project's absolute path. For Windows PowerShell: `${PWD}`, for macOS/Linux: `$(pwd)`, for Windows CMD: `%cd%`.

**Restart Windsurf**: Close and reopen to load the configuration.

> **Note**: Cursor and Windsurf use Cline (formerly Roo Cline) for MCP support. The configuration format is identical to Claude Desktop.

#### Option E: Other MCP-Compatible AI Agents

For other MCP-supporting AI agents, consult their MCP configuration documentation. Use the Docker command configuration shown above, adapting to your agent's configuration format.

### Step 4: Initialize AI Guidelines

Your AI can access Task Orchestrator best practices through MCP resources. Initialize once to enable autonomous pattern recognition:

```
Initialize Task Orchestrator using the initialize_task_orchestrator workflow
```

Or with direct invocation:
```
/task-orchestrator:initialize_task_orchestrator
```

Your AI will:
1. Read Task Orchestrator guideline resources
2. Write patterns to your AI's memory file (CLAUDE.md, .cursorrules, .windsurfrules, etc.)
3. **Detect Claude Code** and offer sub-agent setup (creates `.claude/agents/` with 10 specialists)
4. Confirm initialization

> **Why this matters**: Initialization enables autonomous template discovery, pattern recognition, and best practices. Your AI learns how to use the orchestration framework without explicit instructions every time.

> **Claude Code users**: If `.claude/` directory is detected, initialization automatically offers to set up the 3-level sub-agent orchestration system. This is optional - you can decline and use templates + workflows only.

### Step 5: Verify It Works

Ask your AI:

```
Show me an overview of my current tasks
```

Your AI should respond confirming the connection (showing no tasks initially).

## Understanding Skills and Hooks

Task Orchestrator provides a **4-tier hybrid architecture** for different operation types:

| Tier | Use For | Token Cost | Example |
|------|---------|------------|---------|
| **Direct Tools** | Single operations | 50-100 | `create_task(...)` |
| **Skills** | Coordination (2-5 tools) | 300-600 | "What's next?" |
| **Subagents** | Complex implementation | 1500-3000 | Backend Engineer |
| **Hooks** | Side effects | 0 | Auto-commit on complete |

### Quick Example - Using Skills

**Skills** are lightweight capabilities that Claude Code automatically invokes when your request matches their description. They save 60-82% tokens compared to subagents for coordination operations.

**Example: Feature Management Skill**
```
You: "What task should I work on next in this feature?"

→ Claude invokes Feature Management Skill (300 tokens)
→ Returns: "Task T4: Add authentication tests (high priority, unblocked)"

vs Subagent approach: 1400 tokens for same operation (78% savings)
```

**Example: Task Management Skill + Hook**
```
You: "Mark task T4 complete"

→ Task Management Skill coordinates (450 tokens):
  - Reads task details
  - Creates Summary section
  - Updates status to completed

→ Hook triggers automatically (0 tokens):
  - Creates git commit with task info
  - No LLM calls needed

Total: 450 tokens vs 1500 with subagent (70% savings)
```

**Token Efficiency Benefits**:
- **Skills**: 60-82% savings for coordination operations
- **Hooks**: 100% savings for automation (zero tokens)
- **Hybrid approach**: Use Skills + Hooks for maximum efficiency

**Learn More**:
- **Skills Catalog**: `.claude/skills/README.md` - Complete Skills reference
- **Decision Guide**: `docs/hybrid-architecture.md` - When to use what tier
- **Skills Guide**: `docs/skills-guide.md` - Comprehensive examples and creation

---

## Your First Task (Basic Setup)

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

**Congratulations!** You're using template-driven development. This works on **all MCP clients** (Claude Desktop, Cursor, Windsurf, etc.).

---

## Advanced Setup: Sub-Agent Orchestration (Claude Code Only)

**Skip this section** if you're not using Claude Code, or if you're just getting started. Come back when you have complex features (6+ tasks) needing specialist coordination.

### When to Add Sub-Agents

Add sub-agent orchestration when:
- ✅ You're using **Claude Code** (required)
- ✅ You have **complex features** (6+ tasks with dependencies)
- ✅ You need **cross-domain work** (database → backend → frontend → tests)
- ✅ You want **97% token reduction** for large projects

### Prerequisites

- ✅ Basic Setup complete (Steps 1-5 above)
- ✅ Claude Code installed and configured
- ✅ You've created at least one task successfully

### Step 1: Enable Sub-Agents

Ask your AI (Claude Code):

```
Run setup_claude_agents to enable sub-agent orchestration
```

This creates `.claude/agents/` directory with 10 specialist agent definitions:
- Feature Manager
- Task Manager
- Backend Engineer
- Frontend Developer
- Database Engineer
- Test Engineer
- Technical Writer
- Planning Specialist
- Bug Triage Specialist
- Code Quality Reviewer

### Step 2: Verify Agent Files

Check that `.claude/agents/` directory exists with `.md` files:

```bash
ls .claude/agents/
```

You should see:
```
backend-engineer.md
bug-triage-specialist.md
code-quality-reviewer.md
database-engineer.md
feature-manager.md
frontend-developer.md
planning-specialist.md
task-manager.md
technical-writer.md
test-engineer.md
```

### Step 3: Test Agent Routing

Create a test task and check agent recommendation:

```
Create a task for creating a users database table
```

Then:
```
Use recommend_agent to suggest which specialist should handle this task
```

Your AI should recommend: **Database Engineer**

## Your First Feature with Sub-Agents (Claude Code)

Now you're ready for 3-level orchestration. Here's a complete workflow:

### Step 1: Create a Complex Feature

```
Create a feature called "User Authentication" with the following tasks:
1. Create users database table
2. Implement user registration API
3. Implement login API with JWT
4. Add password reset flow
5. Create frontend login form
6. Create frontend registration form
7. Write API integration tests
8. Write documentation

Apply appropriate templates to each task and set up dependencies.
```

Your AI creates the feature with 8 tasks, templates applied, dependencies configured.

### Step 2: Launch Feature Manager

```
Launch the Feature Manager agent for the User Authentication feature in START mode
```

**Feature Manager** analyzes the feature and recommends:
```
Recommended next task: T1 (Create users database table)
No blockers - ready to start
```

### Step 3: Launch Task Manager

```
Launch Task Manager for task T1
```

**Task Manager**:
1. **Reads** task details + dependency summaries (if any)
2. **Routes** to Database Engineer specialist
3. **Launches** Database Engineer with focused brief

**Database Engineer**:
1. **Reads** technical-approach template section
2. **Implements** database schema
3. **Creates** 300-500 token Summary section
4. **Reports** completion

**Task Manager** reports back (2-3 sentences):
```
Created users table with authentication fields (id, username, email, password_hash).
Added indexes for email lookup. Ready for API implementation.
```

### Step 4: Continue with Feature Manager

```
Launch Feature Manager again for User Authentication
```

**Feature Manager** now recommends:
```
Recommended next task: T2 (Implement user registration API)
Dependency context: T1 Summary (400 tokens) available
```

You continue launching Task Manager for each recommended task. The cycle continues with **automatic dependency context passing**.

### What You Just Did

Instead of accumulating context linearly:
```
Traditional (fails at task 5):
T1: 5k tokens
T2: 11k tokens (includes T1)
T3: 21k tokens (includes T1+T2)
T4: 42k tokens (includes T1+T2+T3)
T5: 79k tokens (CONTEXT POLLUTION)
```

You used summary-based orchestration:
```
Sub-Agent (scales to 100+ tasks):
T1: Database Engineer works with 2k tokens, creates 400-token Summary
T2: Backend Engineer reads T1 Summary (400 tokens), creates 400-token Summary
T3: Backend Engineer reads T1 Summary (400 tokens), creates 400-token Summary
T4: Frontend Developer reads T2+T3 Summaries (800 tokens), creates 400-token Summary
T5: Frontend Developer reads T2+T3 Summaries (800 tokens), creates 400-token Summary
T6: Test Engineer reads T2+T3+T4+T5 Summaries (1,600 tokens), creates 400-token Summary
T7: Technical Writer reads T2+T3 Summaries (800 tokens), creates 400-token Summary

You (orchestrator): See 7 summaries (2,800 tokens) - NOT 79k
```

**Result**: 97% token reduction, specialists see only relevant context, work scales effortlessly.

---

## Decision Guide: When to Use What

### Use Basic Setup (Templates Only)

**Scenarios**:
- ✅ Simple features (1-5 tasks)
- ✅ Working alone on straightforward implementations
- ✅ Using any MCP client (Cursor, Windsurf, Claude Desktop)
- ✅ Learning Task Orchestrator
- ✅ Quick prototypes

**How it works**: AI discovers templates, creates structured tasks, reads sections for guidance, implements directly.

**Example**: "Create a task for user profile API" → AI creates task with templates → AI implements → Done

### Add Advanced Setup (Sub-Agents)

**Scenarios**:
- ✅ Complex features (6+ tasks with dependencies)
- ✅ Using Claude Code specifically
- ✅ Cross-domain work (database → backend → frontend → tests)
- ✅ Large projects where context management is critical
- ✅ Want 97% token reduction

**How it works**: Orchestrator launches Feature Manager → Feature Manager recommends next task → Task Manager routes to specialist → Specialist implements with summary creation → Next task reads summaries.

**Example**: "Create feature for payment processing with 9 tasks" → Feature Manager orchestrates → Specialists implement with automatic context passing → 97% token savings


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

Please analyze and create a complete project structure with features, tasks, and dependencies.
```

**Step 2: AI analyzes and proposes structure**:
```
AI: I've analyzed your PRD. Proposing:

Project: Payment Processing System

Features:
1. Stripe Integration (3 tasks) - FOUNDATIONAL
2. Checkout API (4 tasks) - Depends on F1
3. Order Management (3 tasks) - Depends on F2
4. Email Notifications (2 tasks) - Parallel to F2/F3
5. Admin Dashboard (3 tasks) - Integration layer

Total: 5 features, 15 tasks
Templates: Technical Approach, Testing Strategy applied
Dependencies: 8 blocking relationships

Proceed with creation?
```

**Step 3: Create and start**:
```
Yes, create the structure.
```

**Step 4: Choose your workflow**:

**Basic (any MCP client)**: Work through tasks yourself with template guidance
**Advanced (Claude Code)**: Launch Feature Manager for orchestrated specialist routing

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

- **[🤖 Agent Orchestration](agent-orchestration.md)** - Complete sub-agent coordination guide
- **[📊 Token Reduction Examples](token-reduction-examples.md)** - Quantitative before/after analysis
- **[📝 Templates](templates.md)** - 9 built-in templates explained
- **[🤖 AI Guidelines](ai-guidelines.md)** - How AI uses Task Orchestrator autonomously
- **[🔧 API Reference](api-reference.md)** - Complete tool documentation

### Advanced Features

- **Dependencies** - BLOCKS, RELATES_TO, IS_BLOCKED_BY relationships
- **Bulk Operations** - Create multiple tasks/features efficiently
- **Search and Filter** - Find tasks by status, priority, tags, text
- **Custom Templates** - Create team-specific documentation patterns

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

**Sub-agents not working? (Claude Code only)**
- Run `setup_claude_agents` tool
- Verify `.claude/agents/` directory exists
- Check agent files with `ls .claude/agents/`

**Docker issues?**
- Start Docker Desktop
- Pull image: `docker pull ghcr.io/jpicklyk/task-orchestrator:latest`
- Test: `docker run --rm -i -v mcp-task-data:/app/data ghcr.io/jpicklyk/task-orchestrator:latest`

**Still stuck?** See [Troubleshooting Guide](troubleshooting.md) for comprehensive solutions.

---

**You're ready!** Start with a simple task to learn the system, then scale to complex features with sub-agent orchestration when needed. 🚀
