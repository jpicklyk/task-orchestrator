// Freshness + content tests for scripts/gen-call-shapes.mjs and its five managed outputs (58facb39).
// Scenarios S1-S5 match the item's frozen `test-plan` note. Oracles are independent of the generator:
//   G   = current/src/test/resources/golden/tools/<tool>.<profile>.json  (profile membership via the
//         select/op enum of each profile's golden; write tools = goldens whose inputSchema has `ops`)
//   API = current/docs/api-reference.md  (entry headings, field tables, Errors lines, error catalog)
// Never "what the generator prints today". Node 22, node:test, no dependencies.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  MANAGED_FILES,
  OUT_CALL_SHAPES,
  OUT_CATALOG,
  OUT_TOOL_NAMES,
  OUT_WORKFLOWS,
  REGION_BEGIN,
  REGION_END,
  buildCatalog,
  checkOutputs,
  renderOutputs,
  replaceRegion,
} from '../../scripts/gen-call-shapes.mjs';
import { TOOLS, PROFILES, WRITE_TOOLS, ERROR_KINDS } from '../lib/tool-names.mjs';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/]$/, '');
const REPO = join(PLUGIN, '..', '..');
const GOLDEN_DIR = join(REPO, 'current', 'src', 'test', 'resources', 'golden', 'tools');
const API_PATH = join(REPO, 'current', 'docs', 'api-reference.md');
const SCRIPT = join(PLUGIN, 'scripts', 'gen-call-shapes.mjs');
const CR = String.fromCharCode(13);

const tmpDirs = [];
after(() => {
  for (const d of tmpDirs) rmSync(d, { recursive: true, force: true });
});

function tmpCopyOfPlugin() {
  const d = mkdtempSync(join(tmpdir(), 'cfresh-'));
  tmpDirs.push(d);
  cpSync(PLUGIN, d, { recursive: true });
  return d;
}

function cli(args) {
  const r = spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' });
  return { status: r.status, stdout: r.stdout, stderr: r.stderr };
}

// ---- independent oracle derivation -------------------------------------------------------

function readGoldens() {
  const out = {}; // tool -> profile -> { kind, names }
  for (const f of readdirSync(GOLDEN_DIR).filter((x) => x.endsWith('.json'))) {
    const [tool, profile] = f.split('.');
    const props = JSON.parse(readFileSync(join(GOLDEN_DIR, f), 'utf8')).inputSchema.properties;
    const rec = props.ops
      ? { kind: 'ops', names: props.ops.items.properties.op.enum }
      : { kind: 'select', names: props.select.enum };
    (out[tool] ||= {})[profile] = rec;
  }
  return out;
}

