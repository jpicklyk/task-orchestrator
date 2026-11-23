// Guard test for skills/run-audit/SKILL.md (item 0156b1c6). The skill copies two workflow
// values (the preset limits with their projected agent counts, and the phase-key to class
// map); these scenarios pin each copy to the workflow's own constants so a change to either
// side fails here. Tables are parsed by heading and compared as exact row sets.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'

import { loadCoreNamed } from './workflow-harness-ext.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const SKILL = readFileSync(join(PLUGIN, 'skills', 'run-audit', 'SKILL.md'), 'utf8')
const POLICY = JSON.parse(readFileSync(join(PLUGIN, 'dispatch-policy.json'), 'utf8'))
const CORE = loadCoreNamed(join(PLUGIN, 'workflows', 'deep-codebase-audit.js'), [
  'normalizeArgs', 'projectAgents', 'PRESETS', 'DISPATCH_CLASSES',
])

/** tableRows(heading) -> array of cell arrays for the table under `### <heading>` (header and rule rows dropped) */
function tableRows(heading) {
  const lines = SKILL.split('\n')
  const at = lines.findIndex((l) => l.trim() === `### ${heading}`)
  assert.ok(at >= 0, `heading "### ${heading}" present`)
  const rows = []
  for (let i = at + 1; i < lines.length; i++) {
    const l = lines[i].trim()
    if (l.startsWith('#')) break
    if (!l.startsWith('|')) {
      if (rows.length) break
      continue
    }
    rows.push(l.replace(/^\||\|$/g, '').split('|').map((c) => c.trim()))
  }
  return rows.slice(2)
}

function argsFor(preset, withRoot) {
  const a = {
    contract: 'audit/args-v2',
    runId: 'a-202601010000',
    date: '2026-01-01',
    repo: { root: '/r' },
    scope: { paths: ['.'] },
    preset,
    scriptsDir: '/s',
    workDir: '/w',
    reportPath: '/w/report.html',
    dispatchClasses: POLICY.classes,
  }
  if (withRoot) a.items = { rootId: 'ce064d2c-7c03-4eac-ae7d-89a14c2ba273' }
  return a
}

function total(preset, withRoot) {
  const n = CORE.normalizeArgs(argsFor(preset, withRoot))
  assert.equal(n.ok, true, n.reason)
  return CORE.projectAgents(n.plan).total
}

test('S1 Presets rows equal the workflow presets and projections', () => {
  const expected = Object.entries(CORE.PRESETS).map(([key, p]) => [
    key, String(p.reviewerCount), String(p.gaps.max), String(p.verify.maxFindings),
    String(total(key, true)), String(total(key, false)),
  ])
  assert.equal(expected.length, 3)
  assert.deepEqual(tableRows('Presets'), expected)
})

test('S2 Dispatch rows equal the workflow phase-key map in order', () => {
  const expected = Object.entries(CORE.DISPATCH_CLASSES).map(([k, c]) => [k, c])
  assert.ok(expected.length > 0)
  assert.deepEqual(tableRows('Dispatch'), expected)
})

test('S3 the skill names its contract, workflow, policy file and renderer', () => {
  for (const needle of [
    'task-orchestrator:deep-codebase-audit', 'audit/args-v2', 'dispatch-policy.json', 'audit-report-html.mjs',
  ]) {
    assert.ok(SKILL.includes(needle), `names ${needle}`)
  }
})
