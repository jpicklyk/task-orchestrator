# PM Workflow Pack: Evidence Index

## Primary Source Index

### Module / Runtime Selectors

- `settings.gradle.kts:6-10` — active `:current`, archived `:clockwork`
- `build.gradle.kts:1-3` — root build comments repeat current-active / clockwork-archived split
- `docker-compose.yml:2-24` — default service targets `runtime-v2`
- `docker-compose.yml:36-102` — `current` services are profile-gated
- `Dockerfile:31-33,82-92` — builder builds current by default, runtime targets include both v2 and current
- `scripts/docker-build.sh:5-13,57-68` — default build path is v2 unless `--current` / `--all`

### Active Current Runtime

- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/CurrentMain.kt:10-18,23-38` — active/current entrypoint
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:41-45,61-80,87-120,163-220` — transport selection, repository/tool wiring, HTTP `/mcp`, tool list
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/ToolExecutionContext.kt:12-44` — tool access to repositories and note schema service
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/RepositoryProvider.kt:9-20` — repository/provider contract
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/DefaultRepositoryProvider.kt:18-31` — active SQLite repository bindings

### Persistence Schema

- `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:4-77` — `work_items`, `notes`, `dependencies`, `role_transitions`
- `current/src/main/resources/db/migration/V2__Work_Item_Field_Updates.sql:4-53` — nullable `complexity`, added `requires_verification`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/WorkItemsTable.kt:6-32`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/NotesTable.kt:7-20`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/DependenciesTable.kt:7-20`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/schema/RoleTransitionsTable.kt:7-21`

### Domain Model

- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/Role.kt:3-31` — canonical role semantics and progression ordering
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/WorkItem.kt:7-54` — workflow entity fields and validation
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/Note.kt:7-33` — note semantics and role restriction
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/Dependency.kt:7-46` — dependency semantics and threshold validation
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/RoleTransition.kt:6-23` — transition audit record shape

### Repository / Storage Logic

- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/repository/WorkItemRepository.kt:9-98`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/repository/NoteRepository.kt:6-13`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/repository/DependencyRepository.kt:6-30`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/repository/RoleTransitionRepository.kt:7-12`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteWorkItemRepository.kt:52-127`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteNoteRepository.kt:39-143`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteDependencyRepository.kt:27-202`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteRoleTransitionRepository.kt:27-103`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/DatabaseConfig.kt:7-51`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/DatabaseManager.kt:30-124,127-170`

### Workflow Legality / Read Models

- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/NoteSchemaService.kt:5-37`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/config/YamlNoteSchemaService.kt:10-90`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:53-63,81-188,190-273,294-370`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/CascadeDetector.kt:29-43,55-70,168-240`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/AdvanceItemTool.kt:13-28,31-49,121-387`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetNextItemTool.kt:10-18,77-145,174-225`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetBlockedItemsTool.kt:14-25,101-220,243-310`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetNextStatusTool.kt:11-19,60-141`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetContextTool.kt:13-18,106-196,202-345,352-400`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt:14-23,33-56,163-320,361-516`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/notes/ManageNotesTool.kt:12-18,128-215,222-280`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/dependency/ManageDependenciesTool.kt:12-21,241-340`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/compound/CompleteTreeTool.kt:12-27,160-345`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/compound/CreateWorkTreeTool.kt:13-20,32-55,263-307`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/WorkTreeExecutor.kt:33-39`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/service/SQLiteWorkTreeService.kt:23-33,38-130,133-166`

### Tests Used as Executable Intent

- `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandlerTest.kt:49-110,245-310,385-456`
- `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/SchemaGatedLifecycleTest.kt:18-22,234-318,324-337`
- `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetNextItemToolTest.kt:128-149,188-269`
- `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetBlockedItemsToolTest.kt:156-169,188-247`
- `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/GetContextToolTest.kt:95-119,125-200,207-245`
- `current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/WorkflowIntegrationTest.kt:631-657,665-706,713-740,747-770`

### Archived Variant / Drift Evidence

- `clockwork/DEPRECATED.md:3-5,10-34,37-73`
- `clockwork/src/main/kotlin/Main.kt:8-22,35-44,62-68,82-99`
- `clockwork/src/main/kotlin/io/github/jpicklyk/mcptask/interfaces/mcp/McpServer.kt:108-114,163-179,273-319`
- `clockwork/src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/status/RequestTransitionTool.kt:21-42,43-45`
- `clockwork/src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/status/QueryRoleTransitionsTool.kt:14-20,21-32,36-43`
- `CLAUDE.md:124-132`
- `AGENTS.md:124-132`
- `current/docs/workflow-guide.md:79-83,694-700`
- `current/docs/quick-start.md:267-271,331-335`

### Plugin / Hook Seams

- `.claude-plugin/marketplace.json:1-29`
- `claude-plugins/task-orchestrator/hooks/hooks-config.json:1-52`
- `claude-plugins/task-orchestrator/hooks/session-start.mjs:2-19`
- `claude-plugins/task-orchestrator/hooks/pre-plan.mjs:2-9`
- `claude-plugins/task-orchestrator/hooks/post-plan.mjs:2-9`
- `claude-plugins/task-orchestrator/hooks/subagent-start.mjs:2-23`

## Negative-Evidence Searches

Each search below was run from `/Users/hue/code/task-orchestrator`.

### N1. No dedicated decision/progress persistence

Command:

```bash
rg -n --ignore-case 'decision_id|decision table|progress table|progress record|decision record|decision repository|progress repository' \
  current/src/main/kotlin current/src/main/resources current/src/test/kotlin
```

