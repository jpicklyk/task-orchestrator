import { expect, test } from 'claude-code/testing'

import type { Board, BoardRun } from '../types'
import {
  backdropSvg,
  boardProgress,
  decisionCounts,
  enterToast,
  envelopeData,
  footerLead,
  formatTokens,
  isDirectorSkillCall,
  listTotal,
  markerPath,
  modeDetail,
  pausedCell,
  phaseCell,
  progressBar,
  restData,
  restToolUrl,
  runFromList,
  runName,
  sanitizeKey,
  seatsCell,
  tmpDir,
  withFrontier,
} from './model.ts'

const FOOTER = {
  plugin: 'orchestration-mode',
  component: 'SessionMode',
  requestId: 'session-mode',
  props: { modes: ['focus'] },
} as const

test('the footer slot draws `Orchestration Mode: director` after the native labels while on', async ($, on) => {
  on('state.get', () => ({ value: { value: { since: '2026-11-07T14:02:00.000Z' }, version: 1 } }))

  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ ...FOOTER, surface })
    expect((await ui.find({ type: 'Text', text: /Orchestration Mode/ }))?.text).toBe('focus & Orchestration Mode: director')
    await ui.unmount()
  }
})

test('the footer is left alone while off', async ($, on) => {
  on('state.get', () => ({ value: { value: null, version: 1 } }))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, (e.props as { modes: readonly string[] }).modes.join(' & '))
  })

  const ui = await $.ui.mount({ ...FOOTER, surface: 'desktop' })
  expect((await ui.find({ type: 'Text' }))?.text).toBe('focus')
  await ui.unmount()
})

test('marker path matches the task-orchestrator hooks on both platforms', async () => {
  expect(markerPath('C:\\Users\\me\\AppData\\Local\\Temp', 'abc-123')).toBe(
    'C:\\Users\\me\\AppData\\Local\\Temp\\task-orchestrator\\abc-123\\director.json',
  )
  expect(markerPath('/tmp', 'abc.123/x')).toBe('/tmp/task-orchestrator/abc_123_x/director.json')
  expect(markerPath('/tmp', '')).toBe('/tmp/task-orchestrator/unknown/director.json')
  expect(sanitizeKey('a b:c')).toBe('a_b_c')
})

test('temp dir follows os.tmpdir() precedence and drops a trailing separator', async () => {
  expect(tmpDir({ TMPDIR: '/var/folders/x/' })).toBe('/var/folders/x')
  expect(tmpDir({ TEMP: 'C:\\Temp\\', TMP: 'C:\\Other' })).toBe('C:\\Temp')
  expect(tmpDir({ TMP: 'C:\\Other' })).toBe('C:\\Other')
  expect(tmpDir({})).toBe('/tmp')
})

test('pure helpers', async () => {
  expect(footerLead(['focus'])).toBe('focus & ')
  expect(footerLead(['focus', 'director'])).toBe('focus & ')
  expect(footerLead([])).toBe('')
  expect(isDirectorSkillCall(' task-orchestrator:director ')).toBe(true)
  expect(isDirectorSkillCall('task-orchestrator:work-summary')).toBe(false)
  expect(isDirectorSkillCall(undefined)).toBe(false)
  expect(enterToast('not a date')).toBe('Director mode on')
  expect(enterToast('2026-11-07T14:02:00.000Z').startsWith('Director mode on since ')).toBe(true)
  const svg = backdropSvg(800, 400)
  expect(svg.startsWith('<svg ')).toBe(true)
  expect(svg.includes('DIRECTOR')).toBe(true)
  expect(svg.length < 131072).toBe(true)
})

test('result envelopes: structuredContent first, then the first text block, errors thrown as code: message', async () => {
  expect(envelopeData({ structuredContent: { ok: true, data: { items: [1] } } })).toEqual({ items: [1] })
  expect(envelopeData({ content: [{ type: 'text', text: '{"ok":true,"data":{"page":{"total":2}}}' }] })).toEqual({
    page: { total: 2 },
  })
  expect(() => envelopeData({ structuredContent: { ok: false, error: { code: 'forbidden', message: 'no' } } })).toThrow(
    'forbidden: no',
  )
  expect(() => envelopeData({ content: [{ type: 'text', text: 'not json' }] })).toThrow('no result envelope')
  expect(restData(200, '{"ok":true,"data":{"items":[]}}')).toEqual({ items: [] })
  expect(() => restData(502, '<html>')).toThrow('http 502: no JSON body')
  expect(restToolUrl('http://localhost:3003', 'runs_read')).toBe('http://localhost:3003/api/v1/ops/director/runs_read')
  expect(restToolUrl('http://host/', 'items_read')).toBe('http://host/api/v1/ops/director/items_read')
})

