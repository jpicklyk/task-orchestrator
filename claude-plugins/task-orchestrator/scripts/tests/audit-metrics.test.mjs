// Tests for scripts/audit-metrics.mjs (contract audit/metrics-v1) and
// scripts/lib/audit-metrics/languages.mjs.
//
// Oracles: the frozen contract C1 (plans/audit-upgrade.md) and the task-scope
// rules R1-R14, each expected value hand-computed on the fixture below it.
// Fixtures are temp git repositories with pinned dates and an empty global
// git config; the CLI is driven through spawnSync(process.execPath).
// Source is ASCII only (non-ASCII file names are written as escapes).

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  readFileSync,
  existsSync,
  rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadLanguageTable } from '../lib/audit-metrics/languages.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const scriptsDir = path.resolve(here, '..');
const CLI = path.join(scriptsDir, 'audit-metrics.mjs');
const SHIPPED_TABLE = path.join(scriptsDir, 'lib', 'audit-metrics', 'languages.json');

const workRoot = mkdtempSync(path.join(tmpdir(), 'audit-metrics-test-'));
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

const day = (d) => `${d}T12:00:00+00:00`;

function git(dir, args, env = baseEnv) {
  const r = spawnSync('git', ['-C', dir, ...args], { env, encoding: 'utf8' });
  if (r.status !== 0) {
    throw new Error(`git ${args.join(' ')} failed: ${r.stderr}`);
  }
  return r.stdout;
}

// commits: [{date: 'YYYY-MM-DD', files: {relPath: string | Buffer | null (delete)}}]
function makeRepo(name, commits) {
  const dir = path.join(workRoot, name);
  mkdirSync(dir, { recursive: true });
  git(dir, ['init', '-q']);
  const shas = [];
  for (const c of commits) {
    for (const [rel, content] of Object.entries(c.files)) {
      const abs = path.join(dir, ...rel.split('/'));
      if (content === null) {
        rmSync(abs);
      } else {
        mkdirSync(path.dirname(abs), { recursive: true });
        writeFileSync(abs, content);
      }
    }
    const env = {
      ...baseEnv,
      GIT_AUTHOR_DATE: day(c.date),
      GIT_COMMITTER_DATE: day(c.date),
    };
    git(dir, ['add', '-A'], env);
    git(dir, ['commit', '-q', '--no-gpg-sign', '-m', 'fixture commit'], env);
    shas.push(git(dir, ['rev-parse', 'HEAD']).trim());
  }
  return { dir, shas };
}

const memo = new Map();
const once = (key, build) => {
  if (!memo.has(key)) memo.set(key, build());
  return memo.get(key);
};

function runCli(args, options = {}) {
  return spawnSync(process.execPath, [CLI, ...args], {
    env: baseEnv,
    encoding: 'utf8',
    cwd: options.cwd,
    timeout: 120000,
  });
}

let outSeq = 0;
function nextOut() {
  outSeq += 1;
  return path.join(workRoot, 'out', `m${outSeq}.json`);
}

// Runs the CLI on repo with flags; throws unless exit 0; returns parsed JSON and raw text.
function metrics(repo, opts = {}) {
  const out = nextOut();
  const args = [
    '--root', opts.root ?? repo.dir,
    '--paths', opts.paths ?? '.',
    '--since', opts.since ?? '2026-01-01',
    '--recent', opts.recent ?? '2026-06-01',
    '--out', out,
    ...(opts.extra ?? []),
  ];
  const r = runCli(args, { cwd: opts.cwd });
  if (r.status !== 0) {
    throw new Error(`audit-metrics exited ${r.status}: ${r.stderr}`);
  }
  const text = readFileSync(out, 'utf8');
  return { json: JSON.parse(text), text, out, r };
}

const byPath = (json) => Object.fromEntries(json.files.map((f) => [f.path, f]));
const paths = (rows) => rows.map((r) => r.path);

// A three-line indented body: loc 3, indentation levels 0,1,2 (unit 2) -> indentComplexity 3, maxNesting 2.
const body = (name, rev) => `${name}\n  child ${rev}\n    grandchild\n`;

const TOP_LEVEL_KEYS = [
  'contract', 'root', 'paths', 'window', 'generatedFrom', 'files', 'repoHotSet',
  'hotSet', 'coupling', 'trend', 'modules', 'languages', 'notes',
];
const FILE_KEYS = [
  'path', 'lang', 'loc', 'churn', 'churnRecent', 'indentComplexity', 'maxNesting',
  'branchComplexity', 'topFunctions', 'hotspotScore',
];

// ---------------------------------------------------------------- fixtures

function buildWindow() {
  return makeRepo('window', [
    { date: '2025-12-31', files: { 'f.txt': body('f', 0), 'g.txt': body('g', 0) } },
    { date: '2026-01-01', files: { 'f.txt': body('f', 1) } },
    { date: '2026-03-05', files: { 'f.txt': body('f', 2) } },
    { date: '2026-06-01', files: { 'f.txt': body('f', 3), 'h.txt': body('h', 0) } },
    { date: '2026-06-02', files: { 'f.txt': body('f', 4) } },
  ]);
}

function buildBulk() {
  return makeRepo('bulk', [
    {
      date: '2026-01-02',
      files: {
        'a.txt': body('a', 0), 'b.txt': body('b', 0), 'c.txt': body('c', 0),
        'e.txt': body('e', 0), 'f.txt': body('f', 0), 'gone.txt': body('gone', 0),
        'package-lock.json': '{"v":0}\n',
      },
    },
    {
      date: '2026-01-10',
      files: {
        'a.txt': body('a', 1), 'b.txt': body('b', 1), 'c.txt': body('c', 1),
        'e.txt': body('e', 1), 'gone.txt': body('gone', 1),
      },
    },
    {
      date: '2026-01-20',
      files: {
        'a.txt': body('a', 2), 'b.txt': body('b', 2), 'e.txt': body('e', 2),
        'package-lock.json': '{"v":1}\n',
      },
    },
    { date: '2026-02-01', files: { 'a.txt': body('a', 3), 'b.txt': body('b', 3) } },
    { date: '2026-02-10', files: { 'a.txt': body('a', 4), 'c.txt': body('c', 4) } },
    { date: '2026-02-20', files: { 'gone.txt': null, 'a.txt': body('a', 5) } },
  ]);
}

