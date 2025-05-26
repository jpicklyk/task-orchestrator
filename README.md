# MCP Task Orchestrator

A Kotlin implementation of the Model Context Protocol (MCP) server for comprehensive task management, providing AI
assistants with a structured, context-efficient way to interact with project data.

## Overview

The MCP Task Orchestrator enables AI assistants to create, update, retrieve, and manage tasks, features, and projects
while maintaining context efficiency and optimizing token usage. It features sophisticated task organization,
template-driven documentation, and automated workflows to improve project management for AI-assisted development.

## Pre-Release Status

**Current Version: Pre-1.0.0 (Development)**

This project is actively being developed toward a 1.0.0 release. **The SQL database schema is not finalized and may
change between updates.** For production use, please wait for the 1.0.0 release when the schema will be stable.

To track releases and updates:

- Watch this repository for release notifications
- Check the [Releases](https://github.com/your-username/mcp-task-orchestrator/releases) page for version updates
- Review the [CHANGELOG](CHANGELOG.md) for breaking changes

## Key Features

### Core Functionality

- **Hierarchical Organization**: Projects, Features, Tasks with flexible relationships
- **Template System**: 9 built-in templates organized into 3 categories:
    - **AI Workflow Instructions**: Git workflows, PR management, task implementation, bug investigation
    - **Documentation Properties**: Technical approach, requirements, context & background
    - **Process & Quality**: Testing strategy, definition of done
- **Context Optimization**: Progressive loading, summary views, and efficient data structures
- **Task Type Classification**: Standardized tagging system (bug, feature, enhancement, research, maintenance)

### Technical Features

- SQLite database storage with comprehensive schema
- Model Context Protocol (MCP) server implementation
- Docker containerization support
- Structured section-based content for detailed documentation
- Dependency tracking and relationship validation
- Bulk operations for efficient data management

## Getting Started

### Prerequisites

- JDK 17 or higher
- Kotlin 1.9 or higher
- Docker Desktop (recommended for containerized deployment)

### Option 1: Docker Deployment (Recommended)

#### Building and Running with Docker

Use the provided build script to create and deploy the Docker container:

```bash
# Windows
./scripts/docker-clean-and-build.bat

# Linux/Mac (create equivalent shell script)
./scripts/docker-clean-and-build.sh
```

This script:

1. Builds the project with Gradle
2. Creates a Docker image tagged as `mcp-task-orchestrator`
3. Sets up the necessary Docker volumes for data persistence

#### Claude Desktop Integration

To use the MCP Task Orchestrator with Claude Desktop, add the following configuration to your
`claude_desktop_config.json` file:

```json
{
  "mcpServers": {
    "task-orchestrator": {
      "command": "docker",
      "args": [
        "run",
        "-l",
        "mcp.client=claude-desktop",
        "--rm",
        "-i",
        "--volume",
        "mcp-task-data:/app/data",
        "mcp-task-orchestrator"
      ]
    }
  }
}
```

This configuration:

- Runs the MCP server in a Docker container
- Labels the container for Claude Desktop
- Persists data using a Docker volume
- Automatically removes the container when Claude disconnects

### Option 2: Direct JAR Execution

#### Building

```bash
./gradlew build
```

#### Running

```bash
java -jar build/libs/mcp-task-orchestrator-*.jar
```

With environment variables:

```bash
MCP_TRANSPORT=stdio DATABASE_PATH=data/tasks.db java -jar build/libs/mcp-task-orchestrator-*.jar
```

## Configuration

| Environment Variable | Description                        | Default               |
|----------------------|------------------------------------|-----------------------|
| `MCP_TRANSPORT`      | Transport type (stdio)             | stdio                 |
| `DATABASE_PATH`      | Path to SQLite database file       | data/tasks.db         |
| `MCP_SERVER_NAME`    | Server name for MCP information    | mcp-task-orchestrator |
| `MCP_DEBUG`          | Enable debug mode                  | false                 |

## Template System

The MCP Task Orchestrator includes a comprehensive template system with 9 built-in templates:

### AI Workflow Instructions

- **Local Git Branching Workflow**: Step-by-step git operations and branch management
- **GitHub PR Workflow**: Pull request creation and management using GitHub MCP tools
- **Task Implementation Workflow**: Systematic approach for implementing tasks
- **Bug Investigation Workflow**: Structured debugging and bug resolution process

### Documentation Properties

- **Technical Approach**: Architecture decisions and implementation strategy
- **Requirements Specification**: Functional and non-functional requirements
- **Context & Background**: Business context and stakeholder information

### Process & Quality

- **Testing Strategy**: Comprehensive testing approach and quality gates
- **Definition of Done**: Completion criteria and handoff requirements

Templates can be applied individually or in combination to create structured documentation for projects, features, and
tasks.

## Task Type Classification

The system uses a standardized tagging convention for task types:

- `task-type-bug`: Issues, defects, and fixes
- `task-type-feature`: New functionality
- `task-type-enhancement`: Improvements to existing features
- `task-type-research`: Investigation and analysis work
- `task-type-maintenance`: Refactoring, updates, and technical debt

This enables easy filtering and organization in task overviews and search results.

## Development

### Testing

```bash
./gradlew test
```

### Testing MCP Connection

Run the included test script to verify your connection:

```bash
node scripts/test-mcp-connection.js
```

This will start the server and run basic connectivity tests to ensure proper JSON-RPC communication.

## Troubleshooting

### JSON Parsing Errors

If you see errors like `"Unexpected number in JSON at position 1"` or
`"Unexpected token 'j', "java.util."... is not valid JSON"`, this indicates that non-JSON content (likely Java exception
stack traces) is being mixed into the JSON-RPC message stream.

#### Solution

Enable debug mode to diagnose the issue:

```bash
MCP_DEBUG=true java -jar build/libs/mcp-task-orchestrator-*.jar
```

This will create detailed logs in the `logs` directory:
- `task-orchestrator.log` - General application logs

### Docker Issues

If you encounter Docker-related issues:

1. Ensure Docker Desktop is running
2. Check that the `mcp-task-data` volume has proper permissions
3. Verify the container can access the data directory:

```bash
docker volume inspect mcp-task-data
```

### Advanced Debugging

For advanced debugging, you can:

1. Examine the detailed message logs in the `logs` directory
2. Use the echo tool to test basic connectivity:

```json
{
   "jsonrpc": "2.0",
   "id": 1,
   "method": "tools/call",
   "params": {
      "name": "echo",
      "arguments": {
         "message": "Hello, MCP server!"
      }
   }
}
```

## Available MCP Tools

The server provides comprehensive tools for task management:

- **Project Management**: create_project, get_project, update_project, delete_project, search_projects
- **Feature Management**: create_feature, get_feature, update_feature, delete_feature, search_features
- **Task Management**: create_task, get_task, update_task, delete_task, search_tasks, get_task_overview
- **Section Management**: add_section, get_sections, update_section, delete_section, bulk_create_sections
- **Template Management**: list_templates, apply_template, create_template, get_template

All tools are designed for context efficiency and support progressive loading of related data.

## Data Model

The system uses a hierarchical structure:

```
Project (optional)
  ??? Feature (optional)
      ??? Task (required)
          ??? Section (optional, for detailed content)
```

- **Projects**: Top-level organizational containers
- **Features**: Optional groupings for related tasks
- **Tasks**: Primary work units with status, priority, and complexity
- **Sections**: Structured content blocks for detailed documentation

Tasks can exist independently or be associated with Features and Projects as needed.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

Please ensure all tests pass and follow the existing code style.

## License

[MIT License](LICENSE)
