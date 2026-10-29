---
name: work-summary
description: "Generates a project dashboard from MCP work items. Default is a lean, attention-first view: what's in flight, what's blocked, what to do next, what's queued. Use when the user says: project status, what's active, show me the dashboard, work summary, what should I work on, project health, what's blocked, where did I leave off, show items, what's in the backlog, overview, or any request to see or review the current state of work items. Also use at session start when gathering current project context."
argument-hint: "[full | all | container UUID or title to scope the summary]"
---

# Work Summary

Attention-first project dashboard. The default (lean) mode answers four questions - what's moving, what's stuck, what's next, what's queued - in a bounded view (~45 lines) regardless of workspace size. The exhaustive inventory is an explicit mode: mature workspaces accumulate hundreds of terminal items, and rendering them all buries the actionable content.

Every call below is a 4.0 tool call. Shapes are in [`../_generated/call-shapes.md`](../_generated/call-shapes.md) and are not restated here. Every answer is the response envelope: read `ok`, then `data`; on `ok: false` read `error.code` (an unknown field is `unknown_parameter`, so a typo fails loudly rather than being ignored). All calls here are reads, so there is no per-element write envelope to check.

Supplement the data with brief observations only for a genuine anomaly or actionable insight.

## Director mode

Director mode is the session state entered by `/task-orchestrator:director` (or by running as the hardened `director` agent); its guard denies the file read of Step 0.5 and most of the reads below. Runs, decisions and blocked work belong to the director board: re-invoke `/task-orchestrator:director` for them. For a backlog view in director mode run only these reads: Lean step 1 (`overview`), steps 5 and 6 (`list`), In Flight from `items_read` `list` with `role` `work` and again with `review`, `items_read` `blocked`, and the Runs reads. Skip the health `context` read, `history`, `next`, the Blocked-item decisions `context` reads, a scoped `search` and the Step 0.5 file read (with no session rootId run unscoped), and omit every dashboard section whose read was skipped. For the complete dashboard, dispatch ONE background `Agent` (`run_in_background: true`, `model` set) that runs the steps below and reports the rendered dashboard text, never note bodies. A background agent dispatched by a director is not in director mode: it runs the steps below.

---

## Step 0 - Mode Selection

| `$ARGUMENTS` | Mode |
|--------------|------|
| empty | **Lean** (default) |
| the literal word `full` | **Full Inventory** |
| the literal word `all` | **Multi-Project** - one condensed section per known project root |
| anything else | **Scoped** - resolve to a UUID with `items_read` `search` (`query: $ARGUMENTS`, `limit: 5`, `kinds: ["item"]`); hits carry no depth, parent or type, so `items_read` `get` the top hits before preferring a root or container; if ambiguous, present matches via `AskUserQuestion` |

## Step 0.5 - Project Scope Resolution

- Check the SessionStart context. A `## Project Scope` section carries a **project rootId**, which scopes reads (`ancestorId` / `anchorId`). A `## Personal Scope` section carries a **personal root**, which only anchors new items: in personal scope every call runs unscoped and the header says `personal scope - whole database`.
- Otherwise read the top-level `project.rootId` of `.taskorchestrator/config.yaml` (a file read).
- With no rootId from either path, every call runs unscoped: drop `ancestorId` / `anchorId` / `rootId` from the examples below.

Below, `"<rootId>"` marks a field you include only when a project rootId is known. Scoped mode resolves its own scope per invocation and ignores the project rootId.

---

## Lean Mode (default)

### Data collection (all parallel)

1. Non-terminal roots with `childCounts` roll-ups ([`items_read-overview`](../_generated/call-shapes.md#items_read-overview)). `excludeTerminal` keeps a terminal root that still has open descendants (active work parked under a done container) and drops the rest; `page.truncated` / `page.next` page it.

```to-call
{"tool": "items_read", "select": "overview", "args": {"excludeTerminal": true, "anchorId": "<rootId>"}}
```

2. Health: `data.active.items` (work and review, longest in the role first), `data.blocked.items`, `data.stalled`, `data.claimSummary` ([`items_read-context`](../_generated/call-shapes.md#items_read-context)). `blocked` holds explicit blocks (`blockType: "explicit"`, with `reason` and, for `awaiting_decision`, `decisionId`) AND open items with an unsatisfied dependency (`blockType: "dependency"`). Each `stalled` entry is `{id, title, role, missing[], guidanceKey?, skillPointer?}`.

```to-call
{"tool": "items_read", "select": "context", "args": {"ancestorId": "<rootId>", "limit": 100, "fields": ["ancestors"]}}
```

3. Recent transitions (the "where did I leave off" signal). The `history` select has no `ancestorId`: with no `itemId` it covers every item the caller may see, so the line is whole-tree even inside a project.

```to-call
{"tool": "items_read", "select": "history", "args": {"since": "<now minus 48h, ISO 8601>", "limit": 20}}
```

4. Ranked recommendations (the default `limit` is 1, so ask for 5). `fields: ["ancestors"]` supplies the Container column.

```to-call
{"tool": "items_read", "select": "next", "args": {"limit": 5, "ancestorId": "<rootId>", "fields": ["ancestors"]}}
```

5. The canonical source for queue children. Group rows by `parentId`; `page.total` is the true queued count. Subtract rows whose `type` or tag is `container` or `project` (a fresh container sits in `queue` until its first advance and would inflate `N queued`). When `page.truncated`, the headline count is approximate.

```to-call
{"tool": "items_read", "select": "list", "args": {"role": "queue", "limit": 200, "ancestorId": "<rootId>"}}
```

6. The true terminal count is `data.page.total`.

```to-call
{"tool": "items_read", "select": "list", "args": {"role": "terminal", "limit": 1, "ancestorId": "<rootId>"}}
```

**Headline counts come from these sources**, never from the overview payload (it covers the roots set only and may be truncated).

### Runs (only when runs exist)

Issue both reads in parallel and render the section only when either returns a run. A run is a bounded, budgeted pass over a set of items.

```to-call
{"tool": "runs_read", "select": "list", "args": {"rootId": "<rootId>", "status": "open"}}
```

```to-call
{"tool": "runs_read", "select": "list", "args": {"rootId": "<rootId>", "status": "paused"}}
```

For each run (cap 5, newest first) read its frontier:

```to-call
{"tool": "runs_read", "select": "frontier", "args": {"runId": "<run UUID>"}}
```

`data.counts` is members by role, `ready` the members nothing holds back, `inFlight` the active seats (each with its own `stalled` flag), `waiting` the members held back, each `{id, reason, decisionId?, blockers?}` with a `reason` of `manual`, `dependency`, `awaiting_decision` or `resource`; `decisionId` is present whenever the member's block names a decision, and `blockers` (short ids) only for `dependency`. `stalled` is the coordinator's flag. A paused run's list entry carries `pauseReason`. Render one row per run: label or title, short run id, status, counts, ready, in flight (N silent, from the seats' `stalled` flags), waiting. The word "stalled" is kept for the health `stalled` items (open items missing notes); a run's seats and coordinator are "silent".

When the director connection's tools are present (`report` and `runs_read` `decisions`; skip the rest of this section silently otherwise), add per run the budget and open decisions from the rollup, and one decisions line for the root. Every call here is a director-profile call:

```to-call
{"tool": "report", "select": "rollup", "args": {"runId": "<run UUID>"}, "profile": "director"}
```

`data.budget` is `{used, limit?}` and `data.decisions.open` the open decisions (both run-only; `decisions` is absent when none). Render `budget used/limit` (`used` alone with no `limit`) and `decisions open` in the run's row.

```to-call
{"tool": "runs_read", "select": "decisions", "args": {"rootId": "<rootId>", "limit": 50}, "profile": "director"}
```

The select has no status filter: keep the summaries whose `status` is `open` or `acknowledged`, count them, and count those with `overdue: true`. Render `Decisions waiting: N (k overdue)` just above the Runs table and point at `/task-orchestrator:director` to answer them; omit the line when N is 0.

### Blocked-item decisions

For each health `blocked` entry with `blockType: "explicit"` (cap 10), read the item's context. Its `item.blockedReason` repeats the block reason and `decisions[]` lists the decisions raised on it as `{id, class, status, option?}` (withdrawn ones left out). The question text is not in this view: it is a director-profile read (`runs_read` `decision`), so show the decision class and status instead. This read supplies `decisions[]` class and status only; whether the item is resumable comes from the `blocked` read below.

```to-call
{"tool": "items_read", "select": "context", "args": {"itemId": "<blocked item id>"}}
```

When health `blocked` holds an `awaiting_decision` entry, read `items_read` `blocked` once:

```to-call
{"tool": "items_read", "select": "blocked", "args": {"ancestorId": "<rootId>", "fields": ["ancestors"]}}
```

Each `awaiting_decision` entry carries a server-computed `resumable` (true when its blocking decision is no longer `open` or `acknowledged`). Answering a decision never resumes its item, so flag a resumable one and point at `advance` op `transition` with `trigger: "resume"`.

### Enrichment

- **Containers are shelves, not work.** Items with tag or `type` = `container` never count as active; exclude them from the In Flight list.
- **Active** = health `active.items` minus containers. **Blocked / stalled** = the health lists as they are. Items of a `lifecycle: none` schema have no role, so role-filtered lists and counts leave them out: the dashboard covers role-bearing work (config-reference.md, Lifecycle-free items).
- **Workstreams** = non-terminal roots with children. Progress = `terminal / total` from `childCounts`, rendered `N/M terminal` (not `done`: the terminal bucket includes cancelled items). "Next up" = the highest-priority queue row from step 5 whose `parentId` is that root.
- **Standalone queue roots** group by kind: the `type` if set; else the first tag not in {`agent-observation`, `feature`, `container`}; else "Uncategorized".
- **Rollup threshold:** a group of more than 5 renders as a count line by kind, listing items only when priority is medium or higher or tagged `action-item`.
- **Hygiene candidates:** empty non-terminal containers; evident test artifacts (probe/smoke titles, `mtest-*` tags).

### Dashboard format

Render in this order; omit any section with no data.

```
## ◆ Work Summary

[One sentence: health and momentum.]

**N active · N blocked · N stalled · N queued · N terminal**

### ◉ In Flight
| ID | Item | Container | Status | Pri |
|----|------|-----------|--------|-----|
| `xxxxxxxx` | <title> | <parent title or —> | ◉ work | high |

↳ Recent: <up to 3 one-line transitions from history, newest first; omit if none in 48h>

### ⊘ Attention Required
| ID | Item | Container | Issue |
|----|------|-----------|-------|
| `xxxxxxxx` | <title> | <parent or —> | Blocked by: `<blocker-id>` <blocker-title> |
| `yyyyyyyy` | <title> | <parent or —> | Blocked (awaiting_decision): <class> <status>; resumable |
| `zzzzzzzz` | <title> | <parent or —> | Stalled: missing `<note-key>` |

### ▸ Recommended Next
| ID | Title | Container | Pri |
|----|-------|-----------|-----|

### ⟳ Runs
| Run | Status | Terminal / total | Ready | In flight | Waiting | Budget | Decisions open |
|-----|--------|------------------|-------|-----------|---------|--------|----------------|

### ○ Backlog
**Workstreams**
| ID | Workstream | Progress | Next up | Pri |
|----|-----------|----------|---------|-----|

**<Kind>** (N): `id` <title> (pri) · ...           ← groups of 5 or fewer
**<Kind>** (N): X bug · Y optimization — highest: `id` <title> (pri)   ← groups of more than 5

### ✓ Terminal - N terminal items

↳ Hygiene: <N> test artifacts, <N> empty containers - candidates for /batch-complete
```

**Naming a dependency blocker.** `items_read` `blocked` (`ancestorId`, `fields: ["ancestors"]`) returns EVERY blocking dependency of each item as `blockers[{id, title?, role?, threshold, satisfied}]`; call it only when the health entries are not enough to name the blocker. **For an explicit block** the Issue text is `Blocked (<reason>)` with the reason read above.

**Lean-mode rules:**
- Target ~45 lines; rollups hold the budget.
- The Terminal section is one line (the step 6 count). No titles and no done/cancelled split; use `full` mode for that.
- The Hygiene line appears only when candidates exist.
- If In Flight, Attention and Recommended Next are all empty, say so in the health sentence ("Quiet board - nothing in flight...") instead of rendering empty sections.

---

## Full Inventory Mode (`$ARGUMENTS` = `full`)

Everything lean mode shows, plus the complete per-container inventory.

### Data collection

1. The compact roots list, **without** `includeChildren`; page with `cursor` (`page.next`) while `page.truncated`. Each root carries role, `statusLabel`, `childCounts`, tags and type but no subtree.

```to-call
{"tool": "items_read", "select": "overview", "args": {"limit": 200, "anchorId": "<rootId>"}}
```

2. **Per active container** (a non-terminal root whose `childCounts` show open children), in parallel, a scoped overview. It returns the container, its open children, and any terminal child that still holds open work; `childCounts` stays full. Each call is bounded to one container.

```to-call
{"tool": "items_read", "select": "overview", "args": {"itemId": "<root-id>", "excludeTerminal": true}}
```

3. Health, recommendations and the two role totals exactly as in lean steps 2, 4, 5 and 6.

**Never fetch every root's children in one call** (`includeChildren: true` across the whole tree): on a mature workspace the terminal subtrees exceed the tool-result ceiling. Terminal children are counted from `childCounts`, never enumerated; to see one container's completed items use Scoped mode on it.

### Gate rejections (only with the director tools)

Skip silently without them. One director-profile read, default window 7 days:

```to-call
{"tool": "report", "select": "gates", "args": {"rootId": "<rootId>", "groupBy": "noteKey", "limit": 3}, "profile": "director"}
```

Render one line after Attention Required: `top gate rejections (7d): <noteKey> N, ...` from `data.items[]` (`key.noteKey`, `blocks`); omit it when `items` is empty.

### Sections, in order

1. **Health Headline**, **Attention Required**, **Recommended Next**: as in lean mode.
2. **Project Inventory** - per active container, from its step-2 fetch:

   ```
   #### <Container Title> `<8-char-id>`
   <role-symbol> <role> · <N open> · <N terminal>

   | ID | Title | Status | Pri | Tags | Children |
   |----|-------|--------|-----|------|----------|

   ✓ N terminal - counted from childCounts, not listed
   ```

   - The table lists only **open** children (queue / work / review / blocked), sorted ◉ active, then ⊘ blocked, then ○ queue.
   - `<N open>` = queue + work + review + blocked from `childCounts`; `<N terminal>` = terminal.
   - **Children** = compact role summary `N○ N◉ N⊘ N✓` from the child's own `childCounts` (omit zero roles; `—` if childless).
   - **Tags** = the tags or `—`; traits append with a `▸` prefix, `needs-` stripped.
3. **Standalone Items** - non-terminal childless roots, grouped by the grouping key below.
4. **Terminal** - `### ✓ Terminal - N terminal` (N from the step 6 count). Split root-level terminal items by `statusLabel`: `**Completed containers:**`, `**Completed standalone:**` (+N more if over 5), and `**Cancelled:**` listed separately, never as completed work.
5. **Empty containers** - `**Empty (no items):** <title>, ...` at the end if any.

---

## Scoped Mode (`$ARGUMENTS` = UUID or title)

Use the resolved UUID as the scope for a full-inventory-style rendering of one subtree.

```to-call
{"tool": "items_read", "select": "overview", "args": {"itemId": "<parentId>"}}
```

Health runs with `ancestorId: "<parentId>"`; recommendations use `parentId: "<parentId>"` (direct children) or `ancestorId` (the whole subtree). Render the Full Inventory sections for that subtree only.

---

## Multi-Project Mode (`$ARGUMENTS` = `all`)

One condensed section per project anchor, for workspaces where several project roots share one database.

```to-call
{"tool": "items_read", "select": "list", "args": {"type": "project", "depth": 0}}
```

1. These are the anchors created by `/task-orchestrator:init`, `/adopt-project-scope` or by hand. An anchor tagged `personal-root` is the personal root; label it `(personal)`.
2. If none are found, say so and stop: "No project anchors found - run `/task-orchestrator:init` to bootstrap one, or use `/work-summary` (no arguments) for the whole-DB view."
3. For each anchor, in parallel: the anchored overview (`anchorId`, `excludeTerminal: true`), health with `ancestorId`, and `next` with `limit: 3` and `ancestorId`.

```
### ◆ <Project Name> (`<8-char-id>`)
N active · N blocked · N queued

**Attention:** <up to 2 blocked/stalled items, or "none">
**Next up:** <top next result, or "nothing queued">
```

Keep each section condensed - a cross-project scan, not a deep dive. Point at `/work-summary` for the full dashboard on any one project.

---

## Shared Rendering Rules

**Status symbols:** `✓` terminal · `◉` work/review · `⊘` blocked/stalled · `○` queue.

**Short IDs:** compact views already carry 8-hex ids; render them in backticks. Truncate full UUIDs (run ids, history `itemId`, `blockers[].id` from `items_read` `blocked`) to their first 8 characters. Use `—` for empty values, not `0`. Priority renders `high` / `med` / `low`.

**Grouping key** (standalone items, all modes): `type` if set; else the first tag not in {`agent-observation`, `feature`, `container`}; else "Uncategorized".

**statusLabel:** the role drives the symbol. Print the label only for a non-terminal item whose label differs from the role default; never on terminal items.

**Cancelled is not done:** wherever terminal items are broken down, count `statusLabel: "cancelled"` separately.

**Truncation:** if any response had `page.truncated` that paging could not resolve, end the dashboard with `⚠ showing X of Y` - never silently drop data.

**Observations:** sparingly. A healthy project needs zero.

### Internal: short ID to full UUID

Do NOT render a UUID table. Keep the short-to-full mapping as internal context; when the user cites a short id ("start `0499a6aa`"), resolve it silently from the data already collected (every id field accepts 8 hex characters; an ambiguous prefix is `ambiguous_id`).
