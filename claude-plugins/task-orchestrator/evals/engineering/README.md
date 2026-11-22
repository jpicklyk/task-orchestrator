# Engineering skill scenario evals

Dev tooling that measures what a model gets wrong on engineering work, before and after a skill is added. It ships in
the plugin package but nothing at runtime calls it. Each scenario is a small fixture repository plus one request; the
run is graded from the files written, the git diff and the final message. There is no model judge and no server.

## Scenarios

At least three per skill, fixture scenarios first and the realistic tier after them. A scenario names the failure pattern it probes (the catalog names of the slop skill, or the
design and legibility check it exercises). Fixtures are in `fixtures/`; scenario files in `scenarios/`.

| Skill | Scenarios | What they probe |
|-------|-----------|-----------------|
| `designing-modules` | `1-single-provider`, `2-config-loader`, `3-dependency-direction` | single-implementation interface and factory for one case; temporal decomposition and over-fragmentation; a domain module importing infrastructure |
| `organizing-codebases` | `1-reinvented-formatter`, `2-generic-names`, `3-local-conventions` | reinvented helper; generic names and a function outside its domain folder; convention drift in error and logging style |
| `spotting-code-slop` | `1-seeded-diff`, `2-clean-standard`, `3-seeded-diff-defensive` | review catches four seeded defects; review leaves a documented project standard alone; guards, parallel version, leftovers |
| `defining-project-terms` | `1-order-purchase`, `2-shipment-package`, `3-new-term`, `4-no-glossary` | vocabulary drift against a glossary; a new term recorded; silence when there is no glossary |
| `auditing-codebase-health` | `1-hot-spot`, `2-glossary-drift`, `3-decision-record` | a hot spot outranks a stable ugly module; a glossary-drift pass; a decision-record conflict is flagged |

Prompts carry no hint of the answer (no "Stripe is the only provider we use", no "formatted for display"): a hinted
variant would be a separate scenario id.

Scenario ids start with the skill name, so `--scenario spotting-code-slop` runs all of that skill's scenarios.

### Realistic tier

The fixture scenarios are tiny, so the agent reads every file. Each skill therefore also has one scenario (id
`<skill>-4-realistic-...`, or `-5-` for `defining-project-terms`) that runs against this repository itself, where the
right answer needs discovery in a large tree. Each scenario's `expectation` says why its target was chosen.

| Scenario | Target in this repository | Graded on |
|----------|---------------------------|-----------|
| `organizing-codebases-4-realistic-session-marker` | a once-per-session hook; `hooks/lib/markers.mjs`, `stdin.mjs` and `emit.mjs` serve about ten hooks | no hand-rolled temp file, stdin reader or output, no parallel helper under `hooks/lib`, marker helpers imported |
| `designing-modules-4-realistic-cache-share` | a cache-share figure over a transcript; `scripts/lib/meters.mjs` is the one transcript measure | no new module, no re-parsing of the usage fields, `transcriptMeters` or `sumUsage` reused |
| `defining-project-terms-5-realistic-seat-ownership` | a request that says "seat ownership"; `GLOSSARY.md` lists it as a term to avoid for owning seat | the avoided term in no file name, code, comment or test; the glossary term used |
| `spotting-code-slop-4-realistic-historical-commit` | commit `c506096c5` (#743), whose review recorded finding N1 (the skill step dropped the sort and paging the code relies on) | a FINDING on `SKILL.md` about paging, sort order or the limit |
| `auditing-codebase-health-4-realistic-hooks-history` | `hooks/`, about 125 commits at the base sha | every ranked path, and the first, among the most-changed non-test files |

A scenario with a `snapshot` field (`ref`, and optionally `overlay` or `history`) builds its repository from this
checkout instead of a fixture folder, outside the repository, in a fresh temp directory per run:

- default: `git archive <ref>` extracted, then `git init` and one commit, so the agent can diff. `ref` is `7998dc957`,
  the base sha of the baseline.
- `overlay { from, paths, message }`: the archive of `ref` as commit 1, then those paths as they are in commit `from`
  as commit 2. The review scenario uses the parent of `c506096c5` as `ref` (the commit itself is in the base sha, so a
  base-sha archive has nothing to review) and adds the commit's three non-test files; the tests and changelog are left out.
- `history: true`: a clone checked out at `ref`, with every other ref, reflog entry and unreachable object removed, so
  `git log` shows the real history up to `ref` and nothing after it. The audit scenario needs it.

The snapshot's `.mcp.json` and `.claude/settings*.json` are removed before the first commit and the plugin is never
passed with `--plugin-dir` for a baseline, so the snapshot's own plugin and servers never load; its `CLAUDE.md` and
`GLOSSARY.md` stay, because an agent working in the repository normally sees them. Caps for a realistic run: at most
60 turns (40 for a fixture run) and a 20 minute timeout (15 for a fixture run); a run that hits either is an error row
and is not recorded.

### Slop graders on build scenarios

Every build scenario (fixture or realistic) also grades, on the lines the run added, the slop families models produce
most: `narrating-comments` (a `//` comment that restates the code line below it, or a change-log comment; doc comments are skipped), `swallowed-catch`
(a catch that is empty, log-only or returns a default), `defensive-guards` (three or more null, undefined,
`Array.isArray` or `?.` guards; six for the config loader, which validates input on purpose), a new dependency in
`package.json`, `modified-outside` (an existing file outside the paths the request needs: scope creep), `unused-export`
(a new export no added line refers to a second time) and a TODO or FIXME left behind. They read production files only;
the scenario's own request checks sit next to them.

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

A run spends model tokens, with at most 40 turns (60 for a realistic-tier run). Run each scenario once per model and record what happened; do not
retry a run to get a better row. A model's answer varies between runs, so one row is a sample, not a rate: when a
skill's rerun differs from its baseline in a way that matters, repeat that pair before concluding.
