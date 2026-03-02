---
name: status-progression
description: Navigate role transitions for MCP work items using advance_item. Shows current role, gate status, required notes, and the correct trigger to use. Use when a user says "advance this item", "move to work", "start this task", "complete this item", "what's the next status", "why can't I advance", "unblock this", "cancel this item", or "check gate status".
argument-hint: "[optional: item UUID or title to look up]"
---

# Status Progression — Current (v3)

Guides role transitions for a WorkItem: identify the item, check gate status, fill missing notes, and advance. Handles all triggers including block, resume, and cancel.

---

## Step 1: Identify the Item

Determine which item to work with before calling anything else.

**If `$ARGUMENTS` looks like a UUID** (8-4-4-4-12 hex pattern), use it directly as the item ID in Step 2.

**If `$ARGUMENTS` is a text string** (title fragment or keyword), search for it:

```
query_items(operation="search", query="$ARGUMENTS", limit=5)
```

If the search returns exactly one result, use that item ID. If it returns multiple results, present them to the user via `AskUserQuestion`:

```
◆ Multiple items matched "$ARGUMENTS" — which did you mean?
  1. "Implement authentication module" (work) — uuid-1
  2. "Implement caching layer" (queue) — uuid-2
  3. "Implement rate limiting" (queue) — uuid-3
```

**If `$ARGUMENTS` is empty**, ask via `AskUserQuestion`: "Which item do you want to advance? Provide a UUID or title fragment."

---

## Step 2: Check Current State

Once you have the item ID, call:

```
get_context(itemId="<item-uuid>")
```

Parse the response and display a status card. Use this format:

```
◉ "Implement authentication module"
  Role:     work
  Gate:     ⊘ blocked — 2 required notes missing
  Missing:  implementation-notes (work, required)
            test-results (work, required)
  Guidance: "Describe what was implemented, which files changed, and why
             the approach was chosen..."
```

```
◉ "Design API schema"
  Role:     queue
  Gate:     ✓ open — all required notes filled (or no schema)
  Next:     advance_item(trigger="start") → work
```

**Fields to surface from `get_context` response:**

| Response Field | What to Show |
|---|---|
| `item.role` | Current role label |
| `gateStatus.canAdvance` | ✓ open or ⊘ blocked |
| `gateStatus.missing` | List each missing note key + role |
| `guidancePointer` | Free text — show as "Guidance:" if present |
| `noteSchema` | List all schema notes with `exists` status |

If the item has no schema (no tags matching a schema key), `noteSchema` will be empty and the gate is always open.

---

## Step 3: Fill Missing Notes (if Gated)

If `gateStatus.canAdvance = false`, the item cannot advance until required notes are filled.

For each missing note, check whether its content can be inferred from the conversation context. If yes, fill it directly. If not, ask the user what to capture.

Use `guidancePointer` to prompt the user — it contains the guidance text for the first unfilled required note in the current phase.

Fill notes with:

```
manage_notes(
  operation="upsert",
  notes=[
    { itemId: "<uuid>", key: "implementation-notes", role: "work", body: "<content>" },
    { itemId: "<uuid>", key: "test-results", role: "work", body: "<content>" }
  ]
)
```

After filling, re-check gate status:

```
get_context(itemId="<uuid>")
```

Confirm `gateStatus.canAdvance = true` before proceeding to Step 4. If notes are still missing after the upsert, show the updated status card and repeat for any remaining gaps.

---

## Step 4: Advance the Item

With the gate open, call `advance_item` using the appropriate trigger (see Trigger Reference below):

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
```

Parse the response and report the transition result:

```
✓ Advanced: queue → work
  ↳ Cascade: "Feature: Auth System" also moved queue → work
  ↳ Unblocked: "Write integration tests" (was waiting on this item)
  ↳ Next phase notes:
      implementation-notes (work, required)
      test-results (work, required)
