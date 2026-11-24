// Tests for hooks/lib/session-profile.mjs (item a877f204, scenarios T1-T3 and T9 of its specification).
// Oracles: specification decision D1 (profile precedence, the lifecycle-free predicate, fail-open false),
// D7-D9 (the three skill sections) and hooks/lib/tool-catalog.json for call validity.

import './hermetic-claude-config.mjs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { profileOfRegistrations, isLifecycleFreeConfig, isKnowledgeSession } from '../lib/session-profile.mjs';
import { parseToCallBlocks, checkToCall } from '../../scripts/lib/contract-checks.mjs';

const CATALOG = JSON.parse(readFileSync(fileURLToPath(new URL('../lib/tool-catalog.json', import.meta.url)), 'utf-8'));
const SKILLS = fileURLToPath(new URL('../../skills/', import.meta.url));
const NL = String.fromCharCode(10);
const CRLF = String.fromCharCode(13, 10);

const row = (key, profile) => ({ key, source: '.mcp.json', scope: 'project', url: null, profile });

// ---- T1 profileOfRegistrations ----

test('T1: no rows -> null', () => {
  assert.equal(profileOfRegistrations([]), null);
});

test('T1: orchestration only -> orchestration', () => {
  assert.equal(profileOfRegistrations([row('mcp-task-orchestrator', null)]), 'orchestration');
});

test('T1: knowledge only -> knowledge', () => {
  assert.equal(profileOfRegistrations([row('mcp-task-orchestrator-knowledge', 'knowledge')]), 'knowledge');
});

test('T1: knowledge plus orchestration -> orchestration (its tools are served)', () => {
  const rows = [row('mcp-task-orchestrator-knowledge', 'knowledge'), row('mcp-task-orchestrator', null)];
  assert.equal(profileOfRegistrations(rows), 'orchestration');
});

test('T1: director only -> director; director plus knowledge -> knowledge', () => {
  assert.equal(profileOfRegistrations([row('mcp-task-orchestrator-director', 'director')]), 'director');
  const rows = [row('mcp-task-orchestrator-director', 'director'), row('mcp-task-orchestrator-knowledge', 'knowledge')];
  assert.equal(profileOfRegistrations(rows), 'knowledge');
});

test('T1: a knowledge row under a key without task-orchestrator -> null', () => {
  assert.equal(profileOfRegistrations([row('kb', 'knowledge')]), null);
});

test('T1: non-array input -> null', () => {
  assert.equal(profileOfRegistrations(null), null);
  assert.equal(profileOfRegistrations('x'), null);
});

// ---- T2 isLifecycleFreeConfig ----

const cfg = (...lines) => lines.join(NL) + NL;
const TWO_NONE = cfg('project:', '  rootId: 11111111-1111-1111-1111-111111111111', 'work_item_schemas:', '  page:', '    lifecycle: none', '    notes: []', '  topic:', '    lifecycle: none');

test('T2: two lifecycle: none types -> true', () => {
  assert.equal(isLifecycleFreeConfig(TWO_NONE), true);
});

test('T2: one none and one auto -> false', () => {
  assert.equal(isLifecycleFreeConfig(cfg('work_item_schemas:', '  page:', '    lifecycle: none', '  bug:', '    lifecycle: auto')), false);
});

test('T2: no work_item_schemas, or the block with no types -> false', () => {
  assert.equal(isLifecycleFreeConfig(cfg('project:', '  rootId: abc')), false);
  assert.equal(isLifecycleFreeConfig(cfg('work_item_schemas:', 'project:', '  rootId: abc')), false);
  assert.equal(isLifecycleFreeConfig(cfg('work_item_schemas:', '  # nothing yet')), false);
});

test('T2: a quoted none with a trailing comment -> true', () => {
  assert.equal(isLifecycleFreeConfig(cfg('work_item_schemas:', '  page:', '    lifecycle: "none"  # knowledge')), true);
});

test('T2: None (not lowercase) -> false', () => {
  assert.equal(isLifecycleFreeConfig(cfg('work_item_schemas:', '  page:', '    lifecycle: None')), false);
});

test('T2: a type with no lifecycle key -> false', () => {
  assert.equal(isLifecycleFreeConfig(cfg('work_item_schemas:', '  page:', '    lifecycle: none', '  topic:', '    notes: []')), false);
});

test('T2: a nested lifecycle key never counts as the type\'s own', () => {
  assert.equal(isLifecycleFreeConfig(cfg('work_item_schemas:', '  page:', '    notes:', '      - key: a', '        lifecycle: none')), false);
});

test('T2: an inline-form type -> false', () => {
  assert.equal(isLifecycleFreeConfig(cfg('work_item_schemas:', '  page: { lifecycle: none }')), false);
});

test('T2: CRLF line endings give the same answers', () => {
  assert.equal(isLifecycleFreeConfig(TWO_NONE.split(NL).join(CRLF)), true);
  const mixed = cfg('work_item_schemas:', '  page:', '    lifecycle: none', '  bug:', '    lifecycle: auto');
  assert.equal(isLifecycleFreeConfig(mixed.split(NL).join(CRLF)), false);
});

