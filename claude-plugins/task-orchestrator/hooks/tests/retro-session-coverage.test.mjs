// Retrospective dispatch label and session folder (item 56094202, scenarios S1-S13 of its test-plan).
// BLIND author: written from the frozen task-scope and test-plan notes, the supplied declarations and the
// existing hook test harness (retro-fake-runs.mjs, retro-director-mark.test.mjs). Oracle (task-scope change
// 1-2): the dispatch directive carries description "retrospective:<run8>" (run8 = first 8 chars of the first
// run id, lower-cased, whole id if shorter, "unknown" for an empty list) right after the general-purpose
// subagent type, and, for a non-empty transcript path ending in the case-sensitive suffix .jsonl,
// ` --session-dir "<dir>"` (backslashes -> /, ONE trailing .jsonl stripped) after the last --run. Without a
// usable transcript path nothing is added. buildNudge is unchanged. The hooks pass hook-input
// transcript_path through and stay fail-open.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { rid, retroConfig, withFixture, runFixture, closePayload, contextOf, markDirector } from './retro-fake-runs.mjs';
import { buildDispatch, buildNudge } from '../retro-lib.mjs';
import { run as watchRun } from '../director-watch.mjs';

const sid = (p) => `${p}-${randomUUID()}`;
const DISPATCH = 'Retrospective dispatch';
const NUDGE = 'Retrospective suggested';
const cfg = (mode = 'dispatch', threshold = 3) => retroConfig({ mode, threshold });
const stopPayload = (session, extra = {}) => ({ session_id: session, ...extra });

const ID1 = '1b056448-3504-462e-82fe-5e4549095427';
const ID2 = 'aaaaaaaa-3504-462e-82fe-5e4549095427';
const ID3 = 'bbbbbbbb-3504-462e-82fe-5e4549095427';
const count = (text, needle) => text.split(needle).length - 1;
const desc = (run8) => `description "retrospective:${run8}"`;
const dirFlag = (dir) => `--session-dir "${dir}"`;

// ---- S1: label and session folder ----------------------------------------------------------------

test('S1: buildDispatch carries description retrospective:<run8> and --session-dir for a .jsonl transcript path', () => {
  const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: 'C:/p/899f5f7e.jsonl' });
  assert.ok(text.includes(desc('1b056448')), text);
  assert.ok(text.includes(dirFlag('C:/p/899f5f7e')), text);
  assert.equal(count(text, '--session-dir'), 1, text);
});

test('S1: the description sits right after the general-purpose subagent type', () => {
  const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: 'C:/p/899f5f7e.jsonl' });
  const at = text.indexOf(desc('1b056448'));
  assert.ok(at > 0, text);
  const before = text.slice(0, at);
  assert.ok(/subagent_type "general-purpose",\s*$/.test(before), JSON.stringify(before.slice(-80)));
});

test('S1: --session-dir comes after the last --run, inside the one invocation', () => {
  const text = buildDispatch([ID2, ID3], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  const lastRun = text.lastIndexOf('--run');
  const flag = text.indexOf('--session-dir');
  assert.ok(lastRun > 0 && flag > lastRun, text);
  assert.ok(text.includes(`--run ${ID3} ${dirFlag('/p/x')}`), text);
});

// ---- S2: retro-trigger.mjs ------------------------------------------------------------------------

test('S2: retro-trigger passes stdin transcript_path: the directive carries the label and --session-dir', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger }) => {
    const res = await trigger(closePayload(sid('s2'), rid(1), { extra: { transcript_path: 'C:/p/899f5f7e.jsonl' } }));
    assert.equal(res.status, 0, res.stderr);
    const text = contextOf(res);
    assert.ok(text?.includes(DISPATCH), res.stdout);
    assert.ok(text.includes(desc('00000000')), text);
    assert.ok(text.includes(dirFlag('C:/p/899f5f7e')), text);
  });
});

test('S2 control: retro-trigger without transcript_path has the label and no --session-dir', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger }) => {
    const text = contextOf(await trigger(closePayload(sid('s2c'), rid(1))));
    assert.ok(text?.includes(desc('00000000')), text);
    assert.ok(!text.includes('--session-dir'), text);
  });
});

