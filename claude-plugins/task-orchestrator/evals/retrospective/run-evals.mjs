#!/usr/bin/env node
// run-evals.mjs -- scenario evals for the automatic retrospective. Dev tooling: see README.md (safety, cost).
//
//   node run-evals.mjs [--scenario <id>]... [--image <tag>] [--check] [--preflight] [--seed-only] [--record] [--redproof]
//
// --check      validate the scenario files only (no docker, no model); exit 0, or 2 on an unknown check id or a bad file
// --preflight  build the child environment and prove it cannot reach gh; exit 0, or 3 when it can (or node, git or
//              bash no longer resolve); prints the verdict
// --seed-only  per scenario: start a throwaway container, seed it, run the deterministic hook stages, grade them,
//              tear down. Zero model tokens.
// --record     append one row per graded stage to results.md
// --redproof   also run the stages marked redProof: they run against a COPY of the plugin whose stale rule is cut
//
// Without --seed-only every live stage runs `claude -p` ONCE (never retried): it costs tokens.
// Exit codes: 0 all graded checks passed, 1 a check failed or a stage errored, 2 bad arguments or scenario, 3 preflight
// refused. Nothing here reaches the real gh or GitHub: the child environment carries no gh and no token, and
// bug-report runs the fake gh (TASK_ORCHESTRATOR_GH_SHIM).

import { readFileSync, writeFileSync, readdirSync, mkdirSync, mkdtempSync, rmSync, existsSync, cpSync, appendFileSync, statSync } from 'node:fs';
import { tmpdir, homedir } from 'node:os';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:net';
import { randomBytes, randomUUID } from 'node:crypto';
import { dirname, join, resolve, delimiter, basename } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { CHECKS, CHECK_IDS, LIVE_COMMON } from './checks.mjs';

// The environment as the caller gave it. The hook test helpers imported later pin CLAUDE_CONFIG_DIR to an empty
// directory (they must not see a real registration); the child sessions need the real one for their login.
const PARENT_ENV = { ...process.env };

const HERE = dirname(fileURLToPath(import.meta.url));
const PLUGIN = resolve(HERE, '..', '..');
const REPO = resolve(PLUGIN, '..', '..');
const FLOOR = join(REPO, 'deploy', 'global-config', '.taskorchestrator');
const SCENARIOS = join(HERE, 'scenarios');
const RESULTS = join(HERE, 'results.md');
const FAKE_GH = join(HERE, 'fake-gh.mjs');
const DEFAULT_IMAGE = 'task-orchestrator:dev';
const LIVE_TIMEOUT_MS = 30 * 60 * 1000;
const DAY = 24 * 60 * 60 * 1000;

const { callOp } = await import(pathToFileURL(join(PLUGIN, 'hooks', 'lib', 'api-client.mjs')).href);
const { defectFingerprint, defectComponent } = await import(pathToFileURL(join(PLUGIN, 'scripts', 'lib', 'retro-metrics.mjs')).href);
const { buildDispatch } = await import(pathToFileURL(join(PLUGIN, 'hooks', 'retro-lib.mjs')).href);

// ---- arguments ----------------------------------------------------------------------------------------------

function parseArgs(argv) {
  const a = { scenarios: [], image: DEFAULT_IMAGE, check: false, preflight: false, seedOnly: false, record: false, redproof: false };
  for (let i = 0; i < argv.length; i += 1) {
    const f = argv[i];
    if (f === '--check') a.check = true;
    else if (f === '--preflight') a.preflight = true;
    else if (f === '--seed-only') a.seedOnly = true;
    else if (f === '--record') a.record = true;
    else if (f === '--redproof') a.redproof = true;
    else if (f === '--scenario' || f === '--image') {
      const v = argv[i + 1];
      if (v === undefined || v.startsWith('--')) throw new Error(`${f} needs a value`);
      i += 1;
      if (f === '--scenario') a.scenarios.push(v);
      else a.image = v;
    } else throw new Error(`unknown argument ${f}`);
  }
  return a;
}

// ---- scenario files -------------------------------------------------------------------------------------------

