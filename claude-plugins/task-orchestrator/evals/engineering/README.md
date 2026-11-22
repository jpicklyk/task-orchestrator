# Engineering skill scenario evals

Dev tooling that measures what a model gets wrong on engineering work, before and after a skill is added. It ships in
the plugin package but nothing at runtime calls it. Each scenario is a small fixture repository plus one request; the
run is graded from the files written, the git diff and the final message. There is no model judge and no server.

## Scenarios

At least three per skill. A scenario names the failure pattern it probes (the catalog names of the slop skill, or the
design and legibility check it exercises). Fixtures are in `fixtures/`; scenario files in `scenarios/`.

| Skill | Scenarios | What they probe |
|-------|-----------|-----------------|
| `designing-modules` | `1-single-provider`, `2-config-loader`, `3-dependency-direction` | single-implementation interface and factory for one case; temporal decomposition and over-fragmentation; a domain module importing infrastructure |
| `organizing-codebases` | `1-reinvented-formatter`, `2-generic-names`, `3-local-conventions` | reinvented helper; generic names and a function outside its domain folder; convention drift in error and logging style |
| `spotting-code-slop` | `1-seeded-diff`, `2-clean-standard`, `3-seeded-diff-defensive` | review catches four seeded defects; review leaves a documented project standard alone; guards, parallel version, leftovers |
| `defining-project-terms` | `1-order-purchase`, `2-shipment-package`, `3-new-term`, `4-no-glossary` | vocabulary drift against a glossary; a new term recorded; silence when there is no glossary |
| `auditing-codebase-health` | `1-hot-spot`, `2-glossary-drift`, `3-decision-record` | a hot spot outranks a stable ugly module; a glossary-drift pass; a decision-record conflict is flagged |

Scenario ids start with the skill name, so `--scenario spotting-code-slop` runs all of that skill's scenarios.

### Fixture shapes

- Flat: the files are committed as one commit.
- `base/` and `change/`: `base/` is commit 1, `change/` is copied over it as commit 2 (the scenario's
  `changeMessage`). Review scenarios use this so the agent reviews `git show HEAD`.
- `_history.json`: `{ "commits": [{ "message", "touch": [paths] }] }` adds one commit per entry, appending a comment
  line to each touched file. Audit scenarios use this to give files a change history. Names starting with `_` are
  never copied into the repository.

### Scenario fields

`id`, `skill`, `kind` (`build`, `review` or `audit`), `title`, `fixture`, `prompt`, `pattern` (what it probes),
`expectation` (the failure expected without the skill), `models` (default list), `grading` (`auto` or `manual`),
`checks`, and for review and audit scenarios `output` (the file the agent writes: `review.md` with
`FINDING <file>:<line> | <text>` lines and a `VERDICT:` line, or `audit.md` with
`RANK <n> | <path> | <rating> | <reason>` lines).

### Graders

`checks.mjs` holds the check types (documented at the top of the file). A check names the pattern it probes and FAILS
when that failure is observed, so a run's failures are the distinct pattern names of its failed checks, and a run with
none is `no failure`. The checks are heuristics over paths, added lines and output lines: read a surprising row
together with the run's detail line before drawing a conclusion. A scenario that cannot be graded deterministically
sets `"grading": "manual"` with a `manual` field naming exactly what to look for; the runner keeps its working
directory and prints the path.

## Commands

```
node claude-plugins/task-orchestrator/evals/engineering/run-evals.mjs [--scenario <id>]... [--model <alias>]... [--plugin-dir <path>] [--label <text>] [--check] [--list] [--record] [--expect-pass]
```

- `--check` validates the scenario files and fixtures only: zero tokens. Run it after editing a scenario.
- `--list` prints the selected scenarios with skill, pattern and models.
- `--scenario` selects an id or a skill-name prefix (repeatable); without it every scenario runs.
- `--model` replaces the scenario's own model list. The defaults are `sonnet` (the implementer seat's model) for build
  scenarios and `sonnet` and `opus` (the reviewer seat's model) for review and audit scenarios.
- `--record` appends one row per run to [results.md](results.md): date, sha, model, scenario, mode, result, failure.
- `--expect-pass` exits 1 when any run showed a failure.

Exit codes: 0 done, 1 a run errored (or `--expect-pass` saw a failure), 2 bad arguments, scenario or fixture.

`node --test claude-plugins/task-orchestrator/evals/engineering/checks.test.mjs` tests the graders, the scenario
validation and the repository builder with no model.

## Rerun with and without a skill

Without `--plugin-dir` every run passes `--disable-slash-commands`, so no skill is loaded: that is the baseline, and
it is how results.md was first filled.

To rerun WITH a skill, load the plugin checkout that holds it:

```
node claude-plugins/task-orchestrator/evals/engineering/run-evals.mjs --scenario spotting-code-slop --plugin-dir claude-plugins/task-orchestrator --label "with spotting-code-slop" --record
```

The mode column then reads the label, plus the skills the transcript shows the agent invoked (or `no skill
invoked`, a signal that the skill's description did not trigger). Compare the new rows with the baseline rows of the
same scenario and model. The target failures should be gone; a scenario that was `no failure` in the baseline must
stay `no failure` (that is how a false positive or over-triggering shows up).

## Safety

- Each run copies a fixture into a fresh directory under the system temp folder, initializes a git repository there
  and runs `claude -p` once in it. The directory is removed afterwards (kept only for a manual scenario).
- Settings come from `--setting-sources project`, so the user's settings and enabled plugins do not apply. Tools
  are limited to file tools, `node`, read-only shell commands and `git`; `gh` is on neither the allow list nor the
  child `PATH`, and `GH_TOKEN` and `GITHUB_TOKEN` are removed. Fixtures have no remote.
- Memory files from the user's home directory may still reach the session; a run's recorded model ids and the
  detail line are the evidence to read when a result looks off.
- No server container, no network service and no external URL is used.

## Cost

A run spends model tokens, with at most 40 turns. Run each scenario once per model and record what happened; do not
retry a run to get a better row. A model's answer varies between runs, so one row is a sample, not a rate: when a
skill's rerun differs from its baseline in a way that matters, repeat that pair before concluding.