Scope:
- `current/src/main/kotlin`
- `current/src/main/resources`
- `current/src/test/kotlin`

Result:
- no matches

### N2. No separate comment entity or repository

Command:

```bash
rg -n --ignore-case 'class Comment|data class Comment|CommentRepository|comments table|comment_id' \
  current/src/main/kotlin current/src/main/resources current/src/test/kotlin
```

Scope:
- `current/src/main/kotlin`
- `current/src/main/resources`
- `current/src/test/kotlin`

Result:
- no matches

### N3. No general event store / chronicle / timeline subsystem

Command:

```bash
rg -n --ignore-case 'event store|event_log|event log|timeline|chronicle|journal' \
  current/src/main/kotlin current/src/main/resources current/src/test/kotlin
```

Scope:
- `current/src/main/kotlin`
- `current/src/main/resources`
- `current/src/test/kotlin`

Result:
- only:
  - `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/DatabaseManager.kt:66`
  - `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/DatabaseManager.kt:74`
  - `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/DatabaseManager.kt:75`

Interpretation:
- only SQLite journal-mode comments and PRAGMA usage were found, not an application-level event/chronicle subsystem

### N4. No dedicated decision subsystem in active/current

Command:

```bash
rg -n --ignore-case 'decision|decisions' \
  current/src/main/kotlin current/src/main/resources current/src/test/kotlin
```

Scope:
- `current/src/main/kotlin`
- `current/src/main/resources`
- `current/src/test/kotlin`

Result:
- only incidental references, including schema/test text such as `design-decisions`; no dedicated decision service/repository/tool

### N5. No comment usage in active/current scope

Command:

```bash
rg -n --ignore-case '\\bcomment\\b|comments' \
  current/src/main/kotlin current/src/main/resources current/src/test/kotlin
```

Scope:
- `current/src/main/kotlin`
- `current/src/main/resources`
- `current/src/test/kotlin`

Result:
- no matches

### N6. No `request_transition` or `query_role_transitions` in active/current

Command:

```bash
rg -n 'RequestTransitionTool|QueryRoleTransitionsTool|request_transition|query_role_transitions' \
  current/src/main/kotlin current/src/test/kotlin
```

Scope:
- `current/src/main/kotlin`
- `current/src/test/kotlin`

Result:
- no matches

### N7. No bundled default workflow config file in active/current resources

Commands:

```bash
rg --files current/src/main/resources | rg 'config|configuration|yaml'
rg --files . | rg '^\\.taskorchestrator/'
```

Scope:
- `current/src/main/resources`
- repo root file list

Result:
- no matches for either command

### N8. Config-path drift evidence

Command:

```bash
rg -n 'default-config.yaml|\\.taskorchestrator/config.yaml|AGENT_CONFIG_DIR' \
  current/docs CLAUDE.md AGENTS.md current/src/main/kotlin
```

Scope:
- `current/docs`
- `CLAUDE.md`
- `AGENTS.md`
- `current/src/main/kotlin`

Result:
- root docs refer to both `AGENT_CONFIG_DIR` and `.taskorchestrator/config.yaml`
- `CLAUDE.md:131` and `AGENTS.md:131` also refer to `current/src/main/resources/configuration/default-config.yaml`
- runtime code resolves `.taskorchestrator/config.yaml`

### N9. No transition-handler usage in `ManageItemsTool`

Command:

```bash
rg -n 'RoleTransitionHandler|roleTransitionRepository|applyTransition|advance_item' \
  current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt
```

Scope:
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt`

Result:
- no matches

### N10. No dedicated CLI parser / subcommand framework

Command:

```bash
rg -n 'Clikt|picocli|kotlinx\\.cli|CommandLine\\.|ArgParser|subcommand|option\\(' \
  current/src/main/kotlin current/src/test/kotlin clockwork/src/main/kotlin clockwork/src/test/kotlin
```

Scope:
- `current/src/main/kotlin`
- `current/src/test/kotlin`
- `clockwork/src/main/kotlin`
- `clockwork/src/test/kotlin`

Result:
- no matches

### N11. No dedicated REST/controller layer

Command:

```bash
rg -n '@(Get|Post|Put|Delete)Mapping|routing\\s*\\{|route\\(|install\\(Routing\\)' \
  current/src/main/kotlin clockwork/src/main/kotlin
```

Scope:
- `current/src/main/kotlin`
- `clockwork/src/main/kotlin`

Result:
- no matches

### N12. No dedicated import/export workflow surface

Command:

```bash
rg -n 'override val name = \"(import|export)|class (Import|Export)|\\b(import|export)_' \
  current/src/main/kotlin clockwork/src/main/kotlin claude-plugins/task-orchestrator .claude-plugin scripts .github
```

Scope:
- `current/src/main/kotlin`
- `clockwork/src/main/kotlin`
- `claude-plugins/task-orchestrator`
- `.claude-plugin`
- `scripts`
- `.github`

Result:
- no matches

## Validation Attempts

Attempted targeted test run:

```bash
./gradlew :current:test --tests "*RoleTransitionHandlerTest" --tests "*SchemaGatedLifecycleTest" \
  --tests "*GetNextItemToolTest" --tests "*GetBlockedItemsToolTest" --tests "*GetContextToolTest"
```

Observed result:
- failed because `./gradlew` was not executable in this environment

Retry:

```bash
bash ./gradlew :current:test --tests "*RoleTransitionHandlerTest" --tests "*SchemaGatedLifecycleTest" \
  --tests "*GetNextItemToolTest" --tests "*GetBlockedItemsToolTest" --tests "*GetContextToolTest"
```

Observed result:
- failed because no Java runtime was installed (`Unable to locate a Java Runtime.`)
