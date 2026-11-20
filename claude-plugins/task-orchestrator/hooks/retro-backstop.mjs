#!/usr/bin/env node
// Stop hook — backstop for retro-trigger.mjs. Fires at the end of every turn.
//
// With REST (an API URL and a project rootId): runs the same evaluation as the trigger (the scope or the
// run boundary, per config), so a run this session opened that closed elsewhere (a subagent, a tool the
// trigger never saw) still gets its retrospective; only runs this session opened or closed count (retro-lib
// ownsRun), apart from the scope boundary's claimed-not-done, orphaned and abandoned groups. A closed run is
// a confirmed boundary, so this may dispatch (after winning the server-side claim or `retro` mark) as well
// as nudge. At the scope boundary the Stop pass is also where a failed or dead dispatch is retried and an
// abandoned scope is picked up (hooks/README.md, "Retrospective firing"). Output is a `block` decision.
//
// Without REST: if this session recorded a successful run close (retro-trigger.mjs) and has not been
// nudged this way yet, block once with a nudge naming the recorded runs. Never marks, never dispatches,
// at most once per session.
//
// Fail-open by design: any error results in a silent `{}` on stdout and exit 0 — this hook must never
// block termination. Loop guard: `stop_hook_active === true` is always allowed immediately.

import { readHookInput } from './lib/stdin.mjs';
import { emitEmpty as writeEmpty, emitBlock as writeBlock } from './lib/emit.mjs';
import {
  parseRetrospectiveConfig,
  restRootId,
  readSessionState,
  writeSessionState,
  evaluateRuns,
  evaluateScopes,
  directiveFor,
  directorConfig,
  dispatchAncestorId,
  buildDispatch,
  buildNudge,
} from './retro-lib.mjs';
import { locateConfig } from './config-locator.mjs';
import { isDirectorSession, isHeadlessIteration } from './execution-mode.mjs';

function emitEmpty() {
  writeEmpty();
  process.exit(0);
}

function emitBlock(text) {
  writeBlock(text);
  process.exit(0);
}

try {
  const hookInput = readHookInput();
  if (!hookInput) emitEmpty();

  // The task-orchestrator-mod plugin owns retrospective handling for this event: step aside first.
  if (hookInput.to_mod_retro === true) emitEmpty();
  if (hookInput.stop_hook_active === true) emitEmpty();
  if (isHeadlessIteration()) emitEmpty();

  const sessionId = hookInput.session_id;

  const located = locateConfig();
  // A director session is always treated as dispatch mode (directorConfig).
  const director = isDirectorSession(hookInput);
  const parsedConfig = parseRetrospectiveConfig(located.text);
  const config = director ? directorConfig(parsedConfig) : parsedConfig;
  if (config.mode === 'off') emitEmpty();

  const state = readSessionState(sessionId);
  const rootId = restRootId(located);

  if (rootId) {
    const scope = config.boundary === 'scope';
    const result = scope ? await evaluateScopes({ rootId, config, state }) : await evaluateRuns({ rootId, config, state });
    const text = directiveFor(result, located, { director, transcriptPath: hookInput.transcript_path });
    if (scope || result.nudge.length > 0) writeSessionState(sessionId, state);
    if (text) emitBlock(text);
    emitEmpty();
  }

  if (!state.sawClose || state.backstopNudged) emitEmpty();
  state.backstopNudged = true;
  writeSessionState(sessionId, state);
  emitBlock(director ? buildDispatch(state.closes, dispatchAncestorId(located), undefined, { transcriptPath: hookInput.transcript_path, director: true }) : buildNudge(state.closes, { transcriptPath: hookInput.transcript_path }));
} catch {
  emitEmpty();
}
