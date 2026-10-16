// Snapshot reads on the 4.0 envelope tools (item 5dbd8275, scenarios S11 and S12; AC6 of the frozen task-scope).
//
// Authored blind to the implementation. Oracles: current/docs/api-reference.md "items_read" (compact views carry
// 8-hex short ids; `get` and the dependency edges carry full UUIDs; `list`, `overview`, `history` page with
// `limit`/`cursor` and `page: {total, truncated, next?}`; `context` without an itemId is the health view with
// `stalled[]`), "deps_read" (`list` edges `{id, from, to, type, unblockAt?}`, `from` blocks `to`), "List data"
// (`next` present exactly when `truncated`), and task-scope AC6 (every id of the snapshot normalized to its short id,
// the request shapes, the 1000-item stop, one `get` per distinct external id, `<tool>: <code>: <message>` errors).
import { expect, test } from 'claude-code/testing'

import type { GraphIo } from '../src/graph-data/io.ts'
import { resetLabelState } from '../src/graph-data/labels.ts'
import { snapshot } from '../src/graph-data/snapshot.ts'
import { parseToResult } from '../src/shared/to-client.ts'

const ROOT = 'a0000000-0000-4000-8000-000000000001'
const CONFIG = `project:\n  rootId: ${ROOT}\n`
const uid = (n: number): string => `${(0xe0000000 + n).toString(16)}-0000-4000-8000-${n.toString(16).padStart(12, '0')}`
const sh = (id: string): string => id.slice(0, 8)

type Row = { id: string; parentId?: string; title: string; role: string; depth: number }
type Result = { content: { type: 'text'; text: string }[]; structuredContent: unknown; isError?: boolean }
type Call = { tool: string; args: Record<string, unknown> }

const ok$ = (data: unknown): Result => ({ content: [{ type: 'text', text: 'ok' }], structuredContent: { ok: true, data } })
const fail$ = (code: string, message: string): Result => ({
  isError: true,
  content: [{ type: 'text', text: `${code}: ${message}` }],
  structuredContent: { ok: false, error: { code, kind: 'permanent', message } },
})

const compact = (r: Row) => ({ id: sh(r.id), ...(r.parentId !== undefined ? { parentId: sh(r.parentId) } : {}), title: r.title, role: r.role, priority: 'medium', depth: r.depth, version: 1 })

/** A page of `rows` for the request's `limit` and `cursor` (`c:<offset>`), as the List data rules say. */
function page<T>(rows: T[], args: Record<string, unknown>): { items: T[]; page: { total: number; truncated: boolean; next?: string } } {
  const offset = args.cursor === undefined ? 0 : Number(String(args.cursor).slice(2))
  const limit = Number(args.limit ?? 50)
  const next = offset + limit < rows.length ? `c:${offset + limit}` : undefined

  return { items: rows.slice(offset, offset + limit), page: { total: rows.length, truncated: next !== undefined, ...(next !== undefined ? { next } : {}) } }
}

/** A GraphIo whose TO tools are `handler`; everything else is the minimum snapshot() needs. */
function ioOf(handler: (tool: string, args: Record<string, unknown>) => Result) {
  resetLabelState()
  const calls: Call[] = []
  const io: GraphIo = {
    callTool: async (tool, args) => {
      calls.push({ tool, args })

      return parseToResult(tool, handler(tool, args))
    },
    readFile: async path => {
      if (path === '.taskorchestrator/config.yaml') return CONFIG
      throw new Error(`ENOENT ${path}`)
    },
    now: async () => 1_000_000,
    after: () => ({ cancel: () => undefined }),
    every: () => ({ cancel: () => undefined }),
    sleep: async () => undefined,
    spawn: () => {
      throw new Error('no spawn')
    },
    readScope: async () => null,
    setSnapshot: async () => undefined,
    updateStatus: async () => undefined,
    envApiUrl: async () => undefined,
    envApiToken: async () => undefined,
    homeDir: async () => undefined,
  }

  return { io, calls }
}

