#!/usr/bin/env node
// run-planner.mjs — CLI dispatcher for the run-wave front door's planner helper.
//
// Subcommands: probe | plan | explain | validate | bind-run | snapshot | retro-metrics | bug-report. Input via --in <file> or stdin; JSON on
// stdout; {error, detail} on stderr. Exit 0 ok, 2 invalid input, 3 refused (stdout still
// carries the doc or {ok:false, errors}). A global `--out <path> [--force]` (any subcommand) writes the
// document to <path> instead of stdout, creating any missing parent folders; it refuses (exit 3) to overwrite a launched run's existing file
// (a sibling state.json past 'planned', or a bound plan.json) unless --force. A shell redirect
// (`> plan.json`, as the run-wave SKILL.md save still does) bypasses the guard. This file is the ONLY place that reads the clock,
// the filesystem, the network (probe's server check) or environment variables —
// run-planner-lib.mjs stays pure.
//
// run-exec-lib.mjs adds rows to the subcommand table below: next, prompt, stage-result,
// scan-declarations, verify, notes-audit, dispatch-meta, provenance, session-meters, review-prompt, reseat, run-ops, follow-ups, report, review-args, post-run-steps.
// provenance also takes the per-run layer flags --coordinator <label> (with --session-dir |
// --projects-dir), --session-transcript <path>, --session-since <t> and --session-until <t>, which add
// the 'layer-meters' member to its output.
//
// bug-report spawns gh. The env var TASK_ORCHESTRATOR_GH_SHIM, when non-empty, names a script node runs instead
// (argv = the gh arguments); it exists for the retrospective evals (evals/retrospective/fake-gh.mjs) and the bug-report tests.

import { readFileSync, writeFileSync, existsSync, readdirSync, statSync, mkdirSync, mkdtempSync, rmSync } from 'node:fs';
import { homedir, tmpdir, userInfo, hostname } from 'node:os';
import { spawnSync } from 'node:child_process';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as lib from './run-planner-lib.mjs';
import * as exec from './run-exec-lib.mjs';
import { loadWaveCore } from './lib/wave-core.mjs';
import { checkServer } from '../hooks/lib/server-compat.mjs';
import { apiBaseUrl, callOp, wellKnown } from '../hooks/lib/api-client.mjs';
import { locateConfig, userHome } from '../hooks/config-locator.mjs';
import { runBugReport, readGate, fingerprintOf } from './lib/bug-report.mjs';
import { buildSnapshot, snapshotArgErrors } from './lib/snapshot-builder.mjs';
import { resolveOrchestratorEntryKey } from '../hooks/lib/mcp-registrations.mjs';
import { readSection, scalar } from '../hooks/yaml-lite.mjs';
import { retroMetrics, parseBaselines, CONTRACT as RETRO_METRICS_CONTRACT } from './lib/retro-metrics.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

function readStdin() {
  try {
    return readFileSync(0, 'utf8');
  } catch {
    return '';
  }
}

function flagValue(args, name, def) {
  const idx = args.indexOf(name);
  if (idx !== -1 && args[idx + 1] !== undefined) return args[idx + 1];
  return def;
}

function readInput(args) {
  const path = flagValue(args, '--in', null);
  if (path) return readFileSync(path, 'utf8');
  return readStdin();
}

function safeRead(path) {
  try {
    return readFileSync(path, 'utf8');
  } catch {
    return '';
  }
}

function homeDir() {
  return process.env.USERPROFILE || process.env.HOME || '';
}

// OUT: the global --out/--force pair main() strips before dispatch; null means plain stdout.
let OUT = null;

function fail(code, message, detail) {
  process.stderr.write(`${JSON.stringify({ error: message, detail: detail ?? null })}\n`);
  process.exit(code);
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

// emit: the one writer of a subcommand's stdout document. Without --out it is stdout, as ever.
// With --out it consults lib.artifactWriteGuard (target + sibling state.json) before writing the
// file (creating any missing parent folders once the guard passes), and prints nothing on stdout.
function emit(text) {
  const doc = text.endsWith('\n') ? text : `${text}\n`;
  if (!OUT) {
    process.stdout.write(doc);
    return;
  }
  const target = resolve(OUT.path);
  const targetExists = existsSync(target);
  const statePath = join(dirname(target), 'state.json');
  const guard = lib.artifactWriteGuard({
    targetExists,
    existingText: targetExists ? safeRead(target) : '',
    stateText: existsSync(statePath) ? safeRead(statePath) : '',
    force: OUT.force
  });
  if (!guard.ok) return fail(3, 'refused', `${resolve(OUT.path)} ${guard.error.replace(/^target /, "")}`);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, doc);
}

function resolvePluginRoot(args) {
  const explicit = flagValue(args, '--plugin-root', null);
  if (explicit) return resolve(explicit);
  return resolve(HERE, '..');
}

// serverCompat: null when no REST API URL resolves (or --no-server-check), when the server is 4.x
// with seats, or when it cannot be reached; else the server-compat warning line. --api-url overrides
// the resolved base URL.
async function probeServerCompat(args, cwd) {
  if (args.includes('--no-server-check')) return null;
  const base = flagValue(args, '--api-url', null) || apiBaseUrl({ cwd });
  if (!base) return null;
  return checkServer({ baseUrl: base });
}

