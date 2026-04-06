# Companion Skill Template

Use this template when the user requests a companion skill in Step 4. Write to `.claude/skills/<schema-name>/SKILL.md`. Create the directory if it doesn't exist. This is a project-local skill available immediately as `/<schema-name>` — no plugin version bump required.

Replace all `<placeholders>` with actual values from the schema built in Steps 1-2. Guidance text from the schema may contain markdown (bold headers, bullet points). Render it verbatim in the `**What to write:**` line — do not reformat.

---

```markdown
---
name: <schema-name>
description: Guide the full lifecycle of a <schema-name> typed MCP item — from queue through work to terminal. Use when working on a <schema-name> item, advancing through gates, or filling required notes for <schema-name>.
---

# <Schema Display Name> Workflow

End-to-end workflow for a `<schema-name>` typed item.

**Usage:** `/<schema-name> [item-uuid]`

- If `item-uuid` provided: load existing item and resume from its current phase
- If omitted: create a new item and start from the beginning

---

## Phase 0 — Setup

If UUID provided, call `get_context(itemId="<uuid>")` to check current role, gate status, and `skillPointer`. Jump to the appropriate phase.

If no UUID, create the item:
```
manage_items(
  operation="create",
  items=[{ title: "<title>", type: "<schema-name>", priority: "medium" }]
)
```
Note the UUID and `expectedNotes` list. Confirm `role: queue`, then continue to Phase 1.

---

## Phase 1 — Queue: <Queue Note Names>

**Goal:** Fill required queue-phase notes before advancing to work.

<For each queue note:>
### Fill `<key>`

If `skillPointer` points to a skill for this note, invoke that skill first via the Skill tool, then use its output to compose the note body.

```
manage_notes(
  operation="upsert",
  notes=[{ itemId: "<uuid>", key: "<key>", role: "queue", body: "<content>" }]
)
```
**What to write:** <guidance text from schema>

<End for each>

### Advance to work

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
```
Gate check: all required queue notes must be filled. Confirm `newRole: "work"`.

---

## Phase 2 — Work: <Work Note Names>

**Goal:** Implement, then fill work-phase notes before advancing.

<For each work note:>
### Fill `<key>`

```
manage_notes(
  operation="upsert",
  notes=[{ itemId: "<uuid>", key: "<key>", role: "work", body: "<content>" }]
)
```
**What to write:** <guidance text from schema>

<End for each>

### Advance to review (or terminal)

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
```
Gate check: all required work notes must be filled. Confirm `newRole: "review"` (or `"terminal"` if no review phase).

---

<If review phase:>
## Phase 3 — Review: Deploy and Verify

**Goal:** Verify in the running system, then close.

<For each review note:>
### Fill `<key>` (<required or optional>)

If `skillPointer` points to a skill for this note, invoke that skill first.

```
manage_notes(
  operation="upsert",
  notes=[{ itemId: "<uuid>", key: "<key>", role: "review", body: "<content>" }]
)
```
**What to write:** <guidance text from schema>

<End for each>

### Close the item

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start", summary: "<one-line summary>" }])
```
Confirm `newRole: "terminal"`.

<End if review phase>

---

## Quick Reference

| Phase | Required notes | Advance trigger |
|-------|---------------|-----------------|
| queue | <list required queue keys> | `start` → work |
| work | <list required work keys> | `start` → review/terminal |
<if review:>
| review | <list required review keys or "(none required)"> | `start` → terminal |

**Gate error pattern:** `"required notes not filled for <phase> phase: <keys>"`
→ Fill the listed notes, then retry `advance_item`.
```
