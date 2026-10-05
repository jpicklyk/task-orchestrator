// Keyboard focus of the /to-graph pane: one focus stop per box. A whole-click box draws three Buttons
// (`open:<id>:1`, `open:<id>:2`, `open:<id>`), so Tab and the arrows would stop three times on it; the
// pane's `ui.focus` hook sends the ring to the box's state-line Button (`open:<id>`) instead, where
// Enter opens the detail. Pure, plus the two module variables the hook keeps (a hot reload resets
// them, which only makes the next move pass through).

/** The element a whole-click box line draws under: `open:<id>:<k>`. */
const LINE = /^open:(.+):(\d+)$/

/**
 * Where a proposed focus move should land, given the boxes' ids in drawn order and the element that
 * holds the ring now:
 * - a line of box X while the ring is not on X: X's state line (`open:X`), so each box is one stop;
 * - a line of box X while the ring is on `open:X` (moving backward): the previous box's state line,
 *   or the proposal itself when X is the first box;
 * - anything else, or an order that does not know X (none recorded yet, a hot reload): the proposal.
 */
export function focusTarget(order: readonly string[] | null, current: string | undefined, proposed: string | undefined): string | undefined {
  if (proposed === undefined || order === null) return proposed
  const m = LINE.exec(proposed)
  if (m === null) return proposed
  const id = m[1] as string
  const at = order.indexOf(id)
  if (at < 0) return proposed
  if (current === `open:${id}`) {
    const prev = order[at - 1]

    return prev !== undefined ? `open:${prev}` : proposed
  }
  const onX = current !== undefined && (current === `open:${id}` || LINE.exec(current)?.[1] === id)

  return onX ? proposed : `open:${id}`
}

/** The boxes' ids in drawn order, as the last render drew them; null before the first or when none drew. */
let drawnOrder: string[] | null = null
/** The element of the last `ui.focus` that resolved without a deny. */
let focused: string | undefined

/** Recorded by the render: root first, then the rows top-down, each left to right. */
export function recordFocusOrder(ids: readonly string[] | null): void {
  drawnOrder = ids === null ? null : [...ids]
}

export function focusOrder(): readonly string[] | null {
  return drawnOrder
}

/** For tests, which share this module across cases. */
export function resetFocusState(): void {
  drawnOrder = null
  focused = undefined
}

/**
 * The `ui.focus` hook body: rewrites a box-line stop to the box's single stop and remembers where the
 * ring landed. Takes no `$`.
 */
export async function redirectFocus<E extends { element?: string }, R extends { deny?: string }>(e: E, next: (e: E) => Promise<R>): Promise<R> {
  const target = focusTarget(drawnOrder, focused, e.element)
  const res = await next(target !== e.element ? { ...e, element: target } : e)
  if (res.deny === undefined) focused = target

  return res
}
