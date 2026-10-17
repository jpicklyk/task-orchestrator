// Prompt-builder tests for workflows/implement-wave.js (B1b): seatPrompt/handoff/seatTokenFile,
// the S12 rules-leak scan, and T-scan. Node 22, node:test + node:assert/strict, no deps.
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

import { loadCore, loadScript, fakeAgent, fakeParallel, planFixture, itemFixture, stages } from './workflow-harness.mjs'
import { RETIRED_3X_TOOLS } from '../lib/contract-checks.mjs'
import { TOOLS } from '../../hooks/lib/tool-names.mjs'
import { validateAgainst } from '../run-exec-lib.mjs'

// Cwd-independent paths (Appendix A, D1 refined).
const HERE = path.dirname(fileURLToPath(import.meta.url))
const PLUGIN = path.resolve(HERE, '..', '..')
const REPO = path.resolve(PLUGIN, '..', '..')
const RULES = path.join(REPO, '.taskorchestrator', 'rules')
const SCRIPT_PATH = path.join(PLUGIN, 'workflows', 'implement-wave.js')
const IMPLEMENTER_MD = path.join(PLUGIN, 'agents', 'implementer.md')
const TARGET_PATHS = [
  SCRIPT_PATH,
  path.join(PLUGIN, 'agents', 'planner.md'),
  path.join(PLUGIN, 'agents', 'test-author.md'),
]

// ---- S12 tokenizer (identical rule for rules and targets, per Appendix A D1-refined) ----