```

**Fields to check in the advance response:**

| Response Field | What to Report |
|---|---|
| `previousRole` + `newRole` | The core transition |
| `cascadeEvents` | Parent or ancestor items that auto-transitioned |
| `unblockedItems` | Sibling items that are now actionable |
| `expectedNotes` | Notes for the next phase — show as "Next phase notes:" |

If `cascadeEvents` is empty, omit the cascade line. If `unblockedItems` is empty, omit the unblocked line. If `expectedNotes` is empty or absent (no schema), omit the next phase notes line.

---

## Trigger Reference

Choose the trigger based on the item's current role and the desired outcome:

| Trigger | Transition | When to Use |
|---------|-----------|-------------|
| `start` | QUEUE → WORK | Begin working on a planned item |
| `start` | WORK → REVIEW (or TERMINAL if no review schema) | Implementation complete, ready for verification |
| `start` | REVIEW → TERMINAL | Review passed, close the item |
| `complete` | Any non-terminal → TERMINAL | Jump straight to done — skips remaining phases |
| `cancel` | Any non-terminal → TERMINAL | Abandon the item — no note gates enforced |
| `block` | Any non-terminal → BLOCKED | Pause work — saves `previousRole` for later resume |
| `resume` | BLOCKED → previousRole | Return to the role the item was in before blocking |

**Important notes:**

- `start` checks gates for the **current phase only** — missing notes for the current phase block the transition; future-phase notes are not checked
- `complete` checks gates across **all phases** — all required notes across queue, work, and review must be filled before terminal is reached
- `cancel` does **not** check gates — items can be cancelled at any time regardless of missing notes; `statusLabel` is set to `"cancelled"`
- `block` and `resume` are a paired workflow — `block` saves `previousRole` internally so `resume` always returns to the exact role before blocking; do not use `start` to resume a blocked item
- When an item reaches TERMINAL, any items that had a `BLOCKS` dependency on it become unblocked — they appear in `unblockedItems` in the advance response
- Cascade events: the **first child** to start from queue triggers its parent to cascade queue → work; the **last child** to reach terminal triggers its parent to cascade → terminal

---

## Troubleshooting

**Problem: `advance_item` fails with "required notes not filled"**

Cause: The current phase has required notes that have not been upserted yet. Gate enforcement runs before the transition executes.

Solution: Call `get_context(itemId="<uuid>")` to see exactly which notes are missing. Fill each one with `manage_notes(operation="upsert")`, then retry `advance_item`.

---

**Problem: Item cannot advance — it is blocked by a dependency**

Cause: Another item has a `BLOCKS` edge pointing to this item, and that blocking item has not yet reached terminal role.

Solution: Find the blocker:

```
query_dependencies(itemId="<uuid>", direction="incoming", includeItemInfo=true)
```

Identify the blocking item (role will be non-terminal). Advance the blocking item to terminal first. When it completes, the current item appears in `unblockedItems`.

---

**Problem: Item is in BLOCKED role and `start` fails**

Cause: The item is in the BLOCKED role (was explicitly blocked with `trigger: "block"`). The `start` trigger is not valid from BLOCKED — it is only valid from QUEUE, WORK, or REVIEW.

Solution: Use `trigger: "resume"` to return the item to its previous role:

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "resume" }])
```

After resuming, check `get_context` and then advance normally with `start` if the gate is open.

---

**Problem: Want to skip the review phase and go directly to terminal**

Cause: The item is in WORK role and has a review-phase schema, but verification is already done or not applicable.

