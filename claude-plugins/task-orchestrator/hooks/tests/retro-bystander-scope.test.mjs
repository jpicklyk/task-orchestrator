// Bystander takeover is bounded by the session start (item 9133c488, scenarios S1-S14 of its test-plan).
// BLIND author: written from the frozen diagnosis (D1-D4) and test-plan notes, the supplied declarations and the
// existing hook harness (retro-fake-runs.mjs, retro-session-coverage.test.mjs). Oracle: the 2026-11-21 ruling
// as frozen in diagnosis D1-D4. An UNOWNED group (this session neither opened nor closed one of its runs) may
// act in a dispatch-configured project only when it is due, not stale, and its "since" (the scope's
// roleChangedAt, or an unscoped group's newest closedAt) is at or after the session start (the first
// timestamp of the session transcript); a first claim also waits the 60 minute hold. A claim in the
// session's own state gives no eligibility. An abandoned group is owner-only. An unreadable session start
// (no transcript_path, missing file, no timestamp) makes an unowned group not eligible. Owned groups are
// unchanged. sessionStartMs(path) is the first parseable timestamp within the first 65536 bytes, else NaN.
// Incident F5 (2026-11-21): four unscoped runs closed 1-4 d ago and one scoped run delivered 2 d ago,
// all unmarked, none of them owned by the fresh session that was handed them.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  rid,
  retroConfig,
  withFixture,
  closePayload,
  contextOf,
  markDirector,
  MIN,
  HOUR,
  DAY,
  ago,
  iid,
  scopeFixture,
  mergeFixtures,
  readRetroState,
  writeRetroState,
} from './retro-fake-runs.mjs';
import { sessionStartMs } from '../retro-lib.mjs';

const NL = String.fromCharCode(10);
const sid = (p) => p + '-' + randomUUID();
const DISPATCH = 'Retrospective dispatch';
const NUDGE = 'Retrospective suggested';
const CFG = (o = {}) => retroConfig({ mode: 'dispatch', boundary: 'scope', ...o });
const count = (text, needle) => text.split(needle).length - 1;
const fixtureOpts = (sf, config) => ({ config, runs: sf.runs, items: sf.items, notes: sf.notes });
const sortedIds = (ids) => [...ids].sort();

/** A transcript file in the sandbox: line 1 {"type":"mode"} (no timestamp), line 2 a user line stamped iso. */
function startedAt(sbx, iso) {
  const path = join(sbx.base, 'started-' + randomUUID() + '.jsonl');
  writeFileSync(path, JSON.stringify({ type: 'mode' }) + NL + JSON.stringify({ type: 'user', timestamp: iso }) + NL, 'utf-8');
  return path;
}
/** T(ms): a transcript whose first timestamp is ms ago. */
const T = (sbx, ms) => startedAt(sbx, ago(ms));
const stopWith = (session, transcriptPath) => ({ session_id: session, ...(transcriptPath === undefined ? {} : { transcript_path: transcriptPath }) });

function assertSilent(res) {
  assert.equal(res.status, 0, res.stderr);
  assert.deepEqual(res.json, {}, res.stdout);
}
function assertNoMarks(fake) {
  assert.equal(fake.markOutcomes.length, 0, JSON.stringify(fake.markOutcomes));
}
/** A dispatch block naming every id; returns the reason. */
function assertDispatch(res, ids) {
  assert.equal(res.status, 0, res.stderr);
  assert.equal(res.json?.decision, 'block', res.stdout);
  const reason = res.json.reason;
  assert.ok(reason.includes(DISPATCH), reason);
  for (const id of ids) assert.ok(reason.includes('--run ' + id), id + ' missing from: ' + reason);
  return reason;
}

/**
 * F5: rid(1..4) unscoped, closed 1-4 days ago, unmarked, each with one terminal seat item and no
 * session-tracking note; rid(5) on a scope that went terminal 2 days ago, one implementation item, no note.
 */
