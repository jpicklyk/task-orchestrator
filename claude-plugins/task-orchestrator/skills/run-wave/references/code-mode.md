# Code Mode recipes for run-wave

In a Code Mode session the model sees only `REPL` and `Poll`; every tool is a global inside a `REPL` script. The steps, rules, checkpoints and reports in `SKILL.md`, `method-b.md` and `post-run.md` are unchanged. This reference gives one script per coordinator step, so the mechanical bytes (seat prompts, tokens, the Workflow args, note bodies, helper output) stay in the script and the model sees only a projection.

Tool call shapes are in [call-shapes-js.md](../../_generated/call-shapes-js.md): find the select or op by its anchor (for example `#runs_write-open`) and use that line; it is not copied here. A call the server answers `ok: true` resolves to a JSON string; any other answer throws, and the thrown message ends with the error envelope as one JSON line.

## Rules every recipe follows

- A token never leaves the script. A script reads a token with `(await file(path)).trim()`, writes one with `Write({file_path, content: tokenLine(token)})`, passes it as `seatToken`, and never calls `log()` on it.
- A script never returns a raw tool result, a note body, a seat prompt or a token. Each return is a projection built field by field.
- `stage-result` calls stay serial: each reads the state the previous call wrote, so never run two at once, not even inside one script.
- A write whose failure code the script branches on uses `atomic: true`, so a failure leaves nothing written (a non-atomic batch that fails midway would lose the tokens its good elements answered, since a token is answered once). Otherwise read the state back after a failure.
- A failure goes through `parseErrorTail` and `refusal`, never through message text. The kit's `call` does it: `run_paused` writes `paused.json` and returns `{paused: true}`; `unavailable` is retried once after `retryAfterMs`; a refusal returns `{refused, tool, reason}` and the recipe stops; any other code returns `{failed, code, fix}`.
- One round per script. No recipe waits for a seat, so each finishes far under the limit of about 600 seconds that stops a REPL script whatever its `$` timeout. Seats finish while no script runs; their notifications arrive through `Poll`.
- Every recipe starts with the prelude block and the kit block below pasted verbatim, then the recipe body. Placeholders such as `<scratchpad>` are string literals you replace before running.

## Script globals

Recipes assume these globals, taken from the Code Mode probe record: `tools[...]` (each tool, named by `toolKey`), `Agent`, `Workflow`, `SendMessage`, `Write`, `file(path)` (reads a file), `$(command)` (runs a shell command and resolves its output and exit status), `setTimeout` and `log()`. A name found wrong in a live session is a fix to this file only.

## Prelude

Generated from `scripts/lib/code-mode-helpers.mjs` by `scripts/gen-code-mode-prelude.mjs` (`--check` fails when this block is stale). It defines `toolKey`, `parseErrorTail`, `refusal` and `tokenLine`.

