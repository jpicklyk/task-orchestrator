// Contract tests for hooks/lib/envelope.mjs (fc131c71, scenarios S7-S11). Oracle: the frozen task-scope
// signatures; the 4.0 response envelope in current/docs/api-reference.md ("Response envelope",
// :2397-2431: ok / data / error{code,kind,message,detail,fix,retryAfterMs,index} / meta.reqId, partial
// failure = ok:false + error.code partial_failure + full data); plan S1 (the plugin never infers kind
// from code) and S6 (a top-level `success` key is the 3.x ResponseUtil shape).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isLegacyShape, parseEnvelopeText, parseToolResult } from '../lib/envelope.mjs';
import { extractResponseJson } from '../retro-lib.mjs';

const PARSED_KEYS = ['data', 'error', 'legacy', 'ok', 'reqId', 'results', 'summary'];
const LEGACY_PARSED = { ok: false, legacy: true, data: null, error: null, results: [], summary: null, reqId: null };

function okEnvelope(extra = {}) {
  return {
    ok: true,
    data: {
      results: [{ ok: true, itemId: 'item-1' }, { ok: true, itemId: 'item-2' }],
      summary: { ok: 2, failed: 0 },
    },
    meta: { reqId: 'k7f3q9ab' },
    ...extra,
  };
}

function viaStructured(env) {
  return { structuredContent: env, content: [{ type: 'text', text: env.ok ? 'ok' : `${env.error?.code}: ${env.error?.message}` }], isError: !env.ok };
}

function viaText(env) {
  return { content: [{ type: 'text', text: JSON.stringify(env) }] };
}

// ---- S7 success envelope ----

test('S7: a success envelope via structuredContent yields data/results/summary/reqId and error null', () => {
  const p = parseToolResult(viaStructured(okEnvelope()));
  assert.deepEqual(Object.keys(p).sort(), PARSED_KEYS);
  assert.equal(p.ok, true);
  assert.equal(p.legacy, false);
  assert.deepEqual(p.data, okEnvelope().data);
  assert.deepEqual(p.results, [{ ok: true, itemId: 'item-1' }, { ok: true, itemId: 'item-2' }]);
  assert.deepEqual(p.summary, { ok: 2, failed: 0 });
  assert.equal(p.reqId, 'k7f3q9ab');
  assert.equal(p.error, null);
});

test('S7: the same envelope as a JSON string, as content[0].text, and as a bare object parses identically', () => {
  const expected = parseToolResult(viaStructured(okEnvelope()));
  assert.deepEqual(parseToolResult(JSON.stringify(okEnvelope())), expected);
  assert.deepEqual(parseToolResult(viaText(okEnvelope())), expected);
  assert.deepEqual(parseToolResult(okEnvelope()), expected);
});

test('S7: structuredContent wins over a different JSON document in content[0].text', () => {
  const winner = { ok: true, data: { which: 'structured' }, meta: { reqId: 'r-structured' } };
  const loser = { ok: false, error: { code: 'internal', kind: 'permanent', message: 'text' }, meta: { reqId: 'r-text' } };
  const p = parseToolResult({ structuredContent: winner, content: [{ type: 'text', text: JSON.stringify(loser) }] });
  assert.equal(p.ok, true);
  assert.deepEqual(p.data, { which: 'structured' });
  assert.equal(p.reqId, 'r-structured');
});

test('S7: an envelope with no data and no meta -> data null, results [], summary null, reqId null', () => {
  assert.deepEqual(parseToolResult({ ok: true }), {
    ok: true, legacy: false, data: null, error: null, results: [], summary: null, reqId: null,
  });
});

test('S7 probe: data.results that is not an array -> []; data.summary that is not an object -> null', () => {
  const p = parseToolResult({ ok: true, data: { results: 'nope', summary: 'nope' } });
  assert.deepEqual(p.results, []);
  assert.equal(p.summary, null);
  assert.deepEqual(p.data, { results: 'nope', summary: 'nope' });
  const q = parseToolResult({ ok: true, data: { results: { a: 1 } } });
  assert.deepEqual(q.results, []);
});

test('S7 probe: a data payload without results/summary (a read answer) keeps data and defaults the rest', () => {
  const p = parseToolResult({ ok: true, data: { id: 'x', title: 't' }, meta: { reqId: 'r1' } });
  assert.deepEqual(p.data, { id: 'x', title: 't' });
  assert.deepEqual(p.results, []);
  assert.equal(p.summary, null);
});

// ---- S8 error envelope ----

