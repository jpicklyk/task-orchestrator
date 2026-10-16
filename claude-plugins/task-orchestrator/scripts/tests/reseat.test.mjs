// Coverage for `run-planner reseat` (item 4bd560d0): the pure lib reseat() in run-exec-lib.mjs, the
// CLI subcommand routed from run-planner.mjs, the fix-cycle model in verify(), and the ledger read by
// provenance(). Scenario ids S1-S16 match the item's frozen `test-plan` note; S17-S18 and the probes
// (P-*) are additions recorded in the test-manifest.
// Oracles: task-scope decisions 1-3 ([T1]-[T3]), proposal ac523b3b ([P]), the pre-change
// post-run.md fix-cycle and extra-seats grammar ([R]), and the pre-change verify finding texts ([V]) -
// never "what the code returns". This suite never reads run-exec-lib.mjs's or run-planner.mjs's
// source text; it uses only their exported surface, the run-planner CLI as a subprocess, and
// workflow-harness.mjs.
// run-exec-lib.mjs is imported as a namespace so that a missing `reseat` export fails each reseat test
// individually instead of failing the whole file to load.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync, execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { planFixture, itemFixture, stages } from './workflow-harness.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import * as execLib from '../run-exec-lib.mjs'
import { parseExtraSeats } from '../provenance-lib.mjs'

const { initState, prompt, verify, provenance } = execLib

const HERE = dirname(fileURLToPath(import.meta.url))
const RUN_PLANNER = join(HERE, '..', 'run-planner.mjs')

const SHORT = 'aaaaaaaa'
const RUN_ID = 'r-test-0001' // planFixture's default runId
const BASE = 'a'.repeat(40)
const C1 = 'b'.repeat(40)
const C2 = 'c'.repeat(40)
const C3 = 'd'.repeat(40)
const C4 = 'e'.repeat(40)
const sha7 = (s) => s.slice(0, 7)

function realCore() {
  return loadWaveCore()
}

function callReseat(core, doc, result, idOrShort, seat, opts) {
  return execLib.reseat(core, doc, result, idOrShort, seat, opts)
}

// ── fixtures ────────────────────────────────────────────────────────────────────────────────────

const PLANNER_OUT = {
  proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
  decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
  mainFiles: ['src/x.js'], docFiles: [], testFiles: ['scripts/tests/x.test.mjs'], existingTestEdits: [], redProofShape: 'none',
}
const IMPL_OUT = {
  mainFilesChanged: ['src/x.js'], docFilesChanged: [], verify: [], failingExistingTests: [],
  preExistingFailures: [], publicSurface: 'none', deviations: 'none',
}
const DECL_NONE = { declarations: 'none', harnessPointers: 'ptr-kept', gaps: 'none' }

function stageList({ withExtractor = true, implModel = 'sonnet' } = {}) {
  const list = stages.bugFixLike()
  if (implModel) {
    list.find((s) => s.seat === 'implementer').dispatch = { agent: 'task-orchestrator:implementer', model: implModel }
  }
  return withExtractor ? list : list.filter((s) => s.seat !== 'declarations-extractor')
}

function makeDoc({ list = stageList(), method, worktree, baseSha } = {}) {
  const item = itemFixture({ short: SHORT, stages: list, ...(worktree ? { worktree } : {}) })
  const args = planFixture({ items: [item], ...(baseSha ? { baseSha } : {}) })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: method ? { method } : {} }
  return { doc, item }
}

function stageRow(seat, commits = { pre: '', post: '' }) {
  return { seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits, files: [] }
}

function makeOutputs(over = {}) {
  return { planner: PLANNER_OUT, implementer: IMPL_OUT, 'declarations-extractor': DECL_NONE, ...over }
}

function makeResult(doc, outs = makeOutputs(), reseats) {
  const item = doc.args.items[0]
  const row = {
    id: item.id, short: item.short, status: 'done', reason: 'ok',
    stages: item.stages.map((s) => stageRow(s.seat)),
    outputs: outs,
  }
  if (reseats) row.reseats = reseats
  return {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [row], refused: [], deferred: [],
  }
}

function clone(x) {
  return JSON.parse(JSON.stringify(x))
}

function ledgerEntry(seat, pre, over = {}) {
  const m = /^(.+)-a([1-9][0-9]*)$/.exec(seat)
  return { seat, baseSeat: m[1], cycle: Number(m[2]), model: 'sonnet', tokens: 'unknown', pre, ...over }
}

// ── git helpers (array-form execFileSync, no shell quoting) ─────────────────────────────────────

