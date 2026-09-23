// Direct unit coverage for api-client.mjs: apiBaseUrl (trailing-slash stripping, unset -> null),
// authHeader (Authorization iff token set), and fetchWithTimeout (aborts against a server that
// never responds; sends through caller-supplied headers). Every export here is a pure function
// or a promise this test awaits directly, in-process — no subprocess spawning needed, since
// nothing reads stdin or touches disk state.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { apiBaseUrl, authHeader, fetchWithTimeout } from '../api-client.mjs';

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

test('apiBaseUrl: null when TASK_ORCHESTRATOR_API_URL is unset', () => {
  withEnv({ TASK_ORCHESTRATOR_API_URL: undefined }, () => {
    assert.equal(apiBaseUrl(), null);
  });
});

test('apiBaseUrl: null when TASK_ORCHESTRATOR_API_URL is empty', () => {
  withEnv({ TASK_ORCHESTRATOR_API_URL: '' }, () => {
    assert.equal(apiBaseUrl(), null);
  });
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
