// Item 331ff0a4: the registered MCP entry key reaches the probe, the plan args and every seat prompt;
// a reseat prompt names exactly one ToolSearch; the RERUN CHECK command of a blind test-author seat, run in a
// shell against a temporary repository, lists only its own Seat commits by hash and subject. Scenario ids
// S5-S9, S12 and S13 match the item's frozen `test-plan` note (S13 as ruled by `amendments` R1).
// Oracles: parent interface-contract items 1 and 2 (IC1, IC2), task-scope AC1, AC2, AC4, AC6, AC7 and
// planner decisions D3, D4, D5, amendments R1 (S13) - never observed output. Only exported surfaces and the
// public CLI are used; no production source was opened. Each negative assertion names the fixture condition
// that fails it.
// S6's second half (reviewWaveArgs carries capabilities.mcpServerKey, IC1) was added in completion cycle A1
// once its declaration was supplied; its oracle is the test-plan and interface-contract item 1.

import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import '../../hooks/tests/hermetic-claude-config.mjs'
import { loadCore, planFixture, itemFixture, stages } from './workflow-harness.mjs'
import { loadCoreNamed } from './workflow-harness-ext.mjs'
import { loadWaveCore } from '../lib/wave-core.mjs'
import * as execLib from '../run-exec-lib.mjs'
import { buildPlanDoc, probeFrom } from '../run-planner-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const IMPLEMENT_PATH = join(PLUGIN, 'workflows', 'implement-wave.js')
const REVIEW_PATH = join(PLUGIN, 'workflows', 'review-wave.js')
const CLI = join(PLUGIN, 'scripts', 'run-planner.mjs')
const FIXTURES = join(HERE, 'fixtures', 'run-planner')

const ALT = 'task-orchestrator-alt'
const DEFAULT_KEY = 'mcp-task-orchestrator'
const FALLBACK = 'If that finds nothing, search ToolSearch for "task-orchestrator".'
const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'
const NOW = '2026-09-28T12:00:00Z'
const FEATURES = ['seats', 'dispatchBySeat', 'independent_of', 'rules']

const temps = []
after(() => {
  for (const d of temps) rmSync(d, { recursive: true, force: true })
})
function tempDir(tag) {
  const d = mkdtempSync(join(tmpdir(), `to-${tag}-`))
  temps.push(d)
  return d
}

// ---- prompt helpers ----

/** The one TOOLS: line of a prompt. */
function toolsLine(prompt) {
  const lines = prompt.split('\n').filter((l) => l.trim().startsWith('TOOLS:'))
  assert.equal(lines.length, 1, 'exactly one TOOLS: line')
  return lines[0].trim()
}
/** Entries of a TOOLS line for a key; asserts the whole line has the contract-item-2 shape. */
function entriesFor(line, key) {
  const head = 'TOOLS: load with ToolSearch select:'
  const tail = `. ${FALLBACK}`
  assert.ok(line.startsWith(head), `line starts with the load sentence: ${line}`)
  assert.ok(line.endsWith(tail), `line ends with the fallback sentence: ${line}`)
  const entries = line.slice(head.length, line.length - tail.length).split(',')
  const prefix = `mcp__${key}__`
  for (const e of entries) assert.ok(e.startsWith(prefix) && /^[a-z_]+$/.test(e.slice(prefix.length)), `entry ${e} uses ${prefix}`)
  assert.ok(entries.length >= 3, 'the line lists the seat tools')
  return entries.map((e) => e.slice(prefix.length))
}
function paragraphFrom(text, head) {
  const start = text.indexOf(head)
  assert.ok(start !== -1, `prompt has no ${head} part`)
  const rest = text.slice(start)
  const end = rest.search(/\n\n/)
  return end === -1 ? rest : rest.slice(0, end)
}
const countLines = (text, re) => text.split('\n').filter((l) => re.test(l)).length

// ---- implement-wave fixtures ----

const PROJECT = { searchScope: 'claude-plugins/task-orchestrator', scratchDir: '/tmp/scratch', shell: 'bash', verify: [] }

function makeItem(short, stageList) {
  return itemFixture({ short, title: `Item ${short}`, configFingerprint: `fp-${short}`, traits: ['delegated'], stages: stageList })
}
function capsWith(key) {
  const caps = { features: FEATURES, phase0Hooks: true }
  if (key !== undefined) caps.mcpServerKey = key
  return caps
}
function makePlan(items, { key, bound = false, ...overrides } = {}) {
  return planFixture({
    items,
    rootId: 'root-331',
    project: PROJECT,
    baseSha: 'e77c3e95',
    capabilities: capsWith(key),
    ...(bound ? { serverRunId: SERVER_RUN } : {}),
    ...overrides,
  })
}
const stageOf = (item, seat) => item.stages.find((s) => s.seat === seat)

