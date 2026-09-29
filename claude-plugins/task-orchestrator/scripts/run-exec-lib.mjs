// run-exec-lib.mjs — Method B scheduling ("next"), prompt rendering, envelope parsing/
// mapping, the declarations scan, post-run git verification, the expected-actor table, and
// the provenance line — over the SAME run plan Method A (workflows/implement-wave.js) runs.
//
// Pure except EXEC_COMMANDS' handlers, which call the injected `io` (readInput/readText/
// writeOut/fail/exit/git/loadCore) rather than fs/process/git directly — this file itself
// never references those globals. Every function below takes the loaded core
// (scripts/lib/wave-core.mjs) as an injected first parameter where it needs core behaviour;
// pure helpers (validateAgainst, scanReport's word list, verify, expectedActors/auditActors,
// provenance, reviewPrompt) take no core because they don't call into the @core-begin region,
// per the frozen contract (Appendix D, dispatch contract for f8d3232e).

import { extractLastBalancedJson } from './ralph-lib.mjs'
import { formatProvenance } from './provenance-lib.mjs'

export const STATE_CONTRACT = 'run-wave/state-v1'

/** Same behaviour-word list as implement-wave.js's scanDeclarations (kept in sync by hand). */
export const SCAN_WORDS = ['returns', 'throws', 'falls back', 'catches', 'calls', 'if', 'when', 'otherwise', 'instead']

export const REVIEW_RULES = ['protocol.in-phase-seat', 'review-scoping']

// ---------------------------------------------------------------------------------------
// Path normalization (duplicated from implement-wave.js's normalizePath — verify(doc, result,
// gitFacts) is frozen without a core parameter, so it cannot reach core.normalizePath; this
// is the same trivial, pure algorithm).
// ---------------------------------------------------------------------------------------
function normalizePath(p) {
  let s = String(p).replace(/\\/g, '/')
  if (s.slice(0, 2) === './') s = s.slice(2)
  const m = /^([a-zA-Z]):\//.exec(s)
  if (m) s = m[1].toLowerCase() + s.slice(1)
  return s
}

// ---------------------------------------------------------------------------------------
// State helpers
// ---------------------------------------------------------------------------------------

/**
 * initState(doc, method) -> {contract, runId, phase:'planned', method, turns:0, stages:{}, outs:{}}
 * An absent or {} state file reads as initState(doc) (method left undefined).
 */
export function initState(doc, method) {
  return {
    contract: STATE_CONTRACT,
    runId: doc.args.runId,
    phase: 'planned',
    method,
    turns: 0,
    stages: {},
    outs: {},
  }
}

/**
 * checkState(doc, state) -> {ok:true} | {ok:false, error}.
 * runId mismatch always checked when state carries one; contract checked only when present.
 */
export function checkState(doc, state) {
  if (!state) return { ok: true }
  if (state.runId !== undefined && state.runId !== doc.args.runId) {
    return { ok: false, error: 'state runId mismatch' }
  }
  if (state.contract !== undefined && state.contract !== STATE_CONTRACT) {
    return { ok: false, error: `state contract ${state.contract}` }
  }
  return { ok: true }
}

function cloneState(state) {
  return JSON.parse(JSON.stringify(state || { stages: {}, outs: {} }))
}

// ---------------------------------------------------------------------------------------
// Plan resolution
// ---------------------------------------------------------------------------------------

/**
 * resolvePlan(core, doc) -> {plan, preflight}.
 * plan = core.normalizeArgs(doc.args).plan; throws 'invalid args: <reason>' on failure — the
 * schema-level reasons normalizeArgs returns already carry that prefix, and the run-level
 * guard reasons (e.g. 'server lacks seats; …', 'seat entry requires phase0Hooks') do not, so
 * it is added here when missing, per Appendix D.
 */
export function resolvePlan(core, doc) {
  const normalized = core.normalizeArgs(doc.args)
  if (!normalized.ok) {
    const reason = normalized.reason && normalized.reason.startsWith('invalid args:')
      ? normalized.reason
      : `invalid args: ${normalized.reason}`
    throw new Error(reason)
  }
  const preflight = core.preflight(normalized.plan)
  return { plan: normalized.plan, preflight }
}

export function findItem(plan, idOrShort) {
  const found = plan.items.find((it) => it.id === idOrShort || it.short === idOrShort)
  if (!found) throw new Error(`unknown item ${idOrShort}`)
  return found
}

export function findStage(item, seat) {
  const stage = item.stages.find((s) => s.seat === seat)
  if (!stage) throw new Error(`unknown seat ${seat} for ${item.short}`)
  return stage
}

/** outsBySeat(item, state) -> {[seat]: state.outs['<short>:<seat>']} (defined entries only). */
export function outsBySeat(item, state) {
  const out = {}
  const outs = (state && state.outs) || {}
  for (const stage of item.stages) {
    const key = `${item.short}:${stage.seat}`
    if (Object.prototype.hasOwnProperty.call(outs, key) && outs[key] !== undefined) {
      out[stage.seat] = outs[key]
    }
  }
  return out
}

function stageStateStatus(state, item, stage) {
  const key = `${item.short}:${stage.seat}`
  const entry = state && state.stages && state.stages[key]
  if (!entry || entry.status === undefined) return 'pending'
  if (entry.status === 'done' || entry.status === 'stopped' || entry.status === 'deferred') return entry.status
  return 'in-flight'
}

function computeItemStatus(item, state, refusedById) {
  if (refusedById.has(item.id)) {
    return { terminal: true, inFlight: false, status: 'refused', reason: refusedById.get(item.id) }
  }
  for (const stage of item.stages) {
    const st = stageStateStatus(state, item, stage)
    if (st === 'pending') return { terminal: false, inFlight: false }
    if (st === 'in-flight') return { terminal: false, inFlight: true, stage }
    if (st === 'stopped' || st === 'deferred') {
      const entry = state.stages[`${item.short}:${stage.seat}`]
      return { terminal: true, inFlight: false, status: st, reason: entry.reason }
    }
    // 'done' -> keep scanning later stages
  }
  return { terminal: true, inFlight: false, status: 'done', reason: 'all stages done' }
}

