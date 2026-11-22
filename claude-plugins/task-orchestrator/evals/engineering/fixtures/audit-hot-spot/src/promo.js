const PROMOS = [
  { code: 'SPRING5', category: 'garden', pct: 5 },
  { code: 'BULK10', minQty: 10, pct: 10 },
];

export function applyPromo(price, item, customer) {
  for (const p of PROMOS) {
    if (customer.promoCode === p.code) {
      if (p.category && item.category !== p.category) continue;
      if (p.minQty && (item.qty || 1) < p.minQty) continue;
      price = price * (1 - p.pct / 100);
    }
  }
  return price;
}
