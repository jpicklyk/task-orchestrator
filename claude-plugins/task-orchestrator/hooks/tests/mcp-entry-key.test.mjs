// Item 331ff0a4: resolveOrchestratorEntryKey returns the registered orchestration entry key.
// Scenario ids S1-S4 match the item's frozen `test-plan` note. Oracles are the parent interface-contract
// item 1 (prefer mcp-task-orchestrator, else the first non-auxiliary key containing "task-orchestrator",
// else null), task-scope AC1/AC3 and planner decisions D1/D2 (auxiliary rows dropped first, the preferred
// key included; collect order is local, project, user) - never observed output.
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
const keyOf = (fx) => resolveOrchestratorEntryKey({ cwd: fx.W, env: fx.env });

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
