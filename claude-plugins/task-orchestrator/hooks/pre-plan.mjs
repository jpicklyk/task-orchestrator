#!/usr/bin/env node
// PreToolUse EnterPlanMode — triggers the pre-plan-workflow skill
const output = {
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    additionalContext: `## PREREQUISITE — Complete before exploring or planning

You MUST invoke the \`task-orchestrator:pre-plan-workflow\` skill BEFORE starting any codebase exploration or plan writing.

Do this now. Do not begin exploring, reading code, or drafting your plan until this prerequisite completes.`
  }
};
process.stdout.write(JSON.stringify(output));
