# PM Workflow Pack: Entity and Persistence Model

## Active Baseline

`Implemented behavior:` the root Gradle selectors mark `:current` as the active module and `:clockwork` as archived, so the entity/persistence model below is grounded first in `current/`. `settings.gradle.kts:6-10`, `build.gradle.kts:1-3`

## Core Workflow Entities

### 1. WorkItem

`Implemented behavior:` `WorkItem` is the core persisted workflow object. It stores hierarchy (`parentId`, `depth`), canonical workflow state (`role`), optional display state (`statusLabel`), suspension state (`previousRole`), prioritization (`priority`, `complexity`), verification flag (`requiresVerification`), metadata/tags, timestamps, and an optimistic-lock version. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/WorkItem.kt:7-25`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/Role.kt:4-6`, `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:4-25`, `current/src/main/resources/db/migration/V2__Work_Item_Field_Updates.sql:4-26`

`Implemented behavior:` the Exposed table definition matches the migration-level fields, including `requires_verification`, `role_changed_at`, and `version`. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/WorkItemsTable.kt:6-32`

`Implemented behavior:` repository reads/writes for `WorkItem` are centralized through `WorkItemRepository`, with create/update/delete, filter queries, tree queries, and ancestor-chain lookup. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/repository/WorkItemRepository.kt:9-98`

`Implemented behavior:` the active MCP server exposes both direct CRUD surfaces and workflow surfaces over `WorkItem` state by registering `manage_items`, `query_items`, `advance_item`, `get_next_status`, `get_next_item`, `get_blocked_items`, `complete_tree`, `create_work_tree`, and `get_context`. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:87-110,220`

### 2. Note

`Implemented behavior:` `Note` is a role-scoped accountability artifact attached to a `WorkItem`. It is unique by `(itemId, key)` and constrained to `queue`, `work`, or `review` roles. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/Note.kt:7-12,13-33`

`Implemented behavior:` notes are persisted in a dedicated `notes` table with timestamps and a unique index on `(work_item_id, key)`. `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:33-46`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/NotesTable.kt:7-20`

`Implemented behavior:` note persistence is exposed through `NoteRepository`, including upsert, delete, per-item queries, and batch lookup by item IDs. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/repository/NoteRepository.kt:6-13`

`Implemented behavior:` the active write surface for notes is `manage_notes`, which validates `itemId` existence before upsert and preserves existing note IDs on `(itemId, key)` collisions. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/notes/ManageNotesTool.kt:128-177`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteNoteRepository.kt:39-74`

### 3. Dependency

`Implemented behavior:` `Dependency` is a directed edge between `WorkItem`s. It supports `BLOCKS`, `IS_BLOCKED_BY`, and `RELATES_TO`, and can carry an `unblockAt` threshold for blocking semantics. Self-loops and invalid thresholds are rejected in the domain model. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/Dependency.kt:7-19,25-46`

`Implemented behavior:` dependencies are persisted in a dedicated `dependencies` table with uniqueness on `(from_item_id, to_item_id, type)`. `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:48-61`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/DependenciesTable.kt:7-20`

`Implemented behavior:` dependency persistence is exposed through `DependencyRepository`, including single writes, batch writes, graph lookup, delete, and cycle checks. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/repository/DependencyRepository.kt:6-30`

`Implemented behavior:` `manage_dependencies` is the active external write surface. It routes creates into `createBatch`, which applies duplicate and cycle validation atomically. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/dependency/ManageDependenciesTool.kt:12-21,241-296`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteDependencyRepository.kt:35-60,97-149,151-202`

### 4. RoleTransition

`Implemented behavior:` `RoleTransition` is a persisted audit record for workflow state changes. It stores `fromRole`, `toRole`, optional status labels, the trigger, summary, and the transition timestamp. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/RoleTransition.kt:6-23`

`Implemented behavior:` the audit records are stored in a dedicated `role_transitions` table with indexes by item and time. `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:63-77`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/RoleTransitionsTable.kt:7-21`

`Implemented behavior:` the repository surface supports create, per-item reads, time-range reads, `findSince`, and delete-by-item. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/repository/RoleTransitionRepository.kt:7-12`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteRoleTransitionRepository.kt:27-40,46-54,87-103`

## Relationships

`Implemented behavior:` `WorkItem` is self-referential through `parentId`, which creates a bounded depth hierarchy rather than separate project/feature/task tables in the active/current module. `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:4-25`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt:21-23,27-29,192-247`

`Implemented behavior:` `Note`, `Dependency`, and `RoleTransition` all reference `WorkItem` rows directly; notes and role transitions are deleted on item deletion, and dependencies cascade on either endpoint deletion. `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:33-77`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/NotesTable.kt:15-20`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/DependenciesTable.kt:14-20`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/RoleTransitionsTable.kt:17-20`

## Readers and Writers

`Implemented behavior:` the active/current tool layer reaches persistence through `ToolExecutionContext`, which exposes typed repository access for work items, notes, dependencies, role transitions, note schemas, and atomic work-tree execution. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/ToolExecutionContext.kt:12-44`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/RepositoryProvider.kt:9-20`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/DefaultRepositoryProvider.kt:18-31`

`Implemented behavior:` the canonical SQLite-backed implementations are wired by `DefaultRepositoryProvider`, so `current` owns direct persistence for all four core entity types in the active runtime. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/DefaultRepositoryProvider.kt:18-31`

`Inferred capability:` because `create_work_tree` delegates to `WorkTreeExecutor`, and `DefaultRepositoryProvider` binds that interface to `SQLiteWorkTreeService`, the active/current module also owns an atomic multi-entity writer that can create items, dependencies, and notes in one transaction. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/compound/CreateWorkTreeTool.kt:292-307`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/WorkTreeExecutor.kt:33-39`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/DefaultRepositoryProvider.kt:24-31`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/service/SQLiteWorkTreeService.kt:26-33,38-130`

## Absent / No Evidence Found

`Absent/no evidence found:` no separate `Comment` entity or `CommentRepository` was found in the active/current scan scope (`current/src/main/kotlin`, `current/src/main/resources`, `current/src/test/kotlin`). Search: `rg -n --ignore-case 'class Comment|data class Comment|CommentRepository|comments table|comment_id' current/src/main/kotlin current/src/main/resources current/src/test/kotlin` returned no matches. See `99-evidence-index.md`, negative search `N2`.

`Absent/no evidence found:` no dedicated decision or progress persistence layer was found in the active/current scan scope. Search: `rg -n --ignore-case 'decision_id|decision table|progress table|progress record|decision record|decision repository|progress repository' current/src/main/kotlin current/src/main/resources current/src/test/kotlin` returned no matches. See `99-evidence-index.md`, negative search `N1`.
