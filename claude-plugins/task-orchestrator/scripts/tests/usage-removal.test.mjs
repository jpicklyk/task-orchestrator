// Regression coverage for item 798240c1: the plugin half of removing run usage reporting in 4.0.1
// (issue #677). Scenario ids S1-S9 and S14-S16 plus the probes match the item's frozen `test-plan` note.
// S3 lives in ralph-loop.test.mjs (its harness) and S10-S13 in ralph-driver-fixes.test.mjs; S11 and S12
// also get a direct check here because they need only the shared fake API and fake claude fixtures.
//
// BLIND test author: no production source under scripts/*.mjs or scripts/lib/ was opened by any tool.
// Oracles are the diagnosis decisions D1-D5 (frozen before implementation) and e74ff75e AC1/AC2 (the
// server refuses heartbeat usage and budget.tokens/costUsd). Every expected value is a literal from those,
// never from running the code under test. Signatures come from the dispatch declarations.

import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn, spawnSync, execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import * as ralphLib from '../ralph-lib.mjs'
import * as runExecLib from '../run-exec-lib.mjs'
import { buildRunOpenOp } from '../ralph-lib.mjs'
import { SNAPSHOT_CONTRACT } from '../run-planner-lib.mjs'
import { startFakeApi } from './fixtures/ralph/fake-api.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..')
const CLI = join(PLUGIN_ROOT, 'scripts', 'run-planner.mjs')
const LOOP = join(PLUGIN_ROOT, 'scripts', 'ralph-loop.mjs')
const RENDER = join(PLUGIN_ROOT, 'scripts', 'render-ralph-prompts.mjs')
const FAKE_CLAUDE = join(HERE, 'fixtures', 'ralph', 'fake-claude.mjs')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')
const NOW = '2026-09-28T12:00:00Z'
const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'
const ROOT = '00000000-0000-4000-8000-000000000000'
const LOOP_ROOT = '11111111-2222-4333-8444-555555555555'
const BEARER = 'bearer-0a1b2c3d4e5f60718293a4b5c6d7e8f9'
const A = 'aaaa1111'

const tmpDirs = []
function tmp(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix))
  tmpDirs.push(d)
  return d
}
after(() => {
  for (const d of tmpDirs) rmSync(d, { recursive: true, force: true })
})

// ---- run-planner CLI helpers (same shape as run-ops.test.mjs) ---------------------------------------

