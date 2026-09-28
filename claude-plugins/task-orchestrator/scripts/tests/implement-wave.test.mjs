// Scheduler-core scenarios for workflows/implement-wave.js (B1a), authored BLIND per the
// test-author trait/skill: implement-wave.js itself is never opened by this file's author —
// every fact this file asserts against comes from the frozen `test-plan` MCP note on item
// 01bb5ffb, from the dispatch contract's Appendix B (public core-function contract), or from
// this harness (workflow-harness.mjs), never from reading the script under test.
//
// Scenario ids (S1-S11, S14, T-meta, T-core-pure, T-schema, T-args, R6) match the item's
// `test-plan` note. PROBE-prefixed tests cover the plan's adversarial PROBES list; they are not
// separately S-numbered but are recorded in test-manifest. Oracle citations use "P§n.n" for the
// b1-implement-wave.md plan sections as quoted verbatim inside test-plan/Appendix B — this file
// never opens that plan directly either; every P§ reference below is copied from test-plan or
// the dispatch contract's Appendix B text.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import {
  scriptText, coreSlice, loadMeta, loadScript, loadCore, fakeAgent, fakeParallel,
  planFixture, itemFixture, stages,
} from './workflow-harness.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SCRIPT_PATH = join(HERE, '..', '..', 'workflows', 'implement-wave.js')

// ---- shared envelope builders (valid-by-construction against the declared OUTPUT_SCHEMAS) ----

function plannerEnvelope(output = {}) {
  return {
    status: 'done', reason: 'ok', notes: [], commits: { pre: '', post: '' }, files: [],
    modelReported: 'fake', entry: { applied: true, newRole: 'work' },
    output: {
      proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
      decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
      mainFiles: [], docFiles: [], testFiles: [], existingTestEdits: [], redProofShape: 'none',
      ...output,
    },
  }
}

function implementerEnvelope({
  output = {}, entry = { applied: true, newRole: 'work' }, reason = 'ok', status = 'done',
} = {}) {
  return {
    status, reason, notes: [], commits: { pre: '', post: '' }, files: [],
    modelReported: 'fake', entry,
    output: {
      mainFilesChanged: [], docFilesChanged: [], verify: [], failingExistingTests: [],
      preExistingFailures: [], publicSurface: 'none', deviations: 'none',
      ...output,
    },
  }
}

// Bounded microtask-flush poll — NOT a sleep: it waits zero wall-clock time per iteration and
// terminates the instant the predicate is true, or fails fast after a bounded number of
// microtask ticks if the scheduler never reaches that state. Used only with fakeAgent's manual
// mode (S1, S3, S6a, S6b), which is itself the harness's virtual-clock interleaving mechanism.
async function waitUntil(predicate, { label = 'condition', maxTicks = 500 } = {}) {
  for (let i = 0; i < maxTicks; i++) {
    if (predicate()) return
    await Promise.resolve()
  }
  throw new Error(`waitUntil: timed out waiting for: ${label}`)
}

// ============================================================================================
// T-meta
// ============================================================================================

test('T-meta: meta is a pure literal evaluable in an empty vm context; name is "implement-wave"; phases are exactly Queue then Work', () => {
  const meta = loadMeta(SCRIPT_PATH)
  assert.equal(meta.name, 'implement-wave')
  // loadMeta evaluates in a separate vm context (per the harness), so its plain objects have a
  // different realm's Object.prototype — deepStrictEqual across realms reports "same structure
  // but not reference-equal" even when the data matches. Round-trip through JSON to compare
  // structurally in this realm instead of asserting reference/prototype identity.
  assert.deepEqual(JSON.parse(JSON.stringify(meta.phases)), [{ title: 'Queue' }, { title: 'Work' }])
  assert.equal(typeof meta.description, 'string')
  assert.equal(typeof meta.whenToUse, 'string')
})

// ============================================================================================
// T-core-pure
// ============================================================================================