const KOTLIN_LINES = [
  '/* header if && while',
  '   more header for */',
  'package ex',
  '',
  '// when && catch',
  'fun add(a: Int, b: Int): Int {',
  '    if (a > 0 && b > 0) {',
  '        return a + b',
  '    }',
  '    return 0',
  '}',
  '',
  'fun twice(x: Int) = x * 2',
];
const KOTLIN_LF = `${KOTLIN_LINES.join('\n')}\n`;
const KOTLIN_CRLF = `${KOTLIN_LINES.join('\r\n')}\r\n`;

const PYTHON_TEXT = [
  '# if and or decoy',
  'def alpha(x):',
  '    if x and x > 1:',
  '        for i in range(x):',
  '            x -= i',
  '    color = x + 1',
  '    return color',
  '',
  'async def beta(y):',
  '    return y or 0',
  '',
].join('\n');

const UNKNOWN_TEXT = ['alpha', '  beta if for', '    gamma', '  delta', '# hash', ''].join('\n');

function buildLangs() {
  return makeRepo('langs', [
    {
      date: '2026-02-01',
      files: {
        'K.kt': KOTLIN_LF,
        'p.py': PYTHON_TEXT,
        'data.zz': UNKNOWN_TEXT,
        'Other.ZZ': 'X\n  y\n',
        Makefile: 'all:\n\techo hi\n',
        'blob.dat': Buffer.from([0x61, 0x00, 0x62, 0x0a]),
      },
    },
  ]);
}

function buildProbe() {
  const noise = {
    'CHANGELOG.md': 'x\n  y\n',
    'CHANGES.md': 'x\n  y\n',
    'HISTORY.md': 'x\n  y\n',
    'package-lock.json': '{\n  "a": 1\n}\n',
    'yarn.lock': 'x\n  y\n',
    'deps.lock': 'x\n  y\n',
    'app.min.js': 'x\n  y\n',
    's.min.css': 'x\n  y\n',
    'a.js.map': 'x\n  y\n',
    'node_modules/pkg/i.js': 'x\n  y\n',
    'dist/out.js': 'x\n  y\n',
    'vendor/v.js': 'x\n  y\n',
    'generated/g.kt': 'x\n  y\n',
    'lib/build/o.txt': 'x\n  y\n',
    'tgt/target/t.txt': 'x\n  y\n',
    '.gradle/c.txt': 'x\n  y\n',
    '__pycache__/m.txt': 'x\n  y\n',
    'out/o.txt': 'x\n  y\n',
  };
  const files = {
    ...noise,
    'tabs/mixed.zz': 'a\n\tb\n    c\n',
    'tabs/tab.zz': 'a\n\tb\n\t\tc\n',
    'tabs/two.zz': 'a\n  b\n    c\n',
    'tabs/four.zz': 'a\n    b\n        c\n',
    'eol/lf.kt': KOTLIN_LF,
    'eol/crlf.kt': KOTLIN_CRLF,
    'cmt/tail.kt': [
      '/* open if',
      '        close */ val x = 1',
      'val y = 2 /* start',
      'still when comment */',
      'val z = 3',
      '',
    ].join('\n'),
    'nest/nested.kt': [
      'fun outer(a: Int): Int {',
      '    fun inner(b: Int): Int {',
      '        return if (b > 0) b else 0',
      '    }',
      '    return inner(a)',
      '}',
      '',
    ].join('\n'),
    'rb/foo.rb': [
      'class Foo',
      '  def bar(x)',
      '    if x',
      '      1',
      '    end',
      '  end',
      'end',
      '',
    ].join('\n'),
    'docs/my file.txt': body('space', 0),
    'docs/\u00fcn\u00ef.txt': body('unicode', 0),
    'bin/blob.dat': Buffer.from([0x00, 0x61, 0x0a]),
    'bin/edge.dat': Buffer.concat([Buffer.alloc(7999, 0x61), Buffer.from([0x00]), Buffer.from('z\n')]),
    'bin/late.dat': Buffer.concat([Buffer.alloc(8000, 0x61), Buffer.from([0x00]), Buffer.from('z\n')]),
  };
  return makeRepo('probe', [
    { date: '2026-02-01', files },
    {
      date: '2026-02-02',
      files: {
        'docs/my file.txt': body('space', 1),
        'docs/\u00fcn\u00ef.txt': body('unicode', 1),
      },
    },
  ]);
}

function buildScope() {
  return makeRepo('scope', [
    {
      date: '2026-02-01',
      files: {
        'src/a.txt': body('a', 0), 'src/sub/c.txt': body('c', 0),
        'docs/b.txt': body('b', 0), 'docs/d.txt': body('d', 0),
      },
    },
    { date: '2026-02-02', files: { 'docs/b.txt': body('b', 1), 'docs/d.txt': body('d', 1) } },
    { date: '2026-02-03', files: { 'docs/b.txt': body('b', 2) } },
  ]);
}

function buildBoundary() {
  // round r touches every file whose target churn is >= r: churns f1=1 f2=2 f3=6 f4=7 f5=8
  const target = { 'f1.txt': 1, 'f2.txt': 2, 'f3.txt': 6, 'f4.txt': 7, 'f5.txt': 8 };
  const commits = [];
  for (let r = 1; r <= 8; r += 1) {
    const files = {};
    for (const [name, n] of Object.entries(target)) {
      if (n >= r) files[name] = body(name.replace('.txt', ''), r);
    }
    commits.push({ date: `2026-02-0${r}`, files });
  }
  return makeRepo('boundary', commits);
}

function buildTrend() {
  const children = (n) => `  child\n`.repeat(n);
  const commits = [];
  for (let j = 0; j < 7; j += 1) {
    const files = { 't.txt': `t\n${children(j + 1)}` };
    if (j === 0 || j === 3 || j === 6) {
      const i = j / 3;
      files['u.txt'] = `u\n${children(i + 1)}`;
    }
    commits.push({ date: `2026-03-0${j + 1}`, files });
  }
  return makeRepo('trend', commits);
}

