import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { loadPolicy, forClass, escalate, ceilingReached, classNames, DEFAULT_POLICY_PATH } from '../lib/dispatch-policy.mjs';

const base = () => JSON.parse(readFileSync(DEFAULT_POLICY_PATH, 'utf8'));
function tmpPolicy(obj) {
  const p = join(mkdtempSync(join(tmpdir(), 'dp-')), 'policy.json');
  writeFileSync(p, JSON.stringify(obj));
  return p;
}

test('default policy loads with the eleven classes', () => {
  assert.equal(classNames().length, 11);
  assert.ok(loadPolicy().checked);
});

test('forClass returns model and effort', () => {
  assert.deepEqual(forClass('implementation'), { model: 'sonnet', effort: 'medium' });
  assert.deepEqual(forClass('review'), { model: 'opus', effort: 'high' });
});

test('forClass throws on an unknown class naming the known ones', () => {
  assert.throws(() => forClass('nope'), /unknown dispatch class "nope".*implementation/);
  assert.throws(() => forClass('toString'), /unknown dispatch class/);
});

test('escalate: cycle 0 or absent returns effort', () => {
  assert.equal(escalate('medium', 0), 'medium');
  assert.equal(escalate('medium'), 'medium');
});

test('escalate: one level per cycle, capped at the ceiling', () => {
  assert.equal(escalate('medium', 1), 'high');
  assert.equal(escalate('medium', 2), 'xhigh');
  assert.equal(escalate('medium', 5), 'xhigh');
  assert.equal(escalate('low', 1), 'medium');
});

test('escalate: never reaches max and never lowers an effort at or above the ceiling', () => {
  assert.equal(escalate('xhigh', 3), 'xhigh');
  assert.equal(escalate('max', 2), 'max');
});

test('escalate throws on an unknown effort', () => {
  assert.throws(() => escalate('turbo', 1), /unknown effort "turbo"/);
});

test('ceilingReached', () => {
  assert.equal(ceilingReached('medium', 1), false);
  assert.equal(ceilingReached('medium', 2), true);
  assert.equal(ceilingReached('high', 1), true);
  assert.equal(ceilingReached('medium', 0), false);
});

test('policy passed by path is honoured (ceiling lowered to high)', () => {
  const p = base();
  p.escalation.ceiling = 'high';
  const path = tmpPolicy(p);
  assert.equal(escalate('medium', 5, path), 'high');
  assert.equal(escalate('medium', 5), 'xhigh');
});

test('malformed policies are rejected on load', () => {
  let p = base(); p.escalation.ceiling = 'max';
  assert.throws(() => loadPolicy(tmpPolicy(p)), /below the top level/);
  p = base(); p.escalation.ceiling = 'huge';
  assert.throws(() => loadPolicy(tmpPolicy(p)), /not one of the levels/);
  p = base(); p.classes.review.effort = 'turbo';
  assert.throws(() => loadPolicy(tmpPolicy(p)), /class review effort/);
  p = base(); delete p.classes.review.model;
  assert.throws(() => loadPolicy(tmpPolicy(p)), /class review has no model/);
  p = base(); p.classes = {};
  assert.throws(() => loadPolicy(tmpPolicy(p)), /classes missing/);
  assert.throws(() => loadPolicy(tmpPolicy('not an object')), /not an object/);
});
