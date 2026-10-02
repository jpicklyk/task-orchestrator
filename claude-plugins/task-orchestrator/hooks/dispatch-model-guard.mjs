#!/usr/bin/env node
// Dispatch Model Guard Hook — PreToolUse on the Agent tool. Denies a dispatch whose
// tool_input.model is absent (or empty/whitespace/non-string), because the shipped agents use
// `model: inherit` and an omitted model silently runs the subagent on the caller's own model.
// The deny reason tells the caller how to retry. The value is NOT validated: the Agent tool owns that.
//
// Gates, in order (each emits `{}`): unreadable/non-JSON stdin -> tool_name not Agent -> headless
// iteration -> no located config -> orchestration.mode off -> model present.
// Active in `workflow` (default, also absent/invalid) and `schema` modes; the reason text differs.
// Fail-open: any throw yields `{}` and exit 0.

import { readFileSync } from 'node:fs';
import { isHeadlessIteration } from './execution-mode.mjs';
import { locateConfig } from './config-locator.mjs';
import { parseOrchestrationConfig } from './orchestration-lib.mjs';

const RULE =
  'Task Orchestrator dispatch rule: every Agent dispatch must pass `model` explicitly. The shipped agents use `model: inherit`, so omitting it silently runs the subagent on your own model. Retry the same call (keep `subagent_type`, `description`, `prompt`) with `model` set.';
const OFF_POINTER = 'Set `orchestration.mode: off` in .taskorchestrator/config.yaml to disable this guard.';

const WORKFLOW_REASON = `${RULE} Use the item's dispatch profile \`model\` (from \`advance_item\` / \`get_context\`) when it has one. Otherwise use the delegation table: \`haiku\` for MCP bulk ops, materialization, and simple queries; \`sonnet\` for code reading, implementation, and test writing; \`opus\` for architecture, complex tradeoffs, and multi-file synthesis (see \`task-orchestrator:orchestrate\`). ${OFF_POINTER}`;

const SCHEMA_REASON = `${RULE} Use the dispatch profile \`model\` for the item (\`dispatch\` / \`dispatchBySeat\` from \`advance_item\` / \`get_context\`), or otherwise a model you choose explicitly for the work. ${OFF_POINTER}`;

function hasModel(toolInput) {
  const m = toolInput && typeof toolInput === 'object' ? toolInput.model : undefined;
  return typeof m === 'string' && m.trim().length > 0;
}

function main() {
  let input;
  try {
    input = JSON.parse(readFileSync(0, 'utf-8'));
  } catch {
    return {};
  }
  if (!input || typeof input !== 'object') return {};
  if (input.tool_name !== 'Agent') return {};
  if (isHeadlessIteration(process.env)) return {};
  const loc = locateConfig({ cwd: process.cwd(), env: process.env });
  if (!loc || loc.scope === 'none' || typeof loc.text !== 'string' || !loc.text) return {};
  const mode = parseOrchestrationConfig(loc.text).mode;
  if (mode === 'off') return {};
  if (hasModel(input.tool_input)) return {};
  return {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason: mode === 'schema' ? SCHEMA_REASON : WORKFLOW_REASON,
    },
  };
}

let out = {};
try {
  out = main();
} catch {
  out = {};
}
process.stdout.write(JSON.stringify(out));
process.exit(0);
