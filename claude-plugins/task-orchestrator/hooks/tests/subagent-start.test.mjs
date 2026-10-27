// Protocol-text coverage for subagent-start.mjs on the 4.0 surface (0bbdc4cc P10 / AC8; the 3.x S15
// errorCode wording of c39fe915 is retired with the advance_item tool). Test author actor id:
// test-author:0bbdc4cc:r-202610161834-0bbdc4cc.
//
// The gating tests (phase-owner agent types, headless iteration, workflow-subagent skip, locator
// gate, fail-open stdin) are unchanged. The protocol-text tests assert the 4.0 call shapes and
// derive the expected error-code groups from lib/tool-catalog.json and lib/tool-names.mjs
// ERROR_KINDS (generated artifacts), never from the hook output.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ERROR_KINDS, TOOLS } from '../lib/tool-names.mjs';

const HOOK = fileURLToPath(new URL('../subagent-start.mjs', import.meta.url));

// Hermeticity recipe (Ruling 2): every spawn pins TASK_ORCHESTRATOR_HOME to its OWN empty temp
// dir, runs with cwd = a fixture dir (never the repo), and drops inherited config/mode env.
const tempDirs = [];
after(() => {
  for (const d of tempDirs) rmSync(d, { recursive: true, force: true });
});
function mkTemp(prefix) {
  const d = mkdtempSync(join(tmpdir(), `ss-${prefix}-`));
  tempDirs.push(d);
  return d;
}
function writeConfig(dir, text = 'project:\n  rootId: "00000000-0000-0000-0000-000000000001"\n') {
  mkdirSync(join(dir, '.taskorchestrator'), { recursive: true });
  writeFileSync(join(dir, '.taskorchestrator', 'config.yaml'), text);
}
// Default fixture: a project config reachable via cwd (walk-up).
const PROJECT_FIXTURE = mkTemp('project');
writeConfig(PROJECT_FIXTURE);

function spawnHook(inputText, { cwd = PROJECT_FIXTURE, home, env: envOverrides = {} } = {}) {
  const pinnedHome = home ?? mkTemp('home');
  const env = { ...process.env, TASK_ORCHESTRATOR_HOME: pinnedHome, ...envOverrides };
  for (const k of ['AGENT_CONFIG_DIR', 'TASK_ORCHESTRATOR_MODE', 'TASK_ORCHESTRATOR_API_URL', 'TASK_ORCHESTRATOR_API_TOKEN']) {
    if (!(k in envOverrides)) delete env[k];
  }
  return spawnSync(process.execPath, [HOOK], { input: inputText, env, cwd, encoding: 'utf-8' });
}

function runHook(payload = {}, envOverrides = {}, opts = {}) {
  return spawnHook(JSON.stringify(payload), { ...opts, env: envOverrides });
}

function protocolText(payload = { session_id: 'test-session', agent_id: 'test-agent', agent_type: 'task-orchestrator:implementer' }) {
  const res = runHook(payload);
  assert.equal(res.status, 0, `hook exited non-zero: ${res.stderr}`);
  const out = JSON.parse(res.stdout);
  assert.equal(out.hookSpecificOutput.hookEventName, 'SubagentStart');
  return out.hookSpecificOutput.additionalContext;
}

// ── S7 / AC8: the protocol names the 4.0 call shapes; the error-code groups come from the catalog ──
// Oracle (0bbdc4cc AC8): the protocol names advance (ops: [{op: "transition", itemId, trigger:
// "start"}]), notes_write (op: "upsert"), items_read (select: "context"), seatToken as a top-level field
// of every call when the dispatch prompt carries one, error.code / error.kind / retryAfterMs. The codes
// are NOT hand-written: lib/tool-catalog.json tools.advance.entries.transition.errors, partitioned by
// lib/tool-names.mjs ERROR_KINDS. gate_blocked = a required note is missing (stop, report the missing
// notes, do not retry; it is NOT an already-in-phase signal -- a second start moves the item forward,
// 66a5bc0c); the transient and shedding codes = stop, report retryAfterMs, never spin; the remaining
// permanent codes listed by name = stop and report. The expected sets below are computed here from
// those two generated files, never copied from the hook output.

