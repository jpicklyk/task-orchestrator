// Direct unit coverage for api-client.mjs: apiBaseUrl (trailing-slash stripping, unset -> null),
// authHeader (Authorization iff token set), and fetchWithTimeout (aborts against a server that
// never responds; sends through caller-supplied headers). Every export here is a pure function
// or a promise this test awaits directly, in-process — no subprocess spawning needed, since
// nothing reads stdin or touches disk state.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { apiBaseUrl, authHeader, fetchWithTimeout, isLoopbackApiUrl } from '../api-client.mjs';
import { userClientPath } from '../config-locator.mjs';

function withEnv(callerVars, fn) {
  // 4e15b651 DE1: hermetic defaults under the caller's vars (caller's keys win) - with cwd equal to
  // the ceiling the locator examines nothing, so a repo-level client.json cannot leak into any case.
  const vars = { TASK_ORCHESTRATOR_CEILING: process.cwd(), AGENT_CONFIG_DIR: undefined, ...callerVars };
  const saved = {};
  for (const key of Object.keys(vars)) saved[key] = process.env[key];
  try {
    for (const [key, value] of Object.entries(vars)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    return fn();
  } finally {
    for (const [key, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
}

function startServer(handler) {
  return new Promise((resolveListen) => {
    const server = createServer(handler);
    server.listen(0, '127.0.0.1', () => resolveListen(server));
  });
}

function stopServer(server) {
  return new Promise((res) => server.close(res));
}

// Home pinned to a fresh empty temp dir so a real ~/.taskorchestrator/client.json cannot leak in.
function emptyHome() {
  return mkdtempSync(join(tmpdir(), 'toapi-home-'));
}

function homeWithClientJson(content) {
  const home = emptyHome();
  mkdirSync(join(home, '.taskorchestrator'), { recursive: true });
  if (content !== undefined) writeFileSync(join(home, '.taskorchestrator', 'client.json'), content);
  return home;
}

function cleanup(dir) {
  try { rmSync(dir, { recursive: true, force: true }); } catch { /* best effort */ }
}

test('apiBaseUrl: null when TASK_ORCHESTRATOR_API_URL is unset', () => {
  const home = emptyHome();
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.equal(apiBaseUrl(), null);
    });
  } finally {
    cleanup(home);
  }
});

test('apiBaseUrl: null when TASK_ORCHESTRATOR_API_URL is empty', () => {
  const home = emptyHome();
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: '', TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.equal(apiBaseUrl(), null);
    });
  } finally {
    cleanup(home);
  }
});

// ---- client.json fallback (oracle: task-scope note, section 2) ----

test('S14: env URL beats client.json', () => {
  const home = homeWithClientJson(JSON.stringify({ apiUrl: 'http://file:1' }));
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: 'http://env:2/', TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.equal(apiBaseUrl(), 'http://env:2');
    });
  } finally {
    cleanup(home);
  }
});

test('S15: env unset -> client.json apiUrl, trailing slash stripped', () => {
  const home = homeWithClientJson(JSON.stringify({ apiUrl: 'http://h:9/' }));
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.equal(apiBaseUrl(), 'http://h:9');
    });
  } finally {
    cleanup(home);
  }
});

test('S15: client.json apiUrl with multiple trailing slashes is fully stripped', () => {
  const home = homeWithClientJson(JSON.stringify({ apiUrl: 'http://h:9///' }));
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.equal(apiBaseUrl(), 'http://h:9');
    });
  } finally {
    cleanup(home);
  }
});

test('S16: empty env URL falls through to client.json', () => {
  const home = homeWithClientJson(JSON.stringify({ apiUrl: 'http://h:9' }));
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: '', TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.equal(apiBaseUrl(), 'http://h:9');
    });
  } finally {
    cleanup(home);
  }
});

test('S17: no client.json (directory exists, file absent) -> null', () => {
  const home = homeWithClientJson(undefined);
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.equal(apiBaseUrl(), null);
    });
  } finally {
    cleanup(home);
  }
});

