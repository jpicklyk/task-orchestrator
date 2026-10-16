// 4.0 wire helpers of the mod (item 5dbd8275, scenario S1; AC1 of the frozen task-scope).
//
// Authored blind to the implementation: every expected value below comes from the 4.0 response envelope
// (current/docs/api-reference.md "Response envelope": `{ok, data | error{code,kind,message,detail}, meta}`,
// carried in MCP `structuredContent`; the text content is only `ok` or `<code>: <message>`), from the
// 8-hex short-id rule ("Item views": compact views carry the first 8 hex of the UUID), and from the
// tool catalog (claude-plugins/task-orchestrator/hooks/lib/tool-catalog.json: 13 tools, 7 of kind `ops`).
import { expect, test } from 'claude-code/testing'

import { TO_TOOL, TO_TOOLS, TO_WRITE_TOOLS } from '../src/shared/constants.ts'
import { envelopeOf, parseToResult, shortId } from '../src/shared/to-client.ts'

const ID = 'AbCdEf12-3456-4789-8abc-def012345678'
const OK = { ok: true, data: { items: [], page: { total: 0, truncated: false } }, meta: { reqId: 'k7f3q9ab' } }
const GATE = {
  ok: false,
  error: { code: 'gate_blocked', kind: 'permanent', message: 'required notes missing', detail: { itemId: ID } },
  meta: { reqId: 'k7f3q9ac' },
}

/** The message a call threw, or null when it returned. Avoids relying on a particular matcher. */
const thrown = (fn: () => unknown): string | null => {
  try {
    fn()

    return null
  } catch (e) {
    return e instanceof Error ? e.message : String(e)
  }
}

// --- S1: envelopeOf ---------------------------------------------------------------------

test('S1 envelopeOf: reads structuredContent', () => {
  expect(envelopeOf({ structuredContent: OK, content: [{ type: 'text', text: 'ok' }] })).toEqual(OK)
})

test('S1 envelopeOf: reads result.structuredContent (a tool.call result nests it one level down)', () => {
  expect(envelopeOf({ result: { structuredContent: GATE } })).toEqual(GATE)
})

test('S1 envelopeOf: reads a content block whose text is the envelope JSON', () => {
  expect(envelopeOf({ content: [{ type: 'text', text: JSON.stringify(OK) }] })).toEqual(OK)
})

test('S1 envelopeOf: reads a bare envelope object', () => {
  expect(envelopeOf(OK)).toEqual(OK)
  expect(envelopeOf(GATE)).toEqual(GATE)
})

test('S1 envelopeOf: structuredContent wins over the content text (source order)', () => {
  const other = { ok: true, data: { marker: 'from-text' } }
  expect(envelopeOf({ structuredContent: OK, content: [{ type: 'text', text: JSON.stringify(other) }] })).toEqual(OK)
})

test('S1 envelopeOf: a 3.x payload with a top-level success key is not an envelope', () => {
  expect(envelopeOf({ success: true, data: { items: [] } })).toBeNull()
  expect(envelopeOf({ content: [{ type: 'text', text: JSON.stringify({ success: true, data: {} }) }] })).toBeNull()
  expect(envelopeOf({ structuredContent: { success: false, error: { code: 'x' } } })).toBeNull()
})

test('S1 envelopeOf: nothing envelope-shaped gives null', () => {
  expect(envelopeOf(null)).toBeNull()
  expect(envelopeOf(undefined)).toBeNull()
  expect(envelopeOf('ok')).toBeNull()
  expect(envelopeOf({})).toBeNull()
  // the MCP text content of a 4.0 answer is only `ok`, which is not JSON
  expect(envelopeOf({ content: [{ type: 'text', text: 'ok' }] })).toBeNull()
  expect(envelopeOf({ content: [{ type: 'text', text: 'gate_blocked: required notes missing' }] })).toBeNull()
  // a bare object counts only with a boolean ok
  expect(envelopeOf({ ok: 'yes', data: {} })).toBeNull()
  expect(envelopeOf({ ok: 1 })).toBeNull()
})

// --- S1: parseToResult ------------------------------------------------------------------

test('S1 parseToResult: returns the envelope data', () => {
  const data = parseToResult<{ page: { total: number } }>('items_read', { structuredContent: OK })
  expect(data).toEqual(OK.data)
  expect(data.page.total).toBe(0)
})

