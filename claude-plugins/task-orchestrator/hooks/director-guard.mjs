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
//    every segment starts with a DIRECTOR_BASH_ALLOW entry and, for `git log`, `git status` and
//    `git branch`, every later token is on that command's flag allow-list or is a plain operand
//    (so no patch, diff or file output), and Agent is denied unless `run_in_background === true`. SendMessage and ListAgents are never denied, a call carrying
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
 * True when a director session may run `command`: a string with no backtick, `<`, `>`, carriage
 * return, `$`, brace, glob character (`*`, `?`, `[`, `]`) or backslash (R1), whose every segment
 * (split on `;`, `&&`, `||`, `|`, `&` and newline), trimmed, starts with a DIRECTOR_BASH_ALLOW entry
 * followed by end-of-segment or whitespace. Case-sensitive. For `git log`, `git status` and
 * `git branch` each later token has every `'` and `"` deleted (R2) and must then pass that
 * command's flag allow-list (R3, R4, R5).
 */
export function bashAllowed(command) {
  if (typeof command !== 'string') return false;
  if (/[`<>\r${}*?[\]\\]/.test(command)) return false;
  const segments = command.split(/&&|\|\||;|\||&|\n/);
  return segments.every((raw) => {
    const seg = raw.trim();
    const entry = DIRECTOR_BASH_ALLOW.find((e) => seg === e || (seg.startsWith(e) && /^\s/.test(seg.slice(e.length))));
    if (entry === undefined) return false;
    const rest = seg.slice(entry.length).trim();
    const tokens = rest === '' ? [] : rest.split(/\s+/).map(stripQuotes);
    if (entry === 'git log') return tokens.every(gitLogTokenAllowed);
    if (entry === 'git status') return tokens.every(gitStatusTokenAllowed);
    if (entry === 'git branch') return gitBranchListingOnly(tokens);
    return true;
  });
}

/** R2: a token with every `'` and `"` deleted, the form the shell hands to git. */
const stripQuotes = (token) => token.replace(/['"]/g, '');

/** An operand: a normalized token that does not start with `-`. */
const isOperand = (token) => !token.startsWith('-');

/** R3: exact `git log` listing flags. */
const GIT_LOG_FLAGS = new Set([
  '--oneline', '--graph', '--decorate', '--no-decorate', '--all', '--reverse', '--first-parent', '--merges',
  '--no-merges', '--abbrev-commit', '--stat', '--shortstat', '--name-only', '--name-status', '--pretty',
  '-n', '-i', '-E', '-F', '-P', '--',
]);

/** R3: `git log` flag prefixes that carry a value. */
const GIT_LOG_VALUE_PREFIXES = Object.freeze([
  '--format=', '--pretty=', '--decorate=', '--max-count=', '--since=', '--until=', '--after=', '--before=',
  '--author=', '--committer=', '--grep=', '--date=',
]);

/**
 * R3, the `git log` flag allow-list: a token is allowed only when it is an operand, an exact
 * GIT_LOG_FLAGS entry, starts with a GIT_LOG_VALUE_PREFIXES entry, or matches `-<n>` / `-n<n>`.
 * Any other token (a patch, diff, merge-diff or output flag in any spelling) denies the segment.
 * Tokens after `--` are classified the same way, so a path starting with `-` is denied (fail closed).
 */
function gitLogTokenAllowed(token) {
  if (isOperand(token) || GIT_LOG_FLAGS.has(token)) return true;
  if (GIT_LOG_VALUE_PREFIXES.some((prefix) => token.startsWith(prefix))) return true;
  return /^-n?\d+$/.test(token);
}

/** R4, the `git status` flag allow-list. -v, -vv, --verbose and every abbreviation are absent. */
const GIT_STATUS_FLAGS = new Set([
  '-s', '--short', '-b', '--branch', '-sb', '--long', '--porcelain', '--porcelain=v1', '--porcelain=v2',
  '--show-stash', '--ahead-behind', '--no-ahead-behind', '-u', '-uno', '-unormal', '-uall', '--untracked-files',
  '--untracked-files=no', '--untracked-files=normal', '--untracked-files=all', '--ignored', '--',
]);

/** R4: a `git status` token is an operand or an exact GIT_STATUS_FLAGS entry. */
function gitStatusTokenAllowed(token) {
  return isOperand(token) || GIT_STATUS_FLAGS.has(token);
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
    return denyReason('Bash', `only ${allowed} run, unchained and without redirection or substitution, with only listing flags on \`git log\`, \`git status\` and \`git branch\``);
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