```js
// @generated:code-mode-prelude-begin (scripts/gen-code-mode-prelude.mjs; do not edit, run it to refresh)
function toolKey(tool, server = 'mcp-task-orchestrator') {
  if (typeof tool !== 'string' || !/^[a-z][a-z0-9_]*$/.test(tool)) return null;
  if (typeof server !== 'string' || !/^[A-Za-z0-9_.-]+$/.test(server)) return null;
  if (server.indexOf('__') !== -1) return null;
  return 'mcp__' + server + '__' + tool;
}

function parseErrorTail(err) {
  try {
    const candidates = [];
    if (typeof err === 'string') {
      candidates.push(err);
    } else if (err !== null && typeof err === 'object') {
      const m = err.message;
      if (typeof m === 'string') candidates.push(m);
      const e = err.error;
      if (typeof e === 'string') candidates.push(e);
    }
    const isPlain = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
    for (const text of candidates) {
      const tail = text.slice(text.lastIndexOf('\n') + 1);
      let parsed;
      try {
        parsed = JSON.parse(tail);
      } catch (_) {
        continue;
      }
      if (isPlain(parsed) && parsed.ok === false && isPlain(parsed.error)) return parsed;
    }
    return null;
  } catch (_) {
    return null;
  }
}

function refusal(value) {
  try {
    if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
    const P = 'Permission denied for';
    const hit = (s) => typeof s === 'string' && s.indexOf(P) !== -1;
    const build = (text, tool) => {
      const from = text.slice(text.indexOf(P));
      const nl = from.indexOf('\n');
      const line = (nl === -1 ? from : from.slice(0, nl)).trim();
      return { refused: true, tool, reason: line.slice(0, 300) };
    };
    const parseTool = (text) => {
      const m = /Permission denied for ([^\s:]+)/.exec(text);
      return m ? m[1] : null;
    };
    const message = value.message;
    const error = value.error;
    // a. Code Mode McpToolError
    if (typeof value.toolName === 'string') {
      if (hit(message)) return build(message, value.toolName);
      if (hit(error)) return build(error, value.toolName);
      return null;
    }
    // b. thrown Error whose message starts with the marker
    if (typeof message === 'string' && message.indexOf(P) === 0) {
      return build(message, parseTool(message));
    }
    // c. plain resolved object with a string error
    if (hit(error)) return build(error, parseTool(error));
    // d. $ result with a non-zero exit and the marker in stderr
    const names = ['exitCode', 'exit_code', 'code', 'status'];
    let exit;
    for (const n of names) {
      const v = value[n];
      if (typeof v === 'number' && !Number.isNaN(v)) {
        exit = v;
        break;
      }
    }
    if (exit !== undefined && exit !== 0 && hit(value.stderr)) {
      return build(value.stderr, parseTool(value.stderr));
    }
    return null;
  } catch (_) {
    return null;
  }
}

function tokenLine(token) {
  if (typeof token !== 'string') throw new TypeError('token must be a string');
  const t = token.trim();
  if (t === '') throw new TypeError('token must not be empty');
  if (t.indexOf('\r') !== -1 || t.indexOf('\n') !== -1) {
    throw new TypeError('token must be a single line');
  }
  return t + '\n';
}
// @generated:code-mode-prelude-end
```

## Kit

Hand-written glue the recipes share. Paste it after the prelude.

```js
const RUN_DIR = '<scratchpad>/run-wave/<runId>';
const HELPER = '<helper>';
const q = (s) => JSON.stringify(String(s));
const readJson = async (p) => JSON.parse(await file(p));
const writeJson = (p, v) => Write({ file_path: p, content: JSON.stringify(v, null, 1) + '\n' });
const sh = async (cmd) => {
  const r = await $(cmd);
  const ref = refusal(r);
  if (ref) return { ref, code: 1, out: '', err: '' };
  let code = 0;
  for (const k of ['exitCode', 'exit_code', 'code', 'status']) {
    if (typeof r[k] === 'number') { code = r[k]; break; }
  }
  return { ref: null, code, out: String(r.stdout === undefined ? '' : r.stdout), err: String(r.stderr === undefined ? '' : r.stderr) };
};
// node "<helper>" <cmd> <args>; the helper prints JSON (verify or notes-audit exit 3 on findings), except prompt, which prints the seat prompt as raw text: read it from raw.
const helper = async (cmd, args) => {
  const r = await sh('node ' + q(HELPER) + ' ' + cmd + ' ' + (args || ''));
  let json = null;
  try { json = JSON.parse(r.out); } catch (_) { json = null; }
  return { ref: r.ref, code: r.code, json, raw: r.out, err: r.err.slice(0, 300) };
};
const call = async (tool, args) => {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      return { ok: true, data: JSON.parse(await tools[toolKey(tool)](args)).data };
    } catch (e) {
      const ref = refusal(e);
      if (ref) return { ok: false, refused: ref };
      const env = parseErrorTail(e);
      if (!env) return { ok: false, code: 'unparsed', fix: String(e && e.message).slice(0, 200) };
      const er = env.error;
      if (er.code === 'unavailable' && attempt === 0) {
        await new Promise((res) => setTimeout(res, er.retryAfterMs || 1000));
        continue;
      }
      return { ok: false, code: er.code, fix: er.fix, detail: er.detail };
    }
  }
};
const stop = async (r) => {
  if (r.refused) return r.refused;
  if (r.code === 'run_paused') {
    await writeJson(RUN_DIR + '/paused.json', r.detail || {});
    return { paused: true, decisionId: r.detail && r.detail.pauseDecisionId };
  }
  return { failed: true, code: r.code, fix: r.fix };
};
const writeToken = (path, token) => Write({ file_path: path, content: tokenLine(token) });
const readToken = async (path) => (await file(path)).trim();
```

