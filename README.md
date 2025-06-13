# MCP Task Orchestrator

A Kotlin implementation of the Model Context Protocol (MCP) server for comprehensive task management, providing AI assistants with a structured, context-efficient way to interact with project data.

[![Version](https://img.shields.io/github/v/release/jpicklyk/task-orchestrator?include_prereleases)](https://github.com/jpicklyk/task-orchestrator/releases)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL%203.0-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

## 📚 Documentation

- **[📖 Full Documentation](docs/)** - Complete guides and reference
- **[🚀 Quick Start Guide](docs/quick-start.md)** - Get running in 2 minutes
- **[🔧 API Reference](docs/api-reference.md)** - All 37 MCP tools detailed
- **[📋 Workflow Prompts](docs/workflow-prompts.md)** - 5 built-in workflow automations
- **[📝 Templates](docs/templates.md)** - 9 built-in documentation templates
- **[🗃️ Database Migrations](docs/database-migrations.md)** - Schema change management for developers
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

### 1. Pull or Build Docker Image

#### Option A: Production Image (Recommended)
```bash
# Pull latest release
docker pull ghcr.io/jpicklyk/task-orchestrator:latest

# Or specific version
docker pull ghcr.io/jpicklyk/task-orchestrator:1.0.0

# Or latest build from main branch
docker pull ghcr.io/jpicklyk/task-orchestrator:main
```

#### Option B: Build Locally (Development)
```bash
# Build locally
./scripts/docker-clean-and-build.bat  # Windows
# Or manually: docker build -t mcp-task-orchestrator:dev .
```

### 2. Configure Claude Desktop or Claude Code

#### For Claude Desktop
Add to your `claude_desktop_config.json`:

**Production Configuration**
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

**Local Development Configuration**
```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "--volume", "mcp-task-data:/app/data",
        "mcp-task-orchestrator:dev"
      ]
    }
  }
}
```

#### For Claude Code
Use the JSON configuration command:

```bash
# Production version (latest release)
claude mcp add-json task-orchestrator '{"type":"stdio","command":"docker","args":["run","--rm","-i","-v","mcp-task-data:/app/data","ghcr.io/jpicklyk/task-orchestrator:latest"]}'

# Specific version
claude mcp add-json task-orchestrator '{"type":"stdio","command":"docker","args":["run","--rm","-i","-v","mcp-task-data:/app/data","ghcr.io/jpicklyk/task-orchestrator:1.0.0"]}'

# Latest from main branch
claude mcp add-json task-orchestrator '{"type":"stdio","command":"docker","args":["run","--rm","-i","-v","mcp-task-data:/app/data","ghcr.io/jpicklyk/task-orchestrator:main"]}'

# Local development version (after building locally)
claude mcp add-json task-orchestrator '{"type":"stdio","command":"docker","args":["run","--rm","-i","-v","mcp-task-data:/app/data","mcp-task-orchestrator:dev"]}'
```

### 3. Test Connection (Optional)
```bash
# Test the Docker container runs correctly
docker run --rm -i -v mcp-task-data:/app/data ghcr.io/jpicklyk/task-orchestrator:latest

# Test MCP connection (requires Node.js)
node scripts/test-mcp-connection.js
```

### 4. Start Using
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
- `project_setup_workflow` - Complete project initialization
- `implement_feature_workflow` - Git-aware feature implementation with completion validation

### MCP Tools (37 Total)
- **6 Task Management Tools** - Core CRUD operations
- **5 Feature Management Tools** - Group related work
- **5 Project Management Tools** - Top-level organization
- **3 Dependency Management Tools** - Model relationships
- **9 Section Management Tools** - Rich documentation
- **9 Template Management Tools** - Workflow automation

## Alternative Installation Options

### Option 1: Direct JAR (Without Docker)
```bash
# Build
./gradlew build

# Run
java -jar build/libs/mcp-task-orchestrator-*.jar
```

### Option 2: Development Environment Variables
```bash
# Configure environment for local development
MCP_TRANSPORT=stdio
DATABASE_PATH=data/tasks.db
USE_FLYWAY=true
MCP_DEBUG=true  # Enable debug logging
```

## Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `MCP_TRANSPORT` | Transport type | `stdio` |
| `DATABASE_PATH` | SQLite database path | `data/tasks.db` |
| `USE_FLYWAY` | Enable Flyway database migrations | `true` |
| `MCP_SERVER_NAME` | Server name | `mcp-task-orchestrator` |
| `MCP_DEBUG` | Enable debug logging | `false` |

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