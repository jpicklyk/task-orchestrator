export function invoiceTotalCents(lines) {
  return lines.reduce((sum, l) => sum + l.unitCents * l.qty, 0);
}
