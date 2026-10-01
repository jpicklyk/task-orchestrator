#!/usr/bin/env node
// SessionStart/FileChanged hook — syncs the located .taskorchestrator/config.yaml (project scope,
// or the user-level config in user scope — see config-locator.mjs) into the per-root config store
// over the REST API, so a shared HTTP-transport server picks up its schemas/traits without a
// restart. Then syncs the rules/*.md next to that config and, on SessionStart only, the plugin's
// bundled rules (bundled-rules/, governed by bundled-rules/manifest.json).
//
// All requests share ONE deadline measured from hook start (SESSION_START_BUDGET_MS /
// FILE_CHANGED_BUDGET_MS, kept below the hooks-config.json timeouts); each request's own timeout is
// min(PER_REQUEST_TIMEOUT_MS, time remaining), and no request is sent once the deadline has passed.
//
// Fail-open by design: ANY error, missing env, or unreachable API results in exit 0 with
// (at most) a one-line note — it must never block session start. It no-ops entirely for
// stdio/local setups (no API env vars set) where the global config file already serves the
// workspace directly.
//
// Requires (all optional — absent = no-op):
//   TASK_ORCHESTRATOR_API_URL    base URL of the REST API, e.g. http://localhost:3001 (falls back
//                                to apiUrl in a project-level, then the user-level client.json — see api-client.mjs)
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
import { locateConfig, isValidRootId } from './config-locator.mjs';

// Mirrors RuleService.KEY_PATTERN (current/.../application/service/RuleService.kt) exactly — a rule
// key must match this server-side grammar or the PUT would be rejected anyway; validated client-side
// so an invalid filename is reported as "skipped" instead of surfacing a failed PUT.
const RULE_KEY_PATTERN = /^[a-z0-9][a-z0-9._-]{0,99}$/;

// Mirrors RuleService.MAX_RULE_BODY_BYTES (server-side 413 threshold) — checked client-side so an
// oversized rule body is never sent at all.
const MAX_RULE_BODY_BYTES = 16384;

// Shared deadline budgets (ms from hook start). hooks-config.json kills the hook at 10s / 5s.
const SESSION_START_BUDGET_MS = 8000;
const FILE_CHANGED_BUDGET_MS = 3500;
const PER_REQUEST_TIMEOUT_MS = 2000;

// Plugin-shipped rule bodies + manifest.json ({key: [hash, ...]}, oldest first, current last).
const BUNDLED_RULES_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'bundled-rules');

/** Rejection for a request that was never sent because the shared deadline had already passed. */
class DeadlineExpired extends Error {
  constructor() {
    super('deadline expired');
    this.name = 'DeadlineExpired';
  }
}

/**
 * The shared deadline. `fetch` uses timeout = min(PER_REQUEST_TIMEOUT_MS, remaining) and rejects
 * with DeadlineExpired, without sending, when remaining <= 0. A request is deadline-capped when
 * remaining <= PER_REQUEST_TIMEOUT_MS at SEND time; that is decided once, at send, never from the
 * clock when an error is caught. `isDeadlineError(err)` is true for the not-sent rejection and for
 * an AbortError raised by a deadline-capped request (in `fetch` or in `json` on its response).
 * `json(res)` reads the body (bounded by the same timer, see fetchWithTimeout).
 */
