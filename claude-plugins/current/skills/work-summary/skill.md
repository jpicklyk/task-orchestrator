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
| Add jokes.json | Features › Joke Telling Service | work |

### ○ Pending Work
| Container | ○ | ◉ | ✓ |
|-----------|---|---|---|
| `97050f82` Features | 1 | 1 | — |
| `b27fe02a` Observations | 1 | — | — |

**Empty:** Bugs · Tech Debt · Action Items
**Done (2):** v3 Smoke Test · V3 Cascade Refactor
```

## Formatting Rules

**In Progress section:**
- Include any item with role=work or role=review
- Each active item from `get_context(includeAncestors=true)` includes an `ancestors` array: `[{id, title, depth}, ...]` ordered root → direct parent
- Build the breadcrumb path by joining ancestor titles with ` › ` separator
- Example: if item has `ancestors: [{title:"Features"}, {title:"Auth System"}]`, path = `Features › Auth System`
- If ancestors is empty (root item), path = `—`
- If nothing is in progress, show: `_Nothing in progress._`

**Pending Work section:**
- Only include root containers that have at least one child (any role)
- Sort: containers with active (work/review) children first, then queue-only
- Omit this section entirely if all containers are empty

**Stalled section** (only if stalledItems is non-empty):
```
### ⊘ Stalled
| Item | Path | Missing |
|------|------|---------|
| <title> | <breadcrumb> | <missing note keys> |
```

**Blocked section** (only if blockedItems is non-empty):
```
### ⊘ Blocked
| Item | Path |
|------|------|
| <title> | <breadcrumb> |
```

**Footer lines:**
- `**Empty:**` — comma/dot separated list of root containers with zero children; omit line if none
- `**Done (N):**` — comma/dot separated list of root items in terminal role; omit line if none

**General:**
- Use `—` (em dash) for zero counts in tables, not `0`
- Status symbols: ✓ terminal · ◉ work/review · ○ queue · ⊘ blocked/stalled
- Short IDs are the first 8 characters of the UUID
