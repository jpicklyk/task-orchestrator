#!/usr/bin/env node
// Session Start Hook — injects 4.0 workflow guidance plus setup/scope status derived from the
// shared config locator (none / config without rootId / personal root / project root).
//
// Output is split in two. GUIDANCE (base guidance, Setup Status, Personal/Project Scope) is withheld
// when `suppressionReason` is non-null (headless iteration, subagent, director session). DIAGNOSTICS
// (setup hint, registration check, config shape, config-sync notice, plugin drift, server
// compatibility line, watchPaths) are always computed. With nothing left, additionalContext is omitted.

import { readFileSync, statSync } from 'fs';
import { resolve, dirname, join } from 'path';
import { fileURLToPath } from 'url';
import { createHash } from 'crypto';
import { isOrchestratorServerKey, SERVER_SEGMENT_TOKEN } from './registration.mjs';
import { locateConfig, userConfigPath } from './config-locator.mjs';
import { readSection } from './yaml-lite.mjs';
import { readHookInput } from './lib/stdin.mjs';
import { suppressionReason } from './execution-mode.mjs';
import { apiBaseUrl, apiBaseUrlInfo } from './lib/api-client.mjs';
import { collectOrchestratorRegistrations, preferredHttpRegistration } from './lib/mcp-registrations.mjs';
import { emit } from './lib/emit.mjs';
import { sharedMarkerPath, createMarkerOnce } from './lib/markers.mjs';
import { checkServer } from './lib/server-compat.mjs';

const BASE_GUIDANCE = `## Task Orchestrator — Session Context

- Move items between phases with \`advance\` op \`transition\`, never raw status edits. Ids accept a full UUID or a hex prefix of 8 or more characters.
- Resume or inspect with \`items_read\` select \`context\` (no arguments lists active and stalled items; pass \`itemId\` for one item's gate status). Find the next work with \`items_read\` select \`next\`.
- Hierarchy: items have parentId and depth; trees nest to any depth. \`items_write\` op \`create\` requires \`type\`.
- These are orchestration-profile tools (the \`profiles\` entry in the tool catalog); a server running another profile may not expose them.`;

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
- Do NOT pass \`ancestorId\` on \`items_read\` selects (\`list\`, \`search\`, \`context\`, \`next\`, \`blocked\`) — the personal root is a global store, so reads stay unscoped and may return other projects' items.`;
  }

  return `${BASE_GUIDANCE}

## Project Scope

Active project: ${label}
Config: ${loc.path}

- Pass \`ancestorId: "${loc.rootId}"\` on \`items_read\` selects \`list\`, \`search\`, \`context\`, \`next\` and \`blocked\` to scope results to this project.
- Anchor new root-level items under this project by setting \`parentId: "${loc.rootId}"\`.`;
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
  // createMarkerOnce is false on EEXIST (already shown today) and on any other error (stay silent).
  if (!createMarkerOnce(sharedMarkerPath(`setup-hint-${hash}-${date}`, ''))) return null;
  return state === 'none'
    ? 'Task Orchestrator is not set up for this directory — run /task-orchestrator:init to configure it.'
    : `Task Orchestrator config at ${loc.path} has no project.rootId — run /task-orchestrator:init${loc.scope === 'user' ? ' --user' : ''} to finish setup.`;
}

