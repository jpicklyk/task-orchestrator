// Tests for the director guard host-tool coverage (item 240116c3, scenarios S1-S11 and probes).
// BLIND author: written from the frozen diagnosis and test-plan notes, the supplied declarations and the
// existing director-guard tests. Oracles: the item's acceptance criteria AC1-AC8, the director SKILL.md
// altitude rule, coordinator-tiers.md (the host UI tools), and the golden runs_read.director.json select set.
// Review R1 additions (ruling fix-decisions-r1): the allow-list is seven bare names (get_settings and
// set_session_output_style join the five), O2 cross-server bare-name allow, O8 gh flag spellings.
// Harness: guardDecision(input, { env, director }) for decisions; the hook process against a synthetic temp
// root with a planted marker for the end-to-end scenario, so no real marker path is touched.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync, readdirSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

import { directorMarkerPath } from '../execution-mode.mjs';
import { orchestratorToolMatcher, hostToolMatcher } from '../registration.mjs';
import { checkMatchers } from '../../scripts/lib/contract-checks.mjs';
import {
  REASON_PREFIX,
  DIRECTOR_SKILL_NAMES,
  DIRECTOR_SKILL_ALLOW,
  DIRECTOR_HOST_MCP_ALLOW,
  DIRECTOR_RUNS_READ_SELECTS,
  GH_PR_JSON_FIELDS,
  bashAllowed,
  guardDecision,
} from '../director-guard.mjs';

const HOOK = fileURLToPath(new URL('../director-guard.mjs', import.meta.url));
const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/]$/, '');
const HOOKS_CONFIG_PATH = join(PLUGIN, 'hooks', 'hooks-config.json');
const GOLDEN_DIR = join(PLUGIN, '..', '..', 'current', 'src', 'test', 'resources', 'golden', 'tools');
const ORCHESTRATION_TOOLS = readdirSync(GOLDEN_DIR)
  .filter((f) => f.endsWith('.orchestration.json'))
  .map((f) => f.split('.')[0])
  .sort();

const NOTES_READ = 'mcp__mcp-task-orchestrator__notes_read';
const NOTES_WRITE = 'mcp__mcp-task-orchestrator__notes_write';
const CONFIG = 'mcp__mcp-task-orchestrator__config';
const RUNS_READ = 'mcp__mcp-task-orchestrator__runs_read';
const RUNS_READ_DIRECTOR = 'mcp__mcp-task-orchestrator-director__runs_read';
const IMPLEMENT_WAVE = 'task-orchestrator:implement-wave';

const HOST_DENY_TOOLS = [
  'mcp__terminal__read_terminal',
  'mcp__e773fde7-78fe-4237-be9f-d0d3a7e2057b__read_file_content',
  'mcp__computer-use__screenshot',
  'mcp__ccd_session_mgmt__search_session_transcripts',
  'mcp__to__notes_read',
  // Review R1: host tools that sit next to an allowed one but are not on the list stay denied.
  'mcp__ccd_settings__set_setting',
  'mcp__ccd_session_mgmt__set_session_title',
  'mcp__ccd_session_mgmt__set_session_model',
];
const HOST_ALLOW_TOOLS = [
  'mcp__ccd_session__start_session',
  'mcp__ccd_session_mgmt__send_message',
  'mcp__ccd_turn__update_plan',
  'mcp__ccd_turn__wrap_up',
  'mcp__ccd_session__mark_chapter',
  // Review R1 (B1): the tier-1 launch profile calls these two.
  'mcp__ccd_settings__get_settings',
  'mcp__ccd_session_mgmt__set_session_output_style',
];
const ALLOWED_HOST_NAMES = [
  'start_session', 'send_message', 'update_plan', 'wrap_up', 'mark_chapter', 'get_settings', 'set_session_output_style',
];
const HOST_SERVERS = ['terminal', 'ccd_session', 'ccd_session_mgmt', 'ccd_settings', 'ccd_turn', 'computer-use', 'to', 'e773fde7-78fe-4237-be9f-d0d3a7e2057b'];
const CONTENT_TOOL_NAMES = ['WebFetch', 'WebSearch', 'ReadMcpResourceTool', 'ReadMcpResourceDirTool'];

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
const cmd = (command) => call('Bash', { command });

