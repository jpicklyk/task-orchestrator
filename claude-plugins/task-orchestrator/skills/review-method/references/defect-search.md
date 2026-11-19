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

- **Changed contracts and their callers.** A changed signature, return value, error, default,
  ordering or format: find every caller and check each still works. A caller outside the change is
  the most common place for this defect to hide.
- **State and ordering.** Two callers at once; the unit re-entered through a callback; the same
  request applied twice; a step that fails half way; a retry after a failure that actually
  succeeded.
- **A check and an act on different values.** A value validated in one form and used in another
  (before and after decoding, normalising or a second read), or a check made on state that changes
  before the act.
- **Resources.** A handle, lock or temporary file not released on the error path; a cache, queue or
  buffer with no bound; work that grows faster than its input.
- **Logic, boundaries and unsafe input.** The usual classes (inverted conditions, off-by-one,
  empty and maximum inputs, untrusted input reaching a query, shell, path or deserializer) apply
  as always; spend the attention on the classes above, which a reading of the diff alone misses.

## Reporting a defect

A defect finding states a concrete failure scenario: the input or state, and the wrong result it
produces. For example: "a request retried after a timeout that had already committed inserts the
row twice, because the insert has no uniqueness check."

When you suspect a defect but cannot state a concrete scenario, report it as an observation with
low confidence stated, and say what would confirm or rule it out.
