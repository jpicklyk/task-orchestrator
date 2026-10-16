// Tests of the retrospective feature on the 4.0 run-mark model (item 5dbd8275, scenarios S15 and S16; AC8 of the
// frozen task-scope).
//
// The 3.x tests of this file covered the advance/complete-tree triggers, the ack command, the cooldown and the
// recorded roots; those behaviours are gone (a retrospective is owed per closed run, deduped by the server-side
// `retro` mark). The behaviour that survives is kept with its assertions: the Stop-time spawn with its busy hold and
// fallback block, the owned-event flag, the retro agents' own calls being ignored, mode off / no config / headless.
//
// Oracles: claude-plugins/task-orchestrator/hooks/retro-lib.mjs at the base commit (config location, the run
// evaluation rules, the nudge and dispatch texts), current/docs/api-reference.md "runs_read" (list with `unmarked`
// and `endedSince`, `frontier` counts) and "runs_write" (`close` and `mark`; a batch with failures is `partial_failure`
// next to the full `data.results`; `mark` answers `marked`, false for a replay), task-scope AC8.
import type { On } from 'claude-code'
import { expect, test } from 'claude-code/testing'

import type { RetroState } from '../types'
import { RETRO_OWNED_FLAG } from '../src/retro/index.ts'
import {
  MAX_RECORDED,
  QUEUED_NOTICE,
  buildDispatch,
  buildNudge,
  buildSpawnPrompt,
  evaluateRuns,
  holdsForTasks,
  normalizeState,
  parseRetrospectiveConfig,
  recordCloses,
  successfulCloses,
} from '../src/retro/logic.ts'
import type { RetroConfig } from '../src/retro/logic.ts'

const TO = 'mcp__mcp-task-orchestrator__'
const ROOT = 'ce064d2c-7c03-4eac-ae7d-89a14c2ba273'
const R1 = 'a1111111-0000-4000-8000-000000000001'
const R2 = 'b2222222-0000-4000-8000-000000000002'
const R3 = 'c3333333-0000-4000-8000-000000000003'
const HOUR = 3_600_000

const cfg = (over: Partial<RetroConfig> = {}): RetroConfig => ({ mode: 'dispatch', dispatchThreshold: 3, ...over })
const retroYaml = (mode: string, threshold = 3) => `extensions:\n  claude-code:\n    retrospective:\n      mode: ${mode}\n      dispatchThreshold: ${threshold}\n`

// --- config ----------------------------------------------------------------------------

test('config: defaults, block form, inline form and invalid values', () => {
  expect(parseRetrospectiveConfig(null)).toEqual({ mode: 'nudge', dispatchThreshold: 3 })
  expect(parseRetrospectiveConfig('')).toEqual({ mode: 'nudge', dispatchThreshold: 3 })
  expect(parseRetrospectiveConfig(retroYaml('Dispatch', 5))).toEqual({ mode: 'dispatch', dispatchThreshold: 5 })
  expect(parseRetrospectiveConfig('extensions:\n  claude-code:\n    retrospective: { mode: off }\n').mode).toBe('off')
  expect(parseRetrospectiveConfig(retroYaml('bogus', -1))).toEqual({ mode: 'nudge', dispatchThreshold: 3 })
  expect(parseRetrospectiveConfig(retroYaml('dispatch', 0)).dispatchThreshold).toBe(0)
  expect(parseRetrospectiveConfig('extensions:\n  claude-code:\n    retrospective:\n      dispatchThreshold: 2.5\n').dispatchThreshold).toBe(3)
})

test('config: only extensions.claude-code.retrospective is read; a top-level retrospective block is not; no cooldown member', () => {
  const topLevel = 'retrospective:\n  mode: dispatch\n  dispatchThreshold: 7\n'
  expect(parseRetrospectiveConfig(topLevel)).toEqual({ mode: 'nudge', dispatchThreshold: 3 })
  const both = `${topLevel}\n${retroYaml('off', 1)}`
  expect(parseRetrospectiveConfig(both)).toEqual({ mode: 'off', dispatchThreshold: 1 })
  expect('cooldownMinutes' in parseRetrospectiveConfig(retroYaml('dispatch'))).toBe(false)
  // a sibling extension does not leak in
  expect(parseRetrospectiveConfig('extensions:\n  other:\n    retrospective:\n      mode: dispatch\n')).toEqual({ mode: 'nudge', dispatchThreshold: 3 })
})

// --- texts --------------------------------------------------------------------------------

