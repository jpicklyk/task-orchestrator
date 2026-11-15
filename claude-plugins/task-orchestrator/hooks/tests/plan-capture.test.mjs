// plan-capture.mjs on the docs_write op (eb262d39 AC7, AC9). Oracle: current/docs/api-reference.md docs_write
// (upsert: {op, rootId, kind, slug, body, expectedVersion?}; omitting expectedVersion writes unconditionally;
// the plan slug grammar), Response envelope (a failed element of a batch is HTTP 207 ok:false partial_failure
// with data.results[i]), and the frozen task-scope AC7 / test-plan S8, S14.
// Spawned as a subprocess against an in-process stub REST server speaking POST /api/v1/ops/docs_write.
// Hermeticity recipe (Ruling 2): every spawn pins TASK_ORCHESTRATOR_HOME to its OWN empty temp dir, drops
// inherited config/API env, and runs with cwd = a fixture dir (never the repo).

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const HOOK = fileURLToPath(new URL('../plan-capture.mjs', import.meta.url));
const ROOT_ID = '11111111-2222-3333-4444-555555555555';
const NL = String.fromCharCode(10);
const PROJECT_CONFIG = ['project:', `  rootId: "${ROOT_ID}"`, '  name: "X"', ''].join(NL);
const PLAN_TEXT = '# My Great Plan' + NL + NL + 'body' + NL;
const PLAN = { tool_input: { plan: PLAN_TEXT } };
const META = { reqId: 'k7f3q9ab' };

const temps = [];
after(() => {
  for (const d of temps) rmSync(d, { recursive: true, force: true });
});
function mkTemp(prefix) {
  const d = mkdtempSync(join(tmpdir(), `pc-${prefix}-`));
  temps.push(d);
  return d;
}
function writeConfig(dir, text) {
  mkdirSync(join(dir, '.taskorchestrator'), { recursive: true });
  writeFileSync(join(dir, '.taskorchestrator', 'config.yaml'), text);
}

// ---- API envelope replies (api-reference.md "Response envelope") ----
const okReply = (slug) => ({
  status: 200,
  json: {
    ok: true,
    data: {
      results: [{ ok: true, id: '22222222-2222-4222-8222-222222222222', kind: 'plan', slug, version: 1, contentHash: 'a'.repeat(64), created: true }],
      summary: { ok: 1, failed: 0 },
    },
    meta: META,
  },
});
const elementFailReply = (code, detail) => ({
  status: 207,
  json: {
    ok: false,
    error: { code: 'partial_failure', kind: 'permanent', message: '1 of 1 failed' },
    data: { results: [{ ok: false, error: { code, kind: 'permanent', message: code, ...(detail ? { detail } : {}) } }], summary: { ok: 0, failed: 1 } },
    meta: META,
  },
});
const topFailReply = (status, code) => ({
  status,
  json: { ok: false, error: { code, kind: 'permanent', message: code }, meta: META },
});

