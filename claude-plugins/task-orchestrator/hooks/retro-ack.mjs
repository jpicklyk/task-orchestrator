#!/usr/bin/env node
// CLI — compatibility no-op. The retrospective acknowledgement is now the server-side `retro` mark on
// the run (runs_write mark, set by retro-trigger.mjs / retro-backstop.mjs when they dispatch), so there
// is no local marker to acknowledge. Kept so an existing `node retro-ack.mjs` call still exits 0.
// Makes no REST request and writes no file.

process.stdout.write('retro-ack is a no-op: the run mark (runs_write mark, key retro) is the retrospective acknowledgement.\n');
