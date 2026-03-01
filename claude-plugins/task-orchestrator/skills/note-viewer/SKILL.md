---
name: note-viewer
description: Read, search, and edit notes on MCP work items — view requirements, update implementation docs, check what's been captured. Use when a user says "show me the notes", "what are the requirements", "read the design doc", "update implementation notes", "what notes does this have", "show requirements for", or "edit the notes on".
argument-hint: "[optional: item UUID or title to view notes for]"
---

# note-viewer — Read, Search, and Edit Item Notes

View, fill, and update notes on MCP work items. Handles schema-defined notes, ad-hoc notes, gate status, and content editing in one flow.

---

## Step 1 — Identify the Item

Determine which item to work with before calling anything else.

**If `$ARGUMENTS` looks like a UUID** (8-4-4-4-12 hex pattern), use it directly as the item ID in Step 2.

**If `$ARGUMENTS` is a text string** (title fragment or keyword), search for it:

```
query_items(operation="search", query="$ARGUMENTS", limit=5)
```

If the search returns exactly one result, use that item ID. If it returns multiple results, present them via `AskUserQuestion`:

```
◆ Multiple items matched "$ARGUMENTS" — which did you mean?
  1. "Implement OAuth2 Login" (work) — uuid-1
  2. "Implement API Gateway" (queue) — uuid-2
```

**If `$ARGUMENTS` is empty**, ask via `AskUserQuestion`: "Which item do you want to view notes for? Provide a UUID or title fragment."

---

## Step 2 — Show Note Overview

Once you have the item ID, call:

```
get_context(itemId="<uuid>")
```

Display a checklist of all notes (schema-defined and existing), grouped by phase. Use this format:

```
◆ Notes — "Implement OAuth2 Login"
  Role: work | Gate: ⊘ blocked — 1 required note missing

  Queue Phase:
    ✓ requirements — filled
    ✓ acceptance-criteria — filled
  Work Phase:
    ⊘ implementation-notes — missing (required)
    ○ design-decisions — empty (optional)
  Review Phase:
    ○ test-results — empty (required for review)
```

Show gate status summary: can the item advance, and what is missing?

| get_context Field | What to Show |
|---|---|
| `item.role` | Current role label |
| `gateStatus.canAdvance` | ✓ open or ⊘ blocked |
| `gateStatus.missing` | Each missing note key + phase |
| `noteSchema` | All schema notes with `exists` status, grouped by role |
| `guidancePointer` | Guidance text for the first unfilled required note |

If the item has no matching schema (`noteSchema` is empty), show: "No note schema — ad-hoc notes only, no gate enforcement."

---

## Step 3 — Determine Action

Infer intent from `$ARGUMENTS` and conversation context before asking:

| Signal words in request | Action |
|---|---|
| "show", "view", "read", "what are the", "list" | READ (3a) |
| "update", "edit", "change", "modify" | EDIT (3b) |
| "fill", "add", "create", "write" | CREATE (3c) |

If intent cannot be inferred, ask via `AskUserQuestion`:

```
◆ What would you like to do with the notes on "Implement OAuth2 Login"?
  1. Read — view the full content of one or all notes
  2. Edit — update an existing note
  3. Create — add a new note (schema or ad-hoc)
```

**Parallel optimization for READ path:** When intent is clearly READ, call `get_context` (Step 2) and `query_notes(includeBody=true)` (Step 3a) in parallel — they are independent.

---

### 3a — Read

Call:

```
query_notes(operation="list", itemId="<uuid>", includeBody=true)
```

Display each note with its content:

```
◆ requirements (queue)
────────────────────
Implement OAuth2 login via GitHub and Google providers.
Users should be redirected to provider, authenticated,
and returned with a session token.
────────────────────

◆ acceptance-criteria (queue)
────────────────────
- Login button visible on /login page
- GitHub and Google providers both work in staging
- Session persists across page reload
────────────────────
```

If the user asked about a specific note, display only that note's content. If all notes are empty or missing, say so clearly.

---

### 3b — Edit

Show the current content of the target note (use `includeBody=true`). Ask the user what to change. Then upsert with the updated content:

```
manage_notes(operation="upsert", notes=[{
  itemId: "<uuid>",
  key: "<note-key>",
  role: "<note-role>",
  body: "<updated content>"
}])
```

The `(itemId, key)` pair is unique — upserting an existing key updates it in place. No separate create vs. update call is needed.

---

### 3c — Create

**For a missing schema note:** Use `guidancePointer` from `get_context` to prompt the user for content. Then upsert:

```
manage_notes(operation="upsert", notes=[{
  itemId: "<uuid>",
  key: "<schema-key>",
  role: "<schema-role>",
  body: "<content>"
}])
```

**For an ad-hoc note** (key not in the schema): Ask the user for the key name, role, and content. Then upsert:

