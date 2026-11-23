// Tests for scripts/lib/audit-scope.mjs (scopePaths) and scripts/audit-scope.mjs (item bf401ffd, D6).
//
// Oracle: the item's task-scope D6 rule, applied by hand to each fixture; the expected value of every
// scenario is derived from that rule and the scenario's own input, never from the implementation.
// Scenario ids S16-S19 and the probe list match the item's test-plan note.
// The CLI runs through spawnSync(process.execPath) against temp git repositories with an empty global
// git config. Source is ASCII only (non-ASCII names are written as escapes).

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { scopePaths } from '../lib/audit-scope.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const CLI = path.join(path.resolve(here, '..'), 'audit-scope.mjs');

const workRoot = mkdtempSync(path.join(tmpdir(), 'audit-scope-test-'));
after(() => {
  try {
    rmSync(workRoot, { recursive: true, force: true, maxRetries: 5 });
  } catch {
    // best-effort cleanup of a temp directory; read-only git objects on Windows
  }
});

const emptyGitConfig = path.join(workRoot, 'empty.gitconfig');
writeFileSync(emptyGitConfig, '');
const baseEnv = {
  ...process.env,
  GIT_CONFIG_GLOBAL: emptyGitConfig,
  GIT_CONFIG_NOSYSTEM: '1',
  GIT_AUTHOR_NAME: 'Fixture',
  GIT_AUTHOR_EMAIL: 'fixture@example.invalid',
  GIT_COMMITTER_NAME: 'Fixture',
  GIT_COMMITTER_EMAIL: 'fixture@example.invalid',
};

