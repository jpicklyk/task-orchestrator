// Tests for the audit/digest-v1 digest (scripts/lib/audit-metrics/digest.mjs), the coChangePairs
// helper (lib/audit-metrics/churn.mjs) and the --digest-out / --candidates flags of
// scripts/audit-metrics.mjs (item 350df587, test-author seat).
//
// Oracles: task-scope decisions D1-D6 and acceptance criteria 1-7 of the item, contract C4 as
// amended by A2. Every expected value on a hand-built metrics object is computed by hand from
// D2/D3/D4 beside the fixture. Where a run's metrics are only known after the CLI ran (S7), the
// expected candidates are recomputed by a small oracle written here from D3/D4, never read from the
// implementation. Source is ASCII only (non-ASCII file names are written as escapes).

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  readFileSync,
  readdirSync,
  existsSync,
  rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildDigest, serializeDigest, DIGEST_CONTRACT, DEFAULT_CANDIDATES } from '../lib/audit-metrics/digest.mjs';
import { coChangePairs } from '../lib/audit-metrics/churn.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const scriptsDir = path.resolve(here, '..');
const CLI = path.join(scriptsDir, 'audit-metrics.mjs');

const workRoot = mkdtempSync(path.join(tmpdir(), 'audit-digest-test-'));
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
  if (r.status !== 0) throw new Error(`git ${args.join(' ')} failed: ${r.stderr}`);
  return r.stdout;
}

