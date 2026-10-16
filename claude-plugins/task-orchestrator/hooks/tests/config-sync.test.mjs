// config-sync.mjs on the 4.0 ops (eb262d39): config get/validate/push, docs_read list, docs_write upsert.
// Oracle for every wire shape: current/docs/api-reference.md (config, docs_read, docs_write, Response
// envelope, List data, error catalog) and the frozen task-scope AC1-AC10 / test-plan S1-S18 of item eb262d39;
// none of it is derived from the hook's output. The hook is spawned as a subprocess against an in-process
// stub that speaks POST /api/v1/ops/<tool> with API envelopes (a batch answer is data.results[i]; a failed
// element of a non-atomic call is HTTP 207, ok:false, partial_failure). Importing the module must NOT trigger
// a live sync (config-sync.mjs guards main() behind an entrypoint check).
//
// Hermeticity recipe: every spawn pins TASK_ORCHESTRATOR_HOME to its OWN empty temp dir, drops inherited
// AGENT_CONFIG_DIR / API URL / token, and runs with cwd = a fixture dir (never the repo).

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync, spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync, readdirSync, realpathSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import * as configSyncModule from '../config-sync.mjs';
import {
  parseRootId,
  isTargetConfigPath,
  normalizeForFingerprint,
  configFingerprint,
  isValidRuleKey,
  planRuleSync,
  planBundledSync,
  formatRuleSyncSummary,
} from '../config-sync.mjs';

const HOOK = fileURLToPath(new URL('../config-sync.mjs', import.meta.url));
const HOOKS_SRC = fileURLToPath(new URL('..', import.meta.url));
const PLUGIN_ROOT = fileURLToPath(new URL('../..', import.meta.url));
const BUNDLED_DIR = fileURLToPath(new URL('../../bundled-rules/', import.meta.url));

// Shared with the Kotlin side (ConfigFingerprintTest) so both implementations are proven
// byte-for-byte identical against the same vectors - see current/src/test/resources/fixtures/.
const FIXTURE_URL = new URL(
  '../../../../current/src/test/resources/fixtures/config-fingerprint-vectors.json',
  import.meta.url,
);
const fingerprintVectors = JSON.parse(readFileSync(FIXTURE_URL, 'utf-8'));

// ---- generic helpers -------------------------------------------------------------------------------

const temps = [];
after(() => {
  for (const d of temps) rmSync(d, { recursive: true, force: true });
});
function mkTemp(prefix = 'to-config-sync-') {
  const d = mkdtempSync(join(tmpdir(), prefix));
  temps.push(d);
  return d;
}
const tmpConfigDir = () => mkTemp('to-config-sync-');

function sha256Hex(buf) {
  return createHash('sha256').update(buf).digest('hex');
}

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content);
}

function writeRuleFile(dir, filename, content) {
  const rulesDir = join(dir, '.taskorchestrator', 'rules');
  mkdirSync(rulesDir, { recursive: true });
  writeFileSync(join(rulesDir, filename), content);
}

// Independent oracle for the normalization every byte on the wire must use: ONE leading BOM stripped,
// CRLF -> LF, a lone CR kept (config-fingerprint-vectors.json S1-S8).
function expectedNormalized(text) {
  const noBom = text.startsWith('\ufeff') ? text.slice(1) : text;
  return noBom.split('\r\n').join('\n');
}
const fpOf = (text) => sha256Hex(Buffer.from(expectedNormalized(text), 'utf8'));

// Status line of an emitted hook output: SessionStart carries it in hookSpecificOutput.additionalContext,
// FileChanged (which has no documented additionalContext channel) in a top-level systemMessage.
function statusLine(out) {
  return out.systemMessage ?? out.hookSpecificOutput?.additionalContext;
}
const lineOf = (res) => statusLine(JSON.parse(res.stdout));

// Invariant for every emitted stdout JSON: hookSpecificOutput is absent or names the firing event.
function assertEventContract(res, hookEventName) {
  const out = JSON.parse(res.stdout);
  if (out.hookSpecificOutput !== undefined) {
    assert.equal(out.hookSpecificOutput.hookEventName, hookEventName, res.stdout);
  }
  assert.equal(typeof statusLine(out), 'string', res.stdout);
  if (hookEventName === 'FileChanged') {
    assert.equal(out.hookSpecificOutput, undefined, res.stdout);
  }
  return out;
}

// A local, unroutable-in-practice URL so the call fails fast (connection refused).
const UNREACHABLE_API_URL = 'http://127.0.0.1:1';

function hermeticEnv(extra) {
  const home = mkTemp('to-config-sync-home-');
  const env = { ...process.env };
  for (const k of ['AGENT_CONFIG_DIR', 'TASK_ORCHESTRATOR_API_URL', 'TASK_ORCHESTRATOR_API_TOKEN', 'TASK_ORCHESTRATOR_CEILING']) {
    delete env[k];
  }
  return { env: { ...env, TASK_ORCHESTRATOR_HOME: home, ...extra }, home };
}

function spawnHook(dir, { stdin, hookEventName, filePath } = {}) {
  const input = stdin !== undefined
    ? stdin
    : JSON.stringify(hookEventName ? { hook_event_name: hookEventName, file_path: filePath } : {});
  const { env } = hermeticEnv({ AGENT_CONFIG_DIR: dir, TASK_ORCHESTRATOR_API_URL: UNREACHABLE_API_URL });
  return spawnSync(process.execPath, [HOOK], { input, env, encoding: 'utf-8', cwd: dir });
}

// MUST use async spawn (not spawnSync): the stub runs in THIS process, so a blocked event loop could never
// answer the child and every test would false-green on "unreachable" without the stub being hit.
function runScript(script, { cwd, env, input, killMs = 15000 }) {
  return new Promise((resolvePromise, rejectPromise) => {
    const started = Date.now();
    const child = spawn(process.execPath, [script], { env, cwd });
    let killed = false;
    const killTimer = setTimeout(() => {
      killed = true;
      child.kill('SIGKILL');
    }, killMs);
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    child.on('error', (err) => { clearTimeout(killTimer); rejectPromise(err); });
    child.on('close', (status) => {
      clearTimeout(killTimer);
      resolvePromise({ status, stdout, stderr, killed, wall: Date.now() - started, started });
    });
    child.stdin.end(input);
  });
}

function eventInput(hookEventName, filePath) {
  return JSON.stringify(hookEventName === 'FileChanged' ? { hook_event_name: hookEventName, file_path: filePath } : { hook_event_name: hookEventName });
}

/** Project scope: config (and rules/) live in `dir`, found through AGENT_CONFIG_DIR and cwd. */
function runHook(dir, apiUrl, { event = 'SessionStart', token, script = HOOK, killMs } = {}) {
  const extra = { AGENT_CONFIG_DIR: dir, TASK_ORCHESTRATOR_API_URL: apiUrl };
  if (token) extra.TASK_ORCHESTRATOR_API_TOKEN = token;
  const { env } = hermeticEnv(extra);
  const filePath = join(dir, '.taskorchestrator', 'config.yaml');
  return runScript(script, { cwd: dir, env, input: eventInput(event, filePath), killMs });
}

/** User scope: config lives under the pinned home; cwd is an empty fixture dir. */
function runUserScope(home, cwd, apiUrl, { event = 'SessionStart' } = {}) {
  const env = { ...process.env };
  for (const k of ['AGENT_CONFIG_DIR', 'TASK_ORCHESTRATOR_API_TOKEN', 'TASK_ORCHESTRATOR_CEILING']) delete env[k];
  env.TASK_ORCHESTRATOR_HOME = home;
  env.TASK_ORCHESTRATOR_API_URL = apiUrl;
  const filePath = join(home, '.taskorchestrator', 'config.yaml');
  return runScript(HOOK, { cwd, env, input: eventInput(event, filePath) });
}

const projectConfigText = (rootId, extra = '') => `project:\n  rootId: "${rootId}"\n${extra}`;

/** A project fixture dir with a config.yaml; returns { dir, text, normalized, fp }. */
function makeProject(rootId, { extra = '', text } = {}) {
  const dir = tmpConfigDir();
  const body = text ?? projectConfigText(rootId, extra);
  writeConfig(dir, body);
  return { dir, text: body, normalized: expectedNormalized(body), fp: fpOf(body) };
}

// ---- the bundled rules, listed at their current server hash --------------------------------------------

const BUNDLED_KEYS = [
  'protocol.entry-seat',
  'protocol.in-phase-seat',
  'protocol.read-only-agent',
  'commit-discipline',
  'review-scoping',
];
const PROTOCOL_BUNDLED_KEYS = BUNDLED_KEYS.filter((k) => k.startsWith('protocol.'));
function bundledNormalizedBytes(key) {
  return normalizeForFingerprint(readFileSync(join(BUNDLED_DIR, `${key}.md`)));
}
function bundledAtCurrentHash() {
  const manifest = JSON.parse(readFileSync(join(BUNDLED_DIR, 'manifest.json'), 'utf-8'));
  return Object.keys(manifest).map((key) => ({ key, hash: sha256Hex(bundledNormalizedBytes(key)) }));
}

// ---- API envelope builders (api-reference.md "Response envelope") ----------------------------------------

const META = { reqId: 'k7f3q9ab' };
const okEl = (fields = {}) => ({ ok: true, ...fields });
const errEl = (code, detail, message = code) => ({
  ok: false,
  error: { code, kind: 'permanent', message, ...(detail === undefined ? {} : { detail }) },
});
function batch(elements) {
  const failed = elements.filter((e) => !e.ok).length;
  const data = { results: elements, summary: { ok: elements.length - failed, failed } };
  if (failed === 0) return { status: 200, json: { ok: true, data, meta: META } };
  return {
    status: 207,
    json: { ok: false, error: { code: 'partial_failure', kind: 'permanent', message: `${failed} of ${elements.length} failed` }, data, meta: META },
  };
}
const topError = (status, code, message = code, detail) => ({
  status,
  json: { ok: false, error: { code, kind: 'permanent', message, ...(detail === undefined ? {} : { detail }) }, meta: META },
});
const listAnswer = (items, page = {}) => ({
  status: 200,
  json: { ok: true, data: { items, page: { total: items.length, truncated: false, ...page } }, meta: META },
});

// ---- the stateful stub API ---------------------------------------------------------------------------------
//
// opts.config   { version, fingerprint, createdAt, history?: [fingerprints once held] } | undefined (no config yet)
// opts.docs     { slug: { hash, version } } rule documents the server holds (bundled rules at their current
//               hash are seeded first unless opts.seedBundled === false)
// opts.pageSize page size of the docs_read list (default: everything on one page)
// opts.override (call, state) => reply | undefined; a reply is { status, json | raw, delayMs, stall }
// Writes follow the documented semantics: upsert expectedVersion omitted = unconditional, 0 = must not exist,
// n = current version must be n (else version_conflict); a config push compares expectedVersion with the head.

