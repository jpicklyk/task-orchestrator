# Verification

Verify your own change before calling it done, and say what you ran, what it showed, and what you did not run and why.

## What to run

- Build the code, run the tests that cover what changed, and run the project's own lint, format
  and static checks.
- Start narrow (the tests closest to the change) for a fast answer, then run the broader suite the
  project expects before calling the change done.
- Read the full output of every check, not only its last line: a summary can hide a skipped test,
  a warning that matters, or a failure in a part you did not expect to touch.

## When something fails

- Fix the cause of a failure. Never skip, delete or loosen a test to make it pass.
- A failure that existed before your change, or that comes from code your change does not touch,
  is reported as such with its evidence (the same failure without your change, or the unrelated
  code it points to). It is not silently ignored and not silently fixed inside this change.

## Beyond the tests

- Exercise the behaviour the tests cannot reach (a user-visible flow, an integration with a real
  dependency, an error path that is hard to trigger in a test), and say how you exercised it.

## Reporting

- Say what ran and what it showed, and what did not run and why.
- Never claim a check that did not run, and never report a result from an earlier revision as if
  it were for the final one.

## Signs it was followed

- The change's report names each check that ran and its result, for the final revision.
- The report lists any check that did not run, with the reason.
- No test is skipped, deleted or loosened in the diff to make a failure pass.
- Any pre-existing or unrelated failure is reported as such with its evidence.
- Behaviour the tests cannot reach is described as exercised, or as not exercised with the reason.
