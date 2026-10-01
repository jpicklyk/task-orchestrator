// Coverage for config-locator.mjs: userHome, userConfigPath, locateConfig.
// Oracle: task-scope note + plan section A (lookup order AGENT_CONFIG_DIR -> cwd walk-up
// (skipping the user-level path) -> main checkout via .git file gitdir/commondir -> user-level
// config). Every test pins TASK_ORCHESTRATOR_HOME to an empty temp dir through the env argument.

import { test, mock } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, realpathSync, rmSync } from 'node:fs';
import os, { tmpdir, homedir } from 'node:os';
import { join, resolve, relative, sep, dirname, parse } from 'node:path';
import { userHome, userConfigPath, userClientPath, locateConfig, projectClientPath, projectClientCandidates } from '../config-locator.mjs';
import { existsSync } from 'node:fs';

const made = [];

function tmp(label) {
  const dir = realpathSync(mkdtempSync(join(tmpdir(), `tocfg-${label}-`)));
  made.push(dir);
  return dir;
}

function norm(p) {
  const r = resolve(p);
  return process.platform === 'win32' ? r.toLowerCase() : r;
}

function writeCfg(dir, text) {
  mkdirSync(join(dir, '.taskorchestrator'), { recursive: true });
  const p = join(dir, '.taskorchestrator', 'config.yaml');
  writeFileSync(p, text);
  return p;
}

function projectCfg(rootId, name) {
  return `project:\n  rootId: ${rootId}\n  name: ${name}\n`;
}

// Builds a main checkout M (with .git dir, optional config) and a worktree W elsewhere whose
// .git file points into M/.git/worktrees/w.
function makeWorktree({ mainCfg, gitdirAbsolute = false, commondir = '../..', writeCommondir = true } = {}) {
  const M = tmp('main');
  const W = tmp('wt');
  mkdirSync(join(M, '.git', 'worktrees', 'w'), { recursive: true });
  if (writeCommondir) writeFileSync(join(M, '.git', 'worktrees', 'w', 'commondir'), commondir);
  const gitdirAbs = join(M, '.git', 'worktrees', 'w');
  const gitdirValue = gitdirAbsolute ? gitdirAbs : relative(W, gitdirAbs);
  writeFileSync(join(W, '.git'), `gitdir: ${gitdirValue}\n`);
  const mainPath = mainCfg ? writeCfg(M, mainCfg) : null;
  return { M, W, mainPath };
}

test.after(() => {
  for (const d of made) {
    try { rmSync(d, { recursive: true, force: true }); } catch { /* best effort cleanup */ }
  }
});

// ---- userHome / userConfigPath ----

test('S9: userHome honours TASK_ORCHESTRATOR_HOME; falls back to os.homedir() when unset or empty', () => {
  assert.equal(userHome({ TASK_ORCHESTRATOR_HOME: '/some/where' }), '/some/where');
  assert.equal(userHome({}), homedir());
  assert.equal(userHome({ TASK_ORCHESTRATOR_HOME: '' }), homedir());
});

test('S9: userConfigPath is <home>/.taskorchestrator/config.yaml, absolute', () => {
  const home = tmp('home');
  const p = userConfigPath({ TASK_ORCHESTRATOR_HOME: home });
  assert.equal(norm(p), norm(join(home, '.taskorchestrator', 'config.yaml')));
  assert.equal(resolve(p), p);
});

test('S9: two homes - config only in A; HOME=A finds it as user, HOME=B finds none', () => {
  const A = tmp('homeA');
  const B = tmp('homeB');
  const cwd = tmp('cwd');
  writeCfg(A, projectCfg('rA', 'A'));
  const hitA = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: A } });
  assert.equal(hitA.scope, 'user');
  assert.equal(norm(hitA.path), norm(join(A, '.taskorchestrator', 'config.yaml')));
  const hitB = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: B } });
  assert.equal(hitB.scope, 'none');
});

// ---- locateConfig ----

test('S1: walk-up from a nested cwd finds a project config with rootId/name, bytes and text', () => {
  const home = tmp('home');
  const P = tmp('proj');
  const rootId = '11111111-2222-3333-4444-555555555555';
  const file = writeCfg(P, projectCfg(rootId, 'Proj One'));
  const cwd = join(P, 'a', 'b');
  mkdirSync(cwd, { recursive: true });
  const r = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'project');
  assert.equal(norm(r.path), norm(file));
  assert.equal(resolve(r.path), r.path);
  assert.equal(r.rootId, rootId);
  assert.equal(r.name, 'Proj One');
  assert.ok(Buffer.isBuffer(r.bytes));
  assert.equal(r.bytes.toString('utf8'), projectCfg(rootId, 'Proj One'));
  assert.equal(r.text, projectCfg(rootId, 'Proj One'));
});

test('S2: AGENT_CONFIG_DIR beats a config found by walk-up', () => {
  const home = tmp('home');
  const X = tmp('x');
  const P = tmp('proj');
  const xFile = writeCfg(X, projectCfg('x-root', 'X'));
  writeCfg(P, projectCfg('p-root', 'P'));
  const r = locateConfig({ cwd: P, env: { TASK_ORCHESTRATOR_HOME: home, AGENT_CONFIG_DIR: X } });
  assert.equal(norm(r.path), norm(xFile));
  assert.equal(r.rootId, 'x-root');
  assert.equal(r.scope, 'project');
});

test('S3: walk-up config inside a worktree beats the main checkout config', () => {
  const home = tmp('home');
  const { M, W } = makeWorktree({ mainCfg: projectCfg('main-root', 'Main') });
  const wFile = writeCfg(W, projectCfg('wt-root', 'Wt'));
  const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(norm(r.path), norm(wFile));
  assert.equal(r.rootId, 'wt-root');
  assert.notEqual(norm(r.path), norm(join(M, '.taskorchestrator', 'config.yaml')));
});

test('S4: worktree .git file (relative gitdir) outside the repo resolves to the main checkout config', () => {
  const home = tmp('home');
  const { W, mainPath } = makeWorktree({ mainCfg: projectCfg('main-root', 'Main') });
  const cwd = join(W, 'src', 'deep');
  mkdirSync(cwd, { recursive: true });
  const r = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'project');
  assert.equal(norm(r.path), norm(mainPath));
  assert.equal(r.rootId, 'main-root');
});

test('S4: worktree .git file with an absolute gitdir resolves to the main checkout config', () => {
  const home = tmp('home');
  const { W, mainPath } = makeWorktree({ mainCfg: projectCfg('main-root', 'Main'), gitdirAbsolute: true });
  const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'project');
  assert.equal(norm(r.path), norm(mainPath));
});

test('S5: main-checkout config beats the user-level config', () => {
  const home = tmp('home');
  writeCfg(home, projectCfg('user-root', 'User'));
  const { W, mainPath } = makeWorktree({ mainCfg: projectCfg('main-root', 'Main') });
  const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(norm(r.path), norm(mainPath));
  assert.equal(r.scope, 'project');
  assert.equal(r.rootId, 'main-root');
});

test('S6: no project config anywhere, user config present -> scope user', () => {
  const home = tmp('home');
  const homeFile = writeCfg(home, projectCfg('user-root', 'User'));
  const cwd = tmp('cwd');
  const r = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'user');
  assert.equal(norm(r.path), norm(homeFile));
  assert.equal(r.rootId, 'user-root');
});

