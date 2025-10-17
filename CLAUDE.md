# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MCP Task Orchestrator is a Kotlin-based Model Context Protocol (MCP) server that provides comprehensive task management capabilities for AI assistants. It implements a hierarchical task management system (Projects → Features → Tasks) with dependency tracking, templates, and workflow automation.

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

# Run Docker container
docker run --rm -i -v mcp-task-data:/app/data mcp-task-orchestrator:dev

# Clean and rebuild Docker
./scripts/docker-clean-and-build.bat

# Debug with logs
docker run --rm -i -v mcp-task-data:/app/data -e MCP_DEBUG=true mcp-task-orchestrator:dev
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
  - `task/` - Task management
  - `feature/` - Feature management
  - `project/` - Project management
  - `template/` - Template management
  - `section/` - Section management
  - `dependency/` - Dependency management
- `service/` - Services like TemplateInitializer
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
- `WorkflowPromptsGuidance.kt` - Workflow automation prompts

**Entry Point:** `src/main/kotlin/Main.kt`

## Key Design Patterns

1. **Repository Pattern** - Domain defines interfaces, infrastructure implements
2. **Adapter Pattern** - McpToolAdapter separates MCP protocol from business logic
3. **Dependency Injection** - ToolExecutionContext provides repositories to tools
4. **Factory Pattern** - SchemaManagerFactory creates appropriate schema manager
5. **Template Method** - BaseToolDefinition provides structure for all tools
6. **Result Pattern** - Type-safe error handling with `Result<T>` sealed class

## Adding New Components

### Adding a New MCP Tool

1. Create tool class in appropriate package under `application/tools/`:
```kotlin
package io.github.jpicklyk.mcptask.application.tools.task

class MyNewTool : BaseToolDefinition() {
    override val category = ToolCategory.TASK_MANAGEMENT
    override val name = "my_new_tool"
    override val title = "My New Tool"
    override val description = "..."
    override val parameterSchema = Tool.Input(...)
    override val outputSchema = Tool.Output(...)

    override fun validateParams(params: JsonElement) {
        requireString(params, "param1")
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val repository = context.taskRepository()
        // Business logic here
        return successResponse(data = buildJsonObject { }, message = "Success")
    }
}
```

2. Register in `McpServer.createTools()`:
```kotlin
private fun createTools(): List<ToolDefinition> {
    return listOf(
        // ... existing tools
        MyNewTool(),
    )
}
```

3. Add tests in `src/test/kotlin/application/tools/`

### Adding a Database Migration

**For Production (Flyway):**
1. Create file: `src/main/resources/db/migration/V{N}__{Description}.sql`
2. Use sequential numbering (next available version)
3. Follow SQLite patterns:
   - UUIDs as BLOB: `id BLOB PRIMARY KEY DEFAULT (randomblob(16))`
   - Timestamps: `created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP`
   - Foreign keys: `FOREIGN KEY (parent_id) REFERENCES parent_table(id)`
   - Add indexes for queried columns
4. Include rollback instructions in comments
5. Restart server - Flyway applies automatically

**For Development (Direct):**
1. Update table in `infrastructure/database/schema/`
2. Update `DirectDatabaseSchemaManager.kt`

See [database-migrations.md](docs/developer-guides/database-migrations.md) for detailed guide.

### Adding a New Template

1. Create template creator in `application/service/templates/`:
```kotlin
object MyTemplateCreator {
    fun create(): Pair<Template, List<TemplateSection>> {
        val template = Template.create { ... }
        val sections = listOf( ... )
        return template to sections
    }
}
```

2. Register in `TemplateInitializerImpl.kt`:
   - Add to `initializeTemplate()` when statement
   - Add private creation method
   - Add to initialization list

### Adding a Repository Method

1. Add method to repository interface in `domain/repository/`
2. Implement in SQLite repository in `infrastructure/database/repository/`
3. Use in tools via `context.{repository}()`

## Database Management

**Environment Variables:**
- `DATABASE_PATH` - SQLite database file path (default: `data/tasks.db`)
- `USE_FLYWAY` - Enable Flyway migrations (default: `true` in Docker)
- `MCP_DEBUG` - Enable debug logging

**Schema Management:**
- **Flyway** (Production) - Versioned SQL migrations with history tracking
- **Direct** (Development) - Exposed ORM schema updates for faster iteration

**Migration Files:** `src/main/resources/db/migration/`
- `V1__initial_schema.sql` - Initial database schema
- `V2__template_section_ordinal.sql` - Template section ordering

## Dependency Management

**IMPORTANT:** When adding new dependencies:
1. Check Maven Central for the latest stable version
2. Add version to `gradle/libs.versions.toml` under `[versions]`
3. Add library to `[libraries]` section with version reference
4. Add to `build.gradle.kts` using `libs.{name}` notation

