---
name: batch-complete
description: Complete or cancel multiple items at once — close out features, clean up old work, archive completed workstreams. Use when a user says "close out this feature", "complete everything under X", "cancel this workstream", "clean up old items", "bulk complete", "finish this feature", or "archive completed work".
argument-hint: "[optional: root item UUID or title to complete]"
---

# batch-complete — Bulk Complete or Cancel Items

Close out a feature subtree, cancel an abandoned workstream, or clean up stale items in one operation. Handles gate checks, active-item warnings, and reports exactly what succeeded and what was skipped.

---

## Step 1 — Identify Scope

Determine what to complete before calling anything else.

**If `$ARGUMENTS` looks like a UUID** (8-4-4-4-12 hex pattern), use it directly as the root item ID in Step 2.

**If `$ARGUMENTS` is a text string** (title fragment or keyword), search for it:

```
query_items(operation="search", query="$ARGUMENTS", limit=5)
```

If the search returns exactly one result, use that item ID. If multiple results match, present them via `AskUserQuestion`:

```
◆ Multiple items matched "$ARGUMENTS" — which did you mean?
  1. "Auth System Feature" (queue) — uuid-1
  2. "Auth Token Refresh" (work) — uuid-2
```

**If `$ARGUMENTS` is empty**, classify the request from conversation context:

- **Feature subtree**: user mentions completing "everything under" a named item → search for that item, use `rootId`
- **Specific items**: user lists names or IDs → collect each UUID, use `itemIds`
- **Cleanup**: user wants to clear old/stale items → search by status or title fragment, collect UUIDs, use `itemIds`

If scope still cannot be determined, ask via `AskUserQuestion`: "Which item (or items) do you want to complete? Provide a root UUID, a title fragment, or a list of item IDs."

---

## Step 2 — Preview Impact

Before executing, show the user what will happen. Call:

```
query_items(operation="overview", itemId="<rootId>")
```

Parse the child counts by role and present a preview table:

```
◆ Impact Preview — "Auth System Feature"
  ○ queue:    3 items (will be completed)
  ◉ work:     1 item  (active — will be force-completed)
  ◉ review:   1 item  (active — will be force-completed)
  ✓ terminal: 2 items (already done — will be skipped)
```

**For `itemIds` path** (no root item): call `query_items(operation="get", id="<uuid>")` on each item and build the same role-grouped preview table from the individual results. For large lists (10+ items), use `query_items(operation="search")` with filters instead of individual get calls.

**If any items are in `work` or `review`**, warn the user that active work will be force-completed (gate checks still apply). Use `AskUserQuestion` with three options:

```
◆ 2 items are currently active (work or review).
  How would you like to proceed?
  1. Proceed — complete everything including active items
  2. Cancel active items instead — use trigger="cancel" (bypasses gates)
  3. Abort — leave everything as-is
```

Wait for the user's choice before continuing. Record whether to use `trigger="complete"` or `trigger="cancel"`.

---

## Step 3 — Gate Check

**Skip this step if `trigger="cancel"` was already chosen in Step 2** — cancel bypasses all gates, so gate checking is unnecessary.

For `trigger="complete"`, check gate status. Call `get_context` on the root item (or each specific item if using `itemIds`):

```
get_context(itemId="<rootId>")
```

If gate warnings exist, display them:

```
⊘ Gate Warnings:
  "Implement login" — missing: implementation-notes (work, required)
  "Write tests" — missing: test-results (work, required)
```

Then offer three options via `AskUserQuestion`:

```
◆ Some items have unfilled required notes and will be skipped by the gate.
  What would you like to do?
  1. Fill notes first — use manage_notes(operation="upsert") to fill each item's required notes, then return here
  2. Use cancel trigger — bypasses all gates, marks items as "cancelled"
  3. Proceed anyway — gated items will be skipped, others will complete
```

Call `get_context(itemId=...)` to retrieve `guidancePointer` for items with missing notes. Use the guidance as authoring instructions before filling.

Wait for the user's choice. If they choose option 2, switch to `trigger="cancel"` for the execution step.

---

## Step 4 — Execute

Call `complete_tree` with the chosen trigger:

**Feature subtree (rootId):**
```
complete_tree(rootId="<uuid>", trigger="complete")
```

**Specific items (itemIds):**
```
complete_tree(itemIds=["<uuid-1>", "<uuid-2>", "<uuid-3>"], trigger="complete")
```

**Cancel variant (bypasses gates):**
```
complete_tree(rootId="<uuid>", trigger="cancel")
```

Parse the response and present results:

```
✓ Batch Complete — "Auth System Feature"
  ✓ Design API schema — completed
  ✓ Set up database — completed
  ⊘ Implement login — skipped (gate: missing implementation-notes)
  ✓ Write unit tests — completed
  — Integration tests — skipped (dependency on "Implement login")

  Summary: 3/5 completed | 1 gate failure | 1 dependency skip
```

Use these symbols:
- `✓` — applied: true (completed or cancelled)
- `⊘` — gate failure (gateErrors present)
- `—` — skipped due to dependency on a failed item

---

## Step 5 — Cleanup (Optional)