test('S7: cwd under the user home with no own config reports user, never project', () => {
  const home = tmp('home');
  const homeFile = writeCfg(home, projectCfg('user-root', 'User'));
  const cwd = join(home, 'proj');
  mkdirSync(cwd, { recursive: true });
  const r = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'user');
  assert.equal(norm(r.path), norm(homeFile));
});

test('S7: cwd equal to the user home itself reports user', () => {
  const home = tmp('home');
  writeCfg(home, projectCfg('user-root', 'User'));
  const r = locateConfig({ cwd: home, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'user');
});

test('S7 variant: a nested project config under home still wins as project over the user config', () => {
  const home = tmp('home');
  writeCfg(home, projectCfg('user-root', 'User'));
  const proj = join(home, 'proj');
  const pFile = writeCfg(proj, projectCfg('proj-root', 'Proj'));
  const r = locateConfig({ cwd: join(proj, 'sub'), env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'project');
  assert.equal(norm(r.path), norm(pFile));
});

test('S8: AGENT_CONFIG_DIR pointing at the user home reports user', () => {
  const home = tmp('home');
  writeCfg(home, projectCfg('user-root', 'User'));
  const cwd = tmp('cwd');
  const r = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: home, AGENT_CONFIG_DIR: home } });
  assert.equal(r.scope, 'user');
  assert.equal(r.rootId, 'user-root');
});

test('S10: nothing found -> scope none with every field null', () => {
  const home = tmp('home');
  const cwd = tmp('cwd');
  const r = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.deepEqual(r, { scope: 'none', path: null, bytes: null, text: null, rootId: null, name: null });
});

test('S11: config without a project block -> scope project, rootId and name null', () => {
  const home = tmp('home');
  const P = tmp('proj');
  const file = writeCfg(P, 'retrospective:\n  mode: nudge\n');
  const r = locateConfig({ cwd: P, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'project');
  assert.equal(norm(r.path), norm(file));
  assert.equal(r.rootId, null);
  assert.equal(r.name, null);
  assert.equal(r.text, 'retrospective:\n  mode: nudge\n');
});

test('S12: .git file without commondir (submodule shape) skips the main-checkout step, falls to user', () => {
  const home = tmp('home');
  const homeFile = writeCfg(home, projectCfg('user-root', 'User'));
  // Main checkout has a config that WOULD be found if commondir were ignored.
  const { W } = makeWorktree({ mainCfg: projectCfg('main-root', 'Main'), writeCommondir: false });
  const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'user');
  assert.equal(norm(r.path), norm(homeFile));
});

test('S12: .git file without commondir and no user config -> none, no throw', () => {
  const home = tmp('home');
  const { W } = makeWorktree({ mainCfg: projectCfg('main-root', 'Main'), writeCommondir: false });
  const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'none');
  assert.equal(r.path, null);
});

test('S12: malformed .git file (no gitdir: line) is skipped without throwing', () => {
  const home = tmp('home');
  const homeFile = writeCfg(home, projectCfg('user-root', 'User'));
  const W = tmp('wt');
  writeFileSync(join(W, '.git'), 'this is not a gitdir pointer\n');
  const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'user');
  assert.equal(norm(r.path), norm(homeFile));
});

test('S12: a .git DIRECTORY (ordinary checkout) does not trigger the main-checkout step', () => {
  const home = tmp('home');
  const P = tmp('plainrepo');
  mkdirSync(join(P, '.git'), { recursive: true });
  const r = locateConfig({ cwd: P, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'none');
});

test('S13: a directory named config.yaml is skipped; lookup continues to the user config', () => {
  const home = tmp('home');
  const homeFile = writeCfg(home, projectCfg('user-root', 'User'));
  const P = tmp('proj');
  mkdirSync(join(P, '.taskorchestrator', 'config.yaml'), { recursive: true });
  const r = locateConfig({ cwd: P, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.scope, 'user');
  assert.equal(norm(r.path), norm(homeFile));
});

test('S13: AGENT_CONFIG_DIR with no config falls through to the walk-up result', () => {
  const home = tmp('home');
  const X = tmp('x-empty');
  const P = tmp('proj');
  const pFile = writeCfg(P, projectCfg('p-root', 'P'));
  const r = locateConfig({ cwd: P, env: { TASK_ORCHESTRATOR_HOME: home, AGENT_CONFIG_DIR: X } });
  assert.equal(norm(r.path), norm(pFile));
  assert.equal(r.scope, 'project');
});

// ---- adversarial probes ----

test('probe: CRLF config still yields rootId and name', () => {
  const home = tmp('home');
  const P = tmp('proj');
  writeCfg(P, 'project:\r\n  rootId: crlf-root\r\n  name: Crlf Name\r\n');
  const r = locateConfig({ cwd: P, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(r.rootId, 'crlf-root');
  assert.equal(r.name, 'Crlf Name');
});

test('probe: quoted and bare rootId parse to the same bare value', () => {
  const home = tmp('home');
  const P1 = tmp('q');
  const P2 = tmp('b');
  writeCfg(P1, 'project:\n  rootId: "quoted-id"\n  name: "Q Name"\n');
  writeCfg(P2, 'project:\n  rootId: quoted-id\n  name: Q Name\n');
  const a = locateConfig({ cwd: P1, env: { TASK_ORCHESTRATOR_HOME: home } });
  const b = locateConfig({ cwd: P2, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(a.rootId, 'quoted-id');
  assert.equal(a.name, 'Q Name');
  assert.equal(b.rootId, 'quoted-id');
  assert.equal(b.name, 'Q Name');
});

test('probe: commondir with a trailing newline and an absolute commondir both resolve', () => {
  const home = tmp('home');
  const nl = makeWorktree({ mainCfg: projectCfg('main-root', 'Main'), commondir: '../..\n' });
  const r1 = locateConfig({ cwd: nl.W, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(norm(r1.path), norm(nl.mainPath));
  const abs = makeWorktree({ mainCfg: projectCfg('main2', 'Main2') });
  writeFileSync(join(abs.M, '.git', 'worktrees', 'w', 'commondir'), join(abs.M, '.git'));
  const r2 = locateConfig({ cwd: abs.W, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(norm(r2.path), norm(abs.mainPath));
});

test('probe: trailing separator on TASK_ORCHESTRATOR_HOME still identifies the user config as user', () => {
  const home = tmp('home');
  writeCfg(home, projectCfg('user-root', 'User'));
  const cwd = join(home, 'proj');
  mkdirSync(cwd, { recursive: true });
  const r = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: home + sep } });
  assert.equal(r.scope, 'user');
});

test('probe: win32 case-differing HOME vs cwd still reports user', { skip: process.platform !== 'win32' }, () => {
  // Skip is platform-conditional only: case-insensitive path equality is a win32 filesystem property.
  const home = tmp('home');
  writeCfg(home, projectCfg('user-root', 'User'));
  const cwd = join(home, 'proj');
  mkdirSync(cwd, { recursive: true });
  const r = locateConfig({ cwd: cwd.toLowerCase(), env: { TASK_ORCHESTRATOR_HOME: home.toUpperCase() } });
  assert.equal(r.scope, 'user');
});

test('probe: no caching - env and file mutation between calls are observed', () => {
  const homeA = tmp('homeA');
  const homeB = tmp('homeB');
  const cwd = tmp('cwd');
  writeCfg(homeA, projectCfg('a-root', 'A'));
  assert.equal(locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: homeA } }).rootId, 'a-root');
  assert.equal(locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: homeB } }).scope, 'none');
  writeCfg(homeA, projectCfg('a-root-2', 'A2'));
  assert.equal(locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: homeA } }).rootId, 'a-root-2');
});

