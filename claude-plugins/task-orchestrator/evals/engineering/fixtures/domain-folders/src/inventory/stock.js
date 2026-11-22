const levels = new Map();

export function receiveStock(sku, qty) {
  levels.set(sku, (levels.get(sku) ?? 0) + qty);
}

export function availableStock(sku) {
  return levels.get(sku) ?? 0;
}
