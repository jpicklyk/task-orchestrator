// Item 79a61aba (U1): plugin side of a note entry that names several skills and a skill mode. Scenarios S12, S13, S14
// and S16 of the frozen test-plan (blind test-author seat; no production file opened, only exported surfaces).
//
// Oracle: the frozen task-scope clauses "Plugin" and "Decision": a review lane is keyed by its seat and its skills in note
// order (`<seat>.<skill>`, `<seat>.<a>--<b>`, bare `<seat>`); the stage carries `skills` (always the full list) and
// `skillMode` only when it is method-on-diff; one seat+skills group with two modes is invalid args; review-wave names the
// BRANCH-SCOPED instruction only for a stage whose skillMode is method-on-diff (a skill name alone, e.g. security-review,
// never triggers it); an unknown skillMode is refused as `invalid args: bad stage skillMode`; the review-wave script holds
// no skill name and no BRANCH_SCOPED_SKILLS list; deriveStages keeps a served rule in `rules` and any other skill in
// `skills`, for every skill of an owned note.
//
// Wire shape of the snapshot notes (self-resolved from the declared NoteSchemaEntryDto): `skill` (first skill), `skills`
// (full list) and `skillMode` (wire string, absent for invoke).
// Fixture condition for each negative assertion: the paired multi-skill/method-on-diff entry in the same fixture does
// carry the member, so an implementation that always dropped or always added it fails one of the two assertions.
// Forbidden-construct declaration: none.

import '../../hooks/tests/hermetic-claude-config.mjs'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { scriptText } from './workflow-harness.mjs'
import { loadCoreNamed } from './workflow-harness-ext.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import * as execLib from '../run-exec-lib.mjs'
import { buildPlanDoc, deriveStages } from '../run-planner-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const REVIEW_PATH = join(PLUGIN, 'workflows', 'review-wave.js')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')
const BASELINE = join(PLUGIN, 'scripts', 'lib', 'domain-boundary-baseline.json')
const NOW = '2026-09-28T12:00:00Z'
const ITEM = 'aaaaaaaa-1111-4111-8111-111111111111'

const RW = loadCoreNamed(REVIEW_PATH, ['normalizeArgs', 'preflight', 'lanePrompt'])

// ---- S12: reviewWaveArgs lanes ----

const snapshot = () => JSON.parse(readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8'))

/** review-wave args built from the fixture plan with `reviewNotes` appended to the item's schema. */
function argsFor(reviewNotes) {
  const snap = snapshot()
  snap.schemas[ITEM].notes.push(...reviewNotes)
  const built = buildPlanDoc(snap, { now: NOW })
  assert.equal(built.ok, true, JSON.stringify(built.errors))
  const doc = built.doc
  const item = doc.args.items.find((i) => i.id === ITEM)
  assert.ok(item, 'the fixture item is in the plan')
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: item.short, status: 'done', reason: 'ok',
      stages: item.stages.map((s) => ({ seat: s.seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] })),
      outputs: {},
    }],
    refused: [], deferred: [],
  }
  return execLib.reviewWaveArgs(loadWaveCore(), doc, snap, result, [item.short])
}

const note = (key, extra) => ({ key, role: 'review', required: true, seat: 'reviewer', ...extra })
const lanesOf = (args) => Object.fromEntries(args.items[0].stages.map((s) => [s.lane, s]))

test('S12: a two-skill method-on-diff note is lane reviewer.rule-a--rule-b with skills [a,b] and skillMode method-on-diff', () => {
  const lanes = lanesOf(argsFor([note('n-multi', { skill: 'rule-a', skills: ['rule-a', 'rule-b'], skillMode: 'method-on-diff' })]))
  const lane = lanes['reviewer.rule-a--rule-b']
  assert.ok(lane, `lane ids: ${Object.keys(lanes)}`)
  assert.deepEqual(lane.skills, ['rule-a', 'rule-b'])
  assert.equal(lane.skillMode, 'method-on-diff')
  assert.deepEqual(lane.notes, ['n-multi'])
})

test('S12: a single-skill note is lane reviewer.rule-a with skills [a] and no skillMode member', () => {
  const lanes = lanesOf(argsFor([
    note('n-multi', { skill: 'rule-a', skills: ['rule-a', 'rule-b'], skillMode: 'method-on-diff' }),
    note('n-solo', { skill: 'rule-a' }),
  ]))
  const solo = lanes['reviewer.rule-a']
  assert.ok(solo, `lane ids: ${Object.keys(lanes)}`)
  assert.deepEqual(solo.skills, ['rule-a'])
  assert.equal(Object.hasOwn(solo, 'skillMode'), false, 'invoke is the default and carries no member')
  assert.notEqual(lanes['reviewer.rule-a--rule-b'], solo, 'the one-skill lane is a different lane than the pair')
})