test('probe: locateConfig defaults (no args) do not throw and return a well-formed result', () => {
  // O8 edit: TASK_ORCHESTRATOR_HOME is pinned to an empty temp dir in process.env (restored in
  // finally), so the no-arg defaults cannot be influenced by a real user-level config.
  const pinned = tmp('o8home');
  const saved = process.env.TASK_ORCHESTRATOR_HOME;
  process.env.TASK_ORCHESTRATOR_HOME = pinned;
  try {
    const r = locateConfig();
    assert.ok(['project', 'user', 'none'].includes(r.scope));
    assert.deepEqual(Object.keys(r).sort(), ['bytes', 'name', 'path', 'rootId', 'scope', 'text']);
    // Oracle: the declared defaults are cwd = process.cwd() and env = process.env.
    const explicit = locateConfig({ cwd: process.cwd(), env: process.env });
    assert.equal(r.scope, explicit.scope);
    assert.equal(r.path, explicit.path);
    assert.equal(r.text, explicit.text);
    assert.equal(r.rootId, explicit.rootId);
    assert.equal(r.name, explicit.name);
  } finally {
    if (saved === undefined) delete process.env.TASK_ORCHESTRATOR_HOME;
    else process.env.TASK_ORCHESTRATOR_HOME = saved;
  }
});

// ---- b2d81d68: TASK_ORCHESTRATOR_HOME replaces the home set (decision.md Ruling 1) ----
// Oracle: decision.md Ruling 1 (b) table + task-scope planner decisions; labels per test-plan.
// R = simulated env home (USERPROFILE and HOME set in-process) holding a config; E empty; E2 with config.

// Runs fn with the env home pinned to envHome (undefined = leave process env alone). os.userInfo()
// is mocked only when `account` is given: a path string returns that homedir, 'throw' throws, ''
// returns an empty homedir. With `account` undefined NO mock is installed, so the machine's real
// account home stays in the locator's home set. Everything is restored in finally.
function withHomes({ envHome, account, fn }) {
  const keys = ['USERPROFILE', 'HOME'];
  const saved = {};
  for (const k of keys) saved[k] = process.env[k];
  const m = account === undefined ? null : mock.method(os, 'userInfo', () => {
    if (account === 'throw') throw new Error('no passwd entry');
    return { homedir: account };
  });
  try {
    if (envHome !== undefined) for (const k of keys) process.env[k] = envHome;
    return fn();
  } finally {
    if (m) m.mock.restore();
    for (const k of keys) {
      if (saved[k] === undefined) delete process.env[k];
      else process.env[k] = saved[k];
    }
  }
}

// Linked worktree W (fresh) whose main checkout is the given directory M.
function worktreeOf(M, commondir = '../..') {
  const W = tmp('wt');
  mkdirSync(join(M, '.git', 'worktrees', 'w'), { recursive: true });
  writeFileSync(join(M, '.git', 'worktrees', 'w', 'commondir'), commondir);
  writeFileSync(join(W, '.git'), `gitdir: ${join(M, '.git', 'worktrees', 'w')}\n`);
  return W;
}

function simulatedHome() {
  const T = tmp('hT');
  const R = join(T, 'home');
  mkdirSync(R, { recursive: true });
  const cfg = writeCfg(R, projectCfg('real-home-root', 'RealHome'));
  const sub = join(R, 'sub');
  mkdirSync(sub, { recursive: true });
  return { T, R, cfg, sub };
}

test('b2d81d68 S1: override E, env home R holds config, cwd R/sub -> none', () => {
  const { R, sub } = simulatedHome();
  const E = tmp('E');
  withHomes({ envHome: R, fn: () => {
    const r = locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: E } });
    assert.deepEqual(r, { scope: 'none', path: null, bytes: null, text: null, rootId: null, name: null });
  } });
});

test('b2d81d68 S2: override E2 with config, cwd R/sub or R -> user at E2', () => {
  const { R, sub } = simulatedHome();
  const E2 = tmp('E2');
  const e2File = writeCfg(E2, projectCfg('e2-root', 'E2'));
  withHomes({ envHome: R, fn: () => {
    for (const cwd of [sub, R]) {
      const r = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: E2 } });
      assert.equal(r.scope, 'user');
      assert.equal(norm(r.path), norm(e2File));
      assert.equal(r.rootId, 'e2-root');
    }
  } });
});

test('b2d81d68 S3: override unset, cwd R/sub -> user at R', () => {
  const { R, cfg, sub } = simulatedHome();
  withHomes({ envHome: R, fn: () => {
    const r = locateConfig({ cwd: sub, env: {} });
    assert.equal(r.scope, 'user');
    assert.equal(norm(r.path), norm(cfg));
    assert.equal(r.rootId, 'real-home-root');
  } });
});

test('b2d81d68 S4: override E, AGENT_CONFIG_DIR = R -> project at R (step 1 is not filtered)', () => {
  const { R, cfg } = simulatedHome();
  const E = tmp('E');
  const cwd = tmp('cwd');
  withHomes({ envHome: R, fn: () => {
    const r = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: E, AGENT_CONFIG_DIR: R } });
    assert.equal(r.scope, 'project');
    assert.equal(norm(r.path), norm(cfg));
  } });
});

test('b2d81d68 S5: override E, linked worktree whose main checkout is R -> none', () => {
  const { R } = simulatedHome();
  const E = tmp('E');
  const W = worktreeOf(R);
  withHomes({ envHome: R, fn: () => {
    const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: E } });
    assert.equal(r.scope, 'none');
    assert.equal(r.path, null);
  } });
});

test('b2d81d68 S6: override E, project config in R parent, cwd R/sub -> project at the parent (walk-up climbs past R)', () => {
  const { T, R, sub } = simulatedHome();
  const E = tmp('E');
  const parentFile = writeCfg(T, projectCfg('parent-root', 'Parent'));
  withHomes({ envHome: R, fn: () => {
    const r = locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: E } });
    assert.equal(r.scope, 'project');
    assert.equal(norm(r.path), norm(parentFile));
    assert.equal(r.rootId, 'parent-root');
  } });
});

test('b2d81d68 S7: override E, nested project config under R -> project at the nested path', () => {
  const { R } = simulatedHome();
  const E = tmp('E');
  const nested = join(R, 'proj');
  const nestedFile = writeCfg(nested, projectCfg('nested-root', 'Nested'));
  const cwd = join(nested, 'sub');
  mkdirSync(cwd, { recursive: true });
  withHomes({ envHome: R, fn: () => {
    const r = locateConfig({ cwd, env: { TASK_ORCHESTRATOR_HOME: E } });
    assert.equal(r.scope, 'project');
    assert.equal(norm(r.path), norm(nestedFile));
  } });
});

test('b2d81d68 S8: env home Y empty, userInfo homedir = R (config), override E, cwd R/sub -> none', () => {
  const { T, R, sub } = simulatedHome();
  const Y = tmp('Y');
  const E = tmp('E');
  withHomes({ envHome: Y, account: R, fn: () => {
    const r = locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: T } });
    assert.equal(r.scope, 'none');
    assert.equal(r.path, null);
  } });
});

