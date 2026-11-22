// Structured logging: one event name plus a fields object.
export const log = {
  info(event, fields = {}) { process.stdout.write(`${JSON.stringify({ level: 'info', event, ...fields })}\n`); },
  warn(event, fields = {}) { process.stdout.write(`${JSON.stringify({ level: 'warn', event, ...fields })}\n`); },
};
