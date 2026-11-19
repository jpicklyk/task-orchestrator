// Seat rules: one resolver for the rule keys a seat is told to fetch, shared by the run planner, the
// Method B helpers, both workflow scripts (through a generated region) and the seat hint hook.
// Pure at import (the table is read on each loadSeatRules call); fail-open: a read or parse failure
// yields an empty table, never a throw.
//
// The table (hooks/lib/seat-rules.json) maps a seat role to three kinds of key:
// - protocol: the role's protocol rule, tool mechanics the plugin chooses; always first in a seat's
//   keys, and no declared list removes it or adds another role's.
// - pluginChosen: rules the plugin adds for the role whatever the schema declares.
// - defaults: the role's rules when its seat declares no `rules` list; a declared list replaces them.
//
// The block between the shared markers below is copied, without `export`, into the generated
// seat-rules region of workflows/implement-wave.js and workflows/review-wave.js by
// scripts/gen-seat-rules.mjs, because a workflow script cannot import. Keep it import-free.
import { readFileSync } from 'node:fs';

/** The prefix of the hint text that names a seat's rule keys. */
export const SEAT_RULES_PREFIX = 'SEAT RULES:';

function cleanKeys(list) {
  const out = [];
  if (!Array.isArray(list)) return out;
  for (const k of list) {
    if (typeof k === 'string' && k.trim() !== '' && !out.includes(k)) out.push(k);
  }
  return out;
}

/** The table {roles: {<role>: {protocol, pluginChosen, defaults}}}; {roles: {}} on a read or parse failure. */
export function loadSeatRules() {
  const roles = {};
  try {
    const raw = JSON.parse(readFileSync(new URL('./seat-rules.json', import.meta.url), 'utf8'));
    const src = raw && typeof raw === 'object' && raw.roles && typeof raw.roles === 'object' && !Array.isArray(raw.roles)
      ? raw.roles
      : {};
    for (const [role, row] of Object.entries(src)) {
      if (!row || typeof row !== 'object' || Array.isArray(row)) continue;
      roles[role] = {
        protocol: typeof row.protocol === 'string' && row.protocol ? row.protocol : null,
        pluginChosen: cleanKeys(row.pluginChosen),
        defaults: cleanKeys(row.defaults),
      };
    }
  } catch {
    // fail-open: an empty table
  }
  return { roles };
}

// @seat-rules-shared-begin
/**
 * seatRoleOf(stage) -> role | null. stage = {seat, phase, output, enters, writes, notes}.
 * orchestrator: null; review phase: reviewer; a known output, else a known seat name (an `-a<n>` fix-cycle
 * suffix read as its base): that role; writes false with no notes: read-only; work phase: implementer when it
 * enters, else work-seat; otherwise queue-seat.
 */
export function seatRoleOf(stage) {
  const s = stage && typeof stage === 'object' ? stage : {};
  const seat = typeof s.seat === 'string' ? s.seat : '';
  if (seat === 'orchestrator') return null;
  if (s.phase === 'review') return 'reviewer';
  const byOutput = {
    'planner-v1': 'planner',
    'implementer-v1': 'implementer',
    'test-author-v1': 'test-author',
    'declarations-v1': 'declarations-extractor',
  };
  if (typeof s.output === 'string' && Object.prototype.hasOwnProperty.call(byOutput, s.output)) return byOutput[s.output];
  const base = seat.replace(/-a[1-9][0-9]*$/, '');
  if (base === 'planner' || base === 'implementer' || base === 'test-author') return base;
  if (!s.writes && (!Array.isArray(s.notes) || s.notes.length === 0)) return 'read-only';
  if (s.phase === 'work') return s.enters ? 'implementer' : 'work-seat';
  return 'queue-seat';
}

/**
 * The role's own protocol key: its row's protocol, except that a stage that does not enter (enters === false)
 * on a row whose protocol is the implementer row's takes the work-seat row's protocol. null without a row.
 */
function seatRulesOwnProtocol(table, role, enters) {
  const roles = (table && table.roles) || {};
  const row = role ? roles[role] : null;
  if (!row) return null;
  const entryKey = roles.implementer ? roles.implementer.protocol : null;
  if (enters === false && entryKey && row.protocol === entryKey) {
    return roles['work-seat'] ? roles['work-seat'].protocol : null;
  }
  return row.protocol || null;
}

function seatRulesIsProtocol(k) {
  return typeof k === 'string' && k.indexOf('protocol.') === 0;
}

/**
 * seatRulesFor(table, {role, seat, enters, declared, noteRules, served}) -> {rules, degradations}
 * The note-derived rules first, then the seat's declared list (an array, `[]` is none) or else the role's
 * defaults, deduped with order kept. A protocol key other than the role's own is dropped and reported as
 * `seat rule <k> dropped: protocol rule of another role (<seat>)`. served (a Set) filters: an unserved
 * declared key is reported as `seat rule <k> not served (<seat>)`, an unserved default is dropped silently;
 * served null filters nothing.
 */
export function seatRulesFor(table, opts) {
  const o = opts && typeof opts === 'object' ? opts : {};
  const roles = (table && table.roles) || {};
  const row = o.role ? roles[o.role] : null;
  const own = seatRulesOwnProtocol(table, o.role, o.enters);
  const isDeclared = Array.isArray(o.declared);
  const served = o.served instanceof Set ? o.served : null;
  const seat = o.seat || o.role || 'seat';
  const wanted = [];
  for (const k of Array.isArray(o.noteRules) ? o.noteRules : []) wanted.push({ k, from: 'note' });
  for (const k of isDeclared ? o.declared : (row ? row.defaults : [])) wanted.push({ k, from: isDeclared ? 'declared' : 'default' });
  const rules = [];
  const degradations = [];
  const seen = [];
  for (const w of wanted) {
    const k = w.k;
    if (typeof k !== 'string' || k === '' || seen.includes(k)) continue;
    seen.push(k);
    if (seatRulesIsProtocol(k) && k !== own) {
      degradations.push(`seat rule ${k} dropped: protocol rule of another role (${seat})`);
      continue;
    }
    if (served && !served.has(k)) {
      if (w.from === 'declared') degradations.push(`seat rule ${k} not served (${seat})`);
      continue;
    }
    rules.push(k);
  }
  return { rules, degradations };
}

/**
 * promptKeysFor(table, stage) -> string[]: the stage's own protocol key, the role's plugin-chosen keys, then
 * stage.rules without any other role's protocol key, deduped with order kept.
 */
export function promptKeysFor(table, stage) {
  const s = stage && typeof stage === 'object' ? stage : {};
  const role = seatRoleOf(s);
  const roles = (table && table.roles) || {};
  const row = role ? roles[role] : null;
  const own = seatRulesOwnProtocol(table, role, s.enters);
  const keys = [];
  const add = (k) => {
    if (typeof k === 'string' && k !== '' && !keys.includes(k)) keys.push(k);
  };
  add(own);
  for (const k of row ? row.pluginChosen : []) add(k);
  for (const k of Array.isArray(s.rules) ? s.rules : []) {
    if (seatRulesIsProtocol(k) && k !== own) continue;
    add(k);
  }
  return keys;
}
// @seat-rules-shared-end
