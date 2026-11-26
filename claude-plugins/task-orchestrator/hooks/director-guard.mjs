#!/usr/bin/env node
// Director Guard Hook - PreToolUse, three registrations. Group 1: the exact tool names Skill, Read, Edit, Write, Bash,
// PowerShell, Monitor, Agent, Grep, Glob, NotebookEdit, Workflow, WebFetch, WebSearch, ReadMcpResourceTool,
// ReadMcpResourceDirTool, Artifact, ArtifactData, ArtifactComments, TaskOutput, LSP and REPL (the Code Mode tool, see job 3). Group 2: the task-orchestrator config, docs_read, items_read, notes_read and runs_read
// tools and the write tools advance, items_write, notes_write, deps_write, docs_write and runs_write. Group 3: every other mcp__ tool (a name without `task-orchestrator`), matched by a negative lookahead.
// Two jobs:
//
// 1. Marker lifecycle. A Skill call naming the director skill (`director` or
//    `task-orchestrator:director`) from the main session (non-empty `session_id`, no `agent_id`)
//    writes the per-session director marker at `directorMarkerPath(session_id)`; the same call
//    with `args` `off` removes it. A skill body cannot see its session id, the hook can, so the
//    marker is written here.
// 2. Altitude rule. While the marker exists, the director session may not open files, run
//    foreground work or drive work: advance, items_write, notes_write, deps_write and docs_write are denied,
//    runs_write is allowed only when every op is in DIRECTOR_RUNS_WRITE_OPS (decision ops and run control),
//    Read is allowed only for a skill's or a plugin's own files (isSkillFile, isPluginFile: instructions and
//    deterministic helpers, not work content), a Bash segment may also be `node` on an installed plugin's script,
//    Edit/Write/Grep/Glob/NotebookEdit are denied, PowerShell and Monitor are
//    denied outright, notes_read and docs_read on any task-orchestrator server run only their metadata selects
//    (DIRECTOR_NOTES_READ_SELECTS, DIRECTOR_DOCS_READ_SELECTS; `get` returns a body and is denied), items_read
//    is allowed only for DIRECTOR_ITEMS_READ_SELECTS, `search` with `snippet: false` and `schema` without
//    `guidance` (the forms that return no body text) or `describe: true`,
//    Workflow is allowed only for `task-orchestrator:implement-wave` with an object `args` carrying
//    `altitudeSafe: true` and no `scriptPath` or `resumeFromRunId`, config is denied, runs_read is
//    allowed only for DIRECTOR_RUNS_READ_SELECTS, WebFetch, WebSearch, the MCP resource readers,
//    Artifact, ArtifactData, ArtifactComments, TaskOutput and LSP are denied, Skill is always allowed (it loads instructions only; each tool a skill calls is checked on its own
//    call), every host MCP tool (a name
//    without `task-orchestrator`) is denied unless its bare name is in DIRECTOR_HOST_MCP_ALLOW, Bash is denied unless
//    every segment starts with a DIRECTOR_BASH_ALLOW entry and, for `git log`, `git status` and
//    `git branch`, every later token is on that command's flag allow-list or is a plain operand
//    (so no patch, diff or file output), and Agent is denied unless `run_in_background` is `true`, the host's every Agent call runs in the
//    background (CLAUDE_CODE_ENTRYPOINT in ASYNC_AGENT_ENTRYPOINTS: the desktop app, whose Agent has no such field and
//    whose host drops it before this hook runs), or the session is in Code Mode (job 3: Code Mode's Agent has no such
//    field and always launches in the background). SendMessage and ListAgents are never denied, a call carrying
//    `agent_id` (a subagent) is exempt, and a headless ralph iteration is inert.
//
// 3. Code Mode marker. A REPL call from the main session (non-empty `session_id`, no `agent_id`, not headless)
//    creates the per-session code-mode marker at `codeModeMarkerPath(session_id)`, with or without the director
//    marker. REPL exists only when Code Mode started, and every in-script Agent call runs inside a REPL call whose
//    PreToolUse fires first. session-start.mjs clears the marker except on compact. The env var that enables Code
//    Mode is never read.
//
// Fail closed under the marker: a throw while the marker exists yields a deny (hooks-config also
// sets `onFailure: block` for a crash or timeout). Without the marker the hook is fail-open (`{}`).