function startStub(opts = {}) {
  const calls = [];
  const state = {
    config: opts.config ? { history: [], createdAt: '2026-01-01T00:00:00Z', ...opts.config } : null,
    docs: new Map(),
  };
  if (opts.seedBundled !== false) {
    for (const { key, hash } of bundledAtCurrentHash()) state.docs.set(key, { hash, version: 1 });
  }
  for (const [slug, v] of Object.entries(opts.docs ?? {})) state.docs.set(slug, { version: 1, ...v });
  let inFlight = 0;
  let maxInFlight = 0;

  function route(call) {
    const { tool, body } = call;
    if (tool === 'config') {
      if (!Array.isArray(body.ops) || body.ops.length !== 1) return topError(400, 'invalid_request', 'expected exactly one op');
      const el = body.ops[0];
      if (el.op === 'get') {
        if (!state.config) return batch([errEl('not_found', { kind: 'config', id: el.rootId })]);
        const result = {
          rootId: el.rootId,
          version: state.config.version,
          fingerprint: state.config.fingerprint,
          source: 'push',
          createdAt: state.config.createdAt,
          configYaml: 'project: {}\n',
        };
        if (el.fingerprint !== undefined) {
          result.relation = el.fingerprint === state.config.fingerprint
            ? 'current'
            : state.config.history.includes(el.fingerprint) ? 'superseded' : 'unknown';
        }
        return batch([okEl(result)]);
      }
      if (el.op === 'validate') {
        return batch([okEl({ rootId: el.rootId, valid: true, errors: [], warnings: [], diff: { types: { added: [], removed: [], changed: [] }, traits: { added: [], removed: [], changed: [] }, settings: { added: [], removed: [], changed: [] } }, affectedInFlight: { count: 0, sample: [] } })]);
      }
      if (el.op === 'push') {
        if (state.config && typeof el.expectedVersion === 'number' && el.expectedVersion !== state.config.version) {
          return batch([errEl('version_conflict', { kind: 'config', id: el.rootId, expected: el.expectedVersion, actual: state.config.version })]);
        }
        const fp = sha256Hex(Buffer.from(el.configYaml, 'utf8'));
        const appended = !state.config || state.config.fingerprint !== fp;
        if (appended) {
          const history = state.config ? [...state.config.history, state.config.fingerprint] : [];
          state.config = { version: (state.config?.version ?? 0) + 1, fingerprint: fp, createdAt: '2026-02-02T00:00:00Z', history };
        }
        return batch([okEl({ rootId: el.rootId, version: state.config.version, fingerprint: fp, appended, createdAt: state.config.createdAt })]);
      }
      return topError(400, 'invalid_request', `unexpected config op ${el.op}`);
    }
    if (tool === 'docs_read') {
      if (body.select !== 'list') return topError(400, 'invalid_request', 'only list is expected');
      const slugs = [...state.docs.keys()].sort();
      const pageSize = opts.pageSize ?? 100000;
      let offset = 0;
      if (body.cursor !== undefined) {
        offset = Number(body.cursor);
        if (!Number.isInteger(offset) || offset < 0) return topError(400, 'invalid_cursor', 'bad cursor');
      }
      const slice = slugs.slice(offset, offset + pageSize);
      const items = slice.map((slug) => ({
        id: `00000000-0000-4000-8000-${String(slugs.indexOf(slug)).padStart(12, '0')}`,
        kind: 'rule',
        slug,
        contentHash: state.docs.get(slug).hash,
        version: state.docs.get(slug).version,
        createdAt: '2026-01-01T00:00:00Z',
        modifiedAt: '2026-01-01T00:00:00Z',
      }));
      const truncated = offset + pageSize < slugs.length;
      return listAnswer(items, { total: slugs.length, truncated, ...(truncated ? { next: String(offset + pageSize) } : {}) });
    }
    if (tool === 'docs_write') {
      if (!Array.isArray(body.ops) || body.ops.length !== 1) return topError(400, 'invalid_request', 'expected exactly one op');
      const el = body.ops[0];
      if (el.op !== 'upsert') return topError(400, 'invalid_request', `unexpected docs_write op ${el.op}`);
      const cur = state.docs.get(el.slug);
      if (el.expectedVersion !== undefined) {
        const have = cur ? cur.version : 0;
        if (el.expectedVersion !== have) {
          return batch([errEl('version_conflict', { kind: 'document', id: `rule/${el.slug}`, expected: el.expectedVersion, actual: have })]);
        }
      }
      const hash = sha256Hex(Buffer.from(String(el.body).split('\r\n').join('\n'), 'utf8'));
      const version = (cur?.version ?? 0) + 1;
      state.docs.set(el.slug, { hash, version });
      return batch([okEl({ id: '11111111-1111-4111-8111-111111111111', kind: el.kind, slug: el.slug, version, contentHash: hash, created: !cur })]);
    }
    return topError(404, 'not_found', 'no such tool');
  }

  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const raw = Buffer.concat(chunks);
      const m = /^\/api\/v1\/ops\/([a-z_]+)$/.exec(req.url.split('?')[0]);
      let body = null;
      try { body = JSON.parse(raw.toString('utf8')); } catch { /* leaves null */ }
      const call = {
        method: req.method, url: req.url, tool: m ? m[1] : null, body, raw, auth: req.headers.authorization,
        contentType: req.headers['content-type'], headers: req.headers, at: Date.now(),
      };
      calls.push(call);
      const isWrite = call.tool === 'docs_write';
      if (isWrite) { inFlight += 1; maxInFlight = Math.max(maxInFlight, inFlight); }
      res.on('close', () => { if (isWrite) inFlight -= 1; });
      let reply;
      if (!m || req.method !== 'POST' || body === null) reply = topError(404, 'not_found', 'no route');
      else reply = (opts.override && opts.override(call, state)) || route(call);
      setTimeout(() => {
        if (reply.stall) {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.write('{"ok":true,"data":{"items":[');
          return;
        }
        res.writeHead(reply.status ?? 200, { 'Content-Type': 'application/json' });
        res.end(reply.raw !== undefined ? reply.raw : JSON.stringify(reply.json));
      }, reply.delayMs || 0);
    });
  });
  return new Promise((resolveListen) => {
    server.listen(0, '127.0.0.1', () => {
      resolveListen({
        url: `http://127.0.0.1:${server.address().port}`,
        calls,
        state,
        tools: () => calls.map((c) => c.tool),
        ofTool: (t) => calls.filter((c) => c.tool === t),
        configOps: () => calls.filter((c) => c.tool === 'config').map((c) => c.body.ops[0]),
        upserts: () => calls.filter((c) => c.tool === 'docs_write').map((c) => c.body.ops[0]),
        maxInFlight: () => maxInFlight,
        close: () => new Promise((r) => { if (typeof server.closeAllConnections === 'function') server.closeAllConnections(); server.close(r); }),
      });
    });
  });
}

async function withStub(opts, fn) {
  const stub = await startStub(opts);
  try {
    return await fn(stub);
  } finally {
    await stub.close();
  }
}

// ---- retained pure-function and gating coverage (unchanged contracts) ------------------------------------------

test('isValidRuleKey: accepts the server grammar, rejects spaces/uppercase/leading dot', () => {
  assert.ok(isValidRuleKey('commit-discipline'));
  assert.ok(isValidRuleKey('a'));
  assert.ok(isValidRuleKey('a.b_c-9'));
  assert.ok(!isValidRuleKey('Bad Key'));
  assert.ok(!isValidRuleKey('UPPER'));
  assert.ok(!isValidRuleKey('.leading-dot'));
  assert.ok(!isValidRuleKey(''));
});

test('planRuleSync: classifies invalid key, oversized body, unchanged, and changed/new rules', () => {
  const unchangedBytes = Buffer.from('same');
  const changedBytes = Buffer.from('new-content');
  const localRules = [
    { key: 'Bad Key', bytes: Buffer.from('x') },
    { key: 'toobig', bytes: Buffer.alloc(16385, 'x') },
    { key: 'unchanged', bytes: unchangedBytes },
    { key: 'changed', bytes: changedBytes },
  ];
  const serverVersions = new Map([
    ['unchanged', sha256Hex(unchangedBytes)],
    ['changed', 'a-stale-hash'],
  ]);
  const plan = planRuleSync(localRules, serverVersions);
  assert.equal(plan.unchangedCount, 1);
  assert.deepEqual(
    plan.skipped.map((s) => s.key),
    ['Bad Key', 'toobig'],
  );
  assert.deepEqual(
    plan.toPush.map((p) => p.key),
    ['changed'],
  );
});

test('formatRuleSyncSummary: composes pushed/in-sync/skipped/failed clauses', () => {
  assert.equal(
    formatRuleSyncSummary({ pushedKeys: [], unchangedCount: 9, skipped: [], failed: [] }),
    '9 in sync.',
  );
  assert.equal(
    formatRuleSyncSummary({
      pushedKeys: ['commit-discipline', 'test-author'],
      unchangedCount: 7,
      skipped: [],
      failed: [],
    }),
    'pushed 2 (commit-discipline, test-author); 7 in sync.',
  );
  assert.equal(
    formatRuleSyncSummary({
      pushedKeys: [],
      unchangedCount: 3,
      skipped: [{ key: 'bad', reason: 'invalid key' }],
      failed: [{ key: 'conflict', reason: 'HTTP 409' }],
    }),
    '3 in sync.; skipped: bad (invalid key); failed: conflict (HTTP 409)',
  );
});

// ─── Rule-sync tests (T1–T7) ────────────────────────────────────────────────
//
// A local `node:http` fake API (same technique as api-client.test.mjs), spawned per test on an

test('parseRootId: resolves rootId from a project: block', () => {
  const content = 'project:\n  rootId: "abc-123"\n  name: "X"\n';
  assert.equal(parseRootId(content), 'abc-123');
});

test('parseRootId: tolerates a column-0 comment above rootId', () => {
  const content = [
    'project:',
    '# a stray column-0 comment sitting right above rootId',
    '  rootId: "root-comment-check"',
  ].join('\n');
  assert.equal(parseRootId(content), 'root-comment-check');
});

test('parseRootId: null when project: block is absent', () => {
  assert.equal(parseRootId('retrospective:\n  mode: nudge\n'), null);
  assert.equal(parseRootId(null), null);
});

test('parseRootId: block-only — ignores an inline project: { ... } form', () => {
  assert.equal(parseRootId('project: { rootId: abc }\n'), null);
});

test('isTargetConfigPath: matches forward-slash and backslash forms of the real config path', () => {
  assert.ok(isTargetConfigPath('/project/.taskorchestrator/config.yaml'));
  assert.ok(isTargetConfigPath('C:\\project\\.taskorchestrator\\config.yaml'));
});

test('isTargetConfigPath: rejects unrelated paths, including a differently-located config.yaml', () => {
  assert.ok(!isTargetConfigPath('foo/config.json'));
  assert.ok(!isTargetConfigPath('some/other/config.yaml')); // config.yaml, but not under .taskorchestrator
  assert.ok(!isTargetConfigPath(undefined));
  assert.ok(!isTargetConfigPath(null));
  assert.ok(!isTargetConfigPath(''));
});

