// Carry-in disposition coverage (item f32d5442): seatPrompt parts, run-planner snapshot
// validation, plan args and meta, explainPlan, and agent/skill text.
//
// BLIND test author: written from the frozen test-plan and task-scope notes (D1-D8) and a
// DECLARATIONS file, never from the implementation sources. Oracles are cited inline as Dn
// (task-scope decision n). Node 22, node:test + node:assert/strict, no deps.
//
// Scenario ids S1-S12 match the item's test-plan; S13 and S14 are author-added (schema
// fixture and doc text), S12c is a split of S12.
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadCore, planFixture, itemFixture, stages } from './workflow-harness.mjs'
import {
  carryInKeys,
  isCarryInDisposed,
  validateSnapshot,
  buildPlanDoc,
  validatePlanDoc,
  explainPlan,
} from '../run-planner-lib.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN = path.resolve(HERE, '..', '..')
const SCRIPT_PATH = path.join(PLUGIN, 'workflows', 'implement-wave.js')
const PLANNER_MD = path.join(PLUGIN, 'agents', 'planner.md')
const REVIEWER_MD = path.join(PLUGIN, 'agents', 'reviewer.md')
const SKILL_MD = path.join(PLUGIN, 'skills', 'run-wave', 'SKILL.md')
const SNAPSHOT_MD = path.join(PLUGIN, 'skills', 'run-wave', 'references', 'snapshot.md')
const FIXTURES = path.join(HERE, 'fixtures', 'run-planner')
const NOW = '2026-09-28T12:00:00Z'

function clone(x) {
  return JSON.parse(JSON.stringify(x))
}
function loadFixture(name) {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'))
}
function readText(p) {
  return fs.readFileSync(p, 'utf8')
}

// ---------------------------------------------------------------------------
// seatPrompt helpers
// ---------------------------------------------------------------------------

const PROJECT = {
  searchScope: 'claude-plugins/task-orchestrator',
  scratchDir: '/tmp/scratch',
  shell: 'bash',
  verify: [{ seats: ['implementer'], command: 'node --test scripts/tests/implement-wave.test.mjs' }],
}

function makeItem(short, stageList, overrides = {}) {
  return itemFixture({
    short,
    title: `Item ${short}`,
    configFingerprint: `fp-${short}`,
    traits: ['delegated', 'session-tracked'],
    stages: stageList,
    ...overrides,
  })
}

function makePlan(items) {
  return planFixture({ items, rootId: 'root-carry', project: PROJECT, baseSha: 'e77c3e95' })
}

function plannerStage(notes) {
  return { ...stages.featureTaskLike()[0], notes }
}
function implementerStage() {
  return stages.featureTaskLike()[1]
}

function promptFor(stage, itemOverrides, short = 'aaaaaaaa') {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem(short, [stage], itemOverrides)
  const plan = makePlan([item])
  return core.seatPrompt(plan, item, stage, {})
}

