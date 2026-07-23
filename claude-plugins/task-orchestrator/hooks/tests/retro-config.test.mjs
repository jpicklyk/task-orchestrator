import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  parseRetrospectiveConfig,
  parseRetrospectiveMode,
  parseProjectRootId,
  buildNudge,
  buildDispatch,
  COOLDOWN_MS,
} from '../retro-lib.mjs';

test('parseRetrospectiveConfig: defaults when config content is absent', () => {
  assert.deepEqual(parseRetrospectiveConfig(null), { mode: 'nudge', dispatchThreshold: 3, cooldownMinutes: 30 });
  assert.deepEqual(parseRetrospectiveConfig(''), { mode: 'nudge', dispatchThreshold: 3, cooldownMinutes: 30 });
});

test('parseRetrospectiveConfig: defaults when the retrospective: block is absent', () => {
  const cfg = parseRetrospectiveConfig('work_item_schemas:\n  foo: bar\n');
  assert.deepEqual(cfg, { mode: 'nudge', dispatchThreshold: 3, cooldownMinutes: 30 });
});

test('parseRetrospectiveConfig: reads all three keys from a block', () => {
  const content = [
    'retrospective:',
    '  mode: dispatch',
    '  dispatchThreshold: 5',
    '  cooldownMinutes: 10',
  ].join('\n');
  assert.deepEqual(parseRetrospectiveConfig(content), { mode: 'dispatch', dispatchThreshold: 5, cooldownMinutes: 10 });
});

test('parseRetrospectiveConfig: invalid dispatchThreshold falls back to the default of 3', () => {
  assert.equal(
    parseRetrospectiveConfig('retrospective:\n  dispatchThreshold: not-a-number\n').dispatchThreshold,
    3,
  );
  assert.equal(parseRetrospectiveConfig('retrospective:\n  dispatchThreshold: -1\n').dispatchThreshold, 3);
  assert.equal(parseRetrospectiveConfig('retrospective:\n  dispatchThreshold: 2.5\n').dispatchThreshold, 3);
});

test('parseRetrospectiveConfig: valid dispatchThreshold of 0 is honored (not treated as invalid)', () => {
  assert.equal(parseRetrospectiveConfig('retrospective:\n  dispatchThreshold: 0\n').dispatchThreshold, 0);
});

test('parseRetrospectiveConfig: invalid cooldownMinutes falls back to the default of 30', () => {
  assert.equal(
    parseRetrospectiveConfig('retrospective:\n  cooldownMinutes: not-a-number\n').cooldownMinutes,
    30,
  );
  assert.equal(parseRetrospectiveConfig('retrospective:\n  cooldownMinutes: 0\n').cooldownMinutes, 30);
  assert.equal(parseRetrospectiveConfig('retrospective:\n  cooldownMinutes: -5\n').cooldownMinutes, 30);
});

test('parseRetrospectiveConfig: valid non-default cooldownMinutes is honored', () => {
  assert.equal(parseRetrospectiveConfig('retrospective:\n  cooldownMinutes: 10\n').cooldownMinutes, 10);
});

test('parseRetrospectiveConfig: mode off stays off — never silently falls back to nudge', () => {
  assert.equal(parseRetrospectiveConfig('retrospective:\n  mode: off\n').mode, 'off');
});

test('parseRetrospectiveConfig: unrecognized mode value falls back to nudge', () => {
  assert.equal(parseRetrospectiveConfig('retrospective:\n  mode: bogus\n').mode, 'nudge');
});

test('parseRetrospectiveConfig: a column-0 comment above mode does not break the block', () => {
  const content = [
    'retrospective:',
    '# comment describing the mode field',
    '  mode: dispatch',
  ].join('\n');
  assert.equal(parseRetrospectiveConfig(content).mode, 'dispatch');
});

test('parseRetrospectiveConfig: inline {} form reads mode and dispatchThreshold', () => {
  const cfg = parseRetrospectiveConfig('retrospective: { mode: dispatch, dispatchThreshold: 7 }\n');
  assert.equal(cfg.mode, 'dispatch');
  assert.equal(cfg.dispatchThreshold, 7);
});

test('parseRetrospectiveConfig: inline {} form with a trailing comment after the brace still reads mode (fix M2)', () => {
  const cfg = parseRetrospectiveConfig('retrospective: { mode: dispatch }  # comment\n');
  assert.equal(cfg.mode, 'dispatch');
});

test('parseRetrospectiveMode: thin wrapper matches parseRetrospectiveConfig().mode', () => {
  const content = 'retrospective:\n  mode: dispatch\n';
  assert.equal(parseRetrospectiveMode(content), parseRetrospectiveConfig(content).mode);
  assert.equal(parseRetrospectiveMode(null), 'nudge');
});

test('parseProjectRootId: resolves rootId and tolerates a column-0 comment', () => {
  const content = [
    'project:',
    '# a stray comment',
    '  rootId: "abc-123"',
    'retrospective:',
    '  mode: nudge',
  ].join('\n');
  assert.equal(parseProjectRootId(content), 'abc-123');
});

test('parseProjectRootId: null when project: block is absent', () => {
  assert.equal(parseProjectRootId('retrospective:\n  mode: nudge\n'), null);
  assert.equal(parseProjectRootId(null), null);
});

test('buildNudge: empty roots renders a clean command with no argument and no root(s) fragment', () => {
  const text = buildNudge([]);
  assert.ok(!text.includes('root(s):'));
  assert.ok(text.includes('`/session-retrospective`'));
});

test('buildNudge: non-empty roots renders the root list and the first root as the argument', () => {
  const text = buildNudge(['aaa', 'bbb']);
  assert.ok(text.includes('root(s): aaa, bbb'));
  assert.ok(text.includes('`/session-retrospective aaa`'));
});

test('buildDispatch: includes roots and the ancestor rootId', () => {
  const text = buildDispatch(['aaa'], 'root-1');
  assert.ok(text.includes('aaa'));
  assert.ok(text.includes('root-1'));
  assert.ok(text.includes('Retrospective dispatch'));
});

test('COOLDOWN_MS default equals 30 minutes', () => {
  assert.equal(COOLDOWN_MS, 30 * 60 * 1000);
});
