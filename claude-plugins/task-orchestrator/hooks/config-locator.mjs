// Locates the Task Orchestrator config file for a hook invocation. Pure module — no side effects
// at import time, file reads only (never spawns git), never throws, no caching across calls.
//
// Lookup order (first readable regular file wins):
//   1. $AGENT_CONFIG_DIR/.taskorchestrator/config.yaml
//   2. walk up from cwd checking <dir>/.taskorchestrator/config.yaml; a home-level path is never
//      yielded here and the walk keeps climbing past it
//   3. the main checkout of the nearest .git *file* (linked worktree): gitdir -> commondir -> parent;
//      only when the common dir's basename is `.git` (a bare repo yields nothing); never a home-level path
//   4. the user-level <home>/.taskorchestrator/config.yaml (home = TASK_ORCHESTRATOR_HOME else os.homedir())
//
// The home set is TASK_ORCHESTRATOR_HOME (when non-empty), os.homedir() and os.userInfo().homedir, each
// read in its own try/catch (a source that throws or is empty is left out). TASK_ORCHESTRATOR_HOME
// replaces the home, it does not overlay it: the real-home config is never a project hit. Step 1 is
// not filtered. Scope is 'user' only when the hit equals the user-level path, otherwise 'project'.
//
// Optional walk ceiling: TASK_ORCHESTRATOR_CEILING (read from the env argument; a relative value
// resolves against cwd), modelled on GIT_CEILING_DIRECTORIES. Unset or empty: no effect. When set, the
// step-2 walk-up and the step-3 .git search stop BEFORE examining the ceiling directory (cwd equal to
// the ceiling examines nothing), and a step-3 main checkout equal to or above the ceiling is discarded
// only when the ceiling applied to this lookup (it is cwd or an ancestor of cwd); a main checkout below
// or beside the ceiling is kept. A ceiling that is not cwd or an ancestor of it has no effect on the
// lookup. Steps 1 and 4 are never bounded. Exists mainly as a test seam; leave unset in normal use.
//
// projectClientCandidates() lists the PROJECT-level client.json files to try, in order: beside the located
// project config, then (linked worktree only) <main checkout>/.taskorchestrator/client.json.
// projectClientPath() is its first entry, or null.

import { readFileSync, statSync } from 'fs';
import os from 'os';
import { join, resolve, dirname, isAbsolute, basename } from 'path';
import { readSection, scalar } from './yaml-lite.mjs';

const CONFIG_REL = join('.taskorchestrator', 'config.yaml');

/** TASK_ORCHESTRATOR_HOME when non-empty, else os.homedir(). */
export function userHome(env = process.env) {
  const h = env.TASK_ORCHESTRATOR_HOME;
  return h ? h : os.homedir();
}

/** Absolute path of the user-level config file. */
export function userConfigPath(env = process.env) {
  return resolve(userHome(env), CONFIG_REL);
}

/** Absolute path of the user-level client.json (REST API connection settings). */
export function userClientPath(env = process.env) {
  return resolve(userHome(env), '.taskorchestrator', 'client.json');
}

/** Normalised home-level config paths for every home source that yields a non-empty value. */
function homeLevelPaths(env) {
  const set = new Set();
  const readers = [() => env.TASK_ORCHESTRATOR_HOME, () => os.homedir(), () => os.userInfo().homedir];
  for (const read of readers) {
    try {
      const h = read();
      if (typeof h === 'string' && h) set.add(norm(join(h, CONFIG_REL)));
    } catch {
      // source unavailable
    }
  }
  return set;
}

function norm(p) {
  const r = resolve(p);
  return process.platform === 'win32' ? r.toLowerCase() : r;
}

function isFile(p) {
  try {
    return statSync(p).isFile();
  } catch {
    return false;
  }
}

function isDir(p) {
  try {
    return statSync(p).isDirectory();
  } catch {
    return false;
  }
}

function readTrim(p) {
  try {
    return readFileSync(p, 'utf8').trim();
  } catch {
    return null;
  }
}

/** Main checkout dir for the nearest .git found walking up from cwd, or null. */
function mainCheckoutFromGit(cwd, ceiling) {
  let dir = resolve(cwd);
  for (;;) {
    if (ceiling && norm(dir) === norm(ceiling)) return null;
    const dotGit = join(dir, '.git');
    if (isDir(dotGit)) return null;
    if (isFile(dotGit)) {
      const content = readTrim(dotGit);
      const line = content && content.split('\n').map((l) => l.trim()).find((l) => l.startsWith('gitdir:'));
      const target = line ? line.slice('gitdir:'.length).trim() : '';
      if (!target) return null;
      const gitdir = isAbsolute(target) ? target : resolve(dir, target);
      const common = readTrim(join(gitdir, 'commondir'));
      if (!common) return null;
      const commonDir = isAbsolute(common) ? common : resolve(gitdir, common);
      const commonResolved = resolve(commonDir);
      const base = basename(commonResolved);
      if ((process.platform === 'win32' ? base.toLowerCase() : base) !== '.git') return null;
      return dirname(commonResolved);
    }
    const parent = dirname(dir);
    if (parent === dir) return null;
    dir = parent;
  }
}

