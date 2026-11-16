// Tests for scripts/redproof-extract.mjs, written against the frozen test-plan contract of
// item dea8ed0d (scenarios S1-S16, S14-S16 and the S10/S11 refusal-token checks added by the
// planner's amendment A1). Oracles: proposal 6fedc0f6 change 1 (P), the task-scope planner
// decisions (TS), the CI rule that a run of 0 tests is not green (CI), and amendment A1 (A1).
//
// CLI: node scripts/redproof-extract.mjs <sha> <outDir> <testFile>...
// Green control: every test that ran passed and at least one ran. A skipped or todo test is
// outside the count and is reported on the control line.
// Exit 0 extracted and control green (stdout `control P/P`, plus ` skipped S` when S > 0);
// exit 1 control ran and is not green (stdout `control P/N`, same optional part);
// exit 2 input refused (no `control` line, control not run). A test file refused by the
// outside-extraction guards (absolute, a `..` segment, not under a listed path) puts the
// phrase `outside the extraction` on stderr; a file merely absent at the SHA does not.

import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SCRIPT = fileURLToPath(new URL('../redproof-extract.mjs', import.meta.url));

const EXPECTED_PATHS = [
  'claude-plugins/',
  'current/src/test/resources/',
  'current/docs/',
  'CHANGELOG.md',
  '.taskorchestrator/',
];

const PASS2 = "import { test } from 'node:test';\ntest('one', () => {});\ntest('two', () => {});\n";
const PASS1 = "import { test } from 'node:test';\ntest('three', () => {});\n";
const FAIL1_OF_3 =
  "import { test } from 'node:test';\nimport assert from 'node:assert/strict';\n" +
  "test('ok1', () => {});\ntest('ok2', () => {});\ntest('bad', () => { assert.equal(1, 2); });\n";
const NO_TESTS = "// a file that defines no test\nexport const x = 1;\n";
// two passing tests, one skipped, one todo that would fail if it ran as a plain test
const SKIP_AND_TODO =
  "import { test } from 'node:test';\nimport assert from 'node:assert/strict';\n" +
  "test('p1', () => {});\ntest('p2', () => {});\n" +
  "test('sk', { skip: true }, () => {});\n" +
  "test('td', { todo: true }, () => { assert.equal(1, 2); });\n";
const ONLY_SKIPPED = "import { test } from 'node:test';\ntest('sk', { skip: true }, () => {});\n";
const OUTSIDE = /outside the extraction/;

const roots = [];

function tmp(prefix) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `rpx-${prefix}-`));
  roots.push(dir);
  return dir;
}

after(() => {
  for (const r of roots) fs.rmSync(r, { recursive: true, force: true });
});

function git(cwd, ...args) {
  return execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
}

function write(repo, rel, content) {
  const p = path.join(repo, ...rel.split('/'));
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, content);
}

// Fixture: a repo with a file under each EXTRACT_PATHS entry plus files outside the set.
// `omit` lists top-level entries to leave out (S7).
function makeRepo({ omit = [], tests = {} } = {}) {
  const repo = tmp('repo');
  git(repo, 'init', '-q');
  git(repo, 'config', 'core.autocrlf', 'false');
  git(repo, 'config', 'user.name', 'Test');
  git(repo, 'config', 'user.email', 'test@example.com');
  git(repo, 'config', 'commit.gpgsign', 'false');
  const files = {
    'claude-plugins/p/plugin.txt': 'plugin v1\n',
    'current/src/test/resources/res.txt': 'resource v1\n',
    'current/docs/guide.md': 'guide v1\n',
    'CHANGELOG.md': 'changelog v1\n',
    '.taskorchestrator/config.yaml': 'cfg: v1\n',
    'README.md': 'readme\n',
    'current/src/main/A.kt': 'class A\n',
    'scripts/s.mjs': 'export const s = 1;\n',
  };
  for (const [rel, content] of Object.entries(files)) {
    if (omit.some((o) => rel === o || rel.startsWith(o))) continue;
    write(repo, rel, content);
  }
  for (const [rel, content] of Object.entries(tests)) write(repo, rel, content);
  git(repo, 'add', '-A');
  git(repo, 'commit', '-q', '-m', 'c1');
  return { repo, sha: git(repo, 'rev-parse', 'HEAD') };
}

