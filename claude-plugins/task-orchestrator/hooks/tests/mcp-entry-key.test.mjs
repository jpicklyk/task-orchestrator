// Item 331ff0a4: resolveOrchestratorEntryKey returns the registered orchestration entry key.
// Scenario ids S1-S4 match the item's frozen `test-plan` note. Oracles are the parent interface-contract
// item 1 (prefer mcp-task-orchestrator, else the first non-auxiliary key containing "task-orchestrator",
// else null), task-scope AC1/AC3 and planner decisions D1/D2 (auxiliary rows dropped first, the preferred
// key included; collect order is local, project, user) - never observed output.
// S14 (fix cycle A1, review finding O1; oracle: `amendments` ruling R3 and the AC3/D2 rule that an auxiliary
// registration is never the orchestration entry): a url in `${VAR}` form that names /mcp/director or
// /mcp/knowledge is excluded whether or not the variable is set - the url is env-expanded first, and one
// that still does not parse as an http(s) URL is read as text (cut at the first ? or #, trailing slashes
// dropped, matched on its /mcp/director or /mcp/knowledge ending).
// Hermetic: every case passes an explicit cwd and env pointing at fresh temp dirs.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { resolveOrchestratorEntryKey, collectOrchestratorRegistrations } from '../lib/mcp-registrations.mjs';

const temps = [];
after(() => {
  for (const d of temps) rmSync(d, { recursive: true, force: true });
});

/** Fixture: W is the workspace (cwd and the local-scope project key), C = CLAUDE_CONFIG_DIR. */
function fixture() {
  const T = mkdtempSync(join(tmpdir(), 'to-entrykey-'));
  temps.push(T);
  const W = join(T, 'ws');
  const C = join(T, 'claude');
  const H = join(T, 'home');
  for (const d of [W, C, H]) mkdirSync(d, { recursive: true });
  const env = { CLAUDE_CONFIG_DIR: C, TASK_ORCHESTRATOR_HOME: H, TASK_ORCHESTRATOR_CEILING: T };
  return { T, W, C, env };
}
const http = (url) => ({ type: 'http', url });
const writeUser = (fx, servers, local) =>
  writeFileSync(
    join(fx.C, '.claude.json'),
    JSON.stringify({ mcpServers: servers, ...(local ? { projects: { [fx.W]: { mcpServers: local } } } : {}) })
  );
const writeProject = (fx, servers) => writeFileSync(join(fx.W, '.mcp.json'), JSON.stringify({ mcpServers: servers }));
// `vars` are extra environment members (the ${VAR} forms of a url expand from them), absent unless given.
const keyOf = (fx, vars = {}) => resolveOrchestratorEntryKey({ cwd: fx.W, env: { ...fx.env, ...vars } });
const rowsOf = (fx, vars = {}) => collectOrchestratorRegistrations({ cwd: fx.W, env: { ...fx.env, ...vars } });

test('S1: a single user entry under another key containing task-orchestrator is returned', () => {
  const fx = fixture();
  writeUser(fx, { 'task-orchestrator-alt': http('http://localhost:3001/mcp') });
  assert.equal(keyOf(fx), 'task-orchestrator-alt');
});

test('S2: the preferred key mcp-task-orchestrator wins over an earlier-scope alternative', () => {
  const fx = fixture();
  writeUser(
    fx,
    { 'mcp-task-orchestrator': http('http://localhost:3001/mcp') },
    { 'task-orchestrator-alt': http('http://localhost:3002/mcp') }
  );
  assert.equal(keyOf(fx), 'mcp-task-orchestrator');
});

test('S2 probe: one key present in two scopes is returned once as that key', () => {
  const fx = fixture();
  writeUser(fx, { 'mcp-task-orchestrator': http('http://localhost:3001/mcp') });
  writeProject(fx, { 'mcp-task-orchestrator': http('http://localhost:3002/mcp') });
  assert.equal(keyOf(fx), 'mcp-task-orchestrator');
});

test('S2 probe: without the preferred key the first remaining row in collect order (local, project, user) wins', () => {
  const fx = fixture();
  writeUser(fx, { 'task-orchestrator-user': http('http://localhost:3003/mcp') }, { 'task-orchestrator-local': http('http://localhost:3001/mcp') });
  writeProject(fx, { 'task-orchestrator-project': http('http://localhost:3002/mcp') });
  assert.equal(keyOf(fx), 'task-orchestrator-local');
  const fx2 = fixture();
  writeUser(fx2, { 'task-orchestrator-user': http('http://localhost:3003/mcp') });
  writeProject(fx2, { 'task-orchestrator-project': http('http://localhost:3002/mcp') });
  assert.equal(keyOf(fx2), 'task-orchestrator-project');
});

