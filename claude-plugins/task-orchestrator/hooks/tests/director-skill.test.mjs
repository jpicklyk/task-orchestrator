// Tests for the director skill, agent file and docs (item 8aee9676, S12-S14 / AC9-AC11). Oracles: task-scope
// Build 3-5 and Amendment A1 (O1-O10, agent and docs oracles). Files are read at test runtime; assertion
// messages name the missing item, never echo file content.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { parseToCallBlocks, checkToCall, RETIRED_3X_TOOLS } from '../../scripts/lib/contract-checks.mjs';

const rd = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf-8');
const SKILL = rd('../../skills/director/SKILL.md');
const AGENT = rd('../../agents/director.md');
const README = rd('../README.md');
const CHANGELOG = rd('../../../../CHANGELOG.md');
const GUIDE = rd('../../../../current/docs/integration-guides/plugin-skills-hooks.md');
const CATALOG = JSON.parse(rd('../lib/tool-catalog.json'));

function frontmatter(text) {
  assert.ok(text.startsWith('---'), 'file starts with frontmatter fence');
  const lines = text.split(/\r?\n/);
  const end = lines.findIndex((l, i) => i > 0 && l.trim() === '---');
  assert.ok(end > 0, 'frontmatter closing fence');
  return lines.slice(1, end).join('\n');
}
const has = (text, needle) => assert.ok(text.includes(needle), 'missing: ' + needle);

// ---- S12 ----
test('S12 O1: skill frontmatter', () => {
  const fm = frontmatter(SKILL);
  assert.match(fm, /^name:\s*director\s*$/m);
  const desc = fm.split('\n').find((l) => l.startsWith('description:'));
  assert.ok(desc, 'description line');
  for (const t of ['director mode', 'become the director', 'run the board', 'show open decisions', 'answer decisions']) {
    assert.ok(desc.toLowerCase().includes(t), 'description trigger: ' + t);
  }
  assert.match(fm, /^argument-hint:.*\boff\b/m);
});

test('S12 O2: skill body size cap', () => {
  const n = Buffer.byteLength(SKILL, 'utf8');
  assert.ok(n <= 18000, 'skill is ' + n + ' bytes, cap 18000');
});

test('S12 O3: to-call blocks parse, use the director profile and validate', () => {
  const blocks = parseToCallBlocks(SKILL);
  assert.ok(blocks.length >= 5, 'block count ' + blocks.length);
  const pairs = new Set();
  for (const b of blocks) {
    assert.equal(b.error, undefined, 'block at line ' + b.line + ' parse error');
    assert.equal(b.value.profile, 'director', 'block at line ' + b.line + ' profile');
    assert.deepEqual(checkToCall(b.value, CATALOG), [], 'block at line ' + b.line + ' catalog check');
    pairs.add(b.value.tool + ' ' + (b.value.select || b.value.op));
  }
  for (const p of ['runs_read list', 'runs_read frontier', 'runs_read decisions', 'report rollup', 'items_read blocked', 'items_read overview', 'runs_write answer', 'runs_write withdraw']) {
    assert.ok(pairs.has(p), 'missing to-call block: ' + p);
  }
  const list = blocks.find((b) => b.value.tool === 'runs_read' && b.value.select === 'list');
  assert.ok(list, 'runs_read list block');
  assert.equal(list.value.args.status, 'open');
  const statuses = blocks.filter((b) => b.value.tool === 'runs_read' && b.value.select === 'list').map((b) => b.value.args.status);
  assert.ok(statuses.includes('open') && statuses.includes('paused'), 'list blocks for open and paused: ' + statuses);
  const dec = blocks.find((b) => b.value.tool === 'runs_read' && b.value.select === 'decisions');
  assert.ok(dec && dec.value.args.rootId, 'decisions read is root-wide (rootId)');
  assert.equal(dec.value.args.runId, undefined, 'decisions read is not per run');
});

test('S12 O4: dashboard content', () => {
  has(SKILL, 'blockType');
  has(SKILL, 'blockers[]');
  has(SKILL, 'decisions');
  has(SKILL, 'status filter');
  has(SKILL, 'resumable');
  has(SKILL, 'pauseReason');
  has(SKILL, 'needs resume');
});

test('S12 O5: decision answering', () => {
  for (const s of ['answer', 'withdraw', 'decide', 'runs_write']) has(SKILL, s);
});

