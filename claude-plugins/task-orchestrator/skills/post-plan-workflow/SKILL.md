---
name: post-plan-workflow
description: "Internal, hook-triggered when a plan is approved: materializes MCP items from the plan and dispatches implementation."
user-invocable: false
---

# Post-Plan Workflow - Materialize and Implement

Plan approval is the green light for the full pipeline. Run all three phases without stopping; the one expected stop is the turn that ends when a run-wave Method A launch hands control to its async notification (Phase 2, Route). Shapes are in [`_generated/call-shapes.md`](../_generated/call-shapes.md); ids below are 8-hex shorts.

## Phase 1: Materialize

Finish materialization **before** any implementation begins.

**Prefer the stashed plan.** The `plan-capture` hook stashes the approved plan as a plan document when ExitPlanMode fires and reports the slug in its context (`plan stashed as plan document '<slug>' (root <rootId>)`). Confirm it and read it:

```to-call
{"tool": "docs_read", "select": "list", "args": {"rootId": "9f3a1c22", "kind": "plan"}}
```

A plan with no `adoptedBy` is still pending. `docs_read` `get` with `kind: "plan"` and the `slug` returns the body. Quote or anchor its sections instead of retyping them; with no stashed document (stdio setups, hook failed open) use the plan text in context.

1. **Create the items** with one `create_tree` call (all-or-nothing, so there is no partial state to repair). Every item needs a declared `type` (read `config effective` `types`; see create-item Step 4); `traits` and `tags` are arrays. Anchor the root under the project: set `parentId` to the project rootId (or a category container beneath it) when known. Dedup first, once per new root or feature (children inherit): one unscoped `items_read` `search` (`kinds: ["item"]`, `limit: 5`); unscoped on purpose so legacy depth-0 and other-root items show. Show close matches as one FYI line and keep going; never auto-link, auto-skip or auto-cancel.

```to-call
{"tool": "items_write", "op": "create_tree", "args": {"root": {"title": "Add retry layer", "type": "feature-implementation"}, "parentId": "9f3a1c22", "docRef": {"slug": "retry-layer"}, "children": [{"ref": "t1", "title": "Backoff policy", "type": "feature-task", "noteAnchors": [{"noteKey": "task-scope", "role": "queue", "anchor": "backoff-policy"}]}, {"ref": "t2", "title": "Retry tests", "type": "feature-task"}], "deps": [{"from": "t1", "to": "t2"}]}}
```

   `docRef` plus `noteAnchors` cut plan sections into notes and the root adopts the document; `notes[]` writes explicit bodies. The result is `{ok, id, version, items, deps, notes}` and carries no `expectedNotes`.

2. **Dependencies.** A `blocks` edge (the `deps` array, or `deps_write` later) is for a semantic dependency only: the dependent cannot start meaningfully until the other item is done.
   - Give each edge a `Depends on <short>: <reason>` line on the dependent's spec note (write it once `items[]` gives the ids).
   - Items that only share files in one shared-worktree run get NO edge; the per-file lock serializes same-file edits. Record each shared file as `Shared file, lock-serialized: <path> (with <short>)` in both items' scopes.
   - Qualifications: this holds in shared-worktree mode only; an edge that unblocks at review or terminal costs a separate run, because the dependent cannot enter the run until the blocker reaches that role; per-item worktree mode still defers on overlap.

3. **Fill queue notes.** Read the schema (a create returns no `expectedNotes`):

```to-call
{"tool": "items_read", "select": "schema", "args": {"itemId": "4b7d0e19", "fields": ["guidance"]}}
```

   `data` is `{schemaFree: true}` for a schema-free item. Otherwise fill each required queue note (`feature-summary`, `task-scope`, ...) from the plan, in one `notes_write` call with an `upsert` element per note.
   - **`feature-implementation` root:** keep `feature-summary` lean (under 2k chars): goal in 2-3 sentences, a findings-to-tasks table mapping plan findings to the child items just created, the dependency edges between them, and a pointer to non-goals. Alternatives, blast radius, risk flags and test strategy go in each child's `task-scope`.
   - **Test owner flag.** Detect seats from the `items_read` `schema` answer (`seats`). For each seat-aware item that lists no test-author seat and whose spec note names test files or test commands, show one FYI line: `test owner: <short> lists tests without needs-test-author - add the trait (preferred) or drop the tests; run-wave stops a seat-aware item after its planner (a seat-less item is not stopped; its implementer gets a TESTS ARE YOURS line instead)`. Never add the trait automatically.

