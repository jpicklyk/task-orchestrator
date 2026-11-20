#!/usr/bin/env node
// PostToolUse hook for runs_write. Every successful `open` and `close` op is recorded in the per-session
// state (a subagent's calls carry the parent's session_id; a subagent never acts, the main session's
// backstop does): a retrospective is owed only to the session that opened or closed the run (retro-lib
// ownsRun). On a successful `close`, in the main session with REST configured and a project rootId, it
// evaluates what is owed:
//   boundary scope (default): one retrospective per completed scope; evaluateScopes groups the runs, and
//     a group not yet due (its scope still open) yields nothing (hooks/README.md, "Retrospective firing").
//   boundary run: one per closed run this session owns and not marked `retro`; lists the root's recently
//     closed, unmarked runs, reads each run's terminal member count, and either dispatches (mode: dispatch
//     and terminal >= minItems, after winning the server-side `retro` mark: first write wins, so two
//     sessions never both dispatch) or nudges.
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

// Run ids of the `kind` ops ('open' or 'close') whose result at the same index is ok === true; an open
// op carries no runId, so its id comes from the result. Null when there is no such op.
function successfulOps(toolInput, toolResponse, kind) {
  const parsed = parseToolResult(toolResponse?.structuredContent ?? toolResponse);
  if (!parsed) return null;
  const ops = opsOf(toolInput);
  const ids = [];
  let any = false;
  ops.forEach((op, i) => {
    if (op.op !== kind) return;
    const r = parsed.results[i];
    if (!r || r.ok !== true) return;
    any = true;
    const runId = typeof op.runId === 'string' && op.runId ? op.runId : (typeof r.runId === 'string' ? r.runId : null);
    if (runId) ids.push(runId);
  });
  return any ? ids : null;
}

const merge = (prev, ids) => [...new Set([...prev, ...ids])].slice(-MAX_RECORDED);

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

  const opens = successfulOps(hookInput.tool_input, hookInput.tool_response, 'open');
  const closes = successfulOps(hookInput.tool_input, hookInput.tool_response, 'close');
  if (opens === null && closes === null) emitEmpty();

  const sessionId = hookInput.session_id;
  const state = readSessionState(sessionId);
  const rootId = restRootId(located);

  // Record what this session (its main thread or a subagent: both carry its session_id) opened and
  // closed: only those runs owe this session a retrospective (retro-lib ownsRun). An open alone has
  // nothing to evaluate.
  if (opens) state.opened = merge(state.opened, opens);
  if (closes) state.closes = merge(state.closes, closes);
  if (closes === null) {
    writeSessionState(sessionId, state);
    emitEmpty();
  }

  // A subagent never acts, and without REST there is nothing to evaluate: the main session's backstop
  // surfaces the close.
  const scope = config.boundary === 'scope';
  const acts = !isSubagentInvocation(hookInput) && rootId;
  if (scope || !acts) state.sawClose = true;
  writeSessionState(sessionId, state);
  if (!acts) emitEmpty();

  const result = scope ? await evaluateScopes({ rootId, config, state, configuredMode: parsedConfig.mode }) : await evaluateRuns({ rootId, config, state });
  const text = directiveFor(result, located, { director, transcriptPath: hookInput.transcript_path });
  if (scope || result.nudge.length > 0) writeSessionState(sessionId, state);
  if (text) emitContext(text);
  emitEmpty();
} catch {
  emitEmpty();
}