test('b2d81d68 S9: env home Y holds config, userInfo homedir = R (config), override unset, cwd R/sub -> user at Y', () => {
  const { T, R, sub } = simulatedHome();
  const Y = tmp('Y');
  const yFile = writeCfg(Y, projectCfg('y-root', 'Y'));
  withHomes({ envHome: Y, account: R, fn: () => {
    const r = locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_CEILING: T } });
    assert.equal(r.scope, 'user');
    assert.equal(norm(r.path), norm(yFile));
    assert.equal(r.rootId, 'y-root');
  } });
});

test('b2d81d68 S10: os.userInfo() throwing never throws out of locateConfig; S1-S3 results unchanged', () => {
  const { T, R, cfg, sub } = simulatedHome();
  const E = tmp('E');
  const E2 = tmp('E2');
  const e2File = writeCfg(E2, projectCfg('e2-root', 'E2'));
  withHomes({ envHome: R, account: 'throw', fn: () => {
    assert.equal(locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: T } }).scope, 'none');
    const two = locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: E2, TASK_ORCHESTRATOR_CEILING: T } });
    assert.equal(two.scope, 'user');
    assert.equal(norm(two.path), norm(e2File));
    const three = locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_CEILING: T } });
    assert.equal(three.scope, 'user');
    assert.equal(norm(three.path), norm(cfg));
  } });
});

test('b2d81d68 S11: env home = AGENT_CONFIG_DIR = cwd = X holding config, override E -> project at X', () => {
  const X = tmp('X');
  const xFile = writeCfg(X, projectCfg('x-root', 'X'));
  const E = tmp('E');
  withHomes({ envHome: X, fn: () => {
    const r = locateConfig({ cwd: X, env: { TASK_ORCHESTRATOR_HOME: E, AGENT_CONFIG_DIR: X } });
    assert.equal(r.scope, 'project');
    assert.equal(norm(r.path), norm(xFile));
    assert.equal(r.rootId, 'x-root');
  } });
});

function assertHomedirFailureKeepsProject(setup) {
  const P = tmp('proj');
  const pFile = writeCfg(P, projectCfg('p-root', 'P'));
  const C = tmp('cwdempty');
  const E2 = tmp('E2');
  const e2File = writeCfg(E2, projectCfg('e2-root', 'E2'));
  setup(() => {
    // User-level path is unavailable (override unset + homedir throws): every hit is project.
    const a = locateConfig({ cwd: P, env: {} });
    assert.equal(a.scope, 'project');
    assert.equal(norm(a.path), norm(pFile));
    assert.equal(a.rootId, 'p-root');
    // Override set: user-level path is computable without homedir -> user at E2.
    const b = locateConfig({ cwd: C, env: { TASK_ORCHESTRATOR_HOME: E2 } });
    assert.equal(b.scope, 'user');
    assert.equal(norm(b.path), norm(e2File));
  });
}

test('b2d81d68 S12: USERPROFILE="" makes os.homedir() throw (win32 only); project config still found',
  { skip: process.platform !== 'win32' }, () => {
    // Skip is a platform gate only: an empty USERPROFILE makes os.homedir() throw only on win32.
    assertHomedirFailureKeepsProject((run) => withHomes({ fn: () => {
      const saved = process.env.USERPROFILE;
      process.env.USERPROFILE = '';
      try { run(); } finally {
        if (saved === undefined) delete process.env.USERPROFILE;
        else process.env.USERPROFILE = saved;
      }
    } }));
  });

test('b2d81d68 S12b: os.homedir() mocked to throw; project config still found, override config is user', () => {
  assertHomedirFailureKeepsProject((run) => withHomes({ fn: () => {
    const m = mock.method(os, 'homedir', () => { throw new Error('ERR_SYSTEM_ERROR'); });
    try { run(); } finally { m.mock.restore(); }
  } }));
});

test('b2d81d68 S13: bare-repo worktree (common dir basename is not .git) skips the main-checkout step', () => {
  const T = tmp('bare');
  writeCfg(T, projectCfg('bare-parent', 'BareParent'));
  const bare = join(T, 'repo.git');
  mkdirSync(join(bare, 'worktrees', 'w'), { recursive: true });
  writeFileSync(join(bare, 'worktrees', 'w', 'commondir'), '../..');
  const W = tmp('wt');
  writeFileSync(join(W, '.git'), `gitdir: ${join(bare, 'worktrees', 'w')}\n`);
  const E = tmp('E');
  withHomes({ fn: () => {
    const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: E } });
    assert.equal(r.scope, 'none');
    assert.equal(r.path, null);
  } });
});

test('b2d81d68 S14: commondir with a trailing separator still resolves the main checkout config', () => {
  const home = tmp('home');
  const { W, mainPath } = makeWorktree({ mainCfg: projectCfg('main-root', 'Main'), commondir: '../..' + sep });
  withHomes({ fn: () => {
    const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: home } });
    assert.equal(r.scope, 'project');
    assert.equal(norm(r.path), norm(mainPath));
    assert.equal(r.rootId, 'main-root');
  } });
});

test('b2d81d68 S15: relative AGENT_CONFIG_DIR resolves against the cwd argument, not process.cwd()', () => {
  const P = tmp('proj');
  const file = writeCfg(join(P, 'cfg'), projectCfg('rel-root', 'Rel'));
  const home = tmp('home');
  assert.notEqual(norm(process.cwd()), norm(P));
  withHomes({ fn: () => {
    const r = locateConfig({ cwd: P, env: { TASK_ORCHESTRATOR_HOME: home, AGENT_CONFIG_DIR: 'cfg' } });
    assert.equal(r.scope, 'project');
    assert.equal(norm(r.path), norm(file));
    assert.equal(r.rootId, 'rel-root');
  } });
});

test('b2d81d68 S17: userClientPath is <home>/.taskorchestrator/client.json, absolute, separator-insensitive', () => {
  const H = tmp('home');
  const expected = resolve(H, '.taskorchestrator', 'client.json');
  const p = userClientPath({ TASK_ORCHESTRATOR_HOME: H });
  assert.equal(norm(p), norm(expected));
  assert.equal(resolve(p), p);
  assert.equal(norm(userClientPath({ TASK_ORCHESTRATOR_HOME: H + sep })), norm(expected));
  // Same home rule as userConfigPath: they live in the same directory.
  assert.equal(norm(dirname(p)), norm(dirname(userConfigPath({ TASK_ORCHESTRATOR_HOME: H }))));
});

test('b2d81d68 S17: userClientPath without an override uses os.homedir()', () => {
  assert.equal(norm(userClientPath({})), norm(resolve(homedir(), '.taskorchestrator', 'client.json')));
  assert.equal(norm(userClientPath({ TASK_ORCHESTRATOR_HOME: '' })), norm(resolve(homedir(), '.taskorchestrator', 'client.json')));
});

// ---- b2d81d68 adversarial probes ----

test('b2d81d68 probe: empty-string override behaves as unset (user at R)', () => {
  const { R, cfg, sub } = simulatedHome();
  withHomes({ envHome: R, fn: () => {
    const r = locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: '' } });
    assert.equal(r.scope, 'user');
    assert.equal(norm(r.path), norm(cfg));
  } });
});

