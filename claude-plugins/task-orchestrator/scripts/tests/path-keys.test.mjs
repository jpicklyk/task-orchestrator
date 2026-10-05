// Root-relative lock keys, overlap-aware locks, quoted review pathspecs (item c7de82ed).
//
// BLIND test author: written from the item's frozen queue notes `diagnosis` (decisions D2-D6) and
// `test-plan` (S1-S18 + probes), the orchestrator-supplied declarations file (doc comments and
// signatures only), git's documented pathspec magic (gitglossary: ':(literal)' / ':(glob)'), and
// the existing test harnesses. No production source under workflows/ or scripts/*.mjs or
// scripts/lib/ was opened by any tool. Every expected value below is a literal derived from those
// sources, never from running the code under test.
//
// Fixture constants (test-plan header): WT = /repo/.claude/worktrees/feat-x, repoRoot = /repo,
// shared mode. "serialize" = item B's implementer agent call is not made while item A's
// implementer call is still pending (B's impl waits on A's lock).
//
// Loading notes: the core is reached through the harness loadCore; run-exec-lib.mjs is imported as
// a namespace so that a missing relativizePath export (pre-fix) fails only S17, not the file.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { loadCore, fakeAgent, fakeParallel, planFixture, itemFixture, stages } from './workflow-harness.mjs'
import * as rel from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..')
const SCRIPT_PATH = join(PLUGIN_ROOT, 'workflows', 'implement-wave.js')
const RUN_PLANNER = join(PLUGIN_ROOT, 'scripts', 'run-planner.mjs')
const PLANNER_FIXTURES = join(HERE, 'fixtures', 'run-planner')

const WT = '/repo/.claude/worktrees/feat-x'
const REPO = '/repo'
const BASE = 'e77c3e95'

// ── builders (valid by construction against the planner-v1 / implementer-v1 required fields) ──

function plannerOutput(over = {}) {
  return {
    proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
    decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
    mainFiles: [], docFiles: [], testFiles: [], existingTestEdits: [], redProofShape: 'none',
    ...over,
  }
}

function implementerOutput() {
  return {
    mainFilesChanged: [], docFilesChanged: [], verify: [], failingExistingTests: [],
    preExistingFailures: [], publicSurface: 'none', deviations: 'none',
  }
}

function plannerEnvelope(over = {}) {
  return {
    status: 'done', reason: 'ok', notes: [], commits: { pre: '', post: '' }, files: [],
    modelReported: 'fake', entry: { applied: true, newRole: 'work' }, output: plannerOutput(over),
  }
}

function envelope(output, over = {}) {
  return { status: 'done', reason: 'ok', notes: [], commits: { pre: 'p1', post: 'p2' }, files: [], modelReported: 'sonnet', output, ...over }
}

async function flush(ticks = 500) {
  for (let i = 0; i < ticks; i++) await Promise.resolve()
}

async function waitUntil(pred, label, maxTicks = 500) {
  for (let i = 0; i < maxTicks; i++) {
    if (pred()) return
    await Promise.resolve()
  }
  throw new Error(`waitUntil: timed out waiting for ${label}`)
}

/**
 * Drives runPlan over two shared-mode items (A first in args order, both on worktree WT) whose
 * planners report filesA / filesB as mainFiles. Releases A's planner, waits for A's implementer to
 * be pending, then releases B's planner and flushes 500 microtask ticks. Returns whether B's
 * implementer agent call had been made while A's was still pending, then drains the run.
 */
