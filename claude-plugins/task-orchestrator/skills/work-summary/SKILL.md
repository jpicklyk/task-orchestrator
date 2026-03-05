---
name: work-summary
description: Generate a hierarchical project dashboard showing all work items organized by container, with IDs, tags, status, and priority visible. Always use this skill for any request about project status, work summaries, or item overviews — never construct dashboards manually with raw MCP calls. Trigger on any of these phrases or intent: "project status", "what's active", "show me the dashboard", "work summary", "summary", "what should I work on", "project health", "what's blocked", "where did I leave off", "show items", "what's in the backlog", "overview", or any request to see or review the current state of work items. This includes session-start context gathering — if you need to understand current project state, use this skill.
argument-hint: "[optional: container UUID or title to scope the summary]"
---

# Work Summary — Current (v3)

Generate a PM-ready project dashboard. The goal is to show the full project state in one view — every container, every item, with enough detail (IDs, tags, priority, status) to make decisions and take action. Supplement the data with brief observations only when there is a genuine anomaly or actionable insight.

---

## Step 0 — Scope Check

If `$ARGUMENTS` is provided:
- If it looks like a UUID, use it directly as `parentId` for all queries below
- If it's text, search: `query_items(operation="search", query="<text>")` — pick the best-matching root or container item and use its UUID as `parentId`
- If multiple matches, pick the closest title match; if ambiguous, use `AskUserQuestion` to clarify

When scoped to a `parentId`, modify the data collection calls:
1. `query_items(operation="overview", itemId="<parentId>")` — scoped overview of that subtree
2. `query_items(operation="search", parentId="<parentId>", limit=200)` — scoped item details
3. `get_context(includeAncestors=true)` — still global (filter to scope in analysis phase)
4. `get_next_item(limit=5, includeAncestors=true, parentId="<parentId>")` — scoped recommendations

If no `$ARGUMENTS`, proceed with the global (unscoped) data collection as written below.

## Data Collection (4 calls, run in parallel)

1. `query_items(operation="overview", includeChildren=true)` — all root items with childCounts per role and direct children
2. `query_items(operation="search", limit=200)` — all items with full fields: `id, parentId, title, role, priority, depth, tags`
3. `get_context(includeAncestors=true)` — active (work/review), blocked, and stalled items
4. `get_next_item(limit=5, includeAncestors=true)` — dependency-aware ranked recommendations

**Why 4 calls:** Overview gives hierarchy structure and child counts. Search gives tags and priority for every item (overview children lack these fields). get_context gives active/blocked/stalled signals. get_next_item gives dependency-aware recommendations that search cannot provide.

---

## Enrichment Phase

Before rendering, cross-reference the data sources:

1. **Build a search lookup map:** `searchMap[id] = { priority, tags, role }` from the search results
2. **Build child count map:** Group search results by `parentId`. For each parent, count children by role: `childRoleCounts[parentId] = { queue: N, work: N, review: N, blocked: N, terminal: N }`. This powers the Children column in tables.
3. **Enrich overview children:** For each child in the overview tree, look up its `priority`, `tags`, and child role counts from the maps
4. **Build active/blocked/stalled sets:** From get_context, create sets of item IDs that are active, blocked, or stalled
5. **Classify root items:** Use the classification table below
6. **Group standalone items by tag:** For root items classified as "Standalone item", group by their `tags` value (first tag if multiple). Items with no tag go into an "Uncategorized" group.

### Root Item Classification

| Pattern | Classification | Rendering |
|---------|---------------|-----------|
| Has non-terminal children | **Active container** | Full section with children table |
| All children terminal | **Completed container** | Done footer |
| No children, non-terminal role | **Standalone item** | Standalone Items table |
| No children, terminal role | **Completed standalone** | Done footer |

> **Note:** Overview `childCounts` reflects **direct children only**. Active grandchildren can exist under a terminal root. Cross-reference with `get_context` results.

---

## Dashboard Format

Render the dashboard in this exact section order. Omit any section that has no data.

### Section 1: Health Headline

```
## ◆ Work Summary

[One sentence assessing project health and momentum.]

**X active · Y blocked · Z stalled · W queued · V done**
```

Counts come from the search results grouped by role. "active" = work + review roles. "done" = terminal role.

---

### Section 2: Attention Required

Omit this entire section if there are no blocked or stalled items.

```
### ⊘ Attention Required

| ID | Item | Container | Issue |
|----|------|-----------|-------|
| `short-id` | <title> | <parent title or —> | Blocked by: `<blocker-id>` <blocker-title> |
| `short-id` | <title> | <parent title or —> | Stalled: missing `<note-key>`, `<note-key>` |
```

For blocked items, show what is blocking them. For stalled items, show which required notes are missing.

If there is actionable context (e.g., blocker is not in active work, or a stalled item has a `guidancePointer`), add a brief observation line below the table — one sentence max.

Include the short ID so the user can reference items in follow-up commands.

---

### Section 3: Project Inventory

This is the core of the dashboard. Show every active container with its children.

For each active container (has non-terminal children), render:

