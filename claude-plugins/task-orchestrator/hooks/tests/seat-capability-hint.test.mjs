// Item 2f3833fb: the SEAT RULES hint follows the served seat facts (enters and writes) when the server advertises seatWrites.
// Scenarios AC1 and AC2 of the item's specification on the hint, as spawned node hook runs against a stub server.
//
// Oracles: the specification's literal lists (planner: protocol.read-only-agent, planning-sweeps, review-cited-claims; implementer:
// protocol.entry-seat, commit-discipline, closure-notes; a review seat with writes true: protocol.in-phase-seat, commit-discipline; a seat
// with no defaults row: its protocol key only). The old-path twin proves the same schema without seatWrites keeps the role path.
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


const SERVED_FEATURES = ['seats', 'dispatchBySeat', 'seatSkills', 'seatWrites', 'orchestratorNotes']
const OLD_FEATURES = ['seats', 'dispatchBySeat', 'seatSkills']
const ENTRY = 'protocol.entry-seat'
const IN_PHASE = 'protocol.in-phase-seat'
const READ_ONLY = 'protocol.read-only-agent'

async function hintFor({ seats, features, ctx }) {
  return withStub({ schema: schemaView({ seats, features }) }, async (server, fx) => {
    const res = await run(HINT, ctxCall(sid('cap'), ctx), fx, { apiUrl: server.origin })
    assert.equal(res.status, 0, res.stderr)
    return contextOf(res)
  })
}

test('AC1 (hint): served planner (writes false) is read-only with its defaults; served implementer (enters, writes) keeps the entry protocol', async () => {
  const seats = [planner({ writes: false }), implementer({ writes: true }), reviewer({ writes: false })]
  const plannerText = await hintFor({ seats, features: SERVED_FEATURES, ctx: ROLE_CTX.planner })
  assertRulesText(plannerText, { seat: 'planner', keys: [READ_ONLY, 'planning-sweeps', 'review-cited-claims'] })
  const implText = await hintFor({ seats, features: SERVED_FEATURES, ctx: ROLE_CTX.implementer })
  assertRulesText(implText, { seat: 'implementer', keys: [ENTRY, 'commit-discipline', 'closure-notes'] })
})

test('AC1 (hint): the same schema without seatWrites keeps the role path: the planner gets the in-phase protocol', async () => {
  const seats = [planner({ writes: false }), implementer({ writes: true }), reviewer({ writes: false })]
  const text = await hintFor({ seats, features: OLD_FEATURES, ctx: ROLE_CTX.planner })
  assertRulesText(text, { seat: 'planner', keys: [IN_PHASE, 'planning-sweeps', 'review-cited-claims'] })
  assert.equal(text.includes(READ_ONLY), false, 'fixture condition: the served twin names read-only-agent')
})

test('AC2 (hint): a served test-author that enters with writes gets the entry protocol and commit-discipline; a review seat fixer with writes gets in-phase and commit-discipline', async () => {
  const seats = [planner({ writes: false }), { name: 'test-author', phase: 'work', enters: true, writes: true }, { name: 'fixer', phase: 'review', writes: true }]
  const author = await hintFor({ seats, features: SERVED_FEATURES, ctx: { agentType: 'task-orchestrator:test-author', role: 'work', callerSeat: 'test-author' } })
  assertRulesText(author, { seat: 'test-author', keys: [ENTRY, 'commit-discipline'] })
  const fixer = await hintFor({ seats, features: SERVED_FEATURES, ctx: { agentType: 'task-orchestrator:reviewer', role: 'review', callerSeat: 'fixer' } })
  assertRulesText(fixer, { seat: 'fixer', keys: [IN_PHASE, 'commit-discipline'] })
})

test('AC2 (hint): a served queue seat with no defaults row and no declared rules gets its protocol key only', async () => {
  const seats = [{ name: 'drafter', phase: 'queue', writes: false }, implementer({ writes: true })]
  const text = await hintFor({ seats, features: SERVED_FEATURES, ctx: { agentType: 'task-orchestrator:planner', role: 'queue', callerSeat: 'drafter' } })
  assertRulesText(text, { seat: 'drafter', keys: [READ_ONLY] })
})