test('S1 parseToResult: ok without data is an empty object', () => {
  expect(parseToResult('advance', { structuredContent: { ok: true, meta: { reqId: 'k7f3q9ad' } } })).toEqual({})
})

test('S1 parseToResult: ok:false throws "<tool>: <code>: <message>"', () => {
  expect(thrown(() => parseToResult('advance', { structuredContent: GATE }))).toBe('advance: gate_blocked: required notes missing')
})

test('S1 parseToResult: a partial_failure envelope is ok:false and throws although it carries data', () => {
  const partial = {
    ok: false,
    error: { code: 'partial_failure', kind: 'permanent', message: '1 of 2 failed' },
    data: { results: [{ ok: true }, { ok: false }], summary: { ok: 1, failed: 1 } },
  }
  expect(thrown(() => parseToResult('notes_write', { structuredContent: partial }))).toBe('notes_write: partial_failure: 1 of 2 failed')
})

test('S1 parseToResult: no envelope throws "<tool>: no envelope"', () => {
  expect(thrown(() => parseToResult('items_read', { content: [{ type: 'text', text: 'ok' }] }))).toBe('items_read: no envelope')
  expect(thrown(() => parseToResult('items_read', undefined))).toBe('items_read: no envelope')
  expect(thrown(() => parseToResult('items_read', { success: true }))).toBe('items_read: no envelope')
})

// --- S1: shortId ------------------------------------------------------------------------

test('S1 shortId: first 8 characters, lower-cased', () => {
  expect(shortId(ID)).toBe('abcdef12')
  expect(shortId('ABCDEF12')).toBe('abcdef12')
})

test('S1 shortId: an already short id is unchanged; a longer hex prefix is cut to 8', () => {
  expect(shortId('abcdef12')).toBe('abcdef12')
  expect(shortId('abcdef123456')).toBe('abcdef12')
})

test('S1 shortId: the compact id of a UUID equals the short form of the same UUID in any case', () => {
  const uuid = 'ce064d2c-7c03-4eac-ae7d-89a14c2ba273'
  expect(shortId(uuid)).toBe(shortId(uuid.toUpperCase()))
  expect(shortId(uuid)).toBe('ce064d2c')
})

// --- AC1 constants ----------------------------------------------------------------------

// Oracle: tool-catalog.json (13 tools; kind `ops` = advance, config, deps_write, docs_write, items_write,
// notes_write, runs_write; the other six are `select` tools).
const CATALOG_TOOLS = ['advance', 'config', 'deps_read', 'deps_write', 'docs_read', 'docs_write', 'items_read', 'items_write', 'notes_read', 'notes_write', 'report', 'runs_read', 'runs_write']
const CATALOG_WRITE = ['advance', 'config', 'deps_write', 'docs_write', 'items_write', 'notes_write', 'runs_write']

test('AC1 TO_TOOLS is the 13 catalog tools, sorted and frozen', () => {
  expect([...TO_TOOLS]).toEqual(CATALOG_TOOLS)
  expect([...TO_TOOLS]).toEqual([...TO_TOOLS].sort())
  expect(Object.isFrozen(TO_TOOLS)).toBe(true)
})

test('AC1 TO_WRITE_TOOLS is the 7 ops tools of the catalog, sorted, frozen and a subset of TO_TOOLS', () => {
  expect([...TO_WRITE_TOOLS]).toEqual(CATALOG_WRITE)
  expect([...TO_WRITE_TOOLS]).toEqual([...TO_WRITE_TOOLS].sort())
  expect(Object.isFrozen(TO_WRITE_TOOLS)).toBe(true)
  for (const t of TO_WRITE_TOOLS) expect(TO_TOOLS.includes(t)).toBe(true)
})

test('AC1 TO_TOOL still matches the engine spelling of every catalog tool and no foreign server', () => {
  for (const t of CATALOG_TOOLS) expect(TO_TOOL.test(`mcp__mcp-task-orchestrator__${t}`)).toBe(true)
  expect(TO_TOOL.test('mcp__plugin_task-orchestrator_task-orchestrator__advance')).toBe(true)
  expect(TO_TOOL.test('mcp__github__create_issue')).toBe(false)
  expect(TO_TOOL.test('Bash')).toBe(false)
})