function loadScenarios(only) {
  const out = [];
  const errors = [];
  for (const name of readdirSync(SCENARIOS).filter((n) => n.endsWith('.json')).sort()) {
    let sc;
    try {
      sc = JSON.parse(readFileSync(join(SCENARIOS, name), 'utf8'));
    } catch (e) {
      errors.push(`${name}: not JSON (${e.message})`);
      continue;
    }
    errors.push(...validateScenario(sc, name));
    out.push(sc);
  }
  const picked = only.length === 0 ? out : out.filter((s) => only.some((id) => s.id === id || s.id.startsWith(id)));
  if (only.length > 0 && picked.length === 0) errors.push(`no scenario matches ${only.join(', ')}`);
  return { scenarios: picked, errors };
}

function validateScenario(sc, name) {
  const errs = [];
  const bad = (m) => errs.push(`${name}: ${m}`);
  if (!sc || typeof sc.id !== 'string') return [`${name}: no id`];
  if (!Array.isArray(sc.features) || sc.features.length === 0) bad('features must be a non-empty list');
  const keys = new Set();
  for (const f of sc.features || []) {
    if (typeof f.key !== 'string' || keys.has(f.key)) bad(`feature key ${f.key} missing or repeated`);
    keys.add(f.key);
    if (!Array.isArray(f.items) || f.items.length === 0) bad(`feature ${f.key} has no items`);
    else if (!(Number.isInteger(f.runs) && f.runs >= 1 && f.runs <= f.items.length)) bad(`feature ${f.key}: runs must be 1..items`);
  }
  if (!Array.isArray(sc.stages) || sc.stages.length === 0) bad('stages must be a non-empty list');
  for (const st of sc.stages || []) {
    if (typeof st.id !== 'string') bad('a stage has no id');
    if (!['hook', 'live', 'age-state'].includes(st.kind)) bad(`stage ${st.id}: unknown kind ${st.kind}`);
    for (const c of st.checks || []) if (!CHECK_IDS.includes(c)) bad(`stage ${st.id}: unknown check id ${c}`);
    for (const k of [st.feature, ...(st.features || []), ...(st.other || []), ...(st.seedFeatures || [])]) {
      if (k !== undefined && !keys.has(k)) bad(`stage ${st.id}: unknown feature ${k}`);
    }
  }
  return errs;
}

// ---- child environment and preflight --------------------------------------------------------------------------

const GH_NAMES = ['gh', 'gh.exe', 'gh.cmd', 'gh.bat'];

function pathKey(env) {
  return Object.keys(env).find((k) => k.toLowerCase() === 'path') || 'PATH';
}

function resolveOn(name, pathValue) {
  const exts = process.platform === 'win32' ? ['', '.exe', '.cmd', '.bat', '.com'] : [''];
  for (const dir of String(pathValue || '').split(delimiter).filter(Boolean)) {
    for (const ext of exts) {
      const p = join(dir, name + ext);
      if (existsSync(p) && statSync(p).isFile()) return p;
    }
  }
  return null;
}

/** The child environment (never carries gh) and where it lives: sandbox = { dir, home, ghDir, tmp, ghLog }. */
export function buildChildEnv(sandbox, parentEnv = PARENT_ENV) {
  const env = { ...parentEnv };
  const key = pathKey(env);
  const entries = String(env[key] || '').split(delimiter).filter(Boolean);
  const kept = entries.filter((dir) => !GH_NAMES.some((n) => existsSync(join(dir, n))));
  for (const k of Object.keys(env)) if (k.toLowerCase() === 'path') delete env[k];
  env[key] = kept.join(delimiter);
  for (const k of ['GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'CLAUDE_CODE_PLUGIN_DIRS', 'TASK_ORCHESTRATOR_BUG_REPORTS']) delete env[k];
  Object.assign(env, {
    GH_CONFIG_DIR: join(sandbox.dir, 'gh-config'),
    TASK_ORCHESTRATOR_HOME: sandbox.home,
    TEMP: sandbox.tmp,
    TMP: sandbox.tmp,
    TMPDIR: sandbox.tmp,
    TASK_ORCHESTRATOR_GH_SHIM: FAKE_GH,
    FAKE_GH_DIR: sandbox.ghLog,
  });
  return { env, droppedPathEntries: entries.length - kept.length };
}

function preflight(sandbox) {
  const { env, droppedPathEntries } = buildChildEnv(sandbox);
  const p = env[pathKey(env)];
  const problems = [];
  const gh = resolveOn('gh', p);
  if (gh) problems.push(`gh still resolves in the child environment: ${gh}`);
  for (const k of ['GH_TOKEN', 'GITHUB_TOKEN']) if (env[k]) problems.push(`${k} is set`);
  for (const tool of ['node', 'git', 'bash']) if (!resolveOn(tool, p)) problems.push(`${tool} no longer resolves once the gh directories are dropped (a directory holding gh also holds ${tool}: run from a PATH that keeps them apart)`);
  if (env.TASK_ORCHESTRATOR_GH_SHIM !== FAKE_GH) problems.push('the gh shim is not fake-gh.mjs');
  return { ok: problems.length === 0, problems, droppedPathEntries };
}

// ---- sandbox, containers and teardown --------------------------------------------------------------------------

const owned = { dirs: [], containers: [], volumes: [], children: new Set() };

function docker(args, opts = {}) {
  return spawnSync('docker', args, { encoding: 'utf8', timeout: 120000, ...opts });
}

function teardown() {
  for (const c of owned.children) {
    try {
      c.kill();
    } catch {
      // already gone
    }
  }
  owned.children.clear();
  for (const name of owned.containers.splice(0)) docker(['rm', '-f', name]);
  for (const vol of owned.volumes.splice(0)) docker(['volume', 'rm', '-f', vol]);
  for (const d of owned.dirs.splice(0)) rmSync(d, { recursive: true, force: true });
}

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    teardown();
    process.exit(130);
  });
}
process.on('exit', teardown);

