import { expect, test } from 'claude-code/testing'

import type { Board, BoardRun, FocusBoard } from '../types'
import {
  backdropSvg,
  bandWidth,
  blockedByCell,
  blockerOf,
  boardProgress,
  cellsWidth,
  closedRunIds,
  coordinatorFromMarker,
  coordinatorFromOpen,
  decisionCounts,
  enterToast,
  fitCells,
  envelopeData,
  focusBlocked,
  focusFromGet,
  focusValue,
  focusParent,
  focusProgress,
  footerLead,
  footerModes,
  formatTokens,
  graphCounts,
  isDirectorSkillCall,
  itemLead,
  line1Cells,
  line2Cells,
  line3Cells,
  listTotal,
  markerPath,
  pausedCell,
  phasePipeline,
  phaseCell,
  progressBar,
  resolveFocus,
  restData,
  restToolUrl,
  runFromList,
  runLabel,
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

/**
 * The leaf texts of a mount, in order: a Text that nests other Texts (line 1 is one, so the frame
 * cuts it as a whole) reports the join of its children, which is dropped here.
 */
function leaves(found: readonly { text: string }[]): string[] {
  const all = found.map(t => t.text)

  return all.filter((text, i) => {
    let joined = ''
    for (let k = i + 1; k < all.length; k++) {
      joined += all[k]
      if (joined === text) return false
      if (joined.length >= text.length) break
    }
    return true
  })
}

/** The first leaf text a mount of `site` on the desktop shows that matches `pattern`. */
async function shown($: any, site: object, pattern: RegExp): Promise<string | undefined> {
  const ui = await $.ui.mount({ ...site, surface: 'desktop' })
  const found = leaves(await ui.findAll({ type: 'Text' }))
  await ui.unmount()

  return found.find(text => pattern.test(text))
}

/** Every leaf Text a mount of `site` shows, in order. */
async function texts($: any, site: object, surface: 'terminal' | 'desktop' = 'desktop'): Promise<string[]> {
  const ui = await $.ui.mount({ ...site, surface })
  const found = await ui.findAll({ type: 'Text' })
  await ui.unmount()

  return leaves(found)
}

/** Lets the background work a TO call started finish: the hook hands the result back before it observes. */
const settle = () => new Promise<void>(resolve => setTimeout(resolve, 100))

/** A REST envelope body. */
const ok = (data: unknown) => ({ value: { status: 200, ok: true, headers: {}, text: JSON.stringify({ ok: true, data }) } })

/**
 * The server the board reads stand for: items by id from `items`, runs from `runs`, an overview per
 * anchor and a blocked list per ancestor from `lists`; every other list empty.
 */
function server(
  items: Record<string, unknown>,
  runs: Record<string, unknown> = {},
  lists: { overview?: Record<string, unknown[]>; blocked?: Record<string, unknown[]> } = {},
) {
  return (_: unknown, e: { url: string; init?: { body?: unknown } }) => {
    const body = JSON.parse(String(e.init?.body ?? '{}')) as Record<string, string>
    if (e.url.endsWith('/runs_read') && body.select === 'get') return ok(runs[body.runId ?? ''] ?? { status: 'open' })
    if (e.url.endsWith('/items_read') && body.select === 'get') {
      const item = items[body.itemId ?? '']
      return item === undefined
        ? { value: { status: 404, ok: false, headers: {}, text: '{"ok":false,"error":{"code":"not_found","message":"no"}}' } }
        : ok(item)
    }
    const listed =
      e.url.endsWith('/items_read') && body.select === 'overview'
        ? lists.overview?.[body.anchorId ?? '']
        : e.url.endsWith('/items_read') && body.select === 'blocked'
          ? lists.blocked?.[body.ancestorId ?? '']
          : undefined
    if (listed !== undefined) return ok({ items: listed, page: { total: listed.length } })

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
const RUN_BOARD: Board = { at: '2026-11-07T14:02:00.000Z', runs: [RUN], decisions: { open: 2, overdue: 1 } }
const COORD = { runId: RUN_ID, label: 'wave-8', since: 'x', source: 'tool' }

/** A feature with a task under it, as the server answers them. */
const FEATURE = {
  id: 'd99e4f7a-0000',
  parentId: 'ce064d2c-0000',
  depth: 1,
  type: 'feature-implementation',
  title: 'Model and effort policy',
  statusLabel: 'in-progress',
  role: 'work',
}
const TASK = { id: '8da6e710-0000', parentId: 'd99e4f7a-0000', depth: 2, type: 'feature-task', title: 'A task', role: 'queue' }
const FEATURE_LISTS = {
  overview: { 'd99e4f7a-0000': [{ id: '8da6e710', role: 'queue', childCounts: {} }, { id: '29fe3b41', role: 'terminal', childCounts: {} }] },
  blocked: { 'd99e4f7a-0000': [{ id: '8da6e710', blockers: [{ id: '95c660e2-617b', satisfied: false }] }] },
}

test('the footer always names the mode: orchestrator at rest, else the roster modes joined with &', async ($, on) => {
  const cases = [
    { values: { director: null, coordinator: null }, modes: 'orchestrator' },
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
      expect(await ui.find({ type: 'Text', text: /engine/ })).toBe(undefined)
      expect((await ui.find({ type: 'Text', text: /^TO$/ }))?.text).toBe('TO')
      expect((await ui.find({ type: 'Text', text: /orchestrator|director|coordinator/ }))?.text).toBe(c.modes)
      expect((await ui.find({ type: 'Text', text: /focus/ }))?.text).toBe('focus &')
      await ui.unmount()
    }
  }
})

test('the footer label `TO director` follows the native labels while director mode is on', async ($, on) => {
  on('state.get', seedState(() => ({ director: { since: '2026-11-07T14:02:00.000Z' } })))

  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ ...FOOTER, surface })
    expect((await ui.find({ type: 'Text', text: /^TO$/ }))?.text).toBe('TO')
    expect((await ui.find({ type: 'Text', text: /^director$/ }))?.text).toBe('director')
    expect((await ui.find({ type: 'Text', text: /focus/ }))?.text).toBe('focus &')
    await ui.unmount()
  }
})

