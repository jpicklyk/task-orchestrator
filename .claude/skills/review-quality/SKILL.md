---
name: review-quality
description: This codebase's review supplement. Repo-specific review inputs for the work-to-review gate: how build state is read, the needs-test-author inputs and judgments, the docs-accuracy checks, and two checks the generic review method lacks. Referenced by schema guidance fields during review-phase note filling.
user-invocable: false
---

# Review Supplement for This Codebase

This supplements the generic review method; it never repeats it.

## Build state

Read the build state the orchestrator recorded (the review scoping slot's build-state line, the
`session-tracking` `Verified:` line or the `test-manifest` VERIFY line) and run no Gradle suite:
rule `test-author` section 5 points 8 and 11. A missing build state, or one for an older SHA than
the diff under review, is a blocking finding.

## Test substance (every reviewed item, with or without `needs-test-author`)

Fetch the served rules `forbidden-test-patterns` and `test-assertion-vacuity` as rules (`config` op
`rules`, `key`, `rootId`) and judge every test the diff adds or changes against them. Also check
for happy-path-only coverage: tests that cover only the happy path of a change that has failure and
edge cases are a gap to report.

## Items with the `needs-test-author` trait

- Inputs: the `test-plan` and `test-manifest` notes, and the orchestrator's per-child SHA table
  (Pre-SHA, Post-SHA, Test-Pre-SHA, Test-Post-SHA) with any declared fixture-repair SHAs. A
  trait-bearing item with no `test-plan` note is a blocking issue on its own.
- Declared orchestrator fixture repairs are judged by rule `test-file-ownership` point 4 and rule
  `review-scoping` point 3; they may touch only construction or setup code, never assertions.
- An oracle that matches the implementation but not its cited source is blocking.
- `A<n>` lines in `orchestrator-confirmation` are red-test rulings (rule `arbitration`).
- A false forbidden-pattern declaration weighs as `not-independent`.
- An independence result of `independent-degraded (temporal-only)` maps to Pass: record it as the
  observed mode, not as a finding.
- Spot-check the coverage mapping: open at least two mapped tests and read their bodies rather
  than trusting the manifest alone.
- Check the planning freeze: when the schema declares `amendments`, the planning note's
  `modifiedAt` (`notes_read` `list`) is its freeze time; otherwise its `## Amendment A<n>`
  sections are the only later edits. Compare the freeze with the implementer's first commit.

## Items with the `needs-docs-accuracy-review` trait

Rule `review-cited-claims` point 1 governs cited lines; on a docs item it also covers claims of
defaults, limits, budgets and ordering, and wording copied from an existing doc is unverified
until its source is opened.

- Open each `claim -> source file:line` pair the planner lists.
- Check the Docs and CHANGELOG lines against `git diff --name-only <base>...HEAD`.
- Scan changed files for bytes below 0x20 other than TAB and LF, non-UTF-8 bytes and CR line
  endings.

## Two checks the generic method lacks

- **Root cause.** A fix addresses the diagnosed root cause, not its symptom.
- **Scenario coverage.** Every scenario in the test strategy and every edge case in the diagnosis
  maps to a test; a missing one is a gap to report.
