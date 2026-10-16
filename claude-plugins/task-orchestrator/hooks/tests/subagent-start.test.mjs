// Protocol-text coverage for subagent-start.mjs on the 4.0 surface (0bbdc4cc P10 / AC8; the 3.x S15
// errorCode wording of c39fe915 is retired with the advance_item tool). Test author actor id:
// test-author:0bbdc4cc:r-202610161834-0bbdc4cc.
//
// The gating tests (phase-owner agent types, headless iteration, workflow-subagent skip, locator
// gate, fail-open stdin) are unchanged. The protocol-text tests assert the 4.0 call shapes and
// derive the expected error-code groups from lib/tool-catalog.json and lib/tool-names.mjs
// ERROR_KINDS (generated artifacts), never from the hook output.

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
// lib/tool-names.mjs ERROR_KINDS. gate_blocked with detail.role equal to the agent phase = already in
// phase; the transient and shedding codes = stop, report retryAfterMs, never spin; the remaining
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

test('S7: gate_blocked is the already-in-phase signal, keyed to error.detail.role equal to the agent phase', () => {
  const para = paragraphWith(protocolText(), 'gate_blocked');
  assert.ok(para, 'expected a paragraph naming gate_blocked');
  assert.match(para, /already in your phase/i);
  assert.ok(para.includes('detail.role'), para);
  assert.ok(!para.includes('stop immediately'), 'already-in-phase is not a stop-and-report case');
});

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