function makeSandbox() {
  const dir = mkdtempSync(join(tmpdir(), 'to-eval-box-'));
  owned.dirs.push(dir);
  const box = { dir, home: join(dir, 'home'), tmp: join(dir, 'tmp'), ghLog: join(dir, 'ghlog'), work: join(dir, 'work') };
  for (const d of [box.home, box.tmp, box.ghLog, box.work, join(dir, 'gh-config')]) mkdirSync(d, { recursive: true });
  return box;
}

function freePort() {
  return new Promise((res, rej) => {
    const s = createServer();
    s.on('error', rej);
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => res(port));
    });
  });
}

async function startServer(image) {
  const id = `to-eval-${randomBytes(4).toString('hex')}`;
  const port = await freePort();
  owned.containers.push(id);
  owned.volumes.push(id);
  const run = docker([
    'run', '-d', '--name', id, '-v', `${id}:/app/data`, '-v', `${FLOOR}:/project/.taskorchestrator:ro`,
    '-e', 'MCP_TRANSPORT=http', '-e', 'API_ENABLED=true', '-e', 'API_AUTH_MODE=none', '-e', 'API_ALLOW_UNAUTHENTICATED=true',
    '-e', 'AGENT_CONFIG_DIR=/project', '-p', `127.0.0.1:${port}:3001`, image,
  ]);
  if (run.status !== 0) throw new Error(`docker run failed: ${(run.stderr || '').trim()}`);
  const base = `http://127.0.0.1:${port}`;
  for (let i = 0; i < 90; i += 1) {
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), 3000); // a ref'd timer: an unanswered connect must not end the process
    try {
      const r = await fetch(`${base}/.well-known/mcp-task-orchestrator.json`, { signal: ac.signal });
      if (r.status === 200) return { id, base };
    } catch {
      // not up yet
    } finally {
      clearTimeout(timer);
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  const logs = docker(['logs', '--tail', '15', id]);
  throw new Error(`the server did not answer in 90 s (image ${image}). Last log lines:\n${(logs.stdout || '') + (logs.stderr || '')}`);
}

// ---- REST helpers ------------------------------------------------------------------------------------------------

function makeApi(base) {
  const api = {
    /** One write op; throws on an error element. Returns the element. */
    async write(tool, ops, extra = {}) {
      const res = await callOp(tool, { ops, ...extra }, { baseUrl: base, timeoutMs: 60000 });
      if (!res) throw new Error(`${tool}: no answer`);
      const el = res.results && res.results[0];
      if (!el || el.ok !== true) {
        const e = (el && el.error) || res.error || {};
        throw new Error(`${tool} ${ops[0].op || ''} failed: ${e.code || 'unknown'} ${e.message || ''} ${JSON.stringify(e.detail || '').slice(0, 300)}`);
      }
      return el;
    },
    async read(tool, body) {
      const res = await callOp(tool, body, { baseUrl: base, timeoutMs: 60000 });
      if (!res || res.ok !== true) throw new Error(`${tool} ${body.select || ''} failed: ${res && res.error ? res.error.code : 'no answer'}`);
      return res.data;
    },
  };
  return api;
}

