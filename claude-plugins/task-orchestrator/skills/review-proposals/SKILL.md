---
name: review-proposals
description: "Triages pending improvement-proposal MCP items - presents each with its scope and evidence, collects an accept/reject/defer decision per proposal, and carries out the disposition: project-scoped acceptances get their exact YAML applied to .taskorchestrator/config.yaml and pushed per-root; global acceptances get a tracked GitHub issue (or dwell in review for the maintainer); rejections are recorded and cancelled; deferrals are recorded and left in queue. Use when a user says: review proposals, triage proposals, pending proposals, what proposals are open, adopt proposals, process improvement proposals, accept or reject proposals."
argument-hint: "[optional: proposal UUID | --scope global|project]"
---

# Review Proposals

Triages `improvement-proposal` MCP items created by `/session-retrospective` when a cross-session trend graduates. Presents pending proposals, collects a per-proposal decision, and carries out the disposition, including applying project-scoped config changes and filing or linking GitHub issues for global changes.

Calls are 4.0 tool calls; shapes live in [`../_generated/call-shapes.md`](../_generated/call-shapes.md). Every answer is the response envelope (`ok`, `data` or `error`). After each write read `ok`, `data.results[i].ok` and `error.code`; a multi-element call that part-fails answers `ok: false` with `error.code: partial_failure` next to the full `data`.

Shared GitHub conventions (issue template, scrub rule, `gh` guard, dedup procedure) are not duplicated here: see `<skill-base-dir>/../session-retrospective/references/github-feedback.md` for the C2/C4/C5 contract referenced in Step 5.

---

## Step 0 - Parse Arguments and Read Config

Parse `$ARGUMENTS`:

- **A UUID**: single-proposal mode. Skip Step 1 and go to Step 2 for that item.
- **`--scope global` or `--scope project`**: run only that scope's discovery query. Otherwise run both.

Read the config file directly (a file read, not an MCP call): the path on the SessionStart context's `Config:` line when there is one, otherwise the workspace `.taskorchestrator/config.yaml`. In personal scope that path is the user-level file. Below, "the config file" is whichever you read here. Read:

- `project.rootId` / `project.name`: enables the project-scoped discovery query and the per-root push in Step 5. In personal scope the root is the anchor-only personal root: skip the project-scoped discovery query (proposals live in the global container), but a project-scoped acceptance still edits and pushes the user-level file with rootId = the personal root. Without a rootId, project-scoped discovery and acceptance are unavailable; proceed global-only.
- `extensions.claude-code.retrospective.github_feedback.enabled`: default `false` when the block or key is absent.
- `extensions.claude-code.retrospective.github_feedback.repo`: default `jpicklyk/task-orchestrator`.

A leftover top-level `retrospective:` block is not read by the plugin and is rejected by a strict push (see Step 5, Accept - project-scoped).

---

## Step 1 - Discovery

**Skip in single-proposal mode.** Run both queries in parallel (unless `--scope` narrows to one).

**Global.** Find the "Improvement Proposals" container, then read its children:

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "Improvement Proposals", "kinds": ["item"], "limit": 5}}
```

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
- **Terminal**: skip.

If nothing is pending (no queue-role item without an `adoption-decision`, and no stalled item), report `No pending improvement proposals.` and stop.

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

- **Proposal body**: the `proposal` note (or the item's `summary` if the note is absent).
- **Scope clause**: the scope classification the note states (global or project-specific). If it cannot be parsed unambiguously, **treat as global**: never auto-edit a project's config on an ambiguous scope reading.
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

Below it list deferred proposals as `` `<short-id>` - deferred: <condition> (deferred <date>) `` and stalled ones as `` `<short-id>` - stalled in work: <hint> ``.

---

## Step 4 - Collect Decisions

For each pending or stalled proposal, show the proposed change (the exact YAML it names, or the file and section it targets) and ask via `AskUserQuestion`:

```
◆ "<proposal title>"  [scope: project]
  Proposed change:
  <exact YAML or file+section from the proposal note>

  1. Accept - apply this change
  2. Reject - do not adopt, record why
  3. Defer - revisit later, leave in queue
  4. Skip - no decision this round
```

For Reject or Defer take a one-line reason from the follow-up (or an "Other" free-text option) so the rationale is recorded verbatim in the `adoption-decision` note. `Skip` leaves the item untouched: no note, no transition.

---

## Step 5 - Carry Out Dispositions

### Accept - project-scoped

1. **Extract the exact YAML** from the proposal note. If it has none ready to apply, draft the edit, show it as a diff against the current config file, and confirm via `AskUserQuestion` before applying anything.
2. **A different `rootId`.** If the proposal targets a different `rootId` than this workspace's `project.rootId`, do not edit this config. Upsert `adoption-decision` recording that the change must be applied from the owning workspace, and treat the item as **deferred** (leave it in queue; do not advance).
3. Otherwise **Edit** the config file to apply the change (additive and targeted, not a rewrite).
4. **Validate** the full file text, LF-normalized, before pushing. Act on `valid` and `errors` (each `{path, reason}`). Strict validation rejects an unknown key: a top-level `retrospective:` or `orchestration:` block fails, so move it under `extensions.claude-code`. Rules: [`current/docs/config-reference.md`](../../../../current/docs/config-reference.md).

```to-call
{"tool": "config", "op": "validate", "args": {"rootId": "<rootId>", "configYaml": "<full file text>"}}
```

5. **Read the head version**, then **push** with it as `expectedVersion`:

```to-call
{"tool": "config", "op": "get", "args": {"rootId": "<rootId>"}}
```

```to-call
{"tool": "config", "op": "push", "args": {"rootId": "<rootId>", "configYaml": "<full file text>", "expectedVersion": 0}}
```

   Use the `version` that `get` returned (when the root has no config yet, `get` answers `not_found` and `expectedVersion` is ignored). Check `ok`, `data.results[0].ok` and `error.code`. On `version_conflict` never force blindly: `get` the head, diff its `configYaml` against the local file, and show the user the divergence. Re-push with `force: true` only after the user confirms which version wins. `config_invalid` carries `detail.errors` as `{path, reason}` pointers: fix and re-validate.
6. Upsert the closure note:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<uuid>", "key": "adoption-decision", "role": "work", "body": "accepted - <rationale>. Applied to <config file actually edited> (<section>), pushed per-root <rootId> on <YYYY-MM-DD>."}}
```