function stripCommentsAndStrings(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ')
    .replace(/`(?:\\.|[^`\\])*`/g, ' ')
    .replace(/"(?:\\.|[^"\\])*"/g, ' ')
    .replace(/'(?:\\.|[^'\\])*'/g, ' ')
}

const RUNTIME_GLOBALS = ['agent', 'parallel', 'pipeline', 'phase', 'log', 'args', 'budget', 'workflow']

test('T-core-pure: the core region has no free reference to a runtime global (not after "." or as an object key), and no nondeterministic time/random call (P§2.2)', () => {
  const slice = coreSlice(SCRIPT_PATH)
  const stripped = stripCommentsAndStrings(slice)

  for (const name of RUNTIME_GLOBALS) {
    const re = new RegExp(`(?<!\\.)\\b${name}\\b(?!\\s*:)`, 'g')
    const matches = stripped.match(re) || []
    assert.equal(
      matches.length, 0,
      `core region must not free-reference runtime global "${name}" (found ${matches.length} occurrence(s))`,
    )
  }

  assert.doesNotMatch(stripped, /Date\.now\s*\(/, 'core must not call Date.now()')
  assert.doesNotMatch(stripped, /Math\.random\s*\(/, 'core must not call Math.random()')
  assert.doesNotMatch(stripped, /new\s+Date\s*\(/, 'core must not construct new Date()')
})

test('T-core-pure: loadCore builds the entire core region with no injected globals, exporting every declared core function (P§2.2, W6)', () => {
  assert.doesNotThrow(() => loadCore(SCRIPT_PATH))
  const core = loadCore(SCRIPT_PATH)
  for (const fn of [
    'normalizeArgs', 'normalizePath', 'preflight', 'makeMilestones', 'makeLocks', 'lockKeysFor',
    'overlapDeferral', 'envelopeSchema', 'mapEntry', 'mapStageResult', 'runItem', 'runPlan',
  ]) {
    assert.equal(typeof core[fn], 'function', `loadCore should export ${fn}`)
  }
})

// ============================================================================================
// T-schema
// ============================================================================================

const EXPECTED_OUTPUT_SCHEMA_REQUIRED = {
  'planner-v1': [
    'proceed', 'blockReason', 'diagnosisCorrections', 'defectClassSiblings', 'decisions',
    'missingApiOrSeam', 'testPlanStatus', 'mainFiles', 'docFiles', 'testFiles',
    'existingTestEdits', 'redProofShape',
  ],
  'implementer-v1': [
    'mainFilesChanged', 'docFilesChanged', 'verify', 'failingExistingTests',
    'preExistingFailures', 'publicSurface', 'deviations',
  ],
  'declarations-v1': ['declarations', 'harnessPointers', 'gaps'],
  'test-author-v1': [
    'returnLine', 'testFiles', 'scenariosCovered', 'verify', 'redAuthorTests',
    'missingDeclaration', 'breachDisclosure',
  ],
  'generic-v1': ['summary'],
}

function assertRequiredSubsetOfProperties(schema, path = 'schema') {
  if (!schema || typeof schema !== 'object') return
  if (schema.type === 'object') {
    const required = schema.required || []
    const props = schema.properties || {}
    for (const key of required) {
      assert.ok(key in props, `${path}: required "${key}" is missing from properties`)
    }
    for (const [key, sub] of Object.entries(props)) assertRequiredSubsetOfProperties(sub, `${path}.${key}`)
  }
  if (schema.type === 'array' && schema.items) assertRequiredSubsetOfProperties(schema.items, `${path}[]`)
}

test('T-schema: every OUTPUT_SCHEMAS id has required-subset-of-properties (recursively) and the exact required field list (P§5.2, P§2.2)', () => {
  const core = loadCore(SCRIPT_PATH)
  for (const [id, expectedRequired] of Object.entries(EXPECTED_OUTPUT_SCHEMA_REQUIRED)) {
    const schema = core.OUTPUT_SCHEMAS[id]
    assert.ok(schema, `OUTPUT_SCHEMAS should define "${id}"`)
    assert.deepEqual([...schema.required].sort(), [...expectedRequired].sort(), `OUTPUT_SCHEMAS["${id}"].required mismatch`)
    assertRequiredSubsetOfProperties(schema, id)
  }
})

test('T-schema: envelopeSchema requires status/reason/notes/commits/files/modelReported/output, is required-subset-of-properties, and embeds the requested output schema (P§5.1, P§2.2)', () => {
  const core = loadCore(SCRIPT_PATH)
  const schema = core.envelopeSchema('planner-v1')
  assert.deepEqual(
    [...schema.required].sort(),
    ['commits', 'files', 'modelReported', 'notes', 'output', 'reason', 'status'].sort(),
  )
  assertRequiredSubsetOfProperties(schema, 'envelope')
  assert.equal(schema.properties.output, core.OUTPUT_SCHEMAS['planner-v1'])
})

test('T-schema: envelopeSchema falls back to generic-v1 for an unknown output id (P§2.2)', () => {
  const core = loadCore(SCRIPT_PATH)
  const schema = core.envelopeSchema('totally-unknown-output-id')
  assert.equal(schema.properties.output, core.OUTPUT_SCHEMAS['generic-v1'])
})

test('T-schema: envelopeSchema\'s "extra" param adds new output ids but never overrides a built-in one (P§2.2)', () => {
  const core = loadCore(SCRIPT_PATH)
  const maliciousOverride = { 'planner-v1': { type: 'object', required: [], properties: {} } }
  const schemaTryingToOverride = core.envelopeSchema('planner-v1', maliciousOverride)
  assert.equal(
    schemaTryingToOverride.properties.output, core.OUTPUT_SCHEMAS['planner-v1'],
    'a built-in id must never be overridden by extra',
  )

  const customSchema = { type: 'object', required: ['x'], properties: { x: { type: 'string' } } }
  const schemaWithExtra = core.envelopeSchema('custom-added-id', { 'custom-added-id': customSchema })
  assert.equal(schemaWithExtra.properties.output, customSchema, 'extra should add a new output id')
})

// ============================================================================================
// T-args
// ============================================================================================

test('T-args: normalizeArgs rejects undefined, unparseable JSON, a bare object missing required fields, and null — each with a reason starting "invalid args" (P§3.1, W11)', () => {
  const core = loadCore(SCRIPT_PATH)
  for (const bad of [undefined, 'not json at all {', '{}', null]) {
    const r = core.normalizeArgs(bad)
    assert.equal(r.ok, false, `normalizeArgs(${JSON.stringify(bad)}) should fail`)
    assert.match(r.reason, /^invalid args/)
  }
})

test('T-args: normalizeArgs rejects a bad runId, a bad baseSha, and a bad item short, each with a reason starting "invalid args" (P§3.1)', () => {
  const core = loadCore(SCRIPT_PATH)
  // item.short must be a valid 8-char lowercase hex string (probed directly against
  // normalizeArgs, since neither test-plan nor Appendix B states the format and it is not a
  // declared literal — confirmed empirically via loadCore, never by reading src/main). Confirm
  // the baseline itself is accepted before asserting that a deliberately-broken variant fails,
  // so a "bad short" baseline can't make every sub-assertion below vacuously true.
  const okItem = itemFixture({ short: 'aaaa1111', stages: stages.schemaFree(), schemaFree: true, configFingerprint: null })
  const baseline = core.normalizeArgs(planFixture({ items: [okItem] }))
  assert.equal(baseline.ok, true, 'baseline fixture must itself be valid, or the sub-assertions below would pass vacuously')

  const badRunId = core.normalizeArgs(planFixture({ items: [okItem], runId: 12345 }))
  assert.equal(badRunId.ok, false)
  assert.match(badRunId.reason, /^invalid args/)

  const badBaseSha = core.normalizeArgs(planFixture({ items: [okItem], baseSha: '' }))
  assert.equal(badBaseSha.ok, false)
  assert.match(badBaseSha.reason, /^invalid args/)

  const badShortItem = { ...itemFixture({ short: 'aaaa2222', stages: stages.schemaFree(), schemaFree: true, configFingerprint: null }), short: 42 }
  const badShort = core.normalizeArgs(planFixture({ items: [badShortItem] }))
  assert.equal(badShort.ok, false)
  assert.match(badShort.reason, /^invalid args/)
})

test('T-args: normalizeArgs accepts a valid plan given as a JSON string (P§3.1, W11)', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'aaaa3333', stages: stages.schemaFree(), schemaFree: true, configFingerprint: null })
  const plan = planFixture({ items: [item] })
  const r = core.normalizeArgs(JSON.stringify(plan))
  assert.equal(r.ok, true)
})

test('T-args: each run-level §3.4 guard fires with its declared reason — missing capabilities.features (checked seats, then dispatchBySeat, then rules), seat-entry without phase0Hooks, waitsFor under pre-entered mode, waitsFor under per-item mode (P§3.4)', () => {
  const core = loadCore(SCRIPT_PATH)
  const okItem = itemFixture({ short: 'aaaa4444', stages: stages.schemaFree(), schemaFree: true, configFingerprint: null })

  const noSeats = core.normalizeArgs(planFixture({ items: [okItem], capabilities: { features: [], phase0Hooks: true } }))
  assert.equal(noSeats.ok, false)
  assert.equal(noSeats.reason, 'server lacks seats; front door must use its fallback')

  const noDispatchBySeat = core.normalizeArgs(planFixture({ items: [okItem], capabilities: { features: ['seats'], phase0Hooks: true } }))
  assert.equal(noDispatchBySeat.ok, false)
  assert.equal(noDispatchBySeat.reason, 'server lacks dispatchBySeat; front door must use its fallback')

  const noRules = core.normalizeArgs(planFixture({ items: [okItem], capabilities: { features: ['seats', 'dispatchBySeat'], phase0Hooks: true } }))
  assert.equal(noRules.ok, false)
  assert.equal(noRules.reason, 'server lacks rules; front door must use its fallback')

  const noPhase0Hooks = core.normalizeArgs(planFixture({
    items: [okItem],
    entryMode: 'seat',
    capabilities: { features: ['seats', 'dispatchBySeat', 'independent_of', 'rules'], phase0Hooks: false },
  }))
  assert.equal(noPhase0Hooks.ok, false)
  assert.equal(noPhase0Hooks.reason, 'seat entry requires phase0Hooks')

  const waitingItem = itemFixture({
    short: 'aaaa5555', stages: stages.schemaFree(), schemaFree: true, configFingerprint: null,
    waitsFor: [{ item: okItem.id, milestone: 'owner' }],
  })

  const preEnteredWithWaits = core.normalizeArgs(planFixture({ items: [okItem, waitingItem], entryMode: 'pre-entered' }))
  assert.equal(preEnteredWithWaits.ok, false)
  assert.equal(preEnteredWithWaits.reason, 'pre-entered mode forbids waitsFor')

  const perItemWithWaits = core.normalizeArgs(planFixture({ items: [okItem, waitingItem], worktreeMode: 'per-item' }))
  assert.equal(perItemWithWaits.ok, false)
  assert.equal(perItemWithWaits.reason, 'per-item mode forbids waitsFor')
})

test('T-args: loadScript with args undefined returns {started:false} via normalizeArgs\' failure (W11, P§3.4)', async () => {
  const script = loadScript(SCRIPT_PATH)
  const result = await script.run({ agent: async () => ({}), parallel: fakeParallel, log: () => {}, args: undefined })
  assert.equal(result.started, false)
  assert.match(result.reason, /^invalid args/)
})

// ============================================================================================
// R6
// ============================================================================================

test('R6: normalizePath lowercases the drive letter and converts backslashes to forward slashes, stripping a leading "./" (P§4.5)', () => {
  const core = loadCore(SCRIPT_PATH)
  assert.equal(core.normalizePath('C:\\a\\b'), 'c:/a/b')
  assert.equal(core.normalizePath('.\\src\\x.js'), 'src/x.js')
})

test('R6: lockKeysFor normalizes and dedupes mainFiles paths from the planner output into file:<path> lock keys (P§4.5)', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({
    short: 'r6lock',
    stages: [
      { seat: 'planner', phase: 'queue', writes: false, output: 'planner-v1', notes: [], dispatch: {} },
      { seat: 'implementer', phase: 'work', enters: true, writes: true, output: 'implementer-v1', notes: [], dispatch: {} },
    ],
  })
  const outs = { planner: { mainFiles: ['.\\src\\A.js', 'src/A.js'], docFiles: [] } }
  const plan = planFixture({ items: [item] }) // worktreeMode: 'shared' by default
  const implementerStage = item.stages[1]
  const keys = core.lockKeysFor(item, implementerStage, outs, plan)
  assert.deepEqual(keys, ['file:src/A.js'])
})

test('R6: preflight refuses an item whose worktree path is not absolute, with reason "worktree not absolute" (P§4.5)', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'relwt', stages: stages.featureTaskLike(), worktree: 'relative/path/wt' })
  const plan = planFixture({ items: [item] })
  const result = core.preflight(plan)
  assert.ok(result.refused.some((r) => r.id === item.id && r.reason === 'worktree not absolute'))
  assert.equal(result.runnable.length, 0)
})

// ============================================================================================
// S1 (happy) — independent streams run inside one shared parallel()
// ============================================================================================

test('S1: independent items are scheduled inside one shared parallel() — item A\'s implementer call can start before item B\'s planner call has resolved (P§4.1, W7)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const itemA = itemFixture({ short: 'parA', stages: stages.featureTaskLike() })
  const itemB = itemFixture({ short: 'parB', stages: stages.featureTaskLike() })
  const plan = planFixture({ items: [itemA, itemB] })

  const { agent, calls, release, pending } = fakeAgent({}, { manual: true })
  const runPromise = core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  await waitUntil(
    () => pending().includes(`planner:${itemA.short}`) && pending().includes(`planner:${itemB.short}`),
    { label: 'both planners pending' },
  )

  release(`planner:${itemA.short}`)
  await waitUntil(() => pending().includes(`implementer:${itemA.short}`), { label: 'implementer:A pending' })
  const implACall = calls.find((c) => c.label === `implementer:${itemA.short}`)
  const implAt0 = implACall.t0

  assert.ok(
    pending().includes(`planner:${itemB.short}`),
    'B\'s planner should still be unresolved while A\'s implementer has already started',
  )

  release(`planner:${itemB.short}`)
  await waitUntil(() => calls.find((c) => c.label === `planner:${itemB.short}`).t1 !== null, { label: 'planner:B resolved' })
  const plannerBCall = calls.find((c) => c.label === `planner:${itemB.short}`)

  assert.ok(implAt0 < plannerBCall.t1, 'A\'s implementer must have started (lower tick) before B\'s planner resolved')

  release(`implementer:${itemA.short}`)
  await waitUntil(() => pending().includes(`implementer:${itemB.short}`), { label: 'implementer:B pending' })
  release(`implementer:${itemB.short}`)

  const result = await runPromise
  assert.equal(result.started, true)
})

// ============================================================================================
// S2 (happy) — deferred passthrough
// ============================================================================================

test('S2: result.deferred passes plan.deferred through verbatim, and no dispatched call\'s label or prompt names a deferred item (P§3.3)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const runnable = itemFixture({ short: 'runnableOne', stages: stages.featureTaskLike() })
  const deferredEntry = { id: 'ghost-deferred-item-1', reason: 'deferred in a previous run' }
  const plan = planFixture({ items: [runnable], deferred: [deferredEntry] })

  const { agent, calls } = fakeAgent({})
  const result = await core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  assert.deepEqual(result.deferred, [deferredEntry])
  assert.ok(calls.length > 0)
  for (const call of calls) {
    assert.ok(!call.label.includes('ghost-deferred-item-1'), `call label "${call.label}" must not reference the deferred item`)
    assert.ok(!call.prompt.includes('ghost-deferred-item-1'), `call prompt for "${call.label}" must not reference the deferred item`)
  }
})

test('S2: result.deferred is [] when plan.deferred is absent/empty (P§3.3)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const runnable = itemFixture({ short: 'runnableTwo', stages: stages.featureTaskLike() })
  const plan = planFixture({ items: [runnable] }) // deferred: [] by planFixture default
  const { agent } = fakeAgent({})
  const result = await core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })
  assert.deepEqual(result.deferred, [])
})

// ============================================================================================
// S3 (happy) — in-run milestone wait
// ============================================================================================

test('S3: item B waitsFor item A\'s "implementer" milestone — B\'s first call does not start until A\'s implementer stage completes; B still reaches done (P§4.2)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const itemA = itemFixture({ short: 'msA', stages: stages.featureTaskLike() })
  const itemB = itemFixture({
    short: 'msB', stages: stages.featureTaskLike(), waitsFor: [{ item: itemA.id, milestone: 'implementer' }],
  })
  const plan = planFixture({ items: [itemA, itemB] })

  const { agent, calls, release, pending } = fakeAgent({}, { manual: true })
  const runPromise = core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  await waitUntil(() => pending().includes(`planner:${itemA.short}`), { label: 'planner:A pending' })
  assert.ok(!calls.some((c) => c.label.endsWith(`:${itemB.short}`)), 'B must not have made any call yet — it is waiting on A\'s implementer milestone')

  release(`planner:${itemA.short}`)
  await waitUntil(() => pending().includes(`implementer:${itemA.short}`), { label: 'implementer:A pending' })
  assert.ok(!calls.some((c) => c.label.endsWith(`:${itemB.short}`)), 'B must still not have made any call — A\'s implementer has not completed yet')

  release(`implementer:${itemA.short}`)
  const implACall = calls.find((c) => c.label === `implementer:${itemA.short}`)
  await waitUntil(() => pending().includes(`planner:${itemB.short}`), { label: 'planner:B pending once A\'s milestone settles' })
  const plannerBCall = calls.find((c) => c.label === `planner:${itemB.short}`)

  assert.ok(plannerBCall.t0 > implACall.t1, 'B\'s first call must start only after A\'s implementer stage tick completed')

  release(`planner:${itemB.short}`)
  await waitUntil(() => pending().includes(`implementer:${itemB.short}`), { label: 'implementer:B pending' })
  release(`implementer:${itemB.short}`)

  const result = await runPromise
  const rB = result.items.find((r) => r.short === itemB.short)
  assert.equal(rB.status, 'done')
})

// ============================================================================================
// S4 (failure) — dependency_blocked entry + dependent in-run blocker
// ============================================================================================

test('S4 (unit): mapEntry maps entry.errorCode "dependency_blocked" with blockers to deferred "blocked by <ids>" (P§4.1, P§4.3)', () => {
  const core = loadCore(SCRIPT_PATH)
  const r = core.mapEntry({ entry: { errorCode: 'dependency_blocked', blockers: ['blocker-x'] } }, 'seat')
  assert.deepEqual(r, { status: 'deferred', reason: 'blocked by blocker-x' })
})

test('S4: an item entry-blocked (dependency_blocked) is deferred "blocked by <ids>"; a dependent waitsFor()ing its seat is deferred as an in-run blocker (P§4.1, P§4.3)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const itemB = itemFixture({ short: 'depB', stages: stages.featureTaskLike() })
  const itemC = itemFixture({
    short: 'depC', stages: stages.featureTaskLike(), waitsFor: [{ item: itemB.id, milestone: 'implementer' }],
  })
  const plan = planFixture({ items: [itemB, itemC] })

  const script = {
    [`implementer:${itemB.short}`]: implementerEnvelope({
      entry: { errorCode: 'dependency_blocked', blockers: ['ext-dep-1'] },
    }),
  }
  const { agent } = fakeAgent(script)
  const result = await core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  const rB = result.items.find((r) => r.short === itemB.short)
  const rC = result.items.find((r) => r.short === itemC.short)
  assert.equal(rB.status, 'deferred')
  assert.equal(rB.reason, 'blocked by ext-dep-1')
  assert.equal(rC.status, 'deferred')
  assert.equal(rC.reason, `in-run blocker ${itemB.short} did not reach implementer`)
})

// ============================================================================================
// S5 (failure) — resource_unavailable, no retry, later stages never called
// ============================================================================================

test('S5 (unit): mapEntry maps entry.errorCode "resource_unavailable" to deferred "resource <ids>" (P§4.3)', () => {
  const core = loadCore(SCRIPT_PATH)
  const r = core.mapEntry({ entry: { errorCode: 'resource_unavailable', contendedResources: ['db'] } }, 'seat')
  assert.deepEqual(r, { status: 'deferred', reason: 'resource db' })
})

test('S5: resource_unavailable at entry defers the item after exactly one implementer call; later stages in its sequence never run (P§4.3)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'resA', stages: stages.bugFixLike() })
  const plan = planFixture({ items: [item] })

  const script = {
    [`implementer:${item.short}`]: implementerEnvelope({
      entry: { errorCode: 'resource_unavailable', contendedResources: ['scratch-db'] },
    }),
  }
  const { agent, calls } = fakeAgent(script)
  const result = await core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  const r = result.items.find((it) => it.short === item.short)
  assert.equal(r.status, 'deferred')
  assert.equal(r.reason, 'resource scratch-db')

  const implCalls = calls.filter((c) => c.label === `implementer:${item.short}`)
  assert.equal(implCalls.length, 1)
  assert.ok(!calls.some((c) => c.label === `declarations-extractor:${item.short}`))
  assert.ok(!calls.some((c) => c.label === `test-author:${item.short}`))
})

// ============================================================================================
// S6a (happy) — shared-mode lock serialization, disjoint intervals
// ============================================================================================

test('S6a: shared-mode file locks serialize contending implementer stages — releasing item A\'s planner first gives it the lock, and item B\'s implementer call only starts after A\'s completes (disjoint intervals, A first) (P§4.5)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const itemA = itemFixture({ short: 'lockA', stages: stages.featureTaskLike() })
  const itemB = itemFixture({ short: 'lockB', stages: stages.featureTaskLike() })
  const plan = planFixture({ items: [itemA, itemB] }) // worktreeMode: 'shared' by default

  const script = {
    [`planner:${itemA.short}`]: plannerEnvelope({ mainFiles: ['src/x.js'] }),
    [`planner:${itemB.short}`]: plannerEnvelope({ mainFiles: ['src/x.js'] }),
  }
  const { agent, calls, release, pending } = fakeAgent(script, { manual: true })
  const runPromise = core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  await waitUntil(
    () => pending().includes(`planner:${itemA.short}`) && pending().includes(`planner:${itemB.short}`),
    { label: 'both planners pending' },
  )

  release(`planner:${itemA.short}`)
  await waitUntil(() => pending().includes(`implementer:${itemA.short}`), { label: 'implementer:A pending after its planner released' })

  release(`planner:${itemB.short}`)
  // B's implementer wants the same file lock A already holds; withLocks must not invoke its fn
  // (which is what makes the agent() call) until the lock is free — so it must not be issued yet.
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
  assert.ok(
    !calls.some((c) => c.label === `implementer:${itemB.short}`),
    'implementer:B should be queued behind the lock A holds, not yet called',
  )

  release(`implementer:${itemA.short}`)
  await waitUntil(() => pending().includes(`implementer:${itemB.short}`), { label: 'implementer:B pending once A releases the lock' })
  release(`implementer:${itemB.short}`)

  await runPromise

  const implACall = calls.find((c) => c.label === `implementer:${itemA.short}`)
  const implBCall = calls.find((c) => c.label === `implementer:${itemB.short}`)
  assert.ok(implACall.t1 < implBCall.t0, 'A\'s implementer interval must fully precede B\'s — disjoint, A first')
})

// ============================================================================================
// S6b (failure) — per-item overlap deferral
// ============================================================================================

test('S6b (unit): overlapDeferral in "per-item" mode returns a deferral when files overlap with a higher-priority item; "shared" mode never defers on overlap; disjoint files never defer (P§4.1)', () => {
  const core = loadCore(SCRIPT_PATH)
  const mine = { short: 'lowpri', output: { mainFiles: ['src/x.js'], docFiles: [], testFiles: [], existingTestEdits: [] } }
  const higher = [{ short: 'hipri', output: { mainFiles: ['src/x.js'], docFiles: [], testFiles: [], existingTestEdits: [] } }]

  assert.deepEqual(core.overlapDeferral(mine, higher, 'per-item'), { reason: 'overlap hipri' })
  assert.equal(core.overlapDeferral(mine, higher, 'shared'), null)

  const disjointHigher = [{ short: 'hipri2', output: { mainFiles: ['src/other.js'], docFiles: [], testFiles: [], existingTestEdits: [] } }]
  assert.equal(core.overlapDeferral(mine, disjointHigher, 'per-item'), null)
})

test('S6b: in per-item worktree mode, a lower-priority item whose planner output overlaps a higher-priority item\'s files is deferred "overlap <short>" and never reaches its own work call (P§4.1)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const itemA = itemFixture({ short: 'ovA', stages: stages.featureTaskLike() })
  const itemB = itemFixture({ short: 'ovB', stages: stages.featureTaskLike() })
  const plan = planFixture({ items: [itemA, itemB], worktreeMode: 'per-item' })

  const script = {
    [`planner:${itemA.short}`]: plannerEnvelope({ mainFiles: ['src/shared.js'] }),
    [`planner:${itemB.short}`]: plannerEnvelope({ mainFiles: ['src/shared.js'] }),
  }
  const { agent, calls, release, pending } = fakeAgent(script, { manual: true })
  const runPromise = core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  await waitUntil(
    () => pending().includes(`planner:${itemA.short}`) && pending().includes(`planner:${itemB.short}`),
    { label: 'both planners pending' },
  )
  release(`planner:${itemA.short}`)
  release(`planner:${itemB.short}`)

  // Item A has no overlap (nothing outranks it), so it proceeds to its own implementer stage —
  // that call must be released too, or runPlan's single parallel() never settles for item A.
  await waitUntil(() => pending().includes(`implementer:${itemA.short}`), { label: 'implementer:A pending' })
  release(`implementer:${itemA.short}`)

  const result = await runPromise
  const rB = result.items.find((r) => r.short === itemB.short)
  assert.equal(rB.status, 'deferred')
  assert.equal(rB.reason, `overlap ${itemA.short}`)
  assert.ok(!calls.some((c) => c.label === `implementer:${itemB.short}`))
})

// ============================================================================================
// S7 (happy) — mixed item shapes, own-seat labels, no per-type-config literal
// ============================================================================================

test('S7: each dispatched call\'s label matches one of its own item\'s seats; a feature-like item never calls test-author/declarations-extractor; and the script text names no config type-id literal (P§2.2, T-no-type-names)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const bugItem = itemFixture({ short: 'mixBug', stages: stages.bugFixLike() })
  const featureItem = itemFixture({ short: 'mixFeature', stages: stages.featureTaskLike() })
  const plan = planFixture({ items: [bugItem, featureItem] })

  const { agent, calls } = fakeAgent({})
  await core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  const itemsByShort = { [bugItem.short]: bugItem, [featureItem.short]: featureItem }
  assert.ok(calls.length > 0)
  for (const call of calls) {
    const [seat, short] = call.label.split(':')
    const item = itemsByShort[short]
    assert.ok(item, `label "${call.label}" should belong to one of the two dispatched items`)
    assert.ok(
      item.stages.some((s) => s.seat === seat),
      `label "${call.label}": seat "${seat}" should be one of item "${short}"'s own declared stages`,
    )
  }

  assert.ok(!calls.some((c) => c.label === `test-author:${featureItem.short}`), 'a feature-like item must never dispatch a test-author call')
  assert.ok(!calls.some((c) => c.label === `declarations-extractor:${featureItem.short}`), 'a feature-like item must never dispatch a declarations-extractor call')

  const text = scriptText(SCRIPT_PATH)
  for (const typeName of ['bug-fix', 'feature-task', 'plugin-change', 'feature-implementation']) {
    assert.ok(!text.includes(typeName), `implement-wave.js must not contain the per-type-config literal "${typeName}"`)
  }
})