function buildConfigSyncNotice(state, registrations) {
  if (state !== 'project' && state !== 'user') return null;
  if (apiBaseUrl()) return null;
  if (!registrations.some((r) => typeof r.url === 'string' && /^https?:\/\//i.test(r.url))) return null;
  return `## Config Sync

Config-sync cannot push this config: the MCP server is registered over http(s) but no REST API URL resolves from it (the registration url carries credentials, is a non-loopback \`.mcp.json\` url, or uses an unresolved \`\${VAR}\`). Register the MCP server over HTTP at a usable URL, set \`TASK_ORCHESTRATOR_API_URL\`, or run \`/task-orchestrator:init${state === 'user' ? ' --user' : ''}\` (writes \`client.json\`).`;
}

function normOrigin(origin) {
  try {
    const u = new URL(origin); // WHATWG parse lowercases the host and drops default ports
    return `${u.protocol}//${u.host}`;
  } catch {
    return origin;
  }
}

const SOURCE_LABEL = { env: 'TASK_ORCHESTRATOR_API_URL', 'project-client': 'project client.json', 'user-client': 'user client.json' };

// One line when the hooks' REST base comes from env or a client.json but the MCP server is
// registered over http at a different origin: hooks and tools would be talking to different servers.
function buildMismatchWarning() {
  const info = apiBaseUrlInfo({ cwd: process.cwd(), env: process.env });
  if (!info || !SOURCE_LABEL[info.source]) return null;
  const reg = preferredHttpRegistration({ cwd: process.cwd(), env: process.env });
  if (!reg) return null;
  if (normOrigin(info.url) === normOrigin(reg.origin)) return null;
  return `Task Orchestrator: hooks call the REST API at ${info.url} (${SOURCE_LABEL[info.source]}) but MCP server ${reg.key} is registered at ${reg.origin} (${reg.source}) - hooks and tools are talking to different servers.`;
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
// that omits it means the plugin hooks silently never fire for that server.
//
// Strictly diagnostic: every read/parse failure is swallowed and the section
// is simply omitted. No network calls. Runs in every mode.
// ─────────────────────────────────────────────────────────────────────────

function buildRegistrationCheckSection(registrations) {
  const offending = registrations.filter((r) => !isOrchestratorServerKey(r.key));
  if (offending.length === 0) return null;

  const lines = offending.map(
    (r) =>
      `- \`${r.key}\` (found in ${r.source}): rename the MCP server key to include \`${SERVER_SEGMENT_TOKEN}\`, e.g. \`mcp-task-orchestrator\` — plugin hooks match only such keys.`
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

// Config-shape diagnostic: 3.x top-level `orchestration:` / `retrospective:` / the removed 3.x attribution
// sections are ignored by the plugin and rejected by the server (config-sync validates, then pushes the
// whole file). The first two move under `extensions.claude-code`; the attribution section is deleted.
// One line naming what was found.
// The retired key is spelled literally here because this is a diagnostic for the removed key, not live use of
// it (see current/docs/config-reference.md, the 3.x top-level keys section). actor-removal.test.mjs exempts
// exactly this declaration line in this file and bans the literal everywhere else in hooks/*.mjs.
const RETIRED_SECTION = 'actor_attribution';

function buildConfigShapeNotice(loc) {
  if (typeof loc.text !== 'string' || !loc.text) return null;
  const found = ['orchestration', 'retrospective', RETIRED_SECTION].filter((name) => readSection(loc.text, name) !== null);
  if (found.length === 0) return null;
  const names = found.map((n) => `\`${n}:\``).join(' and ');
  const movable = found.filter((n) => n !== RETIRED_SECTION).map((n) => `\`${n}:\``).join(' and ');
  const actions = [];
  if (movable) actions.push(`move ${movable} under \`extensions.claude-code\``);
  if (found.includes(RETIRED_SECTION)) actions.push(`delete \`${RETIRED_SECTION}:\``);
  return `## Config Shape

The top-level ${names} ${found.length > 1 ? 'sections are' : 'section is'} ignored by the plugin and rejected by the server, so config-sync cannot push ${loc.path}: ${actions.join(' and ')}.`;
}

const hookInput = readHookInput();
const suppressed = suppressionReason(hookInput, process.env) !== null;
const location = getLocation();
const state = setupState(location);

// Guidance: withheld when suppressed.
let guidance = null;
if (!suppressed) {
  try {
    guidance = buildContext(location, state);
  } catch {
    // Fail-open: any unexpected error falls back to static guidance so a broken
    // hook never blocks session start.
    guidance = BASE_GUIDANCE;
  }
}

// Diagnostics: always computed, each fail-open.
let registrations = [];
try {
  registrations = collectOrchestratorRegistrations({ cwd: process.cwd(), env: process.env });
} catch {
  // Fail-open: registrations are purely diagnostic.
}

const diagnostics = [];
for (const build of [
  () => buildRegistrationCheckSection(registrations),
  () => buildConfigShapeNotice(location),
  () => buildConfigSyncNotice(state, registrations),
  () => buildFreshnessWarning(),
]) {
  try {
    const section = build();
    if (section) diagnostics.push(section);
  } catch {
    // Fail-open: diagnostics never affect session start.
  }
}

const parts = [guidance, ...diagnostics].filter(Boolean);
const output = { hookSpecificOutput: { hookEventName: "SessionStart" } };
if (parts.length > 0) output.hookSpecificOutput.additionalContext = parts.join('\n\n');

try {
  const hint = buildSetupHint(location, state, registrations);
  if (hint) output.systemMessage = hint;
} catch {
  // Fail-open.
}

// Hooks/MCP endpoint mismatch: one line, fail-open.
try {
  const mismatch = buildMismatchWarning();
  if (mismatch) output.systemMessage = output.systemMessage ? output.systemMessage + '\n' + mismatch : mismatch;
} catch {
  // Fail-open.
}

// Server compatibility: only when a REST API is configured; an unreachable server or an unusable
// well-known document yields no line. The warning follows the setup hint, one line each.
try {
  if (apiBaseUrl()) {
    const warning = await checkServer({ timeoutMs: 1000 });
    if (warning) output.systemMessage = output.systemMessage ? output.systemMessage + '\n' + warning : warning;
  }
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
emit(output);
