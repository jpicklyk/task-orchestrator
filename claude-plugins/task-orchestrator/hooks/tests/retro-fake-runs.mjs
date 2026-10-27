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
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readdirSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, sep } from 'node:path';

export const HOOKS = {
  trigger: fileURLToPath(new URL('../retro-trigger.mjs', import.meta.url)),
  backstop: fileURLToPath(new URL('../retro-backstop.mjs', import.meta.url)),
  ack: fileURLToPath(new URL('../retro-ack.mjs', import.meta.url)),
};

export const HOOKS_CONFIG = fileURLToPath(new URL('../hooks-config.json', import.meta.url));

/** A full-UUID run id, stable per n. */
export const rid = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
/** The project root id every project-scope fixture configures (a valid plain-token id). */
export const ROOT = '5a5a5a5a-0000-4000-8000-0000000000aa';

const NL = String.fromCharCode(10);
const OPS_PREFIX = '/api/v1/ops/';

/**
 * config.yaml text. rootId null omits the project block; mode / threshold undefined omit the key.
 * The retrospective settings live under extensions.claude-code.retrospective (AC6).
 */
export function retroConfig({ rootId = ROOT, mode, threshold, extraRetro = [] } = {}) {
  const lines = [];
  if (rootId) lines.push('project:', `  rootId: "${rootId}"`, '');
  lines.push('extensions:', '  claude-code:', '    retrospective:');
  if (mode !== undefined) lines.push(`      mode: ${mode}`);
  if (threshold !== undefined) lines.push(`      dispatchThreshold: ${threshold}`);
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
export function runHook(hookPath, input, sbx, { apiUrl, env = {}, timeoutMs = 20000 } = {}) {
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
    const child = spawn(process.execPath, [hookPath], { env: e, cwd });
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
 * init.runs: [{ runId, rootId?, members?, terminal?, marks? }]. marks omitted -> the view carries no
 * marks field; marks null -> marks: null.
 * init.behavior: {
 *   list: 'ok' | 'http500' | 'badjson' | 'hang',
 *   frontier: { [runId]: 'http500' },
 *   mark: 'ok' | 'http500' | 'forbidden' | 'itemfail' | 'badjson' | 'hang',
 *   stale: true       -> list answers every run with only its static marks (a snapshot older than
 *                        another session's mark), whatever the server holds
 *   unfiltered: true  -> list ignores the `unmarked` filter and returns every run, with the server's
 *                        marks merged into each view
 * }
 * Default list: closed runs without a retro mark (static or server), as `unmarked: retro` asks.
 * mark is first-write-wins per (runId, key): a replay answers marked:false.
 */
export async function startFakeRuns(init = {}) {
  const runs = (init.runs ?? []).map((r) => ({ rootId: ROOT, members: r.terminal ?? 0, terminal: 0, ...r }));
  const behavior = { list: 'ok', frontier: {}, mark: 'ok', stale: false, unfiltered: false, ...init.behavior };
  const requests = [];
  const markOutcomes = [];
  const marked = new Map();
  const sockets = new Set();

  const viewOf = (r, withServer) => {
    const server = withServer ? marked.get(r.runId) ?? null : null;
    const v = {
      runId: r.runId,
      rootId: r.rootId,
      status: 'closed',
      terminalReason: 'completed',
      openedAt: '2026-10-16T10:00:00Z',
      closedAt: '2026-10-16T11:00:00Z',
      coordinatorStalled: false,
      stallAfterSeconds: 600,
      usage: { reportedTokens: 0, reportedCostUsd: 0, observedTokens: 0 },
      hints: {},
      members: r.members,
      activeSeats: 0,
      stalledSeats: 0,
      version: 2,
    };
    if ('marks' in r || server) {
      const base = r.marks && typeof r.marks === 'object' ? r.marks : {};
      v.marks = r.marks === null && !server ? null : { ...base, ...(server ?? {}) };
    }
    return v;
  };
  const isMarked = (r, key = 'retro') => Boolean((r.marks && r.marks[key]) || marked.get(r.runId)?.[key]);

  const send = (res, status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(typeof body === 'string' ? body : JSON.stringify(body));
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
      let withServer = true;
      if (behavior.stale) withServer = false;
      else if (!behavior.unfiltered) rows = runs.filter((r) => !isMarked(r));
      if (Number.isInteger(b.limit)) rows = rows.slice(0, b.limit);
      const items = rows.map((r) => viewOf(r, withServer));
      return send(res, 200, okEnvelope({ items, page: { total: items.length, truncated: false } }));
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

    if (tool === 'runs_write') {
      const op = Array.isArray(b.ops) ? b.ops[0] : null;
      if (!op || op.op !== 'mark') return send(res, 400, errEnvelope('invalid_request'));
      if (behavior.mark === 'hang') return;
      if (behavior.mark === 'http500') return send(res, 500, errEnvelope('internal'));
      if (behavior.mark === 'forbidden') return send(res, 403, errEnvelope('forbidden'));
      if (behavior.mark === 'badjson') return send(res, 200, 'not json {');
      if (behavior.mark === 'itemfail') {
        return send(res, 207, batchEnvelope([failedResult('forbidden')]));
      }
      const r = runs.find((x) => x.runId === op.runId);
      if (!r) return send(res, 404, errEnvelope('not_found'));
      const cur = marked.get(r.runId) ?? {};
      const first = !cur[op.key];
      if (first) {
        cur[op.key] = new Date().toISOString();
        marked.set(r.runId, cur);
      }
      markOutcomes.push({ runId: r.runId, key: op.key, marked: first });
      return send(res, 200, batchEnvelope([{ ok: true, ...viewOf(r, true), marked: first }]));
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
    get lists() {
      return callsOf('runs_read', 'list');
    },
    get frontiers() {
      return callsOf('runs_read', 'frontier');
    },
    get marks() {
      return callsOf('runs_write');
    },
    isMarked: (runId, key = 'retro') => Boolean(marked.get(runId)?.[key]),
    async close() {
      for (const s of sockets) s.destroy();
      await new Promise((r) => server.close(r));
    },
  };
}

/**
 * Sandbox + fake server + bound hook runners, cleaned up after fn. opts: { config, scope, runs,
 * behavior }. fx.trigger / fx.stop / fx.ack(payload, extra) point the hook at the fake unless
 * extra.apiUrl is given (pass apiUrl: undefined for "no API URL").
 */
export async function withFixture(opts, fn) {
  const sbx = makeSandbox({ config: opts.config ?? null, scope: opts.scope });
  const fake = await startFakeRuns({ runs: opts.runs, behavior: opts.behavior });
  const bind = (hook) => (payload, extra = {}) => runHook(hook, payload, sbx, { apiUrl: fake.url, ...extra });
  const fx = { sbx, fake, trigger: bind(HOOKS.trigger), stop: bind(HOOKS.backstop), ack: bind(HOOKS.ack) };
  try {
    return await fn(fx);
  } finally {
    await fake.close();
    sbx.cleanup();
  }
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
