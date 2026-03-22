# Tier 5: Output Styles — Workflow Orchestrator Mode

**Prerequisites:** [Tier 4: Plugin: Skills and Hooks](plugin-skills-hooks.md) installed and working · Claude Code CLI with output style support

**Cross-references:** [Quick Start](../quick-start.md) · [API Reference](../api-reference.md) · [Workflow Guide](../workflow-guide.md) · [Self-Improving Workflow](self-improving-workflow.md)

---

## What You Get

- Claude never implements directly — it plans, delegates, tracks, and reports
- Structured delegation model: haiku for bulk MCP ops, sonnet for implementation, opus for architecture
- Worktree isolation for parallel implementation without file conflicts
- Agent-owned phase transitions with orchestrator-controlled terminal advancement
- Session task visibility alongside persistent MCP tracking

---

## What Output Styles Are

Output styles are persistent behavioral instructions that shape how Claude operates throughout a session. Unlike `CLAUDE.md` (which provides project context), output styles define Claude's working mode — how it approaches tasks, delegates work, and communicates.

Output styles are personal — they live in `~/.claude/output-styles/` and are activated via `.claude/settings.local.json` (gitignored). They are not shared with the team.

---

## Activating the Workflow Orchestrator

The plugin includes a `Workflow Orchestrator` output style. To activate it:

### Via settings file

Add to `.claude/settings.local.json`:

```json
{
  "outputStyle": "Workflow Orchestrator"
}
```

### Via CLI

```
/output-style
```

Then select `Workflow Orchestrator` from the list.

---

## Key Behavioral Changes

### Never Implement Directly

The orchestrator delegates all coding and file changes to subagents. It reads files, plans, reviews diffs, and coordinates — but never edits code itself.

### Plan Before Acting

Non-trivial features trigger plan mode (`EnterPlanMode`). The pre-plan hook fires automatically, gathering MCP state before the plan is written.

### Materialize Before Implement

All MCP work items must exist before dispatching implementation agents. The post-plan hook handles this — creating work trees, wiring dependencies, and filling queue-phase notes from the approved plan.

### Agent-Owned Phases

Phase transitions follow a strict ownership model:

| Transition | Owner | Mechanism |
|-----------|-------|-----------|
| queue → work | Implementation agent | `advance_item(trigger="start")` at task start |
| work → review | Implementation agent | `advance_item(trigger="start")` before returning |
| review → terminal | Orchestrator | `advance_item(trigger="start")` after review verdict |

Implementation agents own their lifecycle through review. The orchestrator performs the final terminal transition after reviewing the verdict.

---

## The Delegation Model

The orchestrator always sets the `model` parameter explicitly on every agent dispatch:

| Task type | Model | Rationale |
|-----------|-------|-----------|
| MCP bulk ops, materialization, simple queries | `haiku` | Fast, cheap, structured output |
| Code reading, implementation, test writing | `sonnet` | Strong coding, good cost/quality balance |
| Architecture, complex tradeoffs, multi-file synthesis | `opus` | Deep reasoning for cross-file synthesis |

Omitting `model` causes the agent to inherit the orchestrator's model — typically opus — wasting tokens on sonnet-eligible work.

**Rule: Never make 3+ MCP write calls in a single orchestrator turn.** Delegate bulk MCP write work (multiple item/dependency/note creates) to a haiku agent to keep the orchestrator context clean. Parallelized reads (e.g., `get_context` + `query_items overview`) are fine and encouraged.

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
6. After review passes: squash-merge worktree branch into local `main`
7. Run full test suite after each merge to catch integration issues
8. Delete worktree branch

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
4. **Self-advance:** Each agent calls `advance_item(trigger="start")` twice — queue→work at start, work→review before returning.
5. **Review:** Orchestrator dispatches review agents into each worktree path.
6. **Terminal:** Orchestrator advances each item review→terminal after review passes.
7. **Cascade:** Parent auto-cascades to terminal when all children reach terminal.
8. **Merge:** Squash-merge each worktree branch into local `main`. Run tests after each merge.

---

## Visual Conventions

The output style uses unicode symbols for status:

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

When items reach terminal after an implementation run — whether via `advance_item`, `complete_tree`, or auto-cascade — the orchestrator nudges:

```
↳ Implementation run complete. Consider running `/session-retrospective` to capture learnings.
```

The nudge appears at most once per run and is never auto-invoked.

---

## Next Step

[Tier 6: Self-Improving Workflow](self-improving-workflow.md) — close the loop by having Claude monitor its own MCP usage, log friction points as persistent observations, and self-correct discipline issues via auto-memory.
