# Test strategy

A test strategy is a specific accounting of what will be verified and how. "Add tests" is not a
strategy.

## Cover three kinds of scenario

- Happy paths: the primary uses the change enables, under normal conditions.
- Failure paths: invalid input, a missing dependency, an operation that fails. These confirm the
  system fails clearly instead of corrupting state or throwing an unhandled error.
- Edge cases: boundaries specific to the change, such as empty collections, absent optional
  fields, maximum depth, cycles and concurrent access. Ask what a caller could do that you did
  not design for.

Name each scenario concretely. "Test edge cases" says nothing; "a circular parent reference is
detected and rejected with a clear error" is a scenario.

Self-check: for each of the three kinds, is there at least one named scenario, or a stated reason
that kind does not apply?

## Stable numbered ids

Give every scenario an id (S1, S2, ...) that never changes once written. Anything that refers to
a scenario later, such as a test name or a coverage report, uses the id, so renumbering breaks
those references. Add new scenarios at the end.

Self-check: if a scenario is dropped, does its id stay retired instead of being reused?

## An oracle for each scenario

An oracle is the source that says what the right answer is. It is a specification clause or a
stated acceptance criterion, a documented contract, an external reference cited by name and
section, or a value computed independently of the code under test. It is never the current output
of that code, because a test that copies today's output proves only that nothing changed,
including bugs.

Write the oracle source next to the scenario: "S3: empty name is rejected; oracle: acceptance
criterion 2".

Self-check: set the implementation aside. Could the expected value still be derived from the
oracle you wrote? If it needs the code, the oracle is wrong.

## Existing tests the change breaks

If the change alters a shared interface (a domain model, a storage contract, a tool or API
parameter, a fixed list), find the existing tests that assume the old behaviour. For each one,
state the edit it needs or confirm it still passes unchanged. Search for them rather than
guessing, and record the search (see [claims and sweeps](claims-and-sweeps.md)).

Self-check: is every test that pins a changed value or list in the plan, with its edit, or
covered by a named search that found none?
