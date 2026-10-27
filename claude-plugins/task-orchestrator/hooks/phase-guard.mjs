#!/usr/bin/env node
// SubagentStop — re-checks the phase gate for every item this subagent recorded via
// phase-guard-record.mjs (PostToolUse:advance|items_read) and, when required notes are still missing
// for a work/review-phase item, blocks the subagent's stop so it gets sent back with the missing
// keys named. This implements a harness-level backstop for a subagent that ends its turn with a
// progress report instead of finished work: treat the early stop as a report, check the external
// checklist (the item's gate status), and send a capped continuation naming what's still open.
//
// Entered-role gating: phase-guard-record.mjs also records, per item, the ROLE this agent
// entered (`marker.enteredRoles[itemId]` — `newRole` on a successful `start`, or the role the
// seat owns when its first context read found the item already in it; `gate_blocked` is never
// recorded). When a role was recorded for an item, this hook blocks on
// it ONLY if the item's CURRENT gate role still equals that entered role — if a later seat has
// since advanced the item further, gate.role differs from the recorded entered role and the item
// is skipped entirely, so an earlier seat is never blocked on notes belonging to a phase it never
// owned. A marker with no recorded role for an item (e.g. one written before this field existed)
// falls back to the prior role-agnostic behavior (BLOCKING_ROLES + seat filtering only).
//
// Seat awareness: the hook-input `agent_type` (documented SubagentStop field, same as
// SubagentStart) identifies which seat is stopping. `phaseOwnerSeat()` maps it to `'implementer'`
// (owns `work`), `'reviewer'` (owns `review`), or `null` (unrecognised/pre-field build — keeps the
// prior role-agnostic behaviour). A recognised seat never blocks on an item outside the role it
// owns (SEAT_ROLE) — it is still fetched, just never named as a blocker.
//
// Seat buckets: when the context view's `gateStatus.missingBySeat` is an object, the stopping seat is
// known (`phaseOwnerSeat()`, or `'test-author'` via `isTestAuthorAgentType`), the view's current-phase
// `seats` array names that seat (exact match) and its bucket is absent or an array, the hook blocks only
// on `missingBySeat[seat]` (an absent bucket is empty). The `unowned` bucket and other seats' buckets
// never block, and the server's ownership wins over TEST_AUTHOR_OWNED_KEYS: a schema that gives
// `test-manifest` to the implementer blocks the implementer on it. Otherwise the fallback applies:
// keys in TEST_AUTHOR_OWNED_KEYS (e.g. `test-manifest`) are dropped from the flat `missing` list
// unless the stopping agent is itself a test-author seat. The DTO's `skillPointer`/`guidanceKey`
// describe only the FIRST raw missing key, so the block reason surfaces them only when that first
// raw key is also the first key left after the bucket or fallback filter.
//
// Known limitation (non-goal, not addressed here): the guard only engages for subagents that
// either send `start` or, as a phase-owner seat, make an item-mode context read of an item
// already in their phase. A subagent dispatched under an orchestrator-owns-transitions contract
// that does neither has nothing recorded by phase-guard-record.mjs, so the guard stays inert for it.
//
// Fail-open everywhere: no REST API configured, no state file for this agent (also covers
// Claude Code's own internal agents — prompt suggestions, etc. — which fire SubagentStop too),
// the block cap already reached, any parse/fetch/timeout error, or a per-item non-2xx response
// (403 — token without READ, 404) all result in that item never blocking. A silent `{}` on
// stdout and exit 0 is always the floor.
//
// The gate is read with items_read select "context" over POST /api/v1/ops/items_read; with no API
// URL the hook says so once per session (a systemMessage) and then stays silent.
//
// A2 independence-attestation violations: `gate.gateStatus.violations` (present only when
// independence mode is not OFF and the resolved schema declares `independent_of` somewhere;
// absent otherwise, in which case this hook behaves exactly as before A2). This hook honours a
// REJECT-mode block the same way it honours missing required notes: it never re-derives
// `blocksAdvance` itself, it trusts the context view's own `gateStatus.canAdvance` (already false when a
// non-waived violation blocks) and only ever names the stopping agent's OWN seat's non-waived
// violations, mirroring the seat-only note-missing filter above. A `warn`-mode item never trips
// this (its `canAdvance` stays true), and a waived violation never blocks in any mode.

