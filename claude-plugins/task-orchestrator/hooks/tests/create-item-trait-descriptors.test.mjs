// Tests for the create-item skill and trait descriptors (item 3b7a9ed5 / U2, S10-S11 / AC3). Oracles: the carry-in
// xref audit obligation (the plugin skill names no process trait of the project config) and the create-item Step 4
// obligation (trait selection reads appliesWhen and the trait-selection rule). Files are read at test runtime;
// assertion messages name the missing item, never echo file content.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const rd = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf-8');
const SKILL = rd('../../skills/create-item/SKILL.md');
const CONFIG = rd('../../../../.taskorchestrator/config.yaml');

// Names declared under the top-level traits: map (two-space indented keys until the next top-level key).
function traitNames(yaml) {
  const lines = yaml.split(/\r?\n/);
  const start = lines.findIndex((l) => /^traits:\s*$/.test(l));
  assert.ok(start >= 0, 'config.yaml declares a traits: map');
  const names = [];
  for (let i = start + 1; i < lines.length; i++) {
    if (/^\S/.test(lines[i])) break;
    const m = /^ {2}([A-Za-z0-9_.-]+):\s*(#.*)?$/.exec(lines[i]);
    if (m) names.push(m[1]);
  }
  return names;
}

test('S10 create-item names no trait declared in the project config', () => {
  const names = traitNames(CONFIG);
  assert.ok(names.length > 0, 'the project config declares at least one trait');
  const tokens = new Set(SKILL.split(/[^A-Za-z0-9_-]+/).filter(Boolean));
  for (const name of names) {
    assert.ok(!tokens.has(name), 'create-item SKILL.md names project trait: ' + name);
  }
});

function step4(text) {
  const lines = text.split(/\r?\n/);
  const s = lines.findIndex((l) => /^## Step 4\b/.test(l));
  assert.ok(s >= 0, 'create-item has a Step 4 heading');
  let e = lines.findIndex((l, i) => i > s && /^## /.test(l));
  if (e < 0) e = lines.length;
  return lines.slice(s, e).join('\n');
}

test('S11 create-item Step 4 names appliesWhen and the trait-selection rule', () => {
  const body = step4(SKILL);
  assert.ok(body.includes('appliesWhen'), 'Step 4 names appliesWhen');
  assert.ok(body.includes('trait-selection'), 'Step 4 names rule trait-selection');
});