function buildJsCycle() {
  return makeRepo('jscycle', [
    {
      date: '2026-02-01',
      files: {
        'src/x/a.mjs': "import { b } from '../y/b.mjs';\nexport const a = b + 1;\n",
        'src/y/b.mjs':
          "import { a } from '../x/a.mjs';\nimport { c } from '../z/c.mjs';\nexport const b = a + c;\n",
        'src/z/c.mjs':
          "import fs from 'node:fs';\nimport m from './missing.mjs';\nexport const c = fs && m;\n",
      },
    },
  ]);
}

function buildKotlinCycle() {
  return makeRepo('ktcycle', [
    {
      date: '2026-02-01',
      files: {
        'kt/src/com/ex/q/A.kt': [
          'package com.ex.q',
          '',
          'import com.ex.r.B',
          'import com.ex.r.*',
          'import kotlin.collections.List',
          '',
          'class A(val b: B, val l: List<Int>)',
          '',
        ].join('\n'),
        'kt/src/com/ex/r/B.kt': [
          'package com.ex.r',
          '',
          'import com.ex.q.A',
          '',
          'class B(val a: A?)',
          '',
        ].join('\n'),
        'kt/src/com/ex/q/deep/er/C.kt': 'package com.ex.q.deep.er\n\nclass C\n',
      },
    },
  ]);
}

function buildGraph() {
  return makeRepo('graph', [
    {
      date: '2026-02-01',
      files: {
        'src/m1/i.js':
          "import a from '../m2/i.js';\nimport a2 from '../m2/i.js';\nexport default a + a2;\n",
        'src/m2/i.js': "import b from '../m3/i.js';\nexport default b;\n",
        'src/m3/i.js': "import c from '../m1/i.js';\nexport default c;\n",
        'src/m4/i.js': "import d from '../m5/i.js';\nexport default d;\n",
        'src/m5/i.js': "import e from '../m4/i.js';\nexport default e;\n",
        'src/m6/i.js': "import s from './j.js';\nexport default s;\n",
        'src/m6/j.js': 'export default 1;\n',
        'src/m6/k.js': "import k from './k.js';\nexport default k;\n",
        'src/m7/i.js': "import x from '../m1/i.js';\nexport default x;\n",
      },
    },
  ]);
}

function buildRel() {
  return makeRepo('rel', [
    {
      date: '2026-02-01',
      files: {
        'src/x/a.js': [
          "import b from '../y/b';",
          "import z from '../z';",
          "import u from '../../lib/u';",
          "import n from '../../lib/nothere';",
          'export default b + z + u + n;',
          '',
        ].join('\n'),
        'src/y/b.js': 'export default 1;\n',
        'src/z/index.js': 'export default 2;\n',
        'lib/u.js': 'export default 3;\n',
      },
    },
  ]);
}

function buildWorkingTree() {
  const repo = makeRepo('wt', [
    {
      date: '2026-02-01',
      files: { 'a.txt': 'a\n  b\n', 'gone-wt.txt': 'g\n  h\n' },
    },
  ]);
  writeFileSync(path.join(repo.dir, 'a.txt'), 'a\n  b\n  c\n  d\n');
  rmSync(path.join(repo.dir, 'gone-wt.txt'));
  writeFileSync(path.join(repo.dir, 'u.txt'), 'u\n  v\n');
  return repo;
}

// ------------------------------------------------------------------- tests

test('S1: churn and churnRecent use inclusive day bounds (R3)', () => {
  const repo = once('window', buildWindow);
  const { json } = metrics(repo, { since: '2026-01-01', recent: '2026-06-01' });
  assert.equal(json.contract, 'audit/metrics-v1');
  assert.deepEqual(json.paths, ['.']);
  assert.deepEqual(json.window, { since: '2026-01-01', recent: '2026-06-01' });
  assert.deepEqual(json.generatedFrom, { headSha: repo.shas[4] });
  const f = byPath(json);
  // f.txt: commits 2026-01-01, 03-05, 06-01, 06-02 in window; 2025-12-31 is the day before and out.
  assert.equal(f['f.txt'].churn, 4);
  // recent bound day 06-01 is in, so 06-01 and 06-02.
  assert.equal(f['f.txt'].churnRecent, 2);
  // g.txt was only touched the day before the window opens.
  assert.equal(f['g.txt'].churn, 0);
  assert.equal(f['g.txt'].churnRecent, 0);
  // h.txt: one commit exactly on the recent bound day.
  assert.equal(f['h.txt'].churn, 1);
  assert.equal(f['h.txt'].churnRecent, 1);
  // R8, N = 3: f 3/3, h 2/3 -> 0.6667 (4 decimals), g churn 0 -> 0; complexity rank is 1 for all.
  assert.equal(f['f.txt'].hotspotScore, 1);
  assert.equal(f['h.txt'].hotspotScore, 0.6667);
  assert.equal(f['g.txt'].hotspotScore, 0);
  assert.deepEqual(paths(json.repoHotSet), ['f.txt', 'h.txt']);
});

