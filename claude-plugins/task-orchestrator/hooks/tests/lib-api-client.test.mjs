// Contract tests for hooks/lib/api-client.mjs callOp and wellKnown (fc131c71, scenarios S13-S16).
// Oracle: the frozen task-scope signatures; current/docs/api-rest.md:359-360,745 (a 401 is the
// `unauthenticated` envelope) and :870-886 (the unauthenticated well-known document); api-reference.md
// "Response envelope" (HTTP status = the code's status, 207 for partial_failure). A node:http fake
// records method, url, raw headers and body of every request. The four moved exports
// (apiBaseUrl/authHeader/isLoopbackApiUrl/fetchWithTimeout) keep their coverage in api-client.test.mjs.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { callOp, wellKnown } from '../lib/api-client.mjs';

// Hermetic registration lookup (bc3e3c99): apiBaseUrl now falls back to the orchestrator MCP registration, so
// no real ~/.claude.json (CLAUDE_CONFIG_DIR) or ancestor .mcp.json (cwd) may be visible to this file.
// Module-level on purpose: the process is per test file.
const ISOLATED_CWD = mkdtempSync(join(tmpdir(), 'tolib-iso-'));
process.env.CLAUDE_CONFIG_DIR = join(ISOLATED_CWD, 'claude-config');
const ORIGINAL_CWD = process.cwd();
process.chdir(ISOLATED_CWD);
after(() => {
  process.chdir(ORIGINAL_CWD);
  rmSync(ISOLATED_CWD, { recursive: true, force: true });
});

const trackedDirs = [];
after(() => {
  for (const d of trackedDirs) rmSync(d, { recursive: true, force: true });
});

// Hermetic env: no client.json anywhere (home is a fresh empty dir, the ceiling is the cwd), caller's keys win.
async function withEnv(callerVars, fn) {
  const home = mkdtempSync(join(tmpdir(), 'to-lib-api-home-'));
  trackedDirs.push(home);
  const vars = {
    TASK_ORCHESTRATOR_CEILING: process.cwd(),
    AGENT_CONFIG_DIR: undefined,
    TASK_ORCHESTRATOR_HOME: home,
    TASK_ORCHESTRATOR_API_URL: undefined,
    TASK_ORCHESTRATOR_API_TOKEN: undefined,
    ...callerVars,
  };
  const saved = {};
  for (const k of Object.keys(vars)) saved[k] = process.env[k];
  try {
    for (const [k, v] of Object.entries(vars)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
    return await fn();
  } finally {
    for (const [k, v] of Object.entries(saved)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  }
}

function startFake(handler) {
  const hits = [];
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const rec = {
        method: req.method,
        url: req.url,
        headers: req.headers,
        rawHeaders: req.rawHeaders,
        body: Buffer.concat(chunks).toString('utf8'),
      };
      hits.push(rec);
      handler(rec, res);
    });
  });
  return new Promise((resolveListen) => {
    server.listen(0, '127.0.0.1', () => {
      resolveListen({ server, hits, base: `http://127.0.0.1:${server.address().port}` });
    });
  });
}

function stopFake(fake) {
  fake.server.closeAllConnections?.();
  return new Promise((res) => fake.server.close(res));
}

async function withFake(handler, fn) {
  const fake = await startFake(handler);
  try {
    return await fn(fake);
  } finally {
    await stopFake(fake);
  }
}

function sendJson(res, status, obj, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...headers });
  res.end(typeof obj === 'string' ? obj : JSON.stringify(obj));
}

function authHeaderCount(hit) {
  return hit.rawHeaders.filter((h, i) => i % 2 === 0 && h.toLowerCase() === 'authorization').length;
}

const OK_ENVELOPE = {
  ok: true,
  data: { results: [{ ok: true, itemId: 'item-1' }], summary: { ok: 1, failed: 0 } },
  meta: { reqId: 'k7f3q9ab' },
};

async function closedPortBase() {
  const f = await startFake(() => {});
  const base = f.base;
  await stopFake(f);
  return base;
}

// ---- S13 request shape and success ----