test('texts: nudge and dispatch carry the per-run command; the spawn prompt names the runs and the scope', () => {
  expect(buildNudge([])).not.toMatch(/[0-9a-f]{8}-/)
  expect(buildNudge([R1])).toContain(`/session-retrospective --run ${R1}`)
  expect(buildNudge([R1, R2])).toContain(`\`/session-retrospective --run ${R1}\` and \`/session-retrospective --run ${R2}\``)
  expect(buildNudge([R1]).startsWith('Retrospective suggested')).toBe(true)
  expect(buildDispatch([R1, R2], ROOT)).toContain(`project ancestorId ${ROOT}`)
  expect(buildDispatch([R1, R2], ROOT)).toContain(`/session-retrospective --run ${R1}`)
  expect(buildDispatch([R1], null)).toContain('none configured')
  expect(buildDispatch([R1], ROOT).startsWith('Retrospective dispatch (mode: dispatch)')).toBe(true)
  const spawn = buildSpawnPrompt([R1, R2], ROOT)
  expect(spawn).toContain(`/session-retrospective --run ${R1}`)
  expect(spawn).toContain(`/session-retrospective --run ${R2}`)
  expect(spawn).toContain(`project ancestorId ${ROOT}`)
  for (const text of [spawn, buildSpawnPrompt([R1], null)]) {
    expect(text).not.toContain('depth 0')
    expect(text).not.toContain('OUTSIDE any project root')
  }
  expect(QUEUED_NOTICE).not.toContain('subagent_type')
  expect(holdsForTasks([{ status: 'completed' }, { status: 'pending' }])).toBe(true)
  expect(holdsForTasks([{ status: 'completed' }, { status: 'failed' }])).toBe(false)
})

// --- the loaded plugin ----------------------------------------------------------------------

type Agent = { id: string; status: string; parentId?: string; description?: string; type?: string }
type Proc = { argv: readonly string[]; stdin?: string; body: { ops: { runId: string }[] } }
type Mcp = { runs: View[]; terminal: Record<string, number>; mark: Record<string, 'won' | 'lost' | 'fail'>; calls: { tool: string; args: Record<string, unknown> }[]; marked: Set<string>; procs: Proc[]; proc: { exitCode: number; stdout?: string } }
type Rig = {
  state: Map<string, { value: unknown; version: number }>
  spawns: { prompt: string; subagentType?: string; model?: string; description?: string }[]
  seen: Record<string, unknown>[]
  now: { t: number }
  agents: Agent[]
  spawnMode: { deny?: string; throws?: boolean }
  /** What the base `tool.call` answers. */
  reply: () => unknown
  /** What the base `classic.Stop` answers, and the events it received. */
  stopReply: Record<string, unknown>
  stops: Record<string, unknown>[]
  post: Record<string, unknown>[]
  mcp: Mcp
}

const retroState = (rig: Rig): RetroState => (rig.state.get('task-orchestrator-mod/retro/')?.value ?? {}) as RetroState

