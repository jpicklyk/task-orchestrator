#!/usr/bin/env node
// SubagentStop hook — detects a failed retrospective. When the main session's retro state holds a
// dispatch claim (a retro-claim or retro-retry this session won), a stopping subagent is checked:
//   - the claim's anchor run carries the `retro` mark: the retrospective finished, the claim is dropped;
//   - no `retro` mark and the subagent is that retrospective (its transcript's first 256 KB hold
//     `/session-retrospective` and the anchor run id): the claim is marked failed, so the next Stop pass
//     retries at once (retro-retry). A session that holds an unfailed claim never takes it over itself: when
//     this hook cannot see the transcript, a later session takes the claim over after 20 minutes.
// Only the scope boundary has claims. Nothing here writes to the server and nothing is printed but `{}`:
// the retry or the give-up happens in retro-backstop.mjs (hooks/README.md, "Retrospective firing").
//
// Fail-open by design: any error leaves the state unchanged and answers `{}` with exit 0.

import { openSync, readSync, closeSync } from 'node:fs';
import { readHookInput } from './lib/stdin.mjs';
import { emitEmpty as writeEmpty } from './lib/emit.mjs';
import { callOp } from './lib/api-client.mjs';
import { parseRetrospectiveConfig, restRootId, readSessionState, writeSessionState } from './retro-lib.mjs';
import { locateConfig } from './config-locator.mjs';
import { isHeadlessIteration } from './execution-mode.mjs';

const CALL_TIMEOUT_MS = 1500;
const TRANSCRIPT_HEAD_BYTES = 256 * 1024;

function emitEmpty() {
  writeEmpty();
  process.exit(0);
}

function isPlain(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

/** The first 256 KB of the transcript as text, or '' when unreadable. */
function transcriptHead(path) {
  if (typeof path !== 'string' || path === '') return '';
  let fd;
  try {
    fd = openSync(path, 'r');
    const buf = Buffer.alloc(TRANSCRIPT_HEAD_BYTES);
    const n = readSync(fd, buf, 0, TRANSCRIPT_HEAD_BYTES, 0);
    return buf.subarray(0, n).toString('utf-8');
  } catch {
    return '';
  } finally {
    if (fd !== undefined) {
      try {
        closeSync(fd);
      } catch {
        // ignore
      }
    }
  }
}

try {
  const hookInput = readHookInput();
  if (!hookInput) emitEmpty();
  if (hookInput.to_mod_retro === true) emitEmpty();
  if (isHeadlessIteration()) emitEmpty();

  const located = locateConfig();
  const config = parseRetrospectiveConfig(located.text);
  if (config.mode === 'off' || config.boundary !== 'scope') emitEmpty();
  if (!restRootId(located)) emitEmpty();

  const sessionId = hookInput.session_id;
  const state = readSessionState(sessionId);
  if (!state.claims.some((c) => !c.failed)) emitEmpty();

  let head; // read lazily: only a claim whose retrospective has not finished needs the transcript
  let changed = false;
  const kept = [];
  // The claim reads run in parallel (a hung server costs one timeout, not one per claim); a failed claim is not read.
  const views = await Promise.all(
    state.claims.map(async (claim) => {
      if (claim.failed) return null;
      const res = await callOp('runs_read', { select: 'get', runId: claim.anchor }, { timeoutMs: CALL_TIMEOUT_MS });
      return res && res.ok === true && isPlain(res.data) ? (isPlain(res.data.run) ? res.data.run : res.data) : null;
    }),
  );
  for (const [i, claim] of state.claims.entries()) {
    if (claim.failed) {
      kept.push(claim);
      continue;
    }
    const view = views[i];
    if (!view) {
      kept.push(claim); // error: state unchanged
      continue;
    }
    if (isPlain(view.marks) && Object.prototype.hasOwnProperty.call(view.marks, 'retro')) {
      changed = true; // finished: drop the claim
      continue;
    }
    if (head === undefined) head = transcriptHead(hookInput.agent_transcript_path);
    if (head.includes('/session-retrospective') && head.includes(claim.anchor)) {
      changed = true;
      kept.push({ ...claim, failed: true });
    } else {
      kept.push(claim);
    }
  }
  if (changed) {
    state.claims = kept;
    writeSessionState(sessionId, state);
  }
  emitEmpty();
} catch {
  emitEmpty();
}
