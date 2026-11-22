---
name: planning-standards
description: "Sets the bar for planning a software change before any code is written: testable acceptance criteria, real alternatives, resolved decisions, non-goals, blast radius, risk flags, test strategy, architecture fit and reuse before build. Use when writing or checking a plan, specification, task scope or bug diagnosis for a code change."
user-invocable: false
---

# Planning standards

These standards apply to any plan, specification or diagnosis written before a code change,
sized to the kind of change.

A plan is worth the thinking it forces before code exists. Every sentence should prevent a
mistake or force a decision; a section that changes nothing about how the work is done does not
belong. This is a floor, not a ceiling: add analysis the problem demands, but do not skip what is
below.

## Size the plan to the change

| Kind of change | What the plan holds |
|----------------|---------------------|
| Behaviour change | The full bar: every section below |
| Docs-only or single-file change | Keeps: goal, files, acceptance criteria, one non-goal, a verify command. Drops: alternatives, blast radius, risk flags and test scenarios |
| Bug | Reproduction, root cause, the fix, every other site of the same defect (see [claims and sweeps](references/claims-and-sweeps.md)), and a regression scenario seen failing before the fix is applied |

## Acceptance criteria

Write each criterion so a stranger could run it and get a yes or no. State the observable
result, not the activity ("rejects an empty name with a clear error", not "handle empty names").
Number them so later work can point at one.

Self-check: pick any criterion and ask what command or observation turns it into pass or fail. If
you cannot name one, rewrite it.

## Alternatives considered

Evaluate two real approaches. Doing nothing counts as one. For each, say what it looks like and
the specific trade-off that rejected it. The aim is to catch yourself before committing to an
approach with a better neighbour. A strawman ("rewrite everything; too much work") forces no
thinking.

Self-check: would a skilled colleague who favours the rejected option accept your stated reason?
If the reason is only "too much work" or "worse", it is a strawman.

## Open decisions

Resolve every open choice (a name, an interface shape, an option under consideration) before work
starts. A pivot after code exists costs several times a short question asked up front. If a
choice truly depends on something measurable only after the first step, it is not open: it is a
two-stage plan, so scope stage one explicitly and describe stage two as its own piece of work.

Self-check: search the plan for "or", "TBD", "options" and "to be decided". Each hit is either
resolved or restated as a stage boundary.

## Non-goals

Name what a reader would reasonably expect the work to include but that is deliberately left out.
Without them, work drifts into adjacent improvements that carry unplanned risk.

Self-check: if you cannot name one non-goal, the scope is not tight yet.

## Blast radius

List every module, file and interface the change touches, then trace downstream consumers: who
calls a changed signature, which tests assume an old default, which docs name a changed command.
If the change touches a surface with an automated budget, ceiling or quota test, measure the
current headroom while planning and write down the measured and predicted values. Deferring the
measurement to build time means the scope decision is already made.

Self-check: for each changed interface, can you point to the search that found its consumers?
See [claims and sweeps](references/claims-and-sweeps.md).

## Risk flags

Call out the one or two things most likely to go wrong: tight coupling, a migration, concurrency,
or a part of the code you do not yet understand. Make uncertainty explicit instead of hidden.

Self-check: each flag names a concrete failure, not a mood ("may be slow" is a mood; "the
lookup runs once per row of a 10k-row table" is a failure).

## Test strategy

Name happy, failure and edge scenarios concretely, give each a stable id and an oracle that is
not the code's current output, and list the existing tests the change breaks with the edit each
needs. Detail in [test strategy](references/test-strategy.md).

Self-check: could someone write each test from the plan alone, without reading the
implementation?

## Architecture fit

Find the project's stated layering and dependency rules, place the change in the module it
belongs in, and look for an existing helper or pattern before proposing a new one. Detail in
[architecture fit](references/architecture-fit.md). For each new seam, say what it hides, whether a second
implementation or test fake exists, and which way its dependencies point; when the change is
awkward in the current structure, plan the restructuring first.

Self-check: for every new type or helper, can you name the existing one you looked at and why it
did not serve?

## Claims in a plan

A plan states facts about the code. A fact owned by another artifact is cited, not restated, and a
claim of nullability, redaction, ownership, a default or a limit names the file and line that
prove it. A negative or exhaustive claim names its search. Detail in
[claims and sweeps](references/claims-and-sweeps.md).

Self-check: for each fact about the code, can you point at the line that shows it or the search
that found it?

## Completion checklist

- [ ] Sized to the change kind; sections the size row drops are absent, not padded
- [ ] Acceptance criteria are numbered and each is checkable
- [ ] Two real alternatives, doing nothing counted as one (full bar only)
- [ ] No open decision remains; any deferred choice is a stage boundary
- [ ] At least one non-goal named
- [ ] Downstream consumers of every changed interface found by a named search (full bar only)
- [ ] Budget or ceiling headroom measured, if the change touches one
- [ ] One or two concrete risk flags (full bar only)
- [ ] Contracts cited, not restated; semantic claims carry file and line
- [ ] Verify commands shown to pass on good input and fail on bad
- [ ] Test scenarios named with ids, oracles and the existing tests that break (full bar; for a bug, the regression scenario seen failing before the fix)
- [ ] Layering respected, reuse searched, every wiring site named

## References

- [Claims and sweeps](references/claims-and-sweeps.md): citing contracts, proving claims, sweeping for siblings, proving verify commands
- [Test strategy](references/test-strategy.md): scenario derivation, ids, oracles, tests a change breaks
- [Architecture fit](references/architecture-fit.md): layering, reuse before build, wiring sites
