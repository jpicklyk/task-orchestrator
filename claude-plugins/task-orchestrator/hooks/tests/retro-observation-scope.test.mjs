// Retrospective triage: an agent-observation counts as a signal only when it is linked to the group (item 64735764).
// BLIND author: written from the frozen diagnosis (D1-D6) and test-plan notes, the supplied declarations and the
// existing hook test harness (retro-fake-runs.mjs, retro-session-coverage.test.mjs). Oracle: diagnosis D1-D4 and D6.
// An observation names the group when its title or summary holds, with no ASCII letter or digit on either side, a
// hex mention of 8 to 32 hex characters (or a UUID) that is a prefix match, either way round, of a group id (a run
// id, the scope, an item), or a run label of 8 or more characters (case-insensitive, whole token: no letter, digit,
// '-' or '_' either side). A full page of 200 with more rows is a signal even when no row names the group.
// Scenario ids S1-S16 are those of the test-plan; the extra probes are labelled probe.

import './hermetic-claude-config.mjs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  rid,
  retroConfig,
  withFixture,
  ROOT,
  SCOPE,
  HOUR,
  ago,
  iid,
  scopeFixture,
  writeRetroState,
} from './retro-fake-runs.mjs';
import { observationNamesGroup } from '../retro-lib.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const PLUGIN = resolve(HERE, '..', '..');
const sid = (p) => p + '-' + randomUUID();
const SCOPE_CFG = (o = {}) => retroConfig({ mode: 'dispatch', boundary: 'scope', ...o });
const stopPayload = (session) => ({ session_id: session });
const DISPATCH = 'Retrospective dispatch';
const fixtureOpts = (sf, config, behavior) => ({ config, runs: sf.runs, items: sf.items, notes: sf.notes, behavior });
const CLEAN2 = () => scopeFixture({ runs: 2, impl: [{}, {}] });

const RUN_A = 'a1c0d3bf-5e1d-4c2b-9a7f-3b6e8d2c4f10';
const RUN_A8 = 'a1c0d3bf';
const LABEL = 'r-202611192345-c660971b';

/** The a1c0d3bf shape: one unscoped closed run with a label, no item seats, no members. */
function unscopedRun() {
  return {
    runs: [{ runId: RUN_A, members: 0, terminal: 0, marks: {}, openedAt: ago(5 * HOUR), closedAt: ago(2 * HOUR), label: LABEL }],
    items: [],
    notes: {},
    runIds: [RUN_A],
  };
}
/** An agent-observation row: a depth-0 item (no parent), created an hour ago unless stated. */
const obs = (n, extra = {}) => ({ id: iid(n), type: 'agent-observation', role: 'queue', createdAt: ago(HOUR), ...extra });

function assertSilent(res) {
  assert.equal(res.status, 0, res.stderr);
  assert.deepEqual(res.json, {}, res.stdout);
}
function assertDispatch(res, runIds) {
  assert.equal(res.status, 0, res.stderr);
  assert.equal(res.json?.decision, 'block', res.stdout);
  assert.ok(res.json.reason.includes(DISPATCH), res.json.reason);
  for (const id of runIds) assert.ok(res.json.reason.includes('--run ' + id), id + ' missing from: ' + res.json.reason);
}
/** Skipped: retro-skipped then retro won on every run, retro-claim never. */
function assertSkipped(fake, runIds) {
  for (const id of runIds) {
    const keys = fake.markOutcomes.filter((o) => o.runId === id && o.marked).map((o) => o.key);
    assert.ok(keys.includes('retro-skipped') && keys.includes('retro'), id + ': ' + JSON.stringify(keys));
    assert.ok(keys.indexOf('retro-skipped') < keys.indexOf('retro'), id + ': ' + JSON.stringify(keys));
    assert.ok(!keys.includes('retro-claim'), id + ' claimed: ' + JSON.stringify(keys));
  }
}
/** Dispatched: retro-claim won on every run, nothing skipped. */
function assertDispatched(fake, runIds) {
  assert.deepEqual([...fake.won('retro-claim')].sort(), [...runIds].sort());
  assert.deepEqual(fake.won('retro-skipped'), []);
  assert.deepEqual(fake.won('retro'), []);
}
/** Runs one Stop hook for a session owning runIds; returns the hook answer. */
async function stopOnce(sf, extraOpts, check) {
  await withFixture({ ...fixtureOpts(sf, SCOPE_CFG()), ...extraOpts }, async (fx) => {
    const s = sid('obs');
    writeRetroState(fx.sbx, s, { closes: sf.runIds });
    const res = await fx.stop(stopPayload(s));
    await check(res, fx.fake);
  });
}

// ---- S1, S2, S6 (EXISTING-SURFACE): the a1c0d3bf shape ---------------------------------------------------

