// Blind-author test suite for workflows/review-wave.js (item faa8e3e8).
//
// BLINDNESS: this file was written WITHOUT ever opening review-wave.js with any tool. The
// script's core is reached only through loadCoreNamed (workflow-harness-ext.mjs), which
// evaluates the marker-delimited `@core-begin`/`@core-end` slice as a `new Function` body and
// returns only the requested names. Every oracle below cites the item's frozen `test-plan` note,
// the c1-dispatch-contract.md Appendix A frozen signatures (a planning artifact, not
// implementation source — self-resolved per test-author skill §8's non-src/main carve-out, see
// the arbitration notes inline), phase-c-workflows.md §1, .taskorchestrator/config.yaml, and
// .taskorchestrator/rules/{review-scoping,protocol.read-only-agent}.md.
//
// Scenario ids (S1-S14 + probes + T-*) match the item's `test-plan` note and c1-dispatch-contract
// Appendix A's RED-PROOF list.
//
// Access surface used: loadCoreNamed, autoAgent, scanForbiddenApis, freeRuntimeIds,
// ruleWindowHits (workflow-harness-ext.mjs) + scriptText, coreSlice, loadMeta, fakeParallel
// (workflow-harness.mjs). B1's loadCore/fakeAgent/planFixture/itemFixture/stages are
// implement-wave-specific and are never imported here. Args-v1 fixtures are hand-built literals.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'

import { scriptText, coreSlice, loadMeta, fakeParallel } from './workflow-harness.mjs'
import {
  loadCoreNamed,
  autoAgent,
  scanForbiddenApis,
  freeRuntimeIds,
  ruleWindowHits,
} from './workflow-harness-ext.mjs'

// ── Paths (cwd-independent) ─────────────────────────────────────────────────
const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN = resolve(HERE, '..', '..')
const REPO = resolve(PLUGIN, '..', '..')
const SCRIPT_PATH = join(PLUGIN, 'workflows', 'review-wave.js')
const RULES_DIR = join(REPO, '.taskorchestrator', 'rules')

// ── Core, loaded once by name (Appendix A "Core names for loadCoreNamed") ──
const CORE_NAMES = [
  'ENVELOPE_VERSION', 'VERDICTS', 'INDEPENDENCE_MODES', 'SIMPLIFY_ANGLES', 'BRANCH_SCOPED_SKILLS',
  'PROTOCOL_KEY', 'SCOPING_RULE_KEY', 'REQUIRED_FEATURES', 'REVIEW_OUTPUT_SCHEMAS',
  'normalizePath', 'normalizeArgs', 'preflight', 'laneActor', 'laneSchema', 'lanePrompt',
  'mapLaneResult', 'aggregateVerdict', 'runItem', 'runReview',
]
const core = loadCoreNamed(SCRIPT_PATH, CORE_NAMES)
const {
  ENVELOPE_VERSION, VERDICTS, INDEPENDENCE_MODES, SIMPLIFY_ANGLES, BRANCH_SCOPED_SKILLS,
  PROTOCOL_KEY, SCOPING_RULE_KEY, REQUIRED_FEATURES, REVIEW_OUTPUT_SCHEMAS,
  normalizePath, normalizeArgs, preflight, laneActor, laneSchema, lanePrompt,
  mapLaneResult, aggregateVerdict, runItem, runReview,
} = core

// ── Fixture builders (hand-built args-v1 literals; review-wave/args-v1 per
//    c1-dispatch-contract.md Appendix A) ────────────────────────────────────

function clone(x) {
  return structuredClone(x)
}

function validRawItem(short = 'aaaaaaaa', overrides = {}) {
  return {
    id: `item-${short}`,
    short,
    type: 'test-item',
    configFingerprint: 'fp-1',
    traits: [],
    worktree: `/tmp/wt-${short}`,
    stages: [
      {
        seat: 'simplify', lane: 'simplify', phase: 'review', notes: [], writes: false,
        protocol: 'protocol.read-only-agent', dispatch: {}, output: 'simplify-v1',
      },
      {
        seat: 'reviewer', lane: 'reviewer.review-quality', phase: 'review',
        notes: ['review-checklist', 'test-independence-audit'], writes: false,
        protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1',
        after: ['simplify'],
      },
    ],
    review: {
      ownedFiles: {
        implementer: ['workflows/review-wave.js'],
        testAuthor: ['scripts/tests/review-wave.test.mjs'],
        docs: [],
      },
      commits: { implementer: ['aaaaaaa1'], testAuthor: ['bbbbbbb2'], fixtureRepairs: [] },
      implementerActors: [`implementer:${short}:r-impl-0001`],
      testAuthorActors: [`test-author:${short}:r-impl-0001`],
      requiredReviewNotes: ['review-checklist', 'test-independence-audit'],
    },
    ...overrides,
  }
}

function validRawPlan(overrides = {}) {
  return {
    contract: 'review-wave/args-v1',
    runId: 'r-test-0001',
    rootId: 'root-test-0001',
    baseSha: 'e77c3e95',
    worktreeMode: 'shared',
    entryMode: 'pre-entered',
    capabilities: { features: ['seats', 'dispatchBySeat', 'rules'], phase0Hooks: true },
    items: [validRawItem()],
    deferred: [],
    ...overrides,
  }
}

/** normalizeArgs(raw) and assert it was accepted; returns the normalized plan. */
function normalize(raw) {
  const r = normalizeArgs(raw)
  assert.equal(r.ok, true, `expected normalizeArgs to accept fixture, got reason: ${r.reason}`)
  return r.plan
}

/** Build a valid raw plan, apply a mutator, normalize it, and return the plan. */
function planFrom(mutate) {
  const raw = validRawPlan()
  mutate(raw)
  return normalize(raw)
}

function passEnvelope(notesFilled, { verdict = 'pass', findings = [], independence } = {}) {
  return {
    status: 'done',
    reason: 'ok',
    notes: notesFilled.map((key) => ({ key, actor: 'reviewer:x:r-test-0001' })),
    commits: { pre: 'p', post: 'q' },
    files: [],
    modelReported: 'fake-model',
    entry: { alreadyInPhase: true, previousRole: 'review' },
    output: {
      verdict,
      findings,
      independence: independence || { mode: 'independent', violations: [], evidence: 'checked' },
      notesFilled,
    },
  }
}

function simplifyEnvelope(findings = []) {
  return {
    status: 'done',
    reason: 'ok',
    notes: [],
    commits: { pre: 'p', post: 'q' },
    files: [],
    modelReported: 'fake-model',
    entry: { alreadyInPhase: true, previousRole: 'review' },
    output: { findings },
  }
}

// =============================================================================
// T-meta / T-core-pure / T-schema — structural tests
// =============================================================================

test('T-meta: meta is the declared pure literal (name, description, whenToUse, single Review phase)', () => {
  const meta = loadMeta(SCRIPT_PATH)
  assert.equal(meta.name, 'review-wave')
  assert.equal(
    meta.description,
    'Runs independent, lane-derived review agents across a wave of MCP work items already in the review phase — validates lane coverage, aggregates verdicts, and guards reviewer independence.'
  )
  assert.equal(
    meta.whenToUse,
    'Never invoke this workflow bare. It is called by the review step of the front door with review-wave/args-v1; a bare or malformed call returns {started:false}.'
  )
  // loadMeta evaluates the meta literal in a separate vm context (a different JS realm), so its
  // plain objects are not deepStrictEqual-comparable against this realm's object literals even
  // when structurally identical (Node's deepStrictEqual also compares prototypes). Field-by-field
  // comparison sidesteps the realm mismatch while still pinning the exact declared shape.
  assert.equal(Array.isArray(meta.phases), true)
  assert.equal(meta.phases.length, 1)
  assert.equal(meta.phases[0].title, 'Review')
  assert.deepEqual(Object.keys(meta.phases[0]), ['title'])
})

test('T-meta: markers @core-begin/@core-end appear exactly once each', () => {
  const text = scriptText(SCRIPT_PATH)
  const beginMatches = text.match(/\/\/ @core-begin/g) || []
  const endMatches = text.match(/\/\/ @core-end/g) || []
  assert.equal(beginMatches.length, 1)
  assert.equal(endMatches.length, 1)
})

test('T-core-pure: no free runtime-global identifier (agent, parallel, pipeline, phase, log, args, budget, workflow) in the core region', () => {
  const hits = freeRuntimeIds(coreSlice(SCRIPT_PATH))
  assert.deepEqual(hits, [])
})

test('T-core-pure: the core region uses no forbidden non-determinism API (Date.now, Math.random, new Date(), import(), require())', () => {
  const hits = scanForbiddenApis(coreSlice(SCRIPT_PATH))
  assert.deepEqual(hits, [])
})

test('T-core-pure: the whole script also carries no forbidden non-determinism API', () => {
  const hits = scanForbiddenApis(scriptText(SCRIPT_PATH))
  assert.deepEqual(hits, [])
})

