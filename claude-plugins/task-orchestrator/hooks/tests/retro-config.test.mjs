// retro-lib.mjs pure functions (c7e43c6d): config reading (AC6, S12), directive text (AC9, S15)
// and the removal of the cross-session marker API (AC1). Oracle: task-scope AC1/AC6/AC9 and the
// declarations' doc comments (dispatchAncestorId: only a project-scope config contributes its
// rootId).

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import * as lib from '../retro-lib.mjs';

// Hermetic registration lookup (bc3e3c99): apiBaseUrl now falls back to the orchestrator MCP registration, so
// no real ~/.claude.json (CLAUDE_CONFIG_DIR) or ancestor .mcp.json (cwd) may be visible to this file.
// Module-level on purpose: the process is per test file.
const ISOLATED_CWD = mkdtempSync(join(tmpdir(), 'toretro-iso-'));
process.env.CLAUDE_CONFIG_DIR = join(ISOLATED_CWD, 'claude-config');
const ORIGINAL_CWD = process.cwd();
process.chdir(ISOLATED_CWD);
after(() => {
  process.chdir(ORIGINAL_CWD);
  rmSync(ISOLATED_CWD, { recursive: true, force: true });
});

const { parseRetrospectiveConfig, parseRetrospectiveMode, dispatchAncestorId, restRootId, buildNudge, buildDispatch } = lib;

const NL = String.fromCharCode(10);
const DEFAULTS = { mode: 'nudge', dispatchThreshold: 3 };
const nested = (...retroLines) =>
  ['extensions:', '  claude-code:', '    retrospective:', ...retroLines.map((l) => `      ${l}`), ''].join(NL);

// ---- S12 defaults and shape -----------------------------------------------------------------

test('S12: absent or empty config -> exactly {mode: nudge, dispatchThreshold: 3} (no cooldownMinutes key)', () => {
  assert.deepEqual(parseRetrospectiveConfig(null), DEFAULTS);
  assert.deepEqual(parseRetrospectiveConfig(undefined), DEFAULTS);
  assert.deepEqual(parseRetrospectiveConfig(''), DEFAULTS);
});

test('S12: a config without the extensions block, or without retrospective inside it -> defaults', () => {
  assert.deepEqual(parseRetrospectiveConfig('work_item_schemas:\n  foo: bar\n'), DEFAULTS);
  assert.deepEqual(parseRetrospectiveConfig('extensions:\n  claude-code:\n    other: x\n'), DEFAULTS);
  assert.deepEqual(parseRetrospectiveConfig('extensions:\n  claude-code: {}\n'), DEFAULTS);
});

test('S12: reads mode and dispatchThreshold from extensions.claude-code.retrospective', () => {
  assert.deepEqual(parseRetrospectiveConfig(nested('mode: dispatch', 'dispatchThreshold: 5')), { mode: 'dispatch', dispatchThreshold: 5 });
  assert.deepEqual(parseRetrospectiveConfig(nested('mode: off')), { mode: 'off', dispatchThreshold: 3 });
  assert.deepEqual(parseRetrospectiveConfig(nested('dispatchThreshold: 7')), { mode: 'nudge', dispatchThreshold: 7 });
});

test('S12: a sibling key before retrospective under claude-code does not hide it', () => {
  const text = ['extensions:', '  claude-code:', '    other: x', '    retrospective:', '      mode: dispatch', '      dispatchThreshold: 4', ''].join(NL);
  assert.deepEqual(parseRetrospectiveConfig(text), { mode: 'dispatch', dispatchThreshold: 4 });
});

test('S12: a column-0 comment inside the block does not break it', () => {
  const text = ['extensions:', '  claude-code:', '    retrospective:', '# a comment', '      mode: dispatch', ''].join(NL);
  assert.equal(parseRetrospectiveConfig(text).mode, 'dispatch');
});

// ---- S12 the old top-level block is no longer read -------------------------------------------

