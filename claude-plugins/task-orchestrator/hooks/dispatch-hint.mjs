#!/usr/bin/env node
// Dispatch Hint Hook — PostToolUse on advance_item. For each transition that landed in `work` or
// `review`, injects one line telling the dispatcher the item's phase-level dispatch profile
// (agent / model / effort) and that `model` must be passed explicitly on the Agent dispatch.
// The profile is already in the advance_item response, so there is no server round-trip.
//
// The JSON payload lives only in `tool_response.structuredContent`; `content[0].text` is a human
// summary, so extractResponseJson must be given structuredContent first.
//
// Gates, in order (each emits `{}`): unreadable/non-JSON stdin -> tool_name not advance_item ->
// headless iteration -> no located config -> orchestration.mode off -> subagent/seat (agent_id set;
// only the dispatcher needs the hint) -> no qualifying results.
// Fail-open: any throw yields `{}` and exit 0.

import { readFileSync } from 'node:fs';
import { isHeadlessIteration } from './execution-mode.mjs';
import { locateConfig } from './config-locator.mjs';
import { parseOrchestrationConfig } from './orchestration-lib.mjs';
import { extractResponseJson } from './retro-lib.mjs';

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
  let input;
  try {
    input = JSON.parse(readFileSync(0, 'utf-8'));
  } catch {
    return {};
  }
  if (!input || typeof input !== 'object') return {};
  if (typeof input.tool_name !== 'string' || !/__advance_item$/.test(input.tool_name)) return {};
  if (isHeadlessIteration(process.env)) return {};
  const loc = locateConfig({ cwd: process.cwd(), env: process.env });
  if (!loc || loc.scope === 'none' || typeof loc.text !== 'string' || !loc.text) return {};
  const mode = parseOrchestrationConfig(loc.text).mode;
  if (mode === 'off') return {};
  if (input.agent_id) return {};
  const resp = input.tool_response;
  const payload = extractResponseJson(resp?.structuredContent ?? resp);
  const results = payload && Array.isArray(payload.results) ? payload.results : [];
  const lines = [];
  for (const r of results) {
    if (!r || r.applied !== true) continue;
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
process.stdout.write(JSON.stringify(out));
process.exit(0);