const CATALOG = JSON.parse(readFileSync(fileURLToPath(new URL('../lib/tool-catalog.json', import.meta.url)), 'utf-8'));
const TRANSITION_CODES = CATALOG.tools.advance.entries.transition.errors;
const NON_PERMANENT = TRANSITION_CODES.filter((c) => ERROR_KINDS[c] !== 'permanent').sort();
const PERMANENT_OTHER = TRANSITION_CODES.filter((c) => ERROR_KINDS[c] === 'permanent' && c !== 'gate_blocked').sort();

const paragraphs = (text) => text.split(/\n\s*\n/);
const paragraphWith = (text, needle) => paragraphs(text).find((p) => p.includes(needle));
/** The backticked snake_case tokens of a paragraph that are not tool names. */
const codeTokens = (para) =>
  [...para.matchAll(/`([a-z]+(?:_[a-z]+)*)`/g)].map((m) => m[1]).filter((t) => !TOOLS.includes(t)).sort();

test('S7 oracle sanity: the catalog partition has all three groups (guards the computed expectations)', () => {
  assert.ok(TRANSITION_CODES.includes('gate_blocked'));
  assert.deepEqual(NON_PERMANENT, ['internal', 'resource_unavailable', 'unavailable']);
  assert.ok(PERMANENT_OTHER.length >= 5, PERMANENT_OTHER.join(','));
  assert.ok(PERMANENT_OTHER.includes('not_claim_holder') && PERMANENT_OTHER.includes('seat_forbidden'));
});

test('S7: protocol text names advance / transition / start, notes_write upsert, items_read context and the seatToken rule', () => {
  const text = protocolText();
  assert.ok(text.includes('`advance`'), 'advance');
  assert.match(text, /op:\s*"transition"/);
  assert.match(text, /trigger:\s*"start"/);
  assert.ok(text.includes('`notes_write`'), 'notes_write');
  assert.match(text, /op:\s*"upsert"/);
  assert.ok(text.includes('`items_read`'), 'items_read');
  assert.match(text, /select:\s*"context"/);
  assert.ok(text.includes('seatToken'), 'seatToken');
  assert.match(text, /top-level field of every call/);
  for (const field of ['error.code', 'error.kind', 'retryAfterMs']) {
    assert.ok(text.includes(field), field);
  }
});

test('S7: protocol text carries none of the retired 3.x vocabulary', () => {
  const text = protocolText();
  for (const retired of ['advance_item', 'manage_notes', 'get_context', 'invalid_actor', 'rejected_by_policy', 'errorCode', 'previousRole', 'item_not_found', 'dependency_blocked']) {
    assert.ok(!text.includes(retired), `retired token present: ${retired}`);
  }
  assert.ok(!/\bactor\b/i.test(text), 'the removed actor field must not be mentioned');
});

test('S7: every transition error code of the catalog is named in the protocol text', () => {
  const text = protocolText();
  for (const code of TRANSITION_CODES) {
    assert.ok(text.includes(code), `expected "${code}" (tools.advance.entries.transition.errors) in the protocol text`);
  }
});

// (66a5bc0c S6, at the end of this file, replaces the retired "gate_blocked is the already-in-phase
// signal" test.)

test('S7: the transient and shedding codes (ERROR_KINDS) share one paragraph: stop, report retryAfterMs, never spin', () => {
  const text = protocolText();
  const para = paragraphWith(text, NON_PERMANENT[0]);
  assert.ok(para, 'expected a paragraph naming the transient codes');
  assert.deepEqual(codeTokens(para), NON_PERMANENT, 'exactly the non-permanent codes, no hand-written extras');
  assert.match(para, /stop/i);
  assert.ok(para.includes('retryAfterMs'), para);
  assert.match(para, /do NOT retry|not retry|never spin|spin/i);
  for (const code of PERMANENT_OTHER) assert.ok(!para.includes(code), `${code} is permanent but sits in the transient paragraph`);
});