/** In-memory `$.state`, `$.env`, `$.fs`, `$.clock`, `$.agent` and `$.mcp` on the test's `on`. */
function rig(on: On, opts: { config?: string | null; env?: Record<string, string>; agents?: Agent[]; files?: Record<string, string>; noApi?: boolean } = {}): Rig {
  const r: Rig = {
    state: new Map(),
    spawns: [],
    seen: [],
    now: { t: 1_000_000 },
    agents: opts.agents ?? [],
    spawnMode: {},
    reply: () => ({ ref: 'r', result: 'ok', text: 'ok' }),
    stopReply: {},
    stops: [],
    post: [],
    mcp: { runs: [], terminal: {}, mark: {}, calls: [], marked: new Set(), procs: [], proc: { exitCode: 0 } },
  }
  const config = opts.config === undefined ? `project:\n  rootId: ${ROOT}\n\n${retroYaml('dispatch')}` : opts.config
  // no REST API: no URL in the env, and no client.json anywhere (only the project config is readable)
  const files = opts.noApi ? { ...opts.files, '.taskorchestrator/config.yaml': config ?? '' } : opts.files
  const slot = (e: { plugin: string; key: string; id?: string }) => `${e.plugin}/${e.key}/${e.id ?? ''}`
  on('state.get', async (_$, e) => ({ value: r.state.get(slot(e as never)) ?? { value: undefined, version: 0 } }) as never)
  on('state.set', async (_$, e) => {
    const k = slot(e as never)
    const cur = r.state.get(k)?.version ?? 0
    const ifVersion = (e as { ifVersion?: number }).ifVersion
    if (ifVersion !== undefined && ifVersion !== cur) return { value: { isSet: false, version: cur } } as never
    r.state.set(k, { value: (e as { value: unknown }).value, version: cur + 1 })

    return { value: { isSet: true, version: cur + 1 } } as never
  })
  const env: Record<string, string> = opts.noApi ? { ...opts.env } : { TASK_ORCHESTRATOR_API_URL: 'http://127.0.0.1:8080', ...opts.env }
  on('env.get', async (_$, e) => ({ value: env[e.name] }) as never)
  on('fs.read', async (_$, e) => {
    const byPath = files?.[(e as { path: string }).path]
    if (byPath !== undefined) return { value: byPath } as never
    if (files !== undefined && (e as { path: string }).path !== '.taskorchestrator/config.yaml') throw new Error('ENOENT')
    if (config === null) throw new Error('ENOENT')

    return { value: config } as never
  })
  on('tool.call', async () => r.reply() as never)
  on('classic.Stop', async (_$, e) => {
    r.stops.push(e as never)

    return r.stopReply as never
  })
  on('classic.PostToolUse', async (_$, e) => {
    r.post.push(e as never)

    return {} as never
  })
  on('clock.now', async () => ({ value: r.now.t }) as never)
  on('agent.list', async () => ({ value: r.agents }) as never)
  on('agent.spawn', async (_$, e) => {
    if (r.spawnMode.throws) throw new Error('boom')
    if (r.spawnMode.deny) return { deny: r.spawnMode.deny } as never
    r.spawns.push({ prompt: e.prompt, subagentType: (e as { subagent_type?: string }).subagent_type ?? e.subagentType, model: e.model, description: e.description })

    return { model: 'sonnet', agentId: `retro-${r.spawns.length}` } as never
  })
  on('mcp.call', async (_$, e) => {
    r.mcp.calls.push({ tool: e.tool, args: e.args })
    const done = (envelope: unknown) => ({ value: { content: [{ type: 'text', text: 'ok' }], structuredContent: envelope, isError: (envelope as { ok: boolean }).ok === false } }) as never
    if (e.tool === 'runs_read' && e.args.select === 'list') {
      // the server honours unmarked: 'retro' - a run whose mark was written is no longer listed
      const items = r.mcp.runs.filter(v => !r.mcp.marked.has(v.runId))

      return done({ ok: true, data: { items, page: { total: items.length, truncated: false } } })
    }
    if (e.tool === 'runs_read' && e.args.select === 'frontier') {
      const t = r.mcp.terminal[String(e.args.runId)]
      if (t === undefined) return done({ ok: false, error: { code: 'not_found', kind: 'permanent', message: 'no such run' } })

      return done({ ok: true, data: { runId: e.args.runId, status: 'closed', counts: { queue: 0, work: 0, review: 0, blocked: 0, terminal: t }, ready: [], inFlight: [], waiting: [] } })
    }
    return done({ ok: false, error: { code: 'invalid_request', kind: 'permanent', message: `unexpected ${e.tool}` } })
  })

  // The mark is a REST write (`runs_write` `mark` has no MCP profile): curl through `$.process.run`.
  on('process.run', async (_$, e) => {
    const x = e as { argv: readonly string[]; init?: { stdin?: string }; stdin?: string }
    const at = x.argv.indexOf('--data-raw')
    const body = JSON.parse(String(x.argv[at + 1])) as Proc['body']
    r.mcp.procs.push({ argv: x.argv, stdin: x.init?.stdin ?? x.stdin, body })
    if (r.mcp.proc.exitCode !== 0) return { value: { exitCode: r.mcp.proc.exitCode, stdout: '', stderr: 'curl: failed' } } as never
    if (r.mcp.proc.stdout !== undefined) return { value: { exitCode: 0, stdout: r.mcp.proc.stdout, stderr: '' } } as never
    const op = body.ops[0] as { runId: string }
    const m = r.mcp.mark[op.runId] ?? 'won'
    if (m === 'fail') {
      const failed = {
        ok: false,
        error: { code: 'partial_failure', kind: 'permanent', message: '1 of 1 failed' },
        data: { results: [{ ok: false, error: { code: 'forbidden', kind: 'permanent', message: 'no' } }], summary: { ok: 0, failed: 1 } },
      }

      return { value: { exitCode: 0, stdout: JSON.stringify(failed), stderr: '' } } as never
    }

    const first = m === 'won' && !r.mcp.marked.has(op.runId)
    if (first) r.mcp.marked.add(op.runId)
    const done = { ok: true, data: { results: [{ ok: true, runId: op.runId, marked: first }], summary: { ok: 1, failed: 0 } } }

    return { value: { exitCode: 0, stdout: JSON.stringify(done), stderr: '' } } as never
  })

  return r
}

