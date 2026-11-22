# Entry shape

One heading per term. Write the meaning first; add a field only when it carries something the meaning does not.

| Field | Required | Holds |
|-------|----------|-------|
| Meaning | yes | what the thing is, in as few words as keep it exact |
| Distinct from | no | the nearest neighbour and the line between the two |
| Don't call it | no | words not to use; old names after a rename |
| In code | no | the symbol or folder name |
| Example | no | the case that settled the meaning |

## Worked entry

A community lending library, where "reservation" was used for two things and the project settled on one word for each.

```markdown
## Hold

A member's place in the queue for a title whose copies are all out. A hold exists without any copy and ends when a returned copy is set aside for that member.

Distinct from: Loan. A loan is a specific copy in a member's hands with a due date; a hold has neither copy nor date.
Don't call it: reservation, booking. ("Reservation" was the old name in the catalogue import.)
In code: `Hold`, `holds/`.
Example: a member who joins the queue for a title with every copy out has a hold and no loan, so their "borrowed" page shows nothing for that title until a copy comes back.
```

## Wording rules

- Define by what the thing is and does, not by how it is stored. "A row in the holds table" is an implementation note, not a meaning.
- Do not define a term with its own word or only with a synonym.
- Use no word in the meaning that itself needs an entry the reader does not have.
- If the meaning needs a paragraph, it is probably two terms; separate them (see [testing a term](testing-a-term.md)).
- Keep the Example to the one case that settled the question; two examples are a tutorial.
- A rename leaves the old name under Don't call it for as long as it could still be typed from habit or found in old notes.
- An entry that stops being true is edited or removed in the change that makes it false.
