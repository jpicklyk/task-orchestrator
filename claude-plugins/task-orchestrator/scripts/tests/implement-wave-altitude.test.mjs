// Altitude-safe return of workflows/implement-wave.js (item dbb45373, scenarios S6-S9 of the item's
// test-plan). BLIND author: written from the frozen task-scope (AC2) and test-plan notes, the
// orchestrator-supplied declarations and the existing harness, never from the script under test.
// Every expected value is derived from the task-scope contract (field lists, 120/200 cut lengths,
// rollup definition) or from the unscrubbed run of the same fixture used as the input of the scrub.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { loadCore, loadScript, fakeAgent, fakeParallel, planFixture, itemFixture, stages } from './workflow-harness.mjs'
import { buildPlanDoc, validatePlanDoc } from '../run-planner-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SCRIPT_PATH = join(HERE, '..', '..', 'workflows', 'implement-wave.js')
const PLANNER_CLI = join(HERE, '..', 'run-planner.mjs')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')

const core = () => loadCore(SCRIPT_PATH)

// A 600-char string that shares no 40-char window with any other uniq() string.
function uniq(tag) {
  let out = ''
  let h = createHash('sha256').update(tag).digest('hex')
  while (out.length < 600) {
    out += h
    h = createHash('sha256').update(h).digest('hex')
  }
  return out.slice(0, 600)
}
function windows(s, n = 40) {
  const out = []
  for (let i = 0; i + n <= s.length; i++) out.push(s.slice(i, i + n))
  return out
}

const LONG = {
  decisions: uniq('decisions'),
  corrections: uniq('diagnosisCorrections'),
  testEdit: uniq('existingTestEdits-edit'),
  deviations: uniq('deviations'),
  publicSurface: uniq('publicSurface'),
}
const RED_LONG = uniq('red')

function plannerOutput(over = {}) {
  return {
    proceed: true, blockReason: 'none', diagnosisCorrections: LONG.corrections, defectClassSiblings: 'none',
    decisions: LONG.decisions, missingApiOrSeam: 'none', testPlanStatus: 'none',
    mainFiles: ['src/a.js'], docFiles: ['docs/a.md'], testFiles: [],
    existingTestEdits: [{ file: 't/old.test.js', edit: LONG.testEdit }],
    redProofShape: 'none',
    ...over,
  }
}
function implementerOutput(over = {}) {
  return {
    mainFilesChanged: ['src/a.js'], docFilesChanged: ['docs/a.md'], verify: [], failingExistingTests: [],
    preExistingFailures: [], publicSurface: LONG.publicSurface, deviations: LONG.deviations,
    ...over,
  }
}
function envelope(output, over = {}) {
  return {
    status: 'done', reason: 'ok', notes: [], commits: { pre: 'aaa111', post: 'bbb222' }, files: ['src/a.js'],
    modelReported: 'fake', entry: { applied: true, newRole: 'work' }, output, ...over,
  }
}

function fixtureRun({ altitudeSafe, implNotes } = {}) {
  const items = ['a1000001', 'a1000002', 'a1000003'].map((short) => itemFixture({ short, stages: stages.featureTaskLike() }))
  const planOver = { items, deferred: [{ id: 'ghost-deferred-1', reason: 'x'.repeat(300) }] }
  if (altitudeSafe !== undefined) planOver.altitudeSafe = altitudeSafe
  const plan = planFixture(planOver)
  const script = {
    'planner:a1000001': envelope(plannerOutput({ mainFiles: ['src/a.js'], redProofShape: RED_LONG })),
    'implementer:a1000001': envelope(implementerOutput(), implNotes ? { notes: implNotes } : {}),
    'planner:a1000002': envelope(plannerOutput({ mainFiles: ['src/b.js'], docFiles: ['docs/b.md'], existingTestEdits: [] })),
    'implementer:a1000002': envelope(implementerOutput({ mainFilesChanged: ['src/b.js'], docFilesChanged: ['docs/b.md'] })),
    'planner:a1000003': envelope(plannerOutput({ proceed: false, blockReason: 'B'.repeat(2000), mainFiles: ['src/c.js'], docFiles: ['docs/c.md'] })),
  }
  const { agent } = fakeAgent(script)
  return core().runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })
}

