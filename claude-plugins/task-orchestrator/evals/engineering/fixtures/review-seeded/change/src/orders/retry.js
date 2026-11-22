export async function withRetry(fn, attempts = 3) {
  for (let i = 0; i < attempts; i += 1) {
    try {
      return await fn();
    } catch (e) {
      // try again
    }
  }
  return null;
}
