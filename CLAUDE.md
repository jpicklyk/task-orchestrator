## Project: MCP Task Orchestrator

A Kotlin-based MCP server providing hierarchical work item management with dependency tracking, note schemas, and role-based workflow automation.

**Key Technologies:**
- Kotlin 2.3.21 with Coroutines
- Exposed ORM 1.2.0 for SQLite
- MCP SDK 0.12.0 (with Ktor Streamable HTTP transport)
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

Source lives under `current/`.

**Package root:** `io.github.jpicklyk.mcptask.current`
**Source root:** `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/`

```
domain/
  model/       — WorkItem, Note, Dependency, Role, Priority, RoleTransition, LifecycleMode, WorkItemSchema
  repository/  — WorkItemRepository, NoteRepository, DependencyRepository, RoleTransitionRepository

application/
  tools/items/      — ManageItemsTool, QueryItemsTool (FTS5 search + list-filter)
  tools/notes/      — ManageNotesTool, QueryNotesTool (FTS5 search + get/list)
  tools/dependency/ — ManageDependenciesTool, QueryDependenciesTool (backlinks + get)
  tools/workflow/   — AdvanceItemTool, ClaimItemTool, GetNextStatusTool, GetNextItemTool, GetBlockedItemsTool, GetContextTool
  tools/compound/   — CreateWorkTreeTool, CompleteTreeTool
  service/          — RoleTransitionHandler, NoteSchemaService, CascadeDetector, WorkTreeExecutor
  service/search/   — FtsQuerySanitizer, RrfFusion

infrastructure/
  database/schema/      — WorkItemsTable, NotesTable, DependenciesTable, RoleTransitionsTable
  database/schema/management/ — DirectDatabaseSchemaManager, FlywayDatabaseSchemaManager, SchemaManagerFactory
  repository/           — SQLite implementations, RepositoryProvider
  config/               — YamlWorkItemSchemaService (typealias YamlNoteSchemaService), ApiAuthConfigLoader

interfaces/mcp/
  CurrentMcpServer.kt, McpToolAdapter.kt

interfaces/api/v1/
  auth/     — ApiAuthConfig, ApiPrincipal, ApiScope, ApiCapability, AuthenticationPlugin, AuthorizationPlugin, BearerTokenStore, JwksApiVerifier
  cors/     — CorsConfig (env-driven CORS from CORS_ALLOWED_ORIGINS etc.)
  dto/      — Dtos.kt (ItemDto, NoteDto, ActorClaimDto, VerificationDto, RoleTransitionDto, DependenciesDto, DependencyEdgeDto, BacklinkDto, PageDto, ErrorDto, SearchHitDto, config DTOs, request DTOs, AdvanceResponseDto)
  etag/     — etagFor() — "v1-<modifiedAtMillis>" for items/notes
  events/   — ApiEvent, ApiEventType constants, ApiEventBus (ring-buffer pub/sub with per-root filtering)
  mapping/  — Domain → DTO mappers (.toDto() extensions)
  pagination/ — pageParams(), buildPageDto()
  redaction/  — AttributionRedactor (API_REDACT_NOTE_ATTRIBUTION, API_REDACT_ACTOR_PROOF)
  audit/    — ApiAuditBridge (server-synthesized actor "api:<tokenId>", kind external)
  routes/   — ItemRoutes, ItemWriteRoutes, NoteRoutes, NoteWriteRoutes, DependencyRoutes, DependencyWriteRoutes, TransitionRoutes, SearchRoutes, ConfigRoutes, ServiceRoutes, EventRoutes, WellKnownRoutes, WriteIdempotency

application/service/rest/
  MergePatchApplier — RFC 7396 JSON Merge Patch
  StatusGraphBuilder — status-transition graph builder for ConfigRoutes
  WorkItemPatchProjection — projects existing item fields into a JsonObject base for merge-patch

infrastructure/security/
  ConstantTimeCompare — timing-safe byte comparison (used by BearerTokenStore for SHA-256 digests)
  JwksKeyCache — JWKS key material cache for JWKS auth mode
```

**Entry point:** `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/CurrentMain.kt`

## Modes of Operation

- **Orchestration** (default) — orchestrator pushes items through phases via `advance_item`
- **Claim** (opt-in) — consumers pull work via `claim_item`, holding TTL-based ownership before advancing

The optional `actor_authentication` config block adds JWKS-based identity verification — independent of claim mode (claim works without it). See [`current/docs/fleet-deployment.md`](current/docs/fleet-deployment.md).

## Trait System (Orchestration Signals)

Traits are **composable orchestration signals** declared in `.taskorchestrator/config.yaml` under the `traits:` key. They are NOT merely note requirements. Each trait carries three dimensions:

1. **Note requirements** -- notes with `key`, `role`, `required` that merge into an item's resolved schema and enforce gates
2. **Guidance** -- `guidance` text on each note telling agents HOW to fill it (context, constraints, structure)
3. **Skill routing** -- optional `skill` pointer (e.g., `skill: "migration-review"`) that routes evaluation to a specialized skill

