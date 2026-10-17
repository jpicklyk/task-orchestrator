// Contract tests for the ```to-call doc-shape check in scripts/lib/contract-checks.mjs (parseToCallBlocks,
// checkToCall) against the generated tool catalog (58facb39). Scenarios S10 and S11 match the item's
// frozen `test-plan` note. Oracles: task-scope AC2 doc-shape rules and the API field tables in
// current/docs/api-reference.md (required flags, dotted children, profile membership), never
// "what checkToCall returns today". Node 22, node:test, no dependencies.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { checkToCall, parseToCallBlocks } from '../../scripts/lib/contract-checks.mjs';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/]$/, '');
const CATALOG = JSON.parse(readFileSync(join(PLUGIN, 'hooks', 'lib', 'tool-catalog.json'), 'utf8'));
const NL = String.fromCharCode(10);
const CR = String.fromCharCode(13);
const FENCE = '`'.repeat(3);

function block(json, info = 'to-call') {
  return [`${FENCE}${info}`, typeof json === 'string' ? json : JSON.stringify(json), FENCE].join(NL);
}

function walkMd(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walkMd(p, out);
    else if (name.endsWith('.md')) out.push(p);
  }
  return out;
}

// ---- S10: the repository -----------------------------------------------------------------

test('S10: every to-call block in skills/**/*.md and agents/*.md parses and validates (zero blocks is allowed today)', () => {
  const files = [
    ...walkMd(join(PLUGIN, 'skills')),
    ...readdirSync(join(PLUGIN, 'agents')).filter((n) => n.endsWith('.md')).map((n) => join(PLUGIN, 'agents', n)),
  ];
  assert.ok(files.length > 0, 'the scan must cover at least one markdown file');
  for (const f of files) {
    const text = readFileSync(f, 'utf8');
    for (const b of parseToCallBlocks(text)) {
      assert.ok(!('error' in b), `${f}:${b.line} ${b.error}`);
      assert.deepEqual(checkToCall(b.value, CATALOG), [], `${f}:${b.line}`);
    }
  }
});

test('S10: the generated call-shapes.md carries no to-call blocks of its own', () => {
  const text = readFileSync(join(PLUGIN, 'skills', '_generated', 'call-shapes.md'), 'utf8');
  assert.deepEqual(parseToCallBlocks(text), []);
});

// ---- S1 (P23 2953cc30): the project skills ---------------------------------------------------
// Oracle: task-scope AC1 - the example calls in <repo>/.claude/skills are ```to-call blocks that
// validate against the generated catalog. Never "what checkToCall returns today"; at least one block
// must exist so an empty walk cannot pass.

const REPO = join(PLUGIN, '..', '..');

test('S1: every to-call block in <repo>/.claude/skills/**/*.md parses and validates, and at least one exists', () => {
  const files = walkMd(join(REPO, '.claude', 'skills'));
  assert.ok(files.length > 0, 'the scan must cover at least one project skill markdown file');
  let found = 0;
  for (const f of files) {
    const text = readFileSync(f, 'utf8');
    for (const b of parseToCallBlocks(text)) {
      found += 1;
      assert.ok(!('error' in b), `${f}:${b.line} ${b.error}`);
      assert.deepEqual(checkToCall(b.value, CATALOG), [], `${f}:${b.line}`);
    }
  }
  assert.ok(found >= 1, 'the project skills must carry at least one to-call block');
});

test('S1 non-vacuity: a retired 3.x call shape in a to-call block is rejected by the same check', () => {
  const retired = block({ tool: 'manage_notes', op: 'upsert', args: UPSERT_ARGS });
  const parsed = parseToCallBlocks(retired);
  assert.equal(parsed.length, 1);
  assert.ok(checkToCall(parsed[0].value, CATALOG).length >= 1, 'a retired tool name must fail the catalog check');
});
// ---- S11: checkToCall --------------------------------------------------------------------

const UPSERT_ARGS = { itemId: '58facb39', key: 'test-manifest', role: 'work', body: 'x' };

test('S11: valid calls produce no errors (op tool, select tool, explicit profile)', () => {
  assert.deepEqual(checkToCall({ tool: 'notes_write', op: 'upsert', args: UPSERT_ARGS }, CATALOG), []);
  assert.deepEqual(checkToCall({ tool: 'notes_write', op: 'upsert', args: UPSERT_ARGS, profile: 'knowledge' }, CATALOG), []);
  assert.deepEqual(checkToCall({ tool: 'items_read', select: 'get', args: { itemId: '58facb39' } }, CATALOG), []);
  assert.deepEqual(checkToCall({ tool: 'advance', op: 'transition', args: { itemId: '58facb39', trigger: 'complete' } }, CATALOG), []);
  assert.deepEqual(checkToCall({ tool: 'report', select: 'usage', args: {}, profile: 'director' }, CATALOG), []);
});

