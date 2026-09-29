# test-author

Test authorship for a trait-gated item is separated from implementation because an agent that
writes both the code and its tests has no adversary in the loop: the tests confirm what was built,
not what was intended. This rule defines the two-seat split, the oracle-freeze and blindness rules
that keep authorship independent, the scenario-labelling discipline, and the test-manifest record.

## 1. Two seats, two phases

- **The plan seat**, before implementation starts, writes the scenarios, their oracle sources, and
  a probe list, frozen before any implementation exists. This is the structural lever: an oracle
  written before code exists cannot be derived from that code, no matter who writes it later.
- **The test-author seat**, after implementation lands, writes the actual test files -- compiling
  against the real, now-existing signatures, but without reading the implementation's reasoning or
  its own tests.
- **Single-item exception:** when there is genuinely no second agent to dispatch, the same agent
  may occupy both seats, but only under a declared, temporal-only degraded mode: the plan must
  still be frozen before implementation begins, and the record must say explicitly that separation
  was temporal only, never presented as if a second agent were involved.
- Wherever a second agent IS available, never collapse the two seats to save a round-trip -- the
  whole value of this rule is the second agent's blindness.

## 2. Scenarios and labelling

- Derive numbered scenarios from the item's acceptance criteria, partitioned into happy / failure /
  edge cases. A criterion that yields only a happy-path scenario has not been fully partitioned.
- Number scenarios stably (S1, S2, ...) and never renumber once test code references them; a
  dropped scenario is marked dropped, not deleted from the numbering.
- Label every scenario **EXISTING-SURFACE** (every declaration it touches predates the fix; a
  plain revert of the fix yields real behavioral red) or **NEW-SURFACE** (the scenario binds to a
  declaration the fix introduces; a plain revert produces only a compile failure, which proves
  nothing about behavior). A NEW-SURFACE scenario must carry a narrowest-revert recipe (typically:
  keep the new type or parameter, revert only its call sites) or, where no such revert can work, an
  explicit substitute verification named in the plan, not improvised later.
- The plan seat assigns these labels, not the test author -- the author is blind to the
  implementation by design and cannot tell which surfaces are new.

## 3. Oracle-derivation rule

- Every scenario's expected result has a stated oracle source: a spec clause, a documented
  algorithm, an external reference, or an independently run computation. Never "what the code
  returns," and never a worked example copied uninspected -- recompute an illustrative example
  independently and cite the rule, not the illustration.
- Never open the implementation to decide correctness. If you catch yourself checking what the
  code currently returns and then asserting that value, stop -- the test has become a
  change-detector for whatever the code does today, bugs included.
- A NEW-SURFACE label does not relax this. Derive the expected result from the spec clause that
  motivated the new surface, or escalate; a new surface with no oracle available outside the
  implementation is a spec gap to raise, not a license to read the implementation.

## 4. Blindness -- a capability boundary, not a reading discipline

This holds only if the author's inputs are actually restricted, mechanically, not by asking the
author to read narrowly (no reading tool has a declarations-only mode; any of them will put a
function body in front of an author trying, in good faith, to resolve a signature).

- **Declarations are supplied, not looked up.** Every public declaration the author needs --
  constructors with full parameter lists and defaults, method signatures, constants, enum values,
  and any pre-existing doc comment carrying an oracle or invariant (including domain-validation
  rules fixtures must satisfy) -- is pasted inline and verbatim into the dispatch. A missing
  declaration is an orchestrator error: say so and stop; never reconstruct it.
- **Hard ban on implementation sources.** The author does not open any implementation file with
  any tool for any reason, including "just the signature" -- the tool decides what comes back, not
  the intent behind the call. The same ban covers viewing the implementer's diff, commit, or log
  on this branch. Test files, fixtures, and harnesses stay fully readable throughout.
- **Explicit, filtered queries only.** Every note/context lookup is restricted to the frozen
  planning-phase keys (typically the scope/diagnosis/test-plan equivalents). An unfiltered lookup
  that could return implementation notes is itself a breach, whether or not those notes were read.
- **A missing or non-compiling declaration means stop and ask** -- never derive the corrected shape
  from context, from a compiler error that quotes surrounding source, or from a diff. One narrow
  exception: an ambiguity resolvable from public, non-implementation evidence (a tool's declared
  parameter schema, existing test harnesses, docs) may be self-resolved if the record states the
  evidence used, for a reviewer to verify.
- **Self-disclosure on breach.** A breach, deliberate or accidental, means: stop immediately,
  commit nothing, delete any draft written after it, and disclose exactly what was read -- in the
  return report and in the manifest. A disclosed breach costs a re-dispatch; an undisclosed one
  costs the item's independence verdict entirely.

## 5. Red-first

