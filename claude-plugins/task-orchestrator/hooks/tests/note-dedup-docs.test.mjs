// Doc-needle and to-call checks for item cd48f32c (note-dedup plugin docs). Scenarios S9 and S10
// match the item's frozen `test-plan` note. Oracles: task-scope AC1-AC7 and AC-SURF and
// plans/note-dedup-templates-2026-11-02.md sections 1e, 2b, 2c, 3a, 3b, 4b - never the
// text of the docs as they stand. Node 22, node:test, no dependencies.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { checkToCall, parseToCallBlocks } from '../../scripts/lib/contract-checks.mjs';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/]$/, '');
const CATALOG = JSON.parse(readFileSync(join(PLUGIN, 'hooks', 'lib', 'tool-catalog.json'), 'utf8'));
const read = (...p) => readFileSync(join(PLUGIN, ...p), 'utf8');

const REVIEWER = read('agents', 'reviewer.md');
const POST_RUN = read('skills', 'run-wave', 'references', 'post-run.md');
const RUN_WAVE = read('skills', 'run-wave', 'SKILL.md');
const CONFIG_FORMAT = read('skills', 'manage-schemas', 'references', 'config-format.md');
const WORK_SUMMARY = read('skills', 'work-summary', 'SKILL.md');
const RETRO = read('skills', 'session-retrospective', 'SKILL.md');

// Text of the "## Step <id>" section of post-run.md, up to the next "## " heading.
function postRunSection(id) {
  const re = new RegExp('^## Step ' + id + '(?![0-9a-z])', 'm');
  const m = re.exec(POST_RUN);
  assert.ok(m, 'post-run.md has a Step ' + id + ' heading');
  const rest = POST_RUN.slice(m.index + 3);
  const next = rest.search(/^## /m);
  return next === -1 ? rest : rest.slice(0, next);
}

test('S9 AC1: reviewer.md carries the One findings register paragraph', () => {
  assert.ok(REVIEWER.includes('One findings register'));
});

test('S9 AC2: post-run Step 7b hands over verdict and count lines, with no review-checklist read call', () => {
  const s = postRunSection('7b');
  assert.equal(s.includes('key:"review-checklist")'), false, 'the old whole-note review-checklist read is gone');
  assert.ok(s.includes('never re-listed'));
});

test('S9 AC3: post-run Step 5 gains Dispositions at child close', () => {
  assert.ok(postRunSection('5').includes('Dispositions at child close'));
});

test('S9 AC4: the carry-in requirement shape is in post-run Step 7a and run-wave SKILL.md', () => {
  assert.ok(postRunSection('7a').includes('A carry-in note is a requirement'));
  assert.ok(RUN_WAVE.includes('A carry-in note is a requirement'));
});

test('S9 AC5: post-run fix-cycle routes rulings to the amendments note', () => {
  assert.ok(postRunSection('5').includes('amendments'));
});

test('S9 AC6: post-run.md no longer calls orchestrator-confirmation off-schema', () => {
  assert.equal(POST_RUN.includes('off-schema'), false);
});

test('S9 AC7: config-format.md names line-number tables as verbatim artifacts', () => {
  assert.ok(CONFIG_FORMAT.includes('line-number tables'));
});

function notesBlocks(text) {
  return parseToCallBlocks(text).filter((b) => !('error' in b) && b.value.tool === 'report' && b.value.select === 'notes');
}

test('S10 AC-SURF: work-summary has one director-profile report notes call grouped by role, catalog-valid', () => {
  const blocks = notesBlocks(WORK_SUMMARY);
  const byRole = blocks.filter((b) => b.value.args && b.value.args.groupBy === 'role');
  assert.equal(byRole.length, 1, 'exactly one groupBy role notes call');
  const call = byRole[0].value;
  assert.equal(call.profile, 'director');
  assert.ok(call.args.rootId, 'the call is scoped by rootId');
  assert.deepEqual(checkToCall(call, CATALOG), []);
  assert.ok(WORK_SUMMARY.includes('note volume (7d)'), 'the rendered line is named');
});

test('S10 AC-SURF: session-retrospective has director report notes calls for groupBy role and noteKey, with the baseline figures', () => {
  const blocks = notesBlocks(RETRO);
  for (const groupBy of ['role', 'noteKey']) {
    const hit = blocks.filter((b) => b.value.args && b.value.args.groupBy === groupBy);
    assert.ok(hit.length >= 1, 'a notes call grouped by ' + groupBy);
    for (const b of hit) {
      assert.equal(b.value.profile, 'director', groupBy);
      assert.deepEqual(checkToCall(b.value, CATALOG), [], groupBy);
    }
  }
  for (const figure of ['31%', '6.5 KB', '37 KB']) {
    assert.ok(RETRO.includes(figure), 'baseline figure ' + figure);
  }
});