**Resolution flow:** `ToolExecutionContext.resolveSchema(item)` merges trait notes from two sources:
- `defaultTraits` on the schema type definition (always applied to items of that type)
- Per-item `traits` from the item's `properties` JSON bag (applied via `PropertiesHelper.extractTraits()`)

Base schema note keys win on duplicates; first-trait-in-order wins for duplicate trait keys.

**Example:** An item typed `feature-task` with trait `needs-migration-review` gets the base `feature-task` notes PLUS the `migration-assessment` note (queue phase, required, with `migration-review` skill pointer and guidance about SQLite table recreation patterns). The orchestrator sees this merged schema via `get_context(itemId=...)` and routes accordingly -- dispatching a migration-specialized agent or invoking the migration-review skill.

**Key files:**

| What | Path |
|------|------|
| Trait definitions | `.taskorchestrator/config.yaml` -> `traits:` section |
| Schema resolution + trait merging | `current/.../application/tools/ToolExecutionContext.kt` -> `resolveSchema()`, `mergeTraits()` |
| Properties helper | `current/.../application/tools/PropertiesHelper.kt` -> `extractTraits()`, `mergeTraits()` |
| Domain models | `WorkItemSchema.kt` (`defaultTraits`), `NoteSchemaEntry.kt` (`skill`, `guidance`) |

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
- Currently used by: `YamlWorkItemSchemaService`
- **This is the GLOBAL/fallback config.** `AGENT_CONFIG_DIR` locates the single, server-wide `.taskorchestrator/config.yaml`, read once at startup (restart to reload). Per-**project** config is stored per-root in the DB — pushed via `manage_project_config` or `PUT /api/v1/roots/{rootId}/config`, synced from the workspace file by the `config-sync` SessionStart hook — and hot-reloads without a restart, layering over this global file per item `rootId`. See `claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md` → "Global vs Per-Project Config".

## Adding New Components

### New MCP Tool
1. Extend `BaseToolDefinition` in `current/src/main/kotlin/.../application/tools/`
2. Register in `CurrentMcpServer.kt`
3. Update all three documentation surfaces (see below)
4. Add to `ToolDocumentationConsistencyTest` tool list
5. Add tests in `current/src/test/kotlin/application/tools/`

### Tool Documentation Surfaces — Single-Source Policy (post token-efficiency program)

Every tool has three documentation surfaces:

| Surface | Location | Audience |
|---------|----------|----------|
| `description` string | In the tool source file | LLMs — seen via `tools/list` |
| `parameterSchema` | In the tool source file | MCP clients — drives validation |
| API reference | `current/docs/api-reference.md` | Humans |

**Single source of truth per parameter:** each parameter is documented ONCE, in its own
`parameterSchema` field `description` — not duplicated in the tool's prose `description` string.
The prose `description` is reserved for what a flat JSON Schema cannot express: operation/mode
enum selection, mode-selection rules, trigger effects (e.g. the trigger table in `advance_item`),
gate semantics, and mutual-exclusion/XOR constraints across fields. This keeps the `tools/list`
payload lean (the MCP Token-Efficiency Program brought the 14-tool payload from 56,984 chars to
under 30,000 by removing exactly this kind of prose/schema duplication).

**CI guard:** `ToolDocumentationConsistencyTest` asserts (1) every `parameterSchema` property has
a non-blank field-level `description`, and (2) every `operation`/`mode` enum value is still named
in the prose `description` (so callers can discover available operations without reading the full
schema). It no longer requires every param name to appear in the prose description — that older
policy is what produced the bloat this program removed.
The `api-reference.md` surface is not machine-checked — update it manually alongside code changes.

**When changing a tool's parameters:**
- Add/rename a param → update its `parameterSchema` field description and `api-reference.md`;
  touch the prose `description` only if the change affects mode-selection/trigger/gate semantics
- Remove a param → same, plus remove any prose mention if one existed
- Change required/optional status → update the field's own schema description and `api-reference.md`
- Do NOT reintroduce per-field prose in `description` that merely restates what's already in
  `parameterSchema` — that's the duplication this program removed

### New Database Migration
Create `current/src/main/resources/db/migration/V{N}__{Description}.sql`. SQLite has no `ALTER COLUMN` — schema changes require table recreation. New tables in `DirectDatabaseSchemaManager` must be inserted in foreign-key order.

### New Gradle Dependency
Add to `gradle/libs.versions.toml` (`[versions]` + `[libraries]`), then reference as `libs.{name}` in `build.gradle.kts`. Check Maven Central for the latest version.

## Database Management

**Key environment variables:**
- `DATABASE_PATH` — SQLite file path (default: `data/current-tasks.db`)
- `USE_FLYWAY` — enable Flyway migrations (default: `true` in Docker)
- `AGENT_CONFIG_DIR` — directory containing `.taskorchestrator/` (default: working dir)
- `MCP_TRANSPORT` — `stdio` (default) or `http`
- `MCP_HTTP_PORT` — HTTP port (default: `3001`)
- `LOG_LEVEL` — DEBUG / INFO / WARN / ERROR (default: `INFO`)
- `FLYWAY_REPAIR` — run repair and exit (default: `false`)
- `DEGRADED_MODE_POLICY` — overrides `actor_authentication.degraded_mode_policy` in config; values: `accept-cached` (default) | `accept-self-reported` | `reject`; invalid value = startup failure

