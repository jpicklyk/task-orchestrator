# PM Workflow Pack: Audit, History, and Chronicle

## Transition Audit Trail

`Implemented behavior:` validated workflow transitions are persisted in `role_transitions`, which stores `fromRole`, `toRole`, optional status labels, the triggering action, an optional summary, and `transitioned_at`. `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:63-77`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/RoleTransition.kt:6-23`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/RoleTransitionsTable.kt:7-21`

`Implemented behavior:` the validated transition path writes an audit entry every time `applyTransition` succeeds. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:294-355`

`Implemented behavior:` tests confirm that applying a transition updates the item and creates a corresponding role-transition audit record. `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandlerTest.kt:385-413`

`Implemented behavior:` the active/current repository exposes internal reads over this audit trail through `findByItemId`, `findByTimeRange`, and `findSince`. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/repository/RoleTransitionRepository.kt:7-12`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteRoleTransitionRepository.kt:46-57,59-98`

## Exposed History Surfaces

`Implemented behavior:` `get_context(session-resume)` exposes recent transitions since a supplied timestamp alongside active and stalled items. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetContextTool.kt:202-275`

`Implemented behavior:` `get_context(health-check)` exposes active, blocked, and stalled read models, but it does not emit a full transition log. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetContextTool.kt:282-345`

`Implemented behavior:` tests cover the session-resume and health-check response shapes. `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetContextToolTest.kt:207-245`

`Absent/no evidence found:` the active/current MCP tool surface does not include a dedicated `query_role_transitions` tool. The registered current tool list is fixed at 13 tools and does not name that surface, and an explicit search in the current module found no `RequestTransitionTool`, `QueryRoleTransitionsTool`, `request_transition`, or `query_role_transitions` symbols. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:87-110,220`, search `rg -n 'RequestTransitionTool|QueryRoleTransitionsTool|request_transition|query_role_transitions' current/src/main/kotlin current/src/test/kotlin` returned no matches; see `99-evidence-index.md`, negative search `N6`.

## Notes as Supporting Records

`Implemented behavior:` notes carry their own `createdAt` and `modifiedAt` timestamps and are persisted independently from role transitions. They are operational records for gate satisfaction and context, not merely ephemeral annotations. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/Note.kt:13-20`, `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:33-46`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteNoteRepository.kt:39-74,94-143`

`Implemented behavior:` `get_context(item)` reads notes and reports whether schema-defined notes exist and are filled; `findStalledItems` derives stalled status from missing required notes in the current phase. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetContextTool.kt:120-192,352-400`

`Inferred capability:` notes are part of the operational workflow record because they can block advancement, but they are not a full chronology of all actions. The note model records current content and timestamps, not an append-only sequence of note edits. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/Note.kt:7-20`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteNoteRepository.kt:39-74`

## Chronicle / Event-Store Assessment

`Absent/no evidence found:` no general event store, timeline subsystem, chronicle store, or journal-like history service was found in the active/current scan scope (`current/src/main/kotlin`, `current/src/main/resources`, `current/src/test/kotlin`). Search: `rg -n --ignore-case 'event store|event_log|event log|timeline|chronicle|journal' current/src/main/kotlin current/src/main/resources current/src/test/kotlin` returned only SQLite `journal_mode=WAL` comments in `DatabaseManager.kt`. See `99-evidence-index.md`, negative search `N3`.

`Implemented behavior:` the only repo-local `journal` evidence in current is SQLite WAL configuration for database concurrency, not an application-level history model. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/DatabaseManager.kt:64-78`

`Absent/no evidence found:` no separate comment subsystem was found, so there is no evidence of a comment timeline or comment-driven audit trail beyond notes and role transitions. Search: `rg -n --ignore-case '\\bcomment\\b|comments' current/src/main/kotlin current/src/main/resources current/src/test/kotlin` returned no matches. See `99-evidence-index.md`, negative search `N5`.

## Repo-Local Assessment

`Inferred capability:` active/current maintains a bounded workflow history model:
- canonical operational transition history in `role_transitions`
- timestamped supporting workflow notes in `notes`
- derived session/health read models in `get_context`

It does not implement a general-purpose chronicle or event-sourcing system in the scanned scope. `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:33-77`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetContextTool.kt:202-345`
