// Unit tests for hooks/execution-mode.mjs — pure functions, no subprocess needed.
// Oracle: item 004d65fd's specification, "Design" section 1.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isHeadlessIteration, isSubagentInvocation, isPhaseOwnerAgentType } from '../execution-mode.mjs';

// ── S1: isHeadlessIteration ───────────────────────────────────────────────────────────────────

test('S1: isHeadlessIteration is true only for an exact match on the sentinel value', () => {
  assert.equal(isHeadlessIteration({ TASK_ORCHESTRATOR_MODE: 'headless-iteration' }), true);
});

test('S1: isHeadlessIteration is false for absent, empty, differently-cased, or other values', () => {
  assert.equal(isHeadlessIteration({}), false);
  assert.equal(isHeadlessIteration({ TASK_ORCHESTRATOR_MODE: '' }), false);
  assert.equal(isHeadlessIteration({ TASK_ORCHESTRATOR_MODE: 'HEADLESS-ITERATION' }), false);
  assert.equal(isHeadlessIteration({ TASK_ORCHESTRATOR_MODE: 'interactive' }), false);
  assert.equal(isHeadlessIteration(undefined), false);
});

// ── isSubagentInvocation ──────────────────────────────────────────────────────────────────────

test('isSubagentInvocation is true only for a non-empty string agent_id', () => {
  assert.equal(isSubagentInvocation({ agent_id: 'sub-1' }), true);
  assert.equal(isSubagentInvocation({ agent_id: '' }), false);
  assert.equal(isSubagentInvocation({}), false);
  assert.equal(isSubagentInvocation({ agent_id: 123 }), false);
  assert.equal(isSubagentInvocation(undefined), false);
});

// ── S2: isPhaseOwnerAgentType ─────────────────────────────────────────────────────────────────

test('S2: isPhaseOwnerAgentType is true for plugin-qualified and bare implementer/reviewer', () => {
  assert.equal(isPhaseOwnerAgentType('task-orchestrator:implementer'), true);
  assert.equal(isPhaseOwnerAgentType('task-orchestrator:reviewer'), true);
  assert.equal(isPhaseOwnerAgentType('implementer'), true);
  assert.equal(isPhaseOwnerAgentType('reviewer'), true);
  assert.equal(isPhaseOwnerAgentType('some-project-plugin:reviewer'), true);
});

test('S2: isPhaseOwnerAgentType is false for other agent types, including near-miss substrings and undefined', () => {
  assert.equal(isPhaseOwnerAgentType('general-purpose'), false);
  assert.equal(isPhaseOwnerAgentType('Explore'), false);
  assert.equal(isPhaseOwnerAgentType('Plan'), false);
  assert.equal(isPhaseOwnerAgentType('claude-code-guide'), false);
  assert.equal(isPhaseOwnerAgentType('task-orchestrator:implementer-helper'), false);
  assert.equal(isPhaseOwnerAgentType(undefined), false);
  assert.equal(isPhaseOwnerAgentType(''), false);
});
