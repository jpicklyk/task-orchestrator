# MCP Task Orchestrator

A Kotlin implementation of the Model Context Protocol (MCP) server for comprehensive task management, providing AI assistants with a structured, context-efficient way to interact with project data.

[![Version](https://img.shields.io/github/v/release/jpicklyk/task-orchestrator?include_prereleases)](https://github.com/jpicklyk/task-orchestrator/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

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

## Why Use MCP Task Orchestrator?

- **🤖 AI-Native**: Designed specifically for AI assistant workflows with autonomous pattern recognition
- **📊 Hierarchical Organization**: Projects → Features → Tasks with dependencies
- **🎯 Context-Efficient**: Progressive loading and token optimization
- **📋 Template-Driven**: 9 built-in templates for consistent documentation
- **🔄 Workflow Automation**: 6 comprehensive workflow prompts for common scenarios
- **🔗 Rich Relationships**: Task dependencies with cycle detection
- **🔒 Concurrent Access Protection**: Built-in sub-agent collision prevention
- **⚡ 37 MCP Tools**: Complete task orchestration API

## Quick Start (2 Minutes)

Get Task Orchestrator running with your MCP-compatible AI agent:

**Step 1: Pull Docker Image**
```bash
docker pull ghcr.io/jpicklyk/task-orchestrator:latest
```

**Step 2: Configure Your AI Agent**

For **Claude Desktop**, add to `claude_desktop_config.json`:
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

For **Claude Code**, use the MCP configuration command:
```bash
claude mcp add-json task-orchestrator '{"type":"stdio","command":"docker","args":["run","--rm","-i","-v","mcp-task-data:/app/data","ghcr.io/jpicklyk/task-orchestrator:latest"]}'
```

For **other MCP-compatible AI agents** (Cursor, Windsurf, etc.), use similar Docker configuration adapted to your agent's format.

**Step 3: Restart/Reconnect Your AI Agent**

**Step 4: Start Using**

Ask your AI agent:
- "Create a new project for my web application"
- "Show me the project overview"
- "Apply the technical approach template to this task"

> **📖 Full Quick Start Guide**: See [docs/quick-start.md](docs/quick-start.md) for detailed instructions including Claude Code setup, building from source, and troubleshooting.
>
> **🔧 Advanced Installation**: See [docs/installation-guide.md](docs/installation-guide.md) for all installation options, environment variables, and platform-specific instructions.
>
> **⭐ PRD-Driven Development**: For best results, provide Claude with Product Requirements Documents (PRDs) for intelligent breakdown into features and tasks with proper dependencies. See [PRD Workflow Guide](docs/quick-start.md#prd-driven-development-workflow).

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

## AI-Native Design

Task Orchestrator includes a comprehensive **AI Guidelines and Initialization System** that enables AI agents to use the system autonomously through natural language pattern recognition:

- **Three-Layer Architecture**: MCP Resources (internalized knowledge) + Workflow Prompts (explicit guidance) + Dynamic Templates (database-driven)
- **Autonomous Pattern Recognition**: AI recognizes user intent like "help me plan this feature" without explicit commands
- **Dual Workflow Model**: Autonomous pattern application for speed + explicit workflow invocation for comprehensive guidance
- **Template Discovery**: AI dynamically discovers and applies appropriate templates based on work type
- **Git Workflow Detection**: Automatic .git directory detection triggers git workflow templates

> **See**: [AI Guidelines Documentation](docs/ai-guidelines.md) for complete initialization process and autonomous workflow patterns

## Key Features

### Template System (9 Built-in Templates)
- **AI Workflow Instructions**: Git workflows, PR management, task implementation, bug investigation
- **Documentation Properties**: Technical approach, requirements, context & background
- **Process & Quality**: Testing strategy, definition of done

> **See**: [Templates Documentation](docs/templates.md) for AI-driven template discovery and composition patterns

### Workflow Prompts (5 Built-in Workflows)
- `initialize_task_orchestrator` - AI initialization and guideline loading
- `create_feature_workflow` - Comprehensive feature creation
- `task_breakdown_workflow` - Complex task decomposition
- `project_setup_workflow` - Complete project initialization
- `implementation_workflow` - Git-aware implementation workflow for tasks, features, and bugs with completion validation

> **See**: [Workflow Prompts Documentation](docs/workflow-prompts.md) for dual workflow model (autonomous vs. explicit)

### MCP Tools (37 Total)
- **6 Task Management Tools** - Core CRUD operations
- **5 Feature Management Tools** - Group related work
- **5 Project Management Tools** - Top-level organization
- **3 Dependency Management Tools** - Model relationships
- **9 Section Management Tools** - Rich documentation
- **9 Template Management Tools** - Workflow automation

> **See**: [API Reference](docs/api-reference.md) for workflow-based tool patterns and AI usage examples

## Alternative Installation Options

**Without Docker (Direct JAR)**:
```bash
./gradlew build
java -jar build/libs/mcp-task-orchestrator-*.jar
```

**Environment Variables**:
```bash
MCP_TRANSPORT=stdio          # Transport type
DATABASE_PATH=data/tasks.db  # SQLite database path
USE_FLYWAY=true             # Enable migrations
MCP_DEBUG=true              # Enable debug logging
```

> **📖 Complete Configuration Reference**: See [Installation Guide](docs/installation-guide.md) for all environment variables, platform-specific instructions, and advanced configuration options.

## Release Information

Version follows semantic versioning with git-based build numbers:

- Format: `{major}.{minor}.{patch}.{git-commit-count}-{qualifier}`
- Stable releases remove the qualifier (e.g., `1.0.0.123`)
- Pre-releases include qualifier (e.g., `1.0.0.123-beta-01`)

Current versioning defined in [build.gradle.kts](build.gradle.kts).

- 🔔 [Watch for releases](../../releases)
- 📋 [View changelog](CHANGELOG.md)

## Development & Testing

```bash
# Run tests
./gradlew test

# Test MCP connection
node scripts/test-mcp-connection.js

# Debug mode
MCP_DEBUG=true java -jar build/libs/mcp-task-orchestrator-*.jar
```

> **👨‍💻 For Developers**: See [Developer Guides](docs/developer-guides/) for architecture, contributing guidelines, development setup, and database migration management.

## Troubleshooting

**Quick Fixes**:
- **Claude can't find tools**: Restart Claude Desktop
- **Docker not running**: Start Docker Desktop, check with `docker version`
- **Connection problems**: Enable `MCP_DEBUG=true` and check logs

**Get Help**:
- 📖 [Troubleshooting Guide](docs/troubleshooting.md) - Quick reference table, AI-specific issues, and comprehensive solutions
- 💬 [Community Discussions](../../discussions) - Ask questions and share ideas
- 🐛 [Report Issues](../../issues) - Bug reports and feature requests

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Submit a pull request

See [contributing guidelines](CONTRIBUTING.md) for details.

## License

[MIT License](LICENSE)
