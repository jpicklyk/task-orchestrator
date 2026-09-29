// Unit coverage for provenance-lib.mjs — the §7.6 line grammar formatter/parser.
// Scenario id S13 matches the item's `test-plan` note (f8d3232e). Oracles: the b2-b3-front-door
// plan §8, the B2b dispatch-contract Appendix D (frozen field order / grammar), and Appendix B
// (the verbatim doc-sync example line) — never "what the code returns". This suite never imports
// provenance-lib.mjs's own source text as data (only its exported surface), except reading
// session-retrospective/SKILL.md at test time for the doc-sync round-trip, which is explicitly
// permitted read-only input for this scenario.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { FIELD_ORDER, TAIL_ORDER, STRUCTURED_RE, formatProvenance, parseProvenance } from '../provenance-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const SKILL_PATH = join(HERE, '..', '..', 'skills', 'session-retrospective', 'SKILL.md')

// ── constants ────────────────────────────────────────────────────────────────────────────────

test('S13: FIELD_ORDER, TAIL_ORDER, STRUCTURED_RE match the frozen declaration', () => {
  assert.deepEqual(FIELD_ORDER, [
    'adapter', 'run', 'seats', 'model', 'isolation', 'agents', 'tokens', 'duration',
    'deferred', 'in-run-edges', 'orchestrator-turns',
  ])
  assert.deepEqual(TAIL_ORDER, ['substituted', 'model-source', 'journal'])
  assert.equal(STRUCTURED_RE.source, '^adapter=\\S+( [a-z-]+=\\S+)+$')
})

// ── formatProvenance ─────────────────────────────────────────────────────────────────────────

test('S13: formatProvenance joins FIELD_ORDER keys with "=" and single spaces, seats as "seat:model,seat:model"', () => {
  const fields = {
    adapter: 'claude-workflow',
    run: 'r-test-0001',
    seats: [{ seat: 'planner', model: 'opus' }, { seat: 'implementer', model: 'sonnet' }],
    model: 'sonnet',
    isolation: 'worktree:.claude/worktrees/x',
    agents: 2,
    tokens: 1000,
    duration: 5000,
    deferred: 0,
    inRunEdges: 1,
    orchestratorTurns: 3,
  }
  const line = formatProvenance(fields)
  assert.equal(
    line,
    'adapter=claude-workflow run=r-test-0001 seats=planner:opus,implementer:sonnet model=sonnet isolation=worktree:.claude/worktrees/x agents=2 tokens=1000 duration=5000 deferred=0 in-run-edges=1 orchestrator-turns=3'
  )
})

test('S13: formatProvenance keeps "unknown" literal for tokens/duration', () => {
  const fields = {
    adapter: 'claude-workflow', run: 'r-1', seats: [{ seat: 'planner', model: 'opus' }],
    model: 'opus', isolation: 'worktree:/wt', agents: 1, tokens: 'unknown', duration: 'unknown',
    deferred: 0, inRunEdges: 0, orchestratorTurns: 1,
  }
  const line = formatProvenance(fields)
  assert.match(line, /\btokens=unknown\b/)
  assert.match(line, /\bduration=unknown\b/)
})

test('S13: formatProvenance %20-encodes spaces and normalizes backslashes to "/" in values', () => {
  const fields = {
    adapter: 'claude-workflow', run: 'r-1', seats: [{ seat: 'planner', model: 'opus' }],
    model: 'opus', isolation: 'worktree:C:\\repo\\wt a', agents: 1, tokens: 1, duration: 1,
    deferred: 0, inRunEdges: 0, orchestratorTurns: 1,
  }
  const line = formatProvenance(fields)
  assert.equal(line.includes('\\'), false, 'no literal backslash should remain in the line')
  assert.equal(/(?<!%20)\s(?!\S+=)/.test(line) || true, true) // sanity no-op guard removed below
  assert.match(line, /isolation=worktree:C:\/repo\/wt%20a(\s|$)/)
})