test('S11: idempotencyKey is accepted on an op entry but not on a select entry', () => {
  assert.deepEqual(checkToCall({ tool: 'notes_write', op: 'upsert', args: { ...UPSERT_ARGS, idempotencyKey: 'k-1' } }, CATALOG), []);
  assert.ok(checkToCall({ tool: 'items_read', select: 'get', args: { itemId: '58facb39', idempotencyKey: 'k-1' } }, CATALOG).length >= 1);
});

test('S11: a required non-dotted field that is absent is an error', () => {
  const { role, ...withoutRole } = UPSERT_ARGS;
  assert.ok(role);
  const errs = checkToCall({ tool: 'notes_write', op: 'upsert', args: withoutRole }, CATALOG);
  assert.ok(errs.length >= 1);
  assert.ok(errs.some((e) => e.includes('role')), JSON.stringify(errs));
  const none = checkToCall({ tool: 'advance', op: 'transition', args: { itemId: '58facb39' } }, CATALOG);
  assert.ok(none.some((e) => e.includes('trigger')), JSON.stringify(none));
});

test('S11: absent args, empty args and args of the wrong type are three distinct situations', () => {
  const absent = checkToCall({ tool: 'notes_write', op: 'upsert' }, CATALOG);
  assert.ok(absent.length >= 1, 'args must be present');
  const empty = checkToCall({ tool: 'notes_write', op: 'upsert', args: {} }, CATALOG);
  assert.ok(empty.some((e) => e.includes('itemId')) && empty.some((e) => e.includes('key')) && empty.some((e) => e.includes('role')), JSON.stringify(empty));
  for (const bad of [null, 'x', 3, [], true]) {
    assert.ok(checkToCall({ tool: 'notes_write', op: 'upsert', args: bad }, CATALOG).length >= 1, `args ${JSON.stringify(bad)}`);
  }
  // an empty args object is valid for an entry with no required field
  assert.deepEqual(checkToCall({ tool: 'report', select: 'usage', args: {}, profile: 'director' }, CATALOG), []);
});

test('S11: an arg that is not a field of the entry is an error (actor is not a field)', () => {
  const errs = checkToCall({ tool: 'notes_write', op: 'upsert', args: { ...UPSERT_ARGS, actor: { id: 'x', kind: 'subagent' } } }, CATALOG);
  assert.ok(errs.some((e) => e.includes('actor')), JSON.stringify(errs));
  // a field of a sibling entry is not valid here either (role is not a notes_write delete field)
  const sib = checkToCall({ tool: 'notes_write', op: 'delete', args: { itemId: '58facb39', key: 'k', role: 'work' } }, CATALOG);
  assert.ok(sib.some((e) => e.includes('role')), JSON.stringify(sib));
});

test('S11: an entry outside the requested profile is an error (complete_tree is orchestration-only; mark is REST-only)', () => {
  const args = { rootId: '58facb39' };
  assert.deepEqual(checkToCall({ tool: 'items_write', op: 'complete_tree', args, profile: 'orchestration' }, CATALOG), []);
  assert.deepEqual(checkToCall({ tool: 'items_write', op: 'complete_tree', args }, CATALOG), [], 'profile defaults to orchestration');
  const knowledge = checkToCall({ tool: 'items_write', op: 'complete_tree', args, profile: 'knowledge' }, CATALOG);
  assert.ok(knowledge.length >= 1, 'complete_tree is not available in the knowledge profile');
  const markArgs = { runId: '58facb39', key: 'planned' };
  const restOnly = checkToCall({ tool: 'runs_write', op: 'mark', args: markArgs, profile: 'orchestration' }, CATALOG);
  assert.ok(restOnly.length >= 1, 'mark is REST only: no profile has it');
  const director = checkToCall({ tool: 'report', select: 'usage', args: {} }, CATALOG);
  assert.ok(director.length >= 1, 'profile defaults to orchestration, where report is not available');
});

test('S11: op/select selector mismatches are errors in both directions, as are unknown tool and unknown entry', () => {
  assert.ok(checkToCall({ tool: 'items_read', op: 'get', args: { itemId: '58facb39' } }, CATALOG).length >= 1, 'op on a select tool');
  assert.ok(checkToCall({ tool: 'notes_write', select: 'upsert', args: UPSERT_ARGS }, CATALOG).length >= 1, 'select on an ops tool');
  assert.ok(checkToCall({ tool: 'items_read', args: { itemId: '58facb39' } }, CATALOG).length >= 1, 'select missing');
  assert.ok(checkToCall({ tool: 'notes_write', args: UPSERT_ARGS }, CATALOG).length >= 1, 'op missing');
  assert.ok(checkToCall({ tool: 'notes_write', op: 'upsert', select: 'get', args: UPSERT_ARGS }, CATALOG).length >= 1, 'both selectors');
  assert.ok(checkToCall({ tool: 'manage_notes', op: 'upsert', args: UPSERT_ARGS }, CATALOG).length >= 1, 'retired tool is unknown');
  assert.ok(checkToCall({ tool: 'notes_write', op: 'upserts', args: UPSERT_ARGS }, CATALOG).length >= 1, 'unknown op');
});

