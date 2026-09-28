// run-planner-lib.mjs — pure planner core for the run-wave front door.
//
// No wall-clock reads, no non-deterministic randomness, no filesystem or process access, and
// no CommonJS-style module loading of Node builtins.
// Every function is a pure function of its arguments — the CLI (run-planner.mjs) supplies
// the clock (`--now`), reads files, and writes stdout/stderr. This module never branches on
// an item-type string; it only reads declared seats, dispatch tables and note schemas from
// the snapshot it is given.
//
// Contract: run-wave/snapshot-v1 in, implement-wave/args-v1 + run-wave/plan-doc-v1 out.

export const SNAPSHOT_CONTRACT = 'run-wave/snapshot-v1';
export const PLAN_DOC_CONTRACT = 'run-wave/plan-doc-v1';
export const ARGS_CONTRACT = 'implement-wave/args-v1';
export const PLAN_DOC_MAX_BYTES = 61440;

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ROLE_ENUM = new Set(['queue', 'work', 'review', 'blocked', 'terminal']);

/**
 * validateSnapshot(snap) -> {ok:true} | {ok:false, errors:string[]}
 * Structural check: contract id, UUID-shaped ids, role enum membership (case-sensitive),
 * every candidate has a `schemas` entry. Duplicate ids across sources are never an error.
 */
export function validateSnapshot(snap) {
  const errors = [];
  if (!snap || typeof snap !== 'object' || Array.isArray(snap)) {
    return { ok: false, errors: ['snapshot must be an object'] };
  }
  if (snap.contract !== SNAPSHOT_CONTRACT) {
    errors.push(`contract must be ${SNAPSHOT_CONTRACT}`);
  }
  const candidates = Array.isArray(snap.candidates) ? snap.candidates : [];
  const schemas = snap.schemas && typeof snap.schemas === 'object' ? snap.schemas : {};
  for (const c of candidates) {
    if (!c || !UUID_RE.test(String(c.id || ''))) {
      errors.push(`candidate id not a UUID: ${c && c.id}`);
    }
    if (!c || !ROLE_ENUM.has(c.role)) {
      errors.push(`candidate ${c && c.id}: invalid role ${c && c.role}`);
    }
    if (c && !(c.id in schemas)) {
      errors.push(`candidate ${c.id}: missing schemas entry`);
    }
  }
  const blocked = Array.isArray(snap.blocked) ? snap.blocked : [];
  for (const b of blocked) {
    if (!b || !UUID_RE.test(String(b.itemId || ''))) {
      errors.push(`blocked itemId not a UUID: ${b && b.itemId}`);
    }
    if (!b || !ROLE_ENUM.has(b.role)) {
      errors.push(`blocked ${b && b.itemId}: invalid role ${b && b.role}`);
    }
    for (const bb of (b && b.blockedBy) || []) {
      if (!bb || !UUID_RE.test(String(bb.itemId || ''))) {
        errors.push(`blocker itemId not a UUID: ${bb && bb.itemId}`);
      }
      if (bb && bb.role != null && !ROLE_ENUM.has(bb.role)) {
        errors.push(`blocker ${bb.itemId}: invalid role ${bb.role}`);
      }
      if (bb && bb.effectiveUnblockRole != null && !ROLE_ENUM.has(bb.effectiveUnblockRole)) {
        errors.push(`blocker ${bb.itemId}: invalid effectiveUnblockRole ${bb.effectiveUnblockRole}`);
      }
    }
  }
  for (const [id, schema] of Object.entries(schemas)) {
    for (const note of (schema && schema.notes) || []) {
      if (note.seat !== undefined && note.seat !== null && typeof note.seat !== 'string') {
        errors.push(`item ${id}: note ${note.key} has a non-string seat`);
      }
    }
  }
  return errors.length ? { ok: false, errors } : { ok: true };
}

/**
 * compareVersions(a, b) -> -1 | 0 | 1 | null
 * Compares two 'x.y.z' strings. Returns null when either is not that shape.
 */
export function compareVersions(a, b) {
  const re = /^\d+\.\d+\.\d+$/;
  if (typeof a !== 'string' || typeof b !== 'string' || !re.test(a) || !re.test(b)) return null;
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    if (pa[i] > pb[i]) return 1;
    if (pa[i] < pb[i]) return -1;
  }
  return 0;
}

/**
 * probeFrom({pluginRoot, pluginJsonText, subagentStartText, phaseGuardText, configYamlText, settings})
 * -> {pluginRoot, pluginVersion|null, phase0Hooks, hookMarkers:{subagentSkip, workflowParentFilter},
 *     actorAttributionRequired, workflowSizeGuideline|null, allow:{workflow, mcp, bashGit, bashNode}}
 */
export function probeFrom({ pluginRoot, pluginJsonText, subagentStartText, phaseGuardText, configYamlText, settings } = {}) {
  let pluginVersion = null;
  if (typeof pluginJsonText === 'string' && pluginJsonText.trim() !== '') {
    try {
      const pkg = JSON.parse(pluginJsonText);
      pluginVersion = typeof pkg.version === 'string' ? pkg.version : null;
    } catch {
      pluginVersion = null;
    }
  }

  const subagentSkip = typeof subagentStartText === 'string' && subagentStartText.includes('workflow-subagent');
  const workflowParentFilter = typeof phaseGuardText === 'string' && phaseGuardText.includes('workflow:');
  const versionCmp = pluginVersion != null ? compareVersions(pluginVersion, '3.8.0') : null;
  const phase0Hooks = !!(versionCmp !== null && versionCmp >= 0 && subagentSkip && workflowParentFilter);

  const actorAttributionRequired = typeof configYamlText === 'string'
    && /actor_attribution\s*:\s*\n(?:[ \t]+.*\n)*?[ \t]+required\s*:\s*true/.test(configYamlText);

  const settingsList = Array.isArray(settings) ? settings : [];
  let workflowSizeGuideline = null;
  const allow = { workflow: false, mcp: false, bashGit: false, bashNode: false };
  for (const text of settingsList) {
    if (typeof text !== 'string' || text.trim() === '') continue;
    let parsed;
    try {
      parsed = JSON.parse(text);
    } catch {
      continue;
    }
    if (workflowSizeGuideline == null && typeof parsed.workflowSizeGuideline === 'string') {
      workflowSizeGuideline = parsed.workflowSizeGuideline;
    }
    const list = parsed && parsed.permissions && parsed.permissions.allow;
    if (Array.isArray(list)) {
      for (const entry of list) {
        if (entry === 'Workflow' || entry === 'Workflow(task-orchestrator:implement-wave)') allow.workflow = true;
        if (entry === 'mcp__mcp-task-orchestrator__*') allow.mcp = true;
        if (entry === 'Bash(git:*)') allow.bashGit = true;
        if (entry === 'Bash(node:*)') allow.bashNode = true;
      }
    }
  }

  return {
    pluginRoot: pluginRoot ?? null,
    pluginVersion,
    phase0Hooks,
    hookMarkers: { subagentSkip, workflowParentFilter },
    actorAttributionRequired,
    workflowSizeGuideline,
    allow
  };
}

