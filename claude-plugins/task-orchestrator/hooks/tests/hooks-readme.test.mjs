// Tests for the 'Mode gating' section of hooks/README.md (item 8cd79bc0, scenario S12 of the frozen test-plan).
//
// Oracle: task-scope AC10 - the section carries one table row per script registered in hooks-config.json
// (two-way: no missing script, no extra row), with columns headless / subagent (`agent_id`) /
// `orchestration.mode` / director, every cell stating the hook's behaviour at this version (non-injecting
// hooks say so), plus a paragraph saying plugin keys live under `extensions.claude-code` and that top-level
// 3.x keys are ignored. The registered-script set is read from hooks-config.json, never from the README.
// Cell wording is cross-checked only where other tests pin the behaviour (S4, S6, S8, S9): the injecting
// hooks answer `{}` or omit guidance; the dispatch guard still guards a director session.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const README = readFileSync(fileURLToPath(new URL('../README.md', import.meta.url)), 'utf8').replace(/\r\n/g, '\n');
const HOOKS_CONFIG = JSON.parse(readFileSync(fileURLToPath(new URL('../hooks-config.json', import.meta.url)), 'utf8'));

function registeredScripts() {
  const found = [];
  const walk = (v) => {
    if (Array.isArray(v)) v.forEach(walk);
    else if (v && typeof v === 'object') {
      if (typeof v.command === 'string') {
        const m = v.command.match(/hooks\/([A-Za-z0-9._-]+\.mjs)/);
        assert.ok(m, `unrecognised hook command: ${v.command}`);
        found.push(m[1]);
      }
      Object.values(v).forEach(walk);
    }
  };
  walk(HOOKS_CONFIG.hooks);
  return [...new Set(found)].sort();
}

function modeGatingSection() {
  const start = README.search(/^## Mode gating\s*$/m);
  assert.ok(start >= 0, 'README has no "## Mode gating" section');
  const rest = README.slice(start + 1);
  const next = rest.search(/^## /m);
  return next < 0 ? README.slice(start) : README.slice(start, start + 1 + next);
}

const cells = (line) => line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim());

function parseTable(section) {
  const lines = section.split('\n').filter((l) => l.trim().startsWith('|'));
  assert.ok(lines.length >= 3, 'the section must hold a table (header, separator, rows)');
  const header = cells(lines[0]);
  assert.ok(/^[\s|:-]+$/.test(lines[1]), 'second table line must be the separator');
  const rows = lines.slice(2).map((l) => ({ line: l, cells: cells(l) }));
  return { header, rows };
}

const scriptOf = (row) => {
  const m = row.cells[0].match(/`([A-Za-z0-9._-]+\.mjs)`/);
  assert.ok(m, `row does not name a script in backticks: ${row.line}`);
  return m[1];
};

test('S12: the section exists and the table header has the four gating columns after the script column', () => {
  const { header } = parseTable(modeGatingSection());
  assert.equal(header.length, 5, `header: ${header.join(' | ')}`);
  assert.match(header[0], /script|hook/i);
  assert.match(header[1], /headless/i);
  assert.match(header[2], /subagent/i);
  assert.match(header[2], /agent_id/);
  assert.match(header[3], /orchestration\.mode/);
  assert.match(header[4], /director/i);
});

test('S12: one row per script registered in hooks-config.json - none missing, none extra, none repeated', () => {
  const { rows } = parseTable(modeGatingSection());
  const documented = rows.map(scriptOf);
  assert.deepEqual([...new Set(documented)].sort(), registeredScripts());
  assert.equal(documented.length, new Set(documented).size, 'a script appears in two rows');
});

test('S12: every row has all five cells filled in', () => {
  const { rows } = parseTable(modeGatingSection());
  for (const row of rows) {
    assert.equal(row.cells.length, 5, row.line);
    for (const cell of row.cells) assert.ok(cell.length > 0, `empty cell in: ${row.line}`);
  }
});

test('S12: a paragraph says plugin keys live under extensions.claude-code and top-level 3.x keys are ignored', () => {
  const section = modeGatingSection();
  const prose = section.split('\n').filter((l) => !l.trim().startsWith('|')).join('\n');
  assert.ok(prose.includes('extensions.claude-code'));
  assert.match(prose, /top-level/i);
  assert.match(prose, /ignored/i);
});

test('S12: the injecting hooks document their gating - {} for orchestration-context, pre-plan, post-plan; guidance omitted for session-start', () => {
  const byScript = Object.fromEntries(parseTable(modeGatingSection()).rows.map((r) => [scriptOf(r), r.cells]));
  for (const name of ['orchestration-context.mjs', 'pre-plan.mjs', 'post-plan.mjs']) {
    const [, headless, subagent, mode, director] = byScript[name];
    assert.ok(headless.includes('{}'), `${name} headless: ${headless}`);
    assert.ok(subagent.includes('{}'), `${name} subagent: ${subagent}`);
    assert.ok(director.includes('{}'), `${name} director: ${director}`);
    assert.match(mode, /off/, `${name} mode: ${mode}`);
  }
  const [, headless, subagent, mode, director] = byScript['session-start.mjs'];
  for (const cell of [headless, subagent, director]) assert.match(cell, /omit/i, `session-start: ${cell}`);
  assert.match(mode, /not read/i);
});

test('S12: the dispatch guard row says a director session is still guarded, and mode off silences it', () => {
  const byScript = Object.fromEntries(parseTable(modeGatingSection()).rows.map((r) => [scriptOf(r), r.cells]));
  const [, , , mode, director] = byScript['dispatch-model-guard.mjs'];
  assert.ok(!director.includes('{}'), `director cell must not claim silence: ${director}`);
  assert.match(director, /guard/i);
  assert.match(mode, /off/);
});

test('S12: hooks that never inject context say so', () => {
  const byScript = Object.fromEntries(parseTable(modeGatingSection()).rows.map((r) => [scriptOf(r), r.cells]));
  for (const name of ['config-sync.mjs', 'plan-capture.mjs']) {
    assert.match(byScript[name].join(' '), /non-injecting/i, name);
  }
});