test('S8: claim_held with kind, retryAfterMs, detail, fix and index is copied field for field', () => {
  const env = {
    ok: false,
    error: {
      code: 'claim_held', kind: 'transient', message: 'claimed by other',
      detail: { claimedBy: 'agent-x' }, fix: 'wait for the claim to expire', retryAfterMs: 1500, index: 2,
    },
    meta: { reqId: 'r8' },
  };
  const p = parseToolResult(viaStructured(env));
  assert.equal(p.ok, false);
  assert.equal(p.legacy, false);
  assert.equal(p.reqId, 'r8');
  assert.deepEqual(p.error, {
    code: 'claim_held', kind: 'transient', message: 'claimed by other',
    retryAfterMs: 1500, detail: { claimedBy: 'agent-x' }, fix: 'wait for the claim to expire', index: 2,
  });
});

test('S8: retryAfterMs present only in error.detail is lifted to error.retryAfterMs', () => {
  const p = parseToolResult({
    ok: false,
    error: { code: 'unavailable', kind: 'shedding', message: 'busy', detail: { retryAfterMs: 1500 } },
  });
  assert.equal(p.error.retryAfterMs, 1500);
  assert.deepEqual(p.error.detail, { retryAfterMs: 1500 });
});

test('S8: error-level retryAfterMs wins over detail.retryAfterMs; an error-level 0 is kept', () => {
  const both = parseToolResult({
    ok: false, error: { code: 'unavailable', kind: 'shedding', message: 'm', retryAfterMs: 1000, detail: { retryAfterMs: 2000 } },
  });
  assert.equal(both.error.retryAfterMs, 1000);
  const zero = parseToolResult({
    ok: false, error: { code: 'unavailable', kind: 'shedding', message: 'm', retryAfterMs: 0, detail: { retryAfterMs: 2000 } },
  });
  assert.equal(zero.error.retryAfterMs, 0);
});

test('S8: no kind on the wire -> kind null even for a code a plugin could be tempted to classify (S1)', () => {
  for (const code of ['claim_held', 'unavailable', 'not_found', 'internal', 'gate_blocked']) {
    const p = parseToolResult({ ok: false, error: { code, message: 'm' } });
    assert.equal(p.error.code, code);
    assert.equal(p.error.kind, null, code);
  }
});

test('S8: a bare error with only a code -> every other error field null', () => {
  const p = parseToolResult({ ok: false, error: { code: 'invalid_request' } });
  assert.deepEqual(p.error, {
    code: 'invalid_request', kind: null, message: null, retryAfterMs: null, detail: null, fix: null, index: null,
  });
});

test('S8 probe: error.index 0 (first element of an atomic batch) is preserved, not nulled', () => {
  const p = parseToolResult({ ok: false, error: { code: 'invalid_request', kind: 'permanent', message: 'm', index: 0 } });
  assert.equal(p.error.index, 0);
});

test('S8: ok:false with no error object still yields an error record with null fields', () => {
  const p = parseToolResult({ ok: false });
  assert.equal(p.ok, false);
  assert.equal(p.legacy, false);
  assert.deepEqual(p.error, {
    code: null, kind: null, message: null, retryAfterMs: null, detail: null, fix: null, index: null,
  });
});

test('S8 probe: an error key on a SUCCESS envelope is ignored -> error null', () => {
  const p = parseToolResult({ ok: true, error: { code: 'internal', kind: 'permanent', message: 'm' } });
  assert.equal(p.ok, true);
  assert.equal(p.error, null);
});

// ---- S9 partial failure ----

test('S9: partial_failure keeps ok:false, the error, and the full results and summary', () => {
  const env = {
    ok: false,
    error: { code: 'partial_failure', kind: 'permanent', message: '1 of 2 failed' },
    data: {
      results: [{ ok: true, itemId: 'a' }, { ok: false, error: { code: 'not_found', kind: 'permanent', message: 'gone' } }],
      summary: { ok: 1, failed: 1 },
    },
    meta: { reqId: 'r9' },
  };
  const p = parseToolResult(viaStructured(env));
  assert.equal(p.ok, false);
  assert.equal(p.error.code, 'partial_failure');
  assert.equal(p.results.length, 2);
  assert.deepEqual(p.results[0], { ok: true, itemId: 'a' });
  assert.equal(p.results[1].ok, false);
  assert.equal(p.results[1].error.code, 'not_found');
  assert.deepEqual(p.summary, { ok: 1, failed: 1 });
  assert.deepEqual(p.data, env.data);
  assert.equal(p.reqId, 'r9');
});

// ---- S10 legacy 3.x shape ----

