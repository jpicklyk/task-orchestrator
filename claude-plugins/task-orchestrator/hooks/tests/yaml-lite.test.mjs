import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readSection, scalar, inlineScalar } from '../yaml-lite.mjs';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

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

test('readSection: indented nested header before the real column-0 block is skipped (F-012 T1)', () => {
  const content = [
    'work_item_schemas:',
    '  project:',
    '    rootId: nested',
    'project:',
    '  rootId: real',
  ].join('\n');
  const section = readSection(content, 'project', { blockOnly: true });
  assert.ok(section);
  assert.equal(scalar(section.lines, 'rootId'), 'real');
});

test('scalar: only matches at the first-level indent of the block (F-012 T2)', () => {
  const content = [
    'retrospective:',
    '  thresholds:',
    '    mode: off',
    '  mode: dispatch',
  ].join('\n');
  const section = readSection(content, 'retrospective');
  assert.ok(section);
  assert.equal(scalar(section.lines, 'mode'), 'dispatch');
  assert.equal(scalar(section.lines, 'thresholds'), null);
  assert.equal(scalar([], 'mode'), null);
  assert.equal(scalar(['  # only a comment'], 'mode'), null);
});

test('readSection: an indented-only header is not a section (F-012 T3)', () => {
  const content = 'other:\n  orchestration:\n    mode: x\n';
  assert.equal(readSection(content, 'orchestration'), null);
  assert.equal(readSection(content, 'orchestration', { blockOnly: true }), null);
});

test('readSection: CRLF input and a BOM-prefixed first line still match (F-012 T4)', () => {
  const crlf = 'project:\r\n  rootId: abc\r\nother:\r\n  x: 1\r\n';
  const s1 = readSection(crlf, 'project', { blockOnly: true });
  assert.ok(s1);
  assert.equal(scalar(s1.lines, 'rootId'), 'abc');
  const bom = '﻿project:\n  rootId: abc\n';
  const s2 = readSection(bom, 'project', { blockOnly: true });
  assert.ok(s2);
  assert.equal(scalar(s2.lines, 'rootId'), 'abc');
});

test('readSection: block header with a trailing comment is recognized (F-012 T5)', () => {
  const content = 'project:  # anchor\n  rootId: abc\n';
  const section = readSection(content, 'project', { blockOnly: true });
  assert.ok(section);
  assert.equal(scalar(section.lines, 'rootId'), 'abc');
});

test('inlineScalar: key boundary and brace depth (F-012 T6)', () => {
  assert.equal(inlineScalar(' not_enabled: x, enabled: true ', 'enabled'), 'true');
  assert.equal(inlineScalar(' nested: { mode: off }, mode: dispatch ', 'mode'), 'dispatch');
  assert.equal(inlineScalar(' nested: { mode: off } ', 'mode'), null);
  assert.equal(inlineScalar(' a: 1 ', 'missing'), null);
});

test('yaml-lite: hook and mod copies stay byte-identical (F-012 T7)', () => {
  const here = dirname(fileURLToPath(import.meta.url));
  const norm = (p) => readFileSync(p, 'utf8').replace(/\r\n/g, '\n');
  const hook = norm(join(here, '..', 'yaml-lite.mjs'));
  const mod = norm(join(here, '..', '..', '..', 'task-orchestrator-mod', 'src', 'lib', 'yaml-lite.mjs'));
  assert.equal(mod, hook);
});
