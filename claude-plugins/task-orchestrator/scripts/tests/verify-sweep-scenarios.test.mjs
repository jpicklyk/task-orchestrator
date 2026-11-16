// Verify coverage for item 47071e0f: planner sweep check, plan-vs-manifest scenario check and the
// exact Seat-line match (hasSeatLine). Scenario ids S1-S15 match the item's `test-plan` note.
// Oracles: parent 416134d6 interface-contract facts 5, 11 and 12 and the task-scope acceptance
// criteria AC1-AC6 with decisions D1-D6 -- never what the code returns. This suite imports only the
// exported surface of run-exec-lib.mjs plus workflow-harness.mjs, and runs run-planner.mjs as a
// subprocess for the CLI case.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync, execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { planFixture, itemFixture } from './workflow-harness.mjs'
import { verify, hasSeatLine } from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const RUN_PLANNER = join(HERE, '..', 'run-planner.mjs')
const SERVER_RUN_ID = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'
const SHORT = 'aaaaaaaa'
const BASE = 'a'.repeat(40)
const C1 = 'b'.repeat(40)
const C2 = 'c'.repeat(40)
const sha7 = (s) => s.slice(0, 7)

const BASE_PLANNER_OUT = {
  proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
  decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
  mainFiles: ['src/x.js'], docFiles: [], testFiles: ['scripts/tests/x.test.mjs'], existingTestEdits: [], redProofShape: 'none',
}
const IMPL_OUT = {
  mainFilesChanged: ['src/x.js'], docFilesChanged: [], verify: [], failingExistingTests: [],
  preExistingFailures: [], publicSurface: 'none', deviations: 'none',
}
const BASE_AUTHOR_OUT = {
  returnLine: 'none', testFiles: ['scripts/tests/x.test.mjs'], scenariosCovered: 'none', verify: [],
  redAuthorTests: [], missingDeclaration: 'none', breachDisclosure: 'none',
}

function stageRow(seat, commits = { pre: '', post: '' }) {
  return { seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits, files: [] }
}
function implCommit(sha, body = 'why\n\nSeat: implementer') {
  return { sha, subject: `feat(x): implement [${SHORT}]`, body, files: ['src/x.js'] }
}
function authorCommit(sha, body = 'why\n\nSeat: test-author') {
  return { sha, subject: `test(x): tests [${SHORT}]`, body, files: ['scripts/tests/x.test.mjs'] }
}

// Builds a plan doc, a result and git facts for one item; verify() is run on them.
// planner: overrides for the planner output; manifest: undefined (no author output) or an output override;
// withAuthor: whether the plan has a test-author stage.
function runVerify({
  planner = {}, withAuthor = true, authorOut, commits, implCommitBody, altitudeSafe = false,
  plannerOutRaw,
} = {}) {
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: { agent: 'task-orchestrator:implementer' }, output: 'implementer-v1' }
  const testStage = { seat: 'test-author', phase: 'work', writes: true, notes: [], dispatch: {}, output: 'test-author-v1', readsExclude: [] }
  const stagesDef = withAuthor ? [plannerStage, implStage, testStage] : [plannerStage, implStage]
  const item = itemFixture({ short: SHORT, stages: stagesDef })
  const args = planFixture({ items: [item], baseSha: BASE })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const gitCommits = commits || (withAuthor
    ? [implCommit(C1, implCommitBody), authorCommit(C2)]
    : [implCommit(C1, implCommitBody)])
  const stageRows = [stageRow('planner', { pre: BASE, post: BASE }), stageRow('implementer', { pre: BASE, post: C1 })]
  if (withAuthor) stageRows.push(stageRow('test-author', { pre: C1, post: C2 }))
  const outputs = {
    planner: plannerOutRaw || { ...BASE_PLANNER_OUT, ...planner },
    implementer: IMPL_OUT,
  }
  if (withAuthor && authorOut !== undefined) outputs['test-author'] = { ...BASE_AUTHOR_OUT, ...authorOut }
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: args.runId, serverRunId: SERVER_RUN_ID,
    items: [{ id: item.id, short: SHORT, status: 'done', reason: 'ok', stages: stageRows, outputs }],
    refused: [], deferred: [],
  }
  if (altitudeSafe) result.altitudeSafe = true
  const exists = { [BASE]: true }
  for (const c of gitCommits) exists[c.sha] = true
  const out = verify(doc, result, { exists, commits: gitCommits })
  return { out, row: out.items.find((i) => i.short === SHORT), doc, result }
}

