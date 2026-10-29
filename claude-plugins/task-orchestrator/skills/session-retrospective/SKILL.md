---
name: session-retrospective
description: "Analyzes the current implementation run - evaluates schema effectiveness, delegation alignment, note quality, and plan-to-execution fit. Captures cross-session trends and proposes improvements when patterns repeat. Use after implementation runs, or when user says 'retrospective', 'session review', 'what did we learn', 'analyze this run', 'how did that go', 'evaluate our process', 'wrap up', 'end of session review'. Also use after a run closes, when the retrospective nudge fires."
argument-hint: "[optional: root item UUID] [--run <runId>]... (repeatable) [--dry-run to preview without creating items] [--deep: workflow-backed matching, main session only]"
---

# Session Retrospective

Structured post-implementation analysis. Evaluates the run across five dimensions, persists findings in MCP, and keeps cross-session trend memory to surface improvement proposals.

Calls are 4.0 tool calls; shapes live in [`../_generated/call-shapes.md`](../_generated/call-shapes.md). Every answer is the response envelope (`ok`, `data` or `error`). A write is `{ops: [...]}` of flat elements (one per `to-call` block here). Results come back as `data.results[i]` in request order with `data.summary {ok, failed}`; read ops of an ops tool (`config` `get`, `validate`, `effective`) answer there too. These calls are non-atomic: if any element fails, even in a one-element call, the answer is `ok: false` with top-level `error.code: partial_failure` (no detail, no fix) beside the full `data`, and the cause is that element's `data.results[i].error` (`code`, `message`, `detail`, `fix`). Any other top-level `error.code` is a call-level rejection (envelope, unknown field, access).

---

## How the trigger works

A retrospective is owed per **closed** run that is not marked `retro`. Two hooks watch for it:

- `retro-trigger` (PostToolUse on `runs_write`) acts only on a successful `op: close`.
- `retro-backstop` (Stop) re-checks at the end of every turn, so a run closed elsewhere (another session, a subagent) is still found. A closed run is a confirmed boundary, so the backstop may dispatch as well as nudge.

Both ask REST `runs_read` `list` with `unmarked: retro` and `endedSince = now - 24h`, and read each run's terminal count from `runs_read` `frontier` (`data.counts.terminal`), for at most 5 runs per pass. In mode `dispatch` with a terminal count at or above `dispatchThreshold` (default 3) they call `runs_write` `mark {runId, key: retro}` (REST only; first write wins, which is the cross-session dedupe; `marked: false` means another session won, so they stay silent) and emit the dispatch directive. Below the threshold, in `nudge` mode (the default), or when the mark fails, they nudge once per run per session and never mark. There is no cross-session marker file (the server mark is the dedupe); per-session state (recorded closes, runs already nudged) lives in `os.tmpdir()/task-orchestrator/<session>/retro.json`. A run whose frontier read fails is skipped that pass, and a close made by a subagent is recorded for the main session's backstop, not acted on. Without an API URL or a project rootId the backstop only nudges once per session, from the closes the trigger recorded.

`retro-ack.mjs` is the manual acknowledgement: `node retro-ack.mjs --run <id> [--run <id>]...` sends the same `runs_write` `mark {runId, key: retro}` over REST for each run (Step 8b calls it). It prints one line per id (`marked <id>`, `already marked <id>`, `mark failed <id>: <code>`, or `not marked <id>: no REST API configured`) and always exits 0.

**Director sessions.** A director never runs a retrospective in its own context, so for it both hooks always dispatch (mode `dispatch`, threshold 0, whatever the config says; `off` stays off): every closed unmarked run it sees is marked first and then dispatched, so a later session does not dispatch it again. Without REST, `director-watch` dispatches once per run per session and only for a run closed within the last 24 h. The dispatch is ONE background agent that invokes this skill with a repeated `--run`.

The settings (`mode`: `nudge` | `dispatch` | `off`, and `dispatchThreshold`) are read ONLY from `extensions.claude-code.retrospective`; a top-level `retrospective:` block is ignored and `cooldownMinutes` is gone. Headless iterations, `to_mod_retro` and `mode: off` make both hooks inert.

