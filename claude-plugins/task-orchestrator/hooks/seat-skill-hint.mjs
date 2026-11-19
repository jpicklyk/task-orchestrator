#!/usr/bin/env node
// PostToolUse (advance|items_read): names the skills a seat must load and the rule keys it must fetch, on its
// first own-item read.
//
// Trigger: an ok item-mode `items_read` `context` read (tool_input.itemId a UUID or 8-32 hex; the phase is
// `data.item.role`, the seat `data.callerSeat`), or each ok `advance` element with a `newRole` (the phase is
// `newRole`, the seat the element's `callerSeat`).
//
// Subagent (`agent_id` set): the seat is `callerSeat`, else the agent type's last segment when it is planner,
// implementer, reviewer or test-author; otherwise nothing. One required record per agent
// (lib/seat-skill-state.mjs), written when none exists, or when this read carries `callerSeat` and the record
// did not. Facts come from one REST `items_read` `schema` read of the item (1.5 s): `seats` of every phase
// and `features`; a failed read writes nothing. enforce = the server lists the seatSkills feature, so an
// older server gets the hint only and seat-skill-gate.mjs never refuses there. Required = resolved skills
// minus the agent file's preloaded `skills:`. The gate point follows the seat's phase: work -> file edits,
// queue or review -> the first note upsert of that phase role.
//
// Main session (no `agent_id`): an ok advance element of a call without a top-level seatToken; the seat is
// the new role's owner; once per (session, item, role); no record, never a refusal.
//
// Output: additionalContext beginning `SEAT SKILLS:` when a skill is required, naming the binding source
// (`Binding source: schema.` or `Binding source: plugin default.`) and telling an agent whose skill cannot
// load to stop and report; then, whenever the seat's role resolves in the seat rule table (lib/seat-rules.mjs,
// hooks/lib/seat-rules.json), a `SEAT RULES:` line naming its rule keys in order: the role's protocol key, its
// plugin-chosen keys, then the seat's own rules (note-derived, then declared or the role's defaults). Served
// keys come from one `config` op `rules` read (lib/served-rules.mjs) for the located config's project.rootId,
// in the same time budget; when that read does not answer, the keys are not filtered and the line says to skip
// another key that answers not_found. Rule keys are hint only: nothing is recorded or refused for a rule.
// Else `{}`. Fail-open: any error emits `{}`.

import { apiBaseUrl, callOp } from './lib/api-client.mjs';
import { readHookInput } from './lib/stdin.mjs';
import { emit, emitEmpty, contextOutput } from './lib/emit.mjs';
import { parseToolResult } from './lib/envelope.mjs';
import { sessionMarkerPath, createMarkerOnce } from './lib/markers.mjs';
import { resolveSeatSkills, seatNameFor, SEAT_SKILLS_FEATURE } from './lib/seat-skills.mjs';
import { loadSeatRules, seatRoleOf, seatRulesFor, promptKeysFor, SEAT_RULES_PREFIX } from './lib/seat-rules.mjs';
import { loadServedRuleKeys, RULES_TIMEOUT_MS } from './lib/served-rules.mjs';
import { locateConfig } from './config-locator.mjs';
import {
  SEAT_SKILLS_PREFIX,
  UNKNOWN_SKILL_TEXT,
  bindingSourceLabel,
  bindingSourceText,
  DEFAULT_SEAT_PHASE,
  gateForPhase,
  hash16,
  quoteList,
  readRequired,
  writeRequired,
  preloadedSkills,
} from './lib/seat-skill-state.mjs';

const SCHEMA_TIMEOUT_MS = 1500;
const DEADLINE_MS = 4000;
const ITEM_ID_RE = /^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{8,32})$/i;
const KNOWN_SEATS = ['planner', 'implementer', 'reviewer', 'test-author'];
const deadline = Date.now() + (DEADLINE_MS - process.uptime() * 1000);

