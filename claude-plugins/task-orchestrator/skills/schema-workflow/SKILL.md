---
name: schema-workflow
description: >
  Guide an MCP work item through its schema-defined lifecycle — filling required notes using
  guidancePointer and advancing through gate-enforced phases. Internal skill triggered by hooks
  and output styles during orchestration workflows. Use when an item has schema tags and needs
  to progress through queue, work, review, or terminal phases with note gates.
user-invocable: false
---

# Schema Workflow

Drive any schema-tagged MCP work item through its gate-enforced lifecycle. This skill is
schema-driven — it reads note requirements and authoring guidance from the item's tag schema
at runtime, never hardcoding what notes should contain.

**When this skill applies:** Any item whose `tags` match a schema defined in
`.taskorchestrator/config.yaml`. Items without matching schemas advance freely (no gates).

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
| `expectedNotes` | All notes defined by the schema, with `exists` and `filled` status |
| `guidancePointer` | Authoring instructions for the first unfilled required note (from schema `guidance` field) |
| `noteSchema` | The full schema definition matching the item's tags |

If `currentRole` is `terminal`, the item is already complete — nothing to do.

If `noteSchema` is null or empty, no schema matches the item's tags. This means either:
- `.taskorchestrator/config.yaml` doesn't exist or has no `note_schemas` section
- The item's tags don't match any configured schema key

Inform the user: "No note schema found for tag `<tag>`. Use `/manage-schemas` to configure gate workflows." The item can still advance freely — this is non-blocking, but gate enforcement won't apply.

---

## Phase Progression Loop

Each phase follows the same pattern: **fill required notes, then advance.**

### Step 1 — Identify missing notes

From `get_context`, check the `missing` array. These are the required notes that must be
filled before the gate allows advancement.

If `missing` is empty and `canAdvance` is true, skip to Step 3.

### Step 2 — Fill notes using guidancePointer

For each missing note, the schema provides authoring guidance via `guidancePointer`. This
is the schema author's instruction for what the note should contain — follow it.

```
manage_notes(
  operation="upsert",
  notes=[{
    itemId: "<uuid>",
    key: "<note-key>",
    role: "<note-role>",
    body: "<content following guidancePointer instructions>"
  }]
)
```

**How guidancePointer works:**
- `get_context` returns `guidancePointer` for the first unfilled required note
- After filling that note, call `get_context` again to get the pointer for the next one
- The pointer comes from the `guidance` field in `.taskorchestrator/config.yaml`
- If `guidancePointer` is null, the note has no specific authoring instructions — use the
  note's `description` field as a general guide

**Batch filling:** If you already know the content for multiple notes (e.g., from a completed
plan or implementation), fill them all in one `manage_notes` call. You only need to re-check
`get_context` between notes when you need the next `guidancePointer` for authoring direction.

### Step 3 — Advance to the next phase

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
```

The response confirms the transition:

| Field | Check |
|-------|-------|
| `applied` | Must be `true` — if `false`, the gate rejected (notes still missing) |
| `previousRole` → `newRole` | Confirms which phase you moved from/to |
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
- Dispatches review agents after the item reaches review phase (implementation agent advances work→review before returning)
- Performs the final terminal transition (review→terminal) after the review verdict
- Uses this skill for queue-phase note filling and terminal advancement

**Implementation agents** (agent-owned-phase model):
- Receive the full phase-aware protocol automatically via the `subagent-start` hook
- Call `advance_item(start)` to enter work phase (queue→work)
- Fill work-phase notes using the JIT progression loop described in the hook protocol
- Call `advance_item(start)` again to advance to review (work→review) before returning
- Do NOT call `advance_item(trigger="complete")` — the orchestrator handles terminal transitions

**Review agents** (dispatched into an item already in review):
- Receive the `subagent-start` hook, which tells them to call `advance_item(start)`
- Since the item is already in review, `advance_item` returns `applied: false` — this is expected
- The hook's fallback applies: call `get_context(itemId=...)` to get guidance instead
- Fill review-phase notes (e.g., review-checklist), report verdict, return
- Do NOT call `advance_item` again — the orchestrator handles the terminal transition

**Key invariant:** Implementation agents own queue→work and work→review transitions. The orchestrator owns review→terminal. Review agents do not advance items — they evaluate and report.

---

## Creating a New Schema-Tagged Item

When creating a new item with a schema tag:

```
manage_items(
  operation="create",
  items=[{ title: "...", tags: "<schema-tag>", priority: "medium" }]
)
```

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

**No schema match:** Items whose tags don't match any schema in config.yaml have no gate
enforcement. `advance_item` will succeed without notes. This is by design — only schema-tagged
items require structured note workflows.
