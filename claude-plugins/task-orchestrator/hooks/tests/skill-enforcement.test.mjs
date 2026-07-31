// Drives skill-enforcement.mjs as a subprocess with fixture JSON on stdin. Marker isolation:
// every test uses a fixture (randomly-generated) session_id, so the shared marker file at
// os.tmpdir()/task-orchestrator/skill-enforce-<session>.json is never the live marker for a
// real session. Markers are cleaned up in `finally` blocks using a locally-duplicated
// markerPath (the hook does not export it — it is deliberately self-contained, see scope
// note in skill-enforcement.mjs).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

const HOOK = fileURLToPath(new URL('../skill-enforcement.mjs', import.meta.url));

const FIXTURE_CONFIG = `
work_item_schemas:
  test-schema:
    notes:
      - key: security-assessment
        role: review
        required: true
        description: "Security review"
        skill: "security-review"
      - key: quick-review
        role: review
        required: true
        description: "Quick review"
        skill: "review-quality"
        maxLength: 300
`;

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

function markerPath(sessionId) {
  const sanitized = sessionId ? String(sessionId).replace(/[^a-zA-Z0-9-]/g, '_') : 'unknown';
  return join(tmpdir(), 'task-orchestrator', `skill-enforce-${sanitized}.json`);
}

function spawnHook(agentConfigDir, payload, extra = {}) {
  return spawnSync(process.execPath, [HOOK], {
    input: JSON.stringify(payload),
    env: { ...process.env, AGENT_CONFIG_DIR: agentConfigDir, ...extra.env },
    encoding: 'utf-8',
    cwd: extra.cwd,
  });
}

function tmpConfigDir() {
  return mkdtempSync(join(tmpdir(), 'to-skill-enforcement-'));
}

function upsertPayload(sessionId, notes) {
  return {
    session_id: sessionId,
    tool_name: 'mcp__mcp-task-orchestrator__manage_notes',
    tool_input: { operation: 'upsert', notes },
  };
}

test('short body on skill-bound key warns with advisory, non-blocking wording', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, FIXTURE_CONFIG);
  const sessionId = `test-warn-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const res = spawnHook(dir, upsertPayload(sessionId, [
      { itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' },
    ]));
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const text = out.hookSpecificOutput.additionalContext;
    assert.ok(text.includes('SKILL SUGGESTED'), text);
    assert.ok(text.includes('Unknown skill'), text);
    assert.ok(!text.includes('Abort this call'), text);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('same session_id + itemId + key again is silent (dedup)', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, FIXTURE_CONFIG);
  const sessionId = `test-dedup-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const first = spawnHook(dir, upsertPayload(sessionId, [
      { itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' },
    ]));
    assert.equal(first.status, 0);
    assert.ok(first.stdout.length > 0);

    const second = spawnHook(dir, upsertPayload(sessionId, [
      { itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'still too short' },
    ]));
    assert.equal(second.status, 0);
    assert.equal(second.stdout.trim(), '');
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('same session_id, different itemId warns again (dedup is per item+key)', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, FIXTURE_CONFIG);
  const sessionId = `test-diffitem-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const first = spawnHook(dir, upsertPayload(sessionId, [
      { itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' },
    ]));
    assert.ok(first.stdout.length > 0);

    const second = spawnHook(dir, upsertPayload(sessionId, [
      { itemId: 'item-2', key: 'security-assessment', role: 'review', body: 'also too short' },
    ]));
    assert.equal(second.status, 0);
    const out = JSON.parse(second.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('SKILL SUGGESTED'));
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('different session_id warns again (marker is per session)', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, FIXTURE_CONFIG);
  const sessionId1 = `test-sess-a-${randomUUID()}`;
  const sessionId2 = `test-sess-b-${randomUUID()}`;
  const marker1 = markerPath(sessionId1);
  const marker2 = markerPath(sessionId2);
  try {
    const first = spawnHook(dir, upsertPayload(sessionId1, [
      { itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' },
    ]));
    assert.ok(first.stdout.length > 0);

    const second = spawnHook(dir, upsertPayload(sessionId2, [
      { itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' },
    ]));
    assert.equal(second.status, 0);
    const out = JSON.parse(second.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('SKILL SUGGESTED'));
  } finally {
    rmSync(marker1, { force: true });
    rmSync(marker2, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('body >= 200 chars is silent and records no marker entry (a later short body still warns)', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, FIXTURE_CONFIG);
  const sessionId = `test-longbody-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const longBody = 'x'.repeat(200);
    const first = spawnHook(dir, upsertPayload(sessionId, [
      { itemId: 'item-1', key: 'security-assessment', role: 'review', body: longBody },
    ]));
    assert.equal(first.status, 0);
    assert.equal(first.stdout.trim(), '');

    // If the long-body call had (incorrectly) recorded a marker entry, this would stay silent.
    const second = spawnHook(dir, upsertPayload(sessionId, [
      { itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'too short' },
    ]));
    assert.equal(second.status, 0);
    const out = JSON.parse(second.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('SKILL SUGGESTED'));
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('maxLength: 300 key scales the floor to min(200, 75) = 75', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, FIXTURE_CONFIG);
  const sessionId = `test-maxlen-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    // 100 chars >= floor(75) -> silent, no marker recorded.
    const first = spawnHook(dir, upsertPayload(sessionId, [
      { itemId: 'item-1', key: 'quick-review', role: 'review', body: 'x'.repeat(100) },
    ]));
    assert.equal(first.status, 0);
    assert.equal(first.stdout.trim(), '');

    // 50 chars < floor(75) -> warns.
    const second = spawnHook(dir, upsertPayload(sessionId, [
      { itemId: 'item-1', key: 'quick-review', role: 'review', body: 'x'.repeat(50) },
    ]));
    assert.equal(second.status, 0);
    const out = JSON.parse(second.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('SKILL SUGGESTED'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('quick-review'));
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('placeholder body under the floor warns', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, FIXTURE_CONFIG);
  const sessionId = `test-placeholder-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const res = spawnHook(dir, upsertPayload(sessionId, [
      { itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' },
    ]));
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('SKILL SUGGESTED'));
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: operation != upsert is silent', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, FIXTURE_CONFIG);
  const sessionId = `test-notupsert-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__manage_notes',
      tool_input: { operation: 'get', notes: [{ itemId: 'item-1', key: 'security-assessment', body: 'n/a' }] },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '');
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: missing notes array is silent', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, FIXTURE_CONFIG);
  const sessionId = `test-nonotes-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__manage_notes',
      tool_input: { operation: 'upsert' },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '');
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: unparseable stdin is silent', () => {
  const res = spawnSync(process.execPath, [HOOK], { input: '{not valid json', encoding: 'utf-8' });
  assert.equal(res.status, 0);
  assert.equal(res.stdout.trim(), '');
});

test('fail-open: no discoverable config is silent', () => {
  const emptyDir = tmpConfigDir();
  const sessionId = `test-noconfig-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const res = spawnHook(emptyDir, upsertPayload(sessionId, [
      { itemId: 'item-1', key: 'security-assessment', role: 'review', body: 'n/a' },
    ]), { cwd: tmpdir() }); // cwd override: don't let the cwd-walk fallback find this repo's real config.yaml
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '');
  } finally {
    rmSync(marker, { force: true });
    rmSync(emptyDir, { recursive: true, force: true });
  }
});
