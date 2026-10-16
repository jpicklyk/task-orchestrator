# Delete Schema Workflow

Remove a schema from `.taskorchestrator/config.yaml`.

---

## Identify Schema

Read `.taskorchestrator/config.yaml`. If the schema name was not provided in `$ARGUMENTS`, list all available schemas (from `work_item_schemas:`) and ask the user to pick one via `AskUserQuestion`.

---

## Warn About Orphaned Notes

Before deleting, warn the user:

```
⊘ Warning — Deleting schema "bug-fix"

New items with type "bug-fix" will have no schema applied, and existing ones lose
gate enforcement once repinned:
- Notes already written on those items will remain as ad-hoc notes
- In-flight items keep their pinned schema, and so their gate, until they are repinned;
  after a repin, advance no longer checks required notes for them
- New items with type "bug-fix" will resolve the next schema in the fixed order
  (a `default` schema, if one is declared) or none

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

Remove the schema key and all its entries from `work_item_schemas:`. If this was the only schema, leave the file with an empty `work_item_schemas:` key:

```yaml
work_item_schemas:
```

**Preserve all other top-level keys verbatim.** Deleting a schema must not touch `project`, `extensions`, `note_limits`, `traits`, `resources`, `runs`, `decision_classes`, or any other top-level key — carry them through exactly as read rather than reconstructing the file from a parsed model. Never write `actor_authentication`, `schema_resolution` or a top-level `orchestration:`/`retrospective:`/`actor_attribution:`; flag one already in the file (manage-schemas skill, Step 2).

Write the updated file back, then validate and push (manage-schemas skill, Step 4) when the file has a `project.rootId`. The `validate` result's `affectedInFlight` lists the in-flight items whose schema would change; offer `items_write` `repin` for them.

---

## Report

```
✓ Schema "bug-fix" deleted from .taskorchestrator/config.yaml
  Removed: 4 note definitions (1 queue, 2 work, 1 review)
  Existing items with type "bug-fix" are unaffected until repinned — notes remain as ad-hoc

  Pushed to root <rootId>: version <n>   (or: local file only; server file needs a restart)
```
