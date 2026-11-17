#!/usr/bin/env node
// tier-policy.mjs - prints the tier-classification table in force: the plugin defaults merged with
// the project's tier-policy.json (beside the located project config) or an explicit --override file.
//
//   node tier-policy.mjs [--override <file>]
//
// Stdout is exactly the rendered block. Any error: message on stderr, nothing on stdout, exit 1.
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { realpathSync } from 'node:fs';
import { locateConfig } from '../hooks/config-locator.mjs';
import { tierPolicy, loadTierOverride, mergeTierOverride, renderTierBlock } from './lib/dispatch-policy.mjs';

export function run(argv) {
  let explicit = null;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--override' && i + 1 < argv.length) explicit = argv[++i];
    else throw new Error(`unknown argument: ${argv[i]}`);
  }
  const base = tierPolicy();
  let file = explicit;
  if (!file) {
    const located = locateConfig();
    if (located.path) file = join(dirname(located.path), 'tier-policy.json');
  }
  if (!file) return renderTierBlock(base);
  const override = loadTierOverride(file);
  if (override === null) {
    if (explicit) throw new Error(`tier policy ${file}: file not found`);
    return renderTierBlock(base);
  }
  return renderTierBlock(mergeTierOverride(base, override, file));
}

let isMain = false;
try {
  isMain = realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url));
} catch {
  isMain = false;
}
if (isMain) {
  try {
    process.stdout.write(run(process.argv.slice(2)));
  } catch (e) {
    process.stderr.write(`${e.message}\n`);
    process.exit(1);
  }
}