/**
 * chooseMode(snap, requested='auto') -> 'shared' | 'per-item'
 * auto = shared iff every non-container candidate shares one non-null parentId.
 */
export function chooseMode(snap, requested = 'auto') {
  if (requested === 'shared' || requested === 'per-item') return requested;
  const items = ((snap && snap.candidates) || []).filter((c) => !c.hasChildren);
  if (items.length === 0) return 'per-item';
  const parentIds = new Set(items.map((c) => c.parentId ?? null));
  if (parentIds.size === 1) {
    const [only] = parentIds;
    if (only != null) return 'shared';
  }
  return 'per-item';
}

/**
 * chooseEntry(probe, requested='auto') -> 'seat' | 'pre-entered'
 * auto = seat iff probe.phase0Hooks.
 */
export function chooseEntry(probe, requested = 'auto') {
  if (requested === 'seat' || requested === 'pre-entered') return requested;
  return probe && probe.phase0Hooks ? 'seat' : 'pre-entered';
}

/**
 * ownership(schema, phases) -> {unowned:string[], orchestratorNotes:string[]}
 * In schema note order (notes scoped to `phases`); `unowned` lists only REQUIRED notes whose
 * seat is null, names an undeclared seat, or names a seat declared for a different phase.
 */
export function ownership(schema, phases) {
  const declaredSeatsByPhase = {};
  for (const s of (schema && schema.seats) || []) {
    if (!declaredSeatsByPhase[s.phase]) declaredSeatsByPhase[s.phase] = new Set();
    declaredSeatsByPhase[s.phase].add(s.name);
  }
  const unowned = [];
  const orchestratorNotes = [];
  for (const note of (schema && schema.notes) || []) {
    if (!phases.includes(note.role)) continue;
    const declaredForPhase = declaredSeatsByPhase[note.role] || new Set();
    const owned = note.seat != null && declaredForPhase.has(note.seat);
    if (owned && note.seat === 'orchestrator') {
      orchestratorNotes.push(note.key);
      continue;
    }
    if (!owned && note.required) {
      unowned.push(note.key);
    }
  }
  return { unowned, orchestratorNotes };
}

/**
 * resolveDispatch(schema, seat, phase, {enters, implicitOwner}, defaults) -> {agent|null, model, effort?}
 * Field-wise precedence: dispatchBySeat[phase][seat] -> dispatch[phase] (entry/implicit only)
 * -> defaults {queue, work, extractor:{model, effort}}. An explicit null agent is kept.
 */
export function resolveDispatch(schema, seat, phase, entryInfo = {}, defaults = {}) {
  const { enters, implicitOwner } = entryInfo;
  const bySeat = (schema && schema.dispatchBySeat && schema.dispatchBySeat[phase] && schema.dispatchBySeat[phase][seat]) || {};
  const eligibleForPhase = !!(enters || implicitOwner);
  const byPhase = eligibleForPhase ? ((schema && schema.dispatch && schema.dispatch[phase]) || {}) : {};

  let def = {};
  if (seat === 'declarations-extractor' && defaults.extractor) {
    def = defaults.extractor;
  } else if (typeof defaults[phase] === 'string') {
    def = { model: defaults[phase] };
  } else if (defaults[phase] && typeof defaults[phase] === 'object') {
    def = defaults[phase];
  }

  const result = {};
  result.agent = 'agent' in bySeat ? bySeat.agent : ('agent' in byPhase ? byPhase.agent : (def.agent ?? null));
  result.model = 'model' in bySeat ? bySeat.model : ('model' in byPhase ? byPhase.model : def.model);
  const effort = 'effort' in bySeat ? bySeat.effort : ('effort' in byPhase ? byPhase.effort : def.effort);
  if (effort !== undefined) result.effort = effort;
  return result;
}

function orderByAfter(seats) {
  const byName = new Map(seats.map((s) => [s.name, s]));
  const visited = new Set();
  const result = [];
  function visit(s) {
    if (visited.has(s.name)) return;
    visited.add(s.name);
    for (const dep of s.after || []) {
      const d = byName.get(dep);
      if (d) visit(d);
    }
    result.push(s);
  }
  for (const s of seats) visit(s);
  return result;
}

function deriveRulesSkills(ownedKeys, allNotes, servedKeys) {
  const rules = [];
  const skills = [];
  for (const k of ownedKeys) {
    const note = allNotes.find((n) => n.key === k);
    const skill = note && note.skill;
    if (!skill) continue;
    if (servedKeys.has(skill)) rules.push(skill);
    else skills.push(skill);
  }
  return { rules, skills };
}

/**
 * deriveStages(candidate, schemaEntry, ctx) -> {stages, orchestratorNotes, unownedRequired,
 *   schemaFree, hasReviewPhase, warnings, degradations, exclude:null|string}
 * ctx = {rulesServed:[{key,rulesVersion}], noteActors:[{key,actorId}], profile}
 */