function git(dir, args) {
  return execFileSync('git', args, { cwd: dir, encoding: 'utf8' })
}
function makeTempGitRepo() {
  const dir = mkdtempSync(join(tmpdir(), 'reseat-git-'))
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

function runCli(args) {
  return spawnSync(process.execPath, [RUN_PLANNER, ...args], { encoding: 'utf8' })
}

function writeJson(dir, name, obj) {
  const p = join(dir, name)
  writeFileSync(p, JSON.stringify(obj))
  return p
}

function cliBase(planPath, resultPath, seat, extra = []) {
  return ['reseat', '--plan', planPath, '--run', RUN_ID, '--result', resultPath, '--item', SHORT, '--seat', seat, ...extra]
}

// ── S1: implementer-a1 prompt ───────────────────────────────────────────────────────────────────

test('S1: reseat implementer-a1 renders an in-phase fix-cycle prompt with the -a1 seat, actor and trailer, no ENTRY block', () => {
  const { doc } = makeDoc()
  const out = callReseat(realCore(), doc, makeResult(doc), SHORT, 'implementer-a1', { pre: C2 })
  assert.equal(out.ok, true, JSON.stringify(out))
  assert.equal(out.seat, 'implementer-a1')
  assert.equal(out.baseSeat, 'implementer')
  assert.equal(out.cycle, 1)
  assert.ok(JSON.stringify(out.actor).includes(`implementer-a1:${SHORT}:${RUN_ID}`), JSON.stringify(out.actor))
  const p = out.prompt
  assert.ok(p.includes(`SEAT: implementer-a1 for item item-${SHORT}`), 'seat line')
  assert.ok(p.includes(`"id":"implementer-a1:${SHORT}:${RUN_ID}"`), 'actor id in the ACTOR line')
  assert.ok(p.includes('-m "Seat: implementer-a1"'), 'commit trailer')
  assert.ok(p.includes('with trailer "Seat: implementer-a1"'), 'RERUN CHECK trailer')
  assert.ok(p.includes(`actor implementer-a1:${SHORT}:${RUN_ID}`), 'RERUN CHECK actor')
  assert.ok(
    p.includes('FIX CYCLE A1: work only section "## Amendment A1" of the item\'s planning note and the blocking findings it cites.'),
    'fix-cycle line'
  )
  assert.equal(p.includes('ENTRY:'), false, 'no ENTRY block for an out-of-run in-phase seat')
  assert.equal(p.includes('ENTRY REPORT'), false)
  assert.ok(p.includes('protocol.in-phase-seat'), 'non-entering seat rule')
  assert.equal(p.includes('protocol.entry-seat'), false)
  assert.ok(p.includes('HANDOFF from planner:') && p.includes('mainFiles: src/x.js'), 'outs come from the result item outputs')
})

test('S1: the cycle number is parsed as a number (a2, a12) and drives the amendment section', () => {
  const { doc } = makeDoc()
  for (const [seat, n] of [['test-author-a2', 2], ['implementer-a12', 12]]) {
    const opts = seat.startsWith('test-author') ? { pre: C2, declarations: 'export function f(x)' } : { pre: C2 }
    const out = callReseat(realCore(), doc, makeResult(doc), SHORT, seat, opts)
    assert.equal(out.ok, true, JSON.stringify(out))
    assert.equal(out.cycle, n)
    assert.equal(out.seat, seat)
    assert.ok(out.prompt.includes(`FIX CYCLE A${n}: work only section "## Amendment A${n}"`), seat)
    assert.ok(out.prompt.includes(`Seat: ${seat}`), seat)
  }
})

test('P-id: the item is found by its full id as well as its short id', () => {
  const { doc, item } = makeDoc()
  const byShort = callReseat(realCore(), doc, makeResult(doc), SHORT, 'implementer-a1', { pre: C2 })
  const byId = callReseat(realCore(), doc, makeResult(doc), item.id, 'implementer-a1', { pre: C2 })
  assert.equal(byId.ok, true)
  assert.equal(byId.prompt, byShort.prompt)
})

test('P-planner: a non-writing stage seat is a valid base (planner-a1)', () => {
  const { doc } = makeDoc()
  const out = callReseat(realCore(), doc, makeResult(doc), SHORT, 'planner-a1', { pre: C2 })
  assert.equal(out.ok, true, JSON.stringify(out))
  assert.equal(out.baseSeat, 'planner')
  assert.ok(out.prompt.includes('SEAT: planner-a1 for item'))
})

test('P-methodB: a Method B plan gets the METHOD B RETURN addendum that prompt() appends', () => {
  const { doc } = makeDoc({ method: 'B' })
  const out = callReseat(realCore(), doc, makeResult(doc), SHORT, 'implementer-a1', { pre: C2 })
  assert.equal(out.ok, true)
  assert.ok(out.prompt.includes('METHOD B RETURN:'))
  assert.ok(out.prompt.includes('Envelope JSON schema: '))
})

// ── S2: ledger entry, input untouched ───────────────────────────────────────────────────────────

test('S2: the returned result gains one reseats entry; the input result is not mutated', () => {
  const { doc } = makeDoc()
  const input = makeResult(doc)
  const before = clone(input)
  const out = callReseat(realCore(), doc, input, SHORT, 'implementer-a1', { pre: C2 })
  assert.equal(out.ok, true)
  assert.deepEqual(out.result.items[0].reseats, [
    { seat: 'implementer-a1', baseSeat: 'implementer', cycle: 1, model: 'sonnet', tokens: 'unknown', pre: C2 },
  ])
  assert.deepEqual(input, before, 'input result must be unchanged')
  assert.notEqual(out.result, input)
  assert.notEqual(out.result.items[0], input.items[0])
  assert.equal(input.items[0].reseats, undefined)
  // everything else on the item is carried over
  assert.deepEqual(out.result.items[0].outputs, input.items[0].outputs)
  assert.deepEqual(out.result.items[0].stages, input.items[0].stages)
})

test('S2: model precedence is opts.model, then the base stage dispatch.model, then "unknown"', () => {
  const { doc } = makeDoc()
  const core = realCore()
  const viaDispatch = callReseat(core, doc, makeResult(doc), SHORT, 'implementer-a1', { pre: C2 })
  assert.equal(viaDispatch.result.items[0].reseats[0].model, 'sonnet')
  const viaOpts = callReseat(core, doc, makeResult(doc), SHORT, 'implementer-a1', { pre: C2, model: 'opus' })
  assert.equal(viaOpts.result.items[0].reseats[0].model, 'opus')
  // the test-author stage declares no dispatch.model
  const unknown = callReseat(core, doc, makeResult(doc), SHORT, 'test-author-a1', { pre: C2, declarations: 'export function f(x)' })
  assert.equal(unknown.ok, true, JSON.stringify(unknown))
  assert.equal(unknown.result.items[0].reseats[0].model, 'unknown')
  assert.equal(String(unknown.result.items[0].reseats[0].tokens), 'unknown')
})

// ── S3: test-author-a1 with a declarations file ─────────────────────────────────────────────────

function inRunTestAuthorPrompt(core, doc, declarations) {
  const state = initState(doc, 'A')
  state.outs[`${SHORT}:planner`] = PLANNER_OUT
  state.outs[`${SHORT}:implementer`] = IMPL_OUT
  state.outs[`${SHORT}:declarations-extractor`] = { ...DECL_NONE, declarations }
  return prompt(core, doc, state, SHORT, 'test-author')
}

test('S3: test-author-a1 with --declarations text replaces the extractor declarations and keeps its other fields', () => {
  const { doc } = makeDoc()
  const core = realCore()
  const text = 'export function f(x)'
  const out = callReseat(core, doc, makeResult(doc), SHORT, 'test-author-a1', { pre: C2, declarations: text })
  assert.equal(out.ok, true, JSON.stringify(out))
  assert.ok(out.prompt.includes(`declarations: ${text}`))
  assert.ok(out.prompt.includes('harnessPointers: ptr-kept'), 'other declarations-v1 fields are kept')
  assert.ok(out.prompt.includes('-m "Seat: test-author-a1"'))
  assert.ok(out.prompt.includes('FIX CYCLE A1'))
  // parity with the in-run test-author prompt: only the seat name and the fix-cycle line differ
  const inRun = inRunTestAuthorPrompt(core, doc, text)
  const normalized = out.prompt
    .split('\n')
    .filter((l) => !l.startsWith('FIX CYCLE A1'))
    .join('\n')
    .split('test-author-a1').join('test-author')
  assert.equal(normalized, inRun)
})

test('S3: the result declarations stay untouched in the returned result (replacement is for the prompt only)', () => {
  const { doc } = makeDoc()
  const out = callReseat(realCore(), doc, makeResult(doc), SHORT, 'test-author-a1', { pre: C2, declarations: 'export function f(x)' })
  assert.deepEqual(out.result.items[0].outputs['declarations-extractor'], DECL_NONE)
})

test('P-scan: declarations containing a behaviour word are redacted exactly as in the in-run test-author prompt', () => {
  const { doc } = makeDoc()
  const core = realCore()
  const text = 'export function f(x) // returns a value'
  const out = callReseat(core, doc, makeResult(doc), SHORT, 'test-author-a1', { pre: C2, declarations: text })
  assert.equal(out.ok, true, JSON.stringify(out))
  const inRunLine = inRunTestAuthorPrompt(core, doc, text).split('\n').find((l) => l.startsWith('declarations: '))
  assert.ok(inRunLine)
  assert.notEqual(inRunLine, `declarations: ${text}`, 'fixture condition: the in-run prompt redacts this text')
  assert.ok(out.prompt.split('\n').includes(inRunLine))
  assert.equal(out.prompt.includes('returns a value'), false)
})

test('S3 (CLI): --declarations <file> is read as plain text and reaches the HANDOFF', () => {
  const { doc } = makeDoc()
  const dir = mkdtempSync(join(tmpdir(), 'reseat-cli-'))
  const planPath = writeJson(dir, 'plan.json', doc)
  const resultPath = writeJson(dir, 'result.json', makeResult(doc))
  const declPath = join(dir, 'decl.txt')
  writeFileSync(declPath, 'export function f(x)')
  const res = runCli(cliBase(planPath, resultPath, 'test-author-a1', ['--declarations', declPath, '--pre', C2]))
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.ok, true)
  assert.ok(out.prompt.includes('declarations: export function f(x)'))
  assert.equal(out.result.items[0].reseats[0].pre, C2)
})

