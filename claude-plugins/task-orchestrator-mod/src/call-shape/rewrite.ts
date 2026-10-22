// Pure call-shape repair for TO tool calls (T7, 4.0 set). No `$`, so it is unit-testable: the caller
// reads the project rootId and hands it in. The engine's reserved keys of a `tool.call` event (`tool`,
// `tool_use_id`, `agentId`, `consent`) always stay at the top level and are never moved or removed.
//
// Rules, each applied at most once per call and labelled `<tool> <rule>`:
//   R1  items_read select list, next, blocked, or context without itemId: inject ancestorId=<rootId>
//       when ancestorId is absent (undefined), parentId absent or null, there is no tags/type/traits filter, a list
//       has depth !== 0, and a root id resolved.
//   R2  items_read ancestorId: "" (the opt-out) is removed and nothing is injected.
//   W1  a call with a top-level `op` string and no `ops` becomes {ops: [{...}], atomic?, seatToken?}.
//   W2  advance transitions[] / notes_write notes[] (only when ops is absent) become ops[], each
//       element given op 'transition' / 'upsert' when it has none.
//   W3  a top-level actor and an actor on every ops element are deleted (4.0 refuses a body actor).
//   W4  items_write create/update ops: properties.traits moves to the op's traits (dropped when the op
//       already has traits); an emptied properties is deleted.
// No missing-`type` repair: a guessed type silently mis-schemas the item, and the server's
// schema_violation already lists the available types.

export type Rewrite = { input: Record<string, unknown>; changes: string[] }

type Input = Record<string, unknown>

const absent = (v: unknown): boolean => v === undefined || v === null

/** Keys of a `tool.call` event the engine owns: a rewrite never moves them into an op. */
const RESERVED: readonly string[] = ['tool', 'tool_use_id', 'agentId', 'consent']
const isObj = (v: unknown): v is Input => typeof v === 'object' && v !== null && !Array.isArray(v)

/** A tags, type or traits filter of any value: the caller wants a kind of item that may live outside the project root. */
function hasKindFilter(input: Input): boolean {
  return !absent(input.tags) || !absent(input.type) || !absent(input.traits)
}

/** Whether R1 (inject `ancestorId`) is in play for this items_read input, before the skip conditions. */
function isScopable(input: Input): boolean {
  switch (input.select) {
    case 'list':
      return input.depth !== 0
    case 'next':
    case 'blocked':
      return true
    case 'context':
      return absent(input.itemId)
    default:
      return false
  }
}

/** True when the tool's own caller is this plugin (its `$.mcp.call` reads), which are never repaired. */
export function isOwnCall(originPlugin: string | undefined, plugin: string): boolean {
  return originPlugin === plugin
}

/** W4 on one op: the op itself when nothing moves. */
function moveTraits(op: Input): Input {
  if ((op.op !== 'create' && op.op !== 'update') || !isObj(op.properties) || !('traits' in op.properties)) return op
  const { traits, ...rest } = op.properties
  const out: Input = { ...op }
  if (out.traits === undefined) out.traits = traits
  if (Object.keys(rest).length > 0) out.properties = rest
  else delete out.properties

  return out
}

/**
 * Repairs one TO tool call. `tool` is the bare TO tool name, `input` the call (reserved keys allowed). Returns
 * the (possibly new) input and one change label per applied rule; `input` is the SAME object when
 * nothing changed.
 */
export function rewriteCall(tool: string, input: Input, rootId: string | null): Rewrite {
  const changes: string[] = []
  let out = input

  if (tool === 'items_read') {
    if (out.ancestorId === '') {
      const { ancestorId: _blank, ...rest } = out
      out = rest
      changes.push(`${tool} R2`)
    } else if (rootId !== null && isScopable(out) && out.ancestorId === undefined && absent(out.parentId) && !hasKindFilter(out)) {
      out = { ...out, ancestorId: rootId }
      changes.push(`${tool} R1`)
    }
  }

  if (typeof out.op === 'string' && out.ops === undefined) {
    const top: Input = {}
    const element: Input = {}
    for (const [k, v] of Object.entries(out)) (k === 'atomic' || k === 'seatToken' || RESERVED.includes(k) ? top : element)[k] = v
    out = { ops: [element], ...top }
    changes.push(`${tool} W1`)
  }

  const legacyKey = tool === 'advance' ? 'transitions' : tool === 'notes_write' ? 'notes' : null
  if (legacyKey !== null && out.ops === undefined && Array.isArray(out[legacyKey])) {
    const op = tool === 'advance' ? 'transition' : 'upsert'
    const { [legacyKey]: list, ...rest } = out
    out = { ...rest, ops: (list as unknown[]).map(el => (isObj(el) && el.op === undefined ? { op, ...el } : el)) }
    changes.push(`${tool} W2`)
  }

  const opsHaveActor = Array.isArray(out.ops) && out.ops.some(el => isObj(el) && 'actor' in el)
  if ('actor' in out || opsHaveActor) {
    const { actor: _actor, ...rest } = out
    out = rest
    if (Array.isArray(out.ops)) {
      out = {
        ...out,
        ops: out.ops.map(el => {
          if (!isObj(el) || !('actor' in el)) return el
          const { actor: _elActor, ...elRest } = el

          return elRest
        }),
      }
    }
    changes.push(`${tool} W3`)
  }

  if (tool === 'items_write' && Array.isArray(out.ops)) {
    const moved = out.ops.map(el => (isObj(el) ? moveTraits(el) : el))
    if (moved.some((el, i) => el !== (out.ops as unknown[])[i])) {
      out = { ...out, ops: moved }
      changes.push(`${tool} W4`)
    }
  }

  return { input: out, changes }
}