/**
 * cascadeDeferrals(plan, state, statusByItemId, byId, refusedById) -> Map(itemId -> reason).
 * B3 fix: an item settled deferred THIS pass (its in-run blocker failed, or is itself
 * cascade-deferred) must be visible to LATER items in the same next() call — otherwise a
 * dependent of a same-pass deferral only ever sees 'waiting', and the run can never reach
 * complete:true. Runs to a fixpoint since a chain (A->B->C) can cascade more than one hop.
 * A blocker's failure point is "at or before the milestone" either when a real per-stage
 * entry up to and including the milestone is stopped/deferred, the blocker was
 * preflight-refused, or the blocker is ITSELF cascade-deferred this pass (which, by
 * construction, only ever happens before its own stage 0 runs — i.e. always before any
 * milestone).
 */
function cascadeDeferrals(plan, state, statusByItemId, byId, refusedById) {
  const cascade = new Map()
  let changed = true
  while (changed) {
    changed = false
    for (const item of plan.items) {
      const st = statusByItemId.get(item.id)
      if (st.terminal || cascade.has(item.id)) continue
      if (!Array.isArray(item.waitsFor) || item.waitsFor.length === 0) continue
      const stageIndex = item.stages.findIndex((s) => stageStateStatus(state, item, s) === 'pending')
      if (stageIndex !== 0) continue

      for (const w of item.waitsFor) {
        const blocker = byId.get(w.item)
        const blockerShort = blocker ? blocker.short : w.item

        if (cascade.has(w.item)) {
          cascade.set(item.id, `in-run blocker ${blockerShort} did not reach ${w.milestone}`)
          changed = true
          break
        }

        const blockerStatus = statusByItemId.get(w.item)
        if (!blockerStatus || !blockerStatus.terminal || blockerStatus.status === 'done') continue

        if (refusedById.has(w.item)) {
          cascade.set(item.id, `in-run blocker ${blockerShort} did not reach ${w.milestone}`)
          changed = true
          break
        }

        const milestoneStage = blocker.stages.find((s) => s.seat === w.milestone)
        const milestoneIndex = blocker.stages.indexOf(milestoneStage)
        let failed = false
        for (let j = 0; j <= milestoneIndex; j++) {
          const sjStatus = stageStateStatus(state, blocker, blocker.stages[j])
          if (sjStatus === 'stopped' || sjStatus === 'deferred') {
            failed = true
            break
          }
        }
        if (failed) {
          cascade.set(item.id, `in-run blocker ${blockerShort} did not reach ${w.milestone}`)
          changed = true
          break
        }
      }
    }
  }
  return cascade
}

/**
 * next(core, doc, state) -> {dispatch, waiting, settled, complete}.
 * Args order then stage order, deterministic. Per item, only the first unsettled stage is
 * considered (<=1 in flight per item). See Appendix D of the f8d3232e dispatch contract for
 * the full waitsFor / per-item-overlap / lock gating algorithm this mirrors.
 */
export function next(core, doc, state) {
  const { plan, preflight } = resolvePlan(core, doc)
  const refusedById = new Map(preflight.refused.map((r) => [r.id, r.reason]))
  const byId = new Map(plan.items.map((it) => [it.id, it]))

  const statusByItemId = new Map()
  for (const item of plan.items) statusByItemId.set(item.id, computeItemStatus(item, state, refusedById))

  const cascade = cascadeDeferrals(plan, state, statusByItemId, byId, refusedById)
  const isSettled = (itemId) => statusByItemId.get(itemId).terminal || cascade.has(itemId)

  const inFlightLockKeys = new Set()
  for (const item of plan.items) {
    const st = statusByItemId.get(item.id)
    if (st.inFlight) {
      const keys = core.lockKeysFor(item, st.stage, outsBySeat(item, state), plan)
      for (const k of keys) inFlightLockKeys.add(k)
    }
  }

  const dispatch = []
  const waiting = []
  const settled = []
  const chosenLockKeys = new Set()

  for (const item of plan.items) {
    const st = statusByItemId.get(item.id)
    if (st.terminal) {
      settled.push({ item: item.short, status: st.status, reason: st.reason })
      continue
    }
    if (cascade.has(item.id)) {
      settled.push({ item: item.short, status: 'deferred', reason: cascade.get(item.id) })
      continue
    }
    if (st.inFlight) continue

    const stageIndex = item.stages.findIndex((s) => stageStateStatus(state, item, s) === 'pending')
    const stage = item.stages[stageIndex]

    // stage-0 waitsFor gating. Any blocker that would FAIL this item was already resolved by
    // cascadeDeferrals above (this item would be in `cascade`, handled by the branch above) —
    // so every remaining blocker here is still legitimately pending/in-flight.
    if (stageIndex === 0 && Array.isArray(item.waitsFor) && item.waitsFor.length > 0) {
      let waitOn = null
      for (const w of item.waitsFor) {
        const blocker = byId.get(w.item)
        const blockerShort = blocker ? blocker.short : w.item
        const milestoneStage = blocker.stages.find((s) => s.seat === w.milestone)
        if (stageStateStatus(state, blocker, milestoneStage) !== 'done') {
          waitOn = `${blockerShort}:${w.milestone}`
          break
        }
      }
      if (waitOn) {
        waiting.push({ item: item.short, seat: stage.seat, on: waitOn })
        continue
      }
    }

    // per-item-mode overlap gating, once the item's own planner-v1 stage is done
    const plannerStage = item.stages.find((s) => s.output === 'planner-v1')
    if (plan.worktreeMode !== 'shared' && plannerStage && stage !== plannerStage &&
        stageStateStatus(state, item, plannerStage) === 'done') {
      const idx = plan.items.indexOf(item)
      const earlierItems = plan.items.slice(0, idx)
      let waitOnPlanner = null
      for (const earlier of earlierItems) {
        const earlierPlanner = earlier.stages.find((s) => s.output === 'planner-v1')
        if (!earlierPlanner) continue
        const pStatus = stageStateStatus(state, earlier, earlierPlanner)
        if (!isSettled(earlier.id) && pStatus !== 'done') {
          waitOnPlanner = `planner ${earlier.short}`
          break
        }
      }
      if (waitOnPlanner) {
        waiting.push({ item: item.short, seat: stage.seat, on: waitOnPlanner })
        continue
      }
      const higher = earlierItems.map((earlier) => {
        const earlierPlanner = earlier.stages.find((s) => s.output === 'planner-v1')
        const pStatus = earlierPlanner ? stageStateStatus(state, earlier, earlierPlanner) : null
        const output = pStatus === 'done' ? outsBySeat(earlier, state)[earlierPlanner.seat] : null
        return { short: earlier.short, output }
      })
      const mine = { short: item.short, output: outsBySeat(item, state)[plannerStage.seat] }
      const overlap = core.overlapDeferral(mine, higher, plan.worktreeMode)
      if (overlap) {
        settled.push({ item: item.short, status: 'deferred', reason: overlap.reason })
        continue
      }
    }

    // lock gating
    const keys = core.lockKeysFor(item, stage, outsBySeat(item, state), plan)
    const conflict = keys.find((k) => inFlightLockKeys.has(k) || chosenLockKeys.has(k))
    if (conflict) {
      waiting.push({ item: item.short, seat: stage.seat, on: `lock ${conflict}` })
      continue
    }
    for (const k of keys) chosenLockKeys.add(k)

    dispatch.push({
      item: item.short,
      seat: stage.seat,
      model: stage.dispatch.model,
      agentType: stage.dispatch.agent ?? null,
      lockKeys: keys,
    })
  }

  const anyInFlight = plan.items.some((it) => statusByItemId.get(it.id).inFlight)
  const complete = dispatch.length === 0 && waiting.length === 0 && !anyInFlight

  return { dispatch, waiting, settled, complete }
}

