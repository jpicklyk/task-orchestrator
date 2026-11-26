// Item 0d6264e3: a decision carries its evidence. Decision 0002 has the director decide from the decision request
// alone, so every raise the plugin shows or prompts fills `context` and `links`, and the rules that bind raisers say so.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url));
const SKIP_DIRS = new Set(['tests', '_generated', 'node_modules', 'fixtures']);

function markdownFiles(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      if (!SKIP_DIRS.has(name)) markdownFiles(p, out);
    } else if (name.endsWith('.md')) {
      out.push(p);
    }
  }
  return out;
}

/** Every ```to-call block in the plugin's markdown whose call is runs_write raise, with its file. */
function raiseBlocks() {
  const blocks = [];
  for (const file of markdownFiles(PLUGIN)) {
    const text = readFileSync(file, 'utf8');
    for (const m of text.matchAll(/```to-call\r?\n([\s\S]*?)\r?\n```/g)) {
      let call;
      try {
        call = JSON.parse(m[1]);
      } catch {
        continue; // contract-doc-shape owns block validity
      }
      if (call.tool === 'runs_write' && call.op === 'raise') blocks.push({ file: relative(PLUGIN, file), call });
    }
  }
  return blocks;
}

test('E1: the plugin shows at least the two known raise examples', () => {
  const files = raiseBlocks().map((b) => b.file.replace(/\\/g, '/'));
  for (const f of ['agents/implementer.md', 'skills/status-progression/references/decisions.md']) {
    assert.ok(files.includes(f), `a runs_write raise to-call block in ${f}`);
  }
});

test('E2: every runs_write raise example fills context and at least one link', () => {
  for (const { file, call } of raiseBlocks()) {
    const args = call.args ?? {};
    assert.equal(typeof args.context, 'string', `${file}: context is a string`);
    assert.ok(args.context.trim().length > 0, `${file}: context is not empty`);
    assert.ok(args.context.length <= 1000, `${file}: context fits the server's 1000-character limit`);
    assert.ok(Array.isArray(args.links) && args.links.length > 0, `${file}: links is a non-empty array`);
    for (const l of args.links) {
      assert.ok(['item', 'note', 'doc', 'commit', 'url'].includes(l.kind), `${file}: link kind ${l.kind}`);
      assert.equal(typeof l.ref, 'string', `${file}: link ref is a string`);
    }
  }
});

test('E3: the implement-wave seat prompt for a raise names context and links', () => {
  const lines = readFileSync(join(PLUGIN, 'workflows', 'implement-wave.js'), 'utf8').split(/\r?\n/);
  const starts = lines.map((l, i) => (l.includes('op:"raise"') ? i : -1)).filter((i) => i >= 0);
  assert.ok(starts.length >= 1, 'implement-wave.js has a raise prompt');
  for (const i of starts) {
    // The template is one string expression joined with `+` across its following lines; read until the line that ends it.
    let end = i;
    while (end < lines.length - 1 && lines[end].trimEnd().endsWith('+')) end++;
    const template = lines.slice(i, end + 1).join(' ');
    // Check the call shape itself, runs_write(ops:[{ ... }]), not the prose that follows it.
    const shape = template.match(/runs_write\(ops:\[\{([\s\S]*?)\}\]\)/);
    assert.ok(shape, 'the raise prompt spells the call as runs_write(ops:[{...}])');
    assert.ok(/(^|[\s,])context,/.test(shape[1]), 'the raise call shape names context');
    assert.ok(shape[1].includes('links:['), 'the raise call shape names links');
  }
});

test('E5: the run-wave Decisions paragraph lists context and links in the raise shape', () => {
  const doc = readFileSync(join(PLUGIN, 'skills', 'run-wave', 'SKILL.md'), 'utf8').replace(/\s+/g, ' ');
  const m = doc.match(/raises `runs_write` `raise \{([^}]*)\}`/);
  assert.ok(m, 'the Decisions paragraph spells the raise shape');
  assert.ok(/\bcontext\b/.test(m[1]) && /\blinks\b/.test(m[1]), 'the raise shape names context and links');
});

test('E4: the rules that bind raisers require the evidence', () => {
  const rule = (key) => readFileSync(join(PLUGIN, 'bundled-rules', `${key}.md`), 'utf8').replace(/\s+/g, ' ');
  for (const key of ['protocol.entry-seat', 'protocol.in-phase-seat']) {
    const r = rule(key);
    assert.ok(r.includes('**A raise carries its evidence.**'), `${key} has the evidence bullet`);
    assert.ok(r.includes('`context`') && r.includes('`links`') && r.includes('1000 characters'), `${key} names context, links and the limit`);
  }
  const od = rule('orchestration-discipline');
  assert.ok(od.includes('**A decision you raise carries its evidence.**'), 'orchestration-discipline has the point');
  assert.ok(od.includes('`context`') && od.includes('`links`'), 'orchestration-discipline names context and links');
});