**Honest limit.** The mark is REST-only. Without a resolvable REST API URL (Step 8b prints `not marked ...: no REST API configured`), a manually run retrospective leaves its run unmarked, so it can nudge or dispatch again in a later session inside the 24 h window; Step 5b's `run:<runId>` search keeps that repeat from creating a second retrospective item.

---

## Step 0 - Mode Check

- `--dry-run` sets **DRY_RUN = true**: perform steps 1-4 and render the report (step 9); skip steps 5-8 (no item creation, no memory updates). Announce: `**Dry run** - no items created, no memory updated.`
- `--run <runId>` scopes the analysis to a run (step 1a). It repeats (`--run A --run B`): the scope is the union of the runs' members, and one retrospective covers them all. It wins over a root UUID.
- `--deep` is handled in 4.3.

---

## Step 1 - Gather Scope

Collect the distributed `session-tracking` notes of the items in scope.

### 1a. Identify items in scope
**`--run <runId>`** (once per run when repeated; union the members). The run view gives `rootId` and `scopeId`:

```to-call
{"tool": "runs_read", "select": "get", "args": {"runId": "<runId>"}}
```

Members are the distinct `itemId`s of the run's seat assignments, ended ones included (coordinator rows carry no `itemId`), plus the ids in the frontier's `waiting` and `inFlight` lists. A closed run's frontier lists no terminal members, so the seats read is the source for finished work. Seat `itemId`s are full UUIDs and frontier ids are 8-hex short ids: dedupe on the first 8 hex characters.

```to-call
{"tool": "runs_read", "select": "seats", "args": {"runId": "<runId>", "ended": true}}
```

```to-call
{"tool": "runs_read", "select": "frontier", "args": {"runId": "<runId>"}}
```

When that member set is empty and the run has a `scopeId`, fall back to the scope root:

```to-call
{"tool": "items_read", "select": "overview", "args": {"itemId": "<scopeId>"}}
```

**A root UUID** is the authoritative scope (also the dispatched background-agent case). List its subtree; run no fallback scan:

```to-call
{"tool": "items_read", "select": "list", "args": {"ancestorId": "<root-uuid>", "limit": 200}}
```

**Neither argument.** Check for a project root: the `## Project Scope` section of the SessionStart context, or `project.rootId` in the project's `.taskorchestrator/config.yaml`. A personal root is not a project root and takes the unscoped branch. With a rootId, scope both fallback calls to that subtree so other projects sharing the DB are not conflated; without one, omit `ancestorId`.

```to-call
{"tool": "items_read", "select": "context", "args": {"ancestorId": "<rootId>"}}
```

```to-call
{"tool": "items_read", "select": "list", "args": {"role": "terminal", "sort": "modifiedAt", "order": "desc", "limit": 20, "modifiedAfter": "<ISO now minus 24h>", "ancestorId": "<rootId>"}}
```

The 24-hour window is the `modifiedAfter` filter: list results carry no timestamps unless asked for, so staleness cannot be judged afterwards.

### 1b. Collect distributed notes
For each item in scope (up to 20), list its notes by key. A list carries no bodies, so `get` each note that exists.

```to-call
{"tool": "notes_read", "select": "list", "args": {"itemId": "<uuid>", "keys": ["session-tracking", "delegation-metadata"]}}
```

```to-call
{"tool": "notes_read", "select": "get", "args": {"itemId": "<uuid>", "key": "session-tracking"}}
```

`session-tracking` holds per-item outcome, files changed, deviations, friction, observations and test results; `delegation-metadata` (optional) holds the orchestrator-recorded model and isolation (structured provenance line or legacy prose).

### 1c. Early exit
If no items are in scope, or no item has a `session-tracking` note, exit with: `No implementation run data found. Nothing to retrospect - run /implement first, then try again.`

---

## Step 2 - Aggregate Note Data

Across the collected `session-tracking` notes: total item count and outcome distribution (success, partial, failed, skipped); combined deduplicated files list; combined friction list; combined observations; test result totals. Run the health read of 1a (`context`) for the current state snapshot when not already done.

From `delegation-metadata` notes extract model per delegation (haiku, sonnet, opus) and isolation (inline, worktree); they feed 3b.