export function deriveStages(candidate, schemaEntry, ctx = {}) {
  const rulesServed = ctx.rulesServed || [];
  const noteActors = ctx.noteActors || [];
  const profile = ctx.profile || {};
  const servedKeys = new Set(rulesServed.map((r) => r.key));
  const actorsByKey = new Map(noteActors.map((n) => [n.key, n.actorId]));
  const warnings = [];
  const degradations = [];

  if (!schemaEntry || schemaEntry.status === 'not-found') {
    const stage = {
      seat: 'implementer',
      phase: 'work',
      enters: true,
      implicitOwner: true,
      notes: [],
      writes: true,
      dispatch: resolveDispatch({}, 'implementer', 'work', { enters: true, implicitOwner: true }, profile.defaultModels || {}),
      output: 'implementer-v1'
    };
    warnings.push('/manage-schemas suggestion');
    return {
      stages: [stage],
      orchestratorNotes: [],
      unownedRequired: [],
      schemaFree: true,
      hasReviewPhase: false,
      warnings,
      degradations,
      exclude: null
    };
  }

  const schema = schemaEntry;
  const notes = schema.notes || [];
  const hasReviewPhase = notes.some((n) => n.role === 'review');
  const isResumed = candidate.role === 'work';
  const phases = isResumed ? ['work'] : ['queue', 'work'];
  const declaredSeats = schema.seats || [];
  const seatAware = declaredSeats.length > 0;

  // DEC-11 (S6) applies to seat-aware schemas only: without declared seats the server says
  // nothing about ownership, so a seat-less schema's null-seat notes are legitimately owned
  // by the implicit phase owner, not "unowned".
  let orchestratorNotes;
  if (seatAware) {
    const own = ownership(schema, ['queue', 'work', 'review']);
    if (own.unowned.length) {
      return {
        stages: [],
        orchestratorNotes: own.orchestratorNotes,
        unownedRequired: own.unowned,
        schemaFree: false,
        hasReviewPhase,
        warnings,
        degradations,
        exclude: `unowned required note(s) ${own.unowned.join(', ')}`
      };
    }
    orchestratorNotes = own.orchestratorNotes;
  } else {
    // Seat-less deviation (S5): without any declared seat, the server says nothing about
    // ownership, so the implicit phase owner (planner/implementer) takes EVERY note in its
    // phase regardless of the note's own `seat` annotation. No note is orchestrator-owned here.
    orchestratorNotes = [];
  }

  const defaults = profile.defaultModels || { queue: 'opus', work: 'sonnet', extractor: { model: 'sonnet', effort: 'low' } };
  const stages = [];

  if (seatAware) {
    const queueSeats = orderByAfter(declaredSeats.filter((s) => s.phase === 'queue'));
    const workSeats = orderByAfter(declaredSeats.filter((s) => s.phase === 'work'));
    const orderedSeats = phases.includes('queue') ? [...queueSeats, ...workSeats] : workSeats;

    let firstQueueAssigned = false;
    for (const s of orderedSeats) {
      if (s.name === 'orchestrator') continue; // never a stage - its notes are in orchestratorNotes
      if (!phases.includes(s.phase)) continue;
      if (isResumed && s.phase === 'queue') continue;

      const seatNotes = notes.filter((n) => n.role === s.phase && n.seat === s.name).map((n) => n.key);

      if (isResumed && seatNotes.length
        && seatNotes.every((k) => {
          const actorId = actorsByKey.get(k);
          return actorId && actorId.startsWith(`${s.name}:`);
        })) {
        continue;
      }

      const isFirstQueue = s.phase === 'queue' && !(s.after && s.after.length) && !firstQueueAssigned;
      if (isFirstQueue) firstQueueAssigned = true;
      const enters = !!s.enters;
      const writes = s.phase === 'work';
      const ownsTestAuthorNote = seatNotes.some((k) => {
        const note = notes.find((n) => n.key === k);
        return note && note.skill === 'test-author';
      });

      let output;
      if (isFirstQueue) output = 'planner-v1';
      else if (s.phase === 'work' && enters) output = 'implementer-v1';
      else if (ownsTestAuthorNote) output = 'test-author-v1';
      else output = 'generic-v1';

      if (s.phase === 'work' && (s.readsExclude || []).length && ownsTestAuthorNote) {
        const served = servedKeys.has('declarations-extractor');
        if (!served) degradations.push('extractor without accuracy rule');
        stages.push({
          seat: 'declarations-extractor',
          phase: 'work',
          inserted: true,
          writes: false,
          notes: [],
          rules: served ? ['declarations-extractor'] : [],
          skills: [],
          dispatch: resolveDispatch(schema, 'declarations-extractor', 'work', {}, defaults),
          output: 'declarations-v1'
        });
      }

      const rulesSkills = deriveRulesSkills(seatNotes, notes, servedKeys);
      const dispatch = resolveDispatch(schema, s.name, s.phase, { enters, implicitOwner: false }, defaults);
      const stage = {
        seat: s.name,
        phase: s.phase,
        notes: seatNotes,
        rules: rulesSkills.rules,
        skills: rulesSkills.skills,
        writes,
        dispatch,
        output
      };
      if (enters) stage.enters = true;
      if (s.readsExclude) stage.readsExclude = s.readsExclude;
      if (s.extraLockKeys) stage.extraLockKeys = s.extraLockKeys;
      stages.push(stage);
    }
  } else {
    if (phases.includes('queue') && !isResumed) {
      const queueNotes = notes.filter((n) => n.role === 'queue').map((n) => n.key);
      const rulesSkills = deriveRulesSkills(queueNotes, notes, servedKeys);
      stages.push({
        seat: 'planner',
        phase: 'queue',
        notes: queueNotes,
        rules: rulesSkills.rules,
        skills: rulesSkills.skills,
        writes: false,
        dispatch: resolveDispatch(schema, 'planner', 'queue', { enters: false, implicitOwner: true }, defaults),
        output: 'planner-v1'
      });
    }
    const workNotes = notes.filter((n) => n.role === 'work').map((n) => n.key);
    const rulesSkillsW = deriveRulesSkills(workNotes, notes, servedKeys);
    stages.push({
      seat: 'implementer',
      phase: 'work',
      enters: true,
      implicitOwner: true,
      notes: workNotes,
      rules: rulesSkillsW.rules,
      skills: rulesSkillsW.skills,
      writes: true,
      dispatch: resolveDispatch(schema, 'implementer', 'work', { enters: true, implicitOwner: true }, defaults),
      output: 'implementer-v1'
    });
  }

  if (stages.length === 0) warnings.push('nothing to run');

  return {
    stages,
    orchestratorNotes,
    unownedRequired: [],
    schemaFree: false,
    hasReviewPhase,
    warnings,
    degradations,
    exclude: null
  };
}

