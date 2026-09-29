import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync, spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const HOOK = fileURLToPath(new URL('../enforce-actor-attribution.mjs', import.meta.url));

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

function cleanEnv(extra) {
  const env = { ...process.env, ...extra };
  if (!('TASK_ORCHESTRATOR_API_URL' in extra)) delete env.TASK_ORCHESTRATOR_API_URL;
  if (!('TASK_ORCHESTRATOR_API_TOKEN' in extra)) delete env.TASK_ORCHESTRATOR_API_TOKEN;
  return env;
}

// Async variant: the stub REST server lives in this process, so the hook must run without
// blocking the event loop (spawnSync would deadlock the stub).
function runHookAsync(dir, payload, extraEnv) {
  return new Promise(resolveP => {
    const child = spawn(process.execPath, [HOOK], {
      env: cleanEnv({ AGENT_CONFIG_DIR: dir, ...extraEnv }),
      cwd: dir,
    });
    let stdout = '';
    child.stdout.on('data', d => { stdout += d; });
    child.on('close', status => resolveP({ status, stdout }));
    child.stdin.end(JSON.stringify(payload));
  });
}

async function withStub(handler, fn) {
  const requests = [];
  const server = createServer((req, res) => { requests.push(req.url); handler(req, res); });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const url = `http://127.0.0.1:${server.address().port}`;
  try { return await fn(url, requests); }
  finally { await new Promise(r => server.close(r)); }
}

function runHook(dir, payload) {
  return spawnSync(process.execPath, [HOOK], {
    input: JSON.stringify(payload),
    env: cleanEnv({ AGENT_CONFIG_DIR: dir }),
    encoding: 'utf-8',
    cwd: dir, // avoid the cwd-walk fallback finding this repo's real config.yaml
  });
}

function tmpConfigDir() {
  return mkdtempSync(join(tmpdir(), 'to-actor-attr-'));
}