4. **Verify the graph.** Confirm every id in `items[]` exists:

```to-call
{"tool": "items_read", "select": "overview", "args": {"itemId": "4b7d0e19"}}
```

Do not dispatch implementation agents until materialization is complete; they need the item ids to report progress.

## Phase 2: Implement

### Route: run-wave or hand-dispatch

Hand off to `/task-orchestrator:run-wave` only when ALL four conditions hold. Each is a concrete probe:

1. **Two or more unblocked leaf items.** At least two leaves (no children) were materialized and none appears in the blocked list:

```to-call
{"tool": "items_read", "select": "blocked", "args": {"ancestorId": "4b7d0e19"}}
```

2. **A rootId resolves** (project or personal), in run-wave Step 1's order: session context (`Active project:` or `Personal root:` line), then `project.rootId` from the file on that context's `Config:` line, then `.taskorchestrator/config.yaml`.
3. **Queue notes are filled.** For each leaf, `items_read` `context` with its `itemId` shows no missing queue notes in `gateStatus.missing`, or its schema read is `{schemaFree: true}`.
4. **Protocol rules are served.** This lists `protocol.entry-seat`, `protocol.in-phase-seat` and `protocol.read-only-agent` (a seed made earlier this session by init or the run-wave repair shows up):

```to-call
{"tool": "config", "op": "rules", "args": {"rootId": "9f3a1c22"}}
```

If all four hold, invoke `/task-orchestrator:run-wave <materialized root id>` and follow it. Plan approval counts as run-wave's Checkpoint 1. When a degradation forces a human look (`meta.excluded` or `meta.degradations` non-empty, or an empty run), show the `explain` table and end the turn; never use `AskUserQuestion`. A Method A launch ending the turn is expected. run-wave's post-run replaces Phase 3, which then applies only to the hand-dispatch path.

If any condition fails, hand-dispatch and add one line naming the failed condition(s); for conditions 2 and 4 point at `/task-orchestrator:init`.

### Hand-dispatch

- Each subagent **owns one item**: put the item id in the delegation prompt.
- Resolve each note's `guidance` and `skill` with `items_read` `schema` (`fields: ["guidance"]`) and embed the guidance as authoring instructions. When a note has a `skill`, add: "Before filling the `<key>` note, invoke `/<skill>` and follow its framework."
- **Agents own phase entry only.** Each agent calls `advance` `transition` with `trigger: "start"` once to enter work, fills the work-phase notes and returns:

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "4b7d0e19", "trigger": "start"}}
```

- Respect dependency order: do not dispatch an agent for a blocked item until its blockers complete.
- **Between waves** run `items_read` `blocked` with the feature's `ancestorId` (it catches blockers anywhere in the subtree, not just direct children). An item still blocked means an upstream item never transitioned: investigate it.
- Do **not** transition delegated items to terminal yourself before their agents return; the orchestrator reviews and advances them afterwards.

Do not use `AskUserQuestion` between phases.

## Phase 3: Verify

After all agents return:

1. List items still in `queue` (agents that failed leave their items there; the `role: "work"` list is what you review and advance next) and diagnose them with `/status-progression`:

```to-call
{"tool": "items_read", "select": "list", "args": {"ancestorId": "4b7d0e19", "role": "queue"}}
```

2. Run the health check to see what completed, stalled or is blocked: `items_read` `context` with the same `ancestorId`.
3. For each stalled item, `items_read` `context` with its `itemId` shows `gateStatus.missing`.
4. Address blockers or incomplete work.

## Workflow complete

Report the final status: what completed, what needs attention, what is still in progress.
