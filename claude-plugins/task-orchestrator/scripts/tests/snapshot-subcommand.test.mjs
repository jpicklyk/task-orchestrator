// Coverage for the run-planner `snapshot` subcommand (scripts/lib/snapshot-builder.mjs + the CLI row in
// run-planner.mjs), item c04b783d. Oracles are the item's specification (D1-D14, AC1-AC11) and
// skills/run-wave/references/snapshot.md, never the builder's own output.
//
// The builder runs against a fake io (a small in-memory server world); S4, S10 and S12 also spawn the CLI.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtempSync, readFileSync, writeFileSync, existsSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { buildPlanDoc, validateSnapshot } from '../run-planner-lib.mjs';
import { buildSnapshot, parseWorktreePorcelain, carryInSection, scopeNeeds, projectSchema } from '../lib/snapshot-builder.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const CLI = path.join(HERE, '..', 'run-planner.mjs');
const NOW = '2026-11-19T12:00:00Z';

const ROOT = 'cccccccc-0000-4000-8000-000000000000';
const P = 'dddddddd-0000-4000-8000-000000000001';
const A = 'aaaaaaaa-1111-4111-8111-111111111111';
const B = 'bbbbbbbb-2222-4222-8222-222222222222';
const L = 'eeeeeeee-3333-4333-8333-333333333333';
const short = (id) => id.slice(0, 8);

function item(id, o = {}) {
  return { id, title: `T ${short(id)}`, priority: 'high', complexity: 2, type: 'scratch-a', role: 'queue', parentId: null, rootId: ROOT, traits: [], ...o };
}

function defaultSchema() {
  return {
    ok: true,
    data: {
      type: 'scratch-a',
      configFingerprint: 'fp1',
      configSource: 'per-root',
      notes: [
        { key: 'specification', role: 'queue', required: true, description: 'DESC-SENTINEL', guidance: 'GUIDE-SENTINEL' },
        { key: 'session-tracking', role: 'work', required: true, maxLength: 2000, description: 'DESC-SENTINEL-2' }
      ],
      features: ['seats', 'dispatchBySeat', 'independent_of', 'rules']
    }
  };
}

function mkWorld(extra = {}) {
  const w = {
    items: { [ROOT]: item(ROOT, { rootId: ROOT }) },
    nextRows: [],
    workRows: [],
    blockedRows: [],
    schemas: {},
    notes: {},
    bodies: {},
    contexts: {},
    pageSize: 200,
    rules: [{ key: 'commit-discipline', rulesVersion: '1' }],
    failures: {},
    calls: [],
    wellKnown: { features: ['seats', 'dispatchBySeat', 'independent_of', 'rules'] },
    origin: 'abc1234',
    ...extra
  };
  return w;
}

function page(w, rows, body) {
  const start = body.cursor ? Number(body.cursor) : 0;
  const size = Math.min(w.pageSize, body.limit || 200);
  const slice = rows.slice(start, start + size);
  const data = { items: slice, page: {} };
  if (start + size < rows.length) data.page.next = String(start + size);
  return { ok: true, data };
}

function findItem(w, id) {
  return Object.values(w.items).find((it) => it.id.startsWith(id) || id.startsWith(it.id));
}

function fakeCall(w) {
  return async (tool, body) => {
    w.calls.push({ tool, body });
    const sel = tool === 'config' ? body.ops[0].op : body.select;
    const failure = w.failures[`${tool} ${sel}`];
    if (failure) return { ok: false, error: { code: failure } };
    if (tool === 'items_read') {
      if (sel === 'get') {
        const it = findItem(w, body.itemId);
        return it ? { ok: true, data: it } : { ok: false, error: { code: 'not_found' } };
      }
      if (sel === 'list' && body.parentId) {
        const kids = Object.values(w.items).filter((it) => it.parentId === body.parentId);
        return { ok: true, data: { items: kids.slice(0, body.limit || 200).map((k) => ({ id: short(k.id) })), page: {} } };
      }
      if (sel === 'list') return page(w, w.workRows, body);
      if (sel === 'next') return page(w, w.nextRows, body);
      if (sel === 'blocked') return page(w, w.blockedRows, body);
      if (sel === 'schema') return w.schemas[body.itemId] || defaultSchema();
      if (sel === 'context') {
        return w.contexts[body.itemId] || { ok: true, data: { item: { role: 'work' }, gateStatus: { canAdvance: true, missing: [] } } };
      }
    }
    if (tool === 'config') return { ok: true, data: { results: [{ ok: true, rules: w.rules }] } };
    if (tool === 'notes_read') {
      const rows = w.notes[body.itemId] || [];
      if (sel === 'list') {
        const filtered = body.keys ? rows.filter((r) => body.keys.includes(r.key)) : rows;
        return page(w, filtered, body);
      }
      if (sel === 'get') {
        const key = `${body.itemId}/${body.key}`;
        return key in w.bodies ? { ok: true, data: { key: body.key, body: w.bodies[key] } } : { ok: false, error: { code: 'not_found' } };
      }
    }
    return { ok: false, error: { code: 'internal' } };
  };
}

