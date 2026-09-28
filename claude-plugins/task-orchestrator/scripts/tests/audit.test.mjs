// ESM tests for workflows/audit.js (item c039bb77). Blind test-author: this file, and its
// sibling workflow-harness-ext.mjs, are the ONLY files the author touches. The script itself
// is never opened directly (Read/Grep/sed/cat/git diff/etc.) — every fact about it used below
// comes from the frozen declarations block and reaches the script only through loadScript /
// loadCoreNamed / scriptText / coreSlice (workflow-harness.mjs, workflow-harness-ext.mjs).
//
// Scenario ids (S1-S13 + probes) match the item's `test-plan` note. Oracles are cited per
// scenario: plan/phase-c-workflows.md §2.x, plans/workflows-reference/architectural-review.js
// (kept-behavior reference), and Appendix B of plans/phase-c-dispatch-contract.md (frozen
// signatures/shapes/reasons — the compile-against source for this file per that contract's
// header). Constants and schema literals below are copied verbatim from the supplied
// declarations, never read from the script.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { dirname, join, resolve } from 'node:path'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'

import { scriptText, coreSlice, loadMeta, loadScript, fakeParallel } from './workflow-harness.mjs'
import {
  loadCoreNamed,
  autoAgent,
  fakePipeline,
  assertBarrier,
  scanForbiddenApis,
  freeRuntimeIds,
  ruleWindowHits,
} from './workflow-harness-ext.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SCRIPT_PATH = join(HERE, '..', '..', 'workflows', 'audit.js')
const PLUGIN = resolve(HERE, '..', '..')
const REPO = resolve(PLUGIN, '..', '..')
const RULES_DIR = join(REPO, '.taskorchestrator', 'rules')

const CORE_NAMES = [
  'normalizeArgs', 'projectAgents', 'sizeGuard', 'flattenFindings', 'mergeFindings',
  'verifyPlan', 'decideVerdict', 'capVerification', 'reportIds', 'schemas',
  'reviewerPreamble', 'buildReviewerPrompt', 'buildLensPrompt', 'buildGapPrompt',
  'normalizeObsPath', 'isObservation', 'buildProposal', 'runAudit',
  'SEVERITY_RANK', 'LENS_KEYS', 'PRESETS', 'CATEGORIES', 'DEFAULT_CLASS_MAP', 'TYPE_MAP_KEY',
]
const CORE = loadCoreNamed(SCRIPT_PATH, CORE_NAMES)

// ── fixture builders ─────────────────────────────────────────────────────────

function reviewerSpec(key, overrides = {}) {
  return {
    key, label: `Reviewer ${key}`, focus: 'focus text',
    paths: ['claude-plugins/task-orchestrator/hooks'],
    ...overrides,
  }
}

function baseArgs(overrides = {}) {
  return {
    contract: 'audit/args-v1',
    runId: 'a-test-0001',
    date: '2026-09-28',
    repo: { root: '/tmp/repo', description: 'test repo' },
    reportPath: '/tmp/audit-report.md',
    preset: 'quick',
    scope: {
      paths: ['claude-plugins/task-orchestrator/hooks'],
      reviewers: [reviewerSpec('r1'), reviewerSpec('r2')],
      lenses: [],
    },
    ...overrides,
  }
}

/** Builds a normalized plan via the core's own normalizeArgs (a pure, oracled function —
 * see S8 below), so fixtures stay valid against whatever normalizeArgs actually requires. */
function buildPlan(overrides = {}) {
  const { ok, plan, reason } = CORE.normalizeArgs(baseArgs(overrides))
  assert.equal(ok, true, `buildPlan: normalizeArgs unexpectedly failed: ${reason}`)
  return plan
}

function makeFinding(overrides = {}) {
  return {
    title: 'A finding', severity: 'high', category: 'coupling',
    locations: ['claude-plugins/task-orchestrator/hooks/example.mjs:1'],
    evidence: 'evidence text', impact: 'impact text', recommendation: 'fix it',
    effort: 'M', confidence: 'high',
    ...overrides,
  }
}
function findingsResult({ sliceSummary = 'summary', strengths = [], findings = [makeFinding()] } = {}) {
  return { sliceSummary, strengths, findings }
}
function scopeResult({ reviewers, lenses = [] } = {}) {
  return { reviewers, lenses }
}
function criticResult({ assessment = 'ok', gaps = [] } = {}) {
  return { assessment, gaps }
}
function dedupResult({ groups = [] } = {}) {
  return { groups }
}
function verdictResult(overrides = {}) {
  return {
    refuted: false, confidence: 'high', evidenceAccurate: true, correctedEvidence: '',
    adjustedSeverity: 'high', alreadyTrackedId: '', rationale: 'r',
    ...overrides,
  }
}
function synthResult(overrides = {}) {
  return {
    reportPath: '/tmp/audit-report.md', healthVerdict: 'ok', executiveSummary: 'summary',
    themes: [], findingIndex: [], roadmap: { now: [], next: [], later: [] },
    ...overrides,
  }
}
function triageResult({ results = [] } = {}) {
  return { results }
}

function makeDeps({ agent, phaseLog = [], logLines = [] } = {}) {
  return {
    agent,
    parallel: fakeParallel,
    pipeline: fakePipeline,
    phase: (title) => phaseLog.push(title),
    log: (msg) => logLines.push(msg),
  }
}

/** A responder covering every label shape with schema-valid, content-agnostic answers.
 * Used where the test only cares about structural/invariant properties (prompt content,
 * determinism, forbidden APIs) and not about specific finding ids or counts. Assumes
 * gaps/items are both disabled in the plan (the default from buildPlan/baseArgs). */
