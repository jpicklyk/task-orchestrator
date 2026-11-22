# Entry shape

One heading per term. Write the meaning first; add a field only when it carries something the meaning does not.

| Field | Required | Holds |
|-------|----------|-------|
| Meaning | yes | one or two sentences saying what the thing is |
| Distinct from | no | the nearest neighbour and the line between the two |
| Don't call it | no | words not to use; old names after a rename |
| In code | no | the symbol or folder name |
| Example | no | the case that settled the meaning |

## Worked entry

```markdown
## Order

A customer's confirmed request to buy one or more items. It is created at checkout and kept for the life of the account.

Distinct from: Cart. A cart is items the customer has gathered; it becomes an order at checkout and is then discarded.
Don't call it: purchase, transaction, sale. ("Purchase" was the old name in the reporting module.)
In code: `Order`, `orders/`.
Example: a customer who abandons checkout has a cart and no order, so the history screen shows nothing for that visit.
```

## Wording rules

- Define by what the thing is and does, not by how it is stored. "A row in the orders table" is an implementation note, not a meaning.
- Do not define a term with its own word or only with a synonym.
- Use no word in the meaning that itself needs an entry the reader does not have.
- Keep the Example to the one case that settled the question; two examples are a tutorial.
- A rename leaves the old name under Don't call it for as long as it could still be typed from habit or found in old notes.
- An entry that stops being true is edited or removed in the change that makes it false.
