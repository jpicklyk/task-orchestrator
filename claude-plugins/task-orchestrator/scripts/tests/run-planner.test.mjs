// Unit + CLI coverage for run-planner-lib.mjs / run-planner.mjs (item 9cdd207d, B2a).
//
// BLIND test author: this file was written from the frozen `test-plan` note on 9cdd207d,
// the b2-b3-front-door.md plan (§3, §4), the master workflows-integration.md (§6.2, §6.3),
// the b2-dispatch-contract.md Appendix C (frozen B2a signatures), and a scanned DECLARATIONS
// file — never from reading run-planner.mjs / run-planner-lib.mjs source. Oracles are cited
// inline as [source] where not obvious from the scenario id.
//
// Scenario ids (S1-S22 + probes) match the item's `test-plan` note.

import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

import {
    SNAPSHOT_CONTRACT,
    PLAN_DOC_MAX_BYTES,
    validateSnapshot,
    compareVersions,
    probeFrom,
    ownership,
    resolveDispatch,
    deriveStages,
    lastWritingWorkSeat,
    classifyEdge,
    orderItems,
    applyCap,
    resourceDeferrals,
    lockSourceDeferrals,
    buildPlanDoc,
    validatePlanDoc,
} from "../run-planner-lib.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const LIB_PATH = path.join(HERE, "..", "run-planner-lib.mjs");
const CLI_PATH = path.join(HERE, "..", "run-planner.mjs");
const FIXTURES = path.join(HERE, "fixtures", "run-planner");

function loadFixture(name) {
    return JSON.parse(readFileSync(path.join(FIXTURES, name), "utf8"));
}
function clone(x) {
    return JSON.parse(JSON.stringify(x));
}
function runCli(args, { input } = {}) {
    return spawnSync(process.execPath, [CLI_PATH, ...args], {
        input: input === undefined ? undefined : typeof input === "string" ? input : JSON.stringify(input),
        encoding: "utf8",
    });
}

const NOW = "2026-09-28T12:00:00Z";
const DISPATCH_DEFAULTS = { queue: "opus", work: "sonnet", extractor: { model: "sonnet", effort: "low" } };

// ── Snapshot-building helpers (fixture invariants: constructed literals must satisfy
//    validateSnapshot's schema by construction; the shipped fixtures are used where they fit). ──

function baseSnapshot(overrides = {}) {
    return {
        contract: SNAPSHOT_CONTRACT,
        rootId: "00000000-0000-4000-8000-000000000000",
        ancestorId: null,
        capturedAt: NOW,
        features: ["seats", "dispatchBySeat", "independent_of", "rules"],
        rulesServed: [{ key: "commit-discipline", rulesVersion: "1" }],
        candidates: [],
        blocked: [],
        schemas: {},
        noteActors: {},
        parents: {},
        profile: {
            shell: "bash",
            verify: [],
            searchScope: ["/repo"],
            worktreeRoot: ".claude/worktrees",
            branchPrefix: { shared: "feat/", perItem: "fix/" },
            maxItems: 5,
            defaultModels: { queue: "opus", work: "sonnet", extractor: { model: "sonnet", effort: "low" } },
        },
        git: { originMain: "e77c3e95", repoRoot: "/repo", worktrees: {} },
        probe: { phase0Hooks: true, pluginVersion: "3.8.0", workflowSizeGuideline: "medium" },
        ...overrides,
    };
}

function plainCandidate({ id, short, parentId = null, priority = "high", complexity = 2, role = "queue", source = "ready", traits = [] }) {
    return {
        id, short, title: `Item ${short}`, priority, complexity, type: "scratch-a", role, parentId, traits,
        hasChildren: false, source,
    };
}

function plainSchema(fingerprint = "fp1") {
    return {
        status: "ok",
        type: "scratch-a",
        configFingerprint: fingerprint,
        configSource: "per-root",
        notes: [
            { key: "specification", role: "queue", required: true, seat: null },
            { key: "session-tracking", role: "work", required: true, seat: null },
        ],
    };
}

function bugFixLikeCandidate({ id, short, parentId, priority = "high", role = "queue", source = "ready" }) {
    return {
        id, short, title: `Item ${short}`, priority, complexity: 2, type: "scratch-bug", role, parentId,
        traits: ["delegated", "session-tracked", "needs-test-author"], hasChildren: false, source,
    };
}

function bugFixLikeSchema(fingerprint = "fp-bug") {
    return {
        status: "ok",
        type: "scratch-bug",
        configFingerprint: fingerprint,
        configSource: "per-root",
        notes: [
            { key: "diagnosis", role: "queue", required: true, seat: "planner" },
            { key: "test-plan", role: "queue", required: true, seat: "planner", skill: "test-author" },
            { key: "implementation-notes", role: "work", required: true, seat: "implementer" },
            { key: "session-tracking", role: "work", required: true, seat: "orchestrator" },
            { key: "test-manifest", role: "work", required: true, seat: "test-author", skill: "test-author" },
        ],
        seats: [
            { name: "planner", phase: "queue" },
            { name: "implementer", phase: "work", enters: true },
            { name: "orchestrator", phase: "work" },
            { name: "test-author", phase: "work", after: ["implementer"], readsExclude: ["implementation-notes", "session-tracking"] },
        ],
        dispatch: { work: { agent: "task-orchestrator:implementer" } },
        dispatchBySeat: { work: { "test-author": { agent: null, model: "sonnet" } } },
    };
}

// ═══════════════════════════════════════════════════════════════════════════
// S1 — orderItems: topological (blockers first), then priority desc,
// complexity asc (null last), id asc. [b2-b3-front-door.md §4.2 step 5]
// ═══════════════════════════════════════════════════════════════════════════

test("S1: orderItems orders by priority desc, complexity asc (null last), id asc", () => {
    const byId = {
        a: { id: "a", priority: "medium", complexity: 5 },
        b: { id: "b", priority: "high", complexity: 3 },
        c: { id: "c", priority: "high", complexity: null },
        d: { id: "d", priority: "high", complexity: 3 },
    };
    const ordered = orderItems(["a", "c", "d", "b"], byId, {});
    // high+complexity3 ties (b,d) -> id asc; high+null sorts after high+3; medium last.
    assert.deepEqual(ordered, ["b", "d", "c", "a"]);
});

test("S1: orderItems places blockers before dependents ahead of priority (topological)", () => {
    const byId = {
        low: { id: "low", priority: "low", complexity: 1 },
        high: { id: "high", priority: "high", complexity: 1 },
    };
    const waitsFor = { high: [{ item: "low", milestone: "implementer" }] };
    const ordered = orderItems(["high", "low"], byId, waitsFor);
    assert.deepEqual(ordered, ["low", "high"]);
});

test("S1 (integration): 2 ready independent items, no edges -> 2 items, no waitsFor, ordered by complexity", () => {
    const snap = loadFixture("independent-two.json");
    const result = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    const items = result.doc.args.items;
    assert.equal(items.length, 2);
    // Item B has complexity 1, Item A has complexity 3; both priority high -> B first.
    assert.deepEqual(items.map((it) => it.short), ["bbbbbbbb", "aaaaaaaa"]);
    for (const it of items) assert.deepEqual(it.waitsFor ?? [], []);
});

// ═══════════════════════════════════════════════════════════════════════════
// S2 — classifyEdge / lastWritingWorkSeat: work-level unsatisfied blocker inside
// the run, same (shared) parent -> in-run, dependent waits on blocker's last
// writing work seat. [master §6.3.2, DEC-9]
// ═══════════════════════════════════════════════════════════════════════════

test("S2: classifyEdge classifies a work-level unsatisfied in-run blocker as in-run, echoing the given milestone", () => {
    const edge = { itemId: "A", role: "work", effectiveUnblockRole: "work", satisfied: false };
    const result = classifyEdge(edge, { runIds: new Set(["A", "B"]), mode: "shared", milestone: "test-author" });
    assert.deepEqual(result, { class: "in-run", item: "A", milestone: "test-author" });
});

test("S2: classifyEdge ignores a satisfied edge", () => {
    const edge = { itemId: "A", role: "work", effectiveUnblockRole: "work", satisfied: true };
    const result = classifyEdge(edge, { runIds: new Set(["A", "B"]), mode: "shared", milestone: "test-author" });
    assert.deepEqual(result, { class: "none" });
});

test("S2: lastWritingWorkSeat returns the last writes:true work-phase stage's seat", () => {
    const stages = [
        { seat: "planner", phase: "queue", writes: false },
        { seat: "implementer", phase: "work", writes: true },
        { seat: "declarations-extractor", phase: "work", writes: false, inserted: true },
        { seat: "test-author", phase: "work", writes: true },
    ];
    assert.equal(lastWritingWorkSeat(stages), "test-author");
});

test("S2: lastWritingWorkSeat returns null when no work stage writes", () => {
    const stages = [{ seat: "planner", phase: "queue", writes: false }];
    assert.equal(lastWritingWorkSeat(stages), null);
});

const A_ID = "aaaaaaaa-1111-4111-8111-111111111111";
const B_ID = "bbbbbbbb-2222-4222-8222-222222222222";
const PARENT_ID = "cccccccc-3333-4333-8333-333333333333";

function s2Snapshot() {
    return baseSnapshot({
        ancestorId: PARENT_ID,
        rulesServed: [
            { key: "commit-discipline", rulesVersion: "1" },
            { key: "declarations-extractor", rulesVersion: "1" },
        ],
        candidates: [
            bugFixLikeCandidate({ id: A_ID, short: "aaaaaaaa", parentId: PARENT_ID }),
            plainCandidate({ id: B_ID, short: "bbbbbbbb", parentId: PARENT_ID }),
        ],
        blocked: [
            {
                itemId: B_ID, role: "queue", blockType: "dependency",
                blockedBy: [{ itemId: A_ID, role: "work", effectiveUnblockRole: "work", satisfied: false }],
            },
        ],
        schemas: { [A_ID]: bugFixLikeSchema(), [B_ID]: plainSchema() },
        parents: { [PARENT_ID]: { role: "work", canAdvance: true, missing: [] } },
    });
}

