// Guard tests for the seat agents and bundled protocol rules on 4.0 (item c6740063). Scenarios S1-S14
// match the item's frozen `test-plan` note. Oracles: task-scope AC1-AC9, the generated tool catalog
// (hooks/lib/tool-catalog.json), RETIRED_3X_TOOLS and checkToCall in scripts/lib/contract-checks.mjs,
// and the API contract in current/docs/api-reference.md; never "what the prose says today".
// Repo-reading, no fixtures. Assertion messages carry file names, line numbers and token names only,
// never file content. Node 22, node:test, no dependencies.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { RETIRED_3X_TOOLS, parseToCallBlocks, checkToCall } from '../../scripts/lib/contract-checks.mjs';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url)).replace(/[/\\]$/, '');
const CATALOG = JSON.parse(readFileSync(join(PLUGIN, 'hooks', 'lib', 'tool-catalog.json'), 'utf8'));
const BASELINE = JSON.parse(readFileSync(join(PLUGIN, 'scripts', 'lib', 'contract-names-baseline.json'), 'utf8'));
const MANIFEST = JSON.parse(readFileSync(join(PLUGIN, 'bundled-rules', 'manifest.json'), 'utf8'));

const SEAT_AGENTS = ['planner', 'implementer', 'reviewer', 'test-author'];
const PROTOCOL_KEYS = ['protocol.entry-seat', 'protocol.in-phase-seat', 'protocol.read-only-agent'];
const TICK = String.fromCharCode(96);
const NL = String.fromCharCode(10);
const CRLF = String.fromCharCode(13) + NL;

const normalize = (s) => s.split(CRLF).join(NL);
const readText = (p) => normalize(readFileSync(p, 'utf8'));
const agentPath = (name) => join(PLUGIN, 'agents', `${name}.md`);
const rulePath = (key) => join(PLUGIN, 'bundled-rules', `${key}.md`);
const allAgentFiles = () => readdirSync(join(PLUGIN, 'agents')).filter((n) => n.endsWith('.md')).sort();
const tick = (s) => `${TICK}${s}${TICK}`;

// ---- helpers (their own behaviour is pinned by the S13/S14 tests below) ---------------------------

// S1 oracle: contract-checks.mjs RETIRED_3X_TOOLS (complete_tree exempt, it is also an items_write op),
// api-reference.md:20-22 (no actor field), task-scope AC1 (no invalid_actor / rejected_by_policy).
const BARE_RETIRED = RETIRED_3X_TOOLS.filter((n) => n !== 'complete_tree');
const RETIRED_RES = BARE_RETIRED.map((name) => ({ name, re: new RegExp(`(?<![a-z0-9])${name}(?![a-z0-9])`, 'i') }));
const OTHER_RES = [
  { name: 'invalid_actor', re: /invalid[ _]actor/i },
  { name: 'rejected_by_policy', re: /rejected[ _]by[ _]policy/i },
  { name: 'actor', re: /(?<![a-z])actor/i },
];

function retiredHits(text) {
  const hits = [];
  normalize(text).split(NL).forEach((line, i) => {
    for (const { name, re } of [...RETIRED_RES, ...OTHER_RES]) {
      if (re.test(line)) hits.push(`${i + 1}:${name}`);
    }
  });
  return hits;
}

// The text of a `## <heading>` section, up to EOF or the next `## ` line. null = section absent,
// '' = present but empty (two distinct states).
function sectionOf(text, heading) {
  const lines = normalize(text).split(NL);
  const start = lines.findIndex((l) => l.trimEnd() === `## ${heading}`);
  if (start === -1) return null;
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i += 1) {
    if (lines[i].startsWith('## ')) {
      end = i;
      break;
    }
  }
  return lines.slice(start + 1, end).join(NL);
}

function stripFences(text) {
  const out = [];
  let inFence = false;
  for (const line of text.split(NL)) {
    if (line.trimStart().startsWith(TICK.repeat(3))) {
      inFence = !inFence;
      continue;
    }
    if (!inFence) out.push(line);
  }
  return out.join(NL);
}

