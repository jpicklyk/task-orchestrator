# Drift classes

Content that restates something the code holds changes whenever the code does. Each class has one move. Take the earliest move that ends the cost; even a copy that is accurate today is a finding.

## Contents

- The classes
- History checks
- Dangling names
- Examples and claims
- Vocabulary
- Checks the project may lack

## The classes

| Content | Move | Source |
| --- | --- | --- |
| A count ("N tools", "N hooks") | Cut | Hunt and Thomas, *The Pragmatic Programmer*: a piece of knowledge has one authoritative home |
| A default, limit, enum list or version number | Point at the file that defines it, stated once; or generate the block | Hunt and Thomas, same book: repeated knowledge drifts apart |
| A signature, parameter list or option table | Generate it from the source | Hunt and Thomas, same book: generate documentation from the code instead of keeping a second copy |
| A file, directory or class inventory | Replace with a codemap of searchable names, or cut | matklad, "ARCHITECTURE.md" (2021): keep the map coarse |
| A step-by-step account of a code path | Replace with the invariant or decision behind it | Ousterhout, *A Philosophy of Software Design*: text that repeats the code adds a second thing to keep true |
| A copied snippet or example | Make it tested or extracted, or point at a real test | The rustdoc Book, documentation tests: examples in docs run as tests |
| A link to a file and line | Use a searchable symbol name | matklad, "ARCHITECTURE.md" (2021): links to files rot, names can be searched |
| The same fact in two places | Keep one canonical home and point the others at it | Hunt and Thomas, same book: one fact, one place |

When a value is copied into several documents, report it as one candidate with every site, and name the home it should be stated in.

## History checks

Use these when the project has history. With none, say so and mark the ranking lower confidence.

- **Stale document.** The document's last-touch date against the code's commit count since: `git log -1 --format=%cs -- <doc>`, then `git rev-list --count --since=<that date> HEAD -- <code path it describes>`. Many commits since the last touch suggests claims that went false. Source: Tornhill, *Your Code as a Crime Scene*, on reading change history as evidence.
- **Cost document.** Files that change in the same commits as the doc: `git log --format=%H -n 100 -- <doc>`, then list the files each of those commits touched. A document that changes with the same code in most commits restates what it should point at. Source: Tornhill, same book, on files that change together.

## Dangling names

For every code name and path a document puts in a code span, search the source for it. Report each one with no hit, with the document line and the search you ran. Names the project defines outside source (commands, configuration keys) are searched where they are defined. This is an instruction to the auditor, not a script.

## Examples and claims

- A fenced example that no test runs will drift quietly. Note it, and name a test or extraction as the move.
- Sample the concrete claims a document makes (names, paths, numbers, behaviour) and verify a handful against the code. Report what the sample found and how large it was.

## Vocabulary

A word the project's glossary rejects, used in a living doc to name that entry's concept, is reported here. A record may keep the old word. The same word in another sense is not drift. Glossary entries checked against code are not part of this audit: list them under what you did not check.

## Checks the project may lack

When the project has no reference check or link check on its documents, that is a recommendation, not a defect. A passing check does not prove a page is accurate.