function standardResponder(plan) {
  return async (label) => {
    if (label.startsWith('review:')) return findingsResult({ findings: [makeFinding({ severity: 'medium' })] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('standardResponder: unexpected label ' + label)
  }
}

// ── S10: T-meta ──────────────────────────────────────────────────────────────
// Oracle: declarations META block (verbatim), copied here without alteration.

test('S10 / T-meta: meta is a pure literal; name and phases are exact per Appendix B, description/whenToUse carry the required content', () => {
  // loadMeta evaluates the literal inside a separate vm context (workflow-harness.mjs), so the
  // returned object's Object.prototype is from that other realm — deepStrictEqual treats that
  // as "same structure but not reference-equal" against a same-realm plain object. Round-trip
  // through JSON (the literal is plain strings/arrays/objects only) to compare by value.
  const meta = JSON.parse(JSON.stringify(loadMeta(SCRIPT_PATH)))
  assert.equal(meta.name, 'audit')
  assert.deepEqual(meta.phases, [
    { title: 'Scope' }, { title: 'Review' }, { title: 'Gaps' }, { title: 'Merge' },
    { title: 'Verify' }, { title: 'Synthesize' }, { title: 'Triage' },
  ])
  // Appendix B freezes `name` and `phases` verbatim, but declares description/whenToUse as
  // placeholders (`description:<string>`, `whenToUse:<names presets ...>`) — content
  // requirements, not exact wording. Asserting exact characters here would make this test an
  // oracle-from-a-snapshot rather than an oracle-from-the-spec; check the content Appendix B
  // actually requires instead.
  assert.equal(typeof meta.description, 'string')
  assert.ok(meta.description.length > 0)
  for (const preset of ['quick', 'standard', 'full']) {
    assert.ok(meta.whenToUse.includes(preset), `whenToUse must name preset "${preset}"`)
  }
  for (const requiredArg of ['runId', 'date', 'reportPath']) {
    assert.ok(meta.whenToUse.includes(requiredArg), `whenToUse must name required arg "${requiredArg}"`)
  }
  assert.match(meta.whenToUse, /90/, "whenToUse must state full's time cost (~90 minutes)")
  assert.match(meta.whenToUse, /30M|30 ?million/i, "whenToUse must state full's token cost (~30M tokens)")
  // Review finding O2: whenToUse must also warn that quick fits the default maxAgents (60)
  // while standard/full need allowLarge (or a raised maxAgents) — required by arbitration
  // case 2 (coordinator citation: Phase C contract Appendix B META + review finding O2).
  assert.match(meta.whenToUse, /maxAgents/, 'whenToUse must name maxAgents')
  assert.match(meta.whenToUse, /allowLarge/, 'whenToUse must name allowLarge')
})

// ── S11: T-core-pure ─────────────────────────────────────────────────────────
// Oracle: B1 Appendix B / phase-c-dispatch-contract MARKERS — "the core references no
// runtime global (agent, parallel, pipeline, phase, log, args, budget, workflow)".

test('S11 / T-core-pure: core references no runtime global after stripping comments/strings', () => {
  assert.deepEqual(freeRuntimeIds(coreSlice(SCRIPT_PATH)), [])
})

// ── S9: no repo strings ──────────────────────────────────────────────────────
// Oracle: test-plan S9; contract MARKERS list of forbidden repo strings.

test('S9: script text contains no repo-identifying strings', () => {
  const text = scriptText(SCRIPT_PATH)
  for (const s of ['current/src', 'D:/', 'D:\\', 'mcptask', 'jpicklyk', 'claude-plugins/']) {
    assert.equal(text.includes(s), false, `script text must not contain ${JSON.stringify(s)}`)
  }
})

// ── S5 (part 1): forbidden APIs ──────────────────────────────────────────────
// Oracle: contract MARKERS — no Date.now(/Math.random(/new Date()/import(/require(.

test('S5: script text contains no forbidden timestamp/randomness/import APIs', () => {
  assert.deepEqual(scanForbiddenApis(scriptText(SCRIPT_PATH)), [])
})

// ── S13: no rule text ─────────────────────────────────────────────────────────
// Oracle: b1-dispatch-contract.md Appendix A S12 tokenizer, reused verbatim by the ext.

test('S13: script contains no verbatim 8-token window from any served rule', () => {
  const { hits, windows } = ruleWindowHits(scriptText(SCRIPT_PATH), RULES_DIR)
  assert.deepEqual(hits, [])
  assert.ok(windows >= 1, 'expected at least one rule window to have been compared (non-vacuous)')
})

test('S13 red-proof support: an empty rules dir compares zero windows (never a silent pass)', () => {
  const emptyDir = mkdtempSync(join(tmpdir(), 'audit-rules-empty-'))
  const { hits, windows } = ruleWindowHits(scriptText(SCRIPT_PATH), emptyDir)
  assert.deepEqual(hits, [])
  assert.equal(windows, 0)
})

// ── S12 / T-schema ────────────────────────────────────────────────────────────
// Oracle: schemas(plan) body given verbatim in the declarations; validated structurally
// (required subset of properties, recursively) plus the documented result-v1 top-level keys.

function assertSchemaShapeValid(schema, path) {
  if (!schema) return
  if (schema.type === 'object') {
    const required = schema.required || []
    const props = schema.properties || {}
    for (const r of required) {
      assert.ok(r in props, `${path}: required "${r}" missing from properties`)
    }
    for (const [key, sub] of Object.entries(props)) assertSchemaShapeValid(sub, `${path}.${key}`)
  }
  if (schema.type === 'array' && schema.items) assertSchemaShapeValid(schema.items, `${path}[]`)
}

test('S12 / T-schema: every schemas(plan) sub-schema has required subset of properties, recursively', () => {
  const plan = buildPlan()
  const built = CORE.schemas(plan)
  for (const name of ['SCOPE', 'FINDINGS', 'CRITIC', 'DEDUP', 'VERDICT', 'SYNTH', 'TRIAGE']) {
    assert.ok(built[name], `schemas(plan) must return ${name}`)
    assertSchemaShapeValid(built[name], name)
  }
  // FINDINGS.category enum must come from plan.categories (declarations: "category enum from args.categories").
  assert.deepEqual(built.FINDINGS.properties.findings.items.properties.category.enum, plan.categories)
})

test('S12: audit/result-v1 has exactly the documented top-level keys', async () => {
  const plan = buildPlan()
  const { agent } = autoAgent(standardResponder(plan))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.deepEqual(
    Object.keys(result).sort(),
    ['contract', 'started', 'runId', 'stats', 'report', 'kept', 'refuted', 'proposal'].sort()
  )
  assert.equal(result.contract, 'audit/result-v1')
  assert.deepEqual(
    Object.keys(result.stats).sort(),
    [
      'reviewers', 'gaps', 'raw', 'merged', 'kept', 'confirmed', 'plausible', 'unverified',
      'refuted', 'missing', 'projectedAgents', 'droppedGaps', 'unverifiedByCap',
    ].sort()
  )
})

// ── S8: normalizeArgs / sizeGuard / projectAgents ────────────────────────────
// Oracle: phase-c-dispatch-contract.md Appendix B "normalizeArgs(raw)" / "projectAgents(plan)"
// / "sizeGuard(plan)" paragraphs (frozen, compiled-against source per the contract header).

test('S8: bad/absent input shapes all fail with a reason starting "invalid args"', () => {
  for (const raw of [undefined, '', 'x{', {}]) {
    const { ok, reason } = CORE.normalizeArgs(raw)
    assert.equal(ok, false, `expected failure for raw=${JSON.stringify(raw)}`)
    assert.match(reason, /^invalid args/)
  }
})

test('S8: malformed JSON string -> "invalid args: bad JSON"', () => {
  const { ok, reason } = CORE.normalizeArgs('x{')
  assert.equal(ok, false)
  assert.equal(reason, 'invalid args: bad JSON')
})

test('S8: a JSON string is parsed the same as an object (W11)', () => {
  const asObject = CORE.normalizeArgs(baseArgs())
  const asString = CORE.normalizeArgs(JSON.stringify(baseArgs()))
  assert.equal(asObject.ok, true)
  assert.equal(asString.ok, true)
  assert.deepEqual(asString.plan, asObject.plan)
})

test('S8: wrong/missing contract -> "invalid args: contract must be audit/args-v1"', () => {
  const { ok, reason } = CORE.normalizeArgs({})
  assert.equal(ok, false)
  assert.equal(reason, 'invalid args: contract must be audit/args-v1')
})

test('S8: missing required fields fail in the frozen order runId, date, repo.root, reportPath', () => {
  const full = baseArgs()
  const cases = [
    [{ ...full, runId: undefined }, 'invalid args: missing runId'],
    [{ ...full, date: undefined }, 'invalid args: missing date'],
    [{ ...full, repo: { description: 'x' } }, 'invalid args: missing repo.root'],
    [{ ...full, reportPath: undefined }, 'invalid args: missing reportPath'],
  ]
  for (const [raw, expected] of cases) {
    const { ok, reason } = CORE.normalizeArgs(raw)
    assert.equal(ok, false, `expected failure for ${expected}`)
    assert.equal(reason, expected)
  }
})

test('S8: runId not starting with "a-" is rejected', () => {
  const { ok, reason } = CORE.normalizeArgs(baseArgs({ runId: 'wrong-0001' }))
  assert.equal(ok, false)
  assert.equal(reason, 'invalid args: runId must start with a-')
})

test('S8: date not in YYYY-MM-DD form is rejected', () => {
  for (const date of ['2026/09/28', '28-09-2026', '2026-9-28', 'today']) {
    const { ok, reason } = CORE.normalizeArgs(baseArgs({ date }))
    assert.equal(ok, false, `expected failure for date=${date}`)
    assert.equal(reason, 'invalid args: date must be YYYY-MM-DD')
  }
})

test('S8: unknown preset name is rejected', () => {
  const { ok, reason } = CORE.normalizeArgs(baseArgs({ preset: 'medium' }))
  assert.equal(ok, false)
  assert.equal(reason, 'invalid args: unknown preset medium')
})

test('S8 / probe (empty vs absent paths): empty scope.paths and absent scope.paths both -> "invalid args: empty scope"', () => {
  const emptyArray = CORE.normalizeArgs(baseArgs({ scope: { reviewers: [reviewerSpec('r1')], paths: [] } }))
  const absent = CORE.normalizeArgs(baseArgs({ scope: { reviewers: [reviewerSpec('r1')] } }))
  assert.equal(emptyArray.ok, false)
  assert.equal(emptyArray.reason, 'invalid args: empty scope')
  assert.equal(absent.ok, false)
  assert.equal(absent.reason, 'invalid args: empty scope')
})

test('S8: an unknown verify lens key is rejected', () => {
  const { ok, reason } = CORE.normalizeArgs(
    baseArgs({ verify: { highLenses: ['not-a-lens'], defaultLenses: ['combined'], maxFindings: 20 } })
  )
  assert.equal(ok, false)
  assert.equal(reason, 'invalid args: unknown verify lens not-a-lens')
})

test('S8: items.enabled without items.rootId is rejected', () => {
  const { ok, reason } = CORE.normalizeArgs(baseArgs({ items: { enabled: true } }))
  assert.equal(ok, false)
  assert.equal(reason, 'invalid args: items.rootId required when items.enabled')
})

test('S8: items.materializeMin outside {high, medium} is rejected', () => {
  const { ok, reason } = CORE.normalizeArgs(
    baseArgs({ items: { enabled: true, rootId: 'root-1', materializeMin: 'low' } })
  )
  assert.equal(ok, false)
  assert.equal(reason, 'invalid args: items.materializeMin must be high or medium')
})

test('S8: preset expansion — quick/standard/full populate the frozen PRESETS fields, explicit fields override', () => {
  for (const [name, expected] of Object.entries(CORE.PRESETS)) {
    const { ok, plan } = CORE.normalizeArgs(baseArgs({ preset: name, scope: { paths: ['x'] } }))
    assert.equal(ok, true, `preset ${name} should normalize`)
    assert.equal(plan.gaps.enabled, expected.gaps.enabled)
    assert.equal(plan.gaps.max, expected.gaps.max)
    assert.deepEqual(plan.verify.highLenses, expected.verify.highLenses)
    assert.deepEqual(plan.verify.defaultLenses, expected.verify.defaultLenses)
    assert.equal(plan.verify.maxFindings, expected.verify.maxFindings)
  }
  // an explicit field overrides the preset's field
  const { plan } = CORE.normalizeArgs(baseArgs({ preset: 'quick', gaps: { enabled: true, max: 9 } }))
  assert.equal(plan.gaps.enabled, true)
  assert.equal(plan.gaps.max, 9)
})

test('S8: defaults — maxAgents 60, allowLarge false, items.materializeMin "high" when items enabled, title = runId', () => {
  const { plan } = CORE.normalizeArgs(baseArgs({ items: { enabled: true, rootId: 'root-1' } }))
  assert.equal(plan.maxAgents, 60)
  assert.equal(plan.allowLarge, false)
  assert.equal(plan.items.materializeMin, 'high')
  assert.equal(plan.title, plan.runId)
})

// projectAgents / sizeGuard — formula from the frozen Appendix B paragraph, computed by hand
// (independent of the implementation) rather than read from it.
function handComputeTotal(plan) {
  const reviewersGiven = Array.isArray(plan.scope.reviewers)
  const R = reviewersGiven ? plan.scope.reviewers.length + (plan.scope.lenses || []).length : plan.scope.reviewerCount
  const scope = reviewersGiven ? 0 : 1
  const review = R
  const gaps = plan.gaps.enabled ? 1 + plan.gaps.max : 0
  const est = (R + (plan.gaps.enabled ? plan.gaps.max : 0)) * 8
  const nV = Math.min(est, plan.verify.maxFindings)
  const verify = nV * Math.max(plan.verify.highLenses.length, plan.verify.defaultLenses.length)
  const merge = 1
  const synth = 1
  const triage = plan.items && plan.items.enabled ? Math.ceil(nV / 10) : 0
  return { total: scope + review + gaps + merge + verify + synth + triage, scope, review, gaps, merge, verify, synth, triage }
}

test('S8: projectAgents matches the frozen formula, reviewers-absent estimate path', () => {
  const { plan } = CORE.normalizeArgs(
    baseArgs({ preset: 'full', scope: { paths: ['x'] } }) // no scope.reviewers -> estimate via reviewerCount
  )
  const expected = handComputeTotal(plan)
  const actual = CORE.projectAgents(plan)
  assert.deepEqual(
    { total: actual.total, scope: actual.scope, review: actual.review, gaps: actual.gaps, merge: actual.merge, verify: actual.verify, synth: actual.synth, triage: actual.triage },
    expected
  )
})

test('S8: projectAgents matches the frozen formula, reviewers-given path (scope=0)', () => {
  const plan = buildPlan({ preset: 'standard', items: { enabled: true, rootId: 'root-1' } })
  const expected = handComputeTotal(plan)
  const actual = CORE.projectAgents(plan)
  assert.equal(actual.scope, 0)
  assert.deepEqual(
    { total: actual.total, scope: actual.scope, review: actual.review, gaps: actual.gaps, merge: actual.merge, verify: actual.verify, synth: actual.synth, triage: actual.triage },
    expected
  )
})

test('S8: sizeGuard refuses when the projection exceeds maxAgents and allowLarge is unset', () => {
  const plan = buildPlan({ preset: 'full', scope: { paths: ['x'] } }) // no reviewers given, big projection
  const projected = CORE.projectAgents(plan)
  assert.ok(projected.total > plan.maxAgents, 'fixture must actually exceed maxAgents for this test to mean anything')
  const guard = CORE.sizeGuard(plan)
  assert.deepEqual(guard, {
    reason: `projected ${projected.total} agents > maxAgents; pass allowLarge or a smaller preset`,
  })
})

test('S8: sizeGuard passes (null) once allowLarge is set, same oversized plan', () => {
  const plan = buildPlan({ preset: 'full', scope: { paths: ['x'] }, allowLarge: true })
  assert.equal(CORE.sizeGuard(plan), null)
})

test('S8: sizeGuard passes for a plan within maxAgents', () => {
  const plan = buildPlan() // quick preset, reviewers given (2), lenses [] -> small projection
  assert.equal(CORE.sizeGuard(plan), null)
})

test('S8: the entry sequence refuses before any agent runs (0 agents) when oversized without allowLarge', async () => {
  const raw = baseArgs({ preset: 'full', scope: { paths: ['x'] } })
  const script = loadScript(SCRIPT_PATH)
  let agentCalls = 0
  const result = await script.run({
    agent: async () => { agentCalls += 1; return null },
    parallel: fakeParallel,
    pipeline: fakePipeline,
    phase: () => {},
    log: () => {},
    args: raw,
  })
  assert.equal(result.started, false)
  assert.match(result.reason, /^projected \d+ agents > maxAgents/)
  assert.equal(agentCalls, 0)
})

// ── S7 / AU7: buildProposal ───────────────────────────────────────────────────
// Oracle: phase-c-workflows.md §2.5 table + Appendix B buildProposal paragraph (verbatim).

function keptFinding(overrides = {}) {
  return {
    findingId: 'f-1', reportId: 'AR-01', title: 'A finding', severity: 'high',
    verdict: 'CONFIRMED', category: 'coupling', effort: 'M',
    locations: ['claude-plugins/task-orchestrator/hooks/example.mjs:1'], trackedId: '',
    ...overrides,
  }
}

function proposalPlan(overrides = {}) {
  return buildPlan({
    items: { enabled: true, rootId: 'root-test-0001', materializeMin: 'high' },
    ...overrides,
  })
}

test('S7: items.enabled false -> buildProposal returns null', () => {
  const plan = buildPlan() // items absent -> disabled
  assert.equal(CORE.buildProposal([keptFinding()], {}, plan), null)
})

test('S7: classification via DEFAULT_CLASS_MAP — bug categories vs tech-debt categories', () => {
  const plan = proposalPlan()
  const bugFinding = keptFinding({ findingId: 'f-bug', category: 'security-architecture' })
  const debtFinding = keptFinding({ findingId: 'f-debt', category: 'documentation-drift' })
  const triage = {
    'f-bug': { candidates: [], likelyDuplicate: 'none' },
    'f-debt': { candidates: [], likelyDuplicate: 'none' },
  }
  const proposal = CORE.buildProposal([bugFinding, debtFinding], triage, plan)
  const byId = Object.fromEntries(proposal.items.map((i) => [i.findingId, i]))
  assert.equal(byId['f-bug'].class, CORE.DEFAULT_CLASS_MAP['security-architecture'])
  assert.equal(byId['f-bug'].class, 'bug')
  assert.equal(byId['f-debt'].class, CORE.DEFAULT_CLASS_MAP['documentation-drift'])
  assert.equal(byId['f-debt'].class, 'tech-debt')
})

test('S7 / probe (backslash paths): a finding whose locations are all under an observationPaths prefix (backslash-separated) is classified as an observation, kept out of batches', () => {
  const plan = proposalPlan({
    observationPaths: ['claude-plugins/task-orchestrator/hooks'],
  })
  const finding = keptFinding({
    findingId: 'f-obs',
    locations: ['claude-plugins\\task-orchestrator\\hooks\\retro-lib.mjs:12', 'claude-plugins\\task-orchestrator\\hooks\\config-sync.mjs:3'],
  })
  const triage = { 'f-obs': { candidates: [], likelyDuplicate: 'none' } }
  const proposal = CORE.buildProposal([finding], triage, plan)
  assert.deepEqual(proposal.observations, ['f-obs'])
  // Appendix B: items[] carries class 'bug'|'tech-debt'|'observation' for every kept finding
  // (it is the human-review table); only `batches` (the create_work_tree materialization) and
  // `observations` (the separate depth-0 batch) treat 'observation' specially.
  const item = proposal.items.find((i) => i.findingId === 'f-obs')
  assert.ok(item, 'an observation still appears in items, classified as such')
  assert.equal(item.class, 'observation')
  assert.ok(!proposal.batches.some((b) => b.childRefs.includes('f-obs')), 'observation must never enter a batch')
})

test('S7: a finding with only one location outside the observationPaths prefix is NOT an observation', () => {
  const plan = proposalPlan({ observationPaths: ['claude-plugins/task-orchestrator/hooks'] })
  const finding = keptFinding({
    findingId: 'f-mixed',
    category: 'coupling',
    locations: ['claude-plugins/task-orchestrator/hooks/x.mjs:1', 'current/src/main/kotlin/Foo.kt:1'],
  })
  const triage = { 'f-mixed': { candidates: [], likelyDuplicate: 'none' } }
  const proposal = CORE.buildProposal([finding], triage, plan)
  assert.ok(!proposal.observations.includes('f-mixed'))
  assert.ok(proposal.items.find((i) => i.findingId === 'f-mixed'))
})

test('S7: priority mapping critical/high->high, medium->medium, low->low', () => {
  const plan = proposalPlan()
  const findings = [
    keptFinding({ findingId: 'f-crit', severity: 'critical' }),
    keptFinding({ findingId: 'f-high', severity: 'high' }),
    keptFinding({ findingId: 'f-med', severity: 'medium' }),
    keptFinding({ findingId: 'f-low', severity: 'low' }),
  ]
  const triage = Object.fromEntries(findings.map((f) => [f.findingId, { candidates: [], likelyDuplicate: 'none' }]))
  const proposal = CORE.buildProposal(findings, triage, plan)
  const byId = Object.fromEntries(proposal.items.map((i) => [i.findingId, i]))
  assert.equal(byId['f-crit'].priority, 'high')
  assert.equal(byId['f-high'].priority, 'high')
  assert.equal(byId['f-med'].priority, 'medium')
  assert.equal(byId['f-low'].priority, 'low')
})

test('S7: tags are "audit,<category>,audit-<runId>"', () => {
  const plan = proposalPlan()
  const finding = keptFinding({ findingId: 'f-tags', category: 'testability' })
  const triage = { 'f-tags': { candidates: [], likelyDuplicate: 'none' } }
  const proposal = CORE.buildProposal([finding], triage, plan)
  const item = proposal.items.find((i) => i.findingId === 'f-tags')
  assert.equal(item.tags, `audit,testability,audit-${plan.runId}`)
})

test('S7: summary/description format, with and without a reportId', () => {
  const plan = proposalPlan()
  const withReport = keptFinding({ findingId: 'f-rep', title: 'Has a report', reportId: 'AR-07' })
  const withoutReport = keptFinding({ findingId: 'f-norep', title: 'No report', reportId: '' })
  const triage = {
    'f-rep': { candidates: [], likelyDuplicate: 'none' },
    'f-norep': { candidates: [], likelyDuplicate: 'none' },
  }
  const proposal = CORE.buildProposal([withReport, withoutReport], triage, plan)
  const byId = Object.fromEntries(proposal.items.map((i) => [i.findingId, i]))
  assert.equal(byId['f-rep'].summary, 'Has a report [AR-07]')
  assert.equal(byId['f-norep'].summary, 'No report')
  const top4 = withReport.locations.slice(0, 4).join('\n')
  assert.equal(byId['f-rep'].description, `Report: ${plan.reportPath}#ar-07\n${top4}`)
  assert.equal(byId['f-norep'].description, `Report: ${plan.reportPath}\n${top4}`)
})

test('S7: materialize gating — first-failing-wins reason precedence', () => {
  const plan = proposalPlan({ items: { enabled: true, rootId: 'root-test-0001', materializeMin: 'high' } })
  const cases = [
    [keptFinding({ findingId: 'f-sev', severity: 'medium' }), 'severity below high'],
    [keptFinding({ findingId: 'f-verdict', verdict: 'REFUTED' }), 'verdict REFUTED'],
    [keptFinding({ findingId: 'f-tracked', trackedId: 'TRACK-9' }), 'tracked TRACK-9'],
    [keptFinding({ findingId: 'f-strong' }), 'likely duplicate strong'],
    [keptFinding({ findingId: 'f-unknown' }), 'likely duplicate unknown'],
  ]
  const triage = {
    'f-sev': { candidates: [], likelyDuplicate: 'none' },
    'f-verdict': { candidates: [], likelyDuplicate: 'none' },
    'f-tracked': { candidates: [], likelyDuplicate: 'none' },
    'f-strong': { candidates: [{ id: 'x', short: 'x', role: 'bug', title: 't' }], likelyDuplicate: 'strong' },
    'f-unknown': { candidates: [], likelyDuplicate: 'unknown' },
  }
  const proposal = CORE.buildProposal(cases.map((c) => c[0]), triage, plan)
  const byId = Object.fromEntries(proposal.items.map((i) => [i.findingId, i]))
  for (const [finding, expectedReason] of cases) {
    assert.equal(byId[finding.findingId].materialize, false, `${finding.findingId} must not materialize`)
    assert.equal(byId[finding.findingId].reason, expectedReason)
  }
  // and the clean case: high severity, CONFIRMED, no tracked id, no/weak duplicate
  const clean = keptFinding({ findingId: 'f-ok', severity: 'high', verdict: 'CONFIRMED', trackedId: '' })
  const proposal2 = CORE.buildProposal([clean], { 'f-ok': { candidates: [], likelyDuplicate: 'weak' } }, plan)
  assert.equal(proposal2.items[0].materialize, true)
  assert.equal(proposal2.items[0].reason, 'ok')
})

test('S7 / probe (25/26): 25 materialized findings fit one batch; 26 split 25+1 with attach-mode root on the second', () => {
  const plan = proposalPlan()
  const make25 = () =>
    Array.from({ length: 25 }, (_, i) =>
      keptFinding({ findingId: `f-${i + 1}`, severity: 'high', verdict: 'CONFIRMED', trackedId: '' })
    )
  const triageFor = (findings) =>
    Object.fromEntries(findings.map((f) => [f.findingId, { candidates: [], likelyDuplicate: 'none' }]))

  const twentyFive = make25()
  const p25 = CORE.buildProposal(twentyFive, triageFor(twentyFive), plan)
  assert.equal(p25.batches.length, 1)
  assert.equal(p25.batches[0].childRefs.length, 25)
  assert.deepEqual(p25.batches[0].root, p25.container)

  const twentySix = [...make25(), keptFinding({ findingId: 'f-26', severity: 'high', verdict: 'CONFIRMED', trackedId: '' })]
  const p26 = CORE.buildProposal(twentySix, triageFor(twentySix), plan)
  assert.equal(p26.batches.length, 2)
  assert.equal(p26.batches[0].childRefs.length, 25)
  assert.deepEqual(p26.batches[0].root, p26.container)
  assert.equal(p26.batches[1].childRefs.length, 1)
  assert.deepEqual(p26.batches[1].root, { id: '<container-id>' })
  assert.equal(p26.batches[1].childRefs[0], 'f-26')
})

test('S7: non-materialized findings are excluded from batches but still listed in items', () => {
  const plan = proposalPlan()
  const materialized = keptFinding({ findingId: 'f-yes', severity: 'high', verdict: 'CONFIRMED', trackedId: '' })
  const skipped = keptFinding({ findingId: 'f-no', severity: 'low' })
  const triage = {
    'f-yes': { candidates: [], likelyDuplicate: 'none' },
    'f-no': { candidates: [], likelyDuplicate: 'none' },
  }
  const proposal = CORE.buildProposal([materialized, skipped], triage, plan)
  assert.equal(proposal.items.length, 2)
  const batchedIds = proposal.batches.flatMap((b) => b.childRefs)
  assert.deepEqual(batchedIds, ['f-yes'])
})

test('S7: container title/type/tags', () => {
  const plan = proposalPlan({ title: 'Hooks sweep' })
  const finding = keptFinding({ findingId: 'f-1', severity: 'high', verdict: 'CONFIRMED', trackedId: '' })
  const proposal = CORE.buildProposal([finding], { 'f-1': { candidates: [], likelyDuplicate: 'none' } }, plan)
  assert.equal(proposal.container.title, `Audit — ${plan.title} — ${plan.date}`)
  assert.equal(proposal.container.type, 'container')
  assert.equal(proposal.container.tags, 'container,audit')
})

test('S7: no typeMap configured -> items carry no type field', () => {
  const plan = proposalPlan() // no items.typeMap override
  const finding = keptFinding({ findingId: 'f-notype', category: 'coupling' })
  const proposal = CORE.buildProposal([finding], { 'f-notype': { candidates: [], likelyDuplicate: 'none' } }, plan)
  const item = proposal.items.find((i) => i.findingId === 'f-notype')
  assert.equal('type' in item, false)
})

test('S7: a configured typeMap maps class to the given type', () => {
  const plan = proposalPlan({
    items: {
      enabled: true, rootId: 'root-test-0001', materializeMin: 'high',
      typeMap: { bug: 'bug-fix', techDebt: 'feature-task' },
    },
  })
  const bugFinding = keptFinding({ findingId: 'f-bug', category: 'security-architecture' })
  const debtFinding = keptFinding({ findingId: 'f-debt', category: 'documentation-drift' })
  const triage = {
    'f-bug': { candidates: [], likelyDuplicate: 'none' },
    'f-debt': { candidates: [], likelyDuplicate: 'none' },
  }
  const proposal = CORE.buildProposal([bugFinding, debtFinding], triage, plan)
  const byId = Object.fromEntries(proposal.items.map((i) => [i.findingId, i]))
  assert.equal(byId['f-bug'].type, 'bug-fix')
  assert.equal(byId['f-debt'].type, 'feature-task')
})

test('S7: proposal carries the triage candidates and likelyDuplicate through to each item', () => {
  const plan = proposalPlan()
  const finding = keptFinding({ findingId: 'f-cand' })
  const candidates = [{ id: 'abc', short: 'abc12345', role: 'bug', title: 'Similar bug' }]
  const triage = { 'f-cand': { candidates, likelyDuplicate: 'weak' } }
  const proposal = CORE.buildProposal([finding], triage, plan)
  const item = proposal.items.find((i) => i.findingId === 'f-cand')
  assert.deepEqual(item.candidates, candidates)
  assert.equal(item.likelyDuplicate, 'weak')
})

// ── S3 / AU3: mergeFindings ───────────────────────────────────────────────────
// Oracle: phase-c-workflows.md §2.1 dedup row + Appendix B mergeFindings paragraph (verbatim).

function rawFinding(id, source, overrides = {}) {
  return {
    id, source, title: `Finding ${id}`, severity: 'medium', category: 'coupling',
    locations: [`loc-${id}-a`], evidence: `evidence-${id}`, impact: 'impact', recommendation: 'rec',
    effort: 'M', confidence: 'medium',
    ...overrides,
  }
}

test('S3: duplicate id across two groups resolves first-group-wins; unknown ids are ignored', () => {
  const all = [rawFinding('a', 's1'), rawFinding('b', 's2'), rawFinding('c', 's3')]
  const groups = [
    { canonicalId: 'a', duplicateIds: ['b'], mergedTitle: 'Merged A+B' },
    { canonicalId: 'b', duplicateIds: ['c', 'ghost'], mergedTitle: 'Merged B+C (should not apply to b)' },
  ]
  const merged = CORE.mergeFindings(all, groups)
  const ab = merged.find((m) => m.id === 'a')
  assert.ok(ab, 'first group (a+b) must be applied')
  assert.equal(ab.title, 'Merged A+B')
  assert.deepEqual(ab.memberIds, ['a', 'b'])
  // b was already consumed by the first group, so the second group is left with only 'c'
  // (an ungrouped-looking singleton for id 'c' with its own id, since 'b' was seen and
  // 'ghost' does not exist in `all`).
  const c = merged.find((m) => m.memberIds.includes('c'))
  assert.ok(c)
  assert.ok(!merged.some((m) => m.id === 'b' && m !== ab), 'b must not form its own group entry')
})

test('S3: ungrouped findings are appended, in input order, after grouped elements', () => {
  const all = [rawFinding('x', 's1'), rawFinding('y', 's2'), rawFinding('z', 's3')]
  const groups = [{ canonicalId: 'y', duplicateIds: [], mergedTitle: 'Y alone' }]
  const merged = CORE.mergeFindings(all, groups)
  assert.deepEqual(merged.map((m) => m.id), ['y', 'x', 'z'])
})

test('S3: canonical member is the first in input order; severity is the max across members', () => {
  const all = [
    rawFinding('p1', 's1', { severity: 'low' }),
    rawFinding('p2', 's2', { severity: 'critical' }),
    rawFinding('p3', 's3', { severity: 'medium' }),
  ]
  const groups = [{ canonicalId: 'p2', duplicateIds: ['p1', 'p3'], mergedTitle: 'M' }]
  const merged = CORE.mergeFindings(all, groups)
  assert.equal(merged.length, 1)
  // canonical = FIRST MEMBER IN INPUT ORDER, not the group's stated canonicalId field
  assert.equal(merged[0].id, 'p1')
  assert.equal(merged[0].severity, 'critical')
})

test('S3: locations are set-unioned in first-seen order', () => {
  const all = [
    rawFinding('l1', 's1', { locations: ['a', 'b'] }),
    rawFinding('l2', 's2', { locations: ['b', 'c'] }),
  ]
  const groups = [{ canonicalId: 'l1', duplicateIds: ['l2'], mergedTitle: 'M' }]
  const merged = CORE.mergeFindings(all, groups)
  assert.deepEqual(merged[0].locations, ['a', 'b', 'c'])
})

test('S3 / probe (groups []): an empty groups array leaves every finding as its own singleton, input order preserved', () => {
  const all = [rawFinding('g1', 's1'), rawFinding('g2', 's2')]
  const merged = CORE.mergeFindings(all, [])
  assert.deepEqual(merged.map((m) => m.id), ['g1', 'g2'])
  assert.deepEqual(merged[0].memberIds, ['g1'])
})

test('S3: an emptied group (every id unknown/already-seen) is skipped entirely', () => {
  const all = [rawFinding('e1', 's1')]
  const groups = [
    { canonicalId: 'e1', duplicateIds: [], mergedTitle: 'First' },
    { canonicalId: 'e1', duplicateIds: ['ghost'], mergedTitle: 'Second, all consumed/unknown' },
  ]
  const merged = CORE.mergeFindings(all, groups)
  assert.equal(merged.length, 1)
  assert.equal(merged[0].title, 'First')
})

// ── S4 / AU4: verifyPlan / decideVerdict / capVerification ──────────────────
// Oracle: reference architectural-review.js:225-243 (kept-behavior) + Appendix B
// decideVerdict/verifyPlan/capVerification paragraph (verbatim).

const THREE_LENSES = ['evidence', 'significance', 'consequence']
const VERIFY_CFG = { highLenses: THREE_LENSES, defaultLenses: ['combined'], maxFindings: 40 }

function vote(overrides = {}) {
  return {
    refuted: false, confidence: 'high', evidenceAccurate: true, correctedEvidence: '',
    adjustedSeverity: 'high', alreadyTrackedId: '', rationale: 'r',
    ...overrides,
  }
}

test('S4: verifyPlan routes severity >= high to highLenses, else defaultLenses', () => {
  assert.deepEqual(CORE.verifyPlan({ severity: 'critical' }, VERIFY_CFG), VERIFY_CFG.highLenses)
  assert.deepEqual(CORE.verifyPlan({ severity: 'high' }, VERIFY_CFG), VERIFY_CFG.highLenses)
  assert.deepEqual(CORE.verifyPlan({ severity: 'medium' }, VERIFY_CFG), VERIFY_CFG.defaultLenses)
  assert.deepEqual(CORE.verifyPlan({ severity: 'low' }, VERIFY_CFG), VERIFY_CFG.defaultLenses)
})

test('S4: a strong evidence-lens refute drops the finding (REFUTED)', () => {
  const votes = [vote({ refuted: true, confidence: 'high' }), vote({ refuted: false }), vote({ refuted: false })]
  const v = CORE.decideVerdict(votes, THREE_LENSES, 'high')
  assert.equal(v.verdict, 'REFUTED')
  assert.equal(v.dropped, true)
})

test('S4: two strong refutes (neither necessarily evidence) drop the finding (REFUTED)', () => {
  const votes = [vote({ refuted: false }), vote({ refuted: true, confidence: 'high' }), vote({ refuted: true, confidence: 'medium' })]
  const v = CORE.decideVerdict(votes, THREE_LENSES, 'high')
  assert.equal(v.verdict, 'REFUTED')
  assert.equal(v.dropped, true)
})

test('S4: a lone low-confidence refute (evidence lens) does not drop — PLAUSIBLE', () => {
  const votes = [vote({ refuted: true, confidence: 'low' }), vote({ refuted: false }), vote({ refuted: false })]
  const v = CORE.decideVerdict(votes, THREE_LENSES, 'high')
  assert.equal(v.verdict, 'PLAUSIBLE')
  assert.equal(v.dropped, false)
})

test('S4: a lone strong significance-lens refute does not drop (only evidence/2-strong drop) — PLAUSIBLE', () => {
  const votes = [vote({ refuted: false }), vote({ refuted: true, confidence: 'high' }), vote({ refuted: false })]
  const v = CORE.decideVerdict(votes, THREE_LENSES, 'high')
  assert.equal(v.verdict, 'PLAUSIBLE')
  assert.equal(v.dropped, false)
})

test('S4: no refutes at all — CONFIRMED', () => {
  const votes = [vote({ refuted: false }), vote({ refuted: false }), vote({ refuted: false })]
  const v = CORE.decideVerdict(votes, THREE_LENSES, 'high')
  assert.equal(v.verdict, 'CONFIRMED')
  assert.equal(v.dropped, false)
})

test('S4: every vote null — UNVERIFIED, finalSeverity falls back to the passed severity, trackedId empty', () => {
  const v = CORE.decideVerdict([null, null, null], THREE_LENSES, 'medium')
  assert.equal(v.verdict, 'UNVERIFIED')
  assert.equal(v.finalSeverity, 'medium')
  assert.equal(v.trackedId, '')
})

test('S4: a single-lens ("combined") strong refute drops the finding — REFUTED', () => {
  const v = CORE.decideVerdict([vote({ refuted: true, confidence: 'high' })], ['combined'], 'high')
  assert.equal(v.verdict, 'REFUTED')
  assert.equal(v.dropped, true)
})

test('S4: a single-lens low-confidence refute does not drop — PLAUSIBLE', () => {
  const v = CORE.decideVerdict([vote({ refuted: true, confidence: 'low' })], ['combined'], 'high')
  assert.equal(v.verdict, 'PLAUSIBLE')
  assert.equal(v.dropped, false)
})

test('S4: finalSeverity is sourced from the significance lens when present', () => {
  const votes = [
    vote({ adjustedSeverity: 'medium' }),
    vote({ adjustedSeverity: 'critical' }), // significance
    vote({ adjustedSeverity: 'low' }),
  ]
  const v = CORE.decideVerdict(votes, THREE_LENSES, 'high')
  assert.equal(v.finalSeverity, 'critical')
})

test('S4: finalSeverity falls back to combined, then to the first vote, when significance is absent', () => {
  const combinedVote = CORE.decideVerdict([vote({ adjustedSeverity: 'low' })], ['combined'], 'high')
  assert.equal(combinedVote.finalSeverity, 'low')
  const fallbackVote = CORE.decideVerdict(
    [vote({ adjustedSeverity: 'medium' }), null, vote({ adjustedSeverity: 'critical' })],
    THREE_LENSES,
    'high'
  )
  // no significance vote (index 1 is null); falls back to the first non-null vote (evidence)
  assert.equal(fallbackVote.finalSeverity, 'medium')
})

test('S4: trackedId is the first non-empty, trimmed alreadyTrackedId in lens order', () => {
  const votes = [
    vote({ alreadyTrackedId: '' }),
    vote({ alreadyTrackedId: '  TRACK-9  ' }),
    vote({ alreadyTrackedId: 'TRACK-OTHER' }),
  ]
  const v = CORE.decideVerdict(votes, THREE_LENSES, 'high')
  assert.equal(v.trackedId, 'TRACK-9')
})

test('S4: correctedEvidence joins "[lens] text" for every vote whose evidence was marked inaccurate', () => {
  const votes = [
    vote({ evidenceAccurate: false, correctedEvidence: 'fix1' }),
    vote({ evidenceAccurate: true, correctedEvidence: '' }),
    vote({ evidenceAccurate: false, correctedEvidence: 'fix2' }),
  ]
  const v = CORE.decideVerdict(votes, THREE_LENSES, 'high')
  assert.equal(v.correctedEvidence, '[evidence] fix1\n[consequence] fix2')
})

test('S4: capVerification stably sorts by severity descending and caps at maxFindings', () => {
  const merged = [
    { id: 'm1', severity: 'medium' },
    { id: 'c1', severity: 'critical' },
    { id: 'h1', severity: 'high' },
    { id: 'm2', severity: 'medium' }, // ties with m1 — must keep original relative order (stable)
    { id: 'l1', severity: 'low' },
  ]
  const { verify, unverified } = CORE.capVerification(merged, 3)
  assert.deepEqual(verify.map((f) => f.id), ['c1', 'h1', 'm1'])
  assert.deepEqual(unverified.map((f) => f.id), ['m2', 'l1'])
})

test('S4 / probe (maxFindings==count): a cap exactly equal to the merged count sends everything to verify', () => {
  const merged = [
    { id: 'a', severity: 'high' },
    { id: 'b', severity: 'medium' },
  ]
  const { verify, unverified } = CORE.capVerification(merged, 2)
  assert.equal(verify.length, 2)
  assert.deepEqual(unverified, [])
})

// ── S3 (integration): flattenFindings + reversed-completion determinism ─────

test('S3: flattenFindings assigns id = key + "-" + (index+1) and source = key, concatenated in input slice order', () => {
  const slices = [
    { key: 'r1', findings: [rawFinding('_', '_'), rawFinding('_', '_')] },
    { key: 'r2', findings: [rawFinding('_', '_')] },
    { key: 'gap1', findings: [rawFinding('_', '_')] },
  ]
  const flat = CORE.flattenFindings(slices)
  assert.deepEqual(flat.map((f) => f.id), ['r1-1', 'r1-2', 'r2-1', 'gap1-1'])
  assert.deepEqual(flat.map((f) => f.source), ['r1', 'r1', 'r2', 'gap1'])
})

test('S3 (integration, replay): fifo vs reverse autoAgent completion order yields deep-equal kept findings', async () => {
  const plan = buildPlan({ scope: { paths: ['x'], reviewers: [reviewerSpec('r1'), reviewerSpec('r2'), reviewerSpec('r3')], lenses: [] } })
  const responder = async (label) => {
    if (label.startsWith('review:')) {
      const key = label.slice('review:'.length)
      return findingsResult({ findings: [makeFinding({ title: `finding for ${key}`, severity: 'high' })] })
    }
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false, adjustedSeverity: 'high' })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('unexpected label ' + label)
  }
  const runOnce = async (order) => {
    const { agent } = autoAgent(responder, { order })
    const result = await CORE.runAudit(plan, makeDeps({ agent }))
    return result.kept
  }
  const fifoKept = await runOnce('fifo')
  const reverseKept = await runOnce('reverse')
  assert.equal(fifoKept.length, 3)
  assert.deepEqual(reverseKept, fifoKept)
})