/** `replyFor(call)` answers a docs_write call; every request is recorded with its parsed JSON body. */
async function withStub(replyFor, fn) {
  const requests = [];
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      let body = null;
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { /* stays null */ }
      const call = { method: req.method, url: req.url, headers: req.headers, body };
      requests.push(call);
      const reply = req.method === 'POST' && req.url === '/api/v1/ops/docs_write' && body
        ? replyFor(call)
        : { status: 404, json: { ok: false, error: { code: 'not_found', kind: 'permanent', message: 'no route' }, meta: META } };
      res.writeHead(reply.status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(reply.json));
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}`;
  try {
    return await fn(url, requests);
  } finally {
    if (typeof server.closeAllConnections === 'function') server.closeAllConnections();
    await new Promise((r) => server.close(r));
  }
}

function run({ cwd, home, url, plan = PLAN, token, ceiling }) {
  return new Promise((resolveP) => {
    const env = { ...process.env, TASK_ORCHESTRATOR_HOME: home };
    for (const k of ['AGENT_CONFIG_DIR', 'TASK_ORCHESTRATOR_MODE', 'TASK_ORCHESTRATOR_API_TOKEN', 'TASK_ORCHESTRATOR_API_URL', 'TASK_ORCHESTRATOR_CEILING']) delete env[k];
    if (url !== undefined) env.TASK_ORCHESTRATOR_API_URL = url;
    if (token) env.TASK_ORCHESTRATOR_API_TOKEN = token;
    if (ceiling) env.TASK_ORCHESTRATOR_CEILING = ceiling;
    const child = spawn(process.execPath, [HOOK], { env, cwd });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => { stdout += d; });
    child.stderr.on('data', (d) => { stderr += d; });
    child.on('close', (status) => resolveP({ status, stdout, stderr }));
    child.stdin.end(JSON.stringify(plan));
  });
}

const planOf = (text) => ({ tool_input: { plan: text } });

// a8b9ebfb S5-S6 (D1, D2): on a project-configured failure path the hook says why in ONE PostToolUse context line,
// exits 0, and never emits a decision. The WHOLE line is pinned, not its prefix. Oracle: the notStashed template
// from the dispatch declarations, `Task Orchestrator: plan not stashed (${why}). Materialize from the plan text in
// this conversation.`, plus the why each case must produce:
//   no plan text or readable plan file -> the literal 'no plan text or readable plan file in the hook input'
//   no REST API URL                    -> the literal 'no REST API URL configured'
//   a write that does not stash        -> `write failed: ${code}`, where code is the first of results[0].error.code,
//     then error.code of what callOp resolves, then `http ${status}`, then 'unknown'. callOp resolves a parsed
//     envelope with its data.results flattened to results (lib-api-client.test.mjs S13, S15), a refused or timed-out
//     connection and a non-envelope body as error.code 'client_error', and a 401 without an envelope as
//     'unauthenticated'. Each caller below states the code from the response its own fixture serves.
const NOT_STASHED = 'Task Orchestrator: plan not stashed';
const notStashedLine = (why) => `${NOT_STASHED} (${why}). Materialize from the plan text in this conversation.`;
function assertNotStashed(res, why) {
  assert.equal(typeof why, 'string', 'every not-stashed assertion pins its reason');
  assert.equal(res.status, 0, res.stderr);
  const out = JSON.parse(res.stdout);
  assert.deepEqual(Object.keys(out), ['hookSpecificOutput']);
  const h = out.hookSpecificOutput;
  assert.equal(h.hookEventName, 'PostToolUse');
  assert.ok(!('permissionDecision' in h) && !('decision' in out), 'a no-op never blocks or decides');
  assert.equal(typeof h.additionalContext, 'string');
  assert.equal(h.additionalContext, notStashedLine(why));
  assert.ok(!h.additionalContext.trim().includes(NL), 'one line');
}

// A plan slug must satisfy the docs_write upsert grammar (api-reference.md docs_write "upsert"): 1 to 200
// characters, no leading or trailing whitespace, no control characters, none of [ ] #, and not starting with rule/.
function assertValidPlanSlug(slug) {
  assert.equal(typeof slug, 'string');
  assert.ok(slug.length >= 1 && slug.length <= 200, `length ${slug.length}`);
  assert.equal(slug, slug.trim(), 'no leading or trailing whitespace');
  assert.ok(!/[\u0000-\u001f\u007f]/.test(slug), 'no control characters');
  assert.ok(!/[\[\]#]/.test(slug), 'none of [ ] #');
  assert.ok(!slug.startsWith('rule/'), 'a plan slug must not start with rule/');
}

test('S8 (AC7): project config with project.rootId - exactly one docs_write call, ops[0] an unconditional plan upsert, context names the slug', async () => {
  const cwd = mkTemp('proj');
  writeConfig(cwd, PROJECT_CONFIG);
  await withStub(() => okReply('my-great-plan'), async (url, requests) => {
    const res = await run({ cwd, home: mkTemp('home'), url });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].method, 'POST');
    assert.equal(requests[0].url, '/api/v1/ops/docs_write');
    assert.deepEqual(requests[0].body.ops, [{ op: 'upsert', rootId: ROOT_ID, kind: 'plan', slug: 'my-great-plan', body: PLAN_TEXT }]);
    assert.ok(!('expectedVersion' in requests[0].body.ops[0]), 'unconditional, as the 3.x PUT was');
    const ctx = JSON.parse(res.stdout).hookSpecificOutput.additionalContext;
    assert.ok(ctx.includes("plan stashed as plan document 'my-great-plan'"), ctx);
  });
});

