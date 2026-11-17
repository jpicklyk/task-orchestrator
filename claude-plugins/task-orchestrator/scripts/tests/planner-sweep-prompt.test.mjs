// Planner existing-test sweep line in the run-wave seat prompts (#635).
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { loadCore, planFixture, itemFixture, stages } from './workflow-harness.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SCRIPT_PATH = join(HERE, '..', '..', 'workflows', 'implement-wave.js')
const LINE =
  'existingTestEdits: include every existing test that pins the size, order or matcher list of an enumerated ' +
  'surface this item changes; list every hit file in your planning note with one disposition (edit, with the line; ' +
  'or no edit, with the reason) and report each grep in sweep as kind tests with its hits and disposed counts, ' +
  'including 0 hits.'
const DOCS_LINE =
  'Docs sweep: for every script, hook, tool or command this item changes or adds, grep its file name and ' +
  'user-facing name across the whole repository; give every hit file one disposition in ' +
  'your planning note (edit in this item; owned by sibling <short>; no edit, with the reason; unowned) and ' +
  'report the grep in sweep as kind docs, listing unowned hit files in unowned.'
const EMPTY_SWEEP_LINE =
  'sweep: [] only when this item changes no enumerated surface and no script; otherwise one entry per grep, 0 hits included.'
const SCENARIO_LINE = 'testPlanScenarios: list every scenario id your test-plan note numbers, in order, one id per entry, written exactly as the test plan writes it, never a range.'

const countOf = (line) => (text) => text.split(line).length - 1
const count = countOf(LINE)
const docsCount = countOf(DOCS_LINE)
const scenarioCount = countOf(SCENARIO_LINE)
const emptySweepCount = countOf(EMPTY_SWEEP_LINE)

for (const [name, make] of [['featureTaskLike', stages.featureTaskLike], ['bugFixLike', stages.bugFixLike]]) {
  test(`planner prompt carries the enumerated-surface sweep line exactly once (${name})`, () => {
    const core = loadCore(SCRIPT_PATH)
    const item = itemFixture({ short: 'sweep01', stages: make() })
    const plan = planFixture({ items: [item] })
    const planner = item.stages.find((s) => s.output === 'planner-v1')
    const prompt = core.seatPrompt(plan, item, planner, {})
    assert.equal(count(prompt), 1)
    assert.equal(docsCount(prompt), 1)
    assert.equal(emptySweepCount(prompt), 1)
  })

  test(`non-planner prompts do not carry the sweep line (${name})`, () => {
    const core = loadCore(SCRIPT_PATH)
    const item = itemFixture({ short: 'sweep02', stages: make() })
    const plan = planFixture({ items: [item] })
    const others = item.stages.filter((s) => s.output !== 'planner-v1')
    assert.ok(others.length > 0)
    for (const st of others) {
      const prompt = core.seatPrompt(plan, item, st, {})
      assert.equal(count(prompt), 0, st.output)
      assert.equal(docsCount(prompt), 0, st.output)
      assert.equal(emptySweepCount(prompt), 0, st.output)
    }
  })
}

test('bugFixLike carries a test-author stage and featureTaskLike does not (both conditional sides covered)', () => {
  assert.ok(stages.bugFixLike().some((s) => s.output === 'test-author-v1'))
  assert.ok(!stages.featureTaskLike().some((s) => s.output === 'test-author-v1'))
})

test('the testPlanScenarios line is on a planner prompt only with a test-author stage', () => {
  const core = loadCore(SCRIPT_PATH)
  const withAuthor = itemFixture({ short: 'sweep03', stages: stages.bugFixLike() })
  const without = itemFixture({ short: 'sweep04', stages: stages.featureTaskLike() })
  for (const [item, expected] of [[withAuthor, 1], [without, 0]]) {
    const plan = planFixture({ items: [item] })
    for (const st of item.stages) {
      const want = st.output === 'planner-v1' ? expected : 0
      assert.equal(scenarioCount(core.seatPrompt(plan, item, st, {})), want, `${item.short} ${st.output}`)
    }
  }
})
