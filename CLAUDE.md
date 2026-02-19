# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: MCP Task Orchestrator (`cee5e258-f759-489d-8a7e-d7b2a5f56880`)

MCP Task Orchestrator is a Kotlin-based Model Context Protocol (MCP) server that provides comprehensive task management capabilities for AI assistants. It implements a hierarchical task management system (Projects -> Features -> Tasks) with dependency tracking, templates, and workflow automation.

**Key Technologies:**
- Kotlin 2.2.0 with Coroutines
- Exposed ORM 1.0.0-beta-2 for SQLite database
- MCP SDK 0.8.4 for protocol implementation (with Ktor Streamable HTTP transport)
- Flyway for database migrations
- Gradle with Kotlin DSL
- Docker for deployment

## Build Commands

### Local Development
```bash
# Build the project (creates fat JAR)
./gradlew build

# Clean build
./gradlew clean build

# Run locally (after build)
java -jar build/libs/mcp-task-orchestrator-*.jar

# Run with environment variables
DATABASE_PATH=data/tasks.db USE_FLYWAY=true LOG_LEVEL=DEBUG java -jar build/libs/mcp-task-orchestrator-*.jar
```

### Docker Development
```bash
# Build Docker image (from project root)
docker build -t task-orchestrator:dev .

# Or use the build scripts
./scripts/docker-build.sh                          # Linux/macOS/Git Bash
scripts\docker-build.bat                           # Windows CMD

# Run Docker container (basic - database only)
docker run --rm -i -v mcp-task-data:/app/data task-orchestrator:dev

# Run with config mount (enables custom note schemas)
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v "$(pwd)"/.taskorchestrator:/project/.taskorchestrator:ro \
  -e AGENT_CONFIG_DIR=/project \
  task-orchestrator:dev

# Debug with logs
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v "$(pwd)"/.taskorchestrator:/project/.taskorchestrator:ro \
  -e AGENT_CONFIG_DIR=/project \
  -e LOG_LEVEL=DEBUG \
  task-orchestrator:dev

# Run in HTTP transport mode (exposes port 3001, endpoint: http://localhost:3001/mcp)
docker run --rm \
  -v mcp-task-data-current:/app/data \
  -v "$(pwd)"/.taskorchestrator:/project/.taskorchestrator:ro \
  -e AGENT_CONFIG_DIR=/project \
  -e MCP_TRANSPORT=http \
  -p 3001:3001 \
  task-orchestrator:current
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
  - `base/` - BaseToolDefinition, SimpleLockAwareToolDefinition
  - `utils/` - Shared tool utilities
- Container tools (`ManageContainerTool`, `QueryContainerTool`) live in the `tools/` root directory
- `service/` - Services like TemplateInitializer, StatusValidator
- `service/progression/` - Status progression service
- `service/templates/` - Built-in template creators

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

## Tight Coupling Areas

When modifying these areas, changes in one location require synchronized updates in others.

### Adding a New Status Enum Value

Four locations must stay in sync:

1. **Domain enum** — Add value to `TaskStatus`, `FeatureStatus`, or `ProjectStatus` in `domain/model/`
2. **Flyway migration** — New `V{N}__*.sql` recreating the table with updated `CHECK (status IN (...))` constraint (SQLite has no `ALTER TYPE`)
3. **Workflow config** — Add status to relevant flows and `status_roles` in `src/main/resources/configuration/default-config.yaml`
4. **Role mapping** — Ensure the new status maps to a role (queue/work/review/terminal) in the config's `status_roles` section

There is **no compile-time validation** between SQL CHECK constraints and Kotlin enum values. A mismatch causes runtime failures on insert.

**Status normalization**: YAML uses `lowercase-with-hyphens` (e.g., `in-progress`). Domain enums use `UPPERCASE_UNDERSCORES` (e.g., `IN_PROGRESS`). The conversion is `status.uppercase().replace('-', '_')` in tools. Mismatches fail silently at transition time.

### Service Dependency Graph

```
RequestTransitionTool
├── StatusProgressionService  (resolves trigger → target status, role lookups)
├── StatusValidator           (validates transitions, checks prerequisites)
├── CascadeService            (detects & applies cascades, finds unblocked tasks)
│   ├── StatusProgressionService  (cascade target resolution)
│   └── StatusValidator           (cascade validation)
└── RoleTransitionRepository  (records role change audit trail)
```

`StatusValidator` and `StatusProgressionService` each maintain **independent config caches** (60s TTL). Both read from `.taskorchestrator/config.yaml` but do not share state. `CascadeService` depends on both.

### DirectDatabaseSchemaManager Table Ordering

`DirectDatabaseSchemaManager` maintains a **manually-ordered list** of table objects. Tables must appear in foreign-key dependency order (e.g., `ProjectsTable` before `FeaturesTable` before `TaskTable`). Adding a new table requires inserting it at the correct position — the Kotlin compiler cannot detect incorrect ordering.

### Template Initializer Registration

Adding a built-in template requires three changes in `TemplateInitializerImpl.kt`:
1. Add template name to the `templateNames` list
2. Add a `when` branch mapping the name to a creator method
3. Add the private creator method calling the template creator object

Additionally, `ApplyTemplateTool` has a hardcoded check for the **"Verification"** section title — if a template includes a section named "Verification", the tool auto-sets `requiresVerification=true` on the target entity.

### ToolExecutionContext

All tools receive a shared `ToolExecutionContext` constructed in `McpServer.kt`:
```kotlin
ToolExecutionContext(repositoryProvider, statusProgressionService, cascadeService)
```
- `repositoryProvider` — access to all repositories (task, feature, project, section, template, dependency, roleTransition)
- `statusProgressionService` — optional, for role-aware status operations
- `cascadeService` — optional, for cascade detection and application

Adding a new service dependency requires updating both the context class and the `McpServer` construction site.

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

## MCP Tools

The server exposes MCP tools organized into categories:

### Container Management (unified CRUD for Projects/Features/Tasks)
- **`manage_container`** - Write operations: create, update, delete (all use array parameters)
- **`query_container`** - Read operations: get, search, export, overview. Supports `role` parameter for semantic phase filtering (queue, work, review, blocked, terminal).

### Section Management
- **`manage_sections`** - Write operations: add, update, updateText, updateMetadata, delete, reorder, bulkCreate, bulkUpdate, bulkDelete
- **`query_sections`** - Read operations with filtering

### Template Management
- **`query_templates`** - Browse and search templates
- **`manage_template`** - Create, update, delete templates
- **`apply_template`** - Apply template to entity

### Dependency Management
- **`query_dependencies`** - Query task dependencies. Use `neighborsOnly=false` for full BFS graph traversal returning a topologically-ordered chain and max depth.
- **`manage_dependencies`** - Create and delete dependencies with batch support. Accepts a `dependencies` array for explicit edges or `pattern` shortcuts (`linear`, `fan-out`, `fan-in`) for common topologies. Use `fromItemId`/`toItemId` for delete-by-relationship.

### Workflow Optimization
- **`get_next_task`** - Intelligent task recommendation with dependency checking and priority sorting
- **`get_blocked_tasks`** - Dependency blocking analysis

### Status Progression
- **`get_next_status`** - Read-only status progression recommendations based on workflow configuration. Returns role annotations (queue, work, review, blocked, terminal) for semantic context.
- **`request_transition`** - Trigger-based status transitions with validation. Use named triggers (start, complete, cancel, block, hold, resume, back) instead of raw status values. Supports batch transitions via `transitions` array parameter. Responses include flow context (activeFlow, flowSequence, flowPosition). Cascade events detected after transitions are automatically applied by default (task completion -> feature advancement -> project advancement). Configure via `auto_cascade` section in `.taskorchestrator/config.yaml`. Responses include `cascadeEvents` with `applied: true/false` and nested `childCascades`.
- **`query_role_transitions`** - Query role transition history for a task, feature, or project. Returns an audit trail of role changes (e.g., queue to work, work to review) with timestamps, triggers, and status details.

### Status Management Workflow

For status changes, use `request_transition` with named triggers:
```
request_transition(containerId="uuid", containerType="task", trigger="start")

