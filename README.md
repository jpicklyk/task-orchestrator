# MCP Task Orchestrator

A Kotlin implementation of the Model Context Protocol (MCP) server for comprehensive task management, providing AI assistants with a structured, context-efficient way to interact with project data.

[![Pre-Release](https://img.shields.io/badge/status-pre--release-orange)](https://github.com/jpicklyk/task-orchestrator/releases)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL%203.0-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

## 📚 Documentation

- **[📖 Full Documentation](docs/)** - Complete guides and reference
- **[🚀 Quick Start Guide](docs/quick-start.md)** - Get running in 2 minutes
- **[🔧 API Reference](docs/api-reference.md)** - All 37 MCP tools detailed
- **[📋 Workflow Prompts](docs/workflow-prompts.md)** - 5 built-in workflow automations
- **[📝 Templates](docs/templates.md)** - 9 built-in documentation templates
- **[💬 Community Wiki](../../wiki)** - Examples, tips, and community guides

## Why Use MCP Task Orchestrator?

- **🤖 AI-Native**: Designed specifically for AI assistant workflows
- **📊 Hierarchical Organization**: Projects → Features → Tasks with dependencies
- **🎯 Context-Efficient**: Progressive loading and token optimization
- **📋 Template-Driven**: 9 built-in templates for consistent documentation
- **🔄 Workflow Automation**: 5 comprehensive workflow prompts
- **🔗 Rich Relationships**: Task dependencies with cycle detection
- **⚡ 37 MCP Tools**: Complete task orchestration API

## Quick Start (2 Minutes)

### 1. Run with Docker
```bash
# Quick test run
docker run --rm -i -v mcp-task-data:/app/data mcp-task-orchestrator

# Or build locally
./scripts/docker-clean-and-build.bat  # Windows
```

### 2. Configure Claude Desktop
Add to your `claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "mcp-task-data:/app/data",
        "mcp-task-orchestrator"
      ]
    }
  }
}
```

### 3. Start Using
Ask Claude:
- "Create a new project for my web application"
- "Show me the project overview"
- "Apply the technical approach template to this task"

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

## Key Features

### Template System (9 Built-in Templates)
- **AI Workflow Instructions**: Git workflows, PR management, task implementation, bug investigation
- **Documentation Properties**: Technical approach, requirements, context & background  
- **Process & Quality**: Testing strategy, definition of done

### Workflow Prompts (5 Built-in Workflows)
- `create_feature_workflow` - Comprehensive feature creation
- `task_breakdown_workflow` - Complex task decomposition
- `bug_triage_workflow` - Systematic bug management
- `sprint_planning_workflow` - Data-driven sprint planning
- `project_setup_workflow` - Complete project initialization

### MCP Tools (37 Total)
- **6 Task Management Tools** - Core CRUD operations
- **5 Feature Management Tools** - Group related work
- **5 Project Management Tools** - Top-level organization
- **3 Dependency Management Tools** - Model relationships
- **9 Section Management Tools** - Rich documentation
- **9 Template Management Tools** - Workflow automation

## Installation Options

### Option 1: Docker (Recommended)
```bash
# Build and run
./scripts/docker-clean-and-build.bat

# Configure environment
MCP_TRANSPORT=stdio
DATABASE_PATH=data/tasks.db
```

### Option 2: Direct JAR
```bash
# Build
./gradlew build

# Run
java -jar build/libs/mcp-task-orchestrator-*.jar
```

## Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `MCP_TRANSPORT` | Transport type | `stdio` |
| `DATABASE_PATH` | SQLite database path | `data/tasks.db` |
| `MCP_SERVER_NAME` | Server name | `mcp-task-orchestrator` |
| `MCP_DEBUG` | Enable debug logging | `false` |

## Pre-Release Status

**⚠️ Current Version: Pre-1.0.0 (Development)**

The SQL database schema may change between updates. For production use, wait for the 1.0.0 release.

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

## Troubleshooting

### Common Issues
- **JSON parsing errors**: Enable `MCP_DEBUG=true` and check logs in `logs/`
- **Docker issues**: Ensure Docker Desktop is running and `docker volume inspect mcp-task-data`
- **Connection problems**: Test with the echo tool (see [troubleshooting guide](docs/troubleshooting.md))

### Getting Help
- 📖 [Full troubleshooting guide](docs/troubleshooting.md)
- 💬 [Community discussions](../../discussions)
- 🐛 [Report issues](../../issues)

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Submit a pull request

See [contributing guidelines](CONTRIBUTING.md) for details.

## License

[AGPL-3.0 License](LICENSE)