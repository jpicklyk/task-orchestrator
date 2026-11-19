# Spec Axis

The spec axis asks whether the change does the right thing. It catches drift in both directions:
work that was asked for and not done, and work that was done and not asked for.

## Map every criterion

Walk the stated acceptance criteria one by one. For each, name:

- the specific change that meets it, and
- the evidence that would fail without that change: a test that would go red, a reproduction that
  would behave differently, an output that would differ.

A criterion with no matching change is a finding: either the work is incomplete or the criterion
was left out on purpose. Leaving a criterion out is acceptable only with a stated reason; a
criterion that silently disappears is blocking.

A criterion met by code but not backed by any evidence that would fail without it is an
observation: the behaviour may be right today and nothing will notice when it stops being right.

## Trace every change back

Every change should trace to a criterion or carry its own justification. Changes outside the
criteria are not wrong by themselves; implementation often reveals necessary adjacent work. But an
unexplained change is a finding, because nobody asked for it and nobody checked it.

## Non-goals

Read the stated non-goals and check the change did not cross them. Work inside an area that was
explicitly scoped out is a finding even when the work itself is good.

## Read the requirement as intended

A criterion is a statement of intent, not a test to be passed literally. Ask what the author of the
requirement wanted, and check the change against that:

- inputs a criterion does not name but plainly covers (empty, missing, very large, repeated, in a
  different order);
- callers and situations the criterion implies but does not list;
- behaviour the criteria take for granted (an existing feature keeps working, an error is still
  reported, data is not lost).

A change that meets the letter of a criterion and breaks its intent fails it.

## Severity on this axis

- **Blocking**: a stated criterion is not met; a criterion was dropped without a stated reason; a
  non-goal was crossed; the change produces wrong behaviour a user or caller can reach.
- **Observation**: the behaviour is right but its evidence is thin; an unexplained change that does
  no harm; a gap in implied behaviour that is minor or unlikely to be reached.
