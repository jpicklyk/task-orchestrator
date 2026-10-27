// bc3e3c99: the hooks derive the REST base URL from the orchestrator MCP registration.
// Covers apiBaseUrlInfo / apiBaseUrl ordering (env > project client.json > registration > user client.json),
// scope precedence (local > project > user), key preference, ${VAR} expansion, loopback and credential rules
// for the registration fallback, and the unpinned repo settings.json. Hermetic: every case passes an explicit
// cwd and env pointing at fresh temp dirs (CLAUDE_CONFIG_DIR, TASK_ORCHESTRATOR_HOME), so neither the real
// ~/.claude.json nor any .mcp.json above the repo is read.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { apiBaseUrl, apiBaseUrlInfo } from '../lib/api-client.mjs';
import { preferredHttpRegistration } from '../lib/mcp-registrations.mjs';

const temps = [];
after(() => {
  for (const d of temps) rmSync(d, { recursive: true, force: true });
});

/** Fresh fixture: W (workspace root, has the .mcp.json), cwd = W/sub, C = CLAUDE_CONFIG_DIR, H = TASK_ORCHESTRATOR_HOME. */
function fixture() {
  const T = mkdtempSync(join(tmpdir(), 'to-mcpreg-'));
  temps.push(T);
  const W = join(T, 'ws');
  const cwd = join(W, 'sub');
  const C = join(T, 'claude');
  const H = join(T, 'home');
  for (const d of [cwd, C, H]) mkdirSync(d, { recursive: true });
  const env = { CLAUDE_CONFIG_DIR: C, TASK_ORCHESTRATOR_HOME: H, TASK_ORCHESTRATOR_CEILING: T };
  return { T, W, cwd, C, H, env };
}
const writeMcp = (fx, servers) => writeFileSync(join(fx.W, '.mcp.json'), JSON.stringify({ mcpServers: servers }));
const writeClaude = (fx, obj) => writeFileSync(join(fx.C, '.claude.json'), JSON.stringify(obj));
const http = (url) => ({ type: 'http', url });
const info = (fx, extraEnv = {}) => apiBaseUrlInfo({ cwd: fx.cwd, env: { ...fx.env, ...extraEnv } });

test('AC1: env wins over every registration', () => {
  const fx = fixture();
  writeMcp(fx, { 'mcp-task-orchestrator': http('http://localhost:3003/mcp') });
  assert.deepEqual(info(fx, { TASK_ORCHESTRATOR_API_URL: 'http://x.example:9/' }), { url: 'http://x.example:9', source: 'env' });
});

test('AC2: a loopback project client.json beats a registration', () => {
  const fx = fixture();
  mkdirSync(join(fx.W, '.taskorchestrator'), { recursive: true });
  writeFileSync(join(fx.W, '.taskorchestrator', 'config.yaml'), 'project:\n  rootId: 11111111-2222-3333-4444-555555555555\n');
  writeFileSync(join(fx.W, '.taskorchestrator', 'client.json'), JSON.stringify({ apiUrl: 'http://localhost:3001' }));
  writeMcp(fx, { 'mcp-task-orchestrator': http('http://localhost:3003/mcp') });
  assert.deepEqual(info(fx), { url: 'http://localhost:3001', source: 'project-client' });
});

test('AC3: no env/client.json + ancestor .mcp.json -> the origin of the registration', () => {
  const fx = fixture();
  writeMcp(fx, { 'mcp-task-orchestrator': http('http://localhost:3003/mcp') });
  assert.deepEqual(info(fx), { url: 'http://localhost:3003', source: 'mcp:mcp-task-orchestrator@project' });
  assert.equal(apiBaseUrl({ cwd: fx.cwd, env: fx.env }), 'http://localhost:3003');
});

test('the registration beats the user-level client.json', () => {
  const fx = fixture();
  mkdirSync(join(fx.H, '.taskorchestrator'), { recursive: true });
  writeFileSync(join(fx.H, '.taskorchestrator', 'client.json'), JSON.stringify({ apiUrl: 'http://user.example:1' }));
  assert.deepEqual(info(fx), { url: 'http://user.example:1', source: 'user-client' });
  writeMcp(fx, { 'mcp-task-orchestrator': http('http://localhost:3003/mcp') });
  assert.equal(info(fx).url, 'http://localhost:3003');
});

