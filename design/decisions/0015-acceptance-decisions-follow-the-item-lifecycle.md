# 0015 Acceptance decisions follow the item's lifecycle

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** [acceptance-gate-states.md](../acceptance-gate-states.md) open questions 2 and 5 and the new questions on re-raise, cancel and reopen, and stdio identity; owner ruling 2026-11-24 ("Yes I agree with your decisions"). Refines [0012](0012-acceptance-gate-note-form.md).

## Decision

An acceptance answer counts only if it was given after the item last left `terminal`; staleness is computed from the newest transition event and never stored. A raise of an acceptance-class decision matches an earlier one on its dedupe key only when that one is pending or answered after the item last left `terminal`; otherwise a new decision is raised. When an item reaches `terminal` by cancel, the server withdraws any open acceptance decision on it and records why; a reopen changes no decision, and earlier answers simply become stale. Each answer records which tool profile answered it (director or orchestration); on stdio the answerer stays `local`, and proof of who answered is left to credentials.

## Context

Today a raise returns any earlier decision with the same root, scope and dedupe key unless it was withdrawn, answered ones included (`DecisionService.kt:111`). Once reopen can undo acceptance, a fresh acceptance request after a reopen would get back the answer given before it, and the gate would pass on stale consent. An open acceptance decision on a cancelled item would sit in the inbox asking about work that no longer exists. The director path is not a security boundary, and for a solo stdio user the director and the session are the same person.

## Alternatives rejected

- **A reopen counter in the dedupe key.** Every caller would have to build the key correctly; the server rule covers them all.
- **A stored `stale` flag or column.** Needs a migration and a writer on every transition; the event history already holds the time.
- **Withdrawing answered decisions on reopen.** Loses the history of what was accepted before.
- **Distinguishing stdio answerers without credentials.** Any label the client sends is a claim, not proof.

## Consequences

- The dedupe rule differs by class: acceptance classes use the lifecycle-aware match, other classes keep today's.
- The cancel path gains a server effect on decisions; it runs in the same unit of work as the transition.
- The decision record gains the answering profile; its storage and wire shape go to the gate package's plan.
- Until P2, only a director-profile connection can answer, so setup registers a second stdio entry with `MCP_TOOL_PROFILE=director` for a stdio user.

## Reopen when

Credentials (P2) can name the answering principal on stdio.
