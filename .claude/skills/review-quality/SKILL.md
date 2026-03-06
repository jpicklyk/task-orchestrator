---
name: review-quality
description: Review quality framework for the work-to-review transition gate. Guides verification of plan alignment, test quality, and code simplification before marking implementation complete. Referenced by schema guidance fields during review-phase note filling. Read this skill when filling review-checklist notes or when asked to review completed implementation work.
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

---

## Getting Started

The reviewer is given an MCP item ID. Use MCP tools and codebase access to gather what
you need — do not expect context to be pre-loaded for you.

1. **Load the item's notes** — `query_notes(itemId=..., includeBody=true)` to retrieve
   the `specification` and `implementation-notes`.
2. **Read the changed files** — use the implementation notes to identify which files were
   modified, then read them directly. Review the actual code, not just summaries.
3. **Run the test suite** — execute the project's test command and capture the results.
   Do not assume tests pass because the implementation agent said they did.

If the specification or implementation notes are missing, the review cannot proceed.
Report this as a blocking issue.

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

Compare what was built against the specification. The goal is to catch drift in both
directions — work that was planned but not done, and work that was done but not planned.

**Check each acceptance criterion.** Walk through the acceptance criteria from the
specification one by one. For each criterion, identify the specific code change that
satisfies it. If a criterion has no corresponding implementation, flag it — either the
work is incomplete or the criterion was intentionally descoped (which should appear in
the implementation notes).

**Check for unplanned changes.** Review the changed files for modifications that don't
trace back to any acceptance criterion. Unplanned changes aren't automatically wrong —
sometimes implementation reveals necessary adjacent work. But they should be
acknowledged and justified in the implementation notes, not silent.

**Check non-goals weren't violated.** Review the specification's non-goals list. If the
implementation touched areas that were explicitly scoped out, flag it.

### 3. Test Quality

The specification's test strategy defined what should be tested — happy paths, failure
paths, and edge cases. The reviewer verifies that the tests actually deliver on that
strategy, not just that they exist and pass.

This is where the separation of concerns matters most. The agent that wrote the tests
has an inherent bias toward believing they're correct. An independent reviewer can
evaluate whether the tests verify real behavior or just confirm that code runs.

**Map tests to the test strategy.** For each scenario in the specification's test
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

**Check edge cases.** Verify each boundary condition from the test strategy has a
corresponding test. If implementation notes documented new edge cases discovered during
development, check whether tests were added for those too.

### 4. Simplification

Run the `/simplify` skill on the changed files and document its findings. The reviewer
records what simplify identifies — it does not apply fixes.

Document each finding from simplify with:
- The file and area affected
- What simplify identified (duplication, unnecessary complexity, over-engineering)
- Whether it's minor (style/preference) or substantive (affects maintainability)

Simplification findings are not blocking unless they indicate a structural problem
that would make the code difficult to maintain or extend.

---

## Review Output

The review produces a `review-checklist` note on the MCP item. Structure the note
around findings, not process.

### Verdict

Every review must end with a clear verdict:

- **Pass** — all acceptance criteria met, tests pass and have substance, no blocking
  issues. The item can advance.
- **Fail — blocking issues** — test failures, missing acceptance criteria, or critical
  gaps in test coverage. The item must go back for fixes before it can advance. List
  every blocking issue.
- **Pass with observations** — no blocking issues, but simplification findings or
  minor test quality concerns worth addressing. The item can advance, but the
  observations should be tracked for follow-up.

### Findings Format

For each finding, state:
- **What was expected** (from the specification or test strategy)
- **What was found** (in the code or test output)
- **Severity** (blocking or observation)

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
