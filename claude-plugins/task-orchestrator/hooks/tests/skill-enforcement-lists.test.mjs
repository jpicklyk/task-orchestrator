// Item 79a61aba (U1): the skill-enforcement PreToolUse hook suggests EVERY skill of a note entry that names several,
// in the entry's order. Scenario S15 of the frozen test-plan (blind test-author seat; no production file opened).
//
// Oracle: the frozen task-scope "Decision" clause: an entry's `skill` is its FIRST skill and `skills` (wire member, present
// only with two or more) lists them all in invocation order; the effective-config view (cached once per session) and the
// per-item schema view (items_read schema) both carry the members. The hook's advisory must name skill a before skill b.
// The harness is the one skill-enforcement.test.mjs documents (an in-process node:http stub plays the server; the hook runs
// as a subprocess). Wire shape of the stubbed views: the DTO members `skill`, `skills` of the declared entry shape.
//
// Ambiguity, self-resolved from public evidence (test harness + declared DTO): how a cached marker written by an older
// hook version looks is not public, so the "cached string value" half of S15 is covered by a SINGLE-skill entry (a plain
// string `skill` and no `skills` member) which must still suggest, not by hand-writing a marker.
//
// Fixture condition for each negative assertion: the paired list entry in the same stub does carry the second skill, so a
// hook that read only `skill` fails the positive assertion and a hook that always listed `skills` fails the negative one.
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

const HOOK = fileURLToPath(new URL('../skill-enforcement.mjs', import.meta.url));
const TOOL = 'mcp__mcp-task-orchestrator__notes_write';
const ROOT_ID = '11111111-1111-1111-1111-111111111111';
const BOOTSTRAP = `project:\n  rootId: ${ROOT_ID}\n`;
const ITEM = 'aaaaaaaa-1111-2222-3333-444444444401';

const LIST_NOTE = { key: 'multi-note', role: 'review', required: true, description: 'Two skills', skill: 'rule-a', skills: ['rule-a', 'rule-b'], skillMode: 'method-on-diff' };
const REV_NOTE = { key: 'rev-note', role: 'review', required: true, description: 'Reordered', skill: 'rule-b', skills: ['rule-b', 'rule-a'] };
const SOLO_NOTE = { key: 'solo-note', role: 'review', required: true, description: 'One skill', skill: 'rule-c' };

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
  const dir = fresh('to-skill-lists-');
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), BOOTSTRAP, 'utf-8');
  return { dir, tmp: fresh('to-skill-lists-tmp-') };
}

const okEnvelope = (data) => ({ status: 200, body: { ok: true, data, meta: { reqId: 'req00001' } } });
const effectiveReply = (notes) =>
  okEnvelope({ results: [{ ok: true, schemas: [{ type: 'test-schema', notes }], traits: [], types: ['test-schema'], statusGraph: {} }], summary: { ok: 1, failed: 0 } });
const errEnvelope = (status, code) => ({ status, body: { ok: false, error: { code, kind: 'permanent', message: code }, meta: { reqId: 'req00002' } } });

async function startStub({ effective, views = {} }) {
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
      let reply;
      if (req.method === 'POST' && req.url === '/api/v1/ops/config' && body.ops?.[0]?.op === 'effective') reply = effective;
      else if (req.method === 'POST' && req.url === '/api/v1/ops/items_read' && body.select === 'schema') reply = views[body.itemId] ?? errEnvelope(404, 'not_found');
      else reply = errEnvelope(404, 'not_found');
      res.writeHead(reply.status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(reply.body));
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  server.origin = `http://127.0.0.1:${server.address().port}`;
  return server;
}
const stopStub = (server) => new Promise((res) => server.close(res));

function runHook(dir, tmp, origin, payload) {
  return new Promise((resolve, reject) => {
    const env = { ...process.env, TASK_ORCHESTRATOR_HOME: fresh('to-skill-lists-home-'), TASK_ORCHESTRATOR_API_URL: origin, AGENT_CONFIG_DIR: dir, TEMP: tmp, TMP: tmp, TMPDIR: tmp };
    delete env.TASK_ORCHESTRATOR_MODE;
    delete env.TASK_ORCHESTRATOR_API_TOKEN;
    const child = spawn(process.execPath, [HOOK], { env, cwd: dir });
    let stdout = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.on('error', reject);
    child.on('close', (status) => resolve({ status, stdout }));
    child.stdin.end(JSON.stringify(payload));
  });
}

