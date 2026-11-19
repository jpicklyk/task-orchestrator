import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as helpers from '../lib/code-mode-helpers.mjs';
import { renderPrelude, replaceRegion, DOC_FILE, HELPERS_FILE, REGION_BEGIN, REGION_END } from '../gen-code-mode-prelude.mjs';

const PLUGIN_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const GEN = join(PLUGIN_ROOT, 'scripts', 'gen-code-mode-prelude.mjs');
const doc = () => readFileSync(join(PLUGIN_ROOT, DOC_FILE), 'utf8');
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

function jsBlocks(text) {
  const out = [];
  const re = /```js\r?\n([\s\S]*?)```/g;
  let m;
  while ((m = re.exec(text)) !== null) out.push(m[1]);
  return out;
}

function regionOf(text) {
  const s = text.indexOf('// @generated:code-mode-prelude-begin');
  const e = text.indexOf(REGION_END, s) + REGION_END.length;
  return text.slice(s, e);
}

test('the generator --check exits 0 on the repo', () => {
  const r = spawnSync(process.execPath, [GEN, '--check'], { encoding: 'utf8' });
  assert.equal(r.status, 0, r.stderr);
});

test('the region in code-mode.md equals the rendered helper source', () => {
  const src = readFileSync(join(PLUGIN_ROOT, HELPERS_FILE), 'utf8');
  assert.equal(regionOf(doc()), renderPrelude(src));
});

test('a changed character in the region makes --check exit 1 and name the file', () => {
  const dir = mkdtempSync(join(tmpdir(), 'cm-prelude-'));
  for (const rel of [HELPERS_FILE, DOC_FILE]) {
    mkdirSync(dirname(join(dir, rel)), { recursive: true });
    cpSync(join(PLUGIN_ROOT, rel), join(dir, rel));
  }
  const p = join(dir, DOC_FILE);
  const text = readFileSync(p, 'utf8');
  const at = text.indexOf('function toolKey');
  writeFileSync(p, text.slice(0, at) + 'function toolKez' + text.slice(at + 'function toolKey'.length));
  const r = spawnSync(process.execPath, [GEN, '--check', '--plugin-root', dir], { encoding: 'utf8' });
  assert.equal(r.status, 1);
  assert.ok(r.stderr.includes('code-mode.md'), r.stderr);
});

test('replaceRegion throws when a marker is missing', () => {
  assert.throws(() => replaceRegion('no markers', 'x'));
  assert.throws(() => replaceRegion(`${REGION_BEGIN}\nbody`, 'x'));
});

test('the prelude has no export or import and its functions match the module', () => {
  const prelude = regionOf(doc());
  assert.ok(!/\bexport\s/.test(prelude), 'export left in the prelude');
  assert.ok(!/\bimport\s/.test(prelude), 'import in the prelude');
  const f = new Function(prelude + '\n; return {toolKey, parseErrorTail, refusal, tokenLine}')();
  assert.equal(f.toolKey('items_read'), helpers.toolKey('items_read'));
  const err = new Error('boom\n{"ok":false,"error":{"code":"unavailable","retryAfterMs":5}}');
  assert.deepEqual(f.parseErrorTail(err), helpers.parseErrorTail(err));
  const denied = { error: 'Permission denied for Read: no' };
  assert.deepEqual(f.refusal(denied), helpers.refusal(denied));
  assert.equal(f.tokenLine(' abc '), helpers.tokenLine(' abc '));
});

test('every js block in code-mode.md parses as an async function body', () => {
  const blocks = jsBlocks(doc());
  assert.ok(blocks.length >= 9, `only ${blocks.length} js blocks`);
  blocks.forEach((b, i) => {
    assert.doesNotThrow(() => new AsyncFunction(b), `js block ${i + 1} does not parse`);
  });
});

test('code-mode.md has a section per recipe and names the rules; SKILL.md routes to it', () => {
  const text = doc();
  for (const n of [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]) assert.ok(new RegExp(`^## R${n}: `, 'm').test(text), `R${n} section missing`);
  for (const needle of ['stage-result', 'atomic: true', 'parseErrorTail', 'refusal', 'call-shapes-js.md']) {
    assert.ok(text.includes(needle), `missing ${needle}`);
  }
  assert.ok(!text.includes('```to-call'));
  assert.ok(!/model: '(sonnet|opus|haiku)'/.test(text) && !/effort: '(low|medium|high)'/.test(text));
  assert.ok(!/[^\x00-\x7f]/.test(text), 'non-ASCII character');
  const skill = readFileSync(join(PLUGIN_ROOT, 'skills', 'run-wave', 'SKILL.md'), 'utf8');
  assert.ok(skill.includes('references/code-mode.md') && skill.includes('Code Mode'));
});
