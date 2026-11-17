// Coverage for the run-profile postRun / review declarations (U5, bc5421b3).
// Scenario ids S1-S15 match the item's `test-plan` note. Oracles: the item's task-scope D1-D6 and
// E1-E10 (exact error text), snapshot.md appliesTo matching, never "what the code returns".
// Imports only exported surface (run-planner-lib.mjs) plus the run-planner CLI as a subprocess.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, readFileSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import { validateSnapshot, buildPlanDoc, selectPostRunSteps } from '../run-planner-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..')
const REPO_ROOT = join(PLUGIN_ROOT, '..', '..')
const CLI = join(PLUGIN_ROOT, 'scripts', 'run-planner.mjs')
const FIXTURE = join(HERE, 'fixtures', 'run-planner', 'independent-two.json')
const NOW = '2026-09-28T12:00:00Z'

const clone = (x) => JSON.parse(JSON.stringify(x))
const baseFixture = () => JSON.parse(readFileSync(FIXTURE, 'utf8'))

// Snapshot with the shipped fixture's profile replaced by `profile` (extra keys merged over the base).
function snapWith(extra = {}) {
  const snap = baseFixture()
  snap.profile = { ...snap.profile, ...extra }
  return snap
}
function errorsOf(extra) {
  return validateSnapshot(snapWith(extra)).errors
}

const ENTRY = { step: 'a', when: 'post-run', rule: 'some-rule' }
const e2 = (i) => `profile.postRun[${i}] must have step, when (post-run or post-merge) and rule`
const e3 = (i) => `profile.postRun[${i}].command must be a non-empty string`
const e4 = (i) => `profile.postRun[${i}].appliesTo must be an array of repo-relative patterns`
const e5 = (i, step) => `profile.postRun[${i}].step "${step}" repeats an earlier step`
const E1 = 'profile.postRun must be an array'
const E6 = 'profile.review must be "handoff", "generic" or an object'
const E7 = 'profile.review.mode must be handoff or generic'
const E8 = 'profile.review.skill must be a non-empty string'
const E9 = 'profile.review.step must be a non-empty string'
const E10 = 'profile.review.onFail must be stop or fix-cycle'

function plan(extra) {
  const r = buildPlanDoc(snapWith(extra), { now: NOW, scratchpad: join(tmpdir(), 'post-run-steps-scratch') })
  assert.equal(r.ok, true, JSON.stringify(r.errors))
  return r.doc
}

function runCli(args, opts = {}) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8', ...opts })
}
const tmp = () => mkdtempSync(join(tmpdir(), 'post-run-steps-'))
function planFile(dir, doc) {
  const p = join(dir, 'plan.json')
  writeFileSync(p, JSON.stringify(doc))
  return p
}
function filesFile(dir, text) {
  const p = join(dir, 'files.txt')
  writeFileSync(p, text)
  return p
}

// ---- validateSnapshot: postRun and review ----

test('S1: postRun with every field plus a handoff review object validates ok', () => {
  const r = validateSnapshot(snapWith({
    postRun: [
      { step: 'live-acceptance', when: 'post-merge', rule: 'live-checks' },
      { step: 'red-proofs', when: 'post-merge', rule: 'red-proofs', command: 'node x.mjs <sha>', appliesTo: ['current/**'] },
      { step: 'p.1_x-y', when: 'post-run', rule: 'r0', extraKey: 'ignored' },
    ],
    review: { mode: 'handoff', skill: 'implement', step: 'wave.md Step 5', onFail: 'stop' },
  }))
  assert.equal(r.ok, true, JSON.stringify(r.errors))
})

test('S2: postRun present but not an array (null, object, string) gives exactly E1', () => {
  for (const bad of [null, {}, 'x']) {
    const r = validateSnapshot(snapWith({ postRun: bad }))
    assert.equal(r.ok, false, JSON.stringify(bad))
    assert.deepEqual(r.errors, [E1], JSON.stringify(bad))
  }
})

