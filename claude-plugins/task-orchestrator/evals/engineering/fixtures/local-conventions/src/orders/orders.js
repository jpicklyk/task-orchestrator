import { DomainError } from '../errors.js';
import { log } from '../log.js';

const orders = new Map();

export function addOrder(order) {
  orders.set(order.id, { ...order, refunds: [] });
  log.info('order.added', { orderId: order.id });
}

export function getOrder(orderId) {
  const order = orders.get(orderId);
  if (!order) throw new DomainError('ORDER_NOT_FOUND', `no order ${orderId}`);
  return order;
}

export function markPaid(orderId, paidCents) {
  const order = getOrder(orderId);
  if (order.paidCents) throw new DomainError('ORDER_ALREADY_PAID', `order ${orderId} is already paid`);
  order.paidCents = paidCents;
  log.info('order.paid', { orderId, paidCents });
  return order;
}
