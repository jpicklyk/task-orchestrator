---
name: repo-architecture
description: This codebase's architecture guard rules and their tests, config traps, test conventions and the design records that judge session-layer changes, for the implementer and reviewer seats. Read it before changing Kotlin source, migrations, tool parameters, config handling, or director, orchestrator and run-wave behaviour.
user-invocable: false
---

# Repository Architecture

The guard rules and config traps of this codebase, stated once here. Server paths below are
relative to the source root `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/` unless
they start at the repo root. For a change that adds a tool, an op, a migration or a dependency, the
component checklists are in `.claude/skills/add-component/SKILL.md`.

## Where things live

- Migrations: `current/src/main/resources/db/migration/sqlite/` (the only Flyway location).
- Environment variables: read only in `platform/settings/AppConfig.kt`; catalogue and defaults in
  `current/docs/fleet-deployment.md`.
- Schema and trait resolution: `current/docs/config-reference.md` and the config slice `config/`
  (`EffectiveConfigResolver`), with `LayeredConfig` and `TraitResolver` under `config/internal/app/`.
  The trait dimensions, and the rule that a per-root trait entry replaces every dimension of the
  server's trait of the same name:
  `claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md`.

## Rules a guard test enforces

Tests are under `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/`. Of the ratchet baselines under `current/src/test/resources/architecture/`, the layering, package-cycle and dialect-neutrality ones are empty, so any violation of those rules fails; in every baseline a line that no longer matches fails too. Fix the code; never add a baseline line.

- **Layering** (`architecture/LayeringTest`, rules R1-R5 in `LayeringRules.kt`): `domain` and `model` (the shared model) -> `application` -> `infrastructure` -> `interfaces`, an outer layer may depend on an inner one, never the reverse. `infrastructure -> application` is allowed (adapters implement application ports). A `platform/` folder counts as the layer it came from (the table in `LayeringRules.kt`), and a `platform/` folder missing from that table is a violation. A slice is a first folder that is not a legacy layer or shared area (the reserved list is `RESERVED_FIRST_SEGMENTS` in `architecture/GuardSupport.kt`): `<s>/F.kt` is its top package (what another slice or the platform names), `<s>/internal/{domain,app,sqlite,wire}/` count as `domain`, `application`, `infrastructure` and `interfaces`, and any other path under `<s>/` is a violation (slice shape). `domain` and `application` must not import `io.modelcontextprotocol` or `io.ktor`. No layer may import `bootstrap`.
- **Application package cycles** (`architecture/PackageCycleTest`): the `application/<sub>` packages are acyclic; slices are out of its scope (`SliceGraphReportTest` only reports slice cycles, report-only; the step-9 rule S2 will enforce them). To share a type, move it down to a package every consumer already sits above.
- **Dialect neutrality** (`architecture/DialectNeutralityTest`): `org.sqlite.*` and `org.jetbrains.exposed.*`, and SQLite tokens such as `PRAGMA` or `datetime(` in string literals, appear only under `infrastructure/sqlite/`, `platform/sqlite/` and a slice's `<s>/internal/sqlite/`. Storage ports (`*Store`) live in `application/port/`, except `EventStore` and `IdempotencyStore`, which live in `platform/port/`, and a slice's own ports, which may sit in its top package or `internal/app/`.
- **Environment access** (`architecture/EnvAccessTest`): `System.getenv` only in `AppConfig.kt`, `EnvBoolean.kt` and `LogFileAppenderInstaller.kt`; everything else receives a typed value from the composition root; a test never calls `AppConfig.fromEnv()` without a resolver (pass `{ null }` or a map lookup).
- **Flyway is the only schema path** (`architecture/NoSchemaUtilsInProductionTest`): a new table or column starts with a migration. Released migration files are byte-immutable (Flyway checksums every line, comments included). The Exposed table objects must match the DDL and the compiler does not check it: `SchemaParityTest` and the snapshots in `current/src/test/resources/schema/snapshot/` do, so a schema change edits the migration, the Exposed object and the snapshot together.
- **Tool parameters are documented once**, in the `@Doc` of the request-class field; a tool `description` or select/op `doc` covers only operation choice, triggers, gates and cross-field constraints. After a `@Doc` edit run and commit both `./gradlew :current:regenerateToolGoldens` and `./gradlew :current:regenerateApiReference` (`ToolSchemaGoldenTest`, `ApiReferenceFreshnessTest`, `ToolSurfaceGuardsTest` check it). Checklists for a new tool, op, migration or dependency: `.claude/skills/add-component/SKILL.md`.
- **Domain boundary** (`claude-plugins/task-orchestrator/hooks/tests/contract-domain-boundary.test.mjs`): plugin text never names a host skill, a project skill or a project command, and never says "this repo"; what it scans, its allowlist and its baseline are in `claude-plugins/CLAUDE.md` under "Domain boundary for plugin text". Fix the text; never add a baseline row.
- **Test functions** need a block body or an explicit `: Unit`; JUnit 5 silently skips a non-void test (`architecture/TestFunctionReturnTypeTest`).

## Config traps

- **Runtime config is DB-only.** `.taskorchestrator/config.yaml` under `AGENT_CONFIG_DIR` (default: the working directory) is imported into the DB at every boot by the `server-config-import` data step; the server then reads the stored head per call, so editing the file does nothing until a restart, and a version appended by another process on a shared volume is served without one.
- **Two configs, two delivery paths.** The global floor mounted into the container is `deploy/global-config/.taskorchestrator/` (process schemas shared by every project). The repository's own `.taskorchestrator/config.yaml` is dogfood project config, pushed per root by the plugin's `config-sync` SessionStart hook; it is never mounted.
- **Boolean env vars** go through `EnvBoolean`: most fall back to the default with a WARN on an unrecognized value, but `API_ENABLED`, `API_ALLOW_UNAUTHENTICATED`, `MCP_ALLOW_UNAUTHENTICATED` and `API_JWKS_ALLOW_INSECURE_URL` fail startup. `RESOURCE_LEASES_ENFORCED` is read once at startup; changing it needs a restart.
- **Single construction sites.** Production constructs `ToolExecutionContext` once, in `ServerComposition.build()`, and `AdvanceService` once, in `AdvanceServiceFactory.forItem()`. A new constructor parameter given a default value compiles without being passed there, so wire it at that site by hand.

## Tests

Kotlin tests use JUnit 5, MockK and a real file-backed SQLite fixture (`SqliteTestDatabase`).

## Session layers

A change to director, orchestrator or run-wave behaviour is judged against decisions
[0001](../../../design/decisions/0001-two-session-layers.md),
[0002](../../../design/decisions/0002-director-on-orchestrator-tools.md),
[0003](../../../design/decisions/0003-role-neutral-decisions-surface.md) and
[0014](../../../design/decisions/0014-director-reads-skills-runs-plugin-scripts.md); terms are in
`GLOSSARY.md`. Key files (plugin paths under `claude-plugins/task-orchestrator/`):
`skills/director/SKILL.md` and `hooks/director-guard.mjs` (director mode);
`skills/director/references/coordinator-tiers.md` and `scripts/coordinator-tiers.mjs` (orchestrator
placement); `skills/run-wave/SKILL.md`; `hooks/decision-guard.mjs` (a subagent never answers,
acknowledges, pauses, resumes or reissues); `scripts/lib/meters.mjs` and
`skills/session-retrospective/SKILL.md` (cost); server side
`run/internal/app/RunPermissions.kt` and `platform/tool/ToolProfile.kt`; user-facing
chapter 12 of `current/docs/workflow-guide.md`.