test('FileChanged with an unrelated file_path exits silently without attempting a sync', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-unrelated"\n');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath: 'foo/config.json' });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, ''); // guard returned before any emit() — no network attempt happened
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('FileChanged with a config.yaml that is not under .taskorchestrator/ also exits silently', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-wrong-location"\n');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath: 'some/other/config.yaml' });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('FileChanged for the real config.yaml (forward-slash path) proceeds through sync logic', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-match-fwd"\n');
    const filePath = join(dir, '.taskorchestrator', 'config.yaml').replace(/\\/g, '/');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath });
    assert.equal(res.status, 0);
    const out = assertEventContract(res, 'FileChanged');
    assert.ok(statusLine(out).includes('config sync skipped'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('FileChanged for the real config.yaml (backslash path) proceeds through sync logic', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-match-back"\n');
    const filePath = join(dir, '.taskorchestrator', 'config.yaml').replace(/\//g, '\\');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath });
    assert.equal(res.status, 0);
    const out = assertEventContract(res, 'FileChanged');
    assert.ok(statusLine(out).includes('config sync skipped'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('SessionStart invocations proceed through sync logic unchanged', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-sessionstart-unchanged"\n');
    const res = spawnHook(dir, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const out = assertEventContract(res, 'SessionStart');
    assert.equal(out.hookSpecificOutput.hookEventName, 'SessionStart');
    assert.ok(out.hookSpecificOutput.additionalContext.includes('config sync skipped'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: missing/garbage stdin still behaves like today (proceeds through sync logic)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-garbage-stdin"\n');
    const res = spawnHook(dir, { stdin: '{not valid json' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('config sync skipped'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: empty stdin still behaves like today (proceeds through sync logic)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-empty-stdin"\n');
    const res = spawnHook(dir, { stdin: '' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('config sync skipped'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('configFingerprint: matches the shared BOM/CRLF normalization vectors', () => {
  for (const vector of fingerprintVectors) {
    const buf = Buffer.from(vector.input, 'utf8');
    assert.equal(
      configFingerprint(buf),
      vector.expectedSha256,
      `vector "${vector.name}" mismatched`,
    );
  }
});

test('normalizeForFingerprint: does not mutate its input Buffer', () => {
  const original = Buffer.from('\ufeffa: 1\r\nb: 2\r\n', 'utf8');
  const copy = Buffer.from(original);
  normalizeForFingerprint(original);
  assert.deepEqual(original, copy);
});

// \u2500\u2500\u2500 S3 (D2, plans/fix-config-sync-busy.md) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
//
// Blind regression coverage: syncRules must send its rule PUTs one at a time (each awaited
// before the next starts), not fanned out via Promise.all \u2014 a concurrent fan-out is exactly what
// trips the server's SQLITE_BUSY_SNAPSHOT (D1). Oracle: the brief's D2 text, not the hook's
// current Promise.all implementation. Red-proof: today's Promise.all fan-out gives max in-flight
// 3 for three rules; the fix must bring it down to 1 while still completing all three PUTs.

test('S4: planBundledSync classifies absent / known-old / current by the manifest', () => {
  const curA = Buffer.from('current a\n');
  const curB = Buffer.from('current b\n');
  const curC = Buffer.from('current c\n');
  const oldHash = sha256Hex(Buffer.from('an older shipped body\n'));
  const manifest = {
    ka: [oldHash, sha256Hex(curA)],
    kb: [oldHash, sha256Hex(curB)],
    kc: [oldHash, sha256Hex(curC)],
  };
  const plan = planBundledSync({
    bundled: [
      { key: 'ka', bytes: curA },
      { key: 'kb', bytes: curB },
      { key: 'kc', bytes: curC },
    ],
    manifest,
    serverVersions: new Map([
      ['ka', oldHash], // known older shipped hash -> overwrite
      ['kc', sha256Hex(curC)], // already current
    ]), // kb absent
    workspaceKeys: new Set(),
  });
  const byKey = Object.fromEntries(plan.toPush.map((p) => [p.key, p]));
  assert.deepEqual(Object.keys(byKey).sort(), ['ka', 'kb']);
  assert.equal(byKey.ka.reason, 'known-old');
  assert.equal(byKey.kb.reason, 'absent');
  assert.deepEqual(byKey.ka.bytes, curA);
  assert.deepEqual(byKey.kb.bytes, curB);
  assert.deepEqual(plan.inSync, ['kc']);
  assert.deepEqual(plan.untouched, []);
});

test('S7: planBundledSync never pushes over an unknown server hash (null, empty, unknown hex, uppercase hex)', () => {
  const cur = Buffer.from('current\n');
  const curHash = sha256Hex(cur);
  const oldHash = sha256Hex(Buffer.from('older\n'));
  const manifest = { k: [oldHash, curHash] };
  const unknowns = [null, '', 'f'.repeat(64), curHash.toUpperCase(), oldHash.toUpperCase()];
  for (const serverHash of unknowns) {
    const plan = planBundledSync({
      bundled: [{ key: 'k', bytes: cur }],
      manifest,
      serverVersions: new Map([['k', serverHash]]),
      workspaceKeys: new Set(),
    });
    assert.deepEqual(plan.toPush, [], `serverHash ${JSON.stringify(serverHash)} must not be pushed over`);
    assert.deepEqual(plan.inSync, [], `serverHash ${JSON.stringify(serverHash)} is not in sync`);
    assert.deepEqual(plan.untouched, ['k'], `serverHash ${JSON.stringify(serverHash)}`);
  }
});

test('planBundledSync: a key the workspace provides is omitted from every list, whatever the server holds', () => {
  const cur = Buffer.from('current\n');
  const curHash = sha256Hex(cur);
  const oldHash = sha256Hex(Buffer.from('older\n'));
  const manifest = { k: [oldHash, curHash] };
  for (const serverVersions of [new Map(), new Map([['k', oldHash]]), new Map([['k', curHash]]), new Map([['k', 'zz']])]) {
    const plan = planBundledSync({
      bundled: [{ key: 'k', bytes: cur }],
      manifest,
      serverVersions,
      workspaceKeys: new Set(['k']),
    });
    assert.deepEqual(plan, { toPush: [], inSync: [], untouched: [] });
  }
});

test('formatRuleSyncSummary: deferredCount 0 changes nothing; a positive count names "N deferred to next session"', () => {
  const base = { pushedKeys: [], unchangedCount: 4, skipped: [], failed: [] };
  assert.equal(formatRuleSyncSummary({ ...base, deferredCount: 0 }), formatRuleSyncSummary(base));
  assert.ok(formatRuleSyncSummary({ ...base, deferredCount: 3 }).includes('3 deferred to next session'));
});

// ---- outcomeOf / formatRejection / makeDeadline units ---------------------------------------------------------

test('outcomeOf: null stays null; a legacy answer is code legacy', () => {
  assert.equal(configSyncModule.outcomeOf(null), null);
  const o = configSyncModule.outcomeOf({ ok: false, legacy: true, data: null, error: null, results: [], summary: null, reqId: null, httpStatus: 404 });
  assert.equal(o.ok, false);
  assert.equal(o.code, 'legacy');
});

test('outcomeOf: results[0] wins over the top-level partial_failure; with no results the top-level error is the outcome', () => {
  const failedEl = errEl('not_found', { kind: 'config', id: 'r' });
  const partial = configSyncModule.outcomeOf({
    ok: false, legacy: false, data: { results: [failedEl] }, error: { code: 'partial_failure', kind: 'permanent', message: 'x' },
    results: [failedEl], summary: { ok: 0, failed: 1 }, reqId: 'k7f3q9ab', httpStatus: 207,
  });
  assert.equal(partial.ok, false);
  assert.equal(partial.code, 'not_found');
  const top = configSyncModule.outcomeOf({
    ok: false, legacy: false, data: null, error: { code: 'unauthenticated', kind: 'permanent', message: 'no' },
    results: [], summary: null, reqId: null, httpStatus: 401,
  });
  assert.equal(top.ok, false);
  assert.equal(top.code, 'unauthenticated');
  const good = configSyncModule.outcomeOf({
    ok: true, legacy: false, data: { results: [okEl({ version: 3 })] }, error: null,
    results: [okEl({ version: 3 })], summary: { ok: 1, failed: 0 }, reqId: 'k7f3q9ab', httpStatus: 200,
  });
  assert.equal(good.ok, true);
  assert.equal(good.data.version, 3);
});

test('formatRejection (AC4, Amendment A1): four entries show the first three joined by "; " then " (+1 more)"', () => {
  const entries = ['/a: first reason', '/b: second reason', '(document): third reason', '/d: fourth reason'];
  const line = configSyncModule.formatRejection('root-fr4', entries);
  assert.equal(typeof line, 'string');
  assert.ok(line.includes('config sync rejected for root root-fr4'), line);
  assert.ok(line.includes('/a: first reason; /b: second reason; (document): third reason'), line);
  assert.ok(line.includes('(+1 more)'), line);
  assert.ok(!line.includes('fourth reason'), line);
});

test('formatRejection (AC4, Amendment A1): two entries are both shown and there is no "(+" suffix', () => {
  const line = configSyncModule.formatRejection('root-fr2', ['/a: first reason', '/b: second reason']);
  assert.ok(line.includes('config sync rejected for root root-fr2'), line);
  assert.ok(line.includes('/a: first reason; /b: second reason'), line);
  assert.ok(!line.includes('(+'), line);
});

const BUDGET_MS = 8000;
const timeoutResult = () => ({
  ok: false, legacy: false, data: null,
  error: { code: 'client_error', kind: 'transient', message: 'client_error: timeout', retryAfterMs: null, detail: { reason: 'timeout' }, fix: null, index: null },
  results: [], summary: null, reqId: null, httpStatus: null,
});
const okResult = () => ({ ok: true, legacy: false, data: { results: [okEl({ x: 1 })] }, error: null, results: [okEl({ x: 1 })], summary: { ok: 1, failed: 0 }, reqId: 'k7f3q9ab', httpStatus: 200 });

function stubbedDeadline({ nowRef, callImpl }) {
  const start = 1_000_000;
  nowRef.value = start;
  return { start, deadline: configSyncModule.makeDeadline(BUDGET_MS, start, { now: () => nowRef.value, callImpl }) };
}

test('75f0e354 S12a / AC6: remaining() <= 0 at send - callImpl is never called and the answer is a deadline result', async () => {
  const nowRef = { value: 0 };
  let calls = 0;
  const { start, deadline } = stubbedDeadline({ nowRef, callImpl: async () => { calls += 1; return okResult(); } });
  nowRef.value = start + BUDGET_MS;
  assert.equal(deadline.remaining(), 0);
  const res = await deadline.call('config', { ops: [] });
  assert.equal(calls, 0, 'nothing is sent once the budget is spent');
  assert.equal(res.ok, false);
  assert.equal(res.error.code, 'client_error');
  assert.equal(res.error.detail.reason, 'deadline');
  assert.equal(deadline.isDeadlineResult(res), true);
});

test('75f0e354 S12b / AC6: a capped call (500ms left) gets timeoutMs 500 and its timeout counts as a deadline result', async () => {
  const nowRef = { value: 0 };
  const seen = [];
  const { start, deadline } = stubbedDeadline({
    nowRef,
    callImpl: async (tool, body, opts) => { seen.push({ tool, body, timeoutMs: opts?.timeoutMs }); return timeoutResult(); },
  });
  nowRef.value = start + 7500;
  const res = await deadline.call('docs_write', { ops: [{ op: 'upsert' }] });
  assert.deepEqual(seen, [{ tool: 'docs_write', body: { ops: [{ op: 'upsert' }] }, timeoutMs: 500 }]);
  nowRef.value = start + 7999; // classification was fixed at send time, not here
  assert.equal(deadline.remaining(), 1);
  assert.equal(deadline.isDeadlineResult(res), true);
});

test('75f0e354 S12c / AC6: an uncapped call (full budget left) gets the 2000ms cap and its timeout is NOT a deadline result', async () => {
  const nowRef = { value: 0 };
  const seen = [];
  const { deadline } = stubbedDeadline({
    nowRef,
    callImpl: async (tool, body, opts) => { seen.push(opts?.timeoutMs); return timeoutResult(); },
  });
  const res = await deadline.call('config', { ops: [] });
  assert.deepEqual(seen, [2000]);
  assert.equal(deadline.isDeadlineResult(res), false);
});

test('75f0e354 S12d / AC6: a capped call that fails for another reason, or succeeds, is not a deadline result', async () => {
  const nowRef = { value: 0 };
  const mode = { result: null };
  const { start, deadline } = stubbedDeadline({ nowRef, callImpl: async () => mode.result });
  nowRef.value = start + 7500; // capped
  mode.result = okResult();
  assert.equal(deadline.isDeadlineResult(await deadline.call('config', {})), false, 'a capped success');
  mode.result = { ...timeoutResult(), error: { ...timeoutResult().error, code: 'forbidden', detail: { scope: 'capability', required: 'write-docs' } } };
  assert.equal(deadline.isDeadlineResult(await deadline.call('config', {})), false, 'a capped forbidden');
});

test('75f0e354 S12 probe: remaining 1 still sends (timeoutMs 1); remaining 0 does not; an ok answer passes through', async () => {
  const nowRef = { value: 0 };
  const seen = [];
  const { start, deadline } = stubbedDeadline({
    nowRef,
    callImpl: async (tool, body, opts) => { seen.push(opts?.timeoutMs); return okResult(); },
  });
  nowRef.value = start + 7999;
  const res = await deadline.call('config', {});
  assert.deepEqual(seen, [1]);
  assert.equal(res.ok, true);
  assert.equal(res.data.results[0].x, 1);
  nowRef.value = start + 8000;
  await deadline.call('config', {});
  assert.deepEqual(seen, [1], 'no second send at remaining 0');
});

// ---- S1-S6: the config phase -------------------------------------------------------------------------------------

test('S1 (AC1): relation current - only config get and the rules listing; "already in sync"; no validate, push or upsert', async () => {
  const { dir, fp } = makeProject('root-s1');
  await withStub({ config: { version: 4, fingerprint: fp } }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const out = assertEventContract(res, 'SessionStart');
    const line = out.hookSpecificOutput.additionalContext;
    assert.ok(line.includes('already in sync'), line);
    assert.ok(line.includes('root-s1'), line);
    assert.deepEqual(stub.tools(), ['config', 'docs_read']);
    assert.deepEqual(stub.configOps(), [{ op: 'get', rootId: 'root-s1', fingerprint: fp }]);
    assert.deepEqual(stub.ofTool('docs_read')[0].body, { select: 'list', rootId: 'root-s1', kind: 'rule', limit: 200 });
    for (const c of stub.calls) {
      assert.equal(c.method, 'POST');
      assert.equal(c.url, `/api/v1/ops/${c.tool}`);
      assert.match(c.contentType ?? '', /application\/json/);
    }
  });
});

test('S2 (AC1): relation superseded - no validate or push; "config sync skipped" naming the server head time', async () => {
  const { dir, fp } = makeProject('root-s2c');
  await withStub({ config: { version: 9, fingerprint: 'e'.repeat(64), createdAt: '2026-01-02T03:04:05Z', history: [fp] } }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const line = lineOf(res);
    assert.ok(line.includes('config sync skipped'), line);
    assert.ok(line.includes("older than the server's (updated 2026-01-02T03:04:05Z)"), line);
    assert.ok(line.includes("your checkout's config.yaml"), line);
    assert.deepEqual(stub.configOps().map((o) => o.op), ['get']);
  });
});

test('75f0e354 S6 (AC1): user scope superseded - the line names the user-level config.yaml and the server time', async () => {
  const home = tmpConfigDir();
  const cwd = tmpConfigDir();
  const text = projectConfigText('u-n6');
  writeConfig(home, text);
  await withStub({ config: { version: 3, fingerprint: 'd'.repeat(64), createdAt: '2026-01-02T03:04:05Z', history: [fpOf(text)] } }, async (stub) => {
    const res = await runUserScope(home, cwd, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const line = lineOf(res);
    assert.ok(line.includes('config sync skipped'), line);
    assert.ok(line.includes("your user-level config.yaml is older than the server's (updated 2026-01-02T03:04:05Z)"), line);
    assert.ok(!line.includes('checkout'), line);
    assert.deepEqual(stub.configOps().map((o) => o.op), ['get']);
  });
});

test('S3 (AC1-3): relation unknown and the document valid - get, validate, push in that order; push.expectedVersion is the get version; appended true says "synced"', async () => {
  const { dir, normalized, fp } = makeProject('root-s3');
  await withStub({ config: { version: 7, fingerprint: 'a'.repeat(64) } }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    assert.deepEqual(stub.configOps(), [
      { op: 'get', rootId: 'root-s3', fingerprint: fp },
      { op: 'validate', rootId: 'root-s3', configYaml: normalized },
      { op: 'push', rootId: 'root-s3', configYaml: normalized, expectedVersion: 7 },
    ]);
    const line = lineOf(res);
    assert.match(line, /\bsynced .* to root root-s3/, line);
    assert.ok(!line.includes('already in sync'), line);
    for (const c of stub.calls) {
      assert.equal(c.headers['if-match'], undefined, 'no If-Match compare-and-set');
      assert.equal(c.headers['if-none-match'], undefined);
    }
  });
});

test('S4 (AC1, AC3): get not_found kind config - validate then push, and the push carries no expectedVersion key', async () => {
  const { dir, normalized } = makeProject('root-s4');
  await withStub({}, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const ops = stub.configOps();
    assert.deepEqual(ops.map((o) => o.op), ['get', 'validate', 'push']);
    assert.ok(!('expectedVersion' in ops[2]), JSON.stringify(ops[2]));
    assert.equal(ops[2].configYaml, normalized);
    assert.match(lineOf(res), /\bsynced .* to root root-s4/);
  });
});

test('S5 (AC3): BOM + CRLF + a lone CR - validate and push carry the BOM-stripped, CRLF->LF text with the lone CR kept; get.fingerprint is that text\'s sha256', async () => {
  const dir = tmpConfigDir();
  const raw = '\ufeff# bom header\n' + 'project:\n  rootId: "root-s5"\n' + '# crlf line\r\n' + '# cr-only\rstill the same comment\n' + 'last: 1\r\n';
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), Buffer.from(raw, 'utf8'));
  const normalized = expectedNormalized(raw);
  assert.ok(!normalized.startsWith('\ufeff') && !normalized.includes('\r\n') && normalized.includes('cr-only\rstill'), 'fixture precondition');
  await withStub({ config: { version: 2, fingerprint: 'c'.repeat(64) } }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const [get, validate, push] = stub.configOps();
    assert.equal(get.fingerprint, sha256Hex(Buffer.from(normalized, 'utf8')));
    assert.equal(get.fingerprint, configFingerprint(Buffer.from(raw, 'utf8')));
    assert.equal(validate.configYaml, normalized);
    assert.equal(push.configYaml, normalized);
    assert.equal(Buffer.compare(Buffer.from(push.configYaml, 'utf8'), Buffer.from(normalized, 'utf8')), 0);
  });
});

test('S5 probe (AC3): a double leading BOM loses exactly one', async () => {
  const dir = tmpConfigDir();
  const raw = '\ufeff\ufeff# two boms\nproject:\n  rootId: "root-s5b"\n';
  mkdirSync(join(dir, '.taskorchestrator'), { recursive: true });
  writeFileSync(join(dir, '.taskorchestrator', 'config.yaml'), Buffer.from(raw, 'utf8'));
  await withStub({}, async (stub) => {
    await runHook(dir, stub.url);
    const push = stub.configOps().find((o) => o.op === 'push');
    assert.ok(push, 'the push happened');
    assert.equal(push.configYaml, '\ufeff# two boms\nproject:\n  rootId: "root-s5b"\n');
  });
});

test('S6 (AC3): appended false - "already in sync", never "synced"', async () => {
  const { dir } = makeProject('root-s6c');
  await withStub({
    config: { version: 4, fingerprint: 'a'.repeat(64) },
    override: (call) => (call.tool === 'config' && call.body.ops[0].op === 'push'
      ? batch([okEl({ rootId: 'root-s6c', version: 4, fingerprint: 'b'.repeat(64), appended: false, createdAt: '2026-01-01T00:00:00Z' })])
      : undefined),
  }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const line = lineOf(res);
    assert.ok(line.includes('already in sync'), line);
    assert.doesNotMatch(line, /\bsynced\b/);
    assert.equal(stub.configOps().filter((o) => o.op === 'push').length, 1);
  });
});

test('S3 probe (AC3): push warnings are listed after " WARNINGS: " in order joined by " | "; warnings absent or [] adds nothing', async () => {
  for (const warnings of [['first warning', 'second warning'], undefined, []]) {
    const { dir } = makeProject('root-warn');
    await withStub({
      override: (call) => (call.tool === 'config' && call.body.ops[0].op === 'push'
        ? batch([okEl({ rootId: 'root-warn', version: 1, fingerprint: 'f'.repeat(64), appended: true, createdAt: '2026-01-01T00:00:00Z', ...(warnings === undefined ? {} : { warnings: warnings.map((w) => ({ path: '/x', reason: w })) }) })])
        : undefined),
    }, async (stub) => {
      const res = await runHook(dir, stub.url);
      const line = lineOf(res);
      if (warnings === undefined || warnings.length === 0) {
        assert.ok(!line.includes('WARNINGS'), `warnings ${JSON.stringify(warnings)}: ${line}`);
      } else {
        assert.match(line, /\bsynced\b/);
        assert.match(line, / WARNINGS: .*first warning.* \| .*second warning/, line);
      }
    });
  }
});

test('S1 probe (AC1): relation absent - falls back to get.fingerprint === the local fingerprint', async () => {
  const { dir, fp } = makeProject('root-norel');
  const noRelation = (call) => {
    if (call.tool !== 'config' || call.body.ops[0].op !== 'get') return undefined;
    return batch([okEl({ rootId: 'root-norel', version: 5, fingerprint: call.body.ops[0].fingerprint === fp ? fp : 'z'.repeat(64), source: 'push', createdAt: '2026-01-01T00:00:00Z', configYaml: 'x: 1\n' })]);
  };
  await withStub({ override: noRelation }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.ok(lineOf(res).includes('already in sync'), lineOf(res));
    assert.deepEqual(stub.configOps().map((o) => o.op), ['get'], 'equal fingerprints: nothing validated or pushed');
  });
  const other = makeProject('root-norel2');
  const differs = (call) => (call.tool === 'config' && call.body.ops[0].op === 'get'
    ? batch([okEl({ rootId: 'root-norel2', version: 5, fingerprint: 'z'.repeat(64), source: 'push', createdAt: '2026-01-01T00:00:00Z', configYaml: 'x: 1\n' })])
    : undefined);
  await withStub({ config: { version: 5, fingerprint: 'z'.repeat(64) }, override: differs }, async (stub) => {
    await runHook(other.dir, stub.url);
    assert.deepEqual(stub.configOps().map((o) => o.op), ['get', 'validate', 'push']);
  });
});

// ---- S12: get failures stop the run ---------------------------------------------------------------------------------

test('S12 (AC1): get not_found kind root - "root <id> not found on this server"; one call, no write, no rules sync', async () => {
  const { dir } = makeProject('root-s12');
  writeRuleFile(dir, 'one.md', 'content\n');
  await withStub({ override: (call) => (call.tool === 'config' ? batch([errEl('not_found', { kind: 'root', id: 'root-s12' })]) : undefined) }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    assert.ok(lineOf(res).includes('root root-s12 not found on this server'), lineOf(res));
    assert.deepEqual(stub.tools(), ['config']);
  });
});

test('S12 (AC1): every other get failure - one "config sync skipped" line, nothing but the get, no rules', async () => {
  const failures = {
    unauthenticated401: () => topError(401, 'unauthenticated', 'no or bad token'),
    forbiddenElement: () => batch([errEl('forbidden', { scope: 'capability', required: 'read' })]),
    forbiddenTop: () => topError(403, 'forbidden', 'nope', { scope: 'root', required: 'read' }),
    invalidRequest: () => topError(400, 'invalid_request', 'bad request'),
    internal: () => topError(500, 'internal', 'Internal server error'),
    legacyBody: () => ({ status: 404, raw: '{"error":"Not Found"}' }),
    plainText: () => ({ status: 200, raw: 'ok' }),
  };
  for (const [name, reply] of Object.entries(failures)) {
    const { dir } = makeProject('root-s12b');
    writeRuleFile(dir, 'one.md', 'content\n');
    await withStub({ override: (call) => (call.tool === 'config' ? reply() : undefined) }, async (stub) => {
      const res = await runHook(dir, stub.url);
      assert.equal(res.status, 0, `${name}: ${res.stderr}`);
      const line = lineOf(res);
      assert.equal(line.split('config sync skipped').length - 1, 1, `${name}: ${line}`);
      assert.deepEqual(stub.tools(), ['config'], name);
    });
  }
});

test('S12 probe (AC1): an unreachable API is one "config sync skipped" line and no rules', async () => {
  const { dir } = makeProject('root-s12c');
  const res = await runHook(dir, UNREACHABLE_API_URL);
  assert.equal(res.status, 0, res.stderr);
  assert.ok(lineOf(res).includes('config sync skipped'), lineOf(res));
});

// ---- S9-S11: validate and push rejections ---------------------------------------------------------------------------

const FOUR_ERRORS = [
  { path: '/orchestration', reason: 'unknown key: orchestration' },
  { path: '', reason: 'document is not a mapping' },
  { path: '/traits/needs-x', reason: 'trait is not defined' },
  { path: '/extras', reason: 'fourth error, never shown' },
];
const EXPECTED_SHOWN = '/orchestration: unknown key: orchestration; (document): document is not a mapping; /traits/needs-x: trait is not defined';

function assertRejected(res, event, rootId, { shown = EXPECTED_SHOWN, more = ' (+1 more)' } = {}) {
  const out = JSON.parse(res.stdout);
  assert.equal(typeof out.systemMessage, 'string', `top-level systemMessage on ${event}: ${res.stdout}`);
  assert.ok(out.systemMessage.includes('config sync rejected'), out.systemMessage);
  assert.ok(out.systemMessage.includes(rootId), out.systemMessage);
  if (shown !== null) {
    assert.ok(out.systemMessage.includes(shown), out.systemMessage);
    assert.ok(out.systemMessage.includes(more), out.systemMessage);
    assert.ok(!out.systemMessage.includes('fourth error'), out.systemMessage);
  }
  if (out.hookSpecificOutput !== undefined) assert.equal(out.hookSpecificOutput.hookEventName, event, res.stdout);
  return out;
}

for (const event of ['SessionStart', 'FileChanged']) {
  test(`S9 (AC2, AC4) ${event}: validate valid:false with four errors - no push; systemMessage lists the first three and "(+1 more)"`, async () => {
    const { dir } = makeProject('root-s9');
    await withStub({
      config: { version: 3, fingerprint: 'a'.repeat(64) },
      override: (call) => (call.tool === 'config' && call.body.ops[0].op === 'validate'
        ? batch([okEl({ rootId: 'root-s9', valid: false, errors: FOUR_ERRORS, warnings: [] })])
        : undefined),
    }, async (stub) => {
      const res = await runHook(dir, stub.url, { event });
      assert.equal(res.status, 0, res.stderr);
      assertRejected(res, event, 'root-s9');
      assert.deepEqual(stub.configOps().map((o) => o.op), ['get', 'validate'], 'validate precedes every push and a rejection is never pushed');
    });
  });

  test(`S10 (AC4) ${event}: push config_invalid (207) - exactly one validate and one push (never retried); systemMessage rejected`, async () => {
    const { dir } = makeProject('root-s10');
    await withStub({
      config: { version: 3, fingerprint: 'a'.repeat(64) },
      override: (call) => (call.tool === 'config' && call.body.ops[0].op === 'push'
        ? batch([errEl('config_invalid', { errors: FOUR_ERRORS }, 'config is invalid')])
        : undefined),
    }, async (stub) => {
      const res = await runHook(dir, stub.url, { event });
      assert.equal(res.status, 0, res.stderr);
      assertRejected(res, event, 'root-s10');
      assert.deepEqual(stub.configOps().map((o) => o.op), ['get', 'validate', 'push']);
    });
  });
}

test('S9 probe (AC4): the cap is exactly three - with three errors there is no "more", and an empty path prints "(document)"', async () => {
  const { dir } = makeProject('root-s9b');
  const three = FOUR_ERRORS.slice(0, 3);
  await withStub({
    config: { version: 3, fingerprint: 'a'.repeat(64) },
    override: (call) => (call.tool === 'config' && call.body.ops[0].op === 'validate'
      ? batch([okEl({ rootId: 'root-s9b', valid: false, errors: three, warnings: [] })])
      : undefined),
  }, async (stub) => {
    const res = await runHook(dir, stub.url);
    const out = JSON.parse(res.stdout);
    assert.ok(out.systemMessage.includes(EXPECTED_SHOWN), out.systemMessage);
    assert.ok(!out.systemMessage.includes('more'), out.systemMessage);
  });
});

test('S9 probe (AC4): a reason longer than 200 characters is cut in the user-visible line', async () => {
  const { dir } = makeProject('root-s9c');
  await withStub({
    config: { version: 3, fingerprint: 'a'.repeat(64) },
    override: (call) => (call.tool === 'config' && call.body.ops[0].op === 'validate'
      ? batch([okEl({ rootId: 'root-s9c', valid: false, errors: [{ path: '/p', reason: 'q'.repeat(600) }], warnings: [] })])
      : undefined),
  }, async (stub) => {
    const res = await runHook(dir, stub.url);
    const out = JSON.parse(res.stdout);
    assert.ok(out.systemMessage.includes('q'.repeat(200)), 'kept 200');
    assert.ok(!out.systemMessage.includes('q'.repeat(201)), 'cut at 200');
  });
});

test('S9 probe (AC4): valid:false with errors [] is still a rejection - no push', async () => {
  const { dir } = makeProject('root-s9d');
  await withStub({
    config: { version: 3, fingerprint: 'a'.repeat(64) },
    override: (call) => (call.tool === 'config' && call.body.ops[0].op === 'validate'
      ? batch([okEl({ rootId: 'root-s9d', valid: false, errors: [], warnings: [] })])
      : undefined),
  }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    assertRejected(res, 'SessionStart', 'root-s9d', { shown: null });
    assert.deepEqual(stub.configOps().map((o) => o.op), ['get', 'validate']);
  });
});

test('S10 probe (AC4): payload_too_large on validate and on push are rejections, never retried', async () => {
  for (const failingOp of ['validate', 'push']) {
    const { dir } = makeProject('root-ptl');
    await withStub({
      config: { version: 3, fingerprint: 'a'.repeat(64) },
      override: (call) => (call.tool === 'config' && call.body.ops[0].op === failingOp
        ? batch([errEl('payload_too_large', { max: 131072, actual: 200000 })])
        : undefined),
    }, async (stub) => {
      const res = await runHook(dir, stub.url);
      assert.equal(res.status, 0, res.stderr);
      assertRejected(res, 'SessionStart', 'root-ptl', { shown: null });
      assert.ok(lineOf(res).includes('(document):'), `payload_too_large carries no path, so the entry reads (document): ${lineOf(res)}`);
      const ops = stub.configOps().map((o) => o.op);
      assert.equal(ops.filter((o) => o === failingOp).length, 1, `${failingOp} sent once: ${ops}`);
      assert.ok(!ops.includes('push') || failingOp === 'push', `no push after a rejected validate: ${ops}`);
    });
  }
});

test('S9 (AC4): rules still sync after a rejection', async () => {
  const { dir } = makeProject('root-s9e');
  writeRuleFile(dir, 'afterreject.md', 'Rule text.\n');
  await withStub({
    config: { version: 3, fingerprint: 'a'.repeat(64) },
    override: (call) => (call.tool === 'config' && call.body.ops[0].op === 'validate'
      ? batch([okEl({ rootId: 'root-s9e', valid: false, errors: FOUR_ERRORS, warnings: [] })])
      : undefined),
  }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    assert.deepEqual(stub.upserts().map((u) => u.slug), ['afterreject']);
    assert.ok(JSON.parse(res.stdout).systemMessage.includes('config sync rejected'));
  });
});

test('S11 (AC3): push version_conflict - "config sync deferred", "updated concurrently", "will retry next session"; one push', async () => {
  const { dir } = makeProject('root-s11c');
  await withStub({
    config: { version: 3, fingerprint: 'a'.repeat(64) },
    override: (call) => (call.tool === 'config' && call.body.ops[0].op === 'push'
      ? batch([errEl('version_conflict', { kind: 'config', id: 'root-s11c', expected: 3, actual: 4 })])
      : undefined),
  }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const line = lineOf(res);
    assert.ok(line.includes('config sync deferred'), line);
    assert.ok(line.includes('updated concurrently'), line);
    assert.ok(line.includes('will retry next session'), line);
    assert.equal(stub.configOps().filter((o) => o.op === 'push').length, 1);
  });
});

// ---- rules: S7, S13 ------------------------------------------------------------------------------------------------

const putKeyOf = (u) => u.slug;
function inSyncProject(rootId) {
  const p = makeProject(rootId);
  return { ...p, configState: { version: 1, fingerprint: p.fp } };
}

test('S7 (AC5): changed + absent rules - exactly two upserts {op, rootId, kind:rule, slug, normalized body, expectedVersion listed/0}', async () => {
  const { dir, configState } = inSyncProject('root-s7');
  writeRuleFile(dir, 'changed.md', 'New changed content.\r\nSecond line.\r\n');
  writeRuleFile(dir, 'missing.md', 'Brand new rule.\n');
  writeRuleFile(dir, 'unchanged.md', 'Steady rule.\n');
  await withStub({
    config: configState,
    docs: {
      changed: { hash: 'f'.repeat(64), version: 3 },
      unchanged: { hash: sha256Hex(Buffer.from('Steady rule.\n')), version: 6 },
    },
  }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const ups = stub.upserts();
    assert.equal(ups.length, 2, JSON.stringify(ups));
    const bySlug = Object.fromEntries(ups.map((u) => [u.slug, u]));
    assert.deepEqual(bySlug.changed, { op: 'upsert', rootId: 'root-s7', kind: 'rule', slug: 'changed', body: 'New changed content.\nSecond line.\n', expectedVersion: 3 });
    assert.deepEqual(bySlug.missing, { op: 'upsert', rootId: 'root-s7', kind: 'rule', slug: 'missing', body: 'Brand new rule.\n', expectedVersion: 0 });
    for (const slug of Object.keys(bySlug)) assert.ok(!slug.startsWith('rule/'), 'rule documents carry no rule/ prefix');
    const line = lineOf(res);
    assert.ok(line.includes('changed') && line.includes('missing'), line);
    assert.ok(line.includes('pushed 2'), line);
  });
});

test('S7 probe (AC5): sequential - one docs_write in flight at a time, protocol.* keys before every other key', async () => {
  const { dir, configState } = inSyncProject('root-s7b');
  writeRuleFile(dir, 'aaa.md', 'Alphabetically first workspace rule.\n');
  writeRuleFile(dir, 'bbb.md', 'Another.\n');
  await withStub({ config: configState, seedBundled: false }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const slugs = stub.upserts().map(putKeyOf);
    assert.equal(slugs.length, 7, slugs.join(','));
    assert.deepEqual(new Set(slugs.slice(0, 3)), new Set(PROTOCOL_BUNDLED_KEYS), `protocol keys first: ${slugs}`);
    assert.deepEqual(stub.tools().slice(0, 2), ['config', 'docs_read']);
  });
});

test('S7 (AC5): in-flight is observed - upserts answered after 40ms never overlap', async () => {
  const { dir, configState } = inSyncProject('root-s7c');
  for (const n of ['r1', 'r2', 'r3']) writeRuleFile(dir, `${n}.md`, `Rule ${n}.\n`);
  await withStub({
    config: configState,
    override: (call) => (call.tool === 'docs_write'
      ? { ...batch([okEl({ id: '11111111-1111-4111-8111-111111111111', kind: 'rule', slug: call.body.ops[0].slug, version: 1, contentHash: 'a'.repeat(64), created: true })]), delayMs: 40 }
      : undefined),
  }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    assert.equal(stub.upserts().length, 3);
    assert.equal(stub.maxInFlight(), 1, 'the three upserts must be serialized');
  });
});

test('S7 probe (AC5): the hash compare is on LF-normalized bytes - a CRLF rule whose server hash is the LF hash is not re-pushed', async () => {
  const { dir, configState } = inSyncProject('root-crlf');
  writeRuleFile(dir, 'crlf.md', 'Line one.\r\nLine two.\r\n');
  await withStub({ config: configState, docs: { crlf: { hash: sha256Hex(Buffer.from('Line one.\nLine two.\n')), version: 2 } } }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    assert.equal(stub.upserts().length, 0);
    assert.ok(lineOf(res).includes('6 in sync.'), lineOf(res)); // 1 workspace + 5 bundled
  });
});

