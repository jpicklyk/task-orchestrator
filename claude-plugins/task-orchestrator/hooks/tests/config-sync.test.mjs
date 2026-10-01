// Direct unit coverage for config-sync.mjs's parseRootId — previously only inferable through
// session-start.mjs's subprocess tests (the two parsers are textually identical). Importing the
// module must NOT trigger a live config sync as a side effect — config-sync.mjs guards its
// `main()` invocation behind an entrypoint check (`process.argv[1] === this file`) specifically
// so importing `parseRootId` here stays side-effect free.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync, spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  parseRootId,
  isTargetConfigPath,
  normalizeForFingerprint,
  configFingerprint,
  isValidRuleKey,
  planRuleSync,
  planBundledSync,
  formatRuleSyncSummary,
} from '../config-sync.mjs';

const HOOK = fileURLToPath(new URL('../config-sync.mjs', import.meta.url));

// Shared with the Kotlin side (ConfigFingerprintTest) so both implementations are proven
// byte-for-byte identical against the same vectors — see current/src/test/resources/fixtures/.
const FIXTURE_URL = new URL(
  '../../../../current/src/test/resources/fixtures/config-fingerprint-vectors.json',
  import.meta.url,
);
const fingerprintVectors = JSON.parse(readFileSync(FIXTURE_URL, 'utf-8'));

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

function tmpConfigDir() {
  return mkdtempSync(join(tmpdir(), 'to-config-sync-'));
}

// A local, unroutable-in-practice URL so `fetch` fails fast (connection refused) instead of
// hitting a real server — lets tests observe "did main() attempt the sync" without a live API.
const UNREACHABLE_API_URL = 'http://127.0.0.1:1';

// Hermetic env for a spawned hook (decision.md Ruling 2): TASK_ORCHESTRATOR_HOME pinned to its OWN
// fresh empty temp dir (never the fixture dir), inherited AGENT_CONFIG_DIR / API URL / token
// scrubbed, then only what the case needs set back. Returns { env, home } — the caller removes home.
function hermeticEnv(extra) {
  const home = mkdtempSync(join(tmpdir(), 'to-config-sync-home-'));
  const env = { ...process.env };
  delete env.AGENT_CONFIG_DIR;
  delete env.TASK_ORCHESTRATOR_API_URL;
  delete env.TASK_ORCHESTRATOR_API_TOKEN;
  return { env: { ...env, TASK_ORCHESTRATOR_HOME: home, ...extra }, home };
}

