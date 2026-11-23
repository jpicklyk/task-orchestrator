#!/usr/bin/env node
// audit-confirm.mjs - CLI over lib/audit-confirm.mjs.
//   node audit-confirm.mjs --result <file> --metrics <file> --out <file.json>
// Checks every measured claim of an audit result against the audit/metrics-v1 file and writes the bare
// result, with confirmed rows kept, refuted rows moved and the counts and proposal recomputed.
// Exit 0 after writing --out (UTF-8, LF). Exit 2 with stderr `INVALID: <msg>` and --out untouched
// for an unknown flag, a missing value, a missing --result, --metrics or --out, an unreadable file,
// bad JSON or an input the library rejects. This file is the only place that does I/O.

import { readFileSync, writeFileSync } from 'node:fs';
import { confirmMeasured } from './lib/audit-confirm.mjs';

function fail(msg) {
  process.stderr.write(`INVALID: ${msg}\n`);
  process.exit(2);
}

const FLAGS = new Set(['--result', '--metrics', '--out']);
const argv = process.argv.slice(2);
const opts = {};
for (let i = 0; i < argv.length; i += 2) {
  const flag = argv[i];
  if (!FLAGS.has(flag)) fail(`unknown argument: ${flag}`);
  if (argv[i + 1] === undefined) fail(`missing value for ${flag}`);
  opts[flag] = argv[i + 1];
}
for (const f of ['--result', '--metrics', '--out']) if (!opts[f]) fail(`missing ${f}`);

const readJson = (f) => {
  let text;
  try {
    text = readFileSync(opts[f], 'utf8');
  } catch (err) {
    return fail(`cannot read ${f} ${opts[f]}: ${err.code || err.message}`);
  }
  try {
    return JSON.parse(text);
  } catch (err) {
    return fail(`bad JSON in ${f} ${opts[f]}: ${err.message}`);
  }
};

const result = readJson('--result');
const metrics = readJson('--metrics');

let out;
try {
  out = confirmMeasured(result, metrics);
} catch (err) {
  if (err instanceof TypeError) fail(err.message);
  throw err;
}

try {
  writeFileSync(opts['--out'], JSON.stringify(out, null, 2) + '\n', 'utf8');
} catch (err) {
  fail(`cannot write --out ${opts['--out']}: ${err.code || err.message}`);
}
