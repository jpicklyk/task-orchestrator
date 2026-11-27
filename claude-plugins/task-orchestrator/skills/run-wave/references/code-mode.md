# Code Mode recipes for run-wave

In a Code Mode session the model sees only `REPL` and `Poll`; every tool is a global inside a `REPL` script. The steps, rules, checkpoints and reports in `SKILL.md`, `method-b.md` and `post-run.md` are unchanged. This reference gives one script per mechanical orchestrator step, so the mechanical bytes (seat prompts, tokens, the Workflow args, note bodies, helper output) stay in the script and the model sees only a projection. The Coverage table at the end lists every step of the three documents with its recipe, or `prose` where no script applies.

Tool call shapes are in [call-shapes-js.md](../../_generated/call-shapes-js.md): find the select or op by its anchor (for example `#runs_write-open`) and use that line; it is not copied here. A call the server answers `ok: true` resolves to a JSON string; any other answer throws, and the thrown message ends with the error envelope as one JSON line.

## Rules every recipe follows

- A token never leaves the script. A script reads a token with `(await file(path).text()).trim()`, writes one with `Write({file_path, content: tokenLine(token)})`, passes it as `seatToken`, and never calls `log()` on it.
- A script never returns a raw tool result, a note body, a seat prompt or a token. Each return is a projection built field by field.
- `stage-result` calls stay serial: each reads the state the previous call wrote, so never run two at once, not even inside one script.
- A failed write throws a top-level `partial_failure` for a non-atomic batch, whatever the element's own code is (it sits in `data.results[i].error`). The kit's `call` keeps that `data`, and `elementCode` (and `stop`) read the first failed element's code, so a one-op write can be branched on by its element code. For a write of several ops that tail holds only the failing entries, each with its `index` (at most 10): read it by `index`, never by position, and an op with no entry passed but its answer is not there (R8). A write of several ops whose failure the script branches on uses `atomic: true`, so a failure leaves nothing written (a non-atomic batch that fails midway would lose the tokens its good elements answered, since a token is answered once) and answers only the failing element's own error, with `error.index`. Otherwise read the state back after a failure.
- A failure goes through `parseErrorTail` and `refusal`, never through message text. The kit's `call` does it: `run_paused` writes `paused.json` (as `{code: 'run_paused', detail}`, the shape the `report` helper reads) and returns `{paused: true}`; `unavailable` is retried once after `retryAfterMs`; a refusal returns `{refused, tool, reason}` and the recipe stops; any other code returns `{failed, code, fix}`.
- `file(path)` returns a handle, not text: read it with `.text()` (or `.json()`, `.lines()`), and test for it with `.exists()`. `$(command)` throws a `ShellError` on a non-zero exit unless the promise is `.nothrow()`; its result carries `exitCode` (`null` when the command moved to the background), and a command's stderr is merged into `stdout`. The kit's `sh` and `helper` wrap both.
- A script's result is the value of its last expression, and `return` at the top level, `eval` and `Function` are refused. So each recipe is one `async function r<n>()` and a last line `await r<n>()`; the `return` statements are inside the function.
- One round per script. No recipe waits for a seat, so each finishes far under the limit of about 600 seconds that stops a REPL script whatever its `$` timeout. Seats finish while no script runs; their notifications arrive through `Poll`.
- The run closes through the MCP `runs_write` tool global (R11), never over REST: the retrospective trigger is a hook on that tool call and does not see a REST close. No recipe calls `fetch`.
- The REPL refuses `eval` and `Function`, so the prelude and the kit are defined once per REPL context as persistent globals, not pasted into each recipe script. A recipe script is then only its function and its last line. Placeholders such as `<scratchpad>` are string literals you replace before running. After a REPL context reset (a recipe fails with `<name> is not defined`, for example `readJson`) define them again and run that recipe again. Defining the kit again puts `RUN_DIR`, `HELPER` and `REPORT_TO` back to their placeholder text, so set all three again before that recipe runs: `HELPER` to the resolved helper path, `RUN_DIR` to the current run's folder (its `runId` is the plan id R1 returned; the folder is under `<scratchpad>/run-wave/`), `REPORT_TO` from the brief. The kit's `RUN_DIR`, `HELPER` and `REPORT_TO` are the exception to "once": each is reassigned when its value becomes known, as the Kit section says.

## Script globals

Recipes assume these globals, taken from the Code Mode probe record: `tools[...]` (each tool, named by `toolKey`), `Agent`, `Workflow`, `SendMessage`, `Write`, `file(path)` (a handle: `text()`, `json()`, `lines()`, `exists()`), `$(command)` (runs a shell command: resolves its output and `exitCode`, throws on a non-zero exit unless `.nothrow()`), `setTimeout` and `log()`. A name found wrong in a live session is a fix to this file only.

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

Hand-written glue the recipes share. Define it after the prelude, once per REPL context. `adoptNotes`, `sendReport`, `endSeats` and `setPhase` are the steps more than one recipe runs (R3 and R5; R10 and R11; R7 and R11; R2, R5, R7, R8 and R9). `RUN_DIR`, `HELPER` and `REPORT_TO` are `let`, not `const`, so each is set by a one-line assignment in its own `REPL` script without defining the kit again: `HELPER = '<helper>';` once, right after the probe resolves the helper path (`SKILL.md` Step 0 F0), before R1 runs; `RUN_DIR = '<scratchpad>/run-wave/<runId>';` once R1 returns the plan's `runId`, before R2 copies the plan into it, and again for each new run of the Step 9 loop (a new `runId`, a new folder); `REPORT_TO = 'main';` (or `null`) from the brief, before the first report. Until they are set they hold the placeholder text, so a recipe that reads `RUN_DIR` before R1 returns has nothing to read.