## R1: Step 0 probe and Step 2 snapshot

Replaces the Step 0 `probe`, the Step 2 snapshot reads, Step 3 `plan` and `explain`. The model sees the `explain` table (Checkpoint 1) and the ids that need a hand-made snapshot field, nothing else. `scopes` and `carryIns` hold note-derived text (see [snapshot.md](snapshot.md)): when the script returns `needs`, build those fields by hand, set them in `EXTRA` and run the script again.

```js
const SCRATCH = '<scratchpad>';
const ROOT_ID = '<rootId>';
const ANCESTOR_ID = null; // or the parent UUID
const METHOD = 'A'; // 'B' when Workflow is not callable (Step 0 F1)
const EXTRA = { scopes: undefined, carryIns: undefined };

const probe = await helper('probe', '');
if (probe.ref) return probe.ref;
if (probe.code !== 0 || !probe.json) return { failed: true, step: 'probe', code: probe.code, err: probe.err };
if (probe.json.serverCompat) return { stopped: true, serverCompat: probe.json.serverCompat };

const scope = ANCESTOR_ID ? { ancestorId: ANCESTOR_ID } : {};
const rows = async (args) => {
  const out = [];
  let cursor;
  for (;;) {
    const r = await call('items_read', Object.assign({ limit: 200 }, args, cursor ? { cursor } : {}));
    if (!r.ok) return { stop: await stop(r) };
    out.push(...(r.data.items || []));
    cursor = r.data.page && r.data.page.next;
    if (!cursor) return { items: out };
  }
};
const blocked = await rows(Object.assign({ select: 'blocked' }, scope));
if (blocked.stop) return blocked.stop;
const ready = await rows(Object.assign({ select: 'next' }, scope));
if (ready.stop) return ready.stop;
const working = await rows(Object.assign({ select: 'list', role: 'work' }, scope));
if (working.stop) return working.stop;

// candidates: ready and work-role items; resume any awaiting_decision entry by hand first (Step 2).
const seen = new Map();
for (const row of ready.items) seen.set(row.id, 'ready');
for (const row of working.items) seen.set(row.id, 'work');
const candidates = [];
const schemas = {};
const noteOwners = {};
const parentIds = new Set();
let features = null; // the server features, from the first schema answer (SKILL.md Step 0 F2)
const ruleRows = await call('config', { ops: [{ op: 'rules', rootId: ROOT_ID }] });
if (!ruleRows.ok) return await stop(ruleRows);
const rulesServed = ruleRows.data.results[0].rules.map((r) => ({ key: r.key, rulesVersion: r.rulesVersion }));
for (const [id, source] of seen) {
  const g = await call('items_read', { select: 'get', itemId: id });
  if (!g.ok) return await stop(g);
  const it = g.data;
  candidates.push({ id: it.id, short: it.id.slice(0, 8), title: it.title, priority: it.priority, type: it.type, role: it.role, parentId: it.parentId, traits: it.traits || [], hasChildren: false, source });
  if (it.parentId) parentIds.add(it.parentId);
  const s = await call('items_read', { select: 'schema', itemId: it.id });
  if (s.ok && features === null) features = s.data.features || [];
  schemas[it.id] = s.ok
    ? { status: 'ok', type: s.data.type, configFingerprint: s.data.configFingerprint, configSource: s.data.configSource, notes: s.data.notes, seats: s.data.seats, dispatch: s.data.dispatch, dispatchBySeat: s.data.dispatchBySeat, resources: s.data.resources }
    : { status: s.code === 'not_found' ? 'not-found' : 'unavailable' };
  if (source === 'work') {
    const keys = (s.ok ? s.data.notes : []).map((n) => n.key);
    const l = await call('notes_read', { select: 'list', itemId: it.id, keys });
    if (l.ok) noteOwners[it.id] = l.data.items.filter((n) => n.owner && n.owner.seat).map((n) => ({ key: n.key, seat: n.owner.seat }));
  }
}
const parents = {};
for (const pid of parentIds) {
  const c = await call('items_read', { select: 'context', itemId: pid });
  if (c.ok) parents[pid] = { role: c.data.item.role, canAdvance: c.data.gateStatus.canAdvance, missing: c.data.gateStatus.missing || [] };
}
const git = async (a) => (await sh('git ' + a)).out.trim();
const repoRoot = (await git('rev-parse --show-toplevel')).replace(/\\/g, '/');
await sh('git fetch origin');
let profileText = null; // the run profile, or {} when the file is absent; a file that does not parse stops the script
try { profileText = await file(repoRoot + '/.taskorchestrator/run-profile.json'); } catch (_) { profileText = null; }
const profile = profileText === null ? {} : JSON.parse(profileText);
const snapshot = {
  contract: 'run-wave/snapshot-v1', rootId: ROOT_ID, ancestorId: ANCESTOR_ID, capturedAt: new Date().toISOString(),
  features: features || [], rulesServed, candidates, noteOwners, parents, schemas, probe: probe.json,
  blocked: blocked.items.map((b) => ({ itemId: b.id, role: b.role, blockType: b.blockType, blockedBy: (b.blockers || []).map((x) => ({ itemId: x.id, role: x.role, effectiveUnblockRole: x.threshold, satisfied: x.satisfied })) })),
  profile, git: { repoRoot, originMain: await git('rev-parse origin/main'), worktrees: {} },
};
if (EXTRA.scopes) snapshot.scopes = EXTRA.scopes;
if (EXTRA.carryIns) snapshot.carryIns = EXTRA.carryIns;
const needs = [];
for (const b of snapshot.blocked) if (candidates.some((c) => c.id.startsWith(b.itemId)) && b.blockedBy.some((x) => !x.satisfied && candidates.some((c) => c.id.startsWith(x.itemId)))) needs.push(b.itemId);
if (needs.length && !EXTRA.scopes) return { needs: { scopes: needs, carryIns: 'list each candidate notes for carry-in-* keys' } };
await writeJson(SCRATCH + '/run-wave/snapshot.json', snapshot);
const plan = await helper('plan', '--in ' + q(SCRATCH + '/run-wave/snapshot.json') + ' --scratchpad ' + q(SCRATCH) + ' --method ' + METHOD + ' --out ' + q(SCRATCH + '/run-wave/plan-draft.json'));
if (plan.code !== 0) return { failed: true, step: 'plan', code: plan.code, err: plan.err };
const exp = await helper('explain', '--in ' + q(SCRATCH + '/run-wave/plan-draft.json'));
if (exp.code !== 0 || !exp.raw) return { failed: true, step: 'explain', code: exp.code, err: exp.err };
return { explain: exp.raw }; // explain prints a text table, not JSON: read raw
```

