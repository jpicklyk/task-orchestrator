#!/usr/bin/env node
// resolve-changelog.mjs - CLI over resolve-changelog-lib.mjs.
//   node resolve-changelog.mjs --ancestor <path> --base <path> --branch <path> [--out <path>]
// Exit 0 resolved (text to --out, else stdout; discarded branch bullets on stderr),
// 1 duplicate heading (stderr DUPLICATE <kind> <name>; --out untouched),
// 2 invalid input or usage (--out untouched). This file is the only place that does I/O.

import { readFileSync, writeFileSync } from 'node:fs';
import { resolveChangelog } from './resolve-changelog-lib.mjs';

function fail(msg) {
  process.stderr.write(`INVALID: ${msg}\n`);
  process.exit(2);
}

const FLAGS = new Set(['--ancestor', '--base', '--branch', '--out']);
const argv = process.argv.slice(2);
const opts = {};
for (let i = 0; i < argv.length; i += 2) {
  const flag = argv[i];
  if (!FLAGS.has(flag)) fail(`unknown argument: ${flag}`);
  if (argv[i + 1] === undefined) fail(`missing value for ${flag}`);
  opts[flag] = argv[i + 1];
}
for (const f of ['--ancestor', '--base', '--branch']) if (!opts[f]) fail(`missing ${f}`);

const read = (f) => {
  try {
    return readFileSync(opts[f], 'utf8');
  } catch (e) {
    return fail(`cannot read ${f} ${opts[f]}: ${e.code || e.message}`);
  }
};
const result = resolveChangelog({
  ancestor: read('--ancestor'),
  base: read('--base'),
  branch: read('--branch'),
});

if (!result.ok && result.code === 'duplicate') {
  for (const d of result.duplicates) process.stderr.write(`DUPLICATE ${d.kind} ${d.name}\n`);
  process.exit(1);
}
if (!result.ok) fail(result.message);

for (const d of result.discarded) {
  const body = d.branchText === null ? '(deleted on branch)' : d.branchText;
  process.stderr.write(`DISCARDED [${d.section}] ${d.key}\n${body}\n`);
}
if (opts['--out']) {
  try {
    writeFileSync(opts['--out'], result.text, 'utf8');
  } catch (e) {
    fail(`cannot write --out: ${e.code || e.message}`);
  }
} else {
  process.stdout.write(result.text);
}
process.exit(0);