function tokenize(text) {
  let s = String(text)
  s = s.replace(/```[\s\S]*?```/g, ' ')
  s = s.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
  const lines = s.split('\n').map((line) => {
    let l = line
    l = l.replace(/^\s*#+\s*/, ' ')
    l = l.replace(/^\s*[-*+]\s+/, ' ')
    l = l.replace(/^\s*\d+\.\s+/, ' ')
    l = l.replace(/`/g, ' ')
    l = l.replace(/\*\*/g, ' ')
    l = l.replace(/__/g, ' ')
    return l
  })
  s = lines.join('\n').toLowerCase()
  const out = []
  for (const raw of s.split(/\s+/)) {
    if (!raw) continue
    const trimmed = raw.replace(/^[^\w<>]+|[^\w<>]+$/g, '')
    if (trimmed) out.push(trimmed)
  }
  return out
}

function windows8(tokens) {
  const out = []
  for (let i = 0; i + 8 <= tokens.length; i++) out.push(tokens.slice(i, i + 8).join(' '))
  return out
}

function extractP08Sentence(implementerMdText) {
  const start = implementerMdText.indexOf('If your dispatch prompt names a seat')
  if (start === -1) throw new Error('P0.8 sentence not found in implementer.md')
  const tail = implementerMdText.slice(start)
  const endMarker = 'their own seats'
  const endIdx = tail.indexOf(endMarker)
  if (endIdx === -1) throw new Error('P0.8 sentence end marker not found in implementer.md')
  let end = endIdx + endMarker.length
  if (tail[end] === '.') end += 1
  return tail.slice(0, end)
}

function removeSubsequence(tokens, sub) {
  if (sub.length === 0) return tokens
  const out = tokens.slice()
  for (let i = 0; i + sub.length <= out.length; i++) {
    let match = true
    for (let j = 0; j < sub.length; j++) {
      if (out[i + j] !== sub[j]) {
        match = false
        break
      }
    }
    if (match) {
      out.splice(i, sub.length)
      return out
    }
  }
  return out
}

/**
 * checkRuleLeaks({rulesDir, implementerMdPath, targetPaths}) -> {mdCount, windowsCompared, leaks}
 * Exported so a red-proof run can point rulesDir at an empty temp dir.
 */
export function checkRuleLeaks({ rulesDir = RULES, implementerMdPath = IMPLEMENTER_MD, targetPaths = TARGET_PATHS } = {}) {
  const mdFiles = fs.readdirSync(rulesDir).filter((f) => f.endsWith('.md'))
  const implementerText = fs.readFileSync(implementerMdPath, 'utf8')
  const allowedTokens = tokenize(extractP08Sentence(implementerText))

  const ruleWindowSet = new Set()
  for (const f of mdFiles) {
    const text = fs.readFileSync(path.join(rulesDir, f), 'utf8')
    for (const w of windows8(tokenize(text))) ruleWindowSet.add(w)
  }

  let windowsCompared = 0
  const leaks = []
  for (const tp of targetPaths) {
    const text = fs.readFileSync(tp, 'utf8')
    const tokens = removeSubsequence(tokenize(text), allowedTokens)
    const tWindows = windows8(tokens)
    windowsCompared += tWindows.length
    for (const w of tWindows) {
      if (ruleWindowSet.has(w)) leaks.push({ target: tp, window: w })
    }
  }
  return { mdCount: mdFiles.length, windowsCompared, leaks }
}

// ---- fixtures shared across tests ----

const PROJECT = {
  searchScope: 'claude-plugins/task-orchestrator',
  scratchDir: '/tmp/scratch',
  shell: 'bash',
  verify: [
    { seats: ['implementer'], command: 'node --test scripts/tests/implement-wave.test.mjs' },
    { seats: ['test-author'], command: 'node --test scripts/tests/implement-wave.test.mjs' },
  ],
}

function makeItem(short, stageList, overrides = {}) {
  return itemFixture({
    short,
    title: `Item ${short}`,
    configFingerprint: `fp-${short}`,
    traits: ['delegated', 'session-tracked'],
    stages: stageList,
    ...overrides,
  })
}

function makePlan(items, overrides = {}) {
  return planFixture({ items, rootId: 'root-b1b', project: PROJECT, baseSha: 'e77c3e95', ...overrides })
}

// ---- S10-prompt: driftPin (fingerprint + traits) on every writing prompt ----

test('S10-prompt: writer prompts carry the pinned configFingerprint and traits', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('aaaaaaaa', stages.bugFixLike())
  const plan = makePlan([item])
  const implementerStage = item.stages.find((s) => s.seat === 'implementer')
  const prompt = core.seatPrompt(plan, item, implementerStage, {})
  assert.ok(prompt.includes(item.configFingerprint), 'prompt must pin configFingerprint')
  assert.ok(prompt.includes(JSON.stringify(item.traits)), 'prompt must include the traits list')
})

test('S10-prompt: itemTraits, when present, is pinned instead of only traits', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('bbbbbbbb', stages.bugFixLike(), { itemTraits: ['needs-test-author'] })
  const plan = makePlan([item])
  const implementerStage = item.stages.find((s) => s.seat === 'implementer')
  const prompt = core.seatPrompt(plan, item, implementerStage, {})
  assert.ok(prompt.includes(JSON.stringify(item.itemTraits)), 'prompt must pin itemTraits when present')
})

test('S10-prompt: driftPin is omitted for schema-free items and read-only seats', () => {
  const core = loadCore(SCRIPT_PATH)
  const freeStages = stages.schemaFree()
  const item = makeItem('cccccccc', freeStages, { schemaFree: true, configFingerprint: 'fp-free' })
  const plan = makePlan([item])
  const ownerStage = item.stages[0]
  const prompt = core.seatPrompt(plan, item, ownerStage, {})
  assert.ok(!prompt.includes('DRIFT PIN'), 'schema-free items must not carry a drift pin')

  const roItem = makeItem('dddddddd', [
    { seat: 'declarations-extractor', phase: 'work', inserted: true, writes: false, notes: [], dispatch: {}, output: 'declarations-v1' },
  ])
  const roPlan = makePlan([roItem])
  const roPrompt = core.seatPrompt(roPlan, roItem, roItem.stages[0], {})
  assert.ok(!roPrompt.includes('DRIFT PIN'), 'read-only seats must not carry a drift pin')
})

// ---- S11c: rerun-check + "never call start from work" ----

test('S11c: every writer prompt carries the rerun-check text', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('eeeeeeee', stages.bugFixLike())
  const plan = makePlan([item])
  const testAuthorStage = item.stages.find((s) => s.seat === 'test-author')
  const prompt = core.seatPrompt(plan, item, testAuthorStage, {})
  assert.ok(prompt.includes('RERUN CHECK'), 'writer prompt must carry the rerun-check heading')
  assert.ok(prompt.includes(`${plan.baseSha}..HEAD`), 'rerun-check must cite baseSha..HEAD')
  assert.ok(prompt.includes(`[${item.short}]`), 'rerun-check must cite the item short id')
})

test('S11c: entry prompt says never call start from work', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('ffffffff', stages.bugFixLike())
  const plan = makePlan([item])
  const implementerStage = item.stages.find((s) => s.seat === 'implementer')
  const prompt = core.seatPrompt(plan, item, implementerStage, {})
  assert.match(prompt, /never (send|call) start from work/i, 'entry prompt must forbid rerunning start from work')
})

// ---- S12: no 8-word rules line leaks into script/agent-definition prose ----

test('S12: no 8-token window from any rules/*.md file appears in implement-wave.js, planner.md, or test-author.md', () => {
  const { mdCount, windowsCompared, leaks } = checkRuleLeaks()
  assert.ok(mdCount >= 1, 'expected at least one rules/*.md file')
  assert.ok(windowsCompared >= 1, 'expected at least one 8-token window to compare')
  assert.deepEqual(leaks, [], `unexpected rule-text leak(s): ${JSON.stringify(leaks)}`)
})

test('S12 red-proof point: an empty rules dir fails the >=1 .md precondition', () => {
  const emptyDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rules-empty-'))
  try {
    const { mdCount } = checkRuleLeaks({ rulesDir: emptyDir })
    assert.throws(() => assert.ok(mdCount >= 1, 'expected at least one rules/*.md file'), /expected at least one/)
  } finally {
    fs.rmSync(emptyDir, { recursive: true, force: true })
  }
})

// ---- S15-prompt: no isolation, worktree named, agentType wiring ----

test('S15-prompt: every seatPrompt names the item worktree', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('11111111', stages.bugFixLike())
  const plan = makePlan([item])
  for (const stage of item.stages) {
    const prompt = core.seatPrompt(plan, item, stage, {})
    assert.ok(prompt.includes(item.worktree), `prompt for seat ${stage.seat} must name the worktree`)
  }
})

test('S15: fakeAgent never receives opts.isolation, and agentType is omitted when dispatch.agent is null', async () => {
  const item = makeItem('22222222', stages.pluginChangeLike())
  const plan = makePlan([item])
  const fa = fakeAgent({})
  const script = loadScript(SCRIPT_PATH)
  await script.run({ agent: fa.agent, parallel: fakeParallel, args: plan })
  assert.ok(fa.calls.length >= 1, 'expected at least one agent call')
  for (const call of fa.calls) {
    assert.equal(call.opts.isolation, undefined, 'no call may set opts.isolation')
  }
  assert.equal(fa.calls[0].opts.agentType, undefined, 'dispatch.agent absent -> agentType omitted')
})

test('S15: agentType flows from stage.dispatch.agent when present', async () => {
  const item = makeItem('33333333', stages.featureTaskLike())
  const plan = makePlan([item])
  const fa = fakeAgent({})
  const script = loadScript(SCRIPT_PATH)
  await script.run({ agent: fa.agent, parallel: fakeParallel, args: plan })
  const implementerCall = fa.calls.find((c) => c.label.startsWith('implementer:'))
  assert.ok(implementerCall, 'expected an implementer call')
  assert.equal(implementerCall.opts.agentType, 'task-orchestrator:implementer')
})

// ---- S16: result carries runId, serverRunId (4.0, replaces planDocSlug), and item ids ----

test('S16: runPlan result carries runId, serverRunId and per-item ids, and no planDocSlug', async () => {
  const item = makeItem('44444444', stages.pluginChangeLike())
  const plan = makePlan([item], { serverRunId: '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10' })
  const fa = fakeAgent({})
  const script = loadScript(SCRIPT_PATH)
  const result = await script.run({ agent: fa.agent, parallel: fakeParallel, args: plan })
  assert.equal(result.runId, plan.runId)
  assert.equal(result.serverRunId, plan.serverRunId)
  assert.equal(Object.hasOwn(result, 'planDocSlug'), false, 'result-v1 carries serverRunId, not planDocSlug (D4)')
  assert.ok(Array.isArray(result.items) && result.items.length === 1)
  assert.equal(result.items[0].id, item.id)
})

// ---- T-bash-cd: no prompt begins a command with "cd " ----

test('T-bash-cd: no seatPrompt output contains a bash "cd " command prefix', () => {
  const core = loadCore(SCRIPT_PATH)
  const cdPattern = /(^|\n|`|&&|;)\s*cd\s/
  const item = makeItem('55555555', stages.bugFixLike())
  const plan = makePlan([item])
  let checked = 0
  for (const stage of item.stages) {
    const prompt = core.seatPrompt(plan, item, stage, {})
    assert.ok(prompt.length > 0, `prompt for seat ${stage.seat} must be non-empty`)
    assert.equal(cdPattern.test(prompt), false, `prompt for seat ${stage.seat} must not contain a cd-prefixed command: ${prompt}`)
    checked += 1
  }
  assert.ok(checked >= 1, 'expected at least one prompt to be checked')
})

// ---- R5: no prompt contains bodyFromFile except the single "never use" line ----

test('R5: bodyFromFile appears at most once per prompt, on the "never use" line', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('66666666', stages.bugFixLike())
  const plan = makePlan([item])
  for (const stage of item.stages) {
    const prompt = core.seatPrompt(plan, item, stage, {})
    const occurrences = (prompt.match(/bodyFromFile/g) || []).length
    assert.ok(occurrences <= 1, `expected at most one bodyFromFile mention for seat ${stage.seat}, got ${occurrences}`)
    if (occurrences === 1) {
      assert.ok(/Never use bodyFromFile/.test(prompt), `the single bodyFromFile mention for seat ${stage.seat} must be the "never use" line`)
    }
  }
})

// ---- T-scan: scanDeclarations strips behaviour-word lines, keeps "runtime call order:" ----

test('T-scan: scanDeclarations strips behaviour-word lines and keeps runtime call order lines', () => {
  const core = loadCore(SCRIPT_PATH)
  const input = [
    'DECLARATIONS for 01bb5ffb',
    'function foo(x) returns bar',
    'runtime call order: normalizeArgs(args) calls runPlan when ok',
    'this line throws on failure',
    'a pure signature line with no behaviour words',
  ].join('\n')
  const { text, stripped } = core.scanDeclarations(input)
  assert.ok(text.includes('runtime call order: normalizeArgs(args) calls runPlan when ok'), 'runtime call order line must survive')
  assert.ok(!text.includes('function foo(x) returns bar'), 'a returns-line must be stripped')
  assert.ok(!text.includes('this line throws on failure'), 'a throws-line must be stripped')
  assert.ok(text.includes('a pure signature line with no behaviour words'), 'a behaviour-word-free line must survive')
  assert.ok(stripped.some((l) => l.includes('function foo(x) returns bar')), 'stripped must record the removed returns-line')
  assert.ok(stripped.some((l) => l.includes('this line throws on failure')), 'stripped must record the removed throws-line')
})

// ---- handoff wiring sanity (part 12) ----

test('handoff: implementer prompt carries the planner handoff fields when outs are supplied', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('77777777', stages.bugFixLike())
  const plan = makePlan([item])
  const implementerStage = item.stages.find((s) => s.seat === 'implementer')
  const outs = {
    planner: {
      decisions: 'use approach A',
      mainFiles: ['a.js'],
      docFiles: [],
      missingApiOrSeam: 'none',
      diagnosisCorrections: 'none',
    },
  }
  const prompt = core.seatPrompt(plan, item, implementerStage, outs)
  assert.ok(prompt.includes('use approach A'), 'handoff must carry the planner decisions text')
  assert.ok(prompt.includes('a.js'), 'handoff must carry the planner mainFiles')
})

