export function addLine(cart, sku, unitCents, qty = 1) {
  return { ...cart, lines: [...cart.lines, { sku, unitCents, qty }] };
}
