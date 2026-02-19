#!/usr/bin/env node
// PreToolUse EnterPlanMode — injects planning workflow guidance
const output = {
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    additionalContext: `## Planning Mode — Current (v3) Workflow

Before implementing, call \`get_context(itemId=<activeItemId>)\` to:
- See the note schema for this item's tags
- Check gate status (which required notes must be filled)
- Get the guidance pointer for authoring notes

**Planning workflow:**
1. Create items with \`manage_items\` or \`create_work_tree\`
2. Check \`get_context\` for expected notes per item
3. Fill required notes with \`manage_notes\`
4. Advance with \`advance_item(trigger="start")\` — gate enforcement will verify notes

**After plan approval:** Materialize containers, then dispatch implementation agents with MCP item UUIDs.`
  }
};
process.stdout.write(JSON.stringify(output));
