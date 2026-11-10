import { expect, test } from 'claude-code/testing'

import type { Board, BoardRun, FocusBoard } from '../types'
import type { BarCell, Row } from './model.ts'
import {
  AGENT_COLOURS,
  NEEDS_YOU,
  activityText,
  agentColour,
  agentRows,
  assignColours,
  backdropSvg,
  bandWidth,
  barCells,
  barRun,
  blockedByCell,
  blockerOf,
  cellStyle,
  cellsWidth,
  closedRunIds,
  colourKey,
  coordStalled,
  coordinatorFromMarker,
  coordinatorFromOpen,
  countsText,
  enterToast,
  envelopeData,
  fitBar,
  fitCells,
  fitRow,
  focusBlocked,
  focusFromGet,
  focusParent,
  isGrouping,
  focusProgress,
  focusValue,
  footerLead,
  footerModes,
  frontierMembers,
  isDirectorSkillCall,
  itemLead,
  line3Cells,
  listTotal,
  markerPath,
  namedRunIds,
  phasePipeline,
  quietFor,
  resolveFocus,
  restData,
  restToolUrl,
  runFromList,
  runLabel,
  sameId,
  sanitizeKey,
  seatsDone,
  sessionRuns,
  statusRow,
  titleRow,
  tmpDir,
  toolEnvelope,
  touchedItemId,
  watchRuns,
  withFrontier,
  workingSlots,
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
const READ_TOOL = 'mcp__mcp-task-orchestrator__items_read'
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
 * The leaf texts of a mount, in order: the Texts whose children are all text. A Text that nests other Texts (a
 * row, which the frame cuts as a whole) is left out.
 */
function leaves(found: readonly { text: string; children?: unknown[] }[]): string[] {
  return found.filter(t => (t.children ?? []).every(c => typeof c === 'string')).map(t => t.text)
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
  cancelled: 0,
  ready: [{ id: 'a1111111', title: 'A task', role: 'queue' }],
  inFlight: [
    { id: 'b2222222', role: 'work', title: 'B task', seat: 'implementer', stalled: true, lastSeenAt: '2026-11-07T13:48:00.000Z' },
    { id: 'c3333333', role: 'queue', title: 'C task', seat: 'planner', stalled: false },
  ],
  waiting: [{ id: 'd4444444', reason: 'awaiting_decision' }],
}

const RUN = withFrontier(runFromList(LISTED) as BoardRun, FRONTIER)
const RUN_BOARD: Board = { at: '2026-11-07T14:02:00.000Z', runs: [RUN], colours: {} }
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
    // Title row: the feature the task belongs to, and its bar: one done, one in progress (the feature itself), one
    // not started, still (a focus graph has no agents).
    'Model and effort policy',
    '▰',
    '▰',
    '▱',
    // Status row: its type and phase and its blocked work; the members by phase on the right.
    'feature-implementation · in-progress',
    ' · 1 waiting on another item',
    '1 planned · 1 in progress · 1 done',
    // The task this session touched.
    '8da6e710 · feature-task · queue',
    '   A task',
    ' · blocked by 95c660e2',
  ])
  expect(await shown($, BAND, /never/)).toBe(undefined)

  // A read of the feature itself (an overview while the task is in hand) keeps the task on line 3.
  await $.tool.call({ tool: READ_TOOL, select: 'context', itemId: 'd99e4f7a-0000' })
  await settle()
  expect((await texts($, BAND)).slice(-3)).toEqual(['8da6e710 · feature-task · queue', '   A task', ' · blocked by 95c660e2'])

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
  expect(await texts($, BAND)).toEqual(['Band item', 'plugin-change', 'queue → ', '◉ work', ' → review → done'])
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
    expect(lines.slice(1)).toEqual(['plugin-change', 'queue → ', '◉ work', ' → review → done'])
  }
  // The desktop's font is proportional: the line goes over whole and the surface cuts it at the frame.
  const desktop = await texts($, { ...BAND, props: { ...BAND.props, bodyColumns: 50 } })
  expect(desktop[0]).toBe(title)
  // The right side never shrinks: a long title gives way, never the phases (or a bar) beside it.
  const ui = await $.ui.mount({ ...BAND, props: { ...BAND.props, bodyColumns: 50 }, surface: 'desktop' })
  const kept = (await ui.findAll({ type: 'Box' })).filter(b => b.props.flexShrink === 0).map(b => b.text)
  expect(kept).toEqual(['queue → ◉ work → review → done'])
  await ui.unmount()
})

