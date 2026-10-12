import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { buildRows, formatText, truncate } from './rollup.mjs';

const script = fileURLToPath(new URL('./rollup.mjs', import.meta.url));
const zero = { queue: 0, work: 0, review: 0, blocked: 0, terminal: 0 };

test('empty items: no rows, zero TOTAL, json rows empty', () => {
  const r = buildRows({ items: [] });
  assert.deepEqual(r, { rows: [], total: zero });
  const lines = formatText(r).split('\n');
  assert.equal(lines.length, 2); // header + TOTAL
  assert.match(lines[1], /^TOTAL\s+(\s+0){5}$/);
  const p = spawnSync(process.execPath, [script, '--json'], { input: '{"items":[]}', encoding: 'utf8' });
  assert.equal(p.status, 0);
  assert.deepEqual(JSON.parse(p.stdout), { rows: [], total: zero });
});

test('missing items / counts count as 0', () => {
  assert.deepEqual(buildRows({}), { rows: [], total: zero });
  assert.deepEqual(buildRows({ items: [{ id: 'x', title: 't' }] }).rows[0], { id: 'x', title: 't', ...zero });
});

test('one item: 8-char id, count order, TOTAL equals row', () => {
  const ov = { items: [{ id: '3b7dc485-3ab8-4669-a774-e72b56f7a3ba', title: 'Hello', childCounts: { terminal: 5, queue: 1, work: 2, review: 3, blocked: 4 } }] };
  const r = buildRows(ov);
  assert.deepEqual(r.rows, [{ id: '3b7dc485', title: 'Hello', queue: 1, work: 2, review: 3, blocked: 4, terminal: 5 }]);
  assert.deepEqual(r.total, { queue: 1, work: 2, review: 3, blocked: 4, terminal: 5 });
  const rowLine = formatText(r).split('\n')[1];
  assert.match(rowLine, /^3b7dc485  Hello\s+1\s+2\s+3\s+4\s+5$/);
});

test('truncation: over 40 -> exactly 40, exactly 40 unchanged', () => {
  const long = 'a'.repeat(41);
  assert.equal(truncate(long).length, 40);
  assert.equal(truncate(long), 'a'.repeat(37) + '...');
  const exact = 'b'.repeat(40);
  assert.equal(truncate(exact), exact);
  assert.equal(buildRows({ items: [{ id: 'i', title: long }] }).rows[0].title.length, 40);
});

test('multi-item TOTAL sums, input order kept', () => {
  const r = buildRows({ items: [
    { id: 'aaaaaaaa1', title: 'A', childCounts: { queue: 1, work: 2 } },
    { id: 'bbbbbbbb2', title: 'B', childCounts: { queue: 3, terminal: 4 } },
  ] });
  assert.deepEqual(r.rows.map((x) => x.id), ['aaaaaaaa', 'bbbbbbbb']);
  assert.deepEqual(r.total, { queue: 4, work: 2, review: 0, blocked: 0, terminal: 4 });
});

test('CLI: text mode via stdin, invalid JSON exits 1', () => {
  const ok = spawnSync(process.execPath, [script], { input: JSON.stringify({ items: [{ id: 'abcdef012345', title: 'T', childCounts: { work: 1 } }] }), encoding: 'utf8' });
  assert.equal(ok.status, 0);
  assert.match(ok.stdout, /abcdef01/);
  assert.match(ok.stdout, /TOTAL/);
  const bad = spawnSync(process.execPath, [script], { input: 'nope', encoding: 'utf8' });
  assert.equal(bad.status, 1);
  assert.match(bad.stderr, /invalid JSON/);
  assert.equal(bad.stdout, '');
});
