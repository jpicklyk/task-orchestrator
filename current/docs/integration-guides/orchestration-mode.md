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
| `orchestration-context` SessionStart hook | The always-on core: workflow principles, skill routing, retrospective handling, action items, visual conventions and, in a Code Mode session, a pointer to the run-wave Code Mode recipes | Every session start, including after `/compact` and `/clear` |
| `task-orchestrator:orchestrate` skill | On-demand depth: the tier table, phase-owner dispatch rules, and a pointer to the `task-orchestrator:model-effort` class table | Invoked from the orchestration context before sizing or dispatching work |
| `dispatch-model-guard` PreToolUse hook (matcher `Agent`) | Denies an `Agent` dispatch that omits `model` or `effort`; the deny reason names what is missing and the retry (the `model-effort` class table or the item's dispatch profile) | Every `Agent` call |
| `dispatch-hint` PostToolUse hook (matcher `advance`) | One line per transition into `work` or `review` naming the item's dispatch profile (`agent`, `model`, `effort`), taken from the `dispatch` object of the `advance` result | After each `advance`, main session only |
| `skills/ralph/iteration-system-prompt.md` | The rules for a headless ralph iteration | Appended to the system prompt of each `claude -p` iteration; the orchestration context stays inert there |

The hooks are inert when the session is a headless ralph iteration, when no `.taskorchestrator/config.yaml` can be located, or when `steering` is `off`. That covers both enforcement hooks too. The orchestration context is also withheld from subagents and director sessions (`hooks/README.md`, Mode gating). Where each item's phase owner comes from is not a hook's call: the dispatch profile arrives in the `advance` `transition` result (`dispatch`) and in the `items_read` `select: "schema"` view of the item.

### The `steering` key

Set it under `extensions.claude-code` in `.taskorchestrator/config.yaml`:

```yaml
extensions:
  claude-code:
    steering: workflow   # workflow (default) | schema | off
```

The old `extensions.claude-code.orchestration.mode` is still read as a deprecated alias when `steering` is absent or invalid; `steering` wins when both are set, and a session start prints one deprecation line while the old key is present.

A top-level `orchestration:` block is ignored by the hooks (they read only `extensions.claude-code`), `session-start` prints a `## Config Shape` line naming it, and a 4.0 server rejects it on push. Until you move it, the hooks run in `workflow` mode.

| Mode | Behaviour |
|------|-----------|
| `workflow` (default) | Tier-aware. Plans, delegates, tracks and reports. Small fixes are done inline. |
| `schema` | No tiers and no model table. The item's resolved note schema alone sets the process; phase-owner dispatch rules still apply. |
| `off` | The orchestration hooks exit silently, including `dispatch-model-guard` and `dispatch-hint`. |

The key is read client-side by the hooks. The context hook re-reads it at the next session start, `/clear`, or `/compact`; `dispatch-model-guard` and `dispatch-hint` re-read it on every call, so `off` silences them immediately. In a knowledge session (an orchestrator registration serving the knowledge profile, or a root config whose every declared type is `lifecycle: none`) the context, plan-mode and dispatch-guard hooks stay silent whatever the mode. See [config-format.md → Plugin settings](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#plugin-settings-extensionsclaude-code) for the full field reference and [config-reference.md](../config-reference.md#extensions) for who owns each key.

### Layering a personal output style

The plugin supplies the orchestration core, so a personal output style does not need to restate it. If you keep your own style in `~/.claude/output-styles/`, put only your own additions in it: tone, project conventions, and any extra retrospective or observation habits. Do not copy the delegation or tier rules into it, since the hook already delivers them and a copy drifts.

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

## Retrospective

A retrospective is owed per completed scope (the item a run is scoped to; boundary `scope`, the default) or per closed run that has no `retro` mark (boundary `run`), and the plugin's retrospective hooks — not the orchestrator's own judgment — are the trigger. It is owed to the session that opened or closed one of its runs; a run another session opened and closed is left to that session. `runs_write` `close` fires `retro-trigger`; `retro-backstop` runs the same check at `Stop`, so a run this session opened that closed elsewhere (a subagent, a tool the trigger never saw) still gets one, and in `dispatch` mode a scope completed after its owning session ended is picked up by another session an hour later. At the scope boundary a low-signal scope (a small, clean one) is skipped by the hook itself and the orchestrator hears nothing; a failed retrospective is retried once and then reported with one nudge. The state machine is the "Retrospective firing" section of the plugin's hooks README. The orchestrator follows the hook according to `retrospective.mode` under `extensions.claude-code` in `.taskorchestrator/config.yaml`:

| Mode | What the orchestrator does |
|------|----------------------------|
| `nudge` (default) | Surfaces one suggestion naming every run of the scope in a single `/session-retrospective --run <runId> --run <runId> --session-dir "<folder>"` invocation; the user opts in |
| `dispatch` | Launches the background retrospective agent the hook specifies, at the next run boundary — one per scope, after the hook wins the claim (the first mark wins, so two sessions never both dispatch). At boundary `run`, a run with fewer terminal members than `minItems` (default 3) still arrives as a nudge |
| `off` | Nothing |

An item reaching terminal by itself does not trigger anything: the unit is the scope (or, at boundary `run`, the run). In `nudge` mode the suggestion looks like:

```
Retrospective suggested — a run closed (run(s): <runId>, <runId>). Consider running `/session-retrospective --run <runId> --run <runId> --session-dir "<folder>"` to capture learnings before the session ends.
```

See [config-format.md → Retrospective](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#retrospective) for the full field reference and [Self-Improving Workflow](self-improving-workflow.md) for what the retrospective does with it.

---

## Next Step

[Tier 6: Self-Improving Workflow](self-improving-workflow.md) — close the loop by having Claude monitor its own MCP usage, log friction points as persistent observations, and self-correct discipline issues via auto-memory.
