// Shared harness for the retro-* hook tests (c7e43c6d): a fake REST server that plays
// POST /api/v1/ops/runs_read and POST /api/v1/ops/runs_write with 4.0 response envelopes, a hermetic
// sandbox (own TEMP, own TASK_ORCHESTRATOR_HOME, own config dir), and an async hook runner.
//
// MUST use async `spawn` (never `spawnSync`): the fake server runs in THIS process, and spawnSync
// blocks this event loop for the life of the child, so the child's fetch could never be answered
// and every REST scenario would degrade into a fail-open false green (see phase-guard.test.mjs:4).
//
// Oracle for the envelopes: current/docs/api-reference.md (runs_read list / frontier, runs_write
// close / mark, "Response envelope", "List data") and current/docs/api-rest.md section 20a.

import './hermetic-claude-config.mjs';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, readdirSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, sep } from 'node:path';

export const HOOKS = {
  trigger: fileURLToPath(new URL('../retro-trigger.mjs', import.meta.url)),
  backstop: fileURLToPath(new URL('../retro-backstop.mjs', import.meta.url)),
  ack: fileURLToPath(new URL('../retro-ack.mjs', import.meta.url)),
  subagentStop: fileURLToPath(new URL('../retro-subagent-stop.mjs', import.meta.url)),
};

export const HOOKS_CONFIG = fileURLToPath(new URL('../hooks-config.json', import.meta.url));

/** A full-UUID run id, stable per n. */
export const rid = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
/** The project root id every project-scope fixture configures (a valid plain-token id). */
export const ROOT = '5a5a5a5a-0000-4000-8000-0000000000aa';

const NL = String.fromCharCode(10);
const OPS_PREFIX = '/api/v1/ops/';

/**
 * config.yaml text. rootId null omits the project block; mode / threshold / minItems undefined omit the key.
 * boundary defaults to 'run' (the pre-scope behaviour the existing suites pin); null omits the key (the server
 * default, scope, applies); any other string is written as is. threshold writes the older dispatchThreshold key.
 * The retrospective settings live under extensions.claude-code.retrospective (AC6).
 */
export function retroConfig({ rootId = ROOT, mode, threshold, minItems, boundary = 'run', extraRetro = [] } = {}) {
  const lines = [];
  if (rootId) lines.push('project:', `  rootId: "${rootId}"`, '');
  lines.push('extensions:', '  claude-code:', '    retrospective:');
  if (mode !== undefined) lines.push(`      mode: ${mode}`);
  if (threshold !== undefined) lines.push(`      dispatchThreshold: ${threshold}`);
  if (minItems !== undefined) lines.push(`      minItems: ${minItems}`);
  if (boundary !== null) lines.push(`      boundary: ${boundary}`);
  for (const l of extraRetro) lines.push(`      ${l}`);
  lines.push('');
  return lines.join(NL);
}

// ---- envelopes and hook payloads -------------------------------------------------------------

export function okEnvelope(data) {
  return { ok: true, data, meta: { reqId: 'k7f3q9ab' } };
}

export function errEnvelope(code, message = code) {
  return { ok: false, error: { code, kind: 'permanent', message }, meta: { reqId: 'k7f3q9ab' } };
}

/** How a PostToolUse tool_response can carry the envelope: structuredContent, content[0].text, or bare. */
export function wrapResponse(env, via = 'structured') {
  if (via === 'text') return { content: [{ type: 'text', text: JSON.stringify(env) }] };
  if (via === 'bare') return env;
  return { content: [{ type: 'text', text: env.ok ? 'ok' : 'failed' }], structuredContent: env, isError: !env.ok };
}

export function closeResultOk(runId) {
  return {
    ok: true,
    runId,
    rootId: ROOT,
    status: 'closed',
    terminalReason: 'completed',
    snapshot: { reason: 'completed', countsByRole: { queue: 0, work: 0, review: 0, blocked: 0, terminal: 3 }, inFlight: 0, released: 0, openDecisions: 0 },
    version: 2,
  };
}

