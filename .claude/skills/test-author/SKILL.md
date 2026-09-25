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

**Label every scenario `EXISTING-SURFACE` or `NEW-SURFACE`.** Red-proof is obtained by reverting
the fix and running the item's tests. That works only where the scenario binds to a surface that
already existed: when the fix *introduces* the type, parameter, seam or enum constant a test
references, reverting it produces a **compile** failure rather than a behavioral red. Compile-red
proves the tests reference new code; it does not prove they detect wrong behavior. Across two bug
waves this hit 3 of 10 items and then 2 of 5 — and in the second wave the two affected items were
the security fix and the silent-exit fix, the two whose defects mattered most.

- **`EXISTING-SURFACE`** — every declaration the scenario touches exists before the fix. A plain
  revert yields behavioral red; no extra field is needed.
- **`NEW-SURFACE`** — the scenario binds to a declaration the fix introduces. It MUST carry a
  **narrowest-revert recipe**: the smallest revert that still compiles and still exercises the
  behavior. Typically *keep the new type, parameter or enum constant; revert only its call sites*
  — the technique that recovered genuine behavioral red on 2 of 3 such items in bug wave 1.
- **`NEW-SURFACE` with no revert that can yield behavioral red** — say so explicitly and name the
  **substitute verification** that replaces it (e.g. "reviewer reads each test body against the
  implementation and confirms every asserted value traces to its oracle citation"). A substitute
  declared in the plan is evidence; one produced at verification time is an excuse.

The plan author assigns these labels, not the test author: the test author is blind to the
implementation by design (§4) and therefore cannot tell which surfaces are new. Where a dispatch
contract's planning seat returns a `red-proof-shape` field, it carries this same labelling, set
before the author is dispatched. The shape actually obtained is recorded in `test-manifest` (§10).

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

**A `NEW-SURFACE` label (§2) does not relax any of this.** When a scenario binds to a declaration
the fix introduces, the pull toward sourcing the expected value from the new code is strongest —
the surface exists nowhere else yet. It still does not qualify as an oracle. Derive the expected
result from the spec clause that motivated the new surface, or escalate per §8. A new surface with
no oracle available outside the implementation is a spec gap to raise, not a licence to read.

---

## 4. Blindness Rule — A Capability Boundary

The test author's independence is only real if its inputs are actually restricted. This section is
the enforceable half of that — the queue-phase oracle freeze (§3) is the other half.

**Why this is not a reading-discipline rule.** An earlier version of this section told the author
how to read implementation sources *narrowly*: look up the declaration, stop before the body. That
model cannot hold, because no reading tool has a declarations-only mode. `Read` returns a window,
`Grep -A<n>` returns context lines, `sed -n <a>,<b>p` returns a range — every one of them will put
a function body in front of an author who is trying, in good faith, to resolve a signature.
Restraint fails at the tool boundary, so the boundary moves.

**The evidence.** Bug wave 3 (2026-09), item `a3ebd108`, consumed THREE test authors:

1. The first called `query_notes(operation="list")` with no `keys=` filter, received
   `implementation-notes` in the response, and read the implementation files it named — an ingress
   no reading-method rule had contemplated.
2. The second, working under the previous wave's "use Grep with `-A2`, never Read" remedy, widened
   to `sed -n <a>,<b>p` ranges "to catch wrapped signatures" and read the sentinel function's body.
3. The third was handed every public declaration inline in its dispatch prompt and was barred from
   `src/main` by any tool. Zero lookups; 12/12 scenarios plus 7 probes; reviewer verdict
   `independent`.

Authors 1 and 2 both self-disclosed and stopped before committing, so no contaminated artifact was
ever tracked — the self-disclosure protocol worked, twice. What failed was the reading model, at a
price of two burned dispatches. The rules below are the structural replacement.

**They are capability rules, not care rules.** A breach is a breach whether or not anything useful
was seen, and it is disclosed the same way either way.

### 4.1 Declarations are supplied, not looked up

The dispatch prompt — or the **Test author protocol** slot of the wave's dispatch contract — MUST
paste inline and verbatim every public declaration the author needs: types and data-class
constructors with full parameter lists and defaults, function and method signatures, constants,
enum values, and any KDoc that carries an oracle or states an invariant (`validate()` included,
per §7 Fixture invariants). The author writes tests against that block and goes looking for
nothing further.

A declarations block that is missing entirely is an orchestrator error. Say so and stop (§4.4);
do not reconstruct it.

### 4.2 Hard tool ban on implementation sources

Do not open any file under `src/main` with ANY tool — `Read`, `Grep`, `sed`, `cat`, `head`, `Glob`
preview, an editor, or any shell command whose output includes file content. The ban attaches to
the file, not to the intent: "I only wanted the signature" does not make the call permitted,
because the tool decides what comes back, not you.

Banned for the same reason: `git diff`, `git show`, `git log -p` and any other view of the
implementer's changes on this branch.

`src/test` stays fully readable. Existing tests, fixtures and harnesses are how you match the
codebase's conventions — reading them is expected, not merely tolerated.

### 4.3 `keys=` is mandatory on every `query_notes` call

Every `query_notes` call carries an explicit `keys=` filter restricted to the item's queue-phase
keys — typically `["task-scope", "diagnosis", "test-plan"]`. An unfiltered `operation="list"`
returns `implementation-notes` and `session-tracking` in the same response and **is itself a
breach**, whether or not their bodies were read. `includeBody=true` with a `keys=` filter is fine;
`includeBody=true` without one is the exact call that burned wave 3's first author.

The same holds for `query_notes(operation="search")`: scope it to the item, and never search for
terms that would rank implementation prose highly.

### 4.4 A missing declaration means stop and ask

If a declaration you need is absent from the supplied block — or what was supplied does not
compile against the test as planned, because a parameter was renamed, a return type reshaped, or a
method the plan assumed exists does not — do **not** derive the corrected shape from context, from
a compiler error that quotes surrounding source, or from the diff.

Send the question back: `SendMessage` to the orchestrator (Parallel/Delegated tier), or ask the
user (Direct tier), naming the exact declaration you need. Asking costs one round-trip; the lookup
costs the dispatch. This is the escalation path of §8, and a plan-vs-implementation drift found
this way is a finding worth recording, not an inconvenience to route around.

### 4.5 What the author may read

- Queue-phase specification notes (`task-scope`, `feature-summary`, `diagnosis`) and `test-plan`,
  fetched with a `keys=` filter per §4.3.
- The declarations block supplied by the dispatch (§4.1).
- Project documentation (`current/docs/`, `CLAUDE.md`) and any external reference cited as an
  oracle.
- Everything under `src/test` — conventions, fixtures, harnesses, existing assertions.
- The implementer's changed-file **names** (`git diff --name-only`, or the File ownership rows of
  the dispatch contract) — enough to know where tests belong, not what the files contain.