test('T-schema: constants match the declared literals verbatim', () => {
  assert.equal(ENVELOPE_VERSION, 'envelope-v1')
  assert.deepEqual(VERDICTS, ['pass', 'pass-with-observations', 'fail-blocking'])
  assert.deepEqual(INDEPENDENCE_MODES, ['independent', 'independent-degraded', 'not-independent', 'n/a'])
  assert.deepEqual(SIMPLIFY_ANGLES, ['reuse', 'simplification', 'efficiency', 'altitude'])
  assert.deepEqual(BRANCH_SCOPED_SKILLS, ['security-review'])
  assert.equal(PROTOCOL_KEY, 'protocol.read-only-agent')
  assert.equal(SCOPING_RULE_KEY, 'review-scoping')
  assert.deepEqual(REQUIRED_FEATURES, ['seats', 'dispatchBySeat', 'rules'])
})

test('T-schema: REVIEW_OUTPUT_SCHEMAS matches the declared review-v1/simplify-v1 literal verbatim', () => {
  const expected = {
    'review-v1': {
      type: 'object',
      required: ['verdict', 'findings', 'independence', 'notesFilled'],
      properties: {
        verdict: { enum: VERDICTS },
        findings: {
          type: 'array',
          items: {
            type: 'object',
            required: ['severity', 'confidence', 'file', 'line', 'expected', 'found'],
            properties: {
              severity: { enum: ['blocking', 'observation'] },
              confidence: { enum: ['high', 'medium', 'low'] },
              file: { type: 'string' },
              line: { type: 'integer' },
              expected: { type: 'string' },
              found: { type: 'string' },
            },
          },
        },
        independence: {
          type: 'object',
          required: ['mode', 'violations', 'evidence'],
          properties: {
            mode: { enum: INDEPENDENCE_MODES },
            violations: {
              type: 'array',
              items: {
                type: 'object',
                required: ['key', 'constraint'],
                properties: {
                  key: { type: 'string' }, seat: { type: 'string' },
                  constraint: { type: 'string' }, conflictingSeat: { type: 'string' },
                  waived: { type: 'boolean' },
                },
              },
            },
            evidence: { type: 'string' },
          },
        },
        notesFilled: { type: 'array', items: { type: 'string' } },
      },
    },
    'simplify-v1': {
      type: 'object',
      required: ['findings'],
      properties: {
        findings: {
          type: 'array',
          items: {
            type: 'object',
            required: ['file', 'line', 'angle', 'suggestion'],
            properties: {
              file: { type: 'string' }, line: { type: 'integer' },
              angle: { enum: SIMPLIFY_ANGLES }, suggestion: { type: 'string' },
            },
          },
        },
      },
    },
  }
  assert.deepEqual(REVIEW_OUTPUT_SCHEMAS, expected)
})

test('T-schema: laneSchema(outputId) required list, status enum, and entry shape (partial coverage — RW-P full byte-for-byte parity with implement-wave.js is orchestrator/reviewer-run per c1-dispatch-contract.md, the file is absent on this branch)', () => {
  for (const outputId of ['review-v1', 'simplify-v1']) {
    const schema = laneSchema(outputId)
    assert.equal(schema.type, 'object')
    assert.deepEqual(schema.required, ['status', 'reason', 'notes', 'commits', 'files', 'modelReported', 'output'])
    for (const r of schema.required) {
      assert.ok(r in schema.properties, `required "${r}" missing from properties`)
    }
    assert.deepEqual(schema.properties.status, { enum: ['done', 'stopped', 'deferred'] })
    assert.equal(schema.properties.entry.type, 'object')
    assert.deepEqual(schema.properties.entry.properties, {
      alreadyInPhase: { type: 'boolean' },
      previousRole: { type: 'string' },
    })
    assert.deepEqual(schema.properties.output, REVIEW_OUTPUT_SCHEMAS[outputId])
    // Presence-only for the B1-shared fields (their exact nested shape is B1 §5.1, not read here).
    for (const key of ['notes', 'commits', 'files', 'rulesFetched']) {
      assert.notEqual(schema.properties[key], undefined, `properties.${key} missing`)
    }
  }
})

test('laneActor(stage, item, plan) returns the declared id/kind/parent shape', () => {
  const plan = planFrom(() => {})
  const item = plan.items[0]
  const stage = item.stages[1] // reviewer.review-quality
  const actor = laneActor(stage, item, plan)
  assert.deepEqual(actor, {
    id: 'reviewer.review-quality:aaaaaaaa:r-test-0001',
    kind: 'subagent',
    parent: 'workflow:r-test-0001',
  })
})

// =============================================================================
// S1 — RW1: a mixed run dispatches exactly the args lanes per item
// Oracle: test-plan S1 ("2 items, simplify+reviewer.review-quality (+reviewer.security-review on
// one) -> labels exactly {<lane>:<short>}, opts.phase 'Review', no isolation"); P §1.2 L1-L7, §1.4.
// =============================================================================

