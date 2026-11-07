// dispatch-policy.mjs - loads dispatch-policy.json (the one source of model and effort values)
// and exposes the resolver: forClass, escalate, ceilingReached, classNames.
import { readFileSync } from 'fs';
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
  if (!policy.models || typeof policy.models !== 'object') fail('models missing');
  const classes = policy.classes;
  if (!classes || typeof classes !== 'object' || Object.keys(classes).length === 0) fail('classes missing');
  for (const [name, c] of Object.entries(classes)) {
    if (!c || typeof c.model !== 'string' || !c.model) fail(`class ${name} has no model`);
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
