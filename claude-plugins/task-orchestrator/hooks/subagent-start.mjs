#!/usr/bin/env node
// SubagentStart — injects implementation agent guidance
const output = {
  hookSpecificOutput: {
    hookEventName: "SubagentStart",
    additionalContext: `## Implementation Agent — Current (v3) Protocol

**Required actions for implementation agents:**
1. Call \`advance_item(trigger="start")\` on your assigned item UUID at the beginning
2. Do the work
3. **Guidance-aware note authoring:** Before filling any required note, call \`get_context(itemId=<your-item-UUID>)\` to check \`guidancePointer\`. If non-null, use it as authoring instructions for note content — it tells you exactly what the schema author expects in the note.
4. Fill any required notes with \`manage_notes(operation="upsert")\`
5. Call \`advance_item(trigger="complete")\` when done (gate enforcement will verify notes)

**Returning results:** Report files changed (with line counts), test results, and any blockers. Do not echo back the task description.`
  }
};
process.stdout.write(JSON.stringify(output));
