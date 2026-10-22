// Token-strip guard for item 6bd76f76 (run-exec and implement-wave: deep-strip seatToken from persisted
// seat envelopes). Scenario ids S1-S12 match the item's frozen `test-plan` note.
//
// BLIND test author: no production source under workflows/, scripts/*.mjs or scripts/lib/ was opened by any
// tool. Oracles are the item's task-scope decisions D1-D4 (strip every `seatToken` / `coordinatorToken`
// key at any depth, replace every occurrence of each known token with the literal `[redacted-token]` in
// string values and object keys, longest token first, ignore non-string/empty tokens, never mutate the
// input) and the cited scripts/ralph-lib.mjs behaviour named there. Surfaces used are exported functions
// (loadCore / loadWaveCore / stageResult / prompt) and the run-planner CLI as a subprocess.
//
// Red-proof note (test-manifest): each decision point below has a named narrowest-revert recipe; the
// positive controls (echo carries real values, sentinel appears where a token was known) keep every
// negative assertion able to fail.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { loadCore as harnessLoadCore, fakeAgent, fakeParallel, planFixture, itemFixture, stages } from './workflow-harness.mjs'
import { loadWaveCore, CORE_NAMES } from '../lib/wave-core.mjs'
import { initState, prompt, stageResult } from '../run-exec-lib.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))
const PLUGIN_ROOT = join(HERE, '..', '..')
const IMPLEMENT_WAVE = join(PLUGIN_ROOT, 'workflows', 'implement-wave.js')
const RUN_PLANNER = join(PLUGIN_ROOT, 'scripts', 'run-planner.mjs')
const SENTINEL = '[redacted-token]'
const CRLF = String.fromCharCode(13, 10)
const LF = String.fromCharCode(10)

// 43-char base64url strings, the shape of a real seat token (task-scope alternative 2).
const T1 = 'Tq9Zr2LmXa8Wv4Nc7Yb1Hd5Kf3Jp6Gs0UeTiOaBxCzQ'
const T2 = 'Zk3Mn8Pq1Rs6Tv9Wx2Yb5Dc7Fg0Hj4Lm-Np_Qr8StUV'
const NOT_A_TOKEN = 'Aa1Bb2Cc3Dd4Ee5Ff6Gg7Hh8Ii9Jj0Kk-Ll_Mm1NnOo'

assert.equal(T1.length, 43)
assert.equal(T2.length, 43)
assert.equal(NOT_A_TOKEN.length, 43)

function hasKeyDeep(value, key) {
  if (Array.isArray(value)) return value.some((v) => hasKeyDeep(v, key))
  if (value && typeof value === 'object') {
    return Object.keys(value).includes(key) || Object.values(value).some((v) => hasKeyDeep(v, key))
  }
  return false
}
function noTokenKeys(value) {
  return !hasKeyDeep(value, 'seatToken') && !hasKeyDeep(value, 'coordinatorToken')
}
function clone(v) {
  return JSON.parse(JSON.stringify(v))
}

const waveCore = loadWaveCore()
const harnessCore = harnessLoadCore(IMPLEMENT_WAVE)

// ---- envelope builders ------------------------------------------------------------------------------

function plannerOutput(overrides = {}) {
  return {
    proceed: true, blockReason: 'none', diagnosisCorrections: 'none', defectClassSiblings: 'none',
    decisions: 'none', missingApiOrSeam: 'none', testPlanStatus: 'none',
    mainFiles: [], docFiles: [], testFiles: [], existingTestEdits: [], redProofShape: 'none',
    ...overrides,
  }
}
function envelope({ output, notes = [], extra = {}, status = 'done', reason = 'ok' }) {
  return {
    status, reason, notes, commits: { pre: 'p1', post: 'p2' }, files: [],
    modelReported: 'sonnet', entry: { applied: true, newRole: 'work' }, output, ...extra,
  }
}

function methodBDoc(extraArgs = {}) {
  const A = itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
  const args = planFixture({ items: [A], ...extraArgs })
  return { contract: 'run-wave/plan-doc-v1', args, meta: {} }
}

