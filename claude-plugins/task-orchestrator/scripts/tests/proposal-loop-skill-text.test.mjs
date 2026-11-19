// Structural text tests for the improvement-proposal loop skills (item 0effb9ab, scenarios S11-S14 of its
// test-plan). BLIND author: the skill files are read here at test time only; the expected structure comes
// from the frozen diagnosis (D1 proposal note at 7b, D2 review-proposals ensures the note before any
// accept and lists an awaiting-verification bucket, D3 a "Verify Adopted Proposals" subsection before 8b,
// D5/D6 retro-ack CLI and repeated --run) and the improvement-proposal schema floor
// (deploy/global-config/.taskorchestrator/config.yaml: proposal in queue, outcome-verification in review).
// The call shapes are the documented ```to-call blocks ({tool, select|op, args}), parsed with the plugin's
// own parseToCallBlocks.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { parseToCallBlocks } from '../lib/contract-checks.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PLUGIN = path.resolve(HERE, '..', '..');
const RETRO = fs.readFileSync(path.join(PLUGIN, 'skills', 'session-retrospective', 'SKILL.md'), 'utf8').replace(/\r\n/g, '\n');
const readLf = (...p) => fs.readFileSync(path.join(PLUGIN, ...p), 'utf8').replace(/\r\n/g, '\n');
const PROPOSALS_SKILL = readLf('skills', 'review-proposals', 'SKILL.md');
const PROPOSALS = [
  PROPOSALS_SKILL,
  readLf('skills', 'review-proposals', 'references', 'accept-project.md'),
  readLf('skills', 'review-proposals', 'references', 'accept-global.md'),
].join('\n');

