# Engineering skill scenario evals

Dev tooling that measures what a model gets wrong on engineering work, before and after a skill is added. It ships in
the plugin package but nothing at runtime calls it. Each scenario is a small fixture repository plus one request; the
run is graded from the files written, the git diff and the final message. There is no model judge and no server.

It is maintainer dev tooling for the plugin's own skills, not plugin text a project reads. Its realistic scenarios run
against the Task Orchestrator repository, so they name that repository's paths and commit shas by design: the rule that
plugin text states no project's paths does not apply to this folder.

## Scenarios

At least three per skill, fixture scenarios first and the realistic tier after them. A scenario names the failure pattern it probes (the entry names of the slop catalog, or the
design and legibility check it exercises). Fixtures are in `fixtures/`; scenario files in `scenarios/`.

| Skill | Scenarios | What they probe |
|-------|-----------|-----------------|
| `designing-modules` | `1-single-provider`, `2-config-loader`, `3-dependency-direction` | single-implementation interface and factory for one case; temporal decomposition and over-fragmentation; a domain module importing infrastructure |
| `organizing-codebases` | `1-reinvented-formatter`, `2-generic-names`, `3-local-conventions` | reinvented helper; generic names and a function outside its domain folder; convention drift in error and logging style |
| `spotting-code-slop` | `1-seeded-diff`, `2-clean-standard`, `3-seeded-diff-defensive` | review catches four seeded defects; review leaves a documented project standard alone; guards, parallel version, leftovers |
| `defining-project-terms` | `1-order-purchase`, `2-shipment-package`, `3-new-term`, `4-no-glossary` | vocabulary drift against a glossary; a new term recorded; silence when there is no glossary |
| `auditing-codebase` | `1-hot-spot`, `2-glossary-drift`, `3-decision-record` | a hot spot outranks a stable ugly module; a glossary-drift pass; a decision-record conflict is flagged |
| `writing-documentation` | `1-module-readme`, `2-agent-file`, `3-stale-section` | a README that tours files and copies values instead of the invariant and its reason; an agent file that retells the tree; a section whose subject is gone is deleted, not kept or rewritten |
| `auditing-documentation` | `1-drift-classes`, `2-agent-file`, `3-records` | accurate copies of code are proposed for a cut, a dangling name is reported, a stated why is kept; an overview, a contradiction and a dead path in an agent file; a changelog and an accepted decision record are not drift-checked or edited |

Prompts carry no hint of the answer (no "Stripe is the only provider we use", no "formatted for display"): a hinted
variant would be a separate scenario id.

Scenario ids start with the skill name, so `--scenario spotting-code-slop` runs all of that skill's scenarios.
Rows that results.md recorded before the audit skill's rename name `auditing-codebase-health-<n>-...`; that is the
scenario now named `auditing-codebase-<n>-...`.

Two scenario families have no skill directory of their own: the slop catalog that `spotting-code-slop` probes now sits
under `skills/auditing-codebase`, and `organizing-codebases` was cancelled. Their scenarios stay as baselines;
`--with-skill` accepts only a family whose `skills/<name>/SKILL.md` exists.
The two documentation families are baselines until their skills exist.

### Realistic tier

The fixture scenarios are tiny, so the agent reads every file. Each skill therefore also has one scenario (id
`<skill>-4-realistic-...`, or `-5-` for `defining-project-terms`; the two documentation skills run at the later base sha `848db0fa0`) that runs against this repository itself, where the
right answer needs discovery in a large tree. Each scenario's `expectation` says why its target was chosen.