function makeStandardRepo() {
  return makeRepo({
    tests: {
      'claude-plugins/t/a.test.mjs': PASS2,
      'claude-plugins/t/b.test.mjs': PASS1,
    },
  });
}

function run(repo, args) {
  const env = { ...process.env };
  // The script is run directly by a person or CI, never from inside another node test run.
  delete env.NODE_TEST_CONTEXT;
  const r = spawnSync(process.execPath, [SCRIPT, ...args], {
    cwd: repo,
    encoding: 'utf8',
    env,
    timeout: 90000,
  });
  return { status: r.status, stdout: r.stdout ?? '', stderr: r.stderr ?? '', all: `${r.stdout ?? ''}${r.stderr ?? ''}` };
}

// The whole control line (`control P/N` or `control P/N skipped S`), or null when there is none.
function controlLine(stdout) {
  const m = stdout.match(/^control \d+\/\d+( skipped \d+)?\s*$/m);
  return m ? m[0].trim() : null;
}

function norm(s) {
  return s.replace(/\r\n/g, '\n');
}

function listAll(dir) {
  const out = [];
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else out.push(path.relative(dir, p).split(path.sep).join('/'));
    }
  };
  walk(dir);
  return out.sort();
}

const A = 'claude-plugins/t/a.test.mjs';
const B = 'claude-plugins/t/b.test.mjs';

describe('happy path', () => {
  let fx;
  let out;
  let res;
  before(() => {
    fx = makeStandardRepo();
    out = path.join(tmp('out'), 'x');
    res = run(fx.repo, [fx.sha, out, A, B]);
  });

  test('S1 two passing files: exit 0 and control 3/3', () => {
    assert.equal(res.status, 0, res.all);
    assert.equal(controlLine(res.stdout), 'control 3/3');
  });

  test('S2 every committed file under EXTRACT_PATHS is extracted with committed content; others absent', () => {
    const expected = {
      'claude-plugins/p/plugin.txt': 'plugin v1\n',
      'current/src/test/resources/res.txt': 'resource v1\n',
      'current/docs/guide.md': 'guide v1\n',
      'CHANGELOG.md': 'changelog v1\n',
      '.taskorchestrator/config.yaml': 'cfg: v1\n',
      [A]: PASS2,
      [B]: PASS1,
    };
    for (const [rel, content] of Object.entries(expected)) {
      const p = path.join(out, ...rel.split('/'));
      assert.ok(fs.existsSync(p), `missing ${rel}`);
      assert.equal(norm(fs.readFileSync(p, 'utf8')), content, rel);
    }
    for (const rel of ['README.md', 'current/src/main/A.kt', 'scripts/s.mjs']) {
      assert.equal(fs.existsSync(path.join(out, ...rel.split('/'))), false, `${rel} must be absent`);
    }
    assert.equal(fs.existsSync(path.join(out, '.git')), false, '.git must be absent');
    assert.equal(fs.existsSync(path.join(out, 'current', 'src', 'main')), false);
  });
});

describe('S3 the SHA, not the tree', () => {
  test('extracting commit 1 yields commit-1 content; uncommitted and untracked files are absent', () => {
    const { repo, sha } = makeStandardRepo();
    write(repo, 'current/docs/guide.md', 'guide v2\n');
    git(repo, 'commit', '-q', '-am', 'c2');
    assert.notEqual(git(repo, 'rev-parse', 'HEAD'), sha);
    // dirty tree: an uncommitted edit and an untracked file in a listed dir
    write(repo, 'CHANGELOG.md', 'changelog dirty\n');
    write(repo, 'current/docs/untracked.md', 'untracked\n');
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, A]);
    assert.equal(res.status, 0, res.all);
    assert.equal(norm(fs.readFileSync(path.join(out, 'current', 'docs', 'guide.md'), 'utf8')), 'guide v1\n');
    assert.equal(norm(fs.readFileSync(path.join(out, 'CHANGELOG.md'), 'utf8')), 'changelog v1\n');
    assert.equal(fs.existsSync(path.join(out, 'current', 'docs', 'untracked.md')), false);
  });
});