// ── S1: barrier ordering (autoAgent order 'reverse') ─────────────────────────
// Oracle: phase-c-workflows.md §2.3 phase table; Appendix B LABELS/opts.phase mapping.

function s1aResponder() {
  return async (label) => {
    if (label === 'scope') return scopeResult({ reviewers: [reviewerSpec('r1'), reviewerSpec('r2')], lenses: [] })
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding({ severity: 'high' })] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label === 'verify:r1-1:combined' || label === 'verify:r2-1:combined') return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: '/tmp/audit-report.md' })
    if (label === 'triage:1') {
      return triageResult({
        results: [
          { findingId: 'r1-1', candidates: [], likelyDuplicate: 'none' },
          { findingId: 'r2-1', candidates: [], likelyDuplicate: 'none' },
        ],
      })
    }
    throw new Error('s1aResponder: unexpected label ' + label)
  }
}

test('S1a: barrier Scope -> Review -> Merge -> Verify -> Synthesize -> Triage (reverse virtual-clock order)', async () => {
  const plan = buildPlan({
    scope: { paths: ['claude-plugins/task-orchestrator/hooks'] }, // reviewers absent -> Scope phase runs
    gaps: { enabled: false, max: 0 },
    items: { enabled: true, rootId: 'root-test-0001', materializeMin: 'high' },
    reportPath: '/tmp/audit-report.md',
  })
  const { agent, calls } = autoAgent(s1aResponder(), { order: 'reverse' })
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.started, true)
  assertBarrier(calls, 'Scope', 'Review')
  assertBarrier(calls, 'Review', 'Merge')
  assertBarrier(calls, 'Merge', 'Verify')
  assertBarrier(calls, 'Verify', 'Synthesize')
  assertBarrier(calls, 'Synthesize', 'Triage')
})