import { rmSync, existsSync, realpathSync } from 'node:fs';
import { resolve, sep, dirname, join, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readHookInput } from './lib/stdin.mjs';
import { emit, denyOutput } from './lib/emit.mjs';
import { writeMarker, createMarkerOnce } from './lib/markers.mjs';
import { directorMarkerPath, codeModeMarkerPath, isDirectorSession, isCodeModeSession, isHeadlessIteration, isSubagentInvocation } from './execution-mode.mjs';

/** Skill names that enter (or, with `args` `off`, leave) director mode. */
export const DIRECTOR_SKILL_NAMES = Object.freeze(['director', 'task-orchestrator:director']);

/** Read-only commands a director session may run in Bash. Each command segment must start with one. */
export const DIRECTOR_BASH_ALLOW = Object.freeze(['git status', 'git log', 'git branch', 'gh pr view', 'gh pr list', 'docker ps']);

/** Every deny reason starts with this. */
export const REASON_PREFIX = 'Director altitude rule:';

/**
 * Bare names (the segment after the last `__`) of host MCP tools a director session may call: the
 * orchestrator placements' session tools, the tier-1 launch profile calls (get_settings, set_session_output_style) and
 * the desktop UI tools. None reads a file, a shell or a screen.
 */
export const DIRECTOR_HOST_MCP_ALLOW = Object.freeze(['start_session', 'send_message', 'update_plan', 'wrap_up', 'mark_chapter', 'get_settings', 'set_session_output_style']);

/** The runs_read selects a director session may use (the director profile set). */
export const DIRECTOR_RUNS_READ_SELECTS = Object.freeze(['get', 'list', 'frontier', 'decisions', 'decision']);

/** The `gh pr view` / `gh pr list` --json fields a director session may request: status and metadata, plus `body`. */
export const GH_PR_JSON_FIELDS = Object.freeze([
  'additions', 'assignees', 'author', 'autoMergeRequest', 'baseRefName', 'baseRefOid', 'body', 'changedFiles',
  'closed', 'closedAt', 'createdAt', 'deletions', 'headRefName', 'headRefOid', 'headRepository',
  'headRepositoryOwner', 'id', 'isCrossRepository', 'isDraft', 'labels', 'maintainerCanModify', 'mergeCommit',
  'mergeStateStatus', 'mergeable', 'mergedAt', 'mergedBy', 'milestone', 'number', 'potentialMergeCommit',
  'projectItems', 'reviewDecision', 'reviewRequests', 'state', 'statusCheckRollup', 'title', 'updatedAt', 'url',
]);

const NEVER_DENIED = new Set(['SendMessage', 'ListAgents']);
/** Tools that fetch or publish page, file, task-output or language-server content; always denied. */
export const CONTENT_TOOLS = Object.freeze([
  'WebFetch', 'WebSearch', 'ReadMcpResourceTool', 'ReadMcpResourceDirTool',
  'Artifact', 'ArtifactData', 'ArtifactComments', 'TaskOutput', 'LSP',
]);
const CONTENT_TOOL_SET = new Set(CONTENT_TOOLS);
const FILE_TOOLS = new Set(['Read', 'Edit', 'Write', 'Grep', 'Glob', 'NotebookEdit']);

/** Tools that run a shell command and return its output. Bash keeps the bashAllowed allow-list; the rest deny. */
export const SHELL_TOOLS = Object.freeze(['Bash', 'PowerShell', 'Monitor']);
const SHELL_TOOL_SET = new Set(SHELL_TOOLS);

