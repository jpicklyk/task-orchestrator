import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addOrder, markPaid } from '../src/orders/orders.js';

test('markPaid rejects a second payment with a domain error', () => {
  addOrder({ id: 'o1' });
  markPaid('o1', 500);
  assert.throws(() => markPaid('o1', 500), (e) => e.code === 'ORDER_ALREADY_PAID');
});
