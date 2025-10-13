# MCP Task Orchestrator

**AI coding assistant memory that persists across sessions - for Claude Desktop, Claude Code, Cursor, Windsurf, and any MCP-compatible AI**

A Kotlin implementation of the Model Context Protocol (MCP) server for AI task management and context persistence. Provides AI coding assistants with structured, persistent memory - eliminating context loss between sessions.

[![Version](https://img.shields.io/github/v/release/jpicklyk/task-orchestrator?include_prereleases)](https://github.com/jpicklyk/task-orchestrator/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![MCP Compatible](https://img.shields.io/badge/MCP-Compatible-purple)](https://modelcontextprotocol.io)

---

## The Problem

AI coding assistants like Claude, Cursor, and Windsurf lose context between sessions. You spend time re-explaining your codebase, reminding them what's complete, and rebuilding project understanding every morning.

**MCP Task Orchestrator provides persistent AI memory** - your AI coding assistant remembers project state, completed work, and next steps across sessions. Works with Claude Desktop, Claude Code, Cursor, Windsurf, and any Model Context Protocol compatible tool.

---

## Why Use MCP Task Orchestrator?

- **🤖 AI-Native**: Designed for AI assistant workflows with autonomous pattern recognition
- **🧠 Persistent Memory**: AI remembers context across sessions - no re-explaining your codebase
- **📊 Hierarchical Organization**: Projects → Features → Tasks with dependencies
- **🎯 Context-Efficient**: Progressive loading, token optimization, and template caching
- **⚡ Bulk Operations**: 70-95% token reduction for multi-task updates
- **📋 Template-Driven**: 9 built-in templates for consistent documentation
- **🔄 Workflow Automation**: 6 comprehensive workflow prompts for common scenarios
- **🔗 Rich Relationships**: Task dependencies with cycle detection
- **🔒 Concurrent Access Protection**: Built-in sub-agent collision prevention
- **🚀 38 MCP Tools**: Complete task orchestration API

---

## 📚 Documentation

**Getting Started**:
- **[🚀 Quick Start](docs/quick-start.md)** - Get running in 2 minutes
- **[🔧 Installation Guide](docs/installation-guide.md)** - Comprehensive setup for all platforms
- **[🤖 AI Guidelines](docs/ai-guidelines.md)** - How AI uses Task Orchestrator autonomously

**Using Task Orchestrator**:
- **[📝 Templates](docs/templates.md)** - 9 built-in documentation templates
- **[📋 Workflow Prompts](docs/workflow-prompts.md)** - 6 workflow automations
- **[🔧 API Reference](docs/api-reference.md)** - Complete MCP tools documentation
- **[🆘 Troubleshooting](docs/troubleshooting.md)** - Solutions to common issues

**For Developers**:
- **[👨‍💻 Developer Guides](docs/developer-guides/)** - Architecture, contributing, development setup
- **[🗃️ Database Migrations](docs/developer-guides/database-migrations.md)** - Schema change management
- **[💬 Community Wiki](../../wiki)** - Examples, tips, and community guides

---

## Quick Start (2 Minutes)

### Step 1: Pull Docker Image
```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

### Step 2: Configure Your AI Agent

**For Claude Desktop**, add to `claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "mcp-task-data:/app/data",
        "ghcr.io/jpicklyk/task-orchestrator:latest"
      ]
    }
  }
}
```

**For Claude Code**, use the MCP configuration command:
```bash
claude mcp add-json task-orchestrator '{"type":"stdio","command":"docker","args":["run","--rm","-i","-v","mcp-task-data:/app/data","ghcr.io/jpicklyk/task-orchestrator:latest"]}'
```

**For other MCP-compatible AI agents** (Cursor, Windsurf, etc.), use similar Docker configuration adapted to your agent's format.

### Step 3: Restart Your AI Agent

### Step 4: Start Using

Ask your AI agent:
- "Create a new project for my web application"
- "Show me the project overview"
- "Apply the technical approach template to this task"

> **📖 Full Quick Start Guide**: See [docs/quick-start.md](docs/quick-start.md) for detailed instructions including Claude Code setup, building from source, and troubleshooting.
>
> **🔧 Advanced Installation**: See [docs/installation-guide.md](docs/installation-guide.md) for all installation options, environment variables, and platform-specific instructions.
>
> **⭐ PRD-Driven Development**: For best results, provide Claude with Product Requirements Documents (PRDs) for intelligent breakdown into features and tasks with proper dependencies. See [PRD Workflow Guide](docs/quick-start.md#prd-driven-development-workflow).

---

## Core Concepts

```
Project (optional)
  └── Feature (optional)
      └── Task (required) ←→ Dependencies → Task
          └── Section (optional, detailed content)