export function makeDeadline(budgetMs, startedAt, { now = Date.now, fetchImpl = fetchWithTimeout } = {}) {
  const remaining = () => budgetMs - (now() - startedAt);
  const deadlineErrors = new WeakSet();
  const cappedResponses = new WeakSet();
  const mark = (err) => {
    if (err && typeof err === 'object' && err.name === 'AbortError') deadlineErrors.add(err);
  };
  return {
    remaining,
    async fetch(url, opts) {
      const rem = remaining();
      if (rem <= 0) throw new DeadlineExpired();
      const capped = rem <= PER_REQUEST_TIMEOUT_MS;
      let res;
      try {
        res = await fetchImpl(url, opts, Math.min(PER_REQUEST_TIMEOUT_MS, rem));
      } catch (err) {
        if (capped) mark(err);
        throw err;
      }
      if (capped && res && typeof res === 'object') cappedResponses.add(res);
      return res;
    },
    async json(res) {
      try {
        return await res.json();
      } catch (err) {
        if (res && typeof res === 'object' && cappedResponses.has(res)) mark(err);
        throw err;
      }
    },
    isDeadlineError(err) {
      if (err instanceof DeadlineExpired) return true;
      return !!err && typeof err === 'object' && deadlineErrors.has(err);
    },
  };
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

/** Extract project.rootId from the config text; null when absent or not a plain token (isValidRootId). */
export function parseRootId(text) {
  const section = readSection(text, 'project', { blockOnly: true });
  if (!section) return null;
  const id = scalar(section.lines, 'rootId');
  return isValidRootId(id) ? id : null;
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
 * Pure push policy for the plugin's bundled rules. `bundled` is `[{key, bytes}]` (bytes already
 * normalized), `manifest` is `{key: [hash, ...]}` (every hash this plugin has shipped for the key),
 * `serverVersions` a `Map<key, rulesVersion>`, `workspaceKeys` a `Set` of the keys the workspace's
 * own rules/ dir provides. Per bundled key:
 * - in workspaceKeys -> omitted from every list (the workspace rule wins; planRuleSync handles it);
 * - absent from serverVersions -> push, reason 'absent';
 * - server hash == sha256(bytes) -> inSync;
 * - server hash is a non-current hash listed in manifest[key] (an older version this plugin
 *   shipped) -> push, reason 'known-old';
 * - anything else (unknown hash, null, empty string, non-string) -> untouched, never pushed.
 *
 * Returns `{ toPush: [{key, bytes, reason}], inSync: [key], untouched: [key] }`.
 */
export function planBundledSync({ bundled, manifest, serverVersions, workspaceKeys }) {
  const toPush = [];
  const inSync = [];
  const untouched = [];
  for (const rule of bundled) {
    if (workspaceKeys.has(rule.key)) continue;
    if (!serverVersions.has(rule.key)) {
      toPush.push({ key: rule.key, bytes: rule.bytes, reason: 'absent' });
      continue;
    }
    const serverHash = serverVersions.get(rule.key);
    const current = createHash('sha256').update(rule.bytes).digest('hex');
    const shipped = Array.isArray(manifest?.[rule.key]) ? manifest[rule.key] : [];
    if (serverHash === current) {
      inSync.push(rule.key);
    } else if (typeof serverHash === 'string' && serverHash !== '' && shipped.includes(serverHash)) {
      toPush.push({ key: rule.key, bytes: rule.bytes, reason: 'known-old' });
    } else {
      untouched.push(rule.key);
    }
  }
  return { toPush, inSync, untouched };
}

/**
 * Reads bundled-rules/manifest.json and the `<key>.md` body for each manifest key (normalized).
 * Fail-open: an unreadable or invalid manifest yields no bundled rules; a key whose body cannot be
 * read is dropped.
 */
function loadBundledRules(dir = BUNDLED_RULES_DIR) {
  const empty = { bundled: [], manifest: {} };
  let manifest;
  try {
    manifest = JSON.parse(readFileSync(join(dir, 'manifest.json'), 'utf-8'));
  } catch {
    return empty;
  }
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) return empty;
  const bundled = [];
  for (const key of Object.keys(manifest)) {
    if (!isValidRuleKey(key)) continue;
    try {
      bundled.push({ key, bytes: normalizeForFingerprint(readFileSync(join(dir, `${key}.md`))) });
    } catch {
      // body missing — skip this key
    }
  }
  return { bundled, manifest };
}

/**
 * Renders the rule-sync outcome into the text that follows `Rules: ` in the emitted line (see
 * rule 6 in the spec). `pushedKeys` and `failed`/`skipped` are listed in the order they were
 * processed (the PUT queue order). Optional `deferredCount` (default 0) counts PUTs not sent, or
 * aborted, because the shared deadline expired; when > 0 it adds `N deferred to next session`.
 */
export function formatRuleSyncSummary({
  pushedKeys,
  unchangedCount,
  skipped,
  failed,
  deferredCount = 0,
  untouchedKeys = [],
  bundledListingUnusable = false,
}) {
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
  if (untouchedKeys.length > 0) {
    clauses.push(`kept unrecognized server copy: ${untouchedKeys.join(', ')}`);
  }
  if (bundledListingUnusable) {
    clauses.push('bundled rules skipped (unusable rules listing)');
  }
  if (deferredCount > 0) {
    clauses.push(`${deferredCount} deferred to next session`);
  }
  return clauses.join('; ');
}

