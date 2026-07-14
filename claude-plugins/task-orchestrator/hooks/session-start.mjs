#!/usr/bin/env node
// Session Start Hook — injects current v3 workflow guidance
const output = {
  hookSpecificOutput: {
    hookEventName: "SessionStart",
    additionalContext: `## Task Orchestrator — Session Context

- Use \`advance_item\` for role transitions — not raw status edits.
- Hierarchy: items have parentId and depth (max 3).
- To resume: call \`get_context()\` with no args to see active and stalled items.`
  }
};
process.stdout.write(JSON.stringify(output));