/**
 * lastWritingWorkSeat(stages) -> string | null
 * The seat name of the last stage with phase 'work' and writes true, or null.
 */
export function lastWritingWorkSeat(stages) {
  let last = null;
  for (const s of stages || []) {
    if (s.phase === 'work' && s.writes) last = s.seat;
  }
  return last;
}

/**
 * classifyEdge(edge, {runIds, mode, milestone}) ->
 *   {class:'none'} | {class:'in-run', item, milestone} | {class:'cross-run', reason}
 */
export function classifyEdge(edge, { runIds, mode, milestone } = {}) {
  const { itemId, effectiveUnblockRole, satisfied } = edge || {};
  if (satisfied) return { class: 'none' };
  if (mode === 'per-item') {
    return { class: 'cross-run', reason: `blocked by ${itemId} until ${effectiveUnblockRole} (cross-run)` };
  }
  if (!runIds || !runIds.has(itemId)) {
    return { class: 'cross-run', reason: `blocked by ${itemId} until ${effectiveUnblockRole} (cross-run)` };
  }
  if (effectiveUnblockRole === 'review' || effectiveUnblockRole === 'terminal') {
    return { class: 'cross-run', reason: `blocked by ${itemId} until ${effectiveUnblockRole} (cross-run)` };
  }
  return { class: 'in-run', item: itemId, milestone: milestone ?? null };
}

function priorityRank(p) {
  return p === 'high' ? 0 : p === 'medium' ? 1 : p === 'low' ? 2 : 3;
}

function comparePriorityComplexityId(a, b) {
  const pr = priorityRank(a && a.priority) - priorityRank(b && b.priority);
  if (pr !== 0) return pr;
  const ca = (a && a.complexity != null) ? a.complexity : Infinity;
  const cb = (b && b.complexity != null) ? b.complexity : Infinity;
  if (ca !== cb) return ca - cb;
  return String((a && a.id) || '').localeCompare(String((b && b.id) || ''));
}

/**
 * frontier(snap, derivedById, {mode, entryMode}) ->
 *   {admitted:string[], waitsFor:{[id]:[{item,milestone}]}, inRunEdges:[{from,to,milestone}],
 *    deferred:[{id,reason}], excluded:[{id,reason}]}
 */
export function frontier(snap, derivedById, { mode, entryMode } = {}) {
  const candById = new Map();
  for (const c of (snap.candidates || [])) {
    if (!candById.has(c.id) || c.source === 'work') candById.set(c.id, c);
  }
  const shortOf = (id) => (candById.get(id) && candById.get(id).short) || String(id).slice(0, 8);

  const excluded = [];
  const R0 = new Map();
  for (const c of candById.values()) {
    if (c.hasChildren) {
      excluded.push({ id: c.id, reason: 'container' });
      continue;
    }
    const schema = snap.schemas && snap.schemas[c.id];
    if (schema && schema.status === 'unavailable') {
      excluded.push({ id: c.id, reason: 'config-unavailable' });
      continue;
    }
    const derived = derivedById[c.id];
    if (derived && derived.exclude) {
      excluded.push({ id: c.id, reason: derived.exclude });
      continue;
    }
    R0.set(c.id, c);
  }

  const deferred = [];
  const explicitIds = new Set();
  for (const b of snap.blocked || []) {
    if (b.blockType === 'explicit') {
      explicitIds.add(b.itemId);
    }
  }
  for (const id of explicitIds) {
    if (R0.has(id)) {
      R0.delete(id);
      excluded.push({ id, reason: 'explicitly blocked' });
    }
  }

  const runIds = new Set(R0.keys());
  const inRunBlockersOf = new Map();
  const crossRunReasonOf = new Map();

  for (const b of snap.blocked || []) {
    if (b.blockType !== 'dependency') continue;
    if (!R0.has(b.itemId)) {
      if (!candById.has(b.itemId) && !explicitIds.has(b.itemId)) {
        deferred.push({ id: b.itemId, reason: 'not projected' });
      }
      continue;
    }
    const unsatisfied = (b.blockedBy || []).filter((bb) => !bb.satisfied);
    const inRun = [];
    let crossReason = null;
    for (const blocker of unsatisfied) {
      const cls = classifyEdge(
        { itemId: blocker.itemId, role: blocker.role, effectiveUnblockRole: blocker.effectiveUnblockRole, satisfied: blocker.satisfied },
        { runIds, mode, milestone: null }
      );
      if (entryMode === 'pre-entered' && cls.class === 'in-run') {
        crossReason = crossReason || `blocked by ${shortOf(blocker.itemId)} until ${blocker.effectiveUnblockRole} (cross-run)`;
        continue;
      }
      if (cls.class === 'cross-run') {
        crossReason = crossReason || `blocked by ${shortOf(blocker.itemId)} until ${blocker.effectiveUnblockRole} (cross-run)`;
      } else if (cls.class === 'in-run') {
        const milestone = lastWritingWorkSeat((derivedById[blocker.itemId] || {}).stages || []);
        if (!milestone) {
          crossReason = crossReason || `blocked by ${shortOf(blocker.itemId)} until ${blocker.effectiveUnblockRole} (cross-run)`;
        } else {
          inRun.push({ blockerId: blocker.itemId, milestone });
        }
      }
    }
    if (crossReason) crossRunReasonOf.set(b.itemId, crossReason);
    else inRunBlockersOf.set(b.itemId, inRun);
  }

  const pending = new Set(R0.keys());
  for (const [id, reason] of crossRunReasonOf) {
    if (pending.has(id)) {
      pending.delete(id);
      deferred.push({ id, reason });
    }
  }

  const admitted = new Set();
  const waitsForMap = new Map();
  let changed = true;
  while (changed) {
    changed = false;
    for (const id of [...pending]) {
      const blockers = inRunBlockersOf.get(id) || [];
      if (blockers.every((bl) => admitted.has(bl.blockerId))) {
        admitted.add(id);
        pending.delete(id);
        if (blockers.length) waitsForMap.set(id, blockers.map((bl) => ({ item: bl.blockerId, milestone: bl.milestone })));
        changed = true;
      }
    }
  }
  for (const id of pending) {
    const blockers = inRunBlockersOf.get(id) || [];
    const unresolved = blockers.find((bl) => !admitted.has(bl.blockerId));
    const blockerShort = unresolved ? shortOf(unresolved.blockerId) : '?';
    const blockerCand = unresolved ? candById.get(unresolved.blockerId) : null;
    const blockerRole = (blockerCand && blockerCand.role) || 'work';
    deferred.push({ id, reason: `blocked by ${blockerShort} until ${blockerRole} (cross-run)` });
  }
  for (const id of R0.keys()) {
    if (!admitted.has(id) && !crossRunReasonOf.has(id) && !pending.has(id) && !waitsForMap.has(id) && !inRunBlockersOf.has(id)) {
      admitted.add(id);
    }
  }

  const inRunEdges = [];
  const waitsFor = {};
  for (const [id, waits] of waitsForMap) {
    waitsFor[id] = waits;
    for (const w of waits) inRunEdges.push({ from: w.item, to: id, milestone: w.milestone });
  }

  return { admitted: [...admitted], waitsFor, inRunEdges, deferred, excluded };
}

