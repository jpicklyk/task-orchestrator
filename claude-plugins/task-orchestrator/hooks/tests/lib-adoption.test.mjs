// Structural contract for the hooks/lib adoption (fc131c71, scenario S21). Oracle: task-scope
// AC1 (pure at import), AC2 (stdin and emit plumbing lives in lib), AC3 (marker implementations delegate
// to lib/markers.mjs), AC7 (the api-client.mjs shim, deleted by eb262d39 AC8), AC8 (hand-written tool-names list with a P9 TODO), AC9 (README row per lib module, a
// CHANGELOG [4.0.0] ### Plugin bullet) and decision D3 (session-start imports server-compat; per AC2 of 0bbdc4cc also the envelope readers dispatch-hint and phase-guard-record).
// Source scans read hook files as text; they do not execute them.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, mkdtempSync, rmSync, existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HOOKS_DIR = fileURLToPath(new URL('..', import.meta.url));
const LIB_DIR = join(HOOKS_DIR, 'lib');
const REPO_ROOT = fileURLToPath(new URL('../../../../', import.meta.url));

const read = (p) => readFileSync(p, 'utf-8');
const hookSource = (name) => read(join(HOOKS_DIR, name));
const topLevelHookFiles = () => readdirSync(HOOKS_DIR).filter((f) => f.endsWith('.mjs'));

const LIB_MODULES = ['stdin', 'emit', 'markers', 'envelope', 'ops', 'api-client', 'server-compat', 'tool-names'];

const STDIN_READERS = [
  'config-sync', 'dispatch-hint', 'dispatch-model-guard', 'phase-guard-record',
  'phase-guard', 'plan-capture', 'retro-backstop', 'retro-trigger', 'skill-enforcement', 'subagent-start',
];
const AC2_EMITTERS = [...STDIN_READERS, 'session-start', 'orchestration-context', 'pre-plan', 'post-plan'];
const MARKER_USERS = ['retro-lib', 'skill-enforcement', 'phase-guard-record', 'session-start'];

function importsNamed(src, name, from) {
  const re = new RegExp(`import\\s*\\{[^}]*\\b${name}\\b[^}]*\\}\\s*from\\s*['"]${from.replace(/[.\\/]/g, '\\$&')}['"]`);
  return re.test(src);
}
function importsModule(src, from) {
  const re = new RegExp(`from\\s*['"]${from.replace(/[.\\/]/g, '\\$&')}['"]`);
  return re.test(src);
}

// ---- AC1 lib modules exist and are pure at import ----

test('S21: every lib module exists as hooks/lib/<name>.mjs', () => {
  for (const m of LIB_MODULES) assert.ok(existsSync(join(LIB_DIR, `${m}.mjs`)), `${m}.mjs`);
});

const scratch = mkdtempSync(join(tmpdir(), 'to-lib-adopt-'));
after(() => rmSync(scratch, { recursive: true, force: true }));

test('S21: importing any lib module performs no I/O: empty stdout/stderr, exit 0, no files in the temp root', () => {
  const tmpRoot = mkdtempSync(join(scratch, 'tmproot-'));
  for (const m of LIB_MODULES) {
    const url = pathToFileURL(join(LIB_DIR, `${m}.mjs`)).href;
    const res = spawnSync(process.execPath, ['--input-type=module', '-e', `await import(${JSON.stringify(url)});`], {
      env: {
        ...process.env,
        TMPDIR: tmpRoot,
        TEMP: tmpRoot,
        TMP: tmpRoot,
        TASK_ORCHESTRATOR_API_URL: 'http://127.0.0.1:9',
      },
      input: '',
      encoding: 'utf-8',
      timeout: 15000,
    });
    assert.equal(res.status, 0, `${m}: ${res.stderr}`);
    assert.equal(res.stdout, '', `${m} wrote to stdout at import`);
    assert.equal(res.stderr, '', `${m} wrote to stderr at import`);
  }
  assert.deepEqual(readdirSync(tmpRoot), [], 'no marker or temp file was created at import');
});

// ---- AC2 stdin and emit adoption ----

test('S21: the 11 stdin-reading hooks import readHookInput from ./lib/stdin.mjs', () => {
  for (const h of STDIN_READERS) {
    assert.ok(importsNamed(hookSource(`${h}.mjs`), 'readHookInput', './lib/stdin.mjs'), `${h}.mjs`);
  }
});

test('S21: no top-level hook file reads fd 0 directly (readFileSync(0 count is 0)', () => {
  for (const f of topLevelHookFiles()) {
    const src = hookSource(f);
    assert.ok(!src.includes('readFileSync(0'), `${f} still has readFileSync(0`);
    assert.ok(!src.includes('/dev/stdin'), `${f} reads /dev/stdin`);
    assert.ok(!src.includes('process.stdin.fd'), `${f} reads process.stdin.fd`);
  }
});