/** prompt(core, doc, state, idOrShort, seat) -> core.seatPrompt(plan, item, stage, outsBySeat(item, state)). */
export function prompt(core, doc, state, idOrShort, seat) {
  const { plan } = resolvePlan(core, doc)
  const item = findItem(plan, idOrShort)
  const stage = findStage(item, seat)
  return core.seatPrompt(plan, item, stage, outsBySeat(item, state))
}

// ---------------------------------------------------------------------------------------
// Envelope validation
// ---------------------------------------------------------------------------------------

function typeOfValue(v) {
  if (v === null) return 'null'
  if (Array.isArray(v)) return 'array'
  return typeof v
}

function matchesType(v, t) {
  if (t === 'null') return v === null
  if (t === 'array') return Array.isArray(v)
  if (t === 'integer') return typeof v === 'number' && Number.isInteger(v)
  if (t === 'object') return v !== null && typeof v === 'object' && !Array.isArray(v)
  return typeof v === t
}

/**
 * validateAgainst(schema, value) -> string[] of '<$.path>: <problem>'.
 * Covers type (incl. type arrays and null), required, properties, items, enum, const.
 */
export function validateAgainst(schema, value, path = '$') {
  const errors = []
  if (!schema) return errors

  if (schema.type !== undefined) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type]
    if (!types.some((t) => matchesType(value, t))) {
      errors.push(`${path}: expected type ${types.join('|')}, got ${typeOfValue(value)}`)
      return errors
    }
  }

  if (schema.enum !== undefined) {
    if (!schema.enum.some((e) => JSON.stringify(e) === JSON.stringify(value))) {
      errors.push(`${path}: not in enum [${schema.enum.join(',')}]`)
    }
  }

  if (schema.const !== undefined && JSON.stringify(schema.const) !== JSON.stringify(value)) {
    errors.push(`${path}: expected const ${JSON.stringify(schema.const)}`)
  }

  const isObjectValue = value !== null && typeof value === 'object' && !Array.isArray(value)
  if ((schema.type === 'object' || schema.properties) && isObjectValue) {
    for (const r of schema.required || []) {
      if (!(r in value)) errors.push(`${path}.${r}: required`)
    }
    const props = schema.properties || {}
    for (const [k, sub] of Object.entries(props)) {
      if (k in value) errors.push(...validateAgainst(sub, value[k], `${path}.${k}`))
    }
  }

  if (schema.type === 'array' && schema.items && Array.isArray(value)) {
    value.forEach((item, i) => {
      errors.push(...validateAgainst(schema.items, item, `${path}[${i}]`))
    })
  }

  return errors
}

/**
 * stageResult(core, doc, state, idOrShort, seat, finalText, {agentTypeUsed, agentTypeFallback}={})
 * -> {status, reason, state} (a NEW state object; input not mutated).
 * Parse via extractLastBalancedJson -> JSON.parse -> validateAgainst(envelopeSchema). Empty/
 * whitespace text and unparseable text are both treated as an invalid envelope. First
 * failure -> retry (envelopeRetries:1 recorded); second failure -> stopped 'invalid envelope'.
 * A valid envelope maps via core.mapStageResult and records outs[key] = env.output unredacted.
 */
