// Doc-needle and library checks for item b02b8bdf (seats declare rules, G8). Scenarios S11 and S12 match the item's frozen
// `test-plan` note. Oracles: task-scope AC3 (the findings-register and planning-sweeps text moves from the agent files into the
// rule it belongs to), AC4 (the agent files name the test-author by seat, not by skill or trait) and the declared contract of
// pluginShippedSkillNames(pluginRoot) (sorted directory names under skills/, a leading underscore excluded, [] when skills/ is
// unreadable). Node 22, node:test, no dependencies.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  HOST_SKILLS,
  pluginShippedNames,
  pluginShippedSkillNames,
  scanDomainBoundary,
} from '../../scripts/lib/contract-checks.mjs';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/\\]$/, '');
const REPO = join(PLUGIN, '..', '..');
const CR = String.fromCharCode(13);
// Line endings are normalised so a CRLF checkout cannot hide or fake a needle.
const read = (...p) => readFileSync(join(...p), 'utf8').split(CR + '\n').join('\n');
const agent = (name) => read(PLUGIN, 'agents', name + '.md');

const tmpDirs = [];
after(() => {
  for (const d of tmpDirs) rmSync(d, { recursive: true, force: true });
});
function fixtureRoot(files) {
  const root = mkdtempSync(join(tmpdir(), 'seat-rules-docs-'));
  tmpDirs.push(root);
  for (const [rel, text] of Object.entries(files)) {
    const abs = join(root, ...rel.split('/'));
    mkdirSync(dirname(abs), { recursive: true });
    writeFileSync(abs, text);
  }
  return root;
}

// ---- S11 -----------------------------------------------------------------------------------

test('S11 AC3: planner.md and reviewer.md hold no rule-owned text', () => {
  for (const name of ['planner', 'reviewer']) {
    const text = agent(name);
    for (const needle of ['review-cited-claims', 'planning-sweeps', 'One findings register']) {
      assert.equal(text.includes(needle), false, name + '.md must not contain ' + needle);
    }
  }
});

function claudeSection(text) {
  const at = text.indexOf('## claude:');
  assert.ok(at !== -1, 'the rule has a ## claude: section');
  const rest = text.slice(at + '## claude:'.length);
  const next = rest.search(/^## /m);
  return next === -1 ? rest : rest.slice(0, next);
}

test('S11 AC3: the review-cited-claims rule names the findings register and the first-review-note fallback in its claude section', () => {
  const bundled = read(PLUGIN, 'bundled-rules', 'review-cited-claims.md');
  const section = claudeSection(bundled);
  assert.match(section, /findings register/i);
  assert.match(section, /first[ -]review[ -]note/i);
  // The project's served copy is the same file, so the move reaches the dogfood config too.
  const project = read(REPO, '.taskorchestrator', 'rules', 'review-cited-claims.md');
  assert.equal(claudeSection(project), section);
});

test('S11 AC4: implementer.md names the test author by seat, not by skill or trait', () => {
  const text = agent('implementer');
  assert.ok(text.includes('`seat` is `test-author`'));
  assert.equal(text.includes('`skill` is `test-author`'), false);
  assert.equal(text.includes('needs-test-author'), false);
});

test('S11 AC4: reviewer.md says "when one exists" for the register fallback', () => {
  assert.ok(agent('reviewer').includes('when one exists'));
});

// ---- S12 -----------------------------------------------------------------------------------

test('S12: pluginShippedSkillNames of the real plugin is the skills/ directories only', () => {
  const names = pluginShippedSkillNames(PLUGIN);
  for (const n of ['run-wave', 'init']) assert.ok(names.includes(n), 'includes ' + n);
  for (const n of ['test-author', 'implement-wave', 'closure-notes', '_generated']) {
    assert.ok(!names.includes(n), 'excludes ' + n);
  }
  assert.deepEqual(names, [...new Set(names)].sort());
  // It is a strict subset of the broader shipped-name set, which also counts agents and workflows.
  const broad = pluginShippedNames(PLUGIN);
  for (const n of names) assert.ok(broad.includes(n), n + ' is also a shipped name');
  assert.ok(broad.length > names.length);
});

test('S12 probe: files, underscore directories and an unreadable skills/ directory', () => {
  const root = fixtureRoot({
    'skills/beta/SKILL.md': 'b',
    'skills/alpha/SKILL.md': 'a',
    'skills/_hidden/SKILL.md': 'h',
    'skills/loose-file.md': 'f',
    'agents/agent-only.md': 'x',
  });
  assert.deepEqual(pluginShippedSkillNames(root), ['alpha', 'beta']);
  assert.deepEqual(pluginShippedSkillNames(join(root, 'does-not-exist')), []);
});

function projectSkillDirs() {
  const dir = join(REPO, '.claude', 'skills');
  return readdirSync(dir).filter((n) => statSync(join(dir, n)).isDirectory());
}

function namesWith(shippedNames) {
  const shipped = new Set(shippedNames);
  return [...new Set([...HOST_SKILLS, ...projectSkillDirs()])].filter((n) => !shipped.has(n)).sort();
}

test('S12: a skill: test-author line is a violation under the skill-only name set and not under the broad one', () => {
  const root = fixtureRoot({ 'skills/x/SKILL.md': 'skill: test-author\n' });
  const scope = [{ dir: 'skills', recursive: true }];
  const skillOnly = namesWith(pluginShippedSkillNames(PLUGIN));
  assert.ok(skillOnly.includes('test-author'), 'the project skill test-author is a foreign name to the plugin');
  const hit = scanDomainBoundary({ root, scopes: scope, names: skillOnly });
  assert.deepEqual(hit.violations.map((v) => v.name), ['test-author']);
  const broad = namesWith(pluginShippedNames(PLUGIN));
  assert.equal(broad.includes('test-author'), false);
  assert.deepEqual(scanDomainBoundary({ root, scopes: scope, names: broad }).violations, []);
});

test('S12: the real-tree plugin scan is clean with the test-author allowlist row, and the row is what keeps it clean', () => {
  const allow = JSON.parse(read(PLUGIN, 'scripts', 'lib', 'domain-boundary-allowlist.json')).plugin;
  const baseline = JSON.parse(read(PLUGIN, 'scripts', 'lib', 'domain-boundary-baseline.json')).pendingConfig;
  const row = allow.find((r) => r.file === 'skills/manage-schemas/references/example-schemas.md' && r.name === 'test-author');
  assert.ok(row, 'the allowlist carries the example-schemas.md test-author row');
  const scan = (rows) =>
    scanDomainBoundary({
      root: PLUGIN,
      scopes: [
        { dir: 'skills', recursive: true },
        { dir: '_fragments', recursive: true },
        { dir: 'agents', recursive: true },
        { dir: 'workflows', recursive: true },
        { dir: 'hooks', recursive: false },
        { dir: 'scripts', recursive: false },
      ],
      names: namesWith(pluginShippedSkillNames(PLUGIN)),
      phrases: ['this repo'],
      allow: rows,
      baseline,
    });
  const clean = scan(allow);
  assert.deepEqual(clean.violations, []);
  assert.deepEqual(clean.stale, []);
  // Fixture condition: without the row the example-schemas.md mention is a violation, so the row is not stale.
  const without = scan(allow.filter((r) => r !== row));
  assert.ok(
    without.violations.some((v) => v.file === row.file && v.name === 'test-author'),
    'removing the row exposes the mention: ' + JSON.stringify(without.violations),
  );
});
