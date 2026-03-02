#!/usr/bin/env node
// PostToolUse ExitPlanMode — triggers the post-plan-workflow skill
const output = {
  hookSpecificOutput: {
    hookEventName: "PostToolUse",
    additionalContext: `## Plan Approved — MCP Task Orchestrator

Invoke the \`task-orchestrator:post-plan-workflow\` skill for the full post-approval workflow. It covers materialization, implementation dispatch, and verification.`
  }
};
process.stdout.write(JSON.stringify(output));
