// Prompt-builder tests for workflows/implement-wave.js (B1b): seatPrompt/handoff/seatActor,
// the S12 rules-leak scan, and T-scan. Node 22, node:test + node:assert/strict, no deps.
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'

import { loadCore, loadScript, fakeAgent, fakeParallel, planFixture, itemFixture, stages } from './workflow-harness.mjs'

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
  assert.ok(prompt.toLowerCase().includes('never call start from work'), 'entry prompt must forbid rerunning start from work')
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

// ---- S16: result carries runId, planDocSlug, and item ids ----

test('S16: runPlan result carries runId, planDocSlug, and per-item ids', async () => {
  const item = makeItem('44444444', stages.pluginChangeLike())
  const plan = makePlan([item], { planDocSlug: 'b1b-scratch-plan' })
  const fa = fakeAgent({})
  const script = loadScript(SCRIPT_PATH)
  const result = await script.run({ agent: fa.agent, parallel: fakeParallel, args: plan })
  assert.equal(result.runId, plan.runId)
  assert.equal(result.planDocSlug, plan.planDocSlug)
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
    assert.ok(
      !prompt.includes('mcp__mcp-task-orchestrator__advance_item'),
      `TOOLS for seat ${stage.seat} must not list advance_item in pre-entered mode`
    )
    assert.ok(
      !prompt.includes('call advance_item(transitions:[{itemId, trigger:"start", actor}]) exactly once'),
      `part 13 for seat ${stage.seat} must not print the queue-entry advance_item step in pre-entered mode`
    )
    if (stage.enters) {
      assert.ok(prompt.includes('never call advance_item'), `entering seat ${stage.seat} must still say never call advance_item`)
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
