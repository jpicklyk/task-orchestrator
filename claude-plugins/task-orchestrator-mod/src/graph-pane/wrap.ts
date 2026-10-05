// Pure text wrapping for box titles: whole words where a space allows it.
export const cut = (s: string, n: number): string => (s.length > n ? `${s.slice(0, Math.max(0, n - 1))}…` : s)

/** Two title lines: break at the last space that fits in `n`; hard-cut only when one word is longer than the line. */
export function titleLines(line: string, n: number): [string, string] {
  if (line.length <= n) return [line, '']
  const at = line.lastIndexOf(' ', n)
  const first = line.slice(0, at > 0 ? at : n)

  return [first, cut(line.slice(first.length).trimStart(), n)]
}