// ── S11: missing declarations ───────────────────────────────────────────────────────────────────

test('S11: test-author-a1 on an item with a declarations stage and no declarations anywhere is refused', () => {
  const { doc } = makeDoc()
  const out = callReseat(realCore(), doc, makeResult(doc), SHORT, 'test-author-a1', { pre: C2 })
  assert.equal(out.ok, false)
  assert.match(out.error, /^missingDeclaration: /)
  assert.equal(out.prompt, undefined)
})

test('S11 probe: empty, whitespace-only and "none" declarations count as absent (still refused)', () => {
  const { doc } = makeDoc()
  for (const declarations of ['', '   \n  ', 'none']) {
    const out = callReseat(realCore(), doc, makeResult(doc), SHORT, 'test-author-a1', { pre: C2, declarations })
    assert.equal(out.ok, false, JSON.stringify(declarations))
    assert.match(out.error, /^missingDeclaration: /)
  }
})

test('S11 probe: an absent declarations file falls back to the declarations already in the result', () => {
  const { doc } = makeDoc()
  const outs = makeOutputs({ 'declarations-extractor': { ...DECL_NONE, declarations: 'export function g(y)' } })
  for (const declarations of [undefined, '', 'none']) {
    const out = callReseat(realCore(), doc, makeResult(doc, outs), SHORT, 'test-author-a1', { pre: C2, declarations })
    assert.equal(out.ok, true, JSON.stringify(out))
    assert.ok(out.prompt.includes('declarations: export function g(y)'))
  }
})

test('S11 edge: an item without a declarations-v1 stage is not refused for missing declarations', () => {
  const { doc } = makeDoc({ list: stageList({ withExtractor: false }) })
  const outs = { planner: PLANNER_OUT, implementer: IMPL_OUT }
  const out = callReseat(realCore(), doc, makeResult(doc, outs), SHORT, 'test-author-a1', { pre: C2 })
  assert.equal(out.ok, true, JSON.stringify(out))
})

test('S11 (CLI): the refusal exits 3 with the ok:false JSON on stdout', () => {
  const { doc } = makeDoc()
  const dir = mkdtempSync(join(tmpdir(), 'reseat-cli-'))
  const planPath = writeJson(dir, 'plan.json', doc)
  const resultPath = writeJson(dir, 'result.json', makeResult(doc))
  const res = runCli(cliBase(planPath, resultPath, 'test-author-a1', ['--pre', C2]))
  assert.equal(res.status, 3, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.ok, false)
  assert.match(out.error, /^missingDeclaration: /)
})