test('b2d81d68 probe: override equal to the env home gives user at that home (no double effect)', () => {
  const { R, cfg, sub } = simulatedHome();
  withHomes({ envHome: R, fn: () => {
    const r = locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: R } });
    assert.equal(r.scope, 'user');
    assert.equal(norm(r.path), norm(cfg));
  } });
});

test('b2d81d68 probe: trailing separator on the env home still filters the real-home config', () => {
  const { R, sub } = simulatedHome();
  const E = tmp('E');
  withHomes({ envHome: R + sep, fn: () => {
    const r = locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: E } });
    assert.equal(r.scope, 'none');
  } });
});

test('b2d81d68 probe: os.userInfo() homedir empty string is ignored, not treated as a home', () => {
  const { T, R, sub } = simulatedHome();
  const E = tmp('E');
  const P = tmp('proj');
  const pFile = writeCfg(P, projectCfg('p-root', 'P'));
  withHomes({ envHome: R, account: '', fn: () => {
    assert.equal(locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: T } }).scope, 'none');
    const r = locateConfig({ cwd: P, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: T } });
    assert.equal(r.scope, 'project');
    assert.equal(norm(r.path), norm(pFile));
  } });
});

test('b2d81d68 probe: win32 case-differing env home spelling still filters the real-home config',
  { skip: process.platform !== 'win32' }, () => {
    // Skip is a platform gate only: case-insensitive path equality is a win32 filesystem property.
    const { R, sub } = simulatedHome();
    const E = tmp('E');
    withHomes({ envHome: R.toUpperCase(), fn: () => {
      const r = locateConfig({ cwd: sub.toLowerCase(), env: { TASK_ORCHESTRATOR_HOME: E } });
      assert.equal(r.scope, 'none');
    } });
  });

test('b2d81d68 probe: repeated lookup is idempotent and observes a config appearing at R mid-sequence', () => {
  const T = tmp('hT');
  const R = join(T, 'home');
  const sub = join(R, 'sub');
  mkdirSync(sub, { recursive: true });
  const E = tmp('E');
  withHomes({ envHome: R, fn: () => {
    assert.equal(locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: E } }).scope, 'none');
    assert.equal(locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: E } }).scope, 'none');
    writeCfg(R, projectCfg('late-root', 'Late'));
    // A config at the (filtered) real home never becomes a project hit under an override.
    assert.equal(locateConfig({ cwd: sub, env: { TASK_ORCHESTRATOR_HOME: E } }).scope, 'none');
    assert.equal(locateConfig({ cwd: sub, env: {} }).rootId, 'late-root');
  } });
});

test('b2d81d68 probe: BOM + CRLF config.yaml at the override still yields user scope with rootId', () => {
  const E2 = tmp('E2');
  const C = tmp('cwdempty');
  writeCfg(E2, '﻿project:\r\n  rootId: bom-root\r\n  name: Bom\r\n');
  withHomes({ fn: () => {
    const r = locateConfig({ cwd: C, env: { TASK_ORCHESTRATOR_HOME: E2 } });
    assert.equal(r.scope, 'user');
    assert.equal(r.rootId, 'bom-root');
    assert.equal(r.name, 'Bom');
  } });
});

// ---- b2d81d68 amendment: opt-in walk ceiling TASK_ORCHESTRATOR_CEILING (amendment-ceiling.md; task-scope C1-C5) ----
// Oracle: AM s1 "stop BEFORE examining that directory" (ceiling dir and above never checked by the
// walk-up and the .git search; steps 1 and 4 unaffected; non-ancestor ceiling has no effect;
// unset/empty = no ceiling) + task-scope D-a (cwd == ceiling -> nothing examined) and D-b (a resolved
// main checkout at/above the ceiling is discarded; a sibling is not). Fixture K: T fresh with a config
// at T (t-root) and at T/a (a-root), dirs T/a/b/c; override E (empty) in every env.

function ceilingFixture() {
  const T = tmp('cT');
  const tFile = writeCfg(T, projectCfg('t-root', 'T'));
  const A = join(T, 'a');
  const aFile = writeCfg(A, projectCfg('a-root', 'A'));
  const B = join(A, 'b');
  const C = join(B, 'c');
  mkdirSync(C, { recursive: true });
  const E = tmp('E');
  return { T, tFile, A, aFile, B, C, E };
}

const NONE = { scope: 'none', path: null, bytes: null, text: null, rootId: null, name: null };

test('b2d81d68 S21: ceiling T/a, cwd T/a/b -> none (the ceiling directory and above are never checked)', () => {
  const { A, B, E } = ceilingFixture();
  const r = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: A } });
  assert.deepEqual(r, NONE);
});

test('b2d81d68 S22: ceiling T/a, config at T/a/b, cwd T/a/b/c -> project at T/a/b (directories below the ceiling are examined)', () => {
  const { A, B, C, E } = ceilingFixture();
  const bFile = writeCfg(B, projectCfg('b-root', 'B'));
  const r = locateConfig({ cwd: C, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: A } });
  assert.equal(r.scope, 'project');
  assert.equal(norm(r.path), norm(bFile));
  assert.equal(r.rootId, 'b-root');
});

test('b2d81d68 S23: ceiling unset, cwd T/a/b -> project at T/a', () => {
  const { aFile, B, E } = ceilingFixture();
  const r = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E } });
  assert.equal(r.scope, 'project');
  assert.equal(norm(r.path), norm(aFile));
  assert.equal(r.rootId, 'a-root');
});

test('b2d81d68 S24: ceiling empty string behaves as unset -> project at T/a', () => {
  const { aFile, B, E } = ceilingFixture();
  const r = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: '' } });
  assert.equal(r.scope, 'project');
  assert.equal(norm(r.path), norm(aFile));
  assert.equal(r.rootId, 'a-root');
});

test('b2d81d68 S25: a ceiling that is not an ancestor of the cwd has no effect', () => {
  const { aFile, B, C, E } = ceilingFixture();
  const Z = tmp('Z');
  const sibling = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: Z } });
  assert.equal(sibling.scope, 'project');
  assert.equal(norm(sibling.path), norm(aFile));
  // A ceiling BELOW the cwd (a descendant) is never met by the upward walk either.
  const below = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: C } });
  assert.equal(below.scope, 'project');
  assert.equal(norm(below.path), norm(aFile));
});

test('b2d81d68 S26: cwd equal to the ceiling -> none (nothing at or above cwd is examined)', () => {
  const { A, E } = ceilingFixture();
  const r = locateConfig({ cwd: A, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: A } });
  assert.deepEqual(r, NONE);
});

test('b2d81d68 S27: the ceiling does not bound step 1 (AGENT_CONFIG_DIR) or step 4 (user-level path)', () => {
  const { T, tFile, A, B, E } = ceilingFixture();
  const viaAgentDir = locateConfig({
    cwd: B, env: { TASK_ORCHESTRATOR_HOME: E, AGENT_CONFIG_DIR: T, TASK_ORCHESTRATOR_CEILING: A },
  });
  assert.equal(viaAgentDir.scope, 'project');
  assert.equal(norm(viaAgentDir.path), norm(tFile));
  assert.equal(viaAgentDir.rootId, 't-root');
  const E2 = tmp('E2');
  const e2File = writeCfg(E2, projectCfg('e2-root', 'E2'));
  const viaUser = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E2, TASK_ORCHESTRATOR_CEILING: A } });
  assert.equal(viaUser.scope, 'user');
  assert.equal(norm(viaUser.path), norm(e2File));
  assert.equal(viaUser.rootId, 'e2-root');
});

