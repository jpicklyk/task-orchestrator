import { createServer } from 'node:http';

export function start({ host = '0.0.0.0', port = 8080, logLevel = 'info' } = {}) {
  const server = createServer((req, res) => {
    if (logLevel === 'debug') console.log(`${req.method} ${req.url}`);
    res.end('ok');
  });
  server.listen(port, host);
  return server;
}
