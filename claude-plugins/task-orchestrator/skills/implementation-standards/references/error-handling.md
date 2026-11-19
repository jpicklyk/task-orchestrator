# Error Handling

## Where a failure is handled

- An expected failure (bad input, a missing file, a refused request, a timeout) is handled where
  something useful can be done about it (retry, fall back, report it to the user) or returned to
  the caller with context. A programming error (a broken invariant, an impossible state) fails fast
  instead of being worked around.
- Nothing is swallowed: no empty catch block, no ignored error result, no fallback that hides that
  something went wrong. A wrapped failure keeps its original cause, and a message names the failing
  input without any secret.

## Validate once, at the trust boundary

- Validate input at the trust boundary where it enters (a request, a file, a message from another
  process), not again in every inner function. Repeating the same check at every layer hides which
  layer is responsible.
- Two cases are checked where the value is used as well: a value that can arrive through more than
  one entry point, and a check that guards security.

## Log once

- Log each failure once, where it is handled. Logging and rethrowing at every level produces many
  records of one failure and buries the useful one.
- The level matches the severity: a failure the code recovered from is not an error, and a failure
  that loses data is not a debug line. Credentials, tokens and personal data are never logged.

## Retries

- A retry is bounded (a fixed number of attempts or a deadline) and backs off between attempts.
  Retry only failures that can succeed on a later attempt: a rejected input fails the same way
  every time. Whether the operation is safe to repeat is in
  [state and resources](state-and-resources.md).

## Signs it was followed

- No empty catch block or ignored error result appears in the diff.
- Wrapped failures carry their original cause, and messages name the failing input with no secrets.
- Each failure is logged once, at a level that matches its severity, with no credentials, tokens or
  personal data.
- Every retry loop has a bound and a backoff.
- Input is validated where it enters, and again at the point of use only for a value with more
  than one entry point or a security check.
