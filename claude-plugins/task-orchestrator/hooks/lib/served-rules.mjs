// The root's served rule keys, shared by phase-guard.mjs and skill-enforcement.mjs. Pure at import;
// fail-open: every failure is a null, never a throw.
//
// A note's skill pointer that is exactly (case-sensitive) a served rule key is a rule to fetch with
// `config` op `rules`; any other pointer stays a skill to invoke. A rule wins over a same-named skill.
// One `config` op `rules` list read per session, cached in the session marker `served-rules` as
// `{rootId, keys}`; only an ok list (empty included) is cached, a failure writes nothing.
import { callOp } from './api-client.mjs';
import { sessionMarkerPath, readMarker, writeMarker } from './markers.mjs';

export const RULES_TIMEOUT_MS = 1000;

/** Served rule keys for rootId as a Set, or null when rootId is absent or the read did not answer ok. */
export async function loadServedRuleKeys(sessionId, rootId, { timeoutMs = RULES_TIMEOUT_MS } = {}) {
  if (typeof rootId !== 'string' || !rootId) return null;
  try {
    const path = sessionMarkerPath(sessionId, 'served-rules');
    const cached = readMarker(path, null);
    if (cached && cached.rootId === rootId && Array.isArray(cached.keys)) {
      return new Set(cached.keys.filter((k) => typeof k === 'string'));
    }
    const res = await callOp('config', { ops: [{ op: 'rules', rootId }] }, { timeoutMs });
    const r = res && Array.isArray(res.results) ? res.results[0] : null;
    if (!r || r.ok !== true || !Array.isArray(r.rules)) return null;
    const keys = r.rules.map((e) => e?.key).filter((k) => typeof k === 'string' && k);
    writeMarker(path, { rootId, keys });
    return new Set(keys);
  } catch {
    return null;
  }
}

/** Splits pointer names into `{rules, skills}` by membership in keys (a Set or null), order kept. */
export function splitPointers(names, keys) {
  const rules = [];
  const skills = [];
  for (const n of names) {
    if (keys && keys.has(n)) rules.push(n);
    else skills.push(n);
  }
  return { rules, skills };
}
