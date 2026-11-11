// Coverage for item a33a1150: post-run reports name the pause decision, count open decisions, and
// route reviewer-assign capacity to a back-off. Scenario ids S1-S14 and the probes match the item's
// frozen `test-plan` note; S13 (ralph driver) lives in ralph-driver-fixes.test.mjs.
//
// BLIND test author: no production source under workflows/, scripts/*.mjs or scripts/lib/ was opened
// by any tool. Oracles are the diagnosis Fix A-D, RunView (pauseReason only, openDecisions an Int),
// ErrorDetail.RunPaused {runId, pauseReason, pauseDecisionId?} and the report line formats in the
// test-plan. Doc scenarios (S10-S12, S14) assert the plan's stated phrases, not what the docs say today.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

import { SNAPSHOT_CONTRACT } from '../run-planner-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..')
const REPO_ROOT = join(PLUGIN_ROOT, '..', '..')
const CLI = join(PLUGIN_ROOT, 'scripts', 'run-planner.mjs')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')
const NOW = '2026-09-28T12:00:00Z'

const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'
const ANCESTOR = 'cccccccc-3333-4333-8333-333333333333'
const A = 'aaaaaaaa-1111-4111-8111-111111111111'
const B = 'bbbbbbbb-2222-4222-8222-222222222222'
const DEC = 'd3c1a2b4-0000-4000-8000-00000000d3c1'
const DEC8 = 'd3c1a2b4'

// ---- helpers ----

