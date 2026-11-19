// Item 13b2fdaf (F1-B): review lanes deliver the rules a review note names as skills. Scenarios S1-S9 and probes
// P1-P4 of the frozen test-plan (test-author seat; no production .mjs or .js file was opened, only the exported
// surfaces reached through loadCoreNamed, loadWaveCore, buildPlanDoc, reviewWaveArgs and reviewPrompt).
//
// Oracle: the frozen task-scope clauses AC1 (a review note's skill that the snapshot serves as a rule reaches the lane as
// `rules`, every other skill stays in `skills`, in note order), D3 (note rules come first, then the seat's declared rules,
// each once; an unserved key or a snapshot without rulesServed leaves the skill a skill), AC2 (the args pass the workflow's
// normalizeArgs and preflight; the lane prompt Keys line lists the rule), AC4/D1 (one reviewer protocol key,
// protocol.read-only-agent) and D2 (the review prompt keeps its END SEAT paragraph). Recomputed by hand, not read from output.
// Fixture condition for each absence assertion: the paired lane in the same fixture does carry the member.
// Forbidden-construct declaration: none.

import '../../hooks/tests/hermetic-claude-config.mjs'
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadCoreNamed } from './workflow-harness-ext.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import * as execLib from '../run-exec-lib.mjs'
import { buildPlanDoc } from '../run-planner-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const REVIEW_PATH = join(PLUGIN, 'workflows', 'review-wave.js')
const CLI = join(PLUGIN, 'scripts', 'run-planner.mjs')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')
const NOW = '2026-09-28T12:00:00Z'
const ITEM = 'aaaaaaaa-1111-4111-8111-111111111111'
const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'

const RW = loadCoreNamed(REVIEW_PATH, ['normalizeArgs', 'preflight', 'lanePrompt'])

const served = (...keys) => keys.map((key) => ({ key, rulesVersion: '1' }))
const note = (key, extra) => ({ key, role: 'review', required: true, seat: 'reviewer', ...extra })
const lanesOf = (args) => Object.fromEntries(args.items[0].stages.map((s) => [s.lane, s]))

const SEATS = (reviewerRules) => [
  { name: 'planner', phase: 'queue' },
  { name: 'implementer', phase: 'work', enters: true },
  { name: 'orchestrator', phase: 'work' },
  reviewerRules === undefined ? { name: 'reviewer', phase: 'review' } : { name: 'reviewer', phase: 'review', rules: reviewerRules },
]

