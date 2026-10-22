// Pure logic of the retrospective on the 4.0 run-mark model (item 5dbd8275, scenario S15; AC8 of the frozen task-scope).
//
// Authored blind to the implementation. Oracles: claude-plugins/task-orchestrator/hooks/retro-lib.mjs at the base
// commit (the run evaluation rules: list window, skip rules, the 5 run cap, mark outcomes, session state hygiene),
// current/docs/api-reference.md "runs_read" (list with `unmarked` and `endedSince`; `frontier` counts) and
// "runs_write" (`close`, `mark` answering `marked`; a batch with failures is `partial_failure` with the full
// `data.results`), task-scope AC8.
import { expect, test } from 'claude-code/testing'

import type { RetroState } from '../types'
import { MAX_RECORDED, evaluateRuns, markRequest, normalizeState, recordCloses, successfulCloses } from '../src/retro/logic.ts'
import type { RetroConfig } from '../src/retro/logic.ts'

const ROOT = 'ce064d2c-7c03-4eac-ae7d-89a14c2ba273'
const R1 = 'a1111111-0000-4000-8000-000000000001'
const R2 = 'b2222222-0000-4000-8000-000000000002'
const R3 = 'c3333333-0000-4000-8000-000000000003'
const HOUR = 3_600_000

const cfg = (over: Partial<RetroConfig> = {}): RetroConfig => ({ mode: 'dispatch', dispatchThreshold: 3, ...over })

// --- successful closes -------------------------------------------------------------------

const closeResult = (results: unknown[], ok = true) => ({
  structuredContent: ok
    ? { ok: true, data: { results, summary: { ok: results.length, failed: 0 } } }
    : { ok: false, error: { code: 'partial_failure', kind: 'permanent', message: 'some failed' }, data: { results, summary: { ok: 1, failed: results.length - 1 } } },
})
const closesOf = (v: string[] | null): string[] => v ?? []

test('S15 successfulCloses: only close ops whose own result is ok, even in a partially failed batch', () => {
  const input = { ops: [{ op: 'close', runId: R1, reason: 'completed' }, { op: 'close', runId: R2, reason: 'completed' }, { op: 'open', rootId: ROOT }, { op: 'close', runId: R3, reason: 'failed' }] }
  const result = closeResult([{ ok: true, runId: R1 }, { ok: false, error: { code: 'forbidden', kind: 'permanent', message: 'no' } }, { ok: true, runId: R3 }, { ok: true, runId: R3 }], false)
  // element 2 (open) is not a close even though ok; element 3 is a close with an ok result
  expect(closesOf(successfulCloses(input, result))).toEqual([R1, R3])
})

test('S15 successfulCloses: the run id comes from the op, else from the result row', () => {
  expect(closesOf(successfulCloses({ ops: [{ op: 'close', reason: 'completed' }] }, closeResult([{ ok: true, runId: R3 }])))).toEqual([R3])
  expect(closesOf(successfulCloses({ ops: [{ op: 'close', runId: R1.slice(0, 8) }] }, closeResult([{ ok: true, runId: R1 }])))).toEqual([R1.slice(0, 8)])
})

test('S15 successfulCloses: other ops, failed closes, a missing envelope and a legacy shape give no closes', () => {
  expect(closesOf(successfulCloses({ ops: [{ op: 'mark', runId: R1, key: 'retro' }] }, closeResult([{ ok: true, runId: R1, marked: true }])))).toEqual([])
  expect(closesOf(successfulCloses({ ops: [{ op: 'close', runId: R1 }] }, closeResult([{ ok: false, error: { code: 'run_closed', kind: 'permanent', message: 'closed' } }], false)))).toEqual([])
  expect(closesOf(successfulCloses({ ops: [{ op: 'close', runId: R1 }] }, { content: [{ type: 'text', text: 'ok' }] }))).toEqual([])
  expect(closesOf(successfulCloses({ ops: [{ op: 'close', runId: R1 }] }, undefined))).toEqual([])
  expect(closesOf(successfulCloses({ ops: [{ op: 'close', runId: R1 }] }, { structuredContent: { success: true, results: [{ ok: true }] } }))).toEqual([])
  expect(closesOf(successfulCloses({}, closeResult([{ ok: true, runId: R1 }])))).toEqual([])
})

// --- the REST mark request ---------------------------------------------------------------------

