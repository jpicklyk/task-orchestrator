// Contract tests for the 4.0 hook matchers and scripts/lib/contract-checks.mjs checkMatchers (58facb39).
// Scenarios S6 and S7 match the item's frozen `test-plan` note. Oracles: task-scope AC3 (matcher table),
// the orchestration-profile tool set derived from current/src/test/resources/golden/tools/*.orchestration.json,
// and the registration.mjs builder signature. Never "what checkMatchers returns today".
// Node 22, node:test, no dependencies.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { checkMatchers, RETIRED_3X_TOOLS } from '../../scripts/lib/contract-checks.mjs';
import { orchestratorToolMatcher, SERVER_SEGMENT_TOKEN } from '../registration.mjs';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/]$/, '');
const GOLDEN_DIR = join(PLUGIN, '..', '..', 'current', 'src', 'test', 'resources', 'golden', 'tools');
const HOOKS_CONFIG = JSON.parse(readFileSync(join(PLUGIN, 'hooks', 'hooks-config.json'), 'utf8'));

const ORCHESTRATION_TOOLS = readdirSync(GOLDEN_DIR)
  .filter((f) => f.endsWith('.orchestration.json'))
  .map((f) => f.split('.')[0])
  .sort();

// AC3 matcher table: hook script -> tools it is registered on.
const EXPECTED = {
  'skill-enforcement.mjs': ['notes_write'],
  'enforce-actor-attribution.mjs': ['advance', 'notes_write'],
  'retro-trigger.mjs': ['runs_write'],
  'phase-guard-record.mjs': ['advance'],
  'dispatch-hint.mjs': ['advance'],
};

// Independent parse of a builder-shaped matcher: the tool part follows the last "__".
function toolsOfMatcher(matcher) {
  const part = matcher.slice(matcher.lastIndexOf('__') + 2).replace(/[$]$/, '').replace(/^[(]/, '').replace(/[)]$/, '');
  return part.split('|');
}

function configWith(event, matcher) {
  return { hooks: { [event]: [{ matcher, hooks: [{ type: 'command', command: 'node "x/some-hook.mjs"', timeout: 5 }] }] } };
}

// ---- S6: the real hooks-config.json ------------------------------------------------------

test('S6: every registered script from the AC3 table is wired to exactly its table matcher', () => {
  const seen = new Set();
  for (const entries of Object.values(HOOKS_CONFIG.hooks)) {
    for (const entry of entries) {
      for (const hook of entry.hooks) {
        for (const [script, tools] of Object.entries(EXPECTED)) {
          if (hook.command.includes(script)) {
            seen.add(script);
            assert.equal(entry.matcher, orchestratorToolMatcher(tools), `${script} matcher`);
          }
        }
      }
    }
  }
  assert.deepEqual([...seen].sort(), Object.keys(EXPECTED).sort(), 'every table script is registered');
});

test('S6: every server-token matcher names only orchestration-profile tools, no retired 3.x name, no hard-coded server literal', () => {
  let tokenMatchers = 0;
  for (const [event, entries] of Object.entries(HOOKS_CONFIG.hooks)) {
    for (const entry of entries) {
      const m = entry.matcher;
      assert.ok(!m.includes('mcp__mcp-task-orchestrator__'), `${event} matcher "${m}" hard-codes the server name`);
      if (!m.includes(SERVER_SEGMENT_TOKEN)) continue;
      tokenMatchers += 1;
      for (const tool of toolsOfMatcher(m)) {
        assert.ok(ORCHESTRATION_TOOLS.includes(tool), `${event} matcher "${m}" names "${tool}", not an orchestration-profile tool`);
        assert.ok(!RETIRED_3X_TOOLS.includes(tool), `${event} matcher "${m}" names retired tool "${tool}"`);
      }
    }
  }
  assert.ok(tokenMatchers >= 5, `expected at least 5 server-token matchers, saw ${tokenMatchers}`);
});

test('S6: checkMatchers over the real hooks-config against the orchestration tool set reports nothing', () => {
  assert.deepEqual(checkMatchers(HOOKS_CONFIG, ORCHESTRATION_TOOLS), []);
});

// ---- S7: checkMatchers fixtures ----------------------------------------------------------

test('S7: a matcher on retired advance_item is a violation naming event, matcher and tool', () => {
  const matcher = orchestratorToolMatcher(['advance_item']);
  const v = checkMatchers(configWith('PostToolUse', matcher), ORCHESTRATION_TOOLS);
  assert.ok(v.length >= 1, 'violation expected');
  const hit = v.find((x) => x.tool === 'advance_item');
  assert.ok(hit, `no violation names advance_item: ${JSON.stringify(v)}`);
  assert.equal(hit.event, 'PostToolUse');
  assert.equal(hit.matcher, matcher);
  assert.equal(typeof hit.reason, 'string');
  assert.ok(hit.reason.length > 0);
});

