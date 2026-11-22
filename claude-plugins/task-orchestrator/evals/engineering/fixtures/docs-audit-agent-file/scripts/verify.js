import { spawnSync } from 'node:child_process';

const r = spawnSync('node', ['--test'], { stdio: 'inherit' });
process.exit(r.status ?? 1);
