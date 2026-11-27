// decision-guard.mjs (a8b9ebfb S8-S15). Oracles: parent interface-contract item 5 (the deny rule and the message,
// verbatim), task-scope AC3-AC8, and the declared emit.mjs denyOutput shape. Never "what the script returns today".
// The guard is spawned as a subprocess with the hook input on stdin; it must exit 0 and print one JSON object.
// Node 22, node:test, no dependencies.

import './hermetic-claude-config.mjs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { denyOutput } from '../lib/emit.mjs';
import { orchestratorToolMatcher } from '../registration.mjs';

const HOOK = fileURLToPath(new URL('../decision-guard.mjs', import.meta.url));
const HOOKS_CONFIG = JSON.parse(readFileSync(fileURLToPath(new URL('../hooks-config.json', import.meta.url)), 'utf-8'));

const MSG =
  'A subagent never answers, acknowledges, pauses, resumes or reissues. Raise the decision (runs_write raise) or report it in your return; the director or the user answers.';
const DENY = denyOutput(MSG);
const TOOL = 'mcp__mcp-task-orchestrator__runs_write';
const DIRECTOR_TOOL = 'mcp__mcp-task-orchestrator-director__runs_write';

function exec(stdin) {
  const input = typeof stdin === 'string' ? stdin : JSON.stringify(stdin);
  const r = spawnSync(process.execPath, [HOOK], { input, encoding: 'utf-8' });
  assert.equal(r.status, 0, `exit ${r.status}: ${r.stderr}`);
  return { out: JSON.parse(r.stdout), raw: r.stdout };
}

const call = (ops, extra = {}) => ({ tool_name: TOOL, tool_input: { ops }, agent_id: 'a1', ...extra });
const ops = (...names) => names.map((op) => ({ op }));

test('S8 (AC3): a subagent op answer, ack, pause, resume or reissue is denied with the contract message, on both entry names', () => {
  for (const tool_name of [TOOL, DIRECTOR_TOOL]) {
    for (const op of ['answer', 'ack', 'pause', 'resume', 'reissue']) {
      const { out } = exec(call(ops(op), { tool_name }));
      assert.deepEqual(out, DENY, `${tool_name} ${op}`);
    }
  }
  assert.equal(DENY.hookSpecificOutput.permissionDecision, 'deny');
  assert.equal(DENY.hookSpecificOutput.permissionDecisionReason, MSG);
});

test('S9 (AC4): the same call with agent_id absent or an empty string is allowed', () => {
  for (const op of ['answer', 'ack', 'pause', 'resume', 'reissue']) {
    const absent = { tool_name: TOOL, tool_input: { ops: ops(op) } };
    assert.deepEqual(exec(absent).out, {}, `absent ${op}`);
    assert.deepEqual(exec(call(ops(op), { agent_id: '' })).out, {}, `empty ${op}`);
  }
});

test('S10 (AC5): a subagent call of only raise, end_seat, assign, open, close, update, withdraw is allowed, each alone and in one batch', () => {
  const allowed = ['raise', 'end_seat', 'assign', 'open', 'close', 'update', 'withdraw'];
  for (const op of allowed) assert.deepEqual(exec(call(ops(op))).out, {}, op);
  assert.deepEqual(exec(call(ops(...allowed))).out, {}, 'all in one batch');
});

test('S11 (AC6): a batch mixing an allowed op with a denied one is denied, whatever the order', () => {
  assert.deepEqual(exec(call(ops('raise', 'answer'))).out, DENY);
  assert.deepEqual(exec(call(ops('end_seat', 'pause'))).out, DENY);
  assert.deepEqual(exec(call(ops('resume', 'raise', 'assign'))).out, DENY);
  assert.deepEqual(exec(call(ops('raise', 'reissue'))).out, DENY);
});

test('S12 (AC7): malformed input is allowed, exit 0, never throws', () => {
  for (const stdin of ['', 'not json', '{', '[]', 'null', '42']) {
    assert.deepEqual(exec(stdin).out, {}, JSON.stringify(stdin));
  }
  assert.deepEqual(exec({ tool_name: TOOL, agent_id: 'a1' }).out, {}, 'no tool_input');
  assert.deepEqual(exec({ tool_name: TOOL, tool_input: null, agent_id: 'a1' }).out, {}, 'null tool_input');
  const badOps = [undefined, {}, 'answer', [], [null], ['answer'], [{ op: 5 }], [{ op: null }], [{}], [[]], { 0: { op: 'answer' } }];
  for (const o of badOps) {
    const input = { tool_name: TOOL, agent_id: 'a1', tool_input: o === undefined ? {} : { ops: o } };
    assert.deepEqual(exec(input).out, {}, `ops ${JSON.stringify(o)}`);
  }
});

