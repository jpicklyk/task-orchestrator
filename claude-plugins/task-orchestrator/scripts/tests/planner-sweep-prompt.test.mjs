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
  'surface this item changes; record the grep and its hit count in your planning note, including 0 hits.'

const count = (text) => text.split(LINE).length - 1

for (const [name, make] of [['featureTaskLike', stages.featureTaskLike], ['bugFixLike', stages.bugFixLike]]) {
  test(`planner prompt carries the enumerated-surface sweep line exactly once (${name})`, () => {
    const core = loadCore(SCRIPT_PATH)
    const item = itemFixture({ short: 'sweep01', stages: make() })
    const plan = planFixture({ items: [item] })
    const planner = item.stages.find((s) => s.output === 'planner-v1')
    assert.equal(count(core.seatPrompt(plan, item, planner, {})), 1)
  })

  test(`non-planner prompts do not carry the sweep line (${name})`, () => {
    const core = loadCore(SCRIPT_PATH)
    const item = itemFixture({ short: 'sweep02', stages: make() })
    const plan = planFixture({ items: [item] })
    const others = item.stages.filter((s) => s.output !== 'planner-v1')
    assert.ok(others.length > 0)
    for (const st of others) assert.equal(count(core.seatPrompt(plan, item, st, {})), 0, st.output)
  })
}

test('bugFixLike carries a test-author stage and featureTaskLike does not (both conditional sides covered)', () => {
  assert.ok(stages.bugFixLike().some((s) => s.output === 'test-author-v1'))
  assert.ok(!stages.featureTaskLike().some((s) => s.output === 'test-author-v1'))
})
