# review-cited-claims

A claim in a plan, a doc, a changelog or a header comment is only as good as the line it cites.
Authors paraphrase from memory and cite a line they never opened; a reviewer who trusts the
citation inherits the error.

1. **Open every cited line.** Where a plan, doc, changelog entry or new comment states
   nullability, redaction, ownership or an honest limit, open each `file:line` it cites and confirm
   the code establishes the claim as worded. Reading the surrounding prose is not confirmation.
2. **An uncited claim of that kind is an observation.** Record it, and ask the author for the
   citation. One the cited code contradicts is blocking.
3. **A finding about a factual claim names its key nouns and lists every other occurrence.** Take
   the reviewed range (`git diff --name-only <base>...HEAD`) and grep the repository for the same
   nouns. List each sibling occurrence as `file:line`, so the fix covers the claim everywhere it
   is stated, not only where the reviewer happened to look.
4. **Authors hold their own notes to the same standard.** When a plan or note asserts one of those
   properties, it names the `file:line` that proves it, since the reviewer will go and read that
   line. For a surface that does not exist yet, cite the decision that specifies it and say so.

## claude:

- The findings register is the item's `review-checklist` when its schema declares one, otherwise
  the first review note you fill. Number each finding once there (`B1`, `O1`); every other review
  note refers to a finding by id (`see B2`) and never restates it. The register note's own guidance
  says what else it holds.
