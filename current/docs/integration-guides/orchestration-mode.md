# Tier 5: Orchestration Mode

**Prerequisites:** [Tier 4: Plugin: Skills and Hooks](plugin-skills-hooks.md) installed and working

**Cross-references:** [Quick Start](../quick-start.md) · [API Reference](../api-reference.md) · [Workflow Guide](../workflow-guide.md) · [Self-Improving Workflow](self-improving-workflow.md)

---

## What You Get

- Tier-aware orchestration — Claude plans, delegates, tracks, and reports; small known fixes (Direct tier) it implements inline, larger work goes to subagents
- Structured delegation model: haiku for bulk MCP ops, sonnet for implementation, opus for architecture
- Worktree isolation for parallel implementation without file conflicts
- Agent-owned phase transitions with orchestrator-controlled terminal advancement
- Session task visibility alongside persistent MCP tracking

---

## How It Is Delivered

The plugin delivers the orchestration core through a SessionStart hook, not an output style. Nothing needs to be selected or activated.

| Channel | What it carries | When |
|---------|-----------------|------|
| `orchestration-context` SessionStart hook | The always-on core: workflow principles, skill routing, retrospective handling, action items, visual conventions | Every session start, including after `/compact` and `/clear` |
| `task-orchestrator:orchestrate` skill | On-demand depth: the tier table, the delegation model table, and phase-owner dispatch rules | Invoked from the orchestration context before sizing or dispatching work |
| `dispatch-model-guard` PreToolUse hook (matcher `Agent`) | Denies an `Agent` dispatch that omits `model`; the deny reason names the retry (the delegation table in `workflow` mode, the dispatch profile in `schema` mode) | Every `Agent` call |
| `dispatch-hint` PostToolUse hook (matcher `advance`) | One line per transition into `work` or `review` naming the item's dispatch profile (`agent`, `model`, optional `effort`), taken from the `dispatch` object of the `advance` result | After each `advance`, main session only |
| `skills/ralph/iteration-system-prompt.md` | The rules for a headless ralph iteration | Appended to the system prompt of each `claude -p` iteration; the orchestration context stays inert there |

The hooks are inert when the session is a headless ralph iteration, when no `.taskorchestrator/config.yaml` can be located, or when `orchestration.mode` is `off`. That covers both enforcement hooks too. The orchestration context is also withheld from subagents and director sessions (`hooks/README.md`, Mode gating). Where each item's phase owner comes from is not a hook's call: the dispatch profile arrives in the `advance` `transition` result (`dispatch`) and in the `items_read` `select: "schema"` view of the item.

### The `orchestration.mode` key

Set it under `extensions.claude-code` in `.taskorchestrator/config.yaml`:

```yaml
extensions:
  claude-code:
    orchestration:
      mode: workflow   # workflow (default) | schema | off
```

A top-level `orchestration:` block is ignored by the hooks (they read only `extensions.claude-code`), `session-start` prints a `## Config Shape` line naming it, and a 4.0 server rejects it on push. Until you move it, the hooks run in `workflow` mode.

| Mode | Behaviour |
|------|-----------|
| `workflow` (default) | Tier-aware. Plans, delegates, tracks and reports. Small fixes are done inline. |
| `schema` | No tiers and no model table. The item's resolved note schema alone sets the process; phase-owner dispatch rules still apply. |
| `off` | The orchestration hooks exit silently, including `dispatch-model-guard` and `dispatch-hint`. |

