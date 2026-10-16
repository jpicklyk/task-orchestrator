#!/usr/bin/env node
// Dispatch Hint Hook — PostToolUse on advance. For each transition that landed in `work` or
// `review`, injects one line telling the dispatcher the item's phase-level dispatch profile
// (agent / model / effort) and that `model` must be passed explicitly on the Agent dispatch.
// The profile is already in the advance response (`results[i].dispatch`), so there is no server
// round-trip. The result is read through lib/envelope.mjs; a 3.x-shaped (`legacy`) result hints
// nothing and emits the one-time legacy-shape warning as a systemMessage.
//
// Gates, in order (each emits `{}`): unreadable/non-JSON stdin -> tool_name not advance ->
// headless iteration -> no located config -> orchestration.mode off -> subagent/seat (agent_id set;
// only the dispatcher needs the hint) -> no qualifying results.
// Fail-open: any throw yields `{}` and exit 0.

import { readHookInput } from './lib/stdin.mjs';
import { emit } from './lib/emit.mjs';
import { isHeadlessIteration } from './execution-mode.mjs';
import { locateConfig } from './config-locator.mjs';
import { parseOrchestrationConfig } from './orchestration-lib.mjs';
import { parseToolResult } from './lib/envelope.mjs';
import { legacyShapeWarningOnce } from './lib/server-compat.mjs';

function str(v) {
  return typeof v === 'string' && v.trim().length > 0 ? v.trim() : null;
}

function lineFor(entry, mode) {
  const d = entry.dispatch && typeof entry.dispatch === 'object' ? entry.dispatch : {};
  const agent = str(d.agent) ?? 'none';
  const model = str(d.model) ?? (mode === 'schema' ? 'unset' : 'table default');
  const effort = str(d.effort);
  return `↳ ${entry.itemId} now in ${entry.newRole}; dispatch profile: agent=${agent} model=${model}${
    effort ? ` effort=${effort}` : ''
  } - pass model explicitly`;
}

function main() {
  const input = readHookInput();
  if (!input) return {};
  if (typeof input.tool_name !== 'string' || !/__advance$/.test(input.tool_name)) return {};
  if (isHeadlessIteration(process.env)) return {};
  const loc = locateConfig({ cwd: process.cwd(), env: process.env });
  if (!loc || loc.scope === 'none' || typeof loc.text !== 'string' || !loc.text) return {};
  const mode = parseOrchestrationConfig(loc.text).mode;
  if (mode === 'off') return {};
  if (input.agent_id) return {};
  const parsed = parseToolResult(input.tool_response);
  if (!parsed) return {};
  if (parsed.legacy) {
    const warning = legacyShapeWarningOnce(input.session_id);
    return warning ? { systemMessage: warning } : {};
  }
  const lines = [];
  for (const r of parsed.results) {
    if (!r || r.ok !== true) continue;
    if (r.newRole !== 'work' && r.newRole !== 'review') continue;
    if (typeof r.itemId !== 'string' || !r.itemId) continue;
    lines.push(lineFor(r, mode));
  }
  if (lines.length === 0) return {};
  return {
    hookSpecificOutput: {
      hookEventName: 'PostToolUse',
      additionalContext: lines.join('\n'),
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