test('S10: a 3.x {success,message,data} payload is legacy via content text, structuredContent, bare and string', () => {
  const legacy = { success: true, message: 'Item created', data: { id: 'x' } };
  assert.deepEqual(parseToolResult(viaText(legacy)), LEGACY_PARSED);
  assert.deepEqual(parseToolResult({ structuredContent: legacy }), LEGACY_PARSED);
  assert.deepEqual(parseToolResult(legacy), LEGACY_PARSED);
  assert.deepEqual(parseToolResult(JSON.stringify(legacy)), LEGACY_PARSED);
});

test('S10: success:false is equally legacy', () => {
  assert.deepEqual(parseToolResult({ success: false, message: 'bad', error: { code: 'x' } }), LEGACY_PARSED);
});

test('S10: success present with ok:true -> still legacy (success wins over ok)', () => {
  assert.deepEqual(parseToolResult({ ok: true, success: false, data: { a: 1 } }), LEGACY_PARSED);
  assert.deepEqual(parseToolResult({ ok: true, success: true, data: { a: 1 } }), LEGACY_PARSED);
  assert.equal(isLegacyShape({ ok: true, success: false }), true);
});

test('S10: {ok:true} without success is NOT legacy', () => {
  assert.equal(isLegacyShape({ ok: true }), false);
  const p = parseToolResult({ ok: true });
  assert.equal(p.legacy, false);
  assert.equal(p.ok, true);
});

test('S10 probe: success with a null value is still a present key -> legacy', () => {
  assert.equal(isLegacyShape({ success: null }), true);
  assert.equal(isLegacyShape(JSON.parse('{"success":null}')), true);
  assert.equal(parseToolResult(viaText({ success: null })).legacy, true);
});

test('S10 probe: success nested inside data is data, not the legacy marker', () => {
  const p = parseToolResult({ ok: true, data: { success: true } });
  assert.equal(p.legacy, false);
  assert.equal(p.ok, true);
  assert.deepEqual(p.data, { success: true });
  assert.equal(isLegacyShape({ ok: true, data: { success: true } }), false);
});

test('S10: isLegacyShape is false for non-plain-object values and for inherited success', () => {
  for (const v of [null, undefined, [], [{ success: true }], 'success', 5, true]) {
    assert.equal(isLegacyShape(v), false, String(v));
  }
  assert.equal(isLegacyShape({}), false);
  assert.equal(isLegacyShape(Object.create({ success: true })), false);
  assert.equal(isLegacyShape({ success: true }), true);
});

// ---- S11 unusable inputs ----

test('S11: null, undefined, {}, [] and primitives -> null', () => {
  for (const v of [null, undefined, {}, [], 42, true, '', 'garbage', 'null', '[1]', '42']) {
    assert.equal(parseToolResult(v), null, JSON.stringify(v));
  }
});

test('S11: plain-text tool results (ok, gate_blocked: msg, any non-JSON) -> null, no throw', () => {
  for (const text of ['ok', 'gate_blocked: missing note', 'not_found: no such item', 'Error: boom']) {
    assert.equal(parseToolResult({ content: [{ type: 'text', text }], isError: text !== 'ok' }), null, text);
  }
});

test('S11: empty or textless content -> null', () => {
  assert.equal(parseToolResult({ content: [] }), null);
  assert.equal(parseToolResult({ content: [{ type: 'image' }] }), null);
  assert.equal(parseToolResult({ content: 'x' }), null);
});

test('S11: content text that is JSON but not an object -> null', () => {
  for (const text of ['42', '[1,2]', 'null', '"s"']) {
    assert.equal(parseToolResult({ content: [{ type: 'text', text }] }), null, text);
  }
});

test('S11: an ok field that is not a boolean does not make a bare object an envelope', () => {
  assert.equal(parseToolResult({ ok: 'yes', data: {} }), null);
  assert.equal(parseToolResult({ ok: 1 }), null);
});

test('S11 probe: a non-object structuredContent falls through to content[0].text', () => {
  const env = okEnvelope();
  for (const sc of [[1, 2], 'str', 5, null]) {
    const p = parseToolResult({ structuredContent: sc, content: [{ type: 'text', text: JSON.stringify(env) }] });
    assert.ok(p, JSON.stringify(sc));
    assert.equal(p.ok, true, JSON.stringify(sc));
    assert.equal(p.reqId, 'k7f3q9ab', JSON.stringify(sc));
  }
});

// f2b33c33: a 4.0 MCP error text is `<code>: <message>`, a newline and the compact error envelope
// (api-reference.md "Response envelope", the MCP text-content sentence). A client that sends no
// structuredContent still yields the envelope from that last line.