function runCli(args) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8' })
}
function writeJson(dir, name, value) {
  const p = join(dir, name)
  writeFileSync(p, typeof value === 'string' ? value : JSON.stringify(value))
  return p
}
function boundPlan() {
  const dir = mkdtempSync(join(tmpdir(), 'post-run-reports-'))
  const snap = JSON.parse(readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8'))
  assert.equal(snap.contract, SNAPSHOT_CONTRACT)
  snap.ancestorId = ANCESTOR
  // `plan` reads the snapshot from stdin
  const res = spawnSync(process.execPath, [CLI, 'plan', '--now', NOW, '--scratchpad', join(dir, 'scratchpad')], {
    encoding: 'utf8', input: JSON.stringify(snap),
  })
  assert.equal(res.status, 0, res.stderr)
  const planPath = writeJson(dir, 'plan.json', res.stdout)
  const bound = runCli(['bind-run', '--in', planPath, '--server-run', SERVER_RUN])
  assert.equal(bound.status, 0, bound.stderr)
  return { dir, boundPath: writeJson(dir, 'bound.json', bound.stdout) }
}
function stageStatus(seat, status) {
  return { seat, status, reason: status, modelReported: 'x', notes: [], commits: { pre: '', post: '' }, files: [] }
}
function resultDoc() {
  return {
    contract: 'implement-wave/result-v1', started: true, runId: 'r-202609281200-bbbbbbbb', serverRunId: SERVER_RUN,
    items: [
      { id: A, short: 'aaaaaaaa', status: 'done', reason: 'ok', stages: [stageStatus('implementer', 'done')], outputs: {} },
      { id: B, short: 'bbbbbbbb', status: 'done', reason: 'ok', stages: [stageStatus('implementer', 'done')], outputs: {} },
    ],
    refused: [], deferred: [],
  }
}
const pausedRun = (extra = {}) => ({
  data: { runId: SERVER_RUN, status: 'paused', pauseReason: 'attempts', hints: { phase: 'launched', method: 'A' }, ...extra },
})
const pausedError = (detail) => ({ code: 'run_paused', detail })
// A write answer: ok elements first, then the run_paused element at `at`.
function writeAnswer(errorAt, detail) {
  const results = Array.from({ length: errorAt }, () => ({ ok: true }))
  results.push({ ok: false, error: pausedError(detail) })
  return { ok: false, data: { results } }
}
const DETAIL_ATTEMPTS = { runId: SERVER_RUN, pauseReason: 'attempts', pauseDecisionId: DEC }

function paused(runDoc, pausedDoc) {
  const { dir, boundPath } = boundPlan()
  const args = ['report', '--plan', boundPath, '--event', 'paused']
  if (runDoc !== undefined) args.push('--run', writeJson(dir, 'run.json', runDoc))
  if (pausedDoc !== undefined) args.push('--paused', writeJson(dir, 'paused.json', pausedDoc))
  return runCli(args)
}
const lineOf = (res, prefix) => res.stdout.split('\n').find((l) => l.startsWith(prefix))
function closed(closeDoc) {
  const { dir, boundPath } = boundPlan()
  const args = ['report', '--plan', boundPath, '--event', 'closed', '--result', writeJson(dir, 'result.json', resultDoc())]
  if (closeDoc !== undefined) args.push('--close', writeJson(dir, 'close.json', closeDoc))
  return runCli(args)
}
const closeWith = (openDecisions) => ({ data: { results: [{ ok: true, snapshot: { reason: 'completed', openDecisions } }] } })
function assertInvalidArgs(res) {
  assert.equal(res.status, 2, res.stdout + res.stderr)
  assert.equal(JSON.parse(res.stderr).error, 'invalid args')
}

// ---- paused report: the pause decision comes from the saved run_paused answer ----

test('S1: --run (paused, attempts) plus a --paused write answer whose results[1] is run_paused names the decision on the decisions and pause lines', () => {
  const res = paused(pausedRun(), writeAnswer(1, DETAIL_ATTEMPTS))
  assert.equal(res.status, 0, res.stderr)
  assert.equal(lineOf(res, 'decisions:'), `decisions: ${DEC8} item -`)
  assert.equal(lineOf(res, 'pause:'), `pause: attempts decision ${DEC8}`)
})

test('S2: --paused as a top-level {ok:false, error}, as a bare {code, detail} and as a top-level results array all give the same pause line', () => {
  const shapes = [
    { ok: false, error: pausedError(DETAIL_ATTEMPTS) },
    pausedError(DETAIL_ATTEMPTS),
    { results: [{ ok: false, error: pausedError(DETAIL_ATTEMPTS) }] },
  ]
  for (const shape of shapes) {
    const res = paused(pausedRun(), shape)
    assert.equal(res.status, 0, `${JSON.stringify(shape)}: ${res.stderr}`)
    assert.equal(lineOf(res, 'pause:'), `pause: attempts decision ${DEC8}`, JSON.stringify(shape))
    assert.equal(lineOf(res, 'decisions:'), `decisions: ${DEC8} item -`, JSON.stringify(shape))
  }
})

test('S3: a run_paused detail with a reason and no id gives a pause line with decision - and decisions none', () => {
  const res = paused(pausedRun({ pauseReason: 'manual' }), writeAnswer(0, { runId: SERVER_RUN, pauseReason: 'manual' }))
  assert.equal(res.status, 0, res.stderr)
  assert.equal(lineOf(res, 'pause:'), 'pause: manual decision -')
  assert.equal(lineOf(res, 'decisions:'), 'decisions: none')
})

test('S4: the run_paused detail reason wins over the run view reason (run says manual, detail says attempts)', () => {
  const res = paused(pausedRun({ pauseReason: 'manual' }), writeAnswer(0, DETAIL_ATTEMPTS))
  assert.equal(res.status, 0, res.stderr)
  assert.equal(lineOf(res, 'pause:'), `pause: attempts decision ${DEC8}`)
})

test('S5: a --paused file with only ok elements, or with a run_closed error, exits 2 invalid args', () => {
  assertInvalidArgs(paused(pausedRun(), { ok: true, data: { results: [{ ok: true }, { ok: true }] } }))
  assertInvalidArgs(paused(pausedRun(), { ok: false, data: { results: [{ ok: false, error: { code: 'run_closed', detail: { runId: SERVER_RUN } } }] } }))
})

test('S6: --run alone (no --paused) leaves the pause line with decision - and decisions none; a run view carrying a pauseDecisionId field is not read', () => {
  const plain = paused(pausedRun())
  assert.equal(plain.status, 0, plain.stderr)
  assert.equal(lineOf(plain, 'pause:'), 'pause: attempts decision -')
  assert.equal(lineOf(plain, 'decisions:'), 'decisions: none')
  // Fixture condition: the removed read of run.pauseDecisionId / run.pause.decisionId would print DEC8 here.
  const wired = paused(pausedRun({ pauseDecisionId: DEC, pause: { decisionId: DEC } }))
  assert.equal(lineOf(wired, 'pause:'), 'pause: attempts decision -')
  assert.equal(lineOf(wired, 'decisions:'), 'decisions: none')
})

// ---- closed report: openDecisions is an Int count ----

test('S7: a close snapshot with openDecisions 2 prints open decisions 2 (ids unlisted)', () => {
  const res = closed(closeWith(2))
  assert.equal(res.status, 0, res.stderr)
  assert.equal(lineOf(res, 'close:'), 'close: completed, open decisions 2 (ids unlisted)')
})

test('S8: openDecisions 0, or a snapshot without openDecisions, prints open decisions none', () => {
  assert.equal(lineOf(closed(closeWith(0)), 'close:'), 'close: completed, open decisions none')
  const bare = { data: { results: [{ ok: true, snapshot: { reason: 'completed' } }] } }
  assert.equal(lineOf(closed(bare), 'close:'), 'close: completed, open decisions none')
})

test('S9: an openDecisions array of ids is not a count: open decisions none (the array branch is gone)', () => {
  const res = closed(closeWith([DEC]))
  assert.equal(res.status, 0, res.stderr)
  assert.equal(lineOf(res, 'close:'), 'close: completed, open decisions none')
})

// ---- probes ----

test('PROBE: an empty, null or empty-object --paused body exits 2 invalid args', () => {
  for (const body of ['', 'null', '{}', '[]']) {
    assertInvalidArgs(paused(pausedRun(), body))
  }
})

test('PROBE: a run_paused element at results[2] is found, and a run_closed element next to a run_paused one lets run_paused win', () => {
  const at2 = paused(pausedRun(), writeAnswer(2, DETAIL_ATTEMPTS))
  assert.equal(at2.status, 0, at2.stderr)
  assert.equal(lineOf(at2, 'pause:'), `pause: attempts decision ${DEC8}`)
  const mixed = {
    ok: false,
    data: { results: [
      { ok: false, error: { code: 'run_closed', detail: { runId: SERVER_RUN } } },
      { ok: false, error: pausedError(DETAIL_ATTEMPTS) },
    ] },
  }
  const res = paused(pausedRun(), mixed)
  assert.equal(res.status, 0, res.stderr)
  assert.equal(lineOf(res, 'pause:'), `pause: attempts decision ${DEC8}`)
})

test('PROBE: openDecisions -1, 2.5 and the string "2" are not counts: open decisions none', () => {
  for (const bad of [-1, 2.5, '2']) {
    const res = closed(closeWith(bad))
    assert.equal(res.status, 0, res.stderr)
    assert.equal(lineOf(res, 'close:'), 'close: completed, open decisions none', `openDecisions ${JSON.stringify(bad)}`)
  }
})

// ---- docs and prompt text (S10, S11, S12, S14) ----

const read = (...parts) => readFileSync(join(PLUGIN_ROOT, ...parts), 'utf8')
const RUN_WAVE = read('skills', 'run-wave', 'SKILL.md')
const POST_RUN = read('skills', 'run-wave', 'references', 'post-run.md')
const TIERS = read('skills', 'director', 'references', 'coordinator-tiers.md')
const slice = (text, from, to) => {
  const a = text.indexOf(from)
  assert.ok(a !== -1, `document has "${from}"`)
  const b = text.indexOf(to, a + from.length)
  return text.slice(a, b === -1 ? undefined : b)
}

test('S10: the run-wave run_at_capacity row names the reviewer assign and a back-off, keeps Method A and Step 5a; post-run Step 5 names run_at_capacity', () => {
  const row = RUN_WAVE.split('\n').find((l) => l.includes('run_at_capacity'))
  assert.ok(row, 'a run_at_capacity row in SKILL.md')
  assert.ok(/reviewer/.test(row), `row names reviewer: ${row}`)
  assert.ok(/back(s)? off/.test(row), `row names a back-off: ${row}`)
  assert.ok(row.includes('Method A') && row.includes('Step 5a'), `row keeps Method A and Step 5a: ${row}`)
  const step5 = slice(POST_RUN, '## Step 5', '## Step 6')
  assert.ok(step5.includes('run_at_capacity'), 'post-run Step 5 names run_at_capacity for the reviewer assign')
})

test('S11: the SKILL.md Reporting text and coordinator-tiers.md name --paused; post-run Step 0 passes --paused', () => {
  assert.ok(RUN_WAVE.includes('--paused'), 'SKILL.md names --paused')
  assert.ok(TIERS.includes('--paused'), 'coordinator-tiers.md names --paused')
  const step0 = slice(POST_RUN, '## Step 0 ', '## Step 0b')
  assert.ok(step0.includes('--paused'), 'post-run Step 0 passes --paused')
})

function textFiles(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'tests') continue
    const p = join(dir, name)
    if (statSync(p).isDirectory()) textFiles(p, out)
    else if (/\.(md|mjs|js|json)$/.test(name)) out.push(p)
  }
  return out
}