**Structured form.** A note whose first line matches `^adapter=\S+( [a-z-]+=\S+)+$` is a structured provenance line, not legacy prose: split on spaces, then each token on `key=value`. Each `seat:model` pair inside `seats=` is one delegation, typed by seat rather than guessed from prose - planner: architecture/opus; implementer, test-author: sonnet; declarations-extractor: code reading/sonnet; reviewer: opus; any other seat is recorded but not scored. Isolation comes from `isolation=`. `model=` is ignored whenever `seats=` is present. Field order is fixed: `adapter= run= seats= model= isolation= agents= tokens= duration= deferred= in-run-edges= orchestrator-turns=`. Example:

```
adapter=claude-workflow run=r-20260928-b2d seats=planner:opus,implementer:sonnet,declarations-extractor:sonnet,test-author:sonnet,reviewer:opus model=sonnet isolation=worktree:.claude/worktrees/feat-b2-front-door agents=5 tokens=412800 duration=1860000 deferred=0 in-run-edges=1 orchestrator-turns=3
```

`tokens=` / `duration=` may read `see:<short>`: the run total lives on that item, so count it once and never sum it per item (in the per-run table show the run's first-item value, never a `see:` reference). An optional second line `extra-seats=<seat>:<model>:<tokens>,...` lists seats dispatched outside the run; score each like a `seats=` pair, typing the seat after stripping a trailing fix-cycle suffix `-a<n>` (`reviewer-a1` scores as reviewer). **Legacy form:** free-form prose naming model and isolation, parsed as before; a run mixing both forms scores both.

---

## Step 3 - Evaluate Across Dimensions

### 3a. Schema Effectiveness
For each item, examine its notes (1b): which schema-required notes exist with non-empty content; size per note (**<50 tokens sparse**, **50-500 appropriate**, **>500 potentially verbose** for status-type notes, specification notes exempt); items missing required notes. **Score:** fraction of expected notes that exist with appropriately sized content.

### 3b. Delegation Alignment
If `delegation-metadata` notes exist, cross-reference the expected model: `haiku` for MCP bulk ops, materialization and simple queries; `sonnet` for code reading, implementation and test writing; `opus` for architecture, complex tradeoffs and multi-file synthesis.

- Flag misalignments (opus for bulk ops, haiku for architecture), scored per seat delegation for a structured note (one score per `seat:model` pair).
- A `substituted=` field is an allowlist substitution, not a misalignment. A `model-source=self-report` field is noted separately and not folded into the misalignment count.
- **Score:** fraction of delegations matching the expected model. With no `delegation-metadata`, note "delegation metadata not recorded" and skip scoring.

With at least one structured line, show a per-run table before 3c: `| adapter | run | agents | tokens | duration | orchestrator-turns |`.

### 3c. Note Effectiveness
For items with both queue-phase notes (specs) and work-phase notes: compare themes; "deviated from spec", "unexpected" or "assumption was wrong" flags a spec gap; work-phase notes under ~30 tokens flag context loss for downstream agents. **Score:** effective / mixed / ineffective.

### 3d. Plan-to-Execution Alignment
Creation times come from one `items_read` `list` with `fields: ["createdAt", "modifiedAt", "summary"]`, scoped by `ancestorId` (the root, or the run's `scopeId`); with neither, `get` each in-scope item with `fields: ["createdAt"]` (at most 20).

- Items created more than 1 hour after the root (or the earliest item) are ad-hoc additions (necessary, or scope creep); items still in queue under the root were planned but skipped. **Score:** fraction of planned items that reached terminal.

### 3e. Friction Synthesis
Group friction entries by type: `tool-error`, `excessive-roundtrips`, `workaround`, `api-confusion`. Name themes across entries ("3 entries about gate failures on items without schemas").

---

## Step 4 - Check Trend Memory

Trend memory is items under a `Retrospective Trends` container, not a file. Reads are targeted queries.

### 4.1 Discover the Trends container
The three containers (Trends, Session Retrospectives, Improvement Proposals) are process-global by design, the shared cross-project learning layer: they are root items (no `parentId`) whose types resolve from the server config (process-global types), and every search for them stays unscoped even when a project rootId is known.

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "Retrospective Trends", "kinds": ["item"], "limit": 5}}
```

Cross-check with a list (`{"type": "container", "depth": 0, "limit": 100}`, filter by title) when the hit is ambiguous or empty.

If the container is absent or empty AND `memory/retrospectives.md` has at least one `- <kebab-key>: ...` entry line, run [`references/legacy-trend-migration.md`](references/legacy-trend-migration.md) FIRST. If both are absent, this is the first retrospective on this database: skip the rest of Step 4, all findings are new baselines.

### 4.2 Read the active trends and recent retrospectives
The listing carries each trend's `summary` (with `Sessions: N`) and timestamps, so no per-trend `get` is needed. The kebab key in the title is the stable identity.

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["retrospective-trend"], "role": "queue", "limit": 100, "fields": ["summary", "createdAt", "modifiedAt"]}}
```

