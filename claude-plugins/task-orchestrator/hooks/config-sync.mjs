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
//   TASK_ORCHESTRATOR_API_TOKEN  bearer token with the WRITE_CONFIG capability, scoped to this root.
//                                Optional: an unauthenticated server (API_AUTH_MODE=none +
//                                API_ALLOW_UNAUTHENTICATED=true) needs no token at all — when
//                                absent, requests are sent with no Authorization header.

import { readFileSync } from 'fs';
import { resolve } from 'path';
import { fileURLToPath } from 'url';
import { createHash } from 'crypto';
import { readSection, scalar } from './yaml-lite.mjs';

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
export function parseRootId(text) {
  const section = readSection(text, 'project', { blockOnly: true });
  if (!section) return null;
  return scalar(section.lines, 'rootId');
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

/**
 * Decides what to do with the GET response's `relation` field (fast-forward guard — see
 * ProjectConfigRoutes.kt's `?fingerprint=` handling). Pure function, kept separate from `main` so
 * the branch logic is unit-inspectable without a JS test harness (this repo has none for the
 * plugin hooks).
 *
 * `relation` is the SOLE authority whenever present — callers must NOT also consult the
 * etag-compare result in that case (see `main`'s call site). Only when `relation` is absent
 * entirely (an older server that predates this guard) does the caller fall back to comparing
 * etags itself.
 *
 * - "current": already in sync, no push needed.
 * - "superseded": this checkout's config.yaml is known-old (a later push happened elsewhere) —
 *   skip the push rather than silently reverting that later change.
 * - "unknown": divergent edit (or no server-side history yet) — push.
 *
 * Returns one of: "already-in-sync" | "skip-superseded" | "push".
 */
function decideSyncAction(relation, updatedAt) {
  if (relation === 'current') return { action: 'already-in-sync' };
  if (relation === 'superseded') return { action: 'skip-superseded', updatedAt };
  return { action: 'push' }; // 'unknown' relation — a divergent edit, or no history yet — push.
}

async function main() {
  if (typeof fetch !== 'function') return; // node < 18 — no global fetch; nothing we can do, stay silent

  const bytes = findConfigBytes();
  if (!bytes) return; // no config file → nothing to sync
  const rootId = parseRootId(bytes.toString('utf-8'));
  if (!rootId) return; // not project-scoped → nothing to sync

  const apiUrl = process.env.TASK_ORCHESTRATOR_API_URL;
  if (!apiUrl) return; // stdio/local: the global config file already serves this workspace

  // Token is optional — an unauthenticated server (API_AUTH_MODE=none +
  // API_ALLOW_UNAUTHENTICATED=true) needs no Authorization header at all.
  const token = process.env.TASK_ORCHESTRATOR_API_TOKEN;

  const localFingerprint = createHash('sha256').update(bytes).digest('hex');
  const localEtag = `"cfg-${localFingerprint}"`;
  const endpoint = `${apiUrl.replace(/\/+$/, '')}/api/v1/roots/${rootId}/config`;
  const authHeader = token ? { Authorization: `Bearer ${token}` } : {};

  // 1) Read the server's current fingerprint — and, via ?fingerprint=, how our local fingerprint
  //    relates to its history (fast-forward guard) — to decide whether a push is needed.
  let currentEtag = null;
  try {
    const res = await fetchWithTimeout(`${endpoint}?fingerprint=${localFingerprint}`, { headers: authHeader });
    if (res.status === 200) {
      currentEtag = res.headers.get('etag');

      let relation;
      let updatedAt;
      try {
        const body = await res.json();
        relation = body?.relation;
        updatedAt = body?.updatedAt;
      } catch {
        relation = undefined; // unparseable body — degrade like an absent relation field
      }

      if (relation !== undefined) {
        // relation is the sole authority when the server provides it — the client-side etag
        // compare below is only ever consulted as the degrade path for an older server that
        // predates this field (see the `else if` branch).
        const decision = decideSyncAction(relation, updatedAt);
        if (decision.action === 'already-in-sync') {
          emit(`Task Orchestrator: project config already in sync for root ${rootId}.`);
          return;
        }
        if (decision.action === 'skip-superseded') {
          emit(
            `Task Orchestrator: config sync skipped — your checkout's config.yaml is older than the ` +
              `server's (updated ${decision.updatedAt ?? 'unknown'}); pull or copy back before editing.`,
          );
          return;
        }
        // decision.action === 'push' ('unknown' relation) — fall through to step 2.
      } else if (currentEtag && currentEtag === localEtag) {
        // Degrade path: an older server with no `relation` field at all — fall back to the
        // client-side etag compare instead of blocking on an ambiguous relation.
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

// Only auto-run when invoked directly as a hook (`node config-sync.mjs`), not when imported
// (e.g. by a test importing `parseRootId` for direct unit coverage) — importing must never
// trigger a live sync attempt as a side effect.
// Fail-open: swallow every error so the hook always exits 0 and never blocks session start.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => {});
}