test('S7 probe (AC5): a workspace rule with a bundled key wins - workspace bytes are upserted even when the server holds the bundled hash', async () => {
  const { dir, configState } = inSyncProject('root-s5w');
  const content = 'Workspace override of the entry seat.\n';
  writeRuleFile(dir, 'protocol.entry-seat.md', content);
  await withStub({ config: configState }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const ups = stub.upserts();
    assert.equal(ups.length, 1);
    assert.equal(ups[0].slug, 'protocol.entry-seat');
    assert.equal(ups[0].body, content);
    assert.equal(ups[0].expectedVersion, 1);
  });
});

test('S7 probe (AC5): docs_read list follows page.next - a rule on page two counts as in sync and is not re-pushed', async () => {
  const { dir, configState } = inSyncProject('root-pages');
  writeRuleFile(dir, 'zzz-last.md', 'Sorts last, so it lives on the final page.\n');
  await withStub({
    config: configState,
    pageSize: 2,
    docs: { 'zzz-last': { hash: sha256Hex(Buffer.from('Sorts last, so it lives on the final page.\n')), version: 4 } },
  }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const lists = stub.ofTool('docs_read').map((c) => c.body);
    assert.ok(lists.length >= 2, `paged: ${JSON.stringify(lists)}`);
    assert.equal(lists[0].cursor, undefined);
    assert.equal(lists[1].cursor, '2');
    for (const l of lists) assert.deepEqual({ select: l.select, rootId: l.rootId, kind: l.kind, limit: l.limit }, { select: 'list', rootId: 'root-pages', kind: 'rule', limit: 200 });
    assert.equal(stub.upserts().length, 0, JSON.stringify(stub.upserts()));
    assert.ok(lineOf(res).includes('6 in sync.'), lineOf(res));
  });
});