test('at rest the footer reads `TO orchestrator`, alone when no native label stands', async ($, on) => {
  on('state.get', () => ({ value: { value: null, version: 1 } }))

  const ui = await $.ui.mount({ ...FOOTER, props: { modes: [] }, surface: 'desktop' })
  const all = leaves(await ui.findAll({ type: 'Text' }))
  expect(all).toEqual(['TO', 'orchestrator'])
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

  await $.tool.call({ tool: RUN_TOOL, ops: [{ op: 'open', rootId: 'ce064d2c', label: 'wave-8', items: [] }] })
  await settle()
  expect(await shown($, FOOTER, /coordinator|orchestrator/)).toBe('coordinator')
  expect(toasts.includes('Coordinator of run wave-8')).toBe(true)

  await $.tool.call({ tool: 'mcp__mcp-task-orchestrator-director__runs_write', ops: [{ op: 'close', runId: 'a51588a2', reason: 'completed' }] })
  await settle()
  expect(await shown($, FOOTER, /coordinator|orchestrator/)).toBe('orchestrator')
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
  await settle()
  await $.tool.call({ tool: NOTES_TOOL, ops: [{ op: 'upsert', itemId: 'd8999287', key: 'k', role: 'work', body: '' }], agentId: 'seat-1' })
  await settle()
  expect(await shown($, FOOTER, /coordinator|orchestrator/)).toBe('orchestrator')
  expect(await shown($, BAND, /Band item|engine/)).toBe('engine')
})

test('a touched task draws its feature, the progress and the task itself; /to-band hides and shows the band', async ($, on) => {
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
  on('http.fetch', server({ '8da6e710': TASK, '8da6e710-0000': TASK, 'd99e4f7a-0000': FEATURE }, {}, FEATURE_LISTS))
  on('tool.call', answerWrites(() => ({})))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })

  expect(await shown($, BAND, /Model and|engine/)).toBe('engine')
  await $.tool.call({ tool: NOTES_TOOL, ops: [{ op: 'upsert', itemId: '8da6e710', key: 'k', role: 'work', body: '' }] })
  await settle()
  expect(await texts($, BAND)).toEqual([
    // Line 1: the feature the task belongs to, its title alone.
    'Model and effort policy',
    // Line 2: its type and phase, the subtree with the feature itself, the non-zero blocked count.
    'feature-implementation · in-progress',
    '   ▰▰▰▱▱▱▱▱',
    ' 1/3',
    '   q1 → w1 → r0 → ✓1',
    ' · blocked 1',
    // Line 3: the task this session touched.
    '8da6e710 · feature-task · queue',
    '   A task',
    ' · blocked by 95c660e2',
  ])

  const hidden = await $.command.run({ command: 'to-band' })
  expect(hidden.text).toBe('Band hidden')
  expect(await shown($, BAND, /Model and|engine/)).toBe('engine')
  expect(await shown($, FOOTER, /orchestrator|engine/)).toBe('orchestrator')

  const back = await $.command.run({ command: 'to-band' })
  expect(back.text).toBe('Band shown')
  expect(await shown($, BAND, /^Model and effort policy$|engine/)).toBe('Model and effort policy')
  expect(toasts.filter(t => t.startsWith('Band'))).toEqual(['Band hidden', 'Band shown'])
  expect(stored).toEqual({})
})