export function stageResult(core, doc, state, idOrShort, seat, finalText, opts = {}) {
  const { plan } = resolvePlan(core, doc)
  const item = findItem(plan, idOrShort)
  const stage = findStage(item, seat)
  const key = `${item.short}:${seat}`
  const prevEntry = (state && state.stages && state.stages[key]) || {}
  const prevRetries = prevEntry.envelopeRetries || 0

  const text = finalText === null || finalText === undefined ? '' : String(finalText)
  let env = null
  let parseError = null

  if (text.trim().length === 0) {
    parseError = 'empty response'
  } else {
    try {
      env = JSON.parse(text)
    } catch {
      const extracted = extractLastBalancedJson(text)
      if (extracted) {
        try {
          env = JSON.parse(extracted)
        } catch {
          parseError = 'unparseable JSON'
        }
      } else {
        parseError = 'unparseable JSON'
      }
    }
  }

  let validationErrors = []
  if (!parseError) {
    const schema = core.envelopeSchema(stage.output, plan.outputSchemas)
    validationErrors = validateAgainst(schema, env)
  }

  const newState = cloneState(state)
  newState.stages = newState.stages || {}
  newState.outs = newState.outs || {}

  if (parseError || validationErrors.length > 0) {
    const detail = parseError || validationErrors[0]
    if (prevRetries >= 1) {
      newState.stages[key] = Object.assign({}, prevEntry, {
        status: 'stopped',
        reason: 'invalid envelope',
        envelopeRetries: prevRetries + 1,
      })
      return { status: 'stopped', reason: 'invalid envelope', state: newState }
    }
    newState.stages[key] = Object.assign({}, prevEntry, {
      status: 'retry-pending',
      envelopeRetries: prevRetries + 1,
    })
    return { status: 'retry', reason: `invalid envelope: ${detail}`, state: newState }
  }

  const mapped = core.mapStageResult(stage, env, plan.entryMode)
  newState.stages[key] = Object.assign({}, prevEntry, {
    status: mapped.status,
    reason: mapped.reason,
    pre: (env.commits && env.commits.pre) || '',
    post: (env.commits && env.commits.post) || '',
    modelReported: env.modelReported || '',
    agentTypeUsed: opts.agentTypeUsed || null,
    agentTypeFallback: !!opts.agentTypeFallback,
    notes: env.notes || [],
    files: env.files || [],
  })
  newState.outs[key] = env.output

  return { status: mapped.status, reason: mapped.reason, state: newState }
}

/**
 * scanReport(core, text) -> {clean, hits:[{line (1-based), word}], redacted}.
 * hits.length === core.scanDeclarations(text).stripped.length by construction (same word
 * list, same 'runtime call order:' exemption; one hit recorded per stripped line).
 */
export function scanReport(core, text) {
  const lines = String(text).split('\n')
  const hits = []
  lines.forEach((line, idx) => {
    if (line.trim().toLowerCase().startsWith('runtime call order:')) return
    for (const w of SCAN_WORDS) {
      const re = new RegExp(`\\b${w.replace(/ /g, '\\s+')}\\b`, 'i')
      if (re.test(line)) {
        hits.push({ line: idx + 1, word: w })
        break
      }
    }
  })
  const scanned = core.scanDeclarations(text)
  return { clean: hits.length === 0, hits, redacted: scanned.text }
}

/**
 * resultFromState(core, doc, state) -> result-v1, matching runPlan's own shape.
 * An item's status/reason is its first non-done stage. A pending stage with no state entry
 * takes `next(core, doc, state)`'s settled reason for this item when `next` would settle it
 * this pass (e.g. an in-run blocker failure or an overlap deferral it never got to start) —
 * only a genuinely missing result (agent dispatched, no stage-result ever recorded, and
 * `next` does NOT consider the item settled) falls back to 'agent returned null'. Refused
 * items are reported ONLY via `refused`, never duplicated into `items`.
 */
export function resultFromState(core, doc, state) {
  const { plan, preflight } = resolvePlan(core, doc)
  const nextInfo = next(core, doc, state)
  const settledByShort = new Map(nextInfo.settled.map((s) => [s.item, s]))

  const items = []
  for (const item of plan.items) {
    const settledInfo = settledByShort.get(item.short)
    if (settledInfo && settledInfo.status === 'refused') continue

    const stagesOut = []
    const outputs = {}
    let status = 'done'
    let reason = 'all stages done'

    for (const stage of item.stages) {
      const key = `${item.short}:${stage.seat}`
      const entry = state && state.stages && state.stages[key]
      if (!entry || entry.status === undefined) {
        if (settledInfo && settledInfo.status !== 'done') {
          stagesOut.push({
            seat: stage.seat, status: settledInfo.status, reason: settledInfo.reason,
            modelReported: '', agentTypeUsed: null, agentTypeFallback: false,
            notes: [], commits: { pre: '', post: '' }, files: [],
          })
          status = settledInfo.status
          reason = settledInfo.reason
        } else {
          stagesOut.push({
            seat: stage.seat, status: 'stopped', reason: 'agent returned null',
            modelReported: '', agentTypeUsed: null, agentTypeFallback: false,
            notes: [], commits: { pre: '', post: '' }, files: [],
          })
          status = 'stopped'
          reason = 'agent returned null'
        }
        break
      }
      stagesOut.push({
        seat: stage.seat,
        status: entry.status,
        reason: entry.reason,
        modelReported: entry.modelReported || '',
        agentTypeUsed: entry.agentTypeUsed || null,
        agentTypeFallback: !!entry.agentTypeFallback,
        notes: entry.notes || [],
        // stageResult() writes flat pre/post; a state entry built by hand from a raw envelope
        // (as an agent would return, or as a caller migrating an in-flight run might stash it)
        // carries them nested under commits — accept either.
        commits: {
          pre: entry.pre !== undefined ? entry.pre : ((entry.commits && entry.commits.pre) || ''),
          post: entry.post !== undefined ? entry.post : ((entry.commits && entry.commits.post) || ''),
        },
        files: entry.files || [],
      })
      if (state.outs && key in state.outs) outputs[stage.seat] = state.outs[key]
      if (entry.status !== 'done') {
        status = entry.status
        reason = entry.reason
        break
      }
    }

    items.push({ id: item.id, short: item.short, status, reason, stages: stagesOut, outputs })
  }

  return {
    contract: 'implement-wave/result-v1',
    started: true,
    runId: plan.runId,
    planDocSlug: plan.planDocSlug,
    items,
    refused: preflight.refused,
    deferred: plan.deferred || [],
  }
}

