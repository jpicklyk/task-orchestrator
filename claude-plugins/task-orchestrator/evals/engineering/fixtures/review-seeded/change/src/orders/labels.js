export function buildLabel(order) {
  const shippingLabel = { to: order.addr.name, line1: order.addr.line1, city: order.addr.city };
  const totalWeightGrams = order.lines.reduce((sum, l) => sum + l.weightGrams * l.qty, 0);
  return { ...shippingLabel, weightGrams: totalWeightGrams };
}
