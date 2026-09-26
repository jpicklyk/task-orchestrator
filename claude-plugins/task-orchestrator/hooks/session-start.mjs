#!/usr/bin/env node
// Session Start Hook — injects current v3 workflow guidance, plus project
// scope (rootId/name) when .taskorchestrator/config.yaml declares a `project:` block.

import { readFileSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import { homedir } from 'os';
import { readSection, scalar } from './yaml-lite.mjs';
import { isOrchestratorServerKey, SERVER_SEGMENT_TOKEN } from './registration.mjs';

// Absolute path of the config.yaml found by findConfigPath(), if any — surfaced to the
// caller so it can be reported as a SessionStart watchPaths entry (mid-session re-sync).
let foundConfigPath = null;

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
      const content = readFileSync(candidate, 'utf-8');
      foundConfigPath = candidate;
      return content;
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

  const section = readSection(configContent, 'project', { blockOnly: true });
  if (!section) return null;

  const rootId = scalar(section.lines, 'rootId');
  const name = scalar(section.lines, 'name');

  if (!rootId) return null;
  return { rootId, name };
}

const BASE_GUIDANCE = `## Task Orchestrator — Session Context

- Use \`advance_item\` for role transitions — not raw status edits.
- Hierarchy: items have parentId and depth; trees nest to any depth.
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
- Process-global items stay OUTSIDE the project root at depth 0: the Session Retrospectives and Improvement Proposals containers, and standalone agent-observation items — do not anchor any of them under \`${project.rootId}\`.`;
}

// ─────────────────────────────────────────────────────────────────────────
// Registration self-check — warns when the MCP Task Orchestrator server is
// registered under a key that the plugin's hook matchers (hooks-config.json)
// cannot recognize. Matchers require the server segment of the tool name
// (mcp__<server>__<tool>) to contain SERVER_SEGMENT_TOKEN; a registration key
// that omits it means actor-attribution enforcement, the retro trigger, the
// phase guard, and skill enforcement silently never fire for that server.
//
// Strictly diagnostic: every read/parse failure is swallowed and the section
// is simply omitted. No network calls. Runs in every mode.
// ─────────────────────────────────────────────────────────────────────────

function readJsonFile(path) {
  try {
    return JSON.parse(readFileSync(path, 'utf-8'));
  } catch {
    return null;
  }
}

// Walk up from cwd looking for a project-level .mcp.json (same walk pattern as findConfigPath).
function findProjectMcpConfig() {
  let dir = process.cwd();
  const root = resolve(dir, '/');
  while (dir !== root) {
    const candidate = resolve(dir, '.mcp.json');
    const parsed = readJsonFile(candidate);
    if (parsed) return parsed;
    dir = resolve(dir, '..');
  }
  return null;
}

// Locate the user-level Claude Code settings file. CLAUDE_CONFIG_DIR relocates the config
// directory that normally lives at ~/.claude — when set, .claude.json is read from there instead
// of the home directory.
function findUserClaudeConfig() {
  const base = process.env.CLAUDE_CONFIG_DIR || homedir();
  return readJsonFile(resolve(base, '.claude.json'));
}

// True when a string looks like a container image / package reference containing the token in an
// owner/name[:tag] shape (e.g. `ghcr.io/jpicklyk/task-orchestrator:latest`, `jpicklyk/task-orchestrator`)
// rather than a filesystem path. Filesystem-path shapes (drive letters, leading `/` segments, `./`
// or `../` prefixes, backslashes) are explicitly excluded so an unrelated server whose path happens
// to mention the project folder name (e.g. a filesystem MCP server pointed at this repo's checkout)
// is never mistaken for an orchestrator registration.
function looksLikeImageRef(value) {
  if (typeof value !== 'string' || !value.includes(SERVER_SEGMENT_TOKEN)) return false;
  if (/^[A-Za-z]:[\\/]/.test(value)) return false; // drive letter, e.g. D:/ or D:\
  if (value.startsWith('/')) return false; // absolute POSIX path
  if (value.startsWith('./') || value.startsWith('../')) return false; // relative path
  if (value.includes('\\')) return false; // any backslash path separator
  return value.includes('/'); // owner/name[:tag] shape requires a slash
}