Recent retrospectives (the `--deep` input; the check in Step 8 reads its own page):

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["session-retrospective"], "sort": "createdAt", "order": "desc", "limit": 10}}
```

### 4.3 Match findings
Match each Step 3 finding against the listing by title and summary. A finding that matches an existing trend (same schema note, delegation pattern or friction type) increments that trend's `Sessions: N` in Step 6 (N from the listing summary); a new one is a candidate for a new trend. For an uncertain match run a per-finding search:

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "<finding keywords>", "kinds": ["item"], "tags": ["retrospective-trend"]}}
```

#### Deep mode (`--deep`, opt-in)

Only when `$ARGUMENTS` contains `--deep`, the user invoked this skill in the main session (never a hook-dispatched or background run), and the Workflow tool is callable; otherwise ignore the flag and match inline. The workflow replaces the inline matching, is read-only, and Step 6 still does every write.

1. Build `retro-analysis/args-v1`: `contract`, `runId: "ra-<YYYYMMDD>-<HHMM>"`, `date`, `mode: "deep"`, `rootId` (when a project root is known), `findings` (each Step 3 finding as `{fid: "f<n>", dimension, text, keywords}`; `dimension` maps 3a `schema-effectiveness`, 3b `delegation`, 3c `note-quality`, 3d `plan-to-execution`, 3e `friction`), `trends` (the 4.2 listing as `{id, short, title}`), `observations` (one unscoped `items_read` `list` with `tags: ["agent-observation"]`, `limit: 100`, terminal ones dropped), `retros` (the second 4.2 block). Only ids and titles go in; the agents read summaries and notes themselves.
2. Unless dry-run, and only with a known `rootId`, stash `{findings, retroScope}` (retroScope = the Step 1 root and item ids) as a plan document and add `planDocSlug: "retro/<runId>"` to the args:

```to-call
{"tool": "docs_write", "op": "upsert", "args": {"rootId": "<rootId>", "kind": "plan", "slug": "retro/<runId>", "body": "<JSON of findings and retroScope>"}}
```

3. Launch `Workflow({name: "task-orchestrator:retro-analysis", args})` with args as a real object, then end the turn.
4. On the task notification: if `started: false`, report its `reason` and match inline. Otherwise the result echoes `findings` (after compaction re-read the stash with `docs_read` `get {rootId, kind: "plan", slug: "retro/<runId>"}`); resume at Step 5:
   - `matched` entries are 4.3 recurrences: Step 6 increments `Sessions: N` from `sessionsBefore`, or from the listing summary when it is null;
   - `stats.unmatchedShards` (`[{label, kind, targetIds}]`) are shards whose Match agent returned nothing: match the findings against those `targetIds` inline per 4.3 before calling any finding new, and report `stats.missing`;
   - `newTrends` and `unresolved` fids are new-pattern candidates; `staleTrends` are retire candidates; `observationLinks` feed the report.
5. Skip 4.4 for matched trends whose `evidence` the result already carries.

### 4.4 Fetch full evidence (only when it matters)
When per-session history changes the assessment (worsening, stabilizing, flagged as environmental), list the trend's notes and `get` the evidence bodies:

```to-call
{"tool": "notes_read", "select": "list", "args": {"itemId": "<trend-uuid>"}}
```

---

## Step 5 - Persist the Retrospective

