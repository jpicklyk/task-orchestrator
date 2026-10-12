# Director wave fixture

This directory is a disposable fixture for the director experiment. The
experiment measures how much context a director session accumulates while a
separate coordinator session runs a real wave through run-wave. When the
experiment ends, this directory, its branch and its PR are deleted (the PR is
closed unmerged) and the MCP items under feature `cc748b14` are cancelled.

## The three tasks

| Item       | Task                                                              |
|------------|-------------------------------------------------------------------|
| `6c6eb39a` | T1: this README                                                   |
| `3b7dc485` | T2: `rollup.mjs` plus `node:test` tests in `rollup.test.mjs`      |
| `2c8fcc04` | T3: `--markdown` output for `rollup.mjs`; blocked by T2           |

`rollup.mjs` reads a `query_items` overview JSON on stdin and prints one row
per child: 8-char id, title truncated to 40 chars, queue/work/review/blocked/
terminal counts, then a TOTAL line. A `--json` variant is also planned. T2 and
T3 are planned files that are written during the wave. T3 depends on T2, so
the frontier runs in two waves: T1 + T2, then T3.

## What is measured

- Director context per turn (input + cache-creation + cache-read tokens).
- The final context figure, compared against the ~550k recorded for the
  combined orchestrator role.
- Top tool results by bytes (a leak check).
- Altitude-guard denials.

The same measurement is run against the coordinator session for the other half.

## Running measure.mjs

The script lives in the kit at `plans/director-experiment/measure.mjs` in the
main checkout, not in this directory. Run it from the main checkout:

```
node plans/director-experiment/measure.mjs --latest
node plans/director-experiment/measure.mjs --list
node plans/director-experiment/measure.mjs <sessionId | path>
```

`--latest` picks the newest transcript whose first prompt invoked the
director, `--list` shows candidates, and the last form targets a specific
transcript (for example the coordinator's).

## Cleanup

Close the PR unmerged, delete the `feat/...` branch and worktree, cancel the
items, and run the kit's `uninstall.mjs`. See `plans/director-experiment/README.md`
for the full runbook.