test('b2d81d68 S28: step 3 stops before a .git file in the ceiling directory (no config in the T tree; control without ceiling finds the main checkout)', () => {
  const T = tmp('cT');
  const A = join(T, 'a');
  const B = join(A, 'b');
  mkdirSync(B, { recursive: true });
  const M = tmp('main');
  mkdirSync(join(M, '.git', 'worktrees', 'w'), { recursive: true });
  writeFileSync(join(M, '.git', 'worktrees', 'w', 'commondir'), '../..');
  const mFile = writeCfg(M, projectCfg('main-root', 'Main'));
  writeFileSync(join(A, '.git'), `gitdir: ${join(M, '.git', 'worktrees', 'w')}\n`);
  const E = tmp('E');
  const control = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E } });
  assert.equal(control.scope, 'project');
  assert.equal(norm(control.path), norm(mFile));
  assert.equal(control.rootId, 'main-root');
  const r = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: A } });
  assert.deepEqual(r, NONE);
});

test('b2d81d68 S29: a resolved main checkout at or above the ceiling is discarded', () => {
  const M = tmp('main');
  mkdirSync(join(M, '.git', 'worktrees', 'w'), { recursive: true });
  writeFileSync(join(M, '.git', 'worktrees', 'w', 'commondir'), '../..');
  writeCfg(M, projectCfg('main-root', 'Main'));
  const wtDir = join(M, 'wt');
  const W = join(wtDir, 'x');
  mkdirSync(W, { recursive: true });
  writeFileSync(join(W, '.git'), `gitdir: ${join(M, '.git', 'worktrees', 'w')}\n`);
  const E = tmp('E');
  const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: wtDir } });
  assert.deepEqual(r, NONE);
});

test('b2d81d68 S30: step 3 below the ceiling still resolves a sibling main checkout', () => {
  const T2 = tmp('cT2');
  const W = join(T2, 'x');
  mkdirSync(W, { recursive: true });
  const M = tmp('main');
  mkdirSync(join(M, '.git', 'worktrees', 'w'), { recursive: true });
  writeFileSync(join(M, '.git', 'worktrees', 'w', 'commondir'), '../..');
  const mFile = writeCfg(M, projectCfg('main-root', 'Main'));
  writeFileSync(join(W, '.git'), `gitdir: ${join(M, '.git', 'worktrees', 'w')}\n`);
  const E = tmp('E');
  const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: T2 } });
  assert.equal(r.scope, 'project');
  assert.equal(norm(r.path), norm(mFile));
  assert.equal(r.rootId, 'main-root');
});

// ---- b2d81d68 ceiling probes ----

test('b2d81d68 probe: trailing separator on the ceiling still bounds the walk', () => {
  const { A, B, E } = ceilingFixture();
  const r = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: A + sep } });
  assert.deepEqual(r, NONE);
});

test('b2d81d68 probe: win32 case-differing ceiling spelling still bounds the walk',
  { skip: process.platform !== 'win32' }, () => {
    // Skip is a platform gate only: case-insensitive path equality is a win32 filesystem property.
    const { A, B, E } = ceilingFixture();
    const r = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: A.toUpperCase() } });
    assert.deepEqual(r, NONE);
  });

test('b2d81d68 probe: a relative ceiling resolves against the cwd argument (.. from T/a/b is T/a)', () => {
  const { B, E } = ceilingFixture();
  const r = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: '..' } });
  assert.deepEqual(r, NONE);
});

test('b2d81d68 probe: a ceiling at the filesystem root does not throw and does not hide T/a', () => {
  const { aFile, B, E } = ceilingFixture();
  const r = locateConfig({ cwd: B, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: parse(B).root } });
  assert.equal(r.scope, 'project');
  assert.equal(norm(r.path), norm(aFile));
});

// ---- b2d81d68 review fix B1: a ceiling that is not an ancestor of the cwd has no effect ----
// Oracle: AM "A ceiling that is not an ancestor of the cwd has no effect on that lookup" (task-scope C5).

function gitMain(parent, name) {
  const M = join(parent, name);
  mkdirSync(join(M, '.git'), { recursive: true });
  return M;
}

test('b2d81d68 S31: linked worktree outside the main checkout; ceiling at M or inside M is not an ancestor of cwd -> project at M', () => {
  const M = tmp('main');
  mkdirSync(join(M, '.git'), { recursive: true });
  const mFile = writeCfg(M, projectCfg('main-root', 'Main'));
  mkdirSync(join(M, 'sub'), { recursive: true });
  const W = worktreeOf(M);
  const E = tmp('E');
  for (const ceiling of [undefined, M, join(M, 'sub')]) {
    const env = { TASK_ORCHESTRATOR_HOME: E };
    if (ceiling !== undefined) env.TASK_ORCHESTRATOR_CEILING = ceiling;
    const r = locateConfig({ cwd: W, env });
    assert.equal(r.scope, 'project', String(ceiling));
    assert.equal(norm(r.path), norm(mFile), String(ceiling));
    assert.equal(r.rootId, 'main-root', String(ceiling));
  }
});

test('b2d81d68 S32: ceiling at the common parent of the main checkout and the worktree -> project at M', () => {
  const P = tmp('P');
  const M = gitMain(P, 'm');
  const mFile = writeCfg(M, projectCfg('main-root', 'Main'));
  mkdirSync(join(M, '.git', 'worktrees', 'w'), { recursive: true });
  writeFileSync(join(M, '.git', 'worktrees', 'w', 'commondir'), '../..');
  const W = join(P, 'w');
  mkdirSync(W, { recursive: true });
  writeFileSync(join(W, '.git'), `gitdir: ${join(M, '.git', 'worktrees', 'w')}\n`);
  const E = tmp('E');
  const r = locateConfig({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: P } });
  assert.equal(r.scope, 'project');
  assert.equal(norm(r.path), norm(mFile));
  assert.equal(r.rootId, 'main-root');
});

// ---- 4e15b651: projectClientPath ----
// Oracle: task-scope R3 (client.json sits in the directory of the located config, only when
// locateConfig().scope is 'project'; the locator's rules apply unchanged); test-plan S16-S20.