// ---- O7: review fix-up regression tests ----

// 58facb39: the TOOLS: line names 4.0 tools as mcp__mcp-task-orchestrator__<tool>. Parse the tool part of every
// entry so assertions compare exact entries (a bare includes() of 'advance' or '...advance_item' is vacuous).
const MCP_TOOL_PREFIX_UNDER_TEST = 'mcp__mcp-task-orchestrator__'
function toolsEntriesOf(prompt) {
  const line = prompt.split(String.fromCharCode(10)).find((l) => l.trim().startsWith('TOOLS:'))
  assert.ok(line, 'prompt must contain a TOOLS: line')
  return line.split(MCP_TOOL_PREFIX_UNDER_TEST).slice(1).map((s) => (s.match(/^[a-z_]+/) || ['*'])[0])
}

// (a) part 6 renders every stage.skills name by value, never an Object.keys index count.
test('O7a: part 6 renders every stage.skills entry by name, never an index-count placeholder', () => {
  const core = loadCore(SCRIPT_PATH)
  const stageDef = {
    seat: 'planner', phase: 'queue', notes: [], writes: false, dispatch: {},
    output: 'planner-v1', skills: ['spec-quality', 'review-quality'],
  }
  const item = makeItem('99999991', [stageDef])
  const plan = makePlan([item])
  const prompt = core.seatPrompt(plan, item, stageDef, {})
  assert.ok(prompt.includes('spec-quality'), 'prompt must name the spec-quality skill fallback')
  assert.ok(prompt.includes('review-quality'), 'prompt must name the review-quality skill fallback')
  assert.ok(!/\(0\)/.test(prompt), 'prompt must never print an Object.keys-style index-count placeholder like "(0)"')
})

