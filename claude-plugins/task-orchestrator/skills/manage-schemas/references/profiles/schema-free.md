# Profile — Schema-Free Tracking

**Recommend when:** the user wants plain status/dependency tracking — kanban semantics, no
gates.

```yaml
work_item_schemas:
  task:
    lifecycle: auto             # or manual/permanent for standing-workflow boards
    notes: []
  container:
    lifecycle: permanent
    notes: []
  default:                      # optional: items typed `default`, or whose type is dropped later
    notes: []
```

**Rationale to present:** every item advances freely; the value is ordering (`items_read` `next`),
dependency blocking (`items_read` `blocked`), and hierarchy. A create must name a declared type (root
plus server; when nothing is declared anywhere, any type is accepted), so an empty `default` alone
admits nothing new: declare each type the project uses with `notes: []`. A server-declared type keeps
the server schema unless the root declares the same type.