test('a lone leaf names itself on line 1 and shows its own phase pipeline, no bar and no line 3', async ($, on) => {
  const leaf = { id: 'd8999287-71c9', parentId: 'ce064d2c-0000', depth: 1, type: 'plugin-change', title: 'Band item', statusLabel: 'in-progress', role: 'work' }
  on('ui.toast', () => ({ value: undefined }))
  on('http.fetch', server({ d8999287: leaf, 'd8999287-71c9': leaf }))
  on('tool.call', answerWrites(() => ({})))

  await $.tool.call({ tool: NOTES_TOOL, ops: [{ op: 'upsert', itemId: 'd8999287', key: 'k', role: 'work', body: '' }] })
  await settle()
  expect(await texts($, BAND)).toEqual(['Band item', 'plugin-change', ' · queue → ', '◉ work', ' → review → done'])
})

test('a long title is cut to the frame with an ellipsis, so no band line can wrap', async ($, on) => {
  const title = 'orchestration-mode: graph-progression band (toggleable) and visible-mode footer (director, coordinator)'
  const leaf = { id: 'd8999287-71c9', parentId: 'ce064d2c-0000', depth: 1, type: 'plugin-change', title, statusLabel: 'in-progress', role: 'work' }
  on('ui.toast', () => ({ value: undefined }))
  on('http.fetch', server({ d8999287: leaf, 'd8999287-71c9': leaf }))
  on('tool.call', answerWrites(() => ({})))

  await $.tool.call({ tool: NOTES_TOOL, ops: [{ op: 'upsert', itemId: 'd8999287', key: 'k', role: 'work', body: '' }] })
  await settle()
  // The terminal's cells are the width: the mod cuts the line to them.
  for (const bodyColumns of [100, 50]) {
    const lines = await texts($, { ...BAND, props: { ...BAND.props, bodyColumns } }, 'terminal')
    expect(lines[0]).toBe(title.slice(0, bodyColumns - 5).trimEnd() + '…')
    expect(lines[0]?.length).toBe(bodyColumns - 4)
    expect(lines.slice(1).join('')).toBe('plugin-change · queue → ◉ work → review → done')
  }
  // The desktop's font is proportional: the line goes over whole and the surface cuts it at the frame.
  const desktop = await texts($, { ...BAND, props: { ...BAND.props, bodyColumns: 50 } })
  expect(desktop[0]).toBe(title)
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
  await settle()
  await $.tool.call({ tool: NOTES_TOOL, ops: [{ op: 'upsert', itemId: 'd99e4f7a-0000', key: 'k', role: 'work', body: '' }] })
  await settle()
  expect(await shown($, BAND, /^Model and effort policy$|engine/)).toBe('Model and effort policy')
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
  await settle()
  expect(errored.isError).toBe(true)
  expect(await shown($, FOOTER, /coordinator|orchestrator/)).toBe('orchestrator')

  answer = { deny: 'no runs here' }
  await $.tool.call(open)
  await settle()
  expect(await shown($, FOOTER, /coordinator|orchestrator/)).toBe('orchestrator')
  expect(toasts).toEqual([])

  answer = { result: opened, text: JSON.stringify(opened) }
  const ran = await $.tool.call(open)
  await settle()
  expect(ran.result).toEqual(opened)
  expect(ran.text).toBe(JSON.stringify(opened))
  expect(await shown($, FOOTER, /coordinator|orchestrator/)).toBe('coordinator')
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
  await settle()
  expect(await shown($, FOOTER, /coordinator|orchestrator/)).toBe('coordinator')
  expect(toasts.includes('Coordinator of run wave-8')).toBe(true)

  await $.tool.call({ tool: RUN_TOOL, ops: [{ op: 'close', runId: RUN_ID, reason: 'completed' }] })
  await settle()
  expect(await shown($, FOOTER, /coordinator|orchestrator/)).toBe('coordinator')
  expect(toasts.includes('Run wave-8 closed')).toBe(false)
})

