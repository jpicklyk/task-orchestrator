// Tests for the director-guard coverage gaps closed in item ef84a1eb (scenarios S1-S11 and probes).
// BLIND author: written from the frozen diagnosis and test-plan notes, the supplied declarations and the
// existing director-guard tests. Oracles: O1 skills/director SKILL.md altitude rule, O2 the item's Done
// clause (every shell-capable tool covered under the marker; notes_read and non-altitude-safe Workflow denied),
// O3 api-reference.md director profile (items_read selects get|list|overview|blocked), O4 coordinator-tiers.md
// and run-wave SKILL.md (altitudeSafe object arg), O5 the registration.mjs builder shape via checkMatchers,
// O6 the guard contract (subagent, headless and no-marker calls are exempt). The E2E tests run the hook as a
// subprocess against a synthetic temp root, so no real marker path is touched.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

import { directorMarkerPath } from '../execution-mode.mjs';
import { orchestratorToolMatcher } from '../registration.mjs';
import { checkMatchers } from '../../scripts/lib/contract-checks.mjs';
import { REASON_PREFIX, SHELL_TOOLS, DIRECTOR_ITEMS_READ_SELECTS, bashAllowed, guardDecision } from '../director-guard.mjs';

const HOOK = fileURLToPath(new URL('../director-guard.mjs', import.meta.url));
const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/]$/, '');
const HOOKS_CONFIG_PATH = join(PLUGIN, 'hooks', 'hooks-config.json');
const GOLDEN_DIR = join(PLUGIN, '..', '..', 'current', 'src', 'test', 'resources', 'golden', 'tools');
const ORCHESTRATION_TOOLS = readdirSync(GOLDEN_DIR)
  .filter((f) => f.endsWith('.orchestration.json'))
  .map((f) => f.split('.')[0])
  .sort();

const NOTES_READ = 'mcp__mcp-task-orchestrator__notes_read';
const DOCS_READ = 'mcp__mcp-task-orchestrator__docs_read';
const ITEMS_READ = 'mcp__mcp-task-orchestrator__items_read';
const ITEMS_READ_DIRECTOR = 'mcp__mcp-task-orchestrator-director__items_read';
const PLUGIN_DOCS_READ = 'mcp__plugin_x_task-orchestrator__docs_read';
const IMPLEMENT_WAVE = 'task-orchestrator:implement-wave';

const GROUP1_MATCHER = 'Skill|Read|Edit|Write|Bash|PowerShell|Monitor|Agent|Grep|Glob|NotebookEdit|Workflow';
const GROUP2_MATCHER = '^mcp__.*task-orchestrator.*__(docs_read|items_read|notes_read)$';

const tracked = [];
function tmp(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix));
  tracked.push(d);
  return d;
}
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});

const call = (tool_name, tool_input = {}, extra = {}) => ({ tool_name, tool_input, session_id: 's', ...extra });
const decide = (input, { env = {}, director = true } = {}) => guardDecision(input, { env, director });
const isDeny = (r) => typeof r === 'string' && r.startsWith(REASON_PREFIX);
const denies = (input, label) => assert.ok(isDeny(decide(input)), `${label ?? JSON.stringify(input)} should deny, got ${JSON.stringify(decide(input))}`);
const allows = (input, label) => assert.equal(decide(input), null, label ?? JSON.stringify(input));
const matcherRe = (m) => new RegExp('^(?:' + m + ')$');

// ---- S1-S3: shell tools other than Bash ---------------------------------------------------

test('S1: PowerShell reading a file is denied under the director marker', () => {
  denies(call('PowerShell', { command: 'Get-Content README.md' }));
});

test('S2: PowerShell git status is denied too (no Bash allow-list carry-over)', () => {
  denies(call('PowerShell', { command: 'git status' }));
  denies(call('PowerShell', { command: 'git log --oneline -5' }));
  denies(call('PowerShell', { command: 'git log (Get-Content x)' }));
});

test('S3: Monitor running a shell command is denied under the director marker', () => {
  denies(call('Monitor', { command: 'cat x' }));
  denies(call('Monitor', { command: 'git status' }));
});

// ---- S4: note and document reads ---------------------------------------------------------

test('S4: notes_read is denied for every select, describe included, and docs_read too', () => {
  denies(call(NOTES_READ, { select: 'get' }));
  denies(call(NOTES_READ, { select: 'list' }));
  denies(call(NOTES_READ, { describe: true }));
  denies(call(DOCS_READ, { select: 'get' }));
  denies(call(DOCS_READ, { describe: true }));
  denies(call(PLUGIN_DOCS_READ, { select: 'get' }));
});

