---
name: adopt-project-scope
description: "Migrates an already-populated, unscoped Task Orchestrator database in place to the project-scoping convention — creates a project anchor root, re-parents existing work trees under it, and writes rootId back to config.yaml. Use when a user says: adopt project scope, migrate this database to project scoping, make this DB multi-project, project-scope this workspace, adopt existing database, or set up project scoping for an existing DB."
argument-hint: "[project name] [--dry-run]"
---

# Adopt Project Scope — In-Place Migration to Project Scoping

Take an existing, unscoped database (many depth-0 roots, no project anchor) and migrate it in place to the project-scoping convention: one `type=project` root that owns the workspace's work trees, with its UUID written back to `.taskorchestrator/config.yaml` under a `project:` block. Global containers (retrospectives, observations) stay at depth 0; test artifacts are flagged for cleanup, never moved or deleted.

This is a **destructive-by-execution** skill: the EXECUTE step re-parents real items. It defaults to a dry run and never mutates anything without an explicit confirmation. It deletes nothing.

For a fresh workspace with no existing unscoped work, use `/task-orchestrator:init` instead; this skill is for databases that already hold unscoped work trees.

Not a director-mode skill: run it outside director mode (`/task-orchestrator:director off`); director-guard denies its file, shell and config steps partway through. Step 0 refuses to start when the session context says director mode is on, because a guard denial between the anchor create and the config write would leave a partial anchor.

Every tool call below is a `to-call` block, `{tool, select|op, args}`, in the shapes of [call-shapes.md](../_generated/call-shapes.md). The `config` tool is orchestration-profile only.

**Non-goals:** cross-DB consolidation (merging items from a second database); merging multiple existing project anchors into one; any server-side enforcement of scope. This skill adopts a single workspace's single database.

---

## Step 0 — Parse Arguments

- **Director mode.** When the session context says this session is in director mode, stop before anything else: tell the user to run `/task-orchestrator:director off` and re-run. Do not create the anchor first.

- **Project name** — the first non-flag token(s) in `$ARGUMENTS`. If absent, do not guess; ask for it in Step 3 (the dry-run plan) via `AskUserQuestion` before any mutation.
- **`--dry-run`** — if present, the skill stops after Step 3 (the plan) and performs zero mutations regardless of confirmation. The dry run is ALSO always rendered before execution even without the flag; the flag only forces an early exit.

---

## Step 1 — Preflight (abort gates)

Run these checks in order. Each abort prints a clear, user-facing reason and stops.

### 1a — Already adopted?

Read `<main>/.taskorchestrator/config.yaml`, where `<main>` is the parent of `git rev-parse --path-format=absolute --git-common-dir` (the same file Step 4(b) writes, correct from a linked worktree). If it contains a `project:` block with a non-empty `rootId:`, the workspace is already scoped:

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

The `config` tool (orchestration profile) pushes the config in EXECUTE step (c). Do not probe it destructively here — just remember to attempt the push there and, if the tool is not served (a knowledge-profile connection) or fails with `data.results[0].error.code` `forbidden` (the credential lacks `write-config`), skip it with a note rather than failing the whole adoption. The local `config.yaml` write (step b) is the source of truth; the server push is a convenience sync.

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

If `data.results[0].types` is empty, the server declares no types: an undeclared type is rejected only when the declared set is non-empty, so the `project` anchor create in Step 4 would succeed schema-free and the anchor would carry no schema. Point the user to `/task-orchestrator:configure-server` to mount the process-schema floor now, before the plan is confirmed; continue only if they choose to adopt without it.

Classify **every depth-0 root** into exactly one bucket:

| Bucket | Rule | Action |
|--------|------|--------|
| **KEEP-GLOBAL** | The root's `type` is declared by the server layer (from the `effective` call above) and is not `container` or `project` — e.g. `agent-observation` items, which are standalone depth-0 roots; OR a `container` root titled `Session Retrospectives`, `Improvement Proposals` or `Retrospective Trends`; OR a depth-0 `type=project` item tagged `personal-root` (never MOVE it). | Stays at depth 0. |
| **MOVE** | Any work container or work tree (a root with work/queue/review children), and any standalone work item. Everything that is real project work. | Re-parented under the new anchor. |
| **CLEANUP-CANDIDATE** | Evident test artifacts: titles containing `probe`, `smoke`, `depth-test`, `zzprobe`; or tags matching `mtest-*`. | **Flagged only** — never moved, never deleted. Recommend `/batch-complete`. |

**Per MOVE root, read and record four things** (they feed the plan; nothing is mutated). Open runs, the root's config head, its documents and its pending decision blocks:

```to-call
{"tool": "runs_read", "select": "list", "args": {"rootId": "<move-root>", "limit": 50}}
```

Keep entries whose `status` is `open` or `paused`. A root with no per-root config answers `not_found`, which means "no head":

```to-call
{"tool": "config", "op": "get", "args": {"rootId": "<move-root>"}}
```

```to-call
{"tool": "docs_read", "select": "list", "args": {"rootId": "<move-root>", "limit": 50}}
```

```to-call
{"tool": "items_read", "select": "blocked", "args": {"ancestorId": "<move-root>", "limit": 50}}
```

From the blocked list keep the explicit blocks whose reason is `awaiting_decision`. A config head that exists is per-root config that stops applying after the move. Documents stay keyed to the old root id; the count is inventory only.

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