// ---------------------------------------------------------------------------------------
// Post-run verification (pure gitFacts + no core — frozen without a core parameter)
// ---------------------------------------------------------------------------------------

function outputForStageId(resultItem, argItem, outputId) {
  const stage = argItem.stages.find((s) => s.output === outputId)
  if (!stage) return null
  return resultItem.outputs ? resultItem.outputs[stage.seat] : undefined
}

/**
 * parseGitLog(text) -> [{sha, subject, body, files:[]}] oldest-first.
 * Parses `git log --reverse --topo-order --format=%H%x1f%s%x1f%b%x1e --name-only <range>`
 * output. --name-only prints each commit's changed-file list AFTER that commit's format
 * block, so splitting on the record separator (\x1e) alone puts commit N's file list at the
 * START of the (N+1)-th chunk, ahead of commit N+1's own %H%x1f header — attributing files to
 * the wrong commit if read naively. \x1f (unit separator) cannot occur in a real subject/body/
 * path, so the FIRST '<40 lowercase hex>\x1f' match in a chunk unambiguously locates the next
 * commit's header; everything before it in that chunk is the PRIOR commit's file list.
 */
export function parseGitLog(text) {
  if (!text) return []
  const HEADER_RE = /([0-9a-f]{40})\x1f([^\x1f]*)\x1f([\s\S]*)/
  const records = String(text).split('\x1e')
  const commits = []

  const attachFiles = (commit, filesText) => {
    if (!commit || !filesText) return
    const files = filesText.split('\n').map((s) => s.trim()).filter(Boolean)
    if (files.length > 0) commit.files = commit.files.concat(files)
  }

  for (let i = 0; i < records.length; i++) {
    const record = records[i]
    if (i === 0) {
      const m = HEADER_RE.exec(record)
      if (m) commits.push({ sha: m[1], subject: m[2], body: m[3], files: [] })
      continue
    }
    const m = HEADER_RE.exec(record)
    if (m) {
      const filesText = record.slice(0, m.index)
      attachFiles(commits[commits.length - 1], filesText)
      commits.push({ sha: m[1], subject: m[2], body: m[3], files: [] })
    } else {
      // Trailing record: no embedded header (end of the range) — the whole thing is the LAST
      // parsed commit's file list.
      attachFiles(commits[commits.length - 1], record)
    }
  }

  return commits
}

/**
 * stageCommitRange(itemCommits, pre, post) -> {commits, startIdx, endIdx}.
 * itemCommits is this item's commits, oldest-first. A stage's own commits are every commit in
 * (pre..post] — strictly after `pre` (the PRIOR stage's last commit, never this stage's own),
 * up to and including `post`. `pre` is used to widen the range backward ONLY when it resolves
 * to a real commit in itemCommits (the normal case: it equals the prior writing stage's real
 * `post`) — when `pre` is absent or names something outside itemCommits (this item's baseSha,
 * a placeholder, or anything else this function cannot resolve), the range defaults to JUST
 * the `post` commit itself rather than reaching all the way back to index 0, so one stage's
 * range can never swallow an EARLIER stage's real commits just because `pre` didn't resolve.
 * `post` falsy or not found -> no commits attributed.
 */
function stageCommitRange(itemCommits, pre, post) {
  if (!post) return { commits: [], startIdx: -1, endIdx: -1 }
  const postIdx = itemCommits.findIndex((c) => c.sha === post)
  if (postIdx === -1) return { commits: [], startIdx: -1, endIdx: -1 }
  let startIdx = postIdx
  if (pre) {
    const preIdx = itemCommits.findIndex((c) => c.sha === pre)
    if (preIdx !== -1) startIdx = preIdx + 1
  }
  if (startIdx > postIdx) startIdx = postIdx
  return { commits: itemCommits.slice(startIdx, postIdx + 1), startIdx, endIdx: postIdx }
}

/**
 * verify(doc, result, gitFacts) -> {ok, items:[{id, short, status, ok, findings, redProof}], warnings}.
 * gitFacts = {exists:{[sha]:bool}, commits:[{sha, subject, body, files[]}]} oldest-first. A
 * commit belongs to an item by the '[<short>]' tag in its subject; untagged -> a run-level
 * warning. Findings: missing sha, missing Seat trailer, unowned files written by the
 * implementer/test-author, test-author committing before the implementer's last commit.
 */
