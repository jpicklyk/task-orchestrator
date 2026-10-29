---
name: dependency-manager
description: "Visualizes, creates, deletes and diagnoses dependencies between MCP work items; use when a user says: what blocks this, add a dependency, show dependency graph, why can't this start, link these items, unblock this, remove dependency, or show blockers."
argument-hint: "[optional: item id, title, or action like 'show blockers for X']"
---

# dependency-manager - Dependency Visualization, Creation and Diagnosis

Manage `blocks` and `relates_to` edges between work items: view them, create them, delete them and diagnose why an item cannot start. Shapes are in [`_generated/call-shapes.md`](../_generated/call-shapes.md) (`deps_read`, `deps_write`); semantics are in `current/docs/api-reference.md` ("deps_read", "deps_write"). Ids below are 8-hex shorts and any item id field also takes the full UUID. Read every answer through the envelope: `ok`, then `data.results[i]` (`ok` or `error`) and `data.summary {ok, failed}`. When a write element fails, the call-level `error` is only `partial_failure` (no detail, no fix): read `code`, `kind` (retry class), `detail` and `fix` from `data.results[i].error`. A read (`select`) call reports its failure in the call-level `error`.

## Step 1: Determine intent

Resolve `$ARGUMENTS` to an item id with a search; if several match, show them via `AskUserQuestion`. An argument that is already an id defaults to VIEW.

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "REST endpoints", "kinds": ["item"], "limit": 5}}
```

With no argument, infer the intent from the conversation, else ask: "What would you like to do with dependencies? view, create, delete or diagnose?" For a compound request (show what blocks this, then remove it) do VIEW first, then act on the result.

| Signal words | Path |
|---|---|
| show, view, graph, what blocks, what depends on | VIEW (Step 2) |
| add, create, link, connect, chain, depend on | CREATE (Step 3) |
| remove, delete, unlink, disconnect | DELETE (Step 4) |
| why can't this start, why is this blocked, diagnose, show blockers, unblock | DIAGNOSE (Step 5) |

## Step 2: View dependencies

```to-call
{"tool": "deps_read", "select": "list", "args": {"item": "4b7d0e19", "direction": "all"}}
```

`data.items[]` is `{id, from, to, type, unblockAt?}` with full UUIDs; `from` blocks `to`. `incoming` is the edges whose `to` is the item (what blocks it), `outgoing` those whose `from` is the item. `type` filters to `blocks` or `relates_to`. Titles and roles are not in the edge: read the other ends with `items_read` `get` (or `list`). Draw a tree:

```
* Design API schema (work)
  |-- blocks --> o Implement data models (queue)
  |-- blocks --> o Build REST endpoints (queue)
  `-- blocked by <-- * Finalize data contract (work)
```

Symbols: `v` terminal, `*` work or review, `o` queue, `x` blocked. For the whole chain around the item:

```to-call
{"tool": "deps_read", "select": "graph", "args": {"item": "4b7d0e19"}}
```

`data.items[]` is `{id, level}` (blockers first; `level` 0 has no blocker) or `{id, cyclic: true}`; `blocks` edges only, `relates_to` is ignored. After the tree, offer DIAGNOSE for any blocked item.

## Step 3: Create dependencies

Pick the shape from what the user described:

| Situation | Op | Fields |
|---|---|---|
| Two items | `create` | `from` (the blocker), `to`, `type?`, `unblockAt?` |
| A sequence A then B then C | `linear` | `ids: [A, B, C]` |
| One item blocks many | `fan_out` | `from`, `toIds` |
| Many items block one | `fan_in` | `fromIds`, `to` |

A pattern is one unit: all its edges are created or none (a duplicate or a cycle anywhere leaves nothing written). Confirm the derived edges first ("About to create A -> B -> C -> D as a chain. Proceed?"). `type` is lowercase `blocks` (default) or `relates_to`; the old `IS_BLOCKED_BY` alias is rejected, so name the blocker as `from`.

```to-call
{"tool": "deps_write", "op": "linear", "args": {"ids": ["4b7d0e19", "6e21c0aa", "9f3a1c22", "0b6d4e11"]}}
```

```to-call
{"tool": "deps_write", "op": "create", "args": {"from": "4b7d0e19", "to": "6e21c0aa", "type": "blocks", "unblockAt": "review"}}
```

Several ops can share one `deps_write` call (`ops[]`); each commits on its own unless `atomic` is true. The answer is `{id, from, to, type, unblockAt?}` for `create` and `{created: [edge, ...]}` for a pattern. A `blocks` edge to or from a lifecycle-free item is `invalid_request`; `relates_to` is allowed. Report:

```
Created 3 edges: A blocks B, B blocks C, C blocks D
```

## Step 4: Delete dependencies

List the edges first so the user can choose:

```to-call
{"tool": "deps_read", "select": "list", "args": {"item": "6e21c0aa", "direction": "all"}}
```

Show them with their `id`, confirm, then delete in one of three modes:

| Mode | Fields | Use |
|---|---|---|
| By edge id | `id` (the full dependency UUID, not a prefix) | one specific edge |
| By pair | `from`, `to`, optional `type` | the edge between two known items; without `type` it matches either type |
| All for an item | `item` | every edge touching the item, either end |