test('S18: invalid or unusable client.json contents -> null, never throws', () => {
  const bad = [
    '{bad',
    '[]',
    'null',
    '"http://x"',
    '42',
    JSON.stringify({ apiUrl: 5 }),
    JSON.stringify({ apiUrl: '' }),
    JSON.stringify({ apiUrl: null }),
    JSON.stringify({ apiUrl: ['http://x'] }),
    JSON.stringify({}),
    '',
  ];
  for (const content of bad) {
    const home = homeWithClientJson(content);
    try {
      withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
        assert.equal(apiBaseUrl(), null, `content ${JSON.stringify(content)} should yield null`);
      });
    } finally {
      cleanup(home);
    }
  }
});

test('S18: client.json that is a directory -> null, never throws', () => {
  const home = emptyHome();
  mkdirSync(join(home, '.taskorchestrator', 'client.json'), { recursive: true });
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.equal(apiBaseUrl(), null);
    });
  } finally {
    cleanup(home);
  }
});

test('S19: TASK_ORCHESTRATOR_HOME selects which client.json is read', () => {
  const homeA = homeWithClientJson(JSON.stringify({ apiUrl: 'http://a:1' }));
  const homeB = homeWithClientJson(JSON.stringify({ apiUrl: 'http://b:2' }));
  const homeC = emptyHome();
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: homeA }, () => {
      assert.equal(apiBaseUrl(), 'http://a:1');
    });
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: homeB }, () => {
      assert.equal(apiBaseUrl(), 'http://b:2');
    });
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: homeC }, () => {
      assert.equal(apiBaseUrl(), null);
    });
  } finally {
    cleanup(homeA);
    cleanup(homeB);
    cleanup(homeC);
  }
});

test('S20: a token in client.json is never used; authHeader stays env-only', () => {
  const home = homeWithClientJson(JSON.stringify({ apiUrl: 'http://h:9', token: 'file-token', apiToken: 'file-token' }));
  try {
    withEnv({ TASK_ORCHESTRATOR_API_TOKEN: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.deepEqual(authHeader(), {});
    });
    withEnv({ TASK_ORCHESTRATOR_API_TOKEN: 'env-token', TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.deepEqual(authHeader(), { Authorization: 'Bearer env-token' });
    });
  } finally {
    cleanup(home);
  }
});

test('probe: no caching - client.json edits and env changes are observed between calls', () => {
  const home = homeWithClientJson(JSON.stringify({ apiUrl: 'http://one:1' }));
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.equal(apiBaseUrl(), 'http://one:1');
      writeFileSync(join(home, '.taskorchestrator', 'client.json'), JSON.stringify({ apiUrl: 'http://two:2' }));
      assert.equal(apiBaseUrl(), 'http://two:2');
      process.env.TASK_ORCHESTRATOR_API_URL = 'http://env:3';
      assert.equal(apiBaseUrl(), 'http://env:3');
    });
  } finally {
    cleanup(home);
  }
});

test('b2d81d68 S18: BOM-prefixed client.json parses and yields the URL (O2)', () => {
  const home = homeWithClientJson('﻿' + JSON.stringify({ apiUrl: 'http://bom:1' }));
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.equal(apiBaseUrl(), 'http://bom:1');
    });
  } finally {
    cleanup(home);
  }
});

test('b2d81d68 S19: client.json apiUrl is trimmed, then trailing slashes stripped; whitespace-only -> null (O3)', () => {
  const cases = [
    ['  http://h:9/  ', 'http://h:9'],
    ['\thttp://h:9//\n', 'http://h:9'],
    ['   ', null],
    ['\t\n ', null],
  ];
  for (const [apiUrl, expected] of cases) {
    const home = homeWithClientJson(JSON.stringify({ apiUrl }));
    try {
      withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
        assert.equal(apiBaseUrl(), expected, `apiUrl ${JSON.stringify(apiUrl)}`);
      });
    } finally {
      cleanup(home);
    }
  }
});

