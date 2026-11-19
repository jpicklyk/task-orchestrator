---
name: test-author
description: This codebase's supplements to the served rule test-author. Holds the numbered stubs that point at the rule, the tool-ban list, the claim-fixture instance, the red-proof shape strings, and the Kotlin, JUnit and MockK forbidden-pattern examples.
user-invocable: false
---

# Test Authoring Framework

This skill is the Claude addendum to how test authorship is separated from implementation: the
served rules hold the protocol, and what follows is what they lack for this codebase.

The served rule `test-author` (`docs_read` `get`, `kind` `rule`, `slug` `test-author`, `rootId`) holds
sections 1-10 below, together with the rules `forbidden-test-patterns` and `test-assertion-vacuity`.
Sections 1-10 here are pointer stubs that keep their numbers (other files cite them) plus the
Claude addendum the rules lack; section 11 holds only note formats. Where a stub and a rule differ, the
rule wins.

---

## 1. When This Applies - The Two Seats

Rule `test-author` section 1. Here the single-item exception is a Direct-tier dispatch (section 11).

---

## 2. Deriving Numbered Scenarios From Acceptance Criteria

Rule `test-author` section 2 (happy / failure / edge partition, stable S-ids,
`EXISTING-SURFACE` / `NEW-SURFACE` labels and narrowest-revert recipes). The `declarationModules:`
line is rule `test-author` section 1 (the extractor consumes it: rule `declarations-extractor`); the `red-proof-shape` field is the closing paragraph
of rule `test-author` section 5. The enumerated-surface `sweep:` line is rule `planning-sweeps` point 2.

---

## 3. Oracle-Derivation Rule

Rule `test-author` section 3.

---

## 4. Blindness Rule - A Capability Boundary

Rule `test-author` section 4 (declarations supplied, hard ban on implementation sources, filtered
queries, stop and ask, self-disclosure). Claude addendum:

- **Tool ban.** The ban covers `Read`, `Grep`, `sed`, `cat`, `head`, `Glob` preview and every
  `git diff`, `git show` and `git log -p` form except `git diff --name-only`.
- **Search.** `items_read` `search` is scoped by `itemId`.
- **Changed files.** Names only: `git diff --name-only`, or the File ownership rows of the dispatch contract.

---

## 5. Red-First Check and the Red-Proof Budget

Rule `test-author` section 5 (read it there). The manifest carries no mandatory per-scenario
"what wrong behavior" line: see rule `test-assertion-vacuity` point 4, where an assertion that
cannot name a bug it would catch is strengthened or marked `not-covered: <reason>`.

---

## 6. Adversarial Probe Catalog

Rule `test-author` section 6.

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

Rule `test-author` section 1 names the Direct-tier temporal-only mode; the separation that remains
is in the shipped `test-authoring` skill's `references/blindness.md`, "Same-author work". Note
formats for it:

- `test-manifest` first line is exactly `independence: temporal-only` (the waiver line,
  `manage-schemas` `references/config-format.md`, "The `independence: temporal-only` waiver"),
  followed by `identity: <principal, seat> (temporal-only, same agent as implementer)`.
- `test-independence-audit` records the verdict as `independent-degraded`, never `independent`.
