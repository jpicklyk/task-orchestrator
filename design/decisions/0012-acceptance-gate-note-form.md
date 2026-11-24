# 0012 The acceptance gate is a note satisfied by an answered decision

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** [architecture-breakdown.md](../architecture-breakdown.md) "Gate form: recommendation"; settles the open question in [0008](0008-acceptance-is-a-server-gate.md)

## Decision

The acceptance gate of [0008](0008-acceptance-is-a-server-gate.md) takes the note form. A gated type declares a required acceptance note whose entry names a decision class and the options that satisfy it. The note counts as filled only when an answered decision of that class, with one of those options, exists for the item. Writing a body does not fill it. No new role, transition or lifecycle step is added.

## Context

Both forms were costed against the code. Every gate sees one snapshot built by the snapshot loader, which already decides which note keys are filled, and start out of review, complete and every terminal cascade go through it. The loader can already read an item's decisions. A note satisfied by a decision is therefore one more predicate there, in about 8 to 10 production files, with no migration. Independence findings and the review-pending gate are existing non-body gate checks of the same kind.

## Alternatives rejected

- **A new lifecycle role or transition.** The role appears in 65 main files and is constrained in the `work_items` table, so it needs a table-rebuild migration, about 25 golden files and over 100 plugin files and fixtures.
- **A separate acceptance gate outside the note model.** About the same size as the note form, but it adds a gate concept the note model already covers.

## Consequences

- "Filled" is computed in at least five places outside the gate (`PhaseNoteContext`, three sites in `ItemsReadContext`, `TransitionOp`). All must move to one decision-aware function, with a test that compares what `context` reports with what `advance` allows.
- Design settles six questions before code: which options satisfy the note; an answer older than the item's last reopen does not satisfy it; cancel still skips the note gate; the conditional plan-approval grant is an answered decision with a satisfying option, from the director or a human, with no other decision open and the review notes filled; shipped acceptance classes are `director-or-human`, and the validator warns when a gated type names a `human-only` class (unanswerable over stdio); after an answer the accept skill advances an auto parent explicitly, since a suppressed cascade does not re-fire.
