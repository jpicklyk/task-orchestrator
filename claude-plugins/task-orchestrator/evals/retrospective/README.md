# Retrospective scenario evals

Dev tooling that checks the automatic retrospective end to end: a firing hook, the session-retrospective skill run
headless against a real throwaway server, and graders that read the result back. It ships in the plugin package but
nothing at runtime calls it.

## What it runs

`run-evals.mjs` starts its own container per scenario (default image `task-orchestrator:dev`, `--image` overrides;
a missing image exits 2 with the build command), seeds it over REST, runs the stages, grades them and removes the
container, the volume and every temporary directory (also after a failure or SIGINT). The checkout's
`deploy/global-config/.taskorchestrator` is mounted read-only as the global floor. No external URL is accepted.

| Scenario | Stages | What it proves |
|----------|--------|----------------|
| `1-clean` | `1-H1`, `1-H2`, `1-L1` | a small clean group is triaged low-signal (marks, no directive); a bigger one gets exactly one directive; a clean retrospective files no bug and no proposal |
| `2-defect` | `2-L2a`, `2-L2b` | a `defect:` friction line becomes one bug observation and one issue through the fake gh; a later sighting adds evidence and one comment, not a second issue |
| `3-project-friction` | `3-L3` | project-only friction recurs a project trend and raises a project proposal; nothing global is written |
| `4-stale` | `4a`, `4b`, red proof | a manual run of a scope that still has an open run is not stale; the directive form with the stale sentence is |

Hook stages (`kind: hook`) run `hooks/retro-trigger.mjs` with a `runs_write` close payload against the container and
cost no model tokens. Live stages (`kind: live`) run `claude -p` once, with the plugin loaded by `--plugin-dir`, only
the container's MCP endpoint (`--strict-mcp-config`), project settings only, in a throwaway working directory whose
project config sets the retrospective mode to `off` (so the headless session's own Stop hooks never re-dispatch) and
`github_feedback.enabled: false`. The prompt is the foreground form of the directive: its invoke text exactly as
`buildDispatch` emits it (scenario `4a` types the slash command), not the whole directive, which asks for a background
agent a print session could outlive.

Every live stage also runs the common checks C1 to C6 (one new retrospective item for the stage's runs, terminal with
its four notes, a `retro` mark on each run, no `AskUserQuestion`, no fake gh call unless the scenario expects some,
`claude` exit 0). Grading is deterministic: REST reads, the fake gh log and a transcript scan. There is no model judge.

## Commands

```
node claude-plugins/task-orchestrator/evals/retrospective/run-evals.mjs [--scenario <id>]... [--image <tag>] [--check] [--preflight] [--seed-only] [--record] [--redproof]
```

- `--check` validates the scenario files only; exit 2 on an unknown check id.
- `--preflight` builds the child environment and exits 3 when it can reach `gh`.
- `--seed-only` seeds, runs the hook stages, grades them and tears down: zero tokens. Run it before any live pass.
- `--record` appends one row per stage to [results.md](results.md): date, sha, observed model, result.
- `--redproof` also runs the red-proof stage, which uses a temporary copy of the plugin whose stale rule is cut (the
  checkout is never edited); the row passes when `T1` fails.

Exit codes: 0 all checks passed, 1 a check failed or a stage errored, 2 bad arguments, scenario or image, 3 preflight.

## Safety

- The child environment has no `gh`: every `PATH` entry holding `gh`, `gh.exe`, `gh.cmd` or `gh.bat` is dropped,
  `GH_TOKEN` and `GITHUB_TOKEN` are removed, `GH_CONFIG_DIR`, `TASK_ORCHESTRATOR_HOME` and `TEMP` point into a sandbox,
  `CLAUDE_CODE_PLUGIN_DIRS` is removed. `--disallowedTools "Bash(gh:*)"` is added on top.
- `bug-report` runs `fake-gh.mjs` through the `TASK_ORCHESTRATOR_GH_SHIM` seam of `scripts/run-planner.mjs` (a script
  node runs in place of gh, because a Windows spawn cannot start a scripted gh). The fake logs every call and body to
  `FAKE_GH_DIR` and answers `auth status`, `api user`, `issue list`, `issue create` (issue 990001, 990002, ...) and
  `issue comment`; anything else exits 1. The runner opens the bug-report gate in the child env of a `bugReports` scenario only.
- If `gh` sits in a directory that `node`, `git` or `bash` also needs (for example `/usr/bin`), preflight exits 3 by
  design: run from a `PATH` that keeps them apart.
- Seeding uses the server's own REST ops with server-set timestamps; nothing is backdated and no SQLite file is edited.
  The one clock move is the runner ageing the fake bug-report state entry by 8 days between `2-L2a` and `2-L2b`, so the
  7-day rate limit does not swallow the second sighting's comment.

## Cost

A live stage spends model tokens (the skill's frontmatter names `sonnet`). Each stage runs once, and a failed stage is
recorded with its observed model, never retried. The rows in `results.md` record the model ids the transcripts show,
which is what counts: the main session and the fork include `claude-opus-5-5`. The deterministic
layers belong to `node --test`, which this tool does not replace.

## Reading a failure

A failed `C1` in every live stage means the headless session did not resolve the bare `/session-retrospective` the
hook emits to the plugin skill: a product signal, record it. The evals report defects; fixing one is a separate item.