function backtickedTokens(text) {
  const re = new RegExp(`${TICK}([^${TICK}\\n]+)${TICK}`, 'g');
  return [...stripFences(text).matchAll(re)].map((m) => m[1]);
}

const SNAKE = /^[a-z][a-z0-9]*(_[a-z0-9]+)+$/;

// Throws when the section is absent or fewer than two snake_case tokens were checked, so a rule whose
// section vanished cannot pass vacuously.
function claudeSnakeTokens(text) {
  const section = sectionOf(text, 'claude:');
  if (section === null) throw new Error('no "## claude:" section');
  const tokens = backtickedTokens(section).filter((t) => SNAKE.test(t));
  if (tokens.length < 2) throw new Error(`only ${tokens.length} snake_case token(s) in the claude section`);
  return tokens;
}

function vocabulary(catalog) {
  const v = new Set();
  const addField = (name) => {
    v.add(name);
    for (const seg of name.split('.')) v.add(seg);
  };
  for (const [tool, def] of Object.entries(catalog.tools)) {
    v.add(tool);
    for (const [entry, e] of Object.entries(def.entries)) {
      v.add(entry);
      for (const [field, f] of Object.entries(e.fields ?? {})) {
        addField(field);
        const m = /^string: (.+)$/.exec(f.type ?? '');
        if (m) for (const val of m[1].split(' | ')) v.add(val.trim());
      }
      for (const code of e.errors ?? []) v.add(code);
    }
  }
  for (const code of Object.keys(catalog.errors)) v.add(code);
  return v;
}

const VOCAB = vocabulary(CATALOG);
const missingTicked = (text, tokens) => tokens.filter((t) => !text.includes(tick(t)));
const missingPlain = (text, tokens) => tokens.filter((t) => !text.includes(t));

// ---- S1 / S14: no retired 3.x name, no actor ------------------------------------------------------

test('S1: no retired 3.x tool name (complete_tree aside), invalid_actor, rejected_by_policy or actor in agents/*.md', () => {
  const files = allAgentFiles();
  for (const name of SEAT_AGENTS) assert.ok(files.includes(`${name}.md`), `agents/${name}.md must exist`);
  for (const f of files) {
    const hits = retiredHits(readText(join(PLUGIN, 'agents', f)));
    assert.deepEqual(hits, [], `agents/${f}`);
  }
});

test('S1: no retired 3.x tool name, invalid_actor, rejected_by_policy or actor in bundled-rules/protocol.*.md', () => {
  for (const key of PROTOCOL_KEYS) {
    const hits = retiredHits(readText(rulePath(key)));
    assert.deepEqual(hits, [], `bundled-rules/${key}.md`);
  }
});

test('S14: the retired-name detector is case-insensitive and sees mcp__server__name qualified forms', () => {
  assert.ok(retiredHits('call Advance_Item now').length >= 1);
  assert.ok(retiredHits('MANAGE_NOTES').length >= 1);
  assert.ok(retiredHits('mcp__task-orchestrator__advance_item').length >= 1);
  assert.ok(retiredHits('mcp__mcp-task-orchestrator__query_rules(get)').length >= 1);
  assert.ok(retiredHits('the Actors field').length >= 1);
  assert.ok(retiredHits('actor_authentication').length >= 1);
  assert.ok(retiredHits('Invalid Actor').length >= 1);
  assert.ok(retiredHits('rejected_by_policy').length >= 1);
  // every retired name is covered, not just a sample
  for (const name of BARE_RETIRED) assert.ok(retiredHits(`use ${name} here`).length >= 1, name);
});

test('S14: the detector leaves alone complete_tree, 4.0 names and words that merely contain "actor"', () => {
  assert.deepEqual(retiredHits('items_write complete_tree'), []);
  assert.deepEqual(retiredHits('items_read context, notes_write upsert, not_claim_holder, claim_held'), []);
  assert.deepEqual(retiredHits('a refactor, the extractor, a factor'), []);
});

