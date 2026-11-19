# Documentation

Code that changes behaviour leaves stale every description of the old behaviour: a document, a
comment beside the code, an example, a usage message. A stale description is worse than none,
because the next reader trusts it.

## What to update

- **Find what describes the behaviour you changed.** Search by the nouns of the behaviour (the
  names of the function, option, field, message or command) in documents, in comments and in
  examples, not only in the file you edited.
- **Update it in the same change.** A description of the old behaviour is corrected alongside the
  code that changed it, so the two never disagree in any revision.
- **Say only what is true.** A changed sentence describes what the code now does, checked against
  the code, not what it was meant to do.
- **Comments say why.** Where a comment beside the changed code only restated what the old code did,
  remove it or rewrite it to say why the code is the way it is.
- **When you do not update it.** If a description cannot be updated in this change (it lives
  elsewhere, or someone else owns it), state in the change's report which description is now stale
  and why it was not updated.

## Signs it was followed

- Every document, comment and example that described the changed behaviour is updated in the same
  change, or the change's report names it and says why it was not.
- No comment in the diff describes behaviour the code no longer has.
- Each changed sentence in a document matches what the code does.
