#!/usr/bin/env node
// Red-proof extraction: a clean copy of the paths the node tests read, taken from one commit, with an
// unmutated control run inside it. Wave-boundary red-proofs on node tests (plugin hooks and scripts)
// mutate this extraction, never the live tree; a mutation counts only after a green control here.
//
//   node scripts/redproof-extract.mjs <sha> <outDir> <testFile>...
//
// Run with the working directory inside the repository. <sha> is anything `git rev-parse` resolves to
// a commit (a full or short sha, a branch). <outDir> is absent or empty. Each <testFile> is a
// repo-relative path with `/`, lies under one of EXTRACT_PATHS and exists at <sha>; it runs inside
// <outDir>, so the control proves the extraction and not the live tree.
//
// Exit codes
//   0  extracted and the control is green; stdout is the single line `control N/N`
//   1  extracted, the control ran and is not green; stdout `control P/N` (P of N run tests passing).
//      A skipped or todo test is run by nobody: it is in neither P nor N and is reported as a
//      trailing ` skipped S` (S = skipped + todo), printed only when S > 0, on any exit code.
//      Green needs N >= 1 and P = N, so a control that runs 0 tests is not green (`control 0/0`,
//      or `control 0/0 skipped S` when every test was skipped).
//   2  input refused (arguments, SHA, a listed path or test file absent at the SHA, a non-empty
//      outDir, a test file outside the extraction); stderr says why, no `control` line, nothing run.
//      A test file refused as absolute, holding a `..` segment, or not under a listed path says
//      `outside the extraction` in that message; a file merely absent at the SHA does not.
//
// Known control trap: a test that reads git history (bundled-rules-parity runs `git log`) cannot be
// green in an extraction, which has no .git; it is not a valid control file.

import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

// Exactly the paths node tests read today: plugin sources, test resources, the docs dir some tests
// parse, the changelog, and the project config and rules (the rules-leak scans read them).
export const EXTRACT_PATHS = Object.freeze([
  'claude-plugins/',
  'current/src/test/resources/',
  'current/docs/',
  'CHANGELOG.md',
  '.taskorchestrator/',
]);

class Refusal extends Error {}

const refuse = (message) => {
  throw new Refusal(message);
};

const git = (cwd, args, options = {}) =>
  execFileSync('git', ['-c', 'core.autocrlf=false', '-c', 'core.eol=lf', ...args], {
    cwd,
    stdio: ['ignore', 'pipe', 'pipe'],
    maxBuffer: 1024 * 1024 * 1024,
    ...options,
  });

const existsAt = (cwd, commit, repoPath) => {
  try {
    git(cwd, ['cat-file', '-e', `${commit}:${repoPath.replace(/\/$/, '')}`]);
    return true;
  } catch {
    return false;
  }
};

const normalizeTestFile = (raw) => {
  // A backslash separates path segments only on Windows; elsewhere it is a name character.
  const value = process.platform === 'win32' ? raw.replace(/\\/g, '/') : raw;
  if (path.posix.isAbsolute(value) || path.win32.isAbsolute(value)) {
    refuse(`test file is outside the extraction (must be repo-relative, got an absolute path): ${raw}`);
  }
  if (value.split('/').includes('..')) {
    refuse(`test file is outside the extraction (a `..` segment): ${raw}`);
  }
  const normalized = path.posix.normalize(value);
  const underListedPath = EXTRACT_PATHS.some((entry) =>
    entry.endsWith('/') ? normalized.startsWith(entry) : normalized === entry,
  );
  if (!underListedPath) {
    refuse(`test file is outside the extraction (not under an extracted path: ${EXTRACT_PATHS.join(', ')}): ${raw}`);
  }
  return normalized;
};