/** The answer of a runs_write close for the given runs, all ok. */
const closeReply = (...runIds: string[]) => ({ ref: 'r', result: { structuredContent: { ok: true, data: { results: runIds.map(runId => ({ ok: true, runId, status: 'closed' })), summary: { ok: runIds.length, failed: 0 } } } }, text: 'ok' })
const closeCall = (...runIds: string[]) => ({ tool: `${TO}runs_write`, ops: runIds.map(runId => ({ op: 'close', runId, reason: 'completed' })) }) as never
/** The marks written: the bodies POSTed through `$.process.run`. */
const markCalls = (g: Rig) => g.mcp.procs.map(p => ({ tool: 'runs_write', args: p.body as unknown as Record<string, unknown> }))
/** The real server refuses `mark` on MCP, so no mark may ride `mcp.call`. */
const mcpMarks = (g: Rig) => g.mcp.calls.filter(c => c.tool === 'runs_write')

test('S15: a close at the threshold in dispatch mode marks the run, adds only the one-line notice, and spawns nothing yet', async ($, on) => {
  const g = rig(on)
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 3 }
  g.reply = () => closeReply(R1)
  const r = await $.tool.call(closeCall(R1))
  expect(r.context).toEqual([QUEUED_NOTICE])
  expect(JSON.stringify(r.context)).not.toContain('Retrospective dispatch')
  expect(retroState(g).pendingDispatch).toEqual([R1])
  expect(markCalls(g).map(c => c.args)).toEqual([{ ops: [{ op: 'mark', runId: R1, key: 'retro' }] }])
  expect(mcpMarks(g)).toHaveLength(0)
  expect(g.spawns).toHaveLength(0)
})

test('S15: a lost mark race adds nothing and queues nothing', async ($, on) => {
  const g = rig(on)
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 3 }
  g.mcp.mark = { [R1]: 'lost' }
  g.reply = () => closeReply(R1)
  const r = await $.tool.call(closeCall(R1))
  expect(r.context).toBeUndefined()
  expect(retroState(g).pendingDispatch ?? null).toBeNull()
  expect(markCalls(g)).toHaveLength(1)
  expect(mcpMarks(g)).toHaveLength(0)
})

test('S15: a failed mark nudges (never dispatches unmarked)', async ($, on) => {
  const g = rig(on)
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 3 }
  g.mcp.mark = { [R1]: 'fail' }
  g.reply = () => closeReply(R1)
  const r = await $.tool.call(closeCall(R1))
  expect(r.context?.[0]).toContain('Retrospective suggested')
  expect(r.context?.[0]).toContain(`/session-retrospective --run ${R1}`)
  expect(retroState(g).pendingDispatch ?? null).toBeNull()
  expect(mcpMarks(g)).toHaveLength(0)
})

test('S15: below the threshold in dispatch mode, and nudge mode, add a nudge and write no mark', async ($, on) => {
  const g = rig(on)
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 2 }
  g.reply = () => closeReply(R1)
  const r = await $.tool.call(closeCall(R1))
  expect(r.context?.[0]).toContain('Retrospective suggested')
  expect(r.context?.[0]).toContain(`/session-retrospective --run ${R1}`)
  expect(retroState(g).pendingDispatch ?? null).toBeNull()
  expect(markCalls(g)).toHaveLength(0)
  expect(mcpMarks(g)).toHaveLength(0)
})

test('S15: the list read names the root, the retro mark filter and the 24 hour window', async ($, on) => {
  const g = rig(on)
  g.mcp.runs = []
  g.reply = () => closeReply(R1)
  await $.tool.call(closeCall(R1))
  const list = g.mcp.calls.find(c => c.tool === 'runs_read' && c.args.select === 'list')
  expect(list?.args).toEqual({ select: 'list', rootId: ROOT, unmarked: 'retro', endedSince: new Date(g.now.t - 24 * HOUR).toISOString(), limit: 20 })
})

test('S15: a run that already carries the retro mark is skipped', async ($, on) => {
  const g = rig(on)
  g.mcp.runs = [{ runId: R1, marks: { retro: '2026-10-01T00:00:00Z' } }]
  g.mcp.terminal = { [R1]: 5 }
  g.reply = () => closeReply(R1)
  const skipped = await $.tool.call(closeCall(R1))
  expect(skipped.context).toBeUndefined()
  expect(g.mcp.calls.some(c => c.args.select === 'frontier')).toBe(false)
  expect(markCalls(g)).toHaveLength(0)
})

const TOKEN = 'test-token-abc'

