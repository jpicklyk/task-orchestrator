#!/usr/bin/env node
// PostToolUse (advance|items_read): names the skills a seat must load, on its first own-item read.
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
// Output: additionalContext beginning `SEAT SKILLS:` when a skill is required, else `{}`. Fail-open: any
// error emits `{}`.

import { apiBaseUrl, callOp } from './lib/api-client.mjs';
import { readHookInput } from './lib/stdin.mjs';
import { emit, emitEmpty, contextOutput } from './lib/emit.mjs';
import { parseToolResult } from './lib/envelope.mjs';
import { sessionMarkerPath, createMarkerOnce } from './lib/markers.mjs';
import { resolveSeatSkills, seatNameFor, SEAT_SKILLS_FEATURE } from './lib/seat-skills.mjs';
import {
  SEAT_SKILLS_PREFIX,
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

/** {seats, supported} from one items_read schema read, or null when it fails or the time budget is spent. */
async function schemaFacts(itemId) {
  const t = Math.min(SCHEMA_TIMEOUT_MS, deadline - Date.now() - 250);
  if (t < 100) return null;
  const res = await callOp('items_read', { select: 'schema', itemId }, { timeoutMs: t });
  if (!res || res.ok !== true || !isPlain(res.data)) return null;
  const seats = Array.isArray(res.data.seats) ? res.data.seats.filter(isPlain) : [];
  const supported = Array.isArray(res.data.features) && res.data.features.includes(SEAT_SKILLS_FEATURE);
  return { seats, supported };
}

/** {seat, phase, gate, skills, enforce} for a seat on an item, or null when no gate point applies. */
function resolveFor(facts, seat, itemPhase) {
  const named = facts.seats.filter((s) => s.name === seat);
  const entry = named.find((s) => s.phase === itemPhase) || named[0];
  const phase = (entry && nonEmpty(entry.phase)) || DEFAULT_SEAT_PHASE[seat] || itemPhase;
  const gate = gateForPhase(phase);
  if (!gate) return null;
  const { skills } = resolveSeatSkills({ seats: facts.seats, supported: facts.supported, callerSeat: seat, phase: entry ? entry.phase : itemPhase });
  return { seat, phase, gate, skills, enforce: facts.supported };
}

function hintText({ seat, itemId, phase, gate, required, enforce }) {
  const point = gate === 'edit' ? 'your first file edit' : `your first ${phase} note write`;
  let text =
    `${SEAT_SKILLS_PREFIX} you are the ${seat} seat on item ${itemId}. Invoke each of these skills with the Skill tool ` +
    `before ${point}, in this order: ${quoteList(required)}.`;
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
    if (required.length === 0) return null;
    // A rebind (callerSeat over an agent-type record) hints again only when the list changed.
    if (existing) {
      const before = existing.skills.filter((s) => !existing.preloaded.includes(s));
      if (before.join('\n') === required.join('\n') && existing.seat === resolved.seat) return null;
    }
    return hintText({ ...resolved, itemId: t.itemId, required });
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
    if (!resolved || resolved.skills.length === 0) continue;
    if (!createMarkerOnce(sessionMarkerPath(input.session_id, `seat-skill-hint-${hash16(`${t.itemId}::${t.phase}`)}`))) continue;
    lines.push(hintText({ ...resolved, itemId: t.itemId, required: resolved.skills }));
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