test('S7: the remaining permanent codes (ERROR_KINDS) are listed by name in one paragraph: stop and report', () => {
  const text = protocolText();
  const para = paragraphWith(text, 'not_claim_holder');
  assert.ok(para, 'expected a paragraph naming the permanent codes');
  assert.deepEqual(codeTokens(para), PERMANENT_OTHER, 'exactly the permanent codes of the catalog except gate_blocked');
  assert.match(para, /stop/i);
  assert.match(para, /report/i);
  for (const code of NON_PERMANENT) assert.ok(!para.includes(code), `${code} is not permanent but sits in the permanent paragraph`);
});

test('S7: the transient group and the permanent group are different paragraphs', () => {
  const text = protocolText();
  assert.notEqual(paragraphWith(text, NON_PERMANENT[0]), paragraphWith(text, 'not_claim_holder'));
});

test('S7 probe: the protocol text is identical for the plugin-qualified and bare implementer / reviewer (no per-seat code list drift)', () => {
  const a = protocolText({ session_id: 's', agent_id: 'a', agent_type: 'task-orchestrator:implementer' });
  const b = protocolText({ session_id: 's', agent_id: 'a', agent_type: 'implementer' });
  assert.equal(paragraphWith(a, 'not_claim_holder'), paragraphWith(b, 'not_claim_holder'));
});

// ── 004d65fd: headless-iteration and non-phase-owner agent-type gating ───────────────────────
// Oracle: item 004d65fd's specification, "Hooks that must honor it" table — subagent-start.mjs
// row: headless iteration exits silently before any output; interactive subagent injects the
// protocol ONLY when isPhaseOwnerAgentType(agent_type) is true.

test('S4: phase-owner agent_type (plugin-qualified implementer) gets the protocol', () => {
  const text = protocolText({ session_id: 's', agent_id: 'a', agent_type: 'task-orchestrator:implementer' });
  assert.ok(text.includes('Agent-Owned-Phase Protocol'));
});

// ── c71ff3cb / #356: Subagent Discipline item 4, leave nothing running ──────────────────────

test('#356: implementer protocol carries the leave-nothing-running discipline rule', () => {
  const text = protocolText({ session_id: 's', agent_id: 'a', agent_type: 'task-orchestrator:implementer' });
  const idx = text.indexOf('4. **Leave nothing running.**');
  assert.ok(idx > text.indexOf('## Subagent Discipline'), 'expected item 4 inside the Subagent Discipline list');
  const item = text.slice(idx);
  assert.ok(item.includes('can outlive your return'), 'expected the no-outliving-background-command rule');
  assert.ok(item.includes('never search from `/` or a drive root'), 'expected the bounded-search rule');
});

test('S4: phase-owner agent_type (bare reviewer) gets the protocol', () => {
  const text = protocolText({ session_id: 's', agent_id: 'a', agent_type: 'reviewer' });
  assert.ok(text.includes('Agent-Owned-Phase Protocol'));
});

test('S5: non-phase-owner agent types get no output', () => {
  for (const agentType of ['general-purpose', 'Explore', 'Plan', 'claude-code-guide', 'task-orchestrator:implementer-helper', undefined]) {
    const res = runHook({ session_id: 's', agent_id: 'a', agent_type: agentType });
    assert.equal(res.status, 0, `hook exited non-zero for ${agentType}: ${res.stderr}`);
    assert.equal(res.stdout, '', `expected empty stdout for agent_type ${agentType}, got: ${res.stdout}`);
  }
});

test('S6: headless iteration mode exits silently even for a phase-owner agent_type', () => {
  const res = runHook(
    { session_id: 's', agent_id: 'a', agent_type: 'task-orchestrator:implementer' },
    { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }
  );
  assert.equal(res.status, 0, `hook exited non-zero: ${res.stderr}`);
  assert.equal(res.stdout, '');
});

