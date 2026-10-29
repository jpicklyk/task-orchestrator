// retro-ack.mjs as a run-mark CLI (item 0effb9ab, scenarios S1-S4 of its test-plan). BLIND author: written
// from the frozen diagnosis (D5) and test-plan notes, the supplied declarations and the existing hook
// tests. Oracle: D5 (`--run <id>` is repeatable, each distinct id gets one REST runs_write mark through
// the ops route, one stdout line per id, always exit 0) and api-reference runs_write mark (first write
// wins; a replay answers marked:false). The hook is spawned as a node child process against the fake REST
// server of retro-fake-runs.mjs; no stdin is sent.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ROOT, rid, retroConfig, withFixture, runFixture } from './retro-fake-runs.mjs';

const cfg = () => retroConfig({ rootId: ROOT, mode: 'dispatch', threshold: 3 });
const linesOf = (res) => res.stdout.split(/\r?\n/).filter((l) => l.trim() !== '');
const runArgs = (...ids) => ids.flatMap((id) => ['--run', id]);
const markBody = (runId) => ({ op: 'mark', runId, key: 'retro' });

test('S1: --run <id> sends one mark POST for key retro, marks the run, prints "marked <id>" and exits 0', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1)] }, async ({ ack, fake }) => {
    const res = await ack(null, { args: runArgs(rid(1)) });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(fake.requests.length, 1, JSON.stringify(fake.requests));
    assert.equal(fake.requests[0].method, 'POST');
    assert.equal(fake.requests[0].path, '/api/v1/ops/runs_write');
    assert.deepEqual(fake.requests[0].body, { ops: [markBody(rid(1))] });
    assert.equal(fake.isMarked(rid(1)), true);
    assert.deepEqual(linesOf(res), [`marked ${rid(1)}`]);
  });
});

test('S2: --run A --run A --run B marks the two distinct runs with two requests, one line each', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1), runFixture(2)] }, async ({ ack, fake }) => {
    const res = await ack(null, { args: runArgs(rid(1), rid(1), rid(2)) });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(fake.requests.length, 2, JSON.stringify(fake.requests));
    assert.deepEqual(
      fake.requests.map((q) => q.body.ops[0].runId).sort(),
      [rid(1), rid(2)].sort(),
    );
    for (const q of fake.requests) assert.equal(q.body.ops.length, 1);
    assert.equal(fake.isMarked(rid(1)), true);
    assert.equal(fake.isMarked(rid(2)), true);
    assert.deepEqual(linesOf(res).sort(), [`marked ${rid(1)}`, `marked ${rid(2)}`].sort());
  });
});

test('S3: a second CLI run on the same run sends one request, the server answers marked:false, line "already marked <id>"', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1)] }, async ({ ack, fake }) => {
    const first = await ack(null, { args: runArgs(rid(1)) });
    assert.deepEqual(linesOf(first), [`marked ${rid(1)}`]);
    const before = fake.requests.length;
    const second = await ack(null, { args: runArgs(rid(1)) });
    assert.equal(second.status, 0, second.stderr);
    assert.equal(fake.requests.length - before, 1, JSON.stringify(fake.requests));
    assert.deepEqual(fake.markOutcomes.map((o) => o.marked), [true, false]);
    assert.deepEqual(linesOf(second), [`already marked ${rid(1)}`]);
  });
});

test('S4: with no REST API URL there is no request, exit 0, line "not marked <id>: no REST API configured"', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1)] }, async ({ ack, fake }) => {
    const res = await ack(null, { args: runArgs(rid(1)), apiUrl: undefined });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(fake.requests.length, 0, JSON.stringify(fake.requests));
    assert.equal(fake.isMarked(rid(1)), false);
    assert.deepEqual(linesOf(res), [`not marked ${rid(1)}: no REST API configured`]);
  });
});

for (const behavior of ['http500', 'forbidden', 'itemfail', 'badjson']) {
  test(`S4: a mark that fails (${behavior}) exits 0 and prints a line starting "mark failed <id>: "`, async () => {
    await withFixture({ config: cfg(), runs: [runFixture(1)], behavior: { mark: behavior } }, async ({ ack, fake }) => {
      const res = await ack(null, { args: runArgs(rid(1)) });
      assert.equal(res.status, 0, res.stderr);
      assert.equal(fake.requests.length, 1, JSON.stringify(fake.requests));
      assert.equal(fake.isMarked(rid(1)), false);
      const lines = linesOf(res);
      assert.equal(lines.length, 1, res.stdout);
      assert.ok(lines[0].startsWith(`mark failed ${rid(1)}: `), lines[0]);
      assert.ok(lines[0].length > `mark failed ${rid(1)}: `.length, 'the failure code follows the colon: ' + lines[0]);
    });
  });
}

test('S4: one failing id does not stop the others: mixed ids give per-id lines and exit 0', async () => {
  // rid(9) is not a run the fake knows, so its mark answers not_found while rid(1) is marked.
  await withFixture({ config: cfg(), runs: [runFixture(1)] }, async ({ ack, fake }) => {
    const res = await ack(null, { args: runArgs(rid(9), rid(1)) });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(fake.requests.length, 2, JSON.stringify(fake.requests));
    assert.equal(fake.isMarked(rid(1)), true);
    const lines = linesOf(res);
    assert.ok(lines.includes(`marked ${rid(1)}`), res.stdout);
    assert.ok(lines.some((l) => l.startsWith(`mark failed ${rid(9)}: `)), res.stdout);
  });
});

// ---- probes -----------------------------------------------------------------------------------

test('probe: a trailing --run with no value is skipped; the valid id before it is still marked', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1)] }, async ({ ack, fake }) => {
    const res = await ack(null, { args: ['--run', rid(1), '--run'] });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(fake.requests.length, 1, JSON.stringify(fake.requests));
    assert.equal(fake.requests[0].body.ops[0].runId, rid(1));
    assert.equal(fake.isMarked(rid(1)), true);
  });
});

test('probe: an empty --run value sends no request and exits 0', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1)] }, async ({ ack, fake }) => {
    const res = await ack(null, { args: ['--run', ''] });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(fake.requests.length, 0, JSON.stringify(fake.requests));
    assert.equal(fake.isMarked(rid(1)), false);
  });
});

test('probe: an id is sent verbatim, upper case and non-UUID shapes included', async () => {
  const id = 'Run-ABC-UPPER-01';
  await withFixture({ config: cfg(), runs: [{ runId: id, terminal: 3, members: 3, marks: {} }] }, async ({ ack, fake }) => {
    const res = await ack(null, { args: runArgs(id) });
    assert.equal(res.status, 0, res.stderr);
    assert.deepEqual(fake.requests[0].body, { ops: [markBody(id)] });
    assert.equal(fake.isMarked(id), true);
    assert.deepEqual(linesOf(res), [`marked ${id}`]);
  });
});