test('S1: runReview dispatches exactly the args-declared lanes, one call per lane per item, label={lane}:{short}, phase Review, no isolation, dispatch fields propagate', async () => {
  const itemA = validRawItem('aaaaaaaa')
  const itemB = validRawItem('bbbbbbbb', {
    stages: [
      { seat: 'simplify', lane: 'simplify', phase: 'review', notes: [], writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'simplify-v1' },
      { seat: 'reviewer', lane: 'reviewer.review-quality', phase: 'review', notes: ['review-checklist', 'test-independence-audit'], writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1', after: ['simplify'] },
      { seat: 'reviewer', lane: 'reviewer.security-review', phase: 'review', notes: ['security-assessment'], writes: false, protocol: 'protocol.read-only-agent', dispatch: { model: 'opus', effort: 'high' }, output: 'review-v1', skills: ['security-review'] },
    ],
    review: {
      ownedFiles: { implementer: ['workflows/review-wave.js'], testAuthor: [], docs: [] },
      commits: { implementer: ['ccccccc3'], testAuthor: [], fixtureRepairs: [] },
      implementerActors: ['implementer:bbbbbbbb:r-impl-0002'],
      testAuthorActors: ['test-author:bbbbbbbb:r-impl-0002'],
      requiredReviewNotes: ['review-checklist', 'test-independence-audit', 'security-assessment'],
    },
  })
  const plan = normalize(validRawPlan({ items: [itemA, itemB] }))

  const expectedLabels = new Set([
    'simplify:aaaaaaaa', 'reviewer.review-quality:aaaaaaaa',
    'simplify:bbbbbbbb', 'reviewer.review-quality:bbbbbbbb', 'reviewer.security-review:bbbbbbbb',
  ])
  const notesByLabel = {
    'reviewer.review-quality:aaaaaaaa': ['review-checklist', 'test-independence-audit'],
    'reviewer.review-quality:bbbbbbbb': ['review-checklist', 'test-independence-audit'],
    'reviewer.security-review:bbbbbbbb': ['security-assessment'],
  }
  const responder = async (label) => {
    if (label.startsWith('simplify:')) return simplifyEnvelope([])
    return passEnvelope(notesByLabel[label])
  }
  const { agent, calls } = autoAgent(responder)

  const result = await runReview(plan, { agent, parallel: fakeParallel, log: () => {} })

  assert.equal(calls.length, 5, `expected exactly 5 lane calls, got labels: ${calls.map((c) => c.label).join(',')}`)
  const gotLabels = new Set(calls.map((c) => c.label))
  assert.deepEqual(gotLabels, expectedLabels)
  for (const call of calls) {
    assert.equal(call.opts.phase, 'Review', `call ${call.label} phase`)
    assert.equal(
      Object.prototype.hasOwnProperty.call(call.opts, 'isolation'), false,
      `call ${call.label} must never set opts.isolation`
    )
  }
  const securityCall = calls.find((c) => c.label === 'reviewer.security-review:bbbbbbbb')
  assert.equal(securityCall.opts.model, 'opus')
  assert.equal(securityCall.opts.effort, 'high')

  assert.equal(result.items.length, 2)
  for (const item of result.items) {
    assert.equal(item.status, 'done', `item ${item.short} status`)
    assert.equal(item.verdict, 'pass', `item ${item.short} verdict`)
  }
})

// =============================================================================
// S2 — RW1: the script text carries no item-type name and no review-note key.
// Oracle: test-plan S2 (config.yaml:19,43,67,100,110,123 type names;
// :35,91,136,177,187,197,259,287,167,207 note keys); P §1.2; contract Appendix A MARKERS.
// =============================================================================

test('S2: script text contains no item-type-name string', () => {
  const text = scriptText(SCRIPT_PATH)
  const typeNames = ['feature-implementation', 'feature-task', 'bug-fix', 'plugin-change', 'quick-fix', 'improvement-proposal']
  for (const name of typeNames) {
    assert.equal(text.includes(name), false, `script text must not contain type name "${name}"`)
  }
})

test('S2: script text contains no review-note-key string', () => {
  const text = scriptText(SCRIPT_PATH)
  const noteKeys = [
    'review-checklist', 'test-independence-audit', 'security-assessment', 'api-compatibility',
    'plugin-impact', 'migration-assessment', 'performance-baseline', 'outcome-verification',
  ]
  for (const key of noteKeys) {
    assert.equal(text.includes(key), false, `script text must not contain note key "${key}"`)
  }
})

// =============================================================================
// S3 — RW2: the primary lane starts only after simplify settles and carries its findings.
// Oracle: test-plan S3; P §1.3.
// =============================================================================

test('S3a: primary lane call starts (t0) after simplify lane call settles (t1); non-primary-eligible lanes start before simplify resolves', async () => {
  const plan = planFrom(() => {})
  const item = plan.items[0]
  const simplifyFindings = [{ file: 'a.js', line: 10, angle: 'simplification', suggestion: 'use const here' }]
  const responder = async (label) => {
    if (label === 'simplify:aaaaaaaa') return simplifyEnvelope(simplifyFindings)
    if (label === 'reviewer.review-quality:aaaaaaaa') return passEnvelope(['review-checklist', 'test-independence-audit'])
    throw new Error(`unexpected label ${label}`)
  }
  const { agent, calls } = autoAgent(responder)
  await runItem(plan, item, { agent, log: () => {} })

  const simplifyCall = calls.find((c) => c.label === 'simplify:aaaaaaaa')
  const primaryCall = calls.find((c) => c.label === 'reviewer.review-quality:aaaaaaaa')
  assert.ok(simplifyCall && primaryCall, 'both calls must have happened')
  assert.ok(primaryCall.t0 > simplifyCall.t1, `primary t0 (${primaryCall.t0}) must be after simplify t1 (${simplifyCall.t1})`)
})

test('S3b: the primary lane prompt carries the SIMPLIFY LANE FINDINGS hand-off with the suggestion text when simplify returns findings', async () => {
  const plan = planFrom(() => {})
  const item = plan.items[0]
  const suggestion = 'inline the single-use helper into its caller'
  const responder = async (label) => {
    if (label === 'simplify:aaaaaaaa') return simplifyEnvelope([{ file: 'a.js', line: 5, angle: 'simplification', suggestion }])
    if (label === 'reviewer.review-quality:aaaaaaaa') return passEnvelope(['review-checklist', 'test-independence-audit'])
    throw new Error(`unexpected label ${label}`)
  }
  const { agent, calls } = autoAgent(responder)
  await runItem(plan, item, { agent, log: () => {} })

  const primaryCall = calls.find((c) => c.label === 'reviewer.review-quality:aaaaaaaa')
  assert.ok(primaryCall.prompt.includes('SIMPLIFY LANE FINDINGS'), 'prompt must carry the hand-off header')
  assert.ok(primaryCall.prompt.includes(suggestion), 'prompt must carry the simplify finding suggestion text')
})

test('S3c: when simplify returns null, the primary lane still runs and its prompt says "simplify lane produced nothing"', async () => {
  const plan = planFrom(() => {})
  const item = plan.items[0]
  const responder = async (label) => {
    if (label === 'simplify:aaaaaaaa') return null
    if (label === 'reviewer.review-quality:aaaaaaaa') return passEnvelope(['review-checklist', 'test-independence-audit'])
    throw new Error(`unexpected label ${label}`)
  }
  const { agent, calls } = autoAgent(responder)
  const result = await runItem(plan, item, { agent, log: () => {} })

  const primaryCall = calls.find((c) => c.label === 'reviewer.review-quality:aaaaaaaa')
  assert.ok(primaryCall, 'primary lane must still be called when simplify is null')
  assert.ok(primaryCall.prompt.includes('simplify lane produced nothing'))
  assert.equal(result.status, 'done')
  assert.equal(result.verdict, 'pass')
})

// =============================================================================
// S4 — aggregateVerdict verdict-table rows (pure function, no agent needed).
// Oracle: test-plan S4; c1-dispatch-contract.md Appendix A aggregateVerdict description.
// =============================================================================

test('S4: all lanes done/pass -> done/pass/"review passed"', () => {
  const result = aggregateVerdict([
    { lane: 'a', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'pass' },
    { lane: 'b', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'pass' },
  ])
  assert.deepEqual(result, { status: 'done', verdict: 'pass', reason: 'review passed' })
})

test('S4: one lane pass-with-observations -> done/pass-with-observations/"review passed with observations"', () => {
  const result = aggregateVerdict([
    { lane: 'a', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'pass' },
    { lane: 'b', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'pass-with-observations' },
  ])
  assert.deepEqual(result, { status: 'done', verdict: 'pass-with-observations', reason: 'review passed with observations' })
})

test('S4: two fail-blocking lanes -> stopped/fail-blocking/"review-fail a,b" in args order', () => {
  const result = aggregateVerdict([
    { lane: 'a', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'fail-blocking' },
    { lane: 'b', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'fail-blocking' },
  ])
  assert.deepEqual(result, { status: 'stopped', verdict: 'fail-blocking', reason: 'review-fail a,b' })
})

test('S4: a fail-blocking lane plus a stopped lane -> stopped/fail-blocking/"review-fail <only the fail lane>"', () => {
  const result = aggregateVerdict([
    { lane: 'a', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'fail-blocking' },
    { lane: 'b', output: 'review-v1', status: 'stopped', reason: 'not in review', verdict: null },
  ])
  assert.deepEqual(result, { status: 'stopped', verdict: 'fail-blocking', reason: 'review-fail a' })
})

test('S4: a single stopped lane with a generic reason -> stopped/null/"<lane> <reason>"', () => {
  const result = aggregateVerdict([
    { lane: 'reviewer.review-quality', output: 'review-v1', status: 'stopped', reason: 'agent returned null', verdict: null },
  ])
  assert.deepEqual(result, { status: 'stopped', verdict: null, reason: 'reviewer.review-quality agent returned null' })
})

test('S4: reason "schema-changed" is used verbatim, not lane-prefixed', () => {
  const result = aggregateVerdict([
    { lane: 'x', output: 'review-v1', status: 'stopped', reason: 'schema-changed', verdict: null },
  ])
  assert.deepEqual(result, { status: 'stopped', verdict: null, reason: 'schema-changed' })
})

test('S4: reason "config-unavailable" -> deferred, reason verbatim', () => {
  const result = aggregateVerdict([
    { lane: 'x', output: 'review-v1', status: 'deferred', reason: 'config-unavailable', verdict: null },
  ])
  assert.deepEqual(result, { status: 'deferred', verdict: null, reason: 'config-unavailable' })
})

test('S4: a reason starting with "note " is used verbatim, not lane-prefixed', () => {
  const result = aggregateVerdict([
    { lane: 'reviewer.review-quality', output: 'review-v1', status: 'stopped', reason: 'note review-checklist missing', verdict: null },
  ])
  assert.deepEqual(result, { status: 'stopped', verdict: null, reason: 'note review-checklist missing' })
})

test('S4: no review-v1 lanes considered (only a simplify-v1 lane present) -> stopped/null/"no review lanes"', () => {
  const result = aggregateVerdict([
    { lane: 'simplify', output: 'simplify-v1', status: 'done', reason: 'ok', verdict: null },
  ])
  assert.deepEqual(result, { status: 'stopped', verdict: null, reason: 'no review lanes' })
})

test('S4: an empty lane-results array -> stopped/null/"no review lanes"', () => {
  const result = aggregateVerdict([])
  assert.deepEqual(result, { status: 'stopped', verdict: null, reason: 'no review lanes' })
})

test('S4: a stopped simplify-v1 lane is never considered — a passing review-v1 lane alongside it still yields done/pass', () => {
  const result = aggregateVerdict([
    { lane: 'simplify', output: 'simplify-v1', status: 'stopped', reason: 'agent returned null', verdict: null },
    { lane: 'reviewer.review-quality', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'pass' },
  ])
  assert.deepEqual(result, { status: 'done', verdict: 'pass', reason: 'review passed' })
})

test('S4: a "skipped" lane is ignored entirely, not treated as stopped — a passing sibling lane still yields done/pass', () => {
  const result = aggregateVerdict([
    { lane: 'a', output: 'review-v1', status: 'skipped', reason: 'skipped after x', verdict: null },
    { lane: 'b', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'pass' },
  ])
  assert.deepEqual(result, { status: 'done', verdict: 'pass', reason: 'review passed' })
})

test('S4: a "skipped" lane is excluded from the fail-blocking lane list even when a sibling did fail', () => {
  const result = aggregateVerdict([
    { lane: 'a', output: 'review-v1', status: 'skipped', reason: 'skipped after b', verdict: null },
    { lane: 'b', output: 'review-v1', status: 'done', reason: 'ok', verdict: 'fail-blocking' },
  ])
  assert.deepEqual(result, { status: 'stopped', verdict: 'fail-blocking', reason: 'review-fail b' })
})

// =============================================================================
// S5 — mapLaneResult precedence rows (pure function, no agent needed).
// Oracle: test-plan S5; c1-dispatch-contract.md Appendix A mapLaneResult description;
// review-quality/SKILL.md:199-207 (independent-degraded stays pass; not-independent is blocking).
// =============================================================================

const stageV1 = { output: 'review-v1', notes: ['review-checklist', 'test-independence-audit'] }

test('S5: env null -> stopped "agent returned null", verdict null', () => {
  const result = mapLaneResult(stageV1, null)
  assert.deepEqual(result, { status: 'stopped', reason: 'agent returned null', verdict: null })
})

test('S5: reason "schema-changed" takes precedence over everything else -> stopped, verdict null', () => {
  const env = { status: 'done', reason: 'schema-changed', entry: null, notes: [], commits: {}, files: [], modelReported: 'm', output: {} }
  const result = mapLaneResult(stageV1, env)
  assert.equal(result.status, 'stopped')
  assert.equal(result.reason, 'schema-changed')
  assert.equal(result.verdict, null)
})

test('S5: reason "config-unavailable" -> deferred, verdict null', () => {
  const env = { status: 'done', reason: 'config-unavailable', entry: { alreadyInPhase: true }, notes: [], commits: {}, files: [], modelReported: 'm', output: {} }
  const result = mapLaneResult(stageV1, env)
  assert.equal(result.status, 'deferred')
  assert.equal(result.reason, 'config-unavailable')
  assert.equal(result.verdict, null)
})

test('S5: missing entry / alreadyInPhase !== true -> stopped "not in review", verdict null', () => {
  const envMissingEntry = { status: 'done', reason: 'ok', notes: [], commits: {}, files: [], modelReported: 'm', output: passEnvelope(stageV1.notes).output }
  const r1 = mapLaneResult(stageV1, envMissingEntry)
  assert.deepEqual(r1, { status: 'stopped', reason: 'not in review', verdict: null })

  const envFalseEntry = { ...envMissingEntry, entry: { alreadyInPhase: false } }
  const r2 = mapLaneResult(stageV1, envFalseEntry)
  assert.deepEqual(r2, { status: 'stopped', reason: 'not in review', verdict: null })
})

test('S5: env.status stopped/deferred -> that status, env.reason passed through, verdict null', () => {
  const envStopped = { status: 'stopped', reason: 'boom', entry: { alreadyInPhase: true }, notes: [], commits: {}, files: [], modelReported: 'm', output: passEnvelope(stageV1.notes).output }
  const r1 = mapLaneResult(stageV1, envStopped)
  assert.deepEqual(r1, { status: 'stopped', reason: 'boom', verdict: null })

  const envDeferred = { ...envStopped, status: 'deferred', reason: 'later' }
  const r2 = mapLaneResult(stageV1, envDeferred)
  assert.deepEqual(r2, { status: 'deferred', reason: 'later', verdict: null })
})

test('S5: the FIRST stage.notes key absent from output.notesFilled -> stopped "note <key> missing", verdict null', () => {
  const env1 = passEnvelope(['test-independence-audit']) // missing 'review-checklist' (first in stage.notes)
  const r1 = mapLaneResult(stageV1, env1)
  assert.deepEqual(r1, { status: 'stopped', reason: 'note review-checklist missing', verdict: null })

  const stageAB = { output: 'review-v1', notes: ['a', 'b'] }
  const env2 = passEnvelope([])
  const r2 = mapLaneResult(stageAB, env2)
  assert.deepEqual(r2, { status: 'stopped', reason: 'note a missing', verdict: null })
})

test('S5: independence.mode "not-independent" forces verdict fail-blocking even when output.verdict is pass', () => {
  const env = passEnvelope(stageV1.notes, {
    verdict: 'pass',
    independence: { mode: 'not-independent', violations: [{ key: 'k', constraint: 'c' }], evidence: 'e' },
  })
  const result = mapLaneResult(stageV1, env)
  assert.equal(result.status, 'done')
  assert.equal(result.verdict, 'fail-blocking')
})

test('S5: independence.mode "independent-degraded" does NOT change the verdict — pass stays pass (review-quality SKILL.md:199-203)', () => {
  const env = passEnvelope(stageV1.notes, {
    verdict: 'pass',
    independence: { mode: 'independent-degraded', violations: [], evidence: 'temporal-only separation' },
  })
  const result = mapLaneResult(stageV1, env)
  assert.equal(result.status, 'done')
  assert.equal(result.verdict, 'pass')
})

test('S5: a simplify-v1 stage maps to done with verdict null regardless of output.verdict-shaped fields', () => {
  const stageSimplify = { output: 'simplify-v1', notes: [] }
  const env = simplifyEnvelope([{ file: 'a.js', line: 1, angle: 'reuse', suggestion: 'x' }])
  const result = mapLaneResult(stageSimplify, env)
  assert.equal(result.status, 'done')
  assert.equal(result.reason, 'ok')
  assert.equal(result.verdict, null)
})

// =============================================================================
// S6 — RW3/RW8: a non-primary lane's fail-blocking result settles before simplify (order
// 'reverse'); the item halts, the primary lane is skipped without an agent call, and a sibling
// item streams independently to done.
// Oracle: test-plan S6; P §1.5 row 3 ("fail skips unstarted lanes"); §1.3 (no cross-item edges).
// =============================================================================

test('S6: a non-primary fail-blocking lane halts the item before simplify settles; the primary lane is skipped with 0 calls; a sibling item completes independently', async () => {
  const itemA = validRawItem('aaaaaaaa', {
    stages: [
      { seat: 'simplify', lane: 'simplify', phase: 'review', notes: [], writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'simplify-v1' },
      { seat: 'reviewer', lane: 'reviewer.review-quality', phase: 'review', notes: ['review-checklist', 'test-independence-audit'], writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1', after: ['simplify'] },
      { seat: 'reviewer', lane: 'reviewer.security-review', phase: 'review', notes: ['security-assessment'], writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1', skills: ['security-review'] },
    ],
    review: {
      ownedFiles: { implementer: ['workflows/review-wave.js'], testAuthor: [], docs: [] },
      commits: { implementer: ['ccccccc3'], testAuthor: [], fixtureRepairs: [] },
      implementerActors: ['implementer:aaaaaaaa:r-impl-0002'],
      testAuthorActors: ['test-author:aaaaaaaa:r-impl-0002'],
      requiredReviewNotes: ['review-checklist', 'test-independence-audit', 'security-assessment'],
    },
  })
  const itemB = validRawItem('bbbbbbbb')
  const plan = normalize(validRawPlan({ items: [itemA, itemB] }))

  const responder = async (label) => {
    if (label === 'reviewer.review-quality:aaaaaaaa') {
      throw new Error('primary lane for item A must never be called once a non-primary lane has already halted the item')
    }
    if (label === 'reviewer.security-review:aaaaaaaa') {
      return passEnvelope(['security-assessment'], { verdict: 'fail-blocking' })
    }
    if (label === 'simplify:aaaaaaaa') return simplifyEnvelope([])
    if (label === 'simplify:bbbbbbbb') return simplifyEnvelope([])
    if (label === 'reviewer.review-quality:bbbbbbbb') return passEnvelope(['review-checklist', 'test-independence-audit'])
    throw new Error(`unexpected label ${label}`)
  }
  // 'reverse' resolves calls queued in the same synchronous burst LIFO: since simplify:aaaaaaaa
  // and reviewer.security-review:aaaaaaaa are both dispatched concurrently (neither has `after`),
  // and security-review is issued second, it settles FIRST under 'reverse' — exactly the ordering
  // this scenario needs (non-primary settles before simplify).
  const { agent, calls } = autoAgent(responder, { order: 'reverse' })

  const result = await runReview(plan, { agent, parallel: fakeParallel, log: () => {} })

  const primaryCalls = calls.filter((c) => c.label === 'reviewer.review-quality:aaaaaaaa')
  assert.equal(primaryCalls.length, 0, 'primary lane for item A must have 0 calls')

  const itemAResult = result.items.find((i) => i.short === 'aaaaaaaa')
  assert.equal(itemAResult.status, 'stopped')
  assert.equal(itemAResult.verdict, 'fail-blocking')
  assert.equal(itemAResult.reason, 'review-fail reviewer.security-review')
  const primaryLaneResult = itemAResult.lanes.find((l) => l.lane === 'reviewer.review-quality')
  assert.equal(primaryLaneResult.status, 'skipped')
  assert.equal(primaryLaneResult.reason, 'skipped after reviewer.security-review')

  const itemBResult = result.items.find((i) => i.short === 'bbbbbbbb')
  assert.equal(itemBResult.status, 'done')
  assert.equal(itemBResult.verdict, 'pass')
})

// =============================================================================
// Follow-up (coordinator, post-S6): lane results must reach aggregateVerdict — and
// result.lanes must be reported — in ARGS order, never completion order, regardless of which
// lane's agent() call settles first.
// Oracle: c1-dispatch-contract.md Appendix A — "aggregateVerdict(laneResults) (args order; ...)"
// and "runItem(...) -> {..., lanes:[args order], ...}". Two independent (no `after`) review-v1
// lanes, A (args-first, stopped) and B (args-second, fail-blocking); autoAgent's `order` option
// is used to force B to settle BEFORE A under 'reverse' while A settles first under 'fifo'. If
// runItem/aggregateVerdict correctly use args order rather than completion order, the item's
// status/verdict/reason and the args-order of result.lanes must be byte-identical between the
// two runs.
// =============================================================================

function twoLaneNoAfterItem(short = 'aaaaaaaa') {
  return validRawItem(short, {
    stages: [
      { seat: 'reviewer', lane: 'reviewer.lane-a', phase: 'review', notes: ['review-checklist'], writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1' },
      { seat: 'reviewer', lane: 'reviewer.lane-b', phase: 'review', notes: ['test-independence-audit'], writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1' },
    ],
  })
}

async function runTwoLaneOrdered(order) {
  const item = twoLaneNoAfterItem('aaaaaaaa')
  const plan = normalize(validRawPlan({ items: [item] }))
  const responder = async (label) => {
    if (label === 'reviewer.lane-a:aaaaaaaa') return null // maps to stopped "agent returned null"
    if (label === 'reviewer.lane-b:aaaaaaaa') return passEnvelope(['test-independence-audit'], { verdict: 'fail-blocking' })
    throw new Error(`unexpected label ${label}`)
  }
  const { agent, calls } = autoAgent(responder, { order })
  const result = await runItem(plan, plan.items[0], { agent, log: () => {} })
  return { result, calls }
}

test('aggregation is args-ordered, not completion-ordered: lane A (args-first, stopped) and lane B (args-second, fail-blocking) give an identical item status/verdict/reason, and result.lanes stays in args order, under both order:"fifo" and order:"reverse"', async () => {
  const fifo = await runTwoLaneOrdered('fifo')
  const reverse = await runTwoLaneOrdered('reverse')

  // Sanity: under 'reverse', lane B (issued second, in the same concurrent burst since neither
  // lane has `after`) must actually settle BEFORE lane A — otherwise this fixture would not
  // exercise a real completion-order difference and the assertions below would pass vacuously.
  const aCall = reverse.calls.find((c) => c.label === 'reviewer.lane-a:aaaaaaaa')
  const bCall = reverse.calls.find((c) => c.label === 'reviewer.lane-b:aaaaaaaa')
  assert.ok(aCall && bCall, 'both lane calls must have happened under reverse ordering')
  assert.ok(bCall.t1 < aCall.t1, `sanity check failed: under order:"reverse" lane B must settle (t1=${bCall.t1}) before lane A (t1=${aCall.t1})`)

  for (const [label, run] of [['fifo', fifo], ['reverse', reverse]]) {
    assert.equal(run.result.status, 'stopped', `${label}: item status`)
    assert.equal(run.result.verdict, 'fail-blocking', `${label}: item verdict`)
    assert.equal(run.result.reason, 'review-fail reviewer.lane-b', `${label}: item reason must name only the fail-blocking lane, independent of completion order`)
    assert.deepEqual(
      run.result.lanes.map((l) => l.lane),
      ['reviewer.lane-a', 'reviewer.lane-b'],
      `${label}: result.lanes must be reported in args order regardless of which lane settled first`
    )
  }

  // The two runs must be indistinguishable at the item-result level: completion order is not an
  // observable input to aggregateVerdict or to how result.lanes is assembled.
  assert.equal(fifo.result.status, reverse.result.status)
  assert.equal(fifo.result.verdict, reverse.result.verdict)
  assert.equal(fifo.result.reason, reverse.result.reason)
  assert.deepEqual(fifo.result.lanes.map((l) => l.lane), reverse.result.lanes.map((l) => l.lane))
})

// =============================================================================
// runItem observations aggregation (declared runItem return shape: observations[] collects
// review-v1 findings whose severity is 'observation', tagged with their lane, in args order).
// Oracle: c1-dispatch-contract.md Appendix A runItem return-shape description.
// =============================================================================

test('runItem: observations[] collects only severity="observation" findings from review-v1 lanes, tagged with lane, excluding "blocking" findings', async () => {
  const item = validRawItem('aaaaaaaa', {
    stages: [
      { seat: 'reviewer', lane: 'reviewer.review-quality', phase: 'review', notes: ['review-checklist', 'test-independence-audit'], writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1' },
    ],
  })
  const plan = normalize(validRawPlan({ items: [item] }))
  const findings = [
    { severity: 'blocking', confidence: 'high', file: 'x.js', line: 1, expected: 'e1', found: 'f1' },
    { severity: 'observation', confidence: 'low', file: 'b.js', line: 2, expected: 'p', found: 'q' },
    { severity: 'observation', confidence: 'medium', file: 'c.js', line: 3, expected: 'r', found: 's' },
  ]
  const responder = async () => passEnvelope(['review-checklist', 'test-independence-audit'], { verdict: 'pass-with-observations', findings })
  const { agent } = autoAgent(responder)

  const result = await runItem(plan, plan.items[0], { agent, log: () => {} })

  assert.deepEqual(result.observations, [
    { lane: 'reviewer.review-quality', severity: 'observation', confidence: 'low', file: 'b.js', line: 2, expected: 'p', found: 'q' },
    { lane: 'reviewer.review-quality', severity: 'observation', confidence: 'medium', file: 'c.js', line: 3, expected: 'r', found: 's' },
  ])
})

// =============================================================================
// S7 — RW4: preflight refusal reasons, each isolated to a single triggering condition.
// Oracle: test-plan S7; c1-dispatch-contract.md Appendix A preflight() description
// ("first failing wins, in order").
// =============================================================================

test('S7: entryMode "seat" is refused by normalizeArgs itself, not preflight', () => {
  const raw = validRawPlan({ entryMode: 'seat' })
  const r = normalizeArgs(raw)
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'invalid args: entryMode must be pre-entered')
})

test('S7: relative worktree -> "worktree not absolute"', () => {
  const plan = planFrom((raw) => { raw.items[0].worktree = 'relative/wt' })
  const { runnable, refused } = preflight(plan)
  assert.deepEqual(runnable, [])
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'worktree not absolute' }])
})

test('S7 probe: a Windows absolute worktree "D:\\wt" normalizes to an accepted absolute path', () => {
  const plan = planFrom((raw) => { raw.items[0].worktree = 'D:\\wt' })
  assert.equal(plan.items[0].worktree, 'd:/wt')
  const { runnable, refused } = preflight(plan)
  assert.deepEqual(refused, [])
  assert.equal(runnable.length, 1)
})

test('S7: a bare relative worktree "wt" is refused', () => {
  const plan = planFrom((raw) => { raw.items[0].worktree = 'wt' })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'worktree not absolute' }])
})

