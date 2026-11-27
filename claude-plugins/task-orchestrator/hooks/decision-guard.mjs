#!/usr/bin/env node
// PreToolUse on runs_write and decisions_write (any MCP entry whose name contains task-orchestrator) - the decision guard.
// A subagent (hook input carries a non-empty string agent_id) never answers, acknowledges, pauses,
// resumes or reissues (runs_write), nor acknowledges, answers, delegates or escalates (decisions_write):
// the call is denied when any ops[] element has an op exactly in the set for the tool_name. A name ending
// __decisions_write uses the decisions set; any other name keeps the runs_write set. raise and withdraw stay allowed.
// Anything else, malformed input included, is allowed ({}): the server is the authority and this is a
// guard rail, so it fails open.

import { isSubagentInvocation } from './execution-mode.mjs';
import { readHookInput } from './lib/stdin.mjs';
import { emit, emitEmpty, denyOutput } from './lib/emit.mjs';

const DENIED_OPS = new Set(['answer', 'ack', 'pause', 'resume', 'reissue']);
const DECISIONS_DENIED_OPS = new Set(['ack', 'answer', 'delegate', 'escalate']);

export const DENY_MESSAGE =
  'A subagent never answers, acknowledges, pauses, resumes or reissues. Raise the decision (runs_write raise) or report it in your return; the director or the user answers.';

export const DECISIONS_DENY_MESSAGE =
  'A subagent never answers, acknowledges, delegates or escalates a decision. Raise it (decisions_write raise) or report it in your return; the director or the user answers.';

try {
  const hookInput = readHookInput();
  const ops = hookInput?.tool_input?.ops;
  const toolName = hookInput?.tool_name;
  const decisions = typeof toolName === 'string' && toolName.endsWith('__decisions_write');
  const deniedOps = decisions ? DECISIONS_DENIED_OPS : DENIED_OPS;
  const denied =
    isSubagentInvocation(hookInput) &&
    Array.isArray(ops) &&
    ops.some((el) => el !== null && typeof el === 'object' && typeof el.op === 'string' && deniedOps.has(el.op));
  if (denied) emit(denyOutput(decisions ? DECISIONS_DENY_MESSAGE : DENY_MESSAGE));
  else emitEmpty();
} catch {
  emitEmpty();
}
