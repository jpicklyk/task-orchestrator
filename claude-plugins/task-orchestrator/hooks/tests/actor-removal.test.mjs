// Repository-state coverage for 0bbdc4cc (P10): the plugin attribution hook is deleted (AC1), the five
// write-path hooks carry no retired 3.x vocabulary and the contract baseline rows owned by this item
// are gone (AC9), and the docs say so (AC9). Test author actor id: test-author:0bbdc4cc:r-202610161834-0bbdc4cc.
//
// Oracle: 0bbdc4cc task-scope AC1 ("grep -rn actor P/hooks/*.mjs returns only prose about the removed
// field: no actor_attribution, actor_authentication, buildActorMap, actorForResult, isWorkflowSeatActor,
// invalid_actor, rejected_by_policy anywhere in P/hooks/*.mjs"), AC2 (retro-lib extractResponseJson is no
// longer imported by phase-guard-record / dispatch-hint) and AC9 (the six pendingPort rows owned by
// 0bbdc4cc are deleted and no retired name remains in those files; README paragraph; CHANGELOG; fleet doc).
// Scenario S15 (test-plan): EXISTING-SURFACE. File-system and source-text checks only; no hook is run here.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const HOOKS = fileURLToPath(new URL('..', import.meta.url));
const PLUGIN = join(HOOKS, '..');
const REPO = join(PLUGIN, '..', '..');
const read = (p) => readFileSync(p, 'utf-8');
const hookFiles = () => readdirSync(HOOKS).filter((f) => f.endsWith('.mjs'));

// the files this item ports (the attribution hook is deleted, so it is not among them)
const PORTED = ['phase-guard-record.mjs', 'phase-guard.mjs', 'dispatch-hint.mjs', 'skill-enforcement.mjs', 'subagent-start.mjs'];
const OWNED_BASELINE_FILES = ['hooks/enforce-actor-attribution.mjs', ...PORTED.map((f) => `hooks/${f}`)];

test('AC1: the attribution hook and its test file are deleted', () => {
  assert.equal(existsSync(join(HOOKS, 'enforce-actor-attribution.mjs')), false);
  assert.equal(existsSync(join(HOOKS, 'tests', 'enforce-actor-attribution.test.mjs')), false);
});

test('AC1: hooks-config.json has no entry for the attribution hook and every registered command script exists', () => {
  const raw = read(join(HOOKS, 'hooks-config.json'));
  assert.ok(!raw.includes('enforce-actor-attribution'), 'the PreToolUse entry must be gone');
  const cfg = JSON.parse(raw);
  const commands = [];
  for (const groups of Object.values(cfg.hooks)) {
    for (const g of groups) for (const h of g.hooks ?? []) commands.push(h.command);
  }
  assert.ok(commands.length > 0);
  for (const cmd of commands) {
    const m = String(cmd).match(/hooks\/([A-Za-z0-9_.-]+\.mjs)/);
    assert.ok(m, `command without a hooks/*.mjs script: ${cmd}`);
    assert.ok(existsSync(join(HOOKS, m[1])), `registered script is missing: ${m[1]}`);
  }
});

test('AC1: no hooks/*.mjs mentions the retired attribution / actor vocabulary', () => {
  const retired = ['actor_attribution', 'actor_authentication', 'buildActorMap', 'actorForResult', 'isWorkflowSeatActor', 'invalid_actor', 'rejected_by_policy'];
  const files = hookFiles();
  assert.ok(files.length >= 10, 'sanity: the hook directory listing is not empty');
  for (const f of files) {
    const text = read(join(HOOKS, f));
    for (const token of retired) assert.ok(!text.includes(token), `${f} still mentions ${token}`);
  }
});

