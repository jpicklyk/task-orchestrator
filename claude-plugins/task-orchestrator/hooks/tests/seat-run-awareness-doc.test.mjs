// Doc-presence checks for item ad3429e6 (scenarios S13-S17 of the item's frozen test-plan; AC1, AC2, AC5, AC6).
// BLIND author: the expected strings are the literals the frozen task-scope (Build A-E) and the declared
// SKILL-doc vocabulary state: error codes seat_forbidden / run_paused / invalid_transition / unauthenticated,
// detail fields allowedSeats / pauseReason / pauseDecisionId, the transition-result key `paused`, the cascade
// entry key `blocked: seat`, and the runs_read selects list / frontier. The documents are read at test runtime;
// nothing below was copied from them. Section-scoped checks use the document's own headings.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = join(HERE, '..', '..')
const read = (...p) => readFileSync(join(PLUGIN, ...p), 'utf8')

const SCHEMA_WORKFLOW = read('skills', 'schema-workflow', 'SKILL.md')
const STATUS_PROGRESSION = read('skills', 'status-progression', 'SKILL.md')
const BATCH_COMPLETE = read('skills', 'batch-complete', 'SKILL.md')
const RUN_WAVE = read('skills', 'run-wave', 'SKILL.md')
const POST_RUN = read('skills', 'run-wave', 'references', 'post-run.md')

/** The markdown table row (a line starting with a pipe) whose FIRST cell contains `head`, or undefined. */
function tableRow(text, head) {
  return text.split('\n').find((l) => l.trimStart().startsWith('|') && (l.split('|')[1] || '').includes(head))
}
/** Text from the first occurrence of `from` to the first occurrence of `to` after it. */
function between(text, from, to) {
  const a = text.indexOf(from)
  assert.ok(a !== -1, `document has "${from}"`)
  const b = text.indexOf(to, a + from.length)
  assert.ok(b !== -1, `document has "${to}" after "${from}"`)
  return text.slice(a, b)
}
function mentionsAll(label, text, needles) {
  for (const needle of needles) assert.ok(text.includes(needle), `${label} mentions ${needle}`)
}

// ---- S13: schema-workflow ------------------------------------------------------------------------

test('S13: schema-workflow names the seat and run error codes, the paused result key, and the coordinator token', () => {
  mentionsAll('schema-workflow', SCHEMA_WORKFLOW, [
    'seat_forbidden', 'run_paused', 'invalid_transition', 'paused', 'allowedSeats', 'pauseReason', 'pauseDecisionId', 'seatToken',
  ])
  assert.match(SCHEMA_WORKFLOW, /coordinator token/i)
})

test('S13: schema-workflow canAdvance context-table row says seat enforcement is excluded', () => {
  const row = tableRow(SCHEMA_WORKFLOW, 'canAdvance')
  assert.ok(row, 'a table row for canAdvance')
  assert.ok(row.includes('seat enforcement'), `row: ${row}`)
})

test('S13: schema-workflow result table has a `paused` row carrying decisionId', () => {
  const row = tableRow(SCHEMA_WORKFLOW, '`paused`')
  assert.ok(row, 'a result-table row for paused')
  assert.ok(row.includes('decisionId'), `row: ${row}`)
})

test('S13: schema-workflow mentions seat_forbidden and run_paused more than once (failure list and Error recovery)', () => {
  for (const code of ['seat_forbidden', 'run_paused']) {
    assert.ok(SCHEMA_WORKFLOW.split(code).length - 1 >= 2, `${code} appears in both the failure list and Error recovery`)
  }
})

test('S13: schema-workflow describes the open-run membership check with runs_read list and frontier', () => {
  mentionsAll('schema-workflow', SCHEMA_WORKFLOW, ['runs_read', 'frontier'])
})

// ---- S14: status-progression -----------------------------------------------------------------------

test('S14: status-progression names seat_forbidden, run_paused, the frontier read and the coordinator token', () => {
  mentionsAll('status-progression', STATUS_PROGRESSION, ['seat_forbidden', 'run_paused', 'frontier', 'runs_read', 'pauseReason', 'seatToken'])
  assert.match(STATUS_PROGRESSION, /coordinator token/i)
})

test('S14: status-progression canAdvance row says seat enforcement is not checked', () => {
  const row = tableRow(STATUS_PROGRESSION, 'canAdvance')
  assert.ok(row, 'a table row for canAdvance')
  assert.ok(row.includes('seat enforcement'), `row: ${row}`)
})

test('S14: status-progression covers resume of a decision-blocked run member under seat_forbidden', () => {
  assert.match(STATUS_PROGRESSION, /resume/)
  assert.match(STATUS_PROGRESSION, /allowedSeats/)
})

// ---- S15: batch-complete -----------------------------------------------------------------------------

test('S15: batch-complete checks run membership and names the blocked, seat and decision cases', () => {
  mentionsAll('batch-complete', BATCH_COMPLETE, [
    'runs_read', 'frontier', 'blocked', 'seat_forbidden', 'unauthenticated', 'awaiting_decision', 'withdraw', 'invalid_transition',
  ])
})