// ── S12: invalid args ───────────────────────────────────────────────────────────────────────────

const BAD_SEATS = [
  'implementer', 'implementer-a0', 'implementer-a01', 'implementer-A1', 'implementer-a',
  'reviewer-a1', 'IMPLEMENTER-a1', 'implementer-a1 ', 'implementer-a-1', 'implementer-a1-a2', 'nobody-a1', '-a1', '',
]

test('S12: seats that are not "<stage seat>-a<n>" with n >= 1 throw "invalid args: ..."', () => {
  const { doc } = makeDoc()
  for (const seat of BAD_SEATS) {
    assert.throws(
      () => callReseat(realCore(), doc, makeResult(doc), SHORT, seat, { pre: C2 }),
      (err) => err instanceof Error && /^invalid args: /.test(err.message),
      JSON.stringify(seat)
    )
  }
})

test('S12: an unknown item throws "invalid args: ..."; --declarations on a non-test-author base throws', () => {
  const { doc } = makeDoc()
  assert.throws(
    () => callReseat(realCore(), doc, makeResult(doc), 'zzzzzzzz', 'implementer-a1', { pre: C2 }),
    (err) => /^invalid args: /.test(err.message)
  )
  assert.throws(
    () => callReseat(realCore(), doc, makeResult(doc), SHORT, 'implementer-a1', { pre: C2, declarations: 'export function f(x)' }),
    (err) => /^invalid args: /.test(err.message)
  )
})

test('S12 (CLI): each invalid argument exits 2 with an "invalid args" error on stderr', () => {
  const { doc } = makeDoc()
  const dir = mkdtempSync(join(tmpdir(), 'reseat-cli-'))
  const planPath = writeJson(dir, 'plan.json', doc)
  const resultPath = writeJson(dir, 'result.json', makeResult(doc))
  const declPath = join(dir, 'decl.txt')
  writeFileSync(declPath, 'export function f(x)')
  // control: a valid invocation of the same shape succeeds, so the exit-2 rows below are about the
  // arguments and not about the subcommand being unknown
  const control = runCli(cliBase(planPath, resultPath, 'implementer-a1', ['--pre', C2]))
  assert.equal(control.status, 0, control.stderr)
  const cases = []
  for (const seat of ['implementer','implementer-a0', 'implementer-a01', 'implementer-A1', 'implementer-a', 'reviewer-a1']) {
    cases.push([`seat ${seat}`, cliBase(planPath, resultPath, seat, ['--pre', C2])])
  }
  cases.push(['unknown item', ['reseat', '--plan', planPath, '--run', RUN_ID, '--result', resultPath, '--item', 'zzzzzzzz', '--seat', 'implementer-a1', '--pre', C2]])
  cases.push(['run mismatch', ['reseat', '--plan', planPath, '--run', 'r-different', '--result', resultPath, '--item', SHORT, '--seat', 'implementer-a1', '--pre', C2]])
  cases.push(['no --result', ['reseat', '--plan', planPath, '--run', RUN_ID, '--item', SHORT, '--seat', 'implementer-a1', '--pre', C2]])
  cases.push(['--declarations on implementer-a1', cliBase(planPath, resultPath, 'implementer-a1', ['--declarations', declPath, '--pre', C2])])
  for (const [label, argv] of cases) {
    const res = runCli(argv)
    assert.equal(res.status, 2, `${label}: exit ${res.status}, stdout ${res.stdout}, stderr ${res.stderr}`)
    assert.equal(JSON.parse(res.stderr).error, 'invalid args', label)
  }
})

// ── S13: replay ─────────────────────────────────────────────────────────────────────────────────

test('S13: re-invoking the same seat updates tokens only when passed, never pre, never duplicates', () => {
  const { doc } = makeDoc()
  const core = realCore()
  const first = callReseat(core, doc, makeResult(doc), SHORT, 'implementer-a1', { pre: C2 })
  assert.equal(first.ok, true)
  const second = callReseat(core, doc, first.result, SHORT, 'implementer-a1', { pre: C3, tokens: 900 })
  assert.equal(second.ok, true)
  const ledger = second.result.items[0].reseats
  assert.equal(ledger.length, 1)
  assert.equal(String(ledger[0].tokens), '900')
  assert.equal(ledger[0].pre, C2, 'pre is recorded once and never replaced')
  assert.equal(ledger[0].model, 'sonnet')
  // a third replay passing nothing keeps the tokens, and passing a model updates only the model
  const third = callReseat(core, doc, second.result, SHORT, 'implementer-a1', { pre: C4 })
  assert.equal(third.result.items[0].reseats.length, 1)
  assert.equal(String(third.result.items[0].reseats[0].tokens), '900')
  assert.equal(third.result.items[0].reseats[0].pre, C2)
  const fourth = callReseat(core, doc, third.result, SHORT, 'implementer-a1', { pre: C4, model: 'opus' })
  assert.equal(fourth.result.items[0].reseats.length, 1)
  assert.equal(fourth.result.items[0].reseats[0].model, 'opus')
  assert.equal(String(fourth.result.items[0].reseats[0].tokens), '900')
  assert.equal(fourth.result.items[0].reseats[0].pre, C2)
})

test('S13 probe: distinct seats append in invocation order', () => {
  const { doc } = makeDoc()
  const core = realCore()
  const a = callReseat(core, doc, makeResult(doc), SHORT, 'implementer-a1', { pre: C2 })
  const b = callReseat(core, doc, a.result, SHORT, 'test-author-a1', { pre: C3, declarations: 'export function f(x)' })
  const c = callReseat(core, doc, b.result, SHORT, 'implementer-a2', { pre: C4 })
  assert.deepEqual(c.result.items[0].reseats.map((r) => r.seat), ['implementer-a1', 'test-author-a1', 'implementer-a2'])
  assert.deepEqual(c.result.items[0].reseats.map((r) => r.cycle), [1, 1, 2])
  assert.deepEqual(c.result.items[0].reseats.map((r) => r.pre), [C2, C3, C4])
})

