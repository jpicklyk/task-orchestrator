import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
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

function runHook(dir, payload) {
  return spawnSync(process.execPath, [HOOK], {
    input: JSON.stringify(payload),
    env: { ...process.env, AGENT_CONFIG_DIR: dir },
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

test('fail-open: malformed stdin -> silent exit 0', () => {
  const res = spawnSync(process.execPath, [HOOK], { input: '{not json', encoding: 'utf-8' });
  assert.equal(res.status, 0);
  assert.equal(res.stdout, '');
});
