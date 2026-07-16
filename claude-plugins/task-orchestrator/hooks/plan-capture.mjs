#!/usr/bin/env node
// PostToolUse:ExitPlanMode — stashes the just-approved plan as a plan_document via the REST API,
// so post-plan-workflow can materialize items from a stored docRef instead of the model re-typing
// the plan body into note text.
//
// Fail-open by design: ANY error, missing env, non-2xx response, or unreachable API results in a
// silent exit 0 — it must never block ExitPlanMode or the plan-approval flow. It no-ops entirely
// for stdio/local setups (no API env vars set), mirroring config-sync.mjs's discipline exactly.
//
// Requires (all optional — absent = no-op):
//   TASK_ORCHESTRATOR_API_URL    base URL of the REST API, e.g. http://localhost:3001
//   TASK_ORCHESTRATOR_API_TOKEN  bearer token with the WRITE_CONFIG capability, scoped to this root.
//                                Optional: an unauthenticated server (API_AUTH_MODE=none +
//                                API_ALLOW_UNAUTHENTICATED=true) needs no token at all — when
//                                absent, requests are sent with no Authorization header.

import { readFileSync } from 'fs';
import { resolve } from 'path';

const REQUEST_TIMEOUT_MS = 2000;
const MAX_SLUG_LENGTH = 64;

/** Locate .taskorchestrator/config.yaml (AGENT_CONFIG_DIR, then walk up from cwd) and return its text. */
function findConfigText() {
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
      return readFileSync(candidate, 'utf-8');
    } catch {
      continue;
    }
  }
  return null;
}

/** Extract project.rootId from the config text (mirrors config-sync.mjs's parser). */
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

/** Derives a URL-safe slug from the plan's first H1 heading; falls back to a timestamp slug. */
function deriveSlug(planText) {
  const headingMatch = planText.match(/^#\s+(.+)$/m);
  if (headingMatch) {
    const slug = headingMatch[1]
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, MAX_SLUG_LENGTH)
      .replace(/-+$/g, '');
    if (slug) return slug;
  }
  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const stamp =
    `${now.getUTCFullYear()}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}` +
    `-${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}`;
  return `plan-${stamp}`;
}

function emit(line) {
  process.stdout.write(
    JSON.stringify({ hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: line } }),
  );
}

function fetchWithTimeout(url, opts) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  return fetch(url, { ...opts, signal: controller.signal }).finally(() => clearTimeout(timer));
}

async function main() {
  if (typeof fetch !== 'function') return; // node < 18 — no global fetch; nothing we can do, stay silent

  let raw = '';
  try {
    raw = readFileSync(0, 'utf-8');
  } catch {
    return;
  }

  let hookInput;
  try {
    hookInput = JSON.parse(raw);
  } catch {
    return;
  }

  const planText = hookInput?.tool_input?.plan;
  if (typeof planText !== 'string' || planText.trim() === '') return; // nothing to stash

  const configText = findConfigText();
  if (!configText) return; // no config file → not a Task Orchestrator workspace

  const rootId = parseRootId(configText);
  if (!rootId) return; // not project-scoped → no root to stash under

  const apiUrl = process.env.TASK_ORCHESTRATOR_API_URL;
  if (!apiUrl) return; // stdio/local setups: no REST API to stash to — silent no-op

  // Token is optional — an unauthenticated server (API_AUTH_MODE=none +
  // API_ALLOW_UNAUTHENTICATED=true) needs no Authorization header at all.
  const token = process.env.TASK_ORCHESTRATOR_API_TOKEN;
  const authHeader = token ? { Authorization: `Bearer ${token}` } : {};

  const slug = deriveSlug(planText);
  const endpoint = `${apiUrl.replace(/\/+$/, '')}/api/v1/roots/${rootId}/plans/${slug}`;

  try {
    const res = await fetchWithTimeout(endpoint, {
      method: 'PUT',
      headers: { ...authHeader, 'Content-Type': 'text/plain' },
      body: planText,
    });
    if (res.status >= 200 && res.status < 300) {
      emit(
        `Task Orchestrator: plan stashed as plan document '${slug}' (root ${rootId}). ` +
          `Prefer referencing this docRef during materialization instead of re-authoring note bodies.`,
      );
    }
    // Any non-2xx (404 not_found, 409 adopted_conflict, 413 payload_too_large, 422 validation_error,
    // etc.) is fail-open: stay completely silent, never block plan approval.
  } catch {
    // Unreachable API, timeout, or network error — fail-open, stay silent.
  }
}

// Fail-open: swallow every error so the hook always exits 0 and never blocks ExitPlanMode.
main().catch(() => {});
