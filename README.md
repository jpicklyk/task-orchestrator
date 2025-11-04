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
- **Sub-agent isolation** - Specialists work with clean contexts, return condensed results
- **Just-in-time loading** - Fetch only what's needed for current work

**Result**: Scale to 50+ tasks without hitting context limits. Up to 90% token reduction (matching Anthropic's 84% benchmark). Zero time wasted rebuilding context.

---

## Key Features

- ✅ **Persistent Memory** - AI remembers project state, completed work, and decisions across sessions
- ✅ **Token Efficiency** - Up to 90% reduction via summary-based context passing
- ✅ **Hierarchical Tasks** - Projects → Features → Tasks with dependency tracking
- ✅ **Template System** - 9 built-in workflow templates with decision frameworks and quality gates
- ✅ **Event-Driven Workflows** - Automatic status progression based on your config
- ✅ **Sub-Agent Orchestration** - Specialist routing for complex work (Claude Code)
- ✅ **Skills & Hooks** - Lightweight coordination and workflow automation (Claude Code)
- ✅ **MCP Protocol Support** - Core persistence and task management work with any MCP client

> **📖 Deep dive**: See [Agent Architecture Guide](docs/agent-architecture.md) for token efficiency comparison and [Developer Architecture](docs/developer-guides/architecture.md) for technical details.

---

## Quick Start

### Option A: Plugin Installation (Recommended for Claude Code)

**Easiest way** - Install everything (MCP server, skills, subagents, hooks) in one step:

1. **Clone this repository:**
   ```bash
   git clone https://github.com/jpicklyk/task-orchestrator.git
   cd task-orchestrator
   ```

2. **Add the local marketplace:**
   ```
   /plugin marketplace add ./
   ```

3. **Install the plugin:**
   ```
   /plugin install task-orchestrator@task-orchestrator-marketplace
   ```

4. **Restart Claude Code**

5. **Initialize your project:**
   ```
   setup_project
   ```

**Note**: Once this repository is published on GitHub, you'll be able to use:
```
/plugin marketplace add jpicklyk/task-orchestrator
/plugin install task-orchestrator
```

See [Plugin Installation Guide](docs/plugin-installation.md) for detailed instructions and troubleshooting.

### Option B: Manual MCP Installation

**For other MCP clients or custom setup:**

1. **Install via Docker:**
   ```bash
   docker pull ghcr.io/jpicklyk/task-orchestrator:latest
   ```

2. **Configure your AI platform:**

   **Claude Code:**
   ```bash
   claude mcp add-json task-orchestrator '{"type":"stdio","command":"docker","args":["run","--rm","-i","-v","mcp-task-data:/app/data","-v",".:/project","-e","AGENT_CONFIG_DIR=/project","ghcr.io/jpicklyk/task-orchestrator:latest"]}'
   ```

   This single command works across all platforms (macOS, Linux, Windows).

   **Other MCP clients**: Task Orchestrator's core MCP protocol (persistent memory, task management) works with any MCP client, but advanced features (skills, subagents, hooks) are Claude Code-specific. See [Installation Guide](docs/installation-guide.md) for configuration.

### 3. Initialize AI & Project

**First time setup** - Initialize your AI with Task Orchestrator patterns:
```
"Run the initialize_task_orchestrator workflow"
```
This writes Task Orchestrator patterns to your AI's permanent memory (CLAUDE.md, .cursorrules, etc.)

**Project setup** - Initialize your project with configuration:
```
"Run setup_project to initialize Task Orchestrator"
```

**Quick reference** - View essential patterns anytime:
```
"Show me the getting_started guide"
```

**That's it!** Your AI can now create and manage tasks with persistent memory.

> **🚀 Complete setup**: [Quick Start Guide](docs/quick-start.md) - Includes sub-agent setup, templates, and first feature walkthrough.

---

## Use Cases

### 📂 Persistent Context Across Sessions
Your AI remembers project state, completed work, and technical decisions - even after restarting. No more re-explaining your codebase every morning.

### 🏗️ Large Feature Implementation
Build features with 10+ tasks without hitting context limits. Traditional approaches fail at 12-15 tasks. Task Orchestrator scales to 50+ tasks effortlessly.

### 🔄 Cross-Domain Coordination
Database → Backend → Frontend → Testing workflows with automatic context passing. Each specialist sees only what they need, not everything.

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

Instead of passing 5,000 tokens of full task details, specialists create 300-500 token summaries:

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

**3. Event-Driven Workflows**

Tasks progress automatically based on workflow events:
- `work_started` → Task moves to in-progress
- `implementation_complete` → Task moves to testing
- `tests_passed` → Task completes
- `all_tasks_complete` → Feature moves to testing

All status transitions validated by your config in `.taskorchestrator/config.yaml`.

> **📘 Learn more**: [Status Progression Guide](docs/status-progression.md) and [Workflow Prompts](docs/workflow-prompts.md)

---

## Core Workflow Pattern

Task Orchestrator follows a **Plan → Orchestrate → Execute** pattern that prevents context pollution:

### 1. Plan Your Work

Start with either:
- **Plan file**: Create a markdown/text file with your feature description, requirements, and context
- **Conversation context**: Describe your feature directly in conversation

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

Use the `coordinate_feature_development` workflow (Claude Code):
```
"Run coordinate_feature_development with my plan file"
```

**What happens**:
1. **Feature Architect** (Opus) analyzes your plan → Creates feature with rich context
2. **Planning Specialist** (Sonnet) breaks down feature → Creates dependency-aware tasks
3. Returns structured feature ready for execution

**Result**: Feature with 5-15 tasks, proper templates, clear dependencies, appropriate specialist tags.

### 3. Execute Based on Dependencies

AI automatically:
- Routes tasks to specialists (**Implementation Specialist** (Haiku) by default, **Senior Engineer** (Sonnet) for complex issues)
- Respects dependency chains (database → API → frontend)
- Passes 300-500 token summaries between tasks (not 5k+ full contexts)
- Triggers status events as work progresses

**Default Specialists:**
- **Implementation Specialist** (Haiku) - General implementation tasks (fast, cost-efficient)
- **Senior Engineer** (Sonnet) - Complex debugging, architecture, unblocking

**Custom Specialists** (optional via `.taskorchestrator/agent-mapping.yaml`):
- Backend Engineer, Frontend Developer, Database Engineer, Test Engineer, Technical Writer
- See [Agent Architecture Guide](docs/agent-architecture.md) for configuration

**Your role**: Just say "What's next?" and the AI handles routing, dependencies, and coordination.

> **💡 Pro Tip**: The Task Orchestrator communication style plugin is automatically active in Claude Code for clearer coordination (uses phase labels, status indicators ✅⚠️❌🔄, and concise progress updates) when installed via the plugin marketplace.

### Status Events Drive Progression

Task Orchestrator uses **event-driven status progression** mapped to your workflow:

- **Default statuses**: PENDING → IN_PROGRESS → COMPLETED (customizable in `.taskorchestrator/config.yaml`)
- **Event triggers**: Work completion, test passing, review approval automatically progress status
- **Workflow types**: Default, bug_fix, documentation flows with different status sequences
- **Cascade effects**: Task completion can trigger feature status changes

**Configuration**: `.taskorchestrator/config.yaml` defines:
- Valid status transitions for each entity type (task, feature, project)
- Workflow flows (default, bug_fix, documentation)
- Event mappings (which events trigger which status changes)
- Prerequisites for status progression (e.g., "can't complete until all tasks done")

> **📘 Deep dive**: [Status Progression Guide](docs/status-progression.md) for complete configuration reference and workflow examples.

---

## Documentation

### Getting Started
- 🚀 **[Quick Start Guide](docs/quick-start.md)** - Complete setup walkthrough
- 🔧 **[Installation Guide](docs/installation-guide.md)** - Platform-specific configuration
- 🤖 **[AI Guidelines](docs/ai-guidelines.md)** - How AI uses Task Orchestrator autonomously

### Using Task Orchestrator
- 🤖 **[Agent Architecture](docs/agent-architecture.md)** - 4-tier hybrid system: Direct Tools, Skills, Hooks, Subagents
- 🎯 **[Skills Guide](docs/skills-guide.md)** - Lightweight coordination (60-82% token savings)
- 🪝 **[Hooks Guide](docs/hooks-guide.md)** - Workflow automation and event-driven integration
- 📝 **[Templates](docs/templates.md)** - 9 built-in workflow templates (instructions, frameworks, quality gates)
- 📋 **[Workflow Prompts](docs/workflow-prompts.md)** - Automated workflow guidance

### Reference
- 🔧 **[API Reference](docs/api-reference.md)** - Complete MCP tools documentation
- 🏗️ **[Architecture](docs/developer-guides/architecture.md)** - Technical deep-dive
- 🆘 **[Troubleshooting](docs/troubleshooting.md)** - Solutions to common issues

### For Developers
- 👨‍💻 **[Contributing Guidelines](docs/developer-guides/index.md#contributing)** - Development setup
- 🗃️ **[Database Migrations](docs/developer-guides/database-migrations.md)** - Schema management
- 💬 **[Community Wiki](../../wiki)** - Examples, tips, and guides

---

## Platform Compatibility

| Feature | Claude Code | Other MCP Clients |
|---------|-------------|-------------------|
| **Persistent Memory** | ✅ Tested & Supported | ✅ MCP Protocol Support |
| **Template System** | ✅ Tested & Supported | ✅ MCP Protocol Support |
| **Task Management** | ✅ Tested & Supported | ✅ MCP Protocol Support |
| **Sub-Agent Orchestration** | ✅ Tested & Supported | ❌ Claude Code-specific |
| **Skills (Lightweight Coordination)** | ✅ Tested & Supported | ❌ Claude Code-specific |
| **Hooks (Workflow Automation)** | ✅ Tested & Supported | ❌ Claude Code-specific |
| **Status Event System** | ✅ Tested & Supported | ✅ MCP Protocol Support |

**Primary Platform**: Claude Code is the primary tested and supported platform with full feature access including skills, subagents, and hooks.

**Other MCP Clients**: The core MCP protocol (persistent memory, task management, templates, status events) works with any MCP client, but we cannot verify functionality on untested platforms. Advanced orchestration features (skills, subagents, hooks) require Claude Code's `.claude/` directory structure.

---

## Example: From Session Start to Feature Complete

**Claude Code (Full Orchestration)**:

```
You: "I have a plan for user authentication in plan.md"
AI: "Loading Feature Orchestration Skill..."
    "Launching Feature Architect (Opus) with plan file..."
    → Feature created with 8 tasks
    "Launching Planning Specialist (Sonnet)..."
    → Tasks broken down with dependencies

You: "What's next?"
AI: "Task 1: Database schema [PENDING]. No blockers."
    Launches Implementation Specialist → Implements schema → Creates 400-token summary

You: "What's next?"
AI: "Task 2: Authentication API [PENDING]. Dependencies satisfied."
    Reads 400-token summary (not 5k full context)
    Launches Implementation Specialist → Implements API → Creates summary

You: "What's next?"
AI: "Task 3: Login UI [PENDING]. Backend ready."
    Launches Implementation Specialist → Implements UI → Feature progresses

[Next morning - new session]
You: "What's next?"
AI: "Task 4: Integration tests [PENDING]. 3 tasks completed yesterday."
    No context rebuilding - AI remembers everything from persistent memory
```

**Key Benefits**:
- **Zero manual routing**: `coordinate_feature_development` handles specialist selection
- **Automatic dependency tracking**: AI only suggests tasks with satisfied dependencies
- **Persistent memory**: New sessions start instantly with full context
- **Token efficiency**: 400-token summaries instead of 5k+ full contexts

---

## Troubleshooting

**Quick Fixes**:
- **AI can't find tools**: Restart your AI client
- **Docker not running**: Start Docker Desktop, verify with `docker version`
- **Connection problems**: Enable `MCP_DEBUG=true` in Docker config
- **Skills/Sub-agents not available**: Install via plugin marketplace (requires Claude Code)
- **coordinate_feature_development not found**: Install plugin via marketplace for full orchestration features

**Get Help**:
- 📖 [Troubleshooting Guide](docs/troubleshooting.md) - Comprehensive solutions
- 💬 [Discussions](../../discussions) - Ask questions and share ideas
- 🐛 [Issues](../../issues) - Bug reports and feature requests

---

## Technical Stack

Built with modern, reliable technologies:
- **Kotlin 2.2.0** with Coroutines for concurrent operations
- **SQLite + Exposed ORM** for fast, zero-config database (persistent memory system)
- **Flyway Migrations** for versioned schema management
- **MCP SDK 0.7.2** for standards-compliant protocol
- **Docker** for one-command deployment

**Architecture Validation**: Task Orchestrator implements patterns recommended in Anthropic's context engineering research: sub-agent architectures, compaction through summarization, just-in-time context loading, and persistent external memory. Our approach prevents context accumulation rather than managing it after the fact.

> **🏗️ Architecture details**: See [Developer Guides](docs/developer-guides/architecture.md)

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

- 🔔 [Watch releases](../../releases) - Get notified of new versions
- 📋 [View changelog](CHANGELOG.md) - See what's changed

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

Then follow the [Quick Start Guide](docs/quick-start.md) to configure your AI platform. 🚀