export function verify(doc, result, gitFacts) {
  const project = (doc.args && doc.args.project) || {}
  const warnings = []
  const commitsByShort = new Map()

  for (const c of (gitFacts && gitFacts.commits) || []) {
    const m = /\[([0-9a-f]{8})\]/.exec(c.subject || '')
    if (!m) {
      warnings.push(`untagged commit ${c.sha.slice(0, 7)}`)
      continue
    }
    const short = m[1]
    if (!commitsByShort.has(short)) commitsByShort.set(short, [])
    commitsByShort.get(short).push(c)
  }

  const resultByItemId = new Map((result.items || []).map((r) => [r.id, r]))
  const items = doc.args.items.map((argItem) => {
    const resItem = resultByItemId.get(argItem.id) || { stages: [], outputs: {} }
    const findings = []
    const itemCommits = commitsByShort.get(argItem.short) || []

    const plannerOut = outputForStageId(resItem, argItem, 'planner-v1')
    const mainFiles = new Set(((plannerOut && plannerOut.mainFiles) || []).map(normalizePath))
    const docFiles = new Set(((plannerOut && plannerOut.docFiles) || []).map(normalizePath))
    const testFiles = new Set(((plannerOut && plannerOut.testFiles) || []).map(normalizePath))
    const existingTestEdits = new Set(
      ((plannerOut && plannerOut.existingTestEdits) || []).map((e) => normalizePath(e.file))
    )

    const rangeByStageSeat = new Map()
    for (const stage of argItem.stages) {
      const stageRes = (resItem.stages || []).find((s) => s.seat === stage.seat)
      if (!stageRes) continue
      const pre = stageRes.commits && stageRes.commits.pre
      const post = stageRes.commits && stageRes.commits.post
      for (const sha of [pre, post]) {
        if (sha && (!gitFacts.exists || gitFacts.exists[sha] !== true)) {
          findings.push(`missing sha ${sha}`)
        }
      }
      if (!stage.writes) continue
      const range = stageCommitRange(itemCommits, pre, post)
      rangeByStageSeat.set(stage.seat, range)
      for (const commit of range.commits) {
        if (!(commit.body || '').includes(`Seat: ${stage.seat}`)) {
          findings.push(`missing Seat trailer ${commit.sha.slice(0, 7)}`)
        }
        const files = (commit.files || []).map(normalizePath)
        if (stage.output === 'implementer-v1') {
          for (const f of files) {
            if (!mainFiles.has(f) && !docFiles.has(f)) findings.push(`implementer wrote unowned ${f}`)
          }
        } else if (stage.output === 'test-author-v1') {
          for (const f of files) {
            if (!testFiles.has(f) && !existingTestEdits.has(f)) findings.push(`test-author wrote unowned ${f}`)
          }
        }
      }
    }

    const implStage = argItem.stages.find((s) => s.output === 'implementer-v1')
    const authorStage = argItem.stages.find((s) => s.output === 'test-author-v1')
    if (implStage && authorStage) {
      const implRange = rangeByStageSeat.get(implStage.seat)
      const authorRange = rangeByStageSeat.get(authorStage.seat)
      if (implRange && authorRange && implRange.commits.length > 0 && authorRange.commits.length > 0 &&
          authorRange.startIdx <= implRange.endIdx) {
        findings.push(`test-author commit ${authorRange.commits[0].sha.slice(0, 7)} precedes implementer`)
      }
    }

    const verifyCommands = (project.verify || [])
      .filter((v) => Array.isArray(v.seats) && v.seats.includes('orchestrator'))
      .map((v) => ({ name: v.name, command: v.command }))

    const status = plannerOut ? resItem.status || 'unknown' : 'skipped: no planner output'

    return {
      id: argItem.id,
      short: argItem.short,
      status,
      ok: findings.length === 0,
      findings,
      redProof: {
        shape: (plannerOut && plannerOut.redProofShape) || null,
        commands: verifyCommands,
      },
    }
  })

  return { ok: items.every((it) => it.ok), items, warnings }
}

/**
 * expectedActors(doc) -> [{itemId, key, actorId:'<seat>:<short>:<runId>'}].
 * args/stage/note order; inserted stages carry no notes (nothing to yield); orchestratorNotes
 * are not stage-owned so they never appear here.
 */
export function expectedActors(doc) {
  const out = []
  for (const item of doc.args.items) {
    for (const stage of item.stages) {
      for (const key of stage.notes || []) {
        out.push({ itemId: item.id, key, actorId: `${stage.seat}:${item.short}:${doc.args.runId}` })
      }
    }
  }
  return out
}

/**
 * auditActors(doc, observed, itemIds?) -> {ok, items:[{itemId, short, ok, missing, mismatched}]}.
 * observed = [{itemId, key, actorId}] (e.g. from query_notes).
 */
export function auditActors(doc, observed, itemIds) {
  const expected = expectedActors(doc)
  const filter = itemIds ? new Set(itemIds) : null
  const items = doc.args.items
    .filter((item) => !filter || filter.has(item.id) || filter.has(item.short))
    .map((item) => {
      const expectedForItem = expected.filter((e) => e.itemId === item.id)
      const observedForItem = (observed || []).filter((o) => o.itemId === item.id)
      const missing = []
      const mismatched = []
      for (const e of expectedForItem) {
        const found = observedForItem.find((o) => o.key === e.key)
        if (!found) missing.push(e.key)
        else if (found.actorId !== e.actorId) mismatched.push({ key: e.key, expected: e.actorId, actual: found.actorId })
      }
      return { itemId: item.id, short: item.short, ok: missing.length === 0 && mismatched.length === 0, missing, mismatched }
    })
  return { ok: items.every((it) => it.ok), items }
}

/**
 * provenance({core, doc, result, method:'A'|'B', turns, usage?, meta?, journal?}) -> {[itemId]: line}.
 * seats = result stages in order; model per seat from meta['<seat>:<short>'].model, else the
 * stage's modelReported, else its dispatch.model, else 'unknown'. model = the entry seat's
 * model (else the first work-stage seat's). isolation = 'worktree:<item.worktree>'. agents =
 * stage count. tokens/duration from usage.items[short] -> run-level usage -> 'unknown'.
 * deferred = result.deferred.length + args.deferred.length. in-run-edges =
 * doc.meta.inRunEdges.length. orchestrator-turns = turns. substituted lists seats whose meta
 * model differs from stage.dispatch.model. model-source is 'meta' only when every seat's
 * model came from meta, else 'self-report'.
 */