**Skip entirely in dry-run mode.**

### 5a. Find or create the container
```to-call
{"tool": "items_read", "select": "search", "args": {"query": "Session Retrospectives", "kinds": ["item"], "limit": 5}}
```

If no root item (no `parentId`) has that exact title, create it; a search hit carries no depth, parent or type, so confirm with the 4.1 list cross-check or `items_read` `get` on the hit. An undeclared `type` fails the element with `data.results[0].error.code: schema_violation`, and `data.results[0].error.detail.availableTypes` lists the declared types: use the declared container type.

```to-call
{"tool": "items_write", "op": "create", "args": {"title": "Session Retrospectives", "summary": "Container for structured post-implementation analyses.", "type": "container", "tags": ["container"], "priority": "low"}}
```

Check `ok` and `data.results[0].ok`; the new `id` is `data.results[0].id`.

### 5b. Create the retrospective item
**First, drop runs already retrospected.** For each `--run <runId>` search for an existing retrospective that carries it (the title below holds `run:<runId>`):

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "run:<runId>", "kinds": ["item"], "tags": ["session-retrospective"], "matchMode": "substring", "limit": 5}}
```

Drop every run that has a hit. If no run remains, create nothing: say `already retrospected <runId>` for each, run Step 8b's `retro-ack.mjs` call for them (the mark may still be missing) and stop.

Include the project name (or rootId) in the title when known, so a shared container stays attributable; otherwise omit that segment. When the scope came from `--run`, end the title with `run:<runId>` once per remaining run (space separated).

```to-call
{"tool": "items_write", "op": "create", "args": {"title": "Retrospective - <project-name> - <root-item-title> - <YYYY-MM-DD> run:<runId>", "summary": "<one-sentence summary of key findings>", "type": "session-retrospective", "tags": ["session-retrospective"], "parentId": "<container-uuid>"}}
```

Check `ok` and `data.results[0].ok`; on a failure read `data.results[0].error.code`. (Use `[i]` for multi-element calls.)

### 5c. Fill the queue-phase notes
The `session-retrospective` schema has four required notes: queue-phase `session-metrics`, `workflow-evaluation`, `improvement-signals`, and work-phase `actions-taken` (filled at 8b). Fill the three queue notes now as one `notes_write` call with three `upsert` elements (one shown):

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<retro-uuid>", "key": "session-metrics", "role": "queue", "body": "<Step 3 quantitative data: item counts, outcomes, schema usage, token estimates, files changed>"}}
```

`workflow-evaluation` holds the Step 3 per-dimension scores and findings; `improvement-signals` the Step 4 trend analysis (new, reinforced, proposals, and the scope classification of Step 7b). Check `data.results[i].ok` for all three.

### 5d. Advance to work
```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<retro-uuid>", "trigger": "start"}}
```

Read `ok` and `data.results[0].newRole`. Do not complete yet: `actions-taken` depends on Step 7. Completion is at 8b.

---

## Step 6 - Update Trend Memory

**Skip entirely in dry-run mode.** Write trend items from the findings, batched: one `notes_write` call for this run's evidence notes, one `items_write` call for its creates and summary updates.

- **Recurrence:** upsert an evidence note `evidence-<YYYY-MM-DD>-<retro-short-id>` (role `work`; body = what happened, the retro item id, cost or impact) AND update the trend's `summary`: increment `Sessions: N` (from the 4.2 listing), set `Last seen: YYYY-MM-DD`, condense if drift warrants.
- **New pattern:** create a trend item (below) with `Sessions: 1`, plus its first evidence note.
- **Retire** (addressed, obsolete, superseded or accepted-environmental): `advance` `transition` with `trigger: "cancel"` and `summary: "archived: <reason>"`. `cancel` is gate-free; `statusLabel: cancelled` here means "retired from active watching". A cancelled trend leaves the 4.2 listing, which filters `role: "queue"`.
- **Graduation** (Step 7 creates a proposal from this trend): record `GRADUATED -> proposal <short-id>` in the trend's `summary`; the role does not change.

**Only `create`, `update`, note upserts and `cancel` touch a trend item**, never `start` or `complete`. That keeps the lifecycle gate-free under any schema config.

