export function groupByAccount(rows) {
  const out = new Map();
  for (const r of rows) out.set(r.account, [...(out.get(r.account) || []), r]);
  return out;
}
