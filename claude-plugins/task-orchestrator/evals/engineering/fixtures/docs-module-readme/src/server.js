import { createServer } from 'node:http';
import { createLimiter } from './ratelimit/index.js';

export function buildServer(handler, limiter = createLimiter()) {
  return createServer((req, res) => {
    const key = req.headers['x-client-id'] || req.socket.remoteAddress;
    if (!limiter.allow(key)) {
      res.writeHead(429, { 'retry-after': '1' });
      res.end('rate limited');
      return;
    }
    handler(req, res);
  });
}