// ---- S7: every seat prompt of every shape uses the resolved key ----

test('S7 (IC2, AC1, AC7): implement-wave seats - both entry modes, bound and unbound - carry the exact TOOLS line for the alt key and never the default prefix', () => {
  const core = loadCore(IMPLEMENT_PATH)
  const item = makeItem('7a7a7a7a', stages.bugFixLike())
  let rendered = 0
  for (const entryMode of ['seat', 'pre-entered']) {
    for (const bound of [false, true]) {
      for (const stage of item.stages) {
        const alt = core.seatPrompt(makePlan([item], { key: ALT, entryMode, bound }), item, stage, {})
        const dflt = core.seatPrompt(makePlan([item], { key: DEFAULT_KEY, entryMode, bound }), item, stage, {})
        const where = `${stage.seat}/${entryMode}/${bound ? 'bound' : 'unbound'}`
        const altEntries = entriesFor(toolsLine(alt), ALT)
        const dfltEntries = entriesFor(toolsLine(dflt), DEFAULT_KEY)
        assert.deepEqual(altEntries, dfltEntries, `${where}: same tools in the same order under either key`)
        assert.equal(alt.includes('mcp__mcp-task-orchestrator__'), false, `${where}: no default-prefixed name remains`)
        rendered += 1
      }
    }
  }
  assert.ok(rendered >= 12, 'every combination was rendered')
})

test('S7 (IC2): a bound writer under the alt key lists runs_write as an alt-prefixed entry (the key reaches the end-seat tool too)', () => {
  const core = loadCore(IMPLEMENT_PATH)
  const item = makeItem('7b7b7b7b', stages.bugFixLike())
  const p = core.seatPrompt(makePlan([item], { key: ALT, bound: true }), item, stageOf(item, 'implementer'), {})
  assert.ok(toolsLine(p).includes(`mcp__${ALT}__runs_write`), toolsLine(p))
})

test('S7 (IC2, AC1): a review-wave lane under the alt key carries the exact TOOLS line and no default-prefixed name', () => {
  const { normalizeArgs, lanePrompt } = loadCoreNamed(REVIEW_PATH, ['normalizeArgs', 'lanePrompt'])
  const raw = (key) => ({
    contract: 'review-wave/args-v1',
    runId: 'r-test-0001',
    rootId: 'root-test-0001',
    baseSha: 'e77c3e95',
    worktreeMode: 'shared',
    entryMode: 'pre-entered',
    capabilities: { features: ['seats', 'dispatchBySeat', 'rules'], phase0Hooks: true, ...(key === undefined ? {} : { mcpServerKey: key }) },
    items: [{
      id: 'item-aaaaaaaa', short: 'aaaaaaaa', type: 'test-item', configFingerprint: 'fp-1', traits: [], worktree: '/tmp/wt-aaaaaaaa',
      stages: [{
        seat: 'reviewer', lane: 'reviewer.review-quality', phase: 'review', notes: ['review-checklist', 'test-independence-audit'], writes: false,
        protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1',
      }],
      review: {
        ownedFiles: { implementer: ['workflows/review-wave.js'], testAuthor: ['scripts/tests/review-wave.test.mjs'], docs: [] },
        commits: { implementer: ['aaaaaaa1'], testAuthor: ['bbbbbbb2'], fixtureRepairs: [] },
        implementerActors: ['implementer:aaaaaaaa:r-impl-0001'], testAuthorActors: ['test-author:aaaaaaaa:r-impl-0001'], requiredReviewNotes: ['review-checklist', 'test-independence-audit'],
      },
    }],
    deferred: [],
  })
  const render = (key) => {
    const r = normalizeArgs(raw(key))
    assert.equal(r.ok, true, `normalizeArgs accepted: ${r.reason}`)
    const item = r.plan.items[0]
    return lanePrompt(r.plan, item, item.stages[0], {})
  }
  const alt = render(ALT)
  const entries = entriesFor(toolsLine(alt), ALT)
  assert.deepEqual(entries, entriesFor(toolsLine(render(DEFAULT_KEY)), DEFAULT_KEY))
  assert.equal(alt.includes('mcp__mcp-task-orchestrator__'), false)
  // S8 on the review lane: absent, null and empty are the default
  for (const empty of [undefined, null, '']) assert.equal(render(empty), render(DEFAULT_KEY), `key ${JSON.stringify(empty)}`)
})

