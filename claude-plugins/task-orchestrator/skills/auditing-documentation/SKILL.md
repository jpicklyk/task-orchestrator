---
name: auditing-documentation
description: "One broad pass over the documents a project already has: judges each against the reader it serves, finds where it restates the code, drifts from it or leaves a real gap, and ranks the candidates Act now, Plan it or Watch. Reports candidates and edits no file. Use when asked to audit or assess the documents a project has: READMEs, guides, user docs or agent instruction files such as CLAUDE.md or AGENTS.md, and as the method for an auditor reviewing one scoped set of document files."
argument-hint: "[path or documents to audit; default: every document file in the repository]"
---

# Auditing documentation

Three standing instructions come before any method. Keep them in force through the whole audit.

1. **Audience first.** Judge every document against the reader it serves: a user, a contributor or an agent that reads it every turn. The goal is a narrow document that reader can trust, not a document that mirrors the code. A living doc owes accuracy where it makes a claim; it never owes coverage.
2. **Order of moves.** Cut, point at the source, generate, consolidate into the canonical home, rewrite for the audience, update, add what is missing. Take the earliest move that ends the cost. A cut needs no proof that the text is wrong: less text narrows the reader's focus, and a true claim that the code already holds is still a cost, because it goes false when the code changes. Update comes late because keeping a restatement fresh is a standing price.
3. **Living docs and records.** A living doc makes claims about the system as it is. A record states what was decided or true at a point in time: a decision record, a changelog, release notes. A record gets no drift finding (an old name in it is history) and is never proposed for an edit. When the code contradicts an accepted record, report it to the owner as a finding, and the move is to fix the code or supersede the record.

## Scope first

The scope is the path or set of documents you were given, or every document file in the project when none was. An auditor handed one set audits that set only, and says in the report what it did not look at. Document files are yours. Comments inside source files belong to the code audit, so leave them. So does text the project ships to drive an agent: skill files and their references, agent definitions, rules and prompt strings. It reads like prose but behaves like code. A file that tells an agent about the project it works in, such as CLAUDE.md or AGENTS.md, is a document and stays yours. A generated page is a document file: judge whether it serves its audience, not whether it is fresh. When a generated file has no freshness check, the finding is to add one. Never regenerate it or diff it to prove it fresh.

## Method

1. **Read what the project says about itself.** Its conventions for documents, its glossary and its decision records. They set what counts as a problem here, and a stated reason is a reason, not a finding.
2. **Classify each document.** Living doc or record; its type (tutorial, how-to, reference, explanation, decision or agent instructions); its audience; its canonical home. A document that cannot name these is a finding of its own. Each document declares one type, one audience and one canonical home. A document mixing a tutorial with reference, or serving a user and a contributor at once, is a finding: split by audience. A fact stated in two documents with no canonical home is a finding: consolidate. Source: Procida, Diataxis framework: documentation is divided by what the reader is doing.
3. **Apply the scope test to every section of a living doc.** Could a reader with the code and a search tool find this in a minute? If so, it restates the code and is a candidate to cut, with no evidence that the text is wrong. Two cuts are easy to get wrong, so check them on every proposal:
   - **Keep the sentence that carries the why.** When a section is cut but one sentence in it gives a reason, a rejected option or a trap, the proposal keeps that sentence, and the report says it kept it. Keep a stated why even when the code does not show its subject at all.
   - **An overview or tree tour in an agent file is cut whole.** An agent has the tree; do not propose to trim or refresh it.

   What earns a place is in the table below. When one is tempting to flag, leave it and say so in the report. The cutting-side findings and the agent-file checks come after the table.
4. **Check for drift.** Count, default, limit, list, inventory, signature, walkthrough, snippet, link and duplicate each have one move (the drift table below). One move is easy to get wrong: **a copied default or limit is moved to the file that defines it and pointed at**. It is an Act now move, not a Watch, even when the copy is accurate today. Then run the history checks, the dangling-name check and the sample of claims below, and read the vocabulary section.
5. **Check for gaps.** An undocumented decision, an invariant no document states, a user task with no how-to, an agent file with no verify command (the four gaps below). Move: add what is missing, and keep it as small as the cut moves are.
6. **Look at the whole set.** A large set rarely needs line edits first. Ask whether a document should be restructured, merged into another, split by audience or retired. These are candidates of their own. When a whole document is proposed for a cut or retirement, fold its line-level findings into that one candidate.
7. **Rank.** Rate by the definitions below, and write the output.