test('S7: a non-empty item.waitsFor -> "waitsFor not allowed in review-wave" (an empty array is a documented no-op, verified separately below)', () => {
  const plan = planFrom((raw) => { raw.items[0].waitsFor = [{ item: 'x', milestone: 'y' }] })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'waitsFor not allowed in review-wave' }])
})

test('S7 probe: an empty item.waitsFor ([]) is accepted — only a non-empty waitsFor is refused', () => {
  const plan = planFrom((raw) => { raw.items[0].waitsFor = [] })
  const { runnable, refused } = preflight(plan)
  assert.deepEqual(refused, [])
  assert.equal(runnable.length, 1)
})

test('S7: a stage carrying extraLockKeys -> "extraLockKeys not allowed in review-wave"', () => {
  const plan = planFrom((raw) => { raw.items[0].stages[0].extraLockKeys = ['k'] })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'extraLockKeys not allowed in review-wave' }])
})

test('S7: a stage carrying readsExclude -> "readsExclude not allowed in review-wave"', () => {
  const plan = planFrom((raw) => { raw.items[0].stages[1].readsExclude = ['implementation-notes'] })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'readsExclude not allowed in review-wave' }])
})

test('S7: two stages sharing the same lane id -> "duplicate lane <lane>"', () => {
  const plan = planFrom((raw) => {
    raw.items[0].stages.push({
      seat: 'reviewer', lane: 'reviewer.review-quality', phase: 'review', notes: ['some-other-key'],
      writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1',
    })
    raw.items[0].review.requiredReviewNotes = ['review-checklist', 'test-independence-audit', 'some-other-key']
  })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'duplicate lane reviewer.review-quality' }])
})

