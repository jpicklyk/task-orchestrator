# Design signals for existing code

What is visible, in the code or its history, when the shape of existing code costs something. The check numbers refer to the seven checks of the designing-modules skill, which holds the reasoning, the legitimate exceptions and the finding format. Apply the signals to the hot spots only.

A signal is a candidate until it is tied to a cost: a change that was, or will be, expensive because of it. Without that tie, drop it.

| Signal | How to see it | Check |
|---|---|---|
| One decision known in several files | the same key, format, table or status value built or parsed in more than one place | 1 |
| Files that move together | history shows the same files in most commits, across a module boundary | 1 |
| A layer that only forwards | a wrapper whose body is one call with the same arguments; a surface as large as what it hides | 2 |
| One idea in many tiny pieces | following a behaviour opens five files of a few lines each | 2 |
| Structure with no named reason | an interface with one implementation, a factory with one case, an option nobody sets, and no dependency rule or test fake that needs it | 3 |
| Dependencies pointing outward | core logic importing transport, storage or framework types; import cycles between packages | 4 |
| State with several writers | one field written from many modules; the same invariant checked at each layer | 5 |
| Errors passed along or hidden | log-and-continue, a default that masks failure, the same error re-wrapped at every layer | 6 |
| A surface bent for each caller | flags and mode parameters added one caller at a time; old and new versions side by side | 7 |

## Reading for cost

Before keeping a signal, find one of:

- a recent change that had to touch several places for one reason;
- a bug fixed in one copy and later fixed in another;
- how many call sites or files the signal reaches;
- a test that needs heavy setup only because of the shape.

Churn makes each of these dearer; a signal in a file nobody has touched in a year is Watch material at most.

## Do not report

- A shape the project documents as intended (a convention, a decision record).
- A single-implementation port that a dependency rule requires or a test fake uses.
- Duplication of two or three similar lines with no sign of drifting apart.
- Anything you can only describe as "feels messy".