```js
let RUN_DIR = '<scratchpad>/run-wave/<runId>'; // reassigned once R1 returns the runId, and for each new run
let HELPER = '<helper>'; // reassigned once, when the helper path resolves
let REPORT_TO = 'main'; // the session SendMessage reports to; null returns the report block for the user instead
const q = (s) => JSON.stringify(String(s));
const readJson = async (p) => JSON.parse(await file(p).text());
const writeJson = (p, v) => Write({ file_path: p, content: JSON.stringify(v, null, 1) + '\n' });
// $ throws on a non-zero exit unless .nothrow(); a helper that exits 3 (verify, notes-audit) still has its JSON on stdout.
const sh = async (cmd) => {
  let r;
  try {
    r = await $(cmd).nothrow();
  } catch (e) {
    const thrown = refusal(e);
    if (thrown) return { ref: thrown, code: 1, out: '', err: '' };
    throw e;
  }
  const ref = refusal(r);
  if (ref) return { ref, code: 1, out: '', err: '' };
  const code = typeof r.exitCode === 'number' ? r.exitCode : -1;
  return { ref: null, code, out: String(r.stdout === undefined ? '' : r.stdout), err: String(r.stderr === undefined ? '' : r.stderr) };
};
// stderr is merged into stdout: a run-planner {"warning":...} or {"error":...} line may sit among the output. Drop those lines when the whole text is not JSON.
const splitOut = (out) => {
  try { return { json: JSON.parse(out), raw: out, errors: [] }; } catch (_) { /* fall through */ }
  const keep = [];
  const errors = [];
  for (const line of out.split('\n')) {
    let o = null;
    try { o = JSON.parse(line); } catch (_) { o = null; }
    if (o !== null && typeof o === 'object' && !Array.isArray(o) && 'error' in o) errors.push(line);
    else if (o !== null && typeof o === 'object' && !Array.isArray(o) && 'warning' in o) continue;
    else keep.push(line);
  }
  const raw = keep.join('\n');
  let json = null;
  try { json = JSON.parse(raw); } catch (_) { json = null; }
  return { json, raw, errors };
};
// node "<helper>" <cmd> <args>; the helper prints JSON (verify or notes-audit exit 3 on findings, a code, never a throw), except prompt, explain, review-prompt and report, which print raw text: read it from raw.
const helper = async (cmd, args) => {
  const r = await sh('node ' + q(HELPER) + ' ' + cmd + ' ' + (args || ''));
  const s = splitOut(r.out);
  // a failing helper prints one {"error":...} line, which also parses as the whole text: keep it as the error text
  const lone = r.code !== 0 && s.errors.length === 0 && s.json !== null && typeof s.json === 'object' && !Array.isArray(s.json) && 'error' in s.json ? r.out.trim() : '';
  return { ref: r.ref, code: r.code, json: s.json, raw: s.raw, err: (s.errors.join('\n') + lone + r.err).slice(0, 300) };
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
      return { ok: false, code: er.code, fix: er.fix, detail: er.detail, data: env.data };
    }
  }
};
// A failed non-atomic write throws a top-level partial_failure whose data.results keeps only the failing entries, each with its index (at most 10); the entry's error is the real code.
// failedElement returns the error of the first failing entry (by content, never data.results[0] as the first op), or null when r is not such an answer (an atomic batch throws the element's own code at the top).
// A caller that needs the answer per op of a batch of several reads the entries by index and treats an op with no entry as passed (R8).
const failedElement = (r) => {
  if (r.ok || r.code !== 'partial_failure') return null;
  const els = (r.data && r.data.results) || [];
  const el = els.find((x) => x && x.ok === false);
  return el && el.error ? el.error : null;
};
const stop = async (r) => {
  if (r.refused) return r.refused;
  const e = failedElement(r) || r;
  if (e.code === 'run_paused') {
    await writeJson(RUN_DIR + '/paused.json', { code: 'run_paused', detail: e.detail || {} }); // the run_paused error shape the report helper reads
    return { paused: true, decisionId: e.detail && e.detail.pauseDecisionId };
  }
  return { failed: true, code: e.code, fix: e.fix };
};
const writeToken = (path, token) => Write({ file_path: path, content: tokenLine(token) });
const readToken = async (path) => (await file(path).text()).trim();
// the error code of a write answer, null when it succeeded: the first failed element's code under a partial_failure, else the thrown code
// (a resolved answer is ok only when every element is ok, so an ok answer has no failed element)
const elementCode = (r) => {
  if (r.ok) return null;
  const e = failedElement(r);
  return (e ? e.code : r.code) || 'failed';
};
const initState = (plan, method) => ({ contract: 'run-wave/state-v1', runId: plan.args.runId, phase: 'planned', method, turns: 0, stages: {}, outs: {} });
// Turn-boundary protocol: the run's hints.phase (run token) and state.json both record the phase. Returns {ok: true} or {ok: false, r} with the failed answer.
const setPhase = async (plan, phase) => {
  const up = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase update --run-phase ' + phase);
  if (up.ref) return { ok: false, r: up.ref };
  if (up.code !== 0 || !up.json) return { ok: false, r: { code: 'run-ops', fix: up.err } };
  const r = await call('runs_write', { ops: up.json.ops, seatToken: await readToken(RUN_DIR + '/tokens/coordinator.token') });
  const sf = file(RUN_DIR + '/state.json');
  let st = (await sf.exists()) ? JSON.parse(await sf.text()) : {};
  if (Object.keys(st).length === 0) st = initState(plan, plan.meta && plan.meta.method);
  st.phase = phase;
  await writeJson(RUN_DIR + '/state.json', st);
  return r.ok ? { ok: true } : { ok: false, r };
};
// post-run Step 0b: ends every still-active member seat under its own token. Returns {ended} or {stop}.
const endSeats = async (plan) => {
  const seats = await call('runs_read', { select: 'seats', runId: plan.args.serverRunId });
  if (!seats.ok) return { stop: await stop(seats) };
  await writeJson(RUN_DIR + '/seats.json', seats.data);
  const withResult = (await file(RUN_DIR + '/result.json').exists()) ? ' --result ' + q(RUN_DIR + '/result.json') : '';
  const ends = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase end-seats --seats ' + q(RUN_DIR + '/seats.json') + withResult);
  if (ends.ref) return { stop: ends.ref };
  if (ends.code !== 0 || !ends.json) return { stop: { failed: true, step: 'end-seats', code: ends.code, err: ends.err } };
  const ended = [];
  for (let i = 0; i < ends.json.ops.length; i += 1) {
    const r = await call('runs_write', { ops: [ends.json.ops[i]], seatToken: await readToken(ends.json.tokenFiles[i]) });
    ended.push({ ok: r.ok, code: r.ok ? undefined : elementCode(r) }); // unauthenticated: superseded or ended already
  }
  return { ended };
};
// Step 5a adopt-notes: re-creates the pre-filled planner notes of every planner stage (or of the item whose short id is `only`) under the planner token.
// A version_conflict on a delete skips that note; any other failed delete holds the planner; a failed upsert after a good delete is re-sent token-less and holds the planner.
const adoptNotes = async (plan, only) => {
  const observed = {};
  for (const item of plan.args.items) {
    if (only && item.short !== only) continue;
    const stage = (item.stages || []).find((s) => s.output === 'planner-v1');
    if (!stage) continue;
    const list = [];
    for (const key of stage.notes || []) {
      const r = await call('notes_read', { select: 'get', itemId: item.id, key });
      if (r.ok) list.push({ key, role: r.data.role, body: r.data.body, version: r.data.version, owner: r.data.owner });
      else if (r.refused) return { stop: r.refused };
      else if (r.code !== 'not_found') return { stop: await stop(r) };
    }
    observed[item.id] = list;
  }
  const seen = RUN_DIR + (only ? '/observed-' + only + '.json' : '/observed.json');
  await writeJson(seen, observed);
  const ops = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase adopt-notes --notes ' + q(seen) + (only ? ' --item ' + only : ''));
  if (ops.ref) return { stop: ops.ref };
  if (ops.code !== 0 || !ops.json) return { stop: { failed: true, step: 'adopt-notes', err: ops.err } };
  const send = async (op, tokenFile) => call('notes_write', Object.assign({ ops: [op] }, tokenFile ? { seatToken: await readToken(tokenFile) } : {}));
  const out = [];
  const held = [];
  const skipped = [];
  let skipUpsert = false; // set by a failed delete: its paired upsert is not sent
  for (let i = 0; i < ops.json.ops.length; i += 1) {
    const op = ops.json.ops[i];
    const short = op.itemId.slice(0, 8);
    if (op.op === 'upsert' && skipUpsert) {
      skipUpsert = false;
      out.push({ item: short, key: op.key, op: op.op, skipped: true });
      continue;
    }
    const r = await send(op, ops.json.tokenFiles[i]);
    if (r.refused) return { stop: r.refused };
    const code = elementCode(r);
    out.push({ item: short, key: op.key, op: op.op, ok: code === null, code: code === null ? undefined : code });
    if (op.op === 'delete' && code !== null) {
      skipUpsert = true;
      if (code === 'version_conflict') skipped.push({ item: short, key: op.key });
      else held.push({ item: short, key: op.key, code });
    } else if (op.op === 'upsert' && code !== null) {
      const again = await send(op, null);
      if (again.refused) return { stop: again.refused };
      held.push({ item: short, key: op.key, code, resent: elementCode(again) === null });
    }
  }
  return { ops: out, held, skipped };
};
// The orchestrator report: the report helper prints the block, SendMessage carries it to REPORT_TO. Returns {event, sent, needs, wait} (plus block when REPORT_TO is null), never the block otherwise.
// The files each event reads are named by the helper's contract (SKILL.md Reporting); `files` overrides a path.
const sendReport = async (event, files) => {
  const plan = await readJson(RUN_DIR + '/plan.json');
  const want = { planned: [], decision: ['result', 'run'], paused: ['run', 'paused'], closed: ['result', 'close'] }[event];
  if (!want) return { failed: true, step: 'report', err: 'event must be planned, decision, paused or closed' };
  const use = Object.assign({}, files);
  if (want.includes('run') && !use.run && plan.args.serverRunId) {
    const g = await call('runs_read', { select: 'get', runId: plan.args.serverRunId });
    if (g.ok) await writeJson(RUN_DIR + '/run.json', g.data);
  }
  let args = '--plan ' + q(RUN_DIR + '/plan.json') + ' --event ' + event;
  for (const k of want) {
    const p = use[k] || RUN_DIR + '/' + k + '.json';
    if (await file(p).exists()) args += ' --' + k + ' ' + q(p);
  }
  const rep = await helper('report', args);
  if (rep.ref) return rep.ref;
  if (rep.code !== 0 || !rep.raw) return { failed: true, step: 'report', code: rep.code, err: rep.err };
  const m = /^needs: (.*)$/m.exec(rep.raw);
  const needs = m ? m[1].trim() : 'nothing';
  const wait = event === 'decision' || event === 'paused' || (event === 'planned' && needs !== 'nothing');
  const block = rep.raw.replace(/\s+$/, '');
  if (!REPORT_TO) return { event, sent: false, needs, wait, block };
  await SendMessage({ to: REPORT_TO, message: block });
  return { event, sent: true, needs, wait };
};
```

## R1: Step 0 probe, F7 resume check, Step 2 snapshot and Step 3 plan