// ── 60e01b3a: workflow-subagent skip + conditional seat wording ──────────────────────────────
// Oracle: item 60e01b3a's specification -- skip injection entirely when agent_type is exactly
// "workflow-subagent" (Claude workflow agents follow their own script-driven transition logic),
// even if that dispatch happens to name an implementer/reviewer seat internally; and reword the
// protocol so only an entry seat (or a single phase owner with no seat named) is told to call
// advance_item(start) -- a non-entry or read-only seat should see no such instruction.

test('workflow-subagent agent_type gets no injected output at all', () => {
  const res = runHook({ session_id: 's', agent_id: 'a', agent_type: 'workflow-subagent' });
  assert.equal(res.status, 0, `hook exited non-zero: ${res.stderr}`);
  assert.equal(res.stdout, '', 'expected empty stdout for agent_type workflow-subagent');
});

test('workflow-subagent skip is unconditional on any other field in the payload', () => {
  // agent_type is always exactly "workflow-subagent" per the hooks reference for workflow-
  // dispatched subagents; this confirms the exact-match skip doesn't get overridden by other
  // fields (e.g. a workflow script happening to also pass a seat-like field).
  const res = runHook({ session_id: 's', agent_id: 'a', agent_type: 'workflow-subagent', seat: 'implementer' });
  assert.equal(res.status, 0, `hook exited non-zero: ${res.stderr}`);
  assert.equal(res.stdout, '');
});

test('invalid JSON on stdin fails open: proceeds as if no agent_type were given (no crash, exit 0, no output)', () => {
  const malformed = spawnHook('{not valid json');
  assert.equal(malformed.status, 0, `hook exited non-zero on invalid stdin: ${malformed.stderr}`);
  assert.equal(malformed.stdout, '', 'invalid stdin has no agent_type, so no phase-owner match, so no output');
});

test('empty stdin fails open the same way: exit 0, no output', () => {
  const res = spawnHook('');
  assert.equal(res.status, 0, `hook exited non-zero on empty stdin: ${res.stderr}`);
  assert.equal(res.stdout, '');
});

test('protocol text conditions advance on being the entry seat, not a blanket instruction', () => {
  const text = protocolText();
  assert.ok(
    text.includes("Follow your dispatch prompt's seat assignment"),
    'expected the seat-deferral framing to open the protocol'
  );
  assert.ok(
    /entry seat/i.test(text),
    'expected the text to name the entry-seat concept as the one that advances the item'
  );
  assert.ok(
    /read-only/i.test(text) && /never calls `advance`/.test(text),
    'expected the text to say a read-only agent never calls advance'
  );
});

// ── project-init locator gate (66d8f971) ─────────────────────────────────────────────────────

const IMPL = { session_id: 's', agent_id: 'a', agent_type: 'task-orchestrator:implementer' };

test('locator gate (a): project config via AGENT_CONFIG_DIR with an unrelated cwd injects the protocol', () => {
  const cfgDir = mkTemp('cfg');
  writeConfig(cfgDir);
  const res = runHook(IMPL, { AGENT_CONFIG_DIR: cfgDir }, { cwd: mkTemp('empty') });
  assert.equal(res.status, 0, res.stderr);
  assert.ok(JSON.parse(res.stdout).hookSpecificOutput.additionalContext.includes('Agent-Owned-Phase Protocol'));
});

test('locator gate (b): user-scope config only (pinned home) injects the protocol', () => {
  const home = mkTemp('home');
  writeConfig(home);
  const res = runHook(IMPL, {}, { home, cwd: mkTemp('empty') });
  assert.equal(res.status, 0, res.stderr);
  assert.ok(JSON.parse(res.stdout).hookSpecificOutput.additionalContext.includes('Agent-Owned-Phase Protocol'));
});

test('locator gate (c): no config anywhere -> empty stdout, exit 0', () => {
  const res = runHook(IMPL, {}, { cwd: mkTemp('empty') });
  assert.equal(res.status, 0, res.stderr);
  assert.equal(res.stdout, '');
});

test('locator gate (d): pinned home holding only deploy.env (no config.yaml) -> empty stdout', () => {
  const home = mkTemp('home');
  mkdirSync(join(home, '.taskorchestrator'), { recursive: true });
  writeFileSync(join(home, '.taskorchestrator', 'deploy.env'), 'X=1\n');
  const res = runHook(IMPL, {}, { home, cwd: mkTemp('empty') });
  assert.equal(res.status, 0, res.stderr);
  assert.equal(res.stdout, '');
});

