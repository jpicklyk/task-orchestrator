---
name: project-summary
description: Display a hierarchical summary of the current v3 work tree with active items, gate status, and recent activity
---

# Project Summary — Current (v3)

Generate a dashboard for the current v3 work tree.

## Steps

1. Call `query_items(operation="overview")` to get root items and their child counts by role
2. Call `get_context()` (health check mode) to surface stalled and blocked items
3. Format a dashboard with:
   - Root items table: id, title, role, child counts (queue/work/review/terminal)
   - Active items (role=work or review)
   - Stalled items (active but missing required notes)
   - Blocked items

## Dashboard Format

```
## ◆ Work Tree — Health Check

| ID | Item | Role | ○ | ◉ | ✓ |
|----|------|------|---|---|---|
| `abcd1234` | Feature X | work | 3 | 1 | 2 |

**Active:** N items in work/review
**Stalled:** N items with missing required notes
**Blocked:** N items waiting on dependencies
```

Status symbols: ✓ terminal · ◉ work/review · ○ queue · ⊘ blocked
