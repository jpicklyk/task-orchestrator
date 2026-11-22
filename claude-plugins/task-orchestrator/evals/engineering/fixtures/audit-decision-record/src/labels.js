export function buildLabel(order) {
  const weightGrams = order.lines.reduce((sum, l) => sum + l.weightGrams * l.qty, 0);
  return { to: order.addr.name, line1: order.addr.line1, city: order.addr.city, weightGrams };
}