test('a task under a backlog bucket focuses its own feature; the bucket touched itself does not count itself', async ($, on) => {
  const bucket = { id: 'babac430-0000', parentId: 'ce064d2c-0000', depth: 1, type: 'container', tags: ['container'], title: 'Bugs', statusLabel: 'done', role: 'work' }
  const fix = { id: 'efccb63a-0000', parentId: 'babac430-0000', depth: 2, type: 'feature-implementation', title: '4.0.1 fix', role: 'queue' }
  const task = { id: '240116c3-0000', parentId: 'efccb63a-0000', depth: 3, type: 'feature-task', title: 'A fix', role: 'queue' }
  on('ui.toast', () => ({ value: undefined }))
  on(
    'http.fetch',
    server({ [bucket.id]: bucket, [fix.id]: fix, [task.id]: task }, {}, {
      overview: {
        [fix.id]: [{ id: '240116c3', role: 'queue', childCounts: {} }, { id: '1ef19932', role: 'terminal', childCounts: {} }],
        [bucket.id]: [{ id: 'efccb63a', role: 'queue', childCounts: { queue: 1, terminal: 1 } }],
      },
    }),
  )
  on('tool.call', answerWrites(() => ({})))

  // The walk stops below the bucket: the band is the feature (itself planned, one task planned, one done).
  await $.tool.call({ tool: NOTES_TOOL, ops: [{ op: 'upsert', itemId: task.id, key: 'k', role: 'work', body: '' }] })
  await settle()
  const feature = await texts($, BAND)
  expect(feature[0]).toBe('4.0.1 fix')
  expect(feature.includes('2 planned · 1 done')).toBe(true)
  expect(feature.includes('Bugs')).toBe(false)

  // The bucket touched itself is the focus, and counts its work, not itself (it is in work, labelled done).
  await $.tool.call({ tool: NOTES_TOOL, ops: [{ op: 'upsert', itemId: bucket.id, key: 'k', role: 'work', body: '' }] })
  await settle()
  const touchedBucket = await texts($, BAND)
  expect(touchedBucket[0]).toBe('Bugs')
  expect(touchedBucket.includes('2 planned · 1 done')).toBe(true)
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

test('a run with nothing touched: one Client draws title and bar, status, and the agents, blinking in step; hidden or with no graph, nothing', async ($, on) => {
  let values: Record<string, unknown> = { board: RUN_BOARD, bandHidden: false, focus: null }
  on('state.get', seedState(() => values))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })

  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ ...BAND, surface })
    expect(await ui.find({ type: 'Client', key: 'band' })).toBeDefined()
    const inBand = async () => leaves(await ui.findAll({ type: 'Text', in: 'band' }))
    // The terminal's 96 cells cut the status row's activity first; the counts on the right stay whole.
    const activity = surface === 'terminal' ? [] : [' · 1 agent planning']
    expect(await inBand()).toEqual([
      // Title row: no container read, so the run's short name; the bar: done, C worked on (blue), B stuck, D
      // waiting on a decision, A not started.
      'run wave-8',
      '▰',
      '▰',
      '▰',
      '▰',
      '▱',
      // Status row: what needs a look first, then what the agents do; the members by phase on the right.
      '1 agent stuck',
      ' · 1 decision for you',
      ...activity,
      '2 planned · 1 in progress · 1 on hold · 1 done',
      // One row per agent, the stuck one first.
      '◉',
      ' implementer · quiet 14m',
      '   B task',
      '◉',
      ' planner',
      '   C task',
    ])
    // The planner's marker and its bar cell share its colour, and blink together on the Client's clock.
    const blue = async () =>
      (await ui.findAll({ type: 'Text', in: 'band' })).filter(t => t.props.color === AGENT_COLOURS[0]).map(t => t.text)
    expect(await blue()).toEqual(['▰', '◉'])
    await ui.advance(600)
    expect(await blue()).toEqual(['▱', '○'])
    expect((await inBand()).filter(t => t === '◉')).toEqual(['◉'])
    await ui.advance(600)
    expect(await blue()).toEqual(['▰', '◉'])
    // The right sides (the bar, the counts) never shrink: a long title gives way, never the bar.
    const kept = (await ui.findAll({ type: 'Box', in: 'band' })).filter(b => b.props.flexShrink === 0).map(b => b.text)
    expect(kept).toEqual(['▰▰▰▰▱', '2 planned · 1 in progress · 1 on hold · 1 done'])
    await ui.unmount()
  }

  values = { board: RUN_BOARD, bandHidden: true }
  expect(await shown($, BAND, /run wave-8|engine/)).toBe('engine')

  values = { board: { ...RUN_BOARD, runs: [] }, bandHidden: false, focus: null }
  expect(await shown($, BAND, /run wave-8|engine/)).toBe('engine')
})