function runCli(args, { input } = {}) {
  return spawnSync(process.execPath, [CLI, ...args], {
    encoding: 'utf8',
    input: input === undefined ? undefined : typeof input === 'string' ? input : JSON.stringify(input),
  })
}
function writeJson(dir, name, value) {
  const p = join(dir, name)
  writeFileSync(p, typeof value === 'string' ? value : JSON.stringify(value))
  return p
}
function planWith(runBudget) {
  const dir = tmp('usage-removal-')
  const snap = JSON.parse(readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8'))
  assert.equal(snap.contract, SNAPSHOT_CONTRACT)
  if (runBudget !== undefined) snap.profile.runBudget = runBudget
  const res = runCli(['plan', '--now', NOW, '--scratchpad', join(dir, 'scratchpad')], { input: snap })
  return { dir, res, doc: res.status === 0 ? JSON.parse(res.stdout) : null }
}
function planned(runBudget) {
  const p = planWith(runBudget)
  assert.equal(p.res.status, 0, p.res.stderr)
  return { ...p, planPath: writeJson(p.dir, 'plan.json', p.doc) }
}
function bound(runBudget) {
  const p = planned(runBudget)
  const res = runCli(['bind-run', '--in', p.planPath, '--server-run', SERVER_RUN])
  assert.equal(res.status, 0, res.stderr)
  return { ...p, boundPath: writeJson(p.dir, 'bound.json', JSON.parse(res.stdout)) }
}
function openOp(runBudget) {
  const { planPath } = planned(runBudget)
  const res = runCli(['run-ops', '--plan', planPath, '--phase', 'open'])
  assert.equal(res.status, 0, res.stderr)
  return JSON.parse(res.stdout).ops[0]
}
function hasKeyDeep(value, key) {
  if (Array.isArray(value)) return value.some((v) => hasKeyDeep(v, key))
  if (value && typeof value === 'object') {
    return Object.keys(value).includes(key) || Object.values(value).some((v) => hasKeyDeep(v, key))
  }
  return false
}

// ---- ralph driver harness (the shape of ralph-driver-fixes.test.mjs) --------------------------------

function baseEnv(extra) {
  const env = { ...process.env }
  for (const k of Object.keys(env)) {
    if (k.startsWith('TASK_ORCHESTRATOR_') || k === 'AGENT_CONFIG_DIR' || k.startsWith('FAKE_CLAUDE_') || k === 'RALPH_PAUSE_POLL_MS') delete env[k]
  }
  return { ...env, ...extra }
}
function runDriver({ argv, env, cwd, timeoutMs }) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [LOOP, ...argv], { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    let timedOut = false
    child.stdout.on('data', (d) => (stdout += d))
    child.stderr.on('data', (d) => (stderr += d))
    const timer = setTimeout(() => {
      timedOut = true
      child.kill('SIGKILL')
    }, timeoutMs)
    child.on('close', (code) => {
      clearTimeout(timer)
      resolve({ code, stdout, stderr, out: stdout + '\n' + stderr, timedOut })
    })
  })
}
async function scenario({ items = [A], plan = [], args = [], timeoutMs = 30000 } = {}) {
  const cwd = tmp('usage-removal-cwd-')
  const home = tmp('usage-removal-home-')
  const aux = tmp('usage-removal-aux-')
  const scratch = join(tmp('usage-removal-scratch-'), 'run')
  try {
    execFileSync('git', ['init', '-q'], { cwd, stdio: 'ignore' })
  } catch {
    // a repository is only for realism: --no-cleanup disables the worktree cleanup
  }
  const api = await startFakeApi({ items: items.map((id) => ({ id })) })
  const logFile = join(aux, 'claude.jsonl')
  const argv = ['--claude-bin', FAKE_CLAUDE, '--root', LOOP_ROOT, '--scratch-dir', scratch, '--no-cleanup', '--ttl', '120', '--budget', '1', '--max', '1', ...args]
  const env = baseEnv({
    TASK_ORCHESTRATOR_HOME: home,
    TASK_ORCHESTRATOR_CEILING: cwd,
    FAKE_CLAUDE_LOG: logFile,
    FAKE_CLAUDE_PLAN: JSON.stringify(plan),
    FAKE_CLAUDE_ITEM_IDS: items.join(','),
    FAKE_CLAUDE_API_URL: api.base,
    TASK_ORCHESTRATOR_API_URL: api.base,
    TASK_ORCHESTRATOR_API_TOKEN: BEARER,
  })
  const res = await runDriver({ argv, env, cwd, timeoutMs })
  await api.stop()
  return { api, res }
}
const tail = (s) => `\n--- driver output (tail) ---\n${s.res.out.slice(-1500)}`
const opsNamed = (s, name) => s.api.trace.filter((t) => t.op === name)
const COST = { total_cost_usd: 1.25, usage: { input_tokens: 100, output_tokens: 50, cache_creation_input_tokens: 10, cache_read_input_tokens: 5 } }

// ============================================================================================
// S1 - run-ops open drops the removed budget keys [D1]
// ============================================================================================

test('S1: run-ops open with profile runBudget {tokens, costUsd, attemptsPerSeat, maxActiveSeats} carries only {attemptsPerSeat, maxActiveSeats}', () => {
  const op = openOp({ tokens: 5000000, costUsd: 12.5, attemptsPerSeat: 3, maxActiveSeats: 2 })
  assert.deepEqual(op.budget, { attemptsPerSeat: 3, maxActiveSeats: 2 })
})

test('S1 (probe): each kept key alone still reaches the open op', () => {
  assert.deepEqual(openOp({ attemptsPerSeat: 2 }).budget, { attemptsPerSeat: 2 })
  assert.deepEqual(openOp({ maxActiveSeats: 2 }).budget, { maxActiveSeats: 2 })
})

// ============================================================================================
// S2 - buildRunOpenOp ignores the removed options [D3]
// ============================================================================================

const OPEN_BASE = { rootId: LOOP_ROOT, label: 'ralph-1-1', title: 'ralph drain: any', filter: {}, pid: 4242 }

test('S2: buildRunOpenOp with runBudgetTokens 1000, runBudgetUsd 12.5 and maxAttempts 3 sends budget {attemptsPerSeat: 3} only', () => {
  const op = buildRunOpenOp({ ...OPEN_BASE, runBudgetTokens: 1000, runBudgetUsd: 12.5, maxAttempts: 3 })
  assert.deepEqual(op.budget, { attemptsPerSeat: 3 })
})

test('S2 (probe): the removed options alone leave no budget key; maxAttempts alone is {attemptsPerSeat}', () => {
  assert.equal(Object.hasOwn(buildRunOpenOp({ ...OPEN_BASE, runBudgetTokens: 1000, runBudgetUsd: 12.5 }), 'budget'), false)
  assert.deepEqual(buildRunOpenOp({ ...OPEN_BASE, maxAttempts: 3 }).budget, { attemptsPerSeat: 3 })
})