test("S2 (integration): B waits on A's last writing work seat (test-author); meta.inRunEdges records the edge", () => {
    const result = buildPlanDoc(s2Snapshot(), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    const items = result.doc.args.items;
    assert.deepEqual(items.map((it) => it.short), ["aaaaaaaa", "bbbbbbbb"]); // topological: A before B
    const bItem = items.find((it) => it.short === "bbbbbbbb");
    assert.deepEqual(bItem.waitsFor, [{ item: A_ID, milestone: "test-author" }]);
    const aItem = items.find((it) => it.short === "aaaaaaaa");
    assert.deepEqual(aItem.waitsFor ?? [], []);

    assert.ok(Array.isArray(result.doc.meta.inRunEdges));
    assert.equal(result.doc.meta.inRunEdges.length, 1);
    const edge = result.doc.meta.inRunEdges[0];
    assert.equal(edge.milestone, "test-author");
    assert.deepEqual(new Set([edge.from, edge.to]), new Set([A_ID, B_ID]));
});

// ═══════════════════════════════════════════════════════════════════════════
// S3 — applyCap: drop from the tail; a dropped blocker cascades its dependents;
// reason "run size cap" lands in args.deferred (excluded is reserved for
// explicit/container/config-unavailable per S12). [§4.2 step 6]
// ═══════════════════════════════════════════════════════════════════════════

test("S3: applyCap drops from the tail, cascading dependents", () => {
    const waitsFor = { B: [{ item: "A", milestone: "implementer" }], C: [{ item: "B", milestone: "implementer" }] };
    const capped2 = applyCap(["A", "B", "C"], 2, waitsFor);
    assert.deepEqual(capped2.kept, ["A", "B"]);
    assert.deepEqual(capped2.dropped, [{ id: "C", reason: "run size cap" }]);

    const capped1 = applyCap(["A", "B", "C"], 1, waitsFor);
    assert.deepEqual(capped1.kept, ["A"]);
    assert.deepEqual(new Set(capped1.dropped.map((d) => d.id)), new Set(["B", "C"]));
    for (const d of capped1.dropped) assert.equal(d.reason, "run size cap");
});

const CHAIN_A = "a0000000-0000-4000-8000-000000000000";
const CHAIN_B = "b0000000-0000-4000-8000-000000000000";
const CHAIN_C = "c0000000-0000-4000-8000-000000000000";
const CHAIN_PARENT = "d0000000-0000-4000-8000-000000000000";

function chainSnapshot() {
    return baseSnapshot({
        ancestorId: CHAIN_PARENT,
        candidates: [
            plainCandidate({ id: CHAIN_A, short: "a0000000", parentId: CHAIN_PARENT }),
            plainCandidate({ id: CHAIN_B, short: "b0000000", parentId: CHAIN_PARENT }),
            plainCandidate({ id: CHAIN_C, short: "c0000000", parentId: CHAIN_PARENT }),
        ],
        blocked: [
            { itemId: CHAIN_B, role: "queue", blockType: "dependency", blockedBy: [{ itemId: CHAIN_A, role: "work", effectiveUnblockRole: "work", satisfied: false }] },
            { itemId: CHAIN_C, role: "queue", blockType: "dependency", blockedBy: [{ itemId: CHAIN_B, role: "work", effectiveUnblockRole: "work", satisfied: false }] },
        ],
        schemas: { [CHAIN_A]: plainSchema(), [CHAIN_B]: plainSchema(), [CHAIN_C]: plainSchema() },
        parents: { [CHAIN_PARENT]: { role: "work", canAdvance: true, missing: [] } },
    });
}

test("S3 (integration): chain A->B->C admits all three in topological order at default maxItems", () => {
    const result = buildPlanDoc(chainSnapshot(), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.doc.args.items.map((it) => it.short), ["a0000000", "b0000000", "c0000000"]);
});

test("S3 (integration): chain A->B->C, maxItems 2 drops only C (run size cap, in args.deferred)", () => {
    const result = buildPlanDoc(chainSnapshot(), { now: NOW, maxItems: 2 });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.doc.args.items.map((it) => it.short), ["a0000000", "b0000000"]);
    assert.ok(result.doc.args.deferred.some((d) => d.id === CHAIN_C && d.reason === "run size cap"));
});

test("S3 (integration): chain A->B->C, maxItems 1 drops B and C (cascade)", () => {
    const result = buildPlanDoc(chainSnapshot(), { now: NOW, maxItems: 1 });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.doc.args.items.map((it) => it.short), ["a0000000"]);
    const deferredIds = result.doc.args.deferred.map((d) => d.id);
    assert.ok(deferredIds.includes(CHAIN_B));
    assert.ok(deferredIds.includes(CHAIN_C));
});

// ═══════════════════════════════════════════════════════════════════════════
// S4 — deriveStages: mixed seat-aware stage derivation, extractor insertion,
// orchestrator seat excluded from stages. [§4.3 S5, S6, OQ-1]
// ═══════════════════════════════════════════════════════════════════════════

function mixedTypesFixture() {
    return loadFixture("mixed-types.json");
}

test("S4: deriveStages on a seat-aware bug-fix-like schema produces planner, implementer, extractor, test-author; orchestrator seat excluded from stages", () => {
    const snap = mixedTypesFixture();
    const candidate = snap.candidates[0]; // scratch-bug, seat-aware, needs-test-author
    const schemaEntry = snap.schemas[candidate.id];
    const result = deriveStages(candidate, schemaEntry, { rulesServed: snap.rulesServed, noteActors: [], profile: snap.profile });

    assert.equal(result.exclude, null);
    assert.equal(result.schemaFree, false);
    const seats = result.stages.map((s) => s.seat);
    assert.deepEqual(seats, ["planner", "implementer", "declarations-extractor", "test-author"]);
    assert.ok(!seats.includes("orchestrator"), "orchestrator must never be a stage");
    assert.deepEqual(result.orchestratorNotes, ["session-tracking"]);

    const extractor = result.stages.find((s) => s.seat === "declarations-extractor");
    assert.equal(extractor.inserted, true);
    assert.equal(extractor.writes, false);
    assert.equal(extractor.output, "declarations-v1");
    const testAuthorIdx = result.stages.findIndex((s) => s.seat === "test-author");
    const extractorIdx = result.stages.findIndex((s) => s.seat === "declarations-extractor");
    assert.ok(extractorIdx < testAuthorIdx, "extractor must be inserted immediately before test-author");

    const implementer = result.stages.find((s) => s.seat === "implementer");
    assert.equal(implementer.enters, true);
    assert.equal(implementer.writes, true);
    assert.equal(implementer.output, "implementer-v1");
});

test("S4 / P9: run-planner-lib.mjs contains no per-type name branches", () => {
    const text = readFileSync(LIB_PATH, "utf8");
    for (const forbidden of ["bug-fix", "feature-task", "plugin-change"]) {
        assert.ok(!text.includes(forbidden), `lib text must not reference type name "${forbidden}"`);
    }
});

// ═══════════════════════════════════════════════════════════════════════════
// S5 — resolveDispatch: dispatchBySeat > dispatch[phase] (entry/implicit only)
// > defaults, field-wise; explicit null agent kept; model always set.
// [Appendix C, DEC "S9 dispatch"]
// ═══════════════════════════════════════════════════════════════════════════

test("S5: resolveDispatch — dispatchBySeat wins for a non-entry seat (live 01bb5ffb test-author)", () => {
    const snap = loadFixture("unowned-note.json");
    const schemaEntry = snap.schemas["01bb5ffb-56f8-4886-ab2e-2659627e35a9"];
    const result = resolveDispatch(schemaEntry, "test-author", "work", { enters: false, implicitOwner: false }, DISPATCH_DEFAULTS);
    assert.deepEqual(result, { agent: null, model: "sonnet" });
});

test("S5: resolveDispatch — dispatch[phase] applies only to the entry/implicitOwner seat", () => {
    const schemaEntry = bugFixLikeSchema();
    const entrySeatResult = resolveDispatch(schemaEntry, "implementer", "work", { enters: true, implicitOwner: false }, DISPATCH_DEFAULTS);
    assert.equal(entrySeatResult.agent, "task-orchestrator:implementer");
    assert.equal(entrySeatResult.model, "sonnet"); // from defaults.work, dispatch.work has no model

    // A non-entry, non-implicit-owner seat must NOT inherit dispatch[phase].agent.
    const otherSeatResult = resolveDispatch(schemaEntry, "orchestrator", "work", { enters: false, implicitOwner: false }, DISPATCH_DEFAULTS);
    assert.notEqual(otherSeatResult.agent, "task-orchestrator:implementer");
});

test("S5: resolveDispatch falls back to defaults when neither dispatchBySeat nor dispatch[phase] applies; model always set", () => {
    const schemaEntry = { status: "ok" }; // no dispatch/dispatchBySeat at all
    const result = resolveDispatch(schemaEntry, "planner", "queue", { enters: false, implicitOwner: false }, DISPATCH_DEFAULTS);
    assert.equal(result.model, "opus"); // defaults.queue
    assert.ok(result.agent === null || result.agent === undefined);
});

test("S5: resolveDispatch preserves an explicit null agent from dispatchBySeat", () => {
    const schemaEntry = { dispatchBySeat: { work: { "test-author": { agent: null, model: "sonnet" } } } };
    const result = resolveDispatch(schemaEntry, "test-author", "work", { enters: false, implicitOwner: false }, DISPATCH_DEFAULTS);
    assert.equal(result.agent, null);
    assert.equal(result.model, "sonnet");
});

// ═══════════════════════════════════════════════════════════════════════════
// S6 — rules vs skills: a note's `skill` pointer resolves to rules[] when the
// key is in rulesServed, else skills[]; extractor rules ["declarations-extractor"]
// iff served, else a degradation. [§4.3 S7/S8 rules vs skills, OQ-1]
// ═══════════════════════════════════════════════════════════════════════════

test("S6: deriveStages routes a note's skill pointer to rules[] when served, skills[] otherwise", () => {
    const snap = mixedTypesFixture();
    const candidate = snap.candidates[0];
    const schemaEntry = snap.schemas[candidate.id];

    const servedResult = deriveStages(candidate, schemaEntry, {
        rulesServed: [{ key: "test-author", rulesVersion: "1" }, { key: "commit-discipline", rulesVersion: "1" }],
        noteActors: [], profile: snap.profile,
    });
    const plannerServed = servedResult.stages.find((s) => s.seat === "planner");
    assert.ok(plannerServed.rules.includes("test-author"));
    assert.ok(!(plannerServed.skills ?? []).includes("test-author"));

    const unservedResult = deriveStages(candidate, schemaEntry, {
        rulesServed: [{ key: "commit-discipline", rulesVersion: "1" }],
        noteActors: [], profile: snap.profile,
    });
    const plannerUnserved = unservedResult.stages.find((s) => s.seat === "planner");
    assert.ok(!(plannerUnserved.rules ?? []).includes("test-author"));
    assert.ok(plannerUnserved.skills.includes("test-author"));
});

test("S6: extractor gets rules:['declarations-extractor'] when served, else a degradation and no rule", () => {
    const snap = mixedTypesFixture();
    const candidate = snap.candidates[0];
    const schemaEntry = snap.schemas[candidate.id];

    const served = deriveStages(candidate, schemaEntry, {
        rulesServed: [{ key: "declarations-extractor", rulesVersion: "1" }],
        noteActors: [], profile: snap.profile,
    });
    const extractorServed = served.stages.find((s) => s.seat === "declarations-extractor");
    assert.deepEqual(extractorServed.rules, ["declarations-extractor"]);
    assert.ok(!served.degradations.includes("extractor without accuracy rule"));

    const unserved = deriveStages(candidate, schemaEntry, {
        rulesServed: [{ key: "commit-discipline", rulesVersion: "1" }],
        noteActors: [], profile: snap.profile,
    });
    const extractorUnserved = unserved.stages.find((s) => s.seat === "declarations-extractor");
    assert.deepEqual(extractorUnserved.rules, []);
    assert.ok(unserved.degradations.includes("extractor without accuracy rule"));
});