const SAFE_OUTPUT_KEYS = new Set([
  'mainFiles', 'docFiles', 'testFiles', 'declarationModules', 'mainFilesChanged', 'docFilesChanged',
  'testFilesChanged', 'existingTestEdits', 'proceed', 'redProofShape',
])
const SAFE_STAGE_KEYS = new Set([
  'seat', 'status', 'reason', 'modelReported', 'agentTypeUsed', 'agentTypeFallback', 'commits', 'files', 'notes', 'decisionId',
])
const SAFE_ITEM_KEYS = new Set(['id', 'short', 'status', 'schemaFree', 'reason', 'stages', 'outputs'])

// S6 [N] AC2
test('S6: altitudeSafe true returns the scrubbed shape: no long free-text survives, structural fields do', async () => {
  const safe = await fixtureRun({ altitudeSafe: true })
  const full = await fixtureRun({})
  const safeJson = JSON.stringify(safe)
  const fullJson = JSON.stringify(full)

  assert.equal(safe.altitudeSafe, true)
  assert.equal(safe.contract, 'implement-wave/result-v1')
  assert.equal(safe.started, true)
  assert.equal(safe.runId, 'r-test-0001')

  // Non-vacuity: the unscrubbed run of the same fixture does carry the long strings, so the absence
  // below is the scrub's doing, not a fixture that never held them.
  for (const [name, s] of Object.entries(LONG)) {
    assert.ok(fullJson.includes(s.slice(0, 40)), `control: unscrubbed result carries ${name}`)
    for (const w of windows(s)) {
      if (safeJson.includes(w)) assert.fail(`altitude-safe result still contains a 40-char window of ${name}: ${w}`)
    }
  }

  const a = safe.items.find((i) => i.short === 'a1000001')
  assert.equal(a.status, 'done')
  assert.deepEqual(a.outputs.planner.mainFiles, ['src/a.js'])
  assert.deepEqual(a.outputs.planner.docFiles, ['docs/a.md'])
  assert.deepEqual(a.outputs.planner.testFiles, [])
  assert.deepEqual(a.outputs.implementer.mainFilesChanged, ['src/a.js'])
  assert.deepEqual(a.outputs.implementer.docFilesChanged, ['docs/a.md'])
  assert.equal(a.outputs.planner.proceed, true)
  const implStage = a.stages.find((s) => s.seat === 'implementer')
  assert.deepEqual(implStage.commits, { pre: 'aaa111', post: 'bbb222' })
  assert.deepEqual(implStage.files, ['src/a.js'])
  assert.equal(implStage.status, 'done')
  assert.equal(implStage.modelReported, 'fake')
})

test('S6: existingTestEdits is reduced to [{file}] and nothing else of the edit survives', async () => {
  const safe = await fixtureRun({ altitudeSafe: true })
  const a = safe.items.find((i) => i.short === 'a1000001')
  assert.deepEqual(a.outputs.planner.existingTestEdits, [{ file: 't/old.test.js' }])
})

test('S6: only the declared top-level, item, stage and output keys appear; dropped output fields are gone', async () => {
  const safe = await fixtureRun({ altitudeSafe: true })
  assert.deepEqual(
    Object.keys(safe).sort(),
    ['altitudeSafe', 'contract', 'deferred', 'items', 'refused', 'rollup', 'runId', 'serverRunId', 'started'],
  )
  for (const it of safe.items) {
    for (const k of Object.keys(it)) assert.ok(SAFE_ITEM_KEYS.has(k), `unexpected item key ${k}`)
    assert.ok(!('schemaFree' in it), 'schemaFree is set only for schema-free items')
    for (const st of it.stages) {
      for (const k of Object.keys(st)) assert.ok(SAFE_STAGE_KEYS.has(k), `unexpected stage key ${k}`)
    }
    for (const [seat, out] of Object.entries(it.outputs)) {
      for (const k of Object.keys(out)) assert.ok(SAFE_OUTPUT_KEYS.has(k), `unexpected output key ${k} on ${seat}`)
    }
  }
  const a = safe.items.find((i) => i.short === 'a1000001')
  for (const dropped of ['decisions', 'diagnosisCorrections', 'defectClassSiblings', 'blockReason']) {
    assert.ok(!(dropped in a.outputs.planner), `${dropped} dropped from planner output`)
  }
  for (const dropped of ['deviations', 'publicSurface', 'verify']) {
    assert.ok(!(dropped in a.outputs.implementer), `${dropped} dropped from implementer output`)
  }
})