## What earns a place

Run the scope test on living docs only; records are not tested here.

| Keep | Because the code cannot say it | Source |
| --- | --- | --- |
| The why: decisions, rejected options, consequences | Code shows the choice that was made, not the reason or the alternatives | Nygard, "Documenting Architecture Decisions" (2011): the short decision record, which keeps what was chosen beside the pressures behind it and what it costs |
| Invariants and absences ("this layer never reaches that one") | An absence leaves no line to read | matklad, "ARCHITECTURE.md" (2021): a rule about what a part must never touch leaves no line to point at, so the document has to carry it |
| Traps: places where reading the code misleads, a single place an object must be built | Reading the code is what leads people wrong there | Ousterhout, *A Philosophy of Software Design*, the chapter on comments: prose pays for itself where reading the code would leave a reader guessing or wrong |
| Knowledge from outside the project: verify commands, deploy steps, process | It is not in the tree | Anthropic, Claude Code documentation on memory files: the file is for facts an agent would otherwise miss because they are not in the tree |
| A coarse codemap by searchable name: one line per module, naming the module and its important types | It orients a reader at the price of one line each | matklad, "ARCHITECTURE.md" (2021): his codemap section, kept coarse and written as names to search for, not links |
| User tasks: getting started, how-to, troubleshooting, the public surface | Users do not read the source | Procida, Diataxis framework: a task guide is its own page |

A stated why stays even when it sits next to text that is cut.

## Findings on the cutting side

- The document's subject (a module, a feature, a command) is gone: move is to cut the document.
- A living doc that announces something no longer exists ("X was removed", "no longer supported"): move is to cut the note. A note about an absent thing is still a claim about it, and the changelog holds the history. A record keeps such a line.
- Two documents cover one topic and neither is the canonical home: move is to consolidate into one and point the other at it.
- No reader task or decision can be named for a long document: move is to cut it, or to split it by audience when two readers can be named.
- An agent file that only grows: the cut list is the report.
- A tour of files or classes, or an account of how a code path runs: replace with the invariant or decision behind it, or cut.

## Agent instruction files

An agent reads the file every turn, so it is the strictest case.

- Length: past about 200 lines, ask what an agent could discover itself. Move: cut.
- An overview of the project or a tour of the tree: an agent has the tree. Move: cut the whole section.
- Two lines that disagree: read the file as a whole, not line by line, and compare every instruction that touches the same command or area. Report both lines. Move: keep the one that is right and cut the other.
- A path or command that no longer exists: report it with the search that found nothing. Move: cut it, or point at what replaced it.
- A rule that must always hold: prose cannot enforce it. Move: propose an automated check. Once the check exists, shrink the line to a pointer that says where the check lives; the rule is stated once, and the check is what enforces it.
- No verify command: a gap, covered by step 5 of the method.

## Drift classes

Content that restates something the code holds changes whenever the code does. Each class has one move. Take the earliest move that ends the cost; even a copy that is accurate today is a finding.

| Content | Move | Source |
| --- | --- | --- |
| A count ("N tools", "N hooks") | Cut | Hunt and Thomas, *The Pragmatic Programmer*, the DRY principle: a fact is stated in one place |
| A default, limit, enum list or version number | Point at the file that defines it, stated once; or generate the block | Hunt and Thomas, same book: repeated knowledge drifts apart |
| A signature, parameter list or option table | Generate it from the source | Hunt and Thomas, same book: derive reference text from the source rather than writing it twice |
| A file, directory or class inventory | Replace with a codemap of searchable names, or cut | matklad, "ARCHITECTURE.md" (2021): a coarse map changes less often than a detailed inventory |
| A step-by-step account of a code path | Replace with the invariant or decision behind it | Ousterhout, *A Philosophy of Software Design*: text that repeats the code adds a second thing to keep true |
| A copied snippet or example | Make it tested or extracted, or point at a real test | The rustdoc Book, documentation tests: an example that fails to build or run fails the suite |
| A link to a file and line | Use a searchable symbol name | matklad, "ARCHITECTURE.md" (2021): a symbol name still finds its target after a file moves, a link does not |
| The same fact in two places | Keep one canonical home and point the others at it | Hunt and Thomas, same book: name the single owner of a fact and let the rest refer to it |

