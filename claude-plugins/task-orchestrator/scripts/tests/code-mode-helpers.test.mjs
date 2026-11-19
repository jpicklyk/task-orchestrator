// Unit tests for scripts/lib/code-mode-helpers.mjs (item 1bacfc61).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import * as helpers from '../lib/code-mode-helpers.mjs';
import { TOOLS } from '../../hooks/lib/tool-names.mjs';
import { orchestratorToolMatcher } from '../../hooks/registration.mjs';

const { toolKey, parseErrorTail, refusal, tokenLine } = helpers;
const P = 'Permission denied for';

class McpToolError extends Error {
  constructor(toolName, error) {
    super(error);
    this.toolName = toolName;
    this.error = error;
  }
}

// ---- fixtures ----
const notFound = { ok: false, error: { code: 'not_found', message: 'no item', detail: { kind: 'item' }, fix: 'check id' } };
const partial = {
  ok: false,
  error: { code: 'partial_failure', message: 'one failed' },
  data: { results: [{ index: 0, ok: true }, { index: 1, ok: false, error: { code: 'gate_blocked', message: 'x' } }], summary: { ok: 1, failed: 1 } },
};
const atomic = { ok: false, error: { code: 'invalid_request', message: 'bad', index: 0 } };
const unavail = { ok: false, error: { code: 'unavailable', message: 'shedding', retryAfterMs: 500 } };
const textOf = (env, msg) => `${env.error.code}: ${msg}\n${JSON.stringify(env)}`;
const envCases = [
  [notFound, textOf(notFound, 'no item')],
  [partial, textOf(partial, 'one failed')],
  [atomic, textOf(atomic, 'bad')],
  [unavail, textOf(unavail, 'shedding')],
  [notFound, textOf(notFound, 'line one\nline two')],
];
const okEnv = { ok: true, data: {} };

const badTools = [undefined, null, 5, '', 'Items_Read', '1abc', 'a-b'];
const toolKeyFixtures = [
  ['items_read'], ['advance', 'srv'], ['advance', 'a__b'], ['advance', ''], ['advance', null],
  ['advance', 'bad server'], ...badTools.map((t) => [t]),
];

const parseRejects = [
  'ok', JSON.stringify(okEnv), `e: x\n${JSON.stringify(okEnv)}`, '{"ok":false}', 'e\n[1,2]', 'x\n', 'e\nnot json',
  undefined, null, 42, {}, { get message() { throw new Error('boom'); } },
];
const parseFixtures = [
  ...envCases.flatMap(([, t]) => [t, { message: t }, { toolName: 'x', error: t }, new McpToolError('x', t), new Error(t)]),
  ...parseRejects,
];

const longLine = `${P} Bash: ${'z'.repeat(500)}`;
const refusalAccept = [
  [new McpToolError('Bash', `${P} Bash: rm -rf`), 'Bash', `${P} Bash: rm -rf`],
  [new Error(`${P} file(): path blocked\nmore`), 'file()', `${P} file(): path blocked`],
  [{ error: `${P} Write: outside root` }, 'Write', `${P} Write: outside root`],
  [{ exitCode: 1, stderr: `noise\n${P} Bash: nope\ntail` }, 'Bash', `${P} Bash: nope`],
  [{ code: 2, stderr: `${P} Edit: x` }, 'Edit', `${P} Edit: x`],
  [{ error: longLine }, 'Bash', longLine.slice(0, 300)],
];
const refusalReject = [
  JSON.stringify({ ok: true, data: { note: `${P} Bash` } }),
  `${P} Bash: bare string`,
  { exitCode: 0, stderr: `${P} Bash: x` },
  { exitCode: 1, stderr: 'plain failure' },
  new McpToolError('items_read', `forbidden: nope\n${JSON.stringify(notFound)}`),
  notFound, undefined, null, 7, [],
];
const tokenFixtures = ['  abc \n', '', '   ', 'a\nb', 'a\rb', undefined, null, 5, 'tok-123'];

// ---- tests ----
test('T1 toolKey maps the catalog and matches the guard matcher', () => {
  const re = new RegExp(orchestratorToolMatcher(TOOLS));
  for (const name of TOOLS) {
    const key = toolKey(name);
    assert.equal(key, `mcp__mcp-task-orchestrator__${name}`);
    assert.match(key, re);
  }
  assert.equal(toolKey('advance', 'srv.x-1'), 'mcp__srv.x-1__advance');
  for (const bad of badTools) assert.equal(toolKey(bad), null);
  assert.equal(toolKey('advance', 'a__b'), null);
  assert.equal(toolKey('advance', ''), null);
  assert.equal(toolKey('advance', null), null);
});

test('T2 parseErrorTail accepts the real error shapes', () => {
  for (const [env, text] of envCases) {
    for (const form of [text, { message: text }, { toolName: 'x', error: text }, new McpToolError('x', text), new Error(text)]) {
      assert.deepEqual(parseErrorTail(form), env);
    }
  }
});

test('T3 parseErrorTail rejects non-envelopes', () => {
  for (const r of parseRejects) assert.equal(parseErrorTail(r), null);
});

test('T4 refusal accepts the four shapes and rejects the rest', () => {
  for (const [v, tool, reason] of refusalAccept) {
    assert.deepEqual(refusal(v), { refused: true, tool, reason });
  }
  assert.equal(refusal({ error: longLine }).reason.length, 300);
  for (const r of refusalReject) assert.equal(refusal(r), null);
});

test('T5 tokenLine formats and never echoes the input', () => {
  assert.equal(tokenLine('  abc \n'), 'abc\n');
  const secret = 'Zq9!xK3vR7-secret-high-entropy';
  for (const bad of ['', '   ', `${secret}\n${secret}`, `${secret}\r${secret}`, undefined, null, 5]) {
    assert.throws(() => tokenLine(bad), (e) => e instanceof TypeError && !e.message.includes(secret));
  }
});

test('T6 self-containment', () => {
  const src = readFileSync(fileURLToPath(new URL('../lib/code-mode-helpers.mjs', import.meta.url)), 'utf8');
  assert.doesNotMatch(src, /^\s*import\b/m);
  assert.doesNotMatch(src.replace(/^\s*\/\/.*$/gm, ''), /instanceof/);
  assert.deepEqual(Object.keys(helpers).sort(), ['parseErrorTail', 'refusal', 'tokenLine', 'toolKey']);
  const run = (fn, args) => {
    try { return { v: JSON.stringify(fn(...args)) }; } catch (e) { return { n: e.name, m: e.message }; }
  };
  const fixtures = {
    toolKey: toolKeyFixtures,
    parseErrorTail: parseFixtures.map((f) => [f]),
    refusal: [...refusalAccept.map((a) => a[0]), ...refusalReject].map((f) => [f]),
    tokenLine: tokenFixtures.map((f) => [f]),
  };
  for (const [name, fn] of Object.entries(helpers)) {
    const isolated = vm.runInContext('(' + fn.toString() + ')', vm.createContext({}));
    for (const args of fixtures[name]) {
      assert.deepEqual(run(isolated, args), run(fn, args), `${name} diverges in a fresh realm`);
    }
  }
});

test('T7 no-throw sweep', () => {
  for (const fn of [toolKey, parseErrorTail, refusal]) {
    for (const v of [undefined, null, 0, NaN, '', {}, []]) assert.doesNotThrow(() => fn(v));
  }
});
