#!/usr/bin/env node
// PostToolUse ExitPlanMode — injects post-approval reminder
const output = {
  hookSpecificOutput: {
    hookEventName: "PostToolUse",
    additionalContext: `## Plan Approved — Current (v3) Next Steps

Plan approval is the green light for the full pipeline. Proceed without stopping:

1. **Materialize:** Create MCP containers (items, dependencies) — complete before implementation
2. **Implement:** Dispatch subagents with MCP item UUIDs. Each agent must call advance_item(trigger="start") at the start and advance_item(trigger="complete") at the end.
3. **Verify:** Run \`get_context()\` health check after all agents complete.

Do NOT use AskUserQuestion between phases — proceed autonomously.`
  }
};
process.stdout.write(JSON.stringify(output));