test('S3: malformed postRun entries give E2 at that index (a bad second entry is index 1)', () => {
  const bads = [
    null,
    { when: 'post-run', rule: 'r' },
    { step: 'Bad Step', when: 'post-run', rule: 'r' },
    { step: 'a', when: 'after', rule: 'r' },
    { step: 'a', rule: 'r' },
    { step: 'a', when: 'post-run' },
    { step: 'a', when: 'post-run', rule: 'Rule!' },
  ]
  for (const bad of bads) {
    const r = validateSnapshot(snapWith({ postRun: [{ ...ENTRY, step: 'ok' }, bad] }))
    assert.equal(r.ok, false, JSON.stringify(bad))
    assert.deepEqual(r.errors, [e2(1)], JSON.stringify(bad))
  }
  // the same defect at index 0 reports index 0
  assert.deepEqual(errorsOf({ postRun: [{ step: 'a', when: 'after', rule: 'r' }] }), [e2(0)])
})

test('S4: command present and empty or non-string gives E3; absent is ok', () => {
  for (const bad of ['', 5]) {
    assert.deepEqual(errorsOf({ postRun: [{ ...ENTRY, command: bad }] }), [e3(0)], JSON.stringify(bad))
  }
  assert.equal(validateSnapshot(snapWith({ postRun: [ENTRY] })).ok, true)
})

test('S5: bad appliesTo values on a postRun entry give E4', () => {
  const bads = [[], '/abs', ['/abs'], ['C:/x'], ['../x'], ['a/../b'], [1], ['']]
  for (const bad of bads) {
    assert.deepEqual(errorsOf({ postRun: [{ ...ENTRY, appliesTo: bad }] }), [e4(0)], JSON.stringify(bad))
  }
  assert.equal(validateSnapshot(snapWith({ postRun: [{ ...ENTRY, appliesTo: ['current/**', 'docs/*.md'] }] })).ok, true)
})

test('S6: a repeated step reports E5 at the later index only', () => {
  const r = validateSnapshot(snapWith({ postRun: [{ ...ENTRY, step: 'a' }, { ...ENTRY, step: 'a', when: 'post-merge' }] }))
  assert.equal(r.ok, false)
  assert.deepEqual(r.errors, [e5(1, 'a')])
  // distinct steps (even differing only by a trailing character) do not repeat
  assert.equal(validateSnapshot(snapWith({ postRun: [{ ...ENTRY, step: 'a' }, { ...ENTRY, step: 'a2' }] })).ok, true)
})

test('S7: review "handoff" and "generic" are ok; any other scalar or array gives E6', () => {
  for (const good of ['handoff', 'generic']) {
    assert.equal(validateSnapshot(snapWith({ review: good })).ok, true, good)
  }
  for (const bad of ['auto', '', 5, true, null, []]) {
    const r = validateSnapshot(snapWith({ review: bad }))
    assert.equal(r.ok, false, JSON.stringify(bad))
    assert.deepEqual(r.errors, [E6], JSON.stringify(bad))
  }
})

test('S8: review object field errors E7-E10; a bare generic object is ok', () => {
  assert.deepEqual(errorsOf({ review: {} }), [E7])
  assert.deepEqual(errorsOf({ review: { mode: 'x' } }), [E7])
  assert.deepEqual(errorsOf({ review: { mode: 'handoff' } }), [E8])
  assert.deepEqual(errorsOf({ review: { mode: 'handoff', skill: '' } }), [E8])
  assert.deepEqual(errorsOf({ review: { mode: 'generic', step: '' } }), [E9])
  assert.deepEqual(errorsOf({ review: { mode: 'generic', onFail: 'retry' } }), [E10])
  assert.equal(validateSnapshot(snapWith({ review: { mode: 'generic' } })).ok, true)
  assert.equal(validateSnapshot(snapWith({ review: { mode: 'handoff', skill: 's', unknown: 1 } })).ok, true)
})

test('S9: the fixture profile (no postRun, no review) and the repo run-profile.json pass validateSnapshot', () => {
  const base = baseFixture()
  assert.equal('postRun' in base.profile, false)
  assert.equal('review' in base.profile, false)
  assert.equal(validateSnapshot(base).ok, true)
  const repoProfile = JSON.parse(readFileSync(join(REPO_ROOT, '.taskorchestrator', 'run-profile.json'), 'utf8'))
  const snap = baseFixture()
  snap.profile = { ...snap.profile, ...repoProfile }
  const r = validateSnapshot(snap)
  assert.equal(r.ok, true, JSON.stringify(r.errors))
})

// ---- buildPlanDoc meta ----

