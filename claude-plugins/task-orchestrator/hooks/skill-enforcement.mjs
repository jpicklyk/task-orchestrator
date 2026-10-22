#!/usr/bin/env node
// PreToolUse notes_write — suggests skill invocation for notes with skill requirements.
// Acts on `tool_input.ops[]` elements with `op === 'upsert'` (fields itemId, key, role, body,
// bodyFromFile) and checks whether the note body being upserted is substantive enough to reflect
// skill-framework output.
//
// Schema facts come from the server, never from config.yaml: once per session the hook reads
// `config` op `effective` (rootId from the located config's `project.rootId` when valid, else
// omitted) and caches the derived `{rootId, skillMap, maxLengthMap}` in a session marker; per op it
// reads `items_read` select `schema` for that op's itemId (once per distinct id per invocation) and
// prefers that item's own notes when the item has a schema. With no API URL it says so once per
// session (a systemMessage) and then stays silent; a failed or absent `effective` result writes no
// marker and the hook is silent.
//
// Advisory only: this hook never blocks the call (additionalContext is non-blocking) and
// never claims to. It warns at most once per (session, itemId, key) — see the marker
// discipline below — so a concise-by-design note that keeps getting re-upserted does not
// re-trigger the same suggestion forever.

import { locateConfig, isValidRootId } from './config-locator.mjs';
import { readSection, scalar } from './yaml-lite.mjs';
import { apiBaseUrl, callOp } from './lib/api-client.mjs';
import { readHookInput } from './lib/stdin.mjs';
import { emit, emitEmpty, emitSystemMessage } from './lib/emit.mjs';
import { opsOf } from './lib/ops.mjs';
import { sessionMarkerPath, readMarker, writeMarker, createMarkerOnce } from './lib/markers.mjs';

const FACTS_TIMEOUT_MS = 1500;
const ITEM_ID_RE = /^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{8,32})$/i;
export const INERT_MESSAGE =
  'task-orchestrator: skill-enforcement.mjs is inert: no REST API URL resolves (set TASK_ORCHESTRATOR_API_URL or client.json apiUrl); in 4.0 schema facts and gate state come from the server.';