export function provenance({ core, doc, result, method, turns, usage, meta, journal }) {
  const out = {}
  const resultByItemId = new Map((result.items || []).map((r) => [r.id, r]))

  for (const item of doc.args.items) {
    const resItem = resultByItemId.get(item.id)
    const resultStages = (resItem && resItem.stages && resItem.stages.length > 0) ? resItem.stages : item.stages
    const seats = []
    const substituted = []
    let anyFromMeta = false
    let anySelfReport = false

    for (const s of resultStages) {
      const seat = s.seat
      const stageDef = item.stages.find((st) => st.seat === seat) || {}
      const metaEntry = meta && meta[`${seat}:${item.short}`]
      let model
      if (metaEntry && metaEntry.model) {
        model = metaEntry.model
        anyFromMeta = true
      } else if (s.modelReported) {
        model = s.modelReported
        anySelfReport = true
      } else {
        model = (stageDef.dispatch && stageDef.dispatch.model) || 'unknown'
        anySelfReport = true
      }
      seats.push({ seat, model })
      const requested = stageDef.dispatch && stageDef.dispatch.model
      if (requested && model !== requested) substituted.push({ seat, requested })
    }

    const enterStage = item.stages.find((s) => s.enters)
    const firstWorkStage = item.stages.find((s) => s.phase === 'work')
    const entrySeatOut = enterStage && seats.find((s) => s.seat === enterStage.seat)
    const firstWorkOut = firstWorkStage && seats.find((s) => s.seat === firstWorkStage.seat)
    const modelField = (entrySeatOut && entrySeatOut.model) || (firstWorkOut && firstWorkOut.model) || 'unknown'

    const usageItem = usage && usage.items && usage.items[item.short]
    const tokens = usageItem && usageItem.tokens !== undefined ? usageItem.tokens : (usage && usage.tokens !== undefined ? usage.tokens : 'unknown')
    const duration = usageItem && usageItem.duration !== undefined ? usageItem.duration : (usage && usage.duration !== undefined ? usage.duration : 'unknown')

    const deferredCount = ((result.deferred && result.deferred.length) || 0) + ((doc.args.deferred && doc.args.deferred.length) || 0)
    const inRunEdges = (doc.meta && Array.isArray(doc.meta.inRunEdges)) ? doc.meta.inRunEdges.length : 0

    const fields = {
      adapter: method === 'A' ? 'claude-workflow' : 'claude-agent',
      run: doc.args.runId,
      seats,
      model: modelField,
      isolation: `worktree:${item.worktree}`,
      agents: resultStages.length,
      tokens,
      duration,
      deferred: deferredCount,
      inRunEdges,
      orchestratorTurns: turns,
    }
    if (substituted.length > 0) fields.substituted = substituted
    fields.modelSource = anyFromMeta && !anySelfReport ? 'meta' : 'self-report'
    if (journal) fields.journal = journal

    out[item.id] = formatProvenance(fields)
  }

  return out
}

/**
 * reviewPrompt(core, doc, idOrShort, result?) -> text joined by '\n\n'.
 * Seat line, the owned-file diff command (planner output's mainFiles+docFiles+testFiles as
 * the pathspec when a result is given, else a bare diff noting 'owned files: planner
 * output'), a line to fill the review-phase notes, REVIEW_RULES by key (no rule text), and
 * the reviewer actor.
 */
export function reviewPrompt(core, doc, idOrShort, result) {
  const { plan } = resolvePlan(core, doc)
  const item = findItem(plan, idOrShort)
  const parts = []

  parts.push(`SEAT: reviewer for item ${item.id} (${item.short}) "${item.title || item.short}". Run ${plan.runId}.`)

  if (result) {
    const resItem = (result.items || []).find((r) => r.id === item.id)
    const plannerOut = resItem ? outputForStageId(resItem, item, 'planner-v1') : null
    if (plannerOut) {
      const owned = [].concat(plannerOut.mainFiles || [], plannerOut.docFiles || [], plannerOut.testFiles || [])
      const pathspec = owned.length > 0 ? ` -- ${owned.join(' ')}` : ''
      parts.push(`git -C ${item.worktree} diff ${plan.baseSha}..HEAD${pathspec}`)
    } else {
      parts.push(`git -C ${item.worktree} diff ${plan.baseSha}..HEAD (owned files: planner output)`)
    }
  } else {
    parts.push(`git -C ${item.worktree} diff ${plan.baseSha}..HEAD (no result — owned files: planner output)`)
  }

  parts.push(
    'Fill this item\'s review-phase notes (get_context expectedNotes, role review). ' +
    'Pass keys= on every query_notes call.'
  )

  parts.push(
    `RULES: fetch each key below via query_rules(operation:"get", rootId:"${plan.rootId}", key:<key>) ` +
    `and follow the returned body. Keys: ${REVIEW_RULES.join(', ')}.`
  )

  const actor = { id: `reviewer:${item.short}:${plan.runId}`, kind: 'subagent', parent: `workflow:${plan.runId}` }
  parts.push(`ACTOR: ${JSON.stringify(actor)}`)

  return parts.join('\n\n')
}

// ---------------------------------------------------------------------------------------
// CLI command table. Each handler is (argv, io) with
// io = {readInput, readText, writeOut, fail, exit, git(cwd, args) -> {status, stdout}, loadCore}
// injected by run-planner.mjs. No fs/process/git identifier appears in this file directly —
// only calls through the injected io object.
// ---------------------------------------------------------------------------------------

function flagValue(argv, name, def) {
  const idx = argv.indexOf(name)
  if (idx !== -1 && argv[idx + 1] !== undefined) return argv[idx + 1]
  return def
}

/**
 * readStateOrFail(doc, argv, io) -> state | undefined (calls io.fail and returns undefined
 * when the state file's runId/contract disagrees with doc.args.runId/STATE_CONTRACT).
 * Runs checkState against the RAW file content (never the initState default, which always
 * agrees by construction) so a stale or foreign state file is caught before use. An absent
 * OR EMPTY ({}) state file reads as initState(doc) — {} is a truthy object, so it must be
 * checked for zero keys explicitly rather than relying on `raw || initState(doc)` (O5).
 */
function readStateOrFail(doc, argv, io) {
  const raw = io.readInput(flagValue(argv, '--state'))
  const isEmpty = !raw || Object.keys(raw).length === 0
  if (raw && !isEmpty) {
    const checked = checkState(doc, raw)
    if (!checked.ok) {
      io.fail(2, 'invalid args', checked.error)
      return undefined
    }
  }
  return isEmpty ? initState(doc) : raw
}

function execNext(argv, io) {
  const doc = io.readInput(flagValue(argv, '--plan'))
  const state = readStateOrFail(doc, argv, io)
  if (state === undefined) return
  const core = io.loadCore()
  const result = next(core, doc, state)
  io.writeOut(JSON.stringify(result))
  io.exit(0)
}

