// Seat-skill state shared by skill-record.mjs, seat-skill-hint.mjs, seat-skill-gate.mjs and
// skill-enforcement.mjs. Pure at import; every function is fail-open and never throws.
//
// Per (session, agent) files under the marker root (lib/markers.mjs), never phase-guard-record's file:
//   seat-skills-<agent>.json                 the required record {itemId, seat, phase, gate, skills, source, enforce, preloaded, callerSeat}
//   skill-loaded-<sha256(name):16>-<agent>   one file per loaded skill, holding {skill}; existence = on record,
//                                            so parallel Skill calls never lose an update
//   seat-skill-armed-<agent>                 the writability probe, written before each denial: a marker root
//                                            that cannot be written could not record the fix, so it never refuses
// There is no release: while a required skill is missing every gated call is denied, and the text tells the
// agent to stop and report when the skill cannot load.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { sessionMarkerPath, readMarker, writeMarker } from './markers.mjs';

export const SEAT_SKILLS_PREFIX = 'SEAT SKILLS:';
export const SEAT_SKILLS_REQUIRED_PREFIX = 'SEAT SKILLS REQUIRED:';
export const NOTE_SKILL_REQUIRED_PREFIX = 'NOTE SKILL REQUIRED:';
export const UNKNOWN_SKILL_TEXT =
  'If the Skill tool answers Unknown skill, or you have no Skill tool, do not retry: stop and report the seat, the skill name and the binding source.';

const BINDING_SOURCE_LABELS = { declared: 'schema', default: 'plugin default' };
const SOURCE_LABELS = new Set(Object.values(BINDING_SOURCE_LABELS));

/** resolveSeatSkills' source as a label: 'declared' -> 'schema', 'default' -> 'plugin default', else null. */
export function bindingSourceLabel(source) {
  return typeof source === 'string' && Object.prototype.hasOwnProperty.call(BINDING_SOURCE_LABELS, source)
    ? BINDING_SOURCE_LABELS[source]
    : null;
}

/** The sentence naming a binding source label, e.g. `Binding source: schema.` */
export function bindingSourceText(label) {
  return `Binding source: ${label}.`;
}

/** The phase a seat is gated in when its schema entry names none. */
export const DEFAULT_SEAT_PHASE = { planner: 'queue', implementer: 'work', 'test-author': 'work', reviewer: 'review' };

const GATE_BY_PHASE = { work: 'edit', queue: 'note', review: 'note' };

/** 'edit' for work, 'note' for queue or review, else null. */
export function gateForPhase(phase) {
  return typeof phase === 'string' && Object.prototype.hasOwnProperty.call(GATE_BY_PHASE, phase) ? GATE_BY_PHASE[phase] : null;
}

/** First 16 hex characters of sha256(text). */
export function hash16(text) {
  return createHash('sha256').update(String(text)).digest('hex').slice(0, 16);
}

function strList(v) {
  return Array.isArray(v) ? v.filter((s) => typeof s === 'string' && s) : [];
}

export function requiredRecordPath(sessionId, agentId) {
  return sessionMarkerPath(sessionId, 'seat-skills', agentId);
}

/** The agent's required record, or null when absent or malformed. */
export function readRequired(sessionId, agentId) {
  try {
    if (!agentId) return null;
    const r = readMarker(requiredRecordPath(sessionId, agentId), null);
    if (!r || typeof r.seat !== 'string' || !r.seat) return null;
    if (typeof r.source !== 'string' || !SOURCE_LABELS.has(r.source)) return null;
    return {
      itemId: typeof r.itemId === 'string' ? r.itemId : null,
      seat: r.seat,
      phase: typeof r.phase === 'string' ? r.phase : null,
      gate: r.gate === 'edit' || r.gate === 'note' ? r.gate : null,
      skills: strList(r.skills),
      source: r.source,
      enforce: r.enforce === true,
      preloaded: strList(r.preloaded),
      callerSeat: r.callerSeat === true,
    };
  } catch {
    return null;
  }
}

export function writeRequired(sessionId, agentId, record) {
  try {
    if (!agentId) return false;
    return writeMarker(requiredRecordPath(sessionId, agentId), record);
  } catch {
    return false;
  }
}

export function skillLoadedPath(sessionId, agentId, skill) {
  return sessionMarkerPath(sessionId, `skill-loaded-${hash16(skill)}`, agentId);
}

/** Records one loaded skill for the agent. False on any failure. */
export function recordSkillLoaded(sessionId, agentId, skill) {
  try {
    if (!agentId || typeof skill !== 'string' || !skill) return false;
    return writeMarker(skillLoadedPath(sessionId, agentId, skill), { skill });
  } catch {
    return false;
  }
}

export function isSkillLoaded(sessionId, agentId, skill) {
  try {
    if (!agentId || typeof skill !== 'string' || !skill) return false;
    return existsSync(skillLoadedPath(sessionId, agentId, skill));
  } catch {
    return false;
  }
}

