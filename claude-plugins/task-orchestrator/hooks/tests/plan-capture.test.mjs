// plan-capture.mjs on the config locator: project scope only. Spawned as a subprocess against an
// in-process stub REST server. Hermeticity recipe (Ruling 2): every spawn pins
// TASK_ORCHESTRATOR_HOME to its OWN empty temp dir, drops inherited config/API env, and runs with
// cwd = a fixture dir (never the repo).

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const HOOK = fileURLToPath(new URL('../plan-capture.mjs', import.meta.url));
const ROOT_ID = '11111111-2222-3333-4444-555555555555';
const NL = String.fromCharCode(10);
const PROJECT_CONFIG = ['project:', `  rootId: "${ROOT_ID}"`, '  name: "X"', ''].join(NL);
const PLAN = { tool_input: { plan: '# My Great Plan' + NL + NL + 'body' + NL } };

const temps = [];
after(() => {
  for (const d of temps) rmSync(d, { recursive: true, force: true });
});
function mkTemp(prefix) {
  const d = mkdtempSync(join(tmpdir(), `pc-${prefix}-`));
  temps.push(d);
  return d;
}
function writeConfig(dir, text) {
  mkdirSync(join(dir, '.taskorchestrator'), { recursive: true });
  writeFileSync(join(dir, '.taskorchestrator', 'config.yaml'), text);
}

async function withStub(fn) {
  const requests = [];
  const server = createServer((req, res) => {
    requests.push({ method: req.method, url: req.url });
    req.resume();
    req.on('end', () => {
      res.statusCode = 200;
      res.end('{}');
    });
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}`;
  try {
    return await fn(url, requests);
  } finally {
    await new Promise((r) => server.close(r));
  }
}

function run({ cwd, home, url }) {
  return new Promise((resolveP) => {
    const env = { ...process.env, TASK_ORCHESTRATOR_HOME: home, TASK_ORCHESTRATOR_API_URL: url };
    for (const k of ['AGENT_CONFIG_DIR', 'TASK_ORCHESTRATOR_MODE', 'TASK_ORCHESTRATOR_API_TOKEN']) delete env[k];
    const child = spawn(process.execPath, [HOOK], { env, cwd });
    let stdout = '';
    child.stdout.on('data', (d) => { stdout += d; });
    child.on('close', (status) => resolveP({ status, stdout }));
    child.stdin.end(JSON.stringify(PLAN));
  });
}

test('project config with project.rootId -> exactly one PUT to /api/v1/roots/<rootId>/plans/<slug>', async () => {
  const cwd = mkTemp('proj');
  writeConfig(cwd, PROJECT_CONFIG);
  await withStub(async (url, requests) => {
    const res = await run({ cwd, home: mkTemp('home'), url });
    assert.equal(res.status, 0);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].method, 'PUT');
    assert.equal(requests[0].url, `/api/v1/roots/${ROOT_ID}/plans/my-great-plan`);
    const ctx = JSON.parse(res.stdout).hookSpecificOutput.additionalContext;
    assert.ok(ctx.includes('my-great-plan'), ctx);
  });
});

test('user scope (config only in pinned home) -> no request, silent, exit 0', async () => {
  const home = mkTemp('uhome');
  writeConfig(home, PROJECT_CONFIG);
  await withStub(async (url, requests) => {
    const res = await run({ cwd: mkTemp('empty'), home, url });
    assert.equal(res.status, 0);
    assert.equal(requests.length, 0);
    assert.equal(res.stdout, '');
  });
});

test('project config without a project block -> no request, silent', async () => {
  const cwd = mkTemp('noproj');
  writeConfig(cwd, 'retrospective:' + NL + '  mode: nudge' + NL);
  await withStub(async (url, requests) => {
    const res = await run({ cwd, home: mkTemp('home'), url });
    assert.equal(res.status, 0);
    assert.equal(requests.length, 0);
    assert.equal(res.stdout, '');
  });
});

test('inline project: { rootId: abc } is block-only -> no request, silent', async () => {
  const cwd = mkTemp('inline');
  writeConfig(cwd, 'project: { rootId: abc }' + NL);
  await withStub(async (url, requests) => {
    const res = await run({ cwd, home: mkTemp('home'), url });
    assert.equal(res.status, 0);
    assert.equal(requests.length, 0);
    assert.equal(res.stdout, '');
  });
});

test('no config anywhere -> no request, silent', async () => {
  await withStub(async (url, requests) => {
    const res = await run({ cwd: mkTemp('empty'), home: mkTemp('home'), url });
    assert.equal(res.status, 0);
    assert.equal(requests.length, 0);
    assert.equal(res.stdout, '');
  });
});

test('importing the module performs no stdin read and exports no config parser', async () => {
  const mod = await import('../plan-capture.mjs');
  assert.equal(mod.parseRootId, undefined);
});

// ---- 4e15b651 amendment A2: S47 hostile rootId never reaches a request path ----
// Oracle: task-scope A2.4/A2.5 (a rootId failing ^[A-Za-z0-9][A-Za-z0-9-]{0,63}$ is "no rootId": the hook
// returns before any request and prints nothing); a UUID then yields exactly the one PUT. Hermetic:
// cwd in a scratch tree, ceiling at its parent, home pinned to an empty dir, fake server on loopback.

function runWithCeiling({ cwd, ceiling, home, url }) {
  return new Promise((resolveP) => {
    const env = { ...process.env, TASK_ORCHESTRATOR_HOME: home, TASK_ORCHESTRATOR_API_URL: url, TASK_ORCHESTRATOR_CEILING: ceiling };
    for (const k of ['AGENT_CONFIG_DIR', 'TASK_ORCHESTRATOR_MODE', 'TASK_ORCHESTRATOR_API_TOKEN']) delete env[k];
    const child = spawn(process.execPath, [HOOK], { env, cwd });
    let stdout = '';
    child.stdout.on('data', (d) => { stdout += d; });
    child.on('close', (status) => resolveP({ status, stdout }));
    child.stdin.end(JSON.stringify(PLAN));
  });
}

test('4e15b651 S47: plan-capture sends nothing for a hostile rootId, then exactly one PUT for a UUID', async () => {
  const T = mkTemp('a2tree');
  const cwd = join(T, 'proj');
  const home = mkTemp('a2home');
  const UUID = '3f9a1c2e-5b7d-4e8f-9a0b-1c2d3e4f5a6b';
  writeConfig(cwd, ['project:', '  rootId: ../../../_cluster/settings?x=', '  name: "X"', ''].join(NL));
  await withStub(async (url, requests) => {
    const hostile = await runWithCeiling({ cwd, ceiling: T, home, url });
    assert.equal(hostile.status, 0);
    assert.equal(hostile.stdout, '');
    assert.equal(requests.length, 0, JSON.stringify(requests));
    writeConfig(cwd, ['project:', `  rootId: ${UUID}`, '  name: "X"', ''].join(NL));
    const ok = await runWithCeiling({ cwd, ceiling: T, home, url });
    assert.equal(ok.status, 0);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].method, 'PUT');
    assert.equal(requests[0].url, `/api/v1/roots/${UUID}/plans/my-great-plan`);
  });
});