const noteText = (spec) => {
  if (spec && typeof spec.friction === 'string') return `Outcome: success, merged\nCommits: abc1234\nVerified: node --test green at abc1234\nFriction: ${spec.friction}\nDeviations: none\n`;
  if (spec && spec.note === 'defect') return `Outcome: success, merged\nCommits: abc1234\nVerified: node --test green at abc1234\nFriction: __DEFECT__\nDeviations: none\n`;
  return 'Outcome: success, merged\nCommits: abc1234\nVerified: node --test green at abc1234\nFriction: None.\nDeviations: none\n';
};

// ---- seeding -------------------------------------------------------------------------------------------------------

function projectConfigYaml(rootId, name, maxLength) {
  return [
    'project:', `  rootId: "${rootId}"`, `  name: "${name}"`, '',
    'work_item_schemas:', '  feature:', '    lifecycle: auto', '    notes: []', '  feature-task:', '    lifecycle: auto', '    notes:',
    '      - key: task-scope', '        role: queue', '        required: false', '        description: "What to build."', `        maxLength: ${maxLength}`,
    '      - key: session-tracking', '        role: work', '        required: false', '        description: "Closure record."', '',
    // the live stages run in a directory holding this same file: the plugin's config-sync hook pushes it, and a
    // different file would replace the schemas seeded here (retrospective mode off: the headless session never re-dispatches)
    'extensions:', '  claude-code:', '    retrospective:', '      mode: off', '      boundary: scope', '      github_feedback:', '        enabled: false', '',
  ].join('\n');
}

async function seedFeature(api, st, f) {
  const created = [];
  const feature = await api.write('items_write', [{ op: 'create', type: 'feature', title: `Feature ${f.key}`, parentId: st.rootId }]);
  const featureId = feature.id;
  const itemIds = [];
  const fp = st.seeded.defectMessage ? `defect: ${st.defect.tool}, ${st.defect.op}, ${st.defect.code}, ${st.defect.message}` : '';
  for (let n = 0; n < f.items.length; n += 1) {
    const it = await api.write('items_write', [{ op: 'create', type: 'feature-task', title: `${f.key} task ${n + 1}`, parentId: featureId }]);
    itemIds.push(it.id);
    const text = noteText(f.items[n]).replace('__DEFECT__', fp);
    await api.write('notes_write', [{ op: 'upsert', itemId: it.id, key: 'session-tracking', role: 'work', body: text }]);
  }
  // pending items exist before any run closes, or the feature would complete under them
  const pending = [];
  for (let p = 0; p < (f.pendingItems || 0); p += 1) {
    const it = await api.write('items_write', [{ op: 'create', type: 'feature-task', title: `${f.key} pending ${p + 1}`, parentId: featureId }]);
    pending.push(it.id);
  }
  const runs = [];
  for (let r = 0; r < f.runs; r += 1) {
    const members = itemIds.filter((_, n) => n % f.runs === r);
    const open = await api.write('runs_write', [{ op: 'open', rootId: st.rootId, scopeId: featureId, items: members, label: `wave-${f.key}-${r + 1}` }]);
    const coordinator = open.coordinatorToken;
    for (const id of members) {
      const seat = await api.write('runs_write', [{ op: 'assign', runId: open.runId, itemId: id, seat: 'implementer' }], { seatToken: coordinator });
      await api.write('advance', [{ op: 'transition', itemId: id, trigger: 'start' }], { seatToken: seat.seatToken });
      await api.write('runs_write', [{ op: 'end_seat', outcome: 'done', summary: 'eval seed' }], { seatToken: seat.seatToken });
      await api.write('advance', [{ op: 'transition', itemId: id, trigger: 'complete' }]);
    }
    await api.write('runs_write', [{ op: 'close', runId: open.runId, reason: 'completed' }], { seatToken: coordinator });
    runs.push(open.runId);
  }
  let openRunId = null;
  for (const id of pending) {
    await api.write('advance', [{ op: 'transition', itemId: id, trigger: 'start' }]);
    const open = await api.write('runs_write', [{ op: 'open', rootId: st.rootId, scopeId: featureId, items: [id], label: `wave-${f.key}-open` }]);
    openRunId = open.runId;
  }
  created.push(featureId);
  return { key: f.key, featureId, itemIds, runs, openRunId };
}

