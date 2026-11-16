# trait-selection

Choose the item's traits before any dispatch. A trait pins the seats' dispatch profile, the notes the
gate asks for and the review lanes, so a missing trait is a missing gate, not a missing label. Read the
resolved profile and pass its model and effort on every dispatch (`items_read` `context` or `schema`,
`dispatchBySeat`); the trait decides the model, never the dispatching session.

A `traits` update replaces the list and re-pins the item: send every trait the item carries
(`items_write` `update`, or `traits` at item creation).

- `delegated` - Delegated or Parallel tier. `feature-implementation`, `feature-task` and `bug-fix`
  carry it by default; apply it by hand to other types (`config` `effective` lists the declared
  traits). It makes the orchestrator-filled `delegation-metadata` note schema-visible. Never on Direct
  tier.
- `complex-implementation` - raises the implementer's profile. Use it for a new public API, a
  cross-layer seam, a subtle state machine or concurrency.
- `high-stakes` - raises the planner's and reviewer's profile; use it sparingly, for security
  predicates, auth, data-loss paths and hard-to-reverse design. `needs-security-review` does not imply
  it.
- `needs-test-author` - `bug-fix` carries it by default. On a `feature-task`, apply it when an
  acceptance criterion involves a predicate, algorithm, parser, validator or state transition, or on a
  force-ON signal: a new public API, a migration, a security predicate, or a prior vacuous-test finding
  in the area. No other type carries it. On Direct tier it applies to `bug-fix` only, in its
  temporal-only degraded mode: the `test-plan` gate and red-first still apply, `test-manifest`
  declares one actor, and no separate seat is dispatched.
