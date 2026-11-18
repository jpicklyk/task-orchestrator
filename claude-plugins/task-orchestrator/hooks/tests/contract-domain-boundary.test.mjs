// Contract tests for the domain-boundary scan in scripts/lib/contract-checks.mjs (HOST_SKILLS,
// declaredSkills, pluginShippedNames, scanDomainBoundary) and its two data files
// (domain-boundary-allowlist.json, domain-boundary-baseline.json). Scenarios S1-S15 match the item's
// frozen `test-plan` note (5243923d). Oracles: task-scope 'Library contract', 'Data files' and
// 'The two scans', acceptance criteria AC1-AC7. S16-S17 oracle: task-scope of item 63ea8f0c.
// Node 22, node:test, no dependencies.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  HOST_SKILLS,
  declaredSkills,
  pluginShippedNames,
  pluginShippedSkillNames,
  scanDomainBoundary,
} from '../../scripts/lib/contract-checks.mjs';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/\\]$/, '');
const REPO = join(PLUGIN, '..', '..');
const ALLOWLIST = JSON.parse(readFileSync(join(PLUGIN, 'scripts', 'lib', 'domain-boundary-allowlist.json'), 'utf8'));
const BASELINE = JSON.parse(readFileSync(join(PLUGIN, 'scripts', 'lib', 'domain-boundary-baseline.json'), 'utf8'));
const CR = String.fromCharCode(13);
const BACKSLASH = String.fromCharCode(92);

const tmpDirs = [];
after(() => {
  for (const d of tmpDirs) rmSync(d, { recursive: true, force: true });
});

/** Builds a throwaway root holding the given { relPath: text } files. */
function fixtureRoot(files) {
  const root = mkdtempSync(join(tmpdir(), 'cdomain-'));
  tmpDirs.push(root);
  for (const [rel, text] of Object.entries(files)) {
    const abs = join(root, ...rel.split('/'));
    mkdirSync(dirname(abs), { recursive: true });
    writeFileSync(abs, text);
  }
  return root;
}

const SKILL_SCOPE = [{ dir: 'skills', recursive: true }];

/** Scans a one-file fixture under skills/ with the given names. */
function scanText(text, names, extra = {}) {
  const root = fixtureRoot({ 'skills/x/SKILL.md': text });
  return scanDomainBoundary({ root, scopes: SKILL_SCOPE, names, ...extra });
}

function foreignNames() {
  const projectSkillDirs = readdirSync(join(REPO, '.claude', 'skills'))
    .filter((n) => statSync(join(REPO, '.claude', 'skills', n)).isDirectory());
  const projectCommands = readdirSync(join(REPO, '.claude', 'commands'))
    .filter((n) => n.endsWith('.md'))
    .map((n) => n.slice(0, -3));
  return [...new Set([...HOST_SKILLS, ...projectSkillDirs, ...projectCommands])];
}

function pluginScan(allow, baseline, pluginRoot = PLUGIN) {
  const shipped = new Set(pluginShippedSkillNames(pluginRoot));
  const names = foreignNames().filter((n) => !shipped.has(n)).sort();
  return scanDomainBoundary({
    root: pluginRoot,
    scopes: [{ dir: '.', recursive: true }],
    names,
    phrases: ['this repo'],
    allow,
    baseline,
  });
}

function projectScan(allow) {
  const declared = declaredSkills(readFileSync(join(REPO, '.taskorchestrator', 'config.yaml'), 'utf8'));
  return scanDomainBoundary({
    root: REPO,
    scopes: [{ dir: '.claude/skills', recursive: true }],
    names: HOST_SKILLS.filter((n) => !declared.includes(n)),
    allow,
  });
}

// ---- S1, S2, S3 ----------------------------------------------------------------------------

test('S1: plugin scan of the real tree reports no violation and no stale row', () => {
  const r = pluginScan(ALLOWLIST.plugin, BASELINE.pendingConfig);
  assert.deepEqual(r.violations, []);
  assert.deepEqual(r.stale, []);
});

test('S2: project scan of the real tree reports no violation and no stale row', () => {
  const r = projectScan(ALLOWLIST.project);
  assert.deepEqual(r.violations, []);
  assert.deepEqual(r.stale, []);
});