7. Advance with `start` twice, as two sequential calls (queue to work, then work to review):

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<uuid>", "trigger": "start"}}
```

   Read `ok`, `data.results[0].newRole` and `error.code`. Do **not** use `complete`: it jumps to terminal and gate-checks required notes across all phases, so the intentionally unfilled `outcome-verification` note would block it. `start` checks only the current phase's notes and lands in `review`.
8. **Stop in `review`.** Tell the user the item dwells in `review` with `outcome-verification` intentionally unfilled: a future `/session-retrospective` run verifies whether the change helped and fills it. This is by design.
9. Apply the **Source-trend write-back** below: the config change is applied, so the source trend retires now.

### Accept - global

1. **Ensure a GitHub issue exists.** If Step 2 found a `github-issue:` line, reuse it. Else, if `github_feedback.enabled` is `true` and the `gh` guard (C5) passes, file an issue per the C4 template and append the record-back line (C2) to the proposal note. Else proceed without an issue and say so plainly in the `adoption-decision` note.
2. **Distinguish install type:** `gh repo view <repo> --json viewerPermission -q .viewerPermission`.
   - **ADMIN, MAINTAIN or WRITE: maintainer path.** Upsert `adoption-decision` with `accepted - will implement; tracked in <url>.` and advance `start` twice (the same dwell-in-review pattern as project-scoped acceptance, with the same `complete` caveat). Outcome verification is filled by a later retrospective.
   - **Anything else, or `gh` unavailable: community handoff (the default).** Upsert **both** notes (one `notes_write` call, two `upsert` elements; the first shown):

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<uuid>", "key": "adoption-decision", "role": "work", "body": "accepted - delegated upstream; tracked in <issue-url>."}}
```

   The second element is `key: "outcome-verification"`, `role: "review"`, body `delegated upstream - verification happens in <repo> issue #<N>, not this workspace. Verdict: n/a (handed off).` Check `data.results[i].ok` for both. Then advance `start`, then `complete`, as two calls: `start` enters work, and `complete` goes straight to terminal because the all-phases note gate passes (`proposal`, `adoption-decision` and `outcome-verification` are filled). The item is now terminal; the GitHub issue is the living tracker.
3. Finish with the **Source-trend write-back**: community handoff (terminal) retires the source trend now; the maintainer path leaves it active until a later retrospective verifies the landed change.

### Reject

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<uuid>", "key": "adoption-decision", "role": "work", "body": "rejected - <reason>. Recorded so future retrospectives do not re-propose."}}
```

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<uuid>", "trigger": "cancel", "summary": "rejected - <reason>"}}
```

Read `ok`, `data.results[0].newRole` and `error.code`. `cancel` is gate-free and goes straight to terminal from any non-terminal role. Do not fill `outcome-verification` for a rejection. Then apply the **Source-trend write-back**.

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

- **After Reject:** `get` the trend for its full summary, then `items_write` `update {id, summary}` with one line appended: `proposal <short-id> rejected <YYYY-MM-DD> - do not re-graduate; see its adoption-decision note.` The trend stays active, and every future retrospective sees the rejection inline in its Step 4.2 listing.
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

Close with a reminder: accepted items dwelling in `review` are not stuck. Their `outcome-verification` note is filled by a future `/session-retrospective` run once the change has had time to show effect.

---

## Troubleshooting

**`gh` unavailable or unauthenticated.** `gh auth status` failed (the C5 guard in the shared reference). For global acceptances proceed without an issue and say so in the note (`accepted - no GitHub issue filed (gh unavailable).`). Filing is best-effort and never blocks the disposition; the user can file the issue later and record the URL on the proposal note.

**`version_conflict` on a config push.** The head moved since this workspace last read it (another session, `manage-schemas` or `quick-start` pushed). `get` the head, diff, show the user exactly what differs, and force only after explicit confirmation of which version wins.

**`config_invalid` on validate or push.** Read `detail.errors`. The usual cause is a section written at the top level that belongs under `extensions.claude-code` (`retrospective`, `orchestration`, `actor_attribution`).

**Proposal has no exact YAML.** The body describes the change in prose only. Draft the edit, show it as a diff, and get explicit confirmation via `AskUserQuestion` before applying. Do not guess silently and push.

**Proposal targets a different workspace's `rootId`.** It was likely created in another project sharing the database. Do not edit this config. Record in `adoption-decision` that the change must be applied from the owning workspace (name the `rootId` if known), and leave the proposal in `queue`.
