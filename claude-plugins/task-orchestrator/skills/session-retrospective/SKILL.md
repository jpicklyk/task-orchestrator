---
name: session-retrospective
description: "Analyzes a closed implementation run (schema, delegation, notes, plan-to-execution), routes defects to items, records cross-run trends and proposes improvements when patterns repeat. Use when a run closes (the hook dispatch directive) or when explicitly asked for a 'retrospective', 'session review' or 'analyze this run'."
argument-hint: "[optional: root item UUID] [--run <runId>]... (repeatable) [--session-dir <dir>] [--dry-run to preview without creating items]"
context: fork
agent: general-purpose
model: sonnet
effort: medium
---

# Session Retrospective

Post-run analysis of five dimensions; findings persist in MCP, defects are routed to items, and trend memory turns repeated findings into improvement proposals. It runs unattended: it asks nobody anything; where an input is missing it states the assumption in the report and carries on.

Call shapes: [`../_generated/call-shapes.md`](../_generated/call-shapes.md); read each write's `data.results[i]` (`ok`, `error.code`). Trigger mechanics: hooks README, "Retrospective trigger"; the grammar and thresholds behind the numbers: [`references/metrics.md`](references/metrics.md).

- [ ] 0 Mode check, 1 Gather scope and run `retro-metrics`
- [ ] 2 Aggregate, 3 Evaluate (3a-3f), 4 Trend memory
- [ ] 5 Persist (5a-5e), 6 Update trends, 7 Proposals
- [ ] 8 Meta-evaluation, 8a Verify adopted proposals, 8b Close, 9 Report

Gate loop, before every `advance`: read the gate (`items_read` `context`), fill what `gateStatus.missing` names, `advance`. On `gate_blocked` re-read the context, fill the rest, retry once, then report the missing notes and stop.

## Step 0 - Mode Check

- `--dry-run` sets **DRY_RUN**: do steps 1-4 and 9, skip 5-8, and announce `**Dry run** - no items created, no memory updated.`
- `--run <runId>` scopes the analysis to a run and repeats (`--run A --run B`): the union of the runs' members, one retrospective. It wins over a root UUID.
- `--session-dir <dir>` is a Claude Code session folder (main transcript `<dir>.jsonl`); without it the session sweep is unmeasured.

## Step 1 - Gather Scope and Numbers

**Items.** Per `--run`: `runs_read` `get` (`rootId`, `scopeId`, `marks`), `seats` (`ended: true`) and `frontier`. Members are the distinct seat `itemId`s plus the frontier's `waiting` and `inFlight` ids, deduplicated on 8 hex characters; with none, the `scopeId` overview. A root UUID is the whole scope (`items_read` `list`, `ancestorId`, `limit: 200`). With neither, list `role: terminal` items modified in the last 24h under the project root (`## Project Scope` or `project.rootId`), else unscoped.

**Run marks.** `Run marks: <run8> <keys>` goes in `session-metrics`; `retro-retry` or `retro-failed` is a friction finding, `retro-skipped` reads "triaged low-signal". A run is **stale** only when the dispatch directive says its scope never completed or, for a manual run, when its `scopeId` item is not terminal, `runs_read` `list` (`rootId`) shows no open or paused run with that `scopeId` and its newest run closed over 7 days ago; a scope merely not terminal yet is not stale. A stale run's title is `[stale] Retrospective - ...`, its header `Stale: scope <8hex> never completed - not a delivery review`; 3d reads its unfinished items as abandoned.

**Notes.** Per item (at most 20) `get` `session-tracking` only (delegation figures come from `delegations[]`). Defect-bearing inputs:

- **Observations:** `list` `type: agent-observation`, `createdAfter` the earliest run `openedAt` (no `--run`: earliest in-scope `createdAt`, else now minus 24h); skip `retro-reported`; `get` `observation-detail` (at most 20).
- **Review record:** only items with a fix cycle (`after A<n>`) or Deviations or Friction not `none` (at most 10): `get` `orchestrator-confirmation`; for its `follow-up` and `accepted:` lines read the findings register once (`review-checklist`, else the first review note).

**Numbers.** Run the metrics command once (helper resolved as in the run-wave SKILL.md, Step 0) and read every figure from its JSON:

```
node "<helper>" retro-metrics --root <rootId> --run <runId> [--run <runId>]... [--session-dir "<dir>"]
```

A `measured: false` document or section is a gap: say so and carry on. No items in scope, or no `session-tracking` note: exit with `No implementation run data found. Nothing to retrospect.`