// ---- S1: host MCP tools are denied ------------------------------------------------------------

test('S1: a host MCP tool (terminal, file store, computer use, session transcripts, token-less TO key) is denied', () => {
  for (const t of HOST_DENY_TOOLS) {
    denies(call(t, {}), t);
    denies(call(t, { select: 'get' }), t + ' with input');
  }
});

// ---- S2: the host allow-list ---------------------------------------------------------------------

test('S2: the seven director host tools are allowed by bare name on any host server', () => {
  for (const t of HOST_ALLOW_TOOLS) allows(call(t, {}), t);
});

test('S2 R1: get_settings and set_session_output_style are allowed under the marker on any host server', () => {
  for (const server of HOST_SERVERS) {
    allows(call(`mcp__${server}__get_settings`, {}), `${server} get_settings`);
    allows(call(`mcp__${server}__set_session_output_style`, { style: 'x' }), `${server} set_session_output_style`);
  }
});

test('S2 R1 O2: the bare-name allow applies across servers, so mcp__terminal__start_session is allowed', () => {
  allows(call('mcp__terminal__start_session', {}), 'mcp__terminal__start_session');
  for (const server of HOST_SERVERS) {
    for (const name of ALLOWED_HOST_NAMES) allows(call(`mcp__${server}__${name}`, {}), `${server} ${name}`);
  }
});

test('S2: a bare name that merely contains or extends an allowed name is denied', () => {
  for (const t of [
    'mcp__ccd_session__start_session_v2', 'mcp__x__xstart_session', 'mcp__x__list_sessions',
    'mcp__x__get_settings_all', 'mcp__x__xget_settings', 'mcp__x__get_setting',
    'mcp__x__set_session_output_styles', 'mcp__x__xset_session_output_style', 'mcp__x__set_session_output',
  ]) denies(call(t, {}), t);
});

test('S2 R1: a host tool next to an allowed one but not on the list is still denied (set_setting, set_session_title, set_session_model)', () => {
  for (const t of ['mcp__ccd_settings__set_setting', 'mcp__ccd_session_mgmt__set_session_title', 'mcp__ccd_session_mgmt__set_session_model']) {
    denies(call(t, {}), t);
    denies(call(t, { value: 'x' }), t + ' with input');
  }
});

test('S2: DIRECTOR_HOST_MCP_ALLOW is frozen and is exactly the seven names', () => {
  assert.deepEqual(
    [...DIRECTOR_HOST_MCP_ALLOW].sort(),
    ['get_settings', 'mark_chapter', 'send_message', 'set_session_output_style', 'start_session', 'update_plan', 'wrap_up'],
  );
  assert.ok(Object.isFrozen(DIRECTOR_HOST_MCP_ALLOW));
});

// ---- S3: web and resource tools ----------------------------------------------------------------

test('S3: WebFetch, WebSearch, ReadMcpResourceTool and ReadMcpResourceDirTool are denied', () => {
  for (const t of CONTENT_TOOL_NAMES) {
    denies(call(t, {}), t);
    denies(call(t, { url: 'http://localhost:3001/api/v1/items/x/notes/y' }), t + ' with a url');
  }
});

// ---- S4: config and runs_read -------------------------------------------------------------------

test('S4: config is denied for every op and for describe', () => {
  denies(call(CONFIG, { ops: [{ op: 'get' }] }));
  denies(call(CONFIG, { ops: [{ op: 'rules', key: 'x' }] }));
  denies(call(CONFIG, { op: 'get' }));
  denies(call(CONFIG, { describe: true }));
  denies(call(CONFIG, {}));
});

test('S4: runs_read get|list|frontier|decisions|decision are allowed on both connections, describe too', () => {
  for (const tool of [RUNS_READ, RUNS_READ_DIRECTOR]) {
    for (const select of ['get', 'list', 'frontier', 'decisions', 'decision']) allows(call(tool, { select }), `${tool} ${select}`);
    allows(call(tool, { describe: true }), `${tool} describe`);
  }
});

test('S4: runs_read seats and an absent select are denied on both connections', () => {
  for (const tool of [RUNS_READ, RUNS_READ_DIRECTOR]) {
    denies(call(tool, { select: 'seats' }), `${tool} seats`);
    denies(call(tool, {}), `${tool} absent select`);
  }
});

