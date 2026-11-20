---
name: review-proposals
description: "Triages pending improvement-proposal MCP items: presents each with its scope and evidence, collects an accept, reject or defer decision per proposal, and carries out the disposition (project changes applied and pushed per-root, global changes tracked on GitHub, rejections recorded, deferrals left in queue). Use when the user wants to review or act on open improvement proposals."
argument-hint: "[optional: proposal UUID | --scope global|project]"
disable-model-invocation: true
---

# Review Proposals

Triages `improvement-proposal` MCP items created by `/session-retrospective` when a cross-session trend graduates. Presents pending proposals, collects a per-proposal decision, and carries out the disposition, including applying project-scoped config changes and filing or linking GitHub issues for global changes.

Calls are 4.0 tool calls; shapes live in [`../_generated/call-shapes.md`](../_generated/call-shapes.md). Every answer is the response envelope (`ok`, `data` or `error`). A write is `{ops: [...]}` of flat elements (one per `to-call` block here). Results come back as `data.results[i]` in request order with `data.summary {ok, failed}`; read ops of an ops tool (`config` `get`, `validate`, `effective`) answer there too. These calls are non-atomic: if any element fails, even in a one-element call, the answer is `ok: false` with top-level `error.code: partial_failure` (no detail, no fix) beside the full `data`, and the cause is that element's `data.results[i].error` (`code`, `message`, `detail`, `fix`). Any other top-level `error.code` is a call-level rejection (envelope, unknown field, access).

Shared GitHub conventions (issue template, scrub rule, `gh` guard, dedup procedure) are not duplicated here: see [`../_shared/github-feedback.md`](../_shared/github-feedback.md) for the C2/C4/C5 contract referenced in Step 5.

---

## Step 0 - Parse Arguments and Read Config

Parse `$ARGUMENTS`:

- **A UUID**: single-proposal mode. Skip Step 1 and go to Step 2 for that item.
- **`--scope global` or `--scope project`**: run only that scope's discovery query. Otherwise run both.

Read the config file directly (a file read, not an MCP call): the path on the SessionStart context's `Config:` line when there is one, otherwise the workspace `.taskorchestrator/config.yaml`. In personal scope that path is the user-level file. Below, "the config file" is whichever you read here. Read:

- `project.rootId` / `project.name`: enables the project-scoped discovery query and the per-root push in Step 5. In personal scope the root is the anchor-only personal root: skip the project-scoped discovery query (proposals live in the global container), but a project-scoped acceptance still edits and pushes the user-level file with rootId = the personal root. Without a rootId, project-scoped discovery and acceptance are unavailable; proceed global-only.
- `extensions.claude-code.retrospective.github_feedback.enabled`: default `false` when the block or key is absent.
- `extensions.claude-code.retrospective.github_feedback.repo`: default `jpicklyk/task-orchestrator`.

A leftover top-level `retrospective:` block is not read by the plugin and is rejected by a strict push (see `references/accept-project.md`).

---

## Step 1 - Discovery

**Skip in single-proposal mode.** Run both queries in parallel (unless `--scope` narrows to one).

**Global.** Find the "Improvement Proposals" container, then read its children:

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "Improvement Proposals", "kinds": ["item"], "limit": 5}}
```

A search hit carries no depth, parent or type: take the hit titled exactly `Improvement Proposals` and confirm it is a root (`items_read` `get`, no `parentId`); a per-project container of that title sits under a project root and is not the global one.

```to-call
{"tool": "items_read", "select": "overview", "args": {"anchorId": "<container-uuid>"}}
```

**Project-scoped** (only with a known `project.rootId`):

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["improvement-proposal"], "ancestorId": "<rootId>", "limit": 50}}
```

### Partition results

Classify each candidate by role and notes:

- **Pending**: role `queue`, no `adoption-decision` note yet. The main triage set.
- **Deferred**: role `queue`, with an `adoption-decision` note whose body starts with "deferred". List separately; do not re-offer unless the user asks to revisit deferred proposals or the stated revisit condition plausibly holds now.
- **Stalled**: role `work` (accepted and advanced by `start` but never reached `review` or terminal: an interrupted adoption). Flag as **stalled adoption**; offer to resume (fill remaining notes and `complete`) or cancel.
- **Awaiting verification**: role `review` (accepted and applied; `outcome-verification` unfilled). List separately as `` `<short-id>` - awaiting verification (accepted <date from its adoption-decision note>) ``. No decision is offered: `/session-retrospective` Step 8a fills `outcome-verification` once at least 2 later retrospectives exist.
- **Terminal**: skip.

If nothing is pending (no queue-role item without an `adoption-decision`, and no stalled item), report `No pending improvement proposals.`, list the awaiting-verification and deferred proposals as Step 3 does, and stop.

---

## Step 2 - Load Proposal Detail

For each candidate (pending and stalled; cap 15, taking the 15 oldest by creation and noting the overflow), list its notes by key, then `get` the bodies that exist. A list carries no bodies.

```to-call
{"tool": "notes_read", "select": "list", "args": {"itemId": "<uuid>", "keys": ["proposal", "adoption-decision"]}}
```

```to-call
{"tool": "notes_read", "select": "get", "args": {"itemId": "<uuid>", "key": "proposal"}}
```

From the notes extract:

- **Proposal body**: the `proposal` note (or the item's `summary` if the note is absent; an absent note is backfilled before any Accept, see Step 5).
- **Scope clause**: the scope classification the note states (`product`, `global-process` or legacy `global`, all read as global, or project-specific). If it cannot be parsed unambiguously, **treat as global**: never auto-edit a project's config on an ambiguous scope reading.
- **Existing `github-issue:` line**: the last line of the proposal body, `github-issue: <url>`. Carry it forward so the global-accept path reuses it instead of filing a duplicate.

---

## Step 3 - Present Triage Table

One compact table of all pending and stalled candidates; deferred proposals get a short list underneath.

```
| # | ID | Proposal | Scope | Evidence | Age | Issue |
|---|-----|----------|-------|----------|-----|-------|
| 1 | `a1b2c3d4` | Add `session-tracking` maxLength guard | project | 3 sessions, sparse-note trend | 4d | — |
| 2 | `e5f6a7b8` | Nudge cooldown too short for solo dev | global | 2 sessions, friction theme | 1d | #142 |
```

- **Evidence**: a one-line summary of the trend and session count that graduated the proposal.
- **Age**: days since creation. **Issue**: the existing `github-issue:` link, else `—`.

Below it list proposals awaiting verification (no decision offered), deferred proposals as `` `<short-id>` - deferred: <condition> (deferred <date>) `` and stalled ones as `` `<short-id>` - stalled in work: <hint> ``.

---

## Step 4 - Collect Decisions

Print each pending or stalled proposal's change block above the question: the exact YAML or rule it names, or, for a mechanics change, the file and section it targets. Then ask via `AskUserQuestion`, batched: up to 4 proposals per call, one question each, the header the proposal's short id, options `Accept`, `Reject`, `Defer` and `Skip`. Put the recommended option first and label it `(Recommended)`:

- **Accept** when the note carries an exact change that can be applied as written.
- **Defer** when the change is prose-only, or the evidence is one trend at `Sessions: 2`.
- **Reject** when an earlier proposal with the same change was rejected.

For Reject or Defer collect the reasons in one follow-up batched call (up to 4 questions) offering a drafted reason plus Other, so the rationale is recorded verbatim in the `adoption-decision` note. `Skip` leaves the item untouched: no note, no transition.

---

## Step 5 - Carry Out Dispositions

### Before any Accept - ensure the `proposal` note

The floor schema requires the `proposal` note in the queue phase, and `start` is gate-blocked without it. A proposal created before the retrospective filled it has none, so every Accept path (project, global, maintainer or community) first checks Step 2's list. When the `proposal` note is absent, upsert it from the item's `summary`, the Step 2 scope classification and a backfill marker, **before any config edit, push or `start`**:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<uuid>", "key": "proposal", "role": "queue", "body": "Problem and evidence: <from the item summary>.\nChange: <the exact change the summary names>.\nExpected effect: <as stated in the summary, or not stated>.\nBlast radius: <as stated, or not stated>.\nScope: <product | global-process | project-specific; target rootId>.\nbackfilled by review-proposals <YYYY-MM-DD>"}}
```

Check `data.results[0].ok`. Never write a note that is only a URL.

### Accept - project-scoped

Follow [`references/accept-project.md`](references/accept-project.md): extract or draft the exact change, apply it, validate and push per-root, record `adoption-decision`, advance `start` twice, stop in `review`, then the Source-trend write-back below.

### Accept - global

Follow [`references/accept-global.md`](references/accept-global.md): ensure a GitHub issue, then the maintainer path (dwell in `review`) or the community handoff (terminal), then the Source-trend write-back below.

### Reject

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<uuid>", "key": "adoption-decision", "role": "work", "body": "rejected - <reason>. Recorded so future retrospectives do not re-propose."}}
```

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<uuid>", "trigger": "cancel", "summary": "rejected - <reason>"}}
```

Read `ok` and `data.results[0].newRole`; on a failure `data.results[0].error.code`. `cancel` is gate-free and goes straight to terminal from any non-terminal role. Do not fill `outcome-verification` for a rejection. Then apply the **Source-trend write-back**.

### Defer

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<uuid>", "key": "adoption-decision", "role": "work", "body": "deferred - revisit when <condition>. Deferred on <YYYY-MM-DD>."}}
```

Check `ok` and `data.results[0].ok`. No transition: the item stays in `queue`. A later Accept overwrites the same note by upsert, so accepting a previously deferred proposal needs no special-casing.

### Source-trend write-back

Most proposals graduated from a trend item (tag `retrospective-trend`) whose summary carries `GRADUATED -> proposal <short-id>`. After a disposition, close the loop at the source using only gate-free operations (`items_write` `update`, `advance` `cancel`), never `start` or `complete` on a trend item. Locate it:

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "<proposal-short-id>", "kinds": ["item"], "tags": ["retrospective-trend"]}}
```

Skip silently when no trend matches: a proposal can be created ad hoc.

- **After Reject:** `get` the trend for its full summary, then `items_write` `update {id, summary}` with one line appended: `proposal <short-id> rejected <YYYY-MM-DD> - do not re-graduate; see its adoption-decision note.` The trend stays active, and every future retrospective sees the rejection inline in its Step 4 listing.
- **After Accept, once the change is applied or landed** (config pushed, or a community handoff filed and the item taken terminal): retire the trend with `advance` `transition`, `trigger: "cancel"`, `summary: "archived: addressed via proposal <short-id>"`. While adoption is only tracked (maintainer path dwelling in `review`), leave the trend active: the retrospective that fills `outcome-verification` retires it once the change held.
- **After Defer:** no write-back.

---

## Step 6 - Report

```
## Proposal Review — <YYYY-MM-DD>

| ID | Proposal | Disposition | Action |
|----|----------|-------------|--------|
| `a1b2c3d4` | Add maxLength guard | Accepted | pushed per-root config (work_item_schemas), dwelling in review |
| `e5f6a7b8` | Nudge cooldown | Accepted (global) | filed https://github.com/jpicklyk/task-orchestrator/issues/143, terminal |
| `c9d0e1f2` | Rename note key | Rejected | cancelled - duplicate of existing key |
| `f3a4b5c6` | Widen skill pointer | Deferred | revisit when trait usage grows |
```

Close with a reminder: accepted items dwelling in `review` are not stuck. Their `outcome-verification` note is filled by `/session-retrospective` (Step 8a) once at least 2 retrospectives have run since the adoption and the change has had time to show effect.
