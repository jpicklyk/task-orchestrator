// Drives skill-enforcement.mjs as a subprocess with fixture JSON on stdin. 0bbdc4cc (P10): the hook
// no longer parses note schemas out of config.yaml. The config file is a BOOTSTRAP file only
// (project.rootId); schema facts come from the server over REST:
//   POST /api/v1/ops/config      {ops:[{op:'effective', rootId?}]}  once per session (cached in a marker)
//   POST /api/v1/ops/items_read  {select:'schema', itemId}     once per distinct item id per invocation
// An in-process node:http stub plays the server, so the hook is spawned ASYNC (spawnSync would block
// this process's event loop and the stub could never answer; a false green would result).
//
// Wire shape (arbitration, public evidence): config is an ops[] tool, so the request body is {ops:[{op:'effective',
// rootId?}]} and the reply is data.results[0] = {ok:true, ...effective result} (api-reference.md :74-81 batch envelope,
// :1408-1433 config tool; api-rest.md :892-905 body = the tool arguments). task-scope AC6 abbreviates this as
// callOp('config', {op:'effective'}).
//
// Oracle: 0bbdc4cc task-scope AC6 (facts, caching, item scoping, silence on failure), AC7 (inert
// line) and AC2 of the unchanged advisory rules (floor 200 / maxLength scaling, placeholders,
// bodyFromFile skip, once per (session, item, key), warning text). api-reference.md :477-479
// (schema view notes[]), :1433 (effective result) and :1001-1016 (notes_write upsert op fields).
//
// Isolation: every spawn gets its own TEMP/TMP/TMPDIR (so the per-session markers never touch the
// real os.tmpdir()), a pinned empty TASK_ORCHESTRATOR_HOME, and cwd = a fixture dir.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

const HOOK = fileURLToPath(new URL('../skill-enforcement.mjs', import.meta.url));
const TOOL = 'mcp__mcp-task-orchestrator__notes_write';
const ROOT_ID = '11111111-1111-1111-1111-111111111111';
const BOOTSTRAP = `project:\n  rootId: ${ROOT_ID}\n`;
const INERT_SE =
  'task-orchestrator: skill-enforcement.mjs is inert: no REST API URL resolves (set TASK_ORCHESTRATOR_API_URL or client.json apiUrl); in 4.0 schema facts and gate state come from the server.';

const U1 = 'aaaaaaaa-1111-2222-3333-444444444401';
const U2 = 'bbbbbbbb-1111-2222-3333-444444444402';
const U3 = 'cccccccc-1111-2222-3333-444444444403';

const SECURITY_NOTE = { key: 'security-assessment', role: 'review', required: true, description: 'Security review', skill: 'security-review' };
const QUICK_NOTE = { key: 'quick-review', role: 'review', required: true, description: 'Quick review', skill: 'review-quality', maxLength: 300 };
const UNION_NOTES = [SECURITY_NOTE, QUICK_NOTE];

const tracked = [];
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});
function fresh(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix));
  tracked.push(d);
  return d;
}

function writeConfig(dir, content = BOOTSTRAP) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

/** A fixture: config dir (bootstrap file), plus a shared per-test TEMP dir for markers. */
function fixture(config = BOOTSTRAP) {
  const dir = fresh('to-skill-enforcement-');
  if (config !== null) writeConfig(dir, config);
  return { dir, tmp: fresh('to-skill-tmp-') };
}

const effectiveData = (notes, extra = {}) => ({ schemas: [{ type: 'test-schema', notes }], traits: [], types: ['test-schema'], statusGraph: {}, ...extra });
const okEnvelope = (data) => ({ status: 200, body: { ok: true, data, meta: { reqId: 'req00001' } } });
const effectiveReply = (data) => okEnvelope({ results: [{ ok: true, ...data }], summary: { ok: 1, failed: 0 } });
const errEnvelope = (status, code, kind = 'permanent') => ({ status, body: { ok: false, error: { code, kind, message: `${code} fixture` }, meta: { reqId: 'req00002' } } });

