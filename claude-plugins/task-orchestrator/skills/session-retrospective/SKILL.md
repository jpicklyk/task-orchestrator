---
name: session-retrospective
description: "Analyzes a closed implementation run: schema effectiveness, delegation alignment, note quality and plan-to-execution fit, then records cross-run trends and proposes improvements when patterns repeat. Use when a run closes (the hook dispatch directive) or when explicitly asked for a 'retrospective', 'session review' or 'analyze this run'."
argument-hint: "[optional: root item UUID] [--run <runId>]... (repeatable) [--session-dir <dir>] [--dry-run to preview without creating items]"
context: fork
agent: general-purpose
model: sonnet
effort: medium
---

# Session Retrospective

Post-run analysis of five dimensions; findings persist in MCP and trend memory turns repeated findings into improvement proposals. It runs unattended: it asks nobody anything; where an input is missing it states the assumption in the report and carries on.

Call shapes: [`../_generated/call-shapes.md`](../_generated/call-shapes.md); read each write's `data.results[i]` (`ok`, `error.code`). The trigger mechanics are in the hooks README ("Retrospective trigger"); the grammar and thresholds behind the numbers are in [`references/metrics.md`](references/metrics.md).

- [ ] 0 Mode check, 1 Gather scope and run `retro-metrics`
- [ ] 2 Aggregate, 3 Evaluate, 4 Trend memory
- [ ] 5 Persist, 6 Update trends, 7 Proposals
- [ ] 8 Meta-evaluation, 8a Verify adopted proposals, 8b Close, 9 Report

Gate loop, before every `advance`: read the gate (`items_read` `context`), fill what `gateStatus.missing` names, `advance`. On `gate_blocked` read the context again, fill the rest, retry once, then report the missing notes and stop.

## Step 0 - Mode Check

- `--dry-run` sets **DRY_RUN**: do steps 1-4 and 9, skip 5-8, and announce `**Dry run** - no items created, no memory updated.`
- `--run <runId>` scopes the analysis to a run and repeats (`--run A --run B`): the scope is the union of the runs' members, covered by one retrospective. It wins over a root UUID.
- `--session-dir <dir>` is a Claude Code session folder (the main transcript is `<dir>.jsonl`); without it the session sweep is unmeasured.

## Step 1 - Gather Scope and Numbers

**Items.** Per `--run`: `runs_read` `get` (gives `rootId`, `scopeId`), `seats` (`ended: true`) and `frontier`. Members are the distinct seat `itemId`s plus the frontier's `waiting` and `inFlight` ids, deduplicated on the first 8 hex characters; with none, the `scopeId` overview. A root UUID is the whole scope (`items_read` `list`, `ancestorId`, `limit: 200`). With neither, list `role: terminal` items modified in the last 24h under the project root (`## Project Scope` or `project.rootId`; a personal root is not one), else unscoped.

**Notes.** Per item (at most 20) `get` `session-tracking` and `delegation-metadata`.

**Numbers.** Run the metrics command once (helper resolved as in the run-wave SKILL.md, Step 0) and read every figure from its JSON:

```
node "<helper>" retro-metrics --root <rootId> --run <runId> [--run <runId>]... [--session-dir "<dir>"]
```

A `measured: false` document or section is a gap: say so in the report and carry on. No items in scope, or no `session-tracking` note: exit with `No implementation run data found. Nothing to retrospect.`

## Step 2 - Aggregate

