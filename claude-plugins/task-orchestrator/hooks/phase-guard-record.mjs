#!/usr/bin/env node
// PostToolUse:advance — records which items a subagent has entered its own phase for, so
// phase-guard.mjs (SubagentStop) can re-check their gate status when the subagent tries to
// return.
//
// Acts only inside a subagent call — the hook input carries `agent_id` only when firing inside a
// subagent call (plugin hooks also run for the main session, where `agent_id` is absent) — and
// only when the REST API is configured: with no API URL, phase-guard.mjs can never read the gate
// back, so recording anything would be dead weight.
//
// Fail-open by design: any read/parse error, missing env, or unrecognized tool_response shape
// results in a silent `{}` on stdout and exit 0 — this hook must never block advance.
//
// Reads the 4.0 envelope through lib/envelope.mjs: every element of `results[]` is considered.
// An element is recordable when it is `ok: true` with a string `newRole` (the role entered), or
// `ok: false` with `error.code === 'gate_blocked'` (the already-in-phase case: the server binds
// `error.detail.role` to the item's CURRENT role, the phase the agent already sits in, and
// `error.detail.itemId` to the item). Every other failure code means the transition never
// touched the item, or the item is not this agent's, and records nothing.
//
// Workflow-seat exclusion: a call carrying a non-empty top-level `seatToken` belongs to a run
// seat, not an ordinary subagent dispatch; those seats never return through phase-guard.mjs's
// SubagentStop check, so recording them would only produce false blocks.
//
// Entered-role tracking: the marker's `enteredRoles` map holds the role the agent entered for
// each item; phase-guard.mjs blocks only on that role, never on whatever role the item has since
// moved to under a later seat.

import { resolve } from 'path';
import { fileURLToPath } from 'url';
import { apiBaseUrl } from './lib/api-client.mjs';
import { readHookInput } from './lib/stdin.mjs';
import { emitEmpty as writeEmpty, emitSystemMessage } from './lib/emit.mjs';
import { sessionMarkerPath, readMarker, writeMarker } from './lib/markers.mjs';
import { parseToolResult } from './lib/envelope.mjs';
import { legacyShapeWarningOnce } from './lib/server-compat.mjs';
import { isHeadlessIteration } from './execution-mode.mjs';

const MAX_ITEMS_PER_AGENT = 50;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * One state file per (session_id, agent_id) pair, under os.tmpdir()/task-orchestrator/<session>/,
 * named `phase-guard-<agent>.json`. Deliberately NOT retro-lib's `markerPath`: that hard-codes a `retro-`
 * prefix and is keyed only on session/rootId — a shared per-session map would race under
 * parallel subagents advancing concurrently, which per-agent files avoid.
 */
export function phaseGuardMarkerPath(sessionId, agentId) {
  return sessionMarkerPath(sessionId, 'phase-guard', agentId || 'unknown');
}

/** Shape: `{items: string[], blocks: number, enteredRoles: {[itemId]: string}}`. `enteredRoles`
 * is new (previously-written markers lack it) and defaults to `{}` on any missing/malformed
 * value, so an old marker degrades to the pre-entered-role behavior in phase-guard.mjs rather
 * than crashing. Any read failure degrades to the empty default. */
export function readPhaseGuardMarker(path) {
  try {
    const parsed = readMarker(path, null);
    if (!parsed) return { items: [], blocks: 0, enteredRoles: {} };
    const enteredRoles =
      parsed.enteredRoles && typeof parsed.enteredRoles === 'object' && !Array.isArray(parsed.enteredRoles)
        ? parsed.enteredRoles
        : {};
    return {
      items: Array.isArray(parsed.items) ? parsed.items : [],
      blocks: Number.isInteger(parsed.blocks) ? parsed.blocks : 0,
      enteredRoles,
    };
  } catch {
    return { items: [], blocks: 0, enteredRoles: {} };
  }
}

export function writePhaseGuardMarker(path, obj) {
  // atomic and fail-open: a marker write must never crash a hook
  writeMarker(path, obj);
}

export function isFullUuid(value) {
  return typeof value === 'string' && UUID_RE.test(value);
}