test('S1b: barrier Review -> Gaps -> Merge, gap reviewer label is gap:<n> 1-based (reverse order)', async () => {
  const plan = buildPlan({ gaps: { enabled: true, max: 2 } }) // scope.reviewers = r1, r2 from baseArgs
  const responder = async (label) => {
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding()] })
    if (label === 'critic') return criticResult({ gaps: [{ label: 'coverage-gap', prompt: 'look at X' }] })
    if (label === 'gap:1') return findingsResult({ findings: [makeFinding()] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('s1b: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(responder, { order: 'reverse' })
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.started, true)
  assertBarrier(calls, 'Review', 'Gaps')
  assertBarrier(calls, 'Gaps', 'Merge')
})

// ── S2 / AU2: null-agent tolerance per phase ─────────────────────────────────
// Oracle: phase-c-workflows.md §2.3 "A null agent at any phase is tolerated" table +
// Appendix B runAudit paragraph (explicit per-phase null-tolerance clauses).

test('S2: a null scope agent -> kept [], stats.missing includes "scope", report null, proposal null, no throw', async () => {
  const plan = buildPlan({
    scope: { paths: ['claude-plugins/task-orchestrator/hooks'] }, // reviewers absent -> Scope runs
    items: { enabled: true, rootId: 'root-test-0001' },
  })
  const responder = async (label) => {
    if (label === 'scope') return null
    throw new Error('no agent beyond scope should be called when scope is null: ' + label)
  }
  const { agent, calls } = autoAgent(responder)
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.deepEqual(result.kept, [])
  assert.ok(result.stats.missing.includes('scope'))
  assert.equal(result.report, null)
  assert.equal(result.proposal, null)
  assert.equal(calls.filter((c) => c.label.startsWith('review:')).length, 0)
})