function parseApi() {
  const text = readFileSync(API_PATH, 'utf8');
  const heading = /^\*\*`([a-z_]+)` (op|select) `([a-z_]+)`\*\*/gm;
  const marks = [];
  let m;
  while ((m = heading.exec(text)) !== null) marks.push({ tool: m[1], kind: m[2], name: m[3], start: m.index });
  const entries = marks.map((mk, i) => {
    const endCandidates = [text.length];
    if (i + 1 < marks.length) endCandidates.push(marks[i + 1].start);
    const close = text.indexOf('<!-- /generated:tool ', mk.start);
    if (close !== -1) endCandidates.push(close);
    const section = text.slice(mk.start, Math.min(...endCandidates));
    const fields = {};
    for (const row of section.matchAll(/^\| `([^`]+)` \| (.*?) \| (yes|no) \| /gm)) {
      fields[row[1]] = { required: row[3] === 'yes' };
    }
    const errLine = section.match(/^Errors: (.*)$/m);
    const errors = errLine ? [...errLine[1].matchAll(/`([a-z_]+)`/g)].map((x) => x[1]).sort() : [];
    return { tool: mk.tool, kind: mk.kind, name: mk.name, fields, errors };
  });
  const catBlock = text.slice(text.indexOf('<!-- generated:error-catalog -->'), text.indexOf('<!-- /generated:error-catalog -->'));
  const errorCatalog = {};
  for (const row of catBlock.matchAll(/^\| `([a-z_]+)` \| (permanent|transient|shedding) \| (\d+) \|/gm)) {
    errorCatalog[row[1]] = { kind: row[2], http: Number(row[3]) };
  }
  return { entries, errorCatalog };
}

const GOLDENS = readGoldens();
const API = parseApi();

function expectedProfiles(tool, name) {
  return Object.keys(GOLDENS[tool] || {}).filter((p) => GOLDENS[tool][p].names.includes(name)).sort();
}

const catalogFile = JSON.parse(readFileSync(join(PLUGIN, OUT_CATALOG), 'utf8'));

// ---- S1 ----------------------------------------------------------------------------------

test('S1: --check on the repository exits 0 with no stale paths', () => {
  const r = cli(['--check']);
  assert.equal(r.status, 0, `stderr: ${r.stderr}`);
  assert.deepEqual(checkOutputs().stale, []);
});

test('S1: managed-file set is exactly the five declared outputs', () => {
  assert.deepEqual(
    [...MANAGED_FILES].sort(),
    [OUT_CALL_SHAPES, OUT_CATALOG, OUT_TOOL_NAMES, ...OUT_WORKFLOWS].sort(),
  );
  assert.equal(MANAGED_FILES.length, 5);
  assert.deepEqual([...renderOutputs(buildCatalog(), { pluginRoot: PLUGIN }).keys()].sort(), [...MANAGED_FILES].sort());
});

test('S1: rendering is deterministic and checked-in outputs equal a fresh render (LF only, one trailing newline)', () => {
  const a = renderOutputs(buildCatalog(), { pluginRoot: PLUGIN });
  const b = renderOutputs(buildCatalog(), { pluginRoot: PLUGIN });
  assert.deepEqual([...a.entries()], [...b.entries()]);
  for (const [rel, text] of a) {
    assert.equal(readFileSync(join(PLUGIN, rel), 'utf8'), text, `${rel} differs from a fresh render`);
    assert.ok(!text.includes(CR), `${rel} must be LF only`);
    assert.ok(text.endsWith('\n') && !text.endsWith('\n\n'), `${rel} must end with exactly one newline`);
  }
});

test('S1: write mode on a stale copy regenerates byte-identical files, leaves other files alone, then --check exits 0', () => {
  const tmp = tmpCopyOfPlugin();
  const args = ['--plugin-root', tmp, '--golden-dir', GOLDEN_DIR, '--api-reference', API_PATH];
  writeFileSync(join(tmp, OUT_CATALOG), '{}\n');
  rmSync(join(tmp, OUT_CALL_SHAPES));
  const w = cli(args);
  assert.equal(w.status, 0, w.stderr);
  for (const rel of MANAGED_FILES) {
    assert.ok(readFileSync(join(tmp, rel)).equals(readFileSync(join(PLUGIN, rel))), `${rel} not regenerated byte-identically`);
  }
  assert.ok(readFileSync(join(tmp, 'hooks', 'hooks-config.json')).equals(readFileSync(join(PLUGIN, 'hooks', 'hooks-config.json'))));
  const c = cli(['--check', ...args]);
  assert.equal(c.status, 0, c.stderr);
  // writing again changes nothing
  const before = MANAGED_FILES.map((rel) => readFileSync(join(tmp, rel)));
  assert.equal(cli(args).status, 0);
  MANAGED_FILES.forEach((rel, i) => assert.ok(before[i].equals(readFileSync(join(tmp, rel))), `${rel} changed on a second write`));
});

// ---- S2 ----------------------------------------------------------------------------------

test('S2: editing tool-catalog.json and deleting call-shapes.md makes --check exit 1 naming exactly those two paths', () => {
  const tmp = tmpCopyOfPlugin();
  const args = ['--plugin-root', tmp, '--golden-dir', GOLDEN_DIR, '--api-reference', API_PATH];
  writeFileSync(join(tmp, OUT_CATALOG), readFileSync(join(tmp, OUT_CATALOG), 'utf8') + ' ');
  rmSync(join(tmp, OUT_CALL_SHAPES));
  assert.ok(!existsSync(join(tmp, OUT_CALL_SHAPES)));
  const r = cli(['--check', ...args]);
  assert.equal(r.status, 1, `stdout: ${r.stdout} stderr: ${r.stderr}`);
  assert.ok(r.stderr.includes(OUT_CATALOG), `stderr must name ${OUT_CATALOG}: ${r.stderr}`);
  assert.ok(r.stderr.includes(OUT_CALL_SHAPES), `stderr must name ${OUT_CALL_SHAPES}: ${r.stderr}`);
  for (const other of [OUT_TOOL_NAMES, ...OUT_WORKFLOWS]) {
    assert.ok(!r.stderr.includes(other), `stderr must not name fresh file ${other}`);
  }
  assert.ok(r.stderr.includes('gen-call-shapes.mjs'), 'stderr must print the regenerate command');
  assert.deepEqual(
    checkOutputs({ pluginRoot: tmp, goldenDir: GOLDEN_DIR, apiReferencePath: API_PATH }).stale,
    [OUT_CATALOG, OUT_CALL_SHAPES].sort(),
  );
});

test('S2: one drifted workflow region makes only that workflow stale', () => {
  const tmp = tmpCopyOfPlugin();
  const rel = OUT_WORKFLOWS[0];
  const text = readFileSync(join(tmp, rel), 'utf8');
  assert.ok(text.includes(REGION_BEGIN) && text.includes(REGION_END));
  writeFileSync(join(tmp, rel), text.replace('ADVANCE:', 'ADVANCED:'));
  const { stale } = checkOutputs({ pluginRoot: tmp, goldenDir: GOLDEN_DIR, apiReferencePath: API_PATH });
  assert.deepEqual(stale, [rel]);
});

test('S2 probe: inconsistent inputs exit 2 (golden property missing; API profile phrase disagrees)', () => {
  // (a) a non-dotted API field absent from that tool's golden properties
  const goldenCopy = mkdtempSync(join(tmpdir(), 'cfresh-g-'));
  tmpDirs.push(goldenCopy);
  cpSync(GOLDEN_DIR, goldenCopy, { recursive: true });
  // the check compares against the tool's goldens as a whole, so the field is removed from every profile
  for (const f of readdirSync(goldenCopy).filter((x) => x.startsWith('notes_write.'))) {
    const gp = join(goldenCopy, f);
    const g = JSON.parse(readFileSync(gp, 'utf8'));
    delete g.inputSchema.properties.ops.items.properties.key;
    writeFileSync(gp, JSON.stringify(g, null, 2) + '\n');
  }
  const tmp = tmpCopyOfPlugin();
  const a = cli(['--check', '--plugin-root', tmp, '--golden-dir', goldenCopy, '--api-reference', API_PATH]);
  assert.equal(a.status, 2, `golden missing a field: stderr ${a.stderr}`);

  // (b) API profile phrase disagrees with golden membership
  const apiCopyDir = mkdtempSync(join(tmpdir(), 'cfresh-a-'));
  tmpDirs.push(apiCopyDir);
  const apiCopy = join(apiCopyDir, 'api-reference.md');
  const phrase = '**`notes_write` op `upsert`** (knowledge and orchestration profiles';
  const apiText = readFileSync(API_PATH, 'utf8');
  assert.ok(apiText.includes(phrase), 'fixture precondition: API heading phrase present');
  writeFileSync(apiCopy, apiText.replace(phrase, '**`notes_write` op `upsert`** (orchestration profile only'));
  const b = cli(['--check', '--plugin-root', tmp, '--golden-dir', GOLDEN_DIR, '--api-reference', apiCopy]);
  assert.equal(b.status, 2, `API phrase disagreement: stderr ${b.stderr}`);
});

// ---- S3 ----------------------------------------------------------------------------------

test('S3: TOOLS equals the distinct golden tool basenames and contains runs_read, runs_write, report', () => {
  const derived = Object.keys(GOLDENS).sort();
  assert.equal(derived.length, 13);
  assert.deepEqual([...TOOLS], derived);
  for (const t of ['runs_read', 'runs_write', 'report']) assert.ok(TOOLS.includes(t), `${t} missing from TOOLS`);
  assert.ok(Object.isFrozen(TOOLS));
});

test('S3: PROFILES[p] is the sorted list of tools with a <tool>.<p>.json golden; frozen', () => {
  const profileNames = [...new Set(Object.values(GOLDENS).flatMap((byProfile) => Object.keys(byProfile)))].sort();
  assert.deepEqual(profileNames, ['director', 'knowledge', 'orchestration']);
  assert.deepEqual(Object.keys(PROFILES).sort(), profileNames);
  for (const p of profileNames) {
    const expected = Object.keys(GOLDENS).filter((t) => GOLDENS[t][p]).sort();
    assert.deepEqual([...PROFILES[p]], expected, `PROFILES.${p}`);
    assert.ok(Object.isFrozen(PROFILES[p]), `PROFILES.${p} frozen`);
  }
  assert.ok(Object.isFrozen(PROFILES));
});

test('S3: WRITE_TOOLS is the sorted set of tools whose golden has ops; frozen', () => {
  const expected = Object.keys(GOLDENS).filter((t) => Object.values(GOLDENS[t]).some((r) => r.kind === 'ops')).sort();
  assert.deepEqual([...WRITE_TOOLS], expected);
  assert.deepEqual(expected, ['advance', 'config', 'deps_write', 'docs_write', 'items_write', 'notes_write', 'runs_write']);
  assert.ok(Object.isFrozen(WRITE_TOOLS));
});

test('S3: ERROR_KINDS maps every API error-catalog code to its kind; frozen', () => {
  const expected = Object.fromEntries(Object.entries(API.errorCatalog).map(([c, v]) => [c, v.kind]));
  assert.ok(Object.keys(expected).length > 10, 'fixture precondition: API error catalog parsed');
  assert.deepEqual({ ...ERROR_KINDS }, expected);
  assert.ok(Object.isFrozen(ERROR_KINDS));
  assert.deepEqual(
    Object.fromEntries(Object.entries(catalogFile.errors).map(([c, v]) => [c, { kind: v.kind, http: v.http }])),
    API.errorCatalog,
  );
});

// ---- S4 ----------------------------------------------------------------------------------

test('S4: catalog has exactly the 72 API entries, each with profiles derived from the goldens (REST-only = [])', () => {
  assert.equal(API.entries.length, 72);
  const catalogCount = Object.values(catalogFile.tools).reduce((n, t) => n + Object.keys(t.entries).length, 0);
  assert.equal(catalogCount, 72);
  assert.deepEqual(Object.keys(catalogFile.tools).sort(), Object.keys(GOLDENS).sort());
  for (const e of API.entries) {
    const got = catalogFile.tools[e.tool].entries[e.name];
    assert.ok(got, `${e.tool}.${e.name} missing from catalog`);
    assert.deepEqual(got.profiles, expectedProfiles(e.tool, e.name), `${e.tool}.${e.name} profiles`);
    assert.equal(got.anchor, `${e.tool}-${e.name}`);
    assert.equal(catalogFile.tools[e.tool].kind, e.kind === 'op' ? 'ops' : 'select');
  }
});

test('S4: every entry lists the API table fields (dotted included) with the right required flag and the API Errors line', () => {
  for (const e of API.entries) {
    const got = catalogFile.tools[e.tool].entries[e.name];
    assert.deepEqual(Object.keys(got.fields).sort(), Object.keys(e.fields).sort(), `${e.tool}.${e.name} field names`);
    for (const [f, spec] of Object.entries(e.fields)) {
      assert.equal(got.fields[f].required, spec.required, `${e.tool}.${e.name}.${f} required`);
      assert.equal(typeof got.fields[f].type, 'string', `${e.tool}.${e.name}.${f} type`);
    }
    assert.deepEqual(got.errors, e.errors, `${e.tool}.${e.name} errors`);
  }
});

test('S4: named spot checks from the plan (notes_write.upsert, items_write.complete_tree, runs_write.mark, report.usage, advance.transition)', () => {
  const up = catalogFile.tools.notes_write.entries.upsert;
  assert.deepEqual(up.profiles, ['knowledge', 'orchestration']);
  assert.deepEqual(Object.keys(up.fields).filter((f) => up.fields[f].required).sort(), ['itemId', 'key', 'role']);
  assert.equal(up.fields.itemId.type, 'string');
  assert.equal(up.destructive, false);
  assert.equal(up.capability, 'write-notes');
  assert.equal(catalogFile.tools.notes_write.entries.delete.destructive, true);
  assert.deepEqual(catalogFile.tools.items_write.entries.complete_tree.profiles, ['orchestration']);
  assert.deepEqual(catalogFile.tools.runs_write.entries.mark.profiles, []);
  assert.deepEqual(catalogFile.tools.report.entries.usage.profiles, ['director']);
  const tr = catalogFile.tools.advance.entries.transition;
  assert.equal(tr.capability, 'advance');
  assert.deepEqual(Object.keys(tr.fields).sort(), ['credentialRefs', 'decisionId', 'itemId', 'reason', 'summary', 'trigger']);
  assert.deepEqual(Object.keys(tr.fields).filter((f) => tr.fields[f].required).sort(), ['itemId', 'trigger']);
  assert.ok(Object.keys(catalogFile.tools.items_write.entries.create_tree.fields).includes('root.id'), 'dotted fields are kept');
});

test('S4: buildCatalog() equals the checked-in catalog JSON', () => {
  assert.deepEqual(JSON.parse(JSON.stringify(buildCatalog())), catalogFile);
});

// ---- S5 ----------------------------------------------------------------------------------

test('S5: call-shapes.md has exactly one unique <a id="<tool>-<name>"> per API entry', () => {
  const md = readFileSync(join(PLUGIN, OUT_CALL_SHAPES), 'utf8');
  const ids = [...md.matchAll(/<a id="([^"]+)"><\/a>/g)].map((m) => m[1]);
  assert.equal(new Set(ids).size, ids.length, 'anchors must be unique');
  assert.deepEqual([...ids].sort(), API.entries.map((e) => `${e.tool}-${e.name}`).sort());
  for (const t of TOOLS) {
    assert.ok(md.includes(`## \`${t}\``), `section for ${t}`);
  }
  assert.ok(md.includes('| Field | Type | Required |'));
});

