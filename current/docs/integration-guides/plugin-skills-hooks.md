# Tier 4: Plugin — Skills and Hooks

## What You Get

- Automatic hooks that fire on session start, plan mode entry, plan approval, subagent launch, and (when the REST API is configured) subagent phase-gate enforcement
- 7 user-invocable skills as `/task-orchestrator:*` slash commands
- 3 internal skills that power the plan-mode pipeline
- The Agent-Owned-Phase Protocol injected into every phase-owner subagent automatically (see "Execution modes" below for exactly which subagents that covers)

## Prerequisites

- [Claude Code](https://claude.ai/code) CLI installed
- [Tier 1: Bare MCP](bare-mcp.md) setup complete

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

---

## Hooks

Hooks fire automatically — no invocation needed after installation.

### Session Start

**Event:** `SessionStart` — every new Claude Code session.

**What it injects:** The 13-tool surface (`manage_items`, `query_items`, `manage_notes`, `query_notes`, `manage_dependencies`, `query_dependencies`, `advance_item`, `get_next_item`, `get_blocked_items`, `get_next_status`, `create_work_tree`, `complete_tree`, `get_context`), the role lifecycle (queue → work → review → terminal), and a session tip to call `get_context()` to see active and stalled items.

**Effect:** The agent knows the MCP tool names and workflow conventions from the first prompt, without any CLAUDE.md instructions.

**Registration self-check:** The plugin's other PreToolUse/PostToolUse hooks — skill enforcement, actor attribution, the retro trigger, and Phase-Guard Record (below) — fire only when an MCP tool call's server segment — the middle part of `mcp__<server>__<tool>` — contains `task-orchestrator`. Session Start reads discoverable MCP registrations (project `.mcp.json`, and `~/.claude.json`'s top-level `mcpServers` plus its `projects[<cwd>].mcpServers`) and, for any orchestrator registration whose key omits that token, appends a `## Hook Registration Check` section naming the offending key and the fix (rename the key to include `task-orchestrator`, e.g. `mcp-task-orchestrator`). The check is purely diagnostic and fail-open: any read or parse error simply omits the section, and it never blocks session start. A registration is recognized as "the orchestrator" when its key, `url`, or `command` mentions `task-orchestrator`, or one of its args is an image-style reference such as `jpicklyk/task-orchestrator` (filesystem-path args are ignored); an HTTP registration whose key and URL both omit it is undetectable.

**Plugin version freshness check:** dev-checkout only. Session Start walks up from `AGENT_CONFIG_DIR` (if set) then `cwd` looking for a checked-out `claude-plugins/task-orchestrator/.claude-plugin/plugin.json` — mirroring the same walk pattern used to find `.taskorchestrator/config.yaml`, so worktrees under `.claude/worktrees/<name>/` still find the checkout root. If found, it compares that file's `version` against the `plugin.json` of the plugin actually running the hook (resolved via `CLAUDE_PLUGIN_ROOT` when the harness sets it, else relative to the hook script's own file location). A mismatch appends a `## Plugin Version Drift` section naming both versions and pointing to `claude-plugins/CLAUDE.md` → "Plugin Discovery and Cache Refresh". Silent when no dev checkout is found, when either `plugin.json` can't be read or parsed, or when the versions match — like the registration self-check, this is purely diagnostic and never blocks session start.

### Pre-Plan

**Event:** `PreToolUse` on `EnterPlanMode` — when Claude enters plan mode.

**What it injects:** An instruction to invoke the `pre-plan-workflow` internal skill.

**Effect:** Before writing a plan, the agent checks MCP state — existing items, note schemas, gate requirements. This sets a "definition floor" so the plan does not duplicate tracked work or ignore schema constraints.

### Post-Plan

**Event:** `PostToolUse` on `ExitPlanMode` — when the user approves a plan.

**What it injects:** An instruction to invoke the `post-plan-workflow` internal skill.

**Effect:** After plan approval, the agent materializes MCP items from the plan — creates work trees, wires dependencies, fills queue-phase notes — before implementation begins. Items exist in the MCP before any code is written.

### Subagent Start

**Event:** `SubagentStart` — any subagent launched via the `Agent` tool.

**What it injects:** The full Agent-Owned-Phase Protocol (see below) — but only for a phase-owner
subagent (`agent_type` resolving to `implementer` or `reviewer`, bare or plugin-qualified, e.g.
`task-orchestrator:implementer`). Any other agent type (`general-purpose`, `Explore`, `Plan`, a
project-local research agent, etc.) gets no output at all. An `agent_type` of exactly
`workflow-subagent` also gets no output at all, even if that dispatch happens to name an
`implementer`/`reviewer` seat internally — Claude workflow agents follow their own script-driven
transition logic instead of this protocol (see the `workflow-authoring` skill). Malformed or empty
stdin fails open (no output), the same as any other unrecognized `agent_type`. See "Execution modes"
below.

**Effect:** A phase-owner subagent knows to defer to whatever seat its own dispatch prompt assigned it: only an **entry seat** (the seat that starts the item's current phase), or a single agent that owns the whole phase with no seat named, calls `advance_item(trigger="start")` to enter its phase; a non-entry or read-only seat never calls `advance_item` at all — it does its assigned work and fills its own notes without transitioning the item. When it does own the transition, it fills notes using the `guidanceKey` loop, commits changes, and returns without calling `complete`. The orchestrator handles terminal transitions. A non-phase-owner subagent dispatched to work on an item still needs the protocol included in its dispatch prompt explicitly — it is not auto-injected.

### Phase-Guard Record

**Event:** `PostToolUse` on `advance_item` — fires after every `advance_item` call, in the main session and inside subagents.

**What it does:** Acts only when the hook input carries `agent_id` (i.e. it fired inside a subagent call) and only when `TASK_ORCHESTRATOR_API_URL` is set. Parses the `advance_item` response and records the itemIds the subagent just entered its own phase for — a full UUID with either `applied === true` or `errorCode === "gate_blocked"` (the "already in phase" case) — into a per-(session, agent) state file under `os.tmpdir()/task-orchestrator/phase-guard-<key>.json`. Every other structured failure (`not_claim_holder`, `resource_unavailable`, `dependency_blocked`, `item_not_found`, etc.) means the transition never touched the item and is not recorded. A result is dropped before recording when its transition's `actor.parent` (read from the raw `tool_input`, either the batch `transitions[]` shape or the singular-sugar `{itemId, trigger, actor}` shape) starts with the literal prefix `workflow:` — those Claude workflow-script seats never return through the SubagentStop check below the way an ordinary subagent dispatch does, so recording them would only produce false blocks later.

Alongside each recorded itemId, the marker now also stores the **role the agent entered** for that item, in `marker.enteredRoles[itemId]` — `newRole` on an `applied: true` result, or `targetRole` on the already-in-phase `gate_blocked` case. A marker written before this field existed simply has no entry for an item, which the SubagentStop guard treats as its prior role-agnostic behavior.

**Effect:** Feeds the SubagentStop guard below the list of items to re-check when this subagent tries to stop, along with the role each item was entered in. Fail-open: no `agent_id`, no REST API configured, or any read/parse error → silent `{}` on stdout, exit 0 — this hook never blocks `advance_item` itself.

### Phase Guard (SubagentStop)

**Event:** `SubagentStop` — every subagent stop, including Claude Code's own internal agents (prompt suggestions, etc.), which also fire this event.

**What it does:** For every item the Phase-Guard Record hook recorded for this subagent, calls `GET /items/{id}/gate` (see [api-rest.md](../api-rest.md) §9) and checks `gateStatus`. If a `work`- or `review`-phase item still has required notes missing (`gateStatus.missing` non-empty), the hook blocks the stop with a reason naming the item, the missing note keys, and — when present — the first missing note's `guidanceKey` or `skillPointer`.

**Seat awareness:** the hook input's `agent_type` field (same SubagentStop field `subagent-start.mjs` already reads) identifies which seat is stopping. `phaseOwnerSeat(agentType)` (`execution-mode.mjs`) resolves it to `implementer` (owns `work`), `reviewer` (owns `review`), or `null`. When the seat is known and the item's `gateStatus.role` does not match the role that seat owns, the item is still fetched but never named as a blocker — a reviewer stopping on a work-phase item, or an implementer stopping on a review-phase item, is never demanded notes outside its own seat. Independently, keys in `TEST_AUTHOR_OWNED_KEYS` (currently just `test-manifest`) are dropped from `missing` before the block decision unless `isTestAuthorAgentType(agentType)` is true — those notes belong to a separately dispatched test-author seat (see the `needs-test-author` trait) and must never be demanded of the implementer or reviewer. When `agent_type` is absent or unrecognised (an older Claude Code build, or a non-seat helper type like `task-orchestrator:implementer-helper`), the guard falls back to today's role-agnostic behavior — it still applies the test-author key filter, just without the seat-to-role check. Because the DTO's `guidanceKey`/`skillPointer` describe only the first raw missing key, the block reason surfaces that hint only when the first key survives the test-author filter.

**Entered-role gating:** when the Phase-Guard Record marker has an entered role recorded for an item (`marker.enteredRoles[itemId]`), the item is checked against the guard only while the item's *current* `gateStatus.role` still equals that recorded entered role. Once a later seat has advanced the item past the phase this agent entered, `gateStatus.role` diverges from the recorded entered role and the item is skipped entirely — it is never named as a blocker for notes belonging to a phase this agent never owned. An item with no recorded entered role (e.g. one recorded by a marker written before this field existed) falls back to the prior role-agnostic behavior — the `BLOCKING_ROLES` and seat-filtering checks above, without the entered-role check.

**Effect:** Sends the subagent back to fill required notes instead of letting it end its turn with an incomplete phase. Capped at **2 blocks per subagent** (`agent_id`) — beyond the cap the guard steps aside even with notes still missing, so a stuck subagent is never looped forever. Requires `TASK_ORCHESTRATOR_API_URL` and, in bearer mode, a `TASK_ORCHESTRATOR_API_TOKEN` with `read` capability (same REST dependency as `config-sync.mjs`, see [fleet-deployment.md](../fleet-deployment.md)); fails open — no block, silent `{}`, exit 0 — on a missing API URL, a missing/unreadable state file, a non-2xx or errored gate fetch for an item, or any other error.

**Known limitation:** The guard only engages for subagents that enter their phase with `advance_item(trigger="start")` — the Agent-Owned-Phase Protocol below. A subagent dispatched under an orchestrator-owns-transitions dispatch contract, which never calls `advance_item` itself, has nothing recorded by the Phase-Guard Record hook, so the guard stays inert for it.

### Execution modes

No documented Claude Code hook-input field distinguishes an interactive session from a headless
`claude -p` run, so the `ralph` skill's drain loop (`scripts/ralph-loop.mjs`) sets an explicit
signal: it spawns every iteration (the initial spawn and every `--resume` continuation) with
`TASK_ORCHESTRATOR_MODE=headless-iteration` in the child's environment. `hooks/execution-mode.mjs`
exposes `isHeadlessIteration()` (reads that var), `isPhaseOwnerAgentType(agentType)` (matches
`implementer`/`reviewer`, bare or plugin-qualified), the seat-returning `phaseOwnerSeat(agentType)` it is
now built on (`implementer`/`reviewer`/`null`), and `isTestAuthorAgentType(agentType)` (matches
`test-author`, bare or plugin-qualified) for the hooks below to branch on:

| Hook | Headless ralph iteration | Interactive subagent (`agent_id` present) |
|---|---|---|
| Subagent Start | exits silently, no output | injects the protocol only for `implementer`/`reviewer` agent types; every other type gets no output |
| Retro Trigger | emits `{}` before reading/writing the retrospective marker — a headless iteration's tool calls never pollute the interactive session's marker | never emits a nudge/dispatch directive itself; a would-be parent-completion is recorded like a lone-terminal signal so the interactive main session's Stop backstop surfaces it once control returns |
| Retro Backstop | emits `{}` before reading the marker | unaffected — this hook only fires for the main session's own turn |
| Phase-Guard Record | emits `{}` before recording anything | unaffected |
| Phase Guard (SubagentStop) | emits `{}` before any gate fetch | unaffected |

Ralph iterations never dispatch subagents in the first place (see the `ralph` skill and the
`ralph-iteration` output style), so the SubagentStart/Phase-Guard rows are belt-and-suspenders —
the headless gate exists so that invariant is not silently load-bearing.

Hooks intentionally NOT gated by execution mode: Session Start (informational, including the
registration self-check above), Config Sync (must still sync in every mode), and the actor
attribution / skill enforcement hooks (correctness checks on the claimed item's own writes,
independent of who or what is running).

---

## User Skills

Skills are invoked as slash commands in any Claude Code session:

| Command | Description |
|---------|-------------|
| `/task-orchestrator:work-summary` | Insight-driven dashboard of active work, blockers, and recommended next actions |
| `/task-orchestrator:create-item` | Create a tracked work item with smart container anchoring and tag inference |
| `/task-orchestrator:quick-start` | Interactive onboarding — teaches by doing, adapts to empty or populated workspaces |
| `/task-orchestrator:manage-schemas` | Create, view, edit, delete, and validate note schemas in config.yaml |
| `/task-orchestrator:status-progression` | Navigate role transitions; shows current gate status and the correct trigger |
| `/task-orchestrator:dependency-manager` | Visualize, create, and diagnose dependency graphs between work items |
| `/task-orchestrator:batch-complete` | Complete or cancel multiple items at once — close out features or workstreams |

---

## Internal Skills

These are triggered by hooks and output styles, not invoked directly by users:

| Skill | Triggered by | Purpose |
|-------|-------------|---------|
| `pre-plan-workflow` | Pre-plan hook (EnterPlanMode) | Gather MCP state, check schemas, set definition floor before plan is written |
| `post-plan-workflow` | Post-plan hook (ExitPlanMode) | Materialize items from approved plan — work trees, dependencies, queue-phase notes |
| `schema-workflow` | Output styles | Guide items through schema-defined note gates during implementation |

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
Post-plan hook fires
  → agent calls post-plan-workflow skill
  → create_work_tree for hierarchy
  → manage_dependencies for cross-item edges
  → manage_notes for queue-phase notes
        |
        v
Implementation begins
  (items exist, dependencies are set, gates are configured)
```

Without the pipeline, agents write plans that never get tracked. With it, every plan becomes a structured work tree with dependency ordering and schema-driven documentation requirements that survive across sessions.

---

## The Agent-Owned-Phase Protocol

When a subagent is dispatched via the `Agent` tool, the subagent-start hook injects this protocol automatically. Each subagent owns exactly one phase: it enters that phase, fills required notes, then returns without advancing further. The hook cannot see the dispatch prompt's own seat assignment, so the injected wording is conditional rather than a blanket instruction: it tells the agent to follow its dispatch prompt's seat assignment when one is named, and only an **entry seat** (the seat that starts the item's current phase), or a single agent that owns the whole phase with no seat named, calls `advance_item(trigger="start")` — a non-entry seat or a **read-only** agent never calls it at all.

### Protocol steps

**1. Enter the phase (entry seat / sole phase owner only):**

```json
advance_item(transitions=[{ "itemId": "<item-UUID>", "trigger": "start" }])
```

This moves the item into the subagent's phase (queue→work or work→review). The response includes `guidanceKey` (reference to the first required note with guidance) and `noteProgress { filled, remaining, total }`.

If the item is already in the target phase (`applied: false` with `errorCode: "gate_blocked"` and `previousRole` equal to the target phase — every `advance_item` failure now carries an `errorCode`, so branch on that positive code, never on its absence), call `get_context(itemId="<item-UUID>")` instead to get the guidance. Any other `errorCode` means the item is not in your phase: stop and report it.

A non-entry seat or a read-only agent skips this step entirely — it does its assigned work and fills only the notes its own seat owns, without transitioning the item.

**2. Read guidance:**

`guidanceKey` names the first unfilled required note with guidance; resolve its text via `query_items(operation="schema", itemId=...)`. If `skillPointer` is set, load that skill via the `Skill` tool first.

**3. Do work and fill the note:**

```json
manage_notes(operation="upsert", notes=[{
  "itemId": "<uuid>",
  "key": "<note-key>",
  "role": "<phase>",
  "body": "<content>"
}])
```

If `noteProgress.total` is 1 (or absent), this was the only note — skip to step 6.

**4. Get next guidance:**

```json
get_context(itemId="<item-UUID>")
```

Returns updated `guidanceKey` and `gateStatus` (get_context has no `noteProgress` — that field is advance_item/manage_notes-only).

**5. Check if done:**

If `guidanceKey` is absent, all required notes are filled. Proceed to step 6. Otherwise go back to step 2.

**6. Return results:**

Commit all changes with a descriptive message. Report: (1) files changed with line counts, (2) test results summary, (3) any blockers. The orchestrator handles the terminal transition.

### Key rules

- Each agent owns exactly one phase — do not advance beyond it
- Do NOT call `advance_item(trigger="complete")` — the orchestrator handles terminal transitions
- Commit before returning — the orchestrator needs committed changes to push and create a PR
- When the REST API is configured, the SubagentStop phase guard (see Hooks above) is a backstop for this protocol: stopping with required notes still missing on a `work`/`review` item can send you back (up to twice) with the missing keys named, instead of letting an incomplete phase through silently

---

## Example: Planning and Implementing a Feature

This walkthrough shows the full pipeline from description to completion.

**1. User:** "Add rate limiting to the API."

**2. Agent enters plan mode** — the pre-plan hook fires. The agent calls `pre-plan-workflow`, checks for existing rate-limiting work, reads the `feature-implementation` schema requirements, and sets the definition floor.

**3. Agent writes a plan** — 4 subtasks: middleware design, Redis integration, error responses, tests. Plan is written with awareness of existing work and schema gates.

**4. User approves** — the post-plan hook fires. The agent calls `post-plan-workflow` and materializes:

```json
create_work_tree(
  root={ "title": "Rate limiting", "tags": "feature-implementation", "priority": "high" },
  children=[
    { "ref": "middleware", "title": "Middleware design", "tags": "feature-task" },
    { "ref": "redis",      "title": "Redis integration", "tags": "feature-task" },
    { "ref": "errors",     "title": "Error responses",   "tags": "feature-task" },
    { "ref": "tests",      "title": "Integration tests", "tags": "feature-task" }
  ],
  deps=[
    { "from": "tests", "to": "middleware" },
    { "from": "tests", "to": "redis" }
  ]
)
```

Queue-phase notes (feature-summary / task-scope) are filled before dispatching.

**5. Agent dispatches implementation subagents** — each subagent receives one child item UUID. The subagent-start hook injects the Agent-Owned-Phase Protocol automatically.

**6. Each subagent:**
- Calls `advance_item(trigger="start")` — queue→work
- Reads `guidanceKey` (resolved via `query_items(operation="schema")`) — fills `implementation-notes` and `session-tracking` notes
- Commits changes
- Returns to the orchestrator

**7. Orchestrator advances each item** — calls `advance_item(trigger="start")`. If `newRole` is `review` (schema has review-phase notes): dispatches a reviewer or fills review notes inline, then advances review→terminal. If `newRole` is `terminal` (no review phase): item is complete.

**8. Parent auto-cascades** — when all four children reach terminal, the root item cascades to terminal automatically. The `cascadeEvents` field in the response confirms the cascade.

---

## Note Schema Integration

The plugin works best when combined with note schemas (Tier 3). The pre-plan hook reads your `config.yaml` schemas to inform the definition floor. The subagent-start protocol uses `guidanceKey` (resolved via `query_items(operation="schema")`) from those schemas to tell agents exactly what to write.

See [Note Schemas](note-schemas.md) for schema setup.

---

## When to Level Up

**Signal:** You want Claude to operate as a full workflow orchestrator — planning, delegating, tracking, and reporting — rather than implementing directly.

**Next:** [Output Styles](output-styles.md) — activate Workflow Analyst mode for delegation-based operation.
