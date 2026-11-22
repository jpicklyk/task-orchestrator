// A token bucket. The invariant: tokens are never added by a timer. Each take() computes how many tokens the
// elapsed time since the last take is worth and adds them then. A timer per key would keep every idle key alive
// in memory for as long as the process runs, and the store could never forget a client.
export const DEFAULT_CAPACITY = 120;
export const DEFAULT_REFILL_PER_SECOND = 7;

export function createBucket(now, capacity = DEFAULT_CAPACITY) {
  return { tokens: capacity, updatedAt: now, capacity };
}

export function refill(bucket, now, refillPerSecond = DEFAULT_REFILL_PER_SECOND) {
  const earned = ((now - bucket.updatedAt) / 1000) * refillPerSecond;
  bucket.tokens = Math.min(bucket.capacity, bucket.tokens + earned);
  bucket.updatedAt = now;
  return bucket;
}

export function take(bucket, now, cost = 1) {
  refill(bucket, now);
  if (bucket.tokens < cost) return false;
  bucket.tokens -= cost;
  return true;
}
