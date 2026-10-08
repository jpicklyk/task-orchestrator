// Method B (run-exec-lib) coverage for the "entering seat returns no entry report" defect (item 91544425).
// Scenario ids S5-S7, S15 and probes P1-P4 match the item's frozen `test-plan` note. Oracles are the
// frozen planning decisions D1-D4 / corrections C3 in the item's `diagnosis` note, never observed output.
// Only exported surfaces of run-exec-lib.mjs and lib/wave-core.mjs are used.

import { test } from 'node:test'
import assert from 'node:assert/strict'

import { planFixture, itemFixture, stages } from './workflow-harness.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import { initState, next, prompt, validateAgainst, stageResult } from '../run-exec-lib.mjs'

const R7 = ['status', 'reason', 'notes', 'commits', 'files', 'modelReported', 'output']
const IMPL_OUTPUT = {
  mainFilesChanged: [], docFilesChanged: [], verify: [], failingExistingTests: [],
  preExistingFailures: [], publicSurface: 'none', deviations: 'none',
}
const PLANNER_OUTPUT = {
  proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
  decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
  mainFiles: [], docFiles: [], testFiles: [], existingTestEdits: [], redProofShape: 'none',
}

function env(output, extra = {}) {
  return {
    status: 'done', reason: 'ok', notes: [], commits: { pre: 'p1', post: 'p2' }, files: [],
    modelReported: 'sonnet', output, ...extra,
  }
}

function docFor(short, stageList, method = 'B') {
  const it = itemFixture({ short, stages: stageList })
  const args = planFixture({ items: [it] })
  return { contract: 'run-wave/plan-doc-v1', args, meta: { method } }
}

const SHORT = 'aaaaaaaa'

function implementerDoc() {
  return docFor(SHORT, [
    { seat: 'implementer', phase: 'work', enters: true, notes: [], writes: true, dispatch: {}, output: 'implementer-v1' },
  ])
}

function implementerResult(extra, textOverride) {
  const core = loadWaveCore()
  const doc = implementerDoc()
  const state = initState(doc, 'B')
  const text = textOverride !== undefined ? textOverride : JSON.stringify(env(IMPL_OUTPUT, extra))
  return stageResult(core, doc, state, SHORT, 'implementer', text)
}

// ---- D1 enforcement through validateAgainst + one retry ----

test('S5: entering stage with no entry retries once naming "$.entry: required", then stops "invalid envelope"', () => {
  const core = loadWaveCore()
  const doc = implementerDoc()
  const state = initState(doc, 'B')
  const text = JSON.stringify(env(IMPL_OUTPUT))
  const first = stageResult(core, doc, state, SHORT, 'implementer', text)
  assert.equal(first.status, 'retry')
  assert.ok(first.reason.includes('$.entry: required'), `reason was: ${first.reason}`)
  const second = stageResult(core, doc, first.state, SHORT, 'implementer', text)
  assert.equal(second.status, 'stopped')
  assert.equal(second.reason, 'invalid envelope')
})

test('S6: non-entering stage with no entry is valid and done (guard)', () => {
  const core = loadWaveCore()
  const doc = docFor(SHORT, [{ seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }])
  const state = initState(doc, 'B')
  const res = stageResult(core, doc, state, SHORT, 'planner', JSON.stringify(env(PLANNER_OUTPUT)))
  assert.equal(res.status, 'done')
})

test('S7: Method B prompt inlines a schema that requires entry for the implementer and not for the planner', () => {
  const core = loadWaveCore()
  const doc = docFor(SHORT, stages.featureTaskLike(), 'B')
  const state = initState(doc, 'B')
  state.outs[`${SHORT}:planner`] = PLANNER_OUTPUT
  const inlined = (seat) => {
    const text = prompt(core, doc, state, SHORT, seat)
    const line = text.split('\n').find((l) => l.startsWith('Envelope JSON schema: '))
    assert.ok(line, `${seat} prompt carries the Envelope JSON schema line`)
    return JSON.parse(line.slice('Envelope JSON schema: '.length))
  }
  const impl = inlined('implementer')
  assert.ok(impl.required.includes('entry'), 'implementer inlined schema requires entry')
  assert.deepEqual(inlined('planner').required.includes('entry'), false)
  assert.ok(validateAgainst(impl, env(IMPL_OUTPUT)).length > 0, 'validateAgainst flags the missing entry')
})

