#!/usr/bin/env node
// run-evals.mjs -- scenario evals for the engineering skills. Dev tooling: see README.md (safety, cost, rerun).
//
//   node run-evals.mjs [--scenario <id>]... [--model <alias>]... [--with-skill <name> | --plugin-dir <path>]
//                      [--label <text>] [--run-id <id>] [--check] [--list] [--record] [--collect <run-id>] [--expect-pass]
//
// --check        validate the scenario files and fixtures only (no model, zero tokens); exit 0, or 2 when one is bad
// --list         print the scenario ids with their skill, pattern and models
// --scenario     run only this id (repeatable; an id prefix such as a skill name selects every scenario of that skill)
// --model        run on this model alias instead of the scenario's own list (repeatable)
// --with-skill   a rerun WITH one skill: a throwaway plugin that holds only skills/<name> of this plugin, so nothing
//                else (other skills, hooks, agents) loads. Without --with-skill and --plugin-dir every skill is
//                disabled: that is the baseline.
// --plugin-dir   load a whole plugin folder (every skill and hook in it); not a one-skill rerun, see README.md
// --label        free text stored in the results row's mode column (default: "baseline", "with <skill>" or "with <plugin-dir>")
// --run-id       the folder under runs/ that holds every run's graded artifacts (default: the UTC minute)
// --record       append one row per run to results.md
// --collect      append the rows saved under runs/<run-id>/ to results.md, in scenario order (for runs started in
//                parallel processes, which must not append to results.md themselves)
// --expect-pass  exit 1 when any run showed a failure (for a rerun with a skill loaded)
//
// Each run copies a fixture into a throwaway git repository, runs `claude -p` ONCE there (never retried: it costs
// tokens), then grades the result from the files written, the git diff and the final message. No model judge. The
// graded artifacts of every run (final message, the review or audit file, the diff, the per-check verdicts) are saved
// under runs/<run-id>/<scenario>--<model>/ and the results row names that folder.
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
const RUNS = join(HERE, 'runs');
// Caps per run. A realistic-tier run (scenario.snapshot) works in a whole repository, so it gets more of both.
const RUN_TIMEOUT_MS = 15 * 60 * 1000;
const MAX_TURNS = 40;
const SNAPSHOT_TIMEOUT_MS = 20 * 60 * 1000;
const SNAPSHOT_MAX_TURNS = 60;
// Scenario families: the `skill` field of a scenario and the prefix of its id. Two families have no skill directory of
// their own: spotting-code-slop's catalog lives under skills/auditing-codebase, and organizing-codebases was cancelled.
// Their scenarios stay as baselines; --with-skill takes only a family whose skills/<name>/SKILL.md exists.
const SKILLS = ['spotting-code-slop', 'designing-modules', 'organizing-codebases', 'defining-project-terms', 'auditing-codebase'];
const hasSkillDir = (name, pluginRoot = PLUGIN) => existsSync(join(pluginRoot, 'skills', name, 'SKILL.md'));
const KINDS = ['build', 'review', 'audit'];

// ---- arguments --------------------------------------------------------------------------------------------------