/** Stub server. `effective` / `views` are reply descriptors ({status, body}) or functions
 * (requestCount) => reply for effective, and a map itemId -> reply for items_read (missing = 404). */
async function startStub({ effective = effectiveReply(effectiveData(UNION_NOTES)), views = {} } = {}) {
  const requests = [];
  const server = createServer((req, res) => {
    let raw = '';
    req.on('data', (d) => {
      raw += d;
    });
    req.on('end', () => {
      let body = {};
      try {
        body = JSON.parse(raw);
      } catch {
        /* leave empty */
      }
      requests.push({ method: req.method, url: req.url, body });
      let reply;
      if (req.method === 'POST' && req.url === '/api/v1/ops/config' && Array.isArray(body.ops) && body.ops[0]?.op === 'effective') {
        const n = requests.filter((r) => r.url === '/api/v1/ops/config').length;
        reply = typeof effective === 'function' ? effective(n) : effective;
      } else if (req.method === 'POST' && req.url === '/api/v1/ops/items_read' && body.select === 'schema') {
        reply = views[body.itemId] ?? errEnvelope(404, 'not_found');
      } else {
        reply = errEnvelope(404, 'not_found');
      }
      res.writeHead(reply.status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(reply.body));
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  server.requests = requests;
  server.origin = `http://127.0.0.1:${server.address().port}`;
  server.effectiveRequests = () => requests.filter((r) => r.url === '/api/v1/ops/config');
  server.schemaRequests = () => requests.filter((r) => r.url === '/api/v1/ops/items_read');
  return server;
}

function stopStub(server) {
  return new Promise((res) => server.close(res));
}

/** Runs the hook asynchronously. extra: {apiUrl, tmp, home, cwd, noAgentConfigDir}. */
function runHook(agentConfigDir, payload, extra = {}) {
  return new Promise((resolve, reject) => {
    const env = { ...process.env, TASK_ORCHESTRATOR_HOME: extra.home ?? fresh('to-skill-home-') };
    delete env.TASK_ORCHESTRATOR_MODE;
    delete env.TASK_ORCHESTRATOR_API_URL;
    delete env.TASK_ORCHESTRATOR_API_TOKEN;
    if (extra.apiUrl) env.TASK_ORCHESTRATOR_API_URL = extra.apiUrl;
    if (extra.tmp) {
      env.TEMP = extra.tmp;
      env.TMP = extra.tmp;
      env.TMPDIR = extra.tmp;
    }
    if (agentConfigDir) env.AGENT_CONFIG_DIR = agentConfigDir;
    else delete env.AGENT_CONFIG_DIR;
    const child = spawn(process.execPath, [HOOK], { env, cwd: extra.cwd ?? agentConfigDir });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.stderr.on('data', (d) => {
      stderr += d;
    });
    child.on('error', reject);
    child.on('close', (status) => resolve({ status, stdout, stderr }));
    child.stdin.end(typeof payload === 'string' ? payload : JSON.stringify(payload));
  });
}

function upsertPayload(sessionId, ops, extraInput = {}) {
  return {
    session_id: sessionId,
    tool_name: TOOL,
    tool_input: { ops: ops.map((o) => ({ op: 'upsert', ...o })), ...extraInput },
  };
}

const adv = (out) => JSON.parse(out.stdout).hookSpecificOutput.additionalContext;

/** Runs `body(server, fx)` against a started stub, always stopping it. */
async function withStub(stubOpts, config, body) {
  const server = await startStub(stubOpts);
  const fx = fixture(config);
  try {
    await body(server, fx);
  } finally {
    await stopStub(server);
  }
}

// ── existing advisory behaviour, now fed by the server facts (S5) ───────────────────────────────

test('S5: short body on a skill-bound key warns with advisory, non-blocking wording', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, upsertPayload(`test-warn-${randomUUID()}`, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.hookEventName, 'PreToolUse');
    const text = out.hookSpecificOutput.additionalContext;
    assert.ok(text.includes('SKILL SUGGESTED'), text);
    assert.ok(text.includes('Unknown skill'), text);
    assert.ok(text.includes('security-assessment'), text);
    assert.ok(text.includes('security-review'), text);
    assert.ok(!text.includes('Abort this call'), text);
  });
});

test('S5: the facts request is POST /api/v1/ops/config {ops:[{op:"effective", rootId}]} with rootId from the bootstrap file', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    await runHook(fx.dir, upsertPayload(`test-req-${randomUUID()}`, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' }]), { apiUrl: server.origin, tmp: fx.tmp });
    const eff = server.effectiveRequests();
    assert.equal(eff.length, 1);
    assert.equal(eff[0].method, 'POST');
    assert.deepEqual(eff[0].body, { ops: [{ op: 'effective', rootId: ROOT_ID }] });
  });
});

