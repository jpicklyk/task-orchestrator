---
name: adopt-project-scope
description: "Migrates an already-populated, unscoped Task Orchestrator database in place to the project-scoping convention — creates a project anchor root, re-parents existing work trees under it, and writes rootId back to config.yaml. Use when a user says: adopt project scope, migrate this database to project scoping, make this DB multi-project, project-scope this workspace, adopt existing database, or set up project scoping for an existing DB."
argument-hint: "[project name] [--dry-run]"
---

# Adopt Project Scope — In-Place Migration to Project Scoping

Take an existing, unscoped database (many depth-0 roots, no project anchor) and migrate it in place to the project-scoping convention: one `type=project` root that owns the workspace's work trees, with its UUID written back to `.taskorchestrator/config.yaml` under a `project:` block. Global containers (retrospectives, observations) stay at depth 0; test artifacts are flagged for cleanup, never moved or deleted.

This is a **destructive-by-execution** skill: the EXECUTE step re-parents real items. It defaults to a dry run and never mutates anything without an explicit confirmation. The only thing it ever deletes is its own throwaway server-probe tree.

**Non-goals:** cross-DB consolidation (merging items from a second database); merging multiple existing project anchors into one; any server-side enforcement of scope. This skill adopts a single workspace's single database.

---

## Step 0 — Parse Arguments

- **Project name** — the first non-flag token(s) in `$ARGUMENTS`. If absent, do not guess; ask for it in Step 3 (the dry-run plan) via `AskUserQuestion` before any mutation.
- **`--dry-run`** — if present, the skill stops after Step 3 (the plan) and performs zero mutations regardless of confirmation. The dry run is ALSO always rendered before execution even without the flag; the flag only forces an early exit.

---

## Step 1 — Preflight (abort gates)

Run these checks in order. Each abort prints a clear, user-facing reason and stops.

### 1a — Already adopted?

Read `.taskorchestrator/config.yaml`. If it contains a `project:` block with a non-empty `rootId:`, the workspace is already scoped:

```
◆ Workspace already adopted — config.yaml already points at project root <rootId>.
  Nothing to do. Use /work-summary to inspect the existing project tree.
```

Stop. (If the file is missing entirely, that is fine — a fresh unscoped DB has no config `project:` block. Continue.)

### 1b — Existing anchor already in the DB?

```
query_items(operation="search", type="project", depth=0)
```

(list mode — `query` omitted, filtering by `type` and `depth`.)

- **No results** → continue to 1c.
- **One or more results** → do NOT silently create a second anchor. Offer, via `AskUserQuestion`:
  1. **Attach to existing** — write the existing anchor's UUID into `config.yaml` (skip anchor creation in EXECUTE; still re-parent MOVE roots under it) and continue.
  2. **Abort** — stop and let the user resolve the ambiguity manually.

  If the user picks "attach", record the existing anchor UUID as `ANCHOR_ID` and note that EXECUTE step (a) is skipped.

### 1c — Server capability probe (the depth-sweep fix)

Bulk re-parenting is exactly the scenario that a pre-fix server corrupts: on a pre-#219/#220 server, re-parenting a subtree does NOT recompute descendant depths (bug `99a3e642`), so this skill would silently leave the moved trees with wrong depths. Verify the fix is deployed with a disposable probe, then delete it.

Build a probe where a grandchild's depth MUST change when a middle node is re-parented deeper:

1. Create a throwaway root and a two-level branch under it in one call:
   ```
   manage_items(operation="create", items=[
     { title: "zzprobe-root",  type: "container", priority: "low" }
   ])
   ```
   Capture its UUID as `P`.
2. Create the branch (sequential parentage, small tree):
   ```
   manage_items(operation="create", items=[
     { title: "zzprobe-A",   parentId: P,  priority: "low" }     → capture as A (depth 1)
   ])
   manage_items(operation="create", items=[
     { title: "zzprobe-M",   parentId: P,  priority: "low" },    → capture as M (depth 1, sibling of A)
     { title: "zzprobe-G",   parentId: M,  priority: "low" }     → capture as G (depth 2, child of M)
   ])
   ```
   (Create M before G so G can reference it; batch where the array ordering allows.)
3. Re-parent the middle node M **under A** (making it deeper):
   ```
   manage_items(operation="update", items=[{ id: M, parentId: A }])
   ```
   Post-fix, M becomes depth 2 and G becomes depth 3.
