## Project: MCP Task Orchestrator

A Kotlin-based MCP server providing hierarchical work item management with dependency tracking, note schemas, and role-based workflow automation.

## Build Commands

```bash
# Docker (most common)
docker build -t task-orchestrator:dev .
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v "$(pwd)"/deploy/global-config/.taskorchestrator:/project/.taskorchestrator:ro \
  -e AGENT_CONFIG_DIR=/project \
  task-orchestrator:dev
```

## Architecture

Source lives under `current/`.

**Package root:** `io.github.jpicklyk.mcptask.current`
**Source root:** `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/`

**Entry point:** `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/CurrentMain.kt`

**Layer rules (enforced by `LayeringTest`):** `domain` -> `application` -> `infrastructure` -> `interfaces`
(an outer layer may depend on an inner one; the reverse is forbidden) — `domain` must not import from
`application`/`infrastructure`/`interfaces`; `application` must not import from `infrastructure`/`interfaces`;
`infrastructure` must not import from `interfaces`. `infrastructure -> application` IS allowed (infrastructure
adapters implement application-layer ports); `interfaces -> anything` is unrestricted; the root package
(`CurrentMain.kt`) is unscoped. Enforced by
`current/src/test/kotlin/.../architecture/LayeringTest.kt` (Konsist), production source only, against a
checked-in, two-way-ratcheted baseline of pre-existing violations at
`current/src/test/resources/architecture/layering-baseline.txt` — a new violation fails the test, and so
does a stale baseline line whose violation was already fixed. Guard tests
(`ToolDocumentationConsistencyTest`, `ToolTokenBudgetTest`) derive their tool list from
`buildMcpTools()` (`interfaces/mcp/CurrentMcpServer.kt`, `internal`) rather than a hard-coded list, so
they automatically cover every tool the production server registers.

## Modes of Operation

- **Orchestration** (default) — orchestrator pushes items through phases via `advance_item`
- **Claim** (opt-in) — consumers pull work via `claim_item`, holding TTL-based ownership before advancing

The optional `actor_authentication` config block adds JWKS-based identity verification — independent of claim mode (claim works without it). See [`current/docs/fleet-deployment.md`](current/docs/fleet-deployment.md).

## Trait System (Orchestration Signals)

Traits are **composable orchestration signals** declared in `.taskorchestrator/config.yaml` under the `traits:` key. They are NOT merely note requirements. Each trait carries five dimensions:

1. **Note requirements** -- notes with `key`, `role`, `required` that merge into an item's resolved schema and enforce gates
2. **Guidance** -- `guidance` text on each note telling agents HOW to fill it (context, constraints, structure)
3. **Skill routing** -- optional `skill` pointer (e.g., `skill: "migration-review"`) that routes evaluation to a specialized skill
4. **Resources** -- optional `resources:` list declaring shared-resource requirements (`exclusive` or `advisory` mode) enforced as a lease gate at WORK entry, independent of the note-requirement dimension. See `claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md` -> "Resources (Trait Dimension)" for declaration syntax, merge semantics, and the leaf-task-types-only rule.
5. **Dispatch** -- optional `dispatch.<queue|work|review>: {agent?, model?, effort?}` declaring which agent/model/effort should pick up a phase, surfaced on `advance_item`/`get_context`/`query_items`. See `claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md` -> "Dispatch (Trait Dimension)" for declaration syntax and precedence.

**Resolution flow:** `ToolExecutionContext.resolveSchema(item)` merges trait notes from two sources:
- `defaultTraits` on the schema type definition (always applied to items of that type)
- Per-item `traits` from the item's `properties` JSON bag (applied via `PropertiesHelper.extractTraits()`)

Base schema note keys win on duplicates; first-trait-in-order wins for duplicate trait keys. Dispatch resolution (`resolveDispatchProfile()`) walks per-item traits FIRST, then `defaultTraits` -- the reverse of note merging -- and a per-root trait's `dispatch` map replaces the global trait's map wholesale, per trait (no per-role fall-through).

**Example:** An item typed `feature-task` with trait `needs-migration-review` gets the base `feature-task` notes PLUS the `migration-assessment` note (queue phase, required, with `migration-review` skill pointer and guidance about SQLite table recreation patterns). The orchestrator sees this merged schema via `get_context(itemId=...)` and routes accordingly -- dispatching a migration-specialized agent or invoking the migration-review skill.