// ═══════════════════════════════════════════════════════════════════════════
// S7 — args assembly: traits === itemTraits === the candidate's raw traits.
// [§4.4, Appendix C "traits", D5/S12]
// ═══════════════════════════════════════════════════════════════════════════

test("S7 (integration): args items' traits and itemTraits both equal the candidate's raw traits array", () => {
    const snap = mixedTypesFixture();
    const result = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    const bugItem = result.doc.args.items.find((it) => it.short === "11111111");
    assert.deepEqual(bugItem.traits, ["delegated", "session-tracked", "needs-test-author"]);
    assert.deepEqual(bugItem.itemTraits, ["delegated", "session-tracked", "needs-test-author"]);
    assert.deepEqual(bugItem.traits, bugItem.itemTraits);
    assert.equal(bugItem.configFingerprint, "fp-bug");
    assert.equal(bugItem.configSource, "per-root");
});

// ═══════════════════════════════════════════════════════════════════════════
// S8 — args validity: produced args satisfy the frozen B1 §3.1 schema fixture.
// [Appendix C args-v1.schema.json, B1 plan §3.1]
// ═══════════════════════════════════════════════════════════════════════════

test("S8: buildPlanDoc's args satisfy the frozen args-v1 schema fixture (validatePlanDoc ok:true, errors:[])", () => {
    const argsSchema = loadFixture("args-v1.schema.json");
    const snap = loadFixture("independent-two.json");
    const result = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    const validation = validatePlanDoc(result.doc, argsSchema);
    assert.deepEqual(validation, { ok: true, errors: [] });
});

test("S8: runId matches the pattern; short is 8 hex chars; worktree is absolute and forward-slashed; unownedRequired is []", () => {
    const snap = loadFixture("independent-two.json");
    const result = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.match(result.doc.args.runId, /^r-[A-Za-z0-9-]{4,40}$/);
    for (const item of result.doc.args.items) {
        assert.match(item.short, /^[0-9a-f]{8}$/);
        assert.match(item.worktree, /^([A-Za-z]:)?\/.*$/);
        assert.ok(!item.worktree.includes("\\"), "worktree must be forward-slashed");
        assert.deepEqual(item.unownedRequired ?? [], []);
    }
});

// ═══════════════════════════════════════════════════════════════════════════
// S9 — a review/terminal-threshold blocker is always cross-run, even in-run,
// with reason "blocked by <short> until <role> (cross-run)" in args.deferred.
// [§6.3.2 edge table]
// ═══════════════════════════════════════════════════════════════════════════

const S9_ADMITTED_ID = "90000000-0000-4000-8000-000000000000";
const S9_DEFERRED_ID = "91000000-0000-4000-8000-000000000000";
const S9_BLOCKER_ID = "92000000-0000-4000-8000-000000000000";

function s9Snapshot() {
    // Two candidates so the run is non-empty (a lone cross-run-deferred candidate would trigger
    // the separate "zero admitted" refusal, covered by S16 instead of this reason text).
    return baseSnapshot({
        candidates: [
            plainCandidate({ id: S9_ADMITTED_ID, short: "90000000" }),
            plainCandidate({ id: S9_DEFERRED_ID, short: "91000000" }),
        ],
        blocked: [
            {
                itemId: S9_DEFERRED_ID, role: "queue", blockType: "dependency",
                blockedBy: [{ itemId: S9_BLOCKER_ID, role: "review", effectiveUnblockRole: "terminal", satisfied: false }],
            },
        ],
        schemas: { [S9_ADMITTED_ID]: plainSchema(), [S9_DEFERRED_ID]: plainSchema() },
    });
}

test("S9 (integration): terminal-threshold blocker -> deferred 'blocked by <short> until terminal (cross-run)'", () => {
    const result = buildPlanDoc(s9Snapshot(), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.doc.args.items.map((it) => it.short), ["90000000"]);
    assert.deepEqual(result.doc.args.deferred, [
        { id: S9_DEFERRED_ID, reason: "blocked by 92000000 until terminal (cross-run)" },
    ]);
});

test("S9 (CLI, zero-admitted variant): a lone terminal-threshold-blocked candidate surfaces the same reason in meta.deferred on refusal", () => {
    const snap = loadFixture("cross-run-edge-terminal.json");
    const res = runCli(["plan"], { input: JSON.stringify(snap) });
    assert.equal(res.status, 3);
    const doc = JSON.parse(res.stdout);
    assert.equal(doc.args, null);
    assert.deepEqual(doc.meta.deferred, [
        { id: "bbbbbbbb-2222-4222-8222-222222222222", reason: "blocked by aaaaaaaa until terminal (cross-run)" },
    ]);
});

// ═══════════════════════════════════════════════════════════════════════════
// S10 — the same work-level edge shape as S2, but the blocker and dependent
// have different parents (per-item mode) -> cross-run (DEC-9).
// ═══════════════════════════════════════════════════════════════════════════

test("S10 (integration): work-level edge, different parents (per-item mode) -> cross-run deferral, not in-run", () => {
    const snap = loadFixture("per-item-overlap.json");
    const result = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    const shorts = result.doc.args.items.map((it) => it.short);
    assert.deepEqual(shorts, ["aaaaaaaa"]); // A admitted, B deferred
    assert.deepEqual(result.doc.args.deferred, [
        { id: "bbbbbbbb-2222-4222-8222-222222222222", reason: "blocked by aaaaaaaa until work (cross-run)" },
    ]);
    assert.deepEqual(result.doc.meta.inRunEdges ?? [], []);
});

// ═══════════════════════════════════════════════════════════════════════════
// S11 — a blocker outside the run set (not itself a candidate) defers the
// dependent; a satisfied edge on another candidate is ignored.
// ═══════════════════════════════════════════════════════════════════════════

const EXTERNAL_ID = "e0000000-0000-4000-8000-000000000000";
const S11_C_ID = "c1000000-0000-4000-8000-000000000000";
const S11_D_ID = "d1000000-0000-4000-8000-000000000000";
const S11_E_ID = "e1000000-0000-4000-8000-000000000000";

function s11Snapshot() {
    return baseSnapshot({
        candidates: [
            plainCandidate({ id: S11_C_ID, short: "c1000000" }),
            plainCandidate({ id: S11_D_ID, short: "d1000000" }),
        ],
        blocked: [
            {
                itemId: S11_C_ID, role: "queue", blockType: "dependency",
                blockedBy: [{ itemId: EXTERNAL_ID, role: "work", effectiveUnblockRole: "work", satisfied: false }],
            },
            {
                itemId: S11_D_ID, role: "queue", blockType: "dependency",
                blockedBy: [{ itemId: S11_E_ID, role: "work", effectiveUnblockRole: "work", satisfied: true }],
            },
        ],
        schemas: { [S11_C_ID]: plainSchema(), [S11_D_ID]: plainSchema() },
    });
}

test("S11 (integration): blocker outside the run set defers the dependent; satisfied edges are ignored", () => {
    const result = buildPlanDoc(s11Snapshot(), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    const shorts = result.doc.args.items.map((it) => it.short);
    assert.deepEqual(shorts, ["d1000000"]); // D admitted (satisfied edge ignored)
    assert.deepEqual(result.doc.args.deferred, [
        { id: S11_C_ID, reason: "blocked by e0000000 until work (cross-run)" },
    ]);
    const dItem = result.doc.args.items.find((it) => it.short === "d1000000");
    assert.deepEqual(dItem.waitsFor ?? [], []);
});

// ═══════════════════════════════════════════════════════════════════════════
// S12 — explicit block / container / config-unavailable exclusions land in
// meta.excluded, never in args.deferred. [§4.2 step 1-2, S12 test-plan text]
// ═══════════════════════════════════════════════════════════════════════════

const S12_ADMITTED_ID = "f0000000-0000-4000-8000-000000000000";
const S12_EXPLICIT_ID = "f1000000-0000-4000-8000-000000000000";
const S12_BLOCKER_ID = "f2000000-0000-4000-8000-000000000000";
const S12_CONTAINER_ID = "f3000000-0000-4000-8000-000000000000";
const S12_UNAVAILABLE_ID = "f4000000-0000-4000-8000-000000000000";

function s12Snapshot() {
    return baseSnapshot({
        candidates: [
            // A normally-admitted candidate keeps the run non-empty, so the three exclusions
            // below surface in meta.excluded instead of tripping the separate "zero admitted"
            // refusal (covered by S16).
            plainCandidate({ id: S12_ADMITTED_ID, short: "f0000000" }),
            plainCandidate({ id: S12_EXPLICIT_ID, short: "f1000000" }),
            { ...plainCandidate({ id: S12_CONTAINER_ID, short: "f3000000" }), hasChildren: true },
            plainCandidate({ id: S12_UNAVAILABLE_ID, short: "f4000000" }),
        ],
        blocked: [
            {
                itemId: S12_EXPLICIT_ID, role: "queue", blockType: "explicit",
                blockedBy: [{ itemId: S12_BLOCKER_ID, role: "work", effectiveUnblockRole: "work", satisfied: false }],
            },
        ],
        schemas: {
            [S12_ADMITTED_ID]: plainSchema(),
            [S12_EXPLICIT_ID]: plainSchema(),
            [S12_CONTAINER_ID]: plainSchema(),
            [S12_UNAVAILABLE_ID]: { status: "unavailable" },
        },
    });
}

test("S12 (integration): explicit block, container, and config-unavailable are all excluded (meta.excluded), never deferred", () => {
    const result = buildPlanDoc(s12Snapshot(), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.doc.args.items.map((it) => it.short), ["f0000000"]);
    assert.deepEqual(result.doc.args.deferred, []);

    const excluded = result.doc.meta.excluded;
    assert.ok(excluded.some((e) => e.id === S12_EXPLICIT_ID && e.reason === "explicitly blocked"));
    assert.ok(excluded.some((e) => e.id === S12_CONTAINER_ID && e.reason === "container"));
    assert.ok(excluded.some((e) => e.id === S12_UNAVAILABLE_ID && e.reason === "config-unavailable"));
});

// ═══════════════════════════════════════════════════════════════════════════
// S13 — ownership: a required note whose seat is null, undeclared, or declared
// for another phase excludes the item (DEC-11); optional notes are ignored.
// Live 01bb5ffb (unowned-note.json). [Appendix C correction (1)]
// ═══════════════════════════════════════════════════════════════════════════

test("S13: ownership — a required note with seat:null, an undeclared seat, or another-phase seat is unowned (schema order); optional notes ignored", () => {
    const snap = loadFixture("unowned-note.json");
    const schemaEntry = snap.schemas["01bb5ffb-56f8-4886-ab2e-2659627e35a9"];
    const result = ownership(schemaEntry, ["queue", "work", "review"]);
    // schema.notes order: specification, session-tracking, delegation-metadata(optional),
    // test-plan, test-manifest(owned by declared seat test-author), test-independence-audit.
    // Only "test-author" is declared in seats[]; "planner"/"orchestrator"/"reviewer" are not.
    assert.deepEqual(result.unowned, ["specification", "session-tracking", "test-plan", "test-independence-audit"]);
});

const S13_ADMITTED_ID = "93000000-0000-4000-8000-000000000000";

test("S13 (integration): unowned required note excludes the item from the run (meta.excluded), not args.items", () => {
    const snap = loadFixture("unowned-note.json");
    // Add a normally-admitted candidate so the run is non-empty (the fixture's sole candidate
    // is itself the unowned-note exclusion, which alone would trip the "zero admitted" refusal
    // covered by S16 rather than surface meta.excluded for inspection here).
    snap.candidates.push(plainCandidate({ id: S13_ADMITTED_ID, short: "93000000" }));
    snap.schemas[S13_ADMITTED_ID] = plainSchema();
    const result = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.doc.args.items.map((it) => it.short), ["93000000"]);
    const entry = result.doc.meta.excluded.find((e) => e.id === "01bb5ffb-56f8-4886-ab2e-2659627e35a9");
    assert.ok(entry, "the item must be excluded");
    assert.match(entry.reason, /^unowned required note\(s\)/);
    assert.match(entry.reason, /specification/);
    assert.match(entry.reason, /session-tracking/);
    assert.match(entry.reason, /test-plan/);
    assert.match(entry.reason, /test-independence-audit/);
    assert.ok(!entry.reason.includes("test-manifest"), "test-manifest is owned by the declared test-author seat");
    assert.ok(!entry.reason.includes("delegation-metadata"), "delegation-metadata is optional, must be ignored");
});

