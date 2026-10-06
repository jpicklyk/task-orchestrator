// ESM tests for workflows/retro-analysis.js (item 34cfa0a1). Implementer-written: no
// needs-test-author trait on this item (the core is assembly-only with content oracles, not a
// blind-authored suite) — the same implementer who wrote the script also wrote this file.
//
// Scenario ids (RA1-RA16 + T-meta/T-core-pure/T-schema/T-rule/T-repo/T-deep) and oracles match
// Appendix C of plans/phase-c-dispatch-contract.md (the frozen contract this file compiles
// against) and plans/phase-c-workflows.md §3.2.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'
import { mkdtempSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'

import { scriptText, coreSlice, loadMeta, loadScript, fakeParallel } from './workflow-harness.mjs'
import {
  loadCoreNamed,
  autoAgent,
  assertBarrier,
  scanForbiddenApis,
  freeRuntimeIds,
  ruleWindowHits,
} from './workflow-harness-ext.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SCRIPT_PATH = join(HERE, '..', '..', 'workflows', 'retro-analysis.js')
const PLUGIN = resolve(HERE, '..', '..')
const REPO = resolve(PLUGIN, '..', '..')
const RULES_DIR = join(REPO, '.taskorchestrator', 'rules')
const SKILL_PATH = join(PLUGIN, 'skills', 'session-retrospective', 'SKILL.md')

const CORE_NAMES = [
  'normalizeArgs', 'projectAgents', 'sizeGuard', 'makeShards', 'schemas', 'collectMatches',
  'routeFindings', 'matchPrompt', 'adjudicatePrompt', 'clusterPrompt', 'buildMatchedEntry',
  'assembleResult', 'runRetroAnalysis', 'kebab', 'trendKey', 'daysBetween', 'pickCandidate',
  'agentOpts', 'DIMENSIONS', 'KINDS', 'CAPS', 'DEFAULTS',
]
const CORE = loadCoreNamed(SCRIPT_PATH, CORE_NAMES)

// ── fixture builders ─────────────────────────────────────────────────────────

function entry(id, title, overrides = {}) {
  return { id, short: id.slice(0, 8), title, ...overrides }
}
function findingFx(fid, overrides = {}) {
  return { fid, dimension: 'friction', text: 'finding text ' + fid, keywords: [], ...overrides }
}
function baseArgs(overrides = {}) {
  return {
    contract: 'retro-analysis/args-v1', runId: 'ra-test-0001', date: '2026-09-28', mode: 'audit',
    findings: [], trends: [], observations: [], retros: [],
    ...overrides,
  }
}
/** Builds a normalized plan via the core's own normalizeArgs (a pure, oracled function — see
 * RA14 below), so fixtures stay valid against whatever normalizeArgs actually requires. */
function buildPlan(overrides = {}) {
  const { ok, plan, reason } = CORE.normalizeArgs(baseArgs(overrides))
  assert.equal(ok, true, `buildPlan: normalizeArgs unexpectedly failed: ${reason}`)
  return plan
}
function matchResult({ matches = [], clusters = [], trendState = [] } = {}) {
  return { matches, clusters, trendState }
}
function adjudicateResult(overrides = {}) {
  return { decision: 'match', trendId: '', kebabKey: '', claim: '', rationale: 'r', ...overrides }
}
function clusterResultFx(overrides = {}) {
  return { newTrends: [], observationLinks: [], ...overrides }
}

function makeDeps({ agent, phaseLog = [], logLines = [] } = {}) {
  return { agent, parallel: fakeParallel, phase: (t) => phaseLog.push(t), log: (m) => logLines.push(m) }
}

async function runWithResponder(plan, responder, order = 'fifo') {
  const { agent, calls } = autoAgent(responder, { order })
  const phaseLog = []
  const logLines = []
  const deps = makeDeps({ agent, phaseLog, logLines })
  const result = await CORE.runRetroAnalysis(plan, deps)
  return { result, calls, phaseLog, logLines }
}

function assertSchemaShapeValid(schema, path) {
  if (!schema) return
  if (schema.type === 'object') {
    const required = schema.required || []
    const props = schema.properties || {}
    for (const r of required) assert.ok(r in props, `${path}: required "${r}" missing from properties`)
    for (const [key, sub] of Object.entries(props)) assertSchemaShapeValid(sub, `${path}.${key}`)
  }
  if (schema.type === 'array' && schema.items) assertSchemaShapeValid(schema.items, `${path}[]`)
}

// ── RA1: makeShards / projectAgents.match ────────────────────────────────────

test('RA1: makeShards partitions trends/observations/retros exactly, in KINDS order, labeled match:<kind>:<n>; projectAgents.match equals shard count', () => {
  const trends = Array.from({ length: 12 }, (_, i) => entry('t' + i, 'Trend ' + i))
  const observations = Array.from({ length: 6 }, (_, i) => entry('o' + i, 'Obs ' + i))
  const plan = buildPlan({ trends, observations, shardSize: 5 })
  const shards = CORE.makeShards(plan)
  assert.deepEqual(shards.map((s) => s.label), [
    'match:trend:1', 'match:trend:2', 'match:trend:3',
    'match:observation:1', 'match:observation:2',
  ])
  const gotTrendIds = shards.filter((s) => s.kind === 'trend').flatMap((s) => s.targets.map((t) => t.id))
  const gotObsIds = shards.filter((s) => s.kind === 'observation').flatMap((s) => s.targets.map((t) => t.id))
  assert.deepEqual(gotTrendIds, trends.map((t) => t.id))
  assert.deepEqual(gotObsIds, observations.map((o) => o.id))
  assert.equal(CORE.projectAgents(plan).match, shards.length)
})

// ── RA2: barrier ──────────────────────────────────────────────────────────────

test('RA2: assertBarrier holds between Match and Adjudicate even under reverse-order resolution', async () => {
  const trends = [entry('t1', 'Trend one'), entry('t2', 'Trend two')]
  const plan = buildPlan({ trends, shardSize: 5 })
  const responder = async (label) => {
    if (label.startsWith('match:')) return matchResult()
    if (label === 'cluster') return clusterResultFx()
    throw new Error('unexpected label ' + label)
  }
  const { calls } = await runWithResponder(plan, responder, 'reverse')
  assertBarrier(calls, 'Match', 'Adjudicate')
})

// ── RA3: null matcher ─────────────────────────────────────────────────────────

test('RA3: a null matcher lists its shard in stats.unmatchedShards and the run still completes', async () => {
  const trends = [entry('t1', 'Trend one')]
  const observations = [entry('o1', 'Obs one')]
  const plan = buildPlan({ trends, observations, shardSize: 5 })
  const responder = async (label) => {
    if (label === 'match:trend:1') return null
    if (label.startsWith('match:')) return matchResult()
    if (label === 'cluster') return clusterResultFx()
    throw new Error('unexpected label ' + label)
  }
  const { result, logLines } = await runWithResponder(plan, responder)
  assert.equal(result.started, true)
  assert.deepEqual(result.stats.unmatchedShards, [{ label: 'match:trend:1', kind: 'trend', targetIds: ['t1'] }])
  assert.ok(logLines.some((l) => l.includes('WARNING: no result from match:trend:1')))
})

// ── RA4: determinism / idempotence ───────────────────────────────────────────

test('RA4: assembleResult is deterministic under fifo vs reverse completion order, and idempotent given the same ctx', async () => {
  const trends = Array.from({ length: 7 }, (_, i) => entry('t' + (i + 1), 'Trend ' + (i + 1)))
  const observations = Array.from({ length: 6 }, (_, i) => entry('o' + (i + 1), 'Obs ' + (i + 1)))
  const findings = [findingFx('f1'), findingFx('f2')]
  const plan = buildPlan({ mode: 'deep', findings, trends, observations, shardSize: 5 })
  assert.ok(CORE.makeShards(plan).filter((s) => s.kind === 'trend').length >= 2)
  assert.ok(CORE.makeShards(plan).filter((s) => s.kind === 'observation').length >= 2)
  const responder = async (label) => {
    if (label === 'match:trend:1') {
      return matchResult({
        matches: [
          { fid: 'f1', targetId: 't1', strength: 'weak', evidence: 'e1', sessionsSeen: 2 },
          { fid: 'f2', targetId: 't1', strength: 'weak', evidence: 'e2', sessionsSeen: 2 },
          { fid: 'f2', targetId: 't2', strength: 'strong', evidence: 'e3', sessionsSeen: 3 },
        ],
      })
    }
    if (label === 'match:trend:2') {
      return matchResult({ matches: [{ fid: 'f2', targetId: 't6', strength: 'weak', evidence: 'e4', sessionsSeen: 1 }] })
    }
    if (label.startsWith('match:')) return matchResult()
    if (label === 'adjudicate:f2') return adjudicateResult({ decision: 'match', trendId: 't2' })
    if (label === 'cluster') return clusterResultFx()
    throw new Error('unexpected label ' + label)
  }
  const a = await runWithResponder(plan, responder, 'fifo')
  const b = await runWithResponder(plan, responder, 'reverse')
  assert.deepEqual(a.result, b.result)
  assert.ok(a.result.matched.length >= 2)
  assert.ok(a.result.matched.some((m) => m.fid === 'f2' && m.trendId === 't2'))

  const shards = CORE.makeShards(plan)
  const collected = CORE.collectMatches(plan, shards, shards.map(() => matchResult()))
  const routed = CORE.routeFindings(plan, collected)
  const ctx = { shards, collected, routed, adjResults: [], clusterResult: clusterResultFx(), projected: CORE.projectAgents(plan) }
  assert.deepEqual(CORE.assembleResult(plan, ctx), CORE.assembleResult(plan, ctx))
})

// ── RA5: read-only / no forbidden tokens ─────────────────────────────────────

test('RA5: no forbidden APIs in script text; every prompt starts READ-ONLY. and names no write tool, select:, or isolation', async () => {
  assert.deepEqual(scanForbiddenApis(scriptText(SCRIPT_PATH)), [])

  const trends = [entry('t1', 'Trend one')]
  const observations = [entry('o1', 'Obs one')]
  const findings = [findingFx('f1'), findingFx('f2')]
  const plan = buildPlan({ mode: 'deep', findings, trends, observations, shardSize: 5 })

  const shard = CORE.makeShards(plan)[0]
  const mp = CORE.matchPrompt(plan, shard)
  const ap = CORE.adjudicatePrompt(plan, findings[0], [{ trendId: 't1', strength: 'weak', evidence: 'e' }])
  const cp = CORE.clusterPrompt(plan, { orphans: findings, uncovered: observations, clusters: [] })

  const WRITE_TOOLS = [
    'manage_items', 'manage_notes', 'advance_item', 'create_work_tree', 'manage_plan_documents',
    'manage_dependencies', 'complete_tree', 'claim_item', 'manage_project_config',
  ]
  const text = scriptText(SCRIPT_PATH)
  for (const w of WRITE_TOOLS) assert.equal(text.includes(w), false, `script text must not name ${w}`)
  assert.equal(text.includes('select:'), false)
  assert.equal(text.includes('isolation'), false)

  for (const p of [mp, ap, cp]) {
    assert.ok(p.startsWith('READ-ONLY.'), 'prompt must start with READ-ONLY.')
    for (const w of WRITE_TOOLS) assert.equal(p.includes(w), false, `prompt must not name ${w}`)
    assert.equal(p.includes('select:'), false)
    assert.equal(p.includes('isolation'), false)
  }

  const { calls } = await runWithResponder(plan, async (label) => {
    if (label.startsWith('match:')) return matchResult()
    if (label === 'cluster') return clusterResultFx()
    throw new Error('unexpected ' + label)
  })
  for (const c of calls) assert.equal(c.opts.isolation, undefined)
})

test('RA5b (O4): the match prompt defines strong vs weak, sessionsSeen, lastSeen, and (deep mode only) clusters', () => {
  const trends = [entry('t1', 'Trend one')]
  const deepPlan = buildPlan({ mode: 'deep', findings: [findingFx('f1')], trends, shardSize: 5 })
  const auditPlan = buildPlan({ mode: 'audit', trends, shardSize: 5 })
  for (const plan of [deepPlan, auditPlan]) {
    const p = CORE.matchPrompt(plan, CORE.makeShards(plan)[0])
    assert.match(p, /"strong" means/)
    assert.match(p, /"weak" means/)
    assert.match(p, /sessionsSeen is the number from a "Sessions: N" line[^.]*else 0/)
    assert.match(p, /lastSeen[^.]*YYYY-MM-DD/)
  }
  assert.match(CORE.matchPrompt(deepPlan, CORE.makeShards(deepPlan)[0]), /clusters: 2 or more shard targets that share a pattern no finding covers/)
  assert.doesNotMatch(CORE.matchPrompt(auditPlan, CORE.makeShards(auditPlan)[0]), /no finding covers/)
})

test('RA14b (D1/O9): plan-to-execution is an accepted finding dimension, in DIMENSIONS and the cluster prompt', () => {
  assert.ok(CORE.DIMENSIONS.includes('plan-to-execution'))
  const r = CORE.normalizeArgs(baseArgs({ mode: 'deep', findings: [findingFx('f1', { dimension: 'plan-to-execution' })], trends: [entry('t1', 'T')] }))
  assert.equal(r.ok, true, r.reason)
  const plan = r.plan
  assert.match(CORE.clusterPrompt(plan, { orphans: [], uncovered: [], clusters: [] }), /plan-to-execution/)
})

// ── RA6: audit mode, empty findings ──────────────────────────────────────────

test('RA6: audit mode with findings:[] runs the cluster agent once, no adjudicators, and newTrends carries the cluster output', async () => {
  const observations = [entry('o1', 'Obs one'), entry('o2', 'Obs two')]
  const plan = buildPlan({ mode: 'audit', observations, shardSize: 5 })
  const responder = async (label) => {
    if (label.startsWith('match:')) {
      return matchResult({ clusters: [{ label: 'c1', claim: 'pattern', dimension: 'friction', targetIds: ['o1', 'o2'] }] })
    }
    if (label === 'cluster') {
      return clusterResultFx({
        newTrends: [{ kebabKey: 'shared-pattern', claim: 'shared pattern', dimension: 'friction', fids: [], observationIds: ['o1', 'o2'], evidenceRefs: [] }],
      })
    }
    throw new Error('unexpected ' + label)
  }
  const { result, calls } = await runWithResponder(plan, responder)
  assert.equal(calls.filter((c) => c.label.startsWith('adjudicate:')).length, 0)
  assert.equal(calls.filter((c) => c.label === 'cluster').length, 1)
  assert.equal(result.newTrends.length, 1)
  assert.deepEqual(result.newTrends[0].observationIds.sort(), ['o1', 'o2'])
})

// ── RA7: null adjudicator / null cluster ─────────────────────────────────────

test('RA7: a null adjudicator falls back to pickCandidate and is recorded missing; a null cluster leaves orphans unresolved and is recorded missing', async () => {
  const trends = [entry('t1', 'Trend one'), entry('t2', 'Trend two')]
  const findings = [findingFx('f1'), findingFx('f2')]
  const plan = buildPlan({ mode: 'deep', findings, trends, shardSize: 5 })
  const responder = async (label) => {
    if (label.startsWith('match:')) {
      return matchResult({
        matches: [
          { fid: 'f1', targetId: 't1', strength: 'strong', evidence: 'e1', sessionsSeen: 2 },
          { fid: 'f1', targetId: 't2', strength: 'weak', evidence: 'e2', sessionsSeen: 1 },
        ],
      })
    }
    if (label === 'adjudicate:f1') return null
    if (label === 'cluster') return null
    throw new Error('unexpected ' + label)
  }
  const { result } = await runWithResponder(plan, responder)
  const m1 = result.matched.find((m) => m.fid === 'f1')
  assert.equal(m1.via, 'fallback')
  assert.equal(m1.trendId, 't1')
  assert.ok(result.stats.missing.includes('adjudicate:f1'))
  assert.ok(result.stats.missing.includes('cluster'))
  assert.ok(result.unresolved.includes('f2'))
})

// ── RA8: partition invariant ──────────────────────────────────────────────────

test('RA8: matched, newTrends.fids, and unresolved are a disjoint partition of every finding fid', async () => {
  const trends = [entry('t1', 'Trend one'), entry('t2', 'Trend two')]
  const findings = [findingFx('f1'), findingFx('f2'), findingFx('f3'), findingFx('f4')]
  const plan = buildPlan({ mode: 'deep', findings, trends, shardSize: 5, maxAdjudicate: 1 })
  const responder = async (label) => {
    if (label.startsWith('match:')) {
      return matchResult({
        matches: [
          { fid: 'f1', targetId: 't1', strength: 'weak', evidence: 'e', sessionsSeen: 1 },
          { fid: 'f2', targetId: 't1', strength: 'weak', evidence: 'e', sessionsSeen: 1 },
          { fid: 'f2', targetId: 't2', strength: 'strong', evidence: 'e', sessionsSeen: 1 },
          { fid: 'f3', targetId: 't1', strength: 'weak', evidence: 'e', sessionsSeen: 1 },
          { fid: 'f3', targetId: 't2', strength: 'weak', evidence: 'e', sessionsSeen: 1 },
        ],
      })
    }
    if (label === 'adjudicate:f2') return adjudicateResult({ decision: 'new', kebabKey: 'brand-new', claim: 'c' })
    if (label === 'cluster') {
      return clusterResultFx({ newTrends: [{ kebabKey: 'orphan-cluster', claim: 'c', dimension: 'friction', fids: ['f4'], observationIds: [], evidenceRefs: [] }] })
    }
    throw new Error('unexpected ' + label)
  }
  const { result } = await runWithResponder(plan, responder)
  const allFids = findings.map((f) => f.fid)
  const matchedFids = result.matched.map((m) => m.fid)
  const newTrendFids = result.newTrends.flatMap((n) => n.fids)
  const union = matchedFids.concat(newTrendFids).concat(result.unresolved)
  assert.deepEqual(union.slice().sort(), allFids.slice().sort())
  assert.equal(new Set(union).size, union.length)
  const viaOf = (fid) => (result.matched.find((m) => m.fid === fid) || {}).via
  assert.equal(viaOf('f1'), 'match')
  assert.equal(result.newTrends.find((n) => n.kebabKey === 'brand-new').fids.includes('f2'), true)
  assert.equal(viaOf('f3'), 'cap')
  assert.equal(result.newTrends.find((n) => n.kebabKey === 'orphan-cluster').fids.includes('f4'), true)
})

// ── RA9: capping ───────────────────────────────────────────────────────────────

test('RA9: capped adjudication is logged and reflected in stats.adjudicationCapped', async () => {
  const trends = [entry('t1', 'Trend one'), entry('t2', 'Trend two')]
  const findings = [findingFx('f1'), findingFx('f2')]
  const plan = buildPlan({ mode: 'deep', findings, trends, shardSize: 5, maxAdjudicate: 1 })
  const responder = async (label) => {
    if (label.startsWith('match:')) {
      return matchResult({
        matches: [
          { fid: 'f1', targetId: 't1', strength: 'strong', evidence: 'e', sessionsSeen: 1 },
          { fid: 'f1', targetId: 't2', strength: 'weak', evidence: 'e', sessionsSeen: 1 },
          { fid: 'f2', targetId: 't1', strength: 'weak', evidence: 'e', sessionsSeen: 1 },
          { fid: 'f2', targetId: 't2', strength: 'weak', evidence: 'e', sessionsSeen: 1 },
        ],
      })
    }
    if (label === 'adjudicate:f1') return adjudicateResult({ decision: 'match', trendId: 't1' })
    if (label === 'cluster') return clusterResultFx()
    throw new Error('unexpected ' + label)
  }
  const { result, logLines } = await runWithResponder(plan, responder)
  assert.deepEqual(result.stats.adjudicationCapped, ['f2'])
  assert.ok(logLines.some((l) => l.includes('adjudication capped: f2')))
})

// ── RA10: key-collision ────────────────────────────────────────────────────────

test('RA10: a new-trend kebabKey equal to an existing trend\'s trendKey(title) resolves as a match via key-collision', async () => {
  const trends = [entry('t1', 'trend: shared-pattern narrative title')]
  const findings = [findingFx('f1')]
  const plan = buildPlan({ mode: 'deep', findings, trends, shardSize: 5 })
  const responder = async (label) => {
    if (label.startsWith('match:')) return matchResult()
    if (label === 'cluster') {
      return clusterResultFx({ newTrends: [{ kebabKey: 'shared-pattern', claim: 'c', dimension: 'friction', fids: ['f1'], observationIds: [], evidenceRefs: [] }] })
    }
    throw new Error('unexpected ' + label)
  }
  const { result } = await runWithResponder(plan, responder)
  assert.equal(result.newTrends.length, 0)
  const m = result.matched.find((mm) => mm.fid === 'f1')
  assert.equal(m.via, 'key-collision')
  assert.equal(m.trendId, 't1')
  assert.equal(m.confidence, 'weak')
})

test('RA10b (O3): a key-collision match keeps its fid\'s non-trend evidence refs and the new trend\'s evidenceRefs', async () => {
  const trends = [entry('t1', 'trend: shared-pattern narrative title')]
  const observations = [entry('o1', 'Obs one')]
  const retros = [entry('r1', 'Retro one'), entry('r2', 'Retro two')]
  const findings = [findingFx('f1')]
  const plan = buildPlan({ mode: 'deep', findings, trends, observations, retros, shardSize: 5 })
  const responder = async (label) => {
    if (label === 'match:observation:1') {
      return matchResult({ matches: [{ fid: 'f1', targetId: 'o1', strength: 'weak', evidence: 'e', sessionsSeen: 0 }] })
    }
    if (label.startsWith('match:')) return matchResult()
    if (label === 'cluster') {
      return clusterResultFx({ newTrends: [{ kebabKey: 'shared-pattern', claim: 'c', dimension: 'friction', fids: ['f1'], observationIds: ['o1'], evidenceRefs: ['r1'] }] })
    }
    throw new Error('unexpected ' + label)
  }
  const { result } = await runWithResponder(plan, responder)
  const m = result.matched.find((mm) => mm.fid === 'f1')
  assert.equal(m.via, 'key-collision')
  assert.deepEqual(m.evidenceRefs.slice().sort(), ['o1', 'r1'])
  assert.equal(result.observationLinks.find((l) => l.observationId === 'o1').trendId, 't1')
})

test('RA10c (O3): an audit-mode key-collision with no fids still links its observationIds to the colliding trend', async () => {
  const trends = [entry('t1', 'trend: shared-pattern narrative title')]
  const observations = [entry('o1', 'Obs one'), entry('o2', 'Obs two')]
  const plan = buildPlan({ mode: 'audit', trends, observations, shardSize: 5 })
  const responder = async (label) => {
    if (label.startsWith('match:')) return matchResult()
    if (label === 'cluster') {
      return clusterResultFx({ newTrends: [{ kebabKey: 'shared-pattern', claim: 'c', dimension: 'friction', fids: [], observationIds: ['o1'], evidenceRefs: [] }] })
    }
    throw new Error('unexpected ' + label)
  }
  const { result } = await runWithResponder(plan, responder)
  assert.equal(result.newTrends.length, 0)
  const byId = Object.fromEntries(result.observationLinks.map((l) => [l.observationId, l]))
  assert.equal(byId.o1.trendId, 't1')
  assert.equal(byId.o2.trendId, null)
})

// ── RA17 (O1): buildMatchedEntry precedence ──────────────────────────────────────

test('RA17 (O1): sessionsBefore prefers trendState, then the first sessionsSeen, then null; strong evidence wins; evidenceRefs are distinct non-trend targets', () => {
  const mk = (byFid, trendState) => ({ byFid, trendState })
  const weak = { kind: 'trend', targetId: 't1', strength: 'weak', evidence: 'weak-ev', sessionsSeen: 4 }
  const strong = { kind: 'trend', targetId: 't1', strength: 'strong', evidence: 'strong-ev', sessionsSeen: 9 }

  const a = CORE.buildMatchedEntry(mk({ f1: [weak, strong] }, { t1: { sessions: 7 } }), 'f1', 't1', 'match')
  assert.equal(a.sessionsBefore, 7)
  const b = CORE.buildMatchedEntry(mk({ f1: [weak, strong] }, {}), 'f1', 't1', 'match')
  assert.equal(b.sessionsBefore, 4)
  const c = CORE.buildMatchedEntry(mk({ f1: [{ ...weak, sessionsSeen: undefined }] }, {}), 'f1', 't1', 'match')
  assert.equal(c.sessionsBefore, null)

  assert.equal(a.evidence, 'strong-ev')
  assert.equal(a.confidence, 'strong')
  const w = CORE.buildMatchedEntry(mk({ f1: [weak] }, {}), 'f1', 't1', 'match')
  assert.equal(w.confidence, 'weak')
  assert.equal(w.evidence, 'weak-ev')

  const refs = CORE.buildMatchedEntry(mk({ f1: [
    weak,
    { kind: 'observation', targetId: 'o1', strength: 'weak', evidence: 'x' },
    { kind: 'observation', targetId: 'o1', strength: 'strong', evidence: 'y' },
    { kind: 'retro', targetId: 'r1', strength: 'weak', evidence: 'z' },
  ] }, {}), 'f1', 't1', 'match')
  assert.deepEqual(refs.evidenceRefs, ['o1', 'r1'])
})

test('RA17b (O1): end to end, match, cap and adjudicate paths each carry sessionsBefore and confidence from the collected entries', async () => {
  const trends = [entry('t1', 'Trend one'), entry('t2', 'Trend two'), entry('t3', 'Trend three')]
  const findings = [findingFx('f1'), findingFx('f2'), findingFx('f3')]
  const plan = buildPlan({ mode: 'deep', findings, trends, shardSize: 5, maxAdjudicate: 1 })
  const responder = async (label) => {
    if (label.startsWith('match:')) {
      return matchResult({
        matches: [
          { fid: 'f1', targetId: 't1', strength: 'strong', evidence: 'ev1', sessionsSeen: 5 },
          { fid: 'f2', targetId: 't2', strength: 'weak', evidence: 'ev2a', sessionsSeen: 2 },
          { fid: 'f2', targetId: 't3', strength: 'strong', evidence: 'ev2b', sessionsSeen: 8 },
          { fid: 'f3', targetId: 't2', strength: 'weak', evidence: 'ev3a', sessionsSeen: 2 },
          { fid: 'f3', targetId: 't3', strength: 'weak', evidence: 'ev3b', sessionsSeen: 8 },
        ],
        trendState: [{ trendId: 't1', sessions: 6, lastSeen: '2026-09-27' }],
      })
    }
    if (label === 'adjudicate:f2') return adjudicateResult({ decision: 'match', trendId: 't2' })
    if (label === 'cluster') return clusterResultFx()
    throw new Error('unexpected ' + label)
  }
  const { result } = await runWithResponder(plan, responder)
  const by = (fid) => result.matched.find((m) => m.fid === fid)
  assert.equal(by('f1').via, 'match')
  assert.equal(by('f1').sessionsBefore, 6)
  assert.equal(by('f1').confidence, 'strong')
  assert.equal(by('f2').via, 'adjudicate')
  assert.equal(by('f2').sessionsBefore, 2)
  assert.equal(by('f2').confidence, 'weak')
  assert.equal(by('f3').via, 'cap')
  assert.equal(typeof by('f3').sessionsBefore, 'number')
})

// ── RA11: staleTrends ───────────────────────────────────────────────────────────

test('RA11: an unmatched trend older than staleDays is listed in staleTrends; a recent one is not', async () => {
  const trends = [entry('t1', 'Trend one'), entry('t2', 'Trend two')]
  const plan = buildPlan({ mode: 'audit', trends, shardSize: 5, staleDays: 30 })
  const responder = async (label) => {
    if (label.startsWith('match:')) {
      return matchResult({
        trendState: [
          { trendId: 't1', sessions: 3, lastSeen: '2026-01-01' },
          { trendId: 't2', sessions: 5, lastSeen: '2026-09-27' },
        ],
      })
    }
    if (label === 'cluster') return clusterResultFx()
    throw new Error('unexpected ' + label)
  }
  const { result } = await runWithResponder(plan, responder)
  assert.deepEqual(result.staleTrends, [{ trendId: 't1', lastSeen: '2026-01-01' }])
})

// ── RA12: observationLinks ──────────────────────────────────────────────────────

test('RA12: observationLinks prefer a matched-fid link, then a cluster link to a known trend, else null; newTrendKey when covered by a new trend', async () => {
  const trends = [entry('t1', 'Trend one')]
  const observations = [entry('o1', 'Obs one'), entry('o2', 'Obs two'), entry('o3', 'Obs three')]
  const findings = [findingFx('f1')]
  const plan = buildPlan({ mode: 'deep', findings, trends, observations, shardSize: 5 })
  const responder = async (label) => {
    if (label.startsWith('match:')) {
      return matchResult({
        matches: [
          { fid: 'f1', targetId: 't1', strength: 'strong', evidence: 'e', sessionsSeen: 1 },
          { fid: 'f1', targetId: 'o1', strength: 'strong', evidence: 'e', sessionsSeen: 1 },
        ],
      })
    }
    if (label === 'cluster') {
      return clusterResultFx({
        observationLinks: [
          { observationId: 'o2', trendId: 't1' },
          { observationId: 'o3', trendId: 'unknown-trend-id' },
        ],
        newTrends: [{ kebabKey: 'k1', claim: 'c', dimension: 'friction', fids: [], observationIds: ['o3'], evidenceRefs: [] }],
      })
    }
    throw new Error('unexpected ' + label)
  }
  const { result } = await runWithResponder(plan, responder)
  const byId = Object.fromEntries(result.observationLinks.map((l) => [l.observationId, l]))
  assert.equal(byId.o1.trendId, 't1')
  assert.equal(byId.o1.newTrendKey, null)
  assert.equal(byId.o2.trendId, 't1')
  assert.equal(byId.o3.trendId, null)
  assert.equal(byId.o3.newTrendKey, 'k1')
})

// ── RA13: droppedMatches ─────────────────────────────────────────────────────────

test('RA13: collectMatches drops (and counts) a deep-mode match with an unknown fid or an out-of-shard targetId', () => {
  const trends = [entry('t1', 'Trend one')]
  const findings = [findingFx('f1')]
  const plan = buildPlan({ mode: 'deep', findings, trends, shardSize: 5 })
  const shards = CORE.makeShards(plan)
  const results = [
    matchResult({
      matches: [
        { fid: 'f1', targetId: 't1', strength: 'weak', evidence: 'e', sessionsSeen: 1 },
        { fid: 'unknown-fid', targetId: 't1', strength: 'weak', evidence: 'e', sessionsSeen: 1 },
        { fid: 'f1', targetId: 'not-in-shard', strength: 'weak', evidence: 'e', sessionsSeen: 1 },
      ],
    }),
  ]
  const collected = CORE.collectMatches(plan, shards, results)
  assert.equal(collected.dropped, 2)
  assert.equal(collected.byFid.f1.length, 1)
})

// ── RA14: normalizeArgs reasons / string-JSON parity / defaults ─────────────────

test('RA14: normalizeArgs enforces every documented reason in order, with string-JSON parity and correct defaults', () => {
  const N = CORE.normalizeArgs
  const withContract = (extra) => Object.assign({ contract: 'retro-analysis/args-v1' }, extra)

  assert.equal(N('not json{').ok, false)
  assert.equal(N('not json{').reason, 'invalid args: bad JSON')

  assert.equal(N({}).reason, 'invalid args: contract must be retro-analysis/args-v1')
  assert.equal(N({ contract: 'wrong' }).reason, 'invalid args: contract must be retro-analysis/args-v1')

  assert.equal(N(withContract({})).reason, 'invalid args: missing runId')
  assert.equal(N(withContract({ runId: 'ra-1' })).reason, 'invalid args: missing date')
  assert.equal(N(withContract({ runId: 'ra-1', date: '2026-09-28' })).reason, 'invalid args: missing mode')

  assert.equal(N(withContract({ runId: 'bad-1', date: '2026-09-28', mode: 'audit' })).reason, 'invalid args: runId must start with ra-')
  assert.equal(N(withContract({ runId: 'ra-1', date: 'bad-date', mode: 'audit' })).reason, 'invalid args: date must be YYYY-MM-DD')
  assert.equal(N(withContract({ runId: 'ra-1', date: '2026-09-28', mode: 'weird' })).reason, 'invalid args: unknown mode weird')

  const validCore = { runId: 'ra-1', date: '2026-09-28', mode: 'audit' }
  assert.equal(N(withContract({ ...validCore, shardSize: 4 })).reason, 'invalid args: shardSize must be 5-30')
  assert.equal(N(withContract({ ...validCore, shardSize: 31 })).reason, 'invalid args: shardSize must be 5-30')
  assert.equal(N(withContract({ ...validCore, shardSize: 5.5 })).reason, 'invalid args: shardSize must be 5-30')

  for (const bad of [0, -1, 2.5, 'x']) {
    assert.equal(N(withContract({ ...validCore, maxAgents: bad })).reason, 'invalid args: maxAgents must be a positive integer')
    assert.equal(N(withContract({ ...validCore, staleDays: bad })).reason, 'invalid args: staleDays must be a positive integer')
  }
  for (const bad of [-1, 1.5, 'x']) {
    assert.equal(N(withContract({ ...validCore, maxAdjudicate: bad })).reason, 'invalid args: maxAdjudicate must be an integer, 0 or more')
  }
  assert.equal(N(withContract({ ...validCore, maxAdjudicate: 0, trends: [entry('t1', 'T')] })).ok, true)
  assert.equal(CORE.kebab(undefined), '')
  assert.equal(CORE.kebab(null), '')
  assert.equal(CORE.kebab('Hello World!'), 'hello-world')

  assert.equal(N(withContract({ ...validCore, findings: 'nope' })).reason, 'invalid args: findings must be an array')
  const tooManyFindings = Array.from({ length: 41 }, (_, i) => findingFx('f' + i))
  assert.equal(N(withContract({ ...validCore, findings: tooManyFindings })).reason, 'invalid args: too many findings (41 > 40)')
  assert.equal(N(withContract({ ...validCore, findings: [{}] })).reason, 'invalid args: bad finding 0')
  assert.equal(N(withContract({ ...validCore, findings: [findingFx('f1', { dimension: 'nope' })] })).reason, 'invalid args: bad finding 0')
  assert.equal(N(withContract({ ...validCore, findings: [findingFx('f1'), findingFx('f1')] })).reason, 'invalid args: duplicate fid f1')

  assert.equal(N(withContract({ ...validCore, mode: 'deep', findings: [] })).reason, 'invalid args: deep mode needs findings')
  assert.equal(N(withContract({ ...validCore, mode: 'audit', findings: [findingFx('f1')] })).reason, 'invalid args: audit mode takes no findings')

  for (const { key, cap } of [{ key: 'trends', cap: 200 }, { key: 'observations', cap: 200 }, { key: 'retros', cap: 30 }]) {
    assert.equal(N(withContract({ ...validCore, [key]: 'nope' })).reason, `invalid args: ${key} must be an array`)
    const tooMany = Array.from({ length: cap + 1 }, (_, i) => entry(key[0] + i, 'T'))
    assert.equal(N(withContract({ ...validCore, [key]: tooMany })).reason, `invalid args: too many ${key} (${cap + 1} > ${cap})`)
    assert.equal(N(withContract({ ...validCore, [key]: [{ id: 'x' }] })).reason, `invalid args: bad ${key} entry 0`)
    assert.equal(N(withContract({ ...validCore, [key]: [entry('x', 'T'), entry('x', 'T2')] })).reason, `invalid args: duplicate ${key} id x`)
  }

  assert.equal(N(withContract(validCore)).reason, 'invalid args: nothing to match')

  assert.equal(
    N(withContract({ ...validCore, trends: [entry('t1', 'T')], planDocSlug: 'wrong-slug' })).reason,
    'invalid args: planDocSlug must be retro/ra-1'
  )
  assert.equal(
    N(withContract({ ...validCore, trends: [entry('t1', 'T')], planDocSlug: 'retro/ra-1' })).reason,
    'invalid args: planDocSlug requires rootId'
  )

  const validArgs = withContract({ ...validCore, trends: [entry('t1', 'T')] })
  const fromObject = N(validArgs)
  const fromString = N(JSON.stringify(validArgs))
  assert.equal(fromObject.ok, true)
  assert.deepEqual(fromObject.plan, fromString.plan)

  assert.equal(fromObject.plan.shardSize, 15)
  assert.equal(fromObject.plan.maxAgents, 30)
  assert.equal(fromObject.plan.allowLarge, false)
  assert.equal(fromObject.plan.maxAdjudicate, 12)
  assert.equal(fromObject.plan.staleDays, 60)
  assert.equal(fromObject.plan.rootId, null)
  assert.equal(fromObject.plan.planDocSlug, null)
  assert.deepEqual(fromObject.plan.observations, [])
  assert.deepEqual(fromObject.plan.retros, [])
})

// ── RA15: sizeGuard refusal ────────────────────────────────────────────────────

test('RA15: sizeGuard refuses an oversized run before any agent is launched', async () => {
  const trends = Array.from({ length: 100 }, (_, i) => entry('t' + i, 'T' + i))
  const args = {
    contract: 'retro-analysis/args-v1', runId: 'ra-big-0001', date: '2026-09-28', mode: 'audit',
    trends, shardSize: 5, maxAgents: 3, allowLarge: false,
  }
  let calls = 0
  const script = loadScript(SCRIPT_PATH)
  const result = await script.run({
    agent: async () => { calls += 1; return null },
    parallel: fakeParallel,
    phase: () => {},
    log: () => {},
    args,
  })
  assert.equal(result.started, false)
  assert.match(result.reason, /projected \d+ agents > maxAgents/)
  assert.equal(calls, 0)

  const plan = CORE.normalizeArgs(args).plan
  const projected = CORE.projectAgents(plan)
  assert.ok(projected.total > plan.maxAgents)
  const guard = CORE.sizeGuard(plan)
  assert.ok(guard)
  assert.equal(guard.reason, `projected ${projected.total} agents > maxAgents; pass allowLarge or a larger shardSize`)
})

// ── RA16: result shape ───────────────────────────────────────────────────────────

test('RA16: result-v1 has exactly the documented top-level and stats keys; planDocSlug and findings are echoed', async () => {
  const trends = [entry('t1', 'Trend one')]
  const findings = [findingFx('f1')]
  const plan = buildPlan({ mode: 'deep', findings, trends, rootId: 'root-1', planDocSlug: 'retro/ra-test-0001', shardSize: 5 })
  const responder = async (label) => {
    if (label.startsWith('match:')) return matchResult({ matches: [{ fid: 'f1', targetId: 't1', strength: 'weak', evidence: 'e', sessionsSeen: 1 }] })
    if (label === 'cluster') return clusterResultFx()
    throw new Error('unexpected ' + label)
  }
  const { result } = await runWithResponder(plan, responder)
  assert.deepEqual(Object.keys(result).sort(), [
    'contract', 'findings', 'matched', 'mode', 'newTrends', 'observationLinks', 'planDocSlug',
    'rootId', 'runId', 'staleTrends', 'started', 'stats', 'unresolved',
  ].sort())
  assert.deepEqual(Object.keys(result.stats).sort(), [
    'adjudicationCapped', 'adjudicators', 'droppedMatches', 'matchReturned', 'missing',
    'mode', 'projectedAgents', 'shards', 'unmatchedShards',
  ].sort())
  assert.equal(result.contract, 'retro-analysis/result-v1')
  assert.equal(result.planDocSlug, 'retro/ra-test-0001')
  assert.deepEqual(result.findings, findings)
})

// ── T-meta ─────────────────────────────────────────────────────────────────────

test('T-meta: meta is a pure literal; name and phases exact; whenToUse names both modes, --deep, required args, user-invoked-only, and read-only', () => {
  const meta = JSON.parse(JSON.stringify(loadMeta(SCRIPT_PATH)))
  assert.equal(meta.name, 'retro-analysis')
  assert.deepEqual(meta.phases, [{ title: 'Match' }, { title: 'Adjudicate' }])
  assert.equal(typeof meta.description, 'string')
  assert.ok(meta.description.length > 0)
  for (const term of ['audit', 'deep', '--deep', 'runId', 'date']) {
    assert.ok(meta.whenToUse.includes(term), `whenToUse must name "${term}"`)
  }
  assert.match(meta.whenToUse, /hook/i, 'whenToUse must say it is never launched from a hook')
  assert.match(meta.whenToUse, /background/i, 'whenToUse must say it is never launched from a background agent')
  assert.match(meta.whenToUse, /read-only/i, 'whenToUse must say the workflow is read-only')
})

// ── T-core-pure ────────────────────────────────────────────────────────────────

test('T-core-pure: core references no runtime global after stripping comments/strings; markers appear exactly once', () => {
  assert.deepEqual(freeRuntimeIds(coreSlice(SCRIPT_PATH)), [])
  const text = scriptText(SCRIPT_PATH)
  assert.equal((text.match(/\/\/ @core-begin/g) || []).length, 1)
  assert.equal((text.match(/\/\/ @core-end/g) || []).length, 1)
})

// ── T-schema ───────────────────────────────────────────────────────────────────

test('T-schema: schemas(plan) required is a subset of properties, recursively, for MATCH/ADJUDICATE/CLUSTER', () => {
  const plan = buildPlan({ trends: [entry('t1', 'T')] })
  const built = CORE.schemas(plan)
  for (const name of ['MATCH', 'ADJUDICATE', 'CLUSTER']) {
    assert.ok(built[name], `schemas(plan) must return ${name}`)
    assertSchemaShapeValid(built[name], name)
  }
})

// ── T-rule ─────────────────────────────────────────────────────────────────────

test('T-rule: script contains no verbatim 8-token window from any served rule', () => {
  const { hits, windows } = ruleWindowHits(scriptText(SCRIPT_PATH), RULES_DIR)
  assert.deepEqual(hits, [])
  assert.ok(windows >= 1, 'expected at least one rule window to have been compared (non-vacuous)')
})

test('T-rule red-proof support: an empty rules dir compares zero windows (never a silent pass)', () => {
  const emptyDir = mkdtempSync(join(tmpdir(), 'retro-analysis-rules-empty-'))
  const { hits, windows } = ruleWindowHits(scriptText(SCRIPT_PATH), emptyDir)
  assert.deepEqual(hits, [])
  assert.equal(windows, 0)
})

// ── T-repo ─────────────────────────────────────────────────────────────────────

test('T-repo: script text contains no repo-identifying strings', () => {
  const text = scriptText(SCRIPT_PATH)
  for (const s of ['current/src', 'D:/', 'D:\\', 'mcptask', 'jpicklyk', 'claude-plugins/']) {
    assert.equal(text.includes(s), false, `script text must not contain ${JSON.stringify(s)}`)
  }
})

// ── T-deep ─────────────────────────────────────────────────────────────────────

test('T-deep: the Deep mode heading appears exactly once, between 4.3 and 4.4, at or under 25 lines, and names retro/<runId> plus the args contract', () => {
  const text = readFileSync(SKILL_PATH, 'utf8')

  const headingMatches = text.match(/^#### Deep mode/gm) || []
  assert.equal(headingMatches.length, 1)

  const nameMatches = text.match(/task-orchestrator:retro-analysis/g) || []
  assert.equal(nameMatches.length, 1)

  const i43 = text.indexOf('### 4.3 Match findings')
  const iDeep = text.indexOf('#### Deep mode')
  const i44 = text.indexOf('### 4.4 Fetch full evidence (only when it matters)')
  assert.ok(i43 !== -1 && iDeep !== -1 && i44 !== -1)
  assert.ok(i43 < iDeep && iDeep < i44)

  const block = text.slice(iDeep, i44).replace(/\n+$/, '')
  const lineCount = block.split('\n').length
  assert.ok(lineCount <= 25, `deep block is ${lineCount} lines, expected <= 25`)

  assert.match(block, /retro\/<runId>/)
  assert.match(block, /retro-analysis\/args-v1/)
})

// ── T-skill-followups (D1/D2/D4/O10/O11) ─────────────────────────────────────

test('T-skill-followups: session-retrospective names plan-to-execution, --deep in its hint, the audit sweep, unmatchedShards, and the sorted retro query', () => {
  const text = readFileSync(SKILL_PATH, 'utf8')
  assert.match(text.split('\n').find((l) => l.startsWith('argument-hint:')), /--deep/)
  assert.match(text, /3d `plan-to-execution`/)
  assert.match(text, /`<dimension>` is one of [^\n]*plan-to-execution/)
  assert.match(text, /\*\*Audit sweep\.\*\*[^\n]*only after they confirm/)
  assert.match(text, /stats\.unmatchedShards[^\n]*targetIds/)
  assert.match(text, /tags="session-retrospective", sortBy="createdAt", sortOrder="desc", limit=10/)
  assert.equal((text.match(/task-orchestrator:retro-analysis/g) || []).length, 1)
})