test('b2d81d68 S19 probe: BOM plus padded apiUrl together', () => {
  const home = homeWithClientJson('﻿' + JSON.stringify({ apiUrl: ' http://bom:2/ ' }));
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
      assert.equal(apiBaseUrl(), 'http://bom:2');
    });
  } finally {
    cleanup(home);
  }
});

test('b2d81d68 S20: apiBaseUrl reads the file at userClientPath(process.env)', () => {
  const home = emptyHome();
  try {
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: home }, () => {
      const p = userClientPath(process.env);
      assert.ok(resolve(p).toLowerCase().startsWith(resolve(home).toLowerCase()), `path ${p} not under ${home}`);
      mkdirSync(dirname(p), { recursive: true });
      writeFileSync(p, JSON.stringify({ apiUrl: 'http://via-locator:7/' }));
      assert.equal(apiBaseUrl(), 'http://via-locator:7');
    });
  } finally {
    cleanup(home);
  }
});

test('apiBaseUrl: strips a single trailing slash', () => {
  withEnv({ TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001/' }, () => {
    assert.equal(apiBaseUrl(), 'http://localhost:3001');
  });
});

test('apiBaseUrl: strips multiple trailing slashes', () => {
  withEnv({ TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001///' }, () => {
    assert.equal(apiBaseUrl(), 'http://localhost:3001');
  });
});

test('apiBaseUrl: leaves a URL with no trailing slash unchanged', () => {
  withEnv({ TASK_ORCHESTRATOR_API_URL: 'http://localhost:3001' }, () => {
    assert.equal(apiBaseUrl(), 'http://localhost:3001');
  });
});

test('authHeader: {} when TASK_ORCHESTRATOR_API_TOKEN is unset', () => {
  withEnv({ TASK_ORCHESTRATOR_API_TOKEN: undefined }, () => {
    assert.deepEqual(authHeader(), {});
  });
});

test('authHeader: {} when TASK_ORCHESTRATOR_API_TOKEN is empty', () => {
  withEnv({ TASK_ORCHESTRATOR_API_TOKEN: '' }, () => {
    assert.deepEqual(authHeader(), {});
  });
});

test('authHeader: Authorization Bearer header when token is set', () => {
  withEnv({ TASK_ORCHESTRATOR_API_TOKEN: 'tok-123' }, () => {
    assert.deepEqual(authHeader(), { Authorization: 'Bearer tok-123' });
  });
});

test('fetchWithTimeout: aborts against a server that never responds', async () => {
  const server = await startServer(() => {
    // Deliberately never call res.end()/res.write() — the request hangs until the client gives up.
  });
  try {
    const { port } = server.address();
    await assert.rejects(
      () => fetchWithTimeout(`http://127.0.0.1:${port}/`, {}, 100),
      (err) => {
        assert.equal(err.name, 'AbortError');
        return true;
      },
    );
  } finally {
    await stopServer(server);
  }
});

test('fetchWithTimeout: resolves normally when the server responds before the timeout', async () => {
  const server = await startServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ok');
  });
  try {
    const { port } = server.address();
    const res = await fetchWithTimeout(`http://127.0.0.1:${port}/`, {}, 2000);
    assert.equal(res.status, 200);
    assert.equal(await res.text(), 'ok');
  } finally {
    await stopServer(server);
  }
});

test('fetchWithTimeout: default timeout (no third argument) still resolves against a fast server', async () => {
  const server = await startServer((req, res) => {
    res.writeHead(200);
    res.end('default-ok');
  });
  try {
    const { port } = server.address();
    const res = await fetchWithTimeout(`http://127.0.0.1:${port}/`, {});
    assert.equal(await res.text(), 'default-ok');
  } finally {
    await stopServer(server);
  }
});

test('fetchWithTimeout: passes through caller-supplied headers (e.g. Authorization)', async () => {
  let seenAuth;
  const server = await startServer((req, res) => {
    seenAuth = req.headers.authorization;
    res.writeHead(200);
    res.end();
  });
  try {
    const { port } = server.address();
    await fetchWithTimeout(`http://127.0.0.1:${port}/`, { headers: { Authorization: 'Bearer tok-abc' } }, 2000);
    assert.equal(seenAuth, 'Bearer tok-abc');
  } finally {
    await stopServer(server);
  }
});