test('S13: callOp POSTs JSON to /api/v1/ops/{tool} and returns the parsed envelope plus httpStatus', async () => {
  await withEnv({ TASK_ORCHESTRATOR_API_TOKEN: undefined }, () =>
    withFake((hit, res) => sendJson(res, 200, OK_ENVELOPE), async (fake) => {
      const body = { select: 'context', itemId: 'abc', ops: [{ itemId: 'abc' }] };
      const r = await callOp('items_read', body, { baseUrl: fake.base });
      assert.equal(fake.hits.length, 1);
      const hit = fake.hits[0];
      assert.equal(hit.method, 'POST');
      assert.equal(hit.url, '/api/v1/ops/items_read');
      assert.equal(hit.headers['content-type'], 'application/json');
      assert.equal(hit.body, JSON.stringify(body));
      assert.equal(authHeaderCount(hit), 0, 'no Authorization header without a token');
      assert.equal(r.ok, true);
      assert.equal(r.legacy, false);
      assert.equal(r.httpStatus, 200);
      assert.deepEqual(r.data, OK_ENVELOPE.data);
      assert.deepEqual(r.results, [{ ok: true, itemId: 'item-1' }]);
      assert.deepEqual(r.summary, { ok: 1, failed: 0 });
      assert.equal(r.reqId, 'k7f3q9ab');
      assert.equal(r.error, null);
    }),
  );
});

test('S13: with TASK_ORCHESTRATOR_API_TOKEN set, exactly one Authorization: Bearer header is sent', async () => {
  await withEnv({ TASK_ORCHESTRATOR_API_TOKEN: 'tok-123' }, () =>
    withFake((hit, res) => sendJson(res, 200, OK_ENVELOPE), async (fake) => {
      await callOp('notes_read', {}, { baseUrl: fake.base });
      assert.equal(authHeaderCount(fake.hits[0]), 1);
      assert.equal(fake.hits[0].headers.authorization, 'Bearer tok-123');
    }),
  );
});

test('S13 probe: an empty token sends no Authorization header', async () => {
  await withEnv({ TASK_ORCHESTRATOR_API_TOKEN: '' }, () =>
    withFake((hit, res) => sendJson(res, 200, OK_ENVELOPE), async (fake) => {
      await callOp('notes_read', {}, { baseUrl: fake.base });
      assert.equal(authHeaderCount(fake.hits[0]), 0);
    }),
  );
});

test('S13 probe: an absent or null body is sent as {}', async () => {
  await withEnv({}, () =>
    withFake((hit, res) => sendJson(res, 200, OK_ENVELOPE), async (fake) => {
      await callOp('items_read', undefined, { baseUrl: fake.base });
      await callOp('items_read', null, { baseUrl: fake.base });
      assert.equal(fake.hits.length, 2);
      assert.equal(fake.hits[0].body, '{}');
      assert.equal(fake.hits[1].body, '{}');
    }),
  );
});

test('S13: baseUrl defaults to apiBaseUrl() (TASK_ORCHESTRATOR_API_URL) and an explicit baseUrl overrides it', async () => {
  await withFake((hit, res) => sendJson(res, 200, OK_ENVELOPE), async (a) => {
    await withFake((hit, res) => sendJson(res, 200, OK_ENVELOPE), async (b) => {
      await withEnv({ TASK_ORCHESTRATOR_API_URL: a.base }, async () => {
        const viaEnv = await callOp('items_read', {});
        assert.equal(viaEnv.ok, true);
        assert.equal(a.hits.length, 1);
        assert.equal(a.hits[0].url, '/api/v1/ops/items_read');
        const viaOpt = await callOp('items_read', {}, { baseUrl: b.base });
        assert.equal(viaOpt.ok, true);
        assert.equal(b.hits.length, 1);
        assert.equal(a.hits.length, 1, 'the env server is not hit when baseUrl is given');
      });
    });
  });
});

test('S13 probe: single-letter and trailing-underscore tool names are valid ([a-z][a-z_]*)', async () => {
  await withEnv({}, () =>
    withFake((hit, res) => sendJson(res, 200, OK_ENVELOPE), async (fake) => {
      await callOp('a', {}, { baseUrl: fake.base });
      await callOp('deps_write_', {}, { baseUrl: fake.base });
      assert.deepEqual(fake.hits.map((h) => h.url), ['/api/v1/ops/a', '/api/v1/ops/deps_write_']);
    }),
  );
});

