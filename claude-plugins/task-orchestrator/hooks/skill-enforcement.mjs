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
// A note's skill pointer that is a served rule key (one `config` op `rules` list read per session,
// lib/served-rules.mjs, made only when advice would name a pointer) is advised as a rule to fetch,
// not a skill to invoke; any other pointer keeps the SKILL SUGGESTED text. A failed or unanswered
// rules read leaves today's text.
//
// In the main session it is advisory only: it never blocks the call (additionalContext is
// non-blocking). It warns at most once per (session, itemId, key) — see the marker
// discipline below — so a concise-by-design note that keeps getting re-upserted does not
// re-trigger the same suggestion forever.
//
// In a subagent (`agent_id` set) a note's skill pointers (never its served-rule pointers) must be on
// record (skill-record.mjs) or preloaded by the agent file's frontmatter `skills:` before the upsert:
// the first upsert of an (agent, item, key) with a skill missing is denied (`NOTE SKILL REQUIRED:`),
// a later one is released with a `task-orchestrator: proceeding without` warning. With no served-rule
// answer (servedKeys null) it keeps the advice and never denies. Rule advice is as in the main session.
//
// Time budget: one deadline, 4000 ms minus process.uptime(); each REST call gets min(its own timeout,
// remaining - 250 ms), and under 100 ms left it is skipped as failed.

import { locateConfig, isValidRootId } from './config-locator.mjs';
import { readSection, scalar } from './yaml-lite.mjs';
import { apiBaseUrl, callOp } from './lib/api-client.mjs';
import { readHookInput } from './lib/stdin.mjs';
import { emit, emitEmpty, emitDeny, emitSystemMessage } from './lib/emit.mjs';
import { opsOf } from './lib/ops.mjs';
import { sessionMarkerPath, readMarker, writeMarker, createMarkerOnce } from './lib/markers.mjs';
import { loadServedRuleKeys, splitPointers, RULES_TIMEOUT_MS } from './lib/served-rules.mjs';
import {
  NOTE_SKILL_REQUIRED_PREFIX,
  missingSkills,
  noteDeniedPath,
  denialStep,
  quoteList,
  releaseText,
  preloadedSkills,
} from './lib/seat-skill-state.mjs';

const FACTS_TIMEOUT_MS = 1500;
const DEADLINE_MS = 4000;
const deadline = Date.now() + (DEADLINE_MS - process.uptime() * 1000);

/** The timeout a REST call may use now, or null when under 100 ms of the budget is left. */
function budgetFor(ownTimeoutMs) {
  const t = Math.min(ownTimeoutMs, deadline - Date.now() - 250);
  return t < 100 ? null : t;
}
const ITEM_ID_RE = /^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{8,32})$/i;
export const INERT_MESSAGE =
  'task-orchestrator: skill-enforcement.mjs is inert: no REST API URL resolves (register the MCP server over http(s), set TASK_ORCHESTRATOR_API_URL, or write a client.json apiUrl); in 4.0 schema facts and gate state come from the server.';

/** A note entry's skills: its `skills` array when non-empty, else `[skill]` when a non-empty string, else []. */
function skillsOf(n) {
  if (Array.isArray(n.skills) && n.skills.length > 0) return n.skills.filter((s) => typeof s === 'string' && s);
  return typeof n.skill === 'string' && n.skill ? [n.skill] : [];
}