test('S12 probe: skill order decides the lane id ([b,a] is not [a,b])', () => {
  const lanes = lanesOf(argsFor([
    note('n-ab', { skill: 'rule-a', skills: ['rule-a', 'rule-b'] }),
    note('n-ba', { skill: 'rule-b', skills: ['rule-b', 'rule-a'] }),
  ]))
  assert.deepEqual(lanes['reviewer.rule-a--rule-b'].notes, ['n-ab'])
  assert.deepEqual(lanes['reviewer.rule-b--rule-a'].notes, ['n-ba'])
  assert.deepEqual(lanes['reviewer.rule-b--rule-a'].skills, ['rule-b', 'rule-a'])
})

test('S12: two notes of one seat with the same skills share one lane and its mode', () => {
  const lanes = lanesOf(argsFor([
    note('n-1', { skill: 'rule-a', skills: ['rule-a', 'rule-b'], skillMode: 'method-on-diff' }),
    note('n-2', { skill: 'rule-a', skills: ['rule-a', 'rule-b'], skillMode: 'method-on-diff' }),
  ]))
  assert.deepEqual(lanes['reviewer.rule-a--rule-b'].notes.slice().sort(), ['n-1', 'n-2'])
  assert.equal(lanes['reviewer.rule-a--rule-b'].skillMode, 'method-on-diff')
})

test('S12: one seat and one skills list with two modes is refused as invalid args', () => {
  assert.throws(
    () => argsFor([
      note('n-1', { skill: 'rule-a', skills: ['rule-a', 'rule-b'], skillMode: 'method-on-diff' }),
      note('n-2', { skill: 'rule-a', skills: ['rule-a', 'rule-b'] }),
    ]),
    /invalid args/,
  )
})

// ---- S13: review-wave lane prompt ----

function reviewStage(lane, notes, extra = {}) {
  return {
    seat: 'reviewer', lane, phase: 'review', notes, writes: false,
    protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1', ...extra,
  }
}

function rawPlan(stage) {
  return {
    contract: 'review-wave/args-v1', runId: 'r-test-0001', rootId: 'root-test-0001', baseSha: 'e77c3e95',
    worktreeMode: 'shared', entryMode: 'pre-entered',
    capabilities: { features: ['seats', 'dispatchBySeat', 'rules'], phase0Hooks: true },
    items: [{
      id: 'item-aaaaaaaa', short: 'aaaaaaaa', type: 'test-item', configFingerprint: 'fp-1', traits: [],
      worktree: '/tmp/wt-aaaaaaaa', stages: [stage],
      review: {
        ownedFiles: { implementer: ['a.js'], testAuthor: ['a.test.js'], docs: [] },
        commits: { implementer: ['aaaaaaa1'], testAuthor: ['bbbbbbb2'], fixtureRepairs: [] },
        implementerActors: ['implementer:aaaaaaaa:r-impl-0001'],
        testAuthorActors: ['test-author:aaaaaaaa:r-impl-0001'],
        requiredReviewNotes: stage.notes,
      },
    }],
    deferred: [],
  }
}

function promptFor(stage) {
  const norm = RW.normalizeArgs(rawPlan(stage))
  assert.equal(norm.ok, true, norm.reason)
  const item = norm.plan.items[0]
  return RW.lanePrompt(norm.plan, item, item.stages[0], {})
}

const branchScopedLines = (prompt) => prompt.split('\n').filter((l) => l.includes('BRANCH-SCOPED'))

test('S13: skillMode method-on-diff gives a BRANCH-SCOPED line naming each skill of the lane', () => {
  const prompt = promptFor(reviewStage('reviewer.rule-a--rule-b', ['n-multi'], { skills: ['rule-a', 'rule-b'], skillMode: 'method-on-diff' }))
  const lines = branchScopedLines(prompt)
  assert.ok(lines.length >= 1, 'a BRANCH-SCOPED line is present')
  for (const skill of ['rule-a', 'rule-b']) {
    assert.ok(lines.some((l) => l.includes(skill)), `a BRANCH-SCOPED line names ${skill}: ${lines.join(' | ')}`)
  }
})

