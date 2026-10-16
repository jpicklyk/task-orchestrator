---
name: orchestrate
description: "Internal, invoked from the orchestration context: tier classification, delegation model table and phase-owner dispatch rules for sizing and dispatching implementation work."
user-invocable: false
---

# Orchestrate - Tier, Delegation and Dispatch

On-demand depth for sizing work and dispatching agents. Shapes are in [`_generated/call-shapes.md`](../_generated/call-shapes.md); ids below are 8-hex shorts (a full UUID is also accepted).

`orchestration.mode` lives under `extensions.claude-code` in the project config (see [`extensions`](../../../../current/docs/config-reference.md#extensions)). The tier table and the delegation model table apply when it is `workflow`. In `schema` mode the schema alone governs process, so skip the tier and model-table content; the phase-owner dispatch rules and the explicit-`model` rule below still apply, with the model chosen by judgment.

## Tier Classification

Classify every piece of work into a tier before starting; the tier decides how much process to apply.
<!-- BEGIN GENERATED:tier-classification | source: claude-plugins/task-orchestrator/_fragments/tier-classification.md · regen: node claude-plugins/task-orchestrator/_fragments/generate.mjs -->
| Criteria | Tier | Pipeline |
|----------|------|----------|
| 1-2 files, known fix, no migration/new API | **Direct** | Orchestrator edits, tests, reviews inline |
| 3-10 files, single logical unit, clear or explorable scope | **Delegated** | Single subagent, separate review agent |
| 11+ files, multiple independent work streams, dependency edges | **Parallel** | Worktree agents, full pipeline |

**Force-UP signals** (bump tier regardless of file count):
- Database migration → min Delegated
- New public API surface → min Delegated
- Multiple independent work streams → Parallel
- User says "let's plan" / collaborative language → min Delegated

**Force-DOWN signals:**
- User says "just fix it" / "quick" → Direct (unless complexity contradicts)
- The item's type resolves to the `default` schema, or the item is schema-free -> eligible for Direct
<!-- END GENERATED:tier-classification -->

### Tier Pipeline Summary

| Step | Direct | Delegated | Parallel |
|------|--------|-----------|----------|
| Plan mode | skip | optional | required |
| Queue notes | none required | fill per schema | fill per schema |
| Implementation | orchestrator inline, no subagent, no delegation table | single subagent | parallel worktree agents |
| Review | inline (orchestrator) | separate agent | separate agent |

Review applies only when the item's schema declares review-phase notes; otherwise work advances straight to terminal. Detect it from `newRole` in the `advance` result and skip review dispatch.

**Multi-item dispatch:** when post-plan-workflow's four-part condition holds (two or more unblocked leaf items, a resolvable rootId, filled queue notes, protocol rules served), dispatch goes through `/task-orchestrator:run-wave`; otherwise hand-dispatch.

## Delegation

> **Project convention, not a plugin requirement.** The model assignments and the write-batching
> threshold below are tuned for this repository. Treat them as defaults and adjust to your own
> model availability and tooling; what transfers is the *principle* (match model to task weight,
> keep the orchestrator's context lean), not the table values.

| Task type | Model |
|-----------|-------|
| Bulk MCP writes, materialization, simple queries | `haiku` |
| Code reading, implementation, test writing | `sonnet` |
| Architecture, complex tradeoffs, multi-file synthesis | `opus` |

**Always set `model` explicitly** on every Agent dispatch: defaulting wastes opus tokens or under-powers complex work.

### Dispatching an item's phase owner

This applies only to the agent that OWNS the phase being entered (the implementer entering work, the reviewer entering review), never to the test author, planning seats or the docs seat; those keep the Delegation table above.

Read the profile for the phase you dispatch INTO, not the item's current phase:

- If the orchestrator already made the transition (for example it advanced the item into review and now dispatches the reviewer), read `dispatch` off that `advance` `transition` result (`data.results[0].dispatch`): it is the profile for `newRole`, the phase being dispatched into.

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "4b7d0e19", "trigger": "start"}}
```

- If the agent will perform its own phase entry (the agent-owned-phase protocol: the implementer is dispatched while the item is still in queue and calls `start` itself), `items_read` `context` returns only the profile for the CURRENT role (queue), which is the wrong one. Read the schema instead; `dispatch` is the per-phase map and `dispatchBySeat` the per-seat one:

```to-call
{"tool": "items_read", "select": "schema", "args": {"itemId": "4b7d0e19"}}
```

When the profile names an `agent`, dispatch with `subagent_type` set to it. Whether or not `agent` is set, ALWAYS pass `model` explicitly: `dispatch.model` if the profile sets one, otherwise the Delegation table's value (or your own judgment in `schema` mode). Both shipped agents (`task-orchestrator:implementer`, `task-orchestrator:reviewer`) ship `model: inherit`, and Claude Code resolves the per-invocation Agent-tool `model` first, then the agent's frontmatter, where `inherit` means the main conversation's model. Omitting `model` silently runs the phase owner on the orchestrator's own model.

`effort` has no Agent-tool parameter. In Claude Code it is honored only through the dispatched agent definition's own frontmatter `effort`, so a profile's `effort` is advisory unless `agent` names a definition carrying it; to change effort, point `agent` at a definition with that effort. This rule covers the phase OWNER only; auxiliary dispatches on the same item (test author under `needs-test-author`, planning seats, the docs seat) keep the Delegation table.

For a **seat-aware** item (its schema declares `seats`), read every seat's profile, test author and planning seats included, from the resolved `dispatchBySeat`; it supersedes the Delegation table for those seats. A model-selecting seat-only override wins whatever the trait order (see [Seat dispatch precedence](../../../../current/docs/config-reference.md#seat-dispatch-precedence)). To change a pinned `model`, change it through the project's config traits, not by overriding it at dispatch time.

Before dispatching a seat at a feature or wave parent, branch on the parent's role with the pre-dispatch recipe in `/task-orchestrator:schema-workflow` ("Pre-dispatch recipe: a seat at a parent"): never a reviewer at a work-role parent.

### Batching and prompts

**Project convention (workflow mode only; in `schema` mode batch by ordinary judgment): avoid 3 or more write calls in a single turn.** One `ops[]` call carries many writes (`items_write`, `notes_write`, `deps_write` and `advance` all take an array), so batch the writes into one call before delegating. Parallelized reads (`items_read` `context` plus `overview`) are fine and encouraged. Delegate bulk write work that cannot be batched to the Agent tool with `model: "haiku"` to keep the orchestrator context lean.

Delegation prompts must include entity ids and full context; subagents start fresh.

### Dispatch contract

**Parallel-tier dispatches follow a dispatch contract.** Where the project's `/implement` skill ships a dispatch-contract template, generate the run's plan file from it; otherwise write one plan file for the wave that pins branch, worktree, file ownership, commit form, build self-check and review scoping. Either way, point every dispatch prompt at that file by absolute path instead of restating those rules inline. Under a run plan (`/task-orchestrator:run-wave`) seat prompts are generated and never reference this file; the contract then serves the orchestrator (fallback hand-dispatches, the post-run commit map) and the reviewers, and anything a seat must know goes in the item's `specification` or `task-scope` note.

### Hand-dispatched seats carry the project's verify commands

`/task-orchestrator:run-wave` reads the project's `.taskorchestrator/run-profile.json`, and a project dispatch contract pins its own build self-check. A seat you dispatch **by hand**, outside both of those, gets neither unless you put it there. When that file exists:

- For each `verify` entry whose `seats` lists the seat you dispatch (`implementer`, `test-author`, ...), paste its `command` into the seat's prompt verbatim, substituting `<worktree>` and `<scratchpad>`, as the check the seat runs before committing. If the command calls a wrapper that a wave's setup creates (a lock script in the scratchpad, say) and that file is absent, give the seat the same tasks through the build tool directly: the task list is what matters, not the wrapper.
- Run every entry whose `seats` lists `orchestrator` yourself, on the item's worktree, before dispatching the reviewer and again before pushing. A failure goes back to the owning seat before review, not to the reviewer.
- Without a run-profile, mirror the project's CI checks (its workflow files): a seat prompt that asks only for the test task misses any lint or format gate CI runs first.

### Verification

**Do not delegate verification.** Do not dispatch subagents to verify or double-check your own work. Verification belongs to the schema's review phase (a separate reviewer) or to inline review on Direct tier. Current models self-verify well, so a redundant verification agent adds cost without catching more. This does not cover independent test authoring under the `needs-test-author` trait: dispatching a separate test author is production work the trait requires, and the separation between writing code and writing its tests is the point.

### Notes are the report

Subagents write findings into their work item's notes; their final message back is 1-2 lines (item id, outcome, note keys filled). Never ask agents to restate note content in replies.

### Delegation metadata

If your project defines a `delegated` trait, apply it to Delegated and Parallel items so the note below is schema-visible rather than convention-only. Traits are an array. Set it at creation, `"traits": ["delegated"]` on the `items_write` `create` (or tree child), or add it later with an update, which REPLACES the list, so send the full list:

```to-call
{"tool": "items_write", "op": "update", "args": {"id": "4b7d0e19", "traits": ["needs-docs-accuracy-review", "delegated"]}}
```

An update that changes `traits` is itself a pin point: the item is re-pinned to the current config (see [Schema pins](../../../../current/docs/config-reference.md#schema-pins)). Then, after each subagent returns, fill its `delegation-metadata` work note: model, isolation, a one-line rationale and a one-line outcome. The orchestrator fills this, not the subagent (only the orchestrator knows the dispatch details). It feeds `/session-retrospective`'s delegation-alignment scoring; projects without the trait skip it.
