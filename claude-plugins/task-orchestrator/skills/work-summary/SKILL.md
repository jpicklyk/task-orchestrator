---
name: work-summary
description: "Generates a project dashboard from MCP work items. Default is a lean, attention-first view: what's in flight, what's blocked, what to do next, what's queued. Use when the user says: project status, what's active, show me the dashboard, work summary, what should I work on, project health, what's blocked, where did I leave off, show items, what's in the backlog, overview, or any request to see or review the current state of work items. Also use at session start when gathering current project context."
argument-hint: "[full | container UUID or title to scope the summary]"
---

# Work Summary — Current (v3)

Attention-first project dashboard. The default (lean) mode answers four questions — what's moving, what's stuck, what's next, what's queued — in a bounded view (~45 lines) regardless of workspace size. The exhaustive inventory is available as an explicit mode, not the default: mature workspaces accumulate hundreds of terminal items, and rendering them all buries the actionable content.

Supplement the data with brief observations only when there is a genuine anomaly or actionable insight.

---

## Step 0 — Mode Selection

| `$ARGUMENTS` | Mode |
|--------------|------|
| empty | **Lean** (default) |
| the literal word `full` | **Full Inventory** |
| the literal word `all` | **Multi-Project** — one condensed section per known project root |
| anything else | **Scoped** — resolve to a UUID via `query_items(operation="search", query=$ARGUMENTS, limit=5)`; prefer the best-matching root or container item; if ambiguous, present matches via `AskUserQuestion` |

---

## Step 0.5 — Project Scope Resolution

Before running Lean, Full, or Multi-Project mode, resolve the project rootId:

- Check session context for a rootId injected by the SessionStart hook.
- Otherwise, read `.taskorchestrator/config.yaml`'s top-level `project.rootId` field (a file read, not an MCP call).
- **If no rootId is found by either path, behavior is exactly as before this feature existed** — every call below runs unscoped (whole-DB), and no `ancestorId` param is passed. This is the common case for single-project workspaces.

When a rootId is known, Lean and Full mode calls pass it as `ancestorId="<rootId>"` (noted inline below). Scoped mode is unaffected — it already resolves its own scope UUID per invocation via FTS.

---

## Lean Mode (default)

### Data collection (all parallel)

1. `query_items(operation="overview", includeChildren=true, excludeTerminal=true)` — non-terminal roots with their children arrays and childCounts. Add `ancestorId="<rootId>"` when a rootId is known, to anchor the overview to this project. **Fallback for older servers:** if `excludeTerminal` is rejected as an unknown parameter, call `query_items(operation="overview")` *without* `includeChildren` (still pass `ancestorId` if known), then fetch children only where needed: for each non-terminal root whose `childCounts` show non-terminal children, `query_items(operation="search", parentId="<id>")` in parallel. Never fetch children of terminal roots.
2. `get_context()` — health-check: active items, blocked items, stalled items, claim summary. Add `ancestorId="<rootId>"` when known.
3. `get_context(mode="session-resume", since="<now minus 48h, ISO 8601>")` — recent role transitions (the "where did I leave off" signal). Add `ancestorId="<rootId>"` when known — but note the **known limitation**: `recentTransitions` is NOT scoped by `ancestorId` even when passed; it always reflects the whole tree, not just this project.
4. `get_next_item(limit=5, includeDetails=true)` — ranked recommendations with parentId/tags. Add `ancestorId="<rootId>"` when known.
5. `query_items(operation="search", role="queue", limit=1)` — read `total` for the true queued count. Add `ancestorId="<rootId>"` when known.
6. `query_items(operation="search", role="terminal", limit=1)` — read `total` for the true done count. Add `ancestorId="<rootId>"` when known.

**Headline counts must come from these sources, never from eyeballing the overview payload** — overview covers roots + direct children only and may be truncated.

### Enrichment