```
#### <Container Title> `<8-char-id>`
<role-symbol> <role> · <N open> · <N done>

| ID | Title | Status | Pri | Tags | Children |
|----|-------|--------|-----|------|----------|
| `xxxxxxxx` | <child title> | ◉ work | high | feature-task | — |
| `yyyyyyyy` | <child title> | ○ queue | med | — | 2○ 1◉ |
| `zzzzzzzz` | <child title> | ⊘ blocked | high | bug | — |

✓ N completed: <comma-separated titles of terminal children>
```

**Rendering rules for container sections:**
- Sort children: ◉ active (work/review) first, then ⊘ blocked, then ○ queue, then ✓ terminal
- Non-terminal children get full table rows with all columns
- Terminal children are collapsed into the `✓ N completed` line below the table. If 0 completed, omit the line. If more than 5 completed, show first 3 titles then `(+N more)`.
- The container header shows the short ID for user reference
- `<N open>` = queue + work + review + blocked children count
- **Children column:** If an item itself has children (detected from search results where other items have this item's ID as `parentId`), show a compact role summary using the format `N○ N◉ N⊘ N✓` (omit roles with zero count). If the item has no children, show `—`.
- Tags column: show tag value or `—` if none
- Priority column: show `high`, `med`, `low`, or `—` if default/unset

**If a container itself is in work/review role**, prefix its header with the role symbol: `#### ◉ Tech Debt \`89d02e32\``

---

### Section 4: Standalone Items

Root items (depth 0) that have no children and are non-terminal. Omit section if none.

**Grouping strategy — adaptive, hierarchy-first:**

Items with a `parentId` are always shown under their container in Section 3 — hierarchy wins over tags. Standalone items (no parent) are grouped by tag in this section. This adapts to how the user organizes work:

- **Hierarchy users** (containers with children): most items appear in Section 3, few standalones here
- **Tag users** (flat items with schema tags, no containers): Section 3 is empty, this section becomes the primary view with tag-based groupings
- **Mixed users**: containers in Section 3, orphaned tagged items grouped here by tag

**Rendering rules:**

1. Collect all non-terminal root items with no children
2. Group them by tag value. Items with multiple tags: use the first tag. Items with no tag: group under "Uncategorized"
3. If only one tag group exists (or all items are uncategorized), render as a flat table without tag subheadings
4. If multiple tag groups exist, render each as a subheading with its own table

**Multi-group format:**
```
### Standalone Items

#### feature-implementation

| ID | Title | Status | Pri | Children |
|----|-------|--------|-----|----------|
| `xxxxxxxx` | <title> | ○ queue | med | — |

#### bug-fix

| ID | Title | Status | Pri | Children |
|----|-------|--------|-----|----------|
| `yyyyyyyy` | <title> | ○ queue | high | — |

#### Uncategorized

| ID | Title | Status | Pri | Children |
|----|-------|--------|-----|----------|
| `zzzzzzzz` | <title> | ○ queue | med | — |
```

**Single-group format** (all same tag or all uncategorized):
```
### Standalone Items

| ID | Title | Status | Pri | Tags | Children |
|----|-------|--------|-----|------|----------|
| `xxxxxxxx` | <title> | ○ queue | med | — | — |
```

Note: when items are grouped by tag, the Tags column is omitted from the table (the tag subheading already conveys it). When rendered as a flat table, include the Tags column.

---

### Section 5: Recommended Next

From `get_next_item` results. Omit if no recommendations.

```
### ▸ Recommended Next

| ID | Title | Container | Pri |
|----|-------|-----------|-----|
| `xxxxxxxx` | <title> | <parent title or —> | high |
```

These items are queue-role, not blocked by dependencies, ranked by priority then complexity. Brief observation (one sentence) only if there's a parallelization opportunity or if all recommendations come from the same container.

---

### Section 6: Done

Compact footer for completed work. Omit if nothing is terminal.

```
### ✓ Done (N total)

**Completed containers:** <title> (N children) · <title> (N children)
**Completed standalone:** <title> · <title> · (+N more if >5)
```

N total = count of all terminal root items (containers + standalone).

---

### Internal: Short ID → Full UUID Mapping

Do NOT render a UUID reference table in the dashboard output. The user references items by short ID only.

Instead, retain the short→full UUID mapping as internal agent context. When the user references a short ID in a follow-up command (e.g., "start `0499a6aa`"), resolve it to the full UUID silently for the MCP tool call. The search results from data collection provide all the full UUIDs needed.

---

## Formatting Conventions

**Status symbols:** `✓` terminal · `◉` work/review · `⊘` blocked/stalled · `○` queue

**Short IDs:** First 8 characters of the UUID, rendered in backticks: `` `af21ed9a` ``

**Use `—`** for empty/null values, not `0` or blank.

**Priority abbreviations:** `high`, `med`, `low` in tables.

**Observations:** Write them sparingly — only when there is a genuine anomaly, bottleneck, or actionable insight. A healthy project needs zero observations. Do not fill space with "work is progressing normally" — the data speaks for itself.

**Empty containers:** Root items where ALL childCounts are zero and role is non-terminal. If any exist, note them at the end of the inventory section: `**Empty (no items):** <title>, <title>`
