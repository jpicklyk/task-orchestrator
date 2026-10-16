// retro-ack.mjs is a compatibility no-op (c7e43c6d, AC7, scenario S13): exit 0, one stdout line
// saying the run mark is the acknowledgement, no REST request, no file written anywhere.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { HOOKS, ROOT, rid, retroConfig, withFixture, runFixture, listTree, runHook, makeSandbox } from './retro-fake-runs.mjs';

function assertCompatNoop(res) {
  assert.equal(res.status, 0);
  const lines = res.stdout.split(/\r?\n/).filter((l) => l.trim() !== '');
  assert.equal(lines.length, 1, `exactly one stdout line, got: ${JSON.stringify(res.stdout)}`);
  assert.match(lines[0], /mark/i, lines[0]);
  assert.match(lines[0], /acknowledg/i, lines[0]);
}

test('S13: with a project root, an API URL and an unmarked closed run: one line, exit 0, no request, no file', async () => {
  const config = retroConfig({ rootId: ROOT, mode: 'dispatch', threshold: 0 });
  await withFixture({ config, runs: [runFixture(1, { terminal: 3 })] }, async ({ ack, fake, sbx }) => {
    const cfgBefore = listTree(sbx.cfg);
    const res = await ack({ session_id: 'sess-ack-1' });
    assertCompatNoop(res);
    assert.equal(fake.requests.length, 0, JSON.stringify(fake.requests));
    assert.equal(fake.isMarked(rid(1)), false, 'retro-ack must not mark a run');
    assert.deepEqual(listTree(sbx.tmp), [], 'nothing written under TEMP');
    assert.deepEqual(listTree(sbx.cfg), cfgBefore, 'nothing written next to the config');
    assert.deepEqual(listTree(sbx.home), [], 'nothing written under the home');
  });
});

test('S13: with no stdin at all it behaves the same', async () => {
  const sbx = makeSandbox({ config: retroConfig({ mode: 'dispatch' }) });
  try {
    const res = await runHook(HOOKS.ack, null, sbx);
    assertCompatNoop(res);
    assert.deepEqual(listTree(sbx.tmp), []);
  } finally {
    sbx.cleanup();
  }
});

test('S13: with no config at all it behaves the same', async () => {
  const sbx = makeSandbox({ config: null });
  try {
    const res = await runHook(HOOKS.ack, { session_id: 'sess-ack-2' }, sbx);
    assertCompatNoop(res);
    assert.deepEqual(listTree(sbx.tmp), []);
  } finally {
    sbx.cleanup();
  }
});

test('S13 probe: garbage on stdin still exits 0 with the single line', async () => {
  const sbx = makeSandbox({ config: retroConfig({ mode: 'dispatch' }) });
  try {
    const res = await runHook(HOOKS.ack, '{not valid json', sbx);
    assertCompatNoop(res);
  } finally {
    sbx.cleanup();
  }
});

test('S13/S14: running the ack leaves no retro-* entry under <tmp>/task-orchestrator', async () => {
  const sbx = makeSandbox({ config: retroConfig({ mode: 'dispatch' }) });
  try {
    await runHook(HOOKS.ack, { session_id: 'sess-ack-3' }, sbx);
    const retro = listTree(sbx.tmp).filter((p) => p.split('/').some((seg) => seg.startsWith('retro-')));
    assert.deepEqual(retro, []);
  } finally {
    sbx.cleanup();
  }
});
