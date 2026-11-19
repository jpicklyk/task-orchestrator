// Item 6f4e4c77: the hint that announces seat skills also names the seat's rule keys (R6, D7), so a hand-dispatched seat gets them.
// Scenarios S1 (path 3), S2 (path 3), S3, S4, S5, S6, S7, S8 and S11 of the frozen test-plan, as spawned node hook runs against a stub server.
//
// Oracles: task-scope R4 (a declared protocol key that is not the role's own is dropped), R5 (closure-notes is the implementer default),
// R6 (the hint names the keys; nothing is recorded or refused for a rule) and D7: after any SEAT SKILLS text the hint says
//   'SEAT RULES: you are the <seat> seat on item <id>. Fetch each of these rule keys with config op "rules" (rootId <id>) and follow it,
//    in this order: <k1>, <k2>. A not_found answer for <protocol> is fatal: stop and report it.'
// with '(rootId: your project root)' when there is no rootId and ' A not_found answer for any other key: skip that key.' appended when the
// served read went unanswered; given when a role resolves, skills or not, on the same triggers. The lists are test-plan P, I and V.
// BLIND test author: no production file was opened. The stub speaks the wire shapes of rule-pointer.test.mjs and seat-skill-hooks.test.mjs.
//
// Fixture condition for each absence assertion: the twin in the same test (or the next) produces the text.
// Forbidden-construct declaration: none.

import './hermetic-claude-config.mjs'
import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { fileURLToPath } from 'node:url'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, cpSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, relative, sep } from 'node:path'
import { randomUUID } from 'node:crypto'
import { SEAT_SKILLS_PREFIX } from '../lib/seat-skill-state.mjs'
import { RULES_TIMEOUT_MS } from '../lib/served-rules.mjs'

const HINT = fileURLToPath(new URL('../seat-skill-hint.mjs', import.meta.url))
const GATE = fileURLToPath(new URL('../seat-skill-gate.mjs', import.meta.url))
const RECORD = fileURLToPath(new URL('../skill-record.mjs', import.meta.url))
const PLUGIN_ROOT = fileURLToPath(new URL('../../', import.meta.url))

const READ_TOOL = 'mcp__mcp-task-orchestrator__items_read'
const ADVANCE_TOOL = 'mcp__mcp-task-orchestrator__advance'
const R = '11111111-1111-1111-1111-111111111111'
const U1 = '4a468f6c-9434-4cbf-97e8-b6c546c1b112'
const P = ['protocol.in-phase-seat', 'planning-sweeps', 'review-cited-claims']
const I = ['protocol.entry-seat', 'commit-discipline', 'closure-notes']
const V = ['protocol.read-only-agent', 'review-scoping', 'review-cited-claims']
const ALL_SERVED = ['commit-discipline', 'planning-sweeps', 'review-cited-claims', 'closure-notes', 'review-scoping', 'declarations-extractor', 'x-rule',
  'protocol.in-phase-seat', 'protocol.entry-seat', 'protocol.read-only-agent']

const tracked = []
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true })
})
const fresh = (prefix) => {
  const d = mkdtempSync(join(tmpdir(), prefix))
  tracked.push(d)
  return d
}
function fixture({ rootId = R } = {}) {
  const dir = fresh('to-seat-rules-')
  mkdirSync(join(dir, '.taskorchestrator'), { recursive: true })
  writeFileSync(join(dir, '.taskorchestrator', 'config.yaml'), rootId ? `project:\n  rootId: ${rootId}\n` : '# no project block\n', 'utf-8')
  return { dir, tmp: fresh('to-seat-rules-tmp-') }
}

// ---- stub -----------------------------------------------------------------------------------------------------

const okEnvelope = (data) => ({ status: 200, body: { ok: true, data, meta: { reqId: 'req00001' } } })
const errEnvelope = (status, code) => ({ status, body: { ok: false, error: { code, kind: 'permanent', message: `${code} fixture` }, meta: { reqId: 'req00002' } } })
const rulesOk = (keys) => okEnvelope({ results: [{ ok: true, rootId: R, rules: keys.map((key) => ({ key, rulesVersion: 'v1', updatedAt: '2026-01-01T00:00:00Z' })) }], summary: { ok: 1, failed: 0 } })

