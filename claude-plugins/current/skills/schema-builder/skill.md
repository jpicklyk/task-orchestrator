---
name: schema-builder
description: Interactively build a YAML note schema for a new work item type and optionally create a companion guidance skill
---

# Schema Builder — Current (v3)

Guides the user through designing a note schema for `.taskorchestrator/config.yaml` and optionally creates a companion command skill. Fully self-contained — no source code access required.

---

## How Note Schemas Work

Before building, understand what you are creating:

**What a schema does:**
- Items in MCP Task Orchestrator have a `tags` field (comma-separated string)
- When `.taskorchestrator/config.yaml` defines a `note_schemas:` entry whose key matches one of an item's tags, that item gets a *schema*
- The schema defines which notes agents must fill at each workflow phase before they can advance

**Matching rule:** The **first** tag in an item's tag list that matches a schema key wins. Only one schema applies per item.

**What agents see:**
- `manage_items(create)` → returns `expectedNotes` array listing all schema notes with `exists: false`
- `advance_item(trigger="start")` → **blocked** if any required notes for the current phase are unfilled; error message lists the missing keys
- `get_context(itemId=...)` → returns full schema with `gateStatus.canAdvance`, `gateStatus.missing`, and `guidancePointer` (the `guidance` text of the first unfilled required note in the current phase)
- `advance_item` success response → returns `expectedNotes` for the *next* phase, prompting agents to fill ahead

**Phase flow:**
```
queue ──(start)──► work ──(start)──► review ──(start)──► terminal
```
- If the schema has NO `role: review` notes, `start` from work goes directly to terminal (review is skipped)
- Required notes for the *current* phase gate the `start` trigger
- Optional notes (`required: false`) are shown but do not block advancement

