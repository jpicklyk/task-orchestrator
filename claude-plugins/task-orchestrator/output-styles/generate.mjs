#!/usr/bin/env node
// Regenerates the shared tier-classification block in every consumer file from the
// single canonical fragment (_fragments/tier-classification.md). Run this after
// editing the fragment — the copies must never be hand-edited.
//
//   node claude-plugins/task-orchestrator/output-styles/generate.mjs            # rewrite copies in place
//   node claude-plugins/task-orchestrator/output-styles/generate.mjs --check    # verify only, non-zero exit on drift (CI)
//   node .../generate.mjs <extra-file> [...]                                    # also sync extra targets, e.g. a home-dir style
//
// The Kotlin test TierClassificationConsistencyTest enforces the in-repo copies in CI;
// this script is the developer convenience for keeping them (and any out-of-repo copy)
// in sync. Required in-repo consumers must contain the markers or the run fails.
import { readFileSync, writeFileSync, existsSync } from 'fs';
import { fileURLToPath } from 'url';
import { dirname, resolve } from 'path';

const MARKER = 'tier-classification';
const BEGIN = `<!-- BEGIN GENERATED:${MARKER}`;
const END = `<!-- END GENERATED:${MARKER}`;

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, '../../..');
const norm = (s) => s.replace(/\r\n/g, '\n');

// Canonical content, normalized to exactly one trailing newline.
const fragment = norm(readFileSync(resolve(scriptDir, '_fragments/tier-classification.md'), 'utf8')).replace(/\n*$/, '\n');

const check = process.argv.includes('--check');
const extra = process.argv.slice(2).filter((a) => !a.startsWith('--'));
// The in-repo consumer set is defined ONCE in the manifest and shared by TierClassificationConsistencyTest
// and current/build.gradle.kts inputs.files, so the three enforcement sites can never diverge.
const manifestPath = resolve(scriptDir, '_fragments/tier-classification.consumers.txt');
const manifestTargets = norm(readFileSync(manifestPath, 'utf8'))
  .split('\n')
  .map((line) => line.trim())
  .filter((line) => line && !line.startsWith('#'))
  .map((rel) => ({ path: resolve(repoRoot, rel), required: true }));
const targets = [
  ...manifestTargets,
  // Out-of-repo copies (e.g. a personal ~/.claude/output-styles/workflow-analyst.md) are not in the
  // manifest — pass them as CLI args to sync them: `node generate.mjs <path-to-style>`.
  ...extra.map((p) => ({ path: resolve(p), required: true })),
];

let failures = 0;
let changed = 0;
for (const { path, required } of targets) {
  if (!existsSync(path)) {
    if (required) { console.error(`ERROR: required consumer missing: ${path}`); failures++; }
    continue;
  }
  const text = norm(readFileSync(path, 'utf8'));
  const bi = text.indexOf(BEGIN);
  const ei = text.indexOf(END);
  if (bi === -1 || ei === -1) {
    if (required) { console.error(`ERROR: markers not found in ${path}`); failures++; }
    continue;
  }
  const beginLineEnd = text.indexOf('\n', bi);
  if (beginLineEnd === -1 || ei <= beginLineEnd) {
    console.error(`ERROR: malformed markers in ${path} (END must follow BEGIN on a later line)`);
    failures++;
    continue;
  }
  const rebuilt = text.slice(0, beginLineEnd + 1) + fragment + text.slice(ei);
  if (rebuilt === text) continue; // already in sync
  if (check) {
    console.error(`OUT OF SYNC: ${path} — run: node claude-plugins/task-orchestrator/output-styles/generate.mjs`);
    failures++;
  } else {
    writeFileSync(path, rebuilt);
    console.log(`updated: ${path}`);
    changed++;
  }
}

if (failures > 0) process.exit(1);
if (!check) console.log(changed === 0 ? 'all consumers already in sync' : `${changed} file(s) updated`);
