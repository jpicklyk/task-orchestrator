# Finding hot spots

A hot spot is code that is both worth changing and changed often. History shows the second; reading shows the first. The idea of combining change frequency with a look at the code, and of treating files that change together as coupled, is Adam Tornhill's (*Your Code as a Crime Scene*); the commands below are ours.

Run every command from the repository root, restricted to the audited path. Pick a window that covers enough work to be stable (six to twelve months, or the last few hundred commits, whichever is longer) and name it in the report.

## Churn: commits per file

```
git log --since="12 months ago" --name-only --pretty=format: -- <path> | sort | uniq -c | sort -rn | head -30
```

Drop what is not code a person restructures: lock files, generated output, changelogs, documentation, vendored code, fixtures. A document can top the list without being a candidate, so look at the paths before you rank them.

Commit count counts changes, not size. A file edited in a hundred one-line commits and one rewritten twice are different cases; for the top few, run `git log --oneline --follow -- <file>` and read the subjects. A burst around one feature that has since stopped is cooler than the count says.

## Recent versus lifetime

The same command with a short window (`--since="3 months ago"`) shows what is hot now. A file high in the long list and absent from the short one is cooling; a file high in both is the one to read first.

## Change coupling: files that change together

For a hot file, list what changes in the same commits:

```
git log --since="12 months ago" --pretty=format:%H -- <file> | while read c; do git show --name-only --pretty=format: "$c"; done | sort | uniq -c | sort -rn | head -15
```

A pair that appears in most of one file's commits is coupled whether or not either imports the other. Two shapes matter:

- coupled files in different modules: a leaked decision (design signal 1), usually the best structural finding;
- two near-identical files that always change together: duplication paying its price on every change. Check the decision records first; a project may have chosen it.

Skip commits that touch dozens of files (renames, formatting sweeps, dependency bumps); they couple everything to everything. Filter by file count per commit, or set them aside by subject.

## Fallbacks

- One commit, a shallow clone or an export: no history. Rank by size and by how many files import each file, mark confidence low, and say that history was unavailable.
- A scoped area: run the commands with the path, but look at coupling across the whole repository, since a partner outside the area is still part of the finding. Name partners outside the scope without auditing them.
- Renamed files: use `--follow` per file; the repository-wide count splits at a rename.