test('S13: the skill name security-review alone no longer triggers the BRANCH-SCOPED line', () => {
  const prompt = promptFor(reviewStage('reviewer.security-review', ['security-assessment'], { skills: ['security-review'] }))
  assert.equal(prompt.includes('BRANCH-SCOPED'), false)
})

test('S13: a method-on-diff lane of security-review does get the line (the mode, not the name, decides)', () => {
  const prompt = promptFor(reviewStage('reviewer.security-review', ['security-assessment'], { skills: ['security-review'], skillMode: 'method-on-diff' }))
  assert.ok(branchScopedLines(prompt).length >= 1)
})

test('S13: an explicit invoke skillMode never triggers it', () => {
  const prompt = promptFor(reviewStage('reviewer.rule-a', ['n-1'], { skills: ['rule-a'], skillMode: 'invoke' }))
  assert.equal(prompt.includes('BRANCH-SCOPED'), false)
})

test('S13: an unknown skillMode is refused as bad stage skillMode', () => {
  for (const bad of ['x', 'Method-On-Diff', '']) {
    const res = RW.normalizeArgs(rawPlan(reviewStage('reviewer.rule-a', ['n-1'], { skills: ['rule-a'], skillMode: bad })))
    assert.equal(res.ok, false, `skillMode ${JSON.stringify(bad)}`)
    assert.equal(res.reason, 'invalid args: bad stage skillMode', `skillMode ${JSON.stringify(bad)}`)
  }
})

// ---- S14: no skill name left in the review-wave script ----

test('S14: review-wave.js names no security-review skill and no BRANCH_SCOPED_SKILLS list', () => {
  const text = scriptText(REVIEW_PATH)
  assert.equal(text.includes('security-review'), false, 'no hard-coded skill name')
  assert.equal(text.includes('BRANCH_SCOPED_SKILLS'), false, 'the skill-name list is gone')
  assert.throws(() => loadCoreNamed(REVIEW_PATH, ['BRANCH_SCOPED_SKILLS']), ReferenceError)
})

test('S14: the domain-boundary baseline holds no pendingConfig row', () => {
  const baseline = JSON.parse(readFileSync(BASELINE, 'utf8'))
  assert.deepEqual(baseline.pendingConfig, [])
})

// ---- S16: run-planner stages split an owned note's skills into rules and skills ----

const CANDIDATE = {
  id: '79000000-0000-4000-8000-000000000001', short: '79000000', title: 'Item 79000000', priority: 'high',
  complexity: 2, type: 'scratch-x', role: 'queue', parentId: null, traits: [], hasChildren: false, source: 'ready',
}
const SERVED = [{ key: 'rule-r', rulesVersion: '1' }]
const schemaWith = (notes) => ({ status: 'ok', type: 'scratch-x', configFingerprint: 'fp-79', configSource: 'per-root', notes })
const derive = (notes) => deriveStages(CANDIDATE, schemaWith(notes), { rulesServed: SERVED, noteOwners: [] })
const stageOf = (result, seat) => result.stages.find((s) => s.seat === seat)

test('S16: an owned note with skills [served rule, other] yields the rule in rules and the other in skills', () => {
  const result = derive([
    { key: 'task-scope', role: 'queue', required: true, seat: 'planner', skill: 'rule-r', skills: ['rule-r', 'other-x'] },
    { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
  ])
  const planner = stageOf(result, 'planner')
  assert.ok((planner.rules ?? []).includes('rule-r'), `rules: ${JSON.stringify(planner.rules)}`)
  assert.ok((planner.skills ?? []).includes('other-x'), `skills: ${JSON.stringify(planner.skills)}`)
  assert.equal((planner.skills ?? []).includes('rule-r'), false, 'a served rule is not also a skill')
  assert.equal((planner.rules ?? []).includes('other-x'), false, 'an unserved skill is not a rule')
})

test('S16 probe: the second skill being the served rule is also found (not only the first)', () => {
  const result = derive([
    { key: 'task-scope', role: 'queue', required: true, seat: 'planner', skill: 'other-x', skills: ['other-x', 'rule-r'] },
    { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
  ])
  const planner = stageOf(result, 'planner')
  assert.ok((planner.rules ?? []).includes('rule-r'), `rules: ${JSON.stringify(planner.rules)}`)
  assert.ok((planner.skills ?? []).includes('other-x'), `skills: ${JSON.stringify(planner.skills)}`)
})