/**
 * Syncs the `rules/*.md` next to the located config and, when `includeBundled` (SessionStart), the
 * plugin's bundled rules into the per-root rule store. Returns `null` when there is nothing to
 * consider (no workspace rule files and no bundled sync) — the caller then emits the config line
 * unchanged. Otherwise returns the summary text that follows `Rules: `.
 *
 * PUT queue order: every `protocol.`-prefixed key first, then the remaining workspace keys, then
 * the remaining bundled keys. PUTs are sequential. A PUT not sent, or aborted, because the shared
 * deadline expired counts as deferred; any other PUT failure is reported under `failed`.
 *
 * A thrown error from the GET listing (other than the deadline) propagates to the caller, which
 * renders it as a fail-open "sync failed" summary — `main` provides the fail-open guarantee.
 */
async function syncRules({ rulesDir, base, rootId, auth, deadline, includeBundled, rootConfirmed = false }) {
  const ruleFiles = listRuleFiles(rulesDir);
  const loaded = includeBundled ? loadBundledRules() : { bundled: [], manifest: {} };
  let { bundled } = loaded;
  const { manifest } = loaded;
  if (ruleFiles.length === 0 && bundled.length === 0) return null;

  const localRules = ruleFiles.map(({ key, filePath }) => ({
    key,
    bytes: normalizeForFingerprint(readFileSync(filePath)),
  }));

  let listRes;
  try {
    listRes = await deadline.fetch(`${base}/api/v1/roots/${rootId}/rules`, { headers: auth });
  } catch (err) {
    if (deadline.isDeadlineError(err)) return 'sync deferred to next session';
    throw err;
  }
  if (listRes.status === 404) {
    if (!rootConfirmed) return `root ${rootId} not found on this server`;
    // The root exists (config GET/PUT just confirmed it), so the missing route means an older
    // server with no rules API: say nothing unless workspace rules could not be synced.
    return ruleFiles.length > 0 ? 'not synced — this server has no rules API.' : null;
  }
  if (listRes.status !== 200) {
    return `sync skipped — GET rules returned HTTP ${listRes.status}`;
  }
  let serverRules = [];
  let bundledListingUnusable = false;
  try {
    const body = await deadline.json(listRes);
    if (body && typeof body === 'object' && Array.isArray(body.rules)) {
      serverRules = body.rules.filter((rule) => rule && typeof rule === 'object' && typeof rule.key === 'string');
    } else {
      bundledListingUnusable = true;
    }
  } catch (err) {
    if (deadline.isDeadlineError(err)) return 'sync deferred to next session';
    bundledListingUnusable = true; // unparseable body — never guess which bundled keys are absent
  }
  if (bundledListingUnusable) bundled = [];
  const serverVersions = new Map(serverRules.map((rule) => [rule.key, rule.rulesVersion]));

  const plan = planRuleSync(localRules, serverVersions);
  const bundledPlan = planBundledSync({
    bundled,
    manifest,
    serverVersions,
    workspaceKeys: new Set(localRules.map((rule) => rule.key)),
  });

  const isProtocol = (rule) => rule.key.startsWith('protocol.');
  const candidates = [...plan.toPush, ...bundledPlan.toPush];
  const queue = [...candidates.filter(isProtocol), ...candidates.filter((rule) => !isProtocol(rule))];

  const pushed = [];
  const failed = [];
  let deferredCount = 0;
  // Pushed sequentially (not Promise.all) — parallel PUTs against the same root race the
  // server's SQLite writer and surface as SQLITE_BUSY_SNAPSHOT 500s (see diagnosis on
  // item 1a400d81). One rule at a time keeps this hook converging in a single run.
  for (const rule of queue) {
    const putUrl = `${base}/api/v1/roots/${rootId}/plans/${encodeURIComponent(`rule/${rule.key}`)}`;
    try {
      const res = await deadline.fetch(putUrl, {
        method: 'PUT',
        headers: { ...auth, 'Content-Type': 'text/markdown; charset=utf-8' },
        body: rule.bytes,
      });
      if (res.status === 200) pushed.push(rule.key);
      else failed.push({ key: rule.key, reason: `HTTP ${res.status}` });
    } catch (err) {
      if (deadline.isDeadlineError(err)) deferredCount += 1;
      else failed.push({ key: rule.key, reason: err?.message ?? String(err) });
    }
  }

  return formatRuleSyncSummary({
    pushedKeys: pushed,
    unchangedCount: plan.unchangedCount + bundledPlan.inSync.length,
    skipped: plan.skipped,
    failed,
    deferredCount,
    untouchedKeys: bundledPlan.untouched,
    bundledListingUnusable,
  });
}

