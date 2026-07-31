---
name: session-retrospective
description: "Analyzes the current implementation run — evaluates schema effectiveness, delegation alignment, note quality, and plan-to-execution fit. Captures cross-session trends and proposes improvements when patterns repeat. Use after implementation runs, or when user says 'retrospective', 'session review', 'what did we learn', 'analyze this run', 'how did that go', 'evaluate our process', 'wrap up', 'end of session review'. Also use when the output style's retrospective nudge fires after complete_tree."
argument-hint: "[optional: root item UUID] [--dry-run to preview without creating items]"
---

# Session Retrospective

Structured post-implementation analysis. Evaluates the current run across five dimensions, persists findings in MCP, and maintains cross-session trend memory to surface actionable improvement proposals.

---

## Step 0 — Mode Check

If `$ARGUMENTS` contains `--dry-run`, set **DRY_RUN = true**. In dry-run mode, perform steps 1-4 and render the report (step 9) but skip steps 5-8 (no MCP item creation, no memory updates). Announce at the top of the report: `**Dry run** — no items created, no memory updated.`

---

## Step 1 — Gather Scope

Determine which items to analyze by collecting distributed `session-tracking` notes.

### 1a. Identify items in scope

**If `$ARGUMENTS` contains a UUID (root item ID):**

```
query_items(operation="overview", itemId="<root-uuid>")
```

This returns the root item and its children. Collect all item UUIDs from the overview.

A supplied root UUID is the **authoritative scope** — this covers dispatched mode, e.g. a background agent invoked with the root item ID (see the output style's hook-driven Retrospective dispatch, or the retro-trigger hook's background-agent directive). When a root UUID is supplied, run only this overview call and do **not** run the fallback scan below (neither the `get_context` calls nor the terminal-items search) — the fallback scan applies only when no UUID argument was provided.

**If no root item ID provided:**

Check for a known project root: session context injected by the SessionStart hook, or a `project.rootId` entry in `.taskorchestrator/config.yaml`.

**If a project rootId is known**, scope both fallback calls to that subtree so concurrent runs in other projects sharing the same DB aren't conflated into this retrospective:

```
get_context(ancestorId="<rootId>") — active, blocked, stalled items within the project subtree
query_items(operation="search", role="terminal", sortBy="modifiedAt", sortOrder="desc", limit=20, ancestorId="<rootId>")
```

**If no project rootId is configured**, fall back to the prior global behavior (unchanged):

```
get_context() — active, blocked, stalled items
query_items(operation="search", role="terminal", sortBy="modifiedAt", sortOrder="desc", limit=20)
```

Build scope from recently completed items (compare `modifiedAt` to current date). Discard items that appear stale (modified more than 24 hours ago).

### 1b. Collect distributed notes

For each item in scope (up to 20):

```
query_notes(operation="list", itemId="<uuid>", includeBody=true)
```

`operation` is **required** — `query_notes` has no default and rejects the call without it (`Missing required parameter: operation`). Use `operation="list"` to enumerate an item's notes.

Extract:
- Notes with key `session-tracking` — these contain per-item outcome, files changed, deviations, friction, observations, and test results
- Notes with key `delegation-metadata` (optional) — orchestrator-recorded model and isolation data

### 1c. Early exit

**If no items are found in scope, or no `session-tracking` notes exist on any item:** Exit early with:
```
No implementation run data found. Nothing to retrospect — run `/implement` first, then try again.
```

---

## Step 2 — Aggregate Note Data

From the collected `session-tracking` notes, aggregate across all items:

- **Total item count** and **outcome distribution** (success, partial, failed, skipped)
- **Combined files list** — all files changed across items, deduplicated
- **Combined friction list** — all friction entries from all items
- **Combined observations** — notable observations from all items
- **Test results summary** — pass/fail counts across items

If `delegation-metadata` notes exist on any items, extract:
- Model used per delegation (haiku, sonnet, opus)
- Isolation mode (inline, worktree)
- These feed into delegation alignment analysis (step 3b)

Run `get_context()` in parallel for the current state snapshot.

---

## Step 3 — Evaluate Across Dimensions