test('S2 probe: retro-trigger with a non-string transcript_path (42) still dispatches without --session-dir', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger }) => {
    const res = await trigger(closePayload(sid('s2n'), rid(1), { extra: { transcript_path: 42 } }));
    assert.equal(res.status, 0, res.stderr);
    const text = contextOf(res);
    assert.ok(text?.includes(DISPATCH), res.stdout);
    assert.ok(text.includes(desc('00000000')), text);
    assert.ok(!text.includes('--session-dir'), text);
  });
});

// ---- S3: retro-backstop.mjs, REST path and the no-REST director path ------------------------------

test('S3: retro-backstop REST path carries the label and --session-dir from the Stop input', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ stop }) => {
    const res = await stop(stopPayload(sid('s3r'), { transcript_path: 'C:/p/899f5f7e.jsonl' }));
    assert.equal(res.status, 0, res.stderr);
    assert.equal(res.json?.decision, 'block', res.stdout);
    assert.ok(res.json.reason.includes(DISPATCH), res.json.reason);
    assert.ok(res.json.reason.includes(desc('00000000')), res.json.reason);
    assert.ok(res.json.reason.includes(dirFlag('C:/p/899f5f7e')), res.json.reason);
  });
});

test('S3: retro-backstop no-REST director path (recorded close) carries the label and --session-dir', async () => {
  await withFixture({ config: cfg('nudge'), runs: [] }, async ({ sbx, trigger, stop }) => {
    const session = sid('s3d');
    markDirector(sbx, session);
    await trigger(closePayload(session, rid(1)), { apiUrl: undefined });
    const res = await stop(stopPayload(session, { transcript_path: 'C:/p/899f5f7e.jsonl' }), { apiUrl: undefined });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(res.json?.decision, 'block', res.stdout);
    assert.ok(res.json.reason.includes(DISPATCH), res.json.reason);
    assert.ok(res.json.reason.includes(`--run ${rid(1)}`), res.json.reason);
    assert.ok(res.json.reason.includes(desc('00000000')), res.json.reason);
    assert.ok(res.json.reason.includes(dirFlag('C:/p/899f5f7e')), res.json.reason);
  });
});

test('S3 control: the no-REST director path without transcript_path has the label and no --session-dir', async () => {
  await withFixture({ config: cfg('nudge'), runs: [] }, async ({ sbx, trigger, stop }) => {
    const session = sid('s3c');
    markDirector(sbx, session);
    await trigger(closePayload(session, rid(1)), { apiUrl: undefined });
    const res = await stop(stopPayload(session), { apiUrl: undefined });
    assert.equal(res.json?.decision, 'block', res.stdout);
    assert.ok(res.json.reason.includes(desc('00000000')), res.json.reason);
    assert.ok(!res.json.reason.includes('--session-dir'), res.json.reason);
  });
});

// ---- S4: director-watch run() ---------------------------------------------------------------------

const tracked = [];
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});
function watchOpts() {
  const dir = mkdtempSync(join(tmpdir(), 'to-sesscov-'));
  tracked.push(dir);
  mkdirSync(dir, { recursive: true });
  const statePath = join(dir, 'state.json');
  writeFileSync(statePath, JSON.stringify({ pushed: [], retro: [] }));
  return { director: true, statePath, rest: null, retroMode: 'dispatch' };
}
function getInput(session, runId, extra = {}) {
  const env = { ok: true, data: { runId, status: 'closed', marks: {} }, meta: { reqId: 'k7f3q9ab' } };
  return {
    tool_name: 'mcp__mcp-task-orchestrator__runs_read',
    tool_input: { select: 'get', runId },
    tool_response: { content: [{ type: 'text', text: 'ok' }], structuredContent: env, isError: false },
    session_id: session,
    ...extra,
  };
}
const textOf = (out) => out.hookSpecificOutput.additionalContext;

