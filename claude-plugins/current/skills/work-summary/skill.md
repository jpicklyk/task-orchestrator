---
name: work-summary
description: Intelligent project dashboard — active work, blockers, next actions, and agent analysis of project health
---

# Work Summary — Current (v3)

Generate an insight-driven project dashboard. The goal is not to display data — it is to **interpret** it. Surface patterns, anomalies, and actionable signals that a plain table cannot. Think like a project manager reading between the lines.

## Data Collection (3 calls, run in parallel)

1. `query_items(operation="overview")` — all root items with childCounts per role (container health)
2. `get_context(includeAncestors=true)` — active (work/review), blocked, and stalled items with full ancestor chains
3. `get_next_item(limit=3, includeAncestors=true)` — priority-ranked actionable items

These three calls return non-overlapping data. No additional traversal or parent-walk calls are needed — ancestor chains are embedded on each item from calls 2 and 3.

---

## Analysis Phase

Before writing anything, analyze the collected data and form observations. This is the step that separates a useful summary from a table dump.

### Root Item Classification

Classify every root item from the overview before rendering:

| Pattern | Classification |
|---------|---------------|
| `childCounts` total > 0, any non-terminal children | **Active container** — feature bucket, bug tracker, sprint list, etc. |
| `childCounts` total > 0, all children terminal | **Completed container** — goes in Done footer |
| All `childCounts` = 0, role non-terminal | **Empty container** — planning gap, no work recorded |
| All `childCounts` = 0, role terminal | **Completed standalone item** — goes in Done footer as standalone |
| Non-zero children, root item itself is terminal | **Structural anomaly** — root closed but descendants may still be active |

Note: the overview `childCounts` reflects **direct children only**, not all descendants. Active grandchildren can exist under a terminal root. `get_context` will surface these — cross-reference both.

### Signals to Look For

**Active Work:**
- All active items share the same parent → work is single-threaded, no parallelization happening
- Active items exist under a root container that is itself marked terminal → container may have been closed prematurely
- Items in `review` role → waiting on validation, not just implementation
- Large number of items in flight → capacity signal

**Blocked / Stalled:**
- A blocked item whose blocker does NOT appear in active work → the block may not be getting attention; resolution isn't visible
- Stalled items reveal their problem through which note is missing: `requirements` missing = was never properly scoped; `done-criteria` missing = implementation may be done but not verified
- Blocked items inside containers with large queue counts → a single block is holding up a whole workstream

**Up Next:**
- All recommendations from the same container → limited opportunity to parallelize
- Only low-priority items recommended → high-priority work is either done or stuck
- No recommendations at all → everything is blocked or already in flight

**Container Health:**
- High completion ratio (e.g., 7/8 done) → momentum, close to wrapping up
- Large queue, nothing active or terminal → container exists but work hasn't started
- Container has active children but the root item itself has never been transitioned → root may need a `start` trigger

**Empty Containers:**
These are planning gaps. They signal areas where work is expected but none has been created. Call them out clearly — they are not the same as completed work.

---

## Dashboard Format

```
## ◆ Work Summary

[One sentence: direct assessment of overall project health and momentum. Examples:
 "Project is moving — 3 items in flight across 2 features with clear next actions."
 "Work is stalled — all active items share a single parent and one item is blocking the rest."
 "Clean slate — nothing in progress yet, but 5 high-priority items are ready to start."]

**X active · Y blocked · Z stalled**

---

### ◉ Active Work

| Item | Path | Role |
|------|------|------|
| <title> | <ancestor titles joined with ›, or — if root> | work or review |

[Agent observation — 1–3 sentences. Note bottlenecks, anomalies, structural issues.
 If nothing notable: "Work is distributed and progressing normally." or omit.]

---

### ⊘ Needs Attention       ← omit entire section if no blocked or stalled items

| Item | Path | Issue |
|------|------|-------|
| <title> | <path> | Blocked / Stalled: <missing note keys if stalled> |

[Agent observation — what's causing this, whether the blocker is itself making progress,
 urgency level, and what would resolve it.]

---

### ▸ Up Next               ← omit if get_next_item returns nothing

| Item | Path | Priority |
|------|------|---------|
| <title> | <path> | high / medium / low |

[Agent observation — ordering advice, parallelization opportunities, or concerns.
 If only one priority level is represented: call that out.]

---

### ▸ Container Health      ← only containers with open work (queue > 0 OR work > 0 OR review > 0)

| Container | ○ Queue | ◉ Active | ✓ Done | Signal |
|-----------|---------|----------|--------|--------|
| <title>   | N       | N        | N      | <label> |

Signal labels: "In flight" · "Not started" · "Almost done (N/M)" · "Stalled" · "Blocked" · "Just started"

[Agent observation — highlight momentum, containers close to completion, and containers
 that have large queues but no demonstrated progress. Call out anything unexpected.]

---

**Empty (no tasks recorded):** <titles of root items with zero children in any role>
[Brief note if relevant — e.g., "These may represent planned backlog areas with no tasks yet."]

**Done (N):** <named terminal containers listed by title> · <(+N standalone) if any>
```

---

## Formatting Rules

**Collapse active/blocked children:** If an item's `ancestors` contains the ID of any other item also in `activeItems` (or `blockedItems`), omit the child — the parent row implies active descendants. Show the highest-level item only.

**Path:** Join `ancestors` titles with ` › `. If ancestors is empty (item is at root), path = `—`.

**Done footer:**
- Named = terminal root items with ≥1 child (any role): list by title
- Standalone = terminal root items with 0 children: collapse to `(+N standalone)`
- N = total count of all terminal root items
- Omit line entirely if no terminal root items exist

**Empty line:** Root items where ALL childCounts are zero. Omit if none.

**Use `—`** for zero counts in tables, not `0`.

**Status symbols:** ✓ terminal · ◉ work/review · ○ queue · ⊘ blocked/stalled

**Agent observations:** Write them only when there is something genuinely worth surfacing. A healthy, unremarkable section needs at most one sentence of confirmation. Do not manufacture observations to fill space — silence is better than noise.
