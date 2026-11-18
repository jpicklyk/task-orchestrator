# Blindness

Blindness is a discipline the author holds, not a restriction any tool enforces. Nothing stops a
read of production code: the separation holds only if the author holds it, and a breach is
disclosed in full, never hidden.

## What the author works from

- **Declarations are supplied, not looked up.** Every public declaration the author needs is
  given inline and verbatim: constructors with full parameter lists and defaults, method
  signatures, constants, enumeration values, and any existing documentation comment that carries
  an oracle or an invariant, including validation rules a fixture must satisfy. These declarations
  are the only view of production code.
- **A missing or non-compiling declaration is asked for.** Never reconstruct the corrected shape
  from context, from a compiler message that quotes surrounding source, or from a diff.
- **Public evidence may settle an ambiguity.** An ambiguity that public, non-implementation
  evidence resolves (a tool's declared parameter shape, an existing test harness, documentation)
  may be settled by the author, if the account states the evidence used; a reading without
  its source cannot be tested.
- Test files, fixtures and harnesses stay fully readable throughout.

## What counts as a breach

Opening any production source file by any means, for any reason, including "just the signature".
Every way of reading a file counts: file viewers, text search, directory previews, command-line
printing, diffs, and history shown with patches. The same holds for the implementer's diff, commit
or log on the branch. Only the names of changed files may be listed, never their contents or their
patches.

## On a breach

A breach, deliberate or accidental, is disclosed in full: say exactly what was read. Never hide
it, never trim it, and never describe an author who read the implementation as independent.

## Ambiguity

When a scenario's expected result is genuinely unclear, raise it. Never settle it by reading the
implementation: that is the shortcut this discipline exists to close. Record what was unclear and
what each candidate resolution would require. The only carve-out is the public-evidence case
above. If an ambiguity truly cannot be raised and must be settled by consulting the
implementation, mark the resulting scenario as oracle-degraded rather than folding it into an
overall verdict of independence.

## Same-author work

When no second author exists and one author writes both the code and the tests, the separation
that remains is one of time. Write each test from the specification, before the fix exists, and
see it fail first. An "I avoided looking" claim is not a substitute and is never recorded as one.
Keep the oracle boundary regardless: expected values come from the specification written before
the code, not from the code just written. Never claim independence for this work; call it what it
is, separation by time only.
