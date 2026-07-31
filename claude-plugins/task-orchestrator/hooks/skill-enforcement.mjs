#!/usr/bin/env node
// PreToolUse manage_notes — suggests skill invocation for notes with skill requirements.
// Reads config.yaml to find which note keys have a `skill` field, then checks if the
// note body being upserted is substantive enough to reflect skill-framework output.
//
// Advisory only: this hook never blocks the call (additionalContext is non-blocking) and
// never claims to. It warns at most once per (session, itemId, key) — see the marker
// discipline below — so a concise-by-design note that keeps getting re-upserted does not
// re-trigger the same suggestion forever.

import { readFileSync, writeFileSync, mkdirSync } from 'fs';
import { resolve, join, dirname } from 'path';
import os from 'os';

// Read hook input from stdin
let input = '';
try {
  input = readFileSync(0, 'utf-8');
} catch {
  process.exit(0);
}

let hookInput;
try {
  hookInput = JSON.parse(input);
} catch {
  process.exit(0);
}

const toolInput = hookInput.tool_input;
if (!toolInput || toolInput.operation !== 'upsert' || !Array.isArray(toolInput.notes)) {
  process.exit(0);
}

// --- Once-per-(session, item, key) marker -----------------------------------------------
// Fail-open discipline mirrors retro-lib.mjs's readMarker/writeMarker (implemented locally
// here per scope — this hook does not import retro-lib.mjs). Any I/O error is treated as
// "no marker" / "write silently dropped"; a marker failure must never crash the hook.

function sanitizeForFilename(value) {
  return String(value).replace(/[^a-zA-Z0-9-]/g, '_');
}

function markerPath(sessionId) {
  const key = sessionId ? sanitizeForFilename(sessionId) : 'unknown';
  return join(os.tmpdir(), 'task-orchestrator', `skill-enforce-${key}.json`);
}

function readMarker(path) {
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf-8'));
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

function writeMarker(path, obj) {
  try {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, JSON.stringify(obj));
  } catch {
    // swallow all errors — a marker write must never crash a hook
  }
}

const marker = markerPath(hookInput.session_id);
const warnedPairs = readMarker(marker);

// Locate config.yaml — check AGENT_CONFIG_DIR, then walk up from cwd to find
// the project root containing .taskorchestrator/. This handles worktrees where
// cwd is nested under .claude/worktrees/<name>/ but config is at the repo root.
function findConfigPath() {
  const candidates = [];
  if (process.env.AGENT_CONFIG_DIR) {
    candidates.push(resolve(process.env.AGENT_CONFIG_DIR, '.taskorchestrator', 'config.yaml'));
  }
  let dir = process.cwd();
  const root = resolve(dir, '/');
  while (dir !== root) {
    candidates.push(resolve(dir, '.taskorchestrator', 'config.yaml'));
    dir = resolve(dir, '..');
  }
  for (const candidate of candidates) {
    try {
      return readFileSync(candidate, 'utf-8');
    } catch {
      continue;
    }
  }
  return null;
}

const configContent = findConfigPath();
if (!configContent) {
  // No config file found — no skill requirements to enforce
  process.exit(0);
}

// Parse skill requirements (and per-key maxLength) from config using a line-based state
// machine. Builds two maps keyed by note key: skillMap (noteKey → skillName) and
// maxLengthMap (noteKey → maxLength as configured). Note: if multiple schemas define the
// same key with different values, last wins.
const skillMap = new Map();
const maxLengthMap = new Map();
let currentKey = null;

for (const line of configContent.split('\n')) {
  const trimmed = line.trim();

  // Match `- key: <value>` (note entry start)
  const keyMatch = trimmed.match(/^- key:\s*["']?([^"'\s]+)["']?/);
  if (keyMatch) {
    currentKey = keyMatch[1];
    continue;
  }

  // Match `skill: <value>` within a note entry
  const skillMatch = trimmed.match(/^skill:\s*["']?([^"'\s]+)["']?/);
  if (skillMatch && currentKey) {
    skillMap.set(currentKey, skillMatch[1]);
    continue;
  }

  // Match `maxLength: <n>` within a note entry
  const maxLengthMatch = trimmed.match(/^maxLength:\s*(\d+)/);
  if (maxLengthMatch && currentKey) {
    maxLengthMap.set(currentKey, Number(maxLengthMatch[1]));
    continue;
  }

  // Reset on section boundary (any line ending in `:` except `skill:`/`maxLength:`)
  if (trimmed.endsWith(':') && !trimmed.startsWith('skill:') && !trimmed.startsWith('maxLength:')) {
    currentKey = null;
  }
}

if (skillMap.size === 0) {
  process.exit(0);
}

// Placeholder patterns that indicate the skill framework was NOT followed.
// Defined at module scope — compiled once, shared across all note checks.
const PLACEHOLDER_PATTERNS = [
  /^n\/?a$/i,
  /^looks?\s+(fine|good|ok)/i,
  /^no\s+issues?\s*(found)?/i,
  /^todo$/i,
  /^placeholder$/i,
  /^tbd$/i,
  /^pending$/i,
  /^will\s+fill\s+(later|soon)/i
];

// Default minimum character count for a note body to be considered substantive.
const DEFAULT_SUBSTANTIVE_LENGTH = 200;

// Effective substantive floor for a key: when the key's configured maxLength is small
// (< 800), a flat 200-char floor would be unreachable for a compliant note, so the floor
// scales down with maxLength instead. Otherwise the default 200-char floor applies.
function substantiveFloor(key) {
  const maxLength = maxLengthMap.get(key);
  if (maxLength !== undefined && maxLength < 800) {
    return Math.min(DEFAULT_SUBSTANTIVE_LENGTH, Math.floor(maxLength / 4));
  }
  return DEFAULT_SUBSTANTIVE_LENGTH;
}

// Check each note being upserted against skill requirements
const warnings = [];
const newlyWarnedPairs = [];

for (const note of toolInput.notes) {
  const { key, body, itemId } = note;
  if (!key || !skillMap.has(key)) continue;

  const dedupKey = `${itemId}::${key}`;
  if (warnedPairs[dedupKey]) continue; // already suggested once this session for this item+key

  const skill = skillMap.get(key);
  const trimmedBody = (body || '').trim();
  const bodyLen = trimmedBody.length;
  const floor = substantiveFloor(key);

  // Only check placeholders on short bodies — long notes that happen to start
  // with "Looks fine..." followed by substantive analysis should not be flagged.
  const isPlaceholder = bodyLen < floor &&
    PLACEHOLDER_PATTERNS.some(p => p.test(trimmedBody));

  if (!body || bodyLen < floor || isPlaceholder) {
    warnings.push(
      `⊘ SKILL SUGGESTED — the note "${key}" is schema-bound to the /${skill} framework. ` +
      `If you have not already, invoke the Skill tool with skill="${skill}" and incorporate ` +
      `its structured evaluation before finalizing this note. If the Skill tool reports ` +
      `'Unknown skill', the schema's skill pointer is invalid — log that as an observation ` +
      `instead of retrying. (Shown once per item+key per session.)`
    );
    newlyWarnedPairs.push(dedupKey);
  }
}

if (warnings.length === 0) {
  process.exit(0);
}

for (const pair of newlyWarnedPairs) {
  warnedPairs[pair] = true;
}
writeMarker(marker, warnedPairs);

const output = {
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    additionalContext: warnings.join('\n\n')
  }
};

process.stdout.write(JSON.stringify(output));
