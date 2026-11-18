// Item ec7874fe (F3-C): skill-enforcement.mjs (SE) refuses a subagent's notes_write upsert until the note's own skills are
// on record, and keeps to its time budget. Scenarios S8, S9, S10 and S17 of the frozen test-plan note, S12, S14, S15 and S22
// from ruling A1, and S23 and S24 from ruling A2 (a denial needs the item's own schema view: its features list seatSkills, it
// is ok, not schemaFree, has notes[] and its itemId is a UUID or 8-32 hex; otherwise today's SKILL SUGGESTED advice only).
// Blind test-author seat: no production .mjs file was opened.
//
// Oracles: task-scope AC4, AC5 (denied while the skill is missing; no release, ruling A1), AC7 and D5, D6 (a subagent upsert op with note pointers is split
// by the served rule keys; a rule is never required; servedKeys null means today's advice and no denial; the deadline is
// 4000 ms for the whole hook); the exported prefix string NOTE_SKILL_REQUIRED_PREFIX (declaration) and the ruling A1 texts;
// wire shapes from rule-pointer.test.mjs (config rules reply {rootId, rules:[{key, rulesVersion, updatedAt}]}) and
// skill-enforcement.test.mjs (items_read schema view with notes[]). Skill records are written only by skill-record.mjs.
//
// Fixture condition for each absence assertion: the twin test or step with the missing skill and no served rule denies.
// Forbidden-construct declaration: none.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { NOTE_SKILL_REQUIRED_PREFIX } from '../lib/seat-skill-state.mjs';

// The literal texts frozen by ruling A1 (the amendment is the oracle, not the code).
const SRC_SCHEMA = 'Binding source: schema.';
const UNK = 'If the Skill tool answers Unknown skill, or you have no Skill tool, do not retry: stop and report the seat, the skill name and the binding source.';

const SE = fileURLToPath(new URL('../skill-enforcement.mjs', import.meta.url));
const RECORD = fileURLToPath(new URL('../skill-record.mjs', import.meta.url));
const NOTES_WRITE = 'mcp__mcp-task-orchestrator__notes_write';
const R = '11111111-1111-1111-1111-111111111111';
const BOOTSTRAP = `project:\n  rootId: ${R}\n`;
const IMPL = 'task-orchestrator:implementer';
const U1 = '4a468f6c-9434-4cbf-97e8-b6c546c1b112';
const U2 = '5b579f7d-a545-4dae-a869-c7d657d205c3';
const U3 = '6c68a08e-b656-4ebf-b97a-d8e768e316d4';

const tracked = [];
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});
function fresh(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix));
  tracked.push(d);
  return d;
}
function fixture() {
  const dir = fresh('to-se-deny-');
  mkdirSync(join(dir, '.taskorchestrator'), { recursive: true });
  writeFileSync(join(dir, '.taskorchestrator', 'config.yaml'), BOOTSTRAP, 'utf-8');
  return { dir, tmp: fresh('to-se-deny-tmp-') };
}

// ---- stub ------------------------------------------------------------------------------------------------

const okEnvelope = (data) => ({ status: 200, body: { ok: true, data, meta: { reqId: 'req00001' } } });
const errEnvelope = (status, code) => ({ status, body: { ok: false, error: { code, kind: 'permanent', message: `${code} fixture` }, meta: { reqId: 'req00002' } } });
const rulesOk = (keys) =>
  okEnvelope({ results: [{ ok: true, rootId: R, rules: keys.map((key) => ({ key, rulesVersion: 'v1', updatedAt: '2026-01-01T00:00:00Z' })) }], summary: { ok: 1, failed: 0 } });
const note = (key, skills) => ({ key, role: 'work', required: true, description: 'd', skill: skills[0], skills });

/**
 * `notes`: the notes of the effective schemas and of every item schema view. `rules`: a reply or undefined (404).
 * `delays`: {effective, schema, rules} ms before answering. `features`: the features of every item schema view (null
 * omits the key; the default lists seatSkills). `schema`: a reply or (itemId) => reply that replaces the item schema view.
 */