// =============================================================================================
// S1 - D1: key stripping at any depth
// =============================================================================================

test('S1: seatToken and coordinatorToken keys are removed at every depth; everything else is deep-equal; input is not mutated', () => {
  const input = {
    status: 'done',
    seatToken: 'V-top',
    coordinatorToken: 'V-coord',
    flag: false, zero: 0, nothing: null, empty: '',
    notes: [{ key: 'task-scope', seatToken: 'V-note', warning: 'keep me' }],
    output: {
      nested: { seatToken: 'V-deep', keep: 1, deeper: { coordinatorToken: 'V-deeper', also: 'kept' } },
      list: [{ seatToken: 'V-el', n: 1 }, { coordinatorToken: 'V-el2', m: 2 }, 'plain', 7],
      arr: ['a', 'b'],
    },
    entry: { applied: true },
  }
  const snapshot = clone(input)
  const expected = {
    status: 'done',
    flag: false, zero: 0, nothing: null, empty: '',
    notes: [{ key: 'task-scope', warning: 'keep me' }],
    output: {
      nested: { keep: 1, deeper: { also: 'kept' } },
      list: [{ n: 1 }, { m: 2 }, 'plain', 7],
      arr: ['a', 'b'],
    },
    entry: { applied: true },
  }
  const out = waveCore.stripTokens(input)
  assert.deepEqual(out, expected)
  assert.equal(noTokenKeys(out), true)
  assert.deepEqual(input, snapshot, 'the input must not be mutated')
  assert.notEqual(out, input, 'a copy is returned, not the same object')
  assert.notEqual(out.notes, input.notes)
})

test('S1: a token-key whose value is itself an object or array is removed whole (and its subtree is gone)', () => {
  const out = waveCore.stripTokens({ a: 1, seatToken: { inner: 'x' }, coordinatorToken: ['y'], b: { seatToken: [1, 2], c: 3 } })
  assert.deepEqual(out, { a: 1, b: { c: 3 } })
})

// =============================================================================================
// S2 - D1: value redaction in every string position and object keys
// =============================================================================================

test('S2: every occurrence of a known token is replaced by the sentinel - reason, note field, repeated, embedded, object key, string array', () => {
  const input = {
    reason: `echo ${T1} done`,
    notes: [{ key: 'task-scope', warning: T1 }],
    output: {
      decisions: `${T1}/${T1}`,
      second: `x${T1}`,
      [T1]: 'keyed by the token',
      nested: { list: ['clean', T1, `pre-${T1}-post`] },
    },
  }
  const snapshot = clone(input)
  const out = waveCore.stripTokens(input, [T1])
  assert.equal(JSON.stringify(out).includes(T1), false)
  assert.deepEqual(out, {
    reason: `echo ${SENTINEL} done`,
    notes: [{ key: 'task-scope', warning: SENTINEL }],
    output: {
      decisions: `${SENTINEL}/${SENTINEL}`,
      second: `x${SENTINEL}`,
      [SENTINEL]: 'keyed by the token',
      nested: { list: ['clean', SENTINEL, `pre-${SENTINEL}-post`] },
    },
  })
  assert.deepEqual(input, snapshot, 'the input must not be mutated')
})

test('S2: several known tokens are all redacted in one pass', () => {
  const out = waveCore.stripTokens({ a: `${T1} and ${T2}`, b: [T2, T1] }, [T1, T2])
  assert.deepEqual(out, { a: `${SENTINEL} and ${SENTINEL}`, b: [SENTINEL, SENTINEL] })
})

// =============================================================================================
// S3 - D1: longest token first (a shorter token that prefixes a longer one leaves no residue)
// =============================================================================================

test('S3: with tokens [A, A+"xyz"] no "xyz" residue remains, in either argument order', () => {
  const A = 'abcDEF123ghi'
  const input = { s: `${A}xyz tail ${A}`, t: `${A}xyz` }
  const expected = { s: `${SENTINEL} tail ${SENTINEL}`, t: SENTINEL }
  assert.deepEqual(waveCore.stripTokens(input, [A, `${A}xyz`]), expected)
  assert.deepEqual(waveCore.stripTokens(input, [`${A}xyz`, A]), expected)
})