/** Markdown headings outside fenced code: [{ idx, level, text }] (idx is the 0-based line index). */
function headings(md) {
  const out = [];
  let fenced = false;
  md.split('\n').forEach((line, idx) => {
    if (/^\s*(```|~~~)/.test(line)) fenced = !fenced;
    if (fenced) return;
    const m = /^(#{1,6})\s+(.*)$/.exec(line);
    if (m) out.push({ idx, level: m[1].length, text: m[2] });
  });
  return out;
}

/** The text from the heading matched by `startRe` up to the first later heading matched by `endRe`. */
function between(md, startRe, endRe) {
  const hs = headings(md);
  const start = hs.find((h) => startRe.test(h.text));
  assert.ok(start, `heading ${startRe} not found`);
  const end = hs.find((h) => h.idx > start.idx && endRe.test(h.text));
  assert.ok(end, `heading ${endRe} not found after ${startRe}`);
  return { start, end, text: md.split('\n').slice(start.idx, end.idx).join('\n') };
}

/** The text of one section: its heading to the next heading of the same or a higher level. */
function section(md, startRe) {
  const hs = headings(md);
  const start = hs.find((h) => startRe.test(h.text));
  assert.ok(start, `heading ${startRe} not found`);
  const end = hs.find((h) => h.idx > start.idx && h.level <= start.level);
  const lines = md.split('\n');
  return lines.slice(start.idx, end ? end.idx : lines.length).join('\n');
}

function blocksOf(text) {
  return parseToCallBlocks(text).map((b) => {
    assert.ok(!b.error, `to-call block at line ${b.line}: ${b.error}`);
    return b.value;
  });
}

/** True when some object anywhere inside `value` has `key` equal to `val`. */
function deepHas(value, key, val) {
  if (value === null || typeof value !== 'object') return false;
  if (!Array.isArray(value) && value[key] === val) return true;
  return Object.values(value).some((v) => deepHas(v, key, val));
}

const isUpsert = (b, key, role) =>
  b.tool === 'notes_write' && deepHas(b, 'op', 'upsert') && deepHas(b, 'key', key) && deepHas(b, 'role', role);

// ---- S11: session-retrospective 7b writes the proposal note -------------------------------------

test('S11: between 7b and 7c there is a notes_write upsert to-call with key proposal, role queue', () => {
  const { text } = between(RETRO, /^(Step\s+)?7b\b/i, /^(Step\s+)?7c\b/i);
  const blocks = blocksOf(text);
  assert.ok(blocks.some((b) => isUpsert(b, 'proposal', 'queue')), `blocks: ${JSON.stringify(blocks)}`);
});

// ---- S12: Step 8 verifies adopted proposals ------------------------------------------------------

test('S12: Step 8 has a "Verify Adopted Proposals" subsection before Step 8b', () => {
  const step8 = between(RETRO, /^Step 8(?![0-9A-Za-z])/i, /^Step 8b\b/i);
  assert.ok(headings(step8.text).some((h) => /Verify Adopted Proposals/.test(h.text)), 'no such heading between Step 8 and Step 8b');
});

test('S12: the subsection lists improvement-proposal items in role review, upserts outcome-verification in review, and completes', () => {
  const sub = section(RETRO, /Verify Adopted Proposals/);
  const blocks = blocksOf(sub);
  assert.ok(
    blocks.some((b) => b.tool === 'items_read' && deepHas(b, 'select', 'list') && deepHas(b, 'type', 'improvement-proposal') && deepHas(b, 'role', 'review')),
    `no items_read list for improvement-proposal in role review: ${JSON.stringify(blocks)}`,
  );
  assert.ok(blocks.some((b) => isUpsert(b, 'outcome-verification', 'review')), `no outcome-verification upsert: ${JSON.stringify(blocks)}`);
  assert.ok(blocks.some((b) => b.tool === 'advance' && deepHas(b, 'trigger', 'complete')), `no advance complete: ${JSON.stringify(blocks)}`);
});

test('S12: within the subsection the outcome-verification upsert comes before the advance complete', () => {
  const sub = section(RETRO, /Verify Adopted Proposals/);
  const blocks = blocksOf(sub);
  const upsert = blocks.findIndex((b) => isUpsert(b, 'outcome-verification', 'review'));
  const complete = blocks.findIndex((b) => b.tool === 'advance' && deepHas(b, 'trigger', 'complete'));
  assert.ok(upsert >= 0 && complete >= 0);
  assert.ok(upsert < complete, 'the verdict note must be written before the proposal is completed');
});

// ---- S13: review-proposals ensures the proposal note before any config push ----------------------

test('S13: review-proposals upserts the proposal note (queue) before its first config push to-call', () => {
  const blocks = blocksOf(PROPOSALS);
  const upsert = blocks.findIndex((b) => isUpsert(b, 'proposal', 'queue'));
  const push = blocks.findIndex((b) => b.tool === 'config' && deepHas(b, 'op', 'push'));
  assert.ok(upsert >= 0, 'no proposal/queue upsert to-call');
  assert.ok(push >= 0, 'no config push to-call (the control for the ordering check)');
  assert.ok(upsert < push, `proposal upsert (block ${upsert}) must precede the first config push (block ${push})`);
});

test('S13: the partition has an "Awaiting verification" bucket that names role review', () => {
  assert.match(PROPOSALS, /awaiting verification/i);
  assert.match(PROPOSALS, /role\W{0,6}review\b/i);
});

// ---- S14: retro-ack CLI and repeatable --run ------------------------------------------------------

test('S14: Step 8b runs retro-ack.mjs with --run', () => {
  const s = section(RETRO, /^Step 8b\b/i);
  assert.ok(s.includes('retro-ack.mjs'), 'Step 8b does not name retro-ack.mjs');
  assert.ok(s.includes('--run'), 'Step 8b does not pass --run');
});

test('S14: Step 0 says --run repeats, and the argument-hint marks it repeatable', () => {
  const s = section(RETRO, /^Step 0\b/i);
  assert.ok(s.includes('--run'), 'Step 0 does not mention --run');
  assert.match(s, /repeat/i);
  const hint = /^argument-hint:\s*(.*)$/m.exec(RETRO.split('\n---')[0])?.[1] ?? '';
  assert.ok(hint.includes('--run <runId>]...'), `argument-hint: ${hint}`);
  assert.match(hint, /repeatable/i);
});

// ---- S15/S16: user-only review skills (item d85d3905, AC1-AC9) -------------------------------------

function fm(md) {
  const lines = md.split('\n');
  assert.equal(lines[0], '---');
  const end = lines.indexOf('---', 1);
  const out = {};
  for (const l of lines.slice(1, end)) {
    const m = /^([A-Za-z][\w-]*):\s*(.*)$/.exec(l);
    if (m) out[m[1]] = m[2].replace(/^"(.*)"$/, '$1');
  }
  return out;
}

/** Raw targets of markdown links that end in .md. */
const mdLinks = (md) => [...md.matchAll(/\]\(([^)#\s]+\.md)(?:#[^)]*)?\)/g)].map((m) => m[1]);

const RR_DIR = path.join(PLUGIN, 'skills', 'review-retrospectives');
const RP_DIR = path.join(PLUGIN, 'skills', 'review-proposals');

function checkUserSkill(skillMd, dir) {
  const f = fm(skillMd);
  assert.equal(f['disable-model-invocation'], 'true');
  assert.ok(f.description.length <= 1024, 'description <= 1024 chars');
  assert.doesNotMatch(f.description, /\b(I|you|your)\b/i, 'third person');
  assert.doesNotMatch(f.description, /use when (a user|the user) says/i, 'no trigger-phrase list');
  assert.ok(skillMd.split('\n').length <= 300, 'SKILL.md at most 300 lines');
  for (const ref of fs.readdirSync(path.join(dir, 'references'))) {
    const refText = fs.readFileSync(path.join(dir, 'references', ref), 'utf8');
    assert.ok(mdLinks(skillMd).includes('references/' + ref), ref + ' is linked from SKILL.md');
    assert.deepEqual(mdLinks(refText), [], ref + ' links no further .md file');
  }
}

test('S15: review-proposals is user-only, short, links each accept reference and batches Step 4 by up to 4', () => {
  checkUserSkill(PROPOSALS_SKILL, RP_DIR);
  const step4 = section(PROPOSALS_SKILL, /^Step 4\b/);
  assert.match(step4, /up to 4/i);
  assert.match(step4, /Recommended/);
  assert.match(PROPOSALS_SKILL, /^### Before any Accept\b/m, 'S13 ordering and github-feedback cite this heading');
  const nothing = PROPOSALS_SKILL.split('\n').find((l) => l.includes('No pending improvement proposals.'));
  assert.match(nothing, /awaiting-verification/i);
});

test('S16: review-retrospectives is user-only, states the empty path, reads its sources and hands off to review-proposals', () => {
  const skill = readLf('skills', 'review-retrospectives', 'SKILL.md');
  checkUserSkill(skill, RR_DIR);
  const hint = fm(skill)['argument-hint'];
  assert.ok(hint.includes('--audit') && hint.includes('--deep'));
  const empty = 'No retrospective data found: no retrospectives, trends or proposals.';
  assert.ok(skill.includes(empty));
  assert.ok(skill.indexOf(empty) < skill.indexOf('print one dashboard'), 'empty path precedes the dashboard');
  const step1 = section(skill, /^Step 1\b/);
  for (const needle of ['session-retrospective', 'workflow-evaluation', 'session-metrics', 'retrospective-trend', '"bug"', 'improvement-proposal', 'fix-cycle-cost']) {
    assert.ok(step1.includes(needle), 'Step 1 reads ' + needle);
  }
  const step2 = section(skill, /^Step 2\b/);
  for (const g of ['product', 'global-process', 'project']) assert.ok(step2.includes('**' + g + '**'), g);
  assert.match(section(skill, /^Step 4\b/), /review-proposals/);
  assert.doesNotMatch(skill, /4\.[234]|W5/);
});

test('S16: every Step n named in review-retrospectives deep-mode.md is a heading of its SKILL.md', () => {
  const skill = readLf('skills', 'review-retrospectives', 'SKILL.md');
  const deep = readLf('skills', 'review-retrospectives', 'references', 'deep-mode.md');
  assert.doesNotMatch(deep, /4\.[234]|W5/);
  const have = new Set(headings(skill).map((h) => /^Step (\d+)\b/.exec(h.text)?.[1]).filter(Boolean));
  const named = [...deep.matchAll(/\bStep (\d+)\b/g)].map((m) => m[1]);
  assert.ok(named.length > 0);
  for (const x of named) assert.ok(have.has(x), 'Step ' + x + ' has a heading');
});
