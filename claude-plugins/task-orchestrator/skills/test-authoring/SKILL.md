---
name: test-authoring
description: "Craft of writing tests independently of the code they check: scenarios derived from stated requirements, oracles taken from outside the implementation, blindness to production source, an adversarial probe catalog, forbidden test constructs, and red evidence that a test can fail. Reports coverage honestly, with every ambiguity and every breach disclosed."
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

1. [Scenarios](references/scenarios-and-oracles.md): derive numbered scenarios from the stated
   requirements, partitioned into happy, failure and edge cases.
2. [Oracles](references/scenarios-and-oracles.md): fix each scenario's expected result from a
   source outside the implementation, before any test is written.
3. Tests against declarations: write each test against the public declarations supplied to you,
   under the discipline in [blindness](references/blindness.md).
4. [Probes](references/probes.md): add the adversarial probes that apply to the surface.
5. Can this fail: for every assertion, name a change to the code that it would catch.
6. [Red evidence](references/red-evidence.md): observe each test fail where it should, then pass.
7. Account for coverage honestly: every scenario is mapped to a test or marked not covered with a
   reason; every probe is listed, including those that found nothing and those that do not apply.
8. [Forbidden constructs](references/forbidden-patterns.md): list each one present, with its
   justification. An empty list is a claim and must be true.
9. Fixture invariants: state what each fixture satisfies by construction.
10. Ambiguities: list each one raised, what was unclear and what each reading would require.

An item left out of the account reads as not done. Use an explicit not-applicable marker with a
reason for an item that genuinely does not apply.

## Scenarios and oracles

Every requirement yields scenarios in all three partitions, with stable identifiers that are never
renumbered; a dropped scenario is marked dropped, not deleted. Each scenario states where its
expected result comes from: a specification clause, a published algorithm or external reference,
or a computation done independently. "What the code returns" is never a source. Whether a scenario
touches only existing surface or surface the fix introduces decides what a revert can prove. See
[scenarios and oracles](references/scenarios-and-oracles.md).

## Blindness

Blindness is a discipline the author holds, not a restriction any tool enforces: a breach is
disclosed in full, never hidden. The only view of production code is the set of declarations
supplied; a missing or non-compiling one is asked for, never reconstructed. See
[blindness](references/blindness.md) for what counts as a breach and what the same-author case
requires.

## Red evidence

A test that has never failed has not shown it can. A bug fix needs its test seen failing without
the fix. New behaviour in guards, boundaries, predicates and similar decision points is checked by
small mutations of the code, and every survivor is triaged rather than ignored. A test that fails
against the implementation is a finding, never something to weaken. See
[red evidence](references/red-evidence.md).

## Probes

Beyond scenario-derived tests, probe the surface with boundary values, alternate spellings of the
same input, the three distinct states of empty, absent and null, duplicates and ordering, replay
of the same write, and platform-bound behaviour. See [probes](references/probes.md).

## Forbidden constructs

Skip guards on behavioural conditions, disjunctive escapes, oracles read from the implementation,
presence-only assertions, order-only verification of mocks, and assertions a surrounding error
handler can swallow are each forbidden without a stated justification. So are negative assertions
whose fixture could never have produced the outcome. See
[forbidden patterns](references/forbidden-patterns.md).
