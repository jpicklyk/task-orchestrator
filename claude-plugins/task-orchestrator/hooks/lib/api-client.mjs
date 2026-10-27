// Shared REST plumbing (4.0 client: callOp / wellKnown plus the URL, token and timeout helpers) for plugin hooks that talk to the Task Orchestrator REST API
// (config-sync.mjs, plan-capture.mjs, phase-guard.mjs, phase-guard-record.mjs). Pure module —
// no side effects at import time, no top-level I/O — safe to import from anywhere.
//
// Requires (all optional — absent = the caller no-ops):
//   TASK_ORCHESTRATOR_API_URL    base URL of the REST API, e.g. http://localhost:3001. Falls back to
//                                `apiUrl` in client.json beside the located project config, then (linked
//                                worktree) in the main checkout's .taskorchestrator/client.json (bare loopback
//                                origins only — no path, query or fragment; first usable wins), then in <home>/.taskorchestrator/client.json (home =
//                                TASK_ORCHESTRATOR_HOME else os.homedir()) when unset/empty. Between the project and
//                                user client.json steps the origin of the orchestrator's http(s) MCP registration
//                                (local ~/.claude.json project entry > .mcp.json > user ~/.claude.json; a
//                                .mcp.json origin must be loopback) is used, so the MCP endpoint is the one
//                                place to change the server; see lib/mcp-registrations.mjs.
//   TASK_ORCHESTRATOR_API_TOKEN  bearer token. Capability requirements are per-op (config-sync needs
//                                read + write-config + write-docs, plan-capture write-docs, the phase
//                                guard read) — this module has no opinion on which. Optional: an
//                                unauthenticated server (API_AUTH_MODE=none +
//                                API_ALLOW_UNAUTHENTICATED=true) needs no token at all — when
//                                absent, requests are sent with no Authorization header.

import { readFileSync } from 'fs';
import { parseToolResult } from './envelope.mjs';
import { userClientPath, projectClientCandidates } from '../config-locator.mjs';
import { preferredHttpRegistration } from './mcp-registrations.mjs';

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
 * True only for a bare loopback ORIGIN: an http(s) URL without credentials whose host, per the WHATWG
 * URL parser, is `localhost`, `[::1]`, or an IPv4 literal in 127.0.0.0/8, with an empty query and
 * fragment and the path `/`. Never throws. The trailing slash run is removed and one slash appended
 * before parsing because callers request `${base}/api/...`: the parser then sees the authority, and
 * any query, fragment or path, exactly as those requests present them.
 */
export function isLoopbackApiUrl(base) {
  try {
    if (typeof base !== 'string') return false;
    const b = base.replace(/\/+$/, '');
    const u = new URL(b + '/');
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return false;
    if (u.username !== '' || u.password !== '') return false;
    if (u.search !== '' || u.hash !== '' || u.pathname !== '/') return false;
    const h = u.hostname;
    if (h === 'localhost' || h === '[::1]') return true;
    return /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(h);
  } catch {
    return false;
  }
}

/**
 * Base URL plus where it came from: `{url, source}` or null. Order: non-empty TASK_ORCHESTRATOR_API_URL
 * (source `env`); `apiUrl` in the project-level client.json files from projectClientCandidates (beside
 * the located project config, then the main checkout's for a linked worktree; each honoured only when
 * isLoopbackApiUrl, else the next is tried; a bare origin only -- a repo file must not redirect the
 * token to a remote host or shape the request target; source `project-client`); the origin of the
 * orchestrator MCP registration (source `mcp:<key>@<scope>`; local > project > user scope; a project
 * `.mcp.json` origin must be loopback, the user-owned scopes may be remote); `apiUrl` in the
 * user-level client.json (source `user-client`). Never throws.
 */