// =============================================================================================
// S4 - D1: tolerant of absent / empty / non-string tokens
// =============================================================================================

test('S4: knownTokens undefined, [] and [empty, number, null, undefined, object] only strip keys; no throw; no sentinel injected', () => {
  const input = { seatToken: 'V', s: 'hello', empty: '', notes: [{ key: 'k', coordinatorToken: 'V2', warning: 'w' }], arr: ['a', ''] }
  const expected = { s: 'hello', empty: '', notes: [{ key: 'k', warning: 'w' }], arr: ['a', ''] }
  for (const tokens of [undefined, [], ['', 42, null, undefined, {}, [], true]]) {
    const out = waveCore.stripTokens(input, tokens)
    assert.deepEqual(out, expected, `tokens=${JSON.stringify(tokens)}`)
    assert.equal(JSON.stringify(out).includes(SENTINEL), false, `no sentinel for tokens=${JSON.stringify(tokens)}`)
  }
})

// =============================================================================================
// S5 - D1: non-object input
// =============================================================================================

test('S5: null / number / boolean pass through; a bare string is redacted; arrays stay arrays', () => {
  assert.equal(waveCore.stripTokens(null, [T1]), null)
  assert.equal(waveCore.stripTokens(7, [T1]), 7)
  assert.equal(waveCore.stripTokens(true, [T1]), true)
  assert.equal(waveCore.stripTokens(`x ${T1} y`, [T1]), `x ${SENTINEL} y`)
  assert.equal(waveCore.stripTokens('no token here', [T1]), 'no token here')

  const arr = waveCore.stripTokens(['a', T1, { seatToken: 'V', k: [T1] }], [T1])
  assert.equal(Array.isArray(arr), true)
  assert.deepEqual(arr, ['a', SENTINEL, { k: [SENTINEL] }])
  const nested = waveCore.stripTokens({ list: [['x', T1]] }, [T1])
  assert.equal(Array.isArray(nested.list), true)
  assert.equal(Array.isArray(nested.list[0]), true)
  assert.deepEqual(nested, { list: [['x', SENTINEL]] })
})

// =============================================================================================
// S11 - control: non-token content is untouched
// =============================================================================================

test('S11: a 43-char non-token string and a file path survive byte-identical, with and without a known token', () => {
  const content = { path: 'claude-plugins/task-orchestrator/workflows/implement-wave.js', other: NOT_A_TOKEN, list: [NOT_A_TOKEN, 'src/aaaaaaaa.js'] }
  assert.deepEqual(waveCore.stripTokens(content), content)
  assert.deepEqual(waveCore.stripTokens(content, []), content)
  assert.deepEqual(waveCore.stripTokens(content, [T1]), content)
  assert.equal(JSON.stringify(waveCore.stripTokens(content)).includes(SENTINEL), false, 'no sentinel when no token is known')
  assert.equal(JSON.stringify(waveCore.stripTokens(content, [T1])).includes(SENTINEL), false, 'no sentinel when the token does not occur')
})

// =============================================================================================
// S12 - D1 export wiring
// =============================================================================================

test('S12: CORE_NAMES includes stripTokens and both core loaders expose it as a function', () => {
  assert.ok(CORE_NAMES.includes('stripTokens'))
  assert.equal(typeof waveCore.stripTokens, 'function')
  assert.equal(typeof harnessCore.stripTokens, 'function')
  assert.deepEqual(Object.keys(waveCore).sort(), Object.keys(harnessCore).sort())
  const input = { seatToken: 'V', s: T1 }
  assert.deepEqual(harnessCore.stripTokens(input, [T1]), waveCore.stripTokens(input, [T1]))
  assert.deepEqual(harnessCore.stripTokens(input, [T1]), { s: SENTINEL })
})

// =============================================================================================
// Probes on the pure helper
// =============================================================================================

