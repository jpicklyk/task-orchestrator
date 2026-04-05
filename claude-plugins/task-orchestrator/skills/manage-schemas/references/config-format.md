# Config Format Reference

YAML format and field rules for `.taskorchestrator/config.yaml`.

---

## File Location

- Path: `.taskorchestrator/config.yaml` in the project root (alongside `.claude/`, `.git/`, etc.)
- Create the directory if it doesn't exist: `.taskorchestrator/`
- This file is typically gitignored (runtime/project config, not source code)
- The server reads and caches this file on first schema access — changes require MCP reconnect (`/mcp`)

---

## YAML Structure (Preferred — work_item_schemas)

```yaml
work_item_schemas:

  your-schema-type:            # Matches items whose type field equals "your-schema-type"
    lifecycle: auto            # Optional: auto | manual | auto-reopen | permanent (default: auto)
    notes:
      - key: note-key          # Stable identifier; kebab-case
        role: queue            # Phase: "queue", "work", or "review"
        required: true         # true = blocks advance_item gate | false = shown but not enforced
        description: "..."     # Short label — shown in expectedNotes response
        guidance: "..."        # Optional — shown as guidancePointer in get_context()
        skill: "review-quality" # Optional — skill to invoke when filling this note (shown as skillPointer)
```

---

## YAML Structure (Legacy — note_schemas)

The `note_schemas` format is still supported for backward compatibility. New configs should use `work_item_schemas`.

```yaml
note_schemas:

  your-schema-tag:             # Matches items whose tags include "your-schema-tag"
    - key: note-key
      role: queue
      required: true
      description: "..."
      guidance: "..."
```

When using `note_schemas`, the `lifecycle` field is not available — all schemas default to `AUTO` lifecycle mode.

---

## Field Reference

### Schema-level fields (work_item_schemas only)

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `lifecycle` | no | string | `auto`, `manual`, `auto-reopen`, or `permanent`. Defaults to `auto` |

### Note-level fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `key` | yes | string | kebab-case, unique within schema, stable after creation |
| `role` | yes | string | `queue`, `work`, or `review` only |
| `required` | yes | boolean | `true` = blocks gate, `false` = shown but not enforced |
| `description` | yes | string | Keep under 80 chars — quick label agents scan |
| `guidance` | no | string | Free text shown as `guidancePointer` in `get_context` |
| `skill` | no | string | Skill to invoke when filling this note (surfaced as `skillPointer` in `get_context`) |

---

## Lifecycle Modes

The `lifecycle` field on a schema controls automatic cascade behavior when children reach terminal:

| Mode | Behavior |
|------|----------|
| `auto` | Default — parent cascades to terminal when all children are terminal |
| `manual` | Terminal cascade suppressed — parent must be explicitly completed. Reopen cascade also suppressed. |
| `auto-reopen` | Terminal cascade allowed. Parent also auto-reopens when a new child is created under it. |
| `permanent` | Parent never auto-terminates and never auto-reopens — always manual lifecycle. |

---

## Matching Rules

Schema resolution uses **type-first lookup with tag fallback**:

1. If the item has a `type` field, look up the schema by type in `work_item_schemas` → direct lookup (exact match)
2. If no type or no type-based schema found, look up by first tag match in `note_schemas` (legacy)
3. If no tag matches, fall back to the schema named `default` (in either section) if one exists
4. If nothing matches, the item is schema-free — no gate enforcement

**Key points:**
- Setting `type` on an item is the preferred way to activate a schema
- Tags can still be used for schema matching (legacy), but `type` takes precedence
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
- **Use `type` for schema selection, tags for categorization.** Mixing them causes confusion.

---

## Multiple Schemas

```yaml
work_item_schemas:

  schema-one:
    lifecycle: auto
    notes:
      - key: ...

  schema-two:
    lifecycle: manual
    notes:
      - key: ...
```

Each schema is independent. An item matches at most one schema.

---

## Mixed Legacy Example

If you have an existing config using `note_schemas`, you can add new schemas as `work_item_schemas` alongside it:

```yaml
work_item_schemas:

  feature-task:
    lifecycle: auto
    notes:
      - key: implementation-notes
        role: work
        required: true
        description: "What was built and why"

note_schemas:

  bug-fix:
    - key: root-cause
      role: queue
      required: true
      description: "Root cause analysis"
```

Items with `type: feature-task` use the `work_item_schemas` entry. Items with tag `bug-fix` use the legacy `note_schemas` entry.

---

## Key Stability Warning

Changing a `key` after notes have been written orphans existing notes under the old key. There is no automatic migration — orphaned notes become ad-hoc notes on their items.
