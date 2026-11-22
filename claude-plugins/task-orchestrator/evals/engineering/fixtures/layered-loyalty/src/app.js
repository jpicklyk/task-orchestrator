import { createMemoryOrderStore } from './infra/memoryOrderStore.js';
import { placeOrder } from './domain/placeOrder.js';

export function createApp() {
  const deps = { orderStore: createMemoryOrderStore() };
  return {
    placeOrder: (customerId, lines) => placeOrder(deps, customerId, lines),
    getOrder: (id) => deps.orderStore.get(id),
  };
}