describe('control gate', () => {
  test('S4 one of three tests failing: exit 1 and control 2/3', () => {
    const { repo, sha } = makeRepo({ tests: { 'claude-plugins/t/f.test.mjs': FAIL1_OF_3 } });
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, 'claude-plugins/t/f.test.mjs']);
    assert.equal(res.status, 1, res.all);
    assert.equal(controlLine(res.stdout), 'control 2/3');
  });

  test('S5 a file defining no test: exit 1 and control 0/0 (0 tests is not green)', () => {
    const { repo, sha } = makeRepo({ tests: { 'claude-plugins/t/n.test.mjs': NO_TESTS } });
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, 'claude-plugins/t/n.test.mjs']);
    assert.equal(res.status, 1, res.all);
    assert.equal(controlLine(res.stdout), 'control 0/0');
  });

  // Oracles P and CI: node itself exits 0 on skip and todo, so the green rule is the script's
  // own: a skipped or todo test, failing or not, is in neither P nor N.
  test('S15 2 passing + 1 skipped + 1 failing todo: exit 0 and control 2/2 skipped 2', () => {
    const { repo, sha } = makeRepo({ tests: { 'claude-plugins/t/st.test.mjs': SKIP_AND_TODO } });
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, 'claude-plugins/t/st.test.mjs']);
    assert.equal(res.status, 0, res.all);
    assert.equal(controlLine(res.stdout), 'control 2/2 skipped 2');
  });

  test('S16 only a skipped test: exit 1 and control 0/0 skipped 1 (0 run tests is not green)', () => {
    const { repo, sha } = makeRepo({ tests: { 'claude-plugins/t/sk.test.mjs': ONLY_SKIPPED } });
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, 'claude-plugins/t/sk.test.mjs']);
    assert.equal(res.status, 1, res.all);
    assert.equal(controlLine(res.stdout), 'control 0/0 skipped 1');
  });
});

