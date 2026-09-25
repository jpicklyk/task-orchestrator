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

import { readFileSync, writeFileSync, mkdirSync } from 'fs';
import { resolve, join, dirname } from 'path';
import { fileURLToPath } from 'url';
import os from 'os';
import { apiBaseUrl } from './api-client.mjs';
import { extractResponseJson } from './retro-lib.mjs';

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

/** Shape: `{items: string[], blocks: number}`. Any read failure degrades to the empty default. */
export function readPhaseGuardMarker(path) {
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf-8'));
    if (!parsed || typeof parsed !== 'object') return { items: [], blocks: 0 };
    return {
      items: Array.isArray(parsed.items) ? parsed.items : [],
      blocks: Number.isInteger(parsed.blocks) ? parsed.blocks : 0,
    };
  } catch {
    return { items: [], blocks: 0 };
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
    const newItems = extractRecordableItemIds(payload);
    if (newItems.length === 0) emitEmpty();

    const path = phaseGuardMarkerPath(hookInput.session_id, hookInput.agent_id);
    const marker = readPhaseGuardMarker(path);
    const merged = [...new Set([...marker.items, ...newItems])];
    const items = merged.length > MAX_ITEMS_PER_AGENT ? merged.slice(merged.length - MAX_ITEMS_PER_AGENT) : merged;
    writePhaseGuardMarker(path, { items, blocks: marker.blocks });

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
