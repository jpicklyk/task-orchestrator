// Contract tests for the hard-coded-name scan in scripts/lib/contract-checks.mjs (scanToolNames,
// RETIRED_3X_TOOLS) and its two-way-ratcheted baseline (58facb39). Scenarios S8, S9, S14 match the
// item's frozen `test-plan` note. Oracles: task-scope AC2 scan rules, the 3.x server tool list
// (S14), and the 4.0 tool names derived from current/src/test/resources/golden/tools/*.json.
// Node 22, node:test, no dependencies.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { RETIRED_3X_TOOLS, scanToolNames } from '../../scripts/lib/contract-checks.mjs';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/]$/, '');
const GOLDEN_DIR = join(PLUGIN, '..', '..', 'current', 'src', 'test', 'resources', 'golden', 'tools');
const BASELINE_PATH = join(PLUGIN, 'scripts', 'lib', 'contract-names-baseline.json');
const DIRS = ['hooks', 'scripts', 'workflows', 'agents', 'bundled-rules', 'skills'];
const CR = String.fromCharCode(13);
const BACKSLASH = String.fromCharCode(92);
const SERVER_PREFIX = 'mcp__mcp-task-orchestrator__';

const TOOLS = [...new Set(readdirSync(GOLDEN_DIR).filter((f) => f.endsWith('.json')).map((f) => f.split('.')[0]))].sort();

const tmpDirs = [];
after(() => {
  for (const d of tmpDirs) rmSync(d, { recursive: true, force: true });
});

/** Builds a throwaway plugin root: every scanned dir exists, plus the given { relPath: text } files. */
function fixtureRoot(files) {
  const root = mkdtempSync(join(tmpdir(), 'cnames-'));
  tmpDirs.push(root);
  for (const d of DIRS) mkdirSync(join(root, d), { recursive: true });
  for (const [rel, text] of Object.entries(files)) {
    const abs = join(root, ...rel.split('/'));
    mkdirSync(dirname(abs), { recursive: true });
    writeFileSync(abs, text);
  }
  return root;
}

const EMPTY_BASELINE = { pendingPort: [], proseExceptions: [] };

function scan(files, baseline = EMPTY_BASELINE) {
  return scanToolNames({ pluginRoot: fixtureRoot(files), dirs: DIRS, tools: TOOLS, baseline });
}

// ---- S14 ---------------------------------------------------------------------------------

test('S14: RETIRED_3X_TOOLS is the frozen, sorted list of the 17 3.x server tools', () => {
  assert.deepEqual([...RETIRED_3X_TOOLS], [
    'advance_item', 'claim_item', 'complete_tree', 'create_work_tree', 'get_blocked_items', 'get_context',
    'get_next_item', 'get_next_status', 'manage_dependencies', 'manage_items', 'manage_notes',
    'manage_plan_documents', 'manage_project_config', 'query_dependencies', 'query_items', 'query_notes', 'query_rules',
  ]);
  assert.ok(Object.isFrozen(RETIRED_3X_TOOLS));
  assert.deepEqual([...RETIRED_3X_TOOLS], [...RETIRED_3X_TOOLS].sort());
  for (const t of RETIRED_3X_TOOLS.filter((x) => x !== 'complete_tree')) {
    assert.ok(!TOOLS.includes(t), `${t} must not also be a 4.0 tool`);
  }
});

// ---- S8: the repository ------------------------------------------------------------------

const baseline = JSON.parse(readFileSync(BASELINE_PATH, 'utf8'));

test('S8: scanning the plugin with the checked-in baseline finds 0 violations and 0 stale baseline rows', () => {
  const r = scanToolNames({ pluginRoot: PLUGIN, dirs: DIRS, tools: TOOLS, baseline });
  assert.deepEqual(r.violations, [], JSON.stringify(r.violations.slice(0, 5)));
  assert.deepEqual(r.stale, [], JSON.stringify(r.stale.slice(0, 5)));
});

