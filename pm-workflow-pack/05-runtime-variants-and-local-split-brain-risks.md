# PM Workflow Pack: Runtime Variants and Local Split-Brain Risks

## Baseline

`Implemented behavior:` the repo declares `:current` as the active module and `:clockwork` as archived. `settings.gradle.kts:6-10`, `build.gradle.kts:1-3`, `clockwork/DEPRECATED.md:3-5,73`

The risks below exist because other repo-local runtime selectors still compete with that declaration.

## Risk 1: Active Build Selectors vs Default Docker Runtime

Pattern:
- build selectors say `current` is active
- default container selectors still point to `clockwork`/`runtime-v2`

Systems/components involved:
- `settings.gradle.kts`
- `build.gradle.kts`
- `docker-compose.yml`
- `Dockerfile`
- `scripts/docker-build.sh`

Why this is a local split-brain risk:
- a reader following the root Gradle/build signals lands on `current`
- a reader following the default docker-compose service or default docker build script lands on `clockwork`
- both paths are repo-local and both are still runnable/buildable

Evidence:
- `settings.gradle.kts:6-10`
- `build.gradle.kts:1-3`
- `docker-compose.yml:2-24` defaults `mcp-task-orchestrator` to `runtime-v2`
- `docker-compose.yml:36-102` exposes `current` only behind the `current` and `current-http` profiles
- `Dockerfile:31-33` builds only `:current:jar` by default, but still defines both `runtime-v2` and `runtime-current` targets at `Dockerfile:82-92`
- `scripts/docker-build.sh:5-13,15-19,57-68` defaults to a v2 image unless `--current` or `--all` is passed

## Risk 2: Competing Workflow/Object Models (`current` vs `clockwork`)

Pattern:
- active/current uses a unified `WorkItem` graph
- archived/clockwork uses a `Project -> Feature -> Task` hierarchy with a different tool surface

Systems/components involved:
- `current` runtime and domain model
- `clockwork` runtime and tool registration

Why this is a local split-brain risk:
- the two variants expose materially different entities, transition tools, and audit/query affordances
- the repo still contains both implementations and both runtime targets

Evidence:
- `current` documents and persists a unified `WorkItem` model. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/domain/model/WorkItem.kt:7-25`, `current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql:4-25`
- `current` registers 13 tools centered on `manage_items`, `advance_item`, `get_context`, and related read models. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:87-110,220`
- `clockwork/DEPRECATED.md` describes the v2 `Project -> Feature -> Task` model and its larger tool surface. `clockwork/DEPRECATED.md:10-20,24-34`
- `clockwork` still registers a different 14-tool MCP surface including `GetNextTaskTool`, `GetBlockedTasksTool`, `RequestTransitionTool`, and `QueryRoleTransitionsTool`. `clockwork/src/main/kotlin/io/github/jpicklyk/mcptask/interfaces/mcp/McpServer.kt:268-302`
- `clockwork` transition handling is exposed through `request_transition` over `task`, `feature`, and `project` containers. `clockwork/src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/status/RequestTransitionTool.kt:21-42,43-45`
- `clockwork` exposes a dedicated audit query tool that current does not expose. `clockwork/src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/status/QueryRoleTransitionsTool.kt:14-20,21-32`

## Risk 3: Competing Writers Inside `current`

Pattern:
- validated transition writer: `advance_item` / `complete_tree`
- direct state writer: `manage_items`
- direct bulk writer: `create_work_tree` via `SQLiteWorkTreeService`

Systems/components involved:
- `AdvanceItemTool`
- `CompleteTreeTool`
- `ManageItemsTool`
- `CreateWorkTreeTool`
- `SQLiteWorkItemRepository`
- `SQLiteWorkTreeService`

Why this is a local split-brain risk:
- the repo contains both a legality-enforcing state transition path and bypass writers that can alter persisted workflow state without the same validation/audit sequence
- consumers can reach different correctness guarantees depending on which write surface they call

Evidence:
- validated transition path uses `RoleTransitionHandler` resolve/validate/apply and writes audit entries. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/AdvanceItemTool.kt:121-265`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/RoleTransitionHandler.kt:53-63,199-273,306-355`
- `complete_tree` also uses the validated transition handler after gate checks. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/compound/CompleteTreeTool.kt:270-304`
- `manage_items(update)` can write `role` and `statusLabel` directly through repository update. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt:391-502`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/repository/SQLiteWorkItemRepository.kt:81-118`
- `create_work_tree` writes items, dependencies, and notes atomically through a direct table-writing executor. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/compound/CreateWorkTreeTool.kt:299-307`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/service/SQLiteWorkTreeService.kt:32-130`
- no transition-handler usage was found in `ManageItemsTool.kt`. Search: `rg -n 'RoleTransitionHandler|roleTransitionRepository|applyTransition|advance_item' current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt` returned no matches. See `99-evidence-index.md`, negative search `N9`.

## Risk 4: Config Path Drift Between Docs and Runtime

Pattern:
- runtime code resolves `.taskorchestrator/config.yaml`
- root docs also describe `.taskorchestrator/config.yaml`
- `CLAUDE.md` and `AGENTS.md` still point at a non-existent bundled `default-config.yaml`

Systems/components involved:
- `YamlNoteSchemaService`
- `NoteSchemaService`
- `current/docs/*`
- `CLAUDE.md`
- `AGENTS.md`

Why this is a local ambiguity risk:
- repo-local guidance names two different config locations for the same workflow-gating system
- a reader following the stale root table path would look for a config file that is not present in the scanned resources

Evidence:
- runtime code resolves `.taskorchestrator/config.yaml` from `AGENT_CONFIG_DIR` or `user.dir`. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/config/YamlNoteSchemaService.kt:13-16,83-89`
- note-schema interface docs describe `.taskorchestrator/config.yaml` and schema-free mode when absent. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/NoteSchemaService.kt:5-13`
- current docs use `.taskorchestrator/config.yaml` as the workflow config location. `current/docs/workflow-guide.md:79-83,694-700`, `current/docs/quick-start.md:267-271,331-335`
- `CLAUDE.md` and `AGENTS.md` still point to `current/src/main/resources/configuration/default-config.yaml`. `CLAUDE.md:124-132`, `AGENTS.md:124-132`
- no bundled config/configuration/yaml file was found under `current/src/main/resources`, and no repo-local `.taskorchestrator/` directory was found. Searches: `rg --files current/src/main/resources | rg 'config|configuration|yaml'` and `rg --files . | rg '^\\.taskorchestrator/'` returned no matches. See `99-evidence-index.md`, negative search `N7`.

## Assessment

`Inferred capability:` the highest local split-brain risk is not just legacy code remaining in-tree. The bigger problem is selector disagreement:
- root build files say `current`
- default docker surfaces still prefer `clockwork`
- `current` itself contains both validated and bypass write paths

That combination makes “what this repo actually enforces” depend on which local runtime and write surface a caller chooses. `settings.gradle.kts:6-10`, `docker-compose.yml:2-24,36-102`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/workflow/AdvanceItemTool.kt:121-265`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/tools/items/ManageItemsTool.kt:361-516`