test('S2: bulk commits count toward churn but not coupling; exactly max is not bulk (R2, R3, R10)', () => {
  const repo = once('bulk', buildBulk);
  const { json } = metrics(repo, { extra: ['--max-commit-files', '4'] });
  // noise (package-lock.json) and the file deleted before HEAD are not in the universe
  assert.deepEqual(paths(json.files), ['a.txt', 'b.txt', 'c.txt', 'e.txt', 'f.txt']);
  const f = byPath(json);
  assert.deepEqual(
    ['a.txt', 'b.txt', 'c.txt', 'e.txt', 'f.txt'].map((p) => f[p].churn),
    [6, 4, 3, 3, 1],
  );
  // N = 5 and every file has indentComplexity 3: score = churn rank only.
  assert.deepEqual(
    ['a.txt', 'b.txt', 'c.txt', 'e.txt', 'f.txt'].map((p) => f[p].hotspotScore),
    [1, 0.8, 0.6, 0.6, 0.2],
  );
  const row = (p, churn, score) => ({
    path: p, churn, churnRecent: 0, indentComplexity: 3, hotspotScore: score,
  });
  const rows = [
    row('a.txt', 6, 1), row('b.txt', 4, 0.8), row('c.txt', 3, 0.6),
    row('e.txt', 3, 0.6), row('f.txt', 1, 0.2),
  ];
  assert.deepEqual(json.repoHotSet, rows);
  // R = churn at index 2 of [1,3,3,4,6] = 3; every churn x 3 >= 3
  assert.deepEqual(json.hotSet, rows);
  assert.deepEqual(json.coupling, [
    { path: 'a.txt', commits: 4, partners: [{ path: 'b.txt', count: 2 }, { path: 'c.txt', count: 1 }, { path: 'e.txt', count: 1 }] },
    { path: 'b.txt', commits: 2, partners: [{ path: 'a.txt', count: 2 }, { path: 'e.txt', count: 1 }] },
    { path: 'c.txt', commits: 1, partners: [{ path: 'a.txt', count: 1 }] },
    { path: 'e.txt', commits: 1, partners: [{ path: 'a.txt', count: 1 }, { path: 'b.txt', count: 1 }] },
    { path: 'f.txt', commits: 0, partners: [] },
  ]);
  // R14: a note counts the 2 bulk commits excluded from coupling
  assert.ok(json.notes.some((n) => /bulk/i.test(n) && /\b2\b/.test(n)), JSON.stringify(json.notes));
});

test('S2b: the default --max-commit-files (40) keeps every commit in coupling', () => {
  const repo = once('bulk', buildBulk);
  const { json } = metrics(repo);
  const a = json.coupling.find((c) => c.path === 'a.txt');
  assert.deepEqual(a, {
    path: 'a.txt',
    commits: 6,
    partners: [
      { path: 'b.txt', count: 4 },
      { path: 'c.txt', count: 3 },
      { path: 'e.txt', count: 3 },
      { path: 'f.txt', count: 1 },
    ],
  });
});

test('S3: Kotlin file gives the documented fields (R4-R7)', () => {
  const repo = once('langs', buildLangs);
  const { json } = metrics(repo);
  const k = byPath(json)['K.kt'];
  assert.match(k.lang, /kotlin/i);
  assert.equal(k.loc, 8);
  assert.equal(k.indentComplexity, 5);
  assert.equal(k.maxNesting, 2);
  // only line 7 holds tokens (if, &&); decoys in the two comment forms are excluded
  assert.equal(k.branchComplexity, 2);
  assert.deepEqual(k.topFunctions, [
    { name: 'add', line: 6, length: 6, branches: 2 },
    { name: 'twice', line: 13, length: 1, branches: 0 },
  ]);
  assert.deepEqual(Object.keys(k), FILE_KEYS);
});

test('S4: Python file gives the documented fields; for is not or (R4-R7)', () => {
  const repo = once('langs', buildLangs);
  const { json } = metrics(repo);
  const p = byPath(json)['p.py'];
  assert.match(p.lang, /python/i);
  assert.equal(p.loc, 8);
  assert.equal(p.indentComplexity, 9);
  assert.equal(p.maxNesting, 3);
  // if, and, for, or; 'color' and 'for' contain 'or' but not as a word
  assert.equal(p.branchComplexity, 4);
  assert.deepEqual(p.topFunctions, [
    { name: 'alpha', line: 2, length: 6, branches: 3 },
    { name: 'beta', line: 9, length: 2, branches: 1 },
  ]);
});

test('S5: unknown extension gets indentation only; binary skipped; extensions counted (R1, R13)', () => {
  const repo = once('langs', buildLangs);
  const { json } = metrics(repo);
  const f = byPath(json);
  const z = f['data.zz'];
  assert.equal(z.lang, null);
  assert.equal(z.branchComplexity, null);
  assert.equal(z.topFunctions, null);
  // no comment lines for an unknown language: all 5 non-blank lines count
  assert.equal(z.loc, 5);
  assert.equal(z.indentComplexity, 4);
  assert.equal(z.maxNesting, 2);
  const mk = f.Makefile;
  assert.equal(mk.lang, null);
  assert.equal(mk.loc, 2);
  assert.equal(mk.indentComplexity, 1);
  assert.equal(f['blob.dat'], undefined, 'NUL-byte file must be skipped');
  assert.deepEqual(json.languages.recognised, { '.kt': 1, '.py': 1 });
  assert.deepEqual(json.languages.unrecognised, { '(none)': 1, '.zz': 2 });
  for (const group of [json.languages.recognised, json.languages.unrecognised]) {
    assert.deepEqual(Object.keys(group), [...Object.keys(group)].sort());
  }
});

test('S6: relative imports roll up to modules; Tarjan finds the 2-cycle (R12)', () => {
  const repo = once('jscycle', buildJsCycle);
  const { json } = metrics(repo, { paths: 'src', extra: ['--module-depth', '1'] });
  const m = json.modules;
  assert.equal(m.depth, 1);
  assert.deepEqual(m.edges, [
    { from: 'src/x', to: 'src/y', count: 1 },
    { from: 'src/y', to: 'src/x', count: 1 },
    { from: 'src/y', to: 'src/z', count: 1 },
  ]);
  assert.deepEqual(m.nodes, [
    { id: 'src/x', files: 1, loc: 2, fanIn: 1, fanOut: 1, instability: 0.5 },
    { id: 'src/y', files: 1, loc: 3, fanIn: 1, fanOut: 2, instability: 0.667 },
    { id: 'src/z', files: 1, loc: 3, fanIn: 1, fanOut: 0, instability: 0 },
  ]);
  assert.deepEqual(m.cycles, [['src/x', 'src/y']]);
  // node:fs and ./missing.mjs
  assert.equal(m.unresolved, 2);
});

