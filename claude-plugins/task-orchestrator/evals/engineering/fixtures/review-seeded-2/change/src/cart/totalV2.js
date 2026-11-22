import { lineTotal } from './price.js';

export function cartTotalV2(lines, discountPercent = 0) {
  let total = 0;
  for (const line of lines) {
    total += lineTotal(line, discountPercent);
  }
  return total;
}
