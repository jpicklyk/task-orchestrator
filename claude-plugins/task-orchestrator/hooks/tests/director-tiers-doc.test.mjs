// Doc-presence checks for item dbb45373 (scenario S5 of the item's test-plan; AC1, AC3, AC6, AC8). BLIND
// author: the expected strings are the literals the frozen task-scope states for each document. The
// documents are read at test runtime; nothing below was copied from them.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = join(HERE, '..', '..')
const read = (...p) => readFileSync(join(PLUGIN, ...p), 'utf8')

const TIERS_DOC = ['skills', 'director', 'references', 'coordinator-tiers.md']

test('S5: coordinator-tiers.md exists and has one table row per tier 1-4', () => {
  assert.ok(existsSync(join(PLUGIN, ...TIERS_DOC)), 'skills/director/references/coordinator-tiers.md')
  const t = read(...TIERS_DOC)
  for (const n of [1, 2, 3, 4]) assert.match(t, new RegExp(`^\\| ${n} \\|`, 'm'), `table row for tier ${n}`)
})

test('S5: coordinator-tiers.md carries the launch-profile facts: crossSessionInbound, budget cap, no --bare, altitudeSafe', () => {
  const t = read(...TIERS_DOC)
  for (const needle of [
    'crossSessionInbound', 'get_settings', 'set_session_output_style', '--max-budget-usd', '--bare',
    'altitudeSafe', '--altitude-safe', 'run_in_background', 'SendMessage', 'TASK_ORCHESTRATOR_MODE', 'start_session',
  ]) {
    assert.ok(t.includes(needle), `coordinator-tiers.md mentions ${needle}`)
  }
  assert.match(t, /altitude/i, 'the tier-4 warning')
})

test('S5: the director SKILL.md links coordinator-tiers.md and keeps its own tier table', () => {
  const s = read('skills', 'director', 'SKILL.md')
  assert.ok(s.includes('coordinator-tiers.md'), 'SKILL.md links the tiers reference')
  assert.match(s, /runs_read/, 'SKILL.md names runs_read for the wake-up read')
  assert.match(s, /wake/i, 'SKILL.md describes the wake-up read')
})

test('S5: the run-wave SKILL.md carries the tier-2 hand-off paragraph with --altitude-safe', () => {
  const s = read('skills', 'run-wave', 'SKILL.md')
  assert.ok(s.includes('--altitude-safe'), 'run-wave SKILL.md mentions --altitude-safe')
  assert.match(s, /coordinator/i)
})

test('S5: the implementer agent documents the deviation raise protocol', () => {
  const t = read('agents', 'implementer.md')
  for (const needle of ['runs_write', 'raise', 'deviation', 'block: true', 'dedupeKey', 'awaiting_decision', '## Deviations requested']) {
    assert.ok(t.includes(needle), `implementer.md mentions ${needle}`)
  }
})

test('S5: the CHANGELOG records director-watch under a ### Plugin heading', () => {
  const t = readFileSync(join(PLUGIN, '..', '..', 'CHANGELOG.md'), 'utf8')
  const at = t.indexOf('director-watch')
  assert.ok(at >= 0, 'CHANGELOG.md mentions director-watch')
  const before = t.slice(0, at).split('\n').filter((l) => l.startsWith('### '))
  assert.equal(before[before.length - 1], '### Plugin')
})

test('S5: the hooks README has a director-watch row and the director-guard row names the new file tools', () => {
  const lines = read('hooks', 'README.md').split('\n')
  const watch = lines.filter((l) => l.startsWith('|') && l.includes('director-watch'))
  assert.ok(watch.length >= 1, 'a table row for director-watch')
  assert.ok(watch.some((l) => l.includes('runs_read')), 'the director-watch row names runs_read')
  const guard = lines.filter((l) => l.startsWith('|') && l.includes('director-guard'))
  assert.ok(guard.some((l) => l.includes('Grep') && l.includes('Glob') && l.includes('NotebookEdit')), 'the director-guard row names Grep, Glob and NotebookEdit')
})