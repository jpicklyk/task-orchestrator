#!/usr/bin/env node
// SubagentStart hook: Injects MCP workflow discipline into every subagent's context.

const context = `MCP Workflow Rules:

STATUS — Use request_transition(trigger=start|complete|cancel|block|hold). Default task flow: start (pending->in-progress) + complete (in-progress->completed) = 2 calls. If complete fails with "Cannot skip statuses", use start to advance first, then retry complete. For non-default flows (tags like qa-required, hotfix, docs), see the status-progression skill for flow tables. For batch status changes, use the transitions array parameter. NEVER use manage_container update with status fields — use request_transition instead for cascade detection.

POST-TRANSITION — Check response for cascadeEvents (advance parent entities), unblockedTasks (newly available downstream work), and flow context (activeFlow, flowSequence, flowPosition). Act on cascades and unblocked tasks.

CREATION — manage_container create/update/delete all use arrays (containers array for create/update, ids array for delete). Include templateIds when creating tasks/features for structured workflows — the tool warns if templates are missing.

COMPLETION — Tasks: summary populated, dependencies resolved, required sections filled. Features: all child tasks terminal. Projects: all features completed.

DEPENDENCIES — Use manage_dependencies with a dependencies array or pattern shortcuts (linear, fan-out, fan-in) for batch creation. Never create dependencies one at a time in a loop.

CLEANUP — When a feature reaches terminal status, its child tasks are automatically deleted (except bug/bugfix/fix/hotfix/critical tagged). Export the feature before completing if task content needs to be preserved.`;

console.log(JSON.stringify({
  hookSpecificOutput: {
    hookEventName: "SubagentStart",
    additionalContext: context
  }
}));