/** True when main equals the ceiling or is an ancestor of it. */
function atOrAboveCeiling(main, ceiling) {
  const m = norm(main);
  let d = resolve(ceiling);
  for (;;) {
    if (norm(d) === m) return true;
    const parent = dirname(d);
    if (parent === d) return false;
    d = parent;
  }
}

function parseProject(text) {
  try {
    const section = readSection(text, 'project', { blockOnly: true });
    if (!section) return { rootId: null, name: null };
    return { rootId: scalar(section.lines, 'rootId') || null, name: scalar(section.lines, 'name') || null };
  } catch {
    return { rootId: null, name: null };
  }
}

function* candidates(cwd, env, userPath, homePaths, ceiling) {
  let ceilingMet = false;
  if (env.AGENT_CONFIG_DIR) yield resolve(cwd, env.AGENT_CONFIG_DIR, CONFIG_REL);
  let dir = resolve(cwd);
  for (;;) {
    if (ceiling && norm(dir) === norm(ceiling)) {
      ceilingMet = true;
      break;
    }
    const c = join(dir, CONFIG_REL);
    if (!homePaths.has(norm(c))) yield c;
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  const main = mainCheckoutFromGit(cwd, ceiling);
  if (main && !(ceilingMet && atOrAboveCeiling(main, ceiling))) {
    const c = join(main, CONFIG_REL);
    if (!homePaths.has(norm(c))) yield c;
  }
  if (userPath) yield userPath;
}

/**
 * @returns {{scope:'project'|'user'|'none', path:string|null, bytes:Buffer|null, text:string|null,
 *            rootId:string|null, name:string|null}}
 */
export function locateConfig({ cwd = process.cwd(), env = process.env } = {}) {
  const none = { scope: 'none', path: null, bytes: null, text: null, rootId: null, name: null };
  try {
    let userPath = null;
    try {
      userPath = userConfigPath(env);
    } catch {
      // home unresolvable: skip the user-level step, every hit is a project hit
    }
    const homePaths = homeLevelPaths(env);
    const ceilingEnv = env.TASK_ORCHESTRATOR_CEILING;
    const ceiling = typeof ceilingEnv === 'string' && ceilingEnv ? resolve(cwd, ceilingEnv) : null;
    for (const p of candidates(cwd, env, userPath, homePaths, ceiling)) {
      if (!isFile(p)) continue;
      let bytes;
      try {
        bytes = readFileSync(p);
      } catch {
        continue;
      }
      const text = bytes.toString('utf8');
      const { rootId, name } = parseProject(text);
      const abs = resolve(p);
      return { scope: userPath && norm(abs) === norm(userPath) ? 'user' : 'project', path: abs, bytes, text, rootId, name };
    }
  } catch {
    // fall through
  }
  return none;
}

/**
 * Absolute path of the PROJECT-level client.json: `client.json` in the directory of the located
 * config, only when the located scope is 'project'; otherwise null. Does not check that the file
 * exists. Never throws.
 */
export function projectClientPath({ cwd = process.cwd(), env = process.env } = {}) {
  return projectClientCandidates({ cwd, env })[0] ?? null;
}

/**
 * Absolute PROJECT-level client.json paths, in the order to try; `[]` when the located scope is not
 * 'project'. P1 = `client.json` beside the located config. P2 = `<main>/.taskorchestrator/client.json`
 * when the located config sits in a linked worktree (main checkout found by mainCheckoutFromGit from the
 * directory holding the config's `.taskorchestrator`). P2 is dropped when: the config was located through
 * AGENT_CONFIG_DIR; the ceiling applies to this lookup (it is cwd or an ancestor of cwd) and main is the
 * ceiling or above it; main's config path is a home-level path; or P2 equals P1. Checks no file's
 * existence. Never throws.
 */
export function projectClientCandidates({ cwd = process.cwd(), env = process.env } = {}) {
  try {
    const located = locateConfig({ cwd, env });
    if (located.scope !== 'project' || !located.path) return [];
    const p1 = join(dirname(located.path), 'client.json');
    const out = [p1];
    try {
      if (env.AGENT_CONFIG_DIR && norm(located.path) === norm(resolve(cwd, env.AGENT_CONFIG_DIR, CONFIG_REL))) return out;
      const ceilingEnv = env.TASK_ORCHESTRATOR_CEILING;
      const ceiling = typeof ceilingEnv === 'string' && ceilingEnv ? resolve(cwd, ceilingEnv) : null;
      const start = dirname(dirname(located.path));
      const main = mainCheckoutFromGit(start, ceiling);
      if (!main) return out;
      if (ceiling && atOrAboveCeiling(ceiling, cwd) && atOrAboveCeiling(main, ceiling)) return out;
      if (homeLevelPaths(env).has(norm(join(main, CONFIG_REL)))) return out;
      const p2 = join(main, '.taskorchestrator', 'client.json');
      if (norm(p2) !== norm(p1)) out.push(p2);
    } catch {
      // keep what was resolved so far
    }
    return out;
  } catch {
    return [];
  }
}