test('S7: no review-v1 stage at all -> "no review lanes"', () => {
  const plan = planFrom((raw) => {
    raw.items[0].stages = [
      { seat: 'simplify', lane: 'simplify', phase: 'review', notes: [], writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'simplify-v1' },
    ]
    raw.items[0].review.requiredReviewNotes = []
  })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'no review lanes' }])
})

test('S7: mixed-case lane id in `after` does not case-insensitively match — "lane <lane> after unknown lane <x>" (exact match only)', () => {
  const plan = planFrom((raw) => { raw.items[0].stages[1].after = ['Simplify'] })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'lane reviewer.review-quality after unknown lane Simplify' }])
})

test('S7: a simplify-v1 lane with non-empty notes -> "simplify lane <lane> owns notes"', () => {
  const plan = planFrom((raw) => { raw.items[0].stages[0].notes = ['review-checklist'] })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'simplify lane simplify owns notes' }])
})

test('S7: a review-v1 lane with empty notes -> "lane <lane> owns no notes"', () => {
  const plan = planFrom((raw) => { raw.items[0].stages[1].notes = [] })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'lane reviewer.review-quality owns no notes' }])
})

test('S7: a lane note key not in requiredReviewNotes ∪ optionalReviewNotes -> "lane <lane> note <key> is not a review note"', () => {
  const plan = planFrom((raw) => {
    raw.items[0].stages[1].notes = ['review-checklist', 'test-independence-audit', 'bogus-key']
  })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'lane reviewer.review-quality note bogus-key is not a review note' }])
})

