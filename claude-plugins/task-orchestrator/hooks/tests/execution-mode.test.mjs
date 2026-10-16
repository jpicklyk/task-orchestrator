// Unit tests for hooks/execution-mode.mjs - pure functions, no subprocess needed.
// Oracles:
//   - S1, S2, S14, S15 (existing surface): item 004d65fd's specification, "Design" section 1.
//   - S3 (item 8cd79bc0 test-plan): task-scope AC2 (director detection) and AC3 (one gate): a director
//     marker is a file at sessionMarkerPath(session_id, 'director'); any content counts; the session id
//     comes from hook stdin; suppressionReason checks headless, then subagent, then director.

import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import {
  isHeadlessIteration,
  isSubagentInvocation,
  isPhaseOwnerAgentType,
  phaseOwnerSeat,
  isTestAuthorAgentType,
  DIRECTOR_MARKER_NAME,
  directorMarkerPath,
  isDirectorSession,
  suppressionReason,
} from '../execution-mode.mjs';
import { sessionMarkerPath } from '../lib/markers.mjs';

// Redirect the marker root (os.tmpdir(), read per call) to a fresh directory so no test touches the
// real temp root.
const TMP_VARS = ['TMPDIR', 'TEMP', 'TMP'];
let saved;
let fakeTmp;
beforeEach(() => {
  saved = {};
  for (const k of TMP_VARS) saved[k] = process.env[k];
  fakeTmp = mkdtempSync(join(tmpdir(), 'to-exec-mode-'));
  for (const k of TMP_VARS) process.env[k] = fakeTmp;
});
afterEach(() => {
  for (const k of TMP_VARS) {
    if (saved[k] === undefined) delete process.env[k];
    else process.env[k] = saved[k];
  }
  rmSync(fakeTmp, { recursive: true, force: true });
});

function plant(sessionId, content = '') {
  const p = directorMarkerPath(sessionId);
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(p, content);
  return p;
}

// ── S1: isHeadlessIteration ───────────────────────────────────────────────────────────────────

test('S1: isHeadlessIteration is true only for an exact match on the sentinel value', () => {
  assert.equal(isHeadlessIteration({ TASK_ORCHESTRATOR_MODE: 'headless-iteration' }), true);
});

test('S1: isHeadlessIteration is false for absent, empty, differently-cased, or other values', () => {
  assert.equal(isHeadlessIteration({}), false);
  assert.equal(isHeadlessIteration({ TASK_ORCHESTRATOR_MODE: '' }), false);
  assert.equal(isHeadlessIteration({ TASK_ORCHESTRATOR_MODE: 'HEADLESS-ITERATION' }), false);
  assert.equal(isHeadlessIteration({ TASK_ORCHESTRATOR_MODE: 'interactive' }), false);
  assert.equal(isHeadlessIteration(undefined), false);
});

// ── isSubagentInvocation ──────────────────────────────────────────────────────────────────────

test('isSubagentInvocation is true only for a non-empty string agent_id', () => {
  assert.equal(isSubagentInvocation({ agent_id: 'sub-1' }), true);
  assert.equal(isSubagentInvocation({ agent_id: '' }), false);
  assert.equal(isSubagentInvocation({}), false);
  assert.equal(isSubagentInvocation({ agent_id: 123 }), false);
  assert.equal(isSubagentInvocation(undefined), false);
});

// ── S2: isPhaseOwnerAgentType ─────────────────────────────────────────────────────────────────

test('S2: isPhaseOwnerAgentType is true for plugin-qualified and bare implementer/reviewer', () => {
  assert.equal(isPhaseOwnerAgentType('task-orchestrator:implementer'), true);
  assert.equal(isPhaseOwnerAgentType('task-orchestrator:reviewer'), true);
  assert.equal(isPhaseOwnerAgentType('implementer'), true);
  assert.equal(isPhaseOwnerAgentType('reviewer'), true);
  assert.equal(isPhaseOwnerAgentType('some-project-plugin:reviewer'), true);
});

test('S2: isPhaseOwnerAgentType is false for other agent types, including near-miss substrings and undefined', () => {
  assert.equal(isPhaseOwnerAgentType('general-purpose'), false);
  assert.equal(isPhaseOwnerAgentType('Explore'), false);
  assert.equal(isPhaseOwnerAgentType('Plan'), false);
  assert.equal(isPhaseOwnerAgentType('claude-code-guide'), false);
  assert.equal(isPhaseOwnerAgentType('task-orchestrator:implementer-helper'), false);
  assert.equal(isPhaseOwnerAgentType(undefined), false);
  assert.equal(isPhaseOwnerAgentType(''), false);
});

