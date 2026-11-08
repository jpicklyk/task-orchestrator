import { expect, test } from 'claude-code/testing'

import type { Board, BoardRun, FocusBoard } from '../types'
import {
  backdropSvg,
  boardProgress,
  closedRunIds,
  coordinatorFromMarker,
  coordinatorFromOpen,
  decisionCounts,
  enterToast,
  envelopeData,
  focusBlocked,
  focusDetail,
  focusFromGet,
  focusParent,
  focusProgress,
  focusRowCell,
  footerLead,
  footerModes,
  formatTokens,
  graphTitle,
  isDirectorSkillCall,
  listTotal,
  markerPath,
  modeDetail,
  pausedCell,
  phaseCell,
  progressBar,
  resolveFocus,
  restData,
  restToolUrl,
  runFromList,
  runName,
  sameId,
  sanitizeKey,
  seatsCell,
  tmpDir,
  toolEnvelope,
  touchedItemId,
  withFrontier,
} from './model.ts'

const PLUGIN = 'orchestration-mode'

const FOOTER = {
  plugin: PLUGIN,
  component: 'SessionMode',
  requestId: 'session-mode',
  props: { modes: ['focus'] },
} as const

const BAND = {
  plugin: PLUGIN,
  component: 'AbovePrompt',
  requestId: 'above-prompt',
  props: { hasSurvey: false, isWorking: false, maxRows: 6, bodyColumns: 100, scroll: { offset: 0, bodyRows: 6 }, view: {} },
} as const

const RUN_TOOL = 'mcp__mcp-task-orchestrator__runs_write'
const NOTES_TOOL = 'mcp__mcp-task-orchestrator__notes_write'
const RUN_ID = 'a51588a2-ee3a-448a-961d-846b137dd5fe'

/**
 * Answers `state.get` per key from what `values()` holds at the time (absent = never written). The
 * hooks beneath the plugin are registered before the test's first call on `$`, so one hook reads a
 * value the test changes between mounts.
 */
function seedState(values: () => Record<string, unknown>) {
  return (_: unknown, e: { plugin: string; key: string }) => {
    const held = values()

    return e.plugin === PLUGIN && e.key in held ? { value: { value: held[e.key], version: 1 } } : { value: { value: undefined, version: 0 } }
  }
}

/** The text of the first Text a mount of `site` on the desktop shows that matches `pattern`. */
async function shown($: any, site: object, pattern: RegExp): Promise<string | undefined> {
  const ui = await $.ui.mount({ ...site, surface: 'desktop' })
  const found = await ui.find({ type: 'Text', text: pattern })
  await ui.unmount()

  return found?.text
}

/** A REST envelope body. */
const ok = (data: unknown) => ({ value: { status: 200, ok: true, headers: {}, text: JSON.stringify({ ok: true, data }) } })

/** The server the board reads stand for: every list empty, items by id from `items`, runs from `runs`. */
function server(items: Record<string, unknown>, runs: Record<string, unknown> = {}) {
  return (_: unknown, e: { url: string; init?: { body?: unknown } }) => {
    const body = JSON.parse(String(e.init?.body ?? '{}')) as Record<string, string>
    if (e.url.endsWith('/runs_read') && body.select === 'get') return ok(runs[body.runId ?? ''] ?? { status: 'open' })
    if (e.url.endsWith('/items_read') && body.select === 'get') {
      const item = items[body.itemId ?? '']
      return item === undefined
        ? { value: { status: 404, ok: false, headers: {}, text: '{"ok":false,"error":{"code":"not_found","message":"no"}}' } }
        : ok(item)
    }

    return ok({ items: [], page: { total: 0 } })
  }
}

/** The engine's answer to a TO write: one ok element per op, `element(op)` each. */
function answerWrites(element: (op: string) => Record<string, unknown>) {
  return (_: unknown, e: unknown) => {
    const ops = ((e as { ops?: { op: string }[] }).ops ?? []).map(op => ({ ok: true, ...element(op.op) }))
    const envelope = { ok: true, data: { results: ops, summary: { ok: ops.length, failed: 0 } } }

    return { result: envelope, text: JSON.stringify(envelope) }
  }
}