test('probe: near-miss key spellings are NOT key-stripped (SeatToken, seat_token, seattoken, seatTokens)', () => {
  const input = { SeatToken: 'a', seat_token: 'b', seattoken: 'c', seatTokens: 'd', CoordinatorToken: 'e', coordinator_token: 'f' }
  assert.deepEqual(waveCore.stripTokens(input), input)
})

test('probe: a token with regex metacharacters is matched literally, not as a pattern', () => {
  const tok = 'a.b*(c'
  const out = waveCore.stripTokens({ s: 'a.b*(c / aXb*(c / a.b*(c' }, [tok])
  assert.deepEqual(out, { s: `${SENTINEL} / aXb*(c / ${SENTINEL}` })
})

test('probe: duplicate tokens in the list redact once, never nest the sentinel', () => {
  const out = waveCore.stripTokens({ s: `a ${T1} b` }, [T1, T1, T1])
  assert.deepEqual(out, { s: `a ${SENTINEL} b` })
})

test('probe: idempotent - stripping a stripped value changes nothing, and an existing sentinel is left alone', () => {
  const input = { seatToken: 'V', s: `${T1} and ${SENTINEL}`, l: [{ coordinatorToken: 'W', t: T1 }] }
  const once = waveCore.stripTokens(input, [T1])
  const twice = waveCore.stripTokens(once, [T1])
  assert.deepEqual(twice, once)
  assert.deepEqual(once, { s: `${SENTINEL} and ${SENTINEL}`, l: [{ t: SENTINEL }] })
})

test('probe: a token split across two strings is not redacted (documented exact-match limit)', () => {
  const half1 = T1.slice(0, 20)
  const half2 = T1.slice(20)
  const out = waveCore.stripTokens({ a: half1, b: half2 }, [T1])
  assert.deepEqual(out, { a: half1, b: half2 })
})

test('probe: a "__proto__" key in parsed input does not pollute Object.prototype', () => {
  const input = JSON.parse('{"__proto__":{"polluted":"yes"},"a":1,"seatToken":"V","n":{"__proto__":{"polluted2":"yes"}}}')
  const out = waveCore.stripTokens(input, [T1])
  assert.equal({}.polluted, undefined)
  assert.equal({}.polluted2, undefined)
  assert.equal(out.a, 1)
  assert.equal(Object.keys(out).includes('seatToken'), false)
})

test('probe: the copy of a parsed "__proto__" data key stays a plain data key - the copy keeps Object.prototype as its prototype (D1: structurally equal copy)', () => {
  const input = JSON.parse('{"__proto__":{"polluted":"yes"},"a":1}')
  assert.equal(Object.keys(input).includes('__proto__'), true, 'fixture: JSON.parse made __proto__ an own data key')
  const out = waveCore.stripTokens(input, [T1])
  assert.equal(Object.getPrototypeOf(out), Object.prototype)
  assert.equal(out.polluted, undefined)
})

test('probe: a parsed "__proto__" object carrying seatToken cannot make seatToken readable on the copy by property access', () => {
  const input = JSON.parse('{"__proto__":{"seatToken":"V-smuggled"},"a":1}')
  const out = waveCore.stripTokens(input, [T1])
  assert.equal(out.seatToken, undefined)
})

// =============================================================================================
// S6 - D2: Method A (Workflow core runPlan with a fake agent)
// =============================================================================================

function methodAItem() {
  return itemFixture({ short: 'aaaaaaaa', stages: stages.featureTaskLike() })
}

