---
name: schema-builder
description: Interactively build a YAML note schema for the MCP Task Orchestrator and optionally create a companion lifecycle skill. Defines which notes agents must fill at each workflow phase before advancing. Use when user says "create schema", "define workflow", "set up note requirements", "build config", "configure gates", "add a new work item type", or "set up documentation gates".
argument-hint: "[optional: work item type name, e.g. 'bug-fix', 'research-spike']"
---

# Schema Builder — Current (v3)

Guides the user through designing a note schema for `.taskorchestrator/config.yaml` and optionally creates a companion lifecycle skill. Fully self-contained — no source code access required.

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

## YAML Schema Format

```yaml
note_schemas:

  your-schema-tag:           # Matches items whose tags include "your-schema-tag"
    - key: note-key          # Stable identifier; kebab-case. Used in manage_notes(key="note-key").
      role: queue            # Phase: "queue", "work", or "review"
      required: true         # true = blocks advance_item gate | false = shown but not enforced
      description: "..."     # Short label — shown in manage_items(create) expectedNotes response
      guidance: "..."        # Optional longer text — shown in get_context() as guidancePointer
```

| Field | Required | Notes |
|-------|----------|-------|
| `key` | yes | kebab-case, unique within schema, stable after creation |
| `role` | yes | `queue`, `work`, or `review` only |
| `required` | yes | `true` or `false` |
| `description` | yes | Keep under 80 chars — this is the quick label agents scan |
| `guidance` | no | Free text; shown as `guidancePointer` in `get_context` when this note is the first unfilled required note |

**WARNING:** Changing a `key` after notes have been written orphans existing notes under the old key.

**Multiple schemas in one file:**
```yaml
note_schemas:

  schema-one:
    - key: ...

  schema-two:
    - key: ...
```

For complete example schemas, see `references/example-schemas.md` in this skill folder.

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
- If `$ARGUMENTS` is provided, use it as the schema tag name
- Otherwise, prompt with examples: `feature-implementation`, `bug-fix`, `research-spike`, `infrastructure-change`, `plugin-update`
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

**Question 6:** "Should we create a companion lifecycle skill at `.claude/skills/<schema-name>/SKILL.md`?"
- This gives agents a `/<schema-name>` slash command with full phase-by-phase lifecycle guidance
- Recommended when the workflow has 3+ notes or involves non-obvious sequencing

**Expected outcome:** You have a schema tag name, a list of notes per phase (key, role, required, description, guidance), and a yes/no on companion skill.

---

## Step 2 — Generate the Schema

Using answers from Step 1, produce the YAML block. Apply these defaults:
- First queue note's `guidance` should open with: `"Run /<schema-name> for the full lifecycle guide. For this note: <specific guidance>."` if a companion skill will be created
- Use kebab-case for all keys
- Keep `description` values under 80 chars

Show the generated YAML to the user and ask for confirmation before writing.

**Expected outcome:** User-approved YAML block ready to write.

---

## Step 3 — Write the Config File

Check if `.taskorchestrator/config.yaml` exists:
- **Exists:** Read it, merge the new schema under `note_schemas:`, write back
- **Doesn't exist:** Create `.taskorchestrator/` directory and write the file with the new schema

After writing, remind the user: **MCP reconnect required** (`/mcp`) for the schema to take effect. The server caches schemas on first access — changes are not hot-reloaded.

**Expected outcome:** `.taskorchestrator/config.yaml` written with the new schema merged in.

---

## Step 4 — Generate Companion Skill (if requested)

Write to `.claude/skills/<schema-name>/SKILL.md`. Create the directory if it doesn't exist. This is a project-local skill available immediately as `/<schema-name>` — no plugin version bump required.

Use the template in `references/companion-template.md` within this skill folder. Replace all placeholders with values from the schema built in Steps 1-2.

**Expected outcome:** `.claude/skills/<schema-name>/SKILL.md` created with phase-by-phase lifecycle instructions matching the schema.

---

## Step 5 — Verify

After writing all files, create a smoke-test item to confirm the schema loads:

```
manage_items(
  operation="create",
  items=[{ title: "Schema smoke test", tags: "<schema-name>", priority: "low" }]
)
```

Check that `expectedNotes` appears in the response with the correct keys and roles. If `expectedNotes` is empty or missing, the server needs `/mcp` reconnect first — remind the user and retry.

Delete the smoke-test item after verification:
```
manage_items(operation="delete", ids=["<smoke-test-uuid>"])
```

**Expected outcome:** Smoke test item returns `expectedNotes` matching the schema. Item deleted after verification.

---

## Examples

**Example 1: Simple bug-fix schema**

User says: "Create a schema for bug fixes"

Actions:
1. Schema tag: `bug-fix`
2. No review phase
3. Queue notes: `reproduction-steps` (required), `root-cause` (required)
4. Work notes: `fix-summary` (required), `test-verification` (required)
5. Write to `.taskorchestrator/config.yaml`
6. Smoke test confirms `expectedNotes` returns 4 notes

Result: Items tagged `bug-fix` now require root cause documentation before work starts and test verification before closing.

**Example 2: Lightweight observation tracker**

User says: "I want a minimal schema for tracking observations"

Actions:
1. Schema tag: `agent-observation`
2. No review phase
3. Queue notes: `observation-detail` (required)
4. No work notes
5. Write to config

Result: Single-gate schema — items need one note filled before advancing, then go straight to terminal.

---

## Troubleshooting

**`expectedNotes` is empty after creating an item with the schema tag**
- Cause: MCP server hasn't loaded the updated config file
- Solution: Run `/mcp` in Claude Code to reconnect the server, then retry the create

**`advance_item` fails with "required notes not filled for queue phase: [keys]"**
- Cause: Gate enforcement is working correctly — required notes haven't been filled yet
- Solution: Use `get_context(itemId=...)` to see which notes are missing, fill them with `manage_notes(upsert)`, then retry

**Schema key doesn't match — item has no schema applied**
- Cause: The item's `tags` field doesn't contain a string that matches any `note_schemas` key
- Solution: Verify the item's tags with `query_items(operation="get", id="<uuid>")`. The first tag matching a schema key wins. Tags are comma-separated strings, not arrays.

**Duplicate schema key in config file**
- Cause: YAML allows duplicate keys but only the last one is used
- Solution: Check `.taskorchestrator/config.yaml` for duplicate entries under `note_schemas:`. Merge them into a single key.

**Companion skill not appearing as a slash command**
- Cause: File not in the correct location or wrong filename
- Solution: Verify the file is at `.claude/skills/<schema-name>/SKILL.md` (case-sensitive on Linux). The skill should appear in `/skills` list without restart.