Add to the plan, per MOVE root: its open or paused runs, its config head (version), its document count and its pending-decision blocks. Then apply these rules before the confirmation:

- **Open or paused runs: HOLD.** A MOVE root with any open or paused run is reclassified HOLD and is not moved until those runs are closed (by a coordinator, director or human). A run belongs to the root it opened under and `runs_write` `open` refuses a non-root, so moving the root would strand the run. The plan says so, per root.
- **Pending decisions.** Recommend answering them first. A decision stays keyed to the old root id after a move (it is not restamped), so a director's `runs_read` `decisions` with the anchor as `rootId` will not show it.
- **Config head.** Either merge it into the workspace `config.yaml` before the move or accept losing it: a per-root config on a root that is no longer a root stops applying.
- **Credential warning (always printed).** List every MOVE root id and state: any token-file `scope.root_ids`, or JWT `to_scope.root_ids`, that names one of these ids must be re-scoped to the anchor before the next server restart. A token file fails startup on a non-root id; a JWT is refused with `401`.
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

**(a) Create the project anchor** — skip if attaching to an existing anchor (Step 1b). The global floor declares `project`; if the create fails (call-level `partial_failure`) with `data.results[0].error.code` `schema_violation` and its `detail.availableTypes` lacks `project` (the server declares types, but not this one), follow the fallback in `/task-orchestrator:init` (offer `container` or adding `project` to the server file; never pick a type silently):

```to-call
{"tool": "items_write", "op": "create", "args": {"title": "<project name>", "type": "project", "summary": "Project anchor — subtree scope for <project name>", "priority": "low"}}
```

Capture the new UUID as `ANCHOR_ID`.

**(b) Write the `project:` block into `config.yaml`** at the main checkout root (the parent of `git rev-parse --path-format=absolute --git-common-dir`, so it is correct from a linked worktree; refuse a home directory, as `/task-orchestrator:init` does) — read-modify-write, preserving ALL existing content:

- Read the current `<main>/.taskorchestrator/config.yaml` text (create the file with just the block if it does not exist).
- Insert this top-level block (surgical insert/append — do NOT regenerate or reformat the rest of the file; leave `work_item_schemas:`, `traits:`, `extensions:`, comments, and formatting byte-for-byte intact):
  ```yaml
  project:
    rootId: "<ANCHOR_ID>"
    name: "<project name>"
  ```
- If a `project:` key somehow already exists (it should not — Step 1a would have aborted), update its `rootId`/`name` in place rather than adding a duplicate key.

**(c) Validate, then push the config server-side** (tolerant — see 1c). Use the file text with CRLF replaced by LF as `configYaml`. A root with no config fails `config` `get` with `data.results[0].error.code` `not_found`, which means "no head" (then omit `expectedVersion`):

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
- If the config write in (b) was denied or failed, stop before (c) and (d): report that the anchor exists without a config block, and that nothing was re-parented.
- Read failures from `data.results[0].error` (call-level `partial_failure`): `config_invalid` on the push: show `detail.errors[{path, reason}]`; nothing was stored. `version_conflict`: read the head again and ask before retrying.
- If the `config` tool is not available or the call is `forbidden`, skip this step and note: "Config saved locally only — the server push was not possible; the local config.yaml is authoritative."

**(d) Batch re-parent the MOVE roots** — one `items_write` call whose `ops` array holds one `update` per root (never a sequential call per root). The example shows one element; repeat it for each MOVE root in the same call:

```to-call
{"tool": "items_write", "op": "update", "args": {"id": "<move-root-1>", "parentId": "<ANCHOR_ID>"}}
```

A 4.x server recomputes each moved subtree's descendant depths and `root_id`. Each moved tree is also repinned against the anchor's config head, which is why the config is pushed before this step (the `reparent` pin point; see [Schema pins](../../../../current/docs/config-reference.md#schema-pins)), so a tree whose items resolve a schema of a different lifecycle class under the anchor's config fails that element with `invalid_transition` and nothing is written for it. The call commits element by element: read the per-element results, report every failure by root with its `data.results[i].error.code`, and leave that root for the user to decide. The codes to expect: `not_claim_holder` (claimed by another holder: wait for the release, or have the holder release it), `forbidden` (the credential's scope does not cover the root or the anchor), `version_conflict` (re-read that root and retry it), `invalid_transition` (a lifecycle-class crossing under the anchor's config). Roots held for open runs are not in this call at all.

If the push in step (c) was skipped, tell the user that the moved trees are pinned to the server's config, and that after a later push they should run `validate` and offer `repin` per `affectedInFlight`.

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
- **Fail per element, not silently.** A root whose re-parent is refused (`not_claim_holder`, `forbidden`, `version_conflict` or `invalid_transition`) is reported by code and left in place.
- **Open runs hold a root.** A root with an open or paused run is never moved; the user closes the run first.
- **Credential scope is the user's to fix.** The plan lists every moved root id so token-file and JWT scopes can be re-pointed at the anchor before a restart.
- **No auto-rollback.** On verify mismatch, the skill reports the manual remedy rather than guessing at an undo.

---

## Verification (before first real run)

Per the specification, exercise the skill against a COPY of a production-shaped DB:

1. **Dry run on the copy** must produce zero mutations and a correct classification table.
2. **Execute on the copy** and confirm Step 5 count reconciliation passes (depth-0 roots, anchored children, grandchild depth +1).

Only after the copy passes should the skill be run against the real database.