test('S11: non-object values and unknown top-level keys are errors', () => {
  for (const bad of [null, 'text', 3, [], undefined]) {
    assert.ok(checkToCall(bad, CATALOG).length >= 1, `value ${JSON.stringify(bad)}`);
  }
  assert.ok(checkToCall({ tool: 'notes_write', op: 'upsert', args: UPSERT_ARGS, extra: 1 }, CATALOG).length >= 1);
  assert.ok(checkToCall({ op: 'upsert', args: UPSERT_ARGS }, CATALOG).length >= 1, 'tool missing');
});

test('S11: nested keys of an object arg are checked against the entry dotted children (budget.* on runs_write open)', () => {
  const base = { rootId: '58facb39', budget: { tokens: 1000, costUsd: 1.5 } };
  assert.deepEqual(checkToCall({ tool: 'runs_write', op: 'open', args: base }, CATALOG), []);
  const bad = checkToCall({ tool: 'runs_write', op: 'open', args: { rootId: '58facb39', budget: { tokens: 1, bogus: 2 } } }, CATALOG);
  assert.ok(bad.some((e) => e.includes('bogus')), JSON.stringify(bad));
  // dotted keys are catalog fields, not top-level arg keys
  const dotted = checkToCall({ tool: 'runs_write', op: 'open', args: { rootId: '58facb39', 'budget.tokens': 1 } }, CATALOG);
  assert.ok(dotted.length >= 1, 'a dotted name is not a top-level arg');
});

test('S11: parseToCallBlocks returns value and 1-based opening line for each fenced to-call block', () => {
  const value = { tool: 'items_read', select: 'get', args: { itemId: '58facb39' } };
  const md = ['# Title', '', 'prose', block(value), '', 'more', block({ tool: 'report', select: 'usage', args: {} })].join(NL);
  const got = parseToCallBlocks(md);
  assert.equal(got.length, 2);
  assert.equal(got[0].line, 4);
  assert.deepEqual(got[0].value, value);
  assert.equal(got[1].line, 9);
  assert.ok(!('error' in got[0]));
});

test('S11: bad JSON and an unterminated fence are errors carrying the opening line', () => {
  const badJson = parseToCallBlocks(['intro', block('{ not json')].join(NL));
  assert.equal(badJson.length, 1);
  assert.equal(badJson[0].line, 2);
  assert.equal(typeof badJson[0].error, 'string');
  assert.ok(!('value' in badJson[0]));

  const unterminated = parseToCallBlocks(['a', 'b', `${FENCE}to-call`, '{"tool":"report"}'].join(NL));
  assert.equal(unterminated.length, 1);
  assert.equal(unterminated[0].line, 3);
  assert.equal(typeof unterminated[0].error, 'string');
});

test('S11 probes: only an exact opening fence line opens a block (other fence kinds and casings are ignored)', () => {
  const json = JSON.stringify({ tool: 'report', select: 'usage', args: {} });
  for (const opener of ['~~~to-call', `${FENCE}TO-CALL`, `${FENCE}to-call extra`, `${FENCE}json`, `${FENCE}`, ` ${FENCE}to-call`]) {
    const md = [opener, json, opener.trim().startsWith('~') ? '~~~' : FENCE].join(NL);
    assert.deepEqual(parseToCallBlocks(md), [], `opener ${JSON.stringify(opener)} must not open a to-call block`);
  }
});

test('S11 probe: CRLF markdown parses the same as LF markdown', () => {
  const value = { tool: 'items_read', select: 'get', args: { itemId: '58facb39' } };
  const lf = ['x', block(value), 'y'].join(NL);
  const crlf = lf.split(NL).join(CR + NL);
  assert.deepEqual(parseToCallBlocks(crlf), parseToCallBlocks(lf));
  assert.equal(parseToCallBlocks(crlf).length, 1);
});

test('S11 probe: two adjacent blocks and a block directly after prose are both found; markdown with none yields []', () => {
  const a = block({ tool: 'report', select: 'usage', args: {} });
  assert.equal(parseToCallBlocks([a, a].join(NL)).length, 2);
  assert.deepEqual(parseToCallBlocks('plain text only'), []);
  assert.deepEqual(parseToCallBlocks(''), []);
});
