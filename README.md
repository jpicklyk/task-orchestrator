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
- ✅ **Template System** - 9 built-in templates for consistent documentation structure
- ✅ **Event-Driven Workflows** - Automatic status progression based on your config
- ✅ **Sub-Agent Orchestration** - Specialist routing for complex work (Claude Code only)
- ✅ **Universal Compatibility** - Works with any MCP client (Claude Desktop, Claude Code, Cursor, Windsurf)

> **📖 Deep dive**: See [Token Reduction Examples](docs/token-reduction-examples.md) for quantitative analysis and [Architecture Guide](docs/developer-guides/architecture.md) for technical details.

---

## Quick Start

### 1. Install via Docker

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

### 2. Configure Your AI Platform

**Claude Code** (recommended):
```bash
# macOS/Linux
claude mcp add-json task-orchestrator "{\"type\":\"stdio\",\"command\":\"docker\",\"args\":[\"run\",\"--rm\",\"-i\",\"-v\",\"mcp-task-data:/app/data\",\"-v\",\"$(pwd):/project\",\"-e\",\"AGENT_CONFIG_DIR=/project\",\"ghcr.io/jpicklyk/task-orchestrator:latest\"]}"

# Windows PowerShell
claude mcp add-json task-orchestrator "{`"type`":`"stdio`",`"command`":`"docker`",`"args`":[`"run`",`"--rm`",`"-i`",`"-v`",`"mcp-task-data:/app/data`",`"-v`",`"${PWD}:/project`",`"-e`",`"AGENT_CONFIG_DIR=/project`",`"ghcr.io/jpicklyk/task-orchestrator:latest`"]}"
```

**Claude Desktop, Cursor, Windsurf**: See [Installation Guide](docs/installation-guide.md) for your platform.

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

> **📘 Learn more**: [Event-Driven Pattern](docs/v2-0/event-driven-status-progression-pattern.md) and [Workflow Guide](docs/workflow-prompts.md)

---

## Documentation

### Getting Started
- 🚀 **[Quick Start Guide](docs/quick-start.md)** - Complete setup walkthrough
- 🔧 **[Installation Guide](docs/installation-guide.md)** - Platform-specific configuration
- 🤖 **[AI Guidelines](docs/ai-guidelines.md)** - How AI uses Task Orchestrator autonomously

### Using Task Orchestrator
- 📊 **[Token Reduction Examples](docs/token-reduction-examples.md)** - Quantitative before/after analysis
- 🤖 **[Agent Orchestration](docs/agent-orchestration.md)** - Sub-agent coordination system
- ⚡ **[Parallel Processing](docs/parallel-processing-guide.md)** - Wave-based parallel execution
- 📝 **[Templates](docs/templates.md)** - 9 built-in documentation templates
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

| AI Platform | Templates | Persistent Memory | Sub-Agent Orchestration |
|-------------|-----------|-------------------|------------------------|
| **Claude Code** | ✅ | ✅ | ✅ Full support |
| **Claude Desktop** | ✅ | ✅ | ❌ |
| **Cursor** | ✅ | ✅ | ❌ |
| **Windsurf** | ✅ | ✅ | ❌ |
| **Any MCP client** | ✅ | ✅ | ❌ |

**Note**: Sub-agent orchestration (specialist routing, parallel execution) is exclusive to Claude Code via `.claude/agents/` directory. All platforms get persistent memory and template-driven workflows.

---

## Example: From Session Start to Feature Complete

```
You: "Show me the project overview"
AI: Reads persistent state, shows 3 features, 12 tasks, current priorities

You: "Create a feature for user authentication with 4 tasks"
AI: Creates feature with template-driven tasks (database, API, frontend, tests)

You: "Work on the first task"
AI: [Claude Code] Routes to Database Specialist → Implements schema → Creates summary
    [Other platforms] Reads template, implements directly

You: "Work on the next task"
AI: Reads 400-token summary from database task (not 5k full context)
    Routes to Backend Specialist → Implements API → Creates summary

[Next morning - new session]
You: "What's next?"
AI: "Task 3: Frontend forms [PENDING]. Backend API completed yesterday. Schema available."
    No context rebuilding needed - AI remembers everything.
```

---

## Troubleshooting

**Quick Fixes**:
- **AI can't find tools**: Restart your AI client
- **Docker not running**: Start Docker Desktop, verify with `docker version`
- **Connection problems**: Enable `MCP_DEBUG=true` in Docker config
- **Sub-agents not available**: Run `setup_claude_orchestration` (Claude Code only)

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

AI coding tools, AI pair programming, Model Context Protocol, MCP server, Claude Desktop, Claude Code, Cursor AI, Windsurf, AI task management, context persistence, AI memory, token optimization, RAG, AI workflow automation, persistent AI assistant, context pollution solution

---

**Ready to build complex features without context limits?**

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

Then follow the [Quick Start Guide](docs/quick-start.md) to configure your AI platform. 🚀