test('S6: Method A - an echoing planner envelope (keys at envelope, note, output and entry level) leaves no token key or probe value in the result or the next prompt', async () => {
  const V = 'PROBE-VALUE-seat-QX7'
  const V2 = 'PROBE-VALUE-coord-RZ4'
  const echoing = envelope({
    notes: [{ key: 'task-scope', seatToken: V, warning: 'keep this text' }],
    output: plannerOutput({
      decisions: 'a real decision',
      seatToken: V,
      extra: { nested: { seatToken: V, list: [{ coordinatorToken: V2, ok: 1 }] } },
    }),
    extra: { seatToken: V, coordinatorToken: V2 },
  })
  echoing.entry = { applied: true, newRole: 'work', seatToken: V }
  const item = methodAItem()
  const plan = planFixture({ items: [item] })
  const fa = fakeAgent({ 'planner:aaaaaaaa': echoing })
  const result = await harnessCore.runPlan(plan, { agent: fa.agent, parallel: fakeParallel, log: () => {} })

  // fixture sanity: the scripted envelope really carries the probe values (so the checks below can fail)
  assert.equal(JSON.stringify(echoing).includes(V), true)
  assert.equal(JSON.stringify(echoing).includes(V2), true)

  const planner = result.items[0].stages.find((s) => s.seat === 'planner')
  assert.equal(planner.status, 'done', `planner -> ${planner.status}/${planner.reason}`)
  assert.equal(planner.notes[0].key, 'task-scope', 'notes[].key stays intact')
  assert.equal(planner.notes[0].warning, 'keep this text', 'non-token note content stays intact')
  assert.equal(noTokenKeys(result), true, 'result carries no seatToken / coordinatorToken key')
  assert.equal(JSON.stringify(result).includes(V), false)
  assert.equal(JSON.stringify(result).includes(V2), false)

  const implCall = fa.calls.find((c) => c.label === 'implementer:aaaaaaaa')
  assert.ok(implCall, 'the implementer seat was dispatched after the planner')
  assert.equal(implCall.prompt.includes(V), false, 'implementer prompt does not carry the probe value')
  assert.equal(implCall.prompt.includes(V2), false)
  assert.equal(implCall.prompt.includes('a real decision'), true, 'positive control: the planner output still feeds the next prompt')
})

test('S6 variant: an agentType throw then a fallback envelope - the fallback envelope is stripped too', async () => {
  const V = 'PROBE-VALUE-fallback-JK2'
  const stageDef = {
    seat: 'owner', phase: 'work', notes: ['task-scope'], enters: true, writes: true,
    output: 'generic-v1', dispatch: { agent: 'task-orchestrator:implementer' },
  }
  const item = itemFixture({ short: '88888881', stages: [stageDef] })
  const plan = planFixture({ items: [item] })
  const fa = fakeAgent({
    'owner:88888881': (n, p, opts) => {
      if (opts.agentType) return { throw: 'unknown subagent type' }
      return envelope({
        notes: [{ key: 'task-scope', seatToken: V }],
        output: { summary: 'ok', seatToken: V },
        extra: { seatToken: V, coordinatorToken: V },
      })
    },
  })
  const result = await harnessCore.runPlan(plan, { agent: fa.agent, parallel: fakeParallel, log: () => {} })
  assert.equal(fa.calls.length, 2, 'initial call plus one fallback call')
  assert.equal(fa.calls[1].opts.agentType, undefined)
  const st = result.items[0].stages[0]
  assert.equal(st.status, 'done', `owner -> ${st.status}/${st.reason}`)
  assert.equal(st.agentTypeFallback, true)
  assert.equal(st.notes[0].key, 'task-scope')
  assert.equal(noTokenKeys(result), true)
  assert.equal(JSON.stringify(result).includes(V), false)
})

// =============================================================================================
// S7 / S9 / S11 - D3: Method B stageResult with knownTokens
// =============================================================================================

