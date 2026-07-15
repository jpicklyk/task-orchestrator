#!/usr/bin/env node
// SessionStart hook — syncs this workspace's .taskorchestrator/config.yaml into the
// per-root config store over the REST API, so a shared HTTP-transport server picks up
// this project's schemas/traits without a restart.
//
// Fail-open by design: ANY error, missing env, or unreachable API results in exit 0 with
// (at most) a one-line note — it must never block session start. It no-ops entirely for
// stdio/local setups (no API env vars set) where the global config file already serves the
// workspace directly.
//
// Requires (all optional — absent = no-op):
//   TASK_ORCHESTRATOR_API_URL    base URL of the REST API, e.g. http://localhost:3001
//   TASK_ORCHESTRATOR_API_TOKEN  bearer token with the WRITE_CONFIG capability, scoped to this root

import { readFileSync } from 'fs';
import { resolve } from 'path';
import { createHash } from 'crypto';

const REQUEST_TIMEOUT_MS = 2000;

/** Locate .taskorchestrator/config.yaml (AGENT_CONFIG_DIR, then walk up from cwd) and return its RAW bytes. */
function findConfigBytes() {
  const candidates = [];
  if (process.env.AGENT_CONFIG_DIR) {
    candidates.push(resolve(process.env.AGENT_CONFIG_DIR, '.taskorchestrator', 'config.yaml'));
  }
  let dir = process.cwd();
  const fsRoot = resolve(dir, '/');
  while (dir !== fsRoot) {
    candidates.push(resolve(dir, '.taskorchestrator', 'config.yaml'));
    dir = resolve(dir, '..');
  }
  for (const candidate of candidates) {
    try {
      return readFileSync(candidate); // Buffer — hash and PUT the SAME bytes so the fingerprint matches the server's
    } catch {
      continue;
    }
  }
  return null;
}

/** Extract project.rootId from the config text (mirrors session-start.mjs's parser). */
function parseRootId(text) {
  let inProject = false;
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (/^project\s*:\s*$/.test(trimmed)) {
      inProject = true;
      continue;
    }
    if (inProject && /^\S/.test(line)) inProject = false;
    if (inProject) {
      const m = trimmed.match(/^rootId\s*:\s*["']?([^"'#]+?)["']?\s*(#.*)?$/);
      if (m) return m[1].trim();
    }
  }
  return null;
}

function emit(line) {
  process.stdout.write(
    JSON.stringify({ hookSpecificOutput: { hookEventName: 'SessionStart', additionalContext: line } }),
  );
}

function fetchWithTimeout(url, opts) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  return fetch(url, { ...opts, signal: controller.signal }).finally(() => clearTimeout(timer));
}

async function main() {
  if (typeof fetch !== 'function') return; // node < 18 — no global fetch; nothing we can do, stay silent

  const bytes = findConfigBytes();
  if (!bytes) return; // no config file → nothing to sync
  const rootId = parseRootId(bytes.toString('utf-8'));
  if (!rootId) return; // not project-scoped → nothing to sync

  const apiUrl = process.env.TASK_ORCHESTRATOR_API_URL;
  const token = process.env.TASK_ORCHESTRATOR_API_TOKEN;
  if (!apiUrl || !token) return; // stdio/local: the global config file already serves this workspace

  const localEtag = `"cfg-${createHash('sha256').update(bytes).digest('hex')}"`;
  const endpoint = `${apiUrl.replace(/\/+$/, '')}/api/v1/roots/${rootId}/config`;
  const authHeader = { Authorization: `Bearer ${token}` };

  // 1) Read the server's current fingerprint to decide whether a push is needed.
  let currentEtag = null;
  try {
    const res = await fetchWithTimeout(endpoint, { headers: authHeader });
    if (res.status === 200) {
      currentEtag = res.headers.get('etag');
      if (currentEtag && currentEtag === localEtag) {
        emit(`Task Orchestrator: project config already in sync for root ${rootId}.`);
        return;
      }
    } else if (res.status === 404) {
      currentEtag = null; // no row yet — first push is a create
    } else {
      emit(`Task Orchestrator: config sync skipped — GET returned HTTP ${res.status}.`);
      return;
    }
  } catch (err) {
    emit(`Task Orchestrator: config sync skipped — API unreachable (${err?.message ?? err}).`);
    return;
  }

  // 2) Push the exact bytes; If-Match guards against a concurrent update when a row exists.
  try {
    const headers = { ...authHeader, 'Content-Type': 'application/yaml' };
    if (currentEtag) headers['If-Match'] = currentEtag;
    const res = await fetchWithTimeout(endpoint, { method: 'PUT', headers, body: bytes });
    if (res.status === 200) {
      emit(`Task Orchestrator: synced project config to root ${rootId} — per-project schemas/traits are now live.`);
    } else if (res.status === 412) {
      emit(`Task Orchestrator: config sync deferred — root ${rootId} was updated concurrently (412); will retry next session.`);
    } else {
      emit(`Task Orchestrator: config sync failed — PUT returned HTTP ${res.status}.`);
    }
  } catch (err) {
    emit(`Task Orchestrator: config sync failed — ${err?.message ?? err}.`);
  }
}

// Fail-open: swallow every error so the hook always exits 0 and never blocks session start.
main().catch(() => {});