const sweepEntry = (kind, grep, hits, disposed, extra = {}) => ({ kind, grep, hits, disposed, ...extra })
const sweepFindings = (row) => row.findings.filter((f) => f.startsWith('planner sweep '))
const manifestFindings = (row) => row.findings.filter((f) => f.startsWith('test-manifest misses plan scenario'))
const sweepWarnings = (out) => out.warnings.filter((w) => w.includes('sweep'))

// ---- hasSeatLine (contract fact 11, D1) ----------------------------------------------------------

// S1
test('S1: hasSeatLine is true for an exact Seat line, a -a<n> suffix, trailing spaces or tabs, CRLF and a dotted seat', () => {
  assert.equal(hasSeatLine('why\n\nSeat: implementer', 'implementer'), true)
  assert.equal(hasSeatLine('why\n\nSeat: implementer-a1', 'implementer'), true)
  assert.equal(hasSeatLine('why\n\nSeat: implementer-a10', 'implementer'), true)
  assert.equal(hasSeatLine('why\n\nSeat: implementer-a99', 'implementer'), true)
  assert.equal(hasSeatLine('why\n\nSeat: implementer \t', 'implementer'), true)
  assert.equal(hasSeatLine('why\n\nSeat: implementer-a2  ', 'implementer'), true)
  assert.equal(hasSeatLine('x\r\nSeat: implementer\r\n', 'implementer'), true)
  assert.equal(hasSeatLine('why\n\nSeat: reviewer.docs', 'reviewer.docs'), true)
  assert.equal(hasSeatLine('Seat: implementer', 'implementer'), true, 'a body that is only the line')
  assert.equal(hasSeatLine('Seat: implementer\n\nmore text after', 'implementer'), true, 'the line need not be last')
  assert.equal(hasSeatLine('first\nSeat: implementer\nlast', 'implementer'), true, 'a middle line')
  assert.equal(hasSeatLine('quoted: Seat: other\nSeat: implementer', 'implementer'), true, 'an earlier non-matching line does not hide the real one')
})

// S5 -- each case would be true under String.includes, or under an unanchored or loosened pattern
test('S5: hasSeatLine is false for a longer seat name, a bad -a suffix, a quoted or indented line, odd spacing or casing', () => {
  const no = (body, seat = 'implementer') => assert.equal(hasSeatLine(body, seat), false, JSON.stringify(body) + ' for ' + seat)
  no('Seat: implementer-extra')
  no('Seat: test-author-extra', 'test-author')
  no('Seat: implementer-a01')
  no('Seat: implementer-a0')
  no('Seat: implementer-a')
  no('Seat: implementer-a1x')
  no('Seat: implementer-A1')
  no('Seat: implementer-a1-a2')
  no('see Seat: implementer')
  no('> Seat: implementer')
  no('fixes what Seat: implementer broke')
  no(' Seat: implementer')
  no('\tSeat: implementer')
  no('Seat:implementer')
  no('Seat:  implementer')
  no('seat: implementer')
  no('SEAT: implementer')
  no('Seat: Implementer')
  no('Seat: implementer.')
  no('Seat: implementer ')
  no('Seat: reviewerXdocs', 'reviewer.docs')
  no('Seat: reviewer', 'reviewer.docs')
  no('Seat: reviewer.docs-extra', 'reviewer.docs')
  no('Seat: implementer', 'test-author')
  no('')
  no('why\n\nno trailer at all')
})

test('S5: hasSeatLine is false for a null or undefined body', () => {
  assert.equal(hasSeatLine(null, 'implementer'), false)
  assert.equal(hasSeatLine(undefined, 'implementer'), false)
})

