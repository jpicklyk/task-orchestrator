// Runs the Code Mode recipes in code-mode.md (prelude + kit + recipe body, as pasted) against the real
// run-planner helper, with stubbed REPL globals, in a fresh temp directory. Catches a recipe that
// mishandles real helper output (finding B1: `prompt` prints raw text; B2: the Method B result is
// the state document; B3: the snapshot carries the profile and the server features).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const PLUGIN_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const HELPER = join(PLUGIN_ROOT, 'scripts', 'run-planner.mjs').replace(/\\/g, '/');
const FIXTURES = join(PLUGIN_ROOT, 'scripts', 'tests', 'fixtures');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const DOC = readFileSync(join(PLUGIN_ROOT, 'skills', 'run-wave', 'references', 'code-mode.md'), 'utf8');
const fwd = (p) => p.replace(/\\/g, '/');

function jsBlocks(text) {
  const out = [];
  const re = /```js\r?\n([\s\S]*?)```/g;
  let m;
  while ((m = re.exec(text)) !== null) out.push(m[1]);
  return out;
}
function section(title) {
  const start = DOC.indexOf('\n## ' + title);
  assert.ok(start !== -1, 'section missing: ' + title);
  const next = DOC.indexOf('\n## ', start + 4);
  return DOC.slice(start, next === -1 ? undefined : next);
}
const PRELUDE = jsBlocks(section('Prelude'))[0];
const KIT = jsBlocks(section('Kit'))[0];
const recipe = (n) => jsBlocks(section('R' + n + ':'))[0];

function mk() {
  const dir = fwd(mkdtempSync(join(tmpdir(), 'cm-recipes-')));
  const scratch = dir + '/scratch';
  mkdirSync(scratch, { recursive: true });
  return { dir, scratch };
}

// Live Code Mode runtime semantics (live check 276f353a): `file(path)` returns a handle read through its methods;
// `$(command)` rejects with a ShellError on a non-zero exit unless `.nothrow()`, merges stderr into stdout and
// resolves `exitCode`; `eval`, `Function` and a top-level `return` are refused, a script's result is its last expression.
function fileHandle(p) {
  return {
    name: p,
    text: async () => readFileSync(p, 'utf8'),
    json: async () => JSON.parse(readFileSync(p, 'utf8')),
    lines: async () => readFileSync(p, 'utf8').split('\n'),
    exists: async () => existsSync(p),
  };
}
function shell(command, { cwd, home }) {
  const r = spawnSync(command, { shell: true, encoding: 'utf8', cwd, env: { ...process.env, HOME: home, USERPROFILE: home, TASK_ORCHESTRATOR_API_URL: '' } });
  const out = { stdout: (r.stderr || '') + (r.stdout || ''), stderr: '', exitCode: r.status };
  let nothrow = false;
  const settle = async () => {
    if (out.exitCode !== 0 && !nothrow) throw Object.assign(new Error('ShellError: exit ' + out.exitCode), out);
    return out;
  };
  const p = {
    nothrow() { nothrow = true; return p; },
    text: async () => (await settle()).stdout,
    then: (res, rej) => settle().then(res, rej),
  };
  return p;
}
const refused = (what) => () => { throw new EvalError(what + ' is refused in Code Mode'); };

// The script exactly as pasted: prelude + kit + recipe. It must parse as a module, end with the recipe call, and
// is then run with that call returned (the REPL's last expression).
function script(n, { runDir, scratch, rootId, subst = {}, body }) {
  let src = PRELUDE + '\n' + KIT + '\n' + (body || recipe(n));
  src = src.split('<scratchpad>/run-wave/<runId>').join(runDir || scratch + '/run-wave/r-test');
  src = src.split('<scratchpad>').join(scratch).split('<helper>').join(HELPER).split('<rootId>').join(rootId);
  for (const [from, to] of Object.entries(subst)) {
    assert.ok(src.includes(from), 'substitution target missing: ' + from);
    src = src.split(from).join(to);
  }
  return src;
}
function checkScript(n, src, dir) {
  const f = dir + '/script-r' + n + '.mjs';
  writeFileSync(f, src);
  const r = spawnSync(process.execPath, ['--check', f], { encoding: 'utf8' });
  assert.equal(r.status, 0, 'R' + n + ' does not pass node --check: ' + r.stderr);
  const last = src.split('\n').filter((l) => l.trim() !== '').pop();
  assert.equal(last.trim(), 'await r' + n + '()', 'R' + n + ' does not end with its call');
}

async function run(n, { runDir, scratch, rootId = '00000000-0000-4000-8000-000000000000', cwd, tools = {}, agent, workflow, sendMessage, subst = {}, shellFn, onSource, body }) {
  const src = script(n, { runDir, scratch, rootId, subst, body });
  const dir = fwd(dirname(scratch));
  checkScript(n, src, dir);
  if (onSource) onSource(src);
  const home = fwd(mkdtempSync(join(tmpdir(), 'cm-home-')));
  const lines = src.split('\n');
  let at = lines.length - 1;
  while (lines[at].trim() === '') at -= 1;
  lines[at] = 'return await r' + n + '()';
  const g = {
    tools,
    Agent: agent || (async () => ({ agentId: 'ag-1' })),
    Workflow: workflow || (async () => ({ taskId: 't-1', wfRunId: 'w-1', wfScriptPath: '/x' })),
    SendMessage: sendMessage || (async () => ({})),
    Write: async ({ file_path, content }) => {
      mkdirSync(dirname(file_path), { recursive: true });
      writeFileSync(file_path, content);
      return {};
    },
    file: fileHandle,
    $: shellFn || ((command) => shell(command, { cwd, home })),
    eval: refused('eval'),
    Function: refused('Function'),
    setTimeout,
    log: () => {},
  };
  const names = Object.keys(g);
  return await new AsyncFunction(...names, lines.join('\n'))(...names.map((k) => g[k]));
}

const env = (data) => JSON.stringify({ ok: true, data });
const fixture = (rel) => JSON.parse(readFileSync(join(FIXTURES, rel), 'utf8'));
const PROTOCOL_RULES = ['commit-discipline', 'protocol.entry-seat', 'protocol.in-phase-seat', 'protocol.read-only-agent'].map((key) => ({ key, rulesVersion: '1' }));
const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10';
const thrown = (code, extra) => new Error('boom\n' + JSON.stringify({ ok: false, error: { code, fix: 'f', detail: extra || null } }));
// What the live tool global throws for a failed one-op non-atomic write: a top-level partial_failure whose element error (index 0) holds the real code.
const thrownElement = (code, extra) => new Error('partial_failure: 1 of 1 elements failed.' + String.fromCharCode(10) + JSON.stringify({ ok: false, error: { code: 'partial_failure', kind: 'permanent', message: '1 of 1 elements failed.' }, data: { results: [{ index: 0, ok: false, error: { code, fix: 'f', detail: extra || null } }], summary: { ok: 0, failed: 1 } }, meta: { reqId: 'r' } }));