/**
 * The items_read selects a director session may always use: the director profile set (get, list, overview, blocked)
 * plus the orchestration selects that return no body text (context: gate state or a health view; next: ranked items;
 * history: transitions; related: ids, titles and reasons). search and schema run only in their metadata form
 * (DIRECTOR_ITEMS_READ_CONDITIONAL).
 */
export const DIRECTOR_ITEMS_READ_SELECTS = Object.freeze(['get', 'list', 'overview', 'blocked', 'context', 'next', 'history', 'related']);

/** items_read selects allowed only in their metadata form: search with `snippet: false`, schema without guidance text. */
const DIRECTOR_ITEMS_READ_CONDITIONAL = Object.freeze({
  search: (ti) => ti.snippet === false,
  schema: (ti) => !(Array.isArray(ti.fields) && ti.fields.includes('guidance')),
});

/** The notes_read and docs_read selects that return metadata only (no body); `get` returns the body and stays denied. */
export const DIRECTOR_NOTES_READ_SELECTS = Object.freeze(['list']);
export const DIRECTOR_DOCS_READ_SELECTS = Object.freeze(['list', 'history', 'links']);

/** The only workflow that honours `altitudeSafe`. */
const ALTITUDE_SAFE_WORKFLOW = 'task-orchestrator:implement-wave';

/**
 * The runs_write ops a director session may send: decision ops and run control. Every other op (open, update, mark,
 * assign, end_seat, raise) drives a run, which is an orchestrator's job.
 */
export const DIRECTOR_RUNS_WRITE_OPS = Object.freeze(['answer', 'ack', 'withdraw', 'pause', 'resume', 'close', 'reissue']);

/** Task Orchestrator write tools a director session never calls: they drive work. runs_write is checked per op. */
export const DIRECTOR_DENIED_WRITE_TOOLS = Object.freeze(['advance', 'items_write', 'notes_write', 'deps_write', 'docs_write']);

const MCP_NAME = /^mcp__.*task-orchestrator.*__(config|docs_read|items_read|notes_read|runs_read|advance|items_write|notes_write|deps_write|docs_write|runs_write)$/;
const HOST_TOKEN = 'task-orchestrator';