test('a run with nothing touched draws its facts and the progress; hidden or with no graph, nothing', async ($, on) => {
  let values: Record<string, unknown> = { board: RUN_BOARD, bandHidden: false, focus: null }
  on('state.get', seedState(() => values))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })

  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ ...BAND, surface })
    expect(leaves(await ui.findAll({ type: 'Text' }))).toEqual([
      'run wave-8 open',
      ' · COORD STALLED',
      ' · in flight 2 (1 stalled)',
      ' · ready 1',
      ' · waiting 1',
      '▰▰▱▱▱▱▱▱',
      ' 1/5',
      '   q2 → w1 → r0 → ✓1',
      ' · blocked 1',
      ' · decisions 2 (1 overdue)',
    ])
    expect(await ui.find({ type: 'Text', text: /orchestration-mode|DIRECTOR MODE/ })).toBe(undefined)
    await ui.unmount()
  }
  expect(await shown($, { ...BAND, props: { ...BAND.props, bodyColumns: 130 } }, /tok$/)).toBe(' · 1.3M tok')

  values = { board: RUN_BOARD, bandHidden: true }
  expect(await shown($, BAND, /run wave-8|engine/)).toBe('engine')

  values = { board: { ...RUN_BOARD, runs: [] }, bandHidden: false, focus: null }
  expect(await shown($, BAND, /run wave-8|engine/)).toBe('engine')
})

test('the focus view draws container, progress and item, and a short band drops line 3, then line 2', async ($, on) => {
  const graph: FocusBoard = {
    itemId: 'd99e4f7a-0000',
    counts: { queue: 1, work: 1, review: 0, blocked: 0, terminal: 2 },
    blocked: 1,
    container: { itemId: 'd99e4f7a-0000', title: 'Model and effort policy', type: 'feature-implementation', label: 'in-progress' },
    touched: { itemId: '6e91c19b-0000', title: 'A task with a long title that the frame truncates', type: 'feature-task', label: 'queue', blockedBy: 'd8999287' },
  }
  const touched = { itemId: '6e91c19b-0000', title: 'A task', type: 'feature-task', label: 'queue' }
  let maxRows = 6
  on(
    'state.get',
    seedState(() => ({
      board: { ...RUN_BOARD, runs: [], focus: graph },
      bandHidden: false,
      focus: { itemId: 'd99e4f7a', title: 'Model and effort policy', since: 'x', touched },
    })),
  )
  const band = () => ({ ...BAND, props: { ...BAND.props, maxRows } })
  // At 96 cells the decisions count is the cell line 2 gives up, and line 3 cuts its title.
  const progress = ['feature-implementation · in-progress', '   ▰▰▰▰▱▱▱▱', ' 2/4', '   q1 → w1 → r0 → ✓2', ' · blocked 1']

  // The terminal cuts by cells; the desktop gets every cell whole and cuts at the frame itself.
  expect(await texts($, band(), 'terminal')).toEqual([
    'Model and effort policy',
    ...progress,
    '6e91c19b · feature-task · queue',
    '   A task with a long title that the frame…',
    ' · blocked by d8999287',
  ])
  expect(await texts($, band())).toEqual([
    'Model and effort policy',
    ...progress,
    ' · decisions 2 (1 overdue)',
    '6e91c19b · feature-task · queue',
    '   A task with a long title that the frame truncates',
    ' · blocked by d8999287',
  ])
  const wide = await texts($, { ...band(), props: { ...band().props, bodyColumns: 130 } }, 'terminal')
  expect(wide.includes(' · decisions 2 (1 overdue)')).toBe(true)

  maxRows = 4
  expect(await texts($, band(), 'terminal')).toEqual(['Model and effort policy', ...progress])
  maxRows = 3
  expect(await texts($, band(), 'terminal')).toEqual(['Model and effort policy'])
})