export function openResultOk(runId) {
  return { ok: true, runId, rootId: ROOT, status: 'open', version: 1 };
}

export function failedResult(code = 'invalid_run_state') {
  return { ok: false, error: { code, kind: 'permanent', message: code } };
}

/** The batch envelope a write tool answers: ok:false + partial_failure + full data when any element failed. */
export function batchEnvelope(results) {
  const failed = results.filter((r) => r.ok !== true).length;
  const data = { results, summary: { ok: results.length - failed, failed } };
  if (failed === 0) return okEnvelope(data);
  return { ok: false, error: { code: 'partial_failure', kind: 'permanent', message: `${failed} of ${results.length} failed` }, data, meta: { reqId: 'k7f3q9ab' } };
}

export const RUNS_WRITE = 'mcp__mcp-task-orchestrator__runs_write';

/** PostToolUse payload for a runs_write call. ops and results are index-aligned. */
export function writePayload({ sessionId, ops, results, via = 'structured', toolName = RUNS_WRITE, extra = {} }) {
  return {
    session_id: sessionId,
    tool_name: toolName,
    tool_input: { ops },
    tool_response: wrapResponse(batchEnvelope(results), via),
    ...extra,
  };
}

/** The common case: one successful close of runId. */
export function closePayload(sessionId, runId, opts = {}) {
  return writePayload({
    sessionId,
    ops: [{ op: 'close', runId, reason: 'completed' }],
    results: [closeResultOk(runId)],
    ...opts,
  });
}

// ---- sandbox --------------------------------------------------------------------------------

/**
 * scope 'project' (default): the config lives in AGENT_CONFIG_DIR (= sbx.cfg, also the cwd).
 * scope 'user': AGENT_CONFIG_DIR is removed and the config lives in TASK_ORCHESTRATOR_HOME.
 * config null writes no config.yaml at all.
 */
export function makeSandbox({ config = null, scope = 'project' } = {}) {
  const base = mkdtempSync(join(tmpdir(), 'to-retro-'));
  const dirs = { base, cfg: join(base, 'cfg'), home: join(base, 'home'), tmp: join(base, 'tmp'), cwd: join(base, 'cwd') };
  for (const d of [dirs.cfg, dirs.home, dirs.tmp, dirs.cwd]) mkdirSync(d);
  const sbx = {
    ...dirs,
    scope,
    setConfig(text) {
      const target = scope === 'user' ? dirs.home : dirs.cfg;
      mkdirSync(join(target, '.taskorchestrator'), { recursive: true });
      writeFileSync(join(target, '.taskorchestrator', 'config.yaml'), text, 'utf-8');
    },
    cleanup() {
      rmSync(base, { recursive: true, force: true });
    },
  };
  if (config !== null) sbx.setConfig(config);
  return sbx;
}

/** Every file and directory under dir, as sorted relative paths (empty list when dir is absent). */
export function listTree(dir) {
  const out = [];
  const walk = (d) => {
    let names;
    try {
      names = readdirSync(d);
    } catch {
      return;
    }
    for (const n of names) {
      const p = join(d, n);
      out.push(relative(dir, p).split(sep).join('/'));
      let isDir = false;
      try {
        isDir = statSync(p).isDirectory();
      } catch {
        /* vanished */
      }
      if (isDir) walk(p);
    }
  };
  walk(dir);
  return out.sort();
}

// ---- hook runner ----------------------------------------------------------------------------

const SCRUBBED_ENV = [
  'AGENT_CONFIG_DIR',
  'TASK_ORCHESTRATOR_API_URL',
  'TASK_ORCHESTRATOR_API_TOKEN',
  'TASK_ORCHESTRATOR_MODE',
  'TASK_ORCHESTRATOR_HOME',
  'TASK_ORCHESTRATOR_CEILING',
  'RETRO_HOOK_DEBUG',
];