- **Containers are shelves, not work.** Items with tag or `type` = `container` never count as active; exclude them from the active/In Flight lists.
- **Active** = health-check `activeItems` minus containers. **Blocked/stalled** = health-check lists as-is.
- **Workstreams** = non-terminal roots with children. Progress fraction = `terminal / total` from `childCounts`; "next up" = the highest-priority queue-role child from the children array.
- **Standalone queue roots** group by kind: the `type` field if set; else the first tag *not* in {`agent-observation`, `feature`, `container`}; else "Uncategorized".
- **Rollup threshold:** any group with more than 5 items renders as a count line broken down by kind, listing individual items only when priority ≥ medium or tagged `action-item`.
- **Hygiene candidates:** empty non-terminal containers; evident test artifacts (probe/smoke/depth-test titles, `mtest-*` tags).

### Dashboard format

Render in this order; omit any section with no data.

```
## ◆ Work Summary

[One sentence: health and momentum.]

**N active · N blocked · N stalled · N queued · N done**

### ◉ In Flight
| ID | Item | Container | Status | Pri |
|----|------|-----------|--------|-----|
| `xxxxxxxx` | <title> | <parent title or —> | ◉ work | high |

↳ Recent: <up to 3 one-line transition summaries from session-resume, newest first — omit line if none in 48h>

### ⊘ Attention Required
| ID | Item | Container | Issue |
|----|------|-----------|-------|
| `xxxxxxxx` | <title> | <parent or —> | Blocked by: `<blocker-id>` <blocker-title> |
| `yyyyyyyy` | <title> | <parent or —> | Stalled: missing `<note-key>` (trait: <trait-name> if applicable) |

### ▸ Recommended Next
| ID | Title | Container | Pri |
|----|-------|-----------|-----|

### ○ Backlog
**Workstreams**
| ID | Workstream | Progress | Next up | Pri |
|----|-----------|----------|---------|-----|
| `xxxxxxxx` | <root title> | 2/5 done | <queue child title> | high |

**<Kind>** (N): `id` <title> (pri) · `id` <title> (pri) · ...        ← groups of ≤5
**<Kind>** (N): X bug · Y optimization · Z friction — highest: `id` <title> (pri)   ← groups of >5

### ✓ Done — N terminal items

↳ Hygiene: <N> test artifacts, <N> empty containers — candidates for /batch-complete
```

**Lean-mode rules:**
- Total output target is ~45 lines; rollups are how you hold that budget.
- The Done section is a single line (count from the role-total query). No titles, no done/cancelled split in lean mode — the terminal statusLabels aren't fetched. Use `full` mode for the breakdown.
- The Hygiene line appears only when candidates exist.
- If all of In Flight / Attention / Recommended Next are empty, say so in the health sentence ("Quiet board — nothing in flight...") rather than rendering empty sections.

---

## Full Inventory Mode (`$ARGUMENTS` = `full`)

Everything lean mode shows, plus the complete per-container inventory.

### Data collection (all parallel)

1. `query_items(operation="overview", includeChildren=true)` — all roots. Add `ancestorId="<rootId>"` when a rootId is known, to anchor the roots list to this project. **If the response has `truncated: true`, re-issue with `limit=<total>`** so nothing is silently missing.
2. `get_context()` — health-check. Add `ancestorId="<rootId>"` when known.
3. `get_next_item(limit=5, includeDetails=true)`. Add `ancestorId="<rootId>"` when known.
4. Role-total queries as in lean mode (queue + terminal, `limit=1`, same `ancestorId` rule).

Per-container fetches within the inventory (below) are already `itemId`-scoped and need no change.

### Sections, in order

1. **Health Headline** — same as lean mode; counts from health-check + role-total queries, containers excluded from active.
2. **Attention Required** — same as lean mode.
3. **Recommended Next** — same as lean mode (deliberately above the inventory).
4. **Project Inventory** — for each **active container** (root with non-terminal children):

   ```
   #### <Container Title> `<8-char-id>`
   <role-symbol> <role> · <N open> · <N done>

   | ID | Title | Status | Pri | Tags | Children |
   |----|-------|--------|-----|------|----------|

   ✓ N completed: <first 3 titles> (+N more)
   ```

   - Sort children: ◉ active first, then ⊘ blocked, then ○ queue; terminal children collapse into the `✓ N completed` footer line (omit if 0; if >5, first 3 titles then `(+N more)`).
   - `<N open>` = queue + work + review + blocked children.
   - **Children column** = compact role summary `N○ N◉ N⊘ N✓` built from the child's own `childCounts` in the overview payload (omit zero roles; `—` if childless).
   - **Tags column** = tag value or `—`; traits append with `▸` prefix, `needs-` stripped (`feature-task ▸migration-review`).
   - If the container itself is work/review role, prefix its header with the role symbol.