test('S4: DIRECTOR_RUNS_READ_SELECTS is frozen and is exactly the director profile set', () => {
  assert.deepEqual([...DIRECTOR_RUNS_READ_SELECTS].sort(), ['decision', 'decisions', 'frontier', 'get', 'list']);
  assert.ok(Object.isFrozen(DIRECTOR_RUNS_READ_SELECTS));
});

// ---- S5: gh pr view / list flag allow-list -----------------------------------------------------------

test('S5: gh pr view / list with operands, -R, status or body json fields and list filters are allowed', () => {
  for (const c of [
    'gh pr view 12',
    'gh pr view 12 --json state,body',
    'gh pr view --json=mergeStateStatus 12',
    'gh pr view 12 -R o/r',
    'gh pr list --state open -L 5 --json number',
  ]) {
    assert.equal(bashAllowed(c), true, c);
    allows(cmd(c), c);
  }
});

test('S5: gh pr view / list reading comments, reviews, the browser or a free-form projection is denied', () => {
  for (const c of [
    'gh pr view 12 --comments',
    'gh pr view 12 -c',
    'gh pr view 12 --json body,comments',
    'gh pr view 12 --json reviews',
    'gh pr view 12 --json latestReviews',
    'gh pr view 12 --json',
    'gh pr view 12 --json state,',
    'gh pr view 12 --web',
    'gh pr view 12 --jq .title',
    'gh pr list --json comments',
  ]) {
    assert.equal(bashAllowed(c), false, c);
    denies(cmd(c), c);
  }
});

// Review R1 O8. Oracles: the ruling names --repo=x allowed and -R=x denied; for the list filters the evidence is the
// public contract text (hooks README director-guard row, CHANGELOG Unreleased Plugin entry: "gh pr view and gh pr list
// take only operands, -R, listing filters and --json ...") and the gh CLI reference, where -d/--draft, -S/--search and
// --app are the filters of gh pr list.
test('S5 R1 O8: --repo=x is allowed on gh pr view and gh pr list', () => {
  for (const c of ['gh pr view 12 --repo=x', 'gh pr view --repo=x 12', 'gh pr list --repo=x']) {
    assert.equal(bashAllowed(c), true, c);
    allows(cmd(c), c);
  }
});

// Fails if the guard accepted an equals-attached short flag: -R=x would then be allowed.
test('S5 R1 O8: -R=x is denied on gh pr view and gh pr list', () => {
  for (const c of ['gh pr view 12 -R=x', 'gh pr view -R=x 12', 'gh pr list -R=x']) {
    assert.equal(bashAllowed(c), false, c);
    denies(cmd(c), c);
  }
});

test('S5 R1 O8: gh pr list -d, --search <v> and --app=<v> are listing filters and are allowed', () => {
  for (const c of ['gh pr list -d', 'gh pr list --search is:open', 'gh pr list --app=dependabot', 'gh pr list -d --state open --json number']) {
    assert.equal(bashAllowed(c), true, c);
    allows(cmd(c), c);
  }
});

test('S5: GH_PR_JSON_FIELDS is frozen and holds status and body fields but no comment or review field', () => {
  assert.ok(Object.isFrozen(GH_PR_JSON_FIELDS));
  for (const f of ['state', 'body', 'mergeStateStatus', 'number', 'title']) assert.ok(GH_PR_JSON_FIELDS.includes(f), f);
  for (const f of ['comments', 'reviews', 'latestReviews']) assert.ok(!GH_PR_JSON_FIELDS.includes(f), f);
});

// ---- S6: Skill allow-list -----------------------------------------------------------------------------

test('S6: Skill director, task-orchestrator:director, a padded name and task-orchestrator:model-effort are allowed', () => {
  for (const skill of ['director', 'task-orchestrator:director', ' director ', 'task-orchestrator:model-effort']) {
    allows(call('Skill', { skill }), JSON.stringify(skill));
  }
});

test('S6: any other Skill, an absent skill and a non-string skill are denied', () => {
  denies(call('Skill', { skill: 'task-orchestrator:init' }));
  denies(call('Skill', { skill: 'task-orchestrator:run-wave' }));
  denies(call('Skill', {}));
  denies(call('Skill', { skill: 5 }));
});

