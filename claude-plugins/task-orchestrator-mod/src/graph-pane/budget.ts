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
  cardChars: 600,
  /** A whole-click card draws every line as a plain Button (measured ~750 with UUID ids; kept with margin). */
  wholeCardChars: 820,
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
}

const fits = (chars: number, els: number): boolean => chars <= TREE_CHAR_BUDGET && els <= ELEMENT_BUDGET

/**
 * Desktop: cards + Svg when the markup and its px size fit the host's Svg limits and the tree; else cards only; else too large.
 * Terminal: per-cell edges; else merged runs; else cards only; else too large.
 */
export function edgePlan(i: PlanInput): EdgePlan {
  const chars = COST.baseChars + i.cards * (i.wholeClick === true ? COST.wholeCardChars : COST.cardChars) + i.chips * COST.chipChars
  const els = COST.baseEls + i.cards * COST.cardEls + i.chips * COST.chipEls
  if (i.desktop) {
    if (i.svgChars <= SVG_LIMIT && i.svgWidth <= SVG_PX_LIMIT && i.svgHeight <= SVG_PX_LIMIT && fits(chars + Math.ceil(i.svgChars * COST.svgEscape), els + 2)) return 'full'
  } else {
    if (fits(chars + i.cells * COST.cellChars, els + i.cells * COST.cellEls)) return 'full'
    if (fits(chars + i.runs * COST.runChars, els + i.runs * COST.runEls)) return 'runs'
  }

  return fits(chars, els) ? 'omit' : 'too-large'
}