test('S7: Method B stageResult redacts known tokens and strips token keys from the persisted state; the stage stays done; the next prompt is clean', () => {
  const doc = methodBDoc()
  const state = initState(doc, 'B')
  const echoing = envelope({
    notes: [{ key: 'task-scope', seatToken: T1, warning: 'real warning' }],
    output: plannerOutput({ decisions: T1, mainFiles: ['src/aaaaaaaa.js'], seatToken: T1 }),
    extra: { seatToken: T1, coordinatorToken: T2 },
  })
  const res = stageResult(waveCore, doc, state, 'aaaaaaaa', 'planner', JSON.stringify(echoing), { knownTokens: [T1, T2] })
  assert.equal(res.status, 'done', `${res.status}/${res.reason}`)
  const serialized = JSON.stringify(res.state)
  assert.equal(serialized.includes(T1), false, 'state lacks the known token T1')
  assert.equal(serialized.includes(T2), false, 'state lacks the known token T2')
  assert.equal(noTokenKeys(res.state), true, 'state carries no token keys')
  assert.equal(res.state.outs['aaaaaaaa:planner'].decisions, SENTINEL, 'a value equal to a known token becomes the sentinel')
  assert.deepEqual(res.state.outs['aaaaaaaa:planner'].mainFiles, ['src/aaaaaaaa.js'], 'non-token content is untouched')
  assert.equal(JSON.stringify(res).includes(T1), false, 'the whole return value (not only state) is clean')

  const p = prompt(waveCore, doc, res.state, 'aaaaaaaa', 'implementer')
  assert.equal(p.includes(T1), false)
  assert.equal(p.includes(T2), false)
})

test('S7 control: without knownTokens the keys are still stripped but a token VALUE under another key is not (knownTokens is what drives value redaction)', () => {
  const doc = methodBDoc()
  const state = initState(doc, 'B')
  const echoing = envelope({
    output: plannerOutput({ decisions: T1, seatToken: T1 }),
    extra: { seatToken: T1 },
  })
  const res = stageResult(waveCore, doc, state, 'aaaaaaaa', 'planner', JSON.stringify(echoing))
  assert.equal(res.status, 'done')
  assert.equal(noTokenKeys(res.state), true)
  assert.equal(res.state.outs['aaaaaaaa:planner'].decisions, T1, 'fixture can fail: with no known token the value is kept')
  const withToken = stageResult(waveCore, doc, state, 'aaaaaaaa', 'planner', JSON.stringify(echoing), { knownTokens: [T1] })
  assert.equal(withToken.state.outs['aaaaaaaa:planner'].decisions, SENTINEL)
})

test('S9: an invalid envelope (missing commits) that echoes a token retries without persisting the token; the second failure stops', () => {
  const doc = methodBDoc()
  const state = initState(doc, 'B')
  const bad = {
    status: 'done', reason: `echo ${T1}`, notes: [{ key: 'task-scope', seatToken: T1, warning: T1 }], files: [],
    modelReported: 'x', output: plannerOutput({ decisions: T1, seatToken: T1 }), seatToken: T1,
  }
  const text = JSON.stringify(bad)
  const first = stageResult(waveCore, doc, state, 'aaaaaaaa', 'planner', text, { knownTokens: [T1] })
  assert.equal(first.status, 'retry')
  assert.match(first.reason, /^invalid envelope/)
  assert.equal(first.state.stages['aaaaaaaa:planner'].envelopeRetries, 1)
  assert.equal(JSON.stringify(first).includes(T1), false, 'neither the state nor the reason carries the token')
  assert.equal(noTokenKeys(first), true)

  const second = stageResult(waveCore, doc, first.state, 'aaaaaaaa', 'planner', text, { knownTokens: [T1] })
  assert.equal(second.status, 'stopped')
  assert.equal(second.reason, 'invalid envelope')
  assert.equal(JSON.stringify(second).includes(T1), false)
})

test('S11 (Method B): file paths and a 43-char non-token string in the output survive byte-identical', () => {
  const doc = methodBDoc()
  const state = initState(doc, 'B')
  const files = ['claude-plugins/task-orchestrator/workflows/implement-wave.js', 'src/aaaaaaaa.js']
  const env = envelope({ output: plannerOutput({ mainFiles: files, decisions: NOT_A_TOKEN }) })
  const res = stageResult(waveCore, doc, state, 'aaaaaaaa', 'planner', JSON.stringify(env), { knownTokens: [T1] })
  assert.equal(res.status, 'done')
  assert.deepEqual(res.state.outs['aaaaaaaa:planner'].mainFiles, files)
  assert.equal(res.state.outs['aaaaaaaa:planner'].decisions, NOT_A_TOKEN)
  assert.equal(JSON.stringify(res.state).includes(SENTINEL), false, 'no sentinel appears when the known token never occurs')
})

