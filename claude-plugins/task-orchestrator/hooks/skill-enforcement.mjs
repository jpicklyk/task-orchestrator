#!/usr/bin/env node
// PreToolUse manage_notes — enforces skill invocation for notes with skill requirements.
// Reads config.yaml to find which note keys have a `skill` field, then checks if the
// note body being upserted is substantive enough to reflect skill-framework output.

import { readFileSync } from 'fs';
import { resolve } from 'path';

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

// Locate config.yaml
const configDir = process.env.AGENT_CONFIG_DIR || process.cwd();
const configPath = resolve(configDir, '.taskorchestrator', 'config.yaml');

let configContent;
try {
  configContent = readFileSync(configPath, 'utf-8');
} catch {
  // No config file — no skill requirements to enforce
  process.exit(0);
}

// Parse skill requirements from config using a line-based state machine.
// Builds a map: noteKey → skillName for entries that have a `skill:` field.
// Note: if multiple schemas define the same key with different skills, last wins.
const skillMap = new Map();
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

  // Reset on section boundary (any line ending in `:` except `skill:`)
  if (trimmed.endsWith(':') && !trimmed.startsWith('skill:')) {
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

// Minimum character count for a note body to be considered substantive.
// Notes below this threshold trigger the skill-invocation directive.
const MIN_SUBSTANTIVE_LENGTH = 200;

// Check each note being upserted against skill requirements
const warnings = [];

for (const note of toolInput.notes) {
  const { key, body } = note;
  if (!key || !skillMap.has(key)) continue;

  const skill = skillMap.get(key);
  const trimmedBody = (body || '').trim();
  const bodyLen = trimmedBody.length;

  // Only check placeholders on short bodies — long notes that happen to start
  // with "Looks fine..." followed by substantive analysis should not be blocked.
  const isPlaceholder = bodyLen < MIN_SUBSTANTIVE_LENGTH &&
    PLACEHOLDER_PATTERNS.some(p => p.test(trimmedBody));

  if (!body || bodyLen < MIN_SUBSTANTIVE_LENGTH || isPlaceholder) {
    warnings.push(
      `⊘ SKILL REQUIRED — The note "${key}" requires the /${skill} skill framework.\n` +
      `Before filling this note, invoke the Skill tool with skill="${skill}" and follow its structured evaluation.\n` +
      `A note produced without the skill framework will not meet the quality bar.\n` +
      `Abort this call, invoke the skill, then retry with substantive findings.`
    );
  }
}

if (warnings.length === 0) {
  process.exit(0);
}

const output = {
  hookSpecificOutput: {
    hookEventName: "PreToolUse",
    additionalContext: warnings.join('\n\n')
  }
};

process.stdout.write(JSON.stringify(output));
