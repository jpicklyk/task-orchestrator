// Item 13b2fdaf (F1-B): doc contracts S10 and S11 of the frozen test-plan.
// Oracle: task-scope AC3/D5 (the reviewer agent follows the note's `skillPointers` in order and fetches a served rule by
// `config` `rules`; it no longer says the first skill is the one it gets first) and AC5/D6 (config-format.md's Rule text
// section says a rule-naming skill reaches the seat on every phase, not only queue- or work-phase, and no longer says such
// a skill is not fetched as a rule). Docs are read with CRLF normalised to LF.
// Fixture condition for each absence assertion: the paired inclusion assertion in the same file fails on the old wording.
// Forbidden-construct declaration: none.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const read = (...p) => readFileSync(join(PLUGIN, ...p), 'utf8').replace(/\r\n/g, '\n')

test('S10: agents/reviewer.md has the reviewer follow skillPointers in order and fetch served rules with config rules', () => {
  const text = read('agents', 'reviewer.md')
  assert.ok(text.includes('`skillPointers`'), 'names skillPointers')
  assert.ok(text.includes('in order'), 'in order')
  assert.ok(text.includes('`config` `rules`'), 'fetches a served rule with config rules')
  assert.equal(text.includes('gets that skill first'), false, 'the first-skill-only wording is gone')
})

test('S11: config-format.md Rule text section says every phase and drops the queue- or work-phase and not-fetched wording', () => {
  const doc = read('skills', 'manage-schemas', 'references', 'config-format.md')
  const start = doc.indexOf('### Rule text')
  assert.ok(start !== -1, 'the Rule text heading exists')
  const next = doc.indexOf('\n### ', start + 1)
  const section = next === -1 ? doc.slice(start) : doc.slice(start, next)
  assert.ok(section.includes('every phase'), 'says every phase')
  assert.equal(section.includes('not fetched as rules'), false)
  assert.equal(section.includes('queue- or work-phase'), false)
})