test('S5 probe: no project.rootId, or one isValidRootId rejects (separator, 65 chars), omits rootId from the effective request', async () => {
  for (const config of ['retrospective:\n  mode: dispatch\n', 'project:\n  rootId: a/b\n', `project:\n  rootId: ${'x'.repeat(65)}\n`, 'project:\n  name: demo\n']) {
    await withStub({}, config, async (server, fx) => {
      const res = await runHook(fx.dir, upsertPayload(`test-norid-${randomUUID()}`, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' }]), { apiUrl: server.origin, tmp: fx.tmp });
      assert.equal(res.status, 0);
      const eff = server.effectiveRequests();
      assert.equal(eff.length, 1, config);
      assert.deepEqual(eff[0].body, { ops: [{ op: 'effective' }] }, config);
      assert.ok(adv(res).includes('SKILL SUGGESTED'), config);
    });
  }
});

test('S5: one effective request per session -- a second call in the same session reuses the cached facts', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const sessionId = `test-cache-${randomUUID()}`;
    const first = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.ok(adv(first).includes('SKILL SUGGESTED'));
    // different item + key so the once-per-(session,item,key) marker does not hide the second advisory
    const second = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-2', key: 'quick-review', role: 'review', body: 'x'.repeat(20) }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.ok(adv(second).includes('quick-review'), 'the cached facts must still drive the second advisory');
    assert.equal(server.effectiveRequests().length, 1);
  });
});

test('S5: a different session fetches the facts again', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    for (const s of ['test-sess-a', 'test-sess-b']) {
      const res = await runHook(fx.dir, upsertPayload(`${s}-${randomUUID()}`, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' }]), { apiUrl: server.origin, tmp: fx.tmp });
      assert.ok(adv(res).includes('SKILL SUGGESTED'), s);
    }
    assert.equal(server.effectiveRequests().length, 2);
  });
});

test('same session_id + itemId + key again is silent (dedup)', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const sessionId = `test-dedup-${randomUUID()}`;
    const first = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(first.status, 0);
    assert.ok(first.stdout.length > 0);
    const second = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'still too short' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(second.status, 0);
    assert.equal(second.stdout.trim(), '');
  });
});

test('same session_id, different itemId warns again (dedup is per item+key)', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const sessionId = `test-diffitem-${randomUUID()}`;
    const first = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.ok(first.stdout.length > 0);
    const second = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-2', key: 'security-assessment', role: 'review', body: 'also too short' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(second.status, 0);
    assert.ok(adv(second).includes('SKILL SUGGESTED'));
  });
});

test('body >= 200 chars is silent and records no marker entry (a later short body still warns)', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const sessionId = `test-longbody-${randomUUID()}`;
    const first = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'x'.repeat(200) }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(first.status, 0);
    assert.equal(first.stdout.trim(), '');
    // If the long-body call had (incorrectly) recorded a marker entry, this would stay silent.
    const second = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.ok(adv(second).includes('SKILL SUGGESTED'));
  });
});

