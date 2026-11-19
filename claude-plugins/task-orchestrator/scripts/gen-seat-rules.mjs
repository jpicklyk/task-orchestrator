#!/usr/bin/env node
// gen-seat-rules.mjs - keeps the seat-rules region of both workflow scripts equal to the seat rule table
// and its resolver.
//
// Inputs (read only): hooks/lib/seat-rules.json and the block of hooks/lib/seat-rules.mjs between
//   // @seat-rules-shared-begin  and  // @seat-rules-shared-end
// Outputs: the region of workflows/implement-wave.js and workflows/review-wave.js between
//   // @generated:seat-rules-begin (...)  and  // @generated:seat-rules-end
// The region holds `const SEAT_RULES = Object.freeze(<table>)` and the shared block without the `export `
// keyword: a workflow script runs as a function body and cannot import.
//
// Usage: node gen-seat-rules.mjs [--check] [--plugin-root D]
//   write mode: rewrites each region, exit 0.
//   --check: exit 0 when fresh; exit 1 naming each stale file on stderr.
//   A missing marker or an unreadable or invalid input: exit 2.

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const DEFAULT_PLUGIN_ROOT = resolve(SCRIPT_DIR, '..');

export const TABLE_FILE = 'hooks/lib/seat-rules.json';
export const RESOLVER_FILE = 'hooks/lib/seat-rules.mjs';
export const WORKFLOW_FILES = ['workflows/implement-wave.js', 'workflows/review-wave.js'];
export const SHARED_BEGIN = '// @seat-rules-shared-begin';
export const SHARED_END = '// @seat-rules-shared-end';
export const REGION_BEGIN = '// @generated:seat-rules-begin (scripts/gen-seat-rules.mjs; do not edit, run it to refresh)';
export const REGION_END = '// @generated:seat-rules-end';
const REGION_BEGIN_TAG = '// @generated:seat-rules-begin';

/** The shared block of the resolver source, markers excluded, `export ` removed. Throws when a marker is missing. */
export function sharedBlock(source) {
  const text = String(source).replace(/\r\n/g, '\n');
  const start = text.indexOf(SHARED_BEGIN);
  if (start === -1) throw new Error(`shared begin marker missing in ${RESOLVER_FILE}`);
  const from = text.indexOf('\n', start) + 1;
  const end = text.indexOf(SHARED_END, from);
  if (end === -1) throw new Error(`shared end marker missing in ${RESOLVER_FILE}`);
  return text
    .slice(from, end)
    .split('\n')
    .map((l) => l.replace(/^export (async )?(function|const) /, '$1$2 '))
    .join('\n')
    .replace(/\s+$/, '');
}

/** The full region (both marker lines included), no trailing newline. */
export function renderRegion(tableText, resolverSource) {
  const table = JSON.parse(String(tableText));
  if (!table || typeof table !== 'object' || !table.roles || typeof table.roles !== 'object') {
    throw new Error(`${TABLE_FILE} has no roles map`);
  }
  const json = JSON.stringify(table, null, 2);
  return `${REGION_BEGIN}\nconst SEAT_RULES = Object.freeze(${json})\n${sharedBlock(resolverSource)}\n${REGION_END}`;
}

/** Replaces the marker-to-marker region of text with region. Throws when a marker is missing. */
export function replaceRegion(text, region, file) {
  const start = text.indexOf(REGION_BEGIN_TAG);
  if (start === -1) throw new Error(`region begin marker missing in ${file}`);
  const endAt = text.indexOf(REGION_END, start);
  if (endAt === -1) throw new Error(`region end marker missing in ${file}`);
  return text.slice(0, start) + region + text.slice(endAt + REGION_END.length);
}

function main() {
  const argv = process.argv.slice(2);
  const check = argv.includes('--check');
  const rootIdx = argv.indexOf('--plugin-root');
  const root = rootIdx !== -1 && argv[rootIdx + 1] ? resolve(argv[rootIdx + 1]) : DEFAULT_PLUGIN_ROOT;
  const outputs = [];
  try {
    const region = renderRegion(readFileSync(join(root, TABLE_FILE), 'utf8'), readFileSync(join(root, RESOLVER_FILE), 'utf8'));
    for (const file of WORKFLOW_FILES) {
      const path = join(root, file);
      const current = readFileSync(path, 'utf8');
      outputs.push({ file, path, current, fresh: replaceRegion(current, region, file) });
    }
  } catch (e) {
    process.stderr.write(`gen-seat-rules: ${e.message}\n`);
    process.exit(2);
  }
  const stale = outputs.filter((o) => o.fresh !== o.current);
  if (check) {
    for (const o of stale) {
      process.stderr.write(`stale: ${o.file} (run: node claude-plugins/task-orchestrator/scripts/gen-seat-rules.mjs)\n`);
    }
    process.exit(stale.length ? 1 : 0);
  }
  for (const o of stale) writeFileSync(o.path, o.fresh);
  process.exit(0);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();
