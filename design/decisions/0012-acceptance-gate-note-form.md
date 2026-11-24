# 0012 The acceptance gate is a note satisfied by an answered decision

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** [architecture-breakdown.md](../architecture-breakdown.md) "Gate form: recommendation"; behaviour from [acceptance-gate-states.md](../acceptance-gate-states.md), owner ruling 2026-11-24. Settles the form left open in [0008](0008-acceptance-is-a-server-gate.md).

## Decision

A gated type declares a required acceptance note whose entry names a decision class and the options that satisfy it. The note counts as filled only when the latest answered decision of that class on the item carries one of those options and was given after the item last left `terminal`. Writing a body never fills it. No new role, transition or lifecycle step is added.

- **When it is checked.** Only on a transition into `terminal`; every other phase gate skips it.
- **Who writes it.** The `orchestrator` seat, as a pointer to the decision plus the user's own words. The gate reads the decision, never the body.
- **Decisions follow the item.** Staleness is computed from the newest transition event, never stored. A new acceptance raise reuses an earlier one only if it is pending or answered since the item last left `terminal`. Cancel withdraws open acceptance decisions; reopen leaves them. Each answer records the tool profile that gave it.
- **Cancel.** The item's own `cancel` skips the gate; a cascade that is cancel-origin only because the last child was cancelled does not.
- **Parents.** "No decision open" counts the whole subtree. On a gated type, an explicit `complete` with non-terminal children is refused. A suppressed cascade does not re-fire, so the accept skill advances an auto parent explicitly.
- **stdio.** Shipped acceptance classes are `director-or-human`; the validator warns on `human-only`. Until P2, setup registers a second stdio entry with `MCP_TOOL_PROFILE=director` so a stdio user can answer.

## Context

Every gate sees one snapshot built by the snapshot loader, which already decides which note keys are filled and can read an item's decisions, so the form is one more predicate there: about 8 to 10 production files, no migration. No existing phase fits the note: a `review` note forces a review phase on every gated type, and a `work` note would block the move from work to review. Today a raise returns any non-withdrawn decision with the same dedupe key (`DecisionService.kt:111`), so without the lifecycle rule a raise after reopen would return the old answer. A cascade's cancel origin comes from the last child to finish (`TransitionPolicy.kt:464`), so treating every cancel-origin cascade as a skip would close delivered work unaccepted.

## Alternatives rejected

- **A new lifecycle role or transition.** The role appears in 65 main files and is constrained in the `work_items` table: a table-rebuild migration, about 25 goldens and over 100 plugin files.
- **A separate gate outside the note model.** About the same size, and it adds a concept the note model already covers.
- **A stored stale flag, or a reopen counter in the dedupe key.** A migration, or a rule every caller must get right.

## Consequences

- "Filled" is computed in at least five places outside the gate (`PhaseNoteContext`, three sites in `ItemsReadContext`, `TransitionOp`); all move to one decision-aware function that also applies the terminal-only rule, with a test that `context` and `advance` agree.
- On stdio the director and the user's session both answer as `local`: provenance, not proof, until credentials (P2) name the principal.