/**
 * Spawns a hook asynchronously. input: an object (JSON-encoded), a string (sent raw) or null (stdin
 * closed empty). Resolves { status, stdout, stderr, json } where json is the parsed stdout or null.
 */
export function runHook(hookPath, input, sbx, { apiUrl, env = {}, timeoutMs = 20000, args = [] } = {}) {
  const e = { ...process.env };
  for (const k of SCRUBBED_ENV) delete e[k];
  Object.assign(e, {
    TEMP: sbx.tmp,
    TMP: sbx.tmp,
    TMPDIR: sbx.tmp,
    TASK_ORCHESTRATOR_HOME: sbx.home,
    TASK_ORCHESTRATOR_CEILING: sbx.base,
  });
  if (sbx.scope !== 'user') e.AGENT_CONFIG_DIR = sbx.cfg;
  if (apiUrl) e.TASK_ORCHESTRATOR_API_URL = apiUrl;
  Object.assign(e, env);
  const cwd = sbx.scope === 'user' ? sbx.cwd : sbx.cfg;
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [hookPath, ...args], { env: e, cwd });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill();
      rejectPromise(new Error(`hook ${hookPath} timed out after ${timeoutMs} ms`));
    }, timeoutMs);
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.stderr.on('data', (d) => {
      stderr += d;
    });
    child.on('error', (err) => {
      clearTimeout(timer);
      rejectPromise(err);
    });
    child.on('close', (status) => {
      clearTimeout(timer);
      let json = null;
      try {
        json = JSON.parse(stdout.trim());
      } catch {
        json = null;
      }
      resolvePromise({ status, stdout, stderr, json });
    });
    if (input === null) child.stdin.end();
    else child.stdin.end(typeof input === 'string' ? input : JSON.stringify(input));
  });
}

/** additionalContext of a PostToolUse answer, or null. */
export function contextOf(res) {
  return res.json?.hookSpecificOutput?.additionalContext ?? null;
}

// ---- the fake REST server -------------------------------------------------------------------

/**
 * init.runs: [{ runId, rootId?, members?, terminal?, marks?, label?, scopeId?, status?, openedAt?, closedAt?,
 *   seats? }]. marks omitted -> the view carries no marks field; marks null -> marks: null; mark values are ISO
 * instants (a server mark is stamped with the time of the write). scopeId '' is written as ''. status is
 * 'closed' (default), 'open' or 'paused'; closedAt / terminalReason are written for a closed run only.
 * seats: [{ itemId, seat? }] answered by runs_read seats (ended seats; ended:true is honoured).
 * init.items: [{ id, type, role, parent?, roleChangedAt?, createdAt? }] answered by items_read get / list.
 * init.notes: { [itemId]: { [key]: body } } answered by notes_read get; an absent note is 404 not_found.
 * init.behavior: {
 *   list: 'ok' | 'http500' | 'badjson' | 'hang',
 *   frontier: { [runId]: 'http500' },
 *   mark: 'ok' | 'http500' | 'forbidden' | 'itemfail' | 'badjson' | 'hang',
 *   runGet / seats / itemsGet / itemsList / notes: 'ok' | 'http500' | 'hang',
 *   dup: true         -> list answers every row twice (both copies count in page.total)
 *   stale: true       -> list and get answer every run with only its static marks (a snapshot older than
 *                        another session's mark), whatever the server holds
 *   unfiltered: true  -> list ignores the `unmarked` filter and returns every run, with the server's
 *                        marks merged into each view
 * }
 * list filters honoured: rootId, status, unmarked <key> (closed runs without the key), endedSince (only for a
 * run whose closedAt the fixture set), limit. mark is first-write-wins per (runId, key), static marks
 * included: a replay answers marked:false.
 * Both lists answer page: { total: matches before limit, truncated: rows returned < total }. The runs list keeps
 * full runIds (RunView.kt:46-48); the items list answers id and parentId as their first 8 characters
 * (ItemView.kt:24,109) while items_read get answers full ids; a list filter accepts either form of an id.
 */