test('S7: the same note key claimed by two lanes -> "note <key> in multiple lanes"', () => {
  const plan = planFrom((raw) => {
    raw.items[0].stages[1].notes = ['review-checklist']
    raw.items[0].stages.push({
      seat: 'reviewer', lane: 'reviewer.other', phase: 'review', notes: ['review-checklist', 'test-independence-audit'],
      writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1',
    })
  })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'note review-checklist in multiple lanes' }])
})

test('S7: an uncovered required review note -> "uncovered required review note(s) <keys>" in requiredReviewNotes order', () => {
  const plan = planFrom((raw) => {
    raw.items[0].stages[1].notes = ['review-checklist']
    raw.items[0].review.requiredReviewNotes = ['review-checklist', 'test-independence-audit']
  })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'uncovered required review note(s) test-independence-audit' }])
})

test('S7 probe: requiredReviewNotes=[] with a lane covering only an optional note key preflights clean', () => {
  const plan = planFrom((raw) => {
    raw.items[0].stages[1].notes = ['review-checklist']
    raw.items[0].review.requiredReviewNotes = []
    raw.items[0].review.optionalReviewNotes = ['review-checklist']
  })
  const { runnable, refused } = preflight(plan)
  assert.deepEqual(refused, [])
  assert.equal(runnable.length, 1)
})

test('S7 probe: optionalReviewNotes absent from raw defaults to [] on the normalized plan, same as an explicit []', () => {
  const planAbsent = planFrom(() => {})
  const planExplicit = planFrom((raw) => { raw.items[0].review.optionalReviewNotes = [] })
  assert.deepEqual(planAbsent.items[0].review.optionalReviewNotes, [])
  assert.deepEqual(planExplicit.items[0].review.optionalReviewNotes, [])
})

// =============================================================================
// S8 — reviewer-actor-matches-implementer/test-author-actor refusals.
// Oracle: test-plan S8; P §1.6; c1-dispatch-contract.md Appendix A preflight() description.
// =============================================================================

test('S8: implementerActors contains a lane\'s computed actor id -> "reviewer would match implementer actor"', () => {
  const plan = planFrom((raw) => {
    raw.items[0].review.implementerActors = ['reviewer.review-quality:aaaaaaaa:r-test-0001']
  })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'reviewer would match implementer actor' }])
})

test('S8: testAuthorActors contains a lane\'s computed actor id (and implementerActors does not) -> "reviewer would match test-author actor"', () => {
  const plan = planFrom((raw) => {
    raw.items[0].review.implementerActors = ['implementer:aaaaaaaa:r-impl-0001']
    raw.items[0].review.testAuthorActors = ['reviewer.review-quality:aaaaaaaa:r-test-0001']
  })
  const { refused } = preflight(plan)
  assert.deepEqual(refused, [{ id: plan.items[0].id, reason: 'reviewer would match test-author actor' }])
})

// =============================================================================
// S9 — RW5: lane prompt content (diff command, keys=, READ-ONLY, protocol key, actor id,
// no <sha>..<sha> content diff, no advance_item/manage_items, simplify forbids /simplify).
// Direct lanePrompt() calls always use a stage WITHOUT `after`, so the (undeclared) shape of the
// `outs` hand-off parameter never needs to be guessed — passing outs={} is always safe here.
// Oracle: test-plan S9; .taskorchestrator/rules/review-scoping.md:24-27; P §1.4; F7, F8.
// =============================================================================

function promptFor(mutateRaw, stageIndex = 1) {
  const plan = planFrom(mutateRaw)
  const item = plan.items[0]
  const stage = item.stages[stageIndex]
  return { plan, item, stage, prompt: lanePrompt(plan, item, stage, {}) }
}

test('S9: the diff line is "DIFF <group>: git -C <wt> diff <baseSha>..HEAD -- <files>" per non-empty ownedFiles group', () => {
  const { prompt, item } = promptFor(() => {})
  assert.ok(prompt.includes(`git -C ${item.worktree} diff e77c3e95..HEAD -- workflows/review-wave.js`))
  assert.ok(prompt.includes('DIFF implementer:'))
  assert.ok(prompt.includes(`git -C ${item.worktree} diff e77c3e95..HEAD -- scripts/tests/review-wave.test.mjs`))
  assert.ok(prompt.includes('DIFF testAuthor:'))
})

test('S9: no prompt contains a <sha>..<sha> content-diff form (only <baseSha>..HEAD is used)', () => {
  const { prompt } = promptFor(() => {})
  assert.equal(/[0-9a-f]{7,40}\.\.[0-9a-f]{7,40}/.test(prompt), false)
})

test('S9: the prompt instructs "Always pass keys= on every query_notes call."', () => {
  const { prompt } = promptFor(() => {})
  assert.ok(prompt.includes('keys='))
})

test('S9: the prompt states the READ-ONLY lane boundary verbatim', () => {
  const { prompt } = promptFor(() => {})
  assert.ok(prompt.includes('READ-ONLY lane: edit no file, commit nothing.'))
})

test('S9: the prompt names protocol.read-only-agent as the fetched protocol key', () => {
  const { prompt } = promptFor(() => {})
  assert.ok(prompt.includes('protocol.read-only-agent'))
})

test('S9: the prompt embeds the lane actor id and "workflow:<runId>" as its parent', () => {
  const { prompt, plan } = promptFor(() => {})
  assert.ok(prompt.includes('reviewer.review-quality:aaaaaaaa:r-test-0001'))
  assert.ok(prompt.includes(`workflow:${plan.runId}`))
})

function toolsLineOf(prompt) {
  const line = prompt.split('\n').find((l) => l.startsWith('TOOLS:'))
  assert.ok(line, 'prompt must contain a TOOLS: line')
  return line
}

test('S9: the review-v1 TOOLS: select list never names advance_item or manage_items as an available tool (the prompt separately and correctly PROHIBITS advance_item by name elsewhere, in its ENTRY section — verified not to be a false positive: "Never call advance_item." is present outside the TOOLS: line)', () => {
  const { prompt } = promptFor(() => {})
  const toolsLine = toolsLineOf(prompt)
  assert.equal(toolsLine.includes('advance_item'), false)
  assert.equal(toolsLine.includes('manage_items'), false)
  assert.equal(prompt.includes('manage_items'), false, 'manage_items must not appear anywhere in the prompt')
  assert.ok(prompt.includes('Never call advance_item.'), 'the prompt explicitly prohibits advance_item by name outside the tool list')
})

test('S9: a review-v1 lane with non-empty notes includes manage_notes in its tool list', () => {
  const { prompt } = promptFor(() => {})
  assert.ok(prompt.includes('manage_notes'))
})

test('S9: the simplify lane prompt forbids invoking /simplify, has no manage_notes in its TOOLS: list, and its TOOLS: list never names advance_item/manage_items', () => {
  const { prompt } = promptFor(() => {}, 0)
  assert.ok(prompt.includes('Do not invoke /simplify'))
  const toolsLine = toolsLineOf(prompt)
  assert.equal(toolsLine.includes('manage_notes'), false)
  assert.equal(toolsLine.includes('advance_item'), false)
  assert.equal(toolsLine.includes('manage_items'), false)
  assert.equal(prompt.includes('manage_notes'), false, 'manage_notes must not appear anywhere in a notes-less lane prompt')
  assert.equal(prompt.includes('manage_items'), false, 'manage_items must not appear anywhere in the prompt')
})

test('S9: DECLARED EXCEPTIONS text is carried into a review-v1 lane prompt when non-empty', () => {
  const { prompt } = promptFor((raw) => { raw.items[0].review.declaredExceptions = 'legacy formatting kept per arbitration' })
  assert.ok(prompt.includes('DECLARED EXCEPTIONS: legacy formatting kept per arbitration'))
})

test('S9: a BOUNDARY line is emitted per listed implementer/test-author commit for a review-v1 lane', () => {
  const { prompt, item } = promptFor(() => {})
  assert.ok(prompt.includes(`BOUNDARY: git -C ${item.worktree} show --stat aaaaaaa1`))
  assert.ok(prompt.includes(`BOUNDARY: git -C ${item.worktree} show --stat bbbbbbb2`))
})

test('S9 probe (W7 replay): calling lanePrompt twice with identical inputs yields byte-identical output', () => {
  const plan = planFrom(() => {})
  const item = plan.items[0]
  const stage = item.stages[1]
  const p1 = lanePrompt(plan, item, stage, {})
  const p2 = lanePrompt(plan, item, stage, {})
  assert.equal(p1, p2)
})

// =============================================================================
// S10 — the security-review lane (a BRANCH_SCOPED_SKILLS member, or branchScoped:true) gets the
// BRANCH-SCOPED instruction + record-filter line; other lanes lack it.
// Oracle: test-plan S10; P OQ-5, R6; .taskorchestrator/config.yaml:202 (needs-security-review
// guidance: "record the inputs tried" / filter-and-record pattern).
// =============================================================================

