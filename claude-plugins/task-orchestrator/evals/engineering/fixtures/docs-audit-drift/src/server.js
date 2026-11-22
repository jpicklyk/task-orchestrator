import { createServer } from 'node:http';
import { createOrder, getOrder, cancelOrder, deleteOrder } from './orders.js';

const routes = [
  ['POST', '/orders', (body) => createOrder(body.id, body.lines)],
  ['GET', '/orders/:id', (body, id) => getOrder(id)],
  ['POST', '/orders/:id/cancel', (body, id) => cancelOrder(id)],
  ['DELETE', '/orders/:id', (body, id) => deleteOrder(id)],
];

export function buildServer() {
  return createServer((req, res) => {
    const hit = routes.find(([m, p]) => m === req.method && new RegExp(`^${p.replace(':id', '([^/]+)')}$`).test(req.url));
    res.statusCode = hit ? 200 : 404;
    res.end(JSON.stringify(hit ? hit[2]({}, req.url.split('/')[2]) : { error: 'not found' }));
  });
}
