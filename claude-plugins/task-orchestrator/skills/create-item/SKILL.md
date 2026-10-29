---
name: create-item
description: "Creates an MCP work item from conversation context, anchored in the right container (an observation is a standalone root) with a declared type and its queue notes pre-filled; use when the conversation surfaces a bug, feature idea, tech debt or observation worth tracking, or the user says: track this, log this bug, create a task for, or add this to the backlog."
argument-hint: "[optional: brief description of what to create]"
---

# create-item - Container-Anchored Work Item Creation

Create work items from conversation context: anchor them in a category container, pick a declared `type`, and pre-fill the queue notes. Call shapes are in [`_generated/call-shapes.md`](../_generated/call-shapes.md); field semantics are in `current/docs/api-reference.md`. Ids in examples are 8-hex shorts; any id field also takes the full UUID. Read every answer through the envelope: `ok`, then `data.results[i]` (`ok` or `error`) and `data.summary {ok, failed}`. When a write element fails, the call-level `error` is only `partial_failure` (no detail, no fix): read `code`, `kind` (retry class), `detail` and `fix` from `data.results[i].error`. A read (`select`) call reports its failure in the call-level `error`.

## Director mode

Director mode is the session state entered by `/task-orchestrator:director` (or by running as the hardened `director` agent). Creating an item writes through `items_write` and `notes_write`, which a director does not run inline: dispatch ONE background `Agent` (`run_in_background: true`, `model` set) that runs the steps below, or `SendMessage` the run's coordinator when the new item is meant for a member of an open or paused run. Hand the agent the title, kind, priority, parent hint and whether it is an observation. It reports one line only, `Created: <title> (<short-id>) under <path>`, never note bodies. A background agent dispatched by a director is not in director mode: it runs the steps below.

## Step 1 - Infer intent

Take from the conversation (or `$ARGUMENTS`): title, kind (bug, feature, tech debt, observation, action item, general), priority (default medium), and scope (one item, or a feature with 2 or more distinct subtasks). If the title or kind is unclear, ask with `AskUserQuestion` and concrete options, never an open question.

## Step 2 - Scan containers

Resolve the scope from the SessionStart context: a `## Project Scope` section carries a project rootId (scopes reads and anchors); a `## Personal Scope` section carries a personal root (anchors only, reads stay unscoped). With no session context, read `project.rootId` from `.taskorchestrator/config.yaml` (a file read).

With a rootId, the category containers are the direct children of the root, which an anchored overview returns (`includeChildren` is not allowed with `anchorId`):

```to-call
{"tool": "items_read", "select": "overview", "args": {"anchorId": "9f3a1c22"}}
```

With no rootId, scan unscoped (`args` `{}`) and classify the result:

| Pattern | Classification |
|---|---|
| Depth-0 item with category-named children | Hierarchical: a project root exists |
| Category-named items at depth 0, no project root | Flat: use them directly |
| No items at all | Empty: hand off to `/task-orchestrator:init`, which creates the project root |

Create-item never creates a project root. After `init` has run, restart this skill.

## Step 3 - Anchor the item

| Item kind | Category | Signal words |
|---|---|---|
| Bug, error, crash | Bugs | bug, error, crash, broken, failure, exception |
| Feature, enhancement | Features | feature, add, implement, support, capability |
| Tech debt, refactor | Tech Debt | refactor, cleanup, debt, migrate, restructure |
| Observation, friction | agent-observation, a standalone root (no parent) | slow, optimize, latency, friction, gap |
| Follow-up, reminder | Action Items | todo, follow up, remind, track |
| Unclear | best match; ask if unsure | |

Use the matching category as `parentId`, except an observation, which takes no `parentId`. If it is missing, create it first with `items_write` `create` and a declared type (Step 4; `container` when the config lists it), then use its id. With only a personal root known, use it as the parent.

