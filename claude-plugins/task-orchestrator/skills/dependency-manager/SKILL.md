---
name: dependency-manager
description: Visualize, create, and diagnose dependencies between MCP work items. Use when a user says "what blocks this", "add a dependency", "show dependency graph", "why can't this start", "link these items", "unblock this", "remove dependency", or "show blockers".
argument-hint: "[optional: item UUID, title, or action like 'show blockers for X']"
---

# dependency-manager — Dependency Visualization, Creation, and Diagnosis

Manage BLOCKS, IS_BLOCKED_BY, and RELATES_TO edges between work items. Handles all four paths: view existing dependencies, create new edges, delete edges, and diagnose why items cannot start.

---

## Step 1: Determine Intent

Classify the user request before making any tool calls.

**If `$ARGUMENTS` looks like a UUID** (8-4-4-4-12 hex pattern), default intent to VIEW for that item.

**If `$ARGUMENTS` is a text string** (title fragment or action phrase), search for matching items:

```
query_items(operation="search", query="$ARGUMENTS", limit=5)
```

If multiple results are returned, present them and ask which item the user means.

**If `$ARGUMENTS` is empty**, infer intent from the surrounding conversation. If intent is still unclear, ask via `AskUserQuestion`: "What would you like to do with dependencies? Options: view, create, delete, diagnose."

**Compound requests** (e.g., "show what blocks this and remove the dependency"): handle the VIEW path first, then proceed to the second action using the results.

**Intent signal words:**

| Signal words | Path |
|---|---|
| "show", "view", "graph", "what blocks", "what depends on", "visualize" | VIEW (Step 2) |
| "add", "create", "link", "connect", "chain", "depend on" | CREATE (Step 3) |
| "remove", "delete", "unlink", "disconnect" | DELETE (Step 4) |
| "why can't this start", "why is this blocked", "diagnose", "show blockers", "unblock" | DIAGNOSE (Step 5) |

---

## Step 2: View Dependencies

Once you have an item ID and intent is VIEW, query its dependency edges:

```
query_dependencies(itemId="<uuid>", direction="all", includeItemInfo=true)
```

Format the result as an ASCII tree:

```
◉ Design API schema (work)
  ↳ BLOCKS → ○ Implement data models (queue)
  ↳ BLOCKS → ○ Build REST endpoints (queue)
  ← BLOCKED BY → ◉ Finalize data contract (work)
```

Use the visual symbols to indicate role at a glance:

| Symbol | Role |
|---|---|
| ✓ | terminal |
| ◉ | work or review |
| ○ | queue |
| ⊘ | blocked |

**Direction parameter meanings:**

| Value | Returns |
|---|---|
| `outgoing` | Edges where this item is the source (things this item blocks) |
| `incoming` | Edges where this item is the target (things that block this item) |
| `all` | Both directions combined |

For a full chain view (ancestors and descendants beyond immediate neighbors), add `neighborsOnly=false`:

```
query_dependencies(itemId="<uuid>", direction="all", includeItemInfo=true, neighborsOnly=false)
```

This performs BFS traversal and returns the full dependency graph. Use it when the user asks to "show the full chain" or "trace all blockers."

After displaying the tree, note any items in `⊘ blocked` state and offer to run DIAGNOSE (Step 5) on them.

---

## Step 3: Create Dependencies

Identify the structure from what the user described, then select the right creation pattern.

**Decision tree:**

```
Two specific items to link → single edge (dependencies array)
Three or more items in a sequence (A then B then C) → linear pattern
One item that blocks many others → fan-out pattern
Many items that all block one item → fan-in pattern
```

**Pattern reference:**

| Pattern | Key parameter | When to use |
|---|---|---|
| Single edge | `dependencies=[{fromItemId, toItemId}]` | Link exactly two items |
| `linear` | `itemIds=[A, B, C, D]` | Sequential chain: A→B→C→D |
| `fan-out` | `source=A`, `targets=[B, C, D]` | One item blocks many |
| `fan-in` | `sources=[A, B, C]`, `target=D` | Many items block one |

Before creating, confirm intent with the user:

```
◆ About to create:
  A → BLOCKS → B
  B → BLOCKS → C
  C → BLOCKS → D
  Proceed?
```

Then call `manage_dependencies(operation="create")` with the selected pattern:

```
manage_dependencies(
  operation="create",
  pattern="linear",
  itemIds=["<uuid-a>", "<uuid-b>", "<uuid-c>", "<uuid-d>"]
)
```

For a single edge or custom edges, use the `dependencies` array directly:

```
manage_dependencies(
  operation="create",
  dependencies=[
    { fromItemId: "<uuid-a>", toItemId: "<uuid-b>", type: "BLOCKS" }
  ]
)
```

After creation, show the edges created:

```
✓ Created 3 dependency edges:
  A → BLOCKS → B
  B → BLOCKS → C
  C → BLOCKS → D
```

To set a partial unblock threshold (so the blocked item unblocks before the blocker is terminal), include `unblockAt` in the dependency spec. See the `unblockAt` reference table below.

---

## Step 4: Delete Dependencies

Query existing edges first so the user knows what can be deleted:

```
query_dependencies(itemId="<uuid>", direction="all", includeItemInfo=true)
```

Present the edges to the user:

```
Existing edges for "Implement data models":
  [1] ◉ Design API schema → BLOCKS → this item  (dep-uuid-1)
  [2] this item → BLOCKS → ○ Build REST endpoints  (dep-uuid-2)

Which edge(s) would you like to remove?
```

Confirm before deleting. Then call `manage_dependencies(operation="delete")` using the appropriate mode:

```
manage_dependencies(operation="delete", id="<dep-uuid>")
```

**Delete parameter modes:**

| Mode | Parameters | When to use |
|---|---|---|
| By dependency ID | `id="<dep-uuid>"` | Delete one specific edge (most precise) |
| By relationship | `fromItemId="<uuid>", toItemId="<uuid>"` | Delete the edge between two known items |
| By relationship + type | `fromItemId, toItemId, type="BLOCKS"` | When multiple edge types exist between same pair |
| All edges for item | `fromItemId="<uuid>", deleteAll=true` | Remove all outgoing edges from an item |
| All edges for item | `toItemId="<uuid>", deleteAll=true` | Remove all incoming edges to an item |

After deletion, confirm:

```
✓ Removed: Design API schema → BLOCKS → Implement data models
```

---

## Step 5: Diagnose Blocked Items

For DIAGNOSE intent, identify why a specific item cannot start or is stuck in blocked state.

**Path A — User provided an item ID:**

```
query_dependencies(itemId="<uuid>", direction="incoming", includeItemInfo=true)
```

**Path B — User wants a broad view of all blocked work:**

```
get_blocked_items(includeItemDetails=true)
```

For each blocker returned, show:

```
⊘ "Build REST endpoints" cannot start because:

  Blocker 1: ◉ Design API schema (work)
    Must reach: terminal (unblockAt: terminal)
    Action: advance Design API schema to terminal first

  Blocker 2: ○ Write OpenAPI spec (queue)
    Must reach: terminal (unblockAt: terminal)
    Action: start and complete Write OpenAPI spec first
```

For each blocker, determine what must happen:

| Blocker's current role | unblockAt threshold | What needs to happen |
|---|---|---|
| queue | terminal | Start and complete the blocker |
| work | terminal | Complete the blocker (already started) |
| work | review | Advance the blocker to review |
| review | terminal | Advance the blocker to terminal |
| blocked | any | The blocker itself is stuck — recurse diagnosis |

If any blocker is itself blocked, offer to recurse: "The blocker is also blocked. Would you like to diagnose that item too?"

After the diagnosis, link to the resolution path:
- To advance the blocking item: use `/status-progression` with its UUID
- To fill missing notes on the blocker first: use `/note-viewer` with its UUID

---

## Dependency Type Reference

| Type | Meaning | Effect |
|---|---|---|
| `BLOCKS` | A must complete before B can proceed | B appears as blocked until A reaches its unblockAt threshold |
| `IS_BLOCKED_BY` | Reverse of BLOCKS — same edge, opposite direction | Equivalent to creating BLOCKS from B to A |
| `RELATES_TO` | Informational link only — no blocking behavior | Item appears in dependency queries but does not affect role transitions |

---

## `unblockAt` Threshold Reference

| Value | When the dependent item unblocks | Use case |
|---|---|---|
| `terminal` (default) | Blocker must finish entirely | Standard sequential dependency |
| `review` | Unblocks when blocker enters review phase | Start next step while review is in progress |
| `work` | Unblocks when blocker starts work | Parallel work that just needs the prior item started |
| `queue` | Unblocks immediately (tracks ordering only) | Soft ordering constraint with no actual blocking |

---

## Troubleshooting

**Problem: Cycle detection error when creating a dependency**

Cause: The proposed edge would create a circular dependency chain (A blocks B, B blocks C, C blocks A). The server detects this and rejects the entire batch atomically.

Solution: Review the dependency direction. One of the edges is backwards. Identify which item actually depends on the other, flip the `fromItemId` and `toItemId` on that edge, and retry.

---

**Problem: "dependency not found" error on delete**

Cause: The dependency UUID or relationship does not exist. The edge may have already been deleted, or the IDs are from a different environment.

Solution: Re-query to confirm current state:

```
query_dependencies(itemId="<uuid>", direction="all", includeItemInfo=true)
```

Use a dependency UUID from the fresh query result for the delete call. If the edge is not present, it was already removed.

---

**Problem: Item is still blocked after the blocker reached terminal**

Cause: Either the `unblockAt` threshold is set to a role the blocker has not yet reached (e.g., `unblockAt: "review"` but the blocker went straight to terminal via `complete`), or there are additional incoming edges from other items that are not yet satisfied.

Solution: Query incoming edges to check all blockers:

```
query_dependencies(itemId="<uuid>", direction="incoming", includeItemInfo=true)
```

Check each blocker's `role`. If all blockers are terminal and the item is still in blocked role, use `advance_item(trigger="resume")` to manually return it to its previous role via `/status-progression`.

---

**Problem: Pattern shortcut creates wrong edges**

Cause: The wrong parameter name was used for the pattern. `linear` uses `itemIds` (an ordered array). `fan-out` uses `source` (single UUID) and `targets` (array). `fan-in` uses `sources` (array) and `target` (single UUID). Mixing these up creates edges in the wrong direction or fails silently.

Solution: Double-check the parameter names against the pattern table in Step 3. Re-query the item after creation to verify edge direction, and delete any incorrect edges using Step 4.

---

## Examples

### Example 1: View dependencies for an item

User: "Show me what blocks the REST endpoints task."

Search → one match `uuid-rest`. Query incoming deps:

```
query_dependencies(itemId="uuid-rest", direction="incoming", includeItemInfo=true)
```

Display:
```
○ Build REST endpoints (queue)
  ← BLOCKED BY ◉ Design API schema (work)
  ← BLOCKED BY ○ Write OpenAPI spec (queue)

Both blockers must reach terminal. Use /status-progression on each.
```

---

### Example 2: Create a linear chain

User: "Set up A → B → C → D as a chain." Resolve UUIDs, confirm, then:

```
manage_dependencies(operation="create", pattern="linear",
  itemIds=["uuid-a", "uuid-b", "uuid-c", "uuid-d"])
```

Result:
```
✓ Created 3 edges: A → B → C → D
```

---

### Example 3: Diagnose why an item cannot start

User: "Why can't 'Write integration tests' start?" Search → `uuid-tests`. Query incoming:

```
query_dependencies(itemId="uuid-tests", direction="incoming", includeItemInfo=true)
```

Display:
```
⊘ "Write integration tests" cannot start:
  Blocker 1: ○ Build REST endpoints (queue) — must reach terminal
  Blocker 2: ◉ Implement data models (work) — must reach terminal

Recommended: complete both blockers via /status-progression
```

---

## Quick Decision Guide

| Situation | Action |
|---|---|
| User asks what blocks an item | Step 2 — query incoming with `includeItemInfo=true` |
| User asks what an item blocks | Step 2 — query outgoing with `includeItemInfo=true` |
| User wants full chain visualization | Step 2 — add `neighborsOnly=false` |
| User wants to link two items | Step 3 — single edge via `dependencies` array |
| User has a sequential list of items | Step 3 — `pattern="linear"` with `itemIds` |
| One item must precede many | Step 3 — `pattern="fan-out"` |
| Many items must precede one | Step 3 — `pattern="fan-in"` |
| User wants to remove a link | Step 4 — query first, confirm, delete by dep ID |
| Item is stuck and user does not know why | Step 5 — diagnose incoming edges |
| Blocker analysis complete, need to advance | Use `/status-progression` on the blocker |