Solution: Use `trigger: "complete"` instead of `start`. This jumps from any non-terminal role directly to TERMINAL, but it checks ALL required notes across all phases first:

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "complete" }])
```

If any required notes across queue, work, or review phases are unfilled, the gate will block this call and list the missing notes. Fill them, then retry.

---

**Problem: Parent item cascaded unexpectedly**

Cause: Cascade is by design. When the first child of a container starts (queue → work), the container cascades to work automatically. When the last child reaches terminal, the container cascades to terminal automatically.

Solution: This is expected behavior — no action needed. Check `cascadeEvents` in the `advance_item` response to see exactly which ancestors transitioned and why. If the cascade is unwanted, you can manually adjust the parent's role using `advance_item` with `trigger: "block"` or `trigger: "complete"` depending on the desired state.

---

**Problem: `advance_item` returns "no valid transition" or "item already terminal"**

Cause: The item is already in TERMINAL role (completed or cancelled). Terminal is a final state — no triggers are valid from terminal.

Solution: The item cannot be advanced further. If the item was completed in error, you would need to create a new item. To verify the item's current state:

```
query_items(operation="get", id="<uuid>")
```

Check the `role` field. If `role = "terminal"`, the item's lifecycle is complete.

---

## Examples

### Example 1: Simple Flow — No Schema

For items with no matching note schema, there are no gates. Items flow freely through roles.

**Step 1: Check state**

```
get_context(itemId="abc-123")
```

Response shows `role: "queue"`, `gateStatus.canAdvance: true`, `noteSchema: []`.

Status card:
```
◉ "Refactor database connection pool"
  Role:  queue
  Gate:  ✓ open (no schema)
  Next:  advance_item(trigger="start") → work
```

**Step 2: Start work**

```
advance_item(transitions=[{ itemId: "abc-123", trigger: "start" }])
```

Result:
```
✓ Advanced: queue → work
```

**Step 3: Complete work (skip review)**

After the refactor is done, complete directly:

```
advance_item(transitions=[{ itemId: "abc-123", trigger: "complete" }])
```

Result:
```
✓ Advanced: work → terminal
```

No gates — no notes required. Items without a schema move freely at any time using any valid trigger.

---

### Example 2: Gated Flow — Item Has `feature-implementation` Tag

Items tagged `feature-implementation` have a schema with required notes at each phase. The gate blocks advancement until notes are filled.

**Step 1: Check state**

```
get_context(itemId="def-456")
```

Response shows `role: "queue"`, `gateStatus.canAdvance: false`, missing: `["requirements"]`.

Status card:
```
◉ "Add OAuth2 login flow"
  Role:     queue
  Gate:     ⊘ blocked — 1 required note missing
  Missing:  requirements (queue, required)
  Guidance: "Document the acceptance criteria and scope of this feature.
             Include: what the feature does, what it does not do, and
             the definition of done."
```

**Step 2: Fill the missing note**

Ask the user (or extract from conversation context) what the requirements are, then upsert:

```
manage_notes(
  operation="upsert",
  notes=[{
    itemId: "def-456",
    key: "requirements",
    role: "queue",
    body: "Implement OAuth2 login via GitHub and Google providers. Users should
           be redirected to provider, authenticated, and returned to the app
           with a session token. Out of scope: social sign-up flow, profile
           linking. Done when: login button visible on /login, both providers
           work in staging, session persists across page reload."
  }]
)
```

**Step 3: Re-check gate**

```
get_context(itemId="def-456")
```

Updated status card:
```
◉ "Add OAuth2 login flow"
  Role:  queue
  Gate:  ✓ open — all queue notes filled
  Next:  advance_item(trigger="start") → work
```

**Step 4: Advance to work**

```
advance_item(transitions=[{ itemId: "def-456", trigger: "start" }])
```

Result:
```
✓ Advanced: queue → work
  ↳ Next phase notes:
      implementation-notes (work, required)
      test-results (work, required)
```

The `expectedNotes` in the response shows what must be filled during the work phase before the next `start` will succeed. Fill these notes as implementation progresses, then call `advance_item(trigger="start")` again to move to review or terminal.

---

## Quick Decision Guide

| Situation | Action |
|---|---|
| Item is in queue, no gate | `advance_item(trigger="start")` |
| Item is in queue, gate blocked | Fill missing queue notes → `advance_item(trigger="start")` |
| Item is in work, ready for review | `advance_item(trigger="start")` |
| Item is in work, skip review | `advance_item(trigger="complete")` — checks all gates |
| Item is in review, verified | `advance_item(trigger="start")` |
| Item needs to be paused | `advance_item(trigger="block")` |
| Item is in BLOCKED role | `advance_item(trigger="resume")` first |
| Item should be abandoned | `advance_item(trigger="cancel")` — no gates |
| Item is terminal | No further transitions possible |
| Blocker is another item | Advance the blocking item first |
