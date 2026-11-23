# Finding hot spots

A hot spot is a file high in both churn (how often it changes) and complexity (how hard it is to change). History shows the first; the code shows the second. The idea of multiplying change frequency by complexity, and of treating files that change together as coupled, is Adam Tornhill's (*Your Code as a Crime Scene*); the commands below are ours. Where the numbers come from, and what each one means, is in [measuring](measuring.md).

Run every command from the repository root, restricted to the audited path. The windows are fixed once, at the start of the audit, and named in the report: a long window of 12 months (the whole history when it is shorter) and a recent window of 3 months. Every number in the report uses one of them.

## Churn: commits per file

```
git log --since="12 months ago" --name-only --pretty=format: -- <path> | grep -v '^$' | sort | uniq -c | sort -rn | head -30
```

`--pretty=format:` prints an empty line for every commit; the `grep` drops them so they are not counted as a path.

Drop churn that is there by design: lock files, generated output, changelogs, vendored code, test fixtures, data snapshots, and bulk commits such as renames or formatting sweeps. Look at the paths before you rank them; what is left goes on to the next two sections, whatever kind of file it is.

Commit count counts changes, not size. A file edited in a hundred one-line commits and one rewritten twice are different cases; for the top few, run `git log --oneline --follow -- <file>` and read the subjects. A burst around one feature that has since stopped is cooler than the count says.

## Hot spot, partner or noise

Configuration, registries, schemas and prompt or skill text stay in the list: in some projects they are the product, and their churn costs as much as code's. A document file busy on its own is named and left to auditing-documentation; as a hot file's partner it still goes on that file's line. For each busy file that is not plain source, decide which of three it is:

- **A hot spot of its own** when its edits carry a cost of their own: every new feature has to be registered in it, edits from unrelated changes collide in it, or it restates facts held elsewhere and the history shows commits that only bring it back in step. Rank it like code. The move is usually structural: split it by owner, or derive it from the place that holds the facts.
- **A partner** when it changes because one hot file changed and the edit is the ordinary work of keeping it current: the test of that file, the section of a document that describes it. Name it on that file's line.
- **Noise** when its churn is by design (the list above). Drop it.

Source files are judged the same way: a source file that changes only in the commits of one other file is usually that file's partner, not a second candidate.

## The hot set

The hot set is the files whose score (churn times complexity) stands clearly above the rest, judged against the whole repository (often ten files or fewer). It is a property of the repository, not of the scope. When the metrics script ran, take its repository-wide hot set and its scoped hot set as given. By hand:

- Take the top 30 files by churn from the list above, estimate the complexity of each (see [measuring](measuring.md)), and rank them by the product of the churn percentile and the complexity percentile (each the share of the 30 listed files at or below the file's value, so a higher product is hotter; an approximation of the repository-wide percentile the script computes). The files at the top, clearly apart from the rest, are the hot set. Mark the complexity numbers `estimated`.
- For a whole-repository audit, that is the whole job.
- For a scoped audit, run the churn command a second time without the path and compare. The area's busiest files belong to the hot set only when their counts are of the same order as the repository's busiest files. An area whose top files change far less than that has no hot set: say so in the report, and rate its candidates as the cold-area case in [ranking](ranking.md) says.

## Recent versus lifetime

The same command with the recent window (`--since="3 months ago"`) shows what is hot now. A file high in the long list and absent from the short one is cooling; a file high in both is the one to read first.

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