const payload = (session, itemId, key) => ({
  session_id: session,
  tool_name: TOOL,
  tool_input: { ops: [{ op: 'upsert', itemId, key, role: 'review', body: 'too short' }] },
});
const advisory = (res) => {
  assert.equal(res.status, 0);
  return JSON.parse(res.stdout).hookSpecificOutput.additionalContext;
};

async function withStub(opts, body) {
  const server = await startStub(opts);
  const fx = fixture();
  try {
    await body(server, fx);
  } finally {
    await stopStub(server);
  }
}

test('S15: a thin body on a two-skill note suggests skill rule-a, then rule-b (effective view)', async () => {
  await withStub({ effective: effectiveReply([LIST_NOTE, REV_NOTE, SOLO_NOTE]) }, async (server, fx) => {
    const text = advisory(await runHook(fx.dir, fx.tmp, server.origin, payload(`s15-${randomUUID()}`, ITEM, 'multi-note')));
    assert.ok(text.includes('SKILL SUGGESTED'), text);
    assert.ok(text.includes('rule-a') && text.includes('rule-b'), `both skills named: ${text}`);
    assert.ok(text.indexOf('rule-a') < text.indexOf('rule-b'), `rule-a is named before rule-b: ${text}`);
  });
});

test('S15 probe: list order decides the order named ([rule-b, rule-a])', async () => {
  await withStub({ effective: effectiveReply([LIST_NOTE, REV_NOTE, SOLO_NOTE]) }, async (server, fx) => {
    const text = advisory(await runHook(fx.dir, fx.tmp, server.origin, payload(`s15-rev-${randomUUID()}`, ITEM, 'rev-note')));
    assert.ok(text.includes('rule-a') && text.includes('rule-b'), text);
    assert.ok(text.indexOf('rule-b') < text.indexOf('rule-a'), `rule-b is named before rule-a: ${text}`);
  });
});

test('S15: the per-item schema view also drives both skills, in order', async () => {
  const view = okEnvelope({ type: 'test-schema', notes: [LIST_NOTE] });
  await withStub({ effective: effectiveReply([SOLO_NOTE]), views: { [ITEM]: view } }, async (server, fx) => {
    const text = advisory(await runHook(fx.dir, fx.tmp, server.origin, payload(`s15-view-${randomUUID()}`, ITEM, 'multi-note')));
    assert.ok(text.includes('rule-a') && text.includes('rule-b'), text);
    assert.ok(text.indexOf('rule-a') < text.indexOf('rule-b'), text);
  });
});

test('S15: a single-skill note (string skill, no skills member) still suggests its skill and names no other', async () => {
  await withStub({ effective: effectiveReply([LIST_NOTE, SOLO_NOTE]) }, async (server, fx) => {
    const text = advisory(await runHook(fx.dir, fx.tmp, server.origin, payload(`s15-solo-${randomUUID()}`, ITEM, 'solo-note')));
    assert.ok(text.includes('SKILL SUGGESTED'), text);
    assert.ok(text.includes('rule-c'), text);
    assert.equal(text.includes('rule-a') || text.includes('rule-b'), false, `no foreign skill: ${text}`);
  });
});

test('S15: the cached facts of the first call still name both skills on a second call in the same session', async () => {
  await withStub({ effective: effectiveReply([LIST_NOTE, REV_NOTE]) }, async (server, fx) => {
    const session = `s15-cache-${randomUUID()}`;
    const first = advisory(await runHook(fx.dir, fx.tmp, server.origin, payload(session, ITEM, 'rev-note')));
    assert.ok(first.includes('rule-a') && first.includes('rule-b'), first);
    const second = advisory(await runHook(fx.dir, fx.tmp, server.origin, payload(session, 'bbbbbbbb-1111-2222-3333-444444444402', 'multi-note')));
    assert.ok(second.includes('rule-a') && second.includes('rule-b'), second);
    assert.ok(second.indexOf('rule-a') < second.indexOf('rule-b'), second);
  });
});
