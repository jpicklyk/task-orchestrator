---
name: post-plan-workflow
description: Internal workflow for post-plan materialization — creates MCP items from the approved plan and dispatches implementation.
user-invocable: false
---

# Post-Plan Workflow — Materialize and Implement

Plan approval is the green light for the full pipeline. Proceed through all three phases without stopping.

## Phase 1: Materialize

Complete materialization **before** any implementation begins.

1. **Create MCP items** from the approved plan using `create_work_tree` (preferred for structured work with dependencies) or `manage_items` (for individual items). Apply appropriate schema tags (e.g., `tags: "feature-implementation"`) based on the plan — this activates gate enforcement for the item.
2. **Wire dependency edges** between items — use `BLOCKS` for sequencing, `fan-out`/`fan-in` patterns for parallel work
3. **Check `expectedNotes` in create responses** — if the item's tags match a schema, the response includes the expected note keys and phases. Fill required queue-phase notes (`requirements`, `acceptance-criteria`, etc.) with content from the plan before advancing.
4. **Verify all item UUIDs exist** — confirm the full item graph is materialized before proceeding

Do NOT dispatch implementation agents until materialization is complete. Agents need MCP item UUIDs to self-report progress.

## Phase 2: Implement

Dispatch subagents to execute the plan:

- Each subagent **owns one MCP item** — include the item UUID in the delegation prompt
- Each agent must call `advance_item(trigger="start")` when beginning work
- Each agent must call `advance_item(trigger="complete")` when done (or `trigger="start"` to advance through intermediate phases if the item has review-phase notes)
- Fill work-phase notes (`implementation-notes`, `test-results`, etc.) as the agent works
- Respect dependency ordering — do not dispatch an agent for a blocked item until its blockers complete

Do NOT use `AskUserQuestion` between phases — proceed autonomously.

## Phase 3: Verify

After all agents complete:

1. Run `get_context()` health check to see what completed, what stalled, and what needs attention
2. Review any stalled items — check which notes are missing with `get_context(itemId=...)`
3. Address blockers or incomplete work as needed
