// Tests for item 6a0823e9: Artifact, ArtifactData, ArtifactComments, TaskOutput and LSP are content tools
// and must be denied under the director marker (scenarios S1-S7 and probes).
// BLIND author: written from the frozen test-plan note, the supplied declarations and the existing director-guard
// tests. Oracles: O1 CLAUDE.md altitude rule (the director never opens a file or reads test output), O2 the
// diagnosis decisions D1-D3 (D1 group-1 matcher, D2 whole-tool deny of the nine content tools), O3 the guard
// contract (subagents exempt, headless never guarded, fail closed under the marker), O4 Claude Code matcher
// semantics (a matcher of letters and | is an exact-name list).

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

import { directorMarkerPath } from '../execution-mode.mjs';
import { REASON_PREFIX, CONTENT_TOOLS, guardDecision } from '../director-guard.mjs';

const HOOK = fileURLToPath(new URL('../director-guard.mjs', import.meta.url));
const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/]$/, '');
const HOOKS_CONFIG_PATH = join(PLUGIN, 'hooks', 'hooks-config.json');

const NINE = ['WebFetch', 'WebSearch', 'ReadMcpResourceTool', 'ReadMcpResourceDirTool', 'Artifact', 'ArtifactData', 'ArtifactComments', 'TaskOutput', 'LSP'];
const NEW_FIVE = ['Artifact', 'ArtifactData', 'ArtifactComments', 'TaskOutput', 'LSP'];

const tracked = [];
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});

const call = (tool_name, tool_input = {}, extra = {}) => ({ tool_name, tool_input, session_id: 's', ...extra });
const decide = (input, { env = {}, director = true } = {}) => guardDecision(input, { env, director });
const isDeny = (r) => typeof r === 'string' && r.startsWith(REASON_PREFIX);
const denies = (input, label) => assert.ok(isDeny(decide(input)), `${label ?? JSON.stringify(input)} should deny, got ${JSON.stringify(decide(input))}`);
const matcherRe = (m) => new RegExp('^(?:' + m + ')$');

function group1Matcher() {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG_PATH, 'utf8'));
  const g = cfg.hooks.PreToolUse.filter((x) => !x.matcher.startsWith('^mcp__') && x.hooks.some((h) => String(h.command).includes('director-guard.mjs')));
  assert.equal(g.length, 1, 'exactly one non-mcp director-guard group');
  return g[0].matcher;
}

const S1_INPUTS = [
  call('Artifact', { action: 'publish', file_path: 'README.md' }),
  call('Artifact', { action: 'read', url: 'https://claude.ai/artifact/x' }),
  call('Artifact', { action: 'list' }),
  call('ArtifactData', { action: 'read', url: 'https://claude.ai/artifact/x' }),
  call('ArtifactComments', { action: 'read', url: 'https://claude.ai/artifact/x' }),
  call('TaskOutput', { task_id: 't1' }),
  call('LSP', { operation: 'hover', filePath: 'a.kt', line: 1, character: 1 }),
];

test('S1: Artifact, ArtifactData, ArtifactComments, TaskOutput and LSP calls are denied under the marker', () => {
  for (const i of S1_INPUTS) denies(i, i.tool_name + ' ' + JSON.stringify(i.tool_input));
});

test('S2: the group-1 matcher covers the five new tools, still Read and REPL, and not SendMessage, ListAgents, TaskStop, ToolSearch', () => {
  const m = group1Matcher();
  assert.equal(m, "Skill|Read|Edit|Write|Bash|PowerShell|Monitor|Agent|Grep|Glob|NotebookEdit|Workflow|WebFetch|WebSearch|ReadMcpResourceTool|ReadMcpResourceDirTool|Artifact|ArtifactData|ArtifactComments|TaskOutput|LSP|REPL");
  const re = matcherRe(m);
  for (const t of [...NEW_FIVE, 'Read', 'REPL']) assert.ok(re.test(t), t + ' should match');
  for (const t of ['SendMessage', 'ListAgents', 'TaskStop', 'ToolSearch']) assert.ok(!re.test(t), t + ' should not match');
});

test('S3: CONTENT_TOOLS is frozen, holds the nine names, and each is matched by group 1 and denies with {} input', () => {
  assert.ok(Object.isFrozen(CONTENT_TOOLS));
  assert.deepEqual([...CONTENT_TOOLS].sort(), [...NINE].sort());
  const re = matcherRe(group1Matcher());
  for (const t of CONTENT_TOOLS) {
    assert.ok(re.test(t), t + ' must be matched by the group-1 matcher');
    denies(call(t, {}), t + ' with {}');
  }
});

test('S4: the S1 inputs pass with no marker, from a subagent and under the headless env', () => {
  for (const i of S1_INPUTS) {
    assert.equal(decide(i, { director: false }), null, 'no marker ' + i.tool_name);
    assert.equal(decide({ ...i, agent_id: 'a1' }), null, 'subagent ' + i.tool_name);
    assert.equal(decide(i, { env: { TASK_ORCHESTRATOR_MODE: 'headless-iteration' } }), null, 'headless ' + i.tool_name);
  }
});

test('S5: a non-object tool_input still denies under the marker (fail closed)', () => {
  denies(call('Artifact', 'x'), 'Artifact with string input');
  denies(call('TaskOutput', null), 'TaskOutput with null input');
});

function e2e() {
  const tmpDir = mkdtempSync(join(tmpdir(), 'to-dct-tmp-'));
  const cwd = mkdtempSync(join(tmpdir(), 'to-dct-cwd-'));
  tracked.push(tmpDir, cwd);
  const saved = {};
  for (const k of ['TMPDIR', 'TEMP', 'TMP']) {
    saved[k] = process.env[k];
    process.env[k] = tmpDir;
  }
  let marker;
  try {
    marker = directorMarkerPath('ct-session');
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

test('S6: through the hook process, Artifact publish emits a deny under the marker and {} without it', () => {
  const { exec, plant } = e2e();
  const input = { tool_name: 'Artifact', tool_input: { action: 'publish', file_path: 'README.md' }, session_id: 'ct-session' };
  assert.deepEqual(exec(input), {}, 'control, no marker');
  plant();
  const out = exec(input);
  assert.equal(out.hookSpecificOutput.hookEventName, 'PreToolUse');
  assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  assert.ok(out.hookSpecificOutput.permissionDecisionReason.startsWith(REASON_PREFIX));
});

test('S7: TaskStop and ToolSearch still pass under the marker and WebFetch still denies', () => {
  assert.equal(decide(call('TaskStop', { task_id: 't1' })), null);
  assert.equal(decide(call('ToolSearch', { query: 'x' })), null);
  denies(call('WebFetch', {}), 'WebFetch');
});

// ---- probes ------------------------------------------------------------------------------------

test('probe: lowercase and trailing-space names are not host tool names and the matcher is exact (recorded only)', () => {
  const re = matcherRe(group1Matcher());
  for (const t of ['artifact', 'lsp', 'Artifact ', 'taskoutput']) assert.ok(!re.test(t), JSON.stringify(t) + ' should not match');
});

test('probe: an MCP-namespaced Artifact is denied under the marker', () => {
  denies(call('mcp__x__Artifact', {}));
});

test('probe: describe:true and extra tool_input keys do not open Artifact', () => {
  denies(call('Artifact', { describe: true }), 'describe:true');
  denies(call('Artifact', { action: 'list', extra: 1, another: { a: 2 } }), 'extra keys');
});
