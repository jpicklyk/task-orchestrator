// Item 331ff0a4: the planned coordinator report names missing allow rules in its `needs:` line.
// Scenario ids S10 and S11 match the item's frozen `test-plan` note. Oracles: parent interface-contract
// item 4 (IC4), task-scope AC5 and planner decision D5: missing = the doc.meta.allow members whose value is
// exactly false, in the order workflow, mcp, bashGit, bashNode; on `planned` with no explicit --needs, any
// missing gives "approve launch; allow rules missing: <n> (<names joined by ", ">)", otherwise today's rule
// (excluded or degradations -> "approve launch", else "nothing"); no meta.allow means none missing.
// Only the public `report` CLI is used. No production source was opened.

import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const CLI = join(PLUGIN, 'scripts', 'run-planner.mjs')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')
const NOW = '2026-09-28T12:00:00Z'

const temps = []
after(() => {
  for (const d of temps) rmSync(d, { recursive: true, force: true })
})

function runCli(args, input) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8', ...(input === undefined ? {} : { input }) })
}

/** A plan doc produced by the real `plan` subcommand from the shipped two-item fixture; meta edited by `edit`. */
function planFile(edit) {
  const dir = mkdtempSync(join(tmpdir(), 'to-launch-allow-'))
  temps.push(dir)
  const snap = readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8')
  const res = runCli(['plan', '--now', NOW, '--scratchpad', join(dir, 'scratchpad')], snap)
  assert.equal(res.status, 0, res.stderr)
  const doc = JSON.parse(res.stdout)
  edit(doc)
  const path = join(dir, 'plan.json')
  writeFileSync(path, JSON.stringify(doc))
  return { dir, path }
}
const EXCLUDED = [{ id: 'cccccccc-3333-4333-8333-333333333333', reason: 'container' }]

function needsOf(path, extra = ['--event', 'planned']) {
  const res = runCli(['report', '--plan', path, ...extra])
  assert.equal(res.status, 0, res.stderr)
  const last = res.stdout.trim().split('\n').pop()
  assert.ok(last.startsWith('needs:'), `last line is the needs line: ${last}`)
  return { line: last, stdout: res.stdout }
}
const allow = (over) => ({ workflow: true, mcp: true, bashGit: true, bashNode: true, ...over })

test('S10 (IC4, D5): two missing rules give the count and the names in member order, with nothing excluded', () => {
  const { path } = planFile((d) => { d.meta.allow = allow({ workflow: false, bashNode: false }) })
  assert.equal(needsOf(path).line, 'needs: approve launch; allow rules missing: 2 (workflow, bashNode)')
})

test('S10 (IC4): the same line when items are also excluded', () => {
  const { path } = planFile((d) => { d.meta.allow = allow({ workflow: false, bashNode: false }); d.meta.excluded = EXCLUDED })
  assert.equal(needsOf(path).line, 'needs: approve launch; allow rules missing: 2 (workflow, bashNode)')
})

test('S10 (D5): a single missing rule reads "1 (<name>)", each member name alone', () => {
  for (const name of ['workflow', 'mcp', 'bashGit', 'bashNode']) {
    const { path } = planFile((d) => { d.meta.allow = allow({ [name]: false }) })
    assert.equal(needsOf(path).line, `needs: approve launch; allow rules missing: 1 (${name})`, name)
  }
})

test('S10 (D5): all four missing are named in order and the 120-character clip does not cut the line', () => {
  const { path } = planFile((d) => { d.meta.allow = { workflow: false, mcp: false, bashGit: false, bashNode: false } })
  assert.equal(needsOf(path).line, 'needs: approve launch; allow rules missing: 4 (workflow, mcp, bashGit, bashNode)')
})

test('S11 (AC5): all rules allowed and an item excluded keeps today\'s "approve launch"; all allowed and a clean plan says nothing', () => {
  const excluded = planFile((d) => { d.meta.allow = allow({}); d.meta.excluded = EXCLUDED })
  assert.equal(needsOf(excluded.path).line, 'needs: approve launch')
  const clean = planFile((d) => { d.meta.allow = allow({}) })
  assert.equal(needsOf(clean.path).line, 'needs: nothing')
})

test('S11 (AC5): a plan with no meta.allow behaves as today, with and without exclusions', () => {
  const clean = planFile((d) => { delete d.meta.allow })
  assert.equal(needsOf(clean.path).line, 'needs: nothing')
  const excluded = planFile((d) => { delete d.meta.allow; d.meta.excluded = EXCLUDED })
  assert.equal(needsOf(excluded.path).line, 'needs: approve launch')
})

test('S11 probe (D5): only a value of exactly false is missing - an absent member is not', () => {
  const { path } = planFile((d) => { d.meta.allow = { workflow: true, bashGit: true, bashNode: true } })
  assert.equal(needsOf(path).line, 'needs: nothing')
  const one = planFile((d) => { d.meta.allow = { workflow: true, bashNode: false } })
  assert.equal(needsOf(one.path).line, 'needs: approve launch; allow rules missing: 1 (bashNode)')
})

test('S11 (D5): an explicit --needs wins over the allow rules', () => {
  const { path } = planFile((d) => { d.meta.allow = allow({ workflow: false }) })
  assert.equal(needsOf(path, ['--event', 'planned', '--needs', 'x']).line, 'needs: x')
})

test('S11 (D5): the closed event ignores meta.allow', () => {
  const { dir, path } = planFile((d) => { d.meta.allow = allow({ workflow: false, mcp: false }) })
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: 'r-202609281200-bbbbbbbb',
    items: [], refused: [], deferred: [],
  }
  const resultPath = join(dir, 'result.json')
  writeFileSync(resultPath, JSON.stringify(result))
  const res = runCli(['report', '--plan', path, '--event', 'closed', '--result', resultPath])
  assert.equal(res.status, 0, res.stderr)
  assert.equal(/allow rules missing/.test(res.stdout), false)
  // fixture condition: the same plan reported as planned does name them
  assert.match(needsOf(path).line, /allow rules missing: 2 \(workflow, mcp\)/)
})