async function startStub({ notes = [], rules, delays = {}, features = ['seats', 'seatSkills'], schema } = {}) {
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
      let reply = errEnvelope(404, 'not_found');
      let delay = 0;
      if (req.method === 'POST' && req.url === '/api/v1/ops/config' && op === 'rules') {
        reply = rules ?? errEnvelope(404, 'not_found');
        delay = delays.rules ?? 0;
      } else if (req.method === 'POST' && req.url === '/api/v1/ops/config' && op === 'effective') {
        reply = okEnvelope({ results: [{ ok: true, schemas: [{ type: 'test-schema', notes }], traits: [], types: ['test-schema'], statusGraph: {} }], summary: { ok: 1, failed: 0 } });
        delay = delays.effective ?? 0;
      } else if (req.method === 'POST' && req.url === '/api/v1/ops/items_read' && body.select === 'schema') {
        reply = schema === undefined
          ? okEnvelope({ type: 'test-schema', configFingerprint: 'fp', configSource: 'per-root', notes, ...(features === null ? {} : { features }) })
          : typeof schema === 'function' ? schema(body.itemId) : schema;
        delay = delays.schema ?? 0;
      }
      setTimeout(() => {
        res.writeHead(reply.status, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(reply.body));
      }, delay);
    });
  });
  server.on('connection', (s) => {
    sockets.add(s);
    s.on('close', () => sockets.delete(s));
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  server.requests = requests;
  server.origin = `http://127.0.0.1:${server.address().port}`;
  server.stop = () => {
    for (const s of sockets) s.destroy();
    return new Promise((r) => server.close(r));
  };
  return server;
}

async function withStub(opts, body) {
  const server = await startStub(opts);
  const fx = fixture();
  try {
    return await body(server, fx);
  } finally {
    await server.stop();
  }
}

function run(hook, payload, fx, apiUrl, env = {}) {
  return new Promise((resolve, reject) => {
    const env2 = { ...process.env, TEMP: fx.tmp, TMP: fx.tmp, TMPDIR: fx.tmp, TASK_ORCHESTRATOR_HOME: join(fx.dir, 'to-home-empty'), TASK_ORCHESTRATOR_CEILING: fx.dir, AGENT_CONFIG_DIR: fx.dir };
    delete env2.TASK_ORCHESTRATOR_API_URL;
    delete env2.TASK_ORCHESTRATOR_API_TOKEN;
    delete env2.TASK_ORCHESTRATOR_MODE;
    delete env2.CLAUDE_PROJECT_DIR;
    if (apiUrl) env2.TASK_ORCHESTRATOR_API_URL = apiUrl;
    Object.assign(env2, env);
    const started = Date.now();
    const child = spawn(process.execPath, [hook], { env: env2, cwd: fx.dir });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.stderr.on('data', (d) => {
      stderr += d;
    });
    child.on('error', reject);
    child.on('close', (status) => resolve({ status, stdout, stderr, ms: Date.now() - started }));
    child.stdin.end(JSON.stringify(payload));
  });
}

const parse = (res) => (res.stdout.trim() === '' ? {} : JSON.parse(res.stdout));
const isDeny = (res) => parse(res).hookSpecificOutput?.permissionDecision === 'deny' || parse(res).decision === 'block';
const reasonOf = (res) => parse(res).hookSpecificOutput?.permissionDecisionReason ?? '';
const sid = (name) => `${name}-${randomUUID()}`;

/** A notes_write upsert as PreToolUse input. agentId null = main session. */
function upsert(session, agentId, ops, agentType = IMPL) {
  return {
    session_id: session,
    ...(agentId ? { agent_id: agentId } : {}),
    ...(agentType && agentId ? { agent_type: agentType } : {}),
    tool_name: NOTES_WRITE,
    tool_input: { ops: ops.map((o) => ({ op: 'upsert', role: 'work', body: 'b'.repeat(400), ...o })) },
  };
}
const skillRecord = (session, agentId, skill) => ({ session_id: session, agent_id: agentId, tool_name: 'Skill', tool_input: { skill }, tool_response: { success: true, commandName: skill } });

// ---- S8 ---------------------------------------------------------------------------------------------------

test('S8: a subagent upsert whose note skill y-skill is not on record is denied naming the key and "y-skill"; after Skill y-skill it is not', async () => {
  await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]) }, async (server, fx) => {
    const s = sid('s8');
    const denied = await run(SE, upsert(s, 'agent-1', [{ itemId: U1, key: 'note-a' }]), fx, server.origin);
    assert.equal(denied.status, 0, denied.stderr);
    assert.ok(isDeny(denied), denied.stdout);
    const reason = reasonOf(denied);
    assert.ok(reason.startsWith(NOTE_SKILL_REQUIRED_PREFIX), reason);
    assert.ok(reason.includes('note-a'), reason);
    assert.ok(reason.includes('"y-skill"'), reason);
    assert.ok(reason.includes(SRC_SCHEMA), reason);
    assert.ok(reason.includes(UNK), reason);

    // Another agent in the session has not loaded it either: its own attempt is also denied.
    const other = await run(SE, upsert(s, 'agent-2', [{ itemId: U1, key: 'note-a' }]), fx, server.origin);
    assert.ok(isDeny(other), 'a denial of agent-1 does not release agent-2');

    const rec = await run(RECORD, skillRecord(s, 'agent-3', 'y-skill'), fx, server.origin);
    assert.equal(rec.status, 0);
    const t = sid('s8b');
    await run(RECORD, skillRecord(t, 'agent-1', 'y-skill'), fx, server.origin);
    const after = await run(SE, upsert(t, 'agent-1', [{ itemId: U1, key: 'note-a' }]), fx, server.origin);
    assert.equal(after.status, 0, after.stderr);
    assert.ok(!isDeny(after), after.stdout);
  });
});