// True when the registration entry mentions the orchestrator by `url`, `command`, or an image-like
// arg value — independent of its own key. Deliberately narrower than a blanket JSON.stringify scan:
// a filesystem-path arg (e.g. a filesystem MCP server pointed at a checkout named `task-orchestrator`)
// must NOT count, or every such unrelated registration gets falsely flagged every session.
function entryMentionsOrchestrator(entry) {
  if (!entry || typeof entry !== 'object') return false;
  try {
    if (typeof entry.url === 'string' && entry.url.includes(SERVER_SEGMENT_TOKEN)) return true;
    if (typeof entry.command === 'string' && entry.command.includes(SERVER_SEGMENT_TOKEN)) return true;
    if (Array.isArray(entry.args)) {
      for (const arg of entry.args) {
        if (looksLikeImageRef(arg)) return true;
      }
    }
    return false;
  } catch {
    return false;
  }
}

// Collects { key, source } for every mcpServers entry across the discoverable registration
// surfaces that "is an orchestrator registration" (key or entry body mentions the token).
function collectOrchestratorRegistrations() {
  const found = [];

  function scan(mcpServers, source) {
    if (!mcpServers || typeof mcpServers !== 'object') return;
    for (const [key, entry] of Object.entries(mcpServers)) {
      const isOrchestrator = isOrchestratorServerKey(key) || entryMentionsOrchestrator(entry);
      if (isOrchestrator) found.push({ key, source });
    }
  }

  const projectConfig = findProjectMcpConfig();
  if (projectConfig) scan(projectConfig.mcpServers, '.mcp.json');

  const userConfig = findUserClaudeConfig();
  if (userConfig) {
    scan(userConfig.mcpServers, '~/.claude.json');
    if (userConfig.projects && typeof userConfig.projects === 'object') {
      let dir = process.cwd();
      const root = resolve(dir, '/');
      while (dir !== root) {
        // ~/.claude.json `projects` keys have been observed in BOTH the native form and a
        // forward-slash form on Windows (e.g. `D:\Projects\task-orchestrator` and
        // `D:/Projects/task-orchestrator`) — try both so the lookup doesn't silently miss one.
        const altDir = dir.replace(/\\/g, '/');
        const keysToTry = altDir === dir ? [dir] : [dir, altDir];
        for (const key of keysToTry) {
          const project = userConfig.projects[key];
          if (project) scan(project.mcpServers, `~/.claude.json (projects[${key}])`);
        }
        dir = resolve(dir, '..');
      }
    }
  }

  return found;
}

function buildRegistrationCheckSection() {
  const registrations = collectOrchestratorRegistrations();
  const offending = registrations.filter((r) => !isOrchestratorServerKey(r.key));
  if (offending.length === 0) return null;

  const lines = offending.map(
    (r) =>
      `- \`${r.key}\` (found in ${r.source}): rename the MCP server key to include \`${SERVER_SEGMENT_TOKEN}\`, e.g. \`mcp-task-orchestrator\` — plugin hooks (actor attribution, retro trigger, phase guard, skill enforcement) match only such keys.`
  );

  return `## Hook Registration Check

${lines.join('\n')}`;
}

// ─────────────────────────────────────────────────────────────────────────
// Plugin version freshness check — dev-checkout only. Warns when the plugin
// version checked out on disk (claude-plugins/task-orchestrator/.claude-plugin/plugin.json,
// found by the same AGENT_CONFIG_DIR-then-cwd walk-up used elsewhere in this file)
// differs from the version of the plugin actually running this hook. Silent
// when not a dev checkout (no such file found), when either version can't be
// read, or when the versions match — never blocks session start.
//
// Paths are resolved from AGENT_CONFIG_DIR/cwd/CLAUDE_PLUGIN_ROOT/import.meta.url
// rather than hard-coded, so tests can fully control both sides.
// ─────────────────────────────────────────────────────────────────────────