// Counts run tests from the TAP summary: tests minus skipped and todo (a skipped or todo test, failing
// or not, is in neither pass nor the total). A file that defines no test is reported by node as one
// passing pseudo-test named after the file; that is not a test, so it is subtracted (a file that
// fails to load stays counted as a failure).
const parseControl = (output, testFiles) => {
  const read = (name) => {
    const match = output.match(new RegExp('^# ' + name + ' (\\d+)\\s*$', 'm'));
    return match ? Number(match[1]) : 0;
  };
  const skipped = read('skipped') + read('todo');
  let tests = read('tests') - skipped;
  let pass = read('pass');
  const baseNames = new Set(testFiles.map((file) => path.posix.basename(file)));
  for (const line of output.split(/\r?\n/)) {
    const match = line.match(/^ok \d+ - (.+?)\s*$/);
    if (match && baseNames.has(match[1].split(/[\/\\]/).pop())) {
      tests -= 1;
      pass -= 1;
    }
  }
  return { tests, pass, skipped };
};

export function run(argv) {
  const [sha, outDirArg, ...rawTestFiles] = argv;
  if (!sha || !outDirArg || rawTestFiles.length === 0) {
    refuse('usage: node scripts/redproof-extract.mjs <sha> <outDir> <testFile>...');
  }
  if (sha.startsWith('-')) refuse(`not a commit: ${sha}`);

  let top;
  try {
    top = git(process.cwd(), ['rev-parse', '--show-toplevel']).toString().trim();
  } catch {
    refuse('run inside a git repository');
  }

  let commit;
  try {
    commit = git(top, ['rev-parse', '--verify', '--quiet', `${sha}^{commit}`]).toString().trim();
  } catch {
    refuse(`cannot resolve ${sha} to a commit`);
  }

  const testFiles = rawTestFiles.map(normalizeTestFile);

  for (const entry of EXTRACT_PATHS) {
    if (!existsAt(top, commit, entry)) refuse(`${entry} does not exist at ${sha}`);
  }
  for (const file of testFiles) {
    if (!existsAt(top, commit, file)) refuse(`test file ${file} does not exist at ${sha}`);
  }

  const outDir = path.resolve(outDirArg);
  if (fs.existsSync(outDir)) {
    if (!fs.statSync(outDir).isDirectory()) refuse(`${outDirArg} exists and is not a directory`);
    if (fs.readdirSync(outDir).length > 0) refuse(`${outDirArg} exists and is not empty`);
  }

  fs.mkdirSync(outDir, { recursive: true });
  const archive = git(top, ['archive', '--format=tar', commit, '--', ...EXTRACT_PATHS.map((p) => p.replace(/\/$/, ''))]);
  const untar = spawnSync('tar', ['-x'], { cwd: outDir, input: archive, stdio: ['pipe', 'pipe', 'pipe'] });
  if (untar.status !== 0) {
    throw new Error(`tar extraction failed: ${untar.stderr ? untar.stderr.toString() : untar.error}`);
  }
  process.stderr.write(`extracted ${commit} into ${outDir}\n`);

  const env = { ...process.env };
  delete env.NODE_TEST_CONTEXT;
  const control = spawnSync(process.execPath, ['--test', '--test-reporter=tap', ...testFiles], {
    cwd: outDir,
    env,
    encoding: 'utf8',
    maxBuffer: 256 * 1024 * 1024,
  });
  const { tests, pass, skipped } = parseControl(control.stdout || '', testFiles);
  process.stdout.write(`control ${pass}/${tests}${skipped > 0 ? ` skipped ${skipped}` : ''}\n`);
  return tests > 0 && pass === tests ? 0 : 1;
}

const isMain = (() => {
  try {
    return Boolean(process.argv[1]) && pathToFileURL(fs.realpathSync(process.argv[1])).href === import.meta.url;
  } catch {
    return false;
  }
})();

if (isMain) {
  try {
    process.exitCode = run(process.argv.slice(2));
  } catch (error) {
    process.stderr.write(`redproof-extract: ${error.message}\n`);
    process.exitCode = 2;
  }
}