test('S7 probe (AC5): duplicate rows for one slug in the listing change nothing', async () => {
  const { dir, configState } = inSyncProject('root-dup');
  const rows = bundledAtCurrentHash().flatMap(({ key, hash }) => [key, key].map(() => ({ id: '00000000-0000-4000-8000-000000000001', kind: 'rule', slug: key, contentHash: hash, version: 1, createdAt: 'x', modifiedAt: 'x' })));
  await withStub({
    config: configState,
    override: (call) => (call.tool === 'docs_read' ? listAnswer(rows) : undefined),
  }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    assert.equal(stub.upserts().length, 0);
  });
});

test('T3 (AC5): an invalid key and an oversized body are skipped client-side; valid siblings still sync', async () => {
  const { dir, configState } = inSyncProject('root-t3');
  writeRuleFile(dir, 'Bad Key.md', 'Has spaces in the filename.\n');
  writeRuleFile(dir, 'toobig.md', 'x'.repeat(16385));
  writeRuleFile(dir, 'valid-key.md', 'Fine.\n');
  await withStub({ config: configState }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const line = lineOf(res);
    assert.ok(line.includes('Bad Key') || line.includes('invalid key'), line);
    assert.ok(line.includes('toobig') && line.includes('16384 bytes'), line);
    assert.deepEqual(stub.upserts().map(putKeyOf), ['valid-key']);
  });
});

