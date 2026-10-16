// Contract tests for hooks/lib/server-compat.mjs and its session-start wiring (fc131c71, scenarios
// S17-S19). Oracle: the frozen task-scope signatures and decision D4. A server is compatible iff its
// `version` is a string whose leading integer (followed by . + - or end) is exactly 4 AND `features`
// is an array containing the exact string 'seats'; every other object doc yields one warning line.
// The well-known document shape is api-rest.md:870-886. Session-start is driven as a subprocess
// against a node:http fake.

import { test, after, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { assessServer, checkServer, legacyShapeWarningOnce } from '../lib/server-compat.mjs';
import { sessionMarkerPath, createMarkerOnce } from '../lib/markers.mjs';

const SESSION_START = fileURLToPath(new URL('../session-start.mjs', import.meta.url));

const trackedDirs = [];
function trackedTmp(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix));
  trackedDirs.push(d);
  return d;
}
after(() => {
  for (const d of trackedDirs) rmSync(d, { recursive: true, force: true });
});

function doc(version, features = ['seats', 'dispatchBySeat', 'independent_of', 'rules']) {
  const d = { name: 'mcp-task-orchestrator-current', apiVersion: 'v1', apiUrl: '/api/v1' };
  if (version !== undefined) d.version = version;
  if (features !== undefined) d.features = features;
  return d;
}

function assertOneLineWarning(w, label) {
  assert.equal(typeof w, 'string', label);
  assert.ok(w.length > 0, label);
  assert.ok(!w.includes('\n'), `${label}: must be a single line, got ${JSON.stringify(w)}`);
}

// ---- S17 assessServer ----

test('S17: compatible docs (major 4 with seats) -> null', () => {
  for (const v of ['4.0.0', '4.1.0-SNAPSHOT', '4', '4.0.0+build.5', '4-rc1', '4.99.99', '4.']) {
    assert.equal(assessServer(doc(v)), null, v);
  }
});

test('S17: features may contain other entries around seats and still be compatible', () => {
  assert.equal(assessServer(doc('4.0.0', ['rules', 'seats'])), null);
  assert.equal(assessServer(doc('4.0.0', ['seats'])), null);
});

test('S17: a 3.x server -> one line naming the reported version', () => {
  const w = assessServer(doc('3.16.0'));
  assertOneLineWarning(w, '3.16.0');
  assert.ok(w.includes('3.16.0'), w);
});

test('S17 probe: majors 40, 5, 0 and 14 and malformed prefixes are NOT compatible and name their version', () => {
  for (const v of ['40.0.0', '5.0.0', '0.4.0', '14.0.0', '4x', 'v4.0.0']) {
    const w = assessServer(doc(v));
    assertOneLineWarning(w, v);
    assert.ok(w.includes(v), v + ': ' + w);
  }
});

test('S17: a missing, unusable or non-string version -> warning naming "unknown"', () => {
  for (const d of [doc(undefined), doc('unknown'), doc(''), doc(4), doc(null), doc({ major: 4 }), doc(['4.0.0'])]) {
    const w = assessServer(d);
    assertOneLineWarning(w, JSON.stringify(d));
    assert.ok(w.includes('unknown'), `${JSON.stringify(d)}: ${w}`);
  }
});

test('S17: major 4 but features missing, empty, not an array, or lacking exact seats -> warning', () => {
  const bad = [[], 'seats', { seats: true }, ['Seats'], ['SEATS'], ['seats '], [' seats'], ['seat'], ['dispatchBySeat', 'rules'], null];
  for (const features of bad) {
    const w = assessServer(doc('4.0.0', features));
    assertOneLineWarning(w, JSON.stringify(features));
    assert.ok(w.includes('4.0.0'), `${JSON.stringify(features)}: ${w}`);
  }
  const noFeatures = doc('4.0.0');
  delete noFeatures.features;
  const w = assessServer(noFeatures);
  assertOneLineWarning(w, 'features key absent');
  assert.ok(w.includes('4.0.0'), w);
});

test('S17: a doc that is not an object -> null (nothing to assess)', () => {
  for (const d of [null, undefined, 'x', 5, true]) {
    assert.equal(assessServer(d), null, String(d));
  }
});

test('S17 probe: a server-controlled version containing a newline cannot break the one-line contract', () => {
  const w = assessServer(doc('3.0.0\nINJECTED LINE'));
  assertOneLineWarning(w, 'newline in version');
});

test('S17 probe: an empty object doc is incompatible (not silently fine)', () => {
  const w = assessServer({});
  assertOneLineWarning(w, '{}');
  assert.ok(w.includes('unknown'), w);
});

// ---- checkServer ----