test('AC4: a non-loopback .mcp.json url is ignored (falls through to nothing)', () => {
  const fx = fixture();
  writeMcp(fx, { 'mcp-task-orchestrator': http('https://evil.example/mcp') });
  assert.equal(info(fx), null);
  assert.equal(apiBaseUrl({ cwd: fx.cwd, env: fx.env }), null);
});

test('AC5: local beats project beats user; ~/.claude.json may be remote', () => {
  const fx = fixture();
  writeMcp(fx, { 'mcp-task-orchestrator': http('http://localhost:3003/mcp') });
  writeClaude(fx, { mcpServers: { 'mcp-task-orchestrator': http('https://user.example/mcp') } });
  assert.deepEqual(info(fx), { url: 'http://localhost:3003', source: 'mcp:mcp-task-orchestrator@project' });
  writeClaude(fx, {
    mcpServers: { 'mcp-task-orchestrator': http('https://user.example/mcp') },
    projects: { [fx.W]: { mcpServers: { 'mcp-task-orchestrator': http('https://local.example:8443/mcp') } } },
  });
  assert.deepEqual(info(fx), { url: 'https://local.example:8443', source: 'mcp:mcp-task-orchestrator@local' });
  writeMcp(fx, {});
  writeClaude(fx, { mcpServers: { 'mcp-task-orchestrator': http('https://user.example/mcp') } });
  assert.deepEqual(info(fx), { url: 'https://user.example', source: 'mcp:mcp-task-orchestrator@user' });
});

test('AC5: a url carrying credentials is rejected', () => {
  const fx = fixture();
  writeClaude(fx, { mcpServers: { 'mcp-task-orchestrator': http('https://u:p@user.example/mcp') } });
  assert.equal(info(fx), null);
});

test('AC6: key preference, stdio ignored, non-token keys ignored', () => {
  const fx = fixture();
  writeMcp(fx, {
    'mcp-task-orchestrator-director': http('http://localhost:3004/mcp/director'),
    'task-orchestrator-alt': http('http://localhost:3005/mcp'),
    'mcp-task-orchestrator': http('http://localhost:3003/mcp'),
  });
  assert.equal(info(fx).url, 'http://localhost:3003');
  // Without the preferred key: first whose path is not /mcp/director or /mcp/knowledge.
  writeMcp(fx, {
    'mcp-task-orchestrator-director': http('http://localhost:3004/mcp/director'),
    'task-orchestrator-knowledge': http('http://localhost:3006/mcp/knowledge'),
    'task-orchestrator-alt': http('http://localhost:3005/mcp'),
  });
  assert.equal(info(fx).url, 'http://localhost:3005');
  // Only auxiliary endpoints: the first one.
  writeMcp(fx, { 'mcp-task-orchestrator-director': http('http://localhost:3004/mcp/director') });
  assert.equal(info(fx).url, 'http://localhost:3004');
  // stdio (command, no url) and keys without the token are ignored.
  writeMcp(fx, {
    'mcp-task-orchestrator': { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator'] },
    other: http('http://localhost:3999/mcp'),
  });
  assert.equal(info(fx), null);
});

test('AC6: ${VAR} and ${VAR:-default} expand from env; an unresolved variable skips the entry', () => {
  const fx = fixture();
  writeMcp(fx, { 'mcp-task-orchestrator': http('http://localhost:${TO_PORT}/mcp') });
  assert.equal(info(fx, { TO_PORT: '3011' }).url, 'http://localhost:3011');
  assert.equal(info(fx), null);
  writeMcp(fx, { 'mcp-task-orchestrator': http('http://localhost:${TO_PORT:-3012}/mcp') });
  assert.equal(info(fx).url, 'http://localhost:3012');
  assert.equal(info(fx, { TO_PORT: '3013' }).url, 'http://localhost:3013');
});

test('preferredHttpRegistration never throws on garbage input', () => {
  assert.equal(preferredHttpRegistration({ cwd: 12345, env: null }), null);
});

test('AC9: the repo .claude/settings.json no longer pins TASK_ORCHESTRATOR_API_URL', () => {
  const settings = resolve(fileURLToPath(new URL('../../../../.claude/settings.json', import.meta.url)));
  const parsed = JSON.parse(readFileSync(settings, 'utf-8'));
  assert.ok(!parsed.env || !('TASK_ORCHESTRATOR_API_URL' in parsed.env));
});
