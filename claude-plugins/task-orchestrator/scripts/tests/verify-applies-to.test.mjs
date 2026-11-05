// appliesTo per verify entry and the knownRed baseline in run-profile (#639).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { loadCore, planFixture, itemFixture, stages } from './workflow-harness.mjs'
import { validateSnapshot, buildPlanDoc } from '../run-planner-lib.mjs'
import { verify } from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..')
const REPO_ROOT = join(PLUGIN_ROOT, '..', '..')
const SCRIPT_PATH = join(PLUGIN_ROOT, 'workflows', 'implement-wave.js')
const NOW = '2026-09-28T12:00:00Z'

const snapshot = (profile) => {
  const s = JSON.parse(readFileSync(join(HERE, 'fixtures', 'run-planner', 'independent-two.json'), 'utf8'))
  s.profile = profile
  return s
}
const profileWith = (extra) => ({ shell: 'bash', verify: [], searchScope: ['/repo'], ...extra })
const entry = (extra) => ({ name: 'e', command: 'echo e', seats: ['implementer'], ...extra })

// ---- validation ----

test('validate: a good appliesTo and good knownRed entries pass', () => {
  const p = profileWith({
    verify: [entry({ appliesTo: ['current/**', 'docs/*.md'] })],
    knownRed: [{ test: 'FooTest', reason: 'flaky', expires: '2026-12-01' }, { test: 'BarTest', reason: 'x', tracking: '#12' }],
  })
  const r = validateSnapshot(snapshot(p))
  assert.equal(r.ok, true, JSON.stringify(r.errors))
})

test('validate: bad appliesTo values are rejected with the named error', () => {
  const msg = 'profile.verify[0].appliesTo must be an array of repo-relative patterns'
  for (const bad of [[], '/abs/x', ['/abs/x'], ['C:/x'], ['../x'], ['a/../b'], [1], [''], null]) {
    const r = validateSnapshot(snapshot(profileWith({ verify: [entry({ appliesTo: bad })] })))
    assert.equal(r.ok, false, JSON.stringify(bad))
    assert.ok(r.errors.includes(msg), JSON.stringify(r.errors))
  }
})

test('validate: knownRed entries need test, reason and a valid expires or tracking', () => {
  const msg = (i) => `profile.knownRed[${i}] must have test, reason and expires (YYYY-MM-DD) or tracking`
  const cases = [
    { test: 'T', reason: 'r' },
    { test: 'T', reason: 'r', expires: '2026-13-45' },
    { test: 'T', reason: 'r', expires: '2026-02-31' },
    { test: 'T', reason: 'r', expires: 'soon' },
    { test: '', reason: 'r', tracking: '#1' },
    { test: 'T', tracking: '#1' },
    { test: 'T', reason: 'r', tracking: '' },
  ]
  for (const bad of cases) {
    const r = validateSnapshot(snapshot(profileWith({ knownRed: [{ test: 'ok', reason: 'ok', tracking: '#1' }, bad] })))
    assert.equal(r.ok, false, JSON.stringify(bad))
    assert.ok(r.errors.includes(msg(1)), JSON.stringify(r.errors))
    assert.ok(!r.errors.includes(msg(0)))
  }
})

test('validate: the repo run-profile.json passes validateSnapshot in full and scopes its entries', () => {
  const profile = JSON.parse(readFileSync(join(REPO_ROOT, '.taskorchestrator', 'run-profile.json'), 'utf8'))
  const r = validateSnapshot(snapshot(profile))
  assert.equal(r.ok, true, JSON.stringify(r.errors))
  const byName = Object.fromEntries(profile.verify.map((v) => [v.name, v]))
  for (const n of ['compile-self-check', 'owned-tests', 'wave-build']) assert.deepEqual(byName[n].appliesTo, ['current/**'])
  for (const n of ['plugin-node-tests', 'plugin-node-owned-tests']) assert.deepEqual(byName[n].appliesTo, ['claude-plugins/**'])
  assert.ok(Array.isArray(profile.knownRed))
})

// ---- buildPlanDoc ----

test('buildPlanDoc keeps live knownRed entries and carries appliesTo; drops an expired one with a warning', () => {
  const p = profileWith({
    verify: [entry({ appliesTo: ['current/**'] })],
    knownRed: [
      { test: 'LiveTest', reason: 'r', expires: '2026-09-28' },
      { test: 'TrackedTest', reason: 'r', tracking: '#9' },
      { test: 'OldTest', reason: 'r', expires: '2026-09-27' },
    ],
  })
  const res = buildPlanDoc(snapshot(p), { now: NOW })
  assert.equal(res.ok, true, JSON.stringify(res.errors))
  assert.deepEqual(res.doc.args.project.knownRed.map((k) => k.test), ['LiveTest', 'TrackedTest'])
  assert.ok(res.doc.meta.warnings.includes('knownRed OldTest expired 2026-09-27'), JSON.stringify(res.doc.meta.warnings))
  assert.deepEqual(res.doc.args.project.verify[0].appliesTo, ['current/**'])
})

