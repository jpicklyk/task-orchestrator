import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatAmount } from '../src/billing/money.js';

test('formatAmount renders minor units', () => {
  assert.equal(formatAmount(12345, 'USD'), '$123.45');
  assert.equal(formatAmount(-5, 'USD'), '-$0.05');
});