**Config file:**
- Location: `.taskorchestrator/config.yaml` in the project root (alongside `.claude/`, `.git/`, etc.)
- Create the directory if it doesn't exist: `.taskorchestrator/`
- This file is typically gitignored (it's runtime/project config, not source code)
- The server reads and caches this file on first schema access — **changes require MCP reconnect** to take effect (in Claude Code: `/mcp` to reconnect the server subprocess)

---

## YAML Schema Format — Complete Reference

```yaml
note_schemas:

  your-schema-tag:           # Matches items whose tags include "your-schema-tag"
    - key: note-key          # Stable identifier; kebab-case. Used in manage_notes(key="note-key").
                             # WARNING: changing this key orphans any existing notes with the old key.
      role: queue            # Phase: "queue", "work", or "review"
      required: true         # true = blocks advance_item gate | false = shown but not enforced
      description: "..."     # Short label — shown in manage_items(create) expectedNotes response
      guidance: "..."        # Optional longer text — shown in get_context() as guidancePointer
                             # Best practice: reference the companion skill in the first queue note:
                             # "Run /your-schema-tag for full lifecycle guide. For this note: ..."
```

**Field rules:**
| Field | Required | Notes |
|-------|----------|-------|
| `key` | yes | kebab-case, unique within schema, stable after creation |
| `role` | yes | `queue`, `work`, or `review` only |
| `required` | yes | `true` or `false` |
| `description` | yes | Keep under 80 chars — this is the quick label agents scan |
| `guidance` | no | Free text; shown as `guidancePointer` in `get_context` when this note is the first unfilled required note |

**Multiple schemas in one file:**
```yaml
note_schemas:

  schema-one:
    - key: ...

  schema-two:
    - key: ...
```

---

## Design Principles

**Keep it lean.** Every required note is friction. 2–3 required notes per phase is the practical maximum before agents find workarounds.

**Queue gates = pre-work contract.** Queue notes answer "should we do this and how?" — requirements, design, root cause. They run *before* agents touch code.

**Work gates = evidence of completion.** Work notes answer "what happened and what's the proof?" — implementation notes, test results. They run *after* implementation.

**Review phase = optional verification step.** Only add `role: review` notes if the work genuinely needs a deploy/smoke-test/sign-off step before closing. Most schemas skip review.

**Optional notes for reminders.** `required: false` notes still appear in `expectedNotes` and `get_context` — useful for prompting agents without blocking them.

**`guidance` is free text, not a skill reference.** Write guidance as a direct instruction to the agent: "Describe X by listing Y." If you create a companion skill, reference it explicitly in the first queue note's `guidance` field.

---

## Step 1 — Gather Requirements

Ask the user the following questions (use `AskUserQuestion` for structured input):

**Question 1:** "What type of work item will use this schema?"
- Examples: `feature-implementation`, `bug-fix`, `research-spike`, `infrastructure-change`, `plugin-update`
- This becomes the schema key and the tag agents apply to items

**Question 2:** "Does this work type need a review/deploy phase after implementation, or does it go straight to done?"
- Yes → include `role: review` notes
- No → schema ends at work phase (terminal reached after work notes filled)

**Question 3:** "What must be documented *before* work starts (queue phase)?"
- Prompt with examples: requirements/acceptance criteria, root cause, research question, change scope
- Aim for 1–3 notes; ask for each: key name, whether required, what agents should capture

**Question 4:** "What must be documented *after* implementation (work phase)?"
- Prompt with examples: implementation summary, test results, files changed, fix verification
- Aim for 1–3 notes

**Question 5 (if review phase):** "What must be documented/verified before closing (review phase)?"
- Prompt with examples: deploy confirmation, smoke test results, sign-off

**Question 6:** "Should we create a companion command skill at `.claude/commands/<schema-name>.md`?"
- This gives agents a `/schema-name` slash command with full phase-by-phase lifecycle guidance
- Recommended when the workflow has 3+ notes or involves non-obvious sequencing

---

## Step 2 — Generate the Schema

Using answers from Step 1, produce the YAML block. Apply these defaults:
- First queue note's `guidance` should open with: `"Run /<schema-name> for the full lifecycle guide. For this note: <specific guidance>."`  if a companion skill will be created
- Use kebab-case for all keys
- Keep `description` values under 80 chars

Show the generated YAML to the user and ask for confirmation before writing.

---

## Step 3 — Write the Config File

Check if `.taskorchestrator/config.yaml` exists:
- **Exists:** Read it, merge the new schema under `note_schemas:`, write back
- **Doesn't exist:** Create `.taskorchestrator/` directory and write the file with the new schema

After writing, remind the user: **MCP reconnect required** (`/mcp`) for the schema to take effect. The server caches schemas on first access — changes are not hot-reloaded.

---

## Step 4 — Generate Companion Skill (if requested)

Write to `.claude/skills/<schema-name>.md`. This is a project-local skill available immediately as `/<schema-name>` — no plugin version bump required.

**Companion skill template:**

```markdown
---
description: Guide the full lifecycle of a <schema-name> tagged MCP item
---

# <Schema Name> Workflow

End-to-end workflow for a `<schema-name>` tagged item.

**Usage:** `/<schema-name> [item-uuid]`

- If `item-uuid` provided: load existing item and resume from its current phase
- If omitted: create a new item and start from the beginning

---

## Phase 0 — Setup

If UUID provided, call `get_context(itemId="<uuid>")` to check current role and gate status, then jump to the appropriate phase.

If no UUID, create the item:
```
manage_items(
  operation="create",
  items=[{ title: "<title>", tags: "<schema-name>", priority: "medium" }]
)
```
Note the UUID and `expectedNotes` list. Confirm `role: queue`, then continue to Phase 1.

---

## Phase 1 — Queue: <Queue Note Names>

**Goal:** Fill required queue-phase notes before advancing to work.

<For each queue note:>
### Fill `<key>`

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

---

## Step 5 — Verify

After writing all files, create a smoke-test item to confirm the schema loads:

```
manage_items(
  operation="create",
  items=[{ title: "Schema smoke test", tags: "<schema-name>", priority: "low" }]
)
```

Check that `expectedNotes` appears in the response with the correct keys. If not, the server needs `/mcp` reconnect first.

Delete the smoke-test item after verification:
```
manage_items(operation="delete", ids=["<smoke-test-uuid>"])
```

---

## Example Schemas

These are real schemas from the MCP Task Orchestrator project itself — useful as reference:

### `feature-implementation` (queue + work + review)
```yaml
feature-implementation:
  - key: requirements
    role: queue
    required: true
    description: "Problem statement and acceptance criteria."
    guidance: "Run /feature-implementation for the full lifecycle guide. For this note: describe what problem this solves. List 2-5 acceptance criteria."
  - key: design
    role: queue
    required: true
    description: "Chosen approach, alternatives considered, key risks."
    guidance: "Explain the implementation approach. Note alternatives ruled out. Call out risks (schema migrations, tight coupling, ORM quirks)."
  - key: implementation-notes
    role: work
    required: true
    description: "Key decisions made during implementation, deviations from design."
    guidance: "Document surprises, wrong-turns, or deviations from the planned approach. Include API/class names that differed from expectations."
  - key: test-results
    role: work
    required: true
    description: "Test pass/fail count and any new tests added."
    guidance: "Run tests and report total count and failures. List any new test classes added."
  - key: deploy-notes
    role: review
    required: false
    description: "Deploy needed? Version bump? Reconnect required?"
    guidance: "Note whether a rebuild/deploy was done, what version was bumped to, and whether reconnect was required."
```

### `bug-fix` (queue + work, no review)
```yaml
bug-fix:
  - key: reproduction-steps
    role: queue
    required: true
    description: "Step-by-step reproduction with expected vs actual result."
    guidance: "Include the exact tool call that triggers the bug. State expected output and actual output."
  - key: root-cause
    role: queue
    required: true
    description: "Why it happens — file, line, and condition."
    guidance: "Identify the specific file and function. Explain the condition that triggers it."
  - key: fix-summary
    role: work
    required: true
    description: "What was changed and which files were modified."
    guidance: "List each file changed and summarize the change."
  - key: test-verification
    role: work
    required: true
    description: "How the fix was verified and test results after fix."
    guidance: "Run tests and report results. Note if a new test was added to cover the fix."
```

### `agent-observation` (queue only, minimal)
```yaml
agent-observation:
  - key: observation-detail
    role: queue
    required: true
    description: "Expected vs actual behavior and suggested improvement."
    guidance: "Describe what was observed. State expected behavior. Suggest a concrete improvement."
```
