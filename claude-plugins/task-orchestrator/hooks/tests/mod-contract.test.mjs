// Contract test of the task-orchestrator-mod against the plugin's 4.0 tool catalog (item 5dbd8275, scenario S17;
// AC1, AC9 and AC10 of the frozen task-scope). Authored blind to the mod's implementation: it reads the mod's
// files only as text, and every expectation comes from the generated catalog (hooks/lib/tool-catalog.json, whose
// tools of kind "ops" are the write tools), from RETIRED_3X_TOOLS of scripts/lib/contract-checks.mjs, and from the
// AC1/AC9/AC10 wording. Node 22, node:test, no dependencies.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { RETIRED_3X_TOOLS } from '../../scripts/lib/contract-checks.mjs';
import { TOOLS, WRITE_TOOLS } from '../lib/tool-names.mjs';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/\\]$/, '');
const REPO = join(PLUGIN, '..', '..');
const MOD = join(REPO, 'claude-plugins', 'task-orchestrator-mod');
const CATALOG = JSON.parse(readFileSync(join(PLUGIN, 'hooks', 'lib', 'tool-catalog.json'), 'utf8'));

const CATALOG_TOOLS = Object.keys(CATALOG.tools).sort();
const CATALOG_WRITE = CATALOG_TOOLS.filter((t) => CATALOG.tools[t].kind === 'ops');

const posix = (p) => p.split(sep).join('/');
const text = (abs) => readFileSync(abs, 'utf-8').replace(/\r\n/g, '\n');

/** Every file under `dir` whose name satisfies `keep`, skipping dependency, generated and VCS directories. */
function files(dir, keep) {
  const out = [];
  const walk = (d) => {
    if (!existsSync(d)) return;
    for (const name of readdirSync(d).sort()) {
      if (name === 'node_modules' || name === '.git' || name === '.claude-plugin') continue;
      const abs = join(d, name);
      if (statSync(abs).isDirectory()) walk(abs);
      else if (keep(name)) out.push(abs);
    }
  };
  walk(dir);
  return out;
}

// ---- S17: TO_TOOLS and TO_WRITE_TOOLS equal the catalog ---------------------------------------------

/** The single-quoted names of `export const <name>: readonly string[] = Object.freeze([ ... ])`. */
function frozenList(source, name) {
  const re = new RegExp('export const ' + name + ': readonly string\\[\\] = Object\\.freeze\\(\\[([^\\]]*)\\]\\)');
  const m = re.exec(source);
  assert.ok(m, name + ' must be declared as an exported frozen array literal');
  const names = [...m[1].matchAll(/'([a-z_]+)'/g)].map((x) => x[1]);
  assert.equal(names.length, m[1].split(',').filter((s) => s.trim() !== '').length, name + ' must list only single-quoted names');
  return names;
}

const constants = text(join(MOD, 'src', 'shared', 'constants.ts'));

test('S17: the catalog is the 13 tools, 7 of them write (ops) tools', () => {
  assert.equal(CATALOG_TOOLS.length, 13);
  assert.deepEqual(CATALOG_WRITE, ['advance', 'config', 'deps_write', 'docs_write', 'items_write', 'notes_write', 'runs_write']);
  assert.deepEqual(CATALOG_TOOLS, [...TOOLS], 'tool-names.mjs TOOLS agrees with the catalog');
  assert.deepEqual(CATALOG_WRITE, [...WRITE_TOOLS], 'tool-names.mjs WRITE_TOOLS agrees with the catalog');
});

test('S17: TO_TOOLS lists exactly the catalog tools, sorted', () => {
  const list = frozenList(constants, 'TO_TOOLS');
  assert.deepEqual(list, CATALOG_TOOLS);
  assert.deepEqual(list, [...list].sort());
});

test('S17: TO_WRITE_TOOLS lists exactly the catalog ops tools, sorted', () => {
  const list = frozenList(constants, 'TO_WRITE_TOOLS');
  assert.deepEqual(list, CATALOG_WRITE);
  assert.deepEqual(list, [...list].sort());
});

test('S17: a list with an extra or a missing tool would not pass the parity check (the check is not vacuous)', () => {
  const retired = ['query', 'items'].join('_');
  const withExtra = constants.replace("'advance',", "'advance', '" + retired + "',");
  assert.notDeepEqual(frozenList(withExtra, 'TO_TOOLS'), CATALOG_TOOLS);
  const withoutOne = constants.replace("'report', ", '');
  assert.notDeepEqual(frozenList(withoutOne, 'TO_TOOLS'), CATALOG_TOOLS);
});

// ---- S17: no retired 3.x tool name anywhere in the mod ----------------------------------------------

// complete_tree is an items_write op in 4.0, so the bare word is legitimate; the other 16 names are not.
const BARE = RETIRED_3X_TOOLS.filter((n) => n !== 'complete_tree');
const BARE_RE = new RegExp('(?<![A-Za-z0-9])(' + BARE.join('|') + ')(?![A-Za-z0-9_])', 'g');

/** The retired names on each line of a text, as { line, name }. */
function retiredHits(source) {
  const hits = [];
  source.split('\n').forEach((line, i) => {
    for (const m of line.matchAll(BARE_RE)) hits.push({ line: i + 1, name: m[1] });
  });
  return hits;
}

test('S17: the scan finds a retired name and lets complete_tree through (the scan is not vacuous)', () => {
  assert.deepEqual(retiredHits('a\ncall ' + ['query', 'items'].join('_') + ' now\n'), [{ line: 2, name: 'query_items' }]);
  assert.deepEqual(retiredHits('items_write op complete_tree is fine\n'), []);
  assert.deepEqual(retiredHits('const x = ' + ['manage', 'notes'].join('_') + 'x\n'), []);
});

