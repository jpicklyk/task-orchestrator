#!/usr/bin/env node
// run-evals.mjs -- scenario evals for the engineering skills. Dev tooling: see README.md (safety, cost, rerun).
//
//   node run-evals.mjs [--scenario <id>]... [--model <alias>]... [--plugin-dir <path>] [--label <text>]
//                      [--check] [--list] [--record] [--expect-pass]
//
// --check        validate the scenario files and fixtures only (no model, zero tokens); exit 0, or 2 when one is bad
// --list         print the scenario ids with their skill, pattern and models
// --scenario     run only this id (repeatable; an id prefix such as a skill name selects every scenario of that skill)
// --model        run on this model alias instead of the scenario's own list (repeatable)
// --plugin-dir   load a plugin from this folder (a rerun WITH the skills under test). Without it every skill is
//                disabled: that is the baseline.
// --label        free text stored in the results row's mode column (default: "baseline" or "with <plugin-dir>")
// --record       append one row per run to results.md
// --expect-pass  exit 1 when any run showed a failure (for a rerun with a skill loaded)
//
// Each run copies a fixture into a throwaway git repository, runs `claude -p` ONCE there (never retried: it costs
// tokens), then grades the result from the files written, the git diff and the final message. No model judge.
// Exit codes: 0 done, 1 a run errored (or --expect-pass saw a failure), 2 bad arguments, scenario or fixture.

