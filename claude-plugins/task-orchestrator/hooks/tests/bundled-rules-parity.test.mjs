// S14/S15 (19bbe0b7 test-plan): the plugin's bundled rules mirror this repo's rules and carry a
// hash manifest whose last entry is the current hash. Oracle: task-scope TS2 and TS5.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { normalizeForFingerprint } from '../config-sync.mjs';

const KEYS = [
  'protocol.entry-seat',
  'protocol.in-phase-seat',
  'protocol.read-only-agent',
  'commit-discipline',
  'review-scoping',
];
const BUNDLED_DIR = fileURLToPath(new URL('../../bundled-rules/', import.meta.url));
const REPO_ROOT = fileURLToPath(new URL('../../../../', import.meta.url));
const REPO_RULES_DIR = join(REPO_ROOT, '.taskorchestrator', 'rules');

const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');
const manifest = JSON.parse(readFileSync(join(BUNDLED_DIR, 'manifest.json'), 'utf-8'));

test('bundled-rules holds exactly the five rule files and the manifest names exactly those keys', () => {
  const mdKeys = readdirSync(BUNDLED_DIR)
    .filter((f) => f.endsWith('.md'))
    .map((f) => f.slice(0, -3))
    .sort();
  assert.deepEqual(mdKeys, [...KEYS].sort());
  assert.ok(manifest !== null && typeof manifest === 'object' && !Array.isArray(manifest));
  assert.deepEqual(Object.keys(manifest).sort(), [...KEYS].sort());
});

for (const key of KEYS) {
  test(`bundled ${key}: body equals the repo rule after normalization`, () => {
    const bundled = normalizeForFingerprint(readFileSync(join(BUNDLED_DIR, `${key}.md`)));
    const repo = normalizeForFingerprint(readFileSync(join(REPO_RULES_DIR, `${key}.md`)));
    assert.deepEqual(bundled, repo);
  });

  test(`manifest ${key}: non-empty, duplicate-free lowercase sha256 hexes, current hash last`, () => {
    const hashes = manifest[key];
    assert.ok(Array.isArray(hashes) && hashes.length > 0);
    for (const h of hashes) assert.match(h, /^[0-9a-f]{64}$/);
    assert.equal(new Set(hashes).size, hashes.length);
    const current = sha256(normalizeForFingerprint(readFileSync(join(BUNDLED_DIR, `${key}.md`))));
    assert.equal(hashes[hashes.length - 1], current);
  });
}

test('.gitattributes pins bundled-rules/*.md to LF', () => {
  const out = execFileSync(
    'git',
    ['check-attr', 'eol', '--', 'claude-plugins/task-orchestrator/bundled-rules/commit-discipline.md'],
    { cwd: REPO_ROOT, encoding: 'utf-8' },
  );
  assert.match(out.trim(), /eol: lf$/);
});
