// Contract tests for hooks/lib/ops.mjs (fc131c71, scenario S12). Oracle: the frozen task-scope
// signatures. opsOf is index-aligned with the call's ops[]; selectOf reads the string `select`;
// opItemIds collects string ops[].itemId de-duplicated in first-seen order; resultItemIds collects the
// string `itemId` of results[i] with ok === true (never `id`, which is a note id for notes_write).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { opsOf, selectOf, opItemIds, resultItemIds } from '../lib/ops.mjs';

// ---- opsOf ----

test('S12: opsOf keeps index alignment and maps non-object entries to {}', () => {
  const input = { ops: [{ itemId: 'a' }, null, 'x', 5, undefined, { op: 'create' }] };
  assert.deepEqual(opsOf(input), [{ itemId: 'a' }, {}, {}, {}, {}, { op: 'create' }]);
  assert.equal(opsOf(input).length, input.ops.length);
});

test('S12: opsOf is [] when ops is absent, not an array, or the input is missing', () => {
  assert.deepEqual(opsOf({}), []);
  assert.deepEqual(opsOf({ ops: 'x' }), []);
  assert.deepEqual(opsOf({ ops: { 0: { itemId: 'a' } } }), []);
  assert.deepEqual(opsOf({ ops: null }), []);
  assert.deepEqual(opsOf(null), []);
  assert.deepEqual(opsOf(undefined), []);
});

test('S12: opsOf returns a copy of the array (mutating it leaves the input intact)', () => {
  const input = { ops: [{ itemId: 'a' }, { itemId: 'b' }] };
  const out = opsOf(input);
  assert.notEqual(out, input.ops);
  out.push({ itemId: 'c' });
  out.length = 0;
  assert.equal(input.ops.length, 2);
});

test('S12 probe: opsOf on an empty ops array is []', () => {
  assert.deepEqual(opsOf({ ops: [] }), []);
});

// ---- selectOf ----

test('S12: selectOf returns a string select and null for anything else', () => {
  assert.equal(selectOf({ select: 'context' }), 'context');
  assert.equal(selectOf({ select: 'list' }), 'list');
  for (const bad of [5, true, {}, ['context'], null, undefined]) {
    assert.equal(selectOf({ select: bad }), null, JSON.stringify(bad));
  }
  assert.equal(selectOf({}), null);
  assert.equal(selectOf(null), null);
  assert.equal(selectOf(undefined), null);
});

// ---- opItemIds ----

test('S12: opItemIds de-duplicates string ops[].itemId in first-seen order', () => {
  const input = { ops: [{ itemId: 'b' }, { itemId: 'a' }, { itemId: 'b' }, { op: 'x' }, { itemId: 5 }, { itemId: null }, { id: 'z' }, { itemId: 'c' }, null, { itemId: 'a' }] };
  assert.deepEqual(opItemIds(input), ['b', 'a', 'c']);
});

test('S12: opItemIds is [] when there are no ops or no string itemIds', () => {
  assert.deepEqual(opItemIds({}), []);
  assert.deepEqual(opItemIds({ ops: [] }), []);
  assert.deepEqual(opItemIds({ ops: [{ itemId: 1 }, {}] }), []);
  assert.deepEqual(opItemIds(null), []);
});

test('S12 probe: opItemIds de-duplication is exact (case and prefix are distinct ids)', () => {
  const input = { ops: [{ itemId: 'ABC' }, { itemId: 'abc' }, { itemId: 'abcd' }, { itemId: 'abcd1234' }, { itemId: 'ABC' }] };
  assert.deepEqual(opItemIds(input), ['ABC', 'abc', 'abcd', 'abcd1234']);
});

// ---- resultItemIds ----

test('S12: resultItemIds takes string itemId of ok:true results only, de-duplicated, never id', () => {
  const parsed = {
    results: [
      { ok: true, itemId: 'a' },
      { ok: false, itemId: 'b' },
      { ok: true, id: 'note-1' },
      { ok: true, itemId: 'a' },
      { ok: true, itemId: 7 },
      { ok: true, id: 'note-2', itemId: 'c' },
      { ok: true },
    ],
  };
  assert.deepEqual(resultItemIds(parsed), ['a', 'c']);
});

test('S12 probe: resultItemIds requires ok to be exactly true (1 and "true" do not count)', () => {
  const parsed = { results: [{ ok: 1, itemId: 'p' }, { ok: 'true', itemId: 'q' }, { itemId: 'r' }, { ok: true, itemId: 's' }] };
  assert.deepEqual(resultItemIds(parsed), ['s']);
});

test('S12 probe: resultItemIds tolerates null/non-object entries and a missing results array', () => {
  assert.deepEqual(resultItemIds({ results: [null, 'x', 5, { ok: true, itemId: 'a' }] }), ['a']);
  assert.deepEqual(resultItemIds({}), []);
  assert.deepEqual(resultItemIds({ results: 'nope' }), []);
  assert.deepEqual(resultItemIds(null), []);
  assert.deepEqual(resultItemIds(undefined), []);
});
