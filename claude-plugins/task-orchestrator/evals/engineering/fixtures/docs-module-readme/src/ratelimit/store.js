import { createBucket } from './bucket.js';

export const DEFAULT_IDLE_TTL_SECONDS = 900;

export function createStore(idleTtlSeconds = DEFAULT_IDLE_TTL_SECONDS) {
  return { buckets: new Map(), idleTtlSeconds };
}

export function bucketFor(store, key, now) {
  let bucket = store.buckets.get(key);
  if (!bucket) {
    bucket = createBucket(now);
    store.buckets.set(key, bucket);
  }
  return bucket;
}

export function sweep(store, now) {
  for (const [key, bucket] of store.buckets) {
    if (now - bucket.updatedAt > store.idleTtlSeconds * 1000) store.buckets.delete(key);
  }
  return store.buckets.size;
}
