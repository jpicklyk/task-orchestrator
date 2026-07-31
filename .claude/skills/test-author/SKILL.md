---
name: test-author
description: Test authoring framework for items carrying the needs-test-author trait. Defines scenario derivation from acceptance criteria, the oracle-derivation and blindness rules that keep test authorship independent of implementation, the adversarial probe catalog, forbidden patterns, and the test-plan/test-manifest note formats. Referenced by trait note guidance during queue-phase test-plan and work-phase test-manifest filling. Use when filling test-plan or test-manifest notes, or when asked to author or review tests independently of an implementation.
user-invocable: false
---

# Test Authoring Framework

This skill defines how test authorship is separated from implementation. It exists because an
agent that writes both the code and its tests has no adversary in the loop — the tests confirm
what was built, not what was intended. The trend record backing this trait names the recurring
cost directly: vacuous positive assertions with `|| isEmpty()` escapes, a test oracle computed
from the implementation's own formula, `assumeTrue` wrapped around three real production bugs so
they never turned the suite red, and coverage claimed in notes that didn't exist in the test
tree. `review-quality` already names the bias — "the agent that wrote the tests has an inherent
bias toward believing they're correct" — this skill is the structural fix upstream of review:
an independent authorship step, gated at the point where an oracle can still be frozen before
implementation exists to copy from.

Everything below applies whether the test author is a separate dispatch or, in a degraded mode,
the same agent operating at a different, declared point in time. The separation is the point —
follow it exactly even when it feels redundant with work you can already see.

---

## 1. When This Applies — The Two Seats

The trait `needs-test-author` puts two seats on an item, occupied at different phases:

- **The plan author**, at queue phase, fills `test-plan` — scenarios, oracle sources, and probes,
  frozen before any implementation exists. This is the structural lever: an oracle written before
  code exists cannot be derived from that code, no matter who writes it later.
- **The test author**, at work phase, fills `test-manifest` — the actual test files, written
  after implementation has landed so they compile against the real signatures, but without
  reading the implementation's reasoning or its own tests.

**Same-agent exception (Direct tier, temporal-only):** for a single-item Direct-tier dispatch
there is no second agent to send the work to. The same agent may occupy both seats, but only
under a *temporal-only* degraded mode — see §11. The gate still applies: `test-plan` must exist
and be frozen before implementation begins, and the manifest must declare the single-actor mode
explicitly rather than silently reusing the plan author's context. This is a narrower guarantee
than true two-agent separation, and `test-independence-audit` records it as such
(`independent-degraded`), never as `independent`.

Outside Direct tier — Delegated and Parallel — the two seats are two dispatches. Never collapse
them to save a round-trip; the whole value of this trait is in the second agent's blindness.

---

## 2. Deriving Numbered Scenarios From Acceptance Criteria

Start from the acceptance criteria in the item's planning note (`task-scope`, `feature-summary`,
or `diagnosis`) or the queue-phase spec these criteria live in. Every criterion maps to at least
one scenario; a criterion with zero scenarios is a coverage gap, not an implicit pass.

**Partition each criterion into happy / failure / edge**, mirroring the `spec-quality` Test
Strategy discipline this note composes with — `test-plan` gives each scenario an oracle and a
probe list; `task-scope`'s Test Strategy section should already have named the scenarios
themselves, so this step should feel like formalizing, not inventing from scratch. If it feels
like inventing from scratch, the queue-phase spec's test strategy was too thin — flag it rather
than filling the gap silently.

- **Happy** — the criterion's primary intended behavior under normal input.
- **Failure** — what happens when the criterion's preconditions are violated (invalid input,
  missing dependency, disallowed state transition).
- **Edge** — boundary values and structural extremes: empty vs. absent vs. null, exact limit
  values, duplicate entries, ordering sensitivity, maximum depth or size.

A criterion that only yields a happy-path scenario has not been fully partitioned — go back and
ask what could violate it or sit at its boundary before treating it as covered.

