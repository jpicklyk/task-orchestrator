# MCP Task Orchestrator

> Maintenance rule: this file holds only what scanning the code will not tell you quickly - traps, guard rules and their tests, where things live, and the session layers. No defaults, counts, version numbers, history or detail a named file already states: point at that file instead.

A Kotlin MCP server for hierarchical work items with dependencies, note schemas and gated phase transitions.

## Where things live

- Source root: `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/` (paths below are relative to it unless they start at the repo root). Entry point `CurrentMain.kt`; composition root `bootstrap/` (`ServerComposition.kt` wires everything).
- Migrations: `current/src/main/resources/db/migration/sqlite/` (the only Flyway location).
- Environment variables: read only in `infrastructure/config/AppConfig.kt`; catalogue and defaults in [current/docs/fleet-deployment.md](current/docs/fleet-deployment.md). Schema and trait resolution: [current/docs/config-reference.md](current/docs/config-reference.md) and `application/config/` (`EffectiveConfigResolver`, `LayeredConfig`, `TraitResolver`). The trait dimensions, and the rule that a per-root trait entry replaces every dimension of the server's trait of the same name: [claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md](claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md).
- Two skill systems, do not confuse them: project skills in `.claude/skills/` (auto-discovered) and plugin skills in `claude-plugins/task-orchestrator/skills/` (enabled through `enabledPlugins` in `.claude/settings.json`). Edits to plugin files are not picked up by a plain marketplace update; the cache refresh procedure is in [claude-plugins/CLAUDE.md](claude-plugins/CLAUDE.md).

## Rules a guard test enforces

Tests are under `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/`. Of the ratchet baselines under `current/src/test/resources/architecture/`, the layering, package-cycle and dialect-neutrality ones are empty, so any violation of those rules fails; in every baseline a line that no longer matches fails too. Fix the code; never add a baseline line.

- **Layering** (`architecture/LayeringTest`, rules R1-R5 in `LayeringRules.kt`): `domain -> application -> infrastructure -> interfaces`, an outer layer may depend on an inner one, never the reverse. `infrastructure -> application` is allowed (adapters implement application ports). `domain` and `application` must not import `io.modelcontextprotocol` or `io.ktor`. No layer may import `bootstrap`.
- **Application package cycles** (`architecture/PackageCycleTest`): the `application/<sub>` packages are acyclic. To share a type, move it down to a package every consumer already sits above.
- **Dialect neutrality** (`architecture/DialectNeutralityTest`): `org.sqlite.*` and `org.jetbrains.exposed.*`, and SQLite tokens such as `PRAGMA` or `datetime(` in string literals, appear only under `infrastructure/sqlite/`. Storage ports (`*Store`) live in `application/port/`.
- **Environment access** (`architecture/EnvAccessTest`): `System.getenv` only in `AppConfig.kt`, `EnvBoolean.kt` and `LogFileAppenderInstaller.kt`; everything else receives a typed value from the composition root.
- **Flyway is the only schema path** (`architecture/NoSchemaUtilsInProductionTest`): a new table or column starts with a migration. Released migration files are byte-immutable (Flyway checksums every line, comments included). The Exposed table objects must match the DDL and the compiler does not check it: `SchemaParityTest` and the snapshots in `current/src/test/resources/schema/snapshot/` do, so a schema change edits the migration, the Exposed object and the snapshot together.
- **Tool parameters are documented once**, in the `@Doc` of the request-class field; a tool `description` or select/op `doc` covers only operation choice, triggers, gates and cross-field constraints. After a `@Doc` edit run and commit both `./gradlew :current:regenerateToolGoldens` and `./gradlew :current:regenerateApiReference` (`ToolSchemaGoldenTest`, `ApiReferenceFreshnessTest`, `ToolSurfaceGuardsTest` check it). Checklists for a new tool, op, migration or dependency: `.claude/skills/add-component/SKILL.md`.
- **Test functions** need a block body or an explicit `: Unit`; JUnit 5 silently skips a non-void test (`architecture/TestFunctionReturnTypeTest`).

## Config traps

- **Runtime config is DB-only.** `.taskorchestrator/config.yaml` under `AGENT_CONFIG_DIR` (default: the working directory) is imported into the DB at every boot by the `server-config-import` data step; the server then reads the stored head per call, so editing the file does nothing until a restart, and a version appended by another process on a shared volume is served without one.
- **Two configs, two delivery paths.** The global floor mounted into the container is `deploy/global-config/.taskorchestrator/` (process schemas shared by every project). This repo's own `.taskorchestrator/config.yaml` is dogfood project config, pushed per root by the plugin's `config-sync` SessionStart hook; it is never mounted.
- **Boolean env vars** go through `EnvBoolean`: most fall back to the default with a WARN on an unrecognized value, but `API_ENABLED`, `API_ALLOW_UNAUTHENTICATED`, `MCP_ALLOW_UNAUTHENTICATED` and `API_JWKS_ALLOW_INSECURE_URL` fail startup. `RESOURCE_LEASES_ENFORCED` is read once at startup; changing it needs a restart.
- **Single construction sites.** Production constructs `ToolExecutionContext` once, in `ServerComposition.build()`, and `AdvanceService` once, in `AdvanceServiceFactory.forItem()`. A new constructor parameter given a default value compiles without being passed there, so wire it at that site by hand.

