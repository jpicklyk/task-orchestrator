export function validateLine(line) {
  if (!line || typeof line.sku !== 'string') throw new Error('line needs a sku');
  if (!Number.isInteger(line.qty) || line.qty < 1) throw new Error('line qty must be a positive integer');
  if (!Number.isInteger(line.priceCents) || line.priceCents < 0) throw new Error('line priceCents must be a non-negative integer');
  return line;
}
