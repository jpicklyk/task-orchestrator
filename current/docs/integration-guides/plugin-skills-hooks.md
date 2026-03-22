# Tier 4: Plugin — Skills and Hooks

## What You Get

- 4 automatic hooks that fire on session start, plan mode entry, plan approval, and subagent launch
- 7 user-invocable skills as `/task-orchestrator:*` slash commands
- 3 internal skills that power the plan-mode pipeline
- The Agent-Owned-Phase Protocol injected into every subagent automatically

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

**What it injects:** The full Agent-Owned-Phase Protocol (see below).

**Effect:** Every subagent knows to call `advance_item(trigger="start")` to enter its phase, fill notes using the `guidancePointer` loop, commit changes, and return without calling `complete`. The orchestrator handles terminal transitions.

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

When a subagent is dispatched via the `Agent` tool, the subagent-start hook injects this protocol automatically. Each subagent owns exactly one phase: it enters that phase, fills required notes, then returns without advancing further.

### Protocol steps

**1. Enter the phase:**

```json
advance_item(transitions=[{ "itemId": "<item-UUID>", "trigger": "start" }])
```

This moves the item into the subagent's phase (queue→work or work→review). The response includes `guidancePointer` (authoring instructions for the first required note) and `noteProgress { filled, remaining, total }`.

If the item is already in the target phase (`applied: false` in the response), call `get_context(itemId="<item-UUID>")` instead to get the guidance.

**2. Read guidance:**

`guidancePointer` is the schema author's instruction for the first unfilled required note. If it references a skill, load it via the `Skill` tool.

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

Returns updated `guidancePointer` and `noteProgress`.

**5. Check if done:**

If `guidancePointer` is null, all required notes are filled. Proceed to step 6. Otherwise go back to step 2.

**6. Return results:**

Commit all changes with a descriptive message. Report: (1) files changed with line counts, (2) test results summary, (3) any blockers. The orchestrator handles the terminal transition.

### Key rules

- Each agent owns exactly one phase — do not advance beyond it
- Do NOT call `advance_item(trigger="complete")` — the orchestrator handles terminal transitions
- Commit before returning — the orchestrator needs committed changes to squash-merge the branch

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

Queue-phase notes (specification / task-scope) are filled before dispatching.

**5. Agent dispatches implementation subagents** — each subagent receives one child item UUID. The subagent-start hook injects the Agent-Owned-Phase Protocol automatically.

**6. Each subagent:**
- Calls `advance_item(trigger="start")` — queue→work
- Reads `guidancePointer` — fills `implementation-notes` and `session-tracking` notes
- Commits changes
- Returns to the orchestrator

**7. Orchestrator reviews each item** — calls `advance_item(trigger="start")` to move work→review, fills review-checklist, then advances review→terminal.

**8. Parent auto-cascades** — when all four children reach terminal, the root item cascades to terminal automatically. The `cascadeEvents` field in the response confirms the cascade.

---

## Note Schema Integration

The plugin works best when combined with note schemas (Tier 3). The pre-plan hook reads your `config.yaml` schemas to inform the definition floor. The subagent-start protocol uses `guidancePointer` from those schemas to tell agents exactly what to write.

See [Note Schemas](note-schemas.md) for schema setup.

---

## When to Level Up

**Signal:** You want Claude to operate as a full workflow orchestrator — planning, delegating, tracking, and reporting — rather than implementing directly.

**Next:** [Output Styles](output-styles.md) — activate Workflow Analyst mode for delegation-based operation.
