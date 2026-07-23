// Shared helpers for the retrospective-trigger hooks (retro-trigger.mjs, retro-ack.mjs).
// Pure module — no side effects at import time, no top-level I/O.

import { readFileSync, writeFileSync, mkdirSync } from 'fs';
import { resolve, join, dirname } from 'path';
import os from 'os';
import { readSection, scalar, inlineScalar } from './yaml-lite.mjs';

// Locate and read .taskorchestrator/config.yaml — check AGENT_CONFIG_DIR, then walk up from
// cwd. Handles worktrees where cwd is nested under .claude/worktrees/<name>/.
// Idiom copied from enforce-actor-attribution.mjs.
export function findConfigContent() {
  const candidates = [];
  if (process.env.AGENT_CONFIG_DIR) {
    candidates.push(resolve(process.env.AGENT_CONFIG_DIR, '.taskorchestrator', 'config.yaml'));
  }
  let dir = process.cwd();
  const root = resolve(dir, '/');
  while (dir !== root) {
    candidates.push(resolve(dir, '.taskorchestrator', 'config.yaml'));
    dir = resolve(dir, '..');
  }
  for (const candidate of candidates) {
    try {
      return readFileSync(candidate, 'utf-8');
    } catch {
      continue;
    }
  }
  return null;
}

const VALID_MODES = ['nudge', 'dispatch', 'off'];
const DEFAULT_MODE = 'nudge';
const DEFAULT_DISPATCH_THRESHOLD = 3;
const DEFAULT_COOLDOWN_MINUTES = 30;

// Parses the top-level `retrospective:` block:
//   retrospective:
//     mode: dispatch
//     dispatchThreshold: 3
//     cooldownMinutes: 30
// Also supports the inline form `retrospective: { mode: dispatch }`. Absent keys, an absent
// block, or an unrecognized/invalid value all fall back to the documented default for that key
// — this never throws. Returns `{ mode, dispatchThreshold, cooldownMinutes }`.
export function parseRetrospectiveConfig(configContent) {
  const result = {
    mode: DEFAULT_MODE,
    dispatchThreshold: DEFAULT_DISPATCH_THRESHOLD,
    cooldownMinutes: DEFAULT_COOLDOWN_MINUTES,
  };
  if (!configContent) return result;

  const section = readSection(configContent, 'retrospective');
  if (!section) return result;

  const read = (key) => (section.inline !== null ? inlineScalar(section.inline, key) : scalar(section.lines, key));

  const rawMode = read('mode');
  if (rawMode !== null) {
    const val = rawMode.toLowerCase();
    if (VALID_MODES.includes(val)) result.mode = val;
  }

  const rawThreshold = read('dispatchThreshold');
  if (rawThreshold !== null) {
    const n = Number(rawThreshold);
    if (Number.isInteger(n) && n >= 0) result.dispatchThreshold = n;
  }

  const rawCooldown = read('cooldownMinutes');
  if (rawCooldown !== null) {
    const n = Number(rawCooldown);
    if (Number.isFinite(n) && n > 0) result.cooldownMinutes = n;
  }

  return result;
}

// Thin wrapper for any caller that only wants the mode.
export function parseRetrospectiveMode(configContent) {
  return parseRetrospectiveConfig(configContent).mode;
}

// Parses the top-level `project:` block for its `rootId` value. Mirrors parseProjectBlock in
// session-start.mjs, but returns only the rootId string (or null).
export function parseProjectRootId(configContent) {
  if (!configContent) return null;
  const section = readSection(configContent, 'project', { blockOnly: true });
  if (!section) return null;
  return scalar(section.lines, 'rootId');
}

export function markerPath(key) {
  return join(os.tmpdir(), 'task-orchestrator', 'retro-' + key.replace(/[^a-zA-Z0-9-]/g, '_') + '.json');
}

export function readMarker(path) {
  try {
    return JSON.parse(readFileSync(path, 'utf-8'));
  } catch {
    return {};
  }
}

export function writeMarker(path, obj) {
  try {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, JSON.stringify(obj));
  } catch {
    // swallow all errors — a marker write must never crash a hook
  }
}