test('D7: the mark is a POST to <base>/api/v1/ops/runs_write; the token rides stdin, never argv', async ($, on) => {
  const g = rig(on, { env: { TASK_ORCHESTRATOR_API_TOKEN: TOKEN } })
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 3 }
  g.reply = () => closeReply(R1)
  const r = await $.tool.call(closeCall(R1))
  expect(g.mcp.procs).toHaveLength(1)
  const p = g.mcp.procs[0] as Proc
  expect(p.argv.at(-1)).toBe('http://127.0.0.1:8080/api/v1/ops/runs_write')
  expect(p.argv).toContain('-X')
  expect(p.argv[p.argv.indexOf('-X') + 1]).toBe('POST')
  expect(p.body).toEqual({ ops: [{ op: 'mark', runId: R1, key: 'retro' }] })
  expect(p.argv.some(a => a.includes(TOKEN))).toBe(false)
  expect(p.stdin ?? '').toContain(`Authorization: Bearer ${TOKEN}`)
  expect(mcpMarks(g)).toHaveLength(0)
  // the token is sent only in that header: it appears in no state, context, spawn or MCP call
  for (const seen of [r.context, [...g.state.values()], g.spawns, g.mcp.calls]) expect(JSON.stringify(seen ?? null)).not.toContain(TOKEN)
})

test('D7: with no REST API URL a dispatch-eligible run is nudged, nothing is spawned or queued, no process.run', async ($, on) => {
  const g = rig(on, { noApi: true, env: { TASK_ORCHESTRATOR_API_TOKEN: TOKEN } })
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 3 }
  g.reply = () => closeReply(R1)
  const r = await $.tool.call(closeCall(R1))
  expect(r.context?.[0]).toContain('Retrospective suggested')
  expect(r.context?.[0]).toContain(`/session-retrospective --run ${R1}`)
  expect(retroState(g).pendingDispatch ?? null).toBeNull()
  expect(g.spawns).toHaveLength(0)
  expect(g.mcp.procs).toHaveLength(0)
  expect(mcpMarks(g)).toHaveLength(0)
  await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(g.spawns).toHaveLength(0)
  expect(g.mcp.procs).toHaveLength(0)
  for (const seen of [r.context, [...g.state.values()], g.spawns]) expect(JSON.stringify(seen ?? null)).not.toContain(TOKEN)
})

async function failedCurl($: Parameters<Parameters<typeof test>[1]>[0], on: On, proc: { exitCode: number; stdout?: string }) {
  const g = rig(on, { env: { TASK_ORCHESTRATOR_API_TOKEN: TOKEN } })
  g.mcp.proc = proc
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 3 }
  g.reply = () => closeReply(R1)
  const r = await $.tool.call(closeCall(R1))
  expect(g.mcp.procs).toHaveLength(1)
  expect(r.context?.[0]).toContain('Retrospective suggested')
  expect(r.context?.[0]).toContain(`/session-retrospective --run ${R1}`)
  expect(retroState(g).pendingDispatch ?? null).toBeNull()
  expect(g.spawns).toHaveLength(0)
  expect(mcpMarks(g)).toHaveLength(0)
  for (const seen of [r.context, [...g.state.values()]]) expect(JSON.stringify(seen ?? null)).not.toContain(TOKEN)
}

test('D7: curl exit 7 is a failed mark - nudge, never dispatched unmarked', async ($, on) => failedCurl($, on, { exitCode: 7 }))
test('D7: a non-JSON body is a failed mark - nudge, never dispatched unmarked', async ($, on) => failedCurl($, on, { exitCode: 0, stdout: 'not json <html>' }))

test('S15: a nudged run is not nudged twice', async ($, on) => {
  const h = rig(on)
  h.mcp.runs = [{ runId: R2 }]
  h.mcp.terminal = { [R2]: 1 }
  h.reply = () => closeReply(R2)
  const first = await $.tool.call(closeCall(R2))
  expect(first.context?.[0]).toContain('Retrospective suggested')
  const second = await $.tool.call(closeCall(R2))
  expect(second.context).toBeUndefined()
})

test('S15: a subagent close is recorded without any server read', async ($, on) => {
  const g = rig(on)
  g.reply = () => closeReply(R1, R2)
  const sub = await $.tool.call({ ...(closeCall(R1, R2) as object), agentId: 'impl-1' } as never)
  expect(sub.context).toBeUndefined()
  expect(retroState(g)).toMatchObject({ sawClose: true, closes: [R1, R2] })
  expect(g.mcp.calls).toHaveLength(0)
})

test('S15: a close with no project rootId is recorded without any server read', async ($, on) => {
  const h = rig(on, { config: retroYaml('dispatch') })
  h.reply = () => closeReply(R3)
  const noRoot = await $.tool.call(closeCall(R3))
  expect(noRoot.context).toBeUndefined()
  expect(retroState(h)).toMatchObject({ sawClose: true, closes: [R3] })
  expect(h.mcp.calls).toHaveLength(0)
})

