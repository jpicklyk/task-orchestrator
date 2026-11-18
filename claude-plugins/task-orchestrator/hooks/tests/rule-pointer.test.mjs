// A note's skill pointer that names a served rule key is presented as a rule to fetch; any other
// pointer stays a skill to invoke (item 25dee868, F1-A). Drives phase-guard.mjs (PG) and
// skill-enforcement.mjs (SE) as spawned node processes against an in-process http stub.
//
// Oracles (frozen in the item test-plan, written before the implementation existed): task-scope
// AC1-AC4 and its pinned strings; the `config` op `rules` reply shape is
// {rootId, rules:[{key, rulesVersion, updatedAt}]} (ConfigRulesOp.kt:48-49, :106-123, public tool
// schema). "Today's text" is never a literal copied from the hook: it is the output of the same
// hook against a stub whose rules route answers 404 (a read that fails leaves today's output, AC3).
//
// MUST use async `spawn`: spawnSync would block this process's event loop and the stub, which runs
// in this same process, could never answer (a false green).
//
// Isolation: every spawn gets its own TEMP/TMP/TMPDIR (session markers never touch the real
// os.tmpdir()), a pinned empty TASK_ORCHESTRATOR_HOME and TASK_ORCHESTRATOR_CEILING, and a fixture cwd.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { phaseGuardMarkerPath, writePhaseGuardMarker } from '../phase-guard-record.mjs';
import { sessionMarkerPath, writeMarker } from '../lib/markers.mjs';

const PG_HOOK = fileURLToPath(new URL('../phase-guard.mjs', import.meta.url));
const SE_HOOK = fileURLToPath(new URL('../skill-enforcement.mjs', import.meta.url));
const PLUGIN_DIR = fileURLToPath(new URL('../../', import.meta.url));

const R = '11111111-1111-1111-1111-111111111111';
const R2 = '22222222-2222-2222-2222-222222222222';
const BOOTSTRAP = `project:\n  rootId: ${R}\n`;
const NOTES_WRITE = 'mcp__mcp-task-orchestrator__notes_write';
const IMPL = 'task-orchestrator:implementer';
const ITEM_A = '4a468f6c-9434-4cbf-97e8-b6c546c1b112';
const ITEM_B = '5b579f7d-a545-4dae-a869-c7d657d205c3';
const AGENT = 'agent-1';

const tracked = [];
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});
function fresh(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix));
  tracked.push(d);
  return d;
}

/** A fixture: `dir` (cwd, holds the bootstrap config unless null) and `tmp` (TEMP for markers). */
function fixture(config = BOOTSTRAP) {
  const dir = fresh('to-rule-pointer-');
  if (config !== null) {
    mkdirSync(join(dir, '.taskorchestrator'), { recursive: true });
    writeFileSync(join(dir, '.taskorchestrator', 'config.yaml'), config, 'utf-8');
  }
  return { dir, tmp: fresh('to-rule-pointer-tmp-') };
}

// ── stub ─────────────────────────────────────────────────────────────────────────────────────────

const errEnvelope = (status, code, kind = 'permanent') => ({ status, body: { ok: false, error: { code, kind, message: `${code} fixture` }, meta: { reqId: 'req00002' } } });
const okEnvelope = (data) => ({ status: 200, body: { ok: true, data, meta: { reqId: 'req00001' } } });

/** A `config` op `rules` reply listing `keys` (each entry {key, rulesVersion, updatedAt}). */
const rulesOk = (rootId, keys) =>
  okEnvelope({ results: [{ ok: true, rootId, rules: keys.map((key) => ({ key, rulesVersion: 'v1', updatedAt: '2026-01-01T00:00:00Z' })) }], summary: { ok: 1, failed: 0 } });

/** Raw `rules` list entries, sent verbatim (for entries without a string key). */
const rulesRaw = (rootId, entries) => okEnvelope({ results: [{ ok: true, rootId, rules: entries }], summary: { ok: 1, failed: 0 } });

const effectiveReply = (notes) => okEnvelope({ results: [{ ok: true, schemas: [{ type: 'test-schema', notes }], traits: [], types: ['test-schema'], statusGraph: {} }], summary: { ok: 1, failed: 0 } });

/** The 4.0 items_read context reply for phase-guard. */
function gateReply({ itemId, role = 'review', missing = ['review-checklist'], skillPointer, missingBySeat, seats }) {
  const gateStatus = { canAdvance: false, missing, violations: [] };
  if (missingBySeat) gateStatus.missingBySeat = missingBySeat;
  const data = { item: { id: itemId, title: 'Widget frobnicator', role }, gateStatus };
  if (seats) data.seats = seats;
  if (skillPointer) data.skillPointer = skillPointer;
  return okEnvelope(data);
}

