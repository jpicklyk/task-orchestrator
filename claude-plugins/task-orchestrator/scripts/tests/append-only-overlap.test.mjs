// Append-only file overlap is a merge-time conflict, not a reason to defer (item 7474b61a).
//
// BLIND test author: written from the item's frozen queue notes `task-scope` (Contract, Pattern
// match, Planner sections) and `test-plan` (S1-S14 + probes), the orchestrator-supplied
// declarations file (signatures and literals only), and the existing test harnesses
// (workflow-harness.mjs, run-exec.test.mjs, run-planner.test.mjs, path-keys*.test.mjs). No
// production source under workflows/, scripts/*.mjs or scripts/lib/ was opened by any tool. Every
// expected value below is a literal derived from the task-scope contract, never from running the
// code under test.
//
// Scenario ids S1-S14 match the `test-plan` note. Labels: E = EXISTING-SURFACE, N = NEW-SURFACE
// (per the test-plan). PROBE-prefixed tests cover the plan's adversarial probe list.
//
// Oracle key: TS = task-scope note. "Contract" / "Pattern match" / "Planner" are its sections.
//
// Ambiguities NOT asserted (task-scope silent, recorded in test-manifest): a trailing "/" in a
// pattern; null for normalizeArgs project.appendOnlyFiles; dedupe or canonical form of the
// printed file names beyond exact-string equality; dedupe of duplicate patterns in
// assembleArgs output.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { loadCore, fakeAgent, fakeParallel, planFixture, itemFixture, stages } from './workflow-harness.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import { initState, next } from '../run-exec-lib.mjs'
import { validateSnapshot, buildPlanDoc, validatePlanDoc } from '../run-planner-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..') // claude-plugins/task-orchestrator
const REPO_ROOT = join(PLUGIN_ROOT, '..', '..')
const SCRIPT_PATH = join(PLUGIN_ROOT, 'workflows', 'implement-wave.js')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')
const NOW = '2026-09-28T12:00:00Z'

const SERVER_KT = 'current/src/main/kotlin/io/github/jpicklyk/mcptask/current/interfaces/mcp/CurrentMcpServer.kt'
const BUDGET_KT = 'current/src/test/kotlin/io/github/jpicklyk/mcptask/current/application/tools/ToolTokenBudgetTest.kt'

// ---- builders (valid by construction against the planner-v1 / implementer-v1 required fields) ----

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

function envelope(output, { entry = { applied: true, newRole: 'work' } } = {}) {
  return {
    status: 'done', reason: 'ok', notes: [], commits: { pre: '', post: '' }, files: [],
    modelReported: 'fake', entry, output,
  }
}

function sideOf(short, files, extra = {}) {
  return { short, output: plannerOutput({ mainFiles: files }), ...extra }
}

function clone(x) {
  return JSON.parse(JSON.stringify(x))
}

function loadFixture(name) {
  return JSON.parse(readFileSync(join(FIXTURES, name), 'utf8'))
}

const core = () => loadCore(SCRIPT_PATH)

// ============================================================================================
// S5, S7, S10 - overlapDeferral basics (shared exemption, no patterns, shared mode)
// ============================================================================================

test('S5 [N]: shared CHANGELOG.md + src/shared.js with CHANGELOG.md append-only -> defers naming ONLY src/shared.js (TS Contract: reason lists files in non-exempt pairs)', () => {
  const mine = sideOf('b', ['CHANGELOG.md', 'src/shared.js'])
  const higher = [sideOf('a', ['CHANGELOG.md', 'src/shared.js'])]
  assert.deepEqual(core().overlapDeferral(mine, higher, 'per-item', ['CHANGELOG.md']), { reason: 'overlap a on src/shared.js' })
})