If the user wants to delete the completed items after finishing (to fully archive a workstream), confirm via `AskUserQuestion`:

```
◆ Delete all items under "Auth System Feature" after completing?
  This cannot be undone.
  1. Yes, delete them
  2. No, keep them in terminal state
```

If confirmed, delete with:

```
manage_items(operation="delete", ids=["<root-uuid>"], recursive=true)
```

Report what was deleted:

```
✓ Deleted: "Auth System Feature" and all 5 descendants
```

---

## Complete vs Cancel Reference

| Aspect | trigger="complete" | trigger="cancel" |
|--------|-------------------|-----------------|
| Gate enforcement | All required notes across all phases must be filled | None — bypasses all gates |
| Final role | terminal | terminal |
| statusLabel | (not set) | "cancelled" |
| Use when | Work is genuinely done and notes are filled | Abandoning, discarding, or force-closing |
| Skips items | Yes — gate failures and dependency skips | No gate skips; dependency order still respected |

## complete_tree Response Fields

| Field | Meaning |
|-------|---------|
| `applied: true` | Item was transitioned to terminal |
| `skipped: true` | Item was not transitioned (see skippedReason) |
| `skippedReason` | "already terminal", "dependency gate failed", or "gate failed" |
| `gateErrors` | Array of missing required note keys that blocked completion |

---

## Troubleshooting

**Problem: Items are skipped due to gate failures**

Cause: The item has required notes that have not been filled. Gate enforcement runs before each transition and blocks completion.

Solution: Fill each item's missing required notes with `manage_notes(operation="upsert")`, then rerun `complete_tree`. Alternatively, switch to `trigger="cancel"` to bypass all gates and force-close the items.

---

**Problem: Items are skipped due to dependency ordering**

Cause: An upstream item in the tree failed its gate check. Any item that depends on it (via BLOCKS edges) is automatically skipped in the same run.

Solution: Fix the upstream gate failure first (fill required notes), then run `complete_tree` again. Only the previously-failed items need to be processed — items already in terminal are skipped silently on subsequent runs.

---

**Problem: rootId not found or complete_tree returns an error**

Cause: The UUID provided does not match any existing item, or the item has already been deleted.

Solution: Verify the UUID with `query_items(operation="get", id="<uuid>")`. If not found, search by title fragment: `query_items(operation="search", query="<title>")`. Use the returned UUID.

---

**FAQ: All items reported as skipped with "already terminal"**

This is expected behavior, not an error. Items already in terminal role are intentionally skipped — they were completed or cancelled in a prior run. If the summary shows all items skipped with "already terminal", the workstream is already closed. No further action is needed.

---

## Examples

### Example 1: Close a Completed Feature

All items are ready; notes are filled; clean completion with no skips.

**Step 2 preview shows:**
```
◆ Impact Preview — "Payment Integration"
  ○ queue:    3 items (will be completed)
  ◉ work:     0 items
  ◉ review:   0 items
  ✓ terminal: 1 item  (already done — will be skipped)
```

No active items — no warning needed. Gate check shows all required notes filled. Proceed directly.

**Step 4 execute:**
```
complete_tree(rootId="pay-uuid", trigger="complete")
```

Result:
```
✓ Batch Complete — "Payment Integration"
  ✓ Design payment schema — completed
  ✓ Implement Stripe API — completed
  ✓ Write payment tests — completed
  ✓ Payment Integration — completed (cascade from last child)

  Summary: 4/4 completed | 0 gate failures | 0 dependency skips
```

---

### Example 2: Cancel an Abandoned Workstream

The feature was scoped out. Force-cancel everything without filling notes.

User says: "Cancel the 'Legacy API Migration' feature — we're not doing it."

**Step 2 preview shows active items in work.** User is warned and chooses option 2 (cancel active items).

**Step 3 gate check** — user chooses option 2 (use cancel trigger) to avoid filling notes.

**Step 4 execute:**
```
complete_tree(rootId="legacy-uuid", trigger="cancel")
```

Result:
```
✓ Batch Cancel — "Legacy API Migration"
  — Audit legacy endpoints — cancelled
  — Map replacement routes — cancelled
  — Update client libraries — cancelled
  — Legacy API Migration — cancelled

  Summary: 4/4 cancelled | 0 gate failures | 0 dependency skips
```

No gates enforced. All items reach terminal with statusLabel="cancelled".

---

### Example 3: Mixed Result with Gate Failures

Some items complete cleanly; others are missing required notes.

**Step 4 execute:**
```
complete_tree(rootId="auth-uuid", trigger="complete")
```

Result:
```
✓ Batch Complete — "Auth System"
  ✓ Design auth schema — completed
  ✓ Set up user table — completed
  ⊘ Implement login — skipped (gate: missing implementation-notes)
  — Write integration tests — skipped (dependency on "Implement login")
  ✓ Write unit tests — completed

  Summary: 3/5 completed | 1 gate failure | 1 dependency skip
```

Follow up: fill `implementation-notes` on "Implement login" with `manage_notes(operation="upsert")`, then rerun `complete_tree`. The two remaining items will be processed on the next run.