function startFake(handler) {
  const hits = [];
  const server = createServer((req, res) => {
    hits.push({ method: req.method, url: req.url });
    handler(req, res);
  });
  return new Promise((resolveListen) => {
    server.listen(0, '127.0.0.1', () => resolveListen({ server, hits, base: `http://127.0.0.1:${server.address().port}` }));
  });
}
function stopFake(fake) {
  fake.server.closeAllConnections?.();
  return new Promise((res) => fake.server.close(res));
}
function wellKnownHandler(body, status = 200) {
  return (req, res) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(typeof body === 'string' ? body : JSON.stringify(body));
  };
}

test('checkServer: composes wellKnown and assessServer (3.x -> warning, 4.x+seats -> null)', async () => {
  const old = await startFake(wellKnownHandler(doc('3.16.0')));
  const cur = await startFake(wellKnownHandler(doc('4.0.0')));
  try {
    const warn = await checkServer({ baseUrl: old.base });
    assertOneLineWarning(warn, 'checkServer 3.16.0');
    assert.ok(warn.includes('3.16.0'), warn);
    assert.equal(await checkServer({ baseUrl: cur.base }), null);
    assert.equal(old.hits[0].url, '/.well-known/mcp-task-orchestrator.json');
  } finally {
    await stopFake(old);
    await stopFake(cur);
  }
});

test('checkServer: unreachable, 404 and non-JSON well-known documents -> null (no line, no throw)', async () => {
  const gone = await startFake(wellKnownHandler('{}', 404));
  const junk = await startFake(wellKnownHandler('<html>'));
  const closed = await startFake(() => {});
  const closedBase = closed.base;
  await stopFake(closed);
  try {
    assert.equal(await checkServer({ baseUrl: gone.base }), null);
    assert.equal(await checkServer({ baseUrl: junk.base }), null);
    assert.equal(await checkServer({ baseUrl: closedBase, timeoutMs: 1000 }), null);
  } finally {
    await stopFake(gone);
    await stopFake(junk);
  }
});

// ---- S18 legacyShapeWarningOnce ----

const TMP_VARS = ['TMPDIR', 'TEMP', 'TMP'];
let savedTmp;
let fakeTmp;
beforeEach(() => {
  savedTmp = {};
  for (const k of TMP_VARS) savedTmp[k] = process.env[k];
  fakeTmp = mkdtempSync(join(tmpdir(), 'to-lib-compat-'));
  for (const k of TMP_VARS) process.env[k] = fakeTmp;
});
afterEach(() => {
  for (const k of TMP_VARS) {
    if (savedTmp[k] === undefined) delete process.env[k];
    else process.env[k] = savedTmp[k];
  }
  rmSync(fakeTmp, { recursive: true, force: true });
});

test('S18: legacyShapeWarningOnce yields a one-line warning once per session, then null', () => {
  const first = legacyShapeWarningOnce('sess-A');
  assertOneLineWarning(first, 'first call');
  assert.equal(legacyShapeWarningOnce('sess-A'), null);
  assert.equal(legacyShapeWarningOnce('sess-A'), null);
});

test('S18: a different session gets its own first warning', () => {
  assertOneLineWarning(legacyShapeWarningOnce('sess-A'), 'A');
  assertOneLineWarning(legacyShapeWarningOnce('sess-B'), 'B');
  assert.equal(legacyShapeWarningOnce('sess-A'), null);
  assert.equal(legacyShapeWarningOnce('sess-B'), null);
});

test('S18 probe: a missing session id is the shared "unknown" session (warn once, then null)', () => {
  assertOneLineWarning(legacyShapeWarningOnce(undefined), 'undefined');
  assert.equal(legacyShapeWarningOnce(null), null);
  assert.equal(legacyShapeWarningOnce(''), null);
});

test('S18: the once-marker is the legacy-shape-warned session marker (a pre-existing one suppresses the warning)', () => {
  const marker = sessionMarkerPath('sess-pre', 'legacy-shape-warned');
  assert.ok(marker.startsWith(fakeTmp), marker);
  mkdirSync(join(marker, '..'), { recursive: true });
  assert.equal(createMarkerOnce(marker), true);
  assert.equal(legacyShapeWarningOnce('sess-pre'), null);
  // and a fresh session creates its own marker file
  assertOneLineWarning(legacyShapeWarningOnce('sess-new'), 'new');
  assert.ok(existsSync(sessionMarkerPath('sess-new', 'legacy-shape-warned')));
});

// ---- S19 session-start wiring (subprocess) ----

const COMPLIANT_STDIO = {
  mcpServers: {
    'mcp-task-orchestrator': { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] },
  },
};

