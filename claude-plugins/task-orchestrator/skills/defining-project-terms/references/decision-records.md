# Decision records

A decision record is a short note that explains a choice so that a later reader does not undo it by mistake. It sits beside the glossary or the code it explains, one note per decision. The practice of keeping one small dated note per significant decision comes from Michael Nygard, "Documenting Architecture Decisions" (2011).

## When a decision earns a record

Record a decision when a reader who does not have the reasoning would likely undo it. Picture the next person, or agent, meeting the choice cold: if they would tidy it away, "fix" it or replace it with the obvious alternative, and doing so would cost something they cannot see, write the record. If they would leave it alone or the code explains itself, a comment or nothing is enough; a record for a choice nobody would question is noise that hides the ones that matter.

## What a record holds

The choice in one sentence, the force that led to it (a constraint, a failure that was seen, a cost), the alternative that was rejected and why, and when to reopen it if there is a known trigger. Give the reason, not a defence of the choice.

## Handling

- A record is not deleted when it is overtaken; mark it superseded and point to the replacement, so the reasoning stays findable.
- Work that would conflict with a record is raised with the owner before it is done, not decided silently either way.
- A term decision (a rename, a split of a word) goes in the glossary entry (Don't call it, Distinct from); it needs a separate record only when the reason would not fit in an entry.