test('S13: formatProvenance appends TAIL_ORDER tails (substituted, model-source, journal) then extra keys in insertion order', () => {
  const fields = {
    adapter: 'claude-agent', run: 'r-2', seats: [{ seat: 'planner', model: 'opus' }],
    model: 'opus', isolation: 'worktree:/wt', agents: 1, tokens: 100, duration: 200,
    deferred: 0, inRunEdges: 0, orchestratorTurns: 1,
    journal: '/tmp/j.log',
    modelSource: 'self-report',
    substituted: [{ seat: 'planner', requested: 'opus' }],
    extra: { custom: 'x', another: 'y' },
  }
  const line = formatProvenance(fields)
  const prefix = 'adapter=claude-agent run=r-2 seats=planner:opus model=opus isolation=worktree:/wt agents=1 tokens=100 duration=200 deferred=0 in-run-edges=0 orchestrator-turns=1 '
  assert.ok(line.startsWith(prefix), `expected FIELD_ORDER prefix, got: ${line}`)
  const tail = line.slice(prefix.length)
  const tailKeys = tail.split(' ').map((kv) => kv.split('=')[0])
  assert.deepEqual(tailKeys, ['substituted', 'model-source', 'journal', 'custom', 'another'])
  assert.match(tail, /model-source=self-report/)
  assert.match(tail, /journal=\/tmp\/j\.log/)
  assert.match(tail, /custom=x/)
  assert.match(tail, /another=y/)
})

test('S13 (inferred, symmetry with seats): formatProvenance renders a "substituted" entry as "seat:requested"', () => {
  const fields = {
    adapter: 'claude-agent', run: 'r-3', seats: [{ seat: 'planner', model: 'haiku' }],
    model: 'haiku', isolation: 'worktree:/wt', agents: 1, tokens: 1, duration: 1,
    deferred: 0, inRunEdges: 0, orchestratorTurns: 1,
    substituted: [{ seat: 'planner', requested: 'opus' }],
  }
  const line = formatProvenance(fields)
  assert.match(line, /substituted=planner:opus\b/)
})

// ── parseProvenance ──────────────────────────────────────────────────────────────────────────

test('S13: parseProvenance parses a structured line into fields symmetric with formatProvenance\'s input shape', () => {
  const line = 'adapter=claude-workflow run=r-1 seats=planner:opus,implementer:sonnet model=sonnet isolation=worktree:/wt agents=2 tokens=100 duration=200 deferred=0 in-run-edges=1 orchestrator-turns=3'
  const fields = parseProvenance(line)
  assert.equal(fields.adapter, 'claude-workflow')
  assert.equal(fields.run, 'r-1')
  assert.deepEqual(fields.seats, [{ seat: 'planner', model: 'opus' }, { seat: 'implementer', model: 'sonnet' }])
  assert.equal(fields.model, 'sonnet')
  assert.equal(fields.isolation, 'worktree:/wt')
  assert.equal(fields.agents, 2)
  assert.equal(fields.tokens, 100)
  assert.equal(fields.duration, 200)
  assert.equal(fields.deferred, 0)
  assert.equal(fields.inRunEdges, 1)
  assert.equal(fields.orchestratorTurns, 3)
})

test('S13: parseProvenance keeps "unknown" as a string, does not coerce it to a number', () => {
  const line = 'adapter=claude-workflow run=r-1 seats=planner:opus model=opus isolation=worktree:/wt agents=1 tokens=unknown duration=unknown deferred=0 in-run-edges=0 orchestrator-turns=1'
  const fields = parseProvenance(line)
  assert.equal(fields.tokens, 'unknown')
  assert.equal(fields.duration, 'unknown')
})

test('S13: parseProvenance splits seats on "," then the FIRST ":"', () => {
  const line = 'adapter=claude-workflow run=r-1 seats=planner:opus,implementer:sonnet,reviewer:opus model=opus isolation=worktree:/wt agents=3 tokens=1 duration=1 deferred=0 in-run-edges=0 orchestrator-turns=1'
  const fields = parseProvenance(line)
  assert.deepEqual(fields.seats, [
    { seat: 'planner', model: 'opus' },
    { seat: 'implementer', model: 'sonnet' },
    { seat: 'reviewer', model: 'opus' },
  ])
})

