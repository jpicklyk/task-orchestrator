export function createMemoryOrderStore() {
  const orders = new Map();
  return {
    save(order) { orders.set(order.id, order); },
    get(id) { return orders.get(id) ?? null; },
    count() { return orders.size; },
  };
}