function f5(marks = {}) {
  const parts = [1, 2, 3, 4].map((n) => {
    const f = scopeFixture({ scopeId: iid(910 + n), runs: 1, runStart: n, itemStart: 20 + n, closedMs: n * DAY, openedMs: n * DAY + 3 * HOUR, impl: [{ note: null }] });
    delete f.runs[0].scopeId;
    f.runs[0].seats = f.implIds.map((itemId) => ({ itemId }));
    f.runs[0].marks = { ...marks };
    return f;
  });
  const scoped = scopeFixture({ runs: 1, runStart: 5, itemStart: 30, scopeChangedMs: 2 * DAY, closedMs: 2 * DAY, openedMs: 2 * DAY + 3 * HOUR, impl: [{ note: null }] });
  scoped.runs[0].marks = { ...marks };
  return mergeFixtures(...parts, scoped);
}
const F5_IDS = [1, 2, 3, 4, 5].map(rid);

/** An unscoped one-run group (three clean items, read through run seats) closed closedMs ago. */
function unscoped(closedMs, runStart = 1) {
  const f = scopeFixture({ scopeId: iid(940), runs: 1, runStart, itemStart: 50, closedMs, openedMs: closedMs + 3 * HOUR });
  delete f.runs[0].scopeId;
  f.runs[0].seats = f.implIds.map((itemId) => ({ itemId }));
  return f;
}

// ---- S1 (EXISTING-SURFACE) the incident: a fresh session with an empty marker is handed nothing ---------------

test('S1: fresh session (opened [] closes []), F5 due long before its start: {}, no mark, no claim recorded', async () => {
  const sf = f5();
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
    const s = sid('s1');
    writeRetroState(sbx, s, { opened: [], closes: [] });
    assertSilent(await stop(stopWith(s, T(sbx, HOUR))));
    assertNoMarks(fake);
    assert.deepEqual(readRetroState(sbx, s).claims, []);
  });
});

// ---- S2 (happy) an owner of one of the five gets that one only -----------------------------------------------

test('S2: F5, closes [rid(1)]: one block naming rid(1) and none of rid(2..5)', async () => {
  const sf = f5();
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
    const s = sid('s2');
    writeRetroState(sbx, s, { closes: [rid(1)] });
    const reason = assertDispatch(await stop(stopWith(s, T(sbx, HOUR))), [rid(1)]);
    for (const n of [2, 3, 4, 5]) assert.ok(!reason.includes(rid(n)), rid(n) + ' named in: ' + reason);
    assert.deepEqual(fake.won('retro-claim'), [rid(1)]);
  });
});

// ---- S3, S4 (happy): the session-start boundary ---------------------------------------------------------------

test('S3: scope delivered 2 h ago, bystander started 3 h ago: directive, retro-claim on every run', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
    assertDispatch(await stop(stopWith(sid('s3'), T(sbx, 3 * HOUR))), sf.runIds);
    assert.deepEqual(sortedIds(fake.won('retro-claim')), sortedIds(sf.runIds));
  });
});

test('S3: scope delivered 30 min ago, bystander started 3 h ago: {} (the 60 minute hold), no mark', async () => {
  const sf = scopeFixture({ runs: 2, scopeChangedMs: 30 * MIN });
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
    assertSilent(await stop(stopWith(sid('s3h'), T(sbx, 3 * HOUR))));
    assertNoMarks(fake);
  });
});

test('S3 probe: since == session start is taken; one second before the start is not; one second after is taken', async () => {
  for (const [offsetMs, taken] of [[0, true], [-1000, false], [1000, true]]) {
    const sf = scopeFixture({ runs: 2 });
    const startIso = ago(2 * HOUR);
    sf.items[0].roleChangedAt = new Date(Date.parse(startIso) + offsetMs).toISOString();
    await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
      const res = await stop(stopWith(sid('s3b'), startedAt(sbx, startIso)));
      if (taken) assertDispatch(res, sf.runIds);
      else {
        assertSilent(res);
        assertNoMarks(fake);
      }
    });
  }
});