test('S8: baseline shape - 8-hex owners, plugin-relative posix paths, no duplicate rows, files exist, no tests/ paths', () => {
  assert.deepEqual(Object.keys(baseline).sort(), ['pendingPort', 'proseExceptions']);
  assert.ok(baseline.pendingPort.length > 0, 'baseline has pending rows today');
  const seen = new Set();
  for (const row of baseline.pendingPort) {
    assert.match(row.owner, /^[0-9a-f]{8}$/, `owner of ${row.file}`);
    assert.ok(!row.file.includes(BACKSLASH), `${row.file} must use forward slashes`);
    assert.ok(!row.file.startsWith('/') && !row.file.includes('..'), `${row.file} must be plugin-relative`);
    assert.ok(!row.file.split('/').includes('tests'), `${row.file} must not be under tests/`);
    assert.ok(existsSync(join(PLUGIN, ...row.file.split('/'))), `${row.file} must exist`);
    assert.ok(!seen.has(row.file), `duplicate pendingPort row ${row.file}`);
    seen.add(row.file);
  }
  const seenProse = new Set();
  for (const row of baseline.proseExceptions) {
    assert.ok(existsSync(join(PLUGIN, ...row.file.split('/'))), `${row.file} must exist`);
    assert.ok(row.text.length > 0);
    const k = `${row.file}\u0000${row.text}`;
    assert.ok(!seenProse.has(k), `duplicate proseException row for ${row.file}`);
    seenProse.add(k);
  }
});

test('S8: the two self-exempt files may name retired tools without being baselined', () => {
  const r = scan({
    'scripts/lib/contract-checks.mjs': 'query_items manage_notes advance_item',
    'scripts/lib/contract-names-baseline.json': '{"x":"get_context"}',
  });
  assert.deepEqual(r.violations, []);
  assert.deepEqual(r.stale, []);
});

// ---- S9: fixtures ------------------------------------------------------------------------

test('S9: an unlisted bare retired name is a violation carrying file, 1-based line and name', () => {
  const r = scan({ 'hooks/x.mjs': '// header\nconst t = "query_items"\n' });
  assert.equal(r.violations.length, 1, JSON.stringify(r.violations));
  const v = r.violations[0];
  assert.equal(v.file, 'hooks/x.mjs');
  assert.equal(v.line, 2);
  assert.equal(v.name, 'query_items');
  assert.equal(v.reason, 'retired 3.x tool name');
  assert.deepEqual(r.stale, []);
});

test('S9: every retired name except complete_tree is detected bare; complete_tree alone is not a violation', () => {
  for (const name of RETIRED_3X_TOOLS.filter((t) => t !== 'complete_tree')) {
    const r = scan({ 'skills/s/SKILL.md': `call ${name} now\n` });
    assert.equal(r.violations.length, 1, `${name}: ${JSON.stringify(r.violations)}`);
    assert.equal(r.violations[0].name, name);
  }
  assert.deepEqual(scan({ 'skills/s/SKILL.md': 'finish with complete_tree\n' }).violations, []);
});

test('S9: bare names are matched on word boundaries (an identifier that merely contains one is not a hit)', () => {
  assert.deepEqual(scan({ 'hooks/x.mjs': 'const xquery_items = 1; const query_itemsfoo = 2\n' }).violations, []);
});

const BARE_REASON = 'retired 3.x tool name';
const QUALIFIED_REASON = 'qualified tool name is not a 4.0 tool';

// Amendment A2 / review B1. Oracle: task-scope A2 scan contract - a bare retired name counts when not
// preceded by [A-Za-z0-9] and not followed by [A-Za-z0-9_]; the qualified-literal server segment also
// admits the regex metacharacters * + ?.
test('S9 (A2): a retired name behind a _ or __ prefix counts as a bare retired name', () => {
  const r = scan({
    'hooks/suffix.mjs': "const ok = name.endsWith('__advance_item')\n",
    'hooks/single.mjs': 'const f = _query_items\n',
  });
  assert.equal(r.violations.length, 2, JSON.stringify(r.violations));
  const byFile = Object.fromEntries(r.violations.map((v) => [v.file, v]));
  assert.deepEqual(Object.keys(byFile).sort(), ['hooks/single.mjs', 'hooks/suffix.mjs']);
  assert.equal(byFile['hooks/suffix.mjs'].name, 'advance_item');
  assert.equal(byFile['hooks/suffix.mjs'].reason, BARE_REASON);
  assert.equal(byFile['hooks/suffix.mjs'].line, 1);
  assert.equal(byFile['hooks/single.mjs'].name, 'query_items');
  assert.equal(byFile['hooks/single.mjs'].reason, BARE_REASON);
});