test('S7 (IC2, AC1): the Method B prompt (run-exec prompt) carries the exact TOOLS line for the alt key', () => {
  const core = loadWaveCore()
  const short = 'bbbbbbbb'
  const item = itemFixture({
    short,
    stages: [{ seat: 'implementer', phase: 'work', enters: true, notes: [], writes: true, dispatch: {}, output: 'implementer-v1' }],
  })
  const docFor = (key) => ({
    contract: 'run-wave/plan-doc-v1',
    args: planFixture({ items: [item], capabilities: capsWith(key) }),
    meta: { method: 'B' },
  })
  const render = (key) => {
    const doc = docFor(key)
    return execLib.prompt(core, doc, execLib.initState(doc, 'B'), short, 'implementer')
  }
  const alt = render(ALT)
  assert.deepEqual(entriesFor(toolsLine(alt), ALT), entriesFor(toolsLine(render(DEFAULT_KEY)), DEFAULT_KEY))
  assert.equal(alt.includes('mcp__mcp-task-orchestrator__'), false)
})

// ---- S8: absent / null / empty key is the default ----

test('S8 (AC2, D4): a key that is absent, null or empty renders byte-identical prompts to the default key, ending in the fallback sentence', () => {
  const core = loadCore(IMPLEMENT_PATH)
  const item = makeItem('8a8a8a8a', stages.bugFixLike())
  for (const stage of item.stages) {
    const expected = core.seatPrompt(makePlan([item], { key: DEFAULT_KEY, bound: true }), item, stage, {})
    assert.ok(toolsLine(expected).endsWith(FALLBACK), `${stage.seat}: the fallback sentence is present`)
    entriesFor(toolsLine(expected), DEFAULT_KEY)
    for (const key of [undefined, null, '']) {
      const got = core.seatPrompt(makePlan([item], { key, bound: true }), item, stage, {})
      assert.equal(got, expected, `${stage.seat}: key ${JSON.stringify(key)}`)
    }
  }
})

test('S8 probe: a seat prompt rendered twice from the same plan is identical', () => {
  const core = loadCore(IMPLEMENT_PATH)
  const item = makeItem('8b8b8b8b', stages.bugFixLike())
  const plan = makePlan([item], { key: ALT })
  const stage = stageOf(item, 'implementer')
  assert.equal(core.seatPrompt(plan, item, stage, {}), core.seatPrompt(plan, item, stage, {}))
})

// ---- S9: reseat prompts ----

const PLANNER_OUT = {
  proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
  decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
  mainFiles: ['src/x.js'], docFiles: [], testFiles: ['scripts/tests/x.test.mjs'], existingTestEdits: [], redProofShape: 'none', sweep: [],
}
const IMPL_OUT = {
  mainFilesChanged: ['src/x.js'], docFilesChanged: [], verify: [], failingExistingTests: [],
  preExistingFailures: [], publicSurface: 'none', deviations: 'none',
}
const DECL_NONE = { declarations: 'none', harnessPointers: 'ptr-kept', gaps: 'none' }
const SHORT = 'aaaaaaaa'
const PRE = 'c'.repeat(40)

function reseatDoc({ key, bound, short = SHORT, worktree, baseSha }) {
  const item = itemFixture({ short, stages: stages.bugFixLike(), ...(worktree ? { worktree } : {}) })
  const args = planFixture({
    items: [item],
    project: PROJECT,
    capabilities: capsWith(key),
    ...(bound ? { serverRunId: SERVER_RUN } : {}),
    ...(baseSha ? { baseSha } : {}),
  })
  return { contract: 'run-wave/plan-doc-v1', args, meta: {} }
}
function reseatResult(doc) {
  const item = doc.args.items[0]
  return {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: item.short, status: 'done', reason: 'ok',
      stages: item.stages.map((s) => ({ seat: s.seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] })),
      outputs: { planner: PLANNER_OUT, implementer: IMPL_OUT, 'declarations-extractor': DECL_NONE },
    }],
    refused: [], deferred: [],
  }
}
function reseatPrompt({ key, bound, seat, short = SHORT, worktree, baseSha, pre = PRE }) {
  const doc = reseatDoc({ key, bound, short, worktree, baseSha })
  const opts = { cause: 'review', pre, ...(seat.startsWith('test-author') ? { declarations: 'export function f(x)' } : {}) }
  const out = execLib.reseat(loadWaveCore(), doc, reseatResult(doc), short, seat, opts)
  assert.equal(out.ok, true, JSON.stringify(out))
  return out.prompt
}