// =============================================================================================
// S8 / S10 - D4: the CLI reads <scratchDir>/tokens/*.token and redacts them
// =============================================================================================

function runCli(args) {
  return spawnSync(process.execPath, [RUN_PLANNER, ...args], { encoding: 'utf8' })
}

function cliWorld({ scratch = true, tokenFiles = {} } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'token-strip-'))
  const scratchDir = join(dir, 'scratch')
  mkdirSync(scratchDir, { recursive: true })
  if (Object.keys(tokenFiles).length > 0) {
    const tokensDir = join(scratchDir, 'tokens')
    mkdirSync(tokensDir, { recursive: true })
    for (const [name, body] of Object.entries(tokenFiles)) writeFileSync(join(tokensDir, name), body)
  }
  const doc = methodBDoc(scratch ? { project: { scratchDir } } : {})
  const state = initState(doc, 'B')
  const planPath = join(dir, 'plan.json')
  const statePath = join(dir, 'state.json')
  writeFileSync(planPath, JSON.stringify(doc))
  writeFileSync(statePath, JSON.stringify(state))
  return { dir, scratchDir, doc, planPath, statePath }
}
function writeEnvelope(dir, env, name = 'envelope.json') {
  const p = join(dir, name)
  writeFileSync(p, typeof env === 'string' ? env : JSON.stringify(env))
  return p
}
function stageResultCli(w, envPath, seat = 'planner') {
  return runCli(['stage-result', '--plan', w.planPath, '--state', w.statePath, '--item', 'aaaaaaaa', '--seat', seat, '--envelope', envPath])
}

test('S8: CLI stage-result redacts the values of every <scratchDir>/tokens/*.token file (CRLF and LF terminated) and strips token keys', () => {
  const w = cliWorld({
    tokenFiles: {
      'aaaaaaaa.planner.token': `${T1}${CRLF}`,
      'aaaaaaaa.implementer.token': `${T2}${LF}`,
    },
  })
  const echoing = envelope({
    notes: [{ key: 'task-scope', seatToken: T1, warning: `see ${T2}` }],
    output: plannerOutput({ decisions: `${T1} and ${T2}`, mainFiles: ['src/aaaaaaaa.js'], seatToken: T1 }),
    extra: { seatToken: T1, coordinatorToken: T2 },
  })
  const res = stageResultCli(w, writeEnvelope(w.dir, echoing))
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.status, 'done', `${out.status}/${out.reason}`)
  assert.equal(res.stdout.includes(T1), false, 'stdout lacks the CRLF-file token (trimmed form)')
  assert.equal(res.stdout.includes(T2), false, 'stdout lacks the LF-file token')
  assert.equal(res.stderr.includes(T1) || res.stderr.includes(T2), false, 'stderr lacks token values')
  assert.equal(noTokenKeys(out), true)
  assert.equal(out.state.outs['aaaaaaaa:planner'].decisions, `${SENTINEL} and ${SENTINEL}`)
  assert.deepEqual(out.state.outs['aaaaaaaa:planner'].mainFiles, ['src/aaaaaaaa.js'])
})

test('S8 probe: a token file value containing inner spaces is trimmed only at its ends and redacted whole', () => {
  const spaced = 'spaced token value'
  const w = cliWorld({ tokenFiles: { 'aaaaaaaa.planner.token': `  ${spaced}  ${CRLF}` } })
  const env = envelope({ output: plannerOutput({ decisions: `before ${spaced} after` }) })
  const res = stageResultCli(w, writeEnvelope(w.dir, env))
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.status, 'done')
  assert.equal(out.state.outs['aaaaaaaa:planner'].decisions, `before ${SENTINEL} after`)
  assert.equal(res.stdout.includes(spaced), false)
})

