// Item 6f4e4c77, scenario S12 and the documentation clauses of R2, R3 and R7: the schema audit reports each served bundled rule key by its
// hash against the manifest, and the docs state the three classes of rule.
//
// Oracles: task-scope R3 ("current (nothing), a known older hash (a stale copy, with the fix), an unknown hash (project-authored; flagged
// when the key is a protocol rule). A finding, never an error. The audit reads the manifest by a path relative to its own skill folder"),
// D8 (audit-workflow.md Stage 2 step names ../../bundled-rules/manifest.json and ../../hooks/lib/seat-rules.json from the skill folder;
// 'stale copy', 'project-authored', 'protocol rule', 'never an error'), R2 (the docs say what replacing a protocol rule's text costs: the
// copy stops receiving plugin updates), R7 (the Rule row of the concept table reads "A short must-do for a seat's conduct"; the paragraph
// after the table names the three classes of R1 once; the plugin CLAUDE.md takes the clause and says the root section states the classes).
// The documents are read at run time; this author did not open them. Needles are the literals the rulings give.
// Forbidden-construct declaration: none.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const REPO = resolve(PLUGIN, '..', '..')
const SKILL_FOLDER = join(PLUGIN, 'skills', 'manage-schemas')
const AUDIT = join(SKILL_FOLDER, 'references', 'audit-workflow.md')
const norm = (s) => s.split('\r\n').join('\n')
const read = (p) => norm(readFileSync(p, 'utf8'))

test('S12 (R3, D8): audit-workflow.md names the manifest and the seat rule table by a path relative to the skill folder, and both resolve', () => {
  const text = read(AUDIT)
  for (const rel of ['../../bundled-rules/manifest.json', '../../hooks/lib/seat-rules.json']) {
    assert.ok(text.includes(rel), `audit-workflow.md names ${rel}`)
    assert.ok(existsSync(resolve(SKILL_FOLDER, rel)), `${rel} resolves from the skill folder`)
  }
})

test('S12 (R3, D8): audit-workflow.md carries the three outcome words and the never-an-error statement', () => {
  const text = read(AUDIT)
  for (const needle of ['stale copy', 'project-authored', 'protocol rule', 'never an error']) {
    assert.ok(text.includes(needle), `audit-workflow.md says "${needle}"`)
  }
})

test('S12 (R3): the audit reads the manifest itself: the manifest is a key-to-hash-list map whose last hash is the current one', () => {
  const manifest = JSON.parse(read(resolve(SKILL_FOLDER, '../../bundled-rules/manifest.json')))
  const keys = Object.keys(manifest)
  assert.ok(keys.length >= 3, 'fixture: the manifest lists keys')
  for (const key of ['protocol.entry-seat', 'protocol.in-phase-seat', 'protocol.read-only-agent']) {
    assert.ok(Array.isArray(manifest[key]) && manifest[key].length >= 1, `${key} has a hash list`)
  }
  const table = JSON.parse(read(resolve(SKILL_FOLDER, '../../hooks/lib/seat-rules.json')))
  const protocolKeys = new Set(Object.values(table.roles).map((r) => r.protocol))
  assert.deepEqual([...protocolKeys].sort(), ['protocol.entry-seat', 'protocol.in-phase-seat', 'protocol.read-only-agent'], 'the table names the protocol keys the audit flags')
})

// ---- R7 -------------------------------------------------------------------------------------------------------------

test("R7: the root CLAUDE.md Rule row reads 'A short must-do for a seat's conduct' and the paragraph after the concept table names protocol, plugin-chosen and default rules", () => {
  const text = read(join(REPO, 'CLAUDE.md'))
  const row = text.split('\n').find((l) => /^\|\s*Rule\s*\|/.test(l))
  assert.ok(row, 'the concept table has a Rule row')
  const cell = row.split('|')[2].trim()
  assert.equal(cell, "A short must-do for a seat's conduct")
  const afterTable = text.slice(text.indexOf(row))
  const paragraph = afterTable.split('\n').slice(1).join('\n').split('\n\n').find((p, i) => i > 0 && /plugin-chosen/i.test(p))
  assert.ok(paragraph, 'a paragraph after the table names the plugin-chosen class')
  assert.match(paragraph, /protocol/i)
  assert.match(paragraph, /default/i)
})

test("R7: claude-plugins/CLAUDE.md takes the clause and points at the root section for the three classes", () => {
  const text = read(join(REPO, 'claude-plugins', 'CLAUDE.md'))
  assert.ok(text.includes("short must-do for a seat's conduct"), 'the R7 clause')
  assert.ok(text.includes('three classes'), 'says the root section states the three classes')
  assert.equal(text.includes('binding protocol'), false, 'the old Rule wording is gone')
})

// ---- R2 -------------------------------------------------------------------------------------------------------------

for (const [label, file] of [
  ['config-format.md', join(SKILL_FOLDER, 'references', 'config-format.md')],
  ['current/docs/config-reference.md', join(REPO, 'current', 'docs', 'config-reference.md')],
]) {
  test(`R2: ${label} says that a project's own copy of a protocol rule stops receiving plugin updates`, () => {
    assert.match(read(file), /plugin updates/i)
  })
}