test('S8 probe (AC7): the bearer token travels as an Authorization header', async () => {
  const cwd = mkTemp('tok');
  writeConfig(cwd, PROJECT_CONFIG);
  await withStub(() => okReply('my-great-plan'), async (url, requests) => {
    const res = await run({ cwd, home: mkTemp('home'), url, token: 'dummy-plan-token' });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].headers.authorization, 'Bearer dummy-plan-token');
  });
});

test('S14 (AC7): a failed element (207 duplicate, an adopted plan) - one not-stashed line naming the element code duplicate, exit 0, still the one call', async () => {
  const cwd = mkTemp('dup');
  writeConfig(cwd, PROJECT_CONFIG);
  await withStub(() => elementFailReply('duplicate', { kind: 'document', id: 'my-great-plan' }), async (url, requests) => {
    const res = await run({ cwd, home: mkTemp('home'), url });
    assert.equal(res.status, 0, res.stderr);
    // The fixture serves data.results[0].error.code 'duplicate'; the element code wins over the batch code partial_failure.
    assertNotStashed(res, 'write failed: duplicate');
    assert.equal(requests.length, 1);
  });
});

test('S14 (AC7): other element failures (forbidden write-docs, version_conflict, payload_too_large) - one not-stashed line naming each element code, exit 0', async () => {
  for (const [code, detail] of [
    ['forbidden', { scope: 'capability', required: 'write-docs' }],
    ['version_conflict', { kind: 'document', id: 'my-great-plan', expected: 1, actual: 2 }],
    ['payload_too_large', { max: 65536, actual: 70000 }],
  ]) {
    const cwd = mkTemp('fail');
    writeConfig(cwd, PROJECT_CONFIG);
    await withStub(() => elementFailReply(code, detail), async (url, requests) => {
      const res = await run({ cwd, home: mkTemp('home'), url });
      assert.equal(res.status, 0, `${code}: ${res.stderr}`);
      // The fixture serves data.results[0].error.code = code, so the reason is that code (underscores survive the sanitizer).
      assertNotStashed(res, `write failed: ${code}`);
      assert.equal(requests.length, 1, code);
    });
  }
});

test('S14 (AC7): 401 unauthenticated, 403 forbidden and 500 internal (top-level errors) - one not-stashed line naming the error code of the envelope, exit 0', async () => {
  for (const [status, code] of [[401, 'unauthenticated'], [403, 'forbidden'], [500, 'internal']]) {
    const cwd = mkTemp('top');
    writeConfig(cwd, PROJECT_CONFIG);
    await withStub(() => topFailReply(status, code), async (url, requests) => {
      const res = await run({ cwd, home: mkTemp('home'), url });
      assert.equal(res.status, 0, `${status}: ${res.stderr}`);
      // The fixture serves no data.results, so results is empty and the reason is error.code of the envelope, not `http ${status}`.
      assertNotStashed(res, `write failed: ${code}`);
      assert.equal(requests.length, 1, String(status));
    });
  }
});

test('S14 (AC7): an unreachable API - one not-stashed line naming client_error (a refused connection), exit 0', async () => {
  const cwd = mkTemp('unreach');
  writeConfig(cwd, PROJECT_CONFIG);
  const res = await run({ cwd, home: mkTemp('home'), url: 'http://127.0.0.1:1' });
  assert.equal(res.status, 0, res.stderr);
  // callOp resolves a refused connection as error.code client_error (reason network, no status).
  assertNotStashed(res, 'write failed: client_error');
});

test('S14 probe (AC7): a 200 body that is not an envelope (a 3.x server) - one not-stashed line naming client_error, exit 0', async () => {
  const cwd = mkTemp('legacy');
  writeConfig(cwd, PROJECT_CONFIG);
  await withStub(() => ({ status: 200, json: { slug: 'my-great-plan', status: 'pending' } }), async (url) => {
    const res = await run({ cwd, home: mkTemp('home'), url });
    assert.equal(res.status, 0, res.stderr);
    // callOp resolves a 200 body that is not an envelope as error.code client_error (reason bad_body).
    assertNotStashed(res, 'write failed: client_error');
  });
});