test('S12: a top-level retrospective: block is ignored -> defaults', () => {
  assert.deepEqual(parseRetrospectiveConfig('retrospective:\n  mode: dispatch\n  dispatchThreshold: 9\n'), DEFAULTS);
  assert.deepEqual(parseRetrospectiveConfig('retrospective:\n  mode: off\n'), DEFAULTS);
});

test('S12: with both blocks present only the extensions one counts', () => {
  const text = 'retrospective:\n  mode: dispatch\n  dispatchThreshold: 9\n' + nested('mode: nudge', 'dispatchThreshold: 2');
  assert.deepEqual(parseRetrospectiveConfig(text), { mode: 'nudge', dispatchThreshold: 2 });
});

test('S12 probe: a retrospective block under another extension namespace is not this plugin\'s', () => {
  const text = ['extensions:', '  other-tool:', '    retrospective:', '      mode: dispatch', ''].join(NL);
  assert.deepEqual(parseRetrospectiveConfig(text), DEFAULTS);
});

test('S12: cooldownMinutes is dropped: configured or not, it never appears in the result', () => {
  const r = parseRetrospectiveConfig(nested('mode: dispatch', 'cooldownMinutes: 10'));
  assert.deepEqual(r, { mode: 'dispatch', dispatchThreshold: 3 });
  assert.ok(!('cooldownMinutes' in r));
});

// ---- S12 validation ---------------------------------------------------------------------------

test('S12: unrecognised mode -> nudge; off stays off; dispatch stays dispatch', () => {
  assert.equal(parseRetrospectiveConfig(nested('mode: bogus')).mode, 'nudge');
  assert.equal(parseRetrospectiveConfig(nested('mode: ')).mode, 'nudge');
  assert.equal(parseRetrospectiveConfig(nested('mode: off')).mode, 'off');
  assert.equal(parseRetrospectiveConfig(nested('mode: dispatch')).mode, 'dispatch');
});

test('S12: dispatchThreshold must be an integer >= 0, else 3; 0 is honoured', () => {
  for (const bad of ['not-a-number', '-1', '2.5', '""', '']) {
    assert.equal(parseRetrospectiveConfig(nested(`dispatchThreshold: ${bad}`)).dispatchThreshold, 3, `value ${JSON.stringify(bad)}`);
  }
  assert.equal(parseRetrospectiveConfig(nested('dispatchThreshold: 0')).dispatchThreshold, 0);
  assert.equal(parseRetrospectiveConfig(nested('dispatchThreshold: 1')).dispatchThreshold, 1);
  assert.equal(parseRetrospectiveConfig(nested('dispatchThreshold: 12')).dispatchThreshold, 12);
});

test('S12: parseRetrospectiveMode is the mode of parseRetrospectiveConfig', () => {
  const text = nested('mode: dispatch');
  assert.equal(parseRetrospectiveMode(text), 'dispatch');
  assert.equal(parseRetrospectiveMode(text), parseRetrospectiveConfig(text).mode);
  assert.equal(parseRetrospectiveMode(null), 'nudge');
  assert.equal(parseRetrospectiveMode('retrospective:\n  mode: dispatch\n'), 'nudge');
});

// ---- scope helpers -----------------------------------------------------------------------------

test('dispatchAncestorId: a project-scope config contributes its rootId; user and none scope do not', () => {
  assert.equal(dispatchAncestorId({ scope: 'project', rootId: 'R' }), 'R');
  assert.equal(dispatchAncestorId({ scope: 'user', rootId: 'P' }), null);
  assert.equal(dispatchAncestorId({ scope: 'none', rootId: null }), null);
});

// restRootId depends on the API base URL, which resolves from TASK_ORCHESTRATOR_API_URL (or a client.json
// under TASK_ORCHESTRATOR_HOME). These tests set both themselves instead of reading the ambient shell.
async function withApiEnv({ url, home }, fn) {
  const saved = { url: process.env.TASK_ORCHESTRATOR_API_URL, home: process.env.TASK_ORCHESTRATOR_HOME };
  const emptyHome = mkdtempSync(join(tmpdir(), 'retro-config-home-'));
  try {
    if (url === undefined) delete process.env.TASK_ORCHESTRATOR_API_URL;
    else process.env.TASK_ORCHESTRATOR_API_URL = url;
    process.env.TASK_ORCHESTRATOR_HOME = home ?? emptyHome;
    return await fn();
  } finally {
    if (saved.url === undefined) delete process.env.TASK_ORCHESTRATOR_API_URL;
    else process.env.TASK_ORCHESTRATOR_API_URL = saved.url;
    if (saved.home === undefined) delete process.env.TASK_ORCHESTRATOR_HOME;
    else process.env.TASK_ORCHESTRATOR_HOME = saved.home;
    rmSync(emptyHome, { recursive: true, force: true });
  }
}