// (b) pre-entered prompts never offer or describe the queue-entry advance_item step.
test('O7b: pre-entered mode never lists the advance_item tool or the queue-entry advance step', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('99999992', stages.bugFixLike())
  const plan = makePlan([item], { entryMode: 'pre-entered' })
  for (const stage of item.stages) {
    const prompt = core.seatPrompt(plan, item, stage, {})
    // the 4.0 tool is `advance`: a includes(...advance_item) check can never fail against it, so assert on the exact TOOLS: entries
    assert.ok(
      !toolsEntriesOf(prompt).includes('advance'),
      `TOOLS for seat ${stage.seat} must not list the advance tool in pre-entered mode`
    )
    assert.ok(
      !toolsEntriesOf(prompt).includes('advance_item'),
      `TOOLS for seat ${stage.seat} must not list the retired advance_item tool`
    )
    assert.ok(
      !prompt.includes('trigger:"start"') && !/advance\(/.test(prompt),
      `part 13 for seat ${stage.seat} must not print the queue-entry advance step in pre-entered mode`
    )
    if (stage.enters) {
      assert.match(prompt, /never call advance/i, `entering seat ${stage.seat} must still say never call advance`)
    }
  }
})

// (c) agentType flow (plan §9.2 #15): retry-without-agentType + fallback flag, then stopped on a second throw.
test('O7c: an agentType throw retries once without it and records agentTypeFallback', async () => {
  const stageDef = {
    seat: 'owner', phase: 'work', notes: [], enters: true, writes: true,
    output: 'generic-v1', dispatch: { agent: 'task-orchestrator:implementer' },
  }
  const item = makeItem('88888881', [stageDef])
  const plan = makePlan([item])
  const fa = fakeAgent({
    'owner:88888881': (n, prompt, opts) => {
      if (opts.agentType) return { throw: 'boom-with-type' }
      return {
        status: 'done', reason: 'ok', notes: [], commits: { pre: '', post: '' }, files: [],
        modelReported: 'fake', entry: { applied: true, newRole: 'work' }, output: { summary: 'ok' },
      }
    },
  })
  const script = loadScript(SCRIPT_PATH)
  const result = await script.run({ agent: fa.agent, parallel: fakeParallel, args: plan })
  assert.equal(fa.calls.length, 2, 'expected the initial call plus one retry without agentType')
  assert.equal(fa.calls[0].opts.agentType, 'task-orchestrator:implementer')
  assert.equal(fa.calls[1].opts.agentType, undefined, 'the retry must omit agentType')
  const stageResult = result.items[0].stages[0]
  assert.equal(stageResult.status, 'done')
  assert.equal(stageResult.agentTypeFallback, true)
  assert.equal(stageResult.agentTypeUsed, null)
})

test('O7c: a second throw after the agentType retry yields stopped with reason starting "agent threw"', async () => {
  const stageDef = {
    seat: 'owner', phase: 'work', notes: [], enters: false, writes: true,
    output: 'generic-v1', dispatch: { agent: 'task-orchestrator:implementer' },
  }
  const item = makeItem('88888882', [stageDef])
  const plan = makePlan([item])
  const fa = fakeAgent({ 'owner:88888882': { throw: 'always-boom' } })
  const script = loadScript(SCRIPT_PATH)
  const result = await script.run({ agent: fa.agent, parallel: fakeParallel, args: plan })
  assert.equal(fa.calls.length, 2, 'expected the initial call plus one retry without agentType')
  const itemResult = result.items[0]
  assert.equal(itemResult.status, 'stopped')
  assert.ok(itemResult.reason.startsWith('agent threw'), `expected reason to start with "agent threw", got: ${itemResult.reason}`)
})

// (d) a stage whose agent() throws unconditionally leaves no unsettled milestone: runPlan resolves
// (no hang) and a dependent item waiting on that milestone is released, not left pending.
test('O7d: an unconditional agent throw settles the milestone — runPlan resolves and a dependent is not left hanging', async () => {
  const stageA = { seat: 'owner', phase: 'work', notes: [], enters: false, writes: true, output: 'generic-v1', dispatch: {} }
  const itemA = makeItem('88888883', [stageA])
  const stageB = { seat: 'owner', phase: 'work', notes: [], enters: false, writes: true, output: 'generic-v1', dispatch: {} }
  const itemB = makeItem('88888884', [stageB], { waitsFor: [{ item: itemA.id, milestone: 'owner' }] })
  const plan = makePlan([itemA, itemB])
  const fa = fakeAgent({ 'owner:88888883': { throw: 'unconditional-boom' } })
  const script = loadScript(SCRIPT_PATH)
  const result = await script.run({ agent: fa.agent, parallel: fakeParallel, args: plan })
  assert.equal(result.items.length, 2, 'runPlan must resolve with both items, not hang')
  const itemAResult = result.items.find((r) => r.id === itemA.id)
  const itemBResult = result.items.find((r) => r.id === itemB.id)
  assert.equal(itemAResult.status, 'stopped')
  assert.ok(itemAResult.reason.startsWith('agent threw'))
  assert.equal(itemBResult.status, 'deferred', 'a dependent whose blocker stopped must be released, not hang')
})

// ---- <ownedTests> verify placeholder (#395): per-seat owned test files, shared-mode contract ----

const OWNED_ENTRY = {
  name: 'plugin-node-owned-tests',
  command: 'node --test <ownedTests>',
  ownedTestsPattern: '\\.test\\.mjs$',
  seats: ['implementer', 'test-author'],
}

function ownedPrompt({ stageList, seat, planner, verify = [OWNED_ENTRY], planOverrides = {} }) {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('0a0a0a0a', stageList)
  const plan = makePlan([item], { project: { ...PROJECT, verify }, ...planOverrides })
  const stage = item.stages.find((s) => s.seat === seat)
  const outs = planner === undefined ? {} : { planner }
  return core.seatPrompt(plan, item, stage, outs)
}

function verifyBlock(prompt) {
  const i = prompt.indexOf('VERIFY:')
  if (i === -1) return ''
  const rest = prompt.slice(i)
  const j = rest.indexOf('\n\n')
  return j === -1 ? rest : rest.slice(0, j)
}

test('ownedTests: shared implementer with no test-author stage gets testFiles filtered by pattern, worktree-prefixed', () => {
  const prompt = ownedPrompt({
    stageList: stages.featureTaskLike(),
    seat: 'implementer',
    planner: { testFiles: ['scripts/tests/a.test.mjs', 'src/B.kt'], existingTestEdits: [] },
  })
  const block = verifyBlock(prompt)
  assert.ok(block.includes('node --test "/tmp/wt-0a0a0a0a/scripts/tests/a.test.mjs"'), block)
  assert.ok(!block.includes('B.kt'), 'pattern must drop non-matching paths')
  assert.ok(!block.includes('<ownedTests>'))
})

