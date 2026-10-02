---
name: orchestrate
description: "Internal, invoked from the orchestration context: tier classification, delegation model table, and phase-owner dispatch rules for sizing and dispatching implementation work."
user-invocable: false
---

# Orchestrate — Tier, Delegation and Dispatch

On-demand depth for sizing work and dispatching agents. The tier table and the delegation model table apply when `orchestration.mode` is `workflow`. In `schema` mode the schema alone governs process, so skip the tier and model-table content — but the phase-owner dispatch rules and the explicit-`model` rule below still apply, with the model chosen by judgment.

## Tier Classification

Classify every piece of work into a tier before starting — the tier determines how much process to apply.

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
- Schema tag is `default` or absent → eligible for Direct
<!-- END GENERATED:tier-classification -->

### Tier Pipeline Summary

| Step | Direct | Delegated | Parallel |
|------|--------|-----------|----------|
| Plan mode | skip | optional | required |
| Queue notes | none required | fill per schema | fill per schema |
| Implementation | orchestrator inline — no subagent, no delegation table | single subagent | parallel worktree agents |
| Review | inline (orchestrator) | separate agent | separate agent |

Review applies only when the item's schema declares review-phase notes; otherwise work advances straight to terminal — detect via `newRole` and skip review dispatch.

**Multi-item dispatch:** when post-plan-workflow's four-part condition holds (two or more unblocked leaf items, a resolvable rootId, filled queue notes, protocol rules listed), dispatch goes through `/task-orchestrator:run-wave`; otherwise hand-dispatch.

## Delegation

> **Project convention, not a plugin requirement.** The specific model assignments and the
> MCP-write batching threshold below are tuned for this repository. Projects using this skill
> should treat them as sensible defaults and adjust to their own model availability and tooling —
> what transfers is the *principle* (match model to task weight; keep the orchestrator's context
> lean), not the exact table values.

| Task type | Model |
|-----------|-------|
| MCP bulk ops, materialization, simple queries | `haiku` |
| Code reading, implementation, test writing | `sonnet` |
| Architecture, complex tradeoffs, multi-file synthesis | `opus` |

**Always set `model` explicitly** on every Agent dispatch — defaulting wastes opus tokens or under-powers complex work.

### Dispatching an item's phase owner

Applies only to the agent that OWNS the phase being entered — the implementer entering work, the reviewer entering review — never test author, planning seats, or the docs seat (those keep the Delegation table above).

Read the profile for the phase you are dispatching INTO, not the item's current phase:
- If the orchestrator already performed the transition (e.g. dispatching the reviewer after advancing the item into review), read `dispatch` off that `advance_item` call's success result — it reports the profile for `newRole`, the phase being dispatched into.
- If the agent will perform its own phase entry (the agent-owned-phase protocol: the implementer is dispatched while the item is still in queue, and calls `advance_item(start)` itself to enter work), `get_context(itemId=...)` returns only the profile for the item's CURRENT role (queue) — not the work-phase profile needed for dispatch. Read `query_items(operation="schema", itemId=...)`'s per-phase `dispatch.work` map instead.

When that profile names an `agent`, dispatch with `subagent_type` set to that `dispatch.agent`. Regardless of whether `agent` is set, ALWAYS still pass `model` explicitly: `dispatch.model` if the profile sets one, otherwise the Delegation table's value above (or your own judgment in `schema` mode) — both shipped agents (`task-orchestrator:implementer`, `task-orchestrator:reviewer`) ship `model: inherit`, and Claude Code resolves the per-invocation Agent-tool `model` first, then the agent's own frontmatter, where `inherit` means the main conversation's model — omitting `model` silently runs the phase owner on the orchestrator's own model instead of the intended assignment.

`effort` has no Agent-tool parameter of its own. In Claude Code it is honored only through the dispatched agent definition's own frontmatter `effort` field, so a profile's `effort` is advisory unless `agent` also names a definition carrying that `effort` — to change effort, point `agent` at a definition with that effort. (A client that calls the model API directly may apply a profile's `effort` field itself.) This rule is for the phase OWNER only — auxiliary dispatches on the same item (test author under `needs-test-author`, planning seats, the docs seat) keep the Delegation table; a work-phase dispatch profile names the phase owner, not every work-phase dispatch.

For a **seat-aware** item (its schema declares `seats:`), read every seat's profile — including the test author and planning seats — from the resolved `dispatchBySeat`, which supersedes the Delegation table for those seats. When a resolved profile pins a `model`, change it through the project's config traits, not by overriding it at dispatch time.

### Batching and prompts

**Project convention: avoid 3+ MCP write calls in a single turn.** Parallelized reads (e.g., `get_context` + `query_items` overview) are fine and encouraged. Delegate bulk MCP write work to the Agent tool with `model: "haiku"` to keep the orchestrator context clean.

Delegation prompts must include entity IDs and full context — subagents start fresh.

### Dispatch contract

**Parallel-tier dispatches follow a dispatch contract.** Where the project's `/implement` skill ships a dispatch-contract template, generate the run's plan file from it; otherwise write one plan file for the wave that pins branch, worktree, file ownership, commit form, build self-check and review scoping. Either way, point every dispatch prompt at that file by absolute path instead of restating those rules inline. Under a run plan (`/task-orchestrator:run-wave`) seat prompts are generated and never reference this file; the contract then serves the orchestrator (fallback hand-dispatches, the post-run commit map) and the reviewers, and anything a seat must know goes in the item's `specification`/`task-scope` note.

### Verification

**Do not delegate verification.** Do not dispatch subagents to verify or double-check your own work. Verification belongs to the schema's review phase (a separate reviewer) or to inline review on Direct tier. Current models self-verify well, so a redundant verification agent adds cost without catching more. This does not cover independent test authoring under the `needs-test-author` trait — dispatching a separate test author is production work the trait requires, not re-verification; the separation between writing code and writing its tests is the point. Redundant double-checking of your own edits remains discouraged.

### Notes are the report

Subagents write findings into their work item's notes; their final message back is 1-2 lines (item ID, outcome, note keys filled). Never ask agents to restate note content in replies.

### Delegation metadata

If your project defines a `delegated` trait (see your schema config), applying it is recommended for Delegated/Parallel items: apply the trait at item creation (`traits: "delegated"` — it appears in `availableTraits` on create responses) or via `manage_items` update before dispatch (per-item form: `items: [{itemId, traits: "delegated"}]`), so the note below is schema-visible rather than convention-only. Then, after each subagent returns, fill its `delegation-metadata` work note — model · isolation · one-line rationale · one-line outcome. The orchestrator fills this, not the subagent (only the orchestrator knows the dispatch details). It feeds `/session-retrospective`'s delegation-alignment scoring; projects that don't define the trait simply skip it.
