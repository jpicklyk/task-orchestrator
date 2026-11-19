// Coordinator tier selection and launch profiles (item dbb45373, scenarios S1-S4 of the item's test-plan).
// BLIND author: written from the frozen task-scope (AC1) and test-plan notes and the supplied
// declarations, never from scripts/coordinator-tiers.mjs. Oracles: task-scope AC1 and the director-layer
// tier table; every expected value is a literal derived from them.
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'

import { TIERS, selectTier, launchProfile } from '../coordinator-tiers.mjs'
import { forClass } from '../lib/dispatch-policy.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const MODULE = join(HERE, '..', 'coordinator-tiers.mjs')

const scratch = mkdtempSync(join(tmpdir(), 'to-tiers-'))
after(() => rmSync(scratch, { recursive: true, force: true }))

// ---- TIERS ----

test('TIERS: a frozen array of tiers 1, 2, 2b, 3 and 4 in order with the declared needs and coordinators', () => {
  assert.ok(Object.isFrozen(TIERS))
  assert.deepEqual(TIERS.map((t) => t.tier), [1, 2, '2b', 3, 4])
  for (const t of TIERS) assert.ok(Object.isFrozen(t), `tier ${t.tier} entry frozen`)
  assert.deepEqual([...TIERS[0].needs], ['start_session', 'SendMessage|send_message'])
  assert.deepEqual([...TIERS[1].needs], ['Workflow'])
  assert.deepEqual([...TIERS[2].needs], ['REPL', 'Agent'])
  assert.deepEqual([...TIERS[3].needs], ['claude CLI'])
  assert.deepEqual([...TIERS[4].needs], [])
  assert.equal(TIERS[0].coordinator, 'desktop session')
  assert.equal(TIERS[4].coordinator, 'inline in the director session')
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
  const c = forClass('coordinator')
  assert.deepEqual(p.argv, ['claude', '-p', 'p', '--allowedTools', 'Workflow', '--max-budget-usd', '5', '--model', c.model, '--effort', c.effort])
  assert.ok(!p.argv.includes('--bare'))
})