function isPlain(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

/**
 * {skillMap, maxLengthMap} (plain objects) over note entries; a later entry wins on a duplicate key. A skillMap value
 * is the note's list of skills in invocation order (`skills` when 2+, else `[skill]`).
 */
function mapsFromNotes(lists) {
  const skillMap = {};
  const maxLengthMap = {};
  for (const notes of lists) {
    if (!Array.isArray(notes)) continue;
    for (const n of notes) {
      if (!isPlain(n) || typeof n.key !== 'string' || !n.key || n.key === '__proto__') continue;
      const list = skillsOf(n);
      if (list.length > 0) skillMap[n.key] = list;
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
  const timeoutMs = budgetFor(FACTS_TIMEOUT_MS);
  if (timeoutMs === null) return null;
  const res = await callOp('config', { ops: [op] }, { timeoutMs });
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
      const timeoutMs = budgetFor(FACTS_TIMEOUT_MS);
      const res = timeoutMs === null ? null : await callOp('items_read', { select: 'schema', itemId }, { timeoutMs });
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
const candidates = [];
const newlyWarnedPairs = [];
// Subagent only: every upsert op whose note has pointers, whatever its body (the loaded-skill check).
const agentId = typeof hookInput.agent_id === 'string' && hookInput.agent_id !== '' ? hookInput.agent_id : null;
const pointerOps = [];

for (const note of ops) {
  const { key, body, bodyFromFile, itemId } = note;
  if (!key) continue;
  const { skillMap, maxLengthMap } = await mapsFor(itemId);
  if (!Object.prototype.hasOwnProperty.call(skillMap, key)) continue;
  if (agentId) {
    const names = (Array.isArray(skillMap[key]) ? skillMap[key] : [skillMap[key]]).filter((s) => typeof s === 'string' && s);
    if (names.length > 0) pointerOps.push({ itemId, key, names });
  }

  // bodyFromFile notes are resolved and size-checked server-side (the server validates
  // existence and the 65536-byte cap); the hook has no access to that file's content, so
  // it cannot apply the length/placeholder heuristic. Skip it rather than misjudging a
  // substantive file-backed note as length 0. A short literal `body` is unaffected.
  if (typeof bodyFromFile === 'string' && bodyFromFile.length > 0) continue;

  const dedupKey = `${itemId}::${key}`;
  if (warnedPairs[dedupKey]) continue; // already suggested once this session for this item+key

  const raw = skillMap[key];
  const skills = (Array.isArray(raw) ? raw : [raw]).filter((s) => typeof s === 'string' && s);
  if (skills.length === 0) continue;
  const trimmedBody = (body || '').trim();
  const bodyLen = trimmedBody.length;
  const floor = substantiveFloor(maxLengthMap, key);

  // Only check placeholders on short bodies — long notes that happen to start
  // with "Looks fine..." followed by substantive analysis should not be flagged.
  const isPlaceholder = bodyLen < floor &&
    PLACEHOLDER_PATTERNS.some(p => p.test(trimmedBody));

  if (!body || bodyLen < floor || isPlaceholder) {
    candidates.push({ key, skills, pair: dedupKey });
  }
}

if (candidates.length === 0 && pointerOps.length === 0) {
  process.exit(0);
}

// Served rule keys, read once and only now that advice or the loaded-skill check would name a pointer.
const rootId = projectRootId(configContent);
const rulesTimeoutMs = budgetFor(RULES_TIMEOUT_MS);
const servedKeys =
  rootId && rulesTimeoutMs !== null ? await loadServedRuleKeys(hookInput.session_id, rootId, { timeoutMs: rulesTimeoutMs }) : null;

// Subagent loaded-skill check: never without a served-rules answer (a rule pointer is never required).
const enforceNotes = agentId !== null && servedKeys !== null;
const denials = [];
const releases = [];
if (enforceNotes) {
  const preloaded = preloadedSkills(hookInput.agent_type);
  const seen = new Set();
  for (const { itemId, key, names } of pointerOps) {
    const pair = `${itemId}::${key}`;
    if (seen.has(pair)) continue;
    seen.add(pair);
    const { skills } = splitPointers(names, servedKeys);
    const missing = missingSkills(hookInput.session_id, agentId, skills, preloaded);
    if (missing.length === 0) continue;
    const step = denialStep(noteDeniedPath(hookInput.session_id, agentId, itemId, key));
    if (step === 'deny') {
      denials.push(
        `${NOTE_SKILL_REQUIRED_PREFIX} the note "${key}" on item ${itemId} needs ${quoteList(missing)} loaded first. ` +
          'Invoke each with the Skill tool, then retry this write.',
      );
    } else if (step === 'release') {
      releases.push(releaseText(missing, `note "${key}"`));
    }
  }
}

if (denials.length > 0) {
  emitDeny(denials.join('\n\n'));
  process.exit(0);
}

for (const { key, skills: names, pair } of candidates) {
  const { rules, skills } = splitPointers(names, servedKeys);
  const paragraphs = [];
  if (rules.length > 0) {
    paragraphs.push(
      `⊘ RULE SUGGESTED — the note "${key}" is schema-bound to served rule(s) ${rules.map((r) => `"${r}"`).join(', ')}. ` +
      `If you have not already, fetch ${rules.map((r) => `rule "${r}"`).join(', then ')} with config op "rules" (rootId ${rootId}) ` +
      `and follow it before finalizing this note. (Shown once per item+key per session.)`
    );
  }
  // Under the loaded-skill check a skill is denied, released or already on record: no skill advice.
  if (skills.length > 0 && !enforceNotes) {
    paragraphs.push(
      `⊘ SKILL SUGGESTED — the note "${key}" is schema-bound to the ${skills.map((s) => `/${s}`).join(', ')} framework${skills.length > 1 ? 's' : ''}. ` +
      `If you have not already, invoke the Skill tool with ${skills.map((s) => `skill="${s}"`).join(', then ')} and incorporate ` +
      `its structured evaluation before finalizing this note. If the Skill tool reports ` +
      `'Unknown skill', the schema's skill pointer is invalid — log that as an observation ` +
      `instead of retrying. (Shown once per item+key per session.)`
    );
  }
  if (paragraphs.length === 0) continue;
  warnings.push(paragraphs.join('\n'));
  newlyWarnedPairs.push(pair);
}

for (const pair of newlyWarnedPairs) {
  warnedPairs[pair] = true;
}
if (newlyWarnedPairs.length > 0) writeMarker(marker, warnedPairs);

if (warnings.length === 0 && releases.length === 0) {
  process.exit(0);
}

const output = {
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    additionalContext: [...releases, ...warnings].join('\n\n')
  }
};
if (releases.length > 0) output.systemMessage = releases.join('\n');

emit(output);
