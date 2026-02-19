# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
