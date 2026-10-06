// Canonical lock-key spellings ('.', './', '//') and whole-tree deferral for unrooted entries
// (item 0836fcd9).
//
// BLIND test author: written from the item's frozen queue note `test-plan` (S1-S13 + probes), the
// orchestrator-supplied declarations file (doc comments and signatures only), and the existing test
// harnesses. No production source under workflows/, scripts/*.mjs, scripts/lib/ or skills/ was
// opened by any tool. Every expected value below is a literal derived from the relativizePath /
// lockKeysFor / overlapDeferral / reviewPrompt doc comments and decisions D2-D7, never from running
// the code under test.
//
// Fixture constants (test-plan header): WT = /repo/.claude/worktrees/feat-x, REPO = /repo, shared
// mode. S1/S2 live in path-keys.test.mjs (tightened bodies); S3-S13 live here.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { loadCore, fakeAgent, fakeParallel, planFixture, itemFixture, stages } from './workflow-harness.mjs'
import * as rel from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..')
const SCRIPT_PATH = join(PLUGIN_ROOT, 'workflows', 'implement-wave.js')

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

/** Same driver as path-keys.test.mjs: did B's implementer start while A's implementer held its lock? */
async function bImplStartedWhileAHolds(filesA, filesB) {
  const core = loadCore(SCRIPT_PATH)
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike(), worktree: WT })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike(), worktree: WT })
  const plan = planFixture({ items: [A, B], worktreeMode: 'shared', repoRoot: REPO })
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

  release('implementer:aaaaaaaa')
  await waitUntil(() => pending().includes('implementer:bbbbbbbb'), 'implementer:B pending after A released')
  release('implementer:bbbbbbbb')
  const result = await run
  assert.equal(result.started, true)
  assert.ok(aStillPending, 'precondition: A\'s implementer was still pending when B was checked')
  return started
}

async function assertSerialize(filesA, filesB) {
  const started = await bImplStartedWhileAHolds(filesA, filesB)
  assert.equal(started, false, `B's implementer must wait for A's lock: A=${JSON.stringify(filesA)} B=${JSON.stringify(filesB)}`)
}

function implKeys(files, { worktree = WT, repoRoot = REPO } = {}) {
  const core = loadCore(SCRIPT_PATH)
  const item = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike(), worktree })
  const plan = planFixture({ items: [item], worktreeMode: 'shared', repoRoot })
  return core.lockKeysFor(item, item.stages[1], { planner: { mainFiles: files, docFiles: [] } }, plan)
}

// ── S3: relativizePath canonicalization table, core and run-exec-lib in parity ─────────────────

const CANON_CASES = [
  // [p, roots, expected] — from the relativizePath doc comment (Canonicalization + Root stripping)
  ['.', [WT, REPO], ''],
  ['./', [WT, REPO], ''],
  ['.//', [WT, REPO], ''],
  ['', [WT, REPO], ''],
  [WT, [WT, REPO], ''],
  [`${WT}/`, [WT, REPO], ''],
  [`${WT}/.`, [WT, REPO], ''],
  [REPO, [WT, REPO], ''],
  ['src/.', [WT, REPO], 'src'],
  ['src/./x.js', [WT, REPO], 'src/x.js'],
  ['src//x.js', [WT, REPO], 'src/x.js'],
  [`${REPO}//src/x.js`, [WT, REPO], 'src/x.js'],
  [`${WT}/./src//x.js`, [WT, REPO], 'src/x.js'],
  ['././x', [WT, REPO], 'x'],
  ['src/./*.js', [WT, REPO], 'src/*.js'],
  ['//h/s/x', [WT, REPO], '/h/s/x'],
  ['src/../x.js', [WT, REPO], 'src/../x.js'],
  ['/', [WT, REPO], '/'],
  ['D:\\Repo\\.\\src\\x.js', ['d:/repo'], 'src/x.js'],
]

test('S3: core and run-exec-lib relativizePath canonicalize every spelling to the documented result', () => {
  const core = loadCore(SCRIPT_PATH)
  for (const [p, roots, want] of CANON_CASES) {
    const a = core.relativizePath(p, roots)
    const b = rel.relativizePath(p, roots)
    assert.equal(a, want, `core.relativizePath(${JSON.stringify(p)}, ${JSON.stringify(roots)})`)
    assert.equal(b, want, `rel.relativizePath(${JSON.stringify(p)}, ${JSON.stringify(roots)})`)
    assert.equal(b, a, `parity for ${JSON.stringify(p)}`)
  }
})

