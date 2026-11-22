import { test } from 'node:test';
import assert from 'node:assert/strict';
import { writeJson } from '../src/index.js';

test('writeJson serialises rows', () => {
  assert.equal(writeJson([{ a: 1 }]), '[{"a":1}]');
});
