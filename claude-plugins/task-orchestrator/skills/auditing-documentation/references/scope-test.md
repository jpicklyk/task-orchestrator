# The scope test

Run this on each section of a living doc. Records are not tested here.

## The one-minute test

Could a reader with the code and a search tool find this out in a minute? If yes, the section restates the code and is a candidate to cut. The cut needs no evidence that the text is wrong.

## What earns a place

| Keep | Because the code cannot say it | Source |
| --- | --- | --- |
| The why: decisions, rejected options, consequences | Code shows the choice that was made, not the reason or the alternatives | Nygard, "Documenting Architecture Decisions" (2011): a decision is worth recording with its context and consequences |
| Invariants and absences ("this layer never reaches that one") | An absence leaves no line to read | matklad, "ARCHITECTURE.md" (2021): invariants are often an absence, and absences are hard to see in code |
| Traps: places where reading the code misleads, a single place an object must be built | Reading the code is what leads people wrong there | Ousterhout, *A Philosophy of Software Design*, the chapter on comments: write down what the code cannot make obvious |
| Knowledge from outside the project: verify commands, deploy steps, process | It is not in the tree | Anthropic, Claude Code documentation on memory files: they earn their place by carrying what the agent cannot infer from the files |
| A coarse codemap by searchable name: one line per module, naming the module and its important types | It orients a reader at the price of one line each | matklad, "ARCHITECTURE.md" (2021): a short map of modules and key types, by name so a search finds them |
| User tasks: getting started, how-to, troubleshooting, the public surface | Users do not read the source | Procida, Diataxis framework: tutorials and how-to guides serve what a user is trying to do |

A stated why stays even when it sits next to text that is cut. Say in the report that you kept it.

## Findings on the cutting side

- The document's subject (a module, a feature, a command) is gone: move is to cut the document.
- Two documents cover one topic and neither is the canonical home: move is to consolidate into one and point the other at it.
- No reader task or decision can be named for a long document: move is to cut it, or to split it by audience when two readers can be named.
- An agent file that only grows: the cut list is the report.
- A tour of files or classes, or an account of how a code path runs: replace with the invariant or decision behind it, or cut.

## Type, audience and home

Each document declares one type, one audience and one canonical home. A document mixing a tutorial with reference, or serving a user and a contributor at once, is a finding: split by audience. A fact stated in two documents with no canonical home is a finding: consolidate.

## Agent instruction files

An agent reads the file every turn, so it is the strictest case.

- Length: past about 200 lines, ask what an agent could discover itself. Move: cut.
- An overview of the project or a tour of the tree: an agent has the tree. Move: cut the whole section.
- Two lines that disagree: read the file as a whole, not line by line, and compare every instruction that touches the same command or area. Report both lines. Move: keep the one that is right and cut the other.
- A path or command that no longer exists: report it with the search that found nothing. Move: cut it, or point at what replaced it.
- A rule that must always hold: prose cannot enforce it. Move: propose an automated check. Once the check exists, shrink the line to a pointer that says where the check lives; the rule is stated once, and the check is what enforces it.
- No verify command: a gap, covered by step 5 of the method.
