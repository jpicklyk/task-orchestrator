#!/usr/bin/env node
// SessionStart/FileChanged hook — syncs the located .taskorchestrator/config.yaml (project scope,
// or the user-level config in user scope — see config-locator.mjs) into the per-root config store
// over the REST API, so a shared HTTP-transport server picks up its schemas/traits without a
// restart (config get, validate, then push with expectedVersion). Then syncs the rules/*.md next to
// that config and, on SessionStart only, the plugin's bundled rules (bundled-rules/, governed by
// bundled-rules/manifest.json) as rule documents (docs_write upsert kind rule).
//
// All calls share ONE deadline measured from hook start (SESSION_START_BUDGET_MS /
// FILE_CHANGED_BUDGET_MS, kept below the hooks-config.json timeouts); each call's own timeout is
// min(PER_REQUEST_TIMEOUT_MS, time remaining), and no call is sent once the deadline has passed.
//
// Fail-open by design: ANY error, missing env, or unreachable API results in exit 0 with
// (at most) a one-line note — it must never block session start. A rejected config (validate
// valid:false, push config_invalid) is the one outcome reported as a top-level systemMessage on
// both events; it is never retried. It no-ops entirely when no REST API is configured (no URL =
// "no REST API configured"), where the global config file already serves the workspace directly.
//
// Requires (all optional — absent = no-op):
//   TASK_ORCHESTRATOR_API_URL    base URL of the REST API, e.g. http://localhost:3001 (falls back
//                                to apiUrl in a project-level, then the user-level client.json — see lib/api-client.mjs)
//   TASK_ORCHESTRATOR_API_TOKEN  bearer token with the read + write-config + write-docs capabilities,
//                                scoped to this root (read: config get and validate, rule listing;
//                                write-config: push; write-docs: rule upserts).
//                                Optional: an unauthenticated server (API_AUTH_MODE=none +
//                                API_ALLOW_UNAUTHENTICATED=true) needs no token at all — when
//                                absent, requests are sent with no Authorization header.

import { readFileSync, readdirSync } from 'fs';
import { dirname, join, resolve } from 'path';
import { fileURLToPath } from 'url';
import { createHash } from 'crypto';
import { readSection, scalar } from './yaml-lite.mjs';
import { apiBaseUrl, callOp } from './lib/api-client.mjs';
// readHookInput is fail-open: an unreadable or unparseable stdin (including no stdin at all, as when
// this script is run manually) yields null, treated exactly like a SessionStart invocation.
import { readHookInput } from './lib/stdin.mjs';
import { emitContext, emitSystemMessage } from './lib/emit.mjs';
import { locateConfig, isValidRootId } from './config-locator.mjs';

// Mirrors RuleService.KEY_PATTERN (current/.../application/service/RuleService.kt) exactly — a rule
// key must match this server-side grammar or the upsert would be rejected anyway; validated client-side
// so an invalid filename is reported as "skipped" instead of surfacing a failed upsert.
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

/** Result handed back for a call that was never sent because the shared deadline had already passed. */
function notSentResult() {
  return {
    ok: false,
    legacy: false,
    data: null,
    error: {
      code: 'client_error',
      kind: 'transient',
      message: 'client_error: deadline',
      retryAfterMs: null,
      detail: { reason: 'deadline' },
      fix: null,
      index: null,
    },
    results: [],
    summary: null,
    reqId: null,
    httpStatus: null,
  };
}

/**
 * The shared deadline. `call(tool, body)` runs `callImpl(tool, body, {timeoutMs})` (callOp) with
 * timeout = min(PER_REQUEST_TIMEOUT_MS, remaining); when remaining <= 0 nothing is sent and a
 * not-sent result comes back. A call is deadline-capped when remaining <= PER_REQUEST_TIMEOUT_MS at
 * SEND time, decided once there, never from the clock afterwards. `isDeadlineResult(res)` is true
 * for the not-sent result and for a capped call that timed out (callOp's `client_error` with reason
 * `timeout`); a timeout of an uncapped call is an ordinary failure. `callImpl` may resolve null (no
 * REST API configured), which `call` passes through.
 */