```to-call
{"tool": "items_write", "op": "update", "args": {"id": "<trend-uuid>", "summary": "<distilled observation>. Sessions: <N+1>. Last seen: <YYYY-MM-DD>."}}
```

Check `ok` and `data.results[0].ok`; on a failure read `data.results[0].error.code`. (Use `[i]` for multi-element calls.)

### New trend item shape
```to-call
{"tool": "items_write", "op": "create", "args": {"title": "trend: <kebab-key> - <one-line claim>", "summary": "<distilled observation>. Sessions: 1. Last seen: <YYYY-MM-DD>.", "type": "retrospective-trend", "tags": ["retrospective-trend", "<dimension>"], "parentId": "<trends-container-uuid>", "priority": "low"}}
```

`<dimension>` is one of `schema-effectiveness | delegation | note-quality | plan-to-execution | friction | extension-candidate`; add `positive` as a further tag for a positive pattern. The kebab key in the title is the stable identity to match on. Batch up to ~10 creates per call; read `data.results[i].ok` per element; a failed one carries its cause in `data.results[i].error` (the top-level `partial_failure` names no element).

**Trend memory unavailable.** The `retrospective-trend` type comes from the server floor config. If a create's `data.results[i].error.code` is `schema_violation` for that type, record "trend memory unavailable" in `improvement-signals`, skip the trend writes of this step and the graduation checks of Step 7, and continue: the retrospective itself never fails on it.

Then the first evidence note:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<new-trend-uuid>", "key": "evidence-<YYYY-MM-DD>-<retro-short-id>", "role": "work", "body": "<this session's specific evidence: what happened, retro item id, cost or impact>"}}
```

Check `ok` and `data.results[0].ok`.

**Condense, don't accumulate.** The trend `summary` is the single distilled current truth: rewrite it on every update; per-session detail lives in the evidence notes (one per recurrence), and `Sessions: N` is updated in the same call that adds the note. Proposal status, priority and dates live on the proposal item, never mirrored into a trend beyond the one-line `GRADUATED -> proposal <short-id>` pointer.

**Audit sweep.** The `retro-analysis` workflow's audit mode (a standalone sweep, no findings) returns the same `newTrends`, `staleTrends` and `observationLinks` shapes. Present that result to the user first; only after they confirm, apply this step's create and retire shapes to it.

---

## Step 7 - Create Improvement Proposals

**Skip entirely in dry-run mode.** For each trend created or updated in Step 6 with **Sessions >= 2**:

### 7a. Find or create the proposals container
Process-global like 5a: search `Improvement Proposals` unscoped (`items_read` `search`, `kinds: ["item"]`, as in 5a) and create it if missing (`type: "container"`, `tags: ["container"]`).

### 7b. Create proposal items - scope-based anchoring
Read the **scope** classification captured in `improvement-signals`: **global** (plugin skills and hooks, the orchestration context, server floor config) or **project-specific** (one project's schemas, traits, config). If it cannot be parsed, treat it as **global**: never auto-anchor under a project on ambiguous evidence.

**Global** - anchor under the 7a container:

```to-call
{"tool": "items_write", "op": "create", "args": {"title": "Proposal: <concrete change description>", "summary": "<what to change and why - reference the retrospective item ids that surfaced the trend>", "type": "improvement-proposal", "tags": ["improvement-proposal"], "parentId": "<7a-proposals-container-uuid>", "priority": "low"}}
```

**Project-specific** - find or create a per-project container under the project's rootId (the 5a search with `ancestorId: "<rootId>"`; if none, create `{"title": "Improvement Proposals", "type": "container", "tags": ["container"], "parentId": "<rootId>", "priority": "low"}` as in 5a), then create the proposal with that `parentId`. Check `ok` and `data.results[0].ok`. The `improvement-proposal` tag stays on the item so 3.x proposals and 4.0 ones are found by the same tag query.

The proposal must carry a **concrete suggestion**: schema edits with the exact YAML; skill updates naming the section; orchestration-context changes naming section and content; hook additions naming event, matcher and purpose.

**Fill the `proposal` note right after each create** (the floor schema requires it in the queue phase; without it `/review-proposals` is gate-blocked at `start`). One upsert per proposal, the new proposal's id as `itemId`:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<proposal-uuid>", "key": "proposal", "role": "queue", "body": "Problem and evidence: <the pattern, with the retrospective item ids and session counts that surfaced it>.\nChange: <the exact YAML, the skill file and section, or the hook event, matcher and purpose>.\nExpected effect: <the measurable effect and how later sessions will measure it>.\nBlast radius: <schemas, skills or agents touched>.\nScope: <global | project-specific; target rootId when project-specific>."}}
```