test('S15: batch-complete lists open and paused runs and offers the coordinator token or close-first choices', () => {
  assert.match(BATCH_COMPLETE, /\bopen\b/)
  assert.match(BATCH_COMPLETE, /\bpaused\b/)
  assert.match(BATCH_COMPLETE, /coordinator/i)
  assert.match(BATCH_COMPLETE, /AskUserQuestion/)
})

test('S15: batch-complete outcome table lists seat_forbidden and unauthenticated as failed codes in one row', () => {
  const row = BATCH_COMPLETE.split('\n').find((l) => l.trimStart().startsWith('|') && l.includes('seat_forbidden'))
  assert.ok(row, 'a table row naming seat_forbidden')
  assert.ok(row.includes('unauthenticated'), `row: ${row}`)
})

// ---- S16: post-run.md -------------------------------------------------------------------------------------

test('S16: post-run Step 7 sends the usage heartbeat before it closes the run', () => {
  const step7 = between(POST_RUN, '## Step 7 ', '## Step 7a')
  assert.match(step7, /heartbeat/i, 'Step 7 sends the heartbeat')
  assert.ok(step7.search(/heartbeat/i) < step7.indexOf('--phase close'), 'the heartbeat comes before the close')
})

// Oracle: the frozen test-plan S16 requires the Step 7 section itself to carry the command (`--phase heartbeat`
// with `--item`), not a back-reference to Step 0a.
test('S16: post-run Step 7 section carries the heartbeat command with --phase heartbeat and --item', () => {
  const step7 = between(POST_RUN, '## Step 7 ', '## Step 7a')
  assert.ok(step7.includes('--phase heartbeat'), 'Step 7 carries --phase heartbeat')
  assert.ok(step7.includes('--item'), 'Step 7 names --item')
})

test('S16: post-run Step 0a names the review phase as the condition that moves the heartbeat out of Step 0a', () => {
  const step0a = between(POST_RUN, '## Step 0a', '## Step 0b')
  assert.match(step0a, /review/i)
  assert.ok(step0a.includes('usage.json'), 'Step 0a still writes usage.json')
})

test('S16: post-run Step 0a states the residual wave-parent start risk with a paused report', () => {
  const step0a = between(POST_RUN, '## Step 0a', '## Step 0b')
  assert.ok(step0a.includes('run_paused'), 'the residual names run_paused')
  assert.ok(step0a.includes('paused'), 'the residual names the paused report')
})

// ---- S17: run-wave SKILL.md ---------------------------------------------------------------------------------

test('S17: run-wave Step 5a row tells the coordinator not to launch after a failed assign element', () => {
  const row = tableRow(RUN_WAVE, '5a Assign')
  assert.ok(row, 'a table row for Step 5a')
  assert.match(row, /do not launch/i)
})

test('S17: run-wave tier-2 paragraph names maxActiveSeats and the exit-3 refusal of --method A', () => {
  const start = RUN_WAVE.indexOf('**Director tier 2.**')
  assert.ok(start !== -1, 'the tier-2 paragraph exists')
  const rest = RUN_WAVE.slice(start)
  const end = rest.search(/\n\n/)
  const para = end === -1 ? rest : rest.slice(0, end)
  assert.ok(para.includes('maxActiveSeats'), 'tier-2 paragraph names maxActiveSeats')
  assert.ok(para.includes('exit 3'), 'tier-2 paragraph names the exit-3 refusal')
})

test('S17: run-wave run_at_capacity retry-class row distinguishes Method B from Method A (Step 5a)', () => {
  const row = tableRow(RUN_WAVE, '`run_at_capacity')
  assert.ok(row, 'a retry-class row for run_at_capacity')
  assert.ok(row.includes('Method A'), `row: ${row}`)
  assert.ok(row.includes('Step 5a'), `row: ${row}`)
})

test('S17: run-wave Step 3 says the plan meta.method decides, even when F1 did not pick Method B', () => {
  const row = tableRow(RUN_WAVE, '3 Plan')
  assert.ok(row, 'a table row for Step 3')
  assert.ok(row.includes('maxActiveSeats'), 'Step 3 names maxActiveSeats')
  assert.ok(row.includes('meta.method'), 'Step 3 names meta.method')
})

// ---- AC6: ASCII punctuation (no invalid-UTF-8 / cp1252 bytes in the touched files) --------------------------------------

test('AC6: the touched docs and scripts decode as strict UTF-8 (no replacement character from a stray cp1252 byte)', () => {
  const files = [
    ['skills', 'schema-workflow', 'SKILL.md'],
    ['skills', 'status-progression', 'SKILL.md'],
    ['skills', 'batch-complete', 'SKILL.md'],
    ['skills', 'run-wave', 'SKILL.md'],
    ['skills', 'run-wave', 'references', 'post-run.md'],
    ['workflows', 'implement-wave.js'],
    ['scripts', 'run-exec-lib.mjs'],
    ['scripts', 'run-planner-lib.mjs'],
  ]
  for (const f of files) {
    const buf = readFileSync(join(PLUGIN, ...f))
    assert.doesNotThrow(() => new TextDecoder('utf-8', { fatal: true }).decode(buf), `${f.join('/')} is valid UTF-8`)
  }
})
