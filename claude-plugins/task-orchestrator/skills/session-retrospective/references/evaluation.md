# Aggregation, dimensions and meta-evaluation

The detail behind Steps 2, 3 and 8 of the skill; finding kind and scope (3f) and routing are in [routing.md](routing.md). Every figure is read from the `retro-metrics` document; its grammar is in [metrics.md](metrics.md).

Contents: [Aggregate](#aggregate) | [Dimensions](#dimensions) | [Meta-evaluation](#meta-evaluation)

## Aggregate

Item count and outcome distribution: success, partial, failed, skipped, and `implemented, review pending` only while the named review note is absent. A note ending `Review: pending - verdict in <key>` takes its outcome, deviation count and first-review blocking count from that review note (final verdict, amendment sections) instead of the frozen pre-review text. A fix-cycled item ends `Review: <verdict> after A<n> (mechanical|ruling) - <key>` with the Outcome `implemented, review <verdict> after A<n>`: count it by that final verdict and read the first-review blocking count from the named note. Report:

- the first-review fail rate (items whose first verdict was Fail over items reviewed), the combined friction list and the combined observations;
- `fixCycle`: loop tokens and their share of the run's tokens, the cycles (item, cause, seats, model, effort, tokens, verdict) and the seats with unknown tokens. State how far to trust them: unknown-token seats are gaps, and runs from before effort and cause were recorded have three-field rows;
- `noteVolume` against its `baselines`, and what moved. The report counts every upsert, so a run with many rewrites reads higher than a baseline measured on final bodies: say so beside the figures when `rewrites` is not small. The `maxLength` cap is advisory under the default `warn` mode (`reject` refuses the write);
- `defects` grouped by `fingerprint`, each with its item ids.

## Dimensions

- **3a Schema effectiveness.** For each item, which schema-required notes exist with non-empty content (queue and review notes; work-role notes are not scored by size, 3c scores them on content), and which items miss a required note. Score: the fraction of expected notes that exist non-empty.
- **3b Delegation alignment.** Cross-reference each delegation with the expected model: the model of the work's class in `task-orchestrator:model-effort` (a seat's resolved dispatch profile, when it set one, is the expectation instead).
  - Flag misalignments (a delegation run on a model other than its class's or its profile's), scored per seat delegation for a structured note (one score per `seat:model` pair).
  - A `substituted=` field is an allowlist substitution, not a misalignment. A `model-source=self-report` field is noted apart and not folded into the misalignment count.
  - Report effort beside model: each `seat:model/effort` (`-` when unset) in the per-run table with its `effort-source`, and the effort distribution per seat type across runs. Effort is not scored against an expected value; flag a standing pattern (a seat type always at `max`, a review seat below `medium`, an effort set on a seat whose model was substituted) as a finding so a trend can form. Every `effort-substituted=` entry is a dispatch-versus-runtime mismatch and always a finding; a run whose lines all read `effort-source=assigned` reads "effort not observed" (the post-run `dispatch-meta` step was skipped).
  - Score: the fraction of delegations matching the expected model. When every `delegations[]` entry has form `none`, note "delegation metadata not recorded" and skip scoring.
  - Tables: `perRun` as `| adapter | run | agents | tokens | cacheRead | duration | orchestrator-turns |` (`cacheRead` is the known sum, with `+<n> unknown` beside it when `unknown` is above 0); `perLayer` as `| run | layer | input | cacheWrite | cacheRead | output | tokens |` (a `legacyBasis` count on the `seats` row says how many five- and three-field rows stayed out of the sums).