export async function startFakeRuns(init = {}) {
  const runs = (init.runs ?? []).map((r) => ({ rootId: ROOT, members: r.terminal ?? 0, terminal: 0, ...r }));
  const items = (init.items ?? []).map((i) => ({ ...i }));
  const notes = init.notes ?? {};
  const behavior = {
    list: 'ok', frontier: {}, mark: 'ok', runGet: 'ok', seats: 'ok', itemsGet: 'ok', itemsList: 'ok', notes: 'ok',
    dup: false, stale: false, unfiltered: false, ...init.behavior,
  };
  const requests = [];
  const markOutcomes = [];
  const marked = new Map();
  const sockets = new Set();

  const staticMark = (r, key) => Boolean(r.marks && r.marks[key]);
  const isMarked = (r, key = 'retro') => staticMark(r, key) || Boolean(marked.get(r.runId)?.[key]);
  const viewOf = (r, withServer) => {
    const server = withServer ? marked.get(r.runId) ?? null : null;
    const status = r.status ?? 'closed';
    const v = {
      runId: r.runId,
      rootId: r.rootId,
      status,
      openedAt: r.openedAt ?? '2026-10-16T10:00:00Z',
      coordinatorStalled: false,
      stallAfterSeconds: 600,
      usage: { reportedTokens: 0, reportedCostUsd: 0, observedTokens: 0 },
      hints: {},
      members: r.members,
      activeSeats: 0,
      stalledSeats: 0,
      version: 2,
    };
    if (status === 'closed') {
      v.terminalReason = 'completed';
      v.closedAt = r.closedAt ?? '2026-10-16T11:00:00Z';
    }
    if (typeof r.label === 'string') v.label = r.label;
    if (typeof r.scopeId === 'string') v.scopeId = r.scopeId;
    if ('marks' in r || server) {
      const base = r.marks && typeof r.marks === 'object' ? r.marks : {};
      v.marks = r.marks === null && !server ? null : { ...base, ...(server ?? {}) };
    }
    return v;
  };
  const findRun = (id) => runs.find((x) => typeof id === 'string' && (x.runId === id || x.runId.startsWith(id)));
  const findItem = (id) => items.find((x) => typeof id === 'string' && (x.id === id || x.id.startsWith(id)));
  const sameItemId = (full, given) => typeof given === 'string' && given.length > 0 && full.startsWith(given);
  const itemView = (i, short = false) => {
    const v = { id: short ? i.id.slice(0, 8) : i.id, type: i.type, role: i.role, title: `item ${i.id.slice(-4)}`, version: 1 };
    if (i.parent) v.parentId = short ? i.parent.slice(0, 8) : i.parent;
    if (i.createdAt) v.createdAt = i.createdAt;
    if (i.roleChangedAt) v.roleChangedAt = i.roleChangedAt;
    return v;
  };
  const subtreeOf = (id) => {
    const out = [];
    const root = findItem(id);
    if (!root) return out;
    out.push(root);
    for (let i = 0; i < out.length; i++) for (const c of items) if (c.parent === out[i].id) out.push(c);
    return out;
  };

  const send = (res, status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(typeof body === 'string' ? body : JSON.stringify(body));
  };
  /** A behavior knob: 'http500' / 'hang' answer here and return true. */
  const faulty = (res, mode) => {
    if (mode === 'hang') return true;
    if (mode === 'http500') {
      send(res, 500, errEnvelope('internal'));
      return true;
    }
    return false;
  };

  const handle = (req, res, body) => {
    const path = req.url.split('?')[0];
    requests.push({ method: req.method, path, body });
    const tool = path.startsWith(OPS_PREFIX) ? path.slice(OPS_PREFIX.length) : null;
    if (req.method !== 'POST' || !tool || !/^[a-z_]+$/.test(tool)) return send(res, 404, errEnvelope('not_found'));
    const b = body && typeof body === 'object' ? body : {};

    if (tool === 'runs_read' && b.select === 'list') {
      if (behavior.list === 'hang') return;
      if (behavior.list === 'http500') return send(res, 500, errEnvelope('internal'));
      if (behavior.list === 'badjson') return send(res, 200, 'not json {');
      let rows = runs;
      const withServer = !behavior.stale;
      if (typeof b.rootId === 'string') rows = rows.filter((r) => r.rootId === b.rootId);
      if (typeof b.status === 'string') rows = rows.filter((r) => (r.status ?? 'closed') === b.status);
      if (typeof b.endedSince === 'string') {
        rows = rows.filter((r) => (r.status ?? 'closed') !== 'closed' || !r.closedAt || Date.parse(r.closedAt) >= Date.parse(b.endedSince));
      }
      if (typeof b.unmarked === 'string' && !behavior.unfiltered) {
        const key = b.unmarked;
        rows = rows.filter((r) => (r.status ?? 'closed') === 'closed' && !(behavior.stale ? staticMark(r, key) : isMarked(r, key)));
      } else if (b.unmarked === undefined && b.status === undefined && !behavior.unfiltered && !behavior.stale) {
        rows = rows.filter((r) => !isMarked(r));
      }
      let views = rows.map((r) => viewOf(r, withServer));
      if (behavior.dup) views = views.flatMap((v) => [v, v]);
      const total = views.length;
      if (Number.isInteger(b.limit)) views = views.slice(0, b.limit);
      return send(res, 200, okEnvelope({ items: views, page: { total, truncated: views.length < total } }));
    }

    if (tool === 'runs_read' && b.select === 'get') {
      if (faulty(res, behavior.runGet)) return;
      const r = findRun(b.runId);
      if (!r) return send(res, 404, errEnvelope('not_found'));
      return send(res, 200, okEnvelope(viewOf(r, !behavior.stale)));
    }

    if (tool === 'runs_read' && b.select === 'seats') {
      if (faulty(res, behavior.seats)) return;
      const r = findRun(b.runId);
      if (!r) return send(res, 404, errEnvelope('not_found'));
      const rows = b.ended === true ? (r.seats ?? []) : [];
      // api-rest.md section 20a: {runId, seats: [{assignmentId, itemId?, seat, attempt, status, assignedAt, endedAt?}]};
      // the coordinator pseudo-seat has no itemId.
      const out = rows
        .filter((x) => b.itemId === undefined || x.itemId === b.itemId)
        .map((x, n) => ({ assignmentId: `seat-${n}`, itemId: x.itemId, seat: x.seat ?? 'implementer', attempt: 1, status: 'ended', outcome: 'done', assignedAt: '2026-10-16T10:00:00Z', endedAt: '2026-10-16T10:30:00Z' }));
      if (b.itemId === undefined && b.ended === true) out.unshift({ assignmentId: 'seat-coordinator', seat: 'coordinator', attempt: 1, status: 'ended', assignedAt: '2026-10-16T10:00:00Z', endedAt: '2026-10-16T10:40:00Z' });
      return send(res, 200, okEnvelope({ runId: r.runId, seats: out }));
    }

    if (tool === 'runs_read' && b.select === 'frontier') {
      const r = runs.find((x) => x.runId === b.runId);
      if (!r) return send(res, 404, errEnvelope('not_found'));
      if (behavior.frontier[r.runId] === 'http500') return send(res, 500, errEnvelope('internal'));
      return send(
        res,
        200,
        okEnvelope({
          runId: r.runId,
          status: 'closed',
          stalled: false,
          counts: { queue: 0, work: 0, review: 0, blocked: 0, terminal: r.terminal },
          ready: [],
          inFlight: [],
          waiting: [],
        }),
      );
    }

    if (tool === 'items_read' && b.select === 'get') {
      if (faulty(res, behavior.itemsGet)) return;
      const i = findItem(b.itemId);
      if (!i) return send(res, 404, errEnvelope('not_found'));
      return send(res, 200, okEnvelope(itemView(i)));
    }

    if (tool === 'items_read' && b.select === 'list') {
      if (faulty(res, behavior.itemsList)) return;
      let rows = typeof b.ancestorId === 'string' ? subtreeOf(b.ancestorId) : items;
      if (typeof b.parentId === 'string') rows = rows.filter((i) => i.parent && sameItemId(i.parent, b.parentId));
      if (typeof b.type === 'string') rows = rows.filter((i) => i.type === b.type);
      if (typeof b.role === 'string') rows = rows.filter((i) => i.role === b.role);
      if (typeof b.createdAfter === 'string') rows = rows.filter((i) => i.createdAt && Date.parse(i.createdAt) >= Date.parse(b.createdAfter));
      const total = rows.length;
      if (Number.isInteger(b.limit)) rows = rows.slice(0, b.limit);
      const out = rows.map((i) => itemView(i, true));
      return send(res, 200, okEnvelope({ items: out, page: { total, truncated: out.length < total } }));
    }

    if (tool === 'notes_read' && b.select === 'get') {
      if (faulty(res, behavior.notes)) return;
      const i = findItem(b.itemId);
      const text = i ? notes[i.id]?.[b.key] : notes[b.itemId]?.[b.key];
      if (typeof text !== 'string') return send(res, 404, errEnvelope('not_found'));
      return send(res, 200, okEnvelope({ id: 'n-' + b.key, key: b.key, role: 'work', version: 1, body: text, modifiedAt: '2026-10-16T10:00:00Z' }));
    }

    if (tool === 'runs_write') {
      const ops = Array.isArray(b.ops) ? b.ops : [];
      if (ops.length === 0 || ops.some((o) => !o || o.op !== 'mark')) return send(res, 400, errEnvelope('invalid_request'));
      if (behavior.mark === 'hang') return;
      if (behavior.mark === 'http500') return send(res, 500, errEnvelope('internal'));
      if (behavior.mark === 'forbidden') return send(res, 403, errEnvelope('forbidden'));
      if (behavior.mark === 'badjson') return send(res, 200, 'not json {');
      if (behavior.mark === 'itemfail') {
        return send(res, 207, batchEnvelope([failedResult('forbidden')]));
      }
      const results = [];
      for (const op of ops) {
        const r = findRun(op.runId);
        if (!r) return send(res, 404, errEnvelope('not_found'));
        const cur = marked.get(r.runId) ?? {};
        const first = !staticMark(r, op.key) && !cur[op.key];
        if (first) {
          cur[op.key] = new Date().toISOString();
          marked.set(r.runId, cur);
        }
        markOutcomes.push({ runId: r.runId, key: op.key, marked: first });
        results.push({ ok: true, ...viewOf(r, true), marked: first });
      }
      return send(res, 200, batchEnvelope(results));
    }

    return send(res, 400, errEnvelope('invalid_request'));
  };

  const server = createServer((req, res) => {
    let raw = '';
    req.on('data', (d) => {
      raw += d;
    });
    req.on('end', () => {
      let body = null;
      try {
        body = raw ? JSON.parse(raw) : null;
      } catch {
        body = raw;
      }
      handle(req, res, body);
    });
  });
  server.on('connection', (s) => {
    sockets.add(s);
    s.on('close', () => sockets.delete(s));
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;

  const callsOf = (tool, select) =>
    requests.filter((q) => q.path === `${OPS_PREFIX}${tool}` && (select === undefined || q.body?.select === select));

  return {
    url: `http://127.0.0.1:${port}`,
    requests,
    markOutcomes,
    behavior,
    runs,
    items,
    notes,
    get lists() {
      return callsOf('runs_read', 'list');
    },
    get frontiers() {
      return callsOf('runs_read', 'frontier');
    },
    get marks() {
      return callsOf('runs_write');
    },
    calls: callsOf,
    isMarked: (runId, key = 'retro') => Boolean(marked.get(runId)?.[key]),
    /** true when the run carries the key, from the fixture or from a write. */
    hasMark: (runId, key) => {
      const r = runs.find((x) => x.runId === runId);
      return Boolean(r && isMarked(r, key));
    },
    /** The runIds a write answered marked:true for (optionally one key). */
    won: (key) => markOutcomes.filter((o) => o.marked && (key === undefined || o.key === key)).map((o) => o.runId),
    /** Server-side mark, as another session or retro-ack would write it. */
    setMark(runId, key, at = new Date().toISOString()) {
      const cur = marked.get(runId) ?? {};
      cur[key] = at;
      marked.set(runId, cur);
    },
    setItem(id, patch) {
      Object.assign(findItem(id), patch);
    },
    async close() {
      for (const s of sockets) s.destroy();
      await new Promise((r) => server.close(r));
    },
  };
}

/**
 * Sandbox + fake server + bound hook runners, cleaned up after fn. opts: { config, scope, runs,
 * behavior }. fx.trigger / fx.stop / fx.ack / fx.subagentStop(payload, extra) point the hook at the fake unless
 * extra.apiUrl is given (pass apiUrl: undefined for "no API URL").
 */
export async function withFixture(opts, fn) {
  const sbx = makeSandbox({ config: opts.config ?? null, scope: opts.scope });
  const fake = await startFakeRuns({ runs: opts.runs, items: opts.items, notes: opts.notes, behavior: opts.behavior });
  const bind = (hook) => (payload, extra = {}) => runHook(hook, payload, sbx, { apiUrl: fake.url, ...extra });
  const fx = {
    sbx,
    fake,
    trigger: bind(HOOKS.trigger),
    stop: bind(HOOKS.backstop),
    ack: bind(HOOKS.ack),
    subagentStop: bind(HOOKS.subagentStop),
  };
  try {
    return await fn(fx);
  } finally {
    await fake.close();
    sbx.cleanup();
  }
}

/** Writes the director-mode marker for `sessionId` inside the sandbox's TEMP (what director-guard writes). */
export function markDirector(sbx, sessionId) {
  const dir = join(sbx.tmp, 'task-orchestrator', String(sessionId).replace(/[^a-zA-Z0-9-]/g, '_'));
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'director.json'), '{}');
}