test('S15: only runs_write closes count - an advance, a run open, a failed close and a mark do not', async ($, on) => {
  const g = rig(on)
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 5 }
  g.reply = () => ({ ref: 'r', result: { structuredContent: { ok: true, data: { results: [{ ok: true, itemId: 'abcdef12-0000-4000-8000-000000000000', newRole: 'terminal' }] } } }, text: 'ok' })
  expect((await $.tool.call({ tool: `${TO}advance`, ops: [{ op: 'transition', itemId: 'abcdef12', trigger: 'complete' }] } as never)).context).toBeUndefined()
  g.reply = () => ({ ref: 'r', result: { structuredContent: { ok: true, data: { results: [{ ok: true, runId: R1, status: 'open' }] } } }, text: 'ok' })
  expect((await $.tool.call({ tool: `${TO}runs_write`, ops: [{ op: 'open', rootId: ROOT }] } as never)).context).toBeUndefined()
  g.reply = () => ({ ref: 'r', result: { structuredContent: { ok: false, error: { code: 'partial_failure', kind: 'permanent', message: 'x' }, data: { results: [{ ok: false, error: { code: 'run_closed', kind: 'permanent', message: 'closed' } }] } } }, text: 'ok' })
  expect((await $.tool.call(closeCall(R1))).context).toBeUndefined()
  g.reply = () => ({ ref: 'r', result: { structuredContent: { ok: true, data: { results: [{ ok: true, runId: R1, marked: true }] } } }, text: 'ok' })
  expect((await $.tool.call({ tool: `${TO}runs_write`, ops: [{ op: 'mark', runId: R1, key: 'retro' }] } as never)).context).toBeUndefined()
  expect(g.mcp.calls).toHaveLength(0)
  expect(g.state.size).toBe(0)
})

test('S15: mode off has no effect and touches no state', async ($, on) => {
  const g = rig(on, { config: `project:\n  rootId: ${ROOT}\n\n${retroYaml('off')}` })
  g.reply = () => closeReply(R1)
  const r1 = await $.tool.call(closeCall(R1))
  expect(r1.context).toBeUndefined()
  expect(g.state.size).toBe(0)
  expect(g.mcp.calls).toHaveLength(0)
})

test('S16: a top-level retrospective block is not read: the default nudge mode applies and no mark is written', async ($, on) => {
  const g = rig(on, { config: `project:\n  rootId: ${ROOT}\n\nretrospective:\n  mode: dispatch\n  dispatchThreshold: 1\n` })
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 9 }
  g.reply = () => closeReply(R1)
  const r = await $.tool.call(closeCall(R1))
  expect(r.context?.[0]).toContain('Retrospective suggested')
  expect(markCalls(g)).toHaveLength(0)
  expect(mcpMarks(g)).toHaveLength(0)
})

test('S15: no cwd config leaves everything to the command hooks', async ($, on) => {
  const g = rig(on, { config: null })
  g.reply = () => closeReply(R1)
  const r = await $.tool.call(closeCall(R1))
  expect(r.context).toBeUndefined()
  expect(g.state.size).toBe(0)
  const e = { tool_name: `${TO}runs_write`, tool_input: {}, tool_response: {}, tool_use_id: 't' }
  await $.classic.PostToolUse(e as never)
  await $.classic.Stop({ stop_hook_active: false } as never)
  expect(g.post[0]).toMatchObject(e)
  expect(g.post[0]?.[RETRO_OWNED_FLAG]).toBeUndefined()
  expect(g.state.size).toBe(0)
})

test('S15: a headless iteration is not owned', async ($, on) => {
  const g = rig(on, { env: { TASK_ORCHESTRATOR_MODE: 'headless-iteration' } })
  g.reply = () => closeReply(R1)
  const r = await $.tool.call(closeCall(R1))
  expect(r.context).toBeUndefined()
  expect(g.state.size).toBe(0)
})

test('S15: owned events reach next carrying to_mod_retro: runs_write PostToolUse and Stop, nothing else', async ($, on) => {
  const g = rig(on)
  await $.classic.PostToolUse({ tool_name: `${TO}runs_write`, tool_input: {}, tool_response: {}, tool_use_id: 't' } as never)
  await $.classic.PostToolUse({ tool_name: `${TO}items_read`, tool_input: {}, tool_response: {}, tool_use_id: 't2' } as never)
  await $.classic.PostToolUse({ tool_name: `${TO}advance`, tool_input: {}, tool_response: {}, tool_use_id: 't3' } as never)
  await $.classic.PostToolUse({ tool_name: 'Bash', tool_input: { command: 'node hooks/retro-ack.mjs' }, tool_response: {}, tool_use_id: 't4' } as never)
  await $.classic.Stop({ stop_hook_active: false } as never)
  expect(g.post[0]?.[RETRO_OWNED_FLAG]).toBe(true)
  for (const i of [1, 2, 3]) expect(g.post[i]?.[RETRO_OWNED_FLAG]).toBeUndefined()
  expect(g.stops[0]?.[RETRO_OWNED_FLAG]).toBe(true)
  expect(g.spawns).toHaveLength(0)
})