/** The fixture snapshot with the review notes, seats and served rule keys applied. */
function makeSnap(reviewNotes, { servedKeys = [], reviewerRules } = {}) {
  const snap = JSON.parse(readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8'))
  snap.rulesServed = served('commit-discipline', ...servedKeys)
  const entry = snap.schemas[ITEM]
  entry.notes = [
    { key: 'specification', role: 'queue', required: true, seat: 'planner' },
    { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer' },
    { key: 'session-tracking', role: 'work', required: true, seat: 'orchestrator' },
    ...reviewNotes,
  ]
  entry.seats = SEATS(reviewerRules)
  entry.dispatchBySeat = { review: { reviewer: { agent: 'task-orchestrator:reviewer', model: 'opus', effort: 'high' } } }
  return snap
}

/** review-wave args built in process; `afterBuild(snap)` may change the snapshot after the plan doc is built. */
function argsFor(reviewNotes, opts = {}) {
  const snap = makeSnap(reviewNotes, opts)
  const built = buildPlanDoc(snap, { now: NOW })
  assert.equal(built.ok, true, JSON.stringify(built.errors))
  const doc = built.doc
  if (opts.afterBuild) opts.afterBuild(snap)
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

function lanePromptOf(args, laneId) {
  const norm = RW.normalizeArgs(args)
  assert.equal(norm.ok, true, norm.reason)
  assert.deepEqual(RW.preflight(norm.plan).refused, [])
  const item = norm.plan.items[0]
  const stage = item.stages.find((s) => s.lane === laneId)
  assert.ok(stage, `lane ${laneId} is in the plan: ${item.stages.map((s) => s.lane)}`)
  return RW.lanePrompt(norm.plan, item, stage, {})
}

const keysLine = (prompt) => {
  const m = /^[ \t]*Keys:[^\n]*/m.exec(prompt)
  assert.ok(m, "the lane prompt carries a line starting with 'Keys:'")
  return m[0]
}
const keysList = (prompt) => keysLine(prompt).replace(/^[ \t]*Keys:\s*/, '').replace(/\.\s*$/, '').split(/\s*,\s*/).filter(Boolean)

// ---- S1-S3: skills split into rules and skills --------------------------------------------------

test('S1: a review note whose skill is a served rule is lane reviewer.note-rule with rules [note-rule] and no skills member', () => {
  const lanes = lanesOf(argsFor([note('n-1', { skill: 'note-rule' }), note('n-2', { skill: 'plain-a' })], { servedKeys: ['note-rule'] }))
  const lane = lanes['reviewer.note-rule']
  assert.ok(lane, `lane ids: ${Object.keys(lanes)}`)
  assert.deepEqual(lane.rules, ['note-rule'])
  assert.equal(Object.hasOwn(lane, 'skills'), false, 'the rule is no longer a skill')
  assert.deepEqual(lanes['reviewer.plain-a'].skills, ['plain-a'], 'fixture: a plain skill keeps its skills member')
})

test('S2: skills [note-rule, plain-a] with note-rule served: lane reviewer.note-rule--plain-a, rules [note-rule], skills [plain-a]', () => {
  const lanes = lanesOf(argsFor([note('n-1', { skill: 'note-rule', skills: ['note-rule', 'plain-a'] })], { servedKeys: ['note-rule'] }))
  const lane = lanes['reviewer.note-rule--plain-a']
  assert.ok(lane, `lane ids: ${Object.keys(lanes)}`)
  assert.deepEqual(lane.rules, ['note-rule'])
  assert.deepEqual(lane.skills, ['plain-a'])
})

test('S3: skills [plain-a, note-rule] (the rule is not first): lane reviewer.plain-a--note-rule, rules [note-rule], skills [plain-a]', () => {
  const lanes = lanesOf(argsFor([note('n-1', { skill: 'plain-a', skills: ['plain-a', 'note-rule'] })], { servedKeys: ['note-rule'] }))
  const lane = lanes['reviewer.plain-a--note-rule']
  assert.ok(lane, `lane ids: ${Object.keys(lanes)}`)
  assert.deepEqual(lane.rules, ['note-rule'])
  assert.deepEqual(lane.skills, ['plain-a'])
})

// ---- S4: order and dedupe with the seat's rules --------------------------------------------------

test('S4: note rules come before the seat rules; a seat rule equal to a note rule is listed once', () => {
  const both = lanesOf(argsFor([note('n-1', { skill: 'note-rule' })], { servedKeys: ['note-rule', 'x-rule'], reviewerRules: ['x-rule'] }))
  assert.deepEqual(both['reviewer.note-rule'].rules, ['note-rule', 'x-rule'])
  const same = lanesOf(argsFor([note('n-1', { skill: 'note-rule' })], { servedKeys: ['note-rule'], reviewerRules: ['note-rule'] }))
  assert.deepEqual(same['reviewer.note-rule'].rules, ['note-rule'])
})

// ---- S5: unserved keys stay skills ---------------------------------------------------------------

test('S5: a rule the snapshot does not serve stays a skill, and a snapshot without rulesServed does the same', () => {
  const lanes = lanesOf(argsFor([note('n-1', { skill: 'note-rule' })], { servedKeys: [] }))
  assert.deepEqual(lanes['reviewer.note-rule'].skills, ['note-rule'])
  assert.equal(Object.hasOwn(lanes['reviewer.note-rule'], 'rules'), false)

  const gone = lanesOf(argsFor([note('n-1', { skill: 'note-rule' })], { servedKeys: ['note-rule'], afterBuild: (snap) => { delete snap.rulesServed } }))
  assert.deepEqual(gone['reviewer.note-rule'].skills, ['note-rule'])
  assert.equal(Object.hasOwn(gone['reviewer.note-rule'], 'rules'), false)

  const control = lanesOf(argsFor([note('n-1', { skill: 'note-rule' })], { servedKeys: ['note-rule'] }))
  assert.deepEqual(control['reviewer.note-rule'].rules, ['note-rule'], 'control: served at the same snapshot gives a rule')
})

// ---- S6: rules stay on their own lane -------------------------------------------------------------

test('S6: one seat, two lanes: only the lane whose note names the rule carries it', () => {
  const lanes = lanesOf(argsFor([note('n-a', { skill: 'note-rule' }), note('n-b', { skill: 'plain-a' })], { servedKeys: ['note-rule'] }))
  assert.deepEqual(lanes['reviewer.note-rule'].rules, ['note-rule'])
  assert.equal(Object.hasOwn(lanes['reviewer.plain-a'], 'rules'), false)
})

// ---- S7: the args reach the workflow --------------------------------------------------------------

test('S7: the S2 args pass normalizeArgs and preflight, and the lane prompt Keys line lists note-rule', () => {
  const args = argsFor([note('n-1', { skill: 'note-rule', skills: ['note-rule', 'plain-a'] })], { servedKeys: ['note-rule'] })
  const prompt = lanePromptOf(args, 'reviewer.note-rule--plain-a')
  assert.ok(keysList(prompt).includes('note-rule'), keysLine(prompt))
})

test('S7: with skillMode method-on-diff the BRANCH-SCOPED line names plain-a and not note-rule', () => {
  const args = argsFor([note('n-1', { skill: 'note-rule', skills: ['note-rule', 'plain-a'], skillMode: 'method-on-diff' })], { servedKeys: ['note-rule'] })
  const prompt = lanePromptOf(args, 'reviewer.note-rule--plain-a')
  const lines = prompt.split('\n').filter((l) => l.includes('BRANCH-SCOPED'))
  assert.ok(lines.length >= 1, 'a BRANCH-SCOPED line is present')
  assert.ok(lines.some((l) => l.includes('plain-a')), lines.join(' | '))
  assert.equal(lines.some((l) => l.includes('note-rule')), false, lines.join(' | '))
})

// ---- S8, S9: the one reviewer protocol key ---------------------------------------------------------

function cli(args, input) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8', input: input === undefined ? undefined : JSON.stringify(input) })
}

/** A bound plan doc built through the planner CLI, as the seat-rules test does. */
function boundDoc() {
  const dir = mkdtempSync(join(tmpdir(), 'review-lane-rules-'))
  const snap = makeSnap([note('review-checklist', { skill: 'review-quality' })])
  const planRes = cli(['plan', '--now', NOW, '--scratchpad', join(dir, 'sp')], snap)
  assert.equal(planRes.status, 0, planRes.stderr)
  const planPath = join(dir, 'plan.json')
  writeFileSync(planPath, planRes.stdout)
  const bound = cli(['bind-run', '--in', planPath, '--server-run', SERVER_RUN])
  assert.equal(bound.status, 0, bound.stderr)
  return JSON.parse(bound.stdout)
}

test('S8 (R1, AC3): the review prompt names protocol.read-only-agent and never protocol.in-phase-seat; with nothing served it lists no review-scoping', () => {
  assert.equal(Object.hasOwn(execLib, 'REVIEW_RULES'), false, 'D4: run-exec-lib no longer exports its own REVIEW_RULES list')
  const text = execLib.reviewPrompt(loadWaveCore(), boundDoc(), 'aaaaaaaa')
  assert.ok(text.includes('protocol.read-only-agent'))
  assert.equal(text.includes('protocol.in-phase-seat'), false)
  // Fixture condition: boundDoc serves no review-scoping, so a prompt that still always added it (the old rule) would list it.
  const m = /Keys:([^\n]*)/.exec(text)
  assert.ok(m, 'a prompt line lists the review rule keys')
  assert.deepEqual(m[1].replace(/\.\s*$/, '').split(',').map((k) => k.trim()), ['protocol.read-only-agent'])
})

test('S8: every review-wave stage protocol is protocol.read-only-agent and its lane prompt names it', () => {
  const args = argsFor([
    note('n-1', { skill: 'note-rule' }),
    note('n-2', { skill: 'plain-a', lane: { output: 'findings' } }),
    note('n-3', { skill: 'plain-b' }),
  ], { servedKeys: ['note-rule'] })
  const stages = args.items[0].stages
  assert.ok(stages.length >= 3, 'fixture: three lanes')
  for (const s of stages) {
    assert.equal(s.protocol, 'protocol.read-only-agent', s.lane)
    const prompt = lanePromptOf(args, s.lane)
    assert.ok(prompt.includes('protocol.read-only-agent'), s.lane)
    assert.equal(prompt.includes('protocol.in-phase-seat'), false, s.lane)
  }
})

test('S9: a bound review prompt keeps an END SEAT paragraph naming end_seat that says only runs_write call', () => {
  const text = execLib.reviewPrompt(loadWaveCore(), boundDoc(), 'aaaaaaaa')
  const at = text.indexOf('END SEAT')
  assert.ok(at !== -1, 'the prompt has an END SEAT paragraph')
  const rest = text.slice(at)
  const end = rest.indexOf('\n\n')
  const paragraph = end === -1 ? rest : rest.slice(0, end)
  assert.ok(paragraph.includes('end_seat'), paragraph)
  assert.ok(paragraph.includes('only runs_write call'), paragraph)
})

// ---- Probes ----------------------------------------------------------------------------------------

test('P1: malformed rulesServed entries never throw; only a well-formed {key} entry serves the rule', () => {
  const junk = [null, {}, 'note-rule', { key: 5 }]
  const lanes = lanesOf(argsFor([note('n-1', { skill: 'note-rule' })], { servedKeys: ['note-rule'], afterBuild: (snap) => { snap.rulesServed = [...junk, { key: 'note-rule' }] } }))
  assert.deepEqual(lanes['reviewer.note-rule'].rules, ['note-rule'])
  const without = lanesOf(argsFor([note('n-1', { skill: 'note-rule' })], { servedKeys: ['note-rule'], afterBuild: (snap) => { snap.rulesServed = junk } }))
  assert.deepEqual(without['reviewer.note-rule'].skills, ['note-rule'], 'without the well-formed entry the skill stays a skill')
  assert.equal(Object.hasOwn(without['reviewer.note-rule'], 'rules'), false)
})

test('P2: a served key matches the skill name exactly (case-sensitive)', () => {
  const lanes = lanesOf(argsFor([note('n-1', { skill: 'note-rule' })], { servedKeys: ['note-rule'], afterBuild: (snap) => { snap.rulesServed = served('Note-Rule') } }))
  assert.deepEqual(lanes['reviewer.note-rule'].skills, ['note-rule'])
  assert.equal(Object.hasOwn(lanes['reviewer.note-rule'], 'rules'), false)
})

test('P3: a skill that is also a reviewer default key (review-scoping, served) is a rule and the Keys line lists it once', () => {
  const args = argsFor([note('n-1', { skill: 'review-scoping' })], { servedKeys: ['review-scoping'] })
  const lane = lanesOf(args)['reviewer.review-scoping']
  assert.ok(lane, 'lane reviewer.review-scoping')
  assert.deepEqual(lane.rules, ['review-scoping'])
  const keys = keysList(lanePromptOf(args, 'reviewer.review-scoping'))
  assert.equal(keys.filter((k) => k === 'review-scoping').length, 1, keys.join(','))
})

test('P4: reviewWaveArgs is deterministic: building twice gives identical JSON', () => {
  const build = () => argsFor([note('n-1', { skill: 'note-rule', skills: ['note-rule', 'plain-a'] }), note('n-2', { skill: 'plain-b' })], { servedKeys: ['note-rule', 'x-rule'], reviewerRules: ['x-rule'] })
  assert.equal(JSON.stringify(build()), JSON.stringify(build()))
})