# Batch transitions (multiple entities in one call)
request_transition(transitions=[{containerId: "uuid1", containerType: "task", trigger: "complete"}, ...])
```

For read-only recommendations before changing status (optional, recommended for previewing):
```
get_next_status(containerId="uuid", containerType="task")
```

### Scoped Overview Pattern

Use `query_container(operation="overview")` for token-efficient hierarchical views:
- Feature overview: metadata + tasks list + counts (NO section content)
- Project overview: metadata + standalone tasks list + features list + task counts
- Global overview: array of all entities with minimal fields

### Template Discovery (always do this before creating entities)
```
query_templates(targetEntityType="TASK", isEnabled=true)
```

## Adding New Components

### Adding a New MCP Tool

1. Create tool class extending `BaseToolDefinition` in `application/tools/`
2. Implement `validateParams()` and `execute()` methods
3. Register in `McpServer.createTools()` — pass required services from context
4. Add tool documentation in `current/docs/api-reference.md` (v3) or `clockwork/docs/tools/{tool-name}.md` (v2)
5. Register documentation resource in `ToolDocumentationResources.kt` (clockwork only — v3 does not have runtime doc loading)
6. Update the relevant `api-reference.md` with parameter tables and examples
7. Update setup instructions in `TaskOrchestratorResources.kt` if the tool affects agent workflows
8. Update plugin skills that reference tool workflows (see scoped CLAUDE.md cross-reference table)
9. Add tests in `src/test/kotlin/application/tools/`

### Adding a Database Migration

**Flyway (Production):** Create `src/main/resources/db/migration/V{N}__{Description}.sql` with sequential numbering. Server auto-applies on restart. For status enum changes, SQLite requires table recreation with updated `CHECK` constraints — see "Tight Coupling Areas" above.

**Direct (Development):** Update schema in `infrastructure/database/schema/` and `DirectDatabaseSchemaManager.kt`. New tables must be inserted in foreign-key dependency order in the manager's table list.

See [database-migrations.md](clockwork/docs/developer-guides/database-migrations.md) for patterns and examples (v2/Clockwork). v3 uses a different migration strategy.

### Adding a New Template

1. Create template creator in `application/service/templates/` following existing patterns
2. Register in `TemplateInitializerImpl.kt` (add name to list, `when` branch, and creator method)
3. Tag all sections with `role:` prefixes (`role:queue`, `role:work`, `role:review`, `role:terminal`) for workflow phase filtering
4. If template includes a section titled "Verification", `ApplyTemplateTool` will auto-set `requiresVerification=true`

Template UUIDs are random — agents must discover them at runtime via `query_templates`. Never hardcode template UUIDs.

### Adding a Repository Method

1. Add method to repository interface in `domain/repository/`
2. Implement in SQLite repository in `infrastructure/database/repository/`
3. Use in tools via `context.{repository}()`

## Database Management

**Environment Variables:**
- `DATABASE_PATH` - SQLite database file path (default: `data/tasks.db`)
- `USE_FLYWAY` - Enable Flyway migrations (default: `true` in Docker)
- `LOG_LEVEL` - Logging verbosity: DEBUG, INFO, WARN, ERROR (default: `INFO`)
- `AGENT_CONFIG_DIR` - Directory containing `.taskorchestrator/` config folder (default: current working directory)
- `DATABASE_MAX_CONNECTIONS` - Connection pool size (default: `10`)
- `DATABASE_SHOW_SQL` - Log SQL statements (default: `false`)
- `MCP_SERVER_NAME` - Custom server name for MCP identity (default: `mcp-task-orchestrator`)
- `MCP_TRANSPORT` - Transport protocol: `stdio` (default) or `http` (Streamable HTTP, MCP spec 2025-03-26)
- `MCP_HTTP_HOST` - Bind host for HTTP transport (default: `0.0.0.0`)
- `MCP_HTTP_PORT` - Port for HTTP transport (default: `3001`). Endpoint: `http://<host>:<port>/mcp`
- `FLYWAY_REPAIR` - Run Flyway repair and exit (default: `false`)

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
- **Status progression:** `src/main/kotlin/io/github/jpicklyk/mcptask/application/service/progression/`
- **Cascade service:** `src/main/kotlin/io/github/jpicklyk/mcptask/application/service/cascade/`
- **Workflow config:** `src/main/resources/configuration/default-config.yaml`
- **Docker:** `Dockerfile`, `.dockerignore`, `docker-compose.yml`
- **Build scripts:** `scripts/docker-build.sh`, `scripts/docker-build.bat`
- **CI/CD:** `.github/workflows/docker-publish.yml`
- **Plugin:** `claude-plugins/task-orchestrator/` (skills, hooks, output styles)
- **Scoped CLAUDE.md:** `src/main/kotlin/io/github/jpicklyk/mcptask/CLAUDE.md` (tool API formatting rules)
- **Tests:** `src/test/kotlin/` (mirrors main structure)

