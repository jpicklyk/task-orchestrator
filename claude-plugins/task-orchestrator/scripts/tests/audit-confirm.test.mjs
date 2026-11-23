// Coverage for audit-confirm (lib + CLI), item 53c1c970. Scenario ids S1-S13 match the item's `test-plan`
// note. Oracles: the item's `task-scope` note (decisions D1-D8, acceptance criteria 1-4) and the
// carry-in contract C8; every expected value below is derived from those clauses and recomputed by hand,
// never read from the implementation. The suite imports only the declared exports `confirmMeasured` and
// `primaryPath`, spawns the declared CLI, and loads the workflow's own `primaryPath` through the test
// harness for the parity scenario (S13).

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdtempSync, rmSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'

import { confirmMeasured, primaryPath } from '../lib/audit-confirm.mjs'
import { loadCoreNamed } from './workflow-harness-ext.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const CLI = join(HERE, '..', 'audit-confirm.mjs')
const WORKFLOW = join(PLUGIN, 'workflows', 'deep-codebase-audit.js')
const VOCAB = JSON.parse(readFileSync(join(HERE, '..', 'lib', 'bug-report-vocabulary.json'), 'utf8'))

const clone = (v) => JSON.parse(JSON.stringify(v))
function deepFreeze(v) {
  if (v && typeof v === 'object' && !Object.isFrozen(v)) {
    Object.freeze(v)
    for (const k of Object.keys(v)) deepFreeze(v[k])
  }
  return v
}

// ── fixtures (every value is hand-written; dependent fields are derived from each other) ─────────

const CONTRACT = 'audit/metrics-v1'
/** A metrics-v1 files[] row: all four claimable fields are finite numbers. */
const frow = (path, over = {}) => ({
  path, lang: 'kotlin', loc: 100, churn: 10, churnRecent: 5, indentComplexity: 200, hotspotScore: 0.5, ...over,
})
const metricsOf = (files, extra = {}) => ({ contract: CONTRACT, files, hotSet: [], modules: { cycles: [] }, ...extra })

let seq = 0
/** A kept row awaiting confirmation: metrics-pending, location on `path`, claim as given. */
function pend(path, claim, over = {}) {
  seq += 1
  return {
    findingId: `f-${seq}`,
    reportId: `R${String(seq).padStart(2, '0')}`,
    title: `Title ${seq}`,
    severity: 'medium',
    verdict: 'UNVERIFIED',
    unverifiedReason: 'metrics-pending',
    verifiedBy: 'none',
    category: 'hotspot',
    effort: 'S',
    locations: [`${path}:3`],
    stableId: `F-${String(seq).padStart(8, '0')}`,
    basis: 'measured',
    measuredClaim: claim,
    ...over,
  }
}
const num = (field, value) => ({ field, value })
const cyc = (...modules) => ({ field: 'cycle', modules })
/** A kept row the agents judged (no claim, nothing pending). */
function judged(path, over = {}) {
  seq += 1
  return {
    findingId: `f-${seq}`,
    reportId: `R${String(seq).padStart(2, '0')}`,
    title: `Title ${seq}`,
    severity: 'low',
    verdict: 'CONFIRMED',
    verifiedBy: 'agents',
    category: 'cohesion',
    effort: 'S',
    locations: [`${path}:1`],
    stableId: `F-${String(seq).padStart(8, '0')}`,
    basis: 'judgement',
    ...over,
  }
}
const stats0 = { raw: 77, merged: 68, kept: 0, confirmed: 0, plausible: 0, unverified: 0, refuted: 0, unverifiedByCap: 4 }
const result = (kept, extra = {}) => ({
  contract: 'audit/result-v2', runId: 'a-202611220000', date: '2026-11-22', kept, refuted: [], stats: { ...stats0 }, report: null, proposal: null, ...extra,
})
const byId = (rows, id) => rows.find((r) => r.findingId === id)