// ═══════════════════════════════════════════════════════════════════════════
// S14 — resourceDeferrals: exclusive keys admit the highest-priority item;
// others deferred "resource <key>"; advisory keys recorded, not gated.
// ═══════════════════════════════════════════════════════════════════════════

test("S14: resourceDeferrals admits the higher-priority holder of an exclusive key; defers the other; records advisory", () => {
    const snap = loadFixture("exclusive-resource.json");
    const aId = "aaaaaaaa-1111-4111-8111-111111111111";
    const bId = "bbbbbbbb-2222-4222-8222-222222222222";
    const result = resourceDeferrals([aId, bId], snap.schemas);
    assert.deepEqual(result.kept, [aId]);
    assert.deepEqual(result.deferred, [{ id: bId, reason: "resource scratch-db" }]);
    assert.deepEqual(result.advisory, [{ id: aId, key: "notes-index" }]);
});

test("S14 (integration): exclusive resource contention -> higher priority admitted, other deferred 'resource <key>'", () => {
    const snap = loadFixture("exclusive-resource.json");
    const result = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.doc.args.items.map((it) => it.short), ["aaaaaaaa"]);
    assert.ok(result.doc.args.deferred.some((d) => d.id === "bbbbbbbb-2222-4222-8222-222222222222" && d.reason === "resource scratch-db"));
    assert.ok(result.doc.meta.advisoryResources.some((a) => a.id === "aaaaaaaa-1111-4111-8111-111111111111" && a.key === "notes-index"));
});

// ═══════════════════════════════════════════════════════════════════════════
// S15 — validatePlanDoc: bad contract, missing short, and (with a snapshot)
// configFingerprint / traits / not-a-candidate drift. [Appendix C, plan-doc-stale fixture]
// ═══════════════════════════════════════════════════════════════════════════

test("S15: validatePlanDoc — bad top-level contract is an error", () => {
    const argsSchema = loadFixture("args-v1.schema.json");
    const snap = loadFixture("independent-two.json");
    const built = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(built.ok, true);
    const bad = clone(built.doc);
    bad.contract = "run-wave/wrong-contract";
    const result = validatePlanDoc(bad, argsSchema);
    assert.equal(result.ok, false);
    assert.ok(result.errors.length > 0);
});

test("S15: validatePlanDoc — a missing item.short is an error", () => {
    const argsSchema = loadFixture("args-v1.schema.json");
    const snap = loadFixture("independent-two.json");
    const built = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(built.ok, true);
    const bad = clone(built.doc);
    delete bad.args.items[0].short;
    const result = validatePlanDoc(bad, argsSchema);
    assert.equal(result.ok, false);
    assert.ok(result.errors.length > 0);
});

test("S15: validatePlanDoc with a snapshot flags a configFingerprint drift, naming the short", () => {
    const argsSchema = loadFixture("args-v1.schema.json");
    const staleDoc = loadFixture("plan-doc-stale.json");
    // "Fresh" snapshot: both items now resolve to fp1 (item bbbbbbbb's plan doc says "stale-fingerprint").
    const freshSnapshot = baseSnapshot({
        candidates: [
            plainCandidate({ id: "bbbbbbbb-2222-4222-8222-222222222222", short: "bbbbbbbb" }),
            plainCandidate({ id: "aaaaaaaa-1111-4111-8111-111111111111", short: "aaaaaaaa" }),
        ],
        schemas: {
            "bbbbbbbb-2222-4222-8222-222222222222": plainSchema("fp1"),
            "aaaaaaaa-1111-4111-8111-111111111111": plainSchema("fp1"),
        },
    });
    const result = validatePlanDoc(staleDoc, argsSchema, freshSnapshot);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("bbbbbbbb") && e.includes("configFingerprint")));
});

test("S15: validatePlanDoc with a snapshot flags 'not a candidate' when a plan item is absent from the snapshot", () => {
    const argsSchema = loadFixture("args-v1.schema.json");
    const staleDoc = loadFixture("plan-doc-stale.json");
    const emptySnapshot = baseSnapshot({ candidates: [], schemas: {} });
    const result = validatePlanDoc(staleDoc, argsSchema, emptySnapshot);
    assert.equal(result.ok, false);
    assert.ok(result.errors.some((e) => e.includes("bbbbbbbb") && e.includes("not a candidate")));
    assert.ok(result.errors.some((e) => e.includes("aaaaaaaa") && e.includes("not a candidate")));
});

test("S15: validatePlanDoc — a fresh, matching plan validates ok:true, errors:[]", () => {
    const argsSchema = loadFixture("args-v1.schema.json");
    const snap = loadFixture("independent-two.json");
    const built = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(built.ok, true);
    const result = validatePlanDoc(built.doc, argsSchema, snap);
    assert.deepEqual(result, { ok: true, errors: [] });
});

// ═══════════════════════════════════════════════════════════════════════════
// S16 — CLI: bad JSON / bad contract -> exit 2 with {error,detail} on stderr;
// zero admitted / oversize / forced seat entry without phase0Hooks -> exit 3.
// ═══════════════════════════════════════════════════════════════════════════

test("S16: CLI plan with malformed JSON on stdin -> exit 2, stderr is {error,detail}", () => {
    const res = runCli(["plan"], { input: "{not valid json" });
    assert.equal(res.status, 2);
    const parsed = JSON.parse(res.stderr);
    assert.ok(typeof parsed.error === "string");
    assert.ok("detail" in parsed);
});

test("S16: CLI plan with a wrong snapshot contract -> exit 2", () => {
    const snap = loadFixture("independent-two.json");
    snap.contract = "run-wave/not-a-snapshot";
    const res = runCli(["plan"], { input: JSON.stringify(snap) });
    assert.equal(res.status, 2);
    const parsed = JSON.parse(res.stderr);
    assert.ok(typeof parsed.error === "string");
});

test("S16: CLI plan with zero admitted items -> exit 3, stdout carries the doc with args:null, stderr names the refusal", () => {
    const snap = loadFixture("cross-run-edge-terminal.json"); // only candidate is cross-run deferred
    const res = runCli(["plan"], { input: JSON.stringify(snap) });
    assert.equal(res.status, 3);
    const doc = JSON.parse(res.stdout);
    assert.equal(doc.contract, "run-wave/plan-doc-v1");
    assert.equal(doc.args, null);
    const stderrParsed = JSON.parse(res.stderr);
    assert.equal(stderrParsed.error, "refused");
    assert.ok(stderrParsed.detail.some((d) => d.includes("no items admitted")));
});

test("S16: CLI plan over PLAN_DOC_MAX_BYTES -> exit 3", () => {
    const snap = loadFixture("independent-two.json");
    // Inflate the project profile far past the 61440-byte cap without changing item count.
    snap.profile.searchScope = Array.from({ length: 900 }, (_, i) => `/repo/very/long/search/scope/entry/number/${i}/${"x".repeat(60)}`);
    const res = runCli(["plan"], { input: JSON.stringify(snap) });
    assert.equal(res.status, 3);
});

test("S16: CLI plan forcing seat entry without phase0Hooks -> exit 3, 'seat entry requires phase0Hooks'", () => {
    const snap = loadFixture("independent-two.json");
    snap.probe.phase0Hooks = false;
    const res = runCli(["plan", "--entry", "seat"], { input: JSON.stringify(snap) });
    assert.equal(res.status, 3);
    const parsed = JSON.parse(res.stdout);
    assert.equal(parsed.ok, false);
    assert.ok(parsed.errors.some((e) => e.includes("seat entry requires phase0Hooks")));
});

test("S16: CLI plan succeeds (exit 0) on a valid snapshot and emits a valid plan-doc-v1 on stdout", () => {
    const snap = loadFixture("independent-two.json");
    const res = runCli(["plan", "--now", NOW], { input: JSON.stringify(snap) });
    assert.equal(res.status, 0, res.stderr);
    const doc = JSON.parse(res.stdout);
    assert.equal(doc.contract, "run-wave/plan-doc-v1");
    assert.equal(doc.args.items.length, 2);
});

// ═══════════════════════════════════════════════════════════════════════════
// S17 — schema-free (not-found): single implicit implementer stage, schemaFree,
// fingerprint null, warning naming /manage-schemas. [§4.3 S2]
// ═══════════════════════════════════════════════════════════════════════════

test("S17: deriveStages on a not-found schema gives a single implementer stage, schemaFree, and a /manage-schemas warning", () => {
    const snap = loadFixture("schema-free.json");
    const candidate = snap.candidates[0];
    const schemaEntry = snap.schemas[candidate.id];
    const result = deriveStages(candidate, schemaEntry, { rulesServed: [], noteActors: [], profile: snap.profile });

    assert.equal(result.schemaFree, true);
    assert.equal(result.stages.length, 1);
    const stage = result.stages[0];
    assert.equal(stage.seat, "implementer");
    assert.equal(stage.phase, "work");
    assert.equal(stage.enters, true);
    assert.equal(stage.implicitOwner, true);
    assert.deepEqual(stage.notes, []);
    assert.equal(stage.writes, true);
    assert.equal(stage.output, "implementer-v1");
    assert.ok(result.warnings.some((w) => w.includes("/manage-schemas")));
});