// S3 (ralph-loop --max-attempts 3 -> budget {attemptsPerSeat: 3}) is covered in ralph-loop.test.mjs S1/D5.

// ============================================================================================
// S4 - the plan warns on the removed profile keys [D2]
// ============================================================================================

const WARN_TOKENS = 'profile.runBudget.tokens is ignored: run token and cost budgets were removed in 4.0.1'
const WARN_COST = 'profile.runBudget.costUsd is ignored: run token and cost budgets were removed in 4.0.1'
const warningsOf = (doc) => JSON.stringify(doc.meta.warnings ?? [])

test('S4: a plan whose profile.runBudget names tokens and costUsd carries both exact warnings', () => {
  const { doc } = planned({ tokens: 1, costUsd: 1, maxActiveSeats: 50 })
  const w = warningsOf(doc)
  assert.ok(w.includes(WARN_TOKENS), 'the tokens warning is present')
  assert.ok(w.includes(WARN_COST), 'the costUsd warning is present')
})

test('S4: a runBudget with only attemptsPerSeat carries neither warning', () => {
  const { doc } = planned({ attemptsPerSeat: 2 })
  const w = warningsOf(doc)
  assert.equal(w.includes(WARN_TOKENS), false)
  assert.equal(w.includes(WARN_COST), false)
})

test('S4 (probe): one warning per present key - tokens alone warns for tokens only', () => {
  const { doc } = planned({ tokens: 7 })
  const w = warningsOf(doc)
  assert.ok(w.includes(WARN_TOKENS))
  assert.equal(w.includes(WARN_COST), false)
})

// ============================================================================================
// S5 / S9 - run-ops --phase heartbeat is gone [D1]
// ============================================================================================

test('S5: run-ops --phase heartbeat --usage <file> on a bound plan exits 2 with empty stdout and the new phase list on stderr', () => {
  const { dir, boundPath } = bound()
  const usage = writeJson(dir, 'usage.json', { tokens: 1 })
  const res = runCli(['run-ops', '--plan', boundPath, '--phase', 'heartbeat', '--usage', usage])
  assert.equal(res.status, 2, `${res.status} ${res.stdout}`)
  assert.equal(res.stdout.trim(), '', 'no ops are rendered')
  assert.ok(res.stderr.includes('--phase must be open, assign, adopt-notes, update, end-seats or close'), 'stderr carries the phase list')
})

test('S9: --phase Heartbeat and --phase HEARTBEAT exit 2 as well', () => {
  const { boundPath } = bound()
  for (const phase of ['Heartbeat', 'HEARTBEAT']) {
    const res = runCli(['run-ops', '--plan', boundPath, '--phase', phase])
    assert.equal(res.status, 2, `${phase} -> ${res.status} ${res.stdout}`)
  }
})

test('S5 (probe): heartbeat with --item and no --usage exits 2 too', () => {
  const { boundPath } = bound()
  const res = runCli(['run-ops', '--plan', boundPath, '--phase', 'heartbeat', '--item', 'aaaaaaaa'])
  assert.equal(res.status, 2)
  assert.equal(res.stdout.trim(), '')
})

// ============================================================================================
// S6 - ralph-loop refuses the removed flags [D4]
// ============================================================================================

test('S6: --run-budget-tokens and --run-budget-usd exit 64 with --dry-run and make zero API calls', async () => {
  for (const args of [['--run-budget-tokens', '1000'], ['--run-budget-usd', '12.5'], ['--run-budget-usd=12.5']]) {
    const s = await scenario({ args: [...args, '--dry-run'], timeoutMs: 20000 })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(s.res.code, 64, `${args.join(' ')} -> ${s.res.code}` + tail(s))
    assert.equal(s.api.hits.length, 0, `${args.join(' ')} reached the API`)
  }
})

// ============================================================================================
// S7 - the usage builders are gone [D3]
// ============================================================================================

test('S7: neither ralph-lib nor run-exec-lib exports buildUsageOps or buildHeartbeatUsageOp', () => {
  for (const [name, mod] of [['ralph-lib', ralphLib], ['run-exec-lib', runExecLib]]) {
    for (const gone of ['buildUsageOps', 'buildHeartbeatUsageOp']) {
      assert.equal(Object.hasOwn(mod, gone), false, `${name} still exports ${gone}`)
    }
  }
})

