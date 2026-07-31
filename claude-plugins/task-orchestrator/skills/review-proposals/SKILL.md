---
name: review-proposals
description: "Triages pending improvement-proposal MCP items — presents each with its scope and evidence, collects an accept/reject/defer decision per proposal, and carries out the disposition: project-scoped acceptances get their exact YAML applied to .taskorchestrator/config.yaml and pushed per-root; global acceptances get a tracked GitHub issue (or dwell in review for the maintainer); rejections are recorded and cancelled; deferrals are recorded and left in queue. Use when a user says: review proposals, triage proposals, pending proposals, what proposals are open, adopt proposals, process improvement proposals, accept or reject proposals."
argument-hint: "[optional: proposal UUID | --scope global|project]"
---

# Review Proposals

Triages `improvement-proposal` MCP items created by `/session-retrospective` when a cross-session
trend graduates. Presents pending proposals, collects a per-proposal decision, and carries out the
disposition — including applying project-scoped config changes and filing/linking GitHub issues for
global changes.

Shared GitHub conventions (issue template, scrub rule, `gh` guard, dedup procedure) are **not**
duplicated here — see
`<skill-base-dir>/../session-retrospective/references/github-feedback.md` for the full C2/C4/C5
contract and dedup steps referenced throughout Step 5.

---

## Step 0 — Parse Arguments and Read Config

Parse `$ARGUMENTS`:

- **A UUID** → single-proposal mode: skip Step 1 discovery entirely: go straight to Step 2 for
  that one item.
- **`--scope global` or `--scope project`** → filter Step 1 discovery to only that scope's query.
  Absent → run both discovery queries.
- Neither → full discovery, both scopes.

Read the workspace `.taskorchestrator/config.yaml` directly (file read, not MCP) for:

- `project.rootId` / `project.name` — enables the project-scoped discovery query and per-root push
  in Step 5. If absent, project-scoped discovery and project-scoped acceptance are unavailable —
  proceed with global-only discovery.
- `retrospective.github_feedback.enabled` — default `false` if the block or key is absent.
- `retrospective.github_feedback.repo` — default `jpicklyk/task-orchestrator` if absent.

---

## Step 1 — Discovery

**Skip this step entirely in single-proposal mode (UUID argument supplied).**

Run both queries in parallel (unless `--scope` narrows to one):

**Global:**

```
query_items(operation="search", query="Improvement Proposals", limit=5)
```

Find the "Improvement Proposals" container from the result, then:

```
query_items(operation="overview", anchorId="<container-uuid>", includeChildren=true)
```

**Project-scoped** (only if `project.rootId` is known):

```
query_items(operation="search", tags="improvement-proposal", ancestorId="<rootId>", limit=50)
```

### Partition results

For each candidate item, classify by role and notes:

- **Pending** — role `queue`, no `adoption-decision` note yet. The main triage set.
- **Deferred** — role `queue`, has an `adoption-decision` note whose body starts with "deferred".
  List in a **separate section**; do not re-offer for a decision unless the user explicitly asks to
  revisit deferred proposals, or the stated revisit condition in the note plausibly holds now.
- **Stalled** — role `work` (item was accepted and advanced to `work` via `start` but never reached
  `review`/terminal — an interrupted or stuck adoption). Flag as **stalled adoption**; offer to
  resume (fill remaining notes and `complete`) or cancel.
- **Terminal** — skip; already resolved (accepted-and-verified, rejected, or fully handed off).

**If nothing is pending** (no queue-role items without an adoption-decision, and no stalled items),
report:

```
No pending improvement proposals.
```

and stop — do not proceed to later steps.

---

## Step 2 — Load Proposal Detail

For each candidate (pending + stalled; cap at 15 — if more, take the 15 oldest by creation and note
the overflow count), fetch notes:

```
query_notes(operation="list", itemId="<uuid>", includeBody=true)
```

From the returned notes, extract:

- **Proposal body** — the `proposal` note (or the item's `summary` if the note is absent).
- **Scope clause** — read the scope classification the note states (global vs project-specific).
  If it cannot be parsed unambiguously, **treat as global** — this is conservative: never auto-edit
  a project's `config.yaml` on an ambiguous scope reading.
- **Existing `github-issue:` line** — the last line of the proposal note body, if present, in the
  form `github-issue: <url>`. Carry this forward so Step 5's global-accept path can reuse it instead
  of filing a duplicate.

---

## Step 3 — Present Triage Table

Render one compact table covering all pending + stalled candidates (deferred proposals get their
own short list underneath, not full rows):

```
| # | ID | Proposal | Scope | Evidence | Age | Issue |
|---|-----|----------|-------|----------|-----|-------|
| 1 | `a1b2c3d4` | Add `session-tracking` maxLength guard | project | 3 sessions, sparse-note trend | 4d | — |
| 2 | `e5f6a7b8` | Nudge cooldown too short for solo dev | global | 2 sessions, friction theme | 1d | #142 |
```

- **Evidence** — a one-line summary of the trend/session count that graduated this proposal (from
  the proposal body or summary).
- **Age** — days since creation.
- **Issue** — existing `github-issue:` link if present, else `—`.

Below the table, list deferred proposals as a short reminder line each: `` `<short-id>` — deferred:
<condition> (deferred <date>) `` and stalled proposals as `` `<short-id>` — stalled in work: <hint>
``.

---

## Step 4 — Collect Decisions

For each pending/stalled proposal, show the proposed change (the exact YAML the proposal names, or
the file + section it targets) and ask via `AskUserQuestion`:

```
◆ "<proposal title>"  [scope: project]
  Proposed change:
  <exact YAML or file+section from the proposal note>

  What would you like to do?
  1. Accept — apply this change
  2. Reject — do not adopt, record why
  3. Defer — revisit later, leave in queue
  4. Skip — no decision this round
```

If the user picks Reject or Defer, either take a one-line reason from their follow-up or offer an
"Other" free-text option so the rationale can be recorded verbatim in the `adoption-decision` note.
`Skip` leaves the item untouched — no note upsert, no advance.

---

## Step 5 — Carry Out Dispositions

### Accept — project-scoped

1. **Extract the exact YAML** from the proposal note. If the proposal doesn't include ready-to-apply
   YAML, draft the edit yourself, show it to the user as a diff against the current
   `.taskorchestrator/config.yaml`, and confirm via `AskUserQuestion` before applying anything.
2. **If the proposal targets a different `rootId`** than this workspace's `project.rootId`: do not
   edit this workspace's config. Instead, upsert `adoption-decision` recording that the change must
   be applied from the owning workspace, and treat the item as **deferred** (leave it in queue) —
   do not advance it.
3. Otherwise, **Edit** the workspace `.taskorchestrator/config.yaml` to apply the change.
4. **Validate** before pushing — same bar as `manage-schemas`' config-format rules (see
   `<skill-base-dir>/../manage-schemas/references/config-format.md`):
   - File still parses as valid YAML.
   - Any note `role` values are limited to `queue` | `work` | `review`.
   - Existing sections are untouched — the edit is additive/targeted, not a rewrite.
5. **Push**: `manage_project_config(operation="push", rootId="<rootId>", configYaml="<full file content>")`.
   - On `CONFLICT_ERROR`: never force blindly. Call `manage_project_config(operation="get", rootId="<rootId>")`,
     diff the server's stored config against the local file, and surface the divergence to the user.
     Only re-push with `force: true` after explicit user confirmation.
   - A response listing `ignoredSections` containing `retrospective` and/or `project` is **expected**,
     not an error — those sections are client-side-only and never resolved server-side.
6. Upsert the closure note:
   ```
   manage_notes(operation="upsert", notes=[{
     itemId: "<uuid>",
     key: "adoption-decision",
     role: "work",
     body: "accepted — <rationale>. Applied to .taskorchestrator/config.yaml (<section>), pushed per-root <rootId> on <YYYY-MM-DD>."
   }])
   ```
7. Advance with `start` twice — queue→work, then work→review:
   ```
   advance_item(transitions=[{itemId: "<uuid>", trigger: "start"}])
   advance_item(transitions=[{itemId: "<uuid>", trigger: "start"}])
   ```
   Do **not** use `complete` here — `complete` jumps straight to terminal and gate-checks required
   notes across **all** phases, so it would be blocked by the intentionally-unfilled
   `outcome-verification` note. `start` checks only the current phase's notes (`adoption-decision`,
   just filled) and lands in `review`.
8. **Stop in `review` — do not advance further.** State this explicitly to the user: the item
   dwells in `review` with `outcome-verification` intentionally unfilled — a future
   `/session-retrospective` run verifies whether the applied change actually helped, and fills that
   note then. This is by design, not an oversight.

### Accept — global

1. **Ensure a GitHub issue exists** for the change:
   - If Step 2 found an existing `github-issue:` line, reuse that URL — no new issue.
   - Else, if `retrospective.github_feedback.enabled` is `true` and the `gh` guard (C5 in the
     shared reference) passes, file an issue per the C4 template and append the record-back line
     (C2) to the proposal note.
   - Else, proceed without an issue — say so plainly in the `adoption-decision` note.
