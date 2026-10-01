// Coverage for config-locator.mjs: userHome, userConfigPath, locateConfig.
// Oracle: task-scope note + plan section A (lookup order AGENT_CONFIG_DIR -> cwd walk-up
// (skipping the user-level path) -> main checkout via .git file gitdir/commondir -> user-level
// config). Every test pins TASK_ORCHESTRATOR_HOME to an empty temp dir through the env argument.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, realpathSync, rmSync } from 'node:fs';
import { tmpdir, homedir } from 'node:os';
import { join, resolve, relative, sep } from 'node:path';
import { userHome, userConfigPath, locateConfig } from '../config-locator.mjs';

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
  const r = locateConfig();
  assert.ok(['project', 'user', 'none'].includes(r.scope));
  assert.deepEqual(Object.keys(r).sort(), ['bytes', 'name', 'path', 'rootId', 'scope', 'text']);
});
