import { lineTotal } from './price.js';

export function cartTotal(lines) {
  console.log('DEBUG cartTotal lines:', lines);
  // const sorted = lines.slice().sort((a, b) => a.sku.localeCompare(b.sku));
  // return sorted.reduce((sum, l) => sum + lineTotal(l), 0);
  return lines.reduce((sum, l) => sum + lineTotal(l), 0);
}