function git(dir, args) {
  const r = spawnSync('git', ['-C', dir, ...args], { env: baseEnv, encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`git ${args.join(' ')} failed: ${r.stderr}`);
  return r.stdout;
}

// files: relPath -> content, committed; untracked: relPath -> content, written but never added
function makeRepo(name, files, untracked = {}) {
  const dir = path.join(workRoot, name);
  mkdirSync(dir, { recursive: true });
  git(dir, ['init', '-q']);
  const put = (rel, content) => {
    const abs = path.join(dir, ...rel.split('/'));
    mkdirSync(path.dirname(abs), { recursive: true });
    writeFileSync(abs, content);
  };
  for (const [rel, content] of Object.entries(files)) put(rel, content);
  git(dir, ['add', '-A']);
  git(dir, ['commit', '-q', '--no-gpg-sign', '-m', 'fixture commit']);
  for (const [rel, content] of Object.entries(untracked)) put(rel, content);
  return dir;
}

function runCli(args) {
  return spawnSync(process.execPath, [CLI, ...args], { env: baseEnv, encoding: 'utf8', timeout: 60000 });
}

// -------------------------------------------------------------------------------- S16 (D6 rule 1-4)

const S16_TRACKED = [
  'src/main/A.kt',
  'src/test/AT.kt',
  'src/test/kotlin/x/test/T.kt',
  'evals/fixtures/p/test/t.js',
  'lib/Specs/s.rb',
  'a/test.kt',
  'contest/x.js',
];

test('S16: no named paths gives "." and the test directories, with fixture trees and nested test directories dropped, sorted by code unit', () => {
  // test dirs: src/test, src/test/kotlin/x/test (inside src/test: dropped), evals/fixtures/p/test (a fixtures
  // segment: dropped), lib/Specs (specs, any case). a/test.kt and contest/x.js are not test dirs: a file name
  // never counts and contest is not test.
  assert.deepEqual(scopePaths(S16_TRACKED, []), ['.', 'lib/Specs', 'src/test']);
});

test('S16: the named list defaults to none', () => {
  assert.deepEqual(scopePaths(S16_TRACKED), ['.', 'lib/Specs', 'src/test']);
});

test('S16 probe (case): test directory names match in any case; a fixtures segment drops in any case', () => {
  assert.deepEqual(scopePaths(['Tests/a.py', 'SPEC/b.rb', '__Tests__/c.js'], []), ['.', 'SPEC', 'Tests', '__Tests__']);
  assert.deepEqual(
    scopePaths(['a/Fixtures/b/test/x.js', 'a/__FIXTURES__/tests/y.js', 'FIXTURES/spec/z.rb', 'keep/spec/k.rb'], []),
    ['.', 'keep/spec'],
  );
});

test('S16 probe: a tracked path with backslashes is read with forward slashes; a tracked set without tests is just "."', () => {
  assert.deepEqual(scopePaths(['src\\test\\A.kt', 'src\\main\\B.kt'], []), ['.', 'src/test']);
  assert.deepEqual(scopePaths(['src/main/A.kt', 'README.md'], []), ['.']);
  assert.deepEqual(scopePaths([], []), ['.']);
});

test('S16 probe: the code-unit sort puts an upper-case segment before a lower-case one', () => {
  assert.deepEqual(scopePaths(['a/tests/x.py', 'B/tests/y.py'], []), ['.', 'B/tests', 'a/tests']);
});

// -------------------------------------------------------------------------------- S17 (D6 rule 5)

const S17_TRACKED = [
  'current/src/main/kotlin/p/api/A.kt',
  'current/src/test/kotlin/p/api/T.kt',
  'plug/scripts/lib/m/x.mjs',
  'plug/scripts/tests/x.mjs',
  'plug/hooks/tests/h.mjs',
];

test('S17: a named main path adds its mirrored test directory when a tracked file lies under the mirror', () => {
  // T=current/src/test, P0=current/src; N lies under P0, first segment below P0 is main, mirror is
  // current/src/test/kotlin/p/api, and T.kt lies under it. The other test dirs have other parents.
  assert.deepEqual(
    scopePaths(S17_TRACKED, ['current/src/main/kotlin/p/api']),
    ['current/src/main/kotlin/p/api', 'current/src/test/kotlin/p/api'],
  );
});

test('S17: when no tracked file lies under the mirror the test directory itself is added', () => {
  // T=plug/scripts/tests, P0=plug/scripts, mirror plug/scripts/tests/m holds no tracked file.
  assert.deepEqual(
    scopePaths(S17_TRACKED, ['plug/scripts/lib/m']),
    ['plug/scripts/lib/m', 'plug/scripts/tests'],
  );
});

test('S17: a named path that contains test directories adds each of them, sorted after the named list', () => {
  assert.deepEqual(scopePaths(S17_TRACKED, ['plug']), ['plug', 'plug/hooks/tests', 'plug/scripts/tests']);
});

test('S17 probe: named paths keep their given order and the added ones follow, sorted, none repeated', () => {
  assert.deepEqual(
    scopePaths(S17_TRACKED, ['plug/scripts/lib/m', 'current/src/main/kotlin/p/api', 'plug/scripts/lib/m']),
    [
      'plug/scripts/lib/m',
      'current/src/main/kotlin/p/api',
      'current/src/test/kotlin/p/api',
      'plug/scripts/tests',
    ],
  );
});

test('S17 probe: a named path that is already a test directory is not added twice', () => {
  assert.deepEqual(scopePaths(S17_TRACKED, ['plug/scripts/tests']), ['plug/scripts/tests']);
});

test('S17 probe: a named path under no test directory parent adds nothing', () => {
  // T=plug/tests, P0=plug: docs does not lie under it
  assert.deepEqual(scopePaths(['src/main/A.kt', 'docs/guide.md', 'plug/tests/t.py'], ['docs']), ['docs']);
});

test('S17 probe: a root-level test directory (P0 is the root) mirrors any named path, even one that is not code', () => {
  // T=tests, P0 is the root, N=docs has no segment after its first; the mirror is tests itself, which holds a tracked file
  assert.deepEqual(scopePaths(['src/main/A.kt', 'docs/guide.md', 'tests/t.py'], ['docs']), ['docs', 'tests']);
});

// -------------------------------------------------------------------------------- S18 (D6 normalization)

test('S18: backslashes, a leading ./ and a trailing slash normalize; duplicates collapse to the first spelling', () => {
  assert.deepEqual(scopePaths([], ['.\\plug\\', 'plug']), ['plug']);
  assert.deepEqual(scopePaths([], ['plug/', './plug', 'plug']), ['plug']);
});

test('S18: the named list "." gives the same result as no named paths', () => {
  assert.deepEqual(scopePaths(S16_TRACKED, ['.']), ['.', 'lib/Specs', 'src/test']);
  assert.deepEqual(scopePaths(S16_TRACKED, ['.']), scopePaths(S16_TRACKED, []));
});

test('S18: a root-level test directory mirrors a named source path under any first segment', () => {
  // T=tests, P0 is the root, so N=src/pkg lies under it; the segment after src is pkg; mirror tests/pkg
  assert.deepEqual(scopePaths(['tests/pkg/t.py', 'src/pkg/m.py'], ['src/pkg']), ['src/pkg', 'tests/pkg']);
});

test('S18 probe (trailing slash): a trailing slash on a named path does not change the mirror', () => {
  const tracked = ['tests/pkg/t.py', 'src/pkg/m.py'];
  assert.deepEqual(scopePaths(tracked, ['src/pkg/']), scopePaths(tracked, ['src/pkg']));
  assert.deepEqual(scopePaths(tracked, ['src\\pkg\\']), ['src/pkg', 'tests/pkg']);
});

test('S18 probe: a root-level test directory with no tracked file under the mirror is added itself', () => {
  assert.deepEqual(scopePaths(['tests/other/t.py', 'src/pkg/m.py'], ['src/pkg']), ['src/pkg', 'tests']);
});

// -------------------------------------------------------------------------------- S19 CLI

const S19_FILES = {
  'src/main/A.kt': 'class A\n',
  'src/test/AT.kt': 'class AT\n',
  'lib/Specs/s.rb': 'puts 1\n',
};

test('S19: a temp repository prints the JSON array of scopePaths(git ls-files) and a newline, exit 0', () => {
  const dir = makeRepo('cli-default', S19_FILES);
  const r = runCli(['--root', dir]);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, `${JSON.stringify(['.', 'lib/Specs', 'src/test'])}\n`);
  assert.equal(r.stderr, '');
});

