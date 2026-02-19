#!/usr/bin/env node
// SubagentStart — injects implementation agent guidance
const output = {
  hookSpecificOutput: {
    hookEventName: "SubagentStart",
    additionalContext: `## Implementation Agent — Current (v3) Protocol

**Required actions for implementation agents:**
1. Call \`advance_item(trigger="start")\` on your assigned item UUID at the beginning
2. Do the work
3. Fill any required notes with \`manage_notes(operation="upsert")\`
4. Call \`advance_item(trigger="complete")\` when done (gate enforcement will verify notes)

**Returning results:** Report files changed (with line counts), test results, and any blockers. Do not echo back the task description.`
  }
};
process.stdout.write(JSON.stringify(output));