test('S4: unscoped run closed 2 h ago, bystander started 3 h ago: taken; closed 4 h ago: {} and no mark', async () => {
  const taken = unscoped(2 * HOUR);
  await withFixture(fixtureOpts(taken, CFG()), async ({ sbx, stop, fake }) => {
    assertDispatch(await stop(stopWith(sid('s4'), T(sbx, 3 * HOUR))), taken.runIds);
    assert.deepEqual(fake.won('retro-claim'), taken.runIds);
  });
  const before = unscoped(4 * HOUR);
  await withFixture(fixtureOpts(before, CFG()), async ({ sbx, stop, fake }) => {
    assertSilent(await stop(stopWith(sid('s4b'), T(sbx, 3 * HOUR))));
    assertNoMarks(fake);
  });
});

// ---- S5, S6: dead claims -------------------------------------------------------------------------------------

test('S5: dead claim (retro-claim 25 min, no retro) on a group due before the start, bystander: no retro-retry; retro-retry 21 min: no retro-failed', async () => {
  const dead = scopeFixture({ runs: 2 });
  for (const r of dead.runs) r.marks = { 'retro-claim': ago(25 * MIN) };
  await withFixture(fixtureOpts(dead, CFG()), async ({ sbx, stop, fake }) => {
    assertSilent(await stop(stopWith(sid('s5'), T(sbx, HOUR))));
    assertNoMarks(fake);
    assert.deepEqual(fake.won('retro-retry'), []);
  });
  const twice = scopeFixture({ runs: 2 });
  for (const r of twice.runs) r.marks = { 'retro-claim': ago(45 * MIN), 'retro-retry': ago(21 * MIN) };
  await withFixture(fixtureOpts(twice, CFG()), async ({ sbx, stop, fake }) => {
    assertSilent(await stop(stopWith(sid('s5f'), T(sbx, HOUR))));
    assertNoMarks(fake);
    assert.deepEqual(fake.won('retro-failed'), []);
  });
});

test('S6: dead claim on an eligible group (delivered 2 h ago, started 3 h ago), bystander: retro-retry on every run plus a directive', async () => {
  const sf = scopeFixture({ runs: 2 });
  for (const r of sf.runs) r.marks = { 'retro-claim': ago(25 * MIN) };
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
    assertDispatch(await stop(stopWith(sid('s6'), T(sbx, 3 * HOUR))), sf.runIds);
    assert.deepEqual(sortedIds(fake.won('retro-retry')), sortedIds(sf.runIds));
  });
});

test('S6: second failure (retro-retry 21 min) on an eligible group, bystander: retro-failed on every run and one nudge', async () => {
  const sf = scopeFixture({ runs: 2 });
  for (const r of sf.runs) r.marks = { 'retro-claim': ago(45 * MIN), 'retro-retry': ago(21 * MIN) };
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
    const res = await stop(stopWith(sid('s6f'), T(sbx, 3 * HOUR)));
    assert.equal(res.status, 0, res.stderr);
    assert.equal(res.json?.decision, 'block', res.stdout);
    assert.equal(count(res.json.reason, NUDGE), 1, res.json.reason);
    assert.ok(!res.json.reason.includes(DISPATCH), res.json.reason);
    assert.deepEqual(sortedIds(fake.won('retro-failed')), sortedIds(sf.runIds));
  });
});

// ---- S7: an abandoned scope is owner-only ----------------------------------------------------------------------

test('S7: abandoned scope (work, newest close 8 d), bystander started 30 d ago: {} and no mark; its owner gets the [stale] directive', async () => {
  const sf = scopeFixture({ runs: 2, scopeRole: 'work', closedMs: 8 * DAY, openedMs: 8 * DAY + 3 * HOUR });
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
    assertSilent(await stop(stopWith(sid('s7'), T(sbx, 30 * DAY))));
    assertNoMarks(fake);
    const owner = sid('s7o');
    writeRetroState(sbx, owner, { closes: sf.runIds });
    const reason = assertDispatch(await stop(stopWith(owner, T(sbx, 30 * DAY))), sf.runIds);
    assert.ok(reason.includes('[stale]'), reason);
  });
});

