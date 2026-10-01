// Shared REST plumbing for plugin hooks that talk to the Task Orchestrator REST API
// (config-sync.mjs, plan-capture.mjs, phase-guard.mjs, phase-guard-record.mjs). Pure module —
// no side effects at import time, no top-level I/O — safe to import from anywhere.
//
// Requires (all optional — absent = the caller no-ops):
//   TASK_ORCHESTRATOR_API_URL    base URL of the REST API, e.g. http://localhost:3001. Falls back to
//                                `apiUrl` in <home>/.taskorchestrator/client.json (home =
//                                TASK_ORCHESTRATOR_HOME else os.homedir()) when unset/empty.
//   TASK_ORCHESTRATOR_API_TOKEN  bearer token. Capability requirements are per-endpoint (e.g.
//                                config-sync needs WRITE_CONFIG, the phase guard needs READ) —
//                                this module has no opinion on which. Optional: an
//                                unauthenticated server (API_AUTH_MODE=none +
//                                API_ALLOW_UNAUTHENTICATED=true) needs no token at all — when
//                                absent, requests are sent with no Authorization header.

import { readFileSync } from 'fs';
import { userClientPath } from './config-locator.mjs';

const DEFAULT_TIMEOUT_MS = 2000;

/**
 * Base URL with any trailing slash(es) stripped: a non-empty TASK_ORCHESTRATOR_API_URL wins, else
 * `apiUrl` (UTF-8 BOM tolerated, trimmed, whitespace-only = absent) from the file at userClientPath(). `null` when neither yields a non-empty string
 * (missing/invalid client.json included) — callers treat `null` as "no REST API configured, no-op".
 * Never throws. The token is env-only; client.json is never read for a token.
 */
export function apiBaseUrl() {
  const raw = process.env.TASK_ORCHESTRATOR_API_URL;
  if (raw) return raw.replace(/\/+$/, '');
  try {
    let text = readFileSync(userClientPath(), 'utf8');
    if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
    const parsed = JSON.parse(text);
    const url = parsed && typeof parsed === 'object' && typeof parsed.apiUrl === 'string' ? parsed.apiUrl.trim() : '';
    if (url) return url.replace(/\/+$/, '');
  } catch {
    // missing or invalid client.json
  }
  return null;
}

/** `{Authorization: 'Bearer <token>'}` when TASK_ORCHESTRATOR_API_TOKEN is set, else `{}`. */
export function authHeader() {
  const token = process.env.TASK_ORCHESTRATOR_API_TOKEN;
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/**
 * `fetch()` with an AbortController-backed timeout (default 2000ms). The timeout bounds the whole
 * exchange, response-body reads (`res.json()`/`res.text()`) included: the timer stays armed after
 * headers arrive (unref'd, so it never keeps a finished process alive; aborting after the body was
 * read is a no-op) and is cleared only when the fetch itself rejects.
 */
export function fetchWithTimeout(url, opts, timeoutMs = DEFAULT_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  timer.unref?.();
  return fetch(url, { ...opts, signal: controller.signal }).catch((err) => {
    clearTimeout(timer);
    throw err;
  });
}