// ---- S2: the contract-names baseline -------------------------------------------------------------

test('S2: no pendingPort row owned by c6740063 or pointing at agents/ or bundled-rules/', () => {
  assert.ok(Array.isArray(BASELINE.pendingPort), 'pendingPort must stay an array');
  const bad = BASELINE.pendingPort.filter(
    (r) => r.owner === 'c6740063' || r.file.startsWith('agents/') || r.file.startsWith('bundled-rules/'),
  );
  assert.deepEqual(bad.map((r) => `${r.file} (${r.owner})`), []);
});

test('S2: proseExceptions holds nothing for agents/ or bundled-rules/ files', () => {
  assert.ok(Array.isArray(BASELINE.proseExceptions), 'proseExceptions must stay an array');
  const bad = BASELINE.proseExceptions.filter((r) => r.file.startsWith('agents/') || r.file.startsWith('bundled-rules/'));
  assert.deepEqual(bad.map((r) => r.file), []);
});

// ---- S3: seatToken and unauthenticated in every seat agent -----------------------------------------

for (const name of SEAT_AGENTS) {
  test(`S3: agents/${name}.md states the seat-token rule (seatToken, unauthenticated)`, () => {
    const text = readText(agentPath(name));
    assert.deepEqual(missingPlain(text, ['seatToken', 'unauthenticated']), []);
  });
}

// ---- S4: to-call blocks validate against the catalog ------------------------------------------------

function toCallErrors(file) {
  const errors = [];
  for (const b of parseToCallBlocks(readText(file))) {
    if ('error' in b) {
      errors.push(`line ${b.line}: ${b.error}`);
      continue;
    }
    for (const e of checkToCall(b.value, CATALOG)) errors.push(`line ${b.line}: ${e}`);
  }
  return errors;
}

test('S4: every to-call block in agents/*.md parses and passes checkToCall against the catalog', () => {
  for (const f of allAgentFiles()) assert.deepEqual(toCallErrors(join(PLUGIN, 'agents', f)), [], `agents/${f}`);
});

test('S4: every to-call block in bundled-rules/protocol.*.md parses and passes checkToCall', () => {
  for (const key of PROTOCOL_KEYS) assert.deepEqual(toCallErrors(rulePath(key)), [], `bundled-rules/${key}.md`);
});

test('S4: the validator wiring rejects an unknown tool and a missing required field (so an empty error list means something)', () => {
  assert.ok(checkToCall({ tool: 'advance_item', args: {} }, CATALOG).length >= 1);
  assert.ok(checkToCall({ tool: 'advance', op: 'transition', args: { itemId: 'abcd1234' } }, CATALOG).length >= 1);
  assert.deepEqual(
    checkToCall({ tool: 'advance', op: 'transition', args: { itemId: 'abcd1234', trigger: 'start' } }, CATALOG),
    [],
  );
});

// ---- S5: entry-seat names every advance.transition error code ----------------------------------------

test('S5: the entry-seat claude section names every error code of advance.transition (read from the catalog)', () => {
  const codes = CATALOG.tools.advance.entries.transition.errors;
  assert.ok(codes.length >= 1, 'the catalog lists transition error codes');
  const section = sectionOf(readText(rulePath('protocol.entry-seat')), 'claude:');
  assert.notEqual(section, null, 'protocol.entry-seat has a "## claude:" section');
  assert.deepEqual(missingTicked(section, codes), []);
});

// ---- S6: every snake_case token in the claude sections is a catalog name -----------------------------

for (const key of PROTOCOL_KEYS) {
  test(`S6: every backticked snake_case token in the ${key} claude section is a catalog tool, entry, field, error or enum value`, () => {
    const tokens = claudeSnakeTokens(readText(rulePath(key)));
    const unknown = [...new Set(tokens)].filter((t) => !VOCAB.has(t));
    assert.deepEqual(unknown, []);
  });
}

