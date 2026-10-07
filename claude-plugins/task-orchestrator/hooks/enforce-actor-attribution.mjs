#!/usr/bin/env node
// PreToolUse hook — enforces actor attribution on MCP write operations when either
// actor_authentication is enabled, or the local-only actor_attribution.required option
// is set, in .taskorchestrator/config.yaml.
//
// The config consulted is the one config-locator finds on the client (AGENT_CONFIG_DIR, workspace,
// main checkout, or ~/.taskorchestrator), NOT the server's global AGENT_CONFIG_DIR mount; the
// server never reads actor_authentication.enabled.
//
// Config format:
//   actor_authentication:
//     enabled: true
//   actor_attribution:
//     required: true
//
// actor_attribution.required (default false) mirrors actor_authentication.enabled's deny
// behavior on this hook, but is independent of it — it can be turned on locally (e.g. as a
// project's own dogfood setting) without also standing up full actor_authentication (JWKS
// identity verification). Either option alone is sufficient to enforce.
//
// When enforced, blocks advance_item and manage_notes(upsert) calls that are missing an
// actor object on any transition/note element — or, for the singular-sugar advance_item form
// ({itemId, trigger}, no transitions array), missing the top-level actor.
//
// Independently of enforcement, a best-effort NON-BLOCKING warning fires on manage_notes(upsert)
// when an element authored by an actor of kind "orchestrator" targets a note whose stored actor
// is a "subagent" (a seat-owned note such as review-checklist or test-manifest): re-upserting it
// would flip ownership and self-confirm the seat's verdict. The stored note is read via
// GET /api/v1/items/{itemId}/notes/{key}. PRECONDITION: the server (AttributionRedactor) nulls the NoteDto
// `actor` only when redaction is ON and the caller is not ADMIN; the actor is visible when the
// caller is ADMIN OR the server runs with API_REDACT_NOTE_ATTRIBUTION=false. Otherwise the warning never fires, so the documented rule (post-run.md Step 5) is the primary
// control and this hook only a backstop. Never denies; silent on unset API URL, 404, non-ok,
// null actor, timeout or any error.

import { readFileSync } from 'fs';
import { readSection, scalar, inlineScalar } from './yaml-lite.mjs';
import { locateConfig } from './config-locator.mjs';
import { apiBaseUrl, authHeader, fetchWithTimeout } from './api-client.mjs';

let input = '';
try {
  input = readFileSync(0, 'utf-8');
} catch {
  process.exit(0);
}

let hookInput;
try {
  hookInput = JSON.parse(input);
} catch {
  process.exit(0);
}

const toolName = hookInput.tool_name || '';
const toolInput = hookInput.tool_input || {};

// Early exit: only enforce on advance_item or manage_notes upsert
const isAdvance = toolName.includes('advance_item');
const isNoteUpsert = toolName.includes('manage_notes') && toolInput.operation === 'upsert';
if (!isAdvance && !isNoteUpsert) process.exit(0);

// Returns true only when actor_authentication.enabled is explicitly set to true.
// Handles both block YAML and inline forms, strips trailing comments.
function isActorAuthenticationEnabled(configContent) {
  if (!configContent) return false;

  const section = readSection(configContent, 'actor_authentication');
  if (!section) return false;

  const raw = section.inline !== null
    ? inlineScalar(section.inline, 'enabled')
    : scalar(section.lines, 'enabled');

  return raw !== null && raw.toLowerCase() === 'true';
}

// Returns true only when actor_attribution.required is explicitly set to true. Independent
// of actor_authentication — a project can require actor attribution locally without also
// enabling actor_authentication's JWKS identity verification. Same block/inline handling as
// isActorAuthenticationEnabled.
function isActorAttributionRequired(configContent) {
  if (!configContent) return false;

  const section = readSection(configContent, 'actor_attribution');
  if (!section) return false;

  const raw = section.inline !== null
    ? inlineScalar(section.inline, 'required')
    : scalar(section.lines, 'required');

  return raw !== null && raw.toLowerCase() === 'true';
}

const configContent = locateConfig().text;
const enforced = isActorAuthenticationEnabled(configContent) || isActorAttributionRequired(configContent);

let missing = false;

if (!enforced) {
  // enforcement off: no deny path
} else if (isAdvance) {
  // The server treats `transitions[]` and the singular-sugar shape (`{itemId, trigger, actor?}`)
  // as mutually exclusive: when `transitions` is present, the singular top-level fields are
  // ignored, so only the batch shape's per-element actors matter. When `transitions` is absent,
  // an actor-less singular call (`{itemId, trigger}` with no top-level `actor`) must be denied
  // the same as a transitions-array element missing its actor — otherwise the singular-sugar
  // path is a silent bypass of actor attribution enforcement.
  if (Array.isArray(toolInput.transitions)) {
    missing = toolInput.transitions.some(t => !t.actor);
  } else if (typeof toolInput.itemId === 'string') {
    missing = !toolInput.actor;
  }
} else if (isNoteUpsert) {
  const notes = toolInput.notes || [];
  missing = notes.some(n => !n.actor);
}

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Returns warning strings for orchestrator-authored upserts onto subagent-owned notes.
async function seatOwnedWarnings() {
  if (!isNoteUpsert) return [];
  const base = apiBaseUrl();
  if (!base) return [];
  const notes = Array.isArray(toolInput.notes) ? toolInput.notes : [];
  const candidates = notes
    .filter(n => n && n.actor && n.actor.kind === 'orchestrator'
      && typeof n.itemId === 'string' && UUID_RE.test(n.itemId)
      && typeof n.key === 'string' && n.key)
    .slice(0, 10);
  const results = await Promise.all(candidates.map(async n => {
    try {
      const res = await fetchWithTimeout(
        `${base}/api/v1/items/${n.itemId}/notes/${encodeURIComponent(n.key)}`,
        { headers: authHeader() },
        1500,
      );
      if (!res.ok) return null;
      const body = await res.json();
      if (body && body.actor && body.actor.kind === 'subagent') {
        return `Seat-owned note: item ${n.itemId} note "${n.key}" is stored under subagent actor ` +
          `"${body.actor.id}". Re-upserting it as the orchestrator flips its ownership and ` +
          'self-confirms the seat\'s verdict. Dispatch a fresh reviewer seat for re-review, or record ' +
          'your confirmation under your own key "orchestrator-confirmation" (role review, optional).';
      }
    } catch {
      // best-effort: stay silent
    }
    return null;
  }));
  return results.filter(Boolean);
}

if (!missing) {
  let warnings = [];
  try {
    warnings = await seatOwnedWarnings();
  } catch {
    warnings = [];
  }
  if (warnings.length > 0) {
    const text = warnings.join('\n');
    process.stdout.write(JSON.stringify({
      hookSpecificOutput: { hookEventName: 'PreToolUse', additionalContext: text },
      systemMessage: text,
    }));
  }
  process.exit(0);
}

process.stdout.write(JSON.stringify({
  hookSpecificOutput: {
    hookEventName: 'PreToolUse',
    permissionDecision: 'deny',
    permissionDecisionReason: 'Actor attribution is required (actor_authentication.enabled or actor_attribution.required is set). Include an "actor" object ' +
      'with "id" (string) and "kind" (orchestrator|subagent|user|external) on every ' +
      'transition/note element (for a singular advance_item call with itemId+trigger, put it at the ' +
      'top level). For subagents, include "parent" with the dispatching agent\'s id.'
  }
}));
