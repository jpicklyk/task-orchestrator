// Item 331ff0a4: the registered MCP entry key reaches the probe, the plan args and every seat prompt;
// a reseat prompt names exactly one ToolSearch; a blind test-author seat's RERUN CHECK shows subjects and
// the Seat trailer only. Scenario ids S5-S9, S12 and S13 match the item's frozen `test-plan` note.
// Oracles: parent interface-contract items 1 and 2 (IC1, IC2), task-scope AC1, AC2, AC4, AC6, AC7 and
// planner decisions D3, D4, D5, D6 - never observed output. Only exported surfaces and the public CLI are
// used; no production source was opened. Each negative assertion names the fixture condition that fails it.
// Not covered here: S6's reviewWaveArgs half (its signature is not among the supplied declarations).

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
  mainFiles: ['src/x.js'], docFiles: [], testFiles: ['scripts/tests/x.test.mjs'], existingTestEdits: [], redProofShape: 'none',
}
const IMPL_OUT = {
  mainFilesChanged: ['src/x.js'], docFilesChanged: [], verify: [], failingExistingTests: [],
  preExistingFailures: [], publicSurface: 'none', deviations: 'none',
}
const DECL_NONE = { declarations: 'none', harnessPointers: 'ptr-kept', gaps: 'none' }
const SHORT = 'aaaaaaaa'
const PRE = 'c'.repeat(40)

function reseatDoc({ key, bound }) {
  const item = itemFixture({ short: SHORT, stages: stages.bugFixLike() })
  const args = planFixture({
    items: [item],
    project: PROJECT,
    capabilities: capsWith(key),
    ...(bound ? { serverRunId: SERVER_RUN } : {}),
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
function reseatPrompt({ key, bound, seat }) {
  const doc = reseatDoc({ key, bound })
  const opts = { cause: 'review', pre: PRE, ...(seat.startsWith('test-author') ? { declarations: 'export function f(x)' } : {}) }
  const out = execLib.reseat(loadWaveCore(), doc, reseatResult(doc), SHORT, seat, opts)
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

// ---- S13: RERUN CHECK for a blind test-author seat ----

const SUBJECTS_ONLY = '--format="%H%x09%s%x09%(trailers:key=Seat,valueonly)"'
const WITH_BODY = '%H%x09%s%x09%b'

test('S13 (AC6, D6): a test-author seat RERUN CHECK logs subjects and the Seat trailer, no %b or %B; an implementer keeps %b', () => {
  const core = loadCore(IMPLEMENT_PATH)
  const item = makeItem('13131313', stages.bugFixLike())
  const plan = makePlan([item], { key: ALT, bound: true })
  const ta = paragraphFrom(core.seatPrompt(plan, item, stageOf(item, 'test-author'), {}), 'RERUN CHECK')
  assert.ok(ta.includes(SUBJECTS_ONLY), `test-author RERUN CHECK: ${ta}`)
  assert.ok(ta.includes('e77c3e95..HEAD'), 'the range is still baseSha..HEAD')
  assert.equal(/%[bB]/.test(ta), false, 'no body placeholder (a %b or %B would fail this)')
  assert.ok(ta.includes('Seat: test-author'), 'the trailer check text survives')
  const impl = paragraphFrom(core.seatPrompt(plan, item, stageOf(item, 'implementer'), {}), 'RERUN CHECK')
  assert.ok(impl.includes(WITH_BODY), `implementer RERUN CHECK: ${impl}`)
  assert.equal(impl.includes('%(trailers'), false, 'the implementer line is unchanged')
})

test('S13 (D6): the -a1 test-author reseat uses the subjects-only form; the -a1 implementer reseat keeps %b', () => {
  const ta = paragraphFrom(reseatPrompt({ key: ALT, bound: true, seat: 'test-author-a1' }), 'RERUN CHECK')
  assert.ok(ta.includes(SUBJECTS_ONLY), ta)
  assert.equal(/%[bB]/.test(ta), false)
  const impl = paragraphFrom(reseatPrompt({ key: ALT, bound: true, seat: 'implementer-a1' }), 'RERUN CHECK')
  assert.ok(impl.includes(WITH_BODY), impl)
})

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

test('S12 (D5): buildPlanDoc copies probe.allow to meta.allow; no probe.allow leaves meta.allow absent', () => {
  const allow = { workflow: false, mcp: true, bashGit: true, bashNode: false }
  assert.deepEqual(planWith({ allow }).meta.allow, allow)
  assert.equal(planWith({}).meta.allow, undefined)
})