test('S10: buildPlanDoc meta.review and meta.postRun follow D3', () => {
  const generic = { mode: 'generic', onFail: 'fix-cycle' }
  assert.deepEqual(plan({}).meta.review, generic)
  assert.deepEqual(plan({}).meta.postRun, [])
  assert.deepEqual(plan({ review: 'generic' }).meta.review, generic)
  assert.deepEqual(plan({ review: 'handoff' }).meta.review, { mode: 'handoff', onFail: 'fix-cycle' })
  assert.deepEqual(
    plan({ review: { mode: 'handoff', skill: 'implement', step: 'wave.md Step 5', onFail: 'stop' } }).meta.review,
    { mode: 'handoff', skill: 'implement', step: 'wave.md Step 5', onFail: 'stop' },
  )
  assert.deepEqual(plan({ review: { mode: 'generic', skill: 's' } }).meta.review, generic)
  const declared = [
    { step: 'one', when: 'post-merge', rule: 'r1' },
    { step: 'two', when: 'post-run', rule: 'r2', command: 'node c.mjs', appliesTo: ['a/**'] },
    { step: 'three', when: 'post-merge', rule: 'r3', extraKey: 'ignored' },
  ]
  const meta = plan({ postRun: declared }).meta
  assert.deepEqual(meta.postRun, [
    { step: 'one', when: 'post-merge', rule: 'r1' },
    { step: 'two', when: 'post-run', rule: 'r2', command: 'node c.mjs', appliesTo: ['a/**'] },
    { step: 'three', when: 'post-merge', rule: 'r3' },
  ])
  assert.equal('command' in meta.postRun[0], false)
  assert.equal('appliesTo' in meta.postRun[0], false)
})

// ---- selectPostRunSteps and the post-run-steps CLI ----

const THREE = [
  { step: 'p1', when: 'post-merge', rule: 'r1' },
  { step: 'p2', when: 'post-run', rule: 'r2' },
  { step: 'p3', when: 'post-merge', rule: 'r3' },
]

test('S11: the CLI selects by when in declared order and echoes review and when', () => {
  const dir = tmp()
  const doc = plan({ postRun: THREE, review: { mode: 'handoff', skill: 'implement', onFail: 'stop' } })
  const pf = planFile(dir, doc)
  const ff = filesFile(dir, 'x/y.txt\n')
  const merge = runCli(['post-run-steps', '--plan', pf, '--when', 'post-merge', '--files-from', ff])
  assert.equal(merge.status, 0, merge.stderr)
  const out = JSON.parse(merge.stdout)
  assert.equal(out.when, 'post-merge')
  assert.deepEqual(out.steps.map((s) => s.step), ['p1', 'p3'])
  assert.deepEqual(out.skipped, [])
  assert.deepEqual(out.review, doc.meta.review)
  const run = JSON.parse(runCli(['post-run-steps', '--plan', pf, '--when', 'post-run', '--files-from', ff]).stdout)
  assert.equal(run.when, 'post-run')
  assert.deepEqual(run.steps.map((s) => s.step), ['p2'])
  assert.deepEqual(run.skipped, [])
})

test('S12: appliesTo filtering by changed files (case, separators, CRLF, blank lines, empty file)', () => {
  const dir = tmp()
  const doc = plan({
    postRun: [
      { step: 'a', when: 'post-merge', rule: 'ra' },
      { step: 'b', when: 'post-merge', rule: 'rb', appliesTo: ['current/**'] },
      { step: 'c', when: 'post-merge', rule: 'rc', appliesTo: ['docs/*.md'] },
    ],
  })
  const pf = planFile(dir, doc)
  const cSkip = { step: 'c', reason: 'appliesTo matched no changed file' }
  const bSkip = { step: 'b', reason: 'appliesTo matched no changed file' }
  const via = (text) => {
    const r = runCli(['post-run-steps', '--plan', pf, '--when', 'post-merge', '--files-from', filesFile(dir, text)])
    assert.equal(r.status, 0, r.stderr)
    return JSON.parse(r.stdout)
  }
  const names = (o) => o.steps.map((s) => s.step)

  let o = via('CURRENT/src/X.kt\n')
  assert.deepEqual(names(o), ['a', 'b'])
  assert.deepEqual(o.skipped, [cSkip])

  o = via('current\\src\\X.kt\n')
  assert.deepEqual(names(o), ['a', 'b'])

  o = via('docs/sub/x.md\n')
  assert.deepEqual(names(o), ['a'])
  assert.deepEqual(o.skipped, [bSkip, cSkip])

  o = via('docs/x.md\n')
  assert.deepEqual(names(o), ['a', 'c'])
  assert.deepEqual(o.skipped, [bSkip])

  o = via('\r\ncurrent/src/X.kt\r\n\r\n\n')
  assert.deepEqual(names(o), ['a', 'b'])
  assert.deepEqual(o.skipped, [cSkip])

  o = via('')
  assert.deepEqual(names(o), ['a'])
  assert.deepEqual(o.skipped, [bSkip, cSkip])

  // the pure function gives the same answers for the same file list
  let d = selectPostRunSteps(doc, 'post-merge', ['CURRENT/src/X.kt'])
  assert.deepEqual(d.steps.map((s) => s.step), ['a', 'b'])
  assert.deepEqual(d.skipped, [cSkip])
  d = selectPostRunSteps(doc, 'post-merge', [])
  assert.deepEqual(d.steps.map((s) => s.step), ['a'])
  assert.deepEqual(d.skipped, [bSkip, cSkip])
})

