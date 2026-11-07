// Doc-needle checks for the closure-notes delta change (retrospective scoring, run-wave post-run
// wording, field guide plate 10). Node 22, node:test, no dependencies.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/]$/, '');
const read = (...p) => readFileSync(join(PLUGIN, ...p), 'utf8');

const RETRO = read('skills', 'session-retrospective', 'SKILL.md');
const POST_RUN = read('skills', 'run-wave', 'references', 'post-run.md');
const PLATE_10 = join(PLUGIN, '..', '..', 'docs', 'field-guide', 'src', 'plates', '10-improvement-loop.html');

test('retrospective SKILL drops the files list, token bands and context-loss clause', () => {
  for (const needle of ['combined deduplicated files list', '50 tokens', 'context loss']) {
    assert.ok(!RETRO.includes(needle), 'retrospective SKILL must not contain "' + needle + '"');
  }
});

test('retrospective SKILL carries the derivability sample and note-volume figures', () => {
  for (const needle of ['derivable', 'avgBytes', '1127']) {
    assert.ok(RETRO.includes(needle), 'retrospective SKILL must contain "' + needle + '"');
  }
});

test('run-wave post-run no longer asks for files touched', () => {
  assert.ok(!POST_RUN.includes('files touched'), 'post-run.md must not contain "files touched"');
});

test('field guide plate 10 no longer states the token-band sparse rule', (t) => {
  if (!existsSync(PLATE_10)) {
    t.skip('field guide sources are absent (plugin cache)');
    return;
  }
  const plate = readFileSync(PLATE_10, 'utf8');
  assert.ok(!plate.includes('50 tokens'), 'plate 10 must not contain "50 tokens"');
});
