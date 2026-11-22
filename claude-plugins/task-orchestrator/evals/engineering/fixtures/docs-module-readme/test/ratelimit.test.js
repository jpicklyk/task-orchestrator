import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createLimiter } from '../src/ratelimit/index.js';

test('a key is limited after its bucket is empty', () => {
  const t = 0;
  const limiter = createLimiter(() => t);
  let allowed = 0;
  for (let i = 0; i < 130; i += 1) if (limiter.allow('a')) allowed += 1;
  assert.equal(allowed, 120);
});
