---
name: review-quality
description: This codebase's review supplement. Repo-specific review inputs for the work-to-review gate: how build state is read, the needs-test-author inputs and judgments, and two checks the generic review method lacks. Referenced by schema guidance fields during review-phase note filling.
user-invocable: false
---

# Review Supplement for This Codebase

This supplements the generic review method; it never repeats it.

## Build state

Read the build state the orchestrator recorded (the review scoping slot's build-state line, the
`session-tracking` `Verified:` line or the `test-manifest` VERIFY line) and run no Gradle suite:
rule `test-author` section 5 points 8 and 11. A missing build state, or one for an older SHA than
the diff under review, is a blocking finding.

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

## Two checks the generic method lacks

- **Root cause.** A fix addresses the diagnosed root cause, not its symptom.
- **Scenario coverage.** Every scenario in the test strategy and every edge case in the diagnosis
  maps to a test; a missing one is a gap to report.
