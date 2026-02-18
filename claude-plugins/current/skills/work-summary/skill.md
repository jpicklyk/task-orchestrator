---
name: work-summary
description: Display an activity-first dashboard grouped by state — active work, pending containers, empty, and done
---

# Work Summary — Current (v3)

Generate an activity-first dashboard. Lead with what's in flight, group containers by activity state, suppress noise.

## Steps

1. Call `query_items(operation="overview")` — gets all root items with child counts by role
2. Call `get_context(includeAncestors=true)` — surfaces active, stalled, and blocked items; each item includes an `ancestors` array with the full parent chain
3. Format the dashboard using the Activity-First layout below — ancestor data is available directly on each item from Step 2, no follow-up traversal needed

## Activity-First Layout

```
## ◆ Work Summary

### ◉ In Progress
| Item | Path | Role |
|------|------|------|
| Auth System | Features | work |

### ○ Pending Work
| Container | ○ | ◉ | ✓ |
|-----------|---|---|---|
| `97050f82` Features | 3 | 1 | 2 |

**Empty:** Bugs · Tech Debt · Action Items
**Done (10):** v3 Smoke Test · V3 Cascade Refactor (+8 standalone)
```

## Formatting Rules

**In Progress section:**
- Include any item with role=work or role=review from `activeItems`
- **Collapse children of active parents:** if an item's `ancestors` array contains the ID of any other item also in `activeItems`, omit that child from the table — the parent's entry implies it has active descendants
- Each shown item's path = join its `ancestors` titles with ` › `. If ancestors is empty, path = `—`
- If nothing is in progress after collapse, show: `_Nothing in progress._`

**Pending Work section:**
- Only include root containers that have at least one **non-terminal** child: `childCounts.queue > 0 OR childCounts.work > 0 OR childCounts.review > 0`
- Containers whose children are all terminal are done — exclude them from this section
- Sort: containers with active (work/review) children first, then queue-only
- Omit this section entirely if no containers qualify

**Stalled section** (only if stalledItems is non-empty):
```
### ⊘ Stalled
| Item | Path | Missing |
|------|------|---------|
| <title> | <breadcrumb> | <missing note keys> |
```

**Blocked section** (only if blockedItems is non-empty):
- Apply the same parent-collapse rule as In Progress: if a blocked item's `ancestors` contains the ID of any item in `activeItems` or `blockedItems`, omit that child
```
### ⊘ Blocked
| Item | Path |
|------|------|
| <title> | <breadcrumb> |
```

**Footer lines:**

`**Empty:**` — root items with zero children of any role; omit line if none

`**Done:**` — two tiers on one line:
- **Named containers** = terminal root items with ≥1 child: list by title separated by ` · `
- **Standalone items** = terminal root items with 0 children: collapse to `(+N standalone)`
- Format examples:
  - Named only: `**Done (3):** v3 Smoke Test · V3 Cascade Refactor · Observations`
  - Both: `**Done (10):** v3 Smoke Test · V3 Cascade Refactor (+8 standalone)`
  - Standalone only: `**Done (8):** (+8 standalone)`
- N = total count of all terminal root items; omit line entirely if none

**General:**
- Use `—` (em dash) for zero counts in tables, not `0`
- Status symbols: ✓ terminal · ◉ work/review · ○ queue · ⊘ blocked/stalled
- Short IDs are the first 8 characters of the UUID