test('S13 (AC5): an upsert forbidden - failed, and the reason names write-docs; the run still exits 0', async () => {
  const { dir, configState } = inSyncProject('root-s13');
  writeRuleFile(dir, 'denied.md', 'content\n');
  await withStub({
    config: configState,
    override: (call) => (call.tool === 'docs_write' ? batch([errEl('forbidden', { scope: 'capability', required: 'write-docs' })]) : undefined),
  }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const line = lineOf(res);
    assert.ok(line.includes('failed'), line);
    assert.ok(line.includes('denied'), line);
    assert.ok(line.includes('write-docs'), line);
    assert.ok(!line.includes('pushed'), line);
  });
});

test('S13 (AC5): an upsert version_conflict is failed (version_conflict) and not retried; a top-level 401 is failed (unauthenticated)', async () => {
  for (const [reply, code] of [
    [() => batch([errEl('version_conflict', { kind: 'document', id: 'rule/conflict', expected: 1, actual: 2 })]), 'version_conflict'],
    [() => topError(401, 'unauthenticated', 'bad token'), 'unauthenticated'],
  ]) {
    const { dir, configState } = inSyncProject('root-s13b');
    writeRuleFile(dir, 'conflict.md', 'content\n');
    await withStub({ config: configState, override: (call) => (call.tool === 'docs_write' ? reply() : undefined) }, async (stub) => {
      const res = await runHook(dir, stub.url);
      assert.equal(res.status, 0, res.stderr);
      const line = lineOf(res);
      assert.ok(line.includes('failed'), line);
      assert.ok(line.includes(`conflict (${code})`), line);
      assert.equal(stub.upserts().length, 1, 'one attempt per rule');
    });
  }
});

// ---- bundled rules: S16 --------------------------------------------------------------------------------------------------

test('S16 (AC5): no workspace rules, none on the server - all five bundled rules pushed with normalized bundled bytes and expectedVersion 0', async () => {
  const { dir, configState } = inSyncProject('root-s16');
  await withStub({ config: configState, seedBundled: false }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const ups = stub.upserts();
    assert.equal(ups.length, 5);
    assert.deepEqual(new Set(ups.map(putKeyOf)), new Set(BUNDLED_KEYS));
    for (const u of ups) {
      assert.equal(u.kind, 'rule');
      assert.equal(u.expectedVersion, 0);
      assert.equal(u.body, bundledNormalizedBytes(u.slug).toString('utf8'), `body for ${u.slug}`);
    }
    const line = lineOf(res);
    for (const k of BUNDLED_KEYS) assert.ok(line.includes(k), `pushed list names ${k}: ${line}`);
  });
});

test('S16 (AC5): an unknown server hash for a bundled key is never overwritten; the other four are pushed and the key is named', async () => {
  const { dir, configState } = inSyncProject('root-s16b');
  await withStub({ config: configState, seedBundled: false, docs: { 'commit-discipline': { hash: 'a'.repeat(64), version: 5 } } }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    const slugs = stub.upserts().map(putKeyOf);
    assert.equal(slugs.length, 4);
    assert.ok(!slugs.includes('commit-discipline'));
    assert.ok(lineOf(res).includes('kept unrecognized server copy: commit-discipline'), lineOf(res));
  });
});

test('S16 / 75f0e354 S2 (AC5): unusable listing shapes - zero upserts and the "bundled rules skipped" clause', async () => {
  const unusable = [{ items: {} }, {}, { items: null }, { items: 'x' }];
  for (const data of unusable) {
    const { dir, configState } = inSyncProject('root-unusable');
    await withStub({
      config: configState,
      override: (call) => (call.tool === 'docs_read' ? { status: 200, json: { ok: true, data, meta: META } } : undefined),
    }, async (stub) => {
      const res = await runHook(dir, stub.url);
      assert.equal(res.status, 0, `${JSON.stringify(data)}: ${res.stderr}`);
      assert.equal(stub.upserts().length, 0, `data ${JSON.stringify(data)} must cause no upsert`);
      assert.ok(lineOf(res).endsWith('Rules: 0 in sync.; bundled rules skipped (unusable rules listing)'), `${JSON.stringify(data)}: ${lineOf(res)}`);
    });
  }
});

test('75f0e354 S15 (AC5): listing elements that are null / non-object / lack a string slug are ignored; the listing is usable', async () => {
  const { dir, configState } = inSyncProject('root-s15e');
  const good = bundledAtCurrentHash().map(({ key, hash }) => ({ id: '00000000-0000-4000-8000-000000000002', kind: 'rule', slug: key, contentHash: hash, version: 1, createdAt: 'x', modifiedAt: 'x' }));
  await withStub({ config: configState, override: (call) => (call.tool === 'docs_read' ? listAnswer([null, 5, { slug: 7 }, ...good]) : undefined) }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.status, 0, res.stderr);
    assert.equal(stub.upserts().length, 0);
    assert.ok(lineOf(res).includes('5 in sync.'), lineOf(res));
    assert.ok(!lineOf(res).includes('unusable'), lineOf(res));
  });
});

test('S16 (AC5): a plain-text or error-envelope listing never causes a bundled upsert and does not lose the config line', async () => {
  const replies = [
    () => ({ status: 200, raw: 'not json' }),
    () => topError(403, 'forbidden', 'nope', { scope: 'capability', required: 'read' }),
    () => topError(404, 'not_found', 'no such root', { kind: 'root', id: 'x' }),
  ];
  for (const reply of replies) {
    const { dir, configState } = inSyncProject('root-badlist');
    await withStub({ config: configState, override: (call) => (call.tool === 'docs_read' ? reply() : undefined) }, async (stub) => {
      const res = await runHook(dir, stub.url);
      assert.equal(res.status, 0, res.stderr);
      assert.ok(lineOf(res).includes('already in sync'), lineOf(res));
      const bundled = stub.upserts().filter((u) => BUNDLED_KEYS.includes(u.slug));
      assert.deepEqual(bundled, []);
    });
  }
});

// ---- copied-hook harness (no production seam for the bundled-rules dir): the hook directory and a
// test-written bundled-rules dir are copied into a throwaway tree so the bundled dir resolves there.

function copyTree(from, to) {
  mkdirSync(to, { recursive: true });
  for (const entry of readdirSync(from)) {
    if (from === HOOKS_SRC && entry === 'tests') continue;
    const f = join(from, entry);
    if (statSync(f).isDirectory()) copyTree(f, join(to, entry));
    else writeFileSync(join(to, entry), readFileSync(f));
  }
}

function makeCopiedHook({ key, body, manifestHashes }) {
  const root = mkTemp('to-copied-hook-');
  copyTree(HOOKS_SRC, join(root, 'hooks'));
  mkdirSync(join(root, 'bundled-rules'), { recursive: true });
  writeFileSync(join(root, 'bundled-rules', `${key}.md`), body);
  writeFileSync(join(root, 'bundled-rules', 'manifest.json'), JSON.stringify({ [key]: manifestHashes }));
  return { root, hook: join(root, 'hooks', 'config-sync.mjs') };
}

