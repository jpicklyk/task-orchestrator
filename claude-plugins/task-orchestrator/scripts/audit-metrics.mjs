#!/usr/bin/env node
// audit-metrics: churn, complexity, change coupling and module graph for the codebase audit.
//   node audit-metrics.mjs --root <repo> --paths <p1,p2,...> --since <YYYY-MM-DD> --recent <YYYY-MM-DD> --out <file.json>
//        [--module-depth N] [--top N] [--lang-table <json>] [--max-commit-files N] [--digest-out <file.json> [--candidates N]]
// A module is a base directory plus up to --module-depth directory segments below it. A base is a source root derived from
// resolved package imports, or the common directory of a connected group of relative imports; a file with none uses the
// common directory of its --paths entry.
// --digest-out also writes the audit/digest-v1 digest of the metrics; --candidates N caps its ranked candidate list (default 12).
// Read-only on the repository (git log, ls-files, show, and file reads); writes only --out and --digest-out.
// Exit 0 on success, 2 on a usage error, a --root that is not a git work tree or not its top level; 1 on a write failure or
// an unusable bundled language table.
import { spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateLanguageTable } from './lib/audit-metrics/languages.mjs';
import { buildDigest, DEFAULT_CANDIDATES, serializeDigest } from './lib/audit-metrics/digest.mjs';
import { collectMetrics } from './lib/audit-metrics/metrics.mjs';
import { sameEntry } from './lib/path-identity.mjs';

const VALUE_FLAGS = new Set(['root', 'paths', 'since', 'recent', 'out', 'module-depth', 'top', 'lang-table', 'max-commit-files', 'digest-out', 'candidates']);
const USAGE = 'usage: audit-metrics.mjs --root <repo> --paths <p1,p2,...> --since <YYYY-MM-DD> --recent <YYYY-MM-DD> --out <file.json> [--module-depth N] [--top N] [--lang-table <json>] [--max-commit-files N] [--digest-out <file.json> [--candidates N]]';

class UsageError extends Error {}
class RunError extends Error {}

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
  const candidates = positiveInt(flags, 'candidates', DEFAULT_CANDIDATES);
  if (flags.candidates !== undefined && flags['digest-out'] === undefined) throw new UsageError('--candidates needs --digest-out');
  if (flags['digest-out'] !== undefined && sameEntry(flags['digest-out'], flags.out)) {
    throw new UsageError('--digest-out must not name the same file as --out');
  }
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
  const topLevel = (io.git(['rev-parse', '--show-toplevel']) || '').trim();
  if (!sameEntry(root, topLevel)) throw new UsageError(`--root ${flags.root} is not the top level of its work tree (${topLevel})`);
  let langTable;
  const tablePath = flags['lang-table'] || join(dirname(fileURLToPath(import.meta.url)), 'lib', 'audit-metrics', 'languages.json');
  try {
    langTable = JSON.parse(readFileSync(tablePath, 'utf8'));
    validateLanguageTable(langTable);
  } catch (e) {
    if (flags['lang-table']) throw new UsageError(`--lang-table ${tablePath} is not a usable language table: ${e.message}`);
    throw new RunError(`language table ${tablePath} is not usable: ${e.message}`);
  }
  const result = collectMetrics({ root: root.replace(/\\/g, '/'), paths, since, recent, moduleDepth, top, maxCommitFiles, langTable }, io);
  const targets = [{ path: resolve(flags.out), content: JSON.stringify(result, null, 2) + '\n' }];
  if (flags['digest-out'] !== undefined) {
    const digest = buildDigest(result, { top, candidates });
    digest.metricsPath = resolve(flags.out).split('\\').join('/');
    targets.push({ path: resolve(flags['digest-out']), content: serializeDigest(digest) });
  }
  const failWrite = (path, why) => new RunError(`cannot write ${path}: ${why}`);
  for (const t of targets) {
    try {
      if (statSync(t.path).isDirectory()) throw failWrite(t.path, 'it is a directory');
    } catch (e) {
      if (e instanceof RunError) throw e;
    }
  }
  for (const t of targets) {
    try {
      mkdirSync(dirname(t.path), { recursive: true });
    } catch (e) {
      throw failWrite(t.path, e.message);
    }
  }
  for (const t of targets) {
    try {
      writeFileSync(t.path, t.content);
    } catch (e) {
      throw failWrite(t.path, e.message);
    }
  }
}

try {
  main(process.argv.slice(2));
} catch (e) {
  if (e instanceof UsageError) {
    process.stderr.write(`${e.message}\n${USAGE}\n`);
    process.exit(2);
  }
  if (e instanceof RunError) {
    process.stderr.write(`${e.message}\n`);
    process.exit(1);
  }
  throw e;
}
