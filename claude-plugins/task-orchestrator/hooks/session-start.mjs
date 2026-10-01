#!/usr/bin/env node
// Session Start Hook — injects current v3 workflow guidance plus setup/scope status derived from
// the shared config locator (none / config without rootId / personal root / project root).

import { readFileSync, statSync, mkdirSync, writeFileSync } from 'fs';
import { resolve, dirname, join } from 'path';
import { fileURLToPath } from 'url';
import { homedir, tmpdir } from 'os';
import { createHash } from 'crypto';
import { isOrchestratorServerKey, SERVER_SEGMENT_TOKEN } from './registration.mjs';
import { locateConfig, userConfigPath } from './config-locator.mjs';
import { apiBaseUrl } from './api-client.mjs';

const BASE_GUIDANCE = `## Task Orchestrator — Session Context

- Use \`advance_item\` for role transitions — not raw status edits.
- Hierarchy: items have parentId and depth; trees nest to any depth.
- To resume: call \`get_context()\` with no args to see active and stalled items.`;

const EMPTY_LOCATION = { scope: 'none', path: null, rootId: null, name: null };

function getLocation() {
  try {
    const loc = locateConfig({ cwd: process.cwd(), env: process.env });
    return loc && typeof loc === 'object' ? loc : EMPTY_LOCATION;
  } catch {
    return EMPTY_LOCATION;
  }
}

// none | no-root | user | project
function setupState(loc) {
  if (loc.scope === 'none' || !loc.path) return 'none';
  if (!loc.rootId) return 'no-root';
  return loc.scope === 'user' ? 'user' : 'project';
}

function buildContext(loc, state) {
  const label = loc.name ? `${loc.name} (\`${loc.rootId}\`)` : `\`${loc.rootId}\``;

  if (state === 'none') {
    return `${BASE_GUIDANCE}

## Setup Status

No Task Orchestrator config was found for this directory. Run \`/task-orchestrator:init\` to set up this project, or \`/task-orchestrator:init --user\` for a personal root that serves every unconfigured directory.`;
  }

  if (state === 'no-root') {
    const fix = loc.scope === 'user'
      ? 'Run `/task-orchestrator:init --user` to create a personal root.'
      : 'Run `/task-orchestrator:init` to set one up.';
    return `${BASE_GUIDANCE}

## Setup Status

This workspace is not project-scoped — no \`project.rootId\` in \`${loc.path}\`.
${fix}`;
  }

  if (state === 'user') {
    return `${BASE_GUIDANCE}

## Personal Scope

Personal root: ${label}
Config: ${loc.path}

- Anchor new root-level items under the personal root by setting \`parentId: "${loc.rootId}"\`.
- Do NOT pass \`ancestorId\` on reads (\`query_items\`, \`get_next_item\`, \`get_context\`, \`get_blocked_items\`) — the personal root is a global store, so reads stay unscoped and may return other projects' items.
- Process-global items stay OUTSIDE the personal root at depth 0: the Session Retrospectives and Improvement Proposals containers, and standalone agent-observation items.`;
  }

  return `${BASE_GUIDANCE}

## Project Scope

Active project: ${label}
Config: ${loc.path}

- Pass \`ancestorId: "${loc.rootId}"\` on \`query_items\` (list mode), \`get_next_item\`, \`get_context\`, and \`get_blocked_items\` to scope results to this project.
- Anchor new root-level items under this project by setting \`parentId: "${loc.rootId}"\`.
- Process-global items stay OUTSIDE the project root at depth 0: the Session Retrospectives and Improvement Proposals containers, and standalone agent-observation items — do not anchor any of them under \`${loc.rootId}\`.`;
}