test('S13: a slow server that answers within timeoutMs still yields the envelope', async () => {
  await withEnv({}, () =>
    withFake((hit, res) => setTimeout(() => sendJson(res, 200, OK_ENVELOPE), 150), async (fake) => {
      const r = await callOp('items_read', {}, { baseUrl: fake.base, timeoutMs: 3000 });
      assert.equal(r.ok, true);
      assert.equal(r.httpStatus, 200);
    }),
  );
});

// ---- envelopes at non-200 statuses ----

test('S15: a 409 error envelope is parsed (code, kind and detail copied) with httpStatus 409', async () => {
  const env = {
    ok: false,
    error: { code: 'claim_held', kind: 'transient', message: 'held', detail: { claimedBy: 'x' }, retryAfterMs: 1500 },
    meta: { reqId: 'r409' },
  };
  await withEnv({}, () =>
    withFake((hit, res) => sendJson(res, 409, env), async (fake) => {
      const r = await callOp('advance', { ops: [] }, { baseUrl: fake.base });
      assert.equal(r.ok, false);
      assert.equal(r.legacy, false);
      assert.equal(r.httpStatus, 409);
      assert.equal(r.reqId, 'r409');
      assert.equal(r.error.code, 'claim_held');
      assert.equal(r.error.kind, 'transient');
      assert.equal(r.error.retryAfterMs, 1500);
      assert.deepEqual(r.error.detail, { claimedBy: 'x' });
    }),
  );
});

test('S15: a 207 partial_failure envelope keeps its results with httpStatus 207', async () => {
  const env = {
    ok: false,
    error: { code: 'partial_failure', kind: 'permanent', message: '1 failed' },
    data: { results: [{ ok: true, itemId: 'a' }, { ok: false, error: { code: 'not_found' } }], summary: { ok: 1, failed: 1 } },
    meta: { reqId: 'r207' },
  };
  await withEnv({}, () =>
    withFake((hit, res) => sendJson(res, 207, env), async (fake) => {
      const r = await callOp('items_write', { ops: [] }, { baseUrl: fake.base });
      assert.equal(r.ok, false);
      assert.equal(r.httpStatus, 207);
      assert.equal(r.error.code, 'partial_failure');
      assert.equal(r.results.length, 2);
      assert.deepEqual(r.summary, { ok: 1, failed: 1 });
    }),
  );
});

test('S15: a 3.x-shaped body (top-level success) from an old server is reported legacy with the status', async () => {
  await withEnv({}, () =>
    withFake((hit, res) => sendJson(res, 200, { success: true, message: 'ok', data: {} }), async (fake) => {
      const r = await callOp('items_read', {}, { baseUrl: fake.base });
      assert.equal(r.legacy, true);
      assert.equal(r.ok, false);
      assert.equal(r.httpStatus, 200);
    }),
  );
});

// ---- S14 401 ----

test('S14: a 401 unauthenticated envelope is returned as sent, with httpStatus 401', async () => {
  const env = {
    ok: false,
    error: { code: 'unauthenticated', kind: 'permanent', message: 'Unknown token' },
    meta: { reqId: 'r401' },
  };
  await withEnv({ TASK_ORCHESTRATOR_API_TOKEN: 'bad' }, () =>
    withFake((hit, res) => sendJson(res, 401, env, { 'WWW-Authenticate': 'Bearer error="invalid_token"' }), async (fake) => {
      const r = await callOp('items_read', {}, { baseUrl: fake.base });
      assert.equal(r.ok, false);
      assert.equal(r.httpStatus, 401);
      assert.equal(r.error.code, 'unauthenticated');
      assert.equal(r.error.kind, 'permanent');
      assert.equal(r.reqId, 'r401');
    }),
  );
});