// ============================================================================================
// S8 (happy) — schema-free single-stage item
// ============================================================================================

test('S8: a 1-stage schema-free item makes exactly one call, reaches done, and the result carries schemaFree:true (P§9.2#8)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'sfree', stages: stages.schemaFree(), schemaFree: true, configFingerprint: null })
  const plan = planFixture({ items: [item] })

  const { agent, calls } = fakeAgent({})
  const result = await core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  assert.equal(calls.length, 1)
  const r = result.items.find((it) => it.short === item.short)
  assert.equal(r.status, 'done')
  assert.equal(r.schemaFree, true)
})

// ============================================================================================
// S9 (failure) — unowned required notes refused at preflight, zero calls
// ============================================================================================

test('S9: an item with unowned required notes is refused by preflight with "unowned required note(s) <keys>", and never dispatched (0 calls) (P§3.4)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'unown', stages: stages.featureTaskLike(), unownedRequired: ['note-a', 'note-b'] })
  const plan = planFixture({ items: [item] })

  const pf = core.preflight(plan)
  assert.deepEqual(pf.refused, [{ id: item.id, reason: 'unowned required note(s) note-a, note-b' }])
  assert.equal(pf.runnable.length, 0)

  const { agent, calls } = fakeAgent({})
  const result = await core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })
  assert.equal(calls.length, 0)
  assert.ok(result.refused.some((r) => r.id === item.id && r.reason === 'unowned required note(s) note-a, note-b'))
})

