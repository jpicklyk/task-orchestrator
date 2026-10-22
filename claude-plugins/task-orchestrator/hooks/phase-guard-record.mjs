#!/usr/bin/env node
// PostToolUse:(advance|items_read) — records which items a subagent has entered its own phase
// for, so phase-guard.mjs (SubagentStop) can re-check their gate status when the subagent tries
// to return.
//
// Acts only inside a subagent call — the hook input carries `agent_id` only when firing inside a
// subagent call (plugin hooks also run for the main session, where `agent_id` is absent) — and
// only when the REST API is configured: with no API URL, phase-guard.mjs can never read the gate
// back, so recording anything would be dead weight.
//
// Fail-open by design: any read/parse error, missing env, or unrecognized tool_response shape
// results in a silent `{}` on stdout and exit 0 — this hook must never block advance or a read.
//
// A seat enters an item in one of two ways:
//   1. advance: an element of the 4.0 `results[]` (read through lib/envelope.mjs) that is
//      `ok: true` with a string `newRole`, the role entered. `gate_blocked` is NEVER recorded:
//      it means a required note is missing, and its `detail.role` is the item's role BEFORE the
//      transition, not a phase the seat entered.
//   2. context read: a phase-owner seat (implementer or reviewer, from `agent_type`) reads the
//      role before its phase and sends `start` only from there, so a seat already in its phase
//      sends no `start` at all. Its FIRST ok item-mode context read (`items_read` select
//      "context" with a full-UUID itemId) is recorded when `data.item.role` equals the role the
//      seat owns (SEAT_ROLE) and the marker holds no items yet. The id is the requested
//      `tool_input.itemId`, never `data.item.id` (a short id in compact views). Only the first
//      read counts (marker `contextReadSeen`), so reading a sibling or parent that also sits in
//      work never adds a false block. The read path never emits anything but `{}`.
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
import { isHeadlessIteration, phaseOwnerSeat } from './execution-mode.mjs';

const MAX_ITEMS_PER_AGENT = 50;
/** The role each phase-owner seat owns; phase-guard.mjs imports it too. */
export const SEAT_ROLE = { implementer: 'work', reviewer: 'review' };
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

/** Shape: `{items: string[], blocks: number, enteredRoles: {[itemId]: string}, contextReadSeen: boolean}`. `enteredRoles`
 * is new (previously-written markers lack it) and defaults to `{}` on any missing/malformed
 * value, so an old marker degrades to the pre-entered-role behavior in phase-guard.mjs rather
 * than crashing. `contextReadSeen` is true only when the stored value is `=== true`.
 * Any read failure degrades to the empty default. */
export function readPhaseGuardMarker(path) {
  try {
    const parsed = readMarker(path, null);
    if (!parsed) return { items: [], blocks: 0, enteredRoles: {}, contextReadSeen: false };
    const enteredRoles =
      parsed.enteredRoles && typeof parsed.enteredRoles === 'object' && !Array.isArray(parsed.enteredRoles)
        ? parsed.enteredRoles
        : {};
    return {
      items: Array.isArray(parsed.items) ? parsed.items : [],
      blocks: Number.isInteger(parsed.blocks) ? parsed.blocks : 0,
      enteredRoles,
      contextReadSeen: parsed.contextReadSeen === true,
    };
  } catch {
    return { items: [], blocks: 0, enteredRoles: {}, contextReadSeen: false };
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
  return null;
}

/**
 * From a parsed 4.0 advance result (`lib/envelope.mjs` Parsed), the itemIds worth recording: a
 * full UUID AND `ok === true` with a string `newRole` (the transition succeeded). Every failure,
 * `gate_blocked` included, records nothing. Every element of `results[]` is considered.
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
 * The role each recordable item actually ENTERED: `newRole` on an `ok: true` result. An item with
 * no recognizable role string is simply omitted from the map.
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

/**
 * Records the first item-mode context read of a phase-owner seat (see the header). Writes nothing
 * for a non-phase-owner agent_type, a non-context select, a non-full-UUID itemId, or a response
 * that is not `ok: true`; never emits.
 */
function recordContextRead(hookInput) {
  const input = hookInput.tool_input;
  if (input.select !== 'context' || !isFullUuid(input.itemId)) return;
  const seat = phaseOwnerSeat(hookInput.agent_type);
  if (!seat) return;
  const parsed = parseToolResult(hookInput.tool_response);
  if (!parsed || parsed.legacy || parsed.ok !== true) return;

  const path = phaseGuardMarkerPath(hookInput.session_id, hookInput.agent_id);
  const marker = readPhaseGuardMarker(path);
  if (marker.contextReadSeen) return;

  const role = parsed.data && typeof parsed.data === 'object' ? parsed.data.item?.role : undefined;
  const record = marker.items.length === 0 && typeof role === 'string' && role === SEAT_ROLE[seat];
  writePhaseGuardMarker(path, {
    items: record ? [input.itemId] : marker.items,
    blocks: marker.blocks,
    enteredRoles: record ? { ...marker.enteredRoles, [input.itemId]: role } : marker.enteredRoles,
    contextReadSeen: true,
  });
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

    // Context read (items_read): a different payload from advance; it has its own recorder.
    if (typeof hookInput.tool_input?.select === 'string') {
      recordContextRead(hookInput);
      emitEmpty();
    }

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
    writePhaseGuardMarker(path, { items, blocks: marker.blocks, enteredRoles, contextReadSeen: marker.contextReadSeen });

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
