# Red evidence

A test that has never failed has not shown it can.

## Bug fixes

Write the regression test from the reproduction of the bug and observe it fail against the code
before the fix. Never assume that a description of a reproduction implies a failing assertion. The
test must then pass after the fix. Waive this only for a stated reason, such as a reproduction
that is impractical to run.

## New behaviour in decision points

For new behaviour in guards, transitions, classification, boundaries and security predicates,
check the tests with a small number of mutations: one per distinct decision point, at most one per
line, only on changed lines the new tests reach. Preferred operators:

- drop one of several parallel enforcement paths;
- flip or shift a boundary comparison;
- invert a predicate;
- remove a guard;
- give a source-scanning check an alternate spelling of what it scans for.

For concurrency or atomicity, aim the mutations at a fault injected at each write inside the unit
and at the lock or ordering seam. If a mutation exposes a real gap, widen the check to the rest of
that work's scenarios. A clean result stops there.

Skip mutation checks for tests of already-correct behaviour, pure refactors and moves, and glue,
configuration, wiring, logging or documentation.

## Survivors

A surviving mutation is triage, not an automatic failure. Classify it before demanding a new test:

- **Weak test**: the test reaches the line but its assertion cannot tell the difference.
- **Equivalent mutant**: the changed code behaves identically for every input.
- **Unreachable**: no input can reach the mutated line.
- **Covered elsewhere**: another test catches it; name that test.

## Coupled authorship

When authorship is not independent (the same author wrote code and tests, or the author read the
implementation), every scenario that asserts new behaviour gets a red check.

## A failing test

A test that fails against the implementation as it stands is a finding, not a problem to make go
away. Report the scenario identifier, the assertion, the observed value and the oracle citation.
Never weaken the assertion, skip it, or wrap it to hide the failure. Do not settle which of the implementation, the test or the specification is wrong by
changing the test to match the code: the recorded value and oracle citation keep that question open
on the evidence.
