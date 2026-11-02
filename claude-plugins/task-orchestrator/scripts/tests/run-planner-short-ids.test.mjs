/**
 * run-planner short-id references (item 1d996505).
 *
 * Oracles: [R] the server item-id rule "a UUID or a hex prefix of 8 to 32 characters";
 * [V] items_read blocked/next answer 8-hex id/parentId and full blockers[].id; [D] the frozen
 * diagnosis rule (identity stays a full UUID, references resolve by prefix before any join);
 * [M] references/snapshot.md ("not projected"; shared iff one non-null parentId).
 * All scenarios are EXISTING-SURFACE. Fixture helpers are copied from run-planner.test.mjs.
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import {
    SNAPSHOT_CONTRACT,
    validateSnapshot,
    chooseMode,
    buildPlanDoc,
} from "../run-planner-lib.mjs";

const NOW = "2026-09-28T12:00:00Z";

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
        noteOwners: {},
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

const A_ID = "aaaaaaaa-1111-4111-8111-111111111111";
const B_ID = "bbbbbbbb-2222-4222-8222-222222222222";
const PARENT_ID = "cccccccc-3333-4333-8333-333333333333";
const OUTSIDE_ID = "99999999-0000-4000-8000-000000000000";

/** A bug-fix-like, B plain, same parent, B blocked by A at work. References are parameters. */
function pairSnapshot({ bRef = B_ID, aRef = A_ID, parentRef = PARENT_ID, blocked, candidateOrder = "ab" } = {}) {
    const cands = [
        bugFixLikeCandidate({ id: A_ID, short: "aaaaaaaa", parentId: parentRef }),
        plainCandidate({ id: B_ID, short: "bbbbbbbb", parentId: parentRef }),
    ];
    if (candidateOrder === "ba") cands.reverse();
    return baseSnapshot({
        ancestorId: PARENT_ID,
        rulesServed: [
            { key: "commit-discipline", rulesVersion: "1" },
            { key: "declarations-extractor", rulesVersion: "1" },
        ],
        candidates: cands,
        blocked: blocked ?? [
            {
                itemId: bRef, role: "queue", blockType: "dependency",
                blockedBy: [{ itemId: aRef, role: "work", effectiveUnblockRole: "work", satisfied: false }],
            },
        ],
        schemas: { [A_ID]: bugFixLikeSchema(), [B_ID]: plainSchema() },
        parents: { [PARENT_ID]: { role: "work", canAdvance: true, missing: [] } },
    });
}

function plan(snap) {
    const r = buildPlanDoc(snap, { now: NOW });
    assert.equal(r.ok, true, JSON.stringify(r.errors));
    return r.doc;
}

function planShape(doc) {
    return { items: doc.args.items, deferred: doc.args.deferred, inRunEdges: doc.meta.inRunEdges };
}

const fullPlan = () => planShape(plan(pairSnapshot()));

// -- Happy ------------------------------------------------------------------

test("S1: validateSnapshot accepts an 8-hex blocked itemId with a full-UUID blocker", () => {
    const r = validateSnapshot(pairSnapshot({ bRef: "bbbbbbbb" }));
    assert.equal(r.ok, true, JSON.stringify(r.errors));
});

test("S2: an 8-hex blocked itemId plans identically to the full-UUID snapshot", () => {
    const shape = planShape(plan(pairSnapshot({ bRef: "bbbbbbbb" })));
    assert.deepEqual(shape, fullPlan());
    const bItem = shape.items.find((it) => it.short === "bbbbbbbb");
    assert.deepEqual(bItem.waitsFor, [{ item: A_ID, milestone: "test-author" }]);
    assert.equal(shape.inRunEdges.length, 1);
});

test("S3: an explicit-block entry with an 8-hex itemId excludes its candidate", () => {
    const snap = baseSnapshot({
        candidates: [
            plainCandidate({ id: A_ID, short: "aaaaaaaa" }),
            plainCandidate({ id: B_ID, short: "bbbbbbbb" }),
        ],
        blocked: [
            {
                itemId: "bbbbbbbb", role: "queue", blockType: "explicit",
                blockedBy: [{ itemId: OUTSIDE_ID, role: "work", effectiveUnblockRole: "work", satisfied: false }],
            },
        ],
        schemas: { [A_ID]: plainSchema(), [B_ID]: plainSchema() },
    });
    const doc = plan(snap);
    assert.deepEqual(doc.args.items.map((it) => it.short), ["aaaaaaaa"]);
    assert.ok(doc.meta.excluded.some((e) => e.id === B_ID && e.reason === "explicitly blocked"),
        JSON.stringify(doc.meta.excluded));
});

