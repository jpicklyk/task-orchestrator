---
name: session-retrospective
description: "Analyze the current implementation run — evaluate schema effectiveness, delegation alignment, note quality, plan-to-execution fit. Captures cross-session trends and proposes improvements when patterns repeat. Use after implementation runs, or when user says 'retrospective', 'session review', 'what did we learn', 'analyze this run'."
argument-hint: "[optional: --dry-run to preview without creating items]"
---

# Session Retrospective

Structured post-implementation analysis. Evaluates the current run across five dimensions, persists findings in MCP, and maintains cross-session trend memory to surface actionable improvement proposals.

---

## Step 0 — Mode Check

If `$ARGUMENTS` contains `--dry-run`, set **DRY_RUN = true**. In dry-run mode, perform steps 1-4 and render the report (step 9) but skip steps 5-8 (no MCP item creation, no memory updates). Announce at the top of the report: `**Dry run** — no items created, no memory updated.`

---

## Step 1 — Load the Manifest

Search for the run manifest session task:

```
TaskList — scan for tasks named `run-manifest:*`
TaskGet — read the most recent one
```

Parse the JSON manifest. Extract: `runId`, `scope`, `delegations`, `schemaInteractions`, `observations`, `friction`, `extensions`.

**If no manifest exists (fallback):** Query MCP for recently active items:

```
get_context() — active, blocked, stalled items
query_items(operation="search", role="terminal", limit=50) — recently completed items
```

Build a synthetic scope from items that appear to have been touched in this session. Note in the report: `**Fallback mode** — no run manifest found. Analysis based on MCP state queries.`

---

## Step 2 — Gather Retrospective Data

Run these calls in parallel:

1. For each item in `scope.preExistingItems` + `scope.adHocItems` (up to 20):
   ```
   query_notes(itemId="<uuid>", includeBody=true)
   ```
2. `get_context()` — current state snapshot

Cross-reference:
- Match `delegations[].itemId` to items — verify outcomes
- Match `schemaInteractions[].itemId` to notes — evaluate content quality
- Identify items in scope with no delegation entry → self-implemented
- Identify items with delegations but no notes → incomplete work

---

## Step 3 — Evaluate Across Dimensions

### 3a. Schema Effectiveness

For each `schemaInteraction` entry:
- Was `guidanceFollowed: true` for all notes?
- Token count per note: **<50 = sparse** (flag), **50-500 = appropriate**, **>500 = potentially verbose** (flag for status-type notes; specification notes are exempt from the upper bound)
- Were gate failures recorded? What caused them?
- **Score:** Fraction of notes where guidance was followed and content was appropriately sized

### 3b. Delegation Alignment

Cross-reference `delegations[]` against the output style delegation table:

| Task type | Expected model |
|-----------|---------------|
| MCP bulk ops, materialization, simple queries | `haiku` |
| Code reading, implementation, test writing | `sonnet` |
| Architecture, complex tradeoffs, multi-file synthesis | `opus` |

- Flag misalignments (e.g., opus for bulk MCP ops, haiku for architecture)
- Flag `selfImplemented: true` entries — the orchestrator should delegate, not implement directly
- **Score:** Fraction of delegations matching expected model for their rationale

### 3c. Note Effectiveness

For items with both queue-phase notes (specs) and work-phase notes (implementation):
- Compare spec content themes to implementation note themes
- If implementation notes mention "deviated from spec", "unexpected", or "assumption was wrong" → flag as a spec gap
- If work-phase notes are nearly empty (<30 tokens) → flag as context loss for downstream agents
- **Score:** Qualitative (effective / mixed / ineffective)

### 3d. Plan-to-Execution Alignment

From `scope`:
- `adHocItems` not in `preExistingItems` = scope change (may be necessary or scope creep)
- Items in `preExistingItems` still in queue role = planned but skipped
- **Score:** Fraction of planned items that reached terminal