/**
 * orderItems(ids, byId, waitsFor) -> string[]
 * Topological (blockers first), then priority desc, complexity asc (null last), id asc.
 */
export function orderItems(ids, byId, waitsFor) {
  const idSet = new Set(ids);
  const inDegree = new Map();
  const adj = new Map();
  for (const id of ids) {
    inDegree.set(id, 0);
    adj.set(id, []);
  }
  for (const id of ids) {
    for (const w of (waitsFor && waitsFor[id]) || []) {
      if (idSet.has(w.item)) {
        inDegree.set(id, inDegree.get(id) + 1);
        adj.get(w.item).push(id);
      }
    }
  }
  const cmp = (a, b) => comparePriorityComplexityId(byId[a], byId[b]);
  const remaining = new Map(inDegree);
  let readyQueue = ids.filter((id) => remaining.get(id) === 0);
  const result = [];
  while (readyQueue.length) {
    readyQueue.sort(cmp);
    const id = readyQueue.shift();
    result.push(id);
    for (const dep of adj.get(id)) {
      remaining.set(dep, remaining.get(dep) - 1);
      if (remaining.get(dep) === 0) readyQueue.push(dep);
    }
  }
  for (const id of ids) if (!result.includes(id)) result.push(id);
  return result;
}

/**
 * applyCap(orderedIds, maxItems, waitsFor) -> {kept:string[], dropped:[{id,reason:'run size cap'}]}
 * Dropping an item cascades to anything that waitsFor it.
 */
export function applyCap(orderedIds, maxItems, waitsFor) {
  if (!maxItems || orderedIds.length <= maxItems) return { kept: [...orderedIds], dropped: [] };
  const kept = new Set(orderedIds.slice(0, maxItems));
  let changed = true;
  while (changed) {
    changed = false;
    for (const id of orderedIds) {
      if (!kept.has(id)) continue;
      const waits = (waitsFor && waitsFor[id]) || [];
      if (waits.some((w) => !kept.has(w.item))) {
        kept.delete(id);
        changed = true;
      }
    }
  }
  const dropped = orderedIds.filter((id) => !kept.has(id)).map((id) => ({ id, reason: 'run size cap' }));
  return { kept: orderedIds.filter((id) => kept.has(id)), dropped };
}

/**
 * resourceDeferrals(orderedIds, schemas) -> {kept, deferred:[{id,reason:'resource <key>'}], advisory:[{id,key}]}
 * First occurrence (in the given order) wins each exclusive key; advisory keys never defer.
 */
export function resourceDeferrals(orderedIds, schemas) {
  const heldExclusive = new Map();
  const kept = [];
  const deferred = [];
  const advisory = [];
  for (const id of orderedIds) {
    const resources = (schemas && schemas[id] && schemas[id].resources) || [];
    let blockedKey = null;
    for (const r of resources) {
      if (r.mode === 'advisory') {
        advisory.push({ id, key: r.key });
        continue;
      }
      if (r.mode === 'exclusive' && heldExclusive.has(r.key)) {
        blockedKey = r.key;
        break;
      }
    }
    if (blockedKey) {
      deferred.push({ id, reason: `resource ${blockedKey}` });
      continue;
    }
    for (const r of resources) {
      if (r.mode === 'exclusive') heldExclusive.set(r.key, id);
    }
    kept.push(id);
  }
  return { kept, deferred, advisory };
}

/**
 * lockSourceDeferrals(ids, derivedById, mode) -> {kept, deferred:[{id,reason:'no planning stage for lock keys'}]}
 * Shared mode only: a writing item with no planner-v1 stage defers unless it is the only writer.
 */
export function lockSourceDeferrals(ids, derivedById, mode) {
  if (mode !== 'shared') return { kept: [...ids], deferred: [] };
  const writingIds = ids.filter((id) => ((derivedById[id] || {}).stages || []).some((s) => s.writes));
  const kept = [];
  const deferred = [];
  for (const id of ids) {
    const stages = (derivedById[id] || {}).stages || [];
    const isWriting = stages.some((s) => s.writes);
    const hasPlanner = stages.some((s) => s.output === 'planner-v1');
    if (isWriting && !hasPlanner && writingIds.length > 1) {
      deferred.push({ id, reason: 'no planning stage for lock keys' });
    } else {
      kept.push(id);
    }
  }
  return { kept, deferred };
}