function isPlain(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function nonEmpty(v) {
  return typeof v === 'string' && v.trim() !== '' ? v : null;
}

/** The read triggers of this call: [{itemId, phase, callerSeat}]. */
function triggersOf(input) {
  const ti = isPlain(input.tool_input) ? input.tool_input : {};
  const parsed = parseToolResult(input.tool_response);
  if (!parsed || parsed.legacy) return { kind: null, list: [] };
  if (typeof ti.select === 'string') {
    if (ti.select !== 'context' || typeof ti.itemId !== 'string' || !ITEM_ID_RE.test(ti.itemId)) return { kind: null, list: [] };
    if (parsed.ok !== true || !isPlain(parsed.data)) return { kind: null, list: [] };
    const role = isPlain(parsed.data.item) ? nonEmpty(parsed.data.item.role) : null;
    if (!role) return { kind: null, list: [] };
    return { kind: 'context', list: [{ itemId: ti.itemId, phase: role, callerSeat: nonEmpty(parsed.data.callerSeat) }] };
  }
  const list = [];
  for (const r of parsed.results) {
    if (!isPlain(r) || r.ok !== true) continue;
    if (typeof r.itemId !== 'string' || !ITEM_ID_RE.test(r.itemId) || !nonEmpty(r.newRole)) continue;
    list.push({ itemId: r.itemId, phase: r.newRole, callerSeat: nonEmpty(r.callerSeat) });
  }
  return { kind: 'advance', list };
}

/** {seats, notes, supported} from one items_read schema read, or null when it fails or the time budget is spent. */
async function schemaFacts(itemId) {
  const t = Math.min(SCHEMA_TIMEOUT_MS, deadline - Date.now() - 250);
  if (t < 100) return null;
  const res = await callOp('items_read', { select: 'schema', itemId }, { timeoutMs: t });
  if (!res || res.ok !== true || !isPlain(res.data)) return null;
  const seats = Array.isArray(res.data.seats) ? res.data.seats.filter(isPlain) : [];
  const notes = Array.isArray(res.data.notes) ? res.data.notes.filter(isPlain) : [];
  const supported = Array.isArray(res.data.features) && res.data.features.includes(SEAT_SKILLS_FEATURE);
  return { seats, notes, supported };
}

/** {rootId, served} for the rule hint: the located project.rootId and its served keys (a Set), or nulls. Read once per call. */
let ruleContext;
async function ruleContextFor(sessionId) {
  if (ruleContext !== undefined) return ruleContext;
  let rootId = null;
  let served = null;
  try {
    rootId = locateConfig().rootId || null;
    const t = Math.min(RULES_TIMEOUT_MS, deadline - Date.now() - 250);
    if (rootId && t >= 100) served = await loadServedRuleKeys(sessionId, rootId, { timeoutMs: t });
  } catch {
    served = null;
  }
  ruleContext = { rootId, served };
  return ruleContext;
}

function noteSkillNames(n) {
  if (Array.isArray(n.skills) && n.skills.length > 0) return n.skills.filter((x) => typeof x === 'string' && x);
  return typeof n.skill === 'string' && n.skill ? [n.skill] : [];
}

/**
 * {phase, enters} of a seat the schema names no entry for: the default phase of its role (an `-a<n>` fix-cycle
 * suffix read as its base), else the item's phase for an unknown seat. Only `implementer` itself, in work, enters.
 */
function unlistedSeatPhase(seat, itemPhase) {
  const base = typeof seat === 'string' ? seat.replace(/-a[1-9][0-9]*$/, '') : '';
  const phase = (Object.prototype.hasOwnProperty.call(DEFAULT_SEAT_PHASE, base) && DEFAULT_SEAT_PHASE[base]) || itemPhase;
  return { phase, enters: seat === 'implementer' && phase === 'work' };
}

/**
 * The SEAT RULES line for a seat on an item, or null when its role does not resolve in the seat rule table.
 * The seat's entry in the schema gives its phase, enters and declared rules; without an entry the seat's role
 * gives its phase (unlistedSeatPhase), so the line does not depend on the phase the item is in. The seat's notes
 * in that phase (every one on a schema without seats) give the note-derived rules.
 */
async function rulesText(input, facts, seat, phase, itemId) {
  const table = loadSeatRules();
  const named = facts.seats.filter((s) => s.name === seat);
  const entry = named.find((s) => s.phase === phase) || named[0];
  const unlisted = entry ? null : unlistedSeatPhase(seat, phase);
  const seatPhase = entry ? nonEmpty(entry.phase) || phase : unlisted.phase;
  const enters = entry ? entry.enters === true : unlisted.enters;
  const owned = facts.notes.filter((n) => n.role === seatPhase && (facts.seats.length === 0 || n.seat === seat));
  const stage = { seat, phase: seatPhase, enters, writes: seatPhase === 'work', notes: owned.map((n) => n.key) };
  const role = seatRoleOf(stage);
  if (!role || !table.roles[role]) return null;
  const { rootId, served } = await ruleContextFor(input.session_id);
  const noteRules = [];
  if (served) {
    for (const n of owned) for (const k of noteSkillNames(n)) if (served.has(k) && !noteRules.includes(k)) noteRules.push(k);
  }
  const { rules } = seatRulesFor(table, { role, seat, enters, declared: entry ? entry.rules : undefined, noteRules, served });
  const keys = promptKeysFor(table, { ...stage, rules });
  if (keys.length === 0) return null;
  const protocol = keys[0];
  const where = rootId ? `(rootId ${rootId})` : '(rootId: your project root)';
  let text =
    `${SEAT_RULES_PREFIX} you are the ${seat} seat on item ${itemId}. Fetch each of these rule keys with config op "rules" ` +
    `${where} and follow it, in this order: ${keys.join(', ')}. A not_found answer for ${protocol} is fatal: stop and report it.`;
  if (!served) text += ' A not_found answer for any other key: skip that key.';
  return text;
}

/**
 * {seat, phase, gate, skills, source, enforce} for a seat on an item, or null when no gate point applies or
 * skills resolve without a binding source. `source` is the label: 'schema' or 'plugin default'.
 */
function resolveFor(facts, seat, itemPhase) {
  const named = facts.seats.filter((s) => s.name === seat);
  const entry = named.find((s) => s.phase === itemPhase) || named[0];
  const phase = (entry && nonEmpty(entry.phase)) || DEFAULT_SEAT_PHASE[seat] || itemPhase;
  const gate = gateForPhase(phase);
  if (!gate) return null;
  const resolved = resolveSeatSkills({ seats: facts.seats, supported: facts.supported, callerSeat: seat, phase: entry ? entry.phase : itemPhase });
  const skills = resolved.skills;
  const source = bindingSourceLabel(resolved.source);
  if (skills.length > 0 && !source) return null;
  return { seat, phase, gate, skills, source: source || 'schema', enforce: facts.supported };
}

function hintText({ seat, itemId, phase, gate, required, source, enforce }) {
  const point = gate === 'edit' ? 'your first file edit' : `your first ${phase} note write`;
  let text =
    `${SEAT_SKILLS_PREFIX} you are the ${seat} seat on item ${itemId}. Invoke each of these skills with the Skill tool ` +
    `before ${point}, in this order: ${quoteList(required)}. ${bindingSourceText(source)} ${UNKNOWN_SKILL_TEXT}`;
  if (!enforce) text += ' (advisory: nothing is refused)';
  return text;
}

async function subagent(input, triggers) {
  const agentId = input.agent_id;
  const existing = readRequired(input.session_id, agentId);
  const lastSegment = typeof input.agent_type === 'string' ? input.agent_type.split(':').pop() : null;
  for (const t of triggers) {
    const seat = t.callerSeat || (KNOWN_SEATS.includes(lastSegment) ? lastSegment : null);
    if (!seat) continue;
    if (existing && !(t.callerSeat && !existing.callerSeat)) continue;
    const facts = await schemaFacts(t.itemId);
    if (!facts) return null;
    const resolved = resolveFor(facts, seat, t.phase);
    if (!resolved) return null;
    const preloaded = preloadedSkills(input.agent_type);
    const record = { itemId: t.itemId, ...resolved, preloaded, callerSeat: Boolean(t.callerSeat) };
    if (!writeRequired(input.session_id, agentId, record)) return null;
    const required = resolved.skills.filter((s) => !preloaded.includes(s));
    // A rebind (callerSeat over an agent-type record) hints skills again only when the list changed, and rule
    // keys again only when the seat changed.
    let skillsChanged = true;
    let seatChanged = true;
    if (existing) {
      const before = existing.skills.filter((s) => !existing.preloaded.includes(s));
      seatChanged = existing.seat !== resolved.seat;
      skillsChanged = before.join('\n') !== required.join('\n') || seatChanged;
    }
    const parts = [];
    if (required.length > 0 && skillsChanged) parts.push(hintText({ ...resolved, itemId: t.itemId, required }));
    if (seatChanged) {
      const rules = await rulesText(input, facts, seat, t.phase, t.itemId);
      if (rules) parts.push(rules);
    }
    return parts.length ? parts.join('\n') : null;
  }
  return null;
}

async function mainSession(input, triggers) {
  const token = isPlain(input.tool_input) ? input.tool_input.seatToken : undefined;
  if (typeof token === 'string' && token.length > 0) return null;
  const lines = [];
  for (const t of triggers) {
    const seat = seatNameFor({ phase: t.phase });
    if (!seat) continue;
    const facts = await schemaFacts(t.itemId);
    if (!facts) continue;
    const resolved = resolveFor(facts, seat, t.phase);
    if (!resolved) continue;
    const rules = await rulesText(input, facts, seat, t.phase, t.itemId);
    if (resolved.skills.length === 0 && !rules) continue;
    if (!createMarkerOnce(sessionMarkerPath(input.session_id, `seat-skill-hint-${hash16(`${t.itemId}::${t.phase}`)}`))) continue;
    if (resolved.skills.length > 0) lines.push(hintText({ ...resolved, itemId: t.itemId, required: resolved.skills }));
    if (rules) lines.push(rules);
  }
  return lines.length ? lines.join('\n') : null;
}

async function main() {
  const input = readHookInput();
  if (!input) return null;
  if (!apiBaseUrl()) return null;
  const { kind, list } = triggersOf(input);
  if (!kind || list.length === 0) return null;
  const agentId = input.agent_id;
  if (typeof agentId === 'string' && agentId !== '') return subagent(input, list);
  if (kind !== 'advance') return null;
  return mainSession(input, list);
}

let text = null;
try {
  text = await main();
} catch {
  text = null;
}
if (text) emit(contextOutput('PostToolUse', text));
else emitEmpty();
