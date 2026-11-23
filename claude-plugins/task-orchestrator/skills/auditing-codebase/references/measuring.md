# Measuring before reading

Numbers pick the places to read; reading judges them. Measure once, at the start, and reuse the same numbers in every later step. The method needs no language-specific tool: everything below works on any text file, and a project's own analyzer may add exact numbers as further evidence.

## The two windows

Fix them once, before collecting anything, and name both in the report. The long window is 12 months, or the whole history when it is shorter. The recent window is 3 months. Every churn number in the report uses one of these two windows; never mix in another.

## Where the numbers come from

First match wins:

1. **A metrics file or digest you were handed.** Read it and never run the script again; the numbers were taken at a fixed point and another run could differ.
2. **The metrics script, from the plugin.** Try `${CLAUDE_PLUGIN_ROOT}/scripts/audit-metrics.mjs`. Confirm that the literal string `${CLAUDE_PLUGIN_ROOT}` did not survive into the command, and that the file exists.
3. **The script in a source checkout of the plugin.** `<git toplevel>/claude-plugins/task-orchestrator/scripts/audit-metrics.mjs`, when that path exists.
4. **By hand,** as the last section describes.

The command line of the script:

```
node "<script>" --root <repo> --paths <p1,p2,...> --since <YYYY-MM-DD> --recent <YYYY-MM-DD> --out <file.json>
```

`--since` is the start of the long window and `--recent` the start of the recent one. `--out` goes to a temporary directory outside the audited tree: the audit never writes inside the code it reads. The script only reads the repository, writes only the `--out` file, and records problems in `notes[]` instead of failing. Optional flags (`--module-depth`, `--top`, `--lang-table`, `--max-commit-files`) are left at their defaults unless the metrics format says otherwise.

## What each output group means

- **`churn` and `churnRecent`.** Commits that touched the file in the long and in the recent window. Counts changes, not size.
- **`indentComplexity` and `maxNesting`.** Logical indentation summed over the non-blank, non-comment lines, and the deepest level. They exist for every text file whatever its language, which makes them the language-neutral measure; deep nesting is a proxy for the number of conditions a reader holds in mind.
- **`branchComplexity` and `topFunctions`.** Branch tokens counted per file and per function, with the longest and branchiest functions listed. Approximate, from a per-language table: they are `null` for a language the table does not know. Treat them as a pointer to where to read, not as a score to quote to the digit.
- **`hotspotScore`.** The file's churn percentile rank times its complexity percentile rank, each the share of the repository's files at or below the file's value, ranked across the whole repository even when the scope is one area. A high product is a hot file (1 is the busiest and most complex).
- **`repoHotSet` and `hotSet`.** The repository's hot files, and the scoped files whose counts are of the same order as them. Take both as given; an area with an empty `hotSet` has no hot set.
- **`coupling`.** For each hot file, the files that changed in the same commits, with counts. Commits that touch more files than the bulk threshold are excluded, so renames and formatting sweeps do not couple everything to everything.
- **`trend`.** Complexity of the top hot files sampled at several points in history. A rising series means the file gets harder with each change; a falling one means it is being cleaned up.
- **`modules`.** The module graph at one directory depth: per module `fanIn` (modules that depend on it), `fanOut` (modules it depends on) and `instability` = fanOut / (fanIn + fanOut) (Martin: near 0 is stable, near 1 changes freely), the edges with counts, and `cycles`. Imports are resolved by a per-language pattern table; an import it cannot resolve is counted and never guessed, so a graph is a lower bound on the real dependencies.
- **`languages`.** The extensions recognised, and `unrecognised` ones, for which only the indentation numbers exist. Name the unrecognised languages in the report.
- **`notes[]`.** Problems the run recorded: no history, a missing path. Each one lowers confidence for what it touches; repeat it in the report.

## By hand

Use the git commands in [hotspots](hotspots.md) for churn and coupling. For complexity, take the top 30 files by churn and, for each, count the non-blank lines and find the deepest indentation by reading it. Mark every such number `estimated`. A ranking that rests on estimated complexity has medium confidence at most. Module fan-in and fan-out can be counted from import lines in the same files; say that the graph is partial.
