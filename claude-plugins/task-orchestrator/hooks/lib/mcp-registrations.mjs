// Discovers how the MCP Task Orchestrator server is registered with Claude Code, and derives a REST
// base URL from an http(s) registration. Pure module: no side effects at import time, never throws,
// file reads only (memoized per process by path + mtime + size, because phase-guard resolves the base
// URL on every orchestrator tool call).
//
// Registration surfaces (Claude Code precedence: local > project > user):
//   local    ~/.claude.json projects[<cwd or ancestor>].mcpServers   (both path spellings on Windows)
//   project  nearest ancestor .mcp.json
//   user     ~/.claude.json top-level mcpServers
// ~/.claude.json lives in CLAUDE_CONFIG_DIR when set, else os.homedir().

import { readFileSync, statSync } from 'fs';
import { resolve } from 'path';
import { homedir } from 'os';
import { isOrchestratorServerKey, SERVER_SEGMENT_TOKEN } from '../registration.mjs';

const SCOPE_ORDER = ['local', 'project', 'user'];
const PREFERRED_KEY = 'mcp-task-orchestrator';

const fileCache = new Map();

/** Parsed JSON of a file, or null. Memoized on path + mtime + size. Never throws. */
function readJsonFile(path) {
  try {
    const st = statSync(path);
    const stamp = `${st.mtimeMs}:${st.size}`;
    const hit = fileCache.get(path);
    if (hit && hit.stamp === stamp) return hit.value;
    let value = null;
    try {
      let text = readFileSync(path, 'utf-8');
      if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
      value = JSON.parse(text);
    } catch {
      value = null;
    }
    fileCache.set(path, { stamp, value });
    return value;
  } catch {
    return null;
  }
}

// Walk up from cwd looking for a project-level .mcp.json.
function findProjectMcpConfig(cwd) {
  let dir = resolve(cwd);
  const root = resolve(dir, '/');
  while (dir !== root) {
    const parsed = readJsonFile(resolve(dir, '.mcp.json'));
    if (parsed) return parsed;
    const up = resolve(dir, '..');
    if (up === dir) break;
    dir = up;
  }
  return null;
}

// CLAUDE_CONFIG_DIR relocates the directory that normally holds ~/.claude.json.
function findUserClaudeConfig(env) {
  const base = env.CLAUDE_CONFIG_DIR || homedir();
  return readJsonFile(resolve(base, '.claude.json'));
}

// True when a string looks like a container image / package reference containing the token in an
// owner/name[:tag] shape (e.g. `ghcr.io/jpicklyk/task-orchestrator:latest`) rather than a filesystem
// path. Filesystem-path shapes (drive letters, leading `/`, `./`, `../`, backslashes) are excluded so
// an unrelated server whose path mentions the project folder is never mistaken for an orchestrator.
export function looksLikeImageRef(value) {
  if (typeof value !== 'string' || !value.includes(SERVER_SEGMENT_TOKEN)) return false;
  if (/^[A-Za-z]:[\\/]/.test(value)) return false;
  if (value.startsWith('/')) return false;
  if (value.startsWith('./') || value.startsWith('../')) return false;
  if (value.includes('\\')) return false;
  return value.includes('/');
}

// True when the entry mentions the orchestrator by `url`, `command`, or an image-like arg value,
// independent of its own key. Deliberately narrower than a blanket JSON scan.
export function entryMentionsOrchestrator(entry) {
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

/**
 * Every mcpServers entry across the discoverable surfaces that is an orchestrator registration (key or
 * entry body mentions the token): `{key, source, scope, url}` (`url` null when the entry has none),
 * ordered local, project, user. Never throws.
 */
export function collectOrchestratorRegistrations({ cwd = process.cwd(), env = process.env } = {}) {
  const found = [];
  try {
    const scan = (mcpServers, source, scope) => {
      if (!mcpServers || typeof mcpServers !== 'object') return;
      for (const [key, entry] of Object.entries(mcpServers)) {
        if (isOrchestratorServerKey(key) || entryMentionsOrchestrator(entry)) {
          found.push({ key, source, scope, url: entry && typeof entry.url === 'string' ? entry.url : null });
        }
      }
    };

    const userConfig = findUserClaudeConfig(env);
    const projectConfig = findProjectMcpConfig(cwd);

    if (userConfig && userConfig.projects && typeof userConfig.projects === 'object') {
      let dir = resolve(cwd);
      const root = resolve(dir, '/');
      while (dir !== root) {
        // `projects` keys have been observed in BOTH the native and a forward-slash form on Windows.
        const altDir = dir.replace(/\\/g, '/');
        const keysToTry = altDir === dir ? [dir] : [dir, altDir];
        for (const key of keysToTry) {
          const project = userConfig.projects[key];
          if (project) scan(project.mcpServers, `~/.claude.json (projects[${key}])`, 'local');
        }
        const up = resolve(dir, '..');
        if (up === dir) break;
        dir = up;
      }
    }
    if (projectConfig) scan(projectConfig.mcpServers, '.mcp.json', 'project');
    if (userConfig) scan(userConfig.mcpServers, '~/.claude.json', 'user');
  } catch {
    // diagnostic only
  }
  return found;
}

/** `${VAR}` / `${VAR:-default}` expansion from env; null when a variable is unresolved. */
function expandEnv(text, env) {
  let unresolved = false;
  const out = text.replace(/\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}/g, (_m, name, dflt) => {
    const v = env[name];
    if (v !== undefined && v !== '') return v;
    if (dflt !== undefined) return dflt;
    unresolved = true;
    return '';
  });
  return unresolved ? null : out;
}

/** Origin of an http(s) URL without credentials, or null. */
function originOf(url) {
  try {
    const u = new URL(url);
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
    if (u.username !== '' || u.password !== '') return null;
    return u.origin;
  } catch {
    return null;
  }
}

function isAuxiliaryPath(url) {
  try {
    const p = new URL(url).pathname.replace(/\/+$/, '');
    return p === '/mcp/director' || p === '/mcp/knowledge';
  } catch {
    return false;
  }
}

/**
 * The preferred http(s) orchestrator registration: highest-precedence scope that has a usable
 * candidate (token key, http(s) url, env-expanded, no credentials); within it key
 * `mcp-task-orchestrator`, else the first whose path is not /mcp/director or /mcp/knowledge, else the
 * first. Returns `{key, scope, source, url, origin}` or null. `isAllowed(origin)` filters project-scope
 * candidates (a repo file must not redirect the token to a remote host). Never throws.
 */
export function preferredHttpRegistration({ cwd = process.cwd(), env = process.env, isAllowed } = {}) {
  try {
    const regs = collectOrchestratorRegistrations({ cwd, env });
    for (const scope of SCOPE_ORDER) {
      const cands = [];
      for (const r of regs) {
        if (r.scope !== scope || !isOrchestratorServerKey(r.key) || typeof r.url !== 'string') continue;
        const expanded = expandEnv(r.url.trim(), env);
        if (!expanded) continue;
        const origin = originOf(expanded);
        if (!origin) continue;
        if (scope === 'project' && typeof isAllowed === 'function' && !isAllowed(origin)) continue;
        cands.push({ key: r.key, scope, source: r.source, url: expanded, origin });
      }
      if (cands.length === 0) continue;
      return cands.find((c) => c.key === PREFERRED_KEY) || cands.find((c) => !isAuxiliaryPath(c.url)) || cands[0];
    }
  } catch {
    // fall through
  }
  return null;
}
