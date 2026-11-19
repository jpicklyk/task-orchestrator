# Standards Axis

The standards axis asks whether the change is built well: whether the next person to read or change
this code will understand it and be able to change it safely.

## Which standards apply

A project's own written standards, where it has them, come before the generic list below. Where the
project's standard and this list disagree, the project's standard wins. Where the project is silent,
use this list.

A standards finding cites the standard it breaks: the project's written standard by its own name or
section, or the item of this list. A finding that cites no standard is a preference: report it as
an observation marked as a preference, because it is not a standard. Its confidence still says only
how sure you are that the problem is real.

## What to check

- **Reuse and duplication.** Does the change re-implement something the codebase already has? Is
  the same logic now written in two places that must stay in step? Copied logic that must change
  together is the most costly kind.
- **Complexity.** Is the code no more complex than the problem needs? Look for speculative
  abstraction. An abstraction (an interface, a base type, an extra parameter, a layer) is justified
  by one of two things: a second real user that exists today, or a stated boundary reason, such as
  a dependency rule that keeps one side from knowing the other, or a seam a test needs. With
  neither, it is speculative and is a standards finding. Configuration nobody sets is the same
  failure.
- **Error handling.** Are failures reported where they can be handled, with enough detail to act
  on? Look for swallowed exceptions, a catch-all that hides the cause, an error turned into a
  default value that a caller cannot tell from success, and cleanup that does not run on the error
  path.
- **Dead and leftover code.** Unused functions, parameters and imports; commented-out code; debug
  output; a temporary workaround with no stated end; a TODO that the change itself should have
  resolved.
- **Architecture fit.** Does the change respect the dependency direction of the codebase (an inner
  layer never reaching an outer one)? Does it follow the local patterns of the code around it, or
  introduce a second way of doing something the codebase already does one way? The exception is an
  existing way that is itself a defect (unsafe, losing or hiding an error, or producing a wrong
  result): new code done correctly instead of copying it is not a finding, as long as the change
  reports the existing occurrences rather than silently diverging from them or rewriting them in
  the same change. Do names say what things are, in the vocabulary the surrounding code already
  uses?

## Test code is code

Judge the test code the change adds or edits by the same list: duplication, complexity, dead code
and naming apply to tests as much as to production code. Whether the tests actually verify the
behaviour they claim to (their substance, independence and oracles) belongs to the craft of
writing tests, and is judged by that subject's own criteria rather than here.

## Severity on this axis

- **Blocking**: construction that will cause wrong behaviour, or makes the code unsafe to change: a
  broken dependency direction, a swallowed error that hides a failure, duplicated logic that must
  stay in step, a breach of a written project standard the project treats as mandatory.
- **Observation**: anything that only makes the code harder to read or extend: a naming
  inconsistency, a small speculative abstraction, leftover debug output, a missed chance to reuse.
