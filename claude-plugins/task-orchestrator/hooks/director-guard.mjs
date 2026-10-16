#!/usr/bin/env node
// Director Guard Hook - PreToolUse on Skill|Read|Edit|Write|Bash|Agent|Grep|Glob|NotebookEdit. Two jobs:
//
// 1. Marker lifecycle. A Skill call naming the director skill (`director` or
//    `task-orchestrator:director`) from the main session (non-empty `session_id`, no `agent_id`)
//    writes the per-session director marker at `directorMarkerPath(session_id)`; the same call
//    with `args` `off` removes it. A skill body cannot see its session id, the hook can, so the
//    marker is written here.
// 2. Altitude rule. While the marker exists, the director session may not open files or run
//    foreground work: Read/Edit/Write/Grep/Glob/NotebookEdit are denied, Bash is denied unless
//    every segment of the command starts with a DIRECTOR_BASH_ALLOW entry (a `git log` segment
//    may not print patches or write a file; a `git branch` segment may only list), and Agent is denied unless
//    `run_in_background === true`. SendMessage and ListAgents are never denied, a call carrying
//    `agent_id` (a subagent) is exempt, and a headless ralph iteration is inert.
//
// Fail closed under the marker: a throw while the marker exists yields a deny (hooks-config also
// sets `onFailure: block` for a crash or timeout). Without the marker the hook is fail-open (`{}`).

import { rmSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readHookInput } from './lib/stdin.mjs';
import { emit, denyOutput } from './lib/emit.mjs';
import { writeMarker } from './lib/markers.mjs';
import { directorMarkerPath, isDirectorSession, isHeadlessIteration, isSubagentInvocation } from './execution-mode.mjs';

/** Skill names that enter (or, with `args` `off`, leave) director mode. */
export const DIRECTOR_SKILL_NAMES = Object.freeze(['director', 'task-orchestrator:director']);

/** Read-only commands a director session may run in Bash. Each command segment must start with one. */
export const DIRECTOR_BASH_ALLOW = Object.freeze(['git status', 'git log', 'git branch', 'gh pr view', 'gh pr list', 'docker ps']);

/** Every deny reason starts with this. */
export const REASON_PREFIX = 'Director altitude rule:';

const NEVER_DENIED = new Set(['SendMessage', 'ListAgents']);
const FILE_TOOLS = new Set(['Read', 'Edit', 'Write', 'Grep', 'Glob', 'NotebookEdit']);

const REMEDY =
  'Delegate the work through a background `Agent` (`run_in_background: true`) or `SendMessage` to a coordinator, or leave director mode with `/task-orchestrator:director off`.';

const isPlainObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const toolInputOf = (input) => (isPlainObject(input.tool_input) ? input.tool_input : {});

/** True when `input` is a Skill call whose trimmed `tool_input.skill` names the director skill. */
export function isDirectorSkillCall(input) {
  if (!isPlainObject(input) || input.tool_name !== 'Skill') return false;
  const skill = toolInputOf(input).skill;
  return typeof skill === 'string' && DIRECTOR_SKILL_NAMES.includes(skill.trim());
}

/**
 * True when a director session may run `command`: a string with no backtick, `$(`, `<`, `>` or
 * carriage return, whose every segment (split on `;`, `&&`, `||`, `|`, `&` and newline), trimmed,
 * starts with a DIRECTOR_BASH_ALLOW entry followed by end-of-segment or whitespace. Case-sensitive.
 */