When a value is copied into several documents, report it as one candidate with every site, and name the home it should be stated in.

### History checks

Use these when the project has history. With none, say so and mark the ranking lower confidence.

- **Stale document.** The document's last-touch date against the code's commit count since: `git log -1 --format=%cs -- <doc>`, then `git rev-list --count --since=<that date> HEAD -- <code path it describes>`. Many commits since the last touch suggests claims that went false. Source: Tornhill, *Your Code as a Crime Scene*, on reading change history as evidence.
- **Cost document.** Files that change in the same commits as the doc: `git log --format=%H -n 100 -- <doc>`, then list the files each of those commits touched. A document that changes with the same code in most commits restates what it should point at. Source: Tornhill, same book, on files that change together.

### Dangling names

For every code name and path a document puts in a code span, search the source for it. Report each one with no hit, with the document line and the search you ran. Names the project defines outside source (commands, configuration keys) are searched where they are defined. This is an instruction to the auditor, not a script.

### Examples and claims

- A fenced example that no test runs will drift quietly. Note it, and name a test or extraction as the move.
- Sample the concrete claims a document makes (names, paths, numbers, behaviour) and verify a handful against the code. Report what the sample found and how large it was.

### Vocabulary

A word the project's glossary rejects, used in a living doc to name that entry's concept, is reported here. A record may keep the old word. The same word in another sense is not drift. Glossary entries checked against code are not part of this audit: list them under what you did not check.

### Checks the project may lack

When the project has no reference check or link check on its documents, that is a recommendation, not a defect. A passing check does not prove a page is accurate.

## Gaps

A gap is something a reader needs and no document holds. Report one only when a reader would pay for its absence; a section nobody needs is not a gap. The move for each is to add what is missing, kept as small as a cut would leave it.

- **A non-obvious decision with no record.** The code does something a reader would undo or doubt, and no document gives the reason. Move: add a short decision record, or one line in the document that owns the area. Source: Nygard, "Documenting Architecture Decisions" (2011).
- **An invariant a guard enforces and no document states.** A test, a lint rule or a hook fails on a rule that no document names. Move: state the rule once, in the home for contributors, naming the guard. Source: matklad, "ARCHITECTURE.md" (2021), on stating invariants.
- **A user task with no how-to.** A task users do through the public surface has no page. Move: add the how-to, not a tour of the surface. Source: Procida, Diataxis framework, on how-to guides.
- **An agent file with no verify command.** An agent cannot tell how to check its own change. Move: add the one command, in the agent instruction file. Source: Anthropic, Claude Code documentation on memory files, on listing the commands an agent needs.

A gap found by reading code only is a lower confidence finding. Ask whether history shows people asking, or reverting, because of it.

## Not findings

- An old name or number inside a record: it is history.
- A stated why, an invariant or a trap that the code cannot show, even when no line of code mentions its subject.
- A short document that serves its reader: thin is not a defect, and a missing section nobody needs is not a gap.
- A block a tool generates from the code and a check keeps fresh: it is a pointer, not a restatement.
- A convention the project documents on purpose, such as a long reference page for users: judge it by its audience, not by length.

## Ratings

- **Act now:** the cost is shown (a false or dangling claim, a dead path, a contradiction, a copied value the code holds) and the move fits one item.
- **Plan it:** a document-level move, a record the code contradicts, or a move that needs an owner's choice.
- **Watch:** thin evidence. Name what would promote it.

## Output

One candidate per line or short block, in rank order, each proposable as a single work item:

- **Rank and rating:** Act now, Plan it or Watch.
- **Location:** file and line, or the document or section.
- **What and why:** the signal and the concrete cost, with evidence: the code that disagrees, the copies, the history.
- **Move:** the earliest move from the order above.
- **Records:** a record it touches, if any.
- **Confidence:** high, medium or low, and what would change it.

If the request names an output file or line format, use that format and keep the same content in it. Close with the scope covered, the history window used, and anything you could not check. Do not pad the list: three well-evidenced candidates beat ten guesses, and a set with nothing worth acting on gets a short report that says so.

## What an audit of documents is not

It is not copy editing: a typo or an awkward sentence goes in one line under "also noticed". It edits no file, including the documents it reads. It does not check glossary entries against code; that belongs to the code audit, which also owns comments in source.