function spawnHook(dir, { stdin, hookEventName, filePath } = {}) {
  const input = stdin !== undefined
    ? stdin
    : JSON.stringify(hookEventName ? { hook_event_name: hookEventName, file_path: filePath } : {});
  const { env, home } = hermeticEnv({ AGENT_CONFIG_DIR: dir, TASK_ORCHESTRATOR_API_URL: UNREACHABLE_API_URL });
  try {
    return spawnSync(process.execPath, [HOOK], {
      input,
      env,
      encoding: 'utf-8',
      cwd: dir, // avoid the cwd-walk fallback finding this repo's real config.yaml
    });
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
}

test('isValidRuleKey: accepts the server grammar, rejects spaces/uppercase/leading dot', () => {
  assert.ok(isValidRuleKey('commit-discipline'));
  assert.ok(isValidRuleKey('a'));
  assert.ok(isValidRuleKey('a.b_c-9'));
  assert.ok(!isValidRuleKey('Bad Key'));
  assert.ok(!isValidRuleKey('UPPER'));
  assert.ok(!isValidRuleKey('.leading-dot'));
  assert.ok(!isValidRuleKey(''));
});

test('planRuleSync: classifies invalid key, oversized body, unchanged, and changed/new rules', () => {
  const unchangedBytes = Buffer.from('same');
  const changedBytes = Buffer.from('new-content');
  const localRules = [
    { key: 'Bad Key', bytes: Buffer.from('x') },
    { key: 'toobig', bytes: Buffer.alloc(16385, 'x') },
    { key: 'unchanged', bytes: unchangedBytes },
    { key: 'changed', bytes: changedBytes },
  ];
  const serverVersions = new Map([
    ['unchanged', sha256Hex(unchangedBytes)],
    ['changed', 'a-stale-hash'],
  ]);
  const plan = planRuleSync(localRules, serverVersions);
  assert.equal(plan.unchangedCount, 1);
  assert.deepEqual(
    plan.skipped.map((s) => s.key),
    ['Bad Key', 'toobig'],
  );
  assert.deepEqual(
    plan.toPush.map((p) => p.key),
    ['changed'],
  );
});

test('formatRuleSyncSummary: composes pushed/in-sync/skipped/failed clauses', () => {
  assert.equal(
    formatRuleSyncSummary({ pushedKeys: [], unchangedCount: 9, skipped: [], failed: [] }),
    '9 in sync.',
  );
  assert.equal(
    formatRuleSyncSummary({
      pushedKeys: ['commit-discipline', 'test-author'],
      unchangedCount: 7,
      skipped: [],
      failed: [],
    }),
    'pushed 2 (commit-discipline, test-author); 7 in sync.',
  );
  assert.equal(
    formatRuleSyncSummary({
      pushedKeys: [],
      unchangedCount: 3,
      skipped: [{ key: 'bad', reason: 'invalid key' }],
      failed: [{ key: 'conflict', reason: 'HTTP 409' }],
    }),
    '3 in sync.; skipped: bad (invalid key); failed: conflict (HTTP 409)',
  );
});

// ─── Rule-sync tests (T1–T7) ────────────────────────────────────────────────
//
// A local `node:http` fake API (same technique as api-client.test.mjs), spawned per test on an
// ephemeral port, that answers the config GET with `{relation: 'current'}` (so the config phase
// always reaches "already-in-sync" without needing to exercise the PUT path) and lets each test
// script the rules GET/PUT behavior it needs. Requests are recorded (method, raw url, body bytes)
// so tests can assert on the exact literal request shape (e.g. the `%2F`-encoded plan path).

function sha256Hex(buf) {
  return createHash('sha256').update(buf).digest('hex');
}

// The plugin's bundled rules (bundled-rules/manifest.json keys), listed at their current hash —
// merged into a server listing so a test's PUT count covers only its workspace rules.
const BUNDLED_DIR = fileURLToPath(new URL('../../bundled-rules/', import.meta.url));
function bundledAtCurrentHash() {
  const manifest = JSON.parse(readFileSync(join(BUNDLED_DIR, 'manifest.json'), 'utf-8'));
  return Object.keys(manifest).map((key) => ({
    key,
    rulesVersion: sha256Hex(normalizeForFingerprint(readFileSync(join(BUNDLED_DIR, `${key}.md`)))),
  }));
}

function writeRuleFile(dir, filename, content) {
  const rulesDir = join(dir, '.taskorchestrator', 'rules');
  mkdirSync(rulesDir, { recursive: true });
  writeFileSync(join(rulesDir, filename), content);
}

function startFakeServer(handler) {
  return new Promise((resolveListen) => {
    const server = createServer((req, res) => {
      const chunks = [];
      req.on('data', (chunk) => chunks.push(chunk));
      req.on('end', () => {
        req.rawBody = Buffer.concat(chunks);
        handler(req, res);
      });
    });
    server.listen(0, '127.0.0.1', () => resolveListen(server));
  });
}

function stopServer(server) {
  return new Promise((res) => server.close(res));
}

/** Routes every request into `requests` and dispatches to `rulesGet`/`rulesPut`; config GET always answers "current". */
function makeFakeApiHandler({ requests, rulesGetStatus = 200, rulesBody = { rules: [] }, putStatus = () => 200 }) {
  return (req, res) => {
    requests.push({ method: req.method, url: req.url, body: req.rawBody });
    if (req.method === 'GET' && req.url.startsWith('/api/v1/roots/') && req.url.includes('/config')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ relation: 'current' }));
      return;
    }
    if (req.method === 'GET' && req.url.endsWith('/rules')) {
      res.writeHead(rulesGetStatus, { 'Content-Type': 'application/json' });
      res.end(rulesGetStatus === 200 ? JSON.stringify(rulesBody) : '');
      return;
    }
    if (req.method === 'PUT' && req.url.includes('/plans/')) {
      const status = putStatus(req.url);
      res.writeHead(status);
      res.end();
      return;
    }
    res.writeHead(404);
    res.end();
  };
}

// MUST use async `spawn` (not `spawnSync`): spawnSync blocks THIS process's event loop for the
// duration of the child, so the fake API server — which runs in this same process — could never
// accept or answer a connection from the child (see phase-guard.test.mjs's identical note). The
// child's fetch would simply hang until its own 2s timeout and every T-test would false-green on
// "API unreachable" without the fake server ever actually being hit.
function spawnHookAgainst(dir, apiUrl, { stdin, hookEventName, filePath } = {}) {
  const input = stdin !== undefined
    ? stdin
    : JSON.stringify(hookEventName ? { hook_event_name: hookEventName, file_path: filePath } : {});
  const { env, home } = hermeticEnv({ AGENT_CONFIG_DIR: dir, TASK_ORCHESTRATOR_API_URL: apiUrl });
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [HOOK], { env, cwd: dir });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.stderr.on('data', (d) => {
      stderr += d;
    });
    child.on('error', (err) => {
      rmSync(home, { recursive: true, force: true });
      rejectPromise(err);
    });
    child.on('close', (status) => {
      rmSync(home, { recursive: true, force: true });
      resolvePromise({ status, stdout, stderr });
    });
    child.stdin.end(input);
  });
}

