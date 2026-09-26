// Blind test-author coverage for c39fe915 (subagent-start.mjs: the SubagentStart protocol text
// branches on the POSITIVE errorCode a failure carries, never on the absence of one). Test author
// actor id: test-author:c39fe915.
//
// Oracle (O-D): dispatch DECLARATIONS block for subagent-start.mjs -- "a SubagentStart hook: reads
// JSON on stdin, writes JSON.stringify({ hookSpecificOutput: { hookEventName: "SubagentStart",
// additionalContext: "<protocol text>" } }) to stdout. The protocol text now contains: the phrase
// errorCode: "gate_blocked" together with previousRole as the already-in-phase signal; the
// sentence "Every `advance_item` failure now carries an `errorCode`; branch on which POSITIVE
// code you got, never on the absence of one."; and a paragraph beginning "**Any other
// `errorCode`**" that lists item_not_found, invalid_trigger, invalid_actor, dependency_blocked,
// validation_failed, invalid_transition, apply_failed, not_claim_holder, rejected_by_policy and
// instructs the agent to stop and report. It no longer contains the text "no `errorCode`"."
//
// Disclosure (per test-author blindness rule 4.6): this session's OWN SubagentStart hook fired
// automatically at dispatch, before any tool call of mine, and its additionalContext happened to
// contain protocol text matching the OLD pre-fix wording (the exact "no errorCode" bug pattern
// this item fixes). That text was not fetched by any tool call of mine -- it was environment-
// injected context, not a read of subagent-start.mjs on this branch. I did not use it as an
// oracle: every assertion below is grounded solely in the DECLARATIONS block quoted above, not in
// anything I incidentally saw. Recorded in test-manifest's arbitration record as well.
//
// Scenario S15 (test-plan, section "node --test"): EXISTING-SURFACE -- subagent-start.mjs already
// emits a SubagentStart protocol text; this fix rewrites its errorCode-handling paragraphs.
// Narrowest-revert recipe per test-plan: revert the protocol-text edit and this file goes red
// (the old text still says "no errorCode" and lacks the "Any other errorCode" paragraph).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HOOK = fileURLToPath(new URL('../subagent-start.mjs', import.meta.url));

function runHook(payload = {}, envOverrides = {}) {
  // Explicitly delete TASK_ORCHESTRATOR_MODE by default so a stray value in the developer's own
  // shell can never flip a test that expects interactive behavior (see execution-mode.mjs's
  // headless gate and the item's risk-flag note on spawn-based test env hygiene).
  const env = { ...process.env, ...envOverrides };
  delete env.TASK_ORCHESTRATOR_MODE;
  if (envOverrides.TASK_ORCHESTRATOR_MODE !== undefined) {
    env.TASK_ORCHESTRATOR_MODE = envOverrides.TASK_ORCHESTRATOR_MODE;
  }
  return spawnSync(process.execPath, [HOOK], {
    input: JSON.stringify(payload),
    env,
    encoding: 'utf-8',
  });
}

function protocolText(payload = { session_id: 'test-session', agent_id: 'test-agent', agent_type: 'task-orchestrator:implementer' }) {
  const res = runHook(payload);
  assert.equal(res.status, 0, `hook exited non-zero: ${res.stderr}`);
  const out = JSON.parse(res.stdout);
  assert.equal(out.hookSpecificOutput.hookEventName, 'SubagentStart');
  return out.hookSpecificOutput.additionalContext;
}

// ── S15: the already-in-phase signal is a positive errorCode, not an absence ─────────────────

test('S15: protocol text names errorCode gate_blocked together with previousRole as the already-in-phase signal', () => {
  const text = protocolText();
  assert.ok(text.includes('errorCode: "gate_blocked"'), 'expected the literal phrase errorCode: "gate_blocked"');
  assert.ok(text.includes('previousRole'), 'expected previousRole named alongside gate_blocked');
});

test('S15: protocol text states the branch-on-positive-code rule verbatim', () => {
  const text = protocolText();
  assert.ok(
    text.includes(
      'Every `advance_item` failure now carries an `errorCode`; branch on which POSITIVE code you got, never on the absence of one.'
    ),
    'expected the exact branch-on-positive-code sentence'
  );
});

test('S15: protocol text no longer contains the old "no errorCode" absence-based wording', () => {
  const text = protocolText();
  assert.ok(!text.includes('no `errorCode`'), 'the old absence-based phrasing must be gone');
});

// ── S15: every other errorCode is named and the agent is told to stop and report ─────────────

test('S15: protocol text has an "Any other errorCode" paragraph listing every remaining code', () => {
  const text = protocolText();
  const idx = text.indexOf('**Any other `errorCode`**');
  assert.ok(idx >= 0, 'expected a paragraph beginning **Any other `errorCode`**');
  // The paragraph is the intended reading unit; take a generous window to remain robust to
  // reflowing without silently matching text far outside the paragraph.
  const paragraph = text.slice(idx, idx + 800);
  const expectedCodes = [
    'item_not_found',
    'invalid_trigger',
    'invalid_actor',
    'dependency_blocked',
    'validation_failed',
    'invalid_transition',
    'apply_failed',
    'not_claim_holder',
    'rejected_by_policy',
  ];
  for (const code of expectedCodes) {
    assert.ok(paragraph.includes(code), `expected "${code}" listed in the Any other errorCode paragraph`);
  }
});