// ============================================================================================
// S10 (failure) — schema-changed stops the item; dependent in-run blocker
// ============================================================================================

test('S10 (unit): mapStageResult maps env.reason "schema-changed" to stopped, ahead of the entry/status checks (P§4.6)', () => {
  const core = loadCore(SCRIPT_PATH)
  const stage = { seat: 'implementer', output: 'implementer-v1', enters: true, writes: true, notes: [] }
  const env = {
    status: 'done', reason: 'schema-changed', notes: [], commits: { pre: '', post: '' }, files: [],
    modelReported: 'x', output: {}, entry: { applied: true, newRole: 'work' },
  }
  assert.deepEqual(core.mapStageResult(stage, env, 'seat'), { status: 'stopped', reason: 'schema-changed' })
})

test('S10: a stopped/"schema-changed" stage stops that item, and a dependent waitsFor()ing on it is deferred as an in-run blocker (P§4.6)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const itemA = itemFixture({ short: 'scA', stages: stages.featureTaskLike() })
  const itemC = itemFixture({
    short: 'scC', stages: stages.featureTaskLike(), waitsFor: [{ item: itemA.id, milestone: 'implementer' }],
  })
  const plan = planFixture({ items: [itemA, itemC] })

  const script = { [`implementer:${itemA.short}`]: implementerEnvelope({ reason: 'schema-changed' }) }
  const { agent } = fakeAgent(script)
  const result = await core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  const rA = result.items.find((r) => r.short === itemA.short)
  const rC = result.items.find((r) => r.short === itemC.short)
  assert.equal(rA.status, 'stopped')
  assert.equal(rA.reason, 'schema-changed')
  assert.equal(rC.status, 'deferred')
  assert.equal(rC.reason, `in-run blocker ${itemA.short} did not reach implementer`)
})

