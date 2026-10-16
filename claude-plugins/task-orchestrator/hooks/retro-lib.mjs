// Shared helpers for the retrospective hooks (retro-trigger.mjs, retro-backstop.mjs, retro-ack.mjs).
// A retrospective is owed per closed run on the project root that is not marked `retro`; the
// server-side mark (runs_write mark, first write wins) is the cross-session dedupe, so no hook keeps
// a cross-session marker file. Per-session state (closes seen without REST, runs already nudged)
// lives in lib/markers.mjs session markers. No side effects at import time, no top-level I/O.

import { writeFileSync, mkdirSync } from 'fs';
import { join } from 'path';
import os from 'os';
import { readSection, scalar, inlineScalar } from './yaml-lite.mjs';
import { sessionMarkerPath, readMarker, writeMarker } from './lib/markers.mjs';
import { callOp, apiBaseUrl } from './lib/api-client.mjs';

const VALID_MODES = ['nudge', 'dispatch', 'off'];
const DEFAULT_MODE = 'nudge';
const DEFAULT_DISPATCH_THRESHOLD = 3;

const LIST_WINDOW_MS = 24 * 60 * 60 * 1000;
const LIST_LIMIT = 20;
const MAX_FRONTIER_RUNS = 5;
const CALL_TIMEOUT_MS = 1500;

function indentOf(line) {
  return line.length - line.trimStart().length;
}

function isContent(line) {
  const t = line.trim();
  return t !== '' && !t.startsWith('#');
}

// Finds the direct child `key:` of a block (the lines readSection or a previous childBlock returned).
// Returns { inline, lines } like readSection: `inline` is the interior of a `{ ... }` form (lines is
// then empty), `lines` the nested block lines otherwise; null when the key is absent.
function childBlock(lines, key) {
  let firstLevel = Infinity;
  for (const line of lines) {
    if (isContent(line)) firstLevel = Math.min(firstLevel, indentOf(line));
  }
  if (firstLevel === Infinity) return null;
  const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp('^' + escaped + '\\s*:\\s*(?:\\{(.*)\\}.*|(?:#.*)?)$');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].replace(/\r$/, '');
    if (!isContent(line) || indentOf(line) !== firstLevel) continue;
    const m = line.trim().match(re);
    if (!m) continue;
    if (m[1] !== undefined) return { inline: m[1], lines: [] };
    const out = [];
    for (let j = i + 1; j < lines.length; j++) {
      const next = lines[j];
      if (isContent(next) && indentOf(next) <= firstLevel) break;
      out.push(next);
    }
    return { inline: null, lines: out };
  }
  return null;
}

// Parses `extensions.claude-code.retrospective` (block form, or the inline `{ mode: dispatch }` form
// at the leaf):
//   extensions:
//     claude-code:
//       retrospective:
//         mode: dispatch
//         dispatchThreshold: 3
// A top-level `retrospective:` block is not read. Absent keys, an absent block, or an invalid value
// fall back to the documented default for that key; this never throws. Returns { mode, dispatchThreshold }.
export function parseRetrospectiveConfig(configContent) {
  const result = { mode: DEFAULT_MODE, dispatchThreshold: DEFAULT_DISPATCH_THRESHOLD };
  try {
    if (!configContent) return result;
    const ext = readSection(configContent, 'extensions', { blockOnly: true });
    if (!ext) return result;
    const cc = childBlock(ext.lines, 'claude-code');
    if (!cc || cc.inline !== null) return result;
    const section = childBlock(cc.lines, 'retrospective');
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
  } catch {
    // defaults
  }
  return result;
}

// Thin wrapper for any caller that only wants the mode.
export function parseRetrospectiveMode(configContent) {
  return parseRetrospectiveConfig(configContent).mode;
}

// ancestorId for a dispatch directive. A user-scope personal root anchors items but must not scope
// reads, so only a project-scope config contributes its rootId.
export function dispatchAncestorId(located) {
  return located && located.scope === 'project' ? (located.rootId || null) : null;
}

// ---- per-session state -------------------------------------------------------------------------

/** Per-session retro state: { closes: runId[], sawClose, nudged: runId[], backstopNudged }. */
export function readSessionState(sessionId) {
  const raw = readMarker(sessionMarkerPath(sessionId, 'retro'), {});
  return {
    closes: Array.isArray(raw.closes) ? raw.closes.filter((v) => typeof v === 'string') : [],
    sawClose: raw.sawClose === true,
    nudged: Array.isArray(raw.nudged) ? raw.nudged.filter((v) => typeof v === 'string') : [],
    backstopNudged: raw.backstopNudged === true,
  };
}

export function writeSessionState(sessionId, state) {
  writeMarker(sessionMarkerPath(sessionId, 'retro'), state);
}

// ---- REST path ---------------------------------------------------------------------------------

/**
 * The project rootId when the REST path can run (an API URL resolves AND the located config is project
 * scope with a rootId), else null.
 */
export function restRootId(located) {
  if (!apiBaseUrl()) return null;
  return dispatchAncestorId(located);
}