// ---- 75f0e354 (O4): the timeout also bounds the response-body read ----

function stopServerNow(server) {
  if (typeof server.closeAllConnections === 'function') server.closeAllConnections();
  return stopServer(server);
}

test('75f0e354 S8: headers arrive but the body stalls — res.json() rejects with AbortError within the timeout', async () => {
  const server = await startServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.write('{"rules":['); // partial body, never finished
  });
  try {
    const { port } = server.address();
    const started = Date.now();
    const res = await fetchWithTimeout(`http://127.0.0.1:${port}/`, {}, 300);
    assert.equal(res.status, 200);
    let guardTimer;
    const guard = new Promise((resolveGuard) => {
      guardTimer = setTimeout(() => resolveGuard('guard-expired'), 3000);
    });
    const outcome = await Promise.race([
      res.json().then(
        (value) => ({ resolved: value }),
        (err) => ({ rejected: err }),
      ),
      guard,
    ]);
    clearTimeout(guardTimer);
    const elapsed = Date.now() - started;
    assert.notEqual(outcome, 'guard-expired', 'the stalled body read must be aborted by the request timeout, not hang');
    assert.ok(outcome.rejected, 'a stalled body must reject, never resolve');
    assert.equal(outcome.rejected.name, 'AbortError');
    assert.ok(elapsed < 1500, `body read ended after ${elapsed}ms; must be bounded by the 300ms timeout`);
  } finally {
    await stopServerNow(server);
  }
});

test('75f0e354 S8 probe: same stall read through res.text() is also bounded', async () => {
  const server = await startServer((req, res) => {
    res.writeHead(200);
    res.write('partial');
  });
  try {
    const { port } = server.address();
    const res = await fetchWithTimeout(`http://127.0.0.1:${port}/`, {}, 300);
    let guardTimer;
    const guard = new Promise((resolveGuard) => {
      guardTimer = setTimeout(() => resolveGuard('guard-expired'), 3000);
    });
    const outcome = await Promise.race([
      res.text().then(
        () => 'resolved',
        (err) => err,
      ),
      guard,
    ]);
    clearTimeout(guardTimer);
    assert.notEqual(outcome, 'guard-expired');
    assert.notEqual(outcome, 'resolved');
    assert.equal(outcome.name, 'AbortError');
  } finally {
    await stopServerNow(server);
  }
});

test('75f0e354 S8 probe: a body that completes within the timeout still reads normally', async () => {
  const server = await startServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.write('{"a":');
    setTimeout(() => res.end('1}'), 100);
  });
  try {
    const { port } = server.address();
    const res = await fetchWithTimeout(`http://127.0.0.1:${port}/`, {}, 2000);
    assert.deepEqual(await res.json(), { a: 1 });
  } finally {
    await stopServerNow(server);
  }
});

test('75f0e354 probe: a finished fetch with a long timeout does not keep the process alive', async () => {
  const server = await startServer((req, res) => {
    res.writeHead(200);
    res.end('ok');
  });
  try {
    const { port } = server.address();
    const moduleUrl = new URL('../api-client.mjs', import.meta.url).href;
    const code =
      `import { fetchWithTimeout } from ${JSON.stringify(moduleUrl)};` +
      `const r = await fetchWithTimeout(process.argv[1], {}, 10000); await r.text();`;
    const started = Date.now();
    const status = await new Promise((resolveExit, rejectExit) => {
      const child = spawn(process.execPath, ['--input-type=module', '-e', code, `http://127.0.0.1:${port}/`], {
        stdio: 'ignore',
      });
      child.on('error', rejectExit);
      child.on('close', resolveExit);
    });
    const elapsed = Date.now() - started;
    assert.equal(status, 0);
    assert.ok(elapsed < 5000, `process lingered ${elapsed}ms after finishing; the armed timeout must not hold it open`);
  } finally {
    await stopServerNow(server);
  }
});

