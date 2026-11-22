import { test } from 'node:test';
import assert from 'node:assert/strict';
import { zoneFor } from '../src/shipping/zones.js';

test('zoneFor maps countries to zones', () => {
  assert.equal(zoneFor('US'), 'domestic');
  assert.equal(zoneFor('DE'), 'international');
});
