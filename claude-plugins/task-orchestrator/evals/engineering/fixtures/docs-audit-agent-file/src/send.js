import { drain } from './queue.js';

export async function flush(queue, deliver) {
  const batch = drain(queue);
  for (const m of batch) await deliver(m);
  return batch.length;
}
