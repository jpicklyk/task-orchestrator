// Shared REST plumbing for plugin hooks that talk to the Task Orchestrator REST API
// (config-sync.mjs, plan-capture.mjs, phase-guard.mjs, phase-guard-record.mjs). Pure module —
// no side effects at import time, no top-level I/O — safe to import from anywhere.
//
// Requires (all optional — absent = the caller no-ops):
//   TASK_ORCHESTRATOR_API_URL    base URL of the REST API, e.g. http://localhost:3001. Falls back to
//                                `apiUrl` in client.json beside the located project config (loopback
//                                hosts only), then in <home>/.taskorchestrator/client.json (home =
//                                TASK_ORCHESTRATOR_HOME else os.homedir()) when unset/empty.
//   TASK_ORCHESTRATOR_API_TOKEN  bearer token. Capability requirements are per-endpoint (e.g.
//                                config-sync needs WRITE_CONFIG, the phase guard needs READ) —
//                                this module has no opinion on which. Optional: an
//                                unauthenticated server (API_AUTH_MODE=none +
//                                API_ALLOW_UNAUTHENTICATED=true) needs no token at all — when
//                                absent, requests are sent with no Authorization header.

import { readFileSync } from 'fs';
import { userClientPath, projectClientPath } from './config-locator.mjs';

const DEFAULT_TIMEOUT_MS = 2000;

/** `apiUrl` from a client.json (UTF-8 BOM tolerated, trimmed, whitespace-only = absent, trailing slashes stripped), or null. Never throws. */
function readApiUrl(path) {
  try {
    let text = readFileSync(path, 'utf8');
    if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
    const parsed = JSON.parse(text);
    const url = parsed && typeof parsed === 'object' && typeof parsed.apiUrl === 'string' ? parsed.apiUrl.trim() : '';
    if (url) return url.replace(/\/+$/, '');
  } catch {
    // missing or invalid client.json
  }
  return null;
}

/**
 * True only for an http(s) URL without credentials whose host, per the WHATWG URL parser, is
 * `localhost`, `[::1]`, or an IPv4 literal in 127.0.0.0/8. Never throws. The slash is appended
 * because callers request `${base}/api/...`, so the authority is parsed as those requests see it.
 */
export function isLoopbackApiUrl(base) {
  try {
    if (typeof base !== 'string') return false;
    const u = new URL(base + '/');
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return false;
    if (u.username !== '' || u.password !== '') return false;
    const h = u.hostname;
    if (h === 'localhost' || h === '[::1]') return true;
    return /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(h);
  } catch {
    return false;
  }
}

/**
 * Base URL with trailing slash(es) stripped. Order: non-empty TASK_ORCHESTRATOR_API_URL; `apiUrl` in
 * the project-level client.json beside the located project config (honoured only when
 * isLoopbackApiUrl — a repo file must not redirect the token to a remote host); `apiUrl` in the
 * user-level client.json. `null` when none yields a value — callers treat `null` as "no REST API
 * configured, no-op". Never throws. The token is env-only; client.json is never read for a token.
 */
export function apiBaseUrl({ cwd = process.cwd(), env = process.env } = {}) {
  const raw = env.TASK_ORCHESTRATOR_API_URL;
  if (raw) return raw.replace(/\/+$/, '');
  try {
    const projectPath = projectClientPath({ cwd, env });
    if (projectPath) {
      const url = readApiUrl(projectPath);
      if (url && isLoopbackApiUrl(url)) return url;
    }
  } catch {
    // fall through to the user-level file
  }
  try {
    return readApiUrl(userClientPath(env));
  } catch {
    return null;
  }
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