const ZZ_BODY = 'Zz test bundled rule, current text.\n';
const ZZ_OLD_HASH = sha256Hex(Buffer.from('Zz test bundled rule, an older shipped text.\n'));
const ZZ_CUR_HASH = sha256Hex(Buffer.from(ZZ_BODY));

test('S16 / 75f0e354 S10 (AC5): a known-old server copy (older manifest hash) is overwritten with the current bundled bytes at the listed version', async () => {
  const { dir, configState } = inSyncProject('root-n10');
  const copied = makeCopiedHook({ key: 'zz-test', body: ZZ_BODY, manifestHashes: [ZZ_OLD_HASH, ZZ_CUR_HASH] });
  await withStub({ config: configState, seedBundled: false, docs: { 'zz-test': { hash: ZZ_OLD_HASH, version: 3 } } }, async (stub) => {
    const res = await runHook(dir, stub.url, { script: copied.hook });
    assert.equal(res.status, 0, res.stderr);
    assert.deepEqual(stub.upserts(), [{ op: 'upsert', rootId: 'root-n10', kind: 'rule', slug: 'zz-test', body: ZZ_BODY, expectedVersion: 3 }]);
    assert.ok(lineOf(res).includes('pushed 1 (zz-test)'), lineOf(res));
  });
});

test('S16 / 75f0e354 S11 (AC5): an unrecognized server copy is never touched and is named in the line', async () => {
  const { dir, configState } = inSyncProject('root-n11');
  const copied = makeCopiedHook({ key: 'zz-test', body: ZZ_BODY, manifestHashes: [ZZ_OLD_HASH, ZZ_CUR_HASH] });
  await withStub({ config: configState, seedBundled: false, docs: { 'zz-test': { hash: 'b'.repeat(64), version: 2 } } }, async (stub) => {
    const res = await runHook(dir, stub.url, { script: copied.hook });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(stub.upserts().length, 0);
    assert.ok(lineOf(res).includes('kept unrecognized server copy: zz-test'), lineOf(res));
  });
});

test('S16 probe (AC5): a server copy at the current hash is in sync - no upsert and no "kept unrecognized" clause', async () => {
  const { dir, configState } = inSyncProject('root-n11b');
  const copied = makeCopiedHook({ key: 'zz-test', body: ZZ_BODY, manifestHashes: [ZZ_OLD_HASH, ZZ_CUR_HASH] });
  await withStub({ config: configState, seedBundled: false, docs: { 'zz-test': { hash: ZZ_CUR_HASH, version: 2 } } }, async (stub) => {
    const res = await runHook(dir, stub.url, { script: copied.hook });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(stub.upserts().length, 0);
    assert.ok(!lineOf(res).includes('kept unrecognized'), lineOf(res));
    assert.ok(lineOf(res).includes('1 in sync.'), lineOf(res));
  });
});

test('S16 (AC5): FileChanged pushes workspace rules only - no bundled sync', async () => {
  const { dir, configState } = inSyncProject('root-s12fc');
  writeRuleFile(dir, 'only.md', 'The only workspace rule.\n');
  await withStub({ config: configState, seedBundled: false }, async (stub) => {
    const res = await runHook(dir, stub.url, { event: 'FileChanged' });
    assert.equal(res.status, 0, res.stderr);
    assert.deepEqual(stub.upserts().map(putKeyOf), ['only']);
  });
});

// ---- replay against a stateful server ------------------------------------------------------------------------------------

test('probe: replay against the stateful stub - run one syncs config and six rules, run two pushes and upserts nothing', async () => {
  const dir = tmpConfigDir();
  writeConfig(dir, projectConfigText('root-replay'));
  writeRuleFile(dir, 'aaa.md', 'Replay rule.\r\n');
  await withStub({ seedBundled: false }, async (stub) => {
    const first = await runHook(dir, stub.url);
    assert.equal(first.status, 0, first.stderr);
    assert.equal(stub.upserts().length, 6);
    assert.equal(stub.configOps().filter((o) => o.op === 'push').length, 1);
    const before = stub.calls.length;
    const second = await runHook(dir, stub.url);
    assert.equal(second.status, 0, second.stderr);
    const delta = stub.calls.slice(before);
    assert.deepEqual(delta.map((c) => c.tool), ['config', 'docs_read'], 'run two: a get and a listing only');
    assert.ok(lineOf(second).includes('already in sync'), lineOf(second));
    assert.ok(lineOf(second).includes('Rules: 6 in sync.'), lineOf(second));
  });
});

// ---- S15: the shared deadline ----------------------------------------------------------------------------------------------

test('S15 / 75f0e354 S9 + S13 (AC5, AC6) SessionStart: shared 8000ms budget - protocol keys first, bounded wall time, in-flight upserts deferred (never failed), nothing sent after the deadline', async () => {
  const { dir, configState } = inSyncProject('root-s9d2');
  for (let i = 1; i <= 8; i += 1) writeRuleFile(dir, `w${i}.md`, `Workspace rule ${i}.\n`);
  await withStub({
    config: configState,
    seedBundled: false,
    override: (call) => (call.tool === 'docs_write'
      ? { ...batch([okEl({ id: '11111111-1111-4111-8111-111111111111', kind: 'rule', slug: call.body.ops[0].slug, version: 1, contentHash: 'a'.repeat(64), created: true })]), delayMs: 1500 }
      : undefined),
  }, async (stub) => {
    const started = Date.now();
    const res = await runHook(dir, stub.url);
    const wall = Date.now() - started;
    assert.equal(res.killed, false);
    assert.equal(res.status, 0, res.stderr);
    assert.ok(wall < 9500, `wall time ${wall}ms must stay under the 8000ms budget plus margin`);
    const slugs = stub.upserts().map(putKeyOf);
    assert.ok(slugs.length >= 3, `received ${slugs.length} upserts`);
    assert.deepEqual(new Set(slugs.slice(0, 3)), new Set(PROTOCOL_BUNDLED_KEYS), 'first three upserts are the protocol.* keys');
    const line = statusLine(assertEventContract(res, 'SessionStart'));
    assert.ok(!line.includes('failed'), `an aborted-in-flight upsert must not be reported as failed: ${line}`);
    const deferred = line.match(/(\d+) deferred to next session/);
    assert.ok(deferred, `deferral clause missing: ${line}`);
    const pushed = line.match(/pushed (\d+)/);
    const x = pushed ? Number(pushed[1]) : 0;
    assert.equal(x + Number(deferred[1]), 13, `pushed ${x} + deferred ${deferred[1]} must cover all 13 rules: ${line}`);
    assert.ok(slugs.length === x || slugs.length === x + 1, `server received ${slugs.length} upserts, hook reports ${x} pushed`);
    const lastArrival = Math.max(...stub.calls.map((c) => c.at)) - started;
    assert.ok(lastArrival < 8000 + 1000, `nothing may be sent once the budget is spent (last request at +${lastArrival}ms)`);
  });
});

test('S15 / 75f0e354 S10 (AC6) FileChanged: 3500ms budget - bounded wall time, deferrals reported, no bundled upserts', async () => {
  const { dir, configState } = inSyncProject('root-s10fc');
  for (let i = 1; i <= 4; i += 1) writeRuleFile(dir, `w${i}.md`, `Workspace rule ${i}.\n`);
  await withStub({
    config: configState,
    seedBundled: false,
    override: (call) => (call.tool === 'docs_write'
      ? { ...batch([okEl({ id: '11111111-1111-4111-8111-111111111111', kind: 'rule', slug: call.body.ops[0].slug, version: 1, contentHash: 'a'.repeat(64), created: true })]), delayMs: 1500 }
      : undefined),
  }, async (stub) => {
    const started = Date.now();
    const res = await runHook(dir, stub.url, { event: 'FileChanged' });
    const wall = Date.now() - started;
    assert.equal(res.status, 0, res.stderr);
    assert.ok(wall < 5000, `wall time ${wall}ms must stay under the 3500ms budget plus margin`);
    const slugs = stub.upserts().map(putKeyOf);
    for (const k of BUNDLED_KEYS) assert.ok(!slugs.includes(k), `no bundled upsert on FileChanged: ${k}`);
    const line = statusLine(assertEventContract(res, 'FileChanged'));
    const m = line.match(/(\d+) deferred to next session/);
    assert.ok(m, `deferral clause missing: ${line}`);
    assert.ok(Number(m[1]) >= 1);
    assert.ok(!line.includes('failed'), line);
    const lastArrival = Math.max(...stub.calls.map((c) => c.at)) - started;
    assert.ok(lastArrival < 3500 + 1000, `nothing may be sent once the budget is spent (last request at +${lastArrival}ms)`);
  });
});

test('75f0e354 S14 (AC6): the rules listing deferred by the FileChanged deadline - "sync deferred to next session", zero upserts', async () => {
  const { dir, configState } = inSyncProject('root-n14');
  writeRuleFile(dir, 'only.md', 'The only workspace rule.\n');
  await withStub({
    config: configState,
    override: (call) => {
      if (call.tool === 'config') return { ...batch([okEl({ rootId: 'root-n14', version: 1, fingerprint: configState.fingerprint, source: 'push', createdAt: 'x', configYaml: 'a: 1\n', relation: 'current' })]), delayMs: 1900 };
      if (call.tool === 'docs_read') return { ...listAnswer([]), delayMs: 2500 };
      return undefined;
    },
  }, async (stub) => {
    const started = Date.now();
    const res = await runHook(dir, stub.url, { event: 'FileChanged' });
    const wall = Date.now() - started;
    assert.equal(res.status, 0, res.stderr);
    assert.ok(wall < 5000, `wall time ${wall}ms`);
    assert.equal(stub.upserts().length, 0);
    assert.ok(lineOf(res).includes('sync deferred to next session'), lineOf(res));
    assert.ok(!lineOf(res).includes('failed'), lineOf(res));
  });
});

test('75f0e354 S9 (AC5, AC6): a stalled listing body - the hook still exits and emits, bounded wall time, zero upserts', async () => {
  const { dir, configState } = inSyncProject('root-n9');
  await withStub({ config: configState, override: (call) => (call.tool === 'docs_read' ? { stall: true } : undefined) }, async (stub) => {
    const res = await runHook(dir, stub.url);
    assert.equal(res.killed, false, 'the hook must exit on its own despite the stalled body');
    assert.equal(res.status, 0, res.stderr);
    assert.ok(res.wall < 9500, `wall time ${res.wall}ms must stay under the 8000ms budget plus margin`);
    assert.equal(stub.upserts().length, 0);
    const line = lineOf(res);
    assert.ok(line.includes('already in sync'), line);
    assert.ok(line.includes('Rules:'), `the rules phase is reported, not silently dropped: ${line}`);
    // Amendment A1 (b): a failed first docs_read listing page (an uncapped stall included) is reported as the
    // listing failing with reason timeout; the bundled-skipped clause is only for an ok listing with non-list items.
    assert.ok(line.includes('Rules: sync skipped - listing rules failed (timeout)'), line);
    assert.equal(stub.upserts().length, 0, 'no docs_write call is sent after a failed listing');
  });
});

// ---- Amendment A2 / review B1: a capped config validate that hits the shared deadline defers --------------------------------

