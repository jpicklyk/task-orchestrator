# PM Workflow Pack: Repo-Local Authority Verdict

## Hard Verdict

`Implemented behavior:` in the active/current module, this repository is authoritative for its own persisted workflow graph:
- `WorkItem`
- `Note`
- `Dependency`
- `RoleTransition`

Those objects are persisted in the active/current SQLite schema and are wired into the current runtime through the repository provider and MCP server. `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:4-77`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/DefaultRepositoryProvider.kt:18-31`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:76-110`

## What This Repo Is Authoritative For

`Implemented behavior:` the repo owns the active/current role-based workflow model itself: canonical roles, trigger resolution, dependency validation, role-transition audit writes, and note-schema gate enforcement when a schema matches an item’s tags. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/Role.kt:3-31`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:53-63,81-188,199-273,306-370`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/AdvanceItemTool.kt:121-265`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/NoteSchemaService.kt:5-13`

`Implemented behavior:` the repo also owns the advisory workflow computations derived from that graph: next-item recommendation, next-status recommendation, blocked-item detection, stalled-item detection, cascade detection, and unblock detection. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetNextItemTool.kt:10-18,77-145,174-225`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetNextStatusTool.kt:11-19,60-141`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetBlockedItemsTool.kt:14-25,101-220`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetContextTool.kt:202-345,352-400`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/CascadeDetector.kt:29-43,168-240`

`Implemented behavior:` the repo owns its own SQLite-backed operational record for validated workflow transitions through `role_transitions`. `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:63-77`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:341-355`

## What This Repo Appears Designed to Enforce

`Documented intent:` root docs and the active/current code agree that the repo is meant to enforce a v3/current `WorkItem` workflow with note-schema gates, dependency-aware progression, and MCP-first integration. `CLAUDE.md:33-36,61-66`, `current/docs/Home.md:5`, `current/docs/workflow-guide.md:75-92`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:206-220`

`Implemented behavior:` that design intent is materially reflected in the active/current runtime, not only in prose, because the server registers the workflow tools and the legality logic exists in executable code plus tests. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:87-110`, `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandlerTest.kt:49-110,245-310,385-456`, `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/SchemaGatedLifecycleTest.kt:18-22,234-318`

## What It Computes

`Implemented behavior:` the repo computes:
- trigger-to-role resolution and legality outcomes
- dependency blocker satisfaction
- note-gate satisfaction
- next-item and next-status recommendations
- blocked-item lists
- stalled-item lists
- cascade events and unblock events
- session-resume and health snapshots

Evidence: `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:81-188,199-273`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/AdvanceItemTool.kt:157-257,274-387`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetNextItemTool.kt:86-145`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetBlockedItemsTool.kt:109-220`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetContextTool.kt:202-345,352-400`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/CascadeDetector.kt:29-43,168-240`

## What It Stores

`Implemented behavior:` the active/current module stores only four first-class operational record types in its own schema:
- work items
- notes
- dependencies
- role transitions

Evidence: `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:4-77`, `current/src/main/resources/db/migration/V2__Work_Item_Field_Updates.sql:4-53`

`Implemented behavior:` the database is a local SQLite file by default (`data/current-tasks.db`) unless `DATABASE_PATH` overrides it. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/DatabaseConfig.kt:7-22`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/DatabaseManager.kt:30-84`

## What It Does Not Own Based on Repo-Local Evidence

`Absent/no evidence found:` no dedicated decision-tracking subsystem was found in the active/current scan scope. Search: `rg -n --ignore-case 'decision|decisions' current/src/main/kotlin current/src/main/resources current/src/test/kotlin` returned only incidental references, not a decision-tracking component. See `99-evidence-index.md`, negative search `N4`.

`Absent/no evidence found:` no dedicated progress-record subsystem was found in the same scope. Search: `rg -n --ignore-case 'decision_id|decision table|progress table|progress record|decision record|decision repository|progress repository' current/src/main/kotlin current/src/main/resources current/src/test/kotlin` returned no matches. See `99-evidence-index.md`, negative search `N1`.

`Absent/no evidence found:` no comment system, general chronicle/event store, or standalone timeline subsystem was found in the active/current scope. Searches `N2`, `N3`, and `N5` in `99-evidence-index.md` cover those negative results.

`Inferred capability:` the repo does not own a single unambiguous workflow surface across all repo-local runtime selectors, because `clockwork` remains present and some default container selectors still point at it. `settings.gradle.kts:6-10`, `docker-compose.yml:2-24,36-102`, `scripts/docker-build.sh:57-68`, `clockwork/DEPRECATED.md:3-5`

## What External Integrators Must Not Bypass

`Implemented behavior:` if an integrator wants the repo’s legality model, it must not bypass `advance_item` / `complete_tree` by mutating `WorkItem.role` or `statusLabel` through `manage_items` or lower-level repository writes. The validated path is the only path that combines dependency validation, note gates, `previousRole` handling, and transition-audit recording. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/AdvanceItemTool.kt:121-265`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/compound/CompleteTreeTool.kt:160-340`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt:361-516`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:294-370`

`Implemented behavior:` if an integrator depends on note gates, it must also supply the runtime with a `.taskorchestrator/config.yaml` that matches the intended workflow. Without that config, the repo falls back to schema-free mode and note gating disappears. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/NoteSchemaService.kt:11-13`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/config/YamlNoteSchemaService.kt:50-53,83-89`

## Final Repo-Local Verdict

`Inferred capability:` this repository is best described as a workflow engine plus workflow-state store for its own entities in the active/current module, with advisory read models layered on top. It enforces legality only on the validated transition path, not on every writer present in the repo. It stores workflow state and transition audit data, but it does not implement dedicated decision tracking, progress records, comments, or a general chronicle subsystem in the scanned scope. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:87-110`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:53-63,199-273,306-370`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt:361-516`, `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:4-77`