test('S8 probe: a record from agent A does not satisfy agent B; the main session (no agent_id) is never denied', async () => {
  await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]) }, async (server, fx) => {
    const s = sid('s8c');
    await run(RECORD, skillRecord(s, 'agent-A', 'y-skill'), fx, server.origin);
    assert.ok(!isDeny(await run(SE, upsert(s, 'agent-A', [{ itemId: U1, key: 'note-a' }]), fx, server.origin)));
    assert.ok(isDeny(await run(SE, upsert(s, 'agent-B', [{ itemId: U1, key: 'note-a' }]), fx, server.origin)), 'B has no record');
    assert.ok(!isDeny(await run(SE, upsert(s, null, [{ itemId: U1, key: 'note-a' }]), fx, server.origin)), 'main session');
  });
});

test('S8 probe: an op that is not an upsert (delete) is never denied', async () => {
  await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]) }, async (server, fx) => {
    const payload = { session_id: sid('s8d'), agent_id: 'agent-1', agent_type: IMPL, tool_name: NOTES_WRITE, tool_input: { ops: [{ op: 'delete', itemId: U1, key: 'note-a' }] } };
    const res = await run(SE, payload, fx, server.origin);
    assert.equal(res.status, 0);
    assert.ok(!isDeny(res), res.stdout);
  });
});

// ---- S9 ---------------------------------------------------------------------------------------------------

test('S9: a note skill that is a served rule (x-rule) is never required; the twin with x-rule unserved is denied', async () => {
  const notes = [note('note-a', ['x-rule'])];
  await withStub({ notes, rules: rulesOk(['x-rule']) }, async (server, fx) => {
    const res = await run(SE, upsert(sid('s9a'), 'agent-1', [{ itemId: U1, key: 'note-a' }]), fx, server.origin);
    assert.equal(res.status, 0, res.stderr);
    assert.ok(!isDeny(res), res.stdout);
  });
  await withStub({ notes, rules: rulesOk(['other-rule']) }, async (server, fx) => {
    const res = await run(SE, upsert(sid('s9b'), 'agent-1', [{ itemId: U1, key: 'note-a' }]), fx, server.origin);
    assert.ok(isDeny(res), 'twin: x-rule is not served here, so it is a skill and is required');
    assert.ok(reasonOf(res).includes('"x-rule"'));
  });
});

test('S9: a mixed note [x-rule, y-skill] is denied naming y-skill only, in either order', async () => {
  for (const skills of [['x-rule', 'y-skill'], ['y-skill', 'x-rule']]) {
    await withStub({ notes: [note('note-a', skills)], rules: rulesOk(['x-rule']) }, async (server, fx) => {
      const res = await run(SE, upsert(sid('s9m'), 'agent-1', [{ itemId: U1, key: 'note-a' }]), fx, server.origin);
      assert.ok(isDeny(res), res.stdout);
      const reason = reasonOf(res);
      assert.ok(reason.startsWith(NOTE_SKILL_REQUIRED_PREFIX), reason);
      assert.ok(reason.includes('"y-skill"'), reason);
      assert.ok(!reason.includes('"x-rule"'), reason);
    });
  }
});

// ---- S10 --------------------------------------------------------------------------------------------------

