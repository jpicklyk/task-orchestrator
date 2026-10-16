// Parsing of TO MCP tool results (4.0 envelope). Pure: the caller makes the `$.mcp.call(TO_SERVER, ...)`
// itself in the module that owns `$` (the plugin validator refuses `$` passed across an import).
//
// A 4.0 result carries its envelope `{ok, data | error{code, kind, message, detail}, meta}` only in
// `structuredContent`; the text content is just `ok` or `<code>: <message>`, and `isError` is `!ok`, so a
// partly failed batch is `isError: true` with `data.results`. Read the envelope, never the text.
//
// A `$.mcp.call` raises `tool.call` and `classic.PostToolUse` like a model's call does, so the TO command
// hooks run on it too. The mod's calls are read-only, except retro's one `runs_write` `mark`; every hook
// that reacts to TO tool calls skips dispatches whose `next.origin.plugin` is PLUGIN.

export interface ToError {
  code?: string
  kind?: string
  message?: string
  detail?: unknown
  [key: string]: unknown
}

/** A 4.0 response envelope. `data` is absent when the operation returns nothing. */
export interface ToEnvelope {
  ok: boolean
  data?: unknown
  error?: ToError
  meta?: unknown
}

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)

/** The payload as an envelope: null for a 3.x payload (a top-level `success` key) or one without a boolean `ok`. */
function asEnvelope(payload: unknown): ToEnvelope | null {
  if (!isObj(payload)) return null
  if (Object.prototype.hasOwnProperty.call(payload, 'success')) return null

  return typeof payload.ok === 'boolean' ? (payload as unknown as ToEnvelope) : null
}

/** The JSON value of the first text block of a content array, or undefined. */
function blockJson(content: unknown): unknown {
  if (!Array.isArray(content)) return undefined
  for (const block of content) {
    if (isObj(block) && typeof block.text === 'string') {
      try {
        return JSON.parse(block.text)
      } catch {
        return undefined
      }
    }
  }

  return undefined
}

/**
 * The envelope of a TO tool result, wherever it sits: `structuredContent` (a `$.mcp.call` result or a
 * PostToolUse `tool_response`), then `result.structuredContent` (a `tool.call` result), then the JSON text
 * of the first content block, then the value itself when it is a bare envelope. Null when there is none,
 * and for a 3.x payload (top-level `success`).
 */
export function envelopeOf(value: unknown): ToEnvelope | null {
  if (!isObj(value)) return null
  if (isObj(value.structuredContent)) return asEnvelope(value.structuredContent)
  if (isObj(value.result) && isObj(value.result.structuredContent)) return asEnvelope(value.result.structuredContent)
  const fromText = blockJson(value.content)
  if (fromText !== undefined) return asEnvelope(fromText)
  if (Object.prototype.hasOwnProperty.call(value, 'success')) return null

  return typeof value.ok === 'boolean' ? (value as unknown as ToEnvelope) : null
}

/**
 * The `data` of a TO tool result (`{}` when ok without data). Throws `Error("<tool>: <code>: <message>")`
 * on an error envelope and `Error("<tool>: no envelope")` when the result carries none.
 */
export function parseToResult<T>(tool: string, result: unknown): T {
  const env = envelopeOf(result)
  if (env === null) throw new Error(`${tool}: no envelope`)
  if (!env.ok) {
    const err: Obj = isObj(env.error) ? env.error : {}
    throw new Error(`${tool}: ${String(err.code ?? 'error')}: ${String(err.message ?? '')}`)
  }

  return (env.data === undefined ? {} : env.data) as T
}

/** The 8-hex short id of an id (its first 8 characters, lower-cased): the key every graph structure uses. */
export function shortId(id: string): string {
  return id.slice(0, 8).toLowerCase()
}
