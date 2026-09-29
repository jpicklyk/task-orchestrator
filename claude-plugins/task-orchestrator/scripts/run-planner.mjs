#!/usr/bin/env node
// run-planner.mjs — CLI dispatcher for the run-wave front door's planner helper.
//
// Subcommands: probe | plan | explain | validate. Input via --in <file> or stdin; JSON on
// stdout; {error, detail} on stderr. Exit 0 ok, 2 invalid input, 3 refused (stdout still
// carries the doc or {ok:false, errors}). This file is the ONLY place that reads the clock,
// the filesystem or environment variables — run-planner-lib.mjs stays pure.
//
// B2b adds rows to the subcommand table below: next, prompt, stage-result, scan-declarations,
// verify, actors, provenance, review-prompt.

import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as lib from './run-planner-lib.mjs';
import * as exec from './run-exec-lib.mjs';
import { loadWaveCore } from './lib/wave-core.mjs';

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

function resolvePluginRoot(args) {
  const explicit = flagValue(args, '--plugin-root', null);
  if (explicit) return resolve(explicit);
  return resolve(HERE, '..');
}

function runProbe(args) {
  const cwd = resolve(flagValue(args, '--cwd', process.cwd()));
  const pluginRoot = resolvePluginRoot(args);
  const pluginJsonText = safeRead(join(pluginRoot, '.claude-plugin', 'plugin.json'));
  const subagentStartText = safeRead(join(pluginRoot, 'hooks', 'subagent-start.mjs'));
  const phaseGuardText = safeRead(join(pluginRoot, 'hooks', 'phase-guard-record.mjs'));
  const configYamlText = safeRead(join(cwd, '.taskorchestrator', 'config.yaml'));
  const local = safeRead(join(cwd, '.claude', 'settings.local.json'));
  const projectSettings = safeRead(join(cwd, '.claude', 'settings.json'));
  const user = safeRead(join(homeDir(), '.claude', 'settings.json'));

  const result = lib.probeFrom({
    pluginRoot,
    pluginJsonText,
    subagentStartText,
    phaseGuardText,
    configYamlText,
    settings: [local, projectSettings, user]
  });
  process.stdout.write(`${JSON.stringify(result)}\n`);
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

  const result = lib.buildPlanDoc(snap, { now, maxItems, mode, entry, method, scratchpad });

  if (result.ok) {
    process.stdout.write(`${JSON.stringify(result.doc)}\n`);
    process.exit(0);
  }
  if (result.doc) {
    process.stdout.write(`${JSON.stringify(result.doc)}\n`);
  } else {
    process.stdout.write(`${JSON.stringify({ ok: false, errors: result.errors })}\n`);
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
  process.stdout.write(`${lib.explainPlan(doc)}\n`);
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
  process.stdout.write(`${JSON.stringify(result)}\n`);
  process.exit(result.ok ? 0 : 3);
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
    return safeRead(path);
  },
  writeOut(text) {
    process.stdout.write(text.endsWith('\n') ? text : `${text}\n`);
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
  listDir(dir) {
    try {
      return readdirSync(dir);
    } catch {
      return [];
    }
  },
  loadCore() {
    return loadWaveCore({ pluginRoot: resolve(HERE, '..') });
  }
};

// ---------------------------------------------------------------------------------------
// Subcommand table. B2b adds: next, prompt, stage-result, scan-declarations, verify,
// actors, provenance, review-prompt.
// ---------------------------------------------------------------------------------------
const SUBCOMMANDS = {
  probe: runProbe,
  plan: runPlan,
  explain: runExplain,
  validate: runValidate,
  next: (args) => exec.EXEC_COMMANDS.next(args, EXEC_IO),
  prompt: (args) => exec.EXEC_COMMANDS.prompt(args, EXEC_IO),
  'stage-result': (args) => exec.EXEC_COMMANDS['stage-result'](args, EXEC_IO),
  'scan-declarations': (args) => exec.EXEC_COMMANDS['scan-declarations'](args, EXEC_IO),
  verify: (args) => exec.EXEC_COMMANDS.verify(args, EXEC_IO),
  actors: (args) => exec.EXEC_COMMANDS.actors(args, EXEC_IO),
  provenance: (args) => exec.EXEC_COMMANDS.provenance(args, EXEC_IO),
  'review-prompt': (args) => exec.EXEC_COMMANDS['review-prompt'](args, EXEC_IO)
};

function main() {
  const [, , cmd, ...rest] = process.argv;
  const handler = SUBCOMMANDS[cmd];
  if (!handler) {
    return fail(2, 'invalid args', `unknown subcommand ${cmd}`);
  }
  try {
    handler(rest);
  } catch (e) {
    fail(2, 'invalid args', String((e && e.stack) || e));
  }
}

main();