test('ownedTests: absolute owned paths are kept as-is and duplicates collapse', () => {
  const prompt = ownedPrompt({
    stageList: stages.featureTaskLike(),
    seat: 'implementer',
    planner: {
      testFiles: ['D:/x/a.test.mjs'],
      existingTestEdits: [{ file: 'd:/x/a.test.mjs' }],
    },
  })
  assert.ok(verifyBlock(prompt).includes('node --test "d:/x/a.test.mjs"'), verifyBlock(prompt))
  assert.equal(verifyBlock(prompt).split('a.test.mjs').length - 1, 1)
})

test('ownedTests: empty owned set (or no planner output) renders SKIP and no node --test', () => {
  for (const planner of [{ testFiles: ['src/B.kt'], existingTestEdits: [] }, undefined]) {
    const prompt = ownedPrompt({ stageList: stages.featureTaskLike(), seat: 'implementer', planner })
    const block = verifyBlock(prompt)
    assert.ok(block.includes('SKIP plugin-node-owned-tests: no owned test files'), block)
    assert.ok(!block.includes('node --test'), block)
  }
})

test('ownedTests: an invalid ownedTestsPattern renders SKIP naming the pattern, never an unfiltered command', () => {
  const prompt = ownedPrompt({
    stageList: stages.featureTaskLike(),
    seat: 'implementer',
    planner: { testFiles: ['scripts/tests/a.test.mjs', 'src/B.kt'], existingTestEdits: [] },
    verify: [{ ...OWNED_ENTRY, ownedTestsPattern: '(' }],
  })
  const block = verifyBlock(prompt)
  assert.ok(block.includes('SKIP plugin-node-owned-tests: invalid ownedTestsPattern "("'), block)
  assert.ok(!block.includes('node --test'), block)
  assert.ok(!block.includes('B.kt'), block)
})

test('ownedTests: implementer WITH a test-author stage owns only existingTestEdits; test-author owns both', () => {
  const planner = { testFiles: ['new.test.mjs'], existingTestEdits: [{ file: 'old.test.mjs' }] }
  const impl = verifyBlock(ownedPrompt({ stageList: stages.bugFixLike(), seat: 'implementer', planner }))
  assert.ok(impl.includes('"/tmp/wt-0a0a0a0a/old.test.mjs"'), impl)
  assert.ok(!impl.includes('new.test.mjs'), impl)
  const ta = verifyBlock(ownedPrompt({ stageList: stages.bugFixLike(), seat: 'test-author', planner }))
  assert.ok(ta.includes('new.test.mjs') && ta.includes('old.test.mjs'), ta)
})

test('ownedTests: shared-mode contract line present iff worktreeMode is shared and VERIFY is non-empty', () => {
  const planner = { testFiles: ['a.test.mjs'], existingTestEdits: [] }
  const shared = ownedPrompt({ stageList: stages.featureTaskLike(), seat: 'implementer', planner })
  assert.ok(shared.includes('owned by another item or seat are expected'))
  const perItem = ownedPrompt({
    stageList: stages.featureTaskLike(), seat: 'implementer', planner, planOverrides: { worktreeMode: 'per-item' },
  })
  assert.ok(!perItem.includes('owned by another item or seat are expected'))
  const none = ownedPrompt({ stageList: stages.featureTaskLike(), seat: 'implementer', planner, verify: [] })
  assert.ok(!none.includes('VERIFY:') && !none.includes('are expected'))
})

test('ownedTests: entries without the placeholder render verbatim', () => {
  const prompt = ownedPrompt({
    stageList: stages.featureTaskLike(),
    seat: 'implementer',
    planner: { testFiles: ['a.test.mjs'], existingTestEdits: [] },
    verify: [{ name: 'x', command: 'echo <worktree> hi', seats: ['implementer'] }],
  })
  assert.ok(verifyBlock(prompt).startsWith('VERIFY:\necho <worktree> hi'))
})

// ---- S12 (58facb39): exact TOOLS: entries per seat, AC4 mapping ----
// Oracle: task-scope AC4. query_items+get_context -> items_read, query_notes -> notes_read, query_dependencies ->
// deps_read, query_rules -> config, manage_notes -> notes_write, advance_item -> advance. implement-wave gets
// items_read, notes_read, deps_read, config; plus notes_write unless the stage is read-only (writes nothing and owns
// no notes); plus advance when the stage enters and the run is not pre-entered. Valid names: golden tool basenames.

const S12_GOLDEN_DIR = path.join(REPO, 'current', 'src', 'test', 'resources', 'golden', 'tools')
const S12_TOOLS = [...new Set(fs.readdirSync(S12_GOLDEN_DIR).filter((f) => f.endsWith('.json')).map((f) => f.split('.')[0]))]
const S12_BASE_READ = ['config', 'deps_read', 'items_read', 'notes_read']

function s12Entries(prompt) {
  const entries = toolsEntriesOf(prompt)
  assert.equal(new Set(entries).size, entries.length, 'TOOLS: entries must be unique')
  for (const e of entries) assert.ok(S12_TOOLS.includes(e), `TOOLS: entry "${e}" is not a 4.0 tool`)
  return [...entries].sort()
}

const S12_PLANNER = { seat: 'planner', phase: 'queue', writes: false, notes: ['task-scope'], dispatch: {}, output: 'planner-v1' }
const S12_ENTERING = { seat: 'implementer', phase: 'work', enters: true, writes: true, notes: ['implementation-notes'], dispatch: {}, output: 'implementer-v1' }
const S12_READ_ONLY = { seat: 'declarations-extractor', phase: 'work', inserted: true, writes: false, notes: [], dispatch: {}, output: 'declarations-v1' }
const S12_TEST_AUTHOR = { seat: 'test-author', phase: 'work', writes: true, notes: ['test-manifest'], dispatch: {}, output: 'test-author-v1' }