The key is read client-side by the hooks. The context hook re-reads it at the next session start, `/clear`, or `/compact`; `dispatch-model-guard` and `dispatch-hint` re-read it on every call, so `off` silences them immediately. See [config-format.md → Plugin settings](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#plugin-settings-extensionsclaude-code) for the full field reference and [config-reference.md](../config-reference.md#extensions) for who owns each key.

### Layering a personal output style

The plugin supplies the orchestration core, so a personal output style does not need to restate it. If you keep your own style in `~/.claude/output-styles/`, put only your own additions in it: tone, project conventions, and any extra retrospective or observation habits. Do not copy the delegation or tier rules into it, since the hook already delivers them and a copy drifts.

---

## Key Behavioral Changes

### Process Proportional to Tier

The orchestrator classifies every piece of work into a tier before starting, and the tier decides who implements it:

| Tier | Typical shape | Implementation | Review |
|------|---------------|----------------|--------|
| **Direct** | 1-2 files, known fix, no migration or new API | Orchestrator edits, tests, and reviews inline — no subagent | Inline |
| **Delegated** | 3-10 files, single logical unit | Single subagent | Separate review agent |
| **Parallel** | 11+ files, multiple independent work streams | Parallel worktree agents | Separate review agent |

A database migration or a new public API surface bumps work to at least Delegated regardless of file count. Review applies only when the item's schema declares review-phase notes. The rest of this guide describes the Delegated and Parallel tiers, where the orchestrator reads files, plans, reviews diffs, and coordinates while subagents make the changes.

### Plan Before Acting

Non-trivial features trigger plan mode (`EnterPlanMode`). The pre-plan hook fires automatically, gathering MCP state before the plan is written.

### Materialize Before Implement

All MCP work items must exist before dispatching implementation agents. The post-plan hook handles this — creating work trees, wiring dependencies, and filling queue-phase notes from the approved plan.

### Agent-Owned Phases

Phase transitions follow a strict ownership model:

| Transition | Owner | Mechanism |
|-----------|-------|-----------|
| queue → work | Implementation agent | `advance` op `transition`, `trigger: "start"` — phase entry, sent after an `items_read` `context` read shows the role just before its phase |
| work → review/terminal | Orchestrator | `advance` `transition` `start` — routes based on schema |
| review → terminal | Orchestrator | `advance` `transition` `start` after review verdict |

Implementation agents enter their assigned phase and fill its notes. The orchestrator owns all subsequent transitions — it advances the item and inspects `newRole` to determine the next phase. If the schema has review-phase notes, the item moves to review and a reviewer is dispatched. If not, the item moves directly to terminal.

When an item's trait or schema resolves a `dispatch` profile for the phase being entered, the orchestrate skill's "Dispatching an item's phase owner" section tells the orchestrator to use that agent and to still pass `model` explicitly.

---

## The Delegation Model

The orchestrator always sets the `model` parameter explicitly on every agent dispatch:

| Task type | Model | Rationale |
|-----------|-------|-----------|
| MCP bulk ops, materialization, simple queries | `haiku` | Fast, cheap, structured output |
| Code reading, implementation, test writing | `sonnet` | Strong coding, good cost/quality balance |
| Architecture, complex tradeoffs, multi-file synthesis | `opus` | Deep reasoning for cross-file synthesis |

Omitting `model` would make the agent inherit the orchestrator's model — typically opus — wasting tokens on sonnet-eligible work. The `dispatch-model-guard` hook denies such a dispatch with a reason naming this table, so the call is retried with `model` set.

**Rule: Never make 3+ MCP write calls in a single orchestrator turn.** Delegate bulk MCP write work (multiple item/dependency/note creates) to a haiku agent to keep the orchestrator context clean. Parallelized reads (e.g., `items_read` `context` + `items_read` `overview`) are fine and encouraged.

---

## Worktree Isolation

When dispatching multiple implementation agents in parallel, use `isolation: "worktree"` to give each agent an isolated copy of the repository.

**When to use:** Independent tasks that modify different files and have no dependency edges between them.

**When not to use:** Tasks with dependency edges (dispatch sequentially instead). Tasks that change shared domain models or test infrastructure should run first — dispatch the parallel wave only after the test suite passes on the changed baseline.

### Lifecycle

1. Orchestrator dispatches agent with `isolation: "worktree"`
2. Agent works in isolated copy, commits to a worktree branch
3. Agent returns — result includes worktree path and branch name
4. Orchestrator spot-checks diff: `git -C <worktree-path> diff main --stat`
5. Review agent dispatched into the same worktree path
6. After review passes: push worktree branch to origin and create PR
7. Run full test suite before pushing to catch integration issues
8. After PR merges: sync local main and clean up branch

### Tracking Table

Record each dispatched agent immediately:

```
| Item UUID | Worktree Path | Branch | Status |
|-----------|--------------|--------|--------|
| a1b2c3d4  | /path/to/wt  | fix/x  | in-progress |
```

---

## Session Tasks vs MCP Items

The orchestrator uses two tracking systems in parallel:

| System | Persistence | Purpose |
|--------|------------|---------|
| Session tasks (`TaskCreate`) | Current session only | Terminal progress visibility — what is happening right now |
| MCP items | Cross-session | Persistent tracking — what needs to be done and what has been done |

**Pattern:** Create a session task when dispatching a subagent. Complete it when the agent returns. The MCP item tracks the work across sessions; the session task shows real-time progress in the terminal.

---

## Example: Multi-Task Feature

Feature: "Refactor authentication into three components"

1. **Plan:** Enter plan mode. Pre-plan hook gathers MCP state. Write plan with three tasks. User approves.
2. **Materialize:** Post-plan creates root item + three children with fan-out dependencies.
3. **Dispatch:** Three worktree agents in parallel. Each gets a child UUID + target files. Each creates a session task.
4. **Phase entry:** Each agent reads the item's role with `items_read` `context`, then sends `advance` `transition` `start` once — queue→work at start. Fills work-phase notes and returns.
5. **Orchestrator advances:** Sends `advance` `transition` `start` for each item. Items with review-phase notes move to review; lightweight items move to terminal.
6. **Review:** For items now in review, orchestrator dispatches review agents into each worktree path. After review passes, advances review→terminal.
7. **Cascade:** An `auto`-lifecycle parent cascades to terminal when all children reach terminal (the `cascades` field of the `transition` result reports it). A `manual`, `permanent` or lifecycle-free parent is left alone, and a terminal cascade into a parent in `review` is suppressed (`reason: review-pending`): that parent needs an explicit advance. The rules are in the Cascades section of the [API reference](../api-reference.md#advance).
8. **Merge:** Squash-merge each worktree branch into local `main`. Run tests after each merge.

---

## Visual Conventions

The orchestration context uses unicode symbols for status:

| Symbol | Meaning |
|--------|---------|
| `✓` | Terminal (complete) |
| `◉` | Work or review (active) |
| `⊘` | Blocked |
| `○` | Queue (pending) |
| `—` | Cancelled |

Completion format:

```
✓ `d5c9c5ed` Design API schema → completed
✓ Unblocked: Implement data models (`2089ba1e`), Build REST endpoints (`26f2fa20`)
```

Narration uses the `↳` prefix for background operations — one line each, skim-friendly. Decisions and blockers use `>` blockquotes with a bold lead-in, only when user action is needed.

---

## Retrospective

A retrospective is owed per closed run that has no `retro` mark, and the plugin's retrospective hooks — not the orchestrator's own judgment — are the trigger. `runs_write` `close` fires `retro-trigger`; `retro-backstop` runs the same check at `Stop` through `runs_read` with `unmarked: "retro"`, so a run closed in another session still gets one. The orchestrator follows the hook according to `retrospective.mode` under `extensions.claude-code` in `.taskorchestrator/config.yaml`:

| Mode | What the orchestrator does |
|------|----------------------------|
| `nudge` (default) | Surfaces a suggestion to run `/session-retrospective --run <runId>`; the user opts in |
| `dispatch` | Launches the background retrospective agent the hook specifies, at the next run boundary — one per run, after the hook wins the `retro` mark (the first mark wins, so two sessions never both dispatch). A run with fewer terminal members than `dispatchThreshold` (default 3) still arrives as a nudge |
| `off` | Nothing |

An item reaching terminal by itself does not trigger anything: the unit is the run. In `nudge` mode the suggestion looks like:

```
Retrospective suggested — a run closed (run(s): <runId>). Consider running `/session-retrospective --run <runId>` to capture learnings before the session ends.
```

See [config-format.md → Retrospective](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#retrospective) for the full field reference and [Self-Improving Workflow](self-improving-workflow.md) for what the retrospective does with it.

---

## Next Step

[Tier 6: Self-Improving Workflow](self-improving-workflow.md) — close the loop by having Claude monitor its own MCP usage, log friction points as persistent observations, and self-correct discipline issues via auto-memory.
