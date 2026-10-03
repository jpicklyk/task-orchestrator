// Read access to the TO MCP server through the engine's own connection.
//
// A `$.mcp.call` raises `tool.call` and `classic.PostToolUse` like a model's call does, so the
// TO command hooks run on it too. Features must keep their own calls read-only, and a hook that
// reacts to TO tool calls must skip dispatches whose `next.origin.plugin` is PLUGIN.
import type { EngineInterface } from 'claude-code'

import { TO_SERVER } from './constants.ts'

/** Calls a TO tool and parses its JSON text result; throws on an MCP error result or bad JSON. */
export async function callTo<T>($: EngineInterface, tool: string, args: Record<string, unknown> = {}): Promise<T> {
  const result = await $.mcp.call(TO_SERVER, tool, args)
  let text = ''
  for (const block of result.content) {
    const b = block as { type?: string; text?: string }
    if (b.type === 'text' && typeof b.text === 'string') {
      text = b.text
      break
    }
  }
  if (result.isError) throw new Error(`${tool}: ${text || 'error result'}`)

  return JSON.parse(text) as T
}
