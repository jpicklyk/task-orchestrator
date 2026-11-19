#!/usr/bin/env node
// PostToolUse hook for runs_write. On a successful `close` op it records the close in the per-session
// state (a subagent's close too: it never acts, the main session's backstop does) and, in the main
// session with REST configured and a project rootId, evaluates what is owed:
//   boundary scope (default): one retrospective per completed scope; evaluateScopes groups the runs, and
//     a group not yet due (its scope still open) yields nothing (hooks/README.md, "Retrospective firing").
//   boundary run: one per closed run not marked `retro`; lists the root's recently closed, unmarked runs,
//     reads each run's terminal member count, and either dispatches (mode: dispatch and terminal >=
//     minItems, after winning the server-side `retro` mark: first write wins) or nudges.
//
// Without REST (no API URL, or no project rootId) the hook only records the close; the Stop backstop
// (retro-backstop.mjs) nudges once from it.
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
  evaluateScopes,
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

  // Record every close (the scope boundary's ownership rule reads it). A subagent never acts, and
  // without REST there is nothing to evaluate: the main session's backstop surfaces the close.
  const scope = config.boundary === 'scope';
  const acts = !isSubagentInvocation(hookInput) && rootId;
  if (scope || !acts) {
    state.sawClose = true;
    state.closes = [...new Set([...state.closes, ...closes])].slice(-MAX_RECORDED);
    writeSessionState(sessionId, state);
  }
  if (!acts) emitEmpty();

  const result = scope ? await evaluateScopes({ rootId, config, state }) : await evaluateRuns({ rootId, config, state });
  const text = directiveFor(result, located, { director, transcriptPath: hookInput.transcript_path });
  if (scope || result.nudge.length > 0) writeSessionState(sessionId, state);
  if (text) emitContext(text);
  emitEmpty();
} catch {
  emitEmpty();
}
