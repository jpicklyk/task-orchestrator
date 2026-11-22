import { test } from 'node:test';
import assert from 'node:assert/strict';
import { placeOrder } from '../src/orders/checkout.js';

test('placeOrder confirms an order with the summed total', () => {
  const order = placeOrder({ customerId: 'c1', lines: [{ sku: 'a', unitCents: 250, qty: 2 }, { sku: 'b', unitCents: 100, qty: 1 }] });
  assert.equal(order.status, 'confirmed');
  assert.equal(order.totalCents, 600);
});