2. **Distinguish install type** via:
   ```
   gh repo view <repo> --json viewerPermission -q .viewerPermission
   ```
   - **ADMIN / MAINTAIN / WRITE ⇒ maintainer path.** Upsert `adoption-decision`:
     `"accepted — will implement; tracked in <url>."` Advance `start` twice (queue→work,
     work→review) — the same dwell-in-review pattern as project-scoped acceptance, with the same
     `complete`-would-gate-block caveat; outcome-verification is filled by a later retrospective.
   - **Anything else, or `gh` unavailable ⇒ community handoff (default).** Upsert **both** notes:
     ```
     manage_notes(operation="upsert", notes=[
       {
         itemId: "<uuid>",
         key: "adoption-decision",
         role: "work",
         body: "accepted — delegated upstream; tracked in <issue-url>."
       },
       {
         itemId: "<uuid>",
         key: "outcome-verification",
         role: "review",
         body: "delegated upstream — verification happens in <repo> issue #<N>, not this workspace. Verdict: n/a (handed off)."
       }
     ])
     ```
     Then advance to terminal in two calls:
     ```
     advance_item(transitions=[{itemId: "<uuid>", trigger: "start"}])
     advance_item(transitions=[{itemId: "<uuid>", trigger: "complete"}])
     ```
     `start` enters work; `complete` then goes straight to terminal — its all-phases note gate
     passes because `proposal`, `adoption-decision`, AND `outcome-verification` are all filled.
     The item is now fully terminal — the GitHub issue is the living tracker, not this workspace.

### Reject

```
manage_notes(operation="upsert", notes=[{
  itemId: "<uuid>",
  key: "adoption-decision",
  role: "work",
  body: "rejected — <reason>. Recorded so future retrospectives do not re-propose."
}])
advance_item(transitions=[{itemId: "<uuid>", trigger: "cancel"}])
```

`cancel` is a single gate-free call — it goes straight to terminal from any non-terminal role, no
note gate involved. Do **not** fill `outcome-verification` for a rejection; there is no change to
verify.

### Defer

```
manage_notes(operation="upsert", notes=[{
  itemId: "<uuid>",
  key: "adoption-decision",
  role: "work",
  body: "deferred — revisit when <condition>. Deferred on <YYYY-MM-DD>."
}])
```

No `advance_item` call — the item stays in `queue`. A later Accept decision overwrites this same
note via upsert (last-writer-wins), so re-running this skill and accepting a previously-deferred
proposal works without any special-casing.

---

## Step 6 — Report

Render a summary table of the run:

```
## Proposal Review — <YYYY-MM-DD>

| ID | Proposal | Disposition | Action |
|----|----------|-------------|--------|
| `a1b2c3d4` | Add maxLength guard | Accepted | pushed .taskorchestrator/config.yaml (work_item_schemas), dwelling in review |
| `e5f6a7b8` | Nudge cooldown | Accepted (global) | filed https://github.com/jpicklyk/task-orchestrator/issues/143, terminal |
| `c9d0e1f2` | Rename note key | Rejected | cancelled — duplicate of existing key |
| `f3a4b5c6` | Widen skill pointer | Deferred | revisit when trait usage grows |
```

Close with a reminder: accepted items dwelling in `review` are not stuck — their
`outcome-verification` note is filled by a future `/session-retrospective` run once the applied
change has had a chance to show effect.

---

## Troubleshooting

**`gh` unavailable or unauthenticated**

Cause: `gh auth status` failed (not installed, not logged in, or network issue) — see the C5 guard
in `<skill-base-dir>/../session-retrospective/references/github-feedback.md`.

Solution: For global acceptances, proceed without filing an issue and say so in the
`adoption-decision` note (`"accepted — no GitHub issue filed (gh unavailable)."`). This never blocks
the disposition — filing is best-effort. The user can file the issue manually later and record the
URL back onto the proposal note themselves.

---

**`CONFLICT_ERROR` on `manage_project_config` push**

Cause: The stored per-root config's fingerprint has moved since this workspace last read it —
someone else (another session, `manage-schemas`, or `quick-start`) pushed a newer version.

Solution: Never force blindly. Call `manage_project_config(operation="get", rootId="<rootId>")`,
diff the returned `configYaml` against the local file, and show the user exactly what differs.
Only retry with `force: true` after the user explicitly confirms which version should win — a blind
force can silently revert someone else's concurrent change.

---

**Proposal has no exact YAML to apply**

Cause: The proposal body describes the change in prose only (common for proposals that predate a
YAML-inclusion convention, or for changes that aren't schema edits — e.g., a skill wording tweak).

Solution: Draft the edit yourself from the proposal's description, show it to the user as a diff
before touching any file, and get explicit confirmation via `AskUserQuestion` before applying. Do
not guess silently and push.

---

**Proposal targets a different workspace's `rootId`**

Cause: The proposal's stated scope names a project root UUID that doesn't match this workspace's
`project.rootId` — the proposal was likely created while working in a different project sharing the
same MCP database.

Solution: Do not edit this workspace's `.taskorchestrator/config.yaml`. Record in the
`adoption-decision` note that the change must be applied from the owning workspace (name the
`rootId` if known), and treat the proposal as deferred — leave it in `queue` rather than advancing
it, since no action was actually taken here.