test('restRootId (AC3): with an API URL, a project-scope config with a rootId yields the rootId; other configs yield none', async () => {
  await withApiEnv({ url: 'http://127.0.0.1:1' }, () => {
    assert.equal(restRootId({ scope: 'project', rootId: 'R' }), 'R');
    for (const located of [
      { scope: 'user', rootId: 'P' },
      { scope: 'none', rootId: null },
      { scope: 'project', rootId: null },
    ]) {
      assert.ok([null, undefined].includes(restRootId(located)), JSON.stringify(located));
    }
  });
});

test('restRootId (AC3): with no API URL resolvable, even a project-scope config with a rootId yields none', async () => {
  await withApiEnv({ url: undefined }, () => {
    assert.ok([null, undefined].includes(restRootId({ scope: 'project', rootId: 'R' })));
  });
});

// ---- AC1 the cross-session marker API is gone ---------------------------------------------------

test('AC1: markerPath, readMarker, writeMarker, retroMarkerKey and COOLDOWN_MS are no longer exported', () => {
  for (const name of ['markerPath', 'readMarker', 'writeMarker', 'retroMarkerKey', 'COOLDOWN_MS']) {
    assert.ok(!(name in lib), `${name} must be removed`);
  }
});

test('Invariants: the exports other modules import are still there', () => {
  for (const name of ['extractResponseJson', 'debugCapture', 'parseRetrospectiveMode', 'dispatchAncestorId', 'parseRetrospectiveConfig', 'buildNudge', 'buildDispatch']) {
    assert.equal(typeof lib[name], 'function', name);
  }
});

// ---- S15 directive text (AC9) --------------------------------------------------------------------

test('S15: buildNudge names every runId as /session-retrospective --run <runId>', () => {
  const text = buildNudge(['run-aaa', 'run-bbb']);
  assert.ok(text.includes('Retrospective suggested'), text);
  assert.ok(text.includes('/session-retrospective --run run-aaa'), text);
  assert.ok(text.includes('/session-retrospective --run run-bbb'), text);
});

test('S15: buildNudge with an empty list renders the bare command and no --run', () => {
  const text = buildNudge([]);
  assert.ok(text.includes('/session-retrospective'), text);
  assert.ok(!text.includes('--run'), text);
});

test('S15: buildDispatch names every runId, keeps the durable wording, names the ancestor, drops the depth-0 clause', () => {
  const text = buildDispatch(['run-aaa', 'run-bbb'], 'root-1');
  assert.ok(text.includes('Retrospective dispatch'), text);
  assert.ok(text.includes('/session-retrospective --run run-aaa'), text);
  assert.ok(text.includes('/session-retrospective --run run-bbb'), text);
  assert.ok(text.includes('root-1'), text);
  assert.ok(text.includes('durable'), text);
  assert.ok(!/depth 0/i.test(text), 'the depth 0 clause is dropped (AR-73): ' + text);
  assert.ok(!/outside any project root/i.test(text), text);
});

test('S15: buildDispatch with an empty list renders the bare command; a null ancestor does not throw', () => {
  const bare = buildDispatch([], 'root-1');
  assert.ok(bare.includes('/session-retrospective'), bare);
  assert.ok(!bare.includes('--run'), bare);
  const noAncestor = buildDispatch(['run-aaa'], null);
  assert.ok(noAncestor.includes('/session-retrospective --run run-aaa'), noAncestor);
  assert.ok(!/depth 0/i.test(noAncestor), noAncestor);
});