const planner = (extra = {}) => ({ name: 'planner', phase: 'queue', ...extra })
const implementer = (extra = {}) => ({ name: 'implementer', phase: 'work', enters: true, ...extra })
const reviewer = (extra = {}) => ({ name: 'reviewer', phase: 'review', ...extra })
const schemaView = ({ seats = [planner(), implementer(), reviewer()], notes = [], features = ['seats', 'dispatchBySeat', 'seatSkills'] } = {}) =>
  okEnvelope({ type: 'feature-task', configFingerprint: 'fp', configSource: 'per-root', notes, ...(seats === null ? {} : { seats }), features })

async function startStub({ schema = schemaView(), rules = rulesOk(ALL_SERVED), rulesDelay = 0 } = {}) {
  const requests = []
  const sockets = new Set()
  const server = createServer((req, res) => {
    let raw = ''
    req.on('data', (d) => {
      raw += d
    })
    req.on('end', () => {
      let body = {}
      try {
        body = JSON.parse(raw)
      } catch {
        /* leave empty */
      }
      requests.push({ method: req.method, url: req.url, body })
      let reply = errEnvelope(404, 'not_found')
      let delay = 0
      if (req.method === 'POST' && req.url === '/api/v1/ops/items_read' && body.select === 'schema') reply = schema
      if (req.method === 'POST' && req.url === '/api/v1/ops/config' && body?.ops?.[0]?.op === 'rules') {
        reply = rules
        delay = rulesDelay
      }
      setTimeout(() => {
        res.writeHead(reply.status, { 'Content-Type': 'application/json' })
        res.end(JSON.stringify(reply.body))
      }, delay)
    })
  })
  server.on('connection', (s) => {
    sockets.add(s)
    s.on('close', () => sockets.delete(s))
  })
  await new Promise((r) => server.listen(0, '127.0.0.1', r))
  server.requests = requests
  server.origin = `http://127.0.0.1:${server.address().port}`
  server.stop = () => {
    for (const s of sockets) s.destroy()
    return new Promise((r) => server.close(r))
  }
  return server
}

async function withStub(opts, body, fxOpts) {
  const server = await startStub(opts)
  const fx = fixture(fxOpts)
  try {
    return await body(server, fx)
  } finally {
    await server.stop()
  }
}

function run(hook, payload, fx, extra = {}) {
  return new Promise((resolve, reject) => {
    const env = { ...process.env, TEMP: fx.tmp, TMP: fx.tmp, TMPDIR: fx.tmp, TASK_ORCHESTRATOR_HOME: join(fx.dir, 'to-home-empty'), TASK_ORCHESTRATOR_CEILING: fx.dir, AGENT_CONFIG_DIR: fx.dir }
    delete env.TASK_ORCHESTRATOR_API_URL
    delete env.TASK_ORCHESTRATOR_API_TOKEN
    delete env.TASK_ORCHESTRATOR_MODE
    delete env.CLAUDE_PROJECT_DIR
    if (extra.apiUrl) env.TASK_ORCHESTRATOR_API_URL = extra.apiUrl
    const started = Date.now()
    const child = spawn(process.execPath, [hook], { env, cwd: fx.dir })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (d) => {
      stdout += d
    })
    child.stderr.on('data', (d) => {
      stderr += d
    })
    child.on('error', reject)
    child.on('close', (status) => resolve({ status, stdout, stderr, ms: Date.now() - started }))
    child.stdin.end(JSON.stringify(payload))
  })
}