test('before the first read the band names the focus and the touched item around a loading progress line', async ($, on) => {
  const touched = { itemId: '6e91c19b-0000', title: 'A task', type: 'feature-task', label: 'queue' }
  on('state.get', seedState(() => ({ board: null, bandHidden: false, focus: { itemId: 'd99e4f7a', title: 'F', since: 'x', touched } })))

  expect(await texts($, BAND)).toEqual(['F', 'loading', '6e91c19b · feature-task · queue', '   A task'])
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
  expect(footerModes([])).toBe('orchestrator')
  expect(footerModes(['director'])).toBe('director')
  expect(footerModes(['coordinator', 'director'])).toBe('director & coordinator')
  expect(footerModes(['implementer', 'ralph'])).toBe('orchestrator')
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
  // A focus written before `touched` existed (the atom survives a reload) is its own touched item.
  expect(focusValue({ itemId: 'f1', title: 'Feature', since: 't' })).toEqual({ itemId: 'f1', title: 'Feature', since: 't', touched: { itemId: 'f1', title: 'Feature', type: '', label: '' } })
  expect(focusValue({ itemId: 'f1', title: 'Feature', since: 't', touched: { itemId: 'c1', title: 'Child', type: 'task', label: 'queue' } })?.touched.itemId).toBe('c1')
  expect(focusValue(null)).toBe(null)
  expect(focusValue({ title: 'no id' })).toBe(null)
})

test('focus progress, blocked work and the line helpers', async () => {
  const overview = {
    anchor: { id: 'f1' },
    items: [
      { id: 'a', role: 'terminal', childCounts: {} },
      { id: 'b', role: 'work', childCounts: { queue: 2, terminal: 1 } },
      { id: 'c', role: 'blocked', childCounts: {} },
    ],
  }
  // The focus itself counts too.
  expect(focusProgress(overview, 'work')).toEqual({ queue: 2, work: 2, review: 0, blocked: 1, terminal: 2 })
  expect(focusProgress(overview)).toEqual({ queue: 2, work: 1, review: 0, blocked: 1, terminal: 2 })
  expect(focusProgress({ items: [] }, 'review')).toEqual({ queue: 0, work: 0, review: 1, blocked: 0, terminal: 0 })
  expect(focusProgress(undefined)).toEqual({ queue: 0, work: 0, review: 0, blocked: 0, terminal: 0 })
  const blocked = focusBlocked({
    items: [
      { id: '6e91c19b', blockers: [{ id: 'ff08e631-x', satisfied: true }, { id: 'd8999287-71c9', satisfied: false }] },
      { id: '3851441D', blockers: [{ id: '95c660e2-617b', satisfied: false }] },
    ],
    page: { total: 3 },
  })
  expect(blocked).toEqual({ total: 3, blockers: { '6e91c19b': 'd8999287', '3851441d': '95c660e2' } })
  expect(blockerOf(blocked.blockers, '6e91c19b-0000-full')).toBe('d8999287')
  expect(blockerOf(blocked.blockers, 'ffffffff')).toBe(undefined)
  expect(focusBlocked({ items: [], page: { total: 0 } })).toEqual({ total: 0, blockers: {} })
  expect(itemLead({ itemId: 'd8999287-71c9-4b30', title: 't', type: 'plugin-change', label: 'in-progress' })).toBe(
    'd8999287 · plugin-change · in-progress',
  )
  expect(itemLead({ itemId: 'd8999287', title: 't', type: '', label: 'work' })).toBe('d8999287 · work')
  expect(blockedByCell('95c660e2-617b-49ef')).toBe('blocked by 95c660e2')
})