test('S6: stage notes are kept as {key, actor, chars} entries and the note body text is not carried', async () => {
  const bodyText = uniq('note-body')
  const notes = [{ key: 'implementation-notes', actor: { id: 'impl:1', kind: 'subagent' }, chars: 321, body: bodyText }]
  const full = await fixtureRun({ implNotes: notes })
  const safe = await fixtureRun({ altitudeSafe: true, implNotes: notes })
  const st = safe.items.find((i) => i.short === 'a1000001').stages.find((s) => s.seat === 'implementer')
  assert.deepEqual(st.notes.map((n) => n.key), ['implementation-notes'])
  for (const n of st.notes) for (const k of Object.keys(n)) assert.ok(['key', 'actor', 'chars'].includes(k), `note key ${k}`)
  assert.ok(!JSON.stringify(safe).includes(bodyText.slice(0, 40)), 'note body text is not carried')
  const fa = full.items.find((i) => i.short === 'a1000001').stages.find((s) => s.seat === 'implementer')
  assert.deepEqual(fa.notes.map((n) => n.key), ['implementation-notes'])
})

test('S6: redProofShape is cut to 200 chars and deferred entries keep id and a 120-char reason', async () => {
  const safe = await fixtureRun({ altitudeSafe: true })
  const a = safe.items.find((i) => i.short === 'a1000001')
  assert.equal(a.outputs.planner.redProofShape, RED_LONG.slice(0, 200))
  assert.equal(safe.deferred.length, 1)
  assert.equal(safe.deferred[0].id, 'ghost-deferred-1')
  assert.equal(safe.deferred[0].reason, 'x'.repeat(120))
})

test('S6: rollup.done counts done items; rollup.items counts all items', async () => {
  const safe = await fixtureRun({ altitudeSafe: true })
  assert.equal(safe.items.filter((i) => i.status === 'done').length, 2)
  assert.equal(safe.rollup.done, 2)
  assert.equal(safe.rollup.items, 3)
})

// S7 [N] AC2
test('S7: a stopped item carries the planner block reason cut to 120 chars and rolls up as stopped', async () => {
  const safe = await fixtureRun({ altitudeSafe: true })
  const full = await fixtureRun({})
  const fullItem = full.items.find((i) => i.short === 'a1000003')
  const safeItem = safe.items.find((i) => i.short === 'a1000003')
  assert.equal(fullItem.status, 'stopped')
  assert.ok(fullItem.reason.length > 1500, 'control: the unscrubbed reason carries the 2000-char block reason')
  assert.equal(safeItem.status, 'stopped')
  assert.ok(safeItem.reason.length <= 120, `reason length ${safeItem.reason.length}`)
  assert.equal(safeItem.reason, fullItem.reason.slice(0, 120))
  assert.equal(safe.rollup.stopped, 1)
  assert.equal(safe.rollup.done, 2)
  assert.equal(safe.rollup.deferred, 0, 'rollup counts result items by status; no item here is deferred')
  assert.equal(safe.rollup.refused, 0)
  assert.deepEqual(safe.refused, [])
  const stopStage = safeItem.stages.find((s) => s.seat === 'planner')
  assert.ok(stopStage.reason.length <= 120)
})

test('S7 probe: the cut is at exactly 120 chars, whether the reason is shorter or longer', async () => {
  for (const blockLen of [100, 400]) {
    const mk = async (altitudeSafe) => {
      const item = itemFixture({ short: 'a100000b', stages: stages.featureTaskLike() })
      const plan = planFixture({ items: [item], altitudeSafe })
      const { agent } = fakeAgent({ 'planner:a100000b': envelope(plannerOutput({ proceed: false, blockReason: 'Q'.repeat(blockLen) })) })
      return core().runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })
    }
    const full = (await mk(false)).items[0].reason
    const safe = (await mk(true)).items[0].reason
    assert.equal(safe.length, Math.min(full.length, 120), `blockReason ${blockLen}`)
    assert.equal(safe, full.slice(0, 120))
  }
})

