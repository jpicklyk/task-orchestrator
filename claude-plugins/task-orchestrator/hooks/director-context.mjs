#!/usr/bin/env node
// Director Context Hook - SessionStart, registered under the `compact` matcher. After a compaction
// a director session loses the skill text, so this hook re-asserts the altitude rule as
// additionalContext. It emits only when the session carries the director marker
// (`isDirectorSession`), is not a headless ralph iteration, and the input has no `agent_id`;
// otherwise `{}`. The hook does not test `source`: the matcher selects `compact`.
// Fail-open: any throw yields `{}`.

import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readHookInput } from './lib/stdin.mjs';
import { emit, contextOutput } from './lib/emit.mjs';
import { isDirectorSession, isHeadlessIteration, isSubagentInvocation } from './execution-mode.mjs';

/** The rule re-injected after compaction in a director session. Two sentences. */
export const DIRECTOR_RULE_TEXT =
  'Director mode is on for this session: the director never opens a file or reads a note body, except a decision request or a run rollup. Delegate every read through a background agent or SendMessage, and re-invoke `/task-orchestrator:director` for the dashboard.';

/** Hook output for one parsed input. */
export function run(input, env = process.env) {
  if (input === null || typeof input !== 'object' || Array.isArray(input)) return {};
  if (isHeadlessIteration(env)) return {};
  if (isSubagentInvocation(input)) return {};
  if (!isDirectorSession(input)) return {};
  return contextOutput('SessionStart', DIRECTOR_RULE_TEXT);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let out = {};
  try {
    out = run(readHookInput());
  } catch {
    out = {};
  }
  emit(out);
  process.exit(0);
}
