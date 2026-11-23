// ESM tests for workflows/deep-codebase-audit.js (item c039bb77). Two authorships share this file.
// Every section before "Method skill" was written by a blind test author (item c039bb77), who
// touched only this file and workflow-harness-ext.mjs and never opened the script directly
// (Read/Grep/sed/cat/git diff/etc.): every fact those sections use comes from the frozen
// declarations block and reaches the script only through loadScript / loadCoreNamed /
// scriptText / coreSlice (workflow-harness.mjs, workflow-harness-ext.mjs). The closing
// "Method skill" section (item e7887059) is implementer-written, by an author who has read the
// script; its oracle is that item's task-scope and amendments, not the script.
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
import { RETIRED_3X_TOOLS, dispatchLiteralsInLine } from '../lib/contract-checks.mjs'
import { loadPolicy } from '../lib/dispatch-policy.mjs'
import {
  loadCoreNamed,
  autoAgent as autoAgentRaw,
  fakePipeline,
  assertBarrier,
  scanForbiddenApis,
  freeRuntimeIds,
  ruleWindowHits,
} from './workflow-harness-ext.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SCRIPT_PATH = join(HERE, '..', '..', 'workflows', 'deep-codebase-audit.js')
const PLUGIN = resolve(HERE, '..', '..')
const REPO = resolve(PLUGIN, '..', '..')
const RULES_DIR = join(REPO, '.taskorchestrator', 'rules')

const CORE_NAMES = [
  'normalizeArgs', 'projectAgents', 'sizeGuard', 'flattenFindings', 'mergeFindings',
  'verifyPlan', 'decideVerdict', 'capVerification', 'reportIds', 'schemas',
  'reviewerPreamble', 'buildReviewerPrompt', 'buildLensPrompt', 'buildGapPrompt',
  'normalizeObsPath', 'isObservation', 'buildProposal', 'runAudit',
  'SEVERITY_RANK', 'LENS_KEYS', 'PRESETS', 'CATEGORIES', 'DEFAULT_CLASS_MAP', 'TYPE_MAP_KEY', 'DEFAULT_METHOD_SKILL',
  'DOCS_METHOD_SKILL', 'FOCUS_VALUES', 'sliceKind', 'inFocus', 'methodSkillOf', 'VERIFY_LENS_INSTRUCTIONS',
  'DISPATCH_CLASSES', 'FAN_OUT_KEYS', 'RATINGS', 'ITEM_POLICIES', 'ARCHITECTURE_LENS', 'testPathsOf',
  'formatMetricsDigest', 'sliceDigest', 'markMetricsPending', 'preCluster', 'primaryPath', 'fnv1a32', 'stableIds',
  'criticState', 'agentOpts', 'dispatchTag',
  'assignedCandidates', 'packVerifyGroups', 'groupVotes', 'buildCandidatePrompt', 'buildVerifyGroupPrompt',
]
const CORE = loadCoreNamed(SCRIPT_PATH, CORE_NAMES)

// T8b (D9, D10): verification is per group, so a verify agent answers {verdicts: [{findingId, ...VERDICT}]}.
// The FINDINGS array of a group prompt is a JSON array after the marker "FINDINGS:" (D10); read here with a bracket scan.
function findingsOfPrompt(prompt) {
  const at = prompt.lastIndexOf('FINDINGS:')
  if (at < 0) return []
  const start = prompt.indexOf('[', at)
  let depth = 0
  let inStr = false
  for (let i = start; i < prompt.length; i++) {
    const c = prompt[i]
    if (inStr) {
      if (c === '\\') i++
      else if (c === '"') inStr = false
    } else if (c === '"') inStr = true
    else if (c === '[') depth++
    else if (c === ']' && --depth === 0) return JSON.parse(prompt.slice(start, i + 1))
  }
  return []
}

/** Wraps a per-finding verify responder (called as label verify:<findingId>:<lens>, the finding as 4th argument) into the
 * group answer a verify:g<n>:<lens> call expects. A null per-finding answer leaves that member out of verdicts. */
function groupAnswer(responder) {
  return async (label, prompt, opts) => {
    const m = /^verify:g\d+:([a-z]+)$/.exec(label)
    if (!m) return responder(label, prompt, opts)
    const verdicts = []
    for (const f of findingsOfPrompt(prompt)) {
      const v = await responder('verify:' + f.id + ':' + m[1], prompt, opts, f)
      if (v) verdicts.push({ findingId: f.id, ...v })
    }
    return { verdicts }
  }
}
const autoAgent = (responder, o) => autoAgentRaw(groupAnswer(responder), o)

// ── fixture builders ─────────────────────────────────────────────────────────

function reviewerSpec(key, overrides = {}) {
  return {
    key, label: `Reviewer ${key}`, focus: 'focus text',
    paths: ['claude-plugins/task-orchestrator/hooks'],
    ...overrides,
  }
}

// E16 (A1): the seven dispatch classes the phase keys map to, at the D3 values (a test literal; never
// read from the script). Rebuilt per call so no test can mutate another test's args.
function classFixture() {
  return {
    research: { model: 'sonnet', effort: 'medium' },
    'audit-review': { model: 'sonnet', effort: 'high' },
    review: { model: 'opus', effort: 'high' },
    extraction: { model: 'sonnet', effort: 'low' },
    'audit-verify-high': { model: 'opus', effort: 'medium' },
    'audit-synthesis': { model: 'opus', effort: 'medium' },
  }
}

function baseArgs(overrides = {}) {
  return {
    contract: 'audit/args-v2',
    dispatchClasses: classFixture(),
    runId: 'a-test-0001',
    date: '2026-09-28',
    repo: { root: '/tmp/repo', description: 'test repo' },
    reportPath: '/tmp/audit-report.md',
    preset: 'quick',
    gaps: { enabled: false, max: 0 }, // E1: quick now enables the critic; most fixtures keep it off
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
    title: 'A finding', severity: 'high', category: 'coupling', basis: 'judgement',
    locations: ['claude-plugins/task-orchestrator/hooks/example.mjs:1'],
    evidence: 'evidence text', impact: 'impact text', recommendation: 'fix it',
    effort: 'M', confidence: 'high',
    ...overrides,
  }
}
// FNV-1a 32-bit over the UTF-8 bytes (offset 0x811c9dc5, prime 0x01000193), written here from the published
// algorithm as the oracle for stable ids: never read from the workflow.
function testFnv(text) {
  let h = 0x811c9dc5
  for (const b of Buffer.from(text, 'utf8')) {
    h ^= b
    h = Math.imul(h, 0x01000193) >>> 0
  }
  return h.toString(16).padStart(8, '0')
}
const stableIdOf = (category, path) => `F-${testFnv(`${category}|${path}`)}`

/** Wraps a label-keyed responder so each review or gap slice answers with a location of its own
 * (src/<key>.mjs:1) wherever the finding still carries the shared default location: the T8a pre-clustering
 * auto-merges findings of one category and location, and these fixtures want separate findings. */
function perSlice(responder) {
  const DEFAULT_LOCATION = makeFinding().locations[0]
  return async (label, ...rest) => {
    const r = await responder(label, ...rest)
    const m = /^(review|gap):(.+)$/.exec(label)
    if (!m || !r || !Array.isArray(r.findings)) return r
    const key = m[1] === 'gap' ? 'gap' + m[2] : m[2]
    const findings = r.findings.map((f) => (f.locations.length === 1 && f.locations[0] === DEFAULT_LOCATION ? { ...f, locations: [`src/${key}.mjs:1`] } : f))
    return { ...r, findings }
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
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
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
  assert.equal(meta.name, 'deep-codebase-audit')
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
  // Review finding O2: whenToUse must also warn that quick and standard fit the default maxAgents (60)
  // while full needs allowLarge (or a raised maxAgents) — required by arbitration
  // case 2 (coordinator citation: Phase C contract Appendix B META + review finding O2).
  assert.match(meta.whenToUse, /maxAgents/, 'whenToUse must name maxAgents')
  assert.match(meta.whenToUse, /allowLarge/, 'whenToUse must name allowLarge')
  assert.match(meta.whenToUse, /quick and standard fit the default maxAgents/)
})

test('whenToUse claim: with Scope proposing, preset gaps and six measured candidates, standard projects at most 60 agents and full more than 60', () => {
  const ids = ['c1', 'c2', 'c3', 'c4', 'c5', 'c6']
  const m = () => measuredFixture({ candidates: ids.map((id) => candidateFixture(id)) })
  for (const measured of [null, m()]) {
    const total = (preset) => {
      const { ok, plan, reason } = CORE.normalizeArgs(noReviewers({ preset, gaps: undefined, measured }))
      assert.equal(ok, true, reason)
      return CORE.projectAgents(plan).total
    }
    assert.ok(total('standard') <= 60, 'standard fits the default maxAgents')
    assert.ok(total('full') > 60, 'full exceeds the default maxAgents')
  }
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
  for (const name of ['SCOPE', 'FINDINGS', 'CRITIC', 'DEDUP', 'VERDICT', 'VERDICTS', 'SYNTH', 'TRIAGE']) {
    assert.ok(built[name], `schemas(plan) must return ${name}`)
    assertSchemaShapeValid(built[name], name)
  }
  // FINDINGS.category enum must come from plan.categories (declarations: "category enum from args.categories").
  assert.deepEqual(built.FINDINGS.properties.findings.items.properties.category.enum, plan.categories)
})

test('S12: audit/result-v2 has exactly the documented top-level keys', async () => {
  const plan = buildPlan()
  const { agent } = autoAgent(standardResponder(plan))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.deepEqual(
    Object.keys(result).sort(),
    ['contract', 'started', 'runId', 'stats', 'report', 'kept', 'refuted', 'proposal', 'metricsPath', 'date', 'scope'].sort()
  )
  assert.deepEqual(result.scope, { paths: plan.scope.paths })
  assert.notEqual(result.scope.paths, plan.scope.paths, 'the result copies the paths')
  assert.equal(result.contract, 'audit/result-v2')
  assert.deepEqual(
    Object.keys(result.stats).sort(),
    [
      'reviewers', 'candidateReviewers', 'verifyAgents', 'gaps', 'raw', 'merged', 'kept', 'confirmed', 'plausible', 'unverified',
      'refuted', 'missing', 'projectedAgents', 'droppedGaps', 'unverifiedByCap', 'metricsPending', 'mergeAgents',
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

test('S8: wrong/missing contract -> "invalid args: contract must be audit/args-v2"', () => {
  const { ok, reason } = CORE.normalizeArgs({})
  assert.equal(ok, false)
  assert.equal(reason, 'invalid args: contract must be audit/args-v2')
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
    const { ok, plan } = CORE.normalizeArgs(baseArgs({ preset: name, scope: { paths: ['x'] }, gaps: undefined }))
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
// E7 (D8, T8a D11): no measure term; merge = max(1, ceil(est / 40)); R = reviewers given ? reviewers + lenses + (D7 appends ? 1 : 0)
// : reviewerCount; triage = items.enabled ? ceil(est / 10) : 0 (est uncapped); the other terms as before.
// T8b (D15): C = assigned candidates (hand-counted: distinct non-empty string ids of measured.candidates, cut to candidateCount;
// none without measured, in a docs focus or at count 0); est = (R + gapsMax + C) * 8; verify = ceil(nV / groupSize) * lens.
function handCandidateCount(plan) {
  if (!plan.measured || plan.focus === 'docs' || plan.scope.candidateCount === 0) return 0
  const seen = new Set()
  for (const c of plan.measured.candidates) {
    if (c && typeof c === 'object' && typeof c.candidateId === 'string' && c.candidateId !== '') seen.add(c.candidateId)
  }
  return Math.min(seen.size, plan.scope.candidateCount)
}
function handComputeTotal(plan) {
  const reviewersGiven = Array.isArray(plan.scope.reviewers)
  const lenses = plan.scope.lenses || []
  const appends = plan.focus !== 'docs' && !lenses.some((l) => l.key === 'architecture')
  const R = reviewersGiven
    ? plan.scope.reviewers.length + lenses.length + (appends ? 1 : 0)
    : plan.scope.reviewerCount
  const scope = reviewersGiven ? 0 : 1
  const review = R
  const gaps = plan.gaps.enabled ? 1 + plan.gaps.max : 0
  const C = handCandidateCount(plan)
  const est = (R + (plan.gaps.enabled ? plan.gaps.max : 0) + C) * 8
  const nV = Math.min(est, plan.verify.maxFindings)
  const verify = Math.ceil(nV / plan.verify.groupSize) * Math.max(plan.verify.highLenses.length, plan.verify.defaultLenses.length)
  const merge = Math.max(1, Math.ceil(est / 40))
  const synth = 1
  const triage = plan.items && plan.items.enabled ? Math.ceil(est / 10) : 0
  return {
    total: scope + review + C + gaps + merge + verify + synth + triage,
    scope, review, candidates: C, gaps, merge, verify, synth, triage,
  }
}

test('S8: projectAgents matches the frozen formula, reviewers-absent estimate path', () => {
  const { plan } = CORE.normalizeArgs(
    baseArgs({ preset: 'full', scope: { paths: ['x'] } }) // no scope.reviewers -> estimate via reviewerCount
  )
  const expected = handComputeTotal(plan)
  const actual = CORE.projectAgents(plan)
  assert.deepEqual(
    { total: actual.total, scope: actual.scope, review: actual.review, candidates: actual.candidates, gaps: actual.gaps, merge: actual.merge, verify: actual.verify, synth: actual.synth, triage: actual.triage },
    expected
  )
})

test('S8: projectAgents matches the frozen formula, reviewers-given path (scope=0)', () => {
  const plan = buildPlan({ preset: 'standard', items: { enabled: true, rootId: 'root-1' } })
  const expected = handComputeTotal(plan)
  const actual = CORE.projectAgents(plan)
  assert.equal(actual.scope, 0)
  assert.deepEqual(
    { total: actual.total, scope: actual.scope, review: actual.review, candidates: actual.candidates, gaps: actual.gaps, merge: actual.merge, verify: actual.verify, synth: actual.synth, triage: actual.triage },
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

test('S3: canonical member is the least (category, primaryPath, first location); severity is the max across members', () => {
  const all = [
    rawFinding('p1', 's1', { severity: 'low' }),
    rawFinding('p2', 's2', { severity: 'critical' }),
    rawFinding('p3', 's3', { severity: 'medium' }),
  ]
  const groups = [{ canonicalId: 'p2', duplicateIds: ['p1', 'p3'], mergedTitle: 'M' }]
  const merged = CORE.mergeFindings(all, groups)
  assert.equal(merged.length, 1)
  // canonical = least (category, primaryPath, first location), not the group's stated canonicalId field (the default locations loc-<id>-a make key order equal input order)
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
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false, adjustedSeverity: 'high' })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('unexpected label ' + label)
  }
  const runOnce = async (order) => {
    const { agent } = autoAgent(perSlice(responder), { order })
    const result = await CORE.runAudit(plan, makeDeps({ agent }))
    return result.kept
  }
  const fifoKept = await runOnce('fifo')
  const reverseKept = await runOnce('reverse')
  assert.equal(fifoKept.length, 4) // E8: r1..r3 plus the architecture lens's finding
  assert.deepEqual(reverseKept, fifoKept)
})

// ── S1: barrier ordering (autoAgent order 'reverse') ─────────────────────────
// Oracle: phase-c-workflows.md §2.3 phase table; Appendix B LABELS/opts.phase mapping.

function s1aResponder() {
  return async (label) => {
    if (label === 'scope') return scopeResult({ reviewers: [reviewerSpec('r1'), reviewerSpec('r2')], lenses: [] })
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding({ severity: 'high', locations: [sharedLocation(label.slice(7))] })] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
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
  const { agent, calls } = autoAgent(perSlice(s1aResponder()), { order: 'reverse' })
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
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding({ locations: [sharedLocation(label.slice(7))] })] })
    if (label === 'critic') return criticResult({ gaps: [{ label: 'coverage-gap', prompt: 'look at X' }] })
    if (label === 'gap:1') return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('s1b: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(perSlice(responder), { order: 'reverse' })
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
  const { agent, calls } = autoAgent(perSlice(responder))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.deepEqual(result.kept, [])
  assert.ok(result.stats.missing.includes('scope'))
  assert.equal(result.report, null)
  assert.equal(result.proposal, null)
  assert.deepEqual(result.scope, { paths: ['claude-plugins/task-orchestrator/hooks'] })
  assert.notEqual(result.scope.paths, plan.scope.paths, 'the result copies the paths')
  assert.equal(calls.filter((c) => c.label.startsWith('review:')).length, 0)
})

test('S2: a null reviewer -> that reviewer key is listed in stats.missing, the run still completes', async () => {
  const plan = buildPlan() // reviewers r1, r2
  const responder = async (label) => {
    if (label === 'review:r1') return null
    if (label === 'review:r2') return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('unexpected label ' + label)
  }
  const { agent } = autoAgent(perSlice(responder))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.ok(result.stats.missing.includes('r1'))
  assert.equal(result.stats.raw, 1)
})

test('S2: a null merge agent leaves findings unmerged (merged count === raw count, singletons)', async () => {
  const plan = buildPlan() // reviewers r1, r2, one finding each -> raw = 2
  const responder = async (label) => {
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return null
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('unexpected label ' + label)
  }
  const { agent } = autoAgent(perSlice(responder))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.stats.raw, 2)
  assert.equal(result.stats.merged, 2)
})

test('S2: every verify lens returning null marks the finding UNVERIFIED (not dropped)', async () => {
  const plan = buildPlan()
  const responder = async (label) => {
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return null
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('unexpected label ' + label)
  }
  const { agent } = autoAgent(perSlice(responder))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.stats.unverified, result.kept.length)
  assert.ok(result.kept.length > 0)
  assert.ok(result.kept.every((k) => k.verdict === 'UNVERIFIED'))
})

test('S2: a null synthesis agent -> report null, kept set still returned', async () => {
  const plan = buildPlan()
  const responder = async (label) => {
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return null
    throw new Error('unexpected label ' + label)
  }
  const { agent } = autoAgent(perSlice(responder))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.report, null)
  assert.equal(result.kept.length, 2)
})

