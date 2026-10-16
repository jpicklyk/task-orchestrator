---
name: add-component
description: Checklists for adding a new MCP tool, select or op, a new Flyway database migration, or a new Gradle dependency to the Task Orchestrator server, including the tool-documentation single-source policy (@Doc on request-class fields vs the tool and op prose vs the generated api-reference.md blocks). Use when adding or changing an MCP tool or its parameters, creating a migration, or adding a dependency to the version catalog.
---

# Adding New Components

Moved from the root `CLAUDE.md` so it loads only when needed. The single-source documentation policy summary stays in `CLAUDE.md`; this file is the full procedure.

### New MCP Tool, Select or Op

A tool is a `ToolSpec` (`SelectToolSpec` for reads, `OpsToolSpec` for writes) holding one `SelectSpec` / `OpSpec` per select or op. `ToolRegistry` (`interfaces/shared/tools/ToolRegistry.kt`) validates the whole set at construction and throws naming the tool, select/op and field.

1. **Request class.** A `@Serializable` class in `current/src/main/kotlin/.../application/api/<area>/` (for example `DepsListRequest`). Every field carries `@Doc("...")`; a field is optional exactly when it has a default; no polymorphic, sealed or recursive member; no reserved field (`actor`, `describe`, `select` for a select; `actor`, `op`, `idempotencyKey` for an op). The same class decodes the call strictly (an unknown member is `unknown_parameter`, a bad value `invalid_request`) and generates its JSON Schema.
2. **Select or op file.** One file per select/op under `interfaces/shared/tools/<area>/` (`interfaces/mcp/tools/<area>/` for `notes_*` and `advance`, which predate the shared folder). A select is `SelectSpec.Single` or `SelectSpec.Paged`; an op is `OpSpec`. Declare `name`, `doc`, `profiles` (`knowledge()`, `orchestration()` or `director()` members combined; an empty set means REST-only, visible to no MCP profile), `capability` (an op has no default: a new write op must never be silently read-only; a select defaults to `READ`), `errors` (codes beyond the common ones), and the handler or `execute`. Handlers receive the one `ToolExecutionContext` as `env` and a `CallContext`; scope checks go through `ScopeGuard`, ids through the shared scope-aware resolver.
3. **Register.** Add the select/op to its tool (for example `depsReadTool()`); a new tool is also added to `buildToolSpecs()` in `ToolRegistry.kt`. That is the only registration: MCP, `POST /api/v1/ops/{tool}`, `describe: true`, the goldens and every guard read the same registry.
4. **Regenerate and commit.** `./gradlew :current:regenerateToolGoldens` rewrites the per-tool schema goldens (`current/src/test/resources/golden/tools/`); `./gradlew :current:regenerateApiReference` rewrites the generated blocks of `current/docs/api-reference.md`. For a new tool, first add its prose section and an empty marker pair `<!-- generated:tool NAME -->` / `<!-- /generated:tool NAME -->` to `api-reference.md` (the regenerator refuses a missing pair). Never edit a golden or a generated block by hand. Then run `node claude-plugins/task-orchestrator/scripts/gen-call-shapes.mjs` and commit its output: it regenerates the plugin's call shapes and tool-name lists from the goldens and the API reference, and CI fails on a stale copy.
5. **Tests.** Add a happy-path and a scope-forbidden parity scenario for each new select/op in `current/src/test/kotlin/.../parity/` (`ParityCoverageGuardTest` fails without both), plus focused tests under `current/src/test/kotlin/.../interfaces/` mirroring the source layout. A new `ErrorCode` also needs its `ErrorDetailDocs` entry when it carries a detail.
6. **Budgets.** `ToolSurfaceGuardsTest` measures the compact `tools/list` entry of every tool in every profile against the ceilings in `ToolSurfaceChecks`, and requires a non-blank description on every property and the describe hint in every tool description. Raising a ceiling needs a recorded deviation, not a quiet edit.

### Tool Documentation Surfaces - Single-Source Policy

| Surface | Location | Audience |
|---------|----------|----------|
| `@Doc` on request-class fields | `application/api/<area>/` | Everything: decoding, the JSON Schema, `describe: true`, the goldens and the generated tables of `api-reference.md` |
| Tool `description`, select/op `doc` | The tool and select/op files | LLMs, via `tools/list` and `describe` |
| API reference prose | `current/docs/api-reference.md` | Humans: behavior, semantics, examples. The field tables and the error catalog between the `<!-- generated:... -->` markers are generated |

**Single source of truth per parameter:** each parameter is documented ONCE, in its own `@Doc`. The tool `description` and the select/op `doc` are reserved for what a flat schema cannot express: operation/select selection, trigger effects, gate semantics, and mutual-exclusion/XOR constraints across fields. This keeps the `tools/list` payload within its budgets.

**CI guards:** `ToolSurfaceGuardsTest` (documentation and budgets), `ToolSchemaGoldenTest` (the goldens match the registry in both directions, and name `regenerateToolGoldens` on a mismatch) and `ApiReferenceFreshnessTest` (every tool has one generated block, each block and the catalog equal their rendering, and a failure names `regenerateApiReference`).

**When changing a tool's parameters:**
- Add, rename or remove a field, or change required/optional: edit the request class and its `@Doc`, regenerate both artifacts, and touch the prose `description`/`doc` only if the change affects selection, trigger or gate semantics.
- Change an op's profile, capability or `errors`: regenerate both artifacts (they render in the generated block).
- Behavior prose in `api-reference.md` changes by hand, next to the block it explains.
- Do NOT reintroduce per-field prose in a `description` or `doc` that restates a `@Doc`.

### New Database Migration
Create `current/src/main/resources/db/migration/sqlite/V{N}__{Description}.sql`. SQLite has no `ALTER COLUMN` — schema changes require table recreation (see the migration-review skill's table-recreation template). The migration is the only schema definition: Direct mode no longer exists.
Every migration also needs one new file `current/src/test/kotlin/.../infrastructure/sqlite/upgrade/seeds/SeedV{N}.kt` (`object SeedV{N} : MigrationSeed({N})`); `UpgradeHarnessTest` fails when a migration of version 18 or later has none. Add the rows the migration transforms in `seed`, declare the exact post-migration values of any column it rewrites in `expected(table, row)` (verified exactly, applied only to steps whose chain includes your migration; there is no column-wide exclusion), and assert the transformation in `verify`. Give any new column a seeded value in `BaselineDataset` (`BaselineColumnCoverageTest` fails otherwise). After an intentional schema change, edit only the per-table files you changed under `current/src/test/resources/schema/snapshot/<table>.txt` (a new table adds its own file); `SchemaParityTest` writes the actual files to `current/build/schema-snapshot.actual/` on a mismatch.

### New Gradle Dependency
Add to `gradle/libs.versions.toml` (`[versions]` + `[libraries]`), then reference as `libs.{name}` in `build.gradle.kts`. Check Maven Central for the latest version.