test('S9 (A2): a retired name inside a regex literal or an endsWith() call counts', () => {
  const regex = scan({ 'hooks/re.mjs': 'if (/__advance_item$/.test(tool)) return {}\n' });
  assert.equal(regex.violations.length, 1, JSON.stringify(regex.violations));
  assert.equal(regex.violations[0].name, 'advance_item');
  assert.equal(regex.violations[0].reason, BARE_REASON);

  const ends = scan({ 'hooks/ends.mjs': "const w = tool.endsWith('__manage_notes')\n" });
  assert.equal(ends.violations.length, 1, JSON.stringify(ends.violations));
  assert.equal(ends.violations[0].name, 'manage_notes');
  assert.equal(ends.violations[0].reason, BARE_REASON);
});

test('S9 (A2): a retired name followed by a letter, digit or underscore, or preceded by a letter or digit, is not a hit', () => {
  for (const text of [
    'const a = advance_items\n',
    'const b = xadvance_item\n',
    'const c = advance_item_v2\n',
    'const d = advance_item2\n',
    'const e = 9advance_item\n',
  ]) {
    assert.deepEqual(scan({ 'hooks/near.mjs': text }).violations, [], text);
  }
});

test('S9 (A2): a mcp__.*task-orchestrator.*__<retired> regex literal is a qualified violation even in a pendingPort file', () => {
  const files = { 'hooks/q.mjs': 'const re = /^mcp__.*task-orchestrator.*__manage_notes$/\n' };
  const listed = scan(files, { pendingPort: [{ file: 'hooks/q.mjs', owner: 'deadbeef' }], proseExceptions: [] });
  assert.equal(listed.violations.length, 1, JSON.stringify(listed.violations));
  assert.equal(listed.violations[0].file, 'hooks/q.mjs');
  assert.equal(listed.violations[0].line, 1);
  assert.ok(String(listed.violations[0].name).includes('manage_notes'), String(listed.violations[0].name));
  assert.equal(listed.violations[0].reason, QUALIFIED_REASON);

  const unlisted = scan(files);
  assert.ok(unlisted.violations.some((v) => v.reason === QUALIFIED_REASON), JSON.stringify(unlisted.violations));
});

test('S9 (A2): a mcp__.*task-orchestrator.*__<4.0 tool> regex literal is not a violation', () => {
  const r = scan({ 'hooks/ok.mjs': 'const re = /^mcp__.*task-orchestrator.*__notes_write$/\n' });
  assert.deepEqual(r.violations, []);
});

test('S9 (A2): a pendingPort file whose only retired name sits behind a __ prefix is not stale', () => {
  const r = scan(
    { 'hooks/p.mjs': 'if (/__advance_item$/.test(tool)) return {}\n' },
    { pendingPort: [{ file: 'hooks/p.mjs', owner: 'deadbeef' }], proseExceptions: [] },
  );
  assert.deepEqual(r.violations, []);
  assert.deepEqual(r.stale, []);
});

test('S9: a pendingPort file is excused for bare retired names; one without any retired name is stale', () => {
  const ok = scan(
    { 'hooks/a.mjs': 'manage_notes and query_items\n' },
    { pendingPort: [{ file: 'hooks/a.mjs', owner: 'deadbeef' }], proseExceptions: [] },
  );
  assert.deepEqual(ok.violations, []);
  assert.deepEqual(ok.stale, []);

  const stale = scan(
    { 'hooks/clean.mjs': 'only notes_write here\n' },
    { pendingPort: [{ file: 'hooks/clean.mjs', owner: 'deadbeef' }], proseExceptions: [] },
  );
  assert.deepEqual(stale.violations, []);
  assert.equal(stale.stale.length, 1, JSON.stringify(stale.stale));
  assert.equal(stale.stale[0].file, 'hooks/clean.mjs');
  assert.equal(typeof stale.stale[0].reason, 'string');
});

test('S9: a pendingPort row whose file is missing is stale', () => {
  const r = scan({}, { pendingPort: [{ file: 'hooks/gone.mjs', owner: 'deadbeef' }], proseExceptions: [] });
  assert.equal(r.stale.length, 1, JSON.stringify(r.stale));
  assert.equal(r.stale[0].file, 'hooks/gone.mjs');
});

