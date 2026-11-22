export function lineTotal(line, discountPercent = 0) {
  if (line === null || line === undefined) return 0;
  if (typeof line.qty !== 'number' || typeof line.priceCents !== 'number') return 0;
  if (line.qty < 0) return 0;
  const gross = line.priceCents * line.qty;
  return gross - Math.round((gross * discountPercent) / 100);
}