test('S15: protocol text instructs the agent to stop and report for any other errorCode', () => {
  const text = protocolText();
  const idx = text.indexOf('**Any other `errorCode`**');
  assert.ok(idx >= 0);
  const paragraph = text.slice(idx, idx + 800);
  assert.ok(/stop/i.test(paragraph), 'expected the paragraph to instruct stopping');
  assert.ok(/report/i.test(paragraph), 'expected the paragraph to instruct reporting');
});

// ── 004d65fd: headless-iteration and non-phase-owner agent-type gating ───────────────────────
// Oracle: item 004d65fd's specification, "Hooks that must honor it" table — subagent-start.mjs
// row: headless iteration exits silently before any output; interactive subagent injects the
// protocol ONLY when isPhaseOwnerAgentType(agent_type) is true.

test('S4: phase-owner agent_type (plugin-qualified implementer) gets the protocol', () => {
  const text = protocolText({ session_id: 's', agent_id: 'a', agent_type: 'task-orchestrator:implementer' });
  assert.ok(text.includes('Agent-Owned-Phase Protocol'));
});

test('S4: phase-owner agent_type (bare reviewer) gets the protocol', () => {
  const text = protocolText({ session_id: 's', agent_id: 'a', agent_type: 'reviewer' });
  assert.ok(text.includes('Agent-Owned-Phase Protocol'));
});

test('S5: non-phase-owner agent types get no output', () => {
  for (const agentType of ['general-purpose', 'Explore', 'Plan', 'claude-code-guide', 'task-orchestrator:implementer-helper', undefined]) {
    const res = runHook({ session_id: 's', agent_id: 'a', agent_type: agentType });
    assert.equal(res.status, 0, `hook exited non-zero for ${agentType}: ${res.stderr}`);
    assert.equal(res.stdout, '', `expected empty stdout for agent_type ${agentType}, got: ${res.stdout}`);
  }
});

test('S6: headless iteration mode exits silently even for a phase-owner agent_type', () => {
  const res = runHook(
    { session_id: 's', agent_id: 'a', agent_type: 'task-orchestrator:implementer' },
    { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }
  );
  assert.equal(res.status, 0, `hook exited non-zero: ${res.stderr}`);
  assert.equal(res.stdout, '');
});

// ── 60e01b3a: workflow-subagent skip + conditional seat wording ──────────────────────────────
// Oracle: item 60e01b3a's specification -- skip injection entirely when agent_type is exactly
// "workflow-subagent" (Claude workflow agents follow their own script-driven transition logic),
// even if that dispatch happens to name an implementer/reviewer seat internally; and reword the
// protocol so only an entry seat (or a single phase owner with no seat named) is told to call
// advance_item(start) -- a non-entry or read-only seat should see no such instruction.

test('workflow-subagent agent_type gets no injected output at all', () => {
  const res = runHook({ session_id: 's', agent_id: 'a', agent_type: 'workflow-subagent' });
  assert.equal(res.status, 0, `hook exited non-zero: ${res.stderr}`);
  assert.equal(res.stdout, '', 'expected empty stdout for agent_type workflow-subagent');
});

test('workflow-subagent skip is unconditional on any other field in the payload', () => {
  // agent_type is always exactly "workflow-subagent" per the hooks reference for workflow-
  // dispatched subagents; this confirms the exact-match skip doesn't get overridden by other
  // fields (e.g. a workflow script happening to also pass a seat-like field).
  const res = runHook({ session_id: 's', agent_id: 'a', agent_type: 'workflow-subagent', seat: 'implementer' });
  assert.equal(res.status, 0, `hook exited non-zero: ${res.stderr}`);
  assert.equal(res.stdout, '');
});

test('invalid JSON on stdin fails open: proceeds as if no agent_type were given (no crash, exit 0, no output)', () => {
  const malformed = spawnSync(process.execPath, [HOOK], {
    input: '{not valid json',
    env: (() => {
      const env = { ...process.env };
      delete env.TASK_ORCHESTRATOR_MODE;
      return env;
    })(),
    encoding: 'utf-8',
  });
  assert.equal(malformed.status, 0, `hook exited non-zero on invalid stdin: ${malformed.stderr}`);
  assert.equal(malformed.stdout, '', 'invalid stdin has no agent_type, so no phase-owner match, so no output');
});

test('empty stdin fails open the same way: exit 0, no output', () => {
  const res = spawnSync(process.execPath, [HOOK], {
    input: '',
    env: (() => {
      const env = { ...process.env };
      delete env.TASK_ORCHESTRATOR_MODE;
      return env;
    })(),
    encoding: 'utf-8',
  });
  assert.equal(res.status, 0, `hook exited non-zero on empty stdin: ${res.stderr}`);
  assert.equal(res.stdout, '');
});

test('protocol text conditions advance_item(start) on being the entry seat, not a blanket instruction', () => {
  const text = protocolText();
  assert.ok(
    text.includes("Follow your dispatch prompt's seat assignment"),
    'expected the seat-deferral framing to open the protocol'
  );
  assert.ok(
    /entry seat/i.test(text),
    'expected the text to name the entry-seat concept as the one that advances the item'
  );
  assert.ok(
    /read-only.*never calls `advance_item`|never calls `advance_item`.*read-only/is.test(text) ||
      (/read-only/i.test(text) && /never calls `advance_item`/.test(text)),
    'expected the text to say a read-only agent never calls advance_item'
  );
});
