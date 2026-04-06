# Delete Schema Workflow

Remove a schema from `.taskorchestrator/config.yaml`.

---

## Identify Schema

Read `.taskorchestrator/config.yaml`. If the schema name was not provided in `$ARGUMENTS`, list all available schemas (from both `work_item_schemas:` and `note_schemas:`) and ask the user to pick one via `AskUserQuestion`.

---

## Warn About Orphaned Notes

Before deleting, warn the user:

```
⊘ Warning — Deleting schema "bug-fix"

Existing items with type "bug-fix" will lose gate enforcement:
- Notes already written on those items will remain as ad-hoc notes
- advance_item will no longer check for required notes on those items
- New items with type "bug-fix" will have no schema applied

This does NOT delete any existing notes or items — it only removes the schema definition.
```

Require explicit confirmation via `AskUserQuestion`:
```
Delete the "bug-fix" schema from config.yaml?
  1. Yes, delete it
  2. No, keep it
```

---

## Remove and Write

Remove the schema key and all its entries from the appropriate section (`work_item_schemas:` or `note_schemas:`). If this was the only schema, leave the file with an empty `work_item_schemas:` key:

```yaml
work_item_schemas:
```

Write the updated file back.

---

## Report

```
✓ Schema "bug-fix" deleted from .taskorchestrator/config.yaml
  Removed: 4 note definitions (1 queue, 2 work, 1 review)
  Existing items with type "bug-fix" are unaffected — notes remain as ad-hoc

  MCP reconnect required: /mcp
```
