---
name: post-plan-workflow
description: "Internal, hook-triggered: materializes MCP items from the approved plan and dispatches implementation."
user-invocable: false
---

# Post-Plan Workflow — Materialize and Implement

Plan approval is the green light for the full pipeline. Proceed through all three phases without stopping; the one expected stop is the turn that ends when a run-wave Method A launch hands control to the async notification (see Phase 2, Route).

## Phase 1: Materialize

Complete materialization **before** any implementation begins.

**Prefer a stashed docRef over re-authoring note bodies.** On HTTP+REST workspaces, the `plan-capture` hook stashes the just-approved plan as a plan document as ExitPlanMode fires, and reports the slug via `additionalContext` (`Task Orchestrator: plan stashed as plan document '<slug>' (root <rootId>)`). When that context is present, or `manage_plan_documents(operation="list", rootId=..., status="pending")` confirms a pending document for this root, use it as the source of truth for materialization — quote/reference its content instead of retyping the plan into note bodies. Fall back to the plan text already in context when no stashed doc exists (stdio setups, or the hook failed open).

1. **Create MCP items** from the approved plan using `create_work_tree` (preferred for structured work with dependencies) or `manage_items` (for individual items). Apply appropriate schema tags based on the plan and the project's `.taskorchestrator/config.yaml` — this activates gate enforcement for each item. If the config defines separate schemas for containers vs. child tasks, apply the appropriate tag at each level.
   - **Anchor the root under the project when known:** resolve the project rootId from session context (injected by the SessionStart hook) or `.taskorchestrator/config.yaml`'s `project.rootId`. When known, set the new root item's `parentId` to that rootId (directly, or to the appropriate category container beneath it if one already exists) so materialized work lands inside the project's tree instead of at a bare depth 0. When no rootId is known, create at depth 0 as before.
   - **Dedup check before each new root or feature item** (children inherit their parent's check): run ONE unscoped `query_items(operation="search", query="<title key terms>", limit=5)`. Unscoped on purpose — depth-0 process-global items such as agent-observations sit outside any project ancestor, so an `ancestorId`-scoped search misses them. Show close matches as one FYI line (role + short id each) and keep materializing — the user decides whether to act; never auto-link, auto-skip or auto-cancel.
2. **Wire dependency edges** between items — use `BLOCKS` for sequencing, `fan-out`/`fan-in` patterns for parallel work
3. **Check `expectedNotes` in create responses** — if the item's tags match a schema, the response includes the expected note keys and phases. Fill required queue-phase notes (`feature-summary`, `task-scope`, etc.) with content from the plan before advancing.
   - **`feature-implementation` root:** keep its `feature-summary` note lean — goal (2-3 sentences), a findings→tasks table mapping plan findings to the child items just created, dependency edges between those children, and a pointer to non-goals (target under 2k chars). Put full alternatives/blast-radius/risk-flags/test-strategy detail in each child's `task-scope` note instead — that's where `/spec-quality`'s full bar applies.
4. **Verify all item UUIDs exist** — confirm the full item graph is materialized before proceeding

**If `create_work_tree` fails:** Check partial state with `query_items(operation='overview')`. Delete partial items with `manage_items(operation="delete", itemIds=["<uuid>"], recursive=true)` and retry.

Do NOT dispatch implementation agents until materialization is complete. Agents need MCP item UUIDs to self-report progress.

## Phase 2: Implement

### Route: run-wave or hand-dispatch

Hand off to `/task-orchestrator:run-wave` only when ALL four conditions hold; each is a concrete probe:

1. **Two or more unblocked leaf items.** At least two leaf items (no children) were materialized and are unblocked: they are absent from `get_blocked_items(ancestorId=<materialized root>)`.
2. **A rootId resolves** (project or personal), in the order run-wave Step 1 uses: session context (`Active project:` or `Personal root:` line), else `project.rootId` from the file on that context's `Config:` line, else `.taskorchestrator/config.yaml`.
3. **Queue notes are filled.** Each of those leaves has its required queue notes filled (`get_context(itemId)` shows no missing queue notes) or is schema-free (the response has no `noteSchema`).
4. **Protocol rules are served.** `query_rules(operation="list", rootId)` lists `protocol.entry-seat`, `protocol.in-phase-seat` and `protocol.read-only-agent`. A seed made earlier this session (by init or the run-wave F3 repair) shows up in this list.

If all four hold, invoke `/task-orchestrator:run-wave <materialized root id>` and follow it. Plan approval counts as run-wave's Checkpoint 1. When a degradation forces a human look (`meta.excluded` or `meta.degradations` non-empty, or an empty run), show the `explain` table and end the turn; never use `AskUserQuestion`. A Method A launch ending the turn is expected. run-wave's post-run replaces Phase 3 below, which then applies only to the hand-dispatch path.

If any condition fails, hand-dispatch as described below and add one line naming the failed condition(s); for conditions 2 and 4, point at `/task-orchestrator:init`.

### Hand-dispatch

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