test('S8 probe: empty and whitespace-only .token files are ignored (no sentinel sprinkled into ordinary strings)', () => {
  const w = cliWorld({ tokenFiles: { 'aaaaaaaa.planner.token': '', 'aaaaaaaa.implementer.token': CRLF, 'bbbbbbbb.reviewer.token': '   ' } })
  const env = envelope({ output: plannerOutput({ decisions: 'plain decision text', mainFiles: ['src/x.js'] }) })
  const res = stageResultCli(w, writeEnvelope(w.dir, env))
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.status, 'done')
  assert.equal(out.state.outs['aaaaaaaa:planner'].decisions, 'plain decision text')
  assert.equal(res.stdout.includes(SENTINEL), false)
})

test('S8 probe: only names ending in .token are read - the value of a differently named file is not redacted', () => {
  const W = 'NOT-A-TOKEN-FILE-VALUE-91'
  const w = cliWorld({ tokenFiles: { 'aaaaaaaa.planner.token': `${T1}${LF}`, 'aaaaaaaa.planner.notes.txt': `${W}${LF}`, 'aaaaaaaa.planner.token.bak': `${W}${LF}` } })
  const env = envelope({ output: plannerOutput({ decisions: `${T1} ${W}` }) })
  const res = stageResultCli(w, writeEnvelope(w.dir, env))
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.state.outs['aaaaaaaa:planner'].decisions, `${SENTINEL} ${W}`)
})

test('S8 failure path: an invalid envelope echoing a token-file value reaches neither stdout nor stderr', () => {
  const w = cliWorld({ tokenFiles: { 'aaaaaaaa.planner.token': `${T1}${CRLF}` } })
  const bad = {
    status: 'done', reason: `echo ${T1}`, notes: [{ key: 'task-scope', warning: T1 }], files: [],
    modelReported: 'x', output: plannerOutput({ decisions: T1 }), seatToken: T1,
  }
  const res = stageResultCli(w, writeEnvelope(w.dir, bad))
  assert.equal(res.status, 0, res.stderr)
  assert.equal(JSON.parse(res.stdout).status, 'retry')
  assert.equal(res.stdout.includes(T1), false)
  assert.equal(res.stderr.includes(T1), false)
})

test('S8 failure path: a prose-wrapped envelope around a token echo is also redacted', () => {
  const w = cliWorld({ tokenFiles: { 'aaaaaaaa.planner.token': `${T1}${LF}` } })
  const env = envelope({ output: plannerOutput({ decisions: T1 }) })
  const text = `My reasoning mentions ${T1} before the answer.${LF}${JSON.stringify(env)}${LF}Done.`
  const res = stageResultCli(w, writeEnvelope(w.dir, text, 'envelope.txt'))
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.status, 'done')
  assert.equal(res.stdout.includes(T1), false)
  assert.equal(out.state.outs['aaaaaaaa:planner'].decisions, SENTINEL)
})

test('S10: CLI with a scratchDir but no tokens directory exits 0, stays done, and still strips keys', () => {
  const w = cliWorld({ scratch: true, tokenFiles: {} })
  const env = envelope({
    notes: [{ key: 'task-scope', seatToken: 'V-note' }],
    output: plannerOutput({ seatToken: 'V-out' }),
    extra: { seatToken: 'V-top', coordinatorToken: 'V-coord' },
  })
  const res = stageResultCli(w, writeEnvelope(w.dir, env))
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.status, 'done', `${out.status}/${out.reason}`)
  assert.equal(noTokenKeys(out), true)
  assert.equal(out.state.stages['aaaaaaaa:planner'].status, 'done')
})

test('S10: CLI with no project.scratchDir in the plan exits 0, stays done, and still strips keys', () => {
  const w = cliWorld({ scratch: false })
  const env = envelope({
    notes: [{ key: 'task-scope', seatToken: 'V-note' }],
    output: plannerOutput({ seatToken: 'V-out' }),
    extra: { seatToken: 'V-top', coordinatorToken: 'V-coord' },
  })
  const res = stageResultCli(w, writeEnvelope(w.dir, env))
  assert.equal(res.status, 0, res.stderr)
  const out = JSON.parse(res.stdout)
  assert.equal(out.status, 'done', `${out.status}/${out.reason}`)
  assert.equal(noTokenKeys(out), true)
})