test('S5 probe: a seat name with regular-expression metacharacters is matched literally', () => {
  assert.equal(hasSeatLine('Seat: a+b', 'a+b'), true)
  assert.equal(hasSeatLine('Seat: aab', 'a+b'), false)
  assert.equal(hasSeatLine('Seat: a(b)', 'a(b)'), true)
  assert.equal(hasSeatLine('Seat: ab', 'a(b)'), false)
  assert.equal(hasSeatLine('Seat: anything', '.*'), false)
  assert.equal(hasSeatLine('Seat: a.b', 'a.b'), true)
  assert.equal(hasSeatLine('Seat: aXb', 'a.b'), false)
})

// ---- planner sweep (contract fact 12, AC1-AC3, D2-D5) --------------------------------------------

// S2
test('S2: a sweep entry with disposed < hits fails the item with the exact finding', () => {
  const { row } = runVerify({ planner: { sweep: [sweepEntry('tests', 'g1', 3, 1)] } })
  assert.deepEqual(row.findings, ['planner sweep tests "g1" disposed 1 of 3'])
  assert.equal(row.ok, false)
})

test('S2: the docs kind appears in the finding, and every undisposed entry gets its own finding in sweep order', () => {
  const { row, out } = runVerify({
    planner: { sweep: [sweepEntry('docs', 'd1', 2, 0), sweepEntry('tests', 'g2', 5, 4), sweepEntry('tests', 'ok', 2, 2)] },
  })
  assert.deepEqual(row.findings, ['planner sweep docs "d1" disposed 0 of 2', 'planner sweep tests "g2" disposed 4 of 5'])
  assert.equal(row.ok, false)
  assert.equal(out.ok, false)
})

// S4 / S11 boundary probe d = h-1, h, h+1
test('S4: disposed equal to hits, and 0 of 0, give no finding and no sweep warning; the item stays ok', () => {
  const { row, out } = runVerify({
    planner: { sweep: [sweepEntry('tests', 'g1', 3, 3), sweepEntry('docs', 'g0', 0, 0)] },
    authorOut: { manifestScenarios: ['S1', 'S2'] },
  })
  assert.deepEqual(row.findings, [])
  assert.equal(row.ok, true)
  assert.equal(sweepWarnings(out).length, 0, JSON.stringify(out.warnings))
})

test('S11 boundary: disposed one below, equal to and one above hits', () => {
  const below = runVerify({ planner: { sweep: [sweepEntry('tests', 'g', 3, 2)] } })
  const equal = runVerify({ planner: { sweep: [sweepEntry('tests', 'g', 3, 3)] } })
  const above = runVerify({ planner: { sweep: [sweepEntry('tests', 'g', 3, 4)] } })
  assert.deepEqual(below.row.findings, ['planner sweep tests "g" disposed 2 of 3'])
  assert.deepEqual(equal.row.findings, [])
  assert.deepEqual(above.row.findings, [], 'disposed above hits is no finding (D5)')
  const zero = runVerify({ planner: { sweep: [sweepEntry('tests', 'g', 1, 0)] } })
  assert.deepEqual(zero.row.findings, ['planner sweep tests "g" disposed 0 of 1'])
})

// S9
test('S9: a planner output with no sweep gives exactly one warning naming the item and no finding', () => {
  const { row, out } = runVerify({})
  assert.deepEqual(sweepFindings(row), [])
  assert.deepEqual(row.findings, [])
  assert.equal(row.ok, true)
  const w = out.warnings.filter((x) => x.includes('planner sweep not reported'))
  assert.equal(w.length, 1, JSON.stringify(out.warnings))
  assert.ok(w[0].includes(SHORT), w[0])
})

test('S9: a null sweep is "not reported" too; an empty sweep array is reported, with no warning and no finding', () => {
  const nul = runVerify({ planner: { sweep: null } })
  assert.deepEqual(nul.row.findings, [])
  assert.equal(nul.out.warnings.filter((x) => x.includes('planner sweep not reported')).length, 1)
  const empty = runVerify({ planner: { sweep: [] } })
  assert.deepEqual(empty.row.findings, [])
  assert.equal(empty.out.warnings.filter((x) => x.includes('planner sweep not reported')).length, 0, JSON.stringify(empty.out.warnings))
  assert.equal(empty.row.ok, true)
})