test('S2: a null triage agent marks affected findings likelyDuplicate "unknown" and not materialized', async () => {
  const plan = buildPlan({ items: { enabled: true, rootId: 'root-test-0001', materializeMin: 'high' } })
  const responder = async (label) => {
    if (label === 'review:r1' || label === 'review:r2') return findingsResult({ findings: [makeFinding({ severity: 'high' })] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    if (label.startsWith('triage:')) return null
    throw new Error('unexpected label ' + label)
  }
  const { agent } = autoAgent(perSlice(responder))
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

// The Appendix B clause quoted above, that the synthesis prompt contains reportPath, is superseded by D12/E9.
test('S6: every prompt, synthesis included, is READ-ONLY and excludes write-tool names; synthesis does not name reportPath; no call sets opts.isolation', async () => {
  const plan = buildPlan({ gaps: { enabled: true, max: 1 } })
  const responder = async (label) => {
    if (label.startsWith('review:')) return findingsResult({ findings: [makeFinding()] })
    if (label === 'critic') return criticResult({ gaps: [{ label: 'g', prompt: 'p' }] })
    if (label.startsWith('gap:')) return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(perSlice(responder))
  await CORE.runAudit(plan, makeDeps({ agent }))
  assert.ok(calls.length > 0)
  for (const c of calls) {
    assert.equal(c.opts.isolation, undefined, `call ${c.label} must not set opts.isolation`)
    // E9 (D12): the synthesis prompt is READ-ONLY too; it writes no file
    assert.match(c.prompt, /READ-ONLY/, `${c.label} prompt must contain READ-ONLY`)
    for (const forbidden of ['manage_items', 'manage_notes', 'advance_item', 'create_work_tree', 'items_write', 'notes_write', 'runs_write', 'docs_write']) {
      assert.equal(c.prompt.includes(forbidden), false, `${c.label} prompt must not mention ${forbidden}`)
    }
  }
  const synth = calls.find((c) => c.label === 'synthesis')
  assert.ok(synth, 'synthesis must have been called')
  assert.equal(synth.prompt.includes(plan.reportPath), false, 'synthesis prompt must not name reportPath (D12)')
})

// 4.0 port (cac1bffa, AC2/AC11): no retired 3.x tool name may appear in any rendered audit prompt.
// Fixture condition that would fail it: the 3.x prompts named query_items / query_notes / get_context.
test('S14 (4.0): no audit prompt names a retired 3.x tool (RETIRED_3X_TOOLS), whole-word', async () => {
  const plan = buildPlan({ gaps: { enabled: true, max: 1 }, items: { enabled: true, rootId: 'root-test-0001', materializeMin: 'high' } })
  const responder = async (label) => {
    if (label.startsWith('review:')) return findingsResult({ findings: [makeFinding()] })
    if (label === 'critic') return criticResult({ gaps: [{ label: 'g', prompt: 'p' }] })
    if (label.startsWith('gap:')) return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    if (label.startsWith('triage:')) return triageResult({ results: [{ findingId: 'r1-1', candidates: [], likelyDuplicate: 'none' }] })
    throw new Error('unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(perSlice(responder))
  await CORE.runAudit(plan, makeDeps({ agent }))
  assert.ok(calls.length > 0)
  for (const c of calls) {
    for (const name of RETIRED_3X_TOOLS) {
      const re = new RegExp('(^|[^A-Za-z0-9_])' + name + '([^A-Za-z0-9_]|$)')
      assert.equal(re.test(c.prompt), false, `${c.label} prompt names retired tool ${name}`)
    }
  }
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
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label === 'verify:r1-1:combined') return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('cap-tail: unexpected label ' + label) // r2-1 must never reach Verify — it is capped
  }
  const { agent } = autoAgent(perSlice(responder))
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
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('droppedGaps: unexpected label ' + label) // gap:2 / gap:3 must never run (max=1)
  }
  const { agent, calls } = autoAgent(perSlice(responder))
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
    if (label.startsWith('merge:')) throw new Error('merge must not be called when raw === 0')
    if (label.startsWith('verify:')) throw new Error('verify must not be called when raw === 0')
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('raw==0: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(perSlice(responder))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.stats.raw, 0)
  assert.equal(calls.some((c) => c.label.startsWith('merge:')), false)
  assert.equal(calls.some((c) => c.label.startsWith('verify:')), false)
})

test('Follow-up S6: the scope prompt is READ-ONLY; the triage prompt names items_read search', async () => {
  const plan = buildPlan({
    scope: { paths: ['claude-plugins/task-orchestrator/hooks'] }, // reviewers absent -> Scope runs
    items: { enabled: true, rootId: 'root-test-0001', materializeMin: 'high' },
  })
  const responder = async (label) => {
    if (label === 'scope') return scopeResult({ reviewers: [reviewerSpec('r1')], lenses: [] })
    if (label === 'review:r1') return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    if (label.startsWith('triage:')) {
      return triageResult({ results: [{ findingId: 'r1-1', candidates: [], likelyDuplicate: 'none' }] })
    }
    throw new Error('S6 scope/triage: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(perSlice(responder))
  await CORE.runAudit(plan, makeDeps({ agent }))
  const scopeCall = calls.find((c) => c.label === 'scope')
  assert.ok(scopeCall, 'scope must have been called')
  assert.match(scopeCall.prompt, /READ-ONLY/)
  const triageCall = calls.find((c) => c.label.startsWith('triage:'))
  assert.ok(triageCall, 'triage must have been called')
  assert.match(triageCall.prompt, /items_read/)
  assert.match(triageCall.prompt, /search/)
  assert.equal(triageCall.prompt.includes('query_items'), false, 'the retired 3.x read tool name must be gone (AC11)')
})

test('Follow-up O6: scope.reviewers: [] is treated as absent (Scope phase runs), per Appendix B', async () => {
  const plan = buildPlan({ scope: { paths: ['claude-plugins/task-orchestrator/hooks'], reviewers: [], lenses: [] } })
  const responder = async (label) => {
    if (label === 'scope') return scopeResult({ reviewers: [reviewerSpec('r1')], lenses: [] })
    if (label === 'review:r1') return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('O6: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(perSlice(responder))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.ok(calls.some((c) => c.label === 'scope'), 'an empty scope.reviewers array must still trigger the Scope phase')
  assert.equal(result.started, true)
})

// ── Second follow-up (reviewer O10: fix-up behaviour untested) ──────────────
// Oracle: phase-c-workflows.md §2.2/§2.3 + phase-c-dispatch-contract.md Appendix B, as
// always — never the current script's own output. The coordinator flagged that the models
// per-phase key is currently read as `models.synthesis` rather than plan §2.2's `synth`; test
// (O10-1) is written to §2.2 regardless and reported red if it is red against HEAD.

// E10 (D3): the dispatch key map. scope -> scope, review:<key> and gap:<n> -> review, review:architecture
// -> review-architecture, verify group calls with a high or critical member -> verify-group-high, else verify-group; an
// omitted key yields its default entry (never inherit). The default table below is a literal copy of D3.
const D3_DEFAULTS = {
  scope: { model: 'sonnet', effort: 'medium' },
  review: { model: 'sonnet', effort: 'high' },
  'review-architecture': { model: 'opus', effort: 'high' },
  critic: { model: 'sonnet', effort: 'medium' },
  merge: { model: 'sonnet', effort: 'low' },
  'review-candidate': { model: 'sonnet', effort: 'high' },
  'verify-group': { model: 'sonnet', effort: 'medium' },
  'verify-group-high': { model: 'opus', effort: 'medium' },
  synth: { model: 'opus', effort: 'medium' },
  triage: { model: 'sonnet', effort: 'low' },
}

test('Follow-up (O10-1, D3): args.models/args.efforts reach agent() opts per dispatch key; scope, review:architecture, gap and verify-group-high have their own keys; an omitted key yields its default entry', async () => {
  // models['verify-group'] is deliberately omitted (a low finding reaches the plain verify-group key): the omitted
  // key must read its D3 default, never inherit; efforts['verify-group'] IS given, so only the model defaults.
  const MODELS = {
    scope: 'm-scope', review: 'm-review', 'review-architecture': 'm-arch', critic: 'm-critic', merge: 'm-merge',
    'verify-group-high': 'm-vhigh', synth: 'm-synth', triage: 'm-triage',
  }
  const EFFORTS = {
    scope: 'e-scope', review: 'e-review', 'review-architecture': 'e-arch', critic: 'e-critic', merge: 'e-merge',
    'verify-group': 'e-verify', 'verify-group-high': 'e-vhigh', synth: 'e-synth', triage: 'e-triage',
  }
  const plan = buildPlan({
    scope: { paths: ['claude-plugins/task-orchestrator/hooks'] }, // reviewers absent -> Scope runs
    gaps: { enabled: true, max: 1 },
    items: { enabled: true, rootId: 'root-test-0001', materializeMin: 'high' },
    verify: { groupSize: 1 }, // one finding per group, so each verify call has one severity
    models: MODELS,
    efforts: EFFORTS,
  })
  const sevBySlice = { r1: 'high', r2: 'low', architecture: 'high', gap1: 'high' }
  const responder = async (label) => {
    if (label === 'scope') return scopeResult({ reviewers: [reviewerSpec('r1'), reviewerSpec('r2')], lenses: [] })
    if (label.startsWith('review:')) return findingsResult({ findings: [makeFinding({ severity: sevBySlice[label.slice(7)], locations: [sharedLocation(label.slice(7))] })] })
    if (label === 'critic') return criticResult({ gaps: [{ label: 'g1', prompt: 'p1' }] })
    if (label === 'gap:1') return findingsResult({ findings: [makeFinding({ severity: sevBySlice.gap1 })] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult()
    if (label.startsWith('triage:')) return triageResult({ results: [] })
    throw new Error('models/efforts: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(perSlice(responder))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.started, true)

  const expected = (key) => ({
    model: MODELS[key] === undefined ? D3_DEFAULTS[key].model : MODELS[key],
    effort: EFFORTS[key] === undefined ? D3_DEFAULTS[key].effort : EFFORTS[key],
  })
  const checkKey = (pred, key, desc) => {
    const matching = calls.filter(pred)
    assert.ok(matching.length > 0, `expected at least one call for ${desc}`)
    const { model, effort } = expected(key)
    for (const c of matching) {
      assert.equal(c.opts.model, model, `${desc} model must come from dispatch key ${key}`)
      assert.equal(c.opts.effort, effort, `${desc} effort must come from dispatch key ${key}`)
      assert.equal(c.opts.label, `${c.label} [${model}/${effort}]`, `${desc} label must carry the [model/effort] tag`)
    }
  }
  checkKey((c) => c.label === 'scope', 'scope', 'scope')
  checkKey((c) => c.label.startsWith('review:') && c.label !== 'review:architecture', 'review', 'review:<key>')
  checkKey((c) => c.label === 'review:architecture', 'review-architecture', 'review:architecture')
  checkKey((c) => c.label.startsWith('gap:'), 'review', 'gap:<n> (reuses the review key)')
  checkKey((c) => c.label === 'critic', 'critic', 'critic')
  checkKey((c) => c.label.startsWith('merge:'), 'merge', 'merge')
  checkKey((c) => c.label === 'synthesis', 'synth', 'synthesis')
  checkKey((c) => c.label.startsWith('triage:'), 'triage', 'triage:<n>')
  // T8b (D9): the key follows the group's highest severity; a group of one carries its finding's severity in the prompt.
  const verifySev = (c) => findingsOfPrompt(c.prompt)[0].severity
  checkKey((c) => c.label.startsWith('verify:') && (verifySev(c) === 'high' || verifySev(c) === 'critical'), 'verify-group-high', 'verify of a high finding')
  const low = calls.filter((c) => c.label.startsWith('verify:') && verifySev(c) === 'low')
  assert.ok(low.length > 0, 'fixture must reach the plain verify-group key (a low finding)')
  for (const c of low) {
    assert.equal(c.opts.model, D3_DEFAULTS['verify-group'].model, 'models.verify-group omitted -> the D3 default model, never inherit')
    assert.equal(c.opts.effort, EFFORTS['verify-group'])
    assert.equal(c.opts.label, `${c.label} [${D3_DEFAULTS['verify-group'].model}/${EFFORTS['verify-group']}]`)
  }
})

test('Follow-up (O10-2): the verify prompt names its own lens, every VERDICT field, and items_read (how alreadyTrackedId is derived)', async () => {
  const plan = buildPlan({
    verify: { highLenses: ['evidence', 'significance', 'consequence'], defaultLenses: ['combined'], maxFindings: 40, groupSize: 1 },
  })
  const responder = async (label) => {
    if (label === 'review:r1') return findingsResult({ findings: [makeFinding({ severity: 'critical' })] })
    if (label === 'review:r2') return findingsResult({ findings: [makeFinding({ severity: 'low' })] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('verify-prompt: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(perSlice(responder))
  await CORE.runAudit(plan, makeDeps({ agent }))
  const verifyCalls = calls.filter((c) => c.label.startsWith('verify:'))
  assert.equal(verifyCalls.length, 4, 'expect 3 highLenses calls (critical finding) + 1 defaultLenses call (low finding)')
  const VERDICT_FIELDS = ['refuted', 'confidence', 'evidenceAccurate', 'correctedEvidence', 'adjustedSeverity', 'alreadyTrackedId', 'rationale']
  for (const c of verifyCalls) {
    const lens = c.label.split(':').pop()
    // Case-insensitive: the reference lens headers are uppercase ("LENS = SIGNIFICANCE."),
    // and not every lens body happens to also use the lowercase word elsewhere (unlike
    // evidence/consequence, whose bodies incidentally do) — the requirement is that the
    // prompt names the lens, not that it does so in a particular case.
    assert.match(c.prompt, new RegExp(lens, 'i'), `${c.label} prompt must name its own lens "${lens}"`)
    for (const field of VERDICT_FIELDS) {
      assert.ok(c.prompt.includes(field), `${c.label} prompt must name VERDICT field "${field}"`)
    }
    assert.ok(c.prompt.includes('findingId'), `${c.label} prompt must name findingId, the key of each verdict`)
    assert.ok(c.prompt.includes('verdicts'), `${c.label} prompt must name the verdicts list the agent returns (task-scope O10-2 edit: also expect verdicts)`)
    assert.ok(c.prompt.includes('items_read'), `${c.label} prompt must mention items_read (alreadyTrackedId derivation)`)
    assert.equal(c.prompt.includes('query_items'), false, `${c.label} prompt must not name the retired query_items`)
  }
})

test('Follow-up (O10-3): a Scope agent proposing more reviewers than reviewerCount is capped to reviewerCount review calls', async () => {
  const plan = buildPlan({
    preset: 'quick',
    scope: { paths: ['claude-plugins/task-orchestrator/hooks'] }, // reviewers absent -> Scope runs
  })
  const proposedReviewers = Array.from({ length: 6 }, (_, i) => reviewerSpec(`r${i + 1}`))
  const responder = async (label) => {
    if (label === 'scope') return scopeResult({ reviewers: proposedReviewers, lenses: [] })
    if (label.startsWith('review:')) return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('scope-cap: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(perSlice(responder))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.started, true)
  const reviewCalls = calls.filter((c) => c.label.startsWith('review:'))
  assert.equal(reviewCalls.length, CORE.PRESETS.quick.reviewerCount)
  assert.equal(reviewCalls.length, 4)
})

test('Follow-up (O10-4, D15): triage:<n> call count equals ceil(kept/10) - the unverified-by-cap tail is triaged too', async () => {
  const reviewers = Array.from({ length: 25 }, (_, i) => reviewerSpec(`r${i + 1}`))
  const plan = buildPlan({
    scope: { paths: ['claude-plugins/task-orchestrator/hooks'], reviewers, lenses: [] },
    verify: { highLenses: ['combined'], defaultLenses: ['combined'], maxFindings: 15 },
    gaps: { enabled: false, max: 0 },
    items: { enabled: true, rootId: 'root-test-0001', materializeMin: 'high' },
    maxAgents: 100, // E11: the projection is now 64 (25 reviewers + the architecture lens)
  })
  const projected = CORE.projectAgents(plan)
  assert.ok(projected.total <= plan.maxAgents, 'fixture must stay within default maxAgents for this test to run without allowLarge')
  const responder = async (label) => {
    if (label.startsWith('review:')) return findingsResult({ findings: [makeFinding({ severity: 'medium' })] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    if (label.startsWith('triage:')) return triageResult({ results: [] })
    throw new Error('triage-cap: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(perSlice(responder))
  const result = await CORE.runAudit(plan, makeDeps({ agent }))
  assert.equal(result.started, true)
  assert.equal(result.kept.length, 26, 'all 26 merged findings are kept (25 reviewers + the architecture lens; verified ones plus the unverified-by-cap tail)')
  const expected = Math.ceil(result.kept.length / 10)
  assert.equal(expected, 3)
  assert.notEqual(expected, Math.ceil(Math.min(result.kept.length, plan.verify.maxFindings) / 10), 'fixture must distinguish triage over every kept row from triage over the verified cap only')
  const triageCalls = calls.filter((c) => c.label.startsWith('triage:'))
  assert.equal(triageCalls.length, expected)
})

// O10-5 (whenToUse contains 'maxAgents' and 'allowLarge'): already asserted by the T-meta
// test above (added for arbitration case 2) — not duplicated here.

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

// ── Method skill (item e7887059, implementer-written) ────────────────────────
// Oracle: the item's task-scope: every reviewer agent (subsystem slice, cross-cutting lens and
// gap follow-up) is told to load the skill named by the data field `methodSkill`, default
// 'task-orchestrator:auditing-codebase'; the name never appears in prompt prose.

const METHOD_DEFAULT = 'task-orchestrator:auditing-codebase'

function methodRunResponder(plan) {
  return async (label) => {
    if (label.startsWith('review:')) return findingsResult({ findings: [makeFinding()] })
    if (label === 'critic') return criticResult({ gaps: [{ label: 'g', prompt: 'p' }] })
    if (label.startsWith('gap:')) return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('method skill: unexpected label ' + label)
  }
}

function methodRunPlan(overrides = {}) {
  return buildPlan({
    gaps: { enabled: true, max: 1 },
    scope: {
      paths: ['claude-plugins/task-orchestrator/hooks'],
      reviewers: [reviewerSpec('r1')],
      lenses: [{ key: 'l1', label: 'Lens l1', focus: 'lens focus' }],
    },
    ...overrides,
  })
}

test('Method skill: the default is task-orchestrator:auditing-codebase', () => {
  assert.equal(CORE.DEFAULT_METHOD_SKILL, METHOD_DEFAULT)
  assert.equal(buildPlan().methodSkill, METHOD_DEFAULT)
})

test('Method skill: every reviewer, lens and gap prompt names the default skill and the Skill tool; no other prompt does', async () => {
  const plan = methodRunPlan()
  const { agent, calls } = autoAgent(methodRunResponder(plan))
  await CORE.runAudit(plan, makeDeps({ agent }))
  const reviewerCalls = calls.filter((c) => c.label.startsWith('review:') || c.label.startsWith('gap:'))
  assert.deepEqual(reviewerCalls.map((c) => c.label).sort(), ['gap:1', 'review:architecture', 'review:l1', 'review:r1'])
  for (const c of reviewerCalls) {
    assert.ok(c.prompt.includes(METHOD_DEFAULT), `${c.label} prompt must name the method skill`)
    assert.match(c.prompt, /Skill tool/, `${c.label} prompt must say how to load it`)
  }
  for (const c of calls.filter((c) => !reviewerCalls.includes(c))) {
    assert.equal(c.prompt.includes(METHOD_DEFAULT), false, `${c.label} prompt must not name the method skill`)
  }
})

test('Method skill: a configured methodSkill replaces the default in every reviewer prompt', async () => {
  const plan = methodRunPlan({ methodSkill: 'proj:area-audit' })
  assert.equal(plan.methodSkill, 'proj:area-audit')
  const { agent, calls } = autoAgent(methodRunResponder(plan))
  await CORE.runAudit(plan, makeDeps({ agent }))
  const reviewerCalls = calls.filter((c) => c.label.startsWith('review:') || c.label.startsWith('gap:'))
  assert.equal(reviewerCalls.length, 4)
  for (const c of reviewerCalls) {
    assert.ok(c.prompt.includes('proj:area-audit'), `${c.label} prompt must name the configured skill`)
    assert.equal(c.prompt.includes(METHOD_DEFAULT), false, `${c.label} prompt must not name the default`)
  }
})

test('Method skill: methodSkill is trimmed, and null falls back to the default', () => {
  assert.equal(buildPlan({ methodSkill: ' p:x ' }).methodSkill, 'p:x')
  assert.equal(buildPlan({ methodSkill: null }).methodSkill, METHOD_DEFAULT)
})

// Oracle: amendments A1 / O2: the slice and its lens set coverage; a hot-spot ranking only
// orders attention and never drops cold code; the bug-class categories (those the class map
// sends to 'bug') stay in scope whatever the method says about bug hunts.
test('Method skill: every reviewer, lens and gap prompt says the slice bounds coverage and keeps the bug-class categories', async () => {
  const plan = methodRunPlan()
  const { agent, calls } = autoAgent(methodRunResponder(plan))
  await CORE.runAudit(plan, makeDeps({ agent }))
  const reviewerCalls = calls.filter((c) => c.label.startsWith('review:') || c.label.startsWith('gap:'))
  assert.equal(reviewerCalls.length, 4)
  const bugList = 'concurrency, transactions, error-handling, security-architecture, data-model'
  for (const c of reviewerCalls) {
    assert.match(c.prompt, /hot spots[^.]*only orders your attention inside the slice and never drops cold code from it/,
      `${c.label} prompt must say a hot-spot ranking never narrows the slice`)
    assert.ok(c.prompt.includes('defects in the categories ' + bugList + ' stay in scope'),
      `${c.label} prompt must keep the bug-class categories in scope`)
    assert.match(c.prompt, /whatever the method says about bug hunts/, `${c.label} prompt must override the method's bug-hunt exclusion`)
  }
})

test('Method skill: the in-scope bug-class list follows the class map', () => {
  const plan = buildPlan({ classMap: { concurrency: 'tech-debt', 'dead-code': 'bug' } })
  const preamble = CORE.reviewerPreamble(plan)
  assert.ok(preamble.includes('defects in the categories transactions, error-handling, security-architecture, data-model, dead-code stay in scope'))
  const none = CORE.reviewerPreamble(buildPlan({ classMap: { concurrency: 'tech-debt', transactions: 'tech-debt', 'error-handling': 'tech-debt', 'security-architecture': 'tech-debt', 'data-model': 'tech-debt' } }))
  assert.match(none, /never drops cold code from it\. /)
  assert.equal(none.includes('defects in the categories'), false)
})

test('Method skill: a methodSkill that is not a non-empty string is rejected', () => {
  for (const bad of ['', '   ', 3, true, {}]) {
    const { ok, reason } = CORE.normalizeArgs(baseArgs({ methodSkill: bad }))
    assert.equal(ok, false, `methodSkill ${JSON.stringify(bad)} must be rejected`)
    assert.equal(reason, 'invalid args: methodSkill must be a non-empty string')
  }
})

test('Method skill: the script names the default skill once, as data, never in prompt prose', () => {
  const text = scriptText(SCRIPT_PATH)
  assert.equal(text.split(METHOD_DEFAULT).length - 1, 1, 'the default skill name appears exactly once (the data constant)')
  assert.equal(text.split('auditing-codebase').length - 1, 1, 'no other spelling of the skill name appears in the script')
})

// ── Method per slice, focus and docs categories (item 41102af3) ──────────────
// Implementer-written; oracle = that item's task-scope (T1-T10), not the script.

const DOCS_SKILL = 'task-orchestrator:auditing-documentation'
const CODE_SIDE = 'Document files in your scope belong to the documentation slices; comments inside source files stay yours.'
const DOCS_SIDE = 'DOCUMENTATION SLICE: audit only the document files in your scope; comments inside source files belong to the code slices.'

function kindResponder(plan, { scope, critic } = {}) {
  return async (label) => {
    if (label === 'scope') return scope
    if (label === 'critic') return critic
    if (label.startsWith('review:') || label.startsWith('gap:')) return findingsResult({ findings: [makeFinding()] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('kind: unexpected label ' + label)
  }
}

function plannerSlice(key, kind) {
  return { key, label: 'Slice ' + key, focus: 'f', kind, paths: ['p/' + key] }
}

function noReviewers(overrides = {}) {
  const a = baseArgs(overrides)
  a.scope = { paths: ['p'], reviewers: null, lenses: [] }
  return a
}

test('T1 focus: absent or null is all; code, docs, all accepted; anything else rejected', () => {
  assert.equal(buildPlan().focus, 'all')
  assert.equal(buildPlan({ focus: null }).focus, 'all')
  for (const f of ['code', 'docs', 'all']) {
    const { ok, plan } = CORE.normalizeArgs(noReviewers({ focus: f }))
    assert.equal(ok, true)
    assert.equal(plan.focus, f)
  }
  for (const bad of ['both', '', 'CODE', 3]) {
    const { ok, reason } = CORE.normalizeArgs(baseArgs({ focus: bad }))
    assert.equal(ok, false, `focus ${JSON.stringify(bad)} must be rejected`)
    assert.equal(reason, 'invalid args: focus must be code, docs or all')
  }
})

test('T2 caller slices: kind, methodSkill and focus reasons for a reviewer and for a lens', () => {
  const withSlice = (where, s, extra = {}) => {
    const a = baseArgs(extra)
    a.scope = { paths: ['p'], reviewers: [], lenses: [] }
    if (where === 'reviewer') a.scope.reviewers = [s]
    else a.scope.lenses = [s]
    return CORE.normalizeArgs(a)
  }
  for (const where of ['reviewer', 'lens']) {
    let r = withSlice(where, reviewerSpec('x', { kind: 'both' }))
    assert.equal(r.reason, 'invalid args: slice x kind must be code or docs')
    for (const bad of ['', '  ', 3]) {
      r = withSlice(where, reviewerSpec('x', { methodSkill: bad }))
      assert.equal(r.reason, 'invalid args: slice x methodSkill must be a non-empty string')
    }
    r = withSlice(where, reviewerSpec('x', { kind: 'docs' }), { focus: 'code' })
    assert.equal(r.reason, 'invalid args: slice x is kind docs, outside focus code')
    r = withSlice(where, reviewerSpec('x'), { focus: 'docs' })
    assert.equal(r.reason, 'invalid args: slice x is kind code, outside focus docs')
    r = withSlice(where, reviewerSpec('x', { kind: 'docs', methodSkill: 'p:m' }), { focus: 'docs' })
    assert.equal(r.ok, true)
  }
})

test('T2b malformed reviewers or lenses return a reason and never throw', () => {
  const run = (where, value) => {
    const a = baseArgs()
    a.scope = { paths: ['p'], reviewers: [], lenses: [] }
    a.scope[where] = value
    return CORE.normalizeArgs(a)
  }
  for (const where of ['reviewers', 'lenses']) {
    for (const bad of [{}, { key: 'x' }, 'r1', 3, true]) {
      const r = run(where, bad)
      assert.equal(r.ok, false, `${where} ${JSON.stringify(bad)} must be rejected`)
      assert.equal(r.reason, `invalid args: scope.${where} must be an array`)
    }
    for (const entry of [null, 3, 'r1', [], undefined]) {
      const r = run(where, [entry])
      assert.equal(r.ok, false, `${where} entry ${JSON.stringify(entry)} must be rejected`)
      assert.equal(r.reason, `invalid args: scope.${where}[0] must be an object`)
    }
    const second = run(where, [reviewerSpec('ok'), null])
    assert.equal(second.reason, `invalid args: scope.${where}[1] must be an object`)
    assert.equal(run(where, null).ok, true)
    assert.equal(run(where, [reviewerSpec('ok')]).ok, true)
  }
})

test('T3 resolution order of the method skill', async () => {
  const plan = buildPlan({
    methodSkill: 'proj:run',
    gaps: { enabled: true, max: 2 },
    scope: {
      paths: ['p'],
      reviewers: [
        reviewerSpec('r1', { methodSkill: ' proj:own ' }),
        reviewerSpec('r2', { kind: 'docs' }),
        reviewerSpec('r3'),
      ],
      lenses: [{ key: 'ld', label: 'ld', focus: 'f', kind: 'docs', methodSkill: 'proj:lens' }],
    },
  })
  const critic = criticResult({ gaps: [{ label: 'gd', kind: 'docs', prompt: 'p' }, { label: 'gc', prompt: 'p' }] })
  const { agent, calls } = autoAgent(kindResponder(plan, { critic }))
  await CORE.runAudit(plan, makeDeps({ agent }))
  const prompt = (label) => calls.find((c) => c.label.startsWith(label)).prompt
  const names = (p) => ['proj:own', DOCS_SKILL, 'proj:run', 'proj:lens', METHOD_DEFAULT].filter((n) => p.includes(n))
  assert.deepEqual(names(prompt('review:r1')), ['proj:own'])
  assert.deepEqual(names(prompt('review:r2')), [DOCS_SKILL])
  assert.deepEqual(names(prompt('review:r3')), ['proj:run'])
  assert.deepEqual(names(prompt('review:ld')), ['proj:lens'])
  assert.deepEqual(names(prompt('gap:1')), [DOCS_SKILL])
  assert.deepEqual(names(prompt('gap:2')), ['proj:run'])
  const bare = buildPlan()
  assert.ok(CORE.reviewerPreamble(bare, reviewerSpec('r3')).includes(METHOD_DEFAULT))
  assert.equal(CORE.methodSkillOf(bare, { kind: 'docs' }), DOCS_SKILL)
})

test('T4 planner slices are filtered by focus before the reviewerCount cap', async () => {
  const scope = scopeResult({
    reviewers: [plannerSlice('c1', 'code'), plannerSlice('d1', 'docs')],
    lenses: [{ key: 'lc', label: 'lc', focus: 'f', kind: 'code' }, { key: 'ld', label: 'ld', focus: 'f', kind: 'docs' }],
  })
  const reviewLabels = async (focus) => {
    const plan = CORE.normalizeArgs(noReviewers({ focus })).plan
    const { agent, calls } = autoAgent(kindResponder(plan, { scope }))
    await CORE.runAudit(plan, makeDeps({ agent }))
    return calls.filter((c) => c.label.startsWith('review:')).map((c) => c.label).sort()
  }
  assert.deepEqual(await reviewLabels('all'), ['review:architecture', 'review:c1', 'review:d1', 'review:lc']) // B = 3: ld is dropped
  assert.deepEqual(await reviewLabels('code'), ['review:architecture', 'review:c1', 'review:lc'])
  assert.deepEqual(await reviewLabels('docs'), ['review:d1', 'review:ld'])

  const plan = CORE.normalizeArgs(noReviewers({ focus: 'docs' })).plan
  assert.equal(plan.scope.reviewerCount, 4)
  const many = scopeResult({
    reviewers: [1, 2, 3, 4, 5].map((i) => plannerSlice('c' + i, 'code')).concat([plannerSlice('d1', 'docs')]),
  })
  const { agent, calls } = autoAgent(kindResponder(plan, { scope: many }))
  await CORE.runAudit(plan, makeDeps({ agent }))
  assert.deepEqual(calls.filter((c) => c.label.startsWith('review:')).map((c) => c.label), ['review:d1'])
})

test('T5 scope and critic prompts ask for a kind and state the focus', async () => {
  const scope = scopeResult({ reviewers: [plannerSlice('c1', 'code')] })
  const critic = criticResult({ gaps: [] })
  const run = async (focus) => {
    const a = noReviewers({ focus, gaps: { enabled: true, max: 1 } })
    const plan = CORE.normalizeArgs(a).plan
    const { agent, calls } = autoAgent(kindResponder(plan, { scope, critic }))
    await CORE.runAudit(plan, makeDeps({ agent }))
    return [calls.find((c) => c.label === 'scope').prompt, calls.find((c) => c.label === 'critic').prompt]
  }
  for (const [focus, has, lacks] of [['code', 'only kind code', 'only kind docs'], ['docs', 'only kind docs', 'only kind code'], ['all', null, null]]) {
    for (const p of await run(focus)) {
      assert.match(p, /kind/)
      if (has) { assert.ok(p.includes(has)); assert.equal(p.includes(lacks), false) }
      else { assert.equal(p.includes('only kind'), false) }
    }
  }
})

test('T6 critic gaps are filtered by focus before the gaps.max cap', async () => {
  const plan = CORE.normalizeArgs(noReviewers({ focus: 'docs', gaps: { enabled: true, max: 1 } })).plan
  const scope = scopeResult({ reviewers: [plannerSlice('d1', 'docs')] })
  const critic = criticResult({ gaps: [{ label: 'gc', kind: 'code', prompt: 'p' }, { label: 'gd', kind: 'docs', prompt: 'p' }] })
  const logLines = []
  const { agent, calls } = autoAgent(kindResponder(plan, { scope, critic }))
  const res = await CORE.runAudit(plan, makeDeps({ agent, logLines }))
  const gapCalls = calls.filter((c) => c.label.startsWith('gap:'))
  assert.equal(gapCalls.length, 1)
  assert.ok(gapCalls[0].prompt.includes(DOCS_SKILL))
  assert.deepEqual(res.stats.droppedGaps, [])
  assert.ok(logLines.some((l) => l.includes('outside focus docs') && l.includes('gc')))
})

test('T7 path-disjointness sentences follow kind; the code side only when the run has docs', async () => {
  const plan = buildPlan({
    scope: { paths: ['p'], reviewers: [reviewerSpec('c1'), reviewerSpec('d1', { kind: 'docs' })], lenses: [] },
  })
  const { agent, calls } = autoAgent(kindResponder(plan, {}))
  await CORE.runAudit(plan, makeDeps({ agent }))
  const c1 = calls.find((c) => c.label.startsWith('review:c1')).prompt
  const d1 = calls.find((c) => c.label.startsWith('review:d1')).prompt
  assert.ok(d1.includes(DOCS_SIDE))
  assert.equal(d1.includes(CODE_SIDE), false)
  assert.ok(c1.includes(CODE_SIDE))
  assert.equal(c1.includes(DOCS_SIDE), false)
  assert.ok(CORE.reviewerPreamble(plan, { kind: 'docs' }, false).includes(DOCS_SIDE))
  assert.equal(CORE.reviewerPreamble(plan, {}, false).includes(CODE_SIDE), false)
})

test('T8 a default run carries neither sentence and names the default skill', async () => {
  const plan = methodRunPlan()
  const { agent, calls } = autoAgent(methodRunResponder(plan))
  await CORE.runAudit(plan, makeDeps({ agent }))
  const reviewerCalls = calls.filter((c) => c.label.startsWith('review:') || c.label.startsWith('gap:'))
  assert.equal(reviewerCalls.length, 4)
  for (const c of reviewerCalls) {
    assert.equal(c.prompt.includes(CODE_SIDE), false)
    assert.equal(c.prompt.includes(DOCS_SIDE), false)
    assert.ok(c.prompt.includes(METHOD_DEFAULT))
  }
  assert.equal(CORE.reviewerPreamble(plan), CORE.reviewerPreamble(plan, reviewerSpec('r1'), false))
})

test('T9 documentation-scope category sits before documentation-drift and is tech-debt', () => {
  const i = CORE.CATEGORIES.indexOf('documentation-scope')
  assert.ok(i >= 0)
  assert.equal(CORE.CATEGORIES[i + 1], 'documentation-drift')
  assert.equal(CORE.DEFAULT_CLASS_MAP['documentation-scope'], 'tech-debt')
  assert.equal(CORE.DEFAULT_CLASS_MAP['documentation-drift'], 'tech-debt')
  for (const c of CORE.CATEGORIES) assert.ok(CORE.DEFAULT_CLASS_MAP[c], `class map entry for ${c}`)
  const plan = buildPlan()
  assert.ok(CORE.schemas(plan).FINDINGS.properties.findings.items.properties.category.enum.includes('documentation-scope'))
})

test('T10 the docs skill name appears once, as data, and in no non-reviewer prompt', async () => {
  const text = scriptText(SCRIPT_PATH)
  assert.equal(text.split(DOCS_SKILL).length - 1, 1)
  assert.equal(CORE.DOCS_METHOD_SKILL, DOCS_SKILL)
  assert.deepEqual(CORE.FOCUS_VALUES, ['code', 'docs', 'all'])
  const plan = CORE.normalizeArgs(noReviewers({ focus: 'docs', gaps: { enabled: true, max: 1 } })).plan
  const scope = scopeResult({ reviewers: [plannerSlice('d1', 'docs')] })
  const critic = criticResult({ gaps: [{ label: 'gd', kind: 'docs', prompt: 'p' }] })
  const { agent, calls } = autoAgent(kindResponder(plan, { scope, critic }))
  await CORE.runAudit(plan, makeDeps({ agent }))
  for (const c of calls.filter((c) => !c.label.startsWith('review:') && !c.label.startsWith('gap:'))) {
    assert.equal(c.prompt.includes(DOCS_SKILL), false, `${c.label} prompt must not name the docs skill`)
  }
})

// ── Verify lenses judge a documentation finding by the documentation method (parent 72b175d9, O1) ──
// Implementer-written; oracle = the O1 ruling: a docs-category finding's verifier prompt carries the
// docs rule, a code finding's prompt is the lens text unchanged.

test('O1 verify: a documentation-category verifier prompt carries the docs rule; a code finding prompt is unchanged', async () => {
  const plan = buildPlan({
    verify: { highLenses: ['evidence', 'significance', 'consequence', 'combined'], defaultLenses: ['combined'], maxFindings: 40, groupSize: 1 },
  })
  const findings = [
    makeFinding({ title: 'docs-scope finding', category: 'documentation-scope' }),
    makeFinding({ title: 'docs-drift finding', category: 'documentation-drift' }),
    makeFinding({ title: 'code finding', category: 'coupling' }),
  ]
  const responder = async (label) => {
    if (label.startsWith('review:')) return label === 'review:r1' ? findingsResult({ findings }) : findingsResult({ findings: [] })
    if (label.startsWith('merge:')) return dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictResult({ refuted: false })
    if (label === 'synthesis') return synthResult({ reportPath: plan.reportPath })
    throw new Error('O1 verify: unexpected label ' + label)
  }
  const { agent, calls } = autoAgent(responder)
  await CORE.runAudit(plan, makeDeps({ agent }))
  const verifyCalls = calls.filter((c) => c.label.startsWith('verify:'))
  assert.equal(verifyCalls.length, 12, 'three findings, four lenses each')
  for (const c of verifyCalls) {
    const lens = c.label.split(':').pop()
    const text = CORE.VERIFY_LENS_INSTRUCTIONS[lens]
    const isDocs = c.prompt.includes('"category":"documentation-')
    assert.ok(isDocs || c.prompt.includes('"category":"coupling"'), `${c.label} carries its finding`)
    if (isDocs) {
      assert.ok(c.prompt.includes(text + '\n\nDOCUMENTATION FINDING:'), `${c.label} docs prompt: the lens text, then the docs rule`)
      assert.ok(c.prompt.includes('a cut needs no proof that the text is wrong'), `${c.label} docs rule says a cut needs no proof of error`)
      assert.ok(c.prompt.includes('do not refute it because the text is true'), `${c.label} docs rule forbids refuting for accuracy`)
      assert.ok(c.prompt.includes('Refute it only when the cited text is not there'), `${c.label} docs rule names the refute conditions`)
    } else {
      assert.ok(c.prompt.includes(text + '\n\nFINDINGS:\n'), `${c.label} code prompt: the lens text unchanged, straight into the finding`)
      assert.equal(c.prompt.includes('DOCUMENTATION FINDING'), false, `${c.label} code prompt carries no docs rule`)
    }
  }
})

// ── The docs verify rule follows the finding's slice kind, and records and checked generated blocks are refutable (347bbfb3) ──
// Implementer-written; oracle = the 347bbfb3 specification: every finding from a docs slice is verified
// by the docs rule whatever its category, a code-slice finding outside the docs categories is not, and the
// rule's refute grounds name records and generated blocks a check keeps current.

test('347bbfb3 verify: a docs-slice or docs-gap finding filed under a code category gets the docs rule; a code-slice finding does not', async () => {
  const plan = CORE.normalizeArgs(noReviewers({ gaps: { enabled: true, max: 1 }, verify: { groupSize: 1 } })).plan
  const scope = scopeResult({ reviewers: [plannerSlice('d1', 'docs'), plannerSlice('c1', 'code')] })
  const critic = criticResult({ gaps: [{ label: 'gd', kind: 'docs', prompt: 'p' }] })
  const { agent, calls } = autoAgent(perSlice(kindResponder(plan, { scope, critic })))
  await CORE.runAudit(plan, makeDeps({ agent }))
  const verifyCalls = calls.filter((c) => c.label.startsWith('verify:'))
  // T8b: a group of one; the member id in the prompt (<sliceKey>-<n>) names the source slice
  const bySource = (prefix) => verifyCalls.filter((c) => findingsOfPrompt(c.prompt)[0].id.startsWith(prefix + '-'))
  assert.ok(bySource('d1').length > 0 && bySource('c1').length > 0 && bySource('gap1').length > 0, 'each slice has verifier calls')
  for (const c of bySource('d1').concat(bySource('gap1'))) {
    assert.ok(c.prompt.includes('"category":"coupling"'), `${c.label} finding keeps its code category`)
    assert.ok(c.prompt.includes('DOCUMENTATION FINDING:'), `${c.label} docs-slice finding carries the docs rule`)
  }
  for (const c of bySource('c1')) assert.equal(c.prompt.includes('DOCUMENTATION FINDING'), false, `${c.label} code-slice finding carries no docs rule`)
})

test('347bbfb3 verify: the docs rule may refute drift in a record and a generated block a check keeps current', async () => {
  const plan = CORE.normalizeArgs(noReviewers({ focus: 'docs' })).plan
  const scope = scopeResult({ reviewers: [plannerSlice('d1', 'docs')] })
  const { agent, calls } = autoAgent(kindResponder(plan, { scope }))
  await CORE.runAudit(plan, makeDeps({ agent }))
  const c = calls.find((x) => x.label.startsWith('verify:'))
  assert.ok(c, 'a verifier ran')
  assert.ok(c.prompt.includes('when the finding claims drift in a record (a changelog, release notes, an accepted decision record)'), 'records are a refute ground')
  assert.ok(c.prompt.includes('a block a tool generates from the code and a check keeps current'), 'checked generated blocks are a refute ground')
})

// ── Audit upgrade (item 463fb596, test-author seat) ──────────────────────────
// Blind test author: the oracle is the item's task-scope D1-D17 and test-plan S15-S33 (frozen before the
// implementation); signatures come from the supplied declarations block. No implementation source was
// opened. Literal tables below (D3_DEFAULTS above, FAN_OUT) are copied from D2/D3, never read from CORE.

// T8b D2: literal, in order
const FAN_OUT = ['review', 'review-candidate', 'verify-group', 'verify-group-high', 'triage', 'merge']

// A measured input of the audit/digest-v1 shape (T7 digest.mjs buildDigest); rows sit under the default reviewer
// path so a territory slice keeps them. Rebuilt per call so no test can mutate another test's input.
const HOOKS = 'claude-plugins/task-orchestrator/hooks'
function measuredFixture(overrides = {}) {
  return {
    contract: 'audit/digest-v1',
    window: { since: '2025-11-22', recent: '2026-08-22' },
    headSha: 'abc123',
    metricsPath: 'W/metrics.json',
    hotSet: [{ path: `${HOOKS}/zz.kt`, churn: 9, churnRecent: 3, complexity: 30, hotspotScore: 0.5, module: HOOKS }],
    topComplexity: [{ path: `${HOOKS}/yy.kt`, complexity: 44, module: HOOKS }],
    cycles: [[HOOKS, 'mod-bb']],
    unstableModules: [{ id: HOOKS, instability: 0.9, fanIn: 1, fanOut: 2 }],
    coupling: [],
    trend: [],
    notes: [],
    candidates: [],
    ...overrides,
  }
}

// A measured candidate (task-scope D3: candidateId, kind, subject paths, facts). Rebuilt per call.
function candidateFixture(candidateId, overrides = {}) {
  return { candidateId, kind: 'hotspot', subject: [`${HOOKS}/zz.kt`], facts: { churn: 9, note: 'fact of ' + candidateId }, ...overrides }
}
const withCandidates = (...ids) => ({ measured: measuredFixture({ candidates: ids.map((id) => candidateFixture(id)) }) })

function v2Args(overrides = {}) {
  return baseArgs({ contract: 'audit/args-v2', measured: measuredFixture(), ...overrides })
}

function synthV2(overrides = {}) {
  return {
    healthVerdict: 'ok', executiveSummary: 'summary', themes: [], strengths: ['a strength'], findingIndex: [],
    roadmap: { now: [], next: [], later: [] }, method: 'method text', notExamined: [],
    ...overrides,
  }
}

/** A responder covering every label of a v2 run. `critic`: pass null for a null agent result. Each slice key
 * gets its own finding location (src/<key>.mjs:1) unless findingFor sets locations itself, so the T8a
 * pre-clustering leaves findings of different slices apart; `locationFor(key)` overrides that location. */
function runResponder({
  scope, critic = criticResult(), sevBySlice = {}, synth = synthV2(),
  triageResults = [], verdictFor = () => verdictResult(), findingFor, nullFor = [], locationFor = null, mergeFor = null,
} = {}) {
  const DEFAULT_LOCATION = makeFinding().locations[0]
  const locate = (key, f) => {
    if (f === null) return null
    const loc = locationFor ? locationFor(key) : `src/${key}.mjs:1`
    return f.locations.length === 1 && f.locations[0] === DEFAULT_LOCATION ? { ...f, locations: [loc] } : f
  }
  const mk = (key) => locate(key, findingFor ? findingFor(key) : makeFinding({ severity: sevBySlice[key] || 'medium', rating: 'plan-it' }))
  return async (label) => {
    if (label === 'scope') return scope
    if (label === 'critic') return critic
    if (nullFor.includes(label)) return null
    if (label.startsWith('review:')) {
      const f = mk(label.slice(7))
      return findingsResult({ findings: f === null ? [] : [f] })
    }
    if (label.startsWith('gap:')) return findingsResult({ findings: [mk('gap' + label.slice(4))] })
    if (label.startsWith('candidate:')) return findingsResult({ findings: [mk(label.slice(10))] })
    if (label.startsWith('merge:')) return mergeFor ? mergeFor(label) : dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictFor(label)
    if (label === 'synthesis') return synth
    if (label.startsWith('triage:')) return triageResult({ results: triageResults })
    throw new Error('runResponder: unexpected label ' + label)
  }
}

async function fullRun(args, responderOpts = {}, { order = 'fifo' } = {}) {
  const { ok, plan, reason } = CORE.normalizeArgs(args)
  assert.equal(ok, true, `normalizeArgs: ${reason}`)
  const logLines = []
  const phaseLog = []
  const { agent, calls } = autoAgent(runResponder(responderOpts), { order })
  const result = await CORE.runAudit(plan, makeDeps({ agent, logLines, phaseLog }))
  return { plan, calls, result, logLines, phaseLog }
}

function dispatchKeyOf(call, sevBySlice) {
  const label = call.label
  if (['scope', 'critic'].includes(label)) return label
  if (label.startsWith('candidate:')) return 'review-candidate'
  if (label.startsWith('merge:')) return 'merge'
  if (label === 'synthesis') return 'synth'
  if (label === 'review:architecture') return 'review-architecture'
  if (label.startsWith('review:') || label.startsWith('gap:')) return 'review'
  if (label.startsWith('triage:')) return 'triage'
  if (label.startsWith('verify:')) {
    // T8b D9: the key follows the highest severity among the group's members, read from the group prompt
    const sevs = findingsOfPrompt(call.prompt).map((f) => f.severity)
    return sevs.includes('high') || sevs.includes('critical') ? 'verify-group-high' : 'verify-group'
  }
  throw new Error('dispatchKeyOf: unexpected label ' + label)
}

const FULL_SEV = { r1: 'critical', r2: 'low', l1: 'medium', architecture: 'high', gap1: 'medium' }

// r1 and r2 share one path at two lines, so the pre-clustering hands exactly one batch to a merge agent
function sharedLocation(key) {
  return key === 'r1' ? 'src/shared.mjs:1' : key === 'r2' ? 'src/shared.mjs:9' : `src/${key}.mjs:1`
}

function fullScopeOpts(extra = {}) {
  return {
    locationFor: sharedLocation,
    scope: scopeResult({ reviewers: [reviewerSpec('r1'), reviewerSpec('r2')], lenses: [{ key: 'l1', label: 'Lens l1', focus: 'f' }] }),
    critic: criticResult({ gaps: [{ label: 'g1', prompt: 'p1' }] }),
    sevBySlice: FULL_SEV,
    ...extra,
  }
}

function fullArgs(overrides = {}) {
  // quick preset with the gaps default (critic enabled), the Scope phase running, measured input and Triage on
  return v2Args({ gaps: undefined, scope: { paths: ['p'] }, items: { enabled: true, rootId: 'root-1' }, ...overrides })
}

// ── S15 / S16: dispatch (D3) ─────────────────────────────────────────────────

test('S15: every call carries the D3 model and effort of its dispatch key, and a [model/effort] label tag', async () => {
  const { calls } = await fullRun(fullArgs(withCandidates('c1')), fullScopeOpts())
  const seenKeys = new Set()
  for (const c of calls) {
    const key = dispatchKeyOf(c, FULL_SEV)
    seenKeys.add(key)
    const want = D3_DEFAULTS[key]
    assert.equal(c.opts.model, want.model, `${c.label} model`)
    assert.equal(c.opts.effort, want.effort, `${c.label} effort`)
    assert.equal(c.opts.label, `${c.label} [${want.model}/${want.effort}]`, `${c.label} label tag`)
  }
  assert.deepEqual([...seenKeys].sort(), Object.keys(D3_DEFAULTS).sort(), 'fixture must exercise all ten dispatch keys')
})

// E17 (A1): the phase-key -> dispatch-class map, a literal copy of A1 item 2 (class names only, no values).
// T8b D2: a literal copy, in the D2 order
const CLASS_MAP = {
  scope: 'research', review: 'audit-review', 'review-candidate': 'audit-review', 'review-architecture': 'review',
  critic: 'research', merge: 'extraction', 'verify-group': 'research', 'verify-group-high': 'audit-verify-high',
  synth: 'audit-synthesis', triage: 'extraction',
}

test('S15: DISPATCH_CLASSES and FAN_OUT_KEYS equal their literals; plan.dispatch is the dispatchClasses table and plan.models/efforts are gone', () => {
  assert.deepEqual(JSON.parse(JSON.stringify(CORE.DISPATCH_CLASSES)), CLASS_MAP)
  assert.deepEqual([...CORE.FAN_OUT_KEYS].sort(), [...FAN_OUT].sort())
  const plan = buildPlan()
  assert.deepEqual(JSON.parse(JSON.stringify(plan.dispatch)), D3_DEFAULTS)
  assert.equal(plan.models, undefined)
  assert.equal(plan.efforts, undefined)
})

test('S16: overrides reach their keys; scope, critic, review-architecture and synth accept fable; omitted keys keep the defaults', async () => {
  const models = {
    scope: 'fable', critic: 'fable', 'review-architecture': 'fable', synth: 'fable', review: 'm-review',
    'verify-group-high': 'm-vh', 'verify-group': 'm-verify', merge: 'm-merge', triage: 'm-triage',
  }
  const efforts = { review: 'e-review', 'verify-group-high': 'e-vh' } // everything else omitted
  const { calls, plan } = await fullRun(fullArgs({ models, efforts, ...withCandidates('c1') }), fullScopeOpts())
  for (const c of calls) {
    const key = dispatchKeyOf(c, FULL_SEV)
    const model = models[key] === undefined ? D3_DEFAULTS[key].model : models[key]
    const effort = efforts[key] === undefined ? D3_DEFAULTS[key].effort : efforts[key]
    assert.equal(c.opts.model, model, `${c.label} model`)
    assert.equal(c.opts.effort, effort, `${c.label} effort`)
    assert.equal(c.opts.label, `${c.label} [${model}/${effort}]`)
  }
  assert.equal('measure' in plan.dispatch, false, 'T8a: no measure dispatch key')
  assert.deepEqual(plan.dispatch.review, { model: 'm-review', effort: 'e-review' })
  assert.deepEqual(plan.dispatch['review-architecture'], { model: 'fable', effort: 'high' })
})

test('S16 / agentOpts: sets model, effort and the label suffix on a copy of the base options and never leaves a model unset', () => {
  const plan = buildPlan({ models: { review: 'm-x' }, efforts: { review: 'e-x' } })
  const opts = CORE.agentOpts(plan, 'review', { label: 'review:r1', phase: 'Review', schema: { type: 'object' } })
  assert.equal(opts.model, 'm-x')
  assert.equal(opts.effort, 'e-x')
  assert.equal(opts.label, 'review:r1 [m-x/e-x]')
  assert.equal(opts.phase, 'Review')
  assert.deepEqual(opts.schema, { type: 'object' })
  for (const key of Object.keys(D3_DEFAULTS)) {
    const o = CORE.agentOpts(buildPlan(), key, { label: 'x' })
    assert.equal(o.model, D3_DEFAULTS[key].model, `${key} default model`)
    assert.equal(o.effort, D3_DEFAULTS[key].effort, `${key} default effort`)
    assert.equal(o.label, `x [${D3_DEFAULTS[key].model}/${D3_DEFAULTS[key].effort}]`)
  }
})

// ── S17 / S25 / S26 / S38 / S40 / S45: measured input replaces the Measure phase (T8a D1-D4) ──

// A digest block is a line that starts with the heading; the reviewer preamble also names the heading in prose
// (it defines the basis words), so an inline mention is not a digest.
const hasDigest = (prompt) => /^MEASURED STRUCTURE/m.test(prompt)
const digestAt = (prompt) => prompt.search(/^MEASURED STRUCTURE/m)

const fullDigestOf = (m) => CORE.formatMetricsDigest(m, { since: m.window.since, recent: m.window.recent, metricsPath: m.metricsPath })

test('S17 / S38 / S45: measured input -> no measure call, Scope is the first phase of seven, metricsPath is measured.metricsPath, each carrier gets its own digest, no prompt names audit-metrics.mjs (reverse barrier)', async () => {
  const m = measuredFixture()
  const args = fullArgs({ date: '2026-11-22', measured: m, scope: { paths: [HOOKS] } })
  const { calls, result, phaseLog } = await fullRun(args, fullScopeOpts(), { order: 'reverse' })
  assert.equal(phaseLog[0], 'Scope')
  assert.equal(phaseLog.includes('Measure'), false)
  assert.equal(calls.some((c) => c.label === 'measure'), false)
  assert.equal(JSON.parse(JSON.stringify(loadMeta(SCRIPT_PATH))).phases.length, 7)
  assert.equal(result.metricsPath, 'W/metrics.json')
  for (const c of calls) assert.equal(c.prompt.includes('audit-metrics.mjs'), false, `${c.label} must not name the metrics script`)
  const territory = CORE.sliceDigest(m, [HOOKS])
  const byLabel = (l) => calls.find((c) => c.label === l)
  // territory reviewers get sliceDigest of their own paths; the other lens and the gap reviewer the scope paths
  for (const l of ['review:r1', 'review:r2', 'review:l1', 'gap:1']) {
    assert.ok(byLabel(l).prompt.includes(territory), `${l} prompt carries sliceDigest(m, paths)`)
  }
  // scope and the architecture lens get the full digest
  for (const l of ['scope', 'review:architecture']) assert.ok(byLabel(l).prompt.includes(fullDigestOf(m)), `${l} carries the full digest`)
  assert.ok(hasDigest(byLabel('review:r1').prompt))
  assert.ok(byLabel('review:r1').prompt.includes(`${HOOKS}/zz.kt`), 'the hot file under the slice path is named')
  for (const c of calls.filter((x) => x.label.startsWith('review:') || x.label.startsWith('gap:'))) {
    assert.ok(digestAt(c.prompt) >= 0 && digestAt(c.prompt) < c.prompt.indexOf('YOUR SLICE:'), `${c.label}: digest sits before YOUR SLICE:`)
  }
  // critic, merge, synthesis and triage carry no digest; verify carries only the file line
  for (const c of calls.filter((x) => x.label === 'critic' || x.label.startsWith('merge:') || x.label === 'synthesis' || x.label.startsWith('triage:'))) {
    assert.equal(hasDigest(c.prompt), false, `${c.label} carries no digest`)
    assert.equal(c.prompt.includes('Metrics file:'), false, `${c.label} carries no metrics file line`)
  }
  const verifies = calls.filter((c) => c.label.startsWith('verify:'))
  assert.ok(verifies.length > 0)
  for (const c of verifies) {
    assert.ok(c.prompt.includes('Metrics file: W/metrics.json'), `${c.label} names the metrics file`)
    assert.equal(hasDigest(c.prompt), false, `${c.label} carries no digest body`)
  }
})

test('S17 / S39 probe (metricsPath variants): a non-empty string is used verbatim, a backslash included; an empty or non-string metricsPath is null; no file line follows', async () => {
  const withSlash = 'D:' + String.fromCharCode(92) + 'work' + String.fromCharCode(92) + 'metrics.json'
  const ok = await fullRun(v2Args({ measured: measuredFixture({ metricsPath: withSlash }) }), {})
  assert.equal(ok.result.metricsPath, withSlash)
  for (const bad of ['', null, 5, undefined]) {
    const { result, calls } = await fullRun(v2Args({ measured: measuredFixture({ metricsPath: bad }) }), {})
    assert.equal(result.metricsPath, null, `metricsPath=${JSON.stringify(bad)}`)
    for (const c of calls) assert.equal(c.prompt.includes('Metrics file:'), false, `${c.label}: no file line`)
  }
})

test('S26 / S40: no measured input -> the absence is logged, metricsPath is null, no prompt carries a digest or a file line, Scope is the first phase', async () => {
  const { calls, result, logLines, phaseLog } = await fullRun(fullArgs({ measured: undefined }), fullScopeOpts())
  assert.ok(logLines.some((l) => l.includes('no measured input')))
  assert.equal(result.metricsPath, null)
  assert.equal(phaseLog[0], 'Scope')
  assert.ok(calls.length > 0)
  for (const c of calls) {
    assert.equal(hasDigest(c.prompt), false, `${c.label} must carry no digest`)
    assert.equal(c.prompt.includes('Metrics file:'), false, `${c.label} must carry no metrics file line`)
  }
})

test('S25 / S40: measured input with metricsPath null -> the digest reaches the reviewers, no prompt carries a file line, metricsPath is null, the absence is not logged', async () => {
  const { calls, result, logLines } = await fullRun(v2Args({ measured: measuredFixture({ metricsPath: null }) }), {})
  assert.equal(result.metricsPath, null)
  assert.equal(result.started, true)
  assert.equal(logLines.some((l) => l.includes('no measured input')), false)
  assert.ok(calls.some((c) => c.label.startsWith('review:') && hasDigest(c.prompt)))
  for (const c of calls) assert.equal(c.prompt.includes('Metrics file:'), false, `${c.label} must carry no metrics file line`)
})

test('S25 / D13: a null scope result with a measured input still reports metricsPath and a null report', async () => {
  const { result } = await fullRun(fullArgs(), { scope: null })
  assert.equal(result.metricsPath, 'W/metrics.json')
  assert.equal(result.report, null)
  assert.deepEqual(result.kept, [])
  assert.ok(Object.keys(result).includes('metricsPath'))
  assert.equal(result.stats.mergeAgents, 0, 'the null-scope return counts no merge agents')
})

// ── S39: normalizeArgs and measured (D1) ─────────────────────────────────────

const MEASURED_REASON = 'invalid args: measured must be an audit/digest-v1 object'

function normalizeMeasuredReason(o) {
  const r = CORE.normalizeArgs(baseArgs(o))
  assert.equal(r.ok, false)
  return r.reason
}

test('S39: measured must be an audit/digest-v1 object: a string, number, array or another contract is refused with the frozen reason', () => {
  for (const bad of ['x', 5, true, [], [measuredFixture()], {}, { contract: 'audit/digest-v0' }, measuredFixture({ contract: 'audit/digest-v2' })]) {
    const r = CORE.normalizeArgs(baseArgs({ measured: bad }))
    assert.equal(r.ok, false, `expected a refusal for ${JSON.stringify(bad)}`)
    assert.equal(r.reason, MEASURED_REASON)
  }
})

test('S39: measured absent or null -> plan.measured null; a digest-v1 object is accepted and kept on the plan, inner lists unchecked', () => {
  const absent = CORE.normalizeArgs(baseArgs())
  const nul = CORE.normalizeArgs(baseArgs({ measured: null }))
  assert.equal(absent.ok, true)
  assert.equal(absent.plan.measured, null)
  assert.equal(nul.ok, true)
  assert.equal(nul.plan.measured, null)
  const m = measuredFixture()
  const given = CORE.normalizeArgs(baseArgs({ measured: m }))
  assert.equal(given.ok, true, given.reason)
  assert.deepEqual(JSON.parse(JSON.stringify(given.plan.measured)), m)
  const bare = CORE.normalizeArgs(baseArgs({ measured: { contract: 'audit/digest-v1' } }))
  assert.equal(bare.ok, true, 'a digest with no lists is valid: a missing list reads empty')
})

test('S39 / probe (order): the measured check comes after the items.policy check and before the dispatchClasses check; a JSON-string args form is checked too', () => {
  const policyBad = { enabled: true, rootId: 'r', policy: 'x' }
  assert.equal(normalizeMeasuredReason({ measured: 'x', items: policyBad }), 'invalid args: items.policy must be gated, bugs or all')
  assert.equal(normalizeMeasuredReason({ measured: 'x', dispatchClasses: undefined }), MEASURED_REASON)
  const asString = CORE.normalizeArgs(JSON.stringify(baseArgs({ measured: 'x' })))
  assert.equal(asString.reason, MEASURED_REASON)
  const asStringOk = CORE.normalizeArgs(JSON.stringify(baseArgs({ measured: measuredFixture() })))
  assert.equal(asStringOk.ok, true)
})

test('S39 / probe (entry sequence): a refused measured stops the run before any agent runs', async () => {
  const script = loadScript(SCRIPT_PATH)
  let agentCalls = 0
  const result = await script.run({
    agent: async () => { agentCalls += 1; return null },
    parallel: fakeParallel,
    pipeline: fakePipeline,
    phase: () => {},
    log: () => {},
    args: baseArgs({ contract: 'audit/args-v2', measured: 'x' }),
  })
  assert.deepEqual(JSON.parse(JSON.stringify(result)), { started: false, reason: MEASURED_REASON })
  assert.equal(agentCalls, 0)
})

// ── S18 / S29: the architecture lens and the lens budget (D6, D7) ────────────

test('S18: with given reviewers, the architecture lens runs under the D7 terms for focus all and code, with its own dispatch key', async () => {
  for (const focus of ['all', 'code']) {
    const { calls } = await fullRun(baseArgs({ focus }), {})
    const arch = calls.filter((c) => c.label === 'review:architecture')
    assert.equal(arch.length, 1, `focus ${focus}: exactly one architecture review`)
    assert.match(arch[0].prompt, /intended architecture/i)
    assert.match(arch[0].prompt, /pattern inventory/i)
    assert.equal(arch[0].opts.model, 'opus')
    assert.equal(arch[0].opts.effort, 'high')
    assert.deepEqual(calls.filter((c) => c.label.startsWith('review:')).map((c) => c.label).sort(), ['review:architecture', 'review:r1', 'review:r2'])
  }
  assert.equal(CORE.ARCHITECTURE_LENS.key, 'architecture')
  assert.equal(CORE.ARCHITECTURE_LENS.label, 'Architecture')
  assert.equal(CORE.ARCHITECTURE_LENS.kind, 'code')
  assert.match(CORE.ARCHITECTURE_LENS.focus, /intended architecture/i)
  assert.match(CORE.ARCHITECTURE_LENS.focus, /pattern inventory/i)
  assert.equal(CORE.ARCHITECTURE_LENS.focus.includes('task-orchestrator:'), false, 'no skill name in the lens focus')
})

test('S18: a scope-path quick run keeps proposed reviewers then lenses up to B = reviewerCount - 1 and logs the dropped keys; caller-given slices stay uncapped', async () => {
  const proposed = scopeResult({
    reviewers: [reviewerSpec('r1'), reviewerSpec('r2'), reviewerSpec('r3')],
    lenses: [{ key: 'lensdrop1', label: 'Lens one', focus: 'f' }, { key: 'lensdrop2', label: 'Lens two', focus: 'f' }],
  })
  const { calls, logLines } = await fullRun(baseArgs({ scope: { paths: ['p'] } }), { scope: proposed })
  assert.deepEqual(calls.filter((c) => c.label.startsWith('review:')).map((c) => c.label).sort(), ['review:architecture', 'review:r1', 'review:r2', 'review:r3'])
  assert.ok(logLines.some((l) => l.includes('lensdrop1')), 'the first dropped lens is logged')
  assert.ok(logLines.some((l) => l.includes('lensdrop2')), 'the second dropped lens is logged')
  // caller-given reviewers and lenses are never capped
  const many = baseArgs({
    scope: { paths: ['p'], reviewers: [1, 2, 3, 4, 5, 6].map((i) => reviewerSpec('g' + i)), lenses: [{ key: 'lx', label: 'lx', focus: 'f' }] },
  })
  const given = await fullRun(many, {})
  assert.equal(given.calls.filter((c) => c.label.startsWith('review:')).length, 8) // 6 + 1 lens + architecture
})

test('S29: focus docs adds no architecture lens; a caller lens keyed architecture is used as given and not doubled', async () => {
  const docs = await fullRun(baseArgs({ focus: 'docs', scope: { paths: ['p'], reviewers: [reviewerSpec('d1', { kind: 'docs' })], lenses: [] } }), {})
  assert.deepEqual(docs.calls.filter((c) => c.label.startsWith('review:')).map((c) => c.label), ['review:d1'])
  const own = { key: 'architecture', label: 'My architecture', focus: 'caller focus text qq7' }
  const args = baseArgs({ scope: { paths: ['p'], reviewers: [reviewerSpec('r1')], lenses: [own] } })
  const { calls, plan } = await fullRun(args, {})
  const arch = calls.filter((c) => c.label === 'review:architecture')
  assert.equal(arch.length, 1, 'not doubled')
  assert.ok(arch[0].prompt.includes('caller focus text qq7'), 'the caller lens is used as given')
  assert.equal(/pattern inventory/i.test(arch[0].prompt), false, 'the default focus is not substituted')
  assert.equal(arch[0].opts.model, 'opus', 'it still dispatches under review-architecture')
  assert.equal(CORE.projectAgents(plan).review, 2, 'no extra lens is projected')
})

test('S18 / D6: the scope prompt asks for slices at the cost of change, not a defect hunt, and states the budget B', async () => {
  for (const [focus, b] of [['all', 3], ['docs', 4]]) {
    const a = baseArgs({ focus, scope: { paths: ['p'] } })
    const { calls } = await fullRun(a, { scope: scopeResult({ reviewers: [plannerSlice('x1', focus === 'docs' ? 'docs' : 'code')] }) })
    const p = calls.find((c) => c.label === 'scope').prompt
    assert.ok(p.includes('costs the most to change'))
    assert.ok(p.includes('not as a defect hunt'))
    assert.match(p, new RegExp(`\\b${b}\\b`), `focus ${focus}: the prompt states ${b} slices and lenses in total`)
  }
})

// ── S31: test code in the scope (D6) ─────────────────────────────────────────

test('S31: testPathsOf keeps paths with a test, tests, __tests__, spec or specs segment (any case, either slash) and normalizes backslashes', () => {
  assert.deepEqual(
    CORE.testPathsOf(['src/test/kotlin', 'scripts\\tests', 'Tests', 'contest', 'src/testing', 'a/__tests__/b', 'x/Specs/y', 'lib/spec', 'latest/x', 'a/test.kt', 'src/main']),
    ['src/test/kotlin', 'scripts/tests', 'Tests', 'a/__tests__/b', 'x/Specs/y', 'lib/spec']
  )
  assert.deepEqual(CORE.testPathsOf([]), [])
  assert.deepEqual(CORE.testPathsOf(['contest', 'src/testing']), [])
})

test('S31: the scope prompt names TEST CODE and the test paths only when the scope has test paths', async () => {
  const withTests = baseArgs({ scope: { paths: ['src/main', 'src\\test\\kotlin'] } })
  const a = await fullRun(withTests, { scope: scopeResult({ reviewers: [reviewerSpec('r1')] }) })
  const p = a.calls.find((c) => c.label === 'scope').prompt
  assert.ok(p.includes('TEST CODE'))
  assert.ok(p.includes('src/test/kotlin'))
  assert.match(p, /test quality/i)
  const without = baseArgs({ scope: { paths: ['src/main', 'src/testing'] } })
  const b = await fullRun(without, { scope: scopeResult({ reviewers: [reviewerSpec('r1')] }) })
  assert.equal(b.calls.find((c) => c.label === 'scope').prompt.includes('TEST CODE'), false)
})

// ── S30 / S32: the metrics helpers ───────────────────────────────────────────

test('S30: the digest windows are measured.window, not a date computed from the run date', async () => {
  const m = measuredFixture({ window: { since: '2020-01-02', recent: '2021-03-04' } })
  const { calls } = await fullRun(fullArgs({ date: '2026-05-31', measured: m }), fullScopeOpts())
  const carriers = calls.filter((x) => x.label.startsWith('review:'))
  assert.ok(carriers.length >= 4)
  for (const c of carriers) {
    assert.ok(c.prompt.includes('long since 2020-01-02, recent since 2021-03-04'), `${c.label} windows come from measured.window`)
    assert.equal(c.prompt.includes('2025-05-31'), false, `${c.label}: no window computed from the run date`)
    assert.equal(c.prompt.includes('2026-02-28'), false, `${c.label}: no window computed from the run date`)
  }
})

test('S32: formatMetricsDigest of a digest-v1 object starts MEASURED STRUCTURE and caps at 15 hot, 10 complex, 10 cycles and 10 unstable modules', () => {
  const pad = (i) => String(i).padStart(2, '0')
  const m = measuredFixture({
    hotSet: Array.from({ length: 20 }, (_, i) => ({ path: `hot-${pad(i + 1)}`, churn: 1, complexity: 1 })),
    topComplexity: Array.from({ length: 12 }, (_, i) => ({ path: `tc-${pad(i + 1)}`, complexity: 5 })),
    cycles: Array.from({ length: 12 }, (_, i) => [`cyc-${pad(i + 1)}-a`, `cyc-${pad(i + 1)}-b`]),
    unstableModules: Array.from({ length: 12 }, (_, i) => ({ id: `unst-${pad(i + 1)}`, instability: 0.5 })),
    notes: [],
  })
  const d = CORE.formatMetricsDigest(m)
  assert.ok(d.startsWith('MEASURED STRUCTURE'))
  for (let i = 1; i <= 15; i++) assert.ok(d.includes(`hot-${pad(i)}`), `hot ${i}`)
  for (let i = 16; i <= 20; i++) assert.equal(d.includes(`hot-${pad(i)}`), false, `hot ${i} is past the cap`)
  for (let i = 1; i <= 10; i++) {
    assert.ok(d.includes(`tc-${pad(i)}`), `complexity ${i}`)
    assert.ok(d.includes(`cyc-${pad(i)}-a -> cyc-${pad(i)}-b`), `cycle ${i}`)
    assert.ok(d.includes(`unst-${pad(i)}`), `unstable ${i}`)
  }
  for (let i = 11; i <= 12; i++) {
    assert.equal(d.includes(`tc-${pad(i)}`), false, `complexity ${i} is past the cap`)
    assert.equal(d.includes(`cyc-${pad(i)}-a`), false, `cycle ${i} is past the cap`)
    assert.equal(d.includes(`unst-${pad(i)}`), false, `unstable ${i} is past the cap`)
  }
  const empty = CORE.formatMetricsDigest({ hotSet: [], topComplexity: [], cycles: [], unstableModules: [], notes: [] })
  assert.ok(empty.startsWith('MEASURED STRUCTURE'))
})

// ── S19: the stratified verify cap (D11) ─────────────────────────────────────

function capItem(id, category, rating, severity) {
  return { id, category, rating, severity }
}

test('S19: capVerification spreads over category x rating groups, ordered by the highest member severity, one finding per group per round', () => {
  const merged = [
    capItem('a', 'coupling', 'act-now', 'medium'),
    capItem('b', 'coupling', 'act-now', 'high'),
    capItem('c', 'security', 'watch', 'low'),
    capItem('d', 'coupling', 'act-now', 'low'),
    capItem('e', 'security', 'watch', 'critical'),
    capItem('f', 'layering', 'plan-it', 'medium'),
  ]
  const { verify, unverified } = CORE.capVerification(merged, 4)
  assert.deepEqual(verify.map((f) => f.id), ['e', 'b', 'f', 'c'])
  assert.deepEqual(unverified.map((f) => f.id), ['a', 'd'])
})

test('S19 / probe (cap 0 and cap >= n): cap 0 verifies nothing; a larger cap verifies all in selection order with an empty tail', () => {
  const merged = [
    capItem('a', 'coupling', 'act-now', 'medium'),
    capItem('b', 'coupling', 'act-now', 'high'),
    capItem('c', 'security', 'watch', 'low'),
    capItem('d', 'coupling', 'act-now', 'low'),
    capItem('e', 'security', 'watch', 'critical'),
    capItem('f', 'layering', 'plan-it', 'medium'),
  ]
  const none = CORE.capVerification(merged, 0)
  assert.deepEqual(none.verify, [])
  assert.deepEqual(none.unverified.map((f) => f.id), ['a', 'b', 'c', 'd', 'e', 'f'], 'the unverified rest keeps input order')
  const all = CORE.capVerification(merged, 10)
  assert.deepEqual(all.verify.map((f) => f.id), ['e', 'b', 'f', 'c', 'a', 'd'])
  assert.deepEqual(all.unverified, [])
})

test('S19: a finding without a rating groups with watch; group ties break by the first member input index, not the strongest member', () => {
  // no rating == watch: x and y share one group (highest member: critical), so y leads and x follows
  const a = CORE.capVerification(
    [capItem('x', 'security', 'watch', 'high'), { id: 'y', category: 'security', severity: 'critical' }, capItem('z', 'layering', 'plan-it', 'high')],
    2
  )
  assert.deepEqual(a.verify.map((f) => f.id), ['y', 'z'])
  assert.deepEqual(a.unverified.map((f) => f.id), ['x'])
  // tie on the highest severity (high): the group whose first member comes first leads
  const b = CORE.capVerification(
    [capItem('p', 'cat1', 'plan-it', 'low'), capItem('q', 'cat2', 'plan-it', 'high'), capItem('r', 'cat1', 'plan-it', 'high')],
    3
  )
  assert.deepEqual(b.verify.map((f) => f.id), ['r', 'q', 'p'])
})

// ── S20: selectedBy and the item policy (D16) ────────────────────────────────

function policyCases() {
  return [
    ['f-1', { category: 'security-architecture', severity: 'high', verdict: 'CONFIRMED' }, 'none', { gated: true, bugs: true, all: true }, { gated: 'ok', bugs: 'ok', all: 'ok' }],
    ['f-2', { category: 'concurrency', severity: 'low', verdict: 'UNVERIFIED' }, 'none', { gated: false, bugs: true, all: true }, { gated: 'severity below high', bugs: 'ok', all: 'ok' }],
    ['f-3', { category: 'coupling', severity: 'high', verdict: 'PLAUSIBLE' }, 'none', { gated: true, bugs: false, all: true }, { gated: 'ok', bugs: 'class tech-debt', all: 'ok' }],
    ['f-4', { category: 'transactions', severity: 'high', verdict: 'CONFIRMED', trackedId: 'TRACK-9' }, 'none', { gated: false, bugs: false, all: false }, { gated: 'tracked TRACK-9', bugs: 'tracked TRACK-9', all: 'tracked TRACK-9' }],
    ['f-5', { category: 'data-model', severity: 'high', verdict: 'CONFIRMED' }, 'strong', { gated: false, bugs: false, all: false }, { gated: 'likely duplicate strong', bugs: 'likely duplicate strong', all: 'likely duplicate strong' }],
    ['f-6', { category: 'error-handling', severity: 'high', verdict: 'CONFIRMED' }, 'unknown', { gated: false, bugs: true, all: true }, { gated: 'likely duplicate unknown', bugs: 'ok', all: 'ok' }],
    ['f-7', { category: 'dead-code', severity: 'high', verdict: 'CONFIRMED', locations: ['obs/zone/x.mjs:1'] }, 'none', { gated: true, bugs: false, all: false }, { gated: 'ok', bugs: 'class observation', all: 'observation' }],
    ['f-8', { category: 'concurrency', severity: 'high', verdict: 'UNVERIFIED', trackedId: 'TRACK-1' }, 'none', { gated: false, bugs: false, all: false }, { gated: null, bugs: 'tracked TRACK-1', all: 'tracked TRACK-1' }],
    // f-8 gated reason is not asserted: which verdicts the gated rule refuses before the tracked check is "today's" rule, not in D16
  ]
}

function policyProposal(policy) {
  const items = { enabled: true, rootId: 'root-test-0001', materializeMin: 'high' }
  if (policy !== undefined) items.policy = policy
  const plan = proposalPlan({ items, observationPaths: ['obs/zone'] })
  const cases = policyCases()
  const kept = cases.map(([id, o]) => keptFinding({ findingId: id, ...o }))
  const triage = Object.fromEntries(cases.map(([id, , dup]) => [id, { candidates: [], likelyDuplicate: dup }]))
  return { plan, proposal: CORE.buildProposal(kept, triage, plan) }
}

test('S20: every proposal row carries severity, verdict and the selectedBy matrix', () => {
  const { proposal } = policyProposal('gated')
  const byId = Object.fromEntries(proposal.items.map((i) => [i.findingId, i]))
  for (const [id, o, , sel] of policyCases()) {
    const row = byId[id]
    assert.deepEqual(row.selectedBy, sel, `${id} selectedBy`)
    assert.equal(row.severity, o.severity)
    assert.equal(row.verdict, o.verdict)
  }
})

test('S20: materialize and reason follow items.policy (gated, bugs, all); an absent policy is gated', () => {
  for (const policy of ['gated', 'bugs', 'all', undefined]) {
    const effective = policy === undefined ? 'gated' : policy
    const { plan, proposal } = policyProposal(policy)
    assert.equal(plan.items.policy, effective)
    const byId = Object.fromEntries(proposal.items.map((i) => [i.findingId, i]))
    for (const [id, , , sel, reasons] of policyCases()) {
      assert.equal(byId[id].materialize, sel[effective], `${effective} ${id} materialize`)
      if (reasons[effective] !== null) assert.equal(byId[id].reason, reasons[effective], `${effective} ${id} reason`)
    }
  }
})

test('S20: batches follow the policy, and an observation never enters a batch', () => {
  const want = { gated: ['f-1', 'f-3'], bugs: ['f-1', 'f-2', 'f-6'], all: ['f-1', 'f-2', 'f-3', 'f-6'] }
  for (const policy of ['gated', 'bugs', 'all']) {
    const { proposal } = policyProposal(policy)
    assert.deepEqual(proposal.batches.flatMap((b) => b.childRefs).sort(), want[policy], `${policy} batches`)
    assert.deepEqual(proposal.observations, ['f-7'])
  }
})

test('S20: the unverified tag is added when the verdict is UNVERIFIED and any selectedBy is true, whatever the policy', () => {
  for (const policy of ['gated', 'bugs', 'all']) {
    const { plan, proposal } = policyProposal(policy)
    const byId = Object.fromEntries(proposal.items.map((i) => [i.findingId, i]))
    assert.equal(byId['f-2'].tags, `audit,concurrency,audit-${plan.runId},unverified`, `${policy}: UNVERIFIED with a true selectedBy`)
    assert.equal(byId['f-8'].tags, `audit,concurrency,audit-${plan.runId}`, `${policy}: UNVERIFIED but tracked (all false)`)
    assert.equal(byId['f-1'].tags, `audit,security-architecture,audit-${plan.runId}`, `${policy}: CONFIRMED is never tagged`)
    assert.equal(byId['f-3'].tags, `audit,coupling,audit-${plan.runId}`, `${policy}: PLAUSIBLE is never tagged`)
  }
})

// ── S22 / S23 / S28: argument validation (D1, D2) ────────────────────────────

test('S22: a fable model under any fan-out key is refused, in any casing or embedding; single-agent keys accept it', () => {
  for (const key of FAN_OUT) {
    for (const value of ['fable', 'claude-fable-x', 'Fable', 'FABLE', 'my-fable-1']) {
      const { ok, reason } = CORE.normalizeArgs(baseArgs({ models: { [key]: value } }))
      assert.equal(ok, false, `${key}=${value} must be refused`)
      assert.equal(reason, `invalid args: fable is not allowed for fan-out phase ${key}`)
    }
  }
  for (const key of ['scope', 'critic', 'review-architecture', 'synth']) {
    assert.equal(CORE.normalizeArgs(baseArgs({ models: { [key]: 'fable' } })).ok, true, `${key} accepts fable`)
  }
  assert.equal(CORE.normalizeArgs(baseArgs({ models: { review: 'opus', 'verify-group': 'haiku' } })).ok, true)
  // the check does not stop at the first fan-out key it looks at
  assert.equal(CORE.normalizeArgs(baseArgs({ models: { scope: 'fable', review: 'ok', 'verify-group': 'fable' } })).reason, 'invalid args: fable is not allowed for fan-out phase verify-group')
})

test('S23: dispatch overrides, scriptsDir, workDir and policy fail with the D2 reasons', () => {
  const reason = (o) => {
    const r = CORE.normalizeArgs(baseArgs(o))
    assert.equal(r.ok, false, `expected a refusal for ${JSON.stringify(o)}`)
    return r.reason
  }
  assert.equal(reason({ models: { bogus: 'x' } }), 'invalid args: unknown dispatch key models.bogus')
  assert.equal(reason({ efforts: { bogus: 'x' } }), 'invalid args: unknown dispatch key efforts.bogus')
  assert.equal(reason({ models: { synthesis: 'x' } }), 'invalid args: unknown dispatch key models.synthesis')
  for (const bad of ['', 3, true, {}]) {
    assert.equal(reason({ models: { review: bad } }), 'invalid args: models.review must be a non-empty string')
    assert.equal(reason({ efforts: { review: bad } }), 'invalid args: efforts.review must be a non-empty string')
    assert.equal(reason({ scriptsDir: bad, workDir: 'W' }), 'invalid args: scriptsDir must be a non-empty string')
    assert.equal(reason({ workDir: bad }), 'invalid args: workDir must be a non-empty string')
  }
  assert.equal(reason({ scriptsDir: 'S' }), 'invalid args: workDir required when scriptsDir is set')
  for (const field of ['models', 'efforts']) {
    for (const bad of [[], ['x'], 'str', 3, true]) {
      assert.equal(reason({ [field]: bad }), `invalid args: ${field} must be an object`, `${field}=${JSON.stringify(bad)}`)
    }
    assert.equal(CORE.normalizeArgs(baseArgs({ [field]: null })).ok, true, `${field}: null stays allowed`)
    assert.equal(CORE.normalizeArgs(baseArgs()).ok, true, `${field} absent stays allowed`)
  }
  for (const bad of ['sometimes', 'GATED', 3]) {
    assert.equal(reason({ items: { enabled: true, rootId: 'r', policy: bad } }), 'invalid args: items.policy must be gated, bugs or all')
  }
  assert.equal(reason({ contract: 'audit/args-v3' }), 'invalid args: contract must be audit/args-v2')
  assert.equal(CORE.normalizeArgs(baseArgs({ workDir: 'W' })).ok, true, 'workDir alone is fine')
})

test('S23 / probe (order): the D2 checks run after the focus checks and in the order scriptsDir, workDir, policy, models, efforts', () => {
  const reason = (o) => CORE.normalizeArgs(baseArgs(o)).reason
  const policyBad = { enabled: true, rootId: 'r', policy: 'x' }
  assert.equal(reason({ focus: 'both', items: policyBad }), 'invalid args: focus must be code, docs or all')
  assert.equal(reason({ scriptsDir: 3, workDir: 4, items: policyBad, models: { bogus: 'x' } }), 'invalid args: scriptsDir must be a non-empty string')
  assert.equal(reason({ scriptsDir: 'S', items: policyBad, models: { bogus: 'x' } }), 'invalid args: workDir required when scriptsDir is set')
  assert.equal(reason({ items: policyBad, models: { bogus: 'x' } }), 'invalid args: items.policy must be gated, bugs or all')
  assert.equal(reason({ models: { bogus: 'x' }, efforts: { bogus: 'x' } }), 'invalid args: unknown dispatch key models.bogus')
  assert.equal(reason({ models: { review: 3 }, efforts: { bogus: 'x' } }), 'invalid args: models.review must be a non-empty string')
  // keys are checked in object order: an unknown key before a non-string value, then the reverse
  assert.equal(reason({ models: { bogus: 'x', review: 3 } }), 'invalid args: unknown dispatch key models.bogus')
  assert.equal(reason({ models: { review: 3, bogus: 'x' } }), 'invalid args: models.review must be a non-empty string')
})

test('S28: audit/args-v1 is refused even with a complete v2 object; plan.contract is audit/args-v2', () => {
  const v1 = CORE.normalizeArgs(baseArgs({ contract: 'audit/args-v1' }))
  assert.equal(v1.ok, false)
  assert.equal(v1.reason, 'invalid args: contract must be audit/args-v2')
  const v2 = CORE.normalizeArgs(baseArgs({ contract: 'audit/args-v2' }))
  assert.equal(v2.ok, true)
  assert.equal(v2.plan.contract, 'audit/args-v2')
})

test('S28: items.enabled defaults from rootId, an explicit false stays false, the policy defaults to gated', () => {
  const alone = CORE.normalizeArgs(baseArgs({ items: { rootId: 'root-1' } })).plan
  assert.equal(alone.items.enabled, true)
  assert.equal(alone.items.policy, 'gated')
  const off = CORE.normalizeArgs(baseArgs({ items: { enabled: false, rootId: 'root-1' } })).plan
  assert.equal(off.items.enabled, false)
  const none = CORE.normalizeArgs(baseArgs()).plan
  assert.equal(!!(none.items && none.items.enabled), false)
  for (const p of ['gated', 'bugs', 'all']) {
    assert.equal(CORE.normalizeArgs(baseArgs({ items: { rootId: 'r', policy: p } })).plan.items.policy, p)
  }
  assert.deepEqual([...CORE.ITEM_POLICIES], ['gated', 'bugs', 'all'])
  assert.deepEqual([...CORE.RATINGS], ['act-now', 'plan-it', 'watch'])
})

test('S28: the triage prompt names every kept finding, the ones capped out of verification included', async () => {
  const reviewers = [
    reviewerSpec('ra'), reviewerSpec('rb'), reviewerSpec('rc'),
  ]
  const sev = { ra: 'critical', rb: 'high', rc: 'low' }
  const cat = { ra: 'coupling', rb: 'layering', rc: 'cohesion' }
  const args = baseArgs({
    scope: { paths: ['p'], reviewers, lenses: [] },
    verify: { highLenses: ['combined'], defaultLenses: ['combined'], maxFindings: 2 },
    items: { enabled: true, rootId: 'root-1' },
  })
  const { calls, result } = await fullRun(args, {
    sevBySlice: sev,
    findingFor: (key) => (key === 'architecture' ? null : makeFinding({ severity: sev[key], category: cat[key], rating: 'plan-it' })),
  })
  assert.equal(result.stats.unverifiedByCap, 1)
  const triage = calls.filter((c) => c.label.startsWith('triage:'))
  assert.equal(triage.length, 1)
  for (const id of ['ra-1', 'rb-1', 'rc-1']) assert.ok(triage[0].prompt.includes(id), `triage names ${id}`)
})

// ── S24: the critic state (D12) ──────────────────────────────────────────────

test('S24: criticState is disabled unless gaps are enabled with max > 0, no-result for a null critic, else ran', () => {
  const plan = (gaps) => buildPlan({ gaps })
  const some = criticResult({ assessment: 'a', gaps: [] })
  assert.equal(CORE.criticState(plan({ enabled: true, max: 1 }), null), 'no-result')
  assert.equal(CORE.criticState(plan({ enabled: true, max: 1 }), some), 'ran')
  assert.equal(CORE.criticState(plan({ enabled: false, max: 0 }), null), 'disabled')
  assert.equal(CORE.criticState(plan({ enabled: false, max: 2 }), some), 'disabled')
  assert.equal(CORE.criticState(plan({ enabled: true, max: 0 }), null), 'disabled')
  assert.equal(CORE.criticState(plan({ enabled: true, max: 0 }), some), 'disabled')
})

test('S24: coverage.critic and the synthesis prompt agree - no-result, disabled, ran', async () => {
  const noResult = await fullRun(baseArgs({ gaps: undefined }), { critic: null })
  assert.equal(noResult.result.report.coverage.critic, 'no-result')
  const p1 = noResult.calls.find((c) => c.label === 'synthesis').prompt
  assert.ok(p1.includes('GAP CRITIC: enabled, but it returned no result.'))
  assert.ok(p1.includes('returned no result'))

  const off = await fullRun(baseArgs(), {})
  assert.equal(off.calls.some((c) => c.label === 'critic'), false)
  assert.equal(off.result.report.coverage.critic, 'disabled')
  const p2 = off.calls.find((c) => c.label === 'synthesis').prompt
  assert.ok(p2.includes('GAP CRITIC: disabled for this run.'))
  assert.equal(p2.includes('no result'), false, 'a disabled critic is not reported as a missing result')

  const ran = await fullRun(baseArgs({ gaps: undefined }), { critic: criticResult({ assessment: 'assessment-text-xyz', gaps: [] }) })
  assert.equal(ran.result.report.coverage.critic, 'ran')
  const p3 = ran.calls.find((c) => c.label === 'synthesis').prompt
  assert.ok(p3.includes('GAP CRITIC ASSESSMENT:'))
  assert.ok(p3.includes('assessment-text-xyz'))
})

// ── S21 / S27: kept rows, coverage and the verification split (D13, D14) ─────

test('S21: kept rows carry the D14 fields with the verifier correction appended; the report carries coverage in run order', async () => {
  const merged = makeFinding({ severity: 'high', rating: 'act-now', confidence: 'medium' })
  const { result, plan, calls } = await fullRun(
    v2Args({ gaps: undefined, scope: { paths: ['p'], reviewers: [reviewerSpec('r1'), reviewerSpec('r2')], lenses: [] }, items: { enabled: true, rootId: 'root-1' } }),
    {
      critic: criticResult({ gaps: [{ label: 'g1', prompt: 'p1' }, { label: 'g2', prompt: 'p2' }] }),
      nullFor: ['review:r1'],
      findingFor: (key) => (key === 'r1' ? null : key === 'r2' ? merged : makeFinding({ severity: 'medium', rating: 'watch' })),
      verdictFor: (label) => (label.startsWith('verify:r2-1') ? verdictResult({ evidenceAccurate: false, correctedEvidence: 'fix1' }) : verdictResult()),
      synth: synthV2({ notExamined: ['synth-note'], findingIndex: [{ reportId: 'AR-01', findingId: 'r2-1', title: 't', severity: 'high', effort: 'M', verdict: 'CONFIRMED', alreadyTrackedId: '' }] }),
    }
  )
  const row = result.kept.find((k) => k.findingId === 'r2-1')
  assert.ok(row, 'r2-1 is kept')
  assert.deepEqual(
    Object.keys(row).sort(),
    ['basis', 'category', 'confidence', 'effort', 'evidence', 'findingId', 'impact', 'locations', 'rating', 'recommendation', 'reportId', 'severity', 'sliceKey', 'stableId', 'title', 'trackedId', 'verdict', 'verifiedBy'],
    'a CONFIRMED judgement row has no unverifiedReason and no measuredClaim'
  )
  assert.equal(row.basis, 'judgement')
  assert.equal(row.verifiedBy, 'agents')
  assert.equal(row.stableId, stableIdOf('coupling', 'src/r2.mjs'))
  assert.equal(row.evidence, 'evidence text\n\nVerifier correction: [combined] fix1')
  assert.equal(row.impact, 'impact text')
  assert.equal(row.recommendation, 'fix it')
  assert.equal(row.confidence, 'medium')
  assert.equal(row.rating, 'act-now')
  assert.equal(row.sliceKey, 'r2')
  assert.equal(row.reportId, 'AR-01')
  const arch = result.kept.find((k) => k.findingId === 'architecture-1')
  assert.equal(arch.evidence, 'evidence text', 'no correction -> no suffix')
  assert.equal(arch.sliceKey, 'architecture')
  assert.equal(arch.rating, 'watch')

  const report = result.report
  assert.deepEqual(
    Object.keys(report).sort(),
    ['coverage', 'executiveSummary', 'healthVerdict', 'method', 'reportPath', 'roadmap', 'strengths', 'themes']
  )
  assert.equal(report.reportPath, plan.reportPath)
  assert.equal(report.method, 'method text')
  assert.deepEqual(report.strengths, ['a strength'])
  // the gap's label text is not frozen by D13 (only that it is a returned gap, in run order): key and kind are
  assert.deepEqual(report.coverage.reviewers.map((r) => [r.key, r.kind]), [['r2', 'code'], ['architecture', 'code'], ['gap1', 'code']])
  assert.deepEqual(report.coverage.reviewers.slice(0, 2).map((r) => r.label), ['Reviewer r2', 'Architecture'])
  assert.ok(report.coverage.reviewers[2].label.includes('g1'))
  assert.deepEqual(report.coverage.notExamined, ['slice r1: no result', 'gap not run: g2', 'synth-note'])
  assert.equal(report.coverage.critic, 'ran')
  assert.deepEqual(report.coverage.verification, { confirmed: 3, refuted: 0, unverifiedByCap: 0, unverifiedNoVerifier: 0 })
  assert.equal(result.stats.projectedAgents, CORE.projectAgents(plan).total)
  assert.ok(calls.length > 0)
})

test('S21 / D11: a quick run with items and no measured candidates projects 17 agents (scope 1, review 4, candidates 0, gaps 2, merge 1, verify 4 groups, synth 1, triage 4) and no measure term', () => {
  const plan = CORE.normalizeArgs(fullArgs()).plan
  const p = CORE.projectAgents(plan)
  assert.deepEqual(
    { total: p.total, scope: p.scope, review: p.review, candidates: p.candidates, gaps: p.gaps, merge: p.merge, verify: p.verify, synth: p.synth, triage: p.triage },
    // T8b D15 by hand: est = (4 + 1 + 0) * 8 = 40, nV = min(40, 20) = 20, verify = ceil(20 / 5) * 1 = 4
    { total: 17, scope: 1, review: 4, candidates: 0, gaps: 2, merge: 1, verify: 4, synth: 1, triage: 4 }
  )
  assert.equal('measure' in p, false, 'projectAgents has no measure key')
  assert.equal(CORE.PRESETS.quick.gaps.enabled, true)
  assert.equal(CORE.PRESETS.quick.gaps.max, 1)
})

test('S27: one finding each confirmed, refuted, capped and all-null -> the verification split, the unverified reasons and the refuted list', async () => {
  const sev = { ra: 'critical', rb: 'high', rc: 'medium', rd: 'low' }
  const cat = { ra: 'coupling', rb: 'layering', rc: 'cohesion', rd: 'duplication' }
  const args = baseArgs({
    scope: { paths: ['p'], reviewers: ['ra', 'rb', 'rc', 'rd'].map((k) => reviewerSpec(k)), lenses: [] },
    verify: { highLenses: ['combined'], defaultLenses: ['combined'], maxFindings: 3 },
  })
  const { result } = await fullRun(args, {
    findingFor: (key) => (key === 'architecture' ? null : makeFinding({ title: 'T-' + key, severity: sev[key], category: cat[key], rating: 'plan-it' })),
    verdictFor: (label) => {
      if (label.startsWith('verify:rb-1')) return verdictResult({ refuted: true, confidence: 'high' })
      if (label.startsWith('verify:rc-1')) return null
      return verdictResult()
    },
  })
  const by = Object.fromEntries(result.kept.map((k) => [k.findingId, k]))
  assert.equal(by['ra-1'].verdict, 'CONFIRMED')
  assert.equal('unverifiedReason' in by['ra-1'], false, 'absent on a CONFIRMED row')
  assert.equal(by['rc-1'].verdict, 'UNVERIFIED')
  assert.equal(by['rc-1'].unverifiedReason, 'no-verifier')
  assert.equal(by['rd-1'].verdict, 'UNVERIFIED')
  assert.equal(by['rd-1'].unverifiedReason, 'cap')
  assert.equal(by['rb-1'], undefined, 'a refuted finding is not kept')
  assert.deepEqual(result.refuted, [{
    findingId: 'rb-1', title: 'T-rb', category: 'layering', locations: ['src/rb.mjs:1'],
    stableId: stableIdOf('layering', 'src/rb.mjs'), basis: 'judgement', verifiedBy: 'agents',
  }])
  assert.deepEqual(result.report.coverage.verification, { confirmed: 1, refuted: 1, unverifiedByCap: 1, unverifiedNoVerifier: 1 })
  assert.equal(result.stats.unverifiedByCap, 1)
})

// ── D9 / D10: schemas, categories, rating merge ──────────────────────────────

test('D9 / S46: the FINDINGS schema requires basis (measured or judgement), takes an optional measuredClaim and no metrics; SYNTH keeps its shape; there is no MEASURE schema', () => {
  const s = CORE.schemas(buildPlan())
  assert.equal('MEASURE' in s, false, 'T8a: the MEASURE schema is gone')
  const f = s.FINDINGS.properties.findings.items
  assert.ok(f.required.includes('rating'))
  assert.deepEqual([...f.properties.rating.enum], ['act-now', 'plan-it', 'watch'])
  assert.ok(f.required.includes('basis'), 'basis is required')
  assert.deepEqual([...f.properties.basis.enum].sort(), ['judgement', 'measured'])
  assert.equal(f.required.includes('measuredClaim'), false, 'measuredClaim is optional')
  const claim = f.properties.measuredClaim
  assert.ok(claim, 'measuredClaim is declared')
  assert.deepEqual([...claim.required], ['field'])
  assert.deepEqual([...claim.properties.field.enum].sort(), ['churn', 'churnRecent', 'complexity', 'cycle', 'hotspotScore'])
  assert.deepEqual(Object.keys(claim.properties).sort(), ['field', 'modules', 'value'].sort(), 'exactly the five-field claim: field, value, modules (and nothing else)')
  assert.equal(claim.properties.value.type, 'number')
  assert.equal(claim.properties.modules.type, 'array')
  assert.equal(claim.properties.modules.items.type, 'string')
  assert.equal('metrics' in f.properties, false, 'the reviewer no longer sends metrics')
  assert.deepEqual(
    [...s.SYNTH.required].sort(),
    ['executiveSummary', 'findingIndex', 'healthVerdict', 'method', 'notExamined', 'roadmap', 'strengths', 'themes']
  )
  assert.equal('reportPath' in s.SYNTH.properties, false, 'the synthesis schema no longer carries reportPath')
})

test('D9: the three new categories follow dead-code in order and are tech-debt', () => {
  const i = CORE.CATEGORIES.indexOf('dead-code')
  assert.ok(i >= 0)
  assert.deepEqual(CORE.CATEGORIES.slice(i + 1, i + 4), ['complexity', 'architecture-conformance', 'test-quality'])
  for (const c of ['complexity', 'architecture-conformance', 'test-quality']) assert.equal(CORE.DEFAULT_CLASS_MAP[c], 'tech-debt')
})

test('D10: a merged row takes the most urgent member rating (absent counts as watch) and the canonical basis and measuredClaim', () => {
  const claim = { field: 'churn', value: 3 }
  const all = [
    rawFinding('m1', 's1', { rating: 'watch', basis: 'measured', measuredClaim: claim }),
    rawFinding('m2', 's2', { rating: 'act-now', basis: 'judgement' }),
    rawFinding('m3', 's3', { rating: 'plan-it' }),
    rawFinding('n1', 's4', { rating: 'watch' }),
    rawFinding('n2', 's5', { rating: 'plan-it' }),
    rawFinding('k1', 's6'),
    rawFinding('k2', 's7'),
    rawFinding('u1', 's8', { rating: 'plan-it' }),
    rawFinding('u2', 's9'),
  ]
  const groups = [
    { canonicalId: 'm1', duplicateIds: ['m2', 'm3'], mergedTitle: 'M' },
    { canonicalId: 'n1', duplicateIds: ['n2'], mergedTitle: 'N' },
    { canonicalId: 'k1', duplicateIds: ['k2'], mergedTitle: 'K' },
  ]
  const merged = CORE.mergeFindings(all, groups)
  const row = (id) => merged.find((m) => m.id === id)
  assert.equal(row('m1').rating, 'act-now')
  assert.equal(row('m1').basis, 'measured', 'the canonical basis wins over a member judgement')
  assert.deepEqual(row('m1').measuredClaim, claim)
  assert.equal(row('n1').rating, 'plan-it')
  assert.equal(row('k1').rating, 'watch', 'no member rating -> watch')
  assert.equal(row('u1').rating, 'plan-it', 'a singleton keeps its own rating')
  assert.equal(row('u2').rating, 'watch', 'an unrated singleton is watch')
})

// ── S33 / S5: prompt invariants and replay with the new phases ───────────────

test('S33: every prompt is READ-ONLY without write-tool names (synthesis included); synthesis omits reportPath', async () => {
  const { calls, plan } = await fullRun(fullArgs(), fullScopeOpts())
  const labels = new Set(calls.map((c) => c.label.split(':')[0]))
  for (const l of ['scope', 'review', 'critic', 'gap', 'merge', 'verify', 'synthesis', 'triage']) assert.ok(labels.has(l), `fixture reaches ${l}`)
  assert.equal(labels.has('measure'), false, 'T8a: no measure call')
  for (const c of calls) {
    assert.equal(c.opts.isolation, undefined)
    assert.match(c.prompt, /READ-ONLY/, `${c.label} is READ-ONLY`)
    for (const f of ['manage_items', 'manage_notes', 'advance_item', 'create_work_tree', 'items_write', 'notes_write', 'runs_write', 'docs_write']) {
      assert.equal(c.prompt.includes(f), false, `${c.label} must not mention ${f}`)
    }
  }
  assert.equal(calls.find((c) => c.label === 'synthesis').prompt.includes(plan.reportPath), false)
})

test('S5 / replay: two identical v2 runs with measured input, Gaps and Triage produce identical [label, prompt, model, effort] sequences', async () => {
  const run = async () => {
    const { calls } = await fullRun(fullArgs(), fullScopeOpts())
    return calls.map((c) => [c.label, c.prompt, c.opts.model, c.opts.effort])
  }
  const a = await run()
  const b = await run()
  assert.deepEqual(a, b)
  assert.ok(a.length > 10)
})

// -- Ruling A1 (fix cycle): dispatch values come from the dispatchClasses argument
// Oracle: the item's amendments note A1 (items 1-4) and test-plan S34-S37; the class map literal CLASS_MAP
// above and the D3 values in D3_DEFAULTS are copies of that text, never read from the script.

const CLASS_REQUIRED = 'invalid args: dispatchClasses required'
const classReason = (cls) => `invalid args: dispatchClasses.${cls} must have a model and an effort`
const fableReason = (key) => `invalid args: fable is not allowed for fan-out phase ${key}`

/** One distinct model and effort per class, so a wrong class lookup cannot match by accident. */
function distinctClasses() {
  const out = {}
  for (const cls of Object.keys(classFixture())) out[cls] = { model: `m-${cls}`, effort: `e-${cls}` }
  return out
}

function withoutClasses(...names) {
  const c = classFixture()
  for (const n of names) delete c[n]
  return c
}

const normalizeReason = (o) => {
  const r = CORE.normalizeArgs(baseArgs(o))
  assert.equal(r.ok, false, `expected a refusal for ${JSON.stringify(o)}`)
  return r.reason
}

test('S34: dispatchClasses absent, null, an array, a string, a number or a boolean is refused, never inherited', () => {
  for (const contract of ['audit/args-v2']) {
    for (const bad of [undefined, null, [], [classFixture()], 'research', 3, true]) {
      assert.equal(normalizeReason({ contract, dispatchClasses: bad }), CLASS_REQUIRED, `${contract} dispatchClasses=${JSON.stringify(bad)}`)
    }
  }
  // the JSON-string form of the args drops an undefined field: the same refusal
  const asString = CORE.normalizeArgs(JSON.stringify(baseArgs({ dispatchClasses: undefined })))
  assert.equal(asString.ok, false)
  assert.equal(asString.reason, CLASS_REQUIRED)
})

test('S34: a missing or incomplete class entry is refused naming that class, for each of the six mapped classes', () => {
  for (const cls of Object.keys(classFixture())) {
    assert.equal(normalizeReason({ dispatchClasses: withoutClasses(cls) }), classReason(cls), `${cls} missing`)
  }
  const bads = [
    null, 'sonnet', 3, {}, { model: 'sonnet' }, { effort: 'low' }, { model: '', effort: 'low' }, { model: 'sonnet', effort: '' },
    { model: 3, effort: 'low' }, { model: 'sonnet', effort: null }, { model: ['sonnet'], effort: 'low' },
  ]
  for (const cls of Object.keys(classFixture())) {
    for (const bad of bads) {
      assert.equal(normalizeReason({ dispatchClasses: { ...classFixture(), [cls]: bad } }), classReason(cls), `${cls}=${JSON.stringify(bad)}`)
    }
  }
})

test('S34 / probe (order): classes are checked in the order of the phase-key map; the policy check comes first and the models check after', () => {
  // map order: research, audit-review, review, (research), extraction, (research), audit-verify-high, audit-synthesis
  assert.equal(normalizeReason({ dispatchClasses: withoutClasses('audit-review', 'research') }), classReason('research'))
  assert.equal(normalizeReason({ dispatchClasses: withoutClasses('review', 'audit-review') }), classReason('audit-review'))
  assert.equal(normalizeReason({ dispatchClasses: withoutClasses('extraction', 'review') }), classReason('review'))
  assert.equal(normalizeReason({ dispatchClasses: withoutClasses('audit-synthesis', 'extraction') }), classReason('extraction'))
  assert.equal(normalizeReason({ dispatchClasses: withoutClasses('audit-synthesis', 'audit-verify-high') }), classReason('audit-verify-high'))
  const policyBad = { enabled: true, rootId: 'r', policy: 'x' }
  assert.equal(normalizeReason({ items: policyBad, dispatchClasses: undefined }), 'invalid args: items.policy must be gated, bugs or all')
  assert.equal(normalizeReason({ scriptsDir: 3, dispatchClasses: undefined }), 'invalid args: scriptsDir must be a non-empty string')
  assert.equal(normalizeReason({ scriptsDir: 'S', dispatchClasses: undefined }), 'invalid args: workDir required when scriptsDir is set')
  assert.equal(normalizeReason({ focus: 'both', dispatchClasses: undefined }), 'invalid args: focus must be code, docs or all')
  assert.equal(normalizeReason({ dispatchClasses: undefined, models: { bogus: 'x' } }), CLASS_REQUIRED)
  assert.equal(normalizeReason({ dispatchClasses: withoutClasses('research'), efforts: { bogus: 'x' } }), classReason('research'))
  assert.equal(normalizeReason({ dispatchClasses: classFixture(), models: { bogus: 'x' } }), 'invalid args: unknown dispatch key models.bogus')
})

test('S34: classes outside the map and fields other than model and effort are ignored; plan.dispatch holds exactly the nine keys with a model and an effort', () => {
  const classes = {
    ...classFixture(),
    research: { model: 'sonnet', effort: 'medium', description: 'text', extra: 1 },
    'unmapped-class': { model: 'opus', effort: 'max' },
    implementation: 'not even an object',
  }
  const { ok, plan, reason } = CORE.normalizeArgs(baseArgs({ dispatchClasses: classes }))
  assert.equal(ok, true, reason)
  assert.deepEqual(JSON.parse(JSON.stringify(plan.dispatch)), D3_DEFAULTS)
  assert.deepEqual(Object.keys(plan.dispatch).sort(), Object.keys(D3_DEFAULTS).sort())
  for (const key of Object.keys(plan.dispatch)) assert.deepEqual(Object.keys(plan.dispatch[key]).sort(), ['effort', 'model'], key)
})

test('S34: the entry sequence refuses a missing dispatchClasses before any agent runs', async () => {
  const script = loadScript(SCRIPT_PATH)
  let agentCalls = 0
  const result = await script.run({
    agent: async () => { agentCalls += 1; return null },
    parallel: fakeParallel,
    pipeline: fakePipeline,
    phase: () => {},
    log: () => {},
    args: baseArgs({ contract: 'audit/args-v2', dispatchClasses: undefined }),
  })
  assert.deepEqual(JSON.parse(JSON.stringify(result)), { started: false, reason: CLASS_REQUIRED })
  assert.equal(agentCalls, 0)
})

test('S34 / A1 item 4: meta.whenToUse names dispatchClasses as required, carries no repo path, and the workflow holds no dispatch literal', () => {
  const meta = JSON.parse(JSON.stringify(loadMeta(SCRIPT_PATH)))
  assert.ok(
    meta.whenToUse.includes('dispatchClasses (required: the classes of dispatch-policy.json) gives each phase key its model and effort, and models and efforts override them per phase key'),
  )
  assert.equal(meta.whenToUse.includes('models and efforts override the default model and effort per phase key'), false, 'the old sentence is gone')
  assert.equal(meta.whenToUse.includes('claude-plugins/'), false)
  assert.deepEqual(
    scriptText(SCRIPT_PATH).split('\n').flatMap((line) => dispatchLiteralsInLine(line)),
    [],
    'the workflow holds no model or effort literal of its own',
  )
})

test('S35: each phase key takes the model and effort of its class from dispatchClasses; keys that share a class share its values', () => {
  const classes = distinctClasses()
  const { ok, plan, reason } = CORE.normalizeArgs(baseArgs({ dispatchClasses: classes }))
  assert.equal(ok, true, reason)
  assert.deepEqual(Object.keys(plan.dispatch).sort(), Object.keys(CLASS_MAP).sort())
  for (const [key, cls] of Object.entries(CLASS_MAP)) {
    assert.deepEqual(JSON.parse(JSON.stringify(plan.dispatch[key])), classes[cls], `${key} <- ${cls}`)
  }
  assert.deepEqual(plan.dispatch.scope, plan.dispatch.critic)
  assert.deepEqual(plan.dispatch.scope, plan.dispatch['verify-group'])
  assert.deepEqual(plan.dispatch.merge, plan.dispatch.triage)
  assert.notDeepEqual(plan.dispatch.review, plan.dispatch['review-architecture'])
  // the plan holds copies: changing the plan's entry leaves the caller's class object alone
  plan.dispatch.scope.model = 'changed'
  assert.equal(classes.research.model, 'm-research')
})

test('S35: models and efforts override the class values per key and leave the other keys of the class alone', () => {
  const classes = distinctClasses()
  const { ok, plan, reason } = CORE.normalizeArgs(baseArgs({ dispatchClasses: classes, models: { review: 'm-over' }, efforts: { 'verify-group': 'e-over', synth: 'e-synth-over' } }))
  assert.equal(ok, true, reason)
  assert.deepEqual(JSON.parse(JSON.stringify(plan.dispatch.review)), { model: 'm-over', effort: 'e-audit-review' })
  assert.deepEqual(JSON.parse(JSON.stringify(plan.dispatch['verify-group'])), { model: 'm-research', effort: 'e-over' })
  assert.deepEqual(JSON.parse(JSON.stringify(plan.dispatch.synth)), { model: 'm-audit-synthesis', effort: 'e-synth-over' })
  assert.deepEqual(JSON.parse(JSON.stringify(plan.dispatch.scope)), classes.research, 'the same class under another key is untouched')
  assert.deepEqual(JSON.parse(JSON.stringify(plan.dispatch.critic)), classes.research)
  assert.deepEqual(JSON.parse(JSON.stringify(plan.dispatch['review-architecture'])), classes.review)
})

test('S35: a whole run carries each call the model and effort of its key class, with the [model/effort] tag', async () => {
  const classes = distinctClasses()
  const { calls } = await fullRun(fullArgs({ dispatchClasses: classes, ...withCandidates('c1') }), fullScopeOpts())
  const seen = new Set()
  for (const c of calls) {
    const key = dispatchKeyOf(c, FULL_SEV)
    seen.add(key)
    const want = classes[CLASS_MAP[key]]
    assert.equal(c.opts.model, want.model, `${c.label} model`)
    assert.equal(c.opts.effort, want.effort, `${c.label} effort`)
    assert.equal(c.opts.label, `${c.label} [${want.model}/${want.effort}]`, `${c.label} label tag`)
  }
  assert.deepEqual([...seen].sort(), Object.keys(CLASS_MAP).sort(), 'fixture must exercise all ten dispatch keys')
})

test('S36: a fable model in the class of a fan-out key is refused naming the first such key, in any casing or embedding', () => {
  const cases = [
    ['audit-review', 'review'], ['research', 'verify-group'], ['audit-verify-high', 'verify-group-high'],
    ['extraction', 'triage'],
  ]
  for (const [cls, key] of cases) {
    for (const model of ['fable', 'Fable', 'FABLE', 'claude-fable-x', 'my-fable-1']) {
      assert.equal(
        normalizeReason({ dispatchClasses: { ...classFixture(), [cls]: { model, effort: 'low' } } }),
        fableReason(key),
        `${cls}=${model}`,
      )
    }
  }
  // two fan-out classes at fable: the first fan-out key in the frozen order (review before triage)
  assert.equal(
    normalizeReason({ dispatchClasses: { ...classFixture(), extraction: { model: 'fable', effort: 'low' }, 'audit-review': { model: 'fable', effort: 'low' } } }),
    fableReason('review'),
  )
  // an effort override does not lift the refusal
  assert.equal(
    normalizeReason({ dispatchClasses: { ...classFixture(), research: { model: 'Fable', effort: 'low' } }, efforts: { 'verify-group': 'high' } }),
    fableReason('verify-group'),
  )
})

test('S36: fable stays allowed where no fan-out key resolves to it; an inline models override replaces a class at fable', () => {
  const single = { ...classFixture(), review: { model: 'fable', effort: 'high' }, 'audit-synthesis': { model: 'fable', effort: 'medium' } }
  const a = CORE.normalizeArgs(baseArgs({ dispatchClasses: single }))
  assert.equal(a.ok, true, a.reason)
  assert.equal(a.plan.dispatch['review-architecture'].model, 'fable')
  assert.equal(a.plan.dispatch.synth.model, 'fable')
  const research = { ...classFixture(), research: { model: 'Fable', effort: 'medium' } }
  const b = CORE.normalizeArgs(baseArgs({ dispatchClasses: research, models: { 'verify-group': 'sonnet' } }))
  assert.equal(b.ok, true, b.reason)
  assert.equal(b.plan.dispatch['verify-group'].model, 'sonnet')
  assert.equal(b.plan.dispatch['verify-group'].effort, 'medium')
  assert.equal(b.plan.dispatch.scope.model, 'Fable', 'scope and critic are single-agent keys and keep the class model')
  assert.equal(b.plan.dispatch.critic.model, 'Fable')
})

test('S36 / probe (order): the inline fable check of models runs before the check on resolved class models', () => {
  const classes = { ...classFixture(), 'audit-review': { model: 'fable', effort: 'high' } }
  assert.equal(normalizeReason({ dispatchClasses: classes, models: { 'verify-group': 'fable' } }), fableReason('verify-group'))
  assert.equal(normalizeReason({ dispatchClasses: classes }), fableReason('review'))
})

test('S37: the classes of dispatch-policy.json give the D3 table, and none resolves to fable', () => {
  const { classes } = loadPolicy()
  for (const contract of ['audit/args-v2']) {
    const { ok, plan, reason } = CORE.normalizeArgs(baseArgs({ contract, dispatchClasses: classes }))
    assert.equal(ok, true, `${contract}: ${reason}`)
    assert.deepEqual(JSON.parse(JSON.stringify(plan.dispatch)), D3_DEFAULTS, contract)
    for (const [key, d] of Object.entries(plan.dispatch)) assert.equal(/fable/i.test(d.model), false, `${key} must not resolve to fable`)
  }
})

test('S37 / S41: the three audit classes exist in the policy at the A1 values, audit-measure is gone, and the reused classes carry the D3 values', () => {
  const { classes } = loadPolicy()
  const want = {
    'audit-review': { model: 'sonnet', effort: 'high' },
    'audit-verify-high': { model: 'opus', effort: 'medium' },
    'audit-synthesis': { model: 'opus', effort: 'medium' },
    research: { model: 'sonnet', effort: 'medium' },
    review: { model: 'opus', effort: 'high' },
    extraction: { model: 'sonnet', effort: 'low' },
  }
  for (const [cls, v] of Object.entries(want)) {
    assert.ok(classes[cls], `class ${cls} exists`)
    assert.deepEqual({ model: classes[cls].model, effort: classes[cls].effort }, v, cls)
  }
  assert.equal('audit-measure' in classes, false, 'T8a: the audit-measure class is removed')
})

// -- Feature-review A1 fixes (item bf401ffd, test-author seat) --------------------------------
// Blind test author: the oracle is the item's task-scope D1-D7 and test-plan S1-S9 (frozen before the
// implementation); signatures come from the supplied declarations block. No implementation source was
// opened. The literals below (the digest cut note, the ctx line shapes, the log text) are copied from D2
// and D5, never read from CORE.

const DIGEST_CUT_NOTE_LITERAL = '(digest cut at 8000 characters; the full numbers are in the metrics file)'
/** Every JSON value (object or array) that parses out of free text, found by a string-aware bracket scan. */
function jsonValuesIn(text) {
  const out = []
  for (let i = 0; i < text.length; i++) {
    if (text[i] !== '{' && text[i] !== '[') continue
    let depth = 0
    let inStr = false
    let esc = false
    for (let j = i; j < text.length; j++) {
      const ch = text[j]
      if (inStr) {
        if (esc) esc = false
        else if (ch === '\\') esc = true
        else if (ch === '"') inStr = false
        continue
      }
      if (ch === '"') inStr = true
      else if (ch === '{' || ch === '[') depth++
      else if (ch === '}' || ch === ']') {
        depth--
        if (depth === 0) {
          try {
            out.push(JSON.parse(text.slice(i, j + 1)))
          } catch {
            // not JSON: an unrelated bracket in prose
          }
          break
        }
      }
    }
  }
  return out
}

function objectsDeep(value, acc = []) {
  if (Array.isArray(value)) {
    for (const v of value) objectsDeep(v, acc)
  } else if (value && typeof value === 'object') {
    acc.push(value)
    for (const v of Object.values(value)) objectsDeep(v, acc)
  }
  return acc
}

// S2, S3 (D2) -----------------------------------------------------------------------------------

test('S2 / D2: no Measure phase: schemas(plan) has no MEASURE entry and meta.phases starts at Scope', () => {
  assert.equal('MEASURE' in CORE.schemas(buildPlan()), false)
  const meta = JSON.parse(JSON.stringify(loadMeta(SCRIPT_PATH)))
  assert.equal(meta.phases[0].title, 'Scope')
  assert.equal(meta.phases.some((ph) => ph.title === 'Measure'), false)
})

test('S3 / D2: a run without measured input names no metrics script in any prompt, and the helpers of the Measure phase are gone', async () => {
  const { calls } = await fullRun(fullArgs({ measured: undefined }), fullScopeOpts())
  for (const c of calls) assert.equal(c.prompt.includes('audit-metrics.mjs'), false, `${c.label} must not name the metrics script`)
  const text = scriptText(SCRIPT_PATH)
  assert.equal(text.includes('buildMeasurePrompt'), false)
  assert.equal(text.includes('shiftMonths'), false)
})

// S4 (D3) ----------------------------------------------------------------------------------------

test('S4 / D3: result.date is args.date on a full run and on a null-scope run, for two different dates', async () => {
  for (const date of ['2026-11-22', '2027-01-31']) {
    const full = await fullRun(fullArgs({ date }), fullScopeOpts())
    assert.equal(full.result.date, date, 'full run')
    const cut = await fullRun(fullArgs({ date }), { scope: null })
    assert.equal(cut.result.date, date, 'null-scope run')
    assert.equal(cut.result.report, null)
  }
})

test('S4 / D3 probe: a run without measured input carries the date too', async () => {
  const { result } = await fullRun(baseArgs({ contract: 'audit/args-v2', date: '2026-03-31' }), {})
  assert.equal(result.date, '2026-03-31')
})

// S5 (D4) ----------------------------------------------------------------------------------------

test('S5 / D4: a reviewer-sent metrics key never reaches a kept row or the synthesis JSON', async () => {
  const withMetrics = makeFinding({ title: 'T-with-metrics-q9', metrics: { churn: 3, complexity: 7 } })
  const without = makeFinding({ title: 'T-without-metrics-q9' })
  const { result, calls } = await fullRun(v2Args(), {
    findingFor: (key) => (key === 'r1' ? withMetrics : key === 'r2' ? without : null),
  })
  const keptWith = result.kept.find((k) => k.title === 'T-with-metrics-q9')
  const keptWithout = result.kept.find((k) => k.title === 'T-without-metrics-q9')
  assert.ok(keptWith && keptWithout, 'both findings are kept')
  assert.equal('metrics' in keptWith, false, 'no kept row carries metrics')
  assert.equal('metrics' in keptWithout, false)
  const synth = calls.find((c) => c.label === 'synthesis')
  assert.ok(synth, 'synthesis ran')
  const rowsFor = (title) => objectsDeep(jsonValuesIn(synth.prompt)).filter((o) => Object.values(o).includes(title))
  for (const t of ['T-with-metrics-q9', 'T-without-metrics-q9']) {
    const payload = rowsFor(t)
    assert.ok(payload.length > 0, `the synthesis prompt carries ${t} as JSON`)
    for (const o of payload) assert.equal('metrics' in o, false, 'no metrics key in the synthesis JSON')
  }
})

// S6, S7, S8 (D5): formatMetricsDigest(m, ctx) ---------------------------------------------------

function digestInput(overrides = {}) {
  return {
    hotSet: [{ path: 'a.kt', churn: 9, churnRecent: 2, complexity: 30, hotspotScore: 0.5 }],
    topComplexity: [{ path: 'a.kt', complexity: 30 }],
    cycles: [],
    unstableModules: [],
    coupling: [{
      path: 'a.kt',
      partners: [{ path: 'b.kt', count: 4 }, { path: 'c.kt', count: 2 }, { path: 'd.kt', count: 1 }, { path: 'e.kt', count: 1 }],
    }],
    trend: [
      { path: 'up.kt', complexities: [10, 12, 15] },
      { path: 'down.kt', complexities: [15, 12, 10] },
      { path: 'flat.kt', complexities: [10, 15, 10] },
    ],
    notes: [],
    ...overrides,
  }
}

const DIGEST_CTX = { metricsPath: 'W/metrics.json', since: '2025-11-22', recent: '2026-08-22' }
const WINDOWS_LINE = 'Windows: long since 2025-11-22, recent since 2026-08-22'
const FILE_LINE = 'Metrics file: W/metrics.json (every number above comes from it, and it holds the numbers for every other file; read it instead of running the metrics script)'

test('S6 / D5: the digest prints hot-set, coupling and trend lines in the D5 shapes and ends with the two ctx lines', () => {
  const d = CORE.formatMetricsDigest(digestInput(), DIGEST_CTX)
  const lines = d.split('\n')
  assert.ok(d.startsWith('MEASURED STRUCTURE'))
  assert.ok(lines.includes('- a.kt (churn 9, recent 2, complexity 30, score 0.5)'), 'hot-set line')
  assert.ok(lines.includes('- a.kt: b.kt (4), c.kt (2), d.kt (1)'), 'coupling line: three partners, the fourth is cut')
  assert.equal(d.includes('e.kt'), false, 'a fourth partner is not printed')
  assert.ok(lines.includes('- up.kt: 10 -> 12 -> 15 (rising)'))
  assert.ok(lines.includes('- down.kt: 15 -> 12 -> 10 (falling)'))
  assert.ok(lines.includes('- flat.kt: 10 -> 15 -> 10 (flat)'), 'last equal to first is flat')
  assert.deepEqual(d.trimEnd().split('\n').slice(-2), [WINDOWS_LINE, FILE_LINE])
})

test('S6 / D5: the sections come in the order hot set, highest complexity, cycles, least stable, coupling, trend', () => {
  const d = CORE.formatMetricsDigest(digestInput({ cycles: [['m1', 'm2']], unstableModules: [{ id: 'm1', instability: 0.9 }] })).toLowerCase()
  const heads = ['hot set', 'highest complexity', 'dependency cycles', 'least stable modules', 'change coupling', 'complexity trend']
  const at = heads.map((h) => d.indexOf(h))
  for (let i = 0; i < heads.length; i++) assert.notEqual(at[i], -1, `${heads[i]} is present`)
  for (let i = 1; i < at.length; i++) assert.ok(at[i - 1] < at[i], `${heads[i - 1]} before ${heads[i]}`)
})

test('S6 / D5 probe: trend direction compares the last sample with the first, not a peak', () => {
  const d = CORE.formatMetricsDigest(digestInput({
    trend: [{ path: 'dip.kt', complexities: [10, 5, 15] }, { path: 'peak.kt', complexities: [10, 30, 12] }, { path: 'fall.kt', complexities: [15, 20, 10] }],
  }))
  const lines = d.split('\n')
  assert.ok(lines.includes('- dip.kt: 10 -> 5 -> 15 (rising)'))
  assert.ok(lines.includes('- peak.kt: 10 -> 30 -> 12 (rising)'))
  assert.ok(lines.includes('- fall.kt: 15 -> 20 -> 10 (falling)'))
})

test('S6 / D5 probe: an absent number in a hot-set item prints as a dash', () => {
  const d = CORE.formatMetricsDigest(digestInput({ hotSet: [{ path: 'p.kt', churn: 1, complexity: 2 }] }))
  assert.ok(d.split('\n').includes('- p.kt (churn 1, recent -, complexity 2, score -)'))
})

test('S6 / D5 probe: the ctx lines appear only for the ctx members given, Windows before Metrics file', () => {
  const m = digestInput()
  const none = CORE.formatMetricsDigest(m)
  assert.equal(none.includes('Windows:'), false)
  assert.equal(none.includes('Metrics file:'), false)
  assert.equal(CORE.formatMetricsDigest(m, {}), none, 'an empty ctx equals no ctx')
  const windowsOnly = CORE.formatMetricsDigest(m, { since: '2025-11-22', recent: '2026-08-22' })
  assert.ok(windowsOnly.includes(WINDOWS_LINE))
  assert.equal(windowsOnly.includes('Metrics file:'), false)
  const sinceOnly = CORE.formatMetricsDigest(m, { since: '2025-11-22' })
  assert.ok(sinceOnly.endsWith('\nWindows: long since 2025-11-22'), 'since alone prints no recent window')
  assert.equal(sinceOnly.includes('recent since'), false)
  const fileOnly = CORE.formatMetricsDigest(m, { metricsPath: 'W/metrics.json' })
  assert.ok(fileOnly.includes(FILE_LINE))
  assert.equal(fileOnly.includes('Windows:'), false)
  const both = CORE.formatMetricsDigest(m, DIGEST_CTX)
  assert.ok(both.indexOf(WINDOWS_LINE) < both.indexOf(FILE_LINE))
  assert.equal(none.includes('digest cut'), false, 'a small digest is not cut')
})

function hotLine(path) {
  return `- ${path} (churn 1, recent 1, complexity 1, score 0.1)`
}

function bigHotInput() {
  const paths = Array.from({ length: 15 }, (_, i) => `h${String(i + 1).padStart(2, '0')}${'x'.repeat(597)}`)
  const m = digestInput({
    hotSet: paths.map((p) => ({ path: p, churn: 1, churnRecent: 1, complexity: 1, hotspotScore: 0.1 })),
    coupling: [],
    trend: [],
    topComplexity: [],
  })
  return { paths, m }
}

test('S7 / D5: 15 hot paths of 600 characters are cut at the last line break within 8000, the note ends the capped text, the ctx lines follow', () => {
  const { paths, m } = bigHotInput()
  const d = CORE.formatMetricsDigest(m, DIGEST_CTX)
  const at = d.indexOf('Windows:')
  assert.notEqual(at, -1)
  const capped = d.slice(0, at).trimEnd()
  assert.ok(capped.endsWith(DIGEST_CUT_NOTE_LITERAL), 'the cut note ends the capped text')
  const cutText = capped.slice(0, capped.length - DIGEST_CUT_NOTE_LITERAL.length).trimEnd()
  assert.ok(cutText.length <= 8000, `the text before the note is at most 8000 characters (${cutText.length})`)
  const kept = paths.filter((p) => cutText.includes(p))
  assert.ok(kept.length >= 1 && kept.length < 15, 'some, not all, hot paths survive the cut')
  assert.deepEqual(kept, paths.slice(0, kept.length), 'the cut drops from the end')
  for (const l of cutText.split('\n').filter((x) => x.startsWith('- h'))) assert.ok(l.endsWith(')'), 'no line is cut in the middle')
  const firstDropped = paths[kept.length]
  assert.ok(cutText.length + 1 + hotLine(firstDropped).length > 8000, 'the cut is at the last line break that fits, not an earlier one')
  assert.deepEqual(d.trimEnd().split('\n').slice(-2), [WINDOWS_LINE, FILE_LINE])
})

test('S7 / D5: without ctx the capped text stands alone and ends with the cut note', () => {
  const { m } = bigHotInput()
  const d = CORE.formatMetricsDigest(m)
  assert.ok(d.endsWith(DIGEST_CUT_NOTE_LITERAL))
  assert.equal(d.includes('Windows:'), false)
  assert.equal(d.includes('Metrics file:'), false)
})

test('S7 / D5 probe: a digest of exactly 8000 characters is not cut, one more character is', () => {
  const build = (len) => digestInput({
    hotSet: [{ path: 'x'.repeat(len), churn: 1, churnRecent: 1, complexity: 1, hotspotScore: 0.1 }],
    coupling: [], trend: [], topComplexity: [],
  })
  const a = CORE.formatMetricsDigest(build(10)).length
  const b = CORE.formatMetricsDigest(build(11)).length
  assert.equal(b - a, 1, 'fixture: the digest grows by one character per path character')
  const exact = 10 + (8000 - a)
  const atCap = CORE.formatMetricsDigest(build(exact))
  assert.equal(atCap.length, 8000, 'fixture: exactly 8000 characters')
  assert.equal(atCap.includes('digest cut'), false, 'at the cap is not over it')
  const over = CORE.formatMetricsDigest(build(exact + 1))
  assert.ok(over.endsWith(DIGEST_CUT_NOTE_LITERAL), 'one character over is cut')
  const cutText = over.slice(0, over.length - DIGEST_CUT_NOTE_LITERAL.length).trimEnd()
  assert.ok(cutText.length <= 8000, 'the text before the note fits the cap')
})

test('S8 / D5: a 12-id cycle prints 8 ids then (+4 more); 8 ids print whole; 9 ids print (+1 more)', () => {
  const ids = (n) => Array.from({ length: n }, (_, i) => `m${String(i + 1).padStart(2, '0')}`)
  const d12 = CORE.formatMetricsDigest(digestInput({ cycles: [ids(12)] }))
  assert.ok(d12.includes(`${ids(8).join(' -> ')} (+4 more)`))
  assert.equal(d12.includes('m09'), false)
  const d8 = CORE.formatMetricsDigest(digestInput({ cycles: [ids(8)] }))
  assert.ok(d8.includes(ids(8).join(' -> ')))
  assert.equal(d8.includes('more)'), false, 'no suffix at exactly 8')
  const d9 = CORE.formatMetricsDigest(digestInput({ cycles: [ids(9)] }))
  assert.ok(d9.includes(`${ids(8).join(' -> ')} (+1 more)`))
  assert.equal(d9.includes('m09'), false)
})

// S9 (D5): runAudit hands the digest its ctx --------------------------------------------------------

test('S9 / D5: reviewer prompts carry the metrics file and both windows after the digest, before the slice', async () => {
  const args = fullArgs({ date: '2026-11-22', scope: { paths: ['p1', 'p2'] } })
  const { calls } = await fullRun(args, fullScopeOpts())
  const reviewers = calls.filter((c) => c.label.startsWith('review:'))
  assert.ok(reviewers.length >= 4, 'reviewers, a lens and the architecture lens ran')
  for (const c of reviewers) {
    assert.ok(c.prompt.includes('Metrics file: W/metrics.json'), `${c.label} names the metrics file`)
    assert.ok(c.prompt.includes('long since 2025-11-22, recent since 2026-08-22'), `${c.label} names the windows`)
    assert.ok(digestAt(c.prompt) >= 0 && digestAt(c.prompt) < c.prompt.indexOf('Metrics file:'), `${c.label}: ctx after the digest`)
    assert.ok(c.prompt.indexOf('Metrics file:') < c.prompt.indexOf('YOUR SLICE:'), `${c.label}: ctx before the slice`)
  }
})

test('S9 / D5 probe: the windows follow measured.window whatever the run date', async () => {
  const m = measuredFixture({ window: { since: '2024-02-29', recent: '2025-12-31' } })
  const { calls } = await fullRun(fullArgs({ date: '2026-05-31', measured: m }), fullScopeOpts())
  const reviewers = calls.filter((x) => x.label.startsWith('review:'))
  assert.ok(reviewers.length >= 4)
  for (const c of reviewers) {
    assert.ok(c.prompt.includes('long since 2024-02-29, recent since 2025-12-31'), `${c.label} windows`)
  }
})

// -- T8a (item 01632f2c, test-author seat): measured input, slice digests, metrics-pending rows, pre-clustered
// merge and stable ids. Oracle: the item's task-scope D1-D11 and test-plan S38-S60 (frozen before the
// implementation); signatures come from the supplied declarations block. No implementation source was
// opened: the one fact not in the declarations, the shapes preCluster returns, is asserted only on the
// members the task-scope names (autoGroups, clusters, batches) and otherwise through the merge calls a run
// makes. FNV expected values come from the published vectors and from testFnv above (an independent
// implementation over Buffer bytes), never from the workflow.

const BS = String.fromCharCode(92)

// ── S41: no measure key, class or fan-out entry ──────────────────────────────

test('S41: DISPATCH_CLASSES and FAN_OUT_KEYS have no measure entry; plan.dispatch and the agent projection have no measure key', () => {
  assert.equal('measure' in JSON.parse(JSON.stringify(CORE.DISPATCH_CLASSES)), false)
  assert.equal(Object.values(JSON.parse(JSON.stringify(CORE.DISPATCH_CLASSES))).includes('audit-measure'), false)
  assert.deepEqual([...CORE.FAN_OUT_KEYS], FAN_OUT)
  const plan = buildPlan()
  assert.equal('measure' in plan.dispatch, false)
  assert.equal('measure' in CORE.projectAgents(plan), false)
  assert.equal(CORE.normalizeArgs(baseArgs({ dispatchClasses: { ...classFixture(), 'audit-measure': { model: 'haiku', effort: 'low' } } })).ok, true, 'an unmapped audit-measure class entry is ignored, not required')
})

test('S41: a quick run with items and no scriptsDir projects 17 agents; the merge term is max(1, ceil(est / 40)) at the batch boundary', () => {
  const plan = CORE.normalizeArgs(fullArgs()).plan
  assert.equal(CORE.projectAgents(plan).total, 17) // T8b D15 by hand: 1 + 4 + 2 + 1 + 4 + 1 + 4
  // est = (R + gaps.max) * 8: R=4 + 1 gap -> 40 -> 1 batch; R=5 + 1 -> 48 -> 2 batches
  const bigger = buildPlan({ scope: { paths: ['x'], reviewers: [1, 2, 3, 4].map((i) => reviewerSpec('g' + i)), lenses: [] }, gaps: { enabled: true, max: 1 } })
  assert.equal(CORE.projectAgents(bigger).merge, handComputeTotal(bigger).merge)
  assert.equal(handComputeTotal(bigger).merge, 2)
})

// ── S42-S44: sliceDigest ─────────────────────────────────────────────────────

function sliceMeasured() {
  const hot = (path, module) => ({ path, churn: 5, churnRecent: 2, complexity: 9, hotspotScore: 0.4, module })
  return measuredFixture({
    hotSet: [hot('src/a/a-hot.kt', 'src/a'), hot('src/b/b-hot.kt', 'src/b'), hot('src/ab/ab-hot.kt', 'src/ab')],
    topComplexity: [
      { path: 'src/a/a-cx.kt', complexity: 40, module: 'src/a' },
      { path: 'src/b/b-cx.kt', complexity: 39, module: 'src/b' },
      { path: 'src/ab/ab-cx.kt', complexity: 38, module: 'src/ab' },
    ],
    trend: [
      { path: 'src/a/a-trend.kt', complexities: [1, 2, 3] },
      { path: 'src/b/b-trend.kt', complexities: [3, 2, 1] },
    ],
    // 'src' overlaps the slice from above, 'src/a/deep' from below; 'src/ab' and 'src/b' do not overlap 'src/a'
    cycles: [['src', 'lib-cyc'], ['src/b', 'drop-cyc-x'], ['src/ab', 'drop-cyc-y'], ['src/a/deep', 'deep-cyc']],
    unstableModules: [
      { id: 'src/a/deep', instability: 0.9, fanIn: 1, fanOut: 2 },
      { id: 'src/b/drop-unst', instability: 0.8, fanIn: 1, fanOut: 2 },
      { id: 'src/ab/drop-unst2', instability: 0.7, fanIn: 1, fanOut: 2 },
    ],
    coupling: [
      { path: 'src/b/x-coup.kt', partners: [{ path: 'src/a/partner-in.kt', count: 3 }] },
      { path: 'src/a/own-coup.kt', partners: [{ path: 'lib/far.kt', count: 2 }] },
      { path: 'src/b/drop-coup.kt', partners: [{ path: 'src/b/drop-partner.kt', count: 4 }] },
    ],
  })
}

test('S42: sliceDigest keeps hot, complexity and trend rows under the slice path, the overlapping cycles and unstable modules, and the coupling rows with a slice path on either side; it drops the rest', () => {
  const d = CORE.sliceDigest(sliceMeasured(), ['src/a'])
  assert.ok(d.startsWith('MEASURED STRUCTURE'))
  for (const kept of ['src/a/a-hot.kt', 'src/a/a-cx.kt', 'src/a/a-trend.kt', 'lib-cyc', 'deep-cyc', 'src/a/deep', 'src/b/x-coup.kt', 'src/a/own-coup.kt']) {
    assert.ok(d.includes(kept), `${kept} is kept`)
  }
  for (const dropped of ['src/b/b-hot.kt', 'src/b/b-cx.kt', 'src/b/b-trend.kt', 'drop-cyc-x', 'drop-cyc-y', 'drop-unst', 'drop-coup.kt', 'src/ab/ab-hot.kt']) {
    assert.equal(d.includes(dropped), false, `${dropped} is dropped`)
  }
  assert.ok(d.trimEnd().split('\n').slice(-2).join('\n') === [WINDOWS_LINE, FILE_LINE].join('\n'), 'the windows and file lines end the digest')
})

test('S42 / probe: a slice that owns nothing in the measured input still gets a digest text, with none of the other slices rows', () => {
  const d = CORE.sliceDigest(sliceMeasured(), ['docs/elsewhere'])
  assert.equal(typeof d, 'string')
  for (const gone of ['a-hot.kt', 'b-hot.kt', 'ab-hot.kt', 'a-cx.kt', 'b-trend.kt', 'lib-cyc', 'drop-cyc-x', 'x-coup.kt', 'own-coup.kt']) {
    assert.equal(d.includes(gone), false, `${gone} is not under docs/elsewhere`)
  }
})

test('S43: a path matches a whole segment: src/a keeps src/a/x and not src/ab/x; separators, a leading ./ and a trailing slash normalize; . and the empty path keep the whole tree', () => {
  const m = sliceMeasured()
  const base = CORE.sliceDigest(m, ['src/a'])
  assert.equal(base.includes('src/ab/ab-hot.kt'), false, 'src/ab is not under src/a')
  assert.equal(base.includes('src/ab/ab-cx.kt'), false)
  assert.equal(CORE.sliceDigest(m, ['src' + BS + 'a' + BS]), base, 'backslashes and a trailing separator')
  assert.equal(CORE.sliceDigest(m, ['./src/a']), base, 'a leading ./')
  assert.equal(CORE.sliceDigest(m, ['src/a/']), base, 'a trailing slash')
  for (const whole of [['.'], [''], ['./']]) {
    const d = CORE.sliceDigest(m, whole)
    for (const p of ['src/a/a-hot.kt', 'src/b/b-hot.kt', 'src/ab/ab-hot.kt', 'src/b/b-cx.kt', 'src/b/b-trend.kt', 'drop-cyc-x']) {
      assert.ok(d.includes(p), `${JSON.stringify(whole)} keeps ${p}`)
    }
  }
  const two = CORE.sliceDigest(m, ['src/a', 'src/b'])
  assert.ok(two.includes('src/a/a-hot.kt') && two.includes('src/b/b-hot.kt'), 'several slice paths union')
  assert.equal(two.includes('src/ab/ab-hot.kt'), false)
})

function bigSliceMeasured(count = 15) {
  const paths = Array.from({ length: count }, (_, i) => `src/a/h${String(i + 1).padStart(2, '0')}${'x'.repeat(597)}`)
  return {
    paths,
    m: measuredFixture({
      hotSet: paths.map((p) => ({ path: p, churn: 1, churnRecent: 1, complexity: 1, hotspotScore: 0.1, module: 'src/a' })),
      topComplexity: [], cycles: [], unstableModules: [], coupling: [], trend: [],
    }),
  }
}
const CUT_NOTE_3000 = '(digest cut at 3000 characters; the full numbers are in the metrics file)'

test('S44: a slice digest over 3000 characters is cut at the last line break within 3000, the note names 3000 and ends the capped text, the ctx lines follow', () => {
  const { paths, m } = bigSliceMeasured()
  const d = CORE.sliceDigest(m, ['src/a'])
  const at = d.indexOf('Windows:')
  assert.notEqual(at, -1)
  const capped = d.slice(0, at).trimEnd()
  assert.ok(capped.endsWith(CUT_NOTE_3000), 'the cut note names the cap used')
  const cutText = capped.slice(0, capped.length - CUT_NOTE_3000.length).trimEnd()
  assert.ok(cutText.length <= 3000, `the text before the note is at most 3000 characters (${cutText.length})`)
  const kept = paths.filter((p) => cutText.includes(p))
  assert.ok(kept.length >= 1 && kept.length < 15, 'some, not all, hot paths survive the cut')
  assert.deepEqual(kept, paths.slice(0, kept.length), 'the cut drops from the end')
  for (const l of cutText.split('\n').filter((x) => x.startsWith('- src/a/h'))) assert.ok(l.endsWith(')'), 'no line is cut in the middle')
  const nextLine = `- ${paths[kept.length]} (churn 1, recent 1, complexity 1, score 0.1)`
  assert.ok(cutText.length + 1 + nextLine.length > 3000, 'the cut is at the last line break that fits')
  assert.deepEqual(d.trimEnd().split('\n').slice(-2), [WINDOWS_LINE, FILE_LINE])
})

test('S44: the same input is cut at 3000 for a slice and at 8000 by default; maxChars 8000 equals the default; a digest of exactly 3000 characters is not cut, 3001 is', () => {
  const { m } = bigSliceMeasured()
  assert.ok(CORE.formatMetricsDigest(m).endsWith(DIGEST_CUT_NOTE_LITERAL), 'default cap 8000')
  assert.equal(CORE.formatMetricsDigest(m, { maxChars: 8000 }), CORE.formatMetricsDigest(m))
  const three = CORE.formatMetricsDigest(m, { maxChars: 3000 })
  assert.ok(three.endsWith(CUT_NOTE_3000))
  assert.equal(three.includes('digest cut at 8000'), false)
  const build = (len) => measuredFixture({
    hotSet: [{ path: 'x'.repeat(len), churn: 1, churnRecent: 1, complexity: 1, hotspotScore: 0.1 }],
    topComplexity: [], cycles: [], unstableModules: [], coupling: [], trend: [],
  })
  const a = CORE.formatMetricsDigest(build(10), { maxChars: 3000 }).length
  const exact = 10 + (3000 - a)
  const atCap = CORE.formatMetricsDigest(build(exact), { maxChars: 3000 })
  assert.equal(atCap.length, 3000, 'fixture: exactly 3000 characters')
  assert.equal(atCap.includes('digest cut'), false)
  assert.ok(CORE.formatMetricsDigest(build(exact + 1), { maxChars: 3000 }).endsWith(CUT_NOTE_3000))
})

// ── S45: carriers with distinct slice paths ──────────────────────────────────

test('S45: each territory reviewer gets the digest of its own paths, the other lens and the gap reviewer the scope paths, scope and the architecture lens the full digest', async () => {
  const m = sliceMeasured()
  const reviewers = [reviewerSpec('ra', { paths: ['src/a'] }), reviewerSpec('rb', { paths: ['src/b'] })]
  const args = baseArgs({
    contract: 'audit/args-v2', measured: m, gaps: { enabled: true, max: 1 },
    scope: { paths: ['src'], reviewers, lenses: [{ key: 'l1', label: 'Lens l1', focus: 'f' }] },
  })
  const { calls } = await fullRun(args, { critic: criticResult({ gaps: [{ label: 'g1', prompt: 'p1' }] }) })
  const prompt = (l) => calls.find((c) => c.label === l).prompt
  assert.ok(prompt('review:ra').includes(CORE.sliceDigest(m, ['src/a'])))
  assert.ok(prompt('review:rb').includes(CORE.sliceDigest(m, ['src/b'])))
  assert.equal(prompt('review:ra').includes('src/b/b-hot.kt'), false, 'ra does not see rb rows')
  assert.equal(prompt('review:rb').includes('src/a/a-hot.kt'), false, 'rb does not see ra rows')
  for (const l of ['review:l1', 'gap:1']) assert.ok(prompt(l).includes(CORE.sliceDigest(m, ['src'])), `${l} gets sliceDigest of the scope paths`)
  assert.ok(prompt('review:architecture').includes(fullDigestOf(m)))
  assert.ok(calls.some((c) => c.label === 'critic'))
  for (const l of ['critic', 'synthesis']) assert.equal(hasDigest(prompt(l)), false, `${l} gets no digest`)
})

// ── S46: FINDINGS prompt words ───────────────────────────────────────────────

test('S46: the reviewer prompt asks for basis and measuredClaim and no longer asks for metrics', async () => {
  const { calls } = await fullRun(v2Args(), {})
  const p = calls.find((c) => c.label === 'review:r1').prompt
  assert.ok(p.includes('basis'))
  assert.ok(p.includes('measuredClaim'))
  assert.ok(p.includes('measured') && p.includes('judgement'))
  assert.equal(/set metrics|metrics:/i.test(p), false, 'the old metrics clause is gone')
})

// ── S47 / S48: metrics-pending rows ──────────────────────────────────────────

function mergedRow(id, overrides = {}) {
  return { id, title: `Row ${id}`, severity: 'high', category: 'coupling', locations: [`src/${id}.kt:1`], basis: 'judgement', ...overrides }
}

test('S47: markMetricsPending: a measured row with a valid claim is pending (UNVERIFIED, metrics-pending, verifiedBy none, finalSeverity = severity); every other row is rest with basis judgement and no claim', () => {
  const rows = [
    mergedRow('p1', { basis: 'measured', measuredClaim: { field: 'churn', value: 9 } }),
    mergedRow('p2', { basis: 'measured', measuredClaim: { field: 'complexity', value: 0 }, severity: 'low' }),
    mergedRow('p3', { basis: 'measured', measuredClaim: { field: 'cycle', modules: ['m/a', 'm/b'] } }),
    mergedRow('r-noclaim', { basis: 'measured' }),
    mergedRow('r-judge', { basis: 'judgement', measuredClaim: { field: 'churn', value: 9 } }),
    mergedRow('r-plain', { basis: 'judgement' }),
    mergedRow('r-field', { basis: 'measured', measuredClaim: { field: 'loc', value: 9 } }),
    mergedRow('r-str', { basis: 'measured', measuredClaim: { field: 'churn', value: '9' } }),
    mergedRow('r-nan', { basis: 'measured', measuredClaim: { field: 'churn', value: NaN } }),
    mergedRow('r-inf', { basis: 'measured', measuredClaim: { field: 'hotspotScore', value: Infinity } }),
    mergedRow('r-novalue', { basis: 'measured', measuredClaim: { field: 'churnRecent' } }),
    mergedRow('r-cyc-empty', { basis: 'measured', measuredClaim: { field: 'cycle', modules: [] } }),
    mergedRow('r-cyc-str', { basis: 'measured', measuredClaim: { field: 'cycle', modules: 'm/a' } }),
    mergedRow('r-cyc-none', { basis: 'measured', measuredClaim: { field: 'cycle' } }),
    mergedRow('r-null', { basis: 'measured', measuredClaim: null }),
  ]
  const { pending, rest } = CORE.markMetricsPending(rows)
  assert.deepEqual(pending.map((r) => r.id), ['p1', 'p2', 'p3'], 'pending rows keep input order')
  assert.deepEqual(rest.map((r) => r.id), rows.slice(3).map((r) => r.id), 'rest rows keep input order')
  for (const r of pending) {
    assert.equal(r.verdict, 'UNVERIFIED')
    assert.equal(r.unverifiedReason, 'metrics-pending')
    assert.equal(r.verifiedBy, 'none')
    assert.equal(r.basis, 'measured')
    assert.ok(r.measuredClaim, 'a pending row keeps its claim')
  }
  assert.equal(pending[0].finalSeverity, 'high')
  assert.equal(pending[1].finalSeverity, 'low', 'finalSeverity is the row severity')
  assert.deepEqual(pending[0].measuredClaim, { field: 'churn', value: 9 })
  assert.deepEqual(pending[2].measuredClaim, { field: 'cycle', modules: ['m/a', 'm/b'] })
  for (const r of rest) {
    assert.equal(r.basis, 'judgement', `${r.id} basis`)
    assert.equal('measuredClaim' in r, false, `${r.id} carries no claim`)
    assert.equal('verdict' in r, false, `${r.id} is not marked unverified`)
  }
})

test('S47 / probe: a reviewer-sent metrics key is dropped from pending and rest rows; an empty input gives two empty lists; the input rows are not mutated', () => {
  const rows = [
    mergedRow('a', { basis: 'measured', measuredClaim: { field: 'churn', value: 3 }, metrics: { churn: 3 } }),
    mergedRow('b', { metrics: { churn: 1, complexity: 2 } }),
  ]
  const before = JSON.parse(JSON.stringify(rows))
  const { pending, rest } = CORE.markMetricsPending(rows)
  assert.equal('metrics' in pending[0], false)
  assert.equal('metrics' in rest[0], false)
  assert.deepEqual(JSON.parse(JSON.stringify(rows)), before)
  const none = CORE.markMetricsPending([])
  assert.deepEqual([JSON.parse(JSON.stringify(none.pending)), JSON.parse(JSON.stringify(none.rest))], [[], []])
})

test('S48: a run with a metrics-pending finding sends it to no verifier, keeps it last, counts stats.metricsPending, and gives it no cap slot', async () => {
  const claim = { field: 'churn', value: 9 }
  const specs = {
    r1: [{ title: 'T-pending', locations: ['src/r1.kt:1'], basis: 'measured', measuredClaim: claim, severity: 'critical' }],
    r2: [{ title: 'T-two', locations: ['src/r2.kt:1'] }],
    r3: [{ title: 'T-three', locations: ['src/r3.kt:1'], metrics: { churn: 1 } }],
  }
  const { calls, result } = await clusterRun(specs, { verify: { highLenses: ['combined'], defaultLenses: ['combined'], maxFindings: 1 } })
  assert.equal(calls.some((c) => c.label.startsWith('verify:') && findingsOfPrompt(c.prompt).some((f) => f.id === 'r1-1')), false, 'no verify group lists the pending finding')
  assert.equal(result.stats.metricsPending, 1)
  assert.equal(result.stats.unverifiedByCap, 1, 'only the judgement finding past the cap is cut; the pending one took no slot')
  assert.deepEqual(result.kept.map((k) => k.findingId), ['r2-1', 'r3-1', 'r1-1'], 'order: verified, cap tail, pending')
  const by = Object.fromEntries(result.kept.map((k) => [k.findingId, k]))
  assert.equal(by['r1-1'].verdict, 'UNVERIFIED')
  assert.equal(by['r1-1'].unverifiedReason, 'metrics-pending')
  assert.equal(by['r1-1'].verifiedBy, 'none')
  assert.equal(by['r1-1'].basis, 'measured')
  assert.deepEqual(by['r1-1'].measuredClaim, claim)
  assert.equal(by['r1-1'].severity, 'critical')
  assert.equal(by['r2-1'].verifiedBy, 'agents')
  assert.equal(by['r2-1'].basis, 'judgement')
  assert.equal('measuredClaim' in by['r2-1'], false)
  assert.equal(by['r3-1'].unverifiedReason, 'cap')
  assert.equal(by['r3-1'].verifiedBy, 'none', 'a cap-tail UNVERIFIED row has verifiedBy none')
  for (const k of result.kept) assert.equal('metrics' in k, false, `${k.findingId} carries no metrics`)
  const synth = calls.find((c) => c.label === 'synthesis')
  const payload = objectsDeep(jsonValuesIn(synth.prompt)).filter((o) => Object.values(o).includes('T-pending'))
  assert.ok(payload.length > 0, 'the synthesis payload carries the pending finding')
  for (const o of payload) {
    assert.equal(o.basis, 'measured')
    assert.deepEqual(o.measuredClaim, claim)
    assert.equal('metrics' in o, false)
  }
})

test('S48: the synthesis prompt names metrics-pending in its UNVERIFIED sentence even when no finding is pending', async () => {
  const { calls } = await clusterRun({ r1: [{ title: 'T-one', locations: ['src/r1.kt:1'] }] })
  const synth = calls.find((c) => c.label === 'synthesis').prompt
  assert.ok(synth.includes('metrics-pending'))
})

test('S48 / probe: a measured finding whose claim is invalid is verified like a judgement finding and reads as judgement on the kept row', async () => {
  const { calls, result } = await clusterRun({
    r1: [{ title: 'T-bad-claim', locations: ['src/r1.kt:1'], basis: 'measured', measuredClaim: { field: 'loc', value: 3 } }],
  })
  assert.ok(calls.some((c) => c.label.startsWith('verify:') && findingsOfPrompt(c.prompt).some((f) => f.id === 'r1-1')), 'verified by agents')
  const row = result.kept.find((k) => k.findingId === 'r1-1')
  assert.equal(row.basis, 'judgement')
  assert.equal('measuredClaim' in row, false)
  assert.equal(row.verifiedBy, 'agents')
  assert.equal(result.stats.metricsPending, 0)
})

// ── S49-S55: pre-clustered merge ─────────────────────────────────────────────

/** A run over a fixed reviewer list; bySlice maps a slice key to the finding overrides it returns. */
async function clusterRun(bySlice, { measured, mergeFor, verdictFor, order = 'fifo', verify, keys } = {}) {
  const sliceKeys = keys || Object.keys(bySlice)
  const args = baseArgs({
    contract: 'audit/args-v2', measured,
    scope: { paths: ['p'], reviewers: sliceKeys.map((k) => reviewerSpec(k)), lenses: [] },
    ...(verify ? { verify } : {}),
  })
  const { ok, plan, reason } = CORE.normalizeArgs(args)
  assert.equal(ok, true, reason)
  const responder = async (label) => {
    if (label.startsWith('review:')) {
      const list = bySlice[label.slice(7)] || []
      return findingsResult({ findings: list.map((f) => makeFinding({ rating: 'plan-it', ...f })) })
    }
    if (label.startsWith('merge:')) return mergeFor ? mergeFor(label) : dedupResult({ groups: [] })
    if (label.startsWith('verify:')) return verdictFor ? verdictFor(label) : verdictResult()
    if (label === 'synthesis') return synthV2()
    throw new Error('clusterRun: unexpected label ' + label)
  }
  const logLines = []
  const { agent, calls } = autoAgent(responder, { order })
  const result = await CORE.runAudit(plan, makeDeps({ agent, logLines }))
  const mergeCalls = calls.filter((c) => c.label.startsWith('merge:'))
  return { plan, calls, result, mergeCalls, logLines }
}

const mentions = (prompt, id) => new RegExp(`(^|[^A-Za-z0-9-])${id}([^A-Za-z0-9]|$)`).test(prompt)

test('S49: three findings on three distinct paths make no cluster: no merge call, stats.mergeAgents 0', async () => {
  const { mergeCalls, result } = await clusterRun({
    s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['src/b.kt:1'] }], s3: [{ locations: ['src/c.kt:1'] }],
  })
  assert.equal(mergeCalls.length, 0)
  assert.equal(result.stats.mergeAgents, 0)
  assert.equal(result.stats.merged, 3)
})

test('S50: two findings on one path (other lines) and one elsewhere -> one merge agent, labelled merge:1, that lists only the two ids; stats.mergeAgents 1', async () => {
  const { mergeCalls, result } = await clusterRun({
    s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['src/a.kt:9'] }], s3: [{ locations: ['src/c.kt:1'] }],
  })
  assert.deepEqual(mergeCalls.map((c) => c.label), ['merge:1'])
  assert.ok(mentions(mergeCalls[0].prompt, 's1-1') && mentions(mergeCalls[0].prompt, 's2-1'))
  assert.equal(mentions(mergeCalls[0].prompt, 's3-1'), false, 'the lone finding is not sent to the agent')
  assert.equal(result.stats.mergeAgents, 1)
  assert.equal(result.stats.merged, 3, 'a merge agent that returns no groups leaves three rows')
})

test('S50 / probe: the primary path ignores a line number and path spelling: a.kt:1 and ./a.kt:9 and a backslash spelling share one cluster', async () => {
  const { mergeCalls } = await clusterRun({
    s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['./src/a.kt:9-12'] }], s3: [{ locations: ['src' + BS + 'a.kt'] }],
  })
  assert.equal(mergeCalls.length, 1)
  for (const id of ['s1-1', 's2-1', 's3-1']) assert.ok(mentions(mergeCalls[0].prompt, id), `${id} is in the cluster`)
})

function manyOn(path, count) {
  return Array.from({ length: count }, (_, i) => ({ locations: [`${path}:${i + 1}`] }))
}

test('S51: a 30-finding cluster and a 20-finding cluster do not share a batch: merge:1 and merge:2, neither split', async () => {
  const { mergeCalls, result } = await clusterRun({ sA: manyOn('src/a.kt', 30), sB: manyOn('src/b.kt', 20) })
  assert.deepEqual(mergeCalls.map((c) => c.label), ['merge:1', 'merge:2'])
  assert.ok(mentions(mergeCalls[0].prompt, 'sA-1') && mentions(mergeCalls[0].prompt, 'sA-30'))
  assert.equal(mentions(mergeCalls[0].prompt, 'sB-1'), false)
  assert.ok(mentions(mergeCalls[1].prompt, 'sB-1') && mentions(mergeCalls[1].prompt, 'sB-20'))
  assert.equal(mentions(mergeCalls[1].prompt, 'sA-1'), false)
  assert.equal(result.stats.mergeAgents, 2)
})

test('S51 / probe (batch boundary): clusters of 20 and 20 share one agent (40 fits), 21 and 20 take two, and a 41-finding cluster is one batch alone', async () => {
  const fit = await clusterRun({ sA: manyOn('src/a.kt', 20), sB: manyOn('src/b.kt', 20) })
  assert.deepEqual(fit.mergeCalls.map((c) => c.label), ['merge:1'])
  assert.ok(mentions(fit.mergeCalls[0].prompt, 'sA-1') && mentions(fit.mergeCalls[0].prompt, 'sB-20'))
  assert.equal(fit.result.stats.mergeAgents, 1)
  const over = await clusterRun({ sA: manyOn('src/a.kt', 21), sB: manyOn('src/b.kt', 20) })
  assert.deepEqual(over.mergeCalls.map((c) => c.label), ['merge:1', 'merge:2'])
  const oversize = await clusterRun({ sA: manyOn('src/a.kt', 41) })
  assert.deepEqual(oversize.mergeCalls.map((c) => c.label), ['merge:1'], 'a single-path cluster over the limit is never split')
  assert.ok(mentions(oversize.mergeCalls[0].prompt, 'sA-41'))
  const behind = await clusterRun({ sA: manyOn('src/a.kt', 41), sB: manyOn('src/b.kt', 2) })
  assert.deepEqual(behind.mergeCalls.map((c) => c.label), ['merge:1', 'merge:2'], 'a small cluster does not join an oversize one')
})

test('S52: two slices with the same category and location make one auto-merged row and no agent; with two categories they make a cluster and one agent', async () => {
  const same = await clusterRun({ s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['src/a.kt:1'] }] })
  assert.equal(same.mergeCalls.length, 0)
  assert.equal(same.result.stats.mergeAgents, 0)
  assert.equal(same.result.stats.merged, 1)
  assert.deepEqual(same.result.kept.map((k) => k.findingId), ['s1-1'], 'the earliest finding is the canonical one')
  const spelled = await clusterRun({ s1: [{ locations: ['src' + BS + 'a.kt:1'] }], s2: [{ locations: ['src/a.kt:1'] }] })
  assert.equal(spelled.result.stats.merged, 1, 'the location is compared after path normalization')
  const lines = await clusterRun({ s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['src/a.kt:2'] }] })
  assert.equal(lines.result.stats.merged, 2, 'the line is part of the auto-merge key')
  const two = await clusterRun({ s1: [{ locations: ['src/a.kt:1'], category: 'coupling' }], s2: [{ locations: ['src/a.kt:1'], category: 'layering' }] })
  assert.equal(two.mergeCalls.length, 1)
  assert.equal(two.result.stats.mergeAgents, 1)
  assert.equal(two.result.stats.merged, 2)
})

test('S53: a digest coupling pair links findings on its two files into one cluster; without the pair they stay apart', async () => {
  const coupling = [{ path: 'x.kt', partners: [{ path: 'y.kt', count: 4 }] }]
  const specs = { s1: [{ locations: ['x.kt:1'] }], s2: [{ locations: ['y.kt:1'] }], s3: [{ locations: ['z.kt:1'] }] }
  const linked = await clusterRun(specs, { measured: measuredFixture({ coupling }) })
  assert.equal(linked.mergeCalls.length, 1)
  assert.ok(mentions(linked.mergeCalls[0].prompt, 's1-1') && mentions(linked.mergeCalls[0].prompt, 's2-1'))
  assert.equal(mentions(linked.mergeCalls[0].prompt, 's3-1'), false, 'a file outside the pair is not linked')
  const alone = await clusterRun(specs)
  assert.equal(alone.mergeCalls.length, 0)
  const foreign = await clusterRun(
    { s1: [{ locations: ['x.kt:1'] }], s3: [{ locations: ['z.kt:1'] }] },
    { measured: measuredFixture({ coupling }) }
  )
  assert.equal(foreign.mergeCalls.length, 0, 'only one of the two paired files has a finding')
})

test('S53: a digest cycle links findings in two of its modules into one cluster, by the longest known module id; a finding in another module stays out', async () => {
  const specs = { s1: [{ locations: ['m/a/1.kt:3'] }], s2: [{ locations: ['m/b/2.kt:3'] }], s3: [{ locations: ['m/c/4.kt:3'] }] }
  const measured = measuredFixture({
    cycles: [['m/a', 'm/b']],
    hotSet: [{ path: 'm/a/1.kt', churn: 1, churnRecent: 1, complexity: 1, hotspotScore: 0.1, module: 'm/a' }],
    topComplexity: [{ path: 'm/c/4.kt', complexity: 3, module: 'm/c' }],
    unstableModules: [],
  })
  const linked = await clusterRun(specs, { measured })
  assert.equal(linked.mergeCalls.length, 1)
  assert.ok(mentions(linked.mergeCalls[0].prompt, 's1-1') && mentions(linked.mergeCalls[0].prompt, 's2-1'))
  assert.equal(mentions(linked.mergeCalls[0].prompt, 's3-1'), false)
  const alone = await clusterRun(specs)
  assert.equal(alone.mergeCalls.length, 0, 'without the cycle the three paths stay apart')
})

test('S53 / probe: the merge prompt lists the units under CLUSTER headings and forbids a group across clusters', async () => {
  const { mergeCalls } = await clusterRun({
    s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['src/a.kt:2'] }],
    s3: [{ locations: ['src/b.kt:1'] }], s4: [{ locations: ['src/b.kt:2'] }],
  })
  assert.equal(mergeCalls.length, 1, 'two small clusters share one batch')
  const p = mergeCalls[0].prompt
  assert.ok(p.includes('CLUSTER 1') && p.includes('CLUSTER 2'))
  assert.equal(p.includes('CLUSTER 3'), false)
  assert.ok(p.indexOf('CLUSTER 1') < p.indexOf('s1-1') && p.indexOf('s2-1') < p.indexOf('CLUSTER 2') && p.indexOf('CLUSTER 2') < p.indexOf('s3-1'), 'ids sit under their own heading')
  assert.match(p, /READ-ONLY/)
})

test('S54: a group the agent returns across two clusters merges only the ids of its canonical cluster', async () => {
  const groups = [{ canonicalId: 's1-1', duplicateIds: ['s2-1', 's3-1'], mergedTitle: 'Merged one' }]
  const { result, mergeCalls } = await clusterRun(
    {
      s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['src/a.kt:2'] }],
      s3: [{ locations: ['src/b.kt:1'] }], s4: [{ locations: ['src/b.kt:2'] }],
    },
    { mergeFor: () => dedupResult({ groups }) }
  )
  assert.equal(mergeCalls.length, 1)
  assert.deepEqual(result.kept.map((k) => k.findingId).sort(), ['s1-1', 's3-1', 's4-1'], 's2 joined s1; s3 stayed out of the cross-cluster group')
  assert.equal(result.kept.find((k) => k.findingId === 's1-1').title, 'Merged one')
  assert.equal(result.stats.merged, 3)
})

test('S54 / probe: a canonical brings its auto-merged members; an untouched auto group is added as is; a null agent leaves its batch to the auto groups', async () => {
  const specs = {
    s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['src/a.kt:1'] }], s3: [{ locations: ['src/a.kt:5'] }],
    s4: [{ locations: ['src/b.kt:1'] }], s5: [{ locations: ['src/b.kt:1'] }],
  }
  const bring = await clusterRun(specs, { mergeFor: () => dedupResult({ groups: [{ canonicalId: 's1-1', duplicateIds: ['s3-1'], mergedTitle: 'Brought' }] }) })
  assert.deepEqual(bring.result.kept.map((k) => k.findingId).sort(), ['s1-1', 's4-1'], 's1+s2 (auto) + s3 form one row; s4+s5 (auto, untouched) another')
  assert.equal(bring.result.kept.find((k) => k.findingId === 's1-1').title, 'Brought')
  assert.equal(bring.result.stats.merged, 2)
  const nul = await clusterRun(specs, { mergeFor: () => null })
  assert.deepEqual(nul.result.kept.map((k) => k.findingId).sort(), ['s1-1', 's3-1', 's4-1'], 'a null agent: the auto groups still merge, nothing else does')
  assert.equal(nul.result.started, true)
})

test('S54 / probe: an id the agent invents, or one outside the cluster, is ignored', async () => {
  const { result } = await clusterRun(
    { s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['src/a.kt:2'] }], s3: [{ locations: ['src/c.kt:1'] }] },
    { mergeFor: () => dedupResult({ groups: [{ canonicalId: 's1-1', duplicateIds: ['ghost-9', 's3-1'], mergedTitle: 'X' }] }) }
  )
  assert.deepEqual(result.kept.map((k) => k.findingId).sort(), ['s1-1', 's2-1', 's3-1'])
})

test('S55: preCluster is deterministic and does not mutate its input; fifo and reverse completion order give the same kept rows and the same merge prompts', async () => {
  const all = [
    rawFinding('a', 's1', { locations: ['src/a.kt:1'] }),
    rawFinding('b', 's2', { locations: ['src/a.kt:1'] }),
    rawFinding('c', 's3', { locations: ['src/a.kt:7'] }),
    rawFinding('d', 's4', { locations: ['src/d.kt:2'], category: 'layering' }),
  ]
  const before = JSON.parse(JSON.stringify(all))
  const first = JSON.parse(JSON.stringify(CORE.preCluster(all)))
  const second = JSON.parse(JSON.stringify(CORE.preCluster(all)))
  assert.deepEqual(first, second)
  assert.deepEqual(JSON.parse(JSON.stringify(all)), before)
  const withNull = JSON.parse(JSON.stringify(CORE.preCluster(all, null)))
  assert.deepEqual(withNull, first, 'an absent measured argument equals null')
  assert.equal(first.autoGroups.length, 1)
  assert.equal(first.autoGroups[0].canonicalId, 'a')
  assert.deepEqual(first.autoGroups[0].duplicateIds, ['b'])
  assert.equal(first.autoGroups[0].mergedTitle, '')
  assert.equal(first.batches.length, 1)
  const specs = {
    s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['src/a.kt:2'] }], s3: [{ locations: ['src/b.kt:1'] }], s4: [{ locations: ['src/b.kt:2'] }],
  }
  const f = await clusterRun(specs, { order: 'fifo' })
  const r = await clusterRun(specs, { order: 'reverse' })
  assert.deepEqual(r.result.kept, f.result.kept)
  assert.deepEqual(r.mergeCalls.map((c) => [c.label, c.prompt]), f.mergeCalls.map((c) => [c.label, c.prompt]))
})

test('S55 / probe: preCluster of no findings and of one finding makes no batch', () => {
  assert.equal(CORE.preCluster([]).batches.length, 0)
  assert.equal(CORE.preCluster([]).autoGroups.length, 0)
  assert.equal(CORE.preCluster([rawFinding('a', 's1')]).batches.length, 0)
})

// ── S56-S60: stable ids ──────────────────────────────────────────────────────

test('S56: fnv1a32 gives the published FNV-1a 32-bit vectors as 8 lowercase hex digits, over the UTF-8 bytes', () => {
  assert.equal(CORE.fnv1a32(''), '811c9dc5')
  assert.equal(CORE.fnv1a32('a'), 'e40c292c')
  assert.equal(CORE.fnv1a32('foobar'), 'bf9cf968')
  for (const text of ['\u00e9', '\u20ac', 'caf\u00e9|src/\u00fc.kt', '\ud83d\ude00']) {
    assert.equal(CORE.fnv1a32(text), testFnv(text), `bytes of ${JSON.stringify(text)}`)
  }
  assert.notEqual(CORE.fnv1a32('\u00e9'), CORE.fnv1a32('e'), 'a non-ASCII character is not truncated to a byte')
  let zeroLeading = null
  for (let i = 0; i < 100000 && zeroLeading === null; i++) if (testFnv('z' + i).startsWith('0')) zeroLeading = 'z' + i
  assert.ok(zeroLeading, 'fixture: an input whose hash starts with 0')
  assert.equal(CORE.fnv1a32(zeroLeading), testFnv(zeroLeading), 'leading zeros are kept')
  assert.equal(CORE.fnv1a32(zeroLeading).length, 8)
  assert.match(CORE.fnv1a32('Hello'), /^[0-9a-f]{8}$/)
})

test('S56: a kept row stableId is F- plus the hash of category|primaryPath, whatever the line or slice', async () => {
  const { result } = await clusterRun({
    s1: [{ category: 'coupling', locations: ['src/a.kt:12'] }],
    s2: [{ category: 'layering', locations: ['src' + BS + 'b.kt:3-9'] }],
    s3: [{ category: 'coupling', locations: ['./src/c.kt'] }],
  })
  const by = Object.fromEntries(result.kept.map((k) => [k.findingId, k.stableId]))
  assert.equal(by['s1-1'], stableIdOf('coupling', 'src/a.kt'))
  assert.equal(by['s2-1'], stableIdOf('layering', 'src/b.kt'))
  assert.equal(by['s3-1'], stableIdOf('coupling', 'src/c.kt'))
})

test('S57: primaryPath takes the first location: backslashes to slashes, no leading ./, a trailing :line or :line-line stripped, a drive letter kept; no location gives an empty string', () => {
  assert.equal(CORE.primaryPath(['a' + BS + 'b.kt:12']), 'a/b.kt')
  assert.equal(CORE.primaryPath(['./x.kt:3-9']), 'x.kt')
  assert.equal(CORE.primaryPath(['C:/x.kt:5']), 'C:/x.kt')
  assert.equal(CORE.primaryPath(['C:' + BS + 'x.kt:5']), 'C:/x.kt')
  assert.equal(CORE.primaryPath(['x.kt:ab']), 'x.kt', 'a :symbol suffix is stripped')
  assert.equal(CORE.primaryPath(['x.kt']), 'x.kt')
  assert.equal(CORE.primaryPath(['x.kt:7', 'y.kt:8']), 'x.kt', 'only the first location counts')
  assert.equal(CORE.primaryPath([]), '')
})

test('S58: three kept findings that share one category and primary path get F-h, F-h-2, F-h-3 in order', async () => {
  const h = testFnv('coupling|src/a.kt')
  const specs = { s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['src/a.kt:2'] }], s3: [{ locations: ['src/a.kt:3'] }] }
  const three = await clusterRun(specs)
  assert.deepEqual(three.result.kept.map((k) => k.stableId), [`F-${h}`, `F-${h}-2`, `F-${h}-3`])
  assert.deepEqual(three.result.kept.map((k) => k.findingId), ['s1-1', 's2-1', 's3-1'])
})

test('S58b: the suffixes follow line order (contract C6) over kept and refuted rows alike: a refuted first finding keeps the bare id', async () => {
  const h = testFnv('coupling|src/a.kt')
  const specs = { s1: [{ locations: ['src/a.kt:1'] }], s2: [{ locations: ['src/a.kt:2'] }], s3: [{ locations: ['src/a.kt:3'] }] }
  const refutedFirst = await clusterRun(specs, {
    verdictFor: (label) => (label.startsWith('verify:s1-1') ? verdictResult({ refuted: true, confidence: 'high' }) : verdictResult()),
  })
  assert.deepEqual(refutedFirst.result.kept.map((k) => [k.findingId, k.stableId]), [['s2-1', `F-${h}-2`], ['s3-1', `F-${h}-3`]])
  assert.deepEqual(refutedFirst.result.refuted.map((r) => [r.findingId, r.stableId]), [['s1-1', `F-${h}`]], 'a refuted row is numbered by its line, not after the kept rows')
})

test('S58 / probe: a different category or a different path starts its own counter', async () => {
  const { result } = await clusterRun({
    s1: [{ locations: ['src/a.kt:1'] }],
    s2: [{ locations: ['src/a.kt:2'], category: 'layering' }],
    s3: [{ locations: ['src/b.kt:1'] }],
  })
  const ids = result.kept.map((k) => k.stableId)
  assert.equal(new Set(ids).size, 3)
  for (const id of ids) assert.match(id, /^F-[0-9a-f]{8}$/, 'no suffix on a first occurrence')
})

test('S59: the same findings give the same title-to-stableId map whatever the completion order or the reviewer order', async () => {
  const specs = {
    s1: [{ title: 'T-one', locations: ['src/a.kt:1'] }, { title: 'T-two', locations: ['src/a.kt:2'] }],
    s2: [{ title: 'T-three', locations: ['src/b.kt:1'], category: 'layering' }],
    s3: [{ title: 'T-four', locations: ['src/a.kt:3'] }],
  }
  const mapOf = (r) => Object.fromEntries(r.result.kept.map((k) => [k.title, k.stableId]))
  const fifo = await clusterRun(specs, { order: 'fifo' })
  const reverse = await clusterRun(specs, { order: 'reverse' })
  assert.deepEqual(mapOf(reverse), mapOf(fifo))
  assert.equal(Object.keys(mapOf(fifo)).length, 4)
  const swapped = await clusterRun(specs, { keys: ['s3', 's2', 's1'] })
  const base = mapOf(fifo)
  const sw = mapOf(swapped)
  // T8b S77 (O1, D13): the tie-break is by line, then the raw first-location string, so the whole map is reviewer-order independent
  assert.deepEqual(sw, base)
  assert.equal(new Set(Object.values(sw)).size, 4, 'four distinct ids')
})

test('S60: a refuted row carries exactly findingId, title, category, locations (first four), stableId, basis judgement and verifiedBy agents', async () => {
  const locations = ['src/a.kt:1', 'src/b.kt:2', 'src/c.kt:3', 'src/d.kt:4', 'src/e.kt:5', 'src/f.kt:6']
  const { result } = await clusterRun(
    { s1: [{ title: 'T-refuted', category: 'layering', locations }] },
    { verdictFor: () => verdictResult({ refuted: true, confidence: 'high' }) }
  )
  assert.equal(result.kept.length, 0)
  assert.deepEqual(result.refuted, [{
    findingId: 's1-1', title: 'T-refuted', category: 'layering', locations: locations.slice(0, 4),
    stableId: stableIdOf('layering', 'src/a.kt'), basis: 'judgement', verifiedBy: 'agents',
  }])
})

// ── T8b (item 3ef6e092, test-author seat): candidate fan-out and grouped verification ─────────────────────
// Oracle: the item's task-scope D1-D16 and AC 1-11, test-plan S61-S79 (frozen before the implementation), and
// carry-in-contracts C5 and C6. Signatures come from the supplied declarations block. No implementation source
// was opened. Two facts outside the declarations were taken from public runtime evidence and are named in the
// test-manifest: the cluster shape preCluster returns ({k, ids, ...}, observed by calling the function, as S55 does)
// and the layout of a verify group prompt (a FINDINGS: JSON array, task-scope D10). Every expected value below is
// derived by hand from the cited decision and written as a literal, never read back from the workflow.

const CAND_FOUR = ['cand-alpha', 'cand-beta', 'cand-gamma', 'cand-delta']

/** Args with a measured input carrying one candidate per id, in the given order. */
function candidateArgs(ids, overrides = {}) {
  return baseArgs({ measured: measuredFixture({ candidates: ids.map((id) => candidateFixture(id)) }), ...overrides })
}

const idsOfGroupPrompt = (prompt) => findingsOfPrompt(prompt).map((f) => f.id)

// ── S61: C5 args (D1) ────────────────────────────────────────────────────────

test('S61: the presets carry candidateCount 3/6/12 and groupSize 5; plan.scope.candidateCount and plan.verify.groupSize take the preset, an override wins', () => {
  const want = { quick: 3, standard: 6, full: 12 }
  for (const [preset, count] of Object.entries(want)) {
    assert.equal(CORE.PRESETS[preset].candidateCount, count, `${preset} preset candidateCount`)
    assert.equal(CORE.PRESETS[preset].verify.groupSize, 5, `${preset} preset groupSize`)
    const { ok, plan, reason } = CORE.normalizeArgs(baseArgs({ preset, gaps: undefined }))
    assert.equal(ok, true, reason)
    assert.equal(plan.scope.candidateCount, count, `${preset} plan candidateCount`)
    assert.equal(plan.verify.groupSize, 5, `${preset} plan groupSize`)
  }
  const base = baseArgs()
  const over = CORE.normalizeArgs({ ...base, scope: { ...base.scope, candidateCount: 2 }, verify: { groupSize: 2 } })
  assert.equal(over.ok, true, over.reason)
  assert.equal(over.plan.scope.candidateCount, 2)
  assert.equal(over.plan.verify.groupSize, 2)
  assert.deepEqual(over.plan.verify.highLenses, ['combined'], 'the other verify fields keep the quick preset')
  const zero = CORE.normalizeArgs({ ...base, scope: { ...base.scope, candidateCount: 0 } })
  assert.equal(zero.ok, true, 'candidateCount 0 is allowed')
  assert.equal(zero.plan.scope.candidateCount, 0, 'a zero override is kept, not replaced by the preset')
})

test('S61: verify.groupSize that is not a positive integer and scope.candidateCount that is not a non-negative integer are refused with the D1 reasons', () => {
  const size = 'invalid args: verify.groupSize must be a positive integer'
  const count = 'invalid args: scope.candidateCount must be a non-negative integer'
  for (const bad of [0, -1, 1.5, '5']) {
    assert.equal(CORE.normalizeArgs(baseArgs({ verify: { groupSize: bad } })).reason, size, `groupSize ${JSON.stringify(bad)}`)
  }
  for (const bad of [-1, 1.5]) {
    const base = baseArgs()
    assert.equal(CORE.normalizeArgs({ ...base, scope: { ...base.scope, candidateCount: bad } }).reason, count, `candidateCount ${bad}`)
  }
  // groupSize is checked first when both are bad
  const base = baseArgs()
  assert.equal(CORE.normalizeArgs({ ...base, scope: { ...base.scope, candidateCount: -1 }, verify: { groupSize: 0 } }).reason, size)
  // 1 is the smallest valid group size
  assert.equal(CORE.normalizeArgs(baseArgs({ verify: { groupSize: 1 } })).ok, true)
})

// ── S62: C5 dispatch maps (D2) ───────────────────────────────────────────────

test('S62: DISPATCH_CLASSES and FAN_OUT_KEYS equal the D2 literals in order; the old verify keys are unknown; fable is refused for each new fan-out key inline and through its class', () => {
  const classes = JSON.parse(JSON.stringify(CORE.DISPATCH_CLASSES))
  assert.deepEqual(Object.entries(classes), [
    ['scope', 'research'], ['review', 'audit-review'], ['review-candidate', 'audit-review'], ['review-architecture', 'review'],
    ['critic', 'research'], ['merge', 'extraction'], ['verify-group', 'research'], ['verify-group-high', 'audit-verify-high'],
    ['synth', 'audit-synthesis'], ['triage', 'extraction'],
  ])
  assert.deepEqual([...CORE.FAN_OUT_KEYS], ['review', 'review-candidate', 'verify-group', 'verify-group-high', 'triage', 'merge'])
  for (const key of ['verify', 'verify-high']) {
    assert.equal(normalizeReason({ models: { [key]: 'sonnet' } }), `invalid args: unknown dispatch key models.${key}`)
    assert.equal(normalizeReason({ efforts: { [key]: 'low' } }), `invalid args: unknown dispatch key efforts.${key}`)
  }
  for (const key of ['review-candidate', 'verify-group', 'verify-group-high']) {
    assert.equal(normalizeReason({ models: { [key]: 'fable' } }), fableReason(key), `inline fable under ${key}`)
  }
  // through the class: research feeds scope, critic and verify-group; only verify-group is a fan-out key
  assert.equal(normalizeReason({ dispatchClasses: { ...classFixture(), research: { model: 'fable', effort: 'low' } } }), fableReason('verify-group'))
  assert.equal(normalizeReason({ dispatchClasses: { ...classFixture(), 'audit-verify-high': { model: 'fable', effort: 'low' } } }), fableReason('verify-group-high'))
  // audit-review feeds review and review-candidate: the first fan-out key in order is review
  assert.equal(normalizeReason({ dispatchClasses: { ...classFixture(), 'audit-review': { model: 'fable', effort: 'low' } } }), fableReason('review'))
  // an inline override of review lifts the class refusal for review, and the next key in order, review-candidate, is named
  assert.equal(
    normalizeReason({ dispatchClasses: { ...classFixture(), 'audit-review': { model: 'fable', effort: 'low' } }, models: { review: 'sonnet' } }),
    fableReason('review-candidate'),
  )
})

// ── S63 / S64: projection (D3, D15) ──────────────────────────────────────────

const twelveIds = Array.from({ length: 12 }, (_, i) => 'cand-' + (i + 1))
const measured12 = () => measuredFixture({ candidates: twelveIds.map((id) => candidateFixture(id)) })
const presetPlan = (preset, { measured, items }) => {
  const { ok, plan, reason } = CORE.normalizeArgs(baseArgs({
    preset, gaps: undefined, scope: { paths: ['x'] }, ...(measured ? { measured } : {}),
    ...(items ? { items: { enabled: true, rootId: 'root-1' } } : {}),
  }))
  assert.equal(ok, true, reason)
  return plan
}
const termsOf = (p) => ({ total: p.total, scope: p.scope, review: p.review, candidates: p.candidates, gaps: p.gaps, merge: p.merge, verify: p.verify, synth: p.synth, triage: p.triage })

test('S63: projectAgents pins per term (D15 by hand): 12 candidates measured, with and without items, and no measured', () => {
  // est = (R + gaps.max + C) * 8; nV = min(est, maxFindings); verify = ceil(nV / 5) * lenses; merge = ceil(est / 40); triage = ceil(est / 10)
  const withMeasured = {
    quick: { total: 24, scope: 1, review: 4, candidates: 3, gaps: 2, merge: 2, verify: 4, synth: 1, triage: 7 },
    standard: { total: 60, scope: 1, review: 8, candidates: 6, gaps: 3, merge: 4, verify: 24, synth: 1, triage: 13 },
    full: { total: 128, scope: 1, review: 16, candidates: 12, gaps: 5, merge: 7, verify: 60, synth: 1, triage: 26 },
  }
  for (const [preset, want] of Object.entries(withMeasured)) {
    assert.deepEqual(termsOf(CORE.projectAgents(presetPlan(preset, { measured: measured12(), items: true }))), want, `${preset} with items`)
  }
  const noItems = { quick: 17, standard: 47, full: 102 }
  for (const [preset, total] of Object.entries(noItems)) {
    const p = CORE.projectAgents(presetPlan(preset, { measured: measured12() }))
    assert.equal(p.total, total, `${preset} without items`)
    assert.equal(p.triage, 0)
  }
  const noMeasured = { quick: 17, standard: 47, full: 103 }
  for (const [preset, total] of Object.entries(noMeasured)) {
    const p = CORE.projectAgents(presetPlan(preset, { items: true }))
    assert.equal(p.total, total, `${preset} without measured, with items`)
    assert.equal(p.candidates, 0)
  }
})

test('S63: assignedCandidates and the projection count no candidate in a docs focus or at candidateCount 0, cut at candidateCount, and count distinct valid ids', () => {
  const ids = (plan) => CORE.assignedCandidates(plan).map((c) => c.candidateId)
  const planOf = (o) => {
    const { ok, plan, reason } = CORE.normalizeArgs(o)
    assert.equal(ok, true, reason)
    return plan
  }
  const m = (list) => measuredFixture({ candidates: list })
  const four = CAND_FOUR.map((id) => candidateFixture(id))
  // no measured, and a measured with an empty list
  assert.deepEqual(ids(planOf(baseArgs())), [])
  assert.deepEqual(ids(planOf(baseArgs({ measured: m([]) }))), [])
  // cut at candidateCount 3, in measured order
  assert.deepEqual(ids(planOf(baseArgs({ measured: m(four) }))), CAND_FOUR.slice(0, 3))
  // two candidates under a count of 3 -> 2
  const two = planOf(baseArgs({ measured: m(four.slice(0, 2)) }))
  assert.equal(ids(two).length, 2)
  assert.equal(CORE.projectAgents(two).candidates, 2)
  // count 0 and a docs focus -> none
  const base = baseArgs({ measured: m(four) })
  const zero = planOf({ ...base, scope: { ...base.scope, candidateCount: 0 } })
  assert.deepEqual(ids(zero), [])
  assert.equal(CORE.projectAgents(zero).candidates, 0)
  const docs = planOf(noReviewers({ measured: m(four), focus: 'docs' }))
  assert.deepEqual(ids(docs), [])
  assert.equal(CORE.projectAgents(docs).candidates, 0)
  // a repeated id, an empty id, a non-string id and a non-object entry are skipped; the first of a repeat wins
  const messy = [candidateFixture('a1'), null, 'x', candidateFixture('a1', { kind: 'later' }), candidateFixture(''), candidateFixture(7), candidateFixture('a2')]
  const got = CORE.assignedCandidates(planOf(baseArgs({ measured: m(messy) })))
  assert.deepEqual(got.map((c) => c.candidateId), ['a1', 'a2'])
  assert.equal(got[0].kind, 'hotspot', 'the first occurrence of a repeated id is kept')
  assert.equal(CORE.projectAgents(planOf(baseArgs({ measured: m(messy) }))).candidates, 2)
  // a candidate whose subject is not an array reads as an empty subject
  const noSubject = CORE.assignedCandidates(planOf(baseArgs({ measured: m([candidateFixture('s1', { subject: 'src/x' })]) })))
  assert.deepEqual(JSON.parse(JSON.stringify(noSubject[0].subject)), [])
})

test('S64: sizeGuard passes standard with 12 candidates and items at maxAgents 60 and refuses full naming 128', () => {
  assert.equal(CORE.sizeGuard(presetPlan('standard', { measured: measured12(), items: true })), null)
  assert.deepEqual(CORE.sizeGuard(presetPlan('full', { measured: measured12(), items: true })), {
    reason: 'projected 128 agents > maxAgents; pass allowLarge or a smaller preset',
  })
})

// ── S65 - S68: candidate reviewers (D4, D5, D7) ──────────────────────────────

test('S65: four candidates under quick run three candidate:<id> calls in measured order with the audit-review model and effort, a prompt carrying the candidate, and findings sourced by the candidate id', async () => {
  const args = candidateArgs(CAND_FOUR)
  const m = args.measured
  const { calls, result } = await fullRun(args, {})
  const candCalls = calls.filter((c) => c.label.startsWith('candidate:'))
  assert.deepEqual(candCalls.map((c) => c.label), ['candidate:cand-alpha', 'candidate:cand-beta', 'candidate:cand-gamma'])
  assert.equal(calls.some((c) => c.label === 'candidate:cand-delta'), false, 'the fourth candidate is past candidateCount')
  assert.deepEqual(calls.filter((c) => c.label.startsWith('review:')).map((c) => c.label), ['review:r1', 'review:r2', 'review:architecture'], 'review calls are unchanged')
  const lastReview = Math.max(...calls.map((c, i) => (c.label.startsWith('review:') ? i : -1)))
  const firstCandidate = calls.findIndex((c) => c.label.startsWith('candidate:'))
  assert.ok(firstCandidate > lastReview, 'candidate reviewers are issued after the reviewers and the lenses')
  for (const c of candCalls) {
    assert.equal(c.opts.model, 'sonnet')
    assert.equal(c.opts.effort, 'high')
    assert.equal(c.opts.label, `${c.label} [sonnet/high]`)
    assert.equal(c.opts.phase, 'Review')
  }
  const cand = m.candidates[0]
  const prompt = candCalls[0].prompt
  assert.ok(prompt.includes('cand-alpha'), 'names the candidate id')
  assert.ok(prompt.includes(cand.kind), 'names the candidate kind')
  assert.ok(prompt.includes('SUBJECT:') && prompt.includes(cand.subject[0]), 'lists the subject paths')
  assert.ok(prompt.includes('FACTS:') && prompt.includes(JSON.stringify(cand.facts)), 'carries the facts as JSON')
  assert.match(prompt, /cost[- ]of[- ]change/i, 'asks the cost-of-change question')
  assert.ok(prompt.includes(CORE.sliceDigest(m, cand.subject)), 'carries the digest of the subject slice')
  assert.equal(prompt.includes('cand-beta'), false, 'carries no other candidate')
  assert.equal(result.stats.candidateReviewers, 3)
  assert.equal(result.stats.reviewers, 3, 'reviewers counts the territory and lens slices only')
  for (const id of ['cand-alpha', 'cand-beta', 'cand-gamma']) {
    const row = result.kept.find((k) => k.sliceKey === id)
    assert.ok(row, `a kept finding carries sliceKey ${id}`)
    assert.equal(row.findingId, `${id}-1`)
  }
})

test('S66: a null candidate result is listed in stats.missing, counts no candidate reviewer and does not stop the run', async () => {
  const { result } = await fullRun(candidateArgs(CAND_FOUR), { nullFor: ['candidate:cand-beta'] })
  assert.equal(result.started, true)
  assert.ok(result.stats.missing.includes('cand-beta'))
  assert.equal(result.stats.candidateReviewers, 2)
  assert.equal(result.kept.some((k) => k.sliceKey === 'cand-beta'), false)
  assert.equal(result.kept.some((k) => k.sliceKey === 'cand-alpha'), true)
})

test('S67: no candidate call without measured (logged), in a docs focus or at candidateCount 0', async () => {
  const none = await fullRun(baseArgs(), {})
  assert.equal(none.calls.some((c) => c.label.startsWith('candidate:')), false)
  assert.ok(none.logLines.some((l) => l.includes('no measured input')), 'the absence of measured input is logged')
  assert.equal(none.result.stats.candidateReviewers, 0)

  const docsArgs = noReviewers({ focus: 'docs', measured: measuredFixture({ candidates: CAND_FOUR.map((id) => candidateFixture(id)) }) })
  const docsScope = scopeResult({ reviewers: [plannerSlice('d1', 'docs')] })
  const docs = await fullRun(docsArgs, { scope: docsScope })
  assert.equal(docs.result.started, true)
  assert.equal(docs.calls.some((c) => c.label.startsWith('candidate:')), false)
  assert.equal(docs.result.stats.candidateReviewers, 0)

  const base = candidateArgs(CAND_FOUR)
  const zero = await fullRun({ ...base, scope: { ...base.scope, candidateCount: 0 } }, {})
  assert.equal(zero.calls.some((c) => c.label.startsWith('candidate:')), false)
  assert.equal(zero.result.stats.candidateReviewers, 0)
})

test('S67 / probe (empty vs absent): a measured input with an empty candidates list runs no candidate call', async () => {
  const { calls, result } = await fullRun(candidateArgs([]), {})
  assert.equal(calls.some((c) => c.label.startsWith('candidate:')), false)
  assert.equal(result.stats.candidateReviewers, 0)
})

test('S68: the scope prompt lists the assigned candidates after the digest, not an unassigned one; the block is absent without measured, at count 0 and in a docs focus', async () => {
  const heading = 'MEASURED CANDIDATES ALREADY ASSIGNED'
  const scopeOf = async (args, scope) => (await fullRun(args, { scope })).calls.find((c) => c.label === 'scope').prompt
  const scope = scopeResult({ reviewers: [reviewerSpec('r1')] })
  const m = measuredFixture({ candidates: CAND_FOUR.map((id) => candidateFixture(id)) })
  const prompt = await scopeOf(noReviewers({ measured: m }), scope)
  const at = prompt.indexOf(heading)
  assert.ok(at >= 0, 'the block is present')
  assert.ok(at > digestAt(prompt) && digestAt(prompt) >= 0, 'the block follows the digest')
  const block = prompt.slice(at)
  const subject = m.candidates[0].subject[0]
  for (const id of CAND_FOUR.slice(0, 3)) assert.ok(block.includes(`${id} (${subject})`), `${id} is listed with its subject`)
  assert.equal(block.includes('cand-delta'), false, 'the unassigned fourth candidate is not listed')

  assert.equal((await scopeOf(noReviewers(), scope)).includes(heading), false, 'absent without measured')
  const zeroArgs = noReviewers({ measured: m })
  zeroArgs.scope.candidateCount = 0
  assert.equal((await scopeOf(zeroArgs, scope)).includes(heading), false, 'absent at candidateCount 0')
  assert.equal((await scopeOf(noReviewers({ measured: m, focus: 'docs' }), scopeResult({ reviewers: [plannerSlice('d1', 'docs')] }))).includes(heading), false, 'absent in a docs focus')
})

test('S68b: a candidate prompt lists every subject path as "SUBJECT: a, b."; the scope prompt lists it as "<id> (a, b)"', async () => {
  const a = `${HOOKS}/zz.kt`
  const b = `${HOOKS}/yy.kt`
  const m = measuredFixture({ candidates: [candidateFixture('cand-alpha', { subject: [a, b] })] })
  const scope = scopeResult({ reviewers: [reviewerSpec('r1')] })
  const { calls } = await fullRun(noReviewers({ measured: m }), { scope })
  const candidate = calls.find((c) => c.label === 'candidate:cand-alpha').prompt
  assert.ok(candidate.includes(`SUBJECT: ${a}, ${b}.`), 'the candidate prompt joins the subject paths')
  const scopePrompt = calls.find((c) => c.label === 'scope').prompt
  assert.ok(scopePrompt.slice(scopePrompt.indexOf('MEASURED CANDIDATES ALREADY ASSIGNED')).includes(`cand-alpha (${a}, ${b})`), 'the scope prompt joins the subject paths')
})

// ── S69: origin (D6) ─────────────────────────────────────────────────────────

test('S69: every coverage.reviewers entry is {key, label, kind, origin}: territory, lens (the architecture lens too), candidate and gap; kind stays code or docs', async () => {
  const { result } = await fullRun(fullArgs(withCandidates('c1')), fullScopeOpts())
  const entries = result.report.coverage.reviewers
  const by = Object.fromEntries(entries.map((e) => [e.key, e]))
  for (const e of entries) assert.deepEqual(Object.keys(e).sort(), ['key', 'kind', 'label', 'origin'], `${e.key} entry shape`)
  assert.deepEqual(
    Object.fromEntries(entries.map((e) => [e.key, e.origin])),
    { r1: 'territory', r2: 'territory', l1: 'lens', architecture: 'lens', c1: 'candidate', gap1: 'gap' },
  )
  for (const e of entries) assert.equal(e.kind, 'code', `${e.key} kind`)
  assert.equal(result.stats.reviewers, 4, 'territory and lens slices: r1, r2, l1, architecture')
  assert.equal(result.stats.candidateReviewers, 1)
  assert.equal(by.c1.label.length > 0, true)

  const docs = await fullRun(noReviewers({ focus: 'docs' }), { scope: scopeResult({ reviewers: [plannerSlice('d1', 'docs')] }) })
  const d1 = docs.result.report.coverage.reviewers.find((e) => e.key === 'd1')
  assert.equal(d1.kind, 'docs')
  assert.equal(d1.origin, 'territory')
})

// ── S70: packVerifyGroups (D8) ───────────────────────────────────────────────

// Rows in capVerification order. Cluster k=1 holds r2 (p/x), r7 (p/x), r4 (p/y); cluster k=2 holds r1 and r5 (p/z); r3 (p/y)
// and r6 (p/x) are in no cluster. First index of each path in rows: p/z 0, p/x 1, p/y 2. By D8 (cluster k, then the first
// index of the row's primaryPath, then the index) the order is r2 r7 r4 | r1 r5 | r6 r3.
const packRows = () => [
  { id: 'r1', locations: ['p/z.kt:1'] }, { id: 'r2', locations: ['p/x.kt:1'] }, { id: 'r3', locations: ['p/y.kt:1'] },
  { id: 'r4', locations: ['p/y.kt:2'] }, { id: 'r5', locations: ['p/z.kt:9'] }, { id: 'r6', locations: ['p/x.kt:5'] },
  { id: 'r7', locations: ['p/x.kt:3'] },
]
const packClusters = () => [{ k: 1, ids: ['r2', 'r4', 'r7'] }, { k: 2, ids: ['r1', 'r5'] }]
const packIds = (groups) => groups.map((g) => g.map((r) => r.id))

test('S70: packVerifyGroups orders by cluster, then path, then index, and chunks by groupSize', () => {
  assert.deepEqual(packIds(CORE.packVerifyGroups(packRows(), packClusters(), 5)), [['r2', 'r7', 'r4', 'r1', 'r5'], ['r6', 'r3']])
  assert.deepEqual(packIds(CORE.packVerifyGroups(packRows(), packClusters(), 1)), [['r2'], ['r7'], ['r4'], ['r1'], ['r5'], ['r6'], ['r3']])
  assert.deepEqual(packIds(CORE.packVerifyGroups(packRows(), packClusters(), 3)), [['r2', 'r7', 'r4'], ['r1', 'r5', 'r6'], ['r3']])
})

test('S70 / probe (boundaries): exactly ceil(n / groupSize) groups at 5 vs 6 rows, at groupSize n and above n', () => {
  const six = [...packRows(), { id: 'r8', locations: ['p/w.kt:1'] }].slice(0, 6)
  assert.equal(CORE.packVerifyGroups(six.slice(0, 5), packClusters(), 5).length, 1)
  assert.equal(CORE.packVerifyGroups(six, packClusters(), 5).length, 2)
  assert.deepEqual(CORE.packVerifyGroups(six, packClusters(), 5).map((g) => g.length), [5, 1])
  for (const size of [7, 8, 100]) assert.deepEqual(packIds(CORE.packVerifyGroups(packRows(), packClusters(), size)), [['r2', 'r7', 'r4', 'r1', 'r5', 'r6', 'r3']], `size ${size}`)
  assert.deepEqual(CORE.packVerifyGroups([], [], 5), [])
  assert.deepEqual(CORE.packVerifyGroups([], packClusters(), 1), [])
})

test('S70 / probe: rows in no cluster sort last by path and index; the clusters array order does not decide; the inputs are not mutated; a repeat gives the same answer', () => {
  const noClusters = packIds(CORE.packVerifyGroups(packRows(), [], 5))
  // path first indexes: p/z 0, p/x 1, p/y 2 -> r1 r5 | r2 r6 r7 | r3 r4
  assert.deepEqual(noClusters, [['r1', 'r5', 'r2', 'r6', 'r7'], ['r3', 'r4']])
  const reversed = packClusters().reverse()
  assert.deepEqual(packIds(CORE.packVerifyGroups(packRows(), reversed, 5)), [['r2', 'r7', 'r4', 'r1', 'r5'], ['r6', 'r3']])
  const rows = packRows()
  const clusters = packClusters()
  const before = JSON.stringify([rows, clusters])
  const first = packIds(CORE.packVerifyGroups(rows, clusters, 2))
  assert.equal(JSON.stringify([rows, clusters]), before, 'inputs unchanged')
  assert.deepEqual(rows.map((r) => r.id), ['r1', 'r2', 'r3', 'r4', 'r5', 'r6', 'r7'])
  assert.deepEqual(packIds(CORE.packVerifyGroups(rows, clusters, 2)), first)
})

// ── S71 - S73: group verification (D9 - D11) ─────────────────────────────────

/** A run of one finding per reviewer slice (s1..sN), each on its own path so no merge agent runs. */
function slicesRun(severities, { verify = {}, verdictFor, order = 'fifo' } = {}) {
  const bySlice = {}
  severities.forEach((sev, i) => { bySlice['s' + (i + 1)] = [{ severity: sev, locations: [`src/s${i + 1}.kt:1`] }] })
  return clusterRun(bySlice, { verify: { highLenses: ['combined'], defaultLenses: ['combined'], maxFindings: 20, ...verify }, verdictFor, order })
}

test('S71: seven low findings at groupSize 5 make two verify groups, verify:g1:combined and verify:g2:combined, under the verify-group key; stats.verifyAgents counts them', async () => {
  const { calls, result } = await slicesRun(Array(7).fill('low'))
  const verifyCalls = calls.filter((c) => c.label.startsWith('verify:'))
  assert.deepEqual(verifyCalls.map((c) => c.label), ['verify:g1:combined', 'verify:g2:combined'])
  assert.equal(result.stats.verifyAgents, 2)
  assert.deepEqual(verifyCalls.map((c) => idsOfGroupPrompt(c.prompt).length), [5, 2])
  const all = verifyCalls.flatMap((c) => idsOfGroupPrompt(c.prompt)).sort()
  assert.deepEqual(all, ['s1-1', 's2-1', 's3-1', 's4-1', 's5-1', 's6-1', 's7-1'], 'every finding is in exactly one group')
  for (const c of verifyCalls) {
    assert.equal(c.opts.model, 'sonnet', 'verify-group is the research class')
    assert.equal(c.opts.effort, 'medium')
    assert.equal(c.opts.label, `${c.label} [sonnet/medium]`)
    assert.equal(c.opts.phase, 'Verify')
    for (const field of ['refuted', 'confidence', 'evidenceAccurate', 'correctedEvidence', 'adjustedSeverity', 'alreadyTrackedId', 'rationale', 'findingId']) {
      assert.ok(c.prompt.includes(field), `${c.label} prompt names ${field}`)
    }
    for (const f of findingsOfPrompt(c.prompt)) {
      assert.deepEqual(Object.keys(f).sort(), ['category', 'documentation', 'evidence', 'id', 'impact', 'locations', 'recommendation', 'severity', 'title'])
    }
  }
  assert.equal(result.kept.length, 7)
  for (const k of result.kept) assert.equal(k.verdict, 'CONFIRMED')
})

test('S71: the group prompt names the verdicts list the verifier returns (test-plan S71: names each VERDICT field and verdicts)', async () => {
  const { calls } = await slicesRun(Array(2).fill('low'))
  const c = calls.find((x) => x.label.startsWith('verify:'))
  assert.ok(c.prompt.includes('verdicts'), 'the prompt names the verdicts list')
})

test('S71 / probe (groupSize 1 and >= n): one group per finding at 1, one group at 7 and above', async () => {
  const single = await slicesRun(Array(3).fill('low'), { verify: { groupSize: 1 } })
  assert.deepEqual(single.calls.filter((c) => c.label.startsWith('verify:')).map((c) => c.label), ['verify:g1:combined', 'verify:g2:combined', 'verify:g3:combined'])
  assert.equal(single.result.stats.verifyAgents, 3)
  const wide = await slicesRun(Array(7).fill('low'), { verify: { groupSize: 7 } })
  assert.deepEqual(wide.calls.filter((c) => c.label.startsWith('verify:')).map((c) => c.label), ['verify:g1:combined'])
  const five = await slicesRun(Array(5).fill('low'))
  assert.equal(five.result.stats.verifyAgents, 1, 'five rows at size 5 is one group')
  const six = await slicesRun(Array(6).fill('low'))
  assert.equal(six.result.stats.verifyAgents, 2, 'six rows at size 5 is two groups')
})

test('S71: a group with a high or critical member takes the high lenses under verify-group-high; a group of medium and low members takes the default lenses under verify-group', async () => {
  const verify = { highLenses: ['evidence', 'significance', 'consequence'], defaultLenses: ['combined'] }
  for (const top of ['high', 'critical']) {
    const { calls, result } = await slicesRun(['low', 'low', top, 'medium', 'low', 'low', 'low'], { verify })
    const verifyCalls = calls.filter((c) => c.label.startsWith('verify:'))
    const holder = (c) => idsOfGroupPrompt(c.prompt).includes('s3-1')
    const high = verifyCalls.filter(holder)
    const rest = verifyCalls.filter((c) => !holder(c))
    assert.deepEqual(high.map((c) => c.label.split(':').pop()).sort(), ['consequence', 'evidence', 'significance'], `${top}: three high lenses`)
    for (const c of high) {
      assert.equal(c.opts.model, 'opus', `${top}: verify-group-high is the audit-verify-high class`)
      assert.equal(c.opts.effort, 'medium')
    }
    assert.deepEqual(rest.map((c) => c.label.split(':').pop()), ['combined'], `${top}: the other group has the default lens`)
    for (const c of rest) assert.equal(c.opts.model, 'sonnet')
    assert.equal(result.stats.verifyAgents, 4)
  }
  const medium = await slicesRun(Array(7).fill('medium'), { verify })
  assert.deepEqual(medium.calls.filter((c) => c.label.startsWith('verify:')).map((c) => c.label), ['verify:g1:combined', 'verify:g2:combined'], 'medium is not high')
})

test('S72: groupVotes aligns votes to the group by findingId: a missing id is null, an unknown id is ignored, the first of a duplicate wins; a null or malformed answer is all null', () => {
  const group = [{ id: 'a' }, { id: 'b' }, { id: 'c' }]
  const answer = { verdicts: [{ findingId: 'b', rationale: 'rb' }, { findingId: 'ghost', rationale: 'rg' }, { findingId: 'a', rationale: 'ra1' }, { findingId: 'a', rationale: 'ra2' }] }
  const votes = CORE.groupVotes(answer, group)
  assert.equal(votes.length, 3)
  assert.equal(votes[0].rationale, 'ra1', 'the first entry for an id wins')
  assert.equal(votes[1].rationale, 'rb', 'matched by id, not by position')
  assert.equal(votes[2], null)
  for (const bad of [null, undefined, {}, { verdicts: null }, { verdicts: 'x' }, { verdicts: {} }]) {
    assert.deepEqual(CORE.groupVotes(bad, group), [null, null, null], JSON.stringify(bad))
  }
  assert.deepEqual(CORE.groupVotes(answer, []), [])
  assert.equal(CORE.groupVotes({ verdicts: [{ findingId: 'a' }, null, 7] }, group)[0].findingId, 'a', 'a non-object entry does not stop the scan')
})

test('S72: in a run, a member the verifier omitted is UNVERIFIED no-verifier and the others are CONFIRMED', async () => {
  const { result } = await slicesRun(['high', 'high', 'high'], { verdictFor: (label) => (label.startsWith('verify:s2-1') ? null : verdictResult()) })
  const by = Object.fromEntries(result.kept.map((k) => [k.findingId, k]))
  assert.equal(by['s1-1'].verdict, 'CONFIRMED')
  assert.equal(by['s3-1'].verdict, 'CONFIRMED')
  assert.equal(by['s2-1'].verdict, 'UNVERIFIED')
  assert.equal(by['s2-1'].unverifiedReason, 'no-verifier')
})

test('S72 / probe: a raw group answer with an unknown id, with no verdicts list, or in the old bare-verdict shape', async () => {
  const run = async (answerFor) => {
    const args = baseArgs({ scope: { paths: ['p'], reviewers: ['s1', 's2'].map((k) => reviewerSpec(k)), lenses: [] } })
    const { plan } = CORE.normalizeArgs(args)
    const responder = async (label, prompt) => {
      if (label.startsWith('review:')) {
        const key = label.slice(7)
        return findingsResult({ findings: key === 'architecture' ? [] : [makeFinding({ locations: [`src/${key}.kt:1`], rating: 'plan-it' })] })
      }
      if (label.startsWith('merge:')) return dedupResult({ groups: [] })
      if (label.startsWith('verify:')) return answerFor(findingsOfPrompt(prompt).map((f) => f.id))
      if (label === 'synthesis') return synthV2()
      throw new Error('unexpected label ' + label)
    }
    const { agent } = autoAgentRaw(responder)
    return CORE.runAudit(plan, makeDeps({ agent }))
  }
  const ghost = await run((ids) => ({ verdicts: [{ findingId: 'ghost', ...verdictResult() }, ...ids.map((id) => ({ findingId: id, ...verdictResult() }))] }))
  assert.deepEqual(ghost.kept.map((k) => k.verdict), ['CONFIRMED', 'CONFIRMED'])
  for (const answer of [() => ({}), () => verdictResult()]) {
    const r = await run(answer)
    assert.deepEqual(r.kept.map((k) => [k.verdict, k.unverifiedReason]), [['UNVERIFIED', 'no-verifier'], ['UNVERIFIED', 'no-verifier']])
  }
})

test('S73: a group with a documentation member carries the docs rule once and marks that member documentation: true; a code-only group has no rule', async () => {
  const findingFor = (key) => {
    if (key === 'architecture') return null
    return makeFinding({ category: key === 'x1' ? 'documentation-drift' : 'coupling', severity: 'medium', rating: 'plan-it' })
  }
  const scope = scopeResult({ reviewers: [plannerSlice('d1', 'docs'), plannerSlice('c1', 'code'), plannerSlice('x1', 'code')] })
  const { calls } = await fullRun(noReviewers(), { scope, findingFor })
  const groupCalls = calls.filter((c) => c.label.startsWith('verify:'))
  assert.equal(groupCalls.length, 1, 'three findings fit one group')
  const prompt = groupCalls[0].prompt
  assert.equal(prompt.split('DOCUMENTATION FINDING:').length - 1, 1, 'the docs rule appears once')
  assert.match(prompt, /marked documentation: true/)
  const flags = Object.fromEntries(findingsOfPrompt(prompt).map((f) => [f.id, f.documentation]))
  assert.deepEqual(flags, { 'd1-1': true, 'c1-1': false, 'x1-1': true })

  const code = await fullRun(noReviewers(), { scope: scopeResult({ reviewers: [plannerSlice('c1', 'code'), plannerSlice('c2', 'code')] }), findingFor: (k) => (k === 'architecture' ? null : makeFinding({ severity: 'medium', rating: 'plan-it' })) })
  const codePrompt = code.calls.find((c) => c.label.startsWith('verify:')).prompt
  assert.equal(codePrompt.includes('DOCUMENTATION FINDING'), false)
  for (const f of findingsOfPrompt(codePrompt)) assert.equal(f.documentation, false)
})

// ── S74 - S77: carry-ins O4, O5, O2, O1 ──────────────────────────────────────

test('S74 (O4): verifiedBy is agents for CONFIRMED, PLAUSIBLE and REFUTED rows and none for an all-null, a capped and a metrics-pending row', async () => {
  const claim = { field: 'churn', value: 9 }
  const specs = {
    sa: [{ title: 'T-a', severity: 'critical', locations: ['src/a.kt:1'] }],
    sb: [{ title: 'T-b', severity: 'critical', locations: ['src/b.kt:1'] }],
    sc: [{ title: 'T-c', severity: 'critical', locations: ['src/c.kt:1'] }],
    sd: [{ title: 'T-d', severity: 'critical', locations: ['src/d.kt:1'] }],
    se: [{ title: 'T-e', severity: 'low', locations: ['src/e.kt:1'] }],
    sp: [{ title: 'T-p', severity: 'critical', locations: ['src/p.kt:1'], basis: 'measured', measuredClaim: claim }],
  }
  const verdictFor = (label) => {
    if (label.startsWith('verify:sb-1')) return verdictResult({ refuted: true, confidence: 'low' })
    if (label.startsWith('verify:sc-1')) return verdictResult({ refuted: true, confidence: 'high' })
    if (label.startsWith('verify:sd-1')) return null
    return verdictResult()
  }
  const { result } = await clusterRun(specs, { verify: { highLenses: ['combined'], defaultLenses: ['combined'], maxFindings: 4, groupSize: 5 }, verdictFor })
  const by = Object.fromEntries(result.kept.map((k) => [k.findingId, k]))
  assert.deepEqual([by['sa-1'].verdict, by['sa-1'].verifiedBy], ['CONFIRMED', 'agents'])
  assert.deepEqual([by['sb-1'].verdict, by['sb-1'].verifiedBy], ['PLAUSIBLE', 'agents'])
  assert.deepEqual([by['sd-1'].verdict, by['sd-1'].unverifiedReason, by['sd-1'].verifiedBy], ['UNVERIFIED', 'no-verifier', 'none'])
  assert.deepEqual([by['se-1'].verdict, by['se-1'].unverifiedReason, by['se-1'].verifiedBy], ['UNVERIFIED', 'cap', 'none'])
  assert.deepEqual([by['sp-1'].verdict, by['sp-1'].unverifiedReason, by['sp-1'].verifiedBy], ['UNVERIFIED', 'metrics-pending', 'none'])
  assert.deepEqual(result.refuted.map((r) => [r.findingId, r.verifiedBy]), [['sc-1', 'agents']])
})

test('S75 (O5): no prompt of a run without measured contains MEASURED STRUCTURE, and the reviewer prompts still define basis and judgement; with measured the digest is named', async () => {
  const without = await fullRun(fullArgs({ measured: undefined }), fullScopeOpts())
  assert.ok(without.calls.length > 8, 'the run reached every phase')
  for (const c of without.calls) assert.equal(c.prompt.includes('MEASURED STRUCTURE'), false, `${c.label} names no measured structure`)
  for (const c of without.calls.filter((x) => x.label.startsWith('review:') || x.label.startsWith('gap:'))) {
    assert.ok(c.prompt.includes('basis'), `${c.label} still asks for a basis`)
    assert.ok(c.prompt.includes('judgement'), `${c.label} still names judgement`)
  }
  const withM = await fullRun(fullArgs(), fullScopeOpts())
  assert.ok(withM.calls.find((c) => c.label === 'review:r1').prompt.includes('MEASURED STRUCTURE'))
})

test('S76 (O2, D13): two findings of one category and path whose first locations are src/a.kt:5 and src/a.kt:5-9 get the bare id on :5 and the -2 suffix on :5-9, in either input order', async () => {
  const h = testFnv('coupling|src/a.kt')
  for (const keys of [['s1', 's2'], ['s2', 's1']]) {
    const specs = { s1: [{ locations: ['src/a.kt:5-9'] }], s2: [{ locations: ['src/a.kt:5'] }] }
    const { result } = await clusterRun(specs, { keys })
    const idOf = (loc) => result.kept.find((k) => k.locations[0] === loc).stableId
    assert.equal(idOf('src/a.kt:5'), `F-${h}`, `${keys}: the shorter string sorts first`)
    assert.equal(idOf('src/a.kt:5-9'), `F-${h}-2`, `${keys}: the longer string takes the suffix`)
  }
})

// ── S78 / S79: determinism and metrics-pending rows ──────────────────────────

test('S78: fifo and reverse completion, and two identical runs, give equal [label, prompt] for the candidate and verify calls and equal kept rows', async () => {
  const args = () => candidateArgs(CAND_FOUR, { verify: { groupSize: 2 } })
  const pick = (calls) => calls.filter((c) => c.label.startsWith('candidate:') || c.label.startsWith('verify:')).map((c) => [c.label, c.prompt])
  const fifo = await fullRun(args(), {}, { order: 'fifo' })
  const reverse = await fullRun(args(), {}, { order: 'reverse' })
  const again = await fullRun(args(), {}, { order: 'fifo' })
  const base = pick(fifo.calls)
  assert.ok(base.some(([l]) => l.startsWith('candidate:')) && base.some(([l]) => l.startsWith('verify:g2')), 'the run has candidate calls and at least two verify groups')
  assert.deepEqual(pick(reverse.calls), base)
  assert.deepEqual(pick(again.calls), base)
  assert.deepEqual(reverse.result.kept, fifo.result.kept)
})

test('S78 / probe: a candidate id with a colon and a slash keeps its label, its finding ids and its verdict', async () => {
  const id = 'pkg:core/io'
  const { calls, result } = await fullRun(candidateArgs([id]), { locationFor: (key) => (key === id ? 'src/pkgcore.mjs:1' : `src/${key}.mjs:1`) })
  assert.ok(calls.some((c) => c.label === 'candidate:pkg:core/io'))
  const row = result.kept.find((k) => k.sliceKey === id)
  assert.ok(row, 'the candidate finding is kept')
  assert.equal(row.findingId, 'pkg:core/io-1')
  assert.equal(row.verdict, 'CONFIRMED')
  assert.ok(calls.some((c) => c.label.startsWith('verify:') && idsOfGroupPrompt(c.prompt).includes('pkg:core/io-1')))
})

test('S79: no verify group lists a metrics-pending finding and every other finding is listed once', async () => {
  const claim = { field: 'churn', value: 9 }
  const specs = {
    r1: [{ title: 'T-pending', locations: ['src/r1.kt:1'], basis: 'measured', measuredClaim: claim }],
    r2: [{ title: 'T-two', locations: ['src/r2.kt:1'] }],
    r3: [{ title: 'T-three', locations: ['src/r3.kt:1'] }],
  }
  const { calls } = await clusterRun(specs, { verify: { highLenses: ['combined'], defaultLenses: ['combined'], maxFindings: 20, groupSize: 1 } })
  const listed = calls.filter((c) => c.label.startsWith('verify:')).flatMap((c) => idsOfGroupPrompt(c.prompt))
  assert.deepEqual(listed.sort(), ['r2-1', 'r3-1'])
})

// ── T11 (item 326f7edf): one location grammar, candidate-free structural links, location sentence ─────
// Oracle: the item's task-scope D1, D2, D4 and AC 1, 3, 6, 7, and the test-plan S1, S3, S6-S14 (frozen before
// the implementation). Hashes come from testFnv above; no implementation source was opened.

const LOCATION_SENTENCE = 'Write each location as path or path:line (path:line-line for a range), with nothing after it; put a symbol or a note in evidence. '
const TAB = String.fromCharCode(9)

test('S1 (T11): primaryPath maps the five D1 forms of one file to src/a.kt (workflow copy)', () => {
  for (const loc of ['src/a.kt:12', 'src/a.kt:12-30', 'src/a.kt:Foo.bar', 'src/a.kt (note)', 'src/a.kt:12 (note)']) {
    assert.equal(CORE.primaryPath([loc]), 'src/a.kt', loc)
  }
})

test('S11 (T11): primaryPath of no location, an empty string or a non-string is an empty string (workflow copy)', () => {
  assert.equal(CORE.primaryPath([]), '')
  assert.equal(CORE.primaryPath(['']), '')
  assert.equal(CORE.primaryPath([null]), '')
  assert.equal(CORE.primaryPath([42]), '')
})

test('S12 (T11): primaryPath D1 edges and separators (workflow copy)', () => {
  assert.equal(CORE.primaryPath(['C:' + BS + 'x' + BS + 'y.kt:9']), 'C:/x/y.kt')
  assert.equal(CORE.primaryPath(['  ./x.kt:3 (n)']), 'x.kt')
  assert.equal(CORE.primaryPath(['x.kt:']), 'x.kt')
  assert.equal(CORE.primaryPath(['x.kt:1:2']), 'x.kt')
  assert.equal(CORE.primaryPath(['a:b/c.kt:7']), 'a:b/c.kt')
  assert.equal(CORE.primaryPath(['plain.kt']), 'plain.kt')
  assert.equal(CORE.primaryPath(['src/a.kt' + TAB + 'vs src/b.kt']), 'src/a.kt', 'a tab ends the path')
  assert.equal(CORE.primaryPath(['src/a.kt  vs src/b.kt']), 'src/a.kt', 'a double space ends the path')
  assert.equal(CORE.primaryPath(['././x.kt:1']), 'x.kt', 'repeated leading ./')
  assert.equal(CORE.primaryPath(['c:/x/y.kt:9']), 'c:/x/y.kt', 'lower-case drive letter stays')
})

test('S3 (T11): one finding at src/a.kt:12, src/a.kt:12 (resolve) or src/a.kt:Foo.bar gets the stable id of category|src/a.kt', async () => {
  for (const loc of ['src/a.kt:12', 'src/a.kt:12 (resolve)', 'src/a.kt:Foo.bar']) {
    const { result } = await clusterRun({ s1: [{ category: 'coupling', locations: [loc] }] })
    assert.equal(result.kept.length, 1, loc)
    assert.equal(result.kept[0].stableId, stableIdOf('coupling', 'src/a.kt'), loc)
  }
})

test('S14 (T11): stable-id suffixes follow the parsed line: the row at :7 (an annotated location) takes the bare id, the row at :30 takes -2', async () => {
  const h = testFnv('coupling|src/a.kt')
  const { result } = await clusterRun({
    s1: [{ title: 'T-b', locations: ['src/a.kt:30'] }],
    s2: [{ title: 'T-a', locations: ['src/a.kt:7 (note)'] }],
  })
  const by = Object.fromEntries(result.kept.map((k) => [k.title, k.stableId]))
  assert.equal(by['T-a'], 'F-' + h)
  assert.equal(by['T-b'], 'F-' + h + '-2')
})

test('S6 (T11): with an empty candidate list a digest coupling pair of count 3 links its two files into one merge call; a third file stays out', async () => {
  const specs = { s1: [{ locations: ['x.kt:1'] }], s2: [{ locations: ['y.kt:1'] }], s3: [{ locations: ['z.kt:1'] }] }
  const measured = measuredFixture({ candidates: [], cycles: [], coupling: [{ path: 'x.kt', partners: [{ path: 'y.kt', count: 3 }] }] })
  const { mergeCalls } = await clusterRun(specs, { measured })
  assert.equal(mergeCalls.length, 1)
  assert.ok(mentions(mergeCalls[0].prompt, 's1-1') && mentions(mergeCalls[0].prompt, 's2-1'))
  assert.equal(mentions(mergeCalls[0].prompt, 's3-1'), false)
})

test('S9 (T11): a coupling partner with count 2 makes no link', async () => {
  const specs = { s1: [{ locations: ['x.kt:1'] }], s2: [{ locations: ['y.kt:1'] }] }
  const measured = measuredFixture({ candidates: [], cycles: [], coupling: [{ path: 'x.kt', partners: [{ path: 'y.kt', count: 2 }] }] })
  const { mergeCalls } = await clusterRun(specs, { measured })
  assert.equal(mergeCalls.length, 0)
})

test('S10 (T11): a co-change candidate on x.kt and y.kt with empty coupling and no cycles makes no link', async () => {
  const candidate = { candidateId: 'co-change:x.kt', kind: 'co-change', subject: ['x.kt', 'y.kt'], facts: { commits: 4, partners: [] }, score: 0.4 }
  const specs = { s1: [{ locations: ['x.kt:1'] }], s2: [{ locations: ['y.kt:1'] }] }
  const measured = measuredFixture({ candidates: [candidate], cycles: [], coupling: [] })
  const { mergeCalls } = await clusterRun(specs, { measured })
  assert.equal(mergeCalls.length, 0)
})

test('S10 (T11): a cycle candidate alone, with cycles empty, makes no link', async () => {
  const candidate = { candidateId: 'cycle:m/a', kind: 'cycle', subject: ['m/a', 'm/b'], facts: { fileCount: 2, hottestPath: 'm/a/1.kt', edges: 2 }, score: 0.7 }
  const specs = { s1: [{ locations: ['m/a/1.kt:3'] }], s2: [{ locations: ['m/b/2.kt:3'] }] }
  const measured = measuredFixture({
    candidates: [candidate], cycles: [],
    hotSet: [{ path: 'm/a/1.kt', churn: 1, churnRecent: 1, complexity: 1, hotspotScore: 0.1, module: 'm/a' }],
    topComplexity: [{ path: 'm/b/2.kt', complexity: 3, module: 'm/b' }], unstableModules: [],
  })
  const { mergeCalls } = await clusterRun(specs, { measured })
  assert.equal(mergeCalls.length, 0)
})

test('S6 / probe (T11): a pair listed under both files links; a partner with no finding, an empty cycle and an empty partner list link nothing', async () => {
  const both = measuredFixture({
    candidates: [], cycles: [],
    coupling: [{ path: 'x.kt', partners: [{ path: 'y.kt', count: 5 }] }, { path: 'y.kt', partners: [{ path: 'x.kt', count: 5 }] }],
  })
  const dup = await clusterRun({ s1: [{ locations: ['x.kt:1'] }], s2: [{ locations: ['y.kt:1'] }] }, { measured: both })
  assert.equal(dup.mergeCalls.length, 1)
  assert.ok(mentions(dup.mergeCalls[0].prompt, 's1-1') && mentions(dup.mergeCalls[0].prompt, 's2-1'))
  const lone = measuredFixture({ candidates: [], cycles: [], coupling: [{ path: 'x.kt', partners: [{ path: 'y.kt', count: 5 }] }] })
  const noPartner = await clusterRun({ s1: [{ locations: ['x.kt:1'] }], s3: [{ locations: ['z.kt:1'] }] }, { measured: lone })
  assert.equal(noPartner.mergeCalls.length, 0, 'the partner has no finding')
  const empty = measuredFixture({ candidates: [], cycles: [[]], coupling: [{ path: 'x.kt', partners: [] }] })
  const none = await clusterRun({ s1: [{ locations: ['x.kt:1'] }], s2: [{ locations: ['y.kt:1'] }] }, { measured: empty })
  assert.equal(none.mergeCalls.length, 0, 'an empty cycle and an empty partner list link nothing')
})

test('S7 (T11): a digest cycle with an empty candidate list links m/a and m/b findings, and the top-complexity module m/c stays out', async () => {
  const specs = { s1: [{ locations: ['m/a/1.kt:3'] }], s2: [{ locations: ['m/b/2.kt:3'] }], s3: [{ locations: ['m/c/4.kt:3'] }] }
  const measured = measuredFixture({
    candidates: [], cycles: [['m/a', 'm/b']], coupling: [],
    hotSet: [{ path: 'm/a/1.kt', churn: 1, churnRecent: 1, complexity: 1, hotspotScore: 0.1, module: 'm/a' }],
    topComplexity: [{ path: 'm/c/4.kt', complexity: 3, module: 'm/c' }],
    unstableModules: [],
  })
  const { mergeCalls } = await clusterRun(specs, { measured })
  assert.equal(mergeCalls.length, 1)
  assert.ok(mentions(mergeCalls[0].prompt, 's1-1') && mentions(mergeCalls[0].prompt, 's2-1'))
  assert.equal(mentions(mergeCalls[0].prompt, 's3-1'), false)
})

test('S8 (T11): the reviewer preamble and the review, gap and candidate prompts carry the location sentence verbatim', async () => {
  assert.ok(CORE.reviewerPreamble(buildPlan()).includes(LOCATION_SENTENCE), 'reviewerPreamble')
  const { calls } = await fullRun(fullArgs(withCandidates('c1')), fullScopeOpts())
  for (const prefix of ['review:', 'gap:', 'candidate:']) {
    const matching = calls.filter((c) => c.label.startsWith(prefix))
    assert.ok(matching.length > 0, 'fixture must run a ' + prefix + ' agent')
    for (const c of matching) assert.ok(c.prompt.includes(LOCATION_SENTENCE), c.label)
  }
})

// ── item 4f4001e9: oversized clusters split by path, location suffix forms, stable merged rows ──────────

const E_DASH = String.fromCharCode(0x2014)

test('A1: a cycle-linked cluster over 40 findings splits by path into more than one merge agent; no path is in two batches', async () => {
  const { mergeCalls, result } = await clusterRun(
    { sA: manyOn('m/a/1.kt', 25), sB: manyOn('m/b/2.kt', 20), sC: manyOn('m/a/3.kt', 10) },
    { measured: measuredFixture({ cycles: [['m/a', 'm/b']], hotSet: [], topComplexity: [], unstableModules: [], coupling: [], candidates: [] }) }
  )
  assert.deepEqual(mergeCalls.map((c) => c.label), ['merge:1', 'merge:2'])
  assert.ok(mentions(mergeCalls[0].prompt, 'sA-1') && mentions(mergeCalls[0].prompt, 'sA-25'))
  assert.equal(mentions(mergeCalls[0].prompt, 'sB-1'), false)
  assert.equal(mentions(mergeCalls[0].prompt, 'sC-1'), false)
  assert.ok(mentions(mergeCalls[1].prompt, 'sB-1') && mentions(mergeCalls[1].prompt, 'sC-10'))
  assert.equal(mentions(mergeCalls[1].prompt, 'sA-1'), false)
  assert.equal(result.stats.mergeAgents, 2)
})

test('A2: preCluster splits a cycle cluster of 45 + 5 findings on two paths: a single path stays whole, k is renumbered, no path is in two batches', () => {
  const rows = []
  for (let i = 1; i <= 45; i++) rows.push(rawFinding('a' + i, 's1', { locations: ['m/a/1.kt:' + i] }))
  for (let i = 1; i <= 5; i++) rows.push(rawFinding('b' + i, 's2', { locations: ['m/b/2.kt:' + i] }))
  const { clusters, batches } = CORE.preCluster(rows, { cycles: [['m/a', 'm/b']], hotSet: [{ module: 'm/a' }, { module: 'm/b' }] })
  assert.deepEqual(clusters.map((c) => c.k), [1, 2])
  assert.deepEqual(clusters.map((c) => c.ids.length), [45, 5])
  assert.equal(batches.length, 2)
  const pathsSeen = new Map()
  const byId = Object.fromEntries(rows.map((r) => [r.id, r]))
  batches.forEach((batch, bi) => {
    const ids = batch.flatMap((c) => c.ids)
    const paths = new Set(ids.map((id) => CORE.primaryPath(byId[id].locations)))
    assert.ok(ids.length <= 40 || paths.size === 1, 'a batch over 40 holds one path')
    for (const p of paths) { assert.equal(pathsSeen.has(p), false, p + ' is in one batch'); pathsSeen.set(p, bi) }
  })
})

test('A3: the workflow location grammar keeps a single space and cuts at a note separator', () => {
  assert.equal(CORE.primaryPath(['my dir/a b.kt:12']), 'my dir/a b.kt')
  assert.equal(CORE.primaryPath(['my dir/a b.kt (note)']), 'my dir/a b.kt')
  assert.equal(CORE.primaryPath(['src/a.kt:3 - see b/c.kt']), 'src/a.kt')
  assert.equal(CORE.primaryPath(['src/a.kt:3 ' + E_DASH + ' note']), 'src/a.kt')
  assert.equal(CORE.primaryPath(['src/a.kt:12 (7)']), 'src/a.kt', 'the note separator wins over a suffix')
})

test('A4: the workflow location grammar removes one #L or (digits) suffix', () => {
  for (const loc of ['src/a.kt(12)', 'src/a.kt(12,5)', 'src/a.kt(12-30)', 'src/a.kt#L5', 'src/a.kt#L5-L9', 'src/a.kt#L5-9']) {
    assert.equal(CORE.primaryPath([loc]), 'src/a.kt', loc)
  }
  assert.equal(CORE.primaryPath(['src/a.kt (12)']), 'src/a.kt', 'a space before the parenthesis makes it a note')
  assert.equal(CORE.primaryPath(['src/foo()/a.kt']), 'src/foo()/a.kt', 'an empty pair is not a suffix')
})

test('A5: stableIds orders a (7) suffix before a #L30 suffix by line', () => {
  const ids = CORE.stableIds([
    { category: 'coupling', locations: ['a.kt#L30'] },
    { category: 'coupling', locations: ['a.kt(7)'] },
  ])
  const base = stableIdOf('coupling', 'a.kt')
  assert.deepEqual(ids, [base + '-2', base])
})

test('A6: mergeFindings picks the canonical member by (category, primaryPath, first location), not by input order', () => {
  const x = rawFinding('x', 's1', { locations: ['src/z.kt:5'] })
  const y = rawFinding('y', 's2', { locations: ['src/a.kt:1'] })
  const group = [{ canonicalId: 'x', duplicateIds: ['y'], mergedTitle: '' }]
  const one = CORE.mergeFindings([x, y], group)[0]
  const two = CORE.mergeFindings([y, x], group)[0]
  for (const m of [one, two]) {
    assert.equal(m.id, 'y')
    assert.equal(m.locations[0], 'src/a.kt:1')
    assert.equal(m.sources[0], 's2')
    assert.deepEqual(m.memberIds, ['y', 'x'])
  }
  assert.deepEqual(CORE.stableIds([one]), CORE.stableIds([two]))
  const l = rawFinding('l', 's1', { category: 'layering', locations: ['a.kt:1'] })
  const c = rawFinding('c', 's2', { category: 'coupling', locations: ['z.kt:1'] })
  const byCat = CORE.mergeFindings([l, c], [{ canonicalId: 'l', duplicateIds: ['c'], mergedTitle: '' }])[0]
  assert.equal(byCat.id, 'c', 'coupling sorts before layering')
  assert.equal(byCat.category, 'coupling')
})

test('A7: two reviewer orderings of one merged group give the same findingId, first location and stableId', async () => {
  const measured = measuredFixture({ cycles: [], hotSet: [], topComplexity: [], unstableModules: [], candidates: [], coupling: [{ path: 'x.kt', partners: [{ path: 'y.kt', count: 4 }] }] })
  const specs = { s1: [{ locations: ['y.kt:1'] }], s2: [{ locations: ['x.kt:1'] }] }
  const mergeFor = () => dedupResult({ groups: [{ canonicalId: 's1-1', duplicateIds: ['s2-1'], mergedTitle: 'Merged' }] })
  for (const keys of [['s1', 's2'], ['s2', 's1']]) {
    const { result } = await clusterRun(specs, { measured, mergeFor, keys })
    assert.equal(result.kept.length, 1, keys.join())
    assert.equal(result.kept[0].findingId, 's2-1', keys.join())
    assert.equal(result.kept[0].locations[0], 'x.kt:1', keys.join())
    assert.equal(result.kept[0].stableId, stableIdOf('coupling', 'x.kt'), keys.join())
  }
})

test('A8: a finding in a nested module takes the nested id, so a cycle through the outer module alone does not link it', async () => {
  const measured = measuredFixture({ cycles: [['m', 'x']], hotSet: [], topComplexity: [], unstableModules: [{ id: 'm/a' }], coupling: [], candidates: [] })
  const { mergeCalls } = await clusterRun(
    { s1: [{ locations: ['m/a/1.kt:1'] }], s2: [{ locations: ['x/2.kt:1'] }], s3: [{ locations: ['m/3.kt:1'] }] },
    { measured }
  )
  assert.equal(mergeCalls.length, 1)
  assert.ok(mentions(mergeCalls[0].prompt, 's2-1') && mentions(mergeCalls[0].prompt, 's3-1'))
  assert.equal(mentions(mergeCalls[0].prompt, 's1-1'), false)
})
