// The desktop edge layer: one Svg drawn in px behind the boxes (markup of the approved probe).
import type { CellSize } from './cell.ts'
import type { Route, RouteKind } from './route.ts'

/** The Svg source limit of the host (SvgProps.source). */
export const SVG_LIMIT = 131072
/** Svg width and height are CSS px, at most this (the host refuses the whole tree past it; found live in tests). */
export const SVG_PX_LIMIT = 4096

const STYLE: Record<RouteKind, string> = {
  crit: 'stroke="#a855f7" stroke-width="3"',
  open: 'stroke="#f59e0b" stroke-width="2" stroke-dasharray="6 3"',
  done: 'stroke="#9ca3af" stroke-width="1.2" opacity="0.55"',
  contain: 'stroke="#6b7280" stroke-width="1" stroke-dasharray="2 3" opacity="0.7"',
}
const MARKER: Record<RouteKind, string> = { crit: ' marker-end="url(#ac)"', open: ' marker-end="url(#ao)"', done: ' marker-end="url(#ad)"', contain: '' }

const DEFS =
  '<defs><marker id="ad" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" markerUnits="userSpaceOnUse" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#9ca3af"/></marker>' +
  '<marker id="ao" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="9" markerHeight="9" markerUnits="userSpaceOnUse" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#f59e0b"/></marker>'
/** The critical path's arrowhead, written only when a critical edge is drawn. */
const CRIT_MARKER =
  '<marker id="ac" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="10" markerHeight="10" markerUnits="userSpaceOnUse" orient="auto"><path d="M0,0 L10,5 L0,10 z" fill="#a855f7"/></marker>'

/** A cell-unit length as CSS px, to one decimal (the probe's rounding). */
export const px = (cells: number, size: number): number => Number((cells * size).toFixed(1))

export function edgeSvg(rs: readonly Route[], cell: CellSize, width: number, height: number): string {
  const fx = (x: number): string => (x * cell.w).toFixed(1)
  const fy = (y: number): string => (y * cell.h).toFixed(1)
  const paths = rs.map(r => {
    const [x0, y0] = r.pts[0] as [number, number]
    let d = `M${fx(x0)} ${fy(y0)}`
    for (let i = 1; i < r.pts.length; i++) {
      const [x, y] = r.pts[i] as [number, number]
      d += i % 2 === 1 ? ` V${fy(y)}` : ` H${fx(x)}`
    }

    return `<path d="${d}" fill="none" ${STYLE[r.kind]}${r.head ? MARKER[r.kind] : ''}/>`
  })

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${px(width, cell.w)}" height="${px(height, cell.h)}">${DEFS}${rs.some(r => r.kind === 'crit') ? CRIT_MARKER : ''}</defs>${paths.join('')}</svg>`
}