test('S9 (IC2, AC4): a bound reseat has exactly one ToolSearch line - the TOOLS line, alt-prefixed, with runs_write and notes_write and no advance - and its END SEAT part names no second load', () => {
  const p = reseatPrompt({ key: ALT, bound: true, seat: 'implementer-a1' })
  assert.equal(countLines(p, /ToolSearch/), 1, 'one line names ToolSearch (a second load instruction would be a second line)')
  const entries = entriesFor(toolsLine(p), ALT)
  assert.ok(entries.includes('runs_write'), `runs_write in ${entries}`)
  assert.ok(entries.includes('notes_write'), `notes_write in ${entries}`)
  assert.equal(entries.includes('advance'), false, 'a non-entering reseat has no advance')
  const endSeat = paragraphFrom(p, 'END SEAT:')
  assert.ok(endSeat.includes('end_seat'), 'END SEAT part is kept')
  assert.equal(/ToolSearch/.test(endSeat), false, 'END SEAT part holds no ToolSearch instruction')
  assert.equal(p.includes('mcp__mcp-task-orchestrator__'), false)
})

test('S9 (AC4): a bound test-author reseat has the same single-load shape', () => {
  const p = reseatPrompt({ key: ALT, bound: true, seat: 'test-author-a1' })
  assert.equal(countLines(p, /ToolSearch/), 1)
  const entries = entriesFor(toolsLine(p), ALT)
  assert.ok(entries.includes('runs_write') && entries.includes('notes_write'))
  assert.equal(/ToolSearch/.test(paragraphFrom(p, 'END SEAT:')), false)
})

test('S9 (IC2): an unbound reseat has no runs_write and no END SEAT part, and still one ToolSearch line', () => {
  const p = reseatPrompt({ key: ALT, bound: false, seat: 'implementer-a1' })
  assert.equal(countLines(p, /ToolSearch/), 1)
  assert.equal(entriesFor(toolsLine(p), ALT).includes('runs_write'), false)
  assert.equal(p.includes('END SEAT:'), false)
})

test('S9 (AC2): a reseat under no key matches the default key byte for byte', () => {
  assert.equal(
    reseatPrompt({ key: undefined, bound: true, seat: 'implementer-a1' }),
    reseatPrompt({ key: DEFAULT_KEY, bound: true, seat: 'implementer-a1' })
  )
})

// ---- S13: the RERUN CHECK command of a blind test-author seat, run against a repository ----
//
// Oracle: amendments ruling R1 (B1 of review-checklist v2). A test-author-v1 stage and its -a<n> reseats
// are told to run a git log that lists, by hash and subject only, the commits whose message has the line
// "Seat: <its seat>"; every other seat keeps the log with bodies. The prompt text is not the oracle: the
// command the rendered prompt prints is taken from the prompt (never retyped) and run in each available
// shell against commits written in the plugin COMMIT form, where "Seat: ..." and "Co-Authored-By: ..." are
// separate -m paragraphs (so git parses only Co-Authored-By as a trailer, the shape that blinded the
// earlier trailer-column form). A format-string pin could not fail on that; the command's output can.

const BODY_MARKER = 'BODY-MARKER'
const COAUTHOR = 'Co-Authored-By: Fixture Model <fixture@example.invalid>'
const RERUN_SHORT = '13131313'
const OTHER_SHORT = '24242424'

/** The environment for every git and shell run: no inherited GIT_* variables, no user or system git config. */
function isolatedGitEnv(home) {
  const env = {}
  for (const [k, v] of Object.entries(process.env)) if (!/^GIT_/i.test(k)) env[k] = v
  const emptyConfig = join(home, 'empty-gitconfig')
  writeFileSync(emptyConfig, '')
  return {
    ...env,
    HOME: home,
    USERPROFILE: home,
    XDG_CONFIG_HOME: join(home, 'xdg'),
    GIT_CONFIG_GLOBAL: emptyConfig,
    GIT_CONFIG_NOSYSTEM: '1',
    GIT_TERMINAL_PROMPT: '0',
    GIT_AUTHOR_NAME: 'Fixture',
    GIT_AUTHOR_EMAIL: 'fixture@example.invalid',
    GIT_COMMITTER_NAME: 'Fixture',
    GIT_COMMITTER_EMAIL: 'fixture@example.invalid',
  }
}

function gitOut(env, args) {
  const r = spawnSync('git', args, { encoding: 'utf8', env })
  assert.equal(r.status, 0, `git ${args.join(' ')}: ${r.stderr}`)
  return r.stdout
}

let rerunRepoCache
/**
 * A temporary repository: base B0, then C1 implementer, C2 test-author, C3 test-author-a1,
 * C4 test-author (another item, 24242424), C5 no Seat line; C1-C3 and C5 carry [13131313]. Every commit is
 * made with the argv of the COMMIT form: --only, subject, why paragraph holding BODY-MARKER, an optional
 * "Seat: <seat>" paragraph, a Co-Authored-By paragraph, then the path. B0 also names Seat test-author
 * but sits at the base, so a range limited to base..HEAD must not list it.
 */
