import { priceFor } from './pricing.js';

export function cartTotalCents(items, customer, date = new Date()) {
  return items.reduce((sum, it) => sum + priceFor(it, customer, date) * (it.qty || 1), 0);
}
