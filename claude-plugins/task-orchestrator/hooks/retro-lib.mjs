// Shared helpers for the retrospective hooks (retro-trigger.mjs, retro-backstop.mjs,
// retro-subagent-stop.mjs, retro-ack.mjs).
// Boundary `scope` (the default): a retrospective is owed per completed scope (the item a run is scoped
// to), covering every run of that scope; evaluateScopes groups the root's runs, decides when a group is
// due, triages low-signal groups away at zero model tokens and claims the dispatch with run marks.
// Boundary `run`: a retrospective is owed per closed run on the project root that is not marked
// `retro`, evaluated by evaluateRuns as before.
// Either way the server-side mark (runs_write mark, first write wins) is the cross-session dedupe, so no
// hook keeps a cross-session marker file. Per-session state (closes seen, runs already nudged, claims
// this session won) lives in lib/markers.mjs session markers. No side effects at import time, no
// top-level I/O. The state machine is described in hooks/README.md, "Retrospective firing".

import { writeFileSync, mkdirSync, openSync, readSync, closeSync } from 'fs';
import { join } from 'path';
import os from 'os';
import { readSection, scalar, inlineScalar } from './yaml-lite.mjs';
import { sessionMarkerPath, readMarker, writeMarker } from './lib/markers.mjs';
import { callOp, apiBaseUrl } from './lib/api-client.mjs';
import { parseEnvelopeText } from './lib/envelope.mjs';
import { forClass } from '../scripts/lib/dispatch-policy.mjs';

