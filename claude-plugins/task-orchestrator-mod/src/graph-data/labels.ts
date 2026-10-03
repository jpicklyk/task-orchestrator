// Plan labels (properties.planLabel) for graph nodes. List mode does not carry properties, so each
// label costs one read-only `query_items get`, cached per id (null: fetched, no label).
import type { GraphNode } from '../../types'
import type { GraphIo } from './io.ts'

const MAX_LABEL = 12
const cache = new Map<string, string | null>()

/** Forgets every cached label; the next snapshot re-reads them. */
export function invalidateLabels(): void {
  cache.clear()
}

/** For tests, which share this module across cases. */
export function resetLabelState(): void {
  cache.clear()
}

/** The label in a `get` result's `properties` (a JSON string; an object is tolerated), or null. */
export function parsePlanLabel(raw: unknown): string | null {
  const item = typeof raw === 'object' && raw !== null ? (raw as { properties?: unknown }) : {}
  let props: unknown = item.properties
  if (typeof props === 'string') {
    try {
      props = JSON.parse(props)
    } catch {
      return null
    }
  }
  if (typeof props !== 'object' || props === null) return null
  const label = (props as { planLabel?: unknown }).planLabel
  if (typeof label !== 'string') return null
  const trimmed = label.trim()

  return trimmed.length >= 1 && trimmed.length <= MAX_LABEL ? trimmed : null
}

/** Fetches the labels of the nodes not yet cached (via `run`, the caller's bounded fan-out) and returns the nodes with `planLabel` set where there is one. A failed get is swallowed and retried next time. */
export async function fetchLabels(
  io: GraphIo,
  nodes: readonly GraphNode[],
  run: (ids: readonly string[], fn: (id: string) => Promise<void>) => Promise<void>,
): Promise<GraphNode[]> {
  const missing = nodes.filter(n => !cache.has(n.id)).map(n => n.id)
  await run(missing, async id => {
    try {
      cache.set(id, parsePlanLabel(await io.callTool('query_items', { operation: 'get', itemId: id })))
    } catch {
      // not cached: the next snapshot retries
    }
  })

  return nodes.map(n => {
    const label = cache.get(n.id)

    return label ? { ...n, planLabel: label } : n
  })
}
