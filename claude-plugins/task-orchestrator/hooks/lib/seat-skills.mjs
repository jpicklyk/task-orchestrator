// Seat skills: one resolver for the skills a seat invokes, shared by the run planner and the hooks.
// Pure at import (the defaults JSON is read lazily on first call and memoized); fail-open: a read or
// parse failure yields an empty table, never a throw.
//
// Resolution: a seat's declared `skills` list is used as is (`[]` means none) when the server supports it,
// otherwise the plugin's defaults for that seat (hooks/lib/seat-skill-defaults.json), otherwise none.
import { readFileSync } from 'node:fs';

export const SEAT_SKILLS_FEATURE = 'seatSkills';

const KNOWN_SEATS = ['planner', 'implementer', 'reviewer', 'test-author'];
const PHASE_SEAT = { queue: 'planner', work: 'implementer', review: 'reviewer' };

let cachedDefaults = null;

/** The defaults table {<seat>: string[]}; {} on a read or parse failure or a non-object top level. */
export function loadSeatSkillDefaults() {
  if (cachedDefaults) return cachedDefaults;
  const out = {};
  try {
    const raw = JSON.parse(readFileSync(new URL('./seat-skill-defaults.json', import.meta.url), 'utf8'));
    if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
      for (const [seat, list] of Object.entries(raw)) {
        if (Array.isArray(list)) out[seat] = list;
      }
    }
  } catch {
    // fail-open: an empty table
  }
  cachedDefaults = out;
  return out;
}

function cleanList(list) {
  const out = [];
  for (const s of list) {
    if (typeof s === 'string' && s.trim() !== '' && !out.includes(s)) out.push(s);
  }
  return out;
}

/**
 * seatSkillsFor(declared, seatName, {supported, defaults}) -> string[]
 * The declared list when supported and an array, else the defaults for the seat, else [].
 */
export function seatSkillsFor(declared, seatName, { supported = true, defaults } = {}) {
  if (supported && Array.isArray(declared)) return cleanList(declared);
  const table = defaults === undefined ? loadSeatSkillDefaults() : defaults;
  const list = table && Array.isArray(table[seatName]) ? table[seatName] : [];
  return cleanList(list);
}

/** The seat a caller plays: its seat, else the agent type's last segment, else the phase's owner, else null. */
export function seatNameFor({ callerSeat, agentType, phase } = {}) {
  if (typeof callerSeat === 'string' && callerSeat.trim() !== '') return callerSeat;
  if (typeof agentType === 'string') {
    const last = agentType.split(':').pop();
    if (KNOWN_SEATS.includes(last)) return last;
  }
  return PHASE_SEAT[phase] || null;
}

/** resolveSeatSkills({seats, supported, callerSeat, agentType, phase, defaults}) -> {seat, skills, source} */
export function resolveSeatSkills({ seats, supported, callerSeat, agentType, phase, defaults } = {}) {
  const seat = seatNameFor({ callerSeat, agentType, phase });
  if (!seat) return { seat: null, skills: [], source: 'none' };
  const named = Array.isArray(seats) ? seats.filter((s) => s && s.name === seat) : [];
  const entry = named.find((s) => s.phase === phase) || named[0];
  const declared = entry ? entry.skills : undefined;
  const opts = { supported: supported === undefined ? true : supported };
  if (defaults !== undefined) opts.defaults = defaults;
  const skills = seatSkillsFor(declared, seat, opts);
  const usedDeclared = opts.supported && Array.isArray(declared);
  const source = usedDeclared ? 'declared' : skills.length ? 'default' : 'none';
  return { seat, skills, source };
}