// ── S16: CLI records HEAD when --pre is absent ──────────────────────────────────────────────────

test('S16 (CLI): without --pre the ledger pre is the item worktree HEAD; with --pre it is taken verbatim', () => {
  const repo = makeTempGitRepo()
  makeCommit(repo, 'README.md', '# base\n', 'chore: base commit')
  const head = makeCommit(repo, 'src/x.js', 'module.exports = 1;\n', `feat(x): implement [${SHORT}]`, 'why\n\nSeat: implementer')
  const { doc } = makeDoc({ worktree: repo })
  const dir = mkdtempSync(join(tmpdir(), 'reseat-cli-'))
  const planPath = writeJson(dir, 'plan.json', doc)
  const resultPath = writeJson(dir, 'result.json', makeResult(doc))

  const res = runCli(cliBase(planPath, resultPath, 'implementer-a1'))
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.ok, true)
  assert.notEqual(head, doc.args.baseSha, 'fixture condition: HEAD differs from the plan baseSha')
  assert.equal(out.result.items[0].reseats[0].pre, head)

  const explicit = runCli(cliBase(planPath, resultPath, 'implementer-a1', ['--pre', C1]))
  assert.equal(explicit.status, 0, explicit.stderr)
  assert.equal(JSON.parse(explicit.stdout).result.items[0].reseats[0].pre, C1)
})

test('P-state (CLI): --result accepts a run-wave/state-v1 document as well as result-v1', () => {
  const { doc } = makeDoc()
  const state = initState(doc, 'B')
  state.outs[`${SHORT}:planner`] = PLANNER_OUT
  state.outs[`${SHORT}:implementer`] = IMPL_OUT
  state.outs[`${SHORT}:declarations-extractor`] = DECL_NONE
  for (const s of doc.args.items[0].stages) {
    state.stages[`${SHORT}:${s.seat}`] = {
      status: 'done', reason: 'ok', notes: [], commits: { pre: '', post: '' }, files: [], modelReported: 'sonnet',
      output: state.outs[`${SHORT}:${s.seat}`] || {},
    }
  }
  const dir = mkdtempSync(join(tmpdir(), 'reseat-cli-'))
  const planPath = writeJson(dir, 'plan.json', doc)
  const statePath = writeJson(dir, 'state.json', state)
  const res = runCli(cliBase(planPath, statePath, 'implementer-a1', ['--pre', C2]))
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.ok, true)
  assert.ok(out.prompt.includes('mainFiles: src/x.js'), 'outs were taken from the state document')
  assert.equal(out.result.items[0].reseats[0].seat, 'implementer-a1')
})

// ── verify: the fix-cycle model ─────────────────────────────────────────────────────────────────

function implCommit(sha, { files = ['src/x.js'], seat = 'implementer', body } = {}) {
  return { sha, subject: `feat(x): implement [${SHORT}]`, body: body === undefined ? `why\n\nSeat: ${seat}` : body, files }
}
function authorCommit(sha) {
  return { sha, subject: `test(x): tests [${SHORT}]`, body: 'why\n\nSeat: test-author', files: ['scripts/tests/x.test.mjs'] }
}

function verifyScenario({ commits, implStage, authorStage, reseats }) {
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStageDef = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: { agent: 'task-orchestrator:implementer' }, output: 'implementer-v1' }
  const testStageDef = { seat: 'test-author', phase: 'work', writes: true, notes: [], dispatch: {}, output: 'test-author-v1', readsExclude: [] }
  const item = itemFixture({ short: SHORT, stages: [plannerStage, implStageDef, testStageDef] })
  const args = planFixture({ items: [item], baseSha: BASE })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const row = {
    id: item.id, short: SHORT, status: 'done', reason: 'ok',
    stages: [
      stageRow('planner', { pre: BASE, post: BASE }),
      stageRow('implementer', implStage),
      stageRow('test-author', authorStage),
    ],
    outputs: { planner: PLANNER_OUT, implementer: IMPL_OUT },
  }
  if (reseats) row.reseats = reseats
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: args.runId, planDocSlug: 'run/x',
    items: [row], refused: [], deferred: [],
  }
  const exists = { [BASE]: true }
  for (const c of commits) exists[c.sha] = true
  const out = verify(doc, result, { exists, commits })
  return { out, row: out.items.find((i) => i.short === SHORT) }
}

// the fix-cycle history: implementer C1, test-author C2, then the implementer-a1 fix commit C3
function fixCycleHistory(c3Opts = {}) {
  return {
    commits: [implCommit(C1), authorCommit(C2), implCommit(C3, { seat: 'implementer-a1', ...c3Opts })],
    implStage: { pre: BASE, post: C3 },
    authorStage: { pre: C1, post: C2 },
  }
}

test('S4: with a ledger pre at the test-author commit, an implementer-a1 commit after the author passes verify', () => {
  const { row, out } = verifyScenario({ ...fixCycleHistory(), reseats: [ledgerEntry('implementer-a1', C2)] })
  assert.deepEqual(row.findings, [])
  assert.equal(row.ok, true)
  assert.equal(out.ok, true)
})

test('S5: a fix commit carrying the base seat trailer "Seat: implementer" is accepted the same way', () => {
  const { row } = verifyScenario({ ...fixCycleHistory({ seat: 'implementer' }), reseats: [ledgerEntry('implementer-a1', C2)] })
  assert.deepEqual(row.findings, [])
  assert.equal(row.ok, true)
})

test('S7: the same commits without a ledger still report the author commit as preceding the implementer', () => {
  const { row, out } = verifyScenario({ ...fixCycleHistory() })
  assert.ok(row.findings.includes(`test-author commit ${sha7(C2)} precedes implementer`), JSON.stringify(row.findings))
  assert.equal(row.ok, false)
  assert.equal(out.ok, false)
})