test('S7 probe: rollup.deferred counts result items whose status is deferred (per-item overlap defers the second)', async () => {
  const mk = async (altitudeSafe) => {
    const itemA = itemFixture({ short: 'a1000011', stages: stages.featureTaskLike() })
    const itemB = itemFixture({ short: 'a1000012', stages: stages.featureTaskLike() })
    const plan = planFixture({ items: [itemA, itemB], worktreeMode: 'per-item', altitudeSafe })
    const { agent } = fakeAgent({
      'planner:a1000011': envelope(plannerOutput({ mainFiles: ['src/shared.js'], docFiles: ['docs/a.md'] })),
      'planner:a1000012': envelope(plannerOutput({ mainFiles: ['src/shared.js'], docFiles: ['docs/b.md'] })),
      'implementer:a1000011': envelope(implementerOutput()),
      'implementer:a1000012': envelope(implementerOutput()),
    })
    return core().runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })
  }
  const full = await mk(false)
  const safe = await mk(true)
  const fullDeferred = full.items.filter((i) => i.status === 'deferred').length
  assert.equal(fullDeferred, 1, 'control: the unscrubbed run defers exactly one item')
  assert.equal(safe.rollup.deferred, 1)
  assert.equal(safe.rollup.done, 1)
  assert.equal(safe.rollup.items, 2)
})
// S8 [E]
test('S8: altitudeSafe absent or false leaves the result unchanged: decisions verbatim, no rollup', async () => {
  const absent = await fixtureRun({})
  const falsy = await fixtureRun({ altitudeSafe: false })
  for (const r of [absent, falsy]) {
    const a = r.items.find((i) => i.short === 'a1000001')
    assert.equal(a.outputs.planner.decisions, LONG.decisions)
    assert.equal(a.outputs.implementer.deviations, LONG.deviations)
    assert.ok(!('rollup' in r))
    assert.deepEqual(Object.keys(r).sort(), ['contract', 'deferred', 'items', 'refused', 'runId', 'serverRunId', 'started'])
  }
  assert.deepEqual(falsy, absent)
})

test('S8: normalizeArgs rejects a non-boolean altitudeSafe with "invalid args: bad altitudeSafe" and accepts booleans and absence', () => {
  const item = itemFixture({ short: 'a100000c', stages: stages.featureTaskLike() })
  const baseline = core().normalizeArgs(planFixture({ items: [item] }))
  assert.equal(baseline.ok, true, 'the baseline plan is valid, so the rejections below are about altitudeSafe only')
  for (const bad of ['yes', 'true', 1, 0, {}, []]) {
    const r = core().normalizeArgs(planFixture({ items: [item], altitudeSafe: bad }))
    assert.equal(r.ok, false, JSON.stringify(bad))
    assert.equal(r.reason, 'invalid args: bad altitudeSafe', JSON.stringify(bad))
  }
  for (const good of [true, false]) {
    assert.equal(core().normalizeArgs(planFixture({ items: [item], altitudeSafe: good })).ok, true, String(good))
  }
})

test('S8: the workflow script itself returns {started:false} with that reason for altitudeSafe "yes"', async () => {
  const item = itemFixture({ short: 'a100000d', stages: stages.featureTaskLike() })
  const result = await loadScript(SCRIPT_PATH).run({
    agent: async () => ({}), parallel: fakeParallel, log: () => {},
    args: planFixture({ items: [item], altitudeSafe: 'yes' }),
  })
  assert.equal(result.started, false)
  assert.equal(result.reason, 'invalid args: bad altitudeSafe')
})