function execPrompt(argv, io) {
  const doc = io.readInput(flagValue(argv, '--plan'))
  const state = readStateOrFail(doc, argv, io)
  if (state === undefined) return
  const core = io.loadCore()
  const text = prompt(core, doc, state, flagValue(argv, '--item'), flagValue(argv, '--seat'))
  io.writeOut(text)
  io.exit(0)
}

function execStageResult(argv, io) {
  const doc = io.readInput(flagValue(argv, '--plan'))
  const state = readStateOrFail(doc, argv, io)
  if (state === undefined) return
  const core = io.loadCore()
  const envelopeText = io.readText(flagValue(argv, '--envelope'))
  const opts = {
    agentTypeUsed: flagValue(argv, '--agent-type', null),
    agentTypeFallback: argv.includes('--agent-type-fallback'),
  }
  const result = stageResult(core, doc, state, flagValue(argv, '--item'), flagValue(argv, '--seat'), envelopeText, opts)
  io.writeOut(JSON.stringify(result))
  io.exit(0)
}

function execScanDeclarations(argv, io) {
  const core = io.loadCore()
  const text = io.readText(flagValue(argv, '--in'))
  const result = scanReport(core, text)
  io.writeOut(JSON.stringify(result))
  io.exit(0)
}

/** resolveResultDoc(core, doc, resultDoc) -> result-v1 (converts a run-wave/state-v1 --result via resultFromState — B5). */
function resolveResultDoc(core, doc, resultDoc) {
  if (resultDoc && resultDoc.contract === STATE_CONTRACT) {
    return resultFromState(core, doc, resultDoc)
  }
  return resultDoc
}

function execVerify(argv, io) {
  const doc = io.readInput(flagValue(argv, '--plan'))
  const resultDoc = io.readInput(flagValue(argv, '--result'))
  const core = io.loadCore()
  const result = resolveResultDoc(core, doc, resultDoc)
  const worktrees = Array.from(new Set(doc.args.items.map((it) => it.worktree)))
  const exists = {}
  const commits = []
  for (const wt of worktrees) {
    for (const item of doc.args.items) {
      if (item.worktree !== wt) continue
      for (const stage of item.stages) {
        const resItem = (result.items || []).find((r) => r.id === item.id)
        const stageRes = resItem && (resItem.stages || []).find((s) => s.seat === stage.seat)
        for (const sha of [stageRes && stageRes.commits && stageRes.commits.pre, stageRes && stageRes.commits && stageRes.commits.post]) {
          if (sha && !(sha in exists)) {
            const r = io.git(wt, ['cat-file', '-e', sha])
            exists[sha] = r.status === 0
          }
        }
      }
    }
    const log = io.git(wt, ['log', '--reverse', '--topo-order', '--format=%H%x1f%s%x1f%b%x1e', '--name-only', `${doc.args.baseSha}..HEAD`])
    commits.push(...parseGitLog(log.stdout))
  }
  const gitFacts = { exists, commits }
  const out = verify(doc, result, gitFacts)
  io.writeOut(JSON.stringify(out))
  io.exit(out.ok ? 0 : 3)
}

function execActors(argv, io) {
  const doc = io.readInput(flagValue(argv, '--plan'))
  const notesPath = flagValue(argv, '--notes')
  if (!notesPath) {
    io.writeOut(JSON.stringify(expectedActors(doc)))
    io.exit(0)
    return
  }
  const observed = io.readInput(notesPath)
  const itemsFlag = flagValue(argv, '--items')
  const itemIds = itemsFlag ? itemsFlag.split(',') : undefined
  const result = auditActors(doc, observed, itemIds)
  io.writeOut(JSON.stringify(result))
  io.exit(result.ok ? 0 : 3)
}

function execProvenance(argv, io) {
  const doc = io.readInput(flagValue(argv, '--plan'))
  const resultDoc = io.readInput(flagValue(argv, '--result'))
  const core = io.loadCore()
  const result = resolveResultDoc(core, doc, resultDoc)
  const usagePath = flagValue(argv, '--usage')
  const usage = usagePath ? io.readInput(usagePath) : undefined
  const metaDir = flagValue(argv, '--meta-dir')
  let meta
  if (metaDir) {
    // O3: '<seat>:<short>.meta.json' is not a legal filename on NTFS (':' is reserved), so the
    // dir is listed and each *.meta.json file's own `label` field (frozen shape
    // {label:'<seat>:<short>', model}) supplies the key instead of the filename.
    meta = {}
    const names = (io.listDir ? io.listDir(metaDir) : []).filter((n) => n.endsWith('.meta.json'))
    for (const name of names) {
      const p = `${metaDir}/${name}`.replace(/\\/g, '/')
      const parsed = io.readInput(p)
      if (parsed && parsed.label) meta[parsed.label] = parsed
    }
  }
  const out = provenance({
    core, doc, result,
    method: flagValue(argv, '--method'),
    turns: Number(flagValue(argv, '--turns', '0')),
    usage, meta,
    journal: flagValue(argv, '--journal'),
  })
  io.writeOut(JSON.stringify(out))
  io.exit(0)
}

function execReviewPrompt(argv, io) {
  const doc = io.readInput(flagValue(argv, '--plan'))
  const core = io.loadCore()
  const resultPath = flagValue(argv, '--result')
  const result = resultPath ? io.readInput(resultPath) : undefined
  const text = reviewPrompt(core, doc, flagValue(argv, '--item'), result)
  io.writeOut(text)
  io.exit(0)
}

export const EXEC_COMMANDS = {
  next: execNext,
  prompt: execPrompt,
  'stage-result': execStageResult,
  'scan-declarations': execScanDeclarations,
  verify: execVerify,
  actors: execActors,
  provenance: execProvenance,
  'review-prompt': execReviewPrompt,
}