**Stable S-ids.** Number scenarios `S1, S2, S3…` in the `test-plan` note and never renumber once
written test code refers to them — the `test-manifest`'s S-id→test mapping is only auditable if
the ids are stable across the plan/manifest boundary. If a scenario is dropped, mark it
`S4 — dropped: <reason>` rather than closing the numbering gap.

---

## 3. Oracle-Derivation Rule

Every scenario's expected result must have a stated **oracle source**: the spec clause, the
documented algorithm, an external reference (an RFC, a library's own documented contract), or an
independently-run computation. The oracle is never "what the code returns" and never a value
lifted uninspected from the ticket's worked example.

**Never encode an illustration as the oracle.** A worked example in a spec or ticket exists to
build intuition, not to be trusted as ground truth — illustrative examples are frequently
approximate, off-by-one, or simplified for readability. If a scenario's only available expected
value is an illustration, recompute it independently from the stated rule before writing the
assertion, and cite the rule, not the illustration, as the oracle source.

**Never read the implementation to decide correctness.** This is the rule the whole trait exists
to enforce. If you find yourself opening the file under test to see what it currently returns and
then asserting that value, stop — you have just converted the test into a change-detector for
whatever the implementation happens to do today, including its bugs. This is the exact failure
pattern in the trend record: an oracle computed from the implementation's own formula reproduces
the formula, not the requirement, so it stays green even when the formula is wrong.

**Concretely, an oracle citation looks like:**
- `"per task-scope §Test Strategy S3: duplicate dependency edges are rejected with
  DUPLICATE_EDGE"` — a spec clause.
- `"per RFC 7396 §2: a merge-patch null removes the key"` — an external reference.
- `"computed independently: SHA-256 of the canonical byte sequence, verified against a second
  implementation (Python hashlib) run outside the codebase"` — an independent computation.

An oracle citation that instead reads `"matches current behavior"` or `"see implementation"` is
not an oracle — it is a confession that this rule was skipped, and should block the note from
being accepted as complete.

---

## 4. Blindness Rule

The test author's independence is only real if its inputs are actually restricted. This section
is the enforceable half of that — the queue-phase oracle freeze (§3) is the other half.

**May read:**
- Queue-phase specification notes (`task-scope`, `feature-summary`, `diagnosis`) and the
  `test-plan` note itself.
- Public signatures — function/class declarations, interface contracts, tool `parameterSchema`s.
- Domain models and other declared data shapes.
- Project documentation (`current/docs/`, `CLAUDE.md`).
- Existing test conventions in the codebase (naming, fixture setup, assertion style) — for
  consistency of form, not for expected values.
- The implementer's changed-file **names** (from `git diff --name-only` or the implementation
  notes' file list) — needed to know where to write tests, not what they contain.

**Must not read:**
- Diff content of the implementer's changes.
- The implementer's own tests, if any exist (e.g., a probe or smoke test the implementer left
  behind).
- `implementation-notes` or `session-tracking` bodies.

**If the public signature contract doesn't compile against the test as planned** — a parameter
was renamed, a return type changed shape, a method the plan assumed exists doesn't — do not
guess the corrected shape from context clues in the diff. Escalate: this is either a
plan-vs-implementation drift that needs arbitration (§8) or a genuine signature question the
implementer must answer directly, not something to resolve by reading the diff to see what
changed.

---

## 5. Red-First Check

Where red is achievable before the fix exists, the test must actually observe it.

**Bug-fix regression tests**: write the test from the `diagnosis` note's reproduction steps and
confirm it fails against the pre-fix code — actually run it red, don't assume the reproduction
description implies a failing assertion. A regression test that was never seen red proves nothing
about whether it would have caught the bug; this is exactly how `assumeTrue` wrapping neutralizes
a would-be regression test without anyone noticing, because the wrapped test never fails at all,
pre-fix or post-fix.

**Feature suites (test-after)**: since implementation already exists by the time the test author
writes code, true red-before-fix isn't available. Substitute a mandatory per-scenario line in the
manifest: *"what specific wrong behavior would this assertion catch?"* — name a plausible bug
this test would fail against (wrong value, wrong exception, silently-accepted invalid input). If
you cannot state one, the assertion is not adding coverage; strengthen it or mark the scenario
`not-covered: <reason>` rather than writing an assertion that would pass against almost anything.
"Nothing specific" for this line is a blocking review finding under `review-quality`.

