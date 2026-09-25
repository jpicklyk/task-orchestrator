---
name: add-component
description: Checklists for adding a new MCP tool, a new Flyway database migration, or a new Gradle dependency to the Task Orchestrator server, including the tool-documentation single-source policy (parameterSchema field descriptions vs the prose description string vs api-reference.md). Use when adding or changing an MCP tool or its parameters, creating a migration, or adding a dependency to the version catalog.
---

# Adding New Components

Moved from the root `CLAUDE.md` so it loads only when needed. The single-source documentation policy summary stays in `CLAUDE.md`; this file is the full procedure.

### New MCP Tool
1. Extend `BaseToolDefinition` in `current/src/main/kotlin/.../application/tools/`
2. Register in `buildMcpTools()` (`CurrentMcpServer.kt`) — `ToolDocumentationConsistencyTest` and
   `ToolTokenBudgetTest` derive their tool list from this function, so registering here is
   sufficient for both guard tests to pick up the new tool automatically
3. Update all three documentation surfaces (see below)
4. Add a `ToolTokenBudgetTest` per-tool ceiling row for the new tool (see that test's "BUDGET
   PHILOSOPHY" doc comment for how to measure and set it) — its sync check fails loudly if a row
   is missing
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
