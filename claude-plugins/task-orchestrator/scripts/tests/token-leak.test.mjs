// Token-leak guard for item cac1bffa (task-scope D2, AC12): a seat token reaches its seat through a file,
// never through prompt text, a log line, the run state, the result or any run-ops output.
//
// BLIND test author: no production source under workflows/, scripts/*.mjs or scripts/lib/ was opened by
// any tool. The suite seeds distinct high-entropy tokens into the token files the plan names
// (<scratchDir>/tokens/<short>.<seat>.token, task-scope D2) and then renders every surface the
// coordinator produces - Method A prompts and log lines, Method B prompts, state and result, the
// review prompt, the review-wave lane prompts, the reseat prompt and every run-ops phase - asserting
// that no seeded token (nor its trimmed form) appears anywhere and that no `seatToken` key is emitted.
//
// Red-proof note (test-manifest): inlining the token file's content into the SEAT TOKEN prompt part turns
// the prompt-surface assertions red, because the seeded files exist at exactly the paths the prompts name;
// the positive controls below prove the prompts do name those paths, so the check cannot pass vacuously.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadCore, fakeAgent, fakeParallel } from './workflow-harness.mjs'
import { loadCoreNamed, autoAgent } from './workflow-harness-ext.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import { initState, next, prompt, stageResult, resultFromState, reviewPrompt } from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..')
const CLI = join(PLUGIN_ROOT, 'scripts', 'run-planner.mjs')
const IMPLEMENT_WAVE = join(PLUGIN_ROOT, 'workflows', 'implement-wave.js')
const REVIEW_WAVE = join(PLUGIN_ROOT, 'workflows', 'review-wave.js')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')
const NOW = '2026-09-28T12:00:00Z'
const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'
const A = 'aaaaaaaa-1111-4111-8111-111111111111'
const B = 'bbbbbbbb-2222-4222-8222-222222222222'
const SHORTS = ['aaaaaaaa', 'bbbbbbbb']

function runCli(args, { input } = {}) {
  return spawnSync(process.execPath, [CLI, ...args], {
    encoding: 'utf8',
    input: input === undefined ? undefined : typeof input === 'string' ? input : JSON.stringify(input),
  })
}
function writeJson(dir, name, value) {
  const p = join(dir, name)
  writeFileSync(p, typeof value === 'string' ? value : JSON.stringify(value))
  return p
}
function hasKeyDeep(value, key) {
  if (Array.isArray(value)) return value.some((v) => hasKeyDeep(v, key))
  if (value && typeof value === 'object') {
    return Object.keys(value).includes(key) || Object.values(value).some((v) => hasKeyDeep(v, key))
  }
  return false
}
function canonPath(p) {
  return String(p).split(String.fromCharCode(92)).join('/').toLowerCase()
}

// ---- the seeded world --------------------------------------------------------------------------

const CRLF = String.fromCharCode(13) + String.fromCharCode(10)

function seedWorld() {
  const dir = mkdtempSync(join(tmpdir(), 'token-leak-'))
  const snap = JSON.parse(readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8'))
  const planRes = runCli(['plan', '--now', NOW, '--scratchpad', join(dir, 'scratchpad')], { input: snap })
  assert.equal(planRes.status, 0, planRes.stderr)
  const unbound = writeJson(dir, 'plan.json', JSON.parse(planRes.stdout))
  const bindRes = runCli(['bind-run', '--in', unbound, '--server-run', SERVER_RUN])
  assert.equal(bindRes.status, 0, bindRes.stderr)
  const bound = JSON.parse(bindRes.stdout)
  const boundPath = writeJson(dir, 'bound.json', bound)

  const tokensDir = join(bound.args.project.scratchDir, 'tokens')
  mkdirSync(tokensDir, { recursive: true })
  const tokens = []
  const tokenFiles = {}
  let crlfDone = false
  for (const short of SHORTS) {
    for (const seat of ['planner', 'implementer', 'implementer-a1', 'reviewer']) {
      const value = `LEAKPROBE${randomBytes(18).toString('hex')}`
      const file = join(tokensDir, `${short}.${seat}.token`)
      // one file ends CRLF (a Windows editor) - the trimmed form must not leak either
      const body = !crlfDone ? value + CRLF : value + String.fromCharCode(10)
      crlfDone = true
      writeFileSync(file, body)
      tokens.push(value)
      tokenFiles[`${short}.${seat}`] = file
    }
  }
  return { dir, bound, boundPath, tokens, tokenFiles }
}

function findLeaks(surfaces, tokens) {
  const leaks = []
  for (const [name, text] of Object.entries(surfaces)) {
    for (const t of tokens) {
      if (text.includes(t)) leaks.push(`${name} contains ${t.slice(0, 14)}...`)
    }
  }
  return leaks
}

function env(output, extra = {}) {
  return {
    status: 'done', reason: 'ok', notes: [], commits: { pre: '', post: '' }, files: [],
    modelReported: 'fake', entry: { applied: true, newRole: 'work' }, output, ...extra,
  }
}
function scriptedEnvelope(bound, short, seat) {
  const stage = bound.args.items.find((it) => it.short === short).stages.find((s) => s.seat === seat)
  const notes = stage.notes.map((key) => ({ key }))
  if (seat === 'planner') {
    return env({
      proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
      decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
      mainFiles: [`src/${short}.js`], docFiles: [], testFiles: [], existingTestEdits: [], redProofShape: 'none',
    }, { notes })
  }
  return env({
    mainFilesChanged: [], docFilesChanged: [], verify: [], failingExistingTests: [],
    preExistingFailures: [], publicSurface: 'none', deviations: 'none',
  }, { notes })
}

// ---- the detector must be able to fail ------------------------------------------------------------

test('detector self-check: findLeaks flags a seeded token in a surface and passes a clean one', () => {
  const token = `LEAKPROBE${randomBytes(18).toString('hex')}`
  assert.equal(findLeaks({ dirty: `before ${token} after`, clean: 'nothing here' }, [token]).length, 1)
  assert.deepEqual(findLeaks({ clean: 'nothing here' }, [token]), [])
})

test('seeding: token files exist at the paths the plan implies, with distinct values, one CRLF-terminated', () => {
  const w = seedWorld()
  assert.equal(new Set(w.tokens).size, w.tokens.length)
  assert.equal(Object.keys(w.tokenFiles).length, w.tokens.length)
  const crlfFiles = Object.values(w.tokenFiles).filter((f) => readFileSync(f, 'utf8').endsWith(CRLF))
  assert.equal(crlfFiles.length, 1)
})

// ---- Method A -----------------------------------------------------------------------------------

test('S12 (Method A): no seeded token in any seat prompt, agent option, log line or the result; the prompts name the token file paths', async () => {
  const w = seedWorld()
  const core = loadCore(IMPLEMENT_WAVE)
  const norm = core.normalizeArgs(w.bound.args)
  assert.equal(norm.ok, true, JSON.stringify(norm))
  const script = {}
  for (const short of SHORTS) {
    for (const seat of ['planner', 'implementer']) script[`${seat}:${short}`] = scriptedEnvelope(w.bound, short, seat)
  }
  const { agent, calls } = fakeAgent(script)
  const logLines = []
  const result = await core.runPlan(norm.plan, { agent, parallel: fakeParallel, log: (m) => logLines.push(String(m)) })

  assert.ok(calls.length >= 4, `expected at least four seat calls, got ${calls.length}`)
  const surfaces = { result: JSON.stringify(result), log: logLines.join('\n') }
  calls.forEach((c, i) => {
    surfaces[`prompt[${c.label}]`] = c.prompt
    surfaces[`opts[${i}]`] = JSON.stringify(c.opts)
  })
  assert.deepEqual(findLeaks(surfaces, w.tokens), [])
  assert.equal(hasKeyDeep(result, 'seatToken'), false, 'the result carries no seatToken key')
  assert.equal(logLines.some((l) => /seatToken["']?\s*[:=]\s*["']?[A-Za-z0-9]/.test(l)), false, 'no log line assigns a seatToken value')

  // positive control: the seat prompts do name their token file (the fixture can fail)
  for (const c of calls) {
    const [seat, short] = c.label.split(':')
    assert.ok(canonPath(c.prompt).includes(canonPath(w.tokenFiles[`${short}.${seat}`])), `${c.label} prompt must name its token file`)
  }
})

// ---- Method B -----------------------------------------------------------------------------------

test('S12 (Method B): no seeded token in any prompt, the state after every stage, the result, the review prompt or a reseat prompt', () => {
  const w = seedWorld()
  const core = loadWaveCore()
  let state = initState(w.bound, 'B')
  const surfaces = { state0: JSON.stringify(state) }
  let prompts = 0
  for (let i = 0; i < 20; i += 1) {
    const r = next(core, w.bound, state)
    surfaces[`next[${i}]`] = JSON.stringify(r)
    if (r.complete) break
    for (const d of r.dispatch) {
      const p = prompt(core, w.bound, state, d.item, d.seat)
      surfaces[`prompt[${d.seat}:${d.item}]`] = p
      prompts += 1
      assert.ok(canonPath(p).includes(canonPath(w.tokenFiles[`${d.item}.${d.seat}`])), `${d.seat}:${d.item} prompt must name its token file`)
      const res = stageResult(core, w.bound, state, d.item, d.seat, JSON.stringify(scriptedEnvelope(w.bound, d.item, d.seat)))
      assert.equal(res.status, 'done', `${d.seat}:${d.item} -> ${res.status}/${res.reason}`)
      state = res.state
      surfaces[`state[${d.seat}:${d.item}]`] = JSON.stringify(state)
    }
  }
  assert.ok(prompts >= 4, `expected four seat prompts, got ${prompts}`)
  const result = resultFromState(core, w.bound, state)
  surfaces.result = JSON.stringify(result)
  for (const short of SHORTS) surfaces[`review[${short}]`] = reviewPrompt(core, w.bound, short, result)

  // reseat prompt (out-of-run fix seat) through the CLI
  const resultPath = writeJson(w.dir, 'result.json', result)
  const reseat = runCli(['reseat', '--plan', w.boundPath, '--run', w.bound.args.runId, '--result', resultPath, '--item', 'aaaaaaaa', '--seat', 'implementer-a1', '--pre', 'e77c3e95', '--assignment', '3d594650-3436-4a25-b9f8-9d3c9f3f3f11'])
  assert.equal(reseat.status, 0, reseat.stderr)
  surfaces.reseat = reseat.stdout

  assert.deepEqual(findLeaks(surfaces, w.tokens), [])
  assert.equal(hasKeyDeep(JSON.parse(surfaces.state0), 'seatToken'), false)
  assert.equal(hasKeyDeep(state, 'seatToken'), false, 'the run state carries no seatToken key')
  assert.equal(hasKeyDeep(result, 'seatToken'), false)
})

// ---- run-ops ------------------------------------------------------------------------------------

test('S12 (run-ops): no phase output carries a seeded token or a seatToken key', () => {
  const w = seedWorld()
  const usage = writeJson(w.dir, 'usage.json', { tokens: 1200, costUsd: 0.5 })
  const seats = writeJson(w.dir, 'seats.json', {
    runId: SERVER_RUN,
    seats: [
      { assignmentId: 'a1', itemId: A, seat: 'planner', attempt: 1, status: 'active', assignedAt: '2026-10-16T10:00:00Z' },
      { assignmentId: 'a2', itemId: B, seat: 'implementer', attempt: 1, status: 'active', assignedAt: '2026-10-16T10:00:00Z' },
    ],
  })
  const result = writeJson(w.dir, 'result.json', {
    contract: 'implement-wave/result-v1', started: true, runId: w.bound.args.runId, serverRunId: SERVER_RUN,
    items: [], refused: [], deferred: [],
  })
  const invocations = {
    open: ['--phase', 'open'],
    assign: ['--phase', 'assign'],
    'assign-one': ['--phase', 'assign', '--item', 'aaaaaaaa', '--seat', 'planner'],
    heartbeat: ['--phase', 'heartbeat', '--usage', usage],
    'end-seats': ['--phase', 'end-seats', '--seats', seats, '--result', result],
    close: ['--phase', 'close', '--result', result],
    'close-cancelled': ['--phase', 'close', '--reason', 'cancelled'],
  }
  const surfaces = {}
  for (const [name, args] of Object.entries(invocations)) {
    const res = runCli(['run-ops', '--plan', w.boundPath, ...args])
    assert.equal(res.status, 0, `${name}: ${res.stderr}`)
    surfaces[name] = res.stdout
    assert.equal(hasKeyDeep(JSON.parse(res.stdout), 'seatToken'), false, `${name} emits a seatToken key`)
  }
  assert.deepEqual(findLeaks(surfaces, w.tokens), [])
  // positive control: the token-file paths ARE listed (paths are fine; contents are not)
  assert.ok(JSON.parse(surfaces.assign).tokenFiles.length === 4)
})

// ---- review-wave lanes ----------------------------------------------------------------------------

test('S12 (review-wave): lane prompts, log lines and the result carry no seeded token; the prompt names the lane token file', async () => {
  const w = seedWorld()
  const core = loadCoreNamed(REVIEW_WAVE, ['normalizeArgs', 'lanePrompt', 'runReview'])
  const tokenFile = w.tokenFiles['aaaaaaaa.reviewer']
  const raw = {
    contract: 'review-wave/args-v1', runId: w.bound.args.runId, rootId: 'root-test-0001', baseSha: 'e77c3e95',
    worktreeMode: 'shared', entryMode: 'pre-entered', serverRunId: SERVER_RUN,
    capabilities: { features: ['seats', 'dispatchBySeat', 'rules'], phase0Hooks: true },
    deferred: [],
    items: [{
      id: `item-aaaaaaaa`, short: 'aaaaaaaa', type: 'test-item', configFingerprint: 'fp-1', traits: [], worktree: '/tmp/wt-aaaaaaaa',
      stages: [{
        seat: 'reviewer', lane: 'reviewer.review-quality', phase: 'review', notes: ['review-checklist'], writes: false,
        protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1', tokenFile,
      }],
      review: {
        ownedFiles: { implementer: ['a.js'], testAuthor: [], docs: [] },
        commits: { implementer: ['aaaaaaa1'], testAuthor: [], fixtureRepairs: [] },
        requiredReviewNotes: ['review-checklist'],
      },
    }],
  }
  const norm = core.normalizeArgs(raw)
  assert.equal(norm.ok, true, norm.reason)
  const item = norm.plan.items[0]
  const lane = core.lanePrompt(norm.plan, item, item.stages[0], {})
  assert.ok(canonPath(lane).includes(canonPath(tokenFile)), 'positive control: the lane prompt names its token file')

  const logLines = []
  const { agent, calls } = autoAgent(async () => ({
    status: 'done', reason: 'ok', notes: [{ key: 'review-checklist' }], commits: { pre: 'p', post: 'q' }, files: [], modelReported: 'fake',
    entry: { alreadyInPhase: true, previousRole: 'review' },
    output: { verdict: 'pass', findings: [], independence: { mode: 'independent', violations: [], evidence: 'ok' }, notesFilled: ['review-checklist'] },
  }))
  const result = await core.runReview(norm.plan, { agent, parallel: fakeParallel, log: (m) => logLines.push(String(m)) })
  const surfaces = { lane, result: JSON.stringify(result), log: logLines.join('\n') }
  calls.forEach((c) => { surfaces[`prompt[${c.label}]`] = c.prompt })
  assert.ok(calls.length >= 1)
  assert.deepEqual(findLeaks(surfaces, w.tokens), [])
  assert.equal(hasKeyDeep(result, 'seatToken'), false)
})
