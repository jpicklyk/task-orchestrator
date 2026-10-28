#!/usr/bin/env node
// Director Watch Hook - PostToolUse on runs_read, director sessions only. Two jobs, both deduped
// per session in code rather than in skill prose:
//
// 1. Decision push. A `runs_read` `decisions` result listing a decision with status exactly
//    `open` that this session has not pushed yet yields one directive: `Decision waiting:` naming
//    each new id once and telling the director to call PushNotification once. The server records
//    a decision but fires nothing, so this is the only push. Pushed ids are kept in the state file.
// 2. Run-ended retrospective. A `runs_read` `get` result (the run view) for a run whose status is
//    `closed` (idle or paused is not done) and that carries no `retro` mark yields the
//    retrospective dispatch directive from retro-lib, once per run per session: a director session
//    is always treated as dispatch mode (`nudge` included; `off` stays silent), because the director
//    must never run a retrospective in its own context. When the
//    REST path is configured (restRootId non-null) retro-trigger / retro-backstop own the mark and
//    the dispatch, so this hook stays silent.
//
// Inert (`{}`) for a headless ralph iteration, a subagent call (`agent_id`), a non-director
// session, a failed tool result, any other select, and on any throw. Never blocks a tool call.

import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readHookInput } from './lib/stdin.mjs';
import { emit, contextOutput } from './lib/emit.mjs';
import { parseToolResult } from './lib/envelope.mjs';
import { sessionMarkerPath, readMarker, writeMarker } from './lib/markers.mjs';
import { isDirectorSession, isHeadlessIteration, isSubagentInvocation } from './execution-mode.mjs';
import { locateConfig } from './config-locator.mjs';
import { parseRetrospectiveConfig, restRootId, dispatchAncestorId, buildDispatch } from './retro-lib.mjs';

/** Per-session marker name of the watch state `{pushed: decisionId[], retro: runId[]}`. */
export const WATCH_STATE_NAME = 'director-watch';

/** Every push directive starts with this. */
export const PUSH_PREFIX = 'Decision waiting:';

const MAX_RECORDED = 200;

const isPlainObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const strings = (v) => (Array.isArray(v) ? v.filter((x) => typeof x === 'string') : []);
const hasOwn = (o, k) => Object.prototype.hasOwnProperty.call(o, k);

function readState(path) {
  const raw = readMarker(path, {});
  return { pushed: strings(raw.pushed), retro: strings(raw.retro) };
}

function writeState(path, state) {
  writeMarker(path, { pushed: state.pushed.slice(-MAX_RECORDED), retro: state.retro.slice(-MAX_RECORDED) });
}

/** The push directive for `ids` (each named once). */
export function buildPush(ids) {
  return (
    `${PUSH_PREFIX} ${ids.join(', ')}. ` +
    `A run is waiting on a decision. Call PushNotification once now so the user sees it, then answer or ` +
    `route it with runs_write (answer) when you can; do not push these ids again.`
  );
}

function decisionsOutput(data, state, statePath) {
  const items = isPlainObject(data) && Array.isArray(data.items) ? data.items : [];
  const fresh = [];
  for (const d of items) {
    if (!isPlainObject(d) || d.status !== 'open') continue;
    if (typeof d.id !== 'string' || d.id === '') continue;
    if (state.pushed.includes(d.id) || fresh.includes(d.id)) continue;
    fresh.push(d.id);
  }
  if (fresh.length === 0) return {};
  state.pushed.push(...fresh);
  writeState(statePath, state);
  return contextOutput('PostToolUse', buildPush(fresh));
}

function runEndedOutput(data, toolInput, state, statePath, opts) {
  if (!isPlainObject(data) || data.status !== 'closed') return {};
  if (isPlainObject(data.marks) && hasOwn(data.marks, 'retro')) return {};
  const runId = typeof data.runId === 'string' && data.runId !== '' ? data.runId : typeof toolInput.runId === 'string' ? toolInput.runId : '';
  if (runId === '') return {};
  let located = null;
  const locate = () => (located ??= locateConfig());
  const rest = hasOwn(opts, 'rest') ? opts.rest : restRootId(locate());
  if (rest !== null && rest !== undefined) return {};
  if (state.retro.includes(runId)) return {};
  const mode = typeof opts.retroMode === 'string' ? opts.retroMode : parseRetrospectiveConfig(locate().text).mode;
  if (mode !== 'nudge' && mode !== 'dispatch') return {};
  const text = buildDispatch([runId], dispatchAncestorId(locate()));
  state.retro.push(runId);
  writeState(statePath, state);
  return contextOutput('PostToolUse', text);
}

/**
 * Hook output for one parsed PostToolUse input. `opts.director` overrides the marker probe,
 * `opts.statePath` the state file, `opts.rest` the REST root (null = no REST), `opts.retroMode`
 * the configured retrospective mode.
 */
export function run(input, env = process.env, opts = {}) {
  try {
    if (!isPlainObject(input)) return {};
    if (isHeadlessIteration(env)) return {};
    if (isSubagentInvocation(input)) return {};
    const o = isPlainObject(opts) ? opts : {};
    const director = typeof o.director === 'boolean' ? o.director : isDirectorSession(input);
    if (!director) return {};
    if (typeof input.tool_name === 'string' && input.tool_name !== '' && !/(^|__)runs_read$/.test(input.tool_name)) return {};
    const parsed = parseToolResult(input.tool_response);
    if (!parsed || parsed.ok !== true) return {};
    const toolInput = isPlainObject(input.tool_input) ? input.tool_input : {};
    const select = toolInput.select;
    if (select !== 'decisions' && select !== 'get') return {};
    const statePath = typeof o.statePath === 'string' && o.statePath !== '' ? o.statePath : sessionMarkerPath(input.session_id, WATCH_STATE_NAME);
    const state = readState(statePath);
    return select === 'decisions' ? decisionsOutput(parsed.data, state, statePath) : runEndedOutput(parsed.data, toolInput, state, statePath, o);
  } catch {
    return {};
  }
}

function main() {
  let out = {};
  try {
    out = run(readHookInput());
  } catch {
    out = {};
  }
  emit(out);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
  process.exit(0);
}
