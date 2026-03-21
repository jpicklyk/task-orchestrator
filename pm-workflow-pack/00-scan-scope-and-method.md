# PM Workflow Pack: Scan Scope and Method

## Scope

This pack was produced from repo-local evidence in `/Users/hue/code/task-orchestrator` only.

Primary directories searched:
- `current/src/main/kotlin`
- `current/src/main/resources`
- `current/src/test/kotlin`
- `current/docs`
- `clockwork/src/main/kotlin`
- `clockwork/src/test/kotlin`
- `clockwork/docs`
- `claude-plugins/task-orchestrator`
- `.claude-plugin`
- `scripts`
- `.github`

Primary runtime/config selectors inspected:
- `settings.gradle.kts`
- `build.gradle.kts`
- `docker-compose.yml`
- `Dockerfile`
- `scripts/docker-build.sh`
- `CLAUDE.md`
- `AGENTS.md`

## Commands Used

Representative inspection commands:

```bash
git branch --show-current
git rev-parse HEAD
git status --short

rg --files current/src/main/kotlin current/src/main/resources current/src/test/kotlin \
  clockwork/src/main/kotlin clockwork/src/test/kotlin \
  claude-plugins/task-orchestrator .claude-plugin scripts .github

nl -ba settings.gradle.kts | sed -n '1,40p'
nl -ba build.gradle.kts | sed -n '1,40p'
nl -ba docker-compose.yml | sed -n '1,140p'
nl -ba Dockerfile | sed -n '1,160p'
nl -ba scripts/docker-build.sh | sed -n '1,140p'

nl -ba current/src/main/kotlin/.../CurrentMain.kt | sed -n '9,46p'
nl -ba current/src/main/kotlin/.../CurrentMcpServer.kt | sed -n '38,223p'
nl -ba current/src/main/resources/db/migration/V1__Current_Initial_Schema.sql | sed -n '1,140p'
nl -ba current/src/main/resources/db/migration/V2__Work_Item_Field_Updates.sql | sed -n '1,140p'

nl -ba current/src/main/kotlin/.../RoleTransitionHandler.kt | sed -n '74,370p'
nl -ba current/src/main/kotlin/.../AdvanceItemTool.kt | sed -n '120,387p'
nl -ba current/src/main/kotlin/.../GetNextItemTool.kt | sed -n '77,225p'
nl -ba current/src/main/kotlin/.../GetBlockedItemsTool.kt | sed -n '101,310p'
nl -ba current/src/main/kotlin/.../GetNextStatusTool.kt | sed -n '60,141p'
nl -ba current/src/main/kotlin/.../GetContextTool.kt | sed -n '106,400p'
nl -ba current/src/main/kotlin/.../ManageItemsTool.kt | sed -n '163,520p'
nl -ba current/src/main/kotlin/.../ManageNotesTool.kt | sed -n '128,280p'
nl -ba current/src/main/kotlin/.../ManageDependenciesTool.kt | sed -n '241,340p'
nl -ba current/src/main/kotlin/.../CompleteTreeTool.kt | sed -n '160,340p'
nl -ba current/src/main/kotlin/.../CreateWorkTreeTool.kt | sed -n '13,307p'
nl -ba current/src/main/kotlin/.../SQLiteWorkTreeService.kt | sed -n '23,166p'

nl -ba clockwork/DEPRECATED.md | sed -n '1,140p'
nl -ba clockwork/src/main/kotlin/Main.kt | sed -n '1,102p'
nl -ba clockwork/src/main/kotlin/.../McpServer.kt | sed -n '108,319p'
nl -ba clockwork/src/main/kotlin/.../RequestTransitionTool.kt | sed -n '21,160p'
nl -ba clockwork/src/main/kotlin/.../QueryRoleTransitionsTool.kt | sed -n '14,140p'
```

Negative-evidence searches are recorded in `99-evidence-index.md`.

## Search Terms

Targeted search terms included:
- `role`, `status`, `transition`, `advance_item`, `request_transition`
- `blocked`, `dependency`, `unblockAt`, `get_next_item`, `get_blocked_items`
- `note`, `schema`, `guidancePointer`, `get_context`
- `decision`, `progress`, `comment`, `chronicle`, `timeline`, `event store`
- `CurrentMcpServer`, `CurrentMain`, `clockwork`, `runtime-v2`, `runtime-current`
- `AGENT_CONFIG_DIR`, `.taskorchestrator/config.yaml`, `default-config.yaml`

## Files and Types Inspected

File types inspected:
- Kotlin runtime code (`.kt`)
- SQL migrations (`.sql`)
- Markdown docs and runbooks (`.md`)
- JSON plugin/hook config (`.json`)
- Shell scripts (`.sh`)
- GitHub workflow YAML (`.yml`)

Priority order during inspection:
1. Runtime/build selectors
2. Active module code under `current/`
3. Database schema and repositories
4. Tests covering workflow behavior
5. Archived `clockwork/` code for variant analysis
6. Plugin/hook/config docs for exposed seams

## Runtime / Config Selectors Examined

Selectors used to determine active vs archived paths:
- `settings.gradle.kts:6-10`
- `build.gradle.kts:1-3`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/CurrentMain.kt:10-12`
- `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:41-45`
- `clockwork/DEPRECATED.md:3-5,73`
- `docker-compose.yml:2-24,36-102`
- `Dockerfile:31-33,82-92`
- `scripts/docker-build.sh:5-13,57-68`

## Limitations

- The active/current implementation was inspected statically; no server process was started.
- Targeted Gradle tests were attempted but not executed successfully:

```bash
./gradlew :current:test --tests "*RoleTransitionHandlerTest" --tests "*SchemaGatedLifecycleTest" \
  --tests "*GetNextItemToolTest" --tests "*GetBlockedItemsToolTest" --tests "*GetContextToolTest"
```

Observed failure:
- `./gradlew` was not executable in this environment.

Retry:

```bash
bash ./gradlew :current:test --tests "*RoleTransitionHandlerTest" --tests "*SchemaGatedLifecycleTest" \
  --tests "*GetNextItemToolTest" --tests "*GetBlockedItemsToolTest" --tests "*GetContextToolTest"
```

Observed failure:
- no Java runtime was installed (`Unable to locate a Java Runtime.`)

- `AGENTS.md` is untracked in this checkout, but it was still repo-local and inspected as local guidance, not as higher authority than code.

## Negative-Evidence Method

Negative claims in this pack use this method:
- define a bounded scan scope
- run explicit `rg` queries against that scope
- record exact commands and results in `99-evidence-index.md`
- label the result as `absent/no evidence found`, not as impossibility

Where code and docs disagree, code/build/runtime selectors were treated as higher authority than prose.