test('S7: Kotlin package imports resolve by path suffix; default depth 2 (R12)', () => {
  const repo = once('ktcycle', buildKotlinCycle);
  const { json } = metrics(repo, { paths: 'kt' });
  const m = json.modules;
  assert.equal(m.depth, 2);
  assert.deepEqual(m.cycles, [['kt/src/com/ex/q', 'kt/src/com/ex/r']]);
  // kotlin.collections.List matches no file anywhere
  assert.equal(m.unresolved, 1);
  // com.ex.r.B and com.ex.r.* (trailing star dropped) both resolve to module r
  assert.deepEqual(m.edges, [
    { from: 'kt/src/com/ex/q', to: 'kt/src/com/ex/r', count: 2 },
    { from: 'kt/src/com/ex/r', to: 'kt/src/com/ex/q', count: 1 },
  ]);
  // C.kt sits 3 directories below the base: only the first 2 segments below it name the module
  assert.deepEqual(m.nodes, [
    { id: 'kt/src/com/ex/q', files: 1, loc: 5, fanIn: 1, fanOut: 1, instability: 0.5 },
    { id: 'kt/src/com/ex/q/deep', files: 1, loc: 2, fanIn: 0, fanOut: 0, instability: 0 },
    { id: 'kt/src/com/ex/r', files: 1, loc: 3, fanIn: 1, fanOut: 1, instability: 0.5 },
  ]);
});

test('S8: same input gives byte-identical JSON in the C1 key order (R13)', () => {
  const repo = once('bulk', buildBulk);
  const first = metrics(repo, { extra: ['--max-commit-files', '4'] });
  const second = metrics(repo, { extra: ['--max-commit-files', '4'] });
  assert.notEqual(first.out, second.out);
  assert.deepEqual(readFileSync(first.out), readFileSync(second.out));
  assert.equal(first.text, `${JSON.stringify(first.json, null, 2)}\n`);
  assert.deepEqual(Object.keys(first.json), TOP_LEVEL_KEYS);
  assert.deepEqual(Object.keys(first.json.files[0]), FILE_KEYS);
});

test('S9: a --paths entry without files gives a note naming it; others still scoped (R14)', () => {
  const repo = once('scope', buildScope);
  const { json } = metrics(repo, { paths: 'src,nopath' });
  assert.deepEqual(paths(json.files), ['src/a.txt', 'src/sub/c.txt']);
  assert.ok(json.notes.some((n) => n.includes('nopath')), JSON.stringify(json.notes));
});

test('S9b: scores are normalised over the whole repository; hot sets follow R9', () => {
  const repo = once('scope', buildScope);
  const { json } = metrics(repo, { paths: 'src' });
  // N = 4: churn a 1, c 1, d 2, b 3 -> a and c rank 2/4 = 0.5 (a scoped-only N would give 1)
  const f = byPath(json);
  assert.equal(f['src/a.txt'].hotspotScore, 0.5);
  assert.equal(f['src/sub/c.txt'].hotspotScore, 0.5);
  assert.deepEqual(paths(json.repoHotSet), ['docs/b.txt', 'docs/d.txt', 'src/a.txt', 'src/sub/c.txt']);
  // R = churn at index 1 of [1,1,2,3] = 1: both scoped files pass churn x 3 >= 1
  assert.deepEqual(paths(json.hotSet), ['src/a.txt', 'src/sub/c.txt']);
});

test('S9c: duplicate and overlapping --paths entries list each file once', () => {
  const repo = once('scope', buildScope);
  const { json } = metrics(repo, { paths: 'src,src/sub,src' });
  assert.deepEqual(paths(json.files), ['src/a.txt', 'src/sub/c.txt']);
});

test('S10: usage errors exit 2 with a stderr message and write no --out (R14)', () => {
  const repo = once('window', buildWindow);
  const plain = path.join(workRoot, 'plain');
  mkdirSync(plain, { recursive: true });
  const valid = { root: repo.dir, paths: '.', since: '2026-01-01', recent: '2026-06-01' };
  const cases = [
    // [label, one change from the valid call, the flag the reason line must name]
    ['missing --root', { root: null }, /--root/],
    ['missing --paths', { paths: null }, /--paths/],
    ['missing --since', { since: null }, /--since/],
    ['missing --recent', { recent: null }, /--recent/],
    ['missing --out', { out: null }, /--out/],
    ['month 13', { since: '2026-13-01' }, /--since/],
    ['day 30 of February', { since: '2026-02-30' }, /--since/],
    ['unpadded date', { recent: '2026-6-1' }, /--recent/],
    ['not a date', { since: 'tomorrow' }, /--since/],
    ['recent before since', { since: '2026-06-01', recent: '2026-05-31' }, /--recent/],
    ['--top 0', { extra: ['--top', '0'] }, /--top/],
    ['--top -1', { extra: ['--top', '-1'] }, /--top/],
    ['--top 1.5', { extra: ['--top', '1.5'] }, /--top/],
    ['--top abc', { extra: ['--top', 'abc'] }, /--top/],
    ['--module-depth 0', { extra: ['--module-depth', '0'] }, /--module-depth/],
    ['--max-commit-files 0', { extra: ['--max-commit-files', '0'] }, /--max-commit-files/],
    ['root that is not a git work tree', { root: plain }, /--root/],
    ['root that does not exist', { root: path.join(workRoot, 'does-not-exist') }, /--root/],
  ];
  cases.forEach(([label, change, flag], i) => {
    const out = path.join(workRoot, 'usage', String(i), 'm.json');
    const cfg = { ...valid, out, ...change };
    const args = [];
    for (const key of ['root', 'paths', 'since', 'recent', 'out']) {
      if (cfg[key] !== null) args.push(`--${key}`, cfg[key]);
    }
    args.push(...(change.extra ?? []));
    const r = runCli(args);
    assert.equal(r.status, 2, `${label}: exit ${r.status}, stderr ${r.stderr}`);
    // The reason is the stderr text outside the usage line: the usage line names every flag,
    // so matching the whole of stderr would accept any rejection for any case.
    const reason = r.stderr.split(/\r?\n/).filter((l) => l.trim() && !/^usage:/i.test(l.trim())).join('\n');
    assert.match(reason, flag, `${label}: reason does not name the flag, stderr ${r.stderr}`);
    assert.equal(existsSync(out), false, `${label}: --out was written`);
  });
});

