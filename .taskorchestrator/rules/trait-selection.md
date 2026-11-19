# trait-selection

Choose the item's traits before any dispatch. A trait pins the seats' dispatch profile, the notes the
gate asks for and the review lanes, so a missing trait is a missing gate, not a missing label. Read the
resolved profile and pass its model and effort on every dispatch (`items_read` `context` or `schema`,
`dispatchBySeat`); the trait decides the model, never the dispatching session.

A `traits` update replaces the list and re-pins the item: send every trait the item carries
(`items_write` `update`, or `traits` at item creation).

Each trait's applicability is its `appliesWhen` in `config` `effective`. What config cannot say:

- `delegated` is never applied on Direct tier.
- `needs-test-author` applies on Direct tier to `bug-fix` only, in its temporal-only degraded mode:
  the `test-plan` gate and red-first still apply, `test-manifest` declares one actor, and no separate
  seat is dispatched.
- `reviewed` is a default trait of `feature-implementation`, `feature-task`, `bug-fix` and
  `plugin-change`. `needs-task-review` is retired and never applied; an item that still carries it
  keeps it until a `traits` update re-pins it.