**Key files:**

| What | Path |
|------|------|
| Trait definitions | `.taskorchestrator/config.yaml` -> `traits:` section |
| Schema resolution + trait merging | `current/.../application/tools/ToolExecutionContext.kt` -> `resolveSchema()`, `mergeTraits()` |
| Properties helper | `current/.../application/tools/PropertiesHelper.kt` -> `extractTraits()`, `mergeTraits()` |
| Domain models | `WorkItemSchema.kt` (`defaultTraits`), `NoteSchemaEntry.kt` (`skill`, `guidance`) |
| Dispatch resolution | `current/.../domain/model/DispatchProfile.kt` (domain model); `current/.../application/tools/ToolExecutionContext.kt` -> `resolveDispatchProfile()` |

## Tight Coupling Areas

### ToolExecutionContext
Most of its dependencies are defaulted (`ToolExecutionContext.kt`), but the class is still constructed by hand at three separate sites: `ServerComposition.kt` (MCP), and `ItemRoutes.kt` / `ItemWriteRoutes.kt` (REST). `AdvanceService` is likewise hand-wired at three call sites: `AdvanceItemTool.kt`, `ItemWriteRoutes.kt`, and `CompleteTreeTool.kt`. Adding a new constructor dependency to either class means updating every one of its construction sites, not just the class itself — the compiler will not catch a site you miss if the new parameter has a default.

### DirectDatabaseSchemaManager
Table creation order is derived automatically (`SchemaUtils.create` orders by FK references), not manually maintained. The real hazard is keeping the Direct-mode table list and DDL in parity with the Flyway migrations: a new table or column must be added to both, and the two can drift silently since nothing enforces the parity at compile time.

## Configuration Directory (AGENT_CONFIG_DIR)

**CRITICAL:** All services reading from `.taskorchestrator/` MUST support the `AGENT_CONFIG_DIR` environment variable.

```kotlin
// AppConfig.resolveConfigBaseDir(agentConfigDir): String =
//     agentConfigDir ?: System.getProperty("user.dir")
val globalConfigPath = Paths.get(AppConfig.resolveConfigBaseDir(appConfig.agentConfigDir))
    .resolve(".taskorchestrator/config.yaml")
```

- In Docker: `-e AGENT_CONFIG_DIR=/project` (where config is mounted)
- In local dev: not needed (uses working directory)
- Resolved once in `ServerComposition.kt` and shared by every service that reads the global config:
  the schema service (`YamlWorkItemSchemaService`, exposed under the `YamlNoteSchemaService` type
  alias), the status-label service, and the actor-authentication service. `ManageNotesTool`,
  `ManagePlanDocumentsTool`, and `JwksKeySetProvider` also resolve it independently for their own
  file access.
- **This is the GLOBAL/fallback config.** `AGENT_CONFIG_DIR` locates the single, server-wide `.taskorchestrator/config.yaml`, read once at startup (restart to reload). Per-**project** config is stored per-root in the DB — pushed via `manage_project_config` or `PUT /api/v1/roots/{rootId}/config`, synced from the workspace file by the `config-sync` SessionStart hook — and hot-reloads without a restart, layering over this global file per item `rootId`. See `claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md` → "Global vs Per-Project Config".
- This repo's own `.taskorchestrator/config.yaml` is git-tracked dogfood config and doubles as a living schema/trait example — it is delivered to the server per-root via the `config-sync` hook, not mounted as the global config. The actual global mount is the process-schema floor at `deploy/global-config/.taskorchestrator/` — agent-observation, session-retrospective, improvement-proposal, and container schemas only, shared across every project the server serves.

## Adding New Components

Step-by-step checklists for a new MCP tool, a new Flyway migration, and a new Gradle dependency live in the `add-component` skill (`.claude/skills/add-component/SKILL.md`) — load it before doing any of those. The non-negotiable rule from it, kept here because it applies whenever a tool's parameters change: **each parameter is documented ONCE, in its own `parameterSchema` field `description`**; the tool's prose `description` string is reserved for operation/mode enum selection, trigger and gate semantics, and cross-field XOR constraints — never per-field restatement. `ToolDocumentationConsistencyTest` guards this in CI; `current/docs/api-reference.md` is not machine-checked and must be updated by hand alongside code changes.

