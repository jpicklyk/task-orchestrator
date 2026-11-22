const orders = [];

export function createOrder(customerId, lines) {
  const order = { id: `ord_${orders.length + 1}`, customerId, lines, createdAt: new Date().toISOString() };
  orders.push(order);
  return order;
}

export function ordersForCustomer(customerId) {
  return orders.filter((o) => o.customerId === customerId);
}
