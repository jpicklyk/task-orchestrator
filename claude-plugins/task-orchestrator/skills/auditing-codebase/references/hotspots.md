# Finding hot spots

A hot spot is a file that is both worth changing and changed often. History shows the second; reading shows the first. The idea of combining change frequency with a look at the code, and of treating files that change together as coupled, is Adam Tornhill's (*Your Code as a Crime Scene*); the commands below are ours.

Run every command from the repository root, restricted to the audited path. Pick a window that covers enough work to be stable (six to twelve months, or the last few hundred commits, whichever is longer) and name it in the report.

## Churn: commits per file

```
git log --since="12 months ago" --name-only --pretty=format: -- <path> | grep -v '^$' | sort | uniq -c | sort -rn | head -30
```

`--pretty=format:` prints an empty line for every commit; the `grep` drops them so they are not counted as a path.

Drop churn that is there by design: lock files, generated output, changelogs, vendored code, test fixtures and data snapshots. Look at the paths before you rank them; what is left goes on to the next two sections, whatever kind of file it is.

Commit count counts changes, not size. A file edited in a hundred one-line commits and one rewritten twice are different cases; for the top few, run `git log --oneline --follow -- <file>` and read the subjects. A burst around one feature that has since stopped is cooler than the count says.

## Hot spot, partner or noise

Configuration, registries, schemas, prompt or skill text and documents stay in the list: in some projects they are the product, and their churn costs as much as code's. For each busy file that is not plain source, decide which of three it is:

- **A hot spot of its own** when its edits carry a cost of their own: every new feature has to be registered in it, edits from unrelated changes collide in it, or it restates facts held elsewhere and the history shows commits that only bring it back in step. Rank it like code. The move is usually structural: split it by owner, or derive it from the place that holds the facts.
- **A partner** when it changes because one hot file changed and the edit is the ordinary work of keeping it current: the test of that file, the section of a document that describes it. Name it on that file's line.
- **Noise** when its churn is by design (the list above). Drop it.

Source files are judged the same way: a source file that changes only in the commits of one other file is usually that file's partner, not a second candidate.

## The hot set

The hot set is the few files at the top of the churn list, where the counts stand clearly above the rest (often ten files or fewer). It is a property of the repository, not of the scope:

- For a whole-repository audit, take it from the list above.
- For a scoped audit, run the churn command a second time without the path and compare. The area's busiest files belong to the hot set only when their counts are of the same order as the repository's busiest files. An area whose top files change far less than that has no hot set: say so in the report, and rate its candidates as the cold-area case in [ranking](ranking.md) says.

## Recent versus lifetime

The same command with a short window (`--since="3 months ago"`) shows what is hot now. A file high in the long list and absent from the short one is cooling; a file high in both is the one to read first.

## Change coupling: files that change together

For a hot file, list what changes in the same commits:

```
git log --since="12 months ago" --format=%H -- <file> | while read c; do git show --name-only --pretty=format: "$c"; done | grep -v '^$' | sort | uniq -c | sort -rn | head -15
```

`--format=%H` ends every hash with a newline; with `--pretty=format:%H` the last hash has none and `read` drops that commit. The hot file itself tops this list with its own commit count; the rows below it are its partners. A pair that appears in most of one file's commits is coupled whether or not either imports the other. Two shapes matter:

- coupled files in different modules: knowledge that leaked across a boundary, usually the best structural finding;
- two near-identical files that always change together: duplication paying its price on every change. Check the decision records first; a project may have chosen it.

Skip commits that touch dozens of files (renames, formatting sweeps, dependency bumps); they couple everything to everything. Filter by file count per commit, or set them aside by subject.

## Fallbacks

- One commit, a shallow clone or an export: no history. Rank by size and by how many files import each file, mark confidence low, and say that history was unavailable.
- A scoped area: run the commands with the path, but look at coupling across the whole repository, since a partner outside the area is still part of the finding. Name partners outside the scope without auditing them.
- Renamed files: use `--follow` per file; the repository-wide count splits at a rename.