test('S3: data file shapes', () => {
  assert.deepEqual(Object.keys(BASELINE), ['pendingConfig']);
  const seenB = new Set();
  for (const row of BASELINE.pendingConfig) {
    assert.match(row.owner, /^[0-9a-f]{8}$/);
    assert.ok(typeof row.gap === 'string' && row.gap.length > 0);
    assert.ok(!row.file.includes(BACKSLASH), `posix path: ${row.file}`);
    assert.ok(!row.file.split('/').includes('tests'), `no tests segment: ${row.file}`);
    assert.ok(existsSync(join(PLUGIN, ...row.file.split('/'))), `exists: ${row.file}`);
    assert.ok(typeof row.name === 'string' && row.name.length > 0);
    const k = `${row.file}\n${row.name}`;
    assert.ok(!seenB.has(k), `duplicate baseline row ${k}`);
    seenB.add(k);
  }
  assert.deepEqual(Object.keys(ALLOWLIST).sort(), ['plugin', 'project']);
  for (const [scope, base] of [['plugin', PLUGIN], ['project', REPO]]) {
    const seen = new Set();
    for (const row of ALLOWLIST[scope]) {
      assert.match(row.audit, /^H[0-9]+$/);
      assert.ok(existsSync(join(base, ...row.file.split('/'))), `exists: ${row.file}`);
      assert.ok(typeof row.reason === 'string' && row.reason.trim().length > 0, `reason: ${row.file}`);
      assert.ok(typeof row.name === 'string' && row.name.length > 0);
      const k = `${row.file}\n${row.name}`;
      assert.ok(!seen.has(k), `duplicate ${scope} allow row ${k}`);
      seen.add(k);
    }
  }
});

// ---- S4, S5 --------------------------------------------------------------------------------

test('S4: a slash mention of a foreign skill is one violation with file, line, name and form', () => {
  const r = scanText('run /simplify now', ['simplify']);
  assert.deepEqual(r.violations, [{ file: 'skills/x/SKILL.md', line: 1, name: 'simplify', form: 'slash' }]);
  assert.deepEqual(r.stale, []);
  const r2 = scanText('first line\nrun /simplify now', ['simplify']);
  assert.deepEqual(r2.violations, [{ file: 'skills/x/SKILL.md', line: 2, name: 'simplify', form: 'slash' }]);
  const r3 = scanText('run /simplify now\nand /simplify again', ['simplify']);
  assert.deepEqual(r3.violations.map((v) => v.line), [1, 2]);
});

test('S5: the phrase check is case-insensitive, whole-word, and reports the entry as given', () => {
  const root = fixtureRoot({ 'skills/run-wave/references/post-run.md': '(this repo: x)' });
  const scan = (text) => {
    writeFileSync(join(root, 'skills', 'run-wave', 'references', 'post-run.md'), text);
    return scanDomainBoundary({ root, scopes: SKILL_SCOPE, names: [], phrases: ['this repo'] });
  };
  const r = scan('(this repo: x)');
  assert.deepEqual(r.violations, [{ file: 'skills/run-wave/references/post-run.md', line: 1, name: 'this repo', form: 'phrase' }]);
  assert.equal(scan('This repo has it').violations.length, 1);
  assert.equal(scan('this repository has it').violations.length, 0);
  assert.equal(scan('xthis repo').violations.length, 0);
});

// ---- S6, S9 --------------------------------------------------------------------------------

test('S6: a baseline or allow row with no remaining occurrence is stale; with one it is excused', () => {
  const row = { file: 'workflows/review-wave.js', name: 'security-review' };
  const scopes = [{ dir: 'workflows', recursive: true }];
  const names = ['security-review'];
  for (const key of ['baseline', 'allow']) {
    const gone = scanDomainBoundary({ root: fixtureRoot({ 'workflows/review-wave.js': 'const a = 1;\n' }), scopes, names, [key]: [row] });
    assert.equal(gone.violations.length, 0, key);
    assert.equal(gone.stale.length, 1, key);
    assert.equal(gone.stale[0].file, row.file);
    assert.equal(gone.stale[0].name, row.name);
    assert.ok(typeof gone.stale[0].reason === 'string' && gone.stale[0].reason.length > 0);

    const missing = scanDomainBoundary({ root: fixtureRoot({ 'workflows/other.js': 'const a = 1;\n' }), scopes, names, [key]: [row] });
    assert.equal(missing.violations.length, 0, key);
    assert.equal(missing.stale.length, 1, key);
    assert.equal(missing.stale[0].file, row.file);

    const present = scanDomainBoundary({ root: fixtureRoot({ 'workflows/review-wave.js': "const SKILLS = ['security-review'];\n" }), scopes, names, [key]: [row] });
    assert.deepEqual(present.violations, [], key);
    assert.deepEqual(present.stale, [], key);
  }
});

