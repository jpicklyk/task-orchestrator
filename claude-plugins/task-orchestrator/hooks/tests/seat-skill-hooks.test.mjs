// Item ec7874fe (F3-C): seat-skill-hint.mjs, skill-record.mjs and seat-skill-gate.mjs as spawned node hook scripts.
// Scenarios S1-S7, S11-S16, S18 and S20 of the frozen test-plan note plus S21 from ruling A1 and S25 and S26 from ruling A2
// (S8-S10, S17, S22, S23 and S24 live in skill-enforcement-deny.test.mjs, S19 in scripts/tests/seat-skills.test.mjs).
// S25: a plugin-default seat binding is advisory unless the server lists seatSkills; S26: an unwritable armed-probe path
// fails open. Blind test-author seat: no production .mjs file was opened; the inputs and
// outputs below come from the task-scope decisions D1-D9 and AC1-AC9, the supplied declarations (exported prefix strings),
// lib-emit.test.mjs (the stdout shapes of deny / context / systemMessage), phase-guard-record-read.test.mjs (the PostToolUse
// tool_response shape) and rule-pointer.test.mjs (the REST stub wire shapes).
//
// Oracles: task-scope AC1-AC9, D1-D9. State is observed only through the hooks (a hint, then a Skill record, then a gate);
// marker files are never read. Hooks run as async children because the REST stub lives in this process (spawnSync would
// starve it and give a false green). Every child gets its own TEMP/TMP/TMPDIR, a pinned empty TASK_ORCHESTRATOR_HOME and a
// fixture cwd. The shipped seat defaults table names one engineering skill per seat (scripts/tests/seat-skills.test.mjs S8),
// so a case where "absent" must mean "none" runs on a plugin copy with an empty table (pluginCopy({})).
//
// Fixture condition for each absence assertion: a twin fixture in the same test (or the next one) produces the output.
// Forbidden-construct declaration: none.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, cpSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, relative, sep } from 'node:path';
import { randomUUID } from 'node:crypto';
import { orchestratorToolMatcher } from '../registration.mjs';
import { SEAT_SKILLS_PREFIX, SEAT_SKILLS_REQUIRED_PREFIX, bindingSourceLabel, bindingSourceText } from '../lib/seat-skill-state.mjs';

// The literal texts frozen by ruling A1 (the amendment is the oracle, not the code).
const SRC_SCHEMA = 'Binding source: schema.';
const UNK = 'If the Skill tool answers Unknown skill, or you have no Skill tool, do not retry: stop and report the seat, the skill name and the binding source.';
const ADVISORY = '(advisory: nothing is refused)';
const SRC_DEFAULT = 'Binding source: plugin default.';
const SHIPPED_IMPL = 'task-orchestrator:implementation-standards';

const HINT = fileURLToPath(new URL('../seat-skill-hint.mjs', import.meta.url));
const GATE = fileURLToPath(new URL('../seat-skill-gate.mjs', import.meta.url));
const RECORD = fileURLToPath(new URL('../skill-record.mjs', import.meta.url));
const HOOKS_CONFIG = JSON.parse(readFileSync(fileURLToPath(new URL('../hooks-config.json', import.meta.url)), 'utf8'));

const READ_TOOL = 'mcp__mcp-task-orchestrator__items_read';
const ADVANCE_TOOL = 'mcp__mcp-task-orchestrator__advance';
const NOTES_WRITE = 'mcp__mcp-task-orchestrator__notes_write';
const IMPL = 'task-orchestrator:implementer';
const R = '11111111-1111-1111-1111-111111111111';
const BOOTSTRAP = `project:\n  rootId: ${R}\n`;
const U1 = '4a468f6c-9434-4cbf-97e8-b6c546c1b112';
const U2 = '5b579f7d-a545-4dae-a869-c7d657d205c3';

const tracked = [];
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});
function fresh(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix));
  tracked.push(d);
  return d;
}

/** A fixture: `dir` (cwd and AGENT_CONFIG_DIR, holds the bootstrap config) and `tmp` (TEMP for markers). */
function fixture() {
  const dir = fresh('to-seat-skill-');
  mkdirSync(join(dir, '.taskorchestrator'), { recursive: true });
  writeFileSync(join(dir, '.taskorchestrator', 'config.yaml'), BOOTSTRAP, 'utf-8');
  return { dir, tmp: fresh('to-seat-skill-tmp-') };
}

// ---- stub server ---------------------------------------------------------------------------------------

const okEnvelope = (data) => ({ status: 200, body: { ok: true, data, meta: { reqId: 'req00001' } } });
const errEnvelope = (status, code) => ({ status, body: { ok: false, error: { code, kind: 'permanent', message: `${code} fixture` }, meta: { reqId: 'req00002' } } });

const SEATS = (implSkills, extra = {}) => [
  { name: 'planner', phase: 'queue', ...(extra.planner ? { skills: extra.planner } : {}) },
  { name: 'implementer', phase: 'work', enters: true, ...(implSkills === undefined ? {} : { skills: implSkills }) },
  { name: 'reviewer', phase: 'review', ...(extra.reviewer ? { skills: extra.reviewer } : {}) },
];

/** The items_read schema view of a feature-task style type. */
const schemaView = ({ seats = SEATS(['p:a', 'p:b']), features = ['seats', 'dispatchBySeat', 'seatSkills'], notes = [] } = {}) =>
  okEnvelope({ type: 'feature-task', configFingerprint: 'fp', configSource: 'per-root', notes, seats, features });