```

- **Projects**: Top-level organizational containers
- **Features**: Group related tasks into functional units
- **Tasks**: Primary work units with status, priority, complexity
- **Dependencies**: Relationships between tasks (BLOCKS, IS_BLOCKED_BY, RELATES_TO)
- **Sections**: Rich content blocks for documentation
- **Templates**: Standardized documentation patterns

---

## AI-Native Design

Task Orchestrator includes a comprehensive **AI Guidelines and Initialization System** that enables AI agents to use the system autonomously through natural language pattern recognition:

- **Three-Layer Architecture**: MCP Resources (internalized knowledge) + Workflow Prompts (explicit guidance) + Dynamic Templates (database-driven)
- **Autonomous Pattern Recognition**: AI recognizes user intent like "help me plan this feature" without explicit commands
- **Dual Workflow Model**: Autonomous pattern application for speed + explicit workflow invocation for comprehensive guidance
- **Template Discovery**: AI dynamically discovers and applies appropriate templates based on work type
- **Git Workflow Detection**: Automatic .git directory detection triggers git workflow templates

> **See**: [AI Guidelines Documentation](docs/ai-guidelines.md) for complete initialization process and autonomous workflow patterns

---

## Integration & Automation

### Workflow Automation with n8n
Task Orchestrator integrates seamlessly with **[n8n](https://n8n.io)**, the open-source workflow automation platform with 400+ integrations and AI orchestration capabilities.

**n8n's MCP Client Tool node** allows workflows to:
- Query task status and retrieve project context
- Create and update tasks programmatically
- Trigger workflows based on task state changes
- Orchestrate multi-step AI workflows with task tracking

**Example use cases**:
- Automated task creation from external systems (Slack, email, webhooks)
- CI/CD integration: Create tasks on deployment, update status on test completion
- Multi-agent orchestration: Coordinate multiple AI agents working on different tasks
- Custom automation: Build complex workflows combining task management with external APIs

Learn more: [n8n MCP Integration](https://docs.n8n.io/integrations/builtin/cluster-nodes/sub-nodes/n8n-nodes-langchain.toolmcp/)

### RAG (Retrieval-Augmented Generation)
Task Orchestrator provides **structured knowledge retrieval** for AI agents through the MCP Resources system:

- **Project Context**: AI agents retrieve relevant project, feature, and task information on demand
- **Template Library**: Access to documentation templates and workflow patterns
- **Efficient Retrieval**: Progressive loading and selective section fetching optimize token usage
- **Dynamic Knowledge**: Task states, dependencies, and documentation stay current in AI context

This enables AI to maintain accurate, up-to-date project knowledge without manual context injection.

### Multi-Tool Ecosystem
Works alongside other MCP tools for comprehensive AI-assisted development:
- **GitHub MCP**: Code management and PR workflows
- **File System MCP**: Local project analysis and file operations
- **Custom MCP Servers**: Extend with your own tools and integrations

---

## Use Cases

### Context Persistence Across Sessions
Your AI remembers project state, completed work, and next steps - even after restarting your editor or taking a break. No need to re-explain your codebase every session.

### Feature Breakdown and Tracking
Break down complex features into manageable tasks. Your AI tracks dependencies and helps you work in the right order.

### Bug Management During Development
Capture bugs and improvements as you find them without losing focus on current work. Your AI helps you decide whether to fix now or later.

### Multi-Agent Workflows
Multiple AI agents can work in parallel without conflicts, thanks to built-in concurrency protection and bulk operations.

---

## Key Features

### Template System (9 Built-in Templates)
- **AI Workflow Instructions**: Git workflows, PR management, task implementation, bug investigation
- **Documentation Properties**: Technical approach, requirements, context & background
- **Process & Quality**: Testing strategy, definition of done

> **See**: [Templates Documentation](docs/templates.md) for AI-driven template discovery and composition patterns

### Workflow Prompts (6 Built-in Workflows)
- `initialize_task_orchestrator` - AI initialization and guideline loading
- `create_feature_workflow` - Comprehensive feature creation
- `task_breakdown_workflow` - Complex task decomposition
- `project_setup_workflow` - Complete project initialization
- `implementation_workflow` - Git-aware implementation workflow for tasks, features, and bugs with completion validation

> **See**: [Workflow Prompts Documentation](docs/workflow-prompts.md) for dual workflow model (autonomous vs. explicit)

### MCP Tools (38 Total)
- **8 Task Management Tools** - Core CRUD operations including bulk updates
- **6 Feature Management Tools** - Group related work
- **6 Project Management Tools** - Top-level organization
- **3 Dependency Management Tools** - Model relationships
- **9 Section Management Tools** - Rich documentation
- **9 Template Management Tools** - Workflow automation

> **See**: [API Reference](docs/api-reference.md) for workflow-based tool patterns and AI usage examples

---

## Alternative Installation Options

### Without Docker (Direct JAR)
```bash
./gradlew build
java -jar build/libs/mcp-task-orchestrator-*.jar
```

### Environment Variables
```bash
MCP_TRANSPORT=stdio          # Transport type
DATABASE_PATH=data/tasks.db  # SQLite database path
USE_FLYWAY=true             # Enable migrations
MCP_DEBUG=true              # Enable debug logging
```

> **📖 Complete Configuration Reference**: See [Installation Guide](docs/installation-guide.md) for all environment variables, platform-specific instructions, and advanced configuration options.

---

## Release Information

Version follows semantic versioning with git-based build numbers:

- Format: `{major}.{minor}.{patch}.{git-commit-count}-{qualifier}`
- Stable releases remove the qualifier (e.g., `1.0.0.123`)
- Pre-releases include qualifier (e.g., `1.0.0.123-beta-01`)

Current versioning defined in [build.gradle.kts](build.gradle.kts).

- 🔔 [Watch for releases](../../releases)
- 📋 [View changelog](CHANGELOG.md)

---

## Development & Testing

```bash
# Run tests
./gradlew test