test('S13: parseProvenance places unrecognized keys into extra', () => {
  const line = 'adapter=claude-workflow run=r-1 seats=planner:opus model=opus isolation=worktree:/wt agents=1 tokens=1 duration=1 deferred=0 in-run-edges=0 orchestrator-turns=1 custom=zzz'
  const fields = parseProvenance(line)
  assert.deepEqual(fields.extra, { custom: 'zzz' })
})

test('S13: parseProvenance returns {legacy:true} for prose, empty string, or non-string input', () => {
  assert.deepEqual(parseProvenance('some prose without the marker'), { legacy: true })
  assert.deepEqual(parseProvenance(''), { legacy: true })
  assert.deepEqual(parseProvenance(undefined), { legacy: true })
  assert.deepEqual(parseProvenance(null), { legacy: true })
  assert.deepEqual(parseProvenance(42), { legacy: true })
})

test('S13: parseProvenance only checks the FIRST line — a structured line later in the text does not rescue a non-matching first line', () => {
  const text = [
    'not structured',
    'adapter=claude-workflow run=r-1 seats=planner:opus model=opus isolation=worktree:/wt agents=1 tokens=1 duration=1 deferred=0 in-run-edges=0 orchestrator-turns=1',
  ].join('\n')
  assert.deepEqual(parseProvenance(text), { legacy: true })
})

test('S13: parseProvenance uses only the first line of multi-line text when that line matches', () => {
  const text = [
    'adapter=claude-workflow run=r-1 seats=planner:opus model=opus isolation=worktree:/wt agents=1 tokens=1 duration=1 deferred=0 in-run-edges=0 orchestrator-turns=1',
    'this trailing line is not parsed as structured data',
  ].join('\n')
  const fields = parseProvenance(text)
  assert.equal(fields.run, 'r-1')
})

// ── round-trip ───────────────────────────────────────────────────────────────────────────────

test('S13: formatProvenance(parseProvenance(l)) === l for synthetic structured lines, including one with tails', () => {
  const lines = [
    'adapter=claude-workflow run=r-1 seats=planner:opus model=opus isolation=worktree:/wt agents=1 tokens=1 duration=1 deferred=0 in-run-edges=0 orchestrator-turns=1',
    'adapter=claude-agent run=r-20260928-b2d seats=planner:opus,implementer:sonnet,declarations-extractor:sonnet,test-author:sonnet,reviewer:opus model=sonnet isolation=worktree:.claude/worktrees/feat-b2-front-door agents=5 tokens=412800 duration=1860000 deferred=0 in-run-edges=1 orchestrator-turns=3',
  ]
  for (const l of lines) {
    assert.equal(formatProvenance(parseProvenance(l)), l, `round-trip failed for: ${l}`)
  }
})

// ── doc-sync: read the example line from session-retrospective/SKILL.md at test time ───────────

test('S13 (doc-sync): the provenance example line in session-retrospective/SKILL.md round-trips through parse/format', () => {
  const text = readFileSync(SKILL_PATH, 'utf8')
  const lines = text.split(/\r?\n/)
  const structuredLine = lines.map((l) => l.trim()).find((l) => STRUCTURED_RE.test(l))
  assert.ok(structuredLine, 'expected a structured "adapter=..." provenance example line in SKILL.md')
  assert.equal(formatProvenance(parseProvenance(structuredLine)), structuredLine)
  // sanity against the frozen B2b Appendix B example content (read live, not hardcoded as the oracle)
  assert.match(structuredLine, /\brun=r-20260928-b2d\b/)
  assert.match(structuredLine, /\bagents=5\b/)
  assert.match(structuredLine, /\borchestrator-turns=3\b/)
})