test('S3: a model option appends --model <model> and the command stays non-bare', () => {
  const p = launchProfile(3, { prompt: 'p', budgetUsd: 2.5, model: 'sonnet' })
  assert.deepEqual(p.argv, ['claude', '-p', 'p', '--allowedTools', 'Workflow', '--max-budget-usd', '2.5', '--model', 'sonnet', '--effort', forClass('coordinator').effort])
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
  const a = launchProfile(3, { prompt: 'p', budgetUsd: 0.01 }).argv
  assert.equal(a[a.indexOf('--max-budget-usd') + 1], '0.01')
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

test('A1 O6: launchProfile(3) throws an Error naming prompt unless prompt is a non-empty string; the budget check stays first', () => {
  assert.throws(() => launchProfile(3, { budgetUsd: 5 }), (e) => e instanceof Error && /prompt/.test(e.message))
  assert.throws(() => launchProfile(3, { prompt: '', budgetUsd: 5 }), (e) => e instanceof Error && /prompt/.test(e.message))
  assert.throws(() => launchProfile(3, { prompt: 'p' }), (e) => e instanceof Error && /budgetUsd/.test(e.message))
  assert.throws(() => launchProfile(3), (e) => e instanceof Error && /budgetUsd/.test(e.message))
  assert.ok(Array.isArray(launchProfile(3, { prompt: 'p', budgetUsd: 5 }).argv))
})

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

test('A1 O6: CLI profile --tier 3 --budget-usd 5 with no --prompt-file exits non-zero', () => {
  const r = cli(['profile', '--tier', '3', '--budget-usd', '5'])
  assert.notEqual(r.status, 0)
})

test('policy: tier 1 and tier 3 default model and effort to the coordinator class', () => {
  const c = forClass('coordinator')
  const p1 = launchProfile(1, {})
  assert.equal(p1.startSession.model, c.model)
  assert.equal(p1.startSession.effort, c.effort)
  const a = launchProfile(3, { prompt: 'p', budgetUsd: 1, effort: 'low' }).argv
  assert.equal(a[a.indexOf('--model') + 1], c.model)
  assert.equal(a[a.indexOf('--effort') + 1], 'low')
})

// ---- Code Mode: the visible REPL/Poll pair is not the real tool list (item edcc4405) ----

test('Code Mode: a script tool list with Workflow selects tier 2 with no warning', () => {
  const r = selectTier({ tools: ['Workflow', 'Agent', 'SendMessage', 'REPL', 'Poll'] })
  assert.equal(r.tier, 2)
  assert.equal(r.warning, null)
})

test('Code Mode: a bare REPL/Poll list asks for a reprobe, never a silent tier 4', () => {
  for (const claudeCli of [false, true]) {
    for (const tools of [['REPL', 'Poll'], ['REPL'], ['Poll', 'REPL'], ['mcp__x__REPL', 'Poll']]) {
      const r = selectTier({ tools, claudeCli })
      assert.equal(r.tier, null, JSON.stringify(tools))
      assert.equal(r.reprobe, true)
      assert.equal(typeof r.warning, 'string')
      assert.ok(r.warning.length > 0)
    }
  }
})

test('Code Mode: near misses keep the old rule', () => {
  assert.equal(selectTier({ tools: ['Poll'] }).tier, 4)
  assert.equal(selectTier({ tools: ['Agent'] }).tier, 4)
  assert.equal(selectTier({ tools: ['Agent'], claudeCli: true }).tier, 3)
  assert.equal(selectTier({ tools: ['repl', 'Poll'] }).tier, 4)
})

test('Code Mode: a non-reprobe result has no reprobe key', () => {
  for (const r of [
    selectTier({ tools: ['REPL', 'Agent'] }),
    selectTier({ tools: ['Workflow'] }),
    selectTier({ tools: ['start_session', 'SendMessage'] }),
    selectTier({ claudeCli: true }),
    selectTier({}),
  ]) {
    assert.equal(Object.hasOwn(r, 'reprobe'), false)
  }
})

// ---- Tier 2b: Code Mode coordinator subagent running Method B (item 0a68c15f) ----

const BRIEF_DOC = join(HERE, '..', '..', 'skills', 'director', 'references', 'coordinator-tiers.md')
const DIRECTOR_SKILL = join(HERE, '..', '..', 'skills', 'director', 'SKILL.md')
const NL = String.fromCharCode(10)

test('2b selectTier: REPL and Agent selects 2b, also with a claude CLI; Workflow and tier 1 still win', () => {
  assert.deepEqual(selectTier({ tools: ['REPL', 'Agent'] }), { tier: '2b', warning: null })
  assert.equal(selectTier({ tools: ['REPL', 'Agent'], claudeCli: true }).tier, '2b')
  assert.equal(selectTier({ tools: ['REPL', 'Agent', 'Workflow'] }).tier, 2)
  assert.equal(selectTier({ tools: ['REPL', 'Agent', 'start_session', 'SendMessage'] }).tier, 1)
  assert.equal(selectTier({ tools: ['mcp__x__REPL', 'mcp__y__Agent'] }).tier, '2b')
})

test('2b selectTier: Agent alone, REPL alone, lowercase repl and REPL+Poll are not 2b', () => {
  for (const tools of [['Agent'], ['REPL'], ['repl', 'Agent'], ['REPL', 'Poll'], ['REPL', 'agent']]) {
    assert.notEqual(selectTier({ tools }).tier, '2b', JSON.stringify(tools))
  }
  assert.equal(selectTier({ tools: ['Agent'], claudeCli: true }).tier, 3)
})

test('2b launchProfile: the shape is the frozen Agent call and has no background key', () => {
  const c = forClass('coordinator')
  const p = launchProfile('2b', { scope: 'd5592535-d78a-4881-adec-3923ba455b95' })
  assert.deepEqual(Object.keys(p), ['tier', 'agent', 'method', 'reportTo', 'messaging'])
  assert.equal(p.tier, '2b')
  assert.equal(p.method, 'B')
  assert.equal(p.reportTo, 'main')
  assert.equal(p.messaging, 'SendMessage')
  assert.deepEqual(Object.keys(p.agent), ['description', 'subagent_type', 'model', 'effort', 'prompt'])
  assert.equal(p.agent.description, 'coordinator:d5592535')
  assert.equal(p.agent.subagent_type, 'general-purpose')
  assert.equal(p.agent.model, c.model)
  assert.equal(p.agent.effort, c.effort)
  assert.equal(JSON.stringify(p).includes('run_in_background'), false)
  assert.equal(JSON.stringify(p).includes('isolation'), false)
})

test('2b launchProfile: the prompt is 11 lines carrying the Code Mode rules and the launch gate', () => {
  const prompt = launchProfile('2b', { scope: 'S1', target: 'T1' }).agent.prompt
  assert.equal(prompt.split(NL).length, 11)
  assert.equal(prompt.split(NL)[0], 'Run /task-orchestrator:run-wave S1 --method B as a tier-2b coordinator.')
  assert.equal(prompt.includes('--altitude-safe'), false)
  assert.ok(prompt.includes("After a planned report whose needs: line is not nothing, wait for the director's message before the first dispatch round: approved launches, stop closes the run cancelled and reports closed."))
  assert.equal(prompt.includes(String.fromCharCode(96)), false)
  for (const needle of [
    '~600 s REPL script limit', 'never wait inside a script', 'wait on Poll with no script running',
    'task-notification result text', '.output file', 'standalone sleep', 'task-orchestrator:run-wave',
    '--method B', 'references/code-mode.md', '"main"',
    'task-orchestrator:run-wave S1 --method B', '--coordinator coordinator:T1 to provenance',
  ]) {
    assert.ok(prompt.includes(needle), needle)
  }
})

test('2b launchProfile: scope missing or empty throws naming scope; target derivation; overrides', () => {
  for (const o of [undefined, {}, { scope: '' }, { scope: '  ' }, { scope: 5 }, { target: 'x' }]) {
    assert.throws(() => launchProfile('2b', o), /scope/)
  }
  assert.equal(launchProfile('2b', { scope: 'abcdef12-3456 more words' }).agent.description, 'coordinator:abcdef12')
  assert.equal(launchProfile('2b', { scope: 'a-b-c' }).agent.description, 'coordinator:abc')
  assert.equal(launchProfile('2b', { scope: 'x,y' }).agent.description, 'coordinator:x')
  assert.equal(launchProfile('2b', { scope: 'abcdef12', target: 'mine' }).agent.description, 'coordinator:mine')
  const o = launchProfile('2b', { scope: 's', model: 'sonnet', effort: 'low' }).agent
  assert.equal(o.model, 'sonnet')
  assert.equal(o.effort, 'low')
})

test('2b launchProfile: 2B, b, 2.5, "3" and undefined throw with the new expected list', () => {
  for (const t of ['2B', 'b', 2.5, '3', undefined]) {
    assert.throws(() => launchProfile(t, { scope: 's' }), /expected 1, 2, 2b, 3 or 4/, String(t))
  }
})

test('2b CLI: profile --tier 2b --scope prints the launchProfile JSON; --target is honoured', () => {
  const scope = 'd5592535-d78a-4881-adec-3923ba455b95'
  const r = cli(['profile', '--tier', '2b', '--scope', scope])
  assert.equal(r.status, 0, r.stderr)
  assert.deepEqual(JSON.parse(r.stdout), launchProfile('2b', { scope }))
  const t = cli(['profile', '--tier', '2b', '--scope', scope, '--target', 'abc', '--model', 'sonnet'])
  assert.equal(t.status, 0, t.stderr)
  const j = JSON.parse(t.stdout)
  assert.equal(j.agent.description, 'coordinator:abc')
  assert.equal(j.agent.model, 'sonnet')
})

test('2b CLI: without --scope exits non-zero with empty stdout; a tier of 2B fails', () => {
  const r = cli(['profile', '--tier', '2b'])
  assert.notEqual(r.status, 0)
  assert.equal(r.stdout, '')
  const b = cli(['profile', '--tier', '2B', '--scope', 's'])
  assert.notEqual(b.status, 0)
  assert.equal(b.stdout, '')
})

test('2b doc sync: every prompt line appears verbatim in coordinator-tiers.md; the 2b rows exist', () => {
  const doc = readFileSync(BRIEF_DOC, 'utf8')
  const prompt = launchProfile('2b', { scope: '<scope>', target: '<target-short>' }).agent.prompt
  for (const line of prompt.split(NL)) assert.ok(doc.includes(line), line)
  assert.match(doc, /^\| 2b \|/m)
  assert.match(readFileSync(DIRECTOR_SKILL, 'utf8'), /^\| 2b \|/m)
})

test('2b launch gate: the director skill and run-wave SKILL.md state the bootstrap line and the approved or stop answers', () => {
  const line1 = 'Run /task-orchestrator:run-wave <scope> --method B as a tier-2b coordinator.'
  const director = readFileSync(DIRECTOR_SKILL, 'utf8')
  assert.ok(director.includes(line1), 'director skill row 2b names the one-line Agent prompt')
  assert.ok(/Tier 2b: on that `planned`[^.]*`approved` or `stop`/.test(director), 'Waking up names approved and stop')
  const runWave = readFileSync(join(dirname(DIRECTOR_SKILL), '..', 'run-wave', 'SKILL.md'), 'utf8')
  assert.ok(!runWave.includes('never the tier-2 `planned` wait'), 'the old no-wait sentence is gone')
  assert.ok(runWave.includes(line1) && runWave.includes('`approved` or `stop`'))
  const doc = readFileSync(BRIEF_DOC, 'utf8')
  assert.ok(doc.includes('**Launch gate.**') && doc.includes('`approved` or `stop`'))
})