async function seedScenario(api, sc, sandbox) {
  const rand = randomBytes(3).toString('hex');
  const projectName = `eval-${sc.id}-${rand}`;
  const root = await api.write('items_write', [{ op: 'create', type: 'project', title: projectName }]);
  const rootId = root.id;
  const configYaml = projectConfigYaml(rootId, projectName, sc.taskScopeMaxLength || 12000);
  const cfg = await api.write('config', [{ op: 'push', rootId, configYaml }]);
  void cfg;
  const st = { rootId, root8: rootId.slice(0, 8), projectName, configYaml, seeded: {}, features: {}, defect: sc.defect || null, bugReports: Boolean(sc.bugReports), memory: {} };
  if (sc.defect) {
    st.seeded.defectMessage = sc.defect.message;
    st.seeded.fingerprint = defectFingerprint({ component: defectComponent(sc.defect.tool), tool: sc.defect.tool, op: sc.defect.op, code: sc.defect.code, message: sc.defect.message });
  }
  st.seeded.projectName = projectName;
  if (sc.projectTrend) {
    const container = await api.write('items_write', [{ op: 'create', type: 'container', title: 'Retrospective Trends', parentId: rootId, tags: ['container'] }]);
    const trend = await api.write('items_write', [{
      op: 'create', type: 'retrospective-trend', title: sc.projectTrend.title, parentId: container.id, priority: 'low',
      tags: ['retrospective-trend', sc.projectTrend.dimension, `project-${st.root8}`], summary: 'Sessions: 1',
    }]);
    await api.write('notes_write', [{ op: 'upsert', itemId: trend.id, key: 'evidence-2026-11-01-seed', role: 'work', body: sc.projectTrend.evidence }]);
    st.seeded.trendId = trend.id;
  }
  if (sc.emptyGlobalProposalsRoot) {
    const g = await api.write('items_write', [{ op: 'create', type: 'container', title: 'Improvement Proposals', tags: ['container'] }]);
    st.seeded.globalProposalsRootId = g.id;
  }
  for (const f of sc.features) if (!f.late) st.features[f.key] = await seedFeature(api, st, f);
  return st;
}

async function gradeSeed(api, st, sc) {
  const problems = [];
  for (const f of sc.features.filter((x) => !x.late)) {
    const s = st.features[f.key];
    const item = await api.read('items_read', { select: 'get', itemId: s.featureId });
    const wantTerminal = !(f.pendingItems > 0);
    if ((item.role === 'terminal') !== wantTerminal) problems.push(`feature ${f.key} role ${item.role}`);
    for (const runId of s.runs) {
      const run = await api.read('runs_read', { select: 'get', runId });
      if (run.status !== 'closed') problems.push(`run ${runId.slice(0, 8)} is ${run.status}`);
    }
    if (s.openRunId) {
      const run = await api.read('runs_read', { select: 'get', runId: s.openRunId });
      if (run.status !== 'open') problems.push(`open run of ${f.key} is ${run.status}`);
    }
  }
  return problems;
}

// ---- stages ---------------------------------------------------------------------------------------------------------

function stageRunIds(st, stage) {
  if (stage.kind === 'hook') return st.features[stage.feature].runs;
  const out = [];
  for (const k of stage.features || []) {
    const runs = st.features[k].runs;
    out.push(...(Array.isArray(stage.runIndexes) ? stage.runIndexes.map((i) => runs[i]) : runs));
  }
  return out;
}

function runHookStage(st, stage, base) {
  return (async () => {
    const fake = await import(pathToFileURL(join(PLUGIN, 'hooks', 'tests', 'retro-fake-runs.mjs')).href);
    const sbx = fake.makeSandbox({ config: fake.retroConfig({ rootId: st.rootId, mode: 'dispatch', minItems: stage.minItems, boundary: 'scope' }) });
    try {
      const runIds = st.features[stage.feature].runs;
      const res = await fake.runHook(fake.HOOKS.trigger, fake.closePayload(`eval-${randomUUID()}`, runIds[runIds.length - 1]), sbx, { apiUrl: base });
      return { status: res.status, stdout: res.stdout, json: res.json };
    } finally {
      sbx.cleanup();
    }
  })();
}