test('S1: an unscoped run with no item seats and one observation naming an unrelated id is skipped (retro-skipped, retro), silent', async () => {
  const sf = unscopedRun();
  sf.items.push(obs(700, { title: 'Gate quirk', summary: 'In run 3e7cade3 the gate refused a note' }));
  await stopOnce(sf, {}, async (res, fake) => {
    assertSilent(res);
    assertSkipped(fake, sf.runIds);
    // Vacuity: the observation list was read and the row was in range, so only the link test could skip it.
    const reads = fake.calls('items_read', 'list').filter((q) => q.body?.type === 'agent-observation');
    assert.ok(reads.length >= 1, JSON.stringify(fake.requests.map((q) => q.body)));
  });
});

test('S2 positive control: the same shape with the summary naming the run 8-hex short dispatches', async () => {
  const sf = unscopedRun();
  sf.items.push(obs(700, { title: 'Gate quirk', summary: 'Stuck in run ' + RUN_A8 + ' after the close' }));
  await stopOnce(sf, {}, async (res, fake) => {
    assertDispatch(res, sf.runIds);
    assertDispatched(fake, sf.runIds);
  });
});

test('S6: the summary naming the run label in UPPER case dispatches', async () => {
  const sf = unscopedRun();
  sf.items.push(obs(700, { summary: 'Abandoned wave ' + LABEL.toUpperCase() + ' left work behind' }));
  await stopOnce(sf, {}, async (res, fake) => {
    assertDispatch(res, sf.runIds);
    assertDispatched(fake, sf.runIds);
  });
});

test('S2 probe: the observation list request asks for the summary field, 200 rows, ascending by createdAt, createdAfter set (D1)', async () => {
  const sf = unscopedRun();
  sf.items.push(obs(700, { summary: 'run ' + RUN_A8 }));
  await stopOnce(sf, {}, async (res, fake) => {
    assertDispatch(res, sf.runIds);
    const reads = fake.calls('items_read', 'list').filter((q) => q.body?.type === 'agent-observation');
    assert.equal(reads.length, 1, JSON.stringify(reads.map((q) => q.body)));
    const b = reads[0].body;
    assert.deepEqual(b.fields, ['summary']);
    assert.equal(b.limit, 200);
    assert.equal(b.sort, 'createdAt');
    assert.equal(b.order, 'asc');
    assert.equal(typeof b.createdAfter, 'string');
  });
});

// ---- S3, S4, S5, S7, S8 (EXISTING-SURFACE): a scoped clean 2-item group -----------------------------------

test('S3: a scoped clean 2-item group with one unrelated observation is skipped', async () => {
  const sf = CLEAN2();
  sf.items.push(obs(700, { title: 'Unrelated', summary: 'Nothing to do with these runs, see 3e7cade3' }));
  await stopOnce(sf, {}, async (res, fake) => {
    assertSilent(res);
    assertSkipped(fake, sf.runIds);
  });
});

test('S4: the same group with an observation whose SUMMARY names an implementation item 8-hex short dispatches', async () => {
  const sf = CLEAN2();
  sf.items.push(obs(700, { summary: 'Friction in ' + sf.implIds[0].slice(0, 8) }));
  await stopOnce(sf, {}, async (res, fake) => {
    assertDispatch(res, sf.runIds);
    assertDispatched(fake, sf.runIds);
  });
});

test('S5: the same group with an observation whose TITLE names the scope UUID dispatches', async () => {
  const sf = CLEAN2();
  sf.items.push(obs(700, { title: 'Follow-up on ' + SCOPE }));
  await stopOnce(sf, {}, async (res, fake) => {
    assertDispatch(res, sf.runIds);
    assertDispatched(fake, sf.runIds);
  });
});

test('S7: the same group with 201 unlinked observations dispatches (a full page with more rows is a signal, D4)', async () => {
  const sf = CLEAN2();
  for (let n = 0; n < 201; n++) sf.items.push(obs(1000 + n, { summary: 'Unrelated note number ' + n }));
  await stopOnce(sf, {}, async (res, fake) => {
    assertDispatch(res, sf.runIds);
    assertDispatched(fake, sf.runIds);
    const reads = fake.calls('items_read', 'list').filter((q) => q.body?.type === 'agent-observation');
    assert.equal(reads.length, 1);
    assert.equal(reads[0].body.limit, 200);
  });
});

test('S7 probe: exactly 200 unlinked observations is a complete page, so the group is skipped', async () => {
  const sf = CLEAN2();
  for (let n = 0; n < 200; n++) sf.items.push(obs(1000 + n, { summary: 'Unrelated note number ' + n }));
  await stopOnce(sf, {}, async (res, fake) => {
    assertSilent(res);
    assertSkipped(fake, sf.runIds);
  });
});

