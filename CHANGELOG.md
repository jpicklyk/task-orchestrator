# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.2.0] - 2026-04-15 (Plugin v3.1.0)

### Added
- Added actor attribution to `advance_item` and `manage_notes` -- optional `actor` object tracks who made each transition and note update, persisted via new database columns (V4 migration)
- Added config-driven auditing enforcement -- when `auditing.enabled: true` in config.yaml, the PreToolUse hook blocks `advance_item` calls that lack actor claims
- Added JWKS-based actor verification as a public beta (`auditing.verifier.type: jwks`) -- validates JWT bearer tokens against JWKS key sets with support for OIDC discovery, direct URI, local file sources, algorithm allowlists, and configurable claim validation
- Added MCP registry metadata and Smithery configuration for marketplace discovery
- Bumped plugin version to 3.1.0 -- new `enforce-actor-attribution` hook and updated auditing docs

### Fixed
- Patched dependency CVEs and updated all dependencies to current stable versions

---

## [3.1.0] - 2026-04-13 (Plugin v3.0.1)

### Added
- Added short hex prefix resolution (4+ chars) to all WorkItem ID parameters — use `a1b2` instead of full UUIDs across all 13 tools, including `manage_items` update, delete, and per-item `parentId`

### Fixed
- Fixed `query_items(operation="overview")` scoped view to include `childCounts` and `traits` on child items
- Fixed contradictory agent-owned-phase documentation across plugin skills, output styles, and integration guides — aligned to single-phase agent model
- Bumped plugin version to 3.0.1 — content fixes to skills and output style

---

## [3.0.0] - 2026-04-06 (Plugin v3.0.0)

### Breaking Changes
- Changed schema matching from tag-based to type-based — items now use a dedicated `type` field for schema activation; tags remain for categorization only
- Evolved config format from `note_schemas:` to `work_item_schemas:` with lifecycle modes and trait support (legacy format still parsed for backward compat)

### Added
- Added `type` and `properties` fields to WorkItem — `type` drives schema resolution, `properties` stores extensible JSON including traits
- Added `LifecycleMode` enum (AUTO, MANUAL, AUTO_REOPEN, PERMANENT) — controls cascade behavior per work item type
- Added composable traits — agents assign traits per-item that merge additional note requirements into the base schema at gate-check time
- Added `skill` field on note schema entries — surfaces as `skillPointer` in `get_context` and `advance_item` responses for deterministic skill routing
- Added `skill-enforcement` PreToolUse hook on `manage_notes` — detects shallow notes on skill-required entries and injects skill-invocation directive
- Added 3 trait-specific review skills: `migration-review`, `plugin-impact-review`, `perf-review`
- Added `traits` as shared default parameter on `manage_items(create)` — applies to all items when per-item traits not specified
- Enriched `query_items(overview)` response — root items and children now include `tags`, `type`, `childCounts`, and `traits`

### Changed
- Reduced `/work-summary` skill from 4 to 3 MCP calls by leveraging enriched overview response
- Updated `subagent-start` hook with deterministic `skillPointer` instruction
- Updated `post-plan-workflow` skill with skill-aware delegation instructions
- Bumped plugin version to 3.0.0 — aligned with server major version

---

## [2.5.2] - 2026-04-03 (Plugin v2.7.3)

### Fixed
- Fixed `manage_items` create response to always include `expectedNotes` and `schemaMatch` fields — agents no longer need a separate `get_context` call after item creation

### Improved
- Unified schema-to-JSON serialization across `manage_items`, `create_work_tree`, `advance_item`, and `get_context` via shared `SchemaEntryJsonBuilder`
- Added structured warning collection to schema config loading — config issues are surfaced programmatically via `getLoadWarnings()` instead of silently logged
- Deduplicated WorkTreeService insert/upsert logic with shared repository helpers
- Expanded test coverage across 12 identified gaps — WorkTreeService rollback, cascade detection, schema loading edge cases, note preservation, and gate lifecycle
- Bumped plugin version to 2.7.3 — added `container` schema example to manage-schemas skill