## Step 2 - Aggregate

Outcome distribution and the first-review fail rate (a `Review: pending - verdict in <key>` or `Review: <verdict> after A<n> ...` note counts by the named review note's final verdict), the friction and observations, and from the document `fixCycle`, `noteVolume` against its `baselines` and `defects` by `fingerprint`.

## Step 3 - Evaluate

Score five dimensions, rules in [`references/evaluation.md`](references/evaluation.md): **3a** schema effectiveness, **3b** delegation alignment (`delegations[]` against the class's model in `task-orchestrator:model-effort`), **3c** note effectiveness (spec gaps; the sample of the longest `implementation-notes`, each sentence derivable or not), **3d** plan-to-execution, **3e** friction themes, **3f** kind (`defect`, `process`, `agent-discipline`, `positive`) and scope (`product`, `global-process`, `project`) of every finding: rubric and F-line format in [`references/routing.md`](references/routing.md).

## Step 4 - Trend Memory

Global trends sit under the process-global `Retrospective Trends` container, a depth-0 root item (found, like the other two containers, by unscoped `items_read` `search` for the exact title, keeping only the hit with no parent); a project's under its own `Retrospective Trends` container, tagged `project-<root8>`. No container and `memory/retrospectives.md` holds `- <kebab-key>: ...` lines: run [`references/legacy-trend-migration.md`](references/legacy-trend-migration.md) first; neither: every finding is a new baseline. Otherwise list the global trends and, with a project root, the project trends (`Sessions: N` in each summary): a `project` finding matches only the project list, any other only the global list. Lists and matching: [`references/routing.md`](references/routing.md).

## Step 5 - Persist

**5a** Find the `Session Retrospectives` root item by exact title, else create it (`type: "container"`, `tags: ["container"]`).

**5b** For each `--run` search `run:<runId>` (`tags: ["session-retrospective"]`, `matchMode: "substring"`) and drop a run with a hit. None left: say `already retrospected <runId>`, run Step 8b's `retro-ack.mjs` and stop. Create the item (`type: "session-retrospective"`, `tags: ["session-retrospective"]`, `parentId` the container) titled `Retrospective - <project-name> - <root-item-title> - <YYYY-MM-DD> run:<runId>` (one `run:` per remaining run; a stale scope prefixes `[stale] `), summary one sentence.

**5c** One `notes_write` of three `upsert` elements, role `queue`: `session-metrics` (contents: `references/evaluation.md`, "Session metrics note"), `workflow-evaluation` (Step 3 scores and findings) and `improvement-signals` (the F-lines, trends, each candidate's scope, defects by fingerprint).

**5d** `advance` `start` on the retrospective; completion is Step 8b.

**5e** Route defects, no session threshold: each `defect` finding and `defects[]` entry becomes evidence on an open item, a regression item or a new `[bug]` observation tagged `retro-reported` (a project defect: a declared `bug-fix`); one an input observation records creates nothing. Procedure: [`references/routing.md`](references/routing.md). When the user-level `bug_reports` gate is open, run the `bug-report` command for every qualifying product defect (a closed gate files nothing: say so in `actions-taken`): [`../_shared/github-feedback.md#bug-reports-product-defects`](../_shared/github-feedback.md#bug-reports-product-defects). An `agent-discipline` finding creates no item and no trend, only a `memory:` line (Step 9).

## Step 6 - Update Trends

Recurrence: upsert `evidence-<YYYY-MM-DD>-<retro-short-id>` (role `work`), update the trend `summary` (`Sessions: N+1`). New: create `trend: <kebab-key> - <one-line claim>` (`type: "retrospective-trend"`, `tags: ["retrospective-trend", "<dimension>"]`, `priority: "low"`) under its scope's container, with a first evidence note. `<dimension>` is one of `schema-effectiveness | delegation | note-quality | plan-to-execution | friction | extension-candidate`; add `positive` for a positive pattern and `project-<root8>` for a project trend. Standing trends, defect evidence, retire and graduation: [`references/routing.md`](references/routing.md).

## Step 7 - Proposals

For each trend touched in Step 6 with **Sessions >= 2** (a standing trend only when this run raised a finding and an earlier evidence note recorded one):

**7a** Find or create the `Improvement Proposals` root item (unscoped, as 5a).

### 7b. Create proposal items
Scope is its trend's: a `project-*` tag is project scope (`project-specific`); any other trend is `product` when every F-line routed to it this run is `scope=product`, else `global-process`. Create the item (`type: "improvement-proposal"`, `tags: ["improvement-proposal"]`, `priority: "low"`, title `Proposal: <concrete change>`) under the 7a container (project scope: the per-project container, [`references/routing.md`](references/routing.md)). Fill its `proposal` note straight after the create, or `review-proposals` is gate-blocked:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<proposal-uuid>", "key": "proposal", "role": "queue", "body": "Problem and evidence: <pattern, retrospective ids, sessions>.\nChange: <exact YAML or rule text, skill file and section, or hook event, matcher and purpose>.\nExpected effect: <measurable effect and how later runs measure it>.\nBlast radius: <schemas, skills or agents touched>.\nScope: <product | global-process | project-specific; target rootId when project-specific>."}}
```

### 7c. GitHub issues (product-scope proposals only)
Files only a proposal whose `Scope:` is `product`. Best-effort: a failure is a one-line reason, the retrospective continues. Gate, guard, dedup, template, record-back: [`../_shared/github-feedback.md`](../_shared/github-feedback.md).

## Step 8 - Meta-Evaluation

**Skip in dry-run.** List prior retrospectives (`items_read` `list`, `tags: ["session-retrospective"]`, `limit: 20`). With 3 or more, evaluate as [`references/evaluation.md`](references/evaluation.md) says and append meta-findings to `improvement-signals`.

### 8a. Verify Adopted Proposals

Every non-dry run, outside the 3-or-more gate. List accepted ones (role `review`) under the global `Improvement Proposals` container and, with a project root, under `<rootId>`; follow `page.next`, sort the union by `roleChangedAt` (oldest first) and verify at most 10 per run (a skip does not count):

```to-call
{"tool": "items_read", "select": "list", "args": {"type": "improvement-proposal", "role": "review", "ancestorId": "<container-or-root-id>", "limit": 200, "fields": ["roleChangedAt"]}}
```

The adoption date is in the `adoption-decision` note; count the retrospectives created after it (a project proposal: that project's only). **Fewer than 2: skip and write nothing.** Otherwise compare the later retrospectives and items with the `proposal` note's expected effect, write the verdict, then complete the proposal, checking each result:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<proposal-uuid>", "key": "outcome-verification", "role": "review", "body": "Verdict: <effective|ineffective|inconclusive>. <evidence: later retrospective ids and what they showed; what to try instead when ineffective>."}}
```

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<proposal-uuid>", "trigger": "complete"}}
```

Then update the source trend: `effective` `cancel`s it (`summary: "archived: verified effective via proposal <short-id>"`) unless terminal; otherwise `reopen` a terminal trend and append `proposal <short-id> <verdict> <date>` to its `summary`.

## Step 8b - Close

**Skip in dry-run.** Re-upsert `session-metrics` with `Retrospective cost: <self.tokens> tokens, <self.wallMs> ms, <self.analysedShare> of the run (partial)`, or `Retrospective cost: unknown (<reason>)`:

```
node "<helper>" retro-metrics --root <rootId> --run <runId> --session-dir "<dir>" --self-only
```

Upsert the work note `actions-taken`: proposals (title, short id), bug items (short id, `created` or `evidence`), memory lines, each `bug-report` result `line`, each global proposal's GitHub outcome (`filed <url>`, `linked existing <url>`, `github filing skipped: <reason>`), each project proposal's anchor, trends created, updated or retired, adopted proposals verified. Then the gate loop:

```to-call
{"tool": "items_read", "select": "context", "args": {"itemId": "<retro-uuid>"}}
```

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<retro-uuid>", "trigger": "complete"}}
```

Then mark the runs (`--run` scope only), one call:

```
node "${CLAUDE_PLUGIN_ROOT}/hooks/retro-ack.mjs" --run <runId> [--run <runId>]...
```

Report its lines. An unexpanded `${CLAUDE_PLUGIN_ROOT}` skips the step; `not marked ...` and `mark failed` lines are not errors.

## Step 9 - Report

Header `<YYYY-MM-DD> - <N> items`, a `Dimension | Score | Key Finding` table, Trends (`Pattern | Sessions | new, reinforced or addressed`) and Proposals Created (`ID | Proposal | Trigger | Issue`); omit a section with no data. A dry run starts with the dry-run line, a stale scope with the `Stale:` header. End with one `memory: <pattern> -> <correct behaviour> (<reason>)` line per `agent-discipline` finding.
