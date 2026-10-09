// Coverage for the interrupted-attempt recovery clause in writing-seat prompts (item 983cb945).
// Only exported surfaces of workflows/implement-wave.js (via workflow-harness.mjs) are used.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import fs from 'node:fs'

import { loadCore, planFixture, itemFixture, stages } from './workflow-harness.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = join(HERE, '..', '..')
const SCRIPT_PATH = join(PLUGIN, 'workflows', 'implement-wave.js')
const HEAD = 'INTERRUPTED-ATTEMPT CHECK'

function prompts(planOverrides = {}) {
  const core = loadCore(SCRIPT_PATH)
  const it = itemFixture({ short: 'iaaaaaaa', stages: stages.bugFixLike(), traits: ['delegated'] })
  const p = planFixture({ items: [it], ...planOverrides })
  const out = {}
  for (const s of it.stages) out[s.seat] = core.seatPrompt(p, it, s, {})
  return out
}

test('I1: writing seats (implementer, test-author) carry the clause; planner and extractor do not', () => {
  const out = prompts()
  assert.ok(out.implementer.includes(HEAD))
  assert.ok(out['test-author'].includes(HEAD))
  assert.ok(!out.planner.includes(HEAD))
  assert.ok(!out['declarations-extractor'].includes(HEAD))
})

test('I2: the clause comes after the RERUN CHECK and names the snapshot ref and temporary index', () => {
  const t = prompts().implementer
  assert.ok(t.indexOf(HEAD) > t.indexOf('RERUN CHECK'))
  assert.ok(t.includes('refs/wip/iaaaaaaa-implementer'))
  assert.ok(t.includes('GIT_INDEX_FILE'))
  assert.ok(t.includes('commit-tree -p HEAD'))
})

test('I3: the clause forbids discarding changes wholesale', () => {
  const t = prompts().implementer
  assert.ok(t.includes('Never discard them wholesale'))
  for (const w of ['checkout -- .', 'reset --hard', 'clean', 'stash']) assert.ok(t.includes(w), w)
})

test('I4: shared mode scopes to owned files and reports foreign files; per-item mode adopts after earlier seats committed', () => {
  const shared = prompts({ worktreeMode: 'shared' }).implementer
  assert.ok(shared.includes('only uncommitted changes in files you own'))
  assert.ok(shared.includes('never touch a foreign uncommitted file'))
  const per = prompts({ worktreeMode: 'per-item' }).implementer
  assert.ok(per.includes('earlier writing seats of this item have committed'))
  assert.ok(!per.includes('never touch a foreign uncommitted file'))
})

test('I5: the clause tells the seat to report interruptedAttempt and wipRef', () => {
  const t = prompts().implementer
  assert.ok(t.includes('interruptedAttempt: true'))
  assert.ok(t.includes('wipRef'))
})

test('I6: envelope schema declares optional interruptedAttempt and wipRef, not required', () => {
  const core = loadCore(SCRIPT_PATH)
  const sch = core.envelopeSchema('implementer-v1', {}, { enters: true })
  assert.equal(sch.properties.interruptedAttempt.type, 'boolean')
  assert.equal(sch.properties.wipRef.type, 'string')
  assert.ok(!sch.required.includes('interruptedAttempt'))
  assert.ok(!sch.required.includes('wipRef'))
})

test('I7: interruptedAttempt true maps to done, not a failure', () => {
  const core = loadCore(SCRIPT_PATH)
  const st = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: [], dispatch: {}, output: 'implementer-v1' }
  const r = core.mapStageResult(
    st,
    { status: 'done', reason: 'ok', interruptedAttempt: true, wipRef: 'refs/wip/iaaaaaaa-implementer', entry: { applied: false, alreadyInPhase: true } },
    'seat',
  )
  assert.equal(r.status, 'done')
})

test('I8: orchestrator docs carry the snapshot and cleanup steps', () => {
  const read = (p) => fs.readFileSync(join(PLUGIN, ...p), 'utf8')
  const skill = read(['skills', 'run-wave', 'SKILL.md'])
  assert.equal((skill.match(/refs\/wip\/<short>/g) || []).length >= 2, true, 'both launched rows')
  assert.ok(read(['skills', 'run-wave', 'references', 'method-b.md']).includes('refs/wip/<short>'))
  assert.ok(read(['skills', 'run-wave', 'references', 'post-run.md']).includes('refs/wip/<short>'))
})
