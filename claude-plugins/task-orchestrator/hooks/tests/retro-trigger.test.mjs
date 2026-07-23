// Drives retro-trigger.mjs as a subprocess with fixture JSON on stdin. Marker isolation: every
// test uses a fixture session_id (or a randomly-generated fixture rootId) with a dedicated temp
// config directory, so the shared marker file at os.tmpdir()/task-orchestrator/retro-<key>.json
// is never the live marker for a real project. Each test cleans up its own marker file.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { markerPath, writeMarker, readMarker } from '../retro-lib.mjs';

const HOOK = fileURLToPath(new URL('../retro-trigger.mjs', import.meta.url));

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
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
  return mkdtempSync(join(tmpdir(), 'to-retro-trigger-'));
}

test('below dispatchThreshold (mode dispatch) yields nudge text, not dispatch', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n  dispatchThreshold: 3\n');
  const sessionId = `test-trigger-below-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__complete_tree',
      tool_input: { rootId: 'aaaaaaaa-0000-0000-0000-000000000001' },
      tool_response: { summary: { completed: 1 } },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const text = out.hookSpecificOutput.additionalContext;
    assert.ok(text.includes('Retrospective suggested'), text);
    assert.ok(!text.includes('Retrospective dispatch'), text);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('at/above dispatchThreshold (mode dispatch) yields dispatch text', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n  dispatchThreshold: 3\n');
  const sessionId = `test-trigger-above-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__complete_tree',
      tool_input: { rootId: 'aaaaaaaa-0000-0000-0000-000000000001' },
      tool_response: { summary: { completed: 3 } },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const text = out.hookSpecificOutput.additionalContext;
    assert.ok(text.includes('Retrospective dispatch'), text);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('advance_item PARENT_COMPLETION substance counts direct terminals + cascade terminals (fix M1)', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n  dispatchThreshold: 3\n');
  const sessionId = `test-trigger-m1-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    // A single batched advance_item call: 4 children go terminal directly, and one of those
    // transitions cascades a parent to terminal too — 5 distinct itemIds reach terminal in this
    // one call. Substance must be 5 (>= dispatchThreshold 3), not 1 (roots.length, the old bug).
    const res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: {},
      tool_response: {
        results: [
          { itemId: 'child-1', newRole: 'terminal', unblockedItems: [], cascadeEvents: [] },
          { itemId: 'child-2', newRole: 'terminal', unblockedItems: [], cascadeEvents: [] },
          { itemId: 'child-3', newRole: 'terminal', unblockedItems: [], cascadeEvents: [] },
          {
            itemId: 'child-4',
            newRole: 'terminal',
            unblockedItems: [],
            cascadeEvents: [{ itemId: 'parent-1', targetRole: 'terminal', applied: true }],
          },
        ],
      },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const text = out.hookSpecificOutput.additionalContext;
    // Substance (5) clears the threshold (3) -> hard dispatch, not a nudge.
    assert.ok(text.includes('Retrospective dispatch'), text);
    // `roots` stays scoped to cascade parents only — the dispatch text names the parent, not
    // the four children directly (that scoping must be unchanged by the substance-count fix).
    assert.ok(text.includes('parent-1'), text);
    assert.ok(!text.includes('child-1'), text);
    // Marker resets to 0 after a directive fires, same as any other PARENT_COMPLETION.
    assert.equal(readMarker(marker).terminalCount, 0);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('advance_item PARENT_COMPLETION below threshold with the M1 union still yields nudge, not dispatch', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n  dispatchThreshold: 5\n');
  const sessionId = `test-trigger-m1-below-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    // 1 direct terminal + 1 cascaded parent = substance 2, below dispatchThreshold 5.
    const res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: {},
      tool_response: {
        results: [
          {
            itemId: 'child-1',
            newRole: 'terminal',
            unblockedItems: [],
            cascadeEvents: [{ itemId: 'parent-1', targetRole: 'terminal', applied: true }],
          },
        ],
      },
    });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const text = out.hookSpecificOutput.additionalContext;
    assert.ok(text.includes('Retrospective suggested'), text);
    assert.ok(!text.includes('Retrospective dispatch'), text);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('trigger reads a non-default dispatchThreshold from config, not just the default of 3', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n  dispatchThreshold: 7\n');
  const sessionId = `test-trigger-nondefault-threshold-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    // completed: 6 would clear the default threshold (3) but not this config's 7 -> nudge.
    let res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__complete_tree',
      tool_input: { rootId: 'root-nondefault' },
      tool_response: { summary: { completed: 6 } },
    });
    let out = JSON.parse(res.stdout);
    let text = out.hookSpecificOutput.additionalContext;
    assert.ok(text.includes('Retrospective suggested'), text);
    assert.ok(!text.includes('Retrospective dispatch'), text);

    // A distinct root (the roots.some bypass, since the prior nudge already reset terminalCount
    // to 0) clearing exactly 7 in one call -> dispatch, proving the hook reads 7 from config
    // rather than silently defaulting to 3.
    res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__complete_tree',
      tool_input: { rootId: 'root-nondefault-2' },
      tool_response: { summary: { completed: 7 } },
    });
    out = JSON.parse(res.stdout);
    text = out.hookSpecificOutput.additionalContext;
    assert.ok(text.includes('Retrospective dispatch'), text);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('terminalCount accumulates across LONE_TERMINAL calls and resets when a directive emits', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n  dispatchThreshold: 3\n');
  const sessionId = `test-trigger-accum-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    let res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: {},
      tool_response: { results: [{ itemId: 'item-1', newRole: 'terminal', unblockedItems: [], cascadeEvents: [] }] },
    });
    assert.equal(res.stdout.trim(), '{}');
    assert.equal(readMarker(marker).terminalCount, 1);

    res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__advance_item',
      tool_input: {},
      tool_response: { results: [{ itemId: 'item-2', newRole: 'terminal', unblockedItems: [], cascadeEvents: [] }] },
    });
    assert.equal(res.stdout.trim(), '{}');
    assert.equal(readMarker(marker).terminalCount, 2);

    // Substance = prior terminalCount (2) + this call's completed (1) = 3 >= threshold -> dispatch.
    res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__complete_tree',
      tool_input: { rootId: 'root-final' },
      tool_response: { summary: { completed: 1 } },
    });
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Retrospective dispatch'));
    assert.equal(readMarker(marker).terminalCount, 0);
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('the roots.some bypass still fires for a distinct root inside the cooldown window', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: nudge\n');
  const sessionId = `test-trigger-bypass-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    writeMarker(marker, { handledAt: Date.now(), rootUuids: ['root-A'], terminalCount: 0 });

    // Same root again, inside the cooldown -> suppressed.
    let res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__complete_tree',
      tool_input: { rootId: 'root-A' },
      tool_response: { summary: { completed: 1 } },
    });
    assert.equal(res.stdout.trim(), '{}');

    // A distinct root inside the same cooldown window -> the bypass fires, a directive emits.
    res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__complete_tree',
      tool_input: { rootId: 'root-B' },
      tool_response: { summary: { completed: 1 } },
    });
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Retrospective suggested'));
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

test('fail-open: unparseable tool_response yields {} and exit 0', () => {
  const dir = tmpConfigDir();
  writeConfig(dir, 'retrospective:\n  mode: dispatch\n');
  const sessionId = `test-trigger-failopen-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const res = spawnHook(dir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__complete_tree',
      tool_input: { rootId: 'root-x' },
      tool_response: { totally: 'unexpected shape' },
    });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    rmSync(marker, { force: true });
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: no discoverable config still exits 0 and defaults to nudge (never crashes)', () => {
  const emptyDir = tmpConfigDir();
  const sessionId = `test-trigger-noconfig-${randomUUID()}`;
  const marker = markerPath(sessionId);
  try {
    const res = spawnHook(emptyDir, {
      session_id: sessionId,
      tool_name: 'mcp__mcp-task-orchestrator__complete_tree',
      tool_input: { rootId: 'root-y' },
      tool_response: { summary: { completed: 1 } },
    }, { cwd: tmpdir() }); // cwd override: don't let the cwd-walk fallback find this repo's real config.yaml
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Retrospective suggested'));
  } finally {
    rmSync(marker, { force: true });
    rmSync(emptyDir, { recursive: true, force: true });
  }
});