test('S3 (AC3): only director or knowledge registrations - by url, trailing slash, args pair, env member - give null', () => {
  const fx = fixture();
  writeUser(
    fx,
    {
      'task-orchestrator-director': http('http://localhost:3001/mcp/director'),
      'task-orchestrator-knowledge': http('http://localhost:3001/mcp/knowledge/'),
      'task-orchestrator-dir-slash': http('http://localhost:3001/mcp/director/'),
      'task-orchestrator-stdio-arg': { command: 'docker', args: ['run', '-e', 'MCP_TOOL_PROFILE=director', 'ghcr.io/example/task-orchestrator'] },
      'task-orchestrator-stdio-env': { command: 'docker', args: ['run', 'ghcr.io/example/task-orchestrator'], env: { MCP_TOOL_PROFILE: 'knowledge' } },
      'task-orchestrator-joined': { command: 'docker', args: ['run', '--env=MCP_TOOL_PROFILE=knowledge', 'ghcr.io/example/task-orchestrator'] },
    }
  );
  assert.equal(keyOf(fx), null);
});

test('S3 (AC3): a non-auxiliary entry beside auxiliary ones is the one returned', () => {
  const fx = fixture();
  writeUser(fx, {
    'task-orchestrator-director': http('http://localhost:3001/mcp/director'),
    'task-orchestrator-main': http('http://localhost:3001/mcp'),
  });
  assert.equal(keyOf(fx), 'task-orchestrator-main');
});

test('S3 (D2): the preferred key is dropped when it is itself auxiliary', () => {
  const fx = fixture();
  writeUser(fx, {
    'mcp-task-orchestrator': http('http://localhost:3001/mcp/director'),
    'task-orchestrator-alt': http('http://localhost:3001/mcp'),
  });
  assert.equal(keyOf(fx), 'task-orchestrator-alt');
  const fx2 = fixture();
  writeUser(fx2, { 'mcp-task-orchestrator': { command: 'docker', args: ['run', '-e', 'MCP_TOOL_PROFILE=director', 'img'] } });
  assert.equal(keyOf(fx2), null);
});

test('S3 probe: a profile that is not director or knowledge does not exclude the entry', () => {
  const fx = fixture();
  writeUser(fx, { 'task-orchestrator-stdio': { command: 'docker', args: ['run', '-e', 'MCP_TOOL_PROFILE=orchestration', 'img'] } });
  assert.equal(keyOf(fx), 'task-orchestrator-stdio');
});

test('S3: collected rows carry profile director or knowledge for an args/env profile and null for a plain stdio entry', () => {
  const fx = fixture();
  writeUser(fx, {
    'task-orchestrator-d': { command: 'docker', args: ['run', '-e', 'MCP_TOOL_PROFILE=director', 'img'] },
    'task-orchestrator-k': { command: 'docker', args: ['run', 'img'], env: { MCP_TOOL_PROFILE: 'knowledge' } },
    'task-orchestrator-plain': { command: 'docker', args: ['run', 'img'] },
  });
  const rows = collectOrchestratorRegistrations({ cwd: fx.W, env: fx.env });
  const profileOf = (key) => rows.find((r) => r.key === key)?.profile;
  assert.equal(profileOf('task-orchestrator-d'), 'director');
  assert.equal(profileOf('task-orchestrator-k'), 'knowledge');
  assert.equal(profileOf('task-orchestrator-plain'), null);
});

test('S4: a stdio entry (command and image arg, no url) is returned by its key', () => {
  const fx = fixture();
  writeUser(fx, { 'task-orchestrator-stdio': { command: 'docker', args: ['run', '--rm', '-i', 'ghcr.io/example/task-orchestrator'] } });
  assert.equal(keyOf(fx), 'task-orchestrator-stdio');
});

test('S4 (D2): a key without the task-orchestrator token is never returned, even with a task-orchestrator url or image', () => {
  const fx = fixture();
  writeUser(fx, {
    to: http('http://localhost:3001/task-orchestrator/mcp'),
    other: { command: 'docker', args: ['run', 'ghcr.io/example/task-orchestrator'] },
  });
  assert.equal(keyOf(fx), null);
});

test('S4: no configuration at all gives null and does not throw; a malformed .claude.json also gives null', () => {
  const fx = fixture();
  assert.equal(keyOf(fx), null);
  writeFileSync(join(fx.C, '.claude.json'), '{ not json');
  assert.equal(keyOf(fx), null);
});

// ---- S14: env-form urls (${VAR}) naming an auxiliary path (R3, O1) ----

const TO_URL = 'http://localhost:3001';
const TO_PORT = '3001';
/** Every case runs with the variable unset and set as two tests; the answer must not depend on it. */
const URL_VARS = [['unset', {}], ['set', { TO_URL, TO_PORT }]];
/** ids[0] names the unset test, ids[1] the set one (they are the same id when the scenario does not tell them apart). */
function perState(ids, title, body) {
  URL_VARS.forEach(([label, vars], i) => test(`${ids[i]} [env ${label}]: ${title}`, () => body(vars, label)));
}
const profileOfRow = (fx, vars, key) => rowsOf(fx, vars).find((r) => r.key === key)?.profile;

perState(['S14a', 'S14b'], 'an entry whose only url is ${TO_URL}/mcp/director is auxiliary: null, and its row profile is director', (vars) => {
  const fx = fixture();
  writeUser(fx, { 'task-orchestrator-director': http('${TO_URL}/mcp/director') });
  assert.equal(keyOf(fx, vars), null);
  assert.equal(profileOfRow(fx, vars, 'task-orchestrator-director'), 'director');
});

