import { applyPromo } from './promo.js';

export function priceFor(item, customer, date) {
  let price = item.basePriceCents;
  if (customer.tier === 'gold') {
    price = price * 0.9;
  } else if (customer.tier === 'silver') {
    price = price * 0.95;
  }
  if (item.category === 'seasonal' && date.getMonth() === 11) {
    price = price * 0.8;
  }
  if (item.category === 'clearance') {
    price = price * 0.5;
    if (customer.tier === 'gold') price = price * 0.95;
  }
  price = applyPromo(price, item, customer);
  return Math.round(price);
}