import { unlinkSync } from 'fs';
import { resolve } from 'path';
import { fileURLToPath } from 'url';
import { apiBaseUrl, callOp } from './lib/api-client.mjs';
import { readHookInput } from './lib/stdin.mjs';
import { emitEmpty as writeEmpty, emitBlock as writeBlock, emitSystemMessage } from './lib/emit.mjs';
import { createMarkerOnce, sessionMarkerPath } from './lib/markers.mjs';
import { locateConfig } from './config-locator.mjs';
import { phaseGuardMarkerPath, readPhaseGuardMarker, writePhaseGuardMarker, SEAT_ROLE } from './phase-guard-record.mjs';
import { isHeadlessIteration, phaseOwnerSeat, isTestAuthorAgentType } from './execution-mode.mjs';

const MAX_BLOCKS_PER_AGENT = 2;
const GATE_TIMEOUT_MS = 2000;
const BLOCKING_ROLES = new Set(['work', 'review']);
export const TEST_AUTHOR_OWNED_KEYS = new Set(['test-manifest']);

function emitEmpty() {
  writeEmpty();
  process.exit(0);
}

function emitBlock(reason) {
  writeBlock(reason);
  process.exit(0);
}

function deleteMarker(path) {
  try {
    unlinkSync(path);
  } catch {
    // swallow — a missing/unremovable marker is not fatal, it just lingers until overwritten
  }
}

/** A1's GateStatusDto.missing is a list of key strings, but tolerate an object shape (`{key}`)
 * defensively in case a future server version normalizes differently. */
function missingKey(entry) {
  return typeof entry === 'string' ? entry : entry?.key;
}

/**
 * The gate for one marker item, read with `items_read` `select: "context"` over POST
 * /api/v1/ops/items_read. Keyed by the marker id it was requested for (not the compact id the view
 * returns). Any non-ok envelope (not_found, forbidden, unauthenticated, unavailable, client_error)
 * or null yields null, which never blocks that item.
 */
async function fetchGate(itemId) {
  try {
    const res = await callOp('items_read', { select: 'context', itemId }, { timeoutMs: GATE_TIMEOUT_MS });
    if (!res || res.ok !== true || !res.data || typeof res.data !== 'object') return null;
    const d = res.data;
    return {
      itemId,
      title: typeof d.item?.title === 'string' ? d.item.title : '',
      role: d.item?.role,
      gateStatus: d.gateStatus,
      seats: d.seats,
      guidanceKey: d.guidanceKey,
      skillPointer: d.skillPointer,
    };
  } catch {
    return null;
  }
}

export const INERT_MESSAGE =
  'task-orchestrator: phase-guard.mjs is inert: no REST API URL resolves (register the MCP server over http(s), set TASK_ORCHESTRATOR_API_URL, or write a client.json apiUrl); in 4.0 schema facts and gate state come from the server.';

/** Renders one A2 violation as `key (constraint)`, e.g. `test-manifest (same_actor)` — never an
 * actor id, proof, or claim (the context view's `violations` array is already actor-free). */
function formatViolation(v) {
  return `${v.key} (${v.constraint})`;
}

function buildReason(blockers) {
  const parts = blockers.map(({ gate, missing, hintValid, violationBlockers }) => {
    const uuid8 = gate.itemId.slice(0, 8);
    const hint = !hintValid
      ? ''
      : gate.skillPointer
        ? ` Invoke the ${gate.skillPointer} skill for guidance.`
        : gate.guidanceKey
          ? ` See guidance: ${gate.guidanceKey}.`
          : '';
    const segments = [];
    if (missing.length > 0) segments.push(`required notes still missing: ${missing.join(', ')}`);
    if (violationBlockers.length > 0) {
      segments.push(`independence-attestation violations blocking this transition: ${violationBlockers.map(formatViolation).join(', ')}`);
    }
    return `Item ${uuid8} "${gate.title}" is in ${gate.role} with ${segments.join(' and ')}.${hint}`;
  });
  return (
    `${parts.join(' ')} Fill them via notes_write (op "upsert") before returning. ` +
    `If something blocks you, say what blocks you and stop.`
  );
}

