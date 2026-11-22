import { DomainError } from '../errors.js';
import { log } from '../log.js';
import { markPaid } from '../orders/orders.js';

export function capturePayment(orderId, amountCents) {
  if (!Number.isInteger(amountCents) || amountCents <= 0) {
    throw new DomainError('PAYMENT_AMOUNT_INVALID', `invalid amount ${amountCents}`);
  }
  const order = markPaid(orderId, amountCents);
  log.info('payment.captured', { orderId, amountCents });
  return order;
}
