const purchases = [];

export function recordPurchase(purchase) {
  purchases.push(purchase);
}

export function purchasesFor(customerId) {
  return purchases.filter((p) => p.customerId === customerId);
}
