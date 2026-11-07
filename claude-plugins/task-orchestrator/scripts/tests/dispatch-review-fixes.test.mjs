// Review follow-ups for feature d99e4f7a: effort precedence (O3), extra-seats rows whose model id
// contains ':' (O1), and the keyword-space-quoted dispatch spelling in the literal guard (O2).
import { test } from 'node:test'
import assert from 'node:assert/strict'

import { resolveDispatch } from '../run-planner-lib.mjs'
import { formatExtraSeats, parseExtraSeats } from '../provenance-lib.mjs'
import { dispatchLiteralsInLine } from '../lib/contract-checks.mjs'

const policyFor = () => ({ model: 'class-model', effort: 'class-effort' })

test('O3: effort precedence is seat, then phase, then class', () => {
  const schema = {
    dispatchBySeat: { work: { implementer: { effort: 'seat-effort' } } },
    dispatch: { work: { effort: 'phase-effort' } },
  }
  const info = { enters: true }
  assert.equal(resolveDispatch(schema, 'implementer', 'work', info, policyFor).effort, 'seat-effort')
  const noSeat = { dispatch: { work: { effort: 'phase-effort' } } }
  assert.equal(resolveDispatch(noSeat, 'implementer', 'work', info, policyFor).effort, 'phase-effort')
  assert.equal(resolveDispatch({}, 'implementer', 'work', info, policyFor).effort, 'class-effort')
})

test('O3: a trait effort of null falls back to the class', () => {
  const schema = { dispatchBySeat: { work: { implementer: { effort: null } } } }
  const r = resolveDispatch(schema, 'implementer', 'work', { enters: true }, policyFor)
  assert.equal(r.effort, 'class-effort')
  assert.equal(r.model, 'class-model')
})

test('O3: a phase effort does not reach a seat that neither enters nor owns the phase', () => {
  const schema = { dispatch: { work: { effort: 'phase-effort' } } }
  assert.equal(resolveDispatch(schema, 'test-author', 'work', {}, policyFor).effort, 'class-effort')
})

test('O1: a five-field row whose model id contains colons round-trips', () => {
  const row = { seat: 'implementer-a1', model: 'us.anthropic.claude:v1:0', tokens: 1234, effort: 'high', cause: 'review' }
  const text = 'line one\n' + formatExtraSeats([row])
  const [parsed] = parseExtraSeats(text)
  assert.equal(parsed.seat, 'implementer-a1')
  assert.equal(parsed.model, 'us.anthropic.claude:v1:0')
  assert.equal(parsed.tokens, 1234)
  assert.equal(parsed.effort, 'high')
  assert.equal(parsed.cause, 'review')
})

test('O1: a three-field row whose model id contains colons stays three-field', () => {
  const [parsed] = parseExtraSeats('line one\nextra-seats=reviewer-r2:a:b:c:42')
  assert.equal(parsed.seat, 'reviewer-r2')
  assert.equal(parsed.tokens, 42)
  assert.equal(parsed.effort, undefined)
  assert.equal(parsed.model, 'a:b:c')
})

test('O2: the guard matches keyword, whitespace, quoted value', () => {
  assert.equal(dispatchLiteralsInLine('dispatch with model "sonnet" here').length, 1)
  assert.equal(dispatchLiteralsInLine("run at effort 'high'").length, 1)
  assert.equal(dispatchLiteralsInLine('a model that is high quality').length, 0)
})