async function bImplStartedWhileAHolds(filesA, filesB, { planOver = {}, wtA = WT, wtB = WT } = {}) {
  const core = loadCore(SCRIPT_PATH)
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike(), worktree: wtA })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike(), worktree: wtB })
  const plan = planFixture({ items: [A, B], worktreeMode: 'shared', repoRoot: REPO, ...planOver })
  const script = {
    'planner:aaaaaaaa': plannerEnvelope({ mainFiles: filesA }),
    'planner:bbbbbbbb': plannerEnvelope({ mainFiles: filesB }),
  }
  const { agent, calls, release, pending } = fakeAgent(script, { manual: true })
  const run = core.runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })

  await waitUntil(() => pending().includes('planner:aaaaaaaa') && pending().includes('planner:bbbbbbbb'), 'both planners pending')
  release('planner:aaaaaaaa')
  await waitUntil(() => pending().includes('implementer:aaaaaaaa'), 'implementer:A pending')
  release('planner:bbbbbbbb')
  await waitUntil(() => calls.find((c) => c.label === 'planner:bbbbbbbb').t1 !== null, 'planner:B resolved')
  await flush()
  const started = calls.some((c) => c.label === 'implementer:bbbbbbbb')
  const aStillPending = pending().includes('implementer:aaaaaaaa')

  // drain: release whatever is pending until the run settles
  release('implementer:aaaaaaaa')
  await waitUntil(() => pending().includes('implementer:bbbbbbbb'), 'implementer:B pending after A released')
  release('implementer:bbbbbbbb')
  const result = await run
  assert.equal(result.started, true)
  assert.ok(aStillPending, 'precondition: A\'s implementer was still pending when B was checked')
  return started
}

async function assertSerialize(filesA, filesB, opts) {
  const started = await bImplStartedWhileAHolds(filesA, filesB, opts)
  assert.equal(started, false, `B's implementer must wait for A's lock: A=${JSON.stringify(filesA)} B=${JSON.stringify(filesB)}`)
}

async function assertConcurrent(filesA, filesB, opts) {
  const started = await bImplStartedWhileAHolds(filesA, filesB, opts)
  assert.equal(started, true, `B's implementer must run concurrently with A's: A=${JSON.stringify(filesA)} B=${JSON.stringify(filesB)}`)
}

// ── S1-S7: runPlan lock contention (D2, D3) ──────────────────────────────────────────────────────

test('S1: an absolute worktree-rooted declaration and the same repo-relative path serialize (D3)', async () => {
  await assertSerialize([`${WT}/src/x.js`], ['src/x.js'])
})

test('S2: a main-checkout absolute path (plan.repoRoot=/repo) and the repo-relative path serialize (D2)', async () => {
  await assertSerialize([`${REPO}/src/x.js`], ['src/x.js'])
})

test('S3: a directory declaration (src/) and a file under it serialize (D3 segment-ancestor)', async () => {
  await assertSerialize(['src/'], ['src/x.js'])
})

test('S4: a glob and a file it covers serialize; src/** and src/sub/*.kt serialize (D3 literal bases)', async () => {
  await assertSerialize(['src/*.js'], ['src/x.js'])
  await assertSerialize(['src/**'], ['src/sub/*.kt'])
})

test('S5 (guard): disjoint files and disjoint glob bases run concurrently (D3 conservative but not total)', async () => {
  await assertConcurrent(['src/a.js'], ['test/b.js'])
  await assertConcurrent(['src/*.js'], ['docs/*.md'])
})

test('S6: an item with empty mainFiles/docFiles (whole-worktree key) serializes against a file key (D3/D4a)', async () => {
  await assertSerialize([], ['src/x.js'])
})

test('S7: an unrooted absolute path claims the whole tree and serializes (D3)', async () => {
  await assertSerialize(['/elsewhere/x.js'], ['src/x.js'])
})

test('S7: a path with a ".." segment claims the whole tree and serializes (D3)', async () => {
  await assertSerialize(['src/../src/x.js'], ['src/x.js'])
})

// ── S8: lockKeysConflict truth table (NEW-SURFACE, D3) ───────────────────────────────────────────

test('S8: lockKeysConflict rows from the test-plan, checked in both argument orders (D3)', () => {
  const core = loadCore(SCRIPT_PATH)
  const rows = [
    ['file:src', 'file:src/x.js', true],
    ['file:src/a.js', 'file:src/a.js.bak', false],
    ['file:Src/X.js', 'file:src/x.js', true],
    ['file:*.md', 'file:any/x', true],
    ['worktree:/wt', 'file:a', true],
    ['res:db', 'res:db', true],
    ['res:db', 'res:db2', false],
    ['res:src', 'file:src', false],
  ]
  for (const [a, b, want] of rows) {
    assert.equal(core.lockKeysConflict(a, b), want, `(${a}, ${b})`)
    assert.equal(core.lockKeysConflict(b, a), want, `(${b}, ${a})`)
  }
})