test('line 2: the counts across the runs or the focus, only the non-zero extras', async () => {
  const paused: BoardRun = { ...RUN, id: 'p', status: 'paused', pauseReason: 'attempt-cap', stalled: 0, coordinatorStalled: false }
  const board: Board = { at: 'x', runs: [RUN, paused], decisions: { open: 2, overdue: 1 } }
  const feature = { itemId: 'f', title: 'Phase 4 plan', type: 'feature-implementation', label: 'in-progress' }
  expect(graphCounts(board)).toEqual({
    counts: { queue: 4, work: 2, review: 0, blocked: 2, terminal: 2 },
    done: 2,
    total: 10,
    blocked: 2,
    attention: true,
  })
  expect(graphCounts({ ...board, runs: [paused] }).attention).toBe(false)
  expect(line2Cells(board, feature, null)).toEqual([
    { text: 'feature-implementation · in-progress', tone: 'dim' },
    { text: '   ▰▰▱▱▱▱▱▱', tone: 'error' },
    { text: ' 2/10', tone: 'dim' },
    { text: '   q4 → w2 → r0 → ✓2', tone: 'dim' },
    { text: ' · blocked 2', tone: 'dim' },
    { text: ' · decisions 2 (1 overdue)', tone: 'dim' },
  ])
  const calm: Board = { at: 'x', runs: [{ ...RUN, counts: { ...RUN.counts, blocked: 0 }, stalled: 0, coordinatorStalled: false }], decisions: { open: 0, overdue: 0 } }
  expect(line2Cells(calm, null, null).map(c => c.text)).toEqual(['▰▰▱▱▱▱▱▱', ' 1/4', '   q2 → w1 → r0 → ✓1'])
  expect(line2Cells(calm, null, null)[0]?.tone).toBe('success')
  const container: FocusBoard = {
    itemId: 'f',
    counts: { queue: 1, work: 0, review: 0, blocked: 0, terminal: 1 },
    hasChildren: true,
    role: 'work',
    blocked: 0,
    container: feature,
    touched: null,
  }
  const focusOnly: Board = { at: 'x', runs: [], decisions: null, focus: container }
  expect(graphCounts(focusOnly)).toEqual({ counts: container.counts, done: 1, total: 2, blocked: 0, attention: false })
  expect(line2Cells(focusOnly, feature, container).map(c => c.text)).toEqual([
    'feature-implementation · in-progress',
    '   ▰▰▰▰▱▱▱▱',
    ' 1/2',
    '   q1 → w0 → r0 → ✓1',
  ])
  // A focus with no children shows its type and its own phases, never a bar of one.
  const leafItem = { itemId: 'l', title: 'Leaf', type: 'plugin-change', label: 'in-progress' }
  const leaf: FocusBoard = { ...container, counts: { queue: 0, work: 1, review: 0, blocked: 0, terminal: 0 }, hasChildren: false, container: leafItem }
  expect(line2Cells({ ...focusOnly, focus: leaf }, leafItem, leaf).map(c => c.text).join('')).toBe('plugin-change · queue → ◉ work → review → done')
  // A board saved before `hasChildren` existed: one member is a leaf, more are a container.
  const { hasChildren: _h, role: _r, ...old } = leaf
  expect(line2Cells({ ...focusOnly, focus: old }, leafItem, old).map(c => c.text).join('')).toBe('plugin-change · queue → work → review → done')
  expect(line2Cells(null, feature, null).map(c => c.text)).toEqual(['feature-implementation · in-progress', '   loading'])
  expect(line2Cells(null, null, null).map(c => c.text)).toEqual(['loading'])
  expect(line2Cells({ ...board, error: 'http 502: no JSON body' }, null, null).map(c => c.text)).toEqual(['board unavailable: http 502: no JSON body'])
  expect(boardProgress(board)).toEqual({ done: 2, total: 10 })
  expect(boardProgress(null)).toEqual({ done: 0, total: 0 })
  expect(progressBar(2, 10)).toBe('▰▰▱▱▱▱▱▱')
  expect(progressBar(0, 0)).toBe('▱▱▱▱▱▱▱▱')
  expect(progressBar(5, 4, 4)).toBe('▰▰▰▰')
  expect(decisionCounts({ items: [{ status: 'open', overdue: true }, { status: 'acknowledged' }, { status: 'answered' }] })).toEqual({
    open: 2,
    overdue: 1,
  })
  expect(listTotal({ items: [{}, {}], page: { total: 7 } })).toBe(7)
  expect(listTotal({ items: [{}, {}] })).toBe(2)
})

test('a leaf phase pipeline lights the current phase', async () => {
  const text = (role: string) => phasePipeline(role).map(c => c.text).join('')
  const lit = (role: string) => phasePipeline(role).filter(c => c.tone === 'lit').map(c => c.text)
  expect(text('queue')).toBe('◉ queue → work → review → done')
  expect(text('work')).toBe('queue → ◉ work → review → done')
  expect(text('review')).toBe('queue → work → ◉ review → done')
  expect(text('terminal')).toBe('queue → work → review → ◉ done')
  expect(lit('work')).toEqual(['◉ work'])
  expect(text('blocked')).toBe('queue → work → review → done · ◉ blocked')
  expect(lit('blocked')).toEqual([' · ◉ blocked'])
  expect(text('')).toBe('queue → work → review → done')
  expect(phasePipeline('queue', ' · ').map(c => c.text)).toEqual([' · ', '◉ queue', ' → work → review → done'])
})

