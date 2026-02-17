---
name: work-summary
description: Display an activity-first dashboard grouped by state — active work, pending containers, empty, and done
---

# Work Summary — Current (v3)

Generate an activity-first dashboard. Lead with what's in flight, group containers by activity state, suppress noise.

## Steps

1. Call `query_items(operation="overview")` — gets all root items with child counts by role
2. Call `get_context()` — surfaces active, stalled, and blocked items
3. For each active item from get_context, build its breadcrumb path:
   - Call `query_items(operation="get", id=<item-id>)` to retrieve `parentId` and `depth`
   - Repeat for each parent until reaching a root item (`depth=0` or no `parentId`)
   - Assemble the path as `Container › Parent › ... › Item` using › as separator
4. Format the dashboard using the Activity-First layout below

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
- Path column shows the full breadcrumb from root container down to the item's parent (not the item itself)
- If the item is a root item (depth=0), Path column shows `—`
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