export function parseArgs(argv) {
  const a = { scenarios: [], models: [], pluginDir: null, withSkill: null, label: null, runId: null, collect: null, check: false, list: false, record: false, expectPass: false };
  for (let i = 0; i < argv.length; i += 1) {
    const f = argv[i];
    if (f === '--check') a.check = true;
    else if (f === '--list') a.list = true;
    else if (f === '--record') a.record = true;
    else if (f === '--expect-pass') a.expectPass = true;
    else if (['--scenario', '--model', '--plugin-dir', '--with-skill', '--label', '--run-id', '--collect'].includes(f)) {
      const v = argv[i + 1];
      if (!v || v.startsWith('--')) throw new Error(`${f} needs a value`);
      i += 1;
      if (f === '--scenario') a.scenarios.push(v);
      else if (f === '--model') a.models.push(v);
      else if (f === '--plugin-dir') a.pluginDir = resolve(v);
      else if (f === '--with-skill') a.withSkill = v;
      else if (f === '--run-id') a.runId = v;
      else if (f === '--collect') a.collect = v;
      else a.label = v;
    } else throw new Error(`unknown argument ${f}`);
  }
  if (a.pluginDir && a.withSkill) throw new Error('--plugin-dir and --with-skill are exclusive');
  if (a.withSkill && !(SKILLS.includes(a.withSkill) && hasSkillDir(a.withSkill))) {
    throw new Error(`--with-skill must be one of ${SKILLS.filter((n) => hasSkillDir(n)).join(', ')}`);
  }
  if (a.runId && !/^[\w.-]+$/.test(a.runId)) throw new Error('--run-id may hold letters, digits, dot, underscore and hyphen only');
  if (a.collect && !/^[\w.-]+$/.test(a.collect)) throw new Error('--collect needs a run id of letters, digits, dot, underscore and hyphen');
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

/**
 * Problems that would make a run meaningless: gh still resolves, a GitHub token is set, or a tool the agent needs
 * (node, git, bash) no longer resolves once the gh directories are dropped from PATH (a directory that holds gh and
 * git together). An empty list is fine; the caller treats any problem as an error, never as a model failure.
 */
export function preflightEnv(env) {
  const p = env[pathKey(env)];
  const problems = [];
  const gh = resolveOn('gh', p);
  if (gh) problems.push(`gh still resolves in the child environment: ${gh}`);
  for (const k of ['GH_TOKEN', 'GITHUB_TOKEN']) if (env[k]) problems.push(`${k} is set`);
  for (const tool of ['node', 'git', 'bash']) {
    if (!resolveOn(tool, p)) problems.push(`${tool} no longer resolves once the gh directories are dropped (a directory holding gh also holds ${tool}: run from a PATH that keeps them apart)`);
  }
  return problems;
}

/** A throwaway plugin that holds exactly one skill of this plugin, so a rerun loads that skill and nothing else. */
export function makeSkillPlugin(skill, root = tmpdir(), pluginRoot = PLUGIN) {
  const src = join(pluginRoot, 'skills', skill);
  if (!existsSync(join(src, 'SKILL.md'))) throw new Error(`skills/${skill}/SKILL.md does not exist in this plugin yet: nothing to rerun with`);
  const dir = mkdtempSync(join(root, 'eng-eval-skill-'));
  mkdirSync(join(dir, '.claude-plugin'), { recursive: true });
  writeFileSync(join(dir, '.claude-plugin', 'plugin.json'), `${JSON.stringify({ name: 'eng-eval-skill', version: '0.0.0', description: `only the ${skill} skill`, skills: './skills' }, null, 2)}\n`);
  cpSync(src, join(dir, 'skills', skill), { recursive: true });
  return dir;
}

/** The checkout sha the graders run at, with +dirty when tracked or untracked files other than results and run artifacts differ. */
export function checkoutLabel(head, porcelain) {
  const ignored = /^claude-plugins\/task-orchestrator\/evals\/engineering\/(runs\/|results\.md$)/;
  const dirty = String(porcelain).split(/\r?\n/).filter(Boolean).map((l) => l.slice(3).replace(/^"|"$/g, '')).filter((p) => !ignored.test(p));
  return `${head}${dirty.length ? '+dirty' : ''}`;
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

// Files that would load the snapshot's own plugin, servers, skills or agents into the headless session; they never
// reach the agent. A rerun with a skill loads that skill only (--with-skill), so the repository's own project skills
// (.claude/skills) must not load beside it.
const SNAPSHOT_STRIP = ['.mcp.json', '.claude/settings.json', '.claude/settings.local.json', '.claude/skills', '.claude/commands', '.claude/agents'];

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
    for (const p of SNAPSHOT_STRIP) rmSync(join(work, p), { recursive: true, force: true });
    git(work, ['add', '-A']);
    git(work, ['commit', '-q', '-m', 'eval: remove plugin registrations from the snapshot']);
    return git(work, ['rev-parse', 'HEAD']).trim();
  }
  const tar = spawnSync('git', ['-C', REPO, 'archive', '--format=tar', ref], { maxBuffer: 1024 * 1024 * 1024 });
  if (tar.status !== 0) throw new Error(`git archive ${ref} failed: ${tar.stderr}`);
  const x = spawnSync('tar', ['-x'], { cwd: work, input: tar.stdout, maxBuffer: 1024 * 1024 * 64 });
  if (x.status !== 0) throw new Error(`tar failed: ${x.stderr}`);
  for (const p of SNAPSHOT_STRIP) rmSync(join(work, p), { recursive: true, force: true });
  git(work, ['init', '-q', '-b', 'main']);
  git(work, ['add', '-A']);
  git(work, ['commit', '-q', '-m', `snapshot of ${ref}`]);
  if (overlay) {
    for (const p of overlay.paths) {
      const blob = spawnSync('git', ['-C', REPO, 'show', `${overlay.from}:${p}`], { maxBuffer: 1024 * 1024 * 64 });
      if (blob.status !== 0) throw new Error(`git show ${overlay.from}:${p} failed: ${blob.stderr}`);
      mkdirSync(dirname(join(work, p)), { recursive: true });
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
  const failure = r.outcome === 'no failure' ? 'no failure observed' : r.outcome === 'manual' ? `manual: ${r.manual}` : r.failures.join('; ');
  return `| ${r.date} | ${r.sha} | ${r.model} | ${r.scenario} | ${r.mode} | ${r.outcome === 'no failure' ? 'pass' : r.outcome === 'manual' ? 'manual' : 'fail'} | ${cell(failure)} | ${r.artifacts || '-'} |`;
}

function record(rows) {
  const existing = readFileSync(RESULTS, 'utf8');
  appendFileSync(RESULTS, `${existing.endsWith('\n') ? '' : '\n'}${rows.map(resultRow).join('\n')}\n`);
}

const MAX_PATCH_BYTES = 200 * 1024;

/**
 * Save what the grader saw, so a row can be audited: the final message, the review or audit file, the staged diff
 * (stat and patch), and each check's verdict. Returns the folder relative to this directory, with forward slashes.
 */
export function saveArtifacts(base, runId, scenario, model, work, obs, g, extra = {}) {
  const dirName = `${scenario.id}--${model}`;
  const dir = join(base, runId, dirName);
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'answer.md'), `${obs.answer || ''}\n`);
  if (scenario.output && obs.output != null) writeFileSync(join(dir, scenario.output), obs.output);
  writeFileSync(join(dir, 'diff.stat'), git(work, ['diff', '--cached', '--no-renames', '--stat', 'HEAD'], { allowFail: true }));
  const patch = git(work, ['diff', '--cached', '--no-renames', 'HEAD'], { allowFail: true });
  writeFileSync(join(dir, 'diff.patch'), patch.length > MAX_PATCH_BYTES ? `${patch.slice(0, MAX_PATCH_BYTES)}\n[cut at ${MAX_PATCH_BYTES} bytes]\n` : patch);
  const lines = g ? g.results.map((x) => `${x.failed ? 'FAIL' : 'ok  '} ${x.id} ${x.pattern}${x.detail ? `: ${x.detail}` : ''}`) : ['manual grading'];
  writeFileSync(join(dir, 'grading.txt'), `${lines.join('\n')}\n`);
  writeFileSync(join(dir, 'run.json'), `${JSON.stringify(extra, null, 2)}\n`);
  return `runs/${runId}/${dirName}/`.replace(/\\/g, '/');
}