After Checkpoint 1, copy `plan-draft.json` and the snapshot into `<scratchpad>/run-wave/<runId>/` as Step 3 says (the `runId` is in the plan), then use R2.

## R2: Step 5 open and Step 5a assign

Sends `run-ops --phase open`, binds the server run id, writes the coordinator token, then assigns every schema seat in one atomic call and writes each seat token. Returns counts only.

```js
const plan0 = await readJson(RUN_DIR + '/plan.json');
const open = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase open');
if (open.code !== 0) return { failed: true, step: 'run-ops open', err: open.err };
const opened = await call('runs_write', { ops: open.json.ops });
if (!opened.ok) return await stop(opened);
const el = opened.data.results[0];
if (!el.ok) return { failed: true, step: 'open', code: el.error && el.error.code };
await sh('mkdir -p ' + q(RUN_DIR + '/tokens'));
await writeToken(open.json.tokenFile, el.coordinatorToken);
const bound = await helper('bind-run', '--in ' + q(RUN_DIR + '/plan.json') + ' --server-run ' + q(el.runId));
if (bound.code !== 0) return { failed: true, step: 'bind-run', err: bound.err };
await writeJson(RUN_DIR + '/plan.json', bound.json);

const CT = await readToken(open.json.tokenFile);
const assign = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase assign');
if (assign.code !== 0) return { failed: true, step: 'run-ops assign', err: assign.err };
const assigned = await call('runs_write', { ops: assign.json.ops, atomic: true, seatToken: CT });
if (!assigned.ok) return await stop(assigned);
let written = 0;
for (let i = 0; i < assign.json.ops.length; i += 1) {
  const r = assigned.data.results[i];
  if (r && r.ok && r.seatToken) { await writeToken(assign.json.tokenFiles[i], r.seatToken); written += 1; }
}
return { serverRunId: el.runId, seats: assign.json.ops.length, tokensWritten: written, failed: assign.json.ops.length - written };
```

