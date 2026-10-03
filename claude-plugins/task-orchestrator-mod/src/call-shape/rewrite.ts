// Pure call-shape repair for TO tool calls (T7). No `$`, so it is unit-testable: the caller reads
// the project rootId and hands it in.

export type Rewrite = { input: Record<string, unknown>; changes: string[] }

type Input = Record<string, unknown>

const absent = (v: unknown): boolean => v === undefined || v === null

/**
 * A `tags` or `type` filter of any value means the caller is looking up a specific kind of item
 * (process-global containers, trends, observations, retrospectives, proposals, personal roots),
 * which may live outside the project root; such listings stay unscoped. An explicit
 * `ancestorId: ""` also passes through untouched (the server reads blank as unscoped).
 */
function hasKindFilter(input: Input): boolean {
  return !absent(input.tags) || !absent(input.type)
}

/** Whether rule A (inject `ancestorId`) is in play for this tool and input, before the skip conditions. */
function isScopable(tool: string, input: Input): boolean {
  switch (tool) {
    case 'query_items':
      return input.operation === 'search' && input.query === undefined && input.depth !== 0
    case 'get_next_item':
    case 'get_blocked_items':
      return true
    case 'get_context':
      return input.itemId === undefined && input.mode !== 'item'
    default:
      return false
  }
}

/** True when the tool's own caller is this plugin (its `$.mcp.call` reads), which are never repaired. */
export function isOwnCall(originPlugin: string | undefined, plugin: string): boolean {
  return originPlugin === plugin
}

/**
 * Repairs one TO tool call. `tool` is the bare TO tool name. Returns the (possibly new) input and
 * one change label per applied rewrite; `input` is the same object when nothing changed.
 */
export function rewriteCall(tool: string, input: Input, rootId: string | null): Rewrite {
  const changes: string[] = []
  let out = input

  if (rootId !== null && isScopable(tool, input) && input.ancestorId === undefined && absent(input.parentId) && !hasKindFilter(input)) {
    out = { ...out, ancestorId: rootId }
    changes.push(`${tool} +ancestorId=${rootId.slice(0, 8)}`)
  }

  if (tool === 'manage_notes' && input.operation === 'upsert' && Array.isArray(input.notes)) {
    const actor = input.actor
    if (typeof actor === 'object' && actor !== null && !Array.isArray(actor)) {
      const filled: number[] = []
      const notes = input.notes.map((n: unknown, i: number) => {
        if (typeof n === 'object' && n !== null && !Array.isArray(n) && (n as Input).actor === undefined) {
          filled.push(i)

          return { ...(n as Input), actor: { ...(actor as Input) } }
        }

        return n
      })
      if (filled.length > 0) {
        out = { ...out, notes }
        changes.push(`manage_notes actor -> notes[${filled.join(',')}]`)
      }
    }
  }

  return { input: out, changes }
}
