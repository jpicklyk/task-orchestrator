// Coverage for several run-wave runs on one root (item 08c8b0eb): the plan-side rules of task-scope AC2 (member
// deferral), AC3 (cross-run file overlap), AC4 (worktree and branch hints, worktree refusal) and AC6 (the
// scoped-rule wording in four docs). Scenario ids S7, S8, S9, S13, S14, S15, S18 match the frozen `test-plan` note.
//
// BLIND test author: no production source (scripts/*.mjs, scripts/lib/*.mjs, workflows/*.js) was opened by any
// tool. Oracles are the task-scope acceptance criteria and the declarations file; every expected value is a literal
// derived from them, never from running the code under test.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildPlanDoc, crossRunDeferrals, validateSnapshot, worktreeConflicts } from '../run-planner-lib.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const PLUGIN_ROOT = join(HERE, '..', '..');
const CLI = join(PLUGIN_ROOT, 'scripts', 'run-planner.mjs');
const FIXTURES = join(HERE, 'fixtures', 'run-planner');
const NOW = '2026-11-27T12:00:00Z';
const BS = String.fromCharCode(92);

const PARENT = 'f0000000-0000-4000-8000-0000000000f0';
const C = 'c0000001-0000-4000-8000-000000000000';
const D = 'd0000001-0000-4000-8000-000000000000';
const E = 'e0000001-0000-4000-8000-000000000000';
const M = 'a1a1a1a1';
const RUN_L = '12345678-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const SERVER_RUN = '8f14e45f-ceea-4672-8a3e-5e8f4a9b7c10';

function candidate(id, parentId = PARENT) {
  return {
    id, short: id.slice(0, 8), title: `Item ${id.slice(0, 8)}`, priority: 'high', complexity: 2, type: 'scratch-a',
    role: 'queue', parentId, traits: [], hasChildren: false, source: 'ready'
  };
}

function schema() {
  return {
    status: 'ok', type: 'scratch-a', configFingerprint: 'fp1', configSource: 'per-root',
    notes: [
      { key: 'specification', role: 'queue', required: true, seat: null },
      { key: 'session-tracking', role: 'work', required: true, seat: null }
    ]
  };
}

function snapshot(over = {}) {
  const ids = over.ids || [C, E];
  const out = {
    contract: 'run-wave/snapshot-v1',
    rootId: '00000000-0000-4000-8000-000000000000',
    ancestorId: null,
    capturedAt: NOW,
    features: ['seats', 'dispatchBySeat', 'independent_of', 'rules'],
    rulesServed: [{ key: 'commit-discipline', rulesVersion: '1' }],
    candidates: ids.map((id) => candidate(id)),
    blocked: [],
    schemas: Object.fromEntries(ids.map((id) => [id, schema()])),
    noteOwners: {},
    parents: { [PARENT]: { role: 'work', canAdvance: true, missing: [] } },
    profile: {
      shell: 'bash', verify: [], searchScope: ['/repo'], worktreeRoot: '.claude/worktrees',
      branchPrefix: { shared: 'feat/', perItem: 'fix/' }, maxItems: 5
    },
    git: { originMain: 'e77c3e95', repoRoot: over.repoRoot || '/repo', worktrees: {} },
    probe: { phase0Hooks: true, pluginVersion: '3.8.0', workflowSizeGuideline: 'medium' }
  };
  delete over.ids;
  delete over.repoRoot;
  return { ...out, ...over };
}

const other = (over = {}) => ({
  runId: RUN_L, label: 'L', status: 'open', scopeId: null, worktree: null, branch: null, members: [M], ...over
});

function reasons(deferred) {
  return Object.fromEntries(deferred.map((d) => [d.id, d.reason]));
}

// ---- S7 (AC2): a candidate that is a member of another run is deferred, and its dependents with it ----

test('[08c8b0eb] S7: a candidate that is a member of another run is deferred "member of run <label>" and never admitted', () => {
  const snap = snapshot({ ids: [C, E], otherRuns: [other({ members: [C.slice(0, 8)] })] });
  assert.equal(validateSnapshot(snap).ok, true, JSON.stringify(validateSnapshot(snap).errors));
  const res = buildPlanDoc(snap, { now: NOW, mode: 'per-item' });
  assert.equal(res.ok, true, JSON.stringify(res.errors));
  assert.deepEqual(res.doc.args.items.map((it) => it.short), [E.slice(0, 8)]);
  assert.deepEqual(res.doc.args.deferred[0], { id: C, reason: 'member of run L' });
});

