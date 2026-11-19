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

// Runs one recipe the way the REPL would: prelude + kit + recipe as one async function body.
async function run(n, { runDir, scratch, rootId = '00000000-0000-4000-8000-000000000000', cwd, tools = {}, agent, workflow, subst = {} }) {
  let src = PRELUDE + '\n' + KIT + '\n' + recipe(n);
  src = src.split('<scratchpad>/run-wave/<runId>').join(runDir || scratch + '/run-wave/r-test');
  src = src.split('<scratchpad>').join(scratch).split('<helper>').join(HELPER).split('<rootId>').join(rootId);
  for (const [from, to] of Object.entries(subst)) {
    assert.ok(src.includes(from), 'substitution target missing: ' + from);
    src = src.split(from).join(to);
  }
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
    file: async (p) => readFileSync(p, 'utf8'),
    $: async (command) => {
      const r = spawnSync(command, { shell: true, encoding: 'utf8', cwd });
      return { stdout: r.stdout, stderr: r.stderr, exitCode: r.status };
    },
    setTimeout,
    log: () => {},
  };
  const names = Object.keys(g);
  return await new AsyncFunction(...names, src)(...names.map((k) => g[k]));
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