// ---- S5: items_read select allow-list ------------------------------------------------------

test('S5: items_read get|list|overview|blocked are allowed on both connections', () => {
  for (const tool of [ITEMS_READ, ITEMS_READ_DIRECTOR]) {
    for (const select of ['get', 'list', 'overview', 'blocked']) allows(call(tool, { select }), `${tool} ${select}`);
  }
});

test('S5: items_read search|related|schema|context|next|history and an absent select are denied on both connections', () => {
  for (const tool of [ITEMS_READ, ITEMS_READ_DIRECTOR]) {
    for (const select of ['search', 'related', 'schema', 'context', 'next', 'history']) denies(call(tool, { select }), `${tool} ${select}`);
    denies(call(tool, {}), `${tool} absent select`);
  }
});

test('S5: items_read with describe:true is allowed on both connections', () => {
  for (const tool of [ITEMS_READ, ITEMS_READ_DIRECTOR]) allows(call(tool, { describe: true }), tool);
});

test('S5: DIRECTOR_ITEMS_READ_SELECTS is the frozen director profile set', () => {
  assert.deepEqual([...DIRECTOR_ITEMS_READ_SELECTS].sort(), ['blocked', 'get', 'list', 'overview']);
  assert.ok(Object.isFrozen(DIRECTOR_ITEMS_READ_SELECTS));
});

// ---- S6: Workflow ------------------------------------------------------------------------

test('S6: Workflow implement-wave with args.altitudeSafe === true is allowed', () => {
  allows(call('Workflow', { name: IMPLEMENT_WAVE, args: { altitudeSafe: true } }));
});

test('S6: Workflow without the flag, with a non-true flag, with non-object args or another workflow is denied', () => {
  const cases = [
    { name: IMPLEMENT_WAVE },
    { name: IMPLEMENT_WAVE, args: {} },
    { name: IMPLEMENT_WAVE, args: { other: true } },
    { name: IMPLEMENT_WAVE, args: { altitudeSafe: false } },
    { name: IMPLEMENT_WAVE, args: { altitudeSafe: 'true' } },
    { name: IMPLEMENT_WAVE, args: { altitudeSafe: 1 } },
    { name: IMPLEMENT_WAVE, args: '{"altitudeSafe":true}' },
    { name: IMPLEMENT_WAVE, args: null },
    { name: 'task-orchestrator:retro-analysis', args: { altitudeSafe: true } },
    { name: 'task-orchestrator:audit', args: { altitudeSafe: true } },
    { scriptPath: 'x.js', resumeFromRunId: 'r-1' },
  ];
  for (const c of cases) denies(call('Workflow', c), JSON.stringify(c));
});

// ---- S7: regression ---------------------------------------------------------------------

test('S7: Bash git status is still allowed and SendMessage / ListAgents are never denied', () => {
  allows(call('Bash', { command: 'git status' }));
  allows(call('SendMessage', {}));
  allows(call('ListAgents', {}));
  assert.equal(bashAllowed('git status'), true);
  assert.equal(bashAllowed('cat f'), false);
  denies(call('Bash', { command: 'cat f' }));
});

// ---- S8: exemptions --------------------------------------------------------------------

const NEW_DENY_INPUTS = [
  call('PowerShell', { command: 'Get-Content README.md' }),
  call('PowerShell', { command: 'git status' }),
  call('Monitor', { command: 'cat x' }),
  call(NOTES_READ, { select: 'get' }),
  call(NOTES_READ, { describe: true }),
  call(DOCS_READ, { select: 'get' }),
  call(ITEMS_READ, { select: 'search' }),
  call(ITEMS_READ_DIRECTOR, { select: 'context' }),
  call(ITEMS_READ, {}),
  call('Workflow', { name: IMPLEMENT_WAVE }),
  call('Workflow', { name: IMPLEMENT_WAVE, args: { altitudeSafe: false } }),
  call('Workflow', { name: 'task-orchestrator:retro-analysis', args: { altitudeSafe: true } }),
];

test('S8: control, every S8 input denies under the marker with no exemption', () => {
  for (const i of NEW_DENY_INPUTS) denies(i);
});