/** A run fixture with a closed run's usual shape: `terminal` terminal members out of `members`. */
export function runFixture(n, opts = {}) {
  const { terminal = 3, members = terminal } = opts;
  const r = { runId: rid(n), terminal, members };
  // marks defaults to {}; pass { marks: undefined } for a view that carries no marks field at all.
  if (!('marks' in opts)) r.marks = {};
  else if (opts.marks !== undefined) r.marks = opts.marks;
  return r;
}

// ---- per-session retro state and scope fixtures (W1: scope boundary) -------------------------------

export const MIN = 60 * 1000;
export const HOUR = 60 * MIN;
export const DAY = 24 * HOUR;
/** ISO instant `ms` before now. */
export const ago = (ms) => new Date(Date.now() - ms).toISOString();

const sessionDir = (sbx, sessionId) => join(sbx.tmp, 'task-orchestrator', String(sessionId).replace(/[^a-zA-Z0-9-]/g, '_'));
/** The session's retro state file: { closes, sawClose, nudged, backstopNudged, claims, lastDispatchAt } or null. */
export function readRetroState(sbx, sessionId) {
  try {
    return JSON.parse(readFileSync(join(sessionDir(sbx, sessionId), 'retro.json'), 'utf8'));
  } catch {
    return null;
  }
}
/** Merges patch into the session's retro state file (creating it with the empty shape). */
export function writeRetroState(sbx, sessionId, patch) {
  const dir = sessionDir(sbx, sessionId);
  mkdirSync(dir, { recursive: true });
  const base = readRetroState(sbx, sessionId) ?? { closes: [], sawClose: false, nudged: [], backstopNudged: false, claims: [] };
  writeFileSync(join(dir, 'retro.json'), JSON.stringify({ ...base, ...patch }));
}