// ---- 4e15b651: project-level client.json, resolution order, loopback-only guard ----
// Oracle: task-scope rules R1-R10, guard G0-G4 and table rows H1-H18 / I1-I30 (frozen in the queue
// phase; WHATWG URL host parsing, loopback = localhost, 127.0.0.0/8, [::1]); test-plan S1-S15.
// Fixture F: T = ceiling (fresh realpath'd temp dir); P = T/proj holding .taskorchestrator/config.yaml;
// cwd = P/sub (nested, so the walk-up runs, never this repo); H = TASK_ORCHESTRATOR_HOME holding the
// user-level client.json, whose URL is always non-loopback so it is distinguishable from a project URL.

function fixtureF() {
  const T = realpathSync(mkdtempSync(join(tmpdir(), 'toapi-F-')));
  const P = join(T, 'proj');
  mkdirSync(join(P, '.taskorchestrator'), { recursive: true });
  writeFileSync(
    join(P, '.taskorchestrator', 'config.yaml'),
    'project:\n  rootId: 11111111-2222-3333-4444-555555555555\n  name: ProjF\n',
  );
  const cwd = join(P, 'sub');
  mkdirSync(cwd, { recursive: true });
  const H = emptyHome();
  return { T, P, cwd, H, projClient: join(P, '.taskorchestrator', 'client.json') };
}

function writeUserClient(fx, content) {
  mkdirSync(join(fx.H, '.taskorchestrator'), { recursive: true });
  writeFileSync(join(fx.H, '.taskorchestrator', 'client.json'), content);
}

function writeProjectClient(fx, content) {
  writeFileSync(fx.projClient, content);
}

function resolveF(fx, apiEnv) {
  return withEnv(
    { TASK_ORCHESTRATOR_API_URL: apiEnv, TASK_ORCHESTRATOR_HOME: fx.H, TASK_ORCHESTRATOR_CEILING: fx.T },
    () => apiBaseUrl({ cwd: fx.cwd }),
  );
}

function cleanupF(fx) {
  cleanup(fx.T);
  cleanup(fx.H);
}

const BACKSLASH = String.fromCharCode(92);

const HONOURED_ROWS = [
  ['H1', 'http://localhost:3001'],
  ['H2', 'https://localhost'],
  ['H3', 'http://127.0.0.1:3001'],
  ['H4', 'http://127.0.0.0/'],
  ['H5', 'http://127.255.255.255/'],
  ['H6', 'http://[::1]:3001/'],
  ['H7', 'http://[0:0:0:0:0:0:0:1]/'],
  ['H8', 'http://LOCALHOST:3001/'],
  ['H9', 'http://127.1/'],
  ['H10', 'http://2130706433/'],
  ['H11', 'http://0x7f.0.0.1/'],
  ['H12', 'http://0177.0.0.1/'],
  ['H13', 'http://127.0.0.1./'],
  ['H14', 'http://ｌｏｃａｌｈｏｓｔ/'],
  ['H15', 'http://localhost:3001/prefix'],
  ['H16', 'http://@localhost/'],
  ['H17', 'http://localhost' + BACKSLASH + '@evil.example/'],
  ['H18', 'http://localhost#@evil.example'],
];

