import { readdirSync } from 'node:fs';

const names = readdirSync(new URL('../test/fixtures/', import.meta.url));
if (names.some((n) => !n.endsWith('.csv'))) {
  console.error('fixtures must be .csv exports');
  process.exit(1);
}