// ============================================================================================
// S8 - a profile carrying only the removed keys opens with no budget [D1]
// ============================================================================================

test('S8: profile runBudget {tokens: 5, costUsd: 1} alone yields an open op without a budget key', () => {
  assert.equal(Object.hasOwn(openOp({ tokens: 5, costUsd: 1 }), 'budget'), false)
})

test('S8 (probe): empty, zero-token and null runBudget yield no budget key', () => {
  for (const rb of [{}, { tokens: 0 }, null]) {
    assert.equal(Object.hasOwn(openOp(rb), 'budget'), false, JSON.stringify(rb))
  }
})

test('S8 (probe): a string attemptsPerSeat is not a budget', () => {
  assert.equal(Object.hasOwn(openOp({ attemptsPerSeat: '3' }), 'budget'), false)
})

test('PROBE: --usage on run-ops close never reaches the ops', () => {
  const { dir, boundPath } = bound()
  const usage = writeJson(dir, 'usage.json', { tokens: 1200, costUsd: 0.5 })
  const res = runCli(['run-ops', '--plan', boundPath, '--phase', 'close', '--reason', 'cancelled', '--usage', usage])
  // JUSTIFIED DISJUNCTION (declared in test-manifest): the oracle says the flag is not read; it names no exit
  // code for an unread flag, so either a clean render without usage or a usage error (2) is correct.
  if (res.status === 0) {
    const out = JSON.parse(res.stdout)
    assert.equal(hasKeyDeep(out, 'usage'), false)
    assert.deepEqual(out.ops, [{ op: 'close', runId: SERVER_RUN, reason: 'cancelled' }])
  } else {
    assert.equal(res.status, 2)
  }
})

test('PROBE: two ready items in one iteration batch each yield complete only (no usage heartbeat)', async () => {
  const s = await scenario({ items: [A, 'bbbb2222'], plan: [{ kind: 'ready', ...COST }, { kind: 'ready', ...COST }], args: ['--max', '2'] })
  assert.equal(s.res.timedOut, false, tail(s))
  assert.equal(opsNamed(s, 'heartbeat').filter((h) => h.usage !== undefined).length, 0)
  assert.equal(opsNamed(s, 'transition').length, 2)
})

// ============================================================================================
// S11 / S12 - non-ready outcomes carrying usage send nothing about it [D4]
// ============================================================================================

test('S11: gate-blocked and error outcomes with usage in the envelope make one claim, no release, no usage heartbeat', async () => {
  for (const entry of [{ kind: 'gate', ...COST }, { kind: 'error', code: 'some_failure', ...COST }]) {
    const s = await scenario({ plan: [entry] })
    assert.equal(s.res.timedOut, false, tail(s))
    assert.equal(opsNamed(s, 'claim').length, 1, `${entry.kind}: only the seat claim` + tail(s))
    assert.equal(opsNamed(s, 'release').length, 0, entry.kind)
    assert.equal(opsNamed(s, 'heartbeat').filter((h) => h.usage !== undefined).length, 0, entry.kind)
    assert.equal(s.res.out.includes('usage-unreported'), false, entry.kind)
  }
})

test('S12: a legacy terminal iteration with usage sends no heartbeat, prints no usage line and ends the seat done', async () => {
  const s = await scenario({ plan: [{ kind: 'terminal-api', ...COST }] })
  assert.equal(s.res.timedOut, false, tail(s))
  assert.equal(opsNamed(s, 'heartbeat').length, 0)
  assert.equal(s.res.out.includes('usage-unreported'), false)
  assert.equal(s.api.seats[0].outcome, 'done')
})

// ============================================================================================
// Fake API fixture contract (harness): a heartbeat carrying usage answers invalid_request
// ============================================================================================

test('HARNESS: the fake API answers invalid_request to a heartbeat carrying usage and its run view has no reported usage fields', async () => {
  const s = await scenario({ plan: [{ kind: 'ready' }] })
  assert.equal(s.res.timedOut, false, tail(s))
  assert.equal(Object.hasOwn(s.api.runView().usage, 'reportedTokens'), false)
  assert.equal(Object.hasOwn(s.api.runView().usage, 'reportedCostUsd'), false)
})

// ============================================================================================
// Docs - S14 post-run.md, S15 ralph skill files and --help, S16 the budget wording sweep [D5]
// ============================================================================================

const read = (...p) => readFileSync(join(PLUGIN_ROOT, ...p), 'utf8')
const between = (text, from, to) => {
  const a = text.indexOf(from)
  assert.ok(a >= 0, `marker ${JSON.stringify(from)} exists`)
  const b = text.indexOf(to, a + from.length)
  assert.ok(b > a, `marker ${JSON.stringify(to)} follows`)
  return text.slice(a, b)
}

