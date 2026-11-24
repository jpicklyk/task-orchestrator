# 0014 The acceptance note is checked only on entry to terminal

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** [acceptance-gate-states.md](../acceptance-gate-states.md) open question 1 and new question "which phase and seat own the note"; owner ruling 2026-11-24 ("Yes I agree with your decisions"). Refines [0012](0012-acceptance-gate-note-form.md).

## Decision

The acceptance note keeps a role for display but is marked to be checked only on a transition whose target is `terminal`; the note gate skips it on every other transition. The `orchestrator` seat writes it; its body is a pointer to the satisfying decision plus the user's own words, and the gate reads the decision, never the body. Only an option the note entry names satisfies it, only the latest answered acceptance decision for the item counts (so a later send-back cancels an earlier grant), and an answer carrying text but no option never satisfies it.

## Context

Every existing phase is wrong for the note. A `review` note gives each gated type a review phase, against the rule that review is opt-in. A `work` note is worse: `start` out of `work` checks that phase's required notes, so the item could not reach review before it was accepted. The person accepting never holds a seat, and driving and deciding stay apart, so the seat that writes the note is the orchestrator, recording the answer it was given.

## Alternatives rejected

- **A `review`-role note.** Forces a review phase on every gated type.
- **A `work`-role note checked on every phase gate.** Blocks the move from work to review.
- **A new acceptance phase or role.** Rejected in [0012](0012-acceptance-gate-note-form.md) on cost.
- **The body as the proof.** Any writer of the note could then grant acceptance.

## Consequences

- One schema property on a note entry and one filter in the NOTE gate; no new phase and no migration.
- The single decision-aware "filled" function that [0012](0012-acceptance-gate-note-form.md) requires must also apply the terminal-only filter, so `context` does not report the note as missing during work or review.
- A body that points at a withdrawn or stale decision does not satisfy the gate.
