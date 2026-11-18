# Minimal Change

Make the smallest change that meets the requirement. Every line in the diff should be traceable to
the requirement; a line that is not makes the change harder to read and riskier to accept.

## Only what the requirement needs

- Change only the code the requirement needs. No drive-by reformatting, renames, reordering or
  cleanup of unrelated code. When you see something worth fixing outside the requirement, raise it
  separately instead of folding it in.
- Leave the public surface (exported names, signatures, wire formats, stored formats, messages
  other code parses) unchanged unless the requirement changes it.

## No speculative abstraction

- Add no parameter, option, interface, extension point or generalisation that has no current
  caller. Code written for a future need guesses that need's shape, and the guess is usually wrong.
- When two concrete uses exist and the shared shape is clear, extract it then; one use is not a
  pattern.

## No dead code

- Leave no unused functions, unreachable branches, commented-out code, debug output or leftover
  flags. Version history keeps the old code; the file does not need to.
- Remove what your own change made unused. Code that was already unused before the change is
  raised separately, like any other unrelated cleanup.

## Departures from the agreed design

- When the agreed design turns out wrong or impossible, state the departure and its evidence. Never
  substitute a different design silently, even a better one: the people relying on the agreed design
  need to know it changed.

## Signs it was followed

- Every hunk in the diff relates to the requirement; no unrelated file or line is reformatted,
  renamed or reordered.
- No new parameter, option, interface or extension point lacks a caller in the same change.
- No commented-out code, debug output, unused function or leftover flag is added.
- The public surface changes only where the requirement asks for it.
- Any departure from the agreed design is stated in the change's report with its evidence.
