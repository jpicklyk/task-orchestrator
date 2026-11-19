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
  const r = spawnSync(command, { shell: true, encoding: 'utf8', cwd, env: { ...process.env, HOME: home, USERPROFILE: home } });
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
function script(n, { runDir, scratch, rootId, subst = {} }) {
  let src = PRELUDE + '\n' + KIT + '\n' + recipe(n);
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

async function run(n, { runDir, scratch, rootId = '00000000-0000-4000-8000-000000000000', cwd, tools = {}, agent, workflow, subst = {}, shellFn, onSource }) {
  const src = script(n, { runDir, scratch, rootId, subst });
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
    SendMessage: async () => ({}),
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
  tools[key('config')] = async () => env({ results: [{ rules: [{ key: 'commit-discipline', rulesVersion: '1' }] }] });
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
    [key('config')]: async () => env({ results: [{ rules: [] }] }),
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

  const key = (t) => 'mcp__mcp-task-orchestrator__' + t;
  let n = 0;
  const writes = [];
  const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10';
  const runsWrite = async (a) => {
    writes.push(a);
    return env({
      results: a.ops.map((op) => ({ ok: true, runId: SERVER_RUN, coordinatorToken: 'COORD-SECRET-TOKEN', seatToken: 'SEAT-SECRET-' + (n += 1) })),
    });
  };
  const tools = { [key('runs_write')]: runsWrite };

  // R2: open + assign
  const r2 = await run(2, { runDir, scratch, tools });
  assert.ok(r2.serverRunId === SERVER_RUN && r2.failed === 0, JSON.stringify(r2));
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
  const r7 = recipe(7);
  assert.ok(r7.indexOf('ends.code !== 0') !== -1 && r7.indexOf('ends.code !== 0') < r7.indexOf('ends.json.ops'), 'R7 reads ends.json before checking ends.code');
});

// ---- Live-runtime defects (live check 276f353a): each test fails against the pre-change code-mode.md ----

const ikey = (t) => 'mcp__mcp-task-orchestrator__' + t;

test('S1 (AC3): R1 to R7 each pass node --check, end with their own call and use no eval or Function; a top-level return fails the check', () => {
  const { dir, scratch } = mk();
  for (let n = 1; n <= 7; n += 1) {
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
    [ikey('config')]: async () => env({ results: [{ rules: [] }] }),
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
function r7Tools({ withNotes, schemaKeys = {}, advances }) {
  return {
    [ikey('runs_read')]: async (a) => env(a.select === 'get' ? { openedAt: '2026-09-28T12:00:00Z' } : { seats: [] }),
    [ikey('notes_read')]: async (a) => env({ items: withNotes.has(a.itemId) ? a.keys.map((key) => ({ key })) : [] }),
    [ikey('items_read')]: async (a) => env({ notes: (schemaKeys[a.itemId] || []).map((key) => ({ key })) }),
    [ikey('advance')]: async (a) => { advances.push(a); return env({ results: a.ops.map((o) => ({ ok: true, itemId: o.itemId })) }); },
    [ikey('runs_write')]: async (a) => env({ results: a.ops.map(() => ({ ok: true })) }),
  };
}
const literal = (scratch, result) => ({ 'const RESULT_LITERAL = null;': 'const RESULT_LITERAL = ' + JSON.stringify(result) + ';' });

test('S6 (AC7): R7 leaves out an item the notes audit fails and an item verify fails, and advances the rest', async () => {
  // audit failure: X has a done planner stage and no observed notes; Y is complete
  {
    const { scratch, runDir, items } = r7Setup();
    const [X, Y] = items;
    const advances = [];
    const result = resultFor(items, { [X.id]: true, [Y.id]: true });
    const out = await run(7, { runDir, scratch, tools: r7Tools({ withNotes: new Set([Y.id]), advances }), subst: literal(scratch, result) });
    assert.deepEqual(out.failing, [X.short], JSON.stringify(out));
    assert.equal(advances.length, 1);
    assert.deepEqual(advances[0].ops.map((o) => o.itemId), [Y.id]);
  }
  // verify failure (boolean ok false, status done): Y's sweep disposed 1 of 2; both have their notes
  {
    const { scratch, runDir, items } = r7Setup();
    const [X, Y] = items;
    const advances = [];
    const result = resultFor(items, { [X.id]: true, [Y.id]: false });
    const out = await run(7, { runDir, scratch, tools: r7Tools({ withNotes: new Set([X.id, Y.id]), advances }), subst: literal(scratch, result) });
    assert.deepEqual(out.failing, [Y.short], JSON.stringify(out));
    assert.deepEqual(advances[0].ops.map((o) => o.itemId), [X.id]);
  }
});

test('S7 (AC7): R7 sends no advance call when every item failed verify or the audit', async () => {
  const { scratch, runDir, items } = r7Setup();
  const [X, Y] = items;
  const advances = [];
  const result = resultFor(items, { [X.id]: true, [Y.id]: false });
  // X fails the audit (no notes), Y fails verify
  const out = await run(7, { runDir, scratch, tools: r7Tools({ withNotes: new Set([Y.id]), advances }), subst: literal(scratch, result) });
  assert.deepEqual([...out.failing].sort(), [X.short, Y.short].sort(), JSON.stringify(out));
  assert.equal(out.advance, 'none');
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
  const tools = r7Tools({ withNotes: new Set(items.map((i) => i.id)), schemaKeys: { [X.id]: ['delegation-metadata'], [Y.id]: ['session-tracking'], [NONMEMBER]: ['session-tracking'] }, advances });
  const out = await run(7, { runDir, scratch, tools, subst: literal(scratch, result) });
  assert.deepEqual(out.provenance.items, [{ short: X.short, target: 'delegation-metadata' }, { short: Y.short, target: 'report' }], JSON.stringify(out));
  assert.equal(out.provenance.layer, 'report');
  assert.ok(existsSync(runDir + '/dispatch-meta.json'));
  const prov = JSON.parse(readFileSync(runDir + '/provenance.json', 'utf8'));
  for (const item of items) assert.ok(prov[item.id], 'provenance.json has a line for ' + item.short);
  assert.ok(!JSON.stringify(out).includes('tokens='), 'a provenance line left the script');
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
  for (const n of [1, 2, 3, 4, 5, 6, 7]) {
    const src = recipe(n) + '\n' + (n === 1 ? KIT : '');
    assert.ok(!/await file\([^)]*\)\)/.test(src), 'R' + n + ' awaits a file handle as text');
  }
  assert.ok(!/JSON\.parse\(await file\([^)]*\)\)/.test(KIT));
  assert.ok(KIT.includes('.nothrow()'), 'the kit sh does not use nothrow');
});
