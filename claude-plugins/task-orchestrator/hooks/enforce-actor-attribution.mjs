#!/usr/bin/env node
// PreToolUse hook — enforces actor attribution on MCP write operations when
// auditing is enabled in .taskorchestrator/config.yaml.
//
// Config format:
//   auditing:
//     enabled: true
//
// When enabled, blocks advance_item and manage_notes(upsert) calls that are
// missing an actor object on any transition/note element.

import { readFileSync } from 'fs';
import { resolve } from 'path';

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

const toolName = hookInput.tool_name || '';
const toolInput = hookInput.tool_input || {};

// Early exit: only enforce on advance_item or manage_notes upsert
const isAdvance = toolName.includes('advance_item');
const isNoteUpsert = toolName.includes('manage_notes') && toolInput.operation === 'upsert';
if (!isAdvance && !isNoteUpsert) process.exit(0);

// Locate and read config.yaml — check AGENT_CONFIG_DIR, then walk up from cwd.
// Handles worktrees where cwd is nested under .claude/worktrees/<name>/.
function readConfigContent() {
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

// Returns true only when auditing.enabled is explicitly set to true.
// Handles both block YAML and inline forms, strips trailing comments.
function isAuditingEnabled(configContent) {
  if (!configContent) return false;

  let inAuditingSection = false;

  for (const line of configContent.split('\n')) {
    const trimmed = line.trim();

    // Detect top-level "auditing:" — check for inline form first
    if (/^auditing\s*:/.test(trimmed)) {
      // Handle inline: `auditing: { enabled: true }`
      const inlineMatch = trimmed.match(/^auditing\s*:\s*\{(.+)\}/);
      if (inlineMatch) {
        const inner = inlineMatch[1];
        const enabledMatch = inner.match(/enabled\s*:\s*(\S+)/);
        return enabledMatch ? enabledMatch[1].replace(/#.*$/, '').trim().toLowerCase() === 'true' : false;
      }
      inAuditingSection = true;
      continue;
    }

    // Any other top-level key (non-indented, non-empty) ends the auditing section
    if (inAuditingSection && /^\S/.test(line)) {
      inAuditingSection = false;
    }

    if (inAuditingSection) {
      const match = trimmed.match(/^enabled\s*:\s*(.+)/);
      if (match) {
        return match[1].replace(/#.*$/, '').trim().toLowerCase() === 'true';
      }
    }
  }

  return false;
}

const configContent = readConfigContent();
if (!isAuditingEnabled(configContent)) {
  process.exit(0);
}

let missing = false;

if (isAdvance) {
  const transitions = toolInput.transitions || [];
  missing = transitions.some(t => !t.actor);
} else if (isNoteUpsert) {
  const notes = toolInput.notes || [];
  missing = notes.some(n => !n.actor);
}

if (!missing) {
  process.exit(0);
}

process.stdout.write(JSON.stringify({
  decision: 'block',
  reason: 'Auditing is enabled \u2014 actor attribution required. Include an "actor" object ' +
    'with "id" (string) and "kind" (orchestrator|subagent|user|external) on every ' +
    'transition/note element. For subagents, include "parent" with the dispatching agent\'s id.'
}));