test('S1 (unit) [N]: overlap confined to append-only files does not defer; the same overlap without the pattern defers naming the file (TS Contract)', () => {
  const mine = sideOf('b', ['CHANGELOG.md', SERVER_KT, 'src/b.js'])
  const higher = [sideOf('a', ['CHANGELOG.md', SERVER_KT, 'src/a.js'])]
  assert.equal(core().overlapDeferral(mine, higher, 'per-item', ['CHANGELOG.md', SERVER_KT]), null)
  // Fixture condition that fails without the fix: a one-sided pattern list leaves the other file non-exempt.
  assert.deepEqual(
    core().overlapDeferral(mine, higher, 'per-item', ['CHANGELOG.md']),
    { reason: `overlap a on ${SERVER_KT}` },
  )
  assert.deepEqual(
    core().overlapDeferral(mine, higher, 'per-item', []),
    { reason: `overlap a on CHANGELOG.md, ${SERVER_KT}` },
  )
})

test('S7 [E]: no patterns (4th argument omitted, or []) -> a CHANGELOG.md overlap still defers, naming the file (TS Contract: default [])', () => {
  const mine = sideOf('b', ['CHANGELOG.md'])
  const higher = [sideOf('a', ['CHANGELOG.md'])]
  assert.deepEqual(core().overlapDeferral(mine, higher, 'per-item'), { reason: 'overlap a on CHANGELOG.md' })
  assert.deepEqual(core().overlapDeferral(mine, higher, 'per-item', []), { reason: 'overlap a on CHANGELOG.md' })
  assert.deepEqual(core().overlapDeferral(mine, higher, 'per-item', undefined), { reason: 'overlap a on CHANGELOG.md' })
})

test('S10 [N]: shared mode never defers, with or without patterns, even on a non-exempt overlap (TS Contract: mode shared -> null)', () => {
  const mine = sideOf('b', ['CHANGELOG.md', 'src/shared.js'])
  const higher = [sideOf('a', ['CHANGELOG.md', 'src/shared.js'])]
  assert.equal(core().overlapDeferral(mine, higher, 'shared', ['CHANGELOG.md']), null)
  assert.equal(core().overlapDeferral(mine, higher, 'shared', []), null)
  assert.equal(core().overlapDeferral(mine, higher, 'shared'), null)
})

// ============================================================================================
// S6 - exemption needs equal, concrete claims on both sides
// ============================================================================================

test('S6 [N]: mine CHANGELOG.md vs a higher glob / directory / whole-tree / unrooted claim still defers (TS Contract: exempt only when both glob-free, not **, equal)', () => {
  for (const h of [['*.md'], ['.'], ['/elsewhere/x']]) {
    assert.deepEqual(
      core().overlapDeferral(sideOf('b', ['CHANGELOG.md']), [sideOf('a', h)], 'per-item', ['CHANGELOG.md']),
      { reason: 'overlap a on CHANGELOG.md' },
      `higher ${JSON.stringify(h)}`,
    )
  }
})

test('S6 [N]: mine whole-tree "." vs higher CHANGELOG.md still defers and the unrooted mine claim prints as ** (TS Contract)', () => {
  assert.deepEqual(
    core().overlapDeferral(sideOf('b', ['.']), [sideOf('a', ['CHANGELOG.md'])], 'per-item', ['CHANGELOG.md']),
    { reason: 'overlap a on **' },
  )
})

test('S6 [N]: a glob claim on the MINE side vs a concrete higher claim still defers (TS Contract: both sides must be glob-free)', () => {
  assert.deepEqual(
    core().overlapDeferral(sideOf('b', ['*.md']), [sideOf('a', ['CHANGELOG.md'])], 'per-item', ['CHANGELOG.md']),
    { reason: 'overlap a on *.md' },
  )
})

// ============================================================================================
// S11, S12 - case-insensitivity, anchoring, glob semantics
// ============================================================================================