function runSessionStart({ apiUrl, withRegistration = false }) {
  const dir = trackedTmp('to-compat-ss-');
  if (withRegistration) writeFileSync(join(dir, '.claude.json'), JSON.stringify(COMPLIANT_STDIO), 'utf-8');
  const toHome = trackedTmp('to-compat-ss-tohome-');
  const tmpDir = trackedTmp('to-compat-ss-tmp-');
  return new Promise((resolveRun) => {
    const child = spawn(process.execPath, [SESSION_START], {
      env: {
        ...process.env,
        AGENT_CONFIG_DIR: dir,
        HOME: dir,
        USERPROFILE: dir,
        CLAUDE_CONFIG_DIR: '',
        TASK_ORCHESTRATOR_HOME: toHome,
        TASK_ORCHESTRATOR_CEILING: dir,
        TEMP: tmpDir,
        TMP: tmpDir,
        TMPDIR: tmpDir,
        TASK_ORCHESTRATOR_API_URL: apiUrl ?? '',
        TASK_ORCHESTRATOR_API_TOKEN: '',
        TASK_ORCHESTRATOR_SETUP_HINT: '',
      },
      cwd: dir,
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (c) => { stdout += c; });
    child.stderr.on('data', (c) => { stderr += c; });
    const started = Date.now();
    const killTimer = setTimeout(() => child.kill('SIGKILL'), 20000);
    child.on('close', (status) => {
      clearTimeout(killTimer);
      resolveRun({ status, stdout, stderr, elapsed: Date.now() - started });
    });
    child.stdin.end();
  });
}

test('S19: a 3.x server -> session-start adds a systemMessage naming the version', async () => {
  const fake = await startFake(wellKnownHandler(doc('3.16.0')));
  try {
    const res = await runSessionStart({ apiUrl: fake.base });
    assert.equal(res.status, 0, res.stderr);
    const out = JSON.parse(res.stdout);
    assert.equal(typeof out.systemMessage, 'string');
    assert.ok(out.systemMessage.includes('3.16.0'), out.systemMessage);
    assert.ok(fake.hits.some((h) => h.url === '/.well-known/mcp-task-orchestrator.json'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Task Orchestrator'), 'the normal context is still emitted');
  } finally {
    await stopFake(fake);
  }
});

test('S19: a 4.0.0 server advertising seats -> no systemMessage at all', async () => {
  const fake = await startFake(wellKnownHandler(doc('4.0.0')));
  try {
    const res = await runSessionStart({ apiUrl: fake.base });
    assert.equal(res.status, 0, res.stderr);
    const out = JSON.parse(res.stdout);
    assert.ok(!('systemMessage' in out), JSON.stringify(out.systemMessage));
    assert.ok(fake.hits.some((h) => h.url === '/.well-known/mcp-task-orchestrator.json'), 'the compat check did run');
  } finally {
    await stopFake(fake);
  }
});

test('S19: a 4.0.0 server WITHOUT seats -> warning line', async () => {
  const fake = await startFake(wellKnownHandler(doc('4.0.0', ['rules'])));
  try {
    const res = await runSessionStart({ apiUrl: fake.base });
    const out = JSON.parse(res.stdout);
    assert.equal(typeof out.systemMessage, 'string');
    assert.ok(out.systemMessage.includes('4.0.0'), out.systemMessage);
  } finally {
    await stopFake(fake);
  }
});

test('S19: the warning is appended after the setup hint, joined by a single newline', async () => {
  const fake = await startFake(wellKnownHandler(doc('3.16.0')));
  try {
    const res = await runSessionStart({ apiUrl: fake.base, withRegistration: true });
    assert.equal(res.status, 0, res.stderr);
    const lines = JSON.parse(res.stdout).systemMessage.split('\n');
    assert.equal(lines.length, 2, JSON.stringify(lines));
    assert.ok(lines[0].includes('/task-orchestrator:init'), lines[0]);
    assert.ok(lines[1].includes('3.16.0'), lines[1]);
  } finally {
    await stopFake(fake);
  }
});

test('S19: an unreachable server or a non-JSON well-known document adds no line and still exits 0', async () => {
  const junk = await startFake(wellKnownHandler('<html>not json</html>'));
  const closed = await startFake(() => {});
  const closedBase = closed.base;
  await stopFake(closed);
  try {
    for (const apiUrl of [junk.base, closedBase]) {
      const res = await runSessionStart({ apiUrl });
      assert.equal(res.status, 0, res.stderr);
      assert.ok(!('systemMessage' in JSON.parse(res.stdout)), apiUrl);
    }
  } finally {
    await stopFake(junk);
  }
});

test('S19 probe: a server that never answers cannot hang session-start; no line is added', async () => {
  const fake = await startFake(() => { /* accept, never respond */ });
  try {
    const res = await runSessionStart({ apiUrl: fake.base });
    assert.equal(res.status, 0, res.stderr);
    assert.ok(res.elapsed < 15000, `took ${res.elapsed}ms`);
    assert.ok(!('systemMessage' in JSON.parse(res.stdout)));
  } finally {
    await stopFake(fake);
  }
});

test('S19: with no API URL anywhere there is no compat line', async () => {
  const res = await runSessionStart({ apiUrl: '' });
  assert.equal(res.status, 0, res.stderr);
  assert.ok(!('systemMessage' in JSON.parse(res.stdout)));
});