4. Read the grandchild's depth:
   ```
   query_items(operation="get", id=G)
   ```
   - `depth == 3` → fix is present. Continue.
   - `depth == 2` (stale) → **abort**:
     ```
     ⊘ Server is missing the reparent depth-sweep fix (bug 99a3e642).
       Bulk re-parenting on this server would corrupt subtree depths.
       Upgrade the Task Orchestrator server (PR #219/#220 or later) and retry.
     ```
5. **Always** delete the probe, pass or fail, before continuing or aborting:
   ```
   manage_items(operation="delete", ids=[P], recursive=true)
   ```

### 1d — Config-push capability (tolerant)

`manage_project_config` may be absent on older servers. Do not probe it destructively here — just remember to attempt the push in EXECUTE step (d) and, if the tool is unavailable, skip it with a note rather than failing the whole adoption. The local `config.yaml` write (step c) is the source of truth; the server push is a convenience sync.

---

## Step 2 — Inventory & Classify

Full depth-0 census:

```
query_items(operation="overview", excludeTerminal=false, includeChildren=true, limit=<total>)
```

If the response reports `truncated: true`, re-issue with `limit` set to the reported `total` so no root is missed.

Classify **every depth-0 root** into exactly one bucket:

| Bucket | Rule | Action |
|--------|------|--------|
| **KEEP-GLOBAL** | Title matches a global container — `Session Retrospectives`, `Improvement Proposals`; OR the root is a `container` (tag or `type`) whose children are `agent-observation` items; OR the root itself is tagged `agent-observation`. | Stays at depth 0. |
| **MOVE** | Any work container or work tree (a root with work/queue/review children), and any standalone work item. Everything that is real project work. | Re-parented under the new anchor. |
| **CLEANUP-CANDIDATE** | Evident test artifacts: titles containing `probe`, `smoke`, `depth-test`, `zzprobe`; or tags matching `mtest-*`. | **Flagged only** — never moved, never deleted. Recommend `/batch-complete`. |

**Ambiguous roots** (no clear rule match — e.g., an untagged standalone item that could be work or a stray container): do NOT guess. Collect them and present via `AskUserQuestion` (`multiSelect`) asking which should MOVE vs stay KEEP-GLOBAL. Test-artifact-looking items always go to CLEANUP-CANDIDATE without asking.

---

## Step 3 — Dry-Run Plan (always shown; `--dry-run` stops here)

Render the full plan before any mutation. Require explicit confirmation to proceed.

```
◆ Adopt Project Scope — Plan   [project: "<name>"]

| Root | ID | Classification | Action |
|------|----|----------------|--------|
| Auth System | `a1b2c3d4` | MOVE | re-parent under new anchor |
| Payments | `e5f6a7b8` | MOVE | re-parent under new anchor |
| Session Retrospectives | `c9d0e1f2` | KEEP-GLOBAL | stays at depth 0 |
| Improvement Proposals | `13243546` | KEEP-GLOBAL | stays at depth 0 |
| zzprobe-smoke-tree | `5768798a` | CLEANUP-CANDIDATE | flag for /batch-complete (not moved) |

Summary: 2 move · 2 stay global · 1 cleanup candidate
Execution will create a "<name>" project anchor and re-parent 2 tree(s) under it.
```

- If the project name is still unknown, ask for it now via `AskUserQuestion` before showing the final counts.
- **`--dry-run`**: stop here. State plainly: "Dry run — no changes made. Re-run without --dry-run to execute."
- Otherwise, confirm via `AskUserQuestion`:
  ```
  ◆ Proceed with adoption? This re-parents <N> tree(s) under a new project anchor.
    1. Yes — execute
    2. No — abort, make no changes
  ```
  Wait for the answer. Only "Yes" proceeds.

---

## Step 4 — Execute

Perform in this order. Record pre-execution counts first (used by Step 5): the depth-0 root count, the MOVE count, and the KEEP-GLOBAL count from Step 2.

**(a) Create the project anchor** — skip if attaching to an existing anchor (Step 1b):

```
manage_items(operation="create", items=[{
  title: "<project name>",
  type: "project",
  summary: "Project anchor — subtree scope for <project name>",
  priority: "low"
}])
```

Capture the new UUID as `ANCHOR_ID`.

**(b) Batch re-parent the MOVE roots** — one call, `items` array (never a sequential call per root):

```
manage_items(operation="update", items=[
  { id: "<move-root-1>", parentId: ANCHOR_ID },
  { id: "<move-root-2>", parentId: ANCHOR_ID },
  ...
])
```