test('S11 [N]: matching and equality are case-insensitive (TS Contract + Pattern match)', () => {
  assert.equal(
    core().overlapDeferral(sideOf('b', ['changelog.md']), [sideOf('a', ['CHANGELOG.md'])], 'per-item', ['CHANGELOG.md']),
    null,
  )
  // Pattern spelled in a different case than the claims (claims equal each other).
  assert.equal(
    core().overlapDeferral(sideOf('b', ['CHANGELOG.md']), [sideOf('a', ['CHANGELOG.md'])], 'per-item', ['changelog.md']),
    null,
  )
})

test('S12 [N]: patterns are anchored at the repo root: CHANGELOG.md does not cover docs/CHANGELOG.md; **/CHANGELOG.md covers both (TS Pattern match)', () => {
  const nested = (p) => core().overlapDeferral(sideOf('b', ['docs/CHANGELOG.md']), [sideOf('a', ['docs/CHANGELOG.md'])], 'per-item', p)
  const top = (p) => core().overlapDeferral(sideOf('b', ['CHANGELOG.md']), [sideOf('a', ['CHANGELOG.md'])], 'per-item', p)
  assert.deepEqual(nested(['CHANGELOG.md']), { reason: 'overlap a on docs/CHANGELOG.md' })
  assert.equal(nested(['**/CHANGELOG.md']), null)
  assert.equal(top(['**/CHANGELOG.md']), null, '** matches zero whole segments')
  assert.equal(top(['CHANGELOG.md']), null)
})

test('S12 [N]: * does not cross a path separator; ? matches exactly one character (TS Pattern match)', () => {
  const run = (file, p) => core().overlapDeferral(sideOf('b', [file]), [sideOf('a', [file])], 'per-item', [p])
  assert.equal(run('docs/a.md', 'docs/*.md'), null)
  assert.deepEqual(run('docs/a/b.md', 'docs/*.md'), { reason: 'overlap a on docs/a/b.md' })
  assert.equal(run('docs/a.md', 'docs/?.md'), null)
  assert.deepEqual(run('docs/ab.md', 'docs/?.md'), { reason: 'overlap a on docs/ab.md' })
  assert.deepEqual(run('docs/.md', 'docs/?.md'), { reason: 'overlap a on docs/.md' }, '? needs exactly one character')
})

test('S12 [N]: ** matches zero or more whole segments in the middle of a pattern (TS Pattern match)', () => {
  const run = (file, p) => core().overlapDeferral(sideOf('b', [file]), [sideOf('a', [file])], 'per-item', [p])
  assert.equal(run('a/c.md', 'a/**/c.md'), null)
  assert.equal(run('a/x/c.md', 'a/**/c.md'), null)
  assert.equal(run('a/x/y/c.md', 'a/**/c.md'), null)
  assert.deepEqual(run('b/x/c.md', 'a/**/c.md'), { reason: 'overlap a on b/x/c.md' })
})

// ============================================================================================
// S13 - reason ordering and first-deferring-item rule
// ============================================================================================

test('S13 [E]: reason lists the distinct mine files of the first deferring higher item in mainFiles, docFiles, testFiles order; exempt-only and null-output items are skipped (TS Contract)', () => {
  const mine = {
    short: 'm',
    output: plannerOutput({
      mainFiles: ['src/a.js', 'CHANGELOG.md'],
      docFiles: ['docs/x.md', 'src/a.js'],
      testFiles: ['t/a.test.js', 'src/c.js'],
    }),
  }
  const higher = [
    { short: 'h0', output: null },
    sideOf('hx', ['CHANGELOG.md']),
    {
      short: 'h2',
      output: plannerOutput({
        mainFiles: ['src/c.js'],
        docFiles: ['docs/x.md', 'CHANGELOG.md'],
        testFiles: ['src/a.js'],
      }),
    },
    sideOf('h3', ['src/a.js']),
  ]
  assert.deepEqual(
    core().overlapDeferral(mine, higher, 'per-item', ['CHANGELOG.md']),
    { reason: 'overlap h2 on src/a.js, docs/x.md, src/c.js' },
  )
})


