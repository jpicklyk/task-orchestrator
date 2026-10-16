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

**Composition root:** `bootstrap/` wires the server. No layer may import it; only `CurrentMain.kt` and `bootstrap`
itself may (rule R5).

**Layer rules (enforced by `LayeringTest`):** `domain` -> `application` -> `infrastructure` -> `interfaces`
(an outer layer may depend on an inner one; the reverse is forbidden) — `domain` must not import from
`application`/`infrastructure`/`interfaces`; `application` must not import from `infrastructure`/`interfaces`;
`infrastructure` must not import from `interfaces`. `infrastructure -> application` IS allowed (infrastructure
adapters implement application-layer ports); `interfaces -> anything` is unrestricted; the root package
(`CurrentMain.kt`) is unscoped. Enforced by
`current/src/test/kotlin/.../architecture/LayeringTest.kt` (Konsist), production source only, against the
two-way-ratcheted baseline `current/src/test/resources/architecture/layering-baseline.txt`, which is empty: any
violation fails the test, and so would a stale baseline line.

**Application sub-packages (enforced acyclic by `PackageCycleTest`):** `application/` splits into `api` (tool
request types and handlers' shared context), `service` (command services, advance), `read` (read views), `run`
(runs and seats), `knowledge` (links, related items, documents, search), `config` (schema/trait resolution, status
labels), `support` (small shared helpers, e.g. `IdRef`, `ScopeGuard`, `eventRootOf`, `Cancellation`, `UnitResults`),
`telemetry`, `port` (storage ports and paging: `PageRequest`, `ListPage`, `CursorCodec`), and the top-level
`decision`, `report` and `upgrade`. Dependencies point down this order: `api` -> service, read, run, knowledge, config,
support, telemetry, port (`decision` and `report` depend on `api`, not the reverse); `service` -> config, knowledge,
port, support, telemetry; `read` -> port, service; `run` -> config, port, service, support; `knowledge` -> port,
support, telemetry; `support` -> port; `telemetry` -> port; `config` and `port` depend on no other sub-package. `PackageCycleTest` fails on any edge that closes a cycle
between sub-packages, against `current/src/test/resources/architecture/package-cycle-baseline.txt`, which is empty.
To share a type, move it down to a package every consumer already sits above. Guard tests
(`ToolSurfaceGuardsTest`, `ToolSchemaGoldenTest`, `ApiReferenceFreshnessTest`) derive their tool list from
`buildToolSpecs()` (`interfaces/shared/tools/ToolRegistry.kt`) rather than a hard-coded list, so
they automatically cover every tool the production server registers.

**Dialect neutrality (enforced by `DialectNeutralityTest`):** SQLite and Exposed code lives only under `infrastructure/sqlite/` (`org.sqlite.*` and `org.jetbrains.exposed.*` imports, plus SQLite dialect tokens such as `PRAGMA` or `datetime(` in string literals, are forbidden elsewhere) against a two-way-ratcheted baseline at `current/src/test/resources/architecture/dialect-neutrality-baseline.txt`. Storage ports (`*Store`) live in `application/port/`.

## Modes of Operation

- **Orchestration** (default) - orchestrator pushes items through phases via `advance` `transition`
- **Claim** (opt-in) - consumers pull work via `advance` `claim`, holding TTL-based ownership before advancing

Caller identity comes from the transport credential (`API_AUTH_MODE`, the `API_JWKS_*` variables, the `/mcp` auth mode) — independent of claim mode (claim works without it). See [`current/docs/fleet-deployment.md`](current/docs/fleet-deployment.md). The old `actor_authentication` config block is removed: a leftover block fails startup.

## Trait System (Orchestration Signals)

Traits are **composable orchestration signals** declared in `.taskorchestrator/config.yaml` under the `traits:` key. They are NOT merely note requirements. Each trait carries five dimensions:

1. **Note requirements** -- notes with `key`, `role`, `required` that merge into an item's resolved schema and enforce gates
2. **Guidance** -- `guidance` text on each note telling agents HOW to fill it (context, constraints, structure)
3. **Skill routing** -- optional `skill` pointer (e.g., `skill: "migration-review"`) that routes evaluation to a specialized skill
4. **Resources** -- optional `resources:` list declaring shared-resource requirements (`exclusive` or `advisory` mode) enforced as a lease gate at WORK entry, independent of the note-requirement dimension. See `claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md` -> "Resources (Trait Dimension)" for declaration syntax, merge semantics, and the leaf-task-types-only rule.
5. **Dispatch** -- optional `dispatch.<queue|work|review>: {agent?, model?, effort?}` declaring which agent/model/effort should pick up a phase, surfaced on `advance` `transition` results and `items_read` `context` and `schema`. See `claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md` -> "Dispatch (Trait Dimension)" for declaration syntax and precedence.

**Resolution flow:** schema and trait resolution live in `application/config` — `EffectiveConfigResolver` (the shared entry point, one instance built in `ServerComposition` and used by both MCP and REST), `LayeredConfig` (the per-root/global layer lookups for one resolution, built fresh per `EffectiveConfigResolver.layered(rootId)` call) and `TraitResolver` (the one place every cross-trait merge happens: notes, seats, resources, dispatch by role and by seat). `ToolExecutionContext.resolveSchema(item)` and its sibling methods now delegate to `configResolver` rather than resolving inline. Trait notes merge from two sources:
- `defaultTraits` on the schema type definition (always applied to items of that type)
- Per-item `traits` from the item's typed `WorkItem.traits` list (stored in the `item_traits` table; read by every `ItemStore`/`ClaimStore`/`HierarchyStore` read)

**One precedence rule.** An item's base schema is per-root exact type -> server exact type -> per-root `default` -> server `default`; tags never select a schema, and a null type uses only the two `default` steps. Every trait dimension uses ONE trait order: per-item traits first, then `defaultTraits`, deduplicated with the first position kept. Notes: base notes first, a key's content comes from its first declaration (base before traits), and `required` is true if ANY declaration marks it. Seats: first declaration of a name wins (a second `enters` seat in a phase is demoted). Resources: union of keys, EXCLUSIVE beats ADVISORY, `ttlSeconds` first-seen. Dispatch by role and by seat: first trait wins. When the per-root `traits:` map contains a trait name, EVERY dimension of that trait (notes, seats, resources, both dispatch shapes) comes from the per-root entry — a dimension it does not declare is empty, never the server's. The resource registry is server-only. The 3.x `schema_resolution` key is removed (it parses with one warning and has no effect).

**Schema pins.** Every item is pinned (`WorkItem.pin`, the V26 `pinned_*`/`lifecycle` columns) to the server and root config versions that were the heads when the pin was taken and to its base schema key and layer. The item facets (`resolveSchema`, `resolveSchemaWithSource`, `resolveHasReviewPhase`, `resolveResourceRequirements`, `resolveDispatchProfile(s)`, `resolveDispatchBySeat`) resolve through `EffectiveConfigResolver.forItem(item)`: the pinned versions (by id, via `ConfigVersionSource.layerAt` on `PerRootConfigService`) with the base schema forced to the pinned key and layer; an unpinned item, or a resolver without a version source, resolves at the heads. Root-wide settings (labels, note limits, independence, registry, type/trait lists, every `*ForType`) always read the heads. `SchemaPinService` is the only code that constructs a `SchemaPin`; the pin points are create, type change, cross-root reparent, trait change (`ItemCommandService`), every transition out of TERMINAL (`AdvanceService`), and `items_write` `repin`, each recording one table-only `item.pinned` event. The `pin-backfill` data step pins 3.x rows by the 3.x rule. See `current/docs/config-reference.md` ("Schema pins").

**Example:** An item typed `feature-task` with trait `needs-migration-review` gets the base `feature-task` notes PLUS the `migration-assessment` note (queue phase, required, with `migration-review` skill pointer and guidance about SQLite table recreation patterns). The orchestrator sees this merged schema via `items_read(select="context", itemId=...)` and routes accordingly -- dispatching a migration-specialized agent or invoking the migration-review skill.

**Key files:**

| What | Path |
|------|------|
| Trait definitions | `.taskorchestrator/config.yaml` -> `traits:` section |
| Schema resolution | `current/.../application/config/LayeredConfig.kt` (per-layer lookups, `resolveBaseSchema()`/`resolveTypeSchema()`, the precedence table), `current/.../application/config/EffectiveConfigResolver.kt` (shared entry point) |
| Trait merging | `current/.../application/config/TraitResolver.kt` (`mergeSchema()`, `resources()`, `dispatch()`, `dispatchBySeat()`, `order()`) |
| Item traits | `WorkItem.traits` (domain); `current/.../infrastructure/sqlite/schema/ItemTraitsTable.kt` + `V27__Item_Traits.sql`; `ItemStore.traitsOf()`; `TraitsFromPropertiesStep` (one-time move out of `properties`) |
| Domain models | `WorkItemSchema.kt` (`defaultTraits`), `NoteSchemaEntry.kt` (`skill`, `guidance`) |
| Dispatch resolution | `current/.../domain/model/DispatchProfile.kt` (domain model); `current/.../application/config/TraitResolver.kt` -> `dispatch()`/`dispatchBySeat()` |
| `ToolExecutionContext` | `current/.../application/api/ToolExecutionContext.kt` - thin delegate methods over `configResolver`; no longer where the merge logic lives |

## Tight Coupling Areas

### ToolExecutionContext
`ToolExecutionContext` (`application/api/ToolExecutionContext.kt`) is constructed exactly ONCE, in `ServerComposition.build()`, and every tool handler receives it as `env`. REST builds no copy: the RPC route `POST /api/v1/ops/{tool}` (`interfaces/api/v1/ops/OpsRoutes.kt`) runs the same `ToolDispatcher` MCP uses, and the two resource GETs translate into registry tools (`ItemRoutes.kt` into `items_read`, `NoteResourceRoutes.kt` into `notes_read`); all of it is wired in `CurrentMcpServer.installRestApiRoutes()` from the single `CompositionResult`. This is also what makes REST and MCP share one per-root last-known-good config cache (one `EffectiveConfigResolver`, see `api-rest.md` section 6). `AdvanceService` itself is built in exactly one place, `AdvanceServiceFactory.forItem()` (`application/service/AdvanceServiceFactory.kt`); the `advance` `transition` op (`interfaces/mcp/tools/advance/TransitionOp.kt`), `TreeCompletionService` (`items_write` `complete_tree`) and `ItemCommandService` (the old-parent cascade of a reparent or delete) all call `advanceServiceFactory.forItem(item)` instead of constructing `AdvanceService` inline. One advance is ONE unit of work (`AdvanceService.advance`): `TransitionSnapshotLoader` loads the facts, the domain `TransitionPolicy` decides, and the apply, its `item.transitioned` event (recorded by `AdvanceService`), lease acquire/release and every cascade commit together. The read-only preview (`items_read` `context` `canAdvance`) goes through `AdvanceServiceFactory.preview()` (`TransitionPreview`, exposed as `env.transitionPreview()`) over the same loader and policy; status labels have one policy, `EffectiveConfigResolver.labelFor`. Adding a new constructor dependency to `ToolExecutionContext` or `AdvanceServiceFactory` still means updating that class's own (now singular) construction site plus its own field/getter - the compiler will not catch a site you miss if the new parameter has a default.

### Flyway migrations are the only schema path
`DirectDatabaseSchemaManager` and `SchemaManagerFactory` are gone from production: the Exposed tables are query mappings only, and `NoSchemaUtilsInProductionTest` bans `SchemaUtils` in production source. A new table or column starts with a Flyway migration under `db/migration/sqlite/`. Migration files are byte-immutable once released (Flyway checksums every line, comments included). Startup runs the whole schema phase under an OS file lock (`<db>.migrate.lock`) and then verifies the FTS5 tables/triggers inventory and FTS `integrity-check` (see `StartupIntegrity`); a missing FTS table or trigger fails startup. A test-only `DirectDatabaseSchemaManager` bridge remains under `src/test` until the test fixture migration removes it. The real hazard is parity: the Exposed table objects must match the Flyway DDL, and nothing at compile time enforces it. `SchemaParityTest` and the schema snapshots under `current/src/test/resources/schema/snapshot/` guard it, so a new table or column changes the migration, the Exposed object and the snapshot together.

## Configuration Directory (AGENT_CONFIG_DIR)

**CRITICAL:** No service reads `AGENT_CONFIG_DIR` (or any environment variable) itself. The environment is read once, in
`AppConfig` (`infrastructure/config/AppConfig.kt`), and the composition root hands every consumer a typed value.
`architecture/EnvAccessTest` fails the build when production code outside `AppConfig.kt`, `EnvBoolean.kt` and
`LogFileAppenderInstaller.kt` calls `System.getenv`.

```kotlin
// AppConfig.resolveConfigBaseDir(agentConfigDir): String =
//     agentConfigDir ?: System.getProperty("user.dir")
val configBaseDir = Paths.get(AppConfig.resolveConfigBaseDir(appConfig.agentConfigDir))   // built ONCE in ServerComposition
val globalConfigPath = configBaseDir.resolve(".taskorchestrator/config.yaml")
```

- In Docker: `-e AGENT_CONFIG_DIR=/project` (where config is mounted)
- In local dev: not needed (uses working directory)
- `ServerComposition.kt` builds ONE `GlobalConfigFile(globalConfigPath)` (`infrastructure/config/GlobalConfigFile.kt`), which reads
  `.taskorchestrator/config.yaml` exactly ONCE (a broken file fails startup before the readiness marker) and exposes the raw text
  (`ConfigFileSource`) plus a lenient parsed layer. The file is **imported** by the every-boot data step `server-config-import`
  (`application/upgrade/steps/ServerConfigImportStep.kt`, phase `CONFIG_IMPORT`): `ConfigService.importDocument` parses it strictly
  and appends it as a `file-import` config version (`project.rootId` absent = the SERVER scope; present = that depth-0 root), verbatim
  with `configFingerprint(text)`. The 3.x top-level keys (`schema_resolution`, `orchestration`, `retrospective`, `actor_attribution`)
  are allowed at boot as stored warnings (`GET /api/v1/info` -> `configWarnings`); any other error fails startup as `DATA_STEPS`.
  An absent or empty file imports nothing.
- **Runtime config is DB-only.** The server layer is the stored SERVER head, read per call through `ServerConfigSource`
  (`PerRootConfigService` implements both it and `PerRootConfigSource`; `EffectiveConfigResolver(server, perRoot)`), so a version another
  process appends to a shared volume is served without a restart (last booting process wins, by design). A head that cannot be
  loaded surfaces as `config_invalid` (REST 422) at every boundary; read faults answer `unavailable` (kind `shedding`).
- `checkRemovedServerConfig` (`infrastructure/config/RemovedServerConfigKeys.kt`) reads the same `GlobalConfigFile` at startup and fails it on a leftover `actor_authentication:` or `auditing:` key or a set `DEGRADED_MODE_POLICY`. `TypeBackfillStep` also keeps reading the file.
- `jwks_path` resolves against the injected `configBaseDir` (`DefaultJwksKeySetProvider(config, configBaseDir)`); `bodyFromFile` against
  the `FileBodySource` root built from the same base directory.
- **This is the SERVER-scope/fallback config.** Per-**project** config is stored per-root in the DB: pushed via the `config` tool's `push` op
  (`POST /api/v1/ops/config`), synced from the workspace file by the `config-sync` SessionStart hook, and layered over the server
  config per item `rootId` (hot-reloads, no restart). See `claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md` -> "Global vs Per-Project Config".
- This repo's own `.taskorchestrator/config.yaml` is git-tracked dogfood config and doubles as a living schema/trait example — it is delivered to the server per-root via the `config-sync` hook, not mounted as the global config. The actual global mount is the process-schema floor at `deploy/global-config/.taskorchestrator/` — agent-observation, session-retrospective, improvement-proposal, and container schemas only, shared across every project the server serves.

## Adding New Components

Step-by-step checklists for a new MCP tool, select or op, a new Flyway migration, and a new Gradle dependency live in the `add-component` skill (`.claude/skills/add-component/SKILL.md`) - load it before doing any of those. The non-negotiable rule from it, kept here because it applies whenever a request class changes: **each parameter is documented ONCE, in the `@Doc` of its field in the request class** (`application/api/<area>/`); a tool's `description` and a select/op `doc` are reserved for operation selection, trigger and gate semantics, and cross-field XOR constraints - never per-field restatement. The request class is the one source of both decoding and documentation, so a `@Doc` edit is followed by two regenerations, each committed: `./gradlew :current:regenerateToolGoldens` (the per-tool schema goldens under `current/src/test/resources/golden/tools/`) and `./gradlew :current:regenerateApiReference` (the generated field tables and error catalog in `current/docs/api-reference.md`). `ToolSchemaGoldenTest`, `ApiReferenceFreshnessTest` and `ToolSurfaceGuardsTest` (documentation and token budgets) guard this in CI; only the behavior prose of `api-reference.md` is hand-written.

## Database Management

**Boolean env vars** are parsed by `EnvBoolean` (`infrastructure/config/EnvBoolean.kt`): `true`/`1`/`yes`
and `false`/`0`/`no` (case-insensitive, trimmed) are all recognized; an unset var silently takes its
default, and an unrecognized non-empty value either falls back to the default with a WARN log
(`EnvBoolean.parse`, used by every var below except the three noted otherwise) or fails startup
(`EnvBoolean.require`, used by `API_ENABLED`, `API_ALLOW_UNAUTHENTICATED` and `MCP_ALLOW_UNAUTHENTICATED`).

**Key environment variables:**
- `DATABASE_PATH` — SQLite file path (default: `data/current-tasks.db`; in-memory URLs are refused)
- `DATABASE_MAX_CONNECTIONS` — reader pool size (default: `10`, clamped 1-64); the writer pool is always 1 connection
- `SCHEMA_MODE` — `migrate` (default) or `validate` (no migrate/baseline; a pending or future version fails startup); any other value fails startup. `USE_FLYWAY` is removed and ignored with a WARN (Flyway is the only schema path)
- `AGENT_CONFIG_DIR` — directory containing `.taskorchestrator/` (default: working dir)
- `MCP_TRANSPORT` — `stdio` (default) or `http`
- `MCP_HTTP_PORT` — HTTP port (default: `3001`)
- `MCP_TOOL_PROFILE` — `orchestration` (default), `knowledge` or `director` (case-insensitive); the stdio transport's tool profile. HTTP serves all three: `/mcp` is orchestration, `/mcp/knowledge` is knowledge, `/mcp/director` is director. Any other value fails startup
- `MCP_ALLOW_UNAUTHENTICATED` — opt-in (default `false`, parsed with `EnvBoolean.require`) that lets `MCP_TRANSPORT=http` serve `/mcp` with the REST API disabled, every caller as the `local` principal; without it that combination fails startup (`MCP_AUTH`)
- `MCP_SERVER_NAME` — service name reported in MCP identity and REST well-known/service metadata (default: `mcp-task-orchestrator-current`)
- `LOG_LEVEL` — DEBUG / INFO / WARN / ERROR (default: `INFO`)
- `LOG_FILE` — opt-in log file path (default: unset, no file logging). Logs are always JSON on stderr; see `current/docs/fleet-deployment.md` → "Logging".
- `FLYWAY_REPAIR` — run repair and exit (default: `false`)
- `DB_COMPACT_ON_UPGRADE` — run the one-time post-V17 startup compaction (VACUUM + FTS5 rebuild) on the first Flyway-mode start after upgrading (default: `true`; set `false` to opt out)
- `FTS_INTEGRITY` - `startup` (default) runs the FTS5 `integrity-check` on every FTS table at each start; `off` skips only that check (the FTS inventory check still runs). An unrecognized value falls back to `startup` with a WARN
- `RETENTION_CALL_LOG_DAYS` (default `90`), `RETENTION_EVENTS_DAYS` (`180`, activity-class events), `RETENTION_AUDIT_EVENTS_DAYS` (`0`) and `RETENTION_LEASE_HISTORY_DAYS` (`365`) — retention of the pruned tables; `0` keeps that data forever, a blank, negative or non-integer value fails startup, and call-log rows are deleted only for days already folded into `usage_daily`. Values, guarantees and the known limits are in `current/docs/fleet-deployment.md` → "Data Retention" (`infrastructure/config/RetentionSettings.kt`)
- `READINESS_FILE` — path to the readiness marker file the server touches once startup (DB init,
  schema update, and transport bind) has fully succeeded; default `/tmp/mcp-task-orchestrator.ready`.
  Backs the Docker image's `HEALTHCHECK` (see `current/docs/fleet-deployment.md`) and covers both
  `stdio` and `http` transport — the marker is cleared at the start of startup (before DB init, so a
  stale marker from a crashed run cannot signal readiness) and again on shutdown

**REST API environment variables** (`API_*`, `CORS_*`, `RESOURCE_LEASES_ENFORCED`) are documented with defaults in `current/docs/fleet-deployment.md` and `current/docs/api-rest.md`. Gotchas: `API_ENABLED`/`API_ALLOW_UNAUTHENTICATED` use `EnvBoolean.require` (a bad value fails startup) while the other booleans fall back with a WARN; `API_JWKS_MAX_STALE_SECONDS` (jwks mode) is how old a cached key set may be and still be served after a failed refresh, default `max(3600, API_JWKS_CACHE_TTL_SECONDS)`, and a value that is not a positive integer or is below the cache TTL fails startup; `RESOURCE_LEASES_ENFORCED` is read ONCE at startup into `AppConfig.resourceLeasesEnforced` (restart required; `true`/`1`/`yes` vs `false`/`0`/`no`; false skips the lease gate and acquisition on every advance, releases still run).

**Migration files:** `current/src/main/resources/db/migration/sqlite/` (the only configured Flyway location)

## Testing

- Tests mirror source under `current/src/test/kotlin/`
- JUnit 5 + MockK; real file-backed SQLite (`SqliteTestDatabase` fixture) for repository and route tests
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
