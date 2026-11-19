---
name: test-authoring
description: "Craft of writing tests independently of the code they check: scenarios derived from stated requirements, oracles taken from outside the implementation, blindness to production source, adversarial probes, forbidden test patterns, and red evidence that a test can fail. Reports coverage honestly, with every ambiguity and every breach disclosed. Use when writing tests from a specification rather than from the implementation, deriving test scenarios and oracles, or proving a regression test fails before its fix."
user-invocable: false
---

# Test Authoring

## Why

An author who writes both the code and its tests has no adversary. The tests confirm what was
built, not what was intended: expected values get copied from the code's own output, branches
that matter get skipped, and a green result says little. Independent test authorship puts a second
view between the requirement and the result. Where no second author exists, the same author can
still keep the separation that matters most: write the test from the specification and see it
fail before the fix exists, and never claim independence that was not there.

## Working order

Each step's detail is in the reference it links.

1. **Scenarios**: derive numbered scenarios from the stated requirements, partitioned into happy,
   failure and edge cases. [Scenarios and oracles](references/scenarios-and-oracles.md).
2. **Oracles**: fix each scenario's expected result from a source outside the implementation,
   before any test is written. [Scenarios and oracles](references/scenarios-and-oracles.md).
3. **Tests against declarations**: write each test against the public declarations supplied to
   you, never against production source. [Blindness](references/blindness.md).
4. **Probes**: add the adversarial probes that apply to the surface. [Probes](references/probes.md).
5. **Can this fail**: for every assertion, name a change to the code that it would catch.
   [Forbidden patterns](references/forbidden-patterns.md), "Vacuous negative assertions".
6. **Red evidence**: observe each test fail where it should, then pass.
   [Red evidence](references/red-evidence.md).
7. **Coverage account**: every scenario is mapped to a test or marked not covered with a reason;
   every probe is listed, including those that found nothing and those that do not apply.
   [Probes](references/probes.md), "Accounting".
8. **Forbidden patterns**: list each one present, with its justification. An empty list is a claim
   and must be true. [Forbidden patterns](references/forbidden-patterns.md).
9. **Fixture invariants**: state what each fixture satisfies by construction.
   [Forbidden patterns](references/forbidden-patterns.md), "Fixtures satisfy invariants by
   construction".
10. **Ambiguities**: list each one raised, what was unclear and what each reading would require.
    [Blindness](references/blindness.md), "Ambiguity".

An item left out of the account reads as not done. Use an explicit not-applicable marker with a
reason for an item that genuinely does not apply, a scenario partition included.