export function bashAllowed(command) {
  if (typeof command !== 'string') return false;
  if (/[`<>\r]/.test(command) || command.includes('$(')) return false;
  const segments = command.split(/&&|\|\||;|\||&|\n/);
  return segments.every((raw) => {
    const seg = raw.trim();
    const entry = DIRECTOR_BASH_ALLOW.find((e) => seg === e || (seg.startsWith(e) && /^\s/.test(seg.slice(e.length))));
    if (entry === undefined) return false;
    const rest = seg.slice(entry.length).trim();
    const tokens = rest === '' ? [] : rest.split(/\s+/);
    if (entry === 'git log') return !tokens.some(gitLogTokenDenied);
    if (entry === 'git branch') return gitBranchListingOnly(tokens);
    return true;
  });
}

/**
 * A `git log` token that prints file contents or writes a file: -p, -u, --patch*, -L*, --output*,
 * --word-diff*, or a single-dash flag cluster containing p, u or L.
 */
function gitLogTokenDenied(token) {
  if (token.startsWith('--')) {
    return token.startsWith('--patch') || token.startsWith('--output') || token.startsWith('--word-diff');
  }
  if (token.startsWith('-L')) return true;
  return token.startsWith('-') && token.length > 1 && /[puL]/.test(token.slice(1));
}

/** Flags that keep `git branch` a listing. */
const GIT_BRANCH_LIST_FLAGS = new Set([
  '-a', '--all', '-r', '--remotes', '-v', '-vv', '--verbose', '--list', '--show-current',
  '--merged', '--no-merged', '--contains', '--no-contains', '--points-at', '--color', '--no-color',
]);

/** Listing flags whose one following operand is allowed. */
const GIT_BRANCH_OPERAND_FLAGS = new Set(['--merged', '--no-merged', '--contains', '--no-contains', '--points-at']);

/** True when every `git branch` token is a listing flag or the one operand right after an operand flag. */
function gitBranchListingOnly(tokens) {
  for (let i = 0; i < tokens.length; i++) {
    if (!GIT_BRANCH_LIST_FLAGS.has(tokens[i])) return false;
    if (GIT_BRANCH_OPERAND_FLAGS.has(tokens[i]) && i + 1 < tokens.length && !tokens[i + 1].startsWith('-')) i++;
  }
  return true;
}

function denyReason(tool, detail) {
  return `${REASON_PREFIX} a director session does not call \`${tool}\` (${detail}). ${REMEDY}`;
}

/**
 * The altitude decision for one PreToolUse input: `null` to allow, or the deny reason.
 * `director` overrides the marker probe (a boolean, or a function of `input`); when omitted the
 * marker at `directorMarkerPath(session_id)` decides.
 */
export function guardDecision(input, { env = process.env, director } = {}) {
  if (!isPlainObject(input)) return null;
  if (isHeadlessIteration(env)) return null;
  if (isSubagentInvocation(input)) return null;
  const tool = input.tool_name;
  if (NEVER_DENIED.has(tool)) return null;
  const inDirector =
    typeof director === 'boolean' ? director : typeof director === 'function' ? director(input) === true : isDirectorSession(input);
  if (!inDirector) return null;
  if (tool === 'Skill') return null;
  if (FILE_TOOLS.has(tool)) return denyReason(tool, 'the director never opens a file');
  if (tool === 'Bash') {
    if (bashAllowed(toolInputOf(input).command)) return null;
    const allowed = DIRECTOR_BASH_ALLOW.map((e) => `\`${e}\``).join(', ');
    return denyReason('Bash', `only ${allowed} run, unchained and without redirection or substitution`);
  }
  if (tool === 'Agent') {
    if (toolInputOf(input).run_in_background === true) return null;
    return denyReason('Agent', 'a foreground dispatch; pass `run_in_background: true`');
  }
  return null;
}

/** Writes or (with `args` `off`) removes the director marker for a main-session director Skill call. */
function applyMarkerLifecycle(input) {
  if (!isDirectorSkillCall(input)) return;
  const sid = input.session_id;
  if (typeof sid !== 'string' || sid === '' || isSubagentInvocation(input)) return;
  const path = directorMarkerPath(sid);
  const args = toolInputOf(input).args;
  if (typeof args === 'string' && args.trim() === 'off') {
    rmSync(path, { force: true });
    return;
  }
  writeMarker(path, { since: new Date().toISOString(), skill: toolInputOf(input).skill.trim() });
}

/** Hook output for one parsed input. */
export function run(input, env = process.env) {
  if (!isPlainObject(input)) return {};
  if (isHeadlessIteration(env)) return {};
  applyMarkerLifecycle(input);
  const reason = guardDecision(input, { env });
  return reason === null ? {} : denyOutput(reason);
}

function main() {
  let input = null;
  let out;
  try {
    input = readHookInput();
    out = run(input);
  } catch {
    const underMarker =
      isPlainObject(input) && !isSubagentInvocation(input) && !isHeadlessIteration(process.env) && isDirectorSession(input);
    out = underMarker ? denyOutput(`${REASON_PREFIX} the guard failed, so the call is refused. ${REMEDY}`) : {};
  }
  emit(out);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
  process.exit(0);
}