test('maxLength: 300 key (from the server facts) scales the floor to min(200, 75) = 75', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const sessionId = `test-maxlen-${randomUUID()}`;
    // 100 chars >= floor(75) -> silent, no marker recorded.
    const first = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-1', key: 'quick-review', role: 'review', body: 'x'.repeat(100) }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(first.status, 0);
    assert.equal(first.stdout.trim(), '');
    // 50 chars < floor(75) -> warns.
    const second = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-1', key: 'quick-review', role: 'review', body: 'x'.repeat(50) }]), { apiUrl: server.origin, tmp: fx.tmp });
    const text = adv(second);
    assert.ok(text.includes('SKILL SUGGESTED'));
    assert.ok(text.includes('quick-review'));
  });
});

test('placeholder body under the floor warns', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, upsertPayload(`test-placeholder-${randomUUID()}`, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(res.status, 0);
    assert.ok(adv(res).includes('SKILL SUGGESTED'));
  });
});

test('bodyFromFile set skips the length/placeholder heuristic (no warning)', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, upsertPayload(`test-bodyfromfile-${randomUUID()}`, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', bodyFromFile: 'notes/security-assessment.md' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '');
  });
});

test('bodyFromFile set does not suppress a sibling short literal body on another op', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, upsertPayload(`test-bodyfromfile-sibling-${randomUUID()}`, [
      { itemId: 'item-1', key: 'security-assessment', role: 'review', bodyFromFile: 'notes/security-assessment.md' },
      { itemId: 'item-1', key: 'quick-review', role: 'review', body: 'too short' },
    ]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(res.status, 0);
    const text = adv(res);
    assert.ok(text.includes('SKILL SUGGESTED'), text);
    assert.ok(text.includes('quick-review'), text);
    assert.ok(!text.includes('"security-assessment"'), text);
  });
});

test('a key the facts do not know (no skill bound) is silent even with a short body', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, upsertPayload(`test-unknownkey-${randomUUID()}`, [{ itemId: 'item-1', key: 'some-other-note', role: 'work', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '');
  });
});

test('a note without a skill pointer in the facts is silent even with a short body', async () => {
  const noSkill = { key: 'plain-note', role: 'work', required: false, description: 'No skill bound' };
  await withStub({ effective: effectiveReply(effectiveData([noSkill])) }, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, upsertPayload(`test-noskill-${randomUUID()}`, [{ itemId: 'item-1', key: 'plain-note', role: 'work', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '');
  });
});

test('facts: a later schema with the same key wins over an earlier one (later type wins on a duplicate key)', async () => {
  const data = {
    schemas: [
      { type: 'first-type', notes: [{ key: 'shared-key', role: 'work', required: true, description: 'd', skill: 'first-skill' }] },
      { type: 'second-type', notes: [{ key: 'shared-key', role: 'work', required: true, description: 'd', skill: 'second-skill' }] },
    ],
    traits: [], types: ['first-type', 'second-type'], statusGraph: {},
  };
  await withStub({ effective: effectiveReply(data) }, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, upsertPayload(`test-duptype-${randomUUID()}`, [{ itemId: 'item-1', key: 'shared-key', role: 'work', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp });
    const text = adv(res);
    assert.ok(text.includes('second-skill'), text);
    assert.ok(!text.includes('first-skill'), text);
  });
});

test('facts: notes declared only in defaultSchema.notes[] are part of the union', async () => {
  const data = effectiveData([SECURITY_NOTE], {
    defaultSchema: { notes: [{ key: 'default-only-note', role: 'work', required: true, description: 'd', skill: 'default-skill' }] },
  });
  await withStub({ effective: effectiveReply(data) }, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, upsertPayload(`test-defaultschema-${randomUUID()}`, [{ itemId: 'item-1', key: 'default-only-note', role: 'work', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.ok(adv(res).includes('default-skill'));
  });
});

// ── ops[] input shape (AC6) ────────────────────────────────────────────────────────────────────────

test('only op:"upsert" elements are acted on: delete / get / other ops in ops[] are ignored', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, {
      session_id: `test-notupsert-${randomUUID()}`,
      tool_name: TOOL,
      tool_input: { ops: [
        { op: 'delete', itemId: 'item-1', key: 'security-assessment', body: 'n/a' },
        { op: 'get', itemId: 'item-1', key: 'security-assessment', body: 'n/a' },
      ] },
    }, { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '');
  });
});

