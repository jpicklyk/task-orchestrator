// The band's surface module: while anything in the band blinks (a bar cell or the marker of a member an agent works
// on), the hooks module (indicator.ts) hands the whole band to this `Client`, so every blinking cell turns on one
// timer, in step, on the surface's own frame clock: no hook run, no board read, no `$.state` write per beat. A redraw
// with new rows keeps the instance and its timer. It draws exactly the styles it is handed (`cellStyle` in model.ts)
// and imports nothing but types: it runs on the drawing thread.
import type { ClientModule } from 'claude-code'

/** One cell: text, the off-beat text of a blinking cell, colour, bold, dim. */
type Style = { t: string; o?: string; c?: string; b?: true; d?: true }
/** One row: the left cells (cut at the edge), the right cells (kept whole). */
type BandRow = { key: string; left: Style[]; right: Style[] }
type BandProps = { rows: BandRow[]; desktop?: boolean }

/** The blink's half period: blinking cells are lit this long, then off this long. */
const BLINK_MS = 600

const Band: ClientModule<BandProps, { lit: boolean }> = (props, surface) => {
  const { Box, Text } = surface.elements
  if (surface.state === undefined) {
    let lit = true
    surface.every(BLINK_MS, () => {
      lit = !lit
      surface.setState({ lit })
    })
    surface.setState({ lit })
  }
  const lit = surface.state?.lit ?? true
  const cell = (s: Style) =>
    Text({
      ...(s.c !== undefined ? { color: s.c } : {}),
      ...(s.b === true ? { bold: true } : {}),
      ...(s.d === true ? { dimColor: true } : {}),
      children: lit || s.o === undefined ? s.t : s.o,
    })
  const rows = Array.isArray(props?.rows) ? props.rows : []

  return Box({
    flexDirection: 'column',
    children: rows.map(row =>
      // The right side (the bar, the counts) never shrinks; the left gives way (indicator.ts draws the same).
      Box({
        key: row.key,
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'flex-start',
        gap: 1,
        overflow: 'hidden',
        children: [
          Box({ flexGrow: 1, flexShrink: 1, minWidth: 0, children: [Text({ wrap: props.desktop === true ? 'truncate-end' : 'truncate', children: row.left.map(cell) })] }),
          ...(row.right.length > 0 ? [Box({ flexShrink: 0, children: [Text({ wrap: 'truncate', children: row.right.map(cell) })] })] : []),
        ],
      }),
    ),
  })
}

export default Band