test('S13 (AC8): only the exact lower-case op names are denied; other spellings are allowed', () => {
  for (const op of ['Answer', 'ANSWER', ' answer', 'answer ', 'Ack', 'PAUSE', 'resume\n', '\tresume', 'answers']) {
    assert.deepEqual(exec(call(ops(op))).out, {}, JSON.stringify(op));
  }
});

test('S14 (contract item 5): a non-string agent_id does not mark a subagent', () => {
  for (const agent_id of [7, true, {}, [], null]) {
    assert.deepEqual(exec(call(ops('answer'), { agent_id })).out, {}, JSON.stringify(agent_id));
  }
});

test('S12-S14 probes: duplicate denied ops still deny; one input twice gives one identical output', () => {
  assert.deepEqual(exec(call(ops('answer', 'answer'))).out, DENY);
  const input = call(ops('raise', 'answer'));
  const a = exec(input);
  const b = exec(input);
  assert.equal(a.raw, b.raw);
  const c = exec(call(ops('raise')));
  const d = exec(call(ops('raise')));
  assert.equal(c.raw, d.raw);
});

test('S15 (contract item 5): exactly one PreToolUse group runs decision-guard.mjs, on the builder matcher, one hook, never blocking on failure', () => {
  const groups = HOOKS_CONFIG.hooks.PreToolUse.filter((g) => g.hooks.some((h) => h.command.includes('decision-guard.mjs')));
  assert.equal(groups.length, 1);
  const [g] = groups;
  assert.equal(g.matcher, orchestratorToolMatcher(['runs_write', 'decisions_write']));
  assert.equal(g.hooks.length, 1);
  assert.notEqual(g.hooks[0].onFailure, 'block');
  assert.equal(g.hooks[0].type, 'command');
  const total = (JSON.stringify(HOOKS_CONFIG).match(/decision-guard\.mjs/g) || []).length;
  assert.equal(total, 1, 'registered once across every event');
  const re = new RegExp(g.matcher);
  for (const name of [TOOL, DIRECTOR_TOOL, 'mcp__plugin_task-orchestrator_task-orchestrator__runs_write', 'mcp__mcp-task-orchestrator__decisions_write', 'mcp__mcp-task-orchestrator-director__decisions_write']) {
    assert.ok(re.test(name), `expected ${name} to match`);
  }
  for (const name of ['mcp__other__runs_write', 'mcp__mcp-task-orchestrator__decisions_read', 'mcp__mcp-task-orchestrator__runs_read', 'mcp__mcp-task-orchestrator__runs_write_x']) {
    assert.ok(!re.test(name), `expected ${name} to be rejected`);
  }
});

const DMSG =
  'A subagent never answers, acknowledges, delegates or escalates a decision. Raise it (decisions_write raise) or report it in your return; the director or the user answers.';
const DDENY = denyOutput(DMSG);
const DTOOL = 'mcp__mcp-task-orchestrator__decisions_write';
const DDIRECTOR_TOOL = 'mcp__mcp-task-orchestrator-director__decisions_write';
const dcall = (names, extra = {}) => call(ops(...names), { tool_name: DTOOL, ...extra });

test('S16 (decisions_write): a subagent ack, answer, delegate or escalate is denied with the decisions message, on both entry names', () => {
  for (const tool_name of [DTOOL, DDIRECTOR_TOOL]) {
    for (const op of ['ack', 'answer', 'delegate', 'escalate']) {
      assert.deepEqual(exec(call(ops(op), { tool_name })).out, DDENY, `${tool_name} ${op}`);
    }
  }
});

test('S17 (decisions_write): raise, withdraw and raise+withdraw give {}', () => {
  for (const names of [['raise'], ['withdraw'], ['raise', 'withdraw']]) assert.deepEqual(exec(dcall(names)).out, {}, names.join('+'));
});

test('S18 (decisions_write): raise mixed with a denied op is denied', () => {
  assert.deepEqual(exec(dcall(['raise', 'answer'])).out, DDENY);
  assert.deepEqual(exec(dcall(['withdraw', 'escalate', 'raise'])).out, DDENY);
});

test('S19 (decisions_write): without agent_id the result is {}; the runs_write-only ops are not denied on decisions_write', () => {
  for (const op of ['ack', 'answer', 'delegate', 'escalate']) {
    assert.deepEqual(exec({ tool_name: DTOOL, tool_input: { ops: ops(op) } }).out, {}, op);
    assert.deepEqual(exec(dcall([op], { agent_id: '' })).out, {}, `empty ${op}`);
  }
  for (const op of ['pause', 'resume', 'reissue']) assert.deepEqual(exec(dcall([op])).out, {}, op);
});
