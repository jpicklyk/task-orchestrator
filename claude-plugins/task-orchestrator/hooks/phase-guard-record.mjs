#!/usr/bin/env node
// PostToolUse:advance_item — records which items a subagent has entered its own phase for, so
// phase-guard.mjs (SubagentStop) can re-check their gate status when the subagent tries to
// return.
//
// Acts only inside a subagent call — the hook input carries `agent_id` only when firing inside a
// subagent call (plugin hooks also run for the main session, where `agent_id` is absent) — and
// only when the REST API is configured (TASK_ORCHESTRATOR_API_URL set): with no API URL,
// phase-guard.mjs can never read the gate back, so recording anything would be dead weight.
//
// Fail-open by design: any read/parse error, missing env, or unrecognized tool_response shape
// results in a silent `{}` on stdout and exit 0 — this hook must never block advance_item.
//
// Workflow-seat exclusion: a transition whose actor.parent starts with the literal prefix
// "workflow:" belongs to a Claude workflow-script seat, not an ordinary subagent dispatch —
// those seats never call manage_notes the way a dispatched subagent does, so recording them
// would only ever produce false SubagentStop blocks later. buildActorMap() reads the actor for
// each transition from tool_input, tolerating both advance_item call shapes: the batch
// `transitions[]` array (each element carries its own `actor`) and the top-level singular-sugar
// shape (`{itemId, trigger, actor}`, normalized server-side but NOT in the raw tool_input this
// hook observes). Results are matched back to their transition by itemId before recording.
//
// Entered-role tracking: alongside the recorded itemIds, this hook now also records the ROLE the
// agent entered for each item (`newRole` on an applied:true result, `targetRole` on the
// already-in-phase gate_blocked case) in the marker's `enteredRoles` map. phase-guard.mjs uses
// this so a SubagentStop check blocks only on the role this agent actually entered, never on
// whatever role the item has since moved to under a later seat.

import { readFileSync, writeFileSync, mkdirSync } from 'fs';
import { resolve, join, dirname } from 'path';
import { fileURLToPath } from 'url';
import os from 'os';
import { apiBaseUrl } from './api-client.mjs';
import { extractResponseJson } from './retro-lib.mjs';
import { isHeadlessIteration } from './execution-mode.mjs';

const MAX_ITEMS_PER_AGENT = 50;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function sanitizeForFilename(value) {
  return String(value).replace(/[^a-zA-Z0-9-]/g, '_');
}

/**
 * One state file per (session_id, agent_id) pair, under os.tmpdir()/task-orchestrator/, with a
 * `phase-guard-` prefix. Deliberately NOT retro-lib's `markerPath`: that hard-codes a `retro-`
 * prefix and is keyed only on session/rootId — a shared per-session map would race under
 * parallel subagents advancing concurrently, which per-agent files avoid.
 */
export function phaseGuardMarkerPath(sessionId, agentId) {
  const key = sanitizeForFilename(`${sessionId || 'unknown'}-${agentId || 'unknown'}`);
  return join(os.tmpdir(), 'task-orchestrator', `phase-guard-${key}.json`);
}

/** Shape: `{items: string[], blocks: number, enteredRoles: {[itemId]: string}}`. `enteredRoles`
 * is new (previously-written markers lack it) and defaults to `{}` on any missing/malformed
 * value, so an old marker degrades to the pre-entered-role behavior in phase-guard.mjs rather
 * than crashing. Any read failure degrades to the empty default. */
export function readPhaseGuardMarker(path) {
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf-8'));
    if (!parsed || typeof parsed !== 'object') return { items: [], blocks: 0, enteredRoles: {} };
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
  try {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, JSON.stringify(obj));
  } catch {
    // swallow all errors — a marker write must never crash a hook
  }
}

export function isFullUuid(value) {
  return typeof value === 'string' && UUID_RE.test(value);
}

/**
 * From an advance_item response payload's `results[]`, the itemIds worth recording: a full UUID
 * (a hex-prefix resolve failure echoes the raw, non-UUID input) AND either `applied===true`
 * (the transition succeeded) OR `errorCode==="gate_blocked"` (the "already in phase" case — the
 * item is still this agent's, it just didn't move). Every OTHER structured failure
 * (`not_claim_holder`, `rejected_by_policy`, `resource_unavailable`, `dependency_blocked`,
 * `validation_failed`, `invalid_transition`, `apply_failed`, `item_not_found`, `invalid_trigger`,
 * `invalid_actor`) means the transition never touched the item, or the item isn't this agent's —
 * branch on the presence of one of the two POSITIVE signals above, never on the absence of an
 * `errorCode` (a codeless `applied:false` is not a valid outcome shape any more; every failure
 * path in `advance_item` now carries one).
 */
