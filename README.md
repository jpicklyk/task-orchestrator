# MCP Task Orchestrator

**Stop losing context. Start building faster.**

An orchestration framework for AI coding assistants that solves context pollution and token exhaustion - enabling your AI to work on complex projects without running out of memory.

[![Version](https://img.shields.io/github/v/release/jpicklyk/task-orchestrator?include_prereleases)](https://github.com/jpicklyk/task-orchestrator/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![MCP Compatible](https://img.shields.io/badge/MCP-Compatible-purple)](https://modelcontextprotocol.io)

---

## The Problem

AI assistants suffer from **context pollution** - a well-documented challenge where model accuracy degrades as token count increases. This "context rot" stems from transformer architecture's quadratic attention mechanism, where each token must maintain pairwise relationships with all others.

**The Impact**: As your AI works on complex features, it accumulates conversation history, tool outputs, and code examples. By task 10-15, the context window fills with 200k+ tokens. The model loses focus, forgets earlier decisions, and eventually fails. You're forced to restart sessions and spend 30-60 minutes rebuilding context just to continue.

**Industry Validation**: Anthropic's research on [context management](https://www.anthropic.com/news/context-management) confirms production AI agents "exhaust their effective context windows" on long-running tasks, requiring active intervention to prevent failure.

Traditional approaches treat context windows like unlimited memory. Task Orchestrator recognizes they're a finite resource that must be managed proactively.

## The Solution

Task Orchestrator implements **industry-recommended patterns** from Anthropic's [context engineering research](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents): persistent external memory, summary-based context passing, and sub-agent architectures with clean contexts.

**How it works**:
- **Persistent memory** (SQLite) stores project state outside context windows
- **Summary-based passing** - Tasks create 300-500 token summaries instead of passing 5-10k full contexts
- **Sub-agent isolation** - Delegated agents work with clean contexts, return condensed results
- **Just-in-time loading** - Fetch only what's needed for current work

**Result**: Scale to 50+ tasks without hitting context limits. Up to 90% token reduction (matching Anthropic's 84% benchmark). Zero time wasted rebuilding context.

---

## Key Features

- ✅ **Persistent Memory** — AI remembers project state, completed work, and decisions across sessions
- ✅ **Token Efficiency** — Up to 90% reduction via summary-based context passing
- ✅ **Hierarchical Tasks** — Projects → Features → Tasks with dependency tracking
- ✅ **Template System** — Built-in templates for planning, implementation, investigation, and testing with composable design
- ✅ **Completion Gates** — Structured JSON verification criteria that block premature task completion
- ✅ **Status Workflows** — Role-aware status transitions with named triggers and configurable flows
- ✅ **Planning Tiers** — Signal-based auto-sizing (Quick/Standard/Detailed) with composable templates
- ✅ **Sub-Agent Orchestration** — Delegated execution for complex work (Claude Code)
- ✅ **Skills & Hooks** — Workflow coordination skills and event-driven automation hooks (Claude Code)
- ✅ **MCP Protocol Support** — Core persistence and task management work with any MCP client

> **Deep dive**: See [Developer Architecture](docs/developer-guides/architecture.md) for technical details.

---

## Quick Start

**Prerequisite**: [Docker](https://www.docker.com/products/docker-desktop/) must be installed and running.

### Option A: Claude Code (Full Experience)

Install the plugin for skills, hooks, and output styles, then configure the MCP server:

1. **Add the marketplace and install the plugin:**
   ```
   /plugin marketplace add jpicklyk/task-orchestrator
   /plugin install task-orchestrator
   ```

2. **Configure the MCP server** by adding to your project's `.mcp.json`:
   ```json
   {
     "mcpServers": {
       "mcp-task-orchestrator": {
         "command": "docker",
         "args": [
           "run", "--rm", "-i",
           "-v", "mcp-task-data:/app/data",
           "ghcr.io/jpicklyk/task-orchestrator:latest"
         ]
       }
     }
   }
   ```

3. **Restart Claude Code**

The plugin provides skills for workflow coordination, a session-start hook that automatically loads your project overview, and an optional orchestrator output style. The MCP server auto-initializes its database and seeds built-in templates on first run.

> **Contributing?** See [Contributing Guidelines](CONTRIBUTING.md) for developer setup.

### Option B: Other MCP Clients (Core MCP)

For any MCP-compatible client, pull the Docker image and configure your client:

1. **Pull the image:**
   ```bash
   docker pull ghcr.io/jpicklyk/task-orchestrator:latest
   ```

2. **Configure your MCP client** with this stdio transport config:
   ```json
   {
     "mcpServers": {
       "mcp-task-orchestrator": {
         "command": "docker",
         "args": [
           "run", "--rm", "-i",
           "-v", "mcp-task-data:/app/data",
           "ghcr.io/jpicklyk/task-orchestrator:latest"
         ]
       }
     }
   }
   ```

   See [Installation Guide](docs/installation-guide.md) for platform-specific configuration and advanced options.

3. **Start using the tools** — the server auto-initializes its database and seeds built-in templates on first run. No additional setup required.

> **Optional**: Create `.taskorchestrator/config.yaml` to customize status workflow transitions. See [Status Progression Guide](docs/status-progression.md) for details.

---

## Use Cases

### 📂 Persistent Context Across Sessions
Your AI remembers project state, completed work, and technical decisions - even after restarting. No more re-explaining your codebase every morning.

### 🏗️ Large Feature Implementation
Build features with 10+ tasks without hitting context limits. Traditional approaches fail at 12-15 tasks. Task Orchestrator scales to 50+ tasks effortlessly.

### 🔄 Cross-Domain Coordination
Database → Backend → Frontend → Testing workflows with automatic context passing. Each agent sees only what they need, not everything.

### 👥 Multi-Agent Workflows
Multiple AI agents work in parallel without conflicts. Built-in concurrency protection and dependency management.

### 🐛 Bug Tracking During Development
Capture bugs and improvements as you find them. Organize work without losing track of what needs fixing.

---

## How It Works

**1. Hierarchical Task Management**
```
Project: E-Commerce Platform
  └── Feature: User Authentication
      ├── Task: Database schema [COMPLETED]
      ├── Task: Login API [IN-PROGRESS]
      ├── Task: Password reset [PENDING]
      └── Task: API docs [PENDING] [BLOCKED BY: Login API]
```

**2. Summary-Based Context Passing**

Instead of passing 5,000 tokens of full task details, task summaries condense context to 300-500 tokens:

```markdown
### Completed
Created Users table with authentication fields (id, email, password_hash).
Added indexes for email lookup.

### Files Changed
- db/migration/V5__create_users.sql
- src/model/User.kt

### Next Steps
API endpoints can use this schema for authentication
```

**Result**: Up to 92% token reduction per dependency. This implements Anthropic's "compaction" pattern - preserving critical information while discarding redundant details.

**3. Role-Aware Status Workflows**

Tasks progress through named triggers validated against your workflow config:
- `start` → Task moves to in-progress (queue → work)
- `complete` → Task completes if verification criteria pass (work → terminal)
- `block` → Task blocked by dependency (any → blocked)

Status roles (queue, work, review, blocked, terminal) provide semantic context
for AI agents to understand where each task sits in the workflow.

All transitions validated by your config in `.taskorchestrator/config.yaml`.

> **Learn more**: [Status Progression Guide](docs/status-progression.md) and [Workflow Patterns](docs/workflow-patterns.md)

---

## Core Workflow Pattern

Task Orchestrator follows a **Plan → Orchestrate → Execute** pattern that prevents context pollution:

### 1. Plan Your Work

The AI agent determines planning depth from your request using context signals:

- **Quick** — Task breakdown only. For small changes, fixes, and straightforward work.
- **Standard** — Feature + tasks with documentation templates. For most features and bounded scope.
- **Detailed** — Full engineering plan with architecture, design decisions, and specifications. For complex, multi-phase work.

The agent analyzes your language for sizing signals (scope words, complexity indicators, work type) and declares its determination. You can override if needed, but most requests have clear signals.

**Example**:
```markdown
# User Authentication Feature
Build complete authentication system with login, signup, and password reset.

Requirements:
- JWT-based authentication
- Password hashing with bcrypt
- Email verification
- Rate limiting on login attempts
```

### 2. Orchestrate Into Structure

Use the feature orchestration skill (Claude Code) or the MCP tools directly:
```
"Help me break down this feature into tasks"
```

**What happens**:
1. AI analyzes your plan and creates a feature with rich context
2. Templates auto-selected based on planning tier and work type — these define the minimum content the plan must cover
3. Feature is broken down into dependency-aware tasks
4. Completion gates auto-enabled when verification templates are applied

**Result**: Feature with properly scoped tasks, baseline documentation from templates, clear dependencies, and verification criteria. The agent adds depth beyond templates wherever the work warrants it.

### 3. Execute Based on Dependencies

AI automatically:
- Uses `get_next_task` for dependency-aware task recommendations
- Passes 300-500 token summaries between tasks (not 5k+ full contexts)
- Progresses status via `request_transition` with named triggers (start, complete, block)
- Enforces verification gates before allowing task completion
- Delegates to subagents for complex work (Claude Code)

**Your role**: Just say "What's next?" and the AI handles dependencies, verification, and coordination.

### Status Transitions

Task Orchestrator uses **trigger-based status transitions** with role awareness:

- **Named triggers**: `start`, `complete`, `cancel`, `block`, `hold` — validated against your workflow config
- **Role annotations**: Each status maps to a semantic role (queue, work, review, blocked, terminal)
- **Workflow flows**: Default, bug_fix, and documentation flows with different status sequences
- **Cascade effects**: Task completion can trigger feature status changes
- **Verification gates**: Tasks with `requiresVerification` must pass all JSON acceptance criteria before completion

**Configuration**: `.taskorchestrator/config.yaml` defines valid transitions, workflow flows, and prerequisites.

> **Deep dive**: [Status Progression Guide](docs/status-progression.md) for complete configuration reference.

---

## Template System

Built-in templates define the **minimum content** for planning and implementation documentation. They're a floor, not a ceiling — the agent should go beyond template sections when the work demands it. Templates are composable, combining based on planning tier and work type.

### Planning Templates

- **Feature Plan** — Engineering plan with problem definition, architecture, and implementation phases
- **Codebase Exploration** — Scoped investigation with target files and structured findings
- **Design Decision** — Architectural decisions with options analysis and recommendation
- **Implementation Specification** — Detailed spec with code change points and test requirements

### Implementation Templates

- **Task Implementation** — Approach, progress notes, and verification criteria
- **Bug Investigation** — Root cause analysis and fix verification
- **Technical Approach** — Architecture decisions within a task scope
- **Test Plan** — Testing approach and acceptance criteria

### Feature Templates

- **Requirements Specification** — Functional requirements, enhancements, and constraints
- **Context & Background** — Business context, user needs, and coordination requirements

### Workflow Templates

- **Local Git Branching Workflow** — Branch naming, commit patterns, and merge strategy
- **GitHub PR Workflow** — PR creation, review process, and merge guidelines

Templates include a **Verification** section (JSON format) that enables completion gates — structured acceptance criteria that block premature task completion.

> **Details**: [Templates Guide](docs/templates.md) for full template documentation.

---

## Documentation

### Getting Started
- **[Quick Start Guide](docs/quick-start.md)** — Complete setup walkthrough
- **[Installation Guide](docs/installation-guide.md)** — Platform-specific configuration
- **[AI Guidelines](docs/ai-guidelines.md)** — How AI uses Task Orchestrator autonomously

### Using Task Orchestrator
- **[Templates](docs/templates.md)** — Built-in templates for planning, implementation, and testing
- **[Workflow Patterns](docs/workflow-patterns.md)** — Workflow automation patterns and examples
- **[Status Progression](docs/status-progression.md)** — Status workflow configuration and triggers
- **[Claude Code Plugin](docs/claude-plugin.md)** — Plugin installation, skills, hooks, and configuration

### Reference
- **[API Reference](docs/api-reference.md)** — Complete MCP tools documentation
- **[Architecture](docs/developer-guides/architecture.md)** — Technical deep-dive
- **[Troubleshooting](docs/troubleshooting.md)** — Solutions to common issues

### For Developers
- **[Contributing Guidelines](docs/developer-guides/index.md#contributing)** — Development setup
- **[Database Migrations](docs/developer-guides/database-migrations.md)** — Schema management
- **[Community Wiki](../../wiki)** — Examples, tips, and guides

---

## Platform Compatibility

| Feature | Claude Code | Other MCP Clients |
|---------|-------------|-------------------|
| **Persistent Memory** | ✅ Tested & Supported | ✅ MCP Protocol Support |
| **Template System** | ✅ Tested & Supported | ✅ MCP Protocol Support |
| **Task Management** | ✅ Tested & Supported | ✅ MCP Protocol Support |
| **Completion Gates** | ✅ Tested & Supported | ✅ MCP Protocol Support |
| **Status Workflows** | ✅ Tested & Supported | ✅ MCP Protocol Support |
| **Sub-Agent Orchestration** | ✅ Tested & Supported | ❌ Claude Code-specific |
| **Skills (Coordination)** | ✅ Tested & Supported | ❌ Claude Code-specific |
| **Hooks (Automation)** | ✅ Tested & Supported | ❌ Claude Code-specific |

**Primary Platform**: Claude Code is the primary tested and supported platform with full feature access including skills, subagents, and hooks.

**Other MCP Clients**: The core MCP protocol (persistent memory, task management, templates, status events) works with any MCP client, but we cannot verify functionality on untested platforms. Advanced orchestration features (skills, subagents, hooks) require Claude Code's `.claude/` directory structure.

---

## Example: From Session Start to Feature Complete

```
You: "I have a plan for user authentication in plan.md"
AI: "Standard-tier — bounded feature with clear requirements."
    → Applies Requirements Spec + Task Implementation templates
    → Creates feature with 8 tasks, dependencies mapped, verification gates set

You: "What's next?"
AI: "Task 1: Database schema [PENDING]. No blockers."
    → Implements schema → Creates 400-token summary
    → Verification: schema tests pass ✓, no regressions ✓

You: "What's next?"
AI: "Task 2: Authentication API [PENDING]. Dependencies satisfied."
    → Reads 400-token summary (not 5k full context)
    → Implements API → Creates summary

You: "What's next?"
AI: "Task 3: Login UI [PENDING]. Backend ready."
    → Implements UI → Feature progresses

[Next morning - new session]
You: "What's next?"
AI: "Task 4: Integration tests [PENDING]. 3 tasks completed yesterday."
    → No context rebuilding - AI remembers everything from persistent memory
```

**Key Benefits**:
- **Planning tier auto-detection**: AI determines appropriate depth from your request
- **Automatic dependency tracking**: AI only suggests tasks with satisfied dependencies
- **Verification gates**: Structured criteria prevent premature task completion
- **Persistent memory**: New sessions start instantly with full context
- **Token efficiency**: 400-token summaries instead of 5k+ full contexts

---

## Troubleshooting

**Quick Fixes**:
- **AI can't find tools**: Restart your AI client
- **Docker not running**: Start Docker Desktop, verify with `docker version`
- **Connection problems**: Enable `LOG_LEVEL=DEBUG` in Docker config
- **Skills not available**: Install via plugin marketplace (requires Claude Code)

**Get Help**:
- [Troubleshooting Guide](docs/troubleshooting.md) - Comprehensive solutions
- [Discussions](../../discussions) - Ask questions and share ideas
- [Issues](../../issues) - Bug reports and feature requests

---

## Technical Stack

Built with modern, reliable technologies:
- **Kotlin 2.2.0** with Coroutines for concurrent operations
- **SQLite + Exposed ORM** for fast, zero-config database (persistent memory system)
- **Flyway Migrations** for versioned schema management
- **MCP SDK 0.8.3** for standards-compliant protocol
- **Docker** for one-command deployment

**Architecture Validation**: Task Orchestrator implements patterns recommended in Anthropic's context engineering research: sub-agent architectures, compaction through summarization, just-in-time context loading, and persistent external memory. Our approach prevents context accumulation rather than managing it after the fact.

> **Architecture details**: See [Developer Guides](docs/developer-guides/architecture.md)

---

## Contributing

We welcome contributions! Task Orchestrator follows Clean Architecture with 4 distinct layers (Domain → Application → Infrastructure → Interface).

**To contribute**:
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes with tests
4. Submit a pull request

See [Contributing Guidelines](docs/developer-guides/index.md#contributing) for detailed development setup.

---

## Release Information

- [Watch releases](../../releases) - Get notified of new versions
- [View changelog](CHANGELOG.md) - See what's changed

**Version format**: `{major}.{minor}.{patch}.{git-commit-count}-{qualifier}`

Current versioning defined in [build.gradle.kts](build.gradle.kts).

---

## License

[MIT License](LICENSE) - Free for personal and commercial use

---

## Keywords

AI coding tools, AI pair programming, Model Context Protocol, MCP server, Claude Code, Claude Desktop, AI task management, context persistence, AI memory, token optimization, RAG, AI workflow automation, persistent AI assistant, context pollution solution, AI orchestration, sub-agent coordination

---

**Ready to build complex features without context limits?**

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

Then follow the [Quick Start Guide](docs/quick-start.md) to configure your AI platform.