const parse = (res) => (res.stdout.trim() === '' ? {} : JSON.parse(res.stdout))
const isEmptyOut = (res) => res.stdout.trim() === '' || res.stdout.trim() === '{}'
const isDeny = (res) => parse(res).hookSpecificOutput?.permissionDecision === 'deny' || parse(res).decision === 'block'
const contextOf = (res) => parse(res).hookSpecificOutput?.additionalContext ?? ''
const sid = (name) => `${name}-${randomUUID()}`

function ctxCall(session, { agentId = 'agent-1', agentType = 'task-orchestrator:implementer', itemId = U1, role = 'work', callerSeat = 'implementer' } = {}) {
  return {
    session_id: session,
    ...(agentId ? { agent_id: agentId } : {}),
    ...(agentType ? { agent_type: agentType } : {}),
    tool_name: READ_TOOL,
    tool_input: { select: 'context', itemId },
    tool_response: {
      content: [{ type: 'text', text: 'ok' }],
      structuredContent: { ok: true, data: { item: { id: 'c0ffee00', title: 'Widget', role }, ...(callerSeat ? { callerSeat } : {}) }, meta: { reqId: 'req00001' } },
    },
  }
}
const advCall = (session, element, { itemId = U1 } = {}) => ({
  session_id: session,
  tool_name: ADVANCE_TOOL,
  tool_input: { ops: [{ op: 'transition', itemId, trigger: 'start' }] },
  tool_response: {
    content: [{ type: 'text', text: 'ok' }],
    structuredContent: { ok: true, data: { results: [element], summary: { ok: 1, failed: 0 } }, meta: { reqId: 'req00002' } },
  },
})
const editCall = (session) => ({ session_id: session, agent_id: 'agent-1', agent_type: 'task-orchestrator:implementer', tool_name: 'Edit', tool_input: { file_path: '/x/a.txt', old_string: 'a', new_string: 'b' } })

const ROLE_CTX = {
  planner: { agentType: 'task-orchestrator:planner', role: 'queue', callerSeat: 'planner' },
  implementer: {},
  reviewer: { agentType: 'task-orchestrator:reviewer', role: 'review', callerSeat: 'reviewer' },
}

/** The exact D7 text for `keys`; the item id is the id the hook was handed, in full or its first 8 characters. */
function expectedTexts({ seat, keys, rootId = R, answered = true }) {
  const rootPart = rootId ? `(rootId ${rootId})` : '(rootId: your project root)'
  return [U1, U1.slice(0, 8)].map(
    (id) =>
      `SEAT RULES: you are the ${seat} seat on item ${id}. Fetch each of these rule keys with config op "rules" ${rootPart} and follow it, in this order: ${keys.join(', ')}. ` +
      `A not_found answer for ${keys[0]} is fatal: stop and report it.${answered ? '' : ' A not_found answer for any other key: skip that key.'}`
  )
}
const assertRulesText = (text, opts) => assert.ok(expectedTexts(opts).some((t) => text.includes(t)), `expected one of:\n${expectedTexts(opts).join('\n')}\ngot:\n${text}`)
const rulesRequests = (server) => server.requests.filter((r) => r.url === '/api/v1/ops/config' && r.body?.ops?.[0]?.op === 'rules')

// ---- S1 / S2: path 3 gives the same keys as the other paths ---------------------------------------------------------

for (const [seat, keys] of [['planner', P], ['implementer', I], ['reviewer', V]]) {
  test(`S1 (R6, D7): a ${seat} with no declared rules or rule skills is told ${keys.join(', ')}, with the exact text`, async () => {
    await withStub({ schema: schemaView({ seats: [planner({ skills: [] }), implementer({ skills: [] }), reviewer({ skills: [] })] }) }, async (server, fx) => {
      const res = await run(HINT, ctxCall(sid('s1'), ROLE_CTX[seat]), fx, { apiUrl: server.origin })
      assert.equal(res.status, 0, res.stderr)
      assertRulesText(contextOf(res), { seat, keys })
      assert.equal(rulesRequests(server).length, 1, 'one served-rule-keys read')
    })
  })
}