test('S8: no marker, a subagent (agent_id) or the headless env makes every new deny input pass', () => {
  for (const i of NEW_DENY_INPUTS) {
    assert.equal(decide(i, { director: false }), null, 'no marker ' + JSON.stringify(i));
    assert.equal(decide({ ...i, agent_id: 'a1' }), null, 'agent_id ' + JSON.stringify(i));
    assert.equal(decide(i, { env: { TASK_ORCHESTRATOR_MODE: 'headless-iteration' } }), null, 'headless ' + JSON.stringify(i));
  }
});

// ---- S9: SHELL_TOOLS ratchet -----------------------------------------------------------------

test('S9: SHELL_TOOLS includes Bash, PowerShell and Monitor and is frozen', () => {
  for (const t of ['Bash', 'PowerShell', 'Monitor']) assert.ok(SHELL_TOOLS.includes(t), t);
  assert.ok(Object.isFrozen(SHELL_TOOLS));
});

test('S9 ratchet: every SHELL_TOOLS member is matched by the guard group and every non-Bash member denies git status', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG_PATH, 'utf8'));
  const groups = cfg.hooks.PreToolUse.filter((g) => g.hooks.some((h) => h.command.includes('director-guard.mjs')));
  const group1 = groups.find((g) => !g.matcher.startsWith('^mcp__'));
  assert.ok(group1, 'a non-mcp guard group exists');
  const re = matcherRe(group1.matcher);
  for (const tool of SHELL_TOOLS) {
    assert.ok(re.test(tool), `${tool} is not matched by the guard matcher "${group1.matcher}"`);
    if (tool === 'Bash') {
      allows(call(tool, { command: 'git status' }), 'Bash keeps the allow-list');
    } else {
      denies(call(tool, { command: 'git status' }), `${tool} git status`);
      denies(call(tool, { command: 'cat x' }), `${tool} cat x`);
    }
  }
});

// ---- S10: hooks-config registration ----------------------------------------------------------

test('S10: hooks-config registers exactly two guard groups, both fail-closed, with the declared matchers', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG_PATH, 'utf8'));
  const groups = cfg.hooks.PreToolUse.filter((g) => g.hooks.some((h) => h.command.includes('director-guard.mjs')));
  assert.equal(groups.length, 2);
  for (const g of groups) {
    assert.equal(g.hooks.length, 1);
    assert.equal(g.hooks[0].onFailure, 'block');
    assert.equal(g.hooks[0].timeout, 5);
  }
  assert.deepEqual(groups.map((g) => g.matcher).sort(), [GROUP1_MATCHER, GROUP2_MATCHER].sort());
  assert.equal((JSON.stringify(cfg).match(/director-guard\.mjs/g) || []).length, 2);
});

test('S10: group 1 matches the file, shell, agent and workflow tools and not SendMessage or ListAgents', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG_PATH, 'utf8'));
  const group1 = cfg.hooks.PreToolUse.find((g) => g.matcher === GROUP1_MATCHER);
  assert.ok(group1, 'group 1 present');
  assert.ok(group1.hooks.some((h) => h.command.includes('director-guard.mjs')));
  const re = matcherRe(group1.matcher);
  for (const t of ['Skill', 'Read', 'Edit', 'Write', 'Bash', 'PowerShell', 'Monitor', 'Agent', 'Grep', 'Glob', 'NotebookEdit', 'Workflow']) {
    assert.ok(re.test(t), t);
  }
  assert.ok(!re.test('SendMessage'));
  assert.ok(!re.test('ListAgents'));
});

test('S10: group 2 is the builder-shaped matcher for docs_read, items_read and notes_read on any task-orchestrator server', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG_PATH, 'utf8'));
  const group2 = cfg.hooks.PreToolUse.find((g) => g.matcher.startsWith('^mcp__') && g.hooks.some((h) => h.command.includes('director-guard.mjs')));
  assert.ok(group2, 'group 2 present');
  assert.equal(group2.matcher, orchestratorToolMatcher(['docs_read', 'items_read', 'notes_read']));
  const re = matcherRe(group2.matcher);
  for (const t of [NOTES_READ, DOCS_READ, ITEMS_READ, PLUGIN_DOCS_READ, ITEMS_READ_DIRECTOR]) assert.ok(re.test(t), t);
  for (const t of ['mcp__other__notes_read', NOTES_READ + '_x', 'mcp__mcp-task-orchestrator__notes_write', 'mcp__mcp-task-orchestrator__advance']) {
    assert.ok(!re.test(t), t);
  }
});

test('S10: checkMatchers over the real hooks-config against the orchestration tool set reports nothing', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG_PATH, 'utf8'));
  assert.deepEqual(checkMatchers(cfg, ORCHESTRATION_TOOLS), []);
});

