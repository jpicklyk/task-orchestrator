// Tests for hooks/orchestration-lib.mjs (item 8cd79bc0, scenarios S1 and S2 of the frozen test-plan).
//
// Oracles (never "what the parser returns today"):
//   - task-scope AC1: plugin keys are read ONLY from extensions -> claude-code -> orchestration; a
//     top-level `orchestration:` block is ignored; default is `workflow`; the parser never throws.
//   - current/docs/config-reference.md 'extensions': nested YAML block structure, 4.0 strict push
//     rejects a top-level `orchestration:`.
//   - modes are workflow | schema | off (config-format), invalid values fall back to workflow.
//   - YAML nesting rules: a key is under its parent only when it is indented deeper than the parent,
//     and a sibling of the parent ends the parent's block.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  parseOrchestrationConfig,
  parseOrchestrationMode,
  readClaudeCodeSection,
  ORCHESTRATION_MODES,
  DEFAULT_ORCHESTRATION_MODE,
} from '../orchestration-lib.mjs';
import { scalar, inlineScalar } from '../yaml-lite.mjs';

// Wrap `inner` (a block of YAML lines at column 0) under extensions -> claude-code with the given
// per-level indent width.
function nest(inner, width = 2) {
  const pad = ' '.repeat(width);
  const body = inner
    .split('\n')
    .map((l) => (l === '' ? l : pad + pad + l))
    .join('\n');
  return 'extensions:\n' + pad + 'claude-code:\n' + body;
}
const modeOf = (text) => parseOrchestrationConfig(text).mode;
const nestedMode = (inner, width) => modeOf(nest(inner, width));

// ---- S1: mode resolution -------------------------------------------------------------------

test('S1: each valid mode under extensions.claude-code.orchestration is read, off stays off', () => {
  for (const m of ORCHESTRATION_MODES) {
    assert.equal(nestedMode(`orchestration:\n  mode: ${m}\n`), m);
  }
  assert.equal(nestedMode('orchestration:\n  mode: off\n'), 'off');
  assert.equal(nestedMode('orchestration:\n  mode: schema\n'), 'schema');
});

test('S1: inline `orchestration: { mode: ... }` forms under claude-code', () => {
  assert.equal(nestedMode('orchestration: { mode: off }\n'), 'off');
  assert.equal(nestedMode('orchestration: {mode: schema}\n'), 'schema');
  assert.equal(nestedMode('orchestration: { mode: schema } # why\n'), 'schema');
});

test('S1: absent input, wrong-type input and empty text fall back to workflow without throwing', () => {
  for (const v of [null, undefined, '', 42, {}, [], true, Buffer.from('extensions:\n  claude-code:\n    orchestration:\n      mode: off\n')]) {
    assert.equal(modeOf(v), 'workflow');
  }
});

test('S1: block absent, mode key absent, or empty block -> workflow', () => {
  assert.equal(nestedMode('retrospective:\n  mode: off\n'), 'workflow');
  assert.equal(nestedMode('orchestration:\n  other: x\n'), 'workflow');
  assert.equal(nestedMode('orchestration:\n'), 'workflow');
  assert.equal(modeOf('extensions:\n  claude-code:\n'), 'workflow');
  assert.equal(modeOf('extensions:\n'), 'workflow');
});

test('S1: invalid values fall back to workflow (empty vs absent vs wrong value are the same default)', () => {
  for (const v of ['turbo', 'true', 'workflows', 'offf', '""', "''", '~', 'null']) {
    assert.equal(nestedMode(`orchestration:\n  mode: ${v}\n`), 'workflow', v);
  }
  assert.equal(nestedMode('orchestration:\n  mode:\n'), 'workflow');
});

test('S1: case, whitespace, quotes and trailing comments are normalized', () => {
  for (const [inner, want] of [
    ['orchestration:\n  mode: Schema\n', 'schema'],
    ['orchestration:\n  mode: SCHEMA\n', 'schema'],
    ['orchestration:\n  mode: OFF\n', 'off'],
    ['orchestration:\n  mode:   schema  \n', 'schema'],
    ['orchestration:\n  mode: "schema"\n', 'schema'],
    ["orchestration:\n  mode: 'off'\n", 'off'],
    ['orchestration:\n  mode: off # why\n', 'off'],
  ]) {
    assert.equal(nestedMode(inner), want, inner);
  }
});

test('S1 probe: indent widths 2 and 4 both resolve', () => {
  assert.equal(nestedMode('orchestration:\n  mode: schema\n', 2), 'schema');
  assert.equal(nestedMode('orchestration:\n    mode: off\n', 4), 'off');
  assert.equal(nestedMode('orchestration: { mode: off }\n', 4), 'off');
});

test('S1 probe: CRLF and a leading BOM do not hide the nested mode', () => {
  assert.equal(modeOf('extensions:\r\n  claude-code:\r\n    orchestration:\r\n      mode: schema\r\n'), 'schema');
  assert.equal(modeOf('\uFEFFextensions:\n  claude-code:\n    orchestration:\n      mode: off\n'), 'off');
});

