---
name: decomposing-work
description: "Decides how a planned scope is cut into pull requests and work items: where a PR boundary goes, how big one item is, which dependency edges are real, and what a breakdown records. Use when cutting an approved scope into PRs and items, when judging a proposed cut, or when asked whether work should ship as one PR or several."
user-invocable: false
---

# Decomposing work

A cut answers three questions: how many pull requests, which items, and in what order. Make the cut once per planned scope, before the work starts, and record it where the plan is approved so a reviewer can challenge it.

Where the project sets its own rules for PR boundaries or item limits, they take precedence over every criterion below.

## 1. A step is not a PR

- A **step** is one dependency layer (a wave, section 4) and protects accuracy. Every item in it ends green with its commit; the step ends with the test suite green, the project's guards passing and a commit on the shared branch. Then check the base: integrate the moved main line before the next step starts. Prefer a merge over a rebase when anything records the branch's commit ids (review records, logs, references in documents), since a rebase rewrites them.
- A **PR** exists for the main line: it pays one CI run, one review and one merge.
- Default: one planned scope is one PR. The steps inside it are commits, not PRs.

## 2. A PR boundary needs a named reason

Divide a scope into several PRs only where the boundary cites one of these:

1. Later work outside the scope, or a release, needs this state on the main line.
2. CI checks something the local suite does not, and later steps build on it.
3. The plan depends on what this step shows (a measurement, a spike). A boundary whose outcome decides the rest of the plan is this reason: finding out late that it failed costs more than an early PR.
4. A mechanical diff is large enough to bury a behaviour change for the reader of the PR. This is the PR's own human review, not an item's review verdict. Moves and behaviour changes kept in separate items are enough when the move's diff stays small enough for that reader to review; the move gets its own PR only when it does not.
5. Other work landing on the same files makes a long branch costly.
6. The state must be released and observed before what depends on it (a data migration).

The scope's overall size, the number of waves and wanting a checkpoint are not reasons. Each boundary is written with its reason; a cut with no boundary is written as "One PR".

## 3. An item is one implementer's unit that ends green

An item has one context an implementer can hold, one acceptance set, one review verdict and one commit after which the tree builds and the guards pass.

**Split an item when**
- it carries more than one behaviour change;
- it touches more files that need judgement than one context can hold (when the project sets a file limit for one implementer's unit, use it; this skill adds no number);
- a reviewer could not give it one verdict.

**Merge items when**
- the fixed cost of an item (its planning, its records, its review, its tests) exceeds the work;
- one cannot be green without the other;
- they share files and would run in the same wave (or order them with an edge instead).

Size by judgement, not volume. A scripted move across many files is one item; a semantic change across a few files may be two.

## 4. Waves are derived, not designed

- Add an edge only for a real dependency: B needs something A produces, or B cannot be reviewed until A exists. Ordering for taste is not a dependency.
- A wave is a dependency layer: the items with no unmet edge. List the items, add the edges, then read the waves off.
- Items that share files and sit in one wave are merged or ordered with an edge.
- More layers do not mean more PRs. Waves run inside one PR unless section 2 applies.

## 5. What a breakdown records

- PR boundaries, each with its reason from section 2, or "One PR".
- Items: type and a one-line scope each.
- Edges, each with the reason it exists.
- Waves, written as the derived layers.
- Files shared by more than one item, and how each is resolved (merged or ordered).

## Checklist

- [ ] Default is one PR; every boundary names a reason from section 2.
- [ ] No boundary rests on the scope's overall size, wave count or wanting a checkpoint.
- [ ] Each item is one context, one acceptance set, one verdict, one green commit.
- [ ] No item is too small to carry its own fixed cost.
- [ ] Every edge is a real dependency; waves are derived from them.
- [ ] No two items in one wave edit the same file.
- [ ] The cut is recorded in the shape of section 5.

## Worked example

A refactor moves a body of code into a new package layout and then tightens the rules around it. The move is a scripted rename of files and imports, a diff the reader of the PR can follow in one sitting.

- **Items:** (A) one mechanical-move item, scripted across the files and judged by "the tree still builds and behaviour is unchanged"; (B1..Bn) one semantic item per package, each making that package's own changes; (C) one rules-and-guards item that adds the checks which keep the new layout in place.
- **Edges:** A before every B, because the semantic work edits the moved files; every B before C, because the guards would fail until each package is done. The B items touch different packages, so no edge among them.
- **Waves (derived):** {A}, then {B1..Bn}, then {C}.
- **PR boundaries:** One PR. Reason 4 was considered and rejected: the moves and the semantics are separate items, and the move's diff is small enough that it does not bury the semantic changes for the reader of the PR. Nothing else on the list applies. Had the move's diff been too large to review beside the behaviour change, reason 4 would have made it its own PR.