test('S6: DIRECTOR_SKILL_ALLOW is the director names plus model-effort, frozen', () => {
  assert.deepEqual([...DIRECTOR_SKILL_ALLOW].sort(), [...DIRECTOR_SKILL_NAMES, 'task-orchestrator:model-effort'].sort());
  assert.ok(Object.isFrozen(DIRECTOR_SKILL_ALLOW));
});

// ---- S7: Workflow scriptPath / resumeFromRunId --------------------------------------------------------

const SAFE_WORKFLOW = { name: IMPLEMENT_WAVE, args: { altitudeSafe: true } };

test('S7: the altitude-safe implement-wave Workflow is allowed', () => {
  allows(call('Workflow', SAFE_WORKFLOW));
});

test('S7: the same call with an own scriptPath or resumeFromRunId, whatever the value, is denied', () => {
  denies(call('Workflow', { ...SAFE_WORKFLOW, scriptPath: 'x.js' }));
  denies(call('Workflow', { ...SAFE_WORKFLOW, resumeFromRunId: 'r-1' }));
  denies(call('Workflow', { ...SAFE_WORKFLOW, scriptPath: null }));
  denies(call('Workflow', { ...SAFE_WORKFLOW, resumeFromRunId: '' }));
  denies(call('Workflow', { ...SAFE_WORKFLOW, scriptPath: 'x.js', resumeFromRunId: 'r-1' }));
});

// ---- S8: exemptions --------------------------------------------------------------------------------------

const NEW_DENY_INPUTS = [
  ...HOST_DENY_TOOLS.map((t) => call(t, {})),
  ...CONTENT_TOOL_NAMES.map((t) => call(t, {})),
  call(CONFIG, { ops: [{ op: 'get' }] }),
  call(CONFIG, { describe: true }),
  call(RUNS_READ, { select: 'seats' }),
  call(RUNS_READ_DIRECTOR, {}),
  cmd('gh pr view 12 --comments'),
  cmd('gh pr view 12 --json reviews'),
  call('Skill', { skill: 'task-orchestrator:init' }),
  call('Skill', { skill: 'task-orchestrator:run-wave' }),
  call('Workflow', { ...SAFE_WORKFLOW, scriptPath: 'x.js' }),
  call('Workflow', { ...SAFE_WORKFLOW, resumeFromRunId: 'r-1' }),
];

test('S8: control, every new deny input denies under the marker', () => {
  for (const i of NEW_DENY_INPUTS) denies(i);
});

test('S8: no marker, a subagent (agent_id) or the headless env lets every new deny input pass', () => {
  for (const i of NEW_DENY_INPUTS) {
    assert.equal(decide(i, { director: false }), null, 'no marker ' + JSON.stringify(i));
    assert.equal(decide({ ...i, agent_id: 'a1' }), null, 'agent_id ' + JSON.stringify(i));
    assert.equal(decide(i, { env: { TASK_ORCHESTRATOR_MODE: 'headless-iteration' } }), null, 'headless ' + JSON.stringify(i));
  }
});

test('S8 R1: without the marker, in a subagent or in the headless env every host tool passes, allow-listed or not', () => {
  for (const t of [...HOST_DENY_TOOLS, ...HOST_ALLOW_TOOLS]) {
    assert.equal(decide(call(t, {}), { director: false }), null, 'no marker ' + t);
    assert.equal(decide(call(t, {}, { agent_id: 'a1' })), null, 'agent_id ' + t);
    assert.equal(decide(call(t, {}), { env: { TASK_ORCHESTRATOR_MODE: 'headless-iteration' } }), null, 'headless ' + t);
  }
});

test('S8: under the marker SendMessage is never denied, TO notes_write is not guarded, notes_read is denied', () => {
  allows(call('SendMessage', {}));
  allows(call('ListAgents', {}));
  allows(call(NOTES_WRITE, { ops: [] }));
  denies(call(NOTES_READ, { select: 'get' }));
});

// ---- S9: matcher and registration -----------------------------------------------------------------------