test('an upsert next to a delete in one ops[] is the only element that warns', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, {
      session_id: `test-mixedops-${randomUUID()}`,
      tool_name: TOOL,
      tool_input: { ops: [
        { op: 'delete', itemId: 'item-1', key: 'quick-review' },
        { op: 'upsert', itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' },
      ] },
    }, { apiUrl: server.origin, tmp: fx.tmp });
    const text = adv(res);
    assert.ok(text.includes('security-assessment'), text);
    assert.ok(!text.includes('quick-review'), text);
  });
});

test('the 3.x shape (operation:"upsert" + notes[]) is not read: silent', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, {
      session_id: `test-legacyshape-${randomUUID()}`,
      tool_name: 'mcp__mcp-task-orchestrator__manage_notes',
      tool_input: { operation: 'upsert', notes: [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' }] },
    }, { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '');
  });
});

test('fail-open: missing ops array, non-array ops and ops without itemId/key are silent', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    for (const toolInput of [{}, { ops: 'nope' }, { ops: [] }, { ops: [{ op: 'upsert', body: 'n/a' }] }, { ops: [{ op: 'upsert', itemId: 'item-1', body: 'n/a' }] }]) {
      const res = await runHook(fx.dir, { session_id: `test-noops-${randomUUID()}`, tool_name: TOOL, tool_input: toolInput }, { apiUrl: server.origin, tmp: fx.tmp });
      assert.equal(res.status, 0, JSON.stringify(toolInput));
      assert.equal(res.stdout.trim(), '', JSON.stringify(toolInput));
    }
  });
});

test('fail-open: unparseable stdin is silent', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, '{not valid json', { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '');
  });
});

// ── item-scoped schema view (S6) ─────────────────────────────────────────────────────────────────

