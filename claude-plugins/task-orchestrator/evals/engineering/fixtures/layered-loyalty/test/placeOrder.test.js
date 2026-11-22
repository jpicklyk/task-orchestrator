import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';

test('placing an order stores it', () => {
  const app = createApp();
  const order = app.placeOrder('c1', [{ sku: 'a', unitCents: 300, qty: 2 }]);
  assert.equal(app.getOrder(order.id).totalCents, 600);
});