async function main() {
  const startedAt = Date.now();
  if (typeof fetch !== 'function') return; // node < 18 — no global fetch; nothing we can do, stay silent

  // FileChanged fires for every watched path, not just ours. Only config.yaml's own change
  // should trigger a push — a FileChanged event for some other watched file is a silent no-op.
  // SessionStart invocations (and any unreadable/unparseable stdin) fall through unchanged.
  const hookInput = readHookInput();
  const isFileChanged = hookInput?.hook_event_name === 'FileChanged';
  if (isFileChanged && !isTargetConfigPath(hookInput.file_path)) {
    return;
  }
  const deadline = makeDeadline(isFileChanged ? FILE_CHANGED_BUDGET_MS : SESSION_START_BUDGET_MS, startedAt);

  const found = locateConfig();
  if (found.scope === 'none') return; // no config file → nothing to sync
  const { path: configPath, bytes } = found;
  const rootId = found.rootId;
  if (!rootId) return; // not project-scoped → nothing to sync
  // Project-scope wording is unchanged; a user-level config is named "user config" instead.
  const configNoun = found.scope === 'user' ? 'user config' : 'project config';

  const base = apiBaseUrl();
  if (!base) return; // stdio/local: the global config file already serves this workspace

  // Token is optional — an unauthenticated server (API_AUTH_MODE=none +
  // API_ALLOW_UNAUTHENTICATED=true) needs no Authorization header at all.
  const localFingerprint = configFingerprint(bytes);
  const localEtag = `"cfg-${localFingerprint}"`;
  const endpoint = `${base}/api/v1/roots/${rootId}/config`;
  const auth = buildAuthHeader();

  // rules/ sits next to the config.yaml actually found — not cwd (AGENT_CONFIG_DIR, a cwd-walk
  // ancestor, the main checkout, or the user-level dir may differ from cwd).
  const rulesDir = join(dirname(configPath), 'rules');

  // configLine is set by whichever branch below decides the config-sync outcome; the rules step
  // (steps 3+) runs afterward for every branch EXCEPT the two early-return cases (API unreachable,
  // GET returned a status other than 200/404) — those `return` directly, exactly as before this
  // change, so the rules step never has a chance to run for them.
  let configLine;

  // 1) Read the server's current fingerprint — and, via ?fingerprint=, how our local fingerprint
  //    relates to its history (fast-forward guard) — to decide whether a push is needed.
  let currentEtag = null;
  let rootConfirmed = false; // the server has this root (config GET 200, or config PUT 200/412)
  try {
    const res = await deadline.fetch(`${endpoint}?fingerprint=${localFingerprint}`, { headers: auth });
    if (res.status === 200) {
      rootConfirmed = true;
      currentEtag = res.headers.get('etag');

      let relation;
      let updatedAt;
      try {
        const body = await deadline.json(res);
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
          configLine = `Task Orchestrator: ${configNoun} already in sync for root ${rootId}.`;
        } else if (decision.action === 'skip-superseded') {
          const where = found.scope === 'user' ? 'user-level config.yaml' : "checkout's config.yaml";
          configLine =
            `Task Orchestrator: config sync skipped — your ${where} is older than the ` +
            `server's (updated ${decision.updatedAt ?? 'unknown'}); pull or copy back before editing.`;
        }
        // decision.action === 'push' ('unknown' relation) — configLine stays unset, fall through to step 2.
      } else if (currentEtag && currentEtag === localEtag) {
        // Degrade path: an older server with no `relation` field at all — fall back to the
        // client-side etag compare instead of blocking on an ambiguous relation.
        configLine = `Task Orchestrator: ${configNoun} already in sync for root ${rootId}.`;
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
      const res = await deadline.fetch(endpoint, { method: 'PUT', headers, body: bytes });
      if (res.status === 200 || res.status === 412) rootConfirmed = true;
      if (res.status === 200) {
        let schemaWarnings;
        try {
          const body = await deadline.json(res);
          schemaWarnings = body?.schemaWarnings;
        } catch {
          schemaWarnings = undefined; // unparseable body — degrade like no warnings reported
        }
        const syncedLine = `Task Orchestrator: synced ${configNoun} to root ${rootId} — per-project schemas/traits are now live.`;
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

  // 3) Push the git-tracked rule files (rules/*.md next to the config) and, on SessionStart only,
  //    the plugin's bundled rules into the per-root rule store. Fail-open: any exception here is
  //    swallowed into a "sync failed" line rather than affecting the config line above or the
  //    process exit code.
  let rulesSummary;
  try {
    rulesSummary = await syncRules({ rulesDir, base, rootId, auth, deadline, includeBundled: !isFileChanged, rootConfirmed });
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
