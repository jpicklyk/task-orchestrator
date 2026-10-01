// Locates the Task Orchestrator config file for a hook invocation. Pure module — no side effects
// at import time, file reads only (never spawns git), never throws, no caching across calls.
//
// Lookup order (first readable regular file wins):
//   1. $AGENT_CONFIG_DIR/.taskorchestrator/config.yaml
//   2. walk up from cwd checking <dir>/.taskorchestrator/config.yaml (skipping the user-level path)
//   3. the main checkout of the nearest .git *file* (linked worktree): gitdir -> commondir -> parent
//   4. the user-level <home>/.taskorchestrator/config.yaml (home = TASK_ORCHESTRATOR_HOME else os.homedir())
//
// A hit at the user-level path is always scope 'user', whichever step found it.

import { readFileSync, statSync } from 'fs';
import { homedir } from 'os';
import { join, resolve, dirname, isAbsolute } from 'path';
import { readSection, scalar } from './yaml-lite.mjs';

const CONFIG_REL = join('.taskorchestrator', 'config.yaml');

/** TASK_ORCHESTRATOR_HOME when non-empty, else os.homedir(). */
export function userHome(env = process.env) {
  const h = env.TASK_ORCHESTRATOR_HOME;
  return h ? h : homedir();
}

/** Absolute path of the user-level config file. */
export function userConfigPath(env = process.env) {
  return resolve(userHome(env), CONFIG_REL);
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
function mainCheckoutFromGit(cwd) {
  let dir = resolve(cwd);
  for (;;) {
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
      return dirname(commonDir);
    }
    const parent = dirname(dir);
    if (parent === dir) return null;
    dir = parent;
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

function* candidates(cwd, env, userPath) {
  if (env.AGENT_CONFIG_DIR) yield resolve(env.AGENT_CONFIG_DIR, CONFIG_REL);
  let dir = resolve(cwd);
  for (;;) {
    const c = join(dir, CONFIG_REL);
    if (norm(c) !== norm(userPath)) yield c;
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  const main = mainCheckoutFromGit(cwd);
  if (main) yield join(main, CONFIG_REL);
  yield userPath;
}

/**
 * @returns {{scope:'project'|'user'|'none', path:string|null, bytes:Buffer|null, text:string|null,
 *            rootId:string|null, name:string|null}}
 */
export function locateConfig({ cwd = process.cwd(), env = process.env } = {}) {
  const none = { scope: 'none', path: null, bytes: null, text: null, rootId: null, name: null };
  try {
    const userPath = userConfigPath(env);
    for (const p of candidates(cwd, env, userPath)) {
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
      return { scope: norm(abs) === norm(userPath) ? 'user' : 'project', path: abs, bytes, text, rootId, name };
    }
  } catch {
    // fall through
  }
  return none;
}
