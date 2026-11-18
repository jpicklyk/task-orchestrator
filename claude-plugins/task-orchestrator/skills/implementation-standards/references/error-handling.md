# Error Handling

## Expected failures and programming errors

- An expected failure (bad input, a missing file, a refused request, a timeout) is handled where it
  happens or returned to the caller with context. A programming error (a broken invariant, an
  impossible state) fails fast and loudly instead of being worked around.
- Handle a failure where something useful can be done about it: retry, fall back, or report it to
  the user. Otherwise let it propagate to a level that can.
- Never swallow a failure: no empty catch block, no ignored error result, no fallback that hides
  that something went wrong.

## Wrapping and messages

- When you wrap a failure in another, keep the original cause attached, so the first reader of the
  failure can still see where it started.
- A message says what failed and with which input (the identifier, the path, the value), so it can
  be acted on without a debugger. It never contains secrets.

## Boundaries and resources

- Validate input at the trust boundary where it enters (a request, a file, a message from another
  process), not again in every inner function. Repeating the same check at every layer hides which
  layer is responsible.
- Two cases are checked where the value is used as well: a value that can arrive through more than
  one entry point, and a check that guards security.
- Release resources (files, connections, locks, temporary state) on every path, the failure paths
  included.

## Logging

- Log each failure once, where it is handled. Logging and rethrowing at every level produces many
  records of one failure and buries the useful one.
- The log level matches the severity: a failure the code recovered from is not an error, and a
  failure that loses data is not a debug line.
- Include context fields (the identifiers and inputs involved) in the record.
- Never log credentials, tokens or personal data.

## Retries

- A retry is bounded (a fixed number of attempts or a deadline) and backs off between attempts.
  Retry only failures that can succeed on a later attempt; a rejected input fails the same way
  every time.

## Signs it was followed

- No empty catch block or ignored error result appears in the diff.
- Wrapped failures carry their original cause, and messages name the failing input with no secrets.
- Each failure is logged once, at a level that matches its severity, with no credentials, tokens or
  personal data.
- Resources acquired in the change are released on the failure paths as well as the success path.
- Every retry loop has a bound and a backoff.
- Input is validated where it enters, and again at the point of use only for a value with more
  than one entry point or a security check.