test('AC1: no code line in hooks/*.mjs reads or sets an actor field (comments may still explain the removal)', () => {
  const fieldUse = /(?:\.|\?\.)actor\b|\bactor\s*:|\[['"]actor['"]\]/;
  for (const f of hookFiles()) {
    const lines = read(join(HOOKS, f)).split(/\r?\n/);
    lines.forEach((line, i) => {
      const t = line.trim();
      if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) return;
      assert.ok(!fieldUse.test(line), `${f}:${i + 1} uses an actor field: ${t}`);
    });
  }
});

test('AC1: no other hooks test file still names the removed hook', () => {
  const dir = join(HOOKS, 'tests');
  for (const f of readdirSync(dir).filter((n) => n.endsWith('.test.mjs') && n !== 'actor-removal.test.mjs')) {
    assert.ok(!read(join(dir, f)).includes('enforce-actor-attribution'), `${f} still names enforce-actor-attribution`);
  }
});

test('AC2: phase-guard-record and dispatch-hint no longer import retro-lib extractResponseJson', () => {
  for (const f of ['phase-guard-record.mjs', 'dispatch-hint.mjs']) {
    assert.ok(!read(join(HOOKS, f)).includes('extractResponseJson'), `${f} still uses extractResponseJson`);
  }
});

test('AC9: the contract baseline has no pendingPort row for a file or owner of this item, and no prose exception names them', () => {
  const baseline = JSON.parse(read(join(PLUGIN, 'scripts', 'lib', 'contract-names-baseline.json')));
  const rows = baseline.pendingPort ?? [];
  assert.ok(Array.isArray(rows), 'pendingPort stays an array');
  for (const row of rows) {
    assert.ok(!OWNED_BASELINE_FILES.includes(row.file), `pendingPort row for ${row.file} must be deleted`);
    assert.notEqual(row.owner, '0bbdc4cc', `pendingPort row owned by 0bbdc4cc remains: ${row.file}`);
  }
  const prose = JSON.stringify(baseline.proseExceptions ?? []);
  for (const f of OWNED_BASELINE_FILES) assert.ok(!prose.includes(f), `proseExceptions names ${f}`);
});

test('AC9: none of the five ported hooks names a retired 3.x tool', () => {
  const retiredTools = [
    'advance_item', 'manage_notes', 'manage_items', 'query_items', 'query_notes', 'get_context', 'claim_item',
    'manage_dependencies', 'query_dependencies', 'manage_project_config', 'manage_plan_documents', 'query_rules',
    'get_next_item', 'get_next_status', 'get_blocked_items', 'create_work_tree',
  ];
  for (const f of PORTED) {
    const text = read(join(HOOKS, f));
    for (const name of retiredTools) assert.ok(!new RegExp(`\\b${name}\\b`).test(text), `${f} still names ${name}`);
  }
});

test('AC9: hooks README has the "Hooks on the envelope" paragraph', () => {
  assert.ok(read(join(HOOKS, 'README.md')).includes('Hooks on the envelope'));
});

test('AC9: fleet-deployment.md no longer says the attribution hook is removed in a later pass', () => {
  const doc = read(join(REPO, 'current', 'docs', 'fleet-deployment.md'));
  assert.ok(!/removed in a later pass/i.test(doc));
});

test('AC9: CHANGELOG [4.0.0] records the attribution hook removal and no longer points operators at actor_attribution.required', () => {
  const changelog = read(join(REPO, 'CHANGELOG.md'));
  const start = changelog.indexOf('## [4.0.0]');
  assert.ok(start >= 0);
  const next = changelog.indexOf('\n## [', start + 1);
  const unreleased = changelog.slice(start, next < 0 ? undefined : next);
  assert.ok(/attribution hook/i.test(unreleased), 'expected a Plugin bullet about the removed attribution hook');
  for (const line of unreleased.split(/\r?\n/)) {
    assert.ok(!/(?:use|set|enable|configure)[^.]*actor_attribution\.required/i.test(line), `still instructs actor_attribution.required: ${line.slice(0, 120)}`);
  }
});