test('S6: the vocabulary is built from the catalog (spot checks keep the builder honest)', () => {
  for (const t of ['items_read', 'notes_write', 'runs_write', 'raise', 'partial_failure', 'note_owned_by_other', 'run_paused', 'awaiting_decision', 'dedupeKey', 'select']) {
    assert.ok(VOCAB.has(t), t);
  }
  for (const t of ['advance_item', 'invalid_actor', 'rejected_by_policy', 'seat_token']) assert.ok(!VOCAB.has(t), t);
});

// ---- S7: entry-seat contract -------------------------------------------------------------------------

test('S7: the entry-seat claude section names the 4.0 advance, context-read, notes and envelope vocabulary', () => {
  const section = sectionOf(readText(rulePath('protocol.entry-seat')), 'claude:');
  assert.notEqual(section, null);
  const names = ['advance', 'transition', 'start', 'items_read', 'context', 'notes_write', 'upsert', 'partial_failure', 'paused', 'note_owned_by_other'];
  assert.deepEqual(missingTicked(section, names), []);
  assert.ok(section.includes('data.results'), 'the per-element outcome is read from data.results');
});

// ---- S8: in-phase-seat and read-only-agent -----------------------------------------------------------

function toolsInBlocks(file) {
  return parseToCallBlocks(readText(file))
    .filter((b) => !('error' in b))
    .map((b) => b.value?.tool);
}

test('S8: in-phase-seat claude section names items_read, notes_write, runs_write and raise; no block calls advance', () => {
  const file = rulePath('protocol.in-phase-seat');
  const section = sectionOf(readText(file), 'claude:');
  assert.notEqual(section, null);
  assert.deepEqual(missingTicked(section, ['items_read', 'notes_write', 'runs_write', 'raise']), []);
  assert.ok(!toolsInBlocks(file).includes('advance'), 'a non-entry seat never calls advance');
});

test('S8: read-only-agent claude section names items_read and notes_write; no block calls advance or runs_write', () => {
  const file = rulePath('protocol.read-only-agent');
  const section = sectionOf(readText(file), 'claude:');
  assert.notEqual(section, null);
  assert.deepEqual(missingTicked(section, ['items_read', 'notes_write']), []);
  const tools = toolsInBlocks(file);
  assert.ok(!tools.includes('advance'), 'a read-only agent never transitions');
  assert.ok(!tools.includes('runs_write'), 'a read-only agent never raises');
});

// ---- S9: implementer deviation raise ------------------------------------------------------------------

test('S9: implementer.md keeps "## Deviations requested" and carries the runs_write raise with its required fields', () => {
  const text = readText(agentPath('implementer'));
  const raiseFields = Object.entries(CATALOG.tools.runs_write.entries.raise.fields)
    .filter(([, f]) => f.required)
    .map(([name]) => name);
  assert.ok(raiseFields.length >= 4, 'the catalog lists the required raise fields');
  const wordRe = (w) => new RegExp(`(?<![A-Za-z0-9_])${w}(?![A-Za-z0-9_])`);
  const missingWords = ['raise', 'deviation', 'block', ...raiseFields].filter((w) => !wordRe(w).test(text));
  assert.deepEqual(missingWords, []);
  assert.deepEqual(missingPlain(text, ['## Deviations requested', 'runs_write', 'awaiting_decision', 'decisionId']), []);
});

// ---- S10 / S11: carry-in contract ----------------------------------------------------------------------

test('S10: planner.md carries the carry-in disposition contract', () => {
  const text = readText(agentPath('planner'));
  const need = [
    'carry-in-dispositions',
    'CARRY-INS',
    'notes_read',
    'keys',
    'in scope (AC n)',
    'deferred to <item-id>',
    'rejected because <reason>',
    'N of N',
    '## Carry-in dispositions',
  ];
  assert.deepEqual(missingPlain(text, need), []);
});