test('S9: a proseException whose text is absent from its file is stale', () => {
  const r = scan(
    { 'skills/s/SKILL.md': 'nothing retired here\n' },
    { pendingPort: [], proseExceptions: [{ file: 'skills/s/SKILL.md', text: 'the 3.x manage_notes tool', comment: 'history' }] },
  );
  assert.deepEqual(r.violations, []);
  assert.equal(r.stale.length, 1, JSON.stringify(r.stale));
  assert.equal(r.stale[0].file, 'skills/s/SKILL.md');
});

test('S9: an excepted line passes, but another retired name on another line of that file is still a violation', () => {
  const text = 'history: the 3.x manage_notes tool was removed\nnow call query_items\n';
  const r = scan(
    { 'skills/s/SKILL.md': text },
    { pendingPort: [], proseExceptions: [{ file: 'skills/s/SKILL.md', text: 'the 3.x manage_notes tool', comment: 'history' }] },
  );
  assert.equal(r.violations.length, 1, JSON.stringify(r.violations));
  assert.equal(r.violations[0].line, 2);
  assert.equal(r.violations[0].name, 'query_items');
  assert.deepEqual(r.stale, []);
});

test('S9: a proseException applies to its own file only - the same text with a retired name in another file is a violation', () => {
  const line = 'history: the 3.x manage_notes tool was removed\n';
  const r = scan(
    { 'skills/a/SKILL.md': line, 'skills/b/SKILL.md': line },
    { pendingPort: [], proseExceptions: [{ file: 'skills/a/SKILL.md', text: 'the 3.x manage_notes tool', comment: 'history' }] },
  );
  assert.deepEqual(r.violations.map((v) => v.file), ['skills/b/SKILL.md']);
  assert.deepEqual(r.stale, []);
});

test('S9: a qualified literal with an unknown tool is a violation everywhere, even in a pendingPort file', () => {
  const r = scan(
    { 'hooks/a.mjs': `const n = '${SERVER_PREFIX}notes_writes'\n` },
    { pendingPort: [{ file: 'hooks/a.mjs', owner: 'deadbeef' }], proseExceptions: [] },
  );
  assert.equal(r.violations.length, 1, JSON.stringify(r.violations));
  assert.equal(r.violations[0].file, 'hooks/a.mjs');
  assert.equal(r.violations[0].line, 1);
  assert.ok(String(r.violations[0].name).includes('notes_writes'));
  assert.equal(r.violations[0].reason, 'qualified tool name is not a 4.0 tool');
});

test('S9: qualified literals - every 4.0 tool passes; other server-segment spellings are checked; the __* wildcard is not a name', () => {
  for (const t of TOOLS) {
    assert.deepEqual(scan({ 'workflows/w.js': `'${SERVER_PREFIX}${t}'\n` }).violations, [], `${t} is a valid qualified name`);
  }
  assert.deepEqual(scan({ 'workflows/w.js': `allow '${SERVER_PREFIX}*'\n` }).violations, []);
  assert.deepEqual(scan({ 'workflows/w.js': "'mcp__mcp-task-orchestrator-http__notes_write'\n" }).violations, []);
  const bad = scan({ 'workflows/w.js': "'mcp__plugin_task-orchestrator_task-orchestrator__advance_item'\n" });
  assert.equal(bad.violations.length, 1, JSON.stringify(bad.violations));
});

test('S9: files under a tests/ path segment or node_modules are ignored', () => {
  const r = scan({
    'hooks/tests/foo.test.mjs': 'query_items\n',
    'skills/s/tests/a.md': `${SERVER_PREFIX}bogus\n`,
    'scripts/node_modules/pkg/index.js': 'manage_items\n',
  });
  assert.deepEqual(r.violations, []);
  assert.deepEqual(r.stale, []);
});

test('S9: only the scanned extensions are read (.png is skipped; .mjs .js .json .md .yaml .yml .txt .sh are scanned)', () => {
  assert.deepEqual(scan({ 'hooks/pic.png': 'query_items\n' }).violations, []);
  for (const ext of ['mjs', 'js', 'json', 'md', 'yaml', 'yml', 'txt', 'sh']) {
    const r = scan({ [`skills/s/f.${ext}`]: 'query_items\n' });
    assert.equal(r.violations.length, 1, `.${ext} must be scanned`);
  }
});