test('S10: when the rules route answers 404 nothing is denied (today\'s advice only)', async () => {
  await withStub({ notes: [note('note-a', ['y-skill'])], rules: undefined }, async (server, fx) => {
    const res = await run(SE, upsert(sid('s10'), 'agent-1', [{ itemId: U1, key: 'note-a' }, { itemId: U1, key: 'note-a' }]), fx, server.origin);
    assert.equal(res.status, 0, res.stderr);
    assert.ok(!isDeny(res), res.stdout);
    assert.ok(server.requests.some((r) => r.body?.ops?.[0]?.op === 'rules'), 'the rules read was attempted, so its failure is what released the call');
  });
});

// ---- S23: a denial needs seatSkills in the item's own schema view (ruling A2, B1) ---------------------------

const adviceOf = (res) => parse(res).hookSpecificOutput?.additionalContext ?? '';

test('S23: an item schema view whose features are [seats], absent or [] never denies a missing note skill and gets today\'s SKILL SUGGESTED advice; the default-features twin is denied', async () => {
  const cases = [['seats only', ['seats']], ['absent', null], ['empty', []]];
  for (const [label, features] of cases) {
    await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]), features }, async (server, fx) => {
      const s = sid('s23');
      for (const attempt of [1, 2, 3]) {
        const res = await run(SE, upsert(s, 'agent-1', [{ itemId: U1, key: 'note-a', body: 'tbd' }]), fx, server.origin);
        assert.equal(res.status, 0, `${label} attempt ${attempt}: ${res.stderr}`);
        assert.ok(!isDeny(res), `${label} attempt ${attempt}: ${res.stdout}`);
        if (attempt === 1) {
          assert.ok(adviceOf(res).includes('SKILL SUGGESTED'), `${label}: ${res.stdout}`);
          assert.ok(adviceOf(res).includes('skill="y-skill"'), `${label}: ${res.stdout}`);
        }
      }
      assert.ok(server.requests.some((r) => r.url === '/api/v1/ops/items_read' && r.body?.select === 'schema' && r.body?.itemId === U1), `${label}: the item's own schema view was read`);
    });
  }
  // Twin: the same fixture with seatSkills in the features (the default) is denied.
  await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]) }, async (server, fx) => {
    const res = await run(SE, upsert(sid('s23t'), 'agent-1', [{ itemId: U1, key: 'note-a', body: 'tbd' }]), fx, server.origin);
    assert.ok(isDeny(res), res.stdout);
    assert.ok(reasonOf(res).startsWith(NOTE_SKILL_REQUIRED_PREFIX), reasonOf(res));
  });
});

test('S23 probe: skill advice is withheld per pair the denial check covered, not for the whole call', async () => {
  // U1's own view lacks seatSkills (advice only, z not involved); U2's view lists it and its skill z-skill is on record (covered, satisfied).
  const views = (itemId) =>
    itemId === U1
      ? okEnvelope({ type: 'test-schema', configFingerprint: 'fp', configSource: 'per-root', notes: [note('note-a', ['y-skill'])], features: ['seats'] })
      : okEnvelope({ type: 'test-schema', configFingerprint: 'fp', configSource: 'per-root', notes: [note('note-b', ['z-skill'])], features: ['seats', 'seatSkills'] });
  await withStub({ notes: [note('note-a', ['y-skill']), note('note-b', ['z-skill'])], rules: rulesOk([]), schema: views }, async (server, fx) => {
    const s = sid('s23p');
    assert.equal((await run(RECORD, skillRecord(s, 'agent-1', 'z-skill'), fx, server.origin)).status, 0);
    const res = await run(SE, upsert(s, 'agent-1', [{ itemId: U1, key: 'note-a', body: 'tbd' }, { itemId: U2, key: 'note-b', body: 'tbd' }]), fx, server.origin);
    assert.equal(res.status, 0, res.stderr);
    assert.ok(!isDeny(res), res.stdout);
    assert.ok(adviceOf(res).includes('skill="y-skill"'), `the uncovered pair keeps its advice: ${res.stdout}`);
    assert.ok(!adviceOf(res).includes('skill="z-skill"'), `the covered pair's advice is withheld: ${res.stdout}`);
  });
});

// ---- S24: a denial never rests on the session maps (ruling A2, O1) ----------------------------------------

