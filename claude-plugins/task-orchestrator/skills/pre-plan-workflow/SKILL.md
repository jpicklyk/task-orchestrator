---
name: pre-plan-workflow
description: "Internal, hook-triggered when entering plan mode: reads existing MCP items and the declared schemas so the plan accounts for tracked work and gate requirements."
user-invocable: false
---

# Pre-Plan Workflow - Definition Floor

On entering plan mode, set the **definition floor** before writing the plan: the baseline of existing work, note requirements and gates the plan must account for. Shapes are in [`_generated/call-shapes.md`](../_generated/call-shapes.md); ids below are 8-hex shorts and any id field also takes the full UUID.

**If MCP is unreachable:** plan from conversation context, note in the plan that MCP state could not be verified (existing work may overlap), and re-check after it reconnects.

## Step 1: Check existing MCP state

Resolve the scope from the SessionStart context: a `## Project Scope` section carries a project rootId (scopes reads); a `## Personal Scope` section carries a personal root (anchors only, reads stay unscoped). With no session context, read `project.rootId` from `.taskorchestrator/config.yaml` (a file read).

Health check, scoped to the project when a rootId is known (omit `ancestorId` otherwise):

```to-call
{"tool": "items_read", "select": "context", "args": {"ancestorId": "9f3a1c22"}}
```

`data` is `{active, blocked, stalled, claimSummary}`; `stalled` lists active items with unfilled required notes (`missing[]`, `guidanceKey`, `skillPointer`). If active, blocked or stalled items relate to the request, avoid planning duplicate work and inspect each one:

```to-call
{"tool": "items_read", "select": "context", "args": {"itemId": "4b7d0e19"}}
```

Read `schema[]` (each note's `exists` and `filled`), `gateStatus` (`canAdvance`, `missing`, `blockedBy`) and `guidanceKey`; resolve the authoring guidance with `items_read` `schema` and `fields: ["guidance"]`. With nothing tracked, the floor is simply "no existing state"; still do Step 2.

## Step 2: Discover declared schemas

Read the resolved config, not the file:

```to-call
{"tool": "config", "op": "effective", "args": {"rootId": "9f3a1c22"}}
```

`config` is an ops tool, so the answer is `data.results[0]`: `types` lists the declared types, `schemas` their notes per phase (required queue notes must exist before work starts; required work notes are captured during implementation), and `traits` the declared traits, each an object with a `name`. Each type key is what an item sets as `type`; a create must pass a declared `type`. If the config tool is unavailable, read `.taskorchestrator/config.yaml` as an offline fallback (types are the keys under `work_item_schemas:`). With no declared schemas, items are schema-free and nothing is gated.

Use the result to inform the plan. Each planned task should:

- name the declared `type` it will be created with (for example `feature-task`);
- produce content that maps onto that type's required note keys;
- list its dependencies by reason, so post-plan can wire only the semantic ones (see post-plan-workflow, "Dependencies").

## Step 3: Plan with MCP awareness

Structure the plan knowing it will be materialized after approval:

- each task maps to one work item with clear boundaries, a unit one subagent can own;
- a root container item with child task items is the standard shape;
- order tasks by real dependency, not by file overlap.

## Continue with plan mode

The prerequisite is done. Proceed with plan mode's normal workflow: explore, design, write the plan. Once it is approved the post-plan hook guides materialization. Do not materialize before approval.