const REMEDY =
  'Delegate the work through a background `Agent` (`run_in_background: true`; in Code Mode every `Agent` call is background) or `SendMessage` to the orchestrator of the run, or leave director mode with `/task-orchestrator:director off`.';

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
export function bashAllowed(command, { pluginFile = isPluginFile } = {}) {
  if (typeof command !== 'string') return false;
  if (/[`<>\r${}*?[\]\\]/.test(command)) return false;
  const segments = command.split(/&&|\|\||;|\||&|\n/);
  return segments.every((raw) => {
    const seg = raw.trim();
    // `node <script>`: a plugin's own script gives the director deterministic routing and decisions.
    const nodeCall = /^node\s+(\S+)/.exec(seg);
    // The path must be absolute: the hook resolves a relative one against its own working directory, not the shell's.
    if (nodeCall) {
      const script = stripQuotes(nodeCall[1]);
      return isAbsolute(script) && pluginFile(script);
    }
    const entry = DIRECTOR_BASH_ALLOW.find((e) => seg === e || (seg.startsWith(e) && /^\s/.test(seg.slice(e.length))));
    if (entry === undefined) return false;
    const rest = seg.slice(entry.length).trim();
    const tokens = rest === '' ? [] : rest.split(/\s+/).map(stripQuotes);
    if (entry === 'git log') return tokens.every(gitLogTokenAllowed);
    if (entry === 'git status') return tokens.every(gitStatusTokenAllowed);
    if (entry === 'git branch') return gitBranchListingOnly(tokens);
    if (entry === 'gh pr view') return ghPrTokensAllowed(tokens, false);
    if (entry === 'gh pr list') return ghPrTokensAllowed(tokens, true);
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

/** `gh pr` flags that take one following value, on both `view` and `list`. */
const GH_VALUE_FLAGS = new Set(['-R', '--repo']);
/** `gh pr list` only: value flags and boolean flags (long and short). */
const GH_LIST_VALUE_FLAGS = new Set([
  '-s', '--state', '-L', '--limit', '-B', '--base', '-H', '--head', '-A', '--author', '-a', '--assignee',
  '-l', '--label', '-S', '--search', '--app',
]);
const GH_LIST_BOOL_FLAGS = new Set(['-d', '--draft']);

/** True when a `--json` value is a non-empty comma list whose every field is in GH_PR_JSON_FIELDS. */
function ghJsonFieldsAllowed(value) {
  return value !== '' && value.split(',').every((f) => GH_PR_JSON_FIELDS.includes(f));
}

/**
 * R6, the `gh pr view` / `gh pr list` allow-list: operands, `-R`/`--repo` (one value), `--json` (one
 * value or `=`, every field allowed) and, for list, the listing filters. Any other flag (`-c`,
 * `--comments`, `--web`, `--jq`, `--template`, a bare `--json`) denies.
 */
function ghPrTokensAllowed(tokens, isList) {
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (isOperand(t)) continue;
    const eq = t.indexOf('=');
    const name = eq === -1 ? t : t.slice(0, eq);
    const inline = eq === -1 ? null : t.slice(eq + 1);
    if (name === '--json') {
      if (inline !== null) {
        if (!ghJsonFieldsAllowed(inline)) return false;
        continue;
      }
      const next = tokens[i + 1];
      if (next === undefined || next.startsWith('-') || !ghJsonFieldsAllowed(next)) return false;
      i++;
      continue;
    }
    if (GH_VALUE_FLAGS.has(name) || (isList && GH_LIST_VALUE_FLAGS.has(name))) {
      if (inline !== null) {
        if (!name.startsWith('--') || inline === '') return false;
        continue;
      }
      const next = tokens[i + 1];
      if (next === undefined || next.startsWith('-')) return false;
      i++;
      continue;
    }
    if (isList && inline === null && GH_LIST_BOOL_FLAGS.has(name)) continue;
    return false;
  }
  return true;
}

/** Entrypoints whose Agent tool only launches in the background, so no Agent call there can be a foreground dispatch. */
export const ASYNC_AGENT_ENTRYPOINTS = Object.freeze(['claude-desktop']);

/** True when CLAUDE_CODE_ENTRYPOINT names a host whose every Agent call runs in the background. */
export function isAsyncAgentHost(env) {
  const entry = env?.CLAUDE_CODE_ENTRYPOINT;
  return typeof entry === 'string' && ASYNC_AGENT_ENTRYPOINTS.includes(entry);
}

/**
 * The real path of an existing `filePath` (links and junctions resolved), or null for a missing file, a path with a
 * `..` segment, or an error. Both file checks walk the real path, so a link inside a skill or plugin folder cannot
 * reach a file outside it.
 */
function realFile(filePath, real) {
  if (typeof filePath !== 'string' || filePath === '') return null;
  if (filePath.replace(/\\/g, '/').split('/').includes('..')) return null;
  return real(resolve(filePath));
}

/**
 * True when `filePath` is a file inside a skill: under a `skills` folder, in a sub-folder that holds a `SKILL.md`
 * (that skill's own files, references and scripts). Skill files are instructions, not work content, so a director
 * may read them. Any other path, a `..` segment, a missing file or an error is false (the caller then denies).
 */
export function isSkillFile(filePath, { exists = existsSync, real = realpathSync } = {}) {
  try {
    const abs = realFile(filePath, real);
    if (abs === null) return false;
    const segs = abs.split(sep);
    for (let k = segs.length - 3; k >= 0; k--) {
      if (segs[k] !== 'skills') continue;
      if (exists([...segs.slice(0, k + 2), 'SKILL.md'].join(sep))) return true;
    }
    return false;
  } catch {
    return false;
  }
}

/**
 * True when `filePath` is a file inside an installed plugin: an ancestor folder holds `.claude-plugin/plugin.json`
 * (the desktop copy, the marketplace cache, a synced copy or a `--plugin-dir` checkout). A plugin's scripts are its
 * deterministic routing and decision helpers, so a director may read and run them. A path with `..`, a missing
 * file or an error is false.
 */
export function isPluginFile(filePath, { exists = existsSync, real = realpathSync } = {}) {
  try {
    const abs = realFile(filePath, real);
    if (abs === null) return false;
    let dir = dirname(abs);
    for (let i = 0; i < 32; i++) {
      if (exists(join(dir, '.claude-plugin', 'plugin.json'))) return true;
      const up = dirname(dir);
      if (up === dir) return false;
      dir = up;
    }
    return false;
  } catch {
    return false;
  }
}

function denyReason(tool, detail) {
  return `${REASON_PREFIX} a director session does not call \`${tool}\` (${detail}). ${REMEDY}`;
}

/**
 * The altitude decision for one PreToolUse input: `null` to allow, or the deny reason.
 * `director` overrides the marker probe (a boolean, or a function of `input`); when omitted the
 * marker at `directorMarkerPath(session_id)` decides. `codeMode` does the same for the code-mode marker
 * (a boolean, or a function of `input`) and lets an Agent call without `run_in_background` through.
 */
export function guardDecision(input, { env = process.env, director, codeMode } = {}) {
  if (!isPlainObject(input)) return null;
  if (isHeadlessIteration(env)) return null;
  if (isSubagentInvocation(input)) return null;
  const tool = input.tool_name;
  if (NEVER_DENIED.has(tool)) return null;
  const inDirector =
    typeof director === 'boolean' ? director : typeof director === 'function' ? director(input) === true : isDirectorSession(input);
  if (!inDirector) return null;
  // Skill only loads instructions; every tool a skill then calls is checked here on its own call.
  if (tool === 'Skill') return null;
  if (CONTENT_TOOL_SET.has(tool)) return denyReason(tool, 'a tool that returns or publishes page, file, task-output or language-server content');
  if (tool === 'Read') {
    const fp = toolInputOf(input).file_path;
    if (isSkillFile(fp) || isPluginFile(fp)) return null;
  }
  if (FILE_TOOLS.has(tool)) return denyReason(tool, "the director never opens a file other than a skill's or a plugin's own files");
  if (SHELL_TOOL_SET.has(tool) && tool !== 'Bash') return denyReason(tool, 'a shell command can print any file; only `Bash` with a read-only allow-listed command runs');
  if (tool === 'Bash') {
    if (bashAllowed(toolInputOf(input).command)) return null;
    const allowed = DIRECTOR_BASH_ALLOW.map((e) => `\`${e}\``).join(', ');
    return denyReason('Bash', `every command segment must start with ${allowed}, or be \`node\` on a script inside an installed plugin; no redirection or substitution, with only listing flags on \`git log\`, \`git status\` and \`git branch\``);
  }
  if (tool === 'Agent') {
    if (toolInputOf(input).run_in_background === true) return null;
    // The desktop app's Agent has no run_in_background field (every subagent runs in the background), and the host
    // drops the field before this hook runs, so the input cannot show it; the entrypoint can.
    if (isAsyncAgentHost(env)) return null;
    const inCodeMode =
      typeof codeMode === 'boolean' ? codeMode : typeof codeMode === 'function' ? codeMode(input) === true : isCodeModeSession(input);
    if (inCodeMode) return null;
    return denyReason('Agent', 'a foreground dispatch; pass `run_in_background: true`');
  }
  if (tool === 'Workflow') {
    const ti = toolInputOf(input);
    const resumes = Object.hasOwn(ti, 'scriptPath') || Object.hasOwn(ti, 'resumeFromRunId');
    if (!resumes && ti.name === ALTITUDE_SAFE_WORKFLOW && isPlainObject(ti.args) && ti.args.altitudeSafe === true) return null;
    return denyReason('Workflow', `only \`${ALTITUDE_SAFE_WORKFLOW}\` with an object \`args\` carrying \`altitudeSafe: true\` runs, and never with a \`scriptPath\` or \`resumeFromRunId\` (a resume goes to the run's orchestrator)`);
  }
  const mcp = typeof tool === 'string' ? MCP_NAME.exec(tool) : null;
  if (mcp !== null) {
    if (mcp[1] === 'config') return denyReason(tool, 'config returns rules and config documents');
    if (DIRECTOR_DENIED_WRITE_TOOLS.includes(mcp[1])) {
      if (toolInputOf(input).describe === true) return null;
      return denyReason(tool, 'a write that drives work; the director never drives, it hands work to an orchestrator');
    }
    if (mcp[1] === 'runs_write') {
      const ti = toolInputOf(input);
      if (ti.describe === true) return null;
      const ops = Array.isArray(ti.ops) ? ti.ops : [];
      if (ops.length > 0 && ops.every((o) => isPlainObject(o) && DIRECTOR_RUNS_WRITE_OPS.includes(o.op))) return null;
      return denyReason(tool, `only the ${DIRECTOR_RUNS_WRITE_OPS.map((e) => `\`${e}\``).join(', ')} ops run (decision ops and run control); every op of the call must be one of them`);
    }
    if (mcp[1] === 'runs_read') {
      const ti = toolInputOf(input);
      if (ti.describe === true || (typeof ti.select === 'string' && DIRECTOR_RUNS_READ_SELECTS.includes(ti.select))) return null;
      return denyReason(tool, `only the ${DIRECTOR_RUNS_READ_SELECTS.map((e) => `\`${e}\``).join(', ')} selects run`);
    }
    if (mcp[1] === 'items_read') {
      const ti = toolInputOf(input);
      if (ti.describe === true || (typeof ti.select === 'string' && DIRECTOR_ITEMS_READ_SELECTS.includes(ti.select))) return null;
      const conditional = typeof ti.select === 'string' && Object.hasOwn(DIRECTOR_ITEMS_READ_CONDITIONAL, ti.select) ? DIRECTOR_ITEMS_READ_CONDITIONAL[ti.select] : null;
      if (conditional !== null && conditional(ti)) return null;
      return denyReason(tool, `only the ${DIRECTOR_ITEMS_READ_SELECTS.map((e) => `\`${e}\``).join(', ')} selects run, \`search\` with \`snippet: false\` and \`schema\` without \`guidance\``);
    }
    const metaSelects = mcp[1] === 'notes_read' ? DIRECTOR_NOTES_READ_SELECTS : mcp[1] === 'docs_read' ? DIRECTOR_DOCS_READ_SELECTS : null;
    if (metaSelects !== null) {
      const ti = toolInputOf(input);
      if (ti.describe === true || (typeof ti.select === 'string' && metaSelects.includes(ti.select))) return null;
      return denyReason(tool, `a body read; only the metadata selects ${metaSelects.map((e) => `\`${e}\``).join(', ')} run; a background agent reads a body and returns a short summary`);
    }
    return denyReason(tool, 'a read that can return a note or document body');
  }
  if (typeof tool === 'string' && tool.startsWith('mcp__') && !tool.includes(HOST_TOKEN)) {
    const bare = tool.slice(tool.lastIndexOf('__') + 2);
    if (DIRECTOR_HOST_MCP_ALLOW.includes(bare)) return null;
    return denyReason(tool, 'a host MCP tool can read files, a shell or a screen; only the session and desktop UI tools run');
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

/** Creates the code-mode marker when the main session calls the REPL tool. Fail-open. */
function applyCodeModeMarker(input) {
  if (input.tool_name !== 'REPL') return;
  const sid = input.session_id;
  if (typeof sid !== 'string' || sid === '' || isSubagentInvocation(input)) return;
  createMarkerOnce(codeModeMarkerPath(sid));
}

/** Hook output for one parsed input. */
export function run(input, env = process.env) {
  if (!isPlainObject(input)) return {};
  if (isHeadlessIteration(env)) return {};
  applyMarkerLifecycle(input);
  applyCodeModeMarker(input);
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