test('S12: no plugin skill, workflow or script text has a paused:{decisionId} member (any spacing); both wave workflows keep a run_paused line naming pauseDecisionId', () => {
  const offenders = []
  for (const sub of ['skills', 'workflows', 'scripts']) {
    for (const p of textFiles(join(PLUGIN_ROOT, sub))) {
      readFileSync(p, 'utf8').split(/\r?\n/).forEach((line, i) => {
        if (/paused\s*:\s*\{\s*decisionId/.test(line)) offenders.push(`${relative(PLUGIN_ROOT, p)}:${i + 1}`)
      })
    }
  }
  assert.deepEqual(offenders, [], 'a write success never carries a paused member')
  for (const wf of ['implement-wave.js', 'review-wave.js']) {
    const lines = read('workflows', wf).split(/\r?\n/).filter((l) => l.includes('run_paused') && l.includes('pauseDecisionId'))
    assert.ok(lines.length >= 1, `${wf} keeps a run_paused line naming pauseDecisionId`)
  }
  const schemaWorkflow = read('skills', 'schema-workflow', 'SKILL.md')
  const pausedRow = schemaWorkflow.split('\n').find((l) => l.trimStart().startsWith('|') && (l.split('|')[1] || '').includes('`paused`'))
  assert.equal(pausedRow, undefined, 'schema-workflow has no `paused` result-table row')
})

test('S14 (director): director SKILL.md describes no answer amount', () => {
  const director = read('skills', 'director', 'SKILL.md')
  assert.equal(/\bamount\b/i.test(director), false, 'director SKILL.md describes an answer amount')
})

test('S14 (work-summary): work-summary SKILL.md has no "budgeted" and no Budget column', () => {
  const workSummary = read('skills', 'work-summary', 'SKILL.md')
  assert.equal(/budgeted/i.test(workSummary), false, 'work-summary names budgeted')
  const budgetColumn = workSummary.split(/\r?\n/).filter((l) => l.trimStart().startsWith('|') && /budget/i.test(l))
  assert.deepEqual(budgetColumn, [], 'work-summary has a Budget column')
})

test('S14 (api-reference): the report row of api-reference.md does not say "attempts and budget"', () => {
  const api = readFileSync(join(REPO_ROOT, 'current', 'docs', 'api-reference.md'), 'utf8')
  assert.equal(api.includes('attempts and budget'), false, 'api-reference.md report row still says attempts and budget')
})
