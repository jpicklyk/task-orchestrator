# The architecture pass

Hot spots show where change is dear in single files. This pass looks at the shape between files: whether the modules depend on each other the way the project says they should, and whether the same job is done one way or several. It reads the numbers from [measuring](measuring.md) and runs after the close look at the hot spots.

An auditor scoped to one area runs the pass on that area's modules, and names the modules outside it that they depend on without auditing them.

## 1. State the intended architecture

From the project's records (conventions, decision records, guard tests, the layout the documentation describes), write down in a few lines what the architecture is meant to be: the layers or modules, which direction dependencies may point, who owns shared state. Quote the source of each statement. A rule that a test enforces is stated more firmly than one a document merely says.

When the records state none, write `no stated architecture` and judge only cycles in step 2: a dependency cycle needs no stated architecture to cost something.

## 2. Compare the graph with it

Lay the module matrix (fan-in, fan-out, instability, edges, cycles) beside the statement.

- A dependency that points the wrong way, a cycle, or a stable core that depends on a module that changes freely is a breach.
- A module that many others depend on and that is itself unstable (high instability with high fan-in) is where every change spreads.
- A cycle between modules that the statement says are separate is the strongest case.

Give each breach its cost, as the design signals require: change coupling across the same boundary, a recent change that had to touch both sides, or the number of modules a change reaches. A breach with no cost evidence is Watch material at most. A breach the records explain is weighed in the step that follows, not dropped here.

## 3. Take a pattern inventory

A pattern here is a recurring way of doing one job: how errors are reported, how a store is accessed, how configuration is read, how a new case is registered. For each recurring job, list the patterns in use, their instances, and where the instances diverge.

Two patterns for one job is a finding when the hot files carry both: every change in those files then has to choose between them, and a fix must be made twice. Two patterns that live only in cold files are noted and left. State which of the two the records prefer, if any; the move is usually to migrate the instances in the hot files first.

## Output

Each finding is a candidate in the usual shape (location, what and why, move, records, confidence), ranked by [ranking](ranking.md). Write the breached statement or the two patterns in the "what and why" and the cost evidence beside them.