async function probeResult(args) {
  const cwd = resolve(flagValue(args, '--cwd', process.cwd()));
  const pluginRoot = resolvePluginRoot(args);
  const pluginJsonText = safeRead(join(pluginRoot, '.claude-plugin', 'plugin.json'));
  const subagentStartText = safeRead(join(pluginRoot, 'hooks', 'subagent-start.mjs'));
  const phaseGuardText = safeRead(join(pluginRoot, 'hooks', 'phase-guard-record.mjs'));
  const local = safeRead(join(cwd, '.claude', 'settings.local.json'));
  const projectSettings = safeRead(join(cwd, '.claude', 'settings.json'));
  const user = safeRead(join(homeDir(), '.claude', 'settings.json'));
  const serverCompat = await probeServerCompat(args, cwd);
  const mcpServerKey = resolveOrchestratorEntryKey({ cwd, env: process.env });

  const result = lib.probeFrom({
    pluginRoot,
    pluginJsonText,
    subagentStartText,
    phaseGuardText,
    settings: [local, projectSettings, user],
    serverCompat,
    mcpServerKey
  });
  return result;
}

async function runProbe(args) {
  emit(JSON.stringify(await probeResult(args)));
  process.exit(0);
}

// snapshot: builds run-wave/snapshot-v1 over the resolved REST API (lib/snapshot-builder.mjs holds the logic;
// this function supplies its io). The snapshot goes to --out through emit, then one summary line goes to stdout.
// Exit 2: unusable arguments, no REST URL, a checkout or profile or --scopes file that cannot be read. Exit 3: a
// required read failed, the snapshot fails validation, or the --out guard refuses. Nothing is written on either.
async function runSnapshot(args) {
  // A flag whose next token starts with `--` has no value of its own (`--root --ancestor X` is not a root `--ancestor`).
  for (const name of ['--root', '--ancestor', '--items', '--scopes', '--cwd', '--api-url', '--plugin-root', '--now']) {
    const at = args.indexOf(name);
    if (at !== -1 && typeof args[at + 1] === 'string' && args[at + 1].startsWith('--')) {
      return fail(2, 'invalid args', `${name} needs a value, not ${args[at + 1]}`);
    }
  }
  const split = (v) => (v ? v.split(',').map((x) => x.trim()).filter(Boolean) : undefined);
  const input = {
    root: flagValue(args, '--root', undefined),
    ancestor: flagValue(args, '--ancestor', undefined),
    items: split(flagValue(args, '--items', undefined)),
    noFetch: args.includes('--no-fetch'),
    now: flagValue(args, '--now', null) || new Date().toISOString()
  };
  const scopesPath = flagValue(args, '--scopes', null);
  if (scopesPath) {
    const scopes = parseJson(safeRead(scopesPath));
    if (scopes === null || typeof scopes !== 'object' || Array.isArray(scopes)) {
      return fail(2, 'invalid args', `--scopes ${scopesPath} is not a JSON object file`);
    }
    input.scopes = scopes;
  }
  const argErrors = snapshotArgErrors({ ...input, hasOut: OUT !== null });
  if (argErrors.length) return fail(2, 'invalid args', argErrors.join('; '));
  const cwd = resolve(flagValue(args, '--cwd', process.cwd()));
  const baseUrl = flagValue(args, '--api-url', null) || apiBaseUrl({ cwd });
  if (!baseUrl) return fail(2, 'invalid args', 'no REST API URL resolves: assemble the snapshot by hand (run-wave SKILL.md Step 2)');

  const git = (gitArgs, timeout) => {
    const r = spawnSync('git', ['-C', cwd, ...gitArgs], {
      encoding: 'utf8',
      timeout,
      env: { ...process.env, GIT_TERMINAL_PROMPT: '0' }
    });
    return { status: r.status === null ? 1 : r.status, stdout: r.stdout || '' };
  };
  const io = {
    async call(tool, body) {
      return (await callOp(tool, body, { timeoutMs: 10000, baseUrl })) || { ok: false, error: { code: 'no_response' } };
    },
    wellKnown: () => wellKnown({ timeoutMs: 10000, baseUrl }),
    git: (gitArgs) => git(gitArgs, 30000),
    fetchOrigin: () => git(['fetch', 'origin'], 60000),
    readProfile(repoRoot) {
      const path = join(repoRoot, '.taskorchestrator', 'run-profile.json');
      if (!existsSync(path)) return { kind: 'absent' };
      const text = safeRead(path).replace(/^\uFEFF/, '');
      const value = parseJson(text);
      return value === null ? { kind: 'invalid' } : { kind: 'ok', value };
    },
    probe: () => probeResult(args)
  };
  const result = await buildSnapshot(input, io);
  if (!result.ok) return fail(result.exit, result.error, result.detail);
  emit(JSON.stringify(result.snapshot));
  const { candidates, needs, skipped, warnings } = result.summary;
  process.stdout.write(`${JSON.stringify({ ok: true, out: resolve(OUT.path), candidates, needs, skipped, warnings })}\n`);
  process.exit(0);
}

