// Drives retro-ack.mjs as a subprocess. Marker isolation: retro-ack has no session_id to key
// off of — with a `project:` block configured it acks exactly that rootId's marker; WITHOUT one
// it acks every marker file in the shared directory (see retro-ack.mjs's module doc), which
// would be unsafe to exercise here (it could ack a concurrently-running test's marker, or the
// live marker for this repo's real root). So every test below configures a randomly-generated
// fixture project rootId, keeping this hook's tests isolated the same way retro-backstop.test.mjs
// isolates its rootId-keyed test — never exercising the ack-everything fallback path.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { markerPath, writeMarker, readMarker } from '../retro-lib.mjs';

const HOOK = fileURLToPath(new URL('../retro-ack.mjs', import.meta.url));

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

function runHook(agentConfigDir) {
  return spawnSync(process.execPath, [HOOK], {
    env: { ...process.env, AGENT_CONFIG_DIR: agentConfigDir },
    encoding: 'utf-8',
  });
}

function tmpConfigDir() {
  return mkdtempSync(join(tmpdir(), 'to-retro-ack-'));
}

test('ackMarker resets terminalCount to 0 (fix H1), alongside sawTerminal/pendingRoots/handledAt', () => {
  const dir = tmpConfigDir();
  const rootId = `project-root-${randomUUID()}`;
  writeConfig(dir, `project:\n  rootId: "${rootId}"\n`);
  const marker = markerPath(rootId);
  try {
    writeMarker(marker, {
      sawTerminal: true,
      pendingRoots: ['root-1'],
      terminalCount: 7,
      rootUuids: ['root-1'],
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const after = readMarker(marker);
    assert.equal(after.terminalCount, 0);
    assert.equal(after.sawTerminal, false);
    assert.deepEqual(after.pendingRoots, []);
    assert.ok(typeof after.handledAt === 'number');
    // rootUuids is untouched by ack — only substance/handled state resets.
    assert.deepEqual(after.rootUuids, ['root-1']);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('ackMarker on an already-empty marker still resets terminalCount to 0, not undefined', () => {
  const dir = tmpConfigDir();
  const rootId = `project-root-${randomUUID()}`;
  writeConfig(dir, `project:\n  rootId: "${rootId}"\n`);
  const marker = markerPath(rootId);
  try {
    // No pre-existing marker file at all — readMarker() returns {} and ackMarker must still
    // write a well-formed terminalCount: 0, not leave it absent/undefined.
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const after = readMarker(marker);
    assert.equal(after.terminalCount, 0);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});