test('buildPlanDoc without knownRed leaves project.knownRed absent', () => {
  const res = buildPlanDoc(snapshot(profileWith({})), { now: NOW })
  assert.equal(res.ok, true, JSON.stringify(res.errors))
  assert.ok(!('knownRed' in res.doc.args.project))
})

// ---- promptVerify ----

function prompt({ seat = 'implementer', planner, verifyList, knownRed, stageList = stages.featureTaskLike() }) {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: '0a0a0a0a', traits: [], stages: stageList })
  const project = { shell: 'bash', verify: verifyList, searchScope: [] }
  if (knownRed) project.knownRed = knownRed
  const plan = planFixture({ items: [item], project })
  const stage = item.stages.find((s) => s.seat === seat)
  return core.seatPrompt(plan, item, stage, planner === undefined ? {} : { planner })
}
const GRADLE = { name: 'gradle', command: 'gradlew test', seats: ['implementer'], appliesTo: ['current/**'] }

test('promptVerify: an appliesTo match renders the command', () => {
  const p = prompt({ verifyList: [GRADLE], planner: { mainFiles: ['current/src/A.kt'], docFiles: [], testFiles: [], existingTestEdits: [] } })
  assert.ok(p.includes('gradlew test'))
  assert.ok(!p.includes('appliesTo matched no planned file'))
})

test('promptVerify: matching also counts docFiles, testFiles and existingTestEdits, relativized against the worktree', () => {
  for (const planner of [
    { mainFiles: ['x/y.js'], docFiles: ['current/docs/a.md'] },
    { mainFiles: ['x/y.js'], testFiles: ['current/src/test/T.kt'] },
    { mainFiles: ['x/y.js'], existingTestEdits: [{ file: 'current/src/test/U.kt' }] },
    { mainFiles: ['/tmp/wt-0a0a0a0a/current/src/A.kt'] },
  ]) {
    assert.ok(prompt({ verifyList: [GRADLE], planner }).includes('gradlew test'), JSON.stringify(planner))
  }
})

test('promptVerify: no match renders a SKIP line instead of the command', () => {
  const p = prompt({ verifyList: [GRADLE], planner: { mainFiles: ['claude-plugins/x.mjs'], docFiles: [], testFiles: [], existingTestEdits: [] } })
  assert.ok(p.includes('SKIP gradle: appliesTo matched no planned file'), p)
  assert.ok(!p.includes('gradlew test'))
})

test('promptVerify: with no planner output the entry renders (fail-safe)', () => {
  const p = prompt({ verifyList: [GRADLE] })
  assert.ok(p.includes('gradlew test'))
  assert.ok(!p.includes('SKIP gradle'))
})

test('promptVerify: appliesTo filters before the ownedTests handling', () => {
  const owned = { name: 'own', command: 'node --test <ownedTests>', seats: ['implementer'], appliesTo: ['current/**'], ownedTestsPattern: 'mjs$' }
  const p = prompt({ verifyList: [owned], planner: { mainFiles: ['docs/a.md'], testFiles: ['a.test.mjs'], existingTestEdits: [] } })
  assert.ok(p.includes('SKIP own: appliesTo matched no planned file'), p)
  assert.ok(!p.includes('no owned test files'))
})

test('promptVerify: knownRed lines go to implementer and test-author only, and force the VERIFY header', () => {
  const kr = [{ test: 'FooTest', reason: 'flaky clock', tracking: '#1' }]
  const line = 'KNOWN RED (not a regression): FooTest - flaky clock'
  const impl = prompt({ verifyList: [], knownRed: kr })
  assert.ok(impl.includes('VERIFY:') && impl.includes(line), impl)
  const ta = prompt({ seat: 'test-author', verifyList: [], knownRed: kr, stageList: stages.bugFixLike() })
  assert.ok(ta.includes(line))
  const pl = prompt({ seat: 'planner', verifyList: [GRADLE], knownRed: kr, stageList: stages.bugFixLike() })
  assert.ok(!pl.includes('KNOWN RED'))
  const none = prompt({ verifyList: [], knownRed: [] })
  assert.ok(!none.includes('VERIFY:'))
})

// ---- run-exec verify ----

test('run-exec verify echoes knownRed and appliesTo on orchestrator commands', () => {
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage] })
  const knownRed = [{ test: 'FooTest', reason: 'r', tracking: '#1' }]
  const project = {
    shell: 'bash',
    searchScope: [],
    knownRed,
    verify: [
      { name: 'scoped', command: 'a', seats: ['orchestrator'], appliesTo: ['current/**'] },
      { name: 'plain', command: 'b', seats: ['orchestrator'] },
    ],
  }
  const mk = (proj) => ({ contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [item], project: proj }), meta: {} })
  const out = verify(mk(project), { items: [] }, { exists: {}, commits: [] })
  assert.deepEqual(out.knownRed, knownRed)
  const cmds = out.items[0].redProof.commands
  assert.deepEqual(cmds[0], { name: 'scoped', command: 'a', appliesTo: ['current/**'] })
  assert.ok(!('appliesTo' in cmds[1]))
  const bare = verify(mk({ shell: 'bash', searchScope: [], verify: [] }), { items: [] }, { exists: {}, commits: [] })
  assert.deepEqual(bare.knownRed, [])
})