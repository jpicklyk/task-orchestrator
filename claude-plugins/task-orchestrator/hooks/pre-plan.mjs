#!/usr/bin/env node
// PreToolUse EnterPlanMode — triggers the pre-plan-workflow skill
const output = {
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    additionalContext: `## Planning Mode — MCP Task Orchestrator

Invoke the \`task-orchestrator:pre-plan-workflow\` skill for the full planning workflow. It will guide you through checking existing MCP state to set the definition floor before writing your plan.

Note: If the work is Direct tier (1-2 files, known fix, no migration), you should not be in plan mode. Consider exiting plan mode and implementing directly.`
  }
};
process.stdout.write(JSON.stringify(output));