test('S9: a row excuses its name in its file only', () => {
  const root = fixtureRoot({
    'skills/a/SKILL.md': 'use /simplify here\nand /loop there\n',
    'skills/b/SKILL.md': 'use /simplify here\n',
  });
  const r = scanDomainBoundary({
    root,
    scopes: SKILL_SCOPE,
    names: ['simplify', 'loop'],
    allow: [{ file: 'skills/a/SKILL.md', name: 'simplify' }],
  });
  assert.deepEqual(
    r.violations.map((v) => `${v.file}:${v.line}:${v.name}`).sort(),
    ['skills/a/SKILL.md:2:loop', 'skills/b/SKILL.md:1:simplify'],
  );
  assert.deepEqual(r.stale, []);
});

// ---- S7, S8 --------------------------------------------------------------------------------

test('S7: slash form boundaries before and after the name', () => {
  const counted = [
    '/simplify', ' /simplify', '\t/simplify', '`/simplify`', '"/simplify"', "'/simplify'", '(/simplify)', '[/simplify]',
    '|/simplify', ',/simplify', 'x /simplify.', 'x /simplify',
  ];
  for (const text of counted) {
    const r = scanText(text, ['simplify']);
    assert.equal(r.violations.length, 1, `counted: ${JSON.stringify(text)}`);
    assert.equal(r.violations[0].form, 'slash');
  }
  const notCounted = [
    'a/simplify', '1/simplify', '//simplify', ':/simplify', '=/simplify', '-/simplify', './simplify', 'x /simplify-x',
    'x /simplify/y', 'x /simplifyx', 'x /simplify1', 'x /simplify_', '/Simplify', 'x /SIMPLIFY',
  ];
  for (const text of notCounted) {
    assert.equal(scanText(text, ['simplify']).violations.length, 0, `not counted: ${JSON.stringify(text)}`);
  }
});

test('S8: skill form hits and non-hits', () => {
  const names = ['code-review', 'security-review', 'simplify', 'review', 'loop'];
  const hits = [
    ['skill: "code-review"', 'code-review'],
    ['`code-review` skill', 'code-review'],
    ["SKILLS = ['security-review']", 'security-review'],
    ['the skill code-review', 'code-review'],
    ['Skills: [simplify', 'simplify'],
  ];
  for (const [text, name] of hits) {
    const r = scanText(text, names);
    assert.equal(r.violations.length, 1, `hit: ${text}`);
    assert.equal(r.violations[0].form, 'skill', text);
    assert.equal(r.violations[0].name, name, text);
  }
  for (const text of ['review skill', 'needs-security-review skill', 'loop skill', 'the loop skill']) {
    assert.equal(scanText(text, names).violations.length, 0, `not a hit: ${text}`);
  }
  const both = scanText('`/simplify` skill', names);
  assert.equal(both.violations.length, 1);
  assert.equal(both.violations[0].form, 'slash');
});

// S8 amendment A2 (review finding O1): the skill form reports every host name of a single-line list,
// not only the first element. Oracle: the review finding's three stated cases.
test('S8 (A2): skill form reports every host name of a single-line list', () => {
  const names = ['code-review', 'security-review'];

  // A non-host first element must not hide a host name after it.
  const second = scanText("SKILLS = ['spec-quality','security-review']", names);
  assert.deepEqual(second.violations.map((v) => [v.name, v.form, v.line]), [['security-review', 'skill', 1]]);

  // Host name first, non-host after it: still exactly the one host name.
  const first = scanText("SKILLS = ['security-review','spec-quality']", names);
  assert.deepEqual(first.violations.map((v) => v.name), ['security-review']);

  // Two host names in one unquoted list: one violation each.
  const both = scanText('skills: security-review, code-review', names);
  assert.deepEqual(both.violations.map((v) => v.name).sort(), ['code-review', 'security-review']);
  assert.ok(both.violations.every((v) => v.form === 'skill' && v.line === 1));

  // Double-quoted list with a non-host element between two host names.
  const mixed = scanText('skills: ["code-review", "spec-quality", "security-review"]', names);
  assert.deepEqual(mixed.violations.map((v) => v.name).sort(), ['code-review', 'security-review']);

  // A name that only contains a host name is no hit, wherever it sits in the list.
  assert.equal(scanText("SKILLS = ['security-review-x']", names).violations.length, 0);
  assert.equal(scanText("SKILLS = ['spec-quality','security-review-x']", names).violations.length, 0);
  const partial = scanText("SKILLS = ['security-review-x','code-review']", names);
  assert.deepEqual(partial.violations.map((v) => v.name), ['code-review']);
});

// ---- S10, S11 ------------------------------------------------------------------------------

test('S10: project scan flags an undeclared host skill and passes a declared one', () => {
  const declared = declaredSkills('x:\n  skill: "security-review"\n');
  assert.ok(declared.includes('security-review'));
  const names = HOST_SKILLS.filter((n) => !declared.includes(n));
  assert.ok(!names.includes('security-review'));
  assert.ok(names.includes('simplify'));
  const scopes = [{ dir: '.claude/skills', recursive: true }];
  const ok = scanDomainBoundary({ root: fixtureRoot({ '.claude/skills/x/SKILL.md': 'run /security-review\n' }), scopes, names });
  assert.deepEqual(ok.violations, []);
  const bad = scanDomainBoundary({ root: fixtureRoot({ '.claude/skills/x/SKILL.md': 'run /simplify\n' }), scopes, names });
  assert.deepEqual(bad.violations, [{ file: '.claude/skills/x/SKILL.md', line: 1, name: 'simplify', form: 'slash' }]);
});

test('S11: declaredSkills value forms, CRLF, duplicates, sorting and first-token rule', () => {
  const lines = [
    'a:',
    '  skill: "double-q"',
    "  skill: 'single-q'",
    '  skill: bare',
    '  skill: commented # a comment',
    '  skill: double-q',
    'skill: ns:name.v1',
    '  - skill: dashed',
    '  guidance: "read this. skill: ignored"',
  ];
  const expected = ['bare', 'commented', 'double-q', 'ns:name.v1', 'single-q'];
  assert.deepEqual(declaredSkills(lines.join('\n')), expected);
  assert.deepEqual(declaredSkills(lines.join(CR + '\n')), expected);
  assert.deepEqual(declaredSkills(''), []);
});

// ---- S12 -----------------------------------------------------------------------------------

test('S12: pluginShippedNames of the real plugin', () => {
  const names = pluginShippedNames(PLUGIN);
  for (const n of ['init', 'run-wave', 'test-author', 'implement-wave', 'closure-notes']) {
    assert.ok(names.includes(n), `includes ${n}`);
  }
  for (const n of ['_generated', '_fragments', 'manifest']) {
    assert.ok(!names.includes(n), `excludes ${n}`);
  }
  assert.deepEqual(names, [...new Set(names)].sort());
});

// ---- S13 -----------------------------------------------------------------------------------

test('S13: scope handling, skipped segments, extensions and CRLF line numbers', () => {
  const files = { 'hooks/top.mjs': 'x /simplify\n', 'hooks/lib/x.mjs': 'x /simplify\n' };
  const root = fixtureRoot(files);
  const flat = scanDomainBoundary({ root, scopes: [{ dir: 'hooks', recursive: false }], names: ['simplify'] });
  assert.deepEqual(flat.violations.map((v) => v.file), ['hooks/top.mjs']);
  const deep = scanDomainBoundary({ root, scopes: [{ dir: 'hooks', recursive: true }], names: ['simplify'] });
  assert.deepEqual(deep.violations.map((v) => v.file).sort(), ['hooks/lib/x.mjs', 'hooks/top.mjs']);

  const skipped = fixtureRoot({
    'skills/tests/a.md': 'x /simplify\n',
    'skills/node_modules/b.md': 'x /simplify\n',
    'skills/x/tests/c.md': 'x /simplify\n',
  });
  assert.deepEqual(scanDomainBoundary({ root: skipped, scopes: SKILL_SCOPE, names: ['simplify'] }).violations, []);

  const exts = {};
  for (const e of ['.mjs', '.js', '.json', '.md', '.yaml', '.yml', '.txt', '.sh']) exts[`skills/ext/f${e}`] = 'x /simplify\n';
  for (const e of ['.png', '.html', '.py']) exts[`skills/ext/g${e}`] = 'x /simplify\n';
  const r = scanDomainBoundary({ root: fixtureRoot(exts), scopes: SKILL_SCOPE, names: ['simplify'] });
  assert.deepEqual(
    r.violations.map((v) => v.file).sort(),
    ['.js', '.json', '.md', '.mjs', '.sh', '.txt', '.yaml', '.yml'].map((e) => `skills/ext/f${e}`),
  );

  const crlf = scanText(['a', 'b', 'run /simplify', 'c'].join(CR + '\n'), ['simplify']);
  assert.deepEqual(crlf.violations.map((v) => v.line), [3]);
  assert.ok(crlf.violations.every((v) => !v.file.includes(BACKSLASH)));
});

