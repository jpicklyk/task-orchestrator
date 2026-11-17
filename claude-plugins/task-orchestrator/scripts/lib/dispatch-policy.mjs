// dispatch-policy.mjs - loads dispatch-policy.json (the one source of model and effort values)
// and exposes the resolver: forClass, escalate, ceilingReached, classNames.
import { readFileSync, existsSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

const here = dirname(fileURLToPath(import.meta.url));
export const DEFAULT_POLICY_PATH = resolve(here, '../../dispatch-policy.json');

const cache = new Map();

function validate(policy, path) {
  const fail = (msg) => { throw new Error(`dispatch policy ${path}: ${msg}`); };
  if (!policy || typeof policy !== 'object') fail('not an object');
  const esc = policy.escalation;
  if (!esc || !Array.isArray(esc.levels) || esc.levels.length < 2) fail('escalation.levels must list at least two levels');
  const ci = esc.levels.indexOf(esc.ceiling);
  if (ci === -1) fail(`escalation.ceiling "${esc.ceiling}" is not one of the levels`);
  if (ci >= esc.levels.length - 1) fail('escalation.ceiling must be a level below the top level');
  const models = policy.models;
  if (!Array.isArray(models) || models.length === 0 || !models.every((m) => typeof m === 'string' && m)) {
    fail('models must list the model aliases');
  }
  const classes = policy.classes;
  if (!classes || typeof classes !== 'object' || Object.keys(classes).length === 0) fail('classes missing');
  for (const [name, c] of Object.entries(classes)) {
    if (!c || typeof c.model !== 'string' || !c.model) fail(`class ${name} has no model`);
    if (!models.includes(c.model)) fail(`class ${name} model "${c.model}" is not one of the models`);
    if (!esc.levels.includes(c.effort)) fail(`class ${name} effort "${c.effort}" is not one of the levels`);
  }
  return policy;
}

export function loadPolicy(path = DEFAULT_POLICY_PATH) {
  const key = resolve(path);
  if (!cache.has(key)) cache.set(key, validate(JSON.parse(readFileSync(key, 'utf8')), key));
  return cache.get(key);
}

export function classNames(path) {
  return Object.keys(loadPolicy(path).classes);
}

export function forClass(name, path) {
  const classes = loadPolicy(path).classes;
  if (!Object.prototype.hasOwnProperty.call(classes, name)) {
    throw new Error(`unknown dispatch class "${name}"; known classes: ${Object.keys(classes).join(', ')}`);
  }
  const { model, effort } = classes[name];
  return { model, effort };
}

function levelIndex(levels, effort) {
  const i = levels.indexOf(effort);
  if (i === -1) throw new Error(`unknown effort "${effort}"; known levels: ${levels.join(', ')}`);
  return i;
}

// The level `cycle` steps above `effort`, capped at the escalation ceiling. An effort already at or
// above the ceiling is returned unchanged (escalation never lowers it).
export function escalate(effort, cycle, path) {
  const { levels, ceiling } = loadPolicy(path).escalation;
  const i = levelIndex(levels, effort);
  const n = Number.isFinite(cycle) && cycle > 0 ? Math.floor(cycle) : 0;
  if (n === 0) return effort;
  const cap = levels.indexOf(ceiling);
  if (i >= cap) return effort;
  return levels[Math.min(i + n, cap)];
}

// True when cycle `cycle` of `effort` runs at or past the ceiling (a further failure goes to the decision path).
export function ceilingReached(effort, cycle, path) {
  const { levels, ceiling } = loadPolicy(path).escalation;
  return levelIndex(levels, escalate(effort, cycle, path)) >= levels.indexOf(ceiling);
}

// ---- tier classification data: the `tiers` key, validated lazily ----
// tierPolicy() is the only reader of `tiers`; loadPolicy() never looks at it, so a broken tiers
// block cannot break forClass, escalate or ceilingReached.

/** The tier names are fixed; a project override cannot add or remove one. */
export const TIER_NAMES = Object.freeze(['Direct', 'Delegated', 'Parallel']);

const THRESHOLD_KEYS = ['directMaxFiles', 'delegatedMaxFiles'];
const ROW_KEYS = ['criteria', 'pipeline'];
const OVERRIDE_KEYS = ['thresholds', 'rows', 'forceUp', 'forceDown'];
const ENTRY_KEYS = ['when', 'bound', 'tier', 'note'];
const BOUNDS = { forceUp: ['min', 'exact'], forceDown: ['exact', 'eligible', 'max'] };

const has = (o, k) => Object.prototype.hasOwnProperty.call(o, k);
const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const clone = (v) => JSON.parse(JSON.stringify(v));

function validateTiers(tiers, source) {
  const fail = (path, msg) => { throw new Error(`tier policy ${source}: ${path} ${msg}`); };
  const text = (v) => typeof v === 'string' && v.trim() !== '' && !/[\r\n]/.test(v);
  if (!isObj(tiers)) fail('tiers', 'is not an object');
  const t = tiers.thresholds;
  if (!isObj(t)) fail('thresholds', 'is missing');
  for (const k of Object.keys(t)) if (!THRESHOLD_KEYS.includes(k)) fail(`thresholds.${k}`, 'is an unknown key');
  for (const k of THRESHOLD_KEYS) {
    if (!has(t, k) || !Number.isInteger(t[k]) || t[k] < 1) fail(`thresholds.${k}`, 'must be an integer >= 1');
  }
  if (t.directMaxFiles >= t.delegatedMaxFiles) fail('thresholds', 'directMaxFiles must be below delegatedMaxFiles');
  if (!isObj(tiers.rows)) fail('rows', 'is missing');
  for (const k of Object.keys(tiers.rows)) if (!TIER_NAMES.includes(k)) fail(`rows.${k}`, 'is an unknown tier');
  for (const name of TIER_NAMES) {
    const row = has(tiers.rows, name) ? tiers.rows[name] : undefined;
    if (!isObj(row)) fail(`rows.${name}`, 'is missing');
    for (const k of Object.keys(row)) if (!ROW_KEYS.includes(k)) fail(`rows.${name}.${k}`, 'is an unknown key');
    for (const k of ROW_KEYS) {
      if (!text(row[k]) || row[k].includes('|')) fail(`rows.${name}.${k}`, 'must be a non-empty string with no "|" and no newline');
    }
  }
  for (const list of ['forceUp', 'forceDown']) {
    const entries = tiers[list];
    if (!Array.isArray(entries)) fail(list, 'must be an array');
    entries.forEach((e, i) => {
      const at = `${list}[${i}]`;
      if (!isObj(e)) fail(at, 'is not an object');
      for (const k of Object.keys(e)) if (!ENTRY_KEYS.includes(k)) fail(`${at}.${k}`, 'is an unknown key');
      if (!text(e.when)) fail(`${at}.when`, 'must be a non-empty single-line string');
      if (!BOUNDS[list].includes(e.bound)) fail(`${at}.bound`, `must be one of ${BOUNDS[list].join(', ')}`);
      if (!TIER_NAMES.includes(e.tier)) fail(`${at}.tier`, `must be one of ${TIER_NAMES.join(', ')}`);
      if (has(e, 'note') && !text(e.note)) fail(`${at}.note`, 'must be a non-empty single-line string');
    });
  }
  return tiers;
}

const tierCache = new Map();

/** The plugin default tiers from dispatch-policy.json, fully validated; cached per path. */
export function tierPolicy(path = DEFAULT_POLICY_PATH) {
  const key = resolve(path);
  if (!tierCache.has(key)) {
    const policy = JSON.parse(readFileSync(key, 'utf8'));
    if (!isObj(policy) || !has(policy, 'tiers')) throw new Error(`tier policy ${key}: tiers missing`);
    tierCache.set(key, validateTiers(policy.tiers, key));
  }
  return tierCache.get(key);
}

/** The override file parsed, or null when it does not exist. */
export function loadTierOverride(path) {
  const file = resolve(path);
  if (!existsSync(file)) return null;
  let value;
  try {
    value = JSON.parse(readFileSync(file, 'utf8'));
  } catch (e) {
    throw new Error(`tier policy ${file}: not valid JSON (${e.message})`);
  }
  if (!isObj(value)) throw new Error(`tier policy ${file}: not an object`);
  return value;
}

/**
 * A new tiers object: thresholds and rows merge per field; forceUp / forceDown replace the whole
 * list when present. The merged result is validated; errors name `source` and the field path.
 */
export function mergeTierOverride(tiers, override, source = 'override') {
  const fail = (path, msg) => { throw new Error(`tier policy ${source}: ${path} ${msg}`); };
  if (!isObj(override)) fail('override', 'is not an object');
  for (const k of Object.keys(override)) if (!OVERRIDE_KEYS.includes(k)) fail(k, 'is an unknown key');
  const out = clone(tiers);
  if (has(override, 'thresholds')) {
    if (!isObj(override.thresholds)) fail('thresholds', 'is not an object');
    for (const k of Object.keys(override.thresholds)) {
      if (!THRESHOLD_KEYS.includes(k)) fail(`thresholds.${k}`, 'is an unknown key');
      out.thresholds[k] = override.thresholds[k];
    }
  }
  if (has(override, 'rows')) {
    if (!isObj(override.rows)) fail('rows', 'is not an object');
    for (const name of Object.keys(override.rows)) {
      if (!TIER_NAMES.includes(name)) fail(`rows.${name}`, 'is an unknown tier');
      const row = override.rows[name];
      if (!isObj(row)) fail(`rows.${name}`, 'is not an object');
      for (const k of Object.keys(row)) {
        if (!ROW_KEYS.includes(k)) fail(`rows.${name}.${k}`, 'is an unknown key');
        out.rows[name][k] = row[k];
      }
    }
  }
  for (const list of ['forceUp', 'forceDown']) {
    if (has(override, list)) out[list] = Array.isArray(override[list]) ? clone(override[list]) : override[list];
  }
  return validateTiers(out, source);
}

function rangeText(lo, hi) {
  if (hi === undefined) return `${lo}+ files`;
  if (lo === hi) return lo === 1 ? '1 file' : `${lo} files`;
  return `${lo}-${hi} files`;
}

function effectText(e) {
  const base = { min: `min ${e.tier}`, exact: e.tier, eligible: `eligible for ${e.tier}`, max: `max ${e.tier}` }[e.bound];
  return e.note ? `${base} (${e.note})` : base;
}

const POINTER =
  'Thresholds and signals are data: `tiers` in the plugin\'s `dispatch-policy.json`. ' +
  'When `.taskorchestrator/tier-policy.json` exists beside the project config, the table in force is the output of ' +
  '`node <plugin root>/scripts/tier-policy.mjs`.';

/** The tier-classification block, with exactly one trailing newline. Input must be valid tiers. */
export function renderTierBlock(tiers) {
  const { directMaxFiles: d, delegatedMaxFiles: g } = tiers.thresholds;
  const ranges = { Direct: rangeText(1, d), Delegated: rangeText(d + 1, g), Parallel: rangeText(g + 1) };
  const lines = ['| Criteria | Tier | Pipeline |', '|----------|------|----------|'];
  for (const name of TIER_NAMES) {
    const row = tiers.rows[name];
    lines.push(`| ${ranges[name]}, ${row.criteria} | **${name}** | ${row.pipeline} |`);
  }
  const bullets = (list) => (list.length ? list.map((e) => `- ${e.when} -> ${effectText(e)}`) : ['- none']);
  lines.push('', '**Force-UP signals** (bump tier regardless of file count):', ...bullets(tiers.forceUp));
  lines.push('', '**Force-DOWN signals:**', ...bullets(tiers.forceDown));
  lines.push('', POINTER);
  return lines.join('\n') + '\n';
}