function gitRepo(dir) {
  const repo = dir + '/repo';
  mkdirSync(repo, { recursive: true });
  for (const a of [['init', '-q'], ['-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '--allow-empty', '-q', '-m', 'init']]) {
    const r = spawnSync('git', a, { cwd: repo, encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
  }
  return repo;
}

// Plans the independent-two fixture into <scratch>/run-wave/<runId>/plan.json (the token files the helper names live under the same directory).
function makePlan(scratch, method) {
  const tmp = scratch + '/plan-draft.json';
  const args = ['plan', '--in', join(FIXTURES, 'run-planner', 'independent-two.json'), '--now', '2026-09-28T12:00:00Z', '--scratchpad', scratch, '--out', tmp];
  if (method) args.push('--method', method);
  const r = spawnSync(process.execPath, [HELPER, ...args], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
  const plan = JSON.parse(readFileSync(tmp, 'utf8'));
  const runDir = scratch + '/run-wave/' + plan.args.runId;
  mkdirSync(runDir + '/tokens', { recursive: true });
  writeFileSync(runDir + '/plan.json', JSON.stringify(plan));
  return { runDir, plan };
}

// R1: the snapshot carries the run profile and the server features (B3).
test('R1 fills snapshot.features from the schema answer and snapshot.profile from run-profile.json', async () => {
  const { dir, scratch } = mk();
  const repo = gitRepo(dir);
  mkdirSync(repo + '/.taskorchestrator', { recursive: true });
  writeFileSync(repo + '/.taskorchestrator/run-profile.json', JSON.stringify({ appendOnlyFiles: ['CHANGELOG.md'] }));
  const snap = fixture('run-planner/independent-two.json');
  const cand = snap.candidates[0];
  const sch = snap.schemas[cand.id];
  const item = { id: cand.id, title: cand.title, priority: cand.priority, type: cand.type, role: 'queue', parentId: null, traits: [] };
  const tools = {};
  const key = (t) => 'mcp__mcp-task-orchestrator__' + t;
  tools[key('items_read')] = async (a) => {
    if (a.select === 'blocked') return env({ items: [] });
    if (a.select === 'next') return env({ items: [{ id: cand.id }] });
    if (a.select === 'list') return env({ items: [] });
    if (a.select === 'get') return env(item);
    if (a.select === 'schema') return env({ ...sch, features: snap.features });
    throw new Error('unexpected select ' + a.select);
  };
  tools[key('config')] = async () => env({ results: [{ rules: PROTOCOL_RULES }] });
  tools[key('runs_read')] = async () => env({ items: [] });
  const out = await run(1, { scratch, cwd: repo, tools });
  assert.ok(out && out.explain, 'R1 returned ' + JSON.stringify(out));
  const written = JSON.parse(readFileSync(scratch + '/run-wave/snapshot.json', 'utf8'));
  assert.deepEqual(written.features, snap.features);
  assert.deepEqual(written.profile, { appendOnlyFiles: ['CHANGELOG.md'] });
  const plan = JSON.parse(readFileSync(scratch + '/run-wave/plan-draft.json', 'utf8'));
  assert.deepEqual(plan.args.capabilities.features, snap.features);
  assert.deepEqual(plan.args.project.appendOnlyFiles, ['CHANGELOG.md']);
});

test('R1 writes profile {} when run-profile.json is absent', async () => {
  const { dir, scratch } = mk();
  const repo = gitRepo(dir);
  const snap = fixture('run-planner/independent-two.json');
  const cand = snap.candidates[0];
  const key = (t) => 'mcp__mcp-task-orchestrator__' + t;
  const tools = {
    [key('items_read')]: async (a) => {
      if (['blocked', 'list'].includes(a.select)) return env({ items: [] });
      if (a.select === 'next') return env({ items: [{ id: cand.id }] });
      if (a.select === 'get') return env({ id: cand.id, title: cand.title, priority: cand.priority, type: cand.type, role: 'queue', parentId: null, traits: [] });
      return env({ ...snap.schemas[cand.id], features: snap.features });
    },
    [key('config')]: async () => env({ results: [{ rules: PROTOCOL_RULES }] }),
    [key('runs_read')]: async () => env({ items: [] }),
  };
  const out = await run(1, { scratch, cwd: repo, tools });
  assert.ok(out && out.explain, JSON.stringify(out));
  const written = JSON.parse(readFileSync(scratch + '/run-wave/snapshot.json', 'utf8'));
  assert.deepEqual(written.profile, {});
});

// R2 -> R5 -> R6 -> R7 on a real plan from the run-planner fixture.
test('R2, R5, R6 and R7 run on real helper output (prompt is raw text; the Method B result is the state)', async () => {
  const { scratch } = mk();
  const { runDir, plan: plan0 } = makePlan(scratch, 'B');
  writeFileSync(runDir + '/state.json', '{}\n');
  // R2 runs git against args.repoRoot: point it at a temp repo and list no worktree to create
  const repo = gitRepo(dirname(scratch));
  const bp = JSON.parse(readFileSync(runDir + '/plan.json', 'utf8'));
  bp.args.repoRoot = repo;
  bp.meta.worktreesToCreate = [];
  writeFileSync(runDir + '/plan.json', JSON.stringify(bp));

  const key = (t) => 'mcp__mcp-task-orchestrator__' + t;
  let n = 0;
  const writes = [];
  const runsWrite = async (a) => {
    writes.push(a);
    return env({
      results: a.ops.map((op) => ({ ok: true, runId: SERVER_RUN, coordinatorToken: 'COORD-SECRET-TOKEN', seatToken: 'SEAT-SECRET-' + (n += 1) })),
    });
  };
  const tools = {
    [key('runs_write')]: runsWrite,
    [key('items_read')]: async (a) => env(a.select === 'context' ? { item: { role: 'work' }, gateStatus: { canAdvance: true, missing: [] } } : { notes: [] }),
    [key('notes_read')]: async (a) => { if (a.select === 'get') throw thrown('not_found'); return env({ items: [] }); },
    [key('notes_write')]: async (a) => env({ results: a.ops.map(() => ({ ok: true })) }),
    [key('advance')]: async (a) => env({ results: a.ops.map((o) => ({ ok: true, itemId: o.itemId })) }),
  };

  // R2: open (Method B assigns per batch in R5, so no seat here)
  const r2 = await run(2, { runDir, scratch, tools });
  assert.ok(r2.serverRunId === SERVER_RUN && r2.seats === 0 && r2.failed === 0, JSON.stringify(r2));
  assert.ok(!JSON.stringify(r2).includes('SECRET'), 'a token left the script');

  // R5: dispatch. The prompt must be the helper's raw text.
  const prompts = [];
  const agent = async (a) => { prompts.push(a); return { agentId: 'ag-' + prompts.length }; };
  const r5 = await run(5, { runDir, scratch, tools, agent });
  assert.ok(r5.dispatched && r5.dispatched.length > 0, JSON.stringify(r5));
  assert.ok(r5.dispatched.every((d) => d.agentId && !d.failed), JSON.stringify(r5));
  assert.equal(prompts.length, r5.dispatched.length);
  for (const p of prompts) assert.ok(typeof p.prompt === 'string' && p.prompt.length > 200, 'prompt is not the helper text');
  assert.ok(!JSON.stringify(r5).includes('SECRET'));

  // R6: collect one planner envelope through stage-result.
  const envelope = JSON.parse(readFileSync(join(FIXTURES, 'run-exec', 'planner-envelope.json'), 'utf8'));
  const shortId = r5.dispatched[0].item;
  const r6 = await run(6, {
    runDir, scratch, tools,
    subst: { "// { item: '<short>', seat: '<seat>', n: 1, agentId: '<id>', envelope: { /* pasted as is */ } },": `{ item: ${JSON.stringify(shortId)}, seat: 'planner', n: 1, agentId: 'ag-1', envelope: ${JSON.stringify(envelope)} },` },
  });
  assert.equal(r6.length, 1, JSON.stringify(r6));
  assert.ok(!r6[0].failed, JSON.stringify(r6));
  const state = JSON.parse(readFileSync(runDir + '/state.json', 'utf8'));
  assert.equal(state.contract, 'run-wave/state-v1');

  // R7: the Method B result is the state document, and the helpers read it.
  mkdirSync(runDir + '/tokens', { recursive: true });
  const seatsView = { seats: [] };
  const tools7 = {
    ...tools,
    [key('runs_read')]: async () => env(seatsView),
    [key('notes_read')]: async () => env({ items: [] }),
    [key('advance')]: async (a) => env({ results: a.ops.map((o) => ({ ok: true, itemId: o.itemId })) }),
  };
  const r7 = await run(7, { runDir, scratch, tools: tools7 });
  assert.ok(r7 && !r7.failed && !r7.needs, 'R7 returned ' + JSON.stringify(r7));
  const saved = JSON.parse(readFileSync(runDir + '/result.json', 'utf8'));
  assert.equal(saved.contract, 'run-wave/state-v1', 'the Method B result is not the state document');
  const ver = spawnSync(process.execPath, [HELPER, 'verify', '--plan', runDir + '/plan.json', '--result', runDir + '/result.json'], { encoding: 'utf8' });
  const verJson = JSON.parse(ver.stdout);
  assert.equal(verJson.items.length, plan0.args.items.length, 'verify judged a different item set than the plan');
  assert.ok(existsSync(runDir + '/seats.json'));
  assert.ok(existsSync(runDir + '/verify.json') && existsSync(runDir + '/failing.json'));

  // R8: the Step 4 advance is its own recipe, after R7 wrote the notes
  const advances = [];
  const tools8 = {
    ...tools7,
    [key('items_read')]: async () => env({ notes: [] }),
    [key('advance')]: async (a) => { advances.push(a); return env({ results: a.ops.map((o) => ({ ok: true, itemId: o.itemId })) }); },
  };
  const r8 = await run(8, { runDir, scratch, tools: tools8 });
  assert.ok(r8 && !r8.failed && !r8.needs, 'R8 returned ' + JSON.stringify(r8));
  assert.ok(r8.advanced.length + r8.failing.length === plan0.args.items.length, JSON.stringify(r8));
});

test('R7 asks for the result in Method A when none was given and no output file is recorded', async () => {
  const { scratch } = mk();
  const { runDir } = makePlan(scratch);
  writeFileSync(runDir + '/state.json', JSON.stringify({ contract: 'run-wave/state-v1', phase: 'launched', stages: {}, outs: {} }));
  writeFileSync(runDir + '/tokens/coordinator.token', 'COORD\n');
  const plan = JSON.parse(readFileSync(runDir + '/plan.json', 'utf8'));
  plan.args.serverRunId = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10';
  writeFileSync(runDir + '/plan.json', JSON.stringify(plan));
  const r = await run(7, { runDir, scratch });
  assert.equal(r.needs, 'result', JSON.stringify(r));
});

test('R4 records state.taskOutputFile and R7 reads the output file it names', async () => {
  const { scratch } = mk();
  const { runDir } = makePlan(scratch);
  const plan = JSON.parse(readFileSync(runDir + '/plan.json', 'utf8'));
  plan.args.serverRunId = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10';
  writeFileSync(runDir + '/plan.json', JSON.stringify(plan));
  writeFileSync(runDir + '/state.json', JSON.stringify({ contract: 'run-wave/state-v1', runId: plan.args.runId, phase: 'planned', turns: 0, stages: {}, outs: {} }));
  writeFileSync(runDir + '/tokens/coordinator.token', 'COORD\n');
  const key = (t) => 'mcp__mcp-task-orchestrator__' + t;
  const tools = { [key('runs_write')]: async (a) => env({ results: a.ops.map(() => ({ ok: true })) }) };
  const out = await run(4, { runDir, scratch, tools, workflow: async () => ({ taskId: 't-9', wfRunId: 'w-9', wfScriptPath: '/s', outputFile: scratch + '/task.output' }) });
  assert.equal(out.taskId, 't-9', JSON.stringify(out));
  const state = JSON.parse(readFileSync(runDir + '/state.json', 'utf8'));
  assert.equal(state.taskOutputFile, scratch + '/task.output');
  const none = await run(4, { runDir, scratch, tools });
  assert.equal(none.taskId, 't-1');
  assert.equal(JSON.parse(readFileSync(runDir + '/state.json', 'utf8')).taskOutputFile, null);
  // the recorded file is the one R7 reads
  writeFileSync(scratch + '/task.output', JSON.stringify({ summary: 's', result: { contract: 'implement-wave/result-v1', started: true, items: [], stages: [] } }, null, 2));
  const st = JSON.parse(readFileSync(runDir + '/state.json', 'utf8'));
  st.taskOutputFile = scratch + '/task.output';
  writeFileSync(runDir + '/state.json', JSON.stringify(st));
  const tools7 = {
    [key('runs_read')]: async () => env({ seats: [] }),
    [key('notes_read')]: async () => env({ items: [] }),
    [key('advance')]: async (a) => env({ results: a.ops.map((o) => ({ ok: true, itemId: o.itemId })) }),
    [key('runs_write')]: async (a) => env({ results: a.ops.map(() => ({ ok: true })) }),
  };
  await run(7, { runDir, scratch, tools: tools7 });
  assert.equal(JSON.parse(readFileSync(runDir + '/result.json', 'utf8')).contract, 'implement-wave/result-v1');
});

// O7: no schema-seat entry means no assign call.
test('R5 sends no assign call when every dispatch entry is token-less', async () => {
  const src = recipe(5);
  assert.ok(/if \(assignable\.length\)/.test(src), 'R5 does not guard the assign call');
  assert.ok(KIT.indexOf('ends.code !== 0') !== -1 && KIT.indexOf('ends.code !== 0') < KIT.indexOf('ends.json.ops'), 'endSeats reads ends.json before checking ends.code');
});

// ---- Live-runtime defects (live check 276f353a): each test fails against the pre-change code-mode.md ----

const ikey = (t) => 'mcp__mcp-task-orchestrator__' + t;

test('S1 (AC3): R1 to R11 each pass node --check, end with their own call and use no eval or Function; a top-level return fails the check', () => {
  const { dir, scratch } = mk();
  for (let n = 1; n <= 11; n += 1) {
    const src = script(n, { scratch, rootId: 'x' });
    checkScript(n, src, dir);
    assert.ok(!/\beval\s*\(|\bnew Function\b|\bFunction\s*\(/.test(src), 'R' + n + ' calls eval or Function');
  }
  const bad = dir + '/bad.mjs';
  writeFileSync(bad, 'const a = 1;\nif (a) return a;\n');
  assert.notEqual(spawnSync(process.execPath, ['--check', bad], { encoding: 'utf8' }).status, 0, 'a top-level return must fail the check');
  for (const block of jsBlocks(DOC)) assert.ok(!/\beval\s*\(|new Function/.test(block), 'a js block in code-mode.md calls eval or Function');
});

// S3 and S4 share this fixture: ANCESTOR_ID A, child C (leaf), container K (child x).
function r1Fixture() {
  const { dir, scratch } = mk();
  const repo = gitRepo(dir);
  const snap = fixture('run-planner/independent-two.json');
  const base = snap.candidates[0];
  const sch = snap.schemas[base.id];
  const A = 'a0000000-0000-4000-8000-00000000000a';
  const C = 'c0000000-0000-4000-8000-00000000000c';
  const K = 'd0000000-0000-4000-8000-00000000000d';
  const X = 'e0000000-0000-4000-8000-00000000000e';
  const children = { [A]: [C, K], [K]: [X], [C]: [] };
  const lists = [];
  const tools = {
    [ikey('items_read')]: async (a) => {
      if (a.select === 'blocked') return env({ items: [] });
      if (a.select === 'next') return env({ items: [{ id: A }, { id: C }, { id: K }] });
      if (a.select === 'list') {
        if (a.parentId !== undefined) {
          lists.push(a);
          return env({ items: (children[a.parentId] || []).map((id) => ({ id })) });
        }
        return env({ items: [] });
      }
      if (a.select === 'get') {
        const id = a.itemId === 'A-SHORT' ? A : a.itemId;
        return env({ id, title: 't-' + id.slice(0, 1), priority: base.priority, type: base.type, role: 'queue', parentId: id === A ? null : A, traits: [] });
      }
      if (a.select === 'context') return env({ item: { role: 'queue' }, gateStatus: { canAdvance: false, missing: [] } });
      if (a.select === 'schema') return env({ ...sch, features: snap.features });
      throw new Error('unexpected select ' + a.select);
    },
    [ikey('config')]: async () => env({ results: [{ rules: PROTOCOL_RULES }] }),
    [ikey('runs_read')]: async () => env({ items: [] }),
  };
  return { scratch, repo, tools, ids: { A, C, K }, lists };
}

test('S3 (AC4, AC5): R1 leaves the ancestor out when it has children and fills hasChildren from a one-row list', async () => {
  const { scratch, repo, tools, ids, lists } = r1Fixture();
  const out = await run(1, { scratch, cwd: repo, tools, subst: { 'const ANCESTOR_ID = null;': "const ANCESTOR_ID = 'A-SHORT';" } });
  assert.ok(out && out.explain, JSON.stringify(out));
  const snap = JSON.parse(readFileSync(scratch + '/run-wave/snapshot.json', 'utf8'));
  assert.deepEqual(snap.candidates.map((c) => c.id).sort(), [ids.C, ids.K].sort());
  assert.equal(snap.candidates.find((c) => c.id === ids.K).hasChildren, true);
  assert.equal(snap.candidates.find((c) => c.id === ids.C).hasChildren, false);
  assert.ok(lists.length >= 3 && lists.every((l) => l.limit === 1));
  const plan = JSON.parse(readFileSync(scratch + '/run-wave/plan-draft.json', 'utf8'));
  const k = (plan.meta.excluded || []).find((e) => e.id === ids.K);
  assert.ok(k && k.reason === 'container', JSON.stringify(plan.meta.excluded));
  assert.ok(!(plan.meta.excluded || []).some((e) => e.id === ids.A) && !plan.args.items.some((i) => i.id === ids.A), 'the ancestor is no candidate');
});

test('S3 (D5): a leaf ancestor stays a candidate (single-item scope)', async () => {
  const { scratch, repo, tools, ids } = r1Fixture();
  const next = tools[ikey('items_read')];
  tools[ikey('items_read')] = async (a) => {
    if (a.select === 'next') return env({ items: [{ id: ids.C }] });
    if (a.select === 'get' && a.itemId === 'C-SHORT') return next({ select: 'get', itemId: ids.C });
    return next(a);
  };
  const out = await run(1, { scratch, cwd: repo, tools, subst: { 'const ANCESTOR_ID = null;': "const ANCESTOR_ID = 'C-SHORT';" } });
  assert.ok(out && out.explain, JSON.stringify(out));
  const snap = JSON.parse(readFileSync(scratch + '/run-wave/snapshot.json', 'utf8'));
  assert.deepEqual(snap.candidates.map((c) => c.id), [ids.C]);
});

test('S4 (AC6): MODE, ALTITUDE_SAFE and a probe without phase 0 hooks reach plan as --mode, --altitude-safe and --entry pre-entered', async () => {
  const { scratch, repo, tools } = r1Fixture();
  const out = await run(1, {
    scratch, cwd: repo, tools,
    subst: {
      'const MODE = null;': "const MODE = 'per-item';",
      'const ALTITUDE_SAFE = false;': 'const ALTITUDE_SAFE = true;',
      'probe.json.phase0Hooks ?': 'false ?',
    },
  });
  assert.ok(out && out.explain, JSON.stringify(out));
  const plan = JSON.parse(readFileSync(scratch + '/run-wave/plan-draft.json', 'utf8'));
  assert.equal(plan.args.worktreeMode, 'per-item');
  assert.equal(plan.args.altitudeSafe, true);
  assert.equal(plan.args.entryMode, 'pre-entered');
});

test('S4 (AC6): SHARED_WORKTREE reaches plan as --worktree and --branch', async () => {
  const { scratch, repo, tools } = r1Fixture();
  const wt = scratch + '/feature-wt';
  mkdirSync(wt, { recursive: true });
  const out = await run(1, {
    scratch, cwd: repo, tools,
    subst: {
      'const MODE = null;': "const MODE = 'shared';",
      'const SHARED_WORKTREE = null;': "const SHARED_WORKTREE = { path: '" + wt + "', branch: 'feat/x' };",
    },
  });
  assert.ok(out && out.explain, JSON.stringify(out));
  const plan = JSON.parse(readFileSync(scratch + '/run-wave/plan-draft.json', 'utf8'));
  assert.equal(plan.args.worktreeMode, 'shared');
  assert.ok(JSON.stringify(plan.args).includes('feat/x'), 'the shared branch is in the plan args');
});

// S5: the kit helper under the live shell semantics.
async function kitHelper(printed, exitCode) {
  const { scratch } = mk();
  const src = PRELUDE + '\n' + KIT + "\nreturn await helper('verify', '');";
  const subst = src.split('<scratchpad>/run-wave/<runId>').join(scratch + '/r').split('<helper>').join(HELPER);
  const shellFn = () => {
    const out = { stdout: printed, stderr: '', exitCode };
    let nothrow = false;
    const p = {
      nothrow() { nothrow = true; return p; },
      then: (res, rej) => (exitCode !== 0 && !nothrow ? rej(Object.assign(new Error('ShellError'), out)) : res(out)),
    };
    return p;
  };
  const g = { tools: {}, file: fileHandle, $: shellFn, Write: async () => ({}), setTimeout };
  const names = Object.keys(g);
  return await new AsyncFunction(...names, subst)(...names.map((k) => g[k]));
}

test('S5 (AC2): the kit helper drops warning and error lines from merged streams, and an exit 3 is a code, not a throw', async () => {
  const r = await kitHelper('{"warning":"w"}\n{"a":1}\n', 0);
  assert.deepEqual(r.json, { a: 1 });
  assert.ok(!r.raw.includes('warning'), r.raw);
  const e = await kitHelper('{"error":"bad","detail":null}\n{"ok":false}\n', 3);
  assert.equal(e.code, 3);
  assert.deepEqual(e.json, { ok: false });
  assert.ok(e.err.includes('bad'), e.err);
  const plain = await kitHelper('{"ok":true,"items":[]}\n', 3);
  assert.equal(plain.code, 3);
  assert.deepEqual(plain.json, { ok: true, items: [] });
  const text = await kitHelper('line one\nline two\n', 0);
  assert.equal(text.json, null);
  assert.equal(text.raw, 'line one\nline two\n');
});

// R7 on a Method A plan with a pasted result. `planner` outputs carry the sweep verify checks.
function r7Setup({ scopeId } = {}) {
  const { scratch } = mk();
  const { runDir, plan } = makePlan(scratch);
  plan.args.serverRunId = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10';
  if (scopeId) plan.meta.scopeId = scopeId;
  writeFileSync(runDir + '/plan.json', JSON.stringify(plan));
  writeFileSync(runDir + '/state.json', JSON.stringify({ contract: 'run-wave/state-v1', runId: plan.args.runId, phase: 'launched', turns: 2, stages: {}, outs: {} }));
  writeFileSync(runDir + '/tokens/coordinator.token', 'COORD\n');
  return { scratch, runDir, plan, items: plan.args.items };
}
const plannerStage = (item) => item.stages.find((s) => s.output === 'planner-v1');
function resultFor(items, sweepOk) {
  return {
    contract: 'implement-wave/result-v1', started: true, items: items.map((item) => ({
      id: item.id, short: item.short, status: 'done',
      stages: [{ seat: plannerStage(item).seat, status: 'done' }],
      outputs: { [plannerStage(item).seat]: { sweep: [{ kind: 'tests', grep: 'g', hits: 2, disposed: sweepOk[item.id] ? 2 : 1 }] } },
    })),
    stages: [], refused: [], deferred: [],
  };
}
function r7Tools({ withNotes, schemaKeys = {}, advances, writes = [], bodies = {}, notesWrite, runsWrite }) {
  const notes = (id) => (schemaKeys[id] || []).map((k) => (typeof k === 'string' ? { key: k, role: 'work' } : k));
  return {
    [ikey('runs_read')]: async (a) => env(a.select === 'get' ? { openedAt: '2026-09-28T12:00:00Z' } : { seats: [] }),
    [ikey('notes_read')]: async (a) => {
      if (a.select === 'get') {
        const b = bodies[a.itemId + ':' + a.key];
        if (b === undefined) throw thrown('not_found');
        return env({ key: a.key, body: b.body, version: b.version });
      }
      return env({ items: withNotes.has(a.itemId) ? a.keys.map((key) => ({ key })) : [] });
    },
    [ikey('items_read')]: async (a) => env({ notes: notes(a.itemId) }),
    [ikey('advance')]: async (a) => { advances.push(a); return env({ results: a.ops.map((o) => ({ ok: true, itemId: o.itemId })) }); },
    [ikey('runs_write')]: runsWrite || (async (a) => env({ results: a.ops.map(() => ({ ok: true })) })),
    [ikey('notes_write')]: notesWrite || (async (a) => { writes.push(a); return env({ results: a.ops.map(() => ({ ok: true })) }); }),
  };
}
const literal = (scratch, result) => ({ 'const RESULT_LITERAL = null;': 'const RESULT_LITERAL = ' + JSON.stringify(result) + ';' });

test('S6 (AC3, AC7): R7 leaves out an item the notes audit fails and an item verify fails; R8 advances the rest', async () => {
  // audit failure: X has a done planner stage and no observed notes; Y is complete
  {
    const { scratch, runDir, items } = r7Setup();
    const [X, Y] = items;
    const advances = [];
    const result = resultFor(items, { [X.id]: true, [Y.id]: true });
    const tools = r7Tools({ withNotes: new Set([Y.id]), advances });
    const out = await run(7, { runDir, scratch, tools, subst: literal(scratch, result) });
    assert.deepEqual(out.failing, [X.short], JSON.stringify(out));
    assert.equal(advances.length, 0, 'R7 must not advance');
    assert.equal(JSON.parse(readFileSync(runDir + '/state.json', 'utf8')).phase, 'notified');
    const o8 = await run(8, { runDir, scratch, tools });
    assert.equal(advances.length, 1, JSON.stringify(o8));
    assert.deepEqual(advances[0].ops.map((o) => o.itemId), [Y.id]);
    assert.equal(JSON.parse(readFileSync(runDir + '/state.json', 'utf8')).phase, 'post-run');
  }
  // verify failure (boolean ok false, status done): Y's sweep disposed 1 of 2; both have their notes
  {
    const { scratch, runDir, items } = r7Setup();
    const [X, Y] = items;
    const advances = [];
    const result = resultFor(items, { [X.id]: true, [Y.id]: false });
    const tools = r7Tools({ withNotes: new Set([X.id, Y.id]), advances });
    const out = await run(7, { runDir, scratch, tools, subst: literal(scratch, result) });
    assert.deepEqual(out.failing, [Y.short], JSON.stringify(out));
    await run(8, { runDir, scratch, tools });
    assert.deepEqual(advances[0].ops.map((o) => o.itemId), [X.id]);
  }
});

test('S7 (AC3): R8 sends no advance call when every item failed verify or the audit', async () => {
  const { scratch, runDir, items } = r7Setup();
  const [X, Y] = items;
  const advances = [];
  const result = resultFor(items, { [X.id]: true, [Y.id]: false });
  // X fails the audit (no notes), Y fails verify
  const tools = r7Tools({ withNotes: new Set([Y.id]), advances });
  const out = await run(7, { runDir, scratch, tools, subst: literal(scratch, result) });
  assert.deepEqual([...out.failing].sort(), [X.short, Y.short].sort(), JSON.stringify(out));
  const o8 = await run(8, { runDir, scratch, tools });
  assert.equal(o8.advance, 'none', JSON.stringify(o8));
  assert.equal(advances.length, 0, 'an empty advance was sent');
});

test('S7 (AC7): R7 returns failed before any advance when verify prints no JSON', async () => {
  const { scratch, runDir, items } = r7Setup();
  const advances = [];
  const result = resultFor(items, { [items[0].id]: true, [items[1].id]: true });
  const real = (command) => shell(command, { cwd: undefined, home: scratch });
  const shellFn = (command) => (/ verify /.test(command) ? shell('node -e "console.log(\'not json\')"', { cwd: undefined, home: scratch }) : real(command));
  const out = await run(7, { runDir, scratch, shellFn, tools: r7Tools({ withNotes: new Set(items.map((i) => i.id)), advances }), subst: literal(scratch, result) });
  assert.equal(out.failed, true, JSON.stringify(out));
  assert.equal(out.step, 'verify');
  assert.equal(advances.length, 0);
});

test('S8 (AC8): R7 returns where each provenance line goes, writes dispatch-meta.json and provenance.json, and no line text', async () => {
  const NONMEMBER = 'cccccccc-3333-4333-8333-333333333333';
  const { scratch, runDir, items } = r7Setup({ scopeId: NONMEMBER });
  const [X, Y] = items;
  const advances = [];
  const result = resultFor(items, { [X.id]: true, [Y.id]: true });
  const writes = [];
  const tools = r7Tools({ withNotes: new Set(items.map((i) => i.id)), schemaKeys: { [X.id]: ['delegation-metadata'], [Y.id]: ['session-tracking'], [NONMEMBER]: ['session-tracking'] }, advances, writes });
  const out = await run(7, { runDir, scratch, tools, subst: literal(scratch, result) });
  assert.deepEqual(out.provenance.items, [{ short: X.short, target: 'delegation-metadata' }, { short: Y.short, target: 'report' }], JSON.stringify(out));
  assert.equal(out.provenance.layer, 'report');
  assert.ok(existsSync(runDir + '/dispatch-meta.json'));
  const prov = JSON.parse(readFileSync(runDir + '/provenance.json', 'utf8'));
  for (const item of items) assert.ok(prov[item.id], 'provenance.json has a line for ' + item.short);
  assert.ok(!JSON.stringify(out).includes('tokens='), 'a provenance line left the script');
  assert.equal(writes.length, 1, 'only the item that declares delegation-metadata is written');
  assert.equal(writes[0].ops[0].itemId, X.id);
  assert.equal(writes[0].ops[0].body, prov[X.id]);
  // a non-member target parent that declares the key
  const again = r7Setup({ scopeId: NONMEMBER });
  const tools2 = r7Tools({ withNotes: new Set(again.items.map((i) => i.id)), schemaKeys: { [NONMEMBER]: ['delegation-metadata'] }, advances: [] });
  const out2 = await run(7, { runDir: again.runDir, scratch: again.scratch, tools: tools2, subst: literal(again.scratch, resultFor(again.items, {})) });
  assert.equal(out2.provenance.layer, 'parent', JSON.stringify(out2));
  // no scope: no layer
  const none = r7Setup();
  const out3 = await run(7, { runDir: none.runDir, scratch: none.scratch, tools: r7Tools({ withNotes: new Set(none.items.map((i) => i.id)), advances: [] }), subst: literal(none.scratch, resultFor(none.items, Object.fromEntries(none.items.map((i) => [i.id, true])))) });
  assert.equal(out3.provenance.layer, null, JSON.stringify(out3));
});

test('S9 (AC1): the recipes read files only through handle methods', () => {
  for (let n = 1; n <= 11; n += 1) {
    const src = recipe(n) + '\n' + (n === 1 ? KIT : '');
    assert.ok(!/await file\([^)]*\)\)/.test(src), 'R' + n + ' awaits a file handle as text');
  }
  assert.ok(!/JSON\.parse\(await file\([^)]*\)\)/.test(KIT));
  assert.ok(KIT.includes('.nothrow()'), 'the kit sh does not use nothrow');
});

// ---- Tier-2b coordinator path: every mechanical coordinator step has a recipe ----

// A bound plan (serverRunId set, coordinator token on disk) ready for R5, R7 to R11.
function boundSetup({ method = 'B', meta = {}, args = {}, state } = {}) {
  const { dir, scratch } = mk();
  const { runDir, plan } = makePlan(scratch, method);
  plan.args.serverRunId = SERVER_RUN;
  Object.assign(plan.meta, meta);
  Object.assign(plan.args, args);
  writeFileSync(runDir + '/plan.json', JSON.stringify(plan));
  writeFileSync(runDir + '/state.json', JSON.stringify(state || { contract: 'run-wave/state-v1', runId: plan.args.runId, phase: 'planned', method, turns: 0, stages: {}, outs: {} }));
  writeFileSync(runDir + '/tokens/coordinator.token', 'COORD\n');
  return { dir, scratch, runDir, plan, items: plan.args.items };
}

// S11 and S12: R5 adopts the pre-filled planner notes after the assign and records launched on its first round only.
function r5Tools({ log, deleteFails, upsertFailsWithToken, deleteCode = 'forbidden', updates }) {
  let n = 0;
  return {
    [ikey('runs_write')]: async (a) => {
      for (const op of a.ops) if (op.op === 'update') updates.push(op);
      return env({ results: a.ops.map((op) => ({ ok: true, seatToken: 'SEAT-' + (n += 1) })) });
    },
    [ikey('notes_read')]: async (a) => env({ key: a.key, role: 'queue', body: 'PRE-FILLED ' + a.itemId.slice(0, 4), version: 3, owner: { id: 'orchestrator' } }),
    [ikey('notes_write')]: async (a) => {
      const op = a.ops[0];
      log.push({ kind: 'note', item: op.itemId.slice(0, 8), op: op.op, token: Boolean(a.seatToken), body: op.body });
      if (op.op === 'delete' && deleteFails && deleteFails.has(op.itemId.slice(0, 8))) throw thrownElement(deleteCode);
      if (op.op === 'upsert' && upsertFailsWithToken && upsertFailsWithToken.has(op.itemId.slice(0, 8)) && a.seatToken) throw thrownElement('invalid_request');
      return env({ results: [{ ok: true }] });
    },
  };
}

test('S11 (AC2): R5 sends the adopt-notes delete and upsert after the assign and before the Agent call', async () => {
  const { scratch, runDir, items } = boundSetup();
  const log = [];
  const updates = [];
  const agent = async (a) => { log.push({ kind: 'agent', item: a.description.split(':')[1].split(' ')[0], seat: a.description.split(':')[0] }); return { agentId: 'ag' }; };
  const out = await run(5, { runDir, scratch, tools: r5Tools({ log, updates }), agent });
  assert.deepEqual(out.held, [], JSON.stringify(out));
  for (const item of items) {
    const mine = log.filter((l) => l.item === item.short);
    const kinds = mine.map((l) => l.kind + ':' + (l.op || l.seat));
    assert.deepEqual(kinds, ['note:delete', 'note:upsert', 'agent:planner'], item.short + ' ' + JSON.stringify(kinds));
    assert.equal(mine[0].token, false, 'a seat-less item deletes token-less');
    assert.equal(mine[1].token, true, 'the upsert goes under the planner token');
    assert.ok(mine[1].body.startsWith('PRE-FILLED'), 'the note body is re-created as it was');
  }
  assert.ok(!JSON.stringify(out).includes('PRE-FILLED') && !JSON.stringify(out).includes('SEAT-'), 'a body or token left the script');
});

test('S11 (AC2): a failed adoption holds that planner back; version_conflict on a delete does not; a failed upsert is re-sent token-less', async () => {
  const forbidden = boundSetup();
  const [X, Y] = forbidden.items;
  {
    const log = [];
    const agent = async (a) => { log.push(a.description); return { agentId: 'ag' }; };
    const out = await run(5, { runDir: forbidden.runDir, scratch: forbidden.scratch, tools: r5Tools({ log: [], updates: [], deleteFails: new Set([X.short]) }), agent });
    assert.deepEqual(out.held, [{ item: X.short, key: 'specification', code: 'forbidden' }], JSON.stringify(out));
    assert.ok(!log.some((d) => d.includes(X.short)), 'the held planner was dispatched');
    assert.ok(log.some((d) => d.includes(Y.short)), 'the other planner is dispatched');
  }
  const conflict = boundSetup();
  {
    const log = [];
    const agent = async (a) => { log.push(a.description); return { agentId: 'ag' }; };
    const nlog = [];
    const out = await run(5, { runDir: conflict.runDir, scratch: conflict.scratch, tools: r5Tools({ log: nlog, updates: [], deleteFails: new Set([conflict.items[0].short]), deleteCode: 'version_conflict' }), agent });
    assert.deepEqual(out.held, [], JSON.stringify(out));
    assert.equal(out.skipped.length, 1);
    assert.ok(log.some((d) => d.includes(conflict.items[0].short)), 'version_conflict still dispatches');
    assert.ok(!nlog.some((l) => l.item === conflict.items[0].short && l.op === 'upsert'), 'no upsert follows a skipped delete');
  }
  const upsert = boundSetup();
  {
    const log = [];
    const nlog = [];
    const agent = async (a) => { log.push(a.description); return { agentId: 'ag' }; };
    const x = upsert.items[0];
    const out = await run(5, { runDir: upsert.runDir, scratch: upsert.scratch, tools: r5Tools({ log: nlog, updates: [], upsertFailsWithToken: new Set([x.short]) }), agent });
    assert.equal(out.held.length, 1, JSON.stringify(out));
    assert.equal(out.held[0].item, x.short);
    assert.equal(out.held[0].resent, true);
    const ups = nlog.filter((l) => l.item === x.short && l.op === 'upsert');
    assert.deepEqual(ups.map((u) => u.token), [true, false], 'the failed upsert is re-sent token-less');
    assert.ok(!log.some((d) => d.includes(x.short)), 'the held planner was dispatched');
  }
});

test('S12 (AC2): R5 sends one launched update on its first round and none on the second', async () => {
  const { scratch, runDir } = boundSetup();
  const updates = [];
  const tools = r5Tools({ log: [], updates });
  await run(5, { runDir, scratch, tools });
  assert.equal(updates.length, 1);
  assert.equal(updates[0].hints.phase, 'launched');
  assert.equal(JSON.parse(readFileSync(runDir + '/state.json', 'utf8')).phase, 'launched');
  await run(5, { runDir, scratch, tools });
  assert.equal(updates.length, 1, 'a second round re-sent the launched update');
  // an empty state file becomes a full state document
  const empty = boundSetup({ state: {} });
  await run(5, { runDir: empty.runDir, scratch: empty.scratch, tools: r5Tools({ log: [], updates: [] }) });
  const st = JSON.parse(readFileSync(empty.runDir + '/state.json', 'utf8'));
  assert.equal(st.contract, 'run-wave/state-v1');
  assert.equal(st.phase, 'launched');
});

// S13 (AC3): R7 never advances; R8 picks the trigger by the review-role note, skips HOLD, and refuses on an unheld open Live-owed entry.
async function after7(opts = {}) {
  const s = r7Setup();
  const [X, Y] = s.items;
  const advances = [];
  const writes = [];
  const tools = r7Tools({
    withNotes: new Set(s.items.map((i) => i.id)),
    schemaKeys: { [X.id]: ['session-tracking', { key: 'review-checklist', role: 'review' }], [Y.id]: ['session-tracking'] },
    advances, writes, bodies: opts.bodies ? opts.bodies(X, Y) : {},
  });
  const result = resultFor(s.items, { [X.id]: true, [Y.id]: true });
  const out = await run(7, { runDir: s.runDir, scratch: s.scratch, tools, subst: literal(s.scratch, result) });
  assert.deepEqual(out.failing, [], JSON.stringify(out));
  assert.equal(advances.length, 0, 'R7 advanced');
  return { ...s, X, Y, advances, tools };
}

test('S13 (AC3): R8 sends one advance, start for a review-phase item and complete otherwise, and records post-run', async () => {
  const { scratch, runDir, X, Y, advances, tools } = await after7();
  const out = await run(8, { runDir, scratch, tools });
  assert.equal(advances.length, 1, JSON.stringify(out));
  assert.deepEqual(advances[0].ops.map((o) => [o.itemId, o.trigger]), [[X.id, 'start'], [Y.id, 'complete']]);
  assert.equal(advances[0].seatToken, 'COORD');
  assert.deepEqual(out.advanced.map((a) => [a.short, a.trigger, a.ok]), [[X.short, 'start', true], [Y.short, 'complete', true]]);
  assert.ok(existsSync(runDir + '/advance.json') && existsSync(runDir + '/advanced-items.json'));
  assert.equal(JSON.parse(readFileSync(runDir + '/state.json', 'utf8')).phase, 'post-run');
  assert.ok(out.followUps && typeof out.followUps === 'object', JSON.stringify(out));
});

test('S13 (AC3): R8 leaves a HOLD item out of the advance and reports it held', async () => {
  const { scratch, runDir, X, Y, advances, tools } = await after7();
  const out = await run(8, { runDir, scratch, tools, subst: { 'const HOLD = [];': "const HOLD = ['" + X.short + "'];" } });
  assert.deepEqual(advances[0].ops.map((o) => o.itemId), [Y.id]);
  assert.deepEqual(out.held, [X.short]);
});

test('S13 (AC3): R8 returns needs hold and sends no advance on an unheld open Live-owed entry; a run or deferred entry is no hold', async () => {
  const open = await after7({ bodies: (X) => ({ [X.id + ':session-tracking']: { body: 'Outcome: x\n**Live-owed**: desktop check of the pane; terminal check run 2026-11-19: pass', version: 1 } }) });
  const out = await run(8, { runDir: open.runDir, scratch: open.scratch, tools: open.tools });
  assert.deepEqual(out, { needs: 'hold', items: [open.X.short] });
  assert.equal(open.advances.length, 0, 'an advance was sent despite the open entry');
  const closed = await after7({ bodies: (X, Y) => ({ [X.id + ':session-tracking']: { body: '**Live-owed**: terminal check run 2026-11-19: pass; desktop check deferred: aaaabbbb', version: 1 }, [Y.id + ':session-tracking']: { body: '**Live-owed**: none', version: 1 } }) });
  const out2 = await run(8, { runDir: closed.runDir, scratch: closed.scratch, tools: closed.tools });
  assert.equal(out2.needs, undefined, JSON.stringify(out2));
  assert.equal(closed.advances.length, 1);
});

// S14 (AC4): R11 closes through the MCP runs_write tool.
function r11Setup({ postRun, closeFails } = {}) {
  const s = boundSetup({ meta: postRun ? { postRun } : {} });
  writeFileSync(s.runDir + '/result.json', JSON.stringify(resultFor(s.items, Object.fromEntries(s.items.map((i) => [i.id, true])))));
  const calls = [];
  const reads = { seats: 0 };
  const sent = [];
  const tools = {
    [ikey('runs_read')]: async (a) => { if (a.select === 'seats') reads.seats += 1; return env({ seats: [] }); },
    [ikey('runs_write')]: async (a) => {
      calls.push(a);
      if (a.ops[0].op === 'close' && closeFails && closeFails > 0) { closeFails -= 1; throw thrownElement('invalid_run_state'); }
      return env({ results: a.ops.map(() => ({ ok: true, runId: SERVER_RUN, snapshot: { reason: 'completed', openDecisions: 2 } })) });
    },
  };
  return { ...s, calls, reads, sent, tools, sendMessage: async (m) => { sent.push(m); return {}; } };
}

test('S14 (AC4): R11 sends the close through the runs_write tool under the coordinator token and then the closed report', async () => {
  const s = r11Setup();
  const out = await run(11, { runDir: s.runDir, scratch: s.scratch, tools: s.tools, sendMessage: s.sendMessage });
  assert.deepEqual(out, { closed: true, reason: 'completed', openDecisions: 2, reported: true });
  const close = s.calls.filter((c) => c.ops[0].op === 'close');
  assert.equal(close.length, 1);
  assert.equal(close[0].seatToken, 'COORD');
  assert.equal(close[0].ops[0].runId, SERVER_RUN);
  assert.ok(existsSync(s.runDir + '/close.json') && existsSync(s.runDir + '/changed-files.txt'));
  assert.equal(s.sent.length, 1);
  assert.equal(s.sent[0].to, 'main');
  assert.ok(s.sent[0].message.startsWith('TO report: run '), s.sent[0].message);
  assert.ok(/ closed: /.test(s.sent[0].message) && /open decisions 2/.test(s.sent[0].message), s.sent[0].message);
});

test('S14 (AC4): invalid_run_state runs the seat sweep and closes once more', async () => {
  const s = r11Setup({ closeFails: 1 });
  const out = await run(11, { runDir: s.runDir, scratch: s.scratch, tools: s.tools, sendMessage: s.sendMessage });
  assert.equal(out.closed, true, JSON.stringify(out));
  assert.equal(s.calls.filter((c) => c.ops[0].op === 'close').length, 2);
  assert.equal(s.reads.seats, 2, 'one sweep before the first close and one between the two');
  const twice = r11Setup({ closeFails: 2 });
  const failed = await run(11, { runDir: twice.runDir, scratch: twice.scratch, tools: twice.tools, sendMessage: twice.sendMessage });
  assert.equal(failed.failed, true, 'a second invalid_run_state is reported, not retried again');
  assert.equal(twice.sent.length, 0);
});

test('S14 (AC4): returned post-run steps stop R11 before the close unless STEPS_DONE is true', async () => {
  const postRun = [{ step: 'live-acceptance', when: 'post-run', rule: 'live-checks' }];
  const s = r11Setup({ postRun });
  const out = await run(11, { runDir: s.runDir, scratch: s.scratch, tools: s.tools, sendMessage: s.sendMessage });
  assert.deepEqual(out.steps, [{ id: 'live-acceptance', rule: 'live-checks' }], JSON.stringify(out));
  assert.equal(s.calls.filter((c) => c.ops[0].op === 'close').length, 0);
  const done = await run(11, { runDir: s.runDir, scratch: s.scratch, tools: s.tools, sendMessage: s.sendMessage, subst: { 'const STEPS_DONE = false;': 'const STEPS_DONE = true;' } });
  assert.equal(done.closed, true, JSON.stringify(done));
});

test('S14 (AC4): R11 passes REASON to the close op; no js block of code-mode.md calls fetch or the REST path', async () => {
  const s = r11Setup();
  const out = await run(11, { runDir: s.runDir, scratch: s.scratch, tools: s.tools, sendMessage: s.sendMessage, subst: { 'const REASON = null;': "const REASON = 'cancelled';" } });
  assert.equal(out.reason, 'cancelled', JSON.stringify(out));
  for (const block of jsBlocks(DOC)) {
    assert.ok(!block.includes('fetch('), 'a js block calls fetch(');
    assert.ok(!block.includes('/api/v1/'), 'a js block names the REST path');
  }
  const post = readFileSync(join(PLUGIN_ROOT, 'skills', 'run-wave', 'references', 'post-run.md'), 'utf8');
  assert.ok(post.includes('Close through the `runs_write` tool, not REST: the retrospective trigger watches the tool call.'));
});

// S15 (AC5): R10's return projection.
test('S15 (AC5): R10 returns only event, sent, needs and wait, and SendMessage receives the helper block', async () => {
  const SECRET = 'SECRET-NOTE-BODY';
  for (const [event, allow, wait] of [['planned', undefined, false], ['planned', { mcp: false }, true], ['decision', undefined, true], ['paused', undefined, true], ['closed', undefined, false]]) {
    const s = boundSetup({ meta: allow ? { allow } : {} });
    writeFileSync(s.runDir + '/note-body.txt', SECRET);
    const sent = [];
    const tools = { [ikey('runs_read')]: async () => env({ status: 'open', hints: { phase: 'planned' } }) };
    const out = await run(10, { runDir: s.runDir, scratch: s.scratch, tools, sendMessage: async (m) => { sent.push(m); return {}; }, subst: { "const EVENT = 'planned';": "const EVENT = '" + event + "';" } });
    assert.deepEqual(Object.keys(out), ['event', 'sent', 'needs', 'wait'], event + ' ' + JSON.stringify(out));
    assert.equal(out.event, event);
    assert.equal(out.sent, true);
    assert.equal(out.wait, wait, event + ' ' + JSON.stringify(out));
    if (allow) assert.ok(out.needs.startsWith('approve launch; allow rules missing'), out.needs);
    assert.equal(sent.length, 1);
    assert.equal(sent[0].to, 'main');
    assert.ok(sent[0].message.startsWith('TO report: run '), sent[0].message);
    assert.ok(sent[0].message.includes(' ' + event + ': '));
    const helperBlock = spawnSync(process.execPath, [HELPER, 'report', '--plan', s.runDir + '/plan.json', '--event', event].concat(event === 'planned' ? [] : event === 'closed' ? [] : ['--run', s.runDir + '/run.json']), { encoding: 'utf8' });
    assert.equal(sent[0].message, helperBlock.stdout.replace(/\n$/, ''), 'the message is the helper block verbatim');
    for (const text of [JSON.stringify(out), sent[0].message]) assert.ok(!text.includes('COORD') && !text.includes(SECRET));
  }
});

test('S15 (AC5): with REPORT_TO null R10 returns the block for the user and sends nothing', async () => {
  const s = boundSetup();
  const sent = [];
  const out = await run(10, { runDir: s.runDir, scratch: s.scratch, tools: {}, sendMessage: async (m) => { sent.push(m); return {}; }, subst: { "const REPORT_TO = 'main';": 'const REPORT_TO = null;' } });
  assert.equal(out.sent, false);
  assert.ok(out.block.startsWith('TO report: run '));
  assert.equal(sent.length, 0);
});

// S16 (AC6): the Coverage table.
const COVERAGE_ROWS = [
  'SKILL F0', 'SKILL F1', 'SKILL F2', 'SKILL F3', 'SKILL F4', 'SKILL F5', 'SKILL F6', 'SKILL F7', 'SKILL F8',
  'SKILL Step 1', 'SKILL Step 2 snapshot', 'SKILL Step 2 awaiting_decision', 'SKILL Step 3 plan', 'SKILL Step 3 lateCarryIns',
  'SKILL Step 4', 'SKILL Step 5 open', 'SKILL Step 5a pre-entered', 'SKILL Step 5a Method A assign', 'SKILL Step 5a Method A adopt-notes', 'SKILL Step 5a planned report',
  'SKILL Step 6a', 'SKILL Step 6b', 'SKILL Step 7 post-run', 'SKILL Step 8 review', 'SKILL Step 8a', 'SKILL Step 8b', 'SKILL Step 9', 'SKILL Stalled seats',
  'method-b 0', 'method-b 1', 'method-b 2', 'method-b 3', 'method-b 4 scan', 'method-b 4a', 'method-b 5 persist', 'method-b 5a', 'method-b 6',
  'post-run Step 0 load', 'post-run Step 0b', 'post-run Step 1', 'post-run Step 2', 'post-run Step 3 item 1', 'post-run Step 3 item 2', 'post-run Step 4',
  'post-run Step 5 generic', 'post-run Step 5 handoff', 'post-run Step 5 review-wave', 'post-run Step 5 fix cycle', 'post-run Step 5 wave parent review',
  'post-run Step 6', 'post-run Step 7 close', 'post-run Step 7 run report', 'post-run Step 7a', 'post-run Step 7b',
];

test('S16 (AC6): the Coverage table has a row for every step and each cell is a recipe with a section or prose', () => {
  const rows = new Map();
  for (const line of section('Coverage').split('\n')) {
    const m = /^\| (.+?) \| (.+?) \|$/.exec(line);
    if (m && m[1] !== 'Step' && !/^-+$/.test(m[1])) rows.set(m[1], m[2]);
  }
  for (const want of COVERAGE_ROWS) {
    const hit = [...rows.keys()].filter((k) => k.startsWith(want));
    assert.equal(hit.length, 1, 'rows starting with "' + want + '": ' + hit.length);
  }
  assert.equal(rows.size, COVERAGE_ROWS.length, 'the table has a row the test does not know');
  const prose = [...rows.entries()].filter(([, v]) => v === 'prose').map(([k]) => k);
  for (const want of ['SKILL F1', 'SKILL F5', 'SKILL F8', 'SKILL Step 1', 'SKILL Step 2 awaiting_decision', 'SKILL Step 3 lateCarryIns', 'method-b 4 scan', 'method-b 4a', 'method-b 5a', 'SKILL Stalled seats', 'post-run Step 3 item 1', 'post-run Step 5 handoff', 'post-run Step 5 review-wave', 'post-run Step 5 fix cycle', 'post-run Step 5 wave parent review', 'post-run Step 6', 'post-run Step 7 run report', 'post-run Step 7a', 'post-run Step 7b', 'SKILL Step 8a', 'SKILL Step 8b', 'SKILL Step 9']) {
    assert.ok(prose.some((k) => k.startsWith(want)), want + ' must be prose');
  }
  for (const [k, v] of rows) {
    if (v === 'prose') continue;
    const ids = v.match(/R\d+/g);
    assert.ok(ids && ids.join(', ') === v.replace(/ and /g, ', '), k + ' cell is not a recipe list: ' + v);
    for (const id of ids) assert.ok(new RegExp('^## ' + id + ': ', 'm').test(DOC), id + ' has no section (' + k + ')');
  }
});

// S17 and S18 (AC7): R1 resume check and snapshot subcommand.
test('S17 (AC7): R1 returns resume for an open r- run and makes no snapshot, plan or probe call', async () => {
  const { scratch, repo } = r1Fixture();
  const commands = [];
  const lists = [];
  const tools = {
    [ikey('runs_read')]: async (a) => {
      lists.push(a);
      if (a.status === 'open') return env({ items: [{ runId: SERVER_RUN, status: 'open', hints: { plan: 'r-20260928-aaaa', phase: 'launched' } }, { runId: 'x', status: 'open', hints: { plan: 'manual' } }] });
      return env({ items: [] });
    },
  };
  const shellFn = (command) => { commands.push(command); return shell(command, { cwd: repo, home: scratch }); };
  const out = await run(1, { scratch, cwd: repo, tools, shellFn });
  assert.deepEqual(out, { resume: [{ run8: SERVER_RUN.slice(0, 8), plan: 'r-20260928-aaaa', status: 'open', phase: 'launched' }] });
  assert.deepEqual(lists.map((l) => l.status), ['open', 'paused']);
  assert.equal(commands.length, 0, 'a command ran before the resume return');
  assert.ok(!existsSync(scratch + '/run-wave/plan-draft.json') && !existsSync(scratch + '/run-wave/snapshot.json'));
});

test('S17 (AC7): a run whose hints.plan is not an r- plan id, and a paused r- run, are judged by that field only', async () => {
  const { scratch, repo, tools } = r1Fixture();
  tools[ikey('runs_read')] = async (a) => env({ items: a.status === 'paused' ? [{ runId: SERVER_RUN, status: 'paused', hints: { plan: 'r-1', phase: 'review' } }] : [{ runId: 'y', status: 'open', hints: {} }] });
  const out = await run(1, { scratch, cwd: repo, tools });
  assert.deepEqual(out.resume, [{ run8: SERVER_RUN.slice(0, 8), plan: 'r-1', status: 'paused', phase: 'review' }]);
});

function handle(stdout, exitCode) {
  const out = { stdout, stderr: '', exitCode };
  let nothrow = false;
  const p = {
    nothrow() { nothrow = true; return p; },
    then: (res, rej) => (exitCode !== 0 && !nothrow ? rej(Object.assign(new Error('ShellError'), out)) : res(out)),
  };
  return p;
}
// Runs every command for real except the snapshot subcommand, which answers `reply(command)`.
function snapshotShell(repo, home, reply, seen) {
  return (command) => {
    if (/ snapshot /.test(command)) {
      seen.push(command);
      return reply(command);
    }
    return shell(command, { cwd: repo, home });
  };
}
function fixtureSnapshot(mutate) {
  const snap = fixture('run-planner/independent-two.json');
  snap.rulesServed = PROTOCOL_RULES;
  if (mutate) mutate(snap);
  return snap;
}
const outPathOf = (command) => /--out "([^"]+)"/.exec(command)[1];

test('S18 (AC7): R1 uses the snapshot subcommand when it exits 0 and then reads no item by hand', async () => {
  const { scratch, repo, tools } = r1Fixture();
  const reads = [];
  const real = tools[ikey('items_read')];
  tools[ikey('items_read')] = async (a) => { reads.push(a); return real(a); };
  const seen = [];
  const snap = fixtureSnapshot();
  const shellFn = snapshotShell(repo, scratch, (command) => {
    mkdirSync(dirname(outPathOf(command)), { recursive: true });
    writeFileSync(outPathOf(command), JSON.stringify(snap));
    return handle(JSON.stringify({ ok: true, out: outPathOf(command), candidates: 2, needs: { scopes: [] }, skipped: [], warnings: [] }) + '\n', 0);
  }, seen);
  const out = await run(1, { scratch, cwd: repo, tools, shellFn, subst: { 'const ANCESTOR_ID = null;': "const ANCESTOR_ID = 'A0';" } });
  assert.ok(out && out.explain && /^r-/.test(out.runId) && out.lateCarryIns === 0, JSON.stringify(out));
  assert.equal(seen.length, 1);
  assert.ok(/--root "[^"]+" --ancestor "A0" --out "/.test(seen[0]), seen[0]);
  assert.equal(reads.length, 0, 'the subcommand path made hand reads');
  const items = await run(1, { scratch, cwd: repo, tools, shellFn, subst: { 'const ITEM_IDS = null;': "const ITEM_IDS = ['i1', 'i2'];" } });
  assert.ok(items.explain, JSON.stringify(items));
  assert.ok(/--items "i1,i2"/.test(seen[1]), seen[1]);
});

test('S18 (AC7): exit 2 for no REST URL falls back to the hand reads; any other non-zero exit is a failed snapshot step', async () => {
  const fallback = r1Fixture();
  const seen = [];
  const noRest = snapshotShell(fallback.repo, fallback.scratch, () => handle('{"error":"invalid args","detail":"no REST API URL resolves: assemble the snapshot by hand"}\n', 2), seen);
  const out = await run(1, { scratch: fallback.scratch, cwd: fallback.repo, tools: fallback.tools, shellFn: noRest, subst: { 'const ANCESTOR_ID = null;': "const ANCESTOR_ID = 'A-SHORT';" } });
  assert.ok(out && out.explain, JSON.stringify(out));
  const snap = JSON.parse(readFileSync(fallback.scratch + '/run-wave/snapshot.json', 'utf8'));
  assert.equal(snap.contract, 'run-wave/snapshot-v1');
  assert.ok(snap.candidates.length > 0, 'the hand reads built the candidates');
  const other = r1Fixture();
  const bad = snapshotShell(other.repo, other.scratch, () => handle('{"error":"read failed","detail":{"read":"items_read","code":"unavailable"}}\n', 3), []);
  const failed = await run(1, { scratch: other.scratch, cwd: other.repo, tools: other.tools, shellFn: bad, subst: { 'const ANCESTOR_ID = null;': "const ANCESTOR_ID = 'A-SHORT';" } });
  assert.equal(failed.failed, true);
  assert.equal(failed.step, 'snapshot');
  assert.equal(failed.code, 3);
  const exit2 = r1Fixture();
  const other2 = snapshotShell(exit2.repo, exit2.scratch, () => handle('{"error":"invalid args","detail":"--scopes x is not a JSON object file"}\n', 2), []);
  const f2 = await run(1, { scratch: exit2.scratch, cwd: exit2.repo, tools: exit2.tools, shellFn: other2, subst: { 'const ANCESTOR_ID = null;': "const ANCESTOR_ID = 'A-SHORT';" } });
  assert.equal(f2.failed, true, 'exit 2 for another reason must not fall back');
  assert.equal(f2.code, 2);
});

test('S18 (AC7): R1 returns needs.scopes from the subcommand summary and passes SCOPES_FILE through', async () => {
  const { scratch, repo, tools } = r1Fixture();
  const seen = [];
  const shellFn = snapshotShell(repo, scratch, (command) => {
    writeFileSync(scratch + '/snap-ignored.json', '{}');
    return handle(JSON.stringify({ ok: true, out: outPathOf(command), candidates: 2, needs: { scopes: ['aaaa', 'bbbb'] }, skipped: [], warnings: [] }) + '\n', 0);
  }, seen);
  const out = await run(1, { scratch, cwd: repo, tools, shellFn, subst: { 'const ANCESTOR_ID = null;': "const ANCESTOR_ID = 'A0';", 'const SCOPES_FILE = null;': "const SCOPES_FILE = 'S.json';" } });
  assert.deepEqual(out, { needs: { scopes: ['aaaa', 'bbbb'] } });
  assert.ok(seen[0].includes('--scopes "S.json"'), seen[0]);
});

test('S18 (AC7): a snapshot without the rules feature stops F2, one without the protocol rules stops F3', async () => {
  const run1 = async (mutate) => {
    const { scratch, repo, tools } = r1Fixture();
    const shellFn = snapshotShell(repo, scratch, (command) => {
      mkdirSync(dirname(outPathOf(command)), { recursive: true });
      writeFileSync(outPathOf(command), JSON.stringify(fixtureSnapshot(mutate)));
      return handle('{"ok":true,"out":"x","candidates":2,"needs":{"scopes":[]},"skipped":[],"warnings":[]}\n', 0);
    }, []);
    const out = await run(1, { scratch, cwd: repo, tools, shellFn, subst: { 'const ANCESTOR_ID = null;': "const ANCESTOR_ID = 'A0';" } });
    assert.ok(!existsSync(scratch + '/run-wave/plan-draft.json'), 'plan ran after a stop');
    return out;
  };
  assert.deepEqual(await run1((s) => { s.features = ['seats', 'dispatchBySeat']; }), { stopped: true, check: 'F2', missing: ['rules'] });
  assert.deepEqual(await run1((s) => { s.rulesServed = [{ key: 'protocol.entry-seat', rulesVersion: '1' }]; }), { stopped: true, check: 'F3', missing: ['protocol.in-phase-seat', 'protocol.read-only-agent'] });
});

// S19 (AC8): R2 on a temp repo.
function gitIn(repo, ...args) {
  const r = spawnSync('git', ['-C', repo, ...args], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
  return r.stdout;
}
function r2Setup({ scopeId, method = 'B', existingWorktree = true } = {}) {
  const { dir, scratch } = mk();
  const repo = gitRepo(dir);
  const { runDir, plan } = makePlan(scratch, method);
  const existing = dir + '/wt-existing';
  const fresh = dir + '/wt-new';
  if (existingWorktree) gitIn(repo, 'worktree', 'add', '-q', '-b', 'b-existing', existing);
  plan.args.repoRoot = repo;
  plan.meta.worktreesToCreate = [{ path: existing, branch: 'b-existing', base: 'HEAD' }, { path: fresh, branch: 'b-new', base: 'HEAD' }];
  if (scopeId) plan.meta.scopeId = scopeId;
  writeFileSync(runDir + '/plan.json', JSON.stringify(plan));
  const store = new Map();
  const calls = { runs: [], advance: [], notes: [], contexts: 0 };
  const ctx = { role: 'queue', canAdvance: true, missing: [] };
  const declared = { value: ['run-pointers', 'session-tracking'] };
  const tools = {
    [ikey('runs_write')]: async (a) => {
      calls.runs.push(a);
      return env({ results: a.ops.map((op) => ({ ok: true, runId: SERVER_RUN, coordinatorToken: 'COORD-SECRET', seatToken: 'SEAT-SECRET' })) });
    },
    [ikey('items_read')]: async (a) => {
      if (a.select === 'context') { calls.contexts += 1; return env({ item: { role: ctx.role }, gateStatus: { canAdvance: ctx.canAdvance, missing: ctx.missing } }); }
      if (a.select === 'schema') return env({ notes: declared.value.map((key) => ({ key, role: key === 'run-pointers' ? 'queue' : 'work' })) });
      throw new Error('unexpected select ' + a.select);
    },
    [ikey('notes_read')]: async (a) => {
      const hit = store.get(a.itemId + ':' + a.key);
      if (!hit) throw thrown('not_found');
      return env({ key: a.key, body: hit.body, version: hit.version });
    },
    [ikey('notes_write')]: async (a) => {
      calls.notes.push(a);
      const op = a.ops[0];
      const k = op.itemId + ':' + op.key;
      store.set(k, { body: op.body, version: ((store.get(k) || {}).version || 0) + 1 });
      return env({ results: [{ ok: true }] });
    },
    [ikey('advance')]: async (a) => { calls.advance.push(a); return env({ results: a.ops.map((o) => ({ ok: true, itemId: o.itemId })) }); },
  };
  return { dir, scratch, repo, runDir, plan, existing, fresh, store, calls, ctx, declared, tools, items: plan.args.items };
}

test('S19 (AC8): R2 creates the missing worktree, reuses an existing one, and with no scope skips the parent check', async () => {
  const s = r2Setup();
  const out = await run(2, { runDir: s.runDir, scratch: s.scratch, tools: s.tools });
  assert.deepEqual(out.worktrees, { created: 1, reused: 1 }, JSON.stringify(out));
  assert.ok(gitIn(s.repo, 'worktree', 'list', '--porcelain').replace(/\\/g, '/').includes(s.fresh.replace(/\\/g, '/')), 'the new worktree exists');
  assert.equal(gitIn(s.repo, 'branch', '--list', 'b-new').includes('b-new'), true);
  assert.equal(out.parent, 'skipped');
  assert.equal(out.pointer, 'none', 'no scope and two items: nowhere to write the pointer');
  assert.equal(s.calls.advance.length, 0);
  assert.ok(!JSON.stringify(out).includes('SECRET'));
  // a second run finds both worktrees
  const again = await run(2, { runDir: s.runDir, scratch: s.scratch, tools: s.tools });
  assert.deepEqual(again.worktrees, { created: 0, reused: 2 }, JSON.stringify(again));
});

test('S19 (AC8): an existing branch is added without -b', async () => {
  const s = r2Setup({ existingWorktree: false });
  gitIn(s.repo, 'branch', 'b-existing');
  const out = await run(2, { runDir: s.runDir, scratch: s.scratch, tools: s.tools });
  assert.deepEqual(out.worktrees, { created: 2, reused: 0 }, JSON.stringify(out));
});

test('S19 (AC8): the parent check starts a queue parent that can advance and writes the pointer to run-pointers token-less', async () => {
  const PARENT = 'dddddddd-4444-4444-8444-444444444444';
  const s = r2Setup({ scopeId: PARENT });
  const out = await run(2, { runDir: s.runDir, scratch: s.scratch, tools: s.tools });
  assert.equal(out.parent, 'started', JSON.stringify(out));
  assert.deepEqual(out.parentMissing, []);
  assert.equal(s.calls.advance.length, 1);
  assert.deepEqual(s.calls.advance[0].ops, [{ op: 'transition', itemId: PARENT, trigger: 'start' }]);
  assert.equal(s.calls.advance[0].seatToken, 'COORD-SECRET');
  assert.equal(s.calls.notes.length, 1);
  const w = s.calls.notes[0];
  assert.equal(w.ops[0].key, 'run-pointers');
  assert.equal(w.ops[0].itemId, PARENT);
  assert.equal(w.ops[0].body, 'run-wave: ' + s.plan.args.runId + ' run=' + SERVER_RUN.slice(0, 8) + ' parent=started');
  assert.equal(w.ops[0].expectedVersion, 0);
  assert.equal(w.seatToken, undefined, 'a non-member parent is written token-less');
  assert.equal(out.pointer, 'run-pointers');
});

test('S19 (AC8): a gate-blocked parent writes blocked:<keys> and returns parentMissing; session-tracking is the key when run-pointers is undeclared', async () => {
  const PARENT = 'dddddddd-4444-4444-8444-444444444444';
  const s = r2Setup({ scopeId: PARENT });
  s.ctx.canAdvance = false;
  s.ctx.missing = ['specification', 'plan'];
  s.declared.value = ['session-tracking'];
  const out = await run(2, { runDir: s.runDir, scratch: s.scratch, tools: s.tools });
  assert.equal(out.parent, 'blocked:specification,plan', JSON.stringify(out));
  assert.deepEqual(out.parentMissing, ['specification', 'plan']);
  assert.equal(s.calls.advance.length, 0, 'a blocked parent is not advanced');
  assert.equal(s.calls.notes[0].ops[0].key, 'session-tracking');
  assert.ok(s.calls.notes[0].ops[0].body.endsWith('parent=blocked:specification,plan'));
  // a parent in another role is recorded by its role
  const work = r2Setup({ scopeId: PARENT });
  work.ctx.role = 'work';
  const o2 = await run(2, { runDir: work.runDir, scratch: work.scratch, tools: work.tools });
  assert.equal(o2.parent, 'work');
  assert.equal(work.calls.advance.length, 0);
});

test('S19 (AC8): a member target is written under the coordinator token; a seat-less item that owns session-tracking gets no pointer', async () => {
  const s = r2Setup();
  const X = s.items[0];
  const plan = JSON.parse(readFileSync(s.runDir + '/plan.json', 'utf8'));
  plan.meta.scopeId = X.id;
  writeFileSync(s.runDir + '/plan.json', JSON.stringify(plan));
  s.declared.value = ['run-pointers'];
  const out = await run(2, { runDir: s.runDir, scratch: s.scratch, tools: s.tools });
  assert.equal(out.parent, 'skipped', 'a run member is no parent');
  assert.equal(s.calls.notes[0].seatToken, 'COORD-SECRET');
  const own = r2Setup();
  const plan2 = JSON.parse(readFileSync(own.runDir + '/plan.json', 'utf8'));
  plan2.meta.scopeId = plan2.args.items[0].id;
  writeFileSync(own.runDir + '/plan.json', JSON.stringify(plan2));
  own.declared.value = ['session-tracking'];
  const o2 = await run(2, { runDir: own.runDir, scratch: own.scratch, tools: own.tools });
  assert.equal(o2.pointer, 'none');
  assert.equal(own.calls.notes.length, 0, 'the pointer would block the implementer own session-tracking write');
});

test('S19 (AC8): Method B sends no assign and records planned; Method A assigns every schema seat once', async () => {
  const b = r2Setup();
  const out = await run(2, { runDir: b.runDir, scratch: b.scratch, tools: b.tools });
  assert.equal(out.seats, 0);
  const ops = b.calls.runs.flatMap((c) => c.ops);
  assert.deepEqual(ops.map((o) => o.op), ['open', 'update']);
  assert.equal(ops[1].hints.phase, 'planned');
  assert.equal(JSON.parse(readFileSync(b.runDir + '/state.json', 'utf8')).phase, 'planned');
  const a = r2Setup({ method: 'A' });
  const outA = await run(2, { runDir: a.runDir, scratch: a.scratch, tools: a.tools });
  assert.ok(outA.seats > 0 && outA.failed === 0 && outA.tokensWritten === outA.seats, JSON.stringify(outA));
  const assigns = a.calls.runs.flatMap((c) => c.ops).filter((o) => o.op === 'assign');
  assert.equal(assigns.length, outA.seats);
  assert.equal(a.calls.runs.find((c) => c.ops[0].op === 'assign').atomic, true);
  // a rerun assigns only the seats whose token file is absent: none
  const rerun = await run(2, { runDir: a.runDir, scratch: a.scratch, tools: a.tools });
  assert.equal(rerun.seats, 0, JSON.stringify(rerun));
});

test('S19 (AC8): under entryMode pre-entered R2 starts the queue items and stops before assign when one stays in queue', async () => {
  const s = r2Setup({ method: 'A' });
  const plan = JSON.parse(readFileSync(s.runDir + '/plan.json', 'utf8'));
  plan.args.entryMode = 'pre-entered';
  writeFileSync(s.runDir + '/plan.json', JSON.stringify(plan));
  const entered = new Set();
  const tools = {
    ...s.tools,
    [ikey('items_read')]: async (a) => {
      if (a.select === 'context') return env({ item: { role: entered.has(a.itemId) ? 'work' : 'queue' }, gateStatus: { canAdvance: true, missing: [] } });
      return s.tools[ikey('items_read')](a);
    },
    [ikey('advance')]: async (a) => { s.calls.advance.push(a); for (const o of a.ops) if (o.itemId !== plan.args.items[1].id) entered.add(o.itemId); return env({ results: a.ops.map((o) => ({ ok: entered.has(o.itemId) })) }); },
  };
  const out = await run(2, { runDir: s.runDir, scratch: s.scratch, tools });
  assert.deepEqual(out, { stopped: 'pre-entered', notEntered: [plan.args.items[1].short] });
  assert.equal(s.calls.advance.length, 1);
  assert.equal(s.calls.advance[0].ops.length, 2);
  assert.ok(!s.calls.runs.flatMap((c) => c.ops).some((o) => o.op === 'assign'), 'an assign was sent after the stop');
});

test('S19 (AC8): a rerun on a bound plan sends no second open and leaves one pointer line with the new parent= value', async () => {
  const PARENT = 'dddddddd-4444-4444-8444-444444444444';
  const s = r2Setup({ scopeId: PARENT });
  await run(2, { runDir: s.runDir, scratch: s.scratch, tools: s.tools });
  s.ctx.role = 'work';
  const out = await run(2, { runDir: s.runDir, scratch: s.scratch, tools: s.tools });
  assert.equal(out.parent, 'work', JSON.stringify(out));
  assert.equal(s.calls.runs.flatMap((c) => c.ops).filter((o) => o.op === 'open').length, 1, 'a second open was sent');
  const body = s.store.get(PARENT + ':run-pointers').body;
  assert.equal(body.split('\n').length, 1, body);
  assert.ok(body.endsWith('parent=work') && body.includes('run=' + SERVER_RUN.slice(0, 8)), body);
  // another run's line in the same note is kept
  s.store.set(PARENT + ':run-pointers', { body: 'run-wave: r-other run=12345678 parent=started\n' + body, version: 9 });
  await run(2, { runDir: s.runDir, scratch: s.scratch, tools: s.tools });
  assert.equal(s.store.get(PARENT + ':run-pointers').body.split('\n').length, 2);
});

// S20 (AC9): R9 generic review dispatch.
function r9Setup({ review, warnings = [], advanced } = {}) {
  const s = boundSetup({ meta: review ? { review } : {} });
  writeFileSync(s.runDir + '/result.json', JSON.stringify(resultFor(s.items, Object.fromEntries(s.items.map((i) => [i.id, true])))));
  writeFileSync(s.runDir + '/verify.json', JSON.stringify({ ok: true, items: [], warnings }));
  writeFileSync(s.runDir + '/advanced-items.json', JSON.stringify(advanced || s.items.map((i, n) => ({ short: i.short, itemId: i.id, trigger: n === 0 ? 'start' : 'complete', ok: true }))));
  const calls = [];
  const issued = [];
  let n = 0;
  const tools = {
    [ikey('runs_write')]: async (a) => {
      calls.push(a);
      return env({ results: a.ops.map((op) => { const seatToken = 'REV-SECRET-' + (n += 1); if (op.op === 'assign') issued.push(seatToken); return { ok: true, seatToken }; }) });
    },
    [ikey('items_read')]: async () => env({ dispatch: { review: { agent: 'task-orchestrator:reviewer' } } }),
  };
  return { ...s, calls, issued, tools };
}

test('S20 (AC9): R9 dispatches a reviewer per item advanced with start: review class model and effort, raw review-prompt text, reviewer:<short>', async () => {
  const s = r9Setup();
  const agents = [];
  const out = await run(9, { runDir: s.runDir, scratch: s.scratch, tools: s.tools, agent: async (a) => { agents.push(a); return { agentId: 'rv-1' }; } });
  const X = s.items[0];
  assert.deepEqual(out, { dispatched: [{ item: X.short, agentId: 'rv-1' }], waiting: [], already: 1 });
  assert.equal(agents.length, 1, 'only the item advanced with start gets a reviewer');
  const policy = JSON.parse(readFileSync(join(PLUGIN_ROOT, 'dispatch-policy.json'), 'utf8')).classes.review;
  assert.equal(agents[0].description, 'reviewer:' + X.short);
  assert.equal(agents[0].model, policy.model);
  assert.equal(agents[0].effort, policy.effort);
  assert.equal(agents[0].subagent_type, 'task-orchestrator:reviewer');
  const raw = spawnSync(process.execPath, [HELPER, 'review-prompt', '--plan', s.runDir + '/plan.json', '--item', X.short, '--result', s.runDir + '/result.json'], { encoding: 'utf8' });
  assert.equal(raw.status, 0, raw.stderr);
  assert.equal(agents[0].prompt, raw.stdout, 'the prompt is the helper text verbatim');
  assert.ok(!agents[0].prompt.includes('REV-SECRET') && !JSON.stringify(out).includes('REV-SECRET'));
  const assign = s.calls.find((c) => c.ops[0].op === 'assign');
  assert.deepEqual(assign.ops[0], { op: 'assign', runId: SERVER_RUN, itemId: X.id, seat: 'reviewer' });
  assert.equal(assign.seatToken, 'COORD');
  assert.equal(readFileSync(s.runDir + '/tokens/' + X.short + '.reviewer.token', 'utf8').trim(), s.issued[0]);
  assert.equal(JSON.parse(readFileSync(s.runDir + '/state.json', 'utf8')).phase, 'review');
});

test('S20 (AC9): a handoff review mode and an unrouted unowned docs hit dispatch nothing', async () => {
  const hand = r9Setup({ review: { mode: 'handoff', skill: 'proj-review', onFail: 'stop' } });
  const agents = [];
  const agent = async (a) => { agents.push(a); return { agentId: 'x' }; };
  const out = await run(9, { runDir: hand.runDir, scratch: hand.scratch, tools: hand.tools, agent });
  assert.deepEqual(out, { handoff: { skill: 'proj-review', step: null, onFail: 'stop' } });
  assert.equal(agents.length, 0);
  assert.equal(hand.calls.length, 0, 'a handoff run made a server write');
  const unowned = r9Setup({ warnings: ['aaaaaaaa docs sweep unowned docs/guide.md', 'untagged commit abc1234'] });
  const o2 = await run(9, { runDir: unowned.runDir, scratch: unowned.scratch, tools: unowned.tools, agent });
  assert.deepEqual(o2, { needs: 'route-unowned', unowned: ['aaaaaaaa docs sweep unowned docs/guide.md'] });
  assert.equal(agents.length, 0);
  assert.equal(unowned.calls.length, 0);
  const routed = await run(9, { runDir: unowned.runDir, scratch: unowned.scratch, tools: unowned.tools, agent, subst: { 'const UNOWNED_ROUTED = false;': 'const UNOWNED_ROUTED = true;' } });
  assert.equal(routed.dispatched.length, 1, JSON.stringify(routed));
  assert.equal(agents.length, 1);
});

test('S20 (AC9): run_at_capacity on the reviewer assign holds that reviewer back', async () => {
  const s = r9Setup();
  s.tools[ikey('runs_write')] = async (a) => { if (a.ops[0].op === 'assign') throw thrownElement('run_at_capacity', { active: 3, max: 3 }); return env({ results: a.ops.map(() => ({ ok: true })) }); };
  const agents = [];
  const out = await run(9, { runDir: s.runDir, scratch: s.scratch, tools: s.tools, agent: async (a) => { agents.push(a); return {}; } });
  assert.deepEqual(out, { dispatched: [], waiting: [s.items[0].short], already: 0 });
  assert.equal(agents.length, 0);
});

test('S20 (AC9): a rerun after run_at_capacity skips the reviewer already dispatched and never re-assigns it', async () => {
  const base = r9Setup();
  const advanced = base.items.map((i) => ({ short: i.short, itemId: i.id, trigger: 'start', ok: true }));
  writeFileSync(base.runDir + '/advanced-items.json', JSON.stringify(advanced));
  const assigned = [];
  let full = true;
  base.tools[ikey('runs_write')] = async (a) => {
    if (a.ops[0].op !== 'assign') return env({ results: a.ops.map(() => ({ ok: true })) });
    assigned.push(a.ops[0].itemId.slice(0, 8));
    if (full && a.ops[0].itemId === base.items[1].id) throw thrownElement('run_at_capacity', { active: 3, max: 3 });
    return env({ results: [{ ok: true, seatToken: 'REV-' + assigned.length }] });
  };
  const agents = [];
  const agent = async (a) => { agents.push(a.description); return { agentId: 'rv' }; };
  const first = await run(9, { runDir: base.runDir, scratch: base.scratch, tools: base.tools, agent });
  assert.deepEqual(first.waiting, [base.items[1].short], JSON.stringify(first));
  full = false;
  const second = await run(9, { runDir: base.runDir, scratch: base.scratch, tools: base.tools, agent });
  assert.deepEqual(second.dispatched.map((d) => d.item), [base.items[1].short], JSON.stringify(second));
  assert.deepEqual(assigned, [base.items[0].short, base.items[1].short, base.items[1].short], 'the first reviewer was assigned again on the rerun');
  assert.deepEqual(agents, ['reviewer:' + base.items[0].short, 'reviewer:' + base.items[1].short]);
});

// S21 (AC10): R7 writes the provenance lines.
test('S21 (AC10): R7 writes the provenance line to delegation-metadata under the coordinator token with expectedVersion 0 and reports present on version_conflict', async () => {
  const { scratch, runDir, items } = r7Setup();
  const [X, Y] = items;
  const writes = [];
  const result = resultFor(items, { [X.id]: true, [Y.id]: true });
  const tools = r7Tools({
    withNotes: new Set(items.map((i) => i.id)), advances: [],
    schemaKeys: { [X.id]: ['delegation-metadata'], [Y.id]: ['delegation-metadata'] },
    notesWrite: async (a) => {
      writes.push(a);
      if (a.ops[0].itemId === Y.id) throw thrownElement('version_conflict');
      return env({ results: [{ ok: true }] });
    },
  });
  const out = await run(7, { runDir, scratch, tools, subst: literal(scratch, result) });
  assert.deepEqual(out.written, { items: 1, present: 1, failed: 0, layer: 'none' }, JSON.stringify(out));
  const prov = JSON.parse(readFileSync(runDir + '/provenance.json', 'utf8'));
  assert.equal(writes.length, 2);
  for (const w of writes) {
    assert.equal(w.seatToken, 'COORD');
    assert.equal(w.ops[0].key, 'delegation-metadata');
    assert.equal(w.ops[0].expectedVersion, 0);
    assert.equal(w.ops[0].body, prov[w.ops[0].itemId]);
  }
  assert.ok(!JSON.stringify(out).includes('tokens='), 'a provenance line left the script');
});

test('S21 (AC10): the parent delegation-metadata keeps other runs lines and replaces the line of this run id', async () => {
  const NONMEMBER = 'cccccccc-3333-4333-8333-333333333333';
  const s = r7Setup({ scopeId: NONMEMBER });
  const runId = s.plan.args.runId;
  const writes = [];
  const bodies = { [NONMEMBER + ':delegation-metadata']: { body: 'layer-meters=coordinator:old run=' + runId + ' session-window=run\nlayer-meters=coordinator:keep run=r-other session-window=run', version: 3 } };
  const tools = r7Tools({ withNotes: new Set(s.items.map((i) => i.id)), advances: [], schemaKeys: { [NONMEMBER]: ['delegation-metadata'] }, writes, bodies });
  const out = await run(7, { runDir: s.runDir, scratch: s.scratch, tools, subst: { ...literal(s.scratch, resultFor(s.items, Object.fromEntries(s.items.map((i) => [i.id, true])))), 'const COORDINATOR_LABEL = null;': "const COORDINATOR_LABEL = 'coordinator:xyz';" } });
  assert.equal(out.provenance.layer, 'parent', JSON.stringify(out));
  assert.equal(out.written.layer, 'replaced', JSON.stringify(out));
  const w = writes.find((x) => x.ops[0].itemId === NONMEMBER);
  assert.ok(w, 'the parent was written');
  assert.equal(w.seatToken, undefined, 'the parent layer line is token-less');
  assert.equal(w.ops[0].expectedVersion, 3);
  const lines = w.ops[0].body.split('\n');
  assert.equal(lines.length, 2, w.ops[0].body);
  assert.ok(lines.some((l) => l.includes('coordinator:keep run=r-other')), 'another run line was dropped');
  assert.ok(!lines.some((l) => l.includes('coordinator:old')), 'the old line of this run stayed');
  assert.ok(lines.some((l) => l.split(' ').includes('run=' + runId) && l.startsWith('layer-meters=')), w.ops[0].body);
  // with no existing body the line is written as a new note
  const fresh = r7Setup({ scopeId: NONMEMBER });
  const writes2 = [];
  const t2 = r7Tools({ withNotes: new Set(fresh.items.map((i) => i.id)), advances: [], schemaKeys: { [NONMEMBER]: ['delegation-metadata'] }, writes: writes2 });
  const o2 = await run(7, { runDir: fresh.runDir, scratch: fresh.scratch, tools: t2, subst: { ...literal(fresh.scratch, resultFor(fresh.items, Object.fromEntries(fresh.items.map((i) => [i.id, true])))), 'const COORDINATOR_LABEL = null;': "const COORDINATOR_LABEL = 'coordinator:xyz';" } });
  assert.equal(o2.written.layer, 'written', JSON.stringify(o2));
  assert.equal(writes2.find((x) => x.ops[0].itemId === NONMEMBER).ops[0].expectedVersion, 0);
});

test('S21 (AC3, AC10): R7 reports paused when the notified update answers run_paused, and lists the decision ids of the result', async () => {
  const { scratch, runDir, items } = r7Setup();
  const result = resultFor(items, Object.fromEntries(items.map((i) => [i.id, true])));
  result.items[0].stages[0].decisionId = 'dec-0001';
  const tools = r7Tools({ withNotes: new Set(items.map((i) => i.id)), advances: [], runsWrite: async (a) => { if (a.ops[0].op === 'update') throw thrownElement('run_paused', { pauseReason: 'manual', pauseDecisionId: null }); return env({ results: a.ops.map(() => ({ ok: true })) }); } });
  const out = await run(7, { runDir, scratch, tools, subst: literal(scratch, result) });
  assert.equal(out.paused, true, JSON.stringify(out));
  assert.deepEqual(out.decisionIds, ['dec-0001']);
  const paused = JSON.parse(readFileSync(runDir + '/paused.json', 'utf8'));
  assert.equal(paused.code, 'run_paused');
  assert.ok(existsSync(runDir + '/verify.json'), 'post-run work went on after the pause');
});

// A1 fix cycle: the live write shape (a top-level partial_failure whose element error holds the code) reaches every branch.
test('A1 (B1): the kit elementCode and stop read the element error of a one-op write that answers partial_failure', async () => {
  const s = r11Setup();
  s.tools[ikey('runs_write')] = async () => { throw thrownElement('run_paused', { pauseReason: 'manual', pauseDecisionId: 'dec-9' }); };
  const out = await run(11, { runDir: s.runDir, scratch: s.scratch, tools: s.tools, sendMessage: s.sendMessage });
  assert.equal(out.paused, true, JSON.stringify(out));
  assert.equal(out.decisionId, 'dec-9');
  assert.equal(JSON.parse(readFileSync(s.runDir + '/paused.json', 'utf8')).detail.pauseDecisionId, 'dec-9');
  const other = r11Setup();
  other.tools[ikey('runs_write')] = async () => { throw thrownElement('forbidden'); };
  const o2 = await run(11, { runDir: other.runDir, scratch: other.scratch, tools: other.tools, sendMessage: other.sendMessage });
  assert.deepEqual([o2.failed, o2.step, o2.code], [true, 'close', 'forbidden'], JSON.stringify(o2));
});

test('A1 (B1): the R5 one-by-one assign after run_at_capacity holds back an element answered run_at_capacity', async () => {
  const s = boundSetup();
  const log = [];
  const tools = r5Tools({ log });
  let batches = 0;
  tools[ikey('runs_write')] = async (a) => {
    if (a.ops[0].op === 'assign') {
      batches += 1;
      if (a.atomic) throw new Error('run_at_capacity: full' + String.fromCharCode(10) + JSON.stringify({ ok: false, error: { code: 'run_at_capacity', kind: 'transient', detail: { active: 1, max: 1 } } }));
      if (batches > 2) throw thrownElement('run_at_capacity', { active: 1, max: 1 });
    }
    return env({ results: a.ops.map(() => ({ ok: true, seatToken: 'SEAT-' + batches })) });
  };
  const out = await run(5, { runDir: s.runDir, scratch: s.scratch, tools, agent: async () => ({ agentId: 'a' }) });
  assert.equal(out.failed, undefined, JSON.stringify(out));
  assert.equal(out.dispatched.length, 1, 'the first seat fits, the second is held back: ' + JSON.stringify(out));
});

// What the live MCP tool global throws for a multi-element advance with failures: the tail cuts data.results to the failing entries, each with its index (McpOperationAdapter.tail).
const thrownTail = (entries, summary) => new Error('partial_failure: ' + summary.failed + ' of ' + (summary.ok + summary.failed) + ' elements failed.' + String.fromCharCode(10) + JSON.stringify({
  ok: false, error: { code: 'partial_failure', kind: 'permanent', message: summary.failed + ' of ' + (summary.ok + summary.failed) + ' elements failed.' },
  data: { results: entries.map(([index, code]) => ({ index, ok: false, error: { code, detail: null } })), summary }, meta: { reqId: 'r' },
}));

test('A2 (B4): R8 maps a partial_failure tail by index: only the later op failing marks that op failed and the earlier one passed', async () => {
  const s = await after7();
  const { runDir, scratch, X, Y, tools } = s;
  tools[ikey('advance')] = async () => { throw thrownTail([[1, 'dependency_unmet']], { ok: 1, failed: 1 }); };
  const out = await run(8, { runDir, scratch, tools });
  assert.deepEqual(out.advanced.map((a) => [a.short, a.ok, a.code]), [[X.short, true, undefined], [Y.short, false, 'dependency_unmet']], JSON.stringify(out));
  assert.deepEqual(JSON.parse(readFileSync(runDir + '/advanced-items.json', 'utf8')).map((a) => [a.short, a.trigger, a.ok]), [[X.short, 'start', true], [Y.short, 'complete', false]]);
  assert.equal(out.followUpsComplete, false, 'a tail carries no passed element, so the follow-ups are not complete');
  assert.deepEqual(JSON.parse(readFileSync(runDir + '/advance.json', 'utf8')).results.map((e) => e.index), [1], 'advance.json is the tail as served');
  assert.equal(JSON.parse(readFileSync(runDir + '/state.json', 'utf8')).phase, 'post-run');
});

test('A2 (B4): R8 maps a tail whose only entry is the first op: the later op passed', async () => {
  const s = await after7();
  const { runDir, scratch, X, Y, tools } = s;
  tools[ikey('advance')] = async () => { throw thrownTail([[0, 'gate_blocked']], { ok: 1, failed: 1 }); };
  const out = await run(8, { runDir, scratch, tools });
  assert.deepEqual(out.advanced.map((a) => [a.short, a.ok, a.code]), [[X.short, false, 'gate_blocked'], [Y.short, true, undefined]], JSON.stringify(out));
});

test('A2 (B4): R8 reads a no-entry op back when the cap cut failures (summary.failed above the entries listed)', async () => {
  const s = await after7();
  const { runDir, scratch, X, Y, tools } = s;
  const schemaRead = tools[ikey('items_read')];
  const roles = { [Y.id]: 'work' };
  tools[ikey('items_read')] = async (a) => (a.select === 'get' ? env({ id: a.itemId, role: roles[a.itemId] }) : schemaRead(a));
  tools[ikey('advance')] = async () => { throw thrownTail([[0, 'gate_blocked']], { ok: 0, failed: 2 }); };
  const out = await run(8, { runDir, scratch, tools });
  assert.deepEqual(out.advanced.map((a) => [a.short, a.ok, a.code]), [[X.short, false, 'gate_blocked'], [Y.short, false, 'failed']], 'Y is still in work: its failure was cut off the tail: ' + JSON.stringify(out));
  const passed = await after7();
  const read = passed.tools[ikey('items_read')];
  passed.tools[ikey('items_read')] = async (a) => (a.select === 'get' ? env({ id: a.itemId, role: 'terminal' }) : read(a));
  passed.tools[ikey('advance')] = async () => { throw thrownTail([[0, 'gate_blocked']], { ok: 0, failed: 2 }); };
  const out2 = await run(8, { runDir: passed.runDir, scratch: passed.scratch, tools: passed.tools });
  assert.deepEqual(out2.advanced.map((a) => [a.short, a.ok]), [[passed.X.short, false], [passed.Y.short, true]], JSON.stringify(out2));
});

test('A2 (B4): a fully applied advance still reads the answers by position and reports the follow-ups complete', async () => {
  const s = await after7();
  const out = await run(8, { runDir: s.runDir, scratch: s.scratch, tools: s.tools });
  assert.deepEqual(out.advanced.map((a) => a.ok), [true, true]);
  assert.equal(out.followUpsComplete, true);
});

// The kit's failedElement, elementCode and stop on a write of several ops: the tail keeps only the failing entries, so the first failing entry is not the first op.
const KIT_PROBE = 'async function r99() {\n  const a = await call(\'runs_write\', { ops: [{ op: \'a\' }, { op: \'b\' }, { op: \'c\' }] });\n  return { code: elementCode(a), first: failedElement(a) && failedElement(a).code, stopped: await stop(a) };\n}\nawait r99()\n';

test('A2 (B4): elementCode, failedElement and stop read the first failing entry of a multi-op tail whatever its index', async () => {
  const s = boundSetup();
  const probe = async (entries, summary) => {
    const tools = { [ikey('runs_write')]: async () => { throw thrownTail(entries, summary); } };
    return await run(99, { runDir: s.runDir, scratch: s.scratch, tools, body: KIT_PROBE });
  };
  const late = await probe([[2, 'forbidden']], { ok: 2, failed: 1 });
  assert.deepEqual(late, { code: 'forbidden', first: 'forbidden', stopped: { failed: true, code: 'forbidden', fix: undefined } }, JSON.stringify(late));
  const two = await probe([[1, 'version_conflict'], [2, 'forbidden']], { ok: 1, failed: 2 });
  assert.equal(two.code, 'version_conflict', 'the first failing entry, not the last and not a position: ' + JSON.stringify(two));
  const paused = await probe([[1, 'run_paused']], { ok: 2, failed: 1 });
  assert.equal(paused.stopped.paused, true, JSON.stringify(paused));
  assert.equal(JSON.parse(readFileSync(s.runDir + '/paused.json', 'utf8')).code, 'run_paused');
});

test('A1 (O2): R8 holds an item with no Live-owed line whose State matrix has a live-owed cell, and reads entries across line breaks', async () => {
  const matrix = await after7({ bodies: (X) => ({ [X.id + ':task-scope']: { body: 'State matrix:\n| fresh | terminal | live-owed: run the hook |', version: 1 } }) });
  const out = await run(8, { runDir: matrix.runDir, scratch: matrix.scratch, tools: matrix.tools });
  assert.deepEqual(out, { needs: 'hold', items: [matrix.X.short] }, JSON.stringify(out));
  assert.equal(matrix.advances.length, 0);
  const na = await after7({ bodies: (X) => ({ [X.id + ':task-scope']: { body: 'State matrix: n/a - docs only', version: 1 } }) });
  const o2 = await run(8, { runDir: na.runDir, scratch: na.scratch, tools: na.tools });
  assert.equal(o2.needs, undefined, JSON.stringify(o2));
  const lines = await after7({ bodies: (X) => ({ [X.id + ':session-tracking']: { body: 'Outcome: x\n**Live-owed**: terminal check run 2026-11-19: pass\n  desktop check of the pane', version: 1 } }) });
  const o3 = await run(8, { runDir: lines.runDir, scratch: lines.scratch, tools: lines.tools });
  assert.deepEqual(o3, { needs: 'hold', items: [lines.X.short] }, JSON.stringify(o3));
});

test('A1 (O3): the session-tracking pointer skip needs a seat-less stage (implicitOwner); a seat-aware stage gets the pointer', async () => {
  const s = r2Setup();
  const plan = JSON.parse(readFileSync(s.runDir + '/plan.json', 'utf8'));
  plan.meta.scopeId = plan.args.items[0].id;
  for (const st of plan.args.items[0].stages) st.implicitOwner = false;
  writeFileSync(s.runDir + '/plan.json', JSON.stringify(plan));
  s.declared.value = ['session-tracking'];
  const out = await run(2, { runDir: s.runDir, scratch: s.scratch, tools: s.tools });
  assert.equal(out.pointer, 'session-tracking', JSON.stringify(out));
  assert.equal(s.calls.notes.length, 1);
});

test('A1 (O4): R11 with REASON cancelled closes without returning the declared post-run steps', async () => {
  const postRun = [{ step: 'live-acceptance', when: 'post-run', rule: 'live-checks' }];
  const s = r11Setup({ postRun });
  const out = await run(11, { runDir: s.runDir, scratch: s.scratch, tools: s.tools, sendMessage: s.sendMessage, subst: { 'const REASON = null;': "const REASON = 'cancelled';" } });
  assert.equal(out.closed, true, JSON.stringify(out));
  assert.equal(out.reason, 'cancelled');
  assert.equal(s.calls.filter((c) => c.ops[0].op === 'close').length, 1);
});

test('A1 (B2): method-b.md has a loop step that records launched with run-ops update', () => {
  const mb = readFileSync(join(PLUGIN_ROOT, 'skills', 'run-wave', 'references', 'method-b.md'), 'utf8');
  assert.ok(/^0\. \*\*Record `launched`/m.test(mb), 'the Method B loop has no launched step');
  assert.ok(mb.includes('--phase update --run-phase launched'));
});
