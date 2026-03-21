# PM Workflow Pack: Gates, Actions, and Progress

## Capability Classification

### Dependency Gates

Classification: `partially authoritative`

`Implemented behavior:` dependency thresholds are authoritative on the validated transition path. `RoleTransitionHandler.validateTransition` blocks forward progress when blocker items have not reached their required `unblockAt` role, and `complete_tree` depends on the same transition machinery after gate checks. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:190-273`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/compound/CompleteTreeTool.kt:270-304`

`Implemented behavior:` dependency creation is also constrained at write time through duplicate and cycle checks in the dependency repository. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteDependencyRepository.kt:35-60,97-149,151-202`

`Inferred capability:` dependency gates are only partially authoritative at the repo level because `manage_items(update)` can still mutate `role` directly without invoking dependency validation. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt:391-502`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteWorkItemRepository.kt:81-118`

### Blockers

Classification: `derived`

`Implemented behavior:` explicit blocked state is stored as `Role.BLOCKED`, but the blocked-items surface itself is computed. `get_blocked_items` scans `BLOCKED`, `QUEUE`, `WORK`, and `REVIEW` items, resolves blocker details from dependency edges, and returns items either explicitly blocked or dependency-blocked. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetBlockedItemsTool.kt:14-25,101-220`

`Implemented behavior:` `get_next_status` also derives a `Blocked` recommendation from either explicit blocked state or unsatisfied dependency validation. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetNextStatusTool.kt:11-19,75-141`

`Implemented behavior:` tests confirm both explicit and dependency-derived blocked cases. `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetBlockedItemsToolTest.kt:156-169,188-247`

### Note / Comment Gates

Classification: `partially authoritative`

`Implemented behavior:` note gates are authoritative only when an item’s tags match a configured note schema. In that mode, `advance_item(start)` requires current-phase notes, `advance_item(complete)` requires all required notes, and `get_context` reports gate status and missing notes. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/NoteSchemaService.kt:5-13,20-29`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/config/YamlNoteSchemaService.kt:13-30,48-71,83-89`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/AdvanceItemTool.kt:195-257`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetContextTool.kt:120-192,352-400`

`Implemented behavior:` schema-free mode disables note gating entirely. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/NoteSchemaService.kt:11-13`, `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/SchemaGatedLifecycleTest.kt:297-318`

`Absent/no evidence found:` no separate comment entity, comment repository, or comment-driven gate was found in the active/current scan scope (`current/src/main/kotlin`, `current/src/main/resources`, `current/src/test/kotlin`). Search: `rg -n --ignore-case 'class Comment|data class Comment|CommentRepository|comments table|comment_id' current/src/main/kotlin current/src/main/resources current/src/test/kotlin` returned no matches. See `99-evidence-index.md`, negative search `N2`.

### Next-Action Computation

Classification: `advisory`

`Implemented behavior:` `get_next_item` computes recommendations by selecting `QUEUE` items, filtering blocked candidates, sorting by priority then complexity, and returning the top results. It does not mutate state. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetNextItemTool.kt:10-18,77-145,174-225`

`Implemented behavior:` `get_next_status` computes a `Ready`, `Blocked`, or `Terminal` recommendation for a single item based on its current role, note-schema review phase, and dependency validation. It also does not mutate state. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetNextStatusTool.kt:11-19,60-141`

`Implemented behavior:` tests verify ordering, blocker exclusion, threshold handling, and downstream visibility after blocker completion. `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetNextItemToolTest.kt:128-149,188-269`, `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/WorkflowIntegrationTest.kt:747-770`

### Decisions / Progress Tracking

Classification: `absent/no evidence found` for dedicated decision/progress records

`Implemented behavior:` the repo does persist workflow progression state indirectly through `role`, `statusLabel`, `roleChangedAt`, `requiresVerification`, and `role_transitions`, and `get_context(session-resume)` surfaces recent transitions. `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:11-24,63-77`, `current/src/main/resources/db/migration/V2__Work_Item_Field_Updates.sql:17-18`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetContextTool.kt:202-275`

`Absent/no evidence found:` no dedicated decision repository, decision table, progress repository, or progress table was found in the active/current scan scope. Search: `rg -n --ignore-case 'decision_id|decision table|progress table|progress record|decision record|decision repository|progress repository' current/src/main/kotlin current/src/main/resources current/src/test/kotlin` returned no matches. See `99-evidence-index.md`, negative search `N1`.

`Absent/no evidence found:` no dedicated decision subsystem was found in the same scope. Search: `rg -n --ignore-case 'decision|decisions' current/src/main/kotlin current/src/main/resources current/src/test/kotlin` returned only incidental references, not a decision-tracking component. See `99-evidence-index.md`, negative search `N4`.
