import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createOrder, ordersForCustomer } from '../src/orders/orders.js';

test('ordersForCustomer returns only that customer', () => {
  createOrder('c1', []);
  createOrder('c2', []);
  assert.equal(ordersForCustomer('c1').length, 1);
});
