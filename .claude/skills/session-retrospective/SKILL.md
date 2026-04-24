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

**If no root item ID provided:**

```
get_context() — active, blocked, stalled items
query_items(operation="search", role="terminal", sortBy="modifiedAt", sortOrder="desc", limit=20)
```

Build scope from recently completed items (compare `modifiedAt` to current date). Discard items that appear stale (modified more than 24 hours ago).

### 1b. Collect distributed notes

For each item in scope (up to 20):

```
query_notes(itemId="<uuid>", includeBody=true)
```

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

Read the trend memory file `memory/retrospectives.md` from the auto memory directory (file read, not MCP call). The auto memory path is shown at session start — typically `~/.claude/projects/<project-key>/memory/`.

**If the file does not exist:** This is the first retrospective. Skip trend comparison — all findings are new baselines.

**If the file exists:** For each dimension finding from step 3:
- Search existing trend entries for matches (same schema note, same delegation pattern, same friction type)
- If a finding matches an existing trend, note the incremented session count
- If a finding is new, mark it as a candidate for the trends section

---

## Step 5 — Persist the Retrospective

**Skip entirely in dry-run mode.**

### 5a. Find or create container

```
query_items(operation="search", query="Session Retrospectives", depth=0, limit=5)
```

If no match with that exact title at depth 0, create it:

```
manage_items(operation="create", items=[{
  title: "Session Retrospectives",
  summary: "Container for structured post-implementation analyses.",
  priority: "low"
}])
```

### 5b. Create retrospective item

```
manage_items(operation="create", items=[{
  title: "Retrospective — <root-item-title> — <YYYY-MM-DD>",
  summary: "<one-sentence summary of key findings>",
  tags: "session-retrospective",
  parentId: "<container-uuid>"
}])
```

### 5c. Fill schema notes

All three notes are queue-role. Fill them in a single batch:

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

### 5d. Advance to terminal

The retrospective is a write-once artifact — all notes are queue-phase, so after filling them there are no further gates. Complete directly:

```
complete_tree(itemIds=["<retro-uuid>"], trigger="complete")
```

---

## Step 6 — Update Trend Memory

**Skip entirely in dry-run mode.**

Read `memory/retrospectives.md` from the auto memory directory. Update each section:

- **Schema Effectiveness:** Add or update entries from dimension 3a findings. Format: `- <schema-note-key>: <observation>. Sessions: N. Last seen: YYYY-MM-DD`
- **Delegation Patterns:** Add or update from dimension 3b. Format: `- <pattern>. Sessions: N. Last seen: YYYY-MM-DD`
- **Note Quality:** Add or update from dimension 3c. Format: `- <note-key>: <observation>. Sessions: N. Last seen: YYYY-MM-DD`
- **Improvement Proposals:** Add new proposal references if created in step 7.

Write the updated file.

---

## Step 7 — Create Improvement Proposals

**Skip entirely in dry-run mode.**

Check the updated trend memory (from step 6). For each trend with **Sessions >= 2**:

### 7a. Find or create proposals container

```
query_items(operation="search", query="Improvement Proposals", depth=0, limit=5)
```

Create if missing (same pattern as 5a).

### 7b. Create proposal items

For each graduating trend:

```
manage_items(operation="create", items=[{
  title: "Proposal: <concrete change description>",
  summary: "<what to change and why — reference retrospective item IDs that surfaced the trend>",
  tags: "improvement-proposal",
  parentId: "<proposals-container-uuid>",
  priority: "low"
}])
```

The proposal should include a **concrete suggestion** — not just "this is a problem" but the specific change:
- Schema edits: include the exact YAML to add/modify
- Skill updates: reference the section and describe the change
- Output style adjustments: specify the zone and content
- Hook additions: specify the event, matcher, and purpose

---

## Step 8 — Meta-Evaluation

**Skip entirely in dry-run mode.**

Query prior retrospectives:

```
query_items(operation="search", tags="session-retrospective", limit=20)
```

**If 3+ retrospectives exist**, evaluate:

1. **Trend durability:** Did previously identified trends get addressed? Check if improvement proposals in terminal state exist for prior trends.
2. **Proposal staleness:** Any proposals created 3+ retrospectives ago with no movement (still in queue)?
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

| ID | Proposal | Trigger |
|----|----------|---------|
| `<short-id>` | <description> | <trend that graduated> |
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

**Trend memory file missing**
- Cause: First retrospective ever run
- Solution: Skill creates the file at step 6 with initial structure. No action needed.

**Container not found**
- Cause: First time creating retrospectives or improvement proposals
- Solution: Skill creates containers lazily at steps 5a and 7a. No action needed.