// ── S14: phaseOwnerSeat ───────────────────────────────────────────────────────────────────────

test('S14: phaseOwnerSeat returns the right value for bare, qualified, helper, undefined and empty inputs', () => {
  assert.equal(phaseOwnerSeat('implementer'), 'implementer');
  assert.equal(phaseOwnerSeat('reviewer'), 'reviewer');
  assert.equal(phaseOwnerSeat('task-orchestrator:implementer'), 'implementer');
  assert.equal(phaseOwnerSeat('task-orchestrator:reviewer'), 'reviewer');
  assert.equal(phaseOwnerSeat('some-project-plugin:reviewer'), 'reviewer');
  assert.equal(phaseOwnerSeat('task-orchestrator:implementer-helper'), null);
  assert.equal(phaseOwnerSeat('general-purpose'), null);
  assert.equal(phaseOwnerSeat(undefined), null);
  assert.equal(phaseOwnerSeat(''), null);
});

// ── S15: isTestAuthorAgentType ────────────────────────────────────────────────────────────────

test('S15: isTestAuthorAgentType returns the right value for the same kinds of input', () => {
  assert.equal(isTestAuthorAgentType('test-author'), true);
  assert.equal(isTestAuthorAgentType('task-orchestrator:test-author'), true);
  assert.equal(isTestAuthorAgentType('x:test-author'), true);
  assert.equal(isTestAuthorAgentType('task-orchestrator:test-author-helper'), false);
  assert.equal(isTestAuthorAgentType('implementer'), false);
  assert.equal(isTestAuthorAgentType(undefined), false);
  assert.equal(isTestAuthorAgentType(''), false);
});

// ── S3: director marker ───────────────────────────────────────────────────────────────────────

test('S3: DIRECTOR_MARKER_NAME is "director" and directorMarkerPath is the session marker of that name', () => {
  assert.equal(DIRECTOR_MARKER_NAME, 'director');
  assert.equal(directorMarkerPath('sess-1'), sessionMarkerPath('sess-1', 'director'));
  assert.equal(directorMarkerPath('sess-1'), sessionMarkerPath('sess-1', DIRECTOR_MARKER_NAME));
  assert.notEqual(directorMarkerPath('sess-1'), directorMarkerPath('sess-2'));
  assert.ok(directorMarkerPath('sess-1').startsWith(fakeTmp), 'marker lives under the (redirected) temp root');
});

test('S3: isDirectorSession is true when a file exists at the marker path - any content counts', () => {
  for (const content of ['', '{}', 'not json at all', '\u0000\u0001']) {
    const sid = 'dir-' + content.length;
    plant(sid, content);
    assert.equal(isDirectorSession({ session_id: sid }), true, JSON.stringify(content));
  }
});

test('S3: isDirectorSession is false with no marker, and for another session\'s marker', () => {
  assert.equal(isDirectorSession({ session_id: 'nobody' }), false);
  plant('other-session');
  assert.equal(isDirectorSession({ session_id: 'my-session' }), false);
  assert.equal(isDirectorSession({ session_id: 'other-session' }), true);
});

test('S3: isDirectorSession is false for null, undefined, non-object input and for empty / absent / non-string session ids', () => {
  // exists always answers true, so only the session-id check can produce false.
  const opts = { exists: () => true, pathFor: () => 'any-path' };
  for (const input of [null, undefined, 42, 'x', [], {}, { session_id: '' }, { session_id: 7 }, { session_id: null }, { session_id: ['a'] }, { session_id: {} }]) {
    assert.equal(isDirectorSession(input, opts), false, JSON.stringify(input));
  }
  assert.equal(isDirectorSession({ session_id: 'ok' }, opts), true);
});

test('S3: the injected seams are used - pathFor receives the session id, exists receives its result', () => {
  const calls = [];
  const out = isDirectorSession(
    { session_id: 'sess-9' },
    {
      pathFor: (sid) => {
        calls.push(['pathFor', sid]);
        return '/virtual/' + sid;
      },
      exists: (p) => {
        calls.push(['exists', p]);
        return true;
      },
    },
  );
  assert.equal(out, true);
  assert.deepEqual(calls, [['pathFor', 'sess-9'], ['exists', '/virtual/sess-9']]);
  assert.equal(isDirectorSession({ session_id: 'sess-9' }, { pathFor: (s) => s, exists: () => false }), false);
});

test('S3: isDirectorSession never throws, even when a seam throws', () => {
  assert.equal(isDirectorSession({ session_id: 's' }, { exists: () => { throw new Error('boom'); } }), false);
  assert.equal(isDirectorSession({ session_id: 's' }, { pathFor: () => { throw new Error('boom'); } }), false);
});