test('S21: every hooks-config.json entrypoint writes its output through ./lib/emit.mjs', () => {
  const cfg = JSON.parse(read(join(HOOKS_DIR, 'hooks-config.json')));
  const entrypoints = new Set();
  for (const groups of Object.values(cfg.hooks)) {
    for (const group of groups) {
      for (const h of group.hooks) {
        const m = /hooks\/([A-Za-z0-9_.-]+)\.mjs/.exec(h.command);
        if (m) entrypoints.add(m[1]);
      }
    }
  }
  for (const required of AC2_EMITTERS) assert.ok(entrypoints.has(required), `${required} is a config entrypoint`);
  for (const ep of entrypoints) {
    const src = hookSource(`${ep}.mjs`);
    assert.ok(importsModule(src, './lib/emit.mjs'), `${ep}.mjs imports ./lib/emit.mjs`);
    assert.ok(!/process\.stdout\.write\s*\(/.test(src), `${ep}.mjs writes stdout directly`);
  }
});

// ---- AC3 marker delegation ----

test('S21: the four marker implementations import from ./lib/markers.mjs', () => {
  for (const h of MARKER_USERS) {
    assert.ok(importsModule(hookSource(`${h}.mjs`), './lib/markers.mjs'), `${h}.mjs`);
  }
});

// ---- AC7 shim ----

test('S21: hooks/api-client.mjs is gone and no top-level hook imports ./api-client.mjs', () => {
  // eb262d39 AC8: the shim is deleted; every importer points at ./lib/api-client.mjs.
  assert.ok(!existsSync(join(HOOKS_DIR, 'api-client.mjs')), 'hooks/api-client.mjs must not exist');
  for (const f of topLevelHookFiles()) {
    assert.ok(!importsModule(hookSource(f), './api-client.mjs'), `${f} imports ./api-client.mjs`);
  }
});

// ---- AC8 / D6 tool names ----

test('S21: tool-names.mjs carries the GENERATED marker and exports ERROR_KINDS, PROFILES, TOOLS, WRITE_TOOLS', async () => {
  assert.ok(
    read(join(LIB_DIR, 'tool-names.mjs')).includes('// GENERATED by scripts/gen-call-shapes.mjs'),
    'GENERATED marker comment',
  );
  const mod = await import('../lib/tool-names.mjs');
  assert.deepEqual(Object.keys(mod).sort(), ['ERROR_KINDS', 'PROFILES', 'TOOLS', 'WRITE_TOOLS']);
});

// ---- D3: server-compat is wired into session-start and, per AC2 (legacyShapeWarningOnce), the two envelope readers ----

test('S21: session-start imports lib/server-compat.mjs; only the envelope readers (dispatch-hint, phase-guard-record) also do', () => {
  assert.ok(importsModule(hookSource('session-start.mjs'), './lib/server-compat.mjs'));
  for (const f of topLevelHookFiles()) {
    if (f === 'session-start.mjs' || f === 'dispatch-hint.mjs' || f === 'phase-guard-record.mjs') continue;
    assert.ok(!hookSource(f).includes('server-compat'), `${f} must not import server-compat (D3)`);
  }
});

// ---- AC9 docs ----

test('S21: hooks/README.md exists and has a row for each lib module', () => {
  const readmePath = join(HOOKS_DIR, 'README.md');
  assert.ok(existsSync(readmePath), 'hooks/README.md');
  const readme = read(readmePath);
  for (const m of LIB_MODULES) {
    assert.ok(readme.includes(`${m}.mjs`), `README names ${m}.mjs`);
  }
});

test('S21: CHANGELOG [4.0.0] has a ### Plugin section with at least one bullet', () => {
  const changelog = read(join(REPO_ROOT, 'CHANGELOG.md'));
  const start = changelog.indexOf('## [4.0.0]');
  assert.ok(start >= 0, '[4.0.0] heading');
  const rest = changelog.slice(start + 1);
  const next = rest.search(/\n## \[/);
  const section = next === -1 ? rest : rest.slice(0, next);
  const idx = section.indexOf('### Plugin');
  assert.ok(idx >= 0, '### Plugin heading inside [4.0.0]');
  const afterHeading = section.slice(idx + '### Plugin'.length);
  const nextHeading = afterHeading.search(/\n### /);
  const body = nextHeading === -1 ? afterHeading : afterHeading.slice(0, nextHeading);
  assert.ok(/^- /m.test(body), 'a bullet under ### Plugin');
});
