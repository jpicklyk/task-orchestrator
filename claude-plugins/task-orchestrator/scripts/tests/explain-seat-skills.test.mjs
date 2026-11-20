// explain prints the plan's seat skills (run-wave Step 0 F8 reads that line) and the run-wave skill
// documents the F8 check and the stopped-seat retry. Oracles: the spec's literal lines and
// hooks/lib/seat-skill-defaults.json; none is read from the implementation.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

import { buildPlanDoc, explainPlan } from "../run-planner-lib.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PLUGIN = path.join(HERE, "..", "..");
const SKILL = readFileSync(path.join(PLUGIN, "skills", "run-wave", "SKILL.md"), "utf8");
const DEFAULTS = JSON.parse(readFileSync(path.join(PLUGIN, "hooks", "lib", "seat-skill-defaults.json"), "utf8"));
const FIXTURE = path.join(HERE, "fixtures", "run-planner", "independent-two.json");

const stage = (seat, seatSkills) => ({ seat, phase: "work", notes: [], dispatch: {}, ...(seatSkills ? { seatSkills } : {}) });
const docOf = (items) => ({ meta: {}, args: { runId: "r-x", items } });
const skillLines = (text) => text.split("\n").filter((l) => l.startsWith("Seat skills:"));

test("E1: distinct names in first-seen order across stages and reviewSeatSkills, one line", () => {
    const doc = docOf([{
        short: "aaaaaaaa", title: "A",
        stages: [stage("planner", ["a", "b"]), stage("implementer", ["b", "c"])],
        reviewSeatSkills: { reviewer: ["c", "d"] },
    }]);
    assert.deepEqual(skillLines(explainPlan(doc)), ["Seat skills: a, b, c, d"]);
});

test("E1b: items in plan order, review skills of item 1 come before stage skills of item 2", () => {
    const doc = docOf([
        { short: "aaaaaaaa", stages: [stage("implementer", ["x"])], reviewSeatSkills: { reviewer: ["y"] } },
        { short: "bbbbbbbb", stages: [stage("implementer", ["z", "x"])] },
    ]);
    assert.deepEqual(skillLines(explainPlan(doc)), ["Seat skills: x, y, z"]);
});

test("E1c: reviewSeatSkills with several lane seats are listed in sorted seat order, not insertion order", () => {
    // The spec says "in key order" without naming one; sorted is chosen so the line does not depend on insertion order.
    const doc = docOf([{
        short: "aaaaaaaa",
        stages: [stage("implementer", ["i"])],
        reviewSeatSkills: { "reviewer-b": ["b1", "shared"], "reviewer-a": ["a1", "shared"] },
    }]);
    assert.deepEqual(skillLines(explainPlan(doc)), ["Seat skills: i, a1, shared, b1"]);
});

test("E2: no seat skills prints none, also with no args", () => {
    const doc = docOf([{ short: "aaaaaaaa", stages: [stage("implementer")] }]);
    assert.deepEqual(skillLines(explainPlan(doc)), ["Seat skills: none"]);
    const empty = explainPlan({ meta: {} });
    assert.ok(empty.includes("No admitted items."));
    assert.deepEqual(skillLines(empty), ["Seat skills: none"]);
});

test("E2b: the line sits right after Degradations", () => {
    const lines = explainPlan(docOf([{ short: "a", stages: [stage("implementer", ["q"])] }])).split("\n");
    const i = lines.findIndex((l) => l.startsWith("Degradations:"));
    assert.ok(i >= 0);
    assert.equal(lines[i + 1], "Seat skills: q");
});

test("E3: non-string and empty entries are ignored", () => {
    const doc = docOf([{ short: "aaaaaaaa", stages: [stage("implementer", ["", "a", 7, null])] }]);
    assert.deepEqual(skillLines(explainPlan(doc)), ["Seat skills: a"]);
});

test("E4: a plan without the seatSkills feature lists the defaults-table names, planner then implementer", () => {
    const snap = JSON.parse(readFileSync(FIXTURE, "utf8"));
    snap.candidates = snap.candidates.slice(0, 1);
    snap.features = (snap.features || []).filter((f) => f !== "seatSkills");
    const result = buildPlanDoc(snap, { now: "2026-09-28T12:00:00Z" });
    assert.equal(result.ok, true, JSON.stringify(result.errors));
    const seats = result.doc.args.items[0].stages.map((s) => s.seat);
    assert.ok(seats.includes("planner") && seats.includes("implementer"), seats.join(","));
    const expected = [...DEFAULTS.planner, ...DEFAULTS.implementer].join(", ");
    assert.deepEqual(skillLines(explainPlan(result.doc)), [`Seat skills: ${expected}`]);
});

test("E5: Step 0 has an F8 row after F7, and Step 3 names F8", () => {
    const rows = SKILL.split("\n");
    const f7 = rows.findIndex((l) => l.startsWith("| F7 |"));
    assert.ok(f7 >= 0);
    const f8 = rows[f7 + 1];
    assert.ok(f8.startsWith("| F8 |"), f8.slice(0, 40));
    for (const t of ["Seat skills:", "/reload-skills", "Step 4"]) assert.ok(f8.includes(t), t);
    const step3 = rows.find((l) => l.startsWith("| 3 Plan |"));
    assert.ok(step3.includes("F8"));
});

test("E6: Retrying a stopped seat is documented and the Method A launched row points at it", () => {
    const i = SKILL.indexOf("**Retrying a stopped seat**");
    assert.ok(i >= 0);
    const para = SKILL.slice(i, SKILL.indexOf("\n\n", i));
    for (const t of ["resumeFromRunId", "stage-result", "`transition` `start`", "Step 2"]) assert.ok(para.includes(t), t);
    const row = SKILL.split("\n").find((l) => l.startsWith("| `launched` (Method A) |"));
    assert.ok(row.includes("Retrying a stopped seat"));
});

test("E7: code-mode R1 names F8", () => {
    const text = readFileSync(path.join(PLUGIN, "skills", "run-wave", "references", "code-mode.md"), "utf8");
    assert.ok(/Checkpoint 1, where it runs Step 0 F8/.test(text));
});