function isPlain(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

/**
 * The shared run evaluation (trigger and backstop). Lists the root's closed runs not yet marked
 * `retro` (closed in the last 24 h), reads the terminal count of at most 5 of them, and for each:
 * dispatch mode and terminal >= threshold -> try to mark (first write wins); a run whose mark this call
 * wrote is won and dispatched; a lost race (marked false) is silent; a failed mark is nudged, never
 * dispatched unmarked. Everything else is nudged. Adds the nudged runs to `state.nudged` (the caller
 * persists). Returns { won: runId[], nudge: runId[] }; fail-open.
 */
export async function evaluateRuns({ rootId, config, state }) {
  const out = { won: [], nudge: [] };
  try {
    const endedSince = new Date(Date.now() - LIST_WINDOW_MS).toISOString();
    const list = await callOp(
      'runs_read',
      { select: 'list', rootId, unmarked: 'retro', endedSince, limit: LIST_LIMIT },
      { timeoutMs: CALL_TIMEOUT_MS },
    );
    if (!list || list.ok !== true || !isPlain(list.data) || !Array.isArray(list.data.items)) return out;

    const candidates = [];
    for (const view of list.data.items) {
      if (!isPlain(view) || typeof view.runId !== 'string' || view.runId === '') continue;
      if (isPlain(view.marks) && Object.prototype.hasOwnProperty.call(view.marks, 'retro')) continue;
      if (state.nudged.includes(view.runId)) continue;
      if (candidates.includes(view.runId)) continue;
      candidates.push(view.runId);
      if (candidates.length >= MAX_FRONTIER_RUNS) break;
    }
    if (candidates.length === 0) return out;

    const frontiers = await Promise.all(
      candidates.map((runId) => callOp('runs_read', { select: 'frontier', runId }, { timeoutMs: CALL_TIMEOUT_MS })),
    );
    const toMark = [];
    candidates.forEach((runId, i) => {
      const f = frontiers[i];
      const terminal = f && f.ok === true && isPlain(f.data) && isPlain(f.data.counts) ? f.data.counts.terminal : undefined;
      if (typeof terminal !== 'number') return; // failed frontier read: skip this run this time
      if (config.mode === 'dispatch' && terminal >= config.dispatchThreshold) toMark.push(runId);
      else out.nudge.push(runId);
    });

    const marks = await Promise.all(
      toMark.map((runId) =>
        callOp('runs_write', { ops: [{ op: 'mark', runId, key: 'retro' }] }, { timeoutMs: CALL_TIMEOUT_MS }),
      ),
    );
    toMark.forEach((runId, i) => {
      const res = marks[i];
      const r = res && Array.isArray(res.results) ? res.results[0] : null;
      if (isPlain(r) && r.ok === true && r.marked === true) out.won.push(runId);
      else if (isPlain(r) && r.ok === true && r.marked === false) return; // another session marked first
      else out.nudge.push(runId); // failed mark: nudge, never dispatch unmarked
    });

    for (const id of out.nudge) if (!state.nudged.includes(id)) state.nudged.push(id);
  } catch {
    // fail-open
  }
  return out;
}

/** Directive text for an evaluateRuns result, or null when nothing is eligible. */
export function directiveFor(result, located) {
  if (result.won.length > 0) return buildDispatch(result.won, dispatchAncestorId(located));
  if (result.nudge.length > 0) return buildNudge(result.nudge);
  return null;
}

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

function runCommands(runIds) {
  return runIds.map((id) => `\`/session-retrospective --run ${id}\``);
}

export function buildNudge(runIds) {
  const list = (runIds || []).filter(Boolean);
  const tail =
    `Skip if the feature still has items in progress, if background tasks or subagents are still ` +
    `running (revisit once they finish), or if this was minor housekeeping.`;
  if (list.length === 0) {
    return `Retrospective suggested — an implementation run closed. ` +
      `Consider running \`/session-retrospective\` to capture learnings before the session ends. ${tail}`;
  }
  return `Retrospective suggested — a run closed (run(s): ${list.join(', ')}). ` +
    `Consider running ${runCommands(list).join(' and ')} to capture learnings before the session ends. ${tail}`;
}

export function buildDispatch(runIds, ancestorId) {
  const list = (runIds || []).filter(Boolean);
  const ancestor = ancestorId || 'none configured';
  const invoke = list.length === 0 ? '`/session-retrospective`' : runCommands(list).join(' and ');
  return `Retrospective dispatch (mode: dispatch). Run ${list.join(', ')} closed. ` +
    `This directive is durable — act on it at the next run boundary: if background tasks or subagents ` +
    `are still running, hold it and dispatch only after the last of them completes and its results are ` +
    `processed. If further dispatch directives arrive while holding, merge them: dispatch ONE retrospective ` +
    `covering the union of their runs. Dispatch exactly ONE background retrospective via the Agent tool: ` +
    `subagent_type "general-purpose", model "sonnet", run_in_background true, prompt: invoke ` +
    `${invoke}, project ancestorId ${ancestor}, work ONLY from durable MCP ` +
    `state (session-tracking + delegation-metadata notes, schema config, agent-observation items — no ` +
    `conversation context), write the retrospective into the process-global 'Session Retrospectives' ` +
    `container, return only the retrospective item UUID plus a 2-3 line ` +
    `headline. Do not dispatch more than one retrospective for this run; skip entirely if the feature still ` +
    `has active items.`;
}