function fakeIo(w, opts = {}) {
  return {
    call: fakeCall(w),
    wellKnown: async () => w.wellKnown,
    git: (args) => {
      if (args[0] === 'rev-parse' && args[1] === '--show-toplevel') return { status: 0, stdout: '/repo\n' };
      if (args[0] === 'rev-parse' && args[1] === 'origin/main') return w.origin ? { status: 0, stdout: `${w.origin}\n` } : { status: 128, stdout: 'origin/main\n' };
      if (args[0] === 'worktree') return { status: 0, stdout: 'worktree /repo\nHEAD abc1234\nbranch refs/heads/main\n\nworktree /repo/.claude/worktrees/x\nHEAD def5678\ndetached\n' };
      return { status: 1, stdout: '' };
    },
    fetchOrigin: () => ({ status: opts.fetchStatus ?? 0 }),
    readProfile: () => opts.profile || { kind: 'absent' },
    probe: async () => ({ phase0Hooks: true, pluginVersion: '3.8.0', workflowSizeGuideline: 'medium' })
  };
}

// P with children A and B (both queue); B is dependency-blocked by A.
function parentWorld(extra = {}) {
  const w = mkWorld(extra);
  w.items[P] = item(P, { type: 'feature', parentId: ROOT });
  w.items[A] = item(A, { parentId: P });
  w.items[B] = item(B, { parentId: P });
  w.nextRows = [{ id: short(A) }];
  w.blockedRows = [
    { id: short(B), role: 'queue', blockType: 'dependency', blockers: [{ id: A, role: 'queue', threshold: 'work', satisfied: false }] }
  ];
  return w;
}

const run = (w, input, ioOpts) => buildSnapshot({ root: short(ROOT), now: NOW, noFetch: true, ...input }, fakeIo(w, ioOpts));

function cliRun(args, { env, cwd } = {}) {
  return spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8', env: { ...process.env, ...env }, cwd });
}

function cliRunAsync(args, { env, cwd } = {}) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [CLI, ...args], { env: { ...process.env, ...env }, cwd });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => (stdout += d));
    child.stderr.on('data', (d) => (stderr += d));
    child.on('close', (status) => resolve({ status, stdout, stderr }));
  });
}

test('S1: ancestor with children A and B: both candidates, validates, plans ok', async () => {
  const w = parentWorld();
  const r = await run(w, { ancestor: short(P) });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.deepEqual(r.snapshot.candidates.map((c) => c.id), [A, B]);
  assert.deepEqual(r.snapshot.candidates.map((c) => c.source), ['ready', 'ready']);
  assert.equal(r.snapshot.ancestorId, P);
  assert.equal(r.snapshot.rootId, ROOT);
  assert.equal(r.snapshot.contract, 'run-wave/snapshot-v1');
  assert.equal(r.snapshot.capturedAt, NOW);
  assert.equal(validateSnapshot(r.snapshot).ok, true);
  const plan = buildPlanDoc(r.snapshot, { now: NOW });
  assert.equal(plan.ok, true, JSON.stringify(plan.errors));
  assert.deepEqual(plan.doc.args.items.map((it) => it.short).sort(), [short(A), short(B)].sort());
});

test('S1: a leaf ancestor is its own single candidate and plans ok', async () => {
  const w = mkWorld();
  w.items[L] = item(L, { parentId: ROOT });
  w.nextRows = [{ id: short(L) }];
  const r = await run(w, { ancestor: L });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.deepEqual(r.snapshot.candidates.map((c) => c.id), [L]);
  assert.equal(r.snapshot.candidates[0].hasChildren, false);
  const plan = buildPlanDoc(r.snapshot, { now: NOW });
  assert.equal(plan.ok, true, JSON.stringify(plan.errors));
});

test('S2: zero candidates takes features from the well-known document; absent profile is {}; rulesServed is an array', async () => {
  const w = mkWorld();
  w.items[L] = item(L, { parentId: ROOT });
  const r = await run(w, { ancestor: L });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.deepEqual(r.snapshot.candidates, []);
  assert.deepEqual(r.snapshot.features, w.wellKnown.features);
  assert.deepEqual(r.snapshot.profile, {});
  assert.ok(Array.isArray(r.snapshot.rulesServed));
  assert.deepEqual(r.snapshot.rulesServed, [{ key: 'commit-discipline', rulesVersion: '1' }]);
});