for (const [seat, keys] of [['planner', P], ['implementer', I]]) {
  test(`S2 (R5): a seatless schema gives the ${seat} the keys ${keys.join(', ')} on the hint`, async () => {
    await withStub({ schema: schemaView({ seats: null }) }, async (server, fx) => {
      const res = await run(HINT, ctxCall(sid('s2'), ROLE_CTX[seat]), fx, { apiUrl: server.origin })
      assert.equal(res.status, 0, res.stderr)
      assertRulesText(contextOf(res), { seat, keys })
    })
  })
}

// ---- S3: no skills, rules still named; skills present, rules after them --------------------------------------------

test('S3 (R6): implementer skills [] -> no SEAT SKILLS text, SEAT RULES lists I, and Edit is not denied', async () => {
  await withStub({ schema: schemaView({ seats: [planner(), implementer({ skills: [] }), reviewer()] }) }, async (server, fx) => {
    const s = sid('s3')
    const res = await run(HINT, ctxCall(s), fx, { apiUrl: server.origin })
    const text = contextOf(res)
    assert.equal(text.includes(SEAT_SKILLS_PREFIX), false, text)
    assert.ok(text.startsWith('SEAT RULES:'), text)
    assertRulesText(text, { seat: 'implementer', keys: I })
    assert.equal(isDeny(await run(GATE, editCall(s), fx, { apiUrl: server.origin })), false)
  })
})

test('S3 (R6, D7): with skills the SEAT SKILLS text comes first and the SEAT RULES text after it; the gate and the record are unchanged', async () => {
  await withStub({ schema: schemaView({ seats: [planner(), implementer({ skills: ['p:a'] }), reviewer()] }) }, async (server, fx) => {
    const s = sid('s3b')
    const text = contextOf(await run(HINT, ctxCall(s), fx, { apiUrl: server.origin }))
    assert.ok(text.startsWith(SEAT_SKILLS_PREFIX), text)
    assert.ok(text.indexOf('SEAT RULES:') > text.indexOf('p:a'), text)
    assertRulesText(text, { seat: 'implementer', keys: I })
    // Nothing is refused for a rule: the Edit is denied for the missing skill only, and allowed once the skill is recorded.
    assert.equal(isDeny(await run(GATE, editCall(s), fx, { apiUrl: server.origin })), true)
    await run(RECORD, { session_id: s, agent_id: 'agent-1', tool_name: 'Skill', tool_input: { skill: 'p:a' }, tool_response: { success: true } }, fx)
    assert.ok(isEmptyOut(await run(GATE, editCall(s), fx, { apiUrl: server.origin })))
  })
})

// ---- S4 / S5 / S6 / S8: the declared lists -----------------------------------------------------------------------------

test('S4 (AC3): a reviewer declaring rules [] is told the protocol key only', async () => {
  await withStub({ schema: schemaView({ seats: [planner(), implementer(), reviewer({ rules: [] })] }) }, async (server, fx) => {
    const res = await run(HINT, ctxCall(sid('s4'), ROLE_CTX.reviewer), fx, { apiUrl: server.origin })
    assertRulesText(contextOf(res), { seat: 'reviewer', keys: ['protocol.read-only-agent'] })
  })
})

test('S5 (R4): a planner declaring [protocol.entry-seat, x-rule] is told [protocol.in-phase-seat, x-rule]; entry-seat is named nowhere', async () => {
  await withStub({ schema: schemaView({ seats: [planner({ rules: ['protocol.entry-seat', 'x-rule'] }), implementer(), reviewer()] }) }, async (server, fx) => {
    const text = contextOf(await run(HINT, ctxCall(sid('s5'), ROLE_CTX.planner), fx, { apiUrl: server.origin }))
    assertRulesText(text, { seat: 'planner', keys: ['protocol.in-phase-seat', 'x-rule'] })
    assert.equal(text.includes('protocol.entry-seat'), false, text)
  })
})

