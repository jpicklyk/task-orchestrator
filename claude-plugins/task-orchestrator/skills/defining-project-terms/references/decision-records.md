# Decision records

A decision record is a short note that explains a choice so that a later reader does not undo it by mistake. It sits beside the glossary or the code it explains, one note per decision.

## When a decision earns a record

Write one only when all three hold:

1. A reasonable reader would find the choice odd or would be tempted to change it.
2. The reason is not visible in the code, the tests or the glossary.
3. Changing it would be expensive or would break something that is not obvious.

If any of the three fails, a comment or nothing is enough. A record for a choice nobody would question is noise that hides the ones that matter.

## Contents

- **Decision**: what was chosen, in one sentence.
- **Why**: the force behind it (a constraint, a failure that was seen, a cost), not a defence of the choice.
- **Instead of**: the alternative that was rejected and the reason.
- **Revisit when**: the condition under which the decision should be reopened, if there is one.

## Handling

- A record is not deleted when it is overtaken; mark it superseded and point to the replacement, so the reasoning stays findable.
- Work that would conflict with a record is raised with the owner before it is done, not decided silently either way.
- A term decision (a rename, a split of a word) goes in the glossary entry (Don't call it, Distinct from); it needs a separate record only when the reason would not fit in an entry.