| Scenario | Target in this repository | Graded on |
|----------|---------------------------|-----------|
| `organizing-codebases-4-realistic-session-marker` | a once-per-session hook; at `7998dc957` `hooks/lib/markers.mjs` is imported by 9 hook scripts, `stdin.mjs` by 22 and `emit.mjs` by 22 (counted as `from './lib/<name>.mjs'` in the top-level hook scripts, tests excluded) | no hand-rolled temp file, stdin reader or output, no parallel helper under `hooks/lib`, marker helpers imported |
| `designing-modules-4-realistic-cache-share` | a cache-share figure over a transcript; `scripts/lib/meters.mjs` is the one transcript measure | no new module, no re-parsing of the usage fields, `transcriptMeters` or `sumUsage` reused |
| `defining-project-terms-5-realistic-seat-ownership` | a request that says "seat ownership"; `GLOSSARY.md` lists it as a term to avoid for owning seat | the avoided term in no file name, code, comment or test; the glossary term used |
| `spotting-code-slop-4-realistic-historical-commit` | commit `c506096c5` (#743), whose review recorded finding N1 (the skill step dropped the sort and paging the code relies on) | a FINDING on `SKILL.md` about paging, sort order or the limit |
| `auditing-codebase-4-realistic-hooks-history` | `hooks/`, about 125 commits at the base sha; the prompt does not mention the git history | every ranked path, and the first, among the most-changed files, `hooks/README.md` (27 commits, the most of any path) included |
| `writing-documentation-4-realistic-hooks-lib` | `hooks/lib`, 16 files and no README at `848db0fa0` | a README of at least eight file-by-file lines, a copied count or ten function lines is a failure; an empty or missing README too |
| `auditing-documentation-4-realistic-current-docs` | `current/docs/`, where the claim TTL default 900 is copied into `workflow-guide.md` and `fleet-deployment.md` and the page-limit default 50 into `api-rest.md` | a finding on a copied default whose move is to state it once, and a move that cuts, merges or splits a section or document |

A scenario with a `snapshot` field (`ref`, and optionally `overlay` or `history`) builds its repository from this
checkout instead of a fixture folder, outside the repository, in a fresh temp directory per run:

- default: `git archive <ref>` extracted, then `git init` and one commit, so the agent can diff. `ref` is `7998dc957`,
  the base sha of the baseline (`848db0fa0` for the two documentation scenarios, which have no history).
- `overlay { from, paths, message }`: the archive of `ref` as commit 1, then those paths as they are in commit `from`
  as commit 2. The review scenario uses the parent of `c506096c5` as `ref` (the commit itself is in the base sha, so a
  base-sha archive has nothing to review) and adds every file the real commit touches, tests, new test file and
  changelog included, so the agent reviews exactly what history holds (a test compares each file with the commit).
- `history: true`: a clone checked out at `ref`, with every other ref, reflog entry and unreachable object removed, so
  `git log` shows the real history up to `ref` and nothing after it. The audit scenario needs it.

The snapshot's `.mcp.json`, `.claude/settings*.json`, `.claude/skills`, `.claude/commands` and `.claude/agents` are
removed before the first commit (and, for `history`, in one extra commit on top of the base sha), and a baseline never
passes the plugin, so the snapshot's own plugin, servers and project skills never load, not even beside the one skill a
rerun adds; its `CLAUDE.md` and `GLOSSARY.md` stay, because an agent working in the repository normally sees them. Caps for a realistic run: at most
60 turns (40 for a fixture run) and a 20 minute timeout (15 for a fixture run); a run that hits either is an error row
and is not recorded.

### Slop graders on build scenarios

Every build scenario (fixture or realistic) also grades, on the lines the run added, the slop families models produce
most: `narrating-comments` (a comment that restates the code line below it, or a change-log comment; a doc comment is
flagged only when its words are all words of the declaration below it, so one that states a contract stays unflagged),
`swallowed-catch` (a catch that is empty, log-only or returns a default), `broad-try` (a try with a catch or finally
over six or more code lines: a wide net around new code), `defensive-guards` (three or more null, undefined,
`Array.isArray` or `?.` guards; six for the config loader, which validates input on purpose), a new dependency in
`package.json`, `modified-outside` (an existing file outside the paths the request needs: scope creep, on every build
scenario), `unused-export` (a new export no added line refers to a second time) and a TODO or FIXME left behind. They
read production files only; the scenario's own request checks sit next to them. The documentation build scenarios
(`writing-documentation-*`) carry only `modified-outside` (an existing file changed or deleted outside the one document
the request names): the other graders read code, and their output is a document.

`designing-modules-1-single-provider` catches a one-implementation abstraction by shape (`single-implementation`): an
`interface`, an `abstract class` or a base class whose methods only throw "not implemented"; a base class with exactly
one subclass; a parameter whose default is the one concrete implementation, with no fake, stub, mock or spy in the
added tests; a one-entry lookup table read back by key. Name checks (`Provider`, `Gateway`, `Factory`) stay beside them.

The review and audit checks require a finding to name the defect kind, not only the file (`S1a`, `S1b`, `S1d`, `S3c`).

### Grader history

A grader that changed after a failing row is listed here with what it was, so a pass can be read against it.

- `S4a` (slop-4) first read `SKILL\.md.*(page|paging|sort|order|truncat|limit|200|older|oldest|newest|beyond|unread)`.
  It was narrowed to terms that trace to finding N1 (`order`, `limit` and `200` were removed); the current regex is
  in the scenario file. The rows from before the narrowing are superseded in results.md.
- `narrating-comments` first flagged a one-line contract doc comment as narration (a false positive), was then changed
  to skip every doc comment, and now skips a doc comment that states a contract and flags one that only restates the
  declaration.
- `S1a`, `S1b`, `S1d` and `S3c` matched a bare file name or a bare word such as `error`; they now need the defect kind.
- `D1a` and `D1c` matched names only (`Provider`, `Gateway`, `Factory`); they are shape checks now and the name
  regexes are `D1d` and `D1e`.
- The glossary fixture's `CLAUDE.md` stated the rule `T3a` checks (update the glossary with the change); it no longer does.
- The audit-4 prompt said "use the repository's git history", which names the method it probes; it no longer does.
- `2-shipment-package` (terms-2) prompt named `trackPackage(trackingId)`, so a run could not fail on its own choice of
  name; it now names neither the function nor any glossary-avoided word ("delivery"). Earlier rows are superseded.
- `3-decision-record` (audit-3) required rank 1 to be a carrier adapter, which the fixture's own decision record
  protects. It now passes when the adapters are not ranked, or are ranked with a line that mentions the record
  (`rank-line-lacks` with `absentOk`); ranking `labels.js` first is a legitimate answer. Earlier rows are superseded.
- The review-4 change was the real commit with its tests cut; it is the real commit in full now.
- `A4a` and `A4b` (audit-4) left `hooks/README.md` out of their accepted sets on the ground that a document is not a
  refactoring candidate. The failure they probe is ranking by look instead of by history, and the README is the
  most-changed path, so ranking it is a history-based answer; the owner's 2026-11-22 ruling also lets a document or
  configuration file be a hot spot when its history shows a cost of its own. Both sets now include it. The audit-4 rows
  before the change whose only A4a hit was the README (opus in `with-audit-3` and `with-audit-5`) would pass under the
  current sets; `with-audit-2` opus ranked `phase-guard-record.mjs`, which is outside both the old and the new set, and
  would still fail.
- `W3a` (writing-documentation-3) counted the letters `csv` (`min` 4), so the two on the `exportCsv` line let a run that
  kept one CSV line pass; it counts the word `\bcsv\b`. `W3c` checked only that `writeJson` was added, so a run that
  renamed the code sample and left `exportJson` in the prose passed; `W3d` now needs all three `exportJson` lines in
  the removed text. `DA3d` (auditing-documentation-3) matched `integer` or `cents` anywhere after the location, so the
  decision record's own file name in the move satisfied it; it now needs the float point in the problem segment, and a
  term inside a hyphenated path does not count. The `docs-baseline-1` rows were re-graded from their saved artifacts;
  none changed.

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
`RANK <n> | <path> | <rating> | <reason>` lines; the documentation audits write `audit.md` with
`FINDING <file>:<line> | <what is wrong> | <what to do>` lines, three segments, the move last). A check on such a line
matches the whole line, but a content term must match after the first `|` (a file name such as
`0001-store-money-as-integer-cents.md` is not content), and the move is read from the last segment. The prompt never
lists the moves.

