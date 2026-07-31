# Profile — Schema-Free Tracking

**Recommend when:** the user wants plain status/dependency tracking — kanban semantics, no
gates. Also the per-root fence for a gate-free project on a shared server.

```yaml
work_item_schemas:
  default:
    lifecycle: auto             # or manual/permanent for standing-workflow boards
    notes: []
```

**Rationale to present:** every item advances freely; the value is ordering (`get_next_item`),
dependency blocking (`get_blocked_items`), and hierarchy. On a shared server, pushing this
per-root fences off the global config entirely (per-root resolution is whole-algorithm-first —
this root's `default` beats even a global exact-type match).
