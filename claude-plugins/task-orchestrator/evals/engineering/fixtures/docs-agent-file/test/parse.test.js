import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseRow } from '../src/parse.js';

test('parseRow converts the amount to cents', () => {
  assert.equal(parseRow('2026-01-02,acc-1,12.50,coffee').cents, 1250);
});