// ============================================================================================
// S11a (happy) — replay determinism
// ============================================================================================

test('S11a: running the same args twice produces an identical (label, prompt) call sequence (W7)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const buildPlan = () => planFixture({
    items: [
      itemFixture({ short: 'i1', stages: stages.featureTaskLike() }),
      itemFixture({ short: 'i2', stages: stages.featureTaskLike() }),
    ],
  })

  const runOnce = async () => {
    const { agent, calls } = fakeAgent({})
    await core.runPlan(buildPlan(), { agent, parallel: fakeParallel, log: () => {} })
    return calls.map((c) => ({ label: c.label, prompt: c.prompt }))
  }

  const seq1 = await runOnce()
  const seq2 = await runOnce()
  assert.deepEqual(seq1, seq2)
  assert.ok(seq1.length > 0)
})

// ============================================================================================
// S11b (happy) — alreadyInPhase / gate_blocked-from-work entry outcomes both map to done
// ============================================================================================

test('S11b: mapEntry — entry.alreadyInPhase, and gate_blocked with previousRole "work", both map to status done (P§4.3)', () => {
  const core = loadCore(SCRIPT_PATH)

  const r1 = core.mapEntry({ entry: { applied: true, alreadyInPhase: true } }, 'seat')
  assert.equal(r1.status, 'done')
  assert.equal(typeof r1.reason, 'string')

  const r2 = core.mapEntry({ entry: { errorCode: 'gate_blocked', previousRole: 'work' } }, 'seat')
  assert.equal(r2.status, 'done')
  assert.equal(typeof r2.reason, 'string')
})