function runPlan(args) {
  const text = readInput(args);
  const snap = parseJson(text);
  if (!snap) return fail(2, 'invalid args', 'bad JSON input');

  // --now defaults to the CLI's own clock (never read inside run-planner-lib.mjs, which stays
  // pure) so a bare `plan` call still produces a real runId/startedAt that `validate` accepts.
  const now = flagValue(args, '--now', null) || new Date().toISOString();
  const profileMaxItems = snap && snap.profile && snap.profile.maxItems;
  const defaultMaxItems = Number.isFinite(profileMaxItems) && profileMaxItems > 0 ? String(profileMaxItems) : '5';
  const maxItemsRaw = flagValue(args, '--max-items', defaultMaxItems);
  const maxItems = Number(maxItemsRaw);
  if (!Number.isFinite(maxItems) || maxItems < 1) return fail(2, 'invalid args', 'max-items must be >= 1');

  const mode = flagValue(args, '--mode', 'auto');
  const entry = flagValue(args, '--entry', 'auto');
  const method = flagValue(args, '--method', undefined);
  const scratchpad = flagValue(args, '--scratchpad', undefined);
  // E3: --worktree/--branch (shared mode only, both-or-neither) reuse an already-created
  // feature worktree/branch instead of the planner deriving a second one. Validation (pairing,
  // shared-mode-only) lives in run-planner-lib.mjs's buildPlanDoc guardErrors, not here.
  const worktree = flagValue(args, '--worktree', undefined);
  const branch = flagValue(args, '--branch', undefined);
  // --altitude-safe: the run returns the scrubbed altitude-safe result (counts and pointers only),
  // for a director session that makes the Workflow call itself (coordinator tier 2).
  const altitudeSafe = args.includes('--altitude-safe');

  const result = lib.buildPlanDoc(snap, { now, maxItems, mode, entry, method, scratchpad, worktree, branch, altitudeSafe });

  if (result.ok) {
    emit(JSON.stringify(result.doc));
    process.exit(0);
  }
  if (result.doc) {
    emit(JSON.stringify(result.doc));
  } else {
    emit(JSON.stringify({ ok: false, errors: result.errors }));
  }
  if (result.refused) {
    process.stderr.write(`${JSON.stringify({ error: 'refused', detail: result.errors })}\n`);
    process.exit(3);
  }
  process.stderr.write(`${JSON.stringify({ error: 'invalid args', detail: result.errors })}\n`);
  process.exit(2);
}

function runExplain(args) {
  const text = readInput(args);
  const doc = parseJson(text);
  if (!doc) return fail(2, 'invalid args', 'bad JSON input');
  emit(lib.explainPlan(doc));
  process.exit(0);
}

function runBindRun(args) {
  const text = readInput(args);
  const doc = parseJson(text);
  if (!doc) return fail(2, 'invalid args', 'bad JSON input');
  let out;
  try {
    out = lib.bindRun(doc, flagValue(args, '--server-run', null));
  } catch (e) {
    return fail(2, 'invalid args', String((e && e.message) || e));
  }
  emit(JSON.stringify(out));
  process.exit(0);
}

function loadArgsSchema() {
  const p = join(HERE, 'tests', 'fixtures', 'run-planner', 'args-v1.schema.json');
  if (!existsSync(p)) return null;
  const text = safeRead(p);
  return parseJson(text);
}

function runValidate(args) {
  const text = readInput(args);
  const doc = parseJson(text);
  if (!doc) return fail(2, 'invalid args', 'bad JSON input');

  const snapshotPath = flagValue(args, '--snapshot', null);
  let snapshot;
  if (snapshotPath) {
    snapshot = parseJson(safeRead(snapshotPath)) || undefined;
  }

  const schema = loadArgsSchema();
  const result = lib.validatePlanDoc(doc, schema, snapshot);
  emit(JSON.stringify(result));
  process.exit(result.ok ? 0 : 3);
}

// walkFiles(dir, nameTest) -> [{path, mtimeMs}] for every file under dir (depth bound 8) whose name
// passes nameTest; an unreadable dir or a file that vanishes before its stat contributes nothing.
function walkFiles(dir, nameTest) {
  const out = [];
  const walk = (d, depth) => {
    if (depth > 8) return;
    let entries;
    try {
      entries = readdirSync(d, { withFileTypes: true });
    } catch {
      return;
    }
    for (const e of entries) {
      const p = join(d, e.name);
      if (e.isDirectory()) walk(p, depth + 1);
      else if (e.isFile() && nameTest(e.name)) {
        try {
          out.push({ path: p, mtimeMs: statSync(p).mtimeMs });
        } catch {
          // skipped: vanished between listing and stat
        }
      }
    }
  };
  walk(dir, 0);
  return out;
}

