// Coordinator tier selection and launch profiles (item dbb45373, scenarios S1-S4 of the item's test-plan).
// BLIND author: written from the frozen task-scope (AC1) and test-plan notes and the supplied
// declarations, never from scripts/coordinator-tiers.mjs. Oracles: task-scope AC1 and the director-layer
// tier table; every expected value is a literal derived from them.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'

import { TIERS, selectTier, launchProfile } from '../coordinator-tiers.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const MODULE = join(HERE, '..', 'coordinator-tiers.mjs')

const scratch = mkdtempSync(join(tmpdir(), 'to-tiers-'))
after(() => rmSync(scratch, { recursive: true, force: true }))

// ---- TIERS ----

test('TIERS: a frozen array of tiers 1-4 in order with the declared needs and coordinators', () => {
  assert.ok(Object.isFrozen(TIERS))
  assert.deepEqual(TIERS.map((t) => t.tier), [1, 2, 3, 4])
  for (const t of TIERS) assert.ok(Object.isFrozen(t), `tier ${t.tier} entry frozen`)
  assert.deepEqual([...TIERS[0].needs], ['start_session', 'SendMessage|send_message'])
  assert.deepEqual([...TIERS[1].needs], ['Workflow'])
  assert.deepEqual([...TIERS[2].needs], ['claude CLI'])
  assert.deepEqual([...TIERS[3].needs], [])
  assert.equal(TIERS[0].coordinator, 'desktop session')
  assert.equal(TIERS[3].coordinator, 'inline in the director session')
})

// ---- S1 selectTier ----

test('S1: start_session plus SendMessage selects tier 1 even when Workflow is present, with no warning', () => {
  const r = selectTier({ tools: ['mcp__ccd_session__start_session', 'SendMessage', 'Workflow'] })
  assert.equal(r.tier, 1)
  assert.equal(r.warning, null)
})

test('S1: Workflow alone selects tier 2 with no warning', () => {
  const r = selectTier({ tools: ['Workflow'] })
  assert.equal(r.tier, 2)
  assert.equal(r.warning, null)
})

test('S1: no tools but a claude CLI selects tier 3 with no warning', () => {
  const r = selectTier({ tools: [], claudeCli: true })
  assert.equal(r.tier, 3)
  assert.equal(r.warning, null)
})

test('S1: nothing available selects tier 4 and the warning names the altitude loss', () => {
  for (const arg of [{}, undefined, { tools: [] }, { tools: [], claudeCli: false }]) {
    const r = selectTier(arg)
    assert.equal(r.tier, 4, JSON.stringify(arg))
    assert.equal(typeof r.warning, 'string', JSON.stringify(arg))
    assert.match(r.warning, /altitude/i)
  }
})

test('S1 probe: empty tools and absent tools agree; Workflow plus CLI prefers tier 2; CLI flag must be exactly true', () => {
  assert.equal(selectTier({ tools: [] }).tier, selectTier({}).tier)
  assert.equal(selectTier({ tools: ['Workflow'], claudeCli: true }).tier, 2)
  assert.equal(selectTier({ tools: [], claudeCli: 'true' }).tier, 4)
  assert.equal(selectTier({ tools: [], claudeCli: 1 }).tier, 4)
})

test('S1 probe: tool order and duplicates do not change the tier', () => {
  const a = selectTier({ tools: ['SendMessage', 'Workflow', 'mcp__x__start_session'] })
  const b = selectTier({ tools: ['Workflow', 'Workflow', 'mcp__x__start_session', 'SendMessage', 'SendMessage'] })
  assert.equal(a.tier, 1)
  assert.equal(b.tier, 1)
})

// ---- S2 name matching ----

test('S2: a bare __start_session-suffixed tool alone is not tier 1; both halves are required', () => {
  assert.notEqual(selectTier({ tools: ['x__start_session'] }).tier, 1)
  assert.notEqual(selectTier({ tools: ['SendMessage'] }).tier, 1)
  assert.notEqual(selectTier({ tools: ['send_message'] }).tier, 1)
})

test('S2: near-miss names do not match: start_session_v2, xstart_session, single underscore prefix', () => {
  assert.notEqual(selectTier({ tools: ['start_session_v2', 'send_message'] }).tier, 1)
  assert.notEqual(selectTier({ tools: ['xstart_session', 'SendMessage'] }).tier, 1)
  assert.notEqual(selectTier({ tools: ['a_start_session', 'SendMessage'] }).tier, 1)
  assert.notEqual(selectTier({ tools: ['start_session_x__', 'SendMessage'] }).tier, 1)
})

test('S2: a prefixed start_session with the lowercase send_message alternative is tier 1', () => {
  assert.equal(selectTier({ tools: ['a__start_session', 'send_message'] }).tier, 1)
  assert.equal(selectTier({ tools: ['start_session', 'SendMessage'] }).tier, 1)
  assert.equal(selectTier({ tools: ['a__start_session', 'b__send_message'] }).tier, 1)
})

test('S2: matching is case-sensitive and the send alternative is also suffix-matched', () => {
  assert.notEqual(selectTier({ tools: ['a__Start_Session', 'SendMessage'] }).tier, 1)
  assert.notEqual(selectTier({ tools: ['a__start_session', 'sendmessage'] }).tier, 1)
  assert.equal(selectTier({ tools: ['a__start_session', 'q__SendMessage'] }).tier, 1)
  assert.equal(selectTier({ tools: ['mcp__plugin__Workflow'] }).tier, 2)
  assert.notEqual(selectTier({ tools: ['workflow'] }).tier, 2)
  assert.notEqual(selectTier({ tools: ['MyWorkflow'] }).tier, 2)
})

// ---- S3 tier 3 argv ----

