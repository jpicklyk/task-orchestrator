#!/usr/bin/env node
// Stop hook — backstop for retro-trigger.mjs. Fires at the end of every turn; if a prior
// PostToolUse call saw an item reach terminal (LONE_TERMINAL) but no PARENT_COMPLETION ever
// arrived to turn it into a nudge/dispatch, this hook escalates it once the turn ends, so a run
// that finishes on a lone terminal item (no cascade, no complete_tree) still gets surfaced.
//
// Always nudges, never dispatches, in ANY configured mode — it fires on the deferred
// `sawTerminal` flag, which by construction is not a confirmed run boundary (see
// retro-trigger.mjs's LONE_TERMINAL comment: "a single leaf finishing isn't necessarily a run
// boundary"). Escalating that into a hard background dispatch would be the worst-case firing —
// a single housekeeping item finishing could spawn a full retrospective agent.
//
// When there is no identifiable root (pendingRoots empty), this emits a nudge with NO UUID
// argument rather than falling back to the project anchor — a supplied root UUID is treated as
// authoritative scope by /session-retrospective (see SKILL.md), so falling back to the whole
// project root would force the most expensive possible scope for what may be a single
// unrelated item. An unscoped nudge instead lets the skill's own fallback scan apply, which is
// narrower (rootId-scoped, discards items older than 24h).
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
  parseRetrospectiveConfig,
  parseProjectRootId,
  markerPath,
  readMarker,
  writeMarker,
  buildNudge,
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
  const config = parseRetrospectiveConfig(configContent);
  if (config.mode === 'off') emitEmpty();

  const cooldownMs = config.cooldownMinutes * 60 * 1000;

  const rootId = parseProjectRootId(configContent);
  const key = rootId || sessionId || 'unknown';
  const path = markerPath(key);
  const marker = readMarker(path);
  const now = Date.now();

  if (!marker.sawTerminal) emitEmpty();

  if (marker.handledAt && (now - marker.handledAt <= cooldownMs)) emitEmpty();

  // No project-anchor fallback here — see the header comment. An empty pendingRoots list
  // renders as a bare `/session-retrospective` with no UUID argument (buildNudge in
  // retro-lib.mjs handles the empty-list case cleanly).
  const roots = (marker.pendingRoots && marker.pendingRoots.length) ? marker.pendingRoots : [];

  writeMarker(path, {
    ...marker,
    handledAt: now,
    sawTerminal: false,
    pendingRoots: [],
    terminalCount: 0,
  });

  emitBlock(buildNudge(roots));
} catch {
  emitEmpty();
}