test('[08c8b0eb] S7: a same-parent dependent of the member defers cross-run, since the member left the copy the pipeline sees', () => {
  const snap = snapshot({
    ids: [C, D, E],
    otherRuns: [other({ members: [C.slice(0, 8)] })],
    blocked: [{ itemId: D, role: 'queue', blockType: 'dependency', blockedBy: [{ itemId: C, role: 'queue', effectiveUnblockRole: 'terminal', satisfied: false }] }]
  });
  const res = buildPlanDoc(snap, { now: NOW, mode: 'per-item' });
  assert.equal(res.ok, true, JSON.stringify(res.errors));
  assert.deepEqual(res.doc.args.items.map((it) => it.short), [E.slice(0, 8)]);
  const r = reasons(res.doc.args.deferred);
  assert.equal(r[C], 'member of run L');
  assert.match(r[D], /^blocked by c0000001 until \w+ \(cross-run\)$/);
});

test('[08c8b0eb] S7: crossRunDeferrals removes the member and its blocked rows from the returned snapshot', () => {
  const snap = snapshot({
    ids: [C, D, E],
    otherRuns: [other({ members: [C.slice(0, 8)] })],
    blocked: [
      { itemId: C, role: 'queue', blockType: 'dependency', blockedBy: [{ itemId: E, role: 'queue', effectiveUnblockRole: 'terminal', satisfied: false }] },
      { itemId: D, role: 'queue', blockType: 'dependency', blockedBy: [{ itemId: C, role: 'queue', effectiveUnblockRole: 'terminal', satisfied: false }] }
    ]
  });
  const out = crossRunDeferrals(snap);
  assert.deepEqual(out.deferred, [{ id: C, reason: 'member of run L' }]);
  assert.deepEqual(out.snap.candidates.map((c) => c.id), [D, E]);
  assert.deepEqual(out.snap.blocked.map((b) => b.itemId), [D]);
});

test('[08c8b0eb] S7 (probe): the label falls back to the run id without dashes, first 8 chars, for a null or empty label', () => {
  for (const label of [null, '']) {
    const snap = snapshot({ otherRuns: [other({ label, members: [C.slice(0, 8)] })] });
    const out = crossRunDeferrals(snap);
    assert.deepEqual(out.deferred, [{ id: C, reason: 'member of run 12345678' }], String(label));
  }
});

test('[08c8b0eb] S7 (probe): no member in common and no otherRuns key defer nothing', () => {
  assert.deepEqual(crossRunDeferrals(snapshot({ otherRuns: [other()] })).deferred, []);
  assert.deepEqual(crossRunDeferrals(snapshot()).deferred, []);
  assert.deepEqual(crossRunDeferrals(snapshot({ otherRuns: [] })).deferred, []);
});

// ---- S8, S14, S15 (AC3): cross-run file overlap ----

const claimsSnap = ({ candidateFiles, claims, append, otherOver = {} }) => snapshot({
  ids: [C, E],
  profile: { ...snapshot().profile, ...(append === undefined ? {} : { appendOnlyFiles: append }) },
  scopes: candidateFiles === undefined ? undefined : { [C]: { files: candidateFiles } },
  otherRuns: [other({ claims: { [M]: claims }, ...otherOver })]
});

test('[08c8b0eb] S8: a candidate whose files overlap a member claim yields "overlap <member> on <files> (run <label>)", shared and per-item', () => {
  for (const mode of ['shared', 'per-item']) {
    const snap = claimsSnap({ candidateFiles: ['src/a.js'], claims: ['src/a.js'] });
    const res = buildPlanDoc(snap, { now: NOW, mode });
    assert.equal(res.ok, true, mode + JSON.stringify(res.errors));
    assert.deepEqual(res.doc.args.deferred[0], { id: C, reason: 'overlap a1a1a1a1 on src/a.js (run L)' }, mode);
    assert.deepEqual(res.doc.args.items.map((it) => it.short), [E.slice(0, 8)], mode);
  }
});

test('[08c8b0eb] S8: disjoint files do not defer', () => {
  const snap = claimsSnap({ candidateFiles: ['src/b.js'], claims: ['src/a.js'] });
  assert.deepEqual(crossRunDeferrals(snap).deferred, []);
});

