## Project: MCP Task Orchestrator

A Kotlin-based MCP server providing hierarchical work item management with dependency tracking, note schemas, and role-based workflow automation.

**Key Technologies:**
- Kotlin 2.2.0 with Coroutines
- Exposed ORM 1.0.0-beta-2 for SQLite
- MCP SDK 0.8.4 (with Ktor Streamable HTTP transport)
- Flyway for database migrations
- Gradle with Kotlin DSL / Docker

## Build Commands

```bash
./gradlew build                        # fat JAR → current/build/libs/
./gradlew clean build
./gradlew test
./gradlew test --tests "*ToolTest"

java -jar current/build/libs/mcp-task-orchestrator-*.jar

# Docker (most common)
docker build -t task-orchestrator:dev .
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v "$(pwd)"/.taskorchestrator:/project/.taskorchestrator:ro \
  -e AGENT_CONFIG_DIR=/project \
  task-orchestrator:dev
```

## Architecture

All v3 source lives under `current/`. The root-level `clockwork/` module is archived v2.

**Package root:** `io.github.jpicklyk.mcptask.current`
**Source root:** `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/`

```
domain/
  model/       — WorkItem, Note, Dependency, Role, Priority, RoleTransition
  repository/  — WorkItemRepository, NoteRepository, DependencyRepository, RoleTransitionRepository

application/
  tools/items/      — ManageItemsTool, QueryItemsTool
  tools/notes/      — ManageNotesTool, QueryNotesTool
  tools/dependency/ — ManageDependenciesTool, QueryDependenciesTool
  tools/workflow/   — AdvanceItemTool, GetNextStatusTool, GetNextItemTool, GetBlockedItemsTool, GetContextTool
  tools/compound/   — CreateWorkTreeTool, CompleteTreeTool
  service/          — RoleTransitionHandler, NoteSchemaService, CascadeDetector, WorkTreeExecutor

infrastructure/
  database/schema/      — WorkItemsTable, NotesTable, DependenciesTable, RoleTransitionsTable
  database/schema/management/ — DirectDatabaseSchemaManager, FlywayDatabaseSchemaManager, SchemaManagerFactory
  repository/           — SQLite implementations, RepositoryProvider
  config/               — YamlNoteSchemaService

interfaces/mcp/
  CurrentMcpServer.kt, McpToolAdapter.kt
```

**Entry point:** `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/CurrentMain.kt`

## Tight Coupling Areas

### ToolExecutionContext
Constructed in `CurrentMcpServer.kt` as `ToolExecutionContext(repositoryProvider, noteSchemaService)`. Adding a new service dependency requires updating **both** the context class and the server construction site.

### DirectDatabaseSchemaManager
Maintains a manually-ordered table list in foreign-key dependency order. New tables must be inserted at the correct position — the compiler cannot detect wrong ordering.

## Configuration Directory (AGENT_CONFIG_DIR)

**CRITICAL:** All services reading from `.taskorchestrator/` MUST support the `AGENT_CONFIG_DIR` environment variable.

```kotlin
private fun getConfigPath(): Path {
    val projectRoot = Paths.get(
        System.getenv("AGENT_CONFIG_DIR") ?: System.getProperty("user.dir")
    )
    return projectRoot.resolve(".taskorchestrator/config.yaml")
}
```

- In Docker: `-e AGENT_CONFIG_DIR=/project` (where config is mounted)
- In local dev: not needed (uses working directory)
- Currently used by: `YamlNoteSchemaService`

## Adding New Components

### New MCP Tool
1. Extend `BaseToolDefinition` in `current/src/main/kotlin/.../application/tools/`
2. Register in `CurrentMcpServer.kt`
3. Update `current/docs/api-reference.md`
4. Add tests in `current/src/test/kotlin/application/tools/`

### New Database Migration
Create `current/src/main/resources/db/migration/V{N}__{Description}.sql`. SQLite has no `ALTER COLUMN` — schema changes require table recreation. New tables in `DirectDatabaseSchemaManager` must be inserted in foreign-key order.

### New Gradle Dependency
Add to `gradle/libs.versions.toml` (`[versions]` + `[libraries]`), then reference as `libs.{name}` in `build.gradle.kts`. Check Maven Central for the latest version.

## Database Management

**Key environment variables:**
- `DATABASE_PATH` — SQLite file path (default: `data/tasks.db`)
- `USE_FLYWAY` — enable Flyway migrations (default: `true` in Docker)
- `AGENT_CONFIG_DIR` — directory containing `.taskorchestrator/` (default: working dir)
- `MCP_TRANSPORT` — `stdio` (default) or `http`
- `MCP_HTTP_PORT` — HTTP port (default: `3001`)
- `LOG_LEVEL` — DEBUG / INFO / WARN / ERROR (default: `INFO`)
- `FLYWAY_REPAIR` — run repair and exit (default: `false`)

**Migration files:** `current/src/main/resources/db/migration/`

## Testing

- Tests mirror source under `current/src/test/kotlin/`
- JUnit 5 + MockK; H2 in-memory database for repository tests
- **Never pipe `./gradlew` output to `tail`** — run directly and read full output

## Common File Locations

| What | Path |
|------|------|
| Entry point | `current/src/main/kotlin/.../current/CurrentMain.kt` |
| MCP Server | `current/.../interfaces/mcp/CurrentMcpServer.kt` |
| Tool definitions | `current/.../application/tools/` |
| Domain models | `current/.../domain/model/` |
| Repositories | `current/.../infrastructure/repository/` |
| Migrations | `current/src/main/resources/db/migration/` |
| Workflow config | `current/src/main/resources/configuration/default-config.yaml` |
| Note schema service | `current/.../infrastructure/config/YamlNoteSchemaService.kt` |
| Plugin | `claude-plugins/task-orchestrator/` |
| Tests | `current/src/test/kotlin/` |

## Claude Code Plugin Discovery

Two skill systems — do not confuse them:

**Project-level skills** (`.claude/skills/`) — auto-discovered, no config needed:
- `/prepare-release` — version bump, changelog, release PR
- `/feature-implementation` — guided feature lifecycle

**Plugin skills** (`claude-plugins/task-orchestrator/skills/`) — require activation via `.claude/settings.json`:
```json
{ "enabledPlugins": { "task-orchestrator@task-orchestrator-marketplace": true } }
```
- Marketplace name: `task-orchestrator-marketplace` (from `.claude-plugin/marketplace.json` → `name`)
- If plugin stops loading: `/plugin marketplace add .claude-plugin` then `/plugin enable task-orchestrator@task-orchestrator-marketplace`
- After editing plugin files: remove and re-add the marketplace (content is cached)

## Documentation

- **v3 (active):** `current/docs/` — quick-start, api-reference, workflow-guide
- **v2 (archived):** `clockwork/docs/` — reference only

## Git Workflow

- Main branch: `main` — follow conventional commits, reference issue numbers
- All tests must pass before committing
- Database migrations require special attention
