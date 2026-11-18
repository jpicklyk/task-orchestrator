# Claims and sweeps

A plan reads as authority to whoever builds from it. An unverified sentence in a plan becomes a
defect in the code. This reference covers how to state facts, how to prove searches, and how to
prove a verify command.

## Cite a contract, never restate it

When a plan prescribes a call sequence, API semantics or any fact owned by another artifact (a
source file, a tool description, another spec), cite the authoritative location and tell the
builder to check it before relying on it. A restated copy drifts from its source.

If the plan says something was verified, name exactly what was checked. "Verified against
source" that opened one path while prescribing another is how a partly verified claim ships a
defect.

Self-check: for each fact in the plan owned elsewhere, is there a location next to it? Delete the
restatement if the location is enough.

## Claims that need a file and line

A claim of nullability, redaction, ownership, a default, a limit or an honest limit (what a
feature deliberately does not do) names the file and line that prove it. Write the sentence from
the code, not from intent.

Anonymised worked case: a plan said an attribute is shown only when the caller is an
administrator and a redaction flag is false. The code hid it only when the flag was true and the
caller was not an administrator, so it showed when the caller was an administrator or the flag
was false. The sentence was written from intent and no line was opened. Opening the one line
that computes visibility would have exposed the inverted logic.

Prose that is not a semantic claim needs no citation. This is not a blanket citation rule.

Self-check: highlight every sentence containing "never", "always", "only", "default", "null",
"empty", "limit" or "owner". Each one carries a file and line or is cut.

## Negative and exhaustive claims name their search

"No other caller", "all sites updated" and "nothing else uses it" are claims about a search.
Write the search, its scope and its hit count: `grep -rn "oldName" src -> 0 hits (main and test
trees)`. A count of zero with no scope proves nothing.

Self-check: could someone rerun your search and get your number? If the scope is vague, they
cannot.

## Sweeps

Two changes need a sweep before the plan is done.

- A changed fixed list (an enumeration, a registry, an allowed-values set): search for the tests
  that pin the list's contents or length, and name each with the edit it needs. Also search for
  documents that restate the list.
- A changed script or command (a renamed flag, a moved entry point, a changed output): search for
  every document, test and automation that names it, and give each hit an owner.

For a bug, sweep for the defect class: search for the root-cause pattern across the codebase and
list every other site, fixed in this change or recorded for later with a reason.

Self-check: for each sweep, is every hit either in the plan's file list or given an owner and a
reason? A hit with neither is a gap.

## Verify commands

Before a plan names a verify command, run it twice: once against known-good input (it must
succeed) and once against known-bad input (it must fail), on the platform where it will run. A
command never shown to fail has not been shown to verify anything, and a check that always passes
is worse than none because it is trusted. Record the command in the exact form that ran, not a
reconstruction.

Self-check: next to each command, can you show the failing run? If not, it is unproven.