test('S10b: accepted boundary values and parent directories of --out are created', () => {
  const repo = once('window', buildWindow);
  const out = path.join(workRoot, 'deep', 'er', 'm.json');
  const r = runCli([
    '--root', repo.dir, '--paths', '.', '--since', '2026-06-01', '--recent', '2026-06-01',
    '--out', out, '--top', '1', '--module-depth', '1', '--max-commit-files', '1',
  ]);
  assert.equal(r.status, 0, r.stderr);
  const json = JSON.parse(readFileSync(out, 'utf8'));
  assert.deepEqual(json.window, { since: '2026-06-01', recent: '2026-06-01' });
  // Long window = recent window = 2026-06-01 on: f.txt has 2 commits (06-01, 06-02), h.txt 1, g.txt 0.
  // N = 3, all three files have indentComplexity 3: f scores 3/3 x 3/3 = 1, h 2/3 x 1, so --top 1 keeps f.txt only.
  assert.deepEqual(json.repoHotSet, [
    { path: 'f.txt', churn: 2, churnRecent: 2, indentComplexity: 3, hotspotScore: 1 },
  ]);
});

test('S11: --since after the last commit gives zero churn, empty sets and a note (R3, R9, R14)', () => {
  const repo = once('window', buildWindow);
  const { json } = metrics(repo, { since: '2026-12-01', recent: '2026-12-15' });
  assert.deepEqual(json.generatedFrom, { headSha: repo.shas[4] });
  assert.deepEqual(json.files.map((f) => f.churn), [0, 0, 0]);
  assert.deepEqual(json.files.map((f) => f.hotspotScore), [0, 0, 0]);
  assert.deepEqual(json.repoHotSet, []);
  assert.deepEqual(json.hotSet, []);
  assert.deepEqual(json.coupling, []);
  assert.deepEqual(json.trend, []);
  // R14: the note for a window without commits mentions the commits.
  assert.ok(json.notes.some((n) => /commit/i.test(n)), JSON.stringify(json.notes));
});

test('S11b: a repository without commits exits 0, headSha null, a note (R14)', () => {
  const dir = path.join(workRoot, 'empty-repo');
  mkdirSync(dir, { recursive: true });
  git(dir, ['init', '-q']);
  const { json } = metrics({ dir });
  assert.deepEqual(json.generatedFrom, { headSha: null });
  assert.deepEqual(json.files, []);
  assert.deepEqual(json.hotSet, []);
  // R14: the note for a repository without commits mentions the commits.
  assert.ok(json.notes.some((n) => /commit/i.test(n)), JSON.stringify(json.notes));
});

test('S12: hot-set cut is churn x 3 >= R, boundary inclusive (R9)', () => {
  const repo = once('boundary', buildBoundary);
  const { json } = metrics(repo, { since: '2026-01-01', recent: '2026-02-05' });
  const row = (p, churn, churnRecent, score) => ({
    path: p, churn, churnRecent, indentComplexity: 3, hotspotScore: score,
  });
  assert.deepEqual(json.repoHotSet, [
    row('f5.txt', 8, 4, 1), row('f4.txt', 7, 3, 0.8), row('f3.txt', 6, 2, 0.6),
    row('f2.txt', 2, 0, 0.4), row('f1.txt', 1, 0, 0.2),
  ]);
  // R = churn at index 2 of [1,2,6,7,8] = 6: f2 has 2 x 3 == 6 (in), f1 has 1 x 3 < 6 (out)
  assert.deepEqual(json.hotSet, [
    row('f5.txt', 8, 4, 1), row('f4.txt', 7, 3, 0.8), row('f3.txt', 6, 2, 0.6),
    row('f2.txt', 2, 0, 0.4),
  ]);
  assert.deepEqual(json.coupling, [
    { path: 'f5.txt', commits: 8, partners: [{ path: 'f4.txt', count: 7 }, { path: 'f3.txt', count: 6 }, { path: 'f2.txt', count: 2 }, { path: 'f1.txt', count: 1 }] },
    { path: 'f4.txt', commits: 7, partners: [{ path: 'f5.txt', count: 7 }, { path: 'f3.txt', count: 6 }, { path: 'f2.txt', count: 2 }, { path: 'f1.txt', count: 1 }] },
    { path: 'f3.txt', commits: 6, partners: [{ path: 'f4.txt', count: 6 }, { path: 'f5.txt', count: 6 }, { path: 'f2.txt', count: 2 }, { path: 'f1.txt', count: 1 }] },
    { path: 'f2.txt', commits: 2, partners: [{ path: 'f3.txt', count: 2 }, { path: 'f4.txt', count: 2 }, { path: 'f5.txt', count: 2 }, { path: 'f1.txt', count: 1 }] },
  ]);
});

test('S12b: --top limits both hot sets and the partner lists', () => {
  const repo = once('boundary', buildBoundary);
  const { json } = metrics(repo, { since: '2026-01-01', recent: '2026-02-05', extra: ['--top', '2'] });
  assert.deepEqual(paths(json.repoHotSet), ['f5.txt', 'f4.txt']);
  assert.deepEqual(paths(json.hotSet), ['f5.txt', 'f4.txt']);
  assert.deepEqual(json.coupling[0].partners, [
    { path: 'f4.txt', count: 7 },
    { path: 'f3.txt', count: 6 },
  ]);
});

test('S13: trend samples oldest first, 6 of 7 commits at indices 0,1,2,4,5,6 (R11)', () => {
  const repo = once('trend', buildTrend);
  const { json } = metrics(repo, { since: '2026-01-01', recent: '2026-03-05' });
  const s = repo.shas;
  assert.deepEqual(paths(json.hotSet), ['t.txt', 'u.txt']);
  assert.deepEqual(json.trend, [
    {
      path: 't.txt',
      samples: [
        { sha: s[0], date: '2026-03-01', indentComplexity: 1 },
        { sha: s[1], date: '2026-03-02', indentComplexity: 2 },
        { sha: s[2], date: '2026-03-03', indentComplexity: 3 },
        { sha: s[4], date: '2026-03-05', indentComplexity: 5 },
        { sha: s[5], date: '2026-03-06', indentComplexity: 6 },
        { sha: s[6], date: '2026-03-07', indentComplexity: 7 },
      ],
    },
    {
      path: 'u.txt',
      // 3 commits (k <= 6): all of them, oldest first
      samples: [
        { sha: s[0], date: '2026-03-01', indentComplexity: 1 },
        { sha: s[3], date: '2026-03-04', indentComplexity: 2 },
        { sha: s[6], date: '2026-03-07', indentComplexity: 3 },
      ],
    },
  ]);
  for (const entry of json.trend) {
    for (const sample of entry.samples) assert.match(sample.sha, /^[0-9a-f]{40}$/);
  }
});

