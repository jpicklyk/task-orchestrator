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
node "<script>" --root <repo> --paths <p1,p2,...> --since <YYYY-MM-DD> --recent <YYYY-MM-DD> --out <file.json> [--digest-out <digest.json>]
```

`--since` is the start of the long window and `--recent` the start of the recent one. `--out` and the optional `--digest-out` go to a temporary directory outside the audited tree: the audit never writes inside the code it reads. The script only reads the repository, writes only the `--out` file and, when given, the `--digest-out` file (`--candidates <n>` caps its candidate list), and records problems in `notes[]` instead of failing. Optional flags (`--module-depth`, `--top`, `--lang-table`, `--max-commit-files`) are left at their defaults unless the metrics format says otherwise.

## What each output group means

- **`churn` and `churnRecent`.** Commits that touched the file in the long and in the recent window. Counts changes, not size.
- **`indentComplexity` and `maxNesting`.** Logical indentation summed over the non-blank, non-comment lines, and the deepest level. They exist for every text file whatever its language, which makes them the language-neutral measure; deep nesting is a proxy for the number of conditions a reader holds in mind.
- **`branchComplexity` and `topFunctions`.** Branch tokens counted per file and per function, with the longest and branchiest functions listed. Approximate, from a per-language table: they are `null` for a language the table does not know. Treat them as a pointer to where to read, not as a score to quote to the digit.
- **`hotspotScore`.** The file's churn percentile rank times its complexity percentile rank, each the share of the repository's files at or below the file's value, ranked across the whole repository even when the scope is one area. A high product is a hot file (1 is the busiest and most complex).
- **`repoHotSet` and `hotSet`.** The repository's hot files, and the scoped files whose counts are of the same order as them. Take both as given; an area with an empty `hotSet` has no hot set.
- **`coupling`.** For each hot file, the files that changed in the same commits, with counts. Commits that touch more files than the bulk threshold are excluded, so renames and formatting sweeps do not couple everything to everything.
- **`trend`.** Complexity of the top hot files sampled at several points in history. A rising series means the file gets harder with each change; a falling one means it is being cleaned up.
- **`modules`.** The module graph at one directory depth below a base. A base is a source root taken from the resolved package imports (the target path minus the matched qualified name), or the common directory of a connected group of relative imports; a file under neither keeps the entry base, the longest directory common to the scoped files of its `--paths` entry, and is grouped by up to `--module-depth` directory segments below it (for the `.` entry, trees no import reaches, such as CI configuration, documentation or a build-tool wrapper directory, are separate modules). Per module: `fanIn` (modules that depend on it), `fanOut` (modules it depends on) and `instability` = fanOut / (fanIn + fanOut) (Martin: near 0 is stable, near 1 changes freely), the edges with counts, and `cycles`. Each file row carries its `module`. Imports are resolved by a per-language pattern table; an import it cannot resolve is counted and never guessed, so a graph is a lower bound on the real dependencies.
- **`languages`.** The extensions recognised, and `unrecognised` ones, for which only the indentation numbers exist. Name the unrecognised languages in the report.
- **`notes[]`.** Problems the run recorded: no history, a missing path. Each one lowers confidence for what it touches; repeat it in the report.

## The digest

A digest is a smaller extract of the metrics file, made for reading by an auditor. It holds:

- **`hotSet`.** The hot files, each with `path`, `churn`, `churnRecent`, `complexity` (the indentation measure), `hotspotScore` and `module`.
- **`topComplexity` and `unstableModules`.** Cut to the top entries (`--top`, 10 by default): `topComplexity` lists `path`, `complexity` and `module` of the most complex files, `unstableModules` the `id`, `instability`, `fanIn` and `fanOut` of the modules with a dependent and a nonzero instability.
- **`cycles`, `coupling` and `trend`.** Taken from the metrics file: `cycles` whole, `coupling` as each hot file's `path` and its partners' `path` and `count` (the commit total is dropped), and `trend` as each file's `path` and the list of its sampled complexities, `complexities`.
- **`notes[]`.** The problems the metrics run recorded, copied unchanged.
- **`candidates[]`.** The candidate list, ranked by `score` and capped by `--candidates` (12 by default). Each entry has a `kind`: `hot-file`, `cycle`, `unstable-module` or `co-change` (a coupling partner in another module, with 3 or more shared commits), its `subject` (paths or module ids) and the measured `facts` it rests on.

The digest has no row per file. A file outside `hotSet` and `topComplexity` has no measured number in the digest: read the metrics file, or say in the report that it was not measured.

## Measured and judgement claims

A claim is measured when it is the measurement itself: a churn count, a complexity figure, a cycle's module ids. State it as the field and the value exactly as the measurements give it. Everything else is judgement: that the cost is real, that a move pays, that two files belong together. A number made by hand is `estimated`, and a claim that rests on it is judgement.

## By hand

Use the git commands in [hotspots](hotspots.md) for churn and coupling. For complexity, take the top 30 files by churn and, for each, count the non-blank lines and find the deepest indentation by reading it. Mark every such number `estimated`. A ranking that rests on estimated complexity has medium confidence at most. Module fan-in and fan-out can be counted from import lines in the same files; say that the graph is partial.
