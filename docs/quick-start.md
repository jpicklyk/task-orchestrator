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

| Setup Type | Tested Platform | When to Use |
|------------|-----------------|-------------|
| **Basic (MCP Protocol)** | Claude Code (tested), Other MCP clients (untested) | Core persistence, templates, task management |
| **Advanced (Orchestration)** | Claude Code ONLY | Full orchestration with skills, subagents, hooks, coordinate_feature_development workflow |

**Claude Code is the primary supported platform** with full testing and feature access. Other MCP clients can use core MCP protocol features (persistence, templates, task management) but advanced orchestration features are Claude Code-specific.

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

#### Option A: Claude Code (Primary Supported Platform)

Use the universal MCP configuration command from your project directory (works on macOS, Linux, Windows):

```bash
claude mcp add-json task-orchestrator '{"type":"stdio","command":"docker","args":["run","--rm","-i","-v","mcp-task-data:/app/data","-v",".:/project","-e","AGENT_CONFIG_DIR=/project","ghcr.io/jpicklyk/task-orchestrator:latest"]}'
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
        "/absolute/path/to/your/project:/project",
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
3. **Detect Claude Code** and offer orchestration setup (creates `.claude/agents/`, skills, and hooks)
4. Confirm initialization

> **Why this matters**: Initialization enables autonomous template discovery, pattern recognition, and best practices. Your AI learns how to use the orchestration framework without explicit instructions every time.

> **Claude Code users**: When you install the Task Orchestrator plugin via marketplace, you get:
> - 4 subagents (Feature Architect, Implementation Specialist, Planning Specialist, Senior Engineer)
> - 6+ skills for lightweight coordination (60-82% token savings)
> - Hooks for workflow automation
> - **Task Orchestrator communication style plugin** - Professional coordination communication with phase labels and status indicators (auto-activates)
> - Access to `coordinate_feature_development` workflow
>
> **💡 Tip**: The communication style plugin automatically activates at session start, providing clearer orchestration coordination (phase labels, status indicators, concise progress updates).

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
| **Direct Tools** | Single operations | 50-100 | `manage_container(...)` |
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
- **Decision Guide**: `docs/agent-architecture.md` - When to use what tier
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

### When to Use Advanced Features

Advanced features (Skills, Subagents, Hooks) become available when:
- ✅ You're using **Claude Code** (required)
- ✅ You have **complex features** (6+ tasks with dependencies)
- ✅ You need **cross-domain work** (database → backend → frontend → tests)
- ✅ You want **97% token reduction** for large projects

### Prerequisites

- ✅ Basic Setup complete (Steps 1-5 above)
- ✅ Claude Code installed and configured
- ✅ Plugin installed via plugin marketplace

### Step 1: Plugin Provides Everything

When you install via the plugin marketplace:

The plugin automatically provides:
- **`.claude/agents/task-orchestrator/`** directory with 4 subagent definitions:
  - **Feature Architect** (Opus) - Complex feature design and analysis
  - **Implementation Specialist** (Haiku) - General implementation tasks (default, fast)
  - **Planning Specialist** (Sonnet) - Task breakdown and dependency planning
  - **Senior Engineer** (Sonnet) - Complex debugging, architecture, unblocking
- **`.claude/skills/`** directory with coordination skills (feature-orchestration, task-orchestration, etc.)
- **Communication style** for orchestration mode
- **Hooks** for workflow automation

### Step 2: Verify Installation

Check that directories exist:

```bash
ls .claude/agents/task-orchestrator/
ls .claude/skills/
```

You should see agent files:
```
feature-architect.md
implementation-specialist.md
planning-specialist.md
senior-engineer.md
```

And skills directories with SKILL.md files.

### Step 3: Test Orchestration

Try the main v2.0 workflow:

```
Create a plan file for a user authentication feature, then run coordinate_feature_development to orchestrate it
```

**What happens:**
1. **Feature Architect** (Opus) analyzes plan → creates feature with rich context
2. **Planning Specialist** (Sonnet) breaks down feature → creates dependency-aware tasks
3. Returns structured feature ready for implementation

## Your First Feature with Orchestration (Claude Code)

Now you're ready for full orchestration using the **Plan → Orchestrate → Execute** pattern.

### Step 1: Create Your Plan

Create a plan file (markdown or text) describing your feature:

**Example: `auth-feature.md`**
```markdown
# User Authentication Feature
Build complete JWT-based authentication system.

## Requirements
- User registration with email validation
- Login with JWT tokens
- Password reset flow
- Secure password hashing (bcrypt)
- Rate limiting on login attempts