```
manage_notes(operation="upsert", notes=[{
  itemId: "<uuid>",
  key: "<user-chosen-key>",
  role: "<queue|work|review>",
  body: "<content>"
}])
```

Ad-hoc notes are never required by gate enforcement — they can be added at any time.

---

## Step 4 — Report

**After READ (3a):** No report needed — the note content is the deliverable.

**After EDIT (3b) or CREATE (3c):** Show what changed. If the written note is schema-required, re-check gate status with `get_context(itemId="<uuid>")`:

```
✓ Updated: implementation-notes (work)
  Gate status: ✓ open — all work-phase notes filled
  ↳ Ready to advance: use /status-progression to move to review
```

If the gate is still blocked, list remaining missing notes. If the note was ad-hoc (not schema-required), skip the gate re-check — ad-hoc notes never affect gates.

---

## Note Concepts

| Aspect | Schema Notes | Ad-hoc Notes |
|---|---|---|
| Defined in | `.taskorchestrator/config.yaml` | Not defined — user-chosen key |
| Can be required | Yes — required notes block gate advancement | No — never required |
| Gate enforcement | Yes — enforced by `advance_item` | No — ignored by gate checks |
| Visible in `get_context` | Yes — always listed whether filled or not | Only if they already exist |
| Key naming | Must match schema exactly (case-sensitive) | Any key name |

---

## Note Roles

| Role | When Used | Typical Content |
|---|---|---|
| `queue` | Planning / requirements — filled before work starts | Requirements, acceptance criteria, scope definition |
| `work` | Implementation / design — filled during development | Implementation notes, design decisions, approach rationale |
| `review` | Verification / testing — filled during review phase | Test results, review sign-off, QA notes |

---

## `includeBody` Optimization

| Scenario | Use |
|---|---|
| Reading note content | `includeBody=true` (default) — returns full body text |
| Checking which notes exist without reading content | `includeBody=false` — saves tokens on large notes |
| Checking gate status | Use `get_context` instead — includes schema + gate in one call |

---

## Troubleshooting

**Problem: Wrong note key — upsert creates a new note instead of updating**
Cause: The key must match the schema exactly — it is case-sensitive. A typo (e.g., `Implementation-Notes` vs `implementation-notes`) creates a separate ad-hoc note.
Solution: Check `get_context` for the exact key names listed in `noteSchema`. Copy the key string exactly, including hyphens and lowercase.

---

**Problem: Notes exist but gate still blocks advancement**
Cause: The item has no schema tags, or its tags do not match any key in `.taskorchestrator/config.yaml`. Only schema-matched notes enforce gates.
Solution: Call `get_context(itemId="<uuid>")` and check whether `noteSchema` is empty. If it is, the item has no gate enforcement. Verify the item's tags match a configured schema key.

---

**Problem: Upsert created a duplicate note instead of updating**
Cause: The `(itemId, key)` pair is the unique identifier. If the key differs even slightly from an existing note's key, a new note is created.
Solution: Use `query_notes(operation="list", itemId="<uuid>", includeBody=false)` to see all existing keys. Match the key exactly when calling upsert.

---

**Problem: `includeBody` defaults to true — response is unexpectedly large**
Cause: `query_notes` returns full body text by default. Large notes on an item with many notes can consume significant tokens.
Solution: Pass `includeBody=false` when you only need to check which notes exist, not read their content. Use `get_context` for schema-and-gate checks — it is more efficient than listing all notes.

---

## Examples

### Example 1: View All Notes on an Item

User says: "Show me the notes for the auth task."

1. Search: `query_items(operation="search", query="auth task", limit=5)` — one result returned.
2. Get context: `get_context(itemId="abc-123")` — shows 4 notes, 2 filled, 2 empty.
3. Display overview checklist grouped by phase.
4. User confirms they want to read. Call: `query_notes(operation="list", itemId="abc-123", includeBody=true)`
5. Display each note's content with the separator format.

---

### Example 2: Fill a Missing Required Note to Unblock Advancement

User says: "I need to fill the implementation notes so I can advance this to review."

1. Item UUID provided in `$ARGUMENTS` — use directly.
2. Get context: gate is blocked, `implementation-notes` is missing (required, work phase). `guidancePointer` provides guidance text.
3. Show the guidance text to the user. User provides content.
4. Upsert: `manage_notes(operation="upsert", notes=[{itemId: "...", key: "implementation-notes", role: "work", body: "..."}])`
5. Re-check: `get_context(itemId="...")` — gate is now open.
6. Report: "Gate status: ✓ open — use /status-progression to advance to review."

---

### Example 3: Update an Existing Note with New Information

User says: "Update the requirements note — we dropped the Google provider."

1. Search by title fragment — one result found.
2. Get context: `requirements` note exists (filled). Show current content.
3. User confirms what to change. Edit the body to remove the Google provider reference.
4. Upsert with updated body. The existing note is updated in place.
5. Report: "✓ Updated: requirements (queue). Gate status unchanged — ✓ open."