test('S19: --paths names the paths and adds their mirrored test directories', () => {
  const dir = makeRepo('cli-paths', S19_FILES);
  const r = runCli(['--root', dir, '--paths', 'src/main']);
  assert.equal(r.status, 0, r.stderr);
  // T=src/test, P0=src, N=src/main: the segment after main is none, mirror src/test, a tracked file lies under it
  assert.equal(r.stdout, `${JSON.stringify(['src/main', 'src/test'])}\n`);
});

test('S19 probe (empty entries): empty entries in --paths are ignored when one entry remains', () => {
  const dir = makeRepo('cli-empty-entry', S19_FILES);
  const r = runCli(['--root', dir, '--paths', 'src/main,,']);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, `${JSON.stringify(['src/main', 'src/test'])}\n`);
});

test('S19 probe (empty versus absent): --paths absent is the default; --paths with no non-empty entry is a usage error', () => {
  const dir = makeRepo('cli-empty-vs-absent', S19_FILES);
  const absent = runCli(['--root', dir]);
  assert.equal(absent.status, 0);
  for (const value of [',', ',,', '']) {
    const r = runCli(['--root', dir, '--paths', value]);
    assert.equal(r.status, 2, `--paths ${JSON.stringify(value)}`);
    assert.equal(r.stdout, '');
    assert.ok(r.stderr.includes('--paths names no path'), r.stderr);
  }
});

test('S19: usage errors exit 2 with a stderr message and nothing on stdout', () => {
  const dir = makeRepo('cli-usage', S19_FILES);
  const cases = [
    [['--root', dir, '--bogus', 'x'], 'unknown argument --bogus'],
    [['--root', dir, '--bogus'], 'unknown argument --bogus'],
    [['--root', dir, '--paths'], 'needs a value'],
    [['--root'], 'needs a value'],
    [['--paths', 'src'], '--root is required'],
    [[], '--root is required'],
  ];
  for (const [args, needle] of cases) {
    const r = runCli(args);
    assert.equal(r.status, 2, `args ${JSON.stringify(args)}`);
    assert.equal(r.stdout, '', `args ${JSON.stringify(args)} stdout`);
    assert.ok(r.stderr.includes(needle), `args ${JSON.stringify(args)}: stderr ${r.stderr}`);
  }
});

test('S19: a --root that is not a git work tree exits 2 naming the root, nothing on stdout', () => {
  const plain = path.join(workRoot, 'not-a-repo');
  mkdirSync(plain, { recursive: true });
  const r = runCli(['--root', plain]);
  assert.equal(r.status, 2);
  assert.equal(r.stdout, '');
  assert.ok(r.stderr.includes(`--root ${plain} is not a git work tree`), r.stderr);
});

test('S19 probe: untracked files are not scoped; the run leaves the repository untouched', () => {
  const dir = makeRepo('cli-untracked', S19_FILES, { 'zzz/test/u.js': 'x\n' });
  const before = git(dir, ['status', '--porcelain']);
  const r = runCli(['--root', dir]);
  assert.equal(r.status, 0, r.stderr);
  assert.equal(r.stdout, `${JSON.stringify(['.', 'lib/Specs', 'src/test'])}\n`);
  assert.equal(git(dir, ['status', '--porcelain']), before, 'read-only');
});

test('S19 probe: a tracked path with a non-ASCII segment is read as written (git does not quote it)', () => {
  const dir = makeRepo('cli-nonascii', {
    'src/é/m.py': 'x = 1\n',
    'tests/é/t.py': 'x = 1\n',
  });
  const r = runCli(['--root', dir, '--paths', 'src/é']);
  assert.equal(r.status, 0, r.stderr);
  // T=tests, mirror tests/<e-acute>, a tracked file lies under it; a quoted path would miss it and add tests
  assert.equal(r.stdout, `${JSON.stringify(['src/é', 'tests/é'])}\n`);
});
