# 0016 Acceptance covers the subtree; cancel skips it only by the item's own hand

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** [acceptance-gate-states.md](../acceptance-gate-states.md) open questions 3, 4 and 6 and the new questions on subtree scope, explicit complete with open children and grants before refinement; [journey-sequences.md](../journey-sequences.md) journey 1 (the grant's checks are split); owner ruling 2026-11-24 ("Yes I agree with your decisions"). Refines [0008](0008-acceptance-is-a-server-gate.md) and [0012](0012-acceptance-gate-note-form.md).

## Decision

- **Cancel.** An item's own `cancel` skips the acceptance gate. A terminal cascade that counts as a cancel because the last child to finish was cancelled does not skip it.
- **Subtree.** "No decision open" for acceptance counts pending decisions in the item's whole subtree.
- **Open children.** On a type with an acceptance gate, an explicit `complete` on a parent with non-terminal children is refused and lists them; `cancel` stays allowed. Types without the gate keep today's behaviour.
- **Refinement.** No extra rule: a grant counts only on entry to `terminal`, and `complete` already checks every phase's required notes, so recording a grant early (as plan approval does) is harmless.
- **Build and findings.** The server does not check build state. The conditional grant's "build green" and "findings disposed" conditions are claims the accept skill records before it advances, and each build claim names the commit and the command, so a stale green is visible.

## Context

A cascade's cancel origin comes from the trigger of the child that finished last (`TransitionPolicy.kt:464`). If three children deliver and the last is cancelled, the parent's cascade is cancel-origin and, under 0012's "cancel skips the note gate", would complete without acceptance although work landed. Children are checked today only for cascades (the WARRANT gate, `TransitionPolicy.kt:167-173`), so an explicit `complete` passes over open children. The server has no source of build state; giving it one is a large package of its own.

## Alternatives rejected

- **Every cancel-origin path skips the gate.** Lets delivered work close unaccepted.
- **"No decision open" on the item alone.** Accepting an epic while a child waits on a decision accepts an unknown.
- **A server build-state check now.** Needs a CI integration or a build-result record the server does not have.

## Consequences

- The gate distinguishes the item's own cancel from a cancel-origin cascade.
- The subtree check is one ancestor-scoped count of pending decisions.
- [journey-sequences.md](../journey-sequences.md) journey 1's gap "the gate alone lets a red build through" stands by design: the claim is the record, and the review that precedes acceptance reads build state.

## Reopen when

Runs record CI results the server can read.
