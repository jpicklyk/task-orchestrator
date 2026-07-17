// Shared helpers for the retrospective-trigger hooks (retro-trigger.mjs, retro-ack.mjs).
// Pure module — no side effects at import time, no top-level I/O.

import { readFileSync, writeFileSync, mkdirSync } from 'fs';
import { resolve, join, dirname } from 'path';
import os from 'os';

// Locate and read .taskorchestrator/config.yaml — check AGENT_CONFIG_DIR, then walk up from
// cwd. Handles worktrees where cwd is nested under .claude/worktrees/<name>/.
// Idiom copied from enforce-actor-attribution.mjs.
export function findConfigContent() {
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

const VALID_MODES = ['nudge', 'dispatch', 'off'];

// Parses the top-level `retrospective:` block:
//   retrospective:
//     mode: dispatch
// Also supports the inline form `retrospective: { mode: dispatch }`. Strips quotes and
// trailing `#` comments. Returns 'nudge' | 'dispatch' | 'off' — defaults to 'nudge' when the
// file, block, or key is absent, or the value is unrecognized.
export function parseRetrospectiveMode(configContent) {
  if (!configContent) return 'nudge';

  let inSection = false;

  for (const line of configContent.split('\n')) {
    const trimmed = line.trim();

    if (/^retrospective\s*:/.test(trimmed)) {
      const inlineMatch = trimmed.match(/^retrospective\s*:\s*\{(.+)\}/);
      if (inlineMatch) {
        const modeMatch = inlineMatch[1].match(/mode\s*:\s*([^,}]+)/);
        if (modeMatch) {
          const val = modeMatch[1].replace(/#.*$/, '').trim().replace(/^["']|["']$/g, '').toLowerCase();
          return VALID_MODES.includes(val) ? val : 'nudge';
        }
        return 'nudge';
      }
      inSection = true;
      continue;
    }

    if (inSection && /^\S/.test(line)) {
      inSection = false;
    }

    if (inSection) {
      const match = trimmed.match(/^mode\s*:\s*(.+)/);
      if (match) {
        const val = match[1].replace(/#.*$/, '').trim().replace(/^["']|["']$/g, '').toLowerCase();
        return VALID_MODES.includes(val) ? val : 'nudge';
      }
    }
  }

  return 'nudge';
}

// Parses the top-level `project:` block for its `rootId` value. Mirrors parseProjectBlock in
// session-start.mjs, but returns only the rootId string (or null).
export function parseProjectRootId(configContent) {
  if (!configContent) return null;

  let inProjectSection = false;

  for (const line of configContent.split('\n')) {
    const trimmed = line.trim();

    if (/^project\s*:\s*$/.test(trimmed)) {
      inProjectSection = true;
      continue;
    }

    if (inProjectSection && /^\S/.test(line)) {
      inProjectSection = false;
    }

    if (inProjectSection) {
      const rootIdMatch = trimmed.match(/^rootId\s*:\s*["']?([^"'#]+?)["']?\s*(#.*)?$/);
      if (rootIdMatch) {
        return rootIdMatch[1].trim();
      }
    }
  }

  return null;
}

export function markerPath(key) {
  return join(os.tmpdir(), 'task-orchestrator', 'retro-' + key.replace(/[^a-zA-Z0-9-]/g, '_') + '.json');
}

export function readMarker(path) {
  try {
    return JSON.parse(readFileSync(path, 'utf-8'));
  } catch {
    return {};
  }
}

export function writeMarker(path, obj) {
  try {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, JSON.stringify(obj));
  } catch {
    // swallow all errors — a marker write must never crash a hook
  }
}

export const COOLDOWN_MS = 30 * 60 * 1000;

// MCP tool responses arrive as { content: [{ type: "text", text: "<json string>" }] }. Parses
// the embedded JSON payload; returns null on any failure so callers can fall back to raw
// string probing if they choose to.
export function extractResponseJson(toolResponse) {
  try {
    return JSON.parse(toolResponse.content[0].text);
  } catch {
    return null;
  }
}

export function buildNudge(roots) {
  const ids = (roots || []).join(', ');
  const first = (roots || [])[0] || '';
  return `Retrospective suggested — an implementation run reached terminal (root(s): ${ids}). ` +
    `Consider running \`/session-retrospective ${first}\` to capture learnings before the session ends. ` +
    `Skip if the feature still has items in progress, or if this was minor housekeeping.`;
}

export function buildDispatch(roots, rootId) {
  const rootsJoined = (roots || []).join(', ');
  const ancestorId = rootId || 'none configured';
  return `Retrospective dispatch (mode: dispatch). Run ${rootsJoined} reached terminal. ` +
    `Dispatch exactly ONE background retrospective now via the Agent tool: subagent_type "general-purpose", ` +
    `model "sonnet", run_in_background true, prompt: invoke /session-retrospective ${rootsJoined}, ` +
    `project ancestorId ${ancestorId}, work ONLY from durable MCP state (session-tracking + delegation-metadata ` +
    `notes, schema config, agent-observation items — no conversation context), write the retrospective into ` +
    `the process-global 'Session Retrospectives' container at depth 0 OUTSIDE any project root, return only the ` +
    `retrospective item UUID plus a 2-3 line headline. Do not dispatch more than one retrospective for this run; ` +
    `skip entirely if the feature still has active items.`;
}