test('S14 [N]: worktree-absolute claims for the same append-only file under two worktrees are exempt; a non-append-only file under the same roots still defers by its repo-relative name (TS Contract + path-keys D4)', () => {
  const REPO = '/repo'
  const wtA = '/repo/.claude/worktrees/a'
  const wtB = '/repo/.claude/worktrees/b'
  const mine = (files) => ({ short: 'b', output: plannerOutput({ mainFiles: files }), roots: [wtB, REPO] })
  const higher = (files) => [{ short: 'a', output: plannerOutput({ mainFiles: files }), roots: [wtA, REPO] }]
  assert.equal(
    core().overlapDeferral(mine([`${wtB}/CHANGELOG.md`]), higher([`${wtA}/CHANGELOG.md`]), 'per-item', ['CHANGELOG.md']),
    null,
  )
  assert.deepEqual(
    core().overlapDeferral(mine([`${wtB}/src/x.js`]), higher([`${wtA}/src/x.js`]), 'per-item', ['CHANGELOG.md']),
    { reason: 'overlap a on src/x.js' },
  )
})

// ============================================================================================
// S1 / S5 (Method A) - runPlan
// ============================================================================================

function methodAPlan(patterns) {
  const itemA = itemFixture({ short: 'ovA', stages: stages.featureTaskLike() })
  const itemB = itemFixture({ short: 'ovB', stages: stages.featureTaskLike() })
  const extra = patterns === undefined ? {} : { project: { appendOnlyFiles: patterns } }
  return { itemA, itemB, plan: planFixture({ items: [itemA, itemB], worktreeMode: 'per-item', ...extra }) }
}

async function runMethodA(patterns, filesA, filesB) {
  const { itemA, itemB, plan } = methodAPlan(patterns)
  const script = {
    [`planner:${itemA.short}`]: envelope(plannerOutput({ mainFiles: filesA })),
    [`planner:${itemB.short}`]: envelope(plannerOutput({ mainFiles: filesB })),
    [`implementer:${itemA.short}`]: envelope(implementerOutput()),
    [`implementer:${itemB.short}`]: envelope(implementerOutput()),
  }
  const { agent, calls } = fakeAgent(script)
  const result = await core().runPlan(plan, { agent, parallel: fakeParallel, log: () => {} })
  return { result, calls, itemA, itemB }
}

test('S1 [N] Acceptance A (Method A): per-item items sharing only CHANGELOG.md and the tool registry both reach their implementer (TS Acceptance)', async () => {
  const { result, calls } = await runMethodA(['CHANGELOG.md', SERVER_KT, BUDGET_KT], ['CHANGELOG.md', SERVER_KT, 'src/a.js'], ['CHANGELOG.md', SERVER_KT, 'src/b.js'])
  assert.ok(calls.some((c) => c.label === 'implementer:ovA'), 'implementer:ovA called')
  assert.ok(calls.some((c) => c.label === 'implementer:ovB'), 'implementer:ovB called')
  const rB = result.items.find((r) => r.short === 'ovB')
  assert.notEqual(rB.status, 'deferred')
})

test('S1 [N] control (Method A): the same fixture with no appendOnlyFiles defers B naming both files (the fixture can fail)', async () => {
  const { result, calls } = await runMethodA(undefined, ['CHANGELOG.md', SERVER_KT, 'src/a.js'], ['CHANGELOG.md', SERVER_KT, 'src/b.js'])
  const rB = result.items.find((r) => r.short === 'ovB')
  assert.equal(rB.status, 'deferred')
  assert.equal(rB.reason, `overlap ovA on CHANGELOG.md, ${SERVER_KT}`)
  assert.ok(!calls.some((c) => c.label === 'implementer:ovB'))
})