## Session Layers: Orchestrator, Coordinator, Director

Three session layers sit above the seats that do the work. They are roles a Claude Code session plays, not server modes (orchestration push via `advance` `transition`, opt-in claim via `advance` `claim`).

**Why.** One session used to be both the user's contact and the coordinator of every run: its context filled, it was unavailable while a run was in flight, and held state was fragile across compaction. The layers split those jobs so each context is purposeful and clearable, token cost stays proportional to the work, and the server (not a prompt) holds state and boundaries.

| Layer | What it is | Owns | Lifetime |
|-------|------------|------|----------|
| Seat (the base) | One agent on one phase of one item: planner, implementer, test author, reviewer | Its seat's notes; the entry seat also makes the one phase entry | One dispatch, fresh context |
| **Orchestrator** (default) | The user's own session | Planning, sizing (Direct, Delegated, Parallel), dispatching seats, every later transition; when it opens a run it is also that run's coordinator | The session |
| **Coordinator** | Whoever presents a run's coordinator token | One run (one wave): assign seats, advance members, post-run, report | One run, disposable; resumes from the run view and its run files, never from conversation |
| **Director** (mode opt-in; init registers the entry by default) | A session in director mode (`/task-orchestrator:director`) | The board across runs, answering decisions, staying free to talk to the user | The session; rebuilds its board from the server |

**Why a director:** (1) a lean, long-lived context; (2) one decision inbox across runs; (3) the user-facing session stays free while work runs in the background; (4) more parallelism and longer-running work than one session can hold. Small work or one short wave does not need it; orchestrator mode is enough, and a director adds a layer and its cost.

**Rules across the layers** (judge any change to director, coordinator or run-wave behaviour against these):

- **Altitude.** The director never opens a file or reads a diff, test output or note body, except one decision request and a run rollup; it hands every read and edit to a coordinator or background agent. The `director` tool profile enforces this on the server (it has no read that returns a body), the `director-guard` hook in the session.
- **Content flows down, pointers flow up.** A layer hands the one above ids, counts and short reasons, never note bodies, diffs or transcripts. Notes are the report.
- **Server records are the state; messages are wake-ups.** Every layer restarts from server state (runs, seats, decisions, notes) plus its run files; nothing needed to resume lives only in a conversation.
- **Decisions go up, answers come down.** A seat or coordinator raises; a director or human answers, as the decision class allows; a coordinator never answers one itself, and a PreToolUse hook denies a subagent's `runs_write` `answer`, `ack`, `pause` or `resume`. An answer never resumes the item: the coordinator does.
- **Identity is declared.** The director is a call served by the director profile with no seat token; the coordinator holds the run's coordinator token (`RunPermissions`). The director path is not a security boundary (any credential with the capability may call it): put decision authority in a credential's capabilities and scope.
- **Cost is measured, not self-reported.** Model and effort come from `dispatch-policy.json` per class of work, and every dispatch names both. Token cost is read from Claude Code transcripts after a run into `delegation-metadata`; the session retrospective analyses it and reports the share of the session's token volume (by meter and model) that notes record; the tool holds no prices.

**Vocabulary.** "Director" names both the server profile (`/mcp/director`) and the session mode: say which. The seat named `orchestrator` is the one whose note keys a run's coordinator token may write. The layers are not the sizing tiers (Direct, Delegated, Parallel) nor the coordinator tiers 1 to 4 (where a coordinator runs).

Key files (plugin paths under `claude-plugins/task-orchestrator/`): `skills/director/SKILL.md` and `hooks/director-guard.mjs` (director mode); `skills/director/references/coordinator-tiers.md` and `scripts/coordinator-tiers.mjs` (coordinator tiers); `skills/run-wave/SKILL.md`; `scripts/lib/meters.mjs` and `skills/session-retrospective/SKILL.md` (cost); server side `interfaces/shared/tools/runs/RunPermissions.kt` and `interfaces/shared/tools/ToolProfile.kt`; user-facing chapter 12 of [current/docs/workflow-guide.md](current/docs/workflow-guide.md).

## Build and run

```bash
docker build -t task-orchestrator:dev .
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v "$(pwd)"/deploy/global-config/.taskorchestrator:/project/.taskorchestrator:ro \
  -e AGENT_CONFIG_DIR=/project \
  task-orchestrator:dev
```

Never pipe `./gradlew` output to `tail`; run it directly and read the full output. Tests use JUnit 5, MockK and a real file-backed SQLite fixture (`SqliteTestDatabase`).

## Git workflow

- The PR boundary is the parent feature: the children of a Parallel-tier feature commit to one shared `feat/<slug>` worktree branch and one PR opens when the parent reaches terminal; a Direct or Delegated item gets its own branch and PR. Procedure: the `implement` skill (`.claude/skills/implement/SKILL.md`, then `wave.md`, `direct.md` or `delegated.md`).
- Conventional commits referencing the issue; all tests pass before a commit; squash-merge via PR; never force-push `main`. After a merge: `git checkout main && git pull origin main && git branch -D <branch>`.
