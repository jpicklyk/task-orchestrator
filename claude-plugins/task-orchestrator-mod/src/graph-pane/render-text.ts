// Terminal renderer: a box-drawing tree of the containment hierarchy with blocker annotations
// (T3 a47dcd6f). Pure: a view in, lines out.
import type { GraphEdge } from '../../types'
import { gateSuffix, glyphOf, id8, kindOf, rollupText } from './shared.ts'
import type { GraphView } from './shared.ts'

/** The lines of the tree, one per node plus one `↳ blocked by` line per live incoming BLOCKS edge. */
export function renderText(view: GraphView): string[] {
  const byId = new Map(view.nodes.map(n => [n.id, n]))
  const kids = new Map<string, string[]>()
  const roots: string[] = []
  for (const n of view.nodes) {
    if (n.parentId !== null && byId.has(n.parentId)) (kids.get(n.parentId) ?? kids.set(n.parentId, []).get(n.parentId))?.push(n.id)
    else roots.push(n.id)
  }
  const incoming = new Map<string, GraphEdge[]>()
  for (const e of view.edges) if (e.type === 'BLOCKS') (incoming.get(e.to) ?? incoming.set(e.to, []).get(e.to))?.push(e)

  const lines: string[] = []
  const seen = new Set<string>()
  const visit = (id: string, prefix: string, connector: string, childPrefix: string): void => {
    const n = byId.get(id)
    if (n === undefined || seen.has(id)) return
    seen.add(id)
    const kind = kindOf(n)
    const role = kind === 'cancelled' ? 'cancelled' : n.role
    const tail = [gateSuffix(view.gates[id]), rollupText(view.rollups?.[id])].filter(s => s !== '').join(' ')
    lines.push(`${prefix}${connector}${glyphOf(n)} ${n.title} [${id8(id)}] ${role}${tail === '' ? '' : ` ${tail}`}`)

    const children = kids.get(id) ?? []
    for (const e of incoming.get(id) ?? []) {
      const blocker = byId.get(e.from)
      const info = blocker ?? view.external[e.from]
      if (info === undefined ? false : info.role === 'terminal') continue
      lines.push(`${childPrefix}${children.length > 0 ? '│  ' : '   '}↳ blocked by ${id8(e.from)}${info === undefined ? '' : ` ${info.title}`}`)
    }
    children.forEach((c, i) => {
      const last = i === children.length - 1
      visit(c, childPrefix, last ? '└─ ' : '├─ ', childPrefix + (last ? '   ' : '│  '))
    })
  }
  roots.forEach(r => visit(r, '', '', ''))

  return lines
}
