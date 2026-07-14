#!/usr/bin/env node
// Session Start Hook — injects current v3 workflow guidance, plus project
// scope (rootId/name) when .taskorchestrator/config.yaml declares a `project:` block.

import { readFileSync } from 'fs';
import { resolve } from 'path';

// Locate config.yaml — check AGENT_CONFIG_DIR, then walk up from cwd to find
// the project root containing .taskorchestrator/. This handles worktrees where
// cwd is nested under .claude/worktrees/<name>/ but config is at the repo root.
// Pattern reused from skill-enforcement.mjs / enforce-actor-attribution.mjs.
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

// Parse the top-level `project:` block:
//   project:
//     rootId: "<uuid>"
//     name: "<project name>"
// Values may be quoted or bare. Returns { rootId, name } or null if the block
// is absent or has no rootId.
function parseProjectBlock(configContent) {
  if (!configContent) return null;

  let inProjectSection = false;
  let rootId = null;
  let name = null;

  for (const line of configContent.split('\n')) {
    const trimmed = line.trim();

    if (/^project\s*:\s*$/.test(trimmed)) {
      inProjectSection = true;
      continue;
    }

    // Any other top-level (non-indented, non-empty) line ends the section.
    if (inProjectSection && /^\S/.test(line)) {
      inProjectSection = false;
    }

    if (inProjectSection) {
      const rootIdMatch = trimmed.match(/^rootId\s*:\s*["']?([^"'#]+?)["']?\s*(#.*)?$/);
      if (rootIdMatch) {
        rootId = rootIdMatch[1].trim();
        continue;
      }
      const nameMatch = trimmed.match(/^name\s*:\s*["']?([^"'#]+?)["']?\s*(#.*)?$/);
      if (nameMatch) {
        name = nameMatch[1].trim();
        continue;
      }
    }
  }

  if (!rootId) return null;
  return { rootId, name };
}

const BASE_GUIDANCE = `## Task Orchestrator — Session Context

- Use \`advance_item\` for role transitions — not raw status edits.
- Hierarchy: items have parentId and depth (max 3).
- To resume: call \`get_context()\` with no args to see active and stalled items.`;

function buildContext() {
  let configContent = null;
  try {
    configContent = findConfigPath();
  } catch {
    return BASE_GUIDANCE;
  }

  if (!configContent) {
    return BASE_GUIDANCE;
  }

  let project = null;
  try {
    project = parseProjectBlock(configContent);
  } catch {
    return BASE_GUIDANCE;
  }

  if (!project) {
    return `${BASE_GUIDANCE}

## Project Scope

This workspace is not project-scoped — no \`project:\` block found in \`.taskorchestrator/config.yaml\`.
Run \`/adopt-project-scope\` (existing DBs) or \`/quick-start\` (fresh workspaces) to set one up.`;
  }

  const label = project.name ? `${project.name} (\`${project.rootId}\`)` : `\`${project.rootId}\``;

  return `${BASE_GUIDANCE}

## Project Scope

Active project: ${label}

- Pass \`ancestorId: "${project.rootId}"\` on \`query_items\` (list mode), \`get_next_item\`, \`get_context\`, and \`get_blocked_items\` to scope results to this project.
- Anchor new root-level items under this project by setting \`parentId: "${project.rootId}"\`.
- Process-global containers (Session Retrospectives, Improvement Proposals, agent-observations) stay OUTSIDE the project root at depth 0 — do not anchor them under \`${project.rootId}\`.`;
}

let additionalContext;
try {
  additionalContext = buildContext();
} catch {
  // Fail-open: any unexpected error falls back to static guidance so a broken
  // hook never blocks session start.
  additionalContext = BASE_GUIDANCE;
}

const output = {
  hookSpecificOutput: {
    hookEventName: "SessionStart",
    additionalContext
  }
};
process.stdout.write(JSON.stringify(output));
