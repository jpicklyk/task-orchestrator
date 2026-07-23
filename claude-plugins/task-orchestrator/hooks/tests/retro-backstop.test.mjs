// Drives retro-backstop.mjs as a subprocess. Marker isolation: every test uses a randomly
// generated fixture key (session_id, or a fixture project rootId when testing the rootId-keyed
// path) with a dedicated temp config directory, so the shared marker file at
// os.tmpdir()/task-orchestrator/retro-<key>.json is never the live marker for a real project.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { markerPath, writeMarker } from '../retro-lib.mjs';

const HOOK = fileURLToPath(new URL('../retro-backstop.mjs', import.meta.url));

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

function spawnHook(agentConfigDir, payload) {
  return spawnSync(process.execPath, [HOOK], {
    input: JSON.stringify(payload),
    env: { ...process.env, AGENT_CONFIG_DIR: agentConfigDir },
    encoding: 'utf-8',
  });
}

function tmpConfigDir() {
  return mkdtempSync(join(tmpdir(), 'to-retro-backstop-'));
}

test('never emits dispatch text, even in mode: dispatch', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n');
  const sessionId = `test-backstop-nudgeonly-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    writeMarker(marker, { sawTerminal: true, pendingRoots: ['root-1'] });
    const res = spawnHook(dir, { session_id: sessionId });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('Retrospective suggested'));
    assert.ok(!out.reason.includes('Retrospective dispatch'));
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('empty pendingRoots emits no project-anchor UUID, even with a project rootId configured', () => {
  const dir = tmpConfigDir();
  const rootId = `project-root-${randomUUID()}`;
  writeConfig(dir, `project:\n  rootId: "${rootId}"\n\nretrospective:\n  mode: dispatch\n`);
  const marker = markerPath(rootId); // key = rootId when a project rootId is configured
  try {
    writeMarker(marker, { sawTerminal: true, pendingRoots: [] });
    const res = spawnHook(dir, { session_id: `test-backstop-emptyroots-${randomUUID()}` });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(!out.reason.includes(rootId), out.reason);
    assert.ok(!out.reason.includes('root(s):'), out.reason);
    assert.ok(out.reason.includes('`/session-retrospective`'), out.reason);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('stop_hook_active short-circuits to {} regardless of marker state', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n');
  const sessionId = `test-backstop-loopguard-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    writeMarker(marker, { sawTerminal: true, pendingRoots: ['root-1'] });
    const res = spawnHook(dir, { session_id: sessionId, stop_hook_active: true });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('sawTerminal falsy -> {} (nothing to escalate)', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n');
  const sessionId = `test-backstop-nothing-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const res = spawnHook(dir, { session_id: sessionId });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('within cooldown -> {} (already handled recently)', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n  cooldownMinutes: 30\n');
  const sessionId = `test-backstop-cooldown-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    writeMarker(marker, { sawTerminal: true, pendingRoots: ['root-1'], handledAt: Date.now() });
    const res = spawnHook(dir, { session_id: sessionId });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: malformed stdin yields {} and exit 0', () => {
  const res = spawnSync(process.execPath, [HOOK], { input: '{not valid json', encoding: 'utf-8' });
  assert.equal(res.status, 0);
  assert.equal(res.stdout.trim(), '{}');
});
