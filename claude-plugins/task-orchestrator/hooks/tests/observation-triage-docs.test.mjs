// Doc-needle checks for item 216a2239 (observation triage at child close). Oracle: the item's
// specification AC1 - post-run Step 5 carries the three child-close dispositions and the triage rule
// that bounds `fixed`. Plugin-relative paths only, so the test passes from the plugin cache too.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/]$/, '');
const POST_RUN = readFileSync(join(PLUGIN, 'skills', 'run-wave', 'references', 'post-run.md'), 'utf8');

function postRunSection(id) {
  const re = new RegExp('^## Step ' + id + '(?![0-9a-z])', 'm');
  const m = re.exec(POST_RUN);
  assert.ok(m, 'post-run.md has a Step ' + id + ' heading');
  const rest = POST_RUN.slice(m.index + 3);
  const next = rest.search(/^## /m);
  return next === -1 ? rest : rest.slice(0, next);
}

test('AC1: child-close dispositions offer fixed, follow-up and accepted', () => {
  const s = postRunSection('5');
  const i = s.indexOf('**Dispositions at child close.**');
  assert.ok(i >= 0, 'the Dispositions at child close paragraph exists');
  const para = s.slice(i, s.indexOf('\n\n', i));
  assert.ok(para.includes('`fixed <sha>`'));
  assert.ok(para.includes('`follow-up <item-id>`'));
  assert.ok(para.includes('`accepted: <reason>`'));
});

test('AC1: the Observation triage paragraph bounds fixed and keeps the Fail recipe for blocking findings', () => {
  const s = postRunSection('5');
  const i = s.indexOf('**Observation triage.**');
  assert.ok(i >= 0, 'the Observation triage paragraph exists');
  const para = s.slice(i, s.indexOf('\n\n', i));
  assert.ok(para.includes('exception, not the default'));
  assert.ok(para.includes('implementer-a<n>'));
  assert.ok(para.includes('no planner amendment'));
  assert.ok(para.includes('blocking\nfindings only') || para.includes('blocking findings only'));
});

test('AC4: no stale two-option disposition sentence remains in post-run', () => {
  assert.equal(POST_RUN.includes('follow-up\nitem created first, or'), false);
});