/** Queue a dispatch the way a close does: a won mark leaves it pending. */
async function queued($: Parameters<Parameters<typeof test>[1]>[0], g: Rig, ...runs: string[]) {
  g.mcp.runs = runs.map(runId => ({ runId }))
  g.mcp.terminal = Object.fromEntries(runs.map(runId => [runId, 3]))
  g.reply = () => closeReply(...runs)
  await $.tool.call(closeCall(...runs))
}

test('S15: a clear Stop spawns exactly one retrospective with the expected args', async ($, on) => {
  const g = rig(on)
  await queued($, g, R1)
  const r = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(r.block).toBeUndefined()
  expect(g.spawns).toHaveLength(1)
  expect(g.spawns[0]).toMatchObject({ subagentType: 'general-purpose', model: 'sonnet', description: 'Session retrospective' })
  expect(g.spawns[0]?.prompt).toContain(`/session-retrospective --run ${R1}`)
  expect(g.spawns[0]?.prompt).toContain(`project ancestorId ${ROOT}`)
  // A1.3: normalizeState keeps pendingDispatch only as an array, so a cleared queue reads as absent
  expect(retroState(g)).toMatchObject({ dispatched: { runs: [R1] } })
  expect(retroState(g).pendingDispatch ?? null).toBeNull()
  await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(g.spawns).toHaveLength(1)
})

test('S15: a running background task holds the dispatch; the next clear Stop spawns once with the union', async ($, on) => {
  const g = rig(on)
  await queued($, g, R1)
  await $.classic.Stop({ stop_hook_active: false, background_tasks: [{ id: 'b1', type: 'shell', status: 'running', description: 'x' }] } as never)
  expect(g.spawns).toHaveLength(0)
  expect(retroState(g).pendingDispatch).toEqual([R1])
  await queued($, g, R2)
  expect(retroState(g).pendingDispatch).toEqual([R1, R2])
  await $.classic.Stop({ stop_hook_active: false, background_tasks: [{ id: 'b1', type: 'subagent', status: 'completed', description: 'x' }] } as never)
  expect(g.spawns).toHaveLength(1)
  expect(g.spawns[0]?.prompt).toContain(`--run ${R1}`)
  expect(g.spawns[0]?.prompt).toContain(`--run ${R2}`)
})

test('S15: without background_tasks the hold falls back to agent.list', async ($, on) => {
  const g = rig(on, { agents: [{ id: 'w1', status: 'running' }] })
  await queued($, g, R1)
  await $.classic.Stop({ stop_hook_active: false } as never)
  expect(g.spawns).toHaveLength(0)
  g.agents = [{ id: 'w1', status: 'completed' }]
  await $.classic.Stop({ stop_hook_active: false } as never)
  expect(g.spawns).toHaveLength(1)
})

test('S15: two racing Stops spawn once', async ($, on) => {
  const g = rig(on)
  await queued($, g, R1)
  await Promise.all([$.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never), $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)])
  expect(g.spawns).toHaveLength(1)
})

type SpawnFailure = { deny?: string; throws?: boolean }
async function failedSpawn($: Parameters<Parameters<typeof test>[1]>[0], on: On, mode: SpawnFailure): Promise<void> {
  const g = rig(on)
  g.spawnMode = mode
  await queued($, g, R1)
  const r = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(r.block).toContain('Retrospective dispatch')
  expect(r.block).toContain(`/session-retrospective --run ${R1}`)
  expect(retroState(g).pendingDispatch).toBeNull()
}

test('S15: a denied spawn falls back to a Stop block carrying the directive', async ($, on) => failedSpawn($, on, { deny: 'no agents' }))
test('S15: a throwing spawn falls back to a Stop block carrying the directive', async ($, on) => failedSpawn($, on, { throws: true }))

test('S15: stop_hook_active returns without spawning or nudging', async ($, on) => {
  const g = rig(on)
  await queued($, g, R1)
  const r = await $.classic.Stop({ stop_hook_active: true, background_tasks: [] } as never)
  expect(r.block).toBeUndefined()
  expect(g.spawns).toHaveLength(0)
  expect(retroState(g).pendingDispatch).toEqual([R1])
})