const LISTED = {
  runId: 'a51588a2-ee3a-448a-961d-846b137dd5fe',
  label: 'wave-8',
  title: 'Band demo wave',
  status: 'open',
  coordinatorStalled: true,
  stalledSeats: 1,
  usage: { reportedTokens: 0, observedTokens: 1_250_000 },
}

const FRONTIER = {
  counts: { queue: 2, work: 1, review: 0, blocked: 1, terminal: 1 },
  ready: [{ itemId: 'a' }],
  inFlight: [{ itemId: 'b', stalled: true }, { itemId: 'c', stalled: false }],
  waiting: [{ itemId: 'd' }],
}

test('a run row is built from the list item and its frontier', async () => {
  expect(runFromList({ ...LISTED, status: 'closed' })).toBe(null)
  const listed = runFromList(LISTED)
  expect(listed?.status).toBe('open')
  expect(listed?.tokens).toBe(1_250_000)
  expect(listed?.stalled).toBe(1)
  const run = withFrontier(listed as BoardRun, FRONTIER)
  expect(run.counts).toEqual({ queue: 2, work: 1, review: 0, blocked: 1, terminal: 1 })
  expect(run.ready).toBe(1)
  expect(run.inFlight).toBe(2)
  expect(run.waiting).toBe(1)
  expect(run.stalled).toBe(1)
  expect(runName(run)).toBe('Band demo wave')
  expect(runName({ ...run, title: 'A title that is far too long for one cell' })).toBe('A title that is far too…')
  expect(runName({ ...run, title: '', label: '' })).toBe('a51588a2')
  expect(phaseCell(run.counts)).toBe('q2 → w1 → r0 → ✓1 · blocked 1')
  expect(phaseCell({ ...run.counts, blocked: 0 })).toBe('q2 → w1 → r0 → ✓1')
  expect(seatsCell(run)).toBe('in flight 2 (1 stalled) · ready 1 · waiting 1')
  expect(seatsCell({ ...run, stalled: 0 })).toBe('in flight 2 · ready 1 · waiting 1')
  expect(pausedCell({ ...run, status: 'paused', pauseReason: 'run-budget' })).toBe('PAUSED run-budget')
  expect(pausedCell({ ...run, status: 'paused' })).toBe('PAUSED')
  expect(formatTokens(run.tokens)).toBe('1.3M')
  expect(formatTokens(26_400)).toBe('26k')
  expect(formatTokens(12)).toBe('12')
})

test('the mode row: bar, totals, and the loading and fault states', async () => {
  const run = withFrontier(runFromList(LISTED) as BoardRun, FRONTIER)
  const paused: BoardRun = { ...run, id: 'p', status: 'paused', pauseReason: 'attempt-cap' }
  const board: Board = { at: '2026-11-07T14:02:00.000Z', runs: [run, paused], decisions: { open: 2, overdue: 1 }, blocked: 3 }
  expect(boardProgress(board)).toEqual({ done: 2, total: 10 })
  expect(boardProgress(null)).toEqual({ done: 0, total: 0 })
  expect(progressBar(2, 10)).toBe('▰▰▱▱▱▱▱▱')
  expect(progressBar(0, 0)).toBe('▱▱▱▱▱▱▱▱')
  expect(progressBar(5, 4, 4)).toBe('▰▰▰▰')
  expect(modeDetail(board)).toBe('runs 1 open, 1 paused · decisions 2 (1 overdue) · blocked 3')
  expect(modeDetail({ ...board, runs: [run], decisions: { open: 0, overdue: 0 }, blocked: null })).toBe('runs 1 open · decisions 0')
  expect(modeDetail(null)).toBe('board loading')
  expect(modeDetail({ ...board, error: 'http 502: no JSON body' })).toBe('board unavailable: http 502: no JSON body')
  expect(decisionCounts({ items: [{ status: 'open', overdue: true }, { status: 'acknowledged' }, { status: 'answered' }] })).toEqual({
    open: 2,
    overdue: 1,
  })
  expect(listTotal({ items: [{}, {}], page: { total: 7 } })).toBe(7)
  expect(listTotal({ items: [{}, {}] })).toBe(2)
})