function scratch(t) {
  const dir = mkdtempSync(join(tmpdir(), 'audit-confirm-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  return dir
}
const runCli = (args) => spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8' })

// ── S1 a numeric claim inside the tolerance is CONFIRMED in place (D2, D4) ───────────────────────

test('S1 churn claimed 40, measured 42: CONFIRMED, verifiedBy metrics, reason gone, same index', () => {
  const a = judged('x/a.kt')
  const b = pend('x/b.kt', num('churn', 40))
  const c = judged('x/c.kt')
  const out = confirmMeasured(result([a, b, c]), metricsOf([frow('x/b.kt', { churn: 42 })]))
  assert.deepEqual(out.kept.map((r) => r.findingId), [a.findingId, b.findingId, c.findingId], 'order and membership unchanged')
  const row = out.kept[1]
  assert.equal(row.verdict, 'CONFIRMED')
  assert.equal(row.verifiedBy, 'metrics')
  assert.equal('unverifiedReason' in row, false, 'metrics-pending is removed, not blanked')
  assert.equal(row.stableId, b.stableId, 'stableId is never recomputed')
  assert.deepEqual(out.refuted, [])
})

// ── S2 a mismatch leaves kept[] and the proposal and lands last in refuted[] (C8, D4, D7) ────────

test('S2 churn claimed 40, measured 60: leaves kept[] and proposal.items, appended last to refuted[] with the C8 rationale', () => {
  const old = { findingId: 'old-1', title: 'Earlier refutation', category: 'x', locations: ['elsewhere/none.kt:9'], verifiedBy: 'agents', rationale: 'agents said no' }
  const a = judged('x/a.kt')
  const b = pend('x/b.kt', num('churn', 40))
  const c = pend('x/c.kt', num('churn', 40))
  const proposal = {
    container: { id: 'root-1' },
    items: [a, b, c].map((r) => ({ findingId: r.findingId, class: 'bug', verdict: r.verdict, materialize: false, reason: 'ok' })),
    batches: [],
    observations: [],
  }
  const out = confirmMeasured(
    result([a, b, c], { refuted: [old], proposal }),
    metricsOf([frow('x/b.kt', { churn: 60, churnRecent: 6, indentComplexity: 210, hotspotScore: 0.6 }), frow('x/c.kt', { churn: 40 })]),
  )
  assert.deepEqual(out.kept.map((r) => r.findingId), [a.findingId, c.findingId])
  assert.deepEqual(out.proposal.items.map((i) => i.findingId), [a.findingId, c.findingId])
  assert.equal(out.refuted.length, 2)
  assert.deepEqual(out.refuted[0], old, 'earlier refuted rows keep their place and content')
  assert.deepEqual(out.refuted[1], {
    findingId: b.findingId,
    reportId: b.reportId,
    title: b.title,
    category: b.category,
    locations: b.locations,
    stableId: b.stableId,
    basis: b.basis,
    measuredClaim: b.measuredClaim,
    verifiedBy: 'metrics',
    rationale: 'measured value differs: churn claimed 40, measured 60',
    metrics: { churn: 60, churnRecent: 6, complexity: 210, hotspotScore: 0.6 },
  })
})

test('S2 a missing refuted[] reads as empty and several refutations append in kept order', () => {
  const rows = [pend('r/0.kt', num('churn', 1)), judged('r/1.kt'), pend('r/2.kt', num('churn', 1)), pend('r/3.kt', num('churn', 1))]
  const input = result(rows)
  delete input.refuted
  const out = confirmMeasured(input, metricsOf([frow('r/0.kt', { churn: 99 }), frow('r/2.kt', { churn: 1 }), frow('r/3.kt', { churn: 99 })]))
  assert.deepEqual(out.refuted.map((r) => r.findingId), [rows[0].findingId, rows[3].findingId])
  assert.deepEqual(out.kept.map((r) => r.findingId), [rows[1].findingId, rows[2].findingId])
  assert.equal(out.kept[1].verdict, 'CONFIRMED')
})

// ── S3 the 10% boundary and the field map (D2) ───────────────────────────────────────────────────

test('S3 measured 50: claims 55 and 45 confirm (inclusive), 56 and 44 refute, for every numeric field', () => {
  const fieldOnFile = { churn: 'churn', churnRecent: 'churnRecent', complexity: 'indentComplexity', hotspotScore: 'hotspotScore' }
  const rows = []
  const files = []
  const want = {}
  for (const [field, fileField] of Object.entries(fieldOnFile)) {
    for (const [claim, ok] of [[50, true], [55, true], [45, true], [56, false], [44, false]]) {
      const path = `b/${field}-${claim}.kt`
      const r = pend(path, num(field, claim))
      rows.push(r)
      // the measured field is 50; the other three are 7; a decoy `complexity` property (not on metrics-v1) is 999
      files.push(frow(path, { churn: 7, churnRecent: 7, indentComplexity: 7, hotspotScore: 7, complexity: 999, [fileField]: 50 }))
      want[r.findingId] = ok
    }
  }
  const out = confirmMeasured(result(rows), metricsOf(files))
  for (const r of rows) {
    const keptRow = byId(out.kept, r.findingId)
    if (want[r.findingId]) {
      assert.ok(keptRow, `${r.locations[0]} stays kept`)
      assert.equal(keptRow.verdict, 'CONFIRMED', r.locations[0])
    } else {
      assert.equal(keptRow, undefined, `${r.locations[0]} leaves kept[]`)
      assert.ok(byId(out.refuted, r.findingId), `${r.locations[0]} is refuted`)
    }
  }
  assert.equal(out.kept.length, 12)
  assert.equal(out.refuted.length, 8)
})

test('S3 the complexity claim reads indentComplexity, not a property named complexity', () => {
  const r = pend('c/x.kt', num('complexity', 200))
  const out = confirmMeasured(result([r]), metricsOf([frow('c/x.kt', { indentComplexity: 200, complexity: 5 })]))
  assert.equal(out.kept[0].verdict, 'CONFIRMED')
  const r2 = pend('c/y.kt', num('complexity', 5))
  const out2 = confirmMeasured(result([r2]), metricsOf([frow('c/y.kt', { indentComplexity: 200, complexity: 5 })]))
  assert.equal(out2.kept.length, 0)
  assert.equal(out2.refuted[0].rationale, 'measured value differs: complexity claimed 5, measured 200')
})

test('S3 probe: a fractional hotspotScore within tolerance confirms (0.82 against 0.8234)', () => {
  const r = pend('h/x.kt', num('hotspotScore', 0.82))
  const out = confirmMeasured(result([r]), metricsOf([frow('h/x.kt', { hotspotScore: 0.8234 })]))
  assert.equal(out.kept[0].verdict, 'CONFIRMED')
  const r2 = pend('h/y.kt', num('hotspotScore', 0.5))
  const out2 = confirmMeasured(result([r2]), metricsOf([frow('h/y.kt', { hotspotScore: 0.8234 })]))
  assert.equal(out2.kept.length, 0)
  assert.equal(out2.refuted[0].rationale, 'measured value differs: hotspotScore claimed 0.5, measured 0.8234')
})

// ── S4 measured zero (D2) ────────────────────────────────────────────────────────────────────────

test('S4 measured 0: a claim of 0 confirms, a claim of 1 refutes', () => {
  const a = pend('z/a.kt', num('churn', 0))
  const b = pend('z/b.kt', num('churn', 1))
  const out = confirmMeasured(result([a, b]), metricsOf([frow('z/a.kt', { churn: 0 }), frow('z/b.kt', { churn: 0 })]))
  assert.equal(byId(out.kept, a.findingId).verdict, 'CONFIRMED')
  assert.equal(byId(out.kept, b.findingId), undefined)
  assert.equal(byId(out.refuted, b.findingId).rationale, 'measured value differs: churn claimed 1, measured 0')
})

// ── S5 cycle claims (D3) ─────────────────────────────────────────────────────────────────────────

test('S5 a cycle claim confirms when every module sits in one measured cycle, in any order; otherwise refuted', () => {
  const m = metricsOf([], { modules: { cycles: [['c', 'a', 'b'], ['x', 'y']] } })
  const sub = pend('m/s.kt', cyc('a', 'b'))
  const swapped = pend('m/w.kt', cyc('b', 'a'))
  const whole = pend('m/h.kt', cyc('c', 'a', 'b'))
  const outsider = pend('m/o.kt', cyc('a', 'd'))
  const split = pend('m/p.kt', cyc('a', 'x'))
  const cased = pend('m/k.kt', cyc('A', 'B'))
  const out = confirmMeasured(result([sub, swapped, whole, outsider, split, cased]), m)
  for (const r of [sub, swapped, whole]) assert.equal(byId(out.kept, r.findingId).verdict, 'CONFIRMED', r.locations[0])
  for (const r of [outsider, split, cased]) {
    assert.equal(byId(out.kept, r.findingId), undefined, `${r.locations[0]} refuted`)
    assert.equal(byId(out.refuted, r.findingId).verifiedBy, 'metrics')
  }
  assert.equal(byId(out.refuted, outsider.findingId).rationale, 'measured value differs: cycle claimed [a, d], measured none')
  assert.equal(byId(out.refuted, split.findingId).rationale, 'measured value differs: cycle claimed [a, x], measured none')
  assert.equal(byId(out.refuted, cased.findingId).rationale, 'measured value differs: cycle claimed [A, B], measured none')
})

// ── S6 no measurement to compare with (D2, D3, D4) ───────────────────────────────────────────────

test('S6 no files[] row, a non-finite field, or no cycles array: the row stays kept UNVERIFIED with reason no-metrics; rows whose path has a files[] row carry metrics, the others none', () => {
  const noRow = pend('n/none.kt', num('churn', 5))
  const nullField = pend('n/null.kt', num('churn', 5))
  const strField = pend('n/str.kt', num('churn', 5))
  const cycleNoArray = pend('n/cyc.kt', cyc('a', 'b'))
  const files = [frow('n/null.kt', { churn: null }), frow('n/str.kt', { churn: '5' }), frow('n/other.kt')]
  const m = metricsOf(files)
  delete m.modules
  const out = confirmMeasured(result([noRow, nullField, strField, cycleNoArray]), m)
  assert.equal(out.kept.length, 4)
  assert.deepEqual(out.refuted, [])
  for (const r of [noRow, nullField, strField, cycleNoArray]) {
    const row = byId(out.kept, r.findingId)
    assert.equal(row.verdict, 'UNVERIFIED', r.locations[0])
    assert.equal(row.unverifiedReason, 'no-metrics', r.locations[0])
  }
  assert.equal('metrics' in byId(out.kept, noRow.findingId), false)
  assert.equal('metrics' in byId(out.kept, cycleNoArray.findingId), false)
  const unusable = { churn: null, churnRecent: 5, complexity: 200, hotspotScore: 0.5 }
  assert.deepEqual(byId(out.kept, nullField.findingId).metrics, unusable)
  assert.deepEqual(byId(out.kept, strField.findingId).metrics, unusable)
})

test('S6 an existing but empty modules.cycles array is a measurement: the cycle claim is refuted, not no-metrics', () => {
  const r = pend('n/e.kt', cyc('a', 'b'))
  const out = confirmMeasured(result([r]), metricsOf([], { modules: { cycles: [] } }))
  assert.equal(out.kept.length, 0)
  assert.equal(out.refuted[0].rationale, 'measured value differs: cycle claimed [a, b], measured none')
})

// ── S7 metrics on every row whose path has a files[] row (D4) ────────────────────────────────────

test('S7 judgement and refuted rows get metrics in D4 key order, found through the normalised primary path; unmatched rows get none', () => {
  const file = frow('x/y.kt', { churn: 11, churnRecent: 4, indentComplexity: 130, hotspotScore: 0.3 })
  const viaDotSlash = judged('x', { locations: ['./x\\y.kt:3-9'], verdict: 'CONFIRMED' })
  const plain = judged('x/y.kt', { locations: ['x/y.kt:77'], verdict: 'PLAUSIBLE', verifiedBy: 'agents' })
  const unmatched = judged('x/zzz.kt')
  const earlier = { findingId: 'old-2', title: 'old', category: 'c', locations: ['./x\\y.kt:5'], verifiedBy: 'agents', rationale: 'no' }
  const earlierUnmatched = { findingId: 'old-3', title: 'old3', category: 'c', locations: ['q/none.kt:5'], verifiedBy: 'agents', rationale: 'no' }
  const out = confirmMeasured(result([viaDotSlash, plain, unmatched], { refuted: [earlier, earlierUnmatched] }), metricsOf([file]))
  const want = { churn: 11, churnRecent: 4, complexity: 130, hotspotScore: 0.3 }
  for (const row of [byId(out.kept, viaDotSlash.findingId), byId(out.kept, plain.findingId), byId(out.refuted, 'old-2')]) {
    assert.deepEqual(row.metrics, want)
    assert.deepEqual(Object.keys(row.metrics), ['churn', 'churnRecent', 'complexity', 'hotspotScore'], 'key order is the contract order')
  }
  assert.equal(byId(out.kept, viaDotSlash.findingId).verdict, 'CONFIRMED', 'verdict untouched')
  assert.equal(byId(out.kept, plain.findingId).verdict, 'PLAUSIBLE', 'verdict untouched')
  assert.equal('metrics' in byId(out.kept, unmatched.findingId), false)
  assert.equal('metrics' in byId(out.refuted, 'old-3'), false)
})

test('S7 probe: duplicate files[] paths, the first row wins', () => {
  const r = pend('d/dup.kt', num('churn', 40))
  const out = confirmMeasured(
    result([r]),
    metricsOf([frow('d/dup.kt', { churn: 42, churnRecent: 1, indentComplexity: 2, hotspotScore: 0.1 }), frow('d/dup.kt', { churn: 1000 })]),
  )
  assert.equal(out.kept[0].verdict, 'CONFIRMED')
  assert.equal(out.kept[0].metrics.churn, 42)
})

// ── S8 counts, coverage, stamps, hot set (D5, D6) ────────────────────────────────────────────────

function mixedRun() {
  const a = pend('s/a.kt', num('churn', 10)) // confirms
  const b = pend('s/b.kt', num('churn', 10)) // refuted
  const c = pend('s/c.kt', num('churn', 10)) // no row: no-metrics
  const d = judged('s/d.kt') // CONFIRMED by agents
  const e = judged('s/e.kt', { verdict: 'PLAUSIBLE' })
  const f = judged('s/f.kt', { verdict: 'UNVERIFIED', unverifiedReason: 'cap', verifiedBy: 'none' })
  const g = judged('s/g.kt', { verdict: 'UNVERIFIED', unverifiedReason: 'no-verifier', verifiedBy: 'none' })
  const old = { findingId: 'old-9', title: 'older', category: 'c', locations: ['zz/none.kt:1'], verifiedBy: 'agents', rationale: 'no' }
  const input = result([a, b, c, d, e, f, g], {
    refuted: [old],
    report: { healthVerdict: 'ok', coverage: { critic: 'ran', verification: { confirmed: 0, refuted: 1, unverifiedByCap: 1, unverifiedNoVerifier: 1 }, notExamined: ['n/a'] } },
  })
  const metrics = metricsOf([frow('s/a.kt', { churn: 10 }), frow('s/b.kt', { churn: 99 })], { hotSet: ['p1.kt', 'p2.kt'] })
  return { input, metrics, rows: { a, b, c, d, e, f, g } }
}

test('S8 stats are recounted, the metrics counters set, other stats left alone', () => {
  const { input, metrics } = mixedRun()
  input.stats.metricsPending = 3
  const out = confirmMeasured(input, metrics)
  assert.equal(out.stats.metricsPending, 0, 'every pending row is decided')
  assert.equal(out.stats.kept, 6)
  assert.equal(out.stats.confirmed, 2) // a (by metrics) and d
  assert.equal(out.stats.plausible, 1)
  assert.equal(out.stats.unverified, 3) // c, f, g
  assert.equal(out.stats.refuted, 2) // old-9 and b
  assert.equal(out.stats.confirmedByMetrics, 1)
  assert.equal(out.stats.refutedByMetrics, 1)
  assert.equal(out.stats.raw, 77)
  assert.equal(out.stats.merged, 68)
  assert.equal(out.stats.unverifiedByCap, 4, 'a stat outside the D5 list is untouched')
})

test('S8 coverage.verification is recomputed to exactly the five D5 values; the rest of report is kept', () => {
  const { input, metrics } = mixedRun()
  const out = confirmMeasured(input, metrics)
  assert.deepEqual(out.report.coverage.verification, {
    confirmed: 2, refuted: 2, unverifiedByCap: 1, unverifiedNoVerifier: 1, unverifiedNoMetrics: 1,
  })
  assert.equal(out.report.coverage.critic, 'ran')
  assert.deepEqual(out.report.coverage.notExamined, ['n/a'])
  assert.equal(out.report.healthVerdict, 'ok')
})

test('S8 the output is the bare result with confirmedBy and the digest hot set appended, existing keys first and in order', () => {
  const { input, metrics } = mixedRun()
  const keys = Object.keys(input)
  const out = confirmMeasured(input, metrics)
  assert.equal(out.confirmedBy, 'audit-confirm')
  assert.deepEqual(out.hotSet, ['p1.kt', 'p2.kt'])
  assert.deepEqual(Object.keys(out).slice(0, keys.length), keys)
  assert.deepEqual(Object.keys(out).slice(keys.length).sort(), ['confirmedBy', 'hotSet'])
  assert.equal(out.contract, 'audit/result-v2')
  assert.equal(confirmMeasured(input, metricsOf([], { hotSet: [] })).hotSet.length, 0)
})

test('S8 a result with no pending rows keeps its coverage.verification; a null report and a report without coverage are fine', () => {
  const a = judged('q/a.kt')
  const f = judged('q/f.kt', { verdict: 'UNVERIFIED', unverifiedReason: 'cap', verifiedBy: 'none' })
  const old = { findingId: 'old-5', title: 't', category: 'c', locations: ['none/x.kt:1'], verifiedBy: 'agents', rationale: 'no' }
  const verification = { confirmed: 1, refuted: 1, unverifiedByCap: 1, unverifiedNoVerifier: 0, unverifiedNoMetrics: 0 }
  const input = result([a, f], { refuted: [old], report: { coverage: { verification: { ...verification } } } })
  assert.deepEqual(confirmMeasured(input, metricsOf([])).report.coverage.verification, verification)
  assert.equal(confirmMeasured(result([a]), metricsOf([])).report, null)
  const noCoverage = confirmMeasured(result([a], { report: { healthVerdict: 'h' } }), metricsOf([]))
  assert.deepEqual(noCoverage.report, { healthVerdict: 'h' }, 'no coverage object is invented')
})

test('S8 an empty kept[] is fine and counts zero', () => {
  const out = confirmMeasured(result([]), metricsOf([]))
  assert.deepEqual(out.kept, [])
  assert.equal(out.stats.kept, 0)
  assert.equal(out.stats.confirmedByMetrics, 0)
  assert.equal(out.stats.refutedByMetrics, 0)
  assert.equal(out.stats.raw, 77)
})

// ── S9 the proposal after confirmation (D7) ──────────────────────────────────────────────────────

function gatedItem(row, over = {}) {
  return {
    findingId: row.findingId,
    title: row.title,
    class: 'bug',
    severity: 'high',
    verdict: 'UNVERIFIED',
    materialize: false,
    reason: 'verdict UNVERIFIED',
    selectedBy: { gated: true, bugs: true, all: true },
    likelyDuplicate: 'none',
    tags: 'audit,hotspot,audit-a-202611220000,unverified',
    ...over,
  }
}

test('S9 a confirmed gated row is re-gated in the workflow order: tracked, then duplicate strong or unknown, else ok', () => {
  const files = []
  const mk = (name, over = {}) => {
    const path = `g/${name}.kt`
    files.push(frow(path, { churn: 10 }))
    return pend(path, num('churn', 10), over)
  }
  const clean = mk('clean')
  const tracked = mk('tracked', { trackedId: 'TRACK-9' })
  const strong = mk('strong')
  const unknown = mk('unknown')
  const weak = mk('weak')
  const both = mk('both', { trackedId: 'TRACK-1' })
  const obs = mk('obs')
  const refutedRow = pend('g/refuted.kt', num('churn', 10))
  files.push(frow('g/refuted.kt', { churn: 99 }))
  const nonGated = mk('policy')
  const tagless = mk('tagless')
  const items = [
    gatedItem(clean),
    gatedItem(tracked),
    gatedItem(strong, { likelyDuplicate: 'strong' }),
    gatedItem(unknown, { likelyDuplicate: 'unknown' }),
    gatedItem(weak, { likelyDuplicate: 'weak' }),
    gatedItem(both, { likelyDuplicate: 'strong' }),
    gatedItem(obs, { class: 'observation' }),
    gatedItem(refutedRow, { class: 'observation', materialize: true }),
    gatedItem(nonGated, { reason: 'class tech-debt', class: 'tech-debt', tags: 'audit,hotspot,audit-a-202611220000' }),
    gatedItem(tagless, { tags: 'audit,unverified-ish,audit-a-202611220000' }),
  ]
  const proposal = { container: { id: 'root-1' }, items, batches: [], observations: [refutedRow.findingId] }
  const out = confirmMeasured(result([clean, tracked, strong, unknown, weak, both, obs, refutedRow, nonGated, tagless], { proposal }), metricsOf(files))
  const item = (r) => out.proposal.items.find((i) => i.findingId === r.findingId)

  assert.equal(item(refutedRow), undefined, 'the refuted row leaves the proposal')
  assert.equal(out.proposal.items.length, 9)
  for (const r of [clean, tracked, strong, unknown, weak, both, obs, nonGated, tagless]) {
    assert.equal(item(r).verdict, 'CONFIRMED', `${r.locations[0]} item verdict`)
  }
  assert.equal(item(clean).materialize, true)
  assert.equal(item(clean).reason, 'ok')
  assert.equal(item(clean).tags, 'audit,hotspot,audit-a-202611220000', 'the trailing ,unverified is cut')
  assert.equal(item(tracked).materialize, false)
  assert.equal(item(tracked).reason, 'tracked TRACK-9')
  assert.equal(item(strong).materialize, false)
  assert.equal(item(strong).reason, 'likely duplicate strong')
  assert.equal(item(unknown).materialize, false)
  assert.equal(item(unknown).reason, 'likely duplicate unknown')
  assert.equal(item(weak).materialize, true)
  assert.equal(item(weak).reason, 'ok')
  assert.equal(item(both).materialize, false)
  assert.equal(item(both).reason, 'tracked TRACK-1', 'tracked is checked before the duplicate verdict')
  assert.equal(item(obs).materialize, true)
  assert.equal(item(obs).reason, 'ok')
  // an item whose reason is not the gated-policy reason keeps it; only verdict and the tag change
  assert.equal(item(nonGated).materialize, false)
  assert.equal(item(nonGated).reason, 'class tech-debt')
  assert.equal(item(nonGated).tags, 'audit,hotspot,audit-a-202611220000')
  // only a trailing ",unverified" is cut
  assert.equal(item(tagless).tags, 'audit,unverified-ish,audit-a-202611220000')
  // batches hold the materialized non-observation items in item order; the observation list is rebuilt
  assert.equal(out.proposal.batches.length, 1)
  assert.equal(item(tagless).materialize, true)
  assert.deepEqual(out.proposal.batches[0].childRefs, [clean.findingId, weak.findingId, tagless.findingId])
  assert.deepEqual(out.proposal.batches[0].root, out.proposal.container)
  assert.deepEqual(out.proposal.observations, [obs.findingId])
})

test('S9 twenty-six materializable items rebuild into two batches: 25 under the container, 1 attached to <container-id>', () => {
  const files = []
  const rows = Array.from({ length: 26 }, (_, i) => {
    const path = `w/${i + 1}.kt`
    files.push(frow(path, { churn: 10 }))
    return pend(path, num('churn', 10))
  })
  const proposal = { container: { id: 'root-1' }, items: rows.map((r) => gatedItem(r)), batches: [], observations: [] }
  const out = confirmMeasured(result(rows, { proposal }), metricsOf(files))
  assert.equal(out.proposal.batches.length, 2)
  assert.deepEqual(out.proposal.batches[0].childRefs, rows.slice(0, 25).map((r) => r.findingId))
  assert.deepEqual(out.proposal.batches[0].root, out.proposal.container)
  assert.deepEqual(out.proposal.batches[1].childRefs, [rows[25].findingId])
  assert.deepEqual(out.proposal.batches[1].root, { id: '<container-id>' })
})

test('S9 items of rows the metrics did not decide are untouched; a null proposal stays null', () => {
  const agent = judged('u/a.kt')
  const noRow = pend('u/none.kt', num('churn', 10))
  const refutedRow = pend('u/r.kt', num('churn', 1))
  const items = [agent, noRow].map((r) => ({ findingId: r.findingId, class: 'bug', verdict: r.verdict, materialize: false, reason: 'verdict UNVERIFIED', tags: 'audit,unverified' }))
  const out = confirmMeasured(
    result([agent, noRow, refutedRow], { proposal: { container: { id: 'root-1' }, items: clone(items), batches: [], observations: [] } }),
    metricsOf([frow('u/r.kt', { churn: 50 })]),
  )
  assert.deepEqual(out.proposal.items, items)
  const nullProposal = confirmMeasured(result([refutedRow], { proposal: null }), metricsOf([frow('u/r.kt', { churn: 50 })]))
  assert.equal(nullProposal.proposal, null)
  assert.equal(nullProposal.refuted.length, 1)
})

// ── S10 purity (D6, D8, AC3) ─────────────────────────────────────────────────────────────────────

test('S10 inputs are never mutated: frozen deep copies pass through and compare equal afterwards', () => {
  const { input, metrics } = mixedRun()
  const proposal = { container: { id: 'r' }, items: [gatedItem(input.kept[0])], batches: [], observations: [] }
  input.proposal = proposal
  const before = clone({ input, metrics })
  const out = confirmMeasured(deepFreeze(input), deepFreeze(metrics))
  assert.deepEqual({ input, metrics }, before)
  assert.ok(out !== input && out.kept !== input.kept)
})

test('S10 a wrapper and the bare result give the same output, byte for byte, and the same input gives the same bytes', () => {
  const { input, metrics } = mixedRun()
  const bare = confirmMeasured(clone(input), clone(metrics))
  const wrapped = confirmMeasured({ summary: 's', agentCount: 3, logs: [], result: clone(input) }, clone(metrics))
  assert.deepEqual(wrapped, bare)
  assert.equal(JSON.stringify(wrapped), JSON.stringify(bare), 'byte-identical')
  assert.equal(JSON.stringify(confirmMeasured(clone(input), clone(metrics))), JSON.stringify(bare), 'same input, same bytes')
})

test('S10 applying confirmMeasured twice equals applying it once', () => {
  const { input, metrics } = mixedRun()
  const once = confirmMeasured(clone(input), clone(metrics))
  assert.deepEqual(confirmMeasured(clone(once), clone(metrics)), once)
})

// ── S11 TypeError cases (D8) ─────────────────────────────────────────────────────────────────────

test('S11 TypeError for an input without kept[], a bad metrics object, or a pending row without a valid claim', () => {
  const m = metricsOf([frow('t/x.kt')])
  const ok = result([pend('t/x.kt', num('churn', 10))])
  // no kept[]
  for (const bad of [{}, { result: {} }, { kept: 'nope' }, { result: { kept: {} } }, null]) {
    assert.throws(() => confirmMeasured(bad, m), TypeError, `input ${JSON.stringify(bad)}`)
  }
  // metrics
  for (const bad of [null, undefined, 'x', 7, { ...m, contract: 'audit/metrics-v0' }, { files: m.files }, { contract: CONTRACT }, { contract: CONTRACT, files: 'x' }, { contract: CONTRACT, files: {} }]) {
    assert.throws(() => confirmMeasured(ok, bad), TypeError, `metrics ${JSON.stringify(bad)}`)
  }
  // a metrics-pending row with no valid measuredClaim (rule at the workflow: field in the list, finite number, or a non-empty string array for a cycle)
  const claims = [
    undefined,
    {},
    { field: 'loc', value: 5 },
    { field: 'churn' },
    { field: 'churn', value: NaN },
    { field: 'churn', value: Infinity },
    { field: 'churn', value: 'abc' },
    { field: 'cycle' },
    { field: 'cycle', modules: [] },
    { field: 'cycle', modules: [1, 2] },
    { field: 'cycle', modules: 'a,b' },
  ]
  for (const claim of claims) {
    const row = pend('t/x.kt', claim)
    if (claim === undefined) delete row.measuredClaim
    assert.throws(() => confirmMeasured(result([row]), m), TypeError, `claim ${JSON.stringify(claim)}`)
  }
  // a row that is not pending is not validated for a claim
  const agent = judged('t/y.kt', { measuredClaim: { field: 'bogus' } })
  assert.doesNotThrow(() => confirmMeasured(result([agent]), m))
})

// ── S12 the CLI (C8, D8, AC3) ────────────────────────────────────────────────────────────────────

test('S12 CLI writes 2-space JSON plus one LF, equal to the lib output, byte-identical across runs; a wrapper input works', (t) => {
  const dir = scratch(t)
  const { input, metrics } = mixedRun()
  const resultPath = join(dir, 'result.json')
  const wrapperPath = join(dir, 'wrapper.json')
  const metricsPath = join(dir, 'metrics.json')
  writeFileSync(resultPath, JSON.stringify(input))
  writeFileSync(wrapperPath, JSON.stringify({ summary: 'x', result: input }))
  writeFileSync(metricsPath, JSON.stringify(metrics))
  const expected = JSON.stringify(confirmMeasured(clone(input), clone(metrics)), null, 2) + '\n'
  const o1 = join(dir, 'one.json')
  const o2 = join(dir, 'two.json')
  const o3 = join(dir, 'three.json')
  const r1 = runCli(['--result', resultPath, '--metrics', metricsPath, '--out', o1])
  const r2 = runCli(['--result', resultPath, '--metrics', metricsPath, '--out', o2])
  const r3 = runCli(['--result', wrapperPath, '--metrics', metricsPath, '--out', o3])
  for (const r of [r1, r2, r3]) assert.equal(r.status, 0, r.stderr)
  assert.ok(readFileSync(o1).equals(readFileSync(o2)))
  assert.equal(readFileSync(o1, 'utf8'), expected)
  assert.equal(readFileSync(o3, 'utf8'), expected)
  assert.ok(!expected.includes('\r'), 'LF only')
  assert.ok(expected.endsWith('}\n') && !expected.endsWith('\n\n'))
})

test('S12 CLI exits 2 with INVALID: on every bad invocation and leaves --out untouched or absent', (t) => {
  const dir = scratch(t)
  const { input, metrics } = mixedRun()
  const good = join(dir, 'result.json')
  const goodM = join(dir, 'metrics.json')
  writeFileSync(good, JSON.stringify(input))
  writeFileSync(goodM, JSON.stringify(metrics))
  const junk = join(dir, 'junk.json')
  writeFileSync(junk, 'this is not json')
  const nokept = join(dir, 'nokept.json')
  writeFileSync(nokept, '{}')
  const badM = join(dir, 'badmetrics.json')
  writeFileSync(badM, JSON.stringify({ ...metrics, contract: 'audit/metrics-v0' }))
  const badClaim = join(dir, 'badclaim.json')
  const rowNoClaim = pend('t/x.kt', undefined)
  delete rowNoClaim.measuredClaim
  writeFileSync(badClaim, JSON.stringify(result([rowNoClaim])))
  const SENT = 'SENTINEL-DO-NOT-OVERWRITE'
  const out = join(dir, 'out.json')
  // The message wording is not part of the contract: each case asserts INVALID: plus the argument or file that caused it.
  const cases = {
    'missing --out': { args: () => ['--result', good, '--metrics', goodM], names: [/--out/] },
    'missing --result': { args: (o) => ['--metrics', goodM, '--out', o], names: [/--result/] },
    'missing --metrics': { args: (o) => ['--result', good, '--out', o], names: [/--metrics/] },
    'unknown flag': { args: (o) => ['--result', good, '--metrics', goodM, '--out', o, '--bogus', 'x'], names: [/--bogus/] },
    'flag without value': { args: (o) => ['--result', good, '--out', o, '--metrics'], names: [/--metrics/] },
    'unreadable result': { args: (o) => ['--result', join(dir, 'nope.json'), '--metrics', goodM, '--out', o], names: [/nope\.json/] },
    'unreadable metrics': { args: (o) => ['--result', good, '--metrics', join(dir, 'nometrics.json'), '--out', o], names: [/nometrics\.json/] },
    'result not JSON': { args: (o) => ['--result', junk, '--metrics', goodM, '--out', o], names: [/junk\.json/] },
    'metrics not JSON': { args: (o) => ['--result', good, '--metrics', junk, '--out', o], names: [/junk\.json/] },
    'result without kept[]': { args: (o) => ['--result', nokept, '--metrics', goodM, '--out', o], names: [] },
    'metrics contract v0': { args: (o) => ['--result', good, '--metrics', badM, '--out', o], names: [] },
    'pending row without a claim': { args: (o) => ['--result', badClaim, '--metrics', goodM, '--out', o], names: [] },
  }
  for (const [name, { args, names }] of Object.entries(cases)) {
    writeFileSync(out, SENT)
    const r = runCli(args(out))
    assert.equal(r.status, 2, `${name}: exit code (stderr: ${r.stderr})`)
    assert.ok(r.stderr.startsWith('INVALID: '), `${name}: stderr starts with INVALID: (got: ${r.stderr})`)
    for (const re of names) assert.match(r.stderr, re, `${name}: names its cause`)
    assert.equal(readFileSync(out, 'utf8'), SENT, `${name}: --out untouched`)
  }
  // no pre-existing out file: none is created
  const fresh = join(dir, 'fresh.json')
  const r = runCli(['--result', junk, '--metrics', goodM, '--out', fresh])
  assert.equal(r.status, 2)
  assert.equal(existsSync(fresh), false)
})

test('S12 bug-report vocabulary carries an audit-confirm.mjs row with the audit-report-html.mjs ops and codes', () => {
  const row = VOCAB.tools['audit-confirm.mjs']
  assert.ok(row, 'row present')
  assert.deepEqual(row.ops, ['run'])
  assert.deepEqual(row.codes, ['exit:0', 'exit:1', 'exit:2', 'Error', 'TypeError', 'RangeError', 'SyntaxError', 'ReferenceError'])
  assert.deepEqual(row, VOCAB.tools['audit-report-html.mjs'])
})

// ── S13 primaryPath parity with the workflow (D1, AC4) ───────────────────────────────────────────

const WF = loadCoreNamed(WORKFLOW, ['primaryPath'])

test('S13 primaryPath follows the contract on hand-derived cases', () => {
  const cases = [
    [[], ''],
    [['a\\b.kt:1-2'], 'a/b.kt'],
    [['./x.md:3'], 'x.md'],
    [['src/a.kt:12'], 'src/a.kt'],
    [['a.kt:1-2', 'b.kt:3'], 'a.kt'],
    [['./x\\y.kt:3-9'], 'x/y.kt'],
    [['p.kt:12 (fn)'], 'p.kt'],
    [['x.kt:'], 'x.kt'],
    [['plain.kt'], 'plain.kt'],
  ]
  for (const [locations, want] of cases) assert.equal(primaryPath(locations), want, JSON.stringify(locations))
})

test('S13 primaryPath equals the workflow core on a probe list', () => {
  const probes = [
    [], [''], ['a\\b.kt:1-2'], ['./x.md:3'], ['p.kt:12 (fn)'], ['x.kt:'], ['.\\y\\z.kt:5'], ['C:\\x\\y.kt:9'],
    ['a.kt:1-2-3'], ['a.kt:12-'], ['../x.kt:1'], ['a/./b.kt:4'], ['a.kt:0'], ['dir/file.kt:10-20', 'other.kt:1'], ['  spaced.kt:3'],
    ['https://h/x.kt:9'], ['a:b/c.kt:7'], ['x.kt:1:2'], ['./.hidden:4'], ['./'],
    ['src/a.kt:12'], ['src/a.kt:12-30'], ['src/a.kt:Foo.bar'], ['src/a.kt (note)'], ['src/a.kt:12 (note)'],
    [''], [null], [42], ['  ./x.kt:3 (n)'], ['src/a.kt	vs src/b.kt'], ['src/a.kt  vs src/b.kt'], ['c:' + String.fromCharCode(92) + 'x' + String.fromCharCode(92) + 'y.kt:9'],['C:/x/y.kt/'], ['x.kt:'], [':'],
  ]
  for (const p of probes) assert.equal(primaryPath(p), WF.primaryPath(p), JSON.stringify(p))
})

// ── T11 (item 326f7edf): one location grammar (D1) and metrics on every row whose path is known ──────
// Oracle: the item's task-scope D1 and AC 1-3, and the test-plan S1, S2, S11, S12, S13 (frozen before the
// implementation). Every expected value below is derived by hand from the grammar clauses.

test('S1 primaryPath maps the five D1 forms of one file to src/a.kt (lib copy)', () => {
  for (const loc of ['src/a.kt:12', 'src/a.kt:12-30', 'src/a.kt:Foo.bar', 'src/a.kt (note)', 'src/a.kt:12 (note)']) {
    assert.equal(primaryPath([loc]), 'src/a.kt', loc)
  }
})

test('S11 primaryPath of no location, an empty string or a non-string is an empty string (lib copy)', () => {
  assert.equal(primaryPath([]), '')
  assert.equal(primaryPath(['']), '')
  assert.equal(primaryPath([null]), '')
  assert.equal(primaryPath([42]), '')
})

test('S12 primaryPath D1 edges (lib copy)', () => {
  const cases = [
    ['C:' + String.fromCharCode(92) + 'x' + String.fromCharCode(92) + 'y.kt:9', 'C:/x/y.kt'],
    ["  ./x.kt:3 (n)", 'x.kt'],
    ['x.kt:', 'x.kt'],
    ['x.kt:1:2', 'x.kt'],
    ['a:b/c.kt:7', 'a:b/c.kt'],
    ['plain.kt', 'plain.kt'],
  ]
  for (const [loc, want] of cases) assert.equal(primaryPath([loc]), want, JSON.stringify(loc))
})

test('S12 / probe: a tab or a double space ends the path like a single space; the first whitespace decides', () => {
  assert.equal(primaryPath(['src/a.kt	vs src/b.kt']), 'src/a.kt')
  assert.equal(primaryPath(['src/a.kt  vs src/b.kt']), 'src/a.kt')
  assert.equal(primaryPath(['	 src/a.kt:5 	(x)']), 'src/a.kt')
})

test('S12 / probe: repeated leading ./ are all stripped; a lower-case drive letter and a final slash keep their form', () => {
  assert.equal(primaryPath(['././x.kt:1']), 'x.kt')
  assert.equal(primaryPath(['c:/x/y.kt:9']), 'c:/x/y.kt')
  assert.equal(primaryPath(['C:/x/y/']), 'C:/x/y/')
})

test('S13 / probe: both copies agree on every D1 form, edge and probe input', () => {
  const BSL = String.fromCharCode(92)
  const inputs = [
    ['src/a.kt:12'], ['src/a.kt:12-30'], ['src/a.kt:Foo.bar'], ['src/a.kt (note)'], ['src/a.kt:12 (note)'],
    ['C:' + BSL + 'x' + BSL + 'y.kt:9'], ['  ./x.kt:3 (n)'], ['x.kt:'], ['x.kt:1:2'], ['a:b/c.kt:7'], ['plain.kt'],
    ['src/a.kt	vs src/b.kt'], ['src/a.kt  vs src/b.kt'], ['././x.kt:1'], ['c:/x/y.kt:9'], ['C:/x/y/'], [''], [null], [42], [], [':'],
  ]
  for (const p of inputs) assert.equal(primaryPath(p), WF.primaryPath(p), JSON.stringify(p))
})

test('S2 result-wrapper fixture: confirm attaches metrics to the 11 rows whose first-location path is in the metrics file and to the ScopeClaimParser row not at all', () => {
  const wrapper = JSON.parse(readFileSync(join(HERE, 'fixtures', 'audit-report-html', 'result-wrapper.json'), 'utf8'))
  const base = 'current/src/main/kotlin/io/github/jpicklyk/mcptask/current/'
  // hand-typed, hand-normalized first-location paths of the fixture rows (AdvanceService.kt is the first location of two rows)
  const paths = [
    'application/config/EffectiveConfigResolver.kt',
    'infrastructure/config/AppConfig.kt',
    'application/api/BatchRunner.kt',
    'application/service/AdvanceService.kt',
    'interfaces/api/v1/routes/EventRoutes.kt',
    'application/support/ScopeGuard.kt',
    'interfaces/api/v1/error/ApiStatusPages.kt',
    'domain/lifecycle/TransitionPolicy.kt',
    'application/read/RunFrontierQueries.kt',
    'interfaces/api/v1/auth/AuthenticationPlugin.kt',
  ].map((p) => base + p)
  const out = confirmMeasured(clone(wrapper.result), metricsOf(paths.map((p) => frow(p))))
  assert.equal(out.kept.length, 12)
  const withMetrics = out.kept.filter((r) => r.metrics)
  assert.equal(withMetrics.length, 11)
  const scope = out.kept.find((r) => r.locations[0].includes('ScopeClaimParser.kt'))
  assert.ok(scope, 'fixture row present')
  assert.equal('metrics' in scope, false)
  assert.deepEqual(out.kept.filter((r) => !r.metrics).map((r) => r.findingId), [scope.findingId])
})

// ── item b5617eb3: refuted-row report id, metricsPending recount, re-gate parity ─────────────────────

test('AC2 a metrics-refuted row copies a non-empty reportId; an empty one leaves no reportId key', () => {
  const withId = pend('q/a.kt', num('churn', 40), { reportId: 'R07' })
  const emptyId = pend('q/b.kt', num('churn', 40), { reportId: '' })
  const out = confirmMeasured(result([withId, emptyId]), metricsOf([frow('q/a.kt', { churn: 60 }), frow('q/b.kt', { churn: 60 })]))
  assert.equal(out.kept.length, 0)
  assert.equal(byId(out.refuted, withId.findingId).reportId, 'R07')
  assert.equal('reportId' in byId(out.refuted, emptyId.findingId), false)
})

test('AC5 stats.metricsPending is recounted from the kept rows still pending, and a second pass gives the same value', () => {
  const rows = [pend('q/c.kt', num('churn', 10)), pend('q/d.kt', num('churn', 10))]
  const input = result(rows, { stats: { ...stats0, metricsPending: 2 } })
  const out = confirmMeasured(input, metricsOf([frow('q/c.kt', { churn: 10 }), frow('q/d.kt', { churn: 10 })]))
  assert.equal(out.stats.metricsPending, 0)
  assert.equal(confirmMeasured(out, metricsOf([])).stats.metricsPending, 0)
})

const PARITY = loadCoreNamed(WORKFLOW, ['buildProposal'])

test('AC1 the re-gated proposal equals the workflow buildProposal over the confirmed kept rows (policy gated)', () => {
  const plan = {
    title: 'T', date: '2026-11-23', runId: 'a-parity', reportPath: 'docs/audit.html', observationPaths: ['docs/'],
    classMap: { hotspot: 'bug', cohesion: 'tech-debt' },
    items: { enabled: true, rootId: 'root-1', materializeMin: 'medium', policy: 'gated', typeMap: { bug: 'bug', techDebt: 'tech-debt' } },
  }
  const clean = pend('p/clean.kt', num('churn', 10))
  const tracked = pend('p/tracked.kt', num('churn', 10), { trackedId: 'abcd1234' })
  const dupStrong = pend('p/strong.kt', num('churn', 10))
  const unknown = pend('p/unknown.kt', num('churn', 10))
  const weak = pend('p/weak.kt', num('churn', 10))
  const low = pend('p/low.kt', num('churn', 10), { severity: 'low' })
  const mismatch = pend('p/mismatch.kt', num('churn', 40))
  const noRow = pend('p/norow.kt', num('churn', 10))
  const obs = pend('docs/obs.md', num('churn', 10))
  const bulk = Array.from({ length: 26 }, (_, i) => judged(`p/bulk${i}.kt`, { severity: 'medium' }))
  const agents = judged('p/judged.kt', { severity: 'high' })
  const kept = [clean, tracked, dupStrong, unknown, weak, low, mismatch, noRow, obs, ...bulk, agents]
  const triage = {
    [dupStrong.findingId]: { candidates: [], likelyDuplicate: 'strong' },
    [unknown.findingId]: { candidates: [], likelyDuplicate: 'unknown' },
    [weak.findingId]: { candidates: [], likelyDuplicate: 'weak' },
  }
  for (const k of kept) if (!triage[k.findingId]) triage[k.findingId] = { candidates: [], likelyDuplicate: 'none' }
  const before = PARITY.buildProposal(clone(kept), triage, plan)
  const files = ['clean', 'tracked', 'strong', 'unknown', 'weak', 'low', 'obs'].map((n) => frow(n === 'obs' ? 'docs/obs.md' : `p/${n}.kt`, { churn: 10 }))
  files.push(frow('p/mismatch.kt', { churn: 10 }))
  const out = confirmMeasured(result(kept, { proposal: before }), metricsOf(files))
  assert.deepEqual(out.proposal, PARITY.buildProposal(out.kept, triage, plan))
  // not vacuous: the refuted row left, and the materializable rows span two batches
  assert.equal(out.proposal.items.some((i) => i.findingId === mismatch.findingId), false)
  assert.equal(byId(out.refuted, mismatch.findingId).findingId, mismatch.findingId)
  assert.equal(out.proposal.batches.length, 2)
  assert.ok(out.proposal.batches[0].childRefs.includes(clean.findingId))
  assert.ok(!out.proposal.batches.some((b) => b.childRefs.includes(tracked.findingId)))
})
