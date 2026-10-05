// Parsing of TO MCP tool results. Pure: the caller makes the `$.mcp.call(TO_SERVER, ...)` itself in
// the module that owns `$` (the plugin validator refuses `$` passed across an import).
//
// A `$.mcp.call` raises `tool.call` and `classic.PostToolUse` like a model's call does, so the
// TO command hooks run on it too. Keep the mod's own calls read-only, and skip dispatches whose
// `next.origin.plugin` is PLUGIN in any hook that reacts to TO tool calls.

/** The JSON text result of a TO tool call, parsed; throws on an MCP error result or bad JSON. */
export function parseToResult<T>(tool: string, result: { content: readonly unknown[]; isError?: boolean }): T {
  let text = ''
  for (const block of result.content) {
    const b = block as { type?: string; text?: string }
    if (b.type === 'text' && typeof b.text === 'string') {
      text = b.text
      break
    }
  }
  if (result.isError === true) throw new Error(`${tool}: ${text || 'error result'}`)

  return JSON.parse(text) as T
}
