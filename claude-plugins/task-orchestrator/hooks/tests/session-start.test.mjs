import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, isAbsolute } from 'node:path';

const HOOK = fileURLToPath(new URL('../session-start.mjs', import.meta.url));

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

function runHook(agentConfigDir, extraEnv = {}) {
  // Isolate HOME/USERPROFILE (os.homedir() honors both) so the registration self-check reads a
  // controlled ~/.claude.json instead of the developer's real one — otherwise results become
  // machine-dependent. Tests that want a specific ~/.claude.json pass homeDir via extraEnv.
  const homeDir = extraEnv.homeDir || agentConfigDir;
  return spawnSync(process.execPath, [HOOK], {
    env: {
      ...process.env,
      AGENT_CONFIG_DIR: agentConfigDir,
      HOME: homeDir,
      USERPROFILE: homeDir,
      CLAUDE_CONFIG_DIR: '',
      ...extraEnv.env,
    },
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

test('config found -> watchPaths present, absolute, and points at the found config.yaml', () => {
  const dir = tmpConfigDir();
  try {
    writeConfig(dir, 'project:\n  rootId: bare-root-id\n');
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    const expectedPath = join(dir, '.taskorchestrator', 'config.yaml');
    assert.deepEqual(out.hookSpecificOutput.watchPaths, [expectedPath]);
    assert.ok(isAbsolute(out.hookSpecificOutput.watchPaths[0]));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('no config discoverable -> no watchPaths key at all', () => {
  const dir = tmpConfigDir();
  try {
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(!('watchPaths' in out.hookSpecificOutput));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

// ─────────────────────────────────────────────────────────────────────────
// Registration self-check
// ─────────────────────────────────────────────────────────────────────────

function writeUserClaudeJson(homeDir, content) {
  writeFileSync(join(homeDir, '.claude.json'), JSON.stringify(content), 'utf-8');
}

function writeProjectMcpJson(dir, content) {
  writeFileSync(join(dir, '.mcp.json'), JSON.stringify(content), 'utf-8');
}

test('offending key in ~/.claude.json mcpServers -> Hook Registration Check warns', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, {
      mcpServers: {
        tasks: { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('tasks'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('compliant key mcp-task-orchestrator -> no Hook Registration Check section', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, {
      mcpServers: {
        'mcp-task-orchestrator': { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('offending HTTP entry in project .mcp.json -> warned', () => {
  const dir = tmpConfigDir();
  try {
    writeProjectMcpJson(dir, {
      mcpServers: {
        tasks: { type: 'http', url: 'http://host/task-orchestrator/mcp' },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('tasks'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('offending entry under ~/.claude.json projects[cwd].mcpServers -> warned', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, {
      projects: {
        [dir]: {
          mcpServers: {
            tasks: { command: 'docker', args: ['run', 'ghcr.io/jpicklyk/task-orchestrator:latest'] },
          },
        },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
    assert.ok(out.hookSpecificOutput.additionalContext.includes('tasks'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('malformed ~/.claude.json -> exit 0, base guidance, no Hook Registration Check', () => {
  const dir = tmpConfigDir();
  try {
    writeFileSync(join(dir, '.claude.json'), '{ not valid json', 'utf-8');
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(out.hookSpecificOutput.additionalContext.includes('Task Orchestrator — Session Context'));
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('unrelated server entry -> not flagged', () => {
  const dir = tmpConfigDir();
  try {
    writeUserClaudeJson(dir, {
      mcpServers: {
        memory: { command: 'npx', args: ['@foo/memory'] },
      },
    });
    const res = runHook(dir);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.ok(!out.hookSpecificOutput.additionalContext.includes('Hook Registration Check'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
