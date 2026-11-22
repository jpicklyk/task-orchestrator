import { lineTotal } from './price.js';

export function cartTotal(lines) {
  return lines.reduce((sum, l) => sum + lineTotal(l), 0);
}
