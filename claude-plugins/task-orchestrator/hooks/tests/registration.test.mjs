import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { orchestratorToolMatcher, isOrchestratorServerKey, SERVER_SEGMENT_TOKEN } from '../registration.mjs';

const HOOKS_CONFIG = fileURLToPath(new URL('../hooks-config.json', import.meta.url));

test('SERVER_SEGMENT_TOKEN is task-orchestrator', () => {
  assert.equal(SERVER_SEGMENT_TOKEN, 'task-orchestrator');
});

test('orchestratorToolMatcher: single tool builds an anchored regex matcher', () => {
  const matcher = orchestratorToolMatcher(['manage_notes']);
  assert.equal(matcher, '^mcp__.*task-orchestrator.*__manage_notes$');
});

test('orchestratorToolMatcher: multiple tools build an alternation', () => {
  const matcher = orchestratorToolMatcher(['advance_item', 'manage_notes']);
  assert.equal(matcher, '^mcp__.*task-orchestrator.*__(advance_item|manage_notes)$');
});

test('orchestratorToolMatcher: throws on empty/invalid input', () => {
  assert.throws(() => orchestratorToolMatcher([]));
  assert.throws(() => orchestratorToolMatcher());
});

// S1 — accepted server-segment spellings
test('matcher regex accepts all documented server-segment spellings', () => {
  const re = new RegExp(orchestratorToolMatcher(['advance_item', 'manage_notes']));
  const accepted = [
    'mcp__mcp-task-orchestrator__advance_item',
    'mcp__mcp-task-orchestrator-http__manage_notes',
    'mcp__task-orchestrator__advance_item',
    'mcp__plugin_task-orchestrator_task-orchestrator__advance_item',
  ];
  for (const name of accepted) {
    assert.ok(re.test(name), `expected ${name} to match`);
  }
});

// S2 — rejected names (anchoring + wrong server + wrong tool)
test('matcher regex rejects non-matching server names and tool names', () => {
  const re = new RegExp(orchestratorToolMatcher(['advance_item', 'manage_notes']));
  const rejected = [
    'mcp__other__advance_item',
    'mcp__mcp-task-orchestrator__advance_item_x',
    'mcp__mcp-task-orchestrator__query_items',
    'mcp__to__advance_item',
  ];
  for (const name of rejected) {
    assert.ok(!re.test(name), `expected ${name} to be rejected`);
  }
});

test('isOrchestratorServerKey: matches any key containing the token', () => {
  assert.ok(isOrchestratorServerKey('mcp-task-orchestrator'));
  assert.ok(isOrchestratorServerKey('mcp-task-orchestrator-http'));
  assert.ok(isOrchestratorServerKey('task-orchestrator'));
  assert.ok(!isOrchestratorServerKey('tasks'));
  assert.ok(!isOrchestratorServerKey(''));
  assert.ok(!isOrchestratorServerKey(undefined));
});

// S3 — drift check: hooks-config.json's orchestrator-tool matchers must equal the builder output,
// and no matcher anywhere may contain the old hard-coded literal.
test('hooks-config.json matchers match the registration.mjs builder output (no drift)', () => {
  const config = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf-8'));

  const expectedByCommand = {
    'skill-enforcement.mjs': orchestratorToolMatcher(['manage_notes']),
    'enforce-actor-attribution.mjs': orchestratorToolMatcher(['advance_item', 'manage_notes']),
    'retro-trigger.mjs': orchestratorToolMatcher(['advance_item', 'complete_tree']),
    'phase-guard-record.mjs': orchestratorToolMatcher(['advance_item']),
  };

  const allMatchers = [];
  for (const eventEntries of Object.values(config.hooks)) {
    for (const entry of eventEntries) {
      allMatchers.push(entry.matcher);
      for (const hookDef of entry.hooks) {
        for (const [commandFile, expectedMatcher] of Object.entries(expectedByCommand)) {
          if (hookDef.command && hookDef.command.includes(commandFile)) {
            assert.equal(
              entry.matcher,
              expectedMatcher,
              `matcher for ${commandFile} should equal orchestratorToolMatcher output`
            );
          }
        }
      }
    }
  }

  for (const matcher of allMatchers) {
    assert.ok(
      !matcher.includes('mcp__mcp-task-orchestrator__'),
      `matcher "${matcher}" still contains the hard-coded literal server name`
    );
  }
});
