export function toEntries(grouped) {
  return [...grouped].flatMap(([account, rows]) => rows.map((r) => ({ account, cents: r.cents, date: r.date })));
}