**REST API environment variables** (see also `current/docs/fleet-deployment.md`):
- `API_ENABLED` — master API switch (default: `false`; set `true` to opt into the REST API, which then requires `API_AUTH_MODE`)
- `API_AUTH_MODE` — `bearer` or `jwks`; required when API enabled; no `none` mode
- `API_TOKENS_PATH` — bearer token YAML file path (default: `/run/secrets/api-tokens.yaml`)
- `API_JWKS_URL` — JWKS endpoint URL (jwks mode, required)
- `API_JWKS_ISSUER` — expected JWT `iss` claim (jwks mode, required)
- `API_JWKS_AUDIENCE` — expected JWT `aud` claim (jwks mode, required)
- `API_JWKS_ALGORITHMS` — comma-separated algorithm allowlist e.g. `RS256,EdDSA` (jwks mode, required)
- `API_JWKS_CACHE_TTL_SECONDS` — JWKS key cache TTL (default: `300`)
- `CORS_ALLOWED_ORIGINS` — comma-separated origins; empty = no CORS
- `CORS_ALLOWED_METHODS` — default: `GET,POST,PATCH,PUT,DELETE,OPTIONS`
- `CORS_ALLOWED_HEADERS` — default: `Authorization,Content-Type,If-Match`
- `CORS_EXPOSE_HEADERS` — default: `ETag,Last-Event-ID`
- `CORS_MAX_AGE_SECONDS` — default: `3600`
- `API_SSE_BUFFER_SIZE` — SSE ring-buffer size for Last-Event-ID replay (default: `1000`)
- `API_ALLOW_QUERY_TOKEN_FOR_SSE` — allow `?token=` auth on SSE endpoint (default: `false`)
- `API_SSE_AUTH_CHECK_INTERVAL_SECONDS` — token-expiry check interval on SSE connections (default: `30`)
- `API_REDACT_NOTE_ATTRIBUTION` — default `true`; when `true` non-admin callers see no actor/verification on notes/transitions
- `API_REDACT_ACTOR_PROOF` — default `true`; when `true` actor.proof redacted unless ADMIN + `?include=proof`
- `API_WARN_ON_CLAIMED_ADVANCE` — default `true`; WARN when REST API caller advances a claimed item

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
| Workflow config | `.taskorchestrator/config.yaml` |
| Note schema service | `current/.../infrastructure/config/YamlWorkItemSchemaService.kt` (backward-compat typealias `YamlNoteSchemaService`) |
| FTS5 search utilities | `current/.../application/service/search/` (FtsQuerySanitizer, RrfFusion) |
| Search types (SearchResult, SearchHit, SearchScope, SearchMatchMode) | `current/.../infrastructure/repository/SQLiteWorkItemRepository.kt` |
| BacklinkRow (domain model) | `current/.../domain/model/BacklinkRow.kt` |
| Plugin | `claude-plugins/task-orchestrator/` |
| Tests | `current/src/test/kotlin/` |
| REST API routes | `current/.../interfaces/api/v1/routes/` |
| REST API DTOs | `current/.../interfaces/api/v1/dto/Dtos.kt` |
| REST API auth config | `current/.../interfaces/api/v1/auth/` |
| REST API audit bridge | `current/.../interfaces/api/v1/audit/ApiAuditBridge.kt` |
| REST API event bus | `current/.../interfaces/api/v1/events/ApiEventBus.kt` |
| REST API auth loader | `current/.../infrastructure/config/ApiAuthConfigLoader.kt` |
| Security utilities | `current/.../infrastructure/security/` (ConstantTimeCompare, JwksKeyCache) |
| Merge patch + status graph | `current/.../application/service/rest/` |
| REST API doc | `current/docs/api-rest.md` |
| OpenAPI spec | `current/docs/api/openapi.yaml` |

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

- `current/docs/` — quick-start, api-reference, workflow-guide, fleet-deployment

## Git Workflow

**PR-per-feature flow** — the PR boundary is the **parent feature**, not individual child tasks. For a `feature-implementation` parent with N children (Parallel tier), all children commit to a shared feature worktree on a single `feat/<slug>` branch; one PR opens when the parent feature reaches terminal. For Direct/Delegated tier (single items), each item gets its own branch and PR. Local `main` always tracks `origin/main`. See the `/implement` skill (Step 2 worktree setup, Step 6 finalization) and `.claude/skills/implement/WORKTREE.md` (Shared feature worktree) for the full process.

- Follow conventional commits, reference issue numbers
- All tests must pass before committing
- Never force-push `main`
- Feature branches push to origin and merge via PR (squash merge on GitHub)
- After PR merges: `git checkout main && git pull origin main && git branch -D <branch>`
- Database migrations require special attention