test('locator gate (e): cwd with an empty .taskorchestrator/ dir and no config.yaml -> empty stdout', () => {
  const cwd = mkTemp('emptydir');
  mkdirSync(join(cwd, '.taskorchestrator'), { recursive: true });
  const res = runHook(IMPL, {}, { cwd });
  assert.equal(res.status, 0, res.stderr);
  assert.equal(res.stdout, '');
});

test('locator gate (f): hook source keeps the literal workflow-subagent string (run-planner-lib detects it by text)', () => {
  assert.ok(readFileSync(HOOK, 'utf-8').includes('workflow-subagent'));
});

// ── 66a5bc0c: a second `start` moves the item forward, so the seat reads the role first ──────────────
// Test author actor id: test-author:66a5bc0c:r-202610171301-66a5bc0c.
// Oracles (frozen in the item's test-plan before any implementation): O1 = the task-scope acceptance
// criteria AC1-AC7; O2 = bundled-rules/protocol.entry-seat.md (read the role first, send start only from
// the role before the phase; gate_blocked = a required note is missing); O3 = TransitionTable.kt (start
// moves queue, work and review forward, so a retried start can advance a seat's own item past its phase).
// The hook cannot see the phase, so the text is one phase-generic text for every phase-owner agent type.

const READ_FIRST = '**Read the role before you send `start`.**';
const readFirstParagraphs = (text) => paragraphs(text).filter((p) => p.includes(READ_FIRST));
/** The one paragraph that opens with the read-the-role-first literal (S1 asserts there is exactly one). */
const readFirstParagraph = (text) => {
  const found = readFirstParagraphs(text);
  assert.equal(found.length, 1, `expected exactly one paragraph containing ${READ_FIRST}, found ${found.length}`);
  return found[0];
};

test('66a5bc0c S1: exactly one paragraph opens with the read-the-role-first literal and names items_read with select: "context"', () => {
  const para = readFirstParagraph(protocolText());
  assert.ok(para.trimStart().startsWith(READ_FIRST), `paragraph must open with the literal: ${para}`);
  assert.ok(para.includes('items_read'), 'items_read');
  assert.match(para, /select:\s*"context"/);
});

test('66a5bc0c S2: that paragraph names queue (before a work phase) and work (before a review phase) in backticks', () => {
  const para = readFirstParagraph(protocolText());
  assert.ok(para.includes('`queue`'), 'the role before a work-phase seat: `queue`');
  assert.ok(para.includes('`work`'), 'the role before a review-phase seat: `work`');
});

test('66a5bc0c S3: that paragraph says why: a second `start` moves the item past your phase', () => {
  const para = readFirstParagraph(protocolText());
  assert.ok(para.includes('a second `start`'), para);
  assert.ok(para.includes('past your phase'), para);
});

test('66a5bc0c S4: when the context already shows your phase, do not send `start`', () => {
  const para = readFirstParagraph(protocolText());
  assert.ok(para.includes('already shows your phase'), para);
  assert.ok(para.includes('do not send `start`'), para);
});

test('66a5bc0c S5: any other role means stop and report', () => {
  const para = readFirstParagraph(protocolText());
  assert.ok(para.includes('any other role'), para);
  assert.match(para, /stop/i);
  assert.match(para, /report/i);
});

// Fixture condition that makes the negative assertions fail without the fix: the pre-fix text carried a
// paragraph naming gate_blocked with "already in your phase" and error.detail.role.
test('66a5bc0c S6: gate_blocked means a required note is missing (report, do not retry), never an already-in-phase signal', () => {
  const text = protocolText();
  const gateParas = paragraphs(text).filter((p) => p.includes('gate_blocked'));
  assert.ok(gateParas.length >= 1, 'expected a paragraph naming gate_blocked');
  const para = gateParas.find((p) => p.includes('required note'));
  assert.ok(para, `expected a gate_blocked paragraph that says a required note is missing: ${gateParas.join(' || ')}`);
  assert.match(para, /report/i);
  assert.match(para, /not retry|never retry/i);
  for (const p of gateParas) {
    assert.ok(!/already in (your )?phase/i.test(p), `gate_blocked must not be an already-in-phase signal: ${p}`);
  }
  assert.ok(!text.includes('detail.role'), 'detail.role is not part of the protocol text');
});

