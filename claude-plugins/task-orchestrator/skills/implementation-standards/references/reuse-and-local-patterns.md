# Reuse and Local Patterns

## Search before writing

- Search for what already does the job before you write it. Search by the nouns of what the code
  does (the data it reads, the thing it computes, the failure it handles), not by a name you guess
  the existing code might have: a guessed name misses code that was named differently.
- Look in two places: the shared modules the project keeps for common work, and the code's
  neighbours (the same file, the same package or directory, and the callers of the code you are
  changing).
- When something close exists, extend it rather than adding a near-duplicate next to it. Two
  functions that do almost the same thing drift apart, and the next fix lands in only one of them.

## Changing shared code

- A change to code that other code calls keeps every caller working, or updates every caller in
  the same change. Find the callers by searching for every use, not by remembering them.
- When a caller cannot be updated in the same change, keep the old behaviour available for it and
  state why.

## Match the code around the change

- Match the local naming, file layout, error style, logging style, test style and dependency
  choices. A reader should not be able to tell from style alone which lines are new.
- Add no new dependency when one already in use does the job. When a new dependency is needed,
  state what it does that the existing ones do not.
- A local pattern that looks wrong is still followed in this change, and called out as a separate
  problem. Replacing it in one place leaves two styles side by side, which is worse than either.

## Signs it was followed

- The change's report names what was searched for and what existing code was reused or extended.
- No new function duplicates the logic of an existing one in the shared modules or nearby.
- Every caller of changed shared code still works or is updated in the same diff.
- New code uses the same naming, error style, logging style and test style as its neighbours.
- No dependency is added where an existing one covers the need, or the report says why one was.
- A local pattern judged wrong is followed and raised separately, not rewritten in this change.