test('S7: AC probe - swapping the real phase-guard-record matcher back to advance_item turns the real-config check red', () => {
  const mutated = JSON.parse(JSON.stringify(HOOKS_CONFIG));
  let swapped = 0;
  for (const entries of Object.values(mutated.hooks)) {
    for (const entry of entries) {
      if (entry.hooks.some((h) => h.command.includes('phase-guard-record.mjs'))) {
        entry.matcher = orchestratorToolMatcher(['advance_item']);
        swapped += 1;
      }
    }
  }
  assert.equal(swapped, 1);
  const v = checkMatchers(mutated, ORCHESTRATION_TOOLS);
  assert.ok(v.some((x) => x.tool === 'advance_item'));
});

test('S7: a tool valid in the director profile but absent from the orchestration set (report) is a violation', () => {
  assert.ok(!ORCHESTRATION_TOOLS.includes('report'), 'fixture precondition: report is not an orchestration tool');
  const v = checkMatchers(configWith('PostToolUse', orchestratorToolMatcher(['report'])), ORCHESTRATION_TOOLS);
  assert.ok(v.some((x) => x.tool === 'report'), JSON.stringify(v));
});

test('S7: a token-bearing matcher that is not the builder spelling is a violation even when its tool is allowed', () => {
  for (const spelled of [
    'mcp__.*task-orchestrator.*__advance',
    '^mcp__.*task-orchestrator.*__advance',
    '^mcp__mcp-task-orchestrator__advance$',
    '^mcp__.*task-orchestrator.*__(advance)$',
  ]) {
    const v = checkMatchers(configWith('PostToolUse', spelled), ORCHESTRATION_TOOLS);
    assert.ok(v.length >= 1, `non-builder spelling "${spelled}" must be a violation`);
  }
});

test('S7: builder-produced matchers over allowed tools report nothing (single tool, alternation, every orchestration tool)', () => {
  assert.deepEqual(checkMatchers(configWith('PreToolUse', orchestratorToolMatcher(['notes_write'])), ORCHESTRATION_TOOLS), []);
  assert.deepEqual(checkMatchers(configWith('PostToolUse', orchestratorToolMatcher(['advance', 'items_write', 'runs_write'])), ORCHESTRATION_TOOLS), []);
  assert.deepEqual(checkMatchers(configWith('PostToolUse', orchestratorToolMatcher(ORCHESTRATION_TOOLS)), ORCHESTRATION_TOOLS), []);
});

test('S7: an alternation with one bad member reports exactly that member, with its event key', () => {
  const v = checkMatchers(configWith('Stop', orchestratorToolMatcher(['advance', 'query_items'])), ORCHESTRATION_TOOLS);
  assert.deepEqual(v.map((x) => x.tool), ['query_items']);
  assert.equal(v[0].event, 'Stop');
});

test('S7 probes: matchers without the server token (empty, "*", plain tool names, absent) are skipped, never throw', () => {
  for (const m of ['', '*', 'Bash', 'Agent', 'EnterPlanMode', 'config.yaml', undefined]) {
    const entry = { hooks: [{ type: 'command', command: 'node "x/h.mjs"', timeout: 5 }] };
    if (m !== undefined) entry.matcher = m;
    assert.deepEqual(checkMatchers({ hooks: { SessionStart: [entry] } }, ORCHESTRATION_TOOLS), [], `matcher ${JSON.stringify(m)}`);
  }
  assert.deepEqual(checkMatchers({ hooks: {} }, ORCHESTRATION_TOOLS), []);
});

test('S7 probe: violations are reported per event and per entry (two bad entries in different events, both reported)', () => {
  const cfg = {
    hooks: {
      PreToolUse: [{ matcher: orchestratorToolMatcher(['manage_notes']), hooks: [{ type: 'command', command: 'a', timeout: 1 }] }],
      PostToolUse: [{ matcher: orchestratorToolMatcher(['advance_item']), hooks: [{ type: 'command', command: 'b', timeout: 1 }] }],
    },
  };
  const v = checkMatchers(cfg, ORCHESTRATION_TOOLS);
  assert.deepEqual(v.map((x) => `${x.event}:${x.tool}`).sort(), ['PostToolUse:advance_item', 'PreToolUse:manage_notes']);
});
