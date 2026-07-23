import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readSection, scalar, inlineScalar } from '../yaml-lite.mjs';

test('readSection: column-0 comment inside a block stays inside the section', () => {
  const content = [
    'retrospective:',
    '# a stray column-0 comment describing mode',
    '  mode: dispatch',
    'work_item_schemas:',
    '  foo: bar',
  ].join('\n');
  const section = readSection(content, 'retrospective');
  assert.ok(section);
  assert.equal(scalar(section.lines, 'mode'), 'dispatch');
});

test('readSection: blank lines stay inside the section', () => {
  const content = [
    'retrospective:',
    '  mode: dispatch',
    '',
    '  dispatchThreshold: 5',
    'project:',
    '  rootId: xyz',
  ].join('\n');
  const section = readSection(content, 'retrospective');
  assert.ok(section);
  assert.equal(scalar(section.lines, 'dispatchThreshold'), '5');
});

test('readSection: inline {} form is captured separately from block lines', () => {
  const content = 'retrospective: { mode: dispatch, dispatchThreshold: 5 }\n';
  const section = readSection(content, 'retrospective');
  assert.ok(section);
  assert.equal(section.lines.length, 0);
  assert.equal(inlineScalar(section.inline, 'mode'), 'dispatch');
  assert.equal(inlineScalar(section.inline, 'dispatchThreshold'), '5');
});

test('readSection: inline {} form with a trailing comment still matches (fix M2)', () => {
  const content = 'retrospective: { mode: dispatch }  # comment\n';
  const section = readSection(content, 'retrospective');
  assert.ok(section);
  assert.equal(inlineScalar(section.inline, 'mode'), 'dispatch');
});

test('readSection: inline {} form with trailing content, non-retrospective key (fix M2)', () => {
  const content = 'actor_authentication: { enabled: true } # trailing\n';
  const section = readSection(content, 'actor_authentication');
  assert.ok(section);
  assert.equal(inlineScalar(section.inline, 'enabled'), 'true');
});

test('readSection: adjacent top-level key ends the section', () => {
  const content = [
    'project:',
    '  rootId: abc-123',
    'retrospective:',
    '  mode: off',
  ].join('\n');
  const section = readSection(content, 'project', { blockOnly: true });
  assert.ok(section);
  assert.equal(scalar(section.lines, 'rootId'), 'abc-123');
  // retrospective's mode must not have leaked into the project block.
  assert.equal(scalar(section.lines, 'mode'), null);
});

test('readSection: absent block returns null', () => {
  const content = 'project:\n  rootId: abc\n';
  assert.equal(readSection(content, 'retrospective'), null);
  assert.equal(readSection('', 'project'), null);
  assert.equal(readSection(null, 'project'), null);
});

test('readSection: blockOnly ignores an inline form for that key', () => {
  const content = 'project: { rootId: abc }\n';
  assert.equal(readSection(content, 'project', { blockOnly: true }), null);
});

test('readSection: non-blockOnly still matches the block form', () => {
  const content = 'actor_authentication:\n  enabled: true\n';
  const section = readSection(content, 'actor_authentication');
  assert.ok(section);
  assert.equal(section.inline, null);
  assert.equal(scalar(section.lines, 'enabled'), 'true');
});

test('scalar: strips quotes and trailing comments', () => {
  const lines = ['  rootId: "abc-123"  # a uuid', '  name: bare-name'];
  assert.equal(scalar(lines, 'rootId'), 'abc-123');
  assert.equal(scalar(lines, 'name'), 'bare-name');
});

test('scalar: skips comment-only lines as candidates', () => {
  const lines = ['  # rootId: should-not-match', '  rootId: real-value'];
  assert.equal(scalar(lines, 'rootId'), 'real-value');
});

test('scalar: returns null when key is absent', () => {
  assert.equal(scalar(['  other: value'], 'rootId'), null);
});

test('inlineScalar: bounded by comma or closing brace', () => {
  const inline = ' mode: dispatch, dispatchThreshold: 5 ';
  assert.equal(inlineScalar(inline, 'mode'), 'dispatch');
  assert.equal(inlineScalar(inline, 'dispatchThreshold'), '5');
});

test('inlineScalar: returns null for an absent key or a null inline', () => {
  assert.equal(inlineScalar(' mode: dispatch ', 'missing'), null);
  assert.equal(inlineScalar(null, 'mode'), null);
});
