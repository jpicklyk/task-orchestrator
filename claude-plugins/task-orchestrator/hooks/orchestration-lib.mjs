// Shared parser for the client-side orchestration config, read by the orchestration hooks so they
// all interpret the mode identically. Pure module: no I/O, no import-time side effects.
//
//   extensions:
//     claude-code:
//       orchestration:
//         mode: workflow   # workflow (default) | schema | off
//
// Also accepts the inline form `orchestration: { mode: schema }` under `claude-code`. A top-level
// `orchestration:` block is NOT read: a 4.0 server rejects it on push, so honouring it here would
// diverge from what the server accepts. Fail-open: any absent block, absent key, invalid value,
// non-string input, or parse error yields the default. Never throws.

import { readSection, scalar, inlineScalar } from './yaml-lite.mjs';

export const ORCHESTRATION_MODES = Object.freeze(['workflow', 'schema', 'off']);
export const DEFAULT_ORCHESTRATION_MODE = 'workflow';

const indentOf = (l) => l.length - l.trimStart().length;
const isBlank = (l) => l.trim() === '' || l.trim().startsWith('#');

// Child lines of the first `key:` header found at the block's first-level indent. Returns
// { inline, lines } (inline form, or the deeper-indented raw lines), or null when the key is absent.
function childSection(lines, keyPattern) {
  let first = Infinity;
  for (const l of lines) if (!isBlank(l)) first = Math.min(first, indentOf(l));
  if (first === Infinity) return null;
  const headerRe = new RegExp(`^${keyPattern}\\s*:\\s*(\\{(.*)\\})?\\s*(#.*)?$`);
  for (let i = 0; i < lines.length; i++) {
    const l = lines[i].replace(/\r$/, '');
    if (isBlank(l) || indentOf(l) !== first) continue;
    const m = l.trim().match(headerRe);
    if (!m) continue;
    if (m[1] !== undefined) return { inline: m[2], lines: [] };
    const out = [];
    for (let j = i + 1; j < lines.length; j++) {
      if (!isBlank(lines[j]) && indentOf(lines[j]) <= first) break;
      out.push(lines[j]);
    }
    return { inline: null, lines: out };
  }
  return null;
}

/**
 * Reads `extensions:` -> `claude-code:` -> `<name>:` from config text. Returns the same
 * `{ inline, lines }` shape as yaml-lite `readSection` (so `scalar` / `inlineScalar` apply), or null.
 * Block form for `extensions` and `claude-code` (any consistent indent width; `claude-code` bare or
 * quoted); `<name>` in block or inline `{ k: v }` form. Never throws.
 */
export function readClaudeCodeSection(configText, name) {
  try {
    if (typeof configText !== 'string' || configText === '' || typeof name !== 'string' || name === '') return null;
    const ext = readSection(configText, 'extensions', { blockOnly: true });
    if (!ext) return null;
    const cc = childSection(ext.lines, `["']?claude-code["']?`);
    if (!cc || cc.inline !== null) return null;
    return childSection(cc.lines, `["']?${name.replace(/[^A-Za-z0-9_-]/g, '')}["']?`);
  } catch {
    return null;
  }
}

// Returns a fresh `{ mode }` object on every call.
export function parseOrchestrationConfig(configText) {
  const result = { mode: DEFAULT_ORCHESTRATION_MODE };
  try {
    if (typeof configText !== 'string' || configText === '') return result;
    const section = readClaudeCodeSection(configText, 'orchestration');
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
