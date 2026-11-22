---
name: auditing-codebase
description: "One broad pass over code that already exists: finds where the codebase costs the most to change, using version-control history to pick the places to look, then applies design signals, the slop catalog and a glossary-drift check there, and ranks the candidates Act now, Plan it or Watch. Use when asked to audit, assess the health of or find refactoring candidates in a codebase, a package or a path, and as the method for an auditor reviewing one scoped area."
argument-hint: "[path or area to audit; default: the whole repository]"
---

# Auditing a codebase

An audit answers one question: where would effort spent on the structure of this code pay back soonest? It looks at code that exists, so it is a different job from reviewing a change or planning one. It is broad and rare; a review of one diff stays targeted and does not run this method.

The audit reports candidates. It files no work items and edits no source: each candidate is written so that someone else can turn it into one item.

## Scope first

The scope is the path or area you were given, or the whole repository when none was. An auditor handed one area audits that area only, and says in the report what it did not look at. Do not widen the scope to look thorough, and do not shrink it because the tree is large: the next step is what makes a large tree tractable.

## Method

1. **Read what the project says about itself.** Its written conventions, its glossary and its decision records (often a `docs/decisions` or `adr` folder). They set what counts as a problem here. A documented decision is a reason, not a finding.
2. **Find the hot spots.** Rank files by how often they change, then find files that change together. Code that is ugly but never touched costs nothing; code that changes every week costs every week. Drop churn that is there by design (the list is in hotspots). Every other file can be a hot spot, whatever its kind: a configuration table, a registry, prompt or skill text, or a document that every change has to edit is ranked like code when its history shows a cost of its own. A file that changes only because one hot file changed is that file's partner. The hot set is judged against the whole repository even when the scope is one area. Commands, the partner test and the hot set: [hotspots](references/hotspots.md). With no history (a fresh clone with one commit, an export), say so and fall back to size and fan-in, and mark every ranking as lower confidence.
3. **Look closely at the hot spots only.** For each, in order of churn, read it and apply the design signals in [design-signals](references/design-signals.md) and the entries of [slop-catalog](references/slop-catalog.md) that apply to code that exists. A file outside the hot set gets a look only because a hot file depends on it or changes together with it; it is named on the hot file's line as a partner, and is not ranked as a candidate of its own.
4. **Check vocabulary drift.** See the next section.
5. **Weigh each candidate against the project's own record.** If a decision record, a convention or a comment explains the shape, drop the candidate, or keep it only with the decision's cost shown: what that choice now makes expensive, with evidence from the history. A candidate that touches a record is flagged as such on its line, whichever way you rank it.
6. **Rank.** Sort and rate by [ranking](references/ranking.md), and write the output below.

## Vocabulary drift

Run this only when the project has a glossary. Otherwise write "no glossary" in the report and move on. Check, in the hot spots first:

- **Terms absent from code.** A glossary term whose code name nothing uses. Either the code moved on or the entry is stale.
- **Words without a term.** A domain word that recurs across hot-spot files, in names or comments, and has no entry. The concept is in use and nobody owns its meaning.
- **Rejected words in use.** A word an entry lists under `Don't call it` appearing in code, tests, comments or docs. Count the sites; a rejected word in a hot spot is the strongest case.
- **Entries that point at nothing.** An `In code` name that no longer exists.

Report each as a candidate with the sites found. Fixing vocabulary is usually cheap, so these rarely rank above structural findings in a hot spot; they rank high when the same file already needs rework, because renames then ride along with it.

## Output

One candidate per line or short block, in rank order, each proposable as a single work item:

- **Rank and rating:** Act now, Plan it or Watch.
- **Location:** file or directory, and symbol where it helps.
- **What and why:** the signal or catalog entry, and the concrete cost with its evidence (change count over a period, files that change together, duplicate sites).
- **Move:** the smallest restructuring that removes the cost.
- **Records:** a decision or convention it touches, if any.
- **Confidence:** high, medium or low, and what would change it.

If the request names an output file or line format, use that format and keep the same content in it. Close with the scope covered, the history window used, and anything you could not check. Do not pad the list: three well-evidenced candidates beat ten guesses, and an area with nothing worth acting on gets a short report that says so.

## What an audit is not

It is not a style review, a bug hunt or a security review. A defect you trip over goes in one line under "also noticed". It does not run tests or the build unless asked, and it never modifies the code it reads.