- **3c Note effectiveness.** For items with both queue-phase notes (specs) and work-phase notes, compare themes: "deviated from spec", "unexpected" or "assumption was wrong" flags a spec gap, and so does a fix-cycle amendment (an `Amendment A<n>` section or amendments note raised after a failed first review). Then take the derivability sample: the longest `implementation-notes` of the run, each sentence marked derivable (it states what the diff, git log or CI show) or not, and report the non-derivable share (a sanctioned empty-form body scores fully non-derivable). Score: effective at >= 70% non-derivable, mixed, ineffective.
- **3d Plan-to-execution alignment.** Creation times come from one `items_read` `list` with `fields: ["createdAt", "modifiedAt", "summary"]` scoped by `ancestorId` (the root, or the run's `scopeId`); with neither, `get` each in-scope item with `fields: ["createdAt"]` (at most 20). Items created more than 1 hour after the root (or the earliest item) are ad-hoc additions (necessary, or scope creep); items still in queue under the root were planned but skipped. The types `retrospective-trend`, `container`, `improvement-proposal`, `agent-observation` and `session-retrospective` are not planned work and stay out of both counts. In a stale run (defined in the skill, Step 1: an abandoned scope, never one merely still in flight) an unfinished item reads as abandoned, not skipped. Score: the fraction of planned items that reached terminal.
- **3e Friction synthesis.** Group friction entries by type (`tool-error`, `excessive-roundtrips`, `workaround`, `api-confusion`) and name themes across entries ("3 entries about gate failures on items without schemas"). A `defect: <tool>, <op>, <code>, <message>` entry is not themed here: Step 5e routes it ([routing.md](routing.md)).

## Meta-evaluation

Run only with 3 or more prior retrospectives. Read proposal state from the container (an anchored `overview`; `includeChildren` is not allowed with `anchorId`, and each child entry carries its `role`), and with a project root also the project-scoped proposals, which anchor outside the global container.

1. **Trend durability.** Find trends whose summary carries `GRADUATED -> proposal <id>` (`items_read` `search` with `query: "GRADUATED"`, `kinds: ["item"]`, `tags: ["retrospective-trend"]`) and check whether each proposal is terminal.
2. **Proposal staleness.** Proposals created 3 or more retrospectives ago and still in queue, or stuck in `work` (usually a stalled adoption). With stale queue proposals the report names `review-proposals`. A `cancelled` proposal is resolved-rejected, not stale: read its `adoption-decision` note before proposing anything similar again.
3. **Self-quality.** Are notes converging on useful patterns or repeating observations; too verbose (over 800 tokens) or too shallow (under 100)?

If meta-findings warrant it, re-upsert the current retrospective's `improvement-signals` note with the meta-evaluation appended.

## Session metrics note

The `session-metrics` queue note carries: item counts and outcomes, schema usage, the `noteVolume` figures (work-role `avgBytes`, `maxLength` overruns, the baselines and what moved), the `fixCycle` figures, and the session coverage. Session coverage is the `sessionCoverage` table (`| class | agents | input | cacheWrite | cacheRead | output | tokens |`, one row per class, then `main`), the line `Recorded share: <recorded> of <total> tokens (<pct>%)`, the unlabelled agents by label with their tokens, and the red-proof `agent-meters=` lines beside the table; with no session folder or a failed sweep it reads `Session coverage: not measured (<reason>)`. Step 8b adds `Retrospective cost: ...`.

## Standing trends

Two trends get one evidence note every run, whatever the run held; create each on first use with tag `delegation`.

- `trend: fix-cycle-cost - ...`: the `fixCycle` figures (loop tokens, loop share, cycles by effort with pass rate, items at the ceiling, unknown-token seats; `no fix cycles` when there were none). Its evidence notes are what the "share rose across three retrospectives" check reads. It graduates to a proposal only through a raised finding, never by its session count alone.
- `trend: retro-cost - ...`: `self.tokens` and `self.analysedShare`, or `unknown (<reason>)`. Raise a finding when the share is above 5% in this note and in the two previous `retro-cost` notes.

## Trend tags

A new trend carries the tags `retrospective-trend` and one dimension: `schema-effectiveness`, `delegation`, `note-quality`, `plan-to-execution`, `friction` or `extension-candidate`; add `positive` for a positive pattern.