function errorText(env) {
  return `${env.error.code}: ${env.error.message}\n${JSON.stringify(env)}`;
}

const NOT_FOUND = {
  ok: false,
  error: { code: 'not_found', kind: 'permanent', message: 'No item matches 0000.', detail: { kind: 'item', id: '0000' }, fix: 'Verify the id.' },
  meta: { reqId: 'tail0001' },
};

test('f2b33c33: an error text with the JSON tail and no structuredContent yields the error envelope', () => {
  const p = parseToolResult({ content: [{ type: 'text', text: errorText(NOT_FOUND) }], isError: true });
  assert.ok(p);
  assert.equal(p.ok, false);
  assert.equal(p.error.code, 'not_found');
  assert.equal(p.error.kind, 'permanent');
  assert.equal(p.error.fix, 'Verify the id.');
  assert.deepEqual(p.error.detail, { kind: 'item', id: '0000' });
  assert.equal(p.reqId, 'tail0001');
});

test('f2b33c33: the same error text as a bare string parses identically', () => {
  assert.deepEqual(parseToolResult(errorText(NOT_FOUND)), parseToolResult({ content: [{ type: 'text', text: errorText(NOT_FOUND) }] }));
});

test('f2b33c33: a partial_failure tail keeps the failing results with their index and the summary', () => {
  const env = {
    ok: false,
    error: { code: 'partial_failure', kind: 'permanent', message: '1 of 2 elements failed.' },
    data: { results: [{ index: 1, ok: false, error: { code: 'not_found', message: 'gone', fix: 'check id' } }], summary: { ok: 1, failed: 1 } },
    meta: { reqId: 'tail0002' },
  };
  const p = parseToolResult({ content: [{ type: 'text', text: errorText(env) }], isError: true });
  assert.equal(p.error.code, 'partial_failure');
  assert.equal(p.results.length, 1);
  assert.equal(p.results[0].index, 1);
  assert.equal(p.results[0].error.code, 'not_found');
  assert.deepEqual(p.summary, { ok: 1, failed: 1 });
});

test('f2b33c33: retryAfterMs in the tail is lifted as from structuredContent', () => {
  const env = { ok: false, error: { code: 'unavailable', kind: 'shedding', message: 'busy', retryAfterMs: 250 }, meta: { reqId: 'tail0003' } };
  const viaTail = parseToolResult({ content: [{ type: 'text', text: errorText(env) }], isError: true });
  assert.deepEqual(viaTail, parseToolResult(viaStructured(env)));
  assert.equal(viaTail.error.retryAfterMs, 250);
});

test('f2b33c33: a message containing a newline still takes the last line as the tail', () => {
  const env = { ...NOT_FOUND, error: { ...NOT_FOUND.error, message: 'line one\nline two' } };
  const p = parseToolResult({ content: [{ type: 'text', text: errorText(env) }], isError: true });
  assert.equal(p.error.message, 'line one\nline two');
});

test('f2b33c33: a last line that is not an error envelope is ignored -> null', () => {
  for (const text of [
    'gate_blocked: missing notes\nnot json',
    'not_found: x\n{"ok":true,"data":{}}',
    'not_found: x\n{"ok":false}',
    'not_found: x\n[1,2]',
    'not_found: x\n',
    'ok',
  ]) {
    assert.equal(parseToolResult({ content: [{ type: 'text', text }], isError: text !== 'ok' }), null, text);
  }
});

test('f2b33c33: parseEnvelopeText takes a whole JSON text first, then only an error-envelope tail', () => {
  assert.deepEqual(parseEnvelopeText(JSON.stringify(okEnvelope())), okEnvelope());
  assert.deepEqual(parseEnvelopeText(errorText(NOT_FOUND)), NOT_FOUND);
  assert.equal(parseEnvelopeText('ok'), null);
  assert.equal(parseEnvelopeText(undefined), null);
  assert.equal(parseEnvelopeText(42), null);
});

test('f2b33c33: retro-lib extractResponseJson reads the error tail in every text-bearing shape', () => {
  const text = errorText(NOT_FOUND);
  for (const shape of [text, [{ type: 'text', text }], { content: [{ type: 'text', text }] }, { type: 'text', text }]) {
    assert.deepEqual(extractResponseJson(shape), NOT_FOUND, JSON.stringify(shape).slice(0, 40));
  }
  assert.equal(extractResponseJson({ content: [{ type: 'text', text: 'ok' }] }), null);
});