Never: any file under `src/main`; any diff, commit or patch content; the implementer's own tests
where the dispatch identifies them as such; `implementation-notes`; `session-tracking`.

### 4.6 Self-disclosure on breach — unchanged

If you breach any rule above, deliberately or by accident: stop immediately, commit nothing, and
report exactly what was read and when — in your return message and in `test-manifest`'s
arbitration record. Delete any draft written after the breach and say that you did.

This protocol is the part of the old model that worked, three times across two waves, and it is
unchanged. A disclosed breach costs a re-dispatch. An undisclosed one costs the trait: every later
`independent` verdict on the item becomes unverifiable.

---

## 5. Red-First Check

Where red is achievable before the fix exists, the test must actually observe it.

**Which scenarios "red is achievable" covers is decided by the §2 surface labels, not here.** An
`EXISTING-SURFACE` scenario can reach behavioural red by a plain revert. A `NEW-SURFACE` one
cannot — reverting the fix removes the declaration the test binds to, so the run is compile-red,
which proves only that the test references new code. For those, red-first means executing the
plan's narrowest-revert recipe (keep the new type or parameter, revert its call sites) or, where
the plan declared no revert can work, the substitute verification it named instead. Read §2's
label definitions before deciding a scenario's red evidence; the shape actually obtained is a
`test-manifest` field (§10).

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

### Fixture invariants

Distinct from the patterns above, and their mirror image: a fixture that violates a domain
invariant produces a **red** suite that looks like an implementation bug. The test fails for a
reason unrelated to the behavior under test, and the cost lands on the orchestrator as an
arbitration round-trip, after the author has already returned. The recorded instance: a claim
fixture built with `claimedAt = Instant.now()` alongside a `claimExpiresAt` in the past, which
`WorkItem.validate()` rejects outright.

Before writing a fixture or a fixture helper, read the domain type's `validate()` — from the
declarations supplied per §4.1, not by opening `src/main` — and satisfy it **by construction**:

- **Derive dependent fields from each other, never independently.** For the case above:
  `claimedAt = claimExpiresAt.minus(ttl)`. The same shape applies to any pair the type constrains
  — created/modified, start/end, offset/limit, parent depth vs. child depth.
- **Escalate rather than relax.** If a scenario cannot be constructed without violating an
  invariant, that is a finding about the scenario or about the invariant — raise it per §8. Do not
  weaken the fixture, do not bypass `validate()` by reflection or a test-only backdoor, and do not
  quietly retarget the scenario at whatever state happens to be constructible.
- **Name the invariants respected in `test-manifest`** (§10), so a reviewer can tell a fixture
  that satisfies `validate()` by construction from one that passes by luck.

If the invariant check is not among the supplied declarations, ask for it (§4.4) — it is exactly
the kind of oracle-bearing declaration the dispatch is required to paste.

### Can this assertion fail?

The patterns above are phrased around assertion text. This class hides in the fixture or the
harness instead, so a clean-looking assertion still passes whether or not the fix is present. The
recorded instances, all blind-authored and caught only in review: a "no item created" assertion
after POSTing a non-JSON body that could never create an item; a duplicate-Host case that never
reached its branch because Ktor `testApplication` merges repeated headers into one comma-joined
value; a cycle rejection that asserted `created=0` but not that the reason was "circular".

