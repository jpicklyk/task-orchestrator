---
name: review-quality
description: Review quality framework for the work-to-review transition gate. Guides verification of build state, plan alignment and test quality before marking implementation complete. Referenced by schema guidance fields during review-phase note filling. Use when filling review-checklist notes or when asked to review completed implementation work.
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
evaluate. The reviewer reads and reports — it never fixes. If issues are found,
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

1. **Load the item's notes** - `notes_read` `list` (`itemId`) names them, and `notes_read` `get`
   (`itemId`, `key`) returns a body. The planning notes are the item's queue-role notes
   (`items_read` `schema` lists them), e.g. `feature-summary`, `task-scope`, `diagnosis`,
   `specification`, plus `amendments` when present; `implementation-notes` is the work-role note.
2. **Read the changed files** - take them from `git -C <worktree> diff --name-only <base>..HEAD`
   filtered to the owned files (rule `review-scoping`); `implementation-notes` carries no file list
   (rule `closure-notes` rule 3). Review the actual code, not just summaries.
3. **Read the recorded build state** - per Area 1, take the command and SHA the orchestrator
   recorded, never a count or breakdown. Do not run the suite, and do not assume tests pass because
   the implementation agent said they did.
4. **For items carrying the `needs-test-author` trait** - load the trait's `test-plan`
   and `test-manifest` notes with `notes_read` `get` (`itemId`, `key`), which carries the test author's own commit SHA range field, and
   obtain the orchestrator-provided per-child SHA table (Pre-SHA/Post-SHA/Test-Pre-SHA/
   Test-Post-SHA) plus any declared orchestrator fixture-repair commit SHAs from the review
   handoff, for cross-checking. A trait-bearing item with no `test-plan` note is a
   blocking issue on its own - do not proceed to the Area 3 independence verification
   until the note exists.

If a planning note the schema requires or the implementation notes are missing, the review
cannot proceed. Report this as a blocking issue.

Before reporting any file or artifact as missing, or any behavior as broken, verify with
a direct check — Read the exact expected path, an exact-path Glob, or a reproduction —
rather than inferring absence from one plausible directory or from a prior bug's pattern.
Two review false-positives reached verdicts this way before being caught downstream.

---

## Review Areas

These three areas form the minimum review. Each one catches a different class of failure.
If the review surfaces additional concerns, include them — this is a floor, not a ceiling.

### 1. Build State Verification

Establish the actual state of the tests before anything else. Everything downstream depends
on it — but the reviewer does not run the suite. The whole suite is the orchestrator's, run
once per wave (dispatch contract, "Who runs gradle"); a reviewer re-running it adds minutes of
Gradle per item and no information.

Read the recorded build state: the Review scoping slot's "Build state reviewers rely on"
line, the orchestrator's `session-tracking` `Verified:` line, or the `test-manifest` VERIFY line
(which cites scoped classes, not a suite count). Record the `Build state:` line in the format the
`review-checklist` guidance gives.

**If no build state is recorded, or it is for an older SHA than the diff under review:**
that is a blocking finding - say so and stop; do not run the suite to fill the gap.

**If a recorded failure is listed:** document every failure (test name, assertion message, test
file); do not fix or speculate about it. A failing test is blocking.

**Re-runs:** rule `test-author` section 5 rules 8 and 11 - read the recorded results; re-run only
one specific doubted result, scoped (`--tests`), and record what you ran.

**If the recorded state is green and current:** record it and move on.

### 2. Plan Alignment

Compare what was built against the item's planning notes (the queue-role notes, e.g.
feature-summary / task-scope / diagnosis / specification, plus amendments when present). The goal is to catch drift in both directions - work that was planned but
not done, and work that was done but not planned.

**Check each acceptance criterion.** Walk through the acceptance criteria from the
planning notes one by one. For each criterion, identify the specific code change that
satisfies it. If a criterion has no corresponding implementation, flag it — either the
work is incomplete or the criterion was intentionally descoped (which should appear in
the implementation notes).

**Check for unplanned changes.** Review the changed files for modifications that don't
trace back to any acceptance criterion. Unplanned changes aren't automatically wrong —
sometimes implementation reveals necessary adjacent work. But they should be
acknowledged and justified in the implementation notes, not silent.