test('S2: with candidates, features come from the first schema answer even when the well-known document differs', async () => {
  const w = parentWorld({ wellKnown: { features: ['other'] } });
  const r = await run(w, { ancestor: P });
  assert.deepEqual(r.snapshot.features, ['seats', 'dispatchBySeat', 'independent_of', 'rules']);
});

test('S2: no features source is exit 3 and carries no snapshot', async () => {
  const w = mkWorld({ wellKnown: null });
  w.items[L] = item(L, { parentId: ROOT });
  const r = await run(w, { ancestor: L });
  assert.equal(r.ok, false);
  assert.equal(r.exit, 3);
  assert.equal(r.snapshot, undefined);
});

test('S2: a parsed run profile is carried verbatim', async () => {
  const w = parentWorld();
  const r = await run(w, { ancestor: P }, { profile: { kind: 'ok', value: { maxItems: 3, verify: [] } } });
  assert.deepEqual(r.snapshot.profile, { maxItems: 3, verify: [] });
});

test('S3: an ancestor with children is never a candidate, even when next and list return it', async () => {
  const w = parentWorld();
  w.nextRows = [{ id: short(P) }, { id: short(A) }];
  w.workRows = [{ id: short(P) }];
  const r = await run(w, { ancestor: P });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.ok(!r.snapshot.candidates.some((c) => c.id === P));
  assert.deepEqual(r.snapshot.candidates.map((c) => c.id), [A, B]);
});

test('S3: a container below the ancestor stays a candidate and records hasChildren', async () => {
  const w = parentWorld();
  w.items[A] = item(A, { parentId: P });
  const kid = 'ffffffff-4444-4444-8444-444444444444';
  w.items[kid] = item(kid, { parentId: A });
  const r = await run(w, { ancestor: P });
  assert.equal(r.snapshot.candidates.find((c) => c.id === A).hasChildren, true);
});

test('S3: candidate order is next rows, then blocked-only-by-candidates joins, then work rows', async () => {
  const w = parentWorld();
  const W = 'b1b1b1b1-9999-4999-8999-999999999999';
  const C = 'a1a1a1a1-8888-4888-8888-888888888888';
  w.items[W] = item(W, { parentId: P, role: 'work' });
  w.items[C] = item(C, { parentId: P });
  w.workRows = [{ id: short(W) }];
  // C is blocked only by the work candidate W and by B (itself a join of A): a fixpoint
  w.blockedRows.push({ id: short(C), role: 'queue', blockType: 'dependency', blockers: [{ id: W, role: 'work', threshold: 'terminal', satisfied: false }, { id: B, role: 'queue', threshold: 'terminal', satisfied: false }] });
  const r = await run(w, { ancestor: P });
  assert.deepEqual(r.snapshot.candidates.map((c) => c.id), [A, B, C, W]);
  assert.equal(r.snapshot.candidates.find((c) => c.id === W).source, 'work');
});

test('S3: an item blocked by a non-candidate is not joined', async () => {
  const w = parentWorld();
  const X = 'a2a2a2a2-7777-4777-8777-777777777777';
  const Y = 'b2b2b2b2-6666-4666-8666-666666666666';
  w.items[X] = item(X, { parentId: P });
  w.items[Y] = item(Y, { parentId: P });
  w.blockedRows.push({ id: short(Y), role: 'queue', blockType: 'dependency', blockers: [{ id: X, role: 'queue', threshold: 'work', satisfied: false }] });
  const r = await run(w, { ancestor: P });
  assert.ok(!r.snapshot.candidates.some((c) => c.id === Y));
});

test('S4: no REST URL exits 2 naming it and writes no --out file', () => {
  const empty = mkdtempSync(path.join(tmpdir(), 'snap-s4-'));
  const out = path.join(empty, 'snapshot.json');
  const res = cliRun(['snapshot', '--root', ROOT, '--ancestor', P, '--out', out], {
    cwd: empty,
    env: {
      TASK_ORCHESTRATOR_API_URL: '',
      TASK_ORCHESTRATOR_HOME: empty,
      CLAUDE_CONFIG_DIR: empty,
      HOME: empty,
      USERPROFILE: empty
    }
  });
  assert.equal(res.status, 2, res.stderr);
  assert.match(res.stderr, /no REST API URL/);
  assert.equal(existsSync(out), false);
});

