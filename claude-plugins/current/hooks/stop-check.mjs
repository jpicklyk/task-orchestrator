#!/usr/bin/env node
// Stop hook — health check reminder
const output = {
  hookSpecificOutput: {
    hookEventName: "Stop",
    additionalContext: `## Before Stopping — Health Check

Consider calling \`get_context()\` to check for:
- Active items (role=work or review) that may have stalled notes
- Blocked items that need attention
- Any pending required notes

This is informational — stopping is allowed regardless.`
  }
};
process.stdout.write(JSON.stringify(output));
