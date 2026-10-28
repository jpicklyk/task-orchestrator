---
name: batch-complete
description: "Completes or cancels many items at once, closing out a feature subtree or cleaning up old work; use when a user says: close out this feature, complete everything under X, cancel this workstream, clean up old items, bulk complete, finish this feature, or archive completed work."
argument-hint: "[optional: root item id or title to complete]"
---

# batch-complete - Bulk Complete or Cancel Items

Close a feature subtree, cancel an abandoned workstream or clean up stale items in one call, with a preview first and a report of exactly what applied, was skipped or failed. Shapes are in [`_generated/call-shapes.md`](../_generated/call-shapes.md) (`items_write` `complete_tree`); semantics are in `current/docs/api-reference.md` ("complete_tree"). Ids below are 8-hex shorts and any id field also takes the full UUID. The tool is orchestration-profile only.

## Step 1 - Identify scope

Resolve `$ARGUMENTS` to an id with a search; if several match, show them via `AskUserQuestion`.

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "Auth System", "kinds": ["item"], "limit": 5}}
```

With no argument, classify the request from the conversation:

- **Feature subtree:** "everything under X" - find X and use `rootId`.
- **Specific items:** the user lists names or ids - collect each id and use `itemIds`.
- **Cleanup:** old or stale items - search by title or filter `items_read` `list` (`role`, `modifiedBefore`), collect the ids and use `itemIds`.

If the scope is still unclear, ask: "Which item (or items) do you want to complete? Give a root id, a title fragment or a list of ids."

## Step 2 - Preview impact

```to-call
{"tool": "items_read", "select": "overview", "args": {"itemId": "98d452e9"}}
```

`data.childCounts` is the direct children by role (zero roles omitted; lifecycle-free items are counted under no role and `complete_tree` leaves them out). Show a preview labelled by the trigger (`complete` or `cancel`):

```
Impact preview - "Auth System Feature"  [trigger: complete]
  queue:    3 items (will be completed)
  work:     1 item  (active - will be force-completed)
  review:   1 item  (active - will be force-completed)
  terminal: 2 items (already done - will be skipped)
```

`childCounts` covers direct children only; for a deeper tree read `items_read` `list` with `ancestorId` and count by role. For an `itemIds` scope, `items_read` `get` each item (use `list` for 10 or more).

If any item is in work or review, warn that active work will be force-completed (gates still apply) and ask:

```
2 items are active (work or review). How would you like to proceed?
  1. Proceed - complete everything including active items
  2. Cancel instead - trigger cancel (bypasses note gates)
  3. Abort - leave everything as it is
```

Wait for the answer and record the trigger.

## Step 3 - Gate pre-check

Skip this if `cancel` was chosen: it bypasses note gates (not claim ownership). For `complete` the definitive check runs at execution; a pre-check just surfaces problems early. Read each child's state:

```to-call
{"tool": "items_read", "select": "context", "args": {"itemId": "4b7d0e19"}}
```

`complete` checks the required notes of every phase, so `gateStatus.missing` for the current phase understates what is needed; the `schema[]` entries with `required: true` and `filled: false` are the full list. Display warnings, then ask:

```
Some items have unfilled required notes and will fail the gate. What now?
  1. Fill the notes first, then return here
  2. Use cancel - bypasses note gates, marks items cancelled (an `auto` parent closed by the cascade keeps the cascade label)
  3. Proceed anyway - gated items fail, the rest complete