/**
 * `gates`: itemId -> reply. `rules`: undefined (404), a reply {status, body}, or a function
 * (n, req, res) that answers itself (a function that never answers makes the route hang).
 * `notes`: the SE effective notes. Every request is recorded with its parsed body.
 */
async function startStub({ gates = {}, rules, notes = [] } = {}) {
  const requests = [];
  const sockets = new Set();
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
      const op = body?.ops?.[0]?.op;
      let reply;
      if (req.method === 'POST' && req.url === '/api/v1/ops/config' && op === 'rules') {
        const n = requests.filter((r) => r.url === '/api/v1/ops/config' && r.body?.ops?.[0]?.op === 'rules').length;
        if (typeof rules === 'function') {
          rules(n, req, res);
          return;
        }
        reply = rules ?? errEnvelope(404, 'not_found');
      } else if (req.method === 'POST' && req.url === '/api/v1/ops/config' && op === 'effective') {
        reply = effectiveReply(notes);
      } else if (req.method === 'POST' && req.url === '/api/v1/ops/items_read' && body.select === 'context') {
        reply = gates[body.itemId] ?? errEnvelope(404, 'not_found');
      } else {
        reply = errEnvelope(404, 'not_found');
      }
      res.writeHead(reply.status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(reply.body));
    });
  });
  server.on('connection', (s) => {
    sockets.add(s);
    s.on('close', () => sockets.delete(s));
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  server.requests = requests;
  server.origin = `http://127.0.0.1:${server.address().port}`;
  server.rulesRequests = () => requests.filter((r) => r.url === '/api/v1/ops/config' && r.body?.ops?.[0]?.op === 'rules');
  server.stop = () => {
    for (const s of sockets) s.destroy();
    return new Promise((r) => server.close(r));
  };
  return server;
}

async function withStub(opts, body) {
  const server = await startStub(opts);
  try {
    return await body(server);
  } finally {
    await server.stop();
  }
}

// ── hook runners ─────────────────────────────────────────────────────────────────────────────────

function runHook(hook, payload, { dir, tmp, apiUrl, agentConfigDir = false }) {
  return new Promise((resolve, reject) => {
    const env = { ...process.env, TEMP: tmp, TMP: tmp, TMPDIR: tmp, TASK_ORCHESTRATOR_HOME: join(dir, 'to-home-empty'), TASK_ORCHESTRATOR_CEILING: dir };
    delete env.AGENT_CONFIG_DIR;
    delete env.TASK_ORCHESTRATOR_API_URL;
    delete env.TASK_ORCHESTRATOR_API_TOKEN;
    delete env.TASK_ORCHESTRATOR_MODE;
    if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
    if (agentConfigDir) env.AGENT_CONFIG_DIR = dir;
    const started = Date.now();
    const child = spawn(process.execPath, [hook], { env, cwd: dir });
    let stdout = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.on('error', reject);
    child.on('close', (status) => resolve({ status, stdout, ms: Date.now() - started }));
    child.stdin.end(JSON.stringify(payload));
  });
}

function withRedirectedTmp(tmp, fn) {
  const saved = { TEMP: process.env.TEMP, TMP: process.env.TMP, TMPDIR: process.env.TMPDIR };
  process.env.TEMP = tmp;
  process.env.TMP = tmp;
  process.env.TMPDIR = tmp;
  try {
    return fn();
  } finally {
    for (const [k, v] of Object.entries(saved)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  }
}

/** Seeds the phase-guard marker, runs PG once. Returns {res, out, reason}. */
async function pgRun(fx, server, { sessionId, items, agentType }) {
  withRedirectedTmp(fx.tmp, () => writePhaseGuardMarker(phaseGuardMarkerPath(sessionId, AGENT), { items, blocks: 0 }));
  const payload = { session_id: sessionId, agent_id: AGENT };
  if (agentType) payload.agent_type = agentType;
  const res = await runHook(PG_HOOK, payload, { dir: fx.dir, tmp: fx.tmp, apiUrl: server.origin, agentConfigDir: true });
  const out = JSON.parse(res.stdout);
  return { res, out, reason: out.reason };
}

const pgGates = (over, ids = [ITEM_A]) => Object.fromEntries(ids.map((id) => [id, gateReply({ ...over, itemId: id })]));

/** One PG run on a fresh fixture and stub. */
async function pgOnce({ rules, gate = {}, config = BOOTSTRAP, items = [ITEM_A], agentType, sessionId = `pg-${randomUUID()}` }) {
  const fx = fixture(config);
  return withStub({ gates: pgGates(gate, items), rules }, async (server) => {
    const r = await pgRun(fx, server, { sessionId, items, agentType });
    return { ...r, rulesReqs: server.rulesRequests(), all: server.requests.map((q) => `${q.url} ${JSON.stringify(q.body)}`) };
  });
}

const seNote = (key, skills, extra = {}) => ({ key, role: 'review', required: true, description: 'd', skill: skills[0], skills, ...extra });
const upsertPayload = (sessionId, ops) => ({ session_id: sessionId, tool_name: NOTES_WRITE, tool_input: { ops: ops.map((o) => ({ op: 'upsert', role: 'review', ...o })) } });
const adv = (res) => JSON.parse(res.stdout).hookSpecificOutput.additionalContext;

async function seRun(fx, server, sessionId, ops) {
  const res = await runHook(SE_HOOK, upsertPayload(sessionId, ops), { dir: fx.dir, tmp: fx.tmp, apiUrl: server.origin, agentConfigDir: true });
  return res;
}

/** One SE run on a fresh fixture and stub; returns the advisory text ('' when silent). */
async function seOnce({ rules, notes, key = 'note-a', body = 'too short', config = BOOTSTRAP, sessionId = `se-${randomUUID()}` }) {
  const fx = fixture(config);
  return withStub({ rules, notes }, async (server) => {
    const res = await seRun(fx, server, sessionId, [{ itemId: 'item-1', key, body }]);
    return { res, text: res.stdout.trim() === '' ? '' : adv(res), rulesReqs: server.rulesRequests() };
  });
}

const XRULE_NOTE = seNote('note-a', ['x-rule']);
const XRULE_NOTE_B = seNote('note-b', ['x-rule']);
const NOTES_X = [XRULE_NOTE, XRULE_NOTE_B];

// ── S1 / S2: phase-guard ─────────────────────────────────────────────────────────────────────────

test('S1: PG, pointer in the served rules -> the reason names the rule fetch, never Invoke the / Skill tool', async () => {
  const r = await pgOnce({ rules: rulesOk(R, ['x-rule']), gate: { skillPointer: 'x-rule' } });
  assert.equal(r.res.status, 0);
  assert.equal(r.out.decision, 'block');
  assert.ok(r.reason.includes('rule "x-rule"'), `${r.reason} | ${r.all.join(' ; ')}`);
  assert.ok(r.reason.includes('op "rules"'), r.reason);
  assert.ok(r.reason.includes(R), `names the rootId: ${r.reason}`);
  assert.ok(!r.reason.includes('Invoke the'), r.reason);
  assert.ok(!r.reason.includes('Skill tool'), r.reason);
  assert.equal(r.rulesReqs.length, 1);
});

test('S2: PG, pointer not in the served rules -> today\'s skill hint, byte-identical to a run with no rules route', async () => {
  const served = await pgOnce({ rules: rulesOk(R, ['x-rule']), gate: { skillPointer: 'y-skill' } });
  const baseline = await pgOnce({ rules: undefined, gate: { skillPointer: 'y-skill' } });
  assert.ok(served.reason.includes(' Invoke the y-skill skill for guidance.'), served.reason);
  assert.equal(served.reason, baseline.reason);
  assert.equal(served.rulesReqs.length, 1, 'the read is made when a hint would name a pointer');
});

// ── S3 / S4 / S5: skill-enforcement ──────────────────────────────────────────────────────────────

test('S3: SE, skill in the served rules -> RULE SUGGESTED naming the rule fetch, never the skill forms', async () => {
  const r = await seOnce({ rules: rulesOk(R, ['x-rule']), notes: [XRULE_NOTE] });
  assert.equal(r.res.status, 0);
  assert.ok(r.text.includes('RULE SUGGESTED'), r.text);
  assert.ok(r.text.includes('rule "x-rule"'), r.text);
  assert.ok(r.text.includes('op "rules"'), r.text);
  assert.ok(r.text.includes('note-a'), r.text);
  assert.ok(!r.text.includes('SKILL SUGGESTED'), r.text);
  assert.ok(!r.text.includes('Skill tool'), r.text);
  assert.ok(!r.text.includes('skill="x-rule"'), r.text);
  assert.ok(!r.text.includes('/x-rule'), r.text);
});

test('S4: SE, mixed note [x-rule, y-skill] -> rule paragraph before skill paragraph; the rule is never rendered as a skill', async () => {
  for (const skills of [['x-rule', 'y-skill'], ['y-skill', 'x-rule']]) {
    const r = await seOnce({ rules: rulesOk(R, ['x-rule']), notes: [seNote('note-a', skills)] });
    const ruleAt = r.text.indexOf('RULE SUGGESTED');
    const skillAt = r.text.indexOf('SKILL SUGGESTED');
    assert.ok(ruleAt >= 0 && skillAt >= 0, r.text);
    assert.ok(ruleAt < skillAt, `rule paragraph first (${skills}): ${r.text}`);
    assert.ok(r.text.includes('skill="y-skill"'), r.text);
    assert.ok(!r.text.includes('skill="x-rule"'), r.text);
    assert.ok(!r.text.includes('/x-rule'), r.text);
    assert.ok(!r.text.includes('Skill tool') || r.text.indexOf('Skill tool') > skillAt, `no skill wording inside the rule paragraph: ${r.text}`);
  }
});

test('S5: SE, skill not in the served rules -> advisory equal to the run with no rules route', async () => {
  const note = seNote('note-a', ['y-skill']);
  const served = await seOnce({ rules: rulesOk(R, ['x-rule']), notes: [note] });
  const baseline = await seOnce({ rules: undefined, notes: [note] });
  assert.ok(served.text.includes('SKILL SUGGESTED'), served.text);
  assert.ok(!served.text.includes('RULE SUGGESTED'), served.text);
  assert.equal(served.text, baseline.text);
  assert.equal(served.rulesReqs.length, 1);
});

// ── S6 - S9: one read per session, shared across both hooks ──────────────────────────────────────

test('S6: two PG runs in one session, both hinting -> one rules request with body {ops:[{op:"rules", rootId}]}', async () => {
  const fx = fixture();
  await withStub({ gates: pgGates({ skillPointer: 'x-rule' }), rules: rulesOk(R, ['x-rule']) }, async (server) => {
    const sessionId = `s6-${randomUUID()}`;
    const first = await pgRun(fx, server, { sessionId, items: [ITEM_A] });
    const second = await pgRun(fx, server, { sessionId, items: [ITEM_A] });
    assert.ok(first.reason.includes('op "rules"'), first.reason);
    assert.ok(second.reason.includes('op "rules"'), `the cached keys drive the second run: ${second.reason}`);
    const reqs = server.rulesRequests();
    assert.equal(reqs.length, 1);
    assert.equal(reqs[0].method, 'POST');
    assert.deepEqual(reqs[0].body, { ops: [{ op: 'rules', rootId: R }] });
  });
});

test('S7: PG then SE in one session and TEMP -> one rules request in total, and SE is served by the PG-cached keys', async () => {
  const fx = fixture();
  await withStub({ gates: pgGates({ skillPointer: 'x-rule' }), rules: rulesOk(R, ['x-rule']), notes: [XRULE_NOTE] }, async (server) => {
    const sessionId = `s7-${randomUUID()}`;
    const pg = await pgRun(fx, server, { sessionId, items: [ITEM_A] });
    assert.ok(pg.reason.includes('op "rules"'), pg.reason);
    const se = await seRun(fx, server, sessionId, [{ itemId: 'item-1', key: 'note-a', body: 'too short' }]);
    assert.ok(adv(se).includes('RULE SUGGESTED'), adv(se));
    assert.equal(server.rulesRequests().length, 1);
  });
});

test('S7b: SE then PG in one session -> one rules request in total', async () => {
  const fx = fixture();
  await withStub({ gates: pgGates({ skillPointer: 'x-rule' }), rules: rulesOk(R, ['x-rule']), notes: [XRULE_NOTE] }, async (server) => {
    const sessionId = `s7b-${randomUUID()}`;
    const se = await seRun(fx, server, sessionId, [{ itemId: 'item-1', key: 'note-a', body: 'too short' }]);
    assert.ok(adv(se).includes('RULE SUGGESTED'), adv(se));
    const pg = await pgRun(fx, server, { sessionId, items: [ITEM_A] });
    assert.ok(pg.reason.includes('op "rules"'), pg.reason);
    assert.equal(server.rulesRequests().length, 1);
  });
});

test('S8: a second session reads the rules again', async () => {
  const fx = fixture();
  await withStub({ gates: pgGates({ skillPointer: 'x-rule' }), rules: rulesOk(R, ['x-rule']) }, async (server) => {
    await pgRun(fx, server, { sessionId: `s8a-${randomUUID()}`, items: [ITEM_A] });
    await pgRun(fx, server, { sessionId: `s8b-${randomUUID()}`, items: [ITEM_A] });
    assert.equal(server.rulesRequests().length, 2);
  });
});

test('S9: an ok empty rules list gives today\'s text and is cached (the next run in the session makes no request)', async () => {
  const baseline = await pgOnce({ rules: undefined, gate: { skillPointer: 'x-rule' } });
  const fx = fixture();
  await withStub({ gates: pgGates({ skillPointer: 'x-rule' }), rules: rulesOk(R, []) }, async (server) => {
    const sessionId = `s9-${randomUUID()}`;
    const first = await pgRun(fx, server, { sessionId, items: [ITEM_A] });
    assert.equal(first.reason, baseline.reason);
    assert.equal(server.rulesRequests().length, 1);
    const second = await pgRun(fx, server, { sessionId, items: [ITEM_A] });
    assert.equal(second.reason, baseline.reason);
    assert.equal(server.rulesRequests().length, 1, 'an empty ok list is cached');
  });
  const seBaseline = await seOnce({ rules: undefined, notes: NOTES_X });
  const sfx = fixture();
  await withStub({ rules: rulesOk(R, []), notes: NOTES_X }, async (server) => {
    const sessionId = `s9se-${randomUUID()}`;
    const first = await seRun(sfx, server, sessionId, [{ itemId: 'item-1', key: 'note-a', body: 'too short' }]);
    assert.equal(adv(first), seBaseline.text);
    const second = await seRun(sfx, server, sessionId, [{ itemId: 'item-1', key: 'note-b', body: 'too short' }]);
    assert.ok(adv(second).includes('SKILL SUGGESTED'), adv(second));
    assert.equal(server.rulesRequests().length, 1);
  });
});

// ── S10 / S11 / S12: failure ─────────────────────────────────────────────────────────────────────

const FAILURES = {
  'HTTP 404': errEnvelope(404, 'not_found'),
  'HTTP 500 internal': errEnvelope(500, 'internal'),
  'HTTP 503 shedding': errEnvelope(503, 'unavailable', 'shedding'),
  '200 non-envelope': { status: 200, body: { success: true, message: 'ok', data: {} } },
  'element ok:false forbidden': okEnvelope({ results: [{ ok: false, error: { code: 'forbidden', kind: 'permanent', message: 'forbidden fixture' } }], summary: { ok: 0, failed: 1 } }),
};

test('S10: a failed or non-ok rules read gives today\'s text from PG and SE, exit 0, and the next run reads again', async () => {
  const pgBase = await pgOnce({ rules: undefined, gate: { skillPointer: 'x-rule' } });
  const seBaseA = await seOnce({ rules: undefined, notes: NOTES_X, key: 'note-a' });
  const seBaseB = await seOnce({ rules: undefined, notes: NOTES_X, key: 'note-b' });
  for (const [name, failure] of Object.entries(FAILURES)) {
    const fx = fixture();
    await withStub({ gates: pgGates({ skillPointer: 'x-rule' }), rules: failure }, async (server) => {
      const sessionId = `s10pg-${randomUUID()}`;
      const first = await pgRun(fx, server, { sessionId, items: [ITEM_A] });
      assert.equal(first.res.status, 0, name);
      assert.equal(first.reason, pgBase.reason, name);
      const second = await pgRun(fx, server, { sessionId, items: [ITEM_A] });
      assert.equal(second.reason, pgBase.reason, name);
      assert.equal(server.rulesRequests().length, 2, `PG ${name}: nothing was cached`);
    });
    const sfx = fixture();
    await withStub({ rules: failure, notes: NOTES_X }, async (server) => {
      const sessionId = `s10se-${randomUUID()}`;
      const first = await seRun(sfx, server, sessionId, [{ itemId: 'item-1', key: 'note-a', body: 'too short' }]);
      assert.equal(first.status, 0, name);
      assert.equal(adv(first), seBaseA.text, name);
      // a different note so the once-per-(session,item,key) dedup does not hide the second advisory
      const second = await seRun(sfx, server, sessionId, [{ itemId: 'item-1', key: 'note-b', body: 'too short' }]);
      assert.equal(adv(second), seBaseB.text, name);
      assert.equal(server.rulesRequests().length, 2, `SE ${name}: nothing was cached`);
    });
  }
});

test('S10b: a failed read followed by a good one in the same session starts serving the rule', async () => {
  const fx = fixture();
  const rules = (n, req, res) => {
    const reply = n === 1 ? errEnvelope(500, 'internal') : rulesOk(R, ['x-rule']);
    res.writeHead(reply.status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(reply.body));
  };
  await withStub({ gates: pgGates({ skillPointer: 'x-rule' }), rules }, async (server) => {
    const sessionId = `s10b-${randomUUID()}`;
    const first = await pgRun(fx, server, { sessionId, items: [ITEM_A] });
    assert.ok(first.reason.includes('Invoke the x-rule skill'), first.reason);
    const second = await pgRun(fx, server, { sessionId, items: [ITEM_A] });
    assert.ok(second.reason.includes('op "rules"'), second.reason);
  });
});

test('S11: a rules route that never answers -> PG and SE exit 0 with today\'s text inside the 5 s hook timeout', async () => {
  const hang = () => {};
  const pgBase = await pgOnce({ rules: undefined, gate: { skillPointer: 'x-rule' } });
  const fx = fixture();
  await withStub({ gates: pgGates({ skillPointer: 'x-rule' }), rules: hang }, async (server) => {
    const r = await pgRun(fx, server, { sessionId: `s11pg-${randomUUID()}`, items: [ITEM_A] });
    assert.equal(r.res.status, 0);
    assert.equal(r.reason, pgBase.reason);
    assert.ok(r.res.ms < 5000, `PG took ${r.res.ms} ms`);
    assert.equal(server.rulesRequests().length, 1, 'the read was attempted');
  });
  const seBase = await seOnce({ rules: undefined, notes: [XRULE_NOTE] });
  const sfx = fixture();
  await withStub({ rules: hang, notes: [XRULE_NOTE] }, async (server) => {
    const res = await seRun(sfx, server, `s11se-${randomUUID()}`, [{ itemId: 'item-1', key: 'note-a', body: 'too short' }]);
    assert.equal(res.status, 0);
    assert.equal(adv(res), seBase.text);
    assert.ok(res.ms < 5000, `SE took ${res.ms} ms`);
    assert.equal(server.rulesRequests().length, 1, 'the read was attempted');
  });
});

test('S12: no config, an invalid rootId (a/b) or no project block -> no rules request and today\'s text', async () => {
  const configs = { 'no config': null, 'rootId a/b': 'project:\n  rootId: a/b\n', 'no project block': 'retrospective:\n  mode: dispatch\n', 'project without rootId': 'project:\n  name: demo\n' };
  for (const [name, config] of Object.entries(configs)) {
    const pg = await pgOnce({ rules: rulesOk(R, ['x-rule']), gate: { skillPointer: 'x-rule' }, config });
    assert.equal(pg.res.status, 0, name);
    assert.equal(pg.out.decision, 'block', name);
    assert.equal(pg.rulesReqs.length, 0, `PG ${name}`);
    assert.ok(pg.reason.includes('Invoke the x-rule skill for guidance.'), `${name}: ${pg.reason}`);
    if (config === null) continue; // SE is silent without a discoverable config
    const se = await seOnce({ rules: rulesOk(R, ['x-rule']), notes: [XRULE_NOTE], config });
    assert.equal(se.res.status, 0, name);
    assert.equal(se.rulesReqs.length, 0, `SE ${name}`);
    assert.ok(se.text.includes('SKILL SUGGESTED'), `${name}: ${se.text}`);
    assert.ok(!se.text.includes('RULE SUGGESTED'), `${name}: ${se.text}`);
  }
});

// ── S13 / S14: lazy read ─────────────────────────────────────────────────────────────────────────

test('S13: PG makes no rules request without a hint to build (no pointer, an invalid hint, no block)', async () => {
  const W = [
    { name: 'implementer', phase: 'work', enters: true },
    { name: 'orchestrator', phase: 'work', after: ['implementer'] },
  ];
  const noPointer = await pgOnce({ rules: rulesOk(R, ['x-rule']), gate: {} });
  assert.equal(noPointer.out.decision, 'block');
  assert.equal(noPointer.rulesReqs.length, 0, 'block without skillPointer');

  const invalidHint = await pgOnce({
    rules: rulesOk(R, ['x-rule']),
    agentType: IMPL,
    gate: {
      role: 'work',
      missing: ['session-tracking', 'implementation-notes'],
      missingBySeat: { implementer: ['implementation-notes'], orchestrator: ['session-tracking'] },
      seats: W,
      skillPointer: 'x-rule',
    },
  });
  assert.equal(invalidHint.out.decision, 'block');
  assert.ok(!invalidHint.reason.includes('session-tracking'), invalidHint.reason);
  assert.equal(invalidHint.rulesReqs.length, 0, 'invalid hint (raw first key filtered out)');

  const noBlock = await pgOnce({ rules: rulesOk(R, ['x-rule']), gate: { missing: [], skillPointer: 'x-rule' } });
  assert.deepEqual(noBlock.out, {});
  assert.equal(noBlock.rulesReqs.length, 0, 'no block');

  const control = await pgOnce({
    rules: rulesOk(R, ['x-rule']),
    agentType: IMPL,
    gate: {
      role: 'work',
      missing: ['implementation-notes', 'session-tracking'],
      missingBySeat: { implementer: ['implementation-notes'], orchestrator: ['session-tracking'] },
      seats: W,
      skillPointer: 'x-rule',
    },
  });
  assert.equal(control.rulesReqs.length, 1, 'control: a valid hint reads the rules');
  assert.ok(control.reason.includes('op "rules"'), control.reason);
});

test('S14: SE makes no rules request when nothing warns (long body, bodyFromFile, dedup repeat)', async () => {
  const long = await seOnce({ rules: rulesOk(R, ['x-rule']), notes: [XRULE_NOTE], body: 'x'.repeat(200) });
  assert.equal(long.text, '');
  assert.equal(long.rulesReqs.length, 0, 'long body');

  const fx = fixture();
  await withStub({ rules: rulesOk(R, ['x-rule']), notes: [XRULE_NOTE] }, async (server) => {
    const res = await runHook(SE_HOOK, upsertPayload(`s14f-${randomUUID()}`, [{ itemId: 'item-1', key: 'note-a', bodyFromFile: 'notes/a.md' }]), { dir: fx.dir, tmp: fx.tmp, apiUrl: server.origin, agentConfigDir: true });
    assert.equal(res.stdout.trim(), '');
    assert.equal(server.rulesRequests().length, 0, 'bodyFromFile');
  });

  // rules answer 404 so nothing is cached: only a read made by the repeat could raise the count
  const dfx = fixture();
  await withStub({ rules: undefined, notes: [XRULE_NOTE] }, async (server) => {
    const sessionId = `s14d-${randomUUID()}`;
    const first = await seRun(dfx, server, sessionId, [{ itemId: 'item-1', key: 'note-a', body: 'too short' }]);
    assert.ok(first.stdout.length > 0);
    assert.equal(server.rulesRequests().length, 1, 'control: a warning reads the rules');
    const second = await seRun(dfx, server, sessionId, [{ itemId: 'item-1', key: 'note-a', body: 'still short' }]);
    assert.equal(second.stdout.trim(), '');
    assert.equal(server.rulesRequests().length, 1, 'dedup repeat');
  });
});

// ── S15: marker keyed by rootId ──────────────────────────────────────────────────────────────────

test('S15: a marker cached for another rootId is a miss (fresh read); a marker for this rootId is served without a request', async () => {
  const fx = fixture();
  await withStub({ gates: pgGates({ skillPointer: 'x-rule' }), rules: rulesOk(R, []) }, async (server) => {
    const sessionId = `s15a-${randomUUID()}`;
    withRedirectedTmp(fx.tmp, () => writeMarker(sessionMarkerPath(sessionId, 'served-rules'), { rootId: R2, keys: ['x-rule'] }));
    const r = await pgRun(fx, server, { sessionId, items: [ITEM_A] });
    assert.equal(server.rulesRequests().length, 1, 'the foreign-rootId marker is a miss');
    assert.ok(r.reason.includes('Invoke the x-rule skill for guidance.'), `the stale R2 keys are not used: ${r.reason}`);
  });
  const fx2 = fixture();
  await withStub({ gates: pgGates({ skillPointer: 'x-rule' }), rules: undefined }, async (server) => {
    const sessionId = `s15b-${randomUUID()}`;
    withRedirectedTmp(fx2.tmp, () => writeMarker(sessionMarkerPath(sessionId, 'served-rules'), { rootId: R, keys: ['x-rule'] }));
    const r = await pgRun(fx2, server, { sessionId, items: [ITEM_A] });
    assert.equal(server.rulesRequests().length, 0, 'a marker for this rootId is a hit');
    assert.ok(r.reason.includes('op "rules"'), r.reason);
  });
});

// ── probes ───────────────────────────────────────────────────────────────────────────────────────

test('probe: X-Rule, task-orchestrator:x-rule, "x-rule " (and a null-ish name) stay skills; the test is exact and case-sensitive', async () => {
  for (const pointer of ['X-Rule', 'task-orchestrator:x-rule', 'x-rule ', 'undefined', 'null']) {
    const pg = await pgOnce({ rules: rulesOk(R, ['x-rule']), gate: { skillPointer: pointer } });
    assert.ok(pg.reason.includes('Invoke the'), `PG ${JSON.stringify(pointer)}: ${pg.reason}`);
    assert.ok(!pg.reason.includes('op "rules"'), `PG ${JSON.stringify(pointer)}: ${pg.reason}`);
    const se = await seOnce({ rules: rulesOk(R, ['x-rule']), notes: [seNote('note-a', [pointer])] });
    assert.ok(se.text.includes('SKILL SUGGESTED'), `SE ${JSON.stringify(pointer)}: ${se.text}`);
    assert.ok(!se.text.includes('RULE SUGGESTED'), `SE ${JSON.stringify(pointer)}: ${se.text}`);
  }
});

test('probe: a duplicate key in the list and entries without a string key do not break the rule test', async () => {
  const entries = [{ key: 5 }, { rulesVersion: 'v1' }, null, 'x-rule', { key: 'x-rule' }, { key: 'x-rule' }];
  const pg = await pgOnce({ rules: rulesRaw(R, entries), gate: { skillPointer: 'x-rule' } });
  assert.equal(pg.res.status, 0);
  assert.ok(pg.reason.includes('op "rules"'), pg.reason);
  const se = await seOnce({ rules: rulesRaw(R, entries), notes: [seNote('note-a', ['x-rule', 'undefined'])] });
  assert.ok(se.text.includes('RULE SUGGESTED'), se.text);
  assert.ok(se.text.includes('skill="undefined"'), `an entry without a key does not make "undefined" a rule: ${se.text}`);
});

test('probe: two recorded items both hinting a rule cost one read', async () => {
  const fx = fixture();
  await withStub({ gates: pgGates({ skillPointer: 'x-rule' }, [ITEM_A, ITEM_B]), rules: rulesOk(R, ['x-rule']) }, async (server) => {
    const r = await pgRun(fx, server, { sessionId: `two-${randomUUID()}`, items: [ITEM_A, ITEM_B] });
    assert.ok(r.reason.includes('op "rules"'), r.reason);
    assert.equal(server.rulesRequests().length, 1);
  });
});

// ── S16: text modules ────────────────────────────────────────────────────────────────────────────

const RULE_TOKENS = ['served rule', '`config` op `rules`'];
const hasTokens = (text) => RULE_TOKENS.every((t) => text.includes(t)) && /invoke/i.test(text);
const readPlugin = (...parts) => readFileSync(join(PLUGIN_DIR, ...parts), 'utf-8');

test('S16: schema-workflow skillPointer table row and Step 2 sentence say: fetch a served rule, invoke a skill', () => {
  const text = readPlugin('skills', 'schema-workflow', 'SKILL.md');
  const row = text.split(/\r?\n/).find((l) => l.startsWith('|') && l.includes('`skillPointer`'));
  assert.ok(row, 'the skillPointer table row exists');
  assert.ok(hasTokens(row), `table row: ${row}`);
  const start = text.indexOf('### Step 2');
  const end = text.indexOf('### Step 3');
  assert.ok(start >= 0 && end > start, 'Step 2 section exists');
  assert.ok(hasTokens(text.slice(start, end)), 'Step 2 section');
});

test('S16: both companion-template "If `skillPointer`" lines say: fetch a served rule, invoke a skill', () => {
  const text = readPlugin('skills', 'manage-schemas', 'references', 'companion-template.md');
  const lines = text.split(/\r?\n/).filter((l) => l.startsWith('If `skillPointer`'));
  assert.equal(lines.length, 2, 'two If `skillPointer` lines');
  for (const l of lines) assert.ok(hasTokens(l), l);
});

test('S16: implementer.md says: fetch a served rule, invoke a skill, in the paragraph that names skillPointer', () => {
  const text = readPlugin('agents', 'implementer.md');
  const para = text.split(/\r?\n\r?\n/).find((p) => p.includes('`skillPointer`'));
  assert.ok(para, 'a paragraph names `skillPointer`');
  assert.ok(hasTokens(para), para);
});