// Oracle: task-scope Amendment A1.4 (argv and stdin shape of markRequest; the token never enters argv).
test('D7 markRequest: argv shape and URL last; the token only via -K - and stdin; no token gives no -K and no input; a CR/LF token is dropped', () => {
  const base = 'http://127.0.0.1:8080'
  const url = `${base}/api/v1/ops/runs_write`
  const body = JSON.stringify({ ops: [{ op: 'mark', runId: R1, key: 'retro' }] })
  const token = 'test-token-abc'
  const withToken = markRequest(base, R1, token)
  expect(withToken.argv).toEqual(['curl', '-s', '-X', 'POST', '-H', 'Content-Type: application/json', '--data-raw', body, '-K', '-', url])
  expect(withToken.input).toBe(`header = "Authorization: Bearer ${token}"
`)
  expect(withToken.argv.some(a => a.includes(token))).toBe(false)
  expect(withToken.argv).not.toContain('-f')
  for (const none of [undefined, '']) {
    const r = markRequest(base, R1, none)
    expect(r.argv).toEqual(['curl', '-s', '-X', 'POST', '-H', 'Content-Type: application/json', '--data-raw', body, url])
    expect(r.argv).not.toContain('-K')
    expect(r.input).toBeUndefined()
  }
  const NL = String.fromCharCode(10)
  const CR = String.fromCharCode(13)
  for (const bad of [`tok${NL}X-Evil: 1`, `tok${CR}${NL}X-Evil: 1`, `tok${CR}`]) {
    const r = markRequest(base, R1, bad)
    expect(r.argv).not.toContain('-K')
    expect(r.input).toBeUndefined()
    expect(r.argv.at(-1)).toBe(url)
    expect(JSON.stringify(r)).not.toContain('X-Evil')
  }
})

// --- state helpers -----------------------------------------------------------------------

test('state: normalizeState keeps the typed members and drops the rest; junk gives an empty state', () => {
  const full: RetroState = { closes: [R1], sawClose: true, nudged: [R2], backstopNudged: true, pendingDispatch: [R3], dispatched: { agentId: 'retro-1', runs: [R1], at: 5 }, retroAgents: ['retro-1'] }
  expect(normalizeState(full)).toEqual(full)
  for (const junk of [null, undefined, 'x', 5, [], true]) expect(normalizeState(junk)).toEqual({})
})

// Oracle for the next test: retro-lib.mjs readSessionState keeps only string run ids in closes and nudged.
test('state: normalizeState keeps only string run ids in closes and nudged', () => {
  const dirty = normalizeState({ closes: [R1, 7, null], nudged: ['x', {}] })
  expect(dirty.closes).toEqual([R1])
  expect(dirty.nudged).toEqual(['x'])
})


test('state: recordCloses sets sawClose and unions the run ids; the newest 50 are kept', () => {
  const one = recordCloses({}, [R1, R2])
  expect(one.sawClose).toBe(true)
  expect(one.closes).toEqual([R1, R2])
  const again = recordCloses(one, [R2, R3])
  expect(again.closes).toEqual([R1, R2, R3])
  expect(MAX_RECORDED).toBe(50)
  const many = Array.from({ length: 60 }, (_, i) => `run-${i}`)
  const capped = recordCloses({}, many)
  expect(capped.closes).toHaveLength(50)
  expect(capped.closes?.[49]).toBe('run-59')
  expect(capped.closes).not.toContain('run-0')
})

// --- evaluateRuns (unit, over a scripted call) -------------------------------------------

type View = { runId: string; marks?: Record<string, string> }
type Script = { runs: View[]; terminal: Record<string, number | 'fail'>; mark: Record<string, 'won' | 'lost' | 'fail' | 'throw'> }
function scripted(s: Script) {
  const log: { tool: string; args: Record<string, unknown> }[] = []
  const call = async (tool: string, args: Record<string, unknown>): Promise<unknown> => {
    log.push({ tool, args })
    if (tool === 'runs_read' && args.select === 'list') return { items: s.runs, page: { total: s.runs.length, truncated: false } }
    if (tool === 'runs_read' && args.select === 'frontier') {
      const t = s.terminal[String(args.runId)]
      if (t === undefined || t === 'fail') throw new Error('runs_read: not_found: no such run')

      return { runId: args.runId, status: 'closed', counts: { queue: 0, work: 0, review: 0, blocked: 0, terminal: t }, ready: [], inFlight: [], waiting: [] }
    }
    if (tool === 'runs_write') {
      const op = (args.ops as { runId: string }[])[0] as { runId: string }
      const m = s.mark[op.runId]
      if (m === 'throw') throw new Error('runs_write: unavailable: busy')
      if (m === 'fail') return { results: [{ ok: false, error: { code: 'forbidden', kind: 'permanent', message: 'no' } }], summary: { ok: 0, failed: 1 } }

      return { results: [{ ok: true, runId: op.runId, marked: m === 'won' }], summary: { ok: 1, failed: 0 } }
    }
    throw new Error(`unexpected ${tool}`)
  }

  return { call, log }
}
const NOW = 5_000_000