// ---- Symptom through the Method B loop ----

test('S15: Method B loop retries an implementer envelope lacking entry, then proceeds through every later stage to done', () => {
  const core = loadWaveCore()
  const doc = docFor(SHORT, stages.bugFixLike(), 'B')
  let state = initState(doc, 'B')
  const outputs = {
    planner: PLANNER_OUTPUT,
    implementer: IMPL_OUTPUT,
    'declarations-extractor': { declarations: 'none', harnessPointers: 'none', gaps: 'none' },
    'test-author': {
      returnLine: 'none', testFiles: [], scenariosCovered: 'none', verify: [],
      redAuthorTests: [], missingDeclaration: 'none', breachDisclosure: 'none',
    },
  }
  const implCalls = []
  const dispatched = []
  for (let i = 0; i < 30; i += 1) {
    const r = next(core, doc, state)
    if (r.complete) break
    assert.ok(r.dispatch.length > 0, 'loop must make progress')
    for (const d of r.dispatch) {
      dispatched.push(d.seat)
      let res
      if (d.seat === 'implementer') {
        // first reply lacks entry: stageResult asks for a retry; the retried reply carries it
        implCalls.push(1)
        res = stageResult(core, doc, state, d.item, d.seat, JSON.stringify(env(outputs[d.seat])))
        assert.equal(res.status, 'retry')
        implCalls.push(1)
        res = stageResult(core, doc, res.state, d.item, d.seat, JSON.stringify(env(outputs[d.seat], { entry: { applied: true, newRole: 'work' } })))
      } else {
        res = stageResult(core, doc, state, d.item, d.seat, JSON.stringify(env(outputs[d.seat])))
      }
      assert.equal(res.status, 'done', `${d.seat}: ${res.status}/${res.reason}`)
      state = res.state
    }
  }
  assert.equal(implCalls.length, 2)
  assert.ok(dispatched.includes('declarations-extractor'))
  assert.ok(dispatched.includes('test-author'))
  assert.equal(next(core, doc, state).complete, true)
})

// ---- Probes ----

test('P1: entering stage with entry {} and status done is schema-valid, then maps to stopped "no entry report"', () => {
  const res = implementerResult({ entry: {} })
  assert.equal(res.status, 'stopped')
  assert.equal(res.reason, 'no entry report')
})

test('P2: entering stage with entry null is rejected and retried', () => {
  const res = implementerResult({ entry: null })
  assert.equal(res.status, 'retry')
})

test('P3: entering stage with only a capitalised "Entry" key is rejected and retried', () => {
  const res = implementerResult({ Entry: { applied: true, newRole: 'work' } })
  assert.equal(res.status, 'retry')
})

test('P4a: entry.missingNotes as objects instead of key strings is rejected and retried (C3)', () => {
  const res = implementerResult({ entry: { applied: false, errorCode: 'gate_blocked', newRole: 'queue', missingNotes: [{ key: 'd' }] } })
  assert.equal(res.status, 'retry')
})

test('P4b: entry gate_blocked in queue with missingNotes ["d"] maps to stopped "queue gap d" (C3)', () => {
  const res = implementerResult({
    status: 'stopped', reason: 'gate',
    entry: { applied: false, errorCode: 'gate_blocked', previousRole: 'queue', missingNotes: ['d'] },
  })
  assert.equal(res.status, 'stopped')
  assert.equal(res.reason, 'queue gap d')
})