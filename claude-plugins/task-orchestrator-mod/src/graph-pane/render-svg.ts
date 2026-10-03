// SVG renderer for the /to-graph pane (T3 a47dcd6f). Pure: a layout plus a view in, a string out.
// The string is at most SVG_LIMIT characters (the engine's SvgProps bound). Over it, the tooltips
// are dropped; still over, the result is null and the pane falls back to the text tree.
import type { GraphNode } from '../../types'
import { GAP_X, NODE_H, NODE_W } from './layout.ts'
import type { Layout } from './layout.ts'
import { dagOf, containerIds, gateSuffix, glyphOf, id8, kindOf, rollupText, truncate } from './shared.ts'
import type { GraphView, Kind } from './shared.ts'

export const SVG_LIMIT = 131072

export type Theme = 'dark' | 'light'

interface Palette {
  bg: string
  text: string
  edge: string
  group: string
  groupText: string
  kind: Record<Kind, string>
}

const KIND: Record<Kind, string> = {
  queue: '#6b7280',
  work: '#b45309',
  review: '#2563eb',
  blocked: '#dc2626',
  terminal: '#15803d',
  cancelled: '#4b5563',
}

export const PALETTES: Record<Theme, Palette> = {
  dark: { bg: '#1e1e1e', text: '#ffffff', edge: '#9ca3af', group: '#52525b', groupText: '#d4d4d8', kind: KIND },
  light: { bg: '#ffffff', text: '#ffffff', edge: '#4b5563', group: '#a1a1aa', groupText: '#3f3f46', kind: KIND },
}

/** XML-escapes text and drops characters XML 1.0 forbids. */
export function esc(text: string): string {
  return text
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
}

const f = (n: number): string => String(Math.round(n * 10) / 10)

function tooltip(node: GraphNode, view: GraphView): string {
  const status = node.statusLabel !== undefined ? `${node.role}/${node.statusLabel}` : node.role
  const gate = view.gates[node.id]
  const parts = [node.title, `${status} [${id8(node.id)}]`]
  if (gate !== undefined && gate.missing.length > 0) parts.push(`missing: ${gate.missing.join(', ')}`)
  const roll = view.rollups?.[node.id]
  if (roll !== undefined && roll.count > 0) {
    parts.push(`+${roll.count} below: ${Object.entries(roll.byRole).map(([r, n]) => `${n} ${r}`).join(', ')}`)
  }

  return parts.join('\n')
}

