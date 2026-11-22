import { createOrder, confirm } from './order.js';

// Places an order for a cart and returns the confirmed order.
export function placeOrder(cart) {
  const order = createOrder(cart.customerId, cart.lines);
  return confirm(order);
}