test('S14 probe (AC7): a 200 envelope whose results[0] is ok:false is not reported as stashed - one not-stashed line naming the element code internal', async () => {
  const cwd = mkTemp('okfalse');
  writeConfig(cwd, PROJECT_CONFIG);
  const reply = {
    status: 200,
    json: { ok: true, data: { results: [{ ok: false, error: { code: 'internal', kind: 'transient', message: 'x' } }], summary: { ok: 0, failed: 1 } }, meta: META },
  };
  await withStub(() => reply, async (url) => {
    const res = await run({ cwd, home: mkTemp('home'), url });
    assert.equal(res.status, 0, res.stderr);
    // The fixture serves data.results[0].error.code 'internal'.
    assertNotStashed(res, 'write failed: internal');
  });
});

test('user scope (config only in pinned home) -> no request, silent, exit 0', async () => {
  const home = mkTemp('uhome');
  writeConfig(home, PROJECT_CONFIG);
  await withStub(() => okReply('x'), async (url, requests) => {
    const res = await run({ cwd: mkTemp('empty'), home, url });
    assert.equal(res.status, 0);
    assert.equal(requests.length, 0);
    assert.equal(res.stdout, '');
  });
});

test('project config without a project block -> no request, silent', async () => {
  const cwd = mkTemp('noproj');
  writeConfig(cwd, 'retrospective:' + NL + '  mode: nudge' + NL);
  await withStub(() => okReply('x'), async (url, requests) => {
    const res = await run({ cwd, home: mkTemp('home'), url });
    assert.equal(res.status, 0);
    assert.equal(requests.length, 0);
    assert.equal(res.stdout, '');
  });
});

test('inline project: { rootId: abc } is block-only -> no request, silent', async () => {
  const cwd = mkTemp('inline');
  writeConfig(cwd, 'project: { rootId: abc }' + NL);
  await withStub(() => okReply('x'), async (url, requests) => {
    const res = await run({ cwd, home: mkTemp('home'), url });
    assert.equal(res.status, 0);
    assert.equal(requests.length, 0);
    assert.equal(res.stdout, '');
  });
});

test('no config anywhere -> no request, silent', async () => {
  await withStub(() => okReply('x'), async (url, requests) => {
    const res = await run({ cwd: mkTemp('empty'), home: mkTemp('home'), url });
    assert.equal(res.status, 0);
    assert.equal(requests.length, 0);
    assert.equal(res.stdout, '');
  });
});

test('importing the module performs no stdin read and exports no config parser', async () => {
  const mod = await import('../plan-capture.mjs');
  assert.equal(mod.parseRootId, undefined);
});

test('S18 (AC9): no API URL configured - one not-stashed line saying no REST API URL configured, exit 0; the same fixture sends once a URL resolves', async () => {
  const T = mkTemp('nourl');
  const cwd = join(T, 'proj');
  writeConfig(cwd, PROJECT_CONFIG);
  await withStub(() => okReply('my-great-plan'), async (url, requests) => {
    const withUrl = await run({ cwd, home: mkTemp('home'), url, ceiling: T });
    assert.equal(withUrl.status, 0, withUrl.stderr);
    assert.equal(requests.length, 1, 'fixture precondition: the config is found and a URL makes the hook call the API');
  });
  const without = await run({ cwd, home: mkTemp('home'), ceiling: T });
  assert.equal(without.status, 0, without.stderr);
  assertNotStashed(without, 'no REST API URL configured');
});

// ---- slug derivation: what reaches the server must satisfy the docs_write slug grammar ----