test('S14: a 401 with no usable envelope (empty, plain text, non-envelope JSON) -> unauthenticated / permanent', async () => {
  const bodies = ['', 'Unauthorized', '<html>401</html>', '{"error":"nope"}'];
  for (const bodyText of bodies) {
    await withEnv({}, () =>
      withFake((hit, res) => { res.writeHead(401, { 'WWW-Authenticate': 'Bearer error="invalid_request"' }); res.end(bodyText); }, async (fake) => {
        const r = await callOp('items_read', {}, { baseUrl: fake.base });
        assert.equal(r.ok, false, JSON.stringify(bodyText));
        assert.equal(r.legacy, false, JSON.stringify(bodyText));
        assert.equal(r.httpStatus, 401, JSON.stringify(bodyText));
        assert.equal(r.error.code, 'unauthenticated', JSON.stringify(bodyText));
        assert.equal(r.error.kind, 'permanent', JSON.stringify(bodyText));
      }),
    );
  }
});

// ---- S15 failures that produce no envelope ----

test('S15: with no resolvable API base callOp resolves null (no throw) and wellKnown resolves null', async () => {
  await withEnv({ TASK_ORCHESTRATOR_API_URL: undefined }, async () => {
    assert.equal(await callOp('items_read', {}), null);
    assert.equal(await wellKnown(), null);
  });
});

test('S15: invalid tool names make no request and return a permanent invalid_tool client_error', async () => {
  const invalid = ['../x', 'items_read?x', 'Items', '', 'items_read/', 'items read', 'items-read', '_x', 'x1', 'items_read\n', 'a/b', undefined, null, 5];
  await withEnv({}, () =>
    withFake((hit, res) => sendJson(res, 200, OK_ENVELOPE), async (fake) => {
      for (const tool of invalid) {
        const r = await callOp(tool, {}, { baseUrl: fake.base });
        assert.ok(r, `result for ${JSON.stringify(tool)}`);
        assert.equal(r.ok, false, JSON.stringify(tool));
        assert.equal(r.legacy, false, JSON.stringify(tool));
        assert.equal(r.httpStatus, null, JSON.stringify(tool));
        assert.equal(r.error.code, 'client_error', JSON.stringify(tool));
        assert.equal(r.error.kind, 'permanent', JSON.stringify(tool));
        assert.equal(r.error.detail.reason, 'invalid_tool', JSON.stringify(tool));
      }
      assert.equal(fake.hits.length, 0, 'no request was made for any invalid tool name');
    }),
  );
});

test('S15: a 500 with a non-JSON body -> transient bad_body carrying the status', async () => {
  await withEnv({}, () =>
    withFake((hit, res) => { res.writeHead(500, { 'Content-Type': 'text/html' }); res.end('<html>boom</html>'); }, async (fake) => {
      const r = await callOp('items_read', {}, { baseUrl: fake.base });
      assert.equal(r.ok, false);
      assert.equal(r.legacy, false);
      assert.equal(r.httpStatus, 500);
      assert.equal(r.error.code, 'client_error');
      assert.equal(r.error.kind, 'transient');
      assert.equal(r.error.detail.reason, 'bad_body');
      assert.equal(r.error.detail.status, 500);
    }),
  );
});

test('S15 probe: a 200 whose body is not an envelope (empty, text, unrelated JSON) is bad_body, not success', async () => {
  for (const bodyText of ['', 'OK', '{"hello":"world"}']) {
    await withEnv({}, () =>
      withFake((hit, res) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(bodyText); }, async (fake) => {
        const r = await callOp('items_read', {}, { baseUrl: fake.base });
        assert.equal(r.ok, false, JSON.stringify(bodyText));
        assert.equal(r.error.code, 'client_error', JSON.stringify(bodyText));
        assert.equal(r.error.kind, 'transient', JSON.stringify(bodyText));
        assert.equal(r.error.detail.reason, 'bad_body', JSON.stringify(bodyText));
      }),
    );
  }
});

test('S15: a refused connection -> transient network client_error with httpStatus null', async () => {
  const base = await closedPortBase();
  await withEnv({}, async () => {
    const r = await callOp('items_read', {}, { baseUrl: base, timeoutMs: 2000 });
    assert.equal(r.ok, false);
    assert.equal(r.legacy, false);
    assert.equal(r.httpStatus, null);
    assert.equal(r.error.code, 'client_error');
    assert.equal(r.error.kind, 'transient');
    assert.equal(r.error.detail.reason, 'network');
  });
});

