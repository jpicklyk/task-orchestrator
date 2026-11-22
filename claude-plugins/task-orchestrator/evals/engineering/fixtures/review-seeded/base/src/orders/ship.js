import { buildLabel } from './labels.js';

export async function shipOrder(order, carrier) {
  const label = buildLabel(order);
  const receipt = await carrier.book(label);
  return { orderId: order.id, trackingId: receipt.trackingId };
}