test('S2: a null reviewer -> that reviewer key is listed in stats.missing, the run still completes', async () => {
  const plan = buildPlan() // reviewers r1, r2
  const responder = async (label) => {
    if (label === 'review:r1') return null
    if (label === 'review:r2') return findingsResult({ findings: [makeFinding()] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('unexpected label ' + label)
  }
  const { agent } = autoAgent(responder)
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.ok(result.stats.missing.includes('r1'))
  assert.equal(result.stats.raw, 1)
})

test('S2: a null merge agent leaves findings unmerged (merged count === raw count, singletons)', async () => {
  const plan = buildPlan() // reviewers r1, r2, one finding each -> raw = 2
  const responder = async (label) => {
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding()] })
    if (label === 'merge') return null
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('unexpected label ' + label)
  }
  const { agent } = autoAgent(responder)
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.stats.raw, 2)
  assert.equal(result.stats.merged, 2)
})

test('S2: every verify lens returning null marks the finding UNVERIFIED (not dropped)', async () => {
  const plan = buildPlan()
  const responder = async (label) => {
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding()] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return null
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('unexpected label ' + label)
  }
  const { agent } = autoAgent(responder)
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.stats.unverified, result.kept.length)
  assert.ok(result.kept.length > 0)
  assert.ok(result.kept.every((k) => k.verdict === 'UNVERIFIED'))
})