const IGNORED_ROWS = [
  ['I1', 'http://localhost@evil.example/'],
  ['I2', 'http://localhost.evil.example/'],
  ['I3', 'http://127.0.0.1.evil.example/'],
  ['I4', 'http://127.evil.example/'],
  ['I5', 'http://evil.example/#localhost'],
  ['I6', 'http://evil.example/?h=127.0.0.1'],
  ['I7', 'http://[::ffff:127.0.0.1]/'],
  ['I8', 'http://0.0.0.0:3001/'],
  ['I9', '//localhost:3001'],
  ['I10', 'file:///localhost'],
  ['I11', 'http://user:pass@localhost:3001/'],
  ['I12', 'http://user@localhost/'],
  ['I13', 'http://localhost./'],
  ['I14', 'http://lоcalhost/'],
  ['I15', 'http://evil.localhost/'],
  ['I16', 'localhost:3001'],
  ['I17', 'http://126.255.255.255/'],
  ['I18', 'http://128.0.0.1/'],
  ['I19', 'http://evil.example' + BACKSLASH + '@localhost/'],
  ['I20', 'http://localhost%2eevil.example/'],
  ['I21', 'http://127.0.0.1:3001@evil.example/'],
  ['I22', 'ws://localhost:3001'],
  ['I23a', 'http://localhost '],
  ['I23b', 'http://localhost /'],
  ['I24', 'http://[::]/'],
  ['I25', 'http://[fe80::1]/'],
  ['I26', 'http://127.0.0.256/'],
  ['I27', 'http://0/'],
  ['I28', 'http://127.0.0.1\n.evil.example/'],
  ['I29', BACKSLASH + BACKSLASH + 'localhost' + BACKSLASH + 'share'],
  ['I30', 'http://evil.example:3001'],
];

const USER_URL = 'http://user.example:4102';