test('S9: two items without a sweep give one warning each, each naming its own short', () => {
  const mk = (short) => itemFixture({
    short,
    stages: [
      { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' },
      { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' },
    ],
  })
  const a = mk('aaaaaaaa')
  const b = mk('bbbbbbbb')
  const args = planFixture({ items: [a, b], baseSha: BASE })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const rowFor = (it, short, sha) => ({
    id: it.id, short, status: 'done', reason: 'ok',
    stages: [stageRow('planner', { pre: BASE, post: BASE }), stageRow('implementer', { pre: BASE, post: sha })],
    outputs: { planner: { ...BASE_PLANNER_OUT }, implementer: IMPL_OUT },
  })
  const commits = [
    { sha: C1, subject: 'feat(x): implement [aaaaaaaa]', body: 'why\n\nSeat: implementer', files: ['src/x.js'] },
    { sha: C2, subject: 'feat(x): implement [bbbbbbbb]', body: 'why\n\nSeat: implementer', files: ['src/x.js'] },
  ]
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: args.runId, serverRunId: SERVER_RUN_ID,
    items: [rowFor(a, 'aaaaaaaa', C1), rowFor(b, 'bbbbbbbb', C2)], refused: [], deferred: [],
  }
  const out = verify(doc, result, { exists: { [BASE]: true, [C1]: true, [C2]: true }, commits })
  const w = out.warnings.filter((x) => x.includes('planner sweep not reported'))
  assert.equal(w.length, 2, JSON.stringify(out.warnings))
  assert.equal(w.filter((x) => x.includes('aaaaaaaa')).length, 1)
  assert.equal(w.filter((x) => x.includes('bbbbbbbb')).length, 1)
})

// S10
test('S10: unowned docs files are one warning each naming the file and the item, and never a finding', () => {
  const { row, out } = runVerify({ planner: { sweep: [sweepEntry('docs', 'g', 2, 2, { unowned: ['a.md', 'b.md'] })] } })
  assert.deepEqual(row.findings, [])
  assert.equal(row.ok, true)
  const wa = out.warnings.filter((w) => w.includes('docs sweep unowned a.md'))
  const wb = out.warnings.filter((w) => w.includes('docs sweep unowned b.md'))
  assert.equal(wa.length, 1, JSON.stringify(out.warnings))
  assert.equal(wb.length, 1, JSON.stringify(out.warnings))
  assert.ok(wa[0].includes(SHORT) && wb[0].includes(SHORT))
})

test('S10: an unowned warning is raised whatever the counts, and an undisposed entry with unowned files gives both', () => {
  const { row, out } = runVerify({ planner: { sweep: [sweepEntry('docs', 'g', 3, 1, { unowned: ['c.md'] })] } })
  assert.deepEqual(row.findings, ['planner sweep docs "g" disposed 1 of 3'])
  assert.equal(out.warnings.filter((w) => w.includes('docs sweep unowned c.md')).length, 1)
  const zero = runVerify({ planner: { sweep: [sweepEntry('docs', 'g', 0, 0, { unowned: ['d.md'] })] } })
  assert.deepEqual(zero.row.findings, [])
  assert.equal(zero.out.warnings.filter((w) => w.includes('docs sweep unowned d.md')).length, 1)
})

test('S10: an empty unowned list gives no unowned warning', () => {
  const { out } = runVerify({ planner: { sweep: [sweepEntry('docs', 'g', 1, 1, { unowned: [] })] } })
  assert.equal(out.warnings.filter((w) => w.includes('docs sweep unowned')).length, 0)
})

// S11
test('S11: the grep is cut to 60 characters in the finding; a 60-character grep is whole; a 61-character one is cut', () => {
  const g100 = 'x'.repeat(100)
  const a = runVerify({ planner: { sweep: [sweepEntry('tests', g100, 1, 0)] } })
  assert.deepEqual(a.row.findings, [`planner sweep tests "${'x'.repeat(60)}" disposed 0 of 1`])
  const g60 = 'y'.repeat(60)
  const b = runVerify({ planner: { sweep: [sweepEntry('tests', g60, 1, 0)] } })
  assert.deepEqual(b.row.findings, [`planner sweep tests "${g60}" disposed 0 of 1`])
  const g61 = 'z'.repeat(60) + 'Q'
  const c = runVerify({ planner: { sweep: [sweepEntry('tests', g61, 1, 0)] } })
  assert.deepEqual(c.row.findings, [`planner sweep tests "${'z'.repeat(60)}" disposed 0 of 1`])
})

test('S11: the grep is inserted raw, with no quote escaping', () => {
  const { row } = runVerify({ planner: { sweep: [sweepEntry('tests', 'a"b\\.c|d', 2, 1)] } })
  assert.deepEqual(row.findings, ['planner sweep tests "a"b\\.c|d" disposed 1 of 2'])
})

// ---- scenario check (AC4, D6) --------------------------------------------------------------------

// S3
test('S3: a plan scenario missing from the manifest is one exact finding', () => {
  const { row } = runVerify({ planner: { testPlanScenarios: ['S1', 'S2', 'S3'] }, authorOut: { manifestScenarios: ['S1', 'S3'] } })
  assert.deepEqual(manifestFindings(row), ['test-manifest misses plan scenario S2'])
  assert.equal(row.ok, false)
})

test('S3: every missing id gets a finding, in plan order, whatever the manifest order', () => {
  const { row } = runVerify({ planner: { testPlanScenarios: ['S3', 'S1', 'S2', 'S4'] }, authorOut: { manifestScenarios: ['S4', 'S2'] } })
  assert.deepEqual(manifestFindings(row), ['test-manifest misses plan scenario S3', 'test-manifest misses plan scenario S1'])
})

test('S3: a manifest that covers the plan gives no finding, and manifest ids absent from the plan are ignored', () => {
  const { row } = runVerify({ planner: { testPlanScenarios: ['S1', 'S2'] }, authorOut: { manifestScenarios: ['S2', 'S1', 'S9'] } })
  assert.deepEqual(row.findings, [])
  assert.equal(row.ok, true)
})

// S12
test('S12: an empty manifest against a one-scenario plan is a finding; [] in the plan is present and finds nothing', () => {
  const a = runVerify({ planner: { testPlanScenarios: ['S1'] }, authorOut: { manifestScenarios: [] } })
  assert.deepEqual(manifestFindings(a.row), ['test-manifest misses plan scenario S1'])
  const b = runVerify({ planner: { testPlanScenarios: [] }, authorOut: { manifestScenarios: ['S1'] } })
  assert.deepEqual(b.row.findings, [])
})

test('S12: ids compare exactly: case-sensitive and untrimmed', () => {
  const a = runVerify({ planner: { testPlanScenarios: ['S1'] }, authorOut: { manifestScenarios: ['s1'] } })
  assert.deepEqual(manifestFindings(a.row), ['test-manifest misses plan scenario S1'])
  const b = runVerify({ planner: { testPlanScenarios: ['S1'] }, authorOut: { manifestScenarios: ['S1 '] } })
  assert.deepEqual(manifestFindings(b.row), ['test-manifest misses plan scenario S1'])
  const c = runVerify({ planner: { testPlanScenarios: ['S1 '] }, authorOut: { manifestScenarios: ['S1'] } })
  assert.deepEqual(manifestFindings(c.row), ['test-manifest misses plan scenario S1 '])
})

test('S12: a plan id listed twice and missing gives one finding', () => {
  const { row } = runVerify({ planner: { testPlanScenarios: ['S1', 'S1', 'S2'] }, authorOut: { manifestScenarios: ['S2'] } })
  assert.deepEqual(manifestFindings(row), ['test-manifest misses plan scenario S1'])
})

test('S12: nothing is checked when either list is absent or null', () => {
  const noPlan = runVerify({ authorOut: { manifestScenarios: [] } })
  assert.deepEqual(manifestFindings(noPlan.row), [])
  const noManifestOut = runVerify({ planner: { testPlanScenarios: ['S1', 'S2'] } })
  assert.deepEqual(manifestFindings(noManifestOut.row), [], 'no test-author output at all')
  const noManifestField = runVerify({ planner: { testPlanScenarios: ['S1'] }, authorOut: { manifestScenarios: undefined } })
  assert.deepEqual(manifestFindings(noManifestField.row), [], 'a test-author output without manifestScenarios')
  const nullManifest = runVerify({ planner: { testPlanScenarios: ['S1'] }, authorOut: { manifestScenarios: null } })
  assert.deepEqual(manifestFindings(nullManifest.row), [])
  const nullPlan = runVerify({ planner: { testPlanScenarios: null }, authorOut: { manifestScenarios: [] } })
  assert.deepEqual(manifestFindings(nullPlan.row), [])
})

test('S12: an item with no test-author stage gets no scenario finding', () => {
  const { row } = runVerify({ withAuthor: false, planner: { testPlanScenarios: ['S1'] } })
  assert.deepEqual(manifestFindings(row), [])
  assert.equal(row.ok, true)
})

// ---- seat attribution through verify (AC5, AC6) --------------------------------------------------

// S6
test('S6: an implementer-range commit whose only Seat text is mid-line is a missing Seat trailer', () => {
  const { row } = runVerify({ withAuthor: false, implCommitBody: 'see Seat: implementer for context' })
  assert.ok(row.findings.includes(`missing Seat trailer ${sha7(C1)}`), JSON.stringify(row.findings))
  assert.equal(row.ok, false)
})

test('S6: a quoted line (> Seat: implementer) and a mid-line mention with a real trailer elsewhere', () => {
  const quoted = runVerify({ withAuthor: false, implCommitBody: 'why\n\n> Seat: implementer' })
  assert.ok(quoted.row.findings.includes(`missing Seat trailer ${sha7(C1)}`), JSON.stringify(quoted.row.findings))
  const both = runVerify({ withAuthor: false, implCommitBody: 'see Seat: implementer\n\nSeat: implementer' })
  assert.equal(both.row.findings.includes(`missing Seat trailer ${sha7(C1)}`), false, JSON.stringify(both.row.findings))
})

// S7
test('S7: a Seat line with a leading-zero suffix or a longer seat name is a missing Seat trailer', () => {
  const a = runVerify({ withAuthor: false, implCommitBody: 'why\n\nSeat: implementer-a01' })
  assert.ok(a.row.findings.includes(`missing Seat trailer ${sha7(C1)}`), JSON.stringify(a.row.findings))
  const b = runVerify({ withAuthor: false, implCommitBody: 'why\n\nSeat: implementer-extra' })
  assert.ok(b.row.findings.includes(`missing Seat trailer ${sha7(C1)}`), JSON.stringify(b.row.findings))
  const c = runVerify({ withAuthor: false, implCommitBody: 'why\n\nSeat: implementer-a0' })
  assert.ok(c.row.findings.includes(`missing Seat trailer ${sha7(C1)}`), JSON.stringify(c.row.findings))
})

test('S7 control: -a1, CRLF and trailing blanks on the trailer line are accepted (AC6)', () => {
  for (const body of ['why\n\nSeat: implementer-a1', 'why\r\n\r\nSeat: implementer\r\n', 'why\n\nSeat: implementer  \t']) {
    const { row } = runVerify({ withAuthor: false, implCommitBody: body })
    assert.deepEqual(row.findings, [], JSON.stringify(body))
    assert.equal(row.ok, true)
  }
})

// S8
test('S8: the author commit that mentions "Seat: implementer" mid-line still belongs to the author alone', () => {
  const { row } = runVerify({
    commits: [implCommit(C1), authorCommit(C2, 'fixes what Seat: implementer broke\n\nSeat: test-author')],
  })
  assert.deepEqual(row.findings, [])
  assert.equal(row.ok, true)
})

test('S8: an author commit with a longer seat name (test-author-extra) is not the test author\'s', () => {
  const { row } = runVerify({
    commits: [implCommit(C1), authorCommit(C2, 'why\n\nSeat: test-author-extra')],
  })
  assert.ok(row.findings.includes(`missing Seat trailer ${sha7(C2)}`), JSON.stringify(row.findings))
  assert.equal(row.ok, false)
})

// S13 -- the altitude-safe result shape (contract fact 5)
test('S13: verify reads the new fields from an altitude-safe result, with the grep cut to 60', () => {
  const g120 = 'k'.repeat(120)
  const { row, result } = runVerify({
    altitudeSafe: true,
    planner: { sweep: [sweepEntry('tests', g120, 4, 2)], testPlanScenarios: ['S1', 'S2', 'S3'] },
    authorOut: { manifestScenarios: ['S1', 'S3'] },
  })
  assert.equal(result.altitudeSafe, true, 'fixture condition: the result is altitude-safe')
  assert.deepEqual(sweepFindings(row), [`planner sweep tests "${'k'.repeat(60)}" disposed 2 of 4`])
  assert.deepEqual(manifestFindings(row), ['test-manifest misses plan scenario S2'])
  assert.equal(row.ok, false)
})

// S14 -- the CLI on a real git repo
function git(dir, args) {
  return execFileSync('git', args, { cwd: dir, encoding: 'utf8' })
}
function makeTempGitRepo() {
  const dir = mkdtempSync(join(tmpdir(), 'verify-sweep-git-'))
  git(dir, ['init', '-q'])
  git(dir, ['config', 'user.email', 'test@example.com'])
  git(dir, ['config', 'user.name', 'Test'])
  return dir
}
function makeCommit(dir, relPath, content, subject, body) {
  const full = join(dir, relPath)
  mkdirSync(dirname(full), { recursive: true })
  writeFileSync(full, content)
  git(dir, ['add', relPath])
  const args = ['commit', '-q', '-m', subject]
  if (body) args.push('-m', body)
  git(dir, args)
  return git(dir, ['rev-parse', 'HEAD']).trim()
}

test('S14 (CLI): verify exits 3 with the sweep finding on stdout for 1 of 2, and 0 for 2 of 2', () => {
  const repo = makeTempGitRepo()
  const baseSha = makeCommit(repo, 'README.md', '# base\n', 'chore: base commit')
  const implSha = makeCommit(repo, 'src/x.js', 'module.exports = 1;\n', `feat(x): implement [${SHORT}]`, 'why\n\nSeat: implementer')
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const item = itemFixture({ short: SHORT, stages: [plannerStage, implStage], worktree: repo })
  const args = planFixture({ items: [item], baseSha })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const resultFor = (sweep) => ({
    contract: 'implement-wave/result-v1', started: true, runId: args.runId, serverRunId: SERVER_RUN_ID,
    items: [{
      id: item.id, short: SHORT, status: 'done', reason: 'ok',
      stages: [
        stageRow('planner', { pre: baseSha, post: baseSha }),
        stageRow('implementer', { pre: baseSha, post: implSha }),
      ],
      outputs: { planner: { ...BASE_PLANNER_OUT, sweep }, implementer: IMPL_OUT },
    }],
    refused: [], deferred: [],
  })
  const dir = mkdtempSync(join(tmpdir(), 'verify-sweep-cli-'))
  const planPath = join(dir, 'plan.json')
  writeFileSync(planPath, JSON.stringify(doc))
  const run = (name, result) => {
    const p = join(dir, name)
    writeFileSync(p, JSON.stringify(result))
    return spawnSync(process.execPath, [RUN_PLANNER, 'verify', '--plan', planPath, '--result', p], { encoding: 'utf8' })
  }
  const bad = run('bad.json', resultFor([sweepEntry('tests', 'g1', 2, 1)]))
  assert.equal(bad.status, 3, bad.stderr)
  const badOut = JSON.parse(bad.stdout)
  assert.equal(badOut.ok, false)
  assert.ok(badOut.items[0].findings.includes('planner sweep tests "g1" disposed 1 of 2'), bad.stdout)
  const good = run('good.json', resultFor([sweepEntry('tests', 'g1', 2, 2)]))
  assert.equal(good.status, 0, good.stderr)
  assert.equal(JSON.parse(good.stdout).ok, true)
})