test('S17: no retired 3.x tool name in any mod .ts file or in the mod README', () => {
  const targets = [...files(MOD, (n) => n.endsWith('.ts')), join(MOD, 'README.md')];
  assert.ok(targets.length > 10, 'the scan reached the mod sources: ' + targets.length);
  const found = [];
  for (const abs of targets) {
    for (const h of retiredHits(text(abs))) found.push(posix(relative(MOD, abs)) + ':' + h.line + ' ' + h.name);
  }
  assert.deepEqual(found, []);
});

// ---- S17: every tool named in a matcher literal is a catalog tool -----------------------------------

// A regex literal (or its string spelling) for the engine's tool names: ...task-orchestrator.*__<name>|<name>...
const MATCHER_RE = /mcp__[^\s'"`]*task-orchestrator[^\s'"`]*?__\(?([a-z0-9_]+(?:\|[a-z0-9_]+)*)\)?\$?/g;

test('S17: every tool name inside a task-orchestrator tool-name matcher in mod src and hooks is a catalog tool', () => {
  const targets = [
    ...files(join(MOD, 'src'), (n) => (n.endsWith('.ts') || n.endsWith('.json')) && !n.endsWith('.test.ts')),
    ...files(join(MOD, 'hooks'), (n) => n.endsWith('.ts') || n.endsWith('.json')),
  ];
  assert.ok(targets.length > 5, 'the scan reached the mod sources: ' + targets.length);
  const known = new Set(CATALOG_TOOLS);
  const found = [];
  const bad = [];
  for (const abs of targets) {
    text(abs).split('\n').forEach((line, i) => {
      for (const m of line.matchAll(MATCHER_RE)) {
        for (const name of m[1].split('|')) {
          found.push(name);
          if (!known.has(name)) bad.push(posix(relative(MOD, abs)) + ':' + (i + 1) + ' ' + name);
        }
      }
    });
  }
  assert.ok(found.length >= 6, 'at least the graph write-tool matcher was found: ' + found.length);
  assert.deepEqual(bad, []);
});

test('S17: the matcher scan would catch an unknown name (the scan is not vacuous)', () => {
  const line = "const WRITE_TOOL_NAME = /^mcp__.*task-orchestrator.*__(advance|items_writes)$/";
  const names = [...line.matchAll(MATCHER_RE)].flatMap((m) => m[1].split('|'));
  assert.deepEqual(names, ['advance', 'items_writes']);
  assert.ok(!CATALOG_TOOLS.includes('items_writes'));
});

// ---- AC10: the CI workflow --------------------------------------------------------------------------

const workflow = text(join(REPO, '.github', 'workflows', 'task-orchestrator-mod.yml'));

/** The lines of the two-space-indented key `key:` under `on:`, up to the next key at that indent. */
function onBlock(key) {
  const lines = workflow.split('\n');
  const start = lines.findIndex((l) => l === '  ' + key + ':');
  assert.ok(start >= 0, 'the workflow has an on.' + key + ' block');
  const out = [];
  for (let i = start + 1; i < lines.length; i++) {
    if (/^ {2}\S/.test(lines[i]) || /^\S/.test(lines[i])) break;
    out.push(lines[i]);
  }
  return out;
}
const pathsOf = (block) => block.filter((l) => /^ {6}- /.test(l)).map((l) => l.replace(/^ {6}- /, '').replace(/^['"]|['"]$/g, ''));

const NEW_PATHS = [
  'claude-plugins/task-orchestrator/hooks/lib/tool-catalog.json',
  'claude-plugins/task-orchestrator/hooks/lib/tool-names.mjs',
  'claude-plugins/task-orchestrator/hooks/retro-lib.mjs',
  'claude-plugins/task-orchestrator/hooks/tests/mod-contract.test.mjs',
];
const KEPT_PATHS = ['claude-plugins/task-orchestrator-mod/**', 'scripts/ci/check-task-orchestrator-mod.mjs', '.github/workflows/task-orchestrator-mod.yml'];

test('AC10: the workflow runs on pull requests to main and to release branches', () => {
  const branches = onBlock('pull_request').find((l) => l.trim().startsWith('branches:'));
  assert.ok(branches, 'pull_request has a branches filter');
  assert.ok(/(^|[\s[,'"])main([\s\],'"]|$)/.test(branches), 'main is listed: ' + branches);
  assert.ok(branches.includes('release/**'), 'release/** is listed: ' + branches);
});

test('AC10: both path lists include the catalog, the tool names, the retro lib and this test, and keep the old paths', () => {
  for (const key of ['push', 'pull_request']) {
    const paths = pathsOf(onBlock(key));
    for (const p of NEW_PATHS) assert.ok(paths.includes(p), key + ' paths include ' + p);
    for (const p of KEPT_PATHS) assert.ok(paths.includes(p), key + ' paths keep ' + p);
  }
});

test('AC10: the new workflow paths exist in the repository', () => {
  for (const p of NEW_PATHS) assert.ok(existsSync(join(REPO, ...p.split('/'))), p + ' exists');
});

test('AC10: the CLI pin and the single gate script are unchanged', () => {
  assert.ok(workflow.includes("CLAUDE_CODE_VERSION: '2.1.286'"));
  assert.ok(workflow.includes('node scripts/ci/check-task-orchestrator-mod.mjs'));
});