test('S15 evaluateRuns: a run at the threshold in dispatch mode is marked and won; the list read carries the window', async () => {
  const s = scripted({ runs: [{ runId: R1 }], terminal: { [R1]: 3 }, mark: { [R1]: 'won' } })
  const r = await evaluateRuns(s.call, ROOT, cfg(), {}, NOW)
  expect(r.won).toEqual([R1])
  expect(r.nudge).toEqual([])
  expect(s.log[0]).toEqual({ tool: 'runs_read', args: { select: 'list', rootId: ROOT, unmarked: 'retro', endedSince: new Date(NOW - 24 * HOUR).toISOString(), limit: 20 } })
  expect(s.log.find(c => c.tool === 'runs_read' && c.args.select === 'frontier')?.args).toEqual({ select: 'frontier', runId: R1 })
  expect(s.log.find(c => c.tool === 'runs_write')?.args).toEqual({ ops: [{ op: 'mark', runId: R1, key: 'retro' }] })
})

test('S15 evaluateRuns: a lost mark race is silent; a failed or thrown mark is a nudge, never a dispatch', async () => {
  const lost = await evaluateRuns(scripted({ runs: [{ runId: R1 }], terminal: { [R1]: 5 }, mark: { [R1]: 'lost' } }).call, ROOT, cfg(), {}, NOW)
  expect(lost.won).toEqual([])
  expect(lost.nudge).toEqual([])
  expect(lost.state.nudged ?? []).toEqual([])
  for (const mark of ['fail', 'throw'] as const) {
    const r = await evaluateRuns(scripted({ runs: [{ runId: R1 }], terminal: { [R1]: 5 }, mark: { [R1]: mark } }).call, ROOT, cfg(), {}, NOW)
    expect([mark, r.won, r.nudge]).toEqual([mark, [], [R1]])
    expect(r.state.nudged).toEqual([R1])
  }
})

test('S15 evaluateRuns: below the threshold, or in nudge mode, a run is nudged and no mark is written', async () => {
  const below = scripted({ runs: [{ runId: R1 }], terminal: { [R1]: 2 }, mark: {} })
  const a = await evaluateRuns(below.call, ROOT, cfg(), {}, NOW)
  expect(a.nudge).toEqual([R1])
  expect(a.won).toEqual([])
  expect(below.log.some(c => c.tool === 'runs_write')).toBe(false)
  const nudgeMode = scripted({ runs: [{ runId: R1 }], terminal: { [R1]: 9 }, mark: {} })
  const b = await evaluateRuns(nudgeMode.call, ROOT, cfg({ mode: 'nudge' }), {}, NOW)
  expect(b.nudge).toEqual([R1])
  expect(nudgeMode.log.some(c => c.tool === 'runs_write')).toBe(false)
  // the threshold itself counts
  const exact = await evaluateRuns(scripted({ runs: [{ runId: R1 }], terminal: { [R1]: 3 }, mark: { [R1]: 'won' } }).call, ROOT, cfg({ dispatchThreshold: 3 }), {}, NOW)
  expect(exact.won).toEqual([R1])
  const zero = await evaluateRuns(scripted({ runs: [{ runId: R1 }], terminal: { [R1]: 0 }, mark: { [R1]: 'won' } }).call, ROOT, cfg({ dispatchThreshold: 0 }), {}, NOW)
  expect(zero.won).toEqual([R1])
})

test('S15 evaluateRuns: a run carrying the retro mark, a run already nudged, and a duplicate row are skipped; a failed frontier read skips the run', async () => {
  const s = scripted({
    runs: [{ runId: R1, marks: { retro: '2026-10-01T00:00:00Z' } }, { runId: R2 }, { runId: R3 }, { runId: R3 }],
    terminal: { [R2]: 4, [R3]: 'fail' },
    mark: { [R2]: 'won' },
  })
  const r = await evaluateRuns(s.call, ROOT, cfg(), { nudged: [R2] }, NOW)
  expect(r.won).toEqual([])
  expect(r.nudge).toEqual([])
  expect(s.log.filter(c => c.args.select === 'frontier').map(c => c.args.runId)).toEqual([R3])
  expect(r.state.nudged).toEqual([R2])
})

test('S15 evaluateRuns: at most 5 runs are examined; the nudged ones are appended to the state, which is not mutated', async () => {
  const runs = Array.from({ length: 8 }, (_, i) => ({ runId: `00000000-0000-4000-8000-00000000000${i}` }))
  const terminal = Object.fromEntries(runs.map(r => [r.runId, 1]))
  const s = scripted({ runs, terminal, mark: {} })
  const start: RetroState = { nudged: ['earlier'] }
  const r = await evaluateRuns(s.call, ROOT, cfg(), start, NOW)
  expect(r.nudge).toHaveLength(5)
  expect(s.log.filter(c => c.args.select === 'frontier')).toHaveLength(5)
  expect(r.state.nudged).toEqual(['earlier', ...r.nudge])
  expect(start).toEqual({ nudged: ['earlier'] })
})

test('S15 evaluateRuns: an unreadable list gives nothing, without throwing', async () => {
  const r = await evaluateRuns(async () => {
    throw new Error('runs_read: unavailable: busy')
  }, ROOT, cfg(), {}, NOW)
  expect(r.won).toEqual([])
  expect(r.nudge).toEqual([])
})

