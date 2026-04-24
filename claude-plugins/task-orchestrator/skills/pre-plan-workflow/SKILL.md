---
name: pre-plan-workflow
description: "Gathers existing MCP work items, note schemas, and gate requirements to establish a definition floor before planning begins. Queries get_context() for active, blocked, and stalled items, reads .taskorchestrator/config.yaml for schema-defined note requirements per phase, and structures the plan so each task maps to one MCP work item with correct dependency ordering. Use when entering plan mode, starting implementation planning, creating a task breakdown, or beginning project setup for any non-trivial implementation task. Triggered automatically by the plan-mode hook."
user-invocable: false
---

# Pre-Plan Workflow — Definition Floor

When entering plan mode, use the MCP to set the **definition floor** before writing your plan.

The definition floor is the baseline of existing work, documentation requirements, and gate constraints that the plan must account for.

**If MCP is unreachable:** Proceed with planning based on conversation context. Note in the plan that MCP state could not be verified — existing work may overlap. Re-check after MCP reconnects.

## Step 1: Check Existing MCP State

Call the health check to see what's already tracked:

```
get_context()
```

**If active or stalled items exist:**
- Identify items related to the current request — avoid planning work that duplicates what's already tracked
- For each relevant active item, call `get_context(itemId=...)` to inspect:
  - **Note schema** — which notes are expected for this item's tags
  - **Gate status** — which required notes are filled vs. missing, and whether the item can advance
  - **Guidance pointer** — authoring guidance for the first unfilled required note

**If no items exist (clean slate):**
- The definition floor is simply "no existing MCP state to account for"
- Proceed with planning, but still check Step 2 for schema awareness

## Step 2: Discover Note Schema Requirements

Read `.taskorchestrator/config.yaml` in the project root (this is a file read, not an MCP call):

- If the file exists, list the discovered schemas and their required notes per phase
- Schemas are defined under `work_item_schemas:` (preferred) or `note_schemas:` (legacy)
- Each schema key (e.g., `feature-implementation`, `bug-fix`) is a **type identifier** — set it as the item's `type` field to activate gate enforcement. Tags can be used for additional categorization but are no longer the primary schema activator.
- Required queue-phase notes define what documentation must exist before work starts
- Required work-phase notes define what must be captured during implementation
- Use `guidancePointer` values from `get_context(itemId=...)` on existing items to understand how to author each note

If no config file exists, the project has no note schemas — items will be schema-free with no gate enforcement. Proceed with planning normally.

**Minimal config example:**

```yaml
work_item_schemas:
  feature-task:
    notes:
      - key: requirements
        role: queue
        required: true
      - key: implementation-notes
        role: work
        required: true
```

**Use schemas to inform the plan:** When a schema applies, each planned task should note which schema type will be applied at materialization (e.g., `type: "feature-task"`) and produce content that maps to required note keys.

## Step 3: Plan with MCP Awareness

Structure the plan knowing it will be materialized into MCP items after approval:

- Each planned task should map to **one work item** with clear boundaries — a single unit of work a subagent can own
- Account for **dependency ordering** — which tasks block others (these become `BLOCKS` edges)
- Consider the **hierarchy** — a root container item with child task items is the standard pattern

## Continue with Plan Mode

The prerequisite is complete. Now proceed with plan mode's normal workflow — explore the codebase, understand existing patterns, and design your implementation approach. Use the definition floor from Steps 1-3 to inform your plan.

Once the plan is approved, the post-plan hook will guide you through materialization and implementation dispatch. Do not materialize before approval.
