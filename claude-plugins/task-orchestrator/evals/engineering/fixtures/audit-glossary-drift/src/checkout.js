import { recordPurchase } from './purchaseLog.js';

export function checkout(cart, customer) {
  const purchase = {
    id: `pur_${Date.now()}`,
    customerId: customer.id,
    lines: cart.lines,
    totalCents: cart.lines.reduce((s, l) => s + l.unitCents * l.qty, 0),
  };
  recordPurchase(purchase);
  return purchase;
}
