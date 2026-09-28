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

import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import * as lib from './run-planner-lib.mjs';

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

  const now = flagValue(args, '--now', null);
  const maxItemsRaw = flagValue(args, '--max-items', '5');
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
// Subcommand table. B2b adds: next, prompt, stage-result, scan-declarations, verify,
// actors, provenance, review-prompt.
// ---------------------------------------------------------------------------------------
const SUBCOMMANDS = {
  probe: runProbe,
  plan: runPlan,
  explain: runExplain,
  validate: runValidate
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