export function makeDeadline(budgetMs, startedAt, { now = Date.now, callImpl = callOp } = {}) {
  const remaining = () => budgetMs - (now() - startedAt);
  const deadlineResults = new WeakSet();
  return {
    remaining,
    async call(tool, body) {
      const rem = remaining();
      if (rem <= 0) {
        const res = notSentResult();
        deadlineResults.add(res);
        return res;
      }
      const capped = rem <= PER_REQUEST_TIMEOUT_MS;
      const res = await callImpl(tool, body, { timeoutMs: Math.min(PER_REQUEST_TIMEOUT_MS, rem) });
      if (capped && res && typeof res === 'object' && res.error?.code === 'client_error' && res.error?.detail?.reason === 'timeout') {
        deadlineResults.add(res);
      }
      return res;
    },
    isDeadlineResult(res) {
      return !!res && typeof res === 'object' && deadlineResults.has(res);
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
 * True when `filePath` is (some path form of) this workspace's `.taskorchestrator/config.yaml`.
 * Normalizes `\` to `/` first since FileChanged on Windows reports native backslash paths.
 */
export function isTargetConfigPath(filePath) {
  if (typeof filePath !== 'string' || !filePath) return false;
  return filePath.replace(/\\/g, '/').endsWith('.taskorchestrator/config.yaml');
}

// FileChanged is not documented to accept hookSpecificOutput.additionalContext, and a
// mismatched hookEventName is invalid, so that event surfaces the status line as a top-level
// systemMessage; so does a rejected config on either event. SessionStart output is otherwise unchanged.
function emit(line, isFileChanged = false, rejected = false) {
  if (isFileChanged || rejected) {
    emitSystemMessage(line);
    return;
  }
  emitContext('SessionStart', line);
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
 * Pure diff between this workspace's local rules and the server's current rule documents.
 * `localRules` is `[{key, bytes}]` where `bytes` is the ALREADY-normalized (`normalizeForFingerprint`)
 * body; `serverVersions` is a `Map<key, rulesVersion>` built from the docs_read rule listing (contentHash).
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
 * processed (the upsert queue order). Optional `deferredCount` (default 0) counts upserts not sent, or
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

const MAX_REASON_CHARS = 200;
const MAX_SHOWN_ERRORS = 3;
const MAX_LIST_PAGES = 20;

/**
 * Reduces one `callOp` answer to a single element outcome: `data.results[0]` when the call carried
 * results (a batch answer, including a 207 partial_failure), else the top-level error. Returns
 * `{ok: true, data}` (the result's fields) or `{ok: false, code, reason, error}`; `null` when the
 * call was not made (no REST API configured). A 3.x payload is `{ok: false, code: 'legacy'}`.
 */
export function outcomeOf(res) {
  if (res === null || res === undefined) return null;
  if (res.legacy) return { ok: false, code: 'legacy', reason: 'legacy server', error: null };
  const first = Array.isArray(res.results) && res.results.length > 0 ? res.results[0] : null;
  if (first && typeof first === 'object') {
    if (first.ok === true) return { ok: true, data: first };
    const error = first.error && typeof first.error === 'object' ? first.error : null;
    return failure(error);
  }
  if (res.ok === true) return { ok: true, data: res.data && typeof res.data === 'object' ? res.data : {} };
  return failure(res.error);
}

function failure(error) {
  const code = typeof error?.code === 'string' ? error.code : 'unknown';
  const reason = code === 'client_error' && typeof error?.detail?.reason === 'string' ? error.detail.reason : code;
  return { ok: false, code, reason, error };
}

/** `<path>: <reason>` entries of a rejection (empty path -> `(document)`, each reason cut to 200 chars). */
function rejectionEntries(errors, fallbackReason) {
  const list = Array.isArray(errors) ? errors.filter((e) => e && typeof e === 'object') : [];
  if (list.length === 0) return [`(document): ${String(fallbackReason).slice(0, MAX_REASON_CHARS)}`];
  return list.map((e) => {
    const path = typeof e.path === 'string' && e.path !== '' ? e.path : '(document)';
    return `${path}: ${String(e.reason ?? '').slice(0, MAX_REASON_CHARS)}`;
  });
}

/** The rejection status line: the first three errors joined by `; `, then ` (+N more)`. */
export function formatRejection(rootId, entries) {
  const shown = entries.slice(0, MAX_SHOWN_ERRORS).join('; ');
  const more = entries.length > MAX_SHOWN_ERRORS ? ` (+${entries.length - MAX_SHOWN_ERRORS} more)` : '';
  return `Task Orchestrator: config sync rejected for root ${rootId} - ${shown}${more}.`;
}

/** Why a failed outcome is unusable, with the capability hint for `forbidden`. */
function failedReason(out, capability) {
  return out.code === 'forbidden' ? `forbidden: needs ${capability}` : out.reason;
}

/**
 * Lists every rule document of the root (docs_read list kind rule, following page.next). Resolves
 * `{serverVersions: Map<key, contentHash>, docVersions: Map<key, version>, unusable}` or
 * `{deferred: true}` / `{failed: outcome}`. An ok answer whose items are not a list, or a later
 * page that fails, is `unusable` (never guess which keys are absent).
 */
async function listRules({ rootId, deadline }) {
  const serverVersions = new Map();
  const docVersions = new Map();
  let cursor;
  for (let page = 0; page < MAX_LIST_PAGES; page += 1) {
    const body = { select: 'list', rootId, kind: 'rule', limit: 200 };
    if (cursor !== undefined) body.cursor = cursor;
    const res = await deadline.call('docs_read', body);
    if (deadline.isDeadlineResult(res)) return { deferred: true };
    const out = outcomeOf(res);
    if (out === null) return { failed: { ok: false, code: 'client_error', reason: 'no REST API configured' } };
    if (!out.ok) return page === 0 ? { failed: out } : { serverVersions, docVersions, unusable: true };
    const data = res.data && typeof res.data === 'object' ? res.data : {};
    if (!Array.isArray(data.items)) return { serverVersions, docVersions, unusable: true };
    for (const item of data.items) {
      if (item && typeof item === 'object' && typeof item.slug === 'string') {
        serverVersions.set(item.slug, item.contentHash);
        docVersions.set(item.slug, item.version);
      }
    }
    const next = data.page && typeof data.page === 'object' ? data.page.next : undefined;
    if (typeof next !== 'string' || next === '') return { serverVersions, docVersions, unusable: false };
    cursor = next;
  }
  return { serverVersions, docVersions, unusable: true };
}

/**
 * Syncs the `rules/*.md` next to the located config and, when `includeBundled` (SessionStart), the
 * plugin's bundled rules into the per-root rule store (docs_write upsert kind rule). Returns `null`
 * when there is nothing to consider (no workspace rule files and no bundled sync) — the caller then
 * emits the config line unchanged. Otherwise returns the summary text that follows `Rules: `.
 *
 * Upsert queue order: every `protocol.`-prefixed key first, then the remaining workspace keys, then
 * the remaining bundled keys. Upserts are sequential, one in flight, each carrying expectedVersion:
 * the listed version, or 0 when the rule is absent. An upsert not sent, or timed out under a
 * deadline cap, counts as deferred; any other failure is reported under `failed` as `(<code>)`.
 */
async function syncRules({ rulesDir, rootId, deadline, includeBundled, rootConfirmed = false }) {
  const ruleFiles = listRuleFiles(rulesDir);
  const loaded = includeBundled ? loadBundledRules() : { bundled: [], manifest: {} };
  let { bundled } = loaded;
  const { manifest } = loaded;
  if (ruleFiles.length === 0 && bundled.length === 0) return null;

  const localRules = ruleFiles.map(({ key, filePath }) => ({
    key,
    bytes: normalizeForFingerprint(readFileSync(filePath)),
  }));

  const listing = await listRules({ rootId, deadline });
  if (listing.deferred) return 'sync deferred to next session';
  if (listing.failed) {
    const out = listing.failed;
    if (out.code === 'not_found' && out.error?.detail?.kind === 'root') {
      return rootConfirmed ? null : `root ${rootId} not found on this server`;
    }
    return `sync skipped - listing rules failed (${failedReason(out, 'read')})`;
  }
  const { serverVersions, docVersions } = listing;
  const bundledListingUnusable = listing.unusable;
  if (bundledListingUnusable) bundled = [];

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
  // Upserted sequentially (not Promise.all) — parallel writes against the same root race the
  // server's SQLite writer. One rule at a time keeps this hook converging in a single run.
  for (const rule of queue) {
    const expectedVersion = serverVersions.has(rule.key) ? docVersions.get(rule.key) : 0;
    const res = await deadline.call('docs_write', {
      ops: [
        {
          op: 'upsert',
          rootId,
          kind: 'rule',
          slug: rule.key,
          body: rule.bytes.toString('utf8'),
          expectedVersion: Number.isInteger(expectedVersion) ? expectedVersion : 0,
        },
      ],
    });
    if (deadline.isDeadlineResult(res)) {
      deferredCount += 1;
      continue;
    }
    const out = outcomeOf(res);
    if (out && out.ok) pushed.push(rule.key);
    else failed.push({ key: rule.key, reason: out ? failedReason(out, 'write-docs') : 'no REST API configured' });
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

  if (!apiBaseUrl()) return; // no REST API configured: the global config file already serves this workspace

  const localFingerprint = configFingerprint(bytes);
  // validate and push carry the same normalized bytes the fingerprint is computed over.
  const configYaml = normalizeForFingerprint(bytes).toString('utf8');

  // rules/ sits next to the config.yaml actually found — not cwd (AGENT_CONFIG_DIR, a cwd-walk
  // ancestor, the main checkout, or the user-level dir may differ from cwd).
  const rulesDir = join(dirname(configPath), 'rules');

  // configLine is set by whichever branch below decides the config-sync outcome; the rules step
  // runs afterward for every branch EXCEPT the early returns (get failed, root not found).
  let configLine;
  let rejected = false;
  let rootConfirmed = false; // the server has this root (config get ok, or not_found kind config)
  let headVersion = null;

  // 1) get decides: the head's relation to our fingerprint (fast-forward guard).
  const getRes = await deadline.call('config', { ops: [{ op: 'get', rootId, fingerprint: localFingerprint }] });
  const got = outcomeOf(getRes);
  if (got === null) return;
  if (got.ok) {
    rootConfirmed = true;
    const head = got.data;
    headVersion = Number.isInteger(head.version) && head.version >= 1 ? head.version : null;
    const relation = typeof head.relation === 'string' ? head.relation : undefined;
    // relation is the sole authority when the server provides it; without it, compare fingerprints.
    const current = relation !== undefined ? relation === 'current' : head.fingerprint === localFingerprint;
    if (current) {
      configLine = `Task Orchestrator: ${configNoun} already in sync for root ${rootId}.`;
    } else if (relation === 'superseded') {
      const where = found.scope === 'user' ? 'user-level config.yaml' : "checkout's config.yaml";
      configLine =
        `Task Orchestrator: config sync skipped - your ${where} is older than the ` +
        `server's (updated ${head.createdAt ?? 'unknown'}); pull or copy back before editing.`;
    }
    // 'unknown' (or an absent relation with a different fingerprint) falls through to validate.
  } else if (got.code === 'not_found' && got.error?.detail?.kind === 'config') {
    rootConfirmed = true; // the root exists, it just has no config yet: the first push is a create
  } else if (got.code === 'not_found' && got.error?.detail?.kind === 'root') {
    emit(`Task Orchestrator: root ${rootId} not found on this server.`, isFileChanged);
    return;
  } else {
    emit(`Task Orchestrator: config sync skipped - ${failedReason(got, 'read')}.`, isFileChanged);
    return;
  }

  // 2) validate, then push (expectedVersion = the head's version). At most one of each per run;
  //    a rejection is never retried.
  if (configLine === undefined) {
    const val = outcomeOf(await deadline.call('config', { ops: [{ op: 'validate', rootId, configYaml }] }));
    if (val === null) return;
    if (!val.ok) {
      if (val.code === 'payload_too_large') {
        rejected = true;
        configLine = formatRejection(rootId, rejectionEntries(null, val.error?.message ?? val.code));
      } else {
        configLine = `Task Orchestrator: config sync failed - validate ${failedReason(val, 'read')}.`;
      }
    } else if (val.data.valid === false) {
      rejected = true;
      configLine = formatRejection(rootId, rejectionEntries(val.data.errors, 'invalid config'));
    } else {
      const op = { op: 'push', rootId, configYaml };
      if (headVersion !== null) op.expectedVersion = headVersion;
      const pushRes = await deadline.call('config', { ops: [op] });
      const push = outcomeOf(pushRes);
      if (push === null) return;
      if (push.ok) {
        rootConfirmed = true;
        if (push.data.appended === false) {
          configLine = `Task Orchestrator: ${configNoun} already in sync for root ${rootId}.`;
        } else {
          const warnings = push.data.warnings;
          const syncedLine = `Task Orchestrator: synced ${configNoun} to root ${rootId} — per-project schemas/traits are now live.`;
          const texts = Array.isArray(warnings)
            ? warnings.map((w) => (w && typeof w === 'object' ? String(w.reason ?? '') : String(w)))
            : [];
          configLine = texts.length > 0 ? `${syncedLine} WARNINGS: ${texts.join(' | ')}` : syncedLine;
        }
      } else if (push.code === 'version_conflict') {
        configLine = `Task Orchestrator: config sync deferred - root ${rootId} was updated concurrently; will retry next session.`;
      } else if (push.code === 'config_invalid' || push.code === 'payload_too_large') {
        rejected = true;
        configLine = formatRejection(rootId, rejectionEntries(push.error?.detail?.errors, push.error?.message ?? push.code));
      } else if (deadline.isDeadlineResult(pushRes)) {
        configLine = `Task Orchestrator: config sync deferred - ${push.reason}; will retry next session.`;
      } else {
        configLine = `Task Orchestrator: config sync failed - ${failedReason(push, 'write-config')}.`;
      }
    }
  }

  // 3) Push the git-tracked rule files (rules/*.md next to the config) and, on SessionStart only,
  //    the plugin's bundled rules. Fail-open: any exception here is swallowed into a "sync failed"
  //    line rather than affecting the config line above or the process exit code. Runs after a
  //    rejection too.
  let rulesSummary;
  try {
    rulesSummary = await syncRules({ rulesDir, rootId, deadline, includeBundled: !isFileChanged, rootConfirmed });
  } catch (err) {
    rulesSummary = `sync failed - ${err?.message ?? err}`;
  }

  emit(rulesSummary ? `${configLine} Rules: ${rulesSummary}` : configLine, isFileChanged, rejected);
}

// Only auto-run when invoked directly as a hook (`node config-sync.mjs`), not when imported
// (e.g. by a test importing `parseRootId` for direct unit coverage) — importing must never
// trigger a live sync attempt as a side effect.
// Fail-open: swallow every error so the hook always exits 0 and never blocks session start.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => {});
}