test('S12: a note-owning planner seat gets the four read tools plus notes_write and never advance', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('12aaaaa1', [S12_PLANNER])
  assert.deepEqual(s12Entries(core.seatPrompt(makePlan([item]), item, S12_PLANNER, {})), [...S12_BASE_READ, 'notes_write'].sort())
})

test('S12: an entering implementer in seat mode gets the read tools, notes_write and advance', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('12aaaaa2', [S12_ENTERING])
  assert.deepEqual(s12Entries(core.seatPrompt(makePlan([item]), item, S12_ENTERING, {})), [...S12_BASE_READ, 'advance', 'notes_write'].sort())
})

test('S12: a pre-entered implementer keeps notes_write but loses advance', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('12aaaaa3', [S12_ENTERING])
  const plan = makePlan([item], { entryMode: 'pre-entered' })
  assert.deepEqual(s12Entries(core.seatPrompt(plan, item, S12_ENTERING, {})), [...S12_BASE_READ, 'notes_write'].sort())
})

test('S12: a read-only stage gets only the four read tools', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('12aaaaa4', [S12_READ_ONLY])
  assert.deepEqual(s12Entries(core.seatPrompt(makePlan([item]), item, S12_READ_ONLY, {})), S12_BASE_READ)
})

test('S12: a non-entering writer (test-author) gets notes_write but not advance, in both entry modes', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('12aaaaa5', [S12_TEST_AUTHOR])
  for (const entryMode of ['seat', 'pre-entered']) {
    const plan = makePlan([item], { entryMode })
    assert.deepEqual(s12Entries(core.seatPrompt(plan, item, S12_TEST_AUTHOR, {})), [...S12_BASE_READ, 'notes_write'].sort(), entryMode)
  }
})

test('S12: no seat prompt lists a retired 3.x tool name as a TOOLS: entry', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('12aaaaa6', stages.bugFixLike())
  const retired = ['advance_item', 'manage_notes', 'query_items', 'query_notes', 'query_dependencies', 'query_rules', 'get_context', 'manage_items']
  for (const stage of item.stages) {
    for (const mode of ['seat', 'pre-entered']) {
      const entries = toolsEntriesOf(core.seatPrompt(makePlan([item], { entryMode: mode }), item, stage, {}))
      for (const r of retired) assert.ok(!entries.includes(r), `${stage.seat}/${mode} lists retired ${r}`)
    }
  }
})

// ==============================================================================================
// 4.0 port (item cac1bffa): seat tokens, END SEAT, deviation raise, retry classes, drift pin,
// envelope additions, and the retired-tool / actor sweep over every seat prompt.
// Oracles: task-scope D2 (token file hand-off), D3 (end_seat), AC1 (actor removed), AC2 (4.0 names
// and the RESULTS rule), AC4 (new parts, envelope fields), AC5 (retry classes), AC7 (deviation).
// Each negative assertion names the fixture condition that would fail it.
// ==============================================================================================

const SERVER_RUN_ID = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10'
const SCRATCH = '/tmp/scratch'

function boundPlan(item, overrides = {}) {
  return makePlan([item], { serverRunId: SERVER_RUN_ID, ...overrides })
}
function stageOfSeat(item, seat) {
  return item.stages.find((s) => s.seat === seat)
}
/** The paragraph of a prompt that starts with `head` (up to the next blank line). */
function partOf(prompt, head) {
  const start = prompt.indexOf(head)
  assert.ok(start !== -1, `prompt has no ${head} part`)
  const rest = prompt.slice(start)
  const end = rest.search(/\n\n/)
  return end === -1 ? rest : rest.slice(0, end)
}
function wholeWord(prompt, name) {
  return new RegExp('(^|[^A-Za-z0-9_])' + name + '([^A-Za-z0-9_]|$)').test(prompt)
}

test('S4 (D2): a bound plan gives every schema seat a SEAT TOKEN part naming <scratchDir>/tokens/<short>.<seat>.token, the seatToken field, the no-print rule and the rejection stop', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('a1a1a1a1', stages.bugFixLike())
  const plan = boundPlan(item)
  for (const seat of ['planner', 'implementer', 'test-author']) {
    const p = core.seatPrompt(plan, item, stageOfSeat(item, seat), {})
    const part = partOf(p, 'SEAT TOKEN')
    assert.ok(part.includes(`${SCRATCH}/tokens/a1a1a1a1.${seat}.token`), `${seat}: token file path`)
    assert.ok(part.includes(SERVER_RUN_ID), `${seat}: the part names the server run`)
    assert.ok(p.includes('seatToken'), `${seat}: names the top-level seatToken field`)
    assert.match(p, /never print/i, `${seat}: no-print rule`)
    assert.ok(p.includes('The seat token is not accepted.'), `${seat}: the unauthenticated message`)
    assert.ok(p.includes('seat-token-rejected'), `${seat}: the stop reason`)
  }
})

test('S4 (D2): the inserted declarations-extractor runs token-less - no token file, no END SEAT part, no runs_write - and a plan without serverRunId renders neither part for any seat', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('a2a2a2a2', stages.bugFixLike())
  const bound = core.seatPrompt(boundPlan(item), item, stageOfSeat(item, 'declarations-extractor'), {})
  assert.equal(bound.includes('/tokens/'), false, 'no token file path')
  assert.equal(bound.includes('END SEAT:'), false)
  assert.ok(/SEAT TOKEN: none/.test(bound), 'the part says this stage has no token')
  assert.ok(!toolsEntriesOf(bound).includes('runs_write'), 'a token-less read-only seat is not given runs_write')
  const unbound = makePlan([item])
  for (const stage of item.stages) {
    const p = core.seatPrompt(unbound, item, stage, {})
    assert.equal(p.includes('/tokens/'), false, `${stage.seat}: no serverRunId, no token file`)
    assert.equal(p.includes('END SEAT:'), false, `${stage.seat}: no serverRunId, no end-seat part`)
    if (stage.seat !== 'declarations-extractor') assert.equal(p.includes('SEAT TOKEN'), false, `${stage.seat}: no serverRunId, no token part`)
  }
  // fixture condition: the same seat in the bound plan does carry the part
  assert.ok(core.seatPrompt(boundPlan(item), item, stageOfSeat(item, 'implementer'), {}).includes('SEAT TOKEN'))
})