### 3e. Friction Synthesis

Group `friction[]` entries and `observations[]` by type:
- `tool-error` — MCP or tool failures
- `excessive-roundtrips` — more calls than necessary
- `workaround` — agent had to work around a limitation
- `api-confusion` — unclear API semantics

Identify themes across entries (e.g., "3 friction entries related to gate failures on items without schemas").

---

## Step 4 — Check Trend Memory

Read the auto memory file at `C:/Users/jeff_/.claude/projects/D--Projects-task-orchestrator/memory/retrospectives.md` (file read, not MCP call).

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
    body: "<Step 3 quantitative data: item counts, delegation breakdown, schema usage, token estimates>"
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
    body: "<Step 4 trend analysis: new trends, reinforced trends, proposals, extension promotions>"
  }
])
```

### 5d. Advance to terminal

The retrospective is a write-once artifact. Advance through all gates to terminal:

```
advance_item(transitions=[{itemId: "<retro-uuid>", trigger: "start"}])
```

Then:

```
complete_tree(itemIds=["<retro-uuid>"], trigger="complete")
```

---

## Step 6 — Update Trend Memory

**Skip entirely in dry-run mode.**

Read `C:/Users/jeff_/.claude/projects/D--Projects-task-orchestrator/memory/retrospectives.md`. Update each section:

- **Schema Effectiveness:** Add or update entries from dimension 3a findings. Format: `- <schema-note-key>: <observation>. Sessions: N. Last seen: YYYY-MM-DD`
- **Delegation Patterns:** Add or update from dimension 3b. Format: `- <pattern>. Sessions: N. Last seen: YYYY-MM-DD`
- **Note Quality:** Add or update from dimension 3c. Format: `- <note-key>: <observation>. Sessions: N. Last seen: YYYY-MM-DD`
- **Extension Candidates:** Add entries from `manifest.extensions[]` that appeared in this run. Format: `- <key>: <value pattern>. Sessions: N. Promoted: no`
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

### 7c. Extension promotions

If any `extensions[]` keys from the trend memory have `Sessions >= 2`:
- Create a proposal item with the proposed JSON schema addition (field name, type, enum values if applicable)
- Note which runs used the extension and what values they recorded

Update the Extension Candidates section in trend memory: set `Promoted: yes` for graduated entries.

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
## ◆ Session Retrospective — <root-item-title>

**<YYYY-MM-DD> · <N> items · <N> delegations · <N> schemas used**

### Dimension Scores

| Dimension | Score | Key Finding |
|-----------|-------|-------------|
| Schema effectiveness | <fraction or qualitative> | <one-line summary> |
| Delegation alignment | <fraction> | <one-line summary> |
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

### Extension Promotions

| Key | Value Pattern | Sessions | Recommendation |
|-----|---------------|----------|----------------|
| <key> | <typical value> | N | promote to core / keep observing |
```

**Conditional prefixes:**
- Dry-run: `**Dry run** — no items created, no memory updated.`
- Fallback: `**Fallback mode** — no run manifest found. Analysis based on MCP state queries.`

Omit sections with no data (e.g., no extension promotions → omit that table).

---

## Troubleshooting

**No manifest found**
- Cause: Implementation run did not maintain a run manifest (output style not active, or short session)
- Solution: Skill falls back to MCP queries. Less precise but functional. The report notes fallback mode.

**Schema not recognized (`expectedNotes` empty)**
- Cause: `session-retrospective` tag not in `.taskorchestrator/config.yaml`, or MCP not reconnected after config edit
- Solution: Run `/mcp` to reconnect, verify config has the schema

**Trend memory file missing**
- Cause: First retrospective ever run
- Solution: Skill creates the file at step 6 with initial structure. No action needed.

**Container not found**
- Cause: First time creating retrospectives or improvement proposals
- Solution: Skill creates containers lazily at steps 5a and 7a. No action needed.
