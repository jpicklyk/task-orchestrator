export const DEFAULT_TIMEOUT_MS = 30000;

export async function call(url, init = {}, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const res = await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
  if (!res.ok) throw new Error(`call failed: ${res.status}`);
  return res.json();
}

export async function retry(fn, attempts = 3) {
  let last;
  for (let i = 0; i < attempts; i += 1) {
    try {
      return await fn();
    } catch (e) {
      last = e;
    }
  }
  throw last;
}