function rerunRepo() {
  if (rerunRepoCache) return rerunRepoCache
  const root = tempDir('rerun')
  const home = join(root, 'home')
  const hooks = join(root, 'no-hooks')
  const repoDir = join(root, 'repo')
  for (const d of [home, hooks, repoDir]) mkdirSync(d, { recursive: true })
  const env = isolatedGitEnv(home)
  const fwd = (p) => p.replace(/\\/g, '/')
  const wt = fwd(repoDir)
  assert.equal(/\s/.test(wt), false, `the rendered command takes the worktree path unquoted, so the temp path must hold no whitespace: ${wt}`)
  gitOut(env, ['init', '-q', wt])
  for (const [k, v] of [
    ['user.name', 'Fixture'], ['user.email', 'fixture@example.invalid'], ['commit.gpgsign', 'false'],
    ['tag.gpgsign', 'false'], ['core.hooksPath', fwd(hooks)], ['core.autocrlf', 'false'],
  ]) gitOut(env, ['-C', wt, 'config', k, v])

  let n = 0
  const subjects = {}
  const commitAs = (id, { seat, short }) => {
    n += 1
    writeFileSync(join(repoDir, 'f.txt'), `${id} ${n}\n`)
    gitOut(env, ['-C', wt, 'add', 'f.txt'])
    const subject = `test(plugin): ${id} change [${short}]`
    const args = ['-C', wt, 'commit', '--only', '-m', subject, '-m', `${BODY_MARKER} why ${id}`]
    if (seat) args.push('-m', `Seat: ${seat}`)
    args.push('-m', COAUTHOR, '--', 'f.txt')
    gitOut(env, args)
    subjects[id] = subject
    return gitOut(env, ['-C', wt, 'rev-parse', 'HEAD']).trim()
  }
  const hash = {}
  hash.B0 = commitAs('B0', { seat: 'test-author', short: RERUN_SHORT })
  hash.C1 = commitAs('C1', { seat: 'implementer', short: RERUN_SHORT })
  hash.C2 = commitAs('C2', { seat: 'test-author', short: RERUN_SHORT })
  hash.C3 = commitAs('C3', { seat: 'test-author-a1', short: RERUN_SHORT })
  hash.C4 = commitAs('C4', { seat: 'test-author', short: OTHER_SHORT })
  hash.C5 = commitAs('C5', { seat: null, short: RERUN_SHORT })

  // The fixture must reproduce the shape that blinded the old form: git parses no Seat trailer here, only Co-Authored-By.
  const seatTrailers = gitOut(env, ['-C', wt, 'log', '--format=%(trailers:key=Seat,valueonly)'])
  assert.equal(seatTrailers.trim(), '', 'no commit has a parsed Seat trailer')
  const coAuthors = gitOut(env, ['-C', wt, 'log', '--format=%(trailers:key=Co-Authored-By,valueonly)']).split('\n').filter((l) => l.trim() !== '')
  assert.equal(coAuthors.length, 6, 'every commit has a parsed Co-Authored-By trailer')

  rerunRepoCache = { root, wt, env, base: hash.B0, hash, subjects }
  return rerunRepoCache
}

/** The shell executables a rendered command may be run in: POSIX sh and pwsh. A missing one is skipped by name. */
function locateSh() {
  const candidates = ['sh']
  if (process.platform === 'win32') {
    const exec = spawnSync('git', ['--exec-path'], { encoding: 'utf8' })
    if (exec.status === 0) {
      const gitRoot = resolve(exec.stdout.trim(), '..', '..', '..')
      candidates.push(join(gitRoot, 'bin', 'sh.exe'), join(gitRoot, 'usr', 'bin', 'sh.exe'))
    }
    for (const base of [process.env.ProgramFiles, process.env.ProgramW6432, process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'Programs')]) {
      if (base) candidates.push(join(base, 'Git', 'bin', 'sh.exe'), join(base, 'Git', 'usr', 'bin', 'sh.exe'))
    }
  }
  return candidates.find((c) => spawnSync(c, ['-c', 'exit 0']).status === 0) ?? null
}
function locatePwsh() {
  return spawnSync('pwsh', ['-NoProfile', '-NonInteractive', '-Command', 'exit 0']).status === 0 ? 'pwsh' : null
}
const SHELLS = [
  { name: 'POSIX sh', exe: locateSh(), argv: (command) => ['-c', command] },
  { name: 'pwsh', exe: locatePwsh(), argv: (command) => ['-NoProfile', '-NonInteractive', '-Command', command] },
]

