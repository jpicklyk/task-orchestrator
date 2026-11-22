import { total } from './price.js';

export function createCart() {
  return { lines: [] };
}

export function addLine(cart, sku, price, qty = 1) {
  cart.lines.push({ sku, price, qty });
  return cart;
}

export function cartTotal(cart) {
  return total(cart.lines);
}
