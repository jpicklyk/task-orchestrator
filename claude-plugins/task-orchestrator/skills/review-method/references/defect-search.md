# Defect Search

The spec axis checks the change against what was asked. The defect search looks for what breaks the
change that nobody asked about: the inputs, states and callers the criteria never mention.

A defect is wrong behaviour, so a defect finding carries axis spec. When what you find is only poor
construction, with no input or state that produces a wrong result, it carries axis standards.

## Technique

For each changed unit (a function, a handler, a query, a migration, a script):

1. **Trace its inputs.** Where does each value come from, and what can it be? Follow it back to the
   boundary where it enters: user input, a file, the network, another component, configuration.
2. **Read its callers.** Who calls this, with what, and what do they do with the result? A change
   that is correct in isolation can break a caller that relied on the old behaviour.
3. **Ask which input breaks it.** Construct the input or state that makes it return the wrong
   result, throw, hang, lose data or leave things half done. If you can construct one, that is a
   defect; if you cannot, move on.

## Catalogue

Use this list to drive the third step. It is a prompt for questions, not a checklist to tick.

- **Logic errors.** Inverted or missing conditions, wrong operator, off-by-one, a branch that can
  never be taken, a fall-through that should not happen, the wrong variable used.
- **Edge and boundary inputs.** Empty, null or missing; zero, negative, maximum and overflow; a
  single element; duplicates; very long strings; unusual characters and encodings; values exactly
  at a limit.
- **State and ordering.**
  - Concurrency: two callers at once, a read and a write racing, a check followed by an act on stale
    state.
  - Re-entry: the unit called again while it is still running, directly or through a callback.
  - Idempotency: the same request applied twice; is the second time harmless?
  - Partial failure: a step fails half way; what is left behind, and is it consistent?
  - Retry: a retry after a failure that actually succeeded; a retry that repeats a side effect.
- **Resources.** Handles, connections, locks or temporary files that are not released on every
  path, the error path included; unbounded growth of a cache, queue or buffer; work that grows
  faster than its input.
- **Unsafe input handling.** Untrusted input reaching a query, a shell command, a file path, a
  template or a deserializer without validation or escaping; secrets written to a log or an error
  message; a check done on one value and an action taken on another.
- **Changed contracts and their callers.** A changed signature, return value, error, default,
  ordering or format: find every caller and check each still works. A caller outside the change is
  the most common place for this defect to hide.

## Reporting a defect

A defect finding states a concrete failure scenario: the input or state, and the wrong result it
produces. For example: "a request retried after a timeout that had already committed inserts the
row twice, because the insert has no uniqueness check."

When you suspect a defect but cannot state a concrete scenario, report it as an observation with
low confidence stated, and say what would confirm or rule it out.