test('S5: no note body, guidance or description enters the snapshot except the carry-in record', async () => {
  const w = parentWorld();
  const W = 'b1b1b1b1-9999-4999-8999-999999999999';
  w.items[W] = item(W, { parentId: P, role: 'work' });
  w.workRows = [{ id: short(W) }];
  // Every list row and every note a careless builder might read hands out a sentinel body.
  w.notes[A] = [
    { key: 'specification', owner: { seat: 'planner' }, body: 'BODY-SENTINEL-ROW-A' },
    { key: 'carry-in-x', body: 'BODY-SENTINEL-ROW-CARRY' },
    { key: 'carry-in-dispositions', body: 'BODY-SENTINEL-ROW-RECORD' }
  ];
  w.notes[W] = [{ key: 'specification', owner: { seat: 'planner' }, body: 'BODY-SENTINEL-ROW-W' }];
  w.bodies[`${A}/specification`] = 'BODY-SENTINEL';
  w.bodies[`${A}/carry-in-x`] = 'BODY-SENTINEL-CARRY';
  w.bodies[`${W}/specification`] = 'BODY-SENTINEL-W';
  w.bodies[`${A}/carry-in-dispositions`] = 'carry-in-x: in scope (AC1)';
  w.schemas[A] = defaultSchema();
  w.schemas[A].data.seats = [{ name: 'implementer', description: 'DESC-SEAT-SENTINEL', skills: ['s'] }];
  const r = await run(w, { ancestor: P });
  assert.equal(r.ok, true, JSON.stringify(r));
  const text = JSON.stringify(r.snapshot);
  for (const s of ['BODY-SENTINEL', 'GUIDE-SENTINEL', 'DESC-SENTINEL', 'DESC-SEAT-SENTINEL']) assert.ok(!text.includes(s), s);
  assert.equal(r.snapshot.carryIns[A].dispositions, 'carry-in-x: in scope (AC1)');
  assert.deepEqual(r.snapshot.noteOwners[W], [{ key: 'specification', seat: 'planner' }]);
  assert.deepEqual(r.snapshot.schemas[A].seats, [{ name: 'implementer', skills: ['s'] }]);
  // The only note body the builder reads is the carry-in record.
  const gets = w.calls.filter((c) => c.tool === 'notes_read' && c.body.select === 'get').map((c) => c.body.key);
  assert.deepEqual(gets, ['carry-in-dispositions']);
});

test('S5: a note projects to its structural fields only', () => {
  const s = projectSchema({
    ok: true,
    data: {
      type: 't', configFingerprint: 'f', configSource: 'per-root',
      notes: [{ key: 'k', role: 'queue', required: true, seat: 'planner', skill: 'x', skills: ['a', 'b'], skillMode: 'method-on-diff', lane: { output: 'findings', after: ['q'] }, maxLength: 4000, description: 'D', guidance: 'G', extra: 1 }]
    }
  });
  assert.deepEqual(s.notes, [{ key: 'k', role: 'queue', required: true, seat: 'planner', skill: 'x', skills: ['a', 'b'], skillMode: 'method-on-diff', lane: { output: 'findings', after: ['q'] }, maxLength: 4000 }]);
  const t = projectSchema({ ok: true, data: { type: 't', notes: [{ key: 'k', role: 'queue', required: false, maxLength: 0 }, { key: 'j', role: 'work', required: false, maxLength: 1.5 }] } });
  assert.equal('maxLength' in t.notes[0], false);
  assert.equal('maxLength' in t.notes[1], false);
});

test('S6: schemaFree and a not_found error give not-found; any other error gives unavailable', async () => {
  assert.deepEqual(projectSchema({ ok: true, data: { schemaFree: true } }), { status: 'not-found' });
  assert.deepEqual(projectSchema({ ok: false, error: { code: 'not_found' } }), { status: 'not-found' });
  assert.deepEqual(projectSchema({ ok: false, error: { code: 'internal' } }), { status: 'unavailable' });
  assert.deepEqual(projectSchema({ ok: false, error: { code: 'client_error' } }), { status: 'unavailable' });
  const w = parentWorld();
  w.schemas[A] = { ok: true, data: { schemaFree: true } };
  w.schemas[B] = { ok: false, error: { code: 'internal' } };
  const r = await run(w, { ancestor: P });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.snapshot.schemas[A].status, 'not-found');
  assert.equal(r.snapshot.schemas[B].status, 'unavailable');
});