// ---------------------------------------------------------------------------------------
// EXEC_IO — the injected io object run-exec-lib.mjs's EXEC_COMMANDS handlers call instead of
// referencing fs/process/git directly. Built from this file's own existing helpers
// (readFileSync-backed readInput/safeRead/fail, stdout, spawnSync git).
// ---------------------------------------------------------------------------------------
const EXEC_IO = {
  readInput(path) {
    if (!path) return null;
    return parseJson(safeRead(path));
  },
  readText(path) {
    if (!path) return '';
    try {
      return readFileSync(path, 'utf8');
    } catch {
      return null;
    }
  },
  writeOut(text) {
    emit(text);
  },
  // warn(text): one {warning} JSON line on stderr; stdout and the exit code are unchanged.
  warn(text) {
    process.stderr.write(`${JSON.stringify({ warning: text })}\n`);
  },
  fail(code, message, detail) {
    fail(code, message, detail);
  },
  exit(code) {
    process.exit(code);
  },
  git(cwd, args) {
    const r = spawnSync('git', ['-C', cwd, ...args], { encoding: 'utf8' });
    return { status: r.status, stdout: r.stdout || '' };
  },
  gitBytes(cwd, args) {
    const r = spawnSync('git', ['-C', cwd, ...args], { encoding: 'buffer', maxBuffer: 64 * 1024 * 1024 });
    return { status: r.status, stdout: r.stdout ? new Uint8Array(r.stdout) : new Uint8Array(0) };
  },
  listDir(dir) {
    try {
      return readdirSync(dir);
    } catch {
      return [];
    }
  },
  // walkMeta(dir) -> [{path, mtimeMs}] for every '*.meta.json' under dir (bounded depth), the
  // files Claude Code writes beside subagent transcripts; an unreadable dir contributes nothing.
  walkMeta(dir) {
    return walkFiles(dir, (name) => name.endsWith('.meta.json'));
  },
  // walkTranscripts(dir) -> [{path, mtimeMs}] for every 'agent-*.jsonl' subagent transcript under
  // dir, by the same walker; a transcript with no meta file beside it is listed too.
  walkTranscripts(dir) {
    return walkFiles(dir, (name) => /^agent-.*\.jsonl$/.test(name));
  },
  isDir(path) {
    try {
      return statSync(path).isDirectory();
    } catch {
      return false;
    }
  },
  homeDir() {
    return homedir();
  },
  loadCore() {
    return loadWaveCore({ pluginRoot: resolve(HERE, '..') });
  }
};

// ---------------------------------------------------------------------------------------
// retro-metrics: the session-retrospective's numbers as one JSON document (lib/retro-metrics.mjs
// computes it; this handler gathers the input over REST, the session folder and the config file).
// retro-metrics [--root <id>] [--run <id>]... [--item <id>]... [--session-dir <dir>] [--config <path>]
//   [--since <ISO>] [--self-only]
// Always exit 0 with a 'run-wave/retro-metrics-v1' document; exit 2 only for bad arguments. With no
// REST API the document is {contract, measured:false, reason}; a read that fails marks its own section
// {measured:false, reason:<code>} (or lands in `gaps`) and the others continue.
// ---------------------------------------------------------------------------------------
const RETRO_VALUE_FLAGS = ['--root', '--run', '--item', '--session-dir', '--config', '--since'];
const RETRO_TIMEOUT_MS = 5000;
const RETRO_MAX_ITEMS = 50;
const DAY_MS = 24 * 60 * 60 * 1000;

function parseRetroArgs(args) {
  const out = { root: null, runs: [], items: [], sessionDir: null, config: null, since: null, selfOnly: false };
  for (let i = 0; i < args.length; i += 1) {
    const a = args[i];
    if (a === '--self-only') {
      out.selfOnly = true;
      continue;
    }
    if (!RETRO_VALUE_FLAGS.includes(a)) throw new Error(`invalid args: unknown argument ${a}`);
    const v = args[i + 1];
    if (v === undefined || v.startsWith('--')) throw new Error(`invalid args: ${a} needs a value`);
    i += 1;
    if (a === '--run') {
      if (!out.runs.includes(v)) out.runs.push(v);
    } else if (a === '--item') {
      if (!out.items.includes(v)) out.items.push(v);
    } else if (a === '--root') out.root = v;
    else if (a === '--session-dir') out.sessionDir = v;
    else if (a === '--config') out.config = v;
    else if (a === '--since') {
      if (Number.isNaN(Date.parse(v))) throw new Error('invalid args: --since must be an ISO 8601 instant');
      out.since = v;
    }
  }
  return out;
}

// readRetro(tool, body, gaps, section) -> the answer's data, or null (no REST, or an error: recorded in gaps unless tolerated).
async function readRetro(tool, body, gaps, section, tolerate = []) {
  const res = await callOp(tool, body, { timeoutMs: RETRO_TIMEOUT_MS });
  if (res === null || res === undefined) return null;
  if (res.ok) return res.data === undefined ? null : res.data;
  const code = (res.error && res.error.code) || 'unknown';
  if (!tolerate.includes(code)) gaps.push({ section, reason: code });
  return null;
}

function retroIdsOf(list) {
  const out = [];
  for (const e of Array.isArray(list) ? list : []) {
    const id = e && typeof e === 'object' ? e.itemId || e.id : null;
    if (typeof id === 'string' && id !== '') out.push(id);
  }
  return out;
}

// Reads every page of a report select, following page.next; {rows} or {error: <code>}.
async function retroReportRows(body) {
  const rows = [];
  let cursor;
  const seen = new Set();
  for (let page = 0; page < 20; page += 1) {
    const res = await callOp('report', cursor ? { ...body, cursor } : body, { timeoutMs: RETRO_TIMEOUT_MS });
    if (res === null || res === undefined) return { error: 'no_api' };
    if (!res.ok) return { error: (res.error && res.error.code) || 'unknown' };
    const data = res.data || {};
    rows.push(...(Array.isArray(data.items) ? data.items : []));
    const next = data.page && typeof data.page.next === 'string' ? data.page.next : null;
    if (!next || seen.has(next)) break;
    seen.add(next);
    cursor = next;
  }
  return { rows };
}

