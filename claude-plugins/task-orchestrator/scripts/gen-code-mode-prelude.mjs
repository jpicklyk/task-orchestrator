#!/usr/bin/env node
// gen-code-mode-prelude.mjs - keeps the Code Mode script prelude in run-wave's code-mode.md equal to
// the helper module it is made from.
//
// Input (read only): scripts/lib/code-mode-helpers.mjs.
// Output: the region of skills/run-wave/references/code-mode.md between
//   // @generated:code-mode-prelude-begin (...)  and  // @generated:code-mode-prelude-end
// The region holds the helper source without its leading header comment and without the `export `
// keyword, so a REPL script can paste it as plain function declarations.
//
// Usage: node gen-code-mode-prelude.mjs [--check] [--plugin-root D]
//   write mode: rewrites the region, exit 0.
//   --check: exit 0 when fresh; exit 1 naming the stale file on stderr.
//   A missing marker or unreadable input: exit 2.

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const DEFAULT_PLUGIN_ROOT = resolve(SCRIPT_DIR, '..');

export const HELPERS_FILE = 'scripts/lib/code-mode-helpers.mjs';
export const DOC_FILE = 'skills/run-wave/references/code-mode.md';
export const REGION_BEGIN =
  '// @generated:code-mode-prelude-begin (scripts/gen-code-mode-prelude.mjs; do not edit, run it to refresh)';
export const REGION_END = '// @generated:code-mode-prelude-end';

// The full region (both marker lines included), no trailing newline.
export function renderPrelude(source) {
  const lines = String(source).replace(/\r\n/g, '\n').split('\n');
  let i = 0;
  while (i < lines.length && (lines[i].startsWith('//') || lines[i].trim() === '')) i += 1;
  const body = lines
    .slice(i)
    .map((l) => l.replace(/^export (async )?function /, '$1function '))
    .join('\n')
    .replace(/\s+$/, '');
  return `${REGION_BEGIN}\n${body}\n${REGION_END}`;
}

// Replaces the marker-to-marker region of text with region. Throws when a marker is missing.
export function replaceRegion(text, region) {
  const start = text.indexOf('// @generated:code-mode-prelude-begin');
  if (start === -1) throw new Error('region begin marker missing');
  const endAt = text.indexOf(REGION_END, start);
  if (endAt === -1) throw new Error('region end marker missing');
  return text.slice(0, start) + region + text.slice(endAt + REGION_END.length);
}

function main() {
  const argv = process.argv.slice(2);
  const check = argv.includes('--check');
  const rootIdx = argv.indexOf('--plugin-root');
  const root = rootIdx !== -1 && argv[rootIdx + 1] ? resolve(argv[rootIdx + 1]) : DEFAULT_PLUGIN_ROOT;
  const docPath = join(root, DOC_FILE);
  let fresh;
  let current;
  try {
    const region = renderPrelude(readFileSync(join(root, HELPERS_FILE), 'utf8'));
    current = readFileSync(docPath, 'utf8');
    fresh = replaceRegion(current, region);
  } catch (e) {
    process.stderr.write(`gen-code-mode-prelude: ${e.message}\n`);
    process.exit(2);
  }
  if (check) {
    if (fresh !== current) {
      process.stderr.write(`stale: ${DOC_FILE} (run: node claude-plugins/task-orchestrator/scripts/gen-code-mode-prelude.mjs)\n`);
      process.exit(1);
    }
    process.exit(0);
  }
  if (fresh !== current) writeFileSync(docPath, fresh);
  process.exit(0);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main();
