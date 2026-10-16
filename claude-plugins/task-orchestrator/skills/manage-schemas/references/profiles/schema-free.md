# Profile — Schema-Free Tracking

**Recommend when:** the user wants plain status/dependency tracking — kanban semantics, no
gates. Also the per-root fence for a gate-free project on a shared server.

```yaml
work_item_schemas:
  default:
    lifecycle: auto             # or manual/permanent for standing-workflow boards
    notes: []
```

**Rationale to present:** every item advances freely; the value is ordering (`items_read` `next`),
dependency blocking (`items_read` `blocked`), and hierarchy. On a shared server, pushing this
per-root (`config` `push`) fences the root off from the server's `default` schema, not from its typed
schemas: schemas resolve in a fixed order — the root's exact type, the server's exact type, the
root's `default`, the server's `default`. A type the server declares (the process types of the global
floor) keeps the server's schema even under an empty per-root `default`; an empty `default` here
only catches the types nobody declares. If every type in the project must be gate-free, give each type
an empty schema in the per-root config too.
