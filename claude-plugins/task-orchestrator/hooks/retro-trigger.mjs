#!/usr/bin/env node
// PostToolUse hook for runs_write — a retrospective is owed per closed run that is not marked
// `retro`. On a successful `close` op this hook (main session, REST configured with a project rootId)
// lists the root's recently closed, unmarked runs, reads each run's terminal member count, and either
// dispatches a retrospective (mode: dispatch and terminal >= dispatchThreshold, after winning the
// server-side `retro` mark: first write wins, so two sessions never both dispatch) or nudges.
//
// Without REST (no API URL, or no project rootId) the hook only records the close in the per-session
// marker; the Stop backstop (retro-backstop.mjs) nudges once from it. A subagent never acts: it records
// the close for the main session's backstop.
//
// Fail-open by design: any error, missing config, or unrecognized tool shape results in a silent `{}`
// on stdout and exit 0 — this hook must never block a tool call.

import { readHookInput } from './lib/stdin.mjs';
import { emitEmpty as writeEmpty, emitContext as writeContext } from './lib/emit.mjs';
import { parseToolResult } from './lib/envelope.mjs';
import { opsOf } from './lib/ops.mjs';
import {
  parseRetrospectiveConfig,
  restRootId,
  readSessionState,
  writeSessionState,
  evaluateRuns,
  directiveFor,
  directorConfig,
  debugCapture,
} from './retro-lib.mjs';
import { locateConfig } from './config-locator.mjs';
import { isDirectorSession, isHeadlessIteration, isSubagentInvocation } from './execution-mode.mjs';

const MAX_RECORDED = 50;

function emitEmpty() {
  writeEmpty();
  process.exit(0);
}

function emitContext(text) {
  writeContext('PostToolUse', text);
  process.exit(0);
}

// Run ids of the `close` ops whose result at the same index is ok === true. Null when there is none.
function successfulCloses(toolInput, toolResponse) {
  const parsed = parseToolResult(toolResponse?.structuredContent ?? toolResponse);
  if (!parsed) return null;
  const ops = opsOf(toolInput);
  const ids = [];
  let any = false;
  ops.forEach((op, i) => {
    if (op.op !== 'close') return;
    const r = parsed.results[i];
    if (!r || r.ok !== true) return;
    any = true;
    const runId = typeof op.runId === 'string' && op.runId ? op.runId : (typeof r.runId === 'string' ? r.runId : null);
    if (runId) ids.push(runId);
  });
  return any ? ids : null;
}

try {
  const hookInput = readHookInput();
  // Diagnostics only (RETRO_HOOK_DEBUG=1): the parsed input re-serialized, not the raw bytes.
  debugCapture(hookInput ? JSON.stringify(hookInput) : '');
  if (!hookInput) emitEmpty();

  // The task-orchestrator-mod plugin owns retrospective handling for this event: step aside first.
  if (hookInput.to_mod_retro === true) emitEmpty();
  if (isHeadlessIteration()) emitEmpty();

  const toolName = hookInput.tool_name || '';
  if (!/(^|__)runs_write$/.test(toolName)) emitEmpty();

  const located = locateConfig();
  // A director session is always treated as dispatch mode (directorConfig).
  const director = isDirectorSession(hookInput);
  const parsedConfig = parseRetrospectiveConfig(located.text);
  const config = director ? directorConfig(parsedConfig) : parsedConfig;
  if (config.mode === 'off') emitEmpty();

  const closes = successfulCloses(hookInput.tool_input, hookInput.tool_response);
  if (closes === null) emitEmpty();

  const sessionId = hookInput.session_id;
  const state = readSessionState(sessionId);
  const rootId = restRootId(located);

  // A subagent never acts; without REST there is nothing to evaluate. Either way, record the close so
  // the main session's backstop can surface it.
  if (isSubagentInvocation(hookInput) || !rootId) {
    state.sawClose = true;
    state.closes = [...new Set([...state.closes, ...closes])].slice(-MAX_RECORDED);
    writeSessionState(sessionId, state);
    emitEmpty();
  }

  const result = await evaluateRuns({ rootId, config, state });
  const text = directiveFor(result, located, { director });
  if (result.nudge.length > 0) writeSessionState(sessionId, state);
  if (text) emitContext(text);
  emitEmpty();
} catch {
  emitEmpty();
}