test("S4: a blockedBy itemId as 8-hex of an in-run candidate yields a full-UUID waitsFor", () => {
    const doc = plan(pairSnapshot({ aRef: "aaaaaaaa" }));
    const bItem = doc.args.items.find((it) => it.short === "bbbbbbbb");
    assert.deepEqual(bItem.waitsFor, [{ item: A_ID, milestone: "test-author" }]);
    assert.deepEqual(planShape(doc), fullPlan());
});

for (const [label, bRef, aRef] of [
    ["uppercase 8-hex", "BBBBBBBB", "AAAAAAAA"],
    ["12-hex prefix", "bbbbbbbb2222", "aaaaaaaa1111"],
    ["32-hex hyphenless", B_ID.replaceAll("-", ""), A_ID.replaceAll("-", "")],
    ["mixed forms (short blocked, hyphenless blocker)", "bbbbbbbb", A_ID.replaceAll("-", "")],
]) {
    test(`S5: ${label} resolves like the full-UUID snapshot`, () => {
        assert.deepEqual(planShape(plan(pairSnapshot({ bRef, aRef }))), fullPlan());
    });
}

// -- Failure ----------------------------------------------------------------

const BAD_REFS = ["a1b2c3d", "a1b2c3d4a1b2c3d4a1b2c3d4a1b2c3d4a", "zzzzzzzz", "a1b2c3d4-e5f6", " a1b2c3d4"];

for (const bad of BAD_REFS) {
    test(`S6: blocked itemId ${JSON.stringify(bad)} is rejected, naming the value`, () => {
        const r = validateSnapshot(pairSnapshot({ bRef: bad }));
        assert.equal(r.ok, false);
        assert.ok(r.errors.join("\n").includes(bad.trim()), JSON.stringify(r.errors));
    });
    test(`S6: blocker itemId ${JSON.stringify(bad)} is rejected, naming the value`, () => {
        const r = validateSnapshot(pairSnapshot({ aRef: bad }));
        assert.equal(r.ok, false);
        assert.ok(r.errors.join("\n").includes(bad.trim()), JSON.stringify(r.errors));
    });
}

test("S6 probe: empty and absent itemId are both rejected", () => {
    const empty = validateSnapshot(pairSnapshot({ bRef: "" }));
    assert.equal(empty.ok, false);
    assert.ok(empty.errors.join("\n").includes("itemId"), JSON.stringify(empty.errors));
    const absent = pairSnapshot();
    delete absent.blocked[0].itemId;
    const absentR = validateSnapshot(absent);
    assert.equal(absentR.ok, false);
    assert.ok(absentR.errors.join("\n").includes("itemId"), JSON.stringify(absentR.errors));
});

const AMB_1 = "aaaaaaaa-1111-4111-8111-111111111111";
const AMB_2 = "aaaaaaaa-2222-4222-8222-222222222222";

function ambiguousSnapshot(ref, satisfied) {
    return baseSnapshot({
        candidates: [plainCandidate({ id: AMB_1, short: "aaaaaaaa" }), plainCandidate({ id: AMB_2, short: "aaaaaaaa" })],
        blocked: [
            {
                itemId: ref, role: "queue", blockType: "dependency",
                blockedBy: [{ itemId: OUTSIDE_ID, role: "work", effectiveUnblockRole: "work", satisfied }],
            },
        ],
        schemas: { [AMB_1]: plainSchema(), [AMB_2]: plainSchema() },
    });
}

test("S7: an 8-hex reference matching two candidates is ambiguous", () => {
    const snap = ambiguousSnapshot("aaaaaaaa", false);
    const v = validateSnapshot(snap);
    assert.equal(v.ok, false);
    const text = v.errors.join("\n");
    assert.ok(text.includes("ambiguous") && text.includes("aaaaaaaa"), text);
    assert.equal(buildPlanDoc(snap, { now: NOW }).ok, false);
});

test("S7 probe: a longer prefix that disambiguates is accepted", () => {
    assert.equal(validateSnapshot(ambiguousSnapshot("aaaaaaaa1111", true)).ok, true);
});