// Default cooldown, in ms — overridden per-config by `retrospective.cooldownMinutes` (see
// parseRetrospectiveConfig). Kept as an export so callers still have a concrete fallback
// constant available without re-deriving it from DEFAULT_COOLDOWN_MINUTES themselves.
export const COOLDOWN_MS = DEFAULT_COOLDOWN_MINUTES * 60 * 1000;

// Extracts the tool's JSON payload from a PostToolUse hook's tool_response field. The exact
// delivered shape is not pinned down by the hooks docs for MCP tools, so this is deliberately
// shape-tolerant — it accepts every plausible encoding rather than betting on one:
//   1. CallToolResult wrapper:   { content: [{ type: "text", text: "<json>" }] }
//   2. Bare content-block array: [{ type: "text", text: "<json>" }]
//   3. Single content block:     { type: "text", text: "<json>" }
//   4. Already-parsed payload:   { results: [...], summary: {...} } — returned as-is
//   5. Raw JSON string:          "<json>"
// Returns null on any failure (fail-open) so callers stay silent on unknown shapes.
export function extractResponseJson(toolResponse) {
  try {
    if (toolResponse == null) return null;
    if (typeof toolResponse === 'string') return JSON.parse(toolResponse);
    if (Array.isArray(toolResponse)) {
      const block = toolResponse.find((b) => b && typeof b.text === 'string');
      return block ? JSON.parse(block.text) : null;
    }
    if (Array.isArray(toolResponse.content)) {
      const block = toolResponse.content.find((b) => b && typeof b.text === 'string');
      return block ? JSON.parse(block.text) : null;
    }
    if (typeof toolResponse.text === 'string') return JSON.parse(toolResponse.text);
    if (toolResponse.results !== undefined || toolResponse.summary !== undefined) return toolResponse;
    return null;
  } catch {
    return null;
  }
}

// Opt-in diagnostic: when RETRO_HOOK_DEBUG=1, persist the hook's raw stdin to
// os.tmpdir()/task-orchestrator/debug-last-invocation.json so a live firing records the true
// payload shape. Best-effort — never throws, never runs unless explicitly enabled.
export function debugCapture(raw) {
  if (process.env.RETRO_HOOK_DEBUG !== '1') return;
  try {
    const dir = join(os.tmpdir(), 'task-orchestrator');
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, 'debug-last-invocation.json'), new Date().toISOString() + '\n' + raw);
  } catch {
    // swallow — diagnostics must never affect hook behavior
  }
}

export function buildNudge(roots) {
  const list = (roots || []).filter(Boolean);
  if (list.length === 0) {
    return `Retrospective suggested — an implementation run reached terminal. ` +
      `Consider running \`/session-retrospective\` to capture learnings before the session ends. ` +
      `Skip if the feature still has items in progress, or if this was minor housekeeping.`;
  }
  const ids = list.join(', ');
  const first = list[0];
  return `Retrospective suggested — an implementation run reached terminal (root(s): ${ids}). ` +
    `Consider running \`/session-retrospective ${first}\` to capture learnings before the session ends. ` +
    `Skip if the feature still has items in progress, or if this was minor housekeeping.`;
}

export function buildDispatch(roots, rootId) {
  const rootsJoined = (roots || []).join(', ');
  const ancestorId = rootId || 'none configured';
  return `Retrospective dispatch (mode: dispatch). Run ${rootsJoined} reached terminal. ` +
    `Dispatch exactly ONE background retrospective now via the Agent tool: subagent_type "general-purpose", ` +
    `model "sonnet", run_in_background true, prompt: invoke /session-retrospective ${rootsJoined}, ` +
    `project ancestorId ${ancestorId}, work ONLY from durable MCP state (session-tracking + delegation-metadata ` +
    `notes, schema config, agent-observation items — no conversation context), write the retrospective into ` +
    `the process-global 'Session Retrospectives' container at depth 0 OUTSIDE any project root, return only the ` +
    `retrospective item UUID plus a 2-3 line headline. Do not dispatch more than one retrospective for this run; ` +
    `skip entirely if the feature still has active items.`;
}