test('S11: reviewer.md treats a bad carry-in disposition record as blocking and keeps note ownership', () => {
  const text = readText(agentPath('reviewer'));
  assert.deepEqual(
    missingPlain(text, ['carry-in-dispositions', 'orchestrator-carry-in-dispositions', 'review-checklist', 'test-manifest']),
    [],
  );
  assert.ok(/blocking/i.test(text), 'a missing, duplicated or count-mismatched row is blocking');
});

// ---- S12: manifest history is append-only ---------------------------------------------------------------

const BASE_FIRST = {
  'protocol.entry-seat': 'f5626a939845ac50bef07996906388c94e58799ef9035e45b972f76c68f90be1',
  'protocol.in-phase-seat': '4ede53bbb5fd53c9bd4bb594359e3de8718542e7b7b28bf1453baf81156d43c0',
  'protocol.read-only-agent': '55e5f899a6a6875c7999cc4de627e209c9ef71664d08ac5af17647ff844f0c44',
};

for (const key of PROTOCOL_KEYS) {
  test(`S12: manifest ${key} keeps its base first hash and appends a different, newer hash`, () => {
    const hashes = MANIFEST[key];
    assert.ok(Array.isArray(hashes), 'manifest entry is an array');
    assert.equal(hashes[0], BASE_FIRST[key]);
    assert.ok(hashes.length >= 2, 'at least one hash appended');
    assert.notEqual(hashes[hashes.length - 1], hashes[0]);
  });
}

test('S12: commit-discipline and review-scoping hash histories are unchanged', () => {
  assert.deepEqual(MANIFEST['commit-discipline'], ['7f0235ee3031df777d915245d5bb72c2ee73c2d61665b64a82b9f808183566cd']);
  assert.deepEqual(MANIFEST['review-scoping'], ['3374170b94cd15cef7a931063f22c502e415a5b3c15045e3cbfacc0b3bb03a6f']);
});

// ---- S13: the section checks cannot pass vacuously -------------------------------------------------------

test('S13: a rule with no claude section, or fewer than two snake_case tokens in it, fails the S6 extractor', () => {
  assert.throws(() => claudeSnakeTokens('# rule' + NL + NL + 'body only' + NL), /no "## claude:" section/);
  assert.throws(() => claudeSnakeTokens('# rule' + NL + NL + '## claude:' + NL + NL), /only 0/);
  assert.throws(() => claudeSnakeTokens('## claude:' + NL + NL + 'use ' + tick('items_read') + ' only' + NL), /only 1/);
  assert.throws(
    () => claudeSnakeTokens('## claude:' + NL + tick('items_read') + NL + '## other' + NL + tick('notes_write') + NL),
    /only 1/,
  );
  assert.deepEqual(
    claudeSnakeTokens('## claude:' + NL + NL + tick('items_read') + ' ' + tick('notes_write') + NL),
    ['items_read', 'notes_write'],
  );
});

test('S13: section extraction separates absent from empty, stops at the next "## ", and tolerates CRLF', () => {
  assert.equal(sectionOf('# t' + NL, 'claude:'), null);
  assert.equal(sectionOf('## claude:' + NL, 'claude:'), '');
  assert.equal(sectionOf(['## claude:', 'A', '## next', 'B', ''].join(NL), 'claude:'), 'A');
  assert.equal(sectionOf(['## claude:', 'A', '## next', 'B', ''].join(CRLF), 'claude:'), 'A');
  // a token inside a fenced block is not a prose token
  const fenced = ['x ' + tick('items_read'), TICK.repeat(3) + 'to-call', tick('hidden_name'), TICK.repeat(3), ''].join(NL);
  assert.deepEqual(backtickedTokens(fenced), ['items_read']);
});

test('S13: an unknown snake_case token would be caught by the S6 vocabulary', () => {
  const tokens = claudeSnakeTokens('## claude:' + NL + NL + tick('items_read') + ' ' + tick('advance_item') + NL);
  assert.deepEqual(tokens.filter((t) => !VOCAB.has(t)), ['advance_item']);
});