test('S7: blocked[] carries reason, decisionId and resumable when present', async () => {
  const w = parentWorld();
  const D = 'abababab-0000-4000-8000-000000000009';
  w.blockedRows.push({ id: short(L), role: 'blocked', blockType: 'explicit', reason: 'awaiting_decision', decisionId: D, resumable: true });
  w.items[L] = item(L, { parentId: P, role: 'blocked' });
  const r = await run(w, { ancestor: P });
  const entry = r.snapshot.blocked.find((b) => b.itemId === short(L));
  assert.equal(entry.reason, 'awaiting_decision');
  assert.equal(entry.decisionId, D);
  assert.equal(entry.resumable, true);
  assert.equal(entry.blockType, 'explicit');
  const dep = r.snapshot.blocked.find((b) => b.itemId === short(B));
  assert.deepEqual(dep.blockedBy, [{ itemId: A, role: 'queue', effectiveUnblockRole: 'work', satisfied: false }]);
  assert.equal('reason' in dep, false);
  assert.equal('resumable' in dep, false);
});

test('S7: --items mode keeps only the given ids\' blocked entries and skips non queue/work roles', async () => {
  const w = parentWorld();
  const T = 'abababab-5555-4555-8555-555555555555';
  w.items[T] = item(T, { parentId: P, role: 'terminal' });
  w.blockedRows.push({ id: short(L), role: 'queue', blockType: 'dependency', blockers: [{ id: A, role: 'queue', threshold: 'work', satisfied: false }] });
  w.items[L] = item(L, { parentId: P });
  const r = await run(w, { items: [A, B, T] });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.snapshot.ancestorId, null);
  assert.deepEqual(r.snapshot.candidates.map((c) => c.id), [A, B]);
  assert.deepEqual(r.summary.skipped, [{ id: T, role: 'terminal' }]);
  assert.deepEqual(r.snapshot.blocked.map((b) => b.itemId), [short(B)]);
  const blockedCall = w.calls.find((c) => c.body.select === 'blocked');
  assert.equal(blockedCall.body.ancestorId, ROOT);
});

test('S8: carryIns keys are collected across pages, exclude the record, dispositions come from the record', async () => {
  const w = parentWorld({ pageSize: 1 });
  w.notes[A] = [{ key: 'specification' }, { key: 'carry-in-a' }, { key: 'carry-in-dispositions' }, { key: 'carry-in-b' }];
  w.bodies[`${A}/carry-in-dispositions`] = 'carry-in-a: in scope (AC1)\ncarry-in-b: rejected because x';
  const r = await run(w, { ancestor: P });
  assert.deepEqual(r.snapshot.carryIns[A], { keys: ['carry-in-a', 'carry-in-b'], dispositions: 'carry-in-a: in scope (AC1)\ncarry-in-b: rejected because x' });
  assert.equal(B in r.snapshot.carryIns, false);
  assert.ok(w.calls.filter((c) => c.tool === 'notes_read' && c.body.itemId === A && c.body.select === 'list' && !c.body.keys).length >= 3);
});

test('S8: without a record the planner note section is used; a missing section gives null', async () => {
  const w = parentWorld();
  w.notes[A] = [{ key: 'specification' }, { key: 'carry-in-a' }];
  w.bodies[`${A}/specification`] = 'intro\n## Carry-in dispositions\ncarry-in-a: in scope (AC2)\n\n## Next\nignored';
  w.notes[B] = [{ key: 'specification' }, { key: 'carry-in-b' }];
  w.bodies[`${B}/specification`] = 'no section here';
  const r = await run(w, { ancestor: P });
  assert.equal(r.snapshot.carryIns[A].dispositions, 'carry-in-a: in scope (AC2)');
  assert.equal(r.snapshot.carryIns[B].dispositions, null);
});

test('S8: a carry-in record answered ok without a string body is exit 3 with code bad_answer, not no_response', async () => {
  const w = parentWorld();
  w.notes[A] = [{ key: 'specification' }, { key: 'carry-in-a' }, { key: 'carry-in-dispositions' }];
  w.bodies[`${A}/carry-in-dispositions`] = undefined;
  const r = await run(w, { ancestor: P });
  assert.equal(r.ok, false);
  assert.equal(r.exit, 3);
  assert.deepEqual(r.detail, { read: 'notes_read get', code: 'bad_answer' });
});

test('S8: carryInSection stops at the next heading, trims, and gives null when empty', () => {
  assert.equal(carryInSection('## Carry-in dispositions\n  x: y  \n# Other\nz'), 'x: y');
  assert.equal(carryInSection('## Carry-in dispositions\n\n## Next'), null);
  assert.equal(carryInSection('nothing'), null);
  assert.equal(carryInSection('## Carry-in dispositions\na\n### sub\nb'), 'a\n### sub\nb');
});