function shortFromId(id) {
  return String(id || '').replace(/-/g, '').slice(0, 8);
}

function normalizePath(p) {
  if (typeof p !== 'string') return p;
  let out = p.replace(/\\/g, '/');
  out = out.replace(/^\.\//, '');
  out = out.replace(/^([A-Za-z]):/, (m, d) => `${d.toLowerCase()}:`);
  return out;
}

/**
 * assembleArgs(snap, planned, opts) -> implement-wave/args-v1 object
 * planned = {ids, waitsFor, derivedById, deferred}
 * opts = {now, runId, mode, entryMode, probe, profile, scratchpad}
 */
export function assembleArgs(snap, planned, opts) {
  const { now, runId, mode, entryMode, probe = {}, profile = {}, scratchpad } = opts || {};
  const candidatesById = new Map((snap.candidates || []).map((c) => [c.id, c]));
  const repoRoot = (snap.git && snap.git.repoRoot) || '';
  const worktreeRoot = profile.worktreeRoot || '.claude/worktrees';
  const branchPrefixShared = (profile.branchPrefix && profile.branchPrefix.shared) || 'feat/';
  const branchPrefixPerItem = (profile.branchPrefix && profile.branchPrefix.perItem) || 'fix/';

  let sharedPath = null;
  let sharedBranch = null;
  if (mode === 'shared' && planned.ids.length) {
    const firstCand = candidatesById.get(planned.ids[0]);
    const parentShort = shortFromId((firstCand && firstCand.parentId) || (firstCand && firstCand.id) || '');
    sharedPath = normalizePath(`${repoRoot}/${worktreeRoot}/feat-${parentShort}`);
    sharedBranch = `${branchPrefixShared}${parentShort}`;
  }

  const items = planned.ids.map((id) => {
    const cand = candidatesById.get(id);
    const derived = planned.derivedById[id] || {};
    let worktree;
    let branch;
    if (mode === 'shared') {
      worktree = sharedPath;
      branch = sharedBranch;
    } else {
      worktree = normalizePath(`${repoRoot}/${worktreeRoot}/${cand.type}-${cand.short}`);
      branch = `${branchPrefixPerItem}${cand.short}`;
    }
    const schema = (snap.schemas && snap.schemas[id]) || {};
    return {
      id,
      short: cand.short,
      title: cand.title,
      priority: cand.priority,
      type: cand.type,
      schemaFree: !!derived.schemaFree,
      configFingerprint: derived.schemaFree ? null : (schema.configFingerprint ?? null),
      configSource: derived.schemaFree ? null : (schema.configSource ?? null),
      traits: cand.traits || [],
      itemTraits: cand.traits || [],
      worktree,
      branch,
      stages: derived.stages || [],
      orchestratorNotes: derived.orchestratorNotes || [],
      unownedRequired: derived.unownedRequired || [],
      waitsFor: entryMode === 'pre-entered' ? [] : ((planned.waitsFor && planned.waitsFor[id]) || [])
    };
  });

  let baseSha = (snap.git && snap.git.originMain) || '';
  if (mode === 'shared' && sharedPath) {
    const existing = snap.git && snap.git.worktrees && snap.git.worktrees[sharedPath];
    if (existing && existing.head) baseSha = existing.head;
  }

  const project = {
    shell: profile.shell || 'bash',
    verify: profile.verify || [],
    searchScope: (profile.searchScope || []).map(normalizePath)
  };
  if (scratchpad) project.scratchDir = normalizePath(`${scratchpad}/run-wave/${runId}`);

  const capabilities = { features: snap.features || [], phase0Hooks: !!probe.phase0Hooks };
  if (probe.pluginVersion) capabilities.pluginVersion = probe.pluginVersion;

  return {
    contract: ARGS_CONTRACT,
    runId,
    startedAt: now,
    rootId: snap.rootId,
    planDocSlug: `run/${runId}`,
    baseSha,
    worktreeMode: mode,
    entryMode,
    capabilities,
    project,
    items,
    deferred: planned.deferred || []
  };
}

function buildRunId(now, firstCandidate) {
  const d = new Date(now);
  const pad = (n) => String(n).padStart(2, '0');
  const stamp = `${d.getUTCFullYear()}${pad(d.getUTCMonth() + 1)}${pad(d.getUTCDate())}${pad(d.getUTCHours())}${pad(d.getUTCMinutes())}`;
  return `r-${stamp}-${(firstCandidate && firstCandidate.short) || 'unknown'}`;
}

function buildWorktreesToCreate(snap, args, mode) {
  const existing = new Set(Object.keys((snap.git && snap.git.worktrees) || {}));
  const base = (snap.git && snap.git.originMain) || null;
  if (mode === 'shared') {
    const wt = args.items[0] && args.items[0].worktree;
    const branch = args.items[0] && args.items[0].branch;
    if (wt && !existing.has(wt)) return [{ path: wt, branch, base }];
    return [];
  }
  const list = [];
  for (const item of args.items) {
    if (!existing.has(item.worktree)) list.push({ path: item.worktree, branch: item.branch, base });
  }
  return list;
}

/**
 * buildPlanDoc(snap, opts) -> {ok:true, doc} | {ok:false, refused:boolean, errors, doc?}
 * opts = {now, maxItems=5, mode='auto', entry='auto', method?, scratchpad}
 * Runs the whole pipeline: validateSnapshot -> chooseMode/chooseEntry -> deriveStages ->
 * frontier -> resourceDeferrals -> orderItems -> applyCap -> lockSourceDeferrals ->
 * assembleArgs -> size check.
 */
export function buildPlanDoc(snap, opts = {}) {
  const { now, maxItems = 5, mode: modeReq = 'auto', entry: entryReq = 'auto', method: methodReq, scratchpad } = opts;

  const snapCheck = validateSnapshot(snap);
  if (!snapCheck.ok) {
    return { ok: false, refused: false, errors: snapCheck.errors };
  }

  const probe = snap.probe || {};
  const profile = snap.profile || {};
  const candidatesById = new Map((snap.candidates || []).map((c) => [c.id, c]));

  const derivedById = {};
  for (const c of snap.candidates || []) {
    const schemaEntry = (snap.schemas || {})[c.id];
    derivedById[c.id] = deriveStages(c, schemaEntry, {
      rulesServed: snap.rulesServed || [],
      noteActors: (snap.noteActors || {})[c.id] || [],
      profile
    });
  }

  const mode = chooseMode(snap, modeReq);
  const entryMode = chooseEntry(probe, entryReq);

  const guardErrors = [];
  if (entryReq === 'seat' && !probe.phase0Hooks) {
    guardErrors.push('seat entry requires phase0Hooks');
  }
  const feats = new Set(snap.features || []);
  const requiredFeats = ['seats', 'dispatchBySeat', 'rules'];
  const missingFeats = requiredFeats.filter((f) => !feats.has(f));
  if (methodReq === 'A' && missingFeats.length) {
    guardErrors.push(`server lacks ${missingFeats[0]}; front door must use its fallback`);
  }
  if (guardErrors.length) {
    return { ok: false, refused: true, errors: guardErrors };
  }

  const degradations = [];
  let method = methodReq;
  if (!method) {
    method = missingFeats.length === 0 ? 'A' : 'B';
    if (method === 'B') degradations.push(`method B: server lacks ${missingFeats.join(', ')}`);
  }

  const fr = frontier(snap, derivedById, { mode, entryMode });

  const priorityOrdered = [...fr.admitted].sort((a, b) => comparePriorityComplexityId(candidatesById.get(a), candidatesById.get(b)));

  const schemasForResources = {};
  for (const id of priorityOrdered) schemasForResources[id] = (snap.schemas || {})[id] || {};
  const resourceResult = resourceDeferrals(priorityOrdered, schemasForResources);

  const byIdForOrder = {};
  for (const id of resourceResult.kept) byIdForOrder[id] = candidatesById.get(id);
  const topoOrdered = orderItems(resourceResult.kept, byIdForOrder, fr.waitsFor);

  const capResult = applyCap(topoOrdered, maxItems, fr.waitsFor);
  const lockResult = lockSourceDeferrals(capResult.kept, derivedById, mode);

  // Cascade: an item waitsFor-ing something lockSourceDeferrals dropped can no longer be
  // scheduled either (a dangling waitsFor target is refused by the B1 core), so it defers too.
  const lockKeptSet = new Set(lockResult.kept);
  const cascadeDropped = [];
  let cascadeChanged = true;
  while (cascadeChanged) {
    cascadeChanged = false;
    for (const id of [...lockKeptSet]) {
      const waits = fr.waitsFor[id] || [];
      if (waits.some((w) => !lockKeptSet.has(w.item))) {
        lockKeptSet.delete(id);
        cascadeDropped.push({ id, reason: 'no planning stage for lock keys' });
        cascadeChanged = true;
      }
    }
  }
  const finalIds = capResult.kept.filter((id) => lockKeptSet.has(id));

  const allDeferred = [
    ...fr.deferred,
    ...resourceResult.deferred,
    ...capResult.dropped,
    ...lockResult.deferred,
    ...cascadeDropped
  ];
  const excluded = fr.excluded;

  if (finalIds.length === 0) {
    // args is null (no items admitted), so this is the only place the deferral reasons survive -
    // meta carries them here even though the non-empty-run meta shape leaves `deferred` to args.
    const doc = {
      contract: PLAN_DOC_CONTRACT,
      args: null,
      meta: {
        method,
        featureBaseSha: (snap.git && snap.git.originMain) || null,
        worktreesToCreate: [],
        excluded,
        deferred: allDeferred,
        warnings: [],
        estAgents: 0,
        sizeGuideline: probe.workflowSizeGuideline ?? null,
        degradations,
        inRunEdges: [],
        advisoryResources: resourceResult.advisory
      }
    };
    return { ok: false, refused: true, errors: ['no items admitted'], doc };
  }

  const filteredWaitsFor = {};
  for (const id of finalIds) filteredWaitsFor[id] = fr.waitsFor[id] || [];

  const planned = { ids: finalIds, waitsFor: filteredWaitsFor, derivedById, deferred: allDeferred };
  const args = assembleArgs(snap, planned, {
    now,
    runId: buildRunId(now, candidatesById.get(finalIds[0])),
    mode,
    entryMode,
    probe,
    profile,
    scratchpad
  });

  const warnings = [];
  for (const id of finalIds) {
    warnings.push(...(derivedById[id].warnings || []));
    degradations.push(...(derivedById[id].degradations || []));
  }

  const estAgents = finalIds.reduce((sum, id) => sum + ((derivedById[id].stages || []).length), 0);
  const worktreesToCreate = buildWorktreesToCreate(snap, args, mode);

  const inRunEdges = [];
  for (const id of finalIds) {
    for (const w of filteredWaitsFor[id] || []) inRunEdges.push({ from: w.item, to: id, milestone: w.milestone });
  }

  const doc = {
    contract: PLAN_DOC_CONTRACT,
    args,
    meta: {
      method,
      featureBaseSha: (snap.git && snap.git.originMain) || null,
      worktreesToCreate,
      excluded,
      warnings,
      estAgents,
      sizeGuideline: probe.workflowSizeGuideline ?? null,
      degradations,
      inRunEdges,
      advisoryResources: resourceResult.advisory
    }
  };

  const size = Buffer.byteLength(JSON.stringify(doc), 'utf8');
  if (size > PLAN_DOC_MAX_BYTES) {
    return {
      ok: false,
      refused: true,
      errors: [`plan exceeds size cap (${size} > ${PLAN_DOC_MAX_BYTES}); use a smaller --max-items`],
      doc
    };
  }

  return { ok: true, doc };
}

/**
 * explainPlan(doc) -> string
 * Renders a plan-doc-v1 as a text table: stages, edges, deferrals, refusals, agent estimate.
 */
export function explainPlan(doc) {
  const lines = [];
  const meta = (doc && doc.meta) || {};
  if (!doc || !doc.args) {
    lines.push('No admitted items.');
  } else {
    lines.push(`Run ${doc.args.runId} (${meta.method || '?'}) - ${doc.args.items.length} item(s)`);
    for (const item of doc.args.items) {
      lines.push(`- ${item.short} ${item.title || ''}`.trim());
      for (const s of item.stages) {
        const agent = (s.dispatch && s.dispatch.agent) ?? 'none';
        const model = (s.dispatch && s.dispatch.model) ?? '?';
        lines.push(`    ${s.seat} . ${s.phase} . notes=[${(s.notes || []).join(',')}] . ${agent}/${model}`);
      }
    }
  }
  lines.push('');
  lines.push(`In-run edges: ${(meta.inRunEdges || []).map((e) => `${e.from}->${e.to}`).join(', ') || 'none'}`);
  const deferredList = (doc && doc.args && doc.args.deferred) || meta.deferred || [];
  lines.push(`Deferrals: ${deferredList.map((d) => `${d.id}: ${d.reason}`).join('; ') || 'none'}`);
  lines.push(`Refusals/excluded: ${(meta.excluded || []).map((e) => `${e.id}: ${e.reason}`).join('; ') || 'none'}`);
  lines.push(`Estimated agents: ${meta.estAgents ?? '?'} vs size guideline ${meta.sizeGuideline ?? 'unset'}`);
  lines.push(`Degradations: ${(meta.degradations || []).join('; ') || 'none'}`);
  return lines.join('\n');
}

function matchesType(value, type) {
  switch (type) {
    case 'object':
      return value !== null && typeof value === 'object' && !Array.isArray(value);
    case 'array':
      return Array.isArray(value);
    case 'string':
      return typeof value === 'string';
    case 'number':
      return typeof value === 'number';
    case 'integer':
      return Number.isInteger(value);
    case 'boolean':
      return typeof value === 'boolean';
    case 'null':
      return value === null;
    default:
      return true;
  }
}

function validateAgainstSchema(value, schema, path) {
  const errors = [];
  if (!schema) return errors;
  if (schema.const !== undefined && value !== schema.const) errors.push(`${path}: expected const ${schema.const}`);
  if (schema.enum && !schema.enum.includes(value)) errors.push(`${path}: expected one of ${schema.enum.join('|')}`);
  if (schema.type) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!types.some((t) => matchesType(value, t))) {
      errors.push(`${path}: expected type ${types.join('|')}`);
      return errors;
    }
  }
  if (schema.pattern && typeof value === 'string' && !new RegExp(schema.pattern).test(value)) {
    errors.push(`${path}: does not match pattern ${schema.pattern}`);
  }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    for (const req of schema.required || []) {
      if (value == null || !(req in value)) errors.push(`${path}.${req}: required`);
    }
    for (const [k, subschema] of Object.entries(schema.properties || {})) {
      if (value[k] !== undefined) errors.push(...validateAgainstSchema(value[k], subschema, `${path}.${k}`));
    }
  }
  if (Array.isArray(value)) {
    if (schema.minItems && value.length < schema.minItems) errors.push(`${path}: minItems ${schema.minItems}`);
    if (schema.items) value.forEach((v, i) => errors.push(...validateAgainstSchema(v, schema.items, `${path}[${i}]`)));
  }
  return errors;
}