// wallMs of the transcripts of one label: last minus first usage-line timestamp, null when fewer than two.
function retroWallMs(sessionDir, label) {
  let lo = Infinity;
  let hi = -Infinity;
  const dir = String(sessionDir).replace(/[\\/]+$/, '');
  for (const entry of EXEC_IO.walkTranscripts(dir) || []) {
    const meta = EXEC_IO.readInput(entry.path.replace(/\.jsonl$/, '.meta.json'));
    if (!meta || typeof meta.description !== 'string' || exec.stripDisplayTag(meta.description) !== label) continue;
    const text = EXEC_IO.readText(entry.path) || '';
    for (const line of text.split('\n')) {
      if (!line.includes('"usage"') || !line.includes('"timestamp"')) continue;
      const rec = parseJson(line);
      const t = rec && typeof rec.timestamp === 'string' ? Date.parse(rec.timestamp) : NaN;
      if (Number.isFinite(t)) {
        lo = Math.min(lo, t);
        hi = Math.max(hi, t);
      }
    }
  }
  return lo !== Infinity && hi > lo ? hi - lo : null;
}

// gatherRetroItems(a, gaps) -> [{id, type, delegationMetadata, sessionTracking}]: --item ids plus every seat and frontier
// member of each --run, read over REST (shared by retro-metrics and bug-report --root).
async function gatherRetroItems(a, gaps) {
  // Members: --item ids plus every seat and frontier member of each --run.
  const ids = [...a.items];
  for (const runId of a.runs) {
    const seats = await readRetro('runs_read', { select: 'seats', runId, ended: true }, gaps, 'members');
    ids.push(...retroIdsOf(seats && (seats.seats || seats.items)));
    const frontier = await readRetro('runs_read', { select: 'frontier', runId }, gaps, 'members');
    if (frontier) ids.push(...retroIdsOf(frontier.waiting), ...retroIdsOf(frontier.inFlight));
  }
  const byShort = new Map();
  for (const id of ids) if (!byShort.has(id.slice(0, 8))) byShort.set(id.slice(0, 8), id);
  const members = [...byShort.values()].slice(0, RETRO_MAX_ITEMS);

  return Promise.all(
    members.map(async (id) => {
      const note = async (key) => {
        const data = await readRetro('notes_read', { select: 'get', itemId: id, key }, gaps, 'notes', ['not_found']);
        return data && typeof data.body === 'string' ? data.body : null;
      };
      const [delegationMetadata, sessionTracking, item] = await Promise.all([
        note('delegation-metadata'),
        note('session-tracking'),
        readRetro('items_read', { select: 'get', itemId: id }, gaps, 'items', ['not_found'])
      ]);
      // items_read get answers the item itself in data (type and rootId at the top level).
      return {
        id,
        type: item && typeof item.type === 'string' ? item.type : null,
        rootId: item && typeof item.rootId === 'string' ? item.rootId : null,
        delegationMetadata,
        sessionTracking
      };
    })
  );
}