// ---- S11: end to end through the hook process --------------------------------------------------

function e2e() {
  const tmpDir = tmp('to-dcov-tmp-');
  const cwd = tmp('to-dcov-cwd-');
  const saved = {};
  for (const k of ['TMPDIR', 'TEMP', 'TMP']) {
    saved[k] = process.env[k];
    process.env[k] = tmpDir;
  }
  let marker;
  try {
    marker = directorMarkerPath('cov-session');
  } finally {
    for (const k of Object.keys(saved)) {
      if (saved[k] === undefined) delete process.env[k];
      else process.env[k] = saved[k];
    }
  }
  const exec = (input) => {
    const r = spawnSync(process.execPath, [HOOK], {
      env: { ...process.env, TASK_ORCHESTRATOR_MODE: '', TEMP: tmpDir, TMP: tmpDir, TMPDIR: tmpDir },
      cwd,
      input: JSON.stringify(input),
      encoding: 'utf8',
    });
    assert.equal(r.status, 0, r.stderr);
    return JSON.parse(r.stdout);
  };
  const plant = () => {
    mkdirSync(dirname(marker), { recursive: true });
    writeFileSync(marker, '');
  };
  return { exec, plant };
}

const e2eCall = (tool_name, tool_input) => ({ tool_name, tool_input, session_id: 'cov-session' });

test('S11: through the hook process, PowerShell and notes_read emit a deny under the marker and {} without it', () => {
  const { exec, plant } = e2e();
  const inputs = [
    e2eCall('PowerShell', { command: 'Get-Content README.md' }),
    e2eCall(NOTES_READ, { select: 'get' }),
    e2eCall('Workflow', { name: IMPLEMENT_WAVE }),
  ];
  for (const i of inputs) assert.deepEqual(exec(i), {}, 'control, no marker: ' + i.tool_name);
  plant();
  for (const i of inputs) {
    const out = exec(i);
    assert.equal(out.hookSpecificOutput.hookEventName, 'PreToolUse', i.tool_name);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny', i.tool_name);
    assert.ok(out.hookSpecificOutput.permissionDecisionReason.startsWith(REASON_PREFIX), i.tool_name);
  }
  assert.deepEqual(exec(e2eCall(ITEMS_READ, { select: 'get' })), {}, 'allowed select passes under the marker');
  assert.deepEqual(exec(e2eCall('Workflow', { name: IMPLEMENT_WAVE, args: { altitudeSafe: true } })), {}, 'altitude-safe workflow passes');
});

// ---- probes ------------------------------------------------------------------------------------

test('probe: select spelled Get or given as an array is denied', () => {
  denies(call(ITEMS_READ, { select: 'Get' }));
  denies(call(ITEMS_READ, { select: ['get'] }));
  denies(call(ITEMS_READ, { select: 5 }));
  denies(call(ITEMS_READ, { select: null }));
});

test('probe: a Workflow name with a leading or trailing space does not count as implement-wave', () => {
  denies(call('Workflow', { name: ' ' + IMPLEMENT_WAVE, args: { altitudeSafe: true } }));
  denies(call('Workflow', { name: IMPLEMENT_WAVE + ' ', args: { altitudeSafe: true } }));
});

test('probe: a tool whose name only starts like notes_read is not guarded', () => {
  allows(call(NOTES_READ + '_x', { select: 'get' }));
  allows(call('mcp__other__notes_read', { select: 'get' }));
});

test('probe: unusable tool_input (null or a string) denies for each new tool, fail closed', () => {
  for (const tool of ['PowerShell', 'Monitor', NOTES_READ, DOCS_READ, ITEMS_READ, 'Workflow']) {
    for (const ti of [null, 'x']) denies({ tool_name: tool, tool_input: ti, session_id: 's' }, `${tool} ${JSON.stringify(ti)}`);
  }
});

test('probe: replay of the same call gives the same decision', () => {
  for (const i of NEW_DENY_INPUTS) assert.equal(decide(i), decide(i));
  const ok = call('Workflow', { name: IMPLEMENT_WAVE, args: { altitudeSafe: true } });
  assert.equal(decide(ok), decide(ok));
});

test('probe: PowerShell and Monitor with a missing or non-string command still deny', () => {
  for (const tool of ['PowerShell', 'Monitor']) {
    denies(call(tool, {}));
    denies(call(tool, { command: 5 }));
  }
});
