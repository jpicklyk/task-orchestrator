# Tier 4: Plugin — Skills and Hooks

## What You Get

- Seventeen hook scripts (everything registered in `claude-plugins/task-orchestrator/hooks/hooks-config.json`) that fire on session start, plan mode, plan approval, subagent launch and stop, run close, director mode and, when the REST API is configured, config sync and gate enforcement
- User-invocable skills as `/task-orchestrator:*` slash commands (see "User Skills" below)
- Internal skills that power the plan-mode pipeline (see "Internal Skills" below)
- The Agent-Owned-Phase Protocol injected into every phase-owner subagent automatically (see "Execution modes" below for exactly which subagents that covers)
- Run-based orchestration: `run-wave` and `ralph` open server runs, `director` watches them (see "Runs, director mode and ralph" below)

## Prerequisites

- [Claude Code](https://claude.ai/code) CLI installed
- [Tier 1: Bare MCP](bare-mcp.md) setup complete, against a 4.x server

The 4.0 plugin needs a server of major version 4 that reports the `seats` feature (`hooks/lib/server-compat.mjs`, `assessServer`). Against any other server, `session-start` adds one warning line, and `phase-guard-record` and `dispatch-hint` show one warning per session when a tool result still has the 3.x shape. A 3.x server pairs with the 3.x plugin: to stay on it, pin the plugin marketplace to the `release/3.x` ref. See the compatibility table in [`claude-plugins/CLAUDE.md`](../../../claude-plugins/CLAUDE.md).

---

## Installation

Two commands in Claude Code:

```
/plugin marketplace add https://github.com/jpicklyk/task-orchestrator
/plugin install task-orchestrator@task-orchestrator-marketplace
```

Verify with `/plugin list` — you should see `task-orchestrator` as enabled.

After editing plugin files, content is cached and the marketplace must be removed and re-added to pick up changes. For initial installation this is not required.

See [Quick Start](../quick-start.md) Step 4 for the full installation walkthrough.

### Two MCP entries

`/task-orchestrator:init` registers two entries next to each other in `.mcp.json`, and both keys contain `task-orchestrator` because the plugin's hook matchers key on that token:

| Entry | Profile | Serves |
|-------|---------|--------|
| the orchestration entry (for example `mcp-task-orchestrator`) | `orchestration` | `advance`, `config`, `deps_*`, `docs_*`, `items_*`, `notes_*`, `runs_*` |
| `mcp-task-orchestrator-director` | `director` | `deps_read`, `items_read`, `report`, `runs_read`, `runs_write`, and no read that returns a note, document or transition-summary body |

An HTTP director entry is the orchestration URL with `/mcp/director` in place of `/mcp`; a stdio director entry is the orchestration command with `-e MCP_TOOL_PROFILE=director`. Pass `--no-director` to skip it. In credential mode both entries carry the `${TASK_ORCHESTRATOR_API_TOKEN}` header placeholder, never a token. Details: `skills/init/SKILL.md` Step 2 and [fleet-deployment.md](../fleet-deployment.md).

### Plugin settings

The plugin reads its own settings from `extensions.claude-code` in `.taskorchestrator/config.yaml` (`orchestration.mode`, `retrospective.mode`, `retrospective.dispatchThreshold`, `retrospective.github_feedback`). A top-level `orchestration:` or `retrospective:` block is ignored by the hooks and rejected by a 4.0 server on push. The keys, values and defaults are in [config-format.md](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#plugin-settings-extensionsclaude-code), and [config-reference.md](../config-reference.md#extensions) says who owns each key.

---

## Hooks

Hooks fire automatically — no invocation needed after installation. Each hook is fail-open (an error emits `{}` and exits 0) except `director-guard`, which fails closed under the director marker. The per-hook gating (headless iteration, subagent, `orchestration.mode`, director session) is tabulated in [`hooks/README.md`](../../../claude-plugins/task-orchestrator/hooks/README.md) under "Mode gating"; each section below states the same facts.

### Session Start

**Script:** `session-start.mjs`. **Event:** `SessionStart` — every new Claude Code session.

**What it injects:** Short workflow guidance: use `advance` op `transition` for role transitions rather than raw status edits; ids accept a full UUID or an 8-character hex prefix; items nest by `parentId` to any depth; `items_read` select `context` with no arguments lists active and stalled items, and `select: "next"` finds the next work.

**Effect:** The agent knows the workflow conventions from the first prompt, without any CLAUDE.md instructions. In a headless iteration, a subagent or a director session the guidance is omitted and the diagnostics below are kept.

**Setup status:** the hook also reports one of four states, with the `Config:` path the skills should read and edit in every state that has a config:

| State | What the session sees |
|-------|-----------------------|
| No config found | `## Setup Status` pointing at `/task-orchestrator:init` (project) and `/task-orchestrator:init --user` (personal root) |
| Config without `project.rootId` | `## Setup Status` naming the config file and the init command to finish setup |
| User-level config with a root | `## Personal Scope`: the personal root anchors new items, reads stay unscoped |
| Project config with a root | `## Project Scope`: the project root scopes reads (`ancestorId`) and anchors new items |

When the state is "no config" or "config without rootId" and an orchestrator MCP registration exists, a one-line `systemMessage` also suggests running init, at most once per working directory per day; set `TASK_ORCHESTRATOR_SETUP_HINT=off` to silence it. In project or user state, if an HTTP registration exists but no REST API URL resolves, a `## Config Sync` notice says config-sync cannot push and names `TASK_ORCHESTRATOR_API_URL` or `client.json`. The hook's `watchPaths` include the user-level config as well as the located one.

**Diagnostics it keeps in every mode:** a `## Config Shape` line when the located config still has a top-level `orchestration:` or `retrospective:` block (the plugin reads those keys from `extensions.claude-code` only), and one compatibility line when the server is not a 4.x server with `seats` (only when a REST API is configured; an unreachable server adds nothing).

**Config discovery:** the hook finds the config via `AGENT_CONFIG_DIR`, a walk up from the working directory, the main checkout of a linked worktree, then the user-level file. `TASK_ORCHESTRATOR_HOME` replaces your home directory for Task Orchestrator: when it is set, the user-level `config.yaml` and `client.json` are read only from `$TASK_ORCHESTRATOR_HOME/.taskorchestrator/`, and `~/.taskorchestrator/config.yaml` is ignored unless `AGENT_CONFIG_DIR` points at it. `TASK_ORCHESTRATOR_CEILING` is an optional directory at which project-config discovery stops climbing — like `GIT_CEILING_DIRECTORIES`. It exists mainly so tests stay isolated; leave it unset in normal use. Full rules: [config-format.md](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#config-discovery-plugin-hooks-and-skills).

**Registration self-check:** The plugin's tool hooks — skill enforcement, the retro trigger, Phase-Guard Record, Dispatch Hint and Director Watch (below) — fire only when an MCP tool call's server segment — the middle part of `mcp__<server>__<tool>` — contains `task-orchestrator`. Session Start reads discoverable MCP registrations (project `.mcp.json`, and `~/.claude.json`'s top-level `mcpServers` plus its `projects[<cwd>].mcpServers`) and, for any orchestrator registration whose key omits that token, appends a `## Hook Registration Check` section naming the offending key and the fix (rename the key to include `task-orchestrator`, e.g. `mcp-task-orchestrator`). The check is purely diagnostic and fail-open: any read or parse error simply omits the section, and it never blocks session start. A registration is recognized as "the orchestrator" when its key, `url`, or `command` mentions `task-orchestrator`, or one of its args is an image-style reference such as `jpicklyk/task-orchestrator` (filesystem-path args are ignored); an HTTP registration whose key and URL both omit it is undetectable.

**Plugin version freshness check:** dev-checkout only. Session Start walks up from `AGENT_CONFIG_DIR` (if set) then `cwd` looking for a checked-out `claude-plugins/task-orchestrator/.claude-plugin/plugin.json` — a walk of its own (the config itself is found by the locator described above), so worktrees under `.claude/worktrees/<name>/` still find the checkout root. If found, it compares that file's `version` against the `plugin.json` of the plugin actually running the hook (resolved via `CLAUDE_PLUGIN_ROOT` when the harness sets it, else relative to the hook script's own file location). A mismatch appends a `## Plugin Version Drift` section naming both versions and pointing to `claude-plugins/CLAUDE.md` → "Plugin Discovery and Cache Refresh". Silent when no dev checkout is found, when either `plugin.json` can't be read or parsed, or when the versions match — like the registration self-check, this is purely diagnostic and never blocks session start.

### Orchestration Context

**Script:** `orchestration-context.mjs`. **Event:** `SessionStart` — every source, including `compact`, so the core survives `/compact` and `/clear`.

**What it injects:** the always-on orchestration core as `additionalContext`: workflow principles (materialize before implement, agent-owned phases, an explicit `model` on every dispatch, one feature-level review before a multi-child feature closes), the schema-resolution rule (a type selects the schema; tags never do), skill routing, retrospective handling, action items and visual conventions. It points at the `task-orchestrator:orchestrate` skill for the tier table, the delegation model table and phase-owner dispatch rules. It is a separate hook from Session Start so it has its own context budget.

**Inert when:** the session is a headless ralph iteration, a subagent or a director session, no `.taskorchestrator/config.yaml` can be located, or `extensions.claude-code.orchestration.mode` is `off`. `orchestration.mode: schema` swaps in a variant with no tiers and no model table. A top-level `orchestration:` block is not read. See [Tier 5: Orchestration Mode](orchestration-mode.md) and [config-format.md → Plugin settings](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#plugin-settings-extensionsclaude-code).

**Effect:** the agent orchestrates from the first prompt without any output style or CLAUDE.md instructions. It fails open: any error emits `{}`.

### Dispatch Model Guard

**Script:** `dispatch-model-guard.mjs`. **Event:** `PreToolUse` on the `Agent` tool.

**What it does:** denies a dispatch whose `model` is absent, empty or not a string. The shipped agents use `model: inherit`, so an omitted `model` silently runs the subagent on the caller's own model. The deny reason says to retry the same call with `model` set: in `workflow` mode it names the delegation table, in `schema` mode the item's dispatch profile. The value of `model` is not validated; the Agent tool owns that.

**Inert when:** the session is a headless ralph iteration, no `.taskorchestrator/config.yaml` can be located, `orchestration.mode` is `off`, or `model` is present. A subagent or director-session dispatch is still guarded: the check is on the dispatch, whoever makes it.

**Effect:** every dispatch carries an explicit model. It fails open: any error emits `{}`.

### Dispatch Hint

**Script:** `dispatch-hint.mjs`. **Event:** `PostToolUse` on `advance`.

**What it does:** for each applied `transition` result element that entered `work` or `review`, injects one line as `additionalContext`: `↳ <itemId> now in <role>; dispatch profile: agent=<agent|none> model=<model> [effort=<effort>] - pass model explicitly`. The profile comes from the `dispatch` object already in the result, so there is no server call. When the profile has no model, `workflow` mode shows `table default` and `schema` mode shows `unset`; `effort=` appears only when the profile has one.

**Inert when:** the session is a headless ralph iteration, no config can be located, `orchestration.mode` is `off`, the call comes from a subagent or a run seat (only the dispatcher needs the hint), or no transition entered `work` or `review`. A 3.x-shaped result shows the legacy-shape warning once per session and hints nothing.

**Effect:** the dispatcher sees the item's dispatch profile right after the transition instead of reading it off the result. It fails open: any error emits `{}`.

### Config Sync

**Script:** `config-sync.mjs`. **Events:** `SessionStart` and `FileChanged` on the located `config.yaml` (and the user-level one).

**What it does:** pushes the located config to the per-root config store over the REST API with the `config` ops `get` (with the local fingerprint), `validate` and `push` (with `expectedVersion` set to the head version), then syncs the workspace's `.taskorchestrator/rules/*.md` as rule documents (`docs_read` `list` kind `rule`, then `docs_write` `upsert` kind `rule`). On SessionStart only, it also syncs the plugin's bundled process rules under a manifest policy: a missing key is pushed, a server copy matching a known older bundled version is overwritten, and any other server copy is left alone. A workspace rule with the same key wins over the bundled one. A rejected config (`valid: false`, `config_invalid`, `payload_too_large`) is reported as a `systemMessage` with the first three `<path>: <reason>` entries, never retried, and rules still sync; a `version_conflict` push is reported as deferred. The REST URL comes from `TASK_ORCHESTRATOR_API_URL`, then `apiUrl` in a `client.json` beside the located project config, then `apiUrl` in the user-level `client.json`; the first that resolves wins, and none means the hook does nothing. The token needs `read`, `write-config` and `write-docs`. Project-mode `/task-orchestrator:init` writes the project-level file, honoured only as a bare loopback origin (`http`/`https`, no credentials, host `localhost`, `127.0.0.0/8` or `[::1]`, an optional port, and no path, query or fragment) so a repo file can neither redirect the bearer token nor choose the request path; `/task-orchestrator:init --user` writes the user-level file, which is unrestricted. In a linked worktree, when the `client.json` beside the worktree's own config yields no usable loopback URL, the hooks also read `.taskorchestrator/client.json` in the main checkout, under the same loopback rule. `AGENT_CONFIG_DIR` pins both files to the directory it names. The project-level `client.json` assumes a single-user machine: any directory above the working directory that holds a `config.yaml` and a `client.json` can point the hooks, with the bearer token, at a loopback port. On a shared host, set `TASK_ORCHESTRATOR_API_URL`, which wins over every file, and do not work under a directory another user can write. Requests share one time budget; unfinished work is reported as `<N> deferred to next session`. A server rule customized on purpose shows `kept unrecognized server copy: <key>` at every session start; to stop that, put the rule in the workspace's `.taskorchestrator/rules/` so the workspace copy owns the key. It is fail-open: errors produce at most a one-line note. It is not gated by execution mode and injects no context.

Details: [config-format.md](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#rule-text) and [fleet-deployment.md](../fleet-deployment.md).

---

### Pre-Plan

**Script:** `pre-plan.mjs`. **Event:** `PreToolUse` on `EnterPlanMode` — when Claude enters plan mode.

**What it injects:** An instruction to invoke the `pre-plan-workflow` internal skill.

**Effect:** Before writing a plan, the agent checks MCP state — existing items, note schemas, gate requirements. This sets a "definition floor" so the plan does not duplicate tracked work or ignore schema constraints. Gated like the other context hooks: `{}` in a headless iteration, a subagent or a director session, and when `orchestration.mode` is `off`.

### Post-Plan

**Script:** `post-plan.mjs`. **Event:** `PostToolUse` on `ExitPlanMode` — when the user approves a plan.

**What it injects:** An instruction to invoke the `post-plan-workflow` internal skill.

**Effect:** After plan approval, the agent materializes MCP items from the plan — creates work trees, wires dependencies, fills queue-phase notes — before implementation begins. Items exist in the MCP before any code is written. Gated like Pre-Plan.

### Plan Capture

**Script:** `plan-capture.mjs`. **Event:** `PostToolUse` on `ExitPlanMode`, after Post-Plan.

**What it does:** stashes the just-approved plan as one `docs_write` `upsert` of kind `plan` over the REST API, so `post-plan-workflow` can materialize items from the stored document instead of the model re-typing the plan body into note text. The token needs `write-docs`.

**Inert when:** no REST API URL resolves (stdio and local setups). It is not gated by execution mode, injects no context, and never blocks plan approval: any error or non-2xx answer exits silently.

### Skill Enforcement

**Script:** `skill-enforcement.mjs`. **Event:** `PreToolUse` on `notes_write`.

**What it does:** for each `ops[]` element with `op: "upsert"`, suggests loading the skill a note's schema entry names (`skill`) when the body is too thin to reflect that skill's output (a default floor of 200 characters, scaled down for a note whose schema `maxLength` is small). The `skill` and `maxLength` facts come from the server, not from `config.yaml`: one `config` op `effective` read per session, cached in a session marker, and the item's `items_read` `select: "schema"` view per op. It is advisory only (it never blocks the call) and suggests at most once per (session, item, key).

**Inert when:** no REST API URL is configured with a located config, in which case it says so once per session in a `systemMessage` and then stays silent. It is not gated by execution mode or `orchestration.mode`: it checks the claimed item's own writes, whoever runs them.

### Subagent Start

**Script:** `subagent-start.mjs`. **Event:** `SubagentStart` — any subagent launched via the `Agent` tool.

**What it injects:** The full Agent-Owned-Phase Protocol (see below) — but only for a phase-owner
subagent (`agent_type` resolving to `implementer` or `reviewer`, bare or plugin-qualified, e.g.
`task-orchestrator:implementer`). Any other agent type (`general-purpose`, `Explore`, `Plan`, a
project-local research agent, etc.) gets no output at all. An `agent_type` of exactly
`workflow-subagent` also gets no output at all, even if that dispatch happens to name an
`implementer`/`reviewer` seat internally — Claude workflow agents follow their own script-driven
transition logic instead of this protocol (see the `workflow-authoring` skill). Malformed or empty
stdin fails open (no output), the same as any other unrecognized `agent_type`. See "Execution modes"
below.

**Effect:** A phase-owner subagent knows to defer to whatever seat its own dispatch prompt assigned it: only an **entry seat** (the seat that starts the item's current phase), or a single agent that owns the whole phase with no seat named, sends `advance` op `transition` with `trigger: "start"` to enter its phase, and it reads the item's role first. A non-entry or read-only seat never calls `advance` at all — it does its assigned work and fills its own notes without transitioning the item. When it does own the transition, it fills notes using the `guidanceKey` loop, commits changes, and returns without calling `complete`. The orchestrator handles terminal transitions. A non-phase-owner subagent dispatched to work on an item still needs the protocol included in its dispatch prompt explicitly — it is not auto-injected.

**Subagent Discipline:** the same injection ends with a short discipline list — commit before returning, stay in scope, notes are the report, and leave nothing running (no background command may outlive the subagent's return, and filesystem searches stay bounded to the repo/worktree and known caches, never `/` or a drive root). The `implementer` and `reviewer` agent definitions carry the leave-nothing-running rule too, so a dispatch that bypasses the hook still sees it.

### Phase-Guard Record

**Script:** `phase-guard-record.mjs`. **Event:** `PostToolUse` on `advance` and `items_read` — fires in the main session and inside subagents.

**What it does:** Acts only when the hook input carries `agent_id` (i.e. it fired inside a subagent call) and only when a REST API URL is configured, and records which items the subagent entered its own phase for, into a per-(session, agent) state file under `os.tmpdir()/task-orchestrator/`. A seat enters an item in one of two ways:

1. **An `advance` result.** Every `results[i]` element is read through the 4.0 envelope; an element is recordable when it is `ok: true` with a `newRole` (the role entered, kept in `marker.enteredRoles[itemId]`). A `gate_blocked` answer is never recorded: it means a required note is missing, and it says nothing about the role the seat entered. Every other failure (`not_claim_holder`, `resource_unavailable`, `dependency_unmet`, `not_found`, and so on) means the transition never touched the item.
2. **A context read.** A phase-owner seat (`implementer` or `reviewer`, from `agent_type`) reads the item's role before `start` and sends `start` only from the role just before its phase, so a seat already in its phase sends no `start` at all. Its FIRST item-mode `items_read` `context` read is recorded when the item's role is the one that seat owns and nothing is recorded yet; the recorded id is the requested `itemId`, and only the first read counts, so reading a sibling or parent that also sits in `work` adds no false block.

A call that carries a non-empty top-level `seatToken` belongs to a run seat and is never recorded: run seats do not return through the SubagentStop check below the way an ordinary subagent dispatch does, so recording them would only produce false blocks later. A 3.x-shaped result records and hints nothing and shows the legacy-shape warning once per session.

**Effect:** Feeds the SubagentStop guard below the list of items to re-check when this subagent tries to stop, along with the role each item was entered in. Fail-open and always silent (`{}` on stdout, exit 0) — this hook never blocks `advance` or a read. It is `{}` in a headless iteration, and it is not gated by `orchestration.mode` or a director session.

### Phase Guard (SubagentStop)

**Script:** `phase-guard.mjs`. **Event:** `SubagentStop` — every subagent stop, including Claude Code's own internal agents (prompt suggestions, etc.), which also fire this event.

**What it does:** For every item the Phase-Guard Record hook recorded for this subagent, reads the gate with `items_read` `select: "context"` over `POST /api/v1/ops/items_read` and checks `gateStatus`. If a `work`- or `review`-phase item still has required notes missing (`gateStatus.missing` non-empty), the hook blocks the stop with a reason naming the item, the missing note keys, and — when present — the first missing note's `guidanceKey` or `skillPointer`.

**Seat awareness:** the hook input's `agent_type` field (same SubagentStop field `subagent-start.mjs` already reads) identifies which seat is stopping. `phaseOwnerSeat(agentType)` (`execution-mode.mjs`) resolves it to `implementer` (owns `work`), `reviewer` (owns `review`), or `null`. When the seat is known and the item's `role` does not match the role that seat owns, the item is still fetched but never named as a blocker — a reviewer stopping on a work-phase item, or an implementer stopping on a review-phase item, is never demanded notes outside its own seat. Independently, keys in `TEST_AUTHOR_OWNED_KEYS` (currently just `test-manifest`) are dropped from `missing` before the block decision unless `isTestAuthorAgentType(agentType)` is true — those notes belong to a separately dispatched test-author seat (see the `needs-test-author` trait) and must never be demanded of the implementer or reviewer. When `agent_type` is absent or unrecognised (an older Claude Code build, or a non-seat helper type like `task-orchestrator:implementer-helper`), the guard falls back to a role-agnostic check — it still applies the test-author key filter, just without the seat-to-role check. Because the DTO's `guidanceKey`/`skillPointer` describe only the first raw missing key, the block reason surfaces that hint only when the first key survives the test-author filter.

**Independence violations:** the gate's `violations` (present only when the resolved schema declares `independent_of` somewhere; see [config-format.md](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md) → "Independence") can also block a stop, but the hook only treats it as a block reason when `gateStatus.missing` is EMPTY, i.e. `canAdvance === false` is caused solely by a `reject`-mode independence violation, not by a required-note gap. A `warn`-mode item never drives a block this way. When it does trip, only the stopping agent's OWN seat's non-waived findings are named in the block reason (`key (constraint)`, e.g. `test-manifest (same_actor)` — never an actor id).

**Entered-role gating:** when the Phase-Guard Record marker has an entered role recorded for an item (`marker.enteredRoles[itemId]`), the item is checked against the guard only while the item's *current* `role` still equals that recorded entered role. Once a later seat has advanced the item past the phase this agent entered, the roles diverge and the item is skipped entirely — it is never named as a blocker for notes belonging to a phase this agent never owned. An item with no recorded entered role falls back to the role-agnostic check.

**Effect:** Sends the subagent back to fill required notes instead of letting it end its turn with an incomplete phase. Capped at **2 blocks per subagent** (`agent_id`) — beyond the cap the guard steps aside even with notes still missing, so a stuck subagent is never looped forever. Requires `TASK_ORCHESTRATOR_API_URL` and, in bearer mode, a `TASK_ORCHESTRATOR_API_TOKEN` with `read` capability (same REST dependency as `config-sync.mjs`, see [fleet-deployment.md](../fleet-deployment.md)); fails open — no block, silent `{}`, exit 0 — on a missing API URL, a missing/unreadable state file, a non-2xx or errored gate fetch for an item, or any other error. `workflow-subagent` agents get `{}`, and a headless iteration gets `{}`. With a located config and no REST API URL it emits one `systemMessage` per session saying it is inert.

**Known limitation:** The guard only engages for subagents that either enter their phase with `advance` `transition` `start` or, as a phase-owner seat, make an item-mode `context` read of an item already in their phase. A subagent dispatched under an orchestrator-owns-transitions dispatch contract that does neither has nothing recorded by the Phase-Guard Record hook, so the guard stays inert for it.

### Retro Trigger

**Script:** `retro-trigger.mjs`. **Event:** `PostToolUse` on `runs_write`.

**What it does:** a retrospective is owed per closed run of the project root that is not yet marked `retro`. On a successful `close` op, in the main session with a REST API URL and a project `rootId`, the hook lists the root's recently closed, unmarked runs (`runs_read` `list` with `unmarked: "retro"`, the last 24 hours), reads each run's terminal member count from its frontier (at most 5 per call), and either dispatches a retrospective or nudges. In `dispatch` mode and with at least `dispatchThreshold` terminal members it first writes the run's `retro` mark with `runs_write` `mark`; the first mark wins, so two sessions never both dispatch. Otherwise it injects the nudge naming `/session-retrospective --run <runId>`. Without a REST API URL or a project `rootId` it only records the close in the session marker for the Stop backstop. The settings are `extensions.claude-code.retrospective.mode` (`nudge` default, `dispatch`, `off`) and `dispatchThreshold` (default 3); there is no cooldown and no tmp marker file.

**Inert when:** the session is a headless iteration, `retrospective.mode` is `off`, or the call is a failed or non-`close` result. A subagent records the close for the main session and never nudges. It is not read against `orchestration.mode`.

### Retro Backstop

**Script:** `retro-backstop.mjs`. **Event:** `Stop`.

**What it does:** the same closed-run evaluation as Retro Trigger, so a run closed elsewhere (another session, a subagent, a tool the trigger never saw) still gets its retrospective: with a REST API URL and a project `rootId` it may dispatch (after winning the `retro` mark) as well as nudge, and the output is a `block` decision. Without REST it nudges once per session, only about closes the trigger recorded in this session, and never marks or dispatches.

**Inert when:** the session is a headless iteration, `retrospective.mode` is `off`, or `stop_hook_active` is set. It is not gated by subagent or director status.

### Director Guard

**Scripts and events:** `director-guard.mjs`, `PreToolUse` on `Skill|Read|Edit|Write|Bash|Agent|Grep|Glob|NotebookEdit` (`onFailure: block`), and `director-context.mjs`, `SessionStart` with the `compact` matcher.

Invoking `/task-orchestrator:director` puts the main session in director mode: the guard sees the `Skill` call and writes a per-session marker, and `/task-orchestrator:director off` removes it. While the marker exists, the guard denies `Read`, `Edit`, `Write`, `Grep`, `Glob` and `NotebookEdit`; denies `Bash` unless every segment of the command starts with `git status`, `git log`, `git branch`, `gh pr view`, `gh pr list` or `docker ps` (no redirection, substitution, braces, globs or backslashes, and no other chained command), where `git log`, `git status` and `git branch` take only the flags on their listing flag allow-list, so none prints a patch, a diff or file contents; and denies an `Agent` dispatch unless it passes `run_in_background: true`. Each deny reason starts with `Director altitude rule:` and names the remedies: a background `Agent`, `SendMessage` to a coordinator, or `off`. `SendMessage` and `ListAgents` are never denied, calls from subagents (`agent_id` present) are exempt, and a headless ralph iteration is never guarded. Under the marker the guard fails closed: an internal error is a deny. Without the marker it does nothing.

After a compaction, `director-context.mjs` re-injects a two-sentence reminder of the rule in a director session (`{}` without the marker, in a subagent or in a headless iteration). The server enforces the same rule for MCP: the director connection (`/mcp/director`, or `MCP_TOOL_PROFILE=director`) has no note-body read. `claude --agent director` is an optional hardened entry (`agents/director.md`) whose tool list already excludes file and shell tools.

### Director Watch

**Script:** `director-watch.mjs`. **Event:** `PostToolUse` on `runs_read`, director sessions only (it acts only under the marker).

**What it does:** two jobs, each deduplicated per session. A `decisions` result listing a decision with status `open` that this session has not pushed yet gives one `Decision waiting:` directive naming each new id and telling the director to call `PushNotification` once (the server records a decision but pushes nothing). A `get` result (the run view) for a `closed` run with no `retro` mark gives the retrospective nudge or dispatch directive once per run; it stays silent there when the REST path is configured, because Retro Trigger owns the mark and the dispatch then.

**Inert when:** the session is a headless iteration, the call comes from a subagent, there is no director marker, the result failed, or `retrospective.mode` is `off` (no retrospective directive).

### Execution modes

No documented Claude Code hook-input field distinguishes an interactive session from a headless
`claude -p` run, so the `ralph` skill's drain loop (`scripts/ralph-loop.mjs`) sets an explicit
signal: it spawns every iteration (the initial spawn and every `--resume` continuation) with
`TASK_ORCHESTRATOR_MODE=headless-iteration` in the child's environment. `hooks/execution-mode.mjs`
exposes `isHeadlessIteration()` (reads that var), `isPhaseOwnerAgentType(agentType)` (matches
`implementer`/`reviewer`, bare or plugin-qualified), the seat-returning `phaseOwnerSeat(agentType)`
(`implementer`/`reviewer`/`null`), `isTestAuthorAgentType(agentType)` (matches `test-author`, bare or
plugin-qualified) and `suppressionReason(hookInput, env)`, the shared gate of the four context hooks
(`headless`, then `subagent`, then `director`). The table summarizes the gates that matter for a
subagent or a headless iteration; the full per-hook table is the Mode gating table in
[`hooks/README.md`](../../../claude-plugins/task-orchestrator/hooks/README.md).

| Hook | Headless ralph iteration | Interactive subagent (`agent_id` present) |
|---|---|---|
| Orchestration Context | exits silently, no output (iterations get the appended iteration system prompt instead) | `{}` (the shared gate) |
| Subagent Start | exits silently, no output | injects the protocol only for `implementer`/`reviewer` agent types; every other type gets no output |
| Retro Trigger | emits `{}` before reading/writing the session marker | never emits a nudge/dispatch directive itself; it records the close for the main session's Stop backstop |
| Retro Backstop | emits `{}` | unaffected — this hook only fires for the main session's own turn |
| Phase-Guard Record | emits `{}` before recording anything | acts only inside a subagent |
| Phase Guard (SubagentStop) | emits `{}` before any gate fetch | unaffected |
| Director Guard | `{}` (no marker write, no denial) | `{}` (exempt; no marker write) |

Ralph iterations never dispatch subagents in the first place (see the `ralph` skill and the
appended `skills/ralph/iteration-system-prompt.md`), so the SubagentStart/Phase-Guard rows are belt-and-suspenders —
the headless gate exists so that invariant is not silently load-bearing.

Hooks intentionally NOT gated by execution mode: Session Start (informational, including the
registration self-check above), Config Sync and Plan Capture (they must still sync in every mode),
and Skill Enforcement (a correctness check on the claimed item's own writes, independent of who or
what is running). There is no attribution hook any more: a 4.0 server rejects a body `actor`, and
the caller's identity is the transport principal.

---

## Runs, director mode and ralph

A **run** is a server-side unit of work (`runs_write` / `runs_read`, see the Runs category of the [API reference](../api-reference.md)). The plugin has three consumers.

### run-wave on server runs

`/task-orchestrator:run-wave` resolves a frontier of ready items into a run plan (`scripts/run-planner.mjs`), then:

- opens the server run with `runs_write` `open` (answering a coordinator token once), assigns one seat per schema-seat stage with `assign` (each answering a `seatToken` once), reports the run's usage once with `advance` `heartbeat`, ends every seat with `end_seat` (with a post-run sweep) and closes the run with `close` (`completed`, `failed`, `cancelled` or `abandoned`);
- delivers each seat token through a token file, `<scratchpad>/run-wave/<runId>/tokens/<short>.<seat>.token`, never through prompt text, notes, logs or the plan; a seat sends its own token as the top-level `seatToken` of every call;
- reads run state from the server (`runs_read` `get`, `frontier`, `seats`), not from documents: the `run/<runId>` plan and state documents are no longer written;
- audits note presence after the seats return with the helper subcommand `notes-audit`, which lists a required stage-owned note with no observed row as `missing` and never an optional one; who wrote a note is not audited, because the server enforces each seat's lane (`note_owned_by_other`, `seat_forbidden`);
- executes the plan through the `implement-wave` Workflow (Method A) or direct `Agent` dispatch (Method B).

The plan id (`r-…`) names the scratch directory and the run's label; the server run id (a UUID) is bound into the plan with `bind-run`. Details: `skills/run-wave/SKILL.md` and `references/post-run.md`.

### Director mode and coordinator tiers

`/task-orchestrator:director` shows open runs, their frontier, open decisions and rollups through the director connection and answers or withdraws decisions; the guard hooks above hold it at altitude. A director never runs a wave itself: a coordinator does. `scripts/coordinator-tiers.mjs` (`selectTier`, `launchProfile`) picks where the coordinator lives:

| Tier | Needs | Coordinator |
|------|-------|-------------|
| 1 | `start_session` and `SendMessage` | A separate desktop session |
| 2 | `Workflow` | A background coordinator agent; the director makes the one `Workflow` call with `args.altitudeSafe: true` |
| 3 | The `claude` CLI | A headless `claude -p --allowedTools Workflow --max-budget-usd <X>` process (never `--bare`) |
| 4 | Nothing above | None: the run executes inline, with an altitude warning |

An altitude-safe `implement-wave` result carries counts, statuses, commit SHAs, file lists and note keys, with reasons cut to 120 characters. Details: `skills/director/references/coordinator-tiers.md`.

### ralph on runs

`/task-orchestrator:ralph` emits the `scripts/ralph-loop.mjs` invocation. The loop needs the REST API (`TASK_ORCHESTRATOR_API_URL`) and a project root, and opens ONE run per loop (a budget only from `--run-budget-tokens`, `--run-budget-usd` and `--max-attempts`, never a default cap). Each iteration selects the oldest ready item with `items_read` `next`, adds it to the run, assigns an `implementer` seat, claims the item under that seat's token and spawns a fresh `claude -p --worktree`; the token reaches the iteration only as a `0o600` file path (`TASK_ORCHESTRATOR_SEAT_TOKEN_FILE`). The seat is ended on every outcome, and the run is closed on exit. The iteration signals its result with a `RALPH_OUTCOME:` marker. There is no `--actor` flag.

### The retrospective on run marks

A retrospective is owed per closed run that has no `retro` mark. `runs_write` `close` triggers Retro Trigger, Retro Backstop checks at `Stop` via `runs_read` `unmarked: retro`, and the first `mark` wins. `/task-orchestrator:session-retrospective --run <runId>` takes the run's members from its ended seats and frontier. See [Self-Improving Workflow](self-improving-workflow.md).

---

## User Skills

Skills are invoked as slash commands in any Claude Code session:

| Command | Description |
|---------|-------------|
| `/task-orchestrator:work-summary` | Insight-driven dashboard of active work, blockers, open runs and recommended next actions |
| `/task-orchestrator:create-item` | Create a tracked work item with smart container anchoring and tag inference |
| `/task-orchestrator:quick-start` | Interactive onboarding — teaches by doing, adapts to empty or populated workspaces |
| `/task-orchestrator:init` | Set up a project root, config and the two MCP entries for this directory, or `--user` for a personal root that serves every unconfigured directory |
| `/task-orchestrator:manage-schemas` | Create, view, edit, delete, and validate note schemas in config.yaml |
| `/task-orchestrator:status-progression` | Navigate role transitions; shows current gate status and the correct trigger |
| `/task-orchestrator:dependency-manager` | Visualize, create, and diagnose dependency graphs between work items |
| `/task-orchestrator:batch-complete` | Complete or cancel multiple items at once — close out features or workstreams |
| `/task-orchestrator:run-wave` | Plan and run a multi-item wave on a server run (Method A via the `implement-wave` Workflow, or Method B direct-Agent fallback) |
| `/task-orchestrator:ralph` | Emit the `ralph-loop.mjs` invocation for a headless queue drain — one run per loop, one `claude -p --worktree` per iteration |
| `/task-orchestrator:session-retrospective` | Analyze an implementation run (`--run <runId>`), record cross-session trends, and graduate recurring patterns into improvement proposals |
| `/task-orchestrator:review-proposals` | Triage pending improvement proposals — accept, reject, or defer each and carry out the disposition |
| `/task-orchestrator:adopt-project-scope` | Migrate an already-populated, unscoped database in place to the project-scoping convention |
| `/task-orchestrator:director` | Director mode: a board of open runs, frontier, decisions and rollups; answer decisions; delegate every read; `off` leaves |
| `/task-orchestrator:configure-server` | Configure how the server runs and is reached — transport, REST API, port publishing, config mounts, config-sync |

---

## Internal Skills

These are triggered by hooks and the orchestration context, not invoked directly by users:

| Skill | Triggered by | Purpose |
|-------|-------------|---------|
| `pre-plan-workflow` | Pre-plan hook (EnterPlanMode) | Gather MCP state, check schemas, set definition floor before plan is written |
| `post-plan-workflow` | Post-plan hook (ExitPlanMode) | Materialize items from approved plan — work trees, dependencies, queue-phase notes |
| `schema-workflow` | Orchestration context | Guide items through schema-defined note gates during implementation |
| `orchestrate` | Orchestration context | On-demand tier classification, delegation model table and phase-owner dispatch rules |

---

## Workflows (Claude Code only)

The front door for these scripts is the `/task-orchestrator:run-wave` skill: it plans a wave of ready items with
the `run-planner` helper, opens the server run, launches `implement-wave` (Method A, the Workflow tool) or dispatches
the same run plan with the Agent tool (Method B), and on the result verifies, records provenance and advances.

`implement-wave` (`claude-plugins/task-orchestrator/workflows/implement-wave.js`) is a Workflow-tool
script: it drives one work item's seats through their phases in order, and can run several items
concurrently, following a run plan the front door builds before the script starts.

It is invoked by the front door, never bare — a bare run returns `{started: false}` rather than
doing any work, so calling the script directly without going through that entry path is not a
supported way to drive a wave.

Each seat the script dispatches fetches its own rule text by key via
the [`config`](../api-reference.md#config) tool's `rules` op rather than carrying that text inline, and reads
its item's resolved schema via [`items_read`](../api-reference.md#items_read) (`schema` select)
rather than assuming note requirements.

Two of the seats the script dispatches are the `planner` and `test-author` agent definitions under
`claude-plugins/task-orchestrator/agents/` — a queue-phase planning seat and a blind, independent
test-authoring seat, respectively.

The shipped workflows, each launched on its own terms:

- `audit` — read-only report + proposal; materializing its findings goes through [create-item](../../../claude-plugins/task-orchestrator/skills/create-item/SKILL.md#from-a-workflow-findings-proposal). Cost scales with the preset: `quick`, `standard` or `full`; `full` runs roughly 90 minutes and about 30M tokens.
- `retro-analysis` — two modes. `deep` is invoked via `/session-retrospective --deep`; `audit` is a standalone sweep whose consumer is the session-retrospective skill's "Audit sweep" paragraph (present the result, then apply Step 6's create and retire shapes after the user confirms).
- `review-wave` — independent review lanes over an item's owned-file diff, aggregated into one verdict in args order. Shipped; the `run-wave` front door does not build its lanes yet (args are hand-built until C1-productize).

---

## The Plan-Mode Pipeline

This is the core automation the plugin provides:

```
User describes a feature
        |
        v
Enter Plan Mode
        |
Pre-plan hook fires
  → agent calls pre-plan-workflow skill
  → checks existing MCP items, reads note schemas, confirms definition floor
        |
        v
Agent writes plan (with MCP awareness)
        |
User approves plan
        |
Post-plan hook fires (plan-capture stashes the plan document)
  → agent calls post-plan-workflow skill
  → items_write create_tree for hierarchy
  → deps_write for cross-item edges
  → notes_write for queue-phase notes
        |
        v
Implementation begins
  (items exist, dependencies are set, gates are configured)
```

Without the pipeline, agents write plans that never get tracked. With it, every plan becomes a structured work tree with dependency ordering and schema-driven documentation requirements that survive across sessions.

---

## The Agent-Owned-Phase Protocol

When a subagent is dispatched via the `Agent` tool, the subagent-start hook injects this protocol automatically. Each subagent owns exactly one phase: it enters that phase, fills required notes, then returns without advancing further. The hook cannot see the dispatch prompt's own seat assignment, so the injected wording is conditional rather than a blanket instruction: it tells the agent to follow its dispatch prompt's seat assignment when one is named, and only an **entry seat** (the seat that starts the item's current phase), or a single agent that owns the whole phase with no seat named, sends `advance` `transition` `start` — a non-entry seat or a **read-only** agent never calls `advance` at all. When the dispatch prompt carries a `seatToken`, the seat sends it as a top-level field of every call.

### Protocol steps

**1. Read the role, then enter the phase (entry seat / sole phase owner only):**

```json
items_read(select="context", itemId="<item-UUID>")
```

A `start` is not idempotent: a second `start` moves the item forward whenever its gate passes, so retrying it can advance the item past your phase. Send `start` only when the role is the one just before your phase (`queue` for a work-phase seat, `work` for a review-phase seat):

```json
advance(ops=[{ "op": "transition", "itemId": "<item-UUID>", "trigger": "start" }])
```

This moves the item into the subagent's phase (queue to work, or work to review). The result element carries `newRole`, `expectedNotes` and `guidanceKey`. When the context already shows your phase, do not send `start`: take the guidance from that context and begin. For any other role (blocked, terminal, or a phase past yours), do not send `start`: stop and report the role.

**Reading the answer.** Every result is an envelope: branch on `ok` and on the element's `error.code` and `error.kind` (and `retryAfterMs` when present). A `gate_blocked` answer means a required note is missing; it says nothing about where the item is. Stop and report the missing notes. A transient code (`resource_unavailable`, `claim_held`) is not an already-in-phase signal: stop, report the code and `retryAfterMs`, and do not retry or spin. Any other code means the item is not in your phase: stop and report it.

A non-entry seat or a read-only agent skips this step entirely — it does its assigned work and fills only the notes its own seat owns, without transitioning the item.

**2. Read guidance:**

`guidanceKey` names the first unfilled required note with guidance; resolve its text via `items_read(select="schema", itemId=..., fields=["guidance"])`. If `skillPointer` is set, load that skill via the `Skill` tool first.

**3. Do work and fill the note:**

```json
notes_write(ops=[{
  "op": "upsert",
  "itemId": "<uuid>",
  "key": "<note-key>",
  "role": "<phase>",
  "body": "<content>"
}])
```

If `expectedNotes` lists only this note, this was the only note — skip to step 6.

**4. Get next guidance:**

```json
items_read(select="context", itemId="<item-UUID>")
```

Returns the updated `guidanceKey` and `gateStatus`.

**5. Check if done:**

If `guidanceKey` is absent, all required notes are filled. Proceed to step 6. Otherwise go back to step 2.

**6. Return results:**

Commit all changes with a descriptive message. Report: (1) files changed with line counts, (2) test results summary, (3) any blockers. The orchestrator handles the terminal transition.

### Key rules

- Each agent owns exactly one phase — do not advance beyond it
- Do NOT send `advance` with `trigger: "complete"` — the orchestrator handles terminal transitions
- Commit before returning — the orchestrator needs committed changes to push and create a PR
- When the REST API is configured, the SubagentStop phase guard (see Hooks above) is a backstop for this protocol: stopping with required notes still missing on a `work`/`review` item can send you back (up to twice) with the missing keys named, instead of letting an incomplete phase through silently

---

## Example: Planning and Implementing a Feature

This walkthrough shows the full pipeline from description to completion.

**1. User:** "Add rate limiting to the API."

**2. Agent enters plan mode** — the pre-plan hook fires. The agent calls `pre-plan-workflow`, checks for existing rate-limiting work, reads the `feature-implementation` schema requirements, and sets the definition floor.

**3. Agent writes a plan** — 4 subtasks: middleware design, Redis integration, error responses, tests. Plan is written with awareness of existing work and schema gates.

**4. User approves** — the post-plan hook fires. The agent calls `post-plan-workflow` and materializes the tree with one all-or-nothing `items_write` op:

```json
items_write(ops=[{
  "op": "create_tree",
  "root": { "title": "Rate limiting", "type": "feature-implementation", "priority": "high" },
  "children": [
    { "ref": "middleware", "title": "Middleware design", "type": "feature-task" },
    { "ref": "redis",      "title": "Redis integration", "type": "feature-task" },
    { "ref": "errors",     "title": "Error responses",   "type": "feature-task" },
    { "ref": "tests",      "title": "Integration tests", "type": "feature-task" }
  ],
  "deps": [
    { "from": "middleware", "to": "tests" },
    { "from": "redis",      "to": "tests" }
  ]
}])
```

A `blocks` edge reads "from blocks to": the middleware and Redis items block the tests item. The full `create_tree` grammar is in the [API reference](../api-reference.md#items_write); `post-plan-workflow` renders it as a validated call. Queue-phase notes (feature-summary / task-scope) are filled before dispatching.

**5. Agent dispatches implementation subagents** — each subagent receives one child item UUID. The subagent-start hook injects the Agent-Owned-Phase Protocol automatically.

**6. Each subagent:**
- Reads the item's role with `items_read` `context`, then sends `advance` `transition` `start` — queue to work
- Reads `guidanceKey` (resolved via `items_read` `select: "schema"`) — fills `implementation-notes` and `session-tracking` notes
- Commits changes
- Returns to the orchestrator

**7. Orchestrator advances each item** — sends `advance` `transition` `start`. If `newRole` is `review` (schema has review-phase notes): dispatches a reviewer or fills review notes inline, then advances review to terminal. If `newRole` is `terminal` (no review phase): item is complete.

**8. Parent cascade** — when all four children reach terminal, the root cascades to terminal only if its schema lifecycle is `auto`; a `manual`, `permanent` or lifecycle-free parent is left alone and nothing is reported. The `cascades` field in the `transition` result lists each parent that applied or was suppressed. A terminal cascade into a parent in `review` is always suppressed (`reason: review-pending`): that parent leaves `review` only by an explicit advance. See the Cascades section of the [API reference](../api-reference.md#advance).

---

## Note Schema Integration

The plugin works best when combined with note schemas (Tier 3). The pre-plan hook reads your `config.yaml` schemas to inform the definition floor. The subagent-start protocol uses `guidanceKey` (resolved via `items_read` `select: "schema"`) from those schemas to tell agents exactly what to write.

See [Note Schemas](note-schemas.md) for schema setup.

---

## When to Level Up

**Signal:** You want Claude to operate as a full workflow orchestrator — planning, delegating, tracking, and reporting — rather than implementing directly.

**Next:** [Orchestration Mode](orchestration-mode.md) — the tier-aware, delegation-based operation the plugin's orchestration context delivers.