test("S17 (integration): schema-free item's plan-doc item has configFingerprint null and schemaFree true", () => {
    const snap = loadFixture("schema-free.json");
    const result = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.equal(result.doc.args.items.length, 1);
    const item = result.doc.args.items[0];
    assert.equal(item.schemaFree, true);
    assert.equal(item.configFingerprint, null);
});

// ═══════════════════════════════════════════════════════════════════════════
// S18 — resume in work: queue stages skipped; a seat whose notes were all
// already written under its own "<seat>:<short>:*" actor is skipped; a
// shared-mode resumed item with no planner-v1 stage defers unless it is the
// run's only writer. [§4.3 S4 resume; Appendix C shared-mode lock source]
// ═══════════════════════════════════════════════════════════════════════════

test("S18: deriveStages on a work-role item skips the queue stage and any seat already written under its own actor", () => {
    const snap = mixedTypesFixture();
    const candidate = { ...snap.candidates[0], role: "work", source: "work" };
    const schemaEntry = snap.schemas[candidate.id];
    const noteActors = [{ key: "implementation-notes", actorId: `implementer:${candidate.short}:r-prior` }];
    const result = deriveStages(candidate, schemaEntry, { rulesServed: snap.rulesServed, noteActors, profile: snap.profile });

    const seats = result.stages.map((s) => s.seat);
    assert.ok(!seats.includes("planner"), "queue stage must be skipped when the item is already in work");
    assert.ok(!seats.includes("implementer"), "a seat whose notes are all already written under its own actor is skipped");
    assert.ok(seats.includes("test-author"), "test-author's note is not yet written, so it must remain");
    assert.ok(seats.includes("declarations-extractor"), "the extractor is still inserted before the remaining test-author seat");
});

test("S18: lockSourceDeferrals defers a shared-mode resumed item with no planner-v1 stage when another writer is present", () => {
    const resumedNoPlanner = { id: "x1", stages: [{ seat: "test-author", phase: "work", writes: true, output: "test-author-v1" }] };
    const otherWriter = { id: "x2", stages: [{ seat: "planner", phase: "queue", writes: false, output: "planner-v1" }, { seat: "implementer", phase: "work", writes: true, output: "implementer-v1" }] };
    const derivedById = { x1: resumedNoPlanner, x2: otherWriter };
    const result = lockSourceDeferrals(["x1", "x2"], derivedById, "shared");
    assert.deepEqual(result.kept, ["x2"]);
    assert.deepEqual(result.deferred, [{ id: "x1", reason: "no planning stage for lock keys" }]);
});

test("S18: lockSourceDeferrals keeps a resumed item with no planner-v1 stage when it is the run's only writer", () => {
    const resumedNoPlanner = { id: "x1", stages: [{ seat: "test-author", phase: "work", writes: true, output: "test-author-v1" }] };
    const derivedById = { x1: resumedNoPlanner };
    const result = lockSourceDeferrals(["x1"], derivedById, "shared");
    assert.deepEqual(result.kept, ["x1"]);
    assert.deepEqual(result.deferred, []);
});

test("S18: lockSourceDeferrals is a no-op in per-item mode", () => {
    const resumedNoPlanner = { id: "x1", stages: [{ seat: "test-author", phase: "work", writes: true, output: "test-author-v1" }] };
    const otherWriter = { id: "x2", stages: [{ seat: "implementer", phase: "work", writes: true, output: "implementer-v1" }] };
    const derivedById = { x1: resumedNoPlanner, x2: otherWriter };
    const result = lockSourceDeferrals(["x1", "x2"], derivedById, "per-item");
    assert.deepEqual(result.kept, ["x1", "x2"]);
    assert.deepEqual(result.deferred, []);
});

// ═══════════════════════════════════════════════════════════════════════════
// S19 — pre-entered mode: waitsFor cleared for every item; S2's in-run
// dependent B becomes cross-run deferred instead. [§4.2 step 7, F-7]
// ═══════════════════════════════════════════════════════════════════════════

test("S19 (integration): pre-entered mode clears waitsFor and turns S2's in-run edge cross-run", () => {
    const result = buildPlanDoc(s2Snapshot(), { now: NOW, entry: "pre-entered" });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    for (const item of result.doc.args.items) {
        assert.deepEqual(item.waitsFor ?? [], []);
    }
    assert.deepEqual(result.doc.args.items.map((it) => it.short), ["aaaaaaaa"]);
    assert.deepEqual(result.doc.args.deferred, [
        { id: B_ID, reason: "blocked by aaaaaaaa until work (cross-run)" },
    ]);
    assert.equal(result.doc.args.entryMode, "pre-entered");
});

// ═══════════════════════════════════════════════════════════════════════════
// S20 — the declarations-extractor is inserted ONLY before a work seat that has
// BOTH a non-empty readsExclude AND a note whose skill is "test-author".
// Either condition alone must not trigger insertion.
// ═══════════════════════════════════════════════════════════════════════════

function schemaWithSeat(seatExtra, noteExtra) {
    return {
        status: "ok", type: "scratch-x", configFingerprint: "fpx", configSource: "per-root",
        notes: [
            { key: "implementation-notes", role: "work", required: true, seat: "implementer" },
            { key: "review-note", role: "work", required: true, seat: "reviewer2", ...noteExtra },
        ],
        seats: [
            { name: "implementer", phase: "work", enters: true },
            { name: "reviewer2", phase: "work", after: ["implementer"], ...seatExtra },
        ],
    };
}

test("S20: extractor is NOT inserted when readsExclude is present but no owned note has skill:test-author", () => {
    const candidate = { id: "z1", short: "z1000000", title: "Z", priority: "high", complexity: 1, type: "scratch-x", role: "queue", parentId: null, traits: [], hasChildren: false, source: "ready" };
    const schemaEntry = schemaWithSeat({ readsExclude: ["implementation-notes"] }, {});
    const result = deriveStages(candidate, schemaEntry, { rulesServed: [{ key: "declarations-extractor", rulesVersion: "1" }], noteActors: [], profile: { defaultModels: DISPATCH_DEFAULTS } });
    assert.ok(!result.stages.some((s) => s.seat === "declarations-extractor"));
});

test("S20: extractor is NOT inserted when a note has skill:test-author but readsExclude is empty", () => {
    const candidate = { id: "z2", short: "z2000000", title: "Z", priority: "high", complexity: 1, type: "scratch-x", role: "queue", parentId: null, traits: [], hasChildren: false, source: "ready" };
    const schemaEntry = schemaWithSeat({}, { skill: "test-author" });
    const result = deriveStages(candidate, schemaEntry, { rulesServed: [{ key: "declarations-extractor", rulesVersion: "1" }], noteActors: [], profile: { defaultModels: DISPATCH_DEFAULTS } });
    assert.ok(!result.stages.some((s) => s.seat === "declarations-extractor"));
});

test("S20: extractor IS inserted when both readsExclude is non-empty AND the seat owns a skill:test-author note", () => {
    const candidate = { id: "z3", short: "z3000000", title: "Z", priority: "high", complexity: 1, type: "scratch-x", role: "queue", parentId: null, traits: [], hasChildren: false, source: "ready" };
    const schemaEntry = schemaWithSeat({ readsExclude: ["implementation-notes"] }, { skill: "test-author" });
    const result = deriveStages(candidate, schemaEntry, { rulesServed: [{ key: "declarations-extractor", rulesVersion: "1" }], noteActors: [], profile: { defaultModels: DISPATCH_DEFAULTS } });
    assert.ok(result.stages.some((s) => s.seat === "declarations-extractor" && s.inserted === true));
});

// ═══════════════════════════════════════════════════════════════════════════
// S21 — determinism: same snapshot + flags -> byte-identical stdout; the lib
// never reads the clock or randomness. [master §3.1, Appendix C "Determinism"]
// ═══════════════════════════════════════════════════════════════════════════

test("S21: buildPlanDoc is byte-identical across two runs of the same snapshot and flags", () => {
    const snap = mixedTypesFixture();
    const doc1 = buildPlanDoc(clone(snap), { now: NOW });
    const doc2 = buildPlanDoc(clone(snap), { now: NOW });
    assert.equal(doc1.ok, true);
    assert.equal(doc2.ok, true);
    assert.equal(JSON.stringify(doc1.doc), JSON.stringify(doc2.doc));
});

test("S21: buildPlanDoc output does not depend on the input candidates' array order", () => {
    const snap = mixedTypesFixture();
    const permuted = clone(snap);
    permuted.candidates = [...snap.candidates].reverse();
    const doc1 = buildPlanDoc(clone(snap), { now: NOW });
    const doc2 = buildPlanDoc(permuted, { now: NOW });
    assert.equal(doc1.ok, true);
    assert.equal(doc2.ok, true);
    assert.equal(JSON.stringify(doc1.doc), JSON.stringify(doc2.doc));
});

test("S21: CLI plan produces byte-identical stdout across two invocations with the same --now", () => {
    const snap = loadFixture("independent-two.json");
    const res1 = runCli(["plan", "--now", NOW], { input: JSON.stringify(snap) });
    const res2 = runCli(["plan", "--now", NOW], { input: JSON.stringify(snap) });
    assert.equal(res1.status, 0, res1.stderr);
    assert.equal(res2.status, 0, res2.stderr);
    assert.equal(res1.stdout, res2.stdout);
});

test("S21: run-planner-lib.mjs source contains no Date.now(), Math.random(), or no-arg new Date()", () => {
    const text = readFileSync(LIB_PATH, "utf8");
    assert.ok(!text.includes("Date.now("), "must not call Date.now()");
    assert.ok(!text.includes("Math.random("), "must not call Math.random()");
    assert.ok(!/new Date\s*\(\s*\)/.test(text), "must not call a no-arg new Date()");
});

// ═══════════════════════════════════════════════════════════════════════════
// S22 — probeFrom: version + marker gating for phase0Hooks; guideline from
// settings precedence; allow flags from permissions.allow. [Appendix C probeFrom]
// ═══════════════════════════════════════════════════════════════════════════

const PLUGIN_JSON_380 = JSON.stringify({ version: "3.8.0" });
const PLUGIN_JSON_3100 = JSON.stringify({ version: "3.10.0" });
const PLUGIN_JSON_379 = JSON.stringify({ version: "3.7.9" });
const BOTH_MARKERS = { subagentStartText: "matcher for 'workflow-subagent' here", phaseGuardText: "parent.startsWith('workflow:')" };
const MISSING_ONE_MARKER = { subagentStartText: "matcher for 'workflow-subagent' here", phaseGuardText: "no marker here" };

