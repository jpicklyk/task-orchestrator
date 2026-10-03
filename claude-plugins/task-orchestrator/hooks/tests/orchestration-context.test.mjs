import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const HOOK = fileURLToPath(new URL('../orchestration-context.mjs', import.meta.url));
const HOOKS_CONFIG = fileURLToPath(new URL('../hooks-config.json', import.meta.url));

const tracked = [];
function tmp(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix));
  tracked.push(d);
  return d;
}
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

function run(config, extraEnv = {}) {
  const dir = tmp('to-orch-ctx-');
  if (config !== null) writeConfig(dir, config);
  const toHome = tmp('to-orch-ctx-home-');
  const tmpDir = tmp('to-orch-ctx-tmp-');
  const r = spawnSync(process.execPath, [HOOK], {
    env: {
      ...process.env,
      AGENT_CONFIG_DIR: dir,
      HOME: dir,
      USERPROFILE: dir,
      CLAUDE_CONFIG_DIR: '',
      TASK_ORCHESTRATOR_HOME: toHome,
      TASK_ORCHESTRATOR_MODE: '',
      TEMP: tmpDir,
      TMP: tmpDir,
      TMPDIR: tmpDir,
      ...extraEnv,
    },
    encoding: 'utf-8',
    cwd: dir,
  });
  assert.equal(r.status, 0, r.stderr);
  const out = JSON.parse(r.stdout);
  return out;
}

function ctxOf(out) {
  assert.equal(out.hookSpecificOutput.hookEventName, 'SessionStart');
  const c = out.hookSpecificOutput.additionalContext;
  assert.ok(c.length < 9000, `additionalContext too long: ${c.length}`);
  return c;
}

const BASE = 'project:\n  rootId: 11111111-1111-1111-1111-111111111111\n';

test('no config -> {}', () => {
  assert.deepEqual(run(null), {});
});

test('headless iteration -> {}', () => {
  assert.deepEqual(run(BASE, { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }), {});
});

test('mode off (block and inline) -> {}', () => {
  assert.deepEqual(run(`${BASE}orchestration:\n  mode: off\n`), {});
  assert.deepEqual(run(`${BASE}orchestration: { mode: off }\n`), {});
});

test('absent block and invalid mode -> workflow variant', () => {
  assert.match(ctxOf(run(BASE)), /task-orchestrator:orchestrate/);
  assert.match(ctxOf(run(`${BASE}orchestration:\n  mode: bogus\n`)), /task-orchestrator:orchestrate/);
});

test('config without project.rootId still injects', () => {
  assert.match(ctxOf(run('orchestration:\n  mode: workflow\n')), /task-orchestrator:orchestrate/);
});

test('schema variant differs: no tier sizing route, but phase-owner dispatch still routes to orchestrate', () => {
  const schema = ctxOf(run(`${BASE}orchestration:\n  mode: schema\n`));
  const workflow = ctxOf(run(BASE));
  assert.match(schema, /task-orchestrator:schema-workflow/);
  assert.ok(!schema.includes('Before sizing or dispatching implementation work'));
  assert.ok(!schema.includes('Direct work you implement yourself'));
  assert.match(schema, /phase owner[^\n]*`task-orchestrator:orchestrate`/);
  assert.match(workflow, /Before sizing or dispatching implementation work \| `task-orchestrator:orchestrate`/);
  assert.notEqual(schema, workflow);
});

test('both variants carry the core rules and omit excluded sections', () => {
  for (const cfg of [BASE, `${BASE}orchestration:\n  mode: schema\n`]) {
    const c = ctxOf(run(cfg));
    for (const s of ['resource_unavailable', 'orchestrator-confirmation', 'task-orchestrator:run-wave', 'task-orchestrator:create-item', 'explicitly']) {
      assert.ok(c.includes(s), `missing ${s}`);
    }
    assert.ok(!c.includes('## Project Scope'));
    assert.ok(!c.includes('| Criteria | Tier |'));
    assert.ok(!c.includes('GENERATED:tier-classification'));
  }
});

test('hooks-config.json lists orchestration-context.mjs exactly once in SessionStart', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf-8'));
  const cmds = cfg.hooks.SessionStart.flatMap((g) => g.hooks.map((h) => h.command));
  assert.equal(cmds.filter((c) => c.includes('orchestration-context.mjs')).length, 1);
  assert.equal(cfg.hooks.SessionStart.length, 1);
});