test('S24: when the item\'s own schema view fails, is not ok, is schemaFree, has no notes[] or times out, nothing is denied; the healthy twin is denied', async () => {
  const view = (extra = {}) => okEnvelope({ type: 'test-schema', configFingerprint: 'fp', configSource: 'per-root', notes: [note('note-a', ['y-skill'])], features: ['seats', 'seatSkills'], ...extra });
  const noNotes = okEnvelope({ type: 'test-schema', configFingerprint: 'fp', configSource: 'per-root', features: ['seats', 'seatSkills'] });
  const variants = [
    ['404', { schema: errEnvelope(404, 'not_found') }],
    ['500', { schema: errEnvelope(500, 'internal') }],
    ['ok false', { schema: { status: 200, body: { ok: false, error: { code: 'not_found', kind: 'permanent', message: 'fixture' }, meta: { reqId: 'req00003' } } } }],
    ['schemaFree', { schema: view({ schemaFree: true }) }],
    ['no notes[]', { schema: noNotes }],
    ['timeout', { schema: view(), delays: { schema: 3000 } }],
  ];
  for (const [label, opts] of variants) {
    // The effective schemas (the session maps) always map note-a -> y-skill and the rules are served without it.
    await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]), ...opts }, async (server, fx) => {
      const res = await run(SE, upsert(sid('s24'), 'agent-1', [{ itemId: U1, key: 'note-a', body: 'tbd' }]), fx, server.origin);
      assert.equal(res.status, 0, `${label}: ${res.stderr}`);
      assert.ok(!isDeny(res), `${label}: ${res.stdout}`);
      assert.ok(res.ms < 5000, `${label}: took ${res.ms} ms`);
      assert.ok(server.requests.some((r) => r.url === '/api/v1/ops/items_read' && r.body?.itemId === U1), `${label}: the item's own view was attempted, so its failure is what released the call`);
    });
  }
  // Twin: the same effective maps with a healthy item view are denied.
  await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]) }, async (server, fx) => {
    const res = await run(SE, upsert(sid('s24t'), 'agent-1', [{ itemId: U1, key: 'note-a', body: 'tbd' }]), fx, server.origin);
    assert.ok(isDeny(res), res.stdout);
  });
});

test('S24: an itemId that is not a UUID or 8-32 hex is never denied; 8 and 32 hex and a UUID are', async () => {
  const hex = (n) => 'abcdef0123456789abcdef0123456789'.slice(0, n);
  await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]) }, async (server, fx) => {
    for (const bad of ['not-an-id', hex(7), `${hex(32)}a`, '']) {
      const res = await run(SE, upsert(sid('s24i'), 'agent-1', [{ itemId: bad, key: 'note-a', body: 'tbd' }]), fx, server.origin);
      assert.equal(res.status, 0, `${JSON.stringify(bad)}: ${res.stderr}`);
      assert.ok(!isDeny(res), `${JSON.stringify(bad)}: ${res.stdout}`);
    }
    for (const good of [hex(8), hex(32), U1]) {
      const res = await run(SE, upsert(sid('s24g'), 'agent-1', [{ itemId: good, key: 'note-a', body: 'tbd' }]), fx, server.origin);
      assert.ok(isDeny(res), `${good}: ${res.stdout}`);
    }
  });
});

// ---- S14 (note skill; no release, ruling A1) ---------------------------------------------------------------

test('S14: a note skill that never loads is denied on every attempt with the stop-and-report text and no systemMessage; denials stay per key and per item', async () => {
  await withStub({ notes: [note('note-a', ['y-skill']), note('note-b', ['y-skill'])], rules: rulesOk([]) }, async (server, fx) => {
    const s = sid('s14');
    for (const attempt of [1, 2, 3]) {
      const res = await run(SE, upsert(s, 'agent-1', [{ itemId: U1, key: 'note-a' }]), fx, server.origin);
      assert.equal(res.status, 0, `attempt ${attempt}`);
      assert.ok(isDeny(res), `attempt ${attempt}: ${res.stdout}`);
      const reason = reasonOf(res);
      assert.ok(reason.startsWith(NOTE_SKILL_REQUIRED_PREFIX), reason);
      assert.ok(reason.includes('"y-skill"'), reason);
      assert.ok(reason.includes(SRC_SCHEMA), reason);
      assert.ok(reason.includes(UNK), reason);
      assert.equal(parse(res).systemMessage, undefined, `attempt ${attempt}: ${res.stdout}`);
      assert.ok(!res.stdout.includes('proceeding without'), res.stdout);
    }
    const otherKey = await run(SE, upsert(s, 'agent-1', [{ itemId: U1, key: 'note-b' }]), fx, server.origin);
    assert.ok(isDeny(otherKey), otherKey.stdout);
    const otherItem = await run(SE, upsert(s, 'agent-1', [{ itemId: U2, key: 'note-a' }]), fx, server.origin);
    assert.ok(isDeny(otherItem), otherItem.stdout);
  });
});

