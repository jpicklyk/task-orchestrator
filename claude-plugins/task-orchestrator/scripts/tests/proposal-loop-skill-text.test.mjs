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
const PROPOSALS = fs.readFileSync(path.join(PLUGIN, 'skills', 'review-proposals', 'SKILL.md'), 'utf8').replace(/\r\n/g, '\n');

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