// ---- S14 -----------------------------------------------------------------------------------

test('S14: HOST_SKILLS is the frozen, sorted host skill list', () => {
  assert.ok(Object.isFrozen(HOST_SKILLS));
  assert.deepEqual([...HOST_SKILLS], [...HOST_SKILLS].sort());
  assert.deepEqual([...HOST_SKILLS], [
    'agents', 'claude-api', 'clear', 'code-review', 'compact', 'init', 'loop', 'review', 'schedule',
    'security-review', 'simplify', 'skill-creator', 'workflows',
  ]);
  for (const n of ['simplify', 'code-review', 'security-review', 'loop']) assert.ok(HOST_SKILLS.includes(n));
  assert.ok(!HOST_SKILLS.includes('mcp'));
});

// ---- S15 -----------------------------------------------------------------------------------

test('S15: root CLAUDE.md names the guard test as an existing file and has no later-task wording', () => {
  const text = readFileSync(join(REPO, 'CLAUDE.md'), 'utf8').split(CR).join('');
  assert.ok(!text.includes('later task'));
  const start = text.indexOf('## Rules a guard test enforces');
  assert.ok(start >= 0, 'section present');
  const rest = text.slice(start + 3);
  const next = rest.indexOf('\n## ');
  const section = next < 0 ? rest : rest.slice(0, next);
  assert.ok(section.includes('hooks/tests/contract-domain-boundary.test.mjs'));
});

// ---- S16, S17 ------------------------------------------------------------------------------

test('S16: the only plugin skill sharing a name with a host skill, project skill or project command is init', () => {
  const foreign = new Set(foreignNames());
  assert.deepEqual(pluginShippedSkillNames(PLUGIN).filter((n) => foreign.has(n)), ['init']);
});

test('S17: a plugin engineering skill and its references are scanned like any other plugin file', () => {
  const ENGINEERING = ['planning-standards', 'implementation-standards', 'review-method', 'test-authoring'];
  const text = ['/simplify', '`spec-quality` skill', '/deploy_to_docker', 'this repo', ''].join('\n');
  const files = {};
  for (const n of ENGINEERING) {
    files[`skills/${n}/SKILL.md`] = text;
    files[`skills/${n}/references/r.md`] = text;
  }
  const r = pluginScan([], [], fixtureRoot(files));
  assert.equal(r.violations.length, 32);
  assert.deepEqual(r.stale, []);
  for (const n of ENGINEERING) {
    for (const f of [`skills/${n}/SKILL.md`, `skills/${n}/references/r.md`]) {
      assert.deepEqual(
        r.violations.filter((v) => v.file === f).map((v) => [v.name, v.form]).sort(),
        [['deploy_to_docker', 'slash'], ['simplify', 'slash'], ['spec-quality', 'skill'], ['this repo', 'phrase']],
        f,
      );
    }
  }
});

// ---- Probes --------------------------------------------------------------------------------

test('probe: omitted phrases, allow and baseline equal empty arrays; two scans are equal', () => {
  const root = fixtureRoot({ 'skills/x/SKILL.md': 'run /simplify\nthis repo\n' });
  const a = scanDomainBoundary({ root, scopes: SKILL_SCOPE, names: ['simplify'] });
  const b = scanDomainBoundary({ root, scopes: SKILL_SCOPE, names: ['simplify'], phrases: [], allow: [], baseline: [] });
  assert.deepEqual(a, b);
  assert.deepEqual(a, scanDomainBoundary({ root, scopes: SKILL_SCOPE, names: ['simplify'] }));
  assert.equal(a.violations.length, 1);
});
