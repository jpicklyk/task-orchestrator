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

import { readFileSync, readdirSync } from 'fs';
import { dirname, join, resolve } from 'path';
import { fileURLToPath } from 'url';
import { createHash } from 'crypto';
import { readSection, scalar } from './yaml-lite.mjs';
import { apiBaseUrl, authHeader as buildAuthHeader, fetchWithTimeout } from './api-client.mjs';

// Mirrors RuleService.KEY_PATTERN (current/.../application/service/RuleService.kt) exactly — a rule
// key must match this server-side grammar or the PUT would be rejected anyway; validated client-side
// so an invalid filename is reported as "skipped" instead of surfacing a failed PUT.
const RULE_KEY_PATTERN = /^[a-z0-9][a-z0-9._-]{0,99}$/;

// Mirrors RuleService.MAX_RULE_BODY_BYTES (server-side 413 threshold) — checked client-side so an
// oversized rule body is never sent at all.
const MAX_RULE_BODY_BYTES = 16384;

/** Locate .taskorchestrator/config.yaml (AGENT_CONFIG_DIR, then walk up from cwd) and return its path + RAW bytes. */
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
      // Buffer — hash and PUT the SAME bytes so the fingerprint matches the server's
      const bytes = readFileSync(candidate);
      return { path: candidate, bytes };
    } catch {
      continue;
    }
  }
  return null;
}

/**
 * Normalizes `buf` for fingerprinting: strips exactly one leading UTF-8 BOM (U+FEFF) if present,
 * then replaces every CRLF (`\r\n`) with LF (`\n`). Nothing else is touched — a lone `\r`,
 * trailing-newline presence/count, trailing whitespace, a non-leading U+FEFF, and a second leading
 * BOM are all left as-is. Mirrors the Kotlin server's `normalizeConfigForFingerprint`
 * (`infrastructure/security/Sha256Hex.kt`) exactly; the two implementations must never diverge.
 * Does not mutate `buf` — decodes to a string, transforms, and re-encodes.
 */
export function normalizeForFingerprint(buf) {
  const text = buf.toString('utf8'); // Node keeps a leading U+FEFF as a real character on decode
  const normalized = text.startsWith('﻿') ? text.slice(1) : text;
  return Buffer.from(normalized.replace(/\r\n/g, '\n'), 'utf8');
}

/** Config-fingerprint hash: lowercase-hex SHA-256 of `normalizeForFingerprint(buf)`. */
export function configFingerprint(buf) {
  return createHash('sha256').update(normalizeForFingerprint(buf)).digest('hex');
}

/** Extract project.rootId from the config text (mirrors session-start.mjs's parser). */
export function parseRootId(text) {
  const section = readSection(text, 'project', { blockOnly: true });
  if (!section) return null;
  return scalar(section.lines, 'rootId');
}

/**
 * Best-effort read of the hook's stdin JSON. Fail-open: an unreadable stream or unparseable
 * body (including no stdin at all, as when this script is run manually) yields null, and the
 * caller treats that exactly like a SessionStart invocation — proceed to the existing sync logic.
 */
function readHookInput() {
  try {
    return JSON.parse(readFileSync(0, 'utf-8'));
  } catch {
    return null;
  }
}

/**
 * True when `filePath` is (some path form of) this workspace's `.taskorchestrator/config.yaml`.
 * Normalizes `\` to `/` first since FileChanged on Windows reports native backslash paths.
 */
export function isTargetConfigPath(filePath) {
  if (typeof filePath !== 'string' || !filePath) return false;
  return filePath.replace(/\\/g, '/').endsWith('.taskorchestrator/config.yaml');
}