// ---- S8: owned groups are unchanged ----------------------------------------------------------------------------

test('S8: owned (closes [rid(5)]) though due before the start: directive at once; a second Stop with the same start writes no new claim', async () => {
  const sf = scopeFixture({ runs: 2, runStart: 5 });
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
    const s = sid('s8');
    writeRetroState(sbx, s, { closes: [rid(5)] });
    const start = T(sbx, HOUR);
    assertDispatch(await stop(stopWith(s, start)), sf.runIds);
    const writes = fake.markOutcomes.length;
    assert.equal(writes, 2, JSON.stringify(fake.markOutcomes));
    assertSilent(await stop(stopWith(s, start)));
    assert.equal(fake.markOutcomes.length, writes, JSON.stringify(fake.markOutcomes));
  });
});

// ---- S9: an unusable session start ----------------------------------------------------------------------------

test('S9: scope delivered 2 h ago, bystander with no usable start (no path, missing file, no timestamp, non-string path): {}; a usable start then dispatches', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
    const noStamp = join(sbx.base, 'no-timestamp.jsonl');
    writeFileSync(noStamp, JSON.stringify({ type: 'mode' }) + NL + JSON.stringify({ type: 'user' }) + NL, 'utf-8');
    const variants = [undefined, join(sbx.base, 'does-not-exist.jsonl'), noStamp, 42];
    for (const v of variants) {
      assertSilent(await stop({ session_id: sid('s9'), ...(v === undefined ? {} : { transcript_path: v }) }));
    }
    assertNoMarks(fake);
    assertDispatch(await stop(stopWith(sid('s9ok'), T(sbx, 3 * HOUR))), sf.runIds);
  });
});

// ---- S10: nudge mode -------------------------------------------------------------------------------------------

test('S10: nudge mode, eligible unowned group: {} and no mark; its owner is nudged', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, CFG({ mode: 'nudge' })), async ({ sbx, stop, fake }) => {
    assertSilent(await stop(stopWith(sid('s10'), T(sbx, 3 * HOUR))));
    assertNoMarks(fake);
    const owner = sid('s10o');
    writeRetroState(sbx, owner, { closes: sf.runIds });
    const res = await stop(stopWith(owner, T(sbx, 3 * HOUR)));
    assert.equal(res.json?.decision, 'block', res.stdout);
    assert.ok(res.json.reason.includes(NUDGE), res.json.reason);
    assert.ok(!res.json.reason.includes(DISPATCH), res.json.reason);
  });
});

// ---- S11: a director session ---------------------------------------------------------------------------------

test('S11: director in a dispatch project, F5, started 1 h ago: {} and no mark; a director owning rid(1) gets rid(1) only', async () => {
  const sf = f5();
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
    const bystander = sid('s11');
    markDirector(sbx, bystander);
    assertSilent(await stop(stopWith(bystander, T(sbx, HOUR))));
    assertNoMarks(fake);
    const owner = sid('s11o');
    markDirector(sbx, owner);
    writeRetroState(sbx, owner, { closes: [rid(1)] });
    const reason = assertDispatch(await stop(stopWith(owner, T(sbx, HOUR))), [rid(1)]);
    for (const n of [2, 3, 4, 5]) assert.ok(!reason.includes(rid(n)), rid(n) + ' named in: ' + reason);
  });
});

// ---- S12: the live marker of the incident -----------------------------------------------------------------------

test('S12: live marker (a failed claim recorded for rid(1..5), server retro-claim 25 min old), started 1 h ago: {} and no mark', async () => {
  const sf = f5({ 'retro-claim': ago(25 * MIN) });
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, stop, fake }) => {
    const s = sid('s12');
    writeRetroState(sbx, s, {
      closes: [],
      claims: F5_IDS.map((id) => ({ anchor: id, runs: [id], key: 'retro-claim', at: ago(25 * MIN), failed: true })),
    });
    assertSilent(await stop(stopWith(s, T(sbx, HOUR))));
    assertNoMarks(fake);
  });
});