test('S8: a naming observation created before the first openedAt is outside the read and the group is skipped', async () => {
  const sf = CLEAN2();
  sf.items.push(obs(700, { createdAt: ago(6 * HOUR), summary: 'Friction in ' + sf.implIds[0].slice(0, 8) }));
  await stopOnce(sf, {}, async (res, fake) => {
    assertSilent(res);
    assertSkipped(fake, sf.runIds);
  });
});

// ---- S9: the observation list fails ------------------------------------------------------------------------

test('S9: the observation list answering http500 on the S2 fixture writes no mark and stays silent', async () => {
  const sf = unscopedRun();
  sf.items.push(obs(700, { summary: 'Stuck in run ' + RUN_A8 }));
  await stopOnce(sf, { behavior: { itemsList: 'http500' } }, async (res, fake) => {
    assertSilent(res);
    assert.equal(fake.markOutcomes.length, 0, JSON.stringify(fake.markOutcomes));
    const reads = fake.calls('items_read', 'list').filter((q) => q.body?.type === 'agent-observation');
    assert.ok(reads.length >= 1, 'the failing list was actually requested');
  });
});

// ---- S10-S14 (NEW-SURFACE): observationNamesGroup -----------------------------------------------------------

const named = (text, ids, labels = []) => observationNamesGroup({ title: 'x', summary: text }, ids, labels);

test('S10: an 8-hex mention names a full-UUID id; a UUID mention names an 8-hex id; upper case names it too', () => {
  assert.equal(named('see ' + RUN_A8, [RUN_A]), true);
  assert.equal(named('see ' + RUN_A, [RUN_A8]), true);
  assert.equal(named('see ' + RUN_A8.toUpperCase(), [RUN_A]), true);
  assert.equal(named('see ' + RUN_A.toUpperCase(), [RUN_A8]), true);
});

test('S11: a mention glued to a letter or digit does not name the id; a trailing -a1 or . does; 7 hex characters do not', () => {
  assert.equal(named('x' + RUN_A8, [RUN_A]), false);
  assert.equal(named(RUN_A8 + '9z', [RUN_A]), false);
  assert.equal(named('9' + RUN_A8, [RUN_A]), false);
  assert.equal(named(RUN_A8 + '-a1', [RUN_A]), true);
  assert.equal(named('closed ' + RUN_A8 + '.', [RUN_A]), true);
  assert.equal(named('(' + RUN_A8 + ')', [RUN_A]), true);
  assert.equal(named('see ' + RUN_A8.slice(0, 7), [RUN_A]), false);
});

test('S17: a mention names the id after a dashed fragment (hex or date) or after a dashed prefix of a UUID; letters adjacent still do not', () => {
  assert.equal(named('bad-' + RUN_A8, [RUN_A]), true);
  assert.equal(named('2026-11-21-' + RUN_A8, [RUN_A]), true);
  assert.equal(named('ab-' + RUN_A, [RUN_A]), true);
  assert.equal(named('xa' + RUN_A8.slice(1), [RUN_A]), false);
  assert.equal(named('x' + RUN_A8, [RUN_A]), false);
  assert.equal(named(RUN_A8 + 'x', [RUN_A]), false);
});

test('S12: another id, a null observation, no text and an empty or blank id list name nothing', () => {
  assert.equal(named('see deadbeef', [RUN_A]), false);
  assert.equal(observationNamesGroup(null, [RUN_A], [LABEL]), false);
  assert.equal(observationNamesGroup(undefined, [RUN_A], [LABEL]), false);
  assert.equal(observationNamesGroup({}, [RUN_A], [LABEL]), false);
  assert.equal(named('see ' + RUN_A8, []), false);
  assert.equal(named('see ' + RUN_A8, ['']), false);
  assert.equal(named('see ' + RUN_A8, [''], []), false);
});

test('S13: a label of 8 or more characters names the group as a whole token in any case, never inside a longer token or when shorter than 8', () => {
  assert.equal(named('wave ' + LABEL + ' ended', [], [LABEL]), true);
  assert.equal(named('wave ' + LABEL.toUpperCase() + ' ended', [], [LABEL]), true);
  assert.equal(named('(' + LABEL + ').', [], [LABEL]), true);
  assert.equal(named(LABEL + '-x', [], [LABEL]), false);
  assert.equal(named('x_' + LABEL, [], [LABEL]), false);
  assert.equal(named('x' + LABEL, [], [LABEL]), false);
  assert.equal(named(LABEL + 'x', [], [LABEL]), false);
  assert.equal(named('run abcd-ef went wrong', [], ['abcd-ef']), false);
  assert.equal(named('run wave went wrong', [], ['wave']), false);
});

test('S13 probe: an 8-character label is the floor and names the group; a 7-character one does not', () => {
  assert.equal(named('run abcd-efg went wrong', [], ['abcd-efg']), true);
  assert.equal(named('run abcd-ef went wrong', [], ['abcd-ef']), false);
});

