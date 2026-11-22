---
name: auditing-documentation
description: "One broad pass over the documents a project already has: judges each against the reader it serves, finds where it restates the code, drifts from it or leaves a real gap, and ranks the candidates Act now, Plan it or Watch. Reports candidates and edits no file. Use when asked to audit, assess or tidy documents, READMEs, guides, user docs or agent instruction files such as CLAUDE.md or AGENTS.md, and as the method for an auditor reviewing one scoped set of document files."
argument-hint: "[path or documents to audit; default: every document file in the repository]"
---

# Auditing documentation

Three standing instructions come before any method. Keep them in force through the whole audit.

1. **Audience first.** Judge every document against the reader it serves: a user, a contributor or an agent that reads it every turn. The goal is a narrow document that reader can trust, not a document that mirrors the code. A living doc owes accuracy where it makes a claim; it never owes coverage.
2. **Order of moves.** Cut, point at the source, generate, consolidate into the canonical home, rewrite for the audience, update, add what is missing. Take the earliest move that ends the cost. A cut needs no proof that the text is wrong: less text narrows the reader's focus, and a true claim that the code already holds is still a cost, because it goes false when the code changes. Update comes late because keeping a restatement fresh is a standing price.
3. **Living docs and records.** A living doc makes claims about the system as it is. A record states what was decided or true at a point in time: a decision record, a changelog, release notes. A record gets no drift finding (an old name in it is history) and is never proposed for an edit. When the code contradicts an accepted record, report it to the owner as a finding, and the move is to fix the code or supersede the record.

## Scope first

The scope is the path or set of documents you were given, or every document file in the project when none was. An auditor handed one set audits that set only, and says in the report what it did not look at. Document files are yours. Comments inside source files belong to the code audit, so leave them. A generated page is a document file: judge whether it serves its audience, not whether it is fresh. When a generated file has no freshness check, the finding is to add one. Never regenerate it or diff it to prove it fresh.

## Method

1. **Read what the project says about itself.** Its conventions for documents, its glossary and its decision records. They set what counts as a problem here, and a stated reason is a reason, not a finding.
2. **Classify each document.** Living doc or record; its type (tutorial, how-to, reference, explanation, decision or agent instructions); its audience; its canonical home. A document that cannot name these is a finding of its own. Detail and the agent-file checks: [scope-test](references/scope-test.md).
3. **Apply the scope test to every section of a living doc.** Could a reader with the code and a search tool find this in a minute? If so, it restates the code and is a candidate to cut. A stated why, an invariant, a trap, off-project knowledge and a user task stay: say so in the report when one is tempting to flag. Keep a stated why even when the code does not show its subject at all.
4. **Check for drift.** Count, default, limit, list, inventory, signature, walkthrough, snippet, link and duplicate each have one move. Check the names and paths a doc puts in code spans against the source, and sample its other claims. Classes, the history checks and the dangling-name check: [drift-classes](references/drift-classes.md).
5. **Check for gaps.** An undocumented decision, an invariant no document states, a user task with no how-to, an agent file with no verify command: [gaps](references/gaps.md). Move: add what is missing, and keep it as small as the cut moves are.
6. **Look at the whole set.** A large set rarely needs line edits first. Ask whether a document should be restructured, merged into another, split by audience or retired. These are candidates of their own. When a whole document is proposed for a cut or retirement, fold its line-level findings into that one candidate.
7. **Rank.** Rate by the definitions below, and write the output.

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
