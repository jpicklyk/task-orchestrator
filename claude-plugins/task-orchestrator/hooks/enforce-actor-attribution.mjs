#!/usr/bin/env node
// PreToolUse hook — enforces actor attribution on MCP write operations when either
// actor_authentication is enabled, or the local-only actor_attribution.required option
// is set, in .taskorchestrator/config.yaml.
//
// Config format:
//   actor_authentication:
//     enabled: true
//   actor_attribution:
//     required: true
//
// actor_attribution.required (default false) mirrors actor_authentication.enabled's deny
// behavior on this hook, but is independent of it — it can be turned on locally (e.g. as a
// project's own dogfood setting) without also standing up full actor_authentication (JWKS
// identity verification). Either option alone is sufficient to enforce.
//
// When enforced, blocks advance_item and manage_notes(upsert) calls that are missing an
// actor object on any transition/note element.

import { readFileSync } from 'fs';
import { resolve } from 'path';
import { readSection, scalar, inlineScalar } from './yaml-lite.mjs';

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

// Returns true only when actor_authentication.enabled is explicitly set to true.
// Handles both block YAML and inline forms, strips trailing comments.
function isActorAuthenticationEnabled(configContent) {
  if (!configContent) return false;

  const section = readSection(configContent, 'actor_authentication');
  if (!section) return false;

  const raw = section.inline !== null
    ? inlineScalar(section.inline, 'enabled')
    : scalar(section.lines, 'enabled');

  return raw !== null && raw.toLowerCase() === 'true';
}

// Returns true only when actor_attribution.required is explicitly set to true. Independent
// of actor_authentication — a project can require actor attribution locally without also
// enabling actor_authentication's JWKS identity verification. Same block/inline handling as
// isActorAuthenticationEnabled.
function isActorAttributionRequired(configContent) {
  if (!configContent) return false;

  const section = readSection(configContent, 'actor_attribution');
  if (!section) return false;

  const raw = section.inline !== null
    ? inlineScalar(section.inline, 'required')
    : scalar(section.lines, 'required');

  return raw !== null && raw.toLowerCase() === 'true';
}

const configContent = readConfigContent();
if (!isActorAuthenticationEnabled(configContent) && !isActorAttributionRequired(configContent)) {
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
  hookSpecificOutput: {
    hookEventName: 'PreToolUse',
    permissionDecision: 'deny',
    permissionDecisionReason: 'Actor attribution is required (actor_authentication.enabled or actor_attribution.required is set). Include an "actor" object ' +
      'with "id" (string) and "kind" (orchestrator|subagent|user|external) on every ' +
      'transition/note element. For subagents, include "parent" with the dispatching agent\'s id.'
  }
}));