test('S2: a null synthesis agent -> report null, kept set still returned', async () => {
  const plan = buildPlan()
  const responder = async (label) => {
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding()] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return null
    throw new Error('unexpected label ' + label)
  }
  const { agent } = autoAgent(responder)
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.report, null)
  assert.equal(result.kept.length, 2)
})

test('S2: a null triage agent marks affected findings likelyDuplicate "unknown" and not materialized', async () => {
  const plan = buildPlan({ items: { enabled: true, rootId: 'root-test-0001', materializeMin: 'high' } })
  const responder = async (label) => {
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding({ severity: 'high' })] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    if (label.startsWith('triage:')) return null
    throw new Error('unexpected label ' + label)
  }
  const { agent } = autoAgent(responder)
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.ok(result.proposal, 'proposal must still be computed when triage is null')
  assert.ok(result.proposal.items.length > 0)
  for (const item of result.proposal.items) {
    assert.equal(item.likelyDuplicate, 'unknown')
    assert.equal(item.materialize, false)
  }
})

// ── S5 (part 2) + S6: prompt content invariants ──────────────────────────────
// Oracle: phase-c-workflows.md §2.1/§2.3 "every other agent's prompt is READ-ONLY ... Only
// synthesis writes" + Appendix B "PROMPTS: every non-synthesis prompt contains 'READ-ONLY';
// no prompt contains manage_items, manage_notes, advance_item or create_work_tree; the
// synthesis prompt contains reportPath".