function promptFor(st, stage, runIds) {
  if (stage.prompt === 'manual') return `/task-orchestrator:session-retrospective ${runIds.map((id) => `--run ${id}`).join(' ')}`;
  const scope8 = (st.features[stage.features[0]].featureId || '').slice(0, 8);
  const text = buildDispatch(runIds, st.rootId, undefined, stage.prompt === 'stale' ? { stale: [scope8] } : {});
  const invoke = /invoke (`[^`]+`)/.exec(text);
  if (!invoke) throw new Error('the directive has no invoke text');
  const stale = /Scope\(s\) [^.]*? never completed: start the retrospective title with \[stale\]\. /.exec(text);
  return `Run ${invoke[1]}, project ancestorId ${st.rootId}. ${stale ? stale[0] : ''}Nobody can answer questions in this session, so ask none. Work only from durable MCP state and reply with the retrospective item UUID and a two or three line headline.`;
}

/** A copy of the plugin whose stale rule is cut (the red proof). Returns the copy's path. */
function cutStaleRuleCopy() {
  const dir = mkdtempSync(join(tmpdir(), 'to-eval-plugin-'));
  owned.dirs.push(dir);
  const copy = join(dir, 'task-orchestrator');
  cpSync(PLUGIN, copy, { recursive: true, filter: (src) => !/[\\/](node_modules|evals|tests)([\\/]|$)/.test(src) });
  const file = join(copy, 'skills', 'session-retrospective', 'SKILL.md');
  const text = readFileSync(file, 'utf8');
  const cut = text.replace(/, for a manual run, when its `scopeId` item is not terminal[^.]*?; a scope merely not terminal yet is not stale/, ', for a manual run, whenever its `scopeId` item is not terminal');
  if (cut === text) throw new Error('red proof: the stale rule text was not found in SKILL.md; update run-evals.mjs');
  writeFileSync(file, cut);
  return copy;
}

function claudeConfigDir() {
  return PARENT_ENV.CLAUDE_CONFIG_DIR || join(homedir(), '.claude');
}

function collectTranscripts(sessionId) {
  const texts = [];
  const models = new Set();
  const projects = join(claudeConfigDir(), 'projects');
  if (!existsSync(projects)) return { texts, models: [] };
  const files = [];
  const walk = (dir, depth) => {
    if (depth > 4) return;
    for (const n of readdirSync(dir)) {
      const p = join(dir, n);
      const s = statSync(p);
      if (s.isDirectory()) walk(p, depth + 1);
      else if (n.endsWith('.jsonl')) files.push(p);
    }
  };
  for (const proj of readdirSync(projects)) {
    const pd = join(projects, proj);
    if (!statSync(pd).isDirectory()) continue;
    if (existsSync(join(pd, `${sessionId}.jsonl`))) files.push(join(pd, `${sessionId}.jsonl`));
    if (existsSync(join(pd, sessionId))) walk(join(pd, sessionId), 0);
  }
  for (const f of files) {
    const t = readFileSync(f, 'utf8');
    texts.push(t);
    const sub = /subagents/.test(f.replace(/\\/g, '/'));
    for (const m of t.matchAll(/"model":"(claude-[a-z0-9.-]+)"/g)) models.add(`${sub ? 'fork:' : 'main:'}${m[1]}`);
  }
  return { texts, models: [...models] };
}

async function runLiveStage(st, stage, base, sandbox, runIds, pluginDir) {
  const work = mkdtempSync(join(sandbox.work, 'cwd-'));
  const fake = await import(pathToFileURL(join(PLUGIN, 'hooks', 'tests', 'retro-fake-runs.mjs')).href);
  mkdirSync(join(work, '.taskorchestrator'), { recursive: true });
  // D6: the headless session's own Stop hooks never re-dispatch
  writeFileSync(join(work, '.taskorchestrator', 'config.yaml'), st.configYaml);
  const mcpConfig = join(sandbox.dir, 'mcp.json');
  writeFileSync(mcpConfig, JSON.stringify({ mcpServers: { 'mcp-task-orchestrator': { type: 'http', url: `${base}/mcp` } } }));
  const { env } = buildChildEnv(sandbox);
  Object.assign(env, { TASK_ORCHESTRATOR_API_URL: base, AGENT_CONFIG_DIR: work, TASK_ORCHESTRATOR_CEILING: work });
  delete env.TASK_ORCHESTRATOR_API_TOKEN;
  // The bug-report gate opens in the child env of a bugReports scenario only (the maintainer-only variable).
  if (st.bugReports) env.TASK_ORCHESTRATOR_BUG_REPORTS = '1';
  const sessionId = randomUUID();
  const claude = resolveOn('claude', PARENT_ENV[pathKey(PARENT_ENV)]);
  if (!claude) throw new Error('claude is not on PATH');
  const args = [
    '-p', promptFor(st, stage, runIds), '--plugin-dir', pluginDir, '--strict-mcp-config', '--mcp-config', mcpConfig,
    '--setting-sources', 'project', '--session-id', sessionId, '--disallowedTools', 'Bash(gh:*)', '--max-turns', '80',
    '--allowedTools', 'mcp__mcp-task-orchestrator', 'Bash(node:*)', 'Read', 'Skill', 'Agent', 'Glob', 'Grep',
    '--output-format', 'stream-json', '--verbose',
  ];
  const out = await new Promise((resolveRun) => {
    const child = spawn(claude, args, { cwd: work, env, stdio: ['ignore', 'pipe', 'pipe'] });
    owned.children.add(child);
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => child.kill(), LIVE_TIMEOUT_MS);
    child.stdout.on('data', (c) => { stdout += c; });
    child.stderr.on('data', (c) => { stderr += c; });
    child.on('close', (code) => {
      clearTimeout(timer);
      owned.children.delete(child);
      resolveRun({ exitCode: code, stdout, stderr });
    });
    child.on('error', (e) => {
      clearTimeout(timer);
      resolveRun({ exitCode: -1, stdout, stderr: String(e) });
    });
  });
  const t = collectTranscripts(sessionId);
  return { exitCode: out.exitCode, transcripts: t.texts, models: t.models, tail: resultEvent(out.stdout), stderr: out.stderr.slice(-600) };
}

/** The stream-json result event reduced to what explains a failed session. */
function resultEvent(stdout) {
  for (const line of stdout.split('\n').reverse()) {
    if (!line.includes('"type":"result"')) continue;
    try {
      const e = JSON.parse(line);
      return JSON.stringify({ subtype: e.subtype, is_error: e.is_error, result: String(e.result || '').slice(0, 300), turns: e.num_turns });
    } catch {
      return line.slice(0, 300);
    }
  }
  return stdout.slice(0, 300);
}

function ghCallsReader(sandbox) {
  return () => {
    const p = join(sandbox.ghLog, 'calls.jsonl');
    if (!existsSync(p)) return [];
    return readFileSync(p, 'utf8').split(/\r?\n/).filter(Boolean).map((l) => JSON.parse(l));
  };
}

async function runStages(a, sc, st, api, base, sandbox, rows) {
  const ghCalls = ghCallsReader(sandbox);
  for (const stage of sc.stages) {
    for (const key of stage.seedFeatures || []) {
      const f = sc.features.find((x) => x.key === key);
      st.features[key] = await seedFeature(api, st, f);
    }
    if (stage.kind === 'age-state') {
      const p = join(sandbox.home, '.taskorchestrator', 'github-feedback-state.json');
      if (existsSync(p)) {
        const doc = JSON.parse(readFileSync(p, 'utf8'));
        for (const v of Object.values(doc.seen || {})) v.at = new Date(Date.parse(v.at) - stage.days * DAY).toISOString();
        writeFileSync(p, JSON.stringify(doc));
      }
      continue;
    }
    if (stage.redProof && !a.redproof) continue;
    if (stage.kind === 'live' && a.seedOnly) continue;
    const runIds = stageRunIds(st, stage);
    const otherRunIds = (stage.other || []).flatMap((k) => st.features[k].runs);
    const ctx = {
      read: (tool, body) => api.read(tool, body), rootId: st.rootId, root8: st.root8, stage, runIds, otherRunIds,
      seeded: st.seeded, memory: st.memory, ghCalls, stageStart: new Date().toISOString(),
    };
    const row = { stage: stage.id, date: new Date().toISOString().slice(0, 10), model: 'n/a', results: [] };
    try {
      if (stage.kind === 'hook') {
        ctx.hook = await runHookStage(st, stage, base);
      } else {
        const pluginDir = stage.redProof ? cutStaleRuleCopy() : PLUGIN;
        ctx.live = await runLiveStage(st, stage, base, sandbox, runIds, pluginDir);
        row.model = ctx.live.models.length > 0 ? ctx.live.models.join(',') : 'unobserved';
      }
      const ids = stage.kind === 'live' ? [...LIVE_COMMON, ...(stage.checks || [])] : stage.checks || [];
      const wanted = stage.redProof ? stage.checks : ids;
      for (const id of wanted) {
        let r;
        try {
          r = await CHECKS[id](ctx);
        } catch (e) {
          r = { ok: false, detail: `check threw: ${e.message}` };
        }
        row.results.push({ id, ...r });
      }
    } catch (e) {
      row.results.push({ id: 'stage', ok: false, detail: e.message });
    }
    row.failing = row.results.filter((r) => !r.ok).map((r) => r.id);
    rows.push({ ...row, redProof: Boolean(stage.redProof) });
    console.log(`${stage.id}: ${row.failing.length === 0 ? 'PASS' : 'FAIL ' + row.failing.join(',')}  model=${row.model}`);
    for (const r of row.results) if (!r.ok) console.log(`    ${r.id}: ${r.detail}`);
  }
}

function recordRows(rows, image) {
  const sha = spawnSync('git', ['-C', REPO, 'rev-parse', '--short', 'HEAD'], { encoding: 'utf8' }).stdout.trim();
  if (!existsSync(RESULTS)) writeFileSync(RESULTS, '| stage | date | sha | observed model | result |\n| --- | --- | --- | --- | --- |\n');
  for (const r of rows) {
    // a red proof passes when its check FAILS
    const verdict = r.redProof ? (r.failing.length > 0 ? `red proof held: ${r.failing.join(', ')} failed` : 'red proof NOT held: T1 passed') : r.failing.length === 0 ? 'pass' : `fail: ${r.failing.join(', ')}`;
    appendFileSync(RESULTS, `| ${r.stage}${r.redProof ? ' (red proof)' : ''} | ${r.date} | ${sha} | ${r.model} | ${verdict} |\n`);
  }
  void image;
}

// ---- main -----------------------------------------------------------------------------------------------------------

async function main() {
  let a;
  try {
    a = parseArgs(process.argv.slice(2));
  } catch (e) {
    console.error(`run-evals: ${e.message}`);
    return 2;
  }
  const { scenarios, errors } = loadScenarios(a.scenarios);
  if (errors.length > 0) {
    for (const e of errors) console.error(`run-evals: ${e}`);
    return 2;
  }
  if (a.check) {
    console.log(`${scenarios.length} scenario file(s) valid: ${scenarios.map((s) => s.id).join(', ')}`);
    return 0;
  }
  const sandbox0 = makeSandbox();
  const pre = preflight(sandbox0);
  if (!pre.ok) {
    for (const p of pre.problems) console.error(`preflight: ${p}`);
    return 3;
  }
  if (a.preflight) {
    console.log(`preflight ok: ${pre.droppedPathEntries} PATH entr${pre.droppedPathEntries === 1 ? 'y' : 'ies'} holding gh dropped; no gh token; gh shim = fake-gh.mjs`);
    return 0;
  }
  if (docker(['image', 'inspect', a.image]).status !== 0) {
    console.error(`run-evals: image ${a.image} not found. Build it from the checkout: docker build -t ${a.image} ${REPO}  (or pass --image <existing tag>)`);
    return 2;
  }
  const rows = [];
  let failed = false;
  for (const sc of scenarios) {
    console.log(`== ${sc.id}`);
    const sandbox = makeSandbox();
    const check = preflight(sandbox);
    if (!check.ok) {
      console.error(`preflight: ${check.problems.join('; ')}`);
      return 3;
    }
    try {
      const server = await startServer(a.image);
      const api = makeApi(server.base);
      const st = await seedScenario(api, sc, sandbox);
      const problems = await gradeSeed(api, st, sc);
      if (problems.length > 0) {
        console.log(`seed: FAIL ${problems.join('; ')}`);
        failed = true;
        continue;
      }
      console.log(`seed: ok (root ${st.root8}, ${Object.keys(st.features).length} feature(s))`);
      await runStages(a, sc, st, api, server.base, sandbox, rows);
    } catch (e) {
      console.error(`run-evals: ${sc.id}: ${e.message}`);
      failed = true;
    } finally {
      teardown();
    }
  }
  if (rows.some((r) => (r.redProof ? r.failing.length === 0 : r.failing.length > 0))) failed = true;
  if (a.record && rows.length > 0) recordRows(rows, a.image);
  return failed ? 1 : 0;
}

const code = await main();
process.exitCode = code;
void basename;
