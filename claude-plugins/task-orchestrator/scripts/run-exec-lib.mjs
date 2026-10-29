// run-exec-lib.mjs — Method B scheduling ("next"), prompt rendering, envelope parsing/
// mapping, the declarations scan, post-run git verification, the notes audit, the provenance
// line, and the server-run ops the orchestrator sends (runOps, closeReason, advanceFollowUps) —
// over the SAME run plan Method A (workflows/implement-wave.js) runs.
//
// Pure except EXEC_COMMANDS' handlers, which call the injected `io` (readInput/readText/
// writeOut/fail/exit/git/loadCore) rather than fs/process/git directly — this file itself
// never references those globals. Every function below takes the loaded core
// (scripts/lib/wave-core.mjs) as an injected first parameter where it needs core behaviour;
// pure helpers (validateAgainst, scanReport's word list, verify, notesAudit, provenance,
// closeReason, advanceFollowUps) take no core because they don't call into the @core-begin region.
//
// No function here renders a seat token or a coordinator token: the orchestrator holds them,
// writes seat tokens to the token files runOps names, and adds the right token as the top-level
// seatToken of each call it sends. The stage-result handler reads the run's token files solely
// to redact them (with any seatToken/coordinatorToken key) from the state it prints; nothing
// renders them.

import { extractLastBalancedJson } from './ralph-lib.mjs'
import { formatProvenance, formatExtraSeats, parseExtraSeats } from './provenance-lib.mjs'
import { formatCoordinatorReport, REPORT_EVENTS } from './lib/coordinator-report.mjs'

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
// Ownership matching (verify): root-relative normalization, directory and glob ownership.
// ---------------------------------------------------------------------------------------

const HEX_SHA_RE = /^[0-9a-f]{7,40}$/

/**
 * relativizePath(p, roots) -> canonical repo-relative POSIX path ('' = the whole worktree).
 * Canonicalization (applied to p and to every root):
 *   1. every '\' becomes '/';
 *   2. an absolute prefix is kept ('/' or a drive 'x:/'); the remainder is split on '/', empty
 *      segments (from '//' runs, leading/trailing '/') and '.' segments are dropped, and '..'
 *      segments are kept verbatim (never resolved); the pieces are rejoined with '/';
 *   3. a drive letter is lowercased (normalizePath).
 *   So '.', './', './/' and '' -> ''; 'src/.' -> 'src'; 'src/./x.js' and 'src//x.js' ->
 *   'src/x.js'; '././x' -> 'x'; '//h/s/x' -> '/h/s/x'; 'src/../x' is unchanged; the filesystem
 *   roots '/' and 'x:/' stay '/' and 'x:/' (absolute, never '' or 'x:').
 * Root stripping: falsy roots and roots that canonicalize to '' or to a filesystem root ('/' or
 * 'x:/') are ignored. Roots are tried longest first; the comparison is case-insensitive when the
 * canonical path is a drive-letter path, exact otherwise. A path equal to a root returns ''; a
 * path under a root ('<root>/...') returns the remainder after '<root>/'. The first match wins.
 * A path under no root is returned in canonical form (an absolute path stays absolute, so callers
 * treat it as unrooted).
 * Duplicated from implement-wave.js's core relativizePath (verify has no core parameter); a
 * parity test pins the two copies together.
 */
export function relativizePath(p, roots) {
  const canon = (x) => {
    const t = String(x).replace(/\\/g, '/')
    const m = /^[a-zA-Z]:\//.exec(t) || /^\//.exec(t)
    const prefix = m ? m[0] : ''
    const segs = t.slice(prefix.length).split('/').filter((g) => g !== '' && g !== '.')
    return normalizePath(prefix + segs.join('/'))
  }
  const s = canon(p)
  const normRoots = (roots || [])
    .filter((r) => r)
    .map(canon)
    .filter((r) => r && !r.endsWith('/'))
    .sort((a, b) => b.length - a.length)
  const drive = /^[a-z]:\//i.test(s)
  const fold = (x) => (drive ? x.toLowerCase() : x)
  for (const r of normRoots) {
    const fs = fold(s)
    const fr = fold(r)
    if (fs === fr) return ''
    if (fs.startsWith(fr + '/')) return s.slice(r.length + 1)
  }
  return s
}

function isGlob(entry) {
  return entry.includes('*') || entry.includes('?')
}

/** The roots an item's declared paths are relativized against: its worktree, then args.repoRoot. */
function itemRootsOf(plan, item) {
  return [item && item.worktree, plan && plan.repoRoot].filter((r) => typeof r === 'string' && r)
}

/** Empty, still absolute, or containing a '..' segment: not a repo-relative location. */
function isUnrooted(rel) {
  return !rel || /^\//.test(rel) || /^[a-z]:\//i.test(rel) || rel.split('/').includes('..')
}

