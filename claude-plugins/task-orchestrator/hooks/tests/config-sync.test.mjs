// Direct unit coverage for config-sync.mjs's parseRootId — previously only inferable through
// session-start.mjs's subprocess tests (the two parsers are textually identical). Importing the
// module must NOT trigger a live config sync as a side effect — config-sync.mjs guards its
// `main()` invocation behind an entrypoint check (`process.argv[1] === this file`) specifically
// so importing `parseRootId` here stays side-effect free.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseRootId, isTargetConfigPath } from '../config-sync.mjs';

const HOOK = fileURLToPath(new URL('../config-sync.mjs', import.meta.url));

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

function tmpConfigDir() {
  return mkdtempSync(join(tmpdir(), 'to-config-sync-'));
}

// A local, unroutable-in-practice URL so `fetch` fails fast (connection refused) instead of
// hitting a real server — lets tests observe "did main() attempt the sync" without a live API.
const UNREACHABLE_API_URL = 'http://127.0.0.1:1';

function spawnHook(dir, { stdin, hookEventName, filePath } = {}) {
  const input = stdin !== undefined
    ? stdin
    : JSON.stringify(hookEventName ? { hook_event_name: hookEventName, file_path: filePath } : {});
  return spawnSync(process.execPath, [HOOK], {
    input,
    env: {
      ...process.env,
      AGENT_CONFIG_DIR: dir,
      TASK_ORCHESTRATOR_API_URL: UNREACHABLE_API_URL,
    },
    encoding: 'utf-8',
    cwd: dir, // avoid the cwd-walk fallback finding this repo's real config.yaml
  });
}

test('parseRootId: resolves rootId from a project: block', () => {
  const content = 'project:\n  rootId: "abc-123"\n  name: "X"\n';
  assert.equal(parseRootId(content), 'abc-123');
});

test('parseRootId: tolerates a column-0 comment above rootId', () => {
  const content = [
    'project:',
    '# a stray column-0 comment sitting right above rootId',
    '  rootId: "root-comment-check"',
  ].join('\n');
  assert.equal(parseRootId(content), 'root-comment-check');
});

test('parseRootId: null when project: block is absent', () => {
  assert.equal(parseRootId('retrospective:\n  mode: nudge\n'), null);
  assert.equal(parseRootId(null), null);
});

test('parseRootId: block-only — ignores an inline project: { ... } form', () => {
  assert.equal(parseRootId('project: { rootId: abc }\n'), null);
});

test('isTargetConfigPath: matches forward-slash and backslash forms of the real config path', () => {
  assert.ok(isTargetConfigPath('/project/.taskorchestrator/config.yaml'));
  assert.ok(isTargetConfigPath('C:\\project\\.taskorchestrator\\config.yaml'));
});

test('isTargetConfigPath: rejects unrelated paths, including a differently-located config.yaml', () => {
  assert.ok(!isTargetConfigPath('foo/config.json'));
  assert.ok(!isTargetConfigPath('some/other/config.yaml')); // config.yaml, but not under .taskorchestrator
  assert.ok(!isTargetConfigPath(undefined));
  assert.ok(!isTargetConfigPath(null));
  assert.ok(!isTargetConfigPath(''));
});

test('FileChanged with an unrelated file_path exits silently without attempting a sync', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-unrelated"\n');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath: 'foo/config.json' });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, ''); // guard returned before any emit() — no network attempt happened
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('FileChanged with a config.yaml that is not under .taskorchestrator/ also exits silently', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-wrong-location"\n');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath: 'some/other/config.yaml' });
    assert.equal(res.status, 0);
    assert.equal(res.stdout, '');
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('FileChanged for the real config.yaml (forward-slash path) proceeds through sync logic', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-match-fwd"\n');
    const filePath = join(dir, '.taskorchestrator', 'config.yaml').replace(/\\/g, '/');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('API unreachable'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('FileChanged for the real config.yaml (backslash path) proceeds through sync logic', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-filechanged-match-back"\n');
    const filePath = join(dir, '.taskorchestrator', 'config.yaml').replace(/\//g, '\\');
    const res = spawnHook(dir, { hookEventName: 'FileChanged', filePath });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('API unreachable'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('SessionStart invocations proceed through sync logic unchanged', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-sessionstart-unchanged"\n');
    const res = spawnHook(dir, { hookEventName: 'SessionStart' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('API unreachable'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: missing/garbage stdin still behaves like today (proceeds through sync logic)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-garbage-stdin"\n');
    const res = spawnHook(dir, { stdin: '{not valid json' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('API unreachable'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('fail-open: empty stdin still behaves like today (proceeds through sync logic)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: "root-empty-stdin"\n');
    const res = spawnHook(dir, { stdin: '' });
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('API unreachable'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
