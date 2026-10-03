// Shared parser for the client-side `orchestration:` config block, read by the orchestration
// hooks so they all interpret the mode identically. Pure module: no I/O, no import-time side effects.
//
//   orchestration:
//     mode: workflow   # workflow (default) | schema | off
//
// Also accepts the inline form `orchestration: { mode: schema }`. Fail-open: any absent block,
// absent key, invalid value, non-string input, or parse error yields the default. Never throws.

import { readSection, scalar, inlineScalar } from './yaml-lite.mjs';

export const ORCHESTRATION_MODES = Object.freeze(['workflow', 'schema', 'off']);
export const DEFAULT_ORCHESTRATION_MODE = 'workflow';

// Returns a fresh `{ mode }` object on every call.
export function parseOrchestrationConfig(configText) {
  const result = { mode: DEFAULT_ORCHESTRATION_MODE };
  try {
    if (typeof configText !== 'string' || configText === '') return result;
    const section = readSection(configText, 'orchestration');
    if (!section) return result;
    const raw = section.inline !== null ? inlineScalar(section.inline, 'mode') : scalar(section.lines, 'mode');
    if (raw === null || raw === undefined) return result;
    const val = String(raw).trim().toLowerCase();
    if (ORCHESTRATION_MODES.includes(val)) result.mode = val;
  } catch {
    // fail open to the default
  }
  return result;
}

// Thin wrapper for callers that only want the mode string.
export function parseOrchestrationMode(configText) {
  return parseOrchestrationConfig(configText).mode;
}