## Technical Stack
- Backend: Kotlin + Ktor
- Database: PostgreSQL
- Email: SendGrid
```

### Step 2: Run coordinate_feature_development

```
Run coordinate_feature_development with my auth-feature.md plan file
```

**What happens automatically:**

**Phase 1: Feature Architecture** (Feature Architect - Opus)
- Analyzes your plan for technical requirements
- Creates feature with comprehensive summary and description
- Applies appropriate templates
- Returns feature ID

**Phase 2: Task Breakdown** (Planning Specialist - Sonnet)
- Breaks feature into 5-8 focused tasks
- Applies task templates (technical-approach, testing-strategy, etc.)
- Sets up dependency chains (database → API → frontend)
- Tags tasks for specialist routing

**Result:** Feature with 5-8 well-structured tasks, ready for execution.

### Step 3: Execute with "What's Next?"

```
What's next?
```

**AI (using Feature Management Skill):**
```
Task 1: Database schema for users table [PENDING]
No blockers - ready to start.
```

**AI automatically:**
1. Routes to **Implementation Specialist** (Haiku) by default
2. Implementation Specialist reads task context + templates
3. Implements code
4. Creates 300-500 token Summary section
5. Marks task complete
6. Returns brief report

```
What's next?
```

**AI:**
```
Task 2: User registration API [PENDING]
Dependencies satisfied. Reading Task 1 summary (400 tokens)...
```

**Continues automatically** with summary-based context passing.

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
Orchestrated (scales to 100+ tasks):
T1: Implementation Specialist works with 2k tokens, creates 400-token Summary
T2: Implementation Specialist reads T1 Summary (400 tokens), creates 400-token Summary
T3: Implementation Specialist reads T1 Summary (400 tokens), creates 400-token Summary
T4: Implementation Specialist reads T2+T3 Summaries (800 tokens), creates 400-token Summary
T5: Implementation Specialist reads T2+T3 Summaries (800 tokens), creates 400-token Summary
T6: Implementation Specialist reads T2+T3+T4+T5 Summaries (1,600 tokens), creates 400-token Summary
T7: Implementation Specialist reads T2+T3 Summaries (800 tokens), creates 400-token Summary

You: See 7 summaries (2,800 tokens) - NOT 79k
```

**Result**: 97% token reduction, specialists see only relevant context, work scales effortlessly.

> **Custom Specialists**: You can configure custom specialist routing (Backend Engineer, Frontend Developer, Database Engineer, etc.) via `.taskorchestrator/agent-mapping.yaml`. See [Agent Architecture Guide](agent-architecture.md) for details.

---

## Decision Guide: When to Use What

### Use Basic Setup (MCP Protocol Only)

**Scenarios**:
- ✅ Simple features (1-5 tasks)
- ✅ Working alone on straightforward implementations
- ✅ Using MCP clients other than Claude Code (untested but should work)
- ✅ Learning Task Orchestrator basics
- ✅ Quick prototypes

**How it works**: AI discovers templates, creates structured tasks, reads sections for guidance, implements directly using core MCP tools.

**Example**: "Create a task for user profile API" → AI creates task with templates → AI implements → Done

**Limitations**: No skills, subagents, hooks, or coordinate_feature_development workflow.

### Add Advanced Setup (Full Orchestration - Claude Code Only)

**Scenarios**:
- ✅ Complex features (6+ tasks with dependencies)
- ✅ Using **Claude Code specifically** (required)
- ✅ Cross-domain work (database → backend → frontend → tests)
- ✅ Large projects where context management is critical
- ✅ Want 97% token reduction with summary-based context passing

**How it works**:
1. Create plan file with requirements
2. Run `coordinate_feature_development` → Feature Architect (Opus) + Planning Specialist (Sonnet)
3. Say "What's next?" → Skills + Subagents coordinate automatically
4. Implementation Specialist (Haiku) implements with summary creation
5. Dependency context passed as 400-token summaries (not 5k+ full contexts)

**Example**: "Create plan for payment processing" → `coordinate_feature_development` → Feature with 9 tasks → "What's next?" → Automatic orchestration → 97% token savings


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

**Claude Code (Recommended - Full Orchestration)**:

**Step 1: Create plan file**:
Save your PRD as `payment-system.md`

**Step 2: Run coordinate_feature_development**:
```
Run coordinate_feature_development with my payment-system.md plan file
```

**What happens automatically:**
- Feature Architect (Opus) analyzes PRD → Creates feature with rich context
- Planning Specialist (Sonnet) breaks into tasks → Applies templates, sets dependencies
- Returns: Feature with 8-12 tasks, proper sequencing, ready for "What's next?"

**Step 3: Execute**:
```
What's next?
```
AI coordinates automatically with skills + subagents.

---

**Other MCP Clients (Basic - Manual Creation)**:

**Step 1: Share your PRD**:
```
I have a PRD for a payment processing system. [Paste or attach PRD]

Please analyze and create a feature with tasks and dependencies.
```

**Step 2: AI creates structure manually**:
AI creates feature and tasks using direct MCP tools (no orchestration)

**Step 3: Implement**:
Work through tasks yourself with template guidance

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

- **[🤖 Agent Architecture](agent-architecture.md)** - Complete agent coordination guide and hybrid architecture
- **[📊 Token Reduction Examples](token-reduction-examples.md)** - Quantitative before/after analysis
- **[📝 Templates](templates.md)** - 9 built-in templates explained
- **[🤖 AI Guidelines](ai-guidelines.md)** - How AI uses Task Orchestrator autonomously
- **[🔧 API Reference](api-reference.md)** - Complete tool documentation

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

**Orchestration not working? (Claude Code only)**
- Verify plugin is installed via `/plugin list`
- Check `.claude/agents/task-orchestrator/` directory exists
- Verify agent files: `ls .claude/agents/task-orchestrator/`
- Should see: feature-architect.md, implementation-specialist.md, planning-specialist.md, senior-engineer.md
- Verify skills: `ls .claude/skills/`
- coordinate_feature_development not found? Reinstall plugin via marketplace: `/plugin install task-orchestrator`

**Docker issues?**
- Start Docker Desktop
- Pull image: `docker pull ghcr.io/jpicklyk/task-orchestrator:latest`
- Test: `docker run --rm -i -v mcp-task-data:/app/data ghcr.io/jpicklyk/task-orchestrator:latest`

**Still stuck?** See [Troubleshooting Guide](troubleshooting.md) for comprehensive solutions.

---

**You're ready!** Start with a simple task to learn the system, then scale to complex features with sub-agent orchestration when needed. 🚀
