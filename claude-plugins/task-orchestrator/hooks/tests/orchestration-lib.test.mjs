import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  parseOrchestrationConfig,
  parseOrchestrationMode,
  ORCHESTRATION_MODES,
  DEFAULT_ORCHESTRATION_MODE,
} from '../orchestration-lib.mjs';

const mode = (t) => parseOrchestrationConfig(t).mode;

test('absent input falls back to workflow', () => {
  for (const v of [null, undefined, '']) assert.equal(mode(v), 'workflow');
});

test('non-string input falls back to workflow without throwing', () => {
  for (const v of [42, {}, [], true, Buffer.from('orchestration:\n  mode: off\n')]) {
    assert.equal(mode(v), 'workflow');
  }
});

test('block absent or mode key absent -> workflow', () => {
  assert.equal(mode('retrospective:\n  mode: off\n'), 'workflow');
  assert.equal(mode('orchestration:\n  other: x\n'), 'workflow');
  assert.equal(mode('orchestration:\n'), 'workflow');
});

test('each valid mode in block form; off stays off', () => {
  for (const m of ORCHESTRATION_MODES) assert.equal(mode(`orchestration:\n  mode: ${m}\n`), m);
  assert.equal(mode('orchestration:\n  mode: off\n'), 'off');
});

test('inline forms', () => {
  assert.equal(mode('orchestration: {mode: schema}'), 'schema');
  assert.equal(mode('orchestration: { mode: off }'), 'off');
  assert.equal(mode('orchestration: { mode: schema } # note'), 'schema');
});

test('invalid values fall back to workflow', () => {
  for (const v of ['turbo', 'true', 'workflows']) assert.equal(mode(`orchestration:\n  mode: ${v}\n`), 'workflow');
});

test('case, whitespace and quotes are normalized', () => {
  for (const [text, want] of [
    ['orchestration:\n  mode: Schema\n', 'schema'],
    ['orchestration:\n  mode: OFF\n', 'off'],
    ['orchestration:\n  mode:   schema  \n', 'schema'],
    ['orchestration:\n  mode: "schema"\n', 'schema'],
    ["orchestration:\n  mode: 'off'\n", 'off'],
    ['orchestration:\n  mode: off # why\n', 'off'],
  ]) assert.equal(mode(text), want);
});

test('column-0 comment between header and mode line still reads mode', () => {
  assert.equal(mode('orchestration:\n# a comment\n  mode: schema\n'), 'schema');
});

test('no leak across blocks', () => {
  assert.equal(mode('orchestration:\nretrospective:\n  mode: off\n'), 'workflow');
  assert.equal(mode('retrospective:\n  mode: dispatch\norchestration:\n  mode: schema\n'), 'schema');
});

test('CRLF text', () => {
  assert.equal(mode('orchestration:\r\n  mode: schema\r\n'), 'schema');
});

test('garbled text never throws', () => {
  for (const t of ['{{{', 'orchestration: {', '\u0000\u0001\ufffd\u00ff', 'orchestration:\n  mode']) {
    assert.equal(mode(t), 'workflow');
  }
});

test('realistic multi-block fixture', () => {
  const text = [
    'project:',
    '  rootId: ce064d2c-7c03-4eac-ae7d-89a14c2ba273',
    'retrospective:',
    '  mode: dispatch',
    '  github_feedback:',
    '    enabled: true',
    'actor_attribution:',
    '  mode: warn',
    'orchestration:',
    '  mode: schema',
    'work_item_schemas:',
    '  default:',
    '    notes: []',
    '',
  ].join('\n');
  assert.equal(mode(text), 'schema');
});

test('fresh object per call; wrapper agrees', () => {
  const a = parseOrchestrationConfig('orchestration:\n  mode: off\n');
  a.mode = 'schema';
  const b = parseOrchestrationConfig('orchestration:\n  mode: off\n');
  assert.notEqual(a, b);
  assert.equal(b.mode, 'off');
  assert.equal(parseOrchestrationMode('orchestration:\n  mode: schema\n'), 'schema');
});

test('constants', () => {
  assert.deepEqual([...ORCHESTRATION_MODES], ['workflow', 'schema', 'off']);
  assert.ok(Object.isFrozen(ORCHESTRATION_MODES));
  assert.equal(DEFAULT_ORCHESTRATION_MODE, 'workflow');
});