Replaces F7, the Step 0 `probe`, the Step 2 snapshot, Step 3 `plan` and `explain`. The model sees one of four things: `{resume}` (the target's own open or paused run of this skill exists, by the F7 rule of `SKILL.md`: nothing else ran, go to the Resume matrix), `{stopped}` (F2 or F3 missed or the server is incompatible), `{needs}` (ids that need a hand-made snapshot field) or the `explain` table with the `runId`, the late carry-in count, `preDir` and `others` (Checkpoint 1, where it runs Step 0 F8 on the `Seat skills:` line; a non-empty `others` is the one line `other open runs left alone: <label> (<status>), ...`). The snapshot comes from the `snapshot` subcommand (see [snapshot.md](snapshot.md)); only when it exits 2 for `no REST API URL resolves` does the script read the server by hand, as the Step 2 table lists. `ANCESTOR_ID` scopes to a subtree, `ITEM_IDS` to an explicit item list (set one, leave the other null; with neither, the hand reads list the root's ready items). `MODE`, `ALTITUDE_SAFE` and `SHARED_WORKTREE` carry the invocation arguments of the same names to `plan`, and `--entry pre-entered` is added when the probe reports no phase 0 hooks. `scopes` and `carryIns` hold note-derived text: when the script returns `needs.scopes`, build `{<id>: {files, dependsOn?}}` by the scopes rule of `snapshot.md`, save it as `scopes.json` in the returned `preDir` (`<scratchpad>/run-wave/pre-<key>/`, the target's pre-runId folder: key = the first 8 hex of the ancestor id, else of the first item id, else `root`), set `SCOPES_FILE` and run the script again (in the hand fallback set `EXTRA.scopes` instead).

```js
async function r1() {
  const SCRATCH = '<scratchpad>';
  const ROOT_ID = '<rootId>';
  const ANCESTOR_ID = null; // the parent UUID, or null with ITEM_IDS
  const ITEM_IDS = null; // ['<id>', ...] for an explicit item list, else null
  const METHOD = 'B'; // 'A' only for a Workflow-capable orchestrator (Step 0 F1)
  const MODE = null; // 'shared' or 'per-item' (the --mode argument), else null
  const ALTITUDE_SAFE = false; // true for a director tier 2 orchestrator (--altitude-safe)
  const SHARED_WORKTREE = null; // { path: '<worktree>', branch: '<branch>' } for --worktree and --branch (shared mode only), else null
  const SCOPES_FILE = null; // after a needs.scopes return: the file holding {<id>: {files, dependsOn?}}
  const EXTRA = { scopes: undefined, carryIns: undefined }; // the hand fallback only

  // F7: the target's own open or paused run of this skill resumes through the Resume matrix, not through Steps 1-3;
  // another run on the root is left alone and reported in `others`
  const idKey = (s) => String(s || '').toLowerCase().replace(/-/g, '');
  const sameId = (a, b) => { const x = idKey(a); const y = idKey(b); return x !== '' && y !== '' && (x.startsWith(y) || y.startsWith(x)); };
  const runs = []; // every open or paused run on the root, any hints: the hand snapshot's otherRuns
  for (const status of ['open', 'paused']) {
    const l = await call('runs_read', { select: 'list', rootId: ROOT_ID, status, limit: 200 });
    if (!l.ok) return await stop(l);
    runs.push(...(l.data.items || []));
  }
  const hasItems = !!(ITEM_IDS && ITEM_IDS.length);
  // the distinct lower-case member shorts of a run's frontier (terminal members are not listed)
  const memberShorts = async (runId) => {
    const f = await call('runs_read', { select: 'frontier', runId });
    if (!f.ok) return { stop: await stop(f) };
    const ids = [];
    for (const k of ['ready', 'inFlight', 'waiting']) {
      for (const row of f.data[k] || []) {
        const id = row && row.id ? String(row.id).toLowerCase() : '';
        if (id && !ids.includes(id)) ids.push(id);
      }
    }
    return { ids };
  };
  let pointer = null; // the run= values of the target's pointer lines, read once and only for an unclassified run
  const pointerRuns = async () => {
    if (pointer) return pointer;
    const id = ANCESTOR_ID || (hasItems && ITEM_IDS.length === 1 ? ITEM_IDS[0] : null);
    pointer = { runs: [] };
    if (!id) return pointer;
    for (const key of ['run-pointers', 'session-tracking']) {
      const n = await call('notes_read', { select: 'get', itemId: id, key });
      if (n.ok) {
        for (const m of String(n.data.body || '').matchAll(/run-wave: \S+ run=([0-9a-fA-F]+)/g)) pointer.runs.push(m[1].toLowerCase());
        return pointer;
      }
      if (n.refused) return (pointer = { stop: n.refused });
      if (n.code !== 'not_found') return (pointer = { stop: await stop(n) });
    }
    return pointer;
  };
  const resume = [];
  const others = [];
  for (const run of runs) {
    const h = run.hints || {};
    if (!(typeof h.plan === 'string' && /^r-/.test(h.plan))) continue;
    const run8 = String(run.runId).slice(0, 8);
    let own = !ANCESTOR_ID && !hasItems; // no target: every r- run is own
    if (!own && ANCESTOR_ID && run.scopeId) own = sameId(run.scopeId, ANCESTOR_ID);
    if (!own && hasItems) {
      const m = await memberShorts(run.runId);
      if (m.stop) return m.stop;
      own = m.ids.some((s) => ITEM_IDS.some((id) => sameId(s, id)));
    }
    if (!own && (ANCESTOR_ID || ITEM_IDS.length === 1)) { // an ancestor, or a single item: its pointer line
      const p = await pointerRuns();
      if (p.stop) return p.stop;
      own = p.runs.some((r) => sameId(r, run.runId));
    }
    if (own) resume.push({ run8, plan: h.plan, status: run.status, phase: h.phase || null });
    else others.push({ run8, label: run.label || h.plan || run8, status: run.status });
  }
  if (resume.length) return { resume };
  // the pre-runId files of this target live in <scratchpad>/run-wave/pre-<key>/, so two targets never share them
  const preKey = (ANCESTOR_ID ? idKey(ANCESTOR_ID) : hasItems ? idKey(ITEM_IDS[0]) : '').slice(0, 8) || 'root';
  const PRE_DIR = SCRATCH + '/run-wave/pre-' + preKey;

  const probe = await helper('probe', '');
  if (probe.ref) return probe.ref;
  if (probe.code !== 0 || !probe.json) return { failed: true, step: 'probe', code: probe.code, err: probe.err };
  if (probe.json.serverCompat) return { stopped: true, serverCompat: probe.json.serverCompat };

  let snapshot = null;
  const snapFile = PRE_DIR + '/snapshot.json';
  const target = ANCESTOR_ID ? '--ancestor ' + q(ANCESTOR_ID) : (ITEM_IDS && ITEM_IDS.length ? '--items ' + q(ITEM_IDS.join(',')) : null);
  // the subcommand needs a scope; with none, the hand reads list the root's ready items
  const sn = target ? await helper('snapshot', '--root ' + q(ROOT_ID) + ' ' + target + ' --out ' + q(snapFile) + (SCOPES_FILE ? ' --scopes ' + q(SCOPES_FILE) : '')) : null;
  if (sn && sn.ref) return sn.ref;
  if (sn && sn.code === 0) {
    if (!sn.json) return { failed: true, step: 'snapshot', code: 0, err: 'no summary line' };
    const wantScopes = (sn.json.needs && sn.json.needs.scopes) || [];
    if (wantScopes.length) return { needs: { scopes: wantScopes }, preDir: PRE_DIR };
    snapshot = await readJson(snapFile);
  } else if (!sn || (sn.code === 2 && sn.err.includes('no REST API URL resolves'))) {
    snapshot = await handSnapshot();
    if (snapshot.stop) return snapshot.stop;
    if (snapshot.needs) return { needs: snapshot.needs, preDir: PRE_DIR };
    snapshot = snapshot.value;
    await writeJson(snapFile, snapshot);
  } else {
    return { failed: true, step: 'snapshot', code: sn.code, err: sn.err };
  }

  // F2: the server features, F3: the protocol rules served for this root
  const missingFeatures = ['seats', 'dispatchBySeat', 'rules'].filter((f) => !(snapshot.features || []).includes(f));
  if (missingFeatures.length) return { stopped: true, check: 'F2', missing: missingFeatures };
  const served = (snapshot.rulesServed || []).map((r) => r.key);
  const missingRules = ['protocol.entry-seat', 'protocol.in-phase-seat', 'protocol.read-only-agent'].filter((k) => !served.includes(k));
  if (missingRules.length) return { stopped: true, check: 'F3', missing: missingRules };

  // the Invocation arguments and Step 0 F4 flags: --mode, --altitude-safe, --worktree and --branch, and --entry pre-entered when the probe has no phase 0 hooks
  const flags = ' --method ' + METHOD
    + (MODE ? ' --mode ' + MODE : '')
    + (ALTITUDE_SAFE ? ' --altitude-safe' : '')
    + (SHARED_WORKTREE ? ' --worktree ' + q(SHARED_WORKTREE.path) + ' --branch ' + q(SHARED_WORKTREE.branch) : '')
    + (probe.json.phase0Hooks ? '' : ' --entry pre-entered');
  const plan = await helper('plan', '--in ' + q(snapFile) + ' --scratchpad ' + q(SCRATCH) + flags + ' --out ' + q(PRE_DIR + '/plan-draft.json'));
  if (plan.code !== 0) return { failed: true, step: 'plan', code: plan.code, err: plan.err };
  const exp = await helper('explain', '--in ' + q(PRE_DIR + '/plan-draft.json'));
  if (exp.code !== 0 || !exp.raw) return { failed: true, step: 'explain', code: exp.code, err: exp.err };
  const draft = await readJson(PRE_DIR + '/plan-draft.json');
  return { explain: exp.raw, runId: draft.args.runId, lateCarryIns: ((draft.meta && draft.meta.lateCarryIns) || []).length, preDir: PRE_DIR, others }; // explain prints a text table, not JSON: read raw

  // the Step 2 hand reads, used only when no REST API URL resolves
  async function handSnapshot() {
    const scope = ANCESTOR_ID ? { ancestorId: ANCESTOR_ID } : {};
    let ancestorFull = null; // items_read next with ancestorId lists the ancestor itself; it is left out below when it has children
    if (ANCESTOR_ID) {
      const a = await call('items_read', { select: 'get', itemId: ANCESTOR_ID });
      if (!a.ok) return { stop: await stop(a) };
      ancestorFull = a.data.id;
    }
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
    if (blocked.stop) return blocked;
    // candidates: ready and work-role items; resume any awaiting_decision entry by hand first (Step 2).
    const seen = new Map();
    if (ANCESTOR_ID || !(ITEM_IDS && ITEM_IDS.length)) {
      const ready = await rows(Object.assign({ select: 'next' }, scope));
      if (ready.stop) return ready;
      const working = await rows(Object.assign({ select: 'list', role: 'work' }, scope));
      if (working.stop) return working;
      for (const row of ready.items) seen.set(row.id, 'ready');
      for (const row of working.items) seen.set(row.id, 'work');
    } else {
      for (const id of ITEM_IDS) {
        const g = await call('items_read', { select: 'get', itemId: id });
        if (!g.ok) return { stop: await stop(g) };
        if (g.data.role === 'queue') seen.set(g.data.id, 'ready');
        else if (g.data.role === 'work') seen.set(g.data.id, 'work');
      }
    }
    const candidates = [];
    const schemas = {};
    const noteOwners = {};
    const parentIds = new Set();
    let features = null; // the server features, from the first schema answer (SKILL.md Step 0 F2)
    const ruleRows = await call('config', { ops: [{ op: 'rules', rootId: ROOT_ID }] });
    if (!ruleRows.ok) return { stop: await stop(ruleRows) };
    const rulesServed = ruleRows.data.results[0].rules.map((r) => ({ key: r.key, rulesVersion: r.rulesVersion }));
    for (const [id, source] of seen) {
      const g = await call('items_read', { select: 'get', itemId: id });
      if (!g.ok) return { stop: await stop(g) };
      const it = g.data;
      const kids = await call('items_read', { select: 'list', parentId: it.id, limit: 1 });
      if (!kids.ok) return { stop: await stop(kids) };
      const hasChildren = (kids.data.items || []).length > 0;
      if (ancestorFull !== null && it.id === ancestorFull && hasChildren) continue; // the scope's own container is no candidate; a leaf ancestor is a single-item scope
      candidates.push({ id: it.id, short: it.id.slice(0, 8), title: it.title, priority: it.priority, type: it.type, role: it.role, parentId: it.parentId, traits: it.traits || [], hasChildren, source });
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
    const pf = file(repoRoot + '/.taskorchestrator/run-profile.json');
    const profileText = (await pf.exists()) ? await pf.text() : null; // the run profile, or {} when the file is absent; a file that does not parse stops the script
    const profile = profileText === null ? {} : JSON.parse(profileText);
    const value = {
      contract: 'run-wave/snapshot-v1', rootId: ROOT_ID, ancestorId: ANCESTOR_ID, capturedAt: new Date().toISOString(),
      features: features || [], rulesServed, candidates, noteOwners, parents, schemas, probe: probe.json,
      blocked: blocked.items.map((b) => ({ itemId: b.id, role: b.role, blockType: b.blockType, blockedBy: (b.blockers || []).map((x) => ({ itemId: x.id, role: x.role, effectiveUnblockRole: x.threshold, satisfied: x.satisfied })) })),
      profile, git: { repoRoot, originMain: await git('rev-parse origin/main'), worktrees: {} },
    };
    // every open or paused run still on the root is another run (an own run returned {resume} above)
    const otherRuns = [];
    for (const run of runs) {
      const m = await memberShorts(run.runId);
      if (m.stop) return { stop: m.stop };
      const h = run.hints || {};
      otherRuns.push({ runId: run.runId, label: run.label || h.plan || String(run.runId).slice(0, 8), status: run.status, scopeId: run.scopeId || null, worktree: h.worktree || null, branch: h.branch || null, members: m.ids });
    }
    if (otherRuns.length) value.otherRuns = otherRuns;
    if (EXTRA.scopes) {
      // a key that is a member short of another run is that run's claim; every other key stays in scopes
      value.scopes = {};
      for (const [k, entry] of Object.entries(EXTRA.scopes)) {
        const holder = candidates.some((c) => c.id === k) ? null : otherRuns.find((r) => r.members.includes(k.toLowerCase()));
        if (holder) (holder.claims = holder.claims || {})[k.toLowerCase()] = (entry && entry.files) || [];
        else value.scopes[k] = entry;
      }
    }
    if (EXTRA.carryIns) value.carryIns = EXTRA.carryIns;
    const needs = [];
    for (const b of value.blocked) if (candidates.some((c) => c.id.startsWith(b.itemId)) && b.blockedBy.some((x) => !x.satisfied && candidates.some((c) => c.id.startsWith(x.itemId)))) needs.push(b.itemId);
    // with another run open, every candidate (full id) and every member (short) names its files
    if (otherRuns.length) for (const id of [...candidates.map((c) => c.id), ...otherRuns.flatMap((r) => r.members)]) if (!needs.includes(id)) needs.push(id);
    if (needs.length && !EXTRA.scopes) return { needs: { scopes: needs, carryIns: 'list each candidate notes for carry-in-* keys' } };
    return { value };
  }
}
await r1()
```

A `{resume}` return sends the model to the Resume matrix of `SKILL.md` with the `run8` and phase it names. A `{stopped}` return is the F2 or F3 stop of `SKILL.md` Step 0 (its repair text applies). After Checkpoint 1 (the `explain` table and the F8 check), use R2 with `PRE_DIR` set to the returned `preDir`: it copies `plan-draft.json` and the snapshot from there into `<scratchpad>/run-wave/<runId>/` for the `runId` returned here. A non-zero `lateCarryIns` means write the disposition lines of Step 3 before R2.

## R2: Steps 4, 5 and 5a: worktrees, open, parent check, pointer, pre-entered start, Method A assign

Copies `plan-draft.json` (only when `plan.json` is absent) and the snapshot from `PRE_DIR` (the `preDir` R1 returned) into `<scratchpad>/run-wave/<runId>/`, creates the `meta.worktreesToCreate` worktrees with `git -C <args.repoRoot>` (a path that is already a worktree is reused; a branch that exists is added without `-b`), sends `run-ops --phase open`, binds the server run id and writes the run token. It then runs the Step 5 parent check and writes the pointer line (target: `meta.scopeId`, else the single item; the key is `run-pointers` when the target's schema declares it, else `session-tracking`, skipped on a seat-less item whose stage owns that note; written under the run token when the target is a run member, token-less otherwise). Under `entryMode: pre-entered` it starts every queue-role item and stops (`{stopped: 'pre-entered', notEntered}`) before any assign when one did not enter. Method A then assigns every schema seat whose token file is absent in one atomic call; Method B skips the assign (R5 assigns per batch). The last step records phase `planned` (both methods; R4 and R5 record `launched`). A rerun on a bound plan whose `tokens/coordinator.token` exists skips open and bind and rewrites the pointer's `parent=` field in place. Returns counts, `parent`, `parentMissing` and the worktree counts only.

```js
async function r2() {
  const PRE_DIR = '<preDir>'; // the preDir R1 returned: <scratchpad>/run-wave/pre-<key>
  const copy = async (from, to) => {
    if ((await file(from).exists()) && !(await file(to).exists())) await Write({ file_path: to, content: await file(from).text() });
  };
  await copy(PRE_DIR + '/plan-draft.json', RUN_DIR + '/plan.json');
  await copy(PRE_DIR + '/snapshot.json', RUN_DIR + '/snapshot.json');
  let plan = await readJson(RUN_DIR + '/plan.json');
  await sh('mkdir -p ' + q(RUN_DIR + '/tokens'));

  // Step 4: worktrees, under git -C of the main checkout
  const wt = { created: 0, reused: 0 };
  const todo = (plan.meta && plan.meta.worktreesToCreate) || [];
  if (todo.length) {
    const repoRoot = plan.args.repoRoot;
    if (!repoRoot) return { failed: true, step: 'worktree', err: 'plan.args.repoRoot is missing' };
    const gitC = 'git -C ' + q(repoRoot) + ' ';
    const listed = await sh(gitC + 'worktree list --porcelain');
    if (listed.ref) return listed.ref;
    const have = new Set(listed.out.split('\n').filter((l) => l.startsWith('worktree ')).map((l) => l.slice(9).trim().replace(/\\/g, '/').toLowerCase()));
    for (const w of todo) {
      const p = String(w.path).replace(/\\/g, '/');
      if (have.has(p.toLowerCase())) { wt.reused += 1; continue; }
      const branchKnown = (await sh(gitC + 'show-ref --verify --quiet ' + q('refs/heads/' + w.branch))).code === 0;
      const add = branchKnown ? 'worktree add ' + q(p) + ' ' + q(w.branch) : 'worktree add -b ' + q(w.branch) + ' ' + q(p) + (w.base ? ' ' + q(w.base) : '');
      const r = await sh(gitC + add);
      if (r.ref) return r.ref;
      if (r.code !== 0) return { failed: true, step: 'worktree', path: p, err: r.out.slice(0, 200) };
      wt.created += 1;
    }
  }

  // Step 5: open and bind, unless this is a rerun of a bound plan
  const tokenFile = RUN_DIR + '/tokens/coordinator.token';
  let serverRunId = plan.args.serverRunId;
  if (!(serverRunId && (await file(tokenFile).exists()))) {
    const open = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase open');
    if (open.code !== 0) return { failed: true, step: 'run-ops open', err: open.err };
    const opened = await call('runs_write', { ops: open.json.ops });
    if (!opened.ok) return await stop(opened);
    const el = opened.data.results[0];
    if (!el.ok) return { failed: true, step: 'open', code: el.error && el.error.code };
    await writeToken(open.json.tokenFile, el.orchestratorToken ?? el.coordinatorToken); // coordinatorToken: a server before 4.1
    const bound = await helper('bind-run', '--in ' + q(RUN_DIR + '/plan.json') + ' --server-run ' + q(el.runId));
    if (bound.code !== 0) return { failed: true, step: 'bind-run', err: bound.err };
    plan = bound.json;
    await writeJson(RUN_DIR + '/plan.json', plan);
    serverRunId = el.runId;
  }
  const CT = await readToken(tokenFile);
  const items = plan.args.items;
  const member = (id) => items.find((i) => i.id === id || i.id.startsWith(id) || id.startsWith(i.id));

  // Step 5 parent check, then the pointer line
  const scopeId = plan.meta && plan.meta.scopeId;
  const target = scopeId || (items.length === 1 ? items[0].id : null);
  let parent = 'skipped';
  const parentMissing = [];
  if (scopeId && !member(scopeId) && !String(plan.args.rootId || '').startsWith(String(scopeId).slice(0, 8))) {
    const c = await call('items_read', { select: 'context', itemId: scopeId });
    if (!c.ok) return await stop(c);
    const gate = c.data.gateStatus || {};
    if (c.data.item.role !== 'queue') {
      parent = c.data.item.role;
    } else if (gate.canAdvance) {
      const a = await call('advance', { ops: [{ op: 'transition', itemId: scopeId, trigger: 'start' }], seatToken: CT });
      if (a.refused) return a.refused;
      const code = elementCode(a);
      parent = code === null ? 'started' : 'blocked:' + code;
    } else {
      parentMissing.push(...(gate.missing || []).map((m) => (typeof m === 'string' ? m : m.key)));
      parent = 'blocked:' + parentMissing.join(',');
    }
  }
  let pointer = 'none';
  if (target) {
    const sch = await call('items_read', { select: 'schema', itemId: target });
    if (!sch.ok) return await stop(sch);
    const declared = sch.data.notes || [];
    const row = declared.find((n) => n.key === 'run-pointers') || declared.find((n) => n.key === 'session-tracking');
    const item = member(target);
    // SKILL Step 5: the skip applies to the session-tracking fallback only, and only on a seat-less schema (a stage with implicitOwner) whose stage owns that note
    const seatless = Boolean(row && row.key === 'session-tracking' && item && (item.stages || []).some((s) => s.implicitOwner === true) && (item.stages || []).some((s) => (s.notes || []).includes('session-tracking')));
    if (row && !seatless) {
      const g = await call('notes_read', { select: 'get', itemId: target, key: row.key });
      if (g.refused) return g.refused;
      if (!g.ok && g.code !== 'not_found') return await stop(g);
      const old = g.ok ? String(g.data.body || '').replace(/\n+$/, '') : '';
      const line = 'run-wave: ' + plan.args.runId + ' run=' + String(serverRunId).slice(0, 8) + ' parent=' + parent;
      const lines = old === '' ? [] : old.split('\n');
      const at = lines.findIndex((l) => l.startsWith('run-wave: ' + plan.args.runId + ' '));
      if (at === -1) lines.push(line);
      else lines[at] = line;
      const w = await call('notes_write', Object.assign({ ops: [{ op: 'upsert', itemId: target, key: row.key, role: row.role || 'work', body: lines.join('\n'), expectedVersion: g.ok ? g.data.version : 0 }] }, item ? { seatToken: CT } : {}));
      if (w.refused) return w.refused;
      const wcode = elementCode(w);
      pointer = wcode === null ? row.key : 'failed:' + wcode;
    }
  }

  // Step 5a: under entryMode pre-entered every queue-role item enters work first
  if (plan.args.entryMode === 'pre-entered') {
    const queued = [];
    for (const it of items) {
      const c = await call('items_read', { select: 'context', itemId: it.id });
      if (!c.ok) return await stop(c);
      if (c.data.item.role === 'queue') queued.push(it);
    }
    if (queued.length) {
      const a = await call('advance', { ops: queued.map((it) => ({ op: 'transition', itemId: it.id, trigger: 'start' })), seatToken: CT });
      if (!a.ok && a.code !== 'partial_failure') return await stop(a);
      const notEntered = [];
      for (const it of queued) {
        const c = await call('items_read', { select: 'context', itemId: it.id });
        if (!c.ok) return await stop(c);
        if (c.data.item.role === 'queue') notEntered.push(it.short);
      }
      if (notEntered.length) return { stopped: 'pre-entered', notEntered };
    }
  }

  // Step 5a Method A: every schema seat whose token file is absent, in one atomic call
  let seats = 0;
  let written = 0;
  let failed = 0;
  if (!(plan.meta && plan.meta.method === 'B')) {
    const assign = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase assign');
    if (assign.code !== 0) return { failed: true, step: 'run-ops assign', err: assign.err };
    const ops = [];
    const files = [];
    for (let i = 0; i < assign.json.ops.length; i += 1) {
      if (await file(assign.json.tokenFiles[i]).exists()) continue;
      ops.push(assign.json.ops[i]);
      files.push(assign.json.tokenFiles[i]);
    }
    seats = ops.length;
    if (ops.length) {
      const assigned = await call('runs_write', { ops, atomic: true, seatToken: CT });
      if (!assigned.ok) return await stop(assigned);
      for (let i = 0; i < ops.length; i += 1) {
        const r = assigned.data.results[i];
        if (r && r.ok && r.seatToken) { await writeToken(files[i], r.seatToken); written += 1; }
      }
      failed = ops.length - written;
    }
  }

  const ph = await setPhase(plan, 'planned');
  if (!ph.ok) return await stop(ph.r);
  return { serverRunId, seats, tokensWritten: written, failed, parent, parentMissing, pointer, worktrees: wt };
}
await r2()
```

A `failed` count above 0 means do not launch. `parent` is the value written into the pointer's `parent=` field; `parentMissing` lists the keys a gate-blocked parent still needs (tell the user before launch and record them under Friction). A `{stopped: 'pre-entered'}` return names the shorts that did not enter: report them and stop. Under Method B the next step is the `planned` report (R10), then R5 (waiting for the director's `approved` first when the report's `needs` is not `nothing`).

## R3: Step 5a adopt-notes (Method A)

Runs the kit's `adoptNotes` for every planner stage. The notes are read token-less, `run-ops --phase adopt-notes` renders the delete and upsert of each, and each op is sent under its token file. Note bodies stay in the script. Method B adopts per batch inside R5.

```js
async function r3() {
  const plan = await readJson(RUN_DIR + '/plan.json');
  const a = await adoptNotes(plan);
  if (a.stop) return a.stop;
  return { ops: a.ops, held: a.held, skipped: a.skipped };
}
await r3()
```

A `skipped` entry is a delete answered `version_conflict`: the note changed since the read, never re-create it, and report it. A `held` entry is a failed delete (do not launch that item's planner seat) or a failed upsert after a good delete (re-sent token-less, do not launch that seat), each with its error code.

## R4: Step 6a Method A launch

Reads the bound plan, calls `Workflow` with `args` as an object (never a JSON string) and records the identifiers. After this the turn ends: the result arrives as a task notification.

```js
async function r4() {
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
}
await r4()
```

Send the `planned` report (R10) before this script, and tell the user the result arrives later. A tier-2 orchestrator sends `--run-phase planned` (R2 already did) and does not call `Workflow` here.

## R5: Method B dispatch

One round per script: records phase `launched` on the first round, `next`, assigns the batch, adopts the pre-filled notes of each planner entry, renders each prompt, dispatches every assigned entry and returns what was dispatched. It does not wait for a seat. A gated tier-2b run stays at phase `planned` until the director's `approved` message: do not run R5 before it.

```js
async function r5() {
  const plan = await readJson(RUN_DIR + '/plan.json');
  const sf = file(RUN_DIR + '/state.json');
  const state0 = (await sf.exists()) ? JSON.parse(await sf.text()) : {};
  if (state0.phase !== 'launched') {
    const ph = await setPhase(plan, 'launched'); // the first round only: later rounds find the phase recorded
    if (!ph.ok) return await stop(ph.r);
  }
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
        else if (elementCode(one) !== 'run_at_capacity') return await stop(one); // a one-op write answers partial_failure: read the element's code
      }
    } else {
      return await stop(all);
    }
  }
  // adopt the pre-filled notes of every planner entry right after its assign; a failed adoption holds that planner back
  const held = [];
  const skipped = [];
  const adopted = [];
  for (const e of ready) {
    const item = plan.args.items.find((i) => i.short === e.item);
    const stage = item && (item.stages || []).find((s) => s.seat === e.seat);
    if (!stage || stage.output !== 'planner-v1' || !assignable.some((x) => x.e === e)) { adopted.push(e); continue; }
    const a = await adoptNotes(plan, e.item);
    if (a.stop) return a.stop;
    skipped.push(...a.skipped);
    if (a.held.length) held.push(...a.held); else adopted.push(e);
  }
  ready = adopted.concat(inserted.filter((e) => !adopted.includes(e)));
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
  return { dispatched, held, skipped, waiting: nx.json.waiting.length, settled: nx.json.settled.map((s) => ({ item: s.item, status: s.status })), complete: nx.json.complete };
}
await r5()
```

Never pass `isolation`: each item's worktree comes from the plan. A `run_paused` answer stops the loop and sends a `paused` report (R10). A `held` entry is a planner whose pre-filled note could not be adopted (code in the entry): it is not dispatched, and the next round assigns it again. A `skipped` entry is a note that changed since the read (`version_conflict`): report it. After `complete: true`, go to R7.

## R6: Method B collect

A seat's final message reaches the model as a task notification, never the script, and the notification's output file stays empty. So the model passes the envelope in: the last JSON object of the seat's final message, pasted as a JS object literal (JSON is a valid JS expression). Never paste it into a template string: a backtick or `${` breaks it. The script writes the envelope to a file and runs `stage-result`, serially, one script call per notification set.

```js
async function r6() {
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
}
await r6()
```

The loop is serial on purpose: do not turn it into `Promise.all`. A second failed re-emit is recorded as `stopped: "invalid envelope"` through a second pass with the envelope omitted and `n` incremented; do not ask a third time. A stage that stopped `awaiting_decision` needs the `decision` report at once (`SendMessage` to the session the report names), never an answer from the orchestrator.

## R7: post-run Steps 0 to 3

Loads the result, records phase `notified`, sweeps the seats, verifies, audits notes and builds and records provenance. It never advances: Step 3's notes come first, R8 sends the Step 4 advance. It returns findings only: `failing` is the shorts that failed verify (`ok: false`) or the notes audit (also saved as `failing.json`, which R8 reads), `paused` is true when the phase update answered `run_paused` (the answer is in `paused.json`: send the `paused` report with R10), and `decisionIds` lists the `decisionId`s the result carries (send the `decision` report). Step 3 item 2: it runs `dispatch-meta` and `provenance`, writes `dispatch-meta.json`, `provenance.json` and `verify.json` into the run folder, then writes each item's provenance line to its `delegation-metadata` note under the run token with `expectedVersion: 0` (a `version_conflict` is reported as `present` and the note is never overwritten) where the item's schema declares that key; an item whose schema does not declare it keeps its line in `provenance.json` for the report (post-run.md Step 3). The run-level layer line goes token-less to the non-member target parent when that parent declares `delegation-metadata`, replacing the line that carries the same `run=<runId>`; otherwise it stays in `provenance.json` as well. `unownedDocs` lists the `docs sweep unowned` warnings of verify: R9 refuses to dispatch while they have no owner. For Method A, the notification's result goes to `result.json` first: paste the result object as a literal like R6; when the notification is cut short, set `OUTPUT_FILE` to the task output file it names (or rely on the `state.taskOutputFile` R4 recorded) and the script saves that file's `result` member. For Method B the script saves the state document itself, and the helper converts it when it reads `result.json`. Step 3 item 1 (`session-tracking`) is written by hand and stays prose.

```js
async function r7() {
  const RESULT_LITERAL = null; // Method A: the pasted result object
  const OUTPUT_FILE = null; // Method A, notification cut short: the task output file path (pretty-printed JSON)
  const COORDINATOR_LABEL = null; // tier 2 and 2b: the label the director dispatched this orchestrator under
  const state = await readJson(RUN_DIR + '/state.json');
  const plan = await readJson(RUN_DIR + '/plan.json');
  const CT = await readToken(RUN_DIR + '/tokens/coordinator.token');
  const serverRunId = plan.args.serverRunId;
  const method = (plan.meta && plan.meta.method) || state.method || 'A';
  let result = RESULT_LITERAL;
  const outFile = OUTPUT_FILE || state.taskOutputFile;
  if (!result && outFile) result = JSON.parse(await file(outFile).text()).result;
  if (!result && ((plan.meta && plan.meta.method === 'B') || state.method === 'B')) result = state; // Method B: the state document (run-wave/state-v1) is the result
  if (!result) return { needs: 'result', why: 'Method A: paste the notification result as RESULT_LITERAL, or set OUTPUT_FILE' };
  await writeJson(RUN_DIR + '/result.json', result);
  const decisionIds = [];
  const walk = (v, depth) => {
    if (v === null || typeof v !== 'object' || depth > 8) return;
    for (const k of Object.keys(v)) {
      if (k === 'decisionId' && typeof v[k] === 'string' && !decisionIds.includes(v[k])) decisionIds.push(v[k]);
      else walk(v[k], depth + 1);
    }
  };
  walk(result, 0);

  // Step 0: record the phase; a paused run answers run_paused and the post-run work goes on
  let paused = false;
  const ph = await setPhase(plan, 'notified');
  if (!ph.ok) {
    if (ph.r.refused) return ph.r.refused;
    if (elementCode(ph.r) !== 'run_paused') return await stop(ph.r);
    await writeJson(RUN_DIR + '/paused.json', { code: 'run_paused', detail: (failedElement(ph.r) || ph.r).detail || {} });
    paused = true;
  }

  // Step 0b
  const swept = await endSeats(plan);
  if (swept.stop) return swept.stop;
  const ended = swept.ended;

  const ver = await helper('verify', '--plan ' + q(RUN_DIR + '/plan.json') + ' --result ' + q(RUN_DIR + '/result.json'));
  if (ver.ref) return ver.ref;
  const observed = [];
  for (const item of plan.args.items) {
    const keys = [].concat(...(item.stages || []).map((s) => s.notes || []));
    const l = await call('notes_read', { select: 'list', itemId: item.id, keys });
    if (l.ok) for (const n of l.data.items) observed.push({ itemId: item.id, key: n.key });
  }
  await writeJson(RUN_DIR + '/observed-notes.json', observed);
  const audit = await helper('notes-audit', '--plan ' + q(RUN_DIR + '/plan.json') + ' --notes ' + q(RUN_DIR + '/observed-notes.json') + ' --result ' + q(RUN_DIR + '/result.json'));
  if (audit.ref) return audit.ref;
  // verify and notes-audit exit 3 on findings and still print their JSON; no JSON at all is a helper failure, never a pass
  if (!ver.json) return { failed: true, step: 'verify', code: ver.code, err: ver.err };
  if (!audit.json) return { failed: true, step: 'notes-audit', code: audit.code, err: audit.err };
  await writeJson(RUN_DIR + '/verify.json', ver.json);

  // failing: a verify item with ok false, or an audit item with ok false (post-run Steps 1 and 2)
  const failing = [];
  for (const i of ver.json.items || []) if (i.ok === false && !failing.includes(i.short)) failing.push(i.short);
  for (const i of audit.json.items || []) if (i.ok === false && !failing.includes(i.short)) failing.push(i.short);
  await writeJson(RUN_DIR + '/failing.json', { failing });
  const unownedDocs = (ver.json.warnings || []).filter((w) => /docs sweep unowned/.test(w));

  // provenance (Step 3 item 2): the helper builds the lines; the script writes them and returns only where each one went, never the text
  const run = await call('runs_read', { select: 'get', runId: serverRunId });
  const runDoc = run.ok ? (run.data.run || run.data) : {};
  const openedAt = typeof runDoc.openedAt === 'string' && !Number.isNaN(Date.parse(runDoc.openedAt)) ? runDoc.openedAt : null;
  const dm = await helper('dispatch-meta', '--plan ' + q(RUN_DIR + '/plan.json') + (openedAt ? ' --since ' + Date.parse(openedAt) : ''));
  const provenance = { code: dm.code, items: [], layer: null };
  let lines = {};
  if (dm.json) {
    await writeJson(RUN_DIR + '/dispatch-meta.json', dm.json);
    const transcript = Object.values(dm.json.meta || {}).map((m) => m && m.transcript).find((x) => typeof x === 'string' && x.replace(/\\/g, '/').includes('/subagents/'));
    const sessionFile = transcript ? transcript.replace(/\\/g, '/').split('/subagents/')[0] + '.jsonl' : null;
    const prov = await helper('provenance', '--plan ' + q(RUN_DIR + '/plan.json') + ' --result ' + q(RUN_DIR + '/result.json') + ' --method ' + method + ' --turns ' + (state.turns || 1) + ' --meta ' + q(RUN_DIR + '/dispatch-meta.json')
      + (COORDINATOR_LABEL ? ' --coordinator ' + q(COORDINATOR_LABEL) : '')
      + (sessionFile ? ' --session-transcript ' + q(sessionFile) + (openedAt ? ' --session-since ' + q(openedAt) : '') : ''));
    provenance.code = prov.code;
    if (prov.json) {
      lines = prov.json;
      await writeJson(RUN_DIR + '/provenance.json', prov.json);
    }
  }
  const declares = async (itemId) => {
    const s = await call('items_read', { select: 'schema', itemId });
    const n = s.ok ? (s.data.notes || []).find((x) => x.key === 'delegation-metadata') : null;
    return n ? { role: n.role || 'work' } : null;
  };
  const written = { items: 0, present: 0, failed: 0, layer: 'none' };
  for (const item of plan.args.items) {
    const d = await declares(item.id);
    provenance.items.push({ short: item.short, target: d ? 'delegation-metadata' : 'report' });
    if (!d || typeof lines[item.id] !== 'string') continue;
    const w = await call('notes_write', { ops: [{ op: 'upsert', itemId: item.id, key: 'delegation-metadata', role: d.role, body: lines[item.id], expectedVersion: 0 }], seatToken: CT });
    if (w.refused) return w.refused;
    const code = elementCode(w);
    if (code === null) written.items += 1;
    else if (code === 'version_conflict') written.present += 1;
    else written.failed += 1;
  }
  const scopeId = plan.meta && plan.meta.scopeId;
  if (scopeId) {
    const member = plan.args.items.some((i) => i.id === scopeId || i.id.startsWith(scopeId));
    const d = member ? null : await declares(scopeId);
    provenance.layer = d ? 'parent' : 'report';
    if (d && typeof lines['layer-meters'] === 'string') {
      // the parent keeps one layer line per run id: the line carrying this run id is replaced, the others stay
      const g = await call('notes_read', { select: 'get', itemId: scopeId, key: 'delegation-metadata' });
      if (g.refused) return g.refused;
      if (!g.ok && g.code !== 'not_found') return await stop(g);
      const body = g.ok ? String(g.data.body || '') : '';
      const mine = (l) => l.split(' ').includes('run=' + plan.args.runId);
      const keep = body.split('\n').filter((l) => l.trim() !== '' && !mine(l));
      const replaced = keep.length !== body.split('\n').filter((l) => l.trim() !== '').length;
      keep.push(lines['layer-meters']);
      const w = await call('notes_write', { ops: [{ op: 'upsert', itemId: scopeId, key: 'delegation-metadata', role: d.role, body: keep.join('\n'), expectedVersion: g.ok ? g.data.version : 0 }] });
      if (w.refused) return w.refused;
      const code = elementCode(w);
      written.layer = code === null ? (replaced ? 'replaced' : 'written') : 'failed:' + code;
    }
  }
  return { ended, verifyExit: ver.code, failing, auditExit: audit.code, auditMissing: audit.json.items, provenance, written, unownedDocs, paused, decisionIds };
}
await r7()
```

Send the `paused` report (R10) when `paused` is true and the `decision` report when `decisionIds` is not empty. Then run R8 for Step 4. Step 3 item 1 and the orchestrator's other notes stay as `post-run.md` says; this recipe covers Steps 0 to 3 item 2.

## R8: post-run Step 4 batched advance

Sends the one Step 4 `advance`, after the Step 3 notes. `HOLD` lists the shorts of items the orchestrator found live-owed while writing `session-tracking`; they stay in work and are left out of the call, as is every item in `failing.json`. As a safety net the script reads each remaining item's `session-tracking`: a `**Live-owed**:` line (entries separated by `;` or a line break) with an entry that ends neither `run <date>: <result>` nor `deferred: <item id>` is an open entry, and so is an item with no such line whose spec note (`task-scope`, `diagnosis` or `specification`) has a State matrix with a `live-owed:` cell (rule `live-checks`); the script then returns `{needs: 'hold', items}` and sends no advance (add those shorts to `HOLD` and run it again). A multi-element `advance` that answers `partial_failure` applied the elements that passed, but its error tail keeps only the failing entries, each with its `index` (at most 10), and `summary {ok, failed}`: the script maps each entry to its op by `index`, never by position, treats an op with no entry as passed, and never claims a passed element's answer, which the tail does not carry. When `summary.failed` is larger than the entries listed (the cap cut some), it reads each op without an entry back with `items_read` (a failed transition leaves the item in `work`). The trigger is `start` for an item whose schema has a review-role note (the rule `plan` uses for its review phase) and `complete` for one without. The answer is saved as `advance.json` for the `follow-ups` helper, the per-item result as `advanced-items.json` (R9 reads it), and the run records phase `post-run`.

```js
async function r8() {
  const HOLD = []; // shorts of items held live-owed: left out of the advance and reported
  const plan = await readJson(RUN_DIR + '/plan.json');
  const CT = await readToken(RUN_DIR + '/tokens/coordinator.token');
  const ff = file(RUN_DIR + '/failing.json');
  const failing = (await ff.exists()) ? (await readJson(RUN_DIR + '/failing.json')).failing : [];
  const rest = plan.args.items.filter((i) => !failing.some((f) => i.id.startsWith(f)));
  const held = rest.filter((i) => HOLD.includes(i.short)).map((i) => i.short);
  const go = rest.filter((i) => !HOLD.includes(i.short));
  const open = [];
  for (const item of go) {
    const g = await call('notes_read', { select: 'get', itemId: item.id, key: 'session-tracking' });
    if (g.refused) return g.refused;
    if (!g.ok && g.code !== 'not_found') return await stop(g);
    // the Live-owed line and its indented continuation lines; an entry is what sits between `;` or a line break
    const m = /\*\*Live-owed\*\*:([^\n]*(?:\n[ \t]+[^\n]*)*)/.exec(g.ok ? String(g.data.body || '') : '');
    if (m) {
      const entries = m[1].split(/;|\n/).map((e) => e.trim()).filter((e) => e !== '' && !/^(none|n\/a)\b/i.test(e));
      if (entries.some((e) => !/\brun \d{4}-\d{2}-\d{2}:/.test(e) && !/\bdeferred:/.test(e))) open.push(item.short);
      continue;
    }
    // live-checks: a State matrix with `live-owed:` cells and no Live-owed line counts as an open entry
    for (const key of ['task-scope', 'diagnosis', 'specification']) {
      const sp = await call('notes_read', { select: 'get', itemId: item.id, key });
      if (sp.refused) return sp.refused;
      if (!sp.ok && sp.code !== 'not_found') return await stop(sp);
      const body = sp.ok ? String(sp.data.body || '') : '';
      const at = body.search(/State matrix/i);
      if (at !== -1 && /\blive-owed:/i.test(body.slice(at))) { open.push(item.short); break; }
    }
  }
  if (open.length) return { needs: 'hold', items: open };
  const ops = [];
  for (const item of go) {
    const s = await call('items_read', { select: 'schema', itemId: item.id });
    if (!s.ok) return await stop(s);
    const review = (s.data.notes || []).some((n) => n.role === 'review');
    ops.push({ op: 'transition', itemId: item.id, trigger: review ? 'start' : 'complete' });
  }
  if (ops.length === 0) return { advance: 'none', held, failing }; // every item failed Step 1 or 2 or is held: no advance call
  const advanced = await call('advance', { ops, seatToken: CT });
  // a multi-element advance answers partial_failure when an element fails and applies the elements that passed, but its tail keeps only the failing entries
  // (each with its index, at most 10) and summary {ok, failed}: a passed element's answer is not in it, so map entries by index, never by position
  const partial = !advanced.ok && advanced.code === 'partial_failure' && Boolean(advanced.data) && Array.isArray(advanced.data.results);
  if (!advanced.ok && !partial) return { advance: await stop(advanced), held, failing };
  await writeJson(RUN_DIR + '/advance.json', advanced.data);
  const entries = partial ? advanced.data.results : [];
  const byIndex = new Map(entries.filter((e) => e && Number.isInteger(e.index)).map((e) => [e.index, e]));
  // failures the 10-entry cap cut off: an op with no entry may be one of them, so its role is read back (a failed transition leaves the item in work)
  const unlisted = partial ? Math.max(0, ((advanced.data.summary && advanced.data.summary.failed) || 0) - entries.length) : 0;
  const answers = advanced.ok ? advanced.data.results || [] : [];
  const items = [];
  for (let i = 0; i < ops.length; i += 1) {
    const op = ops[i];
    const el = partial ? byIndex.get(i) : answers[i];
    let ok = !el || el.ok !== false;
    let code = el && el.ok === false ? (el.error && el.error.code) || 'failed' : undefined;
    if (partial && !el && unlisted > 0) {
      const g = await call('items_read', { select: 'get', itemId: op.itemId });
      if (g.refused) return g.refused;
      if (!g.ok) return await stop(g);
      ok = g.data.role !== 'work';
      code = ok ? undefined : 'failed';
    }
    items.push({ short: op.itemId.slice(0, 8), itemId: op.itemId, trigger: op.trigger, ok, code });
  }
  await writeJson(RUN_DIR + '/advanced-items.json', items);
  const fu = await helper('follow-ups', '--in ' + q(RUN_DIR + '/advance.json'));
  const ph = await setPhase(plan, 'post-run');
  let paused = false;
  if (!ph.ok) {
    if (ph.r.refused) return ph.r.refused;
    if (elementCode(ph.r) !== 'run_paused') return await stop(ph.r);
    await writeJson(RUN_DIR + '/paused.json', { code: 'run_paused', detail: (failedElement(ph.r) || ph.r).detail || {} });
    paused = true;
  }
  return { advanced: items.map((x) => ({ short: x.short, trigger: x.trigger, ok: x.ok, code: x.code })), held, failing, followUps: fu.json, followUpsComplete: !partial, paused };
}
await r8()
```

An element error is per element (`code` in `advanced`): `run_paused` stops the step (the `paused` report, R10) and anything else is reported for that item. `followUps` carries `{unblocked, seatBlocked, gateBlocked, reviewPending, gateMissing}` as `post-run.md` Step 4 describes. After a `partial_failure` it holds nothing of the passed items (`followUpsComplete` is false): their cascades are not in the tail, so read the parents and the newly unblocked items back with `items_read` instead of taking an empty `followUps` for none. Held items are reported live-owed and are the post-merge steps' concern once the PR merges.

## R9: post-run Step 5 generic review dispatch

For the generic review (`meta.review.mode` not `handoff`). A `handoff` mode returns `{handoff}` and dispatches nothing: the hand-off to the project's review step stays prose. Any `docs sweep unowned` hit in `verify.json` must have an owner first (a sibling item's amendment or a follow-up item, `post-run.md` Step 5): while `UNOWNED_ROUTED` is false the script returns `{needs: 'route-unowned'}` and dispatches nothing. Otherwise it records phase `review`, then for each item R8 advanced with `start` it assigns the reviewer seat, writes the token file, renders `review-prompt` with `--result` (raw text) and dispatches the reviewer with the `review` class model and effort of `dispatch-policy.json`, read at run time, and the schema's `dispatch.review.agent` (else `general-purpose`). A `run_at_capacity` answer holds that reviewer back (`waiting`): wait for a reviewer to end its seat, then run R9 again for the rest. The shorts it dispatched are saved in `reviewers.json` (`already` returns their count), and a rerun skips them: it never re-assigns a reviewer that was dispatched, since an assign supersedes the running reviewer's token. Never pass `isolation`.

```js
async function r9() {
  const UNOWNED_ROUTED = false; // true once every `docs sweep unowned` hit has an owner
  const plan = await readJson(RUN_DIR + '/plan.json');
  const CT = await readToken(RUN_DIR + '/tokens/coordinator.token');
  const review = (plan.meta && plan.meta.review) || { mode: 'generic' };
  if (review.mode === 'handoff') return { handoff: { skill: review.skill, step: review.step || null, onFail: review.onFail || null } };
  const vf = file(RUN_DIR + '/verify.json');
  const unowned = (await vf.exists()) ? ((await readJson(RUN_DIR + '/verify.json')).warnings || []).filter((w) => /docs sweep unowned/.test(w)) : [];
  if (unowned.length && !UNOWNED_ROUTED) return { needs: 'route-unowned', unowned };
  const ph = await setPhase(plan, 'review');
  if (!ph.ok) return await stop(ph.r);
  // reviewers.json lists the shorts a run of this script already assigned and dispatched: a rerun skips them, so a running reviewer's token is never superseded
  const rf = file(RUN_DIR + '/reviewers.json');
  const already = (await rf.exists()) ? await readJson(RUN_DIR + '/reviewers.json') : [];
  const sent = (await readJson(RUN_DIR + '/advanced-items.json')).filter((x) => x.ok && x.trigger === 'start' && !already.includes(x.short));
  const policyFile = HELPER.replace(/scripts[\\/]run-planner\.mjs$/, 'dispatch-policy.json');
  const policy = JSON.parse(await file(policyFile).text());
  const cls = policy.classes.review;
  const dispatched = [];
  const waiting = [];
  for (const x of sent) {
    const item = plan.args.items.find((i) => i.id === x.itemId);
    const a = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase assign --item ' + item.short + ' --seat ' + (item.reviewSeats || ['reviewer'])[0]);
    if (a.ref) return a.ref;
    if (a.code !== 0 || !a.json) { dispatched.push({ item: item.short, failed: 'assign' }); continue; }
    const as = await call('runs_write', { ops: a.json.ops, seatToken: CT });
    if (!as.ok) {
      if (elementCode(as) === 'run_at_capacity') { waiting.push(item.short); continue; } // a one-op write answers partial_failure: read the element's code
      return await stop(as);
    }
    if (a.json.tokenFiles[0]) await writeToken(a.json.tokenFiles[0], as.data.results[0].seatToken);
    const p = await helper('review-prompt', '--plan ' + q(RUN_DIR + '/plan.json') + ' --item ' + item.short + ' --result ' + q(RUN_DIR + '/result.json'));
    if (p.code !== 0 || !p.raw) { dispatched.push({ item: item.short, failed: 'review-prompt' }); continue; }
    const s = await call('items_read', { select: 'schema', itemId: item.id });
    const agentType = s.ok && s.data.dispatch && s.data.dispatch.review && s.data.dispatch.review.agent ? s.data.dispatch.review.agent : 'general-purpose';
    const r = await Agent({ prompt: p.raw, model: cls.model, effort: cls.effort, description: 'reviewer:' + item.short, subagent_type: agentType });
    dispatched.push({ item: item.short, agentId: r && (r.agentId || r.agent_id) });
    already.push(x.short);
    await writeJson(RUN_DIR + '/reviewers.json', already);
  }
  return { dispatched, waiting, already: already.length };
}
await r9()
```

The review-wave launch, the fix cycle after a Fail, the wave parent review and the Step 6 human checkpoints stay prose (a Code Mode orchestrator has no `Workflow` tool). Run Step 0b's sweep again before closing: R11 does it.

## R10: orchestrator report

Sends one orchestrator report: the `report` helper prints the block, `SendMessage` carries it to `REPORT_TO` (`main`; with `REPORT_TO` null the block is returned for the user). The kit's `sendReport` reads the files its event needs from the run folder (`planned`: none; `decision`: `result.json` and the run view; `paused`: the run view and `paused.json`; `closed`: `result.json` and `close.json`) and never passes `--needs`: the helper builds the `needs:` line itself from the plan (`SKILL.md` Step 0 F6). The return is only `event`, `sent`, `needs` and `wait`; `wait` is true for `decision` and `paused`, and for `planned` when `needs` is not `nothing`: the orchestrator then waits for the director's message before anything else (for `planned`, `approved` goes on to R5 and `stop` goes to R11 with `REASON = 'cancelled'`).

```js
async function r10() {
  const EVENT = 'planned'; // planned | decision | paused | closed
  return await sendReport(EVENT, {});
}
await r10()
```

## R11: post-run Step 7 close

Runs the Step 0b sweep, writes `changed-files.txt` (`git -C <worktree> diff --name-only <baseSha>..HEAD` of each worktree) and selects the declared post-run steps with `post-run-steps`. When steps come back and `STEPS_DONE` is false the script returns `{steps: [{id, rule}], skipped}` and closes nothing: run each step per the rule it names (`config` `op` `rules`), set `STEPS_DONE` true and run it again. Otherwise it renders `run-ops --phase close` and sends it as `call('runs_write', ...)`, the MCP tool global under the run token, never over REST: the retrospective trigger is a hook on that tool call, so a REST close is invisible to it. With `REASON` `'cancelled'` (a stop before launch, nothing ran) it closes without returning the declared steps. `invalid_run_state` means a seat is still active: the script sweeps again and closes once more. It saves the answer as `close.json` and sends the `closed` report through the kit's `sendReport`. `REASON` is `null` for the usual close (`completed` or `failed` from the result), `'cancelled'` for a director stop and `'abandoned'` for a Resume re-plan of an unrecoverable run.

```js
async function r11() {
  const STEPS_DONE = false; // true after the returned steps have been run
  const REASON = null; // 'cancelled' (director stop) or 'abandoned' (unrecoverable run), else null
  const plan = await readJson(RUN_DIR + '/plan.json');
  const CT = await readToken(RUN_DIR + '/tokens/coordinator.token');
  const swept = await endSeats(plan);
  if (swept.stop) return swept.stop;
  const changed = [];
  const seenTrees = new Set();
  for (const item of plan.args.items) {
    if (seenTrees.has(item.worktree)) continue;
    seenTrees.add(item.worktree);
    const r = await sh('git -C ' + q(item.worktree) + ' diff --name-only ' + plan.args.baseSha + '..HEAD');
    if (r.ref) return r.ref;
    if (r.code === 0) changed.push(...r.out.split('\n').map((l) => l.trim()).filter((l) => l !== ''));
  }
  await Write({ file_path: RUN_DIR + '/changed-files.txt', content: changed.join('\n') + '\n' });
  const ps = await helper('post-run-steps', '--plan ' + q(RUN_DIR + '/plan.json') + ' --when post-run --files-from ' + q(RUN_DIR + '/changed-files.txt'));
  if (ps.ref) return ps.ref;
  if (ps.code !== 0 || !ps.json) return { failed: true, step: 'post-run-steps', code: ps.code, err: ps.err };
  if ((ps.json.steps || []).length && !STEPS_DONE && REASON !== 'cancelled') return { steps: ps.json.steps.map((s) => ({ id: s.step, rule: s.rule })), skipped: ps.json.skipped || [] };
  const withResult = (await file(RUN_DIR + '/result.json').exists()) ? ' --result ' + q(RUN_DIR + '/result.json') : '';
  const cl = await helper('run-ops', '--plan ' + q(RUN_DIR + '/plan.json') + ' --phase close' + withResult + (REASON ? ' --reason ' + REASON : ''));
  if (cl.ref) return cl.ref;
  if (cl.code !== 0 || !cl.json) return { failed: true, step: 'run-ops close', code: cl.code, err: cl.err };
  let closed = await call('runs_write', { ops: cl.json.ops, seatToken: CT });
  if (closed.refused) return closed.refused;
  if (elementCode(closed) === 'invalid_run_state') {
    const again = await endSeats(plan);
    if (again.stop) return again.stop;
    closed = await call('runs_write', { ops: cl.json.ops, seatToken: CT });
    if (closed.refused) return closed.refused;
  }
  const code = elementCode(closed);
  if (code !== null) return code === 'run_paused' ? await stop(closed) : { failed: true, step: 'close', code };
  await writeJson(RUN_DIR + '/close.json', closed.data);
  const del = await sh('node -e ' + q("require('fs').rmSync(process.argv[1],{recursive:true,force:true})") + ' ' + q(RUN_DIR + '/tokens'));
  const tokensLeft = del.ref !== null || del.code !== 0; // a failed delete is reported, never a failed close: the run is closed and its tokens are dead
  const snap = (closed.data.results && closed.data.results[0] && closed.data.results[0].snapshot) || {};
  const rep = await sendReport('closed', {});
  return { closed: true, reason: cl.json.ops[0].reason, openDecisions: Number.isInteger(snap.openDecisions) ? snap.openDecisions : null, reported: rep.sent === true, ...(tokensLeft ? { tokensLeft: true } : {}) };
}
await r11()
```

After a successful close the script deletes `<scratchpad>/run-wave/<runId>/tokens/` itself (a refused close leaves it). When the delete fails the return carries `tokensLeft: true` and the close still stands: delete that folder by hand. Run the rest of Step 7 (the run report, deferred and stopped items, cleanup of `refs/wip/<short>`) by hand as `post-run.md` says.

## Coverage

Every step of `SKILL.md`, `method-b.md` and `post-run.md` has a recipe or is prose. A `prose` row has no script: the model does it from the step's own text (judgment, a human checkpoint, a tool a Code Mode orchestrator lacks, or a one-off write).

| Step | Recipe |
|---|---|
| SKILL F0 helper resolvable | R1 |
| SKILL F1 Workflow callable | prose |
| SKILL F2 server features | R1 |
| SKILL F3 protocol rules | R1 |
| SKILL F4 phase 0 hooks | R1 |
| SKILL F5 size guideline | prose |
| SKILL F6 allow rules | R10 |
| SKILL F7 open run exists | R1 |
| SKILL F8 seat skills | prose |
| SKILL Step 1 scope | prose |
| SKILL Step 2 snapshot | R1 |
| SKILL Step 2 awaiting_decision resume | prose |
| SKILL Step 3 plan and explain | R1 |
| SKILL Step 3 lateCarryIns and Checkpoint 1 | prose |
| SKILL Step 4 worktrees | R2 |
| SKILL Step 5 open, parent check and pointer | R2 |
| SKILL Step 5a pre-entered start | R2 |
| SKILL Step 5a Method A assign | R2 |
| SKILL Step 5a Method A adopt-notes | R3 |
| SKILL Step 5a planned report | R10 |
| SKILL Step 6a Method A launch | R4 |
| SKILL Step 6b Method B launch | R5 |
| SKILL Step 7 post-run | R7 |
| SKILL Step 8 review | R9 |
| SKILL Step 8a post-merge steps | prose |
| SKILL Step 8b feature closure | prose |
| SKILL Step 9 loop | prose |
| SKILL Stalled seats | prose |
| SKILL Resume matrix | prose |
| SKILL Retrying a stopped seat | prose |
| method-b 0 record launched | R5 |
| method-b 1 schedule, assign, adopt, dispatch | R5 |
| method-b 2 respect ordering | R5 |
| method-b 3 collect results | R6 |
| method-b 4 scan declarations | prose |
| method-b 4a missingDeclaration stop | prose |
| method-b 5 persist | R6 |
| method-b 5a resume after interruption | prose |
| method-b 6 repeat | R5 |
| post-run Step 0 load the result | R7 |
| post-run Step 0b end the seats | R7, R11 |
| post-run Step 1 verify | R7 |
| post-run Step 2 notes audit | R7 |
| post-run Step 3 item 1 session-tracking | prose |
| post-run Step 3 item 2 delegation-metadata | R7 |
| post-run Step 4 batched advance | R8 |
| post-run Step 5 generic review dispatch | R9 |
| post-run Step 5 handoff | prose |
| post-run Step 5 review-wave launch | prose |
| post-run Step 5 fix cycle | prose |
| post-run Step 5 wave parent review | prose |
| post-run Step 6 human checkpoints | prose |
| post-run Step 7 close | R11 |
| post-run Step 7 run report and cleanup | prose |
| post-run Step 7a post-merge steps | prose |
| post-run Step 7b feature closure | prose |

The reports (`planned`, `decision`, `paused`, `closed`) all go through R10; R11 sends `closed` itself.
