#!/usr/bin/env node
// CLI — marks closed runs as retrospected. `node retro-ack.mjs --run <id> [--run <id>]...` sends one
// REST `runs_write` `mark {runId, key: retro}` per distinct id (first write wins, so a replay answers
// `marked: false`). The session-retrospective skill calls it at its closing step: a retrospective run by
// hand or by a dispatched agent sets the same mark the hooks set, so a later session does not owe it again.
//
// One line per id on stdout:
//   marked <id> | already marked <id> | mark failed <id>: <code> | not marked <id>: no REST API configured
// Always exits 0. With no `--run` it makes no request and prints the old compatibility no-op line.
// A `--run` with no value (trailing, or empty) is skipped; ids are used verbatim.

import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { callOp, apiBaseUrl } from './lib/api-client.mjs';

const CALL_TIMEOUT_MS = 3000;

/** The distinct, non-empty values following each `--run` flag, in order of first appearance. */
export function parseRunIds(argv) {
  const ids = [];
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] !== '--run') continue;
    const value = i + 1 < argv.length ? argv[i + 1] : '';
    if (typeof value === 'string' && value !== '' && value !== '--run' && !ids.includes(value)) ids.push(value);
  }
  return ids;
}

async function markOne(runId) {
  const res = await callOp('runs_write', { ops: [{ op: 'mark', runId, key: 'retro' }] }, { timeoutMs: CALL_TIMEOUT_MS });
  if (res === null || res === undefined) return `not marked ${runId}: no REST API configured`;
  const r = Array.isArray(res.results) ? res.results[0] : null;
  if (r && typeof r === 'object' && r.ok === true) return r.marked === false ? `already marked ${runId}` : `marked ${runId}`;
  const code =
    (r && typeof r === 'object' && r.error && typeof r.error.code === 'string' && r.error.code) ||
    (res.error && typeof res.error.code === 'string' && res.error.code) ||
    'unknown';
  return `mark failed ${runId}: ${code}`;
}

/** Output lines for the given argv (never throws). */
export async function run(argv = []) {
  try {
    const ids = parseRunIds(argv);
    if (ids.length === 0) {
      return ['retro-ack is a no-op: the run mark (runs_write mark, key retro) is the retrospective acknowledgement.'];
    }
    if (!apiBaseUrl()) return ids.map((id) => `not marked ${id}: no REST API configured`);
    const lines = [];
    for (const id of ids) {
      try {
        lines.push(await markOne(id));
      } catch {
        lines.push(`mark failed ${id}: unknown`);
      }
    }
    return lines;
  } catch {
    return [];
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const lines = await run(process.argv.slice(2));
  if (lines.length > 0) process.stdout.write(lines.join('\n') + '\n');
  process.exit(0);
}