test('[08c8b0eb] S14: with appendOnlyFiles [CHANGELOG.md], two claims of CHANGELOG.md and docs/x.md defer on docs/x.md only', () => {
  const snap = claimsSnap({
    candidateFiles: ['CHANGELOG.md', 'docs/x.md'], claims: ['CHANGELOG.md', 'docs/x.md'], append: ['CHANGELOG.md']
  });
  assert.deepEqual(crossRunDeferrals(snap).deferred, [{ id: C, reason: 'overlap a1a1a1a1 on docs/x.md (run L)' }]);
});

test('[08c8b0eb] S15: a pair that claims only an appendOnlyFiles path is not deferred', () => {
  const snap = claimsSnap({ candidateFiles: ['CHANGELOG.md'], claims: ['CHANGELOG.md'], append: ['CHANGELOG.md'] });
  assert.deepEqual(crossRunDeferrals(snap).deferred, []);
});

test('[08c8b0eb] S15: with an empty appendOnlyFiles list the same CHANGELOG.md pair is deferred (patterns reach the cross-run check)', () => {
  const snap = claimsSnap({ candidateFiles: ['CHANGELOG.md'], claims: ['CHANGELOG.md'], append: [] });
  assert.deepEqual(crossRunDeferrals(snap).deferred, [{ id: C, reason: 'overlap a1a1a1a1 on CHANGELOG.md (run L)' }]);
  const absent = claimsSnap({ candidateFiles: ['CHANGELOG.md'], claims: ['CHANGELOG.md'] });
  assert.deepEqual(crossRunDeferrals(absent).deferred, [{ id: C, reason: 'overlap a1a1a1a1 on CHANGELOG.md (run L)' }]);
});

test('[08c8b0eb] S15: a directory claim "src" overlaps the file "src/a.js"', () => {
  const snap = claimsSnap({ candidateFiles: ['src/a.js'], claims: ['src'] });
  const out = crossRunDeferrals(snap).deferred;
  assert.equal(out.length, 1, JSON.stringify(out));
  assert.equal(out[0].id, C);
  assert.match(out[0].reason, /^overlap a1a1a1a1 on .+ \(run L\)$/);
});

test('[08c8b0eb] S15: a candidate with no scopes entry is never deferred, whatever the claims are', () => {
  const snap = claimsSnap({ candidateFiles: undefined, claims: ['src/a.js', 'CHANGELOG.md'] });
  assert.deepEqual(crossRunDeferrals(snap).deferred, []);
  const res = buildPlanDoc(snap, { now: NOW, mode: 'per-item' });
  assert.equal(res.ok, true, JSON.stringify(res.errors));
  assert.deepEqual(res.doc.args.items.map((it) => it.short).sort(), [C.slice(0, 8), E.slice(0, 8)].sort());
});

test('[08c8b0eb] S15 (probe): a member with no claims entry and a run without a claims key claim nothing', () => {
  const noKey = snapshot({ scopes: { [C]: { files: ['src/a.js'] } }, otherRuns: [other()] });
  assert.deepEqual(crossRunDeferrals(noKey).deferred, []);
  const emptyList = claimsSnap({ candidateFiles: ['src/a.js'], claims: [] });
  assert.deepEqual(crossRunDeferrals(emptyList).deferred, []);
});

test('[08c8b0eb] S15 (probe): a leading ./ and a trailing / in a claim name the same path as the plain spelling', () => {
  for (const claim of ['./src/a.js', 'src/a.js/']) {
    const snap = claimsSnap({ candidateFiles: ['src/a.js'], claims: [claim] });
    const out = crossRunDeferrals(snap).deferred;
    assert.equal(out.length, 1, claim + ' ' + JSON.stringify(out));
    assert.equal(out[0].id, C);
  }
});

test('[08c8b0eb] S15 (probe): the same file spelled with a backslash overlaps', () => {
  const snap = claimsSnap({ candidateFiles: ['src' + BS + 'a.js'], claims: ['src/a.js'] });
  assert.equal(crossRunDeferrals(snap).deferred.length, 1);
});

// ---- S9 (AC4): worktree and branch hints on open and update ----