const LISTED = {
  runId: RUN_ID,
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

const RUN = withFrontier(runFromList(LISTED) as BoardRun, FRONTIER)
const RUN_BOARD: Board = { at: '2026-11-07T14:02:00.000Z', runs: [RUN], decisions: { open: 2, overdue: 1 }, blocked: 3 }
const COORD = { runId: RUN_ID, label: 'wave-8', since: 'x', source: 'tool' }

test('the footer names the roster modes that are on: none, one, or both joined with &', async ($, on) => {
  const cases = [
    { values: { director: null, coordinator: null }, modes: undefined },
    { values: { director: { since: '2026-11-07T14:02:00.000Z' }, coordinator: null }, modes: 'director' },
    { values: { director: null, coordinator: COORD }, modes: 'coordinator' },
    { values: { director: { since: 'x' }, coordinator: COORD }, modes: 'director & coordinator' },
  ]
  let values: Record<string, unknown> = {}
  on('state.get', seedState(() => values))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })
  for (const c of cases) {
    values = c.values
    for (const surface of ['terminal', 'desktop'] as const) {
      const ui = await $.ui.mount({ ...FOOTER, surface })
      if (c.modes === undefined) {
        expect((await ui.find({ type: 'Text' }))?.text).toBe('engine')
        expect(await ui.find({ type: 'Text', text: /Orchestration Mode/ })).toBe(undefined)
      } else {
        expect((await ui.find({ type: 'Text', text: /Orchestration Mode/ }))?.text).toBe('Orchestration Mode')
        expect((await ui.find({ type: 'Text', text: /director|coordinator/ }))?.text).toBe(c.modes)
        expect((await ui.find({ type: 'Text', text: /focus/ }))?.text).toBe('focus &')
      }
      await ui.unmount()
    }
  }
})

test('the footer label `Orchestration Mode director` follows the native labels while director mode is on', async ($, on) => {
  on('state.get', seedState(() => ({ director: { since: '2026-11-07T14:02:00.000Z' } })))

  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ ...FOOTER, surface })
    expect((await ui.find({ type: 'Text', text: /Orchestration Mode/ }))?.text).toBe('Orchestration Mode')
    expect((await ui.find({ type: 'Text', text: /^director$/ }))?.text).toBe('director')
    expect((await ui.find({ type: 'Text', text: /focus/ }))?.text).toBe('focus &')
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

// The tests below leave $.state to the kit's own bottom: the plugin's writes are read back by the
// drawings that read them (the footer for the modes, the band for the focus and the toggle).

test("a runs_write open makes the session the run's coordinator; closing that run clears it", async ($, on) => {
  const toasts: string[] = []
  on('ui.toast', (_, e) => {
    toasts.push(e.text)
    return { value: undefined }
  })
  on('ui.backdrop', () => ({ value: { isDrawn: true, requestId: 'backdrop' } }))
  on('http.fetch', server({}))
  on(
    'tool.call',
    answerWrites(op =>
      op === 'open' ? { runId: RUN_ID, label: 'wave-8', status: 'open', coordinatorToken: 'coord-secret' } : { runId: RUN_ID, status: 'closed' },
    ),
  )
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })

  await $.tool.call({ tool: RUN_TOOL, ops: [{ op: 'open', rootId: 'ce064d2c', label: 'wave-8', items: [] }] })
  expect(await shown($, FOOTER, /coordinator|engine/)).toBe('coordinator')
  expect(toasts.includes('Coordinator of run wave-8')).toBe(true)

  await $.tool.call({ tool: 'mcp__mcp-task-orchestrator-director__runs_write', ops: [{ op: 'close', runId: 'a51588a2', reason: 'completed' }] })
  expect(await shown($, FOOTER, /coordinator|engine/)).toBe('engine')
  expect(toasts.includes('Run wave-8 closed')).toBe(true)
})

