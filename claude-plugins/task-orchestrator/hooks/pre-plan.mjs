#!/usr/bin/env node
// PreToolUse EnterPlanMode — triggers the pre-plan-workflow skill
const output = {
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    additionalContext: `## Planning Mode — MCP Task Orchestrator

Invoke the \`task-orchestrator:pre-plan-workflow\` skill for the full planning workflow. It will guide you through checking existing MCP state to set the definition floor before writing your plan.`
  }
};
process.stdout.write(JSON.stringify(output));