test('S13: the CLI refuses bad arguments with exit 2', () => {
  const dir = tmp()
  const pf = planFile(dir, plan({ postRun: THREE }))
  const ff = filesFile(dir, 'a.txt\n')
  const good = ['--plan', pf, '--when', 'post-merge', '--files-from', ff]
  assert.equal(runCli(['post-run-steps', ...good]).status, 0)
  const without = (flag) => {
    const i = good.indexOf(flag)
    return good.filter((_, k) => k !== i && k !== i + 1)
  }
  for (const flag of ['--plan', '--when', '--files-from']) {
    const r = runCli(['post-run-steps', ...without(flag)])
    assert.equal(r.status, 2, `missing ${flag}: ${r.stderr}`)
  }
  assert.equal(runCli(['post-run-steps', '--plan', pf, '--when', 'later', '--files-from', ff]).status, 2)
  const missing = join(dir, 'does-not-exist.txt')
  assert.equal(existsSync(missing), false)
  assert.equal(runCli(['post-run-steps', '--plan', pf, '--when', 'post-merge', '--files-from', missing]).status, 2)
})

// ---- docs and repo config ----

// Whitespace is collapsed so a phrase wrapped across source lines still counts as one phrase.
const read = (...p) => readFileSync(join(...p), 'utf8')
const flat = (text) => text.replace(/\s+/g, ' ')

test('S14: post-run.md and run-wave SKILL.md name no project rule key; post-run.md names the declared-step surface', () => {
  const postRun = flat(read(PLUGIN_ROOT, 'skills', 'run-wave', 'references', 'post-run.md'))
  const skill = flat(read(PLUGIN_ROOT, 'skills', 'run-wave', 'SKILL.md'))
  const banned = [
    '`live-checks`', 'arbitration rule', '`test-author` rule', '`slug` `test-author`', 'red-proof-results',
    'CHANGELOG', '[Unreleased]', 'plugin-change', 'node claude-plugins/', '`task-scope`, `diagnosis` or `specification`',
  ]
  for (const needle of banned) {
    assert.equal(postRun.includes(needle), false, `post-run.md contains ${needle}`)
    assert.equal(skill.includes(needle), false, `run-wave SKILL.md contains ${needle}`)
  }
  for (const needle of ['post-run-steps', 'meta.review', 'onFail', 'the frozen planning body above any amendment', '## Step 7a', '## Step 7b']) {
    assert.equal(postRun.includes(needle), true, `post-run.md lacks ${needle}`)
  }
})

test('S15: the repo run-profile declares the handoff review object and the two post-merge steps; both rules exist', () => {
  const profile = JSON.parse(read(REPO_ROOT, '.taskorchestrator', 'run-profile.json'))
  assert.deepEqual(profile.review, { mode: 'handoff', skill: 'implement', step: 'wave.md Step 5', onFail: 'stop' })
  assert.deepEqual(profile.postRun.map((s) => s.step), ['live-acceptance', 'red-proofs'])
  assert.deepEqual(profile.postRun.map((s) => s.rule), ['live-checks', 'red-proofs'])
  for (const s of profile.postRun) assert.equal(s.when, 'post-merge')
  assert.ok(profile.postRun[1].command.includes('scripts/redproof-extract.mjs'))
  for (const key of ['red-proofs', 'live-checks']) {
    assert.equal(existsSync(join(REPO_ROOT, '.taskorchestrator', 'rules', `${key}.md`)), true, key)
  }
})