/** Stub. `schema`: a reply or (itemId) => reply. `delays`: ms before answering per route. `rules`: the `config` op `rules` reply (absent: 404 not_found, a served read that fails). */
async function startStub({ schema = schemaView(), delays = {}, rules } = {}) {
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
      let reply = errEnvelope(404, 'not_found');
      let delay = 0;
      if (req.method === 'POST' && req.url === '/api/v1/ops/items_read' && body.select === 'schema') {
        reply = typeof schema === 'function' ? schema(body.itemId) : schema;
        delay = delays.schema ?? 0;
      }
      if (req.method === 'POST' && req.url === '/api/v1/ops/config' && body?.ops?.[0]?.op === 'rules') {
        reply = rules ?? errEnvelope(404, 'not_found');
        delay = delays.rules ?? 0;
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

// ---- runner and payload builders -------------------------------------------------------------------------

/** extra: {apiUrl, env, tmp}. `tmp` overrides TEMP (for the unwritable-marker-dir case). */
function run(hook, payload, fx, extra = {}) {
  return new Promise((resolve, reject) => {
    const tmp = extra.tmp ?? fx.tmp;
    const env = { ...process.env, TEMP: tmp, TMP: tmp, TMPDIR: tmp, TASK_ORCHESTRATOR_HOME: join(fx.dir, 'to-home-empty'), TASK_ORCHESTRATOR_CEILING: fx.dir, AGENT_CONFIG_DIR: fx.dir };
    delete env.TASK_ORCHESTRATOR_API_URL;
    delete env.TASK_ORCHESTRATOR_API_TOKEN;
    delete env.TASK_ORCHESTRATOR_MODE;
    delete env.CLAUDE_PROJECT_DIR;
    if (extra.apiUrl) env.TASK_ORCHESTRATOR_API_URL = extra.apiUrl;
    Object.assign(env, extra.env ?? {});
    const started = Date.now();
    const child = spawn(process.execPath, [hook], { env, cwd: fx.dir });
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
    child.stdin.end(typeof payload === 'string' ? payload : JSON.stringify(payload));
  });
}

const parse = (res) => (res.stdout.trim() === '' ? {} : JSON.parse(res.stdout));
const isEmptyOut = (res) => res.stdout.trim() === '' || res.stdout.trim() === '{}';
const isDeny = (res) => parse(res).hookSpecificOutput?.permissionDecision === 'deny' || parse(res).decision === 'block';
const denyReason = (res) => parse(res).hookSpecificOutput?.permissionDecisionReason ?? '';
const contextOf = (res) => parse(res).hookSpecificOutput?.additionalContext ?? '';

const sid = (name) => `${name}-${randomUUID()}`;

function ctxResponse(role, callerSeat) {
  return {
    content: [{ type: 'text', text: 'ok' }],
    structuredContent: { ok: true, data: { item: { id: 'c0ffee00', title: 'Widget', role }, ...(callerSeat ? { callerSeat } : {}) }, meta: { reqId: 'req00001' } },
  };
}

/** An items_read context call as PostToolUse input. agentId null = main session. */
function ctxCall(session, { agentId = 'agent-1', agentType = IMPL, itemId = U1, role = 'work', callerSeat = 'implementer' } = {}) {
  return {
    session_id: session,
    ...(agentId ? { agent_id: agentId } : {}),
    ...(agentType ? { agent_type: agentType } : {}),
    tool_name: READ_TOOL,
    tool_input: { select: 'context', itemId },
    tool_response: ctxResponse(role, callerSeat),
  };
}

/** An advance call as PostToolUse input; `element` is the data.results[0] entry. */
function advCall(session, element, { agentId = 'agent-1', agentType = IMPL, itemId = U1, seatToken } = {}) {
  return {
    session_id: session,
    ...(agentId ? { agent_id: agentId } : {}),
    ...(agentType ? { agent_type: agentType } : {}),
    tool_name: ADVANCE_TOOL,
    tool_input: { ops: [{ op: 'transition', itemId, trigger: 'start' }], ...(seatToken ? { seatToken } : {}) },
    tool_response: {
      content: [{ type: 'text', text: 'ok' }],
      structuredContent: { ok: true, data: { results: [element], summary: { ok: 1, failed: 0 } }, meta: { reqId: 'req00002' } },
    },
  };
}

const skillCall = (session, agentId, skill, { commandName, success = true } = {}) => ({
  session_id: session,
  ...(agentId ? { agent_id: agentId } : {}),
  tool_name: 'Skill',
  tool_input: { skill },
  tool_response: { success, ...(commandName ? { commandName } : {}) },
});

const toolCall = (session, agentId, tool_name, tool_input, agentType = IMPL) => ({
  session_id: session,
  ...(agentId ? { agent_id: agentId } : {}),
  ...(agentType ? { agent_type: agentType } : {}),
  tool_name,
  tool_input,
});
const editCall = (session, agentId, tool = 'Edit') =>
  toolCall(session, agentId, tool, tool === 'NotebookEdit' ? { notebook_path: '/x/n.ipynb', new_source: 'a' } : { file_path: '/x/a.txt', old_string: 'a', new_string: 'b', content: 'b' });
const upsertCall = (session, agentId, role, itemId = U1) =>
  toolCall(session, agentId, NOTES_WRITE, { ops: [{ op: 'upsert', itemId, key: 'some-note', role, body: 'b'.repeat(300) }] });

/** Runs the hint once for an agent so that its seat record exists. */
const boot = (server, fx, session, o = {}) => run(HINT, ctxCall(session, o), fx, { apiUrl: server.origin });
const record = (fx, session, agent, skill, o) => run(RECORD, skillCall(session, agent, skill, o), fx);
const gate = (fx, payload, extra = {}) => run(extra.gatePath ?? GATE, payload, fx, extra);

// ---- S1 / S2 / S3: record, hint, refuse (AC1, AC3) -------------------------------------------------------

test('S1: hint names the seat and both skills, Edit is denied until both Skill calls are recorded, then allowed silently', async () => {
  await withStub({}, async (server, fx) => {
    const s = sid('s1');
    const hint = await boot(server, fx, s);
    assert.equal(hint.status, 0, hint.stderr);
    const text = contextOf(hint);
    assert.ok(text.startsWith(SEAT_SKILLS_PREFIX), text);
    assert.ok(text.includes('implementer'), text);
    assert.ok(text.indexOf('p:a') !== -1 && text.indexOf('p:a') < text.indexOf('p:b'), text);
    assert.ok(text.includes(SRC_SCHEMA), text);
    assert.ok(text.includes(UNK), text);
    assert.ok(!text.includes('advisory: nothing is refused'), 'an enforcing hint is not advisory');

    const denied = await gate(fx, editCall(s, 'agent-1'));
    assert.equal(denied.status, 0);
    assert.ok(isDeny(denied), denied.stdout);
    const reason = denyReason(denied);
    assert.ok(reason.startsWith(SEAT_SKILLS_REQUIRED_PREFIX), reason);
    assert.ok(reason.includes('implementer'), reason);
    assert.ok(reason.includes('"p:a"') && reason.includes('"p:b"'), reason);
    assert.ok(reason.includes(SRC_SCHEMA), reason);
    assert.ok(reason.includes(UNK), reason);

    // While a required skill is missing every gated call is denied (A1: no release); recording both satisfies it.
    assert.equal((await record(fx, s, 'agent-1', 'p:a')).status, 0);
    assert.equal((await record(fx, s, 'agent-1', 'p:b')).status, 0);
    const allowed = await gate(fx, editCall(s, 'agent-1'));
    assert.equal(allowed.status, 0);
    assert.ok(isEmptyOut(allowed), allowed.stdout);
    assert.equal(parse(allowed).systemMessage, undefined);
  });
});

test('S2: a Skill call with success:false records nothing; a bare skill name with commandName p:a satisfies p:a', async () => {
  await withStub({ schema: schemaView({ seats: SEATS(['p:a']) }) }, async (server, fx) => {
    // Failed call: the Edit is still denied (twin below shows the same fixture allows once a successful call is recorded).
    const s = sid('s2a');
    await boot(server, fx, s);
    await record(fx, s, 'agent-1', 'p:a', { success: false, commandName: 'p:a' });
    assert.ok(isDeny(await gate(fx, editCall(s, 'agent-1'))), 'a failed Skill call must not satisfy the requirement');

    const t = sid('s2b');
    await boot(server, fx, t);
    await record(fx, t, 'agent-1', 'a', { commandName: 'p:a' });
    const ok = await gate(fx, editCall(t, 'agent-1'));
    assert.ok(isEmptyOut(ok), ok.stdout);
  });
});

test('S3: agent A\'s Skill record never satisfies agent B in the same session', async () => {
  await withStub({}, async (server, fx) => {
    const s = sid('s3');
    await boot(server, fx, s, { agentId: 'agent-A' });
    await boot(server, fx, s, { agentId: 'agent-B' });
    await record(fx, s, 'agent-A', 'p:a');
    await record(fx, s, 'agent-A', 'p:b');
    assert.ok(isEmptyOut(await gate(fx, editCall(s, 'agent-A'))), 'agent A is satisfied');
    const b = await gate(fx, editCall(s, 'agent-B'));
    assert.ok(isDeny(b), b.stdout);
    assert.ok(denyReason(b).includes('"p:a"'));
  });
});

test('S3 probe: two concurrent skill-record runs for one agent both land (no lost update)', async () => {
  await withStub({}, async (server, fx) => {
    const s = sid('s3c');
    await boot(server, fx, s);
    const [r1, r2] = await Promise.all([record(fx, s, 'agent-1', 'p:a'), record(fx, s, 'agent-1', 'p:b')]);
    assert.equal(r1.status, 0);
    assert.equal(r2.status, 0);
    assert.ok(isEmptyOut(await gate(fx, editCall(s, 'agent-1'))), 'both skills must be on record');
  });
});

test('S3 probe: a leading slash on the Skill name is stripped, and a skill recorded for the main session (no agent_id) satisfies nobody', async () => {
  await withStub({ schema: schemaView({ seats: SEATS(['p:a']) }) }, async (server, fx) => {
    const s = sid('s3s');
    await boot(server, fx, s);
    await record(fx, s, 'agent-1', '/p:a');
    assert.ok(isEmptyOut(await gate(fx, editCall(s, 'agent-1'))), 'leading slash stripped');

    const t = sid('s3m');
    await boot(server, fx, t);
    await record(fx, t, null, 'p:a');
    assert.ok(isDeny(await gate(fx, editCall(t, 'agent-1'))), 'a main-session Skill call is not an agent record');
  });
});

// ---- S4 / S5: the hint (AC2) -----------------------------------------------------------------------------

test('S4: the hint is given once per agent; an ok advance element with callerSeat hints, a gate_blocked one does not', async () => {
  await withStub({}, async (server, fx) => {
    const s = sid('s4');
    const first = await boot(server, fx, s);
    assert.ok(contextOf(first).startsWith(SEAT_SKILLS_PREFIX));
    const again = await boot(server, fx, s);
    assert.ok(isEmptyOut(again), `second read of the same agent: ${again.stdout}`);

    const viaAdvance = await run(HINT, advCall(s, { ok: true, itemId: U1, newRole: 'work', callerSeat: 'implementer' }, { agentId: 'agent-2' }), fx, { apiUrl: server.origin });
    assert.equal(viaAdvance.status, 0, viaAdvance.stderr);
    const text = contextOf(viaAdvance);
    assert.ok(text.startsWith(SEAT_SKILLS_PREFIX), text);
    assert.ok(text.indexOf('p:a') !== -1 && text.indexOf('p:a') < text.indexOf('p:b'), text);

    const blocked = await run(HINT, advCall(s, { ok: false, itemId: U1, error: { code: 'gate_blocked' } }, { agentId: 'agent-3' }), fx, { apiUrl: server.origin });
    assert.equal(blocked.status, 0);
    assert.ok(isEmptyOut(blocked), blocked.stdout);
  });
});

test('S4 probe: a short hex itemId on the read reaches the schema request as given', async () => {
  await withStub({}, async (server, fx) => {
    const res = await boot(server, fx, sid('s4h'), { itemId: 'c0ffee00' });
    assert.ok(contextOf(res).startsWith(SEAT_SKILLS_PREFIX), res.stdout);
    const reads = server.requests.filter((r) => r.url === '/api/v1/ops/items_read');
    assert.equal(reads.length, 1);
    assert.equal(reads[0].body.select, 'schema');
    assert.equal(reads[0].body.itemId, 'c0ffee00');
  });
});

test('S5: no callerSeat on a general-purpose agent gives no hint and no denial', async () => {
  await withStub({}, async (server, fx) => {
    const s = sid('s5a');
    const res = await boot(server, fx, s, { agentType: 'general-purpose', callerSeat: null });
    assert.equal(res.status, 0);
    assert.ok(isEmptyOut(res), res.stdout);
    assert.ok(isEmptyOut(await gate(fx, editCall(s, 'agent-1'))));
    // Twin: the same read with callerSeat hints.
    const twin = await boot(server, fx, sid('s5b'), { agentType: 'general-purpose', callerSeat: 'implementer' });
    assert.ok(contextOf(twin).startsWith(SEAT_SKILLS_PREFIX), twin.stdout);
  });
});

test('S5 (R6, D7, test-plan S3): a declared empty skills list (shipped hooks) and an absent list (a plugin copy with an empty defaults table) both give no SEAT SKILLS text, a SEAT RULES text listing the implementer keys, and no denial', async () => {
  const empty = pluginCopy({});
  const cases = [
    { seats: SEATS([]), hint: HINT, gatePath: GATE },
    { seats: SEATS(undefined), hint: empty.hint, gatePath: empty.gate },
  ];
  for (const { seats, hint, gatePath } of cases) {
    await withStub({ schema: schemaView({ seats }) }, async (server, fx) => {
      const s = sid('s5e');
      const res = await run(hint, ctxCall(s), fx, { apiUrl: server.origin });
      assert.equal(res.status, 0, res.stderr);
      const text = contextOf(res);
      // Oracle D7: the rules text is given whenever a role resolves, skills or not; with no skills there is no SEAT SKILLS text.
      assert.ok(text.startsWith('SEAT RULES:'), JSON.stringify(seats) + text);
      assert.equal(text.includes(SEAT_SKILLS_PREFIX), false, text);
      const at = (k) => text.indexOf(k);
      assert.ok(at('protocol.entry-seat') !== -1 && at('protocol.entry-seat') < at('commit-discipline') && at('commit-discipline') < at('closure-notes'), text);
      assert.equal(isDeny(await gate(fx, editCall(s, 'agent-1'), { gatePath })), false, 'the empty skills list refuses nothing');
    });
  }
});

// ---- S6 / S7: gate points (AC3) --------------------------------------------------------------------------

test('S6: Write and NotebookEdit are gated like Edit', async () => {
  await withStub({}, async (server, fx) => {
    for (const tool of ['Edit', 'Write', 'NotebookEdit']) {
      const s = sid(`s6-${tool}`);
      await boot(server, fx, s);
      const res = await gate(fx, editCall(s, 'agent-1', tool));
      assert.ok(isDeny(res), `${tool}: ${res.stdout}`);
      assert.ok(denyReason(res).startsWith(SEAT_SKILLS_REQUIRED_PREFIX), tool);
    }
  });
});

test('S6: agent_type workflow-subagent with a callerSeat is gated; without a callerSeat it is not', async () => {
  await withStub({}, async (server, fx) => {
    const s = sid('s6w');
    await boot(server, fx, s, { agentType: 'workflow-subagent', callerSeat: 'implementer' });
    const res = await gate(fx, editCall(s, 'agent-1'));
    assert.ok(isDeny(res), res.stdout);
    const t = sid('s6x');
    await boot(server, fx, t, { agentType: 'workflow-subagent', callerSeat: null });
    assert.ok(isEmptyOut(await gate(fx, editCall(t, 'agent-1'))));
  });
});

test('S7: a reviewer is not gated at Edit; its first review-role note upsert (any casing) is, a work-role upsert is not', async () => {
  const seats = SEATS(['p:a'], { reviewer: ['r:x'], planner: ['q:plan'] });
  await withStub({ schema: schemaView({ seats }) }, async (server, fx) => {
    const rev = { agentType: 'task-orchestrator:reviewer', role: 'review', callerSeat: 'reviewer' };
    const s = sid('s7a');
    await boot(server, fx, s, rev);
    assert.ok(isEmptyOut(await gate(fx, editCall(s, 'agent-1'))), 'Edit is allowed for a reviewer');
    assert.ok(isEmptyOut(await gate(fx, upsertCall(s, 'agent-1', 'work'))), 'a work-role upsert is not the reviewer gate point');
    const denied = await gate(fx, upsertCall(s, 'agent-1', 'review'));
    assert.ok(isDeny(denied), denied.stdout);
    assert.ok(denyReason(denied).startsWith(SEAT_SKILLS_REQUIRED_PREFIX));
    assert.ok(denyReason(denied).includes('"r:x"'));

    const t = sid('s7b');
    await boot(server, fx, t, rev);
    const upper = await gate(fx, upsertCall(t, 'agent-1', 'REVIEW'));
    assert.ok(isDeny(upper), `role casing: ${upper.stdout}`);
  });
});

test('S7: a planner is gated at a queue-role note write, not at Edit', async () => {
  const seats = SEATS(['p:a'], { planner: ['q:plan'] });
  await withStub({ schema: schemaView({ seats }) }, async (server, fx) => {
    const s = sid('s7p');
    await boot(server, fx, s, { agentType: 'task-orchestrator:planner', role: 'queue', callerSeat: 'planner' });
    assert.ok(isEmptyOut(await gate(fx, editCall(s, 'agent-1'))));
    const res = await gate(fx, upsertCall(s, 'agent-1', 'queue'));
    assert.ok(isDeny(res), res.stdout);
    assert.ok(denyReason(res).includes('"q:plan"'));
    assert.ok(denyReason(res).includes('planner'));
  });
});

// ---- S11 / S12 / S13: main session and fail-open (AC5) ----------------------------------------------------

test('S11: the main session is never denied; an ok advance hints once per item and role, and not with a seatToken', async () => {
  await withStub({}, async (server, fx) => {
    const s = sid('s11');
    assert.ok(isEmptyOut(await gate(fx, editCall(s, null, 'Edit'))));
    assert.ok(isEmptyOut(await gate(fx, upsertCall(s, null, 'work'))));
    const el = { ok: true, itemId: U1, newRole: 'work' };
    const first = await run(HINT, advCall(s, el, { agentId: null, agentType: null }), fx, { apiUrl: server.origin });
    assert.equal(first.status, 0, first.stderr);
    assert.ok(contextOf(first).startsWith(SEAT_SKILLS_PREFIX), first.stdout);
    const second = await run(HINT, advCall(s, el, { agentId: null, agentType: null }), fx, { apiUrl: server.origin });
    assert.ok(isEmptyOut(second), `same item and role again: ${second.stdout}`);
    // A different role of the same item hints again; a different item hints again.
    const review = await run(HINT, advCall(s, { ok: true, itemId: U1, newRole: 'review' }, { agentId: null, agentType: null }), fx, { apiUrl: server.origin });
    assert.equal(review.status, 0);
    const other = await run(HINT, advCall(s, { ok: true, itemId: U2, newRole: 'work' }, { agentId: null, agentType: null, itemId: U2 }), fx, { apiUrl: server.origin });
    assert.ok(contextOf(other).startsWith(SEAT_SKILLS_PREFIX), other.stdout);
    // With a seatToken the session is a coordinator or seat: nothing.
    const tokened = await run(HINT, advCall(sid('s11t'), el, { agentId: null, agentType: null, seatToken: 'x'.repeat(20) }), fx, { apiUrl: server.origin });
    assert.ok(isEmptyOut(tokened), tokened.stdout);
  });
});

test('S12: with no REST URL the hint is empty and nothing is denied', async () => {
  await withStub({}, async (_server, fx) => {
    const s = sid('s12');
    const hint = await run(HINT, ctxCall(s), fx);
    assert.equal(hint.status, 0);
    assert.ok(isEmptyOut(hint), hint.stdout);
    assert.ok(isEmptyOut(await gate(fx, editCall(s, 'agent-1'))));
    assert.ok(isEmptyOut(await gate(fx, upsertCall(s, 'agent-1', 'work'))));
  });
});

test('S13: a server without seatSkills (seats carry skills) ignores the declared list: the hint names the shipped default as advisory and nothing is denied', async () => {
  await withStub({ schema: schemaView({ features: ['seats', 'dispatchBySeat'] }) }, async (server, fx) => {
    const s = sid('s13');
    const res = await boot(server, fx, s);
    assert.equal(res.status, 0, res.stderr);
    const text = contextOf(res);
    assert.ok(text.startsWith(SEAT_SKILLS_PREFIX), text);
    assert.ok(text.includes(`"${SHIPPED_IMPL}"`), text);
    assert.ok(text.includes(SRC_DEFAULT), text);
    assert.ok(text.includes(ADVISORY), text);
    assert.ok(!text.includes('p:a'), 'the declared list is ignored');
    assert.ok(!isDeny(await gate(fx, editCall(s, 'agent-1'))));
  });
});

// ---- S14: no release (AC5, ruling A1) --------------------------------------------------------------------

test('S14: a skill that never loads is denied on every attempt, with the source and the stop-and-report text, and no release message', async () => {
  await withStub({ schema: schemaView({ seats: SEATS(['missing-skill']) }) }, async (server, fx) => {
    const s = sid('s14');
    await boot(server, fx, s);
    for (const attempt of [1, 2, 3]) {
      const res = await gate(fx, editCall(s, 'agent-1'));
      assert.equal(res.status, 0, `attempt ${attempt}`);
      assert.ok(isDeny(res), `attempt ${attempt}: ${res.stdout}`);
      const reason = denyReason(res);
      assert.ok(reason.startsWith(SEAT_SKILLS_REQUIRED_PREFIX), reason);
      assert.ok(reason.includes('"missing-skill"'), reason);
      assert.ok(reason.includes(SRC_SCHEMA), reason);
      assert.ok(reason.includes(UNK), reason);
      assert.equal(parse(res).systemMessage, undefined, `attempt ${attempt}: ${res.stdout}`);
      assert.ok(!res.stdout.includes('proceeding without'), res.stdout);
    }
  });
});

test('S14 twin: once the skill is recorded the same call is allowed silently, so the denials above are the missing skill', async () => {
  await withStub({ schema: schemaView({ seats: SEATS(['missing-skill']) }) }, async (server, fx) => {
    const s = sid('s14t');
    await boot(server, fx, s);
    assert.ok(isDeny(await gate(fx, editCall(s, 'agent-1'))));
    assert.equal((await record(fx, s, 'agent-1', 'missing-skill')).status, 0);
    const res = await gate(fx, editCall(s, 'agent-1'));
    assert.ok(isEmptyOut(res), res.stdout);
  });
});

// ---- S25: plugin-default binding is advisory without seatSkills (ruling A2, O4) ---------------------------

/**
 * A throwaway copy of the plugin (hooks/, scripts/ and .claude-plugin/, without their tests/ folders) whose hooks/lib/seat-skill-defaults.json
 * holds `defaults`. The shipped table is never touched. The copied files are never opened by this test, only run.
 */
function pluginCopy(defaults) {
  const root = fresh('to-seat-skill-plugin-');
  const pluginRoot = fileURLToPath(new URL('../../', import.meta.url));
  for (const part of ['hooks', 'scripts', '.claude-plugin']) {
    const from = join(pluginRoot, part);
    if (!existsSync(from)) continue;
    cpSync(from, join(root, part), { recursive: true, filter: (src) => relative(from, src).split(sep)[0] !== 'tests' });
  }
  writeFileSync(join(root, 'hooks', 'lib', 'seat-skill-defaults.json'), JSON.stringify(defaults), 'utf-8');
  return { hint: join(root, 'hooks', 'seat-skill-hint.mjs'), gate: join(root, 'hooks', 'seat-skill-gate.mjs') };
}

test('S25: a plugin-default seat binding on a server without seatSkills is advisory (hint says so, Edit, Write and NotebookEdit are never denied); with seatSkills it is enforced', async () => {
  const copy = pluginCopy({ implementer: ['p:a'] });
  const seats = SEATS(undefined); // the implementer seat declares no skills key, so the plugin default binds
  await withStub({ schema: schemaView({ seats, features: ['seats', 'dispatchBySeat'] }) }, async (server, fx) => {
    const s = sid('s25');
    const hint = await run(copy.hint, ctxCall(s), fx, { apiUrl: server.origin });
    assert.equal(hint.status, 0, hint.stderr);
    const text = contextOf(hint);
    assert.ok(text.startsWith(SEAT_SKILLS_PREFIX), text);
    assert.ok(text.includes('"p:a"'), text);
    assert.ok(text.includes(SRC_DEFAULT), text);
    assert.ok(text.includes(UNK), text);
    assert.ok(text.includes(ADVISORY), text);
    for (const attempt of [1, 2, 3]) {
      for (const tool of ['Edit', 'Write', 'NotebookEdit']) {
        const res = await gate(fx, editCall(s, 'agent-1', tool), { gatePath: copy.gate });
        assert.equal(res.status, 0, `${tool} attempt ${attempt}: ${res.stderr}`);
        assert.ok(!isDeny(res), `${tool} attempt ${attempt}: ${res.stdout}`);
      }
    }
  });
  // Twin: the same copy and defaults with seatSkills among the features hints without the advisory suffix and denies Edit.
  await withStub({ schema: schemaView({ seats, features: ['seats', 'dispatchBySeat', 'seatSkills'] }) }, async (server, fx) => {
    const s = sid('s25t');
    const hint = await run(copy.hint, ctxCall(s), fx, { apiUrl: server.origin });
    const text = contextOf(hint);
    assert.ok(text.startsWith(SEAT_SKILLS_PREFIX), text);
    assert.ok(text.includes('"p:a"'), text);
    assert.ok(text.includes(SRC_DEFAULT), text);
    assert.ok(!text.includes(ADVISORY), text);
    const res = await gate(fx, editCall(s, 'agent-1'), { gatePath: copy.gate });
    assert.ok(isDeny(res), res.stdout);
    assert.ok(denyReason(res).startsWith(SEAT_SKILLS_REQUIRED_PREFIX), denyReason(res));
    assert.ok(denyReason(res).includes('"p:a"'), denyReason(res));
  });
});

test('S25 probe: with the shipped defaults table the same plugin-default fixture binds the shipped implementer skill, not p:a, so p:a above comes from the copied table', async () => {
  await withStub({ schema: schemaView({ seats: SEATS(undefined), features: ['seats', 'dispatchBySeat', 'seatSkills'] }) }, async (server, fx) => {
    const s = sid('s25n');
    const hint = await boot(server, fx, s);
    assert.equal(hint.status, 0, hint.stderr);
    const text = contextOf(hint);
    assert.ok(text.startsWith(SEAT_SKILLS_PREFIX), text);
    assert.ok(text.includes(`"${SHIPPED_IMPL}"`), text);
    assert.ok(text.includes(SRC_DEFAULT), text);
    assert.ok(!text.includes('p:a'), text);
    const res = await gate(fx, editCall(s, 'agent-1'));
    assert.ok(isDeny(res), res.stdout);
    assert.ok(denyReason(res).includes(`"${SHIPPED_IMPL}"`), denyReason(res));
  });
});

// ---- S26: an unwritable armed-probe path fails open (ruling A2, O6) ---------------------------------------

/** The armed-probe marker path of the fixture's marker root: <TEMP>/task-orchestrator/<session_id>/seat-skill-armed-<agent_id>.json. */
const armedPath = (fx, session, agent) => join(fx.tmp, 'task-orchestrator', session.replace(/[^a-zA-Z0-9-]/g, '_'), `seat-skill-armed-${agent.replace(/[^a-zA-Z0-9-]/g, '_')}.json`);

test('S26: when the armed-probe path cannot be written (a non-empty directory sits there) the Edit is not denied; a fresh session is', async () => {
  await withStub({}, async (server, fx) => {
    const s = sid('s26');
    const boots = await boot(server, fx, s);
    assert.ok(contextOf(boots).startsWith(SEAT_SKILLS_PREFIX), boots.stdout);
    // The enforcing record exists and p:a is missing. Make the probe unwritable: a directory with content at the probe path.
    const probe = armedPath(fx, s, 'agent-1');
    rmSync(probe, { recursive: true, force: true });
    mkdirSync(join(probe, 'keep'), { recursive: true });
    const res = await gate(fx, editCall(s, 'agent-1'));
    assert.equal(res.status, 0, res.stderr);
    assert.ok(!isDeny(res), res.stdout);

    const t = sid('s26w');
    await boot(server, fx, t);
    const twin = await gate(fx, editCall(t, 'agent-1'));
    assert.ok(isDeny(twin), `twin with a writable probe path: ${twin.stdout}`);
  });
});

// ---- S21: binding source labels (ruling A1) ---------------------------------------------------------------

test('S21: bindingSourceLabel maps declared to schema, default to plugin default, anything else to null; bindingSourceText names the label', () => {
  assert.equal(bindingSourceLabel('declared'), 'schema');
  assert.equal(bindingSourceLabel('default'), 'plugin default');
  for (const other of ['none', '', undefined, null, 'Declared', 'schema']) assert.equal(bindingSourceLabel(other), null, String(other));
  assert.equal(bindingSourceText('schema'), 'Binding source: schema.');
  assert.equal(bindingSourceText('plugin default'), 'Binding source: plugin default.');
});

// ---- S15: preloaded through agent frontmatter (AC5) -------------------------------------------------------

const FRONTMATTER = {
  inline: '---\nname: implementer\nskills: p:a, p:b\n---\nBody\n',
  flow: '---\nname: implementer\nskills: [p:a, p:b]\n---\nBody\n',
  block: '---\nname: implementer\nskills:\n  - p:a\n  - p:b\n---\nBody\n',
};

test('S15: project agent frontmatter skills (inline, flow, block) satisfy the seat skills; without the file the same call is denied', async () => {
  await withStub({}, async (server, fx) => {
    const twin = sid('s15t');
    await run(HINT, ctxCall(twin, { agentType: 'implementer' }), fx, { apiUrl: server.origin, env: { CLAUDE_PROJECT_DIR: fx.dir } });
    assert.ok(isDeny(await gate(fx, editCall(twin, 'agent-1'), { env: { CLAUDE_PROJECT_DIR: fx.dir } })), 'twin without an agent file is denied');

    for (const [form, text] of Object.entries(FRONTMATTER)) {
      mkdirSync(join(fx.dir, '.claude', 'agents'), { recursive: true });
      writeFileSync(join(fx.dir, '.claude', 'agents', 'implementer.md'), text, 'utf-8');
      const s = sid(`s15-${form}`);
      const env = { CLAUDE_PROJECT_DIR: fx.dir };
      const hint = await run(HINT, ctxCall(s, { agentType: 'implementer' }), fx, { apiUrl: server.origin, env });
      // Skills satisfied through the frontmatter: no SEAT SKILLS text; the rules text is still given (D7).
      assert.equal(contextOf(hint).includes(SEAT_SKILLS_PREFIX), false, `${form}: ${hint.stdout}`);
      assert.ok(contextOf(hint).startsWith('SEAT RULES:'), `${form}: ${hint.stdout}`);
      const res = await gate(fx, editCall(s, 'agent-1'), { env });
      assert.ok(isEmptyOut(res), `${form}: ${res.stdout}`);
    }
  });
});

// ---- S16: bad input and unwritable markers (AC5) ----------------------------------------------------------

test('S16: bad stdin makes every new hook exit 0 without a denial', async () => {
  await withStub({}, async (server, fx) => {
    for (const hook of [HINT, GATE, RECORD]) {
      for (const input of ['', 'not json', '[]', '{"session_id":1,"agent_id":2,"tool_name":{}}', 'null']) {
        const res = await run(hook, input, fx, { apiUrl: server.origin });
        assert.equal(res.status, 0, `${hook} <- ${JSON.stringify(input)}: ${res.stderr}`);
        assert.ok(!isDeny(res), `${hook} <- ${JSON.stringify(input)}`);
      }
    }
  });
});

test('S16: TEMP pointing at a file (unwritable marker root) fails open; a writable twin denies', async () => {
  await withStub({}, async (server, fx) => {
    const file = join(fx.dir, 'not-a-dir');
    writeFileSync(file, 'x', 'utf-8');
    const s = sid('s16');
    const hint = await run(HINT, ctxCall(s), fx, { apiUrl: server.origin, tmp: file });
    assert.equal(hint.status, 0, hint.stderr);
    const gated = await run(GATE, editCall(s, 'agent-1'), fx, { tmp: file });
    assert.equal(gated.status, 0, gated.stderr);
    assert.ok(!isDeny(gated), gated.stdout);
    const rec = await run(RECORD, skillCall(s, 'agent-1', 'p:a'), fx, { tmp: file });
    assert.equal(rec.status, 0, rec.stderr);

    const t = sid('s16w');
    await boot(server, fx, t);
    assert.ok(isDeny(await gate(fx, editCall(t, 'agent-1'))), 'twin with a writable TEMP is denied');
  });
});

// ---- S18: time budget (AC7) -------------------------------------------------------------------------------

test('S18: a hint whose schema read takes 1400 ms exits 0 well under the 5 s hook timeout', async () => {
  await withStub({ delays: { schema: 1400 } }, async (server, fx) => {
    const res = await boot(server, fx, sid('s18'));
    assert.equal(res.status, 0, res.stderr);
    assert.ok(res.ms < 5000, `took ${res.ms} ms`);
    assert.ok(contextOf(res).startsWith(SEAT_SKILLS_PREFIX), 'the read answered inside its own timeout, so the hint is given');
  });
});

test('S18: the gate and the record make no REST request even with a URL set', async () => {
  await withStub({}, async (server, fx) => {
    const s = sid('s18g');
    await run(GATE, editCall(s, 'agent-1'), fx, { apiUrl: server.origin });
    await run(GATE, upsertCall(s, 'agent-1', 'work'), fx, { apiUrl: server.origin });
    await run(RECORD, skillCall(s, 'agent-1', 'p:a'), fx, { apiUrl: server.origin });
    assert.equal(server.requests.length, 0, JSON.stringify(server.requests));
    // Twin: the hint, with the same URL, does make requests, so the zero above can fail. D7: the schema read plus one served-rule-keys read.
    await boot(server, fx, s);
    assert.equal(server.requests.length, 2, JSON.stringify(server.requests.map((r) => [r.url, r.body.select ?? r.body?.ops?.[0]?.op])));
    assert.equal(server.requests.filter((r) => r.url === '/api/v1/ops/items_read' && r.body.select === 'schema').length, 1);
    assert.equal(server.requests.filter((r) => r.url === '/api/v1/ops/config' && r.body?.ops?.[0]?.op === 'rules').length, 1);
  });
});

// ---- S20: registration (AC9) ------------------------------------------------------------------------------

function entriesWith(event, script) {
  return (HOOKS_CONFIG.hooks[event] ?? []).filter((e) => e.hooks.some((h) => h.command.includes(script)));
}

test('S20: skill-record is registered on PostToolUse matcher Skill, timeout 5', () => {
  const entries = entriesWith('PostToolUse', 'skill-record.mjs');
  assert.equal(entries.length, 1);
  assert.equal(entries[0].matcher, 'Skill');
  const def = entries[0].hooks.find((h) => h.command.includes('skill-record.mjs'));
  assert.equal(def.timeout, 5);
  assert.equal('onFailure' in def, false);
});

test('S20: seat-skill-hint sits in the advance|items_read PostToolUse group beside phase-guard-record, timeout 5', () => {
  const entries = entriesWith('PostToolUse', 'seat-skill-hint.mjs');
  assert.equal(entries.length, 1);
  assert.equal(entries[0].matcher, orchestratorToolMatcher(['advance', 'items_read']));
  assert.ok(entries[0].hooks.some((h) => h.command.includes('phase-guard-record.mjs')));
  const def = entries[0].hooks.find((h) => h.command.includes('seat-skill-hint.mjs'));
  assert.equal(def.timeout, 5);
  assert.equal('onFailure' in def, false);
});

test('S20: seat-skill-gate is on PreToolUse Edit|Write|NotebookEdit and in the notes_write group beside skill-enforcement, timeout 5', () => {
  const entries = entriesWith('PreToolUse', 'seat-skill-gate.mjs');
  const matchers = entries.map((e) => e.matcher).sort();
  assert.deepEqual(matchers, ['Edit|Write|NotebookEdit', orchestratorToolMatcher(['notes_write'])].sort());
  const notes = entries.find((e) => e.matcher === orchestratorToolMatcher(['notes_write']));
  assert.ok(notes.hooks.some((h) => h.command.includes('skill-enforcement.mjs')));
  for (const e of entries) {
    const def = e.hooks.find((h) => h.command.includes('seat-skill-gate.mjs'));
    assert.equal(def.timeout, 5);
    assert.equal('onFailure' in def, false);
  }
});