test('S6: an ops element with a UUID itemId reads that item schema view: POST items_read {select:"schema", itemId}', async () => {
  const views = { [U1]: okEnvelope({ type: 'test-schema', configFingerprint: 'fp', configSource: 'per-root', notes: UNION_NOTES, features: [] }) };
  await withStub({ views }, BOOTSTRAP, async (server, fx) => {
    const res = await runHook(fx.dir, upsertPayload(`test-s6req-${randomUUID()}`, [{ itemId: U1, key: 'security-assessment', role: 'review', body: 'too short' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.ok(adv(res).includes('SKILL SUGGESTED'));
    const reqs = server.schemaRequests();
    assert.equal(reqs.length, 1);
    assert.equal(reqs[0].method, 'POST');
    assert.deepEqual(reqs[0].body, { select: 'schema', itemId: U1 });
  });
});

test('S6: the item view lacking the skill on that key silences the advisory the global union would give', async () => {
  const view = { type: 'item-type', configFingerprint: 'fp', configSource: 'per-root', notes: [{ key: 'security-assessment', role: 'review', required: true, description: 'no skill here' }], features: [] };
  await withStub({ views: { [U1]: okEnvelope(view) } }, BOOTSTRAP, async (server, fx) => {
    const scoped = await runHook(fx.dir, upsertPayload(`test-s6a-${randomUUID()}`, [{ itemId: U1, key: 'security-assessment', role: 'review', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(scoped.status, 0);
    assert.equal(scoped.stdout.trim(), '');
    // Control: an id with no schema view (404 not_found) falls back to the union, which warns.
    const control = await runHook(fx.dir, upsertPayload(`test-s6b-${randomUUID()}`, [{ itemId: U3, key: 'security-assessment', role: 'review', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.ok(adv(control).includes('security-review'));
  });
});

test('S6: the item view skill and maxLength replace the union values for that op', async () => {
  const view = { type: 'item-type', configFingerprint: 'fp', configSource: 'per-root', notes: [{ key: 'security-assessment', role: 'review', required: true, description: 'd', skill: 'item-specific-skill', maxLength: 300 }], features: [] };
  await withStub({ views: { [U1]: okEnvelope(view) } }, BOOTSTRAP, async (server, fx) => {
    // 100 chars: the union (no maxLength, floor 200) would warn; the item view maxLength 300 (floor 75) does not
    const long = await runHook(fx.dir, upsertPayload(`test-s6c-${randomUUID()}`, [{ itemId: U1, key: 'security-assessment', role: 'review', body: 'x'.repeat(100) }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(long.stdout.trim(), '');
    // 50 chars warns, and it names the item-specific skill, not the union skill
    const short = await runHook(fx.dir, upsertPayload(`test-s6d-${randomUUID()}`, [{ itemId: U1, key: 'security-assessment', role: 'review', body: 'x'.repeat(50) }]), { apiUrl: server.origin, tmp: fx.tmp });
    const text = adv(short);
    assert.ok(text.includes('item-specific-skill'), text);
    assert.ok(!text.includes('security-review'), text);
  });
});

test('S6: a schemaFree item view, and a non-ok view reply, fall back to the cached union', async () => {
  const views = {
    [U1]: okEnvelope({ schemaFree: true }),
    [U2]: errEnvelope(403, 'forbidden'),
    [U3]: errEnvelope(503, 'unavailable', 'shedding'),
  };
  await withStub({ views }, BOOTSTRAP, async (server, fx) => {
    for (const id of [U1, U2, U3]) {
      const res = await runHook(fx.dir, upsertPayload(`test-s6free-${randomUUID()}`, [{ itemId: id, key: 'security-assessment', role: 'review', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp });
      assert.ok(adv(res).includes('security-review'), id);
    }
  });
});

test('S6: the view is read once per distinct id per invocation (two ops on one item, two ids)', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    await runHook(fx.dir, upsertPayload(`test-s6once-${randomUUID()}`, [
      { itemId: U1, key: 'security-assessment', role: 'review', body: 'n/a' },
      { itemId: U1, key: 'quick-review', role: 'review', body: 'n/a' },
      { itemId: U2, key: 'security-assessment', role: 'review', body: 'n/a' },
    ]), { apiUrl: server.origin, tmp: fx.tmp });
    const ids = server.schemaRequests().map((r) => r.body.itemId).sort();
    assert.deepEqual(ids, [U1, U2].sort());
  });
});

test('S6: a hex-prefix itemId (8-32 hex) is read; a non-id ("item-1") or a too-short / too-long hex is not', async () => {
  await withStub({}, BOOTSTRAP, async (server, fx) => {
    const ok8 = 'abcdef12';
    const ok32 = 'abcdef12abcdef12abcdef12abcdef12';
    const bad = ['item-1', 'abcdef1', `${ok32}0`, 'ghijklmn'];
    await runHook(fx.dir, upsertPayload(`test-s6hex-${randomUUID()}`, [ok8, ok32, ...bad].map((itemId) => ({ itemId, key: 'security-assessment', role: 'review', body: 'n/a' }))), { apiUrl: server.origin, tmp: fx.tmp });
    const ids = server.schemaRequests().map((r) => r.body.itemId).sort();
    assert.deepEqual(ids, [ok8, ok32].sort());
  });
});

// ── failure of the facts: silent, no marker (AC6) ────────────────────────────────────────────────

test('S10: a non-ok effective result (internal, unauthenticated, unavailable, 3.x body) is silent and writes no marker', async () => {
  const failures = [
    errEnvelope(500, 'internal'),
    errEnvelope(401, 'unauthenticated'),
    errEnvelope(503, 'unavailable', 'shedding'),
    { status: 200, body: { success: true, message: 'ok', data: {} } },
  ];
  for (const failure of failures) {
    // first effective request fails, every later one succeeds: a marker written after the failure would
    // make the second call skip the fetch (stay silent); no marker means the second call fetches and warns.
    await withStub({ effective: (n) => (n === 1 ? failure : effectiveReply(effectiveData(UNION_NOTES))) }, BOOTSTRAP, async (server, fx) => {
      const sessionId = `test-s10-${randomUUID()}`;
      const first = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp });
      assert.equal(first.status, 0);
      assert.equal(first.stdout.trim(), '', JSON.stringify(failure.body));
      const second = await runHook(fx.dir, upsertPayload(sessionId, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp });
      assert.ok(adv(second).includes('SKILL SUGGESTED'), JSON.stringify(failure.body));
      assert.equal(server.effectiveRequests().length, 2);
    });
  }
});

test('S10: an unreachable server is silent and exits 0', async () => {
  const fx = fixture();
  const res = await runHook(fx.dir, upsertPayload(`test-s10-down-${randomUUID()}`, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' }]), { apiUrl: 'http://127.0.0.1:1', tmp: fx.tmp });
  assert.equal(res.status, 0);
  assert.equal(res.stdout.trim(), '');
});

// ── config discovery + inert line (S12, AC7) ─────────────────────────────────────────────────────

test('fail-open: no discoverable config is silent', async () => {
  await withStub({}, null, async (server, fx) => {
    const res = await runHook(fx.dir, upsertPayload(`test-noconfig-${randomUUID()}`, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '');
  });
});

test('user scope: bootstrap file only in the pinned home, empty cwd -> advisory still fires', async () => {
  await withStub({}, null, async (server, fx) => {
    const home = fresh('to-skill-userhome-');
    const cwd = fresh('to-skill-usercwd-');
    writeConfig(home, BOOTSTRAP);
    const res = await runHook(null, upsertPayload(`test-userscope-${randomUUID()}`, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' }]), { apiUrl: server.origin, tmp: fx.tmp, home, cwd });
    assert.equal(res.status, 0);
    assert.match(adv(res), /security-review/);
  });
});

test('S12: located config + no API URL -> exactly the inert systemMessage once per session, then silent; exit 0', async () => {
  const fx = fixture();
  const sessionId = `test-inert-${randomUUID()}`;
  const payload = () => upsertPayload(sessionId, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' }]);
  const first = await runHook(fx.dir, payload(), { tmp: fx.tmp });
  assert.equal(first.status, 0);
  assert.deepEqual(JSON.parse(first.stdout), { systemMessage: INERT_SE });
  const second = await runHook(fx.dir, payload(), { tmp: fx.tmp });
  assert.equal(second.status, 0);
  // AC7 says "then {}"; the hook's other silent paths print nothing: both mean no output was emitted
  assert.ok(['', '{}'].includes(second.stdout.trim()), second.stdout);
  // another session gets its own inert line
  const other = await runHook(fx.dir, upsertPayload(`test-inert-other-${randomUUID()}`, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' }]), { tmp: fx.tmp });
  assert.deepEqual(JSON.parse(other.stdout), { systemMessage: INERT_SE });
});

test('S12: no config located + no API URL is silent (no inert line)', async () => {
  const fx = fixture(null);
  const res = await runHook(fx.dir, upsertPayload(`test-inert-noconfig-${randomUUID()}`, [{ itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' }]), { tmp: fx.tmp });
  assert.equal(res.status, 0);
  assert.equal(res.stdout.trim(), '');
});

test('S12: the inert line is not emitted for a non-upsert call even with a located config and no API URL' , async () => {
  const fx = fixture();
  const res = await runHook(fx.dir, { session_id: `test-inert-nonupsert-${randomUUID()}`, tool_name: TOOL, tool_input: { ops: [{ op: 'delete', itemId: 'item-1', key: 'security-assessment' }] } }, { tmp: fx.tmp });
  assert.equal(res.status, 0);
  assert.ok(['', '{}'].includes(res.stdout.trim()), res.stdout);
  assert.ok(!res.stdout.includes('inert'), res.stdout);
});