Check `data.results[0].ok`. A global proposal later gets a final `github-issue: <url>` line from 7c.

### 7c. File GitHub issues (global proposals only)
For each **global** proposal created this run, never project-scoped ones, attempt to file or link a GitHub issue. Conventions, template and dedup live in [`references/github-feedback.md`](references/github-feedback.md); this step is the sequence. Every sub-step is best-effort: a failure is caught, recorded as a one-line reason, and the retrospective continues.

1. **Config gate:** read `extensions.claude-code.retrospective.github_feedback` from `.taskorchestrator/config.yaml` (a file read). Not `enabled: true` skips all of 7c; record `github filing: disabled`.
2. **gh guard:** `gh auth status` via Bash; a non-zero exit skips filing and records the reason.
3. **Dedup (cheapest first):** sibling proposals' `github-issue:` lines (cap 10 note reads), then `gh issue list --repo <repo> --state all --search "<3-5 keywords>" --json number,title,url --limit 10`. Reuse a match.
4. **File** only if nothing matched: `gh issue create --repo <repo> --title "[proposal] <...>" --body-file <scratchpad-tmp-path> --label enhancement` (retry once without `--label` if the label is missing).
5. **Record back:** re-upsert the proposal note with a final `github-issue: <url>` line (references C2).

---

## Step 8 - Meta-Evaluation

**Skip entirely in dry-run mode.** Query prior retrospectives:

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["session-retrospective"], "limit": 20}}
```

With 3 or more, evaluate. Read proposal state from the container (anchored overview; `includeChildren` is not allowed with `anchorId`, and each child entry already carries its `role`):

```to-call
{"tool": "items_read", "select": "overview", "args": {"anchorId": "<proposals-container-uuid>"}}
```

With a project rootId also pull project-scoped proposals, which anchor outside the global container:

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["improvement-proposal"], "ancestorId": "<rootId>", "limit": 50}}
```

1. **Trend durability:** find trends whose summary carries `GRADUATED -> proposal <id>` (`items_read` `search` with `query: "GRADUATED"`, `kinds: ["item"]`, `tags: ["retrospective-trend"]`) and check whether each proposal is terminal.
2. **Proposal staleness:** proposals created 3+ retrospectives ago and still in queue, or stuck in `work` (usually a stalled adoption). When stale queue proposals exist, the report suggests `/task-orchestrator:review-proposals`. A `cancelled` proposal is **resolved-rejected**, not stale: read its `adoption-decision` note before proposing anything similar again.
3. **Self-quality:** are notes converging on useful patterns or repeating observations; too verbose (>800 tokens) or too shallow (<100)?

If meta-findings warrant it, re-upsert the current retrospective's `improvement-signals` note with the meta-evaluation appended (same `notes_write` `upsert` shape as 5c).

### 8a. Verify Adopted Proposals