describe('refusals (exit 2, control not run)', () => {
  test('S6 unresolvable SHA (40 hex of no object, and a non-hex word): exit 2, outDir not created', () => {
    const { repo } = makeStandardRepo();
    for (const bad of ['0123456789abcdef0123456789abcdef01234567', 'no-such-ref-anywhere']) {
      const out = path.join(tmp('out'), 'x');
      const res = run(repo, [bad, out, A]);
      assert.equal(res.status, 2, `${bad}: ${res.all}`);
      assert.equal(controlLine(res.stdout), null);
      assert.equal(fs.existsSync(out), false, `outDir must not exist for ${bad}`);
    }
  });

  test('S7 a listed path absent at the SHA: exit 2 and stderr names current/docs/', () => {
    const { repo, sha } = makeRepo({
      omit: ['current/docs/'],
      tests: { [A]: PASS2 },
    });
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, A]);
    assert.equal(res.status, 2, res.all);
    assert.match(res.stderr, /current\/docs\//);
    assert.equal(controlLine(res.stdout), null);
  });

  test('S8 outDir exists and is non-empty: exit 2, its file unchanged, nothing extracted', () => {
    const { repo, sha } = makeStandardRepo();
    const out = tmp('out');
    fs.writeFileSync(path.join(out, 'keep.txt'), 'precious\n');
    const res = run(repo, [sha, out, A]);
    assert.equal(res.status, 2, res.all);
    assert.equal(controlLine(res.stdout), null);
    assert.equal(fs.readFileSync(path.join(out, 'keep.txt'), 'utf8'), 'precious\n');
    assert.deepEqual(listAll(out), ['keep.txt']);
  });

  test('S8 a second run into the same outDir is refused', () => {
    const { repo, sha } = makeStandardRepo();
    const out = path.join(tmp('out'), 'x');
    assert.equal(run(repo, [sha, out, A]).status, 0);
    const before = listAll(out);
    const second = run(repo, [sha, out, A]);
    assert.equal(second.status, 2, second.all);
    assert.deepEqual(listAll(out), before);
  });

  test('S9 missing testFile or missing outDir argument: exit 2', () => {
    const { repo, sha } = makeStandardRepo();
    const out = path.join(tmp('out'), 'x');
    const noTest = run(repo, [sha, out]);
    assert.equal(noTest.status, 2, noTest.all);
    assert.equal(fs.existsSync(path.join(out, 'CHANGELOG.md')), false);
    const noOut = run(repo, [sha]);
    assert.equal(noOut.status, 2, noOut.all);
    const noArgs = run(repo, []);
    assert.equal(noArgs.status, 2, noArgs.all);
    for (const r of [noTest, noOut, noArgs]) assert.equal(controlLine(r.stdout), null);
  });

  test('S10 a testFile absent at the SHA: exit 2 naming it, not as outside the extraction', () => {
    const { repo, sha } = makeStandardRepo();
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, 'claude-plugins/t/missing.test.mjs']);
    assert.equal(res.status, 2, res.all);
    assert.match(res.all, /claude-plugins\/t\/missing\.test\.mjs/);
    assert.doesNotMatch(res.stderr, OUTSIDE);
    assert.equal(controlLine(res.stdout), null);
  });

  test('S11 a testFile outside the extraction (absolute live-repo path): exit 2', () => {
    const { repo, sha } = makeStandardRepo();
    const live = path.join(tmp('live'), 'live.test.mjs');
    fs.writeFileSync(live, PASS1);
    // the live file passes on its own, so a control that ran it would be green
    const alone = spawnSync(process.execPath, ['--test', live], {
      encoding: 'utf8',
      env: Object.fromEntries(Object.entries(process.env).filter(([k]) => k !== 'NODE_TEST_CONTEXT')),
    });
    assert.equal(alone.status, 0, 'fixture precondition: the live test passes by itself');
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, live]);
    assert.equal(res.status, 2, res.all);
    assert.match(res.stderr, OUTSIDE);
    assert.equal(controlLine(res.stdout), null);
  });

  test('S11 a testFile outside the extraction (../x.test.mjs): exit 2', () => {
    const { repo, sha } = makeStandardRepo();
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, '../x.test.mjs']);
    assert.equal(res.status, 2, res.all);
    assert.match(res.stderr, OUTSIDE);
    assert.equal(controlLine(res.stdout), null);
  });

  // Oracle A1: a file committed in the repo but not under a listed path exists at the SHA and is
  // not in the extraction; without the listed-path guard it would be run in the extraction,
  // found absent there, and the control would not be green (exit 1) instead of a refusal.
  test('S14 a passing testFile in the tree but not under an extracted path: exit 2, outside the extraction', () => {
    const { repo, sha } = makeRepo({ tests: { 'scripts/t/x.test.mjs': PASS1 } });
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, 'scripts/t/x.test.mjs']);
    assert.equal(res.status, 2, res.all);
    assert.match(res.stderr, OUTSIDE);
    assert.equal(controlLine(res.stdout), null);
    assert.equal(fs.existsSync(path.join(out, 'CHANGELOG.md')), false, 'nothing may be extracted');
  });
});