**Check non-goals weren't violated.** Review the planning notes' non-goals list. If the
implementation touched areas that were explicitly scoped out, flag it.

**Check semantic-claim citations.** Rule `review-cited-claims` (rules 1-3): open every cited
line, and treat an uncited claim as an observation and a contradicted claim as blocking.

### 3. Test Quality

The planning notes' test strategy defined what should be tested — happy paths, failure
paths, and edge cases. The reviewer verifies that the tests actually deliver on that
strategy, not just that they exist and pass.

This is where the separation of concerns matters most. The agent that wrote the tests
has an inherent bias toward believing they're correct. An independent reviewer can
evaluate whether the tests verify real behavior or just confirm that code runs.

**Map tests to the test strategy.** For each scenario in the planning notes' test
strategy, identify the corresponding test. Missing coverage is a gap to report.

**Evaluate test substance.** The patterns that produce green results without catching real bugs
are rules `forbidden-test-patterns` and `test-assertion-vacuity`; read the test bodies against
them. Coverage check kept here: **happy-path-only coverage**. If the test strategy called for
failure paths, those tests need to exist and verify the failure behavior (right exception type,
right error message, right fallback).

**Check edge cases.** Verify each boundary condition from the test strategy has a
corresponding test. If implementation notes documented new edge cases discovered during
development, check whether tests were added for those too.

**Independence verification (items with the `needs-test-author` trait).** Follow the
`test-independence-audit` guidance (`items_read` `schema`, `fields` `["guidance"]`): separation held,
manifest maps to spec scenarios, oracle spot-check, arbitration record, forbidden-pattern
declaration true. Verify it before trusting any other finding in this area. Addendum:

- Declared orchestrator fixture repairs are judged by rules `test-file-ownership` rule 4 and
  `review-scoping` rule 3; verify they touched only construction or setup code, never assertions.
- An oracle that matches the implementation but not its cited source is blocking.
- Orchestrator red-test rulings are `A<n>` lines in `orchestrator-confirmation` (rule `arbitration`).
- A false forbidden-pattern declaration carries the same weight as `not-independent`.

---

## Review Output

The review produces a `review-checklist` note on the MCP item. Structure the note
around findings, not process.

### Verdict

Every review must end with a clear verdict:

- **Pass** — all acceptance criteria met, tests pass and have substance, no blocking
  issues. The item can advance. An independence-verification result of
  `independent-degraded (temporal-only)` maps to **Pass** — record it in the audit note
  as the observed mode, not as a finding; it is the sanctioned outcome for Direct-tier
  single-actor runs, not a degradation to flag.
- **Fail — blocking issues** — test failures, missing acceptance criteria, critical
  gaps in test coverage, a nullability/redaction/ownership/honest-limit claim that its
  cited line contradicts, or (for items with the `needs-test-author` trait) a
  `not-independent` independence-verification result, an unexplained implementer edit
  to test files, or an undeclared or falsely declared forbidden test pattern. These fail
  the item even when the test suite is green — a compromised separation or a false
  manifest makes a passing suite untrustworthy. The item must go back for fixes before
  it can advance. List every blocking issue.
- **Pass with observations** — no blocking issues, but minor findings or
  test quality concerns worth addressing. The item can advance; the orchestrator
  disposes each observation at child close as `fixed <sha>` (only a concrete failure in
  behaviour the item added or changed, per run-wave `post-run.md` Step 5 "Observation
  triage"), `follow-up <item-id>` or `accepted: <reason>`.

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

The line format and where the register lives are in the review note's findings-register guidance
(`items_read` `schema`, `fields` `["guidance"]`); sibling review notes cite the finding id.

Be specific. "Tests could be better" is not actionable. "Test `testCreateItem` asserts
only that the result is not null — it should verify the item's title and status match
the input parameters" is actionable.

### Gate Enforcement

Rule `protocol.read-only-agent`: the reviewer does not advance the item, fills only the review
notes it owns and reports the verdict. The orchestrator reads the verdict and either advances the
item (pass or pass-with-observations) or sends it back with the blocking list (fail).