Example:
```toml
# gradle/libs.versions.toml
[versions]
newlib = "1.2.3"

[libraries]
newlib = { module = "com.example:newlib", version.ref = "newlib" }
```

```kotlin
// build.gradle.kts
dependencies {
    implementation(libs.newlib)
}
```

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

Version follows semantic versioning with git-based build numbers:
- Format: `{major}.{minor}.{patch}.{git-commit-count}-{qualifier}`
- Configured in `build.gradle.kts`
- Generated `VersionInfo.kt` available at runtime
- Example: `1.1.0.123-alpha-01`

To change version, edit `build.gradle.kts`:
```kotlin
val majorVersion = "1"
val minorVersion = "1"
val patchVersion = "0"
val qualifier = "alpha-01"  // Empty for stable releases
```

## Common File Locations

- **Main entry:** `src/main/kotlin/Main.kt`
- **MCP Server:** `src/main/kotlin/io/github/jpicklyk/mcptask/interfaces/mcp/McpServer.kt`
- **Tool definitions:** `src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/`
- **Domain models:** `src/main/kotlin/io/github/jpicklyk/mcptask/domain/model/`
- **Repositories:** `src/main/kotlin/io/github/jpicklyk/mcptask/infrastructure/database/repository/`
- **Database schema:** `src/main/kotlin/io/github/jpicklyk/mcptask/infrastructure/database/schema/`
- **Migrations:** `src/main/resources/db/migration/`
- **Templates:** `src/main/kotlin/io/github/jpicklyk/mcptask/application/service/templates/`
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
- TASK_MANAGEMENT - Task CRUD operations
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
- [templates.md](docs/templates.md) - Template system guide
- [workflow-prompts.md](docs/workflow-prompts.md) - Workflow automation

## Git Workflow

When making commits or PRs:
- Main branch: `main`
- Follow conventional commits style
- Reference issue numbers where applicable
- Ensure all tests pass before committing
- Database migrations require special attention

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.

## AI Workflow Systems

Task Orchestrator provides TWO complementary workflow systems that work independently or together:

### 1. Templates + Workflow Prompts (Universal)

**What**: Database-driven templates + MCP workflow prompts for task/feature creation
**Works with**: Any MCP-compatible AI (Claude Desktop, Claude Code, Cursor, Windsurf, etc.)
**Always available**: No setup required

**Purpose**:
- **Templates**: Structure the WORK (what to document, requirements, testing strategy)
- **Workflow Prompts**: Guide the PROCESS (step-by-step creation, implementation, validation)

**Key workflows**:
- `initialize_task_orchestrator` - AI initialization (writes guidelines below to this file)
- `create_feature_workflow` - Feature creation with templates
- `task_breakdown_workflow` - Task decomposition
- `project_setup_workflow` - Project initialization
- `implementation_workflow` - Git-aware implementation with validation

**Template Discovery** (CRITICAL - never skip):
```
1. list_templates(targetEntityType="TASK" or "FEATURE", isEnabled=true)
2. Review available templates
3. Apply via templateIds parameter during creation
```

### 2. Sub-Agent Orchestration (Claude Code Only)

**What**: 3-level agent coordination for complex multi-task features
**Works with**: Claude Code only (requires `.claude/agents/` directory)
**Setup required**: Run `setup_claude_agents` tool first

**When to use**:
- Complex features with 4+ related tasks
- Work requiring specialist coordination (Backend Engineer → Database Engineer → Test Engineer)
- 97% token reduction needed (orchestrator maintains only summaries, not full context)

**Architecture**:
- **Level 0**: Orchestrator (you) - launches Feature Manager for multi-task work
- **Level 1**: Feature Manager - coordinates tasks within a feature, recommends next task
- **Level 2**: Task Manager - routes tasks to specialists, passes dependency context
- **Level 3**: Specialists - Backend, Frontend, Database, Test, Technical Writer, Planning

**How it works**:
1. Orchestrator uses `recommend_agent(taskId)` to find appropriate specialist
2. Task Manager reads task + completed dependency summaries (300-500 tokens each)
3. Task Manager launches specialist with focused brief (not full context)
4. Specialist completes work, creates Summary section (300-500 tokens)
5. Task Manager reports completion to orchestrator (2-3 sentences only)

**Setup**: `setup_claude_agents` creates `.claude/agents/*.md` files for Claude Code discovery

**See**: [Agent Orchestration Documentation](docs/agent-orchestration.md) for complete guide.

### How They Work Together