test('S14: the timed run on the real repository (AC4) is manual, see the manifest', () => {
  // No assertion here by design: the plan marks S14 manual (no test file).
  assert.ok(existsSync(CLI), 'CLI exists at the declared path');
});

// ------------------------------------------------------------------ probes

test('probe: tab counts 4 columns, 2- and 4-space nesting agree, CRLF equals LF (R4, R5)', () => {
  const repo = once('probe', buildProbe);
  const { json } = metrics(repo, { since: '2026-01-01', recent: '2026-02-02' });
  const f = byPath(json);
  // columns 0,4,4: unit 4 -> levels 0,1,1; a tab weighing 1 column would give 5
  assert.equal(f['tabs/mixed.zz'].indentComplexity, 2);
  assert.equal(f['tabs/mixed.zz'].maxNesting, 1);
  assert.equal(f['tabs/mixed.zz'].loc, 3);
  for (const p of ['tabs/tab.zz', 'tabs/two.zz', 'tabs/four.zz']) {
    assert.equal(f[p].indentComplexity, 3, p);
    assert.equal(f[p].maxNesting, 2, p);
  }
  const lf = { ...f['eol/lf.kt'] };
  const crlf = { ...f['eol/crlf.kt'] };
  delete lf.path;
  delete crlf.path;
  assert.deepEqual(crlf, lf);
  assert.equal(crlf.loc, 8);
  assert.equal(crlf.branchComplexity, 2);
});

test('probe: code after a block-comment end, default indent unit, nested functions (R4-R7)', () => {
  const repo = once('probe', buildProbe);
  const { json } = metrics(repo, { since: '2026-01-01', recent: '2026-02-02' });
  const f = byPath(json);
  const tail = f['cmt/tail.kt'];
  // line 2 closes the block but code follows: counted; line 3 opens a block after code: counted;
  // line 4 closes with nothing after: comment
  assert.equal(tail.loc, 3);
  // columns 8,0,0 have no positive increase: unit 4, levels 2,0,0
  assert.equal(tail.indentComplexity, 2);
  assert.equal(tail.maxNesting, 2);
  assert.equal(tail.branchComplexity, 0);
  assert.deepEqual(tail.topFunctions, []);
  const nested = f['nest/nested.kt'];
  assert.equal(nested.indentComplexity, 5);
  assert.equal(nested.branchComplexity, 1);
  assert.deepEqual(nested.topFunctions, [
    { name: 'outer', line: 1, length: 6, branches: 1 },
    { name: 'inner', line: 2, length: 3, branches: 1 },
  ]);
});

test('probe: end-style (Ruby) function span (R7)', () => {
  const repo = once('probe', buildProbe);
  const { json } = metrics(repo, { since: '2026-01-01', recent: '2026-02-02' });
  const rb = byPath(json)['rb/foo.rb'];
  assert.match(rb.lang, /ruby/i, 'Ruby is a shipped language');
  const bar = rb.topFunctions.find((fn) => fn.name === 'bar');
  assert.ok(bar, JSON.stringify(rb.topFunctions));
  assert.equal(bar.line, 2);
  assert.equal(bar.length, 5);
});

test('probe: noise, binary boundary, space and non-ASCII paths (R1, R2, R3)', () => {
  const repo = once('probe', buildProbe);
  const { json } = metrics(repo, { since: '2026-01-01', recent: '2026-02-02' });
  const uni = 'docs/\u00fcn\u00ef.txt';
  const expected = [
    'bin/late.dat', 'cmt/tail.kt', 'docs/my file.txt', uni, 'eol/crlf.kt', 'eol/lf.kt',
    'nest/nested.kt', 'rb/foo.rb', 'tabs/four.zz', 'tabs/mixed.zz', 'tabs/tab.zz', 'tabs/two.zz',
  ].sort();
  // bin/blob.dat (NUL at 0) and bin/edge.dat (NUL at byte 7999) are binary; bin/late.dat
  // has its first NUL at byte 8000, outside the first 8000 bytes, so it is text.
  assert.deepEqual(paths(json.files), expected);
  const f = byPath(json);
  assert.equal(f['bin/late.dat'].loc, 1);
  // the log path and the ls-files path must agree for a space and for non-ASCII characters
  assert.equal(f['docs/my file.txt'].churn, 2);
  assert.equal(f['docs/my file.txt'].churnRecent, 1);
  assert.equal(f[uni].churn, 2);
  assert.equal(f[uni].churnRecent, 1);
});

test('probe: 3-cycle beside a 2-cycle, self import, repeated import lines (R12)', () => {
  const repo = once('graph', buildGraph);
  const { json } = metrics(repo, { paths: 'src', extra: ['--module-depth', '1'] });
  const m = json.modules;
  assert.deepEqual(m.cycles, [['src/m1', 'src/m2', 'src/m3'], ['src/m4', 'src/m5']]);
  assert.deepEqual(m.edges, [
    { from: 'src/m1', to: 'src/m2', count: 2 },
    { from: 'src/m2', to: 'src/m3', count: 1 },
    { from: 'src/m3', to: 'src/m1', count: 1 },
    { from: 'src/m4', to: 'src/m5', count: 1 },
    { from: 'src/m5', to: 'src/m4', count: 1 },
    { from: 'src/m7', to: 'src/m1', count: 1 },
  ]);
  assert.deepEqual(m.nodes, [
    { id: 'src/m1', files: 1, loc: 3, fanIn: 2, fanOut: 1, instability: 0.333 },
    { id: 'src/m2', files: 1, loc: 2, fanIn: 1, fanOut: 1, instability: 0.5 },
    { id: 'src/m3', files: 1, loc: 2, fanIn: 1, fanOut: 1, instability: 0.5 },
    { id: 'src/m4', files: 1, loc: 2, fanIn: 1, fanOut: 1, instability: 0.5 },
    { id: 'src/m5', files: 1, loc: 2, fanIn: 1, fanOut: 1, instability: 0.5 },
    { id: 'src/m6', files: 3, loc: 5, fanIn: 0, fanOut: 0, instability: 0 },
    { id: 'src/m7', files: 1, loc: 2, fanIn: 0, fanOut: 1, instability: 1 },
  ]);
  assert.equal(m.unresolved, 0);
});

