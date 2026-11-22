import { take } from './bucket.js';
import { createStore, bucketFor, sweep } from './store.js';

export function createLimiter(clock = Date.now) {
  const store = createStore();
  return {
    allow(key, cost = 1) {
      return take(bucketFor(store, key, clock()), clock(), cost);
    },
    sweep() {
      return sweep(store, clock());
    },
  };
}