test('S14: post-run.md has no Step 0a section and no --phase heartbeat anywhere', () => {
  const text = read('skills', 'run-wave', 'references', 'post-run.md')
  assert.equal(text.includes('## Step 0a'), false, 'Step 0a is deleted')
  assert.equal(text.includes('--phase heartbeat'), false, 'no heartbeat command')
})

test('S14: the Step 7 section names no heartbeat and keeps the close command', () => {
  const step7 = between(read('skills', 'run-wave', 'references', 'post-run.md'), '## Step 7 ', '## Step 7a')
  assert.equal(/heartbeat/i.test(step7), false, 'Step 7 no longer mentions a heartbeat')
  assert.ok(step7.includes('--phase close'), 'Step 7 still closes the run')
})

const RALPH_FILES = [
  ['SKILL.md', ['skills', 'ralph', 'SKILL.md']],
  ['iteration-prompt.md', ['skills', 'ralph', 'iteration-prompt.md']],
  ['iteration-system-prompt.md', ['skills', 'ralph', 'iteration-system-prompt.md']],
]

test('S15: the ralph skill files carry no --run-budget-tokens, --run-budget-usd or usage-unreported', () => {
  for (const [label, parts] of RALPH_FILES) {
    const text = read(...parts)
    for (const gone of ['--run-budget-tokens', '--run-budget-usd', 'usage-unreported']) {
      assert.equal(text.includes(gone), false, `${label} still contains ${gone}`)
    }
  }
})

test('S15: ralph --help lists --max-attempts and none of the removed flags; SKILL.md keeps --max-attempts', () => {
  const res = spawnSync(process.execPath, [LOOP, '--help'], { encoding: 'utf8' })
  const out = (res.stdout ?? '') + (res.stderr ?? '')
  assert.ok(out.includes('--max-attempts'), '--help names --max-attempts')
  for (const gone of ['--run-budget-tokens', '--run-budget-usd', 'usage-unreported']) {
    assert.equal(out.includes(gone), false, `--help still contains ${gone}`)
  }
  assert.ok(read('skills', 'ralph', 'SKILL.md').includes('--max-attempts'), 'SKILL.md keeps --max-attempts')
})

test('PROBE: render-ralph-prompts --check finds the generated tables up to date (exit 0)', () => {
  const res = spawnSync(process.execPath, [RENDER, '--check'], { encoding: 'utf8' })
  assert.equal(res.status, 0, res.stderr)
})

const SWEEP_FILES = [
  ['run-wave SKILL.md', ['skills', 'run-wave', 'SKILL.md']],
  ['run-wave snapshot.md', ['skills', 'run-wave', 'references', 'snapshot.md']],
  ['director coordinator-tiers.md', ['skills', 'director', 'references', 'coordinator-tiers.md']],
  ['director SKILL.md', ['skills', 'director', 'SKILL.md']],
  ['init SKILL.md', ['skills', 'init', 'SKILL.md']],
  ['manage-schemas SKILL.md', ['skills', 'manage-schemas', 'SKILL.md']],
  ['manage-schemas config-format.md', ['skills', 'manage-schemas', 'references', 'config-format.md']],
  ['manage-schemas validate-workflow.md', ['skills', 'manage-schemas', 'references', 'validate-workflow.md']],
]

test('S16: no swept doc names costUsd, cost_usd, budgets.tokens, usage.json or a --run-budget- flag', () => {
  for (const [label, parts] of SWEEP_FILES) {
    const text = read(...parts)
    for (const gone of ['costUsd', 'cost_usd', 'budgets.tokens', 'usage.json', '--run-budget-']) {
      assert.equal(text.includes(gone), false, `${label} still contains ${gone}`)
    }
  }
})

test('S16: the director, run-wave and init SKILL.md files carry no run-budget wording and no "US dollars"', () => {
  for (const [label, parts] of SWEEP_FILES.filter(([l]) => /^(director|run-wave|init) SKILL\.md$/.test(l))) {
    const text = read(...parts)
    for (const gone of ['run-budget', 'US dollars']) {
      assert.equal(text.includes(gone), false, `${label} still contains ${gone}`)
    }
  }
})

test('S16 (fixture): every swept file exists, so an absence assertion cannot pass on a missing file', () => {
  for (const [label, parts] of [...SWEEP_FILES, ...RALPH_FILES]) {
    assert.ok(existsSync(join(PLUGIN_ROOT, ...parts)), `${label} exists`)
  }
})