---

## [2.5.1] - 2026-03-31 (Plugin v2.7.2)

### Fixed
- Fixed `IS_BLOCKED_BY` dependencies not being enforced by `advance_item` — items could advance past IS_BLOCKED_BY gates that `get_blocked_items` and `get_next_item` correctly reported as blocked
- Fixed `advance_item` unblock detection not discovering items with IS_BLOCKED_BY edges when their blocker completes
- Fixed reversed dependency example in quick-start documentation

### Changed
- Improved manage-schemas skill templates to align with spec-quality and review-quality frameworks
- Added guidance generation rules and cross-schema duplication checks to manage-schemas edit workflow
- Bumped plugin version to 2.7.2

---

## [2.5.0] - 2026-03-30 (Plugin v2.7.1)

### Changed
- Strengthened planning hook language — pre-plan and post-plan hooks now use imperative gate framing (PREREQUISITE/MUST) instead of suggestive phrasing that was deprioritized by plan mode
- Enforced WHAT/HOW separation — output style trimmed to principle-level statements, procedural detail lives in skills
- Added explicit handoff sections to pre-plan and post-plan workflow skills for clean control flow between hook, skill, and plan mode
- Bumped plugin version to 2.7.1 (planning hook and output style refinements)

---

## [2.5.0] - 2026-03-25 (Plugin v2.7.0)

### Added
- Added tiered execution model (Direct/Delegated/Parallel) to the Workflow Orchestrator output style — classifies work by scope and applies proportional process
- Added Direct Tier Workflow section — orchestrator implements, tests, and reviews inline for 1-2 file known fixes
- Added force-UP/DOWN signals for tier classification (migration, new API, collaborative language, "just fix it")
- Added Direct tier safety net to pre-plan hook — warns if plan mode is entered for Direct tier work
- Added YAML frontmatter to workflow-orchestrator output style (name, description, keep-coding-instructions)

### Changed
- Changed Principle #1 from "Never implement directly" to "Delegate by default" with tier-conditional behavior
- Changed Principle #2 from "Plan before acting" to "Plan proportionally" with tier-conditional plan mode
- Trimmed workflow-orchestrator to universally valuable content — removed project-specific references, redundant templates, and niche operational details
- Bumped plugin version to 2.7.0 (tiered execution model)

---

## [2.5.0] - 2026-03-23