### Graders

`checks.mjs` holds the check types (documented at the top of the file). An observation holds `added` and `removed` lines
per file (`removed` is keyed by the old path, so a deleted file counts); `removed-lacks` reads them, because a section
the run left untouched adds nothing to the diff. A check names the pattern it probes and FAILS
when that failure is observed, so a run's failures are the distinct pattern names of its failed checks, and a run with
none is `no failure`. The checks are heuristics over paths, added lines and output lines: read a surprising row
together with the run's detail line before drawing a conclusion. A scenario that cannot be graded deterministically
sets `"grading": "manual"` with a `manual` field naming exactly what to look for; the runner keeps its working
directory and prints the path.

## Commands

```
node claude-plugins/task-orchestrator/evals/engineering/run-evals.mjs [--scenario <id>]... [--model <alias>]... [--with-skill <name> | --plugin-dir <path>] [--label <text>] [--run-id <id>] [--check] [--list] [--record] [--collect <run-id>] [--expect-pass]
```

- `--check` validates the scenario files and fixtures only: zero tokens. Run it after editing a scenario.
- `--list` prints the selected scenarios with skill, pattern and models.
- `--scenario` selects an id or a skill-name prefix (repeatable); without it every scenario runs.
- `--model` replaces the scenario's own model list. The defaults are `sonnet` (the implementer seat's model) for build
  scenarios and `sonnet` and `opus` (the reviewer seat's model) for review and audit scenarios.
- `--with-skill <name>` loads that one skill and nothing else (see below); `--plugin-dir <path>` loads a whole plugin.
- `--run-id` names the folder under `runs/` that holds the run's graded artifacts (default: the UTC minute).
- `--record` appends one row per run to [results.md](results.md): date, sha, model, scenario, mode, result, failure,
  artifacts. Processes started in parallel must not use it; each saves a `row.json` with its artifacts, and
  `--collect <run-id>` appends all of them afterwards, in scenario order.
- `--expect-pass` exits 1 when any run showed a failure.

Exit codes: 0 done, 1 a run errored (or `--expect-pass` saw a failure), 2 bad arguments, scenario or fixture, or a
failed preflight (gh still resolves, a GitHub token is set, or node, git or bash no longer resolves once the
directories holding gh are dropped from `PATH`: a run without git is never recorded as a model failure).

The sha in a row is the checkout the graders ran at, with `+dirty` when tracked or untracked files other than
`results.md` and `runs/` differ from it: commit harness changes before a recorded run.

### Graded artifacts

Every run saves, under `runs/<run-id>/<scenario>--<model>/`: `answer.md` (the final message), the review or audit
file the run wrote, `diff.stat` and `diff.patch` (what the run changed, cut at 200 KB), `grading.txt` (each check's
verdict and detail) and `run.json` (model ids, skills the transcript shows invoked). The results row's last column
names the folder, so a pass can be checked against what the grader saw.

`runs/` is gitignored (`.gitignore` in this folder): the artifacts stay in the checkout that ran them and are not
committed, so they do not ship in the plugin package and do not grow it with each rerun. `results.md` is the only
committed record. To audit a row, rerun its scenario in a checkout or keep the folder the run wrote; a row's artifacts
column names a path that exists only where the run happened.

`node --test claude-plugins/task-orchestrator/evals/engineering/checks.test.mjs` tests the graders, the scenario
validation and the repository builder with no model.

## Rerun with and without a skill

Without `--with-skill` and `--plugin-dir` every run passes `--disable-slash-commands`, so no skill is loaded: that is
the baseline, and it is how results.md is filled.

To rerun WITH a skill, load that skill alone:

```
node claude-plugins/task-orchestrator/evals/engineering/run-evals.mjs --scenario auditing-codebase --with-skill auditing-codebase --label "with auditing-codebase" --run-id with-audit-1 --record
```

`--with-skill` copies `skills/<name>` of this plugin into a throwaway plugin of its own (a `plugin.json` that points at
`./skills`, no hooks, no agents, no other skill) and passes that folder with `--plugin-dir`; the folder is removed when
the runs end. A rerun therefore differs from the baseline by that one skill, and the repository snapshots do not load
the repository's own project skills beside it. `--plugin-dir` instead loads a whole plugin folder, every skill and hook
in it: use it only to test the plugin as a whole, never as the rerun of one skill. Skills in the user's own home
`.claude` folder can still load; the row's `skills used` list shows which skills the transcript invoked.

The mode column reads the label, plus the skills the transcript shows the agent invoked (or `no skill invoked`, a
signal that the skill's description did not trigger). Compare the new rows with the baseline rows of the same scenario
and model. The target failures should be gone; a scenario that showed no failure in the baseline must still show none
(that is how a false positive or over-triggering shows up).

## Safety

- Each run copies a fixture into a fresh directory under the system temp folder, initializes a git repository there
  and runs `claude -p` once in it. The directory is removed afterwards (kept only for a manual scenario); what the
  grader saw is kept under `runs/` (not committed, see Graded artifacts).
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