test('S9: needs.scopes lists both ends of same-parent edges; --scopes entries land in snapshot.scopes and leave needs', async () => {
  const w = parentWorld();
  const r = await run(w, { ancestor: P });
  assert.deepEqual(r.summary.needs.scopes.sort(), [A, B].sort());
  assert.equal('scopes' in r.snapshot, false);
  const scopes = { [A]: { files: ['a.mjs'] } };
  const r2 = await run(parentWorld(), { ancestor: P, scopes });
  assert.deepEqual(r2.snapshot.scopes, scopes);
  assert.deepEqual(r2.summary.needs.scopes, [B]);
  const r3 = await run(parentWorld(), { ancestor: P, scopes: { [A]: { files: [] }, [B]: { files: [] } } });
  assert.deepEqual(r3.summary.needs.scopes, []);
});

test('S9: an edge between different parents or to a satisfied blocker needs no scopes', () => {
  const c = [
    { id: A, parentId: 'p1' },
    { id: B, parentId: 'p2' },
    { id: L, parentId: 'p1' }
  ];
  const blocked = [
    { itemId: short(B), blockedBy: [{ itemId: A, satisfied: false }] },
    { itemId: short(L), blockedBy: [{ itemId: A, satisfied: true }] }
  ];
  assert.deepEqual(scopeNeeds(c, blocked, undefined), []);
});

test('S11: parseWorktreePorcelain maps branch, head and detached to a null branch', () => {
  const text = 'worktree C:/repo\nHEAD 1111\nbranch refs/heads/main\n\nworktree C:/repo/.claude/worktrees/f\nHEAD 2222\nbranch refs/heads/feat/x\n\nworktree C:/repo/.claude/worktrees/d\nHEAD 3333\ndetached\n';
  assert.deepEqual(parseWorktreePorcelain(text), {
    'C:/repo': { branch: 'main', head: '1111' },
    'C:/repo/.claude/worktrees/f': { branch: 'feat/x', head: '2222' },
    'C:/repo/.claude/worktrees/d': { branch: null, head: '3333' }
  });
  assert.deepEqual(parseWorktreePorcelain(''), {});
});

test('S11: git facts: repoRoot, originMain, worktrees; warnings for a failed fetch and a missing origin/main', async () => {
  const w = parentWorld({ origin: null });
  const r = await run(w, { ancestor: P, noFetch: false }, { fetchStatus: 1 });
  assert.equal(r.snapshot.git.repoRoot, '/repo');
  assert.equal(r.snapshot.git.originMain, null);
  assert.deepEqual(r.snapshot.git.worktrees['/repo/.claude/worktrees/x'], { branch: null, head: 'def5678' });
  assert.deepEqual(r.summary.warnings, ['git fetch origin failed', 'origin/main not resolvable']);
  const r2 = await run(parentWorld(), { ancestor: P });
  assert.equal(r2.snapshot.git.originMain, 'abc1234');
  assert.deepEqual(r2.summary.warnings, []);
});

test('S12: argument errors are exit 2 (root mismatch, root not depth 0, missing or both scopes)', async () => {
  const w = parentWorld();
  w.items[L] = item(L, { parentId: ROOT, rootId: 'ffffffff-0000-4000-8000-00000000000f' });
  const exit2 = (r, reason) => {
    assert.equal(r.exit, 2, JSON.stringify(r));
    assert.equal(r.error, 'invalid args');
    assert.match(r.detail, reason);
  };
  exit2(await run(w, { ancestor: L }), /--ancestor .* belongs to another root/);
  exit2(await buildSnapshot({ root: short(P), ancestor: short(A), now: NOW, noFetch: true }, fakeIo(w)), /is not a root item/);
  exit2(await run(w, {}), /one of --ancestor <id> or --items <id,...> is required/);
  exit2(await run(w, { ancestor: P, items: [A] }), /--ancestor and --items are mutually exclusive/);
  exit2(await buildSnapshot({ ancestor: P, now: NOW }, fakeIo(w)), /--root <rootId> is required/);
});

test('S12: an unusable checkout or an invalid profile file is exit 2', async () => {
  const w = parentWorld();
  const badGit = { ...fakeIo(w), git: () => ({ status: 128, stdout: '' }) };
  const git = await buildSnapshot({ root: ROOT, ancestor: P, now: NOW, noFetch: true }, badGit);
  assert.equal(git.exit, 2);
  assert.match(git.detail, /not a git checkout/);
  const profile = await run(w, { ancestor: P }, { profile: { kind: 'invalid' } });
  assert.equal(profile.exit, 2);
  assert.match(profile.detail, /run-profile\.json is not valid JSON/);
});

