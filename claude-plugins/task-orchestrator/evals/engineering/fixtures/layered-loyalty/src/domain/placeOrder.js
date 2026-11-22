// The order store is passed in: see src/infra/memoryOrderStore.js for the implementation.
export function placeOrder({ orderStore }, customerId, lines) {
  const totalCents = lines.reduce((sum, l) => sum + l.unitCents * l.qty, 0);
  const order = { id: `ord_${orderStore.count() + 1}`, customerId, lines, totalCents };
  orderStore.save(order);
  return order;
}