## Claude Code Skills and Plugin Discovery

There are two distinct skill systems — they work independently and must not be confused:

### Project-Level Skills (`.claude/skills/`)

Files placed in `.claude/skills/` are **auto-discovered by Claude Code** with no configuration required. Invoke them as `/skill-name`. These are project-local skills checked into the repo.

- `/prepare-release` — version bump, changelog, release PR, and `gh workflow run` trigger command
- `/feature-implementation` — guided feature lifecycle with note-gated phase progression

No `enabledPlugins` entry or marketplace registration needed.

### Plugin Skills (`claude-plugins/task-orchestrator/skills/`)

The `task-orchestrator` plugin provides namespaced skills (`task-orchestrator:work-summary`, etc.) distributed via the local marketplace at `.claude-plugin/marketplace.json`. These **require explicit activation**.

**Pre-activation is configured in `.claude/settings.json`:**

```json
{
  "enabledPlugins": {
    "task-orchestrator@task-orchestrator-marketplace": true
  }
}
```

- Marketplace name: `task-orchestrator-marketplace` (from `.claude-plugin/marketplace.json` → `name`)
- Plugin entry name: `task-orchestrator` (from the `plugins[].name` field in that same file)
- Key format: `"plugin-name@marketplace-name"`

If the plugin stops loading after a restart, verify `.claude/settings.json` has the entry above. As a fallback, run `/plugin marketplace add .claude-plugin` then `/plugin enable task-orchestrator@task-orchestrator-marketplace`.

