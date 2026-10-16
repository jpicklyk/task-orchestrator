---
name: adopt-project-scope
description: "Migrates an already-populated, unscoped Task Orchestrator database in place to the project-scoping convention — creates a project anchor root, re-parents existing work trees under it, and writes rootId back to config.yaml. Use when a user says: adopt project scope, migrate this database to project scoping, make this DB multi-project, project-scope this workspace, adopt existing database, or set up project scoping for an existing DB."
argument-hint: "[project name] [--dry-run]"
---

# Adopt Project Scope — In-Place Migration to Project Scoping

Take an existing, unscoped database (many depth-0 roots, no project anchor) and migrate it in place to the project-scoping convention: one `type=project` root that owns the workspace's work trees, with its UUID written back to `.taskorchestrator/config.yaml` under a `project:` block. Global containers (retrospectives, observations) stay at depth 0; test artifacts are flagged for cleanup, never moved or deleted.

This is a **destructive-by-execution** skill: the EXECUTE step re-parents real items. It defaults to a dry run and never mutates anything without an explicit confirmation. It deletes nothing.

For a fresh workspace with no existing unscoped work, use `/task-orchestrator:init` instead; this skill is for databases that already hold unscoped work trees.

Every tool call below is a `to-call` block, `{tool, select|op, args}`, in the shapes of [call-shapes.md](../_generated/call-shapes.md). The `config` tool is orchestration-profile only.

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

```to-call
{"tool": "items_read", "select": "list", "args": {"type": "project", "depth": 0}}
```

Drop any result tagged `personal-root`: a personal root is not a project anchor and must never be adopted as one.

- **No results** → continue to 1c.
- **One or more results** → do NOT silently create a second anchor. Offer, via `AskUserQuestion`:
  1. **Attach to existing** — write the existing anchor's UUID into `config.yaml` (skip anchor creation in EXECUTE; still re-parent MOVE roots under it) and continue.
  2. **Abort** — stop and let the user resolve the ambiguity manually.

  If the user picks "attach", record the existing anchor UUID as `ANCHOR_ID` and note that EXECUTE step (a) is skipped.

### 1c — Config-push capability (tolerant)

The `config` tool (orchestration profile) pushes the config in EXECUTE step (d). Do not probe it destructively here — just remember to attempt the push there and, if the tool is not served (a knowledge-profile connection) or answers `forbidden` (the credential lacks `write-config`), skip it with a note rather than failing the whole adoption. The local `config.yaml` write (step c) is the source of truth; the server push is a convenience sync.

A 4.x server recomputes descendant depths when a subtree is re-parented, so no capability probe is needed. A 3.x server before PR #219/#220 does not (bug `99a3e642`); if the server may predate 4.0, upgrade it before adopting.

---

## Step 2 — Inventory & Classify

Full depth-0 census:

```to-call
{"tool": "items_read", "select": "overview", "args": {"excludeTerminal": false, "includeChildren": true, "limit": 200}}
```

If the response carries `page.next`, continue with that `cursor` until every root is listed so none is missed.

Read which types the **server layer** declares, so process items can be told apart from project work. `config` `effective` with no `rootId` returns the server config alone:

```to-call
{"tool": "config", "op": "effective", "args": {}}
```

Classify **every depth-0 root** into exactly one bucket:

| Bucket | Rule | Action |
|--------|------|--------|
| **KEEP-GLOBAL** | The root's `type` is declared by the server layer (from the `effective` call above) and is not `container` or `project` — e.g. `agent-observation` items, which are standalone depth-0 roots; OR a `container` root titled `Session Retrospectives` or `Improvement Proposals`; OR a depth-0 `type=project` item tagged `personal-root` (never MOVE it). | Stays at depth 0. |
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

**(a) Create the project anchor** — skip if attaching to an existing anchor (Step 1b). The global floor declares `project`; if the create answers `schema_violation` and `detail.availableTypes` lacks `project`, follow the fallback in `/task-orchestrator:init` (offer `container` or adding `project` to the server file; never pick a type silently):

```to-call
{"tool": "items_write", "op": "create", "args": {"title": "<project name>", "type": "project", "summary": "Project anchor — subtree scope for <project name>", "priority": "low"}}
```

Capture the new UUID as `ANCHOR_ID`.

**(b) Batch re-parent the MOVE roots** — one `items_write` call whose `ops` array holds one `update` per root (never a sequential call per root). The example shows one element; repeat it for each MOVE root in the same call:

```to-call
{"tool": "items_write", "op": "update", "args": {"id": "<move-root-1>", "parentId": "<ANCHOR_ID>"}}
```