A failed count above 0 means do not launch. Under `entryMode: pre-entered`, send the queue-to-work `advance` calls (one `transition` `start` per item, coordinator token) before this recipe. The Method B loop assigns per batch instead (R5); skip the assign half of R2 for Method B and keep the open half.

## R3: Step 5a adopt-notes (Method A)

Reads each planner stage's pre-filled notes token-less, renders `run-ops --phase adopt-notes` and sends each op under its token file. Note bodies stay in the script.

```js
const plan = await readJson(RUN_DIR + '/plan.json');
const observed = {};
for (const item of plan.args.items) {
  const stage = (item.stages || []).find((s) => s.output === 'planner-v1');
  if (!stage) continue;
  const list = [];
  for (const key of stage.notes || []) {
    const r = await call('notes_read', { select: 'get', itemId: item.id, key });
    if (r.ok) list.push({ key, role: r.data.role, body: r.data.body, version: r.data.version, owner: r.data.owner });
    else if (r.code !== 'not_found') return await stop(r);
  }
  observed[item.id] = list;
}
await writeJson(RUN_DIR + '/observed.json', observed);
const ops = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase adopt-notes --notes ' + q(RUN_DIR + '/observed.json'));
if (ops.code !== 0) return { failed: true, step: 'adopt-notes', err: ops.err };
const out = [];
for (let i = 0; i < ops.json.ops.length; i += 1) {
  const op = ops.json.ops[i];
  const tf = ops.json.tokenFiles[i];
  const r = await call('notes_write', Object.assign({ ops: [op] }, tf ? { seatToken: await readToken(tf) } : {}));
  out.push({ item: op.itemId.slice(0, 8), key: op.key, op: op.op, ok: r.ok, code: r.ok ? undefined : r.code });
  if (!r.ok && r.refused) return r.refused;
}
return { ops: out };
```

A `version_conflict` on a delete means the note changed since the read: skip that note and report it. Any other failed delete: do not launch that item's planner seat. A failed upsert after a good delete: re-send it token-less and report it.

## R4: Step 6a Method A launch

Reads the bound plan, calls `Workflow` with `args` as an object (never a JSON string) and records the identifiers. After this the turn ends: the result arrives as a task notification.

```js
const plan = await readJson(RUN_DIR + '/plan.json');
const state = await readJson(RUN_DIR + '/state.json');
const up = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase update --run-phase launched');
if (up.code !== 0) return { failed: true, step: 'update', err: up.err };
const upd = await call('runs_write', { ops: up.json.ops, seatToken: await readToken(RUN_DIR + '/tokens/coordinator.token') });
if (!upd.ok) return await stop(upd);
let launched;
try {
  launched = await Workflow({ name: 'task-orchestrator:implement-wave', args: plan.args });
} catch (e) {
  return refusal(e) || { failed: true, step: 'Workflow' };
}
state.phase = 'launched';
state.taskId = launched.taskId;
state.wfRunId = launched.wfRunId;
state.wfScriptPath = launched.wfScriptPath;
state.taskOutputFile = launched.outputFile || launched.output_file || null; // none when the launch result names no output file; R7 then takes the path from the notification
await writeJson(RUN_DIR + '/state.json', state);
return { taskId: launched.taskId };
```

Send the `planned` report before this script, and tell the user the result arrives later. A tier-2 coordinator sends `--run-phase planned` instead and does not call `Workflow` here.

## R5: Method B dispatch

One round per script: `next`, assign the batch, render each prompt, dispatch every assigned entry, return what was dispatched. It does not wait for a seat.