test('S8: a ledger pre after an out-of-order author commit does not hide it', () => {
  const commits = [authorCommit(C1), implCommit(C2), implCommit(C3, { seat: 'implementer-a1' })]
  const { row } = verifyScenario({
    commits, implStage: { pre: BASE, post: C3 }, authorStage: { pre: C3, post: C1 },
    reseats: [ledgerEntry('implementer-a1', C3)],
  })
  assert.ok(row.findings.includes(`test-author commit ${sha7(C1)} precedes implementer`), JSON.stringify(row.findings))
  assert.equal(row.ok, false)
})

test('S9: a post-boundary fix commit on an undeclared file is still reported unowned', () => {
  const { row } = verifyScenario({
    ...fixCycleHistory({ files: ['src/x.js', 'src/unowned.js'] }),
    reseats: [ledgerEntry('implementer-a1', C2)],
  })
  assert.ok(row.findings.includes('implementer wrote unowned src/unowned.js'), JSON.stringify(row.findings))
  assert.equal(row.findings.some((f) => f.startsWith('test-author commit')), false, 'the ledger removes the ordering finding')
  assert.equal(row.ok, false)
})

test('S9: a post-boundary fix commit without any Seat trailer is still reported', () => {
  const { row } = verifyScenario({
    ...fixCycleHistory({ body: 'why, no trailer here' }),
    reseats: [ledgerEntry('implementer-a1', C2)],
  })
  assert.ok(row.findings.includes(`missing Seat trailer ${sha7(C3)}`), JSON.stringify(row.findings))
  assert.equal(row.ok, false)
})

test('S10: a ledger pre that is neither in the git log nor the plan baseSha is reported', () => {
  const missing = 'f'.repeat(40)
  const { row } = verifyScenario({ ...fixCycleHistory(), reseats: [ledgerEntry('implementer-a1', missing)] })
  assert.ok(row.findings.includes(`reseat implementer-a1 pre ${sha7(missing)} not in run history`), JSON.stringify(row.findings))
  assert.equal(row.ok, false)
})

test('S14: a ledger pre equal to the plan baseSha places the boundary before every commit', () => {
  const history = {
    commits: [authorCommit(C1), implCommit(C2)],
    implStage: { pre: BASE, post: C2 },
    authorStage: { pre: C2, post: C1 },
  }
  const control = verifyScenario({ ...history })
  assert.ok(control.row.findings.includes(`test-author commit ${sha7(C1)} precedes implementer`), 'fixture condition: reported without a ledger')
  const { row } = verifyScenario({ ...history, reseats: [ledgerEntry('implementer-a1', BASE)] })
  assert.equal(row.findings.some((f) => f.includes('precedes implementer')), false, JSON.stringify(row.findings))
  assert.equal(row.findings.some((f) => f.includes('not in run history')), false, JSON.stringify(row.findings))
})

test('S17: with several ledger entries the boundary is the earliest pre in the log, whatever the ledger order', () => {
  const commits = [implCommit(C1), authorCommit(C2), implCommit(C3, { seat: 'implementer-a1' }), implCommit(C4, { seat: 'implementer-a2' })]
  const common = { commits, implStage: { pre: BASE, post: C4 }, authorStage: { pre: C1, post: C2 } }
  const a1 = ledgerEntry('implementer-a1', C2)
  const a2 = ledgerEntry('implementer-a2', C3)
  for (const reseats of [[a1, a2], [a2, a1]]) {
    const { row } = verifyScenario({ ...common, reseats })
    assert.deepEqual(row.findings, [], reseats.map((r) => r.seat).join(','))
    assert.equal(row.ok, true)
  }
})

test('S18: verify output without a ledger is unchanged for a clean implementer-then-author history', () => {
  const { row, out } = verifyScenario({
    commits: [implCommit(C1), authorCommit(C2)],
    implStage: { pre: BASE, post: C1 },
    authorStage: { pre: C1, post: C2 },
  })
  assert.deepEqual(row.findings, [])
  assert.equal(out.ok, true)
  assert.equal(Object.hasOwn(row, 'reseats'), false, 'no ledger, no reseat field added to the verify row')
})

// ── provenance: the ledger feeds extra-seats ────────────────────────────────────────────────────

function provenanceScenario(ledgerA) {
  const mk = (short) => itemFixture({
    short,
    stages: [{ seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: { model: 'sonnet' }, output: 'implementer-v1' }],
    worktree: `/repo/.claude/worktrees/x-${short}`,
  })
  const a = mk('aaaaaaaa')
  const b = mk('bbbbbbbb')
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [a, b], runId: 'r-test-0201' }), meta: { inRunEdges: [] } }
  const row = (it, reseats) => ({
    id: it.id, short: it.short, status: 'done', reason: 'ok', outputs: {},
    stages: [stageRow('implementer')],
    ...(reseats ? { reseats } : {}),
  })
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: 'r-test-0201', planDocSlug: 'run/r-test-0201',
    items: [row(a, ledgerA), row(b)], refused: [], deferred: [],
  }
  return { doc, a, b, result }
}

test('S6: provenance --item with a ledger and no --extra-seats appends extra-seats from the ledger', () => {
  const { doc, a, result } = provenanceScenario([ledgerEntry('implementer-a1', C2, { tokens: 'unknown' })])
  const out = provenance({ core: realCore(), doc, result, method: 'A', turns: 1, item: 'aaaaaaaa' })
  assert.deepEqual(Object.keys(out), [a.id])
  const [l1, l2, ...rest] = out[a.id].split('\n')
  assert.match(l1, /^adapter=claude-workflow /)
  assert.equal(l2, 'extra-seats=implementer-a1:sonnet:unknown')
  assert.equal(rest.length, 0)
  assert.deepEqual(parseExtraSeats(out[a.id]), [{ seat: 'implementer-a1', model: 'sonnet', tokens: 'unknown' }])
})

