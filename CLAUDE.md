# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MCP Task Orchestrator is a Kotlin-based Model Context Protocol (MCP) server that provides comprehensive task management capabilities for AI assistants. It implements a hierarchical task management system (Projects -> Features -> Tasks) with dependency tracking, templates, and workflow automation.

**Key Technologies:**
- Kotlin 2.2.0 with Coroutines
- Exposed ORM v1 for SQLite database
- MCP SDK 0.7.2 for protocol implementation
- Flyway for database migrations
- Gradle with Kotlin DSL
- Docker for deployment

## Build Commands

### Local Development
```bash
# Build the project (creates fat JAR)
./gradlew build

# Run tests
./gradlew test

# Run specific test
./gradlew test --tests "ClassName"

# Run all migration tests
./gradlew test --tests "*migration*"

# Clean build
./gradlew clean build

# Run locally (after build)
java -jar build/libs/mcp-task-orchestrator-*.jar

# Run with environment variables
DATABASE_PATH=data/tasks.db USE_FLYWAY=true MCP_DEBUG=true java -jar build/libs/mcp-task-orchestrator-*.jar
```

### Docker Development
```bash
# Build Docker image (from project root)
docker build -t mcp-task-orchestrator:dev .

# Run Docker container (basic - database only)
docker run --rm -i -v mcp-task-data:/app/data mcp-task-orchestrator:dev

# Run with project mount (recommended - enables config reading)
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v D:/Projects/task-orchestrator:/project \
  -e AGENT_CONFIG_DIR=/project \
  mcp-task-orchestrator:dev

# Clean and rebuild Docker
./scripts/docker-clean-and-build.bat

# Debug with logs
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v D:/Projects/task-orchestrator:/project \
  -e AGENT_CONFIG_DIR=/project \
  -e MCP_DEBUG=true \
  mcp-task-orchestrator:dev
```

## Architecture

The codebase follows **Clean Architecture** with four distinct layers:

### 1. Domain Layer (`src/main/kotlin/io/github/jpicklyk/mcptask/domain/`)
- **Pure business logic**, framework-agnostic
- `model/` - Core entities (Task, Feature, Project, Template, Section, Dependency)
- `repository/` - Repository interfaces defining data access contracts
- No dependencies outside Kotlin stdlib and kotlinx.serialization

### 2. Application Layer (`src/main/kotlin/io/github/jpicklyk/mcptask/application/`)
- **Business logic orchestration and use cases**
- `tools/` - MCP tool implementations organized by category:
  - `task/` - Task workflow tools (get_next_task, get_blocked_tasks)
  - `status/` - Status progression tools (get_next_status, request_transition)
  - `template/` - Template management tools
  - `section/` - Section management tools
  - `dependency/` - Dependency management tools
- `service/` - Services like TemplateInitializer, StatusValidator
- `service/progression/` - Status progression service
- `service/templates/` - 9 built-in template creators

### 3. Infrastructure Layer (`src/main/kotlin/io/github/jpicklyk/mcptask/infrastructure/`)
- **External concerns and framework implementations**
- `database/` - DatabaseManager, schema management
- `database/repository/` - SQLite repository implementations
- `database/schema/` - Exposed ORM table definitions
- `database/migration/` - Flyway migration management
- `util/` - ErrorCodes, logging utilities

### 4. Interface Layer (`src/main/kotlin/io/github/jpicklyk/mcptask/interfaces/mcp/`)
- **MCP protocol adaptation**
- `McpServer.kt` - Main server, tool registration, lifecycle
- `McpToolAdapter.kt` - Bridges tools to MCP protocol
- `McpServerAiGuidance.kt` - AI guidance configuration
- `TaskOrchestratorResources.kt` - MCP Resources for AI
- `ToolDocumentationResources.kt` - Tool documentation as MCP resources

**Entry Point:** `src/main/kotlin/Main.kt`

## Key Design Patterns

1. **Repository Pattern** - Domain defines interfaces, infrastructure implements
2. **Adapter Pattern** - McpToolAdapter separates MCP protocol from business logic
3. **Dependency Injection** - ToolExecutionContext provides repositories to tools
4. **Factory Pattern** - SchemaManagerFactory creates appropriate schema manager
5. **Template Method** - BaseToolDefinition provides structure for all tools
6. **Result Pattern** - Type-safe error handling with `Result<T>` sealed class

