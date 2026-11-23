#!/usr/bin/env node
// audit-report-html.mjs - CLI over lib/audit-report-html.mjs.
//   node audit-report-html.mjs --result <file> [--metrics <file>] [--previous <file>] --out <file.html>
// Exit 0 after writing --out (UTF-8, LF). Exit 2 with stderr `INVALID: <msg>` and --out untouched
// for an unknown flag, a missing value, a missing --result or --out, an unreadable file, bad JSON
// or an input the renderer rejects. This file is the only place that does I/O.

import { readFileSync, writeFileSync } from 'node:fs';
import { renderAuditReport } from './lib/audit-report-html.mjs';

function fail(msg) {
  process.stderr.write(`INVALID: ${msg}\n`);
  process.exit(2);
}

const FLAGS = new Set(['--result', '--metrics', '--previous', '--out']);
const argv = process.argv.slice(2);
const opts = {};
for (let i = 0; i < argv.length; i += 2) {
  const flag = argv[i];
  if (!FLAGS.has(flag)) fail(`unknown argument: ${flag}`);
  if (argv[i + 1] === undefined) fail(`missing value for ${flag}`);
  opts[flag] = argv[i + 1];
}
for (const f of ['--result', '--out']) if (!opts[f]) fail(`missing ${f}`);

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
const metrics = opts['--metrics'] ? readJson('--metrics') : null;
const previous = opts['--previous'] ? readJson('--previous') : null;

let html;
try {
  html = renderAuditReport(result, metrics, previous);
} catch (err) {
  if (err instanceof TypeError) fail(err.message);
  throw err;
}

try {
  writeFileSync(opts['--out'], html, 'utf8');
} catch (err) {
  fail(`cannot write --out ${opts['--out']}: ${err.code || err.message}`);
}
