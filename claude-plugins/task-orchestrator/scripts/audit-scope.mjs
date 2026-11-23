#!/usr/bin/env node
// audit-scope: the scope.paths list for a codebase audit.
//   node audit-scope.mjs --root <repo> [--paths <p1,p2,...>]
// Prints a JSON array on one line: the whole repository and its test directories, or the named paths and the test
// directories related to them (see lib/audit-scope.mjs). Read-only (git ls-files).
// Exit 0 on success, 2 on a usage error or a --root that is not a git work tree or not its top level; 1 on a failed git ls-files.
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { scopePaths } from './lib/audit-scope.mjs';
import { sameEntry } from './lib/path-identity.mjs';

const VALUE_FLAGS = new Set(['root', 'paths']);
const USAGE = 'usage: audit-scope.mjs --root <repo> [--paths <p1,p2,...>]';

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
  if (!flags.root) throw new UsageError('--root is required');
  return flags;
}

function git(root, args) {
  const r = spawnSync('git', ['-C', root, ...args], { encoding: 'utf8', maxBuffer: 1 << 30 });
  return r.error || r.status !== 0 ? null : r.stdout;
}

function main(argv) {
  const flags = parseArgs(argv);
  let named = [];
  if (flags.paths !== undefined) {
    named = flags.paths.split(',').map((p) => p.trim()).filter((p) => p !== '');
    if (named.length === 0) throw new UsageError('--paths names no path');
  }
  const root = resolve(flags.root);
  if ((git(root, ['rev-parse', '--is-inside-work-tree']) || '').trim() !== 'true') throw new UsageError(`--root ${flags.root} is not a git work tree`);
  const topLevel = (git(root, ['rev-parse', '--show-toplevel']) || '').trim();
  if (topLevel === '') throw new UsageError(`git top level could not be determined for --root ${flags.root}`);
  if (!sameEntry(root, topLevel)) throw new UsageError(`--root ${flags.root} is not the top level of its work tree (${topLevel})`);
  const out = git(root, ['ls-files', '-z']);
  if (out === null) throw new RunError(`git ls-files failed in ${root}`);
  const tracked = [...new Set(out.split('\0').filter((p) => p !== ''))];
  process.stdout.write(JSON.stringify(scopePaths(tracked, named)) + '\n');
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