export function extractRecordableItemIds(payload) {
  const results = Array.isArray(payload?.results) ? payload.results : [];
  return results
    .filter((r) => r && isFullUuid(r.itemId) && (r.applied === true || r.errorCode === 'gate_blocked'))
    .map((r) => r.itemId);
}

/**
 * Maps itemId -> actor (possibly undefined) from the raw `tool_input` of an advance_item call,
 * tolerating both call shapes (AdvanceItemTool.kt normalizes the singular shape into `transitions`
 * server-side, but this hook observes the caller's RAW tool_input, before that normalization):
 *  - batch: `{transitions: [{itemId, trigger, actor?}, ...]}` — each element's own actor.
 *  - singular sugar: `{itemId, trigger, actor?}` — one actor applies to that one itemId.
 * Malformed/missing input yields an empty map (nothing is treated as a workflow actor).
 */
export function buildActorMap(toolInput) {
  const map = new Map();
  if (!toolInput || typeof toolInput !== 'object') return map;
  if (Array.isArray(toolInput.transitions)) {
    for (const t of toolInput.transitions) {
      if (t && typeof t === 'object' && typeof t.itemId === 'string') {
        map.set(t.itemId, t.actor);
      }
    }
  } else if (typeof toolInput.itemId === 'string') {
    map.set(toolInput.itemId, toolInput.actor);
  }
  return map;
}

/** True only when `actor.parent` is a string starting with the literal prefix "workflow:". */
export function isWorkflowSeatActor(actor) {
  return !!actor && typeof actor === 'object' && typeof actor.parent === 'string' && actor.parent.startsWith('workflow:');
}

/**
 * From a (workflow-actor-filtered) advance_item response payload's `results[]`, the role each
 * recordable item actually ENTERED: `newRole` on an `applied:true` result, or `targetRole` on the
 * already-in-phase `errorCode:"gate_blocked"` case (the item is already sitting in that role).
 * Mirrors extractRecordableItemIds's inclusion rule so every id it returns has a chance at a role
 * here too; an item with no recognizable role string is simply omitted from the map.
 */
export function extractEnteredRoles(payload) {
  const results = Array.isArray(payload?.results) ? payload.results : [];
  const roles = {};
  for (const r of results) {
    if (!r || !isFullUuid(r.itemId)) continue;
    if (r.applied === true && typeof r.newRole === 'string' && r.newRole) {
      roles[r.itemId] = r.newRole;
    } else if (r.errorCode === 'gate_blocked' && typeof r.targetRole === 'string' && r.targetRole) {
      roles[r.itemId] = r.targetRole;
    }
  }
  return roles;
}

function emitEmpty() {
  process.stdout.write('{}');
  process.exit(0);
}

function main() {
  try {
    let raw = '';
    try {
      raw = readFileSync(0, 'utf-8');
    } catch {
      emitEmpty();
    }

    let hookInput;
    try {
      hookInput = JSON.parse(raw);
    } catch {
      emitEmpty();
    }

    // Headless ralph iteration: emit empty before recording anything — a ralph iteration never
    // dispatches subagents, so phase-guard.mjs (SubagentStop) would have nothing to check
    // anyway, but recording is skipped explicitly rather than relying on that always holding.
    if (isHeadlessIteration()) emitEmpty();

    // Only present inside a subagent call — nothing to guard for the main session.
    if (!hookInput.agent_id) emitEmpty();

    // No REST API configured → phase-guard.mjs can never read the gate back; recording is moot.
    if (!apiBaseUrl()) emitEmpty();

    // The MCP CallToolResult shape carries a human-readable `content[0].text` summary alongside
    // the machine-readable `structuredContent` object — prefer structuredContent when present
    // (avoids trying to JSON.parse the human summary text) and fall back to the raw tool_response
    // for shapes that lack it (bare {results,summary}, or the content[] wrapper with a JSON text
    // block, both handled by extractResponseJson's shape tolerance).
    const toolResponse = hookInput.tool_response;
    const structured = toolResponse?.structuredContent ?? toolResponse;
    const payload = extractResponseJson(structured);

    // Drop any result whose transition actor is a workflow seat (actor.parent startsWith
    // "workflow:") before recording — those seats never return through phase-guard.mjs's
    // SubagentStop check the way an ordinary subagent dispatch does.
    const actorMap = buildActorMap(hookInput.tool_input);
    const rawResults = Array.isArray(payload?.results) ? payload.results : [];
    const nonWorkflowResults = rawResults.filter((r) => !isWorkflowSeatActor(actorMap.get(r?.itemId)));
    const filteredPayload = { ...payload, results: nonWorkflowResults };

    const newItems = extractRecordableItemIds(filteredPayload);
    if (newItems.length === 0) emitEmpty();
    const newEnteredRoles = extractEnteredRoles(filteredPayload);

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