function isPlain(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

/** {skillMap, maxLengthMap} (plain objects) over note entries; a later entry wins on a duplicate key. */
function mapsFromNotes(lists) {
  const skillMap = {};
  const maxLengthMap = {};
  for (const notes of lists) {
    if (!Array.isArray(notes)) continue;
    for (const n of notes) {
      if (!isPlain(n) || typeof n.key !== 'string' || !n.key || n.key === '__proto__') continue;
      if (typeof n.skill === 'string' && n.skill) skillMap[n.key] = n.skill;
      if (Number.isInteger(n.maxLength) && n.maxLength >= 0) maxLengthMap[n.key] = n.maxLength;
    }
  }
  return { skillMap, maxLengthMap };
}

function projectRootId(text) {
  try {
    const section = readSection(text, 'project', { blockOnly: true });
    if (!section) return null;
    const id = scalar(section.lines, 'rootId');
    return isValidRootId(id) ? id : null;
  } catch {
    return null;
  }
}

/** The session's schema facts: the cached marker, else one `config` `effective` read. Null when unavailable. */
async function loadFacts(sessionId, rootId) {
  const path = sessionMarkerPath(sessionId, 'schema-facts');
  const cached = readMarker(path, null);
  if (cached && isPlain(cached.skillMap) && isPlain(cached.maxLengthMap) && (cached.rootId ?? null) === rootId) {
    return { skillMap: cached.skillMap, maxLengthMap: cached.maxLengthMap };
  }
  const op = rootId ? { op: 'effective', rootId } : { op: 'effective' };
  const res = await callOp('config', { ops: [op] }, { timeoutMs: FACTS_TIMEOUT_MS });
  const r = res && Array.isArray(res.results) ? res.results[0] : null;
  if (!r || r.ok !== true || !Array.isArray(r.schemas)) return null;
  const lists = r.schemas.filter(isPlain).map((sc) => sc.notes);
  if (isPlain(r.defaultSchema)) lists.push(r.defaultSchema.notes);
  const facts = mapsFromNotes(lists);
  writeMarker(path, { rootId, ...facts });
  return facts;
}

// Read hook input from stdin
const hookInput = readHookInput();
if (!hookInput) process.exit(0);

const ops = opsOf(hookInput.tool_input).filter((o) => o.op === 'upsert');
if (ops.length === 0) process.exit(0);

const configContent = locateConfig().text;
if (!configContent) {
  // No config file found — no skill requirements to enforce
  process.exit(0);
}

if (!apiBaseUrl()) {
  if (createMarkerOnce(sessionMarkerPath(hookInput.session_id, 'inert-skill-enforcement'))) {
    emitSystemMessage(INERT_MESSAGE);
  } else {
    emitEmpty();
  }
  process.exit(0);
}

const sessionFacts = await loadFacts(hookInput.session_id, projectRootId(configContent));
if (!sessionFacts) process.exit(0);

// --- Once-per-(session, item, key) marker -----------------------------------------------
// Fail-open discipline lives in lib/markers.mjs: any I/O error is treated as "no marker" /
// "write silently dropped"; a marker failure must never crash the hook. The file is
// <tmp>/task-orchestrator/<session>/skill-enforce.json.

const marker = sessionMarkerPath(hookInput.session_id, 'skill-enforce');
const warnedPairs = readMarker(marker);

// Per-item schema view, once per distinct itemId per invocation. A usable view (not schemaFree)
// replaces the session maps for that item's ops.
const itemMaps = new Map();
async function mapsFor(itemId) {
  if (typeof itemId !== 'string' || !ITEM_ID_RE.test(itemId)) return sessionFacts;
  if (!itemMaps.has(itemId)) {
    let maps = sessionFacts;
    try {
      const res = await callOp('items_read', { select: 'schema', itemId }, { timeoutMs: FACTS_TIMEOUT_MS });
      if (res && res.ok === true && isPlain(res.data) && res.data.schemaFree !== true && Array.isArray(res.data.notes)) {
        maps = mapsFromNotes([res.data.notes]);
      }
    } catch {
      // keep the session maps
    }
    itemMaps.set(itemId, maps);
  }
  return itemMaps.get(itemId);
}

// Placeholder patterns that indicate the skill framework was NOT followed.
// Defined at module scope — compiled once, shared across all note checks.
const PLACEHOLDER_PATTERNS = [
  /^n\/?a$/i,
  /^looks?\s+(fine|good|ok)/i,
  /^no\s+issues?\s*(found)?/i,
  /^todo$/i,
  /^placeholder$/i,
  /^tbd$/i,
  /^pending$/i,
  /^will\s+fill\s+(later|soon)/i
];

// Default minimum character count for a note body to be considered substantive.
const DEFAULT_SUBSTANTIVE_LENGTH = 200;

// Effective substantive floor for a key: when the key's configured maxLength is small
// (< 800), a flat 200-char floor would be unreachable for a compliant note, so the floor
// scales down with maxLength instead. Otherwise the default 200-char floor applies.
function substantiveFloor(maxLengthMap, key) {
  const maxLength = Object.prototype.hasOwnProperty.call(maxLengthMap, key) ? maxLengthMap[key] : undefined;
  if (Number.isInteger(maxLength) && maxLength < 800) {
    return Math.min(DEFAULT_SUBSTANTIVE_LENGTH, Math.floor(maxLength / 4));
  }
  return DEFAULT_SUBSTANTIVE_LENGTH;
}

// Check each note being upserted against skill requirements
const warnings = [];
const newlyWarnedPairs = [];

for (const note of ops) {
  const { key, body, bodyFromFile, itemId } = note;
  if (!key) continue;
  const { skillMap, maxLengthMap } = await mapsFor(itemId);
  if (!Object.prototype.hasOwnProperty.call(skillMap, key)) continue;

  // bodyFromFile notes are resolved and size-checked server-side (the server validates
  // existence and the 65536-byte cap); the hook has no access to that file's content, so
  // it cannot apply the length/placeholder heuristic. Skip it rather than misjudging a
  // substantive file-backed note as length 0. A short literal `body` is unaffected.
  if (typeof bodyFromFile === 'string' && bodyFromFile.length > 0) continue;

  const dedupKey = `${itemId}::${key}`;
  if (warnedPairs[dedupKey]) continue; // already suggested once this session for this item+key

  const skill = skillMap[key];
  const trimmedBody = (body || '').trim();
  const bodyLen = trimmedBody.length;
  const floor = substantiveFloor(maxLengthMap, key);

  // Only check placeholders on short bodies — long notes that happen to start
  // with "Looks fine..." followed by substantive analysis should not be flagged.
  const isPlaceholder = bodyLen < floor &&
    PLACEHOLDER_PATTERNS.some(p => p.test(trimmedBody));

  if (!body || bodyLen < floor || isPlaceholder) {
    warnings.push(
      `⊘ SKILL SUGGESTED — the note "${key}" is schema-bound to the /${skill} framework. ` +
      `If you have not already, invoke the Skill tool with skill="${skill}" and incorporate ` +
      `its structured evaluation before finalizing this note. If the Skill tool reports ` +
      `'Unknown skill', the schema's skill pointer is invalid — log that as an observation ` +
      `instead of retrying. (Shown once per item+key per session.)`
    );
    newlyWarnedPairs.push(dedupKey);
  }
}

if (warnings.length === 0) {
  process.exit(0);
}

for (const pair of newlyWarnedPairs) {
  warnedPairs[pair] = true;
}
writeMarker(marker, warnedPairs);

const output = {
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    additionalContext: warnings.join('\n\n')
  }
};

emit(output);