function cli(args, input) {
  const r = spawnSync(process.execPath, [CLI, ...args], { encoding: 'utf8', input: input === undefined ? undefined : JSON.stringify(input) });
  assert.equal(r.status, 0, r.stderr);
  return JSON.parse(r.stdout);
}

function planFor(mode) {
  const dir = mkdtempSync(join(tmpdir(), 'par-runs-'));
  const snap = JSON.parse(readFileSync(join(FIXTURES, 'independent-two.json'), 'utf8'));
  const doc = cli(['plan', '--now', NOW, '--mode', mode, '--scratchpad', join(dir, 'scratchpad')], snap);
  const planPath = join(dir, 'plan.json');
  writeFileSync(planPath, JSON.stringify(doc));
  const bound = cli(['bind-run', '--in', planPath, '--server-run', SERVER_RUN]);
  const boundPath = join(dir, 'bound.json');
  writeFileSync(boundPath, JSON.stringify(bound));
  return { doc, planPath, boundPath };
}

test('[08c8b0eb] S9: shared mode puts the shared worktree and branch in the open and update hints', () => {
  const { doc, planPath, boundPath } = planFor('shared');
  assert.equal(doc.args.worktreeMode, 'shared');
  const item = doc.args.items[0];
  assert.equal(typeof item.worktree, 'string');
  assert.equal(typeof item.branch, 'string');
  const open = cli(['run-ops', '--plan', planPath, '--phase', 'open']).ops[0];
  assert.equal(open.op, 'open');
  assert.equal(open.hints.worktree, item.worktree);
  assert.equal(open.hints.branch, item.branch);
  assert.equal(open.hints.worktreeMode, 'shared');
  const update = cli(['run-ops', '--plan', boundPath, '--phase', 'update', '--run-phase', 'launched']).ops[0];
  assert.equal(update.op, 'update');
  assert.equal(update.hints.worktree, item.worktree);
  assert.equal(update.hints.branch, item.branch);
  assert.equal(update.hints.phase, 'launched');
  for (const [k, v] of Object.entries(update.hints)) assert.equal(typeof v, 'string', k);
});

test('[08c8b0eb] S9: per-item mode has neither hints.worktree nor hints.branch, on open and update', () => {
  const { doc, planPath, boundPath } = planFor('per-item');
  assert.equal(doc.args.worktreeMode, 'per-item');
  const open = cli(['run-ops', '--plan', planPath, '--phase', 'open']).ops[0];
  const update = cli(['run-ops', '--plan', boundPath, '--phase', 'update', '--run-phase', 'launched']).ops[0];
  for (const op of [open, update]) {
    assert.equal('worktree' in op.hints, false, JSON.stringify(op.hints));
    assert.equal('branch' in op.hints, false, JSON.stringify(op.hints));
    assert.equal(op.hints.worktreeMode, 'per-item');
  }
});

// ---- S13 (AC4): a planned worktree path or branch already in use by another run is refused ----

const SHARED_SNAP = (over = {}) => snapshot({ ids: [C, E], repoRoot: 'C:/repo', ...over });

function derivedPair() {
  const res = buildPlanDoc(SHARED_SNAP(), { now: NOW, mode: 'shared' });
  assert.equal(res.ok, true, JSON.stringify(res.errors));
  const item = res.doc.args.items[0];
  assert.equal(typeof item.worktree, 'string');
  assert.equal(typeof item.branch, 'string');
  return { worktree: item.worktree, branch: item.branch };
}

test('[08c8b0eb] S13: another run on the derived shared path, spelled with backslashes and upper case, refuses the plan naming the run and the worktree', () => {
  const { worktree, branch } = derivedPair();
  const respelled = worktree.split('/').join(BS).toUpperCase();
  assert.notEqual(respelled, worktree);
  const res = buildPlanDoc(SHARED_SNAP({ otherRuns: [other({ worktree: respelled, branch: 'feat/unrelated' })] }), { now: NOW, mode: 'shared' });
  assert.equal(res.ok, false);
  assert.equal(res.refused, true);
  assert.deepEqual(res.errors, [`plan: worktree ${worktree} (branch ${branch}) is in use by open run L; pass --worktree and --branch with a distinct pair, or --mode per-item`]);
});