/**
 * validatePlanDoc(doc, argsSchema, snapshot?) -> {ok:boolean, errors:string[]}
 * Structural check against argsSchema plus the four run-level guard strings; with a snapshot
 * also flags staleness ("stale <short>: configFingerprint|traits|not a candidate").
 */
export function validatePlanDoc(doc, argsSchema, snapshot) {
  const errors = [];
  if (!doc || doc.contract !== PLAN_DOC_CONTRACT) errors.push(`doc contract must be ${PLAN_DOC_CONTRACT}`);

  if (doc && doc.args) {
    errors.push(...validateAgainstSchema(doc.args, argsSchema, 'args'));

    if (doc.args.entryMode === 'seat' && !(doc.args.capabilities && doc.args.capabilities.phase0Hooks)) {
      errors.push('seat entry requires phase0Hooks');
    }
    if (doc.args.entryMode === 'pre-entered') {
      for (const item of doc.args.items || []) {
        if ((item.waitsFor || []).length) errors.push('pre-entered mode forbids waitsFor');
      }
    }
    if (doc.args.worktreeMode === 'per-item') {
      for (const item of doc.args.items || []) {
        if ((item.waitsFor || []).length) errors.push('per-item mode forbids waitsFor');
      }
    }
    const feats = new Set((doc.args.capabilities && doc.args.capabilities.features) || []);
    for (const f of ['seats', 'dispatchBySeat', 'rules']) {
      if (!feats.has(f)) {
        errors.push(`server lacks ${f}; front door must use its fallback`);
        break;
      }
    }
  }

  if (snapshot) {
    const byId = new Map((snapshot.candidates || []).map((c) => [c.id, c]));
    for (const item of (doc && doc.args && doc.args.items) || []) {
      const cand = byId.get(item.id);
      if (!cand) {
        errors.push(`stale ${item.short}: not a candidate`);
        continue;
      }
      const schema = snapshot.schemas && snapshot.schemas[item.id];
      if (!item.schemaFree && schema && schema.configFingerprint !== item.configFingerprint) {
        errors.push(`stale ${item.short}: configFingerprint`);
      }
      const snapTraits = JSON.stringify([...(cand.traits || [])].sort());
      const itemTraits = JSON.stringify([...(item.traits || [])].sort());
      if (snapTraits !== itemTraits) errors.push(`stale ${item.short}: traits`);
    }
  }

  return { ok: errors.length === 0, errors };
}
