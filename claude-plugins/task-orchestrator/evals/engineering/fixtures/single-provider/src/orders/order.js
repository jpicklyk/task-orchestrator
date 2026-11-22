export function createOrder(customerId, lines) {
  if (lines.length === 0) throw new Error('an order needs at least one line');
  const totalCents = lines.reduce((sum, l) => sum + l.unitCents * l.qty, 0);
  return { id: `ord_${Date.now()}`, customerId, lines, totalCents, status: 'pending' };
}

export function confirm(order) {
  return { ...order, status: 'confirmed' };
}