const VALID_MODES = ['nudge', 'dispatch', 'off'];
const DEFAULT_MODE = 'nudge';
const DEFAULT_MIN_ITEMS = 3;
const VALID_BOUNDARIES = ['scope', 'run'];
const DEFAULT_BOUNDARY = 'scope';

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
//         minItems: 3
//         boundary: scope
// `dispatchThreshold` is the older name of `minItems` and is read when `minItems` is absent; `minItems`
// wins when both are present. A top-level `retrospective:` block is not read. Absent keys, an absent
// block, or an invalid value fall back to the documented default for that key; this never throws.
// Returns { mode, minItems, boundary }.
export function parseRetrospectiveConfig(configContent) {
  const result = { mode: DEFAULT_MODE, minItems: DEFAULT_MIN_ITEMS, boundary: DEFAULT_BOUNDARY };
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

    const rawMin = read('minItems');
    const rawThreshold = rawMin !== null ? rawMin : read('dispatchThreshold');
    if (rawThreshold !== null) {
      const n = Number(rawThreshold);
      if (Number.isInteger(n) && n >= 0) result.minItems = n;
    }

    const rawBoundary = read('boundary');
    if (rawBoundary !== null) {
      const val = rawBoundary.toLowerCase();
      if (VALID_BOUNDARIES.includes(val)) result.boundary = val;
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

/**
 * Per-session retro state: { opened: runId[], closes: runId[], sawClose, nudged: runId[], backstopNudged,
 * claims: [{anchor, runs, key, at, failed}], lastDispatchAt }. `opened` and `closes` are the runs this
 * session (its main thread or one of its subagents) opened or closed through runs_write; only those runs
 * owe this session a retrospective (ownsRun). `claims` are the dispatches this session won (key
 * retro-claim or retro-retry on the anchor run); `at` and `lastDispatchAt` are ISO instants.
 */
export function readSessionState(sessionId) {
  const raw = readMarker(sessionMarkerPath(sessionId, 'retro'), {});
  const claims = Array.isArray(raw.claims)
    ? raw.claims
        .filter((c) => isPlain(c) && typeof c.anchor === 'string' && typeof c.key === 'string')
        .map((c) => ({
          anchor: c.anchor,
          runs: Array.isArray(c.runs) ? c.runs.filter((v) => typeof v === 'string') : [c.anchor],
          key: c.key,
          at: typeof c.at === 'string' ? c.at : '',
          failed: c.failed === true,
        }))
    : [];
  return {
    opened: Array.isArray(raw.opened) ? raw.opened.filter((v) => typeof v === 'string') : [],
    closes: Array.isArray(raw.closes) ? raw.closes.filter((v) => typeof v === 'string') : [],
    sawClose: raw.sawClose === true,
    nudged: Array.isArray(raw.nudged) ? raw.nudged.filter((v) => typeof v === 'string') : [],
    backstopNudged: raw.backstopNudged === true,
    claims,
    lastDispatchAt: typeof raw.lastDispatchAt === 'string' ? raw.lastDispatchAt : undefined,
  };
}

export function writeSessionState(sessionId, state) {
  writeMarker(sessionMarkerPath(sessionId, 'retro'), state);
}

/**
 * True when this session opened or closed `runId`. A run another session drove owes that session its
 * retrospective, not this one; a director session is no exception, since a coordinator running in its
 * own session handles its own run.
 */
export function ownsRun(state, runId) {
  return Boolean(state) && typeof runId === 'string' && (
    (Array.isArray(state.opened) && state.opened.includes(runId)) ||
    (Array.isArray(state.closes) && state.closes.includes(runId)));
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

/** fixCycleBaseRun('r-x-a2') -> 'r-x' (a fix-cycle run label's base run id); null for any other label. */
export function fixCycleBaseRun(label) {
  const m = /^(.+)-a[1-9][0-9]*$/.exec(typeof label === 'string' ? label : '');
  return m ? m[1] : null;
}

/**
 * The run-boundary evaluation (trigger and backstop, boundary `run`). Lists the root's closed runs not
 * yet marked `retro` (closed in the last 24 h), keeps the ones this session opened or closed (ownsRun;
 * any other run is left unmarked for the session that drove it), reads the terminal count of at most 5
 * of them, and for each:
 * dispatch mode and terminal >= threshold -> try to mark (first write wins); a run whose mark this call
 * wrote is won and dispatched; a lost race (marked false) is silent; a failed mark is nudged, never
 * dispatched unmarked. Everything else is nudged. A fix-cycle run (label '<runId>-a<n>') is marked in
 * dispatch mode whatever its terminal count. Adds the nudged runs to `state.nudged` (the caller
 * persists). Returns { won: runId[], nudge: runId[], fixCycle: {[runId]: baseRunLabel} }; fail-open.
 */
export async function evaluateRuns({ rootId, config, state }) {
  const out = { won: [], nudge: [], fixCycle: {} };
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
      if (!ownsRun(state, view.runId)) continue; // another session's run: that session owes it
      if (state.nudged.includes(view.runId)) continue;
      if (candidates.includes(view.runId)) continue;
      candidates.push(view.runId);
      const base = fixCycleBaseRun(view.label);
      if (base) out.fixCycle[view.runId] = base;
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
      // A fix-cycle run ('<runId>-a<n>') always owes a retrospective: it often has one item, so the
      // terminal-count threshold would skip the only retrospective that sees the failure loop.
      if (config.mode === 'dispatch' && (terminal >= config.minItems || out.fixCycle[runId])) toMark.push(runId);
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

// ---- scope boundary ----------------------------------------------------------------------------

const MIN_MS = 60 * 1000;
const SCOPE_WINDOW_MS = 30 * 24 * 60 * 60 * 1000;
const ABANDONED_AFTER_MS = 7 * 24 * 60 * 60 * 1000;
const OWNER_HOLD_MS = 60 * MIN_MS;
const DISPATCH_GAP_MS = 60 * MIN_MS;
const CLAIM_DEAD_MS = 20 * MIN_MS;
const PASS_BUDGET_MS = 3500;
const WAVE_START_MS = 2000;
const MAX_GROUPS = 5;
const SCOPE_LIST_LIMIT = 200;
const TRIAGE_ITEM_CAP = 20;
// Items that are not implementation work: they never count toward the size or the session-tracking triage.
const NON_IMPL_TYPES = new Set(['retrospective-trend', 'container', 'improvement-proposal', 'agent-observation']);

const instantMs = (v) => (typeof v === 'string' ? Date.parse(v) : NaN);
// Two ids name the same item iff one key (lowercase, no dashes) is a prefix of the other: a list answers
// 8-hex ids where a run view and a get answer full ones (the prefix rule of scripts/run-planner-lib.mjs).
const idKey = (v) => (typeof v === 'string' ? v.toLowerCase().replace(/-/g, '') : '');
const sameId = (a, b) => {
  const x = idKey(a);
  const y = idKey(b);
  return x !== '' && y !== '' && (x.startsWith(y) || y.startsWith(x));
};

/** The anchor ordering of runs: earliest openedAt first, ties by runId ascending. */
function byOpened(a, b) {
  const d = (instantMs(a.openedAt) || 0) - (instantMs(b.openedAt) || 0);
  return d !== 0 ? d : a.runId < b.runId ? -1 : a.runId > b.runId ? 1 : 0;
}

/**
 * The clean-closure test of a session-tracking note: `Outcome:` starts with success or implemented, and
 * `Friction:` and `Deviations:` are `none` (trimmed, any case, one trailing period, CRLF allowed). A
 * missing line is not clean.
 */
export function sessionTrackingClean(body) {
  if (typeof body !== 'string') return false;
  const text = body.replace(/\r/g, '');
  const line = (label) => {
    const m = new RegExp('^[ \\t]*(?:[-*][ \\t]+)?' + label + ':[ \\t]*(.*)$', 'im').exec(text);
    return m ? m[1].trim() : null;
  };
  const outcome = line('Outcome');
  if (outcome === null || !/^(success|implemented)/i.test(outcome)) return false;
  const isNone = (v) => v !== null && v.toLowerCase().replace(/\.$/, '').trim() === 'none';
  return isNone(line('Friction')) && isNone(line('Deviations'));
}

class Skip extends Error {}

const SESSION_START_READ_BYTES = 65536;

/**
 * The start of the session a transcript belongs to: the first timestamp, in epoch ms, that parses on a
 * complete line within the first 65536 bytes of the file. NaN when the path is not a string, the file
 * cannot be read or no such line carries a timestamp; NaN means "unknown", and an unknown start makes an
 * unowned group ineligible in evaluateScopes.
 */
export function sessionStartMs(transcriptPath) {
  if (typeof transcriptPath !== 'string' || transcriptPath === '') return NaN;
  let fd;
  try {
    fd = openSync(transcriptPath, 'r');
    const buf = Buffer.alloc(SESSION_START_READ_BYTES);
    const n = readSync(fd, buf, 0, SESSION_START_READ_BYTES, 0);
    const lines = buf.toString('utf8', 0, n).split('\n');
    // The last piece is a complete line only when the file ended inside the window.
    if (n === SESSION_START_READ_BYTES) lines.pop();
    for (const line of lines) {
      if (line.trim() === '') continue;
      let rec;
      try {
        rec = JSON.parse(line);
      } catch {
        continue;
      }
      if (!isPlain(rec)) continue;
      const ms = instantMs(rec.timestamp);
      if (Number.isFinite(ms)) return ms;
    }
    return NaN;
  } catch {
    return NaN;
  } finally {
    if (fd !== undefined) {
      try {
        closeSync(fd);
      } catch {
        // swallow
      }
    }
  }
}

/**
 * The scope-boundary evaluation (trigger and backstop, boundary `scope`): one retrospective per
 * completed scope. Groups the root's runs (closed unmarked `retro` in the last 30 days, plus open and
 * paused runs) by scope, and for each of at most 5 actionable groups (a group that is not due, that this
 * session may not act on yet or whose read failed takes no slot; the walk stops at 5, at the end of the list
 * or when no new wave of 5 reads could start within 2000 ms of the pass start). A group with a run in
 * flight is not due, and an unscoped fix-cycle run whose base run is not in the pass joins no group:
 *   - a claim from a dead session or a failed retrospective is retried (retro-retry), then given up
 *     (retro-failed, one nudge); a session holding the claim takes it over only on its own failure
 *     report, any other session after 20 minutes;
 *   - an unclaimed group that is due, owned by this session (or, in a project configured for dispatch, a
 *     bystander's group that became due after this session started and has been due for 60 minutes) and
 *     not low-signal is claimed (retro-claim, first write wins on the anchor run) and dispatched; a
 *     low-signal group is marked retro-skipped + retro with no output.
 * A group this session does not own acts only when it is due, not stale, and its due moment is at or after
 * `sessionStartMs` (NaN, an unknown start, is never eligible): a fresh session never inherits a group that
 * was due before it began. An abandoned (stale) group belongs to its owner alone.
 * First dispatch claims are limited to one per hour per session; retries are exempt. Returns
 * { won: runId[], nudge: runId[], failed: runId[], stale: scopeId[], fixCycle: {[runId]: baseRunLabel} }
 * and updates `state` (claims, lastDispatchAt, nudged) for the caller to persist. Fail-open: a read
 * error skips the group or the pass and writes nothing for it. No call starts 3500 ms after the pass began.
 */
export async function evaluateScopes({ rootId, config, state, now = Date.now(), configuredMode = config.mode, sessionStartMs = NaN }) {
  const out = { won: [], nudge: [], failed: [], stale: [], fixCycle: {} };
  const t0 = Date.now();
  const dispatch = config.mode === 'dispatch';

  const call = async (tool, body) => {
    if (Date.now() - t0 > PASS_BUDGET_MS) throw new Skip('budget');
    const res = await callOp(tool, body, { timeoutMs: CALL_TIMEOUT_MS });
    if (!res) throw new Skip('no rest');
    return res;
  };
  const read = async (tool, body) => {
    const res = await call(tool, body);
    if (res.ok !== true || !isPlain(res.data)) throw new Skip(tool);
    return res.data;
  };
  // The items of a list read; a truncated page is a read error (a partial group would mislead).
  const readAll = async (tool, body) => {
    const data = await read(tool, body);
    if (!Array.isArray(data.items) || (isPlain(data.page) && data.page.truncated === true)) throw new Skip('page');
    return data.items;
  };
  // 'won' | 'lost' | 'error'
  const mark = async (runId, key) => {
    try {
      const res = await call('runs_write', { ops: [{ op: 'mark', runId, key }] });
      const r = Array.isArray(res.results) ? res.results[0] : null;
      if (isPlain(r) && r.ok === true) return r.marked === false ? 'lost' : 'won';
    } catch {
      // error
    }
    return 'error';
  };
  // Marks `keys` in order on every run, the runs in parallel; the anchor's first-key outcome decides.
  const markGroup = async (g, keys) => {
    const outcomes = await Promise.all(
      g.runs.map(async (r) => {
        let first = null;
        for (const key of keys) {
          const o = await mark(r.runId, key);
          if (first === null) first = o;
        }
        return first;
      }),
    );
    return outcomes[g.runs.indexOf(g.anchor)];
  };
  const getItem = async (itemId, fields) => {
    const data = await read('items_read', { select: 'get', itemId, ...(fields ? { fields } : {}) });
    return isPlain(data.item) ? data.item : data;
  };
  const subtree = async (scopeId) => {
    const items = await readAll('items_read', { select: 'list', ancestorId: scopeId, limit: SCOPE_LIST_LIMIT, fields: ['roleChangedAt'] });
    return items.filter((it) => isPlain(it) && !sameId(it.id, scopeId));
  };

  try {
    // 1. Group.
    const endedSince = new Date(now - SCOPE_WINDOW_MS).toISOString();
    const [closed, open, paused] = await Promise.all([
      readAll('runs_read', { select: 'list', rootId, status: 'closed', unmarked: 'retro', endedSince, limit: SCOPE_LIST_LIMIT }),
      readAll('runs_read', { select: 'list', rootId, status: 'open', limit: SCOPE_LIST_LIMIT }),
      readAll('runs_read', { select: 'list', rootId, status: 'paused', limit: SCOPE_LIST_LIMIT }),
    ]);
    const valid = (v) => isPlain(v) && typeof v.runId === 'string' && v.runId !== '';
    // A run the lists answer twice is one run: first row wins, in merge order closed, open, paused.
    const seenRunIds = new Set();
    const distinct = (rows) => rows.filter(valid).filter((v) => !seenRunIds.has(v.runId) && seenRunIds.add(v.runId));
    const closedDistinct = distinct(closed);
    const liveRuns = [...distinct(open), ...distinct(paused)];
    const closedRuns = closedDistinct.filter((v) => !(isPlain(v.marks) && 'retro' in v.marks));
    const all = [...closedRuns, ...liveRuns].sort(byOpened);
    const byLabel = new Map();
    for (const r of all) if (typeof r.label === 'string' && !byLabel.has(r.label)) byLabel.set(r.label, r);
    const scoped = (r) => typeof r.scopeId === 'string' && r.scopeId !== '' && !sameId(r.scopeId, rootId);
    const keyMemo = new Map();
    // The group key of a run; null when the run joins no group: an unscoped fix-cycle run whose base run is
    // not in this pass (it already has its retrospective mark, or lies outside the window) owes nothing.
    const keyOf = (r, depth = 0) => {
      if (keyMemo.has(r.runId)) return keyMemo.get(r.runId);
      let key = r.runId;
      if (scoped(r)) key = r.scopeId.toLowerCase();
      else {
        const base = fixCycleBaseRun(r.label);
        if (base) {
          const target = byLabel.get(base);
          if (!target) key = null;
          else if (target !== r && depth < 5) key = keyOf(target, depth + 1);
        }
      }
      keyMemo.set(r.runId, key);
      return key;
    };
    const groups = new Map();
    for (const r of all) {
      const key = keyOf(r);
      if (key === null) continue;
      if (!groups.has(key)) groups.set(key, { key, scopeId: null, runs: [], live: 0 });
      const g = groups.get(key);
      if (scoped(r)) g.scopeId = r.scopeId;
      if (liveRuns.includes(r)) g.live += 1;
      else g.runs.push(r);
    }
    const pending = [];
    for (const g of groups.values()) {
      if (g.runs.length === 0) continue;
      g.runs.sort(byOpened);
      g.anchor = g.runs[0];
      g.marks = isPlain(g.anchor.marks) ? g.anchor.marks : {};
      if ('retro-failed' in g.marks) continue;
      if (g.live > 0) continue; // a group with a run in flight is not due
      g.newestClosedMs = Math.max(...g.runs.map((r) => instantMs(r.closedAt) || 0));
      g.dead = 'retro-claim' in g.marks;
      pending.push(g);
    }
    // Claims to retry or give up first, then the newest groups; at most 5 actionable groups a pass.
    pending.sort((a, b) => Number(b.dead) - Number(a.dead) || b.newestClosedMs - a.newestClosedMs);
    const claimOf = (g, key) => state.claims.find((c) => c.anchor === g.anchor.runId && c.key === key);
    // A group this session opened or closed a run of (ownsRun): main thread or subagent, open or close.
    const ownedByThisSession = (g) => g.runs.some((r) => ownsRun(state, r.runId));
    const lastDispatchMs = instantMs(state.lastDispatchAt);
    const hourlyOk = Number.isNaN(lastDispatchMs) || now - lastDispatchMs >= DISPATCH_GAP_MS;

    // 4. Triage: true when the group is low-signal (no reason to spend model tokens on it). Each test
    // below is a signal that ends the triage with false; reads stop at the first signal.
    const lowSignal = async (g) => {
      if (g.runs.some((r) => fixCycleBaseRun(r.label))) return false;
      let items;
      if (g.scopeId) items = await subtree(g.scopeId);
      else {
        const seen = new Set();
        for (const r of g.runs) {
          const data = await read('runs_read', { select: 'seats', runId: r.runId, ended: true });
          for (const s of Array.isArray(data.seats) ? data.seats : []) if (isPlain(s) && typeof s.itemId === 'string') seen.add(s.itemId);
        }
        if (seen.size > TRIAGE_ITEM_CAP) return false;
        items = await Promise.all([...seen].map((id) => getItem(id)));
      }
      items = items.filter((it) => isPlain(it) && !NON_IMPL_TYPES.has(it.type));
      if (items.filter((it) => it.role === 'terminal').length >= config.minItems) return false;
      const openedMs = Math.min(...g.runs.map((r) => instantMs(r.openedAt)).filter((n) => !Number.isNaN(n)));
      if (Number.isFinite(openedMs)) {
        // One row is the signal: a page that says more rows exist (truncated) is a signal too.
        const obs = await read('items_read', { select: 'list', type: 'agent-observation', createdAfter: new Date(openedMs).toISOString(), limit: 1 });
        if (!Array.isArray(obs.items)) throw new Skip('page');
        if (obs.items.length > 0) return false;
      }
      if (items.length > TRIAGE_ITEM_CAP) return false;
      const clean = await Promise.all(
        items.map(async (it) => {
          const res = await call('notes_read', { select: 'get', itemId: it.id, key: 'session-tracking' });
          if (res.ok !== true) {
            if (res.error && res.error.code === 'not_found') return false; // a missing note is a signal
            throw new Skip('notes_read');
          }
          return sessionTrackingClean(isPlain(res.data) ? res.data.body : undefined);
        }),
      );
      return clean.every(Boolean);
    };

    // 2. Due: { due, stale, since } (since: the instant the group completed, for the orphan rule).
    const dueOf = async (g) => {
      if (!g.scopeId) return { due: true, stale: false, since: g.newestClosedMs };
      const scope = await getItem(g.scopeId, ['roleChangedAt']);
      if (scope.role === 'terminal') {
        const items = await subtree(g.scopeId);
        if (items.some((it) => it.role === 'work' || it.role === 'review')) return { due: false };
        return { due: true, stale: false, since: instantMs(scope.roleChangedAt) };
      }
      if (g.live === 0 && g.newestClosedMs > 0 && now - g.newestClosedMs > ABANDONED_AFTER_MS) return { due: true, stale: true, since: NaN };
      return { due: false };
    };

    // 6 / 7. A claim exists: what to write next, or null. Only a dispatching session writes either mark. A
    // session that holds the claim takes it over only on its own failure report; the 20 minute age test is for
    // sessions that hold none (a Stop hook that never saw the retrospective's transcript leaves it unfailed).
    const deadStep = (g) => {
      if (!dispatch) return null;
      const retried = 'retro-retry' in g.marks;
      const key = retried ? 'retro-retry' : 'retro-claim';
      const mine = claimOf(g, key);
      const takeOver = mine ? Boolean(mine.failed) : now - instantMs(g.marks[key]) > CLAIM_DEAD_MS;
      if (!takeOver) return null;
      return retried ? { kind: 'failed', mark: 'retro-failed' } : { kind: 'retry', mark: 'retro-retry' };
    };

    // 2 / 3. A group that can act: a claim to retry or give up, or a due group this session owns (or a
    // bystander's that became due after this session started). null otherwise, which takes no slot of the 5.
    // Outside dispatch mode a group this session already nudged stays unmarked and can act no more, so it
    // takes no slot either.
    // A group this session does not own may act only in a project configured for dispatch, and only when it
    // is due, not stale, and its due moment is at or after the session start (both finite). A first claim
    // also waits out the owner hold; a retry or give-up does not (the claim already exists).
    const bystanderEligible = (due, firstClaim) => {
      if (!dispatch || configuredMode !== 'dispatch') return false;
      if (!due.due || due.stale) return false;
      if (!Number.isFinite(due.since) || !Number.isFinite(sessionStartMs) || due.since < sessionStartMs) return false;
      return !firstClaim || now - due.since > OWNER_HOLD_MS;
    };
    const qualify = async (g) => {
      try {
        if (!dispatch && g.runs.every((r) => state.nudged.includes(r.runId))) return null;
        const owned = ownedByThisSession(g);
        if (g.dead) {
          if (!deadStep(g)) return null;
          if (!owned && !bystanderEligible(await dueOf(g), false)) return null;
          return { g };
        }
        const due = await dueOf(g);
        if (!due.due) return null;
        // Not this session's scope: the CONFIGURED mode must be dispatch (a director's forced dispatch does
        // not count), and the group must have become due after this session started.
        if (!owned && !bystanderEligible(due, true)) return null;
        return { g, due };
      } catch (e) {
        if (e instanceof Skip) return null;
        throw e;
      }
    };
    // Waves of 5 in today's order until 5 groups can act, the list ends, or no new wave could start within
    // 2000 ms of the pass start.
    const actionable = [];
    for (let next = 0; next < pending.length && actionable.length < MAX_GROUPS; next += MAX_GROUPS) {
      if (Date.now() - t0 > WAVE_START_MS) break;
      const wave = await Promise.all(pending.slice(next, next + MAX_GROUPS).map(qualify));
      for (const q of wave) if (q && actionable.length < MAX_GROUPS) actionable.push(q);
    }

    const handle = async ({ g, due }) => {
      try {
        if (g.dead) {
          const step = deadStep(g);
          return step && (await markGroup(g, [step.mark])) === 'won' ? { kind: step.kind, g } : null;
        }
        // 4. Triage.
        if (await lowSignal(g)) {
          await markGroup(g, ['retro-skipped', 'retro']);
          return null;
        }
        // 9. Nudge mode.
        if (!dispatch) return { kind: 'nudge', g };
        // 5. Dispatch.
        if (!hourlyOk) return null;
        return (await markGroup(g, ['retro-claim'])) === 'won' ? { kind: 'claim', g, stale: due.stale } : null;
      } catch (e) {
        if (e instanceof Skip) return null;
        throw e;
      }
    };
    const actions = (await Promise.all(actionable.map(handle))).filter(Boolean);

    const at = new Date(now).toISOString();
    const record = (g, key) => {
      state.claims = state.claims.filter((c) => !(c.anchor === g.anchor.runId && c.key === key));
      state.claims.push({ anchor: g.anchor.runId, runs: g.runs.map((r) => r.runId), key, at, failed: false });
    };
    for (const a of actions) {
      const ids = a.g.runs.map((r) => r.runId);
      for (const r of a.g.runs) {
        const base = fixCycleBaseRun(r.label);
        if (base) out.fixCycle[r.runId] = base;
      }
      if (a.kind === 'claim' || a.kind === 'retry') {
        out.won.push(...ids);
        if (a.stale && a.g.scopeId) out.stale.push(a.g.scopeId.slice(0, 8).toLowerCase());
        record(a.g, a.kind === 'claim' ? 'retro-claim' : 'retro-retry');
        if (a.kind === 'claim') state.lastDispatchAt = at;
      } else if (a.kind === 'failed') {
        out.failed.push(...ids);
        state.claims = state.claims.filter((c) => c.anchor !== a.g.anchor.runId);
      } else {
        out.nudge.push(...ids);
        for (const id of ids) if (!state.nudged.includes(id)) state.nudged.push(id);
      }
    }
  } catch {
    // fail-open: a read error skips the pass
  }
  return out;
}

/**
 * A director session never runs a retrospective in its own context (its altitude rule forbids note-body
 * reads), so it is always treated as dispatch mode. At the run boundary the threshold is 0 as well: every
 * closed run it sees is marked (first write wins) and dispatched. At the scope boundary `minItems` stays,
 * because it is the triage's size signal. `off` stays off.
 */
export function directorConfig(config) {
  if (config.mode === 'off') return config;
  return config.boundary === 'run' ? { ...config, mode: 'dispatch', minItems: 0 } : { ...config, mode: 'dispatch' };
}

/**
 * Directive text for an evaluateRuns result, or null when nothing is eligible. With `director`, every
 * eligible run (won or nudged) is named in one dispatch directive and nothing is nudged.
 */
export function directiveFor(result, located, { director = false, transcriptPath } = {}) {
  const stale = Array.isArray(result.stale) && result.stale.length > 0 ? result.stale : undefined;
  const failed = !director && Array.isArray(result.failed) ? result.failed : [];
  const parts = [];
  if (director) {
    const runs = [...result.won, ...result.nudge];
    if (runs.length > 0) parts.push(buildDispatch(runs, dispatchAncestorId(located), result.fixCycle, { transcriptPath, stale, director }));
  } else {
    if (result.won.length > 0) parts.push(buildDispatch(result.won, dispatchAncestorId(located), result.fixCycle, { transcriptPath, stale, director }));
    else if (result.nudge.length > 0) parts.push(buildNudge(result.nudge, { transcriptPath }));
  }
  if (failed.length > 0) parts.push(buildNudge(failed, { transcriptPath, failed: true }));
  return parts.length > 0 ? parts.join(' ') : null;
}

// Extracts the tool's JSON payload from a PostToolUse hook's tool_response field. The exact
// delivered shape is not pinned down by the hooks docs for MCP tools, so this is deliberately
// shape-tolerant — it accepts every plausible encoding rather than betting on one:
//   1. CallToolResult wrapper:   { content: [{ type: "text", text: "<json>" }] }
//   2. Bare content-block array: [{ type: "text", text: "<json>" }]
//   3. Single content block:     { type: "text", text: "<json>" }
//   4. Already-parsed payload:   { results: [...], summary: {...} } — returned as-is
//   5. Raw JSON string:          "<json>"
// A text that is not JSON as a whole is read as a 4.0 MCP error text (`<code>: <message>`, a newline,
// the error envelope) through parseEnvelopeText, which returns the envelope or null.
// Returns null on any failure (fail-open) so callers stay silent on unknown shapes.
export function extractResponseJson(toolResponse) {
  try {
    if (toolResponse == null) return null;
    if (typeof toolResponse === 'string') return parseEnvelopeText(toolResponse);
    if (Array.isArray(toolResponse)) {
      const block = toolResponse.find((b) => b && typeof b.text === 'string');
      return block ? parseEnvelopeText(block.text) : null;
    }
    if (Array.isArray(toolResponse.content)) {
      const block = toolResponse.content.find((b) => b && typeof b.text === 'string');
      return block ? parseEnvelopeText(block.text) : null;
    }
    if (typeof toolResponse.text === 'string') return parseEnvelopeText(toolResponse.text);
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

// ONE invocation covering every run: `--run` repeats and the skill analyzes the union of the runs.
function runCommand(runIds, sessionDir) {
  const dir = sessionDir ? ` --session-dir "${sessionDir}"` : '';
  return `\`/session-retrospective ${runIds.map((id) => `--run ${id}`).join(' ')}${dir}\``;
}

// The session folder of a main transcript path: `<dir>.jsonl` sits beside `<dir>`. A path that does not end
// in `.jsonl` (case-sensitive) names no folder.
function sessionDirOf(transcriptPath) {
  if (typeof transcriptPath !== 'string' || !transcriptPath.endsWith('.jsonl')) return '';
  return transcriptPath.replaceAll('\\', '/').slice(0, -'.jsonl'.length);
}

/**
 * Nudge text: ONE `/session-retrospective` invocation naming every run, plus the session folder when
 * `opts.transcriptPath` names one. `opts.failed` is the second-failure form (the dispatched
 * retrospective failed twice; this nudge is the last word on it).
 */
export function buildNudge(runIds, opts = {}) {
  const list = (runIds || []).filter(Boolean);
  const sessionDir = sessionDirOf(isPlain(opts) ? opts.transcriptPath : undefined);
  const failed = isPlain(opts) && opts.failed === true;
  const tail =
    `Skip if background tasks or subagents are still running (revisit once they finish), ` +
    `or if this was minor housekeeping.`;
  if (list.length === 0) {
    const invoke = sessionDir ? `\`/session-retrospective --session-dir "${sessionDir}"\`` : '`/session-retrospective`';
    return `Retrospective suggested — an implementation run closed. ` +
      `Consider running ${invoke} to capture learnings before the session ends. ${tail}`;
  }
  if (failed) {
    return `Retrospective suggested — the dispatched retrospective for run(s) ${list.join(', ')} failed twice and will not be dispatched again. ` +
      `Run ${runCommand(list, sessionDir)} yourself if the learnings are worth capturing.`;
  }
  return `Retrospective suggested — a run closed (run(s): ${list.join(', ')}). ` +
    `Consider running ${runCommand(list, sessionDir)} to capture learnings before the session ends. ${tail}`;
}

/**
 * fixCycle ({[runId]: baseRunLabel}, optional): runs in `runIds` that are fix-cycle runs. Their
 * retrospective reports the base run's whole failure loop, so the directive names each base run.
 * opts.director: the caller is a director session; memory: lines are relayed, not saved.
 * opts.stale: the scope ids (an array; a string or `true` also reads) of scopes that never completed.
 */
export function buildDispatch(runIds, ancestorId, fixCycle, opts = {}) {
  const list = (runIds || []).filter(Boolean);
  const ancestor = ancestorId || 'none configured';
  const sessionDir = sessionDirOf(isPlain(opts) ? opts.transcriptPath : undefined);
  const invoke = list.length === 0 ? (sessionDir ? `\`/session-retrospective --session-dir "${sessionDir}"\`` : '`/session-retrospective`') : runCommand(list, sessionDir);
  const run8 = list.length === 0 ? 'unknown' : String(list[0]).slice(0, 8).toLowerCase();
  // The retrospective agent runs at the dispatch policy's `research` class.
  const { model, effort } = forClass('research');
  const loops = list.filter((id) => isPlain(fixCycle) && typeof fixCycle[id] === 'string').map((id) => `${id} (base run ${fixCycle[id]})`);
  const loopNote = loops.length === 0 ? '' :
    `Fix-cycle run(s) ${loops.join(', ')}: report the base run's whole fix loop (every -a<n> cycle so far) in the fix-cycle section. `;
  const staleIds = isPlain(opts) && Array.isArray(opts.stale) ? opts.stale.map((v) => String(v).slice(0, 8)).filter(Boolean) : [];
  const staleNote = isPlain(opts) && (staleIds.length > 0 || opts.stale === true || (typeof opts.stale === 'string' && opts.stale !== ''))
    ? `Scope(s) ${staleIds.length > 0 ? staleIds.join(', ') : typeof opts.stale === 'string' ? opts.stale.slice(0, 8) : 'unknown'} never completed: start the retrospective title with [stale]. `
    : '';
  // A director session cannot write memory files (director-guard denies Write and Edit): it relays the lines instead.
  const memoryNote = isPlain(opts) && opts.director === true
    ? 'relay each memory: line of its result in your report to the user (this session cannot write memory files). '
    : 'save each memory: line of its result to your memory. ';
  return `Retrospective dispatch (mode: dispatch). Run ${list.join(', ')} closed. ${loopNote}${staleNote}` +
    `This directive is durable — act on it at the next run boundary: if background tasks or subagents ` +
    `are still running, hold it and dispatch only after the last of them completes and its results are ` +
    `processed. If further dispatch directives arrive while holding, merge them: dispatch ONE retrospective ` +
    `covering the union of their runs, with the description of the first run of the union and the session folder. When it returns, ${memoryNote}Dispatch exactly ONE background retrospective via the Agent tool: ` +
    `subagent_type "general-purpose", description "retrospective:${run8}", model "${model}", effort "${effort}", run_in_background true, prompt: invoke ` +
    `${invoke}, project ancestorId ${ancestor}, work ONLY from durable MCP ` +
    `state (session-tracking + delegation-metadata notes, schema config, agent-observation items — no ` +
    `conversation context), write the retrospective into the process-global 'Session Retrospectives' ` +
    `container, return only the retrospective item UUID, a 2-3 line ` +
    `headline and any memory: lines.`;
}
