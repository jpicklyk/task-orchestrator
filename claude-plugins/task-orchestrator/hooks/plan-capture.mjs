#!/usr/bin/env node
// PostToolUse:ExitPlanMode — stashes the just-approved plan as a plan document (docs_write upsert,
// kind plan) over the REST API,
// so post-plan-workflow can materialize items from a stored docRef instead of the model re-typing
// the plan body into note text.
//
// Fail-open by design: ANY error, missing env, non-2xx response, or unreachable API results in a
// silent exit 0 — it must never block ExitPlanMode or the plan-approval flow. It no-ops entirely
// for stdio/local setups (no API env vars set), mirroring config-sync.mjs's discipline exactly.
//
// Requires (all optional — absent = no-op):
//   TASK_ORCHESTRATOR_API_URL    base URL of the REST API, e.g. http://localhost:3001 (no URL = "no REST
//                                API configured": a silent no-op)
//   TASK_ORCHESTRATOR_API_TOKEN  bearer token with the write-docs capability, scoped to this root.
//                                Optional: an unauthenticated server (API_AUTH_MODE=none +
//                                API_ALLOW_UNAUTHENTICATED=true) needs no token at all — when
//                                absent, requests are sent with no Authorization header.

import { resolve } from 'path';
import { fileURLToPath } from 'url';
import { locateConfig } from './config-locator.mjs';
import { readHookInput } from './lib/stdin.mjs';
import { emitContext } from './lib/emit.mjs';
import { callOp } from './lib/api-client.mjs';

const MAX_SLUG_LENGTH = 64;

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
  emitContext('PostToolUse', line);
}

async function main() {
  if (typeof fetch !== 'function') return; // node < 18 — no global fetch; nothing we can do, stay silent

  const hookInput = readHookInput();
  if (!hookInput) return;

  const planText = hookInput?.tool_input?.plan;
  if (typeof planText !== 'string' || planText.trim() === '') return; // nothing to stash

  const loc = locateConfig();
  if (loc.scope !== 'project') return; // user scope or no config → silent
  const rootId = loc.rootId;
  if (!rootId) return; // not project-scoped → no root to stash under

  const slug = deriveSlug(planText);

  // One unconditional upsert (as the 3.x PUT was). callOp resolves null when no REST API is
  // configured (stdio/local setups) and never rejects; every failure stays silent.
  try {
    const res = await callOp('docs_write', {
      ops: [{ op: 'upsert', rootId, kind: 'plan', slug, body: planText }],
    });
    if (res && res.results[0] && res.results[0].ok === true) {
      emit(
        `Task Orchestrator: plan stashed as plan document '${slug}' (root ${rootId}). ` +
          `Prefer referencing this docRef during materialization instead of re-authoring note bodies.`,
      );
    }
    // Any failure (unauthenticated, forbidden, not_found, duplicate for an adopted plan,
    // payload_too_large, ...) is fail-open: stay completely silent, never block plan approval.
  } catch {
    // fail-open, stay silent.
  }
}

// Only auto-run when invoked directly as a hook (`node plan-capture.mjs`), not when imported
// (e.g. by a test) — importing must never
// trigger a synchronous stdin read as a side effect.
// Fail-open: swallow every error so the hook always exits 0 and never blocks ExitPlanMode.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(() => {});
}