test('S15: a server that never answers -> transient timeout client_error, resolving well inside 3s', async () => {
  await withEnv({}, () =>
    withFake(() => { /* accept the request and never respond */ }, async (fake) => {
      const started = Date.now();
      const r = await callOp('items_read', {}, { baseUrl: fake.base, timeoutMs: 200 });
      const elapsed = Date.now() - started;
      assert.ok(elapsed < 3000, `resolved after ${elapsed}ms`);
      assert.equal(r.ok, false);
      assert.equal(r.httpStatus, null);
      assert.equal(r.error.code, 'client_error');
      assert.equal(r.error.kind, 'transient');
      assert.equal(r.error.detail.reason, 'timeout');
      assert.equal(fake.hits.length, 1);
    }),
  );
});

test('S15 probe: a body JSON cannot serialise (circular) never rejects and sends nothing', async () => {
  await withEnv({}, () =>
    withFake((hit, res) => sendJson(res, 200, OK_ENVELOPE), async (fake) => {
      const circ = {};
      circ.self = circ;
      await assert.doesNotReject(() => callOp('items_read', circ, { baseUrl: fake.base }));
      assert.equal(fake.hits.length, 0);
    }),
  );
});

// ---- S16 wellKnown ----

test('S16: wellKnown GETs the well-known path with NO Authorization header even when a token is set', async () => {
  const doc = { name: 'mcp-task-orchestrator-current', version: '4.0.0', apiVersion: 'v1', apiUrl: '/api/v1', features: ['seats'] };
  await withEnv({ TASK_ORCHESTRATOR_API_TOKEN: 'secret-token' }, () =>
    withFake((hit, res) => sendJson(res, 200, doc), async (fake) => {
      const r = await wellKnown({ baseUrl: fake.base });
      assert.deepEqual(r, doc);
      assert.equal(fake.hits.length, 1);
      assert.equal(fake.hits[0].method, 'GET');
      assert.equal(fake.hits[0].url, '/.well-known/mcp-task-orchestrator.json');
      assert.equal(authHeaderCount(fake.hits[0]), 0);
      assert.ok(!fake.hits[0].rawHeaders.join('\n').includes('secret-token'), 'the token appears in no header');
    }),
  );
});

test('S16: wellKnown uses the env API URL when no baseUrl is given', async () => {
  await withFake((hit, res) => sendJson(res, 200, { version: '4.0.0' }), async (fake) => {
    await withEnv({ TASK_ORCHESTRATOR_API_URL: fake.base }, async () => {
      assert.deepEqual(await wellKnown(), { version: '4.0.0' });
      assert.equal(fake.hits[0].url, '/.well-known/mcp-task-orchestrator.json');
    });
  });
});

test('S16: wellKnown resolves null for 404, a non-2xx JSON object, non-JSON bodies, and non-object JSON', async () => {
  const cases = [
    [404, '{"ok":false}'],
    [404, 'Not Found'],
    [500, '{"version":"4.0.0"}'],
    [200, 'not json'],
    [200, ''],
    [200, '[1,2]'],
    [200, 'null'],
    [200, '"str"'],
  ];
  for (const [status, bodyText] of cases) {
    await withEnv({}, () =>
      withFake((hit, res) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(bodyText); }, async (fake) => {
        assert.equal(await wellKnown({ baseUrl: fake.base }), null, `${status} ${JSON.stringify(bodyText)}`);
      }),
    );
  }
});

test('S16: wellKnown resolves null (no throw) for a refused connection', async () => {
  const base = await closedPortBase();
  await withEnv({}, async () => {
    assert.equal(await wellKnown({ baseUrl: base, timeoutMs: 2000 }), null);
  });
});

test('S16: wellKnown resolves null inside the timeout for a server that never answers', async () => {
  await withEnv({}, () =>
    withFake(() => { /* never respond */ }, async (fake) => {
      const started = Date.now();
      assert.equal(await wellKnown({ baseUrl: fake.base, timeoutMs: 200 }), null);
      assert.ok(Date.now() - started < 3000);
    }),
  );
});
