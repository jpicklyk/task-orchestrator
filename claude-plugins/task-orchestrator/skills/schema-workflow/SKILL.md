---
name: schema-workflow
description: "Internal, hook-triggered: drives a schema-typed MCP item through its gate-enforced phases, filling required notes."
user-invocable: false
---

# Schema Workflow

Drive any schema-tagged MCP work item through its gate-enforced lifecycle. This skill is
schema-driven — it reads note requirements and authoring guidance from the item's tag schema
at runtime, never hardcoding what notes should contain.

**When this skill applies:** Any item whose `type` field matches a schema defined in
`work_item_schemas:` in `.taskorchestrator/config.yaml`, or whose tags match a schema in
`note_schemas:` (legacy). Items without a matching type or tags advance freely (no gates).

---

## Entry Point

Start by loading the item's context:

```
get_context(itemId="<uuid>")
```

The response tells you everything needed to proceed:

| Field | What it means |
|-------|--------------|
| `currentRole` | Which phase the item is in (queue, work, review, terminal) |
| `canAdvance` | Whether the gate is satisfied for the next `start` trigger |
| `missing` | Required notes not yet filled for the current phase |
| `expectedNotes` | All notes defined by the schema, with `exists` and `filled` status (keys-only — no description/guidance/skill) |
| `guidanceKey` | Key of the first unfilled required note with guidance; resolve its text via `query_items(operation="schema", itemId=...)` |
| `noteSchema` | The full schema definition matching the item's tags |

If `currentRole` is `terminal`, the item is already complete — nothing to do.

If `noteSchema` is null or empty, no schema matches the item. This means either:
- `.taskorchestrator/config.yaml` doesn't exist or has no `work_item_schemas` or `note_schemas` section
- The item's `type` field doesn't match any configured schema key in `work_item_schemas`
- The item's tags don't match any configured schema key in `note_schemas` (legacy fallback)
- No `default` schema exists as a fallback

Inform the user: "No schema found for this item's type/tags. Use `/manage-schemas` to configure gate workflows." The item can still advance freely — this is non-blocking, but gate enforcement won't apply.

---

## Phase Progression Loop

Each phase follows the same pattern: **fill required notes, then advance.**

### Step 1 — Identify missing notes

From `get_context`, check the `missing` array. These are the required notes that must be
filled before the gate allows advancement.

If `missing` is empty and `canAdvance` is true, skip to Step 3.

### Step 2 — Fill notes using guidanceKey

For each missing note, `guidanceKey` names the note with authoring guidance; resolve its text via
`query_items(operation="schema", itemId=...)` and follow it.

```
manage_notes(
  operation="upsert",
  notes=[{
    itemId: "<uuid>",
    key: "<note-key>",
    role: "<note-role>",
    body: "<content following the resolved guidance>"
  }]
)
```
Keep the body distilled prose; route verbatim artifacts (test output, diffs, logs) through `bodyFromFile` instead of pasting them inline.

**How guidanceKey works:**
- `get_context` returns `guidanceKey` (a note key) for the first unfilled required note
- After filling that note, call `get_context` again to get the key for the next one
- Resolve the key's `guidance` text via `query_items(operation="schema", itemId=...)`
- If `guidanceKey` is null, no unfilled required note has guidance — use the note's `description` (also from the schema op) as a general guide

**Skill-assisted note filling:**
- If the `get_context` response includes `skillPointer` (a non-null string), invoke that skill via the Skill tool before filling the note
- The skill provides a structured evaluation workflow — follow its steps, then use the output to fill the note
- `skillPointer` is derived from the first unfilled required note's `skill` field in the schema
- If `skillPointer` is null, use the resolved `guidanceKey` text as the authoring guide
- `description`/`guidance`/`skill` are not in `expectedNotes` (keys-only) — fetch them via `query_items(operation="schema", itemId=...)`

**Batch filling:** If you already know the content for multiple notes (e.g., from a completed
plan or implementation), fill them all in one `manage_notes` call. You only need to re-check
`get_context` between notes when you need the next `guidanceKey` for authoring direction.