Accepted proposals dwell in `review` with `outcome-verification` unfilled; this retrospective fills it. List them (global ones: the container's children from the overview above with role `review`; project ones: this list with the project rootId):

```to-call
{"tool": "items_read", "select": "list", "args": {"type": "improvement-proposal", "role": "review", "ancestorId": "<rootId>", "limit": 50}}
```

For each one, take the date in its `adoption-decision` note as the adoption date and count the retrospectives created after it (the Step 8 list above; for a project-scoped proposal count only that project's). **With fewer than 2 later retrospectives, skip it and write nothing**: the change has not had time to show an effect. Otherwise compare the later retrospectives and their items (only items created after the adoption date: schema pins mean older items still follow the old config) with the `proposal` note's expected effect, and upsert the verdict, then complete the proposal:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<proposal-uuid>", "key": "outcome-verification", "role": "review", "body": "Verdict: <effective|ineffective|inconclusive>. <evidence: later retrospective ids and what they showed against the expected effect; what to try instead when ineffective>."}}
```

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<proposal-uuid>", "trigger": "complete"}}
```

Then the source-trend write-back: `effective` retires the trend (`advance` `transition`, `trigger: "cancel"`, `summary: "archived: verified effective via proposal <short-id>"`); `ineffective` or `inconclusive` appends `proposal <short-id> <verdict> <YYYY-MM-DD>` to the trend's `summary` (`items_write` `update`) and keeps the trend active. A proposal delegated upstream is already terminal and is not listed.

---

## Step 8b - Fill the Closure Note and Complete

**Skip entirely in dry-run mode.** Fill the work-phase `actions-taken` note (the closure record), then complete.

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<retro-uuid>", "key": "actions-taken", "role": "work", "body": "<proposals created or updated in Step 7 (title and short id each); per global proposal its GitHub outcome: filed <url>, linked existing <url>, or github filing skipped: <reason>; per project proposal: anchored under project <rootId>; or none graduated this run; plus trend items created, updated or retired (short ids); plus a legacy migration count if one ran>"}}
```

Check `ok` and `data.results[0].ok`, then complete:

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<retro-uuid>", "trigger": "complete"}}
```

Read `ok` and `data.results[0].newRole`; on a failure `data.results[0].error.code` (`gate_blocked`, whose `error.detail.missing` names the unfilled notes).

Then mark the runs (a `--run` scope only; skip with no `--run`). Run it once for all of them through Bash, repeating `--run`:

```
node "${CLAUDE_PLUGIN_ROOT}/hooks/retro-ack.mjs" --run <runId> [--run <runId>]...
```

Report its lines. If `${CLAUDE_PLUGIN_ROOT}` reaches the shell unexpanded (the literal survives in the command), skip the step and record `retro mark skipped: plugin root unresolved` in the report. A `not marked ...: no REST API configured` or `mark failed` line is not an error: say so in the report and continue.

---

## Step 9 - Report

Render a dashboard (symbols: `✓` terminal, `◉` work or review, `⊘` blocked, `○` queue, `—` cancelled):

```
## Session Retrospective — <root-item-title or run label>
**<YYYY-MM-DD> · <N> items · <N> schemas used**

### Dimension Scores
| Dimension | Score | Key Finding |
|-----------|-------|-------------|
| Schema effectiveness / Delegation alignment / Note effectiveness / Plan-to-execution / Friction | <fraction, qualitative, or "not recorded"> | <one line> |

### Trends                       | Pattern | Sessions | Status (new / reinforced / addressed) |
### Improvement Proposals Created | ID | Proposal | Trigger (graduated trend) | Issue (url or —) |
```

One dimension row each in the real table. Dry-run prefix: `**Dry run** - no items created, no memory updated.` Omit sections with no data. With `delegation-metadata` notes present, put a delegations count in the header line: sum of seats (structured) + extra-seats entries + notes (legacy).

---

## Troubleshooting

**No session-tracking notes found.** Implementing agents did not fill them, usually because the items have no matching schema. Check that the type's schema (or a default trait) requires `session-tracking`.

**Container not found.** First retrospective on this database. Containers are created lazily: `Session Retrospectives` at 5a, `Retrospective Trends` at 4.1 or the first trend write, `Improvement Proposals` at 7a.

**Legacy trend file detected.** `memory/retrospectives.md` has `- <key>: ...` lines and the Trends container is absent or empty: see [`references/legacy-trend-migration.md`](references/legacy-trend-migration.md). A count mismatch stops it without stubbing the file; re-run to retry.

**Run keeps being dispatched.** Its `retro` mark is missing: the mark is REST-only, so check that a REST API URL resolves (Step 8b prints it) and run `node "${CLAUDE_PLUGIN_ROOT}/hooks/retro-ack.mjs" --run <runId>` by hand.

**Retrospective pulled in another project's items.** No project scope was configured, so the fallback scan covered the whole database. Set `project.rootId` in `.taskorchestrator/config.yaml`, pass the root UUID, or pass `--run <runId>`.
