import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const HOOK = fileURLToPath(new URL('../session-start.mjs', import.meta.url));

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

function runHook(agentConfigDir) {
  return spawnSync(process.execPath, [HOOK], {
    env: { ...process.env, AGENT_CONFIG_DIR: agentConfigDir },
    encoding: 'utf-8',
    cwd: agentConfigDir, // avoid the cwd-walk fallback finding this repo's real config.yaml
  });
}

function tmpConfigDir() {
  return mkdtempSync(join(tmpdir(), 'to-session-start-'));
}

test('no config discoverable -> base guidance only, no Project Scope section', () => {
  const dir = tmpConfigDir();
  try {
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Task Orchestrator — Session Context'));
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('## Project Scope'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('config present but no project: block -> not-project-scoped guidance', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'retrospective:\n  mode: nudge\n');
    const res = runHook(dir);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('not project-scoped'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('project block with a column-0 comment above rootId still resolves (parser bug fix)', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, [
      'project:',
      '# a stray column-0 comment sitting right above rootId',
      '  rootId: "root-comment-fix-check"',
      '  name: "Comment Fix Check"',
      '',
    ].join('\n'));
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('root-comment-fix-check'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Comment Fix Check'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('project block with only rootId (no name) still resolves', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: bare-root-id\n');
    const res = runHook(dir);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('bare-root-id'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
