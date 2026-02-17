#!/usr/bin/env node
// SubagentStart hook: Injects MCP workflow discipline into every subagent's context.

const context = `MCP Workflow Rules:

STATUS — Use request_transition(trigger=start|complete|cancel|block|hold). Default task flow: start (pending->in-progress) + complete (in-progress->completed) = 2 calls. If complete fails with "Cannot skip statuses", use start to advance first, then retry complete. For non-default flows (tags like qa-required, hotfix, docs), see the status-progression skill for flow tables. For batch status changes, use the transitions array parameter. NEVER use manage_container update with status fields — use request_transition instead for cascade detection.

ROLES — Transition responses include previousRole and newRole (queue, work, review, blocked, terminal). Use role changes to select behavior: queue→work = task pickup, work→review = trigger validation, review→terminal = archive artifacts. Use query_role_transitions to inspect transition audit trails for any entity.

POST-TRANSITION — Check response for cascadeEvents (advance parent entities), unblockedTasks (newly available downstream work), flow context (activeFlow, flowSequence, flowPosition), and previousRole/newRole (role phase annotations). Act on cascades and unblocked tasks.

CREATION — manage_container create/update/delete all use arrays (containers array for create/update, ids array for delete). Include templateIds when creating tasks/features for structured workflows — the tool warns if templates are missing.

COMPLETION — Tasks: summary populated, dependencies resolved, required sections filled. Features: all child tasks terminal. Projects: all features completed.

DEPENDENCIES — Use manage_dependencies with a dependencies array or pattern shortcuts (linear, fan-out, fan-in) for batch creation. Never create dependencies one at a time in a loop. Set unblockAt per dependency to control when it unblocks: "work" (blocker enters active work), "review" (enters validation), or null (default: terminal).

CLEANUP — When a feature reaches terminal status, its child tasks are automatically deleted (except bug/bugfix/fix/hotfix/critical tagged). Export the feature before completing if task content needs to be preserved.`;

console.log(JSON.stringify({
  hookSpecificOutput: {
    hookEventName: "SubagentStart",
    additionalContext: context
  }
}));