test('S14: a title-only mention names the group, summary absent, empty or null', () => {
  assert.equal(observationNamesGroup({ title: 'Run ' + RUN_A8 + ' stalled' }, [RUN_A]), true);
  assert.equal(observationNamesGroup({ title: 'Run ' + RUN_A8 + ' stalled', summary: '' }, [RUN_A]), true);
  assert.equal(observationNamesGroup({ title: 'Run ' + RUN_A8 + ' stalled', summary: null }, [RUN_A]), true);
  assert.equal(observationNamesGroup({ summary: 'Run ' + RUN_A8 + ' stalled' }, [RUN_A]), true);
});

// ---- probes ---------------------------------------------------------------------------------------------------

test('probe: absent, null and empty text are three states that all name nothing', () => {
  assert.equal(observationNamesGroup({ title: undefined, summary: undefined }, [RUN_A], [LABEL]), false);
  assert.equal(observationNamesGroup({ title: null, summary: null }, [RUN_A], [LABEL]), false);
  assert.equal(observationNamesGroup({ title: '', summary: '' }, [RUN_A], [LABEL]), false);
});

test('probe: a non-string title or summary is ignored, the other field still counts', () => {
  assert.equal(observationNamesGroup({ title: 12345678, summary: 'run ' + RUN_A8 }, [RUN_A]), true);
  assert.equal(observationNamesGroup({ title: ['run ' + RUN_A8], summary: 'nothing' }, [RUN_A]), false);
});

test('probe: duplicate group ids give the same answer as one', () => {
  assert.equal(named('see ' + RUN_A8, [RUN_A, RUN_A, RUN_A8]), true);
  assert.equal(named('see deadbeef', [RUN_A, RUN_A, RUN_A8]), false);
});

test('probe: a 32-hex mention names a 32-hex id; a 33-hex run names nothing', () => {
  const hex32 = RUN_A.replace(/-/g, '');
  assert.equal(hex32.length, 32);
  assert.equal(named('see ' + hex32, [RUN_A]), true);
  assert.equal(named('see ' + hex32 + '0', [RUN_A]), false);
  assert.equal(named('see ' + hex32 + '0', [hex32]), false);
});

test('probe: CRLF line ends around a mention do not hide it', () => {
  assert.equal(named('first line\r\n' + RUN_A8 + '\r\nlast line', [RUN_A]), true);
  assert.equal(named('first line\r\nx' + RUN_A8 + '\r\nlast line', [RUN_A]), false);
});

test('probe: a label holding regex metacharacters matches literally', () => {
  const label = 'r.(1)+abc';
  assert.equal(named('see r.(1)+abc here', [], [label]), true);
  assert.equal(named('see rx1abc here', [], [label]), false);
  assert.equal(named('see r(1)abc here', [], [label]), false);
});

test('probe: a mention of a different id of the same group is enough, and two unrelated mentions are not', () => {
  assert.equal(named('see deadbeef and ' + RUN_A8, ['feedface-0000-4000-8000-000000000000', RUN_A]), true);
  assert.equal(named('see deadbeef and 0badcafe', ['feedface-0000-4000-8000-000000000000', RUN_A]), false);
});

test('probe: the unscoped fixture carries no scope id and the run id differs from the first 8 characters of rid()', () => {
  const sf = unscopedRun();
  assert.equal('scopeId' in sf.runs[0], false);
  assert.notEqual(RUN_A.slice(0, 8), rid(1).slice(0, 8));
  assert.equal(ROOT.length, 36);
});

// ---- S15, S16: docs ---------------------------------------------------------------------------------------------

const flat = (s) => s.replace(/\r\n/g, '\n').replace(/\s+/g, ' ');

test("S15: hooks/README.md says the triage signal is an observation whose title or summary names one of the group's runs", () => {
  const readme = flat(readFileSync(join(PLUGIN, 'hooks', 'README.md'), 'utf8'));
  assert.ok(readme.includes("whose title or summary names one of the group's runs"), 'README triage paragraph');
});

test('S16: session-retrospective SKILL.md Step 1 reads observations with fields summary and keeps only rows naming an in-scope id', () => {
  const raw = readFileSync(join(PLUGIN, 'skills', 'session-retrospective', 'SKILL.md'), 'utf8').replace(/\r\n/g, '\n');
  const start = raw.search(/^## Step 1\b[^\n]*$/m);
  assert.ok(start >= 0, 'Step 1 heading exists');
  const rest = raw.slice(start + 1);
  const next = rest.search(/^## /m);
  const step1 = flat(next >= 0 ? rest.slice(0, next) : rest);
  for (const needle of ['fields: ["summary"]', 'names an in-scope id', '8 or more characters', 'createdAfter', 'retro-reported']) {
    assert.ok(step1.includes(needle), 'Step 1 names ' + needle);
  }
});