describe('edges and probes', () => {
  test('S12 outDir absent is created; outDir existing and empty is accepted', () => {
    const { repo, sha } = makeStandardRepo();
    const absent = path.join(tmp('out'), 'nested', 'x');
    const r1 = run(repo, [sha, absent, A]);
    assert.equal(r1.status, 0, r1.all);
    assert.ok(fs.existsSync(path.join(absent, 'CHANGELOG.md')));
    const empty = tmp('empty');
    const r2 = run(repo, [sha, empty, A, B]);
    assert.equal(r2.status, 0, r2.all);
    assert.equal(controlLine(r2.stdout), 'control 3/3');
  });

  test('S13 EXTRACT_PATHS deep-equals the planned list, is frozen; importing prints and spawns nothing', () => {
    const cwd = tmp('import');
    const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => k !== 'NODE_TEST_CONTEXT'));
    const code =
      `const m = await import(${JSON.stringify(pathToFileURL(SCRIPT).href)});` +
      `process.stdout.write('@@' + JSON.stringify({p: m.EXTRACT_PATHS, f: Object.isFrozen(m.EXTRACT_PATHS), a: Array.isArray(m.EXTRACT_PATHS), r: typeof m.run}) + '@@');`;
    const r = spawnSync(process.execPath, ['--input-type=module', '-e', code], {
      cwd,
      encoding: 'utf8',
      env,
      timeout: 30000,
    });
    assert.equal(r.status, 0, r.stderr);
    // nothing but our own marker on stdout: the import itself printed nothing
    const m = r.stdout.match(/^@@(.*)@@$/s);
    assert.ok(m, `unexpected stdout: ${JSON.stringify(r.stdout)}`);
    assert.equal(r.stderr, '');
    const info = JSON.parse(m[1]);
    assert.equal(info.a, true);
    assert.equal(info.f, true);
    assert.deepEqual(info.p, EXPECTED_PATHS);
    assert.equal(info.r, 'function');
    // spawned nothing observable: the empty non-repo cwd gained no files
    assert.deepEqual(fs.readdirSync(cwd), []);
  });

  test('probe: a short SHA prefix and a branch name are accepted', () => {
    const { repo, sha } = makeStandardRepo();
    git(repo, 'branch', '-M', 'main');
    const short = sha.slice(0, 10);
    const r1 = run(repo, [short, path.join(tmp('out'), 'x'), A, B]);
    assert.equal(r1.status, 0, r1.all);
    assert.equal(controlLine(r1.stdout), 'control 3/3');
    const r2 = run(repo, ['main', path.join(tmp('out'), 'x'), A, B]);
    assert.equal(r2.status, 0, r2.all);
    assert.equal(controlLine(r2.stdout), 'control 3/3');
  });

  test('probe: a trailing slash on outDir is accepted', () => {
    const { repo, sha } = makeStandardRepo();
    const out = path.join(tmp('out'), 'x') + '/';
    const res = run(repo, [sha, out, A]);
    assert.equal(res.status, 0, res.all);
    assert.ok(fs.existsSync(path.join(out, 'CHANGELOG.md')));
  });

  test('probe: autocrlf true in the host git does not change archived bytes (LF-normalized compare)', () => {
    const { repo, sha } = makeStandardRepo();
    git(repo, 'config', 'core.autocrlf', 'true');
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, A]);
    assert.equal(res.status, 0, res.all);
    assert.equal(norm(fs.readFileSync(path.join(out, 'CHANGELOG.md'), 'utf8')), 'changelog v1\n');
  });

  // A backslash is a separator on Windows and a name character on Linux: assert by platform.
  // win32: it names claude-plugins/t/a.test.mjs (2 passing tests); elsewhere it names no file.
  test('probe: a backslash in a testFile is a separator on win32 and a name character elsewhere', () => {
    const { repo, sha } = makeStandardRepo();
    const out = path.join(tmp('out'), 'x');
    const res = run(repo, [sha, out, 'claude-plugins\\t\\a.test.mjs']);
    if (process.platform === 'win32') {
      assert.equal(res.status, 0, res.all);
      assert.equal(controlLine(res.stdout), 'control 2/2');
    } else {
      assert.equal(res.status, 2, res.all);
      assert.equal(controlLine(res.stdout), null);
    }
  });
});
