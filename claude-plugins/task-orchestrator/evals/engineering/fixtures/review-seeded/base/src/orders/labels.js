export function buildLabel(order) {
  const lbl = { to: order.addr.name, line1: order.addr.line1, city: order.addr.city };
  const wt = order.lines.reduce((sum, l) => sum + l.weightGrams * l.qty, 0);
  return { ...lbl, weightGrams: wt };
}
