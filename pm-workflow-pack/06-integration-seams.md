# PM Workflow Pack: Integration Seams

## 1. Active MCP Server Surface

`Implemented behavior:` the active/current runtime starts from `CurrentMain`, constructs `CurrentMcpServer`, and exposes MCP tools over either stdio or streamable HTTP depending on `MCP_TRANSPORT`. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/CurrentMain.kt:10-18,23-38`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:41-45,61-80,114-120,163-200`

`Implemented behavior:` the active/current MCP tool surface is 13 tools:
- `manage_items`
- `query_items`
- `manage_notes`
- `query_notes`
- `manage_dependencies`
- `query_dependencies`
- `advance_item`
- `get_next_status`
- `get_next_item`
- `get_blocked_items`
- `complete_tree`
- `create_work_tree`
- `get_context`

Evidence: `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:87-110,206-220`

`Implemented behavior:` when HTTP transport is selected, the current server exposes the MCP stream on `/mcp` with host/port controlled by `MCP_HTTP_HOST` and `MCP_HTTP_PORT`. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:163-200`

## 2. Container / Runtime Entry Seams

`Implemented behavior:` repo-local container entry points are defined in `docker-compose.yml`, with one default service targeting `runtime-v2` and two profile-gated current services targeting `runtime-current` (stdio and HTTP). `docker-compose.yml:2-24,36-102`

`Implemented behavior:` the Dockerfile defines both `runtime-v2` and `runtime-current` targets, while the builder stage only builds the current JAR by default. `Dockerfile:31-33,82-92`

`Implemented behavior:` the shell build wrapper is an operator-facing seam that defaults to a v2 image unless `--current` or `--all` is supplied. `scripts/docker-build.sh:5-13,15-19,57-68`

`Implemented behavior:` runtime configuration is environment-driven: `DATABASE_PATH`, `USE_FLYWAY`, `LOG_LEVEL`, `AGENT_CONFIG_DIR`, and HTTP transport variables are read from environment rather than from a compiled config object. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/database/DatabaseConfig.kt:7-51`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:83-120,163-166`

## 3. Project-Local Workflow Extension Surface

`Implemented behavior:` note-schema configuration is a repo-local extension seam. The runtime resolves `.taskorchestrator/config.yaml` from `AGENT_CONFIG_DIR` or `user.dir`, and the note-schema service changes gate behavior based on that file. `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/infrastructure/config/YamlNoteSchemaService.kt:13-16,31-46,48-71,83-89`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/application/service/NoteSchemaService.kt:5-13`

`Implemented behavior:` current docs instruct operators to mount or create `.taskorchestrator/config.yaml`, which means external consumers can change gate requirements without changing Kotlin code. `current/docs/workflow-guide.md:79-83,694-700`, `current/docs/quick-start.md:267-271,331-335`

## 4. Plugin / Hook Surfaces

`Implemented behavior:` the repo ships a Claude plugin marketplace entry for `task-orchestrator`, pointing at `./claude-plugins/task-orchestrator` with `strict: true`. `.claude-plugin/marketplace.json:1-29`

`Implemented behavior:` plugin hook config registers four client-side hook events:
- `SessionStart`
- `PreToolUse` on `EnterPlanMode`
- `PostToolUse` on `ExitPlanMode`
- `SubagentStart`

Evidence: `claude-plugins/task-orchestrator/hooks/hooks-config.json:1-52`

`Implemented behavior:` the hook scripts inject operational guidance that explicitly references current MCP tools such as `get_context`, `advance_item`, `create_work_tree`, and `complete_tree`. `claude-plugins/task-orchestrator/hooks/session-start.mjs:2-19`, `claude-plugins/task-orchestrator/hooks/pre-plan.mjs:2-9`, `claude-plugins/task-orchestrator/hooks/post-plan.mjs:2-9`, `claude-plugins/task-orchestrator/hooks/subagent-start.mjs:2-23`

`Documented intent:` these plugin hooks are integration aids for MCP clients, not evidence of server-side legality enforcement by themselves. The runtime authority still comes from the server/tool code, not the hook prose. `claude-plugins/task-orchestrator/hooks/session-start.mjs:2-19`, `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt:87-110`

## 5. Archived Variant Surface

`Implemented behavior:` the archived `clockwork` runtime still exposes a separate MCP surface over stdio only, including `request_transition` and `query_role_transitions`. `clockwork/src/main/kotlin/io/github/jpicklyk/mcptask/interfaces/mcp/McpServer.kt:108-114,273-302`, `clockwork/src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/status/RequestTransitionTool.kt:21-42,43-45`, `clockwork/src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/status/QueryRoleTransitionsTool.kt:14-20,21-32`

`Inferred capability:` that archived surface remains an external integration seam because docker/build defaults can still route operators toward v2 unless they explicitly select the current runtime. `docker-compose.yml:2-24`, `scripts/docker-build.sh:57-68`

## 6. Absent / No Evidence Found

`Absent/no evidence found:` no dedicated workflow CLI parser or subcommand framework was found in the scanned Kotlin sources (`current/src/main/kotlin`, `current/src/test/kotlin`, `clockwork/src/main/kotlin`, `clockwork/src/test/kotlin`). Search: `rg -n 'Clikt|picocli|kotlinx\\.cli|CommandLine\\.|ArgParser|subcommand|option\\(' current/src/main/kotlin current/src/test/kotlin clockwork/src/main/kotlin clockwork/src/test/kotlin` returned no matches. The repo exposes startup entrypoints and shell scripts, but not a separate repo-local workflow CLI. See `99-evidence-index.md`, negative search `N10`.

`Absent/no evidence found:` no dedicated REST controller layer was found in the scanned Kotlin sources. Search: `rg -n '@(Get|Post|Put|Delete)Mapping|routing\\s*\\{|route\\(|install\\(Routing\\)' current/src/main/kotlin clockwork/src/main/kotlin` returned no matches. The only HTTP evidence in active/current is the MCP stream transport in `CurrentMcpServer`. See `99-evidence-index.md`, negative search `N11`.

`Absent/no evidence found:` no dedicated import/export tool or class was found in the scanned runtime/plugin/script scope. Search: `rg -n 'override val name = \"(import|export)|class (Import|Export)|\\b(import|export)_' current/src/main/kotlin clockwork/src/main/kotlin claude-plugins/task-orchestrator .claude-plugin scripts .github` returned no matches. See `99-evidence-index.md`, negative search `N12`.
