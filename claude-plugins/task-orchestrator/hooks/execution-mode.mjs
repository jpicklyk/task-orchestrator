// execution-mode.mjs — pure helpers for distinguishing a headless ralph iteration from an
// interactive session, and a phase-owner subagent from any other agent type. No side effects,
// no stdin/stdout — importable directly by hooks and by tests.
//
// Signal: `TASK_ORCHESTRATOR_MODE=headless-iteration`, set by scripts/ralph-lib.mjs's
// `buildIterationEnv` and passed to every `claude -p` iteration spawn (initial and resume) by
// scripts/ralph-loop.mjs's `runIteration`. No documented Claude Code hook-input field
// distinguishes `claude -p` from an interactive session, hence the explicit env var.

import { existsSync } from 'node:fs';
import { sessionMarkerPath } from './lib/markers.mjs';

/**
 * True only for an exact, case-sensitive match against the headless-iteration sentinel value.
 * Any other value (including a different case, e.g. `HEADLESS-ITERATION`) or an absent var is
 * interactive.
 */
export function isHeadlessIteration(env = process.env) {
  return env?.TASK_ORCHESTRATOR_MODE === 'headless-iteration';
}

/**
 * True only when hook input carries a non-empty string `agent_id` — the field Claude Code
 * includes only when firing inside a subagent call (absent for the main session).
 */
export function isSubagentInvocation(hookInput) {
  return typeof hookInput?.agent_id === 'string' && hookInput.agent_id !== '';
}

/**
 * Returns `'implementer'`, `'reviewer'`, or `null` for `agentType` — bare (project-local agent
 * definitions) or plugin-qualified (`<plugin>:implementer`, `<plugin>:reviewer`, e.g.
 * `task-orchestrator:implementer`). Does NOT match a type that merely ends with one of those
 * words as a substring of a longer segment (e.g. `task-orchestrator:implementer-helper`) — the
 * segment after the last `:` (or the whole string when there is no `:`) must equal `implementer`
 * or `reviewer` exactly.
 */
export function phaseOwnerSeat(agentType) {
  const match = /(^|:)(implementer|reviewer)$/.exec(agentType ?? '');
  return match ? match[2] : null;
}

/**
 * True when `agentType` names a phase-owner agent — `implementer` or `reviewer`. See
 * `phaseOwnerSeat` for the exact matching rule; this is a boolean view of the same regex.
 */
export function isPhaseOwnerAgentType(agentType) {
  return phaseOwnerSeat(agentType) !== null;
}

/**
 * True when `agentType` names a test-author seat — bare `test-author` or plugin-qualified
 * (`<plugin>:test-author`). Same last-segment matching discipline as `phaseOwnerSeat`: does not
 * match a longer segment that merely ends with `test-author`.
 */
export function isTestAuthorAgentType(agentType) {
  return /(^|:)test-author$/.test(agentType ?? '');
}

/** Name of the per-session marker whose existence marks a director session (written by the director-mode work, P20). */
export const DIRECTOR_MARKER_NAME = 'director';

/** Path of the director marker for `sessionId`: any file at this path puts that session in director mode. */
export function directorMarkerPath(sessionId) {
  return sessionMarkerPath(sessionId, DIRECTOR_MARKER_NAME);
}

/**
 * True iff `hookInput.session_id` is a non-empty string and a file exists at its director marker
 * path. Existence-based: the marker's content is ignored. Never throws; a probe error is false.
 * `exists` / `pathFor` are injectable for tests.
 */
export function isDirectorSession(hookInput, { exists = existsSync, pathFor = directorMarkerPath } = {}) {
  try {
    const sid = hookInput?.session_id;
    if (typeof sid !== 'string' || sid === '') return false;
    return exists(pathFor(sid)) === true;
  } catch {
    return false;
  }
}

/**
 * The one gate every injecting context hook shares. Returns the reason guidance must be withheld,
 * checked in this order, or null for a normal interactive main session:
 * `'headless'` (ralph iteration env) -> `'subagent'` (non-empty `agent_id`) -> `'director'` (marker).
 */
export function suppressionReason(hookInput, env = process.env, opts = {}) {
  if (isHeadlessIteration(env)) return 'headless';
  if (isSubagentInvocation(hookInput)) return 'subagent';
  if (isDirectorSession(hookInput, opts)) return 'director';
  return null;
}