```js
const nx = await helper('next', '--plan ' + q(RUN_DIR + '/plan.json') + ' --state ' + q(RUN_DIR + '/state.json'));
if (nx.code !== 0 || !nx.json) return { failed: true, step: 'next', err: nx.err };
const CT = await readToken(RUN_DIR + '/tokens/coordinator.token');
const batch = nx.json.dispatch;
const inserted = []; // entries with no schema seat (e.g. a declarations extractor) run token-less
const assignable = [];
for (const e of batch) {
  const a = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase assign --item ' + e.item + ' --seat ' + e.seat);
  if (a.ref) return a.ref;
  if (a.code !== 0) { inserted.push(e); continue; }
  assignable.push({ e, op: a.json.ops[0], tokenFile: a.json.tokenFiles[0] });
}
let ready = [];
if (assignable.length) { // no schema-seat entry: nothing to assign, and an empty ops list would be refused
  const all = await call('runs_write', { ops: assignable.map((x) => x.op), atomic: true, seatToken: CT });
  if (all.ok) {
    for (let i = 0; i < assignable.length; i += 1) {
      await writeToken(assignable[i].tokenFile, all.data.results[i].seatToken);
      ready.push(assignable[i].e);
    }
  } else if (all.code === 'run_at_capacity') {
    // hold back what does not fit: assign one by one, in order
    for (const x of assignable) {
      const one = await call('runs_write', { ops: [x.op], seatToken: CT });
      if (one.ok) { await writeToken(x.tokenFile, one.data.results[0].seatToken); ready.push(x.e); }
      else if (one.code !== 'run_at_capacity') return await stop(one);
    }
  } else {
    return await stop(all);
  }
}
ready = ready.concat(inserted);
const dispatched = [];
for (const e of ready) {
  // prompt prints raw text, not JSON: take it from raw
  const p = await helper('prompt', '--plan ' + q(RUN_DIR + '/plan.json') + ' --state ' + q(RUN_DIR + '/state.json') + ' --item ' + e.item + ' --seat ' + e.seat);
  if (p.code !== 0 || !p.raw) { dispatched.push({ item: e.item, seat: e.seat, failed: 'prompt' }); continue; }
  const r = await Agent({
    prompt: p.raw,
    model: e.model,
    effort: e.effort,
    description: e.seat + ':' + e.item + ' [' + e.model + '/' + e.effort + ']',
    subagent_type: e.agentType ?? 'general-purpose',
  });
  dispatched.push({ item: e.item, seat: e.seat, agentId: r && (r.agentId || r.agent_id) });
}
return { dispatched, waiting: nx.json.waiting.length, settled: nx.json.settled.map((s) => ({ item: s.item, status: s.status })), complete: nx.json.complete };
```

Never pass `isolation`: each item's worktree comes from the plan. A `run_paused` answer stops the loop and sends a `paused` report. After `complete: true`, go to R7.

## R6: Method B collect

A seat's final message reaches the model as a task notification, never the script, and the notification's output file stays empty. So the model passes the envelope in: the last JSON object of the seat's final message, pasted as a JS object literal (JSON is a valid JS expression). Never paste it into a template string: a backtick or `${` breaks it. The script writes the envelope to a file and runs `stage-result`, serially, one script call per notification set.

```js
const RESULTS = [
  // { item: '<short>', seat: '<seat>', n: 1, agentId: '<id>', envelope: { /* pasted as is */ } },
];
const out = [];
for (const r of RESULTS) {
  if (!r.envelope || typeof r.envelope !== 'object') {
    // no parseable envelope: the one re-emit request, sent by the script
    await SendMessage({ to: r.agentId, message: 'Re-emit only your final envelope JSON object, nothing else.' });
    out.push({ item: r.item, seat: r.seat, status: 'reemit-requested' });
    continue;
  }
  const f = RUN_DIR + '/envelopes/' + r.item + '.' + r.seat + '.' + r.n + '.json';
  await writeJson(f, r.envelope);
  const s = await helper('stage-result', '--plan ' + q(RUN_DIR + '/plan.json') + ' --state ' + q(RUN_DIR + '/state.json') + ' --item ' + r.item + ' --seat ' + r.seat + ' --envelope ' + q(f));
  if (s.code !== 0 || !s.json) { out.push({ item: r.item, seat: r.seat, failed: 'stage-result' }); continue; }
  await writeJson(RUN_DIR + '/state.json', s.json.state);
  out.push({ item: r.item, seat: r.seat, status: s.json.status, reason: String(s.json.reason || '').slice(0, 120), decisionId: s.json.decisionId });
}
return out;
```

The loop is serial on purpose: do not turn it into `Promise.all`. A second failed re-emit is recorded as `stopped: "invalid envelope"` through a second pass with the envelope omitted and `n` incremented; do not ask a third time. A stage that stopped `awaiting_decision` needs the `decision` report at once (`SendMessage` to the session the report names), never an answer from the coordinator.

## R7: post-run Steps 0 to 4

