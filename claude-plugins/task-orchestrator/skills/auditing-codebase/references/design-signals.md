# Design signals for existing code

A design signal is something visible in existing code, or in its history, that makes the next change dearer than it needs to be. Each row below gives what to look for, the evidence that turns it into a cost, and the move that usually removes it. Apply the signals to the hot spots only.

A signal is a candidate only once it is tied to a cost: a change that was, or will be, expensive because of it. Without that tie, drop it.

| Signal | Evidence of cost | Usual move |
|---|---|---|
| Knowledge copied | One fact (a format, a key layout, a list of states) spelled out in several files, and commits that edit those files together for one reason | Give the fact one home and have the other files ask it |
| Coupling across a boundary | Two files in different modules changing in most of the same commits, with or without an import between them | Move what both of them know to one side, or merge them |
| Layers that only hand on | Following one behaviour means stepping through wrappers that pass the same arguments along, and fixes land in the wrapper and the wrapped code alike | Remove the layer, or give it a job of its own |
| Scattered behaviour | One behaviour spread over many small files that always change together | Bring the pieces into one module |
| Flexibility that never paid | An interface, factory or option whose second case never arrived, so every change edits the indirection as well as the one real case | Collapse it to the one case, unless a dependency rule or a test fake needs the seam |
| Infrastructure reaching the centre | Domain code importing storage, transport or framework types, so a change at the edge reaches the core, and tests of the core need the edge running | Turn the dependency around behind an interface the core owns |
| Contested state | One value written from several places, and fixes to the same rule about it landing in more than one of them | Give the value one owner that enforces its rules |
| Errors carried by hand | The same failure checked, translated or logged at every layer, and error-handling edits spread across those layers | Handle it where something can be done about it; elsewhere let it pass |
| A shared surface bent per caller | A widely used function or type gaining a flag or mode for each new caller, so each new caller edits the shared code | Split the behaviours, or add a new surface beside the old one, move the callers, then remove the old |

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