async function runRetroMetrics(args) {
  const a = parseRetroArgs(args);
  const cwd = process.cwd();
  const gaps = [];
  const configPath = a.config || join(cwd, '.taskorchestrator', 'config.yaml');
  const configText = existsSync(configPath) ? safeRead(configPath) : '';
  const baselines = configText ? parseBaselines(configText) : null;

  // Session folder: the session sweep and the retrospective's own cost.
  let meters = null;
  let self = { measured: false, reason: 'no session dir' };
  if (a.sessionDir) {
    if (!EXEC_IO.isDir(a.sessionDir)) self = { measured: false, reason: 'session dir not found' };
    else {
      try {
        meters = exec.sessionMeters({ io: EXEC_IO, sessionDir: a.sessionDir, warnings: [] });
        const label = `retrospective:${a.runs.length > 0 ? a.runs[0].slice(0, 8) : 'unknown'}`;
        const row = meters.agents.find((r) => r.label === label) || null;
        self = { label, row, wallMs: row ? retroWallMs(a.sessionDir, label) : null, reason: 'no transcript carries the label' };
      } catch (e) {
        gaps.push({ section: 'session', reason: 'session_sweep_failed' });
      }
    }
  }

  if (!apiBaseUrl({ cwd })) {
    const doc = { contract: RETRO_METRICS_CONTRACT, measured: false, reason: 'no REST API configured' };
    if (a.selfOnly || a.sessionDir) doc.self = retroMetrics({ self }).self;
    emit(JSON.stringify(doc));
    process.exit(0);
  }

  const items = await gatherRetroItems(a, gaps);

  let noteVolume = { measured: false, reason: 'not measured' };
  if (!a.selfOnly) {
    if (!a.root) noteVolume = { measured: false, reason: 'no --root' };
    else {
      const now = Date.now();
      let since = a.since;
      if (!since) {
        const nameSection = configText ? readSection(configText, 'project', { blockOnly: true }) : null;
        const name = nameSection ? scalar(nameSection.lines, 'name') : null;
        const listed = await readRetro(
          'items_read',
          { select: 'list', tags: ['session-retrospective'], sort: 'createdAt', order: 'desc', limit: 20, fields: ['createdAt'] },
          gaps,
          'since'
        );
        const hit = ((listed && listed.items) || []).find((it) => typeof it.title === 'string' && (it.title.includes(a.root) || (name && it.title.includes(name))));
        since = hit && typeof hit.createdAt === 'string' ? hit.createdAt : new Date(now - 7 * DAY_MS).toISOString();
      }
      const floor = now - 31 * DAY_MS;
      if (Date.parse(since) < floor) since = new Date(floor + 60 * 1000).toISOString();
      const base = { select: 'notes', rootId: a.root, since };
      const role = await retroReportRows({ ...base, groupBy: 'role' });
      const key = role.error ? role : await retroReportRows({ ...base, groupBy: 'noteKey' });
      if (role.error || key.error) noteVolume = { measured: false, reason: role.error || key.error };
      else {
        const maxLengthsByType = {};
        const types = new Map();
        for (const it of items) if (it.type && !types.has(it.type)) types.set(it.type, it.id);
        for (const [type, id] of types) {
          const schema = await readRetro('items_read', { select: 'schema', itemId: id }, gaps, 'schema');
          const caps = {};
          for (const n of (schema && schema.notes) || []) if (n && typeof n.key === 'string' && Number.isFinite(n.maxLength) && !(n.key in caps)) caps[n.key] = n.maxLength;
          maxLengthsByType[type] = caps;
        }
        noteVolume = { window: { since }, byRole: role.rows, byNoteKey: key.rows, maxLengthsByType };
      }
    }
  }

  const doc = retroMetrics({ items, sessionMeters: meters, noteVolume, baselines, self });
  const out = a.selfOnly ? { contract: doc.contract, self: doc.self } : { ...doc, gaps };
  emit(JSON.stringify(out));
  process.exit(0);
}

// ---------------------------------------------------------------------------------------
// bug-report: maintainer-only GitHub bug reports for product defects (lib/bug-report.mjs holds the logic; this
// handler supplies gh, the clock, the per-install state file and the versions).
// bug-report (--in <file> | --root <id> [--run <id>]... [--item <id>]... --product <fingerprint>[:<observation>]...) [--deny <name>]...
// Exactly one of --in and --root. --root gathers the defects over REST as retro-metrics does, and only the members whose
// item rootId matches the --root value count. Nothing gathered is filed unless a --product flag names its fingerprint
// (the agent's product-scope classification); every other gathered defect is skipped with reason 'not-product'.
// The gate is one environment variable, read once in runBugReportCmd; no config opens it and there is no --config.
// Exit 0 with a 'run-wave/bug-report-v1' document; exit 2 only for bad arguments.
// ---------------------------------------------------------------------------------------
const PRODUCT_FP_RE = /^to-bug-[0-9a-f]{8}$/;
const PRODUCT_OBS_RE = /^[0-9a-f]{8,}$/;
const ROOT_ID_RE = /^[0-9a-f]{8}(?:-?[0-9a-f]+)*$/i;

function parseBugReportArgs(args) {
  const out = { in: null, root: null, runs: [], items: [], deny: [], product: new Map() };
  for (let i = 0; i < args.length; i += 1) {
    const a = args[i];
    if (!['--in', '--root', '--run', '--item', '--deny', '--product'].includes(a)) throw new Error(`invalid args: unknown argument ${a}`);
    const v = args[i + 1];
    if (v === undefined || v.startsWith('--')) throw new Error(`invalid args: ${a} needs a value`);
    i += 1;
    if (a === '--in') out.in = v;
    else if (a === '--root') out.root = v;
    else if (a === '--run') {
      if (!out.runs.includes(v)) out.runs.push(v);
    } else if (a === '--item') {
      if (!out.items.includes(v)) out.items.push(v);
    } else if (a === '--product') {
      const [fp, obs, ...extra] = v.split(':');
      if (!PRODUCT_FP_RE.test(fp)) throw new Error(`invalid args: --product fingerprint must be to-bug- plus 8 hex characters (got ${fp})`);
      if (extra.length > 0 || (obs !== undefined && !PRODUCT_OBS_RE.test(obs))) throw new Error('invalid args: --product observation must be 8 or more hex characters');
      if (!out.product.has(fp) || obs !== undefined) out.product.set(fp, obs === undefined ? out.product.get(fp) || null : obs);
    } else out.deny.push(v);
  }
  if (out.in && out.root) throw new Error('invalid args: --in and --root are mutually exclusive');
  if (!out.in && !out.root) throw new Error('invalid args: --in <file> or --root <id> is required');
  if (out.root && !ROOT_ID_RE.test(out.root)) throw new Error('invalid args: --root needs an item id (8 or more hex characters, or a UUID)');
  if (out.in && (out.runs.length > 0 || out.items.length > 0 || out.product.size > 0)) throw new Error('invalid args: --run, --item and --product go with --root, not --in');
  return out;
}