test('4e15b651 S1: env URL beats a loopback project file and a user file', () => {
  const fx = fixtureF();
  try {
    writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://127.0.0.1:4101/' }));
    writeUserClient(fx, JSON.stringify({ apiUrl: USER_URL }));
    assert.equal(resolveF(fx, 'http://env.example:4100/'), 'http://env.example:4100');
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 S2: env unset -> loopback project apiUrl beats the user file, trailing slash stripped', () => {
  const fx = fixtureF();
  try {
    writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://127.0.0.1:4101/' }));
    writeUserClient(fx, JSON.stringify({ apiUrl: USER_URL }));
    assert.equal(resolveF(fx, undefined), 'http://127.0.0.1:4101');
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 S3: loopback project apiUrl with no user file is returned; empty env behaves as unset', () => {
  const fx = fixtureF();
  try {
    writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://localhost:4103' }));
    assert.equal(resolveF(fx, undefined), 'http://localhost:4103');
    assert.equal(resolveF(fx, ''), 'http://localhost:4103');
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 S4: non-loopback project apiUrl is ignored -> user value; with no user file -> null', () => {
  const fx = fixtureF();
  try {
    writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://evil.example:4103' }));
    assert.equal(resolveF(fx, undefined), null);
    writeUserClient(fx, JSON.stringify({ apiUrl: USER_URL }));
    assert.equal(resolveF(fx, undefined), USER_URL);
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 S5: missing, unparsable or unusable project client.json falls through to the user value, never throws', () => {
  const contents = [
    ['missing', undefined],
    ['bad json', '{bad'],
    ['array', '[]'],
    ['null literal', 'null'],
    ['string literal', '"http://127.0.0.1:1"'],
    ['number literal', '42'],
    ['apiUrl number', JSON.stringify({ apiUrl: 5 })],
    ['apiUrl null', JSON.stringify({ apiUrl: null })],
    ['apiUrl empty', JSON.stringify({ apiUrl: '' })],
    ['apiUrl whitespace', JSON.stringify({ apiUrl: '   ' })],
    ['apiUrl array', JSON.stringify({ apiUrl: ['http://127.0.0.1:1'] })],
    ['no apiUrl key', JSON.stringify({})],
    ['empty file', ''],
  ];
  for (const [label, content] of contents) {
    const fx = fixtureF();
    try {
      writeUserClient(fx, JSON.stringify({ apiUrl: USER_URL }));
      if (content !== undefined) writeProjectClient(fx, content);
      assert.equal(resolveF(fx, undefined), USER_URL, label);
    } finally {
      cleanupF(fx);
    }
  }
  const fx = fixtureF();
  try {
    writeUserClient(fx, JSON.stringify({ apiUrl: USER_URL }));
    mkdirSync(fx.projClient, { recursive: true });
    assert.equal(resolveF(fx, undefined), USER_URL, 'client.json is a directory');
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 S6: project file with BOM and a padded apiUrl -> trimmed, trailing slashes stripped', () => {
  const fx = fixtureF();
  try {
    writeProjectClient(fx, '﻿' + JSON.stringify({ apiUrl: '  http://localhost:4104//  ' }));
    assert.equal(resolveF(fx, undefined), 'http://localhost:4104');
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 S7: config only at user level -> user value (a client.json beside it is the user file); no config anywhere -> same', () => {
  const fx = fixtureF();
  try {
    rmSync(join(fx.P, '.taskorchestrator'), { recursive: true, force: true });
    mkdirSync(join(fx.H, '.taskorchestrator'), { recursive: true });
    writeFileSync(join(fx.H, '.taskorchestrator', 'config.yaml'), 'project:\n  rootId: user-root\n  name: U\n');
    writeUserClient(fx, JSON.stringify({ apiUrl: 'http://user.example:4105' }));
    assert.equal(resolveF(fx, undefined), 'http://user.example:4105');
    rmSync(join(fx.H, '.taskorchestrator', 'config.yaml'));
    assert.equal(resolveF(fx, undefined), 'http://user.example:4105');
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 S8: result is the trimmed, slash-stripped raw string, not a re-serialised URL', () => {
  const fx = fixtureF();
  try {
    writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://LOCALHOST:3001/' }));
    assert.equal(resolveF(fx, undefined), 'http://LOCALHOST:3001');
    writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://127.1/' }));
    assert.equal(resolveF(fx, undefined), 'http://127.1');
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 S9: adversarial non-loopback project apiUrl values are ignored through apiBaseUrl -> user value', () => {
  const wanted = new Set(['I1', 'I2', 'I4', 'I7', 'I8', 'I11', 'I23b']);
  for (const [id, value] of IGNORED_ROWS.filter(([rowId]) => wanted.has(rowId))) {
    const fx = fixtureF();
    try {
      writeUserClient(fx, JSON.stringify({ apiUrl: USER_URL }));
      writeProjectClient(fx, JSON.stringify({ apiUrl: value }));
      assert.equal(resolveF(fx, undefined), USER_URL, `${id} ${JSON.stringify(value)}`);
    } finally {
      cleanupF(fx);
    }
  }
});

test('4e15b651 S10: project file rewritten between calls is re-read every time (no caching)', () => {
  const fx = fixtureF();
  try {
    writeUserClient(fx, JSON.stringify({ apiUrl: USER_URL }));
    writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://127.0.0.1:4110' }));
    assert.equal(resolveF(fx, undefined), 'http://127.0.0.1:4110');
    writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://127.0.0.1:4111' }));
    assert.equal(resolveF(fx, undefined), 'http://127.0.0.1:4111');
    writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://evil.example:4112' }));
    assert.equal(resolveF(fx, undefined), USER_URL);
    assert.equal(resolveF(fx, undefined), USER_URL);
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 S11: token keys in a project client.json are never used; the result is the URL only', () => {
  const fx = fixtureF();
  try {
    writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://127.0.0.1:4113', token: 'file-token', apiToken: 'file-token' }));
    withEnv({ TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_API_TOKEN: undefined, TASK_ORCHESTRATOR_HOME: fx.H, TASK_ORCHESTRATOR_CEILING: fx.T }, () => {
      assert.deepEqual(authHeader(), {});
      assert.equal(apiBaseUrl({ cwd: fx.cwd, env: process.env }), 'http://127.0.0.1:4113');
    });
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 S12: an explicit env object decides all three sources, not process.env', () => {
  const fx = fixtureF();
  const procHome = homeWithClientJson(JSON.stringify({ apiUrl: 'http://procuser.example:4120' }));
  try {
    writeUserClient(fx, JSON.stringify({ apiUrl: USER_URL }));
    writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://127.0.0.1:4121' }));
    const base = { TASK_ORCHESTRATOR_HOME: fx.H, TASK_ORCHESTRATOR_CEILING: fx.T };
    withEnv(
      { TASK_ORCHESTRATOR_API_URL: 'http://proc.example:4122', TASK_ORCHESTRATOR_HOME: procHome, TASK_ORCHESTRATOR_CEILING: fx.T },
      () => {
        // Explicit env without API URL: the process-level URL is not consulted; project file wins.
        assert.equal(apiBaseUrl({ cwd: fx.cwd, env: { ...base } }), 'http://127.0.0.1:4121');
        // Explicit env URL beats the project file.
        assert.equal(apiBaseUrl({ cwd: fx.cwd, env: { ...base, TASK_ORCHESTRATOR_API_URL: 'http://explicit.example:4123/' } }), 'http://explicit.example:4123');
        // Non-loopback project value falls through to the explicit env's user home, not process.env's.
        writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://evil.example:4124' }));
        assert.equal(apiBaseUrl({ cwd: fx.cwd, env: { ...base } }), USER_URL);
      },
    );
  } finally {
    cleanupF(fx);
    cleanup(procHome);
  }
});

test('4e15b651 S13: isLoopbackApiUrl is true for every honoured row (H1-H18)', () => {
  for (const [id, value] of HONOURED_ROWS) {
    assert.equal(isLoopbackApiUrl(value), true, `${id} ${JSON.stringify(value)}`);
  }
});

test('4e15b651 S14: isLoopbackApiUrl is false for every ignored row (I1-I30)', () => {
  for (const [id, value] of IGNORED_ROWS) {
    assert.equal(isLoopbackApiUrl(value), false, `${id} ${JSON.stringify(value)}`);
  }
});

test('4e15b651 S15: isLoopbackApiUrl is false and never throws for non-string input', () => {
  for (const value of [undefined, null, 42, {}, [], '']) {
    assert.equal(isLoopbackApiUrl(value), false, `${typeof value} ${JSON.stringify(value)}`);
  }
});

// ---- 4e15b651 probes ----

test('4e15b651 probe: duplicate apiUrl keys - the last one wins and the guard sees the returned value', () => {
  const fx = fixtureF();
  try {
    writeUserClient(fx, JSON.stringify({ apiUrl: USER_URL }));
    writeProjectClient(fx, '{"apiUrl":"http://evil.example:4130","apiUrl":"http://127.0.0.1:4131"}');
    assert.equal(resolveF(fx, undefined), 'http://127.0.0.1:4131');
    writeProjectClient(fx, '{"apiUrl":"http://127.0.0.1:4131","apiUrl":"http://evil.example:4130"}');
    assert.equal(resolveF(fx, undefined), USER_URL);
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 probe: a client.json beside a config at or above the ceiling is never read', () => {
  const fx = fixtureF();
  try {
    rmSync(join(fx.P, '.taskorchestrator'), { recursive: true, force: true });
    mkdirSync(join(fx.T, '.taskorchestrator'), { recursive: true });
    writeFileSync(join(fx.T, '.taskorchestrator', 'config.yaml'), 'project:\n  rootId: above\n  name: Above\n');
    writeFileSync(join(fx.T, '.taskorchestrator', 'client.json'), JSON.stringify({ apiUrl: 'http://127.0.0.1:4132' }));
    writeUserClient(fx, JSON.stringify({ apiUrl: USER_URL }));
    assert.equal(resolveF(fx, undefined), USER_URL);
  } finally {
    cleanupF(fx);
  }
});

test('4e15b651 probe: win32 case-differing cwd still finds the project client.json',
  { skip: process.platform !== 'win32' }, () => {
    // Skip is a platform gate only: case-insensitive path equality is a win32 filesystem property.
    const fx = fixtureF();
    try {
      writeProjectClient(fx, JSON.stringify({ apiUrl: 'http://127.0.0.1:4133' }));
      const out = withEnv(
        { TASK_ORCHESTRATOR_API_URL: undefined, TASK_ORCHESTRATOR_HOME: fx.H, TASK_ORCHESTRATOR_CEILING: fx.T },
        () => apiBaseUrl({ cwd: fx.cwd.toUpperCase() }),
      );
      assert.equal(out, 'http://127.0.0.1:4133');
    } finally {
      cleanupF(fx);
    }
  });
