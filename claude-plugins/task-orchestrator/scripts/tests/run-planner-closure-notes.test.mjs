// Regression coverage for item fd401229 (#676): a stage owning implementation-notes or
// session-tracking must receive the closure-notes rule through stage.rules, even when no note
// names it as a skill and dispatch.agent is unset.
//
// BLIND test author: written from the frozen test-plan and diagnosis notes plus the supplied
// declarations; no implementation source was opened. Oracles are cited per scenario.
// Scenario ids S1-S9 match the item's test-plan note.

import { test } from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { deriveStages } from "../run-planner-lib.mjs";
import { loadCore, planFixture, itemFixture } from "./workflow-harness.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const WORKFLOW_PATH = path.join(HERE, "..", "..", "workflows", "implement-wave.js");

const CLOSURE = "closure-notes";
const SERVED = [
    { key: "commit-discipline", rulesVersion: "1" },
    { key: "test-author", rulesVersion: "1" },
    { key: CLOSURE, rulesVersion: "1" },
];
const SERVED_WITHOUT_CLOSURE = SERVED.filter((r) => r.key !== CLOSURE);

function candidate({ role = "queue", source = "ready" } = {}) {
    return {
        id: "fd000000-0000-4000-8000-000000000001",
        short: "fd000000",
        title: "Item fd000000",
        priority: "high",
        complexity: 2,
        type: "scratch-bug",
        role,
        parentId: null,
        traits: ["delegated", "session-tracked", "needs-test-author"],
        hasChildren: false,
        source,
    };
}

// Fixture A: seat-aware, bug-fix-like. dispatch.agent is deliberately absent.
function fixtureA(notesOverride) {
    return {
        status: "ok",
        type: "scratch-bug",
        configFingerprint: "fp-closure",
        configSource: "per-root",
        notes: notesOverride ?? [
            { key: "diagnosis", role: "queue", required: true, seat: "planner" },
            { key: "implementation-notes", role: "work", required: true, seat: "implementer" },
            { key: "session-tracking", role: "work", required: true, seat: "orchestrator" },
            { key: "test-manifest", role: "work", required: true, seat: "test-author", skill: "test-author" },
        ],
        seats: [
            { name: "planner", phase: "queue" },
            { name: "implementer", phase: "work", enters: true },
            { name: "orchestrator", phase: "work", after: ["implementer"] },
            { name: "test-author", phase: "work", after: ["implementer"], readsExclude: ["implementation-notes", "session-tracking"] },
        ],
    };
}

const stageOf = (result, seat) => result.stages.find((s) => s.seat === seat);
const countOf = (list, key) => (list ?? []).filter((k) => k === key).length;

test("S1: implementer owning implementation-notes receives closure-notes exactly once", () => {
    const result = deriveStages(candidate(), fixtureA(), { rulesServed: SERVED, noteOwners: [] });
    const implementer = stageOf(result, "implementer");
    assert.ok(implementer, "implementer stage must exist");
    assert.equal(countOf(implementer.rules, CLOSURE), 1);
});

test("S2: planner and test-author stages do not receive closure-notes", () => {
    const result = deriveStages(candidate(), fixtureA(), { rulesServed: SERVED, noteOwners: [] });
    for (const seat of ["planner", "test-author"]) {
        const stage = stageOf(result, seat);
        assert.ok(stage, `${seat} stage must exist`);
        assert.equal(countOf(stage.rules, CLOSURE), 0, `${seat} rules`);
    }
    // Fixture A is only discriminating if the test-author does get its own skill rule.
    assert.equal(countOf(stageOf(result, "test-author").rules, "test-author"), 1);
});

test("S3: a seat owning only session-tracking receives closure-notes", () => {
    const schema = fixtureA([
        { key: "diagnosis", role: "queue", required: true, seat: "planner" },
        { key: "session-tracking", role: "work", required: true, seat: "implementer" },
    ]);
    const result = deriveStages(candidate(), schema, { rulesServed: SERVED, noteOwners: [] });
    const implementer = stageOf(result, "implementer");
    assert.ok(implementer, "implementer stage must exist");
    assert.equal(countOf(implementer.rules, CLOSURE), 1);
});

test("S4: seat-less schema gives the implicit implementer closure-notes and the planner none", () => {
    const schema = {
        status: "ok",
        type: "scratch-a",
        configFingerprint: "fp-seatless",
        configSource: "per-root",
        notes: [
            { key: "specification", role: "queue", required: true, seat: null },
            { key: "session-tracking", role: "work", required: true, seat: null },
        ],
    };
    const cand = { ...candidate(), type: "scratch-a", traits: [] };
    const result = deriveStages(cand, schema, { rulesServed: SERVED, noteOwners: [] });
    const implementer = stageOf(result, "implementer");
    const planner = stageOf(result, "planner");
    assert.ok(implementer, "implicit implementer stage must exist");
    assert.ok(planner, "implicit planner stage must exist");
    assert.equal(countOf(implementer.rules, CLOSURE), 1);
    assert.equal(countOf(planner.rules, CLOSURE), 0);
});

test("S5: closure-notes not served -> absent from every stage's rules and skills, no closure degradation", () => {
    const result = deriveStages(candidate(), fixtureA(), { rulesServed: SERVED_WITHOUT_CLOSURE, noteOwners: [] });
    assert.ok(result.stages.length >= 2);
    for (const stage of result.stages) {
        assert.equal(countOf(stage.rules, CLOSURE), 0, `${stage.seat} rules`);
        assert.equal(countOf(stage.skills, CLOSURE), 0, `${stage.seat} skills`);
    }
    assert.ok(
        !(result.degradations ?? []).some((d) => String(d).includes("closure")),
        `degradations: ${JSON.stringify(result.degradations)}`,
    );
    assert.ok(
        !(result.warnings ?? []).some((w) => String(w).includes("closure")),
        `warnings: ${JSON.stringify(result.warnings)}`,
    );
});