test('[08c8b0eb] S13: an equal branch alone refuses the plan', () => {
  const { branch } = derivedPair();
  const res = buildPlanDoc(SHARED_SNAP({ otherRuns: [other({ worktree: 'C:/elsewhere/wt', branch })] }), { now: NOW, mode: 'shared' });
  assert.equal(res.ok, false);
  assert.equal(res.refused, true);
  assert.equal(res.errors.length, 1);
  assert.match(res.errors[0], /is in use by open run L; pass --worktree and --branch with a distinct pair, or --mode per-item$/);
});

test('[08c8b0eb] S13: a distinct worktree and branch plans ok, and so does a run with null worktree and branch', () => {
  for (const o of [other({ worktree: 'C:/elsewhere/wt', branch: 'feat/elsewhere' }), other()]) {
    const res = buildPlanDoc(SHARED_SNAP({ otherRuns: [o] }), { now: NOW, mode: 'shared' });
    assert.equal(res.ok, true, JSON.stringify(res));
  }
});

test('[08c8b0eb] S13: passing a distinct --worktree and --branch pair resolves the refusal', () => {
  const { worktree, branch } = derivedPair();
  const snap = SHARED_SNAP({ otherRuns: [other({ worktree, branch })] });
  assert.equal(buildPlanDoc(snap, { now: NOW, mode: 'shared' }).ok, false);
  const res = buildPlanDoc(snap, { now: NOW, mode: 'shared', worktree: 'C:/repo/.claude/worktrees/feat-distinct', branch: 'feat/distinct' });
  assert.equal(res.ok, true, JSON.stringify(res));
});

test('[08c8b0eb] S13: worktreeConflicts returns one message per clash and nothing for a distinct pair', () => {
  const items = [{ worktree: 'C:/repo/wt/one', branch: 'feat/one' }];
  assert.deepEqual(worktreeConflicts(items, [other()]), []);
  assert.deepEqual(worktreeConflicts(items, [other({ worktree: 'c:' + BS + 'REPO' + BS + 'wt' + BS + 'ONE', branch: 'x' })]), [
    'plan: worktree C:/repo/wt/one (branch feat/one) is in use by open run L; pass --worktree and --branch with a distinct pair, or --mode per-item'
  ]);
  assert.equal(worktreeConflicts(items, [other({ worktree: 'C:/other', branch: 'feat/one' })]).length, 1);
});

test('[08c8b0eb] O7: a branch differing from another open run branch only by letter case is refused, worktrees distinct', () => {
  const items = [{ worktree: 'C:/repo/wt/one', branch: 'Feat/x' }];
  assert.deepEqual(worktreeConflicts(items, [other({ worktree: 'C:/other/wt', branch: 'feat/x' })]), [
    'plan: worktree C:/repo/wt/one (branch Feat/x) is in use by open run L; pass --worktree and --branch with a distinct pair, or --mode per-item'
  ]);
  assert.deepEqual(worktreeConflicts(items, [other({ worktree: 'C:/other/wt', branch: 'feat/y' })]), []);
});

// ---- S18 (AC6): the one-wave-per-root wording is gone from the docs ----

const ONE_WAVE = /one wave( runs)? per (project )?root/i;
const DOCS = {
  'run-wave SKILL.md': join(PLUGIN_ROOT, 'skills', 'run-wave', 'SKILL.md'),
  'post-run.md': join(PLUGIN_ROOT, 'skills', 'run-wave', 'references', 'post-run.md'),
  'coordinator-tiers.md': join(PLUGIN_ROOT, 'skills', 'director', 'references', 'coordinator-tiers.md'),
  'purpose.md': join(PLUGIN_ROOT, 'skills', 'director', 'references', 'purpose.md')
};

test('[08c8b0eb] S18: the pattern matches the old spellings (it can fail)', () => {
  for (const old of ['one wave per root', 'One wave runs per project root', 'one wave per project root']) assert.match(old, ONE_WAVE);
});

for (const [name, file] of Object.entries(DOCS)) {
  test(`[08c8b0eb] S18: ${name} states no one-wave-per-root rule`, () => {
    const text = readFileSync(file, 'utf8');
    const hit = ONE_WAVE.exec(text);
    assert.equal(hit, null, hit ? `${name}: ${hit[0]}` : '');
  });
}

test('[08c8b0eb] S18: run-wave SKILL.md keeps the literal hints.plan', () => {
  assert.ok(readFileSync(DOCS['run-wave SKILL.md'], 'utf8').includes('hints.plan'));
});