// The --root input: the defects of the members' session-tracking notes, gathered and parsed exactly as
// retro-metrics does (gatherRetroItems, then retroMetrics().defects), restricted to members of the --root project.
// Returns null when no API URL resolves (a gap says so). Whether a defect is a product defect is the agent's call,
// passed as --product, so a gathered defect carries scope 'unclassified' until runBugReportCmd marks the named ones.
async function retroDefectsInput(a, gaps) {
  if (!apiBaseUrl({ cwd: process.cwd() })) {
    gaps.push({ section: 'api', reason: 'no_api_url' });
    return null;
  }
  const root = a.root.toLowerCase().replace(/-/g, '');
  const before = gaps.length;
  const items = (await gatherRetroItems(a, gaps)).filter((it) => it.rootId !== null && it.rootId.toLowerCase().replace(/-/g, '').startsWith(root));
  // A --root matching no member would read as a clean complete input; say so (a failed read already has its own gap).
  if (items.length === 0 && gaps.length === before) gaps.push({ section: 'root', reason: 'no_member' });
  return retroMetrics({ items }).defects.map((d) => ({
    kind: 'defect',
    scope: 'unclassified',
    signal: d.component === 'server' ? 'tool-error' : 'script-failure',
    tool: d.tool,
    op: d.op,
    code: d.code,
    message: d.message,
    quote: d.message,
    observation: null
  }));
}

async function runBugReportCmd(args) {
  const a = parseBugReportArgs(args);
  let input = { defects: [] };
  if (a.in) {
    try {
      input = JSON.parse(readFileSync(a.in, 'utf8'));
    } catch (e) {
      throw new Error(`invalid args: --in is not readable JSON (${(e && e.code) || 'parse'})`);
    }
    if (!input || typeof input !== 'object' || !Array.isArray(input.defects)) throw new Error('invalid args: input needs a defects array');
  }
  const cwd = process.cwd();
  // Maintainer-only gate (internal, for dogfooding; never document it for users): the bug-report gate opens
  // only when TASK_ORCHESTRATOR_BUG_REPORTS is exactly the string 1. Read once here; no config key opens it.
  // The project config is read only for its project.name, which joins the deny names.
  const gateEnv = process.env.TASK_ORCHESTRATOR_BUG_REPORTS;
  const located = locateConfig({ cwd });
  const projectText = located && typeof located.text === 'string' ? located.text : '';
  const statePath = join(userHome(), '.taskorchestrator', 'github-feedback-state.json');
  const nameSection = projectText ? readSection(projectText, 'project', { blockOnly: true }) : null;
  const denyNames = [...a.deny];
  const projectName = nameSection ? scalar(nameSection.lines, 'name') : null;
  if (projectName) denyNames.push(projectName);
  try {
    denyNames.push(userInfo().username);
  } catch {
    // no OS user
  }
  try {
    denyNames.push(hostname());
  } catch {
    // no hostname
  }
  const versions = { plugin: null, server: null };
  try {
    versions.plugin = JSON.parse(readFileSync(resolve(HERE, '..', '.claude-plugin', 'plugin.json'), 'utf8')).version || null;
  } catch {
    // unknown
  }
  let catalog = null;
  try {
    catalog = JSON.parse(readFileSync(resolve(HERE, '..', 'hooks', 'lib', 'tool-catalog.json'), 'utf8'));
  } catch {
    // no catalog: a server tool then fails qualification
  }
  let vocabulary = null;
  try {
    vocabulary = JSON.parse(readFileSync(resolve(HERE, 'lib', 'bug-report-vocabulary.json'), 'utf8'));
  } catch {
    // no vocabulary: a plugin tool then fails qualification
  }
  // The gate is read before anything is gathered: a closed gate does no REST read and no gh call.
  const gaps = [];
  const rootInput = { state: null, notProduct: [] };
  if (a.root && readGate(gateEnv).open) {
    const gathered = await retroDefectsInput(a, gaps);
    if (gathered === null) rootInput.state = 'unavailable';
    else {
      const seen = new Set();
      for (const d of gathered) {
        const fp = fingerprintOf(d);
        if (a.product.has(fp)) {
          input.defects.push({ ...d, scope: 'product', observation: a.product.get(fp) });
        } else if (!seen.has(fp)) {
          seen.add(fp);
          rootInput.notProduct.push({ fingerprint: fp, observation: null, action: 'skipped', url: null, reason: 'not-product', line: `bug-report ${fp}: skipped not-product` });
        }
      }
      const gatheredFps = new Set(gathered.map((d) => fingerprintOf(d)));
      for (const fp of a.product.keys()) if (!gatheredFps.has(fp)) gaps.push({ section: 'product', reason: 'not_gathered', fingerprint: fp });
      rootInput.state = gaps.length > 0 ? 'partial' : 'complete';
    }
  }
  const doc = await (async () => {
    const gateProbe = runBugReport({ defects: [], gateEnv, deps: { gh: () => ({ status: 1 }), now: () => 0, readState: () => null, writeState: () => false, writeTemp: () => '', removeTemp: () => {} } });
    if (gateProbe.gate === 'disabled') return gateProbe;
    const base = apiBaseUrl({ cwd });
    const wk = base ? await wellKnown({ baseUrl: base, timeoutMs: RETRO_TIMEOUT_MS }) : null;
    if (wk && typeof wk.version === 'string' && wk.version) versions.server = wk.version;
    return runBugReport({
      defects: input.defects,
      gateEnv,
      denyNames,
      versions,
      catalog,
      vocabulary,
      deps: {
        gh: (ghArgs) => {
          // TASK_ORCHESTRATOR_GH_SHIM (test seam, same power as PATH): a script node runs in place of gh, because a
          // Windows spawn cannot start a scripted gh. Unset or empty: the real gh.
          const shim = process.env.TASK_ORCHESTRATOR_GH_SHIM;
          const r = shim
            ? spawnSync(process.execPath, [shim, ...ghArgs], { encoding: 'utf8', timeout: 30000 })
            : spawnSync('gh', ghArgs, { encoding: 'utf8', timeout: 30000 });
          return { status: typeof r.status === 'number' ? r.status : 1, stdout: r.stdout || '' };
        },
        now: () => Date.now(),
        readState: () => parseJson(safeRead(statePath)),
        writeState: (st) => {
          try {
            mkdirSync(dirname(statePath), { recursive: true });
            writeFileSync(statePath, JSON.stringify(st));
            return true;
          } catch {
            return false;
          }
        },
        writeTemp: (text) => {
          const dir = mkdtempSync(join(tmpdir(), 'bug-report-'));
          const file = join(dir, 'body.md');
          writeFileSync(file, text);
          return file;
        },
        removeTemp: (file) => rmSync(dirname(file), { recursive: true, force: true })
      }
    });
  })();
  doc.gaps = gaps;
  if (rootInput.state !== null) {
    doc.input = rootInput.state;
    doc.results.push(...rootInput.notProduct);
  }
  emit(JSON.stringify(doc));
  process.exit(0);
}