test('S9: hostToolMatcher is the negative-lookahead host matcher and matches host tools only', () => {
  assert.equal(hostToolMatcher(), '^mcp__(?!.*task-orchestrator).*$');
  const re = new RegExp(hostToolMatcher());
  for (const t of [...HOST_DENY_TOOLS.filter((x) => !x.includes('task-orchestrator')), ...HOST_ALLOW_TOOLS]) assert.ok(re.test(t), t);
  for (const t of [NOTES_WRITE, NOTES_READ, 'WebFetch']) assert.ok(!re.test(t), t);
});

test('S9: hooks-config registers three director-guard groups, one hook each, timeout 5, fail-closed, with the declared matchers', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG_PATH, 'utf8'));
  const groups = cfg.hooks.PreToolUse.filter((g) => g.hooks.some((h) => h.command.includes('director-guard.mjs')));
  assert.equal(groups.length, 3);
  for (const g of groups) {
    assert.equal(g.hooks.length, 1);
    assert.equal(g.hooks[0].timeout, 5);
    assert.equal(g.hooks[0].onFailure, 'block');
  }
  const group1 = 'Skill|Read|Edit|Write|Bash|PowerShell|Monitor|Agent|Grep|Glob|NotebookEdit|Workflow|' + CONTENT_TOOL_NAMES.join('|');
  assert.deepEqual(groups.map((g) => g.matcher).sort(), [
    group1,
    orchestratorToolMatcher(['config', 'docs_read', 'items_read', 'notes_read', 'runs_read']),
    hostToolMatcher(),
  ].sort());
});

test('S9: checkMatchers over the real hooks-config against the orchestration tool set reports nothing', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG_PATH, 'utf8'));
  assert.deepEqual(checkMatchers(cfg, ORCHESTRATION_TOOLS), []);
});

// ---- S10: end to end through the hook process ------------------------------------------------------------