/** Every string in a value that looks like an id (hex with dashes, 8 or more), walked through objects and arrays and object keys. */
function idLikeStrings(value: unknown, out: string[] = []): string[] {
  if (typeof value === 'string') {
    if (/^[0-9a-f]{8}(-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?$/i.test(value)) out.push(value)
  } else if (Array.isArray(value)) {
    for (const v of value) idLikeStrings(v, out)
  } else if (value !== null && typeof value === 'object') {
    for (const [k, v] of Object.entries(value)) {
      idLikeStrings(k, out)
      idLikeStrings(v, out)
    }
  }

  return out
}

// A scope S (depth 1) with children A and B, under the project root.
const S1 = uid(1)
const A = uid(2)
const B = uid(3)
const EXT = uid(4)
const EXT2 = uid(5)
const rows: Row[] = [
  { id: S1, parentId: ROOT, title: 'Scope', role: 'work', depth: 1 },
  { id: A, parentId: S1, title: 'Task A', role: 'work', depth: 2 },
  { id: B, parentId: S1, title: 'Task B', role: 'review', depth: 2 },
]

function smallWorld(over: { edges?: unknown[]; failExternal?: string[]; stalled?: unknown[]; history?: unknown[]; getAncestors?: unknown[] } = {}) {
  const gets: string[] = []
  const handler = (tool: string, args: Record<string, unknown>): Result => {
    if (tool === 'items_read' && args.select === 'list') return ok$({ ...page(rows.map(compact), args) })
    if (tool === 'items_read' && args.select === 'get') {
      const wanted = String(args.itemId)
      const ext = [EXT, EXT2].find(e => e.startsWith(wanted.toLowerCase()))
      if (ext !== undefined) {
        gets.push(ext)
        if (over.failExternal?.includes(ext)) return fail$('forbidden', 'out of scope')

        return ok$({ id: ext, title: ext === EXT ? 'Outside one' : 'Outside two', role: 'queue', depth: 1 })
      }
      const row = rows.find(r => r.id.startsWith(wanted.toLowerCase()))
      if (row === undefined) return fail$('not_found', 'no such item')
      const fields = (args.fields as string[] | undefined) ?? []

      return ok$({
        id: row.id,
        ...(row.parentId !== undefined ? { parentId: row.parentId } : {}),
        title: row.title,
        role: row.role,
        depth: row.depth,
        ...(fields.includes('ancestors') ? { ancestors: over.getAncestors ?? [{ id: sh(ROOT), title: 'Project', role: 'work', depth: 0 }] } : {}),
      })
    }
    if (tool === 'items_read' && args.select === 'context' && args.itemId !== undefined) {
      return ok$({ item: { id: String(args.itemId).slice(0, 8), title: 'x', role: 'work' }, gateStatus: { canAdvance: false, phase: 'work', missing: ['k'] }, schema: [{ key: 'k', role: 'work', required: true, filled: false }] })
    }
    if (tool === 'items_read' && args.select === 'context') {
      return ok$({ active: { items: [], page: { total: 0, truncated: false } }, blocked: { items: [], page: { total: 0, truncated: false } }, stalled: over.stalled ?? [], claimSummary: { active: 0, expired: 0, unclaimed: 0 } })
    }
    if (tool === 'items_read' && args.select === 'history') return ok$({ ...page(over.history ?? [], args) })
    if (tool === 'deps_read') {
      const mine = (over.edges ?? []).filter(e => {
        const edge = e as { from: string; to: string }

        return edge.from.startsWith(String(args.item).toLowerCase()) || edge.to.startsWith(String(args.item).toLowerCase())
      })

      return ok$({ ...page(mine, args) })
    }

    return fail$('invalid_request', `unexpected ${tool}`)
  }

  return { handler, gets }
}

// --- S11: ids are short everywhere ---------------------------------------------------------

test('S11: every id in the snapshot is an 8-hex short id, whatever form the reads and the scope argument used', async () => {
  const w = smallWorld({
    edges: [
      { id: 'e1', from: A, to: B, type: 'blocks', unblockAt: 'terminal' },
      { id: 'e2', from: EXT, to: A, type: 'relates_to' },
    ],
    stalled: [{ id: sh(A), title: 'Task A', role: 'work', missing: ['implementation-notes'] }],
    history: [],
  })
  const { io } = ioOf(w.handler)
  const s = await snapshot(io, S1.toUpperCase())
  expect(s.error).toBe(undefined)
  expect(s.scopeId).toBe(sh(S1))
  expect(s.rootId).toBe(sh(ROOT))
  expect(s.nodes.map(n => [n.id, n.parentId])).toEqual([
    [sh(S1), null],
    [sh(A), sh(S1)],
    [sh(B), sh(S1)],
  ])
  expect(s.edges).toContainEqual({ from: sh(A), to: sh(B), type: 'BLOCKS', unblockAt: 'terminal' })
  expect(s.edges).toContainEqual({ from: sh(EXT), to: sh(A), type: 'RELATES_TO' })
  expect(Object.keys(s.external)).toEqual([sh(EXT)])
  expect(Object.keys(s.gates).sort()).toEqual([sh(S1), sh(A), sh(B)].sort())
  expect(s.stalled).toEqual({ [sh(A)]: ['implementation-notes'] })
  for (const found of idLikeStrings({ ...s, takenAt: 0 })) expect(found).toMatch(/^[0-9a-f]{8}$/)
})

test('S11: the trail is the get ancestors root first, then the scope itself, all by short id', async () => {
  const w = smallWorld({ getAncestors: [{ id: sh(ROOT), title: 'Project', role: 'work', depth: 0 }, { id: sh(uid(77)), title: 'Container', role: 'work', depth: 0 }] })
  const { io, calls } = ioOf(w.handler)
  const s = await snapshot(io, S1)
  expect(s.trail).toEqual([
    { id: sh(ROOT), title: 'Project' },
    { id: sh(uid(77)), title: 'Container' },
    { id: sh(S1), title: 'Scope' },
  ])
  const trailGet = calls.find(c => c.tool === 'items_read' && c.args.select === 'get' && ((c.args.fields as string[] | undefined) ?? []).includes('ancestors'))
  expect(trailGet?.args.itemId).toBeDefined()
  expect(sh(String(trailGet?.args.itemId))).toBe(sh(S1))
})

test('S11: subtree pages follow page.next with limit 100 and stop after 1000 items', async () => {
  const many: Row[] = [{ id: S1, parentId: ROOT, title: 'Scope', role: 'work', depth: 1 }]
  for (let i = 0; i < 1199; i++) many.push({ id: uid(1000 + i), parentId: S1, title: `Child ${i}`, role: 'queue', depth: 2 })
  const handler = (tool: string, args: Record<string, unknown>): Result => {
    if (tool === 'items_read' && args.select === 'list') return ok$(page(many.map(compact), args))
    if (tool === 'items_read' && args.select === 'get') return ok$({ id: S1, title: 'Scope', role: 'work', depth: 1, ancestors: [] })
    if (tool === 'items_read') return ok$({ item: {}, active: { items: [], page: { total: 0, truncated: false } }, stalled: [], ...page([], args) })
    return ok$({ ...page([], args) })
  }
  const { io, calls } = ioOf(handler)
  const s = await snapshot(io, S1)
  const lists = calls.filter(c => c.tool === 'items_read' && c.args.select === 'list')
  expect(lists).toHaveLength(10)
  expect(lists.map(c => c.args.cursor)).toEqual([undefined, ...Array.from({ length: 9 }, (_, i) => `c:${(i + 1) * 100}`)])
  expect(lists.every(c => c.args.limit === 100)).toBe(true)
  expect(s.truncated).toBe(true)
  expect(s.nodes).toHaveLength(150)
})

test('S11: a list that ends early (no next) is not read further', async () => {
  const w = smallWorld()
  const { io, calls } = ioOf(w.handler)
  await snapshot(io, S1)
  expect(calls.filter(c => c.tool === 'items_read' && c.args.select === 'list')).toHaveLength(1)
})

test('S11: edges page 200 at a time following next; each edge is kept once', async () => {
  const outside: string[] = []
  const edges: unknown[] = []
  for (let i = 0; i < 250; i++) {
    const ext = uid(5000 + i)
    outside.push(ext)
    edges.push({ id: `edge-${i}`, from: A, to: ext, type: 'relates_to' })
  }
  const w = smallWorld({ edges })
  const base = w.handler
  const handler = (tool: string, args: Record<string, unknown>): Result => {
    if (tool === 'items_read' && args.select === 'get') {
      const ext = outside.find(e => e.startsWith(String(args.itemId).toLowerCase()))
      if (ext !== undefined) return ok$({ id: ext, title: `Outside ${ext.slice(0, 8)}`, role: 'queue', depth: 1 })
    }

    return base(tool, args)
  }
  const { io, calls } = ioOf(handler)
  const s = await snapshot(io, S1)
  expect(s.error).toBe(undefined)
  const forA = calls.filter(c => c.tool === 'deps_read' && sh(String(c.args.item)) === sh(A))
  expect(forA).toHaveLength(2)
  expect(forA[0]?.args).toMatchObject({ select: 'list', direction: 'all', limit: 200 })
  expect(forA[0]?.args.cursor).toBeUndefined()
  expect(forA[1]?.args.cursor).toBe('c:200')
  expect(s.edges).toHaveLength(250)
  expect(Object.keys(s.external)).toHaveLength(250)
})

test('S11: one get per distinct external id, however many edges end there', async () => {
  const w = smallWorld({
    edges: [
      { id: 'e1', from: EXT, to: A, type: 'blocks' },
      { id: 'e2', from: EXT, to: B, type: 'blocks' },
      { id: 'e3', from: A, to: EXT2, type: 'relates_to' },
      { id: 'e4', from: B, to: EXT2, type: 'relates_to' },
    ],
  })
  const { io } = ioOf(w.handler)
  const s = await snapshot(io, S1)
  expect(w.gets.sort()).toEqual([EXT, EXT2].sort())
  expect(s.external).toEqual({ [sh(EXT)]: { title: 'Outside one', role: 'queue' }, [sh(EXT2)]: { title: 'Outside two', role: 'queue' } })
})

test('S11: a failed external get leaves no external entry and keeps the edge', async () => {
  const w = smallWorld({ edges: [{ id: 'e1', from: EXT, to: A, type: 'blocks' }], failExternal: [EXT] })
  const { io } = ioOf(w.handler)
  const s = await snapshot(io, S1)
  expect(s.external[sh(EXT)]).toBeUndefined()
  expect(s.edges).toContainEqual({ from: sh(EXT), to: sh(A), type: 'BLOCKS' })
  expect(s.nodes).toHaveLength(3)
})

test('S11: a failed subtree call puts "<tool>: <code>: <message>" into error and returns no nodes', async () => {
  const { io } = ioOf((tool, args) => (args.select === 'list' ? fail$('forbidden', 'out of scope') : ok$({})))
  const s = await snapshot(io, S1)
  expect(s.nodes).toEqual([])
  expect(s.error).toContain('items_read: forbidden: out of scope')
})

test('S11: a failed dependency call keeps the nodes and names the call in error', async () => {
  const base = smallWorld()
  const { io } = ioOf((tool, args) => (tool === 'deps_read' ? fail$('unavailable', 'database is busy') : base.handler(tool, args)))
  const s = await snapshot(io, S1)
  expect(s.nodes).toHaveLength(3)
  expect(s.error).toContain('deps_read: unavailable: database is busy')
})

test('S11: the project-root overview takes truncated from page.truncated', async () => {
  const children = Array.from({ length: 100 }, (_, i) => ({ ...compact({ id: uid(300 + i), parentId: ROOT, title: `C${i}`, role: 'work', depth: 1 }), childCounts: { work: 1 } }))
  for (const truncated of [true, false]) {
    const { io, calls } = ioOf(() => ok$({ anchor: { id: sh(ROOT), title: 'Project' }, items: children, page: { total: truncated ? 250 : 100, truncated, ...(truncated ? { next: 'c:100' } : {}) } }))
    const s = await snapshot(io, null)
    expect(s.overview).toBe(true)
    expect(s.truncated).toBe(truncated)
    expect(calls).toHaveLength(1)
    expect(calls[0]?.tool).toBe('items_read')
    expect(calls[0]?.args).toMatchObject({ select: 'overview', limit: 100 })
    expect(sh(String(calls[0]?.args.anchorId))).toBe(sh(ROOT))
  }
})

// --- S12: stall detection from the context health view and the history ---------------------

test('S12: stall reads - one scoped health context and one history page of 200 since takenAt minus 30 minutes', async () => {
  const w = smallWorld({ stalled: [{ id: sh(A), title: 'Task A', role: 'work', missing: ['k'] }] })
  const { io, calls } = ioOf(w.handler)
  await snapshot(io, S1)
  const health = calls.filter(c => c.tool === 'items_read' && c.args.select === 'context' && c.args.itemId === undefined)
  const history = calls.filter(c => c.tool === 'items_read' && c.args.select === 'history')
  expect(health).toHaveLength(1)
  expect(sh(String(health[0]?.args.ancestorId))).toBe(sh(S1))
  expect(health[0]?.args.limit).toBe(200)
  expect(history).toHaveLength(1)
  expect(history[0]?.args.limit).toBe(200)
  expect(history[0]?.args.since).toBe(new Date(1_000_000 - 30 * 60_000).toISOString())
})

test('S12: a listed work item with no recent transition is stalled; one that moved 10 minutes ago is not and sets stallDueAt', async () => {
  const w = smallWorld({
    stalled: [
      { id: sh(A), title: 'Task A', role: 'work', missing: ['implementation-notes'] },
      { id: sh(B), title: 'Task B', role: 'review', missing: ['review-checklist'] },
    ],
    history: [{ seq: 1_000_000_000_001, id: 'ev1', itemId: B, occurredAt: new Date(1_000_000 - 10 * 60_000).toISOString(), trigger: 'start', fromRole: 'work', toRole: 'review' }],
  })
  const { io } = ioOf(w.handler)
  const s = await snapshot(io, S1)
  expect(s.stalled).toEqual({ [sh(A)]: ['implementation-notes'] })
  expect(s.stallDueAt).toBe(1_000_000 - 10 * 60_000 + 30 * 60_000)
})

test('S12: a full history page of 200 rows gives no stalls', async () => {
  const history = Array.from({ length: 200 }, (_, i) => ({ seq: 1_000_000_000_000 + i, id: `ev${i}`, itemId: uid(9000 + i), occurredAt: new Date(1_000_000 - 60_000).toISOString(), trigger: 'start', fromRole: 'queue', toRole: 'work' }))
  const w = smallWorld({ stalled: [{ id: sh(A), title: 'Task A', role: 'work', missing: ['k'] }], history })
  const { io } = ioOf(w.handler)
  const s = await snapshot(io, S1)
  expect(s.stalled).toEqual({})
})

test('S12: history rows carry full UUIDs and still match the short ids of the listed items', async () => {
  const w = smallWorld({
    stalled: [{ id: sh(A), title: 'Task A', role: 'work', missing: ['k'] }],
    history: [{ seq: 1_000_000_000_001, id: 'ev1', itemId: A.toUpperCase(), occurredAt: new Date(1_000_000 - 5 * 60_000).toISOString(), trigger: 'start', fromRole: 'queue', toRole: 'work' }],
  })
  const { io } = ioOf(w.handler)
  const s = await snapshot(io, S1)
  // A moved 5 minutes ago: not stalled yet, due 25 minutes from now.
  expect(s.stalled).toEqual({})
  expect(s.stallDueAt).toBe(1_000_000 - 5 * 60_000 + 30 * 60_000)
})