test("S22: compareVersions orders 3.10.0 after 3.8.0 (not lexicographic)", () => {
    assert.equal(compareVersions("3.10.0", "3.8.0"), 1);
    assert.equal(compareVersions("3.8.0", "3.10.0"), -1);
    assert.equal(compareVersions("3.8.0", "3.8.0"), 0);
});

test("S22: probeFrom — version 3.8.0 with both markers -> phase0Hooks true", () => {
    const result = probeFrom({
        pluginRoot: "/plugin", pluginJsonText: PLUGIN_JSON_380,
        ...BOTH_MARKERS, configYamlText: "", settings: ["{}", "{}", "{}"],
    });
    assert.equal(result.phase0Hooks, true);
});

test("S22: probeFrom — version 3.10.0 with both markers -> phase0Hooks true (version compare, not lexicographic)", () => {
    const result = probeFrom({
        pluginRoot: "/plugin", pluginJsonText: PLUGIN_JSON_3100,
        ...BOTH_MARKERS, configYamlText: "", settings: ["{}", "{}", "{}"],
    });
    assert.equal(result.phase0Hooks, true);
});

test("S22: probeFrom — version 3.7.9 with both markers -> phase0Hooks false (below 3.8.0)", () => {
    const result = probeFrom({
        pluginRoot: "/plugin", pluginJsonText: PLUGIN_JSON_379,
        ...BOTH_MARKERS, configYamlText: "", settings: ["{}", "{}", "{}"],
    });
    assert.equal(result.phase0Hooks, false);
});

test("S22: probeFrom — version 3.8.0 with a missing marker -> phase0Hooks false", () => {
    const result = probeFrom({
        pluginRoot: "/plugin", pluginJsonText: PLUGIN_JSON_380,
        ...MISSING_ONE_MARKER, configYamlText: "", settings: ["{}", "{}", "{}"],
    });
    assert.equal(result.phase0Hooks, false);
});

test("S22: probeFrom — workflowSizeGuideline takes the first defined value across local, project, user settings (settings are raw JSON text per file)", () => {
    const result = probeFrom({
        pluginRoot: "/plugin", pluginJsonText: PLUGIN_JSON_380,
        ...BOTH_MARKERS, configYamlText: "",
        settings: ["{}", JSON.stringify({ workflowSizeGuideline: "large" }), JSON.stringify({ workflowSizeGuideline: "medium" })],
    });
    assert.equal(result.workflowSizeGuideline, "large");
});

test("S22: probeFrom — workflowSizeGuideline falls through to user settings when local and project are silent", () => {
    const result = probeFrom({
        pluginRoot: "/plugin", pluginJsonText: PLUGIN_JSON_380,
        ...BOTH_MARKERS, configYamlText: "",
        settings: ["{}", "{}", JSON.stringify({ workflowSizeGuideline: "medium" })],
    });
    assert.equal(result.workflowSizeGuideline, "medium");
});

test("S22: probeFrom — workflowSizeGuideline is null when no settings file defines it", () => {
    const result = probeFrom({
        pluginRoot: "/plugin", pluginJsonText: PLUGIN_JSON_380,
        ...BOTH_MARKERS, configYamlText: "", settings: ["{}", "{}", "{}"],
    });
    assert.equal(result.workflowSizeGuideline, null);
});

test("S22: probeFrom — allow flags reflect permissions.allow entries; empty settings -> all false", () => {
    const allAllowed = probeFrom({
        pluginRoot: "/plugin", pluginJsonText: PLUGIN_JSON_380,
        ...BOTH_MARKERS, configYamlText: "",
        settings: [
            JSON.stringify({
                permissions: {
                    allow: [
                        "Workflow(task-orchestrator:implement-wave)",
                        "mcp__mcp-task-orchestrator__*",
                        "Bash(git:*)",
                        "Bash(node:*)",
                    ],
                },
            }),
            "{}",
            "{}",
        ],
    });
    assert.equal(allAllowed.allow.workflow, true);
    assert.equal(allAllowed.allow.mcp, true);
    assert.equal(allAllowed.allow.bashGit, true);
    assert.equal(allAllowed.allow.bashNode, true);

    const noneAllowed = probeFrom({
        pluginRoot: "/plugin", pluginJsonText: PLUGIN_JSON_380,
        ...BOTH_MARKERS, configYamlText: "", settings: ["{}", "{}", "{}"],
    });
    assert.equal(noneAllowed.allow.workflow, false);
    assert.equal(noneAllowed.allow.mcp, false);
    assert.equal(noneAllowed.allow.bashGit, false);
    assert.equal(noneAllowed.allow.bashNode, false);
});

test("S22: probeFrom returns pluginVersion and pluginRoot verbatim", () => {
    const result = probeFrom({
        pluginRoot: "/some/plugin/root", pluginJsonText: PLUGIN_JSON_380,
        ...BOTH_MARKERS, configYamlText: "", settings: ["{}", "{}", "{}"],
    });
    assert.equal(result.pluginRoot, "/some/plugin/root");
    assert.equal(result.pluginVersion, "3.8.0");
});

test("S22 (CLI): probe subcommand exits 0 and emits phase0Hooks/allow fields", () => {
    const res = runCli(["probe", "--plugin-root", HERE]);
    assert.equal(res.status, 0, res.stderr);
    const parsed = JSON.parse(res.stdout);
    assert.ok("phase0Hooks" in parsed);
    assert.ok("allow" in parsed);
});

// ═══════════════════════════════════════════════════════════════════════════
// Probes: maxItems 0, role "Work" (wrong case), duplicate id in ready+work,
// absent vs [] traits, backslash repoRoot -> forward slashes.
// ═══════════════════════════════════════════════════════════════════════════

test("Probe: --max-items 0 is invalid input (exit 2)", () => {
    const snap = loadFixture("independent-two.json");
    const res = runCli(["plan", "--max-items", "0"], { input: JSON.stringify(snap) });
    assert.equal(res.status, 2);
});

test("Probe: a candidate role 'Work' (wrong case) fails validateSnapshot", () => {
    const snap = loadFixture("independent-two.json");
    snap.candidates[0].role = "Work";
    const result = validateSnapshot(snap);
    assert.equal(result.ok, false);
    assert.ok(result.errors.length > 0);
});