import { readFileSync, writeFileSync, readdirSync, mkdirSync, mkdtempSync, rmSync, existsSync, cpSync, appendFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { spawn, spawnSync } from 'node:child_process';
import { dirname, join, resolve, delimiter, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { CHECK_IDS, grade } from './checks.mjs';

const PARENT_ENV = { ...process.env };
const HERE = dirname(fileURLToPath(import.meta.url));
const PLUGIN = resolve(HERE, '..', '..');
const REPO = resolve(PLUGIN, '..', '..');
const SCENARIOS = join(HERE, 'scenarios');
const FIXTURES = join(HERE, 'fixtures');
const RESULTS = join(HERE, 'results.md');
// Caps per run. A realistic-tier run (scenario.snapshot) works in a whole repository, so it gets more of both.
const RUN_TIMEOUT_MS = 15 * 60 * 1000;
const MAX_TURNS = 40;
const SNAPSHOT_TIMEOUT_MS = 20 * 60 * 1000;
const SNAPSHOT_MAX_TURNS = 60;
const SKILLS = ['spotting-code-slop', 'designing-modules', 'organizing-codebases', 'defining-project-terms', 'auditing-codebase-health'];
const KINDS = ['build', 'review', 'audit'];

// ---- arguments --------------------------------------------------------------------------------------------------

export function parseArgs(argv) {
  const a = { scenarios: [], models: [], pluginDir: null, label: null, check: false, list: false, record: false, expectPass: false };
  for (let i = 0; i < argv.length; i += 1) {
    const f = argv[i];
    if (f === '--check') a.check = true;
    else if (f === '--list') a.list = true;
    else if (f === '--record') a.record = true;
    else if (f === '--expect-pass') a.expectPass = true;
    else if (['--scenario', '--model', '--plugin-dir', '--label'].includes(f)) {
      const v = argv[i + 1];
      if (!v || v.startsWith('--')) throw new Error(`${f} needs a value`);
      i += 1;
      if (f === '--scenario') a.scenarios.push(v);
      else if (f === '--model') a.models.push(v);
      else if (f === '--plugin-dir') a.pluginDir = resolve(v);
      else a.label = v;
    } else throw new Error(`unknown argument ${f}`);
  }
  return a;
}

// ---- scenarios --------------------------------------------------------------------------------------------------

export function loadScenarios(dir = SCENARIOS) {
  return readdirSync(dir).filter((n) => n.endsWith('.json')).sort().map((n) => ({ file: n, ...JSON.parse(readFileSync(join(dir, n), 'utf8')) }));
}

/** Problems with the scenario set; an empty list is valid. Also used by the unit test. */
export function validateScenarios(list, fixturesDir = FIXTURES) {
  const problems = [];
  const ids = new Set();
  for (const s of list) {
    const at = s.file || s.id;
    if (!s.id || ids.has(s.id)) problems.push(`${at}: missing or duplicate id`);
    ids.add(s.id);
    if (!SKILLS.includes(s.skill)) problems.push(`${at}: unknown skill ${s.skill}`);
    if (s.id && !String(s.id).startsWith(`${s.skill}-`)) problems.push(`${at}: id must start with the skill name`);
    if (!KINDS.includes(s.kind)) problems.push(`${at}: kind must be one of ${KINDS.join(', ')}`);
    for (const k of ['title', 'prompt', 'pattern', 'expectation']) if (!s[k]) problems.push(`${at}: missing ${k}`);
    if (s.snapshot) {
      if (!/^[0-9a-f]{7,40}$/i.test(String(s.snapshot.ref || ''))) problems.push(`${at}: snapshot.ref must be a commit sha`);
      const o = s.snapshot.overlay;
      if (o && (!/^[0-9a-f]{7,40}$/i.test(String(o.from || '')) || !Array.isArray(o.paths) || o.paths.length === 0)) problems.push(`${at}: snapshot.overlay needs a from sha and a non-empty paths list`);
      if (o && s.snapshot.history) problems.push(`${at}: snapshot.overlay and snapshot.history are exclusive`);
    } else if (!s.fixture) problems.push(`${at}: missing fixture (or a snapshot)`);
    if (!['auto', 'manual'].includes(s.grading)) problems.push(`${at}: grading must be auto or manual`);
    if (s.grading === 'manual' && !s.manual) problems.push(`${at}: manual grading needs a manual field`);
    if (s.kind !== 'build' && !s.output) problems.push(`${at}: a ${s.kind} scenario needs an output file name`);
    if (!Array.isArray(s.models) || s.models.length === 0) problems.push(`${at}: models must be a non-empty list`);
    if (s.fixture && !existsSync(join(fixturesDir, s.fixture))) problems.push(`${at}: fixture ${s.fixture} does not exist`);
    if (!Array.isArray(s.checks) || (s.grading === 'auto' && s.checks.length === 0)) problems.push(`${at}: an auto scenario needs checks`);
    const checkIds = new Set();
    for (const c of s.checks || []) {
      if (!CHECK_IDS.includes(c.type)) problems.push(`${at}: unknown check type ${c.type}`);
      if (!c.id || checkIds.has(c.id)) problems.push(`${at}: check without a unique id`);
      checkIds.add(c.id);
      if (!c.pattern) problems.push(`${at}: check ${c.id} names no pattern`);
      for (const k of ['regex', 'path']) if (c[k]) { try { new RegExp(c[k], 'i'); } catch { problems.push(`${at}: check ${c.id} has a bad ${k}`); } }
    }
  }
  for (const skill of SKILLS) {
    const n = list.filter((s) => s.skill === skill).length;
    if (n < 3) problems.push(`skill ${skill} has ${n} scenarios, at least 3 are required`);
  }
  return problems;
}

// ---- sandbox ----------------------------------------------------------------------------------------------------

const GH_NAMES = ['gh', 'gh.exe', 'gh.cmd', 'gh.bat'];
const pathKey = (env) => Object.keys(env).find((k) => k.toLowerCase() === 'path') || 'PATH';

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

/** The child environment: no gh on PATH, no GitHub token. */
export function buildChildEnv(parentEnv = PARENT_ENV) {
  const env = { ...parentEnv };
  const key = pathKey(env);
  const kept = String(env[key] || '').split(delimiter).filter(Boolean).filter((dir) => !GH_NAMES.some((n) => existsSync(join(dir, n))));
  for (const k of Object.keys(env)) if (k.toLowerCase() === 'path') delete env[k];
  env[key] = kept.join(delimiter);
  for (const k of ['GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN', 'CLAUDE_CODE_PLUGIN_DIRS']) delete env[k];
  return env;
}

function git(cwd, args, extra = {}) {
  const r = spawnSync('git', ['-c', 'user.name=eval', '-c', 'user.email=eval@example.invalid', '-c', 'commit.gpgsign=false', '-c', 'core.autocrlf=false', ...args], { cwd, encoding: 'utf8', ...extra });
  if (r.status !== 0 && !extra.allowFail) throw new Error(`git ${args.join(' ')} failed: ${r.stderr}`);
  return r.stdout || '';
}

const copyFixtureTree = (from, to) => cpSync(from, to, { recursive: true, filter: (src) => !relative(from, src).split(/[\\/]/).some((p) => p.startsWith('_')) });

/**
 * Build the throwaway repository for a scenario. A fixture is either flat (one commit) or has `base/` and `change/`
 * (commit 1 = base, commit 2 = change copied over it, message from the scenario's changeMessage). A flat fixture may
 * carry `_history.json`: { commits: [{ message, touch: [paths] }] }, each commit appending a comment line to its files.
 */
export function buildRepo(scenario, work) {
  if (scenario.snapshot) return buildSnapshot(scenario, work);
  const fx = join(FIXTURES, scenario.fixture);
  mkdirSync(work, { recursive: true });
  git(work, ['init', '-q', '-b', 'main']);
  const layered = existsSync(join(fx, 'base'));
  copyFixtureTree(layered ? join(fx, 'base') : fx, work);
  git(work, ['add', '-A']);
  git(work, ['commit', '-q', '-m', 'initial']);
  if (layered) {
    copyFixtureTree(join(fx, 'change'), work);
    git(work, ['add', '-A']);
    git(work, ['commit', '-q', '-m', scenario.changeMessage || scenario.title]);
  }
  const hist = join(fx, '_history.json');
  if (existsSync(hist)) {
    const { commits } = JSON.parse(readFileSync(hist, 'utf8'));
    commits.forEach((c, i) => {
      for (const p of c.touch) appendFileSync(join(work, p), `\n// revision ${i + 1}\n`);
      git(work, ['add', '-A']);
      git(work, ['commit', '-q', '-m', c.message]);
    });
  }
  return git(work, ['rev-parse', 'HEAD']).trim();
}

// Files that would load the snapshot's own plugin or servers into the headless session; they never reach the agent.
const SNAPSHOT_STRIP = ['.mcp.json', '.claude/settings.json', '.claude/settings.local.json'];

/**
 * The realistic tier: this repository at scenario.snapshot.ref, outside the repository, in a fresh git repository.
 * - default: `git archive <ref>` extracted, one commit (the agent can diff, there is no history);
 * - snapshot.overlay { from, paths, message }: the archive of `ref` as commit 1, then those paths as they are in
 *   commit `from` as commit 2 (a review of a real historical change, without the tests and changelog the scenario leaves out);
 * - snapshot.history: a clone of the repository checked out at `ref`, every other ref and unreachable object removed,
 *   so `git log` shows the real history up to `ref` and nothing after it.
 * The plugin is never loaded from the snapshot: its settings and MCP registrations are removed before the commit.
 */
function buildSnapshot(scenario, work) {
  const { ref, overlay, history } = scenario.snapshot;
  mkdirSync(work, { recursive: true });
  if (history) {
    git(dirname(work), ['clone', '-q', '--no-local', '--no-checkout', REPO, work]);
    git(work, ['checkout', '-q', '-b', 'eval', ref]);
    git(work, ['remote', 'remove', 'origin']);
    const refs = git(work, ['for-each-ref', '--format=%(refname)']).split(/\r?\n/).filter((r) => r && r !== 'refs/heads/eval');
    for (const r of refs) git(work, ['update-ref', '-d', r]);
    git(work, ['reflog', 'expire', '--expire=now', '--all']);
    git(work, ['gc', '-q', '--prune=now']);
    for (const p of SNAPSHOT_STRIP) rmSync(join(work, p), { force: true });
    git(work, ['add', '-A']);
    git(work, ['commit', '-q', '-m', 'eval: remove plugin registrations from the snapshot']);
    return git(work, ['rev-parse', 'HEAD']).trim();
  }
  const tar = spawnSync('git', ['-C', REPO, 'archive', '--format=tar', ref], { maxBuffer: 1024 * 1024 * 1024 });
  if (tar.status !== 0) throw new Error(`git archive ${ref} failed: ${tar.stderr}`);
  const x = spawnSync('tar', ['-x'], { cwd: work, input: tar.stdout, maxBuffer: 1024 * 1024 * 64 });
  if (x.status !== 0) throw new Error(`tar failed: ${x.stderr}`);
  for (const p of SNAPSHOT_STRIP) rmSync(join(work, p), { force: true });
  git(work, ['init', '-q', '-b', 'main']);
  git(work, ['add', '-A']);
  git(work, ['commit', '-q', '-m', `snapshot of ${ref}`]);
  if (overlay) {
    for (const p of overlay.paths) {
      const blob = spawnSync('git', ['-C', REPO, 'show', `${overlay.from}:${p}`], { maxBuffer: 1024 * 1024 * 64 });
      if (blob.status !== 0) throw new Error(`git show ${overlay.from}:${p} failed: ${blob.stderr}`);
      writeFileSync(join(work, p), blob.stdout);
    }
    git(work, ['add', '-A']);
    git(work, ['commit', '-q', '-m', overlay.message || scenario.title]);
  }
  return git(work, ['rev-parse', 'HEAD']).trim();
}

/** Added lines per file, new files and the output file, read from the working tree against the fixture's last commit. */
export function observe(scenario, work, answer) {
  git(work, ['add', '-A', '--', '.', ':(exclude).claude']);
  const status = git(work, ['diff', '--cached', '--no-renames', '--name-status', 'HEAD']).split(/\r?\n/).filter(Boolean);
  const newFiles = status.filter((l) => l.startsWith('A')).map((l) => l.split('\t')[1]);
  const added = {};
  let cur = null;
  for (const line of git(work, ['diff', '--cached', '--no-renames', '-U0', 'HEAD']).split(/\r?\n/)) {
    const h = line.match(/^\+\+\+ b\/(.+)$/);
    if (h) { cur = h[1]; added[cur] = ''; continue; }
    if (cur && line.startsWith('+') && !line.startsWith('+++')) added[cur] += `${line.slice(1)}\n`;
  }
  const outPath = scenario.output ? join(work, scenario.output) : null;
  const output = outPath && existsSync(outPath) ? readFileSync(outPath, 'utf8') : null;
  if (scenario.output) delete added[scenario.output];
  return { added, newFiles: newFiles.filter((p) => p !== scenario.output), output, answer };
}

// ---- running claude ---------------------------------------------------------------------------------------------

const ALLOWED = ['Read', 'Write', 'Edit', 'Glob', 'Grep', 'Bash(node:*)', 'Bash(git:*)', 'Bash(ls:*)', 'Bash(cat:*)', 'Bash(find:*)',
  'Bash(grep:*)', 'Bash(wc:*)', 'Bash(head:*)', 'Bash(sort:*)', 'Skill'];

function parseStream(stdout) {
  let answer = '';
  let tail = '';
  const models = new Set();
  const skills = new Set();
  for (const line of stdout.split('\n')) {
    if (!line.startsWith('{')) continue;
    let e;
    try { e = JSON.parse(line); } catch { continue; }
    if (e.type === 'assistant') {
      if (e.message?.model) models.add(e.message.model);
      for (const part of e.message?.content || []) if (part.type === 'tool_use' && part.name === 'Skill') skills.add(String(part.input?.skill || ''));
    }
    if (e.type === 'result') {
      answer = String(e.result || '');
      tail = JSON.stringify({ subtype: e.subtype, is_error: e.is_error, turns: e.num_turns });
    }
  }
  return { answer, tail, models: [...models], skills: [...skills] };
}

async function runClaude(scenario, model, work, pluginDir) {
  const claude = resolveOn('claude', PARENT_ENV[pathKey(PARENT_ENV)]);
  if (!claude) throw new Error('claude is not on PATH');
  const args = ['-p', scenario.prompt, '--model', model, '--setting-sources', 'project', '--permission-mode', 'acceptEdits',
    '--max-turns', String(scenario.snapshot ? SNAPSHOT_MAX_TURNS : MAX_TURNS), '--allowedTools', ...ALLOWED, ...(scenario.snapshot ? ['Bash(uniq:*)'] : []), '--disallowedTools', 'Bash(gh:*)', '--output-format', 'stream-json', '--verbose'];
  if (pluginDir) args.push('--plugin-dir', pluginDir);
  else args.push('--disable-slash-commands');
  return new Promise((done) => {
    const child = spawn(claude, args, { cwd: work, env: buildChildEnv(), stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => child.kill(), scenario.snapshot ? SNAPSHOT_TIMEOUT_MS : RUN_TIMEOUT_MS);
    child.stdout.on('data', (c) => { stdout += c; });
    child.stderr.on('data', (c) => { stderr += c; });
    child.on('close', (code) => { clearTimeout(timer); done({ exitCode: code, stdout, stderr }); });
    child.on('error', (e) => { clearTimeout(timer); done({ exitCode: -1, stdout, stderr: String(e) }); });
  });
}

// ---- results ----------------------------------------------------------------------------------------------------

const cell = (s) => String(s).replace(/\|/g, '/').replace(/\r?\n/g, ' ');

export function resultRow(r) {
  const failure = r.outcome === 'no failure' ? 'no failure (write no skill text for it)' : r.outcome === 'manual' ? `manual: ${r.manual}` : r.failures.join('; ');
  return `| ${r.date} | ${r.sha} | ${r.model} | ${r.scenario} | ${r.mode} | ${r.outcome === 'no failure' ? 'pass' : r.outcome === 'manual' ? 'manual' : 'fail'} | ${cell(failure)} |`;
}

function record(rows) {
  const existing = readFileSync(RESULTS, 'utf8');
  appendFileSync(RESULTS, `${existing.endsWith('\n') ? '' : '\n'}${rows.map(resultRow).join('\n')}\n`);
}

// ---- main -------------------------------------------------------------------------------------------------------

async function runOne(scenario, model, a, sha) {
  const sandbox = mkdtempSync(join(tmpdir(), 'eng-eval-'));
  const work = join(sandbox, 'repo');
  const row = { date: new Date().toISOString().slice(0, 10), sha, scenario: scenario.id, mode: a.label || (a.pluginDir ? `with ${a.pluginDir}` : 'baseline'), model, failures: [], outcome: 'error' };
  let keep = false;
  try {
    buildRepo(scenario, work);
    const out = await runClaude(scenario, model, work, a.pluginDir);
    const parsed = parseStream(out.stdout);
    row.model = `${model} (${parsed.models.join(',') || 'unobserved'})`;
    if (a.pluginDir) row.mode += parsed.skills.length ? `; skills used: ${parsed.skills.join(',')}` : '; no skill invoked';
    if (out.exitCode !== 0) { row.detail = `claude exit ${out.exitCode}: ${out.stderr.slice(-300)} ${parsed.tail}`; return row; }
    const obs = observe(scenario, work, parsed.answer);
    if (scenario.grading === 'manual') {
      row.outcome = 'manual';
      row.manual = scenario.manual;
      keep = true;
      row.detail = `manual: look at ${work}: ${scenario.manual}`;
    } else {
      const g = grade(scenario, obs);
      row.outcome = g.outcome;
      row.failures = g.failures;
      row.detail = g.results.filter((x) => x.failed).map((x) => `${x.id} ${x.pattern}: ${x.detail}`).join(' | ');
      // A passing review or audit prints what it wrote, so a pass can be checked against the grader.
      if (scenario.output && obs.output) row.seen = obs.output.split(/\r?\n/).filter((l) => /^\s*(FINDING|RANK|VERDICT)\b/i.test(l)).map((l) => l.trim().slice(0, 220));
    }
  } catch (e) {
    row.detail = String(e.message || e);
  } finally {
    if (!keep) rmSync(sandbox, { recursive: true, force: true, maxRetries: 3 });
  }
  return row;
}

async function main() {
  let a;
  try { a = parseArgs(process.argv.slice(2)); } catch (e) { console.error(e.message); process.exit(2); }
  const all = loadScenarios();
  const problems = validateScenarios(all);
  if (a.check) {
    for (const p of problems) console.error(`BAD ${p}`);
    console.log(problems.length ? `${problems.length} problem(s)` : `ok: ${all.length} scenarios`);
    process.exit(problems.length ? 2 : 0);
  }
  if (problems.length) { for (const p of problems) console.error(`BAD ${p}`); process.exit(2); }
  const chosen = a.scenarios.length ? all.filter((s) => a.scenarios.some((p) => s.id === p || s.id.startsWith(`${p}-`))) : all;
  if (chosen.length === 0) { console.error('no scenario matches'); process.exit(2); }
  if (a.list) {
    for (const s of chosen) console.log(`${s.id}\t${s.skill}\t${s.pattern}\t${s.models.join(',')}\t${s.grading}`);
    process.exit(0);
  }
  if (a.pluginDir && !existsSync(join(a.pluginDir, '.claude-plugin', 'plugin.json'))) { console.error(`${a.pluginDir} holds no .claude-plugin/plugin.json`); process.exit(2); }
  const sha = git(REPO, ['rev-parse', '--short', 'HEAD'], { allowFail: true }).trim() || 'unknown';
  const rows = [];
  let errored = false;
  for (const s of chosen) {
    for (const model of a.models.length ? a.models : s.models) {
      process.stdout.write(`${s.id} on ${model} ... `);
      const row = await runOne(s, model, a, sha);
      console.log(`${row.outcome}${row.failures.length ? `: ${row.failures.join('; ')}` : ''}`);
      if (row.detail) console.log(`    ${row.detail}`);
      for (const l of row.seen || []) console.log(`    | ${l}`);
      if (row.outcome === 'error') errored = true;
      else rows.push(row);
    }
  }
  if (a.record && rows.length) record(rows);
  const failed = rows.some((r) => r.outcome === 'fail');
  process.exit(errored || (a.expectPass && failed) ? 1 : 0);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
