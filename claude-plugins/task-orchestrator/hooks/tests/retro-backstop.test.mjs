// Drives retro-backstop.mjs as a subprocess. Marker isolation: every test uses a randomly
// generated fixture key (session_id, or a fixture project rootId when testing the rootId-keyed
// path) with a dedicated temp config directory, so the shared marker file at
// os.tmpdir()/task-orchestrator/retro-<key>.json is never the live marker for a real project.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
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

const pinnedHomes = [];
// Hermetic home: each spawn gets its OWN empty TASK_ORCHESTRATOR_HOME (never the fixture dir).
function freshHome() {
  const h = mkdtempSync(join(tmpdir(), 'to-retro-home-'));
  pinnedHomes.push(h);
  return h;
}
after(() => { for (const h of pinnedHomes) rmSync(h, { recursive: true, force: true }); });

// agentConfigDir === null -> AGENT_CONFIG_DIR removed from the child env (user-scope tests).
function spawnHook(agentConfigDir, payload, envOverrides = {}, cwd) {
  const env = { ...process.env, TASK_ORCHESTRATOR_HOME: freshHome(), ...envOverrides };
  if (agentConfigDir === null) delete env.AGENT_CONFIG_DIR;
  else env.AGENT_CONFIG_DIR = agentConfigDir;
  if (envOverrides.TASK_ORCHESTRATOR_MODE === undefined) {
    delete env.TASK_ORCHESTRATOR_MODE;
  }
  return spawnSync(process.execPath, [HOOK], {
    input: JSON.stringify(payload),
    env,
    encoding: 'utf-8',
    cwd: cwd ?? agentConfigDir ?? freshHome(),
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
  const res = spawnSync(process.execPath, [HOOK], { input: '{not valid json', encoding: 'utf-8', env: { ...process.env, TASK_ORCHESTRATOR_HOME: freshHome() }, cwd: freshHome() });
  assert.equal(res.status, 0);
  assert.equal(res.stdout.trim(), '{}');
});

// ── 004d65fd: S10 — headless iteration never escalates, and never touches the marker ─────────

test('S10: headless iteration with sawTerminal:true marker -> {} and marker left untouched', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n');
  const sessionId = `test-backstop-headless-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    writeMarker(marker, { sawTerminal: true, pendingRoots: ['root-1'] });
    const res = spawnHook(dir, { session_id: sessionId }, { TASK_ORCHESTRATOR_MODE: 'headless-iteration' });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    // Marker read back unmodified — still sawTerminal:true, not cleared as the interactive path
    // would clear it.
    const readBack = JSON.parse(readFileSync(marker, 'utf-8'));
    assert.equal(readBack.sawTerminal, true);
    assert.deepEqual(readBack.pendingRoots, ['root-1']);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

// ── caf6b119: user scope keys the marker by session id ───────────────────────────────────────

test('S5 user scope: sawTerminal marker at markerPath(sessionId) blocks; one only at markerPath(P) gives {}', () => {
  const NL = String.fromCharCode(10);
  const P = `pppppppp-${randomUUID()}`;
  const home = freshHome();
  writeConfig(home, ['project:', `  rootId: "${P}"`, '', 'retrospective:', '  mode: nudge', ''].join(NL));
  const sid = `test-backstop-user-${randomUUID()}`;
  const sid2 = `test-backstop-user2-${randomUUID()}`;
  try {
    writeMarker(markerPath(sid), { sawTerminal: true, pendingRoots: ['root-1'], scope: 'user' });
    const blocked = spawnHook(null, { session_id: sid }, { TASK_ORCHESTRATOR_HOME: home }, freshHome());
    assert.equal(blocked.status, 0);
    assert.equal(JSON.parse(blocked.stdout).decision, 'block');

    writeMarker(markerPath(P), { sawTerminal: true, pendingRoots: ['root-1'] });
    const silent = spawnHook(null, { session_id: sid2 }, { TASK_ORCHESTRATOR_HOME: home }, freshHome());
    assert.equal(silent.status, 0);
    assert.equal(silent.stdout.trim(), '{}');
  } finally {
    rmSync(markerPath(sid), { force: true });
    rmSync(markerPath(sid2), { force: true });
    rmSync(markerPath(P), { force: true });
  }
});

// ── to_mod_retro: the task-orchestrator-mod plugin owns the event ──────────────────────

test('to_mod_retro flag: emits {} and leaves the marker untouched, even with a sawTerminal marker', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n');
  const sessionId = `test-backstop-modflag-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    writeMarker(marker, { sawTerminal: true, pendingRoots: ['root-1'] });
    const res = spawnHook(dir, { session_id: sessionId, to_mod_retro: true });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    const after = JSON.parse(readFileSync(marker, 'utf-8'));
    assert.equal(after.sawTerminal, true);
    assert.deepEqual(after.pendingRoots, ['root-1']);
    assert.equal(after.handledAt, undefined);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});