test('S4: director-watch run() with input.transcript_path carries the label and --session-dir', async () => {
  const out = await watchRun(getInput(sid('s4'), ID1, { transcript_path: 'C:/p/899f5f7e.jsonl' }), {}, watchOpts());
  const text = textOf(out);
  assert.ok(text.includes('--run ' + ID1), text);
  assert.ok(text.includes(desc('1b056448')), text);
  assert.ok(text.includes(dirFlag('C:/p/899f5f7e')), text);
});

test('S4 control: director-watch run() without transcript_path has the label and no --session-dir', async () => {
  const text = textOf(await watchRun(getInput(sid('s4c'), ID1), {}, watchOpts()));
  assert.ok(text.includes(desc('1b056448')), text);
  assert.ok(!text.includes('--session-dir'), text);
});

// ---- S5: merged dispatch keeps the first run's label ----------------------------------------------

test('S5: two runs [B, A] give one description (from B, the first) and one --session-dir', () => {
  const text = buildDispatch([ID3, ID2], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  assert.equal(count(text, 'description "retrospective:'), 1, text);
  assert.ok(text.includes(desc('bbbbbbbb')), text);
  assert.ok(!text.includes(desc('aaaaaaaa')), text);
  assert.equal(count(text, '--session-dir'), 1, text);
});

// ---- S6: absent or unusable transcript path -------------------------------------------------------

test('S6: no transcriptPath -> description present, no --session-dir, equal to "" and 42', () => {
  const none = buildDispatch([ID1], 'root-1');
  assert.ok(none.includes(desc('1b056448')), none);
  assert.ok(!none.includes('--session-dir'), none);
  assert.equal(buildDispatch([ID1], 'root-1', undefined, { transcriptPath: '' }), none);
  assert.equal(buildDispatch([ID1], 'root-1', undefined, { transcriptPath: 42 }), none);
  assert.equal(buildDispatch([ID1], 'root-1', undefined, {}), none);
  assert.equal(buildDispatch([ID1], 'root-1', undefined, { transcriptPath: undefined }), none);
});

test('S6: a transcriptPath makes the text differ from the bare call by exactly the session-dir flag', () => {
  const bare = buildDispatch([ID1], 'root-1');
  const withDir = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  assert.notEqual(withDir, bare);
  assert.equal(withDir.replace(` ${dirFlag('/p/x')}`, ''), bare);
});

// ---- S7: nudge unchanged --------------------------------------------------------------------------

test('S7: buildNudge has no description label and no --session-dir', () => {
  const text = buildNudge([ID1]);
  assert.ok(!text.includes('description "retrospective:'), text);
  assert.ok(!text.includes('--session-dir'), text);
});

test('S7: a nudge-mode hook run with transcript_path nudges without label or --session-dir', async () => {
  await withFixture({ config: cfg('nudge', 3), runs: [runFixture(1, { terminal: 1 })] }, async ({ trigger }) => {
    const res = await trigger(closePayload(sid('s7'), rid(1), { extra: { transcript_path: 'C:/p/899f5f7e.jsonl' } }));
    const text = contextOf(res);
    assert.ok(text?.includes(NUDGE), res.stdout);
    assert.ok(!text.includes(DISPATCH), text);
    assert.ok(!text.includes('description "retrospective:'), text);
    assert.ok(!text.includes('--session-dir'), text);
  });
});

// ---- S8: fail-open --------------------------------------------------------------------------------

test('S8: retro-trigger with malformed, empty or run-less stdin exits 0 with {}', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger }) => {
    for (const input of ['not json {', null, { session_id: sid('s8'), transcript_path: 'C:/p/899f5f7e.jsonl' }]) {
      const res = await trigger(input);
      assert.equal(res.status, 0, res.stderr);
      assert.deepEqual(res.json, {}, JSON.stringify(input) + ' -> ' + res.stdout);
    }
  });
});

test('S8: retro-backstop with malformed stdin exits 0 with {}', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [] }, async ({ stop }) => {
    for (const input of ['not json {', null]) {
      const res = await stop(input);
      assert.equal(res.status, 0, res.stderr);
      assert.deepEqual(res.json, {}, String(input) + ' -> ' + res.stdout);
    }
  });
});