export function apiBaseUrlInfo({ cwd = process.cwd(), env = process.env } = {}) {
  const raw = env.TASK_ORCHESTRATOR_API_URL;
  if (raw) return { url: raw.replace(/\/+$/, ''), source: 'env' };
  try {
    for (const projectPath of projectClientCandidates({ cwd, env })) {
      try {
        const url = readApiUrl(projectPath);
        if (url && isLoopbackApiUrl(url)) return { url, source: 'project-client' };
      } catch {
        // try the next candidate
      }
    }
  } catch {
    // fall through
  }
  try {
    const reg = preferredHttpRegistration({ cwd, env, isAllowed: isLoopbackApiUrl });
    if (reg) return { url: reg.origin, source: `mcp:${reg.key}@${reg.scope}` };
  } catch {
    // fall through to the user-level file
  }
  try {
    const url = readApiUrl(userClientPath(env));
    return url ? { url, source: 'user-client' } : null;
  } catch {
    return null;
  }
}

/** Base URL with trailing slash(es) stripped, or `null` ("no REST API configured, callers no-op"). See apiBaseUrlInfo. Never throws. The token is env-only. */
export function apiBaseUrl(opts = {}) {
  const info = apiBaseUrlInfo(opts);
  return info ? info.url : null;
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

const TOOL_NAME_RE = /^[a-z][a-z_]*$/;

function clientError(reason, kind, httpStatus, status) {
  const detail = status === undefined ? { reason } : { reason, status };
  return {
    ok: false,
    legacy: false,
    data: null,
    error: { code: 'client_error', kind, message: 'client_error: ' + reason, retryAfterMs: null, detail, fix: null, index: null },
    results: [],
    summary: null,
    reqId: null,
    httpStatus,
  };
}

function unauthenticated(httpStatus) {
  return {
    ok: false,
    legacy: false,
    data: null,
    error: { code: 'unauthenticated', kind: 'permanent', message: 'unauthenticated', retryAfterMs: null, detail: null, fix: null, index: null },
    results: [],
    summary: null,
    reqId: null,
    httpStatus,
  };
}

/**
 * POST ${base}/api/v1/ops/${tool} with `body`; resolves to the parsed envelope plus `httpStatus`, or
 * null when no base URL is configured (no request is made). Never rejects.
 */
export async function callOp(tool, body, { timeoutMs = DEFAULT_TIMEOUT_MS, baseUrl } = {}) {
  try {
    const base = baseUrl === undefined || baseUrl === null ? apiBaseUrl() : baseUrl;
    if (!base) return null;
    if (typeof tool !== 'string' || !TOOL_NAME_RE.test(tool)) return clientError('invalid_tool', 'permanent', null);
    let res;
    let text;
    try {
      res = await fetchWithTimeout(
        `${String(base).replace(/\/+$/, '')}/api/v1/ops/${tool}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...authHeader() },
          body: JSON.stringify(body ?? {}),
        },
        timeoutMs,
      );
      text = await res.text();
    } catch (err) {
      return clientError(err && err.name === 'AbortError' ? 'timeout' : 'network', 'transient', null);
    }
    let parsed = null;
    try {
      parsed = parseToolResult(JSON.parse(text));
    } catch {
      parsed = null;
    }
    if (parsed) return { ...parsed, httpStatus: res.status };
    if (res.status === 401) return unauthenticated(res.status);
    return clientError('bad_body', 'transient', res.status, res.status);
  } catch {
    return clientError('network', 'transient', null);
  }
}

/** Unauthenticated GET of the well-known document: a plain object on 200 + JSON, else null. Never rejects. */
export async function wellKnown({ timeoutMs = DEFAULT_TIMEOUT_MS, baseUrl } = {}) {
  try {
    const base = baseUrl === undefined || baseUrl === null ? apiBaseUrl() : baseUrl;
    if (!base) return null;
    const res = await fetchWithTimeout(`${String(base).replace(/\/+$/, '')}/.well-known/mcp-task-orchestrator.json`, { method: 'GET' }, timeoutMs);
    if (res.status !== 200) return null;
    const doc = JSON.parse(await res.text());
    return doc !== null && typeof doc === 'object' && !Array.isArray(doc) ? doc : null;
  } catch {
    return null;
  }
}