test('S5 [N] (Method A): a shared append-only file plus a shared source file defers B naming only the source file', async () => {
  const { result, calls } = await runMethodA(['CHANGELOG.md'], ['CHANGELOG.md', 'src/shared.js'], ['CHANGELOG.md', 'src/shared.js'])
  const rB = result.items.find((r) => r.short === 'ovB')
  assert.equal(rB.status, 'deferred')
  assert.equal(rB.reason, 'overlap ovA on src/shared.js')
  assert.ok(!calls.some((c) => c.label === 'implementer:ovB'))
})

// ============================================================================================
// S2 (Method B) - next
// ============================================================================================

function methodBDoc(patterns, filesA, filesB) {
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const B = itemFixture({ short: 'bbbbbbbb', stages: stages.featureTaskLike() })
  const extra = patterns === undefined ? {} : { project: { appendOnlyFiles: patterns } }
  const doc = {
    contract: 'run-wave/plan-doc-v1',
    args: planFixture({ items: [A, B], worktreeMode: 'per-item', ...extra }),
    meta: {},
  }
  const state = initState(doc, 'B')
  const outA = plannerOutput({ mainFiles: filesA })
  const outB = plannerOutput({ mainFiles: filesB })
  state.stages['aaaaaaaa:planner'] = envelope(outA)
  state.outs['aaaaaaaa:planner'] = outA
  state.stages['bbbbbbbb:planner'] = envelope(outB)
  state.outs['bbbbbbbb:planner'] = outB
  return { doc, state }
}

test('S2 [N] Acceptance B (Method B): next dispatches an implementer for both items sharing only CHANGELOG.md and the tool registry (TS Acceptance)', () => {
  const { doc, state } = methodBDoc(
    ['CHANGELOG.md', SERVER_KT, BUDGET_KT],
    ['CHANGELOG.md', SERVER_KT, 'src/a.js'],
    ['CHANGELOG.md', SERVER_KT, 'src/b.js'],
  )
  const r = next(loadWaveCore(), doc, state)
  const implementers = r.dispatch.filter((d) => d.seat === 'implementer').map((d) => d.item).sort()
  assert.deepEqual(implementers, ['aaaaaaaa', 'bbbbbbbb'])
  assert.ok(!(r.settled || []).some((s) => s.item === 'bbbbbbbb' && s.status === 'deferred'))
})

test('S2 [N] control (Method B): without appendOnlyFiles the same state defers B naming both files', () => {
  const { doc, state } = methodBDoc(undefined, ['CHANGELOG.md', SERVER_KT, 'src/a.js'], ['CHANGELOG.md', SERVER_KT, 'src/b.js'])
  const r = next(loadWaveCore(), doc, state)
  const settled = r.settled.find((s) => s.item === 'bbbbbbbb')
  assert.ok(settled)
  assert.equal(settled.status, 'deferred')
  assert.equal(settled.reason, `overlap aaaaaaaa on CHANGELOG.md, ${SERVER_KT}`)
})

test('S5 [N] (Method B): a shared source file defers B naming only that file when the append-only file is also shared', () => {
  const { doc, state } = methodBDoc(['CHANGELOG.md'], ['CHANGELOG.md', 'src/shared.js'], ['CHANGELOG.md', 'src/shared.js'])
  const r = next(loadWaveCore(), doc, state)
  const settled = r.settled.find((s) => s.item === 'bbbbbbbb')
  assert.ok(settled)
  assert.equal(settled.reason, 'overlap aaaaaaaa on src/shared.js')
})

test('PROBE: next called twice on the same state gives the same result (with appendOnlyFiles set)', () => {
  const { doc, state } = methodBDoc(['CHANGELOG.md'], ['CHANGELOG.md', 'src/a.js'], ['CHANGELOG.md', 'src/b.js'])
  const c = loadWaveCore()
  assert.equal(JSON.stringify(next(c, doc, state)), JSON.stringify(next(c, doc, state)))
})

