# Retrospective metrics reference

What `run-planner.mjs retro-metrics` reads and how its figures are defined. The skill's Step 1 runs the command once and reads its numbers from the one JSON document (`run-wave/retro-metrics-v1`); nothing here is computed by hand. This file is the grammar and the thresholds behind that document.

Contents: [The command](#the-command) | [Provenance grammar](#provenance-grammar) | [Legacy forms](#legacy-forms) | [Layer meters](#layer-meters) | [Fix-cycle cost](#fix-cycle-cost) | [Session coverage](#session-coverage) | [Note volume](#note-volume) | [Defects](#defects) | [The retrospective's own cost](#the-retrospectives-own-cost) | [Troubleshooting](#troubleshooting)

## The command

```
node "<helper>" retro-metrics --root <rootId> [--run <runId>]... [--item <itemId>]... [--session-dir <dir>] [--config <path>] [--since <ISO>] [--self-only]
```

`<helper>` is resolved as in the run-wave SKILL.md, Step 0. The command exits 0 with one JSON document and exits 2 only for a bad argument. Without a REST API the document is `{contract, measured: false, reason}`. A read that fails marks its own section `{measured: false, reason: <code>}` (or lands in `gaps`) and the other sections continue. `--self-only` answers `{contract, self}` alone (Step 8b).

| Member | Content |
|---|---|
| `delegations[]` | per item: `form` (`structured`, `legacy`, `none`), `seats` (`seat`, `type` with the `-a<n>` suffix stripped, `model`, `effort` or null), `isolation`, `extraSeats` |
| `perRun[]` | per run: `adapter`, `agents`, `tokens`, `cacheRead` (`known` sum, `unknown` count), `duration`, `orchestratorTurns` |
| `perLayer[]` | per run with a layer line: rows `seats`, `coordinator`, `session` with the four meters, `tokens`, `unknown` |
| `fixCycle` | `cycles`, `loopTokens`, `runTokens`, `loopShare`, `unknownTokenSeats`, `findings` |
| `sessionCoverage` | one row per agent class, `main`, `recorded`, `total`, `pct`, `unlabelledAgents` |
| `noteVolume` | `window`, `reviewShare`, `reviewChecklistAvgBytes`, `largest`, `workAvgBytes`, `overruns`, `rewrites`, `baselines` |
| `defects[]` | `item`, `component`, `tool`, `op`, `code`, `message`, `fingerprint` |
| `self` | the retrospective's own cost, or `{measured: false, reason}` |

Every token figure is the non-cached measure (input + cacheWrite + output) with cacheRead kept apart. `unknown` is a gap, never zero.

## Provenance grammar

A `delegation-metadata` note whose first line matches `^adapter=\S+( [a-z-]+=\S+)+$` is a structured provenance line, not legacy prose: split on spaces, then each token on `key=value`. Each `seat:model` pair inside `seats=` is one delegation, typed by seat rather than guessed from prose: planner is architecture on opus; implementer and test-author run on sonnet; declarations-extractor is code reading on sonnet; reviewer runs on opus; any other seat is recorded but not scored. `isolation=` gives the isolation. `model=` is ignored whenever `seats=` is present. Field order is fixed: `adapter= run= seats= model= isolation= agents= tokens= duration= deferred= in-run-edges= orchestrator-turns=`. Example:

```
adapter=claude-workflow run=r-20260928-b2d seats=planner:opus,implementer:sonnet,declarations-extractor:sonnet,test-author:sonnet,reviewer:opus model=sonnet isolation=worktree:.claude/worktrees/feat-b2-front-door agents=5 tokens=384010 duration=unknown deferred=0 in-run-edges=1 orchestrator-turns=3 meters=10/280000/3500000/104000 seat-meters=planner:2/30000/200000/9000,implementer:3/90000/1500000/40000,declarations-extractor:1/20000/100000/5000,test-author:2/80000/900000/30000,reviewer:2/60000/800000/20000
```

Optional tails:

- `efforts=<seat>:<effort>,...` (`low`, `medium`, `high`, `xhigh`, `max`; a seat absent from it had none recorded), `effort-source=transcript|assigned|mixed` (`transcript`: read from the seat's own transcript, the effort it ran at; `assigned`: the plan's dispatch effort only, which proves nothing about the run), `effort-substituted=<seat>:<requested>,...` (seats whose observed effort differs from the assigned one).
- `tokens=` is the non-cached transcript total of the line's seats and `unknown` when any seat lacks meters. `duration=` reads `unknown` (there is no per-seat source).
- `meters=<input>/<cacheWrite>/<cacheRead>/<output>` (the item's sum, or `unknown`) and `seat-meters=<seat>:<i>/<cw>/<cr>/<o>,...` (in `seats=` order; a seat with no meters is `<seat>:unknown`) carry the four transcript meters, so cache read is reported on its own and never added to `tokens`.
- `substituted=` is an allowlist substitution, not a misalignment. `model-source=self-report` is noted apart from the misalignment count.

An optional second line `extra-seats=<seat>:<model>:<tokens>:<effort>:<cause>:<input>/<cacheWrite>/<cacheRead>/<output>,...` lists seats dispatched outside the run. A six-field row carries its four meters, which belong to the item's seats layer. A five-field row (an older note) has none, and a three-field `<seat>:<model>:<tokens>` row has no effort or cause: both read `legacyBasis` and stay out of the sums. A seat is typed after stripping a trailing fix-cycle suffix `-a<n>` (`reviewer-a1` is a reviewer). `cause` is `review`, `test`, `carry-in` or `breach`; `tokens` is the non-cached sum of the seat transcript's meters (never the Agent-reported figure).

## Legacy forms

- **Prose.** Free-form text naming model and isolation reads `form: legacy`. A run that mixes both forms scores both.
- **`see:<short>`.** A pre-4.0.1 note may read `tokens=` or `duration=` as `see:<short>`: the run total lives on that item, so it counts once per run and is never summed per item. From 4.0.1 on `see:` is never written.
- **Five- and three-field `extra-seats` rows**, as above.
- **No note.** An item without `delegation-metadata` reads `form: none`; the delegation score is skipped for it.

## Layer meters

A line `layer-meters=<layer>:<i>/<cw>/<cr>/<o>,... run=<runId>[ session-window=run|whole-session]` (on the run's target parent's `delegation-metadata`, one per run id, or in the run report) is the cost of the two layers above the seats: `coordinator` (the tier-2 or tier-2b orchestrator's own transcript) and `session` (the launching session's transcript; `session-window=run` when limited to the run window, `whole-session` when no window flag was given or no usage line carried a timestamp). It is layer cost, not a delegation, and is not scored. `<layer>:unknown` is a gap. The `seats` row of `perLayer` is the sum of the run's item `meters=` tails plus the six-field `extra-seats` meters; a run with no layer line has no `perLayer` rows.

## Fix-cycle cost

A failed review or test sends the fix to a fresh seat at a raised effort (base effort plus a step of 1 plus the seat's earlier review or test cycles, capped at the ceiling), so the failure loop is measured. The loop is every `-a<n>` extra-seats row with cause `review` or `test`: the amending planner, the implementer, the test author and the re-review of each cycle `A<n>`. Carry-in and breach rows are reported apart and are not in the loop. For a fix-cycle run (`<runId>-a<n>`) the figures cover the base run's whole loop, every cycle so far.

`fixCycle` carries: per cycle the item, cycle number, cause, seats, model, effort, tokens and verdict (read from the `Review: <verdict> after A<n>` line of the item's `session-tracking`); `loopTokens`; `runTokens` (the run's `tokens=` plus every `extra-seats` row); `loopShare` (loop over run, a fraction); `unknownTokenSeats` (rows reading `unknown`).

Raise a finding when any of these holds. They are starting thresholds to revisit after a few runs; the lever is `escalation.ceiling` in the plugin's dispatch policy (one level lower cuts the most expensive step):

- Loop tokens exceed 25% of the run's tokens (`fixCycle.findings` carries `loop-share-over-25pct`).
- The loop's share rose across the last three retrospectives (read from the `fix-cycle-cost` trend's evidence notes).
- Cycles at the escalation ceiling pass no more often than cycles one level below it.

Rows from before effort and cause were recorded have three fields and count toward tokens but not toward by-effort pass rates; say so beside the figures.

## Session coverage

`sessionCoverage` is the session sweep (`session-meters`) summarised: one row each for `seat`, `lane`, `red-proofs`, `retrospective`, `coordinator`, `research` and `unlabelled` (a class absent from the sweep reads 0), then `main` (null reads unknown). `recorded` is the summed `tokens` of the agent rows whose label `<name>:<short>` is recorded: `<short>` is a run member (the target parent for `reviewer:`) whose `delegation-metadata` names `<name>` in `seats=` or as an `extra-seats` row seat. `total` is the summed `tokens` of all agent rows (`main` is in neither), `pct` is `recorded` over `total` (null when `total` is 0). `unlabelledAgents` lists each unlabelled agent by label, `(no description)` for a null label. Figures are token volume by meter and model. The red-proof `agent-meters=` lines on the wave parent are listed beside the table; they are not in the recorded set.

## Note volume

Read over REST at `POST /api/v1/ops/report`, select `notes`, grouped by `role` and by `noteKey`, following `page.next`. The window starts at `--since`, else at the `createdAt` of the newest `session-retrospective` item whose title holds the project name or root id, else 7 days ago, and is never longer than 31 days. `maxLength` per note key comes from the resolved schema of each member type. `reviewShare` is the review role's share of all bytes; `overruns` lists the note keys whose largest write exceeds the smallest `maxLength` among the member types that declare the key (the report groups by one dimension, so a write carries no type), with `capsByType` added when those caps differ (advisory under the default `warn` mode; `reject` refuses the write). The report counts every `note.upserted` write, so a rewrite counts again and `avgBytes` is per write; say so beside the figures when `rewrites` is not small. `baselines` echoes the project's dated figures from `extensions.claude-code.retrospective.baselines` in its `.taskorchestrator/config.yaml` (null when absent); compare against them and say what moved.

## Defects

A `session-tracking` entry `defect: <tool>, <op>, <code>, <message>` records one server or plugin defect; the message ends at the next `defect: `, at `; ` or at the end of the line. `component` is `server` when `<tool>` is one of the server's tools, else `plugin`. The fingerprint is `to-bug-` plus the first 8 hex characters of the sha256 of `component|tool|op|code|<normalised message>` (lowercased): the message is lowercased, a token holding a slash or backslash becomes `<path>`, a UUID or a hex run of 8 or more characters becomes `<id>`, a digit run becomes `<n>`, whitespace collapses.

## The retrospective's own cost

`self` is the retrospective agent's own transcript, found by its label `retrospective:<first 8 characters of the first run id>` in the `--session-dir` sweep: `tokens`, the four meters, `wallMs` (last minus first usage-line timestamp), and `analysedShare` (its tokens over `fixCycle.runTokens`). It is partial, because the transcript is still being written when the number is read; Step 8b re-reads it with `--self-only` after the last write. Without a label or a session folder it reads `{measured: false, reason}`.

## Troubleshooting

No `session-tracking` notes: the items' schema does not require it. A run dispatched again: its `retro` mark is missing (REST-only), so run `retro-ack.mjs --run <runId>` by hand. Another project's items: set `project.rootId`, pass the root UUID or `--run`.