test('slug (AC7): a heading becomes the lowercase hyphenated slug; a long heading stays within MAX_SLUG_LENGTH (64)', async () => {
  const cwd = mkTemp('slug');
  writeConfig(cwd, PROJECT_CONFIG);
  await withStub((call) => okReply(call.body.ops[0].slug), async (url, requests) => {
    await run({ cwd, home: mkTemp('home'), url, plan: planOf('# ' + 'Very Long Heading '.repeat(20) + NL + NL + 'body' + NL) });
    assert.equal(requests.length, 1);
    const slug = requests[0].body.ops[0].slug;
    assertValidPlanSlug(slug);
    assert.ok(slug.length <= 64, `slug length ${slug.length}`);
    assert.ok(slug.startsWith('very-long-heading'), slug);
  });
});

test('slug (AC7): no heading -> plan-YYYYMMDD-HHMM in UTC', async () => {
  const cwd = mkTemp('noheading');
  writeConfig(cwd, PROJECT_CONFIG);
  const stamp = (d) => {
    const p = (n) => String(n).padStart(2, '0');
    return `plan-${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}-${p(d.getUTCHours())}${p(d.getUTCMinutes())}`;
  };
  await withStub((call) => okReply(call.body.ops[0].slug), async (url, requests) => {
    const before = new Date();
    await run({ cwd, home: mkTemp('home'), url, plan: planOf('Just some plan text without any heading.' + NL) });
    const after = new Date();
    assert.equal(requests.length, 1);
    const slug = requests[0].body.ops[0].slug;
    assert.match(slug, /^plan-\d{8}-\d{4}$/);
    assert.ok(slug === stamp(before) || slug === stamp(after), `${slug} vs ${stamp(before)} / ${stamp(after)}`);
  });
});

test('slug probe (AC7): hostile headings never produce a slug the server grammar would reject', async () => {
  const headings = [
    'rule/escape the plan facade',
    'Rule/Other',
    '[[bracketed]] # hash [x]',
    '   padded heading   ',
    'tabs\tand\u0001control',
    'x'.repeat(300),
  ];
  for (const h of headings) {
    const cwd = mkTemp('hostile');
    writeConfig(cwd, PROJECT_CONFIG);
    await withStub((call) => okReply(call.body.ops[0].slug), async (url, requests) => {
      await run({ cwd, home: mkTemp('home'), url, plan: planOf(`# ${h}` + NL + NL + 'body' + NL) });
      if (requests.length === 0) return; // an unusable heading may legitimately fall through to no write
      assert.equal(requests.length, 1, h);
      assertValidPlanSlug(requests[0].body.ops[0].slug);
    });
  }
});

// ---- 4e15b651 amendment A2: S47 hostile rootId never reaches a request ----
// Oracle: task-scope A2.4/A2.5 (a rootId failing ^[A-Za-z0-9][A-Za-z0-9-]{0,63}$ is "no rootId": the hook
// returns before any request and prints nothing); a UUID then yields exactly the one docs_write call carrying it.
// Hermetic: cwd in a scratch tree, ceiling at its parent, home pinned to an empty dir, fake server on loopback.

test('4e15b651 S47: plan-capture sends nothing for a hostile rootId, then exactly one docs_write for a UUID', async () => {
  const T = mkTemp('a2tree');
  const cwd = join(T, 'proj');
  const home = mkTemp('a2home');
  const UUID = '3f9a1c2e-5b7d-4e8f-9a0b-1c2d3e4f5a6b';
  writeConfig(cwd, ['project:', '  rootId: ../../../_cluster/settings?x=', '  name: "X"', ''].join(NL));
  await withStub(() => okReply('my-great-plan'), async (url, requests) => {
    const hostile = await run({ cwd, home, url, ceiling: T });
    assert.equal(hostile.status, 0);
    assert.equal(hostile.stdout, '');
    assert.equal(requests.length, 0, JSON.stringify(requests));
    writeConfig(cwd, ['project:', `  rootId: ${UUID}`, '  name: "X"', ''].join(NL));
    const ok = await run({ cwd, home, url, ceiling: T });
    assert.equal(ok.status, 0);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, '/api/v1/ops/docs_write');
    assert.equal(requests[0].body.ops[0].rootId, UUID);
  });
});