test('S3: tier 3 argv is the headless non-bare Workflow-only command with the budget cap', () => {
  const p = launchProfile(3, { prompt: 'p', budgetUsd: 5 })
  assert.equal(p.tier, 3)
  assert.deepEqual(p.argv, ['claude', '-p', 'p', '--allowedTools', 'Workflow', '--max-budget-usd', '5'])
  assert.ok(!p.argv.includes('--bare'))
})

test('S3: a model option appends --model <model> and the command stays non-bare', () => {
  const p = launchProfile(3, { prompt: 'p', budgetUsd: 2.5, model: 'sonnet' })
  assert.deepEqual(p.argv, ['claude', '-p', 'p', '--allowedTools', 'Workflow', '--max-budget-usd', '2.5', '--model', 'sonnet'])
  assert.ok(!p.argv.includes('--bare'))
})

test('S3 probe: budgetUsd absent, 0, negative, NaN, Infinity or a string throws an Error naming budgetUsd', () => {
  const valid = launchProfile(3, { prompt: 'p', budgetUsd: 5 })
  assert.equal(valid.tier, 3, 'control: the same call with a valid budget succeeds, so the throws below come from the budget')
  const bads = [undefined, 0, -1, NaN, Infinity, -Infinity, '5', null, true]
  for (const bad of bads) {
    assert.throws(
      () => launchProfile(3, { prompt: 'p', budgetUsd: bad }),
      (e) => e instanceof Error && /budgetUsd/.test(e.message),
      `budgetUsd=${String(bad)}`,
    )
  }
  assert.throws(() => launchProfile(3, { prompt: 'p' }), (e) => e instanceof Error && /budgetUsd/.test(e.message))
  assert.throws(() => launchProfile(3), (e) => e instanceof Error && /budgetUsd/.test(e.message))
})

test('S3 probe: a tiny positive budget is accepted and rendered with String()', () => {
  assert.deepEqual(launchProfile(3, { prompt: 'p', budgetUsd: 0.01 }).argv.slice(-2), ['--max-budget-usd', '0.01'])
})

// ---- S4 other tiers ----

test('S4: tier 1 profile carries the desktop start_session shape and accepts cross-session input', () => {
  const p = launchProfile(1, { model: 'opus', effort: 'high', permissionMode: 'acceptEdits' })
  assert.equal(p.tier, 1)
  assert.equal(p.crossSessionInbound, 'accept')
  assert.equal(p.messaging, 'SendMessage')
  assert.deepEqual(p.startSession, {
    initiation: 'user_asked', context: 'fresh', model: 'opus', effort: 'high', permission_mode: 'acceptEdits',
  })
  assert.equal(p.outputStyle, null)
  assert.equal(launchProfile(1, { outputStyle: 'workflow-analyst' }).outputStyle, 'workflow-analyst')
})

test('S4: tier 2 profile is the implement-wave Workflow call with altitudeSafe true', () => {
  assert.deepEqual(launchProfile(2), { tier: 2, workflow: 'task-orchestrator:implement-wave', args: { altitudeSafe: true } })
})

test('S4: tier 4 profile is inline with an altitude warning', () => {
  const p = launchProfile(4)
  assert.equal(p.tier, 4)
  assert.equal(p.inline, true)
  assert.match(p.warning, /altitude/i)
})

test('S4: an unknown tier throws', () => {
  for (const bad of [0, 5, 9, -1, '1', undefined, null, NaN]) {
    assert.throws(() => launchProfile(bad), Error, String(bad))
  }
})

// ---- CLI ----

function cli(args) {
  return spawnSync(process.execPath, [MODULE, ...args], { encoding: 'utf8' })
}

test('CLI: profile --tier 2 prints the tier 2 profile as JSON', () => {
  const r = cli(['profile', '--tier', '2'])
  assert.equal(r.status, 0, r.stderr)
  assert.deepEqual(JSON.parse(r.stdout), { tier: 2, workflow: 'task-orchestrator:implement-wave', args: { altitudeSafe: true } })
})

test('CLI: profile --tier 3 reads the prompt from --prompt-file and honours --budget-usd and --model', () => {
  const promptFile = join(scratch, 'prompt.txt')
  writeFileSync(promptFile, 'run the wave please')
  const r = cli(['profile', '--tier', '3', '--budget-usd', '7', '--model', 'sonnet', '--prompt-file', promptFile])
  assert.equal(r.status, 0, r.stderr)
  const p = JSON.parse(r.stdout)
  assert.equal(p.tier, 3)
  assert.equal(p.argv[0], 'claude')
  assert.equal(p.argv[1], '-p')
  assert.equal(p.argv[2], 'run the wave please')
  assert.equal(p.argv[p.argv.indexOf('--max-budget-usd') + 1], '7')
  assert.equal(p.argv[p.argv.indexOf('--model') + 1], 'sonnet')
  assert.equal(p.argv[p.argv.indexOf('--allowedTools') + 1], 'Workflow')
  assert.ok(!p.argv.includes('--bare'))
})

test('CLI: tier 3 without a budget fails with a non-zero exit instead of printing a profile', () => {
  const promptFile = join(scratch, 'prompt2.txt')
  writeFileSync(promptFile, 'x')
  const r = cli(['profile', '--tier', '3', '--prompt-file', promptFile])
  assert.notEqual(r.status, 0)
  assert.equal(r.stdout.trim(), '')
})

test('purity: importing the module prints nothing and runs no subcommand', () => {
  const r = spawnSync(
    process.execPath,
    ['--input-type=module', '-e', `await import(${JSON.stringify(pathToFileURL(MODULE).href)})`],
    { encoding: 'utf8' },
  )
  assert.equal(r.status, 0, r.stderr)
  assert.equal(r.stdout, '')
})