### Added
- Added `McpLoggingService` for MCP protocol-level logging — tool validation errors and internal exceptions now emit `notifications/message` to connected clients, improving visibility for MCP client UIs
- Added `logback.xml` to the `current` module routing all logs away from stdout — fixes MCP spec compliance violation where log output corrupted the JSON-RPC stream for stdio transport clients (Fixes #84)

### Fixed
- Fixed stdout pollution breaking stdio transport clients — Logback previously fell back to `BasicConfigurator` (DEBUG+ to stdout) because no `logback.xml` was bundled in the JAR
- Fixed internal documentation cross-references to use `.md` extensions for proper link resolution

---

## [2.4.1] - 2026-03-22

### Changed
- Removed archived clockwork (v2) module — simplifies Docker configuration and build scripts to a single runtime target
- Standardized phase transition ownership across plugin skills and output styles — implementation agents own queue→work and work→review; orchestrator owns review→terminal
- Fixed `complete` trigger documentation in README — correctly states it requires all notes across all phases (does not bypass gates)
- Added missing `reopen` trigger to README trigger table
- Fixed output style name reference from "Workflow Analyst" to "Workflow Orchestrator"
- Updated MCP SDK version reference from 0.8.4 to 0.9.0

### Added
- Added 7 progressive integration guide cookbooks covering bare MCP, CLAUDE.md-driven workflow, note schema gating, plugin skills and hooks, output styles, and self-improving workflow patterns
- Added `git reset --hard origin/main` to implement skill post-merge sync to prevent local main drift

### Plugin (2.6.1)
- Fixed contradictory phase transition rules in `schema-workflow`, `post-plan-workflow`, and `workflow-orchestrator` output style

---

## [2.4.0] - 2026-03-09

### Added
- Added config-driven status labels to `advance_item` — custom label names per role transition surfaced in responses and `query_items` results
- Added session tracking notes as a schema-enforced work-phase gate — agents fill context that `/session-retrospective` aggregates across the feature tree
- Added cascade gate enforcement to `advance_item` — parent items now verify child note gates before cascading to terminal
- Added `NoteSchemaEntry.role` type safety — role field changed from `String` to `Role` enum, eliminating stringly-typed comparisons across the gate layer
- Added ktlint enforcement with `.editorconfig` and CI integration

### Fixed
- Fixed short UUID prefix lookup failing in SQLite — `CAST(blob AS VARCHAR)` produced raw bytes instead of hex; now uses `HEX()`/`RAWTOHEX()` for correct cross-dialect behavior
- Fixed `RELATES_TO` edges incorrectly blocking cycle detection in dependency validation
- Fixed input validation hardening across multiple tools

### Changed
- Upgraded MCP SDK to 0.9.0 with Ktor Streamable HTTP transport
- Improved plugin worktree reliability — SubagentStart hook now injects commit, scope, and cd-discipline rules; orchestrator output style includes Worktree Dispatch checklist
- Bumped plugin version to 2.6.0 (new hook content, updated skills and output styles)

---

## [2.3.0] - 2026-03-07

### Added
- Added `reopen` trigger to `advance_item` — reopens terminal items back to queue, clears statusLabel on cancelled items
- Added short UUID prefix resolution to `query_items` get — resolve items by 4+ hex character prefix instead of full UUID
- Added `guidancePointer` and `noteProgress` to `advance_item` success response — shows next required note guidance and fill counts for the new role
- Added `itemContext` to `manage_notes` upsert response — returns `guidancePointer` and `noteProgress` per item, eliminating N-1 `get_context` round-trips
- Added role guard to `manage_items` update — rejects direct role changes with guidance to use `advance_item` triggers instead

### Changed
- Refined agent-owned-phase protocol in subagent-start hook — agents now enter their phase, iterate notes via JIT progression, and never double-advance
- Extracted shared `PhaseNoteContext` computation — unified gate-check logic across `manage_notes`, `get_context`, and stalled-item detection
- Bumped plugin version to 2.5.2 (agent-owned-phase protocol refinements)

---

## [2.5.1] - 2026-03-05 (plugin-only)

### Fixed
- Fixed subagent-start hook to enforce transition-before-guidance ordering — agents now call `advance_item(start)` before `get_context`, ensuring they receive work-phase `guidancePointer` instead of stale queue-phase guidance
- Bumped plugin version to 2.5.1

---

## [2.2.0] - 2026-03-05

### New Features
- Added `schema-workflow` skill to the plugin — guides items through schema-defined lifecycle with gate-enforced phase transitions
- Added shared tool helper utilities in `BaseToolDefinition` — reduces boilerplate across all MCP tool implementations

### Improvements
- Improved `complete_tree` with BFS-based traversal — handles deep hierarchies more efficiently and fixes edge cases with dependency ordering
- Improved `query_dependencies` response format — clearer structure for dependency chain visualization
- Overhauled `work-summary`, `batch-complete`, and `create-item` plugin skills — better prompts and more reliable behavior
- Restructured `workflow-orchestrator` output style — cleaner zone separation between shared core and extensions

### Performance
- Parallelized sequential `findByRole` queries in `get_context` — reduces latency when loading items across multiple workflow phases

### Changed
- Automated releases via tag push (`v*` triggers Docker + GitHub Release) — replaces manual `workflow_dispatch`
- Bumped plugin version to 2.5.0 (new skill, skill improvements)

---

## [2.1.0] - 2026-03-04

### Added
- Added `guidancePointer` and `expectedNotes` to `advance_item`, `create_work_tree`, `manage_items`, and `get_context` responses — agents see exactly which notes need filling and get human-readable guidance at each gate transition
- Added 6 new plugin skills: `batch-complete`, `dependency-manager`, `manage-schemas`, `quick-start`, `status-progression`, and `create-item` — covering bulk operations, dependency visualization, schema management, onboarding, and workflow navigation
- Added `pre-plan-workflow` and `post-plan-workflow` internal skills with automatic hook injection — plan mode now checks existing MCP state and materializes items after approval without manual steps

### Changed
- Renamed output style from `current-analyst` to `workflow-orchestrator` to better reflect its orchestration focus
- Overhauled plugin hooks (`pre-plan`, `post-plan`, `subagent-start`) for schema-aware context injection
- Improved quick-start and workflow guide documentation with end-to-end examples

### Fixed
- Fixed 20 skill quality issues across 9 skills (contradictions, jargon, missing guards, unclear guidance)

---

## [2.0.3] - 2026-02-19

### Added
- Added Claude Code plugin installation instructions to README and quick-start guide — covers skills, hooks, output style, and correct install commands
- Added automatic GitHub Wiki sync — documentation in `current/docs/` now auto-publishes to the GitHub Wiki on every merge to `main`
- Added `/prepare-release` skill for streamlined release preparation with changelog drafting and PR creation

### Changed
- Renamed Claude Code plugin from `current` to `task-orchestrator` — install via `/plugin marketplace add https://github.com/jpicklyk/task-orchestrator` then `/plugin install task-orchestrator@task-orchestrator-marketplace`
- Removed legacy `clockwork` plugin (superseded by `task-orchestrator`)
- Fixed quick-start guide Step 2 Option B to use `.mcp.json` (was incorrectly referencing `.claude/settings.json`); added Option C for other MCP clients; corrected note schemas Docker config block to show full config

---

## [2.0.2] - 2026-02-19

### Fixed
- Corrected Docker image tag references and version badge in README

### Documentation
- Restructured Quick Start with explicit `docker pull` step and simplified default config
- Scoped config mount documentation to `.taskorchestrator/` only
- Fixed all project mount path references across docs
- Trimmed README — removed padding sections, collapsed marketing copy

---

## [2.0.1] - 2026-02-19

### Fixed

- Fixed `manage_items(update)` now rejects self-referencing and circular `parentId` assignments, preventing hierarchy corruption
- Fixed `findAncestorChains` BFS detects and breaks parent cycles instead of looping infinitely
- Fixed `version.properties` included in Docker build context so container image versioning is accurate

### Added

- Added `/bump-version` Claude Code skill — automates release PR preparation with semver inference and changelog drafting

---

## [2.0.0] - 2026-02-18

### 🚨 BREAKING CHANGES

**Unified WorkItem Architecture**: Complete rewrite of the domain model. The Project → Feature → Task three-tier hierarchy is replaced by a single `WorkItem` entity with flexible depth (0–3) and a role-based workflow engine. All previous container tools (`manage_container`, `query_container`) are replaced by the new tool surface.

**Tool Surface**: 13 tools replace the prior architecture. No migration path — this is a clean break requiring a fresh database.

---

### Added

**Core WorkItem Model**
- Single `WorkItem` entity replaces Project/Feature/Task — hierarchical via `parentId` and `depth` (max 3)
- Role-based lifecycle: `queue → work → review → terminal` (roles are semantic, statuses are named per-workflow)
- Note schema system: YAML-configured schemas gate role transitions — required notes must be filled before `advance_item` advances to the next phase
- `previousRole` tracking and `roleChangedAt` timestamps on all items

**13 MCP Tools**
- **`manage_items`** — create, update, delete (batch, with `recursive` subtree delete)
- **`query_items`** — get, search, overview; `includeAncestors` for breadcrumb chains; `includeChildren` for scoped hierarchy views; `role` filter for semantic phase queries
- **`manage_notes`** — upsert and delete notes; batch upsert via `notes` array
- **`query_notes`** — list notes per item; `includeBody=false` for metadata-only checks
- **`manage_dependencies`** — create (batch array or `linear`/`fan-out`/`fan-in` pattern shortcuts) and delete; consistent batch failure response shape
- **`query_dependencies`** — neighbor lookup or full BFS graph traversal (`neighborsOnly=false`) returning topologically-ordered chain and depth
- **`advance_item`** — trigger-based role transitions (`start`, `complete`, `block`, `hold`, `resume`, `cancel`) with gate enforcement, cascade detection, and batch support via `transitions` array
- **`get_next_status`** — read-only progression recommendation before transitioning
- **`get_context`** — three modes: item context (schema, gate status, `guidancePointer`), session resume (active + recent transitions since timestamp), health check (active/blocked/stalled)
- **`get_next_item`** — priority-ranked recommendation of next actionable queue item
- **`get_blocked_items`** — dependency blocking analysis with `blockType` (`explicit` vs `dependency`)
- **`create_work_tree`** — atomic single-call hierarchy creation (root + children + dependencies + notes)
- **`complete_tree`** — batch completion of descendants in topological dependency order with gate checking; `cancel` trigger bypasses gates

**Graph-Aware Queries**
- `includeAncestors=true` on `query_items`, `get_context`, `get_blocked_items`, `get_next_item` — embeds full ancestor chain on each item, eliminating sequential parent-walk calls
- 2-call work-summary pattern: `get_context(includeAncestors=true)` + `query_items(overview)` replaces multi-call traversal

**Transport**
- HTTP transport via Ktor Streamable HTTP (MCP spec 2025-03-26): `MCP_TRANSPORT=http`, endpoint `http://host:port/mcp`
- `MCP_HTTP_HOST` and `MCP_HTTP_PORT` environment variables
- Stdio transport remains default

**Infrastructure**
- SQLite WAL mode (`PRAGMA journal_mode=WAL`) for concurrent reads + write without full file locking
- `PRAGMA busy_timeout=5000` — 5-second retry window instead of immediate failure under write contention
- Fixed dangling JDBC Statement on `setupConnection` (prevented SQLITE_BUSY on parallel MCP calls)
- Connection pool via `DATABASE_MAX_CONNECTIONS` environment variable
- `AGENT_CONFIG_DIR` environment variable for Docker-compatible config resolution

**Claude Code Plugin (v1.0.14)**
- `work-summary` skill — insight-driven project dashboard with active/blocked/up-next sections
- `create-item` skill — container-anchored work item creation from conversation context; infers type, anchors to Bugs/Features/Tech Debt/Observations/Action Items containers, pre-fills required notes
- `status-progression` skill — interactive role transition guidance
- `schema-builder` skill — YAML note schema builder for new work item types
- `check_schema_version` and `deploy_to_docker` skills for local development workflow
- Workflow Analyst output style — orchestration mode with MCP efficiency analysis and observation logging
- Planning hooks (PreToolUse/PostToolUse on EnterPlanMode/ExitPlanMode) enforcing MCP container materialization before implementation

**Documentation**
- Complete v3 docs under `current/docs/`: quick-start, api-reference (all 13 tools), workflow-guide
- Full audit of all 13 tools against implementation — 42 discrepancies identified and resolved
- v2/Clockwork docs archived to `clockwork/docs/`

### Fixed

- `manage_dependencies(create)`: validation failures now return consistent batch response shape `{success: true, created: 0, failed: N, failures: [{index, error}]}` instead of top-level error response — matches `manage_items` and `manage_notes` patterns
- SQLite PRAGMA dangling statement: `journal_mode=WAL` returns a result row; wrapping all PRAGMAs in `Statement.use {}` prevents open prepared statement from blocking subsequent `setTransactionIsolation` calls

### Architecture Notes

- **Clean Architecture**: Domain → Application → Infrastructure → Interface layers; domain has no framework dependencies
- **v2/Clockwork archived**: Previous architecture preserved as `:clockwork` Gradle submodule in `clockwork/`; not built by default
- **Flyway migrations**: Production schema management; `DirectDatabaseSchemaManager` for development iteration
- **No sections or templates**: The v3 WorkItem model uses notes (key/value pairs with role phase) rather than rich sections; template system from v2 is not present in v3