## Configuration Directory (AGENT_CONFIG_DIR)

**CRITICAL:** All services that read configuration files from `.taskorchestrator/` MUST support the `AGENT_CONFIG_DIR` environment variable for Docker compatibility.

### Standard Pattern for Config Loading

```kotlin
private fun getConfigPath(): Path {
    val projectRoot = Paths.get(
        System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
    )
    return projectRoot.resolve(".taskorchestrator/config.yaml")
}
```

### Environment Variable Configuration

- **`AGENT_CONFIG_DIR`** - Directory containing `.taskorchestrator/` folder (optional)
  - Defaults to `System.getProperty("user.dir")` if not set
  - In Docker: Set to project mount point (e.g., `-e AGENT_CONFIG_DIR=/project`)
  - In local dev: Not needed (uses working directory)

### Which Services Need This

- `StatusProgressionServiceImpl` - Reads `.taskorchestrator/config.yaml`
- Any future services accessing `.taskorchestrator/` configuration

## MCP Tools (12 tools)

The server exposes 12 MCP tools organized into categories:

### Container Management (unified CRUD for Projects/Features/Tasks)
- **`manage_container`** - Write operations: create, update, delete, setStatus, bulkUpdate
- **`query_container`** - Read operations: get, search, export, overview

### Section Management
- **`manage_sections`** - Write operations: add, update, updateText, updateMetadata, delete, reorder, bulkCreate, bulkUpdate, bulkDelete
- **`query_sections`** - Read operations with filtering

### Template Management
- **`query_templates`** - Browse and search templates
- **`manage_template`** - Create, update, delete templates
- **`apply_template`** - Apply template to entity

### Dependency Management
- **`query_dependencies`** - Query task dependencies
- **`manage_dependency`** - Create, update, delete dependencies

### Workflow Optimization
- **`get_next_task`** - Intelligent task recommendation with dependency checking and priority sorting
- **`get_blocked_tasks`** - Dependency blocking analysis

### Status Progression
- **`get_next_status`** - Read-only status progression recommendations based on workflow configuration. Returns role annotations (queue, work, review, blocked, terminal) for semantic context.
- **`request_transition`** - Trigger-based status transitions with validation. Use named triggers (start, complete, cancel, block, hold) instead of raw status values.

### Status Management Workflow

For status changes, use `request_transition` with named triggers:
```
request_transition(containerId="uuid", containerType="task", trigger="start")
```

For read-only recommendations before changing status:
```
get_next_status(containerId="uuid", containerType="task")
```

### Scoped Overview Pattern

Use `query_container(operation="overview")` for token-efficient hierarchical views:
- Feature overview: metadata + tasks list + counts (NO section content)
- Project overview: metadata + features list + task counts
- Global overview: array of all entities with minimal fields

### Template Discovery (always do this before creating entities)
```
query_templates(targetEntityType="TASK", isEnabled=true)
```

## Adding New Components

### Adding a New MCP Tool

1. Create tool class extending `BaseToolDefinition` in `application/tools/`
2. Implement `validateParams()` and `execute()` methods
3. Register in `McpServer.createTools()`
4. Add tests in `src/test/kotlin/application/tools/`

### Adding a Database Migration

**Flyway (Production):** Create `src/main/resources/db/migration/V{N}__{Description}.sql` with sequential numbering. Server auto-applies on restart.

**Direct (Development):** Update schema in `infrastructure/database/schema/` and `DirectDatabaseSchemaManager.kt`

See [database-migrations.md](docs/developer-guides/database-migrations.md) for patterns and examples.

### Adding a New Template

1. Create template creator in `application/service/templates/` following existing patterns
2. Register in `TemplateInitializerImpl.kt` (add to when statement and initialization list)

### Adding a Repository Method

1. Add method to repository interface in `domain/repository/`
2. Implement in SQLite repository in `infrastructure/database/repository/`
3. Use in tools via `context.{repository}()`

## Database Management