// ---------------------------------------------------------------------------------------
// Subcommand table. snapshot is built here; run-exec-lib.mjs supplies: next, prompt, stage-result, scan-declarations,
// verify, notes-audit, dispatch-meta, provenance, session-meters, review-prompt, reseat, run-ops,
// follow-ups, report, review-args, post-run-steps.
// ---------------------------------------------------------------------------------------
const SUBCOMMANDS = {
  probe: runProbe,
  plan: runPlan,
  explain: runExplain,
  validate: runValidate,
  'bind-run': runBindRun,
  snapshot: runSnapshot,
  'retro-metrics': runRetroMetrics,
  'bug-report': runBugReportCmd,
  next: (args) => exec.EXEC_COMMANDS.next(args, EXEC_IO),
  prompt: (args) => exec.EXEC_COMMANDS.prompt(args, EXEC_IO),
  'stage-result': (args) => exec.EXEC_COMMANDS['stage-result'](args, EXEC_IO),
  'scan-declarations': (args) => exec.EXEC_COMMANDS['scan-declarations'](args, EXEC_IO),
  verify: (args) => exec.EXEC_COMMANDS.verify(args, EXEC_IO),
  'notes-audit': (args) => exec.EXEC_COMMANDS['notes-audit'](args, EXEC_IO),
  'dispatch-meta': (args) => exec.EXEC_COMMANDS['dispatch-meta'](args, EXEC_IO),
  provenance: (args) => exec.EXEC_COMMANDS.provenance(args, EXEC_IO),
  'session-meters': (args) => exec.EXEC_COMMANDS['session-meters'](args, EXEC_IO),
  'review-prompt': (args) => exec.EXEC_COMMANDS['review-prompt'](args, EXEC_IO),
  reseat: (args) => exec.EXEC_COMMANDS.reseat(args, EXEC_IO),
  'run-ops': (args) => exec.EXEC_COMMANDS['run-ops'](args, EXEC_IO),
  'follow-ups': (args) => exec.EXEC_COMMANDS['follow-ups'](args, EXEC_IO),
  report: (args) => exec.EXEC_COMMANDS.report(args, EXEC_IO),
  'review-args': (args) => exec.EXEC_COMMANDS['review-args'](args, EXEC_IO),
  'post-run-steps': (args) => exec.EXEC_COMMANDS['post-run-steps'](args, EXEC_IO)
};

async function main() {
  const [, , cmd, ...argv] = process.argv;
  const rest = [];
  let outPath = null;
  let force = false;
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--force') force = true;
    else if (argv[i] === '--out') {
      // A following flag (e.g. `--out --force`) is a missing path, not a file named '--force'.
      if (argv[i + 1] === undefined || argv[i + 1].startsWith('--')) return fail(2, 'invalid args', '--out needs a path');
      outPath = argv[i + 1];
      i += 1;
    } else rest.push(argv[i]);
  }
  if (force && outPath === null) return fail(2, 'invalid args', '--force applies only with --out <path>');
  if (outPath !== null) OUT = { path: outPath, force };
  const handler = SUBCOMMANDS[cmd];
  if (!handler) {
    return fail(2, 'invalid args', `unknown subcommand ${cmd}`);
  }
  try {
    await handler(rest);
  } catch (e) {
    const msg = String((e && e.message) || e);
    // A helper's own argument error ('invalid args: ...') is reported as its message alone.
    fail(2, 'invalid args', msg.startsWith('invalid args:') ? msg : String((e && e.stack) || e));
  }
}

main();