// ============================================================================================
// S3, S4 - planner: assembleArgs via buildPlanDoc, repo profile
// ============================================================================================

function independentTwoWith(appendOnlyFiles, { absent = false } = {}) {
  const snap = clone(loadFixture('independent-two.json'))
  if (absent) delete snap.profile.appendOnlyFiles
  else snap.profile.appendOnlyFiles = appendOnlyFiles
  return snap
}

test('S3 [E]: buildPlanDoc copies the canonical pattern list to args.project.appendOnlyFiles; [] stays []; an absent key adds no field (TS Planner: assembleArgs)', () => {
  const withKey = buildPlanDoc(independentTwoWith(['.\\CHANGELOG.md', 'docs//x/*.md']), { now: NOW })
  assert.equal(withKey.ok, true, JSON.stringify(withKey.errors))
  assert.deepEqual(withKey.doc.args.project.appendOnlyFiles, ['CHANGELOG.md', 'docs/x/*.md'])

  const empty = buildPlanDoc(independentTwoWith([]), { now: NOW })
  assert.equal(empty.ok, true, JSON.stringify(empty.errors))
  assert.deepEqual(empty.doc.args.project.appendOnlyFiles, [])

  const absent = buildPlanDoc(independentTwoWith(undefined, { absent: true }), { now: NOW })
  assert.equal(absent.ok, true, JSON.stringify(absent.errors))
  assert.equal(Object.hasOwn(absent.doc.args.project ?? {}, 'appendOnlyFiles'), false)
})

test('S3 [E]: the built args still satisfy the args-v1 schema fixture when appendOnlyFiles is set', () => {
  const built = buildPlanDoc(independentTwoWith(['CHANGELOG.md', 'docs/x/*.md']), { now: NOW })
  assert.equal(built.ok, true, JSON.stringify(built.errors))
  assert.deepEqual(validatePlanDoc(built.doc, loadFixture('args-v1.schema.json')), { ok: true, errors: [] })
})

test('S3 [E]: the args-v1 schema fixture rejects a non-array project.appendOnlyFiles (the production validate subcommand reads this schema)', () => {
  const built = buildPlanDoc(independentTwoWith(['CHANGELOG.md']), { now: NOW })
  assert.equal(built.ok, true, JSON.stringify(built.errors))
  const bad = clone(built.doc)
  bad.args.project.appendOnlyFiles = 'CHANGELOG.md'
  assert.equal(validatePlanDoc(bad, loadFixture('args-v1.schema.json')).ok, false)
  const badItem = clone(built.doc)
  badItem.args.project.appendOnlyFiles = [3]
  assert.equal(validatePlanDoc(badItem, loadFixture('args-v1.schema.json')).ok, false)
})

test('S4 [E]: the repo .taskorchestrator/run-profile.json lists CHANGELOG.md, the tool registry and the budget test, and passes validateSnapshot (TS Files)', () => {
  const profile = JSON.parse(readFileSync(join(REPO_ROOT, '.taskorchestrator', 'run-profile.json'), 'utf8'))
  assert.ok(Array.isArray(profile.appendOnlyFiles))
  assert.ok(profile.appendOnlyFiles.includes('CHANGELOG.md'))
  assert.ok(profile.appendOnlyFiles.includes(SERVER_KT))
  assert.ok(profile.appendOnlyFiles.includes(BUDGET_KT))

  const control = validateSnapshot(independentTwoWith(undefined, { absent: true }))
  assert.equal(control.ok, true, JSON.stringify(control.errors))
  const result = validateSnapshot(independentTwoWith(profile.appendOnlyFiles))
  assert.equal(result.ok, true, JSON.stringify(result.errors))
})

// ============================================================================================
// S8 - validateSnapshot
// ============================================================================================

const MUST_BE_ARRAY = 'profile.appendOnlyFiles must be an array of strings'
const entryMessage = (raw) => `profile.appendOnlyFiles entry "${raw}" is not a repo-relative file pattern`