test('S8 probe: further D3 rows — glob bases, segment boundaries, worktree equality', () => {
  const core = loadCore(SCRIPT_PATH)
  const rows = [
    ['file:src/*.js', 'file:src/a.kt', true], // D3: "src/*.js vs src/a.kt contends"
    ['file:src/*.js', 'file:docs/*.md', false], // bases src vs docs
    ['file:src', 'file:srcx/a.js', false], // segment-ancestor, not string prefix
    ['file:src/x.js', 'file:src/x.js', true],
    ['worktree:/wt', 'worktree:/wt', true], // equal worktree keys
    ['worktree:/wt', 'worktree:/WT', false], // orchestrator fact: exact, case-sensitive
    ['worktree:/wt', 'worktree:/wt2', false],
  ]
  for (const [a, b, want] of rows) {
    assert.equal(core.lockKeysConflict(a, b), want, `(${a}, ${b})`)
    assert.equal(core.lockKeysConflict(b, a), want, `(${b}, ${a})`)
  }
})

// ── S9: Method B next() takes the same predicate ────────────────────────────────────────────────

test('S9: next() dispatches only A when A declares src/ and B declares src/x.js; B waits on a lock', () => {
  const core = loadCore(SCRIPT_PATH)
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike(), worktree: WT })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike(), worktree: WT })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A, B], worktreeMode: 'shared', repoRoot: REPO }), meta: {} }
  const state = rel.initState(doc, 'B')
  const outA = plannerOutput({ mainFiles: ['src/'] })
  const outB = plannerOutput({ mainFiles: ['src/x.js'] })
  state.stages['aaaaaaaa:planner'] = envelope(outA)
  state.outs['aaaaaaaa:planner'] = outA
  state.stages['bbbbbbbb:planner'] = envelope(outB)
  state.outs['bbbbbbbb:planner'] = outB

  const r = rel.next(core, doc, state)
  assert.deepEqual(r.dispatch.map((d) => `${d.item}:${d.seat}`), ['aaaaaaaa:implementer'])
  const w = r.waiting.find((x) => x.item === 'bbbbbbbb')
  assert.ok(w, `expected B in waiting, got ${JSON.stringify(r.waiting)}`)
  assert.ok(w.on.startsWith('lock '), `waiting.on must start with "lock ", got ${w.on}`)
})

// ── S10: per-item overlapDeferral relativizes each entry against its own roots (D4) ─────────────

test('S10: overlapDeferral (per-item) defers when the same repo file is declared under two different worktrees; disjoint -> null (D4)', () => {
  const core = loadCore(SCRIPT_PATH)
  const wtA = '/repo/.claude/worktrees/a'
  const wtB = '/repo/.claude/worktrees/b'
  const mine = { short: 'b', output: plannerOutput({ mainFiles: [`${wtB}/src/x.js`] }), roots: [wtB, REPO] }
  const higher = [{ short: 'a', output: plannerOutput({ mainFiles: [`${wtA}/src/x.js`] }), roots: [wtA, REPO] }]
  assert.deepEqual(core.overlapDeferral(mine, higher, 'per-item'), { reason: 'overlap a' })

  const disjoint = [{ short: 'a', output: plannerOutput({ mainFiles: [`${wtA}/src/other.js`] }), roots: [wtA, REPO] }]
  assert.equal(core.overlapDeferral(mine, disjoint, 'per-item'), null)
})

// ── S11-S13: reviewPrompt pathspecs (D5, gitglossary pathspec magic) ────────────────────────────

function reviewText(plannerOver, { repoRoot = REPO } = {}) {
  const core = loadCore(SCRIPT_PATH)
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage, implStage], worktree: WT })
  const args = planFixture({ items: [item], runId: 'r-test-0c7d', baseSha: BASE, repoRoot })
  const doc = { contract: 'run-wave/plan-doc-v1', args, meta: {} }
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: args.runId, planDocSlug: `run/${args.runId}`,
    items: [{ id: item.id, short: 'aaaaaaaa', status: 'done', reason: 'ok', stages: [], outputs: { planner: plannerOutput(plannerOver) } }],
    refused: [], deferred: [],
  }
  return rel.reviewPrompt(core, doc, 'aaaaaaaa', result)
}

function diffLine(text) {
  const lines = text.split('\n').filter((l) => l.includes(`git -C ${WT} diff ${BASE}..HEAD`))
  assert.equal(lines.length, 1, `expected exactly one diff line, got ${JSON.stringify(lines)}\n${text}`)
  return lines[0]
}

function assertPathspec(text, specs) {
  const line = diffLine(text)
  const expected = `git -C ${WT} diff ${BASE}..HEAD -- ${specs.join(' ')}`
  const at = line.indexOf(expected)
  assert.ok(at >= 0, `diff line must contain\n  ${expected}\ngot\n  ${line}`)
  const rest = line.slice(at + expected.length)
  assert.ok(!rest.includes(':('), `no further pathspec entries may follow, got rest ${JSON.stringify(rest)}`)
}

test('S11: reviewPrompt diff covers main+docs+tests+existing edits, relativized, as quoted :(literal)/:(glob) pathspecs in order (D5)', () => {
  const text = reviewText({
    mainFiles: [`${REPO}/src/x.js`, 'src/*.js'],
    docFiles: ['docs/a b.md'],
    testFiles: ['t/x.test.mjs'],
    existingTestEdits: [{ file: 't/old.test.mjs' }],
  })
  assertPathspec(text, [
    '":(literal)src/x.js"', '":(glob)src/*.js"', '":(literal)docs/a b.md"',
    '":(literal)t/x.test.mjs"', '":(literal)t/old.test.mjs"',
  ])
})

test('S12: an unrooted entry is dropped from the pathspec and named on an UNROOTED line (D5)', () => {
  const text = reviewText({ mainFiles: ['/elsewhere/x.js', 'src/a.js'] })
  assertPathspec(text, ['":(literal)src/a.js"'])
  assert.ok(!diffLine(text).includes('/elsewhere/x.js'), 'the unrooted path must not reach the diff command')
  const unrooted = text.split('\n').find((l) => l.includes('UNROOTED (not in pathspec): '))
  assert.ok(unrooted, `expected an UNROOTED line\n${text}`)
  assert.ok(unrooted.includes('/elsewhere/x.js'), unrooted)
})

test('S12: when every entry is unrooted, the diff is bare (no " -- ") (D5)', () => {
  const text = reviewText({ mainFiles: ['/elsewhere/x.js'], docFiles: ['/other/y.md'] })
  const line = diffLine(text)
  assert.ok(!line.includes(' -- '), `bare diff expected, got ${line}`)
  const unrooted = text.split('\n').find((l) => l.includes('UNROOTED (not in pathspec): '))
  assert.ok(unrooted && unrooted.includes('/elsewhere/x.js') && unrooted.includes('/other/y.md'), String(unrooted))
})

test('S13: ./src/a.js, src\\a.js and WT/src/a.js collapse to one pathspec entry (D5 dedupe)', () => {
  const text = reviewText({ mainFiles: ['./src/a.js', 'src\\a.js', `${WT}/src/a.js`] })
  assertPathspec(text, ['":(literal)src/a.js"'])
  assert.equal(diffLine(text).split(':(literal)').length - 1, 1)
})

// ── S14/S15: verify cross-item pattern-vs-pattern coverage (D6) ─────────────────────────────────

function verifyStages() {
  return [
    { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' },
    { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' },
  ]
}

function verifyRow(item, plannerOver, post) {
  return {
    id: item.id, short: item.short, status: 'done', reason: 'ok',
    stages: [
      { seat: 'planner', status: 'done', reason: 'ok', modelReported: 'opus', notes: [], commits: { pre: '', post: '' }, files: [] },
      { seat: 'implementer', status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: 'aaaa000', post }, files: [] },
    ],
    outputs: { planner: plannerOutput(plannerOver), implementer: implementerOutput() },
  }
}

/** A writes aFiles under declaration declA; B declares declB and writes bFiles. Returns A's findings. */
function crossVerify(declA, aFiles, declB, bFiles) {
  const A = itemFixture({ short: 'aaaaaaaa', stages: verifyStages() })
  const B = itemFixture({ short: 'bbbbbbbb', stages: verifyStages() })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A, B] }), meta: {} }
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [verifyRow(A, { mainFiles: declA }, 'bbbb111'), verifyRow(B, { mainFiles: declB }, 'cccc222')],
    refused: [], deferred: [],
  }
  const gitFacts = {
    exists: { aaaa000: true, bbbb111: true, cccc222: true },
    commits: [
      { sha: 'bbbb111', subject: 'feat: a [aaaaaaaa]', body: 'why\n\nSeat: implementer', files: aFiles },
      { sha: 'cccc222', subject: 'feat: b [bbbbbbbb]', body: 'why\n\nSeat: implementer', files: bFiles },
    ],
  }
  const out = rel.verify(doc, result, gitFacts)
  return out.items.find((i) => i.short === 'aaaaaaaa').findings
}

test('S14: A covers src/x.js only by glob src/*.js, B covers it by directory src -> "also covered by item" (D6)', () => {
  const findings = crossVerify(['src/*.js'], ['src/x.js'], ['src'], ['src/z.md'])
  assert.ok(findings.includes('implementer wrote src/x.js also covered by item bbbbbbbb'), JSON.stringify(findings))
})

test('S14: A covers src/x.js only by src/**, B by src/*.js -> "also covered by item" (D6)', () => {
  const findings = crossVerify(['src/**'], ['src/x.js'], ['src/*.js'], [])
  assert.ok(findings.includes('implementer wrote src/x.js also covered by item bbbbbbbb'), JSON.stringify(findings))
})

test('S15 (guard): both exact, A exact + B glob, and disjoint produce no findings for A (D6)', () => {
  assert.deepEqual(crossVerify(['src/x.js'], ['src/x.js'], ['src/x.js'], ['src/x.js']), [])
  assert.deepEqual(crossVerify(['src/x.js'], ['src/x.js'], ['src/*.js'], []), [])
  assert.deepEqual(crossVerify(['src/a.js'], ['src/a.js'], ['docs/b.md'], ['docs/b.md']), [])
})

// ── S16: <ownedTests> roots a main-checkout absolute test path into the worktree ───────────────

test('S16: seatPrompt <ownedTests> rewrites a /repo-absolute test path to WT/<rel> (D2 roots)', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'aaaaaaaa', stages: stages.bugFixLike(), worktree: WT })
  const plan = planFixture({
    items: [item], repoRoot: REPO, baseSha: BASE,
    project: {
      searchScope: 'claude-plugins/task-orchestrator', scratchDir: '/tmp/scratch', shell: 'bash',
      verify: [{ name: 'owned', command: 'node --test <ownedTests>', ownedTestsPattern: '\\.test\\.mjs$', seats: ['implementer', 'test-author'] }],
    },
  })
  const stage = item.stages.find((s) => s.seat === 'test-author')
  const prompt = core.seatPrompt(plan, item, stage, { planner: { testFiles: [`${REPO}/scripts/tests/a.test.mjs`], existingTestEdits: [] } })
  assert.ok(prompt.includes(`node --test "${WT}/scripts/tests/a.test.mjs"`), prompt)
  assert.ok(!prompt.includes(`"${REPO}/scripts/tests/a.test.mjs"`), 'the main-checkout path must not be run')
})

// ── S17: REL relativizePath parity with core (NEW-SURFACE) + literal oracles from the doc ───────

const REL_CASES = [
  // [p, roots, expected] — expected from the relativizePath doc comment and D2/D3
  [`${WT}/src/x.js`, [WT, REPO], 'src/x.js'],
  [`${WT}/src/x.js`, [REPO, WT], 'src/x.js'], // longest root wins regardless of order
  [`${REPO}/src/x.js`, [WT, REPO], 'src/x.js'],
  ['src/', [WT, REPO], 'src'],
  ['src/x.js', [WT, REPO], 'src/x.js'],
  ['src/*.js', [WT, REPO], 'src/*.js'],
  ['docs/a b.md', [WT, REPO], 'docs/a b.md'],
  ['./src/a.js', [WT, REPO], 'src/a.js'],
  ['src\\a.js', [WT, REPO], 'src/a.js'],
  ['/elsewhere/x.js', [WT, REPO], '/elsewhere/x.js'],
  ['D:\\Repo\\src\\x.js', ['d:/repo'], 'src/x.js'], // drive path: case-insensitive
  ['/repo/.claude/worktrees/b/src/x.js', ['/repo/.claude/worktrees/b', REPO], 'src/x.js'],
  ['/repox/a.js', [REPO], '/repox/a.js'], // not under /repo (segment boundary)
  ['/Repo/src/x.js', [REPO], '/Repo/src/x.js'], // non-drive path: case-sensitive, so outside
]

test('S17: run-exec-lib relativizePath equals core.relativizePath on every input, and both match the documented results', () => {
  const core = loadCore(SCRIPT_PATH)
  assert.equal(typeof rel.relativizePath, 'function', 'run-exec-lib must export relativizePath')
  for (const [p, roots, want] of REL_CASES) {
    const a = core.relativizePath(p, roots)
    const b = rel.relativizePath(p, roots)
    assert.equal(b, a, `parity for ${p} ${JSON.stringify(roots)}`)
    assert.equal(a, want, `core.relativizePath(${p}, ${JSON.stringify(roots)})`)
  }
})

// ── S18: args.repoRoot is assembled from the snapshot and accepted by normalizeArgs ────────────

test('S18: run-planner plan sets args.repoRoot from snap.git.repoRoot (assembleArgs, D2)', () => {
  const snap = JSON.parse(readFileSync(join(PLANNER_FIXTURES, 'independent-two.json'), 'utf8'))
  snap.git.repoRoot = '/srv/proj-c7de'
  const dir = mkdtempSync(join(tmpdir(), 'path-keys-s18-'))
  const inPath = join(dir, 'snap.json')
  writeFileSync(inPath, JSON.stringify(snap))
  const res = spawnSync(process.execPath, [RUN_PLANNER, 'plan', '--in', inPath, '--now', '2026-09-28T12:00:00Z', '--scratchpad', dir], { encoding: 'utf8' })
  assert.equal(res.status, 0, res.stdout + res.stderr)
  const out = JSON.parse(res.stdout)
  assert.ok(out.args, 'expected a plan with args')
  assert.equal(out.args.repoRoot, '/srv/proj-c7de')
})

test('S18: normalizeArgs accepts repoRoot present or absent', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const present = core.normalizeArgs(planFixture({ items: [item], repoRoot: REPO }))
  assert.equal(present.ok, true, present.reason)
  const absent = core.normalizeArgs(planFixture({ items: [item] }))
  assert.equal(absent.ok, true, absent.reason)
})

test('S18 probe: normalizeArgs rejects a non-string repoRoot with "invalid args: bad repoRoot" (orchestrator-confirmed contract)', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const bad = core.normalizeArgs(planFixture({ items: [item], repoRoot: 5 }))
  assert.equal(bad.ok, false)
  assert.equal(bad.reason, 'invalid args: bad repoRoot')
})

// ── probes (test-plan probe list) via lockKeysFor ───────────────────────────────────────────────

function implKeys(files, { worktree = WT, repoRoot = REPO } = {}) {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike(), worktree })
  const plan = planFixture({ items: [item], worktreeMode: 'shared', repoRoot })
  return core.lockKeysFor(item, item.stages[1], { planner: { mainFiles: files, docFiles: [] } }, plan)
}

test('probe: a backslash, upper-case drive path under repoRoot d:/repo keys as file:src/x.js', () => {
  assert.deepEqual(implKeys(['D:\\Repo\\src\\x.js'], { worktree: 'd:/repo/.claude/worktrees/feat-x', repoRoot: 'd:/repo' }), ['file:src/x.js'])
})

test('probe: src/ and src produce the same key file:src (trailing slash dropped, D3)', () => {
  assert.deepEqual(implKeys(['src/']), ['file:src'])
  assert.deepEqual(implKeys(['src']), ['file:src'])
})

test('probe: a UNC-style //h/s/x entry claims the whole tree (worktree:<WT>)', () => {
  assert.deepEqual(implKeys(['//h/s/x']), [`worktree:${WT}`])
})

test('probe: duplicate spellings of one file dedupe to a single key', () => {
  assert.deepEqual(implKeys(['./src/a.js', 'src\\a.js', `${WT}/src/a.js`, `${REPO}/src/a.js`]), ['file:src/a.js'])
})