// ── S4/S5: lockKeysFor ───────────────────────────────────────────────────────────────────────────

test('S4: every whole-tree spelling (., ./, WT, WT/., REPO) takes the single worktree key', () => {
  for (const files of [['.'], ['./'], [WT], [`${WT}/.`], [REPO]]) {
    assert.deepEqual(implKeys(files), [`worktree:${WT}`], `lockKeysFor(${JSON.stringify(files)})`)
  }
})

test('S5: src/./x.js, src//x.js and src/x.js collapse to one file:src/x.js key; src/. keys as file:src', () => {
  assert.deepEqual(implKeys(['src/./x.js', 'src//x.js', 'src/x.js']), ['file:src/x.js'])
  assert.deepEqual(implKeys(['src/.']), ['file:src'])
})

// ── S6: runPlan serializes on canonical spellings ───────────────────────────────────────────────

test('S6: "." serializes against src/x.js (whole-tree key)', async () => {
  await assertSerialize(['.'], ['src/x.js'])
})

test('S6: src//x.js serializes against src/x.js', async () => {
  await assertSerialize(['src//x.js'], ['src/x.js'])
})

test('S6: ././x serializes against x', async () => {
  await assertSerialize(['././x'], ['x'])
})

test('S6: the worktree path itself serializes against src/x.js', async () => {
  await assertSerialize([WT], ['src/x.js'])
})

// ── S7: Method B next() ─────────────────────────────────────────────────────────────────────────

test('S7: next() dispatches only A when A declares "." and B declares src/x.js; B waits on a lock', () => {
  const core = loadCore(SCRIPT_PATH)
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike(), worktree: WT })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike(), worktree: WT })
  const doc = { contract: 'run-wave/plan-doc-v1', args: planFixture({ items: [A, B], worktreeMode: 'shared', repoRoot: REPO }), meta: {} }
  const state = rel.initState(doc, 'B')
  const outA = plannerOutput({ mainFiles: ['.'] })
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

// ── S8/S12: overlapDeferral whole-tree claims (D6) ──────────────────────────────────────────────

const wtA = '/repo/.claude/worktrees/a'
const wtB = '/repo/.claude/worktrees/b'

function mineWith(files) {
  return { short: 'b', output: plannerOutput({ mainFiles: files }), roots: [wtB, REPO] }
}
function higherWith(files) {
  return [{ short: 'a', output: plannerOutput({ mainFiles: files }), roots: [wtA, REPO] }]
}

test('S8: an unrooted higher entry (., "", absolute outside roots, "..") overlaps every entry of mine (D6)', () => {
  const core = loadCore(SCRIPT_PATH)
  for (const h of [['.'], [''], ['/elsewhere/x.js'], ['src/../y.js']]) {
    assert.deepEqual(core.overlapDeferral(mineWith(['src/x.js']), higherWith(h), 'per-item'), { reason: 'overlap a' }, `higher ${JSON.stringify(h)}`)
  }
})

test('S8: an unrooted entry on mine\'s side overlaps every higher entry (D6)', () => {
  const core = loadCore(SCRIPT_PATH)
  assert.deepEqual(core.overlapDeferral(mineWith(['.']), higherWith(['docs/a.md']), 'per-item'), { reason: 'overlap a' })
  assert.deepEqual(core.overlapDeferral(mineWith(['/elsewhere/y']), higherWith(['.']), 'per-item'), { reason: 'overlap a' })
})

test('S12 (guard): an empty higher set, disjoint literals, and shared mode never defer', () => {
  const core = loadCore(SCRIPT_PATH)
  assert.equal(core.overlapDeferral(mineWith(['src/x.js']), higherWith([]), 'per-item'), null)
  assert.equal(core.overlapDeferral(mineWith(['src/x.js']), higherWith(['docs/a.md']), 'per-item'), null)
  assert.equal(core.overlapDeferral(mineWith(['src/x.js']), higherWith(['.']), 'shared'), null)
  assert.equal(core.overlapDeferral(mineWith(['.']), higherWith(['src/x.js']), 'shared'), null)
})

// ── S9/S13: reviewPrompt (D5) ───────────────────────────────────────────────────────────────────