// Normalise a prompt line the way a row reads: strip indent, list marker, backticks.
function normLine(line) {
  return line
    .trim()
    .replace(/^(?:[-*+]|\d+\.)\s+/, '')
    .replace(/`/g, '')
}

function rowLinesFor(prompt, key) {
  return prompt.split('\n').filter((l) => normLine(l).startsWith(`${key}:`))
}

// The text of one labelled prompt part: from its label to the next ALL-CAPS label line.
function partOf(prompt, label) {
  const re = new RegExp(`^[ \\t\\-*]*${label}\\b`, 'm')
  const m = re.exec(prompt)
  const start = m ? m.index : prompt.indexOf(label)
  assert.notEqual(start, -1, `prompt has no ${label} part`)
  const rest = prompt.slice(start)
  const firstNl = rest.indexOf('\n')
  if (firstNl === -1) return rest
  const after = rest.slice(firstNl + 1)
  const next = /^[ \t\-*]*[A-Z][A-Z0-9 -]{3,}:/m.exec(after)
  return next ? rest.slice(0, firstNl + 1 + next.index) : rest
}

const KEYS3 = ['carry-in-a', 'carry-in-b', 'carry-in-c']

// ---- S1 ---------------------------------------------------------------------

test('S1: planner-v1 stage with carry-in-dispositions and 3 carry-ins renders exactly 3 rows, "3 of 3", the record key and a keys list [D2, D5]', () => {
  const prompt = promptFor(plannerStage(['task-scope', 'carry-in-dispositions']), { carryIns: KEYS3, lateCarryIns: [] })
  const part = partOf(prompt, 'CARRY-INS')

  let lastIdx = -1
  for (const key of KEYS3) {
    const rows = rowLinesFor(part, key)
    assert.equal(rows.length, 1, `expected exactly one row for ${key}, got ${JSON.stringify(rows)}`)
    assert.match(rows[0], /in scope/)
    assert.match(rows[0], /deferred to/)
    assert.match(rows[0], /rejected because/)
    const idx = part.indexOf(rows[0])
    assert.ok(idx > lastIdx, `row for ${key} out of order`)
    lastIdx = idx
  }
  const allRows = part.split('\n').filter((l) => /^carry-in-[\w-]+:/.test(normLine(l)) && /in scope/.test(l))
  assert.equal(allRows.length, 3, `total row lines: ${JSON.stringify(allRows)}`)

  assert.ok(part.includes('3 of 3'), 'count line "3 of 3" missing')
  assert.ok(part.includes('carry-in-dispositions'), 'record key carry-in-dispositions not named')

  const keyLists = [...part.matchAll(/keys\s*[:=]\s*\[[^\]]*\]/g)].map((m) => m[0])
  assert.ok(
    keyLists.some((l) => KEYS3.every((k) => l.includes(k))),
    `no keys list holding all three keys: ${JSON.stringify(keyLists)}`
  )
  assert.match(part, /includeBody\s*[:=]\s*true/)
})

// ---- S2 ---------------------------------------------------------------------

test('S2: planner-v1 stage WITHOUT carry-in-dispositions in stage.notes renders 3 rows and a "Carry-in dispositions" section target [D2, D5]', () => {
  const prompt = promptFor(plannerStage(['task-scope']), { carryIns: KEYS3, lateCarryIns: [] })
  const part = partOf(prompt, 'CARRY-INS')
  for (const key of KEYS3) {
    assert.equal(rowLinesFor(part, key).length, 1, `row for ${key}`)
  }
  assert.ok(part.includes('3 of 3'))
  assert.match(part, /Carry-in dispositions/, 'section heading target missing')
})

// ---- S3 ---------------------------------------------------------------------

test('S3: implementer-v1 stage with lateCarryIns [carry-in-b] and carryIns [a, b] gets a LATE CARRY-INS part naming only carry-in-b [D5]', () => {
  const prompt = promptFor(implementerStage(), { carryIns: ['carry-in-a', 'carry-in-b'], lateCarryIns: ['carry-in-b'] })
  const part = partOf(prompt, 'LATE CARRY-INS')
  assert.ok(part.includes('carry-in-b'), 'late key not named in the part')
  assert.match(part, /includeBody\s*[:=]\s*true/)
  assert.ok(part.includes('orchestrator-carry-in-dispositions'), 'disposition note not named')
  assert.ok(!prompt.includes('carry-in-a'), 'a non-late carry-in must not appear in the implementer prompt')
  assert.ok(!/(?<!LATE )CARRY-INS/.test(prompt), 'the planner CARRY-INS part must not appear in an implementer prompt')
})

// ---- S7 / S8 / probes: no part when nothing applies -------------------------

test('S7: carryIns [] or absent adds no CARRY-INS part and no "0 of 0" to a planner prompt [D5]', () => {
  for (const overrides of [{ carryIns: [], lateCarryIns: [] }, {}]) {
    const prompt = promptFor(plannerStage(['task-scope', 'carry-in-dispositions']), overrides)
    assert.ok(!prompt.includes('CARRY-INS'), `unexpected part for ${JSON.stringify(overrides)}`)
    assert.ok(!prompt.includes('0 of 0'))
  }
})

test('S7: an implementer with lateCarryIns [] (or absent) has no LATE CARRY-INS part, even when carryIns is set [D5]', () => {
  for (const overrides of [{ carryIns: ['carry-in-a'], lateCarryIns: [] }, { carryIns: ['carry-in-a'] }, {}]) {
    const prompt = promptFor(implementerStage(), overrides)
    assert.ok(!prompt.includes('CARRY-INS'), `unexpected part for ${JSON.stringify(overrides)}`)
    assert.ok(!prompt.includes('carry-in-a'))
  }
})

test('S8: test-author and schema-free generic stages never get a carry-in part, even with both fields set [D5]', () => {
  const both = { carryIns: KEYS3, lateCarryIns: ['carry-in-a'] }
  const testAuthor = stages.bugFixLike().find((s) => s.seat === 'test-author')
  const promptTa = promptFor(testAuthor, both)
  assert.ok(!promptTa.includes('CARRY-INS'))
  assert.ok(!promptTa.includes('carry-in-b'))

  const generic = stages.schemaFree()[0]
  const promptGen = promptFor(generic, { ...both, schemaFree: true, configFingerprint: 'fp-free' })
  assert.ok(!promptGen.includes('CARRY-INS'))
  assert.ok(!promptGen.includes('carry-in-b'))
})

test('S8 (probe): a planner-v1 stage ignores lateCarryIns, and an implementer-v1 stage ignores carryIns [D5]', () => {
  const plannerOnlyLate = promptFor(plannerStage(['task-scope']), { carryIns: [], lateCarryIns: ['carry-in-a'] })
  assert.ok(!plannerOnlyLate.includes('CARRY-INS'))
  const implOnlyCarry = promptFor(implementerStage(), { carryIns: KEYS3, lateCarryIns: [] })
  assert.ok(!implOnlyCarry.includes('CARRY-INS'))
})

test('Probe: seatPrompt with carry-ins is byte-identical across two calls', () => {
  const stage = plannerStage(['task-scope', 'carry-in-dispositions'])
  const a = promptFor(stage, { carryIns: KEYS3, lateCarryIns: [] })
  const b = promptFor(stage, { carryIns: KEYS3, lateCarryIns: [] })
  assert.equal(a, b)
  const stageI = implementerStage()
  assert.equal(
    promptFor(stageI, { carryIns: ['carry-in-a'], lateCarryIns: ['carry-in-a'] }),
    promptFor(stageI, { carryIns: ['carry-in-a'], lateCarryIns: ['carry-in-a'] })
  )
})

// ---------------------------------------------------------------------------
// run-planner helpers
// ---------------------------------------------------------------------------

// One seat-aware candidate (planner, implementer, extractor, test-author stages) cut from
// the shipped mixed-types fixture, so every constructed field satisfies validateSnapshot.
function snapOne({ role = 'queue', carry } = {}) {
  const snap = clone(loadFixture('mixed-types.json'))
  const cand = snap.candidates[0]
  snap.candidates = [cand]
  snap.schemas = { [cand.id]: snap.schemas[cand.id] }
  if (role === 'work') {
    cand.role = 'work'
    cand.source = 'work'
  }
  if (carry !== undefined) snap.carryIns = { [cand.id]: carry }
  return { snap, cand }
}

function plan(snap) {
  const result = buildPlanDoc(clone(snap), { now: NOW })
  assert.equal(result.ok, true, JSON.stringify(result.errors))
  assert.equal(result.doc.args.items.length, 1, 'fixture item was deferred or dropped')
  return result.doc
}

// ---- S6 ---------------------------------------------------------------------

test('S6: validateSnapshot accepts a well-formed carryIns and accepts carryIns absent [D3]', () => {
  const { snap: base } = snapOne()
  assert.equal(validateSnapshot(clone(base)).ok, true)
  const { snap } = snapOne({ carry: { keys: ['carry-in-a'], dispositions: null } })
  const ok = validateSnapshot(snap)
  assert.equal(ok.ok, true, JSON.stringify(ok.errors))
  const { snap: withText } = snapOne({ carry: { keys: ['carry-in-a'], dispositions: 'carry-in-a: in scope (AC 1)\n1 of 1' } })
  assert.equal(validateSnapshot(withText).ok, true)
})

test('S6: validateSnapshot rejects carryIns as an array, naming the field [D3]', () => {
  const { snap } = snapOne()
  snap.carryIns = []
  const r = validateSnapshot(snap)
  assert.equal(r.ok, false)
  assert.ok(r.errors.some((e) => e.includes('carryIns')), JSON.stringify(r.errors))
})

test('S6: validateSnapshot rejects a carryIns id that is not a candidate, naming it [D3]', () => {
  const { snap } = snapOne()
  const strangerId = '99999999-9999-4999-8999-999999999999'
  snap.carryIns = { [strangerId]: { keys: ['carry-in-a'], dispositions: null } }
  const r = validateSnapshot(snap)
  assert.equal(r.ok, false)
  assert.ok(r.errors.some((e) => e.includes(strangerId) || e.includes('99999999')), JSON.stringify(r.errors))
})

test('S6: validateSnapshot rejects non-array keys, naming the item [D3]', () => {
  const { snap, cand } = snapOne({ carry: { keys: 'carry-in-a', dispositions: null } })
  const r = validateSnapshot(snap)
  assert.equal(r.ok, false)
  assert.ok(r.errors.some((e) => e.includes(cand.id) || e.includes(cand.short)), JSON.stringify(r.errors))
})

test('S6: validateSnapshot rejects numeric dispositions, naming the item [D3]', () => {
  const { snap, cand } = snapOne({ carry: { keys: ['carry-in-a'], dispositions: 7 } })
  const r = validateSnapshot(snap)
  assert.equal(r.ok, false)
  assert.ok(r.errors.some((e) => e.includes(cand.id) || e.includes(cand.short)), JSON.stringify(r.errors))
})

// ---- S4 ---------------------------------------------------------------------

test('S4: queue candidate with a planner-v1 stage, 2 keys, dispositions null: carryIns = both keys in snapshot order, lateCarryIns [] [D4]', () => {
  const { snap } = snapOne({ carry: { keys: ['carry-in-b', 'carry-in-a'], dispositions: null } })
  const doc = plan(snap)
  const item = doc.args.items[0]
  assert.ok(item.stages.some((s) => s.output === 'planner-v1'), 'fixture must carry a planner-v1 stage')
  assert.deepEqual(item.carryIns, ['carry-in-b', 'carry-in-a'])
  assert.deepEqual(item.lateCarryIns, [])
  assert.deepEqual(doc.meta.lateCarryIns ?? [], [])
})

test('S4 (probe): a planner-v1 item stays non-late for every dispositions state (null, empty string, partial text) [D4]', () => {
  for (const dispositions of [null, '', 'carry-in-a: in scope (AC 1)']) {
    const { snap } = snapOne({ carry: { keys: ['carry-in-a', 'carry-in-b'], dispositions } })
    assert.deepEqual(plan(snap).args.items[0].lateCarryIns, [], `dispositions ${JSON.stringify(dispositions)}`)
  }
})

// ---- S5 ---------------------------------------------------------------------

const S5_RECORD = '- `carry-in-ab`: in scope (AC 2)\ncarry-in-c: deferred to 1234abcd\n2 of 2'

test('S5: resumed item (no planner-v1 stage), keys a/ab/c, record disposing ab and c: only carry-in-a is late; meta and explainPlan agree [D2, D4]', () => {
  const { snap, cand } = snapOne({
    role: 'work',
    carry: { keys: ['carry-in-a', 'carry-in-ab', 'carry-in-c'], dispositions: S5_RECORD },
  })
  const doc = plan(snap)
  const item = doc.args.items[0]
  assert.ok(!item.stages.some((s) => s.output === 'planner-v1'), 'fixture must have no planner-v1 stage')
  assert.deepEqual(item.carryIns, ['carry-in-a', 'carry-in-ab', 'carry-in-c'])
  assert.deepEqual(item.lateCarryIns, ['carry-in-a'])
  assert.deepEqual(doc.meta.lateCarryIns, [{ id: cand.id, short: cand.short, key: 'carry-in-a' }])

  const text = explainPlan(doc)
  const line = text.split('\n').find((l) => l.includes('Late carry-ins:'))
  assert.ok(line, `no "Late carry-ins:" line in:\n${text}`)
  assert.match(line, /carry-in-a(?![\w-])/)
  assert.ok(!/carry-in-ab/.test(line), 'a disposed key leaked into the late line')
  assert.ok(!/carry-in-c(?![\w-])/.test(line), 'a disposed key leaked into the late line')
})

// ---- S9 ---------------------------------------------------------------------

test('S9: no planner-v1 stage with dispositions null or empty string: every key is late, in snapshot order [D4]', () => {
  const keys = ['carry-in-b', 'carry-in-a', 'carry-in-c']
  for (const dispositions of [null, '']) {
    const { snap, cand } = snapOne({ role: 'work', carry: { keys, dispositions } })
    const doc = plan(snap)
    assert.deepEqual(doc.args.items[0].lateCarryIns, keys, `dispositions ${JSON.stringify(dispositions)}`)
    assert.deepEqual(
      doc.meta.lateCarryIns,
      keys.map((key) => ({ id: cand.id, short: cand.short, key }))
    )
  }
})

test('S9 (probe): a fully disposed record on a resumed item leaves nothing late and no explainPlan line [D2, D4]', () => {
  const record = 'carry-in-a: in scope (AC 1)\ncarry-in-b: rejected because stale\n2 of 2'
  const { snap } = snapOne({ role: 'work', carry: { keys: ['carry-in-a', 'carry-in-b'], dispositions: record } })
  const doc = plan(snap)
  assert.deepEqual(doc.args.items[0].lateCarryIns, [])
  assert.deepEqual(doc.meta.lateCarryIns ?? [], [])
  assert.ok(!explainPlan(doc).includes('Late carry-ins:'))
})

// ---- S10 --------------------------------------------------------------------

test('S10: snapshot keys incl. carry-in-dispositions and a duplicate: args carryIns drops the record key and keeps one copy, order kept [D1, D3]', () => {
  const keys = ['carry-in-c', 'carry-in-dispositions', 'carry-in-a', 'carry-in-c', 'carry-in-b']
  const { snap } = snapOne({ carry: { keys, dispositions: null } })
  assert.equal(validateSnapshot(clone(snap)).ok, true)
  assert.deepEqual(plan(snap).args.items[0].carryIns, ['carry-in-c', 'carry-in-a', 'carry-in-b'])
})

test('S10: carryInKeys drops carry-in-dispositions, duplicates and non-carry-in keys, keeping order [D1, D3]', () => {
  assert.deepEqual(
    carryInKeys(['carry-in-c', 'carry-in-dispositions', 'carry-in-a', 'carry-in-c', 'task-scope', 'xcarry-in-z', 'carry-in-b']),
    ['carry-in-c', 'carry-in-a', 'carry-in-b']
  )
  assert.deepEqual(carryInKeys([]), [])
  assert.deepEqual(carryInKeys(['carry-in-dispositions']), [])
})

// ---- S7 (plan side) / S11 ---------------------------------------------------

test('S7: a candidate with no carryIns entry gets carryIns [] and lateCarryIns [], and an empty keys list does the same [D4]', () => {
  const { snap: none } = snapOne()
  const item = plan(none).args.items[0]
  assert.deepEqual(item.carryIns, [])
  assert.deepEqual(item.lateCarryIns, [])

  const { snap: empty } = snapOne({ role: 'work', carry: { keys: [], dispositions: null } })
  const item2 = plan(empty).args.items[0]
  assert.deepEqual(item2.carryIns, [])
  assert.deepEqual(item2.lateCarryIns, [])
})

test('S11: explainPlan prints no "Late carry-ins:" line when nothing is late (carry-ins present or absent) [D4]', () => {
  const { snap: withKeys } = snapOne({ carry: { keys: ['carry-in-a'], dispositions: null } })
  assert.ok(!explainPlan(plan(withKeys)).includes('Late carry-ins:'))
  const { snap: none } = snapOne()
  assert.ok(!explainPlan(plan(none)).includes('Late carry-ins:'))
})

// ---- D2 matcher probes ------------------------------------------------------

test('Probe: isCarryInDisposed matches only a line that starts with exactly "<key>:" after indent, list marker and backticks [D2]', () => {
  const key = 'carry-in-a'
  const disposes = [
    'carry-in-a: in scope (AC 1)',
    '  carry-in-a: deferred to 1234abcd',
    '- carry-in-a: rejected because stale',
    '* carry-in-a: in scope (AC 1)',
    '+ carry-in-a: in scope (AC 1)',
    '1. carry-in-a: in scope (AC 1)',
    '- `carry-in-a`: in scope (AC 1)',
    '`carry-in-a`: in scope (AC 1)',
    'carry-in-b: in scope (AC 1)\ncarry-in-a: in scope (AC 2)\n2 of 2',
  ]
  for (const record of disposes) {
    assert.equal(isCarryInDisposed(record, key), true, JSON.stringify(record))
  }
  const doesNot = [
    'carry-in-ab: in scope (AC 1)',
    'Carry-In-A: in scope (AC 1)',
    'carry-in-a in scope (AC 1)',
    'see carry-in-a: in scope (AC 1)',
    'xcarry-in-a: in scope (AC 1)',
    'carry-in-b: in scope (AC 1)\n1 of 1',
    '',
    null,
  ]
  for (const record of doesNot) {
    assert.equal(isCarryInDisposed(record, key), false, JSON.stringify(record))
  }
})

// ---- S13: args schema fixture ------------------------------------------------

test('S13: the args-v1 schema fixture declares carryIns and lateCarryIns as arrays of strings [D4]', () => {
  const schema = loadFixture('args-v1.schema.json')
  const props = schema.properties.items.items.properties
  for (const field of ['carryIns', 'lateCarryIns']) {
    assert.deepEqual(props[field], { type: 'array', items: { type: 'string' } }, field)
  }
})

test('S13: plan docs carrying carryIns and lateCarryIns satisfy validatePlanDoc against the schema fixture [D4]', () => {
  const schema = loadFixture('args-v1.schema.json')
  const { snap: queue } = snapOne({ carry: { keys: ['carry-in-a'], dispositions: null } })
  const { snap: work } = snapOne({ role: 'work', carry: { keys: ['carry-in-a'], dispositions: null } })
  for (const snap of [queue, work]) {
    const doc = plan(snap)
    const v = validatePlanDoc(doc, schema)
    assert.equal(v.ok, true, JSON.stringify(v.errors))
  }
})

test('S13 (probe): validatePlanDoc rejects a non-string entry in carryIns [D4]', () => {
  const schema = loadFixture('args-v1.schema.json')
  const { snap } = snapOne({ carry: { keys: ['carry-in-a'], dispositions: null } })
  const doc = plan(snap)
  doc.args.items[0].carryIns = [1]
  assert.equal(validatePlanDoc(doc, schema).ok, false)
  const doc2 = plan(snap)
  doc2.args.items[0].lateCarryIns = 'carry-in-a'
  assert.equal(validatePlanDoc(doc2, schema).ok, false)
})

// ---------------------------------------------------------------------------
// S12: agent and skill text. Loose by design: the author is blind to these files, so the
// assertions follow the D1/D2/D7/D8 vocabulary only.
// ---------------------------------------------------------------------------

function paragraphs(text) {
  return text.split(/\n\s*\n/)
}

test('S12: planner.md names carry-in- keys, the record, the D2 line form and the "N of N" count line [D1, D2, D8]', () => {
  const text = readText(PLANNER_MD)
  for (const needle of ['carry-in-', 'carry-in-dispositions', 'in scope', 'deferred to', 'rejected because', 'N of N']) {
    assert.ok(text.includes(needle), `planner.md must mention ${JSON.stringify(needle)}`)
  }
})

test('S12c: planner.md says the carry-in-dispositions record itself is not a carry-in key to dispose [D1]', () => {
  const text = readText(PLANNER_MD)
  const hit = paragraphs(text).some(
    (p) => p.includes('carry-in-dispositions') && /exclud|except|not a|not an|not one|itself|other than|besides|never|omit|skip/i.test(p)
  )
  assert.ok(hit, 'no paragraph pairs carry-in-dispositions with an exclusion statement')
})

test('S12: reviewer.md makes an undisposed carry-in a blocking finding, names orchestrator-carry-in-dispositions and the count check [D7]', () => {
  const text = readText(REVIEWER_MD)
  for (const needle of ['carry-in-', 'orchestrator-carry-in-dispositions', 'N of N']) {
    assert.ok(text.includes(needle), `reviewer.md must mention ${JSON.stringify(needle)}`)
  }
  const hit = paragraphs(text).some((p) => p.includes('carry-in') && /blocking/i.test(p))
  assert.ok(hit, 'no paragraph pairs carry-in with blocking')
})

// ---- S14: orchestrator-facing docs ------------------------------------------

test('S14: run-wave SKILL.md tells the orchestrator to record late carry-ins in orchestrator-carry-in-dispositions [D6]', () => {
  const text = readText(SKILL_MD)
  assert.ok(text.includes('orchestrator-carry-in-dispositions'))
  assert.ok(text.includes('lateCarryIns'))
})

test('S14: the snapshot reference documents carryIns, keys and dispositions [D3]', () => {
  const text = readText(SNAPSHOT_MD)
  for (const needle of ['carryIns', 'keys', 'dispositions']) {
    assert.ok(text.includes(needle), `snapshot.md must mention ${needle}`)
  }
})
