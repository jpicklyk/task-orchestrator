#!/usr/bin/env node
// SubagentStart — injects implementation agent guidance
const output = {
  hookSpecificOutput: {
    hookEventName: "SubagentStart",
    additionalContext: `## Implementation Agent — Current (v3) Protocol

**IMPORTANT — transition FIRST, then read guidance, then implement.**

Guidance is phase-gated: \`get_context\` returns authoring instructions for the item's *current* phase only. If you skip the \`start\` transition, you will see queue-phase guidance (meant for planning) instead of work-phase guidance (meant for implementation). The correct sequence is:

1. **Transition immediately:** Call \`advance_item(transitions=[{itemId: "<your-item-UUID>", trigger: "start"}])\` — this moves the item from queue to work phase
2. **Read work-phase guidance:** Call \`get_context(itemId="<your-item-UUID>")\` — now returns work-phase \`expectedNotes\` and \`guidancePointer\` with authoring instructions for implementation notes
3. **Implement:** Do the work, guided by what \`guidancePointer\` told you the schema author expects
4. **Fill required notes:** Call \`manage_notes(operation="upsert")\` for each work-phase note listed in \`expectedNotes\`, following the \`guidancePointer\` authoring instructions
5. **Complete:** Call \`advance_item(transitions=[{itemId: "<your-item-UUID>", trigger: "complete"}])\` — gate enforcement verifies all required notes are filled

Do NOT defer steps 1-2 until after implementation — you need the guidance *before* you start coding.

**Returning results:** Report files changed (with line counts), test results, and any blockers. Do not echo back the task description.`
  }
};
process.stdout.write(JSON.stringify(output));