test('S6 (R4): a served protocol key named as a note skill on a note of another role is not in the hint', async () => {
  const notes = [
    { key: 'implementation-notes', role: 'work', required: true, seat: 'implementer', skill: 'protocol.read-only-agent' },
    { key: 'review-checklist', role: 'review', required: true, seat: 'reviewer', skill: 'protocol.in-phase-seat' },
  ]
  await withStub({ schema: schemaView({ notes }) }, async (server, fx) => {
    const impl = contextOf(await run(HINT, ctxCall(sid('s6a'), ROLE_CTX.implementer), fx, { apiUrl: server.origin }))
    assertRulesText(impl, { seat: 'implementer', keys: I })
    assert.equal(impl.includes('protocol.read-only-agent'), false, impl)
    const rev = contextOf(await run(HINT, ctxCall(sid('s6b'), ROLE_CTX.reviewer), fx, { apiUrl: server.origin }))
    assertRulesText(rev, { seat: 'reviewer', keys: V })
    assert.equal(rev.includes('protocol.in-phase-seat'), false, rev)
  })
})

test('S8 (R4): a planner declaring its own protocol key [protocol.in-phase-seat] is told it once', async () => {
  await withStub({ schema: schemaView({ seats: [planner({ rules: ['protocol.in-phase-seat'] }), implementer(), reviewer()] }) }, async (server, fx) => {
    const text = contextOf(await run(HINT, ctxCall(sid('s8'), ROLE_CTX.planner), fx, { apiUrl: server.origin }))
    assertRulesText(text, { seat: 'planner', keys: ['protocol.in-phase-seat'] })
    assert.equal(text.split('protocol.in-phase-seat').length - 1, 2, 'once in the list and once in the fatal clause')
  })
})

// ---- S7: each failure alone --------------------------------------------------------------------------------------------

test('S7: no API URL gives an empty hint; twin: with the URL the hint is given', async () => {
  await withStub({}, async (server, fx) => {
    assert.ok(isEmptyOut(await run(HINT, ctxCall(sid('s7a')), fx)))
    assert.ok(contextOf(await run(HINT, ctxCall(sid('s7b')), fx, { apiUrl: server.origin })).includes('SEAT RULES:'))
  })
})

test('S7: a schema read answering 500 gives an empty hint', async () => {
  await withStub({ schema: errEnvelope(500, 'internal_error') }, async (server, fx) => {
    const res = await run(HINT, ctxCall(sid('s7c')), fx, { apiUrl: server.origin })
    assert.equal(res.status, 0, res.stderr)
    assert.ok(isEmptyOut(res), res.stdout)
  })
})

test('S7: a served read that fails leaves the keys unfiltered and adds the not_found clause for the other keys', async () => {
  for (const rules of [errEnvelope(404, 'not_found'), errEnvelope(500, 'internal_error')]) {
    await withStub({ rules }, async (server, fx) => {
      const res = await run(HINT, ctxCall(sid('s7d')), fx, { apiUrl: server.origin })
      assert.equal(res.status, 0, res.stderr)
      assertRulesText(contextOf(res), { seat: 'implementer', keys: I, answered: false })
    })
  }
})

test('S7: a served read that answers without closure-notes filters it out and needs no skip clause', async () => {
  await withStub({ rules: rulesOk(['commit-discipline', 'protocol.entry-seat']) }, async (server, fx) => {
    const text = contextOf(await run(HINT, ctxCall(sid('s7e')), fx, { apiUrl: server.origin }))
    assertRulesText(text, { seat: 'implementer', keys: ['protocol.entry-seat', 'commit-discipline'] })
    assert.equal(text.includes('closure-notes'), false, text)
  })
})

test('S7: a project with no rootId says "(rootId: your project root)"', async () => {
  await withStub({}, async (server, fx) => {
    const text = contextOf(await run(HINT, ctxCall(sid('s7f')), fx, { apiUrl: server.origin }))
    assertRulesText(text, { seat: 'implementer', keys: I, rootId: null })
  }, { rootId: null })
})