test('S8 [E]: a non-array, null, non-string element or empty-string element -> the must-be-array message (TS Planner)', () => {
  for (const bad of ['CHANGELOG.md', null, [3], ['']]) {
    const result = validateSnapshot(independentTwoWith(bad))
    assert.equal(result.ok, false, `profile.appendOnlyFiles=${JSON.stringify(bad)}`)
    assert.ok(result.errors.includes(MUST_BE_ARRAY), `errors ${JSON.stringify(result.errors)} for ${JSON.stringify(bad)}`)
    assert.ok(
      !result.errors.some((e) => e.includes('is not a repo-relative file pattern')),
      `no entry message for ${JSON.stringify(bad)}`,
    )
  }
})

test('S8 [E]: each non-repo-relative or wildcard-only entry -> exactly one entry error carrying its raw text (TS Planner)', () => {
  const bads = ['/abs', 'C:/x', '//h/x', '../x', 'a/../b', '.', './', '*', '**', '**/*']
  for (const raw of bads) {
    const result = validateSnapshot(independentTwoWith(['CHANGELOG.md', raw]))
    assert.equal(result.ok, false, `entry ${JSON.stringify(raw)}`)
    assert.deepEqual(
      result.errors.filter((e) => e.includes('profile.appendOnlyFiles')),
      [entryMessage(raw)],
      `entry ${JSON.stringify(raw)}`,
    )
  }
})

test('S8 [E]: one error per bad entry, in order, and good entries add none (TS Planner)', () => {
  const result = validateSnapshot(independentTwoWith(['/abs', 'CHANGELOG.md', '..', '**']))
  assert.equal(result.ok, false)
  assert.deepEqual(
    result.errors.filter((e) => e.includes('profile.appendOnlyFiles')),
    [entryMessage('/abs'), entryMessage('..'), entryMessage('**')],
  )
})

test('S8 [E]: repo-relative file patterns, including globs, literal metacharacters and mixed wildcard segments, are accepted (TS Planner)', () => {
  for (const good of [
    ['CHANGELOG.md'], ['docs/*.md'], ['**/CHANGELOG.md'], ['a/**/b.md'], ['docs/**'], ['*.md'],
    ['a+b.md'], ['.\\CHANGELOG.md'], ['docs//x.md'], ['CHANGELOG.md', 'CHANGELOG.md'], [],
  ]) {
    const result = validateSnapshot(independentTwoWith(good))
    assert.equal(result.ok, true, `${JSON.stringify(good)}: ${JSON.stringify(result.errors)}`)
  }
})

test('PROBE: backslash-absolute and UNC spellings are absolute after canonicalisation (TS Planner: canonicalise then check)', () => {
  for (const raw of ['C:\\x', '\\\\h\\x', '\\abs', '..\\x']) {
    const result = validateSnapshot(independentTwoWith([raw]))
    assert.equal(result.ok, false, `entry ${JSON.stringify(raw)}`)
    assert.deepEqual(
      result.errors.filter((e) => e.includes('profile.appendOnlyFiles')),
      [entryMessage(raw)],
      `entry ${JSON.stringify(raw)}`,
    )
  }
})

test('S8 [E]: an invalid appendOnlyFiles makes buildPlanDoc return ok:false (errors pushed through validateSnapshot)', () => {
  const result = buildPlanDoc(independentTwoWith(['/abs']), { now: NOW })
  assert.equal(result.ok, false)
  assert.ok(result.errors.includes(entryMessage('/abs')), JSON.stringify(result.errors))
})

// ============================================================================================
// S9 - normalizeArgs
// ============================================================================================

