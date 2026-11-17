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

The served rule `test-author` (`docs_read` `get`, `kind` `rule`, `slug` `test-author`, `rootId`) holds
sections 1-10 below, together with the rules `forbidden-test-patterns` and `test-assertion-vacuity`.
Sections 1-10 here are pointer stubs that keep their numbers (other files cite them) plus the
Claude addendum the rules lack; section 11 is kept in full. Where a stub and a rule differ, the
rule wins.

---

## 1. When This Applies - The Two Seats

Rule `test-author` section 1. Here the single-item exception is a Direct-tier dispatch (section 11).

---

## 2. Deriving Numbered Scenarios From Acceptance Criteria

Rule `test-author` section 2 (happy / failure / edge partition, stable S-ids,
`EXISTING-SURFACE` / `NEW-SURFACE` labels and narrowest-revert recipes). The `declarationModules:`
line is rule `test-author` section 1 (the extractor consumes it: rule `declarations-extractor`); the `red-proof-shape` field is the closing paragraph
of rule `test-author` section 5. The enumerated-surface `sweep:` line is rule `planning-sweeps` rule 2.

---

## 3. Oracle-Derivation Rule

Rule `test-author` section 3. Addendum, three oracle citations that qualify:

- `"per task-scope Test Strategy S3: duplicate dependency edges are rejected with DUPLICATE_EDGE"` - a spec clause.
- `"per RFC 7396 section 2: a merge-patch null removes the key"` - an external reference.
- `"computed independently: SHA-256 of the canonical byte sequence, verified against a second implementation (Python hashlib) run outside the codebase"` - an independent computation.

`"matches current behavior"` and `"see implementation"` are not oracles and block the note.

---

## 4. Blindness Rule - A Capability Boundary

Rule `test-author` section 4 (declarations supplied, hard ban on implementation sources, filtered
queries, stop and ask, self-disclosure). Claude addendum:

- **Tool ban.** The ban covers `Read`, `Grep`, `sed`, `cat`, `head`, `Glob` preview and every
  `git diff`, `git show` and `git log -p` form except `git diff --name-only`.
- **Notes.** Every `notes_read` `list` carries `keys`, naming only keys outside the seat's
  `reads_exclude` (for `needs-test-author`: `implementation-notes`, `session-tracking`; config.yaml
  trait entry). `items_read` `schema` lists the item's queue keys (`task-scope`, `diagnosis`,
  `test-plan`, `amendments`, `migration-assessment`, ...). A body comes only from `notes_read` `get`
  of one such key.
- **Search.** `items_read` `search` is scoped by `itemId`.
- **Changed files.** Names only: `git diff --name-only`, or the File ownership rows of the dispatch contract.

---

## 5. Red-First Check and the Red-Proof Budget

Rule `test-author` section 5 (read it there). The manifest carries no mandatory per-scenario
"what wrong behavior" line: see rule `test-assertion-vacuity` point 4, where an assertion that
cannot name a bug it would catch is strengthened or marked `not-covered: <reason>`.

---

## 6. Adversarial Probe Catalog

Rule `test-author` section 6. Concrete tokens for alternate separators and encoded forms: `\` vs
`/`, `..%2f`, `\\?\`.

---

## 7. Forbidden Patterns

Rules `forbidden-test-patterns`, `test-author` section 7 and `test-assertion-vacuity`. Before/after
examples are in `references/forbidden-patterns.md`. Claim-fixture instance: a claim built with
`claimedAt = Instant.now()` and a past `claimExpiresAt` is rejected by `WorkItem.validate()`
(`domain/model/WorkItem.kt:107,132`); derive `claimedAt = claimExpiresAt.minus(ttl)`.

---

## 8. Ambiguity Arbitration

Rule `test-author` section 8. The orchestrator side of a red author-test is rule `arbitration`.

---

## 9. On a Failing Test

Rule `test-author` section 9.

---

## 10. The Test Manifest

Rule `test-author` section 10 and the `test-manifest` guidance (`items_read` `schema`, `fields`
`["guidance"]`). A later pass rewrites the note in full, ending with a final `Amendments:` list.
Red-proof shape strings:

- `behavioral-red (narrowest revert: <what was reverted>)`
- `compile-red only - substitute verification: <what the reviewer does instead>`

---

## 11. Degraded Modes

**Direct-tier temporal-only mode** (§1) is the only sanctioned degradation. It applies only when
there is no second agent available to dispatch. Under this mode:

- `test-plan` still gates work entry — the oracle freeze happens before implementation, exactly
  as in the two-agent case. This is the part of the guarantee that survives.
- Red-first (§5) still applies in full for bug-fix reproductions.
- `test-manifest` declares the single-seat mode explicitly: its first line is exactly
  `independence: temporal-only` (the waiver line, `manage-schemas` `references/config-format.md` →
  "The `independence: temporal-only` waiver"), followed by `identity: <principal, seat> (temporal-only,
  same agent as implementer)`, rather than presenting as if a second agent were involved.
- **§4 in temporal-only mode.** §4 is written for a second agent, so state which parts survive
  rather than leaving the section self-contradictory for a single seat:
  - **Relaxed: the tool ban only (section 4).** One seat implements and then writes tests; it has already
    read `src/main` and cannot un-read it. The ban is replaced by the red-first ORDERING of §5 —
    the test is written from the frozen `test-plan` and observed red against pre-fix code BEFORE
    the fix is written, which is the only separation available here. An "I avoided looking"
    claim is not a substitute and must not be recorded as one.
  - **Still in force: supplied declarations and filtered notes (rule `test-author` section 4).** Oracles and declarations come from the frozen `test-plan`
    and the planning-seat notes, not from the implementation just written; every `notes_read`
    `list` still carries `keys` restricted to the queue-phase keys. `implementation-notes` and
    `session-tracking` stay out of the author pass even though the same seat wrote them.
  - **Still in force: stop-and-ask and self-disclosure (rule `test-author` section 4).** A declaration the frozen plan does not carry is still a
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