# Debug mode
MCP_DEBUG=true java -jar build/libs/mcp-task-orchestrator-*.jar
```

> **👨‍💻 For Developers**: See [Developer Guides](docs/developer-guides/) for architecture, contributing guidelines, development setup, and database migration management.

---

## Troubleshooting

**Quick Fixes**:
- **Claude can't find tools**: Restart Claude Desktop
- **Docker not running**: Start Docker Desktop, check with `docker version`
- **Connection problems**: Enable `MCP_DEBUG=true` and check logs

**Get Help**:
- 📖 [Troubleshooting Guide](docs/troubleshooting.md) - Quick reference table, AI-specific issues, and comprehensive solutions
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

See [contributing guidelines](CONTRIBUTING.md) for detailed development setup and guidelines.

---

## Technical Highlights

- **38 MCP Tools** - Complete task orchestration API
- **Token Optimized** - 70-95% token savings with bulk operations
- **Template System** - 9 built-in workflow templates with in-memory caching
- **Concurrent Safe** - Built-in collision prevention for multi-agent workflows
- **Kotlin + SQLite** - Fast, reliable, zero-config database
- **Clean Architecture** - Well-structured codebase for contributors
- **Flyway Migrations** - Versioned database schema management

---

## Keywords & Topics

**AI Coding Tools**: AI coding assistant, AI pair programming, AI development tools, AI code completion, AI assisted development, AI programming assistant

**Model Context Protocol**: MCP, Model Context Protocol, MCP server, MCP tools, MCP integration, MCP compatible, MCP SDK

**AI Platforms**: Claude Desktop, Claude Code, Claude AI, Cursor IDE, Cursor AI, Windsurf, Anthropic Claude, AI editor integration

**Task Management**: AI task management, context persistence, AI memory, persistent context, AI project management, lightweight task tracking, developer task management

**Technical**: RAG, retrieval augmented generation, AI context window, token optimization, AI workflow automation, n8n integration, workflow orchestration

**Development**: vibe coding, agile development, AI development workflow, code with AI, AI developer tools, AI coding workflow

**Use Cases**: AI loses context, AI context loss, AI session persistence, AI memory across sessions, persistent AI assistant, stateful AI

---

## License

[MIT License](LICENSE) - Free for personal and commercial use

---

**Ready to give your AI persistent memory?**

```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

Then configure your AI agent and start building. Your AI will remember everything. 🚀