// S9 [N] AC2 planner
const NOW = '2026-09-28T12:00:00Z'
const snapshot = () => JSON.parse(readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8'))
function runCli(args, input) {
  return spawnSync(process.execPath, [PLANNER_CLI, ...args], { input: JSON.stringify(input), encoding: 'utf8' })
}

test('S9: run-planner plan --altitude-safe sets args.altitudeSafe true; absent leaves the key out', () => {
  const on = runCli(['plan', '--now', NOW, '--altitude-safe'], snapshot())
  assert.equal(on.status, 0, on.stderr)
  assert.strictEqual(JSON.parse(on.stdout).args.altitudeSafe, true)
  const off = runCli(['plan', '--now', NOW], snapshot())
  assert.equal(off.status, 0, off.stderr)
  assert.ok(!('altitudeSafe' in JSON.parse(off.stdout).args), 'no altitudeSafe key without the flag')
})

test('S9: buildPlanDoc opts.altitudeSafe true sets args.altitudeSafe; absent sets no key', () => {
  const on = buildPlanDoc(snapshot(), { now: NOW, altitudeSafe: true })
  assert.equal(on.ok, true, JSON.stringify(on.errors))
  assert.strictEqual(on.doc.args.altitudeSafe, true)
  const off = buildPlanDoc(snapshot(), { now: NOW })
  assert.equal(off.ok, true)
  assert.ok(!('altitudeSafe' in off.doc.args))
})

test('S9: validatePlanDoc accepts a boolean altitudeSafe and rejects a string with "bad altitudeSafe: must be a boolean"', () => {
  const schema = JSON.parse(readFileSync(join(FIXTURES, 'args-v1.schema.json'), 'utf8'))
  const built = buildPlanDoc(snapshot(), { now: NOW, altitudeSafe: true })
  assert.equal(built.ok, true, JSON.stringify(built.errors))
  assert.deepEqual(validatePlanDoc(built.doc, schema), { ok: true, errors: [] })
  const bad = JSON.parse(JSON.stringify(built.doc))
  bad.args.altitudeSafe = 'yes'
  const r = validatePlanDoc(bad, schema)
  assert.equal(r.ok, false)
  assert.ok(r.errors.includes('bad altitudeSafe: must be a boolean'), JSON.stringify(r.errors))
})
// ── Coordinator report (item 666c5e8f): seat-raised decision ids survive both result shapes ──

async function decisionRun({ altitudeSafe, implEnv }) {
  const item = itemFixture({ short: 'a2000001', stages: stages.featureTaskLike() })
  const planOver = { items: [item] }
  if (altitudeSafe !== undefined) planOver.altitudeSafe = altitudeSafe
  const script = {
    'planner:a2000001': envelope(plannerOutput({ mainFiles: ['src/a.js'] })),
    'implementer:a2000001': implEnv,
  }
  const { agent } = fakeAgent(script)
  return core().runPlan(planFixture(planOver), { agent, parallel: fakeParallel, log: () => {} })
}
const DEC_A = '11111111-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const DEC_B = '22222222-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

for (const altitudeSafe of [undefined, true]) {
  test(`S7 (decisionId): a stage envelope's decisionId and paused.decisionId reach the stage record (altitudeSafe ${altitudeSafe})`, async () => {
    const raised = await decisionRun({
      altitudeSafe,
      implEnv: envelope(implementerOutput(), { status: 'stopped', reason: 'awaiting_decision', decisionId: DEC_A }),
    })
    const rs = raised.items[0].stages.find((s) => s.seat === 'implementer')
    assert.equal(rs.decisionId, DEC_A)
    const paused = await decisionRun({
      altitudeSafe,
      implEnv: envelope(implementerOutput(), { status: 'stopped', reason: 'run_paused', paused: { decisionId: DEC_B } }),
    })
    assert.equal(paused.items[0].stages.find((s) => s.seat === 'implementer').decisionId, DEC_B)
  })

  test(`S7 (decisionId): no decisionId key when the envelope carries neither (altitudeSafe ${altitudeSafe})`, async () => {
    const plain = await decisionRun({ altitudeSafe, implEnv: envelope(implementerOutput()) })
    for (const st of plain.items[0].stages) assert.equal(Object.hasOwn(st, 'decisionId'), false)
  })
}
