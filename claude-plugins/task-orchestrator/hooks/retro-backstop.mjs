#!/usr/bin/env node
// Stop hook — backstop for retro-trigger.mjs. Fires at the end of every turn; if a prior
// PostToolUse call saw an item reach terminal (LONE_TERMINAL) but no PARENT_COMPLETION ever
// arrived to turn it into a nudge/dispatch, this hook escalates it once the turn ends, so a run
// that finishes on a lone terminal item (no cascade, no complete_tree) still gets surfaced.
//
// Fail-open by design: any read/parse error, missing config, or unrecognized marker state
// results in a silent `{}` on stdout and exit 0 — this hook must never block termination.
//
// Loop guard: `stop_hook_active === true` means Claude Code is already re-invoking Stop hooks
// after a prior `block` decision from this same turn-end — always allow immediately. Every
// blocking path also clears `sawTerminal` and stamps `handledAt`, so the very next Stop (whether
// it arrives via stop_hook_active or a fresh turn) is guaranteed to fall through to silent exit.

import { readFileSync } from 'fs';
import {
  findConfigContent,
  parseRetrospectiveMode,
  parseProjectRootId,
  markerPath,
  readMarker,
  writeMarker,
  COOLDOWN_MS,
  buildNudge,
  buildDispatch,
} from './retro-lib.mjs';

function emitEmpty() {
  process.stdout.write('{}');
  process.exit(0);
}

function emitBlock(text) {
  process.stdout.write(JSON.stringify({ decision: 'block', reason: text }));
  process.exit(0);
}

try {
  let raw = '';
  try {
    raw = readFileSync(0, 'utf-8');
  } catch {
    emitEmpty();
  }

  let hookInput;
  try {
    hookInput = JSON.parse(raw);
  } catch {
    emitEmpty();
  }

  if (hookInput.stop_hook_active === true) emitEmpty();

  const sessionId = hookInput.session_id;

  const configContent = findConfigContent();
  const mode = parseRetrospectiveMode(configContent);
  if (mode === 'off') emitEmpty();

  const rootId = parseProjectRootId(configContent);
  const key = rootId || sessionId || 'unknown';
  const path = markerPath(key);
  const marker = readMarker(path);
  const now = Date.now();

  if (!marker.sawTerminal) emitEmpty();

  if (marker.handledAt && (now - marker.handledAt <= COOLDOWN_MS)) emitEmpty();

  const roots = (marker.pendingRoots && marker.pendingRoots.length)
    ? marker.pendingRoots
    : (rootId ? [rootId] : []);

  writeMarker(path, {
    ...marker,
    handledAt: now,
    sawTerminal: false,
    pendingRoots: [],
  });

  const text = mode === 'dispatch' ? buildDispatch(roots, rootId) : buildNudge(roots);
  emitBlock(text);
} catch {
  emitEmpty();
}