test('S6: every non-synthesis prompt is READ-ONLY and excludes write-tool names; synthesis names reportPath; no call sets opts.isolation', async () => {
  const plan = buildPlan({ gaps: { enabled: true, max: 1 } })
  const responder = async (label) => {
    if (label.startsWith('review:')) return findingsResult({ findings: [makeFinding()] })
    if (label === 'critic') return criticResult({ gaps: [{ label: 'g', prompt: 'p' }] })
    if (label.startsWith('gap:')) return findingsResult({ findings: [makeFinding()] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(responder)
  await CORE.runAudit(plan, makeDeps({ agent }))
  assert.ok(calls.length > 0)
  for (const c of calls) {
    assert.equal(c.opts.isolation, undefined, `call ${c.label} must not set opts.isolation`)
    if (c.label === 'synthesis') continue
    assert.match(c.prompt, /READ-ONLY/, `${c.label} prompt must contain READ-ONLY`)
    for (const forbidden of ['manage_items', 'manage_notes', 'advance_item', 'create_work_tree']) {
      assert.equal(c.prompt.includes(forbidden), false, `${c.label} prompt must not mention ${forbidden}`)
    }
  }
  const synth = calls.find((c) => c.label === 'synthesis')
  assert.ok(synth, 'synthesis must have been called')
  assert.ok(synth.prompt.includes(plan.reportPath), 'synthesis prompt must name reportPath')
})

// ── S5 (part 3, integration replay across the whole run) ────────────────────

test('S5: two identical runs produce identical [label, prompt] sequences', async () => {
  const plan = buildPlan({ gaps: { enabled: false, max: 0 } })
  const runOnce = async () => {
    const { agent, calls } = autoAgent(standardResponder(plan), { order: 'fifo' })
    await CORE.runAudit(plan, makeDeps({ agent }))
    return calls.map((c) => [c.label, c.prompt])
  }
  const a = await runOnce()
  const b = await runOnce()
  assert.deepEqual(a, b)
  assert.ok(a.length > 0)
})

// ── reportIds ─────────────────────────────────────────────────────────────────

test('reportIds: maps findingIndex entries findingId -> reportId; null synth -> {}', () => {
  assert.deepEqual(CORE.reportIds(null), {})
  const synth = synthResult({
    findingIndex: [
      { reportId: 'AR-01', findingId: 'f-1', title: 't', severity: 'high', effort: 'M', verdict: 'CONFIRMED', alreadyTrackedId: '' },
      { reportId: 'AR-02', findingId: 'f-2', title: 't2', severity: 'low', effort: 'S', verdict: 'PLAUSIBLE', alreadyTrackedId: '' },
    ],
  })
  assert.deepEqual(CORE.reportIds(synth), { 'f-1': 'AR-01', 'f-2': 'AR-02' })
})

// ── Follow-up (reviewer test-independence-audit, O1/O6/O8) ──────────────────
// Oracle: phase-c-workflows.md §2.x + phase-c-dispatch-contract.md Appendix B, same as
// above — never the current script's own output. Some of these exercise behavior the
// implementer is concurrently fixing (verify-prompt substance, models/efforts passed to
// agent opts, scope cap to reviewerCount, triage over kept-within-cap only) — that fix is
// NOT what these particular tests check, but if one of these goes red against the script as
// it stands, it is reported red, not weakened.

test('Follow-up S4: cap tail — findings beyond maxFindings end UNVERIFIED, counted in stats.unverifiedByCap, logged "unverified by cap"', async () => {
  const plan = buildPlan({ verify: { highLenses: ['combined'], defaultLenses: ['combined'], maxFindings: 1 } })
  const logLines = []
  const responder = async (label) => {
    if (label === 'review:r1') return findingsResult({ findings: [makeFinding({ severity: 'critical' })] })
    if (label === 'review:r2') return findingsResult({ findings: [makeFinding({ severity: 'low' })] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label === 'verify:r1-1:combined') return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('cap-tail: unexpected label ' + label) // r2-1 must never reach Verify — it is capped
  }
  const { agent } = autoAgent(responder)
  const result = await CORE.runAudit(plan, makeDeps({ agent, logLines }))
  assert.equal(result.stats.unverifiedByCap, 1)
  const tail = result.kept.find((k) => k.findingId === 'r2-1')
  assert.ok(tail, 'the capped-out finding must still be kept')
  assert.equal(tail.verdict, 'UNVERIFIED')
  const verified = result.kept.find((k) => k.findingId === 'r1-1')
  assert.ok(verified)
  assert.notEqual(verified.verdict, 'UNVERIFIED')
  assert.ok(logLines.some((l) => l.includes('unverified by cap')), 'a log line must mention the cap drop')
})

test('Follow-up S7: observation items carry the "agent-observation," tag prefix', () => {
  const plan = proposalPlan({ observationPaths: ['claude-plugins/task-orchestrator/hooks'] })
  const finding = keptFinding({
    findingId: 'f-obs-tag', category: 'dead-code',
    locations: ['claude-plugins/task-orchestrator/hooks/x.mjs:1'],
  })
  const proposal = CORE.buildProposal([finding], { 'f-obs-tag': { candidates: [], likelyDuplicate: 'none' } }, plan)
  const item = proposal.items.find((i) => i.findingId === 'f-obs-tag')
  assert.equal(item.class, 'observation')
  assert.equal(item.tags, `agent-observation,audit,dead-code,audit-${plan.runId}`)
})

test('Follow-up: the first log line reports "projected <N> agents" matching projectAgents(plan).total', async () => {
  const plan = buildPlan()
  const logLines = []
  const { agent } = autoAgent(standardResponder(plan))
  await CORE.runAudit(plan, makeDeps({ agent, logLines }))
  assert.ok(logLines.length > 0, 'expected at least one log line')
  const expectedTotal = CORE.projectAgents(plan).total
  assert.match(logLines[0], new RegExp(`projected ${expectedTotal} agents`))
})

test('Follow-up: stats.droppedGaps records gap proposals beyond gaps.max, and only gaps.max gap reviewers run', async () => {
  const plan = buildPlan({ gaps: { enabled: true, max: 1 } })
  const responder = async (label) => {
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding()] })
    if (label === 'critic') {
      return criticResult({
        gaps: [{ label: 'g1', prompt: 'p1' }, { label: 'g2', prompt: 'p2' }, { label: 'g3', prompt: 'p3' }],
      })
    }
    if (label === 'gap:1') return findingsResult({ findings: [makeFinding()] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('droppedGaps: unexpected label ' + label) // gap:2 / gap:3 must never run (max=1)
  }
  const { agent, calls } = autoAgent(responder)
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(calls.filter((c) => c.label.startsWith('gap:')).length, 1, 'only gaps.max gap reviewers run')
  assert.equal(result.stats.droppedGaps.length, 2)
  const droppedText = JSON.stringify(result.stats.droppedGaps)
  assert.match(droppedText, /g2/)
  assert.match(droppedText, /g3/)
})

test('Follow-up: raw === 0 skips Merge and Verify entirely (no such agent calls)', async () => {
  const plan = buildPlan() // reviewers r1, r2
  const responder = async (label) => {
    if (label === 'review:r1' || label === 'review:r2') return null
    if (label === 'merge') throw new Error('merge must not be called when raw === 0')
    if (label.startsWith('verify:')) throw new Error('verify must not be called when raw === 0')
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('raw==0: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(responder)
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.stats.raw, 0)
  assert.equal(calls.some((c) => c.label === 'merge'), false)
  assert.equal(calls.some((c) => c.label.startsWith('verify:')), false)
})

test('Follow-up S6: the scope prompt is READ-ONLY; the triage prompt names query_items search', async () => {
  const plan = buildPlan({
    scope: { paths: ['claude-plugins/task-orchestrator/hooks'] }, // reviewers absent -> Scope runs
    items: { enabled: true, rootId: 'root-test-0001', materializeMin: 'high' },
  })
  const responder = async (label) => {
    if (label === 'scope') return scopeResult({ reviewers: [reviewerSpec('r1')], lenses: [] })
    if (label === 'review:r1') return findingsResult({ findings: [makeFinding()] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    if (label.startsWith('triage:')) {
      return triageResult({ results: [{ findingId: 'r1-1', candidates: [], likelyDuplicate: 'none' }] })
    }
    throw new Error('S6 scope/triage: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(responder)
  await CORE.runAudit(plan, makeDeps({ agent }))
  const scopeCall = calls.find((c) => c.label === 'scope')
  assert.ok(scopeCall, 'scope must have been called')
  assert.match(scopeCall.prompt, /READ-ONLY/)
  const triageCall = calls.find((c) => c.label.startsWith('triage:'))
  assert.ok(triageCall, 'triage must have been called')
  assert.match(triageCall.prompt, /query_items/)
  assert.match(triageCall.prompt, /search/)
})

test('Follow-up O6: scope.reviewers: [] is treated as absent (Scope phase runs), per Appendix B', async () => {
  const plan = buildPlan({ scope: { paths: ['claude-plugins/task-orchestrator/hooks'], reviewers: [], lenses: [] } })
  const responder = async (label) => {
    if (label === 'scope') return scopeResult({ reviewers: [reviewerSpec('r1')], lenses: [] })
    if (label === 'review:r1') return findingsResult({ findings: [makeFinding()] })
    if (label === 'merge') return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('O6: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(responder)
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.ok(calls.some((c) => c.label === 'scope'), 'an empty scope.reviewers array must still trigger the Scope phase')
  assert.equal(result.started, true)
})

test('Follow-up O8: first-group-wins — winner memberIds are exact, the losing group keeps only its unconsumed member under its own mergedTitle, and untouched findings are true ungrouped singletons', () => {
  const all = [
    rawFinding('a', 's1'), rawFinding('b', 's2'), rawFinding('c', 's3'),
    rawFinding('d', 's4'), rawFinding('e', 's5'),
  ]
  const groups = [
    { canonicalId: 'a', duplicateIds: ['b'], mergedTitle: 'Winner AB' },
    { canonicalId: 'b', duplicateIds: ['c'], mergedTitle: 'Loser BC' }, // b already consumed; c is still fresh
  ]
  const merged = CORE.mergeFindings(all, groups)

  const winner = merged.find((m) => m.id === 'a')
  assert.ok(winner)
  assert.deepEqual(winner.memberIds, ['a', 'b'], 'the winner owns exactly its own members, nothing leaked from the losing group')

  const loserSurvivor = merged.find((m) => m.memberIds.includes('c'))
  assert.ok(loserSurvivor, "the losing group's unconsumed member must still surface")
  assert.deepEqual(loserSurvivor.memberIds, ['c'], "b (already consumed by the winner) must be absent from the losing group's surviving entry")
  assert.equal(loserSurvivor.title, 'Loser BC')

  // d and e were never referenced by any group: true ungrouped singletons, carrying their OWN
  // original title/source — never a group's mergedTitle.
  const d = merged.find((m) => m.id === 'd')
  const e = merged.find((m) => m.id === 'e')
  assert.deepEqual(d.memberIds, ['d'])
  assert.equal(d.title, 'Finding d')
  assert.deepEqual(d.sources, ['s4'])
  assert.deepEqual(e.memberIds, ['e'])
  assert.equal(e.title, 'Finding e')
})