test("S11: a candidate id in 8-hex form is still rejected (identity stays a full UUID)", () => {
    // Control: with no blocked entries the full-UUID snapshot is valid, so the rename is the only variable.
    assert.equal(validateSnapshot(pairSnapshot({ blocked: [] })).ok, true);
    const snap = pairSnapshot({ blocked: [] });
    snap.candidates[1].id = "bbbbbbbb";
    snap.schemas.bbbbbbbb = snap.schemas[B_ID];
    delete snap.schemas[B_ID];
    const r = validateSnapshot(snap);
    assert.equal(r.ok, false);
    assert.deepEqual(r.errors, ["candidate id not a UUID: bbbbbbbb"]);
});

// -- Edge -------------------------------------------------------------------

test("S8: an 8-hex blocked entry matching no candidate is deferred 'not projected', id as given", () => {
    const snap = baseSnapshot({
        candidates: [plainCandidate({ id: A_ID, short: "aaaaaaaa" })],
        blocked: [
            {
                itemId: "dddddddd", role: "queue", blockType: "dependency",
                blockedBy: [{ itemId: A_ID, role: "work", effectiveUnblockRole: "work", satisfied: false }],
            },
        ],
        schemas: { [A_ID]: plainSchema() },
    });
    const doc = plan(snap);
    assert.ok(doc.args.deferred.some((d) => d.id === "dddddddd" && d.reason === "not projected"),
        JSON.stringify(doc.args.deferred));
});

test("S9: an 8-hex unsatisfied work-threshold blocker outside the run defers the dependent cross-run", () => {
    const C = "c1000000-0000-4000-8000-000000000000";
    const D = "d1000000-0000-4000-8000-000000000000";
    const snap = baseSnapshot({
        candidates: [plainCandidate({ id: C, short: "c1000000" }), plainCandidate({ id: D, short: "d1000000" })],
        blocked: [
            {
                itemId: "c1000000", role: "queue", blockType: "dependency",
                blockedBy: [{ itemId: "e0000000", role: "work", effectiveUnblockRole: "work", satisfied: false }],
            },
        ],
        schemas: { [C]: plainSchema(), [D]: plainSchema() },
    });
    const doc = plan(snap);
    assert.deepEqual(doc.args.items.map((it) => it.short), ["d1000000"]);
    assert.deepEqual(doc.args.deferred, [{ id: C, reason: "blocked by e0000000 until work (cross-run)" }]);
});

test("S10: chooseMode treats a short and a full form of one parent as the same parent", () => {
    const mk = (pa, pb) => baseSnapshot({
        candidates: [
            plainCandidate({ id: A_ID, short: "aaaaaaaa", parentId: pa }),
            plainCandidate({ id: B_ID, short: "bbbbbbbb", parentId: pb }),
        ],
        schemas: { [A_ID]: plainSchema(), [B_ID]: plainSchema() },
    });
    assert.equal(chooseMode(mk(PARENT_ID, "cccccccc")), "shared");
    assert.equal(chooseMode(mk("cccccccc", PARENT_ID)), "shared");
    assert.equal(chooseMode(mk(PARENT_ID, PARENT_ID)), "shared");
    assert.equal(chooseMode(mk(PARENT_ID, "dddddddd-4444-4444-8444-444444444444")), "per-item");
    assert.equal(chooseMode(mk(PARENT_ID, "dddddddd")), "per-item");
});

test("S10: buildPlanDoc picks shared worktree mode when parents differ only in id form", () => {
    const snap = pairSnapshot();
    snap.candidates[1].parentId = "cccccccc";
    assert.equal(plan(snap).args.worktreeMode, "shared");
});

// -- Probes -----------------------------------------------------------------

test("probe: the same item listed short and full in blocked[] plans like the single-entry plan", () => {
    const entry = (ref) => ({
        itemId: ref, role: "queue", blockType: "dependency",
        blockedBy: [{ itemId: A_ID, role: "work", effectiveUnblockRole: "work", satisfied: false }],
    });
    const both = plan(pairSnapshot({ blocked: [entry(B_ID), entry("bbbbbbbb")] }));
    assert.deepEqual(planShape(both), fullPlan());
});

test("probe: candidate order does not change the plan with short references", () => {
    const ab = plan(pairSnapshot({ bRef: "bbbbbbbb", aRef: "aaaaaaaa", candidateOrder: "ab" }));
    const ba = plan(pairSnapshot({ bRef: "bbbbbbbb", aRef: "aaaaaaaa", candidateOrder: "ba" }));
    assert.equal(JSON.stringify(ba), JSON.stringify(ab));
});