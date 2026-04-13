---
name: post-plan-workflow
description: Internal workflow for post-plan materialization — creates MCP items from the approved plan and dispatches implementation. Triggered automatically after plan approval when MCP tracking is active.
user-invocable: false
---

# Post-Plan Workflow — Materialize and Implement

Plan approval is the green light for the full pipeline. Proceed through all three phases without stopping.

## Phase 1: Materialize

Complete materialization **before** any implementation begins.

1. **Create MCP items** from the approved plan using `create_work_tree` (preferred for structured work with dependencies) or `manage_items` (for individual items). Apply appropriate schema tags based on the plan and the project's `.taskorchestrator/config.yaml` — this activates gate enforcement for each item. If the config defines separate schemas for containers vs. child tasks, apply the appropriate tag at each level.
2. **Wire dependency edges** between items — use `BLOCKS` for sequencing, `fan-out`/`fan-in` patterns for parallel work
3. **Check `expectedNotes` in create responses** — if the item's tags match a schema, the response includes the expected note keys and phases. Fill required queue-phase notes (`requirements`, `acceptance-criteria`, etc.) with content from the plan before advancing.
4. **Verify all item UUIDs exist** — confirm the full item graph is materialized before proceeding

**If `create_work_tree` fails:** Check partial state with `query_items(operation='overview')`. Delete partial items with `manage_items(delete, recursive=true)` and retry.

Do NOT dispatch implementation agents until materialization is complete. Agents need MCP item UUIDs to self-report progress.

## Phase 2: Implement

Dispatch subagents to execute the plan:

- Each subagent **owns one MCP item** — include the item UUID in the delegation prompt
- If `expectedNotes` entries include `guidance`, embed it in the delegation prompt as authoring instructions when filling notes
- If `expectedNotes` entries include a `skill` field, include in the delegation prompt: "Before filling the `<key>` note, invoke `/<skill>` and follow its framework." This ensures subagents receive deterministic skill routing rather than relying on guidance prose
- **Agents own phase entry only** — each agent calls `advance_item(trigger="start")` once to enter work phase, fills work-phase notes, and returns. The orchestrator handles all further transitions (work→review or work→terminal depending on schema). Agents do NOT call `advance_item` a second time
- Fill work-phase notes (`implementation-notes`, `test-results`, etc.) as the agent works
- Respect dependency ordering — do not dispatch an agent for a blocked item until its blockers complete
- **Between waves:** call `get_blocked_items(parentId=...)` to confirm upstream items completed — dependency gating implicitly verifies agents transitioned their items. If downstream items are still blocked, investigate the upstream blocker
- **Do not** call `advance_item` or `complete_tree` for terminal transitions on items delegated to agents — the orchestrator reviews and advances to terminal after agents return

Do NOT use `AskUserQuestion` between phases — proceed autonomously.

## Phase 3: Verify

After all agents complete:

1. Run `query_items(parentId=..., role="work")` — any results are items agents failed to transition. Use `/status-progression` to diagnose and manually advance stuck items
2. Run `get_context()` health check to see what completed, what stalled, and what needs attention
3. Review any stalled items — check which notes are missing with `get_context(itemId=...)`
4. Address blockers or incomplete work as needed

## Workflow Complete

The post-plan workflow is done. Report the final status to the user — what completed, what needs attention, and any items still in progress.