test('the focus view draws title and bar, status, and the item, and a short band drops rows from the bottom', async ($, on) => {
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
  // The title with the bar: two done, one in progress (no agents in a focus graph, so it does not blink), one not started.
  const title = ['Model and effort policy', '▰▰', '▰', '▱']

  // The terminal cuts by cells: the status row's blocked count gives way to the counts; the item's title is cut.
  expect(await texts($, band(), 'terminal')).toEqual([
    ...title,
    'feature-implementation · in-progress',
    '1 planned · 1 in progress · 2 done',
    '6e91c19b · feature-task · queue',
    '   A task with a long title that the frame…',
    ' · blocked by d8999287',
  ])
  expect(await texts($, band())).toEqual([
    ...title,
    'feature-implementation · in-progress',
    ' · 1 waiting on another item',
    '1 planned · 1 in progress · 2 done',
    '6e91c19b · feature-task · queue',
    '   A task with a long title that the frame truncates',
    ' · blocked by d8999287',
  ])

  maxRows = 4
  expect(await texts($, band(), 'terminal')).toEqual([...title, 'feature-implementation · in-progress', '1 planned · 1 in progress · 2 done'])
  maxRows = 3
  expect(await texts($, band(), 'terminal')).toEqual(title)
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

  // A parent that only groups work (a backlog bucket) ends the walk below it, unread into the cache.
  const bucketed: Record<string, unknown> = {
    k3: { id: 'k3', parentId: 'k2', depth: 3, type: 'feature-task', title: 'task', role: 'queue' },
    k2: { id: 'k2', parentId: 'bugs', depth: 2, type: 'feature-implementation', title: '4.0.1', role: 'queue' },
    bugs: { id: 'bugs', parentId: 'root', depth: 1, type: 'container', tags: ['container'], title: 'Bugs', role: 'work' },
  }
  const getBucketed = async (id: string) => bucketed[id]
  const underBucket = await resolveFocus('k3', getBucketed)
  expect(underBucket?.item.id).toBe('k2')
  expect(underBucket?.walked).toEqual(['k3', 'k3', 'k2'])
  // A cached bucket (the session touched the bucket itself) ends the walk below it too.
  const bugs = focusFromGet(bucketed.bugs)
  expect(bugs?.grouping).toBe(true)
  expect((await resolveFocus('k2', getBucketed, undefined, id => (id === 'bugs' && bugs !== null ? bugs : undefined)))?.item.id).toBe('k2')
  expect(isGrouping({ type: 'Container' })).toBe(true)
  expect(isGrouping({ type: 'project' })).toBe(true)
  expect(isGrouping({ type: 'epic', tags: ['Container'] })).toBe(true)
  expect(isGrouping({ type: 'feature-implementation', tags: ['plugin'] })).toBe(false)
  expect(isGrouping(null)).toBe(false)
  expect(focusFromGet(bucketed.k2)?.grouping).toBe(undefined)
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

test('frontier members: in flight, waiting and ready, each once, with the status the band draws', async () => {
  expect(frontierMembers(FRONTIER)).toEqual([
    { id: 'b2222222', status: 'stuck', role: 'work', seat: 'implementer', title: 'B task', lastSeenAt: '2026-11-07T13:48:00.000Z' },
    { id: 'c3333333', status: 'working', role: 'queue', seat: 'planner', title: 'C task' },
    { id: 'd4444444', status: 'needsYou' },
    { id: 'a1111111', status: 'notStarted', role: 'queue', title: 'A task' },
  ])
  // A ready member past the queue has finished its step and waits to be advanced; one in the queue has not started.
  expect(frontierMembers({ ready: [{ id: 'w', role: 'work' }, { id: 'r', role: 'review' }, { id: 'q', role: 'queue' }] }).map(m => m.status)).toEqual([
    'finished',
    'finished',
    'notStarted',
  ])
  // Once every seat of a Workflow has ended, every ready member has finished its step.
  expect(frontierMembers({ ready: [{ id: 'q', role: 'queue' }] }, true).map(m => m.status)).toEqual(['finished'])
  // Two seats on one member: one entry, working while either is live.
  const shared = frontierMembers({ inFlight: [{ id: 'e', stalled: true, seat: 'implementer' }, { id: 'e', stalled: false, seat: 'reviewer' }] })
  expect(shared.map(m => [m.id, m.status, m.seat])).toEqual([['e', 'working', 'reviewer']])
  // Waiting on another item is not an alarm: not started.
  expect(frontierMembers({ waiting: [{ id: 'x', reason: 'dependency' }, { id: 'y', reason: 'manual' }] }).map(m => m.status)).toEqual([
    'notStarted',
    'notStarted',
  ])
})

test('the bar: one cell per member in state order, an agent member in its own colour, ⧄ for cancelled, shares past sixteen', async () => {
  expect(barCells(RUN_BOARD)).toEqual([
    { state: 'done' },
    { state: 'working', colour: AGENT_COLOURS[0] },
    { state: 'stuck' },
    { state: 'needsYou' },
    { state: 'notStarted' },
  ])
  expect(barCells(RUN_BOARD, { [colourKey(RUN_ID, 'c3333333')]: 2 })[1]).toEqual({ state: 'working', colour: AGENT_COLOURS[2] })
  const counts = (over: Partial<BoardRun['counts']>) => ({ queue: 0, work: 0, review: 0, blocked: 0, terminal: 0, ...over })
  // Cancelled members are terminal but never done.
  expect(barCells({ at: 'x', runs: [{ ...RUN, counts: counts({ terminal: 3 }), cancelled: 1, members: [] }] }).map(c => c.state)).toEqual([
    'done',
    'done',
    'cancelled',
  ])
  // Members the frontier does not list are not started.
  expect(barCells({ at: 'x', runs: [{ ...RUN, counts: counts({ queue: 2 }), members: [] }] }).map(c => c.state)).toEqual(['notStarted', 'notStarted'])
  // A focus graph has no agents: its members in work or review are in progress, still.
  const focus: FocusBoard = { itemId: 'f', counts: counts({ queue: 1, work: 1, review: 1, blocked: 1, terminal: 1 }), blocked: 0, container: null, touched: null }
  expect(barCells({ at: 'x', runs: [], focus }).map(c => c.state)).toEqual(['done', 'progress', 'progress', 'notStarted', 'notStarted'])

  expect(barRun(barCells(RUN_BOARD))).toEqual([
    { text: '▰', tone: 'success' },
    { text: '▰', tone: 'agent', color: AGENT_COLOURS[0], blink: true, off: '▱' },
    { text: '▰', tone: 'error' },
    { text: '▰', tone: 'needsYou' },
    { text: '▱', tone: 'dim' },
  ])
  expect(barRun([{ state: 'done' }, { state: 'done' }, { state: 'cancelled' }, { state: 'finished' }, { state: 'progress' }])).toEqual([
    { text: '▰▰', tone: 'success' },
    { text: '⧄', tone: 'error' },
    { text: '▰', tone: 'finished' },
    { text: '▰', tone: 'text' },
  ])
  // Two agents in one colour (past four at once) join, blinking together.
  expect(barRun([{ state: 'working', colour: '#111111' }, { state: 'working', colour: '#111111' }])).toEqual([
    { text: '▰▰', tone: 'agent', color: '#111111', blink: true, off: '▱▱' },
  ])

  // Past sixteen: every agent, stuck and decision cell stays; the rest share the room left.
  const many: BarCell[] = [
    ...Array.from({ length: 30 }, (): BarCell => ({ state: 'done' })),
    { state: 'working', colour: AGENT_COLOURS[0] },
    { state: 'working', colour: AGENT_COLOURS[1] },
    { state: 'stuck' },
    ...Array.from({ length: 67 }, (): BarCell => ({ state: 'notStarted' })),
  ]
  const fitted = fitBar(many)
  const count = (cells: BarCell[], state: string) => cells.filter(c => c.state === state).length
  expect(fitted.length).toBe(16)
  expect([count(fitted, 'done'), count(fitted, 'working'), count(fitted, 'stuck'), count(fitted, 'notStarted')]).toEqual([4, 2, 1, 9])
  expect(fitted.filter(c => c.state === 'working').map(c => c.colour)).toEqual([AGENT_COLOURS[0], AGENT_COLOURS[1]])
  // A state with one member among a hundred keeps its cell.
  const thin = fitBar([{ state: 'done' }, ...Array.from({ length: 98 }, (): BarCell => ({ state: 'notStarted' })), { state: 'working', colour: AGENT_COLOURS[0] }])
  expect([thin.length, count(thin, 'done'), count(thin, 'working')]).toEqual([16, 1, 1])
  expect(fitBar(barCells(RUN_BOARD))).toEqual(barCells(RUN_BOARD))
})

test('colours: a member keeps its slot from read to read, and two worked on at once never share one while a slot is free', async () => {
  expect(assignColours({}, ['r:a', 'r:b'])).toEqual({ 'r:a': 0, 'r:b': 1 })
  // B keeps its slot from planning to implementing; C, new, takes the lowest free one.
  expect(assignColours({ 'r:a': 0, 'r:b': 1 }, ['r:b', 'r:c'])).toEqual({ 'r:b': 1, 'r:c': 0 })
  // A member between agents keeps its slot for when it is worked on again.
  expect(assignColours({ 'r:a': 2 }, ['r:b'], ['r:a', 'r:b'])).toEqual({ 'r:b': 0, 'r:a': 2 })
  // Two saved on one slot: the second moves.
  expect(assignColours({ 'r:a': 0, 'r:b': 0 }, ['r:a', 'r:b'])).toEqual({ 'r:a': 0, 'r:b': 1 })
  // Past four at once the slots repeat.
  expect(Object.values(assignColours({}, ['r:1', 'r:2', 'r:3', 'r:4', 'r:5']))).toEqual([0, 1, 2, 3, 0])
  // A member gone from the run drops out.
  expect(assignColours({ 'r:gone': 3 }, [], [])).toEqual({})
  expect(colourKey('Run-1', 'ABCDEF1234-0000')).toBe('Run-1:abcdef12')
  expect(agentColour(5)).toBe(AGENT_COLOURS[1])
  // No agent colour is a status colour.
  expect(AGENT_COLOURS.includes(NEEDS_YOU as never)).toBe(false)
  // The bar and the agent rows read one slot: with none saved, the order among worked-on members.
  const two: Board = {
    at: 'x',
    runs: [{ ...RUN, members: [{ id: 'p', status: 'working', seat: 'planner' }, { id: 'q', status: 'working', seat: 'planner' }] }],
  }
  expect([...workingSlots(two).values()]).toEqual([0, 1])
  expect(workingSlots(two, { [colourKey(RUN_ID, 'q')]: 3 }).get(colourKey(RUN_ID, 'q'))).toBe(3)
})

test('the title and status rows: the title with the bar; alerts first, then what the agents do, the members by phase on the right', async () => {
  const feature = { itemId: 'f', title: 'Phase 4 plan', type: 'feature-implementation', label: 'in-progress' }
  expect(titleRow(RUN_BOARD, feature, null)).toEqual({ key: 'title', left: [{ text: 'Phase 4 plan', tone: 'title' }], right: barRun(barCells(RUN_BOARD)) })
  expect(titleRow(RUN_BOARD, null, null)?.left).toEqual([{ text: 'run wave-8', tone: 'dim' }])
  expect(titleRow(null, null, null)).toBe(null)
  expect(titleRow(null, feature, null)?.right).toEqual([])

  expect(statusRow(RUN_BOARD, feature, null)).toEqual({
    key: 'status',
    left: [
      { text: '1 agent stuck', tone: 'errorBold' },
      { text: ' · 1 decision for you', tone: 'needsYouBold' },
      { text: ' · 1 agent planning', tone: 'dim' },
    ],
    right: [{ text: '2 planned · 1 in progress · 1 on hold · 1 done', tone: 'dim' }],
  })
  const one = (run: BoardRun): Board => ({ at: 'x', runs: [run] })
  const left = (board: Board) => statusRow(board, feature, null).left.map(c => c.text)
  // A quiet coordinator with no seat in flight.
  expect(left(one({ ...RUN, members: [], inFlight: 0, stalled: 0 }))).toEqual(['Coordinator not responding'])
  expect(left(one({ ...RUN, status: 'paused', pauseReason: 'run-budget', members: [] }))).toEqual(['Run paused: run budget'])
  // Every agent of a Workflow finished: the coordinator's move.
  const finished: BoardRun = { ...RUN, workflowLaunched: true, inFlight: 0, stalled: 0, members: [{ id: 'x', status: 'finished' }] }
  expect(statusRow(one(finished), feature, null).left).toEqual([{ text: 'All agents finished · advance the items', tone: 'needsYouBold' }])
  // Several of the session's runs: `2 waves`, then the agents of all of them.
  const calm: BoardRun = { ...RUN, coordinatorStalled: false, members: [{ id: 'c', status: 'working', seat: 'implementer' }] }
  const calm2: BoardRun = { ...calm, id: 'r2', members: [{ id: 'e', status: 'working', seat: 'implementer' }] }
  expect(left({ at: 'x', runs: [calm, calm2] })).toEqual(['2 waves', ' · 2 agents implementing'])
  expect(left(one({ ...calm, members: [...(calm.members ?? []), { id: 'e', status: 'working', seat: 'reviewer' }] }))).toEqual(['2 agents working'])
  expect(left(one({ ...calm, members: [] }))).toEqual(['no agent working'])
  expect(activityText([{ id: 'a', status: 'working', seat: 'reviewer' }])).toBe('1 agent reviewing')
  expect(activityText([{ id: 'a', status: 'working', seat: 'test-author' }])).toBe('1 agent writing tests')
  expect(activityText([{ id: 'a', status: 'finished' }])).toBe('')
  expect(countsText({ queue: 0, work: 0, review: 0, blocked: 0, terminal: 3 }, 1)).toBe('2 done · 1 cancelled')
  expect(countsText({ queue: 0, work: 0, review: 0, blocked: 0, terminal: 0 })).toBe('')
  expect(quietFor('2026-11-07T13:48:00.000Z', '2026-11-07T14:02:30.000Z')).toBe('quiet 14m')
  expect(quietFor(undefined, '2026-11-07T14:02:30.000Z')).toBe('')

  // A focus graph: its type and phase and its blocked work; the members by phase, or a lone leaf's own phases.
  const container: FocusBoard = {
    itemId: 'f',
    counts: { queue: 1, work: 0, review: 0, blocked: 0, terminal: 1 },
    hasChildren: true,
    role: 'work',
    blocked: 2,
    container: feature,
    touched: null,
  }
  const focusOnly: Board = { at: 'x', runs: [], focus: container }
  expect(statusRow(focusOnly, feature, container)).toEqual({
    key: 'status',
    left: [
      { text: 'feature-implementation · in-progress', tone: 'dim' },
      { text: ' · 2 waiting on another item', tone: 'dim' },
    ],
    right: [{ text: '1 planned · 1 done', tone: 'dim' }],
  })
  const leafItem = { itemId: 'l', title: 'Leaf', type: 'plugin-change', label: 'in-progress' }
  const leaf: FocusBoard = { ...container, counts: { queue: 0, work: 1, review: 0, blocked: 0, terminal: 0 }, hasChildren: false, blocked: 0, container: leafItem }
  const leafRow = statusRow({ ...focusOnly, focus: leaf }, leafItem, leaf)
  expect([leafRow.left.map(c => c.text), leafRow.right.map(c => c.text).join('')]).toEqual([['plugin-change'], 'queue → ◉ work → review → done'])
  expect(titleRow({ ...focusOnly, focus: leaf }, leafItem, leaf)?.right).toEqual([])
  expect(statusRow(null, feature, null).left).toEqual([{ text: 'loading', tone: 'dim' }])
  expect(statusRow({ ...focusOnly, error: 'http 502: no JSON body' }, null, null).left.map(c => c.text)).toEqual(['board unavailable: http 502: no JSON body'])
})

test("agent rows: one per agent, the stuck first, each marker in its member's colour and blinking; three at most, then +N more", async () => {
  expect(agentRows(RUN_BOARD)).toEqual([
    {
      key: 'agent-0',
      left: [
        { text: '◉', tone: 'error' },
        { text: ' implementer · quiet 14m', tone: 'dim' },
        { text: '   B task', tone: 'text' },
      ],
      right: [],
      elastic: 2,
    },
    {
      key: 'agent-1',
      left: [
        { text: '◉', tone: 'agent', color: AGENT_COLOURS[0], blink: true, off: '○' },
        { text: ' planner', tone: 'dim' },
        { text: '   C task', tone: 'text' },
      ],
      right: [],
      elastic: 2,
    },
  ])
  // The marker takes its member's saved colour, the same as its bar cell.
  const saved = { [colourKey(RUN_ID, 'c3333333')]: 3 }
  expect(agentRows(RUN_BOARD, saved)[1]?.left[0]?.color).toBe(AGENT_COLOURS[3])
  expect(barCells(RUN_BOARD, saved).find(c => c.state === 'working')?.colour).toBe(AGENT_COLOURS[3])
  const five: Board = {
    at: 'x',
    runs: [{ ...RUN, members: ['a', 'b', 'c', 'd', 'e'].map(id => ({ id, status: 'working' as const, seat: 'implementer', title: `Task ${id}` })) }],
  }
  const rows = agentRows(five)
  expect(rows.length).toBe(3)
  expect(rows[2]?.left.map(c => c.text)).toEqual(['◉', ' implementer', '   Task c', ' · +2 more'])
  // Several runs: the run's name follows the seat.
  const twoRuns: Board = { at: 'x', runs: [{ ...RUN, members: [{ id: 'a', status: 'working', seat: 'implementer', title: 'T' }] }, { ...RUN, id: 'r2', members: [] }] }
  expect(agentRows(twoRuns)[0]?.left[1]?.text).toBe(' implementer · wave-8')
  expect(agentRows(null)).toEqual([])
})

test('every row is cut to the frame: the right side stays whole while the left gives way from its end', async () => {
  expect(bandWidth(100)).toBe(96)
  const status = statusRow(RUN_BOARD, null, null)
  expect(fitRow(status, 96).left.map(c => c.text)).toEqual(['1 agent stuck', ' · 1 decision for you'])
  expect(fitRow(status, 96).right).toEqual(status.right)
  // Too narrow for both: the right side goes.
  expect(fitRow(status, 40)).toEqual({ ...status, right: [], left: [status.left[0], status.left[1]] })
  const title = 'orchestration-mode: graph-progression band (toggleable) and visible-mode footer (director, coordinator)'
  const head = fitRow(titleRow(RUN_BOARD, { itemId: 'd', title, type: '', label: '' }, null) as Row, 96)
  expect(head.left[0]?.text.length).toBe(96 - 5 - 2)
  expect(head.left[0]?.text.endsWith('…')).toBe(true)
  expect(cellsWidth(head.right)).toBe(5)
  // An agent row's title gives way, not its seat.
  const agent = fitRow(agentRows(RUN_BOARD)[0] as Row, 30)
  expect(agent.left.map(c => c.text)).toEqual(['◉', ' implementer · quiet 14m', '   B…'])
  // The item row: the title gives way before the blocker; past a dozen cells the blocker goes.
  const item = { itemId: '6e91c19b-0000', title: 'A task with a long title that the frame truncates', type: 'feature-task', label: 'queue', blockedBy: 'd8999287' }
  expect(line3Cells(item, 96).map(c => c.text)).toEqual(['6e91c19b · feature-task · queue', '   A task with a long title that the frame…', ' · blocked by d8999287'])
  expect(line3Cells(item, 60).map(c => c.text)).toEqual(['6e91c19b · feature-task · queue', '   A task with a long title…'])
  expect(cellsWidth(line3Cells(item, 60))).toBe(59)
  // Too narrow for the cells before the elastic one: that one alone, cut.
  expect(fitCells([{ text: 'a long lead cell', tone: 'dim' }, { text: '   title', tone: 'text' }], 6, 1)).toEqual([{ text: 'title', tone: 'text' }])
})

test("a cell's style is what both renderers draw", async () => {
  expect(cellStyle({ text: '▰', tone: 'agent', color: AGENT_COLOURS[0], blink: true, off: '▱' })).toEqual({ t: '▰', o: '▱', c: AGENT_COLOURS[0] })
  expect(cellStyle({ text: 'T', tone: 'title' })).toEqual({ t: 'T', c: 'warning', b: true })
  expect(cellStyle({ text: 'x', tone: 'dim' })).toEqual({ t: 'x', d: true })
  expect(cellStyle({ text: 'x', tone: 'needsYouBold' })).toEqual({ t: 'x', c: NEEDS_YOU, b: true })
  expect(cellStyle({ text: 'x', tone: 'finished' })).toEqual({ t: 'x', c: 'subtle' })
  expect(cellStyle({ text: '⧄', tone: 'error' })).toEqual({ t: '⧄', c: 'error' })
})

test('a run is built from the list item and its frontier', async () => {
  expect(runFromList({ ...LISTED, status: 'closed' })).toBe(null)
  const listed = runFromList(LISTED)
  expect(listed?.status).toBe('open')
  expect(listed?.tokens).toBe(1_250_000)
  expect(listed?.stalled).toBe(1)
  const run = withFrontier(listed as BoardRun, FRONTIER)
  expect(run.counts).toEqual({ queue: 2, work: 1, review: 0, blocked: 1, terminal: 1 })
  expect([run.ready, run.inFlight, run.waiting, run.stalled, run.cancelled]).toEqual([1, 2, 1, 1, 0])
  expect(run.members?.length).toBe(4)
  expect(withFrontier(listed as BoardRun, { ...FRONTIER, cancelled: 1 }).cancelled).toBe(1)
  // A Workflow with no seat left: every ready member has finished its step.
  const done = withFrontier({ ...(listed as BoardRun), workflowLaunched: true }, { ...FRONTIER, inFlight: [] })
  expect(done.members?.find(m => m.id === 'a1111111')?.status).toBe('finished')
  expect(runLabel(run)).toBe('wave-8')
  expect(runLabel({ ...run, label: 'r-202611101447-6e90e4d6' })).toBe('6e90e4d6')
  expect(runLabel({ ...run, label: 'r-wave-8' })).toBe('r-wave-8')
  expect(runLabel({ ...run, label: '' })).toBe('Band demo wave')
  expect(runLabel({ ...run, label: '', title: 'A title that is far too long for one cell' })).toBe('A title that is far too…')
  expect(runLabel({ ...run, title: '', label: '' })).toBe('a51588a2')
  const finished: BoardRun = { ...run, workflowLaunched: true, inFlight: 0 }
  expect(seatsDone(finished)).toBe(true)
  expect(seatsDone({ ...finished, inFlight: 1 })).toBe(false)
  expect(seatsDone({ ...finished, workflowLaunched: false })).toBe(false)
  expect(seatsDone({ ...finished, status: 'paused' })).toBe(false)
})

test('the coordinator alert follows the director rule: idle under a Workflow or beside a live seat, never on a paused run', async () => {
  // RUN: the server flags the coordinator, and one of its two seats is live.
  expect(coordStalled(RUN)).toBe(false)
  expect(coordStalled({ ...RUN, stalled: 2 })).toBe(true)
  expect(coordStalled({ ...RUN, stalled: 2, coordinatorStalled: false })).toBe(false)
  // A launched Method A run: the Workflow coordinates it, nothing calls under the coordinator token.
  const workflow = runFromList({ ...LISTED, hints: { method: 'A', phase: 'launched' } }) as BoardRun
  expect(workflow.workflowLaunched).toBe(true)
  expect(coordStalled({ ...workflow, inFlight: 0, stalled: 0 })).toBe(false)
  expect(runFromList({ ...LISTED, hints: { method: 'A', phase: 'post-run' } })?.workflowLaunched).toBe(false)
  expect(runFromList({ ...LISTED, hints: { method: 'B', phase: 'launched' } })?.workflowLaunched).toBe(false)
  // No seat in flight at all: a quiet coordinator is stalled.
  expect(coordStalled({ ...RUN, inFlight: 0, stalled: 0 })).toBe(true)
  // A paused run is paused, never stalled.
  expect(coordStalled({ ...RUN, status: 'paused', inFlight: 0 })).toBe(false)
  // The frontier's own coordinator flag is the fresher read.
  expect(withFrontier(runFromList(LISTED) as BoardRun, { ...FRONTIER, stalled: false }).coordinatorStalled).toBe(false)
})

test('the band shows only the runs of this session, in every mode', async () => {
  const run = (id: string, label: string, scopeId?: string): BoardRun => ({ ...RUN, id, label, scopeId })
  const theirs = run('72100b56-0000', 'theirs', 'a977f05e-0000')
  const scoped = run('11111111-0000', 'scoped', 'd99e4f7a-0000')
  const coordinated = run('22222222-0000', 'coordinated')
  const background = run('33333333-0000', 'background')
  const all = [theirs, scoped, coordinated, background]
  const labels = (runs: BoardRun[]) => runs.map(r => r.label)

  expect(labels(sessionRuns(all, { watched: [], focusIds: [] }))).toEqual([])
  expect(labels(sessionRuns(all, { watched: [], focusIds: ['d99e4f7a'] }))).toEqual(['scoped'])
  // A run this session or one of its subagents opened or acted on, by id prefix either way.
  expect(labels(sessionRuns(all, { watched: ['33333333'], focusIds: [] }))).toEqual(['background'])
  // The coordinated run comes first, whatever the server's order.
  expect(labels(sessionRuns(all, { coordinatorRunId: '22222222', watched: ['33333333-0000'], focusIds: ['d99e4f7a-0000'] }))).toEqual([
    'coordinated',
    'scoped',
    'background',
  ])
  // A marker coordinator that names no run matches nothing.
  expect(labels(sessionRuns(all, { coordinatorRunId: '', watched: [''], focusIds: [] }))).toEqual([])
  expect(runFromList({ ...LISTED, scopeId: 'a977f05e-0000', rootId: 'ce064d2c-0000' })).toMatchObject({ scopeId: 'a977f05e-0000', rootId: 'ce064d2c-0000' })
  expect(runFromList(LISTED)?.scopeId).toBe(undefined)
})

test('a runs_write open or an op on a run makes it the session\'s; a read never does', async () => {
  const envelope = { ok: true, data: { results: [{ ok: true, runId: RUN_ID, coordinatorToken: 't' }, { ok: false }, { ok: true }] } }
  const input = { ops: [{ op: 'open', rootId: 'ce064d2c' }, { op: 'open', rootId: 'x' }, { op: 'pause', runId: '72100b56' }] }
  expect(namedRunIds(RUN_TOOL, input, envelope)).toEqual([RUN_ID, '72100b56'])
  expect(namedRunIds('mcp__mcp-task-orchestrator-director__runs_write', { ops: [{ op: 'close', runId: 'a51588a2' }] }, undefined)).toEqual(['a51588a2'])
  expect(namedRunIds('mcp__mcp-task-orchestrator-director__runs_read', { select: 'frontier', runId: RUN_ID }, undefined)).toEqual([])
  expect(namedRunIds(READ_TOOL, { itemId: 'd99e4f7a' }, undefined)).toEqual([])
  // Each id once, the full spelling kept, newest last, the oldest dropped past the cap.
  expect(watchRuns(['a51588a2'], [RUN_ID, 'b0b0b0b0'])).toEqual([RUN_ID, 'b0b0b0b0'])
  expect(watchRuns([RUN_ID], ['a51588a2'])).toEqual([RUN_ID])
  expect(watchRuns(['1111aaaa', '2222bbbb', '3333cccc'], ['4444dddd'], 3)).toEqual(['2222bbbb', '3333cccc', '4444dddd'])
})

/** The runs list the board reads, from `listed()`, over the item server. */
function runServer(items: ReturnType<typeof server>, listed: () => unknown[]) {
  return (hook: unknown, e: { url: string; init?: { body?: unknown } }) => {
    const body = JSON.parse(String(e.init?.body ?? '{}')) as Record<string, string>
    if (e.url.endsWith('/runs_read') && body.select === 'list') {
      const page = body.status === 'open' ? listed() : []
      return ok({ items: page, page: { total: page.length } })
    }

    return items(hook, e)
  }
}

test("another session's run never reaches this session's band; a run scoped to its focus does", async ($, on) => {
  on('ui.toast', () => ({ value: undefined }))
  on('ui.backdrop', () => ({ value: { isDrawn: true, requestId: 'backdrop' } }))
  const items = server({ 'd99e4f7a-0000': FEATURE, d99e4f7a: FEATURE, '8da6e710': TASK, '8da6e710-0000': TASK }, {}, FEATURE_LISTS)
  const listed = [
    { ...LISTED, runId: '72100b56-1824-499f-8801-4017c6cbed72', label: 'theirs', scopeId: 'a977f05e-fae5' },
    { ...LISTED, label: 'mine', scopeId: 'd99e4f7a-0000', coordinatorStalled: false },
  ]
  let runs = listed.slice(0, 1)
  on('http.fetch', runServer(items, () => runs))
  on('tool.call', () => ({ result: { ok: true, data: FEATURE }, text: '{}' }))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })

  await $.tool.call({ tool: READ_TOOL, select: 'context', itemId: 'd99e4f7a' })
  await settle()
  let band = await texts($, BAND)
  expect(band[0]).toBe('Model and effort policy')
  expect(band.some(text => /theirs/.test(text))).toBe(false)

  runs = listed
  await $.tool.call({ tool: READ_TOOL, select: 'context', itemId: '8da6e710' })
  await settle()
  band = await texts($, BAND)
  // The run is the session's now: the band shows its status (no agent works on it yet).
  expect(band.includes('no agent working')).toBe(true)
  expect(band.some(text => /theirs/.test(text))).toBe(false)
})

