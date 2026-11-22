export function createInvoice(customer, lines, taxRateBps = 0, currency = 'USD') {
  const subtotalCents = lines.reduce((sum, l) => sum + l.unitCents * l.qty, 0);
  const taxCents = Math.round((subtotalCents * taxRateBps) / 10000);
  return { customer, lines, currency, subtotalCents, taxCents, totalCents: subtotalCents + taxCents };
}
