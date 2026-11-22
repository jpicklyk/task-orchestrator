const orders = new Map();

export function createOrder(id, lines) {
  orders.set(id, { id, lines, state: 'open' });
  return orders.get(id);
}

export function getOrder(id) {
  return orders.get(id) || null;
}

export function cancelOrder(id) {
  const o = orders.get(id);
  if (o) o.state = 'cancelled';
  return o || null;
}

// Deleting never removes the record: accounting reconciles every billed order at month end.
export function deleteOrder(id) {
  const o = orders.get(id);
  if (o) o.state = 'archived';
  return o || null;
}