test('S10: a lane whose skills include "security-review" gets the BRANCH-SCOPED instruction and the record-filter line', () => {
  const plan = planFrom((raw) => {
    raw.items[0].stages.push({
      seat: 'reviewer', lane: 'reviewer.security-review', phase: 'review', notes: ['security-assessment'],
      writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1', skills: ['security-review'],
    })
    raw.items[0].review.requiredReviewNotes.push('security-assessment')
  })
  const item = plan.items[0]
  const stage = item.stages[2]
  const prompt = lanePrompt(plan, item, stage, {})
  assert.ok(prompt.includes('BRANCH-SCOPED'))
  assert.ok(prompt.includes('findings total, kept, dropped, dropped files'))
})

test('S10: stage.branchScoped === true also triggers the BRANCH-SCOPED instruction without security-review in skills', () => {
  const plan = planFrom((raw) => {
    raw.items[0].stages[1].branchScoped = true
  })
  const item = plan.items[0]
  const stage = item.stages[1]
  const prompt = lanePrompt(plan, item, stage, {})
  assert.ok(prompt.includes('BRANCH-SCOPED'))
})

test('S10: a lane without security-review in skills and branchScoped unset never gets the BRANCH-SCOPED instruction', () => {
  const { prompt } = promptFor(() => {})
  assert.equal(prompt.includes('BRANCH-SCOPED'), false)
})

// =============================================================================
// S11 — RW6: no rule text. ruleWindowHits over the whole script text vs every rules/*.md file;
// the check is guarded against a vacuously-empty rules directory.
// Oracle: test-plan S11; b1-dispatch-contract.md Appendix A S12 (rules-text scan pattern).
// =============================================================================

test('S11: review-wave.js script text contains no contiguous 8-token window from any .taskorchestrator/rules/*.md file', () => {
  const text = scriptText(SCRIPT_PATH)
  const { hits, windows } = ruleWindowHits(text, RULES_DIR)
  assert.deepEqual(hits, [])
  assert.ok(windows > 0, 'expected at least one rule window to compare against — a vacuous (empty) rules dir must not silently pass')
})

test('S11 probe: ruleWindowHits against an empty rules directory reports windows=0 (the vacuity condition the windows>0 guard above catches)', () => {
  const emptyDir = mkdtempSync(join(tmpdir(), 'rw-rules-empty-'))
  const { hits, windows } = ruleWindowHits('some target text that would otherwise be scanned', emptyDir)
  assert.deepEqual(hits, [])
  assert.equal(windows, 0)
})

// =============================================================================
// S12 — RW7: config-unavailable -> deferred; agentType throw -> one retry without agentType,
// agentTypeFallback flagged true; a second throw (or a throw with no agentType) -> stopped
// "agent threw: <msg>".
// Oracle: test-plan S12; b1-dispatch-contract.md §4.6 (retry-once pattern); Appendix A runItem.
// =============================================================================

function singleLaneItem(short, overrides = {}) {
  return validRawItem(short, {
    stages: [
      { seat: 'reviewer', lane: 'reviewer.review-quality', phase: 'review', notes: ['review-checklist', 'test-independence-audit'], writes: false, protocol: 'protocol.read-only-agent', dispatch: {}, output: 'review-v1' },
    ],
    ...overrides,
  })
}

test('S12: env.reason "config-unavailable" maps the lane and the item to deferred', async () => {
  const item = singleLaneItem('aaaaaaaa')
  const plan = normalize(validRawPlan({ items: [item] }))
  const responder = async () => ({
    status: 'stopped', reason: 'config-unavailable', notes: [], commits: { pre: '', post: '' }, files: [],
    modelReported: 'm', entry: { alreadyInPhase: true, previousRole: 'review' }, output: {},
  })
  const { agent } = autoAgent(responder)
  const result = await runItem(plan, plan.items[0], { agent, log: () => {} })
  assert.equal(result.status, 'deferred')
  assert.equal(result.reason, 'config-unavailable')
  const lane = result.lanes.find((l) => l.lane === 'reviewer.review-quality')
  assert.equal(lane.status, 'deferred')
})

test('S12: an agentType throw retries once WITHOUT agentType and flags agentTypeFallback true on success', async () => {
  const item = singleLaneItem('aaaaaaaa', {}) // default dispatch: {} -> agent: undefined => no agentType by default
  item.stages[0].dispatch = { agent: 'task-orchestrator:reviewer' }
  const plan = normalize(validRawPlan({ items: [item] }))

  let callCount = 0
  const responder = async (label, prompt, opts) => {
    callCount += 1
    if (callCount === 1) {
      assert.equal(opts.agentType, 'task-orchestrator:reviewer', 'first attempt must carry agentType')
      throw new Error('agent unavailable')
    }
    assert.equal(callCount, 2, 'must not be called a third time')
    assert.equal(Object.prototype.hasOwnProperty.call(opts, 'agentType'), false, 'retry must omit agentType')
    return passEnvelope(['review-checklist', 'test-independence-audit'])
  }
  const { agent } = autoAgent(responder)
  const result = await runItem(plan, plan.items[0], { agent, log: () => {} })

  assert.equal(callCount, 2)
  const lane = result.lanes.find((l) => l.lane === 'reviewer.review-quality')
  assert.equal(lane.status, 'done')
  assert.equal(lane.agentTypeFallback, true)
})

test('S12: a second throw after the agentType retry -> stopped "agent threw: <msg>"', async () => {
  const item = singleLaneItem('aaaaaaaa')
  item.stages[0].dispatch = { agent: 'task-orchestrator:reviewer' }
  const plan = normalize(validRawPlan({ items: [item] }))

  const responder = async () => { throw new Error('still unavailable') }
  const { agent } = autoAgent(responder)
  const result = await runItem(plan, plan.items[0], { agent, log: () => {} })

  const lane = result.lanes.find((l) => l.lane === 'reviewer.review-quality')
  assert.equal(lane.status, 'stopped')
  assert.equal(lane.reason, 'agent threw: still unavailable')
})

test('S12: a throw with no agentType (dispatch.agent absent) stops immediately with 1 call, no retry', async () => {
  const item = singleLaneItem('aaaaaaaa')
  item.stages[0].dispatch = {}
  const plan = normalize(validRawPlan({ items: [item] }))

  let callCount = 0
  const responder = async (label, prompt, opts) => {
    callCount += 1
    assert.equal(Object.prototype.hasOwnProperty.call(opts, 'agentType'), false)
    throw new Error('no agent available')
  }
  const { agent } = autoAgent(responder)
  const result = await runItem(plan, plan.items[0], { agent, log: () => {} })

  assert.equal(callCount, 1)
  const lane = result.lanes.find((l) => l.lane === 'reviewer.review-quality')
  assert.equal(lane.status, 'stopped')
  assert.equal(lane.reason, 'agent threw: no agent available')
})

// =============================================================================
// S13 — RW-P (laneSchema structural parity) — see the T-schema laneSchema test above, which
// covers this scenario's assertions (required list, status enum, entry shape, output mapping,
// required⊆properties). Kept as its own reference here for the S-id -> test mapping.
// =============================================================================

test('S13: laneSchema required list is a subset of its own properties for both output ids (required⊆properties)', () => {
  function checkRequiredSubset(schema, path = 'schema') {
    if (!schema || typeof schema !== 'object') return
    if (schema.type === 'object') {
      const required = schema.required || []
      const props = schema.properties || {}
      for (const r of required) assert.ok(r in props, `${path}: required "${r}" not in properties`)
      for (const [key, sub] of Object.entries(props)) checkRequiredSubset(sub, `${path}.${key}`)
    }
    if (schema.type === 'array' && schema.items) checkRequiredSubset(schema.items, `${path}[]`)
  }
  checkRequiredSubset(laneSchema('review-v1'))
  checkRequiredSubset(laneSchema('simplify-v1'))
})

// =============================================================================
// S14 — meta name/phases, freeRuntimeIds, scanForbiddenApis, no repo strings, JSON-string args ==
// object args, result contract id, deferred passed through verbatim.
// Oracle: test-plan S14; W1/W6/W11; P §1.1.
// =============================================================================

test('S14: script text contains no repo-identifying string', () => {
  const text = scriptText(SCRIPT_PATH)
  assert.equal(text.includes('current/src'), false)
  assert.equal(text.includes('D:/'), false)
  assert.equal(text.includes('D:\\'), false)
  assert.equal(text.includes('mcptask'), false)
  assert.equal(text.includes('jpicklyk'), false)
  assert.equal(text.includes('claude-plugins/'), false)
})

test('S14: normalizeArgs accepts a JSON string identically to the equivalent object', () => {
  const raw = validRawPlan()
  const fromObject = normalizeArgs(raw)
  const fromString = normalizeArgs(JSON.stringify(raw))
  assert.equal(fromObject.ok, true)
  assert.equal(fromString.ok, true)
  assert.deepEqual(fromString.plan, fromObject.plan)
})

