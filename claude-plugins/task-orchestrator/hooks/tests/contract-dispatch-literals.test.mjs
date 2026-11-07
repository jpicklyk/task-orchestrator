// Guard: no model alias or effort level is used as a dispatch value in the plugin outside
// dispatch-policy.json (item 8da6e710; plan "Keeping it single"). The allow-list is a two-way ratchet.
// Node 22, node:test, no dependencies.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { scanDispatchLiterals } from '../../scripts/lib/contract-checks.mjs';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/]$/, '');
const ALLOWLIST_PATH = join(PLUGIN, 'scripts', 'lib', 'dispatch-literals-allowlist.json');
const DIRS = ['hooks', 'scripts', 'workflows', 'agents', 'bundled-rules', 'skills'];

const tmpDirs = [];
after(() => {
  for (const d of tmpDirs) rmSync(d, { recursive: true, force: true });
});

function fixtureRoot(files) {
  const root = mkdtempSync(join(tmpdir(), 'dlit-'));
  tmpDirs.push(root);
  for (const d of DIRS) mkdirSync(join(root, d), { recursive: true });
  for (const [rel, text] of Object.entries(files)) {
    const abs = join(root, ...rel.split('/'));
    mkdirSync(dirname(abs), { recursive: true });
    writeFileSync(abs, text);
  }
  return root;
}

const scan = (files, allowlist = []) => scanDispatchLiterals({ pluginRoot: fixtureRoot(files), dirs: DIRS, allowlist });

const allowlist = JSON.parse(readFileSync(ALLOWLIST_PATH, 'utf8'));

test('AC1: the plugin tree has no dispatch literal outside the policy and its allow-list', () => {
  const r = scanDispatchLiterals({ pluginRoot: PLUGIN, dirs: DIRS, allowlist });
  assert.deepEqual(r.violations, [], JSON.stringify(r.violations.slice(0, 5)));
  assert.deepEqual(r.stale, [], JSON.stringify(r.stale.slice(0, 5)));
});

test('AC1: an injected literal fails, in every dispatch-value shape', () => {
  const shapes = {
    'scripts/a.mjs': "const d = { model: 'sonnet' }\n",
    'hooks/b.mjs': 'const e = { effort: "high" }\n',
    'workflows/c.mjs': "const args = ['--effort', 'xhigh']\n",
    'skills/x/SKILL.md': 'Run claude --model opus now\n',
    'agents/y.md': 'effort=max\n',
    'bundled-rules/z.md': 'model=haiku\n',
  };
  const r = scan(shapes);
  assert.deepEqual(r.violations.map((v) => v.file).sort(), Object.keys(shapes).sort());
  assert.deepEqual(r.stale, []);
});

test('AC1: prose words and non-dispatch values do not match', () => {
  const r = scan({
    'skills/x/SKILL.md': 'The opus model has high effort; medium is the default. model: inherit\nmodel: sonnetish\n',
    'scripts/a.mjs': 'const model = pick(); const effort = level; const highEffort = 1\n',
  });
  assert.deepEqual(r.violations, []);
});

test('AC1: an allow-listed line passes, another literal in the same file still fails', () => {
  const files = { 'skills/x/SKILL.md': 'example { model: sonnet }\nreal { model: opus }\n' };
  const r = scan(files, [{ file: 'skills/x/SKILL.md', text: 'example { model: sonnet }' }]);
  assert.equal(r.violations.length, 1);
  assert.equal(r.violations[0].line, 2);
  assert.deepEqual(r.stale, []);
});

test('AC2: a stale allow-list entry fails (missing file, missing text, or text without a literal)', () => {
  const files = { 'skills/x/SKILL.md': 'example { model: sonnet }\nplain line\n' };
  const r = scan(files, [
    { file: 'skills/gone.md', text: 'model: sonnet' },
    { file: 'skills/x/SKILL.md', text: 'no such text' },
    { file: 'skills/x/SKILL.md', text: 'plain line' },
  ]);
  assert.equal(r.stale.length, 3);
  assert.deepEqual(r.violations.map((v) => v.line), [1]);
});

test('AC2: allow-list shape - plugin-relative posix paths, no duplicate rows, no tests/ paths', () => {
  const seen = new Set();
  for (const row of allowlist) {
    assert.ok(typeof row.file === 'string' && typeof row.text === 'string' && row.text !== '');
    assert.ok(!row.file.startsWith('/') && !row.file.includes('\\'), row.file);
    assert.ok(!row.file.split('/').includes('tests'), `${row.file} is not scanned`);
    const key = `${row.file}\n${row.text}`;
    assert.ok(!seen.has(key), `duplicate row ${key}`);
    seen.add(key);
  }
});
