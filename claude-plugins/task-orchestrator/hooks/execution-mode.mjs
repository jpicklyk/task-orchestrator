// execution-mode.mjs — pure helpers for distinguishing a headless ralph iteration from an
// interactive session, and a phase-owner subagent from any other agent type. No side effects,
// no stdin/stdout — importable directly by hooks and by tests.
//
// Signal: `TASK_ORCHESTRATOR_MODE=headless-iteration`, set by scripts/ralph-lib.mjs's
// `buildIterationEnv` and passed to every `claude -p` iteration spawn (initial and resume) by
// scripts/ralph-loop.mjs's `runIteration`. No documented Claude Code hook-input field
// distinguishes `claude -p` from an interactive session, hence the explicit env var.

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
