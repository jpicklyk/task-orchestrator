#!/usr/bin/env node
// PostToolUse complete_tree — nudges session retrospective after implementation runs
process.stdout.write(JSON.stringify({
  hookSpecificOutput: {
    hookEventName: "PostToolUse",
    additionalContext:
      "## Retrospective Nudge\n\n" +
      "A `complete_tree` call just finished. If this completed an implementation run " +
      "(feature work, bug fixes, or a batch of items), consider running " +
      "`/session-retrospective` to capture learnings before the session ends.\n\n" +
      "Skip if this was just a minor cleanup (single item cancellation, container housekeeping)."
  }
}));