function reviewText(plannerOver) {
  const core = loadCore(SCRIPT_PATH)
  const plannerStage = { seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {}, output: 'planner-v1' }
  const implStage = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const item = itemFixture({ short: 'aaaaaaaa', stages: [plannerStage, implStage], worktree: WT })
  const args = planFixture({ items: [item], runId: 'r-test-0836', baseSha: BASE, repoRoot: REPO })
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

function count(hay, needle) {
  return hay.split(needle).length - 1
}

test('S9: an owned "." entry makes the review diff bare (no " -- " pathspec) (D5)', () => {
  const line = diffLine(reviewText({ mainFiles: ['.', 'src/a.js'] }))
  assert.ok(!line.includes(' -- '), `bare diff expected, got ${line}`)
})

test('S9: src/./a.js and src//a.js give exactly one ":(literal)src/a.js" pathspec', () => {
  const text = reviewText({ mainFiles: ['src/./a.js', 'src//a.js'] })
  const line = diffLine(text)
  assert.equal(count(text, '":(literal)src/a.js"'), 1, text)
  assert.ok(line.includes(' -- ":(literal)src/a.js"'), line)
})

test('S13 (guard): a bracketed path is a literal pathspec (only * and ? are glob characters)', () => {
  const line = diffLine(reviewText({ mainFiles: ['src/[ab].js'] }))
  assert.ok(line.includes('":(literal)src/[ab].js"'), line)
  assert.ok(!line.includes(':(glob)'), line)
})

// ── S10/S13: verify ownership with canonical spellings (D7) ─────────────────────────────────────

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

test('S10: A declares src//x.js and writes src/x.js -> no findings (canonical ownership)', () => {
  assert.deepEqual(crossVerify(['src//x.js'], ['src/x.js'], ['docs/b.md'], ['docs/b.md']), [])
})

test('S13 (guard): a whole-tree "." declaration owns nothing in verify (D7)', () => {
  const findings = crossVerify(['.'], ['src/x.js'], ['docs/b.md'], ['docs/b.md'])
  assert.ok(findings.includes('implementer wrote unowned src/x.js'), JSON.stringify(findings))
})

// ── S11: seatPrompt <ownedTests> canonicalizes the test path ────────────────────────────────────

test('S11: seatPrompt <ownedTests> runs scripts//tests/a.test.mjs as the canonical WT path', () => {
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
  const prompt = core.seatPrompt(plan, item, stage, { planner: { testFiles: ['scripts//tests/a.test.mjs'], existingTestEdits: [] } })
  assert.ok(prompt.includes(`"${WT}/scripts/tests/a.test.mjs"`), prompt)
})

// ── S13: lockKeysConflict with literal brackets (OBS-3) ─────────────────────────────────────────

test('S13 (guard): brackets are literal in lock keys — no glob match, but a directory still contains them', () => {
  const core = loadCore(SCRIPT_PATH)
  const rows = [
    ['file:src/[ab].js', 'file:src/a.js', false],
    ['file:src/[ab].js', 'file:src', true],
  ]
  for (const [a, b, want] of rows) {
    assert.equal(core.lockKeysConflict(a, b), want, `(${a}, ${b})`)
    assert.equal(core.lockKeysConflict(b, a), want, `(${b}, ${a})`)
  }
})

// ── probes (test-plan probe list) ───────────────────────────────────────────────────────────────

test('probe: SRC/./X.js keys canonically and conflicts with file:src/x.js (case-insensitive)', () => {
  const core = loadCore(SCRIPT_PATH)
  const keys = implKeys(['SRC/./X.js'])
  assert.deepEqual(keys, ['file:SRC/X.js'])
  assert.equal(core.lockKeysConflict(keys[0], 'file:src/x.js'), true)
  assert.equal(core.lockKeysConflict('file:src/x.js', keys[0]), true)
})

test('probe: a bare drive root C:\\ stays unrooted and takes the worktree key', () => {
  assert.equal(rel.relativizePath('C:\\', [WT, REPO]), 'c:/')
  assert.deepEqual(implKeys(['C:\\']), [`worktree:${WT}`])
})

test('probe: src/*/. canonicalizes to src/*', () => {
  const core = loadCore(SCRIPT_PATH)
  assert.equal(core.relativizePath('src/*/.', [WT, REPO]), 'src/*')
  assert.equal(rel.relativizePath('src/*/.', [WT, REPO]), 'src/*')
})

test('probe: duplicate whole-tree spellings dedupe to one worktree key', () => {
  assert.deepEqual(implKeys(['.', './', WT, `${WT}/`, REPO, '']), [`worktree:${WT}`])
})