function makeRepo(name, commits) {
  const dir = path.join(workRoot, name);
  mkdirSync(dir, { recursive: true });
  git(dir, ['init', '-q']);
  for (const c of commits) {
    for (const [rel, content] of Object.entries(c.files)) {
      const abs = path.join(dir, ...rel.split('/'));
      mkdirSync(path.dirname(abs), { recursive: true });
      writeFileSync(abs, content);
    }
    const env = { ...baseEnv, GIT_AUTHOR_DATE: day(c.date), GIT_COMMITTER_DATE: day(c.date) };
    git(dir, ['add', '-A'], env);
    git(dir, ['commit', '-q', '--no-gpg-sign', '-m', 'fixture commit'], env);
  }
  return { dir };
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

let seq = 0;
function slot(tag) {
  seq += 1;
  const dir = path.join(workRoot, 'run', `${tag}${seq}`);
  return { dir, out: path.join(dir, 'm.json'), dig: path.join(dir, 'd.json') };
}

// Required flags for a run on repo; extra flags follow.
function baseArgs(repo, out, extra = [], opts = {}) {
  return [
    '--root', repo.dir,
    '--paths', opts.paths ?? 'src',
    '--since', '2026-01-01',
    '--recent', '2026-06-01',
    '--out', out,
    ...extra,
  ];
}

// Runs with --digest-out; throws unless exit 0; returns parsed files.
function runWithDigest(repo, extra = [], opts = {}) {
  const s = opts.slot ?? slot('d');
  const r = runCli(baseArgs(repo, s.out, ['--module-depth', '1', '--digest-out', s.dig, ...extra], opts));
  if (r.status !== 0) throw new Error(`audit-metrics exited ${r.status}: ${r.stderr}`);
  const digestText = readFileSync(s.dig, 'utf8');
  const metricsText = readFileSync(s.out, 'utf8');
  return { s, r, digestText, digest: JSON.parse(digestText), metricsText, metrics: JSON.parse(metricsText) };
}

// The reason is the stderr text outside the usage line (the usage line names every flag).
const reasonOf = (stderr) =>
  stderr.split(/\r?\n/).filter((l) => l.trim() && !/^usage:/i.test(l.trim())).join('\n');

const cmp = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
const round4 = (x) => Math.round(x * 1e4) / 1e4;

// ----------------------------------------------------- hand-built metrics (H1)

function row(p, mod, churn, churnRecent, ic, bc, hs) {
  return {
    path: p, lang: 'kotlin', loc: 10, churn, churnRecent, indentComplexity: ic, maxNesting: 2,
    branchComplexity: bc, topFunctions: null, hotspotScore: hs, module: mod,
  };
}

// Modules: a (2 files) and b form a cycle; m is an unstable module; r has no dependents (fanIn 0);
// s is stable (instability 0).
function m5() {
  return {
    contract: 'audit/metrics-v1',
    root: '/repo',
    paths: ['.'],
    window: { since: '2026-01-01', recent: '2026-06-01' },
    generatedFrom: { headSha: 'abc123' },
    files: [
      row('a/x.kt', 'a', 10, 4, 8, 5, 0.8),
      row('a/y.kt', 'a', 2, 0, 3, null, 0.2),
      row('b/z.kt', 'b', 6, 2, 5, 2, 0.5),
      row('m/w.kt', 'm', 4, 1, 5, 1, 0.4),
      row('r/q.kt', 'r', 0, 0, 0, null, 0),
      row('s/t.kt', 's', 1, 0, 1, null, 0.1),
    ],
    repoHotSet: [],
    hotSet: [
      { path: 'a/x.kt', churn: 10, churnRecent: 4, indentComplexity: 8, hotspotScore: 0.8 },
      { path: 'b/z.kt', churn: 6, churnRecent: 2, indentComplexity: 5, hotspotScore: 0.5 },
    ],
    coupling: [
      {
        path: 'a/x.kt',
        commits: 10,
        partners: [{ path: 'a/y.kt', count: 6 }, { path: 'b/z.kt', count: 5 }, { path: 'm/w.kt', count: 4 }],
      },
      { path: 'b/z.kt', commits: 6, partners: [{ path: 'a/x.kt', count: 5 }] },
    ],
    trend: [
      {
        path: 'a/x.kt',
        samples: [
          { sha: 's1', date: '2026-02-01', indentComplexity: 6 },
          { sha: 's2', date: '2026-03-01', indentComplexity: 8 },
        ],
      },
    ],
    modules: {
      depth: 2,
      nodes: [
        { id: 'a', files: 2, loc: 20, fanIn: 1, fanOut: 1, instability: 0.5 },
        { id: 'b', files: 1, loc: 10, fanIn: 1, fanOut: 1, instability: 0.5 },
        { id: 'm', files: 1, loc: 10, fanIn: 1, fanOut: 3, instability: 0.75 },
        { id: 'r', files: 1, loc: 10, fanIn: 0, fanOut: 2, instability: 1 },
        { id: 's', files: 1, loc: 10, fanIn: 3, fanOut: 0, instability: 0 },
      ],
      edges: [
        { from: 'a', to: 'b', count: 2 },
        { from: 'b', to: 'a', count: 1 },
        { from: 'm', to: 'a', count: 1 },
      ],
      cycles: [['a', 'b']],
      unresolved: 0,
    },
    languages: { recognised: {}, unrecognised: {} },
    notes: ['note one', 'note two'],
  };
}

const D2_KEYS = [
  'contract', 'window', 'headSha', 'metricsPath', 'hotSet', 'topComplexity', 'cycles',
  'unstableModules', 'coupling', 'trend', 'notes', 'candidates',
];

// Hand-computed with D3 on m5() (top 10, candidates 12). Scores: hot-file = hotspotScore; cycle = hottest
// member (a/x.kt 0.8); unstable-module = instability x hottest file of the module; co-change = 5/10 x 0.8.
// Order: score desc, then candidateId by code-unit order ('cycle:' < 'hot-file:', 'co-change:' < 'unstable-').
const M5_CANDIDATES = [
  {
    candidateId: 'cycle:a', kind: 'cycle', subject: ['a', 'b'],
    facts: { fileCount: 3, hottestPath: 'a/x.kt', edges: 2 }, score: 0.8,
  },
  {
    candidateId: 'hot-file:a/x.kt', kind: 'hot-file', subject: ['a/x.kt'],
    facts: { churn: 10, churnRecent: 4, complexity: 8, branchComplexity: 5, hotspotScore: 0.8 }, score: 0.8,
  },
  {
    candidateId: 'hot-file:b/z.kt', kind: 'hot-file', subject: ['b/z.kt'],
    facts: { churn: 6, churnRecent: 2, complexity: 5, branchComplexity: 2, hotspotScore: 0.5 }, score: 0.5,
  },
  {
    candidateId: 'co-change:a/x.kt', kind: 'co-change', subject: ['a/x.kt', 'b/z.kt', 'm/w.kt'],
    facts: {
      commits: 10,
      partners: [{ path: 'b/z.kt', module: 'b', count: 5 }, { path: 'm/w.kt', module: 'm', count: 4 }],
    },
    score: 0.4,
  },
  {
    candidateId: 'unstable-module:a', kind: 'unstable-module', subject: ['a'],
    facts: { instability: 0.5, fanIn: 1, fanOut: 1, files: 2, hottestPath: 'a/x.kt' }, score: 0.4,
  },
  {
    candidateId: 'unstable-module:m', kind: 'unstable-module', subject: ['m'],
    facts: { instability: 0.75, fanIn: 1, fanOut: 3, files: 1, hottestPath: 'm/w.kt' }, score: 0.3,
  },
  {
    candidateId: 'unstable-module:b', kind: 'unstable-module', subject: ['b'],
    facts: { instability: 0.5, fanIn: 1, fanOut: 1, files: 1, hottestPath: 'b/z.kt' }, score: 0.25,
  },
];

// ------------------------------------------------------------------ happy

test('S1: the same metrics give the same serialized digest, and the input is left unmodified', () => {
  const input = m5();
  const before = structuredClone(input);
  const first = serializeDigest(buildDigest(input));
  const second = serializeDigest(buildDigest(input));
  assert.equal(first, second);
  assert.deepEqual(input, before);
  // D5: compact JSON plus exactly one trailing newline
  const digest = buildDigest(input);
  assert.equal(serializeDigest(digest), `${JSON.stringify(digest)}\n`);
  assert.deepEqual(JSON.parse(first), JSON.parse(JSON.stringify(digest)));
  // the input is also left unmodified by a digest with other options
  buildDigest(input, { top: 1, candidates: 2 });
  assert.deepEqual(input, before);
});

test('S2: digest top-level keys follow D2 in order; contract, metricsPath, window and headSha', () => {
  const d = buildDigest(m5());
  assert.equal(DIGEST_CONTRACT, 'audit/digest-v1');
  assert.equal(DEFAULT_CANDIDATES, 12);
  assert.deepEqual(Object.keys(d), D2_KEYS);
  assert.equal(d.contract, 'audit/digest-v1');
  assert.equal(d.metricsPath, null);
  assert.deepEqual(d.window, { since: '2026-01-01', recent: '2026-06-01' });
  assert.equal(d.headSha, 'abc123');
  assert.equal('files' in d, false);
  assert.equal('moduleOf' in d, false);
});

test('S3: hotSet rows keep metrics order with the module of the files row; topComplexity is positive, ordered and capped', () => {
  const d = buildDigest(m5());
  assert.deepEqual(d.hotSet, [
    { path: 'a/x.kt', churn: 10, churnRecent: 4, complexity: 8, hotspotScore: 0.8, module: 'a' },
    { path: 'b/z.kt', churn: 6, churnRecent: 2, complexity: 5, hotspotScore: 0.5, module: 'b' },
  ]);
  assert.deepEqual(Object.keys(d.hotSet[0]), ['path', 'churn', 'churnRecent', 'complexity', 'hotspotScore', 'module']);
  // indentComplexity > 0 only (r/q.kt has 0); 5 and 5 tie by path; then 3, then 1
  assert.deepEqual(d.topComplexity, [
    { path: 'a/x.kt', complexity: 8, module: 'a' },
    { path: 'b/z.kt', complexity: 5, module: 'b' },
    { path: 'm/w.kt', complexity: 5, module: 'm' },
    { path: 'a/y.kt', complexity: 3, module: 'a' },
    { path: 's/t.kt', complexity: 1, module: 's' },
  ]);
  assert.deepEqual(Object.keys(d.topComplexity[0]), ['path', 'complexity', 'module']);
  assert.deepEqual(buildDigest(m5(), { top: 2 }).topComplexity, [
    { path: 'a/x.kt', complexity: 8, module: 'a' },
    { path: 'b/z.kt', complexity: 5, module: 'b' },
  ]);
});

test('S3b: a module that is the empty string is a real module in hotSet, topComplexity and candidates', () => {
  const m = m5();
  m.files = [row('root.kt', '', 3, 1, 2, null, 0.6)];
  m.hotSet = [{ path: 'root.kt', churn: 3, churnRecent: 1, indentComplexity: 2, hotspotScore: 0.6 }];
  m.coupling = [];
  m.trend = [];
  m.modules = {
    depth: 2,
    nodes: [{ id: '', files: 1, loc: 10, fanIn: 1, fanOut: 1, instability: 0.5 }],
    edges: [],
    cycles: [],
    unresolved: 0,
  };
  const d = buildDigest(m);
  assert.deepEqual(d.hotSet, [{ path: 'root.kt', churn: 3, churnRecent: 1, complexity: 2, hotspotScore: 0.6, module: '' }]);
  assert.deepEqual(d.topComplexity, [{ path: 'root.kt', complexity: 2, module: '' }]);
  assert.deepEqual(d.candidates, [
    {
      candidateId: 'hot-file:root.kt', kind: 'hot-file', subject: ['root.kt'],
      facts: { churn: 3, churnRecent: 1, complexity: 2, branchComplexity: null, hotspotScore: 0.6 }, score: 0.6,
    },
    {
      candidateId: 'unstable-module:', kind: 'unstable-module', subject: [''],
      facts: { instability: 0.5, fanIn: 1, fanOut: 1, files: 1, hottestPath: 'root.kt' }, score: 0.3,
    },
  ]);
});

test('S4: unstableModules, cycles, notes, coupling and trend follow D2', () => {
  const d = buildDigest(m5());
  // r has fanIn 0 and s has instability 0: both out. m 0.75 first, then a and b tie on 0.5 and fanIn 1: id order.
  assert.deepEqual(d.unstableModules, [
    { id: 'm', instability: 0.75, fanIn: 1, fanOut: 3 },
    { id: 'a', instability: 0.5, fanIn: 1, fanOut: 1 },
    { id: 'b', instability: 0.5, fanIn: 1, fanOut: 1 },
  ]);
  assert.deepEqual(Object.keys(d.unstableModules[0]), ['id', 'instability', 'fanIn', 'fanOut']);
  assert.deepEqual(buildDigest(m5(), { top: 2 }).unstableModules.map((u) => u.id), ['m', 'a']);
  assert.deepEqual(d.cycles, [['a', 'b']]);
  assert.deepEqual(d.notes, ['note one', 'note two']);
  // commits dropped, partners as given (the same-module partner stays)
  assert.deepEqual(d.coupling, [
    { path: 'a/x.kt', partners: [{ path: 'a/y.kt', count: 6 }, { path: 'b/z.kt', count: 5 }, { path: 'm/w.kt', count: 4 }] },
    { path: 'b/z.kt', partners: [{ path: 'a/x.kt', count: 5 }] },
  ]);
  assert.deepEqual(Object.keys(d.coupling[0]), ['path', 'partners']);
  assert.deepEqual(d.trend, [{ path: 'a/x.kt', complexities: [6, 8] }]);
  assert.deepEqual(Object.keys(d.trend[0]), ['path', 'complexities']);
});

test('S4b: cycles are copied uncapped whatever top is', () => {
  const m = m5();
  m.modules.cycles = [['a', 'b'], ['c', 'd', 'e'], ['f', 'g']];
  assert.deepEqual(buildDigest(m, { top: 1 }).cycles, [['a', 'b'], ['c', 'd', 'e'], ['f', 'g']]);
});

test('S5: exact candidates on a hand-built metrics object (D3, D4)', () => {
  const d = buildDigest(m5());
  assert.deepEqual(d.candidates, M5_CANDIDATES);
  assert.deepEqual(Object.keys(d.candidates[0]), ['candidateId', 'kind', 'subject', 'facts', 'score']);
  const keysOf = (kind) => Object.keys(d.candidates.find((c) => c.kind === kind).facts);
  assert.deepEqual(keysOf('hot-file'), ['churn', 'churnRecent', 'complexity', 'branchComplexity', 'hotspotScore']);
  assert.deepEqual(keysOf('cycle'), ['fileCount', 'hottestPath', 'edges']);
  assert.deepEqual(keysOf('unstable-module'), ['instability', 'fanIn', 'fanOut', 'files', 'hottestPath']);
  assert.deepEqual(keysOf('co-change'), ['commits', 'partners']);
});

test('S5b: unstable-module candidates come from the capped unstableModules list', () => {
  const d = buildDigest(m5(), { top: 1 });
  assert.deepEqual(d.unstableModules.map((u) => u.id), ['m']);
  const ids = d.candidates.map((c) => c.candidateId);
  assert.ok(ids.includes('unstable-module:m'), JSON.stringify(ids));
  assert.equal(ids.includes('unstable-module:a'), false, JSON.stringify(ids));
  assert.equal(ids.includes('unstable-module:b'), false, JSON.stringify(ids));
});

// ---------------------------------------------------------------- coChangePairs

const MODS = {
  'h.kt': 'm1', 'same.kt': 'm1', 'other.kt': 'm2', 'p2.kt': 'm4', 'k.kt': 'm3', 'root.kt': '', 'nullmod.kt': null,
};
const moduleOf = (p) => MODS[p];

function pairCoupling() {
  return [
    {
      path: 'h.kt',
      commits: 10,
      partners: [
        { path: 'p2.kt', count: 2 },
        { path: 'same.kt', count: 5 },
        { path: 'other.kt', count: 3 },
        { path: 'norow.kt', count: 9 },
        { path: 'nullmod.kt', count: 8 },
      ],
    },
    { path: 'other.kt', commits: 4, partners: [{ path: 'h.kt', count: 3 }] },
    { path: 'k.kt', commits: 3, partners: [{ path: 'other.kt', count: 3 }] },
    { path: 'nullmod.kt', commits: 5, partners: [{ path: 'other.kt', count: 4 }] },
    { path: 'root.kt', commits: 6, partners: [{ path: 'other.kt', count: 4 }] },
  ];
}

test('S10: coChangePairs drops a count below 3, a same-module partner, a partner without a module, and a mirrored pair', () => {
  const coupling = pairCoupling();
  const before = structuredClone(coupling);
  const got = coChangePairs(coupling, moduleOf);
  // h.kt: p2 (2) too weak; same.kt same module; other.kt (exactly 3) kept; norow.kt (undefined) and nullmod.kt (null) dropped.
  // other.kt: its only partner h.kt is the pair already kept under h.kt, so the entry is omitted.
  // k.kt: other.kt count 3, modules m3 vs m2, kept. nullmod.kt as hot file has a null module: omitted.
  // root.kt: hot module '' is a real module, differs from m2: kept.
  assert.deepEqual(got, [
    { path: 'h.kt', commits: 10, partners: [{ path: 'other.kt', module: 'm2', count: 3 }] },
    { path: 'k.kt', commits: 3, partners: [{ path: 'other.kt', module: 'm2', count: 3 }] },
    { path: 'root.kt', commits: 6, partners: [{ path: 'other.kt', module: 'm2', count: 4 }] },
  ]);
  assert.deepEqual(coupling, before);
});

test('S10b: coChangePairs minCount is a >= threshold in both directions', () => {
  // minCount 2 also keeps p2.kt (count 2), partner order as given
  const low = coChangePairs(pairCoupling(), moduleOf, 2);
  assert.deepEqual(low[0], {
    path: 'h.kt',
    commits: 10,
    partners: [{ path: 'p2.kt', module: 'm4', count: 2 }, { path: 'other.kt', module: 'm2', count: 3 }],
  });
  // minCount 4: h.kt and k.kt (count 3) keep nothing and are omitted; other.kt's entry keeps nothing; root.kt (4) stays
  const high = coChangePairs(pairCoupling(), moduleOf, 4);
  assert.deepEqual(high, [{ path: 'root.kt', commits: 6, partners: [{ path: 'other.kt', module: 'm2', count: 4 }] }]);
});

test('S10c: an empty coupling gives an empty list; a partner in the empty-string module differs from a named one', () => {
  assert.deepEqual(coChangePairs([], moduleOf), []);
  const got = coChangePairs(
    [{ path: 'h.kt', commits: 4, partners: [{ path: 'root.kt', count: 3 }] }],
    moduleOf,
  );
  assert.deepEqual(got, [{ path: 'h.kt', commits: 4, partners: [{ path: 'root.kt', module: '', count: 3 }] }]);
});

test('S13: metrics whose contract is not audit/metrics-v1 are refused with a TypeError', () => {
  const m = m5();
  m.contract = 'audit/metrics-v2';
  assert.throws(() => buildDigest(m), TypeError);
  assert.throws(() => buildDigest(m), /buildDigest needs an audit\/metrics-v1 object/);
  assert.throws(() => buildDigest({}), TypeError);
});

// ------------------------------------------------------------------- edges

// n hot files f01..fNN in separate modules is not needed: a hot-file candidate needs only a files row and a hotSet row.
function hotOnly(n) {
  const m = m5();
  m.files = [];
  m.hotSet = [];
  for (let i = 1; i <= n; i += 1) {
    const p = `f${String(i).padStart(2, '0')}.kt`;
    const hs = i / 20;
    m.files.push(row(p, 'm', i, 0, 1, null, hs));
    m.hotSet.push({ path: p, churn: i, churnRecent: 0, indentComplexity: 1, hotspotScore: hs });
  }
  m.coupling = [];
  m.trend = [];
  m.modules = { depth: 2, nodes: [], edges: [], cycles: [], unresolved: 0 };
  return m;
}

test('S14: the candidates option keeps the first N of the ranked list; default 12; above the length keeps all', () => {
  const m = hotOnly(14);
  const all = buildDigest(m, { candidates: 100 }).candidates;
  assert.equal(all.length, 14);
  // highest hotspotScore first: f14 (0.7) .. f01 (0.05)
  assert.equal(all[0].candidateId, 'hot-file:f14.kt');
  assert.equal(all[13].candidateId, 'hot-file:f01.kt');
  assert.deepEqual(buildDigest(m).candidates, all.slice(0, 12));
  assert.equal(buildDigest(m).candidates.length, 12);
  assert.deepEqual(buildDigest(m, { candidates: 1 }).candidates, all.slice(0, 1));
  assert.deepEqual(buildDigest(m, { candidates: 13 }).candidates, all.slice(0, 13));
  assert.deepEqual(buildDigest(m, { candidates: 14 }).candidates, all);
  assert.deepEqual(buildDigest(hotOnly(3)).candidates.length, 3);
});

test('S15: equal scores order by candidateId in code-unit order; ids are unique; a score-0 candidate is ranked last, not dropped', () => {
  const m = m5();
  m.files = [
    row('b.kt', 'm', 3, 0, 1, null, 0.5),
    row('a.kt', 'm', 3, 0, 1, null, 0.5),
    row('B.kt', 'm', 3, 0, 1, null, 0.5),
    row('c.kt', 'm', 3, 0, 1, null, 0.5),
    row('z.kt', 'm', 0, 0, 1, null, 0),
  ];
  m.hotSet = m.files.map((f) => ({
    path: f.path, churn: f.churn, churnRecent: 0, indentComplexity: 1, hotspotScore: f.hotspotScore,
  }));
  m.coupling = [];
  m.trend = [];
  // a cycle whose members have no files rows: no hottest file, score 0
  m.modules = { depth: 2, nodes: [], edges: [], cycles: [['p', 'q']], unresolved: 0 };
  const cands = buildDigest(m).candidates;
  assert.deepEqual(cands.map((c) => c.candidateId), [
    'hot-file:B.kt', 'hot-file:a.kt', 'hot-file:b.kt', 'hot-file:c.kt', 'cycle:p', 'hot-file:z.kt',
  ]);
  assert.equal(new Set(cands.map((c) => c.candidateId)).size, cands.length);
  const zeroCycle = cands.find((c) => c.candidateId === 'cycle:p');
  assert.deepEqual(zeroCycle, {
    candidateId: 'cycle:p', kind: 'cycle', subject: ['p', 'q'],
    facts: { fileCount: 0, hottestPath: null, edges: 0 }, score: 0,
  });
});

test('S16: empty metrics give empty lists and no candidates', () => {
  const m = {
    contract: 'audit/metrics-v1',
    root: '/repo',
    paths: ['.'],
    window: { since: '2026-01-01', recent: '2026-06-01' },
    generatedFrom: { headSha: 'h' },
    files: [],
    repoHotSet: [],
    hotSet: [],
    coupling: [],
    trend: [],
    modules: { depth: 2, nodes: [], edges: [], cycles: [], unresolved: 0 },
    languages: { recognised: {}, unrecognised: {} },
    notes: [],
  };
  const d = buildDigest(m);
  assert.deepEqual(Object.keys(d), D2_KEYS);
  for (const key of ['hotSet', 'topComplexity', 'cycles', 'unstableModules', 'coupling', 'trend', 'notes', 'candidates']) {
    assert.deepEqual(d[key], [], key);
  }
  assert.equal(d.headSha, 'h');
});

// ---------------------------------------------------------------- CLI (H2)

function jsBody(imports, name, rev) {
  return `${imports.map((i) => `import { v } from '${i}';`).join('\n')}\nexport function ${name}() {\n  if (v) {\n    ${rev > 0 ? 'if (v > 1) {\n      return 1;\n    }' : 'return 0;'}\n  }\n  return ${rev};\n}\n`;
}

// x <-> y cycle, y -> z; a and b change together in 4 commits (cross-module coupling count 4).
function buildDigestFx() {
  const files = (rev, withC) => ({
    'src/x/a.mjs': jsBody(['../y/b.mjs'], 'a', rev),
    'src/y/b.mjs': jsBody(['../x/a.mjs', '../z/c.mjs'], 'b', rev * 2),
    ...(withC ? { 'src/z/c.mjs': jsBody([], 'c', rev) } : {}),
  });
  return makeRepo('digestfx', [
    { date: '2026-02-01', files: files(0, true) },
    { date: '2026-03-01', files: files(1, false) },
    { date: '2026-04-01', files: files(2, true) },
    { date: '2026-06-10', files: files(3, false) },
  ]);
}

// An independent recomputation of the D2/D3/D4 lists from a metrics-v1 object (the test's oracle).
function oracleCandidates(m, top) {
  const rows = m.files;
  const rowOf = Object.fromEntries(rows.map((r) => [r.path, r]));
  const hottest = (mods) => {
    const inMods = rows.filter((r) => mods.includes(r.module));
    inMods.sort((p, q) => q.hotspotScore - p.hotspotScore || cmp(p.path, q.path));
    return inMods.length ? { path: inMods[0].path, score: inMods[0].hotspotScore } : { path: null, score: 0 };
  };
  const out = [];
  for (const h of m.hotSet) {
    const r = rowOf[h.path];
    out.push({
      candidateId: `hot-file:${h.path}`, kind: 'hot-file', subject: [h.path],
      facts: {
        churn: r.churn, churnRecent: r.churnRecent, complexity: r.indentComplexity,
        branchComplexity: r.branchComplexity, hotspotScore: r.hotspotScore,
      },
      score: r.hotspotScore,
    });
  }
  for (const cyc of m.modules.cycles) {
    const hot = hottest(cyc);
    out.push({
      candidateId: `cycle:${cyc[0]}`, kind: 'cycle', subject: cyc,
      facts: {
        fileCount: rows.filter((r) => cyc.includes(r.module)).length,
        hottestPath: hot.path,
        edges: m.modules.edges.filter((e) => cyc.includes(e.from) && cyc.includes(e.to)).length,
      },
      score: hot.score,
    });
  }
  const unstable = m.modules.nodes
    .filter((n) => n.fanIn >= 1 && n.instability > 0)
    .sort((p, q) => q.instability - p.instability || q.fanIn - p.fanIn || cmp(p.id, q.id))
    .slice(0, top);
  for (const n of unstable) {
    const hot = hottest([n.id]);
    out.push({
      candidateId: `unstable-module:${n.id}`, kind: 'unstable-module', subject: [n.id],
      facts: { instability: n.instability, fanIn: n.fanIn, fanOut: n.fanOut, files: n.files, hottestPath: hot.path },
      score: round4(n.instability * hot.score),
    });
  }
  const modOf = (p) => (rowOf[p] ? rowOf[p].module : null);
  const kept = new Set();
  for (const e of m.coupling) {
    const hotMod = modOf(e.path);
    if (hotMod === null) continue;
    const partners = [];
    for (const p of e.partners) {
      const pm = modOf(p.path);
      if (pm === null || pm === hotMod || p.count < 3) continue;
      const key = [e.path, p.path].sort().join('\u0000');
      if (kept.has(key)) continue;
      partners.push({ path: p.path, module: pm, count: p.count });
    }
    if (!partners.length) continue;
    for (const p of partners) kept.add([e.path, p.path].sort().join('\u0000'));
    out.push({
      candidateId: `co-change:${e.path}`, kind: 'co-change', subject: [e.path, ...partners.map((p) => p.path)],
      facts: { commits: e.commits, partners },
      score: round4((partners[0].count / e.commits) * rowOf[e.path].hotspotScore),
    });
  }
  return out.sort((p, q) => q.score - p.score || cmp(p.candidateId, q.candidateId));
}

test('S6: --digest-out writes one compact line with the resolved metrics path; a rerun is byte-identical; parents are created', () => {
  const repo = once('digestfx', buildDigestFx);
  const s = slot('s6');
  s.dig = path.join(s.dir, 'nested', 'deeper', 'd.json');
  const first = runWithDigest(repo, [], { slot: s });
  assert.equal(first.digestText.endsWith('\n'), true);
  assert.equal(first.digestText.indexOf('\n'), first.digestText.length - 1, 'one line only');
  assert.equal(first.digest.contract, 'audit/digest-v1');
  const expectedPath = path.resolve(s.out).replace(/\\/g, '/');
  assert.equal(first.digest.metricsPath, expectedPath);
  if (process.platform === 'win32') {
    assert.equal(first.digest.metricsPath.includes('\\'), false);
  }
  // the digest is the library digest of the metrics file with metricsPath set, top 10 and candidates 12 by default
  const expected = serializeDigest({ ...buildDigest(first.metrics, { top: 10, candidates: 12 }), metricsPath: expectedPath });
  assert.equal(first.digestText, expected);
  // replay: the same arguments again give byte-identical files
  const firstBytes = readFileSync(s.dig);
  const firstMetrics = readFileSync(s.out);
  const second = runWithDigest(repo, [], { slot: s });
  assert.deepEqual(readFileSync(s.dig), firstBytes);
  assert.deepEqual(readFileSync(s.out), firstMetrics);
  assert.equal(second.digestText, first.digestText);
});

test('S6b: a relative --out is resolved against the working directory for metricsPath; --top reaches the digest', () => {
  const repo = once('digestfx', buildDigestFx);
  const cwd = path.join(workRoot, 'relcwd');
  mkdirSync(cwd, { recursive: true });
  const r = runCli(
    baseArgs(repo, path.join('rel', 'm.json'), ['--module-depth', '1', '--top', '1', '--digest-out', path.join('rel', 'd.json')]),
    { cwd },
  );
  assert.equal(r.status, 0, r.stderr);
  const digest = JSON.parse(readFileSync(path.join(cwd, 'rel', 'd.json'), 'utf8'));
  assert.equal(digest.metricsPath, path.resolve(cwd, 'rel', 'm.json').replace(/\\/g, '/'));
  const metrics = JSON.parse(readFileSync(path.join(cwd, 'rel', 'm.json'), 'utf8'));
  assert.deepEqual(digest, { ...buildDigest(metrics, { top: 1, candidates: 12 }), metricsPath: digest.metricsPath });
  assert.equal(digest.topComplexity.length, 1);
  assert.equal(digest.unstableModules.length, 1);
});

test('S7: on a fixture repository the candidates equal the D3/D4 recomputation from that run metrics', () => {
  const repo = once('digestfx', buildDigestFx);
  const { digest, metrics } = runWithDigest(repo, ['--candidates', '50']);
  const expected = oracleCandidates(metrics, 10);
  assert.deepEqual(digest.candidates, expected);
  // the fixture exercises every kind (otherwise the comparison above would be thin)
  assert.deepEqual([...new Set(digest.candidates.map((c) => c.kind))].sort(), [
    'co-change', 'cycle', 'hot-file', 'unstable-module',
  ]);
  // hand-derived from the import graph: x <-> y is the only cycle, two files, two edges (y -> z is outside it)
  const cyc = digest.candidates.find((c) => c.kind === 'cycle');
  assert.equal(cyc.candidateId, 'cycle:src/x');
  assert.deepEqual(cyc.subject, ['src/x', 'src/y']);
  assert.equal(cyc.facts.fileCount, 2);
  assert.equal(cyc.facts.edges, 2);
  // a and b changed together in all 4 commits, in different modules: a co-change pair
  const co = digest.candidates.find((c) => c.kind === 'co-change');
  assert.equal(co.facts.commits, 4);
  assert.ok(co.facts.partners.every((p) => p.count >= 3 && p.module !== ''), JSON.stringify(co.facts));
  // sorted: score descending
  for (let i = 1; i < digest.candidates.length; i += 1) {
    assert.ok(digest.candidates[i - 1].score >= digest.candidates[i].score);
  }
});

test('S8: every files row ends with module, a module node id; per id the row count equals node.files', () => {
  const repo = once('digestfx', buildDigestFx);
  const { metrics } = runWithDigest(repo);
  const keys = Object.keys(metrics.files[0]);
  assert.equal(keys[keys.length - 1], 'module');
  assert.equal(keys.length, 11);
  const ids = new Set(metrics.modules.nodes.map((n) => n.id));
  const counts = {};
  for (const f of metrics.files) {
    assert.equal(Object.keys(f).pop(), 'module', f.path);
    assert.equal(typeof f.module, 'string', f.path);
    assert.ok(ids.has(f.module), `${f.path}: ${f.module}`);
    counts[f.module] = (counts[f.module] ?? 0) + 1;
  }
  for (const n of metrics.modules.nodes) assert.equal(counts[n.id], n.files, n.id);
  // from the module roll-up oracle of the existing suite (depth 1): one file per module
  assert.deepEqual(
    Object.fromEntries(metrics.files.map((f) => [f.path, f.module])),
    { 'src/x/a.mjs': 'src/x', 'src/y/b.mjs': 'src/y', 'src/z/c.mjs': 'src/z' },
  );
});

test('S8b: files without imports still carry a string module that is a node id and the counts agree', () => {
  const repo = once('rootonly', () =>
    makeRepo('rootonly', [
      { date: '2026-02-01', files: { 'f.txt': 'f\n  child\n    grand\n', 'g.txt': 'g\n  child\n', 'sub/h.txt': 'h\n  c\n' } },
      { date: '2026-03-01', files: { 'f.txt': 'f\n  child 2\n    grand\n' } },
    ]));
  const out = slot('s8b').out;
  const r = runCli(baseArgs(repo, out, [], { paths: '.' }));
  assert.equal(r.status, 0, r.stderr);
  const m = JSON.parse(readFileSync(out, 'utf8'));
  const ids = new Set(m.modules.nodes.map((n) => n.id));
  const counts = {};
  for (const f of m.files) {
    assert.equal(typeof f.module, 'string', f.path);
    assert.ok(ids.has(f.module), `${f.path}: ${JSON.stringify(f.module)}`);
    counts[f.module] = (counts[f.module] ?? 0) + 1;
  }
  for (const n of m.modules.nodes) assert.equal(counts[n.id], n.files, JSON.stringify(n.id));
});

test('S9: --candidates N keeps exactly the first N of the full ranked list; omitted keeps min(12, total)', () => {
  const repo = once('digestfx', buildDigestFx);
  const s = slot('s9');
  const full = runWithDigest(repo, ['--candidates', '50'], { slot: s });
  const all = full.digest.candidates;
  assert.ok(all.length >= 5 && all.length < 12, `fixture candidate count ${all.length}`);
  const rest = (d) => ({ ...d, candidates: null });
  for (const n of [1, 2]) {
    const run = runWithDigest(repo, ['--candidates', String(n)], { slot: s });
    assert.deepEqual(run.digest.candidates, all.slice(0, n), `--candidates ${n}`);
    assert.deepEqual(rest(run.digest), rest(full.digest));
  }
  const dflt = runWithDigest(repo, [], { slot: s });
  assert.deepEqual(dflt.digest.candidates, all.slice(0, Math.min(12, all.length)));
  const big = runWithDigest(repo, ['--candidates', String(all.length + 7)], { slot: s });
  assert.deepEqual(big.digest.candidates, all);
});

// ------------------------------------------------------------- usage errors

function assertRejected(label, args, flag, files) {
  const r = runCli(args);
  assert.equal(r.status, 2, `${label}: exit ${r.status}, stderr ${r.stderr}`);
  assert.match(reasonOf(r.stderr), flag, `${label}: reason does not name the flag, stderr ${r.stderr}`);
  for (const f of files) assert.equal(existsSync(f), false, `${label}: ${f} was written`);
}

test('S11: --digest-out without a value, or naming the same file as --out, exits 2 and writes neither file', () => {
  const repo = once('digestfx', buildDigestFx);
  const none = slot('s11a');
  assertRejected('no value', baseArgs(repo, none.out, ['--digest-out']), /--digest-out/, [none.out, none.dig]);

  const same = slot('s11b');
  assertRejected('same file', baseArgs(repo, same.out, ['--digest-out', same.out]), /--digest-out/, [same.out]);

  const detour = slot('s11c');
  const spelled = path.join(path.dirname(detour.out), 'sub', '..', path.basename(detour.out));
  assertRejected('detour spelling', baseArgs(repo, detour.out, ['--digest-out', spelled]), /--digest-out/, [detour.out]);

  const dotted = slot('s11d');
  const dot = path.join(path.dirname(dotted.out), '.', path.basename(dotted.out));
  assertRejected('dot spelling', baseArgs(repo, dotted.out, ['--digest-out', dot]), /--digest-out/, [dotted.out]);
});

test('S11b (windows): a --digest-out that differs from --out only in case names the same file', { skip: process.platform !== 'win32' }, () => {
  const repo = once('digestfx', buildDigestFx);
  const s = slot('s11e');
  const upper = path.join(path.dirname(s.out), path.basename(s.out).toUpperCase());
  assertRejected('case spelling', baseArgs(repo, s.out, ['--digest-out', upper]), /--digest-out/, [s.out]);
});

test('S11c (linux): a --digest-out that differs from --out only in case is another file and both are written', { skip: process.platform !== 'linux' }, () => {
  const repo = once('digestfx', buildDigestFx);
  const s = slot('s11f');
  const upper = path.join(path.dirname(s.out), 'M.JSON');
  const r = runCli(baseArgs(repo, s.out, ['--module-depth', '1', '--digest-out', upper]));
  assert.equal(r.status, 0, r.stderr);
  assert.equal(existsSync(s.out), true);
  assert.equal(JSON.parse(readFileSync(upper, 'utf8')).contract, 'audit/digest-v1');
});

test('S12: a bad --candidates, or --candidates without --digest-out, exits 2 naming --candidates and writes nothing', () => {
  const repo = once('digestfx', buildDigestFx);
  for (const bad of ['0', '1.5', 'abc', '-1']) {
    const s = slot('s12');
    assertRejected(`--candidates ${bad}`, baseArgs(repo, s.out, ['--digest-out', s.dig, '--candidates', bad]), /--candidates/, [s.out, s.dig]);
  }
  const noValue = slot('s12v');
  assertRejected('--candidates without a value', baseArgs(repo, noValue.out, ['--digest-out', noValue.dig, '--candidates']), /--candidates/, [noValue.out, noValue.dig]);
  const orphan = slot('s12o');
  assertRejected('--candidates without --digest-out', baseArgs(repo, orphan.out, ['--candidates', '3']), /--candidates/, [orphan.out, orphan.dig]);
});

test('S17: without --digest-out no digest is written and the metrics output equals a run with the flag', () => {
  const repo = once('digestfx', buildDigestFx);
  const plain = slot('s17');
  const r = runCli(baseArgs(repo, plain.out, ['--module-depth', '1']));
  assert.equal(r.status, 0, r.stderr);
  assert.deepEqual(readdirSync(plain.dir), [path.basename(plain.out)]);
  const withDigest = runWithDigest(repo);
  assert.equal(withDigest.metricsText, readFileSync(plain.out, 'utf8'));
});

// ------------------------------------------------------------------ probes

test('probe: a non-ASCII file name survives into the digest', () => {
  const name = 'caf\u00e9.txt';
  const repo = once('unicode', () =>
    makeRepo('unicode', [
      { date: '2026-02-01', files: { [name]: 'c\n  child\n    grand\n' } },
      { date: '2026-03-01', files: { [name]: 'c\n  child 2\n    grand\n' } },
    ]));
  const s = slot('uni');
  const r = runCli(baseArgs(repo, s.out, ['--digest-out', s.dig], { paths: '.' }));
  assert.equal(r.status, 0, r.stderr);
  const digest = JSON.parse(readFileSync(s.dig, 'utf8'));
  assert.deepEqual(digest.topComplexity.map((t) => t.path), [name]);
  assert.equal(readFileSync(s.dig).includes(Buffer.from(name, 'utf8')), true);
});
