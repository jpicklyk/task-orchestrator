#!/usr/bin/env node
// PostToolUse hook — detects when an implementation run reaches terminal, via advance_item
// (direct transition or cascade) or complete_tree, and surfaces a retrospective nudge or a
// hard dispatch directive, per the `retrospective.mode` config in .taskorchestrator/config.yaml.
//
// Fail-open by design: any read/parse error, missing config, unparseable tool_response, or
// unrecognized tool shape results in a silent `{}` on stdout and exit 0 — this hook must never
// block a tool call.

import { readFileSync } from 'fs';
import {
  findConfigContent,
  parseRetrospectiveMode,
  parseProjectRootId,
  markerPath,
  readMarker,
  writeMarker,
  COOLDOWN_MS,
  extractResponseJson,
  debugCapture,
  buildNudge,
  buildDispatch,
} from './retro-lib.mjs';

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
  const mode = parseRetrospectiveMode(configContent);
  if (mode === 'off') emitEmpty();

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

  if (toolName.endsWith('complete_tree')) {
    const completed = resp?.summary?.completed;
    if (typeof completed === 'number' && completed > 0) {
      const ctRoots = toolInput?.rootId
        ? [toolInput.rootId]
        : (Array.isArray(toolInput?.itemIds) ? toolInput.itemIds : []);
      if (ctRoots.length === 0) {
        // completed>0 but no identifiable root — don't emit a directive with an empty root
        // list (broken text). Record it like a lone-terminal signal so the Stop backstop can
        // still catch it via its own rootId fallback.
        writeMarker(path, {
          ...marker,
          sawTerminal: true,
          lastTerminalAt: now,
          sessionId,
          rootId,
        });
        emitEmpty();
      }
      kind = 'PARENT_COMPLETION';
      roots = ctRoots;
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

    if (cascadeTerminals.length) {
      kind = 'PARENT_COMPLETION';
      roots = cascadeTerminals;
    } else if (loneTerminalCandidates.length) {
      kind = 'LONE_TERMINAL';
      roots = loneTerminalCandidates;
    }
  }

  if (!kind) emitEmpty();

  if (kind === 'PARENT_COMPLETION') {
    const existingRoots = marker.rootUuids || [];
    const newRun =
      !marker.handledAt ||
      (now - marker.handledAt > COOLDOWN_MS) ||
      roots.some(r => !existingRoots.includes(r));

    if (!newRun) emitEmpty();

    writeMarker(path, {
      ...marker,
      handledAt: now,
      rootUuids: [...new Set([...existingRoots, ...roots])],
      sawTerminal: false,
      pendingRoots: [],
      sessionId,
      rootId,
    });

    const text = mode === 'dispatch' ? buildDispatch(roots, rootId) : buildNudge(roots);
    emitContext(text);
  }

  // LONE_TERMINAL — record it so a subsequent cascade/parent completion in this run is
  // recognized, but don't nudge/dispatch yet.
  writeMarker(path, {
    ...marker,
    sawTerminal: true,
    lastTerminalAt: now,
    pendingRoots: [...new Set([...(marker.pendingRoots || []), ...roots])],
    sessionId,
    rootId,
  });
  emitEmpty();
} catch {
  emitEmpty();
}