test("Probe: the same id present in both ready and work collapses to one item, not an error", () => {
    const dupId = "dddddddd-1111-4111-8111-111111111111";
    const snap = baseSnapshot({
        candidates: [
            plainCandidate({ id: dupId, short: "dddddddd", role: "queue", source: "ready" }),
            plainCandidate({ id: dupId, short: "dddddddd", role: "work", source: "work" }),
        ],
        schemas: { [dupId]: plainSchema() },
    });
    const validation = validateSnapshot(snap);
    assert.equal(validation.ok, true, JSON.stringify(validation.errors));
    const result = buildPlanDoc(snap, { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.equal(result.doc.args.items.filter((it) => it.id === dupId).length, 1);
});

test("Probe: absent traits behaves identically to traits: [] downstream", () => {
    const idNoTraits = "e2000000-0000-4000-8000-000000000000";
    const idEmptyTraits = "e3000000-0000-4000-8000-000000000000";
    const candNoTraits = plainCandidate({ id: idNoTraits, short: "e2000000" });
    delete candNoTraits.traits;
    const candEmptyTraits = plainCandidate({ id: idEmptyTraits, short: "e3000000" });
    candEmptyTraits.traits = [];

    const snapA = baseSnapshot({ candidates: [candNoTraits], schemas: { [idNoTraits]: plainSchema() } });
    const snapB = baseSnapshot({ candidates: [candEmptyTraits], schemas: { [idEmptyTraits]: plainSchema() } });

    const resultA = buildPlanDoc(snapA, { now: NOW });
    const resultB = buildPlanDoc(snapB, { now: NOW });
    assert.equal(resultA.ok, true, JSON.stringify(resultA.errors));
    assert.equal(resultB.ok, true, JSON.stringify(resultB.errors));
    assert.deepEqual(resultA.doc.args.items[0].traits, []);
    assert.deepEqual(resultB.doc.args.items[0].traits, []);
});

test("Probe: a backslashed git.repoRoot never leaks a backslash into item worktree paths", () => {
    const snap = loadFixture("independent-two.json");
    snap.git.repoRoot = "C:\\repo";
    const result = buildPlanDoc(snap, { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    for (const item of result.doc.args.items) {
        assert.ok(!item.worktree.includes("\\"), `worktree must be forward-slashed: ${item.worktree}`);
    }
});

// ═══════════════════════════════════════════════════════════════════════════
// Follow-up (reviewer's test-independence-audit, keys=["test-independence-audit"]).
// Oracles: b2-dispatch-contract.md Appendix C, b2-b3-front-door.md §3.1/§3.4, B1 plan §3.1.
// Reds against HEAD are expected here (implementer concurrently fixing B1-B4/O4/O7);
// they are reported, never weakened.
// ═══════════════════════════════════════════════════════════════════════════

// ── (1) CLI `plan` without --now uses the CLI clock; runId embeds yyyymmddHHMM (UTC) close
//        to wall-clock time; `validate` on the resulting doc exits 0. [Appendix C "--now defaults
//        to the CLI clock"; assembleArgs runId = r-<yyyymmddHHMM UTC of now>-<items[0].short>]

test("(1) CLI plan without --now: runId's UTC yyyymmddHHMM is within a minute of wall-clock time; format ^r-\\d{12}-[0-9a-f]{8}$; validate exits 0", () => {
    const before = new Date();
    const snap = loadFixture("independent-two.json");
    const res = runCli(["plan"], { input: JSON.stringify(snap) }); // no --now
    const after = new Date();
    assert.equal(res.status, 0, res.stderr);
    const doc = JSON.parse(res.stdout);
    assert.match(doc.args.runId, /^r-\d{12}-[0-9a-f]{8}$/);

    const ts = doc.args.runId.slice(2, 14);
    const y = Number(ts.slice(0, 4));
    const mo = Number(ts.slice(4, 6));
    const d = Number(ts.slice(6, 8));
    const h = Number(ts.slice(8, 10));
    const mi = Number(ts.slice(10, 12));
    const parsedMs = Date.UTC(y, mo - 1, d, h, mi, 0);
    // Minute-truncation can put the embedded timestamp up to 59s before generation time;
    // allow slack for that plus test/process overhead on both sides.
    assert.ok(
        parsedMs >= before.getTime() - 65000 && parsedMs <= after.getTime() + 5000,
        `runId timestamp ${ts} (${new Date(parsedMs).toISOString()}) not within a minute of wall clock [${before.toISOString()}, ${after.toISOString()}]`
    );

    const validateRes = runCli(["validate"], { input: JSON.stringify(doc) });
    assert.equal(validateRes.status, 0, validateRes.stderr);
});

// ── (2) S18 'nothing to run': an item whose every seat's notes are already written under that
//        seat's own actor has nothing left to run and is EXCLUDED with reason 'nothing to run'
//        (not admitted, not deferred); a co-admitted sibling still satisfies args.items minItems 1;
//        with only such items, the run refuses (args:null, the existing zero-admitted shape).

const NOTHING_TO_RUN_ID = "9a000000-0000-4000-8000-000000000000";
const OTHER_ADMITTED_ID = "9b000000-0000-4000-8000-000000000000";

function fullyResumedNoteActors(short) {
    return [
        { key: "diagnosis", actorId: `planner:${short}:r-prior` },
        { key: "test-plan", actorId: `planner:${short}:r-prior` },
        { key: "implementation-notes", actorId: `implementer:${short}:r-prior` },
        { key: "test-manifest", actorId: `test-author:${short}:r-prior` },
    ];
}

test("(2) deriveStages on a fully-resumed item (every seat's notes already written) yields zero stages, exclude:'nothing to run'", () => {
    const candidate = bugFixLikeCandidate({ id: NOTHING_TO_RUN_ID, short: "9a000000", parentId: null, role: "work", source: "work" });
    const schemaEntry = bugFixLikeSchema();
    const result = deriveStages(candidate, schemaEntry, {
        rulesServed: [{ key: "declarations-extractor", rulesVersion: "1" }],
        noteActors: fullyResumedNoteActors("9a000000"),
        profile: { defaultModels: DISPATCH_DEFAULTS },
    });
    assert.deepEqual(result.stages, []);
    assert.equal(result.exclude, "nothing to run");
});

function nothingToRunSnapshot({ withSibling }) {
    const candidates = [bugFixLikeCandidate({ id: NOTHING_TO_RUN_ID, short: "9a000000", parentId: null, role: "work", source: "work" })];
    const schemas = { [NOTHING_TO_RUN_ID]: bugFixLikeSchema() };
    const noteActors = { [NOTHING_TO_RUN_ID]: fullyResumedNoteActors("9a000000") };
    if (withSibling) {
        candidates.push(plainCandidate({ id: OTHER_ADMITTED_ID, short: "9b000000" }));
        schemas[OTHER_ADMITTED_ID] = plainSchema();
    }
    return baseSnapshot({
        rulesServed: [{ key: "declarations-extractor", rulesVersion: "1" }, { key: "commit-discipline", rulesVersion: "1" }],
        candidates, schemas, noteActors,
    });
}

test("(2) integration: a fully-resumed item is excluded 'nothing to run'; a co-admitted sibling still satisfies args.items minItems 1", () => {
    const result = buildPlanDoc(nothingToRunSnapshot({ withSibling: true }), { now: NOW });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.deepEqual(result.doc.args.items.map((it) => it.short), ["9b000000"]);
    assert.ok(result.doc.args.items.length >= 1);
    assert.ok(result.doc.meta.excluded.some((e) => e.id === NOTHING_TO_RUN_ID && e.reason === "nothing to run"));
});

test("(2) integration: with ONLY a fully-resumed item, the run refuses (args:null, zero admitted)", () => {
    const result = buildPlanDoc(nothingToRunSnapshot({ withSibling: false }), { now: NOW });
    assert.equal(result.ok, false);
    assert.equal(result.doc?.args ?? null, null);
});

// ── (3) Placeholder substitution rules, corrected per Appendix C's own text: "`<worktree>`
//        substituted only in shared mode" and "`project.scratchDir` = `<scratchpad>/run-wave/<runId>`"
//        where `<scratchpad>` is the session scratchpad ROOT (`opts.scratchpad`, where the gradle
//        lock helper lives) — never `scratchDir` itself. So: shared mode substitutes `<worktree>`
//        with the shared feature worktree; per-item mode LEAVES `<worktree>` literal (each seat
//        substitutes its own item worktree at dispatch time) and must not leak any one item's
//        worktree path into the shared project.* fields; both modes substitute `<scratchpad>`
//        with the scratchpad root, and `project.scratchDir` is built from that root.

function placeholderProfile() {
    return {
        shell: "bash",
        verify: [{ name: "compile", command: "cd <worktree> && node --check . && cat <scratchpad>/notes.txt", seats: ["implementer"] }],
        searchScope: ["<worktree>/current/src", "<scratchpad>/notes"],
        worktreeRoot: ".claude/worktrees",
        branchPrefix: { shared: "feat/", perItem: "fix/" },
        maxItems: 5,
        defaultModels: DISPATCH_DEFAULTS,
    };
}

test("(3) shared mode: <worktree> is substituted with the shared feature worktree; <scratchpad> is substituted with the scratchpad ROOT (never scratchDir); project.scratchDir = <root>/run-wave/<runId>", () => {
    const parentId = "9c000000-0000-4000-8000-000000000000";
    const scratchpadRoot = "/scratch/run";
    const snap = baseSnapshot({
        ancestorId: parentId,
        candidates: [
            plainCandidate({ id: "9d000000-0000-4000-8000-000000000000", short: "9d000000", parentId }),
            plainCandidate({ id: "9e000000-0000-4000-8000-000000000000", short: "9e000000", parentId }),
        ],
        schemas: {
            "9d000000-0000-4000-8000-000000000000": plainSchema(),
            "9e000000-0000-4000-8000-000000000000": plainSchema(),
        },
        parents: { [parentId]: { role: "work", canAdvance: true, missing: [] } },
        profile: placeholderProfile(),
    });
    const result = buildPlanDoc(snap, { now: NOW, mode: "shared", scratchpad: scratchpadRoot });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    const doc = result.doc;

    const worktrees = new Set(doc.args.items.map((it) => it.worktree));
    assert.equal(worktrees.size, 1, "shared mode must use one worktree for every admitted item");
    const sharedWorktree = [...worktrees][0];

    // The fixture command carries BOTH placeholders on one line; each array entry in
    // searchScope carries only one. Check each string for the placeholders it could plausibly
    // contain, not that every entry contains both substituted values.
    for (const v of doc.args.project.verify ?? []) {
        assert.ok(!v.command.includes("<worktree>"), `shared mode must substitute <worktree>: ${v.command}`);
        assert.ok(!v.command.includes("<scratchpad>"), `<scratchpad> must be substituted: ${v.command}`);
    }
    assert.ok(
        (doc.args.project.verify ?? []).some((v) => v.command.includes(sharedWorktree)),
        "shared mode must substitute the shared worktree path into at least one verify command"
    );
    const searchScope = doc.args.project.searchScope ?? [];
    for (const s of searchScope) {
        assert.ok(!s.includes("<worktree>"), `shared mode must substitute <worktree>: ${s}`);
        assert.ok(!s.includes("<scratchpad>"), `<scratchpad> must be substituted: ${s}`);
    }
    assert.ok(searchScope.some((s) => s.includes(sharedWorktree)), "shared mode must substitute the shared worktree path into searchScope");
    assert.ok(searchScope.some((s) => s.includes(scratchpadRoot)), "the scratchpad root must be substituted into searchScope");

    assert.equal(doc.args.project.scratchDir, `${scratchpadRoot}/run-wave/${doc.args.runId}`);
});

test("(3) per-item mode: <worktree> is left literal for seats to substitute (no item worktree leaks in); <scratchpad> is still substituted with the scratchpad ROOT; project.scratchDir = <root>/run-wave/<runId>", () => {
    const scratchpadRoot = "/scratch/run";
    const snap = baseSnapshot({
        candidates: [plainCandidate({ id: "9f000000-0000-4000-8000-000000000000", short: "9f000000" })],
        schemas: { "9f000000-0000-4000-8000-000000000000": plainSchema() },
        profile: placeholderProfile(),
    });
    const result = buildPlanDoc(snap, { now: NOW, mode: "per-item", scratchpad: scratchpadRoot });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    const doc = result.doc;
    const itemWorktree = doc.args.items[0].worktree;

    for (const v of doc.args.project.verify ?? []) {
        assert.ok(!v.command.includes(itemWorktree), `per-item mode must not leak the item's own worktree path: ${v.command}`);
        assert.ok(!v.command.includes("<scratchpad>"), `<scratchpad> must be substituted: ${v.command}`);
    }
    assert.ok(
        (doc.args.project.verify ?? []).some((v) => v.command.includes("<worktree>")),
        "per-item mode must leave <worktree> literal in at least one verify command"
    );
    const searchScope = doc.args.project.searchScope ?? [];
    for (const s of searchScope) {
        assert.ok(!s.includes(itemWorktree), `per-item mode must not leak the item's own worktree path: ${s}`);
        assert.ok(!s.includes("<scratchpad>"), `<scratchpad> must be substituted: ${s}`);
    }
    assert.ok(searchScope.some((s) => s.includes("<worktree>")), "per-item mode must leave <worktree> literal in searchScope");
    assert.ok(searchScope.some((s) => s.includes(scratchpadRoot)), "the scratchpad root must be substituted into searchScope");

    assert.equal(doc.args.project.scratchDir, `${scratchpadRoot}/run-wave/${doc.args.runId}`);
});

// ── (4) profile.defaultModels.extractor accepts both the string form 'sonnet+low' and the
//        object form {model,effort}; both yield the extractor stage's dispatch {model:'sonnet',
//        effort:'low'}. [Appendix C default-object literal: extractor:{model:'sonnet',effort:'low'}]

function extractorDispatchFor(defaultModelsExtractor) {
    const snap = mixedTypesFixture();
    const candidate = snap.candidates[0];
    const schemaEntry = snap.schemas[candidate.id];
    const profile = { ...snap.profile, defaultModels: { ...snap.profile.defaultModels, extractor: defaultModelsExtractor } };
    const result = deriveStages(candidate, schemaEntry, { rulesServed: snap.rulesServed, noteActors: [], profile });
    const extractor = result.stages.find((s) => s.seat === "declarations-extractor");
    assert.ok(extractor, "extractor stage must be present");
    return extractor.dispatch;
}

test("(4) profile defaultModels.extractor: string form 'sonnet+low' yields {model:'sonnet',effort:'low'}", () => {
    const dispatch = extractorDispatchFor("sonnet+low");
    assert.equal(dispatch.model, "sonnet");
    assert.equal(dispatch.effort, "low");
});

test("(4) profile defaultModels.extractor: object form {model,effort} yields the same {model:'sonnet',effort:'low'}", () => {
    const dispatch = extractorDispatchFor({ model: "sonnet", effort: "low" });
    assert.equal(dispatch.model, "sonnet");
    assert.equal(dispatch.effort, "low");
});

// ── (5) classifyEdge's cross-run reason names the blocker's 8-hex short, never the full UUID.

test("(5) classifyEdge cross-run reason uses the blocker's 8-hex short, never the full UUID", () => {
    const fullId = "12345678-90ab-4cde-8fed-cba098765432";
    const edge = { itemId: fullId, role: "review", effectiveUnblockRole: "terminal", satisfied: false };
    const result = classifyEdge(edge, { runIds: new Set([fullId]), mode: "shared", milestone: null });
    assert.equal(result.class, "cross-run");
    assert.ok(result.reason.includes("12345678"), result.reason);
    assert.ok(!result.reason.includes(fullId), result.reason);
});

// ── (6) O1: resume-skip requires the note actor's short segment to match THIS item's own short;
//        a note written under a DIFFERENT item's short must not skip the seat.

test("(6) O1: a note actor stamped with a DIFFERENT item's short does not skip this item's seat", () => {
    const snap = mixedTypesFixture();
    const candidate = { ...snap.candidates[0], role: "work", source: "work" };
    const schemaEntry = snap.schemas[candidate.id];
    const noteActors = [{ key: "implementation-notes", actorId: "implementer:00000000:r-other" }]; // wrong short
    const result = deriveStages(candidate, schemaEntry, { rulesServed: snap.rulesServed, noteActors, profile: snap.profile });
    const seats = result.stages.map((s) => s.seat);
    assert.ok(seats.includes("implementer"), "a note actor from a different item's short must not count as this item's resume");
});

// ── (7) O9 (tightened S5): resolveDispatch's agent is exactly null, never undefined, when the
//        profile declares agent: null.

test("(7) O9: resolveDispatch's agent is strictly null (never undefined) when the profile declares agent: null", () => {
    const schemaEntry = { dispatchBySeat: { work: { "test-author": { agent: null, model: "sonnet" } } } };
    const result = resolveDispatch(schemaEntry, "test-author", "work", { enters: false, implicitOwner: false }, DISPATCH_DEFAULTS);
    assert.equal(result.agent, null);
    assert.notEqual(result.agent, undefined);
    assert.ok(Object.prototype.hasOwnProperty.call(result, "agent"), "agent key must be present on the result, not omitted");
});

// ═══════════════════════════════════════════════════════════════════════════
// Appendix E (container-review fixes, review-checklist 36b3419f on 5d33ea27).
// BLIND per the same rule as the rest of this file: written from
// b2-dispatch-contract.md Appendix E (E1, E3, E5) only, never from
// run-planner-lib.mjs / run-planner.mjs source. E5 scopes this file to exactly:
// validateSnapshot's two new error strings; `plan` exit 3 + message for the
// <scratchpad> case; `plan` exit 3 for --worktree/--branch in per-item mode;
// --worktree without --branch exit 3; assembleArgs' shared-mode worktree/branch
// override (normalized) and meta.worktreesToCreate empty-vs-non-empty depending
// on snap.git.worktrees[path]. The override/worktreesToCreate scenarios are
// driven through the `plan` CLI rather than a direct assembleArgs(snap, planned,
// opts) unit call: Appendix C never publishes the internal shape of `planned`
// (only assembleArgs' own opts/output contract), and `plan`'s frozen call chain
// (Appendix C) routes through assembleArgs, so a CLI-level assertion exercises
// the same frozen behaviour without guessing an unpublished internal shape.
// ═══════════════════════════════════════════════════════════════════════════

test("E1: validateSnapshot rejects an empty git.repoRoot with the exact frozen error string", () => {
    const snap = baseSnapshot({ git: { originMain: "e77c3e85", repoRoot: "", worktrees: {} } });
    const result = validateSnapshot(snap);
    assert.equal(result.ok, false);
    assert.ok(result.errors.includes("git.repoRoot must be a non-empty string"), JSON.stringify(result.errors));
});

test("E1: validateSnapshot rejects a non-plain-object profile (an array) with the exact frozen error string", () => {
    const snap = baseSnapshot({ profile: ["not", "a", "plain", "object"] });
    const result = validateSnapshot(snap);
    assert.equal(result.ok, false);
    assert.ok(result.errors.includes("profile must be an object"), JSON.stringify(result.errors));
});

test("E1: plan CLI refuses (exit 3) when --scratchpad is absent but a profile verify command uses the <scratchpad> literal", () => {
    const base = loadFixture("independent-two.json");
    const snap = {
        ...base,
        profile: {
            ...base.profile,
            verify: [{ name: "compile-self-check", command: "<scratchpad>/gradle-locked.ps1 -Worktree x" }],
        },
    };
    const res = runCli(["plan"], { input: JSON.stringify(snap) }); // no --scratchpad
    assert.equal(res.status, 3);
    const combined = `${res.stdout}\n${res.stderr}`;
    assert.ok(
        combined.includes("plan: --scratchpad is required because the profile uses <scratchpad>"),
        combined
    );
});

test("E3: plan CLI refuses (exit 3) when --worktree/--branch are passed together with --mode per-item", () => {
    const snap = loadFixture("independent-two.json");
    const res = runCli(
        ["plan", "--mode", "per-item", "--worktree", "/repo/.claude/worktrees/custom", "--branch", "feat/custom"],
        { input: JSON.stringify(snap) }
    );
    assert.equal(res.status, 3);
    const combined = `${res.stdout}\n${res.stderr}`;
    assert.ok(
        combined.includes("plan: --worktree/--branch apply to --mode shared only"),
        combined
    );
});

test("E3: plan CLI refuses (exit 3) when --worktree is passed without --branch (shared mode)", () => {
    const snap = loadFixture("independent-two.json");
    const res = runCli(
        ["plan", "--mode", "shared", "--worktree", "/repo/.claude/worktrees/custom"],
        { input: JSON.stringify(snap) }
    );
    assert.equal(res.status, 3);
});

test("E3: shared-mode --worktree/--branch override replaces every item's worktree/branch, normalizing a backslashed path", () => {
    const snap = loadFixture("in-run-edge-shared.json");
    const res = runCli(
        [
            "plan", "--now", NOW, "--mode", "shared",
            "--worktree", "C:\\repo\\shared-wt",
            "--branch", "feat/shared-override",
        ],
        { input: JSON.stringify(snap) }
    );
    assert.equal(res.status, 0, res.stderr);
    const doc = JSON.parse(res.stdout);
    assert.equal(doc.args.items.length, 2);
    for (const item of doc.args.items) {
        assert.equal(item.worktree, "C:/repo/shared-wt", JSON.stringify(item));
        assert.equal(item.branch, "feat/shared-override", JSON.stringify(item));
    }
});

test("E3: meta.worktreesToCreate contains the shared override pair when snap.git.worktrees does not have that path", () => {
    const base = loadFixture("in-run-edge-shared.json");
    const overridePath = "/repo/custom-shared-wt";
    const overrideBranch = "feat/custom-shared";
    const snap = { ...base, git: { ...base.git, worktrees: {} } };
    const res = runCli(
        ["plan", "--now", NOW, "--mode", "shared", "--worktree", overridePath, "--branch", overrideBranch],
        { input: JSON.stringify(snap) }
    );
    assert.equal(res.status, 0, res.stderr);
    const doc = JSON.parse(res.stdout);
    assert.ok(
        doc.meta.worktreesToCreate.some((w) => w.path === overridePath && w.branch === overrideBranch),
        JSON.stringify(doc.meta.worktreesToCreate)
    );
});

test("E3: meta.worktreesToCreate is empty when snap.git.worktrees already has the shared override path", () => {
    const base = loadFixture("in-run-edge-shared.json");
    const overridePath = "/repo/custom-shared-wt";
    const overrideBranch = "feat/custom-shared";
    const snap = {
        ...base,
        git: { ...base.git, worktrees: { [overridePath]: { branch: overrideBranch, head: "abc1234" } } },
    };
    const res = runCli(
        ["plan", "--now", NOW, "--mode", "shared", "--worktree", overridePath, "--branch", overrideBranch],
        { input: JSON.stringify(snap) }
    );
    assert.equal(res.status, 0, res.stderr);
    const doc = JSON.parse(res.stdout);
    assert.deepEqual(doc.meta.worktreesToCreate, []);
});

// ═══════════════════════════════════════════════════════════════════════════
// S5 — validateSnapshot: snap.probe must be an object. Absent, null, an array,
// or any non-object value is invalid with the exact error 'probe must be an
// object'; a plain object (even {}) passes. [D3, plans/fix-config-sync-busy.md]
// Blind regression: oracle is the brief's D3 text, not validateSnapshot's
// current behavior. Red-proof: today validateSnapshot accepts a missing probe
// (no such error appears), so these assertions fail against unfixed code.
// ═══════════════════════════════════════════════════════════════════════════

test("S5: validateSnapshot — a snapshot with no probe field is invalid with the exact error 'probe must be an object'", () => {
    const snap = baseSnapshot();
    delete snap.probe;
    const result = validateSnapshot(snap);
    assert.equal(result.ok, false);
    assert.ok(
        result.errors.includes("probe must be an object"),
        `expected 'probe must be an object' in ${JSON.stringify(result.errors)}`
    );
});

test("S5: validateSnapshot — probe: null is invalid with the exact error 'probe must be an object'", () => {
    const snap = baseSnapshot({ probe: null });
    const result = validateSnapshot(snap);
    assert.equal(result.ok, false);
    assert.ok(
        result.errors.includes("probe must be an object"),
        `expected 'probe must be an object' in ${JSON.stringify(result.errors)}`
    );
});

test("S5: validateSnapshot — probe: [] (an array) is invalid with the exact error 'probe must be an object'", () => {
    const snap = baseSnapshot({ probe: [] });
    const result = validateSnapshot(snap);
    assert.equal(result.ok, false);
    assert.ok(
        result.errors.includes("probe must be an object"),
        `expected 'probe must be an object' in ${JSON.stringify(result.errors)}`
    );
});

test("S5: validateSnapshot — probe: 'x' (a string) is invalid with the exact error 'probe must be an object'", () => {
    const snap = baseSnapshot({ probe: "x" });
    const result = validateSnapshot(snap);
    assert.equal(result.ok, false);
    assert.ok(
        result.errors.includes("probe must be an object"),
        `expected 'probe must be an object' in ${JSON.stringify(result.errors)}`
    );
});

test("S5: validateSnapshot — probe: {} (a plain, even empty, object) passes; no probe error", () => {
    const snap = baseSnapshot({ probe: {} });
    const result = validateSnapshot(snap);
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    assert.ok(!(result.errors ?? []).includes("probe must be an object"));
});

// ═══════════════════════════════════════════════════════════════════════════
// S6 — CLI: a snapshot without probe fails validation (non-zero exit); the
// unchanged fixtures (which already carry probe) still exit 0.
// [D3, plans/fix-config-sync-busy.md]
// ═══════════════════════════════════════════════════════════════════════════

test("S6: CLI plan on a snapshot missing probe exits non-zero", () => {
    const snap = loadFixture("independent-two.json");
    delete snap.probe;
    const res = runCli(["plan"], { input: JSON.stringify(snap) });
    assert.notEqual(res.status, 0, "CLI must reject a snapshot with no probe field");
});

test("S6: CLI plan on the unchanged independent-two.json fixture (which already carries probe) still exits 0", () => {
    const snap = loadFixture("independent-two.json");
    const res = runCli(["plan", "--now", NOW], { input: JSON.stringify(snap) });
    assert.equal(res.status, 0, res.stderr);
});