**Environment Variables:**
- `DATABASE_PATH` - SQLite database file path (default: `data/tasks.db`)
- `USE_FLYWAY` - Enable Flyway migrations (default: `true` in Docker)
- `MCP_DEBUG` - Enable debug logging
- `AGENT_CONFIG_DIR` - Directory containing `.taskorchestrator/` config folder (default: current working directory)

**Schema Management:**
- **Flyway** (Production) - Versioned SQL migrations with history tracking
- **Direct** (Development) - Exposed ORM schema updates for faster iteration

**Migration Files:** `src/main/resources/db/migration/`

## Dependency Management

Add new dependencies via `gradle/libs.versions.toml`:
1. Add version to `[versions]` section
2. Add library to `[libraries]` with version reference
3. Reference in `build.gradle.kts` as `libs.{name}`

## Testing

**Test Structure:**
- Tests mirror main source structure under `src/test/kotlin/`
- Use JUnit 5 with Kotlin Test
- MockK for mocking
- H2 in-memory database for repository tests

**Running Tests:**
```bash
./gradlew test                              # All tests
./gradlew test --tests "ClassName"          # Specific class
./gradlew test --tests "*migration*"        # Migration tests
./gradlew test --tests "*ToolTest"          # All tool tests
```

## Version Management

Format: `{major}.{minor}.{patch}.{git-commit-count}-{qualifier}`
Edit version in `build.gradle.kts` (majorVersion, minorVersion, patchVersion, qualifier)

## Common File Locations

- **Main entry:** `src/main/kotlin/Main.kt`
- **MCP Server:** `src/main/kotlin/io/github/jpicklyk/mcptask/interfaces/mcp/McpServer.kt`
- **Tool definitions:** `src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/`
- **Domain models:** `src/main/kotlin/io/github/jpicklyk/mcptask/domain/model/`
- **Repositories:** `src/main/kotlin/io/github/jpicklyk/mcptask/infrastructure/database/repository/`
- **Database schema:** `src/main/kotlin/io/github/jpicklyk/mcptask/infrastructure/database/schema/`
- **Migrations:** `src/main/resources/db/migration/`
- **Templates:** `src/main/kotlin/io/github/jpicklyk/mcptask/application/service/templates/`
- **Workflow config:** `src/main/resources/claude/configuration/default-config.yaml`
- **Tests:** `src/test/kotlin/` (mirrors main structure)

## Tool Development Guidelines

**Tool Implementation Checklist:**
1. Extend `BaseToolDefinition` or `SimpleLockAwareToolDefinition`
2. Define clear parameter and output schemas
3. Implement `validateParams()` with proper validation
4. Implement `execute()` with business logic
5. Use `successResponse()` and `errorResponse()` helpers
6. Handle `Result<T>` from repositories properly
7. Add comprehensive tests
8. Register in `McpServer.createTools()`
9. Document in tool description for AI agents

**Tool Categories:**
- TASK_MANAGEMENT - Task CRUD and workflow operations
- FEATURE_MANAGEMENT - Feature CRUD operations
- PROJECT_MANAGEMENT - Project CRUD operations
- TEMPLATE_MANAGEMENT - Template operations
- SECTION_MANAGEMENT - Section content operations
- DEPENDENCY_MANAGEMENT - Task dependency operations

## Documentation

**Developer Guides:** `docs/developer-guides/`
- [architecture.md](docs/developer-guides/architecture.md) - Comprehensive architecture guide
- [database-migrations.md](docs/developer-guides/database-migrations.md) - Migration management

**User Documentation:** `docs/`
- [quick-start.md](docs/quick-start.md) - Getting started
- [ai-guidelines.md](docs/ai-guidelines.md) - How AI uses Task Orchestrator
- [api-reference.md](docs/api-reference.md) - Complete MCP tools documentation
- [status-progression.md](docs/status-progression.md) - Status workflow guide with examples
- [templates.md](docs/templates.md) - Template system guide

## Git Workflow

When making commits or PRs:
- Main branch: `main`
- Follow conventional commits style
- Reference issue numbers where applicable
- Ensure all tests pass before committing
- Database migrations require special attention

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.