### 3a. Schema Effectiveness

For each item in scope, examine its actual notes (from step 1b):
- Which schema-required notes exist? Check for non-empty content.
- Token count per note: **<50 = sparse** (flag), **50-500 = appropriate**, **>500 = potentially verbose** (flag for status-type notes; specification notes are exempt from the upper bound)
- Were any items missing required notes (indicating gate failures or schema-free items)?
- **Score:** Fraction of expected schema notes that exist with appropriately sized content

### 3b. Delegation Alignment

**If `delegation-metadata` notes exist on items**, cross-reference against the delegation table:

| Task type | Expected model |
|-----------|---------------|
| MCP bulk ops, materialization, simple queries | `haiku` |
| Code reading, implementation, test writing | `sonnet` |
| Architecture, complex tradeoffs, multi-file synthesis | `opus` |

- Flag misalignments (e.g., opus for bulk MCP ops, haiku for architecture)
- **Score:** Fraction of delegations matching expected model for their task type

**If no `delegation-metadata` notes exist:** Note "delegation metadata not recorded" and skip scoring for this dimension.

### 3c. Note Effectiveness

For items with both queue-phase notes (specs) and work-phase notes (implementation):
- Compare spec content themes to implementation note themes
- If implementation notes mention "deviated from spec", "unexpected", or "assumption was wrong" -> flag as a spec gap
- If work-phase notes are nearly empty (<30 tokens) -> flag as context loss for downstream agents
- **Score:** Qualitative (effective / mixed / ineffective)

### 3d. Plan-to-Execution Alignment

Compare item creation timestamps to the root item's creation time (or the earliest item in scope if no root provided):
- Items created significantly after the root (>1 hour) = ad-hoc additions (may be necessary or scope creep)
- Items still in queue role under the root = planned but skipped
- **Score:** Fraction of planned items that reached terminal

### 3e. Friction Synthesis

Extract friction entries from each item's `session-tracking` note. Group by type:
- `tool-error` — MCP or tool failures
- `excessive-roundtrips` — more calls than necessary
- `workaround` — agent had to work around a limitation
- `api-confusion` — unclear API semantics

Identify themes across entries (e.g., "3 friction entries related to gate failures on items without schemas").

---

## Step 4 — Check Trend Memory

Trend memory lives in MCP as items under a `Retrospective Trends` container — not in a file. Reads are targeted queries, not a whole-file load.

### 4.1 Discover the Trends container

This container is process-global by design — the shared, cross-project learning layer, same rationale as the `Session Retrospectives` / `Improvement Proposals` containers (5a/7a) — it deliberately lives outside any project root and this search stays unscoped even when a project rootId is known.

```
query_items(operation="search", query="Retrospective Trends", limit=5)
```

Cross-check with a list-mode search (`query_items(operation="search", tags="container", limit=20)`, filter to title) if the FTS hit is ambiguous or empty — an FTS-desync lesson from other containers in this skill.

**If the container is absent or empty AND `memory/retrospectives.md` exists containing at least one `- <kebab-key>: ...` entry line:** run the one-time migration under Step 6 FIRST, then continue with 4.2 below against the freshly migrated container.

**If both are absent** (no container, and no legacy file with entries): this is the first retrospective ever run against this database. Skip the rest of Step 4 — all findings are new baselines.

### 4.2 Read the active trends listing

```
query_items(operation="search", tags="retrospective-trend", role="queue", limit=100)
```

This is list-mode (structured filter, no `query`), so it returns every non-retired trend's title + summary — roughly 3-4k tokens for ~60 trends. This replaces the old whole-file read outright.

### 4.3 Match findings

Match each Step 3 dimension finding against the listing (titles + summaries):
- If a finding matches an existing trend (same schema note, same delegation pattern, same friction type), note the incremented session count for Step 6.
- If a finding is new, mark it as a candidate for a new trend item in Step 6.
- For uncertain matches where title/summary keyword matching isn't conclusive, run a per-finding FTS query for semantic reach:
  ```
  query_items(operation="search", query="<finding keywords>", scope={tags: ["retrospective-trend"]})
  ```