function readPluginVersion(pluginJsonPath) {
  try {
    const parsed = JSON.parse(readFileSync(pluginJsonPath, 'utf-8'));
    return typeof parsed.version === 'string' ? parsed.version : null;
  } catch {
    return null;
  }
}

// Walk up from AGENT_CONFIG_DIR (if set) then cwd looking for the dev-checkout's
// own plugin.json. Mirrors findConfigPath()'s walk pattern so worktrees (cwd
// nested under .claude/worktrees/<name>/) still find the checkout root.
function findDevCheckoutPluginJson() {
  const startDirs = [];
  if (process.env.AGENT_CONFIG_DIR) startDirs.push(process.env.AGENT_CONFIG_DIR);
  startDirs.push(process.cwd());

  for (const start of startDirs) {
    let dir = resolve(start);
    const root = resolve(dir, '/');
    for (;;) {
      const candidate = resolve(dir, 'claude-plugins', 'task-orchestrator', '.claude-plugin', 'plugin.json');
      try {
        readFileSync(candidate, 'utf-8');
        return candidate;
      } catch {
        // keep walking
      }
      if (dir === root) break;
      dir = resolve(dir, '..');
    }
  }
  return null;
}

// Locates the plugin.json of the plugin actually running this hook: CLAUDE_PLUGIN_ROOT
// when the harness sets it, else resolved relative to this hook script's own file
// location (hooks/session-start.mjs -> ../.claude-plugin/plugin.json).
function findRunningPluginJson() {
  if (process.env.CLAUDE_PLUGIN_ROOT) {
    return resolve(process.env.CLAUDE_PLUGIN_ROOT, '.claude-plugin', 'plugin.json');
  }
  const hookDir = dirname(fileURLToPath(import.meta.url));
  return resolve(hookDir, '..', '.claude-plugin', 'plugin.json');
}

function buildFreshnessWarning() {
  const devPluginJsonPath = findDevCheckoutPluginJson();
  if (!devPluginJsonPath) return null; // not a dev checkout — silent

  const devVersion = readPluginVersion(devPluginJsonPath);
  const runningVersion = readPluginVersion(findRunningPluginJson());
  if (!devVersion || !runningVersion) return null; // read error — fail open, silent
  if (devVersion === runningVersion) return null; // up to date — silent

  return `## Plugin Version Drift

The checked-out plugin version (\`${devVersion}\`) differs from the currently loaded plugin cache (\`${runningVersion}\`). Refresh the plugin cache — see \`claude-plugins/CLAUDE.md\` → "Plugin Discovery and Cache Refresh".`;
}

let additionalContext;
try {
  additionalContext = buildContext();
} catch {
  // Fail-open: any unexpected error falls back to static guidance so a broken
  // hook never blocks session start.
  additionalContext = BASE_GUIDANCE;
}

try {
  const registrationSection = buildRegistrationCheckSection();
  if (registrationSection) {
    additionalContext = `${additionalContext}\n\n${registrationSection}`;
  }
} catch {
  // Fail-open: the self-check is purely diagnostic — never let it affect session start.
}

try {
  const freshnessWarning = buildFreshnessWarning();
  if (freshnessWarning) {
    additionalContext = `${additionalContext}\n\n${freshnessWarning}`;
  }
} catch {
  // Fail-open: the freshness check is purely diagnostic — never let it affect session start.
}

const output = {
  hookSpecificOutput: {
    hookEventName: "SessionStart",
    additionalContext
  }
};
// Ask the harness to watch the config file we actually found, so a mid-session edit fires a
// FileChanged event that re-triggers config-sync.mjs without waiting for the next session.
if (foundConfigPath) {
  output.hookSpecificOutput.watchPaths = [foundConfigPath];
}
process.stdout.write(JSON.stringify(output));
