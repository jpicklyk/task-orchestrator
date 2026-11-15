#!/usr/bin/env node
// PreToolUse on runs_write (any MCP entry whose name contains task-orchestrator) - the decision guard.
// A subagent (hook input carries a non-empty string agent_id) never answers, acknowledges, pauses or
// resumes: the call is denied when any ops[] element has op exactly answer, ack, pause or resume.
// Anything else, malformed input included, is allowed ({}): the server is the authority and this is a
// guard rail, so it fails open.

import { isSubagentInvocation } from './execution-mode.mjs';
import { readHookInput } from './lib/stdin.mjs';
import { emit, emitEmpty, denyOutput } from './lib/emit.mjs';

const DENIED_OPS = new Set(['answer', 'ack', 'pause', 'resume']);

export const DENY_MESSAGE =
  'A subagent never answers, acknowledges, pauses or resumes. Raise the decision (runs_write raise) or report it in your return; the director or the user answers.';

try {
  const hookInput = readHookInput();
  const ops = hookInput?.tool_input?.ops;
  const denied =
    isSubagentInvocation(hookInput) &&
    Array.isArray(ops) &&
    ops.some((el) => el !== null && typeof el === 'object' && typeof el.op === 'string' && DENIED_OPS.has(el.op));
  if (denied) emit(denyOutput(DENY_MESSAGE));
  else emitEmpty();
} catch {
  emitEmpty();
}
