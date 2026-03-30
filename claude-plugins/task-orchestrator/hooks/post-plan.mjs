#!/usr/bin/env node
// PostToolUse ExitPlanMode — triggers the post-plan-workflow skill
const output = {
  hookSpecificOutput: {
    hookEventName: "PostToolUse",
    additionalContext: `## NEXT STEP — Required before implementation

The plan has been approved. You MUST invoke the \`task-orchestrator:post-plan-workflow\` skill NOW to materialize MCP items and dispatch implementation.

Do not begin any implementation work until this completes.`
  }
};
process.stdout.write(JSON.stringify(output));