async function main() {
  try {
    const hookInput = readHookInput();
    if (!hookInput) emitEmpty();

    // The task-orchestrator-mod plugin already guarded this stop in-process (it sets `to_mod_active`
    // before forwarding), so this command hook stands down.
    if (hookInput?.to_mod_active === true) emitEmpty();

    // Headless ralph iteration: emit empty before any fetch — a ralph iteration never dispatches
    // subagents, so this hook would find no recorded items anyway, but skip the network round
    // trip explicitly rather than relying on that always holding.
    if (isHeadlessIteration()) emitEmpty();

    // Workflow seats follow their own script-driven transition logic and are never blocked here.
    if (hookInput.agent_type === 'workflow-subagent') emitEmpty();

    const base = apiBaseUrl();
    if (!base) {
      // A located config with no API URL: say so once per session, then stay silent.
      const loc = locateConfig();
      if (loc && loc.scope !== 'none' && createMarkerOnce(sessionMarkerPath(hookInput.session_id, 'inert-phase-guard'))) {
        emitSystemMessage(INERT_MESSAGE);
        process.exit(0);
      }
      emitEmpty();
    }

    const path = phaseGuardMarkerPath(hookInput.session_id, hookInput.agent_id);
    const marker = readPhaseGuardMarker(path);
    if (!marker.items || marker.items.length === 0) emitEmpty();

    if (marker.blocks >= MAX_BLOCKS_PER_AGENT) {
      deleteMarker(path);
      emitEmpty();
    }

    const gates = await Promise.all(marker.items.map((itemId) => fetchGate(itemId)));

    const seat = phaseOwnerSeat(hookInput.agent_type);
    const isTestAuthor = isTestAuthorAgentType(hookInput.agent_type);
    const enteredRoles = marker.enteredRoles || {};

    const blockers = [];
    for (const gate of gates) {
      if (!gate) continue; // non-2xx / fetch error / timeout for this item — does not block
      const enteredRole = enteredRoles[gate.itemId];
      // A recorded entered role takes precedence over the item's current role: only block when
      // the item is STILL in the role this agent entered. A later seat advancing the item past it
      // makes gate.role diverge from enteredRole, so this item is skipped rather than blocking on
      // a phase this agent never owned.
      if (enteredRole && gate.role !== enteredRole) continue;
      if (!BLOCKING_ROLES.has(gate.role)) continue; // queue/blocked/terminal — never blocks
      // A recognised seat only ever answers for the phase it owns — still fetched above, but
      // never named as a blocker outside that role (e.g. a reviewer stopping on a work-phase item).
      if (seat && gate.role !== SEAT_ROLE[seat]) continue;
      const rawMissing = Array.isArray(gate.gateStatus?.missing) ? gate.gateStatus.missing : [];
      const normalizedMissing = rawMissing.map(missingKey).filter(Boolean);
      const stopSeat = seat ?? (isTestAuthor ? 'test-author' : null);
      const buckets = gate.gateStatus?.missingBySeat;
      const bucketMode =
        buckets !== null && typeof buckets === 'object' && !Array.isArray(buckets) &&
        stopSeat !== null &&
        Array.isArray(gate.seats) && gate.seats.some((s) => s && s.name === stopSeat) &&
        (buckets[stopSeat] === undefined || Array.isArray(buckets[stopSeat]));
      const missing = bucketMode
        ? (buckets[stopSeat] ?? []).map(missingKey).filter(Boolean)
        : isTestAuthor
          ? normalizedMissing
          : normalizedMissing.filter((key) => !TEST_AUTHOR_OWNED_KEYS.has(key));

      // A2: only a REJECT-mode block can ever contribute a violation-based blocker. `canAdvance`
      // alone is not the signal — it is also false whenever the gate's raw `missing` list is
      // non-empty (warn mode with missing notes included), so gating on `canAdvance === false`
      // by itself would report warn-mode missing-note findings as independence blockers. Only
      // when the raw missing list is EMPTY does `canAdvance === false` imply a genuine
      // reject-mode independence block. Within that, only this agent's OWN seat's non-waived
      // violations are named (`!seat` keeps the pre-A1 role-agnostic fallback for an
      // unrecognised/legacy agent_type, same convention the seat-role filter above uses); a
      // waived entry, or one raised against a different seat, never blocks here either.
      const rawViolations = Array.isArray(gate.gateStatus?.violations) ? gate.gateStatus.violations : null;
      const violationBlockers =
        rawViolations && rawMissing.length === 0 && gate.gateStatus?.canAdvance === false
          ? rawViolations.filter((v) => v && v.waived !== true && (!seat || v.seat === seat))
          : [];

      if (missing.length === 0 && violationBlockers.length === 0) continue;
      // The DTO's skillPointer/guidanceKey describe only the FIRST raw missing key; only surface
      // them when that key survived the test-author filter above, and only when there IS a missing
      // note (a violations-only block has no note-fill hint to attach).
      const hintValid = missing.length > 0 && missing[0] === normalizedMissing[0];
      blockers.push({ gate, missing, hintValid, violationBlockers });
    }

    if (blockers.length === 0) {
      deleteMarker(path);
      emitEmpty();
    }

    writePhaseGuardMarker(path, { items: marker.items, blocks: marker.blocks + 1, enteredRoles: marker.enteredRoles || {},
      contextReadSeen: marker.contextReadSeen,
    });
    emitBlock(buildReason(blockers));
  } catch {
    emitEmpty();
  }
}

// Only auto-run when invoked directly as a hook (`node phase-guard.mjs`), not when imported.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