test('actor_authentication absent -> allowed, silent exit 0', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'retrospective:\n  mode: nudge\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { transitions: [{ itemId: 'x', trigger: 'start' }] },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('actor_authentication enabled, missing actor on advance_item -> denies', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_authentication:\n  enabled: true\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { transitions: [{ itemId: 'x', trigger: 'start' }] },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('actor_authentication enabled, actor present -> allowed, silent exit 0', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_authentication:\n  enabled: true\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { transitions: [{ itemId: 'x', trigger: 'start', actor: { id: 'a', kind: 'orchestrator' } }] },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('actor_authentication enabled via inline {} form, missing actor on manage_notes upsert -> denies', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_authentication: { enabled: true }\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__manage_notes',
      tool_input: { operation: 'upsert', notes: [{ itemId: 'x', key: 'session-tracking', body: 'hi' }] },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('manage_notes with operation other than upsert is never enforced', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_authentication:\n  enabled: true\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__manage_notes',
      tool_input: { operation: 'delete', notes: [{ itemId: 'x', key: 'session-tracking' }] },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('column-0 comment above enabled: true still resolves as enabled (parser bug fix)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, [
      'actor_authentication:',
      '# a stray column-0 comment documenting enabled',
      '  enabled: true',
      '',
    ].join('\n'));
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { transitions: [{ itemId: 'x', trigger: 'start' }] },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('actor_authentication enabled via inline {} form with a trailing comment still enforces (fix M2)', () => {
  const dir = tmpConfigDir();
  try {
    // The unsafe-direction M2 regression: an anchored inline regex fails this line entirely,
    // readSection returns null, and enforcement goes silently OFF. It must still deny here.
    writeConfig(dir, 'actor_authentication: { enabled: true } # trailing\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { transitions: [{ itemId: 'x', trigger: 'start' }] },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ─────────────────────────────────────────────────────────────────────────
// actor_attribution.required — local-only option, independent of actor_authentication
// ─────────────────────────────────────────────────────────────────────────

test('actor_attribution.required absent (only actor_authentication section, enabled false-ish) -> allowed, silent exit 0', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_attribution:\n  required: false\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { transitions: [{ itemId: 'x', trigger: 'start' }] },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('actor_attribution.required true, actor_authentication absent, missing actor on advance_item -> denies', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_attribution:\n  required: true\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { transitions: [{ itemId: 'x', trigger: 'start' }] },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('actor_attribution.required true, missing actor on manage_notes upsert -> denies', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_attribution:\n  required: true\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__manage_notes',
      tool_input: { operation: 'upsert', notes: [{ itemId: 'x', key: 'session-tracking', body: 'hi' }] },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('actor_attribution.required true, actor present -> allowed, silent exit 0', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_attribution:\n  required: true\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { transitions: [{ itemId: 'x', trigger: 'start', actor: { id: 'a', kind: 'orchestrator' } }] },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('actor_attribution.required true via inline {} form -> denies', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_attribution: { required: true }\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { transitions: [{ itemId: 'x', trigger: 'start' }] },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('actor_authentication.enabled false but actor_attribution.required true -> still denies (independent options)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, [
      'actor_authentication:',
      '  enabled: false',
      'actor_attribution:',
      '  required: true',
      '',
    ].join('\n'));
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { transitions: [{ itemId: 'x', trigger: 'start' }] },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('neither actor_authentication.enabled nor actor_attribution.required set -> allowed, silent exit 0', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'retrospective:\n  mode: nudge\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__manage_notes',
      tool_input: { operation: 'upsert', notes: [{ itemId: 'x', key: 'session-tracking', body: 'hi' }] },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: malformed stdin -> silent exit 0', () => {
  const res = spawnSync(process.execPath, [HOOK], { input: '{not json', encoding: 'utf-8' });
  assert.equal(res.status, 0);
  assert.equal(res.stdout, '');
});

// ─────────────────────────────────────────────────────────────────────────
// O1 — singular-sugar advance_item (`{itemId, trigger}`, no `transitions`) must be enforced too
// ─────────────────────────────────────────────────────────────────────────

test('O1: actor_authentication enabled, actor-less singular-sugar advance_item (no transitions) -> denies', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_authentication:\n  enabled: true\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { itemId: 'x', trigger: 'start' },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('O1: actor_attribution.required true, actor-less singular-sugar advance_item -> denies', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_attribution:\n  required: true\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { itemId: 'x', trigger: 'start' },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('O1: singular-sugar advance_item WITH a top-level actor -> allowed, silent exit 0', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_authentication:\n  enabled: true\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { itemId: 'x', trigger: 'start', actor: { id: 'a', kind: 'orchestrator' } },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('O1: transitions[] present -> singular top-level fields are ignored (server rule); actor-less element still denies', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_authentication:\n  enabled: true\n');
    // A top-level actor alongside transitions[] must NOT rescue a missing per-element actor —
    // the server ignores singular fields whenever transitions[] is present.
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: {
        itemId: 'ignored',
        trigger: 'ignored',
        actor: { id: 'ignored-top-level', kind: 'orchestrator' },
        transitions: [{ itemId: 'x', trigger: 'start' }],
      },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('O1: transitions[] present with a valid per-element actor -> allowed, even with no top-level actor', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_authentication:\n  enabled: true\n');
    const res = runHook(dir, {
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: { transitions: [{ itemId: 'x', trigger: 'start', actor: { id: 'a', kind: 'orchestrator' } }] },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ---- seat-owned note warning (#384) ----
const ITEM = '1369a435-bf86-4fbc-93b0-ebe22e7edb64';
const upsert = (kind, itemId = ITEM) => ({
  tool_name: 'mcp__mcp-task-orchestrator__manage_notes',
  tool_input: { operation: 'upsert', notes: [{ itemId, key: 'review-checklist', role: 'review', body: 'x', actor: { id: 'o', kind } }] },
});
const json = (code, obj) => (req, res) => { res.statusCode = code; res.setHeader('content-type', 'application/json'); res.end(JSON.stringify(obj)); };

test('seat-owned: stored subagent + incoming orchestrator -> warns, never denies', async () => {
  const dir = tmpConfigDir();
  try {
    await withStub(json(200, { actor: { id: 'reviewer:abc', kind: 'subagent' } }), async (url, reqs) => {
      const res = await runHookAsync(dir, upsert('orchestrator'), { TASK_ORCHESTRATOR_API_URL: url });
      assert.equal(res.status, 0);
      const out = JSON.parse(res.stdout);
      assert.equal(out.hookSpecificOutput.permissionDecision, undefined);
      const ctx = out.hookSpecificOutput.additionalContext;
      assert.match(ctx, new RegExp(ITEM));
      assert.match(ctx, /review-checklist/);
      assert.match(ctx, /reviewer:abc/);
      assert.match(ctx, /orchestrator-confirmation/);
      assert.ok(out.systemMessage);
      assert.deepEqual(reqs, [`/api/v1/items/${ITEM}/notes/review-checklist`]);
    });
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('seat-owned: stored orchestrator actor -> silent', async () => {
  const dir = tmpConfigDir();
  try {
    await withStub(json(200, { actor: { id: 'o', kind: 'orchestrator' } }), async url => {
      const res = await runHookAsync(dir, upsert('orchestrator'), { TASK_ORCHESTRATOR_API_URL: url });
      assert.equal(res.status, 0);
      assert.equal(res.stdout, '');
    });
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('seat-owned: null (redacted) actor -> silent', async () => {
  const dir = tmpConfigDir();
  try {
    await withStub(json(200, { actor: null }), async url => {
      const res = await runHookAsync(dir, upsert('orchestrator'), { TASK_ORCHESTRATOR_API_URL: url });
      assert.equal(res.status, 0);
      assert.equal(res.stdout, '');
    });
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('seat-owned: 404 absent note and 500 -> silent', async () => {
  const dir = tmpConfigDir();
  try {
    for (const code of [404, 500]) {
      await withStub(json(code, { error: 'x' }), async url => {
        const res = await runHookAsync(dir, upsert('orchestrator'), { TASK_ORCHESTRATOR_API_URL: url });
        assert.equal(res.status, 0);
        assert.equal(res.stdout, '');
      });
    }
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('seat-owned: unreachable server -> silent exit 0', async () => {
  const dir = tmpConfigDir();
  try {
    const url = await withStub(json(200, {}), async u => u); // server now closed
    const res = await runHookAsync(dir, upsert('orchestrator'), { TASK_ORCHESTRATOR_API_URL: url });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('seat-owned: API URL unset -> silent, no request', async () => {
  const dir = tmpConfigDir();
  try {
    await withStub(json(200, { actor: { id: 's', kind: 'subagent' } }), async (url, reqs) => {
      const res = await runHookAsync(dir, upsert('orchestrator'), {});
      assert.equal(res.status, 0);
      assert.equal(res.stdout, '');
      assert.equal(reqs.length, 0);
    });
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('seat-owned: incoming subagent actor or id prefix -> no request', async () => {
  const dir = tmpConfigDir();
  try {
    await withStub(json(200, { actor: { id: 's', kind: 'subagent' } }), async (url, reqs) => {
      for (const p of [upsert('subagent'), upsert('orchestrator', '1369a435')]) {
        const res = await runHookAsync(dir, p, { TASK_ORCHESTRATOR_API_URL: url });
        assert.equal(res.status, 0);
        assert.equal(res.stdout, '');
      }
      assert.equal(reqs.length, 0);
    });
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('seat-owned: enforcement on + missing actor -> deny takes precedence, no warning', async () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'actor_attribution:\n  required: true\n');
    await withStub(json(200, { actor: { id: 's', kind: 'subagent' } }), async (url, reqs) => {
      const payload = upsert('orchestrator');
      payload.tool_input.notes.push({ itemId: ITEM, key: 'k', role: 'work', body: 'y' });
      const res = await runHookAsync(dir, payload, { TASK_ORCHESTRATOR_API_URL: url });
      assert.equal(res.status, 0);
      const out = JSON.parse(res.stdout);
      assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
      assert.equal(out.hookSpecificOutput.additionalContext, undefined);
    });
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
