// The desktop character-cell size in CSS px, from the plugin's userConfig. The edge Svg is drawn in px,
// so it only lines up with the boxes (drawn in cells) when this matches the host's real cell.
export const DEFAULT_CELL_W = 8.4
export const DEFAULT_CELL_H = 18

export interface CellSize {
  w: number
  h: number
}

const pick = (v: unknown, fallback: number, lo: number, hi: number): number =>
  typeof v === 'number' && Number.isFinite(v) && v > 0 ? Math.min(hi, Math.max(lo, v)) : fallback

/** Finite positive numbers are clamped to [4,32] x [8,64]; anything else takes the default. */
export function cellSize(options: { cellWidthPx?: unknown; cellHeightPx?: unknown } | undefined): CellSize {
  return { w: pick(options?.cellWidthPx, DEFAULT_CELL_W, 4, 32), h: pick(options?.cellHeightPx, DEFAULT_CELL_H, 8, 64) }
}
