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

  // Reset on new note entry or section boundary
  if (trimmed.startsWith('- key:') || trimmed.endsWith(':') && !trimmed.startsWith('skill:')) {
    currentKey = null;
  }
}

if (skillMap.size === 0) {
  process.exit(0);
}

// Check each note being upserted against skill requirements
const warnings = [];

for (const note of toolInput.notes) {
  const { key, body } = note;
  if (!key || !skillMap.has(key)) continue;

  const skill = skillMap.get(key);
  const bodyLen = (body || '').trim().length;

  // Placeholder patterns that indicate the skill framework was NOT followed
  const placeholders = [
    /^n\/?a$/i,
    /^looks?\s+(fine|good|ok)/i,
    /^no\s+issues?\s*(found)?/i,
    /^todo$/i,
    /^placeholder$/i,
    /^tbd$/i,
    /^pending$/i,
    /^will\s+fill\s+(later|soon)/i
  ];

  const isPlaceholder = placeholders.some(p => p.test((body || '').trim()));

  if (!body || bodyLen < 200 || isPlaceholder) {
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