test('S4 (D3): the END SEAT part tells the seat to call runs_write end_seat last, with outcome and summary, and to set seatEnded', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('a3a3a3a3', stages.bugFixLike())
  const p = core.seatPrompt(boundPlan(item), item, stageOfSeat(item, 'implementer'), {})
  const part = partOf(p, 'END SEAT:')
  assert.ok(part.includes('end_seat'))
  assert.ok(part.includes('runs_write'))
  for (const word of ['outcome', 'summary', 'seatEnded', 'done', 'failed', 'abandoned']) {
    assert.ok(part.includes(word), `END SEAT part names ${word}`)
  }
  assert.ok(/500/.test(part), 'the summary limit (500 characters) is named')
})

test('S4 (D2): seatTokenFile(plan, item, stage) is <scratchDir>/tokens/<short>.<seat>.token for a schema seat and empty for the inserted extractor', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('a4a4a4a4', stages.bugFixLike())
  const plan = boundPlan(item)
  assert.equal(core.seatTokenFile(plan, item, stageOfSeat(item, 'implementer')), `${SCRATCH}/tokens/a4a4a4a4.implementer.token`)
  assert.equal(core.seatTokenFile(plan, item, stageOfSeat(item, 'test-author')), `${SCRATCH}/tokens/a4a4a4a4.test-author.token`)
  assert.ok(!core.seatTokenFile(plan, item, stageOfSeat(item, 'declarations-extractor')), 'inserted stages have no token file')
})

test('S4 (AC1/AC2): no seat prompt, in seat or pre-entered mode, bound or unbound, carries an ACTOR line, the word actor, a retired 3.x tool name, or a TOOLS entry outside the 4.0 tool set', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('a5a5a5a5', stages.bugFixLike(), { itemTraits: ['needs-test-author'] })
  let checked = 0
  for (const entryMode of ['seat', 'pre-entered']) {
    for (const plan of [makePlan([item], { entryMode }), boundPlan(item, { entryMode })]) {
      for (const stage of item.stages) {
        const p = core.seatPrompt(plan, item, stage, {})
        checked += 1
        assert.equal(p.includes('ACTOR:'), false, `${stage.seat}/${entryMode}: ACTOR line`)
        assert.equal(/\bactor\b/i.test(p), false, `${stage.seat}/${entryMode}: the word actor`)
        for (const name of RETIRED_3X_TOOLS) assert.equal(wholeWord(p, name), false, `${stage.seat}/${entryMode}: retired tool ${name}`)
        for (const entry of toolsEntriesOf(p)) assert.ok(TOOLS.includes(entry), `${stage.seat}/${entryMode}: TOOLS entry ${entry} is a 4.0 tool`)
      }
    }
  }
  assert.equal(checked, 16)
})

test('S4 (AC2): the drift pin reads items_read schema -> configFingerprint and, for an item with itemTraits, items_read get -> traits as the top-level array', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('a6a6a6a6', stages.bugFixLike(), { itemTraits: ['needs-test-author'] })
  const p = core.seatPrompt(makePlan([item]), item, stageOfSeat(item, 'implementer'), {})
  const pin = partOf(p, 'DRIFT PIN')
  assert.ok(/items_read\([^)]*schema[^)]*\)/.test(pin), 'schema read')
  assert.ok(pin.includes('configFingerprint'))
  assert.ok(pin.includes(item.configFingerprint))
  assert.ok(/items_read\([^)]*get[^)]*\)[^\n]*traits/.test(pin), 'get -> traits')
  assert.ok(/top-level/.test(pin), 'traits is the top-level array')
  assert.ok(pin.includes(JSON.stringify(item.itemTraits)))
  assert.equal(pin.includes('properties.traits'), false, 'the 3.x properties.traits path is gone')
  assert.ok(pin.includes('schema-changed'), 'a mismatch stops with schema-changed')
})

test('S4 (AC2): the RULES part fetches rules through the config tool', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('a7a7a7a7', stages.bugFixLike())
  const p = core.seatPrompt(makePlan([item]), item, stageOfSeat(item, 'implementer'), {})
  const part = partOf(p, 'RULES')
  assert.ok(part.includes('config('))
  assert.ok(part.includes('op:"rules"'))
  assert.equal(part.includes('query_rules'), false)
})

test('S5 (AC2): the RESULTS rule - per-element data.results[i].error, partial_failure only means look per element, atomic batches are the exception', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('a8a8a8a8', stages.bugFixLike())
  const p = core.seatPrompt(makePlan([item]), item, stageOfSeat(item, 'implementer'), {})
  const part = partOf(p, 'RESULTS')
  assert.ok(part.includes('data.results[i]'))
  assert.ok(part.includes('partial_failure'))
  assert.ok(/atomic/.test(part), 'the atomic-batch exception')
  assert.ok(/per element|each element|look per element/.test(part), 'the per-element reading rule')
})