test('line 1: the title alone, then the run facts', async () => {
  const feature = { itemId: 'f', title: 'Phase 4 plan', type: 'feature-implementation', label: 'in-progress' }
  expect(line1Cells(null, [], 100)).toBe(null)
  expect(line1Cells(feature, [], 100)).toEqual([{ text: 'Phase 4 plan', tone: 'title' }])
  expect(line1Cells(feature, [RUN], 130)).toEqual([
    { text: 'Phase 4 plan', tone: 'title' },
    { text: ' · run wave-8 open', tone: 'dim' },
    { text: ' · COORD STALLED', tone: 'errorBold' },
    { text: ' · in flight 2 (1 stalled)', tone: 'dim' },
    { text: ' · ready 1', tone: 'dim' },
    { text: ' · waiting 1', tone: 'dim' },
    { text: ' · 1.3M tok', tone: 'dim' },
  ])
  const paused: BoardRun = { ...RUN, id: 'p', status: 'paused', pauseReason: 'run-budget' }
  expect(line1Cells(null, [paused], 100)?.[1]).toEqual({ text: ' · PAUSED run-budget', tone: 'warningBold' })
  expect(line1Cells(null, [RUN, { ...RUN, id: 'r2' }], 100)?.[0]?.text).toBe('2 runs open')
  expect(line1Cells(null, [RUN, paused], 100)?.[0]?.text).toBe('2 runs, 1 open, 1 paused')
  expect(line1Cells(null, [{ ...RUN, label: 'a label far too long for the line two cell' }], 100)?.[0]?.text).toBe(
    'run a label far too long fo… open',
  )
})

test('every line is cut to the frame: cells from the end first, then the title ends in an ellipsis', async () => {
  expect(bandWidth(100)).toBe(96)
  const title = 'orchestration-mode: graph-progression band (toggleable) and visible-mode footer (director, coordinator)'
  const head = line1Cells({ itemId: 'd', title, type: 'plugin-change', label: 'in-progress' }, [RUN], 100) ?? []
  // The run facts go first, then the title is cut.
  const fitted = fitCells(head, 96)
  expect(fitted).toEqual([{ text: 'orchestration-mode: graph-progression band (toggleable) and visible-mode footer (director, coor…', tone: 'title' }])
  expect(cellsWidth(fitted)).toBe(96)
  const short = fitCells(line1Cells({ itemId: 'd', title: 'Phase 4 plan', type: '', label: '' }, [RUN], 100) ?? [], 50)
  expect(short.map(c => c.text)).toEqual(['Phase 4 plan', ' · run wave-8 open', ' · COORD STALLED'])
  expect(cellsWidth(short) <= 50).toBe(true)
  // Line 3: the title gives way before the blocker; past a dozen cells the blocker goes.
  const item = { itemId: '6e91c19b-0000', title: 'A task with a long title that the frame truncates', type: 'feature-task', label: 'queue', blockedBy: 'd8999287' }
  expect(line3Cells(item, 96).map(c => c.text)).toEqual(['6e91c19b · feature-task · queue', '   A task with a long title that the frame…', ' · blocked by d8999287'])
  expect(line3Cells(item, 60).map(c => c.text)).toEqual(['6e91c19b · feature-task · queue', '   A task with a long title…'])
  expect(cellsWidth(line3Cells(item, 60))).toBe(59)
  expect(line3Cells({ ...item, blockedBy: undefined }, 200).map(c => c.text)).toEqual([
    '6e91c19b · feature-task · queue',
    '   A task with a long title that the frame truncates',
  ])
  // Too narrow for the cells before the elastic one: that one alone, cut.
  expect(fitCells([{ text: 'a long lead cell', tone: 'dim' }, { text: '   title', tone: 'text' }], 6, 1)).toEqual([{ text: 'title', tone: 'text' }])
})

test('a run is built from the list item and its frontier', async () => {
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
  expect(runLabel(run)).toBe('wave-8')
  expect(runLabel({ ...run, label: '' })).toBe('Band demo wave')
  expect(runLabel({ ...run, label: '', title: 'A title that is far too long for one cell' })).toBe('A title that is far too…')
  expect(runLabel({ ...run, title: '', label: '' })).toBe('a51588a2')
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
