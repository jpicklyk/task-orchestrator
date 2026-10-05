// What the tree may cost: the host binds trees at 20,000 nodes / 32 deep / 100,000 serialised chars, so
// the pane picks the richest edge drawing that fits and degrades in steps. The unit costs below were
// MEASURED with JSON.stringify(await ui.drawn()) on the 150-card fixture (tests G20); re-measure after
// changing the card or cell markup.
import { SVG_LIMIT, SVG_PX_LIMIT } from './svg.ts'

export const ELEMENT_BUDGET = 18000
export const TREE_CHAR_BUDGET = 90000

export const COST = {
  baseChars: 3500,
  baseEls: 40,
  /** Measured ~640-655 with real 36-char UUID ids (the 9-char test ids gave 600); kept with margin. */
  cardChars: 700,
  /**
   * A whole-click card draws every line as a plain Button: measured 767-847 with UUID ids, plus ~10 for
   * press-handle numbers that gain digits across redraws over a long session.
   */
  wholeCardChars: 880,
  /** The detail panel: fixed frame and actions, plus its text (see detailChars). */
  detailBaseChars: 1200,
  /** One breadcrumb crumb: Button or Text, separator, keys. */
  crumbChars: 220,
  cardEls: 7,
  chipChars: 330,
  chipEls: 4,
  cellChars: 195,
  cellEls: 3,
  runChars: 260,
  runEls: 3,
  /** JSON escapes the quotes inside the Svg markup. */
  svgEscape: 1.12,
}

export type EdgePlan = 'full' | 'runs' | 'omit' | 'too-large'

export interface PlanInput {
  desktop: boolean
  cards: number
  /** Every line of a card is a Button (see WHOLE_CLICK_MAX), which costs more per card. */
  wholeClick?: boolean
  chips: number
  cells: number
  runs: number
  svgChars: number
  /** The Svg's size in CSS px (the host refuses either side over SVG_PX_LIMIT). */
  svgWidth: number
  svgHeight: number
  /**
   * The detail panel and breadcrumb (see extrasChars). Charged to the edge drawing only: opening the
   * detail may drop the edges, but never refuses the boxes (the card cost has room for it there).
   */
  extraChars?: number
}

/** What the detail panel and breadcrumb add to the tree. */
export function extrasChars(detailLines: readonly string[] | null, crumbTitles: readonly string[]): number {
  const detail = detailLines === null ? 0 : COST.detailBaseChars + detailLines.reduce((n, l) => n + l.length + 60, 0)

  return detail + crumbTitles.reduce((n, t) => n + t.length + COST.crumbChars, 0)
}

const fits = (chars: number, els: number): boolean => chars <= TREE_CHAR_BUDGET && els <= ELEMENT_BUDGET

/**
 * Desktop: cards + Svg when the markup and its px size fit the host's Svg limits and the tree; else cards only; else too large.
 * Terminal: per-cell edges; else merged runs; else cards only; else too large.
 */
export function edgePlan(i: PlanInput): EdgePlan {
  const chars = COST.baseChars + i.cards * (i.wholeClick === true ? COST.wholeCardChars : COST.cardChars) + i.chips * COST.chipChars
  const els = COST.baseEls + i.cards * COST.cardEls + i.chips * COST.chipEls
  const withExtras = chars + (i.extraChars ?? 0)
  if (i.desktop) {
    if (i.svgChars <= SVG_LIMIT && i.svgWidth <= SVG_PX_LIMIT && i.svgHeight <= SVG_PX_LIMIT && fits(withExtras + Math.ceil(i.svgChars * COST.svgEscape), els + 2)) return 'full'
  } else {
    if (fits(withExtras + i.cells * COST.cellChars, els + i.cells * COST.cellEls)) return 'full'
    if (fits(withExtras + i.runs * COST.runChars, els + i.runs * COST.runEls)) return 'runs'
  }

  return fits(chars, els) ? 'omit' : 'too-large'
}