function e2e() {
  const tmpDir = tmp('to-dhost-tmp-');
  const cwd = tmp('to-dhost-cwd-');
  const saved = {};
  for (const k of ['TMPDIR', 'TEMP', 'TMP']) {
    saved[k] = process.env[k];
    process.env[k] = tmpDir;
  }
  let marker;
  try {
    marker = directorMarkerPath('host-session');
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
  return { exec, plant, marker };
}

const e2eCall = (tool_name, tool_input) => ({ tool_name, tool_input, session_id: 'host-session' });

test('S10: through the hook process, host, web, config and foreign Skill calls deny under the marker and pass without it', () => {
  const { exec, plant } = e2e();
  const denyInputs = [
    e2eCall('mcp__terminal__read_terminal', {}),
    e2eCall('WebFetch', { url: 'http://localhost:3001/x' }),
    e2eCall(CONFIG, { ops: [{ op: 'get' }] }),
    e2eCall('Skill', { skill: 'task-orchestrator:init' }),
  ];
  for (const i of denyInputs) assert.deepEqual(exec(i), {}, 'control, no marker: ' + i.tool_name);
  assert.deepEqual(exec(e2eCall('mcp__ccd_session__start_session', {})), {}, 'no marker, allow-listed host tool');
  for (const t of ['mcp__ccd_settings__get_settings', 'mcp__ccd_session_mgmt__set_session_output_style']) {
    assert.deepEqual(exec(e2eCall(t, {})), {}, 'no marker, ' + t);
  }
  plant();
  for (const i of denyInputs) {
    const out = exec(i);
    assert.equal(out.hookSpecificOutput.hookEventName, 'PreToolUse', i.tool_name);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny', i.tool_name);
    assert.ok(out.hookSpecificOutput.permissionDecisionReason.startsWith(REASON_PREFIX), i.tool_name);
  }
  assert.deepEqual(exec(e2eCall('mcp__ccd_session__start_session', {})), {}, 'allow-listed host tool passes under the marker');
  for (const t of ['mcp__ccd_settings__get_settings', 'mcp__ccd_session_mgmt__set_session_output_style', 'mcp__terminal__start_session']) {
    assert.deepEqual(exec(e2eCall(t, {})), {}, 'allow-listed host tool passes under the marker: ' + t);
  }
  const out = exec(e2eCall('mcp__ccd_settings__set_setting', {}));
  assert.equal(out.hookSpecificOutput.permissionDecision, 'deny', 'a host tool next to an allowed one is still denied under the marker');
  assert.ok(out.hookSpecificOutput.permissionDecisionReason.startsWith(REASON_PREFIX));
});

test('S10: Skill director off under the marker passes and removes the marker', () => {
  const { exec, plant, marker } = e2e();
  plant();
  assert.ok(existsSync(marker), 'marker planted');
  assert.deepEqual(exec(e2eCall('Skill', { skill: 'director', args: 'off' })), {});
  assert.ok(!existsSync(marker), 'marker removed');
});

// ---- S11: docs ----------------------------------------------------------------------------------------------

test('S11: director SKILL.md drops the "does not see other MCP servers" claim and names WebFetch and model-effort', () => {
  const doc = readFileSync(join(PLUGIN, 'skills', 'director', 'SKILL.md'), 'utf8');
  assert.ok(!doc.includes('does not see other MCP servers'));
  assert.ok(doc.includes('WebFetch'));
  assert.ok(doc.includes('model-effort'));
});

test('S11: the run-wave resumeFromRunId row says a director does not make the call and the coordinator does', () => {
  const doc = readFileSync(join(PLUGIN, 'skills', 'run-wave', 'SKILL.md'), 'utf8');
  const rows = doc.split(/\r?\n/).filter((l) => l.includes('resumeFromRunId'));
  assert.ok(rows.length >= 1, 'a row names resumeFromRunId');
  assert.ok(rows.some((l) => l.includes('director') && l.includes('coordinator')), 'a resumeFromRunId row names director and coordinator');
});

// ---- probes -------------------------------------------------------------------------------------------------

test('probe: the server token is case sensitive, so a differently cased TO key is a host server and denied', () => {
  denies(call('mcp__Task-Orchestrator__x', {}));
});

test('probe: degenerate host names deny (empty bare name, trailing separator, empty server)', () => {
  denies(call('mcp____x', {}));
  denies(call('mcp__ccd__start_session__', {}));
});

test('probe: Workflow scriptPath null versus absent are different states', () => {
  allows(call('Workflow', SAFE_WORKFLOW));
  denies(call('Workflow', { ...SAFE_WORKFLOW, scriptPath: null }));
});

test('probe: an empty skill name denies', () => {
  denies(call('Skill', { skill: '' }));
});

test('probe: unusable tool_input denies for each guarded tool, while an allow-listed host tool still allows', () => {
  for (const tool of ['mcp__terminal__read_terminal', 'WebFetch', CONFIG, RUNS_READ, 'Skill', 'Workflow']) {
    for (const ti of [null, 'x']) denies({ tool_name: tool, tool_input: ti, session_id: 's' }, `${tool} ${JSON.stringify(ti)}`);
  }
  for (const t of HOST_ALLOW_TOOLS) {
    for (const ti of [null, 'x']) allows({ tool_name: t, tool_input: ti, session_id: 's' }, `${t} ${JSON.stringify(ti)}`);
  }
});

test('probe: a repeated --json flag cannot smuggle comments past the allow-list, in any spelling', () => {
  assert.equal(bashAllowed('gh pr view 12 --json state --json comments'), false);
  assert.equal(bashAllowed('gh pr view 12 --json comments --json state'), false);
  assert.equal(bashAllowed('gh pr view 12 --json=state --json=comments'), false);
  assert.equal(bashAllowed('gh pr view 12 "--json" "comments"'), false);
  assert.equal(bashAllowed("gh pr view 12 '--json' 'comments'"), false);
  denies(cmd('gh pr view 12 --json state --json comments'));
});

test('probe: --json=a,b and --json a,b classify alike, for allowed and denied field lists', () => {
  for (const fields of ['state,body', 'number,title,url', 'state,comments', 'reviews']) {
    assert.equal(bashAllowed(`gh pr view 12 --json=${fields}`), bashAllowed(`gh pr view 12 --json ${fields}`), fields);
  }
  assert.equal(bashAllowed('gh pr view 12 --json=state,body'), true);
  assert.equal(bashAllowed('gh pr view 12 --json=state,comments'), false);
});

test('probe: replay of the same call gives the same decision', () => {
  for (const i of NEW_DENY_INPUTS) assert.equal(decide(i), decide(i));
  for (const t of HOST_ALLOW_TOOLS) assert.equal(decide(call(t, {})), decide(call(t, {})));
});