// ---- a8b9ebfb: the real ExitPlanMode PostToolUse shape (task-scope item 2) ----
// Oracle: task-scope item 2 (transcript of session 899f5f7e): tool_input {plan, planFilePath},
// tool_response {plan, isAgent, filePath, hasTaskTool}; AC2 (stash and report the slug; a no-op says why in one line).
// The stub reply echoes the slug the hook sent, so the context line is checked against what reached the server.

const REAL_SHAPE = (planFilePath, filePath) => ({
  tool_name: 'ExitPlanMode',
  tool_input: { plan: PLAN_TEXT, planFilePath },
  tool_response: { plan: PLAN_TEXT, isAgent: false, filePath, hasTaskTool: true },
});
const GONE = '/nonexistent/plan.md';
const echoSlug = (call) => okReply(call.body.ops[0].slug);

function assertStashed(res, requests, text = PLAN_TEXT) {
  assert.equal(res.status, 0, res.stderr);
  assert.equal(requests.length, 1);
  const op = requests[0].body.ops[0];
  assert.equal(op.op, 'upsert');
  assert.equal(op.kind, 'plan');
  assert.equal(op.rootId, ROOT_ID);
  assert.equal(op.body, text);
  const ctx = JSON.parse(res.stdout).hookSpecificOutput.additionalContext;
  assert.ok(ctx.includes(`plan stashed as plan document '${op.slug}'`), ctx);
  assert.ok(ctx.includes(ROOT_ID), ctx);
}

test('a8b9ebfb S2 (AC2): the full real shape stashes the plan once, body is the plan text, context names the slug', async () => {
  const cwd = mkTemp('real');
  writeConfig(cwd, PROJECT_CONFIG);
  await withStub(echoSlug, async (url, requests) => {
    const res = await run({ cwd, home: mkTemp('home'), url, plan: REAL_SHAPE(GONE, GONE) });
    assertStashed(res, requests);
    assert.equal(requests[0].body.ops[0].slug, 'my-great-plan');
  });
});

test('a8b9ebfb S3 (AC2): tool_input has only planFilePath, tool_response.plan carries the text -> stashed with that body', async () => {
  const cwd = mkTemp('respplan');
  writeConfig(cwd, PROJECT_CONFIG);
  const plan = { tool_name: 'ExitPlanMode', tool_input: { planFilePath: GONE }, tool_response: { plan: PLAN_TEXT, isAgent: false, filePath: GONE, hasTaskTool: true } };
  await withStub(echoSlug, async (url, requests) => {
    assertStashed(await run({ cwd, home: mkTemp('home'), url, plan }), requests);
  });
});

test('a8b9ebfb S4 (AC2): no plan text anywhere; the text is only in the file at tool_input.planFilePath, then only at tool_response.filePath', async () => {
  const cwd = mkTemp('file');
  writeConfig(cwd, PROJECT_CONFIG);
  const dir = mkTemp('planfiles');
  const viaInput = join(dir, 'via-input.md');
  const viaResponse = join(dir, 'via-response.md');
  writeFileSync(viaInput, PLAN_TEXT);
  writeFileSync(viaResponse, PLAN_TEXT);
  await withStub(echoSlug, async (url, requests) => {
    const a = await run({ cwd, home: mkTemp('home'), url, plan: { tool_name: 'ExitPlanMode', tool_input: { planFilePath: viaInput }, tool_response: { isAgent: false, hasTaskTool: true } } });
    assertStashed(a, requests);
  });
  await withStub(echoSlug, async (url, requests) => {
    const b = await run({ cwd, home: mkTemp('home'), url, plan: { tool_name: 'ExitPlanMode', tool_input: {}, tool_response: { isAgent: false, filePath: viaResponse, hasTaskTool: true } } });
    assertStashed(b, requests);
  });
});

