#!/usr/bin/env node
// SubagentStart — injects agent-owned-phase protocol.
// Early exit: if no .taskorchestrator/ directory exists at or above cwd
// (or AGENT_CONFIG_DIR), the project is not orchestrated — emit nothing
// so non-orchestrated projects pay no context cost.
import { statSync } from 'fs';
import { resolve } from 'path';

function isOrchestratedProject() {
  const candidates = [];
  if (process.env.AGENT_CONFIG_DIR) {
    candidates.push(resolve(process.env.AGENT_CONFIG_DIR, '.taskorchestrator'));
  }
  let dir = process.cwd();
  const root = resolve(dir, '/');
  while (dir !== root) {
    candidates.push(resolve(dir, '.taskorchestrator'));
    dir = resolve(dir, '..');
  }
  for (const candidate of candidates) {
    try {
      if (statSync(candidate).isDirectory()) return true;
    } catch {
      continue;
    }
  }
  return false;
}

if (!isOrchestratedProject()) {
  process.exit(0);
}

const output = {
  hookSpecificOutput: {
    hookEventName: "SubagentStart",
    additionalContext: `## Agent-Owned-Phase Protocol

**You own exactly ONE phase.** Enter it with \`advance_item(transitions=[{itemId: "<your-item-UUID>", trigger: "start"}])\`, do the work, and fill the phase's required notes via \`manage_notes(operation="upsert", ...)\` — each upsert response returns the next note's guidance. If the item is already in your phase (\`applied: false\`), call \`get_context(itemId=...)\` once for guidance instead.

**Never call \`advance_item\` again after entering your phase, and never use \`trigger: "complete"\`** — the orchestrator owns all subsequent transitions.

## Subagent Discipline

1. **Commit before returning.** Stage and commit all file changes with a descriptive message — the orchestrator squash-merges your branch.
2. **Stay in scope.** Touch only files for your assigned task. No version bumps, shared config, or CI edits.
3. **Notes are the report.** Write findings into your item's notes — distill inline; route verbatim artifacts (test output, diffs, logs) via \`bodyFromFile\`. Your final message to the orchestrator is 1-2 lines: item ID, outcome, and which note keys were filled. Do not restate note content.`
  }
};
process.stdout.write(JSON.stringify(output));