test('S6: a numeric ledger tokens value is printed as given', () => {
  const { doc, a, result } = provenanceScenario([ledgerEntry('implementer-a1', C2, { tokens: 359000, model: 'opus' })])
  const out = provenance({ core: realCore(), doc, result, method: 'A', turns: 1, item: 'aaaaaaaa' })
  assert.equal(out[a.id].split('\n')[1], 'extra-seats=implementer-a1:opus:359000')
})

test('S6: --item restricts the output to that item; an item without a ledger gets no second line', () => {
  const { doc, b, result } = provenanceScenario([ledgerEntry('implementer-a1', C2)])
  const out = provenance({ core: realCore(), doc, result, method: 'A', turns: 1, item: 'bbbbbbbb' })
  assert.deepEqual(Object.keys(out), [b.id])
  assert.equal(out[b.id].includes('\n'), false)
  assert.equal(out[b.id].includes('extra-seats='), false)
})

test('S15: --extra-seats are merged after the ledger; a seat in both takes the flag model and tokens', () => {
  const { doc, a, result } = provenanceScenario([ledgerEntry('implementer-a1', C2)])
  const extraSeats = [{ seat: 'implementer-a1', model: 'opus', tokens: 5 }, { seat: 'reviewer-a1', model: 'opus', tokens: 7 }]
  const out = provenance({ core: realCore(), doc, result, method: 'A', turns: 1, extraSeats, item: 'aaaaaaaa' })
  assert.equal(out[a.id].split('\n')[1], 'extra-seats=implementer-a1:opus:5,reviewer-a1:opus:7')
})

test('S15: ledger order comes first, then flag-only seats, with the flag override applied in place', () => {
  const ledger = [ledgerEntry('implementer-a1', C2, { tokens: 100 }), ledgerEntry('test-author-a1', C3, { model: 'haiku', tokens: 'unknown' })]
  const { doc, a, result } = provenanceScenario(ledger)
  const extraSeats = [{ seat: 'reviewer-a1', model: 'opus', tokens: 7 }, { seat: 'test-author-a1', model: 'sonnet', tokens: 42 }]
  const out = provenance({ core: realCore(), doc, result, method: 'A', turns: 1, extraSeats, item: 'aaaaaaaa' })
  assert.equal(out[a.id].split('\n')[1], 'extra-seats=implementer-a1:sonnet:100,test-author-a1:sonnet:42,reviewer-a1:opus:7')
})

test('S15: without --item the output is unchanged by a ledger - one line per item, no extra-seats line', () => {
  const { doc, a, b, result } = provenanceScenario([ledgerEntry('implementer-a1', C2)])
  const out = provenance({ core: realCore(), doc, result, method: 'A', turns: 1 })
  assert.deepEqual(Object.keys(out).sort(), [a.id, b.id].sort())
  for (const id of [a.id, b.id]) {
    assert.equal(out[id].includes('\n'), false)
    assert.equal(out[id].includes('extra-seats='), false)
  }
})

test('S15: --extra-seats without --item still throws, ledger or not', () => {
  const { doc, result } = provenanceScenario([ledgerEntry('implementer-a1', C2)])
  assert.throws(
    () => provenance({ core: realCore(), doc, result, method: 'A', turns: 1, extraSeats: [{ seat: 'reviewer-a1', model: 'opus', tokens: 1 }] }),
    /extraSeats requires a single item \(--item\)/
  )
})

function provenanceCli(ledgerA, extra) {
  const { doc, a, b, result } = provenanceScenario(ledgerA)
  const dir = mkdtempSync(join(tmpdir(), 'reseat-prov-'))
  const planPath = writeJson(dir, 'plan.json', doc)
  const resultPath = writeJson(dir, 'result.json', result)
  const res = runCli(['provenance', '--plan', planPath, '--result', resultPath, '--method', 'A', '--turns', '1', ...extra])
  assert.equal(res.status, 0, res.stderr)
  return { lines: JSON.parse(res.stdout), a, b }
}

test('S6 (CLI): provenance --item alone restricts the output to that item and reads the ledger', () => {
  const { lines, a } = provenanceCli([ledgerEntry('implementer-a1', C2)], ['--item', 'aaaaaaaa'])
  assert.deepEqual(Object.keys(lines), [a.id])
  assert.equal(lines[a.id].split('\n')[1], 'extra-seats=implementer-a1:sonnet:unknown')
})

test('S15 (CLI): provenance --item with --extra-seats merges the flag seats after the ledger', () => {
  const { lines, a } = provenanceCli([ledgerEntry('implementer-a1', C2)], ['--item', 'aaaaaaaa', '--extra-seats', 'implementer-a1:opus:5,reviewer-a1:opus:7'])
  assert.deepEqual(Object.keys(lines), [a.id])
  assert.equal(lines[a.id].split('\n')[1], 'extra-seats=implementer-a1:opus:5,reviewer-a1:opus:7')
})

test('S15 (CLI): provenance without --item lists every item and never adds an extra-seats line', () => {
  const { lines, a, b } = provenanceCli([ledgerEntry('implementer-a1', C2)], [])
  assert.deepEqual(Object.keys(lines).sort(), [a.id, b.id].sort())
  for (const id of [a.id, b.id]) assert.equal(lines[id].includes('extra-seats='), false)
})

// ── end to end: reseat CLI -> ledger -> verify CLI against a real git history ───────────────────