This relies on the depth-sweep fix verified in Step 1c — each moved subtree's descendant depths and `root_id` are recomputed server-side.

**(c) Write the `project:` block into `config.yaml`** — read-modify-write, preserving ALL existing content:

- Read the current `.taskorchestrator/config.yaml` text (create the file with just the block if it does not exist).
- Insert this top-level block (surgical insert/append — do NOT regenerate or reformat the rest of the file; leave `work_item_schemas:`, `traits:`, `actor_authentication:`, comments, and formatting byte-for-byte intact):
  ```yaml
  project:
    rootId: "<ANCHOR_ID>"
    name: "<project name>"
  ```
- If a `project:` key somehow already exists (it should not — Step 1a would have aborted), update its `rootId`/`name` in place rather than adding a duplicate key.

**(d) Push the config server-side** (tolerant — skip if the tool is absent):

```
manage_project_config(operation="push", rootItemId=ANCHOR_ID, configYaml="<full config.yaml text>")
```

- A `warning` field about the root's type is only returned when the anchor's `type` is not `project`; since step (a) sets `type: "project"`, none is expected. Report it if present but treat it as non-fatal.
- If `manage_project_config` is unavailable on this server, skip this step and note: "Config pushed locally only — server does not support manage_project_config; the local config.yaml is authoritative."

---

## Step 5 — Verify & Reconcile

Confirm the migration landed correctly. On ANY mismatch, report loudly and do NOT auto-rollback (say the remedy explicitly).

1. **Anchored child count** — the new anchor's direct children must equal the MOVE count:
   ```
   query_items(operation="overview", ancestorId=ANCHOR_ID)
   ```
   The anchor's direct-child count must equal `N move`.
2. **Global census** — remaining depth-0 roots must reconcile:
   ```
   query_items(operation="overview", excludeTerminal=false, limit=<total>)
   ```
   Expected depth-0 roots = KEEP-GLOBAL count + 1 (the anchor) + any CLEANUP-CANDIDATE roots left in place (cleanup candidates were flagged, not moved). Confirm the moved roots are no longer at depth 0.
3. **Depth spot-check** — pick one moved tree that had a grandchild; confirm the grandchild's depth increased by exactly 1 from its pre-move value (the anchor added one level above the old root):
   ```
   query_items(operation="get", id="<grandchild-of-a-moved-tree>")
   ```

Render a before/after table:

```
✓ Adoption Complete — "<project name>"  (anchor `<8-char-id>`)

|                     | Before | After |
|---------------------|--------|-------|
| Depth-0 roots       | 5      | 3     |
| Under project anchor| 0      | 2     |
| Global containers   | 2      | 2     |
| Cleanup candidates  | 1      | 1 (flagged, not moved) |

↳ Config: project.rootId written to .taskorchestrator/config.yaml
↳ Server push: applied | skipped (tool absent)
↳ Cleanup candidates flagged for /batch-complete: <titles>
```

**On mismatch** (e.g., anchored child count ≠ MOVE count):

```
⚠ Reconciliation mismatch — expected <X> children under the anchor, found <Y>.
  No rollback was performed. To undo: re-parent the affected roots back to depth 0
  with manage_items(operation="update", items=[{ id, parentId: null }]) for each,
  then delete the anchor. Investigate before retrying.
```

Remind the user that schema changes / new config require an MCP reconnect (`/mcp`) to take effect if they rely on per-root schema resolution immediately.

---

## Safety Summary

- **Default dry run.** No mutation without an explicit `AskUserQuestion` "Yes".
- **Never deletes user data.** Cleanup candidates are only *flagged*; the sole deletion is the throwaway server probe in Step 1c.
- **Never regenerates config.yaml.** The `project:` block is surgically inserted; all other content is preserved verbatim.
- **Abort, don't corrupt.** A pre-fix server (missing the depth-sweep) aborts in preflight rather than silently corrupting subtree depths.
- **No auto-rollback.** On verify mismatch, the skill reports the manual remedy rather than guessing at an undo.

---

## Verification (before first real run)

Per the specification, exercise the skill against a COPY of a production-shaped DB:

1. **Dry run on the copy** must produce zero mutations and a correct classification table.
2. **Execute on the copy** and confirm Step 5 count reconciliation passes (depth-0 roots, anchored children, grandchild depth +1).

Only after the copy passes should the skill be run against the real database.
