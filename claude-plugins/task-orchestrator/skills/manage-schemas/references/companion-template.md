# Companion Skill Template

Use this template when the user requests a companion skill in Step 4. Write to `.claude/skills/<schema-name>/SKILL.md`. Create the directory if it doesn't exist. This is a project-local skill available immediately as `/<schema-name>` — no plugin version bump required.

Replace all `<placeholders>` with actual values from the schema built in Steps 1-2. Guidance text from the schema may contain markdown (bold headers, bullet points). Render it verbatim in the `**What to write:**` line — do not reformat.

Every tool call in the template is a `to-call` block (`{tool, select|op, args}`) in the shapes of [call-shapes.md](../../_generated/call-shapes.md); keep them as blocks in the generated skill so the plugin's doc-shape check can validate them. The outer fence below is four backticks so the inner three-backtick blocks survive.

---

````markdown
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

If UUID provided, read the item's gate state to check its current role, gate status, and `skillPointer`. Jump to the appropriate phase.

```to-call
{"tool": "items_read", "select": "context", "args": {"itemId": "<uuid>"}}
```

If no UUID, create the item:

```to-call
{"tool": "items_write", "op": "create", "args": {"title": "<title>", "type": "<schema-name>", "priority": "medium"}}
```

Note the UUID, then read the schema decoration for it (a create does not return `expectedNotes`):

```to-call
{"tool": "items_read", "select": "schema", "args": {"itemId": "<uuid>"}}
```

Confirm with `items_read` `context` that `item.role` is `queue` (the schema view has no role), then continue to Phase 1.

---

## Phase 1 — Queue: <Queue Note Names>

**Goal:** Fill required queue-phase notes before advancing to work.

<For each queue note:>
### Fill `<key>`

If `skillPointer` points to a skill for this note, invoke that skill first via the Skill tool, then use its output to compose the note body.

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<uuid>", "key": "<key>", "role": "queue", "body": "<content>"}}
```
**What to write:** <guidance text from schema>

<End for each>

### Advance to work

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<uuid>", "trigger": "start"}}
```
Gate check: all required queue notes must be filled. Confirm `newRole: "work"`.

---

## Phase 2 — Work: <Work Note Names>

**Goal:** Implement, then fill work-phase notes before advancing.

<For each work note:>
### Fill `<key>`

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<uuid>", "key": "<key>", "role": "work", "body": "<content>"}}
```
**What to write:** <guidance text from schema>

<End for each>

### Advance to review (or terminal)

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<uuid>", "trigger": "start"}}
```
Gate check: all required work notes must be filled. Confirm `newRole: "review"` (or `"terminal"` if no review phase).

---

<If review phase:>
## Phase 3 — Review: Deploy and Verify

**Goal:** Verify in the running system, then close.

<For each review note:>
### Fill `<key>` (<required or optional>)

If `skillPointer` points to a skill for this note, invoke that skill first.

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<uuid>", "key": "<key>", "role": "review", "body": "<content>"}}
```
**What to write:** <guidance text from schema>

<End for each>

### Close the item

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<uuid>", "trigger": "start", "summary": "<one-line summary>"}}
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

**Gate error pattern:** a refused `advance` answers call-level `partial_failure`; `data.results[0].error.code` `gate_blocked` lists the required notes still missing for the phase in `detail.missing[]`
→ Fill the listed notes, then retry the `advance` transition.
````