/** The command a rendered prompt's RERUN CHECK tells the seat to run: from "git -C" to the end of the range. */
function rerunCommand(prompt) {
  const start = prompt.indexOf('RERUN CHECK')
  assert.ok(start !== -1, 'prompt has a RERUN CHECK part')
  const line = prompt.slice(start).split('\n')[0]
  const m = /\brun (git -C \S+ log \S.*?\.\.HEAD)(?=\s|$)/.exec(line)
  assert.ok(m, `the RERUN CHECK line names a git log command ending in ..HEAD: ${line}`)
  return m[1]
}

/** Stdout of the command run in the shell inside the temp repo's environment, as lines without the final newline. */
function runInShell(shell, repo, command) {
  const r = spawnSync(shell.exe, shell.argv(command), { encoding: 'utf8', env: repo.env, cwd: repo.root })
  assert.equal(r.status, 0, `${shell.name} exit ${r.status} for: ${command}\n${r.stderr}`)
  const lines = r.stdout.replace(/\r\n/g, '\n').split('\n')
  if (lines[lines.length - 1] === '') lines.pop()
  return { lines, text: lines.join('\n') }
}

function seatPromptFor(repo, seat, { entryMode = 'seat', bound = true } = {}) {
  const item = itemFixture({
    short: RERUN_SHORT, title: `Item ${RERUN_SHORT}`, configFingerprint: `fp-${RERUN_SHORT}`, traits: ['delegated'],
    worktree: repo.wt, stages: stages.bugFixLike(),
  })
  const plan = makePlan([item], { key: ALT, bound, entryMode, baseSha: repo.base })
  return loadCore(IMPLEMENT_PATH).seatPrompt(plan, item, stageOf(item, seat), {})
}
const reseatPromptFor = (repo, seat) =>
  reseatPrompt({ key: ALT, bound: true, seat, short: RERUN_SHORT, worktree: repo.wt, baseSha: repo.base, pre: repo.base })

/** Asserts every line is "hash TAB subject" and that the listed (hash, subject) pairs are exactly the expected commits. */
function assertListsExactly(lines, repo, ids, where) {
  for (const l of lines) assert.match(l, /^[0-9a-f]{40}\t[^\t]*$/, `${where}: each line is hash TAB subject, got ${JSON.stringify(l)}`)
  const got = lines.map((l) => l.split('\t').join(' | ')).sort()
  const want = ids.map((id) => `${repo.hash[id]} | ${repo.subjects[id]}`).sort()
  assert.deepEqual(got, want, `${where}: listed commits`)
}
function assertNoBody(text, where) {
  assert.equal(text.includes(BODY_MARKER), false, `${where}: a commit body (why paragraph) is shown`)
  assert.equal(text.includes('Co-Authored-By'), false, `${where}: a commit body (Co-Authored-By paragraph) is shown`)
  assert.equal(text.includes('Seat:'), false, `${where}: a commit body (Seat line) is shown`)
}

test('S13 (R1 fixture): the temp repository is built from the plugin COMMIT form - Seat and Co-Authored-By are separate -m paragraphs - and the rendered COMMIT line says so', () => {
  const repo = rerunRepo()
  const prompt = seatPromptFor(repo, 'test-author')
  assert.ok(prompt.includes('-m "Seat: test-author" -m "Co-Authored-By: '), 'the COMMIT form gives Seat its own -m paragraph before Co-Authored-By')
  assert.ok(prompt.includes(`[${RERUN_SHORT}]" -m "<why>"`), 'the COMMIT form is subject [short], why, Seat, Co-Authored-By')
})

test('S13 (R1): at least one of POSIX sh and pwsh is installed to run the rendered command', () => {
  const found = SHELLS.filter((s) => s.exe)
  assert.ok(found.length >= 1, `no shell ran: ${SHELLS.map((s) => `${s.name} not installed`).join(', ')}`)
})