/** Item ids for fixtures: stable, valid UUIDs whose first 8 characters differ per n (a list answers 8-hex ids). */
export const iid = (n) => `${String(n).padStart(8, '0')}-1010-4000-8000-${String(n).padStart(12, '0')}`;
/** The scope item every scope fixture hangs its group on. */
export const SCOPE = iid(900);

/** A session-tracking body the triage reads as clean (Outcome success, Friction and Deviations none). */
export const CLEAN_NOTE = ['Outcome: success, merged', 'Friction: None.', 'Deviations: none', ''].join(NL);

/**
 * One completed scope: a scope item (role scopeRole, default terminal, roleChangedAt scopeChangedMs ago), `runs`
 * closed runs of it (rid(runStart..), the first opened earliest, so the anchor), and the implementation items
 * under it: impl = [{ role, type, note }] where note is a body, or null for a missing note (default: three
 * terminal feature-tasks with clean notes). labels maps a run index to its label. Returns { runs, items,
 * notes, scopeId, runIds, implIds } to pass to withFixture; merge several with mergeFixtures.
 */
export function scopeFixture({
  scopeId = SCOPE,
  scopeRole = 'terminal',
  scopeChangedMs = 2 * HOUR,
  runs = 2,
  runStart = 1,
  closedMs = 2 * HOUR,
  openedMs = 5 * HOUR,
  itemStart = 1,
  impl = [{}, {}, {}],
  labels = {},
} = {}) {
  const items = [{ id: scopeId, type: 'feature', role: scopeRole, parent: ROOT, roleChangedAt: ago(scopeChangedMs), createdAt: ago(20 * DAY) }];
  const notes = {};
  const implIds = [];
  impl.forEach((spec, n) => {
    const id = iid(itemStart + n);
    implIds.push(id);
    items.push({ id, type: spec.type ?? 'feature-task', role: spec.role ?? 'terminal', parent: scopeId, createdAt: ago(10 * DAY), roleChangedAt: ago(3 * HOUR) });
    const text = 'note' in spec ? spec.note : CLEAN_NOTE;
    if (typeof text === 'string') notes[id] = { 'session-tracking': text };
  });
  const runRows = [];
  const runIds = [];
  for (let n = 0; n < runs; n++) {
    const runId = rid(runStart + n);
    runIds.push(runId);
    runRows.push({
      runId,
      scopeId,
      members: impl.length,
      terminal: impl.length,
      marks: {},
      openedAt: ago(openedMs - n * MIN),
      closedAt: ago(closedMs + n * 1000),
      ...(labels[n] ? { label: labels[n] } : {}),
    });
  }
  return { runs: runRows, items, notes, scopeId, runIds, implIds };
}

/** Concatenates fixtures from scopeFixture (runs, items, notes, runIds). */
export function mergeFixtures(...fixtures) {
  return {
    runs: fixtures.flatMap((f) => f.runs),
    items: fixtures.flatMap((f) => f.items),
    notes: Object.assign({}, ...fixtures.map((f) => f.notes)),
    runIds: fixtures.flatMap((f) => f.runIds ?? []),
  };
}

/** A JSONL transcript file inside the sandbox holding `text` (a subagent transcript); returns its path. */
export function writeTranscript(sbx, name, text) {
  const path = join(sbx.base, name);
  writeFileSync(path, JSON.stringify({ type: 'user', message: { role: 'user', content: text } }) + NL, 'utf-8');
  return path;
}