/** The skills of `skills` neither preloaded nor on record for the agent, order kept. */
export function missingSkills(sessionId, agentId, skills, preloaded = []) {
  const pre = new Set(strList(preloaded));
  return strList(skills).filter((s) => !pre.has(s) && !isSkillLoaded(sessionId, agentId, s));
}

export function armedProbePath(sessionId, agentId) {
  return sessionMarkerPath(sessionId, 'seat-skill-armed', agentId);
}

/**
 * Writes the agent's writability probe. True when written: a denial may follow. False on any failure: the
 * marker root cannot hold a Skill record, so the caller allows the call (skill-record could not record the fix).
 */
export function armDenial(sessionId, agentId) {
  try {
    if (!agentId) return false;
    return writeMarker(armedProbePath(sessionId, agentId), { at: Date.now() }) === true;
  } catch {
    return false;
  }
}

export function quoteList(names) {
  return names.map((n) => `"${n}"`).join(', ');
}

// ---- preloaded skills (agent frontmatter `skills:`) --------------------------------------

const PLUGIN_ROOT = () => fileURLToPath(new URL('../../', import.meta.url));
const SAFE_NAME_RE = /^[A-Za-z0-9][A-Za-z0-9._-]*$/;

function thisPluginName() {
  try {
    const raw = JSON.parse(readFileSync(join(PLUGIN_ROOT(), '.claude-plugin', 'plugin.json'), 'utf8'));
    return raw && typeof raw.name === 'string' ? raw.name : null;
  } catch {
    return null;
  }
}

function unquote(s) {
  const t = s.trim();
  if (t.length >= 2 && ((t[0] === '"' && t.endsWith('"')) || (t[0] === "'" && t.endsWith("'")))) return t.slice(1, -1).trim();
  return t;
}

/** The `skills:` list of a markdown file's frontmatter (inline `a, b`, flow `[a, b]`, or a block list); [] otherwise. */
export function frontmatterSkills(text) {
  try {
    if (typeof text !== 'string') return [];
    let t = text;
    if (t.charCodeAt(0) === 0xfeff) t = t.slice(1);
    const lines = t.split(/\r?\n/);
    if (lines[0].trim() !== '---') return [];
    let end = -1;
    for (let i = 1; i < lines.length; i++) {
      if (lines[i].trim() === '---') {
        end = i;
        break;
      }
    }
    if (end < 0) return [];
    const fm = lines.slice(1, end);
    const idx = fm.findIndex((l) => /^skills\s*:/.test(l));
    if (idx < 0) return [];
    let value = fm[idx].replace(/^skills\s*:/, '').replace(/\s+#.*$/, '').trim();
    let items = [];
    if (value.startsWith('[')) {
      value = value.replace(/^\[/, '').replace(/\]\s*$/, '');
      items = value.split(',');
    } else if (value !== '') {
      items = value.split(',');
    } else {
      for (let i = idx + 1; i < fm.length; i++) {
        const line = fm[i];
        if (line.trim() === '') continue;
        const m = /^\s+-\s*(.*)$/.exec(line) || /^-\s+(.*)$/.exec(line);
        if (!m) break;
        items.push(m[1]);
      }
    }
    const out = [];
    for (const it of items) {
      const name = unquote(it);
      if (name && !out.includes(name)) out.push(name);
    }
    return out;
  } catch {
    return [];
  }
}

function readSkillsAt(path) {
  try {
    if (!existsSync(path)) return null;
    return frontmatterSkills(readFileSync(path, 'utf8'));
  } catch {
    return null;
  }
}

/**
 * The skills an agent type preloads through its agent file's frontmatter `skills:` list. A bare name reads
 * `<CLAUDE_PROJECT_DIR or cwd>/.claude/agents/<name>.md`, then `<CLAUDE_CONFIG_DIR or ~/.claude>/agents/<name>.md`;
 * `<plugin>:<name>` reads `<plugin root>/agents/<name>.md` only when <plugin> is this plugin. [] otherwise.
 */
export function preloadedSkills(agentType, { env = process.env, cwd = process.cwd() } = {}) {
  try {
    if (typeof agentType !== 'string' || !agentType) return [];
    const parts = agentType.split(':');
    if (parts.length > 2) return [];
    if (parts.length === 2) {
      const [plugin, name] = parts;
      if (!SAFE_NAME_RE.test(name) || plugin !== thisPluginName()) return [];
      return readSkillsAt(join(PLUGIN_ROOT(), 'agents', `${name}.md`)) || [];
    }
    const name = parts[0];
    if (!SAFE_NAME_RE.test(name)) return [];
    const projectDir = env.CLAUDE_PROJECT_DIR || cwd;
    const project = readSkillsAt(join(projectDir, '.claude', 'agents', `${name}.md`));
    if (project) return project;
    const configDir = env.CLAUDE_CONFIG_DIR || join(os.homedir(), '.claude');
    return readSkillsAt(join(configDir, 'agents', `${name}.md`)) || [];
  } catch {
    return [];
  }
}