perState(['S14c', 'S14c'], '${TO_URL:-http://localhost:3001}/mcp/knowledge/ is auxiliary: null, and its row profile is knowledge', (vars) => {
  const fx = fixture();
  writeUser(fx, { 'task-orchestrator-knowledge': http('${TO_URL:-http://localhost:3001}/mcp/knowledge/') });
  assert.equal(keyOf(fx, vars), null);
  assert.equal(profileOfRow(fx, vars, 'task-orchestrator-knowledge'), 'knowledge');
});

perState(['S14d', 'S14d'], 'the preferred key mcp-task-orchestrator at ${TO_URL}/mcp/director is dropped, so task-orchestrator-main at ${TO_URL}/mcp is returned', (vars) => {
  const fx = fixture();
  writeUser(fx, {
    'mcp-task-orchestrator': http('${TO_URL}/mcp/director'),
    'task-orchestrator-main': http('${TO_URL}/mcp'),
  });
  assert.equal(keyOf(fx, vars), 'task-orchestrator-main');
});

perState(['S14d probe', 'S14d probe'], 'an orchestration preferred key at ${TO_URL}/mcp still wins over a ${TO_URL}/mcp/director entry', (vars) => {
  const fx = fixture();
  writeUser(fx, {
    'task-orchestrator-director': http('${TO_URL}/mcp/director'),
    'mcp-task-orchestrator': http('${TO_URL}/mcp'),
  });
  assert.equal(keyOf(fx, vars), 'mcp-task-orchestrator');
});

perState(['S14e', 'S14e'], 'an entry at ${TO_URL}/mcp is an orchestration entry: returned by its key, row profile null', (vars) => {
  const fx = fixture();
  writeUser(fx, { 'task-orchestrator-alt': http('${TO_URL}/mcp') });
  assert.equal(keyOf(fx, vars), 'task-orchestrator-alt');
  assert.equal(profileOfRow(fx, vars, 'task-orchestrator-alt'), null);
});

perState(['S14f1', 'S14f1'], '/mcp/directory is not /mcp/director: a literal url and a ${TO_URL} url both give an orchestration row', (vars) => {
  const fx = fixture();
  writeUser(fx, {
    'task-orchestrator-lit': http('http://localhost:3001/mcp/directory'),
    'task-orchestrator-var': http('${TO_URL}/mcp/directory'),
  });
  assert.equal(profileOfRow(fx, vars, 'task-orchestrator-lit'), null);
  assert.equal(profileOfRow(fx, vars, 'task-orchestrator-var'), null);
  const fx2 = fixture();
  writeUser(fx2, { 'task-orchestrator-var': http('${TO_URL}/mcp/directory') });
  assert.equal(keyOf(fx2, vars), 'task-orchestrator-var');
});

perState(['S14f2', 'S14f2'], 'a query after the auxiliary path does not hide it: ${TO_URL}/mcp/director?x=1 gives null', (vars) => {
  const fx = fixture();
  writeUser(fx, { 'task-orchestrator-aux': http('${TO_URL}/mcp/director?x=1') });
  assert.equal(keyOf(fx, vars), null);
});

perState(['S14f3', 'S14f3'], 'a fragment after the auxiliary path does not hide it: ${TO_URL}/mcp/knowledge#top gives null', (vars) => {
  const fx = fixture();
  writeUser(fx, { 'task-orchestrator-aux': http('${TO_URL}/mcp/knowledge#top') });
  assert.equal(keyOf(fx, vars), null);
});

perState(['S14g', 'S14g'], 'http://localhost:${TO_PORT}/mcp/director is auxiliary: null, row profile director', (vars) => {
  const fx = fixture();
  writeUser(fx, { 'task-orchestrator-director': http('http://localhost:${TO_PORT}/mcp/director') });
  assert.equal(keyOf(fx, vars), null);
  assert.equal(profileOfRow(fx, vars, 'task-orchestrator-director'), 'director');
});

perState(['S14 probe', 'S14 probe'], 'surrounding whitespace and trailing slashes are ignored in an env-form auxiliary url', (vars) => {
  for (const url of ['  ${TO_URL}/mcp/director  ', '${TO_URL}/mcp/knowledge/', '${TO_URL}/mcp/director//']) {
    const fx = fixture();
    writeUser(fx, { 'task-orchestrator-aux': http(url) });
    assert.equal(keyOf(fx, vars), null, JSON.stringify(url));
  }
});

perState(['S14 probe', 'S14 probe'], 'an auxiliary path that is not the ending of an env-form url, or sits inside its query or fragment, does not exclude it', (vars) => {
  const cases = [
    ['${TO_URL}/mcp/director/extra', 'task-orchestrator-extra'],
    ['${TO_URL}/mcp?next=/mcp/director', 'task-orchestrator-query'],
    ['${TO_URL}/mcp#/mcp/knowledge', 'task-orchestrator-fragment'],
  ];
  for (const [url, key] of cases) {
    const fx = fixture();
    writeUser(fx, { [key]: http(url) });
    assert.equal(keyOf(fx, vars), key, url);
  }
});
