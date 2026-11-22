import { buildLabel } from './labels.js';
import { withRetry } from './retry.js';

export async function shipOrder(order, carrier) {
  // build the label for the order
  const label = buildLabel(order);
  // book the carrier, retrying on failure
  const receipt = await withRetry(() => carrier.book(label));
  // return the result
  return { orderId: order.id, trackingId: receipt.trackingId };
}