function build(layout: Layout, view: GraphView, theme: Theme, withTooltips: boolean): string {
  const pal = PALETTES[theme]
  const dag = dagOf(view)
  const byId = new Map(view.nodes.map(n => [n.id, n]))
  const stubs = new Map(dag.stubs.map(s => [s.id, s]))
  const out: string[] = []
  out.push(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${layout.width}" height="${layout.height}" viewBox="0 0 ${layout.width} ${layout.height}">`,
    `<rect width="${layout.width}" height="${layout.height}" fill="${pal.bg}"/>`,
    `<defs><marker id="arr" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="${pal.edge}"/></marker></defs>`,
    `<style>text{font:11px sans-serif;fill:${pal.text}}.gl{fill:${pal.groupText}}.n:hover rect{stroke:${pal.edge};stroke-width:2}</style>`,
  )

  // Groups: a dashed frame around the placed descendants of each visible container, deepest padded least.
  const containers = containerIds(view.nodes)
  const children = new Map<string, string[]>()
  for (const n of view.nodes) {
    if (n.parentId !== null && byId.has(n.parentId)) (children.get(n.parentId) ?? children.set(n.parentId, []).get(n.parentId))?.push(n.id)
  }
  const frame = (id: string): { x0: number; y0: number; x1: number; y1: number; levels: number } | null => {
    const p = layout.pos[id]
    if (p !== undefined) return { x0: p.x, y0: p.y, x1: p.x + NODE_W, y1: p.y + NODE_H, levels: 0 }
    let acc: { x0: number; y0: number; x1: number; y1: number; levels: number } | null = null
    for (const c of children.get(id) ?? []) {
      const r = frame(c)
      if (r === null) continue
      const lv = containers.has(c) ? r.levels + 1 : 0
      acc = acc === null ? { ...r, levels: lv } : { x0: Math.min(acc.x0, r.x0), y0: Math.min(acc.y0, r.y0), x1: Math.max(acc.x1, r.x1), y1: Math.max(acc.y1, r.y1), levels: Math.max(acc.levels, lv) }
    }

    return acc
  }
  const groups = view.nodes
    .filter(n => containers.has(n.id))
    .map(n => ({ n, r: frame(n.id) }))
    .filter((g): g is { n: GraphNode; r: NonNullable<ReturnType<typeof frame>> } => g.r !== null)
    .sort((a, b) => b.r.levels - a.r.levels)
  for (const { n, r } of groups) {
    const pad = 8 + 8 * r.levels
    const x = r.x0 - pad
    const y = r.y0 - pad - 10
    out.push(
      `<g class="grp"><rect x="${f(x)}" y="${f(y)}" width="${f(r.x1 - r.x0 + 2 * pad)}" height="${f(r.y1 - r.y0 + 2 * pad + 10)}" rx="8" fill="none" stroke="${pal.group}" stroke-dasharray="4 3"/>` +
        `<text class="gl" x="${f(x + 6)}" y="${f(y + 11)}">${esc(truncate(n.title, 40))}</text></g>`,
    )
  }

  // Edges under the nodes. Back-edges and RELATES_TO draw dashed/dotted.
  const back = new Set(layout.backEdges)
  for (const e of dag.edges) {
    const a = layout.pos[e.from]
    const b = layout.pos[e.to]
    if (a === undefined || b === undefined) continue
    const relates = e.type === 'RELATES_TO'
    const isBack = back.has(`${e.from}|${e.to}`) || b.layer <= a.layer
    const x1 = a.x + NODE_W
    const y1 = a.y + NODE_H / 2
    const x2 = b.x
    const y2 = b.y + NODE_H / 2
    const mid = isBack ? x1 + GAP_X / 2 : x1 + (x2 - x1) / 2
    const d = isBack ? `M${f(x1)},${f(y1)} H${f(mid)} V${f(y2 < y1 ? y2 - NODE_H : y2 + NODE_H)} H${f(x2 - GAP_X / 2)} V${f(y2)} H${f(x2)}` : `M${f(x1)},${f(y1)} H${f(mid)} V${f(y2)} H${f(x2)}`
    const dash = relates ? ' stroke-dasharray="2 3"' : isBack ? ' stroke-dasharray="6 3"' : ''
    out.push(`<path d="${d}" fill="none" stroke="${pal.edge}"${dash}${relates ? '' : ' marker-end="url(#arr)"'}/>`)
  }

  const label = (text: string, x: number, y: number, bold = false): string => `<text x="${f(x)}" y="${f(y)}"${bold ? ' font-weight="bold"' : ''}>${esc(text)}</text>`
  for (const [id, p] of Object.entries(layout.pos)) {
    const node = byId.get(id)
    if (node !== undefined) {
      const kind = kindOf(node)
      const roll = rollupText(view.rollups?.[id])
      const sub = [id8(id), gateSuffix(view.gates[id]), roll].filter(s => s !== '').join('  ')
      out.push(
        `<g class="n">${withTooltips ? `<title>${esc(tooltip(node, view))}</title>` : ''}` +
          `<rect x="${f(p.x)}" y="${f(p.y)}" width="${NODE_W}" height="${NODE_H}" rx="6" fill="${pal.kind[kind]}"${kind === 'cancelled' ? ' opacity="0.6"' : ''}/>` +
          label(`${glyphOf(node)} ${truncate(node.title, 24)}`, p.x + 8, p.y + 19, true) +
          label(truncate(sub, 30), p.x + 8, p.y + 35) +
          '</g>',
      )
      continue
    }
    const stub = stubs.get(id)
    if (stub === undefined) continue
    out.push(
      `<g class="n">${withTooltips ? `<title>${esc(`${stub.title}\n${stub.role} [${id8(id)}] (outside this view)`)}</title>` : ''}` +
        `<rect x="${f(p.x)}" y="${f(p.y)}" width="${NODE_W}" height="${NODE_H}" rx="6" fill="none" stroke="${pal.edge}" stroke-dasharray="5 3"/>` +
        label(`${stub.label} ${id8(id)}`, p.x + 8, p.y + 19, true) +
        label(truncate(stub.title, 30), p.x + 8, p.y + 35) +
        '</g>',
    )
  }
  out.push('</svg>')

  return out.join('\n')
}

/** The SVG for a view laid out by `layout`, or null when even the tooltip-free form passes SVG_LIMIT. */
export function renderSvg(layout: Layout, view: GraphView, opts: { theme?: Theme } = {}): string | null {
  const theme = opts.theme ?? 'dark'
  const full = build(layout, view, theme, true)
  if (full.length <= SVG_LIMIT) return full
  const lean = build(layout, view, theme, false)

  return lean.length <= SVG_LIMIT ? lean : null
}