**Templates structure the WORK** (what needs to be documented):
- Requirements Specification template → creates "Requirements" section
- Technical Approach template → creates "Technical Approach" section
- Testing Strategy template → creates "Testing Strategy" section

**Sub-agents execute the WORK** (who does the implementation):
- Planning Specialist reads "Requirements" sections → creates breakdown
- Backend Engineer reads "Technical Approach" → implements code
- Test Engineer reads "Testing Strategy" → writes tests

**Templates work with BOTH systems**:
- ✅ Direct execution: You read templates, implement yourself
- ✅ Sub-agent execution: Specialists read templates, implement for you

### Decision Guide: When to Use What

**Use Templates + Workflow Prompts ONLY**:
- Simple tasks (1-3 tasks total)
- Single-agent work (you're doing everything)
- Not using Claude Code (using Cursor, Windsurf, Claude Desktop, etc.)
- Learning the system (workflows teach best practices)

**Add Sub-Agent Orchestration**:
- Complex features (4+ related tasks with dependencies)
- Using Claude Code (only tool with sub-agent support)
- Token efficiency critical (large context, many tasks)
- Specialist coordination valuable (backend → database → frontend → test flow)

**Example workflow comparison**:

*Simple (Templates only)*:
```
You: create_task(templateIds=["technical-approach", "testing-strategy"])
You: Read technical-approach section
You: Implement the code yourself
You: Read testing-strategy section
You: Write tests yourself
```

*Complex (Templates + Sub-agents)*:
```
You: create_feature with 4 tasks, apply templates to each
You: Launch Feature Manager (START mode)
Feature Manager: Recommends next task → T1 (Database schema)
You: Launch Task Manager for T1
Task Manager: Routes to Database Engineer specialist
Database Engineer: Reads "Technical Approach" template section, implements schema, creates Summary
Task Manager: Reports completion (2 sentences) to you
You: Launch Feature Manager again
Feature Manager: Recommends next task → T2 (API implementation, reads T1 Summary)
[continues with automatic dependency context passing]
```

---

## Claude Code Sub-Agent Decision Gates

**These decision gates help you route work to specialized agents proactively.**

### Before Creating a Feature

❓ **Did user say** "create/start/build a feature for..." **OR** provide rich context (3+ paragraphs)?
→ **YES?** Launch **Feature Architect** agent
→ **NO?** Proceed with direct `create_feature` tool

### Before Starting Multi-Task Feature Work

❓ **Does feature have** 4+ tasks with dependencies?
❓ **Need** specialist coordination across domains?
→ **YES?** Launch **Feature Manager** agent (START mode)
→ **NO?** Work through tasks sequentially yourself

### Before Working on a Task

❓ **Is task** part of a larger feature (has `featureId`)?
❓ **Does task** have specialist tags (backend, frontend, database, testing, docs)?
→ **YES?** Check `recommend_agent(taskId)` for specialist routing
→ **NO?** Proceed with direct implementation

### When User Reports a Bug

❓ **User says:** "broken", "error", "crash", "doesn't work", "failing"?
→ **YES?** Launch **Bug Triage Specialist** agent
→ **NO?** If it's a feature request, use Feature Architect

### After Feature Architect Creates Feature

❓ **Does the feature** need task breakdown?
→ **YES?** Launch **Planning Specialist** agent
→ **NO?** If it's a simple feature, create tasks yourself

**Remember:** These gates are for Claude Code only. If using other LLMs (Cursor, Windsurf), use templates and workflow prompts directly.

---

## Task Orchestrator - AI Initialization

Last initialized: 2025-10-16
Version: 1.1.0-beta
Features: none

### Critical Patterns

**Session Start Routine**:
1. Run get_overview() first to understand current state
2. Check for in-progress tasks before starting new work
3. Review priorities and dependencies

**Intent Recognition** (applies to templates OR sub-agents):
- "Create feature for X" → Feature creation with template discovery
- "Implement X" → Task creation with implementation templates (then optionally route to specialist)
- "Fix bug X" → Bug triage with Bug Investigation template (then optionally route to specialist)
- "Break down X" → Task decomposition pattern
- "Set up project" → Project setup workflow

**Template Discovery** (ALWAYS required, regardless of using sub-agents):
- Always: list_templates(targetEntityType, isEnabled=true)
- Never: Assume templates exist
- Apply: Use templateIds parameter during creation
- Templates work with both direct execution AND sub-agent execution

**Git Integration**:
- Auto-detect .git directory presence
- Suggest git workflow templates when detected
- Ask about PR workflows (don't assume)

**Quality Standards**:
- Write descriptive titles and summaries
- Use appropriate complexity ratings (1-10)
- Apply consistent tagging conventions
- Include acceptance criteria in summaries