test('S9 [E]: normalizeArgs rejects a non-array or non-string-element project.appendOnlyFiles with "invalid args: bad appendOnlyFiles"; a valid list is accepted (TS Planner: normalizeArgs)', () => {
  const item = itemFixture({ short: 'aaaa5555', stages: stages.schemaFree(), schemaFree: true, configFingerprint: null })
  const baseline = core().normalizeArgs(planFixture({ items: [item] }))
  assert.equal(baseline.ok, true, 'baseline fixture must itself be valid, or the rejections below would pass vacuously')

  for (const bad of ['x', 3, {}, [3], ['CHANGELOG.md', 7]]) {
    const r = core().normalizeArgs(planFixture({ items: [item], project: { appendOnlyFiles: bad } }))
    assert.equal(r.ok, false, `appendOnlyFiles=${JSON.stringify(bad)}`)
    assert.equal(r.reason, 'invalid args: bad appendOnlyFiles', `appendOnlyFiles=${JSON.stringify(bad)}`)
  }
  for (const good of [['CHANGELOG.md'], []]) {
    const r = core().normalizeArgs(planFixture({ items: [item], project: { appendOnlyFiles: good } }))
    assert.equal(r.ok, true, `appendOnlyFiles=${JSON.stringify(good)}`)
  }
  const asJson = core().normalizeArgs(JSON.stringify(planFixture({ items: [item], project: { appendOnlyFiles: 'x' } })))
  assert.equal(asJson.ok, false)
  assert.equal(asJson.reason, 'invalid args: bad appendOnlyFiles')
})

// ============================================================================================
// PROBES - literal metacharacters, order and duplicates, spelling variants
// ============================================================================================

test('PROBE: regex metacharacters in patterns are literal ("." is not a wildcard; "+" and parentheses are literal)', () => {
  const run = (file, p) => core().overlapDeferral(sideOf('b', [file]), [sideOf('a', [file])], 'per-item', [p])
  assert.deepEqual(run('CHANGELOGxmd', 'CHANGELOG.md'), { reason: 'overlap a on CHANGELOGxmd' })
  assert.equal(run('a+b.md', 'a+b.md'), null)
  assert.deepEqual(run('aab.md', 'a+b.md'), { reason: 'overlap a on aab.md' })
  assert.equal(run('a(b).md', 'a(b).md'), null)
})

test('PROBE: duplicate and reordered patterns give the same result', () => {
  const mine = sideOf('b', ['CHANGELOG.md', 'docs/x.md', 'src/a.js'])
  const higher = [sideOf('a', ['CHANGELOG.md', 'docs/x.md', 'src/a.js'])]
  const want = { reason: 'overlap a on src/a.js' }
  assert.deepEqual(core().overlapDeferral(mine, higher, 'per-item', ['CHANGELOG.md', 'docs/x.md']), want)
  assert.deepEqual(core().overlapDeferral(mine, higher, 'per-item', ['docs/x.md', 'CHANGELOG.md']), want)
  assert.deepEqual(core().overlapDeferral(mine, higher, 'per-item', ['CHANGELOG.md', 'CHANGELOG.md', 'docs/x.md']), want)
})

test('PROBE: pattern spellings are canonicalised before matching (backslash, leading ./, repeated slashes) (TS Pattern match)', () => {
  const run = (file, p) => core().overlapDeferral(sideOf('b', [file]), [sideOf('a', [file])], 'per-item', [p])
  assert.equal(run('CHANGELOG.md', './CHANGELOG.md'), null)
  assert.equal(run('CHANGELOG.md', '.\\CHANGELOG.md'), null)
  assert.equal(run('docs/x.md', 'docs\\x.md'), null)
  assert.equal(run('docs/x.md', 'docs//x.md'), null)
})

test('PROBE: a pattern naming a different file never exempts an overlap', () => {
  assert.deepEqual(
    core().overlapDeferral(sideOf('b', ['CHANGELOG.md']), [sideOf('a', ['CHANGELOG.md'])], 'per-item', ['README.md']),
    { reason: 'overlap a on CHANGELOG.md' },
  )
})