test('S12 (CLI): missing --out, both scopes, neither scope and a non-object --scopes file exit 2', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'snap-s12-'));
  const out = path.join(dir, 's.json');
  const noOut = cliRun(['snapshot', '--root', ROOT, '--ancestor', P, '--api-url', 'http://127.0.0.1:9']);
  assert.equal(noOut.status, 2);
  assert.match(noOut.stderr, /--out/);
  const both = cliRun(['snapshot', '--root', ROOT, '--ancestor', P, '--items', A, '--out', out, '--api-url', 'http://127.0.0.1:9']);
  assert.equal(both.status, 2);
  assert.match(both.stderr, /mutually exclusive/);
  const neither = cliRun(['snapshot', '--root', ROOT, '--out', out, '--api-url', 'http://127.0.0.1:9']);
  assert.equal(neither.status, 2);
  assert.match(neither.stderr, /one of --ancestor/);
  const badScopes = path.join(dir, 'scopes.json');
  writeFileSync(badScopes, '[1]');
  const bad = cliRun(['snapshot', '--root', ROOT, '--ancestor', P, '--out', out, '--scopes', badScopes, '--api-url', 'http://127.0.0.1:9']);
  assert.equal(bad.status, 2);
  assert.match(bad.stderr, /is not a JSON object file/);
  assert.equal(existsSync(out), false);
});

test('S12 (CLI): a flag whose next token is another flag has no value and exits 2 (not a failed read)', () => {
  const dir = mkdtempSync(path.join(tmpdir(), 'snap-s12b-'));
  const out = path.join(dir, 's.json');
  const res = cliRun(['snapshot', '--root', '--ancestor', P, '--out', out, '--api-url', 'http://127.0.0.1:9']);
  assert.equal(res.status, 2, res.stderr);
  assert.match(res.stderr, /--root needs a value/);
  assert.equal(existsSync(out), false);
});

test('S13: a failed required read is exit 3 with the read and code, and no snapshot', async () => {
  const w = parentWorld({ failures: { 'items_read blocked': 'internal' } });
  const r = await run(w, { ancestor: P });
  assert.equal(r.ok, false);
  assert.equal(r.exit, 3);
  assert.deepEqual(r.detail, { read: 'items_read blocked', code: 'internal' });
  const w2 = parentWorld({ failures: { 'config rules': 'unavailable' } });
  const r2 = await run(w2, { ancestor: P });
  assert.equal(r2.exit, 3);
  assert.equal(r2.detail.read, 'config rules');
  const w3 = parentWorld({ failures: { 'items_read context': 'internal' } });
  const r3 = await run(w3, { ancestor: P });
  assert.equal(r3.exit, 3);
  assert.equal(r3.detail.read, 'items_read context');
});

test('S13: a snapshot that fails validateSnapshot is exit 3 and carries the errors', async () => {
  const w = parentWorld();
  w.items[A] = item(A, { parentId: P, role: 'Weird' });
  const r = await run(w, { ancestor: P });
  assert.equal(r.exit, 3);
  assert.ok(Array.isArray(r.detail) && r.detail.some((e) => /invalid role/.test(e)));
});

test('noteOwners: work candidates only, from owner.seat rows; parents context is projected', async () => {
  const w = parentWorld();
  const W = 'b1b1b1b1-9999-4999-8999-999999999999';
  w.items[W] = item(W, { parentId: P, role: 'work' });
  w.workRows = [{ id: short(W) }];
  w.notes[W] = [{ key: 'specification', owner: { seat: 'planner' } }, { key: 'session-tracking' }];
  w.contexts[P] = { ok: true, data: { item: { role: 'work' }, gateStatus: { canAdvance: false, missing: [{ key: 'x' }] } } };
  const r = await run(w, { ancestor: P });
  assert.deepEqual(r.snapshot.noteOwners, { [W]: [{ key: 'specification', seat: 'planner' }] });
  assert.deepEqual(r.snapshot.parents, { [P]: { role: 'work', canAdvance: false, missing: [{ key: 'x' }] } });
});