test('S9 probe: CRLF files keep correct 1-based line numbers', () => {
  const text = `first${CR}\nsecond${CR}\nquery_items${CR}\n`;
  const r = scan({ 'hooks/crlf.mjs': text });
  assert.equal(r.violations.length, 1);
  assert.equal(r.violations[0].line, 3);
  assert.equal(r.violations[0].name, 'query_items');
});

test('S9 probe: two retired names on one line are two violations; a violation is reported per occurrence', () => {
  const r = scan({ 'hooks/x.mjs': 'query_items then manage_notes\n' });
  assert.deepEqual(r.violations.map((v) => v.name).sort(), ['manage_notes', 'query_items']);
});

test('S9 probe: a proseException line excuses every retired name on that line', () => {
  const r = scan(
    { 'skills/s/SKILL.md': 'history: manage_notes replaced query_notes in 4.0\n' },
    { pendingPort: [], proseExceptions: [{ file: 'skills/s/SKILL.md', text: 'manage_notes replaced query_notes', comment: 'history' }] },
  );
  assert.deepEqual(r.violations, []);
  assert.deepEqual(r.stale, []);
});

// ---- S2/S3 (P23 2953cc30): the project skills and project rules ---------------------------------
// Oracle: task-scope AC1/AC4 - zero retired 3.x tool names under <repo>/.claude/skills and
// <repo>/.taskorchestrator with an EMPTY baseline (a bare complete_tree is allowed by the scan rules).

const REPO = join(PLUGIN, '..', '..');
const PROJECT_DIRS = ['.claude/skills', '.taskorchestrator'];

test('S2: scanning <repo> .claude/skills and .taskorchestrator with an empty baseline finds 0 violations and 0 stale rows', () => {
  assert.ok(existsSync(join(REPO, '.claude', 'skills')), 'the project skills directory must exist');
  assert.ok(existsSync(join(REPO, '.taskorchestrator')), 'the project rules directory must exist');
  const r = scanToolNames({ pluginRoot: REPO, dirs: PROJECT_DIRS, tools: TOOLS, baseline: EMPTY_BASELINE });
  assert.deepEqual(r.violations, [], JSON.stringify(r.violations.slice(0, 10)));
  assert.deepEqual(r.stale, [], JSON.stringify(r.stale.slice(0, 10)));
});

function projectFixture(files) {
  const root = mkdtempSync(join(tmpdir(), 'cnames-proj-'));
  tmpDirs.push(root);
  for (const d of PROJECT_DIRS) mkdirSync(join(root, ...d.split('/')), { recursive: true });
  for (const [rel, text] of Object.entries(files)) {
    const abs = join(root, ...rel.split('/'));
    mkdirSync(dirname(abs), { recursive: true });
    writeFileSync(abs, text);
  }
  return root;
}

test('S3: non-vacuity - a retired name under a dot-segment directory (.claude/skills) is exactly one violation', () => {
  const root = projectFixture({ '.claude/skills/x/SKILL.md': 'query_notes' });
  const r = scanToolNames({ pluginRoot: root, dirs: PROJECT_DIRS, tools: TOOLS, baseline: EMPTY_BASELINE });
  assert.equal(r.violations.length, 1, JSON.stringify(r.violations));
  assert.equal(r.violations[0].file, '.claude/skills/x/SKILL.md');
  assert.equal(r.violations[0].line, 1);
  assert.equal(r.violations[0].name, 'query_notes');
  assert.deepEqual(r.stale, []);
});

test('S3: non-vacuity - a retired name under .taskorchestrator/rules is also found, and clean files beside it are not', () => {
  const root = projectFixture({
    '.taskorchestrator/rules/bad.md': 'first\nuse manage_notes here\n',
    '.taskorchestrator/rules/good.md': 'use notes_write here\n',
    '.claude/skills/x/SKILL.md': 'use items_read and complete_tree\n',
  });
  const r = scanToolNames({ pluginRoot: root, dirs: PROJECT_DIRS, tools: TOOLS, baseline: EMPTY_BASELINE });
  assert.equal(r.violations.length, 1, JSON.stringify(r.violations));
  assert.equal(r.violations[0].file, '.taskorchestrator/rules/bad.md');
  assert.equal(r.violations[0].line, 2);
  assert.equal(r.violations[0].name, 'manage_notes');
});