/** A throwaway copy of hooks/ and scripts/ (without their tests) whose hooks/lib/seat-rules.json is replaced by `write`. */
function pluginCopyWith(write) {
  const root = fresh('to-seat-rules-plugin-')
  for (const part of ['hooks', 'scripts', '.claude-plugin']) {
    const from = join(PLUGIN_ROOT, part)
    if (!existsSync(from)) continue
    cpSync(from, join(root, part), { recursive: true, filter: (src) => relative(from, src).split(sep)[0] !== 'tests' })
  }
  write(join(root, 'hooks', 'lib', 'seat-rules.json'))
  return join(root, 'hooks', 'seat-skill-hint.mjs')
}

test('S7: with seat-rules.json removed, or unreadable as JSON, the hint has no SEAT RULES text and the skills text is still given', async () => {
  const removed = pluginCopyWith((p) => rmSync(p))
  const broken = pluginCopyWith((p) => writeFileSync(p, '{ not json', 'utf-8'))
  for (const hint of [removed, broken]) {
    await withStub({ schema: schemaView({ seats: [planner(), implementer({ skills: ['p:a'] }), reviewer()] }) }, async (server, fx) => {
      const res = await run(hint, ctxCall(sid('s7g')), fx, { apiUrl: server.origin })
      assert.equal(res.status, 0, res.stderr)
      const text = contextOf(res)
      assert.ok(text.startsWith(SEAT_SKILLS_PREFIX), text)
      assert.equal(text.includes('SEAT RULES'), false, text)
    })
  }
  // Twin: the unmodified plugin gives the rules text on the same fixture.
  await withStub({ schema: schemaView({ seats: [planner(), implementer({ skills: ['p:a'] }), reviewer()] }) }, async (server, fx) => {
    assert.ok(contextOf(await run(HINT, ctxCall(sid('s7h')), fx, { apiUrl: server.origin })).includes('SEAT RULES:'))
  })
})

test('S7 (budget): a served read that hangs past its own timeout still lets the hint exit under the 5 s hook timeout, unfiltered', async () => {
  assert.ok(RULES_TIMEOUT_MS < 4000, `the served read has its own timeout well inside the hook's 5 s: ${RULES_TIMEOUT_MS}`)
  await withStub({ rulesDelay: RULES_TIMEOUT_MS + 800 }, async (server, fx) => {
    const res = await run(HINT, ctxCall(sid('s7t')), fx, { apiUrl: server.origin })
    assert.equal(res.status, 0, res.stderr)
    assert.ok(res.ms < 5000, `took ${res.ms} ms`)
    assertRulesText(contextOf(res), { seat: 'implementer', keys: I, answered: false })
  })
})

// ---- S11: resume and compaction ---------------------------------------------------------------------------------------

test('S11: the same agent again gets nothing; a new agent in the same session is hinted', async () => {
  await withStub({}, async (server, fx) => {
    const s = sid('s11')
    assert.ok(contextOf(await run(HINT, ctxCall(s, { agentId: 'agent-1' }), fx, { apiUrl: server.origin })).includes('SEAT RULES:'))
    assert.ok(isEmptyOut(await run(HINT, ctxCall(s, { agentId: 'agent-1' }), fx, { apiUrl: server.origin })), 'same agent again')
    assertRulesText(contextOf(await run(HINT, ctxCall(s, { agentId: 'agent-2' }), fx, { apiUrl: server.origin })), { seat: 'implementer', keys: I })
  })
})

test('S11: a main-session advance to review names the reviewer keys V once; the same advance again gives nothing', async () => {
  await withStub({}, async (server, fx) => {
    const s = sid('s11m')
    const el = { ok: true, itemId: U1, newRole: 'review' }
    const first = await run(HINT, advCall(s, el), fx, { apiUrl: server.origin })
    assert.equal(first.status, 0, first.stderr)
    assertRulesText(contextOf(first), { seat: 'reviewer', keys: V })
    assert.ok(isEmptyOut(await run(HINT, advCall(s, el), fx, { apiUrl: server.origin })), 'the same item and role again')
  })
})