// get answers ok (relation unknown) after ~1700ms of the 3500ms FileChanged budget, so the validate call that
// follows has only ~1800ms left and is capped below the 2000ms per-call ceiling. Oracle: task-scope AC6 ("a
// timed-out capped call counts deferred, never failed") and Amendment A2 (a deadline result on validate renders
// the push-style "config sync deferred - <reason>; will retry next session." line and sends no push).
test('A2 / B1 (AC6) FileChanged: get answers at ~1700ms, validate and docs_read stall - "config sync deferred" with reason timeout, no push, zero docs_write', async () => {
  const { dir } = makeProject('root-a2b1');
  await withStub({
    config: { version: 3, fingerprint: 'a'.repeat(64) },
    override: (call) => {
      if (call.tool === 'config' && call.body.ops[0].op === 'get') {
        return { ...batch([okEl({ rootId: 'root-a2b1', version: 3, fingerprint: 'a'.repeat(64), source: 'push', createdAt: '2026-01-01T00:00:00Z', configYaml: 'project: {}\n', relation: 'unknown' })]), delayMs: 1700 };
      }
      if (call.tool === 'config' && call.body.ops[0].op === 'validate') return { stall: true };
      if (call.tool === 'docs_read') return { stall: true };
      return undefined;
    },
  }, async (stub) => {
    const res = await runHook(dir, stub.url, { event: 'FileChanged' });
    assert.equal(res.killed, false, 'the hook must exit on its own despite the stalled validate');
    assert.equal(res.status, 0, res.stderr);
    assert.ok(res.wall < 5500, `wall time ${res.wall}ms must stay near the 3500ms budget`);
    const out = assertEventContract(res, 'FileChanged');
    assert.equal(typeof out.systemMessage, 'string', res.stdout);
    assert.ok(out.systemMessage.includes('config sync deferred'), out.systemMessage);
    assert.ok(out.systemMessage.includes('timeout'), out.systemMessage);
    assert.ok(!out.systemMessage.includes('config sync failed'), `a deadline validate is deferred, never failed: ${out.systemMessage}`);
    const ops = stub.configOps().map((o) => o.op);
    assert.ok(ops.includes('validate'), `the validate call was sent (so the deadline, not a skip, ended it): ${ops}`);
    assert.ok(!ops.includes('push'), `no push after a deferred validate: ${ops}`);
    assert.equal(stub.ofTool('docs_write').length, 0, 'zero docs_write calls');
  });
});

test('A2 / B1 control (AC4) FileChanged: validate answers 207 forbidden - "config sync failed - validate forbidden: needs read", no push', async () => {
  const { dir } = makeProject('root-a2b1c');
  await withStub({
    config: { version: 3, fingerprint: 'a'.repeat(64) },
    override: (call) => (call.tool === 'config' && call.body.ops[0].op === 'validate'
      ? batch([errEl('forbidden', { needs: 'read' }, 'needs read')])
      : undefined),
  }, async (stub) => {
    const res = await runHook(dir, stub.url, { event: 'FileChanged' });
    assert.equal(res.status, 0, res.stderr);
    const out = assertEventContract(res, 'FileChanged');
    assert.ok(out.systemMessage.includes('config sync failed - validate forbidden: needs read'), out.systemMessage);
    assert.ok(!out.systemMessage.includes('config sync deferred'), out.systemMessage);
    assert.deepEqual(stub.configOps().map((o) => o.op), ['get', 'validate']);
  });
});

// ---- user scope, no URL, hostile root ids ----------------------------------------------------------------------------------

test('S1 user scope (AC1): config under the pinned home syncs and the line says "user config"', async () => {
  const home = tmpConfigDir();
  const cwd = tmpConfigDir();
  const text = projectConfigText('u1');
  writeConfig(home, text);
  await withStub({ config: { version: 1, fingerprint: fpOf(text) } }, async (stub) => {
    const res = await runUserScope(home, cwd, stub.url);
    assert.equal(res.status, 0, res.stderr);
    assert.deepEqual(stub.configOps(), [{ op: 'get', rootId: 'u1', fingerprint: fpOf(text) }]);
    const line = lineOf(res);
    assert.ok(line.includes('root u1'), line);
    assert.ok(line.includes('user config'), line);
    assert.ok(!line.includes('project config'), line);
  });
});

test('S13 user scope: a user config without a rootId - silent exit, no requests', async () => {
  const home = tmpConfigDir();
  const cwd = tmpConfigDir();
  writeConfig(home, 'retrospective:\n  mode: nudge\n');
  await withStub({}, async (stub) => {
    const res = await runUserScope(home, cwd, stub.url);
    assert.equal(res.status, 0, res.stderr);
    assert.equal(res.stdout, '');
    assert.equal(stub.calls.length, 0);
  });
});

// ---- 4e15b651 amendment A2: rootId validation and origin-only project URL, end to end ----
// Oracle: task-scope A2.1-A2.5 (a rootId failing ^[A-Za-z0-9][A-Za-z0-9-]{0,63}$ is "no rootId": config-sync
// returns before any request and prints nothing; a project-level client.json URL with a path, query or fragment
// is ignored); the rootId now travels in the request body, never in a path.

const A2_TOKEN = 'dummy-token-a2';
const A2_BAD_ROOT_IDS = [
  '../../../_cluster/settings?x=',
  'a/b',
  'x%2fy',
  '..',
  '.',
  'a b',
  'a.b',
  'a' + String.fromCharCode(92) + 'b',
  'a?b',
  'a:b',
  'a@b',
];

function a2Tree(configText) {
  const T = realpathSync(mkTemp('to-a2-'));
  const P = join(T, 'proj');
  writeConfig(P, configText);
  return { T, P };
}

function spawnA2(P, T, { apiUrl, event = 'SessionStart' } = {}) {
  const { env } = hermeticEnv({ TASK_ORCHESTRATOR_CEILING: T, TASK_ORCHESTRATOR_API_TOKEN: A2_TOKEN });
  if (apiUrl !== undefined) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
  return runScript(HOOK, { cwd: P, env, input: eventInput(event, join(P, '.taskorchestrator', 'config.yaml')) });
}

test('4e15b651 S44: config-sync parseRootId returns null for a value failing the rootId rule (bare and quoted); a plain token is unchanged', () => {
  for (const v of A2_BAD_ROOT_IDS) {
    assert.equal(parseRootId(`project:\n  rootId: ${v}\n`), null, v);
  }
  assert.equal(parseRootId('project:\n  rootId: "a/b"\n'), null);
  assert.equal(parseRootId('project:\n  rootId: abc-123\n'), 'abc-123');
});

test('4e15b651 S45: a hostile rootId makes config-sync silent with zero requests; a plain token syncs with that id in the request body', async () => {
  for (const v of ['../../../_cluster/settings?x=', 'a/b', '..']) {
    const { T, P } = a2Tree(`project:\n  rootId: ${v}\n  name: A2\n`);
    await withStub({}, async (stub) => {
      const res = await spawnA2(P, T, { apiUrl: stub.url });
      assert.equal(res.status, 0, v);
      assert.equal(res.stdout, '', v);
      assert.equal(stub.calls.length, 0, `${v} sent ${JSON.stringify(stub.calls.map((r) => r.url))}`);
    });
  }
  const { T, P } = a2Tree('project:\n  rootId: root-a2\n  name: A2\n');
  await withStub({}, async (stub) => {
    const res = await spawnA2(P, T, { apiUrl: stub.url });
    assert.equal(res.status, 0, res.stderr);
    assert.ok(stub.calls.length >= 1);
    assert.equal(stub.calls[0].method, 'POST');
    assert.equal(stub.calls[0].url, '/api/v1/ops/config');
    assert.equal(stub.calls[0].body.ops[0].op, 'get');
    assert.equal(stub.calls[0].body.ops[0].rootId, 'root-a2');
    for (const c of stub.calls) assert.match(c.url, /^\/api\/v1\/ops\/(config|docs_read|docs_write)$/);
  });
});

test('4e15b651 S46: a project client.json URL with a path, query or fragment is ignored (zero requests); the bare origin syncs with the bearer token', async () => {
  const UUID = '3f9a1c2e-5b7d-4e8f-9a0b-1c2d3e4f5a6b';
  const suffixes = ['/_cluster/settings#', '/v2/keys/k?value=owned&x=', '/prefix', '#', '?x='];
  for (const suffix of suffixes) {
    const { T, P } = a2Tree(`project:\n  rootId: ${UUID}\n  name: A2\n`);
    await withStub({}, async (stub) => {
      writeFileSync(join(P, '.taskorchestrator', 'client.json'), JSON.stringify({ apiUrl: `${stub.url}${suffix}` }));
      const res = await spawnA2(P, T);
      assert.equal(res.status, 0, suffix);
      assert.equal(res.stdout, '', suffix);
      assert.equal(stub.calls.length, 0, `${suffix} sent ${JSON.stringify(stub.calls.map((r) => r.url))}`);
    });
  }
  const { T, P } = a2Tree(`project:\n  rootId: ${UUID}\n  name: A2\n`);
  await withStub({}, async (stub) => {
    writeFileSync(join(P, '.taskorchestrator', 'client.json'), JSON.stringify({ apiUrl: stub.url }));
    const res = await spawnA2(P, T);
    assert.equal(res.status, 0, res.stderr);
    assert.ok(stub.calls.length >= 1);
    for (const c of stub.calls) {
      assert.equal(c.auth, `Bearer ${A2_TOKEN}`);
      if (c.body.ops) for (const o of c.body.ops) assert.equal(o.rootId, UUID);
      else assert.equal(c.body.rootId, UUID);
    }
  });
});

for (const event of ['SessionStart', 'FileChanged']) {
  test(`S18 (AC9) ${event}: no API URL configured - exit 0 and silent (the same fixture does send once a URL resolves)`, async () => {
    const { T, P } = a2Tree(projectConfigText('root-nourl'));
    await withStub({}, async (stub) => {
      const withUrl = await spawnA2(P, T, { apiUrl: stub.url, event });
      assert.equal(withUrl.status, 0, withUrl.stderr);
      assert.ok(stub.calls.length >= 1, 'fixture precondition: the config is found and a URL makes the hook call the API');
    });
    const without = await spawnA2(P, T, { event });
    assert.equal(without.status, 0, without.stderr);
    assert.equal(without.stdout, '');
  });
}

// ---- S17 (AC8): the api-client shim is gone ----------------------------------------------------------------------------------

function walkFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === '.git') continue;
    const p = join(dir, entry);
    if (statSync(p).isDirectory()) walkFiles(p, out);
    else out.push(p);
  }
  return out;
}

test('S17 (AC8): no file under the plugin names hooks/api-client.mjs or imports ./api-client.mjs from hooks/; the four repointed hooks import ./lib/api-client.mjs', () => {
  const offenders = [];
  for (const file of walkFiles(PLUGIN_ROOT)) {
    const norm = file.split('\\').join('/');
    if (norm.includes('/hooks/tests/')) continue;
    if (!/\.(mjs|js|json|md|yaml|yml|txt)$/.test(file)) continue;
    const text = readFileSync(file, 'utf8');
    if (/hooks[\\/]+api-client\.mjs/.test(text)) offenders.push(`${norm}: names hooks/api-client.mjs`);
    if (norm.includes('/hooks/') && !norm.includes('/hooks/lib/') && /from\s*['"]\.\/api-client\.mjs['"]/.test(text)) offenders.push(`${norm}: imports ./api-client.mjs`);
  }
  assert.deepEqual(offenders, []);
  for (const f of ['enforce-actor-attribution.mjs', 'phase-guard.mjs', 'phase-guard-record.mjs', 'session-start.mjs']) {
    const text = readFileSync(join(HOOKS_SRC, f), 'utf8');
    assert.match(text, /from\s*['"]\.\/lib\/api-client\.mjs['"]/, `${f} imports ./lib/api-client.mjs`);
  }
});