```to-call
{"tool": "deps_write", "op": "delete", "args": {"from": "4b7d0e19", "to": "6e21c0aa", "type": "blocks"}}
```

The answer is `{deleted: n}`. An unknown `id` is `not_found`; a pair or item that matches nothing deletes 0.

## Step 5: Diagnose blocked items

**One item.** Read its incoming edges and its gate state:

```to-call
{"tool": "deps_read", "select": "list", "args": {"item": "4b7d0e19", "direction": "incoming"}}
```

```to-call
{"tool": "items_read", "select": "context", "args": {"itemId": "4b7d0e19"}}
```

`gateStatus.blockedBy` names the first failing gate (`table`, `dependency`, `note`, `lease`); for a dependency block `blockers[]` lists `{id, role?, threshold}` for the unsatisfied ones, and for a lease block `contended` lists the resource keys.

**Broad view.** All blocked work, or one subtree:

```to-call
{"tool": "items_read", "select": "blocked", "args": {"ancestorId": "98d452e9"}}
```

Each entry has `blockType` (`explicit` for the blocked role, `dependency` for an unmet edge) and `blockers[]` with `threshold` and `satisfied` for EVERY blocking edge, satisfied ones included. An explicit entry also has `reason`; an `awaiting_decision` entry adds `decisionId` and `resumable`. A `table` gate on a blocked item means an explicit block, not an edge: read `blockedReason`.

For an explicit block, act on the `reason`:

| `reason` | Needed |
|---|---|
| `awaiting_decision`, `resumable: false` | the decision must be answered (director board); do not resume |
| `awaiting_decision`, `resumable: true` | answered or withdrawn: resume per [decisions.md](../status-progression/references/decisions.md) |
| `resource` | lease contention: read `context` `resourceLeases`, retry later |
| `manual` | `resume` via `/status-progression` |

For each unsatisfied blocker decide what must happen:

| Blocker role | `threshold` | Needed |
|---|---|---|
| queue | terminal | start and complete it |
| work | terminal | complete it |
| work | review | advance it to review |
| review | terminal | advance it to terminal |
| blocked | any | the blocker is itself stuck: diagnose it too |

```
x "Build REST endpoints" cannot start because:
  Blocker 1: Design API schema (work)   must reach terminal
  Blocker 2: Write OpenAPI spec (queue) must reach terminal
```

Not every "cannot start" is a dependency. A `resource_unavailable` failure (transient) on `start` means a shared resource the item's traits declare is held by another item; the item stays in its role and no edge exists. If no incoming edge explains the symptom, look at `context` `gateStatus.contended` and `resourceLeases` and treat it as contention, not a dependency problem.

Then point to the fix: `/status-progression` on the blocker, or fill its missing notes with `notes_write`.

## Reference

| Type | Meaning |
|---|---|
| `blocks` | `from` must reach `unblockAt` before `to` can proceed |
| `relates_to` | informational; no blocking; `unblockAt` is rejected |

| `unblockAt` | The dependent unblocks when the blocker |
|---|---|
| `terminal` (default) | finishes entirely |
| `review` | enters review |
| `work` | starts work |
| `queue` | exists (ordering only) |

## Troubleshooting

**`cycle_detected` on create.** The edge or pattern would close a loop (`data.results[i].error.detail.path`). One edge is backwards: flip `from` and `to`.

**`duplicate`.** The edge exists already; list it instead.

**Item is still blocked after the blocker finished.** Either `unblockAt` names a role the blocker has not reached, or other incoming edges remain. List incoming edges and check each blocker. If every blocker is satisfied and the item sits in the blocked role, an explicit `block` is the cause: read its `reason` (table above). Resume it via `/status-progression` only when it is not a pending decision.

**`not_found` on delete by `id`.** The edge is gone or the id is from elsewhere. List again and use an `id` from the fresh answer.

**Wrong pattern fields.** `linear` takes `ids`; `fan_out` takes `from` and `toIds`; `fan_in` takes `fromIds` and `to`. A wrong name is `unknown_parameter`. Re-list the item and delete incorrect edges with Step 4.

## Examples

**View.** "Show me what blocks the REST endpoints task." Search gives `6e21c0aa`; list incoming edges, read the two blocker items, and draw: both blockers must reach terminal; use `/status-progression` on each.

**Chain.** "Set up A, B, C, D as a chain." Resolve the ids, confirm, send one `linear` op, report 3 edges.

**Diagnose.** "Why can't Write integration tests start?" List incoming edges, read `context` for `blockers[]`, report the two unsatisfied blockers and recommend completing them.

## Quick decision guide

| Situation | Action |
|---|---|
| What blocks an item | `deps_read list`, `direction: incoming` |
| What an item blocks | `deps_read list`, `direction: outgoing` |
| Full chain | `deps_read graph` |
| Link two items | `deps_write create` |
| Sequential list | `deps_write linear` |
| One before many | `deps_write fan_out` |
| Many before one | `deps_write fan_in` |
| Remove a link | list, confirm, `deps_write delete` |
| Stuck, cause unknown | Step 5 |
| Need to advance a blocker | `/status-progression` on it |
