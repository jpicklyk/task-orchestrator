# MCP Task Orchestrator

**Stop losing context. Start building faster.**

An orchestration framework for AI coding assistants that solves context pollution, token exhaustion, and cross-domain coordination - enabling your AI to work on complex projects without running out of memory.

[![Version](https://img.shields.io/github/v/release/jpicklyk/task-orchestrator?include_prereleases)](https://github.com/jpicklyk/task-orchestrator/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![MCP Compatible](https://img.shields.io/badge/MCP-Compatible-purple)](https://modelcontextprotocol.io)

---

## TL;DR

**Want to get started right away?** → [Quick Start Guide](docs/quick-start.md)

Task Orchestrator solves AI context pollution through orchestration + sub-agents:
- **85-90% token reduction** (specialists work with summaries, not full context)
- **Works with any MCP client** (Claude Desktop, Claude Code, Cursor, Windsurf)
- **5-minute setup** via Docker
- **Scales to 50+ tasks** without context exhaustion

**Core capabilities**: Hierarchical task management (Projects → Features → Tasks), dependency tracking, templates for structure, workflow automation, and optional 3-level sub-agent coordination (Claude Code only).

---

## The Problem: AI Context Pollution

You're building a complex feature - user authentication with database schema, API endpoints, frontend forms, and tests. You're working with Claude Code, but by task 5, something breaks:

```
❌ Context limit exceeded
❌ AI forgets what was completed in task 1
❌ Token budget exhausted
❌ Must restart session and re-explain entire project
```

**Why does this happen?**

Traditional AI workflows accumulate context linearly. Each new task carries the full context of ALL previous tasks:

```
Task 1: Database schema (5k tokens)
Task 2: API endpoints (11k tokens - includes all of Task 1)
Task 3: Frontend forms (21k tokens - includes Tasks 1+2)
Task 4: Integration tests (42k tokens - includes Tasks 1+2+3)

Total context: 79k tokens
By Task 10: 311k tokens (EXCEEDS 200k LIMIT)
```

**The breaking point**: Traditional approaches fail around **12-15 tasks** due to context limits. Complex features become impossible to build without constant session restarts.

### Real-World Impact

**Before Task Orchestrator**:
- ❌ Context pollution after 10-15 tasks
- ❌ Token exhaustion forces session restarts
- ❌ AI forgets completed work across sessions
- ❌ No cross-domain coordination (database → backend → frontend → tests)
- ❌ Rebuilding context every morning costs 30-60 minutes

**After Task Orchestrator**:
- ✅ 85-90% token reduction via sub-agent orchestration (vs direct implementation)
- ✅ Persistent memory across sessions
- ✅ Scales to 50+ task features effortlessly
- ✅ Automatic cross-domain context passing
- ✅ AI remembers project state - zero context rebuilding

**Note**: Orchestrator carries ~2-3k coordination overhead per task, but specialists stay lean (no context accumulation). The breakthrough: enables complex projects impossible with traditional approaches.

---

## The Solution: Orchestration Framework + Sub-Agent Architecture

Task Orchestrator is **not just another task tracker** - it's an orchestration framework that fundamentally changes how AI assistants work on complex projects.

### How It Works: 3-Level Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  LEVEL 0: Orchestrator (You)                                │
│  Context: 800 tokens (just task summaries)                  │
│  Launches: Feature Manager for multi-task coordination      │
└──────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  LEVEL 1: Feature/Task Manager                              │
│  Context: 2-3k tokens (task details + dependency summaries) │
│  Launches: Specialist agents with focused briefs            │
└──────────────────────────────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  LEVEL 2: Specialists                                        │
│  Context: 2-6k tokens (just what they need)                 │
│  Examples: Backend Engineer, Database Engineer, Test Eng    │
│  Creates: 300-500 token Summary for next task              │
└──────────────────────────────────────────────────────────────┘
```

### The Key Innovation: Summary-Based Context Passing

Instead of passing 5-10k tokens of full task context, specialists create **300-500 token summaries**:

```markdown
### Completed
Created Users table with authentication fields (id, username, email,
password_hash). Added indexes for email lookup.

### Files Changed
- db/migration/V5__create_users.sql - Users table schema
- src/model/User.kt - User domain model

### Next Steps
API endpoints can use this schema for authentication

### Notes
Using bcrypt for password hashing, UUID for distributed compatibility
```

**Result**: Next task reads 400 tokens instead of 5,000 tokens - **92% reduction per dependency**.

### Quantitative Results

**What "Token Reduction" Means**:
- Traditional approaches accumulate full task context (5-10k tokens per task)
- Sub-agent orchestration uses summaries (300-500 tokens) and discards specialist contexts
- Reduction percentages = context accumulation avoided, not total tokens used
- Orchestrator carries coordination overhead (~2-3k per task), specialists stay lean

#### Scenario 1: Simple Feature (4 Tasks)
User Authentication: Database → Login API → Password Reset → Documentation

| Approach | Accumulated Context | Coordination | Real Savings | Outcome |
|----------|---------------------|--------------|--------------|---------|
| **Traditional** | 52k tokens (linear growth) | 0 | Baseline | ✅ Works, but inefficient |
| **Sub-Agent** | 800 tokens (summaries only) | ~10k | **~80%** | ✅ Enables cleaner workflow |

#### Scenario 2: Complex Feature (10 Tasks)
E-Commerce Catalog: Planning → Database (2) → Backend (3) → Frontend (2) → Testing (2)

| Approach | Accumulated Context | Coordination | Real Savings | Outcome |
|----------|---------------------|--------------|--------------|---------|
| **Traditional** | 311k tokens (exponential) | 0 | Baseline | ⚠️ **At context limits** |
| **Sub-Agent** | 2k tokens (summaries) | ~25k | **~91%** | ✅ Well within limits |

#### Scenario 3: Cross-Domain Coordination (9 Tasks)
Payment Integration: Database (2) → Backend (2) → Frontend (2) → Testing (2) → Docs (1)

| Approach | Accumulated Context | Coordination | Real Savings | Outcome |
|----------|---------------------|--------------|--------------|---------|
| **Traditional** | 51k tokens | 0 | Baseline | ⚠️ Frontend sees SQL, Writer sees React |
| **Sub-Agent** | 1,800 tokens | ~22k | **~85%** | ✅ Specialists isolated by domain |

**Breaking Point Analysis**:
- Traditional: Fails at **12-15 tasks** (context limit exceeded)
- Sub-Agent: Scales to **100+ tasks** effortlessly
- **Break-even**: 3-4 tasks (coordination overhead justified by context savings)

> **📊 See complete analysis**: [Token Reduction Examples](docs/token-reduction-examples.md)

---

## Core Capabilities

### 1. Persistent AI Memory Across Sessions

**The Problem**: AI forgets project state when you close your editor.

**The Solution**: Hierarchical knowledge structure that persists in SQLite database.

```
Project: E-Commerce Platform
  └── Feature: User Authentication
      ├── Task: Create database schema [COMPLETED]
      ├── Task: Implement login API [IN-PROGRESS]
      ├── Task: Add password reset [PENDING]
      └── Task: Write API docs [PENDING] [BLOCKED BY: login API]
```

Next morning, your AI asks: "Show me the project overview" and instantly knows:
- What's completed (database schema)
- What's in progress (login API)
- What's next (password reset, blocked by login)
- What was decided (technical approach in task sections)

**No re-explaining. No context rebuilding. Zero time wasted.**

### 2. Sub-Agent Orchestration (Claude Code Only)

**The Problem**: Single AI agent accumulates context exponentially, fails at 12-15 tasks.

**The Solution**: 3-level agent coordination with 85-90% real token reduction.

**How it works**:
1. **Orchestrator** (you) launches Feature Manager for multi-task features (~2k coordination per task)
2. **Feature Manager** recommends next task, launches Task Manager
3. **Task Manager** routes to specialists (Backend, Database, Test Engineer, etc.)
4. **Specialist** completes work with clean context, creates 300-500 token Summary
5. **Next task** reads summary instead of full context (92% savings per dependency)

**Token Reality**:
- Coordination overhead: ~2-3k tokens per task (Feature Manager, Task Manager, briefs)
- Context accumulation avoided: 5-10k tokens per task (specialists discard context)
- Net savings: 85-90% vs direct implementation
- Break-even: 3-4 tasks where coordination overhead < context accumulation

**Setup**: Run `setup_claude_agents` tool once to create `.claude/agents/` directory.

> **📖 Complete guide**: [Agent Orchestration Documentation](docs/agent-orchestration.md)

### 3. Template-Driven Workflows (All MCP Clients)

**The Problem**: AI doesn't know how to structure documentation, requirements, testing strategies.

**The Solution**: 9 built-in templates for consistent, comprehensive documentation.

**Available templates**:
- **Technical Approach** - Architecture, design decisions, implementation strategy
- **Requirements Specification** - User stories, acceptance criteria, constraints
- **Testing Strategy** - Unit tests, integration tests, edge cases
- **Context & Background** - Project context, user problems, success metrics
- **Definition of Done** - Completion checklists for implementation and production
- **Git Workflow** - Branch management, commit conventions, PR checklists
- **Task Implementation Workflow** - Step-by-step implementation guidance
- **Bug Investigation Workflow** - Triage, root cause analysis, fix validation

**How AI uses templates**:
```
You: "Create a task for implementing user authentication API"

AI: *Discovers templates with list_templates()*
    *Creates task with technical-approach + testing-strategy templates*
    *Task now has structured sections for implementation guidance*
```

**Works with**: Claude Desktop, Claude Code, Cursor, Windsurf - any MCP-compatible AI.

> **📋 See all templates**: [Templates Documentation](docs/templates.md)

### 4. Cross-Domain Context Passing

**The Problem**: Backend Engineer needs database schema details, Frontend Developer needs API specs, Test Engineer needs implementation details - but they don't need EVERYTHING.

**The Solution**: Specialists read only relevant summaries from their domain.

**Example: Payment Integration**
```
Database Engineer (Task 1):
  Writes: Full schema with migrations (5k tokens)
  Summary: Table names, key fields, indexes (400 tokens)

Backend Engineer (Task 3):
  Reads: Database summary (400 tokens) ← NOT 5k
  Doesn't see: SQL DDL, migration scripts, testing details
  Gets exactly: Table structure needed for API

Frontend Developer (Task 5):
  Reads: Backend API summary (400 tokens) ← NOT 11k
  Doesn't see: Database schemas, service implementations
  Gets exactly: API endpoints, request/response formats

Test Engineer (Task 7):
  Reads: Backend + Frontend summaries (800 tokens) ← NOT 26k
  Doesn't see: Database migrations, UI component code
  Gets exactly: What needs testing

Technical Writer (Task 9):
  Reads: Backend API summary (400 tokens) ← NOT 51k
  Doesn't see: Database, frontend, or test implementation
  Gets exactly: API specification for documentation
```

**Result**: Writer reads 400 tokens instead of 51k - **99% reduction in cross-domain noise**.

### 5. Dependency Management with Automatic Context

**The Problem**: You implement Task B that depends on Task A, but you have to manually remember what Task A did.

**The Solution**: Task Orchestrator automatically passes completed task summaries to dependent work.

```
Task A: Create database schema [COMPLETED]
  Summary: "Created Users table with authentication fields..."

Task B: Implement login API [DEPENDS ON: Task A]
  AI automatically receives:
    - Task B requirements
    - Task A Summary (400 tokens)
  AI doesn't receive:
    - Task A full implementation (5k tokens)
```

**Dependency types**:
- **BLOCKS** - This task must complete before next task can start
- **RELATES_TO** - Context sharing without strict ordering
- **IS_BLOCKED_BY** - Reverse dependency tracking

---

## When to Use What: Decision Matrix

### Templates Only (All MCP Clients)

**Use when**:
- ✅ Simple features (1-5 tasks)
- ✅ You're working alone
- ✅ Using any MCP client (Cursor, Windsurf, Claude Desktop)
- ✅ Learning the system

**How it works**:
1. AI discovers templates with `list_templates()`
2. AI creates tasks/features with `templateIds` parameter
3. AI reads template sections for guidance
4. AI implements directly (no sub-agents)

**Example**:
```
You: "Create a task for user authentication API"
AI: Creates task with technical-approach + testing-strategy templates
AI: Reads technical-approach section
AI: Implements code directly
AI: Marks task complete
```

### Templates + Sub-Agent Orchestration (Claude Code Only)

**Use when**:
- ✅ Complex features (6+ tasks with dependencies)
- ✅ Using Claude Code specifically
- ✅ Cross-domain work (database → backend → frontend → tests)
- ✅ Need 97% token reduction for large projects

**How it works**:
1. Orchestrator creates feature with templates applied to tasks
2. Orchestrator launches Feature Manager (START mode)
3. Feature Manager recommends next task → Task Manager
4. Task Manager routes to specialist (Backend Engineer, Database Engineer, etc.)
5. Specialist reads template sections + dependency summaries
6. Specialist implements, creates Summary section
7. Task Manager reports completion (2 sentences)
8. Next task reads Summary instead of full context

**Example**:
```
You: "Create feature for payment processing with 9 tasks"
You: Launch Feature Manager (START mode)
Feature Manager: "Recommends T1: Create transactions table"
You: Launch Task Manager for T1
Task Manager: Routes to Database Engineer
Database Engineer: Reads technical-approach template, implements schema
Database Engineer: Creates 400-token Summary
Task Manager: "Created transactions table with payment fields. Ready for API."
You: Launch Feature Manager again
Feature Manager: "Recommends T2: Payment service (reads T1 Summary)"
[cycle continues with automatic dependency context]
```

**Setup required**: Run `setup_claude_agents` once to create agent definitions.

---

## Compatibility Matrix

| AI Platform | Templates | Agent Recommendations | Autonomous Sub-Agents | Setup |
|-------------|-----------|----------------------|----------------------|-------|
| **Claude Code** | ✅ Full support | ✅ Via `recommend_agent` | ✅ Full support | `setup_claude_agents` |
| **Claude Desktop** | ✅ Full support | ✅ Via `recommend_agent` | ❌ Not available | N/A |
| **Cursor** | ✅ Full support | ✅ Via `recommend_agent` | ❌ Not available | N/A |
| **Windsurf** | ✅ Full support | ✅ Via `recommend_agent` | ❌ Not available | N/A |
| **Any MCP client** | ✅ Full support | ✅ Via `recommend_agent` | ❌ Not available | N/A |

**Legend**:
- **Templates**: Template-driven workflows and documentation structure
- **Agent Recommendations**: `recommend_agent` tool suggests which specialist to use based on task tags
- **Autonomous Sub-Agents**: Platform can launch and coordinate specialized sub-agents automatically

**Note**: Only Claude Code supports autonomous sub-agent orchestration via `.claude/agents/` directory. Other platforms receive agent recommendations through the `recommend_agent` MCP tool but must manually implement the suggested approach.

---

## Quick Start (5 Minutes)

### Step 1: Pull Docker Image

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

### Step 2: Configure Your AI Platform

#### For Claude Code

Run from your project directory:

**macOS/Linux**:
```bash
claude mcp add-json task-orchestrator "{\"type\":\"stdio\",\"command\":\"docker\",\"args\":[\"run\",\"--rm\",\"-i\",\"-v\",\"mcp-task-data:/app/data\",\"-v\",\"$(pwd):/workspace\",\"-e\",\"AGENT_CONFIG_DIR=/workspace\",\"ghcr.io/jpicklyk/task-orchestrator:latest\"]}"
```

**Windows PowerShell**:
```powershell
claude mcp add-json task-orchestrator "{`"type`":`"stdio`",`"command`":`"docker`",`"args`":[`"run`",`"--rm`",`"-i`",`"-v`",`"mcp-task-data:/app/data`",`"-v`",`"${PWD}:/workspace`",`"-e`",`"AGENT_CONFIG_DIR=/workspace`",`"ghcr.io/jpicklyk/task-orchestrator:latest`"]}"
```

#### For Claude Desktop
Add to `claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "mcp-task-data:/app/data",
        "--volume", "/absolute/path/to/your/project:/workspace",
        "--env", "AGENT_CONFIG_DIR=/workspace",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

> **Note**: Replace `/absolute/path/to/your/project` with your project's actual path. For Windows: `C:/Users/username/project`, for macOS/Linux: `/Users/username/project` or `/home/username/project`.

#### For Other MCP Clients
Adapt the Docker configuration to your platform's MCP format (Cursor, Windsurf, etc.). See the [Quick Start Guide](docs/quick-start.md) for detailed configuration examples for all platforms.

### Step 3: Initialize (First Use Only)

Ask your AI:
```
"Run the initialize_task_orchestrator workflow"
```

This loads AI guidelines and best practices into your project.

### Step 4: (Optional) Enable Sub-Agents (Claude Code Only)

Ask your AI:
```
"Run setup_claude_agents to enable sub-agent orchestration"
```

Creates `.claude/agents/` directory with 10 specialist agent definitions.

### Step 5: Start Building

**Simple workflow (any platform)**:
```
You: "Create a feature for user authentication with 4 tasks"
AI: Creates feature with template-driven tasks
You: "Start with the first task"
AI: Reads template sections, implements directly
AI: Marks complete, moves to next task
```

**Advanced workflow (Claude Code with sub-agents)**:
```
You: "Create a feature for payment processing with 9 tasks"
AI: Creates feature with templates applied
You: "Launch Feature Manager for this feature (START mode)"
Feature Manager: "Recommends T1: Create transactions table"
You: "Launch Task Manager for T1"
Task Manager: Routes to Database Engineer
Database Engineer: Implements, creates Summary
[Automatic routing continues with dependency context]
```

> **📖 Complete setup guide**: [Quick Start Documentation](docs/quick-start.md)
>
> **🔧 Advanced configuration**: [Installation Guide](docs/installation-guide.md)

---

## Documentation

### Getting Started
- **[🚀 Quick Start](docs/quick-start.md)** - Get running in 5 minutes
- **[🔧 Installation Guide](docs/installation-guide.md)** - Comprehensive setup for all platforms
- **[🤖 AI Guidelines](docs/ai-guidelines.md)** - How AI uses Task Orchestrator autonomously

### Using Task Orchestrator
- **[🤖 Agent Orchestration](docs/agent-orchestration.md)** - 3-level AI agent coordination system
- **[📊 Token Reduction Examples](docs/token-reduction-examples.md)** - Quantitative before/after analysis
- **[📝 Templates](docs/templates.md)** - 9 built-in documentation templates
- **[📋 Workflow Prompts](docs/workflow-prompts.md)** - 6 workflow automations
- **[🔧 API Reference](docs/api-reference.md)** - Complete MCP tools documentation
- **[🆘 Troubleshooting](docs/troubleshooting.md)** - Solutions to common issues

### For Developers
- **[👨‍💻 Developer Guides](docs/developer-guides/)** - Architecture, contributing, development setup
- **[🗃️ Database Migrations](docs/developer-guides/database-migrations.md)** - Schema change management
- **[💬 Community Wiki](../../wiki)** - Examples, tips, and community guides

---

## Alternative Installation Options

### Without Docker (Direct JAR)
```bash
# Build from source
./gradlew build

# Run
java -jar build/libs/mcp-task-orchestrator-*.jar
```

### Environment Variables
```bash
MCP_TRANSPORT=stdio          # Transport type
DATABASE_PATH=data/tasks.db  # SQLite database path
USE_FLYWAY=true             # Enable migrations
MCP_DEBUG=true              # Enable debug logging
```

> **📖 Complete configuration reference**: [Installation Guide](docs/installation-guide.md)

---

## Integration & Automation

### n8n Workflow Automation
Task Orchestrator integrates with **[n8n](https://n8n.io)** for workflow automation:
- Automated task creation from external systems (Slack, email, webhooks)
- CI/CD integration: Create tasks on deployment, update status on test completion
- Multi-agent orchestration: Coordinate multiple AI agents
- Custom automation: Build complex workflows with 400+ integrations

Learn more: [n8n MCP Integration](https://docs.n8n.io/integrations/builtin/cluster-nodes/sub-nodes/n8n-nodes-langchain.toolmcp/)

### RAG (Retrieval-Augmented Generation)
Task Orchestrator provides **structured knowledge retrieval** for AI agents:
- Project context on demand (projects, features, tasks)
- Template library access
- Progressive loading for token efficiency
- Dynamic knowledge that stays current

### Multi-Tool Ecosystem
Works alongside other MCP tools:
- **GitHub MCP** - Code management and PR workflows
- **File System MCP** - Local project analysis
- **Custom MCP Servers** - Extend with your own tools

---

## Technical Highlights

- **Clean Architecture** - 4-layer design (Domain → Application → Infrastructure → Interface)
- **Kotlin 2.2.0 + Coroutines** - Modern, type-safe, concurrent
- **SQLite + Exposed ORM** - Fast, reliable, zero-config database
- **Flyway Migrations** - Versioned schema management
- **MCP SDK 0.7.2** - Standards-compliant protocol implementation
- **Token Optimized** - 97-99% reduction via sub-agent architecture
- **Template System** - 9 built-in templates with in-memory caching
- **Concurrent Safe** - Built-in collision prevention for multi-agent workflows
- **Docker Ready** - One-command deployment

---

## Use Cases

### Context Persistence Across Sessions
Your AI remembers project state, completed work, and next steps - even after restarting. No re-explaining your codebase.

### Large Feature Implementation
Break down 10+ task features without context limits. Sub-agents maintain manageable context (2k tokens) while traditional approaches fail (311k tokens).

### Cross-Domain Coordination
Database → Backend → Frontend → Testing workflows with automatic context passing. Each specialist sees only what they need.

### Bug Management During Development
Capture bugs and improvements as you find them. Your AI helps you decide: fix now or later?

### Multi-Agent Workflows
Multiple AI agents work in parallel without conflicts. Built-in concurrency protection and bulk operations.

---

## Troubleshooting

**Quick Fixes**:
- **Claude can't find tools**: Restart Claude Desktop
- **Docker not running**: Start Docker Desktop, verify with `docker version`
- **Connection problems**: Enable `MCP_DEBUG=true` and check logs
- **Sub-agents not available**: Run `setup_claude_agents` (Claude Code only)

**Get Help**:
- 📖 [Troubleshooting Guide](docs/troubleshooting.md) - Comprehensive solutions
- 💬 [Community Discussions](../../discussions) - Ask questions and share ideas
- 🐛 [Report Issues](../../issues) - Bug reports and feature requests

---

## Contributing

We welcome contributions! Task Orchestrator is built with:
- **Kotlin 2.2.0** with Coroutines
- **Exposed ORM** for SQLite
- **MCP SDK 0.7.2** for protocol implementation
- **Clean Architecture** with 4 distinct layers

**To contribute**:
1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Submit a pull request

See [contributing guidelines](docs/developer-guides/index.md#contributing) for detailed development setup.

---

## Release Information

Version follows semantic versioning with git-based build numbers:
- Format: `{major}.{minor}.{patch}.{git-commit-count}-{qualifier}`
- Stable releases remove qualifier (e.g., `1.0.0.123`)
- Pre-releases include qualifier (e.g., `1.0.0.123-beta-01`)

Current versioning defined in [build.gradle.kts](build.gradle.kts).

- 🔔 [Watch for releases](../../releases)
- 📋 [View changelog](CHANGELOG.md)

---

## Keywords & Topics

**AI Coding Tools**: AI coding assistant, AI pair programming, AI development tools, AI code completion, AI assisted development, AI programming assistant

**Model Context Protocol**: MCP, Model Context Protocol, MCP server, MCP tools, MCP integration, MCP compatible, MCP SDK

**AI Platforms**: Claude Desktop, Claude Code, Claude AI, Cursor IDE, Cursor AI, Windsurf, Anthropic Claude, AI editor integration

**Task Management**: AI task management, context persistence, AI memory, persistent context, AI project management, lightweight task tracking, developer task management

**Technical**: RAG, retrieval augmented generation, AI context window, token optimization, AI workflow automation, n8n integration, workflow orchestration, sub-agent architecture

**Development**: vibe coding, agile development, AI development workflow, code with AI, AI developer tools, AI coding workflow

**Use Cases**: AI loses context, AI context loss, AI session persistence, AI memory across sessions, persistent AI assistant, stateful AI, context pollution, token exhaustion

---

## License

[MIT License](LICENSE) - Free for personal and commercial use

---

**Ready to solve AI context pollution?**

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

Then configure your AI platform and start building complex features without hitting context limits. 🚀