test('66a5bc0c S7: the text is identical for every phase-owner agent type (one phase-generic text)', () => {
  const base = protocolText({ session_id: 's', agent_id: 'a', agent_type: 'implementer' });
  for (const agentType of ['task-orchestrator:implementer', 'task-orchestrator:reviewer', 'reviewer']) {
    assert.equal(protocolText({ session_id: 's', agent_id: 'a', agent_type: agentType }), base, agentType);
  }
});

test('66a5bc0c S8: the rest of the protocol holds: codes named, gate_blocked out of the permanent paragraph, never-advance-again and discipline kept', () => {
  const text = protocolText();
  for (const code of TRANSITION_CODES) assert.ok(text.includes(code), `expected "${code}" in the protocol text`);
  const permanent = paragraphWith(text, 'not_claim_holder');
  assert.ok(permanent, 'expected the permanent-codes paragraph');
  assert.ok(!permanent.includes('gate_blocked'), 'gate_blocked has its own sentence, not the permanent list');
  assert.ok(text.includes('Never call `advance` again after entering your phase'), 'never-advance-again rule');
  assert.ok(text.includes('## Subagent Discipline'), 'Subagent Discipline section');
});

// S9 (gating unchanged: non-phase-owner types, headless iteration, workflow-subagent, no config, bad
// stdin) is covered by the kept tests S5, S6, the workflow-subagent tests and the locator-gate tests above.

// ── 66a5bc0c probes ──────────────────────────────────────────────────────────────────────────────────

test('66a5bc0c P1: the read-the-role-first paragraph is one paragraph holding every S2-S5 phrase (a blank line inside would split it)', () => {
  const para = readFirstParagraph(protocolText());
  for (const phrase of ['items_read', '`queue`', '`work`', 'a second `start`', 'past your phase', 'already shows your phase', 'do not send `start`', 'any other role']) {
    assert.ok(para.includes(phrase), `missing from the one paragraph: ${phrase}`);
  }
});

test('66a5bc0c P2: the new paragraph does not hijack the substring lookups of the transient and permanent paragraph tests', () => {
  const text = protocolText();
  const para = readFirstParagraph(text);
  assert.ok(!para.includes('internal'), 'internal (also as part of a word) belongs to the transient paragraph only');
  assert.ok(!para.includes('not_claim_holder'), 'not_claim_holder belongs to the permanent paragraph only');
  const transient = paragraphWith(text, 'internal');
  assert.ok(transient && transient.includes('resource_unavailable') && transient.includes('unavailable'), `first paragraph with "internal" must be the transient one: ${transient}`);
  const permanent = paragraphWith(text, 'not_claim_holder');
  assert.ok(permanent && permanent.includes('seat_forbidden'), `first paragraph with "not_claim_holder" must be the permanent one: ${permanent}`);
});

test('66a5bc0c P3: the hook source decodes as strict UTF-8 (no stray cp1252 bytes)', () => {
  const bytes = readFileSync(HOOK);
  assert.doesNotThrow(() => new TextDecoder('utf-8', { fatal: true }).decode(bytes));
});

test('66a5bc0c P5: no casing variant of gate_blocked is smuggled in', () => {
  const text = protocolText();
  const exact = text.split('gate_blocked').length - 1;
  const anyCase = text.toLowerCase().split('gate_blocked').length - 1;
  assert.ok(exact >= 1, 'gate_blocked is named');
  assert.equal(anyCase, exact, 'every mention of gate_blocked is the lowercase code');
});

test('66a5bc0c P6 replay: two spawns produce byte-identical protocol text', () => {
  assert.equal(protocolText(), protocolText());
});