// ---- S22: unwritable marker root fails open (ruling A1) ----------------------------------------------------

test('S22: with TEMP pointing at a file (no writable marker root) a missing note skill is not denied; S8 is the writable twin', async () => {
  await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]) }, async (server, fx) => {
    const file = join(fx.dir, 'not-a-dir');
    writeFileSync(file, 'x', 'utf-8');
    const bad = { ...fx, tmp: file };
    const res = await run(SE, upsert(sid('s22'), 'agent-1', [{ itemId: U1, key: 'note-a' }]), bad, server.origin);
    assert.equal(res.status, 0, res.stderr);
    assert.ok(!isDeny(res), res.stdout);
    assert.ok(server.requests.some((r) => r.body?.ops?.[0]?.op === 'rules'), 'the rules read ran, so the missing skill was known and only the probe released the call');
    // Twin: the same upsert with a writable TEMP is denied.
    const twin = await run(SE, upsert(sid('s22w'), 'agent-1', [{ itemId: U1, key: 'note-a' }]), fx, server.origin);
    assert.ok(isDeny(twin), twin.stdout);
  });
});

// ---- S12 (SE) ----------------------------------------------------------------------------------------------

test('S12: with no REST URL a subagent upsert gets the inert line and no denial', async () => {
  await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]) }, async (_server, fx) => {
    const res = await run(SE, upsert(sid('s12'), 'agent-1', [{ itemId: U1, key: 'note-a' }]), fx, undefined);
    assert.equal(res.status, 0, res.stderr);
    assert.ok(!isDeny(res), res.stdout);
    assert.ok(res.stdout.includes('is inert'), res.stdout);
  });
});

// ---- S15 (note skills through frontmatter) -----------------------------------------------------------------

test('S15: a note skill preloaded through project agent frontmatter satisfies it; without the file the call is denied', async () => {
  await withStub({ notes: [note('note-a', ['p:a'])], rules: rulesOk([]) }, async (server, fx) => {
    const env = { CLAUDE_PROJECT_DIR: fx.dir };
    const twin = await run(SE, upsert(sid('s15t'), 'agent-1', [{ itemId: U1, key: 'note-a' }], 'implementer'), fx, server.origin, env);
    assert.ok(isDeny(twin), twin.stdout);
    mkdirSync(join(fx.dir, '.claude', 'agents'), { recursive: true });
    for (const text of ['---\nname: implementer\nskills: p:a, p:b\n---\n', '---\nname: implementer\nskills: [p:a]\n---\n', '---\nname: implementer\nskills:\n  - p:a\n---\n']) {
      writeFileSync(join(fx.dir, '.claude', 'agents', 'implementer.md'), text, 'utf-8');
      const res = await run(SE, upsert(sid('s15'), 'agent-1', [{ itemId: U1, key: 'note-a' }], 'implementer'), fx, server.origin, env);
      assert.ok(!isDeny(res), `${text}: ${res.stdout}`);
    }
  });
});

// ---- S17 ---------------------------------------------------------------------------------------------------

test('S17: a subagent upsert over 3 items with effective and item schemas at 1400 ms and rules at 900 ms exits 0 under 5000 ms with no denial', async () => {
  await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]), delays: { effective: 1400, schema: 1400, rules: 900 } }, async (server, fx) => {
    const res = await run(SE, upsert(sid('s17'), 'agent-1', [{ itemId: U1, key: 'note-a' }, { itemId: U2, key: 'note-a' }, { itemId: U3, key: 'note-a' }]), fx, server.origin);
    assert.equal(res.status, 0, res.stderr);
    assert.ok(res.ms < 5000, `took ${res.ms} ms`);
    assert.ok(!isDeny(res), res.stdout);
  });
});

test('S17 probe: with every route answering instantly the same upsert is denied, so the S17 absence of a denial is the budget', async () => {
  await withStub({ notes: [note('note-a', ['y-skill'])], rules: rulesOk([]) }, async (server, fx) => {
    const res = await run(SE, upsert(sid('s17p'), 'agent-1', [{ itemId: U1, key: 'note-a' }, { itemId: U2, key: 'note-a' }, { itemId: U3, key: 'note-a' }]), fx, server.origin);
    assert.ok(isDeny(res), res.stdout);
  });
});