test('S14: runReview result carries contract "review-wave/result-v1" and passes plan.deferred through verbatim', async () => {
  const plan = normalize(validRawPlan({ deferred: [{ id: 'zzzzzzzz', reason: 'blocked upstream' }] }))
  const responder = async (label) => {
    if (label.startsWith('simplify:')) return simplifyEnvelope([])
    return passEnvelope(['review-checklist', 'test-independence-audit'])
  }
  const { agent } = autoAgent(responder)
  const result = await runReview(plan, { agent, parallel: fakeParallel, log: () => {} })
  assert.equal(result.contract, 'review-wave/result-v1')
  assert.equal(result.started, true)
  assert.equal(result.runId, plan.runId)
  assert.deepEqual(result.deferred, [{ id: 'zzzzzzzz', reason: 'blocked upstream' }])
})

test('S14: runReview.items lists only runnable items, in args order, with refused items separated into result.refused', async () => {
  const runnableA = validRawItem('aaaaaaaa')
  const refusedMiddle = validRawItem('deadbeef', { worktree: 'relative/wt' })
  const runnableC = validRawItem('cccccccc')
  const plan = normalize(validRawPlan({ items: [runnableA, refusedMiddle, runnableC] }))

  const responder = async (label) => {
    if (label.startsWith('simplify:')) return simplifyEnvelope([])
    return passEnvelope(['review-checklist', 'test-independence-audit'])
  }
  const { agent } = autoAgent(responder)
  const result = await runReview(plan, { agent, parallel: fakeParallel, log: () => {} })

  assert.deepEqual(result.items.map((i) => i.short), ['aaaaaaaa', 'cccccccc'])
  assert.deepEqual(result.refused, [{ id: refusedMiddle.id, reason: 'worktree not absolute' }])
})

// =============================================================================
// T-args — normalizeArgs: every declared reason string, in precedence order.
// Oracle: c1-dispatch-contract.md Appendix A normalizeArgs() reason-string list, self-resolved
// per test-author skill §8 (a planning artifact citable as public non-src/main evidence; the
// c1-dispatch-contract explicitly states test-plan "compiles against THESE" signatures).
// =============================================================================

function expectInvalid(label, mutate, expectedReason) {
  test(`T-args: ${label} -> "${expectedReason}"`, () => {
    const raw = validRawPlan()
    mutate(raw)
    const r = normalizeArgs(raw)
    assert.equal(r.ok, false)
    assert.equal(r.reason, expectedReason)
  })
}

test('T-args: malformed JSON string -> "invalid args: bad JSON"', () => {
  const r = normalizeArgs('{not valid json')
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'invalid args: bad JSON')
})

test('T-args: a JSON-valid but non-object top level (boolean) -> "invalid args: not an object"', () => {
  const r = normalizeArgs('true')
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'invalid args: not an object')
})

test('T-args: a JSON-valid but non-object top level (number) -> "invalid args: not an object"', () => {
  const r = normalizeArgs('42')
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'invalid args: not an object')
})

test('T-args: an entirely empty object reports the FIRST missing top-level field in order ("contract")', () => {
  const r = normalizeArgs({})
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'invalid args: missing contract')
})

expectInvalid('missing contract', (raw) => { delete raw.contract }, 'invalid args: missing contract')
expectInvalid('missing runId', (raw) => { delete raw.runId }, 'invalid args: missing runId')
expectInvalid('missing rootId', (raw) => { delete raw.rootId }, 'invalid args: missing rootId')
expectInvalid('missing baseSha', (raw) => { delete raw.baseSha }, 'invalid args: missing baseSha')
expectInvalid('missing worktreeMode', (raw) => { delete raw.worktreeMode }, 'invalid args: missing worktreeMode')
expectInvalid('missing entryMode', (raw) => { delete raw.entryMode }, 'invalid args: missing entryMode')
expectInvalid('missing capabilities', (raw) => { delete raw.capabilities }, 'invalid args: missing capabilities')
expectInvalid('missing items', (raw) => { delete raw.items }, 'invalid args: missing items')

expectInvalid('bad contract', (raw) => { raw.contract = 'implement-wave/args-v1' }, 'invalid args: bad contract')
expectInvalid('bad runId (no r- prefix / bad chars)', (raw) => { raw.runId = 'bad id' }, 'invalid args: bad runId')
expectInvalid('bad rootId (wrong type)', (raw) => { raw.rootId = 123 }, 'invalid args: bad rootId')
expectInvalid('bad baseSha (non-hex)', (raw) => { raw.baseSha = 'zzzzzzz' }, 'invalid args: bad baseSha')
expectInvalid('bad worktreeMode', (raw) => { raw.worktreeMode = 'exclusive' }, 'invalid args: bad worktreeMode')
expectInvalid('entryMode must be pre-entered', (raw) => { raw.entryMode = 'seat' }, 'invalid args: entryMode must be pre-entered')
expectInvalid('bad capabilities (features wrong type)', (raw) => { raw.capabilities = { features: 'not-an-array', phase0Hooks: true } }, 'invalid args: bad capabilities')
expectInvalid('bad capabilities (phase0Hooks wrong type)', (raw) => { raw.capabilities = { features: ['seats', 'dispatchBySeat', 'rules'], phase0Hooks: 'yes' } }, 'invalid args: bad capabilities')
expectInvalid('items empty array', (raw) => { raw.items = [] }, 'invalid args: items must be a non-empty array')
expectInvalid('items not an array', (raw) => { raw.items = 'x' }, 'invalid args: items must be a non-empty array')

expectInvalid('item missing short (id present)', (raw) => { delete raw.items[0].short }, 'invalid args: item missing short')
expectInvalid('item missing id AND short reports "id" first', (raw) => { delete raw.items[0].id; delete raw.items[0].short }, 'invalid args: item missing id')
expectInvalid('bad short (not 8 hex chars)', (raw) => { raw.items[0].short = 'nothex!!' }, 'invalid args: bad short')
expectInvalid('bad traits (not an array)', (raw) => { raw.items[0].traits = 'x' }, 'invalid args: bad traits')
expectInvalid('bad itemTraits (not an array)', (raw) => { raw.items[0].itemTraits = 'not-an-array' }, 'invalid args: bad itemTraits')
expectInvalid('item stages empty array', (raw) => { raw.items[0].stages = [] }, 'invalid args: item stages must be a non-empty array')
expectInvalid('review missing a required field', (raw) => { delete raw.items[0].review.commits }, 'invalid args: review missing commits')

expectInvalid('stage missing lane (seat present) reports "lane"', (raw) => { delete raw.items[0].stages[0].lane }, 'invalid args: stage missing lane')
expectInvalid('stage missing seat AND lane reports "seat" first', (raw) => { delete raw.items[0].stages[0].seat; delete raw.items[0].stages[0].lane }, 'invalid args: stage missing seat')
expectInvalid('bad stage phase', (raw) => { raw.items[0].stages[0].phase = 'work' }, 'invalid args: bad stage phase')
expectInvalid('stage writes must be false', (raw) => { raw.items[0].stages[0].writes = true }, 'invalid args: stage writes must be false')
expectInvalid('bad stage protocol', (raw) => { raw.items[0].stages[0].protocol = 'protocol.in-phase-seat' }, 'invalid args: bad stage protocol')
expectInvalid('bad stage output', (raw) => { raw.items[0].stages[0].output = 'bogus-v1' }, 'invalid args: bad stage output')

test('T-args probe: itemTraits with non-string elements is accepted (only array-ness is checked, not each element\'s type)', () => {
  const raw = validRawPlan()
  raw.items[0].itemTraits = [1, 2]
  const r = normalizeArgs(raw)
  assert.equal(r.ok, true, r.reason)
})

expectInvalid('server lacks seats', (raw) => { raw.capabilities.features = ['dispatchBySeat', 'rules'] }, 'server lacks seats; front door must use its fallback')
expectInvalid('server lacks dispatchBySeat', (raw) => { raw.capabilities.features = ['seats', 'rules'] }, 'server lacks dispatchBySeat; front door must use its fallback')
expectInvalid('server lacks rules', (raw) => { raw.capabilities.features = ['seats', 'dispatchBySeat'] }, 'server lacks rules; front door must use its fallback')

// ── normalizePath (identical transformation rules to B1's) ─────────────────

test('normalizePath: backslashes become forward slashes, leading "./" is stripped, drive letter is lowercased', () => {
  assert.equal(normalizePath('C:\\a\\b'), 'c:/a/b')
  assert.equal(normalizePath('./relative/path'), 'relative/path')
  assert.equal(normalizePath('/already/absolute'), '/already/absolute')
})

// ── plan defaults: optionalReviewNotes / declaredExceptions / deferred ─────

test('T-args: default values apply when optional args-v1 fields are absent (review.optionalReviewNotes, review.declaredExceptions, plan.deferred)', () => {
  const raw = validRawPlan()
  delete raw.deferred
  delete raw.items[0].review.optionalReviewNotes
  delete raw.items[0].review.declaredExceptions
  const plan = normalize(raw)
  assert.deepEqual(plan.deferred, [])
  assert.deepEqual(plan.items[0].review.optionalReviewNotes, [])
  assert.equal(plan.items[0].review.declaredExceptions, '')
})