/** Double-quotes a pathspec for a POSIX shell, escaping the characters still live inside quotes. */
function shellQuote(s) {
  return '"' + s.replace(/(["\\$`])/g, '\\$1') + '"'
}

/** globToRegExp: '**' = any number of segments, '*' = within one segment, '?' = one non-'/' char. */
function globToRegExp(glob) {
  let re = ''
  for (let i = 0; i < glob.length; i++) {
    const ch = glob[i]
    if (ch === '*') {
      if (glob[i + 1] === '*') {
        i++
        if (glob[i + 1] === '/') {
          i++
          re += '(?:.*/)?'
        } else {
          re += '.*'
        }
      } else {
        re += '[^/]*'
      }
    } else if (ch === '?') {
      re += '[^/]'
    } else {
      re += ch.replace(/[.+^${}()|[\]\\]/g, '\\$&')
    }
  }
  return new RegExp(`^${re}$`)
}

/** makeOwner(entries, roots) -> {exact:Set, owns(f), ownsExact(f)}; entries are raw declared paths. */
function makeOwner(entries, roots) {
  const exact = new Set()
  const globs = []
  for (const raw of entries) {
    const e = relativizePath(raw, roots)
    if (!e) continue
    if (isGlob(e)) globs.push(globToRegExp(e))
    else exact.add(e)
  }
  return {
    exact,
    ownsExact: (f) => exact.has(f),
    owns(f) {
      if (exact.has(f)) return true
      for (const d of exact) if (f.startsWith(d + '/')) return true
      return globs.some((g) => g.test(f))
    },
  }
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
        return { short: earlier.short, output, roots: itemRootsOf(plan, earlier) }
      })
      const mine = { short: item.short, output: outsBySeat(item, state)[plannerStage.seat], roots: itemRootsOf(plan, item) }
      const overlap = core.overlapDeferral(mine, higher, plan.worktreeMode, (plan.project && plan.project.appendOnlyFiles) || [])
      if (overlap) {
        settled.push({ item: item.short, status: 'deferred', reason: overlap.reason })
        continue
      }
    }

    // declaration coverage: a test-author dispatch naming a module the extractor never covered is refused
    const gap = core.declarationGap(plan, item, stage, outsBySeat(item, state))
    if (gap) {
      settled.push({ item: item.short, status: gap.status, reason: gap.reason })
      continue
    }

    // lock gating
    const keys = core.lockKeysFor(item, stage, outsBySeat(item, state), plan)
    // D3: a key contends with any held key it may overlap (directory/glob bases, worktree: keys).
    const held = [...inFlightLockKeys, ...chosenLockKeys]
    const conflict = keys.find((k) => held.some((h) => core.lockKeysConflict(h, k)))
    if (conflict) {
      waiting.push({ item: item.short, seat: stage.seat, on: `lock ${conflict}` })
      continue
    }
    for (const k of keys) chosenLockKeys.add(k)

    dispatch.push({
      item: item.short,
      seat: stage.seat,
      model: stage.dispatch.model,
      agentType: stage.dispatch.agent ?? (isMethodB(doc) && stage.output === 'test-author-v1' ? 'task-orchestrator:test-author' : null),
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
  const base = core.seatPrompt(plan, item, stage, outsBySeat(item, state))
  return isMethodB(doc) ? `${base}\n${methodBReturn(core, plan, stage)}` : base
}

/** Method B plans are marked by doc.meta.method (written by run-planner-lib); S6-style docs have meta:{}. */
function isMethodB(doc) {
  return !!(doc && doc.meta && doc.meta.method === 'B')
}

/**
 * Method B RETURN addendum: no StructuredOutput tool exists for a dispatched Agent, so the envelope
 * is the last JSON object of the final message; the exact schema is inlined. The core is untouched,
 * so planner stages get an explicit line superseding the core's StructuredOutput sentence.
 */
function methodBReturn(core, plan, stage) {
  const lines = [
    'METHOD B RETURN: there is no StructuredOutput tool in this run. Return the envelope as the LAST JSON object of your final message (plain JSON, no code fence required; nothing after it).',
  ]
  if (stage.output === 'planner-v1') {
    lines.push('This supersedes the earlier instruction to emit StructuredOutput: do not call StructuredOutput.')
  }
  lines.push(`Envelope JSON schema: ${JSON.stringify(core.envelopeSchema(stage.output, plan.outputSchemas, { enters: stage.enters }))}`)
  return lines.join('\n')
}

// ---------------------------------------------------------------------------------------
// reseat - out-of-run seats (breach replacements, -a<n> fix cycles)
// ---------------------------------------------------------------------------------------

const RESEAT_SEAT_RE = /^(.+)-a([1-9][0-9]*)$/

function isAbsentDeclaration(text) {
  if (typeof text !== 'string') return true
  const t = text.trim()
  return t === '' || t.toLowerCase() === 'none'
}

/**
 * reseat(core, doc, result, idOrShort, seat, opts) ->
 *   {ok:true, prompt, seat, baseSeat, cycle, tokenFile, result} | {ok:false, error}.
 * seat = '<stageSeat>-a<n>' (n >= 1, no leading zero; the base must be a non-reviewer stage seat
 * of the item). The prompt is core.seatPrompt over a clone of the base stage renamed to `seat`
 * with enters:false, outs from the result item's outputs (keyed by base seat), plus a FIX CYCLE
 * line (and the Method B addendum on Method B plans). opts = {declarations?, model?, tokens?, pre?,
 * assignment?}. On a bound run the reseat runs under the BASE seat's server assignment (the
 * orchestrator re-assigns the base seat first, which supersedes it as attempt n+1, and writes the new
 * token to the base seat's token file); the prompt names that file (tokenFile, null when unbound).
 * `result` in the return is a copy with items[i].reseats upserted {seat, baseSeat, cycle, model,
 * tokens, pre, assignment?}: model/tokens/assignment update only when passed, pre never moves; the
 * input is not mutated. Throws 'invalid args: ...' for argument errors; returns {ok:false} for a
 * missing declaration.
 */
export function reseat(core, doc, result, idOrShort, seat, opts = {}) {
  const m = RESEAT_SEAT_RE.exec(String(seat || ''))
  if (!m) throw new Error(`invalid args: seat ${seat} must be <stageSeat>-a<n> with n >= 1`)
  const baseSeat = m[1]
  const cycle = Number(m[2])
  const { plan } = resolvePlan(core, doc)
  let item
  try {
    item = findItem(plan, idOrShort)
  } catch (e) {
    throw new Error(`invalid args: ${e.message}`)
  }
  const baseStage = item.stages.find((s) => s.seat === baseSeat)
  if (!baseStage) throw new Error(`invalid args: unknown base seat ${baseSeat} for ${item.short}`)
  if (!result || !Array.isArray(result.items)) throw new Error('invalid args: result required')
  const resItem = result.items.find((r) => r.id === item.id)
  if (!resItem) throw new Error(`invalid args: no result for item ${item.short}`)

  const outs = Object.assign({}, resItem.outputs || {})
  const declStage = item.stages.find((s) => s.output === 'declarations-v1')
  if (opts.declarations !== undefined && opts.declarations !== null) {
    if (baseStage.output !== 'test-author-v1') {
      throw new Error('invalid args: --declarations is valid only for a test-author base seat')
    }
    if (!declStage) {
      throw new Error('invalid args: --declarations is only valid for a test-author base with a declarations stage')
    }
    if (!isAbsentDeclaration(opts.declarations)) {
      outs[declStage.seat] = Object.assign({}, outs[declStage.seat] || {}, { declarations: opts.declarations })
    }
  }
  if (baseStage.output === 'test-author-v1' && declStage) {
    const d = outs[declStage.seat]
    if (!d || isAbsentDeclaration(d.declarations)) {
      return { ok: false, error: `missingDeclaration: ${seat} has no declarations` }
    }
  }

  const stage = Object.assign({}, baseStage, { seat, enters: false })
  // The server knows only the base seat: the fix cycle reads the base seat's token file.
  const tokenFile = core.seatTokenFile(plan, item, baseStage)
  const promptPlan = tokenFile ? Object.assign({}, plan, { serverRunId: null }) : plan
  let text = core.seatPrompt(promptPlan, item, stage, outs)
  if (tokenFile) text += `\n${reseatTokenPart(plan, baseStage, tokenFile)}`
  text += `\nFIX CYCLE A${cycle}: work only section "## Amendment A${cycle}" of the item's planning note and the blocking findings it cites.`
  if (isMethodB(doc)) text += `\n${methodBReturn(core, plan, stage)}`

  const baseDispatch = baseStage.dispatch && baseStage.dispatch.model
  const out = JSON.parse(JSON.stringify(result))
  const outItem = out.items.find((r) => r.id === item.id)
  const ledger = Array.isArray(outItem.reseats) ? outItem.reseats : []
  let entry = ledger.find((e) => e.seat === seat)
  if (!entry) {
    entry = {
      seat, baseSeat, cycle,
      model: opts.model || baseDispatch || 'unknown',
      tokens: opts.tokens !== undefined && opts.tokens !== null ? opts.tokens : 'unknown',
      pre: opts.pre || '',
    }
    if (opts.assignment) entry.assignment = opts.assignment
    ledger.push(entry)
  } else {
    if (opts.model) entry.model = opts.model
    if (opts.tokens !== undefined && opts.tokens !== null) entry.tokens = opts.tokens
    if (opts.assignment) entry.assignment = opts.assignment
  }
  outItem.reseats = ledger

  return { ok: true, prompt: text, seat, baseSeat, cycle, tokenFile: tokenFile || null, result: out }
}

/**
 * The SEAT TOKEN, DEVIATION and END SEAT parts of a fix-cycle seat on a bound run. The fix cycle is
 * not a schema seat, so it runs under the base seat's fresh assignment and token file.
 */
function reseatTokenPart(plan, baseStage, tokenFile) {
  return [
    `SEAT TOKEN: run ${plan.serverRunId}. This fix cycle runs under the ${baseStage.seat} seat's fresh assignment: your seat token is the single line in the file ${tokenFile}. ` +
      'Read it with your file tool, trim it, and pass it as the top-level seatToken field of every task-orchestrator call you make, reads included. ' +
      'Never print, quote, echo, log or commit the token or the file content. A call refused unauthenticated ("The seat token is not accepted.") means ' +
      'this assignment was superseded or ended: stop at once and return status "stopped", reason "seat-token-rejected".',
    'END SEAT: load runs_write too (ToolSearch select:mcp__mcp-task-orchestrator__runs_write). As your last task-orchestrator call, end the seat with your seat token: runs_write(ops:[{op:"end_seat", outcome, summary}]); ' +
      'outcome "done" for status done, "failed" for stopped, "abandoned" for deferred. Set seatEnded: true in the envelope when it succeeded.',
  ].join('\n')
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
 * stageResult(core, doc, state, idOrShort, seat, finalText, {agentTypeUsed, agentTypeFallback, knownTokens}={})
 * -> {status, reason, state} (a NEW state object; input not mutated).
 * Parse via extractLastBalancedJson -> JSON.parse -> validateAgainst(envelopeSchema). Empty/
 * whitespace text and unparseable text are both treated as an invalid envelope. First
 * failure -> retry (envelopeRetries:1 recorded); second failure -> stopped 'invalid envelope'.
 * A valid envelope maps via core.mapStageResult and records outs[key] = env.output after core.stripTokens(env, opts.knownTokens) removes
 * seatToken/coordinatorToken keys and redacts every known token value.
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
    env = core.stripTokens(env, opts.knownTokens || [])
    const schema = core.envelopeSchema(stage.output, plan.outputSchemas, { enters: stage.enters })
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

  const mapped = core.mapStageResult(stage, env, plan.entryMode, item)
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
  const raised = typeof env.decisionId === 'string' && env.decisionId
    ? env.decisionId
    : env.paused && typeof env.paused.decisionId === 'string' ? env.paused.decisionId : ''
  if (raised) newState.stages[key].decisionId = raised
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
        ...(typeof entry.decisionId === 'string' && entry.decisionId ? { decisionId: entry.decisionId } : {}),
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
    serverRunId: plan.serverRunId || null,
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
 * attributeCommits(itemCommits, writingStages) -> Map<seat, {commits, indices}>.
 * itemCommits is this item's commits, oldest-first; writingStages is argItem.stages filtered
 * to stage.writes, in stage order, each `{seat, commits:{pre,post}}` (post resolved via
 * gitFacts; missing/unresolved post -> that stage gets an empty range here — the "missing sha"
 * finding is raised separately). Attribution order (frozen, B2'):
 *   (1) TRAILER: a commit whose body carries `Seat: <seat>` for one of writingStages' seats
 *       belongs to that seat's stage, regardless of position. This is a pure, position-
 *       independent scan — a commit whose trailer names a stage that structurally falls
 *       earlier or later than its neighbours is still attributed by the trailer text, which
 *       is exactly what lets the pre-existing "test-author commit <sha7> precedes
 *       implementer" check (verify(), below) fire instead of a false ownership finding: the
 *       trailer still says which seat wrote it, so ownership is correct even when the ORDER
 *       is wrong.
 *   (2) POSITIONAL (only for commits rule 1 did not already claim for ANY seat): every commit
 *       in `(preIdx, postIdx]` when this stage's `pre` resolves to a real commit in
 *       itemCommits (the normal case — it equals the prior writing stage's real `post`); else
 *       `[firstUnattributed, postIdx]`, where `firstUnattributed` is one past the highest
 *       index any EARLIER-processed writing stage claimed (0 for the first writing stage) —
 *       this is what correctly sweeps MULTIPLE untrailed/mistrailed commits made by the first
 *       writing stage before its own `pre` (the wave's baseSha, excluded from a `baseSha..HEAD`
 *       log) into that stage, rather than attributing only its final `post` commit.
 *   (3) The stage's returned `commits` is the union of (1) and (2), sorted by position;
 *       ownership/trailer-presence checks in verify() run over that union.
 */
function attributeCommits(itemCommits, writingStages) {
  const seatSet = new Set(writingStages.map((s) => s.seat))
  const trailerSeatByIdx = new Map()
  itemCommits.forEach((c, idx) => {
    for (const seat of seatSet) {
      if ((c.body || '').includes(`Seat: ${seat}`)) {
        trailerSeatByIdx.set(idx, seat)
        break
      }
    }
  })

  const attributedIdx = new Set()
  const rangeBySeat = new Map()
  let firstUnattributed = 0

  for (const stage of writingStages) {
    const post = stage.post
    if (!post) {
      rangeBySeat.set(stage.seat, { commits: [], indices: [] })
      continue
    }
    const postIdx = itemCommits.findIndex((c) => c.sha === post)
    if (postIdx === -1) {
      rangeBySeat.set(stage.seat, { commits: [], indices: [] })
      continue
    }

    let startIdx
    if (stage.pre) {
      const preIdx = itemCommits.findIndex((c) => c.sha === stage.pre)
      startIdx = preIdx !== -1 ? preIdx + 1 : firstUnattributed
    } else {
      startIdx = firstUnattributed
    }

    const idxSet = new Set()
    for (let i = 0; i < itemCommits.length; i++) {
      if (trailerSeatByIdx.get(i) === stage.seat) idxSet.add(i)
    }
    for (let i = startIdx; i <= postIdx; i++) {
      if (attributedIdx.has(i)) continue
      const trailerSeat = trailerSeatByIdx.get(i)
      if (trailerSeat !== undefined && trailerSeat !== stage.seat) continue
      idxSet.add(i)
    }

    const indices = Array.from(idxSet).sort((a, b) => a - b)
    for (const i of indices) attributedIdx.add(i)
    if (postIdx + 1 > firstUnattributed) firstUnattributed = postIdx + 1

    rangeBySeat.set(stage.seat, { commits: indices.map((i) => itemCommits[i]), indices })
  }

  return rangeBySeat
}

/**
 * verify(doc, result, gitFacts) -> {ok, items:[{id, short, status, ok, findings, redProof}], warnings}.
 * gitFacts = {exists:{[sha]:bool}, commits:[{sha, subject, body, files[]}], roots?:{[worktree]:string[]},
 * resolved?:{[worktree]:{[ref]:sha|null}}} oldest-first (roots/resolved are optional git facts). A
 * commit belongs to an item by the '[<short>]' tag in its subject; untagged -> a run-level
 * warning. Findings: missing sha, missing Seat trailer, unowned files written by the
 * implementer/test-author, a file the writer covers only by directory/glob that another item's
 * directory/glob also covers ('<who> wrote <f> also covered by item <short>'), test-author
 * committing before the implementer's last commit, a 'reseat <seat> pre <sha7> not in run history'
 * finding for a reseat ledger `pre` that is not in the run's item history. Fix-cycle boundary: the
 * test-author-before-implementer order is judged only over item commits at or before the earliest
 * reseat ledger `pre` (the ledger `pre` is trusted as recorded).
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
  const rootsFor = (argItem) => {
    const r = gitFacts && gitFacts.roots && gitFacts.roots[argItem.worktree]
    return Array.isArray(r) && r.length > 0 ? r : [argItem.worktree]
  }
  const declaredBy = (argItem) => {
    const resItem = resultByItemId.get(argItem.id) || { stages: [], outputs: {} }
    const out = outputForStageId(resItem, argItem, 'planner-v1')
    return {
      plannerOut: out,
      main: (out && out.mainFiles) || [],
      docs: (out && out.docFiles) || [],
      tests: (out && out.testFiles) || [],
      edits: ((out && out.existingTestEdits) || []).map((e) => e.file),
    }
  }
  // Cross-item precedence: exact (non-glob) declarations by every item with planner output.
  const exactDeclarers = new Map() // normalized path -> [{id, short}]
  // D6: every item's full declared set, for pattern-vs-pattern (directory/glob) coverage.
  const patternOwners = [] // [{id, short, owner}]
  for (const other of doc.args.items) {
    const d = declaredBy(other)
    if (!d.plannerOut) continue
    const roots = rootsFor(other)
    patternOwners.push({ id: other.id, short: other.short, owner: makeOwner([...d.main, ...d.docs, ...d.tests, ...d.edits], roots) })
    for (const raw of [...d.main, ...d.docs, ...d.tests, ...d.edits]) {
      const e = relativizePath(raw, roots)
      if (!e || isGlob(e)) continue
      if (!exactDeclarers.has(e)) exactDeclarers.set(e, [])
      exactDeclarers.get(e).push({ id: other.id, short: other.short })
    }
  }
  const items = doc.args.items.map((argItem) => {
    const resItem = resultByItemId.get(argItem.id) || { stages: [], outputs: {} }
    const findings = []
    const itemCommits = commitsByShort.get(argItem.short) || []
    const roots = rootsFor(argItem)

    const declared = declaredBy(argItem)
    const plannerOut = declared.plannerOut

    // D5: with no test-author-v1 stage on this item, the implementer's owned set widens to
    // include the planner's testFiles/existingTestEdits — those tests are "unowned" by anyone
    // else, so the implementer writing them is not a foreign-file finding.
    const hasTestAuthorStage = argItem.stages.some((s) => s.output === 'test-author-v1')
    const implOwner = makeOwner(
      [...declared.main, ...declared.docs, ...(hasTestAuthorStage ? [] : [...declared.tests, ...declared.edits])],
      roots
    )
    const authorOwner = makeOwner([...declared.tests, ...declared.edits], roots)
    const foreignOwner = (f, ownExactOwner) => {
      if (ownExactOwner.ownsExact(f)) return null
      const hit = (exactDeclarers.get(f) || []).find((o) => o.id !== argItem.id)
      return hit ? hit.short : null
    }
    // D6: the writer covers f only through a directory or glob, and another item's directory or
    // glob covers it too (two items' exact declarations of one file are serialized by locks instead).
    const patternCoOwner = (f, owner) => {
      if (owner.ownsExact(f) || !owner.owns(f)) return null
      const hit = patternOwners.find((o) => o.id !== argItem.id && !o.owner.ownsExact(f) && o.owner.owns(f))
      return hit ? hit.short : null
    }

    // Missing-sha check first (independent of attribution) over every declared stage. Symbolic
    // refs are replaced by their resolved SHA (gitFacts.resolved) and recorded in resolvedRefs.
    const resolvedRefs = []
    const resolvedMap = (gitFacts && gitFacts.resolved && gitFacts.resolved[argItem.worktree]) || {}
    const writingStages = []
    for (const stage of argItem.stages) {
      const stageRes = (resItem.stages || []).find((s) => s.seat === stage.seat)
      if (!stageRes) continue
      const commits = stageRes.commits || {}
      const resolvedStage = {}
      for (const field of ['pre', 'post']) {
        const ref = commits[field]
        if (!ref) {
          resolvedStage[field] = ref
          continue
        }
        if (HEX_SHA_RE.test(ref)) {
          resolvedStage[field] = ref
          if (!gitFacts.exists || gitFacts.exists[ref] !== true) findings.push(`missing sha ${ref}`)
          continue
        }
        const sha = typeof resolvedMap[ref] === 'string' ? resolvedMap[ref] : null
        if (!sha) {
          findings.push(`unresolved ref ${ref} (${stage.seat} ${field})`)
          resolvedStage[field] = undefined
          continue
        }
        resolvedStage[field] = sha
        resolvedRefs.push({ seat: stage.seat, field, ref, sha })
        if (field === 'post' && stage.writes && !itemCommits.some((c) => c.sha === sha)) {
          findings.push(`ref ${ref} resolved to ${sha.slice(0, 7)}, not a commit of this item`)
        }
      }
      if (stage.writes) writingStages.push({ seat: stage.seat, output: stage.output, pre: resolvedStage.pre, post: resolvedStage.post })
    }

    const rangeByStageSeat = attributeCommits(itemCommits, writingStages)
    for (const stage of writingStages) {
      const range = rangeByStageSeat.get(stage.seat)
      for (const commit of range.commits) {
        if (!(commit.body || '').includes(`Seat: ${stage.seat}`)) {
          findings.push(`missing Seat trailer ${commit.sha.slice(0, 7)}`)
        }
        const files = (commit.files || []).map((f) => relativizePath(f, roots))
        const who = stage.output === 'implementer-v1' ? 'implementer' : 'test-author'
        const owner = stage.output === 'implementer-v1' ? implOwner
          : stage.output === 'test-author-v1' ? authorOwner : null
        if (owner) {
          for (const f of files) {
            const foreign = foreignOwner(f, owner)
            if (foreign) findings.push(`${who} wrote ${f} owned by item ${foreign}`)
            else if (!owner.owns(f)) findings.push(`${who} wrote unowned ${f}`)
            else {
              const co = patternCoOwner(f, owner)
              if (co) findings.push(`${who} wrote ${f} also covered by item ${co}`)
            }
          }
        }
      }
    }

    // Fix-cycle boundary: the earliest reseat ledger `pre` position in the run's log. Order is
    // judged only over item commits at or before it (pre == baseSha -> before every commit).
    let boundary = Infinity
    const fullLog = (gitFacts && gitFacts.commits) || []
    for (const r of resItem.reseats || []) {
      let pos
      if (r.pre && r.pre === doc.args.baseSha) pos = -1
      else {
        const idx = r.pre ? fullLog.findIndex((c) => c.sha === r.pre) : -1
        pos = idx === -1 ? undefined : idx
      }
      if (pos === undefined) findings.push(`reseat ${r.seat} pre ${String(r.pre || '').slice(0, 7)} not in run history`)
      else if (pos < boundary) boundary = pos
    }
    const inBoundary = (c) => boundary === Infinity || fullLog.findIndex((x) => x.sha === c.sha) <= boundary

    const implStage = argItem.stages.find((s) => s.output === 'implementer-v1')
    const authorStage = argItem.stages.find((s) => s.output === 'test-author-v1')
    if (implStage && authorStage) {
      const implRange = rangeByStageSeat.get(implStage.seat)
      const authorRange = rangeByStageSeat.get(authorStage.seat)
      if (implRange && authorRange) {
        const implIdx = implRange.indices.filter((i) => inBoundary(itemCommits[i]))
        const authorIdx = authorRange.indices.filter((i) => inBoundary(itemCommits[i]))
        if (implIdx.length > 0 && authorIdx.length > 0 && authorIdx[0] <= implIdx[implIdx.length - 1]) {
          findings.push(`test-author commit ${itemCommits[authorIdx[0]].sha.slice(0, 7)} precedes implementer`)
        }
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
      resolvedRefs,
      redProof: {
        shape: (plannerOut && plannerOut.redProofShape) || null,
        commands: verifyCommands,
      },
    }
  })

  return { ok: items.every((it) => it.ok), items, warnings }
}

/**
 * expectedNotes(doc) -> [{itemId, key, seat, optional?}] in args/stage/note order. Inserted stages
 * own no notes; orchestratorNotes are not stage-owned, so they never appear here.
 */
export function expectedNotes(doc) {
  const out = []
  for (const item of doc.args.items) {
    for (const stage of item.stages) {
      const optional = new Set(stage.optionalNotes || [])
      for (const key of stage.notes || []) {
        const row = { itemId: item.id, key, seat: stage.seat }
        if (optional.has(key)) row.optional = true
        out.push(row)
      }
    }
  }
  return out
}

/**
 * notesAudit(doc, observed, itemIds?, opts?) -> {ok, items:[{itemId, short, ok, missing, skipped?}]}.
 * observed = [{itemId, key}] (the orchestrator lists each item's notes with notes_read list keys:[...]).
 * A required stage-owned note with no observed row is `missing`; an optional one never is. Who wrote a
 * note is not audited: the server enforces each seat's lane (note_owned_by_other, seat_forbidden).
 * With opts.result (the run's result document), only stages whose result status is `done` are
 * expected; an item with no result entry, or no done stage that owns a note, reports
 * {ok:true, missing:[], skipped:true}.
 */
export function notesAudit(doc, observed, itemIds, opts) {
  const filter = itemIds ? new Set(itemIds) : null
  const resultByItemId = opts && opts.result ? new Map((opts.result.items || []).map((r) => [r.id, r])) : null
  const items = doc.args.items
    .filter((item) => !filter || filter.has(item.id) || filter.has(item.short))
    .map((item) => {
      let stages = item.stages
      if (resultByItemId) {
        const resItem = resultByItemId.get(item.id)
        const doneSeats = new Set(((resItem && resItem.stages) || []).filter((s) => s.status === 'done').map((s) => s.seat))
        stages = resItem ? item.stages.filter((s) => doneSeats.has(s.seat)) : []
        if (!stages.some((s) => (s.notes || []).length > 0)) {
          return { itemId: item.id, short: item.short, ok: true, missing: [], skipped: true }
        }
      }
      const have = new Set((observed || []).filter((o) => o && o.itemId === item.id).map((o) => o.key))
      const missing = []
      for (const stage of stages) {
        const optional = new Set(stage.optionalNotes || [])
        for (const key of stage.notes || []) {
          if (!optional.has(key) && !have.has(key) && !missing.includes(key)) missing.push(key)
        }
      }
      return { itemId: item.id, short: item.short, ok: missing.length === 0, missing }
    })
  return { ok: items.every((it) => it.ok), items }
}

/**
 * provenance({core, doc, result, method:'A'|'B', turns, usage?, meta?, journal?}) -> {[itemId]: line}.
 * seats = result stages in order; model per seat from meta['<seat>:<short>'].model, else the
 * stage's modelReported, else its dispatch.model, else 'unknown'. model = the entry seat's
 * model (else the first work-stage seat's). isolation = 'worktree:<item.worktree>'. agents =
 * stage count. tokens/duration (each independently) from usage.items[short]; else run-level
 * usage, written ONLY on the run's first item (doc.args.items[0]) with every other item getting
 * 'see:<firstShort>'; else 'unknown'. Optional extraSeats=[{seat,model,tokens}] plus item
 * (short|id) restricts the output to that item and appends the 'extra-seats=...' line 2.
 * deferred = result.deferred.length + args.deferred.length. in-run-edges =
 * doc.meta.inRunEdges.length. orchestrator-turns = turns. substituted lists seats whose meta
 * model differs from stage.dispatch.model. model-source is 'meta' only when every seat's
 * model came from meta, else 'self-report'.
 */
export function provenance({ core, doc, result, method, turns, usage, meta, journal, extraSeats, item: onlyItem }) {
  const out = {}
  if (extraSeats && extraSeats.length > 0 && !onlyItem) {
    throw new Error('provenance: extraSeats requires a single item (--item)')
  }
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
    const isFirstItem = item === doc.args.items[0]
    const firstShort = doc.args.items[0].short
    const runLevel = (v) => (v === undefined ? 'unknown' : (isFirstItem ? v : `see:${firstShort}`))
    const tokens = usageItem && usageItem.tokens !== undefined ? usageItem.tokens : runLevel(usage && usage.tokens)
    const duration = usageItem && usageItem.duration !== undefined ? usageItem.duration : runLevel(usage && usage.duration)

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
    if (doc.args.serverRunId) fields.extra = { 'server-run': doc.args.serverRunId }

    out[item.id] = formatProvenance(fields)
  }

  if (onlyItem) {
    const { plan } = resolvePlan(core, doc)
    const target = findItem(plan, onlyItem)
    const line = out[target.id]
    const resTarget = resultByItemId.get(target.id)
    const merged = ((resTarget && resTarget.reseats) || []).map((r) => ({ seat: r.seat, model: r.model, tokens: r.tokens }))
    for (const e of extraSeats || []) {
      const at = merged.findIndex((r) => r.seat === e.seat)
      if (at === -1) merged.push(e)
      else merged[at] = e
    }
    const extraLine = formatExtraSeats(merged)
    return { [target.id]: extraLine ? `${line}\n${extraLine}` : line }
  }

  return out
}

/**
 * reviewPrompt(core, doc, idOrShort, result?) -> text joined by '\n\n'.
 * On a bound run (args.serverRunId) it names the reviewer lane's token file
 * (<scratchDir>/tokens/<short>.reviewer.token) and the END SEAT call; the token itself never appears.
 * Seat line, the owned-file diff command (planner output's mainFiles+docFiles+testFiles+
 * existingTestEdits, relativized against the worktree and args.repoRoot, as quoted ':(literal)'/
 * ':(glob)' pathspecs when a result is given, deduped after relativizePath canonicalization (so
 * 'src/./a.js' and 'src//a.js' give one ':(literal)src/a.js'), plus an 'UNROOTED (not in
 * pathspec): ...' part naming each distinct non-empty unrooted entry (still absolute, or
 * containing '..'); else a bare diff noting 'owned files: planner output'), a line to fill the
 * review-phase notes and REVIEW_RULES by key (no rule text).
 * Whole tree (D5): when any owned entry relativizes to '' ('.', './', the worktree or repo root
 * itself), the diff line is the bare `git -C <worktree> diff <base>..HEAD` with no ' -- '
 * pathspec at all; non-empty unrooted entries are still named on the UNROOTED line.
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
      // D5: every owned path (main + docs + tests + existing-test edits), relativized against the
      // worktree and repo root, deduped in order, with explicit pathspec magic so a literal path
      // never globs and a glob never matches across '/'. Unrooted entries are named, not passed.
      const owned = [].concat(
        plannerOut.mainFiles || [], plannerOut.docFiles || [], plannerOut.testFiles || [],
        ((plannerOut.existingTestEdits || []).filter((e) => e && e.file).map((e) => e.file))
      )
      const roots = itemRootsOf(plan, item)
      const specs = []
      const seen = new Set()
      // An owned entry that relativizes to '' ('.', './', the worktree or repo root itself) owns the
      // whole tree, so the diff gets no pathspec at all rather than a narrowed one.
      const unrooted = []
      let wholeTree = false
      for (const raw of owned) {
        const rel = relativizePath(raw, roots)
        if (isUnrooted(rel)) {
          if (!rel) wholeTree = true
          else if (!unrooted.includes(rel)) unrooted.push(rel)
          continue
        }
        if (seen.has(rel)) continue
        seen.add(rel)
        specs.push(shellQuote(`${isGlob(rel) ? ':(glob)' : ':(literal)'}${rel}`))
      }
      const pathspec = !wholeTree && specs.length > 0 ? ` -- ${specs.join(' ')}` : ''
      parts.push(`git -C ${item.worktree} diff ${plan.baseSha}..HEAD${pathspec}`)
      if (unrooted.length > 0) parts.push(`UNROOTED (not in pathspec): ${unrooted.join(' ')}`)
    } else {
      parts.push(`git -C ${item.worktree} diff ${plan.baseSha}..HEAD (owned files: planner output)`)
    }
  } else {
    parts.push(`git -C ${item.worktree} diff ${plan.baseSha}..HEAD (no result — owned files: planner output)`)
  }

  parts.push(
    'Fill this item\'s review-phase notes (items_read(select:"context", itemId) lists them under schema, role review). ' +
    'Pass keys:[...] on every notes_read list call; write with notes_write upsert, body inline.'
  )

  parts.push(
    `RULES: fetch each key below via config(ops:[{op:"rules", rootId:"${plan.rootId}", key:<key>}]) ` +
    `and follow the returned body. Keys: ${REVIEW_RULES.join(', ')}.`
  )

  const tokenFile = core.seatTokenFile(plan, item, { seat: 'reviewer' })
  if (tokenFile) {
    parts.push(
      `SEAT TOKEN: run ${plan.serverRunId}. Your seat token is the single line in the file ${tokenFile}. Read it with your file tool, ` +
      'trim it, and pass it as the top-level seatToken field of every task-orchestrator call you make, reads included. Never print, ' +
      'quote, echo, log or commit the token or the file content. A call refused unauthenticated ("The seat token is not accepted.") ' +
      'means the lane was superseded or ended: stop and return status "stopped", reason "seat-token-rejected".'
    )
    parts.push(
      'END SEAT: as your last task-orchestrator call, end the lane with your seat token: runs_write(ops:[{op:"end_seat", outcome, summary}]); ' +
      'outcome "done" when your notes are filled, "failed" otherwise. Set seatEnded: true in the envelope when it succeeded.'
    )
  }

  return parts.join('\n\n')
}

// ---------------------------------------------------------------------------------------
// Server-run ops (pure). The orchestrator sends what these render; the Workflow runtime has no
// MCP access and this file never calls the server.
// ---------------------------------------------------------------------------------------

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export const CLOSE_REASONS = ['completed', 'cancelled', 'failed', 'abandoned']
const BUDGET_KEYS = ['tokens', 'costUsd', 'attemptsPerSeat', 'maxActiveSeats']

/**
 * closeReason(result, override?) -> 'completed' | 'failed' | 'cancelled' | 'abandoned'.
 * override 'cancelled' or 'abandoned' (an explicit user stop, or a Resume re-plan of an unrecoverable
 * run) wins; any other non-empty override throws 'invalid args: ...'. Else 'completed' when
 * result.started === true, 'failed' when it is false or there is no result (the launch threw).
 */
export function closeReason(result, override) {
  if (override !== undefined && override !== null && override !== '') {
    if (override === 'cancelled' || override === 'abandoned') return override
    throw new Error(`invalid args: --reason must be cancelled or abandoned, got ${JSON.stringify(override)}`)
  }
  return result && result.started === true ? 'completed' : 'failed'
}

function resultsOf(advanceResult) {
  if (!advanceResult || typeof advanceResult !== 'object') return []
  const data = advanceResult.data && typeof advanceResult.data === 'object' ? advanceResult.data : advanceResult
  return Array.isArray(data.results) ? data.results : []
}

/**
 * advanceFollowUps(advanceResult) -> {unblocked, seatBlocked, gateBlocked, reviewPending, gateMissing}.
 * advanceResult is an advance response (the envelope or its data): data.results[i] per transition.
 * Over the successful elements: unblocked = unblocked[].itemId; from cascades[] with applied false,
 * seatBlocked = blocked "seat" (the coordinator token re-sends the parent's transition), reviewPending
 * = blocked "gate" with reason "review-pending" (a terminal cascade into a parent in review, by design),
 * gateBlocked = any other blocked "gate" (gateMissing[itemId] = its missing[].key). Every array holds
 * item ids, deduplicated, in result order; every member is present ([] / {} when nothing applies).
 */
export function advanceFollowUps(advanceResult) {
  const out = { unblocked: [], seatBlocked: [], gateBlocked: [], reviewPending: [], gateMissing: {} }
  const add = (list, id) => {
    if (typeof id === 'string' && id && !list.includes(id)) list.push(id)
  }
  for (const r of resultsOf(advanceResult)) {
    if (!r || r.ok === false) continue
    for (const u of r.unblocked || []) add(out.unblocked, u && u.itemId)
    for (const c of r.cascades || []) {
      if (!c || c.applied !== false) continue
      if (c.blocked === 'seat') add(out.seatBlocked, c.itemId)
      else if (c.blocked === 'gate' && c.reason === 'review-pending') add(out.reviewPending, c.itemId)
      else if (c.blocked === 'gate') {
        add(out.gateBlocked, c.itemId)
        const keys = (c.missing || []).map((m) => m && m.key).filter((k) => typeof k === 'string')
        out.gateMissing[c.itemId] = Array.from(new Set([...(out.gateMissing[c.itemId] || []), ...keys]))
      }
    }
  }
  return out
}

function requireBound(doc) {
  const id = doc && doc.args && doc.args.serverRunId
  if (typeof id !== 'string' || !UUID_RE.test(id)) {
    throw new Error('invalid args: the plan is not bound to a server run (run bind-run first)')
  }
  return id
}

function seatRowsOf(seats) {
  if (Array.isArray(seats)) return seats
  if (!seats || typeof seats !== 'object') return []
  const data = seats.data && typeof seats.data === 'object' ? seats.data : seats
  if (Array.isArray(data.seats)) return data.seats
  if (Array.isArray(data.items)) return data.items
  return []
}

function stageOutcome(result, itemId, seat) {
  const resItem = result && Array.isArray(result.items) ? result.items.find((r) => r.id === itemId) : null
  const st = resItem && (resItem.stages || []).find((s) => s.seat === seat)
  if (st && st.status === 'done') return 'done'
  if (st && st.status === 'stopped') return 'failed'
  return 'abandoned'
}

function runHints(doc, phase) {
  const hints = {
    plan: doc.args.runId,
    method: (doc.meta && doc.meta.method) || 'A',
    worktreeMode: doc.args.worktreeMode,
  }
  if (phase) hints.phase = phase
  return hints
}

/**
 * runOps(core, doc, opts) -> {tool, ops, tokenFiles?} - the exact ops array the orchestrator sends.
 * Never carries a seatToken key: the orchestrator adds the right token top-level (the coordinator
 * token, except end_seat, which goes under each seat's own token).
 * opts.phase:
 *   open      runs_write open {rootId, items, scopeId?, label: runId, title, hints:{plan, method,
 *             worktreeMode}, budget?} (budget only from the profile's opt-in runBudget, meta.runBudget);
 *             also tokenFile: <scratchDir>/tokens/coordinator.token, where the orchestrator persists the
 *             coordinator token so a restart or compaction can read it back (the file is redacted by value).
 *   assign    runs_write assign {runId, itemId, seat}; without opts.item every non-inserted stage of
 *             every item (Method A, at launch), with opts.item + opts.seat that one seat (Method B, a
 *             review lane, a reseat's base seat). tokenFiles[i] is where results[i].seatToken goes.
 *   update    runs_write update {runId, hints} with hints.phase = opts.runPhase (the hints replace).
 *   heartbeat advance [claim(first item, or opts.item: a short or full id of a plan item), heartbeat{usage:{tokens,
 *             costUsd?}}, release] from opts.usage; an unknown opts.item throws.
 *   end-seats one runs_write end_seat per still-active member seat in opts.seats (runs_read seats
 *             data); outcome done/failed/abandoned from opts.result's stage status; the coordinator
 *             row and ended rows are skipped; tokenFiles[i] names the seat's token file.
 *   close     runs_write close {runId, reason: closeReason(opts.result, opts.reason)}.
 * Throws 'invalid args: ...' on bad input.
 */
export function runOps(core, doc, opts = {}) {
  if (!doc || !doc.args) throw new Error('invalid args: not a plan document')
  const phase = opts.phase
  const items = doc.args.items || []
  if (phase === 'open') {
    if (items.length === 0) throw new Error('invalid args: the plan admits no items')
    const op = {
      op: 'open',
      rootId: doc.args.rootId,
      items: items.map((it) => it.id),
    }
    const scopeId = doc.meta && doc.meta.scopeId
    if (typeof scopeId === 'string' && scopeId) op.scopeId = scopeId
    op.label = doc.args.runId
    op.title = `run-wave ${doc.args.runId} (${items.length} item${items.length === 1 ? '' : 's'})`
    op.hints = runHints(doc)
    const rb = doc.meta && doc.meta.runBudget
    if (rb && typeof rb === 'object' && !Array.isArray(rb)) {
      const budget = {}
      for (const k of BUDGET_KEYS) if (typeof rb[k] === 'number' && rb[k] > 0) budget[k] = rb[k]
      if (Object.keys(budget).length > 0) op.budget = budget
    }
    const out = { tool: 'runs_write', ops: [op] }
    const scratch = doc.args.project && doc.args.project.scratchDir
    if (typeof scratch === 'string' && scratch) {
      out.tokenFile = `${normalizePath(scratch).replace(/\/+$/, '')}/tokens/coordinator.token`
    }
    return out
  }
  if (phase === 'assign') {
    const runId = requireBound(doc)
    const { plan } = resolvePlan(core, doc)
    const ops = []
    const tokenFiles = []
    if (opts.item || opts.seat) {
      if (!opts.item || !opts.seat) throw new Error('invalid args: assign takes --item and --seat together')
      const item = findItem(plan, opts.item)
      const stage = item.stages.find((s) => s.seat === opts.seat)
      if (stage && stage.inserted) throw new Error(`invalid args: ${opts.seat} is an inserted stage and runs token-less`)
      ops.push({ op: 'assign', runId, itemId: item.id, seat: opts.seat })
      tokenFiles.push(core.seatTokenFile(plan, item, stage || { seat: opts.seat }))
    } else {
      for (const item of plan.items) {
        for (const stage of item.stages) {
          const file = core.seatTokenFile(plan, item, stage)
          if (!file) continue
          ops.push({ op: 'assign', runId, itemId: item.id, seat: stage.seat })
          tokenFiles.push(file)
        }
      }
    }
    return { tool: 'runs_write', ops, tokenFiles }
  }
  if (phase === 'update') {
    const runId = requireBound(doc)
    if (typeof opts.runPhase !== 'string' || !/^[a-z][a-z-]{0,31}$/.test(opts.runPhase)) {
      throw new Error('invalid args: update needs --run-phase <name>')
    }
    return { tool: 'runs_write', ops: [{ op: 'update', runId, hints: runHints(doc, opts.runPhase) }] }
  }
  if (phase === 'heartbeat') {
    requireBound(doc)
    if (items.length === 0) throw new Error('invalid args: the plan admits no items')
    const u = opts.usage
    if (!u || typeof u !== 'object' || !Number.isInteger(u.tokens) || u.tokens < 0) {
      throw new Error('invalid args: --usage needs {tokens: integer >= 0, costUsd?: number}')
    }
    const usage = { tokens: u.tokens }
    if (u.costUsd !== undefined && u.costUsd !== null) {
      if (typeof u.costUsd !== 'number' || !(u.costUsd >= 0)) throw new Error('invalid args: usage.costUsd must be a number >= 0')
      usage.costUsd = u.costUsd
    }
    const itemId = opts.item ? findItem({ items }, opts.item).id : items[0].id
    return {
      tool: 'advance',
      ops: [
        { op: 'claim', itemId },
        { op: 'heartbeat', itemId, usage },
        { op: 'release', itemId },
      ],
    }
  }
  if (phase === 'end-seats') {
    requireBound(doc)
    const { plan } = resolvePlan(core, doc)
    const byId = new Map(plan.items.map((it) => [it.id.toLowerCase(), it]))
    const ops = []
    const tokenFiles = []
    for (const row of seatRowsOf(opts.seats)) {
      if (!row || row.status !== 'active' || row.seat === 'coordinator' || !row.itemId) continue
      const item = byId.get(String(row.itemId).toLowerCase())
      if (!item) continue
      const stage = item.stages.find((s) => s.seat === row.seat)
      const outcome = stageOutcome(opts.result, item.id, row.seat)
      ops.push({ op: 'end_seat', outcome, summary: `run-wave ${doc.args.runId}: ended by the post-run sweep (${outcome})` })
      tokenFiles.push(core.seatTokenFile(plan, item, stage || { seat: row.seat }))
    }
    return { tool: 'runs_write', ops, tokenFiles }
  }
  if (phase === 'close') {
    const runId = requireBound(doc)
    return { tool: 'runs_write', ops: [{ op: 'close', runId, reason: closeReason(opts.result, opts.reason) }] }
  }
  throw new Error(`invalid args: --phase must be open, assign, update, heartbeat, end-seats or close, got ${JSON.stringify(phase)}`)
}

// ---------------------------------------------------------------------------------------
// Coordinator report (pure) and the review-wave args builder (pure).
// ---------------------------------------------------------------------------------------

function unwrapObject(value, depth = 0) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || depth > 4) return {}
  if (value.data && typeof value.data === 'object' && !Array.isArray(value.data)) return unwrapObject(value.data, depth + 1)
  if (Array.isArray(value.results) && value.results[0] && typeof value.results[0] === 'object') {
    return unwrapObject(value.results[0], depth + 1)
  }
  if (value.run && typeof value.run === 'object' && !Array.isArray(value.run)) return Object.assign({}, value, value.run)
  return value
}

/**
 * reportFields(doc, opts) -> the fields formatCoordinatorReport takes, built from ids, counts and
 * short reasons only. opts: {event, result, run, close, needs}. `result` is a result-v1, `run` a saved
 * runs_read get (or runs_write) answer, `close` a saved runs_write close answer.
 */
export function reportFields(doc, opts = {}) {
  if (!doc || !doc.args) throw new Error('invalid args: not a plan document')
  const meta = doc.meta || {}
  const args = doc.args
  const event = opts.event
  if (!REPORT_EVENTS.includes(event)) {
    throw new Error(`invalid args: --event must be one of ${REPORT_EVENTS.join(', ')}, got ${JSON.stringify(event)}`)
  }
  const run = unwrapObject(opts.run)
  const close = unwrapObject(opts.close)
  const snapshot = close.snapshot && typeof close.snapshot === 'object' ? close.snapshot : {}
  const result = opts.result && typeof opts.result === 'object' ? opts.result : null

  const fields = {
    event,
    label: args.runId,
    runId: args.serverRunId || run.runId || run.id || '',
    method: meta.method === 'B' ? 'B' : 'A',
    phase: (run.hints && run.hints.phase) || '',
    status: run.status || close.status || undefined,
    counts: { done: 0, stopped: 0, deferred: 0, held: 0, excluded: (meta.excluded || []).length },
    decisions: [],
    stopped: [],
    needs: opts.needs || '',
  }

  const deferredPlan = (Array.isArray(args.deferred) ? args.deferred : meta.deferred) || []
  if (result) {
    for (const it of result.items || []) {
      if (it.status === 'done') fields.counts.done++
      else if (it.status === 'stopped') {
        fields.counts.stopped++
        fields.stopped.push({ item: it.short || it.id, reason: it.reason })
      } else if (it.status === 'deferred') fields.counts.deferred++
      else fields.counts.held++
      for (const st of it.stages || []) {
        if (typeof st.decisionId === 'string' && st.decisionId) {
          fields.decisions.push({ decisionId: st.decisionId, itemId: it.id })
        }
      }
    }
    fields.counts.deferred += (result.deferred || []).length
    fields.counts.excluded += (result.refused || []).length
    if (result.started === false) {
      fields.stopped.push({ item: '-', reason: result.reason || 'wave did not start' })
    }
  } else {
    fields.counts.deferred = deferredPlan.length
  }

  const pauseDecisionId = run.pauseDecisionId || (run.pause && run.pause.decisionId) || ''
  if (run.status === 'paused' || event === 'paused') {
    fields.pauseReason = run.pauseReason || ''
    fields.pauseDecisionId = pauseDecisionId
    if (pauseDecisionId && !fields.decisions.some((d) => d.decisionId === pauseDecisionId)) {
      fields.decisions.push({ decisionId: pauseDecisionId, itemId: '' })
    }
  }

  if (event === 'closed') {
    fields.status = 'closed'
    fields.closeReason = snapshot.reason || close.terminalReason || close.reason || (result && result.started === true ? 'completed' : 'failed')
    const od = snapshot.openDecisions
    fields.openDecisions = Array.isArray(od) ? od.map((d) => (d && typeof d === 'object' ? d.id || d.decisionId : d)) : []
    if (!Array.isArray(od) && Number.isInteger(od)) fields.openDecisionCount = od
    fields.headline = `${fields.closeReason}; ${fields.counts.done} done, ${fields.counts.stopped} stopped`
  } else if (event === 'planned') {
    const admitted = (args.items || []).length
    fields.headline = `${admitted} item${admitted === 1 ? '' : 's'} planned, ${deferredPlan.length} deferred`
    fields.counts.deferred = deferredPlan.length
    if (!fields.needs && ((meta.excluded || []).length > 0 || (meta.degradations || []).length > 0)) {
      fields.needs = 'approve launch'
    }
  } else if (event === 'paused') {
    fields.status = 'paused'
    fields.headline = `run paused (${fields.pauseReason || 'unknown'})`
    if (!fields.needs) fields.needs = 'answer the pause decision, then resume or close'
  } else {
    fields.headline = `${fields.decisions.length} open decision${fields.decisions.length === 1 ? '' : 's'}`
    if (!fields.needs) fields.needs = 'answer the decision'
  }
  return fields
}

const LANE_DEFAULT_SKILL = 'review-quality'

/**
 * reviewWaveArgs(core, doc, snapshot, result, shorts) -> review-wave/args-v1 over the given items.
 * Lanes are the item's review-role schema notes grouped by (seat or reviewer, skill or review-quality);
 * no simplify lane. Throws 'invalid args: ...' for an unknown short or an item with no review notes.
 */
export function reviewWaveArgs(core, doc, snapshot, result, shorts) {
  const { plan } = resolvePlan(core, doc)
  if (!Array.isArray(shorts) || shorts.length === 0) throw new Error('invalid args: --items needs at least one item')
  const out = {
    contract: 'review-wave/args-v1',
    runId: plan.runId,
    rootId: plan.rootId,
    baseSha: plan.baseSha,
    worktreeMode: plan.worktreeMode,
    entryMode: 'pre-entered',
    capabilities: plan.capabilities,
    items: [],
  }
  if (plan.serverRunId) out.serverRunId = plan.serverRunId
  for (const raw of shorts) {
    const item = findItem(plan, raw)
    const schema = (snapshot && snapshot.schemas && snapshot.schemas[item.id]) || {}
    const reviewNotes = (schema.notes || []).filter((n) => n && n.role === 'review')
    if (reviewNotes.length === 0) throw new Error(`invalid args: ${item.short} has no review-phase notes`)
    const groups = new Map()
    for (const n of reviewNotes) {
      const seat = n.seat || n.reviewer || 'reviewer'
      const skill = n.skill || LANE_DEFAULT_SKILL
      const key = `${seat}.${skill}`
      if (!groups.has(key)) groups.set(key, { seat, skill, named: !!n.skill, notes: [] })
      groups.get(key).notes.push(n.key)
    }
    const stages = []
    for (const [lane, g] of groups) {
      const dispatch =
        (schema.dispatchBySeat && schema.dispatchBySeat.review && schema.dispatchBySeat.review[g.seat]) ||
        (schema.dispatch && schema.dispatch.review) || {}
      const stage = {
        seat: g.seat,
        lane,
        phase: 'review',
        notes: g.notes,
        writes: false,
        protocol: 'protocol.read-only-agent',
        dispatch,
        output: 'review-v1',
      }
      if (g.named) stage.skills = [g.skill]
      const tokenFile = core.seatTokenFile(plan, item, { seat: g.seat })
      if (tokenFile) stage.tokenFile = tokenFile
      stages.push(stage)
    }
    const resItem = result && (result.items || []).find((r) => r.id === item.id)
    const plannerOut = resItem ? outputForStageId(resItem, item, 'planner-v1') : null
    const roots = itemRootsOf(plan, item)
    const rel = (list) => {
      const seen = []
      for (const p of list) {
        const r = relativizePath(p, roots)
        if (!isUnrooted(r) && !seen.includes(r)) seen.push(r)
      }
      return seen
    }
    const po = plannerOut || {}
    const postOf = (seat) => {
      const st = resItem && (resItem.stages || []).find((s) => s.seat === seat)
      const post = st && st.commits && st.commits.post
      return post ? [post] : []
    }
    out.items.push({
      id: item.id,
      short: item.short,
      title: item.title,
      type: item.type,
      configFingerprint: item.configFingerprint === undefined ? null : item.configFingerprint,
      traits: item.traits || [],
      worktree: item.worktree,
      stages,
      review: {
        requiredReviewNotes: reviewNotes.filter((n) => n.required).map((n) => n.key),
        optionalReviewNotes: reviewNotes.filter((n) => !n.required).map((n) => n.key),
        ownedFiles: {
          implementer: rel(po.mainFiles || []),
          testAuthor: rel([].concat(po.testFiles || [], (po.existingTestEdits || []).filter((e) => e && e.file).map((e) => e.file))),
          docs: rel(po.docFiles || []),
        },
        commits: { implementer: postOf('implementer'), testAuthor: postOf('test-author'), fixtureRepairs: [] },
      },
    })
  }
  return out
}

/** reportBlock(doc, opts) -> the formatted TO report block for a plan, result, run and close answer. */
export function reportBlock(doc, opts = {}) {
  return formatCoordinatorReport(reportFields(doc, opts))
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

/** Token-file values under <scratchDir>/tokens (trimmed, non-empty); used only to redact. */
function readKnownTokens(doc, io) {
  const scratch = doc && doc.args && doc.args.project && doc.args.project.scratchDir
  if (typeof scratch !== 'string' || !scratch) return []
  const dir = `${scratch.replace(/[\\/]+$/, '')}/tokens`
  const names = io.listDir(dir) || []
  const out = []
  for (const name of names) {
    if (!String(name).endsWith('.token')) continue
    const text = io.readText(`${dir}/${name}`)
    if (typeof text !== 'string') continue
    const t = text.trim()
    if (t) out.push(t)
  }
  return out
}

function execStageResult(argv, io) {
  const doc = io.readInput(flagValue(argv, '--plan'))
  const state = readStateOrFail(doc, argv, io)
  if (state === undefined) return
  const core = io.loadCore()
  const envelopeText = io.readText(flagValue(argv, '--envelope'))
  const opts = {
    knownTokens: readKnownTokens(doc, io),
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
  const resolved = {}
  const roots = {}
  const commits = []
  for (const wt of worktrees) {
    // Roots to strip from declared/changed paths: the worktree top-level and the main checkout
    // (parent of the git common dir).
    const rootList = []
    const top = io.git(wt, ['rev-parse', '--show-toplevel'])
    if (top && top.status === 0 && String(top.stdout || '').trim()) rootList.push(String(top.stdout).trim())
    const common = io.git(wt, ['rev-parse', '--path-format=absolute', '--git-common-dir'])
    if (common && common.status === 0 && String(common.stdout || '').trim()) {
      const c = normalizePath(String(common.stdout).trim()).replace(/\/+$/, '')
      const parent = c.replace(/\/\.git$/, '')
      if (parent !== c && parent) rootList.push(parent)
    }
    roots[wt] = rootList
    resolved[wt] = {}
    for (const item of doc.args.items) {
      if (item.worktree !== wt) continue
      for (const stage of item.stages) {
        const resItem = (result.items || []).find((r) => r.id === item.id)
        const stageRes = resItem && (resItem.stages || []).find((s) => s.seat === stage.seat)
        for (const ref of [stageRes && stageRes.commits && stageRes.commits.pre, stageRes && stageRes.commits && stageRes.commits.post]) {
          if (!ref) continue
          if (HEX_SHA_RE.test(ref)) {
            if (!(ref in exists)) {
              const r = io.git(wt, ['cat-file', '-e', ref])
              exists[ref] = r.status === 0
            }
          } else if (!(ref in resolved[wt])) {
            // Symbolic ref (HEAD, branch, tag...): resolve in the item's worktree; never cat-file it.
            let sha = null
            if (!ref.startsWith('-')) {
              const r = io.git(wt, ['rev-parse', '--verify', '--quiet', `${ref}^{commit}`])
              const out = r && r.status === 0 ? String(r.stdout || '').trim() : ''
              sha = /^[0-9a-f]{40,64}$/.test(out) ? out : null
            }
            resolved[wt][ref] = sha
          }
        }
      }
    }
    const log = io.git(wt, ['log', '--reverse', '--topo-order', '--format=%H%x1f%s%x1f%b%x1e', '--name-only', `${doc.args.baseSha}..HEAD`])
    commits.push(...parseGitLog(log.stdout))
  }
  const gitFacts = { exists, commits, resolved, roots }
  const out = verify(doc, result, gitFacts)
  io.writeOut(JSON.stringify(out))
  io.exit(out.ok ? 0 : 3)
}

function execNotesAudit(argv, io) {
  const doc = io.readInput(flagValue(argv, '--plan'))
  const notesPath = flagValue(argv, '--notes')
  if (!notesPath) {
    io.writeOut(JSON.stringify(expectedNotes(doc)))
    io.exit(0)
    return
  }
  const observed = io.readInput(notesPath)
  const itemsFlag = flagValue(argv, '--items')
  const itemIds = itemsFlag ? itemsFlag.split(',') : undefined
  // Optional --result limits expected notes to stages that actually ran (status 'done'), so an item
  // with only deferred/never-run stages is 'skipped' rather than flagged 'missing'.
  const resultPath = flagValue(argv, '--result')
  const resultDoc = resultPath ? io.readInput(resultPath) : undefined
  const core = io.loadCore()
  const result = notesAudit(doc, observed, itemIds, resultDoc ? { result: resolveResultDoc(core, doc, resultDoc) } : undefined)
  io.writeOut(JSON.stringify(result))
  io.exit(result.ok ? 0 : 3)
}

function execFollowUps(argv, io) {
  const inPath = flagValue(argv, '--in')
  const answer = inPath ? io.readInput(inPath) : null
  if (!answer) {
    io.fail(2, 'invalid args', `--in ${inPath} could not be read as an advance answer`)
    return
  }
  io.writeOut(JSON.stringify(advanceFollowUps(answer)))
  io.exit(0)
}

function execRunOps(argv, io) {
  const planPath = flagValue(argv, '--plan')
  const doc = io.readInput(planPath)
  if (!doc || !doc.args) {
    io.fail(2, 'invalid args', `--plan ${planPath} could not be read or parsed as a plan`)
    return
  }
  const core = io.loadCore()
  const read = (flag) => {
    const p = flagValue(argv, flag)
    if (!p) return undefined
    const v = io.readInput(p)
    if (v === null || v === undefined) throw new Error(`invalid args: ${flag} ${p} could not be read as JSON`)
    return v
  }
  let resultDoc = read('--result')
  const state = read('--state')
  if (resultDoc === undefined && state !== undefined) resultDoc = state
  if (resultDoc !== undefined) resultDoc = resolveResultDoc(core, doc, resultDoc)
  const out = runOps(core, doc, {
    phase: flagValue(argv, '--phase'),
    item: flagValue(argv, '--item'),
    seat: flagValue(argv, '--seat'),
    seats: read('--seats'),
    usage: read('--usage'),
    result: resultDoc,
    reason: flagValue(argv, '--reason'),
    runPhase: flagValue(argv, '--run-phase'),
  })
  io.writeOut(JSON.stringify(out))
  io.exit(0)
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
  const extraFlag = flagValue(argv, '--extra-seats')
  const itemFlag = flagValue(argv, '--item')
  if (extraFlag !== undefined && !itemFlag) throw new Error('provenance: --extra-seats requires --item')
  const extraSeats = extraFlag !== undefined ? parseExtraSeats(`x\nextra-seats=${extraFlag}`) : undefined
  const out = provenance({
    core, doc, result,
    method: flagValue(argv, '--method'),
    turns: Number(flagValue(argv, '--turns', '0')),
    usage, meta,
    journal: flagValue(argv, '--journal'),
    extraSeats,
    item: itemFlag,
  })
  io.writeOut(JSON.stringify(out))
  io.exit(0)
}

function execReseat(argv, io) {
  const planPath = flagValue(argv, '--plan')
  const doc = io.readInput(planPath)
  if (!doc || !doc.args) {
    io.fail(2, 'invalid args', `--plan ${planPath} could not be read or parsed as a plan`)
    return
  }
  const runId = flagValue(argv, '--run')
  if (!runId || runId !== doc.args.runId) {
    io.fail(2, 'invalid args', `--run ${runId} does not match the plan's runId`)
    return
  }
  const resultPath = flagValue(argv, '--result')
  const resultDoc = resultPath ? io.readInput(resultPath) : null
  if (!resultDoc) {
    io.fail(2, 'invalid args', '--result is required')
    return
  }
  const core = io.loadCore()
  const result = resolveResultDoc(core, doc, resultDoc)
  const item = flagValue(argv, '--item')
  const declPath = flagValue(argv, '--declarations')
  const tokensRaw = flagValue(argv, '--tokens')
  let pre = flagValue(argv, '--pre')
  if (!pre) {
    const plan = resolvePlan(core, doc).plan
    const found = findItem(plan, item)
    const r = io.git(found.worktree, ['rev-parse', 'HEAD'])
    pre = r && r.status === 0 ? String(r.stdout || '').trim() : ''
    if (!pre) {
      io.fail(2, 'invalid args', `reseat: cannot resolve pre (git rev-parse HEAD failed in ${found.worktree}): pass --pre <sha>`)
      return
    }
  }
  const declText = declPath ? io.readText(declPath) : undefined
  if (declPath && declText == null) {
    io.fail(2, 'invalid args', `--declarations ${declPath} is not readable`)
    return
  }
  const assignment = flagValue(argv, '--assignment')
  if (assignment !== undefined && !UUID_RE.test(assignment)) {
    io.fail(2, 'invalid args', `--assignment ${assignment} must be the assignment UUID runs_write assign returned`)
    return
  }
  const out = reseat(core, doc, result, item, flagValue(argv, '--seat'), {
    declarations: declText,
    model: flagValue(argv, '--model'),
    tokens: tokensRaw === undefined ? undefined : (Number.isFinite(Number(tokensRaw)) ? Number(tokensRaw) : tokensRaw),
    pre,
    assignment,
  })
  io.writeOut(JSON.stringify(out))
  io.exit(out.ok ? 0 : 3)
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

function readJsonFlag(argv, io, flag) {
  const p = flagValue(argv, flag)
  if (!p) return undefined
  const v = io.readInput(p)
  if (v === null || v === undefined) throw new Error(`invalid args: ${flag} ${p} could not be read as JSON`)
  return v
}

function execReport(argv, io) {
  const planPath = flagValue(argv, '--plan')
  const doc = io.readInput(planPath)
  if (!doc || !doc.args) {
    io.fail(2, 'invalid args', `--plan ${planPath} could not be read or parsed as a plan`)
    return
  }
  const core = io.loadCore()
  let result = readJsonFlag(argv, io, '--result')
  if (result !== undefined) result = resolveResultDoc(core, doc, result)
  const text = reportBlock(doc, {
    event: flagValue(argv, '--event'),
    result,
    run: readJsonFlag(argv, io, '--run'),
    close: readJsonFlag(argv, io, '--close'),
    needs: flagValue(argv, '--needs'),
  })
  io.writeOut(text)
  io.exit(0)
}

function execReviewArgs(argv, io) {
  const planPath = flagValue(argv, '--plan')
  const doc = io.readInput(planPath)
  if (!doc || !doc.args) {
    io.fail(2, 'invalid args', `--plan ${planPath} could not be read or parsed as a plan`)
    return
  }
  const core = io.loadCore()
  const snapshot = readJsonFlag(argv, io, '--snapshot')
  if (!snapshot) {
    io.fail(2, 'invalid args', '--snapshot is required')
    return
  }
  let result = readJsonFlag(argv, io, '--result')
  if (result !== undefined) result = resolveResultDoc(core, doc, result)
  const shorts = String(flagValue(argv, '--items', '')).split(',').map((x) => x.trim()).filter(Boolean)
  const out = reviewWaveArgs(core, doc, snapshot, result, shorts)
  io.writeOut(JSON.stringify(out))
  io.exit(0)
}

export const EXEC_COMMANDS = {
  report: execReport,
  'review-args': execReviewArgs,
  next: execNext,
  prompt: execPrompt,
  'stage-result': execStageResult,
  'scan-declarations': execScanDeclarations,
  verify: execVerify,
  'notes-audit': execNotesAudit,
  provenance: execProvenance,
  'review-prompt': execReviewPrompt,
  reseat: execReseat,
  'run-ops': execRunOps,
  'follow-ups': execFollowUps,
}
