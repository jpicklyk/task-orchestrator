#!/usr/bin/env node
// audit-metrics: churn, complexity, change coupling and module graph for the codebase audit.
//   node audit-metrics.mjs --root <repo> --paths <p1,p2,...> --since <YYYY-MM-DD> --recent <YYYY-MM-DD> --out <file.json>
//        [--module-depth N] [--top N] [--lang-table <json>] [--max-commit-files N]
// A module is a base directory plus up to --module-depth directory segments below it. A base is a source root derived from
// resolved package imports, or the common directory of a connected group of relative imports; a file with none uses the
// common directory of its --paths entry.
// Read-only on the repository (git log, ls-files, show, and file reads); writes only --out.
// Exit 0 on success, 2 on a usage error or a --root that is not a git work tree.
import { spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateLanguageTable } from './lib/audit-metrics/languages.mjs';
import { collectMetrics } from './lib/audit-metrics/metrics.mjs';

const VALUE_FLAGS = new Set(['root', 'paths', 'since', 'recent', 'out', 'module-depth', 'top', 'lang-table', 'max-commit-files']);
const USAGE = 'usage: audit-metrics.mjs --root <repo> --paths <p1,p2,...> --since <YYYY-MM-DD> --recent <YYYY-MM-DD> --out <file.json> [--module-depth N] [--top N] [--lang-table <json>] [--max-commit-files N]';

class UsageError extends Error {}

function parseArgs(argv) {
  const flags = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--') || !VALUE_FLAGS.has(a.slice(2))) throw new UsageError(`unknown argument ${a}`);
    const v = argv[i + 1];
    if (v === undefined || v.startsWith('--')) throw new UsageError(`${a} needs a value`);
    flags[a.slice(2)] = v;
    i++;
  }
  for (const req of ['root', 'paths', 'since', 'recent', 'out']) if (!flags[req]) throw new UsageError(`--${req} is required`);
  return flags;
}

function realDate(s, name) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
  const d = m && new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
  if (!d || d.toISOString().slice(0, 10) !== s) throw new UsageError(`--${name} must be a real YYYY-MM-DD date`);
  return s;
}

function positiveInt(flags, name, fallback) {
  if (flags[name] === undefined) return fallback;
  if (!/^\d+$/.test(flags[name]) || Number(flags[name]) < 1) throw new UsageError(`--${name} must be an integer >= 1`);
  return Number(flags[name]);
}

function gitIo(root) {
  return {
    git(args) {
      const r = spawnSync('git', ['-C', root, ...args], { encoding: 'utf8', maxBuffer: 1 << 30 });
      return r.error || r.status !== 0 ? null : r.stdout;
    },
    readFile(rel) {
      try {
        return readFileSync(join(root, rel));
      } catch {
        return null;
      }
    },
  };
}

function main(argv) {
  const flags = parseArgs(argv);
  const since = realDate(flags.since, 'since');
  const recent = realDate(flags.recent, 'recent');
  if (recent < since) throw new UsageError('--recent must not be before --since');
  const moduleDepth = positiveInt(flags, 'module-depth', 2);
  const top = positiveInt(flags, 'top', 10);
  const maxCommitFiles = positiveInt(flags, 'max-commit-files', 40);
  const paths = flags.paths.split(',').map((p) => p.trim()).filter((p) => p !== '');
  if (paths.length === 0) throw new UsageError('--paths names no path');
  const root = resolve(flags.root);
  try {
    if (!statSync(root).isDirectory()) throw new Error('not a directory');
  } catch {
    throw new UsageError(`--root ${flags.root} is not a directory`);
  }
  const io = gitIo(root);
  if ((io.git(['rev-parse', '--is-inside-work-tree']) || '').trim() !== 'true') throw new UsageError(`--root ${flags.root} is not a git work tree`);
  let langTable;
  const tablePath = flags['lang-table'] || join(dirname(fileURLToPath(import.meta.url)), 'lib', 'audit-metrics', 'languages.json');
  try {
    langTable = JSON.parse(readFileSync(tablePath, 'utf8'));
    validateLanguageTable(langTable);
  } catch (e) {
    throw new UsageError(`--lang-table ${tablePath} is not a usable language table: ${e.message}`);
  }
  const result = collectMetrics({ root: root.replace(/\\/g, '/'), paths, since, recent, moduleDepth, top, maxCommitFiles, langTable }, io);
  mkdirSync(dirname(resolve(flags.out)), { recursive: true });
  writeFileSync(resolve(flags.out), JSON.stringify(result, null, 2) + '\n');
}

try {
  main(process.argv.slice(2));
} catch (e) {
  if (e instanceof UsageError) {
    process.stderr.write(`${e.message}\n${USAGE}\n`);
    process.exit(2);
  }
  throw e;
}