Process-global types such as `agent-observation` resolve from the server config wherever they sit (4.0 rule: per-root type, server type, per-root `default`, server `default`; see [Schema pins](../../../../current/docs/config-reference.md#schema-pins)), so an observation is created as a standalone depth-0 item of type `agent-observation` with no `parentId`, in line with [adopt-project-scope](../adopt-project-scope/SKILL.md). Existing items under an old Observations container stay where they are.

## Step 4 - Pick the type and traits

`type` is required on every create. Read what the root declares:

```to-call
{"tool": "config", "op": "effective", "args": {"rootId": "9f3a1c22"}}
```

`data.results[0].types` lists the declared type keys and `data.results[0].traits[].name` the declared traits (each trait is an object `{name, notes, dispatch?, resources?}`; `rootId` omitted reads the server config alone). `data.results[0].schemas[]` carries each type's `notes` and `defaultTraits`; keep them for Step 5. When types are declared, a create with any other type fails `schema_violation` and `data.results[i].error.detail.availableTypes` lists the valid ones; a blank type always fails. A type with a schema activates gates; one without is schema-free.

Match the context to a declared type (a bug to the bug type, a feature to the feature type, and so on). With no confident match use the `default` type when it is listed; otherwise ask via `AskUserQuestion` with the declared types as options. When the config declares no types at all, any non-blank type is accepted and the item is schema-free.

Traits are an array: `"traits": ["needs-migration-review"]`. Assign only declared traits that fit the context:

| Context signal | Trait to consider (if declared) |
|---|---|
| Database migration, ALTER TABLE | `needs-migration-review` |
| Tool parameter or response shape change | `needs-api-compat-review` |
| Plugin skill or hook behaviour change | `needs-plugin-update` |
| Auth, input validation, external data | `needs-security-review` |
| Hot path, per-request work | `needs-perf-review` |

A trait that carries an `exclusive` resource (the default mode; `advisory` is recorded only, never leased) makes the item take a lease on entering work; `advance transition start` can then fail `resource_unavailable` (transient). Mention it to the user; no action is needed here.

## Step 5 - Create

Dedup first (a tree's root only; children inherit): one unscoped search. It stays unscoped because legacy depth-0 items and items under other roots would be missed by an `ancestorId` scope.

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "retry backoff", "kinds": ["item"], "limit": 5}}
```

Show close matches as one FYI line (role and short id each). The user decides; never auto-link, auto-skip or auto-cancel.

Draft the queue notes first, from the `config effective` answer of Step 4: for the chosen type take the notes of its `schemas[]` entry, then the notes of its traits (the item's `traits`, then the schema's `defaultTraits`). Every note with `role: "queue"` that ANY declaration marks `required` is owed; the base declaration is read first and the first declaration of a key supplies its guidance. Draft each body from the conversation using that note's `guidance` (and run its `skill` if set). Omit a note the conversation cannot fill; never write a blank or invented body (no `createNotes`, which would write a blank row for every key, seat-owned keys included).

Create the item and its notes in ONE `create_tree` call, which commits all or nothing. A single item (bug, standalone task) is a `root` only, with no `children`; an observation is the same call without `parentId`, tagged `agent-observation` plus one kind tag. Each `notes[]` entry names its item by `itemRef`: `root` or a child `ref`.

```to-call
{"tool": "items_write", "op": "create_tree", "args": {"root": {"title": "Retry on 503", "type": "bug-fix", "priority": "high", "summary": "Client gives up on the first 503.", "tags": ["backend"]}, "parentId": "9f3a1c22", "notes": [{"itemRef": "root", "key": "diagnosis", "role": "queue", "body": "Client treats 503 as fatal; no retry path."}]}}
```

Work tree (a feature with 2 or more named subtasks); every child needs a `type`:

```to-call
{"tool": "items_write", "op": "create_tree", "args": {"root": {"title": "Add retry layer", "type": "feature-implementation", "priority": "medium"}, "parentId": "9f3a1c22", "children": [{"ref": "t1", "title": "Backoff policy", "type": "feature-task"}, {"ref": "t2", "title": "Retry tests", "type": "feature-task"}], "deps": [{"from": "t1", "to": "t2"}], "notes": [{"itemRef": "t1", "key": "task-scope", "role": "queue", "body": "Exponential backoff with jitter on 503 and 429."}]}}
```

Default to a single item when scope is unclear. A tree answers `{ok, id, version, items, deps, notes}` with `items[]` `{ref, id, depth}` and `notes[]` `{itemRef, key, role, id, warning?}`; a `warning` on a note means the body exceeded its `maxLength` (trim it and upsert again in Step 6).

## Step 6 - Verify the queue notes

The Step 5 drafts came from the root's config; the item's own schema is authoritative because it merges the item's traits. Read it once per created item:

```to-call
{"tool": "items_read", "select": "schema", "args": {"itemId": "4b7d0e19", "fields": ["guidance"]}}
```

`data` is `{schemaFree: true}` for a schema-free item (nothing more to fill). Otherwise compare the required `role: "queue"` notes with the keys Step 5 wrote; for each still missing, draft the body from the conversation using that note's `guidance` and write them all in one `notes_write` call, one `upsert` element per note (and per item, when several items were created):

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "4b7d0e19", "key": "diagnosis", "role": "queue", "body": "Client treats 503 as fatal; no retry path."}}
```

If the conversation is too thin for a real body, leave the note unwritten rather than fabricate (a blank body counts as missing at the gate). A `schema_pinned_conflict` on a write means the item's pin lacks a key the current config requires: `repin` it (see Troubleshooting).

## Step 7 - Report

```
Created: <title> (<short-id>)
  Path: <container path, e.g. Features, or "standalone root" for an observation>
  Tags: <tags or none>   Traits: <traits or none>
  Notes written: <keys or none>
```

Add `  Created new container: <name> under <parent>` when a category was created.

## From a workflow findings proposal

An alternate entry for materializing findings from a `task-orchestrator:audit` run. The audit script never writes items; it returns an `audit/result-v1` result with a `proposal` (candidate rows in `proposal.items[]`) and `kept[]` (surviving findings). Use this path instead of Steps 1-3 when the input is a proposal.

1. **Table.** Show one table: id, severity, class, type, materialize and reason, candidates.
2. **Dedup.** Row `candidates` come from the unscoped Step 5 search the audit already ran; cite them. Re-search only rows with `likelyDuplicate: unknown` or rows the user added or retitled. Candidates are FYI only.
3. **Edits.** The user may flip `materialize`, reclassify a row (bug, tech-debt, agent-observation) or drop it. Apply nothing without an explicit edit.
4. **Container.** Resolve ONE category container with Steps 2-3 and have the user confirm it. Non-observation rows go under a single audit container created beneath it.
5. **Priority and type.** Priority is already mapped; do not re-derive it. Use a row's own `type`; for rows without one, pick from `config effective` `types` (Step 4).
6. **Materialize** only after explicit confirmation: one `create_tree` for the audit container plus its children under the category container (split a larger proposal across calls; later calls attach with `root.id`). `agent-observation` rows are not part of this tree: create them as separate `items_write` `create` elements tagged `agent-observation` plus exactly one kind tag, and no `parentId`.
7. **Notes.** None written from the proposal; queue notes are filled later in normal planning.
8. **Report** with Step 7's format, one line per created item.

Never automated: creating without explicit confirmation, acting on dedup candidates, or materializing a `materialize: false` row the user did not flip.

## Troubleshooting

**Wrong container.** Move the item:

```to-call
{"tool": "items_write", "op": "update", "args": {"id": "4b7d0e19", "parentId": "6e21c0aa"}}
```

**`schema_violation` on create.** Read `data.results[i].error.detail.availableTypes` (or `availableTraits`) and retry with a declared name.

**Schema read says `schemaFree: true` for a typed item.** The type resolves to no schema under the item's pin. An item keeps its pin after a config push (`items_read` `get` shows `stale: true`); `repin` adopts the push:

```to-call
{"tool": "items_write", "op": "repin", "args": {"id": "4b7d0e19"}}
```

Changing `type` or `traits` with `items_write` `update` is itself a pin point; `repin` is for a push made after the pin.
