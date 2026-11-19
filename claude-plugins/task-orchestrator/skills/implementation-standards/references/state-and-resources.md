# State and Resources

A change can meet every stated requirement on a single, orderly call and still fail when calls
overlap, repeat or stop half way, or when it runs long enough to exhaust what it holds. Before you
call the change done, work through the cases below for each unit you wrote or changed.

## State and ordering

- **Concurrency.** Ask what happens when two callers run the unit at once. Where a read is followed
  by a write, or a check by an act, make the pair safe against another caller changing the state in
  between: hold the lock across both, use an atomic operation, or make the write conditional on the
  state that was read.
- **Re-entry.** Ask what happens when the unit is called again while it is still running, directly
  or through a callback it triggers. Finish updating shared state before calling out, or guard
  against the second entry.
- **Idempotency.** Where the same request can arrive twice (a retry, a duplicate message, a
  double submit), make the second application harmless: a uniqueness check, a stored request key, or
  an operation that sets a value instead of adding to it.
- **Partial failure.** For a unit with several steps that change state, decide what must hold if a
  step fails half way. Make the steps one unit (a transaction), undo the earlier steps on failure, or
  leave a state that a later run can recognise and finish. Never leave a half-written state that
  looks complete.
- **Retry.** A retry can follow a failure that actually succeeded, such as a timeout after the work
  was done. Retry only an operation that is safe to repeat, or make it safe first; see
  [error handling](error-handling.md) for bounding and backoff.

## Resources

- **Release on every path.** A handle, connection, lock or temporary file the change acquires is
  released on every path, the error path included, through the language's scoped construct rather
  than a release call at the end of the success path.
- **Bounded growth.** A cache, queue or buffer the change adds or fills has a bound: a size limit,
  an eviction rule or an expiry. State what happens when the bound is reached (block, drop, evict or
  refuse) and make that choice on purpose.
- **Work proportional to input.** Check how the work grows with the size of the input. A loop
  inside a loop over the same collection, a query issued once per element, or a whole-collection
  copy per call grows faster than its input; replace it with a lookup, a batched query or a single
  pass.

## Signs it was followed

- Every read-then-write or check-then-act on shared state in the diff is protected against a
  concurrent caller, or the change's report says why no second caller can exist.
- An operation that can be repeated by a retry or a duplicate request is harmless the second time.
- A multi-step state change either completes as one unit, undoes itself on failure, or leaves a
  state a later run can finish.
- Every handle, connection, lock and temporary file acquired in the change is released on the
  error path as well as the success path.
- Every cache, queue or buffer the change fills has a stated bound and a stated behaviour at that
  bound.
- No loop in the change does work that grows faster than its input.