test('S16: at Stop with a project rootId, closed unmarked runs are evaluated: a won mark spawns once', async ($, on) => {
  const g = rig(on)
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 4 }
  const r = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(r.block).toBeUndefined()
  expect(g.spawns).toHaveLength(1)
  expect(g.spawns[0]?.prompt).toContain(`/session-retrospective --run ${R1}`)
  expect(markCalls(g).map(c => c.args)).toEqual([{ ops: [{ op: 'mark', runId: R1, key: 'retro' }] }])
  expect(mcpMarks(g)).toHaveLength(0)
  await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(g.spawns).toHaveLength(1)
})

test('S16: at Stop a won mark while a background task runs is queued, then spawned by a later clear Stop', async ($, on) => {
  const g = rig(on)
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 4 }
  await $.classic.Stop({ stop_hook_active: false, background_tasks: [{ id: 'b1', type: 'shell', status: 'running', description: 'x' }] } as never)
  expect(g.spawns).toHaveLength(0)
  expect(retroState(g).pendingDispatch).toEqual([R1])
  await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(g.spawns).toHaveLength(1)
})

test('S16: at Stop a run below the threshold is nudged with a block, once', async ($, on) => {
  const g = rig(on)
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 1 }
  g.stopReply = { block: 'other plugin' }
  const r = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(r.block).toContain('other plugin')
  expect(r.block).toContain(`/session-retrospective --run ${R1}`)
  expect(g.spawns).toHaveLength(0)
  const again = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(again.block).toBe('other plugin')
})

test('S16: with no project rootId, a recorded close makes the Stop backstop nudge once, never spawn, and keep an existing block', async ($, on) => {
  const g = rig(on, { config: retroYaml('dispatch') })
  g.reply = () => closeReply(R1)
  await $.tool.call(closeCall(R1))
  expect(retroState(g)).toMatchObject({ sawClose: true, closes: [R1] })
  g.stopReply = { block: 'other plugin' }
  const r = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(r.block).toContain('other plugin')
  expect(r.block).toContain(`/session-retrospective --run ${R1}`)
  expect(g.spawns).toHaveLength(0)
  expect(g.mcp.calls).toHaveLength(0)
  const again = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(again.block).toBe('other plugin')
})

test('S16: with no project rootId and nothing recorded, Stop is silent', async ($, on) => {
  const g = rig(on, { config: retroYaml('dispatch') })
  const r = await $.classic.Stop({ stop_hook_active: false, background_tasks: [] } as never)
  expect(r.block).toBeUndefined()
  expect(g.spawns).toHaveLength(0)
})

test('S15: calls from the retro agent and its descendants are ignored', async ($, on) => {
  const g = rig(on, { agents: [{ id: 'retro-1', status: 'running' }, { id: 'kid', status: 'running', parentId: 'retro-1' }] })
  g.state.set('task-orchestrator-mod/retro/', { value: { retroAgents: ['retro-1'] }, version: 1 })
  g.mcp.runs = [{ runId: R3 }]
  g.mcp.terminal = { [R3]: 5 }
  g.reply = () => closeReply(R3)
  const before = JSON.stringify(retroState(g))
  for (const agentId of ['retro-1', 'kid']) {
    const r = await $.tool.call({ ...(closeCall(R3) as object), agentId } as never)
    expect(r.context).toBeUndefined()
  }
  expect(JSON.stringify(retroState(g))).toBe(before)
  expect(g.mcp.calls).toHaveLength(0)
  const own = await $.tool.call({ ...(closeCall(R3) as object), agentId: 'someone-else' } as never)
  expect(own.context).toBeUndefined()
  expect(retroState(g)).toMatchObject({ sawClose: true, closes: [R3] })
  const main = await $.tool.call(closeCall(R3))
  expect(main.context).toEqual([QUEUED_NOTICE])
})

test('S15: AGENT_CONFIG_DIR config is read first (mode off there leaves the events to the command hooks)', async ($, on) => {
  const withMode = (mode: string) => `project:\n  rootId: ${ROOT}\n\n${retroYaml(mode)}`
  const g = rig(on, { env: { AGENT_CONFIG_DIR: '/cfg/' }, files: { '/cfg/.taskorchestrator/config.yaml': withMode('off'), '.taskorchestrator/config.yaml': withMode('dispatch') } })
  g.mcp.runs = [{ runId: R1 }]
  g.mcp.terminal = { [R1]: 5 }
  g.reply = () => closeReply(R1)
  const r = await $.tool.call(closeCall(R1))
  expect(r.context).toBeUndefined()
  expect(g.mcp.calls).toHaveLength(0)
  expect(retroState(g).sawClose).toBeUndefined()
})
