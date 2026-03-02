# Config Format Reference

YAML format and field rules for `.taskorchestrator/config.yaml`.

---

## File Location

- Path: `.taskorchestrator/config.yaml` in the project root (alongside `.claude/`, `.git/`, etc.)
- Create the directory if it doesn't exist: `.taskorchestrator/`
- This file is typically gitignored (runtime/project config, not source code)
- The server reads and caches this file on first schema access — changes require MCP reconnect (`/mcp`)

---

## YAML Structure

```yaml
note_schemas:

  your-schema-tag:           # Matches items whose tags include "your-schema-tag"
    - key: note-key          # Stable identifier; kebab-case
      role: queue            # Phase: "queue", "work", or "review"
      required: true         # true = blocks advance_item gate | false = shown but not enforced
      description: "..."     # Short label — shown in expectedNotes response
      guidance: "..."        # Optional — shown as guidancePointer in get_context()
```

---

## Field Reference

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `key` | yes | string | kebab-case, unique within schema, stable after creation |
| `role` | yes | string | `queue`, `work`, or `review` only |
| `required` | yes | boolean | `true` = blocks gate, `false` = shown but not enforced |
| `description` | yes | string | Keep under 80 chars — quick label agents scan |
| `guidance` | no | string | Free text shown as `guidancePointer` in `get_context` |

---

## Matching Rules

- Items have a `tags` field (comma-separated string)
- The **first** tag in an item's tag list that matches a schema key wins
- Only one schema applies per item
- Matching is exact and case-sensitive

---

## Phase Flow

```
queue ──(start)──► work ──(start)──► review ──(start)──► terminal
```

- If the schema has NO `role: review` notes, `start` from work goes directly to terminal (review is skipped)
- Required notes for the *current* phase gate the `start` trigger
- Optional notes (`required: false`) are shown but do not block advancement

---

## Design Principles

- **Keep it lean.** 2-3 required notes per phase is the practical maximum before agents find workarounds
- **Queue gates = pre-work contract.** Requirements, design, root cause — filled before agents touch code
- **Work gates = evidence of completion.** Implementation notes, test results — filled after implementation
- **Review phase is optional.** Only add `role: review` notes for genuine deploy/verify/sign-off steps
- **Optional notes are reminders.** `required: false` notes appear in `expectedNotes` without blocking

---

## Multiple Schemas

```yaml
note_schemas:

  schema-one:
    - key: ...

  schema-two:
    - key: ...
```

Each schema is independent. An item matches at most one schema (first tag match wins).

---

## Key Stability Warning

Changing a `key` after notes have been written orphans existing notes under the old key. There is no automatic migration — orphaned notes become ad-hoc notes on their items.
