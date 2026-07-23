#!/usr/bin/env node
// PostToolUse hook — detects when an implementation run reaches terminal, via advance_item
// (direct transition or cascade) or complete_tree, and surfaces a retrospective nudge or a
// hard dispatch directive, per the `retrospective` config in .taskorchestrator/config.yaml.
//
// Fail-open by design: any read/parse error, missing config, unparseable tool_response, or
// unrecognized tool shape results in a silent `{}` on stdout and exit 0 — this hook must never
// block a tool call.
//
// Substance gate: in `mode: dispatch`, a hard background dispatch only fires once the count of
// items that reached terminal SINCE THE LAST retrospective directive (nudge or dispatch) clears
// `dispatchThreshold` (default 3) — tracked in marker.terminalCount, accumulated across
// LONE_TERMINAL calls and reset to 0 whenever any directive fires. Below threshold still emits
// a nudge, never silence: nothing goes unreported, only the automatic background spawn is
// reserved for substantial runs.

import { readFileSync } from 'fs';
import {
  findConfigContent,
  parseRetrospectiveConfig,
  parseProjectRootId,
  markerPath,
  readMarker,
  writeMarker,
  extractResponseJson,
  debugCapture,
  buildNudge,
  buildDispatch,
} from './retro-lib.mjs';

const MAX_ROOT_UUIDS = 50;

function emitEmpty() {
  process.stdout.write('{}');
  process.exit(0);
}

function emitContext(text) {
  process.stdout.write(JSON.stringify({
    hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: text },
  }));
  process.exit(0);
}

// Keep only the most recent MAX_ROOT_UUIDS entries so the marker file can't grow unbounded
// across a long-running session. `ids` comes from a Set spread, so insertion order (oldest
// first) is preserved — slicing off the tail keeps the newest ones.
function capRootUuids(ids) {
  return ids.length > MAX_ROOT_UUIDS ? ids.slice(ids.length - MAX_ROOT_UUIDS) : ids;
}

try {
  let raw = '';
  try {
    raw = readFileSync(0, 'utf-8');
  } catch {
    emitEmpty();
  }

  debugCapture(raw);

  let hookInput;
  try {
    hookInput = JSON.parse(raw);
  } catch {
    emitEmpty();
  }

  const sessionId = hookInput.session_id;
  const toolName = hookInput.tool_name || '';
  const toolInput = hookInput.tool_input || {};
  const toolResponse = hookInput.tool_response;

  const configContent = findConfigContent();
  const config = parseRetrospectiveConfig(configContent);
  if (config.mode === 'off') emitEmpty();

  const cooldownMs = config.cooldownMinutes * 60 * 1000;

  const rootId = parseProjectRootId(configContent);
  const key = rootId || sessionId || 'unknown';
  const path = markerPath(key);
  const marker = readMarker(path);
  const now = Date.now();

  const resp = extractResponseJson(toolResponse);
  if (resp === null) emitEmpty();

  // Classify the tool call into PARENT_COMPLETION (dispatch/nudge now), LONE_TERMINAL (record
  // and wait — a single leaf finishing isn't necessarily a run boundary), or neither.
  let kind = null;
  let roots = [];
  let thisCallTerminalCount = 0;

  if (toolName.endsWith('complete_tree')) {
    const completed = resp?.summary?.completed;
    if (typeof completed === 'number' && completed > 0) {
      const ctRoots = toolInput?.rootId
        ? [toolInput.rootId]
        : (Array.isArray(toolInput?.itemIds) ? toolInput.itemIds : []);
      if (ctRoots.length === 0) {
        // completed>0 but no identifiable root — don't emit a directive with an empty root
        // list (broken text). Record it like a lone-terminal signal so the Stop backstop can
        // still catch it via its own pendingRoots/rootId fallback. Still counts toward
        // substance so a later identifiable completion isn't under-counted.
        writeMarker(path, {
          ...marker,
          sawTerminal: true,
          lastTerminalAt: now,
          terminalCount: (marker.terminalCount || 0) + completed,
          sessionId,
          rootId,
        });
        emitEmpty();
      }
      kind = 'PARENT_COMPLETION';
      roots = ctRoots;
      thisCallTerminalCount = completed;
    }
  } else if (toolName.endsWith('advance_item')) {
    const results = Array.isArray(resp?.results) ? resp.results : [];

    const cascadeTerminals = [];
    for (const r of results) {
      const events = Array.isArray(r.cascadeEvents) ? r.cascadeEvents : [];
      for (const e of events) {
        if (e.targetRole === 'terminal' && e.applied === true) {
          cascadeTerminals.push(e.itemId);
        }
      }
    }

    // Per-result scoping: a result is a lone-terminal candidate iff it went terminal AND that
    // same result has no non-empty unblockedItems of its own. A batch-wide "any unblocked"
    // check would wrongly drop an unrelated terminal result in the same batch.
    const loneTerminalCandidates = results
      .filter(r => r.newRole === 'terminal' && !(Array.isArray(r.unblockedItems) && r.unblockedItems.length > 0))
      .map(r => r.itemId);

    // Direct terminal results in this same batched call, regardless of unblockedItems — used
    // only for substance counting below, never for `roots` (which stays scoped to cascade
    // parents so the dispatch/nudge text's "what completed" scope is unchanged).
    const directTerminals = results
      .filter(r => r.newRole === 'terminal')
      .map(r => r.itemId);

    if (cascadeTerminals.length) {
      kind = 'PARENT_COMPLETION';
      roots = [...new Set(cascadeTerminals)];
      // Substance count: distinct itemIds reaching terminal in this call — the union of cascade
      // terminals and any direct newRole === 'terminal' results in the same batch (e.g. several
      // children going terminal plus one cascaded parent all count toward substance).
      thisCallTerminalCount = new Set([...cascadeTerminals, ...directTerminals]).size;
    } else if (loneTerminalCandidates.length) {
      kind = 'LONE_TERMINAL';
      roots = loneTerminalCandidates;
    }
  }

  if (!kind) emitEmpty();

  if (kind === 'PARENT_COMPLETION') {
    const existingRoots = marker.rootUuids || [];
    // Deliberately NOT removing this bypass: triggers are event-driven with no retry, so
    // without it a second feature tree completing inside the cooldown window of the first
    // would get no retrospective at all, not merely a late one. config-format.md promises one
    // retrospective per run, not per time window.
    const newRun =
      !marker.handledAt ||
      (now - marker.handledAt > cooldownMs) ||
      roots.some(r => !existingRoots.includes(r));

    if (!newRun) emitEmpty();

    const substance = (marker.terminalCount || 0) + thisCallTerminalCount;

    writeMarker(path, {
      ...marker,
      handledAt: now,
      rootUuids: capRootUuids([...new Set([...existingRoots, ...roots])]),
      sawTerminal: false,
      pendingRoots: [],
      terminalCount: 0,
      sessionId,
      rootId,
    });

    const text = (config.mode === 'dispatch' && substance >= config.dispatchThreshold)
      ? buildDispatch(roots, rootId)
      : buildNudge(roots);
    emitContext(text);
  }

  // LONE_TERMINAL — record it so a subsequent cascade/parent completion in this run is
  // recognized, but don't nudge/dispatch yet. Accumulates toward the substance gate.
  writeMarker(path, {
    ...marker,
    sawTerminal: true,
    lastTerminalAt: now,
    pendingRoots: [...new Set([...(marker.pendingRoots || []), ...roots])],
    terminalCount: (marker.terminalCount || 0) + roots.length,
    sessionId,
    rootId,
  });
  emitEmpty();
} catch {
  emitEmpty();
}
