#!/usr/bin/env node
// SubagentStop — re-checks the phase gate for every item this subagent recorded via
// phase-guard-record.mjs (PostToolUse:advance_item) and, when required notes are still missing
// for a work/review-phase item, blocks the subagent's stop so it gets sent back with the missing
// keys named. This implements a harness-level backstop for a subagent that ends its turn with a
// progress report instead of finished work: treat the early stop as a report, check the external
// checklist (the item's gate status), and send a capped continuation naming what's still open.
//
// Entered-role gating: phase-guard-record.mjs now also records, per item, the ROLE this agent
// entered (`marker.enteredRoles[itemId]` — `newRole` on success, or `previousRole` on the
// already-in-phase gate_blocked case, which is the item's CURRENT role at the time of the blocked
// transition — see phase-guard-record.mjs's extractEnteredRoles). When a role was recorded for an
// item, this hook blocks on
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
// owns (SEAT_ROLE) — it is still fetched, just never named as a blocker. Independently of seat,
// keys in TEST_AUTHOR_OWNED_KEYS (e.g. `test-manifest`) are dropped from `missing` unless the
// stopping agent is itself a test-author seat (`isTestAuthorAgentType`) — those notes are filled
// by a separately dispatched seat and must never be demanded of the implementer or reviewer. The
// DTO's `skillPointer`/`guidanceKey` describe only the FIRST raw missing key, so the block reason
// surfaces them only when that first key survives the test-author filter.
//
// Known limitation (non-goal, not addressed here): the guard only engages for subagents that
// enter their phase with `advance_item(start)` — the plugin's agent-owned-phase protocol. A
// subagent dispatched under an orchestrator-owns-transitions contract (never calling
// advance_item itself) has nothing recorded by phase-guard-record.mjs, so the guard stays inert
// for it.
//
// Fail-open everywhere: no REST API configured, no state file for this agent (also covers
// Claude Code's own internal agents — prompt suggestions, etc. — which fire SubagentStop too),
// the block cap already reached, any parse/fetch/timeout error, or a per-item non-2xx response
// (403 — token without READ, 404) all result in that item never blocking. A silent `{}` on
// stdout and exit 0 is always the floor.

import { readFileSync, unlinkSync } from 'fs';
import { resolve } from 'path';
import { fileURLToPath } from 'url';
import { apiBaseUrl, authHeader, fetchWithTimeout } from './api-client.mjs';
import { phaseGuardMarkerPath, readPhaseGuardMarker, writePhaseGuardMarker } from './phase-guard-record.mjs';
import { isHeadlessIteration, phaseOwnerSeat, isTestAuthorAgentType } from './execution-mode.mjs';

const MAX_BLOCKS_PER_AGENT = 2;
const GATE_TIMEOUT_MS = 2000;
const BLOCKING_ROLES = new Set(['work', 'review']);
const SEAT_ROLE = { implementer: 'work', reviewer: 'review' };
export const TEST_AUTHOR_OWNED_KEYS = new Set(['test-manifest']);

function emitEmpty() {
  process.stdout.write('{}');
  process.exit(0);
}

function emitBlock(reason) {
  process.stdout.write(JSON.stringify({ decision: 'block', reason }));
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

async function fetchGate(base, itemId) {
  try {
    const res = await fetchWithTimeout(`${base}/api/v1/items/${itemId}/gate`, { headers: authHeader() }, GATE_TIMEOUT_MS);
    if (res.status !== 200) return null;
    return await res.json();
  } catch {
    return null;
  }
}

function buildReason(blockers) {
  const parts = blockers.map(({ gate, missing, hintValid }) => {
    const uuid8 = gate.itemId.slice(0, 8);
    const hint = !hintValid
      ? ''
      : gate.skillPointer
        ? ` Invoke the ${gate.skillPointer} skill for guidance.`
        : gate.guidanceKey
          ? ` See guidance: ${gate.guidanceKey}.`
          : '';
    return `Item ${uuid8} "${gate.title}" is in ${gate.role} with required notes still missing: ${missing.join(', ')}.${hint}`;
  });
  return (
    `${parts.join(' ')} Fill them via manage_notes(upsert) before returning. ` +
    `If something blocks you, say what blocks you and stop.`
  );
}

async function main() {
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

    // Headless ralph iteration: emit empty before any fetch — a ralph iteration never dispatches
    // subagents, so this hook would find no recorded items anyway, but skip the network round
    // trip explicitly rather than relying on that always holding.
    if (isHeadlessIteration()) emitEmpty();

    const base = apiBaseUrl();
    if (!base) emitEmpty();

    const path = phaseGuardMarkerPath(hookInput.session_id, hookInput.agent_id);
    const marker = readPhaseGuardMarker(path);
    if (!marker.items || marker.items.length === 0) emitEmpty();

    if (marker.blocks >= MAX_BLOCKS_PER_AGENT) {
      deleteMarker(path);
      emitEmpty();
    }

    const gates = await Promise.all(marker.items.map((itemId) => fetchGate(base, itemId)));

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
      const missing = isTestAuthor
        ? normalizedMissing
        : normalizedMissing.filter((key) => !TEST_AUTHOR_OWNED_KEYS.has(key));
      if (missing.length === 0) continue;
      // The DTO's skillPointer/guidanceKey describe only the FIRST raw missing key; only surface
      // them when that key survived the test-author filter above.
      const hintValid = missing[0] === normalizedMissing[0];
      blockers.push({ gate, missing, hintValid });
    }

    if (blockers.length === 0) {
      deleteMarker(path);
      emitEmpty();
    }

    writePhaseGuardMarker(path, { items: marker.items, blocks: marker.blocks + 1, enteredRoles: marker.enteredRoles || {} });
    emitBlock(buildReason(blockers));
  } catch {
    emitEmpty();
  }
}

// Only auto-run when invoked directly as a hook (`node phase-guard.mjs`), not when imported.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