// ============================================================================================
// S14 (failure) — config_unavailable (after seat-side retry) defers; independent item still done
// ============================================================================================

test('S14 (unit): mapEntry maps entry.errorCode "config_unavailable" to deferred "config-unavailable" (P§4.3)', () => {
  const core = loadCore(SCRIPT_PATH)
  const r = core.mapEntry({ entry: { errorCode: 'config_unavailable', retried: true } }, 'seat')
  assert.deepEqual(r, { status: 'deferred', reason: 'config-unavailable' })
})

test('S14 (unit): mapStageResult maps env.reason "config-unavailable" to deferred, independent of env.status (P§4.6)', () => {
  const core = loadCore(SCRIPT_PATH)
  const stage = { seat: 'implementer', output: 'implementer-v1', enters: true, writes: true, notes: [] }
  const env = {
    status: 'done', reason: 'config-unavailable', notes: [], commits: { pre: '', post: '' }, files: [],
    modelReported: 'x', output: {},
  }
  assert.deepEqual(core.mapStageResult(stage, env, 'seat'), { status: 'deferred', reason: 'config-unavailable' })
})

test('S14: config_unavailable (after the seat\'s own retry) maps to deferred "config-unavailable"; a dependent is deferred as an in-run blocker; an independent third item still reaches done (P§4.3, P§4.6)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const itemA = itemFixture({ short: 'cfgA', stages: stages.featureTaskLike() })
  const itemB = itemFixture({
    short: 'cfgB', stages: stages.featureTaskLike(), waitsFor: [{ item: itemA.id, milestone: 'implementer' }],
  })
  const itemC = itemFixture({ short: 'cfgC', stages: stages.featureTaskLike() })
  const plan = planFixture({ items: [itemA, itemB, itemC] })

  const script = {
    [`implementer:${itemA.short}`]: implementerEnvelope({
      reason: 'config-unavailable',
      entry: { errorCode: 'config_unavailable', retried: true },
    }),
  }
  const { agent, calls } = fakeAgent(script)
  const result = await core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  const rA = result.items.find((r) => r.short === itemA.short)
  const rB = result.items.find((r) => r.short === itemB.short)
  const rC = result.items.find((r) => r.short === itemC.short)

  assert.equal(rA.status, 'deferred')
  assert.equal(rA.reason, 'config-unavailable')
  assert.equal(rB.status, 'deferred')
  assert.equal(rB.reason, `in-run blocker ${itemA.short} did not reach implementer`)
  assert.equal(rC.status, 'done')
  assert.ok(calls.some((c) => c.label === `implementer:${itemC.short}`), 'the independent third item should still have been dispatched')
})

