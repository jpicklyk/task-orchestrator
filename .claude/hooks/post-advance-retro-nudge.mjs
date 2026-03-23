#!/usr/bin/env node
// PostToolUse advance_item — nudges retrospective on terminal entry
const chunks = [];
process.stdin.on('data', c => chunks.push(c));
process.stdin.on('end', () => {
  try {
    const input = JSON.parse(Buffer.concat(chunks).toString());
    const result = input.tool_result || '';

    // Item reached terminal — remind about retrospective
    if (result.includes('"newRole":"terminal"')) {
      process.stdout.write(JSON.stringify({
        hookSpecificOutput: {
          hookEventName: "PostToolUse",
          additionalContext:
            "## Retrospective Nudge\n\n" +
            "An item just reached terminal via `advance_item`. If this completes an implementation run, " +
            "suggest running `/session-retrospective` to capture learnings.\n\n" +
            "Skip if other items in the feature are still in progress, or if this was a minor " +
            "status change unrelated to an implementation run."
        }
      }));
      return;
    }

    process.stdout.write('{}');
  } catch {
    process.stdout.write('{}');
  }
});