for (const shell of SHELLS) {
  const opts = { skip: shell.exe ? false : `${shell.name} is not installed on this machine` }

  test(`S13 (AC6, R1) [${shell.name}]: a test-author seat's RERUN CHECK command lists exactly its own Seat commits since the base - hash and subject only, never a body`, opts, () => {
    const repo = rerunRepo()
    const commands = new Set()
    for (const entryMode of ['seat', 'pre-entered']) {
      for (const bound of [true, false]) commands.add(rerunCommand(seatPromptFor(repo, 'test-author', { entryMode, bound })))
    }
    assert.equal(commands.size, 1, 'the command is the same in both entry modes, bound and unbound')
    const [command] = commands
    const { lines, text } = runInShell(shell, repo, command)
    // C2 (this item) and C4 (another item) have Seat: test-author; C1, C3 (test-author-a1), C5 (no Seat line) and the base B0 must not be listed.
    assertListsExactly(lines, repo, ['C2', 'C4'], 'test-author')
    assertNoBody(text, 'test-author')
    assert.deepEqual(
      lines.filter((l) => l.includes(`[${RERUN_SHORT}]`)).map((l) => l.slice(0, 40)),
      [repo.hash.C2],
      'its own commit is the one whose subject names the item short'
    )
  })

  test(`S13 (AC6, R1) [${shell.name}]: the test-author-a1 reseat's command lists only the commit whose Seat line is test-author-a1, and no body`, opts, () => {
    const repo = rerunRepo()
    const { lines, text } = runInShell(shell, repo, rerunCommand(reseatPromptFor(repo, 'test-author-a1')))
    assertListsExactly(lines, repo, ['C3'], 'test-author-a1')
    assertNoBody(text, 'test-author-a1')
  })

  test(`S13 (AC6, R1) [${shell.name}]: an implementer's command keeps the bodies - every commit since the base, with its Seat line - and the -a1 implementer reseat too`, opts, () => {
    const repo = rerunRepo()
    const impl = rerunCommand(seatPromptFor(repo, 'implementer'))
    assert.equal(impl.includes('--grep'), false, 'the implementer log is not narrowed by a Seat grep')
    for (const [where, command] of [['implementer', impl], ['implementer-a1', rerunCommand(reseatPromptFor(repo, 'implementer-a1'))]]) {
      const { text } = runInShell(shell, repo, command)
      assert.ok(text.includes(BODY_MARKER), `${where}: the body is shown`)
      assert.ok(text.includes('Seat: implementer'), `${where}: the Seat line of C1 is shown`)
      const listed = new Set(text.match(/^[0-9a-f]{40}(?=\t)/gm))
      assert.deepEqual([...listed].sort(), ['C1', 'C2', 'C3', 'C4', 'C5'].map((id) => repo.hash[id]).sort(), `${where}: every commit since the base, and not the base`)
    }
  })
}

// ---- S5: probe CLI and probeFrom ----

function runProbe(cwd, claudeDir, ceiling) {
  const res = spawnSync(process.execPath, [CLI, 'probe', '--plugin-root', PLUGIN, '--no-server-check', '--cwd', cwd], {
    encoding: 'utf8',
    env: { ...process.env, CLAUDE_CONFIG_DIR: claudeDir, TASK_ORCHESTRATOR_HOME: join(claudeDir, 'home'), TASK_ORCHESTRATOR_CEILING: ceiling },
  })
  assert.equal(res.status, 0, res.stderr)
  return JSON.parse(res.stdout.trim().split('\n').pop())
}
function probeEnv(servers) {
  const T = tempDir('probe')
  const cwd = join(T, 'ws', 'sub')
  const claudeDir = join(T, 'claude')
  mkdirSync(cwd, { recursive: true })
  mkdirSync(claudeDir, { recursive: true })
  if (servers) writeFileSync(join(claudeDir, '.claude.json'), JSON.stringify({ mcpServers: servers }))
  return { T, cwd, claudeDir }
}

test('S5 (AC1, D3): probe prints the registered entry key as mcpServerKey', () => {
  const { T, cwd, claudeDir } = probeEnv({ [ALT]: { type: 'http', url: 'http://localhost:3001/mcp' } })
  assert.equal(runProbe(cwd, claudeDir, T).mcpServerKey, ALT)
})

test('S5 (D3): with no registration the probe still has the mcpServerKey member, and it is null', () => {
  const { T, cwd, claudeDir } = probeEnv(null)
  const out = runProbe(cwd, claudeDir, T)
  assert.ok('mcpServerKey' in out, 'member present')
  assert.equal(out.mcpServerKey, null)
})

test('S5 (D3): probe skips a director-only registration (null)', () => {
  const { T, cwd, claudeDir } = probeEnv({ 'task-orchestrator-director': { type: 'http', url: 'http://localhost:3001/mcp/director' } })
  assert.equal(runProbe(cwd, claudeDir, T).mcpServerKey, null)
})

test('S5 (D3): probeFrom passes a supplied mcpServerKey through unchanged', () => {
  const base = {
    pluginRoot: '/plugin', pluginJsonText: JSON.stringify({ version: '3.8.0' }),
    subagentStartText: "matcher for 'workflow-subagent' here", phaseGuardText: 'if (!input.seatToken) return;',
    configYamlText: '', settings: ['{}', '{}', '{}'],
  }
  assert.equal(probeFrom({ ...base, mcpServerKey: ALT }).mcpServerKey, ALT)
  assert.equal(probeFrom({ ...base, mcpServerKey: null }).mcpServerKey, null)
})