test('S1 probe: a column-0 comment and blank lines between the headers and the mode line are tolerated', () => {
  assert.equal(modeOf('extensions:\n# a comment\n  claude-code:\n\n    orchestration:\n# c\n\n      mode: schema\n'), 'schema');
});

test('S1: a top-level orchestration block alone is ignored (4.0 strict push rejects it) -> workflow', () => {
  assert.equal(modeOf('orchestration:\n  mode: off\n'), 'workflow');
  assert.equal(modeOf('orchestration:\n  mode: schema\n'), 'workflow');
  assert.equal(modeOf('orchestration: { mode: off }'), 'workflow');
  assert.equal(modeOf('orchestration: { mode: schema }'), 'workflow');
});

test('S1: when both placements are present the extensions value wins, in either order', () => {
  const ext = (m) => `extensions:\n  claude-code:\n    orchestration:\n      mode: ${m}\n`;
  const top = (m) => `orchestration:\n  mode: ${m}\n`;
  assert.equal(modeOf(top('off') + ext('schema')), 'schema');
  assert.equal(modeOf(ext('schema') + top('off')), 'schema');
  assert.equal(modeOf(top('schema') + ext('off')), 'off');
  assert.equal(modeOf(ext('off') + top('schema')), 'off');
  // The top-level value is not a fallback for an extensions block that lacks mode.
  assert.equal(modeOf(top('off') + 'extensions:\n  claude-code:\n    orchestration:\n      other: x\n'), 'workflow');
});

test('S1: orchestration under another tool, directly under extensions, or deeper does not count', () => {
  assert.equal(modeOf('extensions:\n  other-tool:\n    orchestration:\n      mode: off\n'), 'workflow');
  assert.equal(modeOf('extensions:\n  orchestration:\n    mode: off\n'), 'workflow');
  assert.equal(modeOf('extensions:\n  claude-code:\n    foo:\n      orchestration:\n        mode: off\n'), 'workflow');
  assert.equal(modeOf('extensions:\n  x:\n    claude-code:\n      orchestration:\n        mode: off\n'), 'workflow');
});

test('S1: no leak across sibling blocks inside claude-code, and a later orchestration block is still found', () => {
  assert.equal(nestedMode('orchestration:\nretrospective:\n  mode: off\n'), 'workflow');
  assert.equal(nestedMode('retrospective:\n  mode: dispatch\norchestration:\n  mode: schema\n'), 'schema');
  assert.equal(nestedMode('orchestration:\n  mode: schema\nretrospective:\n  mode: off\n'), 'schema');
});

test('S1: a sibling top-level block after extensions does not leak its mode into the section', () => {
  const text = 'extensions:\n  claude-code:\n    orchestration:\n      other: x\nretrospective:\n  mode: off\n';
  assert.equal(modeOf(text), 'workflow');
});

test('S1: garbled text never throws and yields workflow', () => {
  for (const t of ['{{{', 'extensions: {', '\u0000\u0001\ufffd\u00ff', 'extensions:\n  claude-code:\n    orchestration:\n      mode', 'extensions:\n  claude-code:\n    orchestration: {']) {
    assert.equal(modeOf(t), 'workflow', JSON.stringify(t));
  }
});

test('S1: realistic multi-block config reads the nested mode', () => {
  const text = [
    'project:',
    '  rootId: ce064d2c-7c03-4eac-ae7d-89a14c2ba273',
    'extensions:',
    '  claude-code:',
    '    retrospective:',
    '      mode: dispatch',
    '    orchestration:',
    '      mode: schema',
    'work_item_schemas:',
    '  default:',
    '    notes: []',
    '',
  ].join('\n');
  assert.equal(modeOf(text), 'schema');
});

test('S1: replay is idempotent; each call returns a fresh object; the wrapper agrees', () => {
  const text = nest('orchestration:\n  mode: off\n');
  const a = parseOrchestrationConfig(text);
  a.mode = 'schema';
  const b = parseOrchestrationConfig(text);
  assert.notEqual(a, b);
  assert.equal(b.mode, 'off');
  assert.equal(parseOrchestrationConfig(text).mode, 'off');
  assert.equal(parseOrchestrationMode(nest('orchestration:\n  mode: schema\n')), 'schema');
  assert.equal(parseOrchestrationMode('orchestration:\n  mode: schema\n'), 'workflow');
});

test('S1: constants', () => {
  assert.deepEqual([...ORCHESTRATION_MODES], ['workflow', 'schema', 'off']);
  assert.ok(Object.isFrozen(ORCHESTRATION_MODES));
  assert.equal(DEFAULT_ORCHESTRATION_MODE, 'workflow');
});

// ---- S2: readClaudeCodeSection -------------------------------------------------------------

test('S2: block form is found and its lines feed yaml-lite scalar()', () => {
  const s = readClaudeCodeSection('extensions:\n  claude-code:\n    orchestration:\n      mode: off\n', 'orchestration');
  assert.ok(s, 'section found');
  assert.equal(s.inline, null);
  assert.ok(Array.isArray(s.lines));
  assert.equal(scalar(s.lines, 'mode'), 'off');
});

