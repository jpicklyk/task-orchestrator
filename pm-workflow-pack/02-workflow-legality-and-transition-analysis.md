# PM Workflow Pack: Workflow Legality and Transition Analysis

## Repo-Local Verdict

`Implemented behavior:` the active/current module implements a real workflow legality layer. The legality model is explicit in code: resolve trigger to target role, validate the move against dependency gates, then apply the state change and persist an audit record. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:53-63,81-188,199-273,306-370`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/AdvanceItemTool.kt:121-265`

`Implemented behavior:` that legality layer is not the only writer. The same repository also exposes direct mutation paths that can change `role` and `statusLabel` without going through transition validation or transition-audit recording. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt:181-187,249-288,361-516`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteWorkItemRepository.kt:81-118`

## State Model and Transition Rules

`Implemented behavior:` the canonical active/current workflow roles are `QUEUE`, `WORK`, `REVIEW`, `BLOCKED`, and `TERMINAL`. The code treats roles as canonical state, treats statuses as optional display labels, defines sequential progression as `QUEUE -> WORK -> REVIEW -> TERMINAL`, and treats `BLOCKED` as orthogonal. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/Role.kt:3-31`

`Implemented behavior:` validated trigger handling supports `start`, `complete`, `block`, `hold`, `resume`, and `cancel`. `start` moves `QUEUE -> WORK`, `WORK -> REVIEW` or `WORK -> TERMINAL` depending on schema-driven review presence, and `REVIEW -> TERMINAL`; `complete` jumps to `TERMINAL`; `block`/`hold` move to `BLOCKED`; `resume` restores `previousRole`; `cancel` moves to `TERMINAL` with status `"cancelled"`. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:66-68,81-119,121-184`

`Implemented behavior:` targeted tests exercise the role-resolution logic and confirm that terminal and blocked items reject invalid forward moves. `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandlerTest.kt:49-110`

## Dependency-Based Legality

`Implemented behavior:` forward progressions are validated against incoming blocking dependencies. For each dependency, the handler resolves the effective unblock threshold, loads the blocker item, and rejects the transition if the blocker has not reached the required role. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:190-273`

`Implemented behavior:` transitions to `BLOCKED` bypass dependency checks, and items already in `TERMINAL` cannot transition further. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:205-216`

`Implemented behavior:` repository-level dependency writes also apply duplicate and cycle checks, so dependency legality is constrained both at creation time and at transition time. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteDependencyRepository.kt:35-60,97-149,151-202`

`Implemented behavior:` tests cover satisfied dependencies, unsatisfied blockers, blocked-transition bypass, and multi-dependency gating. `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandlerTest.kt:245-310`, `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/WorkflowIntegrationTest.kt:665-706`

## Note / Schema Gating

`Implemented behavior:` note-schema gating is conditional, not universal. The note-schema service returns `null`/`false` in schema-free mode, which means transitions proceed without note-based gate enforcement when no config file exists or no schema matches the item tags. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/NoteSchemaService.kt:5-13,20-29,31-37`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/config/YamlNoteSchemaService.kt:13-30,48-71,83-89`

`Implemented behavior:` `advance_item` enforces required notes for the current phase on `start`, and all required notes across all phases on `complete`. Missing required notes are returned as a failed gate result instead of silently ignored. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/AdvanceItemTool.kt:195-257`

`Implemented behavior:` `complete_tree` applies the same gate concept during batch completion and skips downstream dependents when a required-note gate fails. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/compound/CompleteTreeTool.kt:12-18,160-178,246-267,316-330`

`Implemented behavior:` integration tests show queue/work/review note gates in schema-backed flows and schema-free advancement when no schema tag exists. `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/SchemaGatedLifecycleTest.kt:18-22,234-291,297-318,324-337`

## Validated Transition Path

`Implemented behavior:` the main validated write path is `advance_item`. It fetches the item, resolves the trigger, validates dependencies, checks note gates, applies the transition, records cascade and unblock side effects, and returns the next-role expected notes. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/AdvanceItemTool.kt:121-265,274-387`

`Implemented behavior:` `applyTransition` writes both the updated `WorkItem` and a `RoleTransition` audit entry, preserving `previousRole` on block/hold and clearing it on resume. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:294-370`

`Implemented behavior:` tests confirm that `applyTransition` updates the item and writes an audit transition, including previous-role persistence across block/resume. `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandlerTest.kt:385-456`

## Direct Mutation Bypasses

`Implemented behavior:` `manage_items(create)` accepts caller-supplied `role` and `statusLabel` values and persists them directly through `WorkItemRepository.create`. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt:38-45,176-188,249-290`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteWorkItemRepository.kt:52-79`

`Implemented behavior:` `manage_items(update)` accepts caller-supplied `role`, `statusLabel`, `priority`, `complexity`, and other fields, then writes them through `repo.update(updatedItem)` without invoking the transition handler. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt:391-502`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteWorkItemRepository.kt:81-118`

`Absent/no evidence found:` no `RoleTransitionHandler`, `roleTransitionRepository`, or `applyTransition` call was found in `ManageItemsTool.kt`. Search: `rg -n 'RoleTransitionHandler|roleTransitionRepository|applyTransition|advance_item' current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt` returned no matches. See `99-evidence-index.md`, negative search `N9`.

`Implemented behavior:` `create_work_tree` also bypasses transition validation for its initial writes by delegating to `WorkTreeExecutor`, and the active implementation inserts `work_items`, `dependencies`, and `notes` directly inside one transaction. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/compound/CreateWorkTreeTool.kt:13-20,292-307`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/WorkTreeExecutor.kt:33-39`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/service/SQLiteWorkTreeService.kt:23-33,38-123`

## Assessment

`Inferred capability:` repo-local workflow legality in active/current is best described as a dual-path model:
- validated transitions through `advance_item` and `complete_tree`
- direct item/state mutation through `manage_items` and direct work-tree creation

That means legality enforcement is real, but it is not the only mutation path. If a caller needs dependency validation, note gates, and transition audit records, it must use the validated transition path rather than raw item updates. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/AdvanceItemTool.kt:121-265`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/compound/CompleteTreeTool.kt:160-340`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt:361-516`