// ---- S13: the close trigger ------------------------------------------------------------------------------------

test('S13: trigger close of an owned rid(9) beside F5, started 1 h ago: the context names rid(9) only', async () => {
  const closed = scopeFixture({ scopeId: iid(950), runs: 1, runStart: 9, itemStart: 60 });
  delete closed.runs[0].scopeId;
  closed.runs[0].seats = closed.implIds.map((itemId) => ({ itemId }));
  const sf = mergeFixtures(f5(), closed);
  await withFixture(fixtureOpts(sf, CFG()), async ({ sbx, trigger, fake }) => {
    const res = await trigger(closePayload(sid('s13'), rid(9), { extra: { transcript_path: T(sbx, HOUR) } }));
    assert.equal(res.status, 0, res.stderr);
    const text = contextOf(res);
    assert.ok(text?.includes(DISPATCH), res.stdout);
    assert.ok(text.includes('--run ' + rid(9)), text);
    for (const n of [1, 2, 3, 4, 5]) assert.ok(!text.includes(rid(n)), rid(n) + ' named in: ' + text);
    assert.deepEqual(fake.won('retro-claim'), [rid(9)]);
  });
});

// ---- S14 (NEW-SURFACE): sessionStartMs -----------------------------------------------------------------------

const tmpDirs = [];
after(() => {
  for (const d of tmpDirs) rmSync(d, { recursive: true, force: true });
});
function transcript(lines) {
  const dir = mkdtempSync(join(tmpdir(), 'to-sessstart-'));
  tmpDirs.push(dir);
  const path = join(dir, 't.jsonl');
  writeFileSync(path, lines.join(NL) + NL, 'utf-8');
  return path;
}
const T0 = '2026-11-21T20:52:19.123Z';
const stamped = (iso) => JSON.stringify({ type: 'user', timestamp: iso });

test('S14: untimestamped and malformed lines before the first timestamp are skipped: the result is Date.parse of it', () => {
  const path = transcript([JSON.stringify({ type: 'mode' }), 'not json {', '', JSON.stringify({ type: 'summary' }), stamped(T0), stamped('2026-11-22T00:00:00.000Z')]);
  assert.equal(sessionStartMs(path), Date.parse(T0));
});

test('S14: an unparsable timestamp before the first good one is skipped', () => {
  const path = transcript([JSON.stringify({ type: 'x', timestamp: 'not-a-date' }), stamped(T0)]);
  assert.equal(sessionStartMs(path), Date.parse(T0));
});

test('S14: about 30 kB of untimestamped lines before the first timestamp is still within the read window', () => {
  const pad = JSON.stringify({ type: 'pad', text: 'a'.repeat(1000) });
  const path = transcript([...Array.from({ length: 30 }, () => pad), stamped(T0)]);
  assert.equal(sessionStartMs(path), Date.parse(T0));
});

test('S14: 42, undefined, null, an empty string and a missing file give NaN', () => {
  for (const v of [42, undefined, null, '', join(tmpdir(), 'to-sessstart-missing-' + randomUUID() + '.jsonl')]) {
    assert.ok(Number.isNaN(sessionStartMs(v)), String(v));
  }
});

test('S14: a transcript with no timestamp at all gives NaN', () => {
  assert.ok(Number.isNaN(sessionStartMs(transcript([JSON.stringify({ type: 'mode' }), JSON.stringify({ type: 'user' })]))));
});

test('S14 probe: a first timestamp past byte 65536 gives NaN', () => {
  const pad = JSON.stringify({ type: 'pad', text: 'a'.repeat(1000) });
  const path = transcript([...Array.from({ length: 70 }, () => pad), stamped(T0)]);
  assert.ok(Number.isNaN(sessionStartMs(path)));
});