function emit(line) {
  process.stdout.write(
    JSON.stringify({ hookSpecificOutput: { hookEventName: 'SessionStart', additionalContext: line } }),
  );
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

/** True when `key` matches the server's rule-key grammar (`RuleService.KEY_PATTERN`). */
export function isValidRuleKey(key) {
  return RULE_KEY_PATTERN.test(key);
}

/** Reads the `*.md` files directly in `rulesDir` (no recursion). Returns `[]` when the dir is absent. */
function listRuleFiles(rulesDir) {
  let entries;
  try {
    entries = readdirSync(rulesDir, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries
    .filter((entry) => entry.isFile() && entry.name.endsWith('.md'))
    .map((entry) => ({ key: entry.name.slice(0, -3), filePath: join(rulesDir, entry.name) }));
}

/**
 * Pure diff between this workspace's local rules and the server's current `rule/<key>` versions.
 * `localRules` is `[{key, bytes}]` where `bytes` is the ALREADY-normalized (`normalizeForFingerprint`)
 * body; `serverVersions` is a `Map<key, rulesVersion>` built from the `GET .../rules` listing.
 *
 * A key failing `isValidRuleKey`, or a body over `MAX_RULE_BODY_BYTES`, is skipped and never hashed
 * or sent. Every other rule is pushed unless the server's `rulesVersion` for that key already equals
 * the local SHA-256 hash of the normalized body.
 *
 * Returns `{ toPush: [{key, bytes}], skipped: [{key, reason}], unchangedCount }`.
 */
export function planRuleSync(localRules, serverVersions) {
  const toPush = [];
  const skipped = [];
  let unchangedCount = 0;
  for (const rule of localRules) {
    if (!isValidRuleKey(rule.key)) {
      skipped.push({ key: rule.key, reason: 'invalid key' });
      continue;
    }
    if (rule.bytes.length > MAX_RULE_BODY_BYTES) {
      skipped.push({ key: rule.key, reason: `over ${MAX_RULE_BODY_BYTES} bytes` });
      continue;
    }
    const hash = createHash('sha256').update(rule.bytes).digest('hex');
    if (serverVersions.get(rule.key) === hash) {
      unchangedCount += 1;
    } else {
      toPush.push({ key: rule.key, bytes: rule.bytes });
    }
  }
  return { toPush, skipped, unchangedCount };
}

/**
 * Renders the rule-sync outcome into the text that follows `Rules: ` in the emitted line (see
 * rule 6 in the spec). `pushedKeys` and `failed`/`skipped` are listed in the order they were
 * processed (the same order `listRuleFiles`/`planRuleSync` produced, i.e. directory order).
 */
export function formatRuleSyncSummary({ pushedKeys, unchangedCount, skipped, failed }) {
  const base =
    pushedKeys.length > 0
      ? `pushed ${pushedKeys.length} (${pushedKeys.join(', ')}); ${unchangedCount} in sync.`
      : `${unchangedCount} in sync.`;
  const clauses = [base];
  if (skipped.length > 0) {
    clauses.push(`skipped: ${skipped.map((s) => `${s.key} (${s.reason})`).join(', ')}`);
  }
  if (failed.length > 0) {
    clauses.push(`failed: ${failed.map((f) => `${f.key} (${f.reason})`).join(', ')}`);
  }
  return clauses.join('; ');
}

/**
 * Syncs `.taskorchestrator/rules/*.md` into the per-root rule store. Returns `null` when there is
 * no `rules/` dir or it holds no `.md` files (silent no-op — the caller then emits the config line
 * unchanged, byte-identical to before this feature existed). Otherwise returns the rule-sync
 * summary text that follows `Rules: ` in the combined line.
 *
 * Any thrown error (a network failure on the GET, or on any PUT that isn't individually caught)
 * propagates to the caller, which renders it as a fail-open "sync failed" summary — this function
 * does not itself guarantee fail-open; `main` provides that guarantee at the call site.
 */
async function syncRules({ rulesDir, base, rootId, auth }) {
  const ruleFiles = listRuleFiles(rulesDir);
  if (ruleFiles.length === 0) return null;

  const localRules = ruleFiles.map(({ key, filePath }) => ({
    key,
    bytes: normalizeForFingerprint(readFileSync(filePath)),
  }));

  const listRes = await fetchWithTimeout(`${base}/api/v1/roots/${rootId}/rules`, { headers: auth });
  if (listRes.status !== 200) {
    return `sync skipped — GET rules returned HTTP ${listRes.status}`;
  }
  let serverRules;
  try {
    const body = await listRes.json();
    serverRules = Array.isArray(body?.rules) ? body.rules : [];
  } catch {
    serverRules = []; // unparseable body — degrade like an empty listing, every rule looks new
  }
  const serverVersions = new Map(serverRules.map((rule) => [rule.key, rule.rulesVersion]));

  const plan = planRuleSync(localRules, serverVersions);

  const pushed = [];
  const failed = [];
  if (plan.toPush.length > 0) {
    const results = await Promise.all(
      plan.toPush.map(async (rule) => {
        const putUrl = `${base}/api/v1/roots/${rootId}/plans/${encodeURIComponent(`rule/${rule.key}`)}`;
        try {
          const res = await fetchWithTimeout(putUrl, {
            method: 'PUT',
            headers: { ...auth, 'Content-Type': 'text/markdown; charset=utf-8' },
            body: rule.bytes,
          });
          if (res.status === 200) return { key: rule.key, ok: true };
          return { key: rule.key, ok: false, reason: `HTTP ${res.status}` };
        } catch (err) {
          return { key: rule.key, ok: false, reason: err?.message ?? String(err) };
        }
      }),
    );
    for (const result of results) {
      if (result.ok) pushed.push(result.key);
      else failed.push({ key: result.key, reason: result.reason });
    }
  }

  return formatRuleSyncSummary({ pushedKeys: pushed, unchangedCount: plan.unchangedCount, skipped: plan.skipped, failed });
}

async function main() {
  if (typeof fetch !== 'function') return; // node < 18 — no global fetch; nothing we can do, stay silent

  // FileChanged fires for every watched path, not just ours. Only config.yaml's own change
  // should trigger a push — a FileChanged event for some other watched file is a silent no-op.
  // SessionStart invocations (and any unreadable/unparseable stdin) fall through unchanged.
  const hookInput = readHookInput();
  if (hookInput?.hook_event_name === 'FileChanged' && !isTargetConfigPath(hookInput.file_path)) {
    return;
  }

  const found = findConfigBytes();
  if (!found) return; // no config file → nothing to sync
  const { path: configPath, bytes } = found;
  const rootId = parseRootId(bytes.toString('utf-8'));
  if (!rootId) return; // not project-scoped → nothing to sync

  const base = apiBaseUrl();
  if (!base) return; // stdio/local: the global config file already serves this workspace

  // Token is optional — an unauthenticated server (API_AUTH_MODE=none +
  // API_ALLOW_UNAUTHENTICATED=true) needs no Authorization header at all.
  const localFingerprint = configFingerprint(bytes);
  const localEtag = `"cfg-${localFingerprint}"`;
  const endpoint = `${base}/api/v1/roots/${rootId}/config`;
  const auth = buildAuthHeader();

  // rules/ sits next to the config.yaml actually found — not cwd (AGENT_CONFIG_DIR or a
  // cwd-walk ancestor may differ from cwd).
  const rulesDir = join(dirname(configPath), 'rules');

  // configLine is set by whichever branch below decides the config-sync outcome; the rules step
  // (steps 3+) runs afterward for every branch EXCEPT the two early-return cases (API unreachable,
  // GET returned a status other than 200/404) — those `return` directly, exactly as before this
  // change, so the rules step never has a chance to run for them.
  let configLine;

  // 1) Read the server's current fingerprint — and, via ?fingerprint=, how our local fingerprint
  //    relates to its history (fast-forward guard) — to decide whether a push is needed.
  let currentEtag = null;
  try {
    const res = await fetchWithTimeout(`${endpoint}?fingerprint=${localFingerprint}`, { headers: auth });
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
          configLine = `Task Orchestrator: project config already in sync for root ${rootId}.`;
        } else if (decision.action === 'skip-superseded') {
          configLine =
            `Task Orchestrator: config sync skipped — your checkout's config.yaml is older than the ` +
            `server's (updated ${decision.updatedAt ?? 'unknown'}); pull or copy back before editing.`;
        }
        // decision.action === 'push' ('unknown' relation) — configLine stays unset, fall through to step 2.
      } else if (currentEtag && currentEtag === localEtag) {
        // Degrade path: an older server with no `relation` field at all — fall back to the
        // client-side etag compare instead of blocking on an ambiguous relation.
        configLine = `Task Orchestrator: project config already in sync for root ${rootId}.`;
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
  // Skipped when step 1 already decided the outcome (already-in-sync / skip-superseded above).
  if (configLine === undefined) {
    try {
      const headers = { ...auth, 'Content-Type': 'application/yaml' };
      if (currentEtag) headers['If-Match'] = currentEtag;
      const res = await fetchWithTimeout(endpoint, { method: 'PUT', headers, body: bytes });
      if (res.status === 200) {
        let schemaWarnings;
        try {
          const body = await res.json();
          schemaWarnings = body?.schemaWarnings;
        } catch {
          schemaWarnings = undefined; // unparseable body — degrade like no warnings reported
        }
        const syncedLine = `Task Orchestrator: synced project config to root ${rootId} — per-project schemas/traits are now live.`;
        configLine =
          Array.isArray(schemaWarnings) && schemaWarnings.length > 0
            ? `${syncedLine} WARNINGS: ${schemaWarnings.join(' | ')}`
            : syncedLine;
      } else if (res.status === 412) {
        configLine = `Task Orchestrator: config sync deferred — root ${rootId} was updated concurrently (412); will retry next session.`;
      } else {
        configLine = `Task Orchestrator: config sync failed — PUT returned HTTP ${res.status}.`;
      }
    } catch (err) {
      configLine = `Task Orchestrator: config sync failed — ${err?.message ?? err}.`;
    }
  }

  // 3) Push this workspace's git-tracked rule files (.taskorchestrator/rules/*.md) into the
  //    per-root rule store. Fail-open: any exception here is swallowed into a "sync failed" line
  //    rather than affecting the config line above or the process exit code.
  let rulesSummary;
  try {
    rulesSummary = await syncRules({ rulesDir, base, rootId, auth });
  } catch (err) {
    rulesSummary = `sync failed — ${err?.message ?? err}`;
  }

  emit(rulesSummary ? `${configLine} Rules: ${rulesSummary}` : configLine);
}

// Only auto-run when invoked directly as a hook (`node config-sync.mjs`), not when imported
// (e.g. by a test importing `parseRootId` for direct unit coverage) — importing must never
// trigger a live sync attempt as a side effect.
// Fail-open: swallow every error so the hook always exits 0 and never blocks session start.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => {});
}