test('S3 probe: path-like and odd session ids stay inside the marker root and do not alias a different session', () => {
  plant('real-session');
  for (const sid of ['../real-session', 'real-session/..', 'real session', 'REAL-SESSION-2']) {
    assert.equal(isDirectorSession({ session_id: sid }), false, sid);
  }
});

test('S3 probe: replay returns the same answer each time', () => {
  plant('replay');
  for (let i = 0; i < 3; i++) assert.equal(isDirectorSession({ session_id: 'replay' }), true);
  for (let i = 0; i < 3; i++) assert.equal(isDirectorSession({ session_id: 'nobody-replay' }), false);
});

// ── S3: suppressionReason ─────────────────────────────────────────────────────────────────────

const HEADLESS = { TASK_ORCHESTRATOR_MODE: 'headless-iteration' };

test('S3: suppressionReason names each single cause', () => {
  plant('dir-sess');
  assert.equal(suppressionReason({ session_id: 'main-sess' }, HEADLESS), 'headless');
  assert.equal(suppressionReason({ session_id: 'main-sess', agent_id: 'sub-1' }, {}), 'subagent');
  assert.equal(suppressionReason({ session_id: 'dir-sess' }, {}), 'director');
});

test('S3: suppressionReason is null for an ordinary main session', () => {
  assert.equal(suppressionReason({ session_id: 'main-sess' }, {}), null);
  assert.equal(suppressionReason({ session_id: 'main-sess', agent_id: '' }, {}), null);
  assert.equal(suppressionReason({}, {}), null);
});

test('S3: suppressionReason priority is headless, then subagent, then director', () => {
  plant('dir-sess');
  const all = { session_id: 'dir-sess', agent_id: 'sub-1' };
  assert.equal(suppressionReason(all, HEADLESS), 'headless');
  assert.equal(suppressionReason(all, {}), 'subagent');
  assert.equal(suppressionReason({ session_id: 'dir-sess' }, {}), 'director');
  assert.equal(suppressionReason({ session_id: 'dir-sess', agent_id: '' }, {}), 'director');
});

test('S3: null or missing stdin is a main session - not director, not subagent; headless still applies', () => {
  assert.equal(suppressionReason(null, {}), null);
  assert.equal(suppressionReason(undefined, {}), null);
  assert.equal(suppressionReason(null, HEADLESS), 'headless');
  assert.equal(suppressionReason(undefined, HEADLESS), 'headless');
});

test('S3: only the exact headless sentinel suppresses; a non-string agent_id is not a subagent', () => {
  assert.equal(suppressionReason({}, { TASK_ORCHESTRATOR_MODE: 'HEADLESS-ITERATION' }), null);
  assert.equal(suppressionReason({}, { TASK_ORCHESTRATOR_MODE: '' }), null);
  assert.equal(suppressionReason({ agent_id: 123 }, {}), null);
  assert.equal(suppressionReason({ agent_id: null }, {}), null);
});

test('S3: suppressionReason forwards opts to the director check (exists/pathFor seams)', () => {
  assert.equal(suppressionReason({ session_id: 's' }, {}, { exists: () => true, pathFor: () => 'p' }), 'director');
  assert.equal(suppressionReason({ session_id: 's' }, {}, { exists: () => false, pathFor: () => 'p' }), null);
  // headless and subagent win without consulting the director seam
  let consulted = false;
  const spy = { exists: () => { consulted = true; return true; }, pathFor: () => 'p' };
  assert.equal(suppressionReason({ session_id: 's' }, HEADLESS, spy), 'headless');
  assert.equal(suppressionReason({ session_id: 's', agent_id: 'a' }, {}, spy), 'subagent');
  assert.equal(consulted, false);
});

test('S3: suppressionReason defaults env to process.env', () => {
  const before = process.env.TASK_ORCHESTRATOR_MODE;
  try {
    process.env.TASK_ORCHESTRATOR_MODE = 'headless-iteration';
    assert.equal(suppressionReason({ session_id: 'x' }), 'headless');
    delete process.env.TASK_ORCHESTRATOR_MODE;
    assert.equal(suppressionReason({ session_id: 'x' }), null);
  } finally {
    if (before === undefined) delete process.env.TASK_ORCHESTRATOR_MODE;
    else process.env.TASK_ORCHESTRATOR_MODE = before;
  }
});

test('S3 probe: replay of the same suppression decision is stable', () => {
  plant('replay-dir');
  for (let i = 0; i < 3; i++) assert.equal(suppressionReason({ session_id: 'replay-dir' }, {}), 'director');
});