test("S6: implementation-notes carrying skill closure-notes still yields exactly one", () => {
    const schema = fixtureA([
        { key: "diagnosis", role: "queue", required: true, seat: "planner" },
        { key: "implementation-notes", role: "work", required: true, seat: "implementer", skill: CLOSURE },
    ]);
    const result = deriveStages(candidate(), schema, { rulesServed: SERVED, noteOwners: [] });
    assert.equal(countOf(stageOf(result, "implementer").rules, CLOSURE), 1);
});

test("S7: not-found schema entry -> the sole stage has no closure-notes", () => {
    const result = deriveStages(candidate(), { status: "not-found" }, { rulesServed: SERVED, noteOwners: [] });
    assert.ok(result.stages.length >= 1, "a not-found schema still yields a stage");
    for (const stage of result.stages) {
        assert.equal(countOf(stage.rules ?? [], CLOSURE), 0, `${stage.seat} rules`);
    }
});

test("S8: resumed candidate (role work, no noteOwners) still gives the implementer closure-notes", () => {
    const result = deriveStages(candidate({ role: "work", source: "work" }), fixtureA(), { rulesServed: SERVED, noteOwners: [] });
    assert.ok(!stageOf(result, "planner"), "queue stage skipped for a work-phase item");
    const implementer = stageOf(result, "implementer");
    assert.ok(implementer, "implementer stage must exist");
    assert.equal(countOf(implementer.rules, CLOSURE), 1);
});

function keysLine(prompt) {
    const m = /^[ \t]*Keys:[^\n]*/m.exec(prompt);
    assert.ok(m, "rendered prompt must contain a line starting with 'Keys:'");
    return m[0];
}

test("S9: the implementer seat prompt's Keys line includes closure-notes", () => {
    const result = deriveStages(candidate(), fixtureA(), { rulesServed: SERVED, noteOwners: [] });
    const implementer = stageOf(result, "implementer");
    assert.ok(implementer);
    const item = itemFixture({ short: "fd000000", stages: result.stages, traits: ["delegated", "session-tracked"] });
    const plan = planFixture({ items: [item], rootId: "root-fd", baseSha: "e77c3e95" });
    const core = loadCore(WORKFLOW_PATH);

    const prompt = core.seatPrompt(plan, item, implementer, {});
    const line = keysLine(prompt);
    assert.ok(line.includes(CLOSURE), `Keys line: ${line}`);

    // Falsifiability: the same stage without the planned rule renders a Keys line without it.
    const stripped = { ...implementer, rules: (implementer.rules ?? []).filter((k) => k !== CLOSURE) };
    const strippedLine = keysLine(core.seatPrompt(plan, item, stripped, {}));
    assert.ok(!strippedLine.includes(CLOSURE), `stripped Keys line: ${strippedLine}`);
});

// ---- Probes ----

test("probe: note keys are case-sensitive, 'Implementation-Notes' triggers no rule", () => {
    const schema = fixtureA([
        { key: "diagnosis", role: "queue", required: true, seat: "planner" },
        { key: "Implementation-Notes", role: "work", required: true, seat: "implementer" },
    ]);
    const result = deriveStages(candidate(), schema, { rulesServed: SERVED, noteOwners: [] });
    for (const stage of result.stages) assert.equal(countOf(stage.rules, CLOSURE), 0, stage.seat);
});

test("probe: served key 'Closure-Notes' (wrong case) is not served, so nothing is added", () => {
    const served = [...SERVED_WITHOUT_CLOSURE, { key: "Closure-Notes", rulesVersion: "1" }];
    const result = deriveStages(candidate(), fixtureA(), { rulesServed: served, noteOwners: [] });
    for (const stage of result.stages) {
        assert.equal(countOf(stage.rules, CLOSURE), 0, stage.seat);
        assert.equal(countOf(stage.rules, "Closure-Notes"), 0, stage.seat);
    }
});

test("probe: empty rulesServed adds no closure-notes anywhere", () => {
    const result = deriveStages(candidate(), fixtureA(), { rulesServed: [], noteOwners: [] });
    for (const stage of result.stages) assert.equal(countOf(stage.rules ?? [], CLOSURE), 0, stage.seat);
});

test("probe: a stage owning both keys receives closure-notes once", () => {
    const schema = fixtureA([
        { key: "diagnosis", role: "queue", required: true, seat: "planner" },
        { key: "implementation-notes", role: "work", required: true, seat: "implementer" },
        { key: "session-tracking", role: "work", required: true, seat: "implementer" },
    ]);
    const result = deriveStages(candidate(), schema, { rulesServed: SERVED, noteOwners: [] });
    assert.equal(countOf(stageOf(result, "implementer").rules, CLOSURE), 1);
});

test("probe: duplicate note entries for one key still yield closure-notes once", () => {
    const schema = fixtureA([
        { key: "diagnosis", role: "queue", required: true, seat: "planner" },
        { key: "implementation-notes", role: "work", required: true, seat: "implementer" },
        { key: "implementation-notes", role: "work", required: false, seat: "implementer" },
    ]);
    const result = deriveStages(candidate(), schema, { rulesServed: SERVED, noteOwners: [] });
    assert.equal(countOf(stageOf(result, "implementer").rules, CLOSURE), 1);
});
