---
name: post-plan-workflow
description: "Internal, hook-triggered: materializes MCP items from the approved plan and dispatches implementation."
user-invocable: false
---

# Post-Plan Workflow — Materialize and Implement

Plan approval is the green light for the full pipeline. Proceed through all three phases without stopping.

## Phase 1: Materialize

Complete materialization **before** any implementation begins.

**Prefer a stashed docRef over re-authoring note bodies.** On HTTP+REST workspaces, the `plan-capture` hook stashes the just-approved plan as a plan document as ExitPlanMode fires, and reports the slug via `additionalContext` (`Task Orchestrator: plan stashed as plan document '<slug>' (root <rootId>)`). When that context is present, or `manage_plan_documents(operation="list", rootId=..., status="pending")` confirms a pending document for this root, use it as the source of truth for materialization — quote/reference its content instead of retyping the plan into note bodies. Fall back to the plan text already in context when no stashed doc exists (stdio setups, or the hook failed open).

1. **Create MCP items** from the approved plan using `create_work_tree` (preferred for structured work with dependencies) or `manage_items` (for individual items). Apply appropriate schema tags based on the plan and the project's `.taskorchestrator/config.yaml` — this activates gate enforcement for each item. If the config defines separate schemas for containers vs. child tasks, apply the appropriate tag at each level.
   - **Anchor the root under the project when known:** resolve the project rootId from session context (injected by the SessionStart hook) or `.taskorchestrator/config.yaml`'s `project.rootId`. When known, set the new root item's `parentId` to that rootId (directly, or to the appropriate category container beneath it if one already exists) so materialized work lands inside the project's tree instead of at a bare depth 0. When no rootId is known, create at depth 0 as before.
2. **Wire dependency edges** between items — use `BLOCKS` for sequencing, `fan-out`/`fan-in` patterns for parallel work
3. **Check `expectedNotes` in create responses** — if the item's tags match a schema, the response includes the expected note keys and phases. Fill required queue-phase notes (`feature-summary`, `task-scope`, etc.) with content from the plan before advancing.
   - **`feature-implementation` root:** keep its `feature-summary` note lean — goal (2-3 sentences), a findings→tasks table mapping plan findings to the child items just created, dependency edges between those children, and a pointer to non-goals (target under 2k chars). Put full alternatives/blast-radius/risk-flags/test-strategy detail in each child's `task-scope` note instead — that's where `/spec-quality`'s full bar applies.
4. **Verify all item UUIDs exist** — confirm the full item graph is materialized before proceeding

**If `create_work_tree` fails:** Check partial state with `query_items(operation='overview')`. Delete partial items with `manage_items(operation="delete", itemIds=["<uuid>"], recursive=true)` and retry.

Do NOT dispatch implementation agents until materialization is complete. Agents need MCP item UUIDs to self-report progress.

## Phase 2: Implement

Dispatch subagents to execute the plan:

- Each subagent **owns one MCP item** — include the item UUID in the delegation prompt
- Resolve each note's `guidance`/`skill` via `query_items(operation="schema", itemId=...)` (`expectedNotes` itself is keys-only); embed `guidance` in the delegation prompt as authoring instructions
- When a note's `skill` is set, include in the delegation prompt: "Before filling the `<key>` note, invoke `/<skill>` and follow its framework." This ensures subagents receive deterministic skill routing rather than relying on guidance prose
- **Agents own phase entry only** — each agent calls `advance_item(trigger="start")` once to enter work phase, fills work-phase notes, and returns. The orchestrator handles all further transitions (work→review or work→terminal depending on schema). Agents do NOT call `advance_item` a second time
- Fill work-phase notes (`implementation-notes`, `session-tracking`, etc.) as the agent works
- Respect dependency ordering — do not dispatch an agent for a blocked item until its blockers complete
- **Between waves:** call `get_blocked_items(ancestorId="<featureRootId>")` to confirm upstream items completed — dependency gating implicitly verifies agents transitioned their items. `ancestorId` catches blockers anywhere in the feature's subtree (not just direct children, which `parentId` alone would miss). If downstream items are still blocked, investigate the upstream blocker
- **Do not** call `advance_item` or `complete_tree` for terminal transitions on items delegated to agents — the orchestrator reviews and advances to terminal after agents return

Do NOT use `AskUserQuestion` between phases — proceed autonomously.

## Phase 3: Verify

After all agents complete:

1. Run `query_items(operation="search", parentId=..., role="work")` — any results are items agents failed to transition. Use `/status-progression` to diagnose and manually advance stuck items
2. Run `get_context()` health check to see what completed, what stalled, and what needs attention
3. Review any stalled items — check which notes are missing with `get_context(itemId=...)`
4. Address blockers or incomplete work as needed

## Workflow Complete

The post-plan workflow is done. Report the final status to the user — what completed, what needs attention, and any items still in progress.
