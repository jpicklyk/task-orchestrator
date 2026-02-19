#!/usr/bin/env node
// Session Start Hook — injects current v3 workflow guidance
const output = {
  hookSpecificOutput: {
    hookEventName: "SessionStart",
    additionalContext: `## Current (v3) Task Orchestrator — Session Context

**Tool surface (13 tools):** manage_items, query_items, manage_notes, query_notes, manage_dependencies, query_dependencies, advance_item, get_next_item, get_blocked_items, get_next_status, create_work_tree, complete_tree, get_context

**Workflow:**
- Items progress through roles: queue → work → review → terminal
- Use \`get_context(itemId=...)\` to see expected notes and gate status before starting work
- Use \`advance_item(itemId=..., trigger="start")\` to progress — not raw status updates
- Required notes must be filled before gates allow progression

**Hierarchy:** Items have parentId and depth (max 3). Root items are depth 0.
**Compound ops:** \`create_work_tree\` for atomic hierarchy creation; \`complete_tree\` for batch completion.
**Session tip:** Call \`get_context()\` (no params) to see active and stalled items.`
  }
};
process.stdout.write(JSON.stringify(output));