test('a8b9ebfb S5 (AC2): no text and no path, a missing file, an empty file -> no request, one not-stashed line', async () => {
  const cwd = mkTemp('nothing');
  writeConfig(cwd, PROJECT_CONFIG);
  const dir = mkTemp('planfiles');
  const empty = join(dir, 'empty.md');
  writeFileSync(empty, '');
  const inputs = {
    'no text, no path': { tool_name: 'ExitPlanMode', tool_input: {}, tool_response: { isAgent: false, hasTaskTool: true } },
    'missing file': { tool_name: 'ExitPlanMode', tool_input: { planFilePath: join(dir, 'gone.md') }, tool_response: { filePath: join(dir, 'gone.md') } },
    'empty file': { tool_name: 'ExitPlanMode', tool_input: { planFilePath: empty }, tool_response: { filePath: empty } },
    'no tool_input or tool_response': { tool_name: 'ExitPlanMode' },
  };
  for (const [name, plan] of Object.entries(inputs)) {
    await withStub(echoSlug, async (url, requests) => {
      const res = await run({ cwd, home: mkTemp('home'), url, plan });
      assertNotStashed(res, 'no plan text or readable plan file in the hook input');
      assert.equal(requests.length, 0, name);
    });
  }
});

test('a8b9ebfb S6 (AC2): the real shape with a failing write (207 duplicate, 401, 403, 500, ok:false element, non-envelope) -> one not-stashed line naming that failure', async () => {
  // name -> [the reply the fixture serves, the code that reply must put in `write failed: ${code}`]
  const replies = {
    duplicate: [elementFailReply('duplicate', { kind: 'document', id: 'my-great-plan' }), 'duplicate'],
    401: [topFailReply(401, 'unauthenticated'), 'unauthenticated'],
    403: [topFailReply(403, 'forbidden'), 'forbidden'],
    500: [topFailReply(500, 'internal'), 'internal'],
    okfalse: [{ status: 200, json: { ok: true, data: { results: [{ ok: false, error: { code: 'internal', kind: 'transient', message: 'x' } }], summary: { ok: 0, failed: 1 } }, meta: META } }, 'internal'],
    nonenvelope: [{ status: 200, json: { slug: 'my-great-plan', status: 'pending' } }, 'client_error'],
  };
  for (const [name, [reply, code]] of Object.entries(replies)) {
    const cwd = mkTemp('real-fail');
    writeConfig(cwd, PROJECT_CONFIG);
    await withStub(() => reply, async (url, requests) => {
      const res = await run({ cwd, home: mkTemp('home'), url, plan: REAL_SHAPE(GONE, GONE) });
      assertNotStashed(res, `write failed: ${code}`);
      assert.equal(requests.length, 1, name);
    });
  }
  const cwd = mkTemp('real-unreach');
  writeConfig(cwd, PROJECT_CONFIG);
  assertNotStashed(await run({ cwd, home: mkTemp('home'), url: 'http://127.0.0.1:1', plan: REAL_SHAPE(GONE, GONE) }), 'write failed: client_error');
});

test('a8b9ebfb S6 probe: the not-stashed line carries no secret - the bearer token never appears on stdout or stderr', async () => {
  const cwd = mkTemp('tokleak');
  writeConfig(cwd, PROJECT_CONFIG);
  await withStub(() => topFailReply(401, 'unauthenticated'), async (url) => {
    const res = await run({ cwd, home: mkTemp('home'), url, token: 'dummy-leak-token', plan: REAL_SHAPE(GONE, GONE) });
    assertNotStashed(res, 'write failed: unauthenticated');
    assert.ok(!res.stdout.includes('dummy-leak-token') && !res.stderr.includes('dummy-leak-token'));
  });
});

test('a8b9ebfb S7 (D1): a session without a project rootId stays silent on the real shape, with and without plan text', async () => {
  await withStub(echoSlug, async (url, requests) => {
    const noText = { tool_name: 'ExitPlanMode', tool_input: {}, tool_response: { isAgent: false, hasTaskTool: true } };
    for (const plan of [REAL_SHAPE(GONE, GONE), noText]) {
      const res = await run({ cwd: mkTemp('empty'), home: mkTemp('home'), url, plan });
      assert.equal(res.status, 0, res.stderr);
      assert.equal(res.stdout, '');
    }
    const noProj = mkTemp('noproj2');
    writeConfig(noProj, 'retrospective:' + NL + '  mode: nudge' + NL);
    const res = await run({ cwd: noProj, home: mkTemp('home'), url, plan: noText });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(res.stdout, '');
    assert.equal(requests.length, 0);
  });
});