5. **Standalone Items** — non-terminal childless roots, grouped by the shared grouping key (below). One tag group → flat table with Tags column; multiple groups → subheading per group, Tags column omitted.
6. **Done** — `### ✓ Done (N done · N cancelled)` with `**Completed containers:** <title> (N children) · ...` and `**Completed standalone:** <title> · ... (+N more if >5)`. **Cancelled items are counted and listed separately — never presented as completed work.**
7. **Empty containers** — `**Empty (no items):** <title>, <title>` at the end of the inventory if any exist.

---

## Scoped Mode (`$ARGUMENTS` = UUID or title)

Unchanged by project scoping — already resolves its own scope UUID per invocation, independent of any project rootId.

Use the resolved UUID as the scope for full-inventory-style rendering of one subtree:

1. `query_items(operation="overview", itemId="<parentId>")` — scoped overview.
2. `get_context()` — global; filter to the scope during enrichment.
3. `get_next_item(limit=5, parentId="<parentId>", includeDetails=true)` — scoped recommendations.

Render the Full Inventory sections for that subtree only. All shared rules apply.

---

## Multi-Project Mode (`$ARGUMENTS` = `all`)

Enumerate every known project anchor and render one condensed section per project — for workspaces where multiple project roots share the same database.

1. `query_items(operation="search", type="project", depth=0)` — list all depth-0 items typed `project` (the anchors created by `/quick-start`'s bootstrap flow or manually via `/manage-schemas`).
2. If none are found, say so and stop: "No project anchors found — run `/quick-start` to bootstrap one, or use `/work-summary` (no arguments) for the whole-DB view."
3. For each anchor found, in parallel:
   - `query_items(operation="overview", ancestorId="<anchor-id>", includeChildren=true, excludeTerminal=true)`
   - `get_context(ancestorId="<anchor-id>")`
   - `get_next_item(limit=3, includeDetails=true, ancestorId="<anchor-id>")`

Render, per project, with a clear per-project header:

```
### ◆ <Project Name> (`<8-char-id>`)
N active · N blocked · N queued

**Attention:** <up to 2 blocked/stalled items, or "none">
**Next up:** <top get_next_item result, or "nothing queued">
```

Keep each section condensed — this mode is a cross-project scan, not a per-project deep dive. Point the user at `/work-summary` (unscoped, or with that project's rootId once resolved) for the full dashboard on any one project.

---

## Shared Rendering Rules

**Status symbols:** `✓` terminal · `◉` work/review · `⊘` blocked/stalled · `○` queue

**Short IDs:** first 8 chars of the UUID in backticks: `` `af21ed9a` ``. Use `—` for empty values, not `0` or blank. Priority renders as `high` / `med` / `low`.

**Grouping key** (standalone items, all modes): `type` field if set; else first tag not in {`agent-observation`, `feature`, `container`}; else "Uncategorized". The generic tag is never the group — the specific one is.

**Containers** (tag or type `container`) are never counted as active work in any headline or In Flight list, regardless of their role.

**statusLabel:** the item's *role* drives the status symbol. Print the statusLabel text only when the item is non-terminal AND the label differs from the role's default. Never print statusLabels on terminal items — stale `in-progress` labels on terminal items are a known artifact (bug `100da214`).

**Cancelled ≠ done:** wherever terminal items are broken down, count `statusLabel: "cancelled"` separately.

**Truncation:** if any data-collection response had `truncated: true` that you could not resolve by re-fetching, end the dashboard with `⚠ showing X of Y root items` — never silently drop data.

**Observations:** write them sparingly — only for a genuine anomaly, bottleneck, or actionable insight. A healthy project needs zero observations.

### Internal: Short ID → Full UUID Mapping

Do NOT render a UUID reference table. Retain the short→full UUID mapping as internal context; when the user references a short ID in a follow-up (e.g., "start `0499a6aa`"), resolve it silently for the MCP call from the data already collected.