```

For option 1, resolve each item's guidance with `items_read` `schema` (`fields: ["guidance"]`) and write the notes with `notes_write`. For option 2, switch to `cancel`.

## Step 4 - Execute

Subtree. The descendants run in dependency order and the root runs last (`includeRoot` defaults to true; set it false to leave the root open):

```to-call
{"tool": "items_write", "op": "complete_tree", "args": {"rootId": "98d452e9", "trigger": "complete"}}
```

Specific items (exactly one of `rootId` and `itemIds`):

```to-call
{"tool": "items_write", "op": "complete_tree", "args": {"itemIds": ["4b7d0e19", "6e21c0aa"], "trigger": "complete"}}
```

Cancel (bypasses note gates):

```to-call
{"tool": "items_write", "op": "complete_tree", "args": {"rootId": "98d452e9", "trigger": "cancel"}}
```

Report from `data.results[0]`. `items[]` has one entry per item:

| `outcome` | Meaning |
|---|---|
| `applied` | moved to terminal (`previousRole`, `newRole`) |
| `skipped`, `reason: already_terminal` | already done, or terminalized by a cascade earlier in this call; not a failure |
| `skipped`, `reason: upstream_failed` | an in-set blocker failed (`blockedBy[]` lists it); retry after fixing that item |
| `failed` | rejected; `error.code` says why (`gate_blocked` with `detail.missing`, `dependency_unmet`, `not_claim_holder`, `resource_unavailable`, `invalid_transition`) |

`summary` is `{total, applied, skipped, failed}`. A per-item failure is data: the applied transitions still commit together. A store fault or `unavailable` fails the whole element and rolls it back. An element failure (a store fault, `unavailable`, or `not_found`/`ambiguous_id` on `rootId`) answers call-level `partial_failure`; its code is on `data.results[0].error`.

```
Batch complete - "Auth System Feature"
  OK      Design API schema
  OK      Set up database
  FAILED  Implement login (gate_blocked: implementation-notes)
  SKIPPED Integration tests (upstream_failed: Implement login)
  OK      Auth System Feature (root, last)

  Summary: total 5 | applied 3 | skipped 1 | failed 1
```

## Step 5 - Cleanup (optional)

To fully archive a workstream, confirm first (`AskUserQuestion`: delete everything under X, this cannot be undone), then delete the root with its subtree:

```to-call
{"tool": "items_write", "op": "delete", "args": {"id": "98d452e9", "recursive": true}}
```

A parent without `recursive: true` fails `invalid_request`. The answer's `deleted` counts the item and its descendants.

## Troubleshooting

**Items failed the gate.** Fill each item's missing notes with `notes_write` and rerun `complete_tree`; items already terminal are skipped as `already_terminal`. Or switch to `cancel`.

**Items skipped as `upstream_failed`.** Fix the upstream failure first (fill its notes), then rerun. The rerun processes every item not yet terminal, the formerly `upstream_failed` ones included; terminal ones skip as `already_terminal`.

**`not_found` or `ambiguous_id` on `rootId`.** The id matches nothing, or an 8-hex prefix matches several items. Look it up again with `items_read` `get`, or search by title, and use the full id.

**Everything reports `already_terminal`.** Expected: the workstream is already closed.

**`forbidden`.** The token's scope does not admit the whole tree, so nothing ran.

## Examples

**Close a completed feature.** Preview shows 3 queue items and 1 terminal; no active items, all notes filled. `complete_tree` with `rootId` and `trigger: complete` answers `summary {total: 5, applied: 4, skipped: 1, failed: 0}` (the terminal child is `already_terminal`; the root completes last).

**Cancel an abandoned workstream.** "Cancel the Legacy API Migration feature." The preview shows active work; the user picks cancel. `complete_tree` with `trigger: cancel` moves every item to terminal, with no note gates enforced. Items cancelled directly get `statusLabel: cancelled`; an `auto` parent whose last child is cancelled is closed by the terminal cascade in the same call and keeps the cascade label (`done` by default), then reports `skipped` / `already_terminal`. That includes the root unless its lifecycle is `manual` or `permanent`, or the root is in review or blocked (a cascade does not complete either); `includeRoot: false` does not prevent it, it only leaves the root out of processing. Read the parents' labels before reporting the workstream as cancelled.

**Mixed result.** `complete` over an auth feature applies 3 items, fails `Implement login` (`gate_blocked`, missing `implementation-notes`) and skips its dependent test item as `upstream_failed`. Fill the note, rerun, and the two remaining items are processed.