// ============================================================================================
// PROBES (adversarial probe catalog per test-plan; not separately S-numbered)
// ============================================================================================

test('PROBE: makeMilestones settle is first-wins — a later settle for the same (item, seat) is ignored', async () => {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'm1', stages: stages.featureTaskLike() })
  const milestones = core.makeMilestones([item])
  milestones.settle(item.id, 'planner', { status: 'done', reason: 'first', output: {} })
  milestones.settle(item.id, 'planner', { status: 'stopped', reason: 'second', output: {} })
  const v = await milestones.get(item.id, 'planner')
  assert.equal(v.status, 'done')
  assert.equal(v.reason, 'first')
})

test('PROBE: makeMilestones releaseFrom settles remaining stages null but never overrides an already-settled one', async () => {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'm2', stages: stages.featureTaskLike() }) // [planner, implementer]
  const milestones = core.makeMilestones([item])
  milestones.settle(item.id, 'planner', { status: 'done', reason: 'kept', output: {} })
  milestones.releaseFrom(item, 0)
  const plannerV = await milestones.get(item.id, 'planner')
  const implV = await milestones.get(item.id, 'implementer')
  assert.equal(plannerV.reason, 'kept')
  assert.equal(implV, null)
})

test('PROBE: makeLocks withLocks releases the lock when fn rejects, so a subsequent acquisition of the same key is not deadlocked', { timeout: 3000 }, async () => {
  const core = loadCore(SCRIPT_PATH)
  const locks = core.makeLocks()
  await assert.rejects(() => locks.withLocks(['file:probe-reject.js'], async () => { throw new Error('boom') }))
  const second = await locks.withLocks(['file:probe-reject.js'], async () => 'ok')
  assert.equal(second, 'ok')
})

