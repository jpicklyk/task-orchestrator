export function createQueue() {
  return { items: [] };
}

export function push(queue, message) {
  queue.items.push(message);
  return queue.items.length;
}

export function drain(queue) {
  return queue.items.splice(0);
}