test('probe: extensionless and directory-index relative imports; outside scope ignored (R12)', () => {
  const repo = once('rel', buildRel);
  const { json } = metrics(repo, { paths: 'src', extra: ['--module-depth', '1'] });
  const m = json.modules;
  assert.deepEqual(m.edges, [
    { from: 'src/x', to: 'src/y', count: 1 },
    { from: 'src/x', to: 'src/z', count: 1 },
  ]);
  // lib/u.js is in the repository but outside --paths: ignored; lib/nothere matches nothing
  assert.equal(m.unresolved, 1);
  assert.deepEqual(m.cycles, []);
  assert.deepEqual(m.nodes, [
    { id: 'src/x', files: 1, loc: 5, fanIn: 0, fanOut: 2, instability: 1 },
    { id: 'src/y', files: 1, loc: 1, fanIn: 1, fanOut: 0, instability: 0 },
    { id: 'src/z', files: 1, loc: 1, fanIn: 1, fanOut: 0, instability: 0 },
  ]);
});

test('probe: metrics read the working tree; untracked and missing tracked files are skipped (R1)', () => {
  const repo = once('wt', buildWorkingTree);
  const { json } = metrics(repo);
  assert.deepEqual(paths(json.files), ['a.txt']);
  const a = json.files[0];
  assert.equal(a.loc, 4);
  assert.equal(a.indentComplexity, 3);
  assert.equal(a.churn, 1);
});

test('probe: --root spelling does not change the root field (R13; backslashes are platform-bound)', () => {
  const repo = once('window', buildWindow);
  const expected = path.resolve(repo.dir).split(path.sep).join('/');
  const native = metrics(repo);
  assert.equal(native.json.root, expected);
  const trailing = metrics(repo, { root: `${repo.dir}${path.sep}` });
  assert.equal(trailing.json.root, expected);
  const relative = metrics(repo, { root: '.', cwd: repo.dir });
  assert.equal(relative.json.root, expected);
  if (process.platform === 'win32') {
    assert.equal(native.json.root.includes('\\'), false);
  }
});

test('probe: --lang-table replaces the shipped table, language and noise rules alike', () => {
  const repo = once('langs', buildLangs);
  const shipped = JSON.parse(readFileSync(SHIPPED_TABLE, 'utf8'));
  const custom = JSON.parse(JSON.stringify(shipped));
  custom.languages = custom.languages.filter((l) => !/^kotlin$/i.test(l.name));
  assert.ok(custom.languages.length < shipped.languages.length, 'fixture removes exactly Kotlin');
  custom.noise.basenames.push('Makefile');
  const tablePath = path.join(workRoot, 'custom-table.json');
  writeFileSync(tablePath, JSON.stringify(custom));
  const { json } = metrics(repo, { extra: ['--lang-table', tablePath] });
  const f = byPath(json);
  const k = f['K.kt'];
  assert.equal(k.lang, null);
  assert.equal(k.branchComplexity, null);
  assert.equal(k.topFunctions, null);
  // unknown language: the 11 non-blank lines all count (comments included)
  assert.equal(k.loc, 11);
  assert.equal(k.indentComplexity, 5);
  assert.equal(f.Makefile, undefined, 'added noise basename must exclude the file');
  assert.deepEqual(json.languages.recognised, { '.py': 1 });
  assert.deepEqual(json.languages.unrecognised, { '.kt': 1, '.zz': 2 });
});

// ------------------------------------------------- languages.mjs (library)

test('languages: shipped table recognises the C1 language list and rejects unknown extensions', () => {
  const table = loadLanguageTable(JSON.parse(readFileSync(SHIPPED_TABLE, 'utf8')));
  const known = [
    'x.kt', 'x.java', 'x.js', 'x.mjs', 'x.ts', 'x.go', 'x.rs', 'x.py', 'x.cs',
    'x.swift', 'x.rb', 'x.c', 'x.cpp', 'x.h', 'x.php',
  ];
  for (const p of known) {
    assert.ok(table.langFor(`src/${p}`), `${p} should be a known language`);
  }
  assert.equal(table.langFor('src/x.zz') ?? null, null);
  assert.equal(table.langFor('Makefile') ?? null, null);
});

test('languages: noise rules of R2 (basenames, suffixes, path segments)', () => {
  const table = loadLanguageTable(JSON.parse(readFileSync(SHIPPED_TABLE, 'utf8')));
  const basenames = [
    'package-lock.json', 'yarn.lock', 'pnpm-lock.yaml', 'Cargo.lock', 'Gemfile.lock',
    'poetry.lock', 'composer.lock', 'go.sum', 'gradle.lockfile', 'CHANGELOG.md',
    'CHANGES.md', 'HISTORY.md',
  ];
  for (const name of basenames) {
    assert.equal(table.isNoise(name), true, name);
    assert.equal(table.isNoise(`sub/dir/${name}`), true, `sub/dir/${name}`);
  }
  for (const suffix of ['.lock', '.min.js', '.min.css', '.map']) {
    assert.equal(table.isNoise(`a/b/file${suffix}`), true, suffix);
  }
  const segments = [
    'node_modules', 'dist', 'build', 'out', 'target', 'vendor', 'generated', '__pycache__', '.gradle',
  ];
  for (const seg of segments) {
    assert.equal(table.isNoise(`p/${seg}/f.txt`), true, seg);
    assert.equal(table.isNoise(`${seg}/f.txt`), true, `${seg}/f.txt`);
  }
  // near misses are not noise
  for (const p of [
    'src/main/App.kt', 'README.md', 'src/builder/x.kt', 'notnode_modules/x.js',
    'a/distant/x.js', 'admin.js', 'docs/CHANGELOG.md.bak', 'x.locked', 'a/outer/x.txt',
  ]) {
    assert.equal(table.isNoise(p), false, p);
  }
});
