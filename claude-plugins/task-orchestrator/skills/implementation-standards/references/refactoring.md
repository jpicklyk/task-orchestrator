# Refactoring

A refactoring changes how code is built without changing what it does. The discipline below keeps
that promise checkable.

## Pin behaviour first

- Before restructuring, make sure tests cover the behaviour you are about to move. Use the
  existing tests where they cover it; where they do not, add characterisation tests that record
  what the code does today and pass before the change.

## Separate mechanical and semantic change

- Keep mechanical change (rename, move, extract, inline) and semantic change (anything that alters
  behaviour) in separate steps, each readable on its own. A diff that mixes them hides the behaviour
  change among the moved lines.
- Run the tests after each step, not only at the end, so a failure points at the step that caused
  it.

## Test expectations

- A test's expectation changes only for a behaviour change you intend and state. A refactoring
  that needs an expectation changed has changed behaviour: either it was not a pure refactoring,
  or it introduced a defect.

## Keep it in proportion

- A restructuring that grows beyond what the requirement needs is split out into its own change,
  so the requirement's change stays small enough to read.

## Signs it was followed

- Tests covering the moved behaviour exist and pass before the restructuring.
- Mechanical and semantic changes are in separate, separately readable steps.
- No test expectation changes without a stated, intended behaviour change.
- Restructuring beyond the requirement is absent from the change, or raised as its own change.
