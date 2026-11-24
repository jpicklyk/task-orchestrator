// Shared parser for the client-side steering config, read by the orchestration hooks so they
// all interpret the mode identically. Pure module: no I/O, no import-time side effects.
//
//   extensions:
//     claude-code:
//       steering: workflow   # workflow (default) | schema | off
//
// Deprecated alias: `orchestration: { mode: ... }` under `claude-code` (block or inline form) is read
// only when `steering` is absent or invalid; `steering` wins when both are set. The result reports
// `deprecatedKeyPresent` whenever the old key carries a value, so a caller can emit one notice.
// A top-level `orchestration:` block is NOT read: a 4.0 server rejects it on push, so honouring it
// here would diverge from what the server accepts. Fail-open: any absent block, absent key, invalid
// value, non-string input, or parse error yields the default. Never throws.

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
    if (typeof name !== 'string' || name === '') return null;
    const cc = claudeCodeLines(configText);
    if (!cc) return null;
    return childSection(cc, `["']?${name.replace(/[^A-Za-z0-9_-]/g, '')}["']?`);
  } catch {
    return null;
  }
}

// The block lines of `extensions` -> `claude-code`, or null (absent, inline form or bad input).
function claudeCodeLines(configText) {
  if (typeof configText !== 'string' || configText === '') return null;
  const ext = readSection(configText, 'extensions', { blockOnly: true });
  if (!ext) return null;
  const cc = childSection(ext.lines, `["']?claude-code["']?`);
  if (!cc || cc.inline !== null) return null;
  return cc.lines;
}

const asMode = (raw) => {
  if (raw === null || raw === undefined) return null;
  const val = String(raw).trim().toLowerCase();
  return ORCHESTRATION_MODES.includes(val) ? val : null;
};

// Returns a fresh `{ mode, source, deprecatedKeyPresent }` object on every call. `source` is
// 'steering', 'orchestration.mode' (the deprecated alias) or 'default'.
export function parseOrchestrationConfig(configText) {
  const result = { mode: DEFAULT_ORCHESTRATION_MODE, source: 'default', deprecatedKeyPresent: false };
  try {
    const cc = claudeCodeLines(configText);
    if (!cc) return result;
    const steering = asMode(scalar(cc, 'steering'));
    const section = childSection(cc, `["']?orchestration["']?`);
    let oldRaw = null;
    if (section) oldRaw = section.inline !== null ? inlineScalar(section.inline, 'mode') : scalar(section.lines, 'mode');
    result.deprecatedKeyPresent = oldRaw !== null && oldRaw !== undefined;
    if (steering !== null) {
      result.mode = steering;
      result.source = 'steering';
    } else {
      const old = asMode(oldRaw);
      if (old !== null) {
        result.mode = old;
        result.source = 'orchestration.mode';
      }
    }
  } catch {
    // fail open to the default
  }
  return result;
}

export const STEERING_DEPRECATION_NOTICE =
  'Task Orchestrator: `extensions.claude-code.orchestration.mode` is deprecated; rename it to `extensions.claude-code.steering` (same values) in .taskorchestrator/config.yaml.';

// Thin wrapper for callers that only want the mode string.
export function parseOrchestrationMode(configText) {
  return parseOrchestrationConfig(configText).mode;
}