test('S12 O6: coordinator tier probe', () => {
  for (const s of ['Workflow', 'claude -p', 'ListAgents']) has(SKILL, s);
  assert.match(SKILL, /tier/i);
  assert.match(SKILL, /^\|\s*4\s*\|/m);
  for (const n of [1, 2, 3]) assert.match(SKILL, new RegExp('^\\|\\s*' + n + '\\s*\\|', 'm'), 'tier table row ' + n);
});

test('S12 O7: tool budget statement', () => {
  assert.match(SKILL, /tool budget/i);
  for (const s of ['runs_read', 'runs_write', 'report', 'items_read', 'deps_read', 'Agent', 'SendMessage', 'ListAgents', 'Skill', 'PushNotification', 'run_in_background']) has(SKILL, s);
});

test('S12 O8: server-profile note', () => {
  has(SKILL, '/mcp/director');
  has(SKILL, 'MCP_TOOL_PROFILE=director');
});

test('S12 O9: marker, off, altitude rule', () => {
  for (const s of ['marker', '/task-orchestrator:director off', 'Director altitude rule:', 'Read', 'Edit', 'Write', 'Bash',
    'git status', 'git log', 'git branch', 'gh pr view', 'gh pr list', 'docker ps']) has(SKILL, s);
});

test('S12 O10: no retired 3.x tool names; ASCII only', () => {
  for (const name of RETIRED_3X_TOOLS.filter((n) => n !== 'complete_tree')) {
    assert.equal(new RegExp('(?<![A-Za-z0-9_])' + name + '(?![A-Za-z0-9_])').test(SKILL), false, 'retired name present: ' + name);
  }
  assert.equal(/[^\x00-\x7F]/.test(SKILL), false, 'non-ASCII byte in skill');
});

// ---- S13 ----
test('S13: agent file frontmatter and allow-list', () => {
  const fm = frontmatter(AGENT);
  assert.match(fm, /^name:\s*director\s*$/m);
  assert.match(fm, /^model:\s*inherit\s*$/m);
  const line = fm.split('\n').find((l) => l.startsWith('tools:'));
  assert.ok(line, 'tools line');
  const tools = line.slice('tools:'.length).split(',').map((t) => t.trim());
  for (const t of ['Agent', 'Skill', 'SendMessage', 'ListAgents', 'PushNotification']) assert.ok(tools.includes(t), 'tools missing: ' + t);
  for (const t of ['runs_read', 'runs_write', 'report', 'items_read', 'deps_read']) {
    assert.ok(tools.includes('mcp__mcp-task-orchestrator-director__' + t), 'tools missing director MCP tool: ' + t);
  }
  for (const t of ['Read', 'Edit', 'Write', 'Bash', 'NotebookEdit', 'Glob', 'Grep', 'WebFetch']) assert.ok(!tools.includes(t), 'tools must not include: ' + t);
});

test('S13: agent body invokes the skill and names init', () => {
  has(AGENT, 'task-orchestrator:director');
  has(AGENT, '/task-orchestrator:init');
});

// ---- S14 ----
test('S14: README Mode gating rows for both scripts', () => {
  assert.ok(README.includes('## Mode gating'), 'Mode gating heading');
  const rows = README.split(/\r?\n/);
  for (const s of ['director-guard.mjs', 'director-context.mjs']) {
    assert.ok(rows.some((l) => l.startsWith('| `' + s + '`')), 'README row: ' + s);
  }
});

test('S14: CHANGELOG [4.0.0] ### Plugin has a director bullet', () => {
  const lines = CHANGELOG.split(/\r?\n/);
  const u = lines.findIndex((l) => l.startsWith('## [4.0.0]'));
  assert.ok(u >= 0, '4.0.0 heading');
  let end = lines.findIndex((l, i) => i > u && l.startsWith('## ['));
  if (end < 0) end = lines.length;
  const sec = lines.slice(u, end);
  const p = sec.findIndex((l) => l.trim() === '### Plugin');
  assert.ok(p >= 0, 'Plugin subsection in 4.0.0');
  let pend = sec.findIndex((l, i) => i > p && l.startsWith('### '));
  if (pend < 0) pend = sec.length;
  const bullets = sec.slice(p + 1, pend).filter((l) => l.startsWith('- ') && l.toLowerCase().includes('director'));
  assert.ok(bullets.length >= 1, 'director bullet under 4.0.0 Plugin');
});

test('S14: integration guide has the Director Guard heading and a director skill row', () => {
  const lines = GUIDE.split(/\r?\n/);
  assert.ok(lines.some((l) => l.trim() === '### Director Guard'), 'Director Guard heading');
  assert.ok(lines.some((l) => l.startsWith('|') && l.includes('/task-orchestrator:director')), 'User Skills row for director');
});
