---
name: review-quality
description: Review quality framework for the work-to-review transition gate. Guides verification of plan alignment, test quality, and code simplification before marking implementation complete. Referenced by schema guidance fields during review-phase note filling. Use when filling review-checklist notes or when asked to review completed implementation work.
user-invocable: false
---

# Review Quality Framework

This skill defines what a reviewer must verify before implementation work advances to
completion. It applies whether the reviewer is the orchestrator directly or a delegated
subagent.

The review gate exists because implementation agents optimize for getting things working,
not for verifying they built the right thing. Without a structured review checkpoint,
planned work gets silently dropped, tests get written to pass rather than to verify, and
unnecessary complexity accumulates. The review is where these failure modes get caught.

**Critical separation of concerns:** The reviewer must not be the same agent that wrote
the code or the tests. An agent reviewing its own work will rationalize rather than
evaluate. The reviewer reads, runs, and reports — it never fixes. If issues are found,
they go back to the implementation agent for resolution.

This is the same principle the `needs-test-author` trait applies one step earlier, to
test authorship itself — the test author must not be the implementer, for the same
rationalize-not-evaluate reason. Verifying that separation actually held (see
"Independence verification" in Area 3) is this rule applied to test authorship, not an
optional extra.

---

## Getting Started

The reviewer is given an MCP item ID. Use MCP tools and codebase access to gather what
you need — do not expect context to be pre-loaded for you.

1. **Load the item's notes** — `query_notes(itemId=..., includeBody=true)` to retrieve
   the planning note and `implementation-notes`. The planning note's key depends on the
   item's schema: `feature-summary` (feature-implementation), `task-scope` (feature-task),
   or `diagnosis` (bug-fix).
2. **Read the changed files** — use the implementation notes to identify which files were
   modified, then read them directly. Review the actual code, not just summaries.
3. **Run the test suite** — execute the project's test command and capture the results.
   Do not assume tests pass because the implementation agent said they did.
4. **For items carrying the `needs-test-author` trait** — load the trait's `test-plan`
   and `test-manifest` notes via `query_notes(operation="list", itemId=...,
   includeBody=true)`, and obtain the per-child commit-SHA table from the orchestrator.
   A trait-bearing item with no `test-plan` note is a blocking issue on its own — do not
   proceed to the Area 3 independence verification until the note exists.

If the planning note (feature-summary / task-scope / diagnosis) or implementation notes
are missing, the review cannot proceed. Report this as a blocking issue.

Before reporting any file or artifact as missing, or any behavior as broken, verify with
a direct check — Read the exact expected path, an exact-path Glob, or a reproduction —
rather than inferring absence from one plausible directory or from a prior bug's pattern.
Two review false-positives reached verdicts this way before being caught downstream.

---

## Review Areas

These four areas form the minimum review. Each one catches a different class of failure.
If the review surfaces additional concerns, include them — this is a floor, not a ceiling.

### 1. Test Suite Verification

Run the test suite before anything else. Everything downstream depends on knowing the
actual state of the tests.

Run `./gradlew :current:test` and capture the output. Record the total test count and
the pass/fail breakdown.

**If tests fail:** Document every failure — test name, assertion message, and the file
where the test lives. Do not attempt to fix failures. Do not speculate about whether
failures are pre-existing or new. Report what you observe. Test failures are a blocking
issue — the item cannot advance with a failing test suite.

**If tests pass:** Record the count and move on.

### 2. Plan Alignment

Compare what was built against the planning note (feature-summary / task-scope /
diagnosis). The goal is to catch drift in both directions — work that was planned but
not done, and work that was done but not planned.

**Check each acceptance criterion.** Walk through the acceptance criteria from the
planning note one by one. For each criterion, identify the specific code change that
satisfies it. If a criterion has no corresponding implementation, flag it — either the
work is incomplete or the criterion was intentionally descoped (which should appear in
the implementation notes).

**Check for unplanned changes.** Review the changed files for modifications that don't
trace back to any acceptance criterion. Unplanned changes aren't automatically wrong —
sometimes implementation reveals necessary adjacent work. But they should be
acknowledged and justified in the implementation notes, not silent.

**Check non-goals weren't violated.** Review the planning note's non-goals list. If the
implementation touched areas that were explicitly scoped out, flag it.

### 3. Test Quality

The planning note's test strategy defined what should be tested — happy paths, failure
paths, and edge cases. The reviewer verifies that the tests actually deliver on that
strategy, not just that they exist and pass.

This is where the separation of concerns matters most. The agent that wrote the tests
has an inherent bias toward believing they're correct. An independent reviewer can
evaluate whether the tests verify real behavior or just confirm that code runs.

**Map tests to the test strategy.** For each scenario in the planning note's test
strategy, identify the corresponding test. Missing coverage is a gap to report.

**Evaluate test substance.** Watch for these patterns that produce green results
without catching real bugs:

- **Tautological assertions** — asserting something equals itself, or that a non-null
  value is not null, without verifying the actual value is correct.
- **Mock-heavy tests that verify nothing real** — every dependency mocked, test only
  confirms mocks were called in order. Mocks are fine for isolation, but the test must
  still assert something meaningful about the unit's output or state change.
- **Happy-path-only coverage** — if the test strategy called for failure paths, those
  tests need to exist and need to verify the failure behavior is correct (right
  exception type, right error message, right fallback behavior).
- **Overly broad assertions** — `result != null` or `list.isNotEmpty()` when specific
  values, sizes, or contents should be checked. These pass even when the implementation
  is wrong.
- **Assumption escapes** — `assumeTrue` (or an equivalent guard) gating out a real
  failure instead of asserting against it, so the test silently skips rather than
  reporting the bug it was written to catch.
- **Implementation-derived oracles** — an expected value computed from the
  implementation's own formula or output rather than from an independent oracle source.
  These pass by construction and verify nothing.

**Check edge cases.** Verify each boundary condition from the test strategy has a
corresponding test. If implementation notes documented new edge cases discovered during
development, check whether tests were added for those too.

**Independence verification (items with the `needs-test-author` trait).** When the item
carries this trait, verify the test-author/implementer separation actually held before
trusting anything else found in this area — a compromised separation undermines every
other finding above it:

- **Separation held.** Confirm the test files were *introduced* within the test author's
  commit range, not the implementer's — check `git log` over both ranges using the
  `test-manifest`'s commit-SHA table. Record the result as `independent` when actor and
  commit-range separation both hold, `independent-degraded (temporal-only)` when only
  ordering separates them (for example a Direct-tier bug-fix run in single-actor mode),
  or `not-independent` when separation did not hold. Any silent implementer edit to a
  test file after the author's range is a blocking issue, regardless of whether the edit
  looks benign.
- **Manifest maps to spec scenarios.** Cross-check the `test-manifest`'s S-id-to-test
  mapping against the `test-plan`'s numbered scenarios (S1…): every scenario must be
  marked covered or explicitly not-covered with a reason. Do not take "covered" on
  faith — open at least two of the claimed tests and read their bodies to verify the
  claimed coverage is real.
- **Oracle spot-check.** Pick one non-trivial scenario and trace its expected value back
  to the oracle source recorded in the `test-plan` (spec clause, stated algorithm, or
  external reference). If the expected value matches what the implementation produces
  but not what the named oracle source specifies, that is a blocking issue — it means
  the oracle was derived from the implementation rather than the spec.
- **Arbitration record.** Every ambiguity the test author flagged must have a named
  resolver in the manifest's arbitration record. Give oracle-degraded scenarios — ones
  where the oracle source itself was uncertain — a second, closer look.

### 4. Simplification

The reviewer does not run `/simplify` — that pass belongs at the feature level, not
per-task review. Check the implementation notes for whether `/simplify` was run during
implementation.

**If `/simplify` made changes**, verify those changes have test coverage. This is the
one thing the reviewer checks here — not the simplification itself, just whether the
resulting code is tested. Report coverage gaps; do not re-run simplify or evaluate its
judgment calls.

**If `/simplify` was not run or made no changes**, there is nothing to check in this
area — move on.

Coverage gaps found here are not blocking unless they leave a structural change
entirely unverified.

---

## Review Output

The review produces a `review-checklist` note on the MCP item. Structure the note
around findings, not process.

### Verdict

Every review must end with a clear verdict:

- **Pass** — all acceptance criteria met, tests pass and have substance, no blocking
  issues. The item can advance.
- **Fail — blocking issues** — test failures, missing acceptance criteria, critical
  gaps in test coverage, or (for items with the `needs-test-author` trait) a
  `not-independent` independence-verification result or an unexplained implementer edit
  to test files. These fail the item even when the test suite is green — a compromised
  separation makes a passing suite untrustworthy. The item must go back for fixes before
  it can advance. List every blocking issue.
- **Pass with observations** — no blocking issues, but simplification findings or
  minor test quality concerns worth addressing. The item can advance, but the
  observations should be tracked for follow-up.

### Findings Format

Report every finding you observe, at every severity. Do not withhold minor findings
and do not apply a high-severity-only bar — current models follow severity filters
literally, which suppresses real findings. Mark each finding blocking or observation
and state your confidence; the orchestrator's verdict handling (see Verdict, above)
is the downstream filter, not your own judgment about what is worth mentioning.

For each finding, state:
- **What was expected** (from the planning note or test strategy)
- **What was found** (in the code or test output)
- **Severity** (blocking or observation)
- **Confidence** (how sure you are this is a real issue, not a false positive)

Be specific. "Tests could be better" is not actionable. "Test `testCreateItem` asserts
only that the result is not null — it should verify the item's title and status match
the input parameters" is actionable.

### Gate Enforcement

The reviewer does not advance the item. It fills the `review-checklist` note and
reports the verdict. The orchestrator reads the verdict and decides whether to:
- Advance the item (pass or pass-with-observations)
- Send the item back to the implementation agent with the blocking issues list (fail)

A failing verdict with clear findings gives the implementation agent exactly what to
fix without ambiguity.