// ---- main -------------------------------------------------------------------------------------------------------

async function runOne(scenario, model, a, sha, runId, pluginDir) {
  const sandbox = mkdtempSync(join(tmpdir(), 'eng-eval-'));
  const work = join(sandbox, 'repo');
  const mode = a.label || (a.withSkill ? `with ${a.withSkill}` : a.pluginDir ? `with ${a.pluginDir}` : 'baseline');
  const row = { date: new Date().toISOString().slice(0, 10), sha, scenario: scenario.id, mode, model, failures: [], outcome: 'error' };
  let keep = false;
  try {
    buildRepo(scenario, work);
    const out = await runClaude(scenario, model, work, pluginDir);
    const parsed = parseStream(out.stdout);
    row.model = `${model} (${parsed.models.join(',') || 'unobserved'})`;
    if (pluginDir) row.mode += parsed.skills.length ? `; skills used: ${parsed.skills.join(',')}` : '; no skill invoked';
    if (out.exitCode !== 0) { row.detail = `claude exit ${out.exitCode}: ${out.stderr.slice(-300)} ${parsed.tail}`; return row; }
    const obs = observe(scenario, work, parsed.answer);
    const g = scenario.grading === 'manual' ? null : grade(scenario, obs);
    row.artifacts = saveArtifacts(RUNS, runId, scenario, model, work, obs, g, { models: parsed.models, skills: parsed.skills, result: parsed.tail });
    if (!g) {
      row.outcome = 'manual';
      row.manual = scenario.manual;
      keep = true;
      row.detail = `manual: look at ${work}: ${scenario.manual}`;
    } else {
      row.outcome = g.outcome;
      row.failures = g.failures;
      row.detail = g.results.filter((x) => x.failed).map((x) => `${x.id} ${x.pattern}: ${x.detail}`).join(' | ');
      // A passing review or audit prints what it wrote, so a pass can be checked against the grader.
      if (scenario.output && obs.output) row.seen = obs.output.split(/\r?\n/).filter((l) => /^\s*(FINDING|RANK|VERDICT)\b/i.test(l)).map((l) => l.trim().slice(0, 220));
    }
    writeFileSync(join(RUNS, runId, `${scenario.id}--${model}`, 'row.json'), `${JSON.stringify({ ...row, seen: undefined, detail: undefined }, null, 2)}\n`);
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
  if (a.collect) {
    try {
      const rows = collectRows(a.collect);
      record(rows);
      console.log(`appended ${rows.length} row(s) from runs/${a.collect}/`);
      process.exit(0);
    } catch (e) { console.error(e.message); process.exit(2); }
  }
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
  const env = preflightEnv(buildChildEnv());
  if (env.length) { for (const p of env) console.error(`PREFLIGHT ${p}`); process.exit(2); }
  const head = git(REPO, ['rev-parse', '--short', 'HEAD'], { allowFail: true }).trim() || 'unknown';
  const sha = checkoutLabel(head, git(REPO, ['status', '--porcelain', '--untracked-files=all'], { allowFail: true }));
  const runId = a.runId || new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-');
  let pluginDir = a.pluginDir;
  if (a.withSkill) {
    try { pluginDir = makeSkillPlugin(a.withSkill); } catch (e) { console.error(e.message); process.exit(2); }
  }
  const rows = [];
  let errored = false;
  try {
    for (const s of chosen) {
      for (const model of a.models.length ? a.models : s.models) {
        process.stdout.write(`${s.id} on ${model} ... `);
        const row = await runOne(s, model, a, sha, runId, pluginDir);
        console.log(`${row.outcome}${row.failures.length ? `: ${row.failures.join('; ')}` : ''}`);
        if (row.detail) console.log(`    ${row.detail}`);
        for (const l of row.seen || []) console.log(`    | ${l}`);
        if (row.outcome === 'error') errored = true;
        else rows.push(row);
      }
    }
  } finally {
    if (a.withSkill && pluginDir) rmSync(pluginDir, { recursive: true, force: true, maxRetries: 3 });
  }
  if (a.record && rows.length) record(rows);
  const failed = rows.some((r) => r.outcome === 'fail');
  process.exit(errored || (a.expectPass && failed) ? 1 : 0);
}

/** Rows saved by runs of one run id, in scenario then model order, ready for results.md. */
export function collectRows(runId, base = RUNS) {
  const dir = join(base, runId);
  if (!existsSync(dir)) throw new Error(`no runs under ${dir}`);
  return readdirSync(dir).sort().map((d) => join(dir, d, 'row.json')).filter((p) => existsSync(p)).map((p) => JSON.parse(readFileSync(p, 'utf8')));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