---

## 6. Adversarial Probe Catalog

Beyond the scenarios derived from acceptance criteria, run the applicable probes below against
the feature's actual input surface. Record every probe attempted in `test-manifest` — including
the ones that found nothing. A probe list with only findings looks like it was written after the
fact to justify existing tests; a probe list that also records clean results is evidence the
surface was actually exercised.

- **Boundary / suffix** — values at, one below, and one above a stated limit; strings that are
  prefixes or suffixes of a recognized token rather than exact matches.
- **Alternate separators** — the same logical path or identifier expressed with a different
  delimiter, casing, or encoding than the primary code path expects (e.g. `\` vs `/`, `..%2f`).
- **Encoded / UNC forms** — percent-encoded, double-encoded, or UNC-style (`\\?\`, `\\host\share`)
  variants of path or identifier input, where the surface accepts path-like or URI-like input.
- **Mixed case** — case variants of identifiers or keys where the underlying store or comparison
  may be case-sensitive in one layer and not another.
- **Empty vs. absent vs. null** — three distinct states that are easy to collapse into one
  code path by accident; each deserves its own scenario if the surface can distinguish them.
- **Duplicates / ordering** — repeated entries in a collection input, and whether processing
  order is assumed but not guaranteed.
- **Replay / idempotency** — repeating the same write or transition twice; confirm the documented
  idempotency contract (or lack of one) holds.

Not every probe applies to every surface — a pure in-memory computation has no path-encoding
surface. Record the ones that don't apply as `N/A: <reason>` rather than omitting them silently,
so a reviewer can tell "not applicable" apart from "forgotten."

---

## 7. Forbidden Patterns

These patterns produce a green suite without verifying real behavior. Full before/after examples
for each, in Kotlin/JUnit5, are in `references/forbidden-patterns.md`. Treat this list as gate
criteria for `test-manifest`'s forbidden-pattern declaration (§10) — every instance found in the
authored tests must be declared with a justification, and an undeclared instance found in review
is a blocking finding regardless of intent.

- **`assumeTrue` on non-platform conditions** — `assumeTrue` exists to skip a test on an
  environment it cannot run in (OS, missing external service). Using it to skip a test when a
  *behavioral* precondition isn't met turns a would-be failure into a silent skip. Three real
  production bugs shipped this way in this codebase's history.
- **Disjunctive escapes** — `result == expected || result.isEmpty()`, or any assertion with an
  `||` branch that accepts a "didn't do anything" outcome as equally valid to the intended one.
  This passes whether the feature works or does nothing at all.
- **Oracle-from-implementation** — the value asserted against was read from the implementation
  under test rather than derived per §3. Detectable by asking: could this oracle have been
  written before the implementation existed?
- **Assert-not-null-only** — `assertNotNull(result)` (or `result != null`, `list.isNotEmpty()`)
  standing in for a check of the actual value, size, or contents.
- **Mock-order-only verification** — a test where every dependency is mocked and the only
  assertion is that calls happened in some order, with no assertion on the unit's actual output
  or state change.
- **Catch-swallowed assertions** — an assertion inside a `try` whose `catch` block logs or
  ignores the exception instead of failing the test, so an assertion failure and a caught
  exception both read as a pass.

---

## 8. Ambiguity Arbitration

When a scenario's expected result is genuinely unclear — the spec is silent, two spec clauses
conflict, or the public signature doesn't match what the plan assumed — the test author does not
resolve it by reading the implementation to see what was built. That is exactly the shortcut §3
and §4 exist to close off.

**Escalate instead.** Record the ambiguity in `test-manifest` under the arbitration record: what
was ambiguous, what the plan said, what would need to be true for each candidate resolution.
Escalation goes to the orchestrator (Parallel/Delegated tier) or the user (Direct tier) — never
resolved unilaterally by the same agent that hit the ambiguity.

**If an ambiguity must be resolved by consulting the implementation** (rare, and only when
escalation is not available and the item cannot proceed otherwise), the resulting scenario is
`oracle-degraded` — mark it explicitly in the manifest's S-id mapping. `test-independence-audit`
must surface every `oracle-degraded` scenario individually; it cannot be waved through as part of
an overall "independent" verdict.

This is distinct from implementation-vs-test arbitration proper (red author-test → is the
implementation wrong, or the test wrong, or the spec wrong) — that triage is orchestrator-owned
per the parent feature's arbitration protocol, not something the test author decides. The test
author's job when a written test comes back red is to report it (§9), not to guess which side is
at fault.

---

## 9. On a Failing Test

If a test you authored fails against the implementation as it stands, that is a finding, not a
problem to make go away. Report it — in the manifest and, if the test author is a live dispatch
reporting to an orchestrator, in the return message — with the scenario id, the assertion, the
observed value, and the oracle citation.

**Never weaken the assertion, skip the test, or wrap it in `assumeTrue` to unblock a wave or a
merge.** Every one of the forbidden patterns in §7 is, in practice, a rationalized version of
"this test is inconvenient right now." A red test authored independently, against a frozen
oracle, is the entire point of this trait — silencing it defeats the purpose as completely as
never having written it. Arbitration (§8, orchestrator-owned) decides whether the implementation,
the test, or the spec is wrong; the test author's job stops at an accurate, specific report.

---

## 10. The Test Manifest

`test-manifest` (work phase, required) is the test author's complete account of what was done.
Fill every field — an omitted field reads as "not done," not as "not applicable" (use an explicit
`N/A: <reason>` for genuinely inapplicable fields, matching the probe-catalog convention in §6).

- **Actor id** — the identity that authored the tests, for the independence audit to check
  against the implementer's actor id (or the declared temporal-only mode, §11).
- **Test file paths** — every file created or modified by the test author.
- **Commit SHA range** — the range test authorship spans, so a reviewer can confirm test files
  first appear in this range and not earlier, in the implementer's own commits.
- **S-id → test mapping** — for every scenario in `test-plan`: `covered` (name the test method)
  or `not-covered: <reason>`.
- **Probes executed** — every probe from §6 attempted, with its result, including no-finding
  probes.
- **Forbidden-pattern declaration** — every use of `assumeTrue`, a disjunctive assertion, or any
  other §7 pattern present in the authored tests, each with a justification. An empty declaration
  asserts none were used — it is itself a claim the reviewer checks.
- **Arbitration record** — every ambiguity raised per §8, its resolution, and any `oracle-degraded`
  markers.
- **Implementer modifications** — if the implementer touched test files after the author's
  commits (e.g. a fixture repair), record what changed and why; changes to *expectations* rather
  than construction should have triggered a re-dispatch to the author, not a silent implementer
  edit — note if that didn't happen.

---

## 11. Degraded Modes

**Direct-tier temporal-only mode** (§1) is the only sanctioned degradation. It applies only when
there is no second agent available to dispatch. Under this mode:

- `test-plan` still gates work entry — the oracle freeze happens before implementation, exactly
  as in the two-agent case. This is the part of the guarantee that survives.
- Red-first (§5) still applies in full for bug-fix reproductions.
- `test-manifest` declares the single-actor mode explicitly (`actor: <id> (temporal-only,
  same agent as implementer)`) rather than presenting as if a second agent were involved.
- `test-independence-audit` records the verdict as `independent-degraded`, never `independent`.
  A temporal-only separation is real but weaker than two-agent separation — it defeats
  implementation-derived oracles (the oracle was frozen before code existed) but not
  implementation-derived *test code*, since the same agent that reads the plan later writes both
  the implementation and the tests informed by whatever it just built.

No other degraded mode is sanctioned. If a Delegated- or Parallel-tier item finds itself unable
to actually separate the two dispatches (e.g. capacity pressure), that is a scheduling problem to
raise with the orchestrator — not a reason to fall back to same-agent authorship without
declaring it, and not a reason to skip declaring the degradation if it happens anyway.