test('E2E (CLI, real git): reseat records HEAD as pre, then verify accepts the implementer-a1 fix after the author and rejects it without the ledger', () => {
  const repo = makeTempGitRepo()
  const base = makeCommit(repo, 'README.md', '# base\n', 'chore: base commit')
  const c1 = makeCommit(repo, 'src/x.js', 'module.exports = 1;\n', `feat(x): implement [${SHORT}]`, 'why\n\nSeat: implementer')
  const c2 = makeCommit(repo, 'scripts/tests/x.test.mjs', '// tests\n', `test(x): tests [${SHORT}]`, 'why\n\nSeat: test-author')

  const { doc } = makeDoc({ worktree: repo, baseSha: base })
  const dir = mkdtempSync(join(tmpdir(), 'reseat-e2e-'))
  const planPath = writeJson(dir, 'plan.json', doc)
  const row0 = (extra) => ({
    id: doc.args.items[0].id, short: SHORT, status: 'done', reason: 'ok',
    stages: [
      stageRow('planner', { pre: base, post: base }),
      stageRow('implementer', { pre: base, post: c1 }),
      stageRow('declarations-extractor'),
      stageRow('test-author', { pre: c1, post: c2 }),
    ],
    outputs: makeOutputs(),
    ...extra,
  })
  const mkResult = (rowExtra) => ({
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [row0(rowExtra)], refused: [], deferred: [],
  })
  const resultPath = writeJson(dir, 'result.json', mkResult())

  const reseatRes = runCli(cliBase(planPath, resultPath, 'implementer-a1'))
  assert.equal(reseatRes.status, 0, reseatRes.stderr)
  const reseated = JSON.parse(reseatRes.stdout)
  assert.equal(reseated.result.items[0].reseats[0].pre, c2, 'pre is the worktree HEAD at dispatch time')

  // the fix cycle: the implementer-a1 seat commits after the author
  const c3 = makeCommit(repo, 'src/x.js', 'module.exports = 2;\n', `fix(x): address findings [${SHORT}]`, 'why\n\nSeat: implementer-a1')
  const withLedger = clone(reseated.result)
  withLedger.items[0].stages.find((s) => s.seat === 'implementer').commits.post = c3
  const withLedgerPath = writeJson(dir, 'result-ledger.json', withLedger)
  const okRes = runCli(['verify', '--plan', planPath, '--result', withLedgerPath])
  assert.equal(okRes.status, 0, okRes.stdout)
  const okRow = JSON.parse(okRes.stdout).items.find((i) => i.short === SHORT)
  assert.deepEqual(okRow.findings, [])

  const noLedger = clone(withLedger)
  delete noLedger.items[0].reseats
  const noLedgerPath = writeJson(dir, 'result-noledger.json', noLedger)
  const badRes = runCli(['verify', '--plan', planPath, '--result', noLedgerPath])
  assert.equal(badRes.status, 3)
  const badRow = JSON.parse(badRes.stdout).items.find((i) => i.short === SHORT)
  assert.ok(badRow.findings.includes(`test-author commit ${sha7(c2)} precedes implementer`), JSON.stringify(badRow.findings))
})

// ── A1: exit-2 paths (item 4bd560d0 fix cycle A1) ───────────────────────────────────────────────

test('A1-a (CLI): without --pre, an unresolvable item worktree HEAD exits 2 and writes no ledger row', () => {
  const missing = join(mkdtempSync(join(tmpdir(), 'reseat-a1-')), 'no-such-worktree')
  const { doc } = makeDoc({ worktree: missing })
  const dir = mkdtempSync(join(tmpdir(), 'reseat-cli-'))
  const planPath = writeJson(dir, 'plan.json', doc)
  const resultPath = writeJson(dir, 'result.json', makeResult(doc))
  const res = runCli(cliBase(planPath, resultPath, 'implementer-a1'))
  assert.equal(res.status, 2, `exit ${res.status}, stdout ${res.stdout}, stderr ${res.stderr}`)
  assert.ok(res.stderr.includes('reseat: cannot resolve pre (git rev-parse HEAD failed in '), res.stderr)
  assert.ok(res.stderr.includes('): pass --pre <sha>'), res.stderr)
  assert.equal(res.stdout.includes('"reseats"'), false, 'no ledger row is emitted')
})

test('A1-b (CLI): an unreadable --declarations file exits 2 naming the path', () => {
  const { doc } = makeDoc()
  const dir = mkdtempSync(join(tmpdir(), 'reseat-cli-'))
  const planPath = writeJson(dir, 'plan.json', doc)
  const resultPath = writeJson(dir, 'result.json', makeResult(doc))
  const declPath = join(dir, 'no-such-decl.txt')
  const res = runCli(cliBase(planPath, resultPath, 'test-author-a1', ['--declarations', declPath, '--pre', C2]))
  assert.equal(res.status, 2, `exit ${res.status}, stdout ${res.stdout}, stderr ${res.stderr}`)
  assert.equal(JSON.parse(res.stderr).detail, `--declarations ${declPath} is not readable`)
})

test('A1-c: declarations given for a test-author base whose item has no declarations stage throw the stage-specific message', () => {
  const { doc } = makeDoc({ list: stageList({ withExtractor: false }) })
  const outs = { planner: PLANNER_OUT, implementer: IMPL_OUT }
  assert.throws(
    () => callReseat(realCore(), doc, makeResult(doc, outs), SHORT, 'test-author-a1', { pre: C2, declarations: 'export function f(x)' }),
    (err) => err.message === 'invalid args: --declarations is only valid for a test-author base with a declarations stage'
  )
})

test('A1-d (CLI): an unreadable --plan exits 2 with the plan-specific message', () => {
  const { doc } = makeDoc()
  const dir = mkdtempSync(join(tmpdir(), 'reseat-cli-'))
  const planPath = join(dir, 'no-such-plan.json')
  const resultPath = writeJson(dir, 'result.json', makeResult(doc))
  const res = runCli(cliBase(planPath, resultPath, 'implementer-a1', ['--pre', C2]))
  assert.equal(res.status, 2, `exit ${res.status}, stdout ${res.stdout}, stderr ${res.stderr}`)
  assert.equal(JSON.parse(res.stderr).detail, `--plan ${planPath} could not be read or parsed as a plan`)
})