- **Vacuity check.** For every negative or absence assertion, `test-plan` or `test-manifest` names
  the fixture condition that would make it fail without the fix. If none exists — the fixture
  could never produce the asserted outcome anyway — fix the fixture, not the assertion.
- **Harness-transformation check.** When a scenario depends on raw protocol shape (repeated or
  multi-valued headers, header casing, whitespace, encoding), prove the harness delivers that
  shape unmodified, or drive a real engine over a raw socket. Known case: Ktor `testApplication`
  merges duplicate headers and sends no `Host` by default.
- **Rejection tests assert the reason** — the error code or message — not only that nothing
  happened. "Nothing was created" is also what an unrelated early failure produces.

---

## 8. Ambiguity Arbitration

When a scenario's expected result is genuinely unclear — the spec is silent, two spec clauses
conflict, or the public signature doesn't match what the plan assumed — the test author does not
resolve it by reading the implementation to see what was built. That is exactly the shortcut §3
and §4 exist to close off.

**Escalate instead.** Record the ambiguity in `test-manifest` under the arbitration record: what
was ambiguous, what the plan said, what would need to be true for each candidate resolution.
Escalation goes to the orchestrator (Parallel/Delegated tier) or the user (Direct tier) — never
resolved unilaterally by the same agent that hit the ambiguity. The one carve-out: an ambiguity
resolvable from public non-`src/main` evidence (the tool's parameterSchema, `src/test` harnesses,
docs) may be self-resolved when the arbitration record states the evidence used; the reviewer
verifies it.

**If an ambiguity must be resolved by consulting the implementation** (rare, and only when
escalation is not available and the item cannot proceed otherwise), the resulting scenario is
`oracle-degraded` — mark it explicitly in the manifest's S-id mapping. `test-independence-audit`
must surface every `oracle-degraded` scenario individually; it cannot be waved through as part of
an overall "independent" verdict.

This is distinct from implementation-vs-test arbitration proper (red author-test → is the
implementation wrong, or the test wrong, or the spec wrong) — that triage is orchestrator-owned,
per the `/implement` skill's Step 4b "Arbitration — Red Author-Authored Tests" subsection, not
something the test author decides. The test author's job when a written test comes back red is
to report it (§9), not to guess which side is at fault.

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
- **Invariants respected** — for each fixture, the domain invariant it satisfies by construction
  (§7 Fixture invariants): the `validate()` clause and the derivation used. Record any invariant
  that forced an escalation rather than a fixture.
- **Red-proof shape obtained** — for every scenario the plan labelled `NEW-SURFACE` (§2), which
  shape was actually obtained: `behavioral-red (narrowest revert: <what was reverted>)`, or
  `compile-red only — substitute verification: <what the reviewer does instead>`. Where the revert
  is orchestrator-run rather than author-run, record `red evidence: orchestrator-run` together with
  the recipe you expect it to use. `EXISTING-SURFACE` scenarios need nothing here beyond the §5
  red-first result.
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
- **§4 in temporal-only mode.** §4 is written for a second agent, so state which parts survive
  rather than leaving the section self-contradictory for a single actor:
  - **Relaxed: §4.2's tool ban only.** One actor implements and then writes tests; it has already
    read `src/main` and cannot un-read it. The ban is replaced by the red-first ORDERING of §5 —
    the test is written from the frozen `test-plan` and observed red against pre-fix code BEFORE
    the fix is written, which is the only separation available here. An "I avoided looking"
    claim is not a substitute and must not be recorded as one.
  - **Still in force: §4.1 and §4.3.** Oracles and declarations come from the frozen `test-plan`
    and the planning-seat notes, not from the implementation just written; every `query_notes`
    call still carries `keys=` restricted to the queue-phase keys. `implementation-notes` and
    `session-tracking` stay out of the author pass even though the same actor wrote them.
  - **Still in force: §4.4 and §4.6.** A declaration the frozen plan does not carry is still a
    stop-and-ask (here: amend the plan explicitly, and say so), and any deviation is still
    self-disclosed in the manifest.
  This is what `independent-degraded` names: the oracle boundary held, the capability boundary
  could not.
- `test-independence-audit` records the verdict as `independent-degraded`, never `independent`.
  A temporal-only separation is real but weaker than two-agent separation — it defeats
  implementation-derived oracles (the oracle was frozen before code existed) but not
  implementation-derived *test code*, since the same agent that reads the plan later writes both
  the implementation and the tests informed by whatever it just built.

No other degraded mode is sanctioned. If a Delegated- or Parallel-tier item finds itself unable
to actually separate the two dispatches (e.g. capacity pressure), that is a scheduling problem to
raise with the orchestrator — not a reason to fall back to same-agent authorship without
declaring it, and not a reason to skip declaring the degradation if it happens anyway.