### Step 3 — Advance to the next phase

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
```

The response confirms the transition:

| Field | Check |
|-------|-------|
| `applied` | Must be `true` — if `false`, the gate rejected (notes still missing) |
| `newRole` | Phase you moved to (`previousRole` is omitted from success results) |
| `expectedNotes` | Notes required for the new phase (fill these next) |
| `unblockedItems` | Other items that were waiting on this one |

**If the gate rejects:** The response lists which notes are missing. Fill them (Step 2),
then retry. Do not call `get_context` first — `advance_item` already told you what's needed.

### Step 4 — Repeat or finish

After advancing, check whether the new phase has its own required notes:
- If `expectedNotes` in the advance response shows unfilled required notes → loop back to Step 2
- If `newRole` is `terminal` → the item is complete
- Otherwise, continue work in the new phase and fill notes as progress is made

---

## Phase-Specific Guidance

The schema defines which notes belong to which phase. Common patterns:

| Phase | Typical purpose | When notes get filled |
|-------|----------------|----------------------|
| queue | Requirements, design, reproduction steps | During planning, before implementation starts |
| work | Implementation notes, test results, fix summaries | During or after implementation |
| review | Deploy notes, verification results | After implementation, during validation |

The actual note keys and content requirements vary per schema — always check `expectedNotes`
rather than assuming specific keys exist.

---

## Orchestrator vs Subagent Responsibility

**Orchestrator** (this skill's primary user):
- Fills queue-phase notes (requirements, design) during planning
- Dispatches implementation agents with the item UUID
- After implementation agents return, advances the item via `advance_item(start)` and inspects `newRole`:
  - If `review`: dispatches review agents or performs inline review
  - If `terminal`: item completed through a lightweight lifecycle (no review-phase notes in schema)
- Performs the final terminal transition (review→terminal) after the review verdict
- Uses this skill for queue-phase note filling and terminal advancement

**Implementation agents** (agent-owned-phase model):
- Receive the full phase-aware protocol automatically via the `subagent-start` hook
- Call `advance_item(start)` once to enter work phase (queue→work)
- Fill work-phase notes using the JIT progression loop (guidanceKey + skillPointer)
- Return to the orchestrator — do NOT call `advance_item` again
- The orchestrator advances the item to the next phase and handles all further routing

**Review agents** (dispatched into an item already in review):
- Receive the `subagent-start` hook, which tells them to call `advance_item(start)`
- Since the item is already in review, `advance_item` returns `applied: false` — this is expected
- The hook's fallback applies: call `get_context(itemId=...)` to get guidance instead
- Fill review-phase notes (e.g., review-checklist), report verdict, return
- Do NOT call `advance_item` again — the orchestrator handles the terminal transition

**Key invariant:** Agents own phase entry (one `advance_item(start)` call to enter their assigned phase). The orchestrator owns all phase-to-phase transitions — advancing the item, inspecting the schema to determine the next phase (review or terminal), and dispatching phase-appropriate agents. Review agents fill review-phase notes and return — they do not advance items.

---

## Creating a New Schema Item

When creating a new item with a schema, set the `type` field to the schema key:

```
manage_items(
  operation="create",
  items=[{ title: "...", type: "<schema-key>", priority: "medium" }]
)
```

The `type` field is the primary schema selector — it maps directly to a key in `work_item_schemas:`.
Tags can still be used for additional categorization and as a legacy schema fallback, but `type`
takes precedence.

Check `expectedNotes` in the response — it lists all notes the schema requires across all
phases. Begin filling queue-phase notes immediately, then follow the progression loop above.

---

## Error Recovery

**Gate rejection:** `advance_item` returns `applied: false` with the missing note keys.
Fill them and retry — no need for a separate `get_context` call.

**Wrong phase notes:** If you try to upsert a note with a `role` that doesn't match the
item's current role, the note is still created (notes are not phase-locked), but it won't
satisfy a gate for a different phase. Always match the note's `role` to the schema definition.

**Blocked items:** If `advance_item` fails because the item is blocked by a dependency,
resolve the blocking item first. Use `get_blocked_items` or `query_dependencies` to diagnose.

**No schema match:** Items whose `type` doesn't match any schema in `work_item_schemas` and
whose tags don't match any schema in `note_schemas` have no gate enforcement. `advance_item`
will succeed without notes. This is by design — only typed or tagged items require structured
note workflows.