test('T2: non-string input -> false', () => {
  assert.equal(isLifecycleFreeConfig(null), false);
  assert.equal(isLifecycleFreeConfig(undefined), false);
  assert.equal(isLifecycleFreeConfig(''), false);
});

// ---- T3 isKnowledgeSession ----

const ROLE_BEARING = cfg('work_item_schemas:', '  bug:', '    lifecycle: auto');

test('T3: knowledge rows and a role-bearing config -> true', () => {
  assert.equal(isKnowledgeSession({ configText: ROLE_BEARING, registrations: [row('mcp-task-orchestrator-knowledge', 'knowledge')] }), true);
});

test('T3: orchestration rows and a lifecycle-free config -> true', () => {
  assert.equal(isKnowledgeSession({ configText: TWO_NONE, registrations: [row('mcp-task-orchestrator', null)] }), true);
});

test('T3: orchestration rows and a role-bearing config -> false', () => {
  assert.equal(isKnowledgeSession({ configText: ROLE_BEARING, registrations: [row('mcp-task-orchestrator', null)] }), false);
});

test('T3: a throwing input -> false', () => {
  const hostile = new Proxy([], { get() { throw new Error('boom'); } });
  assert.equal(isKnowledgeSession({ configText: ROLE_BEARING, registrations: hostile }), false);
  const badText = { toString() { throw new Error('boom'); } };
  assert.equal(isKnowledgeSession({ configText: badText, registrations: [] }), false);
  assert.equal(isKnowledgeSession(), false);
});

// ---- T9 skill sections ----

function section(skill, heading) {
  const text = readFileSync(`${SKILLS}${skill}/SKILL.md`, 'utf-8').replace(/\r\n/g, NL);
  const start = text.indexOf(`${NL}${heading}${NL}`);
  assert.ok(start >= 0, `${skill}: missing ${heading}`);
  const rest = text.slice(start + heading.length + 2);
  const end = rest.search(/\n## /);
  return end < 0 ? rest : rest.slice(0, end);
}

function blocksOf(body, label) {
  const blocks = parseToCallBlocks(body);
  assert.ok(blocks.length > 0, `${label}: at least one to-call block`);
  for (const b of blocks) {
    assert.ok(!b.error, `${label} line ${b.line}: ${b.error}`);
    assert.deepEqual(checkToCall(b.value, CATALOG), [], `${label} line ${b.line}`);
  }
  return blocks.map((b) => b.value);
}

const servedByKnowledge = (v) => {
  const name = v.select ?? v.op;
  const entry = CATALOG.tools[v.tool]?.entries?.[name];
  return Array.isArray(entry?.profiles) && entry.profiles.includes('knowledge');
};

test('T9: the three sections sit right after Director mode', () => {
  for (const [skill, heading] of [['create-item', '## Lifecycle-free root'], ['work-summary', '## Lifecycle-free root'], ['dependency-manager', '## Lifecycle-free items']]) {
    const text = readFileSync(`${SKILLS}${skill}/SKILL.md`, 'utf-8').replace(/\r\n/g, NL);
    const headings = text.split(NL).filter((l) => l.startsWith('## '));
    assert.equal(headings[headings.indexOf('## Director mode') + 1], heading, skill);
  }
});

test('T9: create-item lifecycle-free section creates with create (never create_tree) and dedups over notes and documents', () => {
  const body = section('create-item', '## Lifecycle-free root');
  const calls = blocksOf(body, 'create-item');
  assert.ok(calls.some((c) => c.tool === 'items_write' && c.op === 'create'));
  assert.ok(!body.includes('create_tree'));
  const search = calls.find((c) => c.tool === 'items_read' && c.select === 'search');
  assert.deepEqual(search.args.kinds, ['item', 'note', 'document']);
  assert.ok(calls.some((c) => c.tool === 'notes_write' && c.op === 'upsert'));
  assert.ok(calls.every(servedByKnowledge), 'every call is knowledge-served');
});

test('T9: work-summary lifecycle-free section reads only knowledge-served calls and avoids the dashboard words', () => {
  const body = section('work-summary', '## Lifecycle-free root');
  const calls = blocksOf(body, 'work-summary');
  for (const c of calls) {
    assert.ok(!['context', 'next', 'history'].includes(c.select), `${c.tool} ${c.select}`);
    assert.notEqual(c.tool, 'runs_read');
  }
  assert.ok(calls.every(servedByKnowledge), 'every call is knowledge-served');
  assert.ok(calls.some((c) => c.tool === 'docs_read' && c.select === 'list' && c.args.kind === 'page'));
  const lower = body.toLowerCase();
  for (const word of ['queued', 'backlog', 'blocked', 'next up', 'empty-board']) assert.ok(!lower.includes(word), word);
});

test('T9: dependency-manager lifecycle-free section passes type relates_to on every deps_write block', () => {
  const body = section('dependency-manager', '## Lifecycle-free items');
  const writes = blocksOf(body, 'dependency-manager').filter((c) => c.tool === 'deps_write');
  assert.ok(writes.length > 0);
  for (const w of writes) assert.equal(w.args.type, 'relates_to', w.op);
  assert.ok(!/\bblocker|\bunblock/i.test(body));
});