test("a run a subagent opened is the session's: the title row names its scope item, the status row its agents", async ($, on) => {
  on('ui.toast', () => ({ value: undefined }))
  on('ui.backdrop', () => ({ value: { isDrawn: true, requestId: 'backdrop' } }))
  const BG = 'b0b0b0b0-1111-2222-3333-444455556666'
  const items = server({ 'd99e4f7a-0000': FEATURE }, {}, FEATURE_LISTS)
  const listed = [
    { ...LISTED, runId: '72100b56-1824-499f-8801-4017c6cbed72', label: 'theirs', scopeId: 'a977f05e-fae5' },
    { ...LISTED, runId: BG, label: 'bg', scopeId: 'd99e4f7a-0000', coordinatorStalled: false },
  ]
  on('http.fetch', runServer(items, () => listed))
  on('tool.call', answerWrites(() => ({ runId: BG, label: 'bg', coordinatorToken: 't' })))
  on('ui.render', ($, e) => {
    const { Text } = $.ui.resolve(e)

    return h(Text, null, 'engine')
  })

  // A read of every run's frontier (the director running the board) owns nothing.
  await $.tool.call({ tool: 'mcp__mcp-task-orchestrator-director__runs_read', select: 'frontier', runId: BG })
  await settle()
  expect(await shown($, BAND, /run|engine/)).toBe('engine')

  await $.tool.call({ tool: RUN_TOOL, ops: [{ op: 'open', rootId: 'ce064d2c', label: 'bg' }], agentId: 'coordinator-1' })
  await settle()
  const band = await texts($, BAND)
  expect(band).toEqual(['Model and effort policy', 'no agent working'])
  // The title is a row of its own, the run a row below it.
  const ui = await $.ui.mount({ ...BAND, surface: 'desktop' })
  expect((await ui.find({ key: 'title' }))?.text).toBe('Model and effort policy')
  expect((await ui.find({ key: 'status' }))?.text).toBe('no agent working')
  await ui.unmount()
  // A short band keeps the title, then the status.
  const rowKeys = async (maxRows: number) => {
    const m = await $.ui.mount({ ...BAND, props: { ...BAND.props, maxRows }, surface: 'terminal' })
    const keys = (await m.findAll({ type: 'Box' })).map(b => b.key).filter((k): k is string => k !== undefined)
    await m.unmount()
    return keys
  }
  expect(await rowKeys(6)).toEqual(['title', 'status'])
  expect(await rowKeys(3)).toEqual(['title'])
  // A subagent's open never makes the session a coordinator.
  expect(await shown($, FOOTER, /coordinator|orchestrator/)).toBe('orchestrator')
})