### 4.4 Fetch full evidence (only when it matters)

For matched trends where per-session history changes the assessment (e.g., judging whether a pattern is worsening, stabilizing, or was already flagged as environmental), fetch evidence notes:

```
query_notes(operation="list", itemId="<trend-uuid>", includeBody=true)
```

Do not read `retrospectives-history.md` during a normal run — it stays a frozen provenance archive for the legacy file layout. Read it only when tracing the evidence chain of an entry that cites an archived legacy pattern by name.

---

## Step 5 — Persist the Retrospective

**Skip entirely in dry-run mode.**

### 5a. Find or create container

This container is process-global by design — it is the shared, cross-project learning layer, so it deliberately lives outside any project root and this search stays unscoped even when a project rootId is known.

```
query_items(operation="search", query="Session Retrospectives", limit=5)
```

If no match with that exact title at depth 0, create it:

```
manage_items(operation="create", items=[{
  title: "Session Retrospectives",
  summary: "Container for structured post-implementation analyses.",
  type: "container",
  tags: "container",
  priority: "low"
}])
```

### 5b. Create retrospective item

If a project rootId (or project name from `.taskorchestrator/config.yaml` `project.name`) is known, include it in the title so a shared-DB container holding retrospectives from multiple projects stays attributable:

```
manage_items(operation="create", items=[{
  title: "Retrospective — <project-name> — <root-item-title> — <YYYY-MM-DD>",
  summary: "<one-sentence summary of key findings>",
  tags: "session-retrospective",
  parentId: "<container-uuid>"
}])
```

If no project name is known, omit that segment: `"Retrospective — <root-item-title> — <YYYY-MM-DD>"`.

### 5c. Fill queue-phase notes

The `session-retrospective` schema has **four** required notes: three queue-phase (`session-metrics`, `workflow-evaluation`, `improvement-signals`) plus one **work-phase** `actions-taken` (a closure record of the proposals created in Step 7). Fill the three queue notes now, in a single batch; `actions-taken` is filled later, at Step 8b, once the proposals are known:

```
manage_notes(operation="upsert", notes=[
  {
    itemId: "<retro-uuid>",
    key: "session-metrics",
    role: "queue",
    body: "<Step 3 quantitative data: item counts, outcome distribution, schema usage, token estimates, files changed>"
  },
  {
    itemId: "<retro-uuid>",
    key: "workflow-evaluation",
    role: "queue",
    body: "<Step 3 qualitative assessment: per-dimension scores and key findings>"
  },
  {
    itemId: "<retro-uuid>",
    key: "improvement-signals",
    role: "queue",
    body: "<Step 4 trend analysis: new trends, reinforced trends, proposals>"
  }
])
```

### 5d. Advance to work phase

The three queue notes satisfy the queue→work gate. Advance the item to `work` (the work-phase `actions-taken` gate stays open until Step 8b):

```
advance_item(transitions=[{itemId: "<retro-uuid>", trigger: "start"}])
```

Do **not** complete the item yet — the `actions-taken` work-phase note is still required and depends on the Step 7 proposal results. Completion happens at Step 8b.

---

## Step 6 — Update Trend Memory

**Skip entirely in dry-run mode.**

Write trend items from the Step 3/4 findings as MCP items, batched — one `manage_notes` call for all evidence-note upserts this run, one `manage_items` call for all summary updates this run:

- **Recurrence** (finding matched an existing trend in Step 4.3): upsert an evidence note `evidence-<YYYY-MM-DD>-<retro-short-id>` (role `work`, body = this session's specific evidence: what happened, retro item ID, cost/impact) AND update the trend item's `summary` — increment `Sessions: N`, update `Last seen: YYYY-MM-DD`, and condense the observation if drift warrants it.
- **New pattern**: create a trend item (shape below) with `Sessions: 1` in its summary, plus its first evidence note.
- **Retire** (a previously-active trend is now addressed, obsolete, superseded, or accepted-environmental): `advance_item(itemId="<trend-uuid>", trigger="cancel", summary="archived: <reason>")`. `cancel` is gate-free — it moves any non-terminal role straight to terminal with no note check. `statusLabel: cancelled` on a trend item means "retired from active watching", not failure; the transition's `summary` line carries the actual semantic. A cancelled trend drops out of the Step 4.2 active listing automatically (it filters `role="queue"`).
- **Graduation** (Step 7 creates a proposal from this trend): record `GRADUATED -> proposal <short-id>` in the trend's `summary`. This does **not** change the trend's role — cancelling a graduated trend, if ever warranted, is a separate later decision once the proposal resolves.

**Never call `advance_item` with `start` or `complete` on a trend item** — only `create`, `update`, note upserts, and `cancel`. This keeps the lifecycle gate-free under any user's schema config: an external user's `default` schema could otherwise gate-block `start`/`complete` on these untyped items, and trend items carry no note schema of their own to satisfy such a gate.

### New trend item shape

```
manage_items(operation="create", items=[{
  title: "trend: <kebab-key> — <one-line claim>",
  summary: "<distilled observation>. Sessions: 1. Last seen: YYYY-MM-DD.",
  tags: "retrospective-trend,<dimension>",
  parentId: "<trends-container-uuid>",
  priority: "low"
}])
```

`<dimension>` is one of `schema-effectiveness | delegation | note-quality | friction | extension-candidate`; append `,positive` for a positive pattern. The kebab key in the title is the stable identity to match on across sessions — not exact summary text. Batch up to ~10 creates per call.

Then upsert its first evidence note:

```
manage_notes(operation="upsert", notes=[{
  itemId: "<new-trend-uuid>",
  key: "evidence-<YYYY-MM-DD>-<retro-short-id>",
  role: "work",
  body: "<this session's specific evidence: what happened, retro item ID, cost/impact>"
}])
```

**Condense, don't accumulate.** The trend's `summary` is the single distilled current truth — rewrite it on every update rather than appending to it. Per-session detail belongs in evidence notes, which are naturally bounded (one per recurrence) rather than growing a single blob indefinitely. Sessions count is derivable as the number of `evidence-*` notes (`query_notes(operation="list", itemId=..., includeBody=false)`), but the summary's `Sessions: N` is a denormalized convenience kept in sync in the same write that adds the evidence note — never let it drift out of step.

**Improvement Proposals stay MCP-only, as before.** Proposal status, priority, and dates live on the proposal item, never mirrored into a trend's summary beyond the one-line `GRADUATED -> proposal <short-id>` pointer. Resolve current proposal state on demand with `query_items(operation="overview", anchorId="<proposals-container-uuid>", includeChildren=true)`. Per-proposal outcome narrative belongs on the proposal item's own `adoption-decision` / `outcome-verification` notes.

### One-time migration from the legacy layout

Replaces the old pointer/history-split migration entirely — any `memory/retrospectives.md` content, in whatever legacy shape it's in, now migrates to MCP instead of to a history file.

**Condition** (checked at Step 4.1): the Trends container is absent or empty AND `memory/retrospectives.md` exists containing at least one `- <kebab-key>: ...` entry line. This is self-terminating: after migration the file is a pointer stub with no entry lines, so the condition can never match again for this project.

**Procedure** — create-and-verify BEFORE touching the file. An interrupted migration then leaves content duplicated (recoverable), never lost:

1. **Create the Trends container** (if it wasn't already found empty in 4.1).
2. **Read `retrospectives.md` in full** — the last expensive whole-file read this skill will ever do. Parse every `- <kebab-key>: ...` entry under each `## ` section, mapping section header to dimension tag:

   | Section header | Dimension tag |
   |---|---|
   | Schema Effectiveness | `schema-effectiveness` |
   | Delegation Patterns | `delegation` |
   | Note Quality | `note-quality` |
   | Friction | `friction` |
   | Extension Candidates | `extension-candidate` |

   Ignore `## Meta` narrative sections and the Improvement Proposals pointer section (already MCP-owned — nothing to migrate there). Entries still marked `[ARCHIVED]` from a prior partial legacy migration are migrated as trend items and then immediately retired: `advance_item(itemId="<uuid>", trigger="cancel", summary="archived: migrated from legacy file, was already archived")`.
3. **Create one trend item per entry**, batched (~10 per `manage_items` call): title from the kebab key plus a distilled claim; summary = the condensed observation with the existing `Sessions: N` / `Last seen` carried over verbatim, plus any `GRADUATED -> proposal <id>` pointer already present in the entry. Then upsert one `evidence-migrated` note per item (role `work`), batched, holding the ORIGINAL entry text verbatim — this is the provenance record for the migration.
4. **Verify**: parsed entry count == created item count, via `query_items(operation="overview", anchorId="<trends-container-uuid>", includeChildren=true)` (or a `tags="retrospective-trend"` list search scoped to the container). **On mismatch, stop and report — do NOT stub the file.** Leave the migration to retry on the next run; the condition still matches until the file is stubbed.
5. **Only then rewrite `retrospectives.md`** to a pointer stub containing: the Trends container UUID, the active-trends query snippet from Step 4.2, a note that history stays frozen in `retrospectives-history.md` (do not migrate it — it remains a provenance archive, never itself migrated), and the migration date + this retrospective's item ID.
6. **Record it** in the current retrospective's `actions-taken` note (Step 8b) — entry count migrated, container UUID.

**Multi-project note.** `retrospectives.md` was per-project memory (one file per Claude Code project directory); the Trends container is per-DATABASE. Users running several projects against one MCP server converge on one shared Trends container once each project has migrated — this is intended, the same process-global model already used for `Session Retrospectives` and `Improvement Proposals`.

---

## Step 7 — Create Improvement Proposals

**Skip entirely in dry-run mode.**

Check the trend items created or updated in Step 6 (their `summary` field carries `Sessions: N`). For each trend with **Sessions >= 2**:

### 7a. Find or create proposals container

This container is also process-global by design (same rationale as 5a) — it stays outside any project root, and this search stays unscoped even when a project rootId is known.

```
query_items(operation="search", query="Improvement Proposals", limit=5)
```

Create if missing (same pattern as 5a — include `type: "container"` and `tags: "container"`).

### 7b. Create proposal items — scope-based anchoring

For each graduating trend, first read the **scope** classification already captured in the
`improvement-signals` note (steps 3/4): **global** (plugin skills/hooks, output styles, server floor
config) or **project-specific** (one project's schemas/traits/config). If the scope cannot be parsed
from the classification, treat it as **global** — never guess and auto-anchor a proposal under a
project on ambiguous evidence.

**Global scope** — anchor under the process-global container found/created in step 7a (unchanged):

```
manage_items(operation="create", items=[{
  title: "Proposal: <concrete change description>",
  summary: "<what to change and why — reference retrospective item IDs that surfaced the trend>",
  tags: "improvement-proposal",
  parentId: "<7a-proposals-container-uuid>",
  priority: "low"
}])
```

**Project-specific scope** — find or create a per-project "Improvement Proposals" container under
that project's `rootId` instead, and anchor there:

```
query_items(operation="search", query="Improvement Proposals", ancestorId="<rootId>", limit=5)
```

If no match, create it:

```
manage_items(operation="create", items=[{
  title: "Improvement Proposals",
  type: "container",
  tags: "container",
  parentId: "<rootId>",
  priority: "low"
}])
```

Then create the proposal item exactly as in the global case above, but with
`parentId: "<per-project-container-uuid>"`.

The proposal should include a **concrete suggestion** — not just "this is a problem" but the specific change:
- Schema edits: include the exact YAML to add/modify
- Skill updates: reference the section and describe the change
- Output style adjustments: specify the zone and content
- Hook additions: specify the event, matcher, and purpose

### 7c. File GitHub issues (global proposals only)

For each proposal created in step 7b with **global** scope this run — never project-scoped ones —
attempt to file or link a GitHub issue. Full conventions, the issue template, and the dedup procedure
live in `references/github-feedback.md`; this step carries only the call shapes. Every sub-step here
is best-effort: a failure anywhere is caught, recorded as a one-line reason, and the retrospective
continues (mirrors the step 8c acknowledgment pattern).

1. **Config gate** — read `retrospective.github_feedback` from the workspace `.taskorchestrator/config.yaml`
   (direct file read). Not `enabled: true` ⇒ skip all of 7c for this run; record
   `github filing: disabled` for step 8b.
2. **gh guard** — `gh auth status` via Bash; non-zero exit ⇒ skip filing, record the reason (e.g.
   `gh unavailable/unauthenticated`).
3. **Dedup (cheapest first)** — check sibling proposals' `github-issue:` lines (cap 10 note reads),
   then:
   ```bash
   gh issue list --repo <repo> --state all --search "<3-5 distinctive keywords>" --json number,title,url --limit 10
   ```
   Reuse a matching issue's URL when the same change is already tracked.
4. **File** (only if no match found):
   ```bash
   gh issue create --repo <repo> --title "[proposal] <...>" --body-file <scratchpad-tmp-path> --label enhancement
   ```
   Retry once without `--label` if the label doesn't exist on the repo.
5. **Record back** — re-upsert the proposal note with a final `github-issue: <url>` line appended to
   its body (see `references/github-feedback.md` C2).
6. Every one of the above is best-effort — 7c must never fail the retrospective.

---

## Step 8 — Meta-Evaluation

**Skip entirely in dry-run mode.**

Query prior retrospectives:

```
query_items(operation="search", tags="session-retrospective", limit=20)
```

**If 3+ retrospectives exist**, evaluate:

Proposal and trend state for both checks below comes from MCP — query the container once and read roles off the result:

```
query_items(operation="overview", anchorId="<proposals-container-uuid>", includeChildren=true)
```

**If a project rootId is known**, also pull project-scoped proposals — these anchor outside the
global container per step 7b's scope-based anchoring, so the overview above won't surface them:

```
query_items(operation="search", tags="improvement-proposal", ancestorId="<rootId>", limit=20)
```

Fold both result sets into the durability and staleness checks below.

1. **Trend durability:** Did previously identified trends get addressed? Query trend items whose summary carries a `GRADUATED -> proposal <id>` pointer (`query_items(operation="search", query="GRADUATED", scope={tags: ["retrospective-trend"]})`, or scan the Step 4.2 listing) and check whether the proposal each one graduated into is terminal.
2. **Proposal staleness:** Any proposals created 3+ retrospectives ago with no movement (still in queue)? Also flag any stuck in `work` — a proposal sitting in-progress across runs is usually a stalled adoption, not active work. When stale queue proposals exist (global or project-scoped), the report (step 9) should suggest running `/task-orchestrator:review-proposals`. Treat `cancelled` proposals as **resolved-rejected**, not stale — read their `adoption-decision` note before proposing anything similar again (do-not-re-propose rule); a rejected idea resurfacing under a new title is a signal to check history first, not to recreate it.
3. **Self-quality:** Are retrospective notes converging on useful patterns, or repeating the same observations without resolution? Are notes too verbose (>800 tokens each) or too shallow (<100 tokens)?

If meta-findings warrant it, add a brief note to the current retrospective's `improvement-signals` note via:

```
manage_notes(operation="upsert", notes=[{
  itemId: "<current-retro-uuid>",
  key: "improvement-signals",
  role: "queue",
  body: "<updated body with meta-evaluation appended>"
}])
```

---

## Step 8b — Fill Closure Note and Complete

**Skip entirely in dry-run mode.**

Now that Step 7 has determined which improvement proposals were created (or that none graduated), fill the work-phase `actions-taken` note — the schema's closure record — and complete the item.

```
manage_notes(operation="upsert", notes=[{
  itemId: "<retro-uuid>",
  key: "actions-taken",
  role: "work",
  body: "<closure record: improvement-proposal items created/updated in Step 7 (title + short-id each); for each **global** proposal append its GitHub filing outcome — `filed <url>`, `linked existing <url>`, or `github filing skipped: <reason>`; for each **project-scoped** proposal append `anchored under project <rootId>`; or 'none graduated (≥2 sessions) this run'; plus trend items created/updated/retired this run (short-ids each); plus, if the one-time legacy migration ran this session, the entry count migrated and the Trends container UUID>"
}])
```

The `actions-taken` note satisfies the work→terminal gate. Complete:

```
advance_item(transitions=[{itemId: "<retro-uuid>", trigger: "complete"}])
```

### 8c. Acknowledge the retrospective to the trigger hooks

Run via Bash, from the skill's base directory shown at invocation (`<skill-base-dir>`):

```
node "<skill-base-dir>/../../hooks/retro-ack.mjs"
```

This stamps the hook dedup marker as handled, extending the suppression window so the Stop backstop does not re-prompt for a retrospective — a manual run completing its own item must not look like a new implementation run needing one. This step is best-effort: if the script is missing (plugin layout changed), continue without failing the retrospective.

---

## Step 9 — Report

Render a dashboard using the output style visual conventions:

```
## Session Retrospective — <root-item-title>

**<YYYY-MM-DD> · <N> items · <N> schemas used**

### Dimension Scores

| Dimension | Score | Key Finding |
|-----------|-------|-------------|
| Schema effectiveness | <fraction or qualitative> | <one-line summary> |
| Delegation alignment | <fraction or "not recorded"> | <one-line summary> |
| Note effectiveness | <qualitative> | <one-line summary> |
| Plan-to-execution | <fraction> | <one-line summary> |
| Friction | <count> entries, <N> themes | <top theme> |

### Trends

| Pattern | Sessions | Status |
|---------|----------|--------|
| <trend description> | N | new / reinforced / addressed |

### Improvement Proposals Created

| ID | Proposal | Trigger | Issue |
|----|----------|---------|-------|
| `<short-id>` | <description> | <trend that graduated> | <url or —> |
```

**Conditional prefix:**
- Dry-run: `**Dry run** — no items created, no memory updated.`

Omit sections with no data (e.g., no improvement proposals -> omit that table). If `delegation-metadata` notes were present, include a delegations count in the header line.

---

## Troubleshooting

**No session-tracking notes found**
- Cause: Implementing agents did not fill their `session-tracking` notes. This happens when items have no matching note schema (schema-free items skip gate enforcement).
- Solution: Check `.taskorchestrator/config.yaml` for a `default` schema that includes `session-tracking` as a required note. Adding it ensures agents are prompted to fill tracking data.

**Schema not recognized (`expectedNotes` empty)**
- Cause: `session-retrospective` tag not in `.taskorchestrator/config.yaml`, or MCP not reconnected after config edit
- Solution: Run `/mcp` to reconnect, verify config has the schema

**Container not found**
- Cause: First time creating retrospectives, trends, or improvement proposals in this database.
- Solution: Containers are created lazily — `Session Retrospectives` at step 5a, `Retrospective Trends` at step 4.1 (or on the first trend write in step 6), `Improvement Proposals` at step 7a. No action needed.

**Legacy trend file detected**
- Cause: `memory/retrospectives.md` exists with `- <key>: ...` entry lines and the Trends container is absent or empty — the one-time migration condition (step 4.1 / step 6) matches.
- Solution: The skill runs the migration automatically this pass — see "One-time migration from the legacy layout" under step 6. No manual action needed. If the migration reports a count mismatch, it stops without stubbing the file and reports the discrepancy; re-run the retrospective to retry — the condition still matches until the file is stubbed.

**Trend item advance blocked by a gate**
- Cause: Should never happen — the skill only ever uses `create`, `update`, note upserts, and `cancel` on trend items, all of which are gate-free. If it does happen, an unexpected schema in the user's config is intercepting an operation this skill assumes is unconditionally safe.
- Solution: Fill the named required note minimally so the operation can proceed, then record the anomaly as an observation in the current retrospective's own notes — it's worth a bug report against the skill's gate-free assumption.

**Retrospective pulled in another project's items**
- Cause: Shared multi-project DB with no project scope configured, so the step 1a fallback scan searched globally instead of within the current project's subtree.
- Solution: Configure `project.rootId` in `.taskorchestrator/config.yaml`, or pass the root item UUID as an argument to the skill:
```yaml
project:
  rootId: "<uuid>"
  name: "<project name>"
```
