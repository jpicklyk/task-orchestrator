import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createShipment, shipmentsForOrder } from '../src/shipments/shipments.js';

test('shipmentsForOrder lists an order split across shipments', () => {
  createShipment('o1', 't1');
  createShipment('o1', 't2');
  assert.equal(shipmentsForOrder('o1').length, 2);
});
