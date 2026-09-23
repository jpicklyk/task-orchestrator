// Shared REST plumbing for plugin hooks that talk to the Task Orchestrator REST API
// (config-sync.mjs, plan-capture.mjs, phase-guard.mjs, phase-guard-record.mjs). Pure module —
// no side effects at import time, no top-level I/O — safe to import from anywhere.
//
// Requires (all optional — absent = the caller no-ops):
//   TASK_ORCHESTRATOR_API_URL    base URL of the REST API, e.g. http://localhost:3001
//   TASK_ORCHESTRATOR_API_TOKEN  bearer token. Capability requirements are per-endpoint (e.g.
//                                config-sync needs WRITE_CONFIG, the phase guard needs READ) —
//                                this module has no opinion on which. Optional: an
//                                unauthenticated server (API_AUTH_MODE=none +
//                                API_ALLOW_UNAUTHENTICATED=true) needs no token at all — when
//                                absent, requests are sent with no Authorization header.

const DEFAULT_TIMEOUT_MS = 2000;

/**
 * Base URL from TASK_ORCHESTRATOR_API_URL with any trailing slash(es) stripped, or `null` when
 * the env var is unset/empty — callers treat `null` as "no REST API configured, no-op".
 */
export function apiBaseUrl() {
  const raw = process.env.TASK_ORCHESTRATOR_API_URL;
  if (!raw) return null;
  return raw.replace(/\/+$/, '');
}

/** `{Authorization: 'Bearer <token>'}` when TASK_ORCHESTRATOR_API_TOKEN is set, else `{}`. */
export function authHeader() {
  const token = process.env.TASK_ORCHESTRATOR_API_TOKEN;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** `fetch()` with an AbortController-backed timeout (default 2000ms). */
export function fetchWithTimeout(url, opts, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  return fetch(url, { ...opts, signal: controller.signal }).finally(() => clearTimeout(timer));
}
