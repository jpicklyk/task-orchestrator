# Scenarios and oracles

## Deriving scenarios

- Derive numbered scenarios from the stated requirements. Partition them into happy, failure and
  edge cases. A requirement that yields only a happy-path scenario has not been fully
  partitioned.
- Number scenarios stably (S1, S2, ...). Never renumber once test code refers to an identifier.
  A dropped scenario is marked dropped and keeps its number.
- Label each scenario with the kind of surface it touches where the specification states which
  declarations are new, and say so where it does not:
  - **Existing surface**: every declaration the scenario touches predates the fix. Reverting the
    fix yields real behavioural red.
  - **New surface**: the scenario binds to a declaration the fix introduces. A plain revert
    produces only a compile failure, which proves nothing about behaviour. Such a scenario needs
    the narrowest revert that still compiles (typically: keep the new type or parameter and revert
    only its call sites), or, where no such revert can work, an explicit substitute verification
    named beforehand rather than improvised later.
- The label comes from the specification. An author blind to the implementation cannot always
  tell which surfaces are new: where the specification does not say, mark the scenario "surface not
  stated" and say so, never guess.

## Oracles

Every scenario's expected result has a stated source, one of:

- A specification clause, cited by its identifier: "per the requirement text, S3: duplicate
  entries are rejected with a duplicate error".
- An external reference, cited by name and section: "per RFC 7396 section 2: a merge-patch null
  removes the key".
- An independent computation: "computed independently: SHA-256 of the canonical byte sequence,
  verified against a second implementation run outside the codebase".

"Matches current behaviour" and "see implementation" are not oracles.

- Never open the implementation to decide correctness. If you catch yourself checking what the
  code returns and then asserting that value, stop: the test has become a change detector for
  whatever the code does today, bugs included.
- Never copy a worked example uninspected. Recompute an illustrative example independently and
  cite the rule, not the illustration.
- A new surface does not relax any of this. Derive the expected result from the clause that
  motivated the new surface. A new surface with no oracle available outside the implementation
  is a specification gap to raise, not a licence to read the implementation.
