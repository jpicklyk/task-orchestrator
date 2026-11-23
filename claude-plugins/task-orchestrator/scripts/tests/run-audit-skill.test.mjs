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

// T8b D16: run-audit launches with a digest-v1 measured input; twelve distinct candidates fill the largest preset's
// candidate reviewers (candidateCount 12), so every preset projects its full candidate term.
function measuredWith12() {
  return {
    contract: 'audit/digest-v1',
    window: { since: '2025-11-22', recent: '2026-08-22' },
    headSha: 'abc123',
    metricsPath: '/w/metrics.json',
    hotSet: [], topComplexity: [], cycles: [], unstableModules: [], coupling: [], trend: [], notes: [],
    candidates: Array.from({ length: 12 }, (_, i) => ({ candidateId: 'cand-' + (i + 1), kind: 'hotspot', subject: ['src/m' + (i + 1)], facts: { churn: i } })),
  }
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
    measured: measuredWith12(),
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
    key, String(p.reviewerCount), String(p.gaps.max), String(p.candidateCount), String(p.verify.maxFindings),
    String(total(key, true)), String(total(key, false)),
  ])
  assert.equal(expected.length, 3)
  assert.deepEqual(tableRows('Presets'), expected)
  // the projections by hand (task-orchestrator D15, AC8): with items / without items, 12 candidates measured
  assert.deepEqual(expected.map((r) => r.slice(5)), [['24', '17'], ['60', '47'], ['128', '102']])
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

// T9 S22/S23 (D13): the Presets table gained a Candidates column (the edit to S1 above), and the skill names the
// measuring and comparison steps. Oracles: task-scope D13 and the C5 candidate counts (quick 3, standard 6, full 12).

test('S22 the Candidates column holds 3, 6 and 12 for quick, standard and full', () => {
  const rows = tableRows('Presets')
  assert.deepEqual(Object.fromEntries(rows.map((r) => [r[0], r[3]])), { quick: '3', standard: '6', full: '12' })
})

test('S23 the skill names the confirm and metrics scripts, the digest and candidate flags and the previous-result comparison', () => {
  for (const needle of ['audit-confirm.mjs', 'audit-metrics.mjs', '--digest-out', '--candidates', '--previous']) {
    assert.ok(SKILL.includes(needle), `names ${needle}`)
  }
})