// ---- S6 / S12: plan doc carries the key and the allow flags ----

const snapshot = () => JSON.parse(readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8'))
function planWith(probe) {
  const snap = snapshot()
  snap.probe = { ...snap.probe, ...probe }
  const result = buildPlanDoc(snap, { now: NOW })
  assert.equal(result.ok, true, JSON.stringify(result.errors))
  return result.doc
}

test('S6 (IC1, D4): buildPlanDoc sets args.capabilities.mcpServerKey from the probe key', () => {
  assert.equal(planWith({ mcpServerKey: ALT }).args.capabilities.mcpServerKey, ALT)
})

test('S6 (D4): a null, empty or missing probe key leaves capabilities without an mcpServerKey member', () => {
  for (const probe of [{ mcpServerKey: null }, { mcpServerKey: '' }, {}]) {
    const caps = planWith(probe).args.capabilities
    assert.equal(Object.hasOwn(caps, 'mcpServerKey'), false, JSON.stringify(probe))
    assert.ok(Array.isArray(caps.features) && caps.features.includes('seats'), 'other capabilities are kept')
  }
})

// ---- S6 second half: reviewWaveArgs carries the key (A1) ----

const REVIEW_ITEM = 'aaaaaaaa-1111-4111-8111-111111111111'

/** A review-wave args object built from a plan doc whose probe resolved `probe`; the item has review notes. */
function reviewArgsFor(probe) {
  const snap = snapshot()
  snap.schemas[REVIEW_ITEM].notes.push(
    { key: 'review-checklist', role: 'review', required: true, seat: 'reviewer', skill: 'review-quality' }
  )
  snap.probe = { ...snap.probe, ...probe }
  const built = buildPlanDoc(snap, { now: NOW })
  assert.equal(built.ok, true, JSON.stringify(built.errors))
  const doc = built.doc
  const item = doc.args.items.find((i) => i.id === REVIEW_ITEM)
  assert.ok(item, 'the fixture item is in the plan')
  const result = {
    contract: 'implement-wave/result-v1', started: true, runId: doc.args.runId, planDocSlug: 'run/x',
    items: [{
      id: item.id, short: item.short, status: 'done', reason: 'ok',
      stages: item.stages.map((s) => ({ seat: s.seat, status: 'done', reason: 'ok', modelReported: 'sonnet', notes: [], commits: { pre: '', post: '' }, files: [] })),
      outputs: {},
    }],
    refused: [], deferred: [],
  }
  return execLib.reviewWaveArgs(loadWaveCore(), doc, snap, result, [item.short])
}

test('S6 (IC1): reviewWaveArgs carries a non-empty mcpServerKey as capabilities.mcpServerKey, and a review lane prompt then uses it', () => {
  const args = reviewArgsFor({ mcpServerKey: ALT })
  assert.equal(args.contract, 'review-wave/args-v1')
  assert.equal(args.capabilities.mcpServerKey, ALT)
  // the carried key is the one a review lane's TOOLS line is built from (IC2)
  const { normalizeArgs, lanePrompt } = loadCoreNamed(REVIEW_PATH, ['normalizeArgs', 'lanePrompt'])
  const norm = normalizeArgs(args)
  assert.equal(norm.ok, true, `normalizeArgs accepted: ${norm.reason}`)
  const item = norm.plan.items[0]
  const prompt = lanePrompt(norm.plan, item, item.stages[0], {})
  assert.equal(toolsLine(prompt).includes(`mcp__${ALT}__`), true, toolsLine(prompt))
  assert.equal(prompt.includes('mcp__mcp-task-orchestrator__'), false)
})

test('S6 (IC1, D4): a null, empty or missing probe key means the default key in the review args (the member is not required to be present)', () => {
  for (const probe of [{ mcpServerKey: null }, { mcpServerKey: '' }, {}]) {
    const caps = reviewArgsFor(probe).capabilities
    assert.equal(caps.mcpServerKey ?? DEFAULT_KEY, DEFAULT_KEY, JSON.stringify(probe))
    assert.ok(Array.isArray(caps.features) && caps.features.includes('seats'), 'other capabilities are kept')
  }
})

test('S12 (D5): buildPlanDoc copies probe.allow to meta.allow; no probe.allow leaves meta.allow absent', () => {
  const allow = { workflow: false, mcp: true, bashGit: true, bashNode: false }
  assert.deepEqual(planWith({ allow }).meta.allow, allow)
  assert.equal(planWith({}).meta.allow, undefined)
})