// Single-line, user-visible init hint. Emitted at most once per cwd per local day: the marker file
// is created with 'wx' only after every other condition holds, so a suppressed run never consumes it.
function buildSetupHint(loc, state, registrations) {
  if (state !== 'none' && state !== 'no-root') return null;
  if (registrations.length === 0) return null;
  if ((process.env.TASK_ORCHESTRATOR_SETUP_HINT || '').trim().toLowerCase() === 'off') return null;

  let cwd = resolve(process.cwd());
  if (process.platform === 'win32') cwd = cwd.toLowerCase();
  const hash = createHash('sha256').update(cwd).digest('hex').slice(0, 16);
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const dir = join(tmpdir(), 'task-orchestrator');
  const marker = join(dir, `setup-hint-${hash}-${date}`);
  try {
    mkdirSync(dir, { recursive: true });
    writeFileSync(marker, '', { flag: 'wx' });
  } catch {
    return null; // EEXIST = already shown today; anything else = stay silent
  }
  return state === 'none'
    ? 'Task Orchestrator is not set up for this directory — run /task-orchestrator:init to configure it.'
    : `Task Orchestrator config at ${loc.path} has no project.rootId — run /task-orchestrator:init${loc.scope === 'user' ? ' --user' : ''} to finish setup.`;
}

function buildConfigSyncNotice(state, registrations) {
  if (state !== 'project' && state !== 'user') return null;
  if (apiBaseUrl()) return null;
  if (!registrations.some((r) => typeof r.url === 'string' && /^https?:\/\//i.test(r.url))) return null;
  return `## Config Sync

Config-sync cannot push this config because no REST API URL resolves. Set \`TASK_ORCHESTRATOR_API_URL\` or run \`/task-orchestrator:init${state === 'user' ? ' --user' : ''}\` (writes \`client.json\`).`;
}

function isExistingFile(p) {
  try {
    return statSync(p).isFile();
  } catch {
    return false;
  }
}

function buildWatchPaths(loc) {
  const paths = [];
  if (loc.path) paths.push(loc.path);
  try {
    const userPath = userConfigPath(process.env);
    const norm = (p) => (process.platform === 'win32' ? resolve(p).toLowerCase() : resolve(p));
    if (isExistingFile(userPath) && !(loc.path && norm(loc.path) === norm(userPath))) paths.push(userPath);
  } catch {
    // user path unresolvable — skip
  }
  return paths;
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

// Walk up from cwd looking for a project-level .mcp.json .
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

// Collects { key, source, url } for every mcpServers entry across the discoverable registration
// surfaces that "is an orchestrator registration" (key or entry body mentions the token).
function collectOrchestratorRegistrations() {
  const found = [];

  function scan(mcpServers, source) {
    if (!mcpServers || typeof mcpServers !== 'object') return;
    for (const [key, entry] of Object.entries(mcpServers)) {
      const isOrchestrator = isOrchestratorServerKey(key) || entryMentionsOrchestrator(entry);
      if (isOrchestrator) found.push({ key, source, url: entry && typeof entry.url === 'string' ? entry.url : null });
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

function buildRegistrationCheckSection(registrations) {
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
// found by walking up from AGENT_CONFIG_DIR when set, then from cwd)
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
// own plugin.json. Same walk pattern as the other upward scans so worktrees (cwd
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

const location = getLocation();
const state = setupState(location);

let additionalContext;
try {
  additionalContext = buildContext(location, state);
} catch {
  // Fail-open: any unexpected error falls back to static guidance so a broken
  // hook never blocks session start.
  additionalContext = BASE_GUIDANCE;
}

let registrations = [];
try {
  registrations = collectOrchestratorRegistrations();
} catch {
  // Fail-open: registrations are purely diagnostic.
}

try {
  const registrationSection = buildRegistrationCheckSection(registrations);
  if (registrationSection) {
    additionalContext = `${additionalContext}\n\n${registrationSection}`;
  }
} catch {
  // Fail-open: the self-check is purely diagnostic — never let it affect session start.
}

try {
  const notice = buildConfigSyncNotice(state, registrations);
  if (notice) additionalContext = `${additionalContext}\n\n${notice}`;
} catch {
  // Fail-open.
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

try {
  const hint = buildSetupHint(location, state, registrations);
  if (hint) output.systemMessage = hint;
} catch {
  // Fail-open.
}

// Ask the harness to watch the config file(s) we found so a mid-session edit fires a
// FileChanged event that re-triggers config-sync.mjs without waiting for the next session.
try {
  const watchPaths = buildWatchPaths(location);
  if (watchPaths.length > 0) output.hookSpecificOutput.watchPaths = watchPaths;
} catch {
  // Fail-open.
}
process.stdout.write(JSON.stringify(output));