test('S8: director-watch run() on malformed input answers {} and does not throw', async () => {
  const out = await watchRun({ tool_name: 'mcp__mcp-task-orchestrator__runs_read', transcript_path: 'C:/p/x.jsonl' }, {}, watchOpts());
  assert.deepEqual(out, {});
});

// ---- S9-S11: path normalisation ------------------------------------------------------------------

test('S9: a backslash Windows path becomes forward slashes', () => {
  const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: 'C:\\p\\x.jsonl' });
  assert.ok(text.includes(dirFlag('C:/p/x')), text);
  assert.ok(!text.includes('C:\\p'), text);
});

test('S10: only the case-sensitive .jsonl suffix qualifies', () => {
  for (const p of ['/p/x.txt', '/p/x.JSONL', '/p/x.Jsonl', '/p/x', '/p/x.jsonl ', '/p/x.jsonl/y']) {
    const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: p });
    assert.ok(!text.includes('--session-dir'), `${p} -> ${text}`);
    assert.ok(text.includes(desc('1b056448')), text);
  }
});

test('S11: exactly one trailing .jsonl is stripped', () => {
  const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: '/p/x.jsonl.jsonl' });
  assert.ok(text.includes(dirFlag('/p/x.jsonl')), text);
  assert.ok(!text.includes(dirFlag('/p/x')), text);
});

// ---- S12: run8 rule -------------------------------------------------------------------------------

test('S12: an upper-case run id is lower-cased and cut to 8 characters', () => {
  const text = buildDispatch(['ABCDEF12-3504-462E-82FE-5E4549095427'], 'root-1');
  assert.ok(text.includes(desc('abcdef12')), text);
});

test('S12: a short id is used whole; an empty list reads unknown', () => {
  assert.ok(buildDispatch(['abc'], 'root-1').includes(desc('abc')));
  const none = buildDispatch([], 'root-1');
  assert.ok(none.includes(desc('unknown')), none);
  assert.ok(!none.includes('--run'), none);
});

test('S12 probe: an id of exactly 8 characters is whole, one of 9 is cut', () => {
  assert.ok(buildDispatch(['abcdefgh'], 'root-1').includes(desc('abcdefgh')));
  const nine = buildDispatch(['abcdefghi'], 'root-1');
  assert.ok(nine.includes(desc('abcdefgh')), nine);
  assert.ok(!nine.includes('retrospective:abcdefghi'), nine);
});

// ---- S13: existing pins hold with a transcriptPath ------------------------------------------------

test('S13: with a transcriptPath there is still one /session-retrospective and one --run per run', () => {
  for (const ids of [[], [ID1], [ID2, ID3], [ID1, ID2, ID3]]) {
    const text = buildDispatch(ids, 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
    assert.equal(count(text, '/session-retrospective'), 1, text);
    assert.equal(count(text, '--run'), ids.length, text);
    assert.ok(text.includes('ONE retrospective'), text);
  }
});

// ---- probes ---------------------------------------------------------------------------------------

test('probe: a path with spaces stays inside the quotes', () => {
  const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: 'C:\\My Docs\\a b.jsonl' });
  assert.ok(text.includes(dirFlag('C:/My Docs/a b')), text);
});

test('probe: the same call twice gives identical text', () => {
  const a = buildDispatch([ID2, ID3], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  const b = buildDispatch([ID2, ID3], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  assert.equal(a, b);
});

test('probe: a fixCycle map plus transcriptPath keeps both the base-run sentence and the session folder', () => {
  const plain = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  const text = buildDispatch([ID1], 'root-1', { [ID1]: 'r-base-run' }, { transcriptPath: '/p/x.jsonl' });
  assert.ok(!plain.includes('r-base-run'), plain);
  assert.ok(text.includes('r-base-run'), text);
  assert.ok(text.includes(dirFlag('/p/x')), text);
  assert.ok(text.includes(desc('1b056448')), text);
});
