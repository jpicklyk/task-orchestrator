// Direct unit coverage for api-client.mjs: apiBaseUrl (trailing-slash stripping, unset -> null),
// authHeader (Authorization iff token set), and fetchWithTimeout (aborts against a server that
// never responds; sends through caller-supplied headers). Every export here is a pure function
// or a promise this test awaits directly, in-process — no subprocess spawning needed, since
// nothing reads stdin or touches disk state.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { apiBaseUrl, authHeader, fetchWithTimeout } from '../api-client.mjs';
import { userClientPath } from '../config-locator.mjs';

function withEnv(vars, fn) {
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