test("a subagent's TO calls move neither the mode nor the focus", async ($, on) => {
  on('ui.toast', () => ({ value: undefined }))
  on('ui.backdrop', () => ({ value: { isDrawn: true, requestId: 'backdrop' } }))
  on('http.fetch', server({ d8999287: { id: 'd8999287-full', depth: 1, title: 'Band item', role: 'work' } }))
  on('tool.call', answerWrites(() => ({ runId: RUN_ID, coordinatorToken: 't' })))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })

  await $.tool.call({ tool: RUN_TOOL, ops: [{ op: 'open', rootId: 'ce064d2c' }], agentId: 'seat-1' })
  await $.tool.call({ tool: NOTES_TOOL, ops: [{ op: 'upsert', itemId: 'd8999287', key: 'k', role: 'work', body: '' }], agentId: 'seat-1' })
  expect(await shown($, FOOTER, /coordinator|engine/)).toBe('engine')
  expect(await shown($, BAND, /Band item|engine/)).toBe('engine')
})

test('a touched item moves the focus to its top-most ancestor below the project root; /to-band hides and shows it', async ($, on) => {
  const stored: Record<string, unknown> = {}
  const toasts: string[] = []
  on('store.set', (_, e) => {
    stored[e.key] = e.value
    return { value: undefined }
  })
  on('ui.toast', (_, e) => {
    toasts.push(e.text)
    return { value: undefined }
  })
  on(
    'http.fetch',
    server({
      '8da6e710': { id: '8da6e710-0000', parentId: 'd99e4f7a-0000', depth: 2, title: 'A task', role: 'terminal' },
      'd99e4f7a-0000': { id: 'd99e4f7a-0000', parentId: 'ce064d2c-0000', depth: 1, title: 'Model and effort policy', role: 'work' },
    }),
  )
  on('tool.call', answerWrites(() => ({})))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })

  expect(await shown($, BAND, /Model and|engine/)).toBe('engine')
  await $.tool.call({ tool: NOTES_TOOL, ops: [{ op: 'upsert', itemId: '8da6e710', key: 'k', role: 'work', body: '' }] })
  expect(await shown($, BAND, /Model and|engine/)).toBe('Model and effort policy')
  expect(await shown($, BAND, /^0\/1$/)).toBe('0/1')
  expect(await shown($, BAND, /^in progress|^work/)).toBe('work · blocked 0')

  const hidden = await $.command.run({ command: 'to-band' })
  expect(hidden.text).toBe('Band hidden')
  expect(await shown($, BAND, /Model and|engine/)).toBe('engine')
  expect(await shown($, FOOTER, /Orchestration Mode|engine/)).toBe('engine')

  const back = await $.command.run({ command: 'to-band' })
  expect(back.text).toBe('Band shown')
  expect(await shown($, BAND, /Model and|engine/)).toBe('Model and effort policy')
  expect(toasts.filter(t => t.startsWith('Band'))).toEqual(['Band hidden', 'Band shown'])
  expect(stored).toEqual({})
})

test('the band toggle is per session: a fresh session starts from userConfig band, never from the store', async ($, on) => {
  on('store.get', () => ({ value: true }))
  on('command.register', () => ({ value: { command: 'to-band' } }))
  on('session.start', (_, e) => ({ cwd: e.cwd }))
  on('ui.toast', () => ({ value: undefined }))
  on('http.fetch', server({ 'd99e4f7a-0000': { id: 'd99e4f7a-0000', depth: 1, title: 'Model and effort policy', role: 'work' } }))
  on('tool.call', answerWrites(() => ({})))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })

  await $.session.start({ cwd: '/', surface: 'desktop', isInteractive: true })
  await $.tool.call({ tool: NOTES_TOOL, ops: [{ op: 'upsert', itemId: 'd99e4f7a-0000', key: 'k', role: 'work', body: '' }] })
  expect(await shown($, BAND, /Model and|engine/)).toBe('Model and effort policy')
})