test('T1: steady state — every local rule already matches the server, zero PUTs', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  const bodies = { 'alpha.md': 'Alpha rule.\n', 'beta.md': 'Beta rule.\n' };
  for (const [filename, content] of Object.entries(bodies)) writeRuleFile(dir, filename, content);
  writeConfig(dir, 'project:\n  rootId: "root-t1"\n');
  const rules = Object.entries(bodies).map(([filename, content]) => ({
    key: filename.slice(0, -3),
    rulesVersion: sha256Hex(normalizeForFingerprint(Buffer.from(content))),
  }));
  rules.push(...bundledAtCurrentHash());
  const server = await startFakeServer(makeFakeApiHandler({ requests, rulesBody: { rules } }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const line = out.hookSpecificOutput.additionalContext;
    assert.ok(line.includes('already in sync for root root-t1'));
    assert.ok(line.includes('Rules: 7 in sync.')); // 2 workspace + 5 bundled
    assert.ok(!requests.some((r) => r.method === 'PUT'));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('T2: changed + missing rule — exactly two PUTs to the %2F-encoded plan path with matching bytes', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeRuleFile(dir, 'changed.md', 'New changed content.\n');
  writeRuleFile(dir, 'missing.md', 'Brand new rule.\n');
  writeRuleFile(dir, 'unchanged.md', 'Steady rule.\n');
  writeConfig(dir, 'project:\n  rootId: "root-t2"\n');
  const rules = [
    { key: 'changed', rulesVersion: 'stale-hash-does-not-match' },
    { key: 'unchanged', rulesVersion: sha256Hex(normalizeForFingerprint(Buffer.from('Steady rule.\n'))) },
    // 'missing' intentionally absent from the server listing
    ...bundledAtCurrentHash(),
  ];
  const server = await startFakeServer(makeFakeApiHandler({ requests, rulesBody: { rules } }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const line = out.hookSpecificOutput.additionalContext;
    assert.ok(line.includes('changed') && line.includes('missing'));

    const puts = requests.filter((r) => r.method === 'PUT');
    assert.equal(puts.length, 2);
    const putKeys = puts.map((p) => decodeURIComponent(p.url.split('/plans/')[1]));
    assert.deepEqual(new Set(putKeys), new Set(['rule/changed', 'rule/missing']));
    for (const p of puts) {
      assert.ok(p.url.includes('%2F'), `expected literal %2F in ${p.url}`);
    }
    const changedPut = puts.find((p) => p.url.includes('changed'));
    assert.deepEqual(changedPut.body, normalizeForFingerprint(Buffer.from('New changed content.\n')));
    const missingPut = puts.find((p) => p.url.includes('missing'));
    assert.deepEqual(missingPut.body, normalizeForFingerprint(Buffer.from('Brand new rule.\n')));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('T3: invalid key and oversized body are both skipped client-side, valid siblings still sync', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeRuleFile(dir, 'Bad Key.md', 'Has spaces in the filename.\n');
  writeRuleFile(dir, 'toobig.md', 'x'.repeat(16385));
  writeRuleFile(dir, 'valid-key.md', 'Fine.\n');
  writeConfig(dir, 'project:\n  rootId: "root-t3"\n');
  const server = await startFakeServer(makeFakeApiHandler({ requests, rulesBody: { rules: bundledAtCurrentHash() } }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const line = out.hookSpecificOutput.additionalContext;
    assert.ok(line.includes('Bad Key') || line.includes('invalid key'));
    assert.ok(line.includes('toobig') && line.includes('16384 bytes'));

    const puts = requests.filter((r) => r.method === 'PUT');
    assert.equal(puts.length, 1);
    assert.ok(decodeURIComponent(puts[0].url).includes('rule/valid-key'));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('T4: CRLF body normalizes to LF before hashing and PUTting', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  const crlfContent = 'Line one.\r\nLine two.\r\n';
  writeRuleFile(dir, 'crlf.md', crlfContent);
  writeConfig(dir, 'project:\n  rootId: "root-t4"\n');
  const lfHash = sha256Hex(normalizeForFingerprint(Buffer.from(crlfContent)));
  // Server already has the LF-normalized hash on file — expect NO PUT, proving the hook compares
  // (and would send) the same normalized bytes it hashes.
  const rules = [{ key: 'crlf', rulesVersion: lfHash }, ...bundledAtCurrentHash()];
  const server = await startFakeServer(makeFakeApiHandler({ requests, rulesBody: { rules } }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('6 in sync.')); // 1 workspace + 5 bundled
    assert.ok(!requests.some((r) => r.method === 'PUT'));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('T5: GET rules returns 404 — no PUTs, skip line present, config line still present', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeRuleFile(dir, 'onlyone.md', 'content\n');
  writeConfig(dir, 'project:\n  rootId: "root-t5"\n');
  const server = await startFakeServer(makeFakeApiHandler({ requests, rulesGetStatus: 404 }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const line = out.hookSpecificOutput.additionalContext;
    assert.ok(line.includes('already in sync for root root-t5'));
    assert.ok(line.includes('Rules:'));
    assert.ok(line.includes('root root-t5 not found on this server'));
    assert.ok(!requests.some((r) => r.method === 'PUT'));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('T6: no rules/ dir on SessionStart — bundled rules still listed; all current, zero PUTs', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeConfig(dir, 'project:\n  rootId: "root-t6"\n');
  const server = await startFakeServer(makeFakeApiHandler({ requests, rulesBody: { rules: bundledAtCurrentHash() } }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const line = out.hookSpecificOutput.additionalContext;
    assert.equal(line, 'Task Orchestrator: project config already in sync for root root-t6. Rules: 5 in sync.');
    assert.equal(requests.filter((r) => r.method === 'GET' && r.url.endsWith('/rules')).length, 1);
    assert.ok(!requests.some((r) => r.method === 'PUT'));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('T7: a PUT returning 409 is reported as failed, exit code stays 0', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeRuleFile(dir, 'conflict.md', 'content\n');
  writeConfig(dir, 'project:\n  rootId: "root-t7"\n');
  const server = await startFakeServer(
    makeFakeApiHandler({ requests, rulesBody: { rules: bundledAtCurrentHash() }, putStatus: () => 409 }),
  );
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const line = out.hookSpecificOutput.additionalContext;
    assert.ok(line.includes('failed'));
    assert.ok(line.includes('conflict'));
    assert.ok(line.includes('409'));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('parseRootId: resolves rootId from a project: block', () => {
  const content = 'project:\n  rootId: "abc-123"\n  name: "X"\n';
  assert.equal(parseRootId(content), 'abc-123');
});

test('parseRootId: tolerates a column-0 comment above rootId', () => {
  const content = [
    'project:',
    '# a stray column-0 comment sitting right above rootId',
    '  rootId: "root-comment-check"',
  ].join('\n');
  assert.equal(parseRootId(content), 'root-comment-check');
});

test('parseRootId: null when project: block is absent', () => {
  assert.equal(parseRootId('retrospective:\n  mode: nudge\n'), null);
  assert.equal(parseRootId(null), null);
});

test('parseRootId: block-only — ignores an inline project: { ... } form', () => {
  assert.equal(parseRootId('project: { rootId: abc }\n'), null);
});

test('isTargetConfigPath: matches forward-slash and backslash forms of the real config path', () => {
  assert.ok(isTargetConfigPath('/project/.taskorchestrator/config.yaml'));
  assert.ok(isTargetConfigPath('C:\\project\\.taskorchestrator\\config.yaml'));
});

test('isTargetConfigPath: rejects unrelated paths, including a differently-located config.yaml', () => {
  assert.ok(!isTargetConfigPath('foo/config.json'));
  assert.ok(!isTargetConfigPath('some/other/config.yaml')); // config.yaml, but not under .taskorchestrator
  assert.ok(!isTargetConfigPath(undefined));
  assert.ok(!isTargetConfigPath(null));
  assert.ok(!isTargetConfigPath(''));
});

test('FileChanged with an unrelated file_path exits silently without attempting a sync', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-unrelated"\n');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath: 'foo/config.json' });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, ''); // guard returned before any emit() — no network attempt happened
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('FileChanged with a config.yaml that is not under .taskorchestrator/ also exits silently', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-wrong-location"\n');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath: 'some/other/config.yaml' });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('FileChanged for the real config.yaml (forward-slash path) proceeds through sync logic', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-match-fwd"\n');
    const filePath = join(dir, '.taskorchestrator', 'config.yaml').replace(/\\/g, '/');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('API unreachable'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('FileChanged for the real config.yaml (backslash path) proceeds through sync logic', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-match-back"\n');
    const filePath = join(dir, '.taskorchestrator', 'config.yaml').replace(/\//g, '\\');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('API unreachable'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('SessionStart invocations proceed through sync logic unchanged', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-sessionstart-unchanged"\n');
    const res = spawnHook(dir, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('API unreachable'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: missing/garbage stdin still behaves like today (proceeds through sync logic)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-garbage-stdin"\n');
    const res = spawnHook(dir, { stdin: '{not valid json' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('API unreachable'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: empty stdin still behaves like today (proceeds through sync logic)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-empty-stdin"\n');
    const res = spawnHook(dir, { stdin: '' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('API unreachable'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('configFingerprint: matches the shared BOM/CRLF normalization vectors', () => {
  for (const vector of fingerprintVectors) {
    const buf = Buffer.from(vector.input, 'utf8');
    assert.equal(
      configFingerprint(buf),
      vector.expectedSha256,
      `vector "${vector.name}" mismatched`,
    );
  }
});

test('normalizeForFingerprint: does not mutate its input Buffer', () => {
  const original = Buffer.from('\ufeffa: 1\r\nb: 2\r\n', 'utf8');
  const copy = Buffer.from(original);
  normalizeForFingerprint(original);
  assert.deepEqual(original, copy);
});

// \u2500\u2500\u2500 S3 (D2, plans/fix-config-sync-busy.md) \u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500
//
// Blind regression coverage: syncRules must send its rule PUTs one at a time (each awaited
// before the next starts), not fanned out via Promise.all \u2014 a concurrent fan-out is exactly what
// trips the server's SQLITE_BUSY_SNAPSHOT (D1). Oracle: the brief's D2 text, not the hook's
// current Promise.all implementation. Red-proof: today's Promise.all fan-out gives max in-flight
// 3 for three rules; the fix must bring it down to 1 while still completing all three PUTs.

test('S3: rule PUTs are sent one at a time \u2014 max in-flight is 1, all three still succeed', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeRuleFile(dir, 'r1.md', 'Rule one.\n');
  writeRuleFile(dir, 'r2.md', 'Rule two.\n');
  writeRuleFile(dir, 'r3.md', 'Rule three.\n');
  writeConfig(dir, 'project:\n  rootId: "root-s3"\n');

  let inFlight = 0;
  let maxInFlight = 0;
  const server = await startFakeServer((req, res) => {
    requests.push({ method: req.method, url: req.url, body: req.rawBody });
    if (req.method === 'GET' && req.url.startsWith('/api/v1/roots/') && req.url.includes('/config')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ relation: 'current' }));
      return;
    }
    if (req.method === 'GET' && req.url.endsWith('/rules')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ rules: bundledAtCurrentHash() }));
      return;
    }
    if (req.method === 'PUT' && req.url.includes('/plans/')) {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      setTimeout(() => {
        inFlight -= 1;
        res.writeHead(200);
        res.end();
      }, 30);
      return;
    }
    res.writeHead(404);
    res.end();
  });
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const puts = requests.filter((r) => r.method === 'PUT');
    assert.equal(puts.length, 3, 'all three rule PUTs must still happen');
    assert.equal(
      maxInFlight,
      1,
      `rule PUTs must be sent one at a time (serialized), got max in-flight ${maxInFlight}`,
    );
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

// ─── 19bbe0b7: user scope, bundled rules + manifest policy, shared deadline, 404 wording ──────────
//
// Oracles are the frozen test-plan scenarios (S1..S15, labelled in each title) and task-scope
// sections TS1-TS5; none is derived from the hook's current output.

const BUNDLED_KEYS = [
  'protocol.entry-seat',
  'protocol.in-phase-seat',
  'protocol.read-only-agent',
  'commit-discipline',
  'review-scoping',
];
const PROTOCOL_BUNDLED_KEYS = BUNDLED_KEYS.filter((k) => k.startsWith('protocol.'));

function bundledNormalizedBytes(key) {
  return normalizeForFingerprint(readFileSync(join(BUNDLED_DIR, `${key}.md`)));
}

function putKey(url) {
  return decodeURIComponent(url.split('/plans/')[1]);
}

function stopServerNow(server) {
  if (typeof server.closeAllConnections === 'function') server.closeAllConnections();
  return stopServer(server);
}

/** Scripted fake API: config GET answers "current"; rules GET answers `rules`; each PUT answers 200 after `putDelayMs`. */
function makeScriptedHandler({ requests, rules = [], putDelayMs = 0 }) {
  return (req, res) => {
    requests.push({ method: req.method, url: req.url, body: req.rawBody });
    if (req.method === 'GET' && req.url.includes('/config')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ relation: 'current' }));
      return;
    }
    if (req.method === 'GET' && req.url.endsWith('/rules')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ rules }));
      return;
    }
    if (req.method === 'PUT' && req.url.includes('/plans/')) {
      setTimeout(() => {
        res.writeHead(200);
        res.end();
      }, putDelayMs);
      return;
    }
    res.writeHead(404);
    res.end();
  };
}

/** User-scope spawn (decision.md Ruling 2): config lives under the pinned home; cwd is an empty fixture dir. */
function spawnUserScope(home, cwd, apiUrl, { hookEventName = 'SessionStart' } = {}) {
  const env = { ...process.env };
  delete env.AGENT_CONFIG_DIR;
  delete env.TASK_ORCHESTRATOR_API_TOKEN;
  env.TASK_ORCHESTRATOR_HOME = home;
  env.TASK_ORCHESTRATOR_API_URL = apiUrl;
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [HOOK], { env, cwd });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.stderr.on('data', (d) => {
      stderr += d;
    });
    child.on('error', rejectPromise);
    child.on('close', (status) => resolvePromise({ status, stdout, stderr }));
    child.stdin.end(JSON.stringify({ hook_event_name: hookEventName }));
  });
}

test('S1: user scope — config under the pinned home syncs and the line says "user config"', async () => {
  const home = tmpConfigDir();
  const cwd = tmpConfigDir(); // empty: nothing to find by walk-up
  const requests = [];
  writeConfig(home, 'project:\n  rootId: "u1"\n');
  const server = await startFakeServer(makeScriptedHandler({ requests, rules: bundledAtCurrentHash() }));
  try {
    const { port } = server.address();
    const res = await spawnUserScope(home, cwd, `http://127.0.0.1:${port}`);
    assert.equal(res.status, 0);
    assert.ok(requests.some((r) => r.method === 'GET' && r.url.startsWith('/api/v1/roots/u1/config')));
    const line = JSON.parse(res.stdout).hookSpecificOutput.additionalContext;
    assert.ok(line.includes('root u1'), line);
    assert.ok(line.includes('user config'), line);
    assert.ok(!line.includes('project config'), line);
  } finally {
    await stopServer(server);
    rmSync(home, { recursive: true, force: true });
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('S13: user config without a rootId — silent exit, no requests', async () => {
  const home = tmpConfigDir();
  const cwd = tmpConfigDir();
  const requests = [];
  writeConfig(home, 'retrospective:\n  mode: nudge\n');
  const server = await startFakeServer(makeScriptedHandler({ requests }));
  try {
    const { port } = server.address();
    const res = await spawnUserScope(home, cwd, `http://127.0.0.1:${port}`);
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
    assert.equal(requests.length, 0);
  } finally {
    await stopServer(server);
    rmSync(home, { recursive: true, force: true });
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('S2: no workspace rules, none on server — all five bundled rules pushed with normalized bundled bytes', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeConfig(dir, 'project:\n  rootId: "root-s2"\n');
  const server = await startFakeServer(makeScriptedHandler({ requests }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const puts = requests.filter((r) => r.method === 'PUT');
    assert.equal(puts.length, 5);
    assert.deepEqual(new Set(puts.map((p) => putKey(p.url))), new Set(BUNDLED_KEYS.map((k) => `rule/${k}`)));
    for (const p of puts) {
      const key = putKey(p.url).slice('rule/'.length);
      assert.deepEqual(p.body, bundledNormalizedBytes(key), `body for ${key}`);
    }
    const line = JSON.parse(res.stdout).hookSpecificOutput.additionalContext;
    for (const k of BUNDLED_KEYS) assert.ok(line.includes(k), `pushed list names ${k}: ${line}`);
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S5: a workspace rule with a bundled key wins — workspace bytes are PUT even when the server holds the bundled hash', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  const content = 'Workspace override of the entry seat.\n';
  writeRuleFile(dir, 'protocol.entry-seat.md', content);
  writeConfig(dir, 'project:\n  rootId: "root-s5"\n');
  const server = await startFakeServer(makeScriptedHandler({ requests, rules: bundledAtCurrentHash() }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const puts = requests.filter((r) => r.method === 'PUT');
    assert.equal(puts.length, 1);
    assert.equal(putKey(puts[0].url), 'rule/protocol.entry-seat');
    assert.deepEqual(puts[0].body, normalizeForFingerprint(Buffer.from(content)));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S6: server holds an unknown hash for a bundled key — never overwritten; the other four are pushed', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeConfig(dir, 'project:\n  rootId: "root-s6"\n');
  const server = await startFakeServer(
    makeScriptedHandler({ requests, rules: [{ key: 'commit-discipline', rulesVersion: 'a'.repeat(64) }] }),
  );
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const keys = requests.filter((r) => r.method === 'PUT').map((p) => putKey(p.url));
    assert.equal(keys.length, 4);
    assert.ok(!keys.includes('rule/commit-discipline'));
    assert.deepEqual(
      new Set(keys),
      new Set(BUNDLED_KEYS.filter((k) => k !== 'commit-discipline').map((k) => `rule/${k}`)),
    );
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S8: rules list 404 with no workspace rules — "root <id> not found on this server", zero PUTs', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeConfig(dir, 'project:\n  rootId: "root-s8"\n');
  const server = await startFakeServer(makeFakeApiHandler({ requests, rulesGetStatus: 404 }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const line = JSON.parse(res.stdout).hookSpecificOutput.additionalContext;
    assert.ok(line.includes('root root-s8 not found on this server'), line);
    assert.ok(!requests.some((r) => r.method === 'PUT'));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S9: SessionStart shared deadline — protocol keys first, wall time bounded, deferrals reported', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  for (let i = 1; i <= 8; i += 1) writeRuleFile(dir, `w${i}.md`, `Workspace rule ${i}.\n`);
  writeConfig(dir, 'project:\n  rootId: "root-s9"\n');
  const server = await startFakeServer(makeScriptedHandler({ requests, putDelayMs: 1500 }));
  try {
    const { port } = server.address();
    const started = Date.now();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    const wall = Date.now() - started;
    assert.equal(res.status, 0);
    assert.ok(wall < 9500, `wall time ${wall}ms must stay under the 8000ms budget plus margin`);
    const putKeys = requests.filter((r) => r.method === 'PUT').map((p) => putKey(p.url));
    const received = putKeys.length;
    assert.ok(received >= 3, `received ${received} PUTs`);
    assert.deepEqual(
      new Set(putKeys.slice(0, 3)),
      new Set(PROTOCOL_BUNDLED_KEYS.map((k) => `rule/${k}`)),
      'first three PUTs are the protocol.* keys',
    );
    const line = JSON.parse(res.stdout).hookSpecificOutput.additionalContext;
    const m = line.match(/(\d+) deferred to next session/);
    assert.ok(m, `deferral clause missing: ${line}`);
    const n = Number(m[1]);
    assert.ok(n >= 13 - received && n <= 13 - received + 1, `deferred ${n} with ${received} of 13 PUTs received`);
  } finally {
    await stopServerNow(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S10: FileChanged deadline (3500ms) — bounded wall time, deferrals reported, no bundled PUTs', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  for (let i = 1; i <= 4; i += 1) writeRuleFile(dir, `w${i}.md`, `Workspace rule ${i}.\n`);
  writeConfig(dir, 'project:\n  rootId: "root-s10"\n');
  const server = await startFakeServer(makeScriptedHandler({ requests, putDelayMs: 1500 }));
  try {
    const { port } = server.address();
    const filePath = join(dir, '.taskorchestrator', 'config.yaml');
    const started = Date.now();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'FileChanged', filePath });
    const wall = Date.now() - started;
    assert.equal(res.status, 0);
    assert.ok(wall < 5000, `wall time ${wall}ms must stay under the 3500ms budget plus margin`);
    const putKeys = requests.filter((r) => r.method === 'PUT').map((p) => putKey(p.url));
    for (const k of BUNDLED_KEYS) assert.ok(!putKeys.includes(`rule/${k}`), `no bundled PUT on FileChanged: ${k}`);
    const line = JSON.parse(res.stdout).hookSpecificOutput.additionalContext;
    const m = line.match(/(\d+) deferred to next session/);
    assert.ok(m, `deferral clause missing: ${line}`);
    assert.ok(Number(m[1]) >= 1);
  } finally {
    await stopServerNow(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S11: request order — config GET, rules GET, then protocol.* PUTs before every other key', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeRuleFile(dir, 'aaa.md', 'Alphabetically first workspace rule.\n');
  writeConfig(dir, 'project:\n  rootId: "root-s11"\n');
  const server = await startFakeServer(makeScriptedHandler({ requests }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    assert.equal(requests[0].method, 'GET');
    assert.ok(requests[0].url.includes('/config'), requests[0].url);
    assert.equal(requests[1].method, 'GET');
    assert.ok(requests[1].url.endsWith('/rules'), requests[1].url);
    const putKeys = requests.slice(2).map((r) => {
      assert.equal(r.method, 'PUT');
      return putKey(r.url);
    });
    assert.equal(putKeys.length, 6);
    assert.deepEqual(new Set(putKeys.slice(0, 3)), new Set(PROTOCOL_BUNDLED_KEYS.map((k) => `rule/${k}`)));
    assert.deepEqual(new Set(putKeys.slice(3)), new Set(['rule/aaa', 'rule/commit-discipline', 'rule/review-scoping']));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S12: FileChanged pushes workspace rules only — no bundled sync', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeRuleFile(dir, 'only.md', 'The only workspace rule.\n');
  writeConfig(dir, 'project:\n  rootId: "root-s12"\n');
  const server = await startFakeServer(makeScriptedHandler({ requests }));
  try {
    const { port } = server.address();
    const filePath = join(dir, '.taskorchestrator', 'config.yaml');
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'FileChanged', filePath });
    assert.equal(res.status, 0);
    const puts = requests.filter((r) => r.method === 'PUT');
    assert.equal(puts.length, 1);
    assert.equal(putKey(puts[0].url), 'rule/only');
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('S4: planBundledSync classifies absent / known-old / current by the manifest', () => {
  const curA = Buffer.from('current a\n');
  const curB = Buffer.from('current b\n');
  const curC = Buffer.from('current c\n');
  const oldHash = sha256Hex(Buffer.from('an older shipped body\n'));
  const manifest = {
    ka: [oldHash, sha256Hex(curA)],
    kb: [oldHash, sha256Hex(curB)],
    kc: [oldHash, sha256Hex(curC)],
  };
  const plan = planBundledSync({
    bundled: [
      { key: 'ka', bytes: curA },
      { key: 'kb', bytes: curB },
      { key: 'kc', bytes: curC },
    ],
    manifest,
    serverVersions: new Map([
      ['ka', oldHash], // known older shipped hash -> overwrite
      ['kc', sha256Hex(curC)], // already current
    ]), // kb absent
    workspaceKeys: new Set(),
  });
  const byKey = Object.fromEntries(plan.toPush.map((p) => [p.key, p]));
  assert.deepEqual(Object.keys(byKey).sort(), ['ka', 'kb']);
  assert.equal(byKey.ka.reason, 'known-old');
  assert.equal(byKey.kb.reason, 'absent');
  assert.deepEqual(byKey.ka.bytes, curA);
  assert.deepEqual(byKey.kb.bytes, curB);
  assert.deepEqual(plan.inSync, ['kc']);
  assert.deepEqual(plan.untouched, []);
});

test('S7: planBundledSync never pushes over an unknown server hash (null, empty, unknown hex, uppercase hex)', () => {
  const cur = Buffer.from('current\n');
  const curHash = sha256Hex(cur);
  const oldHash = sha256Hex(Buffer.from('older\n'));
  const manifest = { k: [oldHash, curHash] };
  const unknowns = [null, '', 'f'.repeat(64), curHash.toUpperCase(), oldHash.toUpperCase()];
  for (const serverHash of unknowns) {
    const plan = planBundledSync({
      bundled: [{ key: 'k', bytes: cur }],
      manifest,
      serverVersions: new Map([['k', serverHash]]),
      workspaceKeys: new Set(),
    });
    assert.deepEqual(plan.toPush, [], `serverHash ${JSON.stringify(serverHash)} must not be pushed over`);
    assert.deepEqual(plan.inSync, [], `serverHash ${JSON.stringify(serverHash)} is not in sync`);
    assert.deepEqual(plan.untouched, ['k'], `serverHash ${JSON.stringify(serverHash)}`);
  }
});

test('planBundledSync: a key the workspace provides is omitted from every list, whatever the server holds', () => {
  const cur = Buffer.from('current\n');
  const curHash = sha256Hex(cur);
  const oldHash = sha256Hex(Buffer.from('older\n'));
  const manifest = { k: [oldHash, curHash] };
  for (const serverVersions of [new Map(), new Map([['k', oldHash]]), new Map([['k', curHash]]), new Map([['k', 'zz']])]) {
    const plan = planBundledSync({
      bundled: [{ key: 'k', bytes: cur }],
      manifest,
      serverVersions,
      workspaceKeys: new Set(['k']),
    });
    assert.deepEqual(plan, { toPush: [], inSync: [], untouched: [] });
  }
});

test('formatRuleSyncSummary: deferredCount 0 changes nothing; a positive count names "N deferred to next session"', () => {
  const base = { pushedKeys: [], unchangedCount: 4, skipped: [], failed: [] };
  assert.equal(formatRuleSyncSummary({ ...base, deferredCount: 0 }), formatRuleSyncSummary(base));
  assert.ok(formatRuleSyncSummary({ ...base, deferredCount: 3 }).includes('3 deferred to next session'));
});

test('probe: replay against a stateful server — the second run pushes nothing', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  const store = new Map();
  writeRuleFile(dir, 'aaa.md', 'Replay rule.\n');
  writeConfig(dir, 'project:\n  rootId: "root-replay"\n');
  const server = await startFakeServer((req, res) => {
    requests.push({ method: req.method, url: req.url });
    if (req.method === 'GET' && req.url.includes('/config')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ relation: 'current' }));
    } else if (req.method === 'GET' && req.url.endsWith('/rules')) {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ rules: [...store].map(([key, rulesVersion]) => ({ key, rulesVersion })) }));
    } else if (req.method === 'PUT' && req.url.includes('/plans/')) {
      store.set(putKey(req.url).slice('rule/'.length), sha256Hex(req.rawBody));
      res.writeHead(200);
      res.end();
    } else {
      res.writeHead(404);
      res.end();
    }
  });
  try {
    const { port } = server.address();
    const url = `http://127.0.0.1:${port}`;
    await spawnHookAgainst(dir, url, { hookEventName: 'SessionStart' });
    assert.equal(requests.filter((r) => r.method === 'PUT').length, 6);
    requests.length = 0;
    const second = await spawnHookAgainst(dir, url, { hookEventName: 'SessionStart' });
    assert.equal(second.status, 0);
    assert.equal(requests.filter((r) => r.method === 'PUT').length, 0);
    assert.ok(JSON.parse(second.stdout).hookSpecificOutput.additionalContext.includes('Rules: 6 in sync.'));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('probe: duplicate server listing entries at the current hash — no PUT for bundled keys', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeConfig(dir, 'project:\n  rootId: "root-dup"\n');
  const listing = [...bundledAtCurrentHash(), ...bundledAtCurrentHash()];
  const server = await startFakeServer(makeScriptedHandler({ requests, rules: listing }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    assert.ok(!requests.some((r) => r.method === 'PUT'));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});

test('probe: CRLF workspace rule with a bundled key, server at the LF hash — no PUT, counted in sync', async () => {
  const dir = tmpConfigDir();
  const requests = [];
  writeRuleFile(dir, 'commit-discipline.md', 'Line one.\r\nLine two.\r\n');
  writeConfig(dir, 'project:\n  rootId: "root-crlf-bundled"\n');
  const lfHash = sha256Hex(Buffer.from('Line one.\nLine two.\n'));
  const rules = [
    ...bundledAtCurrentHash().filter((r) => r.key !== 'commit-discipline'),
    { key: 'commit-discipline', rulesVersion: lfHash },
  ];
  const server = await startFakeServer(makeScriptedHandler({ requests, rules }));
  try {
    const { port } = server.address();
    const res = await spawnHookAgainst(dir, `http://127.0.0.1:${port}`, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    assert.ok(!requests.some((r) => r.method === 'PUT'));
    assert.ok(JSON.parse(res.stdout).hookSpecificOutput.additionalContext.includes('Rules: 5 in sync.'));
  } finally {
    await stopServer(server);
    rmSync(dir, { recursive: true, force: true });
  }
});