function recordable(r) {
  if (!r || typeof r !== 'object') return null;
  if (r.ok === true) {
    if (isFullUuid(r.itemId) && typeof r.newRole === 'string' && r.newRole) return { id: r.itemId, role: r.newRole };
    return null;
  }
  if (r.ok === false && r.error && r.error.code === 'gate_blocked') {
    const d = r.error.detail;
    if (d && typeof d === 'object' && isFullUuid(d.itemId)) {
      return { id: d.itemId, role: typeof d.role === 'string' && d.role ? d.role : null };
    }
  }
  return null;
}

/**
 * From a parsed 4.0 advance result (`lib/envelope.mjs` Parsed), the itemIds worth recording: a
 * full UUID AND either `ok === true` with a string `newRole` (the transition succeeded) OR
 * `error.code === 'gate_blocked'` (the "already in phase" case). Every other code, or `ok: false`
 * without a code, records nothing. Every element of `results[]` is considered.
 */
export function extractRecordableItemIds(parsed) {
  const results = Array.isArray(parsed?.results) ? parsed.results : [];
  const ids = [];
  for (const r of results) {
    const rec = recordable(r);
    if (rec) ids.push(rec.id);
  }
  return ids;
}

/**
 * The role each recordable item actually ENTERED: `newRole` on an `ok: true` result, or
 * `error.detail.role` on the already-in-phase `gate_blocked` case. An item with no recognizable
 * role string is simply omitted from the map.
 */
export function extractEnteredRoles(parsed) {
  const results = Array.isArray(parsed?.results) ? parsed.results : [];
  const roles = {};
  for (const r of results) {
    const rec = recordable(r);
    if (rec && rec.role) roles[rec.id] = rec.role;
  }
  return roles;
}

function emitEmpty() {
  writeEmpty();
  process.exit(0);
}

function main() {
  try {
    const hookInput = readHookInput();
    if (!hookInput) emitEmpty();

    // The task-orchestrator-mod plugin already handled this event in-process (it sets
    // `to_mod_active` before forwarding); recording here too would write a redundant marker file.
    if (hookInput?.to_mod_active === true) emitEmpty();

    // Headless ralph iteration: emit empty before recording anything — a ralph iteration never
    // dispatches subagents, so phase-guard.mjs (SubagentStop) would have nothing to check
    // anyway, but recording is skipped explicitly rather than relying on that always holding.
    if (isHeadlessIteration()) emitEmpty();

    // Only present inside a subagent call — nothing to guard for the main session.
    if (!hookInput.agent_id) emitEmpty();

    // No REST API configured → phase-guard.mjs can never read the gate back; recording is moot.
    if (!apiBaseUrl()) emitEmpty();

    // Run seats (a non-empty top-level seatToken) never return through phase-guard.mjs.
    const seatToken = hookInput.tool_input?.seatToken;
    if (typeof seatToken === 'string' && seatToken.length > 0) emitEmpty();

    const parsed = parseToolResult(hookInput.tool_response);
    if (!parsed) emitEmpty();
    if (parsed.legacy) {
      const warning = legacyShapeWarningOnce(hookInput.session_id);
      if (warning) {
        emitSystemMessage(warning);
        process.exit(0);
      }
      emitEmpty();
    }

    const newItems = extractRecordableItemIds(parsed);
    if (newItems.length === 0) emitEmpty();
    const newEnteredRoles = extractEnteredRoles(parsed);

    const path = phaseGuardMarkerPath(hookInput.session_id, hookInput.agent_id);
    const marker = readPhaseGuardMarker(path);
    const merged = [...new Set([...marker.items, ...newItems])];
    const items = merged.length > MAX_ITEMS_PER_AGENT ? merged.slice(merged.length - MAX_ITEMS_PER_AGENT) : merged;
    const mergedRoles = { ...marker.enteredRoles, ...newEnteredRoles };
    // Prune roles for any item the cap above dropped, so enteredRoles never outlives its item.
    const enteredRoles = {};
    for (const id of items) {
      if (mergedRoles[id]) enteredRoles[id] = mergedRoles[id];
    }
    writePhaseGuardMarker(path, { items, blocks: marker.blocks, enteredRoles });

    emitEmpty();
  } catch {
    emitEmpty();
  }
}

// Only auto-run when invoked directly as a hook (`node phase-guard-record.mjs`), not when
// imported (e.g. by phase-guard.mjs for the shared marker helpers, or by a test for direct unit
// coverage) — importing must never trigger a synchronous stdin read as a side effect.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