test('the TO hook hands back the result as answered, and ignores an errored or denied call', async ($, on) => {
  const opened = { ok: true, data: { results: [{ ok: true, runId: RUN_ID, label: 'wave-8', coordinatorToken: 't' }], summary: { ok: 1, failed: 0 } } }
  const toasts: string[] = []
  let answer: Record<string, unknown> = {}
  on('ui.toast', (_, e) => {
    toasts.push(e.text)
    return { value: undefined }
  })
  on('ui.backdrop', () => ({ value: { isDrawn: true, requestId: 'backdrop' } }))
  on('http.fetch', server({}))
  on('tool.call', () => answer as never)
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })
  const open = { tool: RUN_TOOL, ops: [{ op: 'open', rootId: 'ce064d2c', label: 'wave-8' }] }

  answer = { isError: true, result: opened, text: JSON.stringify(opened) }
  const errored = await $.tool.call(open)
  expect(errored.isError).toBe(true)
  expect(await shown($, FOOTER, /coordinator|engine/)).toBe('engine')

  answer = { deny: 'no runs here' }
  await $.tool.call(open)
  expect(await shown($, FOOTER, /coordinator|engine/)).toBe('engine')
  expect(toasts).toEqual([])

  answer = { result: opened, text: JSON.stringify(opened) }
  const ran = await $.tool.call(open)
  expect(ran.result).toEqual(opened)
  expect(ran.text).toBe(JSON.stringify(opened))
  expect(await shown($, FOOTER, /coordinator|engine/)).toBe('coordinator')
})

test('closing the run does not clear a coordinator that came from the marker', async ($, on) => {
  const toasts: string[] = []
  on('env.get', (_, e) => ({ value: e.name === 'TMPDIR' ? '/tmp' : undefined }))
  on('session.id', () => ({ value: 's1' }))
  on('ui.status', () => ({ value: undefined }))
  on('clock.every', () => ({ value: undefined }))
  // The engine resolves the path before the hook sees it (an absolute path on Windows), so match its tail.
  on('fs.exists', (_, e) => ({ value: /task-orchestrator[\\/]s1[\\/]coordinator\.json$/.test(e.path) }))
  on('fs.read', () => ({ value: JSON.stringify({ runId: RUN_ID, label: 'wave-8' }) }))
  on('command.register', () => ({ value: { command: 'to-band' } }))
  on('session.start', (_, e) => ({ cwd: e.cwd }))
  on('ui.toast', (_, e) => {
    toasts.push(e.text)
    return { value: undefined }
  })
  on('ui.backdrop', () => ({ value: { isDrawn: true, requestId: 'backdrop' } }))
  on('http.fetch', server({}))
  on('tool.call', answerWrites(() => ({ runId: RUN_ID, status: 'closed' })))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })

  await $.session.start({ cwd: '/', surface: 'desktop', isInteractive: true })
  expect(await shown($, FOOTER, /coordinator|engine/)).toBe('coordinator')
  expect(toasts.includes('Coordinator of run wave-8')).toBe(true)

  await $.tool.call({ tool: RUN_TOOL, ops: [{ op: 'close', runId: RUN_ID, reason: 'completed' }] })
  expect(await shown($, FOOTER, /coordinator|engine/)).toBe('coordinator')
  expect(toasts.includes('Run wave-8 closed')).toBe(false)
})

test('the band draws the run graph under its name, and nothing while hidden or with no graph', async ($, on) => {
  let values: Record<string, unknown> = { board: RUN_BOARD, bandHidden: false, focus: null }
  on('state.get', seedState(() => values))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })

  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ ...BAND, surface })
    expect((await ui.find({ type: 'Text', text: /^run / }))?.text).toBe('run wave-8')
    expect(await ui.find({ type: 'Text', text: /DIRECTOR MODE/ })).toBe(undefined)
    expect((await ui.find({ type: 'Text', text: /^runs 1 open/ }))?.text).toBe('runs 1 open · decisions 2 (1 overdue) · blocked 3')
    await ui.unmount()
  }

  values = { board: RUN_BOARD, bandHidden: true }
  expect(await shown($, BAND, /run wave-8|engine/)).toBe('engine')

  values = { board: { ...RUN_BOARD, runs: [] }, bandHidden: false, focus: null }
  expect(await shown($, BAND, /run wave-8|engine/)).toBe('engine')
})

test('the band draws the focus graph with no run open', async ($, on) => {
  const graph: FocusBoard = {
    itemId: 'd99e4f7a-0000',
    title: 'Model and effort policy',
    label: 'in-progress',
    counts: { queue: 1, work: 1, review: 0, blocked: 0, terminal: 2 },
    blocked: 1,
    firstBlocked: { id: '6e91c19b', blocker: 'd8999287' },
  }
  on(
    'state.get',
    seedState(() => ({
      board: { ...RUN_BOARD, runs: [], focus: graph },
      bandHidden: false,
      focus: { itemId: 'd99e4f7a', title: 'Model and effort policy', since: 'x' },
    })),
  )

  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ ...BAND, surface })
    expect((await ui.find({ type: 'Text', text: /^Model and/ }))?.text).toBe('Model and effort policy')
    expect((await ui.find({ type: 'Text', text: /^2\/4$/ }))?.text).toBe('2/4')
    expect((await ui.find({ type: 'Text', text: /^q1/ }))?.text).toBe('q1 → w1 → r0 → ✓2 · blocked 1 · decisions 2 (1 overdue)')
    expect((await ui.find({ type: 'Text', text: /^in-progress/ }))?.text).toBe('in-progress · blocked 1: 6e91c19b waits on d8999287')
    await ui.unmount()
  }
})

test('the band stays off with userConfig band false', { options: { band: false } }, async ($, on) => {
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })
  on('state.get', seedState(() => ({ board: RUN_BOARD, bandHidden: false })))

  expect(await shown($, BAND, /run wave-8|engine/)).toBe('engine')
})

test('marker path matches the task-orchestrator hooks on both platforms', async () => {
  expect(markerPath('C:\\Users\\me\\AppData\\Local\\Temp', 'abc-123')).toBe(
    'C:\\Users\\me\\AppData\\Local\\Temp\\task-orchestrator\\abc-123\\director.json',
  )
  expect(markerPath('/tmp', 'abc.123/x')).toBe('/tmp/task-orchestrator/abc_123_x/director.json')
  expect(markerPath('/tmp', '')).toBe('/tmp/task-orchestrator/unknown/director.json')
  expect(markerPath('/tmp', 's', 'coordinator.json')).toBe('/tmp/task-orchestrator/s/coordinator.json')
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
  expect(footerLead(['focus', 'director', 'coordinator'])).toBe('focus & ')
  expect(footerLead([])).toBe('')
  expect(footerModes([])).toBe('')
  expect(footerModes(['director'])).toBe('director')
  expect(footerModes(['coordinator', 'director'])).toBe('director & coordinator')
  expect(footerModes(['implementer', 'ralph'])).toBe('')
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
  expect(toolEnvelope({ result: { ok: true, data: 1 } })).toEqual({ ok: true, data: 1 })
  expect(toolEnvelope({ result: { structuredContent: { ok: true, data: 2 } } })).toEqual({ ok: true, data: 2 })
  expect(toolEnvelope({ result: [{ type: 'text', text: '{"ok":true,"data":3}' }] })).toEqual({ ok: true, data: 3 })
  expect(toolEnvelope({ result: 'x', text: '{"ok":false,"error":{}}' })).toEqual({ ok: false, error: {} })
  expect(toolEnvelope({ text: 'nope' })).toBe(undefined)
})

test('coordinator detection: the open element with a token, a close of the run, a marker', async () => {
  const open = { ops: [{ op: 'update', runId: 'r0' }, { op: 'open', rootId: 'x', title: 'Wave title' }] }
  const answered = { ok: true, data: { results: [{ ok: true }, { ok: true, runId: RUN_ID, title: 'Wave title', coordinatorToken: 't' }] } }
  expect(coordinatorFromOpen(open, answered, 'now')).toEqual({ runId: RUN_ID, label: 'Wave title', since: 'now', source: 'tool' })
  expect(coordinatorFromOpen(open, { ok: true, data: { results: [{ ok: true }, { ok: true, runId: RUN_ID, tokenRedacted: true }] } }, 'now')).toBe(null)
  expect(coordinatorFromOpen(open, { ok: false, data: { results: [{ ok: true }, { ok: false }] } }, 'now')).toBe(null)
  expect(coordinatorFromOpen({ ops: [{ op: 'open' }] }, undefined, 'now')).toBe(null)
  const close = { ops: [{ op: 'close', runId: 'a51588a2' }, { op: 'close', runId: 'b' }] }
  expect(closedRunIds(close, { ok: false, data: { results: [{ ok: true }, { ok: false }] } })).toEqual(['a51588a2'])
  expect(sameId('a51588a2', RUN_ID)).toBe(true)
  expect(sameId(RUN_ID.toUpperCase(), 'a51588a2')).toBe(true)
  expect(sameId('', RUN_ID)).toBe(false)
  expect(coordinatorFromMarker({ runId: RUN_ID, label: 'w' }, 'now')).toEqual({ runId: RUN_ID, label: 'w', since: 'now', source: 'marker' })
  expect(coordinatorFromMarker({}, 'now').source).toBe('marker')
})

test('the touched item of a TO call', async () => {
  expect(touchedItemId('mcp__mcp-task-orchestrator__items_read', { select: 'get', itemId: 'abc12345' })).toBe('abc12345')
  expect(touchedItemId('mcp__mcp-task-orchestrator-director__items_read', { select: 'overview' })).toBe(undefined)
  expect(touchedItemId('mcp__mcp-task-orchestrator__advance', { ops: [{ op: 'transition', itemId: 'i1', trigger: 'start' }] })).toBe('i1')
  expect(touchedItemId('mcp__mcp-task-orchestrator__items_write', { ops: [{ op: 'update', id: 'i2' }] })).toBe('i2')
  expect(touchedItemId('mcp__mcp-task-orchestrator__items_write', { ops: [{ op: 'create', parentId: 'p1', title: 't' }] })).toBe('p1')
  expect(touchedItemId('mcp__mcp-task-orchestrator__deps_write', { ops: [{ op: 'create', from: 'f1', to: 't1' }] })).toBe('f1')
  expect(touchedItemId('mcp__mcp-task-orchestrator__notes_write', { ops: [{ op: 'upsert', itemId: 'n1' }] })).toBe('n1')
  expect(touchedItemId('mcp__mcp-task-orchestrator__runs_write', { ops: [{ op: 'open', rootId: 'r' }] })).toBe(undefined)
})

test('the focus walk climbs parentId to depth 1, at most six hops', async () => {
  const items: Record<string, unknown> = {
    t3: { id: 't3', parentId: 't2', depth: 3, title: 'leaf', role: 'work' },
    t2: { id: 't2', parentId: 'f1', depth: 2, title: 'task', role: 'work' },
    f1: { id: 'f1', parentId: 'root', depth: 1, title: 'Feature', statusLabel: 'in-progress', role: 'work' },
  }
  const reads: string[] = []
  const get = async (id: string) => {
    reads.push(id)
    if (!(id in items)) throw new Error('not_found: no')
    return items[id]
  }
  expect((await resolveFocus('t3', get))?.item.id).toBe('f1')
  expect(reads).toEqual(['t3', 't2', 'f1'])
  expect((await resolveFocus('f1', get))?.item.title).toBe('Feature')
  expect(await resolveFocus('missing', get)).toBe(null)
  expect((await resolveFocus('t3', get, 1))?.item.id).toBe('t2')

  // Every id the walk read is reported, so a cache can answer the shared ancestors.
  expect((await resolveFocus('t3', get))?.walked).toEqual(['t3', 't3', 't2', 'f1'])
  // An ancestor an earlier walk resolved ends the walk there with no further read.
  const feature = focusFromGet(items.f1)
  reads.length = 0
  const sibling = await resolveFocus('t3', get, undefined, id => (id === 't2' && feature !== null ? feature : undefined))
  expect(sibling?.item.id).toBe('f1')
  expect(reads).toEqual(['t3'])

  // The default cap is six hops: a depth-9 item settles on its depth-3 ancestor after seven reads.
  const chain: Record<string, unknown> = {}
  for (let d = 2; d <= 9; d++) chain[`d${d}`] = { id: `d${d}`, parentId: `d${d - 1}`, depth: d, title: `depth ${d}`, role: 'work' }
  const chainReads: string[] = []
  const deep = await resolveFocus('d9', async id => {
    chainReads.push(id)
    return chain[id]
  })
  expect(deep?.item.id).toBe('d3')
  expect(chainReads.length).toBe(7)
  expect(focusFromGet({ id: 'root', depth: 0, title: 'Project', role: 'work' })?.parentId).toBe(null)
  expect(focusParent({ id: 'x', parentId: 'p', depth: 1, title: '', label: '', role: '' })).toBe(null)
  expect(focusParent({ id: 'x', parentId: 'p', depth: 4, title: '', label: '', role: '' })).toBe('p')
  expect(focusFromGet({ id: 'f1', statusLabel: 'in-progress', role: 'work' })?.label).toBe('in-progress')
  expect(focusFromGet({ title: 'no id' })).toBe(null)
})

test('focus progress, blocked work, detail and the graph name', async () => {
  const overview = {
    anchor: { id: 'f1' },
    items: [
      { id: 'a', role: 'terminal', childCounts: {} },
      { id: 'b', role: 'work', childCounts: { queue: 2, terminal: 1 } },
      { id: 'c', role: 'blocked', childCounts: {} },
    ],
  }
  const counts = focusProgress(overview)
  expect(counts).toEqual({ queue: 2, work: 1, review: 0, blocked: 1, terminal: 2 })
  expect(focusProgress({ items: [] }, 'review')).toEqual({ queue: 0, work: 0, review: 1, blocked: 0, terminal: 0 })
  expect(focusProgress(undefined)).toEqual({ queue: 0, work: 0, review: 0, blocked: 0, terminal: 0 })
  const blocked = focusBlocked({
    items: [{ id: '6e91c19b', blockers: [{ id: 'ff08e631-x', satisfied: true }, { id: 'd8999287-71c9', satisfied: false }] }],
    page: { total: 3 },
  })
  expect(blocked).toEqual({ total: 3, first: { id: '6e91c19b', blocker: 'd8999287' } })
  expect(focusBlocked({ items: [], page: { total: 0 } })).toEqual({ total: 0 })
  const graph: FocusBoard = { itemId: 'f1', title: 'Feature', label: 'in-progress', counts, blocked: 3, firstBlocked: blocked.first }
  const board: Board = { at: 'x', runs: [], decisions: { open: 2, overdue: 1 }, blocked: 5, focus: graph }
  expect(focusDetail(board, graph)).toBe('q2 → w1 → r0 → ✓2 · blocked 3 · decisions 2 (1 overdue)')
  expect(focusDetail(board, { ...graph, blocked: null })).toBe('q2 → w1 → r0 → ✓2 · blocked 1 · decisions 2 (1 overdue)')
  expect(focusDetail(null, graph)).toBe('board loading')
  expect(focusDetail(board, null)).toBe('board loading')
  expect(focusRowCell(graph)).toBe('in-progress · blocked 3: 6e91c19b waits on d8999287')
  expect(focusRowCell({ ...graph, blocked: 0, firstBlocked: undefined })).toBe('in-progress · blocked 0')
  expect(focusRowCell({ ...graph, label: '', blocked: null })).toBe('no phase')
  expect(graphTitle([RUN], undefined)).toBe('run wave-8')
  expect(graphTitle([RUN, { ...RUN, id: 'p' }], undefined)).toBe('run wave-8 +1')
  expect(graphTitle([{ ...RUN, label: '' }], undefined)).toBe('run Band demo wave')
  expect(graphTitle([], 'A focus title that is far too long')).toBe('A focus title that is f…')
  expect(graphTitle([], undefined)).toBe('')
})

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
