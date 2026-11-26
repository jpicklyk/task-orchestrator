# 0012 The acceptance gate is a note satisfied by an answered decision

**Status:** Accepted | **Date:** 2026-11-24, amended 2026-11-26 | **Source:** [architecture-breakdown.md](../architecture-breakdown.md) "Gate form: recommendation"; behaviour from [acceptance-gate-states.md](../acceptance-gate-states.md), owner ruling 2026-11-24. Settles the form left open in [0008](0008-acceptance-is-a-server-gate.md). Amendment: Boundary analysis 2026-11-26, owner answers (plans/boundaries/README.md, not tracked), revised decisions 7 and 17.

**Amended 2026-11-26:** the predicate also checks that the answered acceptance decision carries its claims as evidence; "who writes it" names an orchestrator note instead of the `orchestrator` seat; an item in an open run gets its note written with the run token. Why: the build and findings claims were enforced only by the accept skill, so a client without it could pass a conditional grant unrecorded; the decision already carries evidence, so no second note is needed; and the orchestrator is never a seat ([0016](0016-seat-capability-and-orchestrator-notes.md)).

## Decision

A gated type declares a required acceptance note whose entry names a decision class and the options that satisfy it. The note counts as filled only when the latest answered decision of that class on the item carries one of those options, was given after the item last left `terminal`, and carries its claims as evidence: the commit, the verify command and its result, and the count of disposed findings, in the decision's `context` and `links`. The check is presence only, as provenance ([0008](0008-acceptance-is-a-server-gate.md)): the server does not judge the claims. Writing a body never fills it. No new role, transition or lifecycle step is added.

- **When it is checked.** Only on a transition into `terminal`; every other phase gate skips it.
- **Who writes it.** It is an orchestrator note (`seat: orchestrator` on its entry), written as a pointer to the decision plus the user's own words. Outside a run the orchestrator writes it directly. For an item in an open run it is written with the run token: the accept skill uses the token from the run files or hands the step to the run's orchestrator, and on a seat-aware type a token-less write there is refused by the existing note rule (`NoteSeatRule`). The gate reads the decision, never the body.
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
- **A separate claims note beside the decision.** A second record for evidence the decision request already carries ([0002](0002-director-on-orchestrator-tools.md): evidence travels with the decision).
- **A server rule that lets an entry skill act on an open-run member without the run token.** The existing refusals are correct; what was missing is the skill's route to the token or to the run's orchestrator.

## Consequences

- "Filled" is computed in at least five places outside the gate (`PhaseNoteContext`, three sites in `ItemsReadContext`, `TransitionOp`); all move to one decision-aware function that also applies the terminal-only rule, with a test that `context` and `advance` agree.
- On stdio the director and the user's session both answer as `local`: provenance, not proof, until credentials (P2) name the principal.
- The accept skill raises the acceptance decision with the claims in `context` and `links`; a raise without them can be answered but does not fill the note.