Where behavioral red is achievable before the fix exists, the test must actually observe it.
EXISTING-SURFACE scenarios reach it by a plain revert. NEW-SURFACE scenarios need the plan's
narrowest-revert recipe, or the substitute verification it named. For bug-fix regressions, run the
test against the pre-fix code and confirm it actually fails -- never assume a reproduction
description implies a failing assertion.

In a worktree shared with other seats, never run `git stash`, `git checkout -- <path>`,
`git restore`, `git reset`, or any other tree-wide write: they discard or hide another seat's
uncommitted edits. The revert that observes red runs only in a scratch copy -- `git worktree add
--detach <unique-tmp> <commit>` or a unique throwaway directory -- with the authored test files
copied in, and the scratch copy is removed afterwards (`git worktree remove --force <unique-tmp>`).
Record the scratch path and the commit it was made from in `test-manifest`. If no scratch copy is
possible, do not revert at all: record `red evidence: orchestrator-run` with the recipe.

## 6. Adversarial probes

Beyond scenario-derived tests, run the probes applicable to the surface under test: boundary and
suffix values around stated limits; alternate separators/casing/encoding for the same logical
input; encoded or UNC-style path/identifier variants; case sensitivity mismatches across layers;
empty vs. absent vs. null as three distinct states; duplicate entries and order sensitivity;
replay/idempotency of the same write or transition. Record every probe attempted, including ones
that found nothing, and mark inapplicable probes explicitly rather than omitting them.

## 7. Scope and forbidden constructs

- Create only the new test files your dispatch assigns you, plus any declared edit to an existing
  test file named down to the exact edit. Never implementation code, never docs, never another
  item's files, never an undeclared edit to a shared harness. An existing test that looks wrong but
  isn't declared for you is reported, not edited.
- Forbidden without a stated justification: skip-style constructs guarding non-platform
  conditions; disjunctive assertions that accept "did nothing" as an equally valid outcome; an
  oracle read from the implementation instead of derived independently; not-null-only assertions
  standing in for a real value check; mock-order-only verification with no assertion on actual
  output or state; assertions placed where a surrounding error handler can swallow their failure.
- Before writing a fixture, satisfy the domain type's validation rules by construction -- derive
  dependent fields from each other rather than setting them independently. If a scenario cannot be
  constructed without violating an invariant, escalate; never weaken the fixture or bypass
  validation through a backdoor.
- For every negative/absence assertion, name the fixture condition that would make it fail without
  the fix -- if none exists, the assertion cannot fail and the fixture needs fixing, not the
  assertion. Where a scenario depends on exact protocol shape (repeated headers, casing,
  whitespace, encoding), prove the test harness actually preserves that shape rather than
  normalizing it away.

## 8. Ambiguity arbitration

When a scenario's expected result is genuinely unclear, escalate rather than resolving it by
reading the implementation -- that is exactly the shortcut the blindness rule exists to close.
Record the ambiguity, what was unclear, and what each candidate resolution would require. The one
carve-out is the same public-evidence self-resolution named in section 4. If an ambiguity truly
cannot be escalated and must be resolved by consulting the implementation, mark the resulting
scenario explicitly as oracle-degraded rather than folding it into an overall "independent"
verdict.

## 9. On a failing test

A test that fails against the implementation as it stands is a finding, not a problem to make go
away. Report it with the scenario id, the assertion, the observed value, and the oracle citation.
Never weaken the assertion, skip it, or wrap it to unblock a merge -- a red test authored
independently against a frozen oracle is the entire point; silencing it defeats the purpose as
completely as never having written it. Whether the implementation, the test, or the spec is wrong
is decided elsewhere, not by the test author.

## 10. The test-manifest record

Record, per item: the authoring actor's identity; every test file created or modified; the commit
range test authorship spans; a scenario-id-to-test mapping (covered, naming the test, or
not-covered with a reason); every probe attempted and its result; every forbidden-construct
instance present, each with a justification (an empty declaration is itself a checked claim); the
domain invariant each fixture satisfies by construction; the red-proof shape actually obtained for
each NEW-SURFACE scenario; the arbitration record for every ambiguity raised; and any
implementer-made change to a test file after the author's commits, with what changed and why. An
omitted field reads as "not done," not as "not applicable" -- use an explicit not-applicable marker
with a reason for genuinely inapplicable fields.

## claude:

- Orchestration tool names: `query_notes(operation="list", itemId=..., keys=[...])` for the
  frozen planning-phase notes (a `keys=` filter is mandatory on every call); `SendMessage` to the
  orchestrator (or ask the user in a single-agent run) for stop-and-ask escalation;
  `manage_notes(operation="upsert", ...)` to write `test-manifest`; never call `advance_item` or
  `manage_items`. Return line format:
  `<item>: commit <sha> | files: <list> | scenarios: <covered>/<total> | compile: EXIT=<n> | missing-declaration: <none or name>`.