// ---- generated workflow regions ----------------------------------------------------------

test('S3: workflow TOOL_NAMES region sits inside the core slice, is frozen, and maps every tool UPPER_SNAKE -> name', () => {
  for (const rel of OUT_WORKFLOWS) {
    const text = readFileSync(join(PLUGIN, rel), 'utf8');
    const b = text.indexOf(REGION_BEGIN);
    const e = text.indexOf(REGION_END);
    assert.ok(b !== -1 && e > b, `${rel} markers`);
    assert.equal(text.indexOf(REGION_BEGIN, b + 1), -1, `${rel} has one begin marker`);
    assert.ok(text.indexOf('// @core-begin') < b && e < text.indexOf('// @core-end'), `${rel} region inside @core slice`);
    const lines = text.split(String.fromCharCode(10));
    const bi = lines.findIndex((l) => l.includes(REGION_BEGIN));
    const ei = lines.findIndex((l) => l.includes(REGION_END));
    assert.ok(bi !== -1 && ei > bi, `${rel} marker lines`);
    const region = lines.slice(bi + 1, ei).join(String.fromCharCode(10));
    const names = new Function(`${region}\nreturn TOOL_NAMES;`)();
    assert.ok(Object.isFrozen(names), `${rel} TOOL_NAMES frozen`);
    assert.deepEqual({ ...names }, Object.fromEntries(Object.keys(GOLDENS).sort().map((t) => [t.toUpperCase(), t])));
  }
});

test('replaceRegion: replaces only the marked region and throws naming the label when markers are absent', () => {
  const text = ['before', REGION_BEGIN + ' (generated)', 'old body', REGION_END, 'after', ''].join('\n');
  const out = replaceRegion(text, 'const X = 1', 'wf-label');
  assert.ok(out.startsWith('before\n'));
  assert.ok(out.endsWith('after\n'));
  assert.ok(out.includes('const X = 1'));
  assert.ok(!out.includes('old body'));
  assert.throws(() => replaceRegion('no markers here\n', 'x', 'wf-label'), /wf-label/);
  assert.throws(() => replaceRegion([REGION_END, REGION_BEGIN].join('\n'), 'x', 'wf-label'), /wf-label/, 'end before begin');
});