A 4.x server recomputes each moved subtree's descendant depths and `root_id`. Each moved tree is also repinned against the anchor's config head (the `reparent` pin point; see [Schema pins](../../../../current/docs/config-reference.md#schema-pins)), so a tree whose items resolve a schema of a different lifecycle class under the anchor's config fails that element with `invalid_transition` and nothing is written for it. The call commits element by element: read the per-element results, report any failure by root, and leave that root for the user to decide.

**(c) Write the `project:` block into `config.yaml`** at the main checkout root (the parent of `git rev-parse --path-format=absolute --git-common-dir`, so it is correct from a linked worktree; refuse a home directory, as `/task-orchestrator:init` does) — read-modify-write, preserving ALL existing content:

- Read the current `<main>/.taskorchestrator/config.yaml` text (create the file with just the block if it does not exist).
- Insert this top-level block (surgical insert/append — do NOT regenerate or reformat the rest of the file; leave `work_item_schemas:`, `traits:`, `extensions:`, comments, and formatting byte-for-byte intact):
  ```yaml
  project:
    rootId: "<ANCHOR_ID>"
    name: "<project name>"
  ```
- If a `project:` key somehow already exists (it should not — Step 1a would have aborted), update its `rootId`/`name` in place rather than adding a duplicate key.

**(d) Validate, then push the config server-side** (tolerant — see 1c). Use the file text with CRLF replaced by LF as `configYaml`. A root with no config answers `config` `get` with `not_found`, which means "no head" (then omit `expectedVersion`):

```to-call
{"tool": "config", "op": "get", "args": {"rootId": "<ANCHOR_ID>"}}
```

```to-call
{"tool": "config", "op": "validate", "args": {"rootId": "<ANCHOR_ID>", "configYaml": "<full config.yaml text, LF>"}}
```

```to-call
{"tool": "config", "op": "push", "args": {"rootId": "<ANCHOR_ID>", "configYaml": "<full config.yaml text, LF>", "expectedVersion": 1}}
```

- `validate` reports a problem as `valid: false` with `errors[{path, reason}]`; show them and skip the push (the local file is saved). Relay `warnings`.
- `config_invalid` on the push: show `detail.errors[{path, reason}]`; nothing was stored. `version_conflict`: read the head again and ask before retrying.
- If the `config` tool is not available or the call is `forbidden`, skip this step and note: "Config saved locally only — the server push was not possible; the local config.yaml is authoritative."

---

## Step 5 — Verify & Reconcile

Confirm the migration landed correctly. On ANY mismatch, report loudly and do NOT auto-rollback (say the remedy explicitly).

1. **Anchored child count** — the new anchor's direct children must equal the MOVE count:
   ```to-call
   {"tool": "items_read", "select": "overview", "args": {"anchorId": "<ANCHOR_ID>"}}
   ```
   The anchor's direct-child count must equal `N move`.
2. **Global census** — remaining depth-0 roots must reconcile:
   ```to-call
   {"tool": "items_read", "select": "overview", "args": {"excludeTerminal": false, "limit": 200}}
   ```
   Expected depth-0 roots = KEEP-GLOBAL count + 1 (the anchor) + any CLEANUP-CANDIDATE roots left in place (cleanup candidates were flagged, not moved). Confirm the moved roots are no longer at depth 0.
3. **Depth spot-check** — pick one moved tree that had a grandchild; confirm the grandchild's depth increased by exactly 1 from its pre-move value (the anchor added one level above the old root):
   ```to-call
   {"tool": "items_read", "select": "get", "args": {"itemId": "<grandchild-of-a-moved-tree>"}}
   ```

Render a before/after table:

```
✓ Adoption Complete — "<project name>"  (anchor `<8-char-id>`)

|                     | Before | After |
|---------------------|--------|-------|
| Depth-0 roots       | 5      | 4     |
| Under project anchor| 0      | 2     |
| Global containers   | 2      | 2     |
| Cleanup candidates  | 1      | 1 (flagged, not moved) |

↳ Config: project.rootId written to .taskorchestrator/config.yaml
↳ Server push: applied | skipped (tool absent or forbidden)
↳ Cleanup candidates flagged for /batch-complete: <titles>
```

**On mismatch** (e.g., anchored child count ≠ MOVE count):

```
⚠ Reconciliation mismatch — expected <X> children under the anchor, found <Y>.
  No rollback was performed. To undo: re-parent the affected roots back to depth 0
  with an items_write update that clears their parent, one element per root, then
  delete the anchor. Investigate before retrying.
```

The undo element for one root clears `parentId` (a `clear` list, since `parentId: null` is not a valid value):

```to-call
{"tool": "items_write", "op": "update", "args": {"id": "<moved-root>", "clear": ["parentId"]}}
```

Finish by seeding the bundled process rules for the new anchor: run `/task-orchestrator:init` (a re-run is idempotent and resumes at rule seeding), or rely on the next session's config-sync.

A pushed config takes effect immediately for new and unpinned items of the root; items already pinned keep their schema until `items_write` `repin` (see `/task-orchestrator:manage-schemas`).

---

## Safety Summary

- **Default dry run.** No mutation without an explicit `AskUserQuestion` "Yes".
- **Never deletes user data.** Cleanup candidates are only *flagged*; the skill deletes nothing.
- **Never regenerates config.yaml.** The `project:` block is surgically inserted; all other content is preserved verbatim.
- **Fail per element, not silently.** A root whose re-parent is refused (`invalid_transition` on a lifecycle-class crossing) is reported and left in place.
- **No auto-rollback.** On verify mismatch, the skill reports the manual remedy rather than guessing at an undo.

---

## Verification (before first real run)

Per the specification, exercise the skill against a COPY of a production-shaped DB:

1. **Dry run on the copy** must produce zero mutations and a correct classification table.
2. **Execute on the copy** and confirm Step 5 count reconciliation passes (depth-0 roots, anchored children, grandchild depth +1).

Only after the copy passes should the skill be run against the real database.