Loads the result, sweeps the seats, verifies, audits notes, builds provenance and sends the batched advance. It returns findings only. For Method A, the notification's result goes to `result.json` first: paste the result object as a literal like R6; when the notification is cut short, set `OUTPUT_FILE` to the task output file it names (or rely on the `state.taskOutputFile` R4 recorded) and the script saves that file's `result` member. For Method B the script saves the state document itself, and the helper converts it when it reads `result.json`.

```js
const RESULT_LITERAL = null; // Method A: the pasted result object
const OUTPUT_FILE = null; // Method A, notification cut short: the task output file path (pretty-printed JSON)
const state = await readJson(RUN_DIR + '/state.json');
const plan = await readJson(RUN_DIR + '/plan.json');
const CT = await readToken(RUN_DIR + '/tokens/coordinator.token');
const serverRunId = plan.args.serverRunId;
let result = RESULT_LITERAL;
const outFile = OUTPUT_FILE || state.taskOutputFile;
if (!result && outFile) result = JSON.parse(await file(outFile)).result;
if (!result && ((plan.meta && plan.meta.method === 'B') || state.method === 'B')) result = state; // Method B: the state document (run-wave/state-v1) is the result
if (!result) return { needs: 'result', why: 'Method A: paste the notification result as RESULT_LITERAL, or set OUTPUT_FILE' };
await writeJson(RUN_DIR + '/result.json', result);

const seats = await call('runs_read', { select: 'seats', runId: serverRunId });
if (!seats.ok) return await stop(seats);
await writeJson(RUN_DIR + '/seats.json', seats.data);
const ends = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase end-seats --seats ' + q(RUN_DIR + '/seats.json') + ' --result ' + q(RUN_DIR + '/result.json'));
if (ends.ref) return ends.ref;
if (ends.code !== 0 || !ends.json) return { failed: true, step: 'end-seats', code: ends.code, err: ends.err };
const ended = [];
for (let i = 0; i < ends.json.ops.length; i += 1) {
  const r = await call('runs_write', { ops: [ends.json.ops[i]], seatToken: await readToken(ends.json.tokenFiles[i]) });
  ended.push({ ok: r.ok, code: r.ok ? undefined : r.code }); // unauthenticated: superseded or ended already
}

const ver = await helper('verify', '--plan ' + q(RUN_DIR + '/plan.json') + ' --result ' + q(RUN_DIR + '/result.json'));
const observed = [];
for (const item of plan.args.items) {
  const keys = [].concat(...(item.stages || []).map((s) => s.notes || []));
  const l = await call('notes_read', { select: 'list', itemId: item.id, keys });
  if (l.ok) for (const n of l.data.items) observed.push({ itemId: item.id, key: n.key });
}
await writeJson(RUN_DIR + '/observed-notes.json', observed);
const audit = await helper('notes-audit', '--plan ' + q(RUN_DIR + '/plan.json') + ' --notes ' + q(RUN_DIR + '/observed-notes.json') + ' --result ' + q(RUN_DIR + '/result.json'));
// provenance: node "<helper>" provenance --plan <plan> --result <result> --method A|B --turns <n> --meta <dispatch-meta.json>

const failing = (ver.json && ver.json.items || []).filter((i) => i.status !== 'ok').map((i) => i.short || i.item);
const adv = plan.args.items.map((i) => i.id).filter((id) => !failing.some((f) => id.startsWith(f)));
const advanced = await call('advance', { ops: adv.map((itemId) => ({ op: 'transition', itemId, trigger: '<start or complete per the schema>' })), seatToken: CT });
if (advanced.ok) {
  await writeJson(RUN_DIR + '/advance.json', advanced.data);
  const fu = await helper('follow-ups', '--in ' + q(RUN_DIR + '/advance.json'));
  return { ended, verifyExit: ver.code, failing, auditExit: audit.code, auditMissing: audit.json && audit.json.items, followUps: fu.json };
}
return { ended, verifyExit: ver.code, failing, auditExit: audit.code, advance: await stop(advanced) };
```

Before the advance, apply the live-owed hold of Step 4 (read each item's `session-tracking` for a live-owed line) and replace the trigger placeholder per item: `start` for an item whose schema has a review phase, `complete` for one without. Step 3's orchestrator notes, Step 5 and later steps stay as `post-run.md` says; this recipe covers Steps 0 to 4.
