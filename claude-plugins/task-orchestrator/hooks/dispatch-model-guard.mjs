#!/usr/bin/env node
// Dispatch Model Guard Hook - PreToolUse on the Agent tool. Denies a dispatch whose
// tool_input.model or tool_input.effort is absent (or empty/whitespace/non-string): the shipped
// agents use `model: inherit` and carry no `effort`, so an omitted model silently runs the subagent
// on the caller's own model and an omitted effort runs it at the model's default.
// The deny reason names what is missing and tells the caller how to retry. The values are NOT
// validated: the Agent tool owns that.
//
// Gates, in order (each emits `{}`): unreadable/non-JSON stdin -> tool_name not Agent -> headless
// iteration -> no located config -> orchestration mode off -> model and effort present.
// A director session is still guarded (its dispatches must pass `model` and `effort` too).
// Active in `workflow` (default, also absent/invalid) and `schema` modes; the reason text differs.
// Fail-open: any throw yields `{}` and exit 0.

import { readHookInput } from './lib/stdin.mjs';
import { emit } from './lib/emit.mjs';
import { isHeadlessIteration } from './execution-mode.mjs';
import { locateConfig } from './config-locator.mjs';
import { parseOrchestrationConfig } from './orchestration-lib.mjs';

const OFF_POINTER = 'Set `mode: off` under `extensions.claude-code.orchestration` in .taskorchestrator/config.yaml to disable this guard.';

function rule(missing) {
  const names = missing.map((f) => `\`${f}\``).join(' and ');
  return `Task Orchestrator dispatch rule: every Agent dispatch must pass \`model\` and \`effort\` explicitly. The shipped agents use \`model: inherit\` and carry no \`effort\`, so omitting them silently runs the subagent on your own model at the model's default effort. This call is missing ${names}. Retry the same call (keep \`subagent_type\`, \`description\`, \`prompt\`) with ${names} set.`;
}

const WORKFLOW_TAIL = `Use the item's dispatch profile \`model\` and \`effort\` (from the \`advance\` result or \`items_read\` \`context\`) when it has them. Otherwise use the class of the work in the dispatch policy (\`task-orchestrator:model-effort\`). ${OFF_POINTER}`;

const SCHEMA_TAIL = `Use the dispatch profile \`model\` and \`effort\` for the item (\`dispatch\` / \`dispatchBySeat\` from the \`advance\` result or \`items_read\` \`context\`), or otherwise values you choose explicitly for the work. ${OFF_POINTER}`;

function present(toolInput, field) {
  const v = toolInput && typeof toolInput === 'object' ? toolInput[field] : undefined;
  return typeof v === 'string' && v.trim().length > 0;
}

function main() {
  const input = readHookInput();
  if (!input) return {};
  if (input.tool_name !== 'Agent') return {};
  if (isHeadlessIteration(process.env)) return {};
  const loc = locateConfig({ cwd: process.cwd(), env: process.env });
  if (!loc || loc.scope === 'none' || typeof loc.text !== 'string' || !loc.text) return {};
  const mode = parseOrchestrationConfig(loc.text).mode;
  if (mode === 'off') return {};
  const missing = ['model', 'effort'].filter((f) => !present(input.tool_input, f));
  if (missing.length === 0) return {};
  return {
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason: `${rule(missing)} ${mode === 'schema' ? SCHEMA_TAIL : WORKFLOW_TAIL}`,
    },
  };
}

let out = {};
try {
  out = main();
} catch {
  out = {};
}
emit(out);
process.exit(0);