## Database Management

**Boolean env vars** are parsed by `EnvBoolean` (`infrastructure/config/EnvBoolean.kt`): `true`/`1`/`yes`
and `false`/`0`/`no` (case-insensitive, trimmed) are all recognized; an unset var silently takes its
default, and an unrecognized non-empty value either falls back to the default with a WARN log
(`EnvBoolean.parse`, used by every var below except the two noted otherwise) or fails startup
(`EnvBoolean.require`, used by `API_ENABLED`/`API_ALLOW_UNAUTHENTICATED`).

**Key environment variables:**
- `DATABASE_PATH` — SQLite file path (default: `data/current-tasks.db`)
- `USE_FLYWAY` — enable Flyway migrations (default: `true` in Docker)
- `AGENT_CONFIG_DIR` — directory containing `.taskorchestrator/` (default: working dir)
- `MCP_TRANSPORT` — `stdio` (default) or `http`
- `MCP_HTTP_PORT` — HTTP port (default: `3001`)
- `MCP_SERVER_NAME` — service name reported in MCP identity and REST well-known/service metadata (default: `mcp-task-orchestrator-current`)
- `LOG_LEVEL` — DEBUG / INFO / WARN / ERROR (default: `INFO`)
- `LOG_FILE` — opt-in log file path (default: unset, no file logging). Logs are always JSON on stderr; see `current/docs/fleet-deployment.md` → "Logging".
- `FLYWAY_REPAIR` — run repair and exit (default: `false`)
- `DEGRADED_MODE_POLICY` — overrides `actor_authentication.degraded_mode_policy` in config; values: `accept-cached` (default) | `accept-self-reported` | `reject`; invalid value = startup failure
- `READINESS_FILE` — path to the readiness marker file the server touches once startup (DB init,
  schema update, and transport bind) has fully succeeded; default `/tmp/mcp-task-orchestrator.ready`.
  Backs the Docker image's `HEALTHCHECK` (see `current/docs/fleet-deployment.md`) and covers both
  `stdio` and `http` transport — the marker is cleared on shutdown

**REST API environment variables** (`API_*`, `CORS_*`, `RESOURCE_LEASES_ENFORCED`) are documented with defaults in `current/docs/fleet-deployment.md` and `current/docs/api-rest.md`. Gotchas: `API_ENABLED`/`API_ALLOW_UNAUTHENTICATED` use `EnvBoolean.require` (a bad value fails startup) while the other booleans fall back with a WARN; `RESOURCE_LEASES_ENFORCED` is read fresh on every `advance_item`/advance-route call, but since a running process's environment cannot change and a container's environment changes only when the container is recreated, flipping this var takes effect only after the process or container restarts.

**Migration files:** `current/src/main/resources/db/migration/`

## Testing

- Tests mirror source under `current/src/test/kotlin/`
- JUnit 5 + MockK; H2 in-memory database for repository tests
- **Never pipe `./gradlew` output to `tail`** — run directly and read full output

## Claude Code Plugin Discovery

Two skill systems — do not confuse them: **project-level skills** in `.claude/skills/` (auto-discovered) and **plugin skills** in `claude-plugins/task-orchestrator/skills/` (activated via `enabledPlugins` in `.claude/settings.json`). The plugin cache is version-keyed and re-extracts lazily, so edits to plugin files are NOT picked up by a plain marketplace update — the refresh procedure and its gotchas are in `claude-plugins/CLAUDE.md`.

## Git Workflow

**PR-per-feature flow** — the PR boundary is the **parent feature**, not individual child tasks. For a `feature-implementation` parent with N children (Parallel tier), all children commit to a shared feature worktree on a single `feat/<slug>` branch; one PR opens when the parent feature reaches terminal. For Direct/Delegated tier (single items), each item gets its own branch and PR. Local `main` always tracks `origin/main`. See the `/implement` skill (Step 2 worktree setup, Step 6 finalization) and `.claude/skills/implement/WORKTREE.md` (Shared feature worktree) for the full process.

- Follow conventional commits, reference issue numbers
- All tests must pass before committing
- Never force-push `main`
- Feature branches push to origin and merge via PR (squash merge on GitHub)
- After PR merges: `git checkout main && git pull origin main && git branch -D <branch>`
- Database migrations require special attention
