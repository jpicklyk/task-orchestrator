#!/usr/bin/env node
// SubagentStart — injects agent-owned-phase protocol
const output = {
  hookSpecificOutput: {
    hookEventName: "SubagentStart",
    additionalContext: `## Agent-Owned-Phase Protocol — Current (v3)

**You own exactly ONE phase.** Enter it, fill its notes, then return. Do NOT advance beyond your phase.

### Phase entry

1. **Enter your phase:** Call \`advance_item(transitions=[{itemId: "<your-item-UUID>", trigger: "start"}])\`
   - This moves the item into your phase (e.g., queue→work or work→review)
   - The response includes \`guidancePointer\` (authoring instructions for the first note) and \`noteProgress { filled, remaining, total }\`
   - If the item is already in your phase (applied: false), call \`get_context(itemId="<your-item-UUID>")\` instead to get guidance

### Just-in-time note progression

2. **Read guidance:** \`guidancePointer\` tells you what the schema author expects for the current note. If it references a skill, load it via the Skill tool.
3. **Do work and fill the note:** Implement what the guidance asks, then call \`manage_notes(operation="upsert", notes=[{itemId, key, role, body}])\`
   - If \`noteProgress.total\` is 1 (or absent), this was the only note — skip to step 6.
4. **Get next guidance:** Call \`get_context(itemId="<your-item-UUID>")\` — returns updated \`guidancePointer\` and \`noteProgress\`.
5. **Check if done:** If \`guidancePointer\` is null, all required notes are filled — skip to step 6. Otherwise go to step 2.

### Return

6. **Return results.** Report: (1) files changed with line counts, (2) test results summary, (3) any blockers. Do not echo back the task description.

**CRITICAL:** Do NOT call \`advance_item(trigger="start")\` a second time — that would skip your phase. Do NOT call \`advance_item(trigger="complete")\` — the orchestrator handles terminal transitions.`
  }
};
process.stdout.write(JSON.stringify(output));