test('S10 (CLI): end to end against an http stub: exit 0, summary line with the origin warning, plan accepts the file', async () => {
  const w = parentWorld();
  const call = fakeCall(w);
  const server = createServer((req, res) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', async () => {
      res.setHeader('Content-Type', 'application/json');
      if (req.method === 'GET') {
        res.end(JSON.stringify(w.wellKnown));
        return;
      }
      const tool = req.url.split('/').pop();
      const r = await call(tool, JSON.parse(data || '{}'));
      res.end(JSON.stringify(r.ok ? { ok: true, data: r.data } : { ok: false, error: { code: r.error.code, kind: 'permanent', message: r.error.code } }));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const url = `http://127.0.0.1:${server.address().port}`;
    const repo = mkdtempSync(path.join(tmpdir(), 'snap-s10-'));
    assert.equal(spawnSync('git', ['init', '-q', repo]).status, 0);
    const out = path.join(repo, 'snapshot.json');
    const res = await cliRunAsync(['snapshot', '--root', ROOT, '--ancestor', P, '--out', out, '--api-url', url, '--no-server-check', '--no-fetch', '--cwd', repo, '--now', NOW], { cwd: repo });
    assert.equal(res.status, 0, res.stderr);
    const summary = JSON.parse(res.stdout.trim());
    assert.equal(summary.ok, true);
    assert.equal(path.resolve(summary.out), path.resolve(out));
    assert.equal(summary.candidates, 2);
    assert.deepEqual(summary.needs.scopes.sort(), [A, B].sort());
    assert.deepEqual(summary.skipped, []);
    assert.deepEqual(summary.warnings, ['origin/main not resolvable']);
    const snap = JSON.parse(readFileSync(out, 'utf8'));
    assert.equal(snap.contract, 'run-wave/snapshot-v1');
    assert.equal(snap.capturedAt, NOW);
    assert.ok(snap.probe && typeof snap.probe === 'object');
    assert.equal(snap.git.originMain, null);
    const scratch = mkdtempSync(path.join(tmpdir(), 'snap-s10-scratch-'));
    mkdirSync(path.join(scratch, 'run-wave'), { recursive: true });
    const plan = spawnSync(process.execPath, [CLI, 'plan', '--in', out, '--scratchpad', scratch], { encoding: 'utf8' });
    assert.equal(plan.status, 0, plan.stderr);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('S10 (CLI): a valid --scopes file reaches the snapshot and leaves the stdout needs', async () => {
  const w = parentWorld();
  const call = fakeCall(w);
  const server = createServer((req, res) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', async () => {
      res.setHeader('Content-Type', 'application/json');
      if (req.method === 'GET') return res.end(JSON.stringify(w.wellKnown));
      const r = await call(req.url.split('/').pop(), JSON.parse(data || '{}'));
      res.end(JSON.stringify(r.ok ? { ok: true, data: r.data } : { ok: false, error: { code: r.error.code, kind: 'permanent', message: r.error.code } }));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const repo = mkdtempSync(path.join(tmpdir(), 'snap-s10c-'));
    assert.equal(spawnSync('git', ['init', '-q', repo]).status, 0);
    const out = path.join(repo, 'snapshot.json');
    const scopesFile = path.join(repo, 'scopes.json');
    const scopes = { [A]: { files: ['a.mjs'] }, [B]: { files: ['b.mjs'], dependsOn: [A] } };
    writeFileSync(scopesFile, JSON.stringify(scopes));
    const res = await cliRunAsync(['snapshot', '--root', ROOT, '--ancestor', P, '--out', out, '--scopes', scopesFile, '--api-url', `http://127.0.0.1:${server.address().port}`, '--no-server-check', '--no-fetch', '--cwd', repo, '--now', NOW], { cwd: repo });
    assert.equal(res.status, 0, res.stderr);
    assert.deepEqual(JSON.parse(res.stdout.trim()).needs.scopes, []);
    assert.deepEqual(JSON.parse(readFileSync(out, 'utf8')).scopes, scopes);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});

test('S10 (CLI): a server that fails a read gives exit 3 and writes no file', async () => {
  const w = parentWorld({ failures: { 'items_read next': 'internal' } });
  const call = fakeCall(w);
  const server = createServer((req, res) => {
    let data = '';
    req.on('data', (c) => (data += c));
    req.on('end', async () => {
      res.setHeader('Content-Type', 'application/json');
      if (req.method === 'GET') return res.end(JSON.stringify(w.wellKnown));
      const r = await call(req.url.split('/').pop(), JSON.parse(data || '{}'));
      res.end(JSON.stringify(r.ok ? { ok: true, data: r.data } : { ok: false, error: { code: r.error.code, kind: 'permanent', message: r.error.code } }));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const repo = mkdtempSync(path.join(tmpdir(), 'snap-s13-'));
    assert.equal(spawnSync('git', ['init', '-q', repo]).status, 0);
    const out = path.join(repo, 'snapshot.json');
    const res = await cliRunAsync(['snapshot', '--root', ROOT, '--ancestor', P, '--out', out, '--api-url', `http://127.0.0.1:${server.address().port}`, '--no-server-check', '--no-fetch', '--cwd', repo], { cwd: repo });
    assert.equal(res.status, 3, res.stderr);
    assert.match(res.stderr, /items_read next/);
    assert.equal(existsSync(out), false);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