test('4e15b651 S16: project config found by walk-up from a nested cwd -> <P>/.taskorchestrator/client.json, absolute, file absent', () => {
  const home = tmp('home');
  const P = tmp('proj');
  writeCfg(P, projectCfg('p-root', 'P'));
  const cwd = join(P, 'a', 'b');
  mkdirSync(cwd, { recursive: true });
  const p = projectClientPath({ cwd, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(norm(p), norm(join(P, '.taskorchestrator', 'client.json')));
  assert.equal(resolve(p), p);
  assert.equal(existsSync(p), false);
});

test('4e15b651 S17: user scope, no config, or AGENT_CONFIG_DIR at the user home -> null', () => {
  const home = tmp('home');
  writeCfg(home, projectCfg('user-root', 'User'));
  const cwd = tmp('cwd');
  assert.equal(projectClientPath({ cwd, env: { TASK_ORCHESTRATOR_HOME: home } }), null);
  const emptyHome = tmp('emptyhome');
  assert.equal(projectClientPath({ cwd, env: { TASK_ORCHESTRATOR_HOME: emptyHome } }), null);
  assert.equal(projectClientPath({ cwd, env: { TASK_ORCHESTRATOR_HOME: home, AGENT_CONFIG_DIR: home } }), null);
});

test('4e15b651 S18: linked worktree without a config -> main checkout path; worktree with its own config -> the worktree path', () => {
  const home = tmp('home');
  const { M, W } = makeWorktree({ mainCfg: projectCfg('main-root', 'Main') });
  const viaMain = projectClientPath({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(norm(viaMain), norm(join(M, '.taskorchestrator', 'client.json')));
  writeCfg(W, projectCfg('wt-root', 'Wt'));
  const own = projectClientPath({ cwd: W, env: { TASK_ORCHESTRATOR_HOME: home } });
  assert.equal(norm(own), norm(join(W, '.taskorchestrator', 'client.json')));
});

test('4e15b651 S19: an AGENT_CONFIG_DIR hit beats a walk-up config -> path next to it', () => {
  const home = tmp('home');
  const X = tmp('x');
  const P = tmp('proj');
  writeCfg(X, projectCfg('x-root', 'X'));
  writeCfg(P, projectCfg('p-root', 'P'));
  const p = projectClientPath({ cwd: P, env: { TASK_ORCHESTRATOR_HOME: home, AGENT_CONFIG_DIR: X } });
  assert.equal(norm(p), norm(join(X, '.taskorchestrator', 'client.json')));
});

test('4e15b651 S20: cwd equal to TASK_ORCHESTRATOR_CEILING -> null; without the ceiling the same cwd yields its path', () => {
  const { A, E } = ceilingFixture();
  assert.equal(projectClientPath({ cwd: A, env: { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: A } }), null);
  const control = projectClientPath({ cwd: A, env: { TASK_ORCHESTRATOR_HOME: E } });
  assert.equal(norm(control), norm(join(A, '.taskorchestrator', 'client.json')));
});

// ---- 4e15b651 amendment A1: projectClientCandidates ----
// Oracle: task-scope "Amendment A1" rules A1.1-A1.6; test-plan S29-S34. The list is the ordered
// project-step files: [client.json beside the located config, <main>/.taskorchestrator/client.json
// when that config sits in a linked worktree]; [] when the located scope is not 'project'. Every case
// also asserts the invariant projectClientPath(args) === (list[0] ?? null) (A1.6).
// Fixture G: T = ceiling (fresh); M = T/main (.git directory, worktrees/w/commondir); W = T/<wtPath>
// a linked worktree (.git file -> M/.git/worktrees/w) with its own config unless told otherwise.

function fixtureGL({ wtPath = 'wt', wtConfig = true, mainConfig = false, gitdirAbsolute = true, commondir = '../..' } = {}) {
  const T = tmp('gT');
  const M = join(T, 'main');
  mkdirSync(join(M, '.git', 'worktrees', 'w'), { recursive: true });
  if (commondir !== null) writeFileSync(join(M, '.git', 'worktrees', 'w', 'commondir'), commondir);
  if (mainConfig) writeCfg(M, projectCfg('main-root', 'Main'));
  const W = join(T, ...wtPath.split('/'));
  mkdirSync(W, { recursive: true });
  const gitdirAbs = join(M, '.git', 'worktrees', 'w');
  writeFileSync(join(W, '.git'), `gitdir: ${gitdirAbsolute ? gitdirAbs : relative(W, gitdirAbs)}\n`);
  if (wtConfig) writeCfg(W, projectCfg('wt-root', 'Wt'));
  const E = tmp('E');
  mkdirSync(join(W, 'sub'), { recursive: true });
  return { T, M, W, E, sub: join(W, 'sub') };
}

const clientOf = (dir) => join(dir, '.taskorchestrator', 'client.json');

function listOf(cwd, env) {
  const list = projectClientCandidates({ cwd, env });
  assert.ok(Array.isArray(list));
  assert.equal(projectClientPath({ cwd, env }), list.length ? list[0] : null, 'projectClientPath is the first entry, or null for an empty list');
  return list;
}

function assertList(list, expected, label) {
  assert.deepEqual(list.map(norm), expected.map(norm), label);
  for (const p of list) assert.equal(resolve(p), p, `${label} absolute`);
}

test('4e15b651 S29: linked worktree with its own config, main checkout without .taskorchestrator -> [worktree file, main file], absolute, neither exists', () => {
  for (const gitdirAbsolute of [true, false]) {
    const { T, M, W, E, sub } = fixtureGL({ gitdirAbsolute });
    const list = listOf(sub, { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: T });
    assertList(list, [clientOf(W), clientOf(M)], `gitdirAbsolute=${gitdirAbsolute}`);
    assert.equal(list.length, 2);
    for (const p of list) assert.equal(existsSync(p), false);
  }
});

test('4e15b651 S30: single-file and empty results - walk-up config without a linked-worktree main, user scope, no config, AGENT_CONFIG_DIR at the user home, worktree without a config', () => {
  const E = tmp('E');
  const T = tmp('pT');
  const P = join(T, 'p');
  writeCfg(P, projectCfg('p-root', 'P'));
  const nested = join(P, 'a', 'b');
  mkdirSync(nested, { recursive: true });
  const env = { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: T };
  assertList(listOf(nested, env), [clientOf(P)], 'no .git anywhere');
  mkdirSync(join(P, '.git'), { recursive: true });
  assertList(listOf(nested, env), [clientOf(P)], '.git directory at P');

  const userHome = tmp('uhome');
  writeCfg(userHome, projectCfg('user-root', 'User'));
  const cwd = join(T, 'plain');
  mkdirSync(cwd, { recursive: true });
  assert.deepEqual(listOf(cwd, { TASK_ORCHESTRATOR_HOME: userHome, TASK_ORCHESTRATOR_CEILING: T }), [], 'user scope');
  assert.deepEqual(listOf(cwd, { TASK_ORCHESTRATOR_HOME: E, TASK_ORCHESTRATOR_CEILING: T }), [], 'no config');
  assert.deepEqual(
    listOf(cwd, { TASK_ORCHESTRATOR_HOME: userHome, TASK_ORCHESTRATOR_CEILING: T, AGENT_CONFIG_DIR: userHome }),
    [],
    'AGENT_CONFIG_DIR at the user home',
  );

  const g = fixtureGL({ wtConfig: false, mainConfig: true });
  assertList(listOf(g.sub, { TASK_ORCHESTRATOR_HOME: g.E, TASK_ORCHESTRATOR_CEILING: g.T }), [clientOf(g.M)], 'worktree without a config -> main only');
});

test('4e15b651 S31: the ceiling bounds the main-checkout entry only when it is the cwd or an ancestor and the main checkout is at or above it', () => {
  // W inside M: ceiling M/wts or M drops the main entry; ceiling T keeps it.
  {
    const fx = fixtureGL({ wtPath: 'main/wts/x' });
    const base = { TASK_ORCHESTRATOR_HOME: fx.E };
    assertList(listOf(fx.sub, { ...base, TASK_ORCHESTRATOR_CEILING: join(fx.M, 'wts') }), [clientOf(fx.W)], 'ceiling M/wts');
    assertList(listOf(fx.sub, { ...base, TASK_ORCHESTRATOR_CEILING: fx.M }), [clientOf(fx.W)], 'ceiling M');
    assertList(listOf(fx.sub, { ...base, TASK_ORCHESTRATOR_CEILING: fx.T }), [clientOf(fx.W), clientOf(fx.M)], 'ceiling T');
  }
  // W outside M: a ceiling at M or inside M is not an ancestor of the cwd -> no effect.
  {
    const fx = fixtureGL();
    mkdirSync(join(fx.M, 'sub'), { recursive: true });
    for (const ceiling of [fx.T, fx.M, join(fx.M, 'sub')]) {
      assertList(
        listOf(fx.sub, { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: ceiling }),
        [clientOf(fx.W), clientOf(fx.M)],
        `ceiling ${ceiling}`,
      );
    }
  }
  // Config in a subdirectory W/pkg of the worktree, cwd below it: the .git search starts at W/pkg.
  {
    const fx = fixtureGL({ wtConfig: false });
    const pkg = join(fx.W, 'pkg');
    writeCfg(pkg, projectCfg('pkg-root', 'Pkg'));
    const deep = join(pkg, 'x');
    mkdirSync(deep, { recursive: true });
    assertList(listOf(deep, { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.W }), [clientOf(pkg)], 'ceiling W stops the .git search before W');
    assertList(listOf(deep, { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.T }), [clientOf(pkg), clientOf(fx.M)], 'ceiling T');
  }
});

test('4e15b651 S32: a main checkout that is a home-level directory (env home or override home) is dropped; otherwise kept', () => {
  const fx = fixtureGL({ mainConfig: true });
  const wFirst = [clientOf(fx.W)];
  withHomes({ envHome: fx.M, fn: () => {
    assertList(listOf(fx.sub, { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.T }), wFirst, 'M is the USERPROFILE/HOME home');
  } });
  assertList(listOf(fx.sub, { TASK_ORCHESTRATOR_HOME: fx.M, TASK_ORCHESTRATOR_CEILING: fx.T }), wFirst, 'M is the override home');
  assertList(
    listOf(fx.sub, { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.T }),
    [clientOf(fx.W), clientOf(fx.M)],
    'neither',
  );
});

test('4e15b651 S33: AGENT_CONFIG_DIR naming the located config pins the list to one file; a dir without a config leaves the fallback on', () => {
  const fx = fixtureGL();
  const env = { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.T };
  assertList(listOf(fx.sub, { ...env, AGENT_CONFIG_DIR: fx.W }), [clientOf(fx.W)], 'absolute AGENT_CONFIG_DIR = W');
  assertList(listOf(fx.W, { ...env, AGENT_CONFIG_DIR: '.' }), [clientOf(fx.W)], 'AGENT_CONFIG_DIR = . with cwd W');
  const empty = join(fx.T, 'emptycfg');
  mkdirSync(empty, { recursive: true });
  assertList(listOf(fx.sub, { ...env, AGENT_CONFIG_DIR: empty }), [clientOf(fx.W), clientOf(fx.M)], 'AGENT_CONFIG_DIR at a dir with no config, walk-up hit at W');
});

test('4e15b651 S34: no derivable main checkout, or one equal to the config dir -> only the file beside the config, no throw', () => {
  const envOf = (fx) => ({ TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.T });
  {
    const fx = fixtureGL({ commondir: null });
    assertList(listOf(fx.sub, envOf(fx)), [clientOf(fx.W)], '.git file without commondir');
  }
  {
    const fx = fixtureGL({ commondir: '..' });
    assertList(listOf(fx.sub, envOf(fx)), [clientOf(fx.W)], 'common dir basename is not .git');
  }
  {
    const fx = fixtureGL();
    writeFileSync(join(fx.W, '.git'), 'this is not a gitdir pointer\n');
    assertList(listOf(fx.sub, envOf(fx)), [clientOf(fx.W)], '.git file without gitdir:');
  }
  {
    const fx = fixtureGL();
    const N = join(fx.W, 'clone');
    mkdirSync(join(N, '.git'), { recursive: true });
    writeCfg(N, projectCfg('clone-root', 'Clone'));
    mkdirSync(join(N, 'sub'), { recursive: true });
    assertList(listOf(join(N, 'sub'), envOf(fx)), [clientOf(N)], 'clone nested in a linked worktree');
  }
  {
    const fx = fixtureGL({ wtPath: 'p/w', wtConfig: false });
    const P = join(fx.T, 'p');
    writeCfg(P, projectCfg('p-root', 'P'));
    assertList(listOf(fx.sub, envOf(fx)), [clientOf(P)], 'config in a non-git parent of the worktree');
  }
  {
    const fx = fixtureGL();
    const gd = join(fx.T, 'gd');
    mkdirSync(gd, { recursive: true });
    writeFileSync(join(gd, 'commondir'), join(fx.W, '.git'));
    writeFileSync(join(fx.W, '.git'), `gitdir: ${gd}\n`);
    assertList(listOf(fx.sub, envOf(fx)), [clientOf(fx.W)], 'commondir resolves to the config dir own .git (duplicate dropped)');
  }
});

// ---- 4e15b651 A1 probes ----

test('4e15b651 A1 probe: a commondir file with a trailing newline still resolves the main checkout', () => {
  const fx = fixtureGL({ commondir: '../..\n' });
  assertList(listOf(fx.sub, { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.T }), [clientOf(fx.W), clientOf(fx.M)], 'trailing LF');
  const fx2 = fixtureGL({ commondir: '../..\r\n' });
  assertList(listOf(fx2.sub, { TASK_ORCHESTRATOR_HOME: fx2.E, TASK_ORCHESTRATOR_CEILING: fx2.T }), [clientOf(fx2.W), clientOf(fx2.M)], 'trailing CRLF');
});

test('4e15b651 A1 probe: a trailing separator on the ceiling and on the cwd does not change the list', () => {
  const fx = fixtureGL({ wtPath: 'main/wts/x' });
  assertList(listOf(fx.sub, { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.M + sep }), [clientOf(fx.W)], 'ceiling M/');
  assertList(listOf(fx.sub, { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.T + sep }), [clientOf(fx.W), clientOf(fx.M)], 'ceiling T/');
  assertList(listOf(fx.sub + sep, { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.T }), [clientOf(fx.W), clientOf(fx.M)], 'cwd with trailing separator');
});

test('4e15b651 A1 probe: win32 case-differing ceiling and cwd spellings give the same list',
  { skip: process.platform !== 'win32' }, () => {
    // Skip is a platform gate only: case-insensitive path equality is a win32 filesystem property.
    const fx = fixtureGL({ wtPath: 'main/wts/x' });
    assertList(listOf(fx.sub, { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.M.toUpperCase() }), [clientOf(fx.W)], 'ceiling upper-cased');
    assertList(listOf(fx.sub.toUpperCase(), { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.T }), [clientOf(fx.W), clientOf(fx.M)], 'cwd upper-cased');
  });

test('4e15b651 A1 probe: repeated calls agree and the list is stable', () => {
  const fx = fixtureGL();
  const env = { TASK_ORCHESTRATOR_HOME: fx.E, TASK_ORCHESTRATOR_CEILING: fx.T };
  const a = listOf(fx.sub, env);
  const b = listOf(fx.sub, env);
  assert.deepEqual(a, b);
  assert.equal(a.length, 2);
});