test('PROBE: makeLocks withLocks lets holders of disjoint keys run concurrently rather than fully serializing', async () => {
  const core = loadCore(SCRIPT_PATH)
  const locks = core.makeLocks()
  let aStarted = false
  let bStarted = false
  let bothRunning = false

  const a = locks.withLocks(['file:probe-a.js'], async () => {
    aStarted = true
    await Promise.resolve()
    if (bStarted) bothRunning = true
    return 'a'
  })
  const b = locks.withLocks(['file:probe-b.js'], async () => {
    bStarted = true
    await Promise.resolve()
    if (aStarted) bothRunning = true
    return 'b'
  })

  const [ra, rb] = await Promise.all([a, b])
  assert.equal(ra, 'a')
  assert.equal(rb, 'b')
  assert.equal(bothRunning, true, 'disjoint-key holders should be able to interleave rather than being fully serialized')
})

test('PROBE: mapStageResult maps a null env (agent returned null) to stopped "agent returned null"', () => {
  const core = loadCore(SCRIPT_PATH)
  const stage = { seat: 'implementer', output: 'implementer-v1', enters: false, writes: true, notes: [] }
  assert.deepEqual(core.mapStageResult(stage, null, 'seat'), { status: 'stopped', reason: 'agent returned null' })
})

test('PROBE: a refused item releases its milestones with null, so a dependent waitsFor()ing on it is deferred rather than hanging (P§3.4)', async () => {
  const core = loadCore(SCRIPT_PATH)
  const refusedItem = itemFixture({ short: 'refA', stages: stages.featureTaskLike(), unownedRequired: ['some-note'] })
  const dependent = itemFixture({
    short: 'refB', stages: stages.featureTaskLike(), waitsFor: [{ item: refusedItem.id, milestone: 'implementer' }],
  })
  const plan = planFixture({ items: [refusedItem, dependent] })

  const { agent, calls } = fakeAgent({})
  const result = await core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  assert.ok(result.refused.some((r) => r.id === refusedItem.id))
  const rDep = result.items.find((r) => r.short === dependent.short)
  assert.equal(rDep.status, 'deferred')
  assert.equal(rDep.reason, `in-run blocker ${refusedItem.short} did not reach implementer`)
  assert.ok(!calls.some((c) => c.label.includes(refusedItem.short)))
})

test('PROBE: preflight refuses a waitsFor cycle, an unknown waitsFor item, an unknown waitsFor seat, a duplicate seat, and >1 entering stage in one phase', () => {
  const core = loadCore(SCRIPT_PATH)

  const itemX = itemFixture({ short: 'cx', stages: stages.featureTaskLike(), waitsFor: [{ item: 'item-cy', milestone: 'implementer' }] })
  const itemY = itemFixture({ short: 'cy', stages: stages.featureTaskLike(), waitsFor: [{ item: itemX.id, milestone: 'implementer' }] })
  const cycleResult = core.preflight(planFixture({ items: [itemX, itemY] }))
  assert.ok(cycleResult.refused.some((r) => r.reason === 'waitsFor cycle'))

  const unknownItem = itemFixture({ short: 'ux', stages: stages.featureTaskLike(), waitsFor: [{ item: 'does-not-exist', milestone: 'implementer' }] })
  const unknownResult = core.preflight(planFixture({ items: [unknownItem] }))
  assert.ok(unknownResult.refused.some((r) => r.reason === 'waitsFor unknown item does-not-exist'))

  const unknownSeatTarget = itemFixture({ short: 'us', stages: stages.featureTaskLike() })
  const unknownSeatWaiter = itemFixture({
    short: 'us2', stages: stages.featureTaskLike(), waitsFor: [{ item: unknownSeatTarget.id, milestone: 'nonexistent-seat' }],
  })
  const unknownSeatResult = core.preflight(planFixture({ items: [unknownSeatTarget, unknownSeatWaiter] }))
  assert.ok(unknownSeatResult.refused.some((r) => r.reason === 'waitsFor unknown seat nonexistent-seat'))

  const dupSeatItem = itemFixture({
    short: 'dup',
    stages: [
      { seat: 'implementer', phase: 'work', enters: true, writes: true, output: 'implementer-v1', notes: [], dispatch: {} },
      { seat: 'implementer', phase: 'work', writes: true, output: 'implementer-v1', notes: [], dispatch: {} },
    ],
  })
  const dupResult = core.preflight(planFixture({ items: [dupSeatItem] }))
  assert.ok(dupResult.refused.some((r) => r.reason === 'duplicate seat implementer'))

  const twoEntersItem = itemFixture({
    short: 'te',
    stages: [
      { seat: 'implementer', phase: 'work', enters: true, writes: true, output: 'implementer-v1', notes: [], dispatch: {} },
      { seat: 'owner', phase: 'work', enters: true, writes: true, output: 'implementer-v1', notes: [], dispatch: {} },
    ],
  })
  const teResult = core.preflight(planFixture({ items: [twoEntersItem] }))
  assert.ok(teResult.refused.some((r) => r.reason === 'multiple entry stages in phase work'))
})

test('PROBE: an item with waitsFor:[] and an item with waitsFor entirely absent are both treated as having no wait (no refusal, both runnable)', () => {
  const core = loadCore(SCRIPT_PATH)
  const withEmpty = itemFixture({
    short: 'we', stages: stages.schemaFree(), schemaFree: true, configFingerprint: null, waitsFor: [],
  })
  const absentItem = itemFixture({
    short: 'wa', stages: stages.schemaFree(), schemaFree: true, configFingerprint: null,
  })
  delete absentItem.waitsFor

  const result = core.preflight(planFixture({ items: [withEmpty, absentItem] }))
  assert.equal(result.refused.length, 0)
  assert.equal(result.runnable.length, 2)
})