test('S2: inline form is found and feeds yaml-lite inlineScalar()', () => {
  const s = readClaudeCodeSection('extensions:\n  claude-code:\n    orchestration: { mode: schema }\n', 'orchestration');
  assert.ok(s, 'section found');
  assert.equal(typeof s.inline, 'string');
  assert.equal(inlineScalar(s.inline, 'mode'), 'schema');
});

test('S2: indent width 4 and 8, double- and single-quoted claude-code keys', () => {
  for (const key of ['claude-code', '"claude-code"', "'claude-code'"]) {
    const s4 = readClaudeCodeSection(`extensions:\n    ${key}:\n        orchestration:\n            mode: off\n`, 'orchestration');
    assert.ok(s4, `indent 4, key ${key}`);
    assert.equal(scalar(s4.lines, 'mode'), 'off');
    const s2 = readClaudeCodeSection(`extensions:\n  ${key}:\n    orchestration:\n      mode: schema\n`, 'orchestration');
    assert.ok(s2, `indent 2, key ${key}`);
    assert.equal(scalar(s2.lines, 'mode'), 'schema');
  }
  const wide = readClaudeCodeSection('extensions:\n        claude-code:\n                orchestration:\n                        mode: off\n', 'orchestration');
  assert.ok(wide, 'indent 8');
  assert.equal(scalar(wide.lines, 'mode'), 'off');
});

test('S2: CRLF line endings and a leading BOM', () => {
  const crlf = readClaudeCodeSection('extensions:\r\n  claude-code:\r\n    orchestration:\r\n      mode: off\r\n', 'orchestration');
  assert.ok(crlf, 'CRLF');
  assert.equal(scalar(crlf.lines, 'mode'), 'off');
  const bom = readClaudeCodeSection('\uFEFFextensions:\n  claude-code:\n    orchestration:\n      mode: off\n', 'orchestration');
  assert.ok(bom, 'BOM');
  assert.equal(scalar(bom.lines, 'mode'), 'off');
});

test('S2: column-0 comments and blank lines between the levels are tolerated', () => {
  const s = readClaudeCodeSection(
    'extensions:\n# comment\n\n  claude-code:\n# comment\n    orchestration:\n\n# comment\n      mode: off\n',
    'orchestration',
  );
  assert.ok(s);
  assert.equal(scalar(s.lines, 'mode'), 'off');
});

test('S2: any section name under claude-code is addressable, siblings do not bleed into it', () => {
  const text = [
    'extensions:',
    '  claude-code:',
    '    retrospective:',
    '      mode: dispatch',
    '    orchestration:',
    '      mode: off',
    '    other: { k: v }',
    '',
  ].join('\n');
  const retro = readClaudeCodeSection(text, 'retrospective');
  assert.ok(retro);
  assert.equal(scalar(retro.lines, 'mode'), 'dispatch');
  const orch = readClaudeCodeSection(text, 'orchestration');
  assert.ok(orch);
  assert.equal(scalar(orch.lines, 'mode'), 'off');
  assert.ok(!orch.lines.some((l) => l.includes('dispatch')), 'retrospective lines must not bleed into orchestration');
  const other = readClaudeCodeSection(text, 'other');
  assert.ok(other);
  assert.equal(inlineScalar(other.inline, 'k'), 'v');
});

test('S2: not found -> null for every non-matching placement', () => {
  const cases = [
    ['other tool', 'extensions:\n  other-tool:\n    orchestration:\n      mode: off\n'],
    ['directly under extensions', 'extensions:\n  orchestration:\n    mode: off\n'],
    ['claude-code nested deeper', 'extensions:\n  x:\n    claude-code:\n      orchestration:\n        mode: off\n'],
    ['section nested deeper inside claude-code', 'extensions:\n  claude-code:\n    foo:\n      orchestration:\n        mode: off\n'],
    ['top-level only', 'orchestration:\n  mode: off\n'],
    ['flow-style empty extensions', 'extensions: {}\n'],
    ['claude-code without that section', 'extensions:\n  claude-code:\n    retrospective:\n      mode: off\n'],
    ['sibling top-level key claude-code', 'claude-code:\n  orchestration:\n    mode: off\n'],
    ['empty text', ''],
  ];
  for (const [name, text] of cases) {
    assert.equal(readClaudeCodeSection(text, 'orchestration'), null, name);
  }
});

test('S2: non-string input returns null without throwing', () => {
  for (const v of [42, null, undefined, {}, [], true]) {
    assert.equal(readClaudeCodeSection(v, 'orchestration'), null);
  }
});

test('S2: a second top-level block after extensions ends the section (no read past the block)', () => {
  const s = readClaudeCodeSection(
    'extensions:\n  claude-code:\n    orchestration:\n      other: x\nretrospective:\n  mode: off\n',
    'orchestration',
  );
  assert.ok(s);
  assert.equal(scalar(s.lines, 'mode'), null);
});