**Plugin cache note:** Plugin hook script content is cached by Claude Code. After editing any file inside `claude-plugins/task-orchestrator/`, remove and re-add the marketplace to pick up the changes.

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

**Active (v3 Current) User Documentation:** `current/docs/`
- [quick-start.md](current/docs/quick-start.md) - Getting started with v3
- [api-reference.md](current/docs/api-reference.md) - All 13 MCP tools
- [workflow-guide.md](current/docs/workflow-guide.md) - Note schemas, phase gates, lifecycle

**Archived (v2 Clockwork) Documentation:** `clockwork/docs/`
- [quick-start.md](clockwork/docs/quick-start.md) - v2 getting started
- [ai-guidelines.md](clockwork/docs/ai-guidelines.md) - v2 AI usage guide
- [api-reference.md](clockwork/docs/api-reference.md) - v2 tool reference (14 tools)
- [status-progression.md](clockwork/docs/status-progression.md) - v2 status workflow
- [templates.md](clockwork/docs/templates.md) - v2 template system
- [developer-guides/architecture.md](clockwork/docs/developer-guides/architecture.md) - v2 architecture
- [developer-guides/database-migrations.md](clockwork/docs/developer-guides/database-migrations.md) - v2 migration guide

## Git Workflow

When making commits or PRs:
- Main branch: `main`
- Follow conventional commits style
- Reference issue numbers where applicable
- Ensure all tests pass before committing
- Database migrations require special attention

See [CONTRIBUTING.md](CONTRIBUTING.md) for contribution guidelines.

## Current (v3) MCP Tools — Graph-Aware Parameters

The following parameters are available on v3 tools to eliminate sequential parent-walk call chains:

### `query_items`
- `includeAncestors` (boolean, default false) — applies to `get` and `search` operations. Each item includes an `ancestors` array: `[{id, title, depth}, ...]` ordered root → direct parent. Root items (depth=0) get `[]`. Eliminates sequential parent-walk calls for breadcrumb/location context.
- `includeChildren` (boolean, default false) — applies to `overview` global mode only. Each root item includes a `children` array of its direct child items: `[{id, title, role, depth}]`.

### `get_context`
- `includeAncestors` (boolean, default false) — when true, each item in `activeItems`, `blockedItems`, and `stalledItems` includes an `ancestors` array. Recommended for work-summary dashboards.

### `get_blocked_items`
- `includeAncestors` (boolean, default false) — when true, each blocked item includes an `ancestors` array showing its location in the hierarchy.

### `get_next_item`
- `includeAncestors` (boolean, default false) — when true, each recommended item includes an `ancestors` array.

### Pattern: 2-call work-summary
```
get_context(includeAncestors=true)    → active items with full ancestor chains
query_items(operation="overview")      → root containers with child counts
```
Total: 2 calls. Zero follow-up traversal.

## `manage_items` (create operation) Response Enrichment

When creating items with `manage_items(create)`, the response includes two enrichment fields:

- **`tags`** — The item's tag string (string or null). Always included in the create response.
- **`expectedNotes`** — Array of note schema entries when the item's `tags` match a configured schema. Format: `[{key, role, required, description, exists}]` where `exists` is always `false` at creation time. **Omitted entirely when no schema matches the tags.** Agents should check this immediately after creation to know which queue-phase notes to fill before calling `advance_item(trigger="start")`.

Example response with schema match:
```json
{
  "id": "abc-123",
  "title": "Authentication Handler",
  "role": "queue",
  "tags": "task-implementation,security-critical",
  "expectedNotes": [
    { "key": "requirements", "role": "queue", "required": true, "description": "Testable acceptance criteria", "exists": false },
    { "key": "done-criteria", "role": "work", "required": true, "description": "Completion conditions", "exists": false }
  ]
}
```

Example response without schema match (no `expectedNotes` field):
```json
{
  "id": "abc-124",
  "title": "API Documentation",
  "role": "queue",
  "tags": null
}
```