Outcome distribution and the first-review fail rate (a note ending `Review: pending - verdict in <key>` or `Review: <verdict> after A<n> ...` counts by the named review note's final verdict), the friction and observations, and from the document `fixCycle`, `noteVolume` against its `baselines` and `defects` by `fingerprint`. Detail: [`references/evaluation.md`](references/evaluation.md); thresholds and findings to raise: `references/metrics.md`.

## Step 3 - Evaluate

Score five dimensions, each with its rule in [`references/evaluation.md`](references/evaluation.md): **3a** schema effectiveness (required notes present), **3b** delegation alignment (`delegations[]` against the work class's model in `task-orchestrator:model-effort`, effort beside model, `perRun` and `perLayer` tables), **3c** note effectiveness (spec gaps, and the derivability sample: each sentence of the longest `implementation-notes` is derivable or not), **3d** plan-to-execution (ad-hoc and skipped items), **3e** friction themes.

## Step 4 - Trend Memory

Trends are items under `Retrospective Trends`. The three containers (Trends, Session Retrospectives, Improvement Proposals) are process-global root items, found by unscoped `items_read` `search` (`kinds: ["item"]`). Container absent or empty and `memory/retrospectives.md` holds `- <kebab-key>: ...` lines: run [`references/legacy-trend-migration.md`](references/legacy-trend-migration.md) first; both absent: every finding is a new baseline. Otherwise list the active trends (`Sessions: N` in each summary, the title's kebab key is the identity):

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["retrospective-trend"], "role": "queue", "limit": 100, "fields": ["summary", "createdAt", "modifiedAt"]}}
```

Match each finding to a trend by title and summary; a match increments `Sessions: N` in Step 6, anything else is a new-trend candidate. When unsure, search with the finding's keywords (`tags: ["retrospective-trend"]`).

## Step 5 - Persist

**Skip in dry-run.**

**5a** Find the `Session Retrospectives` root item by exact title, else create it (`type: "container"`, `tags: ["container"]`; `schema_violation` lists the declared types).

**5b** For each `--run` search `run:<runId>` (`tags: ["session-retrospective"]`, `matchMode: "substring"`) and drop a run that has a hit. None left: say `already retrospected <runId>`, run Step 8b's `retro-ack.mjs` for them and stop. Create the item (`type: "session-retrospective"`, `tags: ["session-retrospective"]`, `parentId` the container) titled `Retrospective - <project-name> - <root-item-title> - <YYYY-MM-DD> run:<runId>` (one `run:` per remaining run), summary one sentence.

**5c** One `notes_write` of three `upsert` elements, role `queue`: `session-metrics` (contents: `references/evaluation.md`, "Session metrics note"), `workflow-evaluation` (Step 3 scores and findings) and `improvement-signals` (Step 4 trends, each proposal candidate's scope, defects by fingerprint).

**5d** `advance` `start` on the retrospective; completion is Step 8b.

## Step 6 - Update Trends

**Skip in dry-run.** One `notes_write` for this run's evidence notes, one `items_write` for creates and summary updates.

- **Recurrence:** upsert `evidence-<YYYY-MM-DD>-<retro-short-id>` (role `work`: what happened, retro item id, cost or impact) and update the trend `summary` (`Sessions: N+1`, `Last seen: <date>`, condensed).
- **New pattern:** create `trend: <kebab-key> - <one-line claim>` (`type: "retrospective-trend"`, `tags: ["retrospective-trend", "<dimension>"]`, `parentId` the trends container, `priority: "low"`, summary `<observation>. Sessions: 1. Last seen: <date>.`) with its first evidence note; dimension tags are listed in `references/evaluation.md`.
- **Standing trends** `fix-cycle-cost` and `retro-cost` get one evidence note every run ([`references/evaluation.md`](references/evaluation.md), "Standing trends").
- **Retire** (addressed, obsolete, superseded): `advance` `cancel` with `summary: "archived: <reason>"`. **Graduation:** put `GRADUATED -> proposal <short-id>` in the summary.

Only `create`, `update`, note upserts, `cancel` and `reopen` touch a trend, never `start` or `complete`. The summary is the single distilled truth, rewritten on every update. A `schema_violation` on the trend type means trend memory is unavailable: record it in `improvement-signals` and skip the trend writes and Step 7.

## Step 7 - Proposals

**Skip in dry-run.** For each trend created or updated in Step 6 with **Sessions >= 2** (a standing trend only when this run raised a finding and an earlier evidence note recorded one too):

**7a** Find or create the `Improvement Proposals` container (unscoped, as 5a).

### 7b. Create proposal items
Scope is global (plugin skills and hooks, orchestration context, server floor config) or project (one project's schemas, traits, config); unparseable is global. Create the item (`type: "improvement-proposal"`, `tags: ["improvement-proposal"]`, `priority: "low"`, title `Proposal: <concrete change>`) under the 7a container, or for project scope under a per-project `Improvement Proposals` container beneath the project root (found or created as in 5a). It carries a concrete change: schema, trait, rule or run-profile edits with exact YAML or rule text; skill text only for mechanics or generic engineering practice; hooks naming event, matcher and purpose. Fill its `proposal` note straight after the create, or `review-proposals` is gate-blocked:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<proposal-uuid>", "key": "proposal", "role": "queue", "body": "Problem and evidence: <pattern, retrospective ids, session counts>.\nChange: <exact YAML or rule text, or skill file and section, or hook event, matcher and purpose>.\nExpected effect: <measurable effect and how later runs measure it>.\nBlast radius: <schemas, skills or agents touched>.\nScope: <global | project-specific; target rootId when project-specific>."}}
```

### 7c. GitHub issues (global proposals only)
Best-effort per global proposal: a failure becomes a one-line reason and the retrospective continues. The config gate, `gh` guard, dedup, template and record-back are in [`../_shared/github-feedback.md`](../_shared/github-feedback.md).

## Step 8 - Meta-Evaluation

**Skip in dry-run.** List prior retrospectives (`items_read` `list`, `tags: ["session-retrospective"]`, `limit: 20`). With 3 or more, evaluate trend durability, proposal staleness and self-quality as [`references/evaluation.md`](references/evaluation.md) says, and append meta-findings to `improvement-signals`.

### 8a. Verify Adopted Proposals

Every non-dry run, outside the 3-or-more gate. Accepted proposals dwell in `review` with `outcome-verification` unfilled. List them (global container children included), oldest accepted first (`roleChangedAt`), and verify at most 10 per run (a skip does not count):

```to-call
{"tool": "items_read", "select": "list", "args": {"type": "improvement-proposal", "role": "review", "ancestorId": "<rootId>", "limit": 50, "fields": ["roleChangedAt"]}}
```

The adoption date is in the `adoption-decision` note; count the retrospectives created after it (a project proposal: that project's only). **Fewer than 2: skip and write nothing.** Otherwise compare the later retrospectives and the items created after the adoption date with the `proposal` note's expected effect, write the verdict, then complete the proposal, checking each element's result:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<proposal-uuid>", "key": "outcome-verification", "role": "review", "body": "Verdict: <effective|ineffective|inconclusive>. <evidence: later retrospective ids and what they showed; what to try instead when ineffective>."}}
```

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<proposal-uuid>", "trigger": "complete"}}
```

Then write back to the source trend, trend first: `effective` retires it with `cancel` (`summary: "archived: verified effective via proposal <short-id>"`) unless it is already terminal; `ineffective` or `inconclusive` first `reopen`s a terminal trend, then appends `proposal <short-id> <verdict> <YYYY-MM-DD>` to its `summary` and keeps it active.

## Step 8b - Close

**Skip in dry-run.** Re-read the retrospective's own cost and put it in `session-metrics` (re-upsert) as `Retrospective cost: <self.tokens> tokens, <self.wallMs> ms, <self.analysedShare> of the run (partial)`, or `Retrospective cost: unknown (<reason>)`:

```
node "<helper>" retro-metrics --root <rootId> --run <runId> --session-dir "<dir>" --self-only
```

Upsert the work note `actions-taken`: proposals created or updated (title, short id), each global proposal's GitHub outcome (`filed <url>`, `linked existing <url>`, `github filing skipped: <reason>`), each project proposal's anchor, trends created, updated or retired, adopted proposals verified. Then the gate loop:

```to-call
{"tool": "items_read", "select": "context", "args": {"itemId": "<retro-uuid>"}}
```

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<retro-uuid>", "trigger": "complete"}}
```

Then mark the runs (a `--run` scope only), once for all, repeating `--run`:

```
node "${CLAUDE_PLUGIN_ROOT}/hooks/retro-ack.mjs" --run <runId> [--run <runId>]...
```

Report its lines. An unexpanded `${CLAUDE_PLUGIN_ROOT}` skips the step (`retro mark skipped: plugin root unresolved`); `not marked ...` and `mark failed` lines are not errors.

## Step 9 - Report

Header `<YYYY-MM-DD> - <N> items`, a `Dimension | Score | Key Finding` table (one row per dimension), Trends (`Pattern | Sessions | new, reinforced or addressed`) and Proposals Created (`ID | Proposal | Trigger | Issue`); omit a section with no data. A dry run starts with the dry-run line.

## Troubleshooting

No `session-tracking` notes: the items' schema does not require it. A run dispatched again: its `retro` mark is missing (REST-only), so run `retro-ack.mjs --run <runId>` by hand. Another project's items: set `project.rootId`, pass the root UUID or `--run`.