test('S5 (AC5): the ERRORS part retries unavailable once, never retries run_paused, and treats the ownership and decision codes as permanent', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('a9a9a9a9', stages.bugFixLike())
  const p = core.seatPrompt(makePlan([item]), item, stageOfSeat(item, 'implementer'), {})
  const errors = partOf(p, 'ERRORS')
  assert.ok(/Retried:\s*unavailable,\s*once/.test(errors), 'unavailable is retried once')
  const runPausedLine = errors.split('\n').find((l) => l.startsWith('run_paused'))
  assert.ok(runPausedLine, 'a run_paused line exists')
  assert.ok(/never retry/i.test(runPausedLine))
  assert.ok(runPausedLine.includes('pauseReason') && runPausedLine.includes('pauseDecisionId'))
  assert.ok(/status "stopped", reason "run_paused"/.test(runPausedLine), 'the stop reason')
  const permanent = errors.slice(errors.search(/Permanent/))
  for (const code of ['note_owned_by_other', 'ownerSeat', 'seat_forbidden', 'not_claim_holder', 'decision_closed', 'forbidden', 'detail.required', 'not_found', 'invalid_transition']) {
    assert.ok(permanent.includes(code), `permanent list names ${code}`)
  }
  // cae5d141 (carry-in p17-redproof-m3): the whole ERRORS part is asserted, not a slice cut at the first run_paused,
  // so naming run_paused on the Retried: line cannot hide behind the dedicated "never retry" line.
  // Fixture condition: a Retried: line that listed run_paused fails the first loop; a second run_paused line (or none)
  // fails the count.
  const errorLines = errors.split('\n')
  const retriedLines = errorLines.filter((l) => l.includes('Retried:'))
  assert.ok(retriedLines.length >= 1, 'the ERRORS part has a Retried: line')
  for (const l of retriedLines) assert.equal(l.includes('run_paused'), false, `a Retried: line names run_paused: ${l}`)
  assert.equal(errorLines.filter((l) => l.startsWith('run_paused')).length, 1, 'exactly one line starts with run_paused')
})

test('S5 (AC5): a success carrying paused:{decisionId} stops the seat; run_at_capacity is coordinator-only and backed off; a withdrawn pause leaves the run paused', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('b1b1b1b1', stages.bugFixLike())
  const p = core.seatPrompt(makePlan([item]), item, stageOfSeat(item, 'implementer'), {})
  const errors = partOf(p, 'ERRORS')
  assert.ok(errors.includes('paused:{decisionId}'))
  assert.ok(/no further write/i.test(errors))
  assert.ok(errors.includes('run_at_capacity'))
  assert.ok(/coordinator/.test(errors.split('\n').find((l) => l.startsWith('run_at_capacity')) || ''))
  assert.ok(/back(s)? off/.test(errors))
  assert.ok(/withdrawn pause decision leaves the run paused/.test(errors))
  assert.ok(/never resume or cancel an item yourself/.test(p), 'BLOCKED: only resume and cancel, never by the seat')
})

test('S5 (AC5): CONFIG RETRY names unavailable (shedding), never config_unavailable, and keeps the config-unavailable reason string', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('b2b2b2b2', stages.bugFixLike())
  const p = core.seatPrompt(makePlan([item]), item, stageOfSeat(item, 'implementer'), {})
  const part = partOf(p, 'CONFIG RETRY')
  assert.ok(part.includes('unavailable'))
  assert.equal(p.includes('config_unavailable'), false)
  assert.ok(part.includes('"config-unavailable"'))
})

test('S6 (AC7): a writing seat gets the DEVIATION part - record under ## Deviations requested, raise a blocking deviation decision under its token, stop awaiting_decision with the decisionId', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('b3b3b3b3', stages.bugFixLike())
  const p = core.seatPrompt(boundPlan(item), item, stageOfSeat(item, 'implementer'), {})
  const part = partOf(p, 'DEVIATION')
  assert.ok(part.includes('## Deviations requested'))
  assert.ok(part.includes('runs_write'))
  assert.ok(part.includes('op:"raise"'))
  assert.ok(part.includes('class:"deviation"'))
  assert.ok(part.includes('block:true'))
  assert.ok(part.includes('dedupeKey:"b3b3b3b3:implementer:dev-<n>"'))
  for (const field of ['question', 'options', 'recommended', 'itemId']) assert.ok(part.includes(field), `raise names ${field}`)
  assert.ok(part.includes('awaiting_decision'))
  assert.ok(part.includes('decisionId'))
  assert.ok(/stall is never a reason to raise/.test(p), 'D9: a stall never raises')
})

test('S6 (AC7): the read-only inserted extractor has no DEVIATION part', () => {
  const core = loadCore(SCRIPT_PATH)
  const item = makeItem('b4b4b4b4', stages.bugFixLike())
  const p = core.seatPrompt(boundPlan(item), item, stageOfSeat(item, 'declarations-extractor'), {})
  assert.equal(p.includes('DEVIATION'), false)
})

test('S6 (AC4/D6): the seat envelope schema keeps the status enum, requires only key on notes[] items (actor tolerated), and adds optional seatEnded, decisionId and paused', () => {
  const core = loadCore(SCRIPT_PATH)
  const schema = core.envelopeSchema('planner-v1')
  assert.deepEqual(schema.properties.status.enum, ['done', 'stopped', 'deferred'])
  assert.deepEqual(schema.properties.notes.items.required, ['key'])
  assert.equal(Object.hasOwn(schema.properties.notes.items.properties, 'actor'), false, 'actor is not a declared note property')
  assert.equal(schema.properties.seatEnded.type, 'boolean')
  assert.equal(schema.properties.decisionId.type, 'string')
  assert.equal(schema.properties.paused.type, 'object')
  assert.equal(schema.properties.paused.properties.decisionId.type, 'string')
  for (const optional of ['seatEnded', 'decisionId', 'paused']) {
    assert.equal(schema.required.includes(optional), false, `${optional} stays optional`)
  }
})

test('S6 (AC4/D6): an envelope with notes[{key}], seatEnded, decisionId and paused validates; status "blocked" is invalid; notes with a tolerated actor still validate', () => {
  const core = loadCore(SCRIPT_PATH)
  const schema = core.envelopeSchema('generic-v1')
  const base = {
    status: 'stopped', reason: 'awaiting_decision', notes: [{ key: 'diagnosis' }], commits: { pre: '', post: '' }, files: [],
    modelReported: 'x', output: { summary: 'none' }, seatEnded: true, decisionId: 'd-1', paused: { decisionId: 'd-2' },
  }
  assert.deepEqual(validateAgainst(schema, base), [])
  assert.ok(validateAgainst(schema, { ...base, status: 'blocked' }).length > 0, 'blocked is not a status')
  assert.deepEqual(validateAgainst(schema, { ...base, notes: [{ key: 'diagnosis', actor: { id: 'x', kind: 'subagent' } }] }), [], 'a leftover actor is tolerated')
  assert.ok(validateAgainst(schema, { ...base, notes: [{ chars: 3 }] }).length > 0, 'key is required